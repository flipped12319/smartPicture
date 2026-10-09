# Agent 与 Qwen 接入评审报告

> 评审对象：`langgraph/`（8000 Agent / 8001 图片索引 / 8080 Qwen-VL 网关）
> 评审时间：2026-09-21
> 结论摘要：**向量检索已接入**（`PictureController.applySemanticSearch` → `PictureIndexServiceImpl.searchPictureIds` → `indexApi.py:/picture/search`，带 LIKE 降级）；主要问题集中在 **长期记忆无用户隔离**、**会话态内存泄漏**、**工程化外壳缺失**、**Qwen 接入方式粗糙** 四个方面。

---

## 一、Agent 方面的不足

### P0：会真实出错或泄漏

#### 1. 长期记忆没有用户隔离（最严重）

```python
# newAgent.py:247 —— 只用 query 检索，没有任何用户维度
memories = memory_manager.retrieve(user_query, k=Config.MEMORY_RETRIEVAL_K,
                                    threshold=Config.MEMORY_SIMILARITY_THRESHOLD)
```

`store()` 写入的 metadata 只有 `type / session_id / importance / ...`（`memory_manager.py:286-296`），**没有 userId**；`retrieve()` 也没有 where 过滤。

- 后果：用户 A 说「我喜欢详细解释」「我叫 flipped」，用户 B 提问时这些记忆会被检索出来，**注入到 B 的 system prompt**（`newAgent.py:253-259`）。这是跨用户串数据，不是技术债。
- `ChatRequest` 里明明有 `token`（`newAgent.py:337`），只是从没用到记忆上。

#### 2. `image_base64_list` 永不清理 → 内存泄漏

```python
# session_utils.py:13
"image_base64_list": [],    # 注释：调用完上传图片工具后不会清空
```

`upload_image` 只清 `pending_base64_list`（`upload_image.py:83`），`image_base64_list` 要留给 `analyze_images` 用（`analyze_image.py:32`），但**谁都不清它**。一张手机照片 base64 后 1~3MB，`_session_storage` 是进程内全局 dict、随会话无限累积 → 跑一天必 OOM。

#### 3. 会话状态与 checkpoint 都在内存，多 worker 直接失效

| 组件 | 现状 | 后果 |
|---|---|---|
| `_session_storage` | 普通 dict（`session_utils.py:7`） | 多 worker 各存一份，重启即丢 |
| checkpointer | `MemorySaver()`（`newAgent.py:321`） | 重启后对话历史全部消失 |
| `Config.SQLITE_DB_PATH` + `SqliteSaver` | **import 了却没用**（`newAgent.py:35`） | 配置是摆设 |
| `ragMemoryAgent.py` | 另一套 `PostgresSaver` + `DATABASE_URL` | 两套方案并存，互相干扰 |

任何 `--workers 4` 或多副本部署，用户连续两条消息落到不同进程 → **图片队列、搜索到的 picture_ids、对话上下文随机丢失**。

#### 4. 「异步不阻塞响应」是假注释

```python
# newAgent.py:398
# ── 4. 自动提取长期记忆（异步不阻塞响应）──
memory_manager.extract_and_store(messages=..., session_id=...)
```

`extract_and_store` 内部是**同步**的 `self.llm.invoke(...)`（`memory_manager.py:350`）。所以每次对话都额外跑一次完整 LLM 调用，还串在响应路径上 → 用户问一句要等两次模型。注释和实现对不上。

#### 5. 消息历史无限增长

`add_messages` 只追加不裁剪，没有 `trim_messages`、没有摘要。长对话必然撞 token 上限；且每轮都把全部历史重新发给 DeepSeek → 成本随轮数平方增长。

### P1：架构与可维护性

#### 6. `newAgent.py` 一个文件扛 6 种职责，且 import 即产生副作用

420 行里塞了：FastAPI 装配、向量库构建（`create_vectorstore`）、embedding 模型加载、MemoryManager 实例化、图定义、chat 端点。

第 58-65 行、186 行、216-223 行**在 import 时就执行**：加载 bge 模型、扫 docs 建 Chroma、打印日志。后果：无法单测、启动慢、`tools/` 反向 import `session_utils` 形成隐式耦合。

#### 7. 工具间传参靠全局 dict，而不是 graph state

