# services/delete_service.py
import requests
from app_config import Config


def delete_picture_by_id(picture_id: int, token: str) -> dict:
    """调用后端 POST /delete 接口删除指定图片，返回 JSON 响应"""
    payload = {"id": picture_id}
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }

    try:
        resp = requests.post(
            Config.BACKEND_DELETE_URL,
            json=payload,
            headers=headers,
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
