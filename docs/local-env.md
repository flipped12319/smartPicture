# 本机环境说明（阶段 0 + 阶段 1）

> 目标：让「clone 下来 → 起依赖 → 起服务」这条路可复现，不再依赖各人机器上的既有环境。
> 本文对应改造方案里的**阶段 0（环境可复现）**与**阶段 1（统一网关）**。

---

## 零、日常启动（照着做就行）

**顺序不能乱**：后面的都依赖前面的。一共 8 步。

| 步 | 启动什么 | 怎么启动 | 是否必须 |
|---|---|---|---|
| 1 | **MySQL** | 已装成 Windows 服务（服务名 `MySQL80`），开机自启，通常不用管 | **必须** |
| 2 | **Redis** | 在项目根目录执行 `docker compose up -d`；或者把你局域网那台 `192.168.3.128` 开着（后端现在连的是它） | 建议（不启动也能跑，只是没有缓存） |
| 3 | **Java 后端**（8123） | **双击项目根目录的 `run-backend.cmd`** | **必须** |
| 4 | **用户服务**（8126） | **双击 `run-user-service.cmd`** | **必须** —— 登录/注册/用户管理都在这里，不启动就登不进去 |
| 5 | **空间服务**（8130） | **双击 `run-space-service.cmd`** | **必须** —— 空间/团队成员/配额都在这里，不启动则空间相关页面和**图片的空间权限校验**都会失败 |
| 6 | **网关**（9000） | **双击 `run-gateway.cmd`** | **必须**（前端默认走它） |
| 7 | **前端**（5173） | 命令行：`cd picture-frontend` 然后 `npm run dev` | **必须** |
| 8 | **Python 三个服务** | 见下方代码块 | 只影响 AI 功能（智能助手、以文搜图、自动补全） |

> **第 3、4、5、6 步各开一个窗口双击脚本即可，不用装 Maven。**
> 四个脚本会：① 自动找到本机的 JDK 11；② 用工程自带的 Maven Wrapper（`mvnw`）
> 启动服务 —— 首次运行会自动下载一份 Maven 到 `~/.m2/wrapper`，之后不再下载。
>
> ⚠️ **仍然需要 JDK 11**（脚本只免掉了 Maven，不免 JDK）。
> 因为 Spring Boot 2.7.6 带的 Lombok 1.18.24 在 JDK 21 下编译会直接失败。
> 脚本会按顺序在常见位置找 JDK 11，找不到会给出明确提示（当前机器上是
> `C:\Users\HUAWEI\.jdks\ms-11.0.31`，能直接找到）。
>
> 也可以不用脚本、手动在各自目录执行 `mvnw spring-boot:run`（Windows 下是 `mvnw.cmd`）。
> 脚本只是帮你把 JDK 11 和目录切换这两件事做掉了。

> **阶段 3b 起多了一个必须启动的服务**：`picture-user-service`（8126）。
> user 表归它所有，登录、注册、用户管理都由它提供；单体不再读写 user 表。
> 网关把 `/api/user/**` 转给它，其它 `/api/**` 转给单体。
> 所以**第 3、4 步要一起起**：只起单体不起用户服务，登录会失败。
>
> **阶段 4b 起又多了 `picture-space-service`（8130）。**
> `space` 与 `space_user` 两张表归它所有，空间 CRUD、团队成员、配额增减都由它提供；
> **网关把 `/api/space/**` 与 `/api/spaceUser/**` 直接转给它**（不经过单体）。
> 单体只保留三件还需要空间数据的事：图片热路径的空间权限校验、相册要用的私人空间 id、
> 以及图片上传/删除时的配额增减（经 OpenFeign 调它）。
> 所以**第 3、4、5 步必须一起起**：
> - 不起用户服务 → 登录失败，空间服务的成员列表也拿不到昵称；
> - 不起空间服务 → 空间/成员页面全部 404，**图片列表与上传也会失败**
>   （单体查不到空间就无法校验权限——这是刻意设计的「鉴权路径失败即明确报错」）。

第 8 步的三个服务，**必须先进到 `langgraph` 目录**再启动：

```bash
conda activate langchain_learn     # 依赖都装在这个环境里
cd langgraph
python run_agent.py                # 8000 智能助手
python run_index.py                # 8001 向量索引 / 语义检索
python run_qwen.py                 # 8080 Qwen-VL 多模态
```

全部起完后，浏览器打开：**<http://localhost:5173>**

> **关于 `.cmd` 脚本为什么全是英文**：`cmd.exe` 按系统代码页（中文 Windows 是 936）
> 解析批处理文件，UTF-8 的中文会被打乱、碎片还会被当成命令执行，脚本会莫名其妙报
> `'xxx' is not recognized as an internal or external command`。
> 所以这几个脚本刻意只用 ASCII，中文说明就放在这里。
>
> **顺带修掉的一个坑**：`picture-backend/pom.xml` 里的 `<skip>true</skip>` 原先写在**插件级**
> 配置上，而插件级配置对所有 goal 生效，会把 `spring-boot:run` 一起跳过 ——
> 表现是命令跑完什么都没发生、也不报错。现已改成只跳过 `repackage`
> （保持「不打可执行 fat jar」的原行为），`spring-boot:run` 恢复正常。

### 怎么确认起好了

浏览器能打开就是最简单的方式。想更确定一点，命令行跑这一句（能返回 `total` 之类的数字，
就说明「前端 → 网关 → 后端 → 数据库」整条链路是通的）：

```bash
curl -s -X POST http://localhost:9000/api/list/page/vo -H "Content-Type: application/json" -d "{\"current\":1,\"pageSize\":3}"
```

### 关闭

大致按启动的**逆序**关：前端（Ctrl+C）→ 网关（Ctrl+C）→ 后端（IDEA 里 Stop）→
Python 三个（Ctrl+C）→ 依赖容器 `docker compose down`。
MySQL 和 Redis 可以一直开着，不用关。

### 常见问题

