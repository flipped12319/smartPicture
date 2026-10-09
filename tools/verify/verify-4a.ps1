# Run it FROM THE REPOSITORY ROOT, e.g.:
#     powershell -ExecutionPolicy Bypass -File tools\verify\verify-4a.ps1
#
# Requirements:
#   - MySQL up, user-service on 8126, space-service on 8130 (and for 4b: monolith 8124, gateway 9003)
#   - .tmp-dbdump\tokens.txt with "<userId>=<jwt>" lines for the three users referenced below
#     (generate with tools\verify\GenToken.java; it needs -Dsecret=<jwt.secret> from
#      picture-backend/src/main/resources/application.yml, and do not commit the tokens)
## Phase 4a verification for picture-space-service (port 8130).
#
# Two Windows PowerShell 5.1 traps this harness works around (both cost me real debugging time
# and produced failure walls that looked like service bugs - the service was right every time):
#   1. Native-command argument quoting: json bodies must be handed to curl.exe as "@file".
#   2. ConvertFrom-Json turns 19-digit snowflake ids into doubles, silently corrupting them
#      (2107704813947998209 -> 2107704813947998208). So every id this script reuses is taken
#      from the raw response text with a regex, never from ConvertFrom-Json.
#
# Live identity mapping (verified):
#   admin  2052376987161366530  isDelete=0  owns adminspace + team space TEAM
#   user   2052378415758086146  isDelete=1  <-- LOGICALLY DELETED: its token MUST be rejected
#   user2  2052725134551248898  isDelete=0  owns userspace (PRIV_MEMBER)
#
# The script creates only data it deletes again and restores every quota change it makes.

$ErrorActionPreference = 'Continue'
$Base = 'http://127.0.0.1:8130/api'

$ADMIN       = '2052376987161366530'
$DELETED     = '2052378415758086146'
$MEMBER      = '2052725134551248898'
$TEAM        = '2100208978036293634'
$PRIV_MEMBER = '2057258595425259521'

$TokFile = Join-Path (Get-Location) '.tmp-dbdump\tokens.txt'
$tok = @{}
Get-Content $TokFile | ForEach-Object { if ($_ -match '^(\d+)=(.+)$') { $tok[$Matches[1]] = $Matches[2].Trim() } }
if ($tok.Count -ne 3) { Write-Host "FATAL: token file not loaded ($TokFile)" -ForegroundColor Red; exit 2 }

