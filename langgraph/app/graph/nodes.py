# app/graph/nodes.py
"""图的节点与条件边。"""
import os
from typing import List, Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import END

from common.config import Config

from ..memory.long_term import get_memory_manager
from ..tools import tools
from .state import AgentState

# Agent 主模型（与「自动提取长期记忆」共用同一个实例）
llm = ChatOpenAI(
    model=os.getenv("AGENT_MODEL", "deepseek-chat"),
    temperature=Config.LLM_TEMPERATURE,
    api_key=Config.DEEPSEEK_API_KEY,
    base_url=Config.DEEPSEEK_BASE_URL,
)
llm_with_tools = llm.bind_tools(tools)

# ──────────────────── 静态 system prompt ────────────────────
# ⚠️ 这里**不要**拼接每轮变化的内容（例如本轮检索到的长期记忆）。
# DeepSeek 的自动前缀缓存要求前缀逐字节相同，而 system 消息位于消息列表最前面：
# 只要它每轮都变，整段对话的前缀缓存全部失效，长对话的成本和延迟都会明显上升。
# 因此记忆改为追加到「当前这轮 user 消息」的末尾（见 _build_messages）。
_STATIC_SYSTEM_PROMPT = (
    "你是一个智能助手，具备长期记忆能力，可以使用多种工具来帮助用户。\n\n"
    "⚠️ 重要规则：\n"
    "1. 当用户的问题涉及到有关系统功能的内容时，"
    "你必须优先调用 retrieve_from_knowledge_base 工具进行检索，然后严格基于检索结果回答。"
    "不要依赖你自己的内部知识，因为知识库中的信息才是权威来源。\n"
    "2. 当用户告知你的个人偏好、重要信息或值得记住的事实时，"
    "你应该使用 remember_info 工具将其存入长期记忆，以便未来对话中可以回忆。\n"
    "3. 当你需要回忆用户之前告诉过你的事情或任何历史对话中的信息时，"
    "使用 recall_info 工具从长期记忆中检索。\n"
    "4. 如果用户消息末尾附带了【长期记忆】区块，说明系统已经替你检索过相关历史信息，"
    "请直接参考，不要重复调用 recall_info。\n\n"
    "可用工具：\n"
    "- remember_info: 将重要信息存入长期记忆（用户偏好、事实、实体等）\n"
    "- recall_info: 从长期记忆中检索历史信息\n"
    "- analyze_images: 分析用户上传的图片内容\n"
    "- upload_image: 上传图片到图库\n"
    "- search_images: 在图库中搜索图片\n"
    "- delete_images: 删除最近一次搜索到的图片（仅限私人图库）\n"
    "- create_pdf_from_story: 将故事文本和图片合成为 PDF 文件\n"
    "- create_album: 创建相册，可以把最近一次搜索到的图片一并放进去\n"
    "- list_albums: 查询用户的相册列表\n"
    "- add_images_to_album: 把最近一次搜索到的图片加入指定相册，"
    "优先用相册名称（album_name）指定，工具会自动查找；只有拿到候选列表后才需要用 id"
)


def _retrieve_memories_text(query: str, user_id: str) -> str:
    """检索长期记忆并格式化成一段文本；没有则返回空串

    长期记忆按用户隔离，取不到 user_id 时会被跳过（不会退化成全局检索）。
    """
    manager = get_memory_manager()
    if not manager or not query or not user_id:
        return ""
    try:
        memories = manager.retrieve(
            query,
            k=Config.MEMORY_RETRIEVAL_K,
            threshold=Config.MEMORY_SIMILARITY_THRESHOLD,
            user_id=user_id,
        )
    except Exception as e:
        print(f"[LTM] 检索失败: {e}")
        return ""

    if not memories:
        return ""

    lines: List[str] = ["", "【长期记忆 —— 与当前对话相关的历史信息】"]
    for mem in memories:
        mem_type = mem["metadata"].get("type", "fact")
        importance = float(mem["metadata"].get("importance", 0.5))
        stars = "★" * max(1, int(importance * 3))
        lines.append(f"- [{mem_type}] {stars} {mem['content']}")
    return "\n".join(lines)


def _build_messages(state: AgentState) -> list:
    """构造本次推理的消息列表

    记忆只在「一轮对话的开始」注入（最后一条是 human 消息时）。
    工具调用之后的续跑直接复用历史 —— 否则每次工具调用都会重复检索一遍记忆，
    既浪费一次向量库查询，也会产生额外的元数据写入。
    """
    history = list(state["messages"])
    system = SystemMessage(content=_STATIC_SYSTEM_PROMPT)

    if not history or getattr(history[-1], "type", None) != "human":
        return [system] + history

    last = history[-1]
    query = last.content if isinstance(last.content, str) else ""
    memories_text = _retrieve_memories_text(query, state.get("user_id", ""))
    if not memories_text:
        return [system] + history

    # 记忆追加到当前 user 消息末尾（不是塞进 system）：
    # 这样前缀 [静态 system + 追加式历史] 保持稳定，前缀缓存才能命中。
    # 这里构造的是本地副本，不会写回 state，也就不会污染对话历史
    enriched = HumanMessage(content=f"{last.content}\n{memories_text}")
    return [system] + history[:-1] + [enriched]


def call_model(state: AgentState):
    """LLM 节点"""
    response = llm_with_tools.invoke(_build_messages(state))
    return {"messages": [response]}


def should_continue(state: AgentState) -> Literal["tools", END]:
    """条件边：有工具调用就去执行工具，否则结束"""
    last_message = state["messages"][-1]
    if hasattr(last_message, "tool_calls") and last_message.tool_calls:
        return "tools"
    return END
