# session_utils.py
from contextvars import ContextVar
from typing import Dict, Any

current_session_id: ContextVar[str] = ContextVar('current_session_id', default='')

_session_storage: Dict[str, Dict[str, Any]] = {}

def get_session(session_id: str) -> Dict[str, Any]:
    if session_id not in _session_storage:
        _session_storage[session_id] = {
            "pending_base64_list": [],
            "image_base64_list": [],
            "picture_ids": [],        # 最近一次搜索到的图片 ID 列表
            "search_space_target": None,  # 最近一次搜索的 space_target
            "spaceId": None,
            "token": None,
        }
    return _session_storage[session_id]