# tools/search_images.py
from typing import Annotated, List, Optional

from langchain_core.tools import InjectedToolCallId, tool
from langgraph.prebuilt import InjectedState
from langgraph.types import Command

from ..clients.backend import search_pictures

from .state_utils import reply, reply_and_update


@tool(description="""根据搜索条件在后端搜索图片，返回所有匹配图片的 URL 列表。
**重要：你必须从用户的输入中提取以下可选参数，如果所有参数均为空，则提示用户至少提供一个搜索条件。**
- search_text: 搜索文本（图片名称、描述等），可选
- category: 图片分类，如 '风景'、'人物'，可选
- tags: 标签，多个标签用逗号分隔，如 '海洋,古塔'，可选
- space_target: 上传目标，公共图库为 'public' ,私人图库 'private'，必选
""")
def search_images(
    # 注入参数放最前面：它们不能有默认值（有默认值就会泄漏给模型），
    # 而 Python 要求无默认值的参数必须排在有默认值的参数之前
    token: Annotated[str, InjectedState("token")],
    space_id: Annotated[str, InjectedState("space_id")],
    tool_call_id: Annotated[str, InjectedToolCallId],
    search_text: Optional[str] = None,
    category: Optional[str] = None,
    tags: Optional[str] = None,
    space_target: Optional[str] = None,
) -> Command:
    """搜索图片：URL 交给模型，picture_ids 写回图状态供后续工具（删除/加相册）使用"""
    print("使用了搜素工具")
    if not token:
        return reply("错误：未找到用户 token，请先登录。", tool_call_id)

    # 检查是否至少有一个搜索参数
    if not any([search_text, category, tags]):
        return reply("请至少提供一个搜索条件：搜索文本(search_text)、分类(category)或标签(tags)。",
                     tool_call_id)

    # nullSpaceId: True 表示搜索公共图库（无空间 id），False 表示搜索指定空间
    if space_target == "private":
        space_id_for_query = (space_id or "").strip() or None
    elif space_target == "public":
        space_id_for_query = None
    else:
        return reply("请选择搜索公共图库还是私人图库。", tool_call_id)

    try:
        result = search_pictures(
            token=token,
            search_text=search_text or "",   # 若为 None 则传空字符串
            null_space_id=(space_id_for_query is None),
            space_id=space_id_for_query,
            tags=tags,
            category=category,
        )
    except Exception as e:
        return reply(f"搜索失败：{str(e)}", tool_call_id)

    records = result.get("data", {}).get("records", [])
    if not records:
        # 显式清空上一轮的搜索结果，避免后续工具误删/误加
        return reply_and_update("未找到匹配的图片。", tool_call_id,
                                picture_ids=[], image_urls=[], search_space_target="")

    picture_ids = [record.get("id") for record in records if record.get("id")]
    image_urls: List[str] = [record.get("url") for record in records if record.get("url")]
    if not image_urls:
        return reply("找到图片但缺少 URL 字段，请联系管理员。", tool_call_id)

    text = f"搜索到 {len(image_urls)} 张图片，URL 列表：\n" + "\n".join(image_urls)
    return reply_and_update(text, tool_call_id,
                            picture_ids=picture_ids,
                            image_urls=image_urls,
                            search_space_target=space_target or "")
