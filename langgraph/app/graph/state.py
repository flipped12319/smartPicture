# app/graph/state.py
"""Agent 图状态。"""
from typing import Annotated, TypedDict

from langgraph.graph.message import add_messages


class AgentState(TypedDict):
    """图状态：对话消息 + 业务字段

    业务字段由 app/api/chat.py 每轮注入，工具通过 InjectedState 读取、
    通过返回 Command 回写，不再依赖任何全局会话字典。这样：

    - 工具是可单测的（给个 state 就能调用）
    - 不会因为 LLM 并行调工具而互相踩数据
    - 状态随 checkpoint 一起持久化，可回放

    注意：图片 base64 不在这里（太大，会被 checkpoint 反复序列化），
    它们放在 app/session_store.py 的会话侧存里。
    """

    messages: Annotated[list, add_messages]
    # ── 身份与凭证（每轮由 /chat 刷新）──
    user_id: str          # 当前用户 id：长期记忆按它隔离
    token: str            # 调后端接口用的凭证
    space_id: str         # 私人图库 id
    # ── 搜索结果上下文（跨轮保留，由工具回写）──
    picture_ids: list     # 最近一次搜索到的图片 id：删除 / 加入相册 / 建相册 / 生成 PDF 用
    search_space_target: str   # 最近一次搜索的目标（public / private，空串表示未知）
    # ── 每轮重置 ──
    image_urls: list      # 本轮要返回给前端的图片 url
