# tools/upload_image.py
from typing import List

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from session_utils import current_session_id, get_session
from services.upload_service import upload_image_to_backend

@tool(description="""批量上传当前会话中所有待上传的图片。需要为每张图片分别提供名称、分类、标签、上传目标。
**重要：每次调用本工具时，你应当选择用户在消息中传递的名称、分类、标签、上传目标等参数；如果你要从历史聊天记录中补充，应当对用户进行提示问用户是否接受，接受才自己补充，否则让用户补充
- name_list: 图片名称列表，如 ['风景1', '人像2']，长度必须与待上传图片数量一致
- category_list: 分类列表，如 ['自然', '人物']，长度必须与待上传图片数量一致
- tags_list: 标签列表，每个元素可以是单个标签或多个标签（逗号分隔），如 ['山', '水,云']，长度必须与待上传图片数量一致
- space_target_list: 上传目标列表，每个元素为 'public' 或 'private'，长度必须与待上传图片数量一致
""")
def upload_image(
        name_list: List[str],
        category_list: List[str],
        tag_list: List[str],
        space_target_list: List[str]
) -> str:
    """批量上传所有待上传图片（一次调用处理全部）"""
    # 通过上下文变量获取当前会话 ID
    print("使用了工具")
    session_id = current_session_id.get()
    if not session_id:
        return "错误：无法获取会话信息，请重试。"

    sess = get_session(session_id)
    pending_list = sess.get("pending_base64_list", [])
    if not pending_list:
        return "没有待上传的图片。请先发送图片（base64格式），然后再次调用本工具。"

    img_count = len(pending_list)

    # 检查各列表长度是否匹配
    if len(name_list) != img_count:
        return f"错误：name_list 长度 ({len(name_list)}) 与待上传图片数量 ({img_count}) 不一致。"
    if len(category_list) != img_count:
        return f"错误：category_list 长度 ({len(category_list)}) 与待上传图片数量 ({img_count}) 不一致。"
    if len(tag_list) != img_count:
        return f"错误：tag_list 长度 ({len(tag_list)}) 与待上传图片数量 ({img_count}) 不一致。"
    if len(space_target_list) != img_count:
        return f"错误：space_target_list 长度 ({len(space_target_list)}) 与待上传图片数量 ({img_count}) 不一致。"

    # 批量上传
    results = []
    for idx, (base64_str, name, category, tags_str, space_target) in enumerate(
            zip(pending_list, name_list, category_list, tag_list, space_target_list)
    ):
        # ★ 关键修改：将 tags_str 转换为 List[str] ★
        # 规则：如果包含逗号，则按逗号分割并去除前后空格；否则包装为单元素列表
        if tags_str is None or tags_str == "":
            tags_list_converted = []   # 空列表
        elif "," in tags_str:
            tags_list_converted = [tag.strip() for tag in tags_str.split(",") if tag.strip()]
        else:
            tags_list_converted = [tags_str.strip()]
        # 处理空间目标
        space_id = None
        token=sess.get("token")
        if space_target == "private":
            space_id=sess.get("spaceId")

        elif space_target != "public":
            results.append(f"❌ {name}: 上传失败 - space_target 只能是 'public' 或 'private'，收到 '{space_target}'")
            continue

        try:
            result = upload_image_to_backend(
                base64_str=base64_str,
                name=name,
                category=category,
                tags=tags_list_converted,
                space_id=space_id,
                token=token
            )
            results.append(f"✅ {name}: 上传成功，ID={result['id']}, URL={result['url']}")
        except Exception as e:
            results.append(f"❌ {name}: 上传失败 - {str(e)}")

    # 清空队列（无论成败，一次性处理完）
    sess["pending_base64_list"] = []
    return "批量上传完成:\n" + "\n".join(results)