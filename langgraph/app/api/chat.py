# app/api/chat.py
"""对话接口 —— Agent 的唯一入口。

流程：接收消息与图片 → 图片进会话侧存、业务字段注入图状态 → 执行图 → 返回回复。
"""
import re
from typing import List, Optional

from fastapi import APIRouter, HTTPException
from langchain_core.messages import HumanMessage
from pydantic import BaseModel, Field, field_validator

from common.config import Config

from ..clients.backend import get_login_user_id
from ..graph.build import get_graph, read_prior_state
from ..memory.extractor import schedule_extraction
from ..memory.long_term import get_memory_manager
from ..session_store import get_session, store_images, touch_session

router = APIRouter()


class ChatRequest(BaseModel):
    session_id: str = Field(..., description="会话ID")
    message: str = Field(..., description="用户输入的文本")
    images: Optional[List[str]] = Field(None, description="可选的图片 base64 列表（支持多张）")
    # token / spaceId 刻意放宽成「可缺省、可为 null」，并统一归一成空字符串。
    # 原因：调用方（Java 的 AiChatRequest）这两个字段本身可空，一旦是 null，
    # Pydantic 会直接判 422，而 Java 把 422 映射成笼统的「智能助手暂时无法响应」，
    # 前端完全看不出是参数问题 —— 这个坑已经踩过一次。
    # 语义上「没传」就等于空字符串，所以在这里归一，而不是让每个调用方都记得别传 null。
    token: Optional[str] = Field("", description="token字段（可为空）")
    spaceId: Optional[str] = Field("", description="私人图库的id（可为空）")

    @field_validator("token", "spaceId", mode="before")
    @classmethod
    def _none_to_empty(cls, v):
        """把 None 归一成空字符串，避免因为一个 null 就整单 422。"""
        return "" if v is None else v


class ChatResponse(BaseModel):
    reply: str = Field(..., description="Agent 的回复文本")
    session_id: str = Field(..., description="会话ID")
    image_urls: Optional[List[str]] = Field(None, description="可选的图片 url 列表（支持多张）")


def clean_message_with_images(message: str,
                              images: Optional[List[str]]) -> tuple[str, Optional[List[str]]]:
    """移除消息中可能存在的 base64 字符串，并返回清理后的文本和完整的图片列表"""
    if not images:
        return message, None
    pattern = r'data:image/\w+;base64,[A-Za-z0-9+/=]+'
    cleaned = re.sub(pattern, '', message).strip()
    if not cleaned:
        cleaned = "用户发来多张图片"
    else:
        cleaned += " [多张图片]"
    return cleaned, images


@router.post("/chat", response_model=ChatResponse)
def chat_endpoint(req: ChatRequest):
    """执行一轮对话"""
    compiled_app = get_graph()
    if compiled_app is None:
        raise HTTPException(status_code=503, detail="Agent 未就绪")

    session_id = req.session_id

    # 1. 清理消息，获取图片列表
    cleaned_message, image_list = clean_message_with_images(req.message, req.images)

    # 2. 图片存进会话侧存：base64 太大，不进 AgentState（否则每个节点都会序列化一遍）
    sess = get_session(session_id)
    if image_list:
        # 直接替换为新的列表（可按需改为追加），并施加数量上限
        # pending_base64_list：上传工具用，上传后清空
        # image_base64_list：分析工具用，保留一段时间以便多轮追问
        store_images(session_id, image_list)
    # 如果本次没有图片，保留原有队列（支持多轮补充信息）
    touch_session(session_id)

    # 3. 解析当前用户：这是长期记忆按用户隔离的前提。
    #    解析失败时 user_id 为空串，记忆读写会自动跳过 ——
    #    绝不能退化成「不带过滤条件的全局检索」，否则会把别人的记忆注入当前对话
    user_id = get_login_user_id(req.token) or ""

    # 4. 执图：业务字段在这里注入 state，工具用 InjectedState 读取
    config = {"configurable": {"thread_id": session_id}}
    prior = read_prior_state(config)
    try:
        final_state = compiled_app.invoke(
            {
                "messages": [HumanMessage(content=cleaned_message)],
                # 每轮刷新：身份与凭证
                "user_id": user_id,
                "token": req.token,
                "space_id": req.spaceId,
                # 每轮重置：本轮要返回给前端的图片列表
                "image_urls": [],
                # 跨轮保留：最近一次搜索的上下文。
                # 必须显式给值 —— InjectedState 指向的字段缺失时会直接抛 KeyError
                "picture_ids": list(prior.get("picture_ids") or []),
                "search_space_target": prior.get("search_space_target") or "",
            },
            config=config,
        )
        last_message = final_state["messages"][-1]
        reply = last_message.content if hasattr(last_message, "content") else str(last_message)

        # 5. 自动提取长期记忆：交给独立线程池，这里立即返回
        # 回复内容上面已经算完了，提取对本轮响应没有任何贡献，不该让用户等
        if Config.MEMORY_AUTO_EXTRACT:
            schedule_extraction(
                get_memory_manager(), sess, final_state["messages"], session_id, user_id
            )

        # image_urls 由 search_images 工具写进 state，这里从最终状态里取
        return ChatResponse(reply=reply, session_id=session_id,
                            image_urls=final_state.get("image_urls") or [])
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Agent 执行失败: {str(e)}")