```python
sess["picture_ids"]          # search_images 写 → delete_images / add_images_to_album 读
sess["search_space_target"]
sess["token"]
```

这正是 langgraph 里 `AgentState` 该干的事。现在这套做法的代价：LLM 一旦并行调工具就互相踩；无法做 interrupt / 回放；工具无法单测（必须先伪造一个 session）。

#### 8. 工具契约设计不佳

```python
# upload_image.py:17-20
def upload_image(name_list: List[str], category_list: List[str],
                 tag_list: List[str], space_target_list: List[str])
```

要求 4 个**等长列表**，靠工具内部逐个人工校验。模型少填一个元素 → 整批失败。正确做法是单个结构化参数 `items: List[UploadItem]`（Pydantic 嵌套模型），让框架做校验。

#### 9. 业务规则写在提示词里，不可逆操作没有确认

system prompt 里有 10 行工具说明（`newAgent.py:275-287`），工具描述里还有「优先用 album_name」「禁止使用历史图片」这类流程约束 —— 不可测试、不可强制。

更关键的是 `delete_images`、批量上传这类不可逆操作**完全没有人工确认环节**，langgraph 的 `interrupt` / Human-in-the-loop 能力一点没用上。

#### 10. 没有流式输出

`/chat` 是同步 `compiled_app.invoke()` + 一次性返回（`newAgent.py:391-408`）。一个要调 2~3 个工具的任务（每个可能十几秒）→ 前端只能干转圈 30 秒以上。应该用 `astream_events` + SSE。

#### 11. 可观测性与测试为零

全程 `print`（无 logging、无 trace id、无 LangSmith）；没有 pytest、没有工具契约测试；`should_continue` 没有递归上限兜底（默认 25 步后抛异常 → 前端 500，无友好提示）。

### P2：细节清单

| 问题 | 位置 | 说明 |
|---|---|---|
| 记忆相似度阈值形同虚设 | `memory_manager.py:174,176` | `1/(1+L2距离)` + threshold 0.6 ≈ 距离 0.667，归一化向量下几乎全部通过 → 无关记忆也被注入 |
| 检索会写库 | `memory_manager.py:200` | `_touch_memory` 每次检索都 `_collection.update`，读操作放大成写，且用了私有 API |
| 全量拉取 | `memory_manager.py:464,491,519` | `vectorstore.get()` 无参 = 拉全库；`get_memory_count()` 还在每次启动调用 |
| 三份 embedding 实例 | `newAgent.py:61`、`indexApi.py:40`、`ragMemoryAgent.py:39` | 同一个 bge 模型加载三遍（内存 ×3、CPU 竞争） |
| RAG 切片过小 | `newAgent.py:132` | `chunk_size=100`（按字符）≈ 中文一句话，检索质量差 |
| 死代码 | `ragMemoryAgent.py` | 西游记 + PostgresSaver，与 newAgent 重复且无人调用 |
| 日志散落 | `*.log` × 6 | 落在源码目录 |

---

## 二、项目结构是否有问题

**有问题，核心是「三个服务 + 一个 Agent」没有工程化外壳。**

最硬的证据：

```
缺失: langgraph/requirements.txt
缺失: langgraph/pyproject.toml
缺失: langgraph/__init__.py
```

这直接解释了 `ModuleNotFoundError: No module named 'requests'` —— **不是环境没装，而是项目根本没有依赖清单**，换一个解释器就必然崩。这是结构问题，不是操作失误。

### 具体问题

**1. 无包化、依赖隐式 CWD**
`from services.xxx import` / `from tools.xxx import` 依赖「脚本所在目录在 `sys.path` 上」这一隐含前提。没有 `__init__.py`（`services/` 缺失）→ 无法 `pip install -e .`、无法被 pytest 导入、无法容器化。

**2. 三个 FastAPI 服务三套写法**

| 服务 | lifespan | 配置来源 |
|---|---|---|
| `newAgent.py`(8000) | 有 ✓ | 自己拼 `PERSIST_DIR="../chroma_rag_db"`，无视 `Config` |
| `indexApi.py`(8001) | 无 | 老实用了 `Config` ✓ |
| `qwenApi.py`(8080) | 无 | 全走 `os.getenv`，无 Config |

