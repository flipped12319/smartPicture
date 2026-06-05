# tools/search_images.py
from typing import Optional, List
from langchain_core.tools import tool
from session_utils import current_session_id, get_session
from services.search_service import search_service

@tool(description="""根据搜索条件在后端搜索图片，返回所有匹配图片的 URL 列表。
**重要：你必须从用户的输入中提取以下可选参数，如果所有参数均为空，则提示用户至少提供一个搜索条件。**
- search_text: 搜索文本（图片名称、描述等），可选
- category: 图片分类，如 '风景'、'人物'，可选
- tags: 标签，多个标签用逗号分隔，如 '海洋,古塔'，可选
- space_target: 上传目标，公共图库为 'public' ,私人图库 'private'，必选
""")
def search_images(
    search_text: Optional[str] = None,
    category: Optional[str] = None,
    tags: Optional[str] = None,
    space_target: Optional[str]  = None
) -> str:
    """搜索图片，返回图片URL数组的文本描述"""
    # 获取会话信息
    print("使用了搜素工具")
    session_id = current_session_id.get()
    if not session_id:
        return "错误：无法获取会话信息，请重试。"
    sess = get_session(session_id)
    token = sess.get("token")
    if not token:
        return "错误：未找到用户 token，请先登录。"

    # 检查是否至少有一个搜索参数
    if not any([search_text, category, tags]):
        return "请至少提供一个搜索条件：搜索文本(search_text)、分类(category)、标签(tags)或空间ID(space_id)。"
    space_id = None
    token=sess.get("token")
    if space_target == "private":
        space_id=sess.get("spaceId")

    elif space_target != "public":
        return "请选择上传至公共图库还是私人图库。"

    # 构建 search_service 所需参数
    # nullSpaceId: True 表示搜索公共空间（无空间ID），False 表示搜索指定空间
    null_space_id = (space_id is None)   # 如果没有提供 space_id，则搜索公共空间
    # 如果 space_id 为 None，后端可能期望 spaceId 字段为 None 或不传；search_service 中已过滤 None 值
    # 注意：search_service 允许 spaceId=None，会从 payload 中移除该字段

    try:
        result = search_service(
            token=token,
            search_text=search_text or "",   # 若为 None 则传空字符串
            nullSpaceId=null_space_id,
            spaceId=space_id,
            tags=tags,
            category=category
        )
    except Exception as e:
        return f"搜索失败：{str(e)}"

    # 解析 records 中的 url 和 id
    records = result.get("data", {}).get("records", [])
    if not records:
        sess["picture_ids"] = []
        sess["search_space_target"] = None
        return "未找到匹配的图片。"
    sess["picture_ids"] = [record.get("id") for record in records if record.get("id")]
    sess["search_space_target"] = space_target
    sess["image_urls"] = [record.get("url") for record in records if record.get("url")]
    print("这是picture_ids列表 ", sess["picture_ids"])
    print("这是image_urls图片列表 ", sess["image_urls"])
    image_urls: List[str] = sess["image_urls"]
    if not image_urls:
        return "找到图片但缺少 URL 字段，请联系管理员。"

    # 返回可读的结果（模型可根据需要进一步使用这些 URL）
    return f"搜索到 {len(image_urls)} 张图片，URL 列表：\n" + "\n".join(image_urls)