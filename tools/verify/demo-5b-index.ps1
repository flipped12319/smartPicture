# Run it FROM THE REPOSITORY ROOT, with the MQ-enabled backend already running:
#     run-backend-mq.cmd            (starts the monolith on 8128 with MQ on)
#     powershell -ExecutionPolicy Bypass -File tools\verify\demo-5b-index.ps1
#
# What you will see, in order:
#   1. the queue is empty and has a consumer
#   2. a picture is uploaded  -> the HTTP call returns WITHOUT waiting for indexing
#   3. a message appears in picture.mq.index (probably too fast to catch by eye)
#   4. the consumer indexes it and the queue drains back to 0
#   5. the log shows the whole chain correlated by messageId
#   6. the test picture is removed again (nothing is left behind)
#
# Why upload for real instead of publishing a message: the point of 5b is that the
# *upload path* no longer runs indexing on its own thread pool. Publishing a message
# directly would prove the consumer works but not that the trigger moved to MQ.

param(
    [string]$Base = 'http://127.0.0.1:8128',
    [string]$RabbitApi = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'admin',
    [string]$RabbitPassword = 'change-me-dev-only',
    [string]$IndexQueue = 'picture.mq.index',
    # 需要登录 token（管理员）。生成方式见 tools\verify\README.md
    [string]$Token = '',
    [string]$TokensFile = '.tmp-dbdump\tokens.txt',
    [string]$LogFile = 'picture-backend\app.log',
    [int]$IndexWaitSeconds = 90
)

$ErrorActionPreference = 'Continue'
function Step($n, $t) { Write-Host "`n[$n] $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "    OK   $t" -ForegroundColor Green }
function Info($t) { Write-Host "         $t" }

$cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))
function QueueStat {
    $q = curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/$IndexQueue"
    if (-not $q) { return $null }
    try { return ($q | ConvertFrom-Json) } catch { return $null }
}

Step 1 '前提检查'
$stat = QueueStat
if ($null -eq $stat) {
    Write-Host "    RabbitMQ 连不上（$RabbitApi）。先 docker compose up -d rabbitmq" -ForegroundColor Red; exit 2
}
Ok "索引队列 $IndexQueue 存在，消费者 = $($stat.consumers)，当前消息 = $($stat.messages)"
if ($stat.consumers -lt 1) {
    Write-Host "    没有消费者：后端是不是没带 MQ 启动？请用 run-backend-mq.cmd" -ForegroundColor Red; exit 2
}
$ping = curl.exe -s -o NUL -w "%{http_code}" -X POST "$Base/api/mq/debug/publish-test?text=ping"
if ($ping -ne '200') {
    Write-Host "    自检接口不可用（HTTP $ping）。需要 PICTURE_MQ_DEBUG=true，见 run-backend-mq.cmd" -ForegroundColor Red; exit 2
}
Ok "MQ 自检接口可用"

# 取 token
if (-not $Token) {
    if (Test-Path $TokensFile) {
        $line = (Get-Content $TokensFile | Select-String '^2052376987161366530=').Line
        if ($line) { $Token = ($line -replace '^2052376987161366530=','').Trim() }
    }
}
if (-not $Token) {
    Write-Host "    没有 token：请传 -Token <jwt>，或用 GenToken 生成 $TokensFile" -ForegroundColor Red
    Write-Host "    （GenToken 现在必须显式传密钥：java -Dsecret=<application.yml 里的 jwt.secret> ... GenToken <userId>）" -ForegroundColor Red
    Write-Host "    （见 tools\verify\README.md）" -ForegroundColor Red
    exit 2
}
Ok "拿到登录 token"