| 现象 | 原因与处理 |
|---|---|
| 启动报 `Port 8123 was already in use` | 后端已经在跑了（可能上次没关干净，或 IDEA 里还开着）。别重复启动 |
| 页面能打开但数据都加载失败 | 看是不是**网关没起**（9000）。前端默认走 9000。临时绕过：在 `picture-frontend/.env.local` 写 `VITE_API_BASE=http://localhost:8123` 然后重启 `npm run dev` |
| 智能助手/以文搜图报错 | 第 6 步的 Python 服务没起，或 `langgraph/.env` 里的模型密钥没配 |
| `docker compose up -d` 报镜像拉取失败 | 镜像加速器问题，见第二节末尾 |
| 后端日志出现 Redis 相关警告 | 连不上 Redis，会自动降级成「只查数据库」。功能可用、速度变慢 |

---

## 一、一张图看清端口

| 组件 | 端口 | 谁在用 | 说明 |
|---|---|---|---|
| **网关** | **9000** | 前端默认入口 | 阶段 1 新增，浏览器只需要认这一个地址 |
| Java 单体 | 8123 | 网关转发目标 | context-path `/api` |
| **用户服务** | **8126** | 阶段 3b 新增 | 登录/注册/用户管理；单体经 Feign 读用户 |
| **空间服务** | **8130** | 阶段 4 新增 | 空间/团队成员/配额；`space`、`space_user` 两张表的唯一属主 |
| Python Agent | 8000 | Java 调 | LangGraph 助手 |
| Python 索引 | 8001 | Java 调 | 向量索引 / 语义检索 |
| Python Qwen 网关 | 8080 | 同目录 Python 调 | 多模态调用 |
| 前端 dev server | 5173 | 浏览器 | Vite |
| MySQL（本机） | 3306 | 三个 Java 服务 | 你机器上已有的实例（**同一个库，不同表归不同服务**） |
| **MySQL（容器，可选）** | **3307** | 仅 `--profile mysql` | 刻意避开 3306 |
| Redis（容器） | 6379 | 单体 / 后续服务 | |
| RabbitMQ（容器） | 5672 / 15672 | 阶段 5 起 | 15672 是管理台 |
| Nacos（容器） | 8848 / 9848 | 阶段 2 起 | 8848 是控制台，9848 是 gRPC |

> **空间服务的端口为什么是 8130**：方案文档 §3.2 里预写的是 8126，
> 但那份表格写于拆分之前，8126 已被阶段 3b 的 `picture-user-service` 占用。

---

## 二、起依赖（阶段 0）

**前提**：Docker Desktop 已启动。

```bash
docker compose up -d          # 起 Redis + RabbitMQ + Nacos
docker compose ps             # 看状态
docker compose logs -f nacos  # Nacos 启动约 20~40 秒，用它确认
docker compose down           # 停止（数据保留在具名卷里）
```

访问入口：

- RabbitMQ 管理台 <http://localhost:15672> （凭据见 `.env.example`，默认 `admin` / `change-me-dev-only`）
- Nacos 控制台 <http://localhost:8848/nacos> （本机开发已关闭鉴权）

### 想用容器里的空 MySQL

本机 3306 已被占用，所以容器 MySQL 映射到 **3307**，而且**默认不启动**：

```bash
copy .env.example .env                                  # 按需修改里面的密码
docker compose --profile mysql up -d
```

首次启动会自动执行 `picture-backend/sql/00-schema.sql` 建好全部 6 张表。
之后把应用的数据库地址指到 3307 即可（见下节）。

> **为什么只挂 00-schema.sql**：`sql/album.sql`、`team_space.sql`、`picture_index.sql`
> 是给**老库做升级**的增量脚本，对空库执行会因为列已存在而报错。
> `00-schema.sql` 是从当前库导出的**最终结构**，包含全部表与全部列。

### ⚠️ 关于 Docker 镜像加速器

本机 Docker 配置的加速器 `docker.xuanyuan.me` 当前**不可用**（TLS 证书无效 + 部分请求 403），
而 `registry-1.docker.io` 直连也不通。若 `docker compose up -d` 报镜像拉取失败，有两种办法：

1. 修 Docker Desktop → Settings → Docker Engine 里的 `registry-mirrors`（换一个可用源）；
2. 或者用全限定地址绕过加速器（加速器只对 `docker.io` 生效），拉完重命名即可：

```bash
docker pull docker.m.daocloud.io/library/redis:7.2-alpine
docker tag  docker.m.daocloud.io/library/redis:7.2-alpine redis:7.2-alpine
# rabbitmq:3.13-management / nacos/nacos-server:v2.3.2 同理
```

---

## 三、起后端（阶段 1：走网关）

配置模板：`picture-backend/src/main/resources/application.yml.example`
（真实的 `application.yml` 含密钥，已被忽略、不入库）

```bash
copy application.yml.example application.yml     # 在 picture-backend/src/main/resources 下
```

密钥二选一：设环境变量 `DB_PASSWORD` / `JWT_SECRET`，或写进同样被忽略的 `application-local.yml`。

> ⚠️ **`jwt.secret` 是三个服务共用的签名密钥，绝不能写进被 git 跟踪的文件。**
> `picture-user-service` 用它签发，单体与 `picture-space-service` 用它校验 ——
> 泄露它等于任何人都能伪造 **admin** token 调所有内部接口。
> 本项目踩过一次：`tools/verify/GenToken.java` 曾把真实密钥硬编码成默认值，
> 随代码推上了 GitHub，只能轮换密钥并把那个默认值删掉（现在必须 `-Dsecret=` 显式传入）。
> 换密钥时三个服务的 `application.yml` 要一起改，否则症状是「登录成功但之后全部未登录」。

启动顺序：**MySQL → Redis → 单体(8123) → 网关(9000) → 前端**

```bash
# 单体
cd picture-backend && mvn spring-boot:run          # 注意：pom 里 skip=true，见下方说明

# 网关（独立工程）
cd picture-gateway && mvn spring-boot:run
```

> **已知问题**：`picture-backend/pom.xml` 给 `spring-boot-maven-plugin` 配了插件级
> `<skip>true</skip>`，会连带把 `spring-boot:run` 一起跳过，所以单体只能用
> 「IDEA 里跑」或 `java -cp target/classes;<依赖> 主类` 的方式启动。
> 网关工程没有这个问题，`mvn spring-boot:run` 正常可用。

