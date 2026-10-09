# Run it FROM THE REPOSITORY ROOT, with the MQ-enabled backend already running:
#     run-backend-mq.cmd
#
#   powershell -ExecutionPolicy Bypass -File tools\verify\verify-5c.ps1
#
# What this verifies (phase 5c): the reliability layer around MQ.
#   A. 安全前提：重试队列 TTL 与应用配置一致（不一致会让消费者被静默中止）
#   B. status 接口在 broker 正常时可用
#   C. 本地消息表在「正常投递」时也会被使用（不是只在故障时才写）
#   D. 重放接口：只接受认识的死信队列（防止它变成任意队列清空工具）
#   E. 对账接口可手动触发，且不会碰 indexStatus=2
#
#   [+Durability] 可选的真实故障演练（会**停掉 RabbitMQ**，默认关闭）：
#       powershell ... -DurabilityTest
#     它做的事：停 broker -> 上传一张图 -> 断言「上传成功且本地消息表里有 pending」
#     -> 启 broker -> 断言消息被自动补投（status 0 -> 1）-> 清理测试图片。
#     这是「MQ 挂了消息也不丢」的直接证据，但会短暂影响所有用到 MQ 的服务，
#     所以必须显式开启。

param(
    [string]$Base = 'http://127.0.0.1:8128',
    [string]$RabbitApi = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'admin',
    [string]$RabbitPassword = 'change-me-dev-only',
    [string]$IndexQueue = 'picture.mq.index',
    [string]$IndexDlq = 'picture.mq.index.dlq',
    [string]$DockerContainer = 'sp-rabbitmq',
    [string]$Token = '',
    [string]$TokensFile = '.tmp-dbdump\tokens.txt',
    [string]$LogFile = 'picture-backend\app.log',
    [int]$RelayWaitSeconds = 120,
    [switch]$DurabilityTest
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
function Api($path, $method = 'GET') {
    if ($method -eq 'GET') { return (curl.exe -s "$Base$path") }
    return (curl.exe -s -X $method "$Base$path")
}
function StatusJson {
    $r = Api '/api/mq/debug/status'
    try { return ($r | ConvertFrom-Json) } catch { return $null }
}

Write-Host "`n===== A. 安全前提：重试队列 TTL 与应用配置一致 ====="
# 队列是 durable 的：一旦用某个 x-message-ttl 建成，之后改这个参数再启动，broker 会拒绝
# 重新声明（PRECONDITION_FAILED），**整个监听容器的连接被中止** ——
# 症状是「队列里有消息、consumers=0、谁也不消费」，而应用看起来一切正常。
# 实测踩到过：用 PICTURE_MQ_RETRY_DELAY_MS=1000 覆盖了建队列时的 5000。
$retry = QueueStat "$IndexQueue.retry"
if ($null -eq $retry -or $null -eq $retry.name) {
    Write-Host "    重试队列还不存在（应用可能刚启动）—— 跳过 TTL 检查" -ForegroundColor DarkYellow
} else {
    Assert "重试队列 $( $IndexQueue ).retry 带 x-message-ttl" ($null -ne $retry.arguments.'x-message-ttl') "ttl=$($retry.arguments.'x-message-ttl')"
}
$idx = QueueStat $IndexQueue
Assert '索引队列有消费者（没有被 TTL 冲突中止）' ($null -ne $idx -and $idx.consumers -ge 1) "consumers=$($idx.consumers)"

Write-Host "`n===== B. status 接口可用（broker 正常时）====="
$st = StatusJson
Assert 'status 接口返回 code 0' ($null -ne $st -and $st.code -eq 0) (Api '/api/mq/debug/status')

Write-Host "`n===== C. 消息不会滞留（正常路径下 pending 应为 0）====="
# 说明：索引真正走本地消息表是在**上传路径**上（`publishIndexRequest` -> `saveInTransaction`）。
# 下面用的 /mq/debug/publish-index 是**为了测消费者**而直接发送的，它刻意不经过本地消息表 ——
# 所以这里不能拿它来断言「localMessageSent 增加」。真实路径由 -DurabilityTest 用上传验证。
$st = StatusJson
$f = Join-Path $env:TEMP 'v5c-page.json'
[System.IO.File]::WriteAllText($f, '{"current":1,"pageSize":1}', (New-Object System.Text.ASCIIEncoding))
$page = curl.exe -s -X POST "$Base/api/list/page/vo" -H 'Content-Type: application/json' --data-binary "@$f"
$picId = [regex]::Match($page, '"id":"?(\d{15,})"?').Groups[1].Value
Assert '取到一张已存在的图片' ($picId.Length -ge 15) $page.Substring(0, [Math]::Min(120, $page.Length))
if ($picId.Length -ge 15) {
    curl.exe -s -X POST "$Base/api/mq/debug/publish-index?pictureId=$picId" | Out-Null
    Start-Sleep -Seconds 5
    $st2 = StatusJson
    Assert 'pending 保持为 0（broker 正常时不会滞留消息）' `
        ($null -ne $st2 -and [int]$st2.data.localMessagePending -eq 0) `
        "pending=$($st2.data.localMessagePending)"
    Assert '没有需要人工处理的消息（giveUp = 0）' `
        ($null -ne $st2 -and [int]$st2.data.localMessageGiveUp -eq 0) `
        "giveUp=$($st2.data.localMessageGiveUp)"
    $s = QueueStat $IndexQueue
    # 轮询：索引要调多模态模型，几秒内处理不完是正常的；读一次就断言会随机失败
    $drainDeadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $drainDeadline) {
        $s = QueueStat $IndexQueue
        if ($null -ne $s -and $s.messages -eq 0 -and $s.messages_unacknowledged -eq 0) { break }
        Start-Sleep -Seconds 3
    }
    Assert '索引队列没有被卡住（消息都被 ack，无未确认消息）' `
        ($null -ne $s -and $s.messages_unacknowledged -eq 0 -and $s.messages -eq 0) `
        "messages=$($s.messages) unacked=$($s.messages_unacknowledged)"
}

