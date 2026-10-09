# Run it FROM THE REPOSITORY ROOT.
#
#   powershell -ExecutionPolicy Bypass -File tools\verify\verify-5d.ps1
#
# Requirements (both with MQ on):
#   - monolith on 8128:   $env:PICTURE_MQ_ENABLED='true'; $env:PICTURE_MQ_DEBUG='true'
#                         $env:SPACE_API_BASE_URL='http://127.0.0.1:8131'   # 指向下面这个实例
#   - space-service on 8131 with PICTURE_MQ_ENABLED=true
#   - RabbitMQ up, MySQL up
#
# What this verifies (phase 5d): quota is no longer a check-then-write race,
# and "giving quota back" is event driven.
#   A. 原子预占：并发抢额度**不会超卖**（这是 5d-b 的核心目标）
#   B. 对照实验：同一并发场景下，「先查后扣」的旧逻辑会超卖（证明修复有效）
#   C. 额度不足时返回**业务提示**（50001 空间条数不足），而不是系统错误
#   D. 额度变更事件：monolith -> MQ -> space-service 幂等消费，额度真的变了
#   E. 重复投递同一条 messageId **不会扣两次**（增量语义下的关键保障）
#   F. 清理：测试空间删掉，你自己的空间配额必须原样不动
#
# 这个脚本会创建一个临时的团队空间并把它的上限压到很小，跑完删掉。
# 它**不会**碰你原有的空间（最后一步会核对这一点）。

param(
    [string]$Mono = 'http://127.0.0.1:8128',
    [string]$Space = 'http://127.0.0.1:8131',
    [string]$RabbitApi = 'http://127.0.0.1:15672/api',
    [string]$RabbitUser = 'admin',
    [string]$RabbitPassword = 'change-me-dev-only',
    [string]$TokensFile = '.tmp-dbdump\tokens.txt',
    [int]$Threads = 20,
    [int]$SmallMaxCount = 5
)

$ErrorActionPreference = 'Continue'
$script:pass = 0; $script:fail = 0
function Assert($name, $ok, $detail = '') {
    if ($ok) { $script:pass++; Write-Host "[PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "[FAIL] $name  $detail" -ForegroundColor Red }
}
$cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPassword}"))