---

## 四、前端指向网关

前端默认就指向网关（`picture-frontend/src/config.ts`）：

```ts
export const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:9000'
```

**回退到直连单体（不需要改代码）**：

```bash
# picture-frontend/.env.local
VITE_API_BASE=http://localhost:8123
```

改完重启 `npm run dev`。

---

## 五、网关做了什么 / 没做什么

**做了**：

| 能力 | 说明 |
|---|---|
| 统一入口 | `/api/**` → 单体 8123；**WebSocket `/api/ws/**` 单独一条路由**（uri scheme 必须是 `ws`） |
| 统一 CORS | 替代各服务各自配置；并去掉与单体重复的响应头 |
| traceId | 生成/透传 `X-Trace-Id`，回写响应头，并打访问日志 |
| 可观测入口 | `/actuator/health`、`/actuator/gateway/routes` |

**刻意没做**：**网关层鉴权**。单体的鉴权是「HTTP Session 为主 + 部分接口 Bearer 兜底」，
在网关重复一套路径级规则容易与单体不一致，还会挡住依赖 Session 的接口。
鉴权收敛放到阶段 3（user-service 成型、JWT 成为唯一凭据）之后。

### ⚠️ 一个必须记住的坑：`default-filters` 会打断 WebSocket

实测发现：把 `DedupeResponseHeader` 配在 `spring.cloud.gateway.default-filters` 下，
它会同时作用到 WebSocket 路由，**破坏升级成功后的回包链路** ——
表现为客户端收到 `101` 却收不到任何服务端消息，随后以 `1006` 异常断开；
而后端日志显示「房间已建、又立刻离开」。

**结论**：去重这类响应处理过滤器**只挂在 HTTP 路由的 `filters` 上**，
不要放进 `default-filters`。`application.yml` 里已按此配置并加了注释。

---

## 六、验证清单（改完自己跑一遍）

```bash
# 1. 依赖
docker exec sp-redis redis-cli ping                              # PONG
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:15672/ # 200
curl -s http://localhost:8848/nacos/v1/console/health/readiness   # OK

# 2. 网关自身
curl -s http://localhost:9000/actuator/health
curl -s http://localhost:9000/actuator/gateway/routes

# 3. 代理正确性：直连与经网关的响应体应完全一致
curl -s -X POST http://localhost:8123/api/list/page/vo -H 'Content-Type: application/json' -d '{"current":1,"pageSize":3}' > d.json
curl -s -X POST http://localhost:9000/api/list/page/vo -H 'Content-Type: application/json' -d '{"current":1,"pageSize":3}' > g.json
diff d.json g.json && echo "一致"

# 4. CORS：Allow-Origin 必须只出现一次，否则浏览器直接判跨域失败
curl -s -D - -o /dev/null -X OPTIONS http://localhost:9000/api/user/login \
  -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type' | grep -ci 'access-control-allow-origin'
```

**WebSocket 必须用真正的 WS 客户端测**（curl 不是 WS 客户端，它的耗时/状态码会误导）：
先登录取 `JSESSIONID`，再带 Cookie 连 `ws://localhost:9000/api/ws/picture/edit?pictureId=<id>`，
应当先 `open`、随即收到一条 `{"type":1,...}` 的 INFO 消息。
不带 Cookie 时应当收不到 INFO（后端握手阶段会拒绝）。

### 阶段 4 起要加的两条验证（拆分是否「真」）

```bash
# 5. 空间路径必须由 space-service 承接：直连单体应当 404
curl -s -o /dev/null -w "monolith=%{http_code}\n" http://localhost:8123/api/space/list/level   # 404
curl -s -o /dev/null -w "gateway =%{http_code}\n" http://localhost:9000/api/space/list/level   # 200

# 6. 决定性证据：space 表的查询只能出现在 space-service 的日志里
#    （单体日志里出现任何 FROM space / FROM space_user 就说明拆得不干净）
```

> 原理：阶段 4b 已经把单体的 `SpaceMapper` / `SpaceUserMapper` / `SpaceUserMapper.xml`
> 与两个空间 Controller **整体删除**，所以单体在结构上就没有访问这两张表的能力；
> 上面两条只是用来在运行期再确认一次路由与日志。
>
> 更完整的自动验证在 **`tools/verify/`**（阶段 4 的 85 + 41 项断言都在那里，
> 用法与两个「测试脚本自己的坑」见 `tools/verify/README.md`）。

---

## 七、回滚

阶段 0/1 的改动都在 `feature/microservice-phase01` 分支上，`main` 未动。

| 想回滚的范围 | 做法 |
|---|---|
| 只回退前端指向 | 在 `picture-frontend/.env.local` 设 `VITE_API_BASE=http://localhost:8123` |
| 只停网关 | 停掉 9000 进程即可；单体与前端（配上一条）照常工作 |
| 完全放弃阶段 0/1 | `git checkout main` —— 分支上的提交不会丢，`main` 回到动手前的状态 |
| 回退某一个提交 | `git revert <sha>` |
| 删掉容器与数据 | `docker compose down -v` |

阶段 1 是**纯增量**的：新增 `picture-gateway/` 独立工程，**没有改动单体任何代码**，
所以回滚不需要动 `picture-backend`。

---

## 八、阶段 2：Nacos 服务发现与配置中心

### 怎么用

**默认形态不依赖 Nacos** —— 单体与网关都照常按第七节的方式启动，Nacos 没起也没关系。
要用服务发现，只加一个参数：

```bash
cd picture-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=nacos
# 或 java -jar picture-gateway.jar --spring.profiles.active=nacos
```

然后在 Nacos 控制台（<http://localhost:8848/nacos>）就能看到
`picture-backend` 与 `picture-gateway` 两个服务。想要两个单体实例做负载均衡，
就用不同端口多起一个：

```bash
cd picture-backend
java -cp "target/classes;<依赖>" com.flipped.picturebackend.PictureBackendApplication --server.port=8127
```

（`<依赖>` 用 `mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt` 生成，
文件内容即 classpath。）