$Jd = Join-Path $env:TEMP 'verify4a'
Remove-Item -Recurse -Force $Jd -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Jd | Out-Null
function W($name, $json) {
    $f = Join-Path $Jd "$name.json"
    [System.IO.File]::WriteAllText($f, $json, (New-Object System.Text.ASCIIEncoding))
    return $f
}
function J($url, $method = 'GET', $token = $null, $bodyFile = $null) {
    $a = @('-s', '-X', $method, $url)
    if ($token) { $a += @('-H', "Authorization: Bearer $token") }
    if ($bodyFile) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile") }
    return (curl.exe @a)
}
function Ji($url, $method = 'GET', $bodyFile = $null) {
    $a = @('-s', '-X', $method, $url, '-H', 'X-Internal-Token: dev-internal-token')
    if ($bodyFile) { $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile") }
    return (curl.exe @a)
}
function C($json) { return (ConvertFrom-Json $json).code }
# take an id out of raw response text (PS 5.1 mangles big numbers via ConvertFrom-Json)
# 取原始响应里的 data。**必须同时接受字符串与数字两种写法**：正确实现是字符串
# （"data":"2107713929437249537"），但曾经漏过 Jackson 配置、以数字下发 ——
# 那种情况下正则照样能取到值，可前端拿到的是被四舍五入过的 id，于是点进去报「空间不存在」。
# 所以第 3 节专门断言了 id 必须是带引号的字符串。
function IdOf($json) { return [regex]::Match($json, '"data":"?(\d+)"?').Groups[1].Value }
function RoleOf($spaceId, $token) {
    return (ConvertFrom-Json (J "$Base/space/get/vo?id=$spaceId" 'GET' $token)).data.currentUserRole
}
function AssertEq($name, $actual, $expected) {
    if ("$actual" -eq "$expected") { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  (expected '$expected', got '$actual')" -ForegroundColor Red }
}

$script:pass = 0; $script:fail = 0
function Show($name, $json) { Write-Host "### $name"; Write-Host $json }
function Assert($name, $cond, $detail) {
    if ($cond) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  -> $detail" -ForegroundColor Red }
}

Write-Host "`n===== 1. auth: the service resolves JWT itself (no gateway headers) ====="
Assert 'get/vo without token readable (render path degrades)' ((C (J "$Base/space/get/vo?id=$TEAM")) -eq 0) ''
Assert 'no token on authenticated endpoint -> 40100' ((C (J "$Base/space/getIdByUserId?userId=$ADMIN")) -eq 40100) ''
Assert 'garbage token -> 40100' ((C (J "$Base/space/getIdByUserId?userId=$ADMIN" 'GET' 'garbage.token')) -eq 40100) ''
Assert 'LOGICALLY DELETED user token rejected -> 40100' ((C (J "$Base/space/getIdByUserId?userId=$DELETED" 'GET' $tok[$DELETED])) -eq 40100) ''
Assert 'admin querying own space id -> 0' ((C (J "$Base/space/getIdByUserId?userId=$ADMIN" 'GET' $tok[$ADMIN])) -eq 0) ''
Assert 'MEMBER querying ADMIN space id -> 40101' ((C (J "$Base/space/getIdByUserId?userId=$ADMIN" 'GET' $tok[$MEMBER])) -eq 40101) ''

Write-Host "`n===== 2. role resolution + SpaceVO.user enrichment (calls user-service) ====="
$r = J "$Base/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN]
Show 'get/vo as ADMIN' $r
$vo = ConvertFrom-Json $r
Assert 'ADMIN role in own team space = 3 (manager)' ($vo.data.currentUserRole -eq 3) $r
Assert 'SpaceVO.user fetched from user-service' ($vo.data.user.userName -eq 'admin') $r
$vo = ConvertFrom-Json (J "$Base/space/get/vo?id=$TEAM" 'GET' $tok[$MEMBER])
Assert 'MEMBER role in team space = 2 (editor)' ($vo.data.currentUserRole -eq 2) ''
Assert 'no-token get/vo has no role' ($null -eq (ConvertFrom-Json (J "$Base/space/get/vo?id=$TEAM")).data.currentUserRole) ''
AssertEq 'MEMBER is manager(3) of own private space' (RoleOf $PRIV_MEMBER $tok[$MEMBER]) 3
Assert 'ADMIN has no role in MEMBER private space' ($null -eq (RoleOf $PRIV_MEMBER $tok[$ADMIN])) ''

Write-Host "`n===== 3. Long 必须以字符串下发（否则雪花 id 到前端就被四舍五入） ====="
# 这不是「风格问题」：本服务与 user-service 都漏过这份 Jackson 配置，真实事故是
# 前端拿到 2107713929437249500 去请求 2107713929437249537，报「空间不存在」。
# 所以这里直接断言原始 JSON 里的 id 是带引号的字符串。
$r = J "$Base/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN]
Assert 'space id serialised as a JSON string (not a number)' ($r -match ('"id":"' + $TEAM + '"')) $r
Assert 'creator userId serialised as a JSON string' ($r -match '"userId":"\d{15,}"') $r
$r = J "$Base/spaceUser/list/page/vo" 'POST' $tok[$MEMBER] (W 'longstr' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":10}'))
Assert 'member list ids serialised as JSON strings' ($r -match '"id":"\d{15,}"' -and $r -match '"userId":"\d{15,}"') $r

Write-Host "`n===== 3b. member list (space-service -> user-service) ====="
$r = J "$Base/spaceUser/list/page/vo" 'POST' $tok[$MEMBER] (W 'member' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":10}'))
$p = ConvertFrom-Json $r
Assert 'member list code 0' ($p.code -eq 0) $r
Assert 'member list total = 2' ($p.data.total -eq 2) $r
Assert 'member nicknames fetched via Feign' ((($p.data.records | ForEach-Object { $_.user.userName }) -join ',') -match 'admin') $r
Assert 'inviter nickname filled' ((($p.data.records | Where-Object { $_.inviterId } | ForEach-Object { $_.inviter.userName }) -contains 'admin')) $r
Assert 'DELETED user token on member list -> 40100' ((C (J "$Base/spaceUser/list/page/vo" 'POST' $tok[$DELETED] (W 'member2' ('{"spaceId":"' + $TEAM + '","current":1,"pageSize":10}')))) -eq 40100) ''

Write-Host "`n===== 4. space CRUD ====="
$r = J "$Base/space/add" 'POST' $tok[$MEMBER] (W 'teamnew' '{"spaceName":"4a-verify-team","spaceType":1,"spaceLevel":0}')
Show 'add team space' $r
$newId = IdOf $r
Assert 'MEMBER can create team space' ($newId.Length -gt 0) $r
AssertEq 'creator is manager(3) in new team space' (RoleOf $newId $tok[$MEMBER]) 3
Assert 'second private space -> 50001 (MEMBER already has one)' ((C (J "$Base/space/add" 'POST' $tok[$MEMBER] (W 'privnew' '{"spaceName":"4a-verify-private","spaceType":0,"spaceLevel":0}'))) -eq 50001) ''
Assert 'non-admin flagship -> 40101' ((C (J "$Base/space/add" 'POST' $tok[$MEMBER] (W 'flagship' '{"spaceName":"x","spaceType":0,"spaceLevel":2}'))) -eq 40101) ''
Assert 'non-admin /space/update -> 40101' ((C (J "$Base/space/update" 'POST' $tok[$MEMBER] (W 'updhack' ('{"id":' + $newId + ',"spaceName":"hacked"}')))) -eq 40101) ''
$r = J "$Base/space/add" 'POST' $tok[$MEMBER] (W 'nolvl' '{"spaceName":"4a-verify-nolevel","spaceType":1}')
Assert 'team space without spaceLevel -> 0 (no NPE)' ((ConvertFrom-Json $r).code -eq 0) $r
$nolevel = IdOf $r

# ADMIN's private space: 'adminspace' (2056680093672083458) already exists, so "one private
# space per user" is verified purely by the rejection below.
# NOTE: this harness must NOT delete it. It is pre-existing fixture data it did not create -
# an earlier version asserted "ADMIN deletes own private space" and by doing so destroyed the
# fixture that verify-4b.ps1 relies on (PRIV_ADMIN), which then made 4b fail with a confusing
# 40400. Harnesses may only remove what they themselves created.
$privAdmin = IdOf (Ji "$Base/internal/space/getSpaceIdByUserId?userId=$ADMIN")
Assert 'ADMIN already owns a private space (pre-existing adminspace)' ($privAdmin -eq '2056680093672083458') $privAdmin
Assert 'ADMIN second private space -> 50001' ((C (J "$Base/space/add" 'POST' $tok[$ADMIN] (W 'admpriv2' '{"spaceName":"4a-verify-admin-private-2","spaceType":0,"spaceLevel":0}'))) -eq 50001) ''
# 读取（而不是删除）它，顺便验证「自己私有空间里 GET/vo 的角色是 3」
Assert 'ADMIN is manager(3) of own private space' ((RoleOf $privAdmin $tok[$ADMIN]) -eq 3) ''

Write-Host "`n===== 5. invitation flow ====="
Assert 'invite logically-deleted account -> 40400' ((C (J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] (W 'invdel' ('{"spaceId":"' + $newId + '","userAccount":"user","spaceRole":0}')))) -eq 40400) ''
Assert 'invite unknown account -> 40400' ((C (J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] (W 'invnone' ('{"spaceId":"' + $newId + '","userAccount":"nobody-exists","spaceRole":0}')))) -eq 40400) ''
Assert 'invite self -> 40000' ((C (J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] (W 'invself' ('{"spaceId":"' + $newId + '","userAccount":"user2","spaceRole":0}')))) -eq 40000) ''
Assert 'invite with manager role -> 40000' ((C (J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] (W 'invadm' ('{"spaceId":"' + $newId + '","userAccount":"admin","spaceRole":3}')))) -eq 40000) ''
$B_INVOK = W 'invok' ('{"spaceId":"' + $newId + '","userAccount":"admin","spaceRole":0}')
$r = J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] $B_INVOK
Show 'invite ADMIN as viewer' $r
$memberId = IdOf $r
Assert 'MEMBER (manager) invites ADMIN OK' ($memberId.Length -gt 0) $r
Assert 'duplicate pending invite -> 50001' ((C (J "$Base/spaceUser/invite" 'POST' $tok[$MEMBER] $B_INVOK)) -eq 50001) ''
$invPage = ConvertFrom-Json (J "$Base/spaceUser/my/invitation/list/page" 'POST' $tok[$ADMIN] (W 'myinv' '{"current":1,"pageSize":10}'))
Assert 'ADMIN sees 1 pending invitation' ($invPage.data.total -eq 1) ''
Assert 'invitation carries space name' ($invPage.data.records[0].space.spaceName -eq '4a-verify-team') ''
$B_ROLE2 = W 'role2' ('{"id":' + $memberId + ',"spaceRole":2}')
$B_ROLE3 = W 'role3' ('{"id":' + $memberId + ',"spaceRole":3}')
$B_ACCEPT = W 'accept' ('{"id":' + $memberId + '}')
Assert 'non-manager role update -> 40101' ((C (J "$Base/spaceUser/update/role" 'POST' $tok[$ADMIN] $B_ROLE2)) -eq 40101) ''
Assert 'ADMIN accepts invitation -> 0' ((C (J "$Base/spaceUser/accept" 'POST' $tok[$ADMIN] $B_ACCEPT)) -eq 0) ''
Assert 'accept twice -> 50001' ((C (J "$Base/spaceUser/accept" 'POST' $tok[$ADMIN] $B_ACCEPT)) -eq 50001) ''
AssertEq 'after accept ADMIN role = 0 (viewer)' (RoleOf $newId $tok[$ADMIN]) 0
Assert 'manager promotes ADMIN to editor -> 0' ((C (J "$Base/spaceUser/update/role" 'POST' $tok[$MEMBER] $B_ROLE2)) -eq 0) ''
AssertEq 'ADMIN role now 2' (RoleOf $newId $tok[$ADMIN]) 2
Assert 'promote to manager -> 40000' ((C (J "$Base/spaceUser/update/role" 'POST' $tok[$MEMBER] $B_ROLE3)) -eq 40000) ''
$mine = ConvertFrom-Json (J "$Base/spaceUser/my/space/list/page" 'POST' $tok[$ADMIN] (W 'myspace' '{"current":1,"pageSize":20}'))
$found = $mine.data.records | Where-Object { "$($_.id)" -eq "$newId" }
Assert 'ADMIN my-space list contains the joined team space' ($null -ne $found) ''
Assert 'my-space record carries currentUserRole = 2' ($found.currentUserRole -eq 2) ''

