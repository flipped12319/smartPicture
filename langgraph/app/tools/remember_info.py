# -*- coding: utf-8 -*-
"""
主动记忆工具 —— Agent 可调用此工具将重要信息存入长期记忆。

使用场景：
- 用户明确告知偏好："我喜欢用表格展示数据"
- 用户告知个人信息："我的名字是 flipped"
- 对话中提炼出值得记住的事实
"""

from typing import Annotated

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState


@tool(description="""将重要信息存入长期记忆，以便在未来的对话中回忆和使用。

**何时调用：**
- 用户明确告知偏好、习惯或要求（如"我喜欢详细的技术解释"）
- 用户告知个人信息或重要事实（如"我叫 xxx"、"当前项目使用 xxx 框架"）
- 对话中出现了值得跨会话记住的关键信息

**参数说明：**
- content: 要记住的具体内容，用一句简洁的话表述
- memory_type: 记忆类型，可选值：
  - "preference"：用户偏好/习惯
  - "fact"：重要事实/个人信息
  - "entity"：关键实体/概念
  - "summary"：会话摘要
- importance: 重要性评分 0-1（默认 0.7）。1.0=极其重要需长期记住，0.5=一般参考信息

**注意：** 不要频繁调用此工具——只在确实有值得长期记住的信息时才使用。""")
def remember_info(
    # 注入参数放最前面（不能有默认值，否则会泄漏给模型）
    user_id: Annotated[str, InjectedState("user_id")],
    content: str,
    memory_type: str = "fact",
    importance: float = 0.7,
) -> str:
    """将重要信息存入长期记忆"""
    from ..memory.long_term import get_memory_manager

    if not content or not content.strip():
        return "错误：content 参数不能为空。"

    # 校验 memory_type
    valid_types = ("preference", "fact", "entity", "summary")
    if memory_type not in valid_types:
        return f"错误：memory_type 必须为 {', '.join(valid_types)} 之一，当前值: {memory_type}"

    # 校验 importance 范围
    importance = max(0.0, min(1.0, importance))

    manager = get_memory_manager()
    if manager is None:
        return "错误：长期记忆管理器未初始化，无法存储记忆。"

    # 记忆必须归属于具体用户：取不到用户身份就直接放弃，
    # 否则会写出一条「所有人都能检索到」的无归属记忆
    if not user_id:
        return "错误：无法确定当前用户身份，已跳过记忆存储。"

    memory_id = manager.store(
        content=content.strip(),
        memory_type=memory_type,
        importance=importance,
        user_id=user_id,
    )

    if memory_id:
        return f"✅ 已将以下信息存入长期记忆 (ID: {memory_id})：\n[{memory_type}] {content.strip()}"
    else:
        return "❌ 存储记忆失败，请稍后重试。"
