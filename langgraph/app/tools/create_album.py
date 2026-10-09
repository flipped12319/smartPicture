# tools/create_album.py
from typing import Annotated

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from ..clients.backend import create_album as create_album_api


@tool(description="""为用户创建一个相册。相册会保存到用户的私人空间中，内部记录的是对图片的引用，
不会复制图片文件、也不会占用空间的容量额度（图片本身仍然留在原来的图库中）。

**何时调用：** 用户说"帮我建一个相册"、"创建一个叫 XX 的相册"、"把这些图片做成一个相册"等。

参数说明：
- name: 相册名称（必填）
- introduction: 相册说明（必填）。用户没有说明相册用途时，你应当根据相册名称或包含的图片
  主题，自行撰写一句简短的说明。
- include_last_search: 是否把「最近一次搜索到的图片」一并放进相册，默认 False。
  当用户表达"把刚才搜到的图片放进去"、"把这些图片做成相册"这类意图时设为 True。
  如果最近一次没有搜索到任何图片，该参数会被忽略。

返回：创建结果与新相册的 id。
""")
def create_album(
    # 注入参数放最前面（不能有默认值，否则会泄漏给模型）
    token: Annotated[str, InjectedState("token")],
    search_picture_ids: Annotated[list, InjectedState("picture_ids")],
    name: str,
    introduction: str,
    include_last_search: bool = False,
) -> str:
    """创建一个相册，可选地把最近一次搜索到的图片一并放进去"""
    print("使用了创建相册工具")
    if not token:
        return "错误：未登录，无法创建相册。"

    if not name or not name.strip():
        return "错误：相册名称不能为空，请先向用户确认相册名称。"
    if not introduction or not introduction.strip():
        return "错误：相册说明不能为空。请根据相册主题补充一句简短的说明。"

    picture_ids = list(search_picture_ids or []) if include_last_search else []

    try:
        result = create_album_api(name.strip(), introduction.strip(), picture_ids, token)
    except Exception as e:
        return f"❌ 创建相册失败：{str(e)}"

    if result.get("code") != 0:
        return f"❌ 创建相册失败：{result.get('message', '未知错误')}"

    album_id = result.get("data")
    if picture_ids:
        return (
            f"✅ 相册「{name.strip()}」创建成功（id={album_id}），"
            f"已放入最近搜索到的 {len(picture_ids)} 张图片。"
        )
    return f"✅ 相册「{name.strip()}」创建成功（id={album_id}），目前相册内还没有图片。"
