# Run it FROM THE REPOSITORY ROOT:
#     powershell -ExecutionPolicy Bypass -File tools\verify\verify-5a.ps1
#
# Requirements:
#   - RabbitMQ up (docker compose up -d rabbitmq), management API on 15672
#   - MySQL up
#   - the monolith running with MQ ENABLED on port 8128:
#         $env:PICTURE_MQ_ENABLED='true'; $env:PICTURE_MQ_DEBUG='true'
#         mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8128
#   - if the monolith is started with -Dlogging.config=classpath:logback-file.xml,
#     pass -LogFile picture-backend\app.log so the "consume happened" assertions can be checked
#
# What this verifies (phase 5a groundwork only - no business path is touched yet):
#   1. topology declared: 3 queues + 3 exchanges, business queue has a consumer
#   2. publish -> consume works end to end (JSON converter, envelope intact)
#   3. IDEMPOTENCY: the same messageId delivered N times is processed exactly ONCE
#   4. ACK: the queue drains to 0 - an unacked message would sit in messages_unacknowledged
#      (this caught a real bug: manual-ack mode with no basicAck left every message unacked
#       and, with prefetch=1, blocked every message behind it)
#
# Why a script for this: both failures above are invisible from "the API returned 200".
# The idempotency one needs a duplicate delivery; the ack one needs the broker's own counters.

param(
    [string]$Base = 'http://127.0.0.1:8128',
    [string]$RabbitApi = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'admin',
    [string]$RabbitPassword = 'change-me-dev-only',
    [string]$Queue = 'picture.mq.test',
    [string]$LogFile = '',
    [int]$WaitSeconds = 4
)

$ErrorActionPreference = 'Continue'
# 计数器必须走 $script: 作用域：Assert 是函数，函数里的 $pass++ 只会改函数局部的副本，
# 结果就是「断言全打 [PASS]，最后 PASS=0 FAIL=0」（这里踩过一次）
$script:pass = 0; $script:fail = 0
function Assert($name, $ok, $detail = '') {
    if ($ok) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  $detail" -ForegroundColor Red }
}
$cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
function QueueStat {
    $q = curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/$Queue"
    if (-not $q) { return $null }
    try { return ($q | ConvertFrom-Json) } catch { return $null }
}

Write-Host "`n===== 1. RabbitMQ 拓扑已被应用声明 ====="
$stat = QueueStat
if ($null -eq $stat) {
    Write-Host "FATAL: 连不上 RabbitMQ 管理接口（$RabbitApi）。先 docker compose up -d rabbitmq" -ForegroundColor Red
    exit 2
}
Assert "业务队列 $Queue 存在" ($null -ne $stat.name) ''
Assert '业务队列是 durable（broker 重启不丢）' ($stat.durable -eq $true) "durable=$($stat.durable)"
Assert '业务队列有消费者（@RabbitListener 已生效）' ($stat.consumers -ge 1) "consumers=$($stat.consumers)"

# ⚠️ 这条断言防的是「消费者被静默中止」这个坑（实测踩到，而且很难联想到）：
# 队列是 durable 的，一旦已经用某个 x-message-ttl 建成，之后改小/改大这个参数再启动，
# broker 会拒绝重新声明并报 PRECONDITION_FAILED，**整个监听容器的连接会被中止** ——
# 症状是「队列里有消息、但 consumers=0，谁也不消费」，而应用本身看起来好好的。
# 最常见触发方式：改了 picture.mq.retry-delay-ms 之后重启（我用 1000 覆盖过默认的 5000）。
# 解法是删掉 retry 队列让它按新 TTL 重建：
#     docker exec sp-rabbitmq rabbitmqctl delete_queue picture.mq.index.retry
$retryQ = "$Queue.retry"
$retryStat = (curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/$retryQ") | ConvertFrom-Json
if ($null -ne $retryStat.name) {
    $ttl = $retryStat.arguments.'x-message-ttl'
    Assert "重试队列 $retryQ 的 TTL 与应用配置一致（消费者没被中止）" ($null -ne $ttl) "x-message-ttl=$ttl"
}

foreach ($q in @("$Queue.dlq", "$Queue.retry")) {
    $s = (curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/$q") | ConvertFrom-Json
    Assert "辅助队列 $q 存在" ($null -ne $s.name) ''
}
foreach ($ex in @('picture.mq', 'picture.mq.dlx', 'picture.mq.retry')) {
    $e = (curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/exchanges/%2F/$ex") | ConvertFrom-Json
    Assert "交换机 $ex 存在" ($null -ne $e.name) ''
}

Write-Host "`n===== 2. 投递 -> 消费 打通（JSON 转换 + 信封完整）====="
$mid = 'verify-5a-auto-' + (Get-Date -Format 'HHmmssfff')
$url = "$Base/api/mq/debug/publish-test?messageId=$mid&text=verify"
$r = curl.exe -s -X POST $url
Assert '自检投递接口返回 code 0' ($r -match '"code":0') $r

Write-Host "`n===== 3. 幂等：同一 messageId 投递 3 次，只应处理 1 次 ====="
# 连发两次（第一次已在上面发过），共 3 次
foreach ($i in 1..2) {
    curl.exe -s -X POST $url | Out-Null
    Start-Sleep -Milliseconds 700
}
Start-Sleep -Seconds $WaitSeconds
if ($LogFile -and (Test-Path $LogFile)) {
    # 全部投递完成后再读日志：读太早会漏掉后面的行
    $fs = [System.IO.File]::Open($LogFile, 'Open', 'Read', 'ReadWrite')
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $all = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
    $lines = ($all -split "`r?`n") | Where-Object { $_ -match [regex]::Escape($mid) }
    # 匹配 MqPublisher 投递成功那条（"消息已交给 broker：..."）。
    # 文案在 5c 改过（原先叫「消息已投递」，与「消费成功」容易混），断言要跟着走。
    $delivered = ($lines | Select-String -SimpleMatch '消息已交给 broker').Count
    # 匹配 MqConsumerSupport 的统一日志（"消息处理成功：messageId = ..."）。
    # 不能用 "处理成功" —— 它会同时命中监听器自己那条 "收到测试消息并处理成功"，
    # 于是 processed 变成 2（实测踩过）。
    $processed = ($lines | Select-String -SimpleMatch '消息处理成功：messageId').Count
    $deduped = ($lines | Select-String -SimpleMatch '重复消息已被幂等去重丢弃').Count
    Assert '3 次投递都被 broker 接收' ($delivered -eq 3) "delivered=$delivered"
    Assert '3 次投递只处理了 1 次' ($processed -eq 1) "processed=$processed"
    Assert '另外 2 次被幂等去重丢弃' ($deduped -eq 2) "deduped=$deduped"
    Assert '投递数 = 处理数 + 去重数' ($delivered -eq $processed + $deduped) "delivered=$delivered processed=$processed deduped=$deduped"
} else {
    Write-Host "  （未提供 -LogFile，跳过日志断言；幂等仍可用管理台/数据库核对）" -ForegroundColor DarkYellow
}

Write-Host "`n===== 4. ACK：消息必须被 ack 干净（unacked 会堵住整个队列）====="
$stat = QueueStat
Assert '业务队列里没有残留的未确认消息' ($stat.messages_unacknowledged -eq 0) "unacked=$($stat.messages_unacknowledged)"
Assert '业务队列已排空（没有卡住的消息）' ($stat.messages_ready -eq 0) "ready=$($stat.messages_ready)"

Write-Host "`n================ RESULT ================"
Write-Host "PASS = $pass, FAIL = $fail"
if ($fail -gt 0) { exit 1 }