### 配置中心里放了什么

DataId = `picture-backend.yml`，仓库里的源文件是 **`deploy/nacos/picture-backend.yml`**
（Nacos 服务器上的内容以它为准）。内容是原先**只以 `@Value` 内联默认值存在、
在配置文件里看不到**的几项：

```yaml
pictureIndex:
  api:
    base-url: http://127.0.0.1:8001
  search:
    top-k: 100
    min-similarity: 0.48
```

发布 / 更新（新环境初始化时执行一次）：

```bash
curl -s -X POST http://localhost:8848/nacos/v1/cs/configs \
  -F "dataId=picture-backend.yml" -F "group=DEFAULT_GROUP" \
  -F "type=yaml" -F "content=<deploy/nacos/picture-backend.yml"
```

对应 Bean 加了 `@RefreshScope`，所以在 Nacos 控制台改完**不用重启**就能生效。
优先级：环境变量 > Nacos 远程配置 > 本地 `application.yml`。

### ⚠️ 三个已实测的坑

**1. Nacos 配置内容必须是纯 ASCII（最容易踩）**

Spring Cloud Alibaba 解析 Nacos 配置时用的是 **JVM 默认字符集**。中文 Windows 的
ANSI 代码页是 GB2312(936)，此时配置里只要有一个中文字符就会报：

```
YamlException: java.nio.charset.MalformedInputException: Input length = 1
```

**并且整份远程配置会被静默跳过**（不会启动失败，所以很难发现）。
解决办法就是让远程配置内容保持纯 ASCII，中文说明写在仓库文档里。
如果一定要写中文，则在启动参数里加 `-Dfile.encoding=UTF-8`（IDEA 默认就带）。

**2. 必须显式指定注册 IP**

本机有 WSL / VMware×2 / Hyper-V / 蓝牙 / WLAN 共 7 个网卡，Nacos 自动挑 IP 的结果不确定
（实测挑中了 Hyper-V 的 `192.168.137.1`）。一旦挑到不可达的网卡，网关的 `lb://` 就会转发失败。
已在配置里固定：

```yaml
spring.cloud.nacos.discovery.ip: ${NACOS_DISCOVERY_IP:127.0.0.1}
```

部署到多机时用环境变量覆盖成本机真实 IP。

**3. 多实例下 Session 登录会「时好时坏」（重要）**

实测：两个单体实例 + 网关轮询，带同一个 Cookie 连发 8 次，结果是 **4 次成功 / 4 次失败**，
失败时报 `40101 未登录`。

原因：登录态存在**单个实例的内存 Session** 里，请求被轮询到另一个实例就认不出来。
所以**当前架构不能水平扩容**，需要二选一：

- 短期：网关做会话粘滞（sticky session），或引入 Spring Session + Redis 共享会话；
- 正解：**阶段 3** 把 JWT 变成唯一凭据（JWT 是无状态的，天然支持多实例）。

同理，协同编辑的房间也在单个实例内存里，多实例下需要分布式锁 + 事件广播（见方案文档第六节）。

### 回滚

阶段 2 涉及单体改动（这是与阶段 1 最大的不同），改动面很小，共 3 处：

| 位置 | 内容 |
|---|---|
| `picture-backend/pom.xml` | 2 个版本号 + 2 个 nacos starter + 2 个 BOM 导入 |
| `picture-backend/.../application.yml` | `spring.cloud.nacos.*` 与 `spring.config.import` |
| `PictureIndexServiceImpl` | `@RefreshScope` 注解（1 行） |

回滚方式（任选）：

- 整体弃用：`git checkout main`（分支上的提交不会丢）；
- 只回退阶段 2：`git revert <阶段2的两个提交>`；
- 临时停用而不改代码：启动时加
  `--spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false`。

> 另外：Nacos 连不上时**不会阻塞启动**（已实测）——配置侧靠 `optional:` 前缀 +
> 客户端本地快照缓存，注册侧靠 `fail-fast: false`，只会打一条 WARN。

---

## 九、阶段 4：拆 space-service（空间 / 团队成员 / 配额）

### 怎么用

比阶段 3 多了一个必须启动的服务，顺序变成
**MySQL → Redis → 单体(8123) → 用户服务(8126) → 空间服务(8130) → 网关(9000) → 前端**。

```bash
# 空间服务（独立工程）
cd picture-space-service && mvnw.cmd spring-boot:run      # 或双击 run-space-service.cmd
```

启动后它自己会注册进 Nacos（服务名 `picture-space-service`）。单体经**静态地址**
调它（`space.api.base-url`，默认 `http://127.0.0.1:8130`），**不依赖 Nacos** ——
延续项目「本机不跑 Nacos 也能开发」的原则；把该配置置空则退化为按服务名走服务发现。

### 数据与路由的归属

| 表 / 路径 | 归谁 | 说明 |
|---|---|---|
| `space`、`space_user` | **space-service** | 唯一属主。单体已删除两个 Mapper 与两个 Controller |
| `/api/space/**`、`/api/spaceUser/**` | **space-service** | 网关直接转发（order=-2），**不经过单体** |
| `/api/user/**` | user-service | 阶段 3b |
| `/api/internal/**` | 无人路由 → 落到单体并 404 | 服务间调用直连端口，从网关走不进来 |

单体**仍然需要**空间数据的三件事（都走 OpenFeign + 本地 Caffeine 缓存）：

1. **图片热路径的空间权限校验** —— 图片列表/上传/编辑/删除都要判定用户在该空间的角色；
2. **相册要用的私人空间 id** —— `getSpaceIdByUserId`；
3. **配额增减** —— 上传/删除图片时的 `totalSize` / `totalCount`（**阶段 4 唯一的跨服务写**）。

### ⚠️ 一个必须记住的坑（阶段 4 实测踩到）

**「未登录」的判断顺序不能反。** 阶段 4b 一度把单体接口从
`checkSpaceUserAuth(space, User loginUser, role)` 改成接收 `Long loginUserId`，
于是调用点写成 `checkSpaceUserAuth(space, loginUser.getId(), role)` ——
未登录时 `loginUser` 是 null，**取 id 先抛了 NPE**，被全局异常处理器兜底成
`50000 系统错误`，而拆分前这里是明确的 `40100 未登录`。

