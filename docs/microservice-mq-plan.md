# 微服务 + 消息队列改造方案

> 目的：把当前「Java 单体 + Python AI 服务」改造成微服务架构，并引入消息队列，
> 用于补齐**架构设计能力**这部分简历内容。
>
> 本文只做方案设计，不含实现代码。
> 结论摘要：**Nacos / OpenFeign / Sentinel / Gateway / RabbitMQ 选型合适；Seata 需要收敛使用范围**，
> 而且真正值得做的不是「多加几个组件」，而是**先用 MQ 把现有代码里已经存在的痛点改对**——
> 这样面试时才有「问题 → 方案 → 代价」的完整链条可讲。

---

## 实施进度

| 阶段 | 状态 | 产出 | 说明 |
|---|---|---|---|
| **0. 环境可复现** | ✅ 已完成 | `docker-compose.yml`、`.env.example`、`picture-backend/sql/00-schema.sql`、`docs/local-env.md` | Redis / RabbitMQ / Nacos 已实测可用；MySQL 以 profile 方式可选（本机 3306 已占用，容器映射 3307） |
| **1. 统一网关** | ✅ 已完成 | `picture-gateway/`（独立工程）、前端 `src/config.ts` | 已实测：HTTP 代理响应体与直连逐字节一致、CORS 去重、traceId 透传、**WebSocket 协同编辑可正常收发** |
| **2. 注册与配置** | ✅ 已完成 | 单体接 Nacos（注册 + 配置中心）、网关加 `nacos` profile 走 `lb://` | 已实测：单体与网关都注册进 Nacos、两台实例负载均衡生效、`lb://` 与 `lb:ws://` 均可用、`pictureIndex.*` 进配置中心且支持动态刷新、**Nacos 停掉后单体照常启动并正常服务** |
| **3a. JWT 收敛为唯一凭据** | ✅ 已完成 | 单体 `getLoginUser` 改为 JWT 优先；前端统一附 `Authorization`；WS 握手支持 `?token=` | 已实测：同一 token 交替打两个实例 **8/8 成功**（阶段 2 用 Session 是 4/8），无损修复了「无法水平扩容」的正确性缺陷 |
| **3b. 拆 user-service** | ✅ 已完成 | `picture-user-service/`（独立工程）；单体 `UserService` 接口不变、实现换成 Feign + Caffeine；删除单体 `UserController` / `UserMapper` / `UserMapper.xml`；网关加 `/api/user/**` 路由 | 已实测：**单体日志里零 `FROM user`**、作者信息仍正常显示（改为经 Feign 从 user-service 取得）；`/api/internal/**` 经网关返回 404 |
| **4. 拆 space-service** | ✅ 已完成 | `picture-space-service/`（独立工程，端口 **8130**）；单体 `SpaceService` / `SpaceUserService` 换成 Feign + Caffeine；删除单体 2 个空间 Controller / 2 个 Mapper / Mapper.xml 及 6 个已无用的空间类；网关加 `/api/space/**`、`/api/spaceUser/**` 路由 | 已实测：**space/space_user 的 SQL 只出现在 space-service 日志里**；空间与成员全链路（CRUD、邀请/接受、角色、配额）**88 项**断言全绿、拆分链路 **44 项**全绿；**助手仍能搜到私人空间的图**。另有 3 处收尾修复（`Long` 序列化、图片代理鉴权、null 语义），见下 |
| **5. MQ 落地（索引异步化 + 配额事件化）** | ✅ 已完成 | 5a 消息地基 + 幂等消费；5b 索引写路径切 MQ；5c 本地消息表 + 补投 + DLQ 重放 + 对账；**5d 配额原子预占（消除 TOCTOU）+ 删除额度事件化（4c）** | 5a **16/16**、5b **18/18**、5c **11/11**（故障演练 **18/18**）、5d **14/14**；MQ 默认关闭、关闭时回落原行为。**并发 20 线程抢 5 个名额：旧逻辑超卖到 20，现在正好 5** |
| 6~9 | ⬜ 未开始 | — | 见第七节 |

### 阶段 4 的提交（含上生产后由用户报出的三处收尾修复）

| 提交 | 内容 |
|---|---|
| `0966893` | **4a** 抽出 `picture-space-service`（纯增量，**单体一行未改**），85 项断言全绿 |
| `decc2b0` | **4b** 单体换 Feign + 缓存、删 Controller/Mapper、网关加路由，41 项断言全绿 |
| `6ef87c6` | **收尾①** 区分「空间不存在」与「空间服务连不上」，别再误报 |
| `0b773d6` | **收尾②** 空间不存在时把 id 打进日志（正是这条日志揪出了收尾③） |
| `f6fc4b8` | **收尾③** 新服务补上 `Long → 字符串` 序列化 —— **「空间不存在」的真凶** |
| `3bab43c` | **收尾④** 图片代理支持 `token` 查询参数（`<img>` 带不了 `Authorization` 头） |
| `10afe74` | **收尾⑤** 修 `EditPicturePage` 的 `token is not defined`（上一条引入的回归） |

> 4a / 4b 的验证脚本后来都收进了 `tools/verify/`（`59f5164`），并顺手修了两处
> 「测试自己错了」的问题（`46b1bd2`、`f6fc4b8`）。收尾①~⑤ 暴露出的三个坑
> 已写进 `docs/local-env.md`（null 语义、`Long` 序列化、浏览器元素带不了请求头）。

### 阶段 4 的收尾修复（三处，都不是「拆分」本身的问题）

阶段 4 主体完成后，在实际使用中暴露出**三个与拆分正交**的问题，值得单独记：

| # | 现象 | 根因 | 为什么难发现 |
|---|---|---|---|
| 1 | 点进空间报「请求数据不存在」 | **新服务漏了单体早就有的 `Long → ToStringSerializer` 配置** → 雪花 id 以 JSON 数字下发 → 前端 `Number` 精度丢失（`...537` → `...500`） | 不报错、不影响展示，只在**下一跳**查那个 id 时才 404 |
| 2 | 团队空间里点编辑图片报「未登录」 | `/api/picture/proxy` 由 **`<img src>`** 发起，**永远带不了 `Authorization` 头**；阶段 3a 收敛成 JWT 后就再没凭据 | 公共图库图片不校验权限，只有**空间图片**才会走到 `getLoginUser` |
| 3 | 「空间服务连不上」被报成「空间不存在」 | 远程结果被压成 `null`，丢失了「没有」与「不知道」的区别 | 提示指向错误方向（以为是数据被删），把排查带偏两轮 |

**共同教训**：跨服务改造里真正危险的，**往往不是被拆的那部分逻辑，而是那些「横切的、
编译期不报错的」约定** —— 序列化规则、鉴权凭据怎么传、`null` 代表什么。
每拆一个服务，都该拿单体当基准问一遍：「它有哪些全局配置/约定是我不自知的？」


### 阶段 3 的四个提交

| 提交 | 内容 |
|---|---|
| `4b57759` | **3a** JWT 收敛为唯一凭据（多实例从 4/8 → **8/8**） |
| `e1ce6f1` | **3b-1** 抽出 `picture-user-service`，user 表唯一属主 |
| `984085b` | **3b-2** 单体改用 Feign 读用户，彻底不再碰 user 表 |
| `2f92e9a` / `6068329` | 3b 的连带修复：Agent 回调地址 404、助手 422 |

