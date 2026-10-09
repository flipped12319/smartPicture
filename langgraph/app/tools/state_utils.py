# -*- coding: utf-8 -*-
"""工具回写图状态的小工具。

LangGraph 里工具不能直接改 state：返回值会被当成「给模型看的工具结果」。
要让工具更新状态，必须返回 Command，并且**自己**把给模型看的 ToolMessage 放进 update 里，
否则模型收不到这次工具调用的回复。

⚠️ 另一个必须遵守的约定：**注入参数（InjectedState / InjectedToolCallId / RunnableConfig）
一律不要给默认值**。一旦给了 None 之类的默认值，LangChain 生成工具 schema 时就不会把它们
当成「注入参数」过滤掉，模型会看到 token、picture_ids 这些它根本不该填的参数。
"""
from typing import Any

from langchain_core.messages import ToolMessage
from langgraph.types import Command


def reply(text: str, tool_call_id: str) -> Command:
    """只回一条 ToolMessage，不改任何业务字段"""
    return Command(update={"messages": [ToolMessage(content=text, tool_call_id=tool_call_id)]})


def reply_and_update(text: str, tool_call_id: str, **updates: Any) -> Command:
    """回一条 ToolMessage，同时更新图状态里的业务字段"""
    update: dict = dict(updates)
    update["messages"] = [ToolMessage(content=text, tool_call_id=tool_call_id)]
    return Command(update=update)