教训与阶段 3 的 `null` 教训同源：**跨服务改造时，参数类型的调整会把「判空」从
被调方挪到调用方**；一旦挪错位置，「你没登录」这种一眼能看出的结论就会变成一个
查不出原因的 500。所以单体侧的接口刻意保留完整的 `User loginUser`，
在实现的第一行判空 —— 这样调用点一行都不用改，语义也不会漂移。

### ⚠️ 第二个坑：「空间不存在」其实经常是「空间服务连不上」

**现象**：建完团队空间、点进去看，页面报 `获取数据失败，空间不存在`。

**真实原因**：单体的 `getById` 查空间是**跨服务调用**。原来的实现把
「空间真的不存在」和「space-service 连不上 / 返回非 0」**都压成了 null**，
于是后面每一处 `throwIf(space == null, "空间不存在")` 都会把一次依赖抖动
报成「空间不存在」——按这个提示去查会发现空间明明在库里，排查方向直接跑偏。

**修法**（已修，提交见下）：按 `code` 区分两种「查不到」——
- `code=0 且 data=null` → 空间真的不存在，报 40400；
- 抛异常 / 返回非 0 → 报 `50000 空间服务暂不可用`，**绝不**说成「空间不存在」。

**下次再看到这个提示，按这个顺序查**：

```bash
# 1. 8130 在不在？不在就先起空间服务（run-space-service.cmd）
netstat -ano | findstr :8130

# 2. 空间是不是真的存在（绕过单体与网关，直连空间服务）
curl -s -H "X-Internal-Token: dev-internal-token" http://127.0.0.1:8130/api/internal/space/<空间id>
#    data 有内容 -> 空间在；data=null -> 这个 id 在库里真没有（或已被软删除）

# 3. 一键把三步都跑一遍并打印原始响应
powershell -ExecutionPolicy Bypass -File tools\verify\diagnose-space-view.ps1 -SpaceId <空间id>
```

### ⚠️ 第三个坑：新服务漏了「Long 以字符串下发」→ 前端拿到被四舍五入的 id

**现象**：创建完团队空间，点进去看：`获取数据失败，空间不存在` /
`获取空间详情失败，请求数据不存在`。可库里空间好好的，直接 curl 那个 id 也能查到。

**根因**：项目所有 id 都是雪花 id（19 位，约 2.1e18），而 **JavaScript 的 Number 只能精确到
2^53-1**（9007199254740991），一旦以 JSON **数字**下发，前端拿到的那一刻精度就丢了 ——
而且丢了之后**一切看起来都正常**（列表能显示、名字能显示），只有拿这个 id 去请求时才 404。

单体早就有 `JsonConfig`（`Long` → `ToStringSerializer`），所以单体自己的接口没这个问题；
但**阶段 3b 的 picture-user-service 与阶段 4 的 picture-space-service 都漏了这份配置**。
实测日志（空间服务）：

```
查询空间详情失败：空间不存在，id = 2107713929437249500   ← 真实 id 是 2107713929437249537
查询空间详情失败：空间不存在，id = 2100208978036293600   ← 真实 id 是 2100208978036293634
```

两个 id 都被抹成了 100 的整数倍 —— 这就是 `Number` 往返的指纹
（`Number(2107713929437249537)` → 2107713929437249536 → 打印成 `"2107713929437249500"`）。

**修法**：每个 Java 服务都必须有与单体一致的 `JsonConfig`（已给两个新服务补上）。
验证脚本里也加了断言（`verify-4a.ps1` 第 3 节）：**id 必须是带引号的字符串**。

**排查时怎么一眼认出来**：报错里的 id 与数据库里的 id **长度一样但末几位不同**，
且差异发生在百位/万位这种「整数倍」位置上 → 一定是 JS 精度丢失，不是数据被删。

### ⚠️ 第四个坑：`<img>`/`<a>` 发起的请求带不了 `Authorization` 头

**现象**：团队空间里点「编辑图片」，后端抛 `BusinessException: 未登录`
（`PictureController.proxyPicture` → `UserServiceImpl.getLoginUser`）。

**根因**：`/api/picture/proxy` 是给 **`<img src>`** 用的（绕过 COS 跨域，让 canvas 能导出），
而 `<img>` **只能带 Cookie、永远带不了 `Authorization` 头**（`crossorigin="use-credentials"`
也只管 Cookie）。阶段 3a 把登录态收敛成 JWT 之后，这个接口就再也拿不到凭据了 ——
公共图库的图片看起来正常（代码里 `spaceId != null` 才校验），一进团队空间就报未登录。

**修法**：前端把 token 放进查询参数（与协同编辑 WS 握手 `?token=` 同一套做法），
后端 `getLoginUser` 支持 `token` 查询参数：

```
/api/picture/proxy?id=<图片id>&token=<jwt>
```

**以后再加这类接口时的检查清单**：只要是「浏览器元素直接发起、不经过 axios」的请求
（`<img>` / `<a href>` / `<video>` / WS 握手 / `window.open`），
就**不可能**带上 `Authorization` 头，必须走查询参数或 Cookie。

### ⚠️ 第五个坑：`mvnw spring-boot:run` 的日志看不到应用输出

`mvnw spring-boot:run` 会 **fork 一个新 JVM** 跑应用，那个子进程的 stdout
**不会**进到父进程被重定向的文件里 —— 表现是「`> run.log` 里只有 Maven 的输出，
应用日志与 MyBatis 的 SQL 一条都没有」，很容易误判成「没有报错」。

需要抓日志时用仓库里准备好的配置（日志会**再**写一份到该模块目录下的 `app.log`）：

```bash
mvnw spring-boot:run -Dspring-boot.run.jvmArguments=-Dlogging.config=classpath:logback-file.xml
```

单体与空间服务都带了这份 `logback-file.xml`。

### 已知取舍：角色缓存的 60 秒窗口