Write-Host "`n===== 6. quota read/write (the cross-service WRITE) ====="
$r = Ji "$Base/internal/space/quota/$TEAM"
$q0 = (ConvertFrom-Json $r).data
Show 'quota before' $r
$B_QADD = W 'qadd' ('{"spaceId":' + $TEAM + ',"sizeDelta":1024,"countDelta":1,"reason":"verify-4a"}')
$q1 = (ConvertFrom-Json (Ji "$Base/internal/space/quota/change" 'POST' $B_QADD)).data
Assert 'quota +1024/+1 applied' ([long]$q1.totalSize -eq ([long]$q0.totalSize + 1024) -and [long]$q1.totalCount -eq ([long]$q0.totalCount + 1)) ''
$q2 = (ConvertFrom-Json (Ji "$Base/internal/space/quota/change" 'POST' (W 'qroll' ('{"spaceId":' + $TEAM + ',"sizeDelta":-1024,"countDelta":-1,"reason":"rollback"}')))).data
Assert 'quota rollback matches original' ($q2.totalSize -eq $q0.totalSize -and $q2.totalCount -eq $q0.totalCount) ''
Assert 'missing delta fields tolerated (treated as 0)' ((C (Ji "$Base/internal/space/quota/change" 'POST' (W 'qzero' ('{"spaceId":' + $TEAM + '}')))) -eq 0) ''
$q3 = (ConvertFrom-Json (Ji "$Base/internal/space/quota/change" 'POST' (W 'qfloor' ('{"spaceId":' + $TEAM + ',"sizeDelta":-999999999999,"countDelta":-999999999999,"reason":"floor"}')))).data
Assert 'floor at 0 (never negative)' ($q3.totalSize -eq 0 -and $q3.totalCount -eq 0) ''
$q4 = (ConvertFrom-Json (Ji "$Base/internal/space/quota/change" 'POST' (W 'qrest' ('{"spaceId":' + $TEAM + ',"sizeDelta":' + $q0.totalSize + ',"countDelta":' + $q0.totalCount + ',"reason":"restore"}')))).data
Assert 'quota restored' ($q4.totalSize -eq $q0.totalSize -and $q4.totalCount -eq $q0.totalCount) ''
Assert 'quota change on unknown space -> 40400' ((C (Ji "$Base/internal/space/quota/change" 'POST' (W 'qbad' '{"spaceId":999999999,"sizeDelta":1,"countDelta":1}'))) -eq 40400) ''

