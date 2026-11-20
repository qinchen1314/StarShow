<#
.SYNOPSIS
    StarShow 链路一（Phase 1）隔离数据可复跑 E2E 实证验收脚本（ISSUE-007 & ISSUE-010）。
.DESCRIPTION
    严格隔离测试数据，绝不触碰默认 starshow MySQL 库与 Redis DB 0：
      - 自动校验隔离配置：拒绝 TestDatabase=starshow 或 TestRedisDb=0，并在写入前校验目标 Redis 测试库为空（DBSIZE == 0）；
      - 仅创建本轮专属隔离 MySQL 库并导入仓库最新 01_schema.sql + 02_seed.sql；
      - 统一将所有被测服务、健康检查与夹具查询绑定到同一隔离库与隔离 Redis DB；
      - 支持 -Mode monolith、-Mode distributed、-VerifyMissingSecrets、-VerifyCrashRecovery 与 -SimulateMidwayFailure；
      - 无论成功或中途失败，finally 均保证停止所有启动的 Java 进程并仅销毁本轮创建的隔离库与隔离 Redis DB，保持错误退出码。
#>
param(
    [ValidateSet("monolith", "distributed")]
    [string]$Mode = "monolith",
    [switch]$VerifyMissingSecrets,
    [switch]$VerifyCrashRecovery,
    [switch]$SimulateMidwayFailure,
    [string]$MysqlContainer = "starshow-mysql",
    [string]$RedisContainer = "starshow-redis",
    [string]$TestDatabase = "",
    [int]$TestRedisDb = 15,
    [int]$DelayOverrideMs = 5000
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

# --- 2. 强校验测试数据隔离边界（任何写入前 Fail-Fast，ISSUE-010） ---
if ([string]::IsNullOrWhiteSpace($TestDatabase)) {
    $TestDatabase = "starshow_e2e_${Mode}_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}
if ($TestDatabase -match "^(?i)starshow$" -or $TestDatabase -notmatch "^[a-zA-Z0-9_]+$") {
    throw "Refusing unsafe TestDatabase='$TestDatabase'. Must be an isolated non-default database name matching ^[a-zA-Z0-9_]+$."
}
if ($TestRedisDb -lt 1 -or $TestRedisDb -gt 15) {
    throw "Refusing unsafe TestRedisDb=$TestRedisDb. Must be an isolated Redis DB index in 1..15 (never DB 0)."
}

$existingDbCount = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$TestDatabase';").Trim()
if ($existingDbCount -ne "0") {
    throw "Refusing to reuse pre-existing MySQL database '$TestDatabase'. Provide a fresh isolated TestDatabase name."
}

$existingRedisSize = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb DBSIZE).Trim()
if ($existingRedisSize -ne "0") {
    throw "Refusing to use non-empty Redis DB $TestRedisDb (DBSIZE=$existingRedisSize). Choose an empty isolated Redis DB."
}
Write-Output "[PASS] Isolation precheck passed: TestDatabase=$TestDatabase (fresh), TestRedisDb=$TestRedisDb (DBSIZE=0)"

$mysqlPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
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
        $hdrLines = [System.IO.File]::ReadAllLines($tmpHdr)
        $traceLines = $hdrLines | Where-Object { $_ -match "^(?i)X-Trace-Id:" }
        return [PSCustomObject]@{
            StatusCode = [int]$statusCode
            Body = $respBody
            TraceHeaderCount = @($traceLines).Count
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

function Assert-PortsFree([int[]]$ports) {
    foreach ($p in $ports) {
        $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
        if ($busy) {
            throw "Port $p is already in use by PID $($busy[0].OwningProcess). Stop the conflicting process before running E2E."
        }
    }
}

function Wait-PortsFree([int[]]$ports, [int]$maxSeconds = 10) {
    for ($i = 0; $i -lt ($maxSeconds * 2); $i++) {
        $busyAny = $false
        foreach ($p in $ports) {
            $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
            if ($busy) {
                $busyAny = $true
                break
            }
        }
        if (-not $busyAny) { return }
        Start-Sleep -Milliseconds 500
    }
}

function Stop-ServiceProcess($item) {
    if (-not $item -or -not $item.Proc) { return }
    try {
        $pidToKill = $item.Proc.Id
        & taskkill.exe /PID $pidToKill /T /F 2>$null | Out-Null
        if (-not $item.Proc.HasExited) {
            Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue
        }
        $null = $item.Proc.WaitForExit(5000)
    } catch {}
}

function Wait-Health([string]$url, [int]$maxSeconds = 35, $ownerProc = $null) {
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
        throw "Expected $jarPath to fail fast when secrets are missing, but it stayed running!"
    }
    $combined = $outTask.Result + "`n" + $errTask.Result
    if ($proc.ExitCode -eq 0) {
        throw "Expected non-zero exit code for $jarPath without secrets"
    }
    if ($combined -notmatch [regex]::Escape($expectedPropName)) {
        throw "Expected startup failure for $jarPath to mention '$expectedPropName'"
    }
    Write-Output "[PASS] Missing-secret startup rejection: $jarPath (exit=$($proc.ExitCode), mentions $expectedPropName)"
}

function Build-SampleIdCard([string]$first17) {
    $weights = @(7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2)
    $codes = @('1','0','X','9','8','7','6','5','4','3','2')
    $sum = 0
    for ($i = 0; $i -lt 17; $i++) { $sum += ([int][string]$first17[$i]) * $weights[$i] }
    return $first17 + $codes[$sum % 11]
}

if ($VerifyMissingSecrets) {
    Write-Output "`n=== [ISSUE-002] Verifying Missing-Secret Startup Rejection Across All 4 Jars ==="
    Test-MissingSecretRejection "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "--server.port=0" "starshow.security.jwt-secret"
    Test-MissingSecretRejection "starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=0" "starshow.security.internal-gateway-secret"
    Test-MissingSecretRejection "starshow-user/target/starshow-user-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=0 --starshow.reservation.mq-enabled=false" "starshow.security.jwt-secret"
    Test-MissingSecretRejection "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" "--server.port=0 --starshow.reservation.mq-enabled=false" "starshow.security.jwt-secret"
}

$javaExe = "java"
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
}

