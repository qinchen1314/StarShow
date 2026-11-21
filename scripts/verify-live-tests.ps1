# Runs one live middleware suite against a disposable MySQL schema and empty Redis DB.
param(
    [Parameter(Mandatory)]
    [ValidateSet("Chain1", "Chain2", "Chain3", "Chain4", "Chain5")]
    [string]$Chain,
    [switch]$Distributed,
    [switch]$FromPhase2Schema,
    [switch]$RefundCrash,
    [ValidateRange(1, 15)]
    [int]$TestRedisDb = 12,
    [string]$MysqlContainer = "starshow-mysql",
    [string]$RedisContainer = "starshow-redis"
)

$ErrorActionPreference = "Stop"
if (($Distributed -or $FromPhase2Schema -or $RefundCrash) -and $Chain -ne 'Chain4') { throw "This option requires Chain4" }
$envKeyNames = @("MYSQL_PASSWORD", "REDIS_PASSWORD", "MYSQL_PWD", "REDISCLI_AUTH", "MYSQL_DATABASE", "MYSQL_PORT", "REDIS_PORT", "REDIS_DATABASE",
    "JAVA_TOOL_OPTIONS", "STARSHOW_JWT_SECRET", "STARSHOW_AES_KEY", "STARSHOW_IDCARD_PEPPER",
    "STARSHOW_INTERNAL_GATEWAY_SECRET", "STARSHOW_MOCK_PAY_SIGN_SECRET", "STARSHOW_TICKET_SIGN_SECRET")
$saved = @{}
foreach ($key in $envKeyNames) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key)
}

function Invoke-Docker([string[]]$Arguments) {
    $output = docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed with exit code $LASTEXITCODE" }
    return $output
}

function New-TestSecret() {
    return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}

