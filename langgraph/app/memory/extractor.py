# app/memory/extractor.py
"""长期记忆的自动提取：把一轮对话里值得记住的信息提炼出来写进长期记忆。

两个关键设计：

1. **后台执行** —— 提取是一次完整的 LLM 调用（外加在本机跑 embedding 写库），
   而它发生在「回复内容已经算完」之后，对本轮响应没有任何贡献，
   所以丢进独立线程池，绝不能让用户等；
2. **节流** —— 默认每轮都提取太贵（聊 10 轮就是 10 次额外 LLM 调用），
   用「时间 + 轮数」双条件控制，再加一个强制上限，
   避免高频对话永远等不到时间间隔。

并发度默认 1：Chroma 底层是 SQLite，多线程并发写容易撞 database is locked，
而提取本身已经节流，单线程足够。
"""
import json
import time
from concurrent.futures import ThreadPoolExecutor
from typing import List, Optional

from langchain_core.messages import HumanMessage, SystemMessage

from common.config import Config

from .long_term import MemoryManager

EXTRACTION_SYSTEM_PROMPT = """你是一个信息提取专家。你的任务是从对话中提取值得长期记住的信息。

请分析以下对话，提取出其中的：
1. **用户偏好** (preference)：用户明确表达的偏好、习惯、要求。例如"我喜欢详细的技术解释"、"用中文回复"
2. **重要事实** (fact)：用户告知的关于自己或项目的事实信息。例如"我叫 flipped"、"当前项目使用 DeepSeek 模型"
3. **关键实体** (entity)：对话中反复出现或用户重点关注的事物。例如"项目使用 LangGraph 框架"
4. **会话摘要** (summary)：本轮对话的核心内容和结论（一句话概括）

对于每条信息，请评估其重要性 (importance)，范围 0-1：
- 1.0：极其重要，用户明确强调，需要长期记住（如姓名、核心偏好）
- 0.7：重要，但不紧急（如一般偏好、项目信息）
- 0.5：一般性事实
- 0.3：可能有用的上下文

请以 JSON 数组格式返回，每个元素包含 content、type、importance 三个字段。
如果没有值得记住的信息，返回空数组 []。

只返回 JSON 数组，不要包含其他文字。"""

# 提取用的 LLM（由 app/main.py 启动时注入，与 Agent 共用同一个模型）
_llm = None

_executor: Optional[ThreadPoolExecutor] = None


def init_extractor(llm) -> None:
    """注入提取用的 LLM"""
    global _llm
    _llm = llm


def _get_executor() -> ThreadPoolExecutor:
    """惰性创建提取线程池"""
    global _executor
    if _executor is None:
        _executor = ThreadPoolExecutor(
            max_workers=Config.MEMORY_EXTRACT_WORKERS,
            thread_name_prefix="mem-extract",
        )
    return _executor


def shutdown_extractor(wait: bool = True) -> None:
    """关闭线程池；wait=True 会等在跑的任务结束，避免写了一半的记忆丢失"""
    global _executor
    if _executor is not None:
        _executor.shutdown(wait=wait)
        _executor = None


# ─────────────────────── 节流 ───────────────────────


def should_extract(sess: dict) -> bool:
    """判断这一轮是否值得触发提取（时间 + 轮数双条件，带强制兜底）"""
    turns = int(sess.get("extract_pending_turns") or 0) + 1
    sess["extract_pending_turns"] = turns

    # 高频对话可能永远等不到时间间隔，累积到上限就强制提取一次
    if turns >= Config.MEMORY_EXTRACT_FORCE_TURNS:
        print(f"[LTM] 已累积 {turns} 轮，强制提取一次")
        return True
    if turns < Config.MEMORY_EXTRACT_MIN_TURNS:
        print(f"[LTM] 本轮跳过提取（已累积 {turns}/{Config.MEMORY_EXTRACT_MIN_TURNS} 轮）")
        return False
    elapsed = time.time() - float(sess.get("last_extract_at") or 0)
    if elapsed < Config.MEMORY_EXTRACT_MIN_INTERVAL:
        print(f"[LTM] 本轮跳过提取（距上次提取 {elapsed:.0f}s，"
              f"需满 {Config.MEMORY_EXTRACT_MIN_INTERVAL}s）")
        return False
    return True


# ─────────────────────── 调度 ───────────────────────


def _run_extraction(manager: MemoryManager, messages: list, session_id: str,
                    user_id: str, submitted_at: float) -> None:
    """线程池里的实际执行体

    必须自己吞掉异常：后台线程抛出的异常不会进入请求的错误处理，
    只会静默消失（连栈都看不到）。
    """
    started = time.time()
    queue_wait = started - submitted_at
    try:
        ids = extract_and_store(manager, messages, session_id, user_id)
        print(f"[LTM] 后台提取完成：排队 {queue_wait:.2f}s，"
              f"执行 {time.time() - started:.2f}s，存储/更新 {len(ids)} 条")
    except Exception as e:
        print(f"[LTM] 后台提取失败：排队 {queue_wait:.2f}s，"
              f"执行 {time.time() - started:.2f}s，原因 {e}")