> 阶段 3 期间还顺手做了两件与主线无关的事：`5579240` 修 `spring-boot:run` 被
> `<skip>true</skip>` 静默跳过；`170b7c6` 加 Maven Wrapper + 一键启动脚本
> （本机没装 Maven，见 `docs/local-env.md`）。

### 阶段 3 的两条教训（阶段 4 要重点防范）

1. **跨语言的调用点没有编译期检查，最容易漏**：3b 把登录接口从单体搬到 user-service 后，
   Java 侧调用点我全查了，但**漏了 Python Agent 里写死的回调地址**
   （`BACKEND_LOGIN_USER_URL` 指向单体的 `/api/user/get/login`）。
   后果是**长期记忆静默失效** —— 助手照常回话，只是记不住任何东西，日志里只有一条 404。
   → **阶段 4 开工前必须把「谁调用了 `/api/space/**`」查全**，包括 Python、前端、脚本。
2. **一个 `null` 能引发完全看不出原因的报错**：前端 `spaceId: spaceId.value ?? undefined`
   会把整个 key 从 JSON 里删掉 → Java 得到 null → Python 必填校验失败 **422** →
   前端只看到「助手无法响应」。已把 Python 侧改成宽容解析（`field_validator` 归一 `null`）。
   → 阶段 4 新增的 HTTP 契约，**跨服务字段一律按「可缺省」设计**，别用必填。

### 阶段 4 预检结论（2026-10-07 实测，开工前必读）

**规模**：`spaceService.*` / `spaceUserService.*` 在单体里有 **54 处**调用，
分布在 `FileController`、`PictureController`、`SpaceController`、`SpaceUserController`、
`SpaceUserServiceImpl`、`PictureServiceImpl`、`AlbumServiceImpl` 共 8 个文件。

**三个必须提前设计的难点**（都比阶段 3 难）：

1. **配额是跨服务的「写」，这是项目里第一个真正的跨服务事务**
   `PictureServiceImpl` 有 3 处 `spaceService.lambdaUpdate().setSql("totalSize = totalSize + Δ")`
   （上传/删除图片时改空间容量：L219、L667、L730）。
   拆出去后就是方案第五节说的那个决策点。
   → 按既定策略**分两步**：先同步 Feign 跑通（可回滚），再切事件驱动 + 本地消息表。
2. **`checkSpaceUserAuth` 在热路径上**
   每次图片列表 / 上传 / 删除都要校验空间权限（`PictureServiceImpl` L147/605/620/716 等）。
   → 必须照搬用户那套：**保留接口、换实现 + 本地 Caffeine 缓存**，否则等于给所有图片接口
   加一次网络往返。
3. **`SpaceVO` / `SpaceUserVO` 都要填用户信息 → 会出现嵌套跨服务调用**
   「单体 → space-service → user-service」。失败放大效应要提前定策略：
   沿用阶段 3 的分法 —— **渲染路径降级、鉴权路径明确报错**。

**好消息**：Python Agent **不直接调 `/api/space/**`**，只是把 `spaceId` 当参数传给
图片/相册接口，所以阶段 4 **不会重演那个 404**。

**但要补一条验收项**：前端拿 `spaceId` 走的是 `/api/space/getIdByUserId`。
这条链路一旦断，**助手会静默退化成只搜公共图库**（和那次 422 一样属于「不报错但功能没了」），
所以阶段 4 的验收清单里要有「助手仍能搜到私人空间的图」。

### 阶段 4 完成后回看这三条（实测结论）

| 预检的判断 | 实际结果 |
|---|---|
| 配额是第一个跨服务写 | ✅ 属实。做成「增量 + 服务端原子累加 + 返回值带回最新值」，并顺手修掉原实现的缺陷：**并发删除不再把配额减成负数**（`GREATEST(...,0)` 兜底，原来没有这层保护） |
| `checkSpaceUserAuth` 在热路径上 | ✅ 属实。最终做成「单体保留 `SpaceUserService` 接口 + `SpaceAuthService` 本地缓存（60s）」，图片链路调用点没改 |
| SpaceVO 会引发嵌套调用 | ⚠️ **只对了一半**。`SpaceVO` 的创建者信息**没有**在 space-service 侧查询 —— 单体自己的 `UserService` 本来就是「Feign + 缓存」，没必要让 space-service 再跨一次去问 user-service。真正产生嵌套调用的只有 `SpaceUserVO`（成员列表要显示成员与邀请人的昵称），而那是 space-service 自己渲染、不经过单体。**少一跳就是少一个失败点** |
| 「助手仍能搜到私人空间的图」 | ✅ 已作为验收项实测通过（agent 返回的 URL 落在私人空间前缀下，未退化成公共图库） |

**预检没料到、实际踩到的一条**：把单体接口参数从 `User` 改成 `Long loginUserId` 之后，
调用点会写成 `loginUser.getId()` —— **未登录时先 NPE**，被兜底成 `50000 系统错误`，
而拆分前是明确的 `40100 未登录`。回归是 4b 实测抓到的，已改回保留 `User` 入参。
→ **教训**：跨服务改造一旦调整参数类型，「判空」的位置就从被调方挪到了调用方；
挪错一步，就把一眼能看出的结论变成查不出原因的 500。（与上面第 2 条 null 教训同源。）

**预检也没料到的第二条（上生产后由用户报出来的）**：单体查空间是跨服务调用，
而原来的实现把**两种「查不到」压成了同一个 null**：

| 情况 | space-service 的真实响应 | 修复前单体报的 | 应该报的 |
|---|---|---|---|
| 空间真的不存在 | `code=0, data=null` | 40400「空间不存在」✅ | 40400「空间不存在」 |
| **space-service 连不上 / 返回非 0** | 抛异常或 `code!=0` | 40400「空间不存在」❌ | 50000「空间服务暂不可用」 |

后果：空间服务抖一下（启动中、被重启、端口冲突），用户看到的是
**「空间不存在」**——按这个提示去查会发现空间明明在库里，排查方向整个跑偏。
→ **教训**：跨服务之后，「一个 null」承载不了「没有」与「不知道」两种语义。
**只要是把远程结果压成 null 再判空报错的地方，都要先问一句：这个 null 会不会是故障？**
修法见 `SpaceAuthServiceImpl#fetchSpace`（按 code 是否为 0 区分）。

**预检也没料到的第三条（最隐蔽，也是最终真正的那条）**：
新的两个 Java 服务**漏了单体早就有的「Long 以字符串下发」配置**
（`JsonConfig` 里的 `Long` → `ToStringSerializer`）。

雪花 id 是 19 位，而 **JS 的 Number 只能精确到 2^53-1**，一旦以 JSON 数字下发，
前端拿到的那一刻精度就丢了，而且**丢了之后看不出任何异常**——列表能显示、名字能显示，
只有拿这个 id 去请求时才 404。实测日志里的指纹：

```
真实 2107713929437249537  →  前端请求 2107713929437249500
真实 2100208978036293634  →  前端请求 2100208978036293600
```

→ **教训**：跨服务契约里凡是 `Long`（尤其是 id），**一律以字符串下发**；
「单体有、新服务没有」的横切配置（Jackson / 全局异常 / 序列化）要列一张清单逐个对齐。
这条比前两条更值得记：它不报错、不影响展示，只让你在**下一跳**才看到 404，
所以最容易被归因成「数据被删了」。验证脚本里已加断言（`verify-4a.ps1` 第 3 节：
id 必须是带引号的字符串），避免它悄悄回来。

