<#
.SYNOPSIS
    StarShow 链路二（Phase 2）双形态原始发行包隔离数据 E2E 实证验收脚本。
.DESCRIPTION
    严格隔离测试数据，绝不触碰默认 starshow MySQL 库与 Redis DB 0：
      - 自动校验隔离配置：拒绝 TestDatabase=starshow 或 TestRedisDb=0，并在写入前校验目标 Redis 测试库为空（DBSIZE == 0）；
      - 在默认 starshow 库与 Redis DB 0 预置哨兵并在验收后验证 100% 完好；
      - 仅创建本轮专属隔离 MySQL 库并导入仓库最新 01_schema.sql + 02_seed.sql；
      - 支持 -Mode monolith（boot-all + gateway）与 -Mode distributed（show-exec.jar 零补丁 + user-exec.jar OpenFeign + gateway）；
      - 验证公网零写路由、稳定游标与城市筛选、Long 字符串序列化、10002 10x10 MSB 座位图与四态（PREPARING/503 B0201/SOLD_OUT/ENDED）、
        10003 跨链路改期自动联动通知与毫秒级开售入口一致性；
      - 无论成功或失败，finally 均停止所有 Java 进程并销毁本轮隔离库与隔离 Redis DB。
#>
param(
    [ValidateSet("monolith", "distributed")]
    [string]$Mode = "monolith",
    [switch]$VerifyMissingSecrets,
    [switch]$FromPhase1Schema,
    [string]$MysqlContainer = "starshow-mysql",
    [string]$RedisContainer = "starshow-redis",
    [string]$TestDatabase = "",
    [int]$TestRedisDb = 13
)

$ErrorActionPreference = "Stop"

# --- 1. 强校验安全凭据（零硬编码） ---
$requiredEnv = @(
    "MYSQL_PASSWORD",
    "REDIS_PASSWORD",
    "STARSHOW_JWT_SECRET",
    "STARSHOW_AES_KEY",
    "STARSHOW_IDCARD_PEPPER",
    "STARSHOW_INTERNAL_GATEWAY_SECRET"
)
foreach ($name in $requiredEnv) {
    $val = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($val)) {
        throw "Required environment variable '$name' is not set. Provide independent non-placeholder values before running."
    }
}

# --- 2. 强校验测试数据隔离边界 ---
if ([string]::IsNullOrWhiteSpace($TestDatabase)) {
    $TestDatabase = "starshow_c2_${Mode}_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}
if ($TestDatabase -match "^(?i)starshow$" -or $TestDatabase -notmatch "^[a-zA-Z0-9_]+$") {
    throw "Refusing unsafe TestDatabase='$TestDatabase'. Must be an isolated non-default database name matching ^[a-zA-Z0-9_]+$."
}
if ($TestRedisDb -lt 1 -or $TestRedisDb -gt 15) {
    throw "Refusing unsafe TestRedisDb=$TestRedisDb. Must be an isolated Redis DB index in 1..15 (never DB 0)."
}

$existingDbCount = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$TestDatabase';").Trim()
if ($existingDbCount -ne "0") {
    throw "Refusing to reuse pre-existing MySQL database '$TestDatabase'."
}
$existingRedisSize = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb DBSIZE).Trim()
if ($existingRedisSize -ne "0") {
    throw "Refusing to use non-empty Redis DB $TestRedisDb (DBSIZE=$existingRedisSize)."
}
Write-Output "[PASS] Isolation precheck passed: TestDatabase=$TestDatabase (fresh), TestRedisDb=$TestRedisDb (DBSIZE=0)"

$mysqlPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "13306" }
$redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
$redisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "6379" }
$rmqNameSrv = if ($env:ROCKETMQ_NAME_SERVER) { $env:ROCKETMQ_NAME_SERVER } else { "127.0.0.1:9876" }

