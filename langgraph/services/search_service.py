# services/search_service.py
import requests
from typing import Optional
from app_config import Config

def search_service(
    token: str,
    search_text: str,
    nullSpaceId: bool,
    spaceId: Optional[int] = None,
    tags: Optional[str] = None,
    category: Optional[str] = None
) -> dict:
    """
    调用后端搜索接口。
    参数：
        token: 用户认证 token
        search_text: 搜索文本
        nullSpaceId: 是否空空间标识（布尔值）
        spaceId: 空间 ID，可选
        tags: 标签字符串，可选（如 "tag1,tag2"）
        category: 分类字符串，可选
    返回：
        后端返回的 JSON 数据（字典）
    """
    # 构建请求体（根据后端实际要求的字段名调整）
    payload = {
        "searchText": search_text,
        "nullSpaceId": nullSpaceId,
        "spaceId": spaceId,           # 若为 None，后端需能处理
        "tags": tags,                 # 直接传递字符串，后端自行解析
        "category": category
    }

    # 移除值为 None 的字段，避免后端报错（可选）
    payload = {k: v for k, v in payload.items() if v is not None}

    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    try:
        response = requests.post(
            Config.BACKEND_SEARCH_URL,
            json=payload,
            headers=headers,
            timeout=30
        )
        response.raise_for_status()   # 非 2xx 状态码抛出异常
        result = response.json()
        # 假设后端统一返回 code 字段，0 表示成功
        if result.get("code") != 0:
            raise Exception(f"搜索失败: {result.get('message', '未知错误')}")
        return result
    except requests.exceptions.RequestException as e:
        raise Exception(f"请求后端搜索接口失败: {str(e)}")