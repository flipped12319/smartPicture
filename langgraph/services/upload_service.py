# services/upload_service.py
import requests
import base64
from typing import Optional, List
from app_config import Config
# from langgraph.app_config import Config

def upload_image_to_backend(
        base64_str: str,
        name: str,
        token: str,
        category: Optional[str] = None,
        tags: Optional[List[str]] = None,
        space_id: Optional[int] = None
) -> dict:
    """
    调用后台上传接口（先上传得到 id，再调用编辑接口完善信息）
    返回图片信息字典，包含 id, url 等
    """
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

    # 构建上传参数
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
        headers=headers
    )
    upload_resp.raise_for_status()
    result = upload_resp.json()
    if result.get('code') != 0:
        raise Exception(f"上传失败: {result.get('message')}")
    picture_vo = result['data']
    picture_id = picture_vo['id']

    # 2. 编辑图片信息（名称、分类、标签等）
    edit_payload = {
        'id': picture_id,
        'name': name,
        'category': category,
        'tags': tags or [],
        'introduction': ''
    }
    edit_resp = requests.post(
        Config.BACKEND_EDIT_URL,
        json=edit_payload,
        headers=headers
    )
    edit_resp.raise_for_status()
    edit_result = edit_resp.json()
    if edit_result.get('code') != 0:
        raise Exception(f"编辑失败: {edit_result.get('message')}")

    return picture_vo