Step 2 '造一张测试图片并上传（走真实上传路径）'
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap 480,360
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::MidnightBlue)
$g.FillEllipse([System.Drawing.Brushes]::Gold, 100, 70, 280, 220)
$g.Dispose()
$img = Join-Path $env:TEMP 'demo-5b.png'
$bmp.Save($img, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Info "测试图片: $img ($((Get-Item $img).Length) bytes)"

$t0 = Get-Date
$resp = curl.exe -s -X POST "$Base/api/upload" -H "Authorization: Bearer $Token" -F "file=@$img"
$ms = [int]((Get-Date) - $t0).TotalMilliseconds
$picId = [regex]::Match($resp, '"id":"?(\d{15,})"?').Groups[1].Value
if ($picId.Length -lt 15) {
    Write-Host "    上传失败: $resp" -ForegroundColor Red; exit 1
}
Ok "上传返回 HTTP 耗时 ${ms}ms（**不等索引**），图片 id = $picId"
Info '索引要调多模态模型，单张通常 3-20 秒；这一步就返回说明没有占着请求线程'

Step 3 '观察队列（MQ 的意义：索引任务在队列里，不在本机线程池）'
$sawMessage = $false
for ($i = 0; $i -lt 20; $i++) {
    $s = QueueStat
    if ($null -ne $s -and ($s.messages -gt 0 -or $s.messages_unacknowledged -gt 0)) {
        Ok "队列里有消息: messages=$($s.messages) unacked=$($s.messages_unacknowledged)"
        $sawMessage = $true
        break
    }
    Start-Sleep -Milliseconds 250
}
if (-not $sawMessage) {
    Info '（消息被瞬间消费掉了，队列一直是 0 —— 模型快的时候正常）'
}

Step 4 '等消费者处理完，确认队列排空'
$deadline = (Get-Date).AddSeconds($IndexWaitSeconds)
$s = QueueStat
while ((Get-Date) -lt $deadline) {
    $s = QueueStat
    if ($s.messages -eq 0 -and $s.messages_unacknowledged -eq 0) { break }
    Start-Sleep -Seconds 2
}
if ($s.messages -eq 0 -and $s.messages_unacknowledged -eq 0) {
    Ok '队列已排空（消息被 ack，没有卡住）'
} else {
    Write-Host "    队列仍有积压: messages=$($s.messages) unacked=$($s.messages_unacknowledged)" -ForegroundColor Yellow
}

Step 5 '看日志里这条链路（按 messageId 串起来）'
if (Test-Path $LogFile) {
    # 轮询等业务侧把「索引成功」写完：队列排空 ≠ 日志已落盘（这两个之间没有先后保证）
    $bizOk = $false
    $logDeadline = (Get-Date).AddSeconds($IndexWaitSeconds)
    while ((Get-Date) -lt $logDeadline) {
        $fs = [System.IO.File]::Open($LogFile, 'Open', 'Read', 'ReadWrite')
        $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $probe = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
        if (($probe -split "`r?`n") | Select-String -SimpleMatch "图片索引成功，id = $picId") { $bizOk = $true; break }
        Start-Sleep -Seconds 2
    }
    $fs = [System.IO.File]::Open($LogFile, 'Open', 'Read', 'ReadWrite')
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $all = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
    $allLines = $all -split "`r?`n"
    # 先找出这条索引消息的 messageId：**必须以它为锚点**只展示这一条链路，
    # 否则会把别的消息（比如前提检查时那条自检 ping）一起捞出来，看着像串了线
    # 文案在 5c 改过：MqPublisher 现在打「消息已交给 broker」（原先叫「消息已投递」，
    # 与消费侧的「消息处理成功」容易混）
    $publishLine = $allLines | Where-Object { $_ -match '消息已交给 broker' -and $_ -match 'index.requested' } | Select-Object -Last 1
    $mid = if ($publishLine) { [regex]::Match($publishLine, 'messageId = ([0-9a-f\-]+)').Groups[1].Value } else { '' }
    if ($mid) {
        Info "这条索引消息的 messageId = $mid"
        $allLines | Where-Object { $_ -match [regex]::Escape($mid) } | ForEach-Object { Info $_.Trim() }
    }
    # 业务侧日志不带 messageId，单独按 pictureId 呈现
    $bizLines = $allLines | Where-Object { $_ -match $picId }
    if ($bizLines) {
        Info '--- 业务日志（按图片 id 关联）---'
        $bizLines | ForEach-Object { Info $_.Trim() }
    }
    if ($bizOk) {
        Ok '索引成功（业务侧确认）'
    } else {
        Write-Host "    没看到索引成功日志，可能还在跑或失败了（看 app.log）" -ForegroundColor Yellow
    }
} else {
    Info "（没找到 $LogFile，跳过日志展示）"
}

Step 6 '清理：删掉这张测试图片'
$df = Join-Path $env:TEMP 'demo-5b-del.json'
[System.IO.File]::WriteAllText($df, ('{"id":' + $picId + '}'), (New-Object System.Text.ASCIIEncoding))
$del = curl.exe -s -X POST "$Base/api/delete" -H "Authorization: Bearer $Token" -H 'Content-Type: application/json' --data-binary "@$df"
if ($del -match '"code":0') { Ok "测试图片已删除（id=$picId）" }
else { Write-Host "    删除失败，请手工清理图片 $picId : $del" -ForegroundColor Yellow }

Write-Host "`n现在去 RabbitMQ 管理台 http://127.0.0.1:15672 看 Queues：" -ForegroundColor Cyan
Write-Host "  picture.mq.index       业务队列（消费者 1 个）"
Write-Host "  picture.mq.index.dlq   死信队列（失败消息会堆在这里，等 5c 做重放）"
Write-Host "  picture.mq.index.retry 延迟重试队列（消息挂 TTL 后自动回到业务队列）"
Write-Host ""