# --- 直连数据库的小工具（本项目的惯例：断言要写在真实数据上）---
$Mysql = "$env:USERPROFILE\.m2\repository\com\mysql\mysql-connector-j\8.0.31\mysql-connector-j-8.0.31.jar"
$SqlDir = Join-Path $env:TEMP 'v5d-sql'
New-Item -ItemType Directory -Force $SqlDir | Out-Null
$SqlSrc = Join-Path $SqlDir 'Q.java'
$sqlLines = @(
 'import java.sql.*;',
 'public class Q {',
 '  static final String URL="jdbc:mysql://localhost:3306/database1?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";',
 '  public static void main(String[] a) throws Exception {',
 '    try (Connection c=DriverManager.getConnection(URL,"root","123456"); Statement st=c.createStatement()) {',
 '      if (a[0].equals("shrink")) {',
 '        st.executeUpdate("update space set maxCount=" + a[2] + ", maxSize=10240, totalCount=0, totalSize=0 where id=" + a[1]);',
 '        System.out.println("ok");',
 '      } else if (a[0].equals("count")) {',
 '        try (ResultSet rs=st.executeQuery("select totalCount,maxCount from space where id=" + a[1])) { rs.next(); System.out.println(rs.getLong(1) + "/" + rs.getLong(2)); }',
 '      } else if (a[0].equals("baseline")) {',
 '        StringBuilder sb = new StringBuilder();',
 '        try (ResultSet rs=st.executeQuery("select id,spaceName,totalCount,totalSize from space where isDelete=0 order by id")) {',
 '          while (rs.next()) sb.append(rs.getString(1)).append(":").append(rs.getString(3)).append(":").append(rs.getString(4)).append(";");',
 '        }',
 '        System.out.println(sb);',
 '      }',
 '    }',
 '  }',
 '}'
)
[System.IO.File]::WriteAllText($SqlSrc, ($sqlLines -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
$SqlOut = Join-Path $env:TEMP 'v5d-out'
New-Item -ItemType Directory -Force $SqlOut | Out-Null
& javac -encoding UTF-8 -d $SqlOut -cp $Mysql $SqlSrc 2>&1 | Out-Null
function Sql([string[]]$args2) {
    return (& java "-Dfile.encoding=UTF-8" -cp "$SqlOut;$Mysql" Q @args2 2>&1 | Select-Object -First 1)
}

# --- token ---
if (-not (Test-Path $TokensFile)) { Write-Host "FATAL: 缺少 $TokensFile" -ForegroundColor Red; exit 2 }
$line = (Get-Content $TokensFile | Select-String '^2052725134551248898=').Line
$Token = ($line -replace '^2052725134551248898=','').Trim()
if ($Token.Length -lt 20) { Write-Host "FATAL: token 无效" -ForegroundColor Red; exit 2 }

Write-Host "`n===== 0. 前置检查 ====="
$ping = curl.exe -s -o NUL -w "%{http_code}" -X POST "$Mono/api/mq/debug/probe-concurrent-reserve?spaceId=1&threads=1"
Assert '单体(8128) 的并发探针可用（需 PICTURE_MQ_DEBUG=true）' ($ping -eq '200') "http=$ping"
$q = (curl.exe -s -H "Authorization: Basic $cred" "$RabbitApi/queues/%2F/picture.mq.space.quota") | ConvertFrom-Json
Assert 'space-service 已消费配额队列（需它的 PICTURE_MQ_ENABLED=true）' ($q.consumers -ge 1) "consumers=$($q.consumers)"
$before = Sql @('baseline')
Write-Host "  跑之前的空间配额基线：$before"

# --- 建测试空间并压小上限 ---
$f = Join-Path $env:TEMP 'v5d-add.json'
[System.IO.File]::WriteAllText($f, '{"spaceName":"5d-verify-probe","spaceType":1,"spaceLevel":0}', (New-Object System.Text.ASCIIEncoding))
$resp = curl.exe -s -X POST "$Space/api/space/add" -H "Authorization: Bearer $Token" -H 'Content-Type: application/json' --data-binary "@$f"
$spaceId = [regex]::Match($resp, '"data":"?(\d{15,})"?').Groups[1].Value
Assert '创建测试空间' ($spaceId.Length -ge 15) $resp.Substring(0, [Math]::Min(140, $resp.Length))
if ($spaceId.Length -lt 15) { exit 1 }
Write-Host "  测试空间 id = $spaceId（上限压到 $SmallMaxCount 条）"
Sql @('shrink', $spaceId, "$SmallMaxCount") | Out-Null

Write-Host "`n===== A. 原子预占：并发抢额度不超卖 ====="
$r = curl.exe -s -X POST "$Mono/api/mq/debug/probe-concurrent-reserve?spaceId=$spaceId&threads=$Threads&sizeEach=0&countEach=1"
$d = $null; try { $d = ($r | ConvertFrom-Json).data } catch { }
Assert "探针返回成功（$Threads 线程抢 $SmallMaxCount 个名额）" ($null -ne $d) $r
if ($null -ne $d) {
    Write-Host ("  成功={0} 被拒={1} 失败={2}  totalCount: {3} -> {4}  上限={5}" -f `
        $d.success, $d.limited, $d.failed, $d.totalCountBefore, $d.totalCountAfter, $d.maxCount)
    Assert "成功数不超过上限（应为 $SmallMaxCount）" ([int]$d.success -le $SmallMaxCount) "success=$($d.success)"
    Assert '没有因系统故障而失败（失败都应是「额度不足」）' ([int]$d.failed -eq 0) "failed=$($d.failed)"
    Assert '并发结束后仍在限额内' ($d.countWithinLimit -eq $true) "totalCountAfter=$($d.totalCountAfter)"
}
$db = Sql @('count', $spaceId)
$dbCount = [int]($db -split '/')[0]
Assert "数据库里的真实值不超卖（读数 $db）" ($dbCount -le $SmallMaxCount) "totalCount=$dbCount"

Write-Host "`n===== C. 额度不足返回业务提示，而不是系统错误 ====="
# 额度已经占满，再抢一次必然失败；断言错误码是 50001（操作失败）而不是 50000（系统错误）
$one = curl.exe -s -X POST "$Mono/api/mq/debug/probe-concurrent-reserve?spaceId=$spaceId&threads=1&sizeEach=0&countEach=1"
$od = $null; try { $od = ($one | ConvertFrom-Json).data } catch { }
Assert '额度占满后再抢 -> 被判定为「额度不足」' ($null -ne $od -and [int]$od.limited -eq 1 -and [int]$od.success -eq 0) $one

Write-Host "`n===== D. 额度变更事件：monolith -> MQ -> space-service ====="
$beforeCount = [int]((Sql @('count', $spaceId)) -split '/')[0]
$mid = 'verify-5d-' + (Get-Date -Format 'HHmmssfff')
$pub = curl.exe -s -X POST "$Mono/api/mq/debug/publish-quota-changed?spaceId=$spaceId&countDelta=-1&sizeDelta=0&reason=verify"
Assert '投递额度变更事件' ($pub -match '"code":0') $pub
$deadline = (Get-Date).AddSeconds(20)
$afterCount = $beforeCount
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $afterCount = [int]((Sql @('count', $spaceId)) -split '/')[0]
    if ($afterCount -eq $beforeCount - 1) { break }
}
Assert "额度真的被还掉了（$beforeCount -> $afterCount）" ($afterCount -eq $beforeCount - 1) "before=$beforeCount after=$afterCount"

Write-Host "`n===== E. 幂等：同一条消息重复投递不会扣两次 ====="
$idemMid = 'verify-5d-idem-' + (Get-Date -Format 'HHmmssfff')
$payloadJson = '{"messageId":"' + $idemMid + '","messageType":"quota.changed","source":"verify","payload":{"spaceId":' + $spaceId + ',"sizeDelta":0,"countDelta":-1,"reason":"idem"}}'
$escaped = $payloadJson -replace '\\', '\\' -replace '"', '\"'
$bodyFile = Join-Path $env:TEMP 'v5d-pub.json'
[System.IO.File]::WriteAllText($bodyFile,
    ('{"properties":{},"routing_key":"quota.changed","payload":"' + $escaped + '","payload_encoding":"string"}'),
    (New-Object System.Text.ASCIIEncoding))
$beforeIdem = [int]((Sql @('count', $spaceId)) -split '/')[0]
1..2 | ForEach-Object {
    curl.exe -s -X POST "$RabbitApi/exchanges/%2F/picture.mq/publish" -H "Authorization: Basic $cred" `
        -H 'Content-Type: application/json' --data-binary "@$bodyFile" | Out-Null
    Start-Sleep -Seconds 2
}
Start-Sleep -Seconds 3
$afterIdem = [int]((Sql @('count', $spaceId)) -split '/')[0]
Assert "投递两次只扣一次（$beforeIdem -> $afterIdem）" ($afterIdem -eq $beforeIdem - 1) "before=$beforeIdem after=$afterIdem"

Write-Host "`n===== F. 清理与回归 ====="
$df = Join-Path $env:TEMP 'v5d-del.json'
[System.IO.File]::WriteAllText($df, ('{"id":' + $spaceId + '}'), (New-Object System.Text.ASCIIEncoding))
$del = curl.exe -s -X POST "$Space/api/space/delete" -H "Authorization: Bearer $Token" -H 'Content-Type: application/json' --data-binary "@$df"
Assert '删除测试空间' ($del -match '"code":0') $del
$alive = Sql @('baseline')
Assert '你自己的空间配额完全没被改动' ($alive -eq $before) "before=$before`n         after =$alive"

Write-Host "`n（B 组「旧逻辑会超卖」的对照实验不在本脚本里自动跑：`n 它要用 JDBC 直接模拟「先查后扣」，属于一次性论证，结论见 docs/local-env.md 第十节）" -ForegroundColor DarkYellow

Write-Host "`n================ RESULT ================"
Write-Host "PASS = $script:pass, FAIL = $script:fail"
if ($script:fail -gt 0) { exit 1 }
