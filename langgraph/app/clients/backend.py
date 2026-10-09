# app/clients/backend.py
"""Java 后端（8123）的 HTTP 客户端。

原来散在 services/ 下的四个文件（search/delete/upload/album）+ 一个用户查询里，
现在合并到一个文件，统一走 _post_json，超时与错误提示只维护一份。

调用约定（刻意保持各自原有的语义，避免改行为）：
- search_pictures      : 后端 code != 0 时**抛异常**
- delete_*             : 返回后端 JSON，由调用方判断 code
- upload_image_to_backend : 失败时抛异常，成功返回图片 VO
- 相册相关三个          : 返回后端 JSON，由调用方判断 code
- get_login_user_id    : 失败返回 None（用于长期记忆的用户隔离）
"""
import base64
import hashlib
import time
from typing import List, Optional, Union

import requests

from common.config import Config

# ─────────────────────── 通用请求 ───────────────────────


def _auth_headers(token: str) -> dict:
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


def _post_json(url: str, payload: dict, token: str, action: str, timeout: int = 60) -> dict:
    """统一的后端 POST 调用：异常信息带上具体动作和地址，便于排查"""
    try:
        resp = requests.post(url, json=payload, headers=_auth_headers(token), timeout=timeout)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.Timeout:
        raise Exception(f"{action}请求超时，请稍后重试。")
    except requests.exceptions.ConnectionError:
        raise Exception(f"无法连接到后端服务（{url}），请检查服务是否启动。")
    except requests.exceptions.RequestException as e:
        raise Exception(f"{action}请求失败: {str(e)}")


# ─────────────────────── 图片搜索 ───────────────────────


def search_pictures(token: str, search_text: str, null_space_id: bool,
                    space_id: Optional[Union[int, str]] = None,
                    tags: Optional[str] = None,
                    category: Optional[str] = None) -> dict:
    """调用后端分页搜索接口。

    Args:
        null_space_id: True 表示搜索公共图库（不带 spaceId 条件）
        space_id: 私人图库 id
        tags / category: 可选筛选
    """
    payload = {
        "searchText": search_text,
        "nullSpaceId": null_space_id,
        "spaceId": space_id,
        "tags": tags,
        "category": category,
    }
    # 移除值为 None 的字段，避免后端报错
    payload = {k: v for k, v in payload.items() if v is not None}

    try:
        resp = requests.post(
            Config.BACKEND_SEARCH_URL,
            json=payload,
            headers=_auth_headers(token),
            timeout=30,
        )
        resp.raise_for_status()
        result = resp.json()
    except requests.exceptions.RequestException as e:
        raise Exception(f"请求后端搜索接口失败: {str(e)}")

    # 后端统一返回 code，0 表示成功
    if result.get("code") != 0:
        raise Exception(f"搜索失败: {result.get('message', '未知错误')}")
    return result


# ─────────────────────── 删除 ───────────────────────


def delete_picture_by_id(picture_id: Union[int, str], token: str) -> dict:
    """删除指定图片"""
    try:
        resp = requests.post(
            Config.BACKEND_DELETE_URL,
            json={"id": picture_id},
            headers=_auth_headers(token),
            timeout=30,
        )
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.Timeout:
        raise Exception("删除图片请求超时，请稍后重试。")
    except requests.exceptions.ConnectionError:
        raise Exception(
            f"无法连接到后端服务（{Config.BACKEND_DELETE_URL}），请检查服务是否启动。"
        )
    except requests.exceptions.RequestException as e:
        raise Exception(f"删除图片请求失败: {str(e)}")


def delete_pictures_batch(picture_ids: List[Union[int, str]], token: str) -> dict:
    """批量删除图片。

    后端约束：所有图片必须属于同一个私人空间，且由空间创建者本人操作；
    该接口为整体事务，任意一张不存在或校验失败则全部不删除 ——
    因此只要 code != 0，就表示本次一张都没删掉。
    """
    if not picture_ids:
        raise Exception("没有需要删除的图片。")

    try:
        resp = requests.post(
            Config.BACKEND_DELETE_BATCH_URL,
            json={"ids": list(picture_ids)},
            headers=_auth_headers(token),
            timeout=60,
        )
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.Timeout:
        raise Exception("批量删除图片请求超时，请稍后重试。")
    except requests.exceptions.ConnectionError:
        raise Exception(
            f"无法连接到后端服务（{Config.BACKEND_DELETE_BATCH_URL}），请检查服务是否启动。"
        )
    except requests.exceptions.RequestException as e:
        raise Exception(f"批量删除图片请求失败: {str(e)}")


# ─────────────────────── 上传 ───────────────────────


