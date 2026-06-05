# app_config.py
import os
from dotenv import load_dotenv

load_dotenv()  # 加载 .env 文件


class Config:
    """基础配置"""
    # 后端上传接口
    BACKEND_UPLOAD_URL = os.getenv("BACKEND_UPLOAD_URL", "http://localhost:8123/api/upload/byToken")
    BACKEND_EDIT_URL = os.getenv("BACKEND_EDIT_URL", "http://localhost:8123/api/edit/byToken")
    BACKEND_SEARCH_URL=os.getenv("BACKEND_SEARCH_URL", "http://localhost:8123/api/list/page/vo/cache")
    BACKEND_DELETE_URL=os.getenv("BACKEND_DELETE_URL", "http://localhost:8123/api/delete")
    DEFAULT_PRIVATE_SPACE_ID = int(os.getenv("DEFAULT_PRIVATE_SPACE_ID", "0"))

    # 数据库
    DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://langgraph_user:123456@localhost:5432/langgraph_db")

    # 模型 API
    DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY")
    DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"

    # 图片分析服务（Qwen）
    QWEN_IMAGE_API_URL = os.getenv("QWEN_IMAGE_API_URL", "http://localhost:8080/chat/image")

    # 向量数据库路径
    CHROMA_PERSIST_DIR = os.getenv("CHROMA_PERSIST_DIR", "../chroma_rag_db")

    # RAG 文档路径
    RAG_DOC_PATH = os.getenv("RAG_DOC_PATH", "../docs/journey_to_the_west.txt")

    # 嵌入模型
    EMBEDDING_MODEL_NAME = os.getenv("EMBEDDING_MODEL_NAME", "BAAI/bge-small-zh-v1.5")
    EMBEDDING_DEVICE = os.getenv("EMBEDDING_DEVICE", "cpu")

    # 静态文件目录
    STATIC_DIR = os.getenv("STATIC_DIR", "./static")
    BASE_URL = os.getenv("BASE_URL", "http://localhost:8000")

    # 中文字体路径
    FONT_PATH = os.getenv("FONT_PATH", "C:/Windows/Fonts/simhei.ttf")

    # LLM 参数
    LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0.7"))

    # 工具超时
    ANALYZE_IMAGE_TIMEOUT = int(os.getenv("ANALYZE_IMAGE_TIMEOUT", "120"))