### 阶段 4 建议的实施顺序

沿用阶段 3 验证过的节奏，**每个里程碑独立可回滚**：

1. **4a（纯增量，不碰单体）**：建 `picture-space-service` 并独立验证 ——
   空间 CRUD、成员邀请/接受、角色校验、配额读写都能在独立端口上跑通。
   这一步单体一行不改，**不可能破坏现有功能**。
2. **4b**：单体把 `SpaceService` / `SpaceUserService` 的实现换成 Feign + 缓存，
   删除单体的 `SpaceController` / `SpaceUserController` / 对应 Mapper，网关加
   `/api/space/**` 与 `/api/spaceUser/**` 路由。
3. **4c（可选，与阶段 5 合并）**：配额从同步 Feign 切成事件驱动。

**验收标准**：空间与成员功能不回退；单体日志里不再出现 `FROM space` / `FROM space_user`；
助手仍能搜到私人空间的图片。

**补充说明（4b 实际做法与预检的一处差异）**：「接口不变、调用点不动」这条只对
*图片/相册链路真正在用* 的方法成立（`getById` / `listByIds` / `checkSpaceAuth` /
`getSpaceIdByUserId` / `getSpaceVOPage`）。凡是只被那两个被删 Controller 用到的
（`addSpace` / `updateSpace` / `removeById` / `getQueryWrapper` / 邀请与成员管理的全部方法）
都直接删掉了 —— 前端现在经网关直接打 space-service，在单体留一层纯转发的空壳
只会让「谁在负责什么」变得含糊，那是分布式单体，不是微服务。

### 阶段 3 为什么拆成 3a / 3b（保留记录）

先把**正确性**修掉，再做**服务拆分**：

- **3a（已完成）**：登录态从「单实例内存 Session」改为 JWT。因为 Session 不可跨实例，
  这是**当前唯一真正挡住扩容的缺陷**；而它也是拆分里最难的一半 —— 身份识别改完之后，
  拆服务基本就是「搬代码 + 换调用方式」。
- **3b（待做）**：把认证与用户 CRUD 抽成 `picture-user-service`。
  这里有个必须坚持的底线：**不能让新服务和单体共享 `user` 表**。
  共享库的「微服务」面试官一眼就能看穿（"这不算拆"），所以 3b 必须同时把
  单体的用户读取路径换掉 —— 计划做法是**保留 `UserService` 接口不变，
  把实现换成「OpenFeign 调 user-service + 本地 Caffeine 缓存」**，
  这样几十处调用点（`getById` / `listByIds` / `getUserVO` …）不用动。
  这是一个独立的、有明确验收标准的改动，所以单独作为 3b。

> 3a 的实测细节：JWT 优先解析后，旧的行为仍保留一个**过渡期兜底**（没带 Authorization 时回退 Session），
> 确认所有调用方都改成 Bearer 之后可以把这个分支删掉，彻底去掉 Session 依赖。

### 阶段 2 的三条实测结论（细节见 `docs/local-env.md` 第八节）

1. **Nacos 配置内容必须纯 ASCII**：Spring Cloud Alibaba 用 JVM 默认字符集解析远程配置，
   中文 Windows（代码页 936）下含中文会抛 `MalformedInputException`，
   **而且整份远程配置被静默跳过**（不报错、不启动失败，极难发现）。已用纯 ASCII 规避。
2. **必须显式指定注册 IP**：本机 7 个网卡，Nacos 自动选 IP 不可控（实测挑中 Hyper-V 的
   `192.168.137.1`），一旦挑到不可达网卡，`lb://` 就转发失败。已固定 `127.0.0.1` 并支持环境变量覆盖。
3. **【最重要】多实例下 Session 登录时好时坏**：两个实例 + 网关轮询，带同一个 Cookie
   连发 8 次，实测 **4 成功 / 4 失败**（失败报 `40101 未登录`）—— 登录态在单实例内存里。
   → **当前架构无法水平扩容。**

> 第 3 条把**阶段 3（JWT 收敛为唯一凭据）从「设计整洁」变成了「正确性缺陷」**：
> 只要想多副本，就必须先解决它。短期可以用「网关粘滞会话」或 Spring Session + Redis 缓解，
> 但 JWT 无状态才是正解。面试时这是个很好的「一扩容就暴露存量设计缺陷」的案例。

改动都在 `feature/microservice-phase01` 分支上，`main` 未动；阶段 1 是**纯增量**
（新增独立工程，没改单体代码），**阶段 2 首次改到单体**（依赖 + 配置 + 1 行注解，共 3 处）。
回滚命令见 `docs/local-env.md` 第七、八节。

**阶段 1 踩到的坑（务必记住）**：`DedupeResponseHeader` 放在
`spring.cloud.gateway.default-filters` 下会**同时作用到 WebSocket 路由并破坏回包链路** ——
客户端收到 101 却收不到任何消息、随即以 1006 断开，而后端日志显示房间已建又立刻离开。
解决办法是把这类响应处理过滤器**只挂在 HTTP 路由的 `filters` 上**。细节见 `docs/local-env.md` 第五节。

---

## 一、先看清现状：这个项目现在是什么形态

### 1.1 物理部署

| 进程 | 端口 | 职责 |
|---|---|---|
| Java 后端 | 8123（context-path `/api`） | 业务、存储、权限、协同编辑、缓存 |
| Python Agent | 8000 | LangGraph 智能助手（11 个工具） |
| Python 索引服务 | 8001 | 图片向量索引 / 语义检索 |
| Python Qwen 网关 | 8080 | Qwen-VL 多模态调用 |
| Vue 前端 | 5173 | 界面 |

调用关系：

```
浏览器 ──HTTP(:8123, JSESSIONID cookie)──► Java 后端 ──► MySQL / Redis(192.168.3.128) / 腾讯 COS
   │                                            │
   └──WebSocket(:8123/api/ws/picture/edit)───────┘
                                                │ HTTP :8001（检索/索引）
Java ──► :8000 Agent ────────────────────────────┼── HTTP :8080 Qwen-VL
                                                └── HTTP :8123 反向回调 Java（Bearer token）
```

要点：
- **浏览器只直连 8123**，并不直接访问 Python 的三个端口；
- Java 通过 HTTP 调 8001（索引）和 8000（Agent）；
- **8080 只被同目录的 Python 文件调用**，Java 不碰它；
- 前端 `request.ts` 把 `baseURL` 硬编码成 `http://localhost:8123`，Vite **没有配 proxy**，
  靠后端 `CorsConfig` 放行跨域 + `withCredentials`。

### 1.2 已经「像微服务」的部分（说明作者有意识，是加分项）

| 现状 | 对应微服务概念 |
|---|---|
| Java / Python 双语言，按能力切分进程 | 服务按能力边界划分 |
| `PictureIndexServiceImpl` 检索与索引**分离两套超时**（1s/2s vs 5s/180s） | 依赖隔离、快速失败 |
| `RedisCacheHelper` 熔断降级、`searchPictureIds` 失败降级为 LIKE | 服务降级 |
| `ChatAgentService` 区分 429/503「繁忙」与真故障 | 过载保护语义 |
| 图片列表缓存带**版本号失效**，多实例共享 | 分布式缓存一致性 |
| `@Async("pictureIndexExecutor")` 独立线程池 | 异步化、资源隔离 |

