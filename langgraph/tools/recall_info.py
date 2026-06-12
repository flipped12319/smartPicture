# -*- coding: utf-8 -*-
"""
主动回忆工具 —— Agent 可调用此工具从长期记忆中检索相关历史信息。

使用场景：
- 不确定用户之前说过什么时
- 需要了解用户偏好以做出更好的回应时
- 需要参考之前的对话上下文时
"""

from langchain_core.tools import tool


@tool(description="""从长期记忆中检索与查询相关的历史信息。

**何时调用：**
- 对话开始时，检索用户偏好和历史上下文
- 用户问到之前讨论过的话题，需要回忆过去的对话内容
- 需要了解用户的习惯/偏好以做出更好的回应
- 不确定某个信息是否在之前的对话中出现过

**参数说明：**
- query: 搜索查询文本，关键词或自然语言问题均可。例如："用户的编程偏好"、"之前讨论过的记忆方案"

**返回：** 相关的历史记忆列表（如有）""")
def recall_info(query: str) -> str:
    """从长期记忆中检索相关历史信息"""
    from memory_manager import get_memory_manager

    if not query or not query.strip():
        return "错误：query 参数不能为空。"

    manager = get_memory_manager()
    if manager is None:
        return "错误：长期记忆管理器未初始化，无法检索记忆。"

    memories = manager.retrieve(query=query.strip(), k=5, threshold=0.5)

    if not memories:
        return f"未在长期记忆中找到与「{query}」相关的信息。"

    lines = [f"找到 {len(memories)} 条与「{query}」相关的历史记忆："]
    for i, mem in enumerate(memories, 1):
        mem_type = mem["metadata"].get("type", "unknown")
        importance = mem["metadata"].get("importance", 0)
        content = mem["content"]
        timestamp = mem["metadata"].get("timestamp", "")[:10]  # 只取日期
        lines.append(f"{i}. [{mem_type}] {content} (重要性: {importance:.0%}, 日期: {timestamp})")

    return "\n".join(lines)
