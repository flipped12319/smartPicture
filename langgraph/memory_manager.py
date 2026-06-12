# -*- coding: utf-8 -*-
"""
长期记忆管理器 —— 基于 Chroma 向量数据库实现。

职责：
- 语义存储：将用户偏好、重要事实、实体信息等存入独立 Chroma collection
- 语义检索：根据查询语义匹配最相关的历史记忆
- 自动提取：调用 LLM 从对话中自动提炼值得长期记住的信息
- 去重合并：新记忆与已有记忆相似度过高时自动合并，避免冗余
- 记忆维护：支持遗忘、重要性衰减等操作

记忆数据模型（每条记忆 = 一个 Chroma document）：
    id          — 唯一标识 "mem_<uuid>"
    content     — 被 embedding 的文本内容
    metadata    — type, session_id, timestamp, last_access, importance,
                  access_count, entity_tags, status
"""

import json
import uuid
import os
from datetime import datetime, timezone
from typing import List, Optional, Dict, Any

from langchain_chroma import Chroma
from langchain_core.documents import Document


# ── 东八区时间工具 ──

def _now_iso() -> str:
    """返回东八区 (UTC+8) 当前时间的 ISO 格式字符串"""
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# ── 默认提取提示词 ──

# ── 全局单例引用（由 newAgent.py 在初始化时设置）──

_memory_manager_instance: Optional["MemoryManager"] = None


def set_memory_manager(manager: "MemoryManager"):
    """设置全局 MemoryManager 实例（供工具函数访问）"""
    global _memory_manager_instance
    _memory_manager_instance = manager


def get_memory_manager() -> Optional["MemoryManager"]:
    """获取全局 MemoryManager 实例"""
    return _memory_manager_instance


EXTRACTION_SYSTEM_PROMPT = """你是一个信息提取专家。你的任务是从对话中提取值得长期记住的信息。

请分析以下对话，提取出其中的：
1. **用户偏好** (preference)：用户明确表达的偏好、习惯、要求。例如"我喜欢详细的技术解释"、"用中文回复"
2. **重要事实** (fact)：用户告知的关于自己或项目的事实信息。例如"我叫 flipped"、"当前项目使用 DeepSeek 模型"
3. **关键实体** (entity)：对话中反复出现或用户重点关注的事物。例如"项目使用 LangGraph 框架"
4. **会话摘要** (summary)：本轮对话的核心内容和结论（一句话概括）

对于每条信息，请评估其重要性 (importance)，范围 0-1：
- 1.0：极其重要，用户明确强调，需要长期记住（如姓名、核心偏好）
- 0.7：重要，但不紧急（如一般偏好、项目信息）
- 0.5：一般性事实
- 0.3：可能有用的上下文

请以 JSON 数组格式返回，每个元素包含 content、type、importance 三个字段。
如果没有值得记住的信息，返回空数组 []。

只返回 JSON 数组，不要包含其他文字。"""


