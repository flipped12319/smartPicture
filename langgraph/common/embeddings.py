# common/embeddings.py
"""嵌入模型（进程内单例）。

原先 RAG 知识库、长期记忆、图片索引各自加载一份 bge 模型，同一个进程里
会重复占内存、抢 CPU。这里收敛成一个惰性单例：

- app 进程（8000）：知识库与长期记忆共用同一份
- services/index_api.py（8001）：进程内单独一份（跨进程无法共享，属正常）

⚠️ 切换模型后，语义检索的相似度阈值必须重新标定：
   不同模型的分数区间完全不同（当前 0.48 是针对 bge-small-zh 定的）。
"""
from typing import Optional

from langchain_huggingface import HuggingFaceEmbeddings

from common.config import Config

_embeddings: Optional[HuggingFaceEmbeddings] = None


def get_embeddings() -> HuggingFaceEmbeddings:
    """获取共享的嵌入模型实例（首次调用时加载）"""
    global _embeddings
    if _embeddings is None:
        _embeddings = HuggingFaceEmbeddings(
            model_name=Config.EMBEDDING_MODEL_NAME,
            model_kwargs={"device": Config.EMBEDDING_DEVICE},
            # BGE 系列建议归一化：归一化后 L2 距离与余弦距离单调对应
            encode_kwargs={"normalize_embeddings": True},
        )
    return _embeddings
