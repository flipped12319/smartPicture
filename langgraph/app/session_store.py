# session_store.py
"""进程内会话侧存 —— 只放「不适合进 AgentState」的东西。

**业务字段一律走 AgentState**（工具用 InjectedState 读、用 Command 回写）。
这里只保留三类：

1. **图片 base64**：动辄 1~3MB，塞进 state 会带来两个后果 ——
   每经过一个节点都要序列化一次快照（CPU/内存开销陡增），
   而且 MemorySaver 会保留每个快照，等于把刚修掉的内存泄漏又引回来。
2. **记忆提取的节流计数**：属于 /chat 这一层的记账，不是工具之间传递的数据。
3. **会话生命周期**（created_at / last_active）：供空闲回收使用。

线程安全：工具跑在线程池里，同一会话并发两条消息时可能同时读写这里的列表
（例如 A 的上传会清空待上传队列，而 B 刚往里塞了图片）。
因此所有会改动内容的操作都持同一把可重入锁。
粒度说明：会话数不多、临界区都是内存操作，一把锁足够简单且没有死锁风险。

注意：这仍是「单进程内存态」，多 worker 部署或重启都会丢失。
"""
import threading
import time
from typing import Any, Dict, List

from common.config import Config

_session_storage: Dict[str, Dict[str, Any]] = {}

# 保护 _session_storage 及其中的可变列表
_lock = threading.RLock()


def get_session(session_id: str) -> Dict[str, Any]:
    """取会话（不存在则创建）

    返回的是内部字典本身，调用方**不要**在多线程里直接改它；
    需要改动请用本模块提供的方法。
    """
    with _lock:
        if session_id not in _session_storage:
            _session_storage[session_id] = {
                "pending_base64_list": [],    # 待上传的图片（上传工具用，上传后清空）
                "image_base64_list": [],      # 可用于分析的图片（保留一段时间，支持多轮追问）
                "extract_pending_turns": 0,   # 距上次长期记忆提取累积了多少轮对话
                "last_extract_at": 0.0,       # 上次长期记忆提取的时间戳
                "created_at": time.time(),
                "last_active": time.time(),   # 供空闲回收使用
            }
        return _session_storage[session_id]


def session_id_from_config(config: Any) -> str:
    """从 RunnableConfig 中取出会话 id（就是图的 thread_id）

    工具拿会话侧存（图片）时用它，避免再依赖 ContextVar ——
    ContextVar 在后台线程里是取不到的，而 config 是随调用一路传下来的。
    """
    if not isinstance(config, dict):
        return ""
    configurable = config.get("configurable") or {}
    return str(configurable.get("thread_id") or "")


def touch_session(session_id: str) -> None:
    """刷新会话活跃时间"""
    with _lock:
        if session_id in _session_storage:
            _session_storage[session_id]["last_active"] = time.time()


def _cap_images(images: List[str]) -> List[str]:
    """只保留最近 N 张图片，超出部分丢弃最早的"""
    limit = Config.MAX_IMAGES_PER_SESSION
    if limit > 0 and len(images) > limit:
        return images[-limit:]
    return images


def store_images(session_id: str, images: List[str]) -> Dict[str, Any]:
    """保存本次消息携带的图片（base64）

    - pending_base64_list：给上传工具用，上传完即清空
    - image_base64_list：给分析工具用，会保留一段时间以便多轮追问

    两个列表都做了数量上限；第二次发图时整体替换，不再叠加。
    """
    kept = _cap_images(list(images))
    with _lock:
        sess = get_session(session_id)
        sess["pending_base64_list"] = list(kept)
        sess["image_base64_list"] = list(kept)
        sess["last_active"] = time.time()
        return sess


def get_analysis_images(session_id: str) -> List[str]:
    """读取可用于分析的图片（不改变状态）"""
    if not session_id:
        return []
    with _lock:
        sess = _session_storage.get(session_id)
        return list(sess.get("image_base64_list") or []) if sess else []


def get_pending_images(session_id: str) -> List[str]:
    """读取待上传的图片（不改变状态）

    注意不要在这里清空：上传工具要先校验参数个数，校验不过时图片必须留着让模型重试。
    """
    if not session_id:
        return []
    with _lock:
        sess = _session_storage.get(session_id)
        return list(sess.get("pending_base64_list") or []) if sess else []


def clear_pending_images(session_id: str) -> None:
    """清空待上传队列（一次图片只上传一次）"""
    with _lock:
        sess = _session_storage.get(session_id)
        if sess:
            sess["pending_base64_list"] = []


def session_count() -> int:
    """当前进程内保有的会话数"""
    with _lock:
        return len(_session_storage)


def sweep_idle_sessions(ttl_seconds: int) -> int:
    """回收空闲超过 ttl_seconds 的会话，返回回收数量"""
    if ttl_seconds <= 0:
        return 0
    deadline = time.time() - ttl_seconds
    with _lock:
        stale = [
            session_id
            for session_id, sess in _session_storage.items()
            if float(sess.get("last_active") or 0) < deadline
        ]
        for session_id in stale:
            _session_storage.pop(session_id, None)
    return len(stale)
