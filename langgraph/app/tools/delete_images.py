# tools/delete_images.py
from typing import Annotated

from langchain_core.tools import InjectedToolCallId, tool
from langgraph.prebuilt import InjectedState
from langgraph.types import Command

from ..clients.backend import delete_pictures_batch

from .state_utils import reply, reply_and_update


@tool(description="""删除当前搜索结果中的所有图片。用户说"删除这些图片"、"帮我把这些删掉"、"删掉它们"等时调用。
**每次调用会自动删除最近一次搜索返回的全部图片，无需指定 ID。**

⚠️ 限制：只能删除私人图库（private）中的图片。如果当前搜索的是公共图库，会提示用户无法删除。
""")
def delete_images(
    # 注入参数不给默认值，否则会被当成普通参数暴露给模型
    token: Annotated[str, InjectedState("token")],
    picture_ids: Annotated[list, InjectedState("picture_ids")],
    search_space_target: Annotated[str, InjectedState("search_space_target")],
    tool_call_id: Annotated[str, InjectedToolCallId],
) -> Command:
    """批量删除最近一次搜索到的所有图片（一次请求调用后端批量删除接口）"""
    print("使用了删除工具")
    picture_ids = list(picture_ids or [])

    if not picture_ids:
        return reply("当前没有可删除的图片。请先使用搜索功能找到要删除的图片。", tool_call_id)

    # 安全检查：只能删除私人图库的图片
    if search_space_target != "private":
        return reply(
            "❌ 无法删除：当前搜索的是公共图库中的图片。\n"
            "只有私人图库（private）中的图片才能被删除。"
            "如需删除私人图库中的图片，请重新搜索并指定 space_target 为 'private'。",
            tool_call_id,
        )

    if not token:
        return reply("错误：未登录，无法删除图片。", tool_call_id)

    # 一次性批量删除（后端为整体事务，失败则全部不删除）
    try:
        result = delete_pictures_batch(picture_ids, token)
    except Exception as e:
        return reply(f"❌ 批量删除失败：{str(e)}\n本次一张都没有删除，可以稍后重试。", tool_call_id)

    if result.get("code") != 0:
        return reply(
            f"❌ 批量删除失败：{result.get('message', '未知错误')}\n"
            f"本次共尝试删除 {len(picture_ids)} 张图片，一张都没有删除。",
            tool_call_id,
        )

    deleted_count = result.get("data")
    if deleted_count is None:
        deleted_count = len(picture_ids)

    # 删除成功后清空状态里的图片记录，避免重复删除
    return reply_and_update(f"✅ 批量删除完成：共删除 {deleted_count} 张图片。", tool_call_id,
                            picture_ids=[], image_urls=[], search_space_target="")