### 1.3 与微服务冲突 / 缺失的部分（改造要解决的真问题）

| # | 问题 | 证据 |
|---|---|---|
| 1 | 入口分散，无统一网关；每个服务各自配 CORS | `CorsConfig.java` + 前端硬编码 8123 |
| 2 | 无服务注册与发现，地址全靠 `@Value` 内联默认值 | `PictureIndexServiceImpl.java:54`（`pictureIndex.api.base-url` 只存在于注解默认值） |
| 3 | 限流是手写的，散在 Python 中间件与 Java 各自实现里 | `app/main.py` 在途计数中间件；`application.yml` 并发参数 |
| 4 | 同步 HTTP 调用长任务，靠线程池扛 | 索引走 180s 超时的 `RestTemplate` + `pictureIndexExecutor`（core 2 / max 4 / queue 200） |
| 5 | **批量抓图是同步 for 循环**，每张都触发一次**全量缓存失效** | `PictureServiceImpl.java:507-565` 循环调 `uploadPicture`，而 `uploadPicture` 在 `:240` 每次都 `pictureListCacheManager.invalidateAll()` |
| 6 | 索引失败没有真正的重试机制 | 只有 `indexStatus=2（失败）` 状态位，`rebuildIndex` 需人工触发 |
| 7 | 跨服务写一致性没有方案 | 删除图片 = 删 picture + 减空间配额 + 删相册引用 + 删 COS 文件 + 删向量索引 |
| 8 | **协同编辑房间是单实例内存态** | `EditRoomManager.java` 用 `ConcurrentHashMap<Long, EditRoom>` 存在本地堆里 |
| 9 | 配额检查在事务外、事务内不复检 | `PictureServiceImpl.java:143-155` 检查 / `:206-227` 修改（TOCTOU） |

> 第 5、6、8 条是本次改造最有价值的抓手：**它们本身就是 bug 或缺陷**，
> 用 MQ / 微服务解决它们是「被问题驱动」，而不是「为了用组件而用组件」。

---

## 二、技术选型评估

### 2.1 逐个结论

| 组件 | 结论 | 理由 | 在这个项目里解决什么 |
|---|---|---|---|
| **Nacos** | ✅ 合适 | 注册中心 + 配置中心二合一，省一个组件；Spring Cloud Alibaba 生态成熟 | 问题 2：替换 `@Value` 硬编码地址；配置集中管理（含 `pictureIndex.*`、并发参数、Sentinel 规则） |
| **OpenFeign** | ✅ 合适（有前提） | 声明式 HTTP 调用，和 Spring Cloud LoadBalancer 配合 | 服务间调用（ai-service、space-service）。**前提：绝不用在列表这类热读路径上**，见 §3.4 |
| **Sentinel** | ✅ 合适 | 流控/熔断/热点参数限流，规则可持久化到 Nacos | 问题 3：把 Python 中间件 + Java 手写限流**收敛成一套规则**；给 Agent/索引这类高延迟依赖配熔断 |
| **Gateway** | ✅ 合适（**优先级最高**） | Spring Cloud Gateway，支持 WebSocket 路由 | 问题 1：统一入口 `:9000 → /api/**`；统一鉴权 Filter、统一 CORS；顺带解决前端硬编码端口 |
| **Seata** | ⚠️ **需收敛范围** | 见 §5 专门讨论 | 问题 7：只有「删除图片」这一个真跨服务写场景值得用 |
| **RabbitMQ** | ✅ 合适 | 路由灵活（topic/fanout/DLX），本机部署成本低，延迟队列可做 | 问题 4/5/6/8 + 新增的通知、图片处理流水线、回收站 |

### 2.2 补充建议要加的（比 Seata 更划算）

| 建议 | 理由（性价比） |
|---|---|
| **Spring Cloud LoadBalancer** | 用 Feign 就绕不开，必配 |
| **Micrometer + Prometheus + Grafana** | 微服务后「出问题在哪一跳」是刚需。可观测性比 Seata 更能加分，且成本低 |
| **Docker Compose** | 改造后本机要跑 Nacos + RabbitMQ + 多个服务 + 多个库，手工起会重演「8001 跑旧代码」那类事故 |
| **链路追踪**（Micrometer Tracing / SkyWalking，可选） | 有 Feign + MQ 之后，traceId 贯穿是很好的谈资 |

### 2.3 关于 RabbitMQ 的坦白话

RabbitMQ 适合这个项目，但有两点要提前知道，面试容易被问：

1. **延迟队列不是原生的**。要么装 `rabbitmq_delayed_message_exchange` 插件，要么用
   「TTL + 死信交换机（DLX）」自己拼。回收站 30 天清理就是典型用例（见 §4.7）。
2. **顺序性弱于 RocketMQ / Kafka**。RabbitMQ 只能保证**单队列 + 单消费者**有序；
   要按业务键保序得靠「一致性哈希交换机」把同一 pictureId 路由到同一队列。
   如果简历想突出「事务消息」，RocketMQ 更顺手——但**不建议为此改选型**，
   RabbitMQ 的 DLX / 路由能力更贴合本项目的通知与重试场景。

> 一句话：**选 RabbitMQ 没问题，但你要能答出「延迟队列怎么实现」和「怎么保证顺序」。**

---

## 三、服务怎么拆

### 3.1 拆分原则（比拆成几个更重要）

1. **按业务能力与「写所有权」拆，不按技术分层拆**（不要拆成 `controller-service-dao` 三件套）。
2. **拆出来的服务要有独立的伸缩/生命周期/失败特征**——否则就是分布式单体。
3. **一张表只能有一个服务写**。其他服务要数据，只能通过接口或事件，不能直连库。
4. **先拆读少写多、依赖外部慢资源的**，最后才碰热读路径。

### 3.2 推荐方案：4 个 Java 业务服务 + 1 个网关 + Python 保持独立

| 服务 | 端口 | 负责的表 | 拆分理由 |
|---|---|---|---|
| **gateway** | 9000 | 无 | 统一入口、JWT 校验、CORS、限流。**收益最大、风险最小，第一个做** |
| **user-service** | 8124 | `user` | 认证是横切能力，被所有服务依赖；JWT 签发/校验收敛到一处 |
| **picture-service** | 8125 | `picture`, `album`, `album_picture` | 主业务，写最密集。**相册先不拆**（见 §3.3） |
| **space-service** | 8126 | `space`, `space_user` | 配额本质是「账本」，与图片写解耦后适合事件驱动；团队权限可独立演进 |
| **ai-service** | 8127 | 无（只读 picture 的 `indexStatus`） | 依赖外部模型，延迟秒级到几十秒，**失败特征与业务服务完全不同**，必须独立熔断限流 |
| **notification-service**（新增） | 8128 | `notification` | 纯 MQ 消费者、无同步写入口，天生异步；演示 fanout / DLQ / 延迟队列的最佳载体 |
| Python AI 能力层 | 8000/8001/8080 | Chroma | **保持现状**，理由见 §3.5 |

### 3.3 明确不拆的

