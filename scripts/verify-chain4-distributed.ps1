# Called by verify-live-tests.ps1 after it provisions an isolated Phase 4 schema and Redis DB.
param(
    [Parameter(Mandatory)]
    [string]$MysqlContainer,
    [Parameter(Mandatory)]
    [string]$RedisContainer
)

$ErrorActionPreference = "Stop"
if ($env:MYSQL_DATABASE -notmatch '^starshow_c4_[0-9a-f]{12}$' -or [int]$env:REDIS_DATABASE -notin 1..15) {
    throw "A dedicated Chain 4 database and non-default Redis DB are required"
}

function Get-FreePort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Wait-For([scriptblock]$Probe, [string]$Description, [int]$Seconds = 35) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $result = & $Probe
            if ($result) { return $result }
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description"
}

function Invoke-Api([string]$Uri, [string]$Method = "Get", $Body = $null) {
    $request = @{ Uri = $Uri; Method = $Method; Headers = $headers; TimeoutSec = 5 }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = ConvertTo-Json -InputObject $Body -Compress
    }
    $answer = Invoke-RestMethod @request
    if ($answer.code -ne "00000") { throw "API returned $($answer.code) for $Uri" }
    return $answer.data
}

function Start-ServiceJar([string]$module, [int]$port, [string[]]$extra) {
    $jar = Get-ChildItem "$module/target" -Filter "*-exec.jar" | Select-Object -First 1
    if ($null -eq $jar) { throw "Package $module before running this verification" }
    $arguments = @("-Xmx192m", "-jar", ('"{0}"' -f $jar.FullName), "--server.port=$port",
        "--server.address=127.0.0.1", "--spring.profiles.active=distributed",
        "--spring.datasource.hikari.maximum-pool-size=5", "--spring.datasource.hikari.minimum-idle=1",
        "--starshow.payment.enabled=true", "--starshow.seckill.mq-enabled=false",
        "--starshow.seckill.timeout-dispatch-enabled=false", "--starshow.seckill.pending-reconcile-enabled=false") + $extra
    $logPrefix = "starshow-boot-all/target/phase4-distributed-$module"
    $launch = @{ FilePath = "java"; ArgumentList = $arguments; WindowStyle = "Hidden"; PassThru = $true
        RedirectStandardOutput = "$logPrefix.out.log"; RedirectStandardError = "$logPrefix.err.log" }
    return Start-Process @launch
}

$orderPort = Get-FreePort
$orderBase = "http://127.0.0.1:$orderPort"
do { $paymentPort = Get-FreePort } while ($paymentPort -eq $orderPort)
$paymentBase = "http://127.0.0.1:$paymentPort"
$headers = @{
    "X-Internal-Gateway-Secret" = $env:STARSHOW_INTERNAL_GATEWAY_SECRET
    "X-User-Id" = "777071"
}
$orderNo = "91000015"
$unpaidNo = "91000030"
$redis = @("exec", "-e", "REDISCLI_AUTH", $RedisContainer, "redis-cli", "-n", $env:REDIS_DATABASE)
$mysql = @("exec", "-i", "-e", "MYSQL_PWD", $MysqlContainer, "mysql", "-uroot", $env:MYSQL_DATABASE)
@"
INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status)
VALUES (967711,'Phase 4','Shanghai','Demo',DATE_ADD(NOW(),INTERVAL 1 DAY),NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR),'ON_SALE');
INSERT INTO t_ticket_tier (id,show_id,tier_name,price_cent,total_stock,available_stock)
VALUES (967712,967711,'Demo',6800,2,0);
INSERT INTO t_order_3 (order_no,user_id,show_id,tier_id,amount_cent,status,expire_time)
VALUES ($orderNo,777071,967711,967712,6800,'WAIT_PAY',DATE_ADD(NOW(),INTERVAL 15 MINUTE));
INSERT INTO t_order_2 (order_no,user_id,show_id,tier_id,amount_cent,status,expire_time)
VALUES ($unpaidNo,777070,967711,967712,6800,'WAIT_PAY',DATE_ADD(NOW(),INTERVAL 15 MINUTE));
"@ | docker @mysql
if ($LASTEXITCODE -ne 0) { throw "Failed to seed distributed orders" }
docker @redis SET 'starshow:stock:{967711}:967712' 0 | Out-Null
docker @redis SADD 'starshow:bought:{967711}' 777071 777070 | Out-Null
docker @redis HSET 'starshow:bought_owner:{967711}' 777071 $orderNo 777070 $unpaidNo | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to seed Redis order ownership" }

$started = @()
try {
    $order = Start-ServiceJar 'starshow-order' $orderPort @("--starshow.services.payment-uri=$paymentBase")
    $started += $order
    Wait-For { if ((Invoke-RestMethod "$orderBase/actuator/health" -TimeoutSec 2).status -eq 'UP') { $true } } 'order health' | Out-Null
    $payment = Start-ServiceJar 'starshow-payment' $paymentPort @(
        "--starshow.services.order-uri=$orderBase", "--starshow.payment.mock-enabled=true")
    $started += $payment
    Wait-For { if ((Invoke-RestMethod "$paymentBase/actuator/health" -TimeoutSec 2).status -eq 'UP') { $true } } 'payment health' | Out-Null

    $pay = Invoke-Api "$paymentBase/api/v1/payments" Post @{ orderNo = $orderNo }
    if ($pay.amountCent -ne '6800' -or $pay.nextAction -ne 'PAY') { throw "Unexpected payment details" }
    Invoke-Api "$paymentBase/api/v1/payments/mock/pay/$($pay.payNo)" Post | Out-Null
    $tickets = Wait-For { $view = Invoke-Api "$orderBase/api/v1/tickets/order/$orderNo"; if ($view.issueStatus -eq 'ISSUED') { $view } } 'ticket issue'
    if ($tickets.tickets.Count -ne 1 -or -not $tickets.tickets[0].qrCodeToken) { throw "Expected one usable ticket" }
    $refund = Invoke-Api "$paymentBase/api/v1/refunds" Post @{ orderNo = $orderNo; reason = 'Smoke test' }
    Wait-For {
        $view = Invoke-Api "$paymentBase/api/v1/refunds/order/$orderNo"
        if ($view.refundStatus -eq 'SUCCESS' -and $view.payRefundStatus -eq 'SUCCESS') { $true }
    } "refund $($refund.refundNo) and stock release" | Out-Null

    $headers["X-User-Id"] = "777070"
    $cancel = Invoke-Api "$orderBase/api/v1/orders/$unpaidNo/cancel" Post
    if ($cancel.orderStatus -ne "CLOSED") { throw "Unpaid order did not close" }
    Wait-For {
        if ((docker @redis GET 'starshow:stock:{967711}:967712') -eq "2") { $true }
    } 'single Redis release for each order' | Out-Null
    $dbStock = docker @mysql -N -e "SELECT available_stock FROM t_ticket_tier WHERE id=967712"
    if ($dbStock -ne "2") { throw "MySQL stock did not converge to 2" }
    Write-Output "[PASS] Distributed order and payment release jars. One ticket, one refund, two stock releases. Redis and MySQL stock both 2."
} finally {
    foreach ($process in $started) {
        if (-not $process.HasExited) { Stop-Process -InputObject $process }
    }
}
