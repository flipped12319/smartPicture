# app/main.py
"""Agent 服务（8000）—— 只做装配。

启动方式（二选一，都要在 langgraph/ 目录下执行）：
    python run_agent.py
    python -m app.main
"""
import asyncio
import os
from contextlib import asynccontextmanager

import anyio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from common.config import Config
from common.embeddings import get_embeddings

from .api.chat import router as chat_router
from .graph.build import init_graph
from .graph.nodes import llm
from .memory.extractor import init_extractor, shutdown_extractor
from .memory.long_term import init_memory_manager
from .session_store import session_count, sweep_idle_sessions


async def _session_sweeper():
    """后台定期回收空闲会话

    会话态（尤其是图片 base64）全部驻留在进程内存里，不回收会随会话数无限增长，
    最终 OOM。这里按「最后活跃时间」清理，正在使用的会话不受影响。
    """
    while True:
        await asyncio.sleep(Config.SESSION_SWEEP_INTERVAL_SECONDS)
        try:
            removed = sweep_idle_sessions(Config.SESSION_IDLE_TTL_SECONDS)
            if removed:
                print(f"[session] 已回收 {removed} 个空闲会话，当前会话数 {session_count()}")
        except Exception as e:
            print(f"[session] 回收空闲会话失败: {e}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 提高线程池上限：同步端点、LangGraph 里的同步工具都跑在这个池子里。
    # 默认只有 40，并发对话时每轮都要占住一个线程十几秒（等模型返回），很快会被占满。
    # ⚠️ 必须在事件循环内设置：current_default_thread_limiter() 依赖当前运行的 loop，
    # 放在模块顶层会直接抛 anyio.NoEventLoopError 导致服务起不来。
    anyio.to_thread.current_default_thread_limiter().total_tokens = Config.AGENT_THREAD_POOL_SIZE
    print(f"[concurrency] 线程池上限 = {Config.AGENT_THREAD_POOL_SIZE}，"
          f"在途请求上限 = {Config.AGENT_MAX_CONCURRENCY}")

    # 长期记忆（向量库 + 自动提取线程池）
    manager = init_memory_manager(
        persist_directory=Config.CHROMA_MEMORY_DIR,
        collection_name=Config.MEMORY_COLLECTION_NAME,
        embedding_function=get_embeddings(),
    )
    init_extractor(llm)
    print(f"[MemoryManager] 长期记忆库已就绪，当前记忆数: {manager.get_memory_count()}")

    # Agent 图
    init_graph()
    sweeper = asyncio.create_task(_session_sweeper())
    print("Agent 已加载，API 服务启动")
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        # 等在跑的提取跑完再退出，避免写了一半的记忆丢失
        shutdown_extractor(wait=True)
        print("服务已关闭")


app = FastAPI(title="Smart Picture Agent", lifespan=lifespan)

# ──────────────────── 准入控制（限流）────────────────────
# 在途请求数。没有并发竞态：中间件跑在事件循环上，
# 判断与自增之间没有 await，所以这里用普通 int 即可
_inflight = 0


@app.middleware("http")
async def limit_concurrency_middleware(request: Request, call_next):
    """超过在途上限时直接返回 429，而不是让请求排队等待

    排队会让所有请求一起变慢、最后一起超时；快速拒绝并给出 Retry-After，
    客户端可以立刻重试，整体体验更好。
    """
    global _inflight

    # 健康检查与静态文件不计入并发额度
    path = request.url.path
    if path == "/health" or path.startswith("/static"):
        return await call_next(request)

    if _inflight >= Config.AGENT_MAX_CONCURRENCY:
        return JSONResponse(
            status_code=429,
            content={
                "code": 42900,
                "message": "当前请求过多，请稍后重试",
                "data": None,
            },
            headers={"Retry-After": str(Config.RETRY_AFTER_SECONDS)},
        )

    _inflight += 1
    try:
        return await call_next(request)
    finally:
        _inflight -= 1


app.include_router(chat_router)

# 挂载 PDF 等静态产物目录
os.makedirs(Config.STATIC_DIR, exist_ok=True)
app.mount("/static", StaticFiles(directory=Config.STATIC_DIR), name="static")


@app.get("/health")
def health():
    return {"status": "ok"}


def main():
    """启动 uvicorn 服务"""
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=Config.AGENT_API_PORT,
        # 最后一道保险：中间件被绕过时由 uvicorn 兜底（略高于中间件阈值）
        limit_concurrency=Config.AGENT_MAX_CONCURRENCY + 32,
    )


if __name__ == "__main__":
    main()
