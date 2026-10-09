# tools/verify —— 分阶段验证脚本

这些脚本是**改造过程中真正跑过的验证**，不是事后补的样例：
每一阶段的验收结论（「零 `FROM space`」「助手仍能搜到私人空间」之类）
都是靠它们得出的。留在这里是因为后续阶段（5~9 加 MQ）还会继续用到同一套办法。

## 文件

| 文件 | 作用 |
|---|---|
| `GenToken.java` | 用共享密钥直接签发 JWT，省得为了验证去查每个人的密码。**密钥必须用 `-Dsecret=` 传入，文件里故意不含任何密钥**（曾经硬编码过一次，随代码推上了 GitHub，只能轮换密钥收场） |
| `DbInspect.java` | 打印 users / spaces / space_user / pictures 的真实数据，便于断言写在真实的 id 上 |
| `DbSpaces.java` / `DbRestore.java` | 空间相关的数据核对与恢复（`DbRestore` 是 `verify-4a` 早期版本误删数据后的补救工具） |
| `ProbePrecision.java` | 证明「报错里的 id」就是 JS `Number` 精度丢失后的值（阶段 4 收尾那个 `Long` 序列化 bug） |
| `verify-4a.ps1` | 阶段 4a：`picture-space-service` 独立验证（88 项断言） |
| `verify-4b.ps1` | 阶段 4b：拆完之后的**全链路**验证（44 项断言） |
| `verify-5a.ps1` | 阶段 5a：MQ 地基（拓扑、投递消费、**幂等去重**、ack 排空）（15 项断言） |
| `verify-5b.ps1` | 阶段 5b：索引写路径走 MQ（拓扑、投递→消费、幂等、不存在图片跳过）（18 项断言） |
| `demo-5b-index.ps1` | **给人看的**演示：上传一张图，观察它进队列、被消费、索引完成、然后清理 |
| `diagnose-space-view.ps1` | 排查「点进空间报错」：给定 spaceId，四步定位（含该用户可用空间列表） |

## 怎么跑

```bash
# 0. 起依赖：MySQL、user-service(8126)、space-service(8130)
#    verify-4b 还需要单体(8124) 与网关(9003) —— 刻意用非日常端口，
#    这样不会打扰正在跑的日常环境

# 1. 准备 token（会写出一份含真实 token 的文件，别提交）
cd tools\verify
javac -encoding UTF-8 -cp "<jjwt-api.jar>" GenToken.java
# ⚠️ 必须显式传 -Dsecret=<jwt.secret>：密钥**故意不写在 GenToken.java 里**。
#    取 picture-backend/src/main/resources/application.yml 的 jwt.secret（该文件被 git 忽略，
#    user-service 签发、另两个服务校验，三处必须是同一个值）。
#    曾经这里硬编码过真实密钥，结果随代码推上了 GitHub —— 任何能看仓库的人都能签发 admin
#    token，只能靠轮换密钥收场。别再给它加默认值。
java -Dsecret=<jwt.secret 的值> -cp ".;<jjwt-api>;<jjwt-impl>;<jjwt-jackson>;<jackson-*>" GenToken ^
     2052376987161366530 2052725134551248898 2052378415758086146 ^
     > ..\..\.tmp-dbdump\tokens.txt
# 用户 id 从 DbInspect 的输出里取

# 2. 跑验证（必须从仓库根目录跑）
powershell -ExecutionPolicy Bypass -File tools\verify\verify-4a.ps1
powershell -ExecutionPolicy Bypass -File tools\verify\verify-4b.ps1
```

脚本会自己清理：它新建的空间都会删掉，改过的配额都会改回原值。

### 阶段 5（MQ）怎么跑

MQ **默认是关的**（`picture.mq.enabled: false`），所以要先带开关把单体起起来。
仓库里准备了专用启动脚本（用 8128，不动你日常的 8123）：

```bash
run-backend-mq.cmd            # MQ 打开 + 自检接口打开 + 写 picture-backend\app.log
run-backend-mq.cmd 8123       # 想用日常端口就传端口号
```

跑验证（**必须带 `-LogFile`**，否则跳过日志类断言）：

```bash
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5a.ps1 -LogFile picture-backend\app.log
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5b.ps1 -LogFile picture-backend\app.log
```

想看效果而不是看断言，跑演示脚本（它会打印每一步在做什么）：

```bash
powershell -ExecutionPolicy Bypass -File tools\verify\demo-5b-index.ps1
```

> `verify-5b` 刻意**不新建数据**：它对图库里已有的一张图片做重新索引（重新索引幂等，可随时重跑）。
> `demo-5b-index.ps1` 会真的上传一张图并观察全链路，所以它**会删掉自己上传的那张**。

### 阶段 5 相关的两个额外坑

3. **日志断言有竞态**：「队列已排空」与「日志已落盘」之间没有先后保证，
   读一次就断言会随机失败（看起来像服务有问题）。→ 本仓库的做法：**轮询等待**
   （`verify-5b.ps1` 里的 `WaitLogCount`）。

4. **匹配串要够独特**：`Select-String -SimpleMatch '处理成功'` 会同时命中
   `消息处理成功：messageId` 和 `收到测试消息并处理成功`，把计数变成 2。
   → 匹配到 **messageId 那一行**（`消息处理成功：messageId`），别用泛化子串。

## 两个必须记住的坑（否则会看到一整屏假的「服务有问题」）

1. **Windows PowerShell 5.1 传原生程序的参数会被拆坏**。含双引号的 JSON 内联在
   单引号字符串里交给 `curl.exe`，会静默变成 `{spaceId:...}` 这种非法 JSON，
   服务端报 JSON 解析错误 —— 看起来像服务 bug，其实是测试脚本的问题。
   → 本仓库的做法：**请求体一律写文件，用 `--data-binary "@file"`**。

2. **`ConvertFrom-Json` 会把 19 位雪花 id 变成 double**，精度直接丢
   （`2107704813947998209` → `...208`），拿它去请求就是另一个 id，报「不存在」。
   → 本仓库的做法：**id 一律用正则从响应原文里取字符串**，不经过 `ConvertFrom-Json`。

另外：脚本内容**必须保持纯 ASCII**。PS 5.1 会把无 BOM 的 UTF-8 脚本当 ANSI/GBK 解析，
脚本里的中文字面量会被打乱；最阴的是——即使打乱了，搜索类断言仍可能「碰巧通过」，
让测试悄悄失去判别力（阶段 4 就踩过一次，见 `verify-4b.ps1` 里那段注释）。
