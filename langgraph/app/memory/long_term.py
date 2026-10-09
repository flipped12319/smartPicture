# app/memory/long_term.py
"""长期记忆（Chroma 向量库）。

职责：
- 语义存储：把用户偏好、重要事实、实体信息等写进独立 collection
- 语义检索：按查询语义匹配最相关的历史记忆
- 去重合并：新记忆与已有记忆相似度过高时合并，避免冗余
- 维护操作：遗忘、按时间/重要性查询

**所有读写都必须带 userId**：长期记忆是跨会话共享的，漏掉这个过滤会把
别人的记忆注入到当前对话里（数据串号），比「暂时没有记忆」严重得多 ——
因此拿不到用户身份时一律 fail-closed（拒绝读写）。

注：从对话里自动提炼记忆的逻辑在 app/memory/extractor.py。
"""
import json
import os
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from langchain_chroma import Chroma
from langchain_core.documents import Document


def _now_iso() -> str:
    """当前时间的 ISO 字符串（使用系统本地时区）"""
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# ── 全局单例（由 app/main.py 在启动时初始化）──

_manager: Optional["MemoryManager"] = None


def init_memory_manager(persist_directory: str, collection_name: str = "long_term_memory",
                        embedding_function=None) -> "MemoryManager":
    """初始化并注册全局长期记忆管理器"""
    global _manager
    _manager = MemoryManager(
        persist_directory=persist_directory,
        collection_name=collection_name,
        embedding_function=embedding_function,
    )
    return _manager


def get_memory_manager() -> Optional["MemoryManager"]:
    """获取全局长期记忆管理器（未初始化时返回 None）"""
    return _manager