function Invoke-CurlApi {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Body = $null,
        [hashtable]$Headers = @{}
    )
    $tmpBody = [System.IO.Path]::GetTempFileName()
    $tmpHdr = [System.IO.Path]::GetTempFileName()
    $tmpReq = $null
    try {
        $argsList = @("-s", "-X", $Method, "-D", $tmpHdr, "-o", $tmpBody, "-w", "%{http_code}", "-H", "Content-Type: application/json; charset=utf-8")
        foreach ($k in $Headers.Keys) {
            $argsList += @("-H", "$k`: $($Headers[$k])")
        }
        if ($null -ne $Body -and $Body -ne "") {
            $tmpReq = [System.IO.Path]::GetTempFileName()
            [System.IO.File]::WriteAllText($tmpReq, $Body, (New-Object System.Text.UTF8Encoding($false)))
            $argsList += @("--data-binary", "@$tmpReq")
        }
        $argsList += $Url
        $statusCode = (& curl.exe @argsList).Trim()
        $respBody = [System.IO.File]::ReadAllText($tmpBody, [System.Text.Encoding]::UTF8)
        $hdrText = [System.IO.File]::ReadAllText($tmpHdr, [System.Text.Encoding]::UTF8)
        return [PSCustomObject]@{
            StatusCode = [int]$statusCode
            Body = $respBody
            HeadersText = $hdrText
        }
    } finally {
        if ($tmpReq -and (Test-Path $tmpReq)) { Remove-Item $tmpReq -Force }
        if (Test-Path $tmpBody) { Remove-Item $tmpBody -Force }
        if (Test-Path $tmpHdr) { Remove-Item $tmpHdr -Force }
    }
}

function Assert-Eq($actual, $expected, $msg) {
    if ("$actual" -ne "$expected") {
        throw "ASSERTION FAILED [$msg]: expected=<$expected>, actual=<$actual>"
    }
    Write-Output "[PASS] $msg => $actual"
}

function Assert-True($cond, $msg) {
    if (-not $cond) {
        throw "ASSERTION FAILED [$msg]"
    }
    Write-Output "[PASS] $msg"
}

function Assert-PortsFree([int[]]$ports) {
    foreach ($p in $ports) {
        $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
        if ($busy) {
            throw "Port $p is already in use by PID $($busy[0].OwningProcess)."
        }
    }
}

function Wait-Health([string]$url, [int]$maxSeconds = 40, $ownerProc = $null) {
    for ($i = 0; $i -lt $maxSeconds; $i++) {
        if ($ownerProc -and $ownerProc.HasExited) {
            throw "Service process exited prematurely (exit=$($ownerProc.ExitCode)) while waiting for $url"
        }
        try {
            $r = Invoke-CurlApi -Method GET -Url $url
            if ($r.StatusCode -eq 200) {
                Write-Output "[PASS] Health UP: $url"
                return
            }
        } catch {}
        Start-Sleep -Seconds 1
    }
    throw "Timed out waiting for health endpoint: $url"
}

function Test-MissingSecretRejection([string]$jarPath, [string]$extraArgs, [string]$expectedPropName) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "java"
    $psi.Arguments = "-jar `"$jarPath`" $extraArgs"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($k in @("STARSHOW_JWT_SECRET", "STARSHOW_AES_KEY", "STARSHOW_IDCARD_PEPPER", "STARSHOW_INTERNAL_GATEWAY_SECRET")) {
        $psi.EnvironmentVariables.Remove($k)
    }
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $exited = $proc.WaitForExit(25000)
    if (-not $exited) {
        $proc.Kill()
        throw "Expected $jarPath to fail fast when secrets are missing!"
    }
    $combined = $outTask.Result + "`n" + $errTask.Result
    if ($proc.ExitCode -eq 0 -or $combined -notmatch [regex]::Escape($expectedPropName)) {
        throw "Expected non-zero startup failure mentioning '$expectedPropName' for $jarPath (exit=$($proc.ExitCode))"
    }
    Write-Output "[PASS] Missing-secret startup rejection: $jarPath (mentions $expectedPropName)"
}

function Build-SampleIdCard([string]$first17) {
    $weights = @(7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2)
    $codes = @('1','0','X','9','8','7','6','5','4','3','2')
    $sum = 0
    for ($i = 0; $i -lt 17; $i++) { $sum += ([int][string]$first17[$i]) * $weights[$i] }
    return $first17 + $codes[$sum % 11]
}

if ($VerifyMissingSecrets) {
    Write-Output "`n=== Verifying Missing-Secret Rejection on Distribution Jars ==="
    Test-MissingSecretRejection "starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=0" "starshow.security.internal-gateway-secret"
    Test-MissingSecretRejection "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "--server.port=0" "starshow.security.jwt-secret"
}

$javaExe = "java"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
}