Write-Host "`n===== 7. internal API auth and contract ====="
Assert 'internal wrong token -> 40101' ((C (curl.exe -s -H 'X-Internal-Token: wrong' "$Base/internal/space/$TEAM")) -eq 40101) ''
$r = Ji "$Base/internal/space/999999999"
Assert 'internal unknown space -> 0 with null data' ((ConvertFrom-Json $r).code -eq 0 -and $null -eq (ConvertFrom-Json $r).data) $r
$rm = ConvertFrom-Json (Ji "$Base/internal/space-user/roleMap" 'POST' (W 'rolemap' ('{"userId":' + $MEMBER + ',"spaceIds":[' + $TEAM + ']}')))
Assert 'internal roleMap MEMBER in TEAM -> 2' ($rm.data.$TEAM -eq 2) ''
Assert 'internal roleMap with no fields -> 0' ((C (Ji "$Base/internal/space-user/roleMap" 'POST' (W 'empty' '{}'))) -eq 0) ''
Assert 'internal getSpaceIdByUserId' ((IdOf (Ji "$Base/internal/space/getSpaceIdByUserId?userId=$MEMBER")) -eq $PRIV_MEMBER) ''
Assert 'internal listMySpaceByPage -> 0' ((C (Ji "$Base/internal/space/listMySpaceByPage?userId=$ADMIN&current=1&size=5")) -eq 0) ''
Assert 'internal listByIds returns 1' (((ConvertFrom-Json (Ji "$Base/internal/space/listByIds" 'POST' (W 'listids' ('[' + $TEAM + ']')))).data).Count -eq 1) ''
Assert 'internal listByIds empty -> empty' (((ConvertFrom-Json (Ji "$Base/internal/space/listByIds" 'POST' (W 'emptyarr' '[]'))).data).Count -eq 0) ''
Assert 'internal role/check editor for MEMBER in TEAM -> 0' ((C (Ji "$Base/internal/space/role/check?spaceId=$TEAM&userId=$MEMBER&requireRole=2")) -eq 0) ''
Assert 'internal role/check viewer for non-member -> 40101' ((C (Ji "$Base/internal/space/role/check?spaceId=$PRIV_MEMBER&userId=$ADMIN&requireRole=0")) -eq 40101) ''
Assert 'internal role/check unknown space -> 40400' ((C (Ji "$Base/internal/space/role/check?spaceId=999999999&userId=$MEMBER&requireRole=0")) -eq 40400) ''
Assert 'internal role/get returns 3 for owner' (((ConvertFrom-Json (Ji "$Base/internal/space/role/get?spaceId=$PRIV_MEMBER&userId=$MEMBER")).data) -eq 3) ''
Assert 'internal role/get returns null for non-member' ($null -eq (ConvertFrom-Json (Ji "$Base/internal/space/role/get?spaceId=$PRIV_MEMBER&userId=$ADMIN")).data) ''