$database = "starshow_c" + $Chain.Substring(5) + "_" + [Guid]::NewGuid().ToString("N").Substring(0, 12)
$created = $false
$ownsRedis = $false
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if (-not $env:MYSQL_PASSWORD) {
        $env:MYSQL_PASSWORD = Invoke-Docker @("exec", $MysqlContainer, "printenv", "MYSQL_ROOT_PASSWORD")
    }
    if (-not $env:REDIS_PASSWORD) {
        $redisPassword = docker exec $RedisContainer printenv REDIS_PASSWORD
        if ($LASTEXITCODE -eq 0) {
            $env:REDIS_PASSWORD = $redisPassword
        } else {
            throw "Set REDIS_PASSWORD to use the authenticated Redis test container"
        }
    }
    $env:MYSQL_DATABASE = $database
    if (-not $env:MYSQL_PORT) {
        $port = Invoke-Docker @("port", $MysqlContainer, "3306/tcp") | Select-Object -First 1
        $env:MYSQL_PORT = $port.Split(":")[-1]
    }
    if (-not $env:REDIS_PORT) {
        $port = Invoke-Docker @("port", $RedisContainer, "6379/tcp") | Select-Object -First 1
        $env:REDIS_PORT = $port.Split(":")[-1]
    }
    $env:REDIS_DATABASE = [string]$TestRedisDb
    $env:JAVA_TOOL_OPTIONS = "-Xms32m -Xmx288m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=4 -Dio.netty.eventLoopThreads=4 -Dspring.test.context.cache.maxSize=1 -Duser.timezone=Asia/Shanghai"
    foreach ($secret in $envKeyNames | Where-Object { $_ -match 'SECRET$|PEPPER$|AES_KEY$' }) {
        [Environment]::SetEnvironmentVariable($secret, (New-TestSecret))
    }
    $env:STARSHOW_AES_KEY = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $env:REDISCLI_AUTH = $env:REDIS_PASSWORD
    $mysql = @("exec", "-i", "-e", "MYSQL_PWD", $MysqlContainer, "mysql", "-uroot")
    $redis = @("exec")
    if ($env:REDISCLI_AUTH) { $redis += @("-e", "REDISCLI_AUTH") }
    $redis += @($RedisContainer, "redis-cli", "-n", "$TestRedisDb")
    if ((Invoke-Docker ($redis + "DBSIZE")) -ne "0") { throw "Redis test DB $TestRedisDb is not empty" }
    $ownsRedis = $true
    Invoke-Docker ($mysql + @("-e", "CREATE DATABASE $database CHARACTER SET utf8mb4")) | Out-Null
    $created = $true
    if ($FromPhase2Schema) {
        $schema = (git show "e550646:docker/mysql/init/01_schema.sql") -join [Environment]::NewLine
        if ($LASTEXITCODE -ne 0) { throw "The Phase 2 baseline commit is required" }
    } else {
        $schema = Get-Content -Raw -Encoding utf8 "docker/mysql/init/01_schema.sql"
    }
    $schema = $schema -replace '(?im)^CREATE DATABASE [^;]+;\s*', '' -replace '(?im)^USE [^;]+;\s*', ''
    $seed = (Get-Content -Raw -Encoding utf8 "docker/mysql/init/02_seed.sql") -replace '(?im)^USE [^;]+;\s*', ''
    $schema | docker @mysql --default-character-set=utf8mb4 --one-database $database
    if ($LASTEXITCODE -ne 0) { throw "Schema initialization failed" }
    $seed | docker @mysql --default-character-set=utf8mb4 --one-database $database
    if ($LASTEXITCODE -ne 0) { throw "Seed initialization failed" }
    if ($Chain -eq "Chain4") {
        if ($FromPhase2Schema) {
            Invoke-Docker ($mysql + @($database, "-e", "INSERT INTO t_order_0 (order_no,user_id,show_id,tier_id,amount_cent,status,expire_time) VALUES (11712,4,10001,20001,6800,'CLOSED',NOW())")) | Out-Null
        }
        foreach ($migration in @('V3__phase3_order_seckill.sql', 'V4__phase4_payment_fulfillment.sql')) {
            foreach ($pass in 1..2) {
                Get-Content -Raw -Encoding utf8 "docker/mysql/migrations/$migration" |
                    docker @mysql --default-character-set=utf8mb4 --one-database $database
                if ($LASTEXITCODE -ne 0) { throw "$migration pass $pass failed in $database" }
            }
        }
        if ($FromPhase2Schema) {
            $row = Invoke-Docker ($mysql + @($database, "-N", "-e", "SELECT COUNT(*) FROM t_order_0 WHERE order_no=11712 AND amount_cent=6800 AND status='CLOSED'"))
            if ($row -ne "1") { throw "Legacy order did not survive migration replay" }
            Write-Output "[PASS] Phase 2 legacy order survived V3 and V4 migrations and repeat execution"
        }
        foreach ($topic in @("TOPIC_TICKET_ISSUE", "TOPIC_REFUND_EVENT")) {
            Invoke-Docker @("exec", "starshow-rmq-broker", "sh", "mqadmin", "updateTopic",
                "-n", "rmqnamesrv:9876", "-c", "StarShowCluster", "-t", $topic) | Out-Null
        }
    }
    $suite = if ($RefundCrash) { "RefundCrashRecoveryTest" } else { "${Chain}LiveMiddlewareVerificationTest" }
    $log = "starshow-boot-all/target/$suite.log"
    New-Item -ItemType Directory -Force "starshow-boot-all/target" | Out-Null
    Write-Output "Running $Chain with Redis DB $TestRedisDb and a disposable MySQL schema"
    $mavenArgs = @("-B", "-T", "1", "-pl", "starshow-boot-all", "-am", "-Dtest=$suite",
        "-Dsurefire.failIfNoSpecifiedTests=false", "-Dstarshow.live.enabled=true", "-DforkCount=1", "test")
    mvn @mavenArgs *> $log
    $result = $LASTEXITCODE
    Select-String -Path $log -Pattern 'Tests run:|BUILD (SUCCESS|FAILURE)|[Ll][23].*(passed|PASS|oversell)|BENCH_(ENV|RESULT)|REFUND_CRASH_' | ForEach-Object Line
    if ($result -ne 0) { throw "$Chain exited with status $result. See $log" }
    if ($Distributed) {
        & "$PSScriptRoot/verify-chain4-distributed.ps1" -MysqlContainer $MysqlContainer -RedisContainer $RedisContainer
    }
} finally {
    try {
        if ($created) { Invoke-Docker ($mysql + @("-e", "DROP DATABASE $database")) | Out-Null }
    } finally {
        try {
            if ($ownsRedis) { Invoke-Docker ($redis + "FLUSHDB") | Out-Null }
        } finally {
            foreach ($key in $envKeyNames) { [Environment]::SetEnvironmentVariable($key, $saved[$key]) }
            Pop-Location
        }
    }
}