单体的角色缓存（`SpaceCacheManager`）TTL 是 **60 秒**，且**不做跨服务失效通知**。
后果：成员被移除或角色被调低后，**本实例最多 60 秒内仍按旧角色放行**。

为什么接受它：角色查询在图片热路径上，不缓存等于给每个图片接口加一次网络往返；
而成员变更发生在 space-service 侧（前端经网关直接打它），要立刻失效就得让
space-service 反调单体的每个实例去清缓存，属于把小问题做大。
彻底解决留给阶段 6 的 `picture.changed` fanout（缓存失效广播）。
（空间对象的缓存也是 60 秒，但它的写路径都在单体自己手里，写完会主动清。）

### 回滚

阶段 4 拆成两个提交，**各自可独立回滚**：

| 提交 | 内容 | 回滚方式 |
|---|---|---|
| `0966893` | **4a** 新增 `picture-space-service` 独立工程，**单体一行未改** | `git revert 0966893` —— 那时还没有任何调用方，删掉即可 |
| `decc2b0` | **4b** 单体换成 Feign + 缓存、删 Controller/Mapper、网关加路由 | `git revert decc2b0` —— 单体回到「自己读写 space 表」的状态 |

> 4a 之所以单独成一个提交，就是为了让「新增服务」与「切换调用方」分开：
> 前者不可能破坏现有功能，后者才是真正有风险的一步。
> 详见方案文档 `docs/microservice-mq-plan.md` 阶段 4 一节。

---

## 十、阶段 5：消息队列（RabbitMQ）

阶段 5 与 4c（配额事件化）合并实施，分 5a~5d 四步，计划见
`docs/microservice-mq-plan.md` 的「4.2 / 4.6 合并实施计划」。

### 怎么用（5a~5d 已可用）

**MQ 默认是关的** —— 延续本项目「默认不依赖外部组件也能开发」的底线，
RabbitMQ 没起时应用照常启动，索引回落为本地线程池执行（与阶段 4 行为一致）。

**5d 起还要多一个服务带 MQ**：`quota.changed` 的消费端是 **space-service**，
所以要同时把两个服务都打开 MQ（否则配额事件发出去没人消费，会堆在队列里）：

```bash
# 单体（生产索引事件 + 额度事件）
$env:PICTURE_MQ_ENABLED='true'; $env:PICTURE_MQ_DEBUG='true'
$env:SPACE_API_BASE_URL='http://127.0.0.1:8131'      # 让单体连下面这个实例
mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8128

# space-service（消费额度事件）
$env:PICTURE_MQ_ENABLED='true'
mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8131
```

> ⚠️ 单体的 `space.api.base-url` 决定它把额度请求发给**哪个** space-service。
> 验证时如果单体指向 8130 而你的测试实例在 8131，会看到满屏
> 「调 space-service 预占额度失败」—— 看起来像服务坏了，其实是连错了实例。


```bash
docker compose up -d rabbitmq        # 5672 应用端口 / 15672 管理台（admin / change-me-dev-only）

# 打开 MQ（自检接口只在开发期开）
$env:PICTURE_MQ_ENABLED='true'
$env:PICTURE_MQ_DEBUG='true'
mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8128
```

**新增的表**（增量迁移，不动任何现有表）：

```bash
# picture-backend/sql/01-phase5-mq.sql —— 可反复执行
#   consumed_message 消费幂等表
#   local_message    本地消息表（5c 用，已建好）
```

⚠️ 别把新表加进 `00-schema.sql`：那是 mysqldump 出来的**整库快照**，
每个表前面都带 `DROP TABLE`，对已有数据的库执行会直接清库。

### 验证

```bash
# 5a：消息地基（拓扑、投递消费、幂等、ack）
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5a.ps1 -LogFile picture-backend\app.log

# 5b：索引写路径走 MQ
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5b.ps1 -LogFile picture-backend\app.log

# 5c：可靠性（本地消息表 / 重放 / 对账）
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5c.ps1 -LogFile picture-backend\app.log

# 5c 的真实故障演练（会**停掉 RabbitMQ**，默认关闭，必须显式开）
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5c.ps1 -DurabilityTest

# 5d：配额原子性与事件化（会建一个临时空间并把上限压小，跑完删掉）
powershell -ExecutionPolicy Bypass -File tools\verify\verify-5d.ps1
```

实测：**5a 16/16**、**5b 18/18**、**5c 11/11**（默认）、**5c 18/18**（含故障演练）、**5d 14/14**。

`verify-5b` 刻意**不新建任何数据**：它对图库里已有的一张图片做重新索引
（重新索引本身幂等，随时可重跑）。真实上传路径另测过一次（上传临时图片、验证后物理删除），
因为那条路需要 COS，不适合放进自动化。

### 5a 做了什么：消息地基与通用幂等消费

| 组件 | 作用 |
|---|---|
| `picture.mq` / `.dlx` / `.retry` 三个交换机 | 业务事件（topic）、死信（direct）、延迟重试（direct） |
| `consumed_message` 表 + `MessageIdempotency` | 消费幂等：`INSERT IGNORE` 认领，撞唯一键即重复投递。用 insert 而非「先查再插」，因为后者有竞态 —— 两个消费者可能同时查不到、然后都执行副作用 |
| `MqConsumerSupport` | 把一次正确消费的四个动作收在一处：幂等登记 → 成功 ack → 失败时**先撤销幂等登记再 reject** → `requeue=false` |
| `MqMessage` 统一信封 | `messageId` 是幂等去重的依据；另带 `messageType`/`source`/`occurredAt` 便于排查 |

**延迟重试用队列而不是消费线程里 sleep**：后者会把线程占住，而「长任务占线程」正是阶段 5 要解决的问题。

### 5b 做了什么：索引写路径搬到 MQ

图片索引的**写路径**从「丢进本机线程池」改成「发一条 `picture.index.requested`」：

