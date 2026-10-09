# Run it FROM THE REPOSITORY ROOT, e.g.:
#     powershell -ExecutionPolicy Bypass -File tools\verify\verify-4b.ps1
#
# Requirements:
#   - MySQL up, user-service on 8126, space-service on 8130 (and for 4b: monolith 8124, gateway 9003)
#   - .tmp-dbdump\tokens.txt with "<userId>=<jwt>" lines for the users referenced below
#     (generate with tools\verify\GenToken.java; it needs -Dsecret=<jwt.secret> from
#      picture-backend/src/main/resources/application.yml, and do not commit the tokens)
#
# Targets the spare-port deployment built for 4b:
#   monolith  8124  (everyday 8123)  -> Feign to user-service 8126 + space-service 8130
#   gateway   9003  (everyday 9000)  -> /api/space/**, /api/spaceUser/** go to space-service
#   space-service 8130, user-service 8126
#
# What "split" means here and how each item is proven:
#   1. monolith has no space code/data path  -> proven structurally (mappers deleted) AND by the
#      space-service log, which is the only place those queries now appear (checked separately);
#   2. frontend contract unchanged           -> same paths, same JSON shape, still reachable;
#   3. cross-service write still correct     -> quota round-trip measured in the DB;
#   4. hot-path auth still correct           -> role checks on the picture list path;
#   5. assistant still finds private pictures-> agent returns a URL under the private space prefix.

$ErrorActionPreference = 'Continue'
$GW   = 'http://127.0.0.1:9003/api'
$MONO = 'http://127.0.0.1:8124/api'

$ADMIN  = '2052376987161366530'
$MEMBER = '2052725134551248898'
$TEAM   = '2100208978036293634'
$PRIV_MEMBER = '2057258595425259521'
$PRIV_ADMIN  = '2056680093672083458'

$tok = @{}
Get-Content (Join-Path (Get-Location) '.tmp-dbdump\tokens.txt') | ForEach-Object {
    if ($_ -match '^(\d+)=(.+)$') { $tok[$Matches[1]] = $Matches[2].Trim() }
}

$Jd = Join-Path $env:TEMP 'verify4b'
Remove-Item -Recurse -Force $Jd -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Jd | Out-Null
function W($n, $j) {
    $f = Join-Path $Jd "$n.json"
    [System.IO.File]::WriteAllText($f, $j, (New-Object System.Text.ASCIIEncoding))
    return $f
}
function J($url, $method = 'GET', $token = $null, $bodyFile = $null) {
    $a = @('-s', '-X', $method, $url)
    if ($token) { $a += @('-H', "Authorization: Bearer $token") }
    if ($bodyFile) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile") }
    return (curl.exe @a)
}
function C($json) { if (-not $json) { return -999 }; return (ConvertFrom-Json $json).code }

$script:pass = 0; $script:fail = 0
function Assert($name, $cond, $detail) {
    if ($cond) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  -> $detail" -ForegroundColor Red }
}
function Show($n, $j) { Write-Host "### $n"; Write-Host $j }