Write-Host "`n===== 8. cleanup + cascade check ====="
$B_REMOVE = W 'remove' ('{"id":' + $memberId + '}')
Assert 'ADMIN leaves the team space -> 0' ((C (J "$Base/spaceUser/remove" 'POST' $tok[$ADMIN] $B_REMOVE)) -eq 0) ''
Assert 'leave twice -> 40400' ((C (J "$Base/spaceUser/remove" 'POST' $tok[$ADMIN] $B_REMOVE)) -eq 40400) ''
# checkSpaceAuth allows the owner OR a platform admin, so ADMIN deleting MEMBER's space is
# EXPECTED TO SUCCEED (that is the documented rule). The deletion also exercises the cascade.
Assert 'platform ADMIN may delete another user space (by design) -> 0' ((C (J "$Base/space/delete" 'POST' $tok[$ADMIN] (W 'delnew2' ('{"id":' + $newId + '}')))) -eq 0) ''
Assert 'space already deleted by ADMIN -> MEMBER delete returns 40400' ((C (J "$Base/space/delete" 'POST' $tok[$MEMBER] (W 'delnew' ('{"id":' + $newId + '}')))) -eq 40400) ''
Assert 'MEMBER deletes own no-level team space -> 0' ((C (J "$Base/space/delete" 'POST' $tok[$MEMBER] (W 'delnolvl' ('{"id":' + $nolevel + '}')))) -eq 0) ''
# 刻意**不删** $privAdmin（adminspace）：它是既有数据，本脚本只清理自己新建的东西。
# 删空间这条路径已经由上面两次 "MEMBER deletes own ..." 覆盖，不需要拿既有数据来验证。
Assert 'deleted space no longer readable' ($null -eq (ConvertFrom-Json (Ji "$Base/internal/space/$newId")).data) ''
$rmAfter = ConvertFrom-Json (Ji "$Base/internal/space-user/roleMap" 'POST' (W 'rmcheck' ('{"userId":' + $ADMIN + ',"spaceIds":[' + $newId + ']}')))
$rmCount = @($rmAfter.data.PSObject.Properties).Count
Assert 'cascade: space_user rows of the deleted space are gone' ($rmCount -eq 0) (ConvertTo-Json $rmAfter)