$procs = @()
$startedPorts = @()
$createdDatabase = $false
$usedIsolatedRedis = $false
$logDir = Join-Path $env:TEMP ("starshow-e2e-" + $Mode + "-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
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
    Write-Output "`n=== Creating Isolated MySQL Schema '$TestDatabase' & Binding Redis DB $TestRedisDb ==="
    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "CREATE DATABASE ``$TestDatabase`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    $createdDatabase = $true
    $usedIsolatedRedis = $true

    # 重定向 01_schema.sql 与 02_seed.sql 中的库名至本轮隔离库（绝不触碰 starshow 默认库）
    $schemaSql = (Get-Content -Raw -Encoding UTF8 "docker/mysql/init/01_schema.sql") -replace "(?i)CREATE DATABASE IF NOT EXISTS\s+``?starshow``?[^;]*;", "" -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
    $seedSql = (Get-Content -Raw -Encoding UTF8 "docker/mysql/init/02_seed.sql") -replace "(?i)USE\s+``?starshow``?;", "USE ``$TestDatabase``;"
    $schemaSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase
    $seedSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql --default-character-set=utf8mb4 -uroot $TestDatabase

    $tableCount = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TestDatabase';").Trim()
    Assert-Eq $tableCount "19" "Isolated DB '$TestDatabase' initialized with 19 physical tables"

    $commonEnv = @{
        "MYSQL_PORT" = $mysqlPort
        "MYSQL_DATABASE" = $TestDatabase
        "REDIS_HOST" = $redisHost
        "REDIS_PORT" = $redisPort
        "REDIS_DATABASE" = "$TestRedisDb"
        "ROCKETMQ_NAME_SERVER" = $rmqNameSrv
        "STARSHOW_TEST_OVERRIDE_DELAY_MS" = "$DelayOverrideMs"
        "STARSHOW_COUNT_CACHE_TTL_SECONDS" = "3"
    }

    if ($Mode -eq "monolith") {
        Assert-PortsFree @(8000, 8090)
        $startedPorts += @(8000, 8090)
        $bootItem = Start-ServiceJar "boot-all" "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" "" $commonEnv
        $procs += $bootItem
        $gwEnv = $commonEnv.Clone()
        $gwEnv["USER_SERVICE_URI"] = "http://127.0.0.1:8090"
        $gwEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8090"
        $gwItem = Start-ServiceJar "gateway" "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "" $gwEnv
        $procs += $gwItem

        Wait-Health "http://127.0.0.1:8090/actuator/health" 35 $bootItem.Proc
        Wait-Health "http://127.0.0.1:8000/actuator/health" 35 $gwItem.Proc
        $directUserUrl = "http://127.0.0.1:8090"
        $directShowUrl = "http://127.0.0.1:8090"
    } else {
        Assert-PortsFree @(8000, 8001, 8002)
        $startedPorts += @(8000, 8001, 8002)
        $showItem = Start-ServiceJar "show" "starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed" $commonEnv
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

        Wait-Health "http://127.0.0.1:8002/actuator/health" 35 $showItem.Proc
        Wait-Health "http://127.0.0.1:8001/actuator/health" 35 $userItem.Proc
        Wait-Health "http://127.0.0.1:8000/actuator/health" 35 $gwItem.Proc
        $directUserUrl = "http://127.0.0.1:8001"
        $directShowUrl = "http://127.0.0.1:8002"

        $internalUnauth = Invoke-CurlApi -Method GET -Url "http://127.0.0.1:8002/internal/shows/10003/basic"
        Assert-Eq $internalUnauth.StatusCode 401 "Show internal endpoint rejects missing internal secret"
        $internalOk = Invoke-CurlApi -Method GET -Url "http://127.0.0.1:8002/internal/shows/10003/basic" -Headers @{ "X-Internal-Gateway-Secret" = $env:STARSHOW_INTERNAL_GATEWAY_SECRET }
        Assert-Eq $internalOk.StatusCode 200 "Show internal endpoint HTTP 200 with internal secret"
        Assert-Eq ($internalOk.Body | ConvertFrom-Json).data.showId "10003" "Show internal endpoint returns showId 10003"
    }

    if ($SimulateMidwayFailure) {
        throw "Simulated midway failure to verify non-zero exit code and automatic process/resource cleanup"
    }

    $gw = "http://127.0.0.1:8000"
    $suffix = ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() % 9000) + 1000
    $phoneA = "1386666$suffix"
    $phoneB = "1387777$suffix"
    $validIdCard = Build-SampleIdCard "31010119930615001"

    Write-Output "`n=== Running Isolated E2E Chain 1 Flow ($Mode, DB=$TestDatabase, RedisDB=$TestRedisDb) ==="
    $regA = Invoke-CurlApi -Method POST -Url "$gw/api/v1/auth/register" -Body "{`"phone`":`"$phoneA`",`"password`":`"StarShow2026`",`"nickname`":`"E2EUserA`"}"
    Assert-Eq $regA.StatusCode 200 "Register A HTTP 200"
    Assert-Eq $regA.TraceHeaderCount 1 "Single X-Trace-Id response header"
    $userAId = ($regA.Body | ConvertFrom-Json).data.userId

    $tokenA = ((Invoke-CurlApi -Method POST -Url "$gw/api/v1/auth/login" -Body "{`"phone`":`"$phoneA`",`"password`":`"StarShow2026`"}").Body | ConvertFrom-Json).data.token
    $authA = @{ "Authorization" = "Bearer $tokenA" }

    $spoofGw = Invoke-CurlApi -Method GET -Url "$gw/api/v1/users/identity" -Headers @{ "X-User-Id" = "999" }
    Assert-Eq $spoofGw.StatusCode 401 "Gateway rejects spoofed X-User-Id"
    $spoofDirect = Invoke-CurlApi -Method GET -Url "$directUserUrl/api/v1/users/identity" -Headers @{ "X-User-Id" = "999"; "X-Internal-Gateway-Secret" = "invalid-secret" }
    Assert-Eq $spoofDirect.StatusCode 401 "Downstream rejects spoofed X-User-Id"

    $myUnverified = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/10003/me" -Headers $authA
    $myUnverifiedObj = ($myUnverified.Body | ConvertFrom-Json).data
    Assert-Eq $myUnverifiedObj.realNameVerified "False" "GET /reservations/10003/me before KYC => realNameVerified=False"
    Assert-Eq $myUnverifiedObj.nextAction "VERIFY_IDENTITY" "GET /reservations/10003/me before KYC => nextAction=VERIFY_IDENTITY"

    $rsvBefore = Invoke-CurlApi -Method POST -Url "$gw/api/v1/reservations" -Body '{"showId":10003}' -Headers $authA
    Assert-Eq ($rsvBefore.Body | ConvertFrom-Json).code "A0104" "Unverified reservation blocked A0104"

    $idA = Invoke-CurlApi -Method POST -Url "$gw/api/v1/users/identity" -Body "{`"realName`":`"林星演`",`"idCard`":`"$validIdCard`"}" -Headers $authA
    Assert-Eq ($idA.Body | ConvertFrom-Json).code "00000" "User A identity verified"

    $null = Invoke-CurlApi -Method POST -Url "$gw/api/v1/auth/register" -Body "{`"phone`":`"$phoneB`",`"password`":`"StarShow2026`",`"nickname`":`"ScalperB`"}"
    $tokenB = ((Invoke-CurlApi -Method POST -Url "$gw/api/v1/auth/login" -Body "{`"phone`":`"$phoneB`",`"password`":`"StarShow2026`"}").Body | ConvertFrom-Json).data.token
    $idB = Invoke-CurlApi -Method POST -Url "$gw/api/v1/users/identity" -Body "{`"realName`":`"黄牛号`",`"idCard`":`"$validIdCard`"}" -Headers @{ "Authorization" = "Bearer $tokenB" }
    Assert-Eq ($idB.Body | ConvertFrom-Json).code "A0105" "Duplicate ID card blocked A0105"

    $myBefore = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/10003/me" -Headers $authA
    $myBeforeObj = ($myBefore.Body | ConvertFrom-Json).data
    Assert-Eq $myBeforeObj.reserved "False" "GET /reservations/10003/me before reserve => reserved=False"
    Assert-Eq $myBeforeObj.nextAction "RESERVE" "GET /reservations/10003/me before reserve => nextAction=RESERVE"

    $rsvA = Invoke-CurlApi -Method POST -Url "$gw/api/v1/reservations" -Body '{"showId":10003}' -Headers $authA
    $rsvAObj = $rsvA.Body | ConvertFrom-Json
    Assert-Eq $rsvAObj.code "00000" "Reserve 10003 succeeded"
    Assert-Eq $rsvAObj.data.remindScheduled "True" "Broker accepted delay remind"
    Assert-Eq $rsvAObj.data.saleConfigVersion "1" "Reservation bound to saleConfigVersion 1"
    $reservationId = $rsvAObj.data.reservationId

    $myAfter = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/10003/me" -Headers $authA
    $myAfterObj = ($myAfter.Body | ConvertFrom-Json).data
    Assert-Eq $myAfterObj.reserved "True" "GET /reservations/10003/me after reserve => reserved=True"
    Assert-Eq $myAfterObj.remindStatusLabel "SCHEDULED" "GET /reservations/10003/me => SCHEDULED"
    Assert-Eq $myAfterObj.nextAction "WAIT_FOR_SALE" "GET /reservations/10003/me => WAIT_FOR_SALE"

    $myListResp = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/me" -Headers $authA
    Assert-Eq $myListResp.StatusCode 200 "GET /api/v1/reservations/me HTTP 200"
    $myListItems = @((($myListResp.Body | ConvertFrom-Json).data))
    Assert-Eq $myListItems.Count 1 "GET /api/v1/reservations/me returns 1 reserved show"
    Assert-Eq $myListItems[0].showId "10003" "GET /api/v1/reservations/me item showId == 10003"

    $initialStatus = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT remind_status FROM t_reservation WHERE id = $reservationId;").Trim()
    Assert-Eq $initialStatus "3" "Pre-delivery remind_status observed as 3 (SCHEDULED) before consumer runs"

    $countResp = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/10003/count"
    $countData = ($countResp.Body | ConvertFrom-Json).data
    Assert-Eq $countData.count "1" "Public reservation count == 1"
    Assert-Eq $countData.countAvailable "True" "Public reservation countAvailable == True"
    Assert-Eq $countData.stale "False" "Public reservation count stale == False"
    $rsvDup = Invoke-CurlApi -Method POST -Url "$gw/api/v1/reservations" -Body '{"showId":10003}' -Headers $authA
    Assert-Eq ($rsvDup.Body | ConvertFrom-Json).code "A0106" "Duplicate reservation blocked A0106"

    $finalStatus = $initialStatus
    for ($sec = 1; $sec -le 18; $sec++) {
        Start-Sleep -Seconds 1
        $finalStatus = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT remind_status FROM t_reservation WHERE id = $reservationId;").Trim()
        if ($finalStatus -eq "1") {
            Write-Output "[PASS] RocketMQ 5.3.0 delivered and consumer transitioned status 3 -> 1 after ${sec}s"
            break
        }
    }
    Assert-Eq $finalStatus "1" "Post-delivery remind_status converged to 1"

    # Phase 1.1: 验证站内通知同事务落账、通过网关可读并支持标记已读
    $notifResp = Invoke-CurlApi -Method GET -Url "$gw/api/v1/notifications/me" -Headers $authA
    Assert-Eq $notifResp.StatusCode 200 "GET /api/v1/notifications/me HTTP 200"
    $notifItems = @((($notifResp.Body | ConvertFrom-Json).data))
    Assert-Eq $notifItems.Count 1 "Exactly 1 in-app reminder notification persisted in same tx"
    Assert-Eq $notifItems[0].saleConfigVersion "1" "Notification carries saleConfigVersion 1"
    Assert-Eq $notifItems[0].read "False" "Notification initially unread"
    $notifId = $notifItems[0].notificationId
    $readResp = Invoke-CurlApi -Method POST -Url "$gw/api/v1/notifications/$notifId/read" -Headers $authA
    Assert-Eq ($readResp.Body | ConvertFrom-Json).data.read "True" "Notification marked read via gateway"

    # Phase 1.1: 验证主办方改期（v1 -> v2）+ 预约重排联动（通过网关 GET /me 立即观测到新版本）
    $newStart = [DateTime]::Now.AddHours(24).ToString("yyyy-MM-ddTHH:mm:ss")
    $newEnd = [DateTime]::Now.AddDays(20).ToString("yyyy-MM-ddTHH:mm:ss")
    $internalHdr = @{ "X-Internal-Gateway-Secret" = $env:STARSHOW_INTERNAL_GATEWAY_SECRET }
    $schedResp = Invoke-CurlApi -Method PUT -Url "$directShowUrl/internal/shows/10003/schedule" -Body "{`"saleStartTime`":`"$newStart`",`"saleEndTime`":`"$newEnd`"}" -Headers $internalHdr
    Assert-Eq $schedResp.StatusCode 200 "Organizer reschedule PUT /internal/shows/10003/schedule HTTP 200"
    Assert-Eq ($schedResp.Body | ConvertFrom-Json).data.saleConfigVersion "2" "Show saleConfigVersion incremented to 2"

    $reschedResp = Invoke-CurlApi -Method POST -Url "$directUserUrl/internal/reservations/shows/10003/reschedule" -Headers $internalHdr
    Assert-Eq $reschedResp.StatusCode 200 "Reservation reschedule trigger HTTP 200"
    $reschedObj = ($reschedResp.Body | ConvertFrom-Json).data
    Assert-Eq $reschedObj.resetReservationCount "1" "Existing reservation reset to version 2"
    Assert-Eq $reschedObj.scheduleNotifiedCount "1" "Immediate SCHEDULE_CHANGED notification persisted for version 2"

    $notifAfterResched = Invoke-CurlApi -Method GET -Url "$gw/api/v1/notifications/me" -Headers $authA
    $notifAfterItems = @((($notifAfterResched.Body | ConvertFrom-Json).data))
    Assert-Eq $notifAfterItems.Count 2 "User notifications now include REMIND_BEFORE_SALE + SCHEDULE_CHANGED"
    Assert-Eq $notifAfterItems[0].notificationType "SCHEDULE_CHANGED" "Latest notification type is SCHEDULE_CHANGED"

    $myV2 = Invoke-CurlApi -Method GET -Url "$gw/api/v1/reservations/10003/me" -Headers $authA
    Assert-Eq ($myV2.Body | ConvertFrom-Json).data.saleConfigVersion "2" "GET /reservations/10003/me reflects updated saleConfigVersion 2"

    $logoutResp = Invoke-CurlApi -Method POST -Url "$gw/api/v1/auth/logout" -Headers $authA
    Assert-Eq ($logoutResp.Body | ConvertFrom-Json).code "00000" "Logout succeeded"
    $revokedGw = Invoke-CurlApi -Method GET -Url "$gw/api/v1/users/identity" -Headers $authA
    Assert-Eq $revokedGw.StatusCode 401 "Gateway rejects revoked JWT with 401"
    Assert-Eq ($revokedGw.Body | ConvertFrom-Json).code "A0102" "Gateway rejects revoked JWT with A0102"
    $revokedDirect = Invoke-CurlApi -Method GET -Url "$directUserUrl/api/v1/users/identity" -Headers $authA
    Assert-Eq $revokedDirect.StatusCode 401 "Direct user endpoint rejects revoked JWT with 401"
    Assert-Eq ($revokedDirect.Body | ConvertFrom-Json).code "A0102" "Direct user endpoint rejects revoked JWT with A0102"

    if ($VerifyCrashRecovery) {
        Write-Output "`n=== [ISSUE-008] Two-Process Crash-Between-DB-And-Redis Recovery Verification ==="
        Assert-PortsFree @(8012)
        $startedPorts += @(8012)
        # 启动第二个独立进程节点 (port 8012) 与主节点共享同一隔离库与 Redis 测试库（countCacheTtlSeconds=3）
        $peerArgs = if ($Mode -eq "monolith") { "--server.port=8012 --starshow.reservation.mq-enabled=false" } else { "--spring.profiles.active=distributed --server.port=8012 --starshow.reservation.mq-enabled=false" }
        $peerJar = if ($Mode -eq "monolith") { "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" } else { "starshow-user/target/starshow-user-0.1.0-SNAPSHOT-exec.jar" }
        $peerEnv = $commonEnv.Clone()
        if ($Mode -eq "distributed") { $peerEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8002" }
        $peerProc = Start-ServiceJar "crash-peer" $peerJar $peerArgs $peerEnv
        $procs += $peerProc
        Wait-Health "http://127.0.0.1:8012/actuator/health" 35 $peerProc.Proc

        # 准备空场次 10002，通过 peer 节点预热 Redis 计数为 0
        $preCount = Invoke-CurlApi -Method GET -Url "http://127.0.0.1:8012/api/v1/reservations/10002/count"
        Assert-Eq ($preCount.Body | ConvertFrom-Json).data.count "0" "Pre-warmed show 10002 count == 0"

        # 登录账号 B 并完成独立身份证实名
        $idCardB = Build-SampleIdCard "31010119940718002"
        $null = Invoke-CurlApi -Method POST -Url "$directUserUrl/api/v1/users/identity" -Body "{`"realName`":`"林测试二`",`"idCard`":`"$idCardB`"}" -Headers @{ "Authorization" = "Bearer $tokenB" }
        $userBId = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT id FROM t_user WHERE phone='$phoneB';").Trim()

        # 模拟写入者进程在 MySQL 提交预约后、更新 Redis 前硬中断退出：提交 DB 行并立刻强杀写入者进程
        docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -D $TestDatabase -e "INSERT INTO t_reservation (id, user_id, show_id, remind_status, next_retry_time, deleted) VALUES (88000010002, $userBId, 10002, 1, NOW(), 0);"
        $writerItem = $procs | Where-Object { $_.Name -eq $(if ($Mode -eq "monolith") { "boot-all" } else { "user" }) } | Select-Object -First 1
        $writerPort = if ($Mode -eq "monolith") { 8090 } else { 8001 }
        Stop-ServiceProcess $writerItem
        Wait-PortsFree @($writerPort) 10
        Write-Output "[PASS] Hard-killed writer process ($($writerItem.Name)) right after DB commit before Redis update"

        # 切换流量至 peer 节点并等待有界收敛时限 (countCacheTtlSeconds=3 -> 4s)
        Start-Sleep -Seconds 4
        $peerCountAfter = Invoke-CurlApi -Method GET -Url "http://127.0.0.1:8012/api/v1/reservations/10002/count"
        Assert-Eq ($peerCountAfter.Body | ConvertFrom-Json).data.count "1" "Peer node converged show 10002 count to 1 within bounded TTL"

        # 重启原写入者进程并验证同一用户重试预约返 A0106 且 DB/Redis/GET 严格为 1
        $restartedJar = if ($Mode -eq "monolith") { "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" } else { "starshow-user/target/starshow-user-0.1.0-SNAPSHOT-exec.jar" }
        $restartedArgs = if ($Mode -eq "monolith") { "" } else { "--spring.profiles.active=distributed" }
        $restartedEnv = $commonEnv.Clone()
        if ($Mode -eq "distributed") { $restartedEnv["SHOW_SERVICE_URI"] = "http://127.0.0.1:8002" }
        $restartedProc = Start-ServiceJar "writer-restarted" $restartedJar $restartedArgs $restartedEnv
        $procs += $restartedProc
        Wait-Health "$directUserUrl/actuator/health" 35 $restartedProc.Proc

        $retryRsv = Invoke-CurlApi -Method POST -Url "$directUserUrl/api/v1/reservations" -Body '{"showId":10002}' -Headers @{ "Authorization" = "Bearer $tokenB" }
        Assert-Eq ($retryRsv.Body | ConvertFrom-Json).code "A0106" "Retrying reservation after crash returns A0106 (single DB row)"
        $restartedCount = Invoke-CurlApi -Method GET -Url "$directUserUrl/api/v1/reservations/10002/count"
        Assert-Eq ($restartedCount.Body | ConvertFrom-Json).data.count "1" "Restarted writer node GET count == 1"
        $dbFinal = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -D $TestDatabase -e "SELECT COUNT(*) FROM t_reservation WHERE show_id=10002 AND deleted=0;").Trim()
        $redisFinal = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb HGET "starshow:rsv:count:10002" count).Trim()
        Assert-Eq $dbFinal "1" "MySQL final count == 1"
        Assert-Eq $redisFinal "1" "Redis final count == 1"
    }

    Write-Output "`n=== [$Mode] ALL ISOLATED CHAIN 1 LIVE E2E ASSERTIONS PASSED ==="
} finally {
    $ErrorActionPreference = "Continue"
    foreach ($item in $procs) {
        Stop-ServiceProcess $item
    }
    if ($startedPorts.Count -gt 0) {
        Wait-PortsFree $startedPorts 10
        # 兜底清理仍占用本脚本拉起端口的残留子进程
        foreach ($p in ($startedPorts | Select-Object -Unique)) {
            $busy = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
            foreach ($conn in $busy) {
                if ($conn.OwningProcess -gt 0) {
                    & taskkill.exe /PID $conn.OwningProcess /T /F 2>$null | Out-Null
                }
            }
        }
        Wait-PortsFree $startedPorts 5
    }
    if ($createdDatabase) {
        try {
            docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "DROP DATABASE IF EXISTS ``$TestDatabase``;" 2>$null | Out-Null
            Write-Output "[CLEANUP] Dropped isolated MySQL database '$TestDatabase'"
        } catch {}
    }
    if ($usedIsolatedRedis) {
        try {
            docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb FLUSHDB 2>$null | Out-Null
            Write-Output "[CLEANUP] Cleared isolated Redis DB $TestRedisDb (DB 0 untouched)"
        } catch {}
    }
}
