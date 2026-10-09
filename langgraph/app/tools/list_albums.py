# tools/list_albums.py
from typing import Annotated, Optional

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from ..clients.backend import list_albums as list_albums_api


@tool(description="""查询当前用户的相册列表，返回每个相册的 id、名称、说明和图片数量。

**何时调用：**
- 用户询问"我有哪些相册"、"我的相册里都有什么"
- 用户要求把图片加入某个相册时，你必须先调用本工具拿到目标相册的 id
  （**绝对不要凭空猜测相册 id**）

参数说明：
- keyword: 可选，按相册名称模糊筛选

返回：相册列表（包含 id）。
""")
def list_albums(
    # 注入参数放最前面（不能有默认值，否则会泄漏给模型）
    token: Annotated[str, InjectedState("token")],
    keyword: Optional[str] = None,
) -> str:
    """查询当前用户的相册列表"""
    print("使用了查询相册工具")
    if not token:
        return "错误：未登录，无法查询相册。"

    try:
        result = list_albums_api(token, name=keyword)
    except Exception as e:
        return f"❌ 查询相册失败：{str(e)}"

    if result.get("code") != 0:
        return f"❌ 查询相册失败：{result.get('message', '未知错误')}"

    records = result.get("data", {}).get("records", [])
    if not records:
        return "你还没有创建任何相册。"

    lines = [f"共找到 {len(records)} 个相册："]
    for album in records:
        lines.append(
            f"- id={album.get('id')}｜名称：{album.get('name')}"
            f"｜图片数：{album.get('pictureCount', 0)}"
            f"｜说明：{album.get('introduction')}"
        )
    return "\n".join(lines)