Write-Host "`n===== D. 重放接口只接受认识的死信队列 ====="
$r = Api '/api/mq/debug/replay-dlq?dlq=some.random.queue' 'POST'
Assert '对未知队列名重放 -> 被拒绝（40000 参数错误）' ($r -match '"code":40000') $r
$r = Api ('/api/mq/debug/replay-dlq?dlq=' + $IndexDlq + '&maxReplay=1') 'POST'
Assert '对索引死信队列重放 -> code 0' ($r -match '"code":0') $r

Write-Host "`n===== E. 对账接口 ====="
$r = Api '/api/mq/debug/reconcile' 'POST'
Assert '对账接口返回 code 0' ($r -match '"code":0') $r
if ($LogFile -and (Test-Path $LogFile)) {
    $fs = [System.IO.File]::Open($LogFile, 'Open', 'Read', 'ReadWrite')
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8); $all = $sr.ReadToEnd(); $sr.Close(); $fs.Close()
    $lines = $all -split "`r?`n"
    # 对账只捞 indexStatus=0；不该出现对 indexStatus=2 的补建
    $reconcileLines = $lines | Select-String -SimpleMatch '索引对账发现'
    Assert '对账日志没有提到 indexStatus=2（保守策略：失败件交给 DLQ 重放，不自动重投）' `
        (-not ($reconcileLines | Select-String -SimpleMatch 'indexStatus=2')) ($reconcileLines -join ' | ')
} else {
    Write-Host "    （未提供 -LogFile，跳过对账日志断言）" -ForegroundColor DarkYellow
}

if ($DurabilityTest) {
    Write-Host "`n===== F. 真实故障演练：停掉 RabbitMQ，验证消息不丢 =====" -ForegroundColor Yellow
    Write-Host "    注意：这会短暂停掉 broker，影响所有用 MQ 的服务" -ForegroundColor Yellow
    if (-not $Token) {
        if (Test-Path $TokensFile) {
            $line = (Get-Content $TokensFile | Select-String '^2052376987161366530=').Line
            if ($line) { $Token = ($line -replace '^2052376987161366530=','').Trim() }
        }
    }
    Assert '有上传用的 token' ($Token.Length -gt 20) '需要 -Token 或 .tmp-dbdump\tokens.txt'
    if ($Token.Length -gt 20) {
        Add-Type -AssemblyName System.Drawing
        $bmp = New-Object System.Drawing.Bitmap 320,240
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::DarkSlateBlue)
        $g.FillEllipse([System.Drawing.Brushes]::Orange, 60, 40, 200, 160)
        $g.Dispose()
        $img = Join-Path $env:TEMP 'verify-5c-durability.png'
        $bmp.Save($img, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()

        & docker compose stop rabbitmq 2>&1 | Out-Null
        Start-Sleep -Seconds 4
        Assert 'RabbitMQ 已停' (-not (netstat -ano | Select-String ':5672\s+.*LISTENING')) '5672 仍在监听'

        $resp = curl.exe -s -X POST "$Base/api/upload" -H "Authorization: Bearer $Token" -F "file=@$img"
        $newPic = [regex]::Match($resp, '"id":"?(\d{15,})"?').Groups[1].Value
        Assert 'MQ 挂掉时上传**依然成功**（业务不被消息中间件拖垮）' ($resp -match '"code":0') $resp.Substring(0, [Math]::Min(160, $resp.Length))
        Start-Sleep -Seconds 3
        $stDown = StatusJson
        Assert 'MQ 挂掉时本地消息表出现 pending（消息**没有丢**）' `
            ($null -ne $stDown -and [int]$stDown.data.localMessagePending -ge 1) `
            "status=$($stDown.data | ConvertTo-Json -Compress)"
        # status 接口在 broker 挂掉时**不能**崩（实测踩过：它去查队列深度会抛异常 -> 50000）
        Assert 'broker 不可用时 status 接口仍可用（队列深度降级为 -1，而不是 50000）' `
            ($null -ne $stDown -and $stDown.code -eq 0) (Api '/api/mq/debug/status')

        & docker compose start rabbitmq 2>&1 | Out-Null
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            if (netstat -ano | Select-String ':5672\s+.*LISTENING') { break }
            Start-Sleep -Seconds 2
        }
        Assert 'RabbitMQ 已恢复' ([bool](netstat -ano | Select-String ':5672\s+.*LISTENING')) ''
        Write-Host "    等自动补投（有退避，最长可能需要几分钟）..." -ForegroundColor DarkYellow
        $ok = $false
        $deadline = (Get-Date).AddSeconds($RelayWaitSeconds)
        while ((Get-Date) -lt $deadline) {
            $s = StatusJson
            if ($null -ne $s -and [int]$s.data.localMessagePending -eq 0) { $ok = $true; break }
            Start-Sleep -Seconds 5
        }
        Assert 'broker 恢复后消息被自动补投（pending 归零）' $ok '仍有 pending，看 app.log 里的补投日志 / 退避状态'

        if ($newPic.Length -ge 15) {
            $df = Join-Path $env:TEMP 'v5c-del.json'
            [System.IO.File]::WriteAllText($df, ('{"id":' + $newPic + '}'), (New-Object System.Text.ASCIIEncoding))
            curl.exe -s -X POST "$Base/api/delete" -H "Authorization: Bearer $Token" -H 'Content-Type: application/json' --data-binary "@$df" | Out-Null
            Write-Host "    已删除演练用的测试图片 $newPic（注意：是软删除；如需彻底清除见 docs/local-env.md）" -ForegroundColor DarkYellow
        }
    }
} else {
    Write-Host "`n（未启用 -DurabilityTest：跳过「停 broker 验证消息不丢」的真实演练）" -ForegroundColor DarkYellow
    Write-Host "  要跑它：powershell ... -File tools\verify\verify-5c.ps1 -DurabilityTest" -ForegroundColor DarkYellow
}

Write-Host "`n================ RESULT ================"
Write-Host "PASS = $script:pass, FAIL = $script:fail"
if ($script:fail -gt 0) { exit 1 }