| 不拆 | 理由 |
|---|---|
| **`album` / `album_picture`** | 相册只是图片的引用集合，校验逻辑要读 picture 的 `spaceId` 和归属，拆出去只会制造跨服务校验。为了「凑服务数」拆它是减分项 |
| **`PictureListCacheManager` / `RedisCacheHelper`** | 缓存是基础设施，不是业务服务。应下沉为公共 starter/依赖，而不是独立服务 |
| **协同编辑的 Disruptor 房间** | 它是单实例内存态（§6），不能当独立服务，但**多实例化后必须改造** |
| **`PictureIndexServiceImpl` 的短超时检索客户端** | 这是「依赖隔离」的正确实现，别在拆分时弄丢 |

### 3.4 拆分后最容易被问倒的三个真问题（必须提前想好）

**① 聚合查询：`PictureVO` 需要用户信息**

现状 `getPictureVOPage`（`PictureServiceImpl.java:402-426`）是**一次批量** `listByIds(userIdSet)`
——它已经是批量的，**不是 N+1**。但拆出 user-service 后，它会变成：
- 公共列表：走缓存，大部分请求不打库，风险可控；
- **私有空间列表：直接查库（不走缓存）**，于是每次请求 = 1 次 picture 查询 + **1 次跨服务 Feign 调用**。

这一跳会给最热的接口引入新的网络延迟和新的失败点。三个可选做法：

| 做法 | 说明 | 取舍 |
|---|---|---|
| A. **冗余用户快照**（推荐） | `picture` 表冗余 `userName` / `userAvatar`，改昵称时发事件异步回填 | 读路径零跨服务调用；代价是最终一致 + 回填逻辑 |
| B. 本地缓存用户信息 | Caffeine 缓存 `userId → UserVO`，短 TTL | 改动小；代价是短时不一致、缓存穿透要防 |
| C. 每次 Feign 聚合 | 最「标准」 | **不推荐用在热路径**，且 user-service 抖动会直接放大到列表接口 |

> 面试话术：**「我没有在热读路径上做服务间同步调用，而是用冗余快照 + 事件回填换掉它。」**
> 这句话比「我用了 Feign」值钱得多。

**② 跨服务写一致性** → 见 §5（Seata 决策）。

**③ 协同编辑房间跨实例** → 见 §6。

### 3.5 为什么**不**把 Python 服务强行纳入 Spring Cloud

- 把 FastAPI 注册进 Nacos 要用 `nacos-sdk-python` 手写心跳/注册，收益低；
- **Seata 无法跨语言**：Seata 的 AT/TCC 依赖 Java 侧的数据源代理，Python 的 Chroma 写入根本无法纳入全局事务；
- 更划算的做法：**由 `ai-service` 作为防腐层（ACL）** 统一封装对 Python 的调用——
  它负责超时、重试、熔断、降级（现在的 `PictureIndexServiceImpl` 已经做了一半），
  Python 侧只需要保持稳定的 HTTP 契约。

> 这样既保住了「Java + Python 双语言」这个最大卖点，又不用为跨语言一致性付学费。

---

## 四、消息队列用在哪

**排序原则：先做「现有代码已经有痛点」的，再做「新增功能」。**
前者有真实证据可讲，后者容易被追问「不用 MQ 行不行」。

### 4.1 交换机 / 队列 / 路由键总览

| 交换机 | 类型 | 队列 | 路由键 | 生产者 → 消费者 | 用途 |
|---|---|---|---|---|---|
| `picture.exchange` | topic | `picture.index.queue` | `picture.index.requested` | picture-service → ai-service | 向量索引异步化（**替代现在的 180s 同步 HTTP**） |
| `picture.exchange` | topic | `picture.process.queue` | `picture.process.requested` | picture-service → picture-service(worker) | 转码 / 缩略图 / EXIF / 机审 流水线 |
| `picture.exchange` | topic | `picture.changed.queue` | `picture.changed` | picture-service → 所有实例 | **L1 本地缓存失效广播** |
| `audit.exchange` | topic | `audit.review.queue` | `audit.requested` | picture-service → ai-service | 机审（Qwen-VL / 内容安全） |
| `audit.exchange` | topic | `audit.result.queue` | `audit.result` | ai-service → picture-service + notification | 审核结果回写 + 通知 |
| `quota.exchange` | topic | `quota.change.queue` | `quota.changed` | picture-service → space-service | **空间配额最终一致（替代同步 setSql）** |
| `notify.exchange` | fanout | `notify.inbox.queue`, `notify.mail.queue` | 忽略 | 多服务 → notification-service | 通知扇出 |
| `recycle.exchange` | direct | `recycle.delay.queue` →（DLX）→ `recycle.queue` | `recycle.delay` / `recycle.delete` | picture-service → picture-service | **回收站 30 天延迟清理**（TTL + DLX） |
| 各业务交换机 | — | `*.dlq` | — | 失败消息 | 死信 + 人工/定时重试 |

### 4.2 【优先级最高】向量索引异步化

**现状痛点（有代码证据）**：Java 通过同步 HTTP 调 8001 建索引，读超时给到 **180 秒**
（`PictureIndexServiceImpl.java:101-104`），靠 `pictureIndexExecutor`（core 2 / max 4 / queue 200）扛。
批量抓图 30 张时会往这个池子里塞 30 个长任务；失败只落一个 `indexStatus=2`，**没有重试**。

**改造**：
- `picture-service` 在图片达到「已过审」后，只投递一条 `picture.index.requested`（含 pictureId、url、spaceId、userId），**立即返回**；
- `ai-service` 消费并调用 8001，成功/失败后投递结果事件回写 `indexStatus`；
- 配 `prefetch=1~2`、手动 ack、失败进 `picture.index.dlq`，由定时任务或人工重放。

**收益**：响应时间从「秒级到几十秒」变成毫秒级；削峰；可重试；索引服务宕机不再占用业务线程。
**顺带修掉**：`indexStatus=2` 的自动重试（`rebuildIndex` 目前要人工触发）。

### 4.3 【优先级最高】批量抓图 / 批量上传削峰

**现状痛点（有代码证据）**：`uploadPictureByBatch`（`PictureServiceImpl.java:507-565`）
在一个 HTTP 请求里同步循环，最多 30 张，逐张 `uploadPicture`；而 `uploadPicture` 在 `:240`
**每张都调用一次 `invalidateAll()`** —— 即一次批量操作触发 30 次全量缓存失效 + 30 次 COS 上传。

**改造**：
- 接口改成「提交任务」语义：写一条批量任务记录，投递 30 条 `picture.process.requested`（或一条批量消息由 worker 拆分），**立即返回 taskId**；
- 前端轮询/SSE 查进度（现有 `PictureUploadByBatchRequest` 加个 taskId 即可）；
- **缓存失效改为「合并后广播一次」**：worker 处理完一批，只发一条 `picture.changed`。

**收益**：接口从「几十秒阻塞」变成「立即返回」；削峰；**顺便把 30 次缓存失效收敛成 1 次**。
**这条最适合写进简历**：它同时体现了「异步化」「削峰」「批处理优化」三个点。

### 4.4 图片处理流水线（新增功能，天然需要 MQ）

上传成功后异步做一串互相独立的事：

```
picture.process.requested
   ├─ 生成缩略图 / webp 转码        （CPU 密集，可多 worker）
   ├─ 提取 EXIF / 主色调 / 宽高比    （CPU 密集）
   ├─ 向量索引                      （转发 picture.index.requested）
   └─ 内容安全机审                  （转发 audit.requested）
```