def upload_image_to_backend(base64_str: str, name: str, token: str,
                            category: Optional[str] = None,
                            tags: Optional[List[str]] = None,
                            space_id: Optional[Union[int, str]] = None) -> dict:
    """上传图片并补齐信息（先上传拿到 id，再调编辑接口），返回图片 VO"""
    # 解析 base64 图片
    if ',' in base64_str:
        header, data = base64_str.split(',', 1)
        mime_type = header.split(':')[1].split(';')[0]
        ext = mime_type.split('/')[-1]
    else:
        data = base64_str
        ext = 'png'

    image_bytes = base64.b64decode(data)
    files = {'file': (f"{name}.{ext}", image_bytes, f"image/{ext}")}

    upload_data = {"picName": name}
    if space_id is not None:
        upload_data['nullSpaceId'] = True
        upload_data['spaceId'] = space_id
    else:
        upload_data['nullSpaceId'] = 1
        upload_data['spaceId'] = ''  # 根据后端要求

    headers = {'Authorization': f'Bearer {token}'}

    # 1. 上传图片
    upload_resp = requests.post(
        Config.BACKEND_UPLOAD_URL,
        files=files,
        data=upload_data,
        headers=headers,
    )
    upload_resp.raise_for_status()
    result = upload_resp.json()
    if result.get('code') != 0:
        raise Exception(f"上传失败: {result.get('message')}")
    picture_vo = result['data']
    picture_id = picture_vo['id']

    # 2. 补齐名称、分类、标签
    edit_payload = {
        'id': picture_id,
        'name': name,
        'category': category,
        'tags': tags or [],
        'introduction': ''
    }
    edit_resp = requests.post(Config.BACKEND_EDIT_URL, json=edit_payload, headers=headers)
    edit_resp.raise_for_status()
    edit_result = edit_resp.json()
    if edit_result.get('code') != 0:
        raise Exception(f"编辑失败: {edit_result.get('message')}")

    return picture_vo


# ─────────────────────── 相册 ───────────────────────


def create_album(name: str, introduction: str, picture_ids: List[Union[int, str]], token: str) -> dict:
    """创建相册（保存到用户的私人空间）"""
    payload = {
        "name": name,
        "introduction": introduction,
        "pictureIds": list(picture_ids or []),
    }
    return _post_json(Config.BACKEND_ALBUM_ADD_URL, payload, token, "创建相册")


def list_albums(token: str, name: Optional[str] = None,
                current: int = 1, page_size: int = 20) -> dict:
    """分页查询当前用户的相册列表"""
    payload = {
        "current": current,
        "pageSize": page_size,
        "sortField": "createTime",
        "sortOrder": "descend",
    }
    if name:
        payload["name"] = name
    return _post_json(Config.BACKEND_ALBUM_LIST_URL, payload, token, "查询相册列表")


def add_pictures_to_album(album_id: Union[int, str], picture_ids: List[Union[int, str]],
                          token: str) -> dict:
    """把图片批量加入相册。

    注意：相册 id 是字符串形式的 19 位雪花 id，必须原样回传，
    不要转成数字，否则精度丢失会导致找不到相册。
    """
    payload = {
        "albumId": album_id,
        "pictureIds": list(picture_ids or []),
    }
    return _post_json(Config.BACKEND_ALBUM_PICTURE_ADD_URL, payload, token, "把图片加入相册")


# ─────────────────────── 当前用户 ───────────────────────

# token 摘要 -> (userId, 过期时间戳)
# 用 token 的 md5 作为 key，避免在内存里再存一份明文凭证
_user_cache: dict = {}


def _cache_key(token: str) -> str:
    return hashlib.md5(token.encode("utf-8")).hexdigest()


def get_login_user_id(token: str) -> Optional[str]:
    """用 token 换取当前登录用户 id（字符串形式），失败返回 None。

    返回字符串而不是数字：后端 Jackson 已把 Long 序列化成字符串，
    保持字符串既与前端一致，也避免大整数跨语言传递时被截断。

    调用方必须把 None 当作「无法确定用户身份」处理并跳过记忆功能，
    绝不能退化成「不带过滤条件的全局检索」—— 那会造成跨用户串数据。
    """
    if not token:
        return None

    key = _cache_key(token)
    now = time.time()
    cached = _user_cache.get(key)
    if cached and cached[1] > now:
        return cached[0]

    try:
        resp = requests.get(
            Config.BACKEND_LOGIN_USER_URL,
            headers={"Authorization": f"Bearer {token}"},
            timeout=Config.BACKEND_LOGIN_USER_TIMEOUT,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            print(f"[user] 获取登录用户失败: {body.get('message')}")
            return None
        user_id = (body.get("data") or {}).get("id")
        if user_id is None:
            print("[user] 登录用户响应中没有 id 字段")
            return None
        user_id = str(user_id)
        _user_cache[key] = (user_id, now + Config.LOGIN_USER_CACHE_TTL)
        return user_id
    except Exception as e:
        print(f"[user] 获取登录用户异常: {e}")
        return None


def clear_user_cache() -> None:
    """清空 token 缓存（仅供测试/调试使用）"""
    _user_cache.clear()