def schedule_extraction(manager: Optional[MemoryManager], sess: dict, messages: list,
                        session_id: str, user_id: str) -> None:
    """按节流规则把提取任务丢进线程池"""
    if manager is None or not should_extract(sess):
        return
    # 先记账再提交：即使任务还在排队，后续请求也不会重复触发
    sess["extract_pending_turns"] = 0
    sess["last_extract_at"] = time.time()
    # 只传不可变快照：messages 拷贝一份，避免后台执行期间被后续请求改写。
    # 特别注意不要把 sess 传进去 —— 它是共享可变 dict，后台跑的时候内容可能已经变了
    _get_executor().submit(
        _run_extraction, manager, list(messages), session_id, user_id, time.time()
    )


# ─────────────────────── 提取 ───────────────────────


def extract_and_store(manager: MemoryManager, messages: list, session_id: str = "",
                      user_id: str = "") -> List[str]:
    """从对话消息中提炼重要信息并写进长期记忆，返回新增/更新的记忆 id 列表"""
    if _llm is None:
        print("[LTM] 未注入提取用的 LLM，跳过自动提取")
        return []
    # 提前退出：既避免跨用户写入，也省掉这次没意义的 LLM 调用
    if not user_id:
        print("[LTM] 未获取到用户 id，跳过记忆自动提取")
        return []

    # 1. 把消息序列化成可读对话文本
    conversation_text = _serialize_messages(messages)
    if not conversation_text.strip():
        return []

    # 2. 调用 LLM 提取（打点：这一步是整段提取里最贵的部分）
    llm_started = time.time()
    try:
        response = _llm.invoke([
            SystemMessage(content=EXTRACTION_SYSTEM_PROMPT),
            HumanMessage(content=f"请分析以下对话并提取值得记住的信息：\n\n{conversation_text}"),
        ])
        raw_output = response.content if hasattr(response, "content") else str(response)
    except Exception as e:
        print(f"[LTM] LLM 提取调用失败（耗时 {time.time() - llm_started:.2f}s）: {e}")
        return []
    llm_cost = time.time() - llm_started

    # 3. 解析 JSON 输出
    try:
        cleaned = raw_output.strip()
        if cleaned.startswith("```"):
            lines = cleaned.split("\n")
            cleaned = "\n".join(lines[1:-1]) if lines[-1].strip() == "```" else cleaned
            cleaned = cleaned.strip()
        items = json.loads(cleaned)
        if not isinstance(items, list):
            print(f"[LTM] LLM 返回格式异常: {raw_output[:200]}")
            return []
    except json.JSONDecodeError as e:
        print(f"[LTM] JSON 解析失败: {e}\n原始输出: {raw_output[:300]}")
        return []

    # 4. 逐条存储（打点：每条的查重与写入都要在本机跑 embedding，也可能很慢）
    store_started = time.time()
    stored_ids: List[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        content = (item.get("content") or "").strip()
        if not content:
            continue

        memory_type = item.get("type", "fact")
        if memory_type not in ("preference", "fact", "entity", "summary"):
            memory_type = "fact"

        importance = max(0.0, min(1.0, float(item.get("importance", 0.5))))

        mem_id = manager.store(
            content=content,
            memory_type=memory_type,
            importance=importance,
            session_id=session_id,
            user_id=user_id,
        )
        if mem_id:
            stored_ids.append(mem_id)

    # 耗时拆解：LLM（网络）与 写库（本机 embedding）分开看，才好判断该调哪个参数
    print(f"[LTM] 自动提取完成：LLM {llm_cost:.2f}s，"
          f"写库 {time.time() - store_started:.2f}s，"
          f"存储/更新 {len(stored_ids)} 条（候选 {len(items)} 条）")
    return stored_ids


def _serialize_messages(messages: list) -> str:
    """将 LangChain 消息列表序列化为可读对话文本"""
    lines = []
    for msg in messages:
        role = getattr(msg, "type", "unknown")
        content = getattr(msg, "content", "")

        if role == "tool":
            # 工具返回内容通常很长，截断即可
            short_content = content[:200] + "..." if len(content) > 200 else content
            lines.append(f"[工具返回]: {short_content}")
            continue

        if role == "ai":
            tool_calls = getattr(msg, "tool_calls", None)
            if tool_calls:
                tool_names = [tc.get("name", "unknown") for tc in tool_calls]
                lines.append(f"[AI 调用工具]: {', '.join(tool_names)}")
            if content:
                lines.append(f"AI: {content}")
            continue

        if role == "human":
            lines.append(f"用户: {content}")
            continue

        if role == "system":
            continue  # 系统提示不需要提取记忆

    return "\n".join(lines)
