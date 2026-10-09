# Run it FROM THE REPOSITORY ROOT:
#     powershell -ExecutionPolicy Bypass -File tools\verify\verify-5b.ps1 -LogFile picture-backend\app.log
#
# Requirements:
#   - RabbitMQ up, MySQL up
#   - the monolith running on 8128 with MQ ENABLED and the debug endpoints on:
#         $env:PICTURE_MQ_ENABLED='true'; $env:PICTURE_MQ_DEBUG='true'
#         mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8128
#   - the Python index service on 8001 must be up (the consumer really calls it)
#
# What this verifies (phase 5b): the INDEX write path now goes through MQ.
#   1. topology: picture.mq.index (+ dlq + retry) declared, business queue has a consumer
#   2. publish -> consume: an index request actually indexes the picture
#   3. ACK: the queue drains (no message stuck unacked)
#   4. IDEMPOTENCY: the same messageId twice is only indexed once
#   5. a non-existent pictureId is "skipped and acked", NOT left piling up
#
# Design note - this script creates NO data and deletes nothing:
# it re-indexes a picture that already exists in the public gallery. Re-indexing is
# idempotent by nature (it overwrites the vector entry), so the script is safe to re-run.
# The real upload path was verified separately by uploading a throwaway picture and
# hard-deleting it afterwards; that path needs COS and is not automatable here.

param(
    [string]$Base = 'http://127.0.0.1:8128',
    [string]$RabbitApi = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'admin',
    [string]$RabbitPassword = 'change-me-dev-only',
    [string]$IndexQueue = 'picture.mq.index',
    [string]$LogFile = '',
    # 单张索引要调多模态模型，可能几十秒
    [int]$IndexWaitSeconds = 60
)

$ErrorActionPreference = 'Continue'
$script:pass = 0; $script:fail = 0
function Assert($name, $ok, $detail = '') {
    if ($ok) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  $detail" -ForegroundColor Red }
}
$cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
function QueueStat($name) {
    $q = curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/$name"
    if (-not $q) { return $null }
    try { return ($q | ConvertFrom-Json) } catch { return $null }
}
function ReadLog($path) {
    $fs = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    $all = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
    return $all
}
# 日志断言必须**轮询等待**，不能读一次就下结论：
# 「队列已排空」与「日志已写完」之间没有先后保证（实测踩过 —— 队列空了但成功日志还没落盘，
# 断言随机失败）。这里等到出现就返回，超时才判定失败。
function WaitLogCount($path, $needle, $atLeast, $timeoutSec, $midFilter = '') {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $count = 0
    while ((Get-Date) -lt $deadline) {
        $lines = (ReadLog $path) -split "`r?`n"
        if ($midFilter) { $lines = $lines | Where-Object { $_ -match [regex]::Escape($midFilter) } }
        $count = ($lines | Select-String -SimpleMatch $needle).Count
        if ($count -ge $atLeast) { return $count }
        Start-Sleep -Seconds 2
    }
    return $count
}
function WaitQueueEmpty($name, $timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = QueueStat $name
        if ($null -eq $s) { return $null }
        if ($s.messages -eq 0) { return $s }
        Start-Sleep -Seconds 2
    }
    return (QueueStat $name)
}

Write-Host "`n===== 1. 索引队列拓扑 ====="
$stat = QueueStat $IndexQueue
if ($null -eq $stat) {
    Write-Host "FATAL: 连不上 RabbitMQ（$RabbitApi）。先 docker compose up -d rabbitmq" -ForegroundColor Red
    exit 2
}
Assert "索引队列 $IndexQueue 存在" ($null -ne $stat.name) ''
Assert '索引队列 durable' ($stat.durable -eq $true) "durable=$($stat.durable)"
Assert '索引队列有消费者' ($stat.consumers -ge 1) "consumers=$($stat.consumers)"
foreach ($n in @("$IndexQueue.dlq", "$IndexQueue.retry")) {
    $s = QueueStat $n
    Assert "辅助队列 $n 存在" ($null -ne $s -and $null -ne $s.name) ''
}

Write-Host "`n===== 2. 取一张已存在的图片（不新建数据）====="
$f = Join-Path $env:TEMP 'v5b-page.json'
[System.IO.File]::WriteAllText($f, '{"current":1,"pageSize":1}', (New-Object System.Text.ASCIIEncoding))
$page = curl.exe -s -X POST "$Base/api/list/page/vo" -H 'Content-Type: application/json' --data-binary "@$f"
$picId = [regex]::Match($page, '"id":"?(\d{15,})"?').Groups[1].Value
Assert '从公开图库取到一张图片' ($picId.Length -ge 15) $page.Substring(0, [Math]::Min(120, $page.Length))
if ($picId.Length -lt 15) { Write-Host "无法继续：需要图库里至少有一张图片" -ForegroundColor Red; exit 2 }
Write-Host "  用图片 id = $picId 做重新索引（重新索引是幂等的，随时可重跑）"