| | 阶段 4 及以前 | 阶段 5b |
|---|---|---|
| 触发 | `@Async("pictureIndexExecutor")` | 投递 MQ 消息 |
| 谁来跑 | 本机线程池 core2/max4/queue200 | 任意实例的消费者（加消费者即扩容） |
| 单张耗时 | 占着线程池一个槽几十秒，批量 30 张即塞满 | 只占队列，削峰 |
| 失败 | 写 `indexStatus=2` 后**消息就没了**，要人工重建 | 抛异常 → 延迟重试 → 死信队列 → 可重放（5c 补上重放与对账） |
| 进程重启 | 排队的索引全丢 | 消息在 broker 上，重启后继续消费 |

**MQ 关闭时自动回落到线程池**（`ObjectProvider<MqPublisher>` 拿不到 bean 就回退），
行为与阶段 4 完全一致 —— 这是「不开 MQ 也能开发」这条底线的具体落地方式。

**消息体只带 pictureId**，刻意不带 url/spaceId 快照：消息会在队列里停留（削峰正是目的），
停留期间图片可能被改名/删除，带快照就会索引过期数据。消费端回查一次库，顺带天然处理「图片已删除」。

实测：上传一张真图片，HTTP **3450ms** 返回（不等索引），随后消息被消费、
索引耗时 **5817ms** 在消费者线程上完成、队列排空、`indexStatus=1` 且标签回填成功。

### 5c 做了什么：可靠投递

5b 把索引搬到了 MQ 上，但那时是**直接发消息** —— 中间有个窗口：业务提交成功、消息还没发出去时
进程崩了或 MQ 抖了，那条索引就**静默丢了**（用户不知道，图也搜不到）。5c 补上三件事：

| 组件 | 作用 | 关键点 |
|---|---|---|
| `local_message` 本地消息表 | 消息与业务数据**在同一个本地事务里落库**，再由定时任务补投 | 事务内只写库、不发消息（发消息是网络 I/O，会拖长事务）；补投与立即投递**共用同一个发送出口**，不会分叉 |
| `MqScheduler` 定时任务 | ①补投未发出的消息 ②索引对账 | 项目里**第一个** `@Scheduled`，都挂在 `picture.mq.enabled` 下；失败走指数退避，超过上限转「待人工」 |
| `IndexReconcileService` 对账 | 找出「已过审但从未索引」的图片补建索引 | 兜住「消息**从头就没产生**」——事件驱动看不见这类问题。**刻意不碰 `indexStatus=2`**：失败件交给 DLQ 重放，不自动重投，否则模型服务一挂就会刷出大量重复任务 |
| `DlqReplayService` 重放 | 把死信队列里的消息重新投回业务队列 | **重放时重新生成 messageId**：旧 id 可能已在幂等表里，沿用会让重放被当成重复消息丢掉（表面重放了、实际什么都没做） |

**验证到的效果**（`verify-5c -DurabilityTest`，真的把 RabbitMQ 停掉）：

```
[PASS] MQ 挂掉时上传**依然成功**            <- 业务不被消息中间件拖垮
[PASS] MQ 挂掉时本地消息表出现 pending      <- 消息没有丢
[PASS] broker 不可用时 status 接口仍可用
[PASS] broker 恢复后消息被自动补投（pending 归零）
```

另外手工验证过完整重放链路：停掉索引服务 → 消费失败 → 消息进死信队列 → 重启索引服务 →
`POST /api/mq/debug/replay-dlq` → 重放成功、队列排空、索引成功。

### 5d 做了什么：配额最终一致（4c 合并进来）

阶段 4 把空间配额改成跨服务写（单体 Feign 调 space-service），但那时它是**同步 Feign
且在单体事务内**，留下两个问题：

1. **TOCTOU 超配额**：额度检查在事务外（`PictureServiceImpl` 上传前查 `totalCount`/`totalSize`），
   事务内才扣减，中间还夹着一次 COS 上传（几百毫秒）—— 并发上传可以**都通过检查**，最后一起超限；
2. **强耦合**：space-service 抖一下，上传就失败；而且「配额已扣、本地事务回滚」的偏差无法自愈。

**5d-a（已完成，纯增量）**：给 space-service 补上消费地基 ——
`MqConfig`（拓扑与单体一致）、`MqMessage` 信封、`MessageIdempotency` + `consumed_message`
（与单体共用同一张表）、`MqConsumerSupport`（幂等 → ack → 失败撤销+reject）、
自检队列 `picture.mq.space.test`。**MQ 默认关闭，本服务不依赖 broker 也能提供全部空间功能。**

**5d-b（已完成）**：配额改动按「是否需要立即拒绝」分成两类：

| 场景 | 处理方式 | 为什么 |
|---|---|---|
| **上传占额度** | **同步**调用，改成 space-service 侧的**条件 UPDATE**（原子校验+扣减） | 超限必须让用户**立刻**被拒绝；条件 UPDATE 消除 TOCTOU |
| **删除还额度** | **事件化**（`quota.changed`，走本地消息表 + 幂等消费） | 还额度晚一点没有正确性影响，却让删除路径不再依赖 space-service 可用性 |

上传的时序变成三段式：**预占（原子）→ COS 上传 → 入库**，任一步失败都要把预占还回去
（`refundReservedQuota`，同步调用 —— 用户刚被拒绝、马上会重试，异步归还他会看到「额度不足」却查不出原因）。
条数在上传前就能确定，所以先占条数；体量要等 COS 返回才知道，所以上传后补占差额。

> 一条经验：**「能不能做」要同步，「记一笔账」可以异步**。
> 把两者混在一起（全同步或全异步）都会出问题：全同步耦合太紧，全异步会超卖。

**TOCTOU 修好了没有 —— 用并发测试直接量**（20 个线程同时抢 5 个名额）：

| 实现 | 结果 |
|---|---|
| 旧逻辑（先查额度、再扣） | 20 个线程**全部通过检查**，`totalCount = 20` —— **超卖 4 倍** |
| 现在（条件 UPDATE） | `success=5, limited=15, failed=0`，库里 `totalCount = 5` —— **不超卖** |

这个对照很值得留着：TOCTOU 在单线程测试里**永远测不出来**，
所以专门的探针接口 `/api/mq/debug/probe-concurrent-reserve` 会一直留在代码里（默认关闭）。