$procs = @()
$createdDatabase = $false
$sentinelRedisKey = "starshow:sentinel:c2e2e:${Mode}:" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$sentinelRedisVal = "INTACT_" + [guid]::NewGuid().ToString("N")
$defaultShowSnapshotBefore = ""
$logDir = Join-Path $env:TEMP ("starshow-c2-e2e-" + $Mode + "-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Start-ServiceJar([string]$name, [string]$jarPath, [string]$argsStr, [hashtable]$extraEnv = @{}) {
    $outLog = Join-Path $logDir "$name.out.log"
    $errLog = Join-Path $logDir "$name.err.log"
    $saved = @{}
    foreach ($k in $extraEnv.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, $extraEnv[$k])
    }
    try {
        $p = Start-Process -FilePath $javaExe -ArgumentList "-jar `"$jarPath`" $argsStr" -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -NoNewWindow
        return [PSCustomObject]@{ Name = $name; Proc = $p; OutLog = $outLog; ErrLog = $errLog }
    } finally {
        foreach ($k in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($k, $saved[$k])
        }
    }
}

try {
    # --- 3. 预置默认库哨兵（证明全程绝不污染默认库与 Redis DB 0） ---
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 SET $sentinelRedisKey $sentinelRedisVal EX 600 | Out-Null
    $defaultShowSnapshotBefore = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D starshow -e "SELECT COUNT(*), IFNULL(SUM(id),0) FROM t_show;").Trim()
    Write-Output "[PASS] Placed default DB & Redis DB 0 sentinels (defaultShowSnapshot='$defaultShowSnapshotBefore')"

    # --- 4. 创建本轮隔离库并导入 Schema & Seed（支持全新建库或从 Phase 1 存量库平滑无损升级） ---
    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "CREATE DATABASE ``$TestDatabase`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    $createdDatabase = $true
    if ($FromPhase1Schema) {
        Write-Output "=== Initializing Phase 1 Legacy Schema (050eb5e) + Existing User/Reservation Data & Running V2 Migration ==="
        $p1Schema = ((git show "050eb5e:docker/mysql/init/01_schema.sql") -join "`n") -replace "(?i)CREATE DATABASE IF NOT EXISTS\s+``?starshow``?[^;]*;", "" -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
        $p1Seed = ((git show "050eb5e:docker/mysql/init/02_seed.sql") -join "`n") -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
        $p1Schema | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase
        $p1Seed | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase

        # 写入模拟存量 Phase 1 用户、实名与预约记录，验证升级全程零丢数据
        docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -D $TestDatabase -e "INSERT INTO t_user(id, phone, password_hash, nickname) VALUES (880001, '13800008801', 'legacy-bcrypt-hash', 'LegacyFan'); INSERT INTO t_reservation(id, user_id, show_id, remind_status, remind_config_version) VALUES (880101, 880001, 10003, 3, 1);"
        $hasTaskBefore = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TestDatabase' AND table_name='t_show_task';").Trim()
        Assert-Eq $hasTaskBefore "0" "Legacy Phase 1 DB indeed lacks t_show_task before migration"

        # 连续执行两遍 V2 迁移脚本，验证无损升级与 100% 幂等可重入
        $migSql = Get-Content -Raw -Encoding UTF8 "docker/mysql/migrations/V2__phase2_show_inventory.sql"
        $migSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase
        $migSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase

        $legacyUserKept = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM t_user WHERE id=880001;").Trim()
        $legacyRsvKept = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM t_reservation WHERE id=880101 AND user_id=880001 AND show_id=10003;").Trim()
        $migratedSegs = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM t_seat WHERE show_id=10002 AND zone_id='HALL_A' AND segment_id LIKE 'R%-%';").Trim()
        Assert-Eq $legacyUserKept "1" "Legacy Phase 1 user preserved 100% after V2 migration"
        Assert-Eq $legacyRsvKept "1" "Legacy Phase 1 reservation preserved 100% after V2 migration"
        Assert-Eq $migratedSegs "100" "Legacy 10002 HALL_A 100 seats backfilled with real aisle segment_id"
    } else {
        $schemaSql = (Get-Content -Raw -Encoding UTF8 "docker/mysql/init/01_schema.sql") -replace "(?i)CREATE DATABASE IF NOT EXISTS\s+``?starshow``?[^;]*;", "" -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
        $seedSql = (Get-Content -Raw -Encoding UTF8 "docker/mysql/init/02_seed.sql") -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
        $schemaSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase
        $seedSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase
    }

    $tableCount = [int](docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TestDatabase';").Trim()
    Assert-True ($tableCount -ge 20) "Isolated DB '$TestDatabase' initialized with >= 20 physical tables (actual=$tableCount)"
    $hasShowTask = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TestDatabase' AND table_name='t_show_task';").Trim()
    $hasSegmentCol = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$TestDatabase' AND table_name='t_seat' AND column_name='segment_id';").Trim()
    Assert-Eq $hasShowTask "1" "Isolated DB contains t_show_task"
    Assert-Eq $hasSegmentCol "1" "Isolated DB contains t_seat.segment_id"

    # 将 10002 重置为未开售 INIT 便于完整实测 PREPARING -> OPEN -> 503 B0201 -> SOLD_OUT -> ENDED
    $futureStart = (Get-Date).AddHours(2).ToString("yyyy-MM-dd HH:mm:ss")
    $futureEnd = (Get-Date).AddDays(10).ToString("yyyy-MM-dd HH:mm:ss")
    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -D $TestDatabase -e "UPDATE t_show SET sale_status='INIT', sale_config_version=1, sale_start_time='$futureStart', sale_end_time='$futureEnd' WHERE id=10002;"

    $commonEnv = @{
        "MYSQL_PORT" = $mysqlPort
        "MYSQL_DATABASE" = $TestDatabase
        "REDIS_HOST" = $redisHost
        "REDIS_PORT" = $redisPort
        "REDIS_DATABASE" = "$TestRedisDb"
        "ROCKETMQ_NAME_SERVER" = $rmqNameSrv
        "STARSHOW_TEST_OVERRIDE_DELAY_MS" = "3000"
        "STARSHOW_SHOW_LIFECYCLE_SYNC_ENABLED" = "false"
        "STARSHOW_SHOW_TASK_RECOVERY_ENABLED" = "false"
    }

    if ($Mode -eq "monolith") {
        Assert-PortsFree @(8000, 8090)
        $bootItem = Start-ServiceJar "boot-all" "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" "" $commonEnv
        $procs += $bootItem
        $gwEnv = $commonEnv.Clone()
        $gwEnv["USER_SERVICE_URI"] = "http://127.0.0.1:8090"
        $gwEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8090"
        $gwItem = Start-ServiceJar "gateway" "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "" $gwEnv
        $procs += $gwItem

        Wait-Health "http://127.0.0.1:8090/actuator/health" 40 $bootItem.Proc
        Wait-Health "http://127.0.0.1:8000/actuator/health" 40 $gwItem.Proc
        $directShowUrl = "http://127.0.0.1:8090"
    } else {
        Assert-PortsFree @(8000, 8001, 8002)
        $showEnv = $commonEnv.Clone()
        $showEnv["USER_SERVICE_URI"] = "http://127.0.0.1:8001"
        $showItem = Start-ServiceJar "show" "starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed" $showEnv
        $procs += $showItem

        $userEnv = $commonEnv.Clone()
        $userEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8002"
        $userItem = Start-ServiceJar "user" "starshow-user/target/starshow-user-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed" $userEnv
        $procs += $userItem

        $gwEnv = $commonEnv.Clone()
        $gwEnv["USER_SERVICE_URI"] = "http://127.0.0.1:8001"
        $gwEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8002"
        $gwItem = Start-ServiceJar "gateway" "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "" $gwEnv
        $procs += $gwItem

        Wait-Health "http://127.0.0.1:8002/actuator/health" 40 $showItem.Proc
        Wait-Health "http://127.0.0.1:8001/actuator/health" 40 $userItem.Proc
        Wait-Health "http://127.0.0.1:8000/actuator/health" 40 $gwItem.Proc
        $directShowUrl = "http://127.0.0.1:8002"
    }

    $gwUrl = "http://127.0.0.1:8000"
    $internalHdrs = @{ "X-Internal-Gateway-Secret" = $env:STARSHOW_INTERNAL_GATEWAY_SECRET }

    # --- 5. 安全隔离门禁：公网零写路由 + 内部密钥校验 ---
    Write-Output "`n=== [Step 1] Gateway Public Read-Only & Internal Secret Isolation ==="
    $gwInternalBlock = Invoke-CurlApi -Method POST -Url "$gwUrl/internal/shows/10002/preheat" -Body "{}"
    Assert-True ($gwInternalBlock.StatusCode -eq 401 -or $gwInternalBlock.StatusCode -eq 404) "Gateway blocks /internal/shows/** (status=$($gwInternalBlock.StatusCode))"
    $gwWriteBlock = Invoke-CurlApi -Method POST -Url "$gwUrl/api/v1/shows/10002" -Body "{}"
    Assert-True ($gwWriteBlock.StatusCode -eq 401 -or $gwWriteBlock.StatusCode -eq 404 -or $gwWriteBlock.StatusCode -eq 405) "Gateway blocks non-GET /api/v1/shows/** (status=$($gwWriteBlock.StatusCode))"
    $unauthInternal = Invoke-CurlApi -Method POST -Url "$directShowUrl/internal/shows/10002/preheat" -Body "{}"
    Assert-Eq $unauthInternal.StatusCode "401" "Internal /preheat rejects missing X-Internal-Gateway-Secret"

    # --- 6. 节点 0：稳定游标分页、城市筛选、Long 字符串序列化、未知场次 ---
    Write-Output "`n=== [Step 2] Show List Cursor Pagination & Detail Long-as-String ==="
    $page1 = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows?limit=2"
    Assert-Eq $page1.StatusCode "200" "GET /api/v1/shows?limit=2"
    $p1Json = $page1.Body | ConvertFrom-Json
    Assert-Eq $p1Json.data.items.Count "2" "Page 1 returns 2 items"
    Assert-True (-not [string]::IsNullOrWhiteSpace($p1Json.data.nextCursor)) "Page 1 returns non-empty nextCursor"

    $encodedCursor = [Uri]::EscapeDataString($p1Json.data.nextCursor)
    $page2 = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows?limit=2&cursor=$encodedCursor"
    Assert-Eq $page2.StatusCode "200" "GET /api/v1/shows page 2"
    $p2Json = $page2.Body | ConvertFrom-Json
    Assert-Eq $p2Json.data.items.Count "1" "Page 2 returns remaining 1 item"
    Assert-True ($null -eq $p2Json.data.nextCursor) "Page 2 ends pagination with null nextCursor"

    # 游标绑定城市筛选校验
    $badCityCursor = Invoke-CurlApi -Method GET -Url ("$gwUrl/api/v1/shows?city=" + [Uri]::EscapeDataString("北京") + "&cursor=$encodedCursor")
    Assert-Eq $badCityCursor.StatusCode "400" "Cross-city cursor rejected with 400"

    $bjList = Invoke-CurlApi -Method GET -Url ("$gwUrl/api/v1/shows?city=" + [Uri]::EscapeDataString("北京"))
    $bjJson = $bjList.Body | ConvertFrom-Json
    Assert-Eq $bjJson.data.items.Count "1" "City filter returns 1 Beijing show"
    Assert-Eq $bjJson.data.items[0].showId "10002" "Beijing show is 10002"

    $unknownShow = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/999999"
    Assert-Eq (($unknownShow.Body | ConvertFrom-Json).code) "A0201" "Unknown show returns A0201"

    # 验证 Long ID 与金额 priceCent 均为 JSON 字符串
    $detail10002Raw = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002"
    Assert-Eq $detail10002Raw.StatusCode "200" "GET /api/v1/shows/10002"
    Assert-True ($detail10002Raw.Body -match '"showId"\s*:\s*"10002"') "showId serialized as JSON string"
    Assert-True ($detail10002Raw.Body -match '"tierId"\s*:\s*"20004"') "tierId serialized as JSON string"
    Assert-True ($detail10002Raw.Body -match '"priceCent"\s*:\s*"88000"') "priceCent serialized as JSON string"

    # --- 7. 节点 1 & 2：10002 预热、毫秒级准点开放、10x10 MSB 座位图与四态实证 ---
    Write-Output "`n=== [Step 3] Show 10002 Preheat, MSB 10x10 SeatMap & Four-State Isolation ==="
    $preheat1 = Invoke-CurlApi -Method POST -Url "$directShowUrl/internal/shows/10002/preheat" -Body '{"saleConfigVersion":1}' -Headers $internalHdrs
    Assert-Eq $preheat1.StatusCode "200" "Preheat 10002 v1 succeeds"
    $ph1Data = ($preheat1.Body | ConvertFrom-Json).data
    Assert-Eq $ph1Data.taskStatus "READY" "Preheat 10002 v1 status READY"

    # 幂等重放同版本预热
    $preheat1Dup = Invoke-CurlApi -Method POST -Url "$directShowUrl/internal/shows/10002/preheat" -Body '{"saleConfigVersion":1}' -Headers $internalHdrs
    Assert-Eq (($preheat1Dup.Body | ConvertFrom-Json).data.executionId) $ph1Data.executionId "Repeat v1 preheat is idempotent"

    # 态 1：PREPARING
    $stockPrep = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/stock"
    Assert-Eq (($stockPrep.Body | ConvertFrom-Json).data.entryState) "PREPARING" "Before saleStartTime stock returns PREPARING"

    # 改期 10002 到当前已开售窗口（v1->v2）并预热 v2 -> 毫秒级立即开放 OPEN（不依赖定时任务！）
    $pastStartIso = (Get-Date).AddSeconds(-3).ToString("yyyy-MM-ddTHH:mm:ss")
    $futureEndIso = (Get-Date).AddHours(4).ToString("yyyy-MM-ddTHH:mm:ss")
    $resched10002 = Invoke-CurlApi -Method PUT -Url "$directShowUrl/internal/shows/10002/schedule" -Body "{`"saleStartTime`":`"$pastStartIso`",`"saleEndTime`":`"$futureEndIso`"}" -Headers $internalHdrs
    Assert-Eq (($resched10002.Body | ConvertFrom-Json).data.saleConfigVersion) "2" "10002 rescheduled to v2"

    $preheat2 = Invoke-CurlApi -Method POST -Url "$directShowUrl/internal/shows/10002/preheat" -Body '{"saleConfigVersion":2}' -Headers $internalHdrs
    Assert-Eq (($preheat2.Body | ConvertFrom-Json).data.taskStatus) "READY" "10002 v2 preheated READY"

    # 模拟占用 offset 0, 7, 99 并扣减库存为 97
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SETBIT "starshow:seat:bitmap:{10002}:HALL_A" 0 1 | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SETBIT "starshow:seat:bitmap:{10002}:HALL_A" 7 1 | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SETBIT "starshow:seat:bitmap:{10002}:HALL_A" 99 1 | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SET "starshow:stock:{10002}:20004" 97 | Out-Null

    $seatResp = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/zones/HALL_A/seats"
    Assert-Eq $seatResp.StatusCode "200" "GET /api/v1/shows/10002/zones/HALL_A/seats"
    $seatData = ($seatResp.Body | ConvertFrom-Json).data
    Assert-Eq $seatData.stockState "OPEN" "10002 opens immediately at millisecond boundary (stage=READY)"
    Assert-Eq $seatData.seats.Count "100" "10002 HALL_A returns 10x10=100 seats"
    $selectableCount = @($seatData.seats | Where-Object { $_.selectable -eq $true }).Count
    Assert-Eq $selectableCount "97" "MSB bitmap decoded accurately: 97 selectable, 3 occupied"
    $s0 = $seatData.seats | Where-Object { $_.seatIndex -eq 0 } | Select-Object -First 1
    $s3 = $seatData.seats | Where-Object { $_.seatIndex -eq 3 } | Select-Object -First 1
    $s99 = $seatData.seats | Where-Object { $_.seatIndex -eq 99 } | Select-Object -First 1
    Assert-Eq $s0.selectable "False" "Seat 0 occupied"
    Assert-Eq $s0.segmentId "R1-L" "Seat 0 aisle segment R1-L"
    Assert-Eq $s3.segmentId "R1-M" "Seat 3 aisle segment R1-M"
    Assert-Eq $s99.selectable "False" "Seat 99 occupied"
    Assert-Eq $s99.segmentId "R10-R" "Seat 99 aisle segment R10-R"

    # 内部票档快照接口校验
    $tierSnap = Invoke-CurlApi -Method GET -Url "$directShowUrl/internal/shows/10002/tiers/20004/snapshot" -Headers $internalHdrs
    Assert-Eq $tierSnap.StatusCode "200" "GET /internal/shows/10002/tiers/20004/snapshot"
    $tierSnapData = ($tierSnap.Body | ConvertFrom-Json).data
    Assert-Eq $tierSnapData.saleConfigVersion "2" "Tier snapshot carries saleConfigVersion=2"
    Assert-Eq $tierSnapData.layoutVersion "2" "Tier snapshot carries layoutVersion=2"
    Assert-Eq $tierSnapData.maxTicketsPerOrder "4" "Tier snapshot carries maxTicketsPerOrder=4"

    # 态 2：在售期缺键 -> 503/B0201（严禁误报 SOLD_OUT）
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb DEL "starshow:stock:{10002}:20004" | Out-Null
    $missingStockResp = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/stock"
    Assert-Eq $missingStockResp.StatusCode "503" "Missing stock key during sale returns HTTP 503"
    Assert-Eq (($missingStockResp.Body | ConvertFrom-Json).code) "B0201" "Missing stock key returns code B0201"

    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SET "starshow:stock:{10002}:20004" 97 | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb DEL "starshow:seat:bitmap:{10002}:HALL_A" | Out-Null
    $missingBitmapResp = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/zones/HALL_A/seats"
    Assert-Eq $missingBitmapResp.StatusCode "503" "Missing seat bitmap during sale returns HTTP 503"
    Assert-Eq (($missingBitmapResp.Body | ConvertFrom-Json).code) "B0201" "Missing seat bitmap returns code B0201"

    # 态 3：真实零余量 -> HTTP 200 + SOLD_OUT
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SET "starshow:stock:{10002}:20004" 0 | Out-Null
    $soldOutResp = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/stock"
    Assert-Eq $soldOutResp.StatusCode "200" "Real zero stock returns HTTP 200"
    Assert-Eq (($soldOutResp.Body | ConvertFrom-Json).data.entryState) "SOLD_OUT" "Real zero stock returns SOLD_OUT"

    # 态 4：停售（即使库存恢复为 50，只要进入 ENDED 仍必须返回 ENDED）
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SET "starshow:stock:{10002}:20004" 50 | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb HSET "starshow:show:ready:{10002}" stage ENDED | Out-Null
    $endedStockResp = Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10002/stock"
    Assert-Eq $endedStockResp.StatusCode "200" "Ended show returns HTTP 200"
    Assert-Eq (($endedStockResp.Body | ConvertFrom-Json).data.entryState) "ENDED" "Ended show returns ENDED even with positive stock"

    # --- 8. 跨链路改期闭环（10003）：monolith 本地直调 / distributed 走真实 OpenFeign ---
    Write-Output "`n=== [Step 4] Cross-Chain Reschedule & Millisecond Entry Consistency on 10003 ($Mode) ==="
    $phone = "139" + ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString().Substring(2, 8))
    $reg = Invoke-CurlApi -Method POST -Url "$gwUrl/api/v1/auth/register" -Body "{`"phone`":`"$phone`",`"password`":`"StarShow@2026`",`"nickname`":`"C2Fan`"}"
    Assert-Eq $reg.StatusCode "200" "Register fan user"
    $login = Invoke-CurlApi -Method POST -Url "$gwUrl/api/v1/auth/login" -Body "{`"phone`":`"$phone`",`"password`":`"StarShow@2026`"}"
    $token = ($login.Body | ConvertFrom-Json).data.token
    Assert-True (-not [string]::IsNullOrWhiteSpace($token)) "Login returned JWT token"
    $authHdrs = @{ "Authorization" = "Bearer $token" }
    $idCard = Build-SampleIdCard "31010119950615123"
    $verify = Invoke-CurlApi -Method POST -Url "$gwUrl/api/v1/users/identity" -Body "{`"realName`":`"林星演`",`"idCard`":`"$idCard`"}" -Headers $authHdrs
    Assert-Eq (($verify.Body | ConvertFrom-Json).code) "00000" "Real-name identity verified"
    $rsv = Invoke-CurlApi -Method POST -Url "$gwUrl/api/v1/reservations" -Body '{"showId":10003}' -Headers $authHdrs
    Assert-Eq (($rsv.Body | ConvertFrom-Json).code) "00000" "User reserved show 10003 (v1)"

    # 推迟改期至 +5 天（v1 -> v2）：自动联动链路一落 SCHEDULE_CHANGED 通知
    $postponeStart = (Get-Date).AddDays(5).ToString("yyyy-MM-ddTHH:mm:ss")
    $postponeEnd = (Get-Date).AddDays(20).ToString("yyyy-MM-ddTHH:mm:ss")
    $r10003v2 = Invoke-CurlApi -Method PUT -Url "$directShowUrl/internal/shows/10003/schedule" -Body "{`"saleStartTime`":`"$postponeStart`",`"saleEndTime`":`"$postponeEnd`"}" -Headers $internalHdrs
    Assert-Eq (($r10003v2.Body | ConvertFrom-Json).data.saleConfigVersion) "2" "10003 postponed to v2"

    $notifsV2 = (Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/notifications/me" -Headers $authHdrs).Body | ConvertFrom-Json
    $hasSchedV2 = @($notifsV2.data | Where-Object { $_.notificationType -eq "SCHEDULE_CHANGED" -and $_.saleConfigVersion -eq 2 }).Count -ge 1
    Assert-True $hasSchedV2 "Chain1 auto-received SCHEDULE_CHANGED notification for v2 via $Mode"

    # 提前改期至已开售窗口（v2 -> v3）：未预热前详情与 /me 均为 PREPARING / entryOpen=false
    $advStart = (Get-Date).AddSeconds(-2).ToString("yyyy-MM-ddTHH:mm:ss")
    $advEnd = (Get-Date).AddDays(10).ToString("yyyy-MM-ddTHH:mm:ss")
    $r10003v3 = Invoke-CurlApi -Method PUT -Url "$directShowUrl/internal/shows/10003/schedule" -Body "{`"saleStartTime`":`"$advStart`",`"saleEndTime`":`"$advEnd`"}" -Headers $internalHdrs
    Assert-Eq (($r10003v3.Body | ConvertFrom-Json).data.saleConfigVersion) "3" "10003 advanced to v3"

    $myV3Before = ((Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/reservations/10003/me" -Headers $authHdrs).Body | ConvertFrom-Json).data
    Assert-Eq $myV3Before.saleConfigVersion "3" "Chain1 /me synchronized to v3"
    Assert-Eq $myV3Before.entryOpen "False" "Unpreheated v3 keeps Chain1 entryOpen=false"

    # 预热 v3 -> 详情页与链路一 /me 毫秒级同步开放 OPEN / ENTER_SECKILL！
    $ph10003v3 = Invoke-CurlApi -Method POST -Url "$directShowUrl/internal/shows/10003/preheat" -Body '{"saleConfigVersion":3}' -Headers $internalHdrs
    Assert-Eq (($ph10003v3.Body | ConvertFrom-Json).data.taskStatus) "READY" "10003 v3 preheated READY"

    $detail10003Open = ((Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10003").Body | ConvertFrom-Json).data
    Assert-Eq $detail10003Open.entryState "OPEN" "Show 10003 detail entryState=OPEN"
    Assert-Eq $detail10003Open.saleConfigVersion "3" "Show 10003 detail version=3"

    $myV3Open = ((Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/reservations/10003/me" -Headers $authHdrs).Body | ConvertFrom-Json).data
    Assert-Eq $myV3Open.entryOpen "True" "Chain1 /me entryOpen=true synchronized with Show 10003 READY"
    Assert-Eq $myV3Open.entryState "OPEN" "Chain1 /me entryState=OPEN"
    Assert-Eq $myV3Open.nextAction "ENTER_SECKILL" "Chain1 /me nextAction=ENTER_SECKILL"

    # 验证 10003 真实零余量时，详情页与「我的预约」一致呈 SOLD_OUT / WATCH_RESTOCK（绝不误返 WAIT_FOR_SALE）
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb SET "starshow:stock:{10003}:20005" 0 | Out-Null
    $detail10003SoldOut = ((Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/shows/10003").Body | ConvertFrom-Json).data
    Assert-Eq $detail10003SoldOut.entryState "SOLD_OUT" "Show 10003 detail entryState=SOLD_OUT"
    $myV3SoldOut = ((Invoke-CurlApi -Method GET -Url "$gwUrl/api/v1/reservations/10003/me" -Headers $authHdrs).Body | ConvertFrom-Json).data
    Assert-Eq $myV3SoldOut.entryState "SOLD_OUT" "Chain1 /me entryState=SOLD_OUT aligned with detail"
    Assert-Eq $myV3SoldOut.nextAction "WATCH_RESTOCK" "Chain1 /me nextAction=WATCH_RESTOCK (not WAIT_FOR_SALE)"

    # --- 9. 验证默认库与 Redis DB 0 哨兵 100% 完好 ---
    $sentinelAfter = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 GET $sentinelRedisKey).Trim()
    $defaultShowSnapshotAfter = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D starshow -e "SELECT COUNT(*), IFNULL(SUM(id),0) FROM t_show;").Trim()
    Assert-Eq $sentinelAfter $sentinelRedisVal "Redis DB 0 sentinel 100% intact"
    Assert-Eq $defaultShowSnapshotAfter $defaultShowSnapshotBefore "Default MySQL starshow database 100% intact"

    Write-Output "`n=== ALL CHAIN 2 E2E CHECKS PASSED ($Mode) ==="
} finally {
    foreach ($item in $procs) {
        if ($item.Proc -and -not $item.Proc.HasExited) {
            try { Stop-Process -Id $item.Proc.Id -Force -ErrorAction SilentlyContinue } catch {}
        }
    }
    if ($createdDatabase) {
        docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "DROP DATABASE IF EXISTS ``$TestDatabase``;" | Out-Null
    }
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb FLUSHDB | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 DEL $sentinelRedisKey | Out-Null
    Write-Output "[CLEANUP] Stopped processes, dropped '$TestDatabase', flushed Redis DB $TestRedisDb, removed sentinel."
}