Write-Host "`n===== 9. pre-existing data regression ====="
$vo = ConvertFrom-Json (J "$Base/space/get/vo?id=$PRIV_MEMBER" 'GET' $tok[$MEMBER])
Assert 'MEMBER private space readable, role 3' ($vo.code -eq 0 -and $vo.data.currentUserRole -eq 3) ''
Assert 'MEMBER private space totalCount still 21' ($vo.data.totalCount -eq 21) ''
Assert 'MEMBER private space totalSize still 707046' ($vo.data.totalSize -eq 707046) ''
$vo = ConvertFrom-Json (J "$Base/space/get/vo?id=$TEAM" 'GET' $tok[$ADMIN])
Assert 'TEAM totalSize unchanged after quota round-trip' ($vo.data.totalSize -eq 59396) ''
Assert 'TEAM totalCount unchanged after quota round-trip' ($vo.data.totalCount -eq 1) ''
Assert 'list/level works without login' ((C (J "$Base/space/list/level")) -eq 0) ''
Assert 'admin-only list/page with non-admin -> 40101' ((C (J "$Base/space/list/page" 'POST' $tok[$MEMBER] (W 'listpage' '{"current":1,"pageSize":10}'))) -eq 40101) ''
Assert 'admin list/page -> 0' ((C (J "$Base/space/list/page" 'POST' $tok[$ADMIN] (W 'listpage2' '{"current":1,"pageSize":10}'))) -eq 0) ''
$lp = ConvertFrom-Json (J "$Base/space/list/page/vo" 'POST' $tok[$ADMIN] (W 'listvo' '{"current":1,"pageSize":20,"spaceType":1}'))
Assert 'list/page/vo team spaces -> 0' ($lp.code -eq 0) ''
Assert 'list/page/vo carries currentUserRole for admin-owned rows' (($lp.data.records | Where-Object { "$($_.userId)" -eq $ADMIN } | Select-Object -First 1).currentUserRole -eq 3) ''
Assert 'list/page/vo pageSize>20 -> 40000' ((C (J "$Base/space/list/page/vo" 'POST' $tok[$ADMIN] (W 'listbig' '{"current":1,"pageSize":50}'))) -eq 40000) ''
Assert 'admin get raw space -> 0' ((C (J "$Base/space/get?id=$TEAM" 'GET' $tok[$ADMIN])) -eq 0) ''
Assert 'non-admin get raw space -> 40101' ((C (J "$Base/space/get?id=$TEAM" 'GET' $tok[$MEMBER])) -eq 40101) ''
$r = J "$Base/space/add" 'POST' $tok[$ADMIN] (W 'admteam' '{"spaceName":"4a-verify-admin-team","spaceType":1,"spaceLevel":2}')
$admTeam = IdOf $r
Assert 'ADMIN can create flagship team space' ($admTeam.Length -gt 0) $r
Assert 'ADMIN can update space -> 0' ((C (J "$Base/space/update" 'POST' $tok[$ADMIN] (W 'admupd' ('{"id":' + $admTeam + ',"spaceName":"4a-verify-admin-team-renamed"}')))) -eq 0) ''
Assert 'ADMIN deletes flagship team space -> 0' ((C (J "$Base/space/delete" 'POST' $tok[$ADMIN] (W 'admdel' ('{"id":' + $admTeam + '}')))) -eq 0) ''

Write-Host "`n================ RESULT ================" -ForegroundColor Yellow
Write-Host "PASS = $script:pass, FAIL = $script:fail"
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
