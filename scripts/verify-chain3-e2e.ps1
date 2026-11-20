<#
.SYNOPSIS
    StarShow 链路三（Phase 3）双形态原始发行包隔离数据 E2E 实证验收脚本。
.DESCRIPTION
    严格隔离测试数据，绝不触碰默认 starshow MySQL 库与 Redis DB 0：
      - 拒绝 TestDatabase=starshow 或 TestRedisDb=0，写入前校验目标隔离 Redis DB 为空（DBSIZE == 0）；
      - 在默认 starshow 库与 Redis DB 0 预置哨兵并在验收后验证 100% 完好；
      - 仅创建本轮专属隔离 MySQL 库并导入仓库最新 01_schema.sql + 02_seed.sql；
      - 支持 -Mode monolith（boot-all + gateway）与 -Mode distributed（order-exec.jar 零补丁 + show-exec.jar + user-exec.jar + gateway）；
      - 完整验证：缺失密钥拒绝启动、未实名拦截 A0104、公网零内部路由、变价 A0308、跨走道选座拦截、
        V4 连座下单 HTTP 202/00202、同请求重放找回 & 篡改 A0307、异步消费落库 WAIT_PAY、
        换设备 GET /orders/current 找回、详情/游标列表 Long 字符串序列化、跨用户越权 A0304、
        并发座位冲突 A0204+conflictSeatIndices、网关限流 HTTP 429/B0101、TraceId 全程透传与基因分片一致性；
      - 无论成功或失败，finally 均停止全部 Java 进程并销毁隔离资源。