class MemoryManager:
    """长期记忆管理器

    在独立的 Chroma collection 中存储和检索跨会话记忆。
    """

    def __init__(
        self,
        persist_directory: str,
        collection_name: str = "long_term_memory",
        embedding_function=None,
        llm=None,
    ):
        """初始化长期记忆管理器

        Args:
            persist_directory: Chroma 持久化目录路径
            collection_name: Chroma collection 名称
            embedding_function: embedding 模型实例（复用 RAG 的，避免加载两份）
            llm: LLM 实例，用于自动提取记忆（与 Agent 共用 DeepSeek）
        """
        self.persist_directory = persist_directory
        self.collection_name = collection_name
        self.embedding_function = embedding_function
        self.llm = llm
        self._similarity_threshold = 0.85  # 去重相似度阈值

        # 确保目录存在
        os.makedirs(persist_directory, exist_ok=True)

        # 初始化 Chroma collection
        self._vectorstore: Optional[Chroma] = None
        self._init_vectorstore()

    def _init_vectorstore(self):
        """初始化 Chroma vectorstore，创建或加载指定 collection"""
        if os.path.isdir(self.persist_directory) and os.listdir(self.persist_directory):
            # 已有数据库，直接加载
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
            # 删除占位文档
            try:
                results = self._vectorstore.get()
                if results["ids"]:
                    self._vectorstore.delete(ids=results["ids"])
                    print("[MemoryManager] 已清理占位文档")
            except Exception:
                pass

    # ───────────────── 检索 ─────────────────

    def retrieve(
        self,
        query: str,
        k: int = 5,
        threshold: float = 0.6,
    ) -> List[Dict[str, Any]]:
        """语义检索相关长期记忆

        Args:
            query: 搜索查询文本
            k: 返回的最大记忆条数
            threshold: 相似度阈值 (0-1)，低于此值的记忆不会被返回。
                       注意：Chroma 默认使用 L2 距离，此处转换为余弦相似度估算。

        Returns:
            [{"id": "...", "content": "...", "metadata": {...}}, ...]
        """
        if self._vectorstore is None:
            return []

        try:
            # 使用 similarity_search_with_score 获取带分数的结果
            results = self._vectorstore.similarity_search_with_score(query, k=k)
        except Exception as e:
            print(f"[MemoryManager] 检索失败: {e}")
            return []

        memories = []
        for doc, score in results:
            # Chroma L2 距离：越小越相似。转换为 0-1 相似度（近似）
            # L2 距离通常在 [0, ~2] 范围（归一化 embedding 后），
            # similarity ≈ 1 / (1 + distance)
            similarity = 1.0 / (1.0 + score)

            if similarity < threshold:
                continue

            metadata = dict(doc.metadata)
            # 更新访问计数和最后访问时间
            self._touch_memory(doc.metadata.get("id", ""), metadata)

            memories.append({
                "id": doc.metadata.get("id", ""),
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
            # 更新 Chroma 中的 metadata
            self._vectorstore._collection.update(
                ids=[memory_id],
                metadatas=[metadata],
            )
        except Exception:
            pass  # 非关键操作，静默失败

    # ───────────────── 存储 ─────────────────

    def store(
        self,
        content: str,
        memory_type: str = "fact",
        importance: float = 0.7,
        session_id: str = "",
        entity_tags: Optional[List[str]] = None,
    ) -> Optional[str]:
        """存入一条长期记忆（带去重检查）

        Args:
            content: 记忆文本内容
            memory_type: 类型 — preference / fact / entity / summary
            importance: 重要性 0-1
            session_id: 来源会话 ID
            entity_tags: 实体标签列表

        Returns:
            记忆 ID（新创建或已更新的），去重失败时返回 None
        """
        if self._vectorstore is None:
            return None

        # 1. 去重检查：搜索最相似的已有记忆
        try:
            existing = self._vectorstore.similarity_search_with_score(content, k=1)
        except Exception:
            existing = []

        memory_id = f"mem_{uuid.uuid4().hex[:12]}"
        timestamp = _now_iso()

        if existing:
            doc, score = existing[0]
            similarity = 1.0 / (1.0 + score)
            if similarity >= self._similarity_threshold:
                # 相似度过高 → 合并到已有记忆
                existing_id = doc.metadata.get("id", "")
                if existing_id:
                    # 提升重要性（取最大值）、更新时间戳
                    old_importance = float(doc.metadata.get("importance", 0.5))
                    new_importance = max(old_importance, importance)
                    merged_content = self._merge_content(doc.page_content, content)

                    # 合并标签
                    old_tags = doc.metadata.get("entity_tags", "")
                    old_tag_list = [t.strip() for t in old_tags.split(",") if t.strip()] if old_tags else []
                    new_tag_list = old_tag_list + (entity_tags or [])
                    merged_tags = ",".join(list(set(new_tag_list)))

                    # 删除旧文档后重新添加（使用 vectorstore API 保证 embedding 维度一致）
                    try:
                        self._vectorstore.delete(ids=[existing_id])
                    except Exception:
                        pass

                    merged_metadata = {
                        "id": existing_id,
                        "type": memory_type,
                        "session_id": session_id or doc.metadata.get("session_id", ""),
                        "timestamp": timestamp,
                        "last_access": timestamp,
                        "importance": new_importance,
                        "access_count": int(doc.metadata.get("access_count", 0)),
                        "entity_tags": merged_tags,
                        "status": "active",
                    }
                    merged_doc = Document(page_content=merged_content, metadata=merged_metadata)
                    try:
                        self._vectorstore.add_documents([merged_doc])
                        print(f"[MemoryManager] 合并记忆 {existing_id} (similarity={similarity:.3f})")
                        return existing_id
                    except Exception as e:
                        print(f"[MemoryManager] 合并记忆失败: {e}")
                        return None

        # 2. 无重复 → 新增
        metadata = {
            "id": memory_id,
            "type": memory_type,
            "session_id": session_id,
            "timestamp": timestamp,
            "last_access": timestamp,
            "importance": importance,
            "access_count": 0,
            "entity_tags": ",".join(entity_tags) if entity_tags else "",
            "status": "active",
        }

        doc = Document(page_content=content, metadata=metadata)
        try:
            self._vectorstore.add_documents([doc])
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
        # 两者不同但相似，保留旧内容并追加新信息（去重词）
        return f"{old}；{new}"

    # ───────────────── 自动提取 ─────────────────

    def extract_and_store(
        self,
        messages: list,
        session_id: str = "",
    ) -> List[str]:
        """从对话消息中自动提取重要信息并存入长期记忆

        使用 LLM 分析对话内容，提取偏好/事实/实体/摘要，
        然后逐条调用 store() 存入（自动去重）。

        Args:
            messages: LangChain 消息列表 (HumanMessage, AIMessage, ToolMessage...)
            session_id: 来源会话 ID

        Returns:
            新增/更新的记忆 ID 列表
        """
        if self.llm is None:
            print("[MemoryManager] 未配置 LLM，跳过自动提取")
            return []

        # 1. 将消息序列化为可读文本
        conversation_text = self._serialize_messages(messages)
        if not conversation_text.strip():
            return []

        # 2. 调用 LLM 提取记忆
        try:
            from langchain_core.messages import SystemMessage, HumanMessage

            response = self.llm.invoke([
                SystemMessage(content=EXTRACTION_SYSTEM_PROMPT),
                HumanMessage(content=f"请分析以下对话并提取值得记住的信息：\n\n{conversation_text}"),
            ])
            raw_output = response.content if hasattr(response, "content") else str(response)
        except Exception as e:
            print(f"[MemoryManager] LLM 提取调用失败: {e}")
            return []

        # 3. 解析 JSON 输出
        try:
            # 清洗可能的 markdown 代码块包裹
            cleaned = raw_output.strip()
            if cleaned.startswith("```"):
                # 去掉 ```json ... ``` 包裹
                lines = cleaned.split("\n")
                cleaned = "\n".join(lines[1:-1]) if lines[-1].strip() == "```" else cleaned
                cleaned = cleaned.strip()
            items = json.loads(cleaned)
            if not isinstance(items, list):
                print(f"[MemoryManager] LLM 返回格式异常: {raw_output[:200]}")
                return []
        except json.JSONDecodeError as e:
            print(f"[MemoryManager] JSON 解析失败: {e}\n原始输出: {raw_output[:300]}")
            return []

        # 4. 逐条存储
        stored_ids = []
        for item in items:
            if not isinstance(item, dict):
                continue
            content = item.get("content", "").strip()
            if not content:
                continue

            memory_type = item.get("type", "fact")
            if memory_type not in ("preference", "fact", "entity", "summary"):
                memory_type = "fact"

            importance = float(item.get("importance", 0.5))
            importance = max(0.0, min(1.0, importance))  # clamp 到 [0, 1]

            mem_id = self.store(
                content=content,
                memory_type=memory_type,
                importance=importance,
                session_id=session_id,
            )
            if mem_id:
                stored_ids.append(mem_id)

        if stored_ids:
            print(f"[MemoryManager] 自动提取完成，共存储 {len(stored_ids)} 条记忆")
        return stored_ids

    def _serialize_messages(self, messages: list) -> str:
        """将 LangChain 消息列表序列化为可读对话文本"""
        lines = []
        for msg in messages:
            role = getattr(msg, "type", "unknown")
            content = getattr(msg, "content", "")

            # 跳过工具调用和工具返回的冗长内容
            if role == "tool":
                # 截断工具返回内容
                short_content = content[:200] + "..." if len(content) > 200 else content
                lines.append(f"[工具返回]: {short_content}")
                continue

            if role == "ai":
                # 跳过 AI 的工具调用部分，只保留文本回复
                tool_calls = getattr(msg, "tool_calls", None)
                if tool_calls:
                    tool_names = [tc.get("name", "unknown") for tc in tool_calls]
                    lines.append(f"[AI 调用工具]: {', '.join(tool_names)}")
                if content:
                    lines.append(f"AI: {content}")
                continue

            if role == "human":
                lines.append(f"用户: {content}")
                continue

            if role == "system":
                continue  # 系统提示不需要提取记忆

        return "\n".join(lines)

    # ───────────────── 维护操作 ─────────────────

    def forget(self, memory_id: str) -> bool:
        """删除指定记忆

        Args:
            memory_id: 要删除的记忆 ID

        Returns:
            是否删除成功
        """
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

            memories = []
            for i in range(len(results["ids"])):
                memories.append({
                    "id": results["ids"][i],
                    "content": results["documents"][i] if results["documents"] else "",
                    "metadata": results["metadatas"][i] if results["metadatas"] else {},
                })

            # 按时间戳降序排序
            memories.sort(
                key=lambda m: m["metadata"].get("timestamp", ""),
                reverse=True,
            )
            return memories[:n]
        except Exception as e:
            print(f"[MemoryManager] 获取最近记忆失败: {e}")
            return []

    def get_high_importance(self, threshold: float = 0.7) -> List[Dict[str, Any]]:
        """获取重要性高于阈值的高优先级记忆（适合注入 system prompt）"""
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

            memories.sort(
                key=lambda m: float(m["metadata"].get("importance", 0)),
                reverse=True,
            )
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