**为什么适合 MQ**：四个消费者的耗时、资源类型、失败重试策略都不同（CPU vs 外部 API）；
用工作队列并行 + 各自 prefetch/重试，比现在「一个线程池里塞不同性质的任务」合理得多。

### 4.5 缓存失效广播（微服务化后从「可选」变成「必需」）

**现状**：多实例一致性靠 Redis 里的**版本号**（`invalidateAll()` → `bumpVersion`），
本地 L1 只能靠 TTL（30s）自然过期；`RedisCacheHelper` 还把版本号本地缓存了 **3 秒**，
所以其他实例感知延迟最坏是这个量级。单机时够用。

**改造**：写操作发 `picture.changed`（fanout），各实例收到后：
- 立即 `localCache.invalidateAll()`；
- 版本号仍留在 Redis 作为兜底（MQ 丢消息时不至于永久不一致）。

**收益**：把「最坏 3 秒 + L1 30s TTL」的不一致窗口压到毫秒级；
且**这是「不做 MQ 就不对」的场景**，面试时最好解释。

### 4.6 空间配额最终一致（这条会引出 Seata 的取舍）

**现状**：上传/删除时在同一个 `TransactionTemplate` 里用
`setSql("totalSize = totalSize + Δ")` / `totalCount = totalCount + Δ` 更新 `space`。
而且**检查在事务外、事务内不复检**（`:143-155` 检查 vs `:206-227` 修改，TOCTOU 超配额）。

**拆出 space-service 后**，配额变化变成跨服务写，两条路：

| 方案 | 说明 | 代价 |
|---|---|---|
| **A. MQ 最终一致（推荐）** | picture-service 写本地消息表（事务内），异步投递 `quota.changed`；space-service 幂等消费 | 短暂不一致；需要幂等 + 对账 |
| B. Seata AT 全局事务 | 强一致 | undo_log、全局锁、性能损耗；**且覆盖不到 COS / Python** |

**推荐 A**，理由：
1. 配额是「统计值」，短暂偏差不影响正确性，**不需要强一致**；
2. 它天然可对账（`SUM(picSize)` vs `totalSize`），这对账逻辑本身就是很好的简历素材；
3. 同时把 TOCTOU 问题一起解决——改成事件驱动后，用「先扣减、条件不满足则补偿」
   或在 space-service 侧用**条件 UPDATE**（`WHERE totalCount < maxCount`）原子校验。

---

### 4.2 / 4.6 合并实施计划（阶段 5 + 4c，2026-10-07 制定）

**先纠正两条与文档原判断不符的实测事实**（否则计划会建立在错误前提上）：

| 文档原判断 | 实测事实 |
|---|---|
| 「索引同步 HTTP，上传响应要等几十秒」 | **已经是异步的**：`indexPictureAndFillAsync` 带 `@Async`，上传 HTTP 响应**不阻塞**。真正的痛点是 **180 秒的长任务在占线程池**（core 2 / max 4 / queue 200）——批量 30 张就往里塞 30 个长任务；`rebuildIndex` 更是 fannout 到同一个池子 |
| 「加 MQ 就能重试」 | `applyQuotaDelta` 是**纯增量 UPDATE，不自带幂等**：同一条 `quota.changed` 消费两次就扣两次。所以**消费去重是事件化的硬前提**，必须先有 |

**因此合并实施的分步**（仍是「每步独立可跑、独立回滚」）：

| 步骤 | 内容 | 验收 | 风险 |
|---|---|---|---|
| **5a** | MQ 地基 + 通用幂等消费：`spring-boot-starter-amqp`、exchange/queue/DLQ 声明、`consumed_message` 去重表、一条**故意空转的测试队列** | 能 publish/consume；**同一条消息重复投递只被处理一次** | 低（纯增量，不碰业务） |
| **5b** | 索引写路径切 MQ：`indexPictureAsync` / `indexPictureAndFillAsync` 从「提交线程池」改为「发一条 `picture.index.requested`」，单体消费并调 8001 | 上传立即返回；索引进队列；`index_status` 仍正确；线程池长任务归零 | 中 |
| **5c** | 可靠性：**本地消息表**（事务内落库）+ 定时任务补投 + DLQ + 重放接口 + 对账（找出 `reviewStatus=通过 但 index_status` 空的图） | 关掉 RabbitMQ 再上传：上传照常成功、消息**不丢**（恢复后自动补投）；失败消息可一键重放 | 中 |
| **5d** | 配额事件化（4c）：`updateSpaceQuota` 改为写本地消息表 + 发 `quota.changed`，space-service 幂等消费；**条件 UPDATE** 解决 TOCTOU | 上传/删除后配额最终一致；重复投递不会重复扣减；对账脚本能报告并修正偏差 | **较高**（动的是数据一致性） |

**5d 的两个关键设计**：
- **幂等**：`quota.changed` 带全局唯一 `messageId`，space-service 用 `consumed_message`
  去重（与 5a 同一套机制，不重复造）；
- **TOCTOU**：把「先查额度再写图片」改成 space-service 侧
  `UPDATE space SET totalCount = totalCount + 1 WHERE id = ? AND totalCount < maxCount`，
  受影响行数为 0 即超限 —— 原子校验，不再有检查与写入之间的窗口。

**刻意不做**：阶段 5 不新建 `picture-ai-service`。§4.2 里写的 `ai-service` 目前并不存在
（8001 是 Python 脚本 `langgraph/run_index.py`），容器化拆分留到阶段 6 与处理流水线一起做。

### 4.7 回收站 + 延迟队列（新增功能）

- 删除图片 → 标记 `isDelete=1` + 发 `recycle.delay`（TTL 30 天，队列无消费者，到期进 DLX）→ 到期消息进 `recycle.queue` → 真正清理 COS 文件 + 删向量索引 + 广播缓存失效；
- 期间可「恢复」：把 `isDelete` 改回 0 即可（因为真删被推迟了）。

**为什么适合 MQ**：延迟队列是这个需求的教科书场景；也顺手让「删除」变成可撤销的，是产品上的加分。

### 4.8 通知服务（新增功能）

事件源：空间邀请/拒绝、审核通过/拒绝、协同编辑邀请、批量任务完成、回收站即将清理。

```
notify.exchange (fanout)
   ├─ notify.inbox.queue   → 站内信落库
   └─ notify.mail.queue    → 邮件（可再加消费失败的 DLQ）
```

**为什么适合 MQ**：典型 fanout + 无返回值 + 允许延迟；而且它是**纯消费者服务**，
形态上和业务服务完全不同，作为「第 5 个服务」的理由比拆相册充分得多。

### 4.9 消息可靠性：三件事必须说清楚

| 环节 | 做法 |
|---|---|
| **不丢（生产端）** | publisher confirm + 持久化交换机/队列/消息；配 `mandatory` + 返回回调 |
| **不丢（消费端）** | **手动 ack**（不要 autoAck）；业务成功后再 ack；失败 `nack + requeue=false` 进 DLQ |
| **不重复** | **消费幂等**：消息带唯一 `msgId`，消费端用「业务幂等键 + 去重表/Redis SETNX」拦截。业务幂等键举例：`pictureId + action`、`quota:pictureId` |
| **顺序性** | 需要有序的场景（同一图片的配额增减）用**单队列单消费者**，或一致性哈希按 `pictureId` 路由 |
| **事务边界** | 「本地事务 + 本地消息表（事务性发件箱）」解决「业务写成功但消息没发出去」 |