同一个概念两套来源：`Config.CHROMA_PERSIST_DIR` 存在却没人用，`newAgent.py` 自己拼路径。

**3. 三个向量库、两个基准目录**

```
../chroma_rag_db        ← 在 langgraph 的上一级
./chroma_memory_db      ← 在 langgraph 下
./chroma_picture_db     ← 在 langgraph 下
```

CWD 一变就找不到库；`ragMemoryAgent.py:33` 的 `../` 尤其危险。

**4. 四进程手工启动、无编排**
8123 / 8000 / 8001 / 8080 全靠手起，没有统一脚本或 compose。已因此踩过两次坑：8001 跑着旧代码、用错解释器。**这不是运气差，是缺少启动入口的必然结果。**

**5. 命名误导**
`services/` 里装的其实是 HTTP 客户端（`search_service` / `upload_service` / `delete_service`），容易被误认为业务服务层；而 `session_utils` / `memory_manager` / `app_config` 平铺在顶层。

**6. 产物混进源码**
`*.log` × 6、`checkpoints.db`、`chroma_*_db/`、`static/*.pdf`。

### 建议的目标结构

```text
langgraph/
  pyproject.toml            # ★ 最优先，先让依赖可复现
  requirements.txt
  .env.example
  app/
    main.py                 # 只做 FastAPI 装配
    api/chat.py             # 端点
    graph/
      state.py              # ★ AgentState 里带业务字段（picture_ids / token / user_id）
      nodes.py              # call_model / should_continue
      build.py
    memory/{long_term.py, extractor.py}
    clients/{backend.py, qwen.py, index.py}   # 统一超时/重试/连接池
    tools/*.py              # 只依赖 state + clients，不再 import 全局 dict
  services/
    index_api.py            # 8001（独立进程，可保留）
    qwen_api.py             # 8080（建议合并，见第三部分）
  data/                     # chroma_*_db / logs / static
```

---

## 三、Qwen 模型的接入方式是否合理

**方向对，落地粗糙。能跑，但有 3 个「该省的没省」，还有 1 个安全口子。**

现状：`qwenApi.py`(8080) 用 `openai` SDK 走 DashScope **OpenAI 兼容端点**，包成 `/chat/image`，再由 `analyze_image.py`(工具) 和 `indexApi.py`(8001) 通过 **HTTP + base64** 调用。

### 问题 1：base64 在链路上被反复放大

```python
# indexApi.py:130-134
resp = requests.get(url, timeout=30)                    # ① 从 COS 下载
return base64.b64encode(resp.content).decode("utf-8")   # ② 编码成 base64
# 然后：json POST 到 8080 → 解出 base64 → 拼 data URL → SDK → DashScope
```

一张 2MB 图：base64 后约 2.7MB，再经 JSON 转义 → 每次索引要构造/传输约 3MB 字符串，**两次编解码**。且不做压缩、不限尺寸 —— 4000×3000 原图直送模型，token 按像素计费。

### 问题 2：thinking 参数写死且过大

```python
# qwenApi.py:72-79
completion = client.chat.completions.create(
    model="qwen3-vl-flash", stream=True,
    extra_body={'enable_thinking': True, "thinking_budget": 81920}
)
```

对**每一次**调用都开 8 万思考预算。但调用方的任务是「生成 3-6 个标签」「描述这张图」这类简单抽取 —— 延迟和费用成倍，而产出的 `reasoning` 调用方基本丢掉（`indexApi.py:214` 只取 `answer`）。

### 问题 3：`async def` 里跑同步阻塞调用

```python
# qwenApi.py:104-115
@app.post("/chat/image")
async def chat(request: ImageChatRequest):
    reasoning, answer = call_vlm(request.text, request.images)   # ← 同步阻塞
```

`call_vlm` 里是同步 `client.chat.completions.create`，**阻塞事件循环** → 8080 上的并发请求被串行化。重建索引时十几张图会排成一队。

### 问题 4：8080 无鉴权 + 监听 0.0.0.0

```python
uvicorn.run(app, host="0.0.0.0", port=8080)
```

无任何 auth，且机器在 `192.168.3.x` → **局域网内任何人都能无限调用，烧百炼额度**。8001 / 8000 同理。开发期可接受，但不应留到「以后再说」。