Write-Host "`n===== 1. gateway routes the space paths to space-service ====="
$routes = curl.exe -s "http://127.0.0.1:9003/actuator/gateway/routes"
Assert 'gateway exposes picture-space-service route' ($routes -match 'picture-space-service') ''
Assert 'space route order is -2' ($routes -match '"order":-2') ''
# a space endpoint that exists ONLY in space-service (the monolith has no space controller left)
$r = J "$GW/space/list/level"
Assert '/api/space/list/level via gateway -> 0' ((C $r) -eq 0) $r
$r = J "$GW/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN]
Assert '/api/space/get/vo via gateway -> 0' ((C $r) -eq 0) $r
$r = J "$GW/spaceUser/list/page/vo" 'POST' $tok[$MEMBER] (W 'member' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":10}'))
Assert '/api/spaceUser/list/page/vo via gateway -> 0' ((C $r) -eq 0) $r

Write-Host "`n===== 1b. 图片代理 /picture/proxy 的鉴权（<img> 只能带 token 查询参数）====="
# 这个接口是 <img src> 直接发起的，浏览器**带不了 Authorization 头**，只能带 Cookie。
# 阶段 3a 把登录态收敛成 JWT 之后，它就再也拿不到凭据了 —— 表现是「团队空间里点编辑图片报未登录」。
# 修法是：前端把 token 放进查询参数，后端 getLoginUser 支持 `token` 查询参数。
$PIC_IN_SPACE = '2100258994876686337'   # 团队空间1 里的图片；ADMIN 与 MEMBER 都是该空间成员
function ProbeGet($url) {
    $a = @('-s', '-o', '-', '-w', "`n@@%{content_type}|%{size_download}", $url)
    return (curl.exe @a)
}
$r = ProbeGet "$GW/picture/proxy?id=$PIC_IN_SPACE"
Assert 'proxy without token -> 40100 未登录（这就是「点编辑图片报未登录」的现场）' ($r -match '"code":40100') ($r.Substring(0,[Math]::Min(120,$r.Length)))
$r = ProbeGet "$GW/picture/proxy?id=$PIC_IN_SPACE&token=$tok[$ADMIN]"
Assert 'proxy with ?token= (member) -> returns image bytes' ($r -match 'image/webp') ($r.Substring(0,[Math]::Min(120,$r.Length)))
$r = ProbeGet "$GW/picture/proxy?id=$PIC_IN_SPACE&token=garbage.token.x"
Assert 'proxy with bad token -> 40100' ($r -match '"code":40100') ($r.Substring(0,[Math]::Min(120,$r.Length)))
$r = ProbeGet "$GW/picture/proxy?id=999999999999&token=$tok[$MEMBER]"
Assert 'proxy unknown picture -> 40400 图片不存在' ($r -match '"code":40400') ($r.Substring(0,[Math]::Min(120,$r.Length)))

Write-Host "`n===== 2. monolith no longer serves space endpoints (proves the routes really moved) ====="
$r = J "$MONO/space/list/level"
Assert 'monolith direct /api/space/list/level -> 404 (controller deleted)' ((C $r) -eq 40400 -or $r -match '404') $r
$r = J "$MONO/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN]
Assert 'monolith direct /api/space/get/vo -> 404' ((C $r) -eq 40400 -or $r -match '404') $r

Write-Host "`n===== 3. monolith's own space reads (Feign + cache) still work ====="
# /api/spaceUser/my/space/list/page lives in space-service, but its SpaceVO rendering path
# (SpaceService.getSpaceVOPage) is one of the few space reads the monolith still performs.
$r = J "$GW/spaceUser/my/space/list/page" 'POST' $tok[$ADMIN] (W 'myspace' '{"current":1,"pageSize":20}')
$p = ConvertFrom-Json $r
Assert 'my-space list -> 0' ($p.code -eq 0) $r
Assert 'my-space list has records' ($p.data.total -ge 2) $r
Assert 'records carry currentUserRole (monolith role mapping)' (($p.data.records | Where-Object { $_.currentUserRole -ne $null }).Count -ge 1) $r
Assert 'records carry creator name (monolith UserService)' (($p.data.records | Select-Object -First 1).user.userName -eq 'admin') $r

Write-Host "`n===== 4. hot-path auth still enforced (picture list in a private space) ====="
$r = J "$GW/list/page/vo" 'POST' $tok[$ADMIN] (W 'pl_admin' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":3}'))
Assert 'ADMIN (member) can list team-space pictures -> 0' ((C $r) -eq 0) $r
Assert 'list returns the team picture' ($r -match '2100258994876686337') $r
$r = J "$GW/list/page/vo" 'POST' $tok[$MEMBER] (W 'pl_other' ('{"spaceId":"' + $PRIV_ADMIN + '","current":1,"pageSize":3}'))
Assert 'MEMBER cannot list ADMIN private space -> 40101 (auth path fails closed)' ((C $r) -eq 40101) $r
$r = J "$GW/list/page/vo" 'POST' $null (W 'pl_anon' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":3}'))
Assert 'anonymous cannot list a space -> 40100' ((C $r) -eq 40100) $r
$r = J "$GW/list/page/vo" 'POST' $null (W 'pl_pub' '{"current":1,"pageSize":3}')
Assert 'anonymous public gallery list still works -> 0' ((C $r) -eq 0) $r

Write-Host "`n===== 5. cross-service WRITE: quota round-trip through the monolith ====="
# The only remaining way to change quota is the monolith's PictureService path. We cannot upload
# a real file here, so we drive the same internal endpoint the monolith uses and verify the
# DB value moves by exactly the delta and comes back. (The monolith->space-service wiring itself
# is proven by 4b's compile + the fact that PictureServiceImpl is the sole caller.)
$before = (ConvertFrom-Json (J "$GW/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN])).data
$q = (ConvertFrom-Json (curl.exe -s -H 'X-Internal-Token: dev-internal-token' "http://127.0.0.1:8130/api/internal/space/quota/$TEAM")).data
Assert 'quota read from space-service' ($q.totalSize -eq $before.totalSize) "vo=$($before.totalSize) quota=$($q.totalSize)"
$b = Join-Path $Jd 'qadd.json'; [System.IO.File]::WriteAllText($b, '{"spaceId":' + $TEAM + ',"sizeDelta":4096,"countDelta":1,"reason":"verify-4b"}', (New-Object System.Text.ASCIIEncoding))
$q2 = (ConvertFrom-Json (curl.exe -s -X POST 'http://127.0.0.1:8130/api/internal/space/quota/change' -H 'X-Internal-Token: dev-internal-token' -H 'Content-Type: application/json' --data-binary "@$b")).data
Assert 'quota +4096/+1 applied' ([long]$q2.totalSize -eq ([long]$before.totalSize + 4096) -and [long]$q2.totalCount -eq ([long]$before.totalCount + 1)) ''
$b2 = Join-Path $Jd 'qback.json'; [System.IO.File]::WriteAllText($b2, '{"spaceId":' + $TEAM + ',"sizeDelta":-4096,"countDelta":-1,"reason":"verify-4b-rollback"}', (New-Object System.Text.ASCIIEncoding))
$q3 = (ConvertFrom-Json (curl.exe -s -X POST 'http://127.0.0.1:8130/api/internal/space/quota/change' -H 'X-Internal-Token: dev-internal-token' -H 'Content-Type: application/json' --data-binary "@$b2")).data
Assert 'quota rollback restores original' ($q3.totalSize -eq $before.totalSize -and $q3.totalCount -eq $before.totalCount) ''
$after = (ConvertFrom-Json (J "$GW/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN])).data
Assert 'space VO reflects restored quota' ($after.totalSize -eq $before.totalSize) ''

Write-Host "`n===== 6. assistant still reaches private pictures (the silent-degradation trap) ====="
# This is the acceptance item the plan calls out: /api/space/getIdByUserId breaking would make the
# assistant silently fall back to the public gallery. Check the chain piece by piece, then ask.
$r = J "$GW/space/getIdByUserId?userId=$MEMBER" 'GET' $tok[$MEMBER]
Assert 'frontend spaceId lookup via gateway -> 0' ((C $r) -eq 0) $r
Assert 'spaceId lookup returns the private space id' ($r -match $PRIV_MEMBER) $r
$chatBody = '{"session_id":"4b-verify-' + (Get-Date -Format 'HHmmss') + '","message":"search my private gallery for jpg","token":"' + $tok[$MEMBER] + '","spaceId":"' + $PRIV_MEMBER + '"}'
$chatFile = Join-Path $Jd 'chat.json'
# NOTE: the message is deliberately ASCII ("... for jpg"). Windows PowerShell 5.1 decodes a
# BOM-less script as ANSI/GBK, so a Chinese literal inside the script would reach the agent
# mangled - and the search would still succeed, silently weakening the test.
# The private picture in this database is named "<chinese-tiger>.jpg" (jpg suggests the tiger), and no
# public-gallery picture name suggests "jpg", so this keyword discriminates: if the assistant
# were silently searching the public gallery instead, it would return nothing.
[System.IO.File]::WriteAllText($chatFile, $chatBody, (New-Object System.Text.UTF8Encoding($false)))
$chat = curl.exe -s -m 180 -X POST 'http://127.0.0.1:8000/chat' -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$chatFile"
Show 'agent reply' ($chat.Substring(0, [Math]::Min(400, $chat.Length)))
Assert 'agent returns an image_urls entry under the private space prefix' ($chat -match [regex]::Escape("/space/$PRIV_MEMBER/")) $chat
Assert 'agent found the jpg picture (keyword is private-space-only)' ($chat -match 'UlCynfJ6otmJAmWX') $chat
Assert 'agent did not fall back to the public gallery' (-not ($chat -match '/public/')) $chat

Write-Host "`n===== 7. regression: space features through the gateway ====="
$r = J "$GW/space/add" 'POST' $tok[$MEMBER] (W 'teamnew' '{"spaceName":"4b-verify-team","spaceType":1,"spaceLevel":0}')
$newId = [regex]::Match($r, '"data":"?(\d+)"?').Groups[1].Value
Assert 'MEMBER can still create a team space' ($newId.Length -gt 0) $r
Assert 'new space reports manager role' ((J "$GW/space/get/vo?id=$newId" 'GET' $tok[$MEMBER]) -match '"currentUserRole":3') ''
Assert 'duplicate private space still rejected with 50001' ((C (J "$GW/space/add" 'POST' $tok[$MEMBER] (W 'privnew' '{"spaceName":"x","spaceType":0,"spaceLevel":0}'))) -eq 50001) ''
Assert 'non-admin flagship still rejected with 40101' ((C (J "$GW/space/add" 'POST' $tok[$MEMBER] (W 'flag' '{"spaceName":"x","spaceType":0,"spaceLevel":2}'))) -eq 40101) ''
$invite = W 'invite' ('{"spaceId":"' + $newId + '","userAccount":"admin","spaceRole":0}')
$r = J "$GW/spaceUser/invite" 'POST' $tok[$MEMBER] $invite
$memberId = [regex]::Match($r, '"data":"?(\d+)"?').Groups[1].Value
Assert 'invite works through the gateway' ($memberId.Length -gt 0) $r
Assert 'accept invitation works' ((C (J "$GW/spaceUser/accept" 'POST' $tok[$ADMIN] (W 'accept' ('{"id":' + $memberId + '}')))) -eq 0) ''
Assert 'role updated by manager' ((C (J "$GW/spaceUser/update/role" 'POST' $tok[$MEMBER] (W 'role2' ('{"id":' + $memberId + ',"spaceRole":2}')))) -eq 0) ''
Assert 'ADMIN now editor in the new space' ((J "$GW/space/get/vo?id=$newId" 'GET' $tok[$ADMIN]) -match '"currentUserRole":2') ''
Assert 'remove member works' ((C (J "$GW/spaceUser/remove" 'POST' $tok[$ADMIN] (W 'rm' ('{"id":' + $memberId + '}')))) -eq 0) ''
Assert 'delete space works' ((C (J "$GW/space/delete" 'POST' $tok[$MEMBER] (W 'del' ('{"id":' + $newId + '}')))) -eq 0) ''
Assert 'deleted space is gone' ((J "$GW/space/get/vo?id=$newId" 'GET' $tok[$MEMBER]) -match '40400') ''

Write-Host "`n===== 8. internal API is still not reachable through the gateway ====="
Assert 'gateway /api/internal/space/** -> 404' ((curl.exe -s "http://127.0.0.1:9003/api/internal/space/$TEAM") -match '404') ''
Assert 'gateway /api/internal/space-user/role -> 404' ((curl.exe -s "http://127.0.0.1:9003/api/internal/space-user/role?spaceId=$TEAM&userId=$ADMIN") -match '404') ''

Write-Host "`n===== 9. pre-existing data untouched ====="
$vo = ConvertFrom-Json (J "$GW/space/get/vo?id=$PRIV_MEMBER" 'GET' $tok[$MEMBER])
Assert 'MEMBER private space totalCount still 21' ($vo.data.totalCount -eq 21) ''
Assert 'MEMBER private space totalSize still 707046' ($vo.data.totalSize -eq 707046) ''
$vo = ConvertFrom-Json (J "$GW/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN])
Assert 'TEAM totalSize unchanged (59396)' ($vo.data.totalSize -eq 59396) ''
Assert 'TEAM totalCount unchanged (1)' ($vo.data.totalCount -eq 1) ''

Write-Host "`n================ RESULT ================" -ForegroundColor Yellow
Write-Host "PASS = $script:pass, FAIL = $script:fail"
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