> 这四条是面试必问，**方案里写清楚比代码写出来还重要**。

---

## 五、Seata 到底要不要用（重点决策）

### 5.1 先找「真的需要分布式事务」的场景

把服务拆开后，**唯一真正的跨服务写**是：

> **删除图片** = picture-service 删 `picture` + space-service 减配额 + picture-service 删相册引用
> + 删 COS 文件 + ai-service 删向量索引

其他场景（上传、编辑、审核）主写都落在 picture-service 单库内。
**注意：COS 文件和向量索引本来就不在事务里**（现在是 `@Async` 清理 + 异步删索引），
所以就算上了 Seata，**也只能覆盖 2 个数据库事务，剩下两个还是要靠补偿**。

### 5.2 三个选项对比

| 方案 | 适用性 | 代价 | 结论 |
|---|---|---|---|
| **Seata AT** | 自动回滚，对「减配额」这种单行 UPDATE 收益低 | 每个库要 `undo_log` 表；全局锁降低并发；**跨不了 Python / COS** | ❌ 不推荐做主方案 |
| **Seata Saga** | 显式定义正向 + 补偿动作，适合「上传→处理→索引→审核」这类长流程 | 要自己写补偿逻辑；无隔离性 | ⚠️ 想用 Seata 就用它，比 AT 贴合 |
| **本地消息表 + MQ 最终一致** | 配额、索引、通知、缓存失效全都适用 | 需要幂等 + 对账 + 可观测 | ✅ **推荐主方案** |

### 5.3 我的建议

**主链路用「本地消息表 + MQ 最终一致」，Seata 只作为可选加分项，且若要用就用于
「删除图片」这一个场景、并选 Saga 模式。**

理由：
1. 这个项目的分布式写**大多是「不要求强一致」的派生数据**（配额统计、索引、通知、缓存）——
   用 Seata 是用力过猛；
2. **Seata 覆盖不到一半的参与者**（COS、Python），强上会得到「看起来有全局事务、
   实际仍有不一致」的尴尬结论，面试官一问「COS 删除失败了怎么办」就露馅；
3. 反过来，**如果你能讲清「为什么这里不用 Seata」**，这比「我用了 Seata」更能体现判断力。

### 5.4 如果还是想写 Seata（简历导向的折中）

那就把它绑在**一个能自圆其说的场景**上，并把边界写清楚：

> 「删除图片涉及 picture 与 space 两个库，我用 Seata Saga 串起正向与补偿；
> COS 文件与向量索引不在事务内，用**本地消息表 + 幂等重试 + 每日对账**兜底。
> AT 模式我没选，因为这里只有一个单行 UPDATE，用 AT 要付全局锁和 undo_log 的代价，
> 而且它跨不了 Python。」

这段话在面试里是**加分**的；反之「我用了 Seata AT 做分布式事务」大概率被追问到崩。

---

## 六、隐藏难点：协同编辑在微服务/多实例下会坏

这条容易被忽略，但**一旦多实例部署就会出正确性 bug**，必须列入方案。

**现状**：`EditRoomManager` 把 `Map<Long pictureId, EditRoom>` 放在**本地堆内存**
（`EditRoomManager.java:42`），编辑权用 `AtomicReference` CAS 抢占。
这意味着：
- 用户 A 连到实例 1、用户 B 连到实例 2 → **两人各自抢到「编辑权」，同时编辑同一张图**；
- 房间的 `participants` 列表、`operationHistory`（后加入者用来追平画布的回放）都只在单实例内可见；
- Disruptor 只保证**单实例内**消息串行。

**改造方案**：

```
浏览器 ──WS──► gateway（支持 WS 路由，按 pictureId 一致性哈希）
                    │
                    ▼
              picture-service（多个实例）
                    │ 本地房间只存「本实例的连接」
                    ▼
              Redis Pub/Sub 或 RabbitMQ fanout（编辑事件广播）
```

要点：
1. **网关做 WS 路由**，尽量把同一 `pictureId` 的连接的粘到同一实例（减少广播量，但**不能只依赖粘性**）；
2. 编辑权改为**分布式锁**（Redis `SET NX PX` + 续期），或「以 Redis 中的 owner 为准」，本地 CAS 只作快速失败；
3. 操作历史回放改为**共享存储**（Redis List / 或从已有的事件流重建）；
4. 广播用 **Redis Pub/Sub（低延迟）或 MQ fanout（可靠）**——这里要注意取舍：
   Pub/Sub 不保证送达但延迟低，适合协同编辑这种「丢一帧也能靠后续操作纠正」的场景。

> 这一条是很强的简历素材：**「微服务化暴露了原本单实例内存态的设计缺陷，我用分布式锁 +
> 事件广播解决了它，并论证了为什么这里选 Pub/Sub 而不是 MQ。」**

---

## 七、分阶段实施顺序

原则：**每个阶段都能独立跑通、独立演示、独立回滚**；全程保持单体可运行。

| 阶段 | 内容 | 验收标准 | 风险 |
|---|---|---|---|
| **0. 环境可复现** | Docker Compose 起 MySQL / Redis / RabbitMQ / Nacos；用上 `application.yml.example`；**先把当前未提交的并发改动提交** | `docker compose up` 后能一键起后端 + 前端 | 低 |
| **1. 网关** | Gateway 统一入口，路由 `/api/**` → 现有单体；统一 CORS；JWT 校验 Filter | 前端只改 `baseURL` 就能跑，功能不回退 | **低（收益最大）** |
| **2. 注册与配置** | Nacos 注册 + 配置中心；把 `pictureIndex.*`、并发参数、Sentinel 规则挪进 Nacos | 改配置不用重启；不再有硬编码地址 | 低 |
| **3. 拆 user-service** | 用户/认证独立，JWT 签发收敛；picture-service 用**本地缓存**拿用户信息（§3.4 方案 B） | 登录/注册/权限不回退 | 中（热路径耦合） |
| **4. 拆 space-service** | 空间/成员/配额独立；配额先**保持同步 Feign**，跑通后再切事件 | 空间与成员功能不回退 | 中 |
| **5. MQ 落地（索引异步化）** | 引入 RabbitMQ；§4.2 | 上传后立即返回；索引异步完成；失败可重放 | 中 |
| **6. MQ 新功能** | 批量任务化（§4.3）→ 处理流水线（§4.4）→ 缓存广播（§4.5）→ 配额事件化（§4.6）→ 通知（§4.8）→ 回收站（§4.7） | 每项独立可演示 | 中 |
| **7. Sentinel** | 收敛限流；给 ai-service 配熔断降级；规则持久化到 Nacos | 压测可见 429 来自 Sentinel 而非超时 | 低 |
| **8. Seata（可选）** | 仅「删除图片」场景，Saga 模式 | 补偿路径可演示（人为让 space-service 失败） | **高** |
| **9. 可观测性** | Micrometer + Prometheus + Grafana；traceId 贯穿 HTTP 与 MQ | 能定位「哪一跳慢/失败」 | 低 |

**顺序理由**：先做「收益大、风险小」的（网关、Nacos），再做「有真实痛点」的（MQ），
最后做「成本高、争议大」的（Seata）。**如果时间有限，做完阶段 0-5 就已经是一个完整可讲的架构故事。**

---

## 八、明确不做什么（防止过度设计）