### 问题 5：包装层没带来价值，反而丢了 SDK 能力

- `stream=True` 收流后**全量拼接再一次性返回** → 调用方享受不到流式（唯一好处「同时拿 reasoning」直接用 SDK 也能拿到）
- 没传 `timeout` → SDK 默认 10 分钟，卡住只能干等
- 没有重试/退避、没有 `max_tokens`、没有并发上限
- `prepare_data_url` 一律猜 `image/jpeg`（PNG/WebP 贴错 mime）
- 模型名 `"qwen3-vl-flash"` **硬编码在代码里**，而 `DASHSCOPE_BASE_URL` 走环境变量 → 配置口径不统一

### 做得对的地方（应保留）

- **密钥从环境变量读，且启动时 fail-fast**（`qwenApi.py:18-23`，缺 key 直接拒绝启动）—— 做法正确
- 用 **OpenAI 兼容端点**而不是私有 SDK → 换模型/换供应商成本低，方向正确
- `indexApi.py` 的**降级设计**很好：模型失败仍写纯文本索引，不阻断主流程
- 把模型调用收敛到一处便于换 key、打日志 —— 只是**收敛的边界选错了**：应该是「SDK 客户端模块」，而不是「HTTP 服务」

补充依据：**Java 后端并不直接调 8080**（它走 8001，见 `PictureIndexServiceImpl` 的 `pictureIndex.api.base-url`），所以 8080 作为独立服务目前唯一的消费者就是同目录的两个 Python 文件 —— 这层 HTTP 边界是纯成本。

### 建议改法（按性价比）

1. **把 8080 降级成进程内客户端模块** `clients/qwen.py`，`analyze_image` / `index_api` 直接函数调用 → 省掉一次 base64 编解码 + 一次 HTTP 往返。
2. **按任务区分 thinking**：抽取/打标签 `enable_thinking=False`；复杂视觉推理才开，且 `thinking_budget` 降到 1024~4096。
3. **送模型前压图**：最长边 ≤ 1024、转 JPEG q85 → 体积降一个数量级，延迟和费用同步下降。
4. **超时 + 重试 + 并发上限**：`timeout=60`、`max_retries=2`、`Semaphore` 限流。
5. `async` 端点改 `def`（走线程池）或换 `AsyncOpenAI`；`host` 改 `127.0.0.1` 或加内部 token。

---

## 四、优先级建议

| 优先级 | 事项 | 理由 |
|---|---|---|
| **P0** | 长期记忆加 userId 过滤 | 串数据，属正确性问题 |
| **P0** | `image_base64_list` 清理与容量上限 | 内存泄漏，跑久必崩 |
| **P0** | `requirements.txt` + 包化 | 5 分钟的事，挡住一整类环境问题 |
| **P1** | 会话状态改 Redis 或明确单 worker 约束；checkpoint 换 `SqliteSaver` | 让部署方式不再有隐藏前提 |
| **P1** | 业务字段进 `AgentState`，工具不再读全局 dict | 是后面加 interrupt / 流式 / 多 Agent 的前置条件 |
| **P1** | 记忆提取改真异步（`BackgroundTasks` 或队列） | 直接砍掉一半响应延迟 |
| **P2** | Qwen 客户端化 + thinking 分级 + 压图 | 降本降延迟，改动集中在 3 个文件 |
| **P2** | `upload_image` 结构化参数、历史裁剪、流式输出 | 体验与健壮性 |

前三条是**必须修**的，后面的可按需推进。

---

## 附：本次评审的探查基础

- 通读文件：`newAgent.py`、`app_config.py`、`qwenApi.py`、`indexApi.py`、`memory_manager.py`、`session_utils.py`、`ragMemoryAgent.py`、`tools/*`（含 `upload_image`、`analyze_image`、`search_image`、`remember_info`、`recall_info`）、`services/search_service.py`
- 交叉验证：`PictureController.applySemanticSearch`、`PictureIndexServiceImpl.searchPictureIds / rebuildIndex`、`JsonConfig`（Long 序列化）
- 结构核查：`requirements.txt` / `pyproject.toml` / `__init__.py` 均不存在；`pip list` 确认依赖只存在于 `langchain_learn` conda 环境