Write-Host "`n===== 3. 投递索引请求 -> 消费 -> 队列排空 ====="
$mid = 'verify-5b-' + (Get-Date -Format 'HHmmssfff')
$r = curl.exe -s -X POST "$Base/api/mq/debug/publish-index?pictureId=$picId&messageId=$mid"
Assert '索引请求投递返回 code 0' ($r -match '"code":0') $r
$after = WaitQueueEmpty $IndexQueue $IndexWaitSeconds
Assert '索引队列已排空（消息被 ack，没有卡住）' ($after.messages -eq 0) "messages=$($after.messages)"
Assert '没有未确认消息（ack 正确）' ($after.messages_unacknowledged -eq 0) "unacked=$($after.messages_unacknowledged)"

if ($LogFile -and (Test-Path $LogFile)) {
    # 断言的是**公共层**的关联日志（MqConsumerSupport 统一打 messageId），
    # 而不是业务自己的日志 —— 业务日志不带 messageId，按它过滤必然落空
    $processed = WaitLogCount $LogFile '消息处理成功' 1 $IndexWaitSeconds $mid
    Assert '这条消息确实被消费并处理成功（按 messageId 关联到）' ($processed -ge 1) "matched=$processed"
    $indexed = WaitLogCount $LogFile '图片索引成功' 1 10
    Assert '业务侧确实完成了索引（图片索引成功）' ($indexed -ge 1) "matched=$indexed"
} else {
    Write-Host "  （未提供 -LogFile，跳过「消费成功」的日志断言）" -ForegroundColor DarkYellow
}

Write-Host "`n===== 4. 幂等：同一 messageId 再投两次，不应重复索引 ====="
foreach ($i in 1..2) {
    curl.exe -s -X POST "$Base/api/mq/debug/publish-index?pictureId=$picId&messageId=$mid" | Out-Null
    Start-Sleep -Milliseconds 500
}
$after2 = WaitQueueEmpty $IndexQueue 20
Assert '重复投递后队列仍能排空' ($after2.messages -eq 0) "messages=$($after2.messages)"
if ($LogFile -and (Test-Path $LogFile)) {
    $deduped = WaitLogCount $LogFile '重复消息已被幂等去重丢弃' 2 30 $mid
    Assert '重复的 2 条被幂等去重丢弃' ($deduped -eq 2) "deduped=$deduped"
} else {
    Write-Host "  （未提供 -LogFile，跳过幂等日志断言）" -ForegroundColor DarkYellow
}

Write-Host "`n===== 5. 不存在的图片：应「跳过并 ack」，不能堆在队列里 ====="
$missingMid = 'verify-5b-missing-' + (Get-Date -Format 'HHmmssfff')
$r = curl.exe -s -X POST "$Base/api/mq/debug/publish-index?pictureId=999999999999&messageId=$missingMid"
Assert '不存在的图片也能投递' ($r -match '"code":0') $r
$after3 = WaitQueueEmpty $IndexQueue 20
Assert '不存在的图片被跳过并 ack（队列排空）' ($after3.messages -eq 0) "messages=$($after3.messages)"
if ($LogFile -and (Test-Path $LogFile)) {
    # 「图片不存在，跳过」这类业务日志不带 messageId，所以全局找；它只可能由这次投递产生
    # （唯一会跳过的场景就是查不到图片，而上一步刚验证过正常图片能索引成功）
    $skipped = WaitLogCount $LogFile '图片不存在或已删除' 1 15
    Assert '日志记录了「图片不存在，跳过」' ($skipped -ge 1) "matched=$skipped"
    $ackedSkipped = WaitLogCount $LogFile '消息处理成功' 1 15 $missingMid
    Assert '跳过也算处理成功（已 ack，不会重投）' ($ackedSkipped -ge 1) "matched=$ackedSkipped"
}
Assert '死信队列为空（没有误入死信）' ((QueueStat "$IndexQueue.dlq").messages -eq 0) ''

Write-Host "`n================ RESULT ================"
Write-Host "PASS = $script:pass, FAIL = $script:fail"
if ($script:fail -gt 0) { exit 1 }
