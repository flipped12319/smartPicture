"""Agent 可调用的工具集合。

约定：
- 工具之间共享的业务数据一律走 AgentState（用 InjectedState 读、用 Command 回写）；
- 只由某个工具单独使用的运行时数据（如图片 base64、RAG 向量库）留在各自的模块里。
"""

from .knowledge_base import retrieve_from_knowledge_base
from .analyze_image import analyze_images
from .upload_image import upload_image
from .create_pdf_from_story import create_pdf_from_story
from .search_image import search_images
from .delete_images import delete_images
from .remember_info import remember_info
from .recall_info import recall_info
from .create_album import create_album
from .list_albums import list_albums
from .add_images_to_album import add_images_to_album

__all__ = [
    "retrieve_from_knowledge_base",
    "analyze_images",
    "upload_image",
    "create_pdf_from_story",
    "search_images",
    "delete_images",
    "remember_info",
    "recall_info",
    "create_album",
    "list_albums",
    "add_images_to_album",
    "tools",
]

# 绑定给模型的工具清单（顺序与原实现保持一致，避免影响模型的选择倾向）
tools = [
    analyze_images,
    retrieve_from_knowledge_base,
    upload_image,
    create_pdf_from_story,
    search_images,
    delete_images,
    remember_info,
    recall_info,
    create_album,
    list_albums,
    add_images_to_album,
]
