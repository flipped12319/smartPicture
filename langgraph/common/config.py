# common/config.py
"""全局配置。

目录约定（所有相对路径都锚定到 langgraph/，与启动目录无关）：

    langgraph/
      common/      共享配置与客户端
      app/         Agent 服务（8000）
      services/    图片索引服务（8001）、Qwen 网关（8080）
      data/        运行时产物：向量库 / 日志 / 静态文件（已在 .gitignore 忽略）
      ../docs      知识库源文件（RAG 只索引其中的 .txt）
"""
import os

from dotenv import load_dotenv

load_dotenv()  # 加载 langgraph/.env

# common/config.py -> common/ -> langgraph/
_BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PROJECT_DIR = os.path.dirname(_BASE_DIR)
DATA_DIR = os.path.join(_BASE_DIR, "data")


def _resolve(env_value: str, default: str) -> str:
    """相对路径按 langgraph/ 目录解析；绝对路径原样返回"""
    path = env_value or default
    if os.path.isabs(path):
        return path
    return os.path.normpath(os.path.join(_BASE_DIR, path))


class Config:
    """基础配置"""
    # ── 后端接口 ──
    BACKEND_UPLOAD_URL = os.getenv("BACKEND_UPLOAD_URL", "http://localhost:8123/api/upload/byToken")
    BACKEND_EDIT_URL = os.getenv("BACKEND_EDIT_URL", "http://localhost:8123/api/edit/byToken")
    BACKEND_SEARCH_URL = os.getenv("BACKEND_SEARCH_URL", "http://localhost:8123/api/list/page/vo/cache")
    BACKEND_DELETE_URL = os.getenv("BACKEND_DELETE_URL", "http://localhost:8123/api/delete")
    BACKEND_DELETE_BATCH_URL = os.getenv("BACKEND_DELETE_BATCH_URL", "http://localhost:8123/api/delete/batch")
    BACKEND_ALBUM_ADD_URL = os.getenv("BACKEND_ALBUM_ADD_URL", "http://localhost:8123/api/album/add")
    BACKEND_ALBUM_LIST_URL = os.getenv("BACKEND_ALBUM_LIST_URL", "http://localhost:8123/api/album/list/page/vo")
    BACKEND_ALBUM_PICTURE_ADD_URL = os.getenv("BACKEND_ALBUM_PICTURE_ADD_URL",
                                              "http://localhost:8123/api/album/picture/add")
    # 获取当前登录用户：长期记忆按用户隔离时要用它解析 userId
    #
    # ⚠️ 阶段 3b 起这个路径不再由单体提供：登录相关接口已整体搬到 picture-user-service。
    # 所以默认改成走**统一网关**（9000）—— 网关会把 /api/user/** 转给 user-service，
    # 这样 Agent 只认一个入口，以后接口再换属主也不用改这里。
    # 改成 8123 会让这里持续 404：表现是「助手能回话，但长期记忆静默失效」
    # （记忆读写是 fail-closed 的，拿不到 userId 就直接拒绝）。
    BACKEND_LOGIN_USER_URL = os.getenv("BACKEND_LOGIN_USER_URL", "http://localhost:9000/api/user/get/login")
    BACKEND_LOGIN_USER_TIMEOUT = int(os.getenv("BACKEND_LOGIN_USER_TIMEOUT", "5"))
    LOGIN_USER_CACHE_TTL = int(os.getenv("LOGIN_USER_CACHE_TTL", "600"))

    DEFAULT_PRIVATE_SPACE_ID = int(os.getenv("DEFAULT_PRIVATE_SPACE_ID", "0"))

    # ── 模型 API ──
    DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY")
    DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")

    # Qwen 网关（services/qwen_api.py，8080）
    QWEN_IMAGE_API_URL = os.getenv("QWEN_IMAGE_API_URL", "http://localhost:8080/chat/image")
    # 多模态模型单次调用超时（秒）：生成标签、描述图片通常几十秒内
    ANALYZE_IMAGE_TIMEOUT = int(os.getenv("ANALYZE_IMAGE_TIMEOUT", "120"))

    # ── 向量库（都在 data/ 下）──
    CHROMA_PERSIST_DIR = _resolve(os.getenv("CHROMA_PERSIST_DIR", ""), "data/chroma_rag_db")
    CHROMA_PICTURE_DIR = _resolve(os.getenv("CHROMA_PICTURE_DIR", ""), "data/chroma_picture_db")
    CHROMA_MEMORY_DIR = _resolve(os.getenv("CHROMA_MEMORY_DIR", ""), "data/chroma_memory_db")
    PICTURE_COLLECTION_NAME = os.getenv("PICTURE_COLLECTION_NAME", "picture_index")

    # ── 嵌入模型 ──
    EMBEDDING_MODEL_NAME = os.getenv("EMBEDDING_MODEL_NAME", "BAAI/bge-small-zh-v1.5")
    EMBEDDING_DEVICE = os.getenv("EMBEDDING_DEVICE", "cpu")

    # ── 知识库 ──
    DOCS_DIR = _resolve(os.getenv("DOCS_DIR", ""), "../docs")

    # ── 运行时产物 ──
    STATIC_DIR = _resolve(os.getenv("STATIC_DIR", ""), "data/static")
    LOG_DIR = _resolve(os.getenv("LOG_DIR", ""), "data/logs")
    SQLITE_DB_PATH = _resolve(os.getenv("SQLITE_DB_PATH", ""), "data/checkpoints.db")
    BASE_URL = os.getenv("BASE_URL", "http://localhost:8000")

    # 中文字体路径
    FONT_PATH = os.getenv("FONT_PATH", "C:/Windows/Fonts/simhei.ttf")

    # ── LLM 参数 ──
    LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0.7"))

    # ── 服务端口 ──
    INDEX_API_PORT = int(os.getenv("INDEX_API_PORT", "8001"))
    QWEN_API_PORT = int(os.getenv("QWEN_API_PORT", "8080"))
    AGENT_API_PORT = int(os.getenv("AGENT_API_PORT", "8000"))

    # ── 并发控制 ──
    # 在途请求上限：超过就直接返回 429，而不是让请求排队 ——
    # 排队会让所有请求一起变慢、最后一起超时，快速拒绝 + 让用户重试整体体验更好
    AGENT_MAX_CONCURRENCY = int(os.getenv("AGENT_MAX_CONCURRENCY", "64"))
    INDEX_MAX_CONCURRENCY = int(os.getenv("INDEX_MAX_CONCURRENCY", "32"))
    QWEN_MAX_CONCURRENCY = int(os.getenv("QWEN_MAX_CONCURRENCY", "16"))
    # 过载时建议客户端等待的秒数（放在 Retry-After 响应头里）
    RETRY_AFTER_SECONDS = int(os.getenv("RETRY_AFTER_SECONDS", "5"))
    # 线程池上限：同步端点与 LangGraph 里的同步工具都跑在这个池子里，
    # uvicorn 默认 40，并发对话时会成为天花板
    AGENT_THREAD_POOL_SIZE = int(os.getenv("AGENT_THREAD_POOL_SIZE", "100"))

    # ── 长期记忆 ──
    MEMORY_COLLECTION_NAME = os.getenv("MEMORY_COLLECTION_NAME", "long_term_memory")
    MEMORY_RETRIEVAL_K = int(os.getenv("MEMORY_RETRIEVAL_K", "3"))
    MEMORY_SIMILARITY_THRESHOLD = float(os.getenv("MEMORY_SIMILARITY_THRESHOLD", "0.6"))
    MEMORY_AUTO_EXTRACT = os.getenv("MEMORY_AUTO_EXTRACT", "true").lower() == "true"

    # 自动提取的省流节流：时间 + 轮数双条件满足才提取；
    # 高频对话可能永远等不到时间间隔，所以再加一个强制上限
    MEMORY_EXTRACT_MIN_INTERVAL = int(os.getenv("MEMORY_EXTRACT_MIN_INTERVAL", "300"))
    MEMORY_EXTRACT_MIN_TURNS = int(os.getenv("MEMORY_EXTRACT_MIN_TURNS", "3"))
    MEMORY_EXTRACT_FORCE_TURNS = int(os.getenv("MEMORY_EXTRACT_FORCE_TURNS", "8"))
    # 提取线程池大小。默认 1：Chroma 底层是 SQLite，多线程并发写容易撞 database is locked
    MEMORY_EXTRACT_WORKERS = int(os.getenv("MEMORY_EXTRACT_WORKERS", "1"))

    # ── 会话侧存容量控制 ──
    MAX_IMAGES_PER_SESSION = int(os.getenv("MAX_IMAGES_PER_SESSION", "6"))
    SESSION_IDLE_TTL_SECONDS = int(os.getenv("SESSION_IDLE_TTL_SECONDS", "1800"))
    SESSION_SWEEP_INTERVAL_SECONDS = int(os.getenv("SESSION_SWEEP_INTERVAL_SECONDS", "300"))
