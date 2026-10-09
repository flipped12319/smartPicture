# 诊断脚本：定位「查看某个空间时报 获取空间详情失败 / 请求数据不存在」
#
# 用法（从仓库根目录跑，**把浏览器地址栏里那个空间 id 传进来**）：
#     powershell -ExecutionPolicy Bypass -File tools\verify\diagnose-space-view.ps1 -SpaceId 1234567890123456789
#
# 也可以带一个 token（浏览器 localStorage 里的 token 值），登录态的链路会一起测：
#     ... -SpaceId <id> -Token <jwt>
#
# 它会依次打印：
#   1. 这个 id 在 space-service 里到底有没有（绕过网关与单体）
#   2. 走网关拿空间详情（前端第 1 个请求，报「获取空间详情失败」的就是它）
#   3. 走网关拿图片列表（前端第 2 个请求）
#   4. 该用户「与我有关的空间」列表 —— 用来对照「应该用哪个 id」
#
# 怎么读结果：
#   第 1 步 data=null             -> 这个 id 在库里真的不存在（或已被软删除）。
#                                   用第 4 步列出来的 id 重新进页面即可。
#   第 1 步有 data、第 2 步 40400 -> 说明前端传的 id 与第 1 步测的不是同一个
#   第 2 步 50000                 -> 空间服务连不上（双击 run-space-service.cmd）
#   第 3 步 40101                 -> 是权限问题，不是「空间不存在」

param(
    [Parameter(Mandatory = $true)][string]$SpaceId,
    [string]$Token = '',
    [string]$Gateway = 'http://127.0.0.1:9000',
    [string]$SpaceService = 'http://127.0.0.1:8130',
    [string]$InternalToken = 'dev-internal-token'
)

$ErrorActionPreference = 'Continue'

$Jd = Join-Path $env:TEMP 'diagnose-space'
Remove-Item -Recurse -Force $Jd -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Jd | Out-Null
function W($n, $j) {
    $f = Join-Path $Jd "$n.json"
    [System.IO.File]::WriteAllText($f, $j, (New-Object System.Text.ASCIIEncoding))
    return $f
}
function J($url, $method = 'GET', $body = $null) {
    $a = @('-s', '-X', $method, $url)
    if ($Token) { $a += @('-H', "Authorization: Bearer $Token") }
    if ($body) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$(W 'b' $body)") }
    return (curl.exe @a)
}

Write-Host "目标 spaceId = $SpaceId" -ForegroundColor Cyan
if (-not $Token) { Write-Host "(未提供 -Token：需要登录态的步骤会返回 40100，属正常)" -ForegroundColor DarkYellow }

Write-Host "`n[1] space-service 直连（绕过网关/单体，看这个 id 到底在不在）:"
Write-Host ("    " + (curl.exe -s -H "X-Internal-Token: $InternalToken" "$SpaceService/api/internal/space/$SpaceId"))

Write-Host "`n[2] 网关 /api/space/get/vo（前端第 1 个请求 = 报「获取空间详情失败」的那个）:"
Write-Host ("    " + (J "$Gateway/api/space/get/vo?id=$SpaceId"))

Write-Host "`n[3] 网关 /api/list/page/vo（前端第 2 个请求）:"
Write-Host ("    " + (J "$Gateway/api/list/page/vo" 'POST' ('{"spaceId":' + $SpaceId + ',"nullSpaceId":false,"current":1,"pageSize":20}')))

Write-Host "`n[4] 该用户「与我有关的空间」（对照：应该用哪个 id 进页面）:"
$mine = J "$Gateway/api/spaceUser/my/space/list/page" 'POST' '{"current":1,"pageSize":20}'
Write-Host ("    " + $mine)
# 成对提取 "id"+"spaceName"：只匹配记录开头的 {"id":N,"spaceName":"..."，
# 这样不会把记录里的 userId / user.id 当成空间 id（那种正则在 JSON 里很难写对）
$pairs = [regex]::Matches($mine, '\{"id":(\d{15,}),"spaceName":"([^"]*)"')
if ($pairs.Count -gt 0) {
    Write-Host "`n    该用户可用的空间：" -ForegroundColor Green
    $found = $false
    foreach ($p in $pairs) {
        $sid = $p.Groups[1].Value
        $sname = $p.Groups[2].Value
        Write-Host "      $sid    $sname"
        if ($sid -eq $SpaceId) { $found = $true }
    }
    if ($found) {
        Write-Host "`n    => 你给的这个 id **在**列表里；若 [1] 仍查不到，请把 [1][2] 的原文发出来。" -ForegroundColor Yellow
    } else {
        Write-Host "`n    => 你给的这个 id **不在**列表里（多半是已删除空间的旧链接）。" -ForegroundColor Yellow
        Write-Host "       用上面列出的 id 重新进页面即可。" -ForegroundColor Yellow
    }
} else {
    Write-Host "    （列表为空或未提供有效 -Token）" -ForegroundColor DarkYellow
}

Write-Host "`n如果 [1] 返回 data=null，说明这个空间已经不存在（可能被删过 / 是旧链接）。" -ForegroundColor Green
Write-Host "如果 [2] 报 50000，说明空间服务没起：双击 run-space-service.cmd。" -ForegroundColor Green
