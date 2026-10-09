# common/clients/qwen.py
"""Qwen-VL（多模态）客户端。

当前通过 services/qwen_api.py（8080）这个网关调用 —— 它内部走 DashScope 的
OpenAI 兼容端点。之所以收敛成一个客户端模块：

1. 超时、请求体、错误处理只写一遍（app 与 services 共用同一份实现）；
2. 后续如果按评审建议把 8080 合并进进程内，只需要改这一个文件；
3. 「按任务区分 thinking 开关」「送模型前压图」这类优化也有唯一的落点。

约定：调用失败直接抛 requests 异常（不包装），让调用方按自己的场景处理 ——
工具函数要把它转成给模型看的文字，索引服务要让它变成 HTTP 500。
"""
from typing import List, Optional

import requests

from common.config import Config


def chat_with_images(text: str, images: List[str], timeout: Optional[int] = None) -> dict:
    """把文本 + 图片交给 Qwen-VL，返回 {"answer": str, "reasoning": str}

    Args:
        text: 提问或指令
        images: 纯 base64 列表（不含 data:image/ 前缀，符合网关的入参约定）
        timeout: 超时秒数，默认取 Config.ANALYZE_IMAGE_TIMEOUT
    """
    payload = {"text": text, "images": list(images)}
    resp = requests.post(
        Config.QWEN_IMAGE_API_URL,
        json=payload,
        timeout=timeout or Config.ANALYZE_IMAGE_TIMEOUT,
    )
    resp.raise_for_status()
    data = resp.json()
    return {
        "answer": data.get("answer", "") or "",
        "reasoning": data.get("reasoning", "") or "",
    }
