# tools/add_images_to_album.py
from typing import Annotated, Optional

from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState

from ..clients.backend import (
    add_pictures_to_album as add_pictures_to_album_api,
    list_albums as list_albums_api,
)


@tool(description="""把「最近一次搜索到的图片」批量加入指定相册。

参数说明（album_name 与 album_id 至少要提供一个）：
- album_name: 目标相册的名称，**推荐优先使用**。工具会自动按名称查找相册，
  不需要你事先调用 list_albums。如果匹配到多个相册，工具会返回候选列表，
  此时请你向用户确认要放入哪一个，再带上 album_id 重新调用一次。
- album_id: 目标相册的 id。仅在用户明确说出了 id、或上一步已返回过候选列表时使用。
  ⚠️ 相册 id 是 19 位长数字，必须**原样复制**，不要凭记忆书写或做任何计算。

返回：加入结果，包含实际新增的图片数量。
""")
def add_images_to_album(
    # 注入参数放最前面（不能有默认值，否则会泄漏给模型）
    token: Annotated[str, InjectedState("token")],
    picture_ids: Annotated[list, InjectedState("picture_ids")],
    album_name: Optional[str] = None,
    album_id: Optional[str] = None,
) -> str:
    """把最近一次搜索到的图片批量加入相册（支持按名称或 id 指定相册）"""
    print("使用了加入相册工具")
    if not token:
        return "错误：未登录，无法操作相册。"

    album_id = (album_id or "").strip() or None
    album_name = (album_name or "").strip() or None
    if not album_id and not album_name:
        return "错误：请提供相册名称（album_name）或相册 id（album_id）。"

    picture_ids = list(picture_ids or [])
    if not picture_ids:
        return "当前没有可加入相册的图片。请先使用搜索功能找到要加入相册的图片。"

    # ── 1. 确定目标相册 ──
    target_album_id = album_id
    if not target_album_id:
        try:
            result = list_albums_api(token, name=album_name)
        except Exception as e:
            return f"❌ 查询相册失败：{str(e)}"
        if result.get("code") != 0:
            return f"❌ 查询相册失败：{result.get('message', '未知错误')}"

        records = result.get("data", {}).get("records", [])
        if not records:
            return (
                f"没有找到名称包含「{album_name}」的相册。\n"
                f"请先用 list_albums 查看用户的相册列表，或向用户确认相册名称是否正确。"
            )
        # 优先精确匹配，避免「风景」误命中「风景合集」
        exact_matches = [
            album for album in records if (album.get("name") or "").strip() == album_name
        ]
        candidates = exact_matches or records
        if len(candidates) > 1:
            lines = [
                f"找到 {len(candidates)} 个名称匹配「{album_name}」的相册，"
                f"请向用户确认要放入哪一个，再带上对应的 album_id 重新调用本工具："
            ]
            for album in candidates:
                lines.append(
                    f"- album_id={album.get('id')}｜名称：{album.get('name')}"
                    f"｜图片数：{album.get('pictureCount', 0)}"
                )
            return "\n".join(lines)
        target_album_id = candidates[0].get("id")

    if not target_album_id:
        return "错误：未能确定目标相册，请提供相册名称或 id。"

    # ── 2. 执行加入 ──
    try:
        result = add_pictures_to_album_api(target_album_id, picture_ids, token)
    except Exception as e:
        return f"❌ 加入相册失败：{str(e)}"

    if result.get("code") != 0:
        return f"❌ 加入相册失败：{result.get('message', '未知错误')}"

    added_count = result.get("data")
    if added_count is None:
        added_count = len(picture_ids)
    if added_count == 0:
        return f"这 {len(picture_ids)} 张图片都已经在这个相册里了，无需重复加入。"
    return f"✅ 已把 {added_count} 张图片加入相册（相册 id={target_album_id}）。"