class MemoryManager:
    """长期记忆管理器：在独立的 Chroma collection 中存储与检索跨会话记忆"""

    def __init__(self, persist_directory: str, collection_name: str = "long_term_memory",
                 embedding_function=None):
        self.persist_directory = persist_directory
        self.collection_name = collection_name
        self.embedding_function = embedding_function
        self._similarity_threshold = 0.85  # 去重相似度阈值

        os.makedirs(persist_directory, exist_ok=True)
        self._vectorstore: Optional[Chroma] = None
        self._init_vectorstore()

    def _init_vectorstore(self):
        """初始化 Chroma vectorstore，创建或加载指定 collection"""
        if os.path.isdir(self.persist_directory) and os.listdir(self.persist_directory):
            print(f"[MemoryManager] 加载已有长期记忆库: {self.persist_directory}")
            self._vectorstore = Chroma(
                collection_name=self.collection_name,
                persist_directory=self.persist_directory,
                embedding_function=self.embedding_function,
            )
        else:
            # 新建数据库 —— 先写一条占位文档以创建 collection，再立即删除
            print(f"[MemoryManager] 创建新的长期记忆库: {self.persist_directory}")
            placeholder = Document(
                page_content="__memory_placeholder__",
                metadata={"type": "system", "importance": 0.0, "status": "deleted"},
            )
            self._vectorstore = Chroma.from_documents(
                collection_name=self.collection_name,
                persist_directory=self.persist_directory,
                embedding=self.embedding_function,
                documents=[placeholder],
            )
            try:
                results = self._vectorstore.get()
                if results["ids"]:
                    self._vectorstore.delete(ids=results["ids"])
                    print("[MemoryManager] 已清理占位文档")
            except Exception:
                pass

    # ───────────────── 检索 ─────────────────

    def retrieve(self, query: str, k: int = 5, threshold: float = 0.6,
                 user_id: str = "") -> List[Dict[str, Any]]:
        """语义检索相关长期记忆

        Args:
            query: 搜索查询文本
            k: 返回的最大记忆条数
            threshold: 相似度阈值 (0-1)
            user_id: 记忆归属的用户 id

        Returns:
            [{"id": "...", "content": "...", "metadata": {...}}, ...]

        user_id 为空时直接返回空列表（fail-closed）。
        """
        if self._vectorstore is None:
            return []
        if not user_id:
            print("[MemoryManager] 未获取到用户 id，跳过记忆检索（避免跨用户串数据）")
            return []

        try:
            results = self._vectorstore.similarity_search_with_score(
                query, k=k, filter={"userId": user_id}
            )
        except Exception as e:
            print(f"[MemoryManager] 检索失败: {e}")
            return []

        memories = []
        for doc, score in results:
            # Chroma L2 距离：越小越相似，这里近似换算成 0~1 相似度
            similarity = 1.0 / (1.0 + score)
            if similarity < threshold:
                continue

            metadata = dict(doc.metadata)
            self._touch_memory(metadata.get("id", ""), metadata)
            memories.append({
                "id": metadata.get("id", ""),
                "content": doc.page_content,
                "metadata": metadata,
                "similarity": round(similarity, 4),
            })
        return memories

    def _touch_memory(self, memory_id: str, metadata: Dict[str, Any]):
        """更新记忆的访问时间和计数（尽最大努力，失败不影响检索结果）"""
        if not memory_id:
            return
        try:
            metadata["last_access"] = _now_iso()
            metadata["access_count"] = int(metadata.get("access_count", 0)) + 1
            self._vectorstore._collection.update(ids=[memory_id], metadatas=[metadata])
        except Exception:
            pass  # 非关键操作，静默失败

    # ───────────────── 存储 ─────────────────

    def store(self, content: str, memory_type: str = "fact", importance: float = 0.7,
              session_id: str = "", entity_tags: Optional[List[str]] = None,
              user_id: str = "") -> Optional[str]:
        """存入一条长期记忆（带去重检查），返回记忆 id；无法归属时返回 None

        user_id 为空时拒绝写入：一条没有归属的记忆会被所有用户检索到，
        等于凭空制造一个跨用户泄漏源，宁可丢弃。
        """
        if self._vectorstore is None:
            return None
        if not user_id:
            print("[MemoryManager] 未获取到用户 id，拒绝写入记忆（避免产生无归属的全局记忆）")
            return None

        # 1. 去重检查：只在同一个用户的记忆里查重，否则会把别人的记忆合并进来
        try:
            existing = self._vectorstore.similarity_search_with_score(
                content, k=1, filter={"userId": user_id}
            )
        except Exception:
            existing = []

        memory_id = f"mem_{uuid.uuid4().hex[:12]}"
        timestamp = _now_iso()

        if existing:
            doc, score = existing[0]
            similarity = 1.0 / (1.0 + score)
            if similarity >= self._similarity_threshold:
                existing_id = doc.metadata.get("id", "")
                if existing_id:
                    old_importance = float(doc.metadata.get("importance", 0.5))
                    new_importance = max(old_importance, importance)
                    merged_content = self._merge_content(doc.page_content, content)

                    old_tags = doc.metadata.get("entity_tags", "")
                    old_tag_list = [t.strip() for t in old_tags.split(",") if t.strip()] if old_tags else []
                    merged_tags = ",".join(list(set(old_tag_list + (entity_tags or []))))

                    # 删除旧文档后重新添加（用 vectorstore API 保证 embedding 维度一致）
                    try:
                        self._vectorstore.delete(ids=[existing_id])
                    except Exception:
                        pass

                    merged_metadata = {
                        "id": existing_id,
                        # userId 必须保留：丢失它会让这条记忆对所有人可见
                        "userId": user_id,
                        "type": memory_type,
                        "session_id": session_id or doc.metadata.get("session_id", ""),
                        "timestamp": timestamp,
                        "last_access": timestamp,
                        "importance": new_importance,
                        "access_count": int(doc.metadata.get("access_count", 0)),
                        "entity_tags": merged_tags,
                        "status": "active",
                    }
                    try:
                        self._vectorstore.add_documents(
                            [Document(page_content=merged_content, metadata=merged_metadata)]
                        )
                        print(f"[MemoryManager] 合并记忆 {existing_id} (similarity={similarity:.3f})")
                        return existing_id
                    except Exception as e:
                        print(f"[MemoryManager] 合并记忆失败: {e}")
                        return None

        # 2. 无重复 → 新增
        metadata = {
            "id": memory_id,
            "userId": user_id,
            "type": memory_type,
            "session_id": session_id,
            "timestamp": timestamp,
            "last_access": timestamp,
            "importance": importance,
            "access_count": 0,
            "entity_tags": ",".join(entity_tags) if entity_tags else "",
            "status": "active",
        }
        try:
            self._vectorstore.add_documents([Document(page_content=content, metadata=metadata)])
            print(f"[MemoryManager] 新增记忆 {memory_id}: {content[:50]}...")
            return memory_id
        except Exception as e:
            print(f"[MemoryManager] 存储记忆失败: {e}")
            return None

    def _merge_content(self, old: str, new: str) -> str:
        """合并两条相似记忆的内容（简单策略：取较长的，或拼接去重）"""
        if old == new:
            return old
        if new in old:
            return old
        if old in new:
            return new
        return f"{old}；{new}"

    # ───────────────── 维护 ─────────────────

    def forget(self, memory_id: str) -> bool:
        """删除指定记忆"""
        if self._vectorstore is None:
            return False
        try:
            self._vectorstore.delete(ids=[memory_id])
            print(f"[MemoryManager] 已删除记忆: {memory_id}")
            return True
        except Exception as e:
            print(f"[MemoryManager] 删除记忆失败: {e}")
            return False

    def get_recent(self, n: int = 10) -> List[Dict[str, Any]]:
        """获取最近的 N 条记忆"""
        if self._vectorstore is None:
            return []
        try:
            results = self._vectorstore.get()
            if not results["ids"]:
                return []
            memories = [
                {
                    "id": results["ids"][i],
                    "content": results["documents"][i] if results["documents"] else "",
                    "metadata": results["metadatas"][i] if results["metadatas"] else {},
                }
                for i in range(len(results["ids"]))
            ]
            memories.sort(key=lambda m: m["metadata"].get("timestamp", ""), reverse=True)
            return memories[:n]
        except Exception as e:
            print(f"[MemoryManager] 获取最近记忆失败: {e}")
            return []

    def get_high_importance(self, threshold: float = 0.7) -> List[Dict[str, Any]]:
        """获取重要性高于阈值的高优先级记忆"""
        if self._vectorstore is None:
            return []
        try:
            results = self._vectorstore.get()
            if not results["ids"]:
                return []
            memories = []
            for i in range(len(results["ids"])):
                meta = results["metadatas"][i] if results["metadatas"] else {}
                if float(meta.get("importance", 0)) >= threshold:
                    memories.append({
                        "id": results["ids"][i],
                        "content": results["documents"][i] if results["documents"] else "",
                        "metadata": meta,
                    })
            memories.sort(key=lambda m: float(m["metadata"].get("importance", 0)), reverse=True)
            return memories
        except Exception as e:
            print(f"[MemoryManager] 获取高重要性记忆失败: {e}")
            return []

    def get_memory_count(self) -> int:
        """返回记忆总数"""
        if self._vectorstore is None:
            return 0
        try:
            results = self._vectorstore.get()
            return len(results["ids"]) if results["ids"] else 0
        except Exception:
            return 0
