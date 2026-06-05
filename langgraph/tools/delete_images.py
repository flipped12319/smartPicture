# tools/delete_images.py
from langchain_core.tools import tool
from session_utils import current_session_id, get_session
from services.delete_service import delete_picture_by_id


@tool(description="""删除当前搜索结果中的所有图片。用户说"删除这些图片"、"帮我把这些删掉"、"删掉它们"等时调用。
**每次调用会自动删除最近一次搜索返回的全部图片，无需指定 ID。**

⚠️ 限制：只能删除私人图库（private）中的图片。如果当前搜索的是公共图库，会提示用户无法删除。
""")
def delete_images() -> str:
    """删除最近一次搜索到的所有图片"""
    print("使用了删除工具")
    session_id = current_session_id.get()
    if not session_id:
        return "错误：无法获取会话信息，请重试。"

    sess = get_session(session_id)
    picture_ids = sess.get("picture_ids", [])
    search_space_target = sess.get("search_space_target")

    if not picture_ids:
        return "当前没有可删除的图片。请先使用搜索功能找到要删除的图片。"

    # 安全检查：只能删除私人图库的图片
    if search_space_target != "private":
        return (
            "❌ 无法删除：当前搜索的是公共图库中的图片。\n"
            "只有私人图库（private）中的图片才能被删除。"
            "如需删除私人图库中的图片，请重新搜索并指定 space_target 为 'private'。"
        )

    token = sess.get("token")
    if not token:
        return "错误：未登录，无法删除图片。"

    # 逐个删除
    results = []
    for pid in picture_ids:
        try:
            result = delete_picture_by_id(pid, token)
            if result.get("code") == 0:
                results.append(f"✅ 图片 ID={pid} 删除成功")
            else:
                results.append(
                    f"❌ 图片 ID={pid} 删除失败: {result.get('message', '未知错误')}"
                )
        except Exception as e:
            results.append(f"❌ 图片 ID={pid} 删除失败: {str(e)}")

    # 清空已删除的图片 ID 列表，避免重复删除
    sess["picture_ids"] = []
    sess["search_space_target"] = None

    return "删除结果:\n" + "\n".join(results)