验证：`tools/verify/verify-5d.ps1` —— **14/14**（含并发不超卖、事件送达、重复投递只扣一次、
以及「你自己的空间配额完全没被改动」这条回归）。



### 运维入口（5c 之后）

| 入口 | 用途 |
|---|---|
| `GET /api/mq/debug/status` | 本地消息表各状态数量 + 关键队列深度（排查「消息到底发出去没有」第一个该看的接口） |
| `POST /api/mq/debug/replay-dlq?dlq=...&maxReplay=N` | 重放死信消息（只接受认识的死信队列） |
| `POST /api/mq/debug/reconcile` | 手动触发一次索引对账（不用等 10 分钟的定时任务） |
| 管理台 http://127.0.0.1:15672 | 看队列堆积、手动取消息 |

> `/mq/debug/**` 只在 `picture.mq.debug-enabled=true` 时注册 —— 它能往业务交换机投任意消息，
> 不能留在生产环境。

### ⚠️ 阶段 5 的第一个坑：手动 ack 模式下忘了 ack

配了 `acknowledge-mode: manual` 之后，框架**不再替你 ack**。忘了写 `basicAck` 的后果是
消息永远停在 `messages_unacknowledged`，而 `prefetch=1` 意味着**后续所有消息都被堵住**。

它比「自动 ack 丢消息」更隐蔽：**不会报错、消息也不会丢**（消费者断开后 broker 会把它们
退回队列），只是**从此没人处理**。实测现象就是管理台里
`messages_ready=1, messages_unacknowledged=1` 一直不动。

所以消费逻辑统一收进 `MqConsumerSupport.consume(...)`，一次做对四件事：
幂等登记 → 成功 ack → 失败时**先撤销幂等登记再 reject**（只 ack 不撤销会让重投被当成
重复消息丢掉；只 reject 不撤销会重复执行副作用，两个都要）→ `requeue=false`
（否则失败消息被立刻重投，CPU 打满）。

### ⚠️ 阶段 5 的第二个坑：messageId 必须在**公共层**打

`messageId` 是排查 MQ 问题的唯一线索 —— 靠它才能把「投递 → 消费 → 去重」三条日志串起来。
我先是让每个业务监听器自己打，结果**立刻漏了一处**（索引成功的业务日志只打了 pictureId），
验证脚本按 messageId 过滤时一条都匹配不到，看起来像「消息根本没被消费」。

修法：成功/失败各一条统一日志，由 `MqConsumerSupport` 打（含 messageId、type、consumer、耗时）。
**横切关注点就该在公共层解决** —— 指望每个业务实现者都记得打，就一定会漏。

### ⚠️ 阶段 5 的第三个坑：验证脚本读日志会有竞态

「队列已排空」与「日志已落盘」之间**没有先后保证**。实测：队列已经是 0，
但成功日志还没写出来，断言随机失败、看起来像服务有问题（实际日志后来就在那儿）。
所以日志断言一律用**轮询等待**（`WaitLogCount`），不能用「读一次就下结论」。

另外 `Select-String -SimpleMatch '处理成功'` 会同时命中
`消息处理成功：messageId` 与 `收到测试消息并处理成功`，把计数变成 2 ——
断言里的匹配串要么够独特（`消息处理成功：messageId`），要么就别用子串匹配。

### ⚠️ 阶段 5 的第四个坑：改了代码别忘了重启应用

`mvnw spring-boot:run` 在**启动时**加载 class，之后重新编译**不会**生效。
我编译完 5b 就直接上传图片测试，结果走的是老的线程池路径 ——
日志里没有投递记录才暴露出来（索引照样成功，差点误判成「5b 生效了」）。
改完 Java 代码，先重启再验证。

> 顺带提醒：`mvnw spring-boot:run` 会 fork 新 JVM，子进程 stdout **不会**进你重定向的文件 ——
> 要看应用日志得加 `-Dlogging.config=classpath:logback-file.xml`（阶段 4 记过这条）。
> **杀进程时也要注意**：只杀 Maven 父进程，fork 出的子 JVM 会活下来继续占端口
> （实测因此撞过一次 `Port 8128 already in use`）。按端口找 pid 再杀更可靠。

### ⚠️ 阶段 5 的第五个坑：改重试队列的 TTL 之后，消费者会**静默停掉**

队列是 durable 的：一旦已经用某个 `x-message-ttl` 建成，之后**改这个参数再启动**，
broker 会拒绝重新声明：

```
PRECONDITION_FAILED - inequivalent arg 'x-message-ttl' for queue 'picture.mq.index.retry'
    in vhost '/': received '1000' but current is '5000'
→ Stopping container from aborted consumer
```

**整个监听容器的连接被中止**，于是：队列里有消息、`consumers=0`、谁也不消费，
而应用本身看起来一切正常（不报错、接口可用）。实测踩到过 ——
我用 `PICTURE_MQ_RETRY_DELAY_MS=1000` 覆盖了建队列时的 5000。

修法是**删掉队列让它按新 TTL 重建**：

```bash
docker exec sp-rabbitmq rabbitmqctl delete_queue picture.mq.index.retry
```

`verify-5a` / `verify-5c` 都加了断言：重试队列必须带 `x-message-ttl`、业务队列必须有消费者 ——
就是为了让这个「静默停掉」变成显式红灯。**改这类带参数的队列配置时，记得删队列重建。**

### ⚠️ 阶段 5 的第六个坑：诊断接口自己不能在依赖故障时崩掉

`/mq/debug/status` 会去查队列深度。broker 挂掉时那次查询抛异常 → 接口返回
`50000 系统错误` —— 而「broker 挂了」正是最需要看这个接口的时刻。

现在队列深度查询**降级返回 -1** 而不是抛异常：把「查不到」作为信息暴露出来，
让调用方能区分「深度 0」和「不知道」。**凡是诊断/状态类接口，都要假设它依赖的东西正好坏着。**

同理，`MqPublisher.publish` 原先把异常吞成 `false`，导致本地消息表的 `lastError` 里只留一句
「publish returned false」，看不出到底是不是 MQ 挂了。现在它**让异常穿透**，
`lastError` 里能看到 `Connection refused` —— 排查时这两者是天壤之别。