#>
param(
    [ValidateSet("monolith", "distributed")]
    [string]$Mode = "monolith",
    [switch]$VerifyMissingSecrets,
    [string]$MysqlContainer = "starshow-mysql",
    [string]$RedisContainer = "starshow-redis",
    [string]$TestDatabase = "",
    [int]$TestRedisDb = 15
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
    $TestDatabase = "starshow_c3_${Mode}_" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
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

function Wait-Health([string]$url, [int]$maxSeconds = 45, $ownerProc = $null) {
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
    Write-Output "`n=== Verifying Missing-Secret Rejection on starshow-order-exec.jar ==="
    Test-MissingSecretRejection "starshow-order/target/starshow-order-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=0 --starshow.seckill.mq-enabled=false" "starshow.security.internal-gateway-secret"
}

# --- 3. 预置默认库与 DB 0 哨兵 ---
$sentinelKey = "starshow:sentinel:c3e2e:${Mode}:" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$sentinelVal = "sentinel-c3-intact"
$defaultShowCountBefore = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM starshow.t_show;").Trim()
docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 SET $sentinelKey $sentinelVal EX 3600 | Out-Null

$startedProcs = @()
$logDir = Join-Path ([System.IO.Path]::GetTempPath()) ("starshow-c3-e2e-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Start-JavaService([string]$name, [string]$jarPath, [string]$argsStr, [hashtable]$extraEnv = @{}) {
    $outLog = Join-Path $logDir "$name.out.log"
    $errLog = Join-Path $logDir "$name.err.log"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "java"
    $psi.Arguments = "-jar `"$jarPath`" $argsStr"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables["MYSQL_PORT"] = $mysqlPort
    $psi.EnvironmentVariables["MYSQL_DATABASE"] = $TestDatabase
    $psi.EnvironmentVariables["REDIS_HOST"] = $redisHost
    $psi.EnvironmentVariables["REDIS_PORT"] = $redisPort
    $psi.EnvironmentVariables["REDIS_DATABASE"] = "$TestRedisDb"
    $psi.EnvironmentVariables["ROCKETMQ_NAME_SERVER"] = $rmqNameSrv
    $psi.EnvironmentVariables["STARSHOW_SHOW_MAX_TICKETS_PER_ORDER"] = "2"
    $psi.EnvironmentVariables["STARSHOW_SECKILL_STRATEGY"] = "V4"
    $psi.EnvironmentVariables["STARSHOW_SECKILL_MQ_ENABLED"] = "true"
    $psi.EnvironmentVariables["STARSHOW_RESERVATION_MQ_ENABLED"] = "false"
    $psi.EnvironmentVariables["STARSHOW_RESERVATION_RECOVERY_ENABLED"] = "false"
    $psi.EnvironmentVariables["STARSHOW_SHOW_TASK_RECOVERY_ENABLED"] = "false"
    $psi.EnvironmentVariables["STARSHOW_SHOW_LIFECYCLE_SYNC_ENABLED"] = "false"
    foreach ($k in $extraEnv.Keys) {
        $psi.EnvironmentVariables[$k] = $extraEnv[$k]
    }
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $entry = [PSCustomObject]@{ Name = $name; Proc = $proc; OutLog = $outLog; ErrLog = $errLog; OutTask = $outTask; ErrTask = $errTask }
    $script:startedProcs += $entry
    return $entry
}

try {
    # --- 4. 创建隔离 MySQL 库并导入 01_schema.sql + 02_seed.sql ---
    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "CREATE DATABASE ``$TestDatabase`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    $schemaSql = [System.IO.File]::ReadAllText("docker/mysql/init/01_schema.sql", [System.Text.Encoding]::UTF8)
    $schemaSql = $schemaSql -replace 'CREATE DATABASE IF NOT EXISTS `starshow`[^;]*;', ''
    $schemaSql = $schemaSql -replace 'USE `starshow`;', "USE ``$TestDatabase``;"
    $schemaSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot $TestDatabase

    $seedSql = [System.IO.File]::ReadAllText("docker/mysql/init/02_seed.sql", [System.Text.Encoding]::UTF8)
    $seedSql = $seedSql -replace 'USE `starshow`;', "USE ``$TestDatabase``;"
    $seedSql | docker exec -i -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot $TestDatabase

    # 将 10002 置为 INIT 以便通过真实 /internal/shows/10002/preheat 预热并落到售卖窗内
    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot $TestDatabase -e "UPDATE t_show SET sale_status='INIT', sale_start_time=DATE_SUB(NOW(), INTERVAL 5 MINUTE), sale_end_time=DATE_ADD(NOW(), INTERVAL 2 HOUR) WHERE id=10002;"

    # --- 5. 启动目标运行形态的原始发行包（零补丁） ---
    $gwPort = 18000
    $showDirectUrl = ""
    if ($Mode -eq "monolith") {
        $bootPort = 18090
        Assert-PortsFree @($gwPort, $bootPort)
        $bootEntry = Start-JavaService "boot-all" "starshow-boot-all/target/starshow-boot-all-0.1.0-SNAPSHOT.jar" "--spring.profiles.active=monolith --server.port=$bootPort --knife4j.enable=false"
        Wait-Health "http://127.0.0.1:$bootPort/actuator/health" 50 $bootEntry.Proc

        $gwEnv = @{
            "USER_SERVICE_URI" = "http://127.0.0.1:$bootPort"
            "SHOW_SERVICE_URI" = "http://127.0.0.1:$bootPort"
            "ORDER_SERVICE_URI" = "http://127.0.0.1:$bootPort"
            "PAYMENT_SERVICE_URI" = "http://127.0.0.1:$bootPort"
            "STARSHOW_GW_SECKILL_RL_ENABLED" = "true"
            "STARSHOW_GW_SECKILL_RL_USER_LIMIT" = "3"
            "STARSHOW_GW_SECKILL_RL_WINDOW_SECONDS" = "2"
        }
        $gwEntry = Start-JavaService "gateway" "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "--server.port=$gwPort" $gwEnv
        Wait-Health "http://127.0.0.1:$gwPort/actuator/health" 40 $gwEntry.Proc
        $showDirectUrl = "http://127.0.0.1:$bootPort"
    } else {
        $gwPort = 18100
        $userPort = 18101
        $showPort = 18102
        $orderPort = 18103
        Assert-PortsFree @($gwPort, $userPort, $showPort, $orderPort)

        $userEntry = Start-JavaService "user" "starshow-user/target/starshow-user-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=$userPort" @{ "SHOW_SERVICE_URI" = "http://127.0.0.1:$showPort" }
        $showEntry = Start-JavaService "show" "starshow-show/target/starshow-show-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=$showPort" @{ "USER_SERVICE_URI" = "http://127.0.0.1:$userPort" }
        Wait-Health "http://127.0.0.1:$userPort/actuator/health" 50 $userEntry.Proc
        Wait-Health "http://127.0.0.1:$showPort/actuator/health" 50 $showEntry.Proc

        $orderEntry = Start-JavaService "order" "starshow-order/target/starshow-order-0.1.0-SNAPSHOT-exec.jar" "--spring.profiles.active=distributed --server.port=$orderPort" @{
            "USER_SERVICE_URI" = "http://127.0.0.1:$userPort"
            "SHOW_SERVICE_URI" = "http://127.0.0.1:$showPort"
        }
        Wait-Health "http://127.0.0.1:$orderPort/actuator/health" 50 $orderEntry.Proc

        $gwEnv = @{
            "USER_SERVICE_URI" = "http://127.0.0.1:$userPort"
            "SHOW_SERVICE_URI" = "http://127.0.0.1:$showPort"
            "ORDER_SERVICE_URI" = "http://127.0.0.1:$orderPort"
            "PAYMENT_SERVICE_URI" = "http://127.0.0.1:18004"
            "STARSHOW_GW_SECKILL_RL_ENABLED" = "true"
            "STARSHOW_GW_SECKILL_RL_USER_LIMIT" = "3"
            "STARSHOW_GW_SECKILL_RL_WINDOW_SECONDS" = "2"
        }
        $gwEntry = Start-JavaService "gateway" "starshow-gateway/target/starshow-gateway-0.1.0-SNAPSHOT.jar" "--server.port=$gwPort" $gwEnv
        Wait-Health "http://127.0.0.1:$gwPort/actuator/health" 40 $gwEntry.Proc
        $showDirectUrl = "http://127.0.0.1:$showPort"
    }

    $gwBase = "http://127.0.0.1:$gwPort"
    $internalHdrs = @{ "X-Internal-Gateway-Secret" = $env:STARSHOW_INTERNAL_GATEWAY_SECRET }

    Write-Output "`n=== [$Mode] E2E Step 1: Public Gateway Zero Internal Route & Preheat 10002 ==="
    $pubInternal = Invoke-CurlApi -Method POST -Url "$gwBase/internal/shows/10002/preheat" -Body '{"saleConfigVersion":1}' -Headers $internalHdrs
    Assert-True ($pubInternal.StatusCode -eq 404 -or $pubInternal.StatusCode -eq 401) "Public gateway blocks /internal/shows/10002/preheat (status=$($pubInternal.StatusCode))"

    $preheatResp = Invoke-CurlApi -Method POST -Url "$showDirectUrl/internal/shows/10002/preheat" -Body '{"saleConfigVersion":1}' -Headers $internalHdrs
    Assert-Eq $preheatResp.StatusCode 200 "Internal preheat 10002 HTTP 200"
    $preheatJson = $preheatResp.Body | ConvertFrom-Json
    Assert-Eq $preheatJson.code "00000" "Internal preheat 10002 code=00000"
    Assert-Eq $preheatJson.data.taskStatus "READY" "Internal preheat 10002 taskStatus=READY"

    Write-Output "`n=== [$Mode] E2E Step 2: Register/Login User A, Verify Unverified A0104, Then Real-Name Verify ==="
    $phoneA = "1380003" + (Get-Random -Minimum 1000 -Maximum 9999)
    $regA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/auth/register" -Body "{`"phone`":`"$phoneA`",`"password`":`"Pass123456!`",`"nickname`":`"FanA`"}"
    Assert-Eq $regA.StatusCode 200 "Register User A HTTP 200"
    $loginA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/auth/login" -Body "{`"phone`":`"$phoneA`",`"password`":`"Pass123456!`"}"
    $jwtA = ($loginA.Body | ConvertFrom-Json).data.token
    $userIdA = ($loginA.Body | ConvertFrom-Json).data.userId
    Assert-True (-not [string]::IsNullOrWhiteSpace($jwtA)) "Login User A JWT obtained (userId=$userIdA)"

    $traceUnverified = "c3e2etrace0000000000000000000001"
    $unverifiedTok = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/token" `
        -Body '{"showId":10002,"saleConfigVersion":1}' `
        -Headers @{ "Authorization" = "Bearer $jwtA"; "X-Trace-Id" = $traceUnverified }
    Assert-Eq ($unverifiedTok.Body | ConvertFrom-Json).code "A0104" "Unverified user rejected with A0104"
    Assert-True ($unverifiedTok.HeadersText -match $traceUnverified) "TraceId echoed on A0104 response"

    $idCardA = Build-SampleIdCard ("31010119950615" + (Get-Random -Minimum 100 -Maximum 999))
    $verifyA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/users/identity" `
        -Body "{`"realName`":`"张演一`",`"idCard`":`"$idCardA`"}" `
        -Headers @{ "Authorization" = "Bearer $jwtA" }
    Assert-Eq ($verifyA.Body | ConvertFrom-Json).code "00000" "User A real-name verified"

    # 等待网关限流窗口刷新（避免未实名那一次占额度）
    Start-Sleep -Seconds 2

    Write-Output "`n=== [$Mode] E2E Step 3: Issue Token, Price Check A0308, Aisle Check, V4 Seat Pair [3,4] -> 202 ==="
    $traceMain = "c3e2etrace0000000000000000000002"
    $tokRespA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/token" `
        -Body '{"showId":10002,"saleConfigVersion":1}' `
        -Headers @{ "Authorization" = "Bearer $jwtA"; "X-Trace-Id" = $traceMain; "X-Show-Id" = "10002" }
    Assert-Eq $tokRespA.StatusCode 200 "Issue seckillToken HTTP 200"
    $tokJsonA = $tokRespA.Body | ConvertFrom-Json
    Assert-Eq $tokJsonA.code "00000" "Issue seckillToken code=00000"
    $seckillTokenA = $tokJsonA.data.seckillToken
    Assert-True (-not [string]::IsNullOrWhiteSpace($seckillTokenA)) "seckillToken issued"
    Assert-True ($tokRespA.HeadersText -match $traceMain) "TraceId echoed on seckill/token"

    # 价格变化校验 A0308
    $priceBody = "{`"showId`":10002,`"tierId`":20004,`"qty`":2,`"zoneId`":`"HALL_A`",`"seatIndices`":[3,4],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":77000,`"seckillToken`":`"$seckillTokenA`",`"clientRequestId`":`"e2e-price-check-01`"}"
    $priceResp = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $priceBody -Headers @{ "Authorization" = "Bearer $jwtA" }
    $priceJson = $priceResp.Body | ConvertFrom-Json
    Assert-Eq $priceJson.code "A0308" "Price mismatch returns A0308"
    Assert-Eq $priceJson.data.currentUnitPriceCent "88000" "A0308 carries authoritative currentUnitPriceCent=88000"

    # 跨走道 [2,3] 校验 A0103
    $aisleBody = "{`"showId`":10002,`"tierId`":20004,`"qty`":2,`"zoneId`":`"HALL_A`",`"seatIndices`":[2,3],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":88000,`"seckillToken`":`"$seckillTokenA`",`"clientRequestId`":`"e2e-aisle-check-01`"}"
    $aisleResp = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $aisleBody -Headers @{ "Authorization" = "Bearer $jwtA" }
    Assert-Eq $aisleResp.StatusCode 400 "Across-aisle seats rejected HTTP 400"
    Assert-Eq ($aisleResp.Body | ConvertFrom-Json).code "A0103" "Across-aisle seats code=A0103"

    # 刷新限流窗口后提交合法连座 [3,4]
    Start-Sleep -Seconds 2
    $reqIdA = "e2e-req-" + [Guid]::NewGuid().ToString("N").Substring(0, 16)
    $orderBodyA = "{`"showId`":10002,`"tierId`":20004,`"qty`":2,`"zoneId`":`"HALL_A`",`"seatIndices`":[3,4],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":88000,`"seckillToken`":`"$seckillTokenA`",`"clientRequestId`":`"$reqIdA`"}"
    $orderRespA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $orderBodyA -Headers @{ "Authorization" = "Bearer $jwtA"; "X-Trace-Id" = $traceMain; "X-Show-Id" = "10002" }
    Assert-Eq $orderRespA.StatusCode 202 "V4 accepted returns HTTP 202"
    $orderJsonA = $orderRespA.Body | ConvertFrom-Json
    Assert-Eq $orderJsonA.code "00202" "V4 accepted returns code=00202"
    Assert-Eq $orderJsonA.data.status "PENDING_CONFIRM" "V4 accepted status=PENDING_CONFIRM"
    $orderNoA = $orderJsonA.data.orderNo
    Assert-True ($orderRespA.Body -match "`"orderNo`":`"$orderNoA`"") "orderNo serialized as JSON String (no JS precision loss)"
    Assert-True ($orderRespA.Body -match "`"amountCent`":`"176000`"") "amountCent serialized as JSON String 176000"
    Assert-True ($orderRespA.HeadersText -match $traceMain) "TraceId echoed on V4 202 response"

    Write-Output "`n=== [$Mode] E2E Step 4: Replay Recovery, Tamper A0307, Poll -> WAIT_PAY, Cross-Device /current & List ==="
    # 同请求重放（换过期 Token）找回同一原单号
    $replayBodyA = "{`"showId`":10002,`"tierId`":20004,`"qty`":2,`"zoneId`":`"HALL_A`",`"seatIndices`":[4,3],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":88000,`"seckillToken`":`"expired-tok`",`"clientRequestId`":`"$reqIdA`"}"
    $replayRespA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $replayBodyA -Headers @{ "Authorization" = "Bearer $jwtA" }
    $replayJsonA = $replayRespA.Body | ConvertFrom-Json
    Assert-Eq $replayJsonA.data.orderNo $orderNoA "Replay with same clientRequestId recovers identical orderNo"

    # 同 clientRequestId 篡改参数 -> A0307
    $tamperBodyA = "{`"showId`":10002,`"tierId`":20004,`"qty`":1,`"zoneId`":`"HALL_A`",`"seatIndices`":[3],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":88000,`"seckillToken`":`"expired-tok`",`"clientRequestId`":`"$reqIdA`"}"
    $tamperRespA = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $tamperBodyA -Headers @{ "Authorization" = "Bearer $jwtA" }
    Assert-Eq ($tamperRespA.Body | ConvertFrom-Json).code "A0307" "Tampered clientRequestId returns A0307"

    # 轮询 GET /api/v1/orders/{orderNo}/status?showId=10002 直到 WAIT_PAY
    $finalStatus = ""
    for ($i = 0; $i -lt 40; $i++) {
        $stResp = Invoke-CurlApi -Method GET -Url "$gwBase/api/v1/orders/$orderNoA/status?showId=10002" -Headers @{ "Authorization" = "Bearer $jwtA" }
        $stJson = $stResp.Body | ConvertFrom-Json
        $finalStatus = $stJson.data.status
        if ($finalStatus -eq "WAIT_PAY") { break }
        Start-Sleep -Milliseconds 300
    }
    Assert-Eq $finalStatus "WAIT_PAY" "Order $orderNoA asynchronously persisted as WAIT_PAY"

    # 换设备找回 GET /api/v1/orders/current?showId=10002
    $curResp = Invoke-CurlApi -Method GET -Url "$gwBase/api/v1/orders/current?showId=10002" -Headers @{ "Authorization" = "Bearer $jwtA" }
    $curJson = $curResp.Body | ConvertFrom-Json
    Assert-Eq $curJson.data.hasOrder $true "GET /orders/current hasOrder=true"
    Assert-Eq $curJson.data.orderNo $orderNoA "GET /orders/current recovers orderNo"
    Assert-Eq $curJson.data.status "WAIT_PAY" "GET /orders/current status=WAIT_PAY"

    # 详情与列表
    $detailResp = Invoke-CurlApi -Method GET -Url "$gwBase/api/v1/orders/$orderNoA" -Headers @{ "Authorization" = "Bearer $jwtA" }
    $detailJson = $detailResp.Body | ConvertFrom-Json
    Assert-Eq $detailJson.code "00000" "GET /orders/{orderNo} code=00000"
    Assert-Eq $detailJson.data.amountCent "176000" "Order detail amountCent=176000"
    Assert-Eq ($detailJson.data.seatIndices -join ",") "3,4" "Order detail seatIndices=3,4"

    $listResp = Invoke-CurlApi -Method GET -Url "$gwBase/api/v1/orders?size=10" -Headers @{ "Authorization" = "Bearer $jwtA" }
    $listJson = $listResp.Body | ConvertFrom-Json
    Assert-Eq $listJson.code "00000" "GET /orders list code=00000"
    Assert-Eq $listJson.data.items.Count 1 "GET /orders returns 1 order from single user shard"

    Write-Output "`n=== [$Mode] E2E Step 5: User B Cross-User A0304, Seat Conflict A0204, Gateway Rate-Limit 429/B0101 ==="
    $phoneB = "1390003" + (Get-Random -Minimum 1000 -Maximum 9999)
    Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/auth/register" -Body "{`"phone`":`"$phoneB`",`"password`":`"Pass123456!`",`"nickname`":`"FanB`"}" | Out-Null
    $loginB = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/auth/login" -Body "{`"phone`":`"$phoneB`",`"password`":`"Pass123456!`"}"
    $jwtB = ($loginB.Body | ConvertFrom-Json).data.token
    $idCardB = Build-SampleIdCard ("32010119960718" + (Get-Random -Minimum 100 -Maximum 999))
    Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/users/identity" -Body "{`"realName`":`"李演二`",`"idCard`":`"$idCardB`"}" -Headers @{ "Authorization" = "Bearer $jwtB" } | Out-Null

    # 越权查 A 的订单 -> A0304
    $crossDetail = Invoke-CurlApi -Method GET -Url "$gwBase/api/v1/orders/$orderNoA" -Headers @{ "Authorization" = "Bearer $jwtB" }
    Assert-Eq ($crossDetail.Body | ConvertFrom-Json).code "A0304" "Cross-user GET /orders/{orderNo} rejected with A0304"

    # B 申请凭证并抢同一连座 [3,4] -> A0204 + conflictSeatIndices
    $tokB = (Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/token" -Body '{"showId":10002,"saleConfigVersion":1}' -Headers @{ "Authorization" = "Bearer $jwtB" }).Body | ConvertFrom-Json
    $conflictBodyB = "{`"showId`":10002,`"tierId`":20004,`"qty`":2,`"zoneId`":`"HALL_A`",`"seatIndices`":[3,4],`"layoutVersion`":`"1`",`"saleConfigVersion`":1,`"expectedUnitPriceCent`":88000,`"seckillToken`":`"$($tokB.data.seckillToken)`",`"clientRequestId`":`"e2e-conflict-b-01`"}"
    $conflictRespB = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/orders" -Body $conflictBodyB -Headers @{ "Authorization" = "Bearer $jwtB" }
    $conflictJsonB = $conflictRespB.Body | ConvertFrom-Json
    Assert-Eq $conflictJsonB.code "A0204" "Occupied seat pair returns A0204"
    Assert-Eq ($conflictJsonB.data.conflictSeatIndices -join ",") "3,4" "A0204 returns conflictSeatIndices=3,4"

    # 网关限流实测（userLimit=3/2s）：用户 B 连续打 5 次 /seckill/token，必触发 HTTP 429 + B0101
    $got429 = $false
    $traceRl = "c3e2etrace0000000000000000000429"
    for ($k = 0; $k -lt 5; $k++) {
        $rlResp = Invoke-CurlApi -Method POST -Url "$gwBase/api/v1/seckill/token" `
            -Body '{"showId":10002,"saleConfigVersion":1}' `
            -Headers @{ "Authorization" = "Bearer $jwtB"; "X-Trace-Id" = $traceRl }
        if ($rlResp.StatusCode -eq 429) {
            Assert-Eq ($rlResp.Body | ConvertFrom-Json).code "B0101" "Gateway rate-limit returns B0101"
            Assert-True ($rlResp.HeadersText -match $traceRl) "Gateway 429 echoes X-Trace-Id"
            $got429 = $true
            break
        }
    }
    Assert-True $got429 "Gateway seckill write rate-limit triggered HTTP 429/B0101"

    Write-Output "`n=== [$Mode] E2E Step 6: Verify DB/Redis Consistency & Gene Shard Routing ==="
    $dbStock = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N $TestDatabase -e "SELECT available_stock FROM t_ticket_tier WHERE id=20004;").Trim()
    Assert-Eq $dbStock "98" "DB t_ticket_tier.available_stock == 98"
    $redisStock = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb GET "starshow:stock:{10002}:20004").Trim()
    Assert-Eq $redisStock "98" "Redis starshow:stock:{10002}:20004 == 98"
    $pendingZcard = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb ZCARD "starshow:deduct_pending:{10002}").Trim()
    Assert-Eq $pendingZcard "0" "Redis deduct_pending ZSET drained to 0"

    $shardIdx = [int]([int64]$userIdA % 4)
    $shardCount = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N $TestDatabase -e "SELECT COUNT(*) FROM t_order_$shardIdx WHERE order_no=$orderNoA AND status='WAIT_PAY';").Trim()
    Assert-Eq $shardCount "1" "Order landed precisely in gene shard t_order_$shardIdx"
    $closeActionStatus = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N $TestDatabase -e "SELECT status FROM t_order_action WHERE order_no=$orderNoA AND action_type='CLOSE_CHECK';").Trim()
    Assert-Eq $closeActionStatus "SENT" "CLOSE_CHECK action persisted & dispatched (SENT)"

    Write-Output "`n[ALL PASS] Chain 3 E2E ($Mode) passed 100%!"
} finally {
    foreach ($entry in $startedProcs) {
        try {
            if (-not $entry.Proc.HasExited) {
                $entry.Proc.Kill($true)
                $entry.Proc.WaitForExit(5000) | Out-Null
            }
            if ($entry.OutTask.IsCompleted) { [System.IO.File]::WriteAllText($entry.OutLog, $entry.OutTask.Result) }
            if ($entry.ErrTask.IsCompleted) { [System.IO.File]::WriteAllText($entry.ErrLog, $entry.ErrTask.Result) }
        } catch {}
    }

    docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -e "DROP DATABASE IF EXISTS ``$TestDatabase``;" | Out-Null
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n $TestRedisDb FLUSHDB | Out-Null

    $defaultShowCountAfter = (docker exec -e MYSQL_PWD=$env:MYSQL_PASSWORD $MysqlContainer mysql -uroot -N -e "SELECT COUNT(*) FROM starshow.t_show;").Trim()
    $sentinelAfter = (docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 GET $sentinelKey).Trim()
    docker exec -e REDISCLI_AUTH=$env:REDIS_PASSWORD $RedisContainer redis-cli -n 0 DEL $sentinelKey | Out-Null

    Write-Output "[SENTINEL] default starshow.t_show: before=$defaultShowCountBefore, after=$defaultShowCountAfter; redis DB 0 sentinel=$sentinelAfter"
    if ($defaultShowCountBefore -ne $defaultShowCountAfter -or $sentinelAfter -ne $sentinelVal) {
        throw "SENTINEL CORRUPTED! Default DB or Redis DB 0 changed unexpectedly!"
    }
    Write-Output "[CLEANUP] Destroyed isolated DB '$TestDatabase', flushed Redis DB $TestRedisDb, stopped $($startedProcs.Count) processes."
}