| 不做 | 原因 |
|---|---|
| 每个服务一个独立数据库实例 | 单机资源扛不住，且跨库查询会先把你拖垮。**先用同一 MySQL 的不同 schema，服务间不直连对方的表**，等有真实扩容需求再物理拆库 |
| 上 Kubernetes | 简历写 K8s 但讲不出生产问题，反而扣分。**Docker Compose 够用** |
| 为了凑服务数把 `album` 拆出去 | 见 §3.3，属于分布式单体 |
| CQRS / 事件溯源 | 这个项目没有读写严重失衡到需要它的程度 |
| 把 Python 强行注册进 Nacos / 用 Seata 跨语言 | 见 §3.5，成本高收益低 |
| 一次性把所有同步调用都改异步 | **凡是有返回值的查询都不能异步**。MQ 只用在「写后派生」和「通知」上 |
| 引入 Kafka | 当前吞吐远没到需要 Kafka 的量级；RabbitMQ 的路由能力更贴合 |

---

## 九、简历怎么写 + 面试追问预演

### 9.1 简历条目（建议写法）

> **微服务化改造（Nacos + Gateway + OpenFeign + Sentinel + RabbitMQ）**
> - 以 Spring Cloud Gateway 收敛入口，统一鉴权与 CORS，替换前端硬编码多端口直连；
>   服务注册与配置下沉 Nacos，消除 `@Value` 硬编码地址
> - 按「写所有权」拆分 user / picture / space / ai / notification 五个服务；
>   针对聚合读路径**刻意避免服务间同步调用**，改用用户信息冗余快照 + 事件回填
> - 引入 RabbitMQ 解决三处真实缺陷：图片向量索引由 **180s 同步 HTTP 改为事件驱动**
>   （响应从秒级降到毫秒级、失败可重放）；批量抓图由**同步循环 30 张**改为任务化削峰，
>   并把**30 次全量缓存失效收敛为 1 次**；缓存失效由 TTL 被动过期改为事件广播
> - 跨服务一致性用**本地消息表 + 幂等消费 + 每日对账**替代强一致方案，
>   并论证了 Seata 在此场景的边界（跨不了 Python/COS，且配额本身不需要强一致）
> - 修复微服务化暴露的存量缺陷：协同编辑房间原为单实例内存态，
>   改用**分布式锁 + 事件广播**支持多实例

### 9.2 高频追问预演

| 追问 | 答案要点 |
|---|---|
| **为什么要微服务？单体不好吗？** | 别说「为了简历」。说具体的：入口分散导致 CORS/鉴权重复；AI 依赖的延迟特征与业务完全不同，需要独立熔断；同步长任务挤占业务线程。**能说出「哪些不该拆」比拆了几个更重要** |
| **为什么不用 Seata？** | 见 §5.3 三条理由。核心：**参与者里有非 Java 的 COS/Python，强一致覆盖不全，而配额是派生统计值** |
| **拆了服务，列表接口的用户信息怎么办？** | §3.4 方案 A：冗余快照 + 事件回填，**不在热路径做同步 Feign** |
| **消息丢了怎么办？重复消费怎么办？** | §4.9 三件事：confirm + 手动 ack + 持久化；幂等键 + 去重表；本地消息表解决「写库成功但没发出去」 |
| **怎么保证顺序？** | 单队列单消费者，或一致性哈希按 `pictureId` 路由；并说明哪些场景**不需要**顺序 |
| **为什么 RabbitMQ 不用 Kafka/RocketMQ？** | 量级不需要 Kafka；需要灵活路由 + DLX + 延迟队列；并主动说出 RabbitMQ 的两个短板（延迟队列要插件/TTL+DLX、顺序性弱） |
| **分布式锁怎么实现？锁过期了业务没做完怎么办？** | Redis `SET NX PX` + 唯一 value 校验删除 + **看门狗续期**；对协同编辑场景说明「用 owner 任期 + 版本号」防止旧 owner 写入 |
| **数据一致性怎么验证？** | 每日对账任务：`SUM(picSize)` vs `space.totalSize`，差异报警；幂等去重表的命中计数 |
| **服务雪崩怎么防？** | Sentinel 流控/熔断 + Feign 短超时 + 线程池隔离（ai-service 独立）+ 降级（语义检索 → LIKE 已有现成实现） |
| **改造成本多大？值得吗？** | 给出阶段划分与「做到阶段 5 就算完整」的取舍，体现**控制范围的能力** |

---

## 十、成本与风险

| 维度 | 说明 |
|---|---|
| **本机资源** | 需同时运行：MySQL + Redis + RabbitMQ + Nacos + Seata TC（可选）+ 5 个 Java 服务 + 3 个 Python 服务。**建议 Compose 编排 + 限制各服务 `-Xmx`** |
| **运维复杂度** | 原来「4 个进程手工起」已经出过事故（8001 跑旧代码、用错解释器）。多服务后**没有统一启动入口必然再犯**——阶段 0 必须先解决 |
| **调试难度** | 一次请求跨 3~4 个服务 + 1 个 MQ，**没有 traceId 会非常痛苦**，所以可观测性建议提前到阶段 5 之后尽快做 |
| **一致性风险** | 最终一致引入的窗口期需要在 UI 上可接受（例如配额数字短暂偏差） |
| **项目可运行性** | **最大的风险是「改造到一半，演示不了了」**。必须：开分支、每阶段可回滚、单体始终可运行 |
| **现有环境阻塞** | Redis 目前指向 `192.168.3.128:6379`（局域网虚拟机，当前不可达）。改造前建议先在本机 Compose 起一个 Redis，否则连现有功能都跑不完整 |
| **工作量粗估** | 阶段 0-2：约 2-3 天；阶段 3-4：约 3-5 天；阶段 5-6：约 5-8 天；阶段 7-9：约 3-5 天。**Seata（阶段 8）单独算，且最容易反复** |

---

## 十一、一页速览

```
必须做（收益大 / 风险低）
  Gateway 统一入口          ← 立刻解决 CORS、鉴权、硬编码端口
  Nacos 注册 + 配置         ← 干掉 @Value 硬编码
  RabbitMQ：索引异步化       ← 现有 180s 同步 HTTP 是真实缺陷
  RabbitMQ：批量抓图任务化   ← 现有同步 30 张 + 30 次缓存失效是真实缺陷

应该做（有真实痛点）
  缓存失效广播、图片处理流水线、配额最终一致、通知服务、回收站延迟队列
  协同编辑多实例化（分布式锁 + 事件广播）   ← 不同步解决就是正确性 bug

谨慎做（成本高 / 争议大）
  Seata                    ← 只在「删除图片」用，且选 Saga；主方案用本地消息表
  拆 album                 ← 不建议

明确不做
  独立数据库、K8s、CQRS、Kafka、Python 注册 Nacos
```

---

## 附：与仓库内其它文档的关系

| 文档 | 关系 |
|---|---|
| `docs/agent-review.md` | Agent 与 Qwen 接入评审，本文承接其「工程化外壳缺失」的结论 |
| `docs/agent-concurrency-plan.md` | 并发改进方案（限流/降级/缓存），是本文阶段 2、7 的前置 |
| `docs/resume-project.md` | 简历材料。**建议在完成本文阶段 0-5 后，把 §9.1 的条目合并进去** |
| `picture-backend/src/main/resources/application.yml.example` | 阶段 0 的前提：环境配置模板 |
