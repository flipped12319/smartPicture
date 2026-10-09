# -*- coding: utf-8 -*-
"""
图片向量索引服务

职责：
1. 按 url 下载图片，调用 Qwen-VL 生成名称、分类、简介与检索标签
2. 把「名称 + 简介 + 分类 + 标签」拼成文本并计算 embedding
3. 写入独立的 Chroma collection（与知识库 chroma_rag_db、长期记忆 chroma_memory_db 分开）

由 Java 后端通过 HTTP 调用（见 PictureIndexServiceImpl）：
    POST /picture/index    建立或更新索引（幂等）
    POST /picture/search   语义检索，只返回 pictureId 与相似度（正文由调用方回表 MySQL）
    POST /picture/remove   删除索引
    GET  /health           健康检查

设计要点：
- 向量库是「派生数据」，正文永远以 MySQL 为准，这里只存 pictureId + 权限过滤字段
- metadata 必须带 spaceId / reviewStatus，检索时先过滤再回表，避免越权
- 标签生成失败不算致命错误，会降级为「纯文本索引」
"""
import base64
import json
import os
import re
from typing import List, Optional, Union

import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from langchain_chroma import Chroma

from common.clients.qwen import chat_with_images
from common.config import Config
from common.embeddings import get_embeddings

# ──────────────────── 向量库初始化 ────────────────────

os.makedirs(Config.CHROMA_PICTURE_DIR, exist_ok=True)
vectorstore = Chroma(
    collection_name=Config.PICTURE_COLLECTION_NAME,
    persist_directory=Config.CHROMA_PICTURE_DIR,
    embedding_function=get_embeddings(),
)

app = FastAPI(title="Picture Index API")

INDEX_PROMPT = """请仔细观察这张图片，输出一个用于图片库检索与展示的 JSON 对象。

字段要求：
1. name：图片名称，6-16 个字，准确概括画面主体
2. category：分类，从「风景、人物、动物、植物、美食、建筑、交通、物品、艺术、其他」中选一个最贴切的
3. introduction：简介，30-80 个字，客观描述画面内容、构图与氛围
4. tags：检索标签数组，3-6 个，每个 2-6 个字，覆盖主体、场景、风格、颜色等不同维度，彼此不要重复或同义

只输出 JSON 对象，不要输出解释文字，不要使用 markdown 代码块。
示例：{"name":"海边日落","category":"风景","introduction":"傍晚的海岸线，橙红色夕阳落在海平面上，沙滩上有几道浅浅的脚印，整体氛围宁静温暖。","tags":["日落","海边","晚霞","风景"]}"""


# ──────────────────── 请求 / 响应模型 ────────────────────

class PictureIndexRequest(BaseModel):
    pictureId: Union[int, str]
    name: Optional[str] = ""
    introduction: Optional[str] = ""
    category: Optional[str] = ""
    url: Optional[str] = ""
    tags: Optional[List[str]] = []
    spaceId: Optional[Union[int, str]] = None
    userId: Optional[Union[int, str]] = None
    reviewStatus: Optional[int] = None


class PictureRemoveRequest(BaseModel):
    pictureIds: List[Union[int, str]]


class PictureIndexResponse(BaseModel):
    """Java 侧只会用 name/category/introduction/tags 去补「原本为空」的字段"""

    indexed: bool
    name: str = ""
    category: str = ""
    introduction: str = ""
    tags: List[str] = []
    message: str = ""


class PictureSearchRequest(BaseModel):
    """语义检索请求

    注意：这里只做「按相似度召回 id」，不下发图片正文，
    权限过滤与最终数据都以 MySQL 为准（由 Java 侧回表完成）。
    """

    query: str
    topK: int = 50
    # 空间过滤：公共图库传空串（与索引写入时的 metadata 保持一致）；不传表示不限空间
    spaceId: Optional[Union[int, str]] = None
    # 以下两个为可选过滤，Java 侧当前不传：审核状态交给 MySQL 过滤，避免召回被过度收窄
    reviewStatus: Optional[int] = None
    userId: Optional[Union[int, str]] = None
    # 最小相似度（0~1），低于该值的召回会被丢弃；不传表示不过滤
    minSimilarity: Optional[float] = None


class PictureSearchHit(BaseModel):
    pictureId: str
    # 1/(1+距离) 换算出的相似度，越大越相似
    similarity: float
    # Chroma 返回的原始距离，越小越相似（仅用于调试与阈值标定）
    distance: Optional[float] = None


class PictureSearchResponse(BaseModel):
    hits: List[PictureSearchHit] = []
    total: int = 0
    message: str = ""


# 语义检索的最低相似度：低于它的召回直接丢弃。
# 这个值是拿当前图库的真实数据量出来的（bge-small-zh 归一化向量 + Chroma L2 距离换算）：
#   相关查询的 top1 落在 0.51~0.61，噪声普遍落在 0.40~0.47，断层很清晰。
#   取 0.48 能把「量子物理与超导材料」这类完全无关的查询清空（返回空后调用方会降级为关键词搜索），
#   同时保住「赛车 → 跑车」这类近似相关的召回。
# 这里再兜一道默认值：即使调用方没传 minSimilarity，也不会退化成「返回最近的 N 张」。
# ⚠️ 更换嵌入模型后必须重新测量，不同模型的分数区间完全不同。
DEFAULT_MIN_SIMILARITY = float(os.getenv("PICTURE_SEARCH_MIN_SIMILARITY", "0.48"))


# ──────────────────── 工具函数 ────────────────────

def download_image(url: str) -> str:
    """下载图片并返回纯 base64（不含 data: 前缀）"""
    resp = requests.get(url, timeout=30)
    resp.raise_for_status()
    return base64.b64encode(resp.content).decode("utf-8")


def dedupe(items: List[str]) -> List[str]:
    """去重并保持顺序"""
    seen = set()
    result = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            result.append(item)
    return result


def parse_tags(answer: str, limit: int = 8) -> List[str]:
    """从模型回答里解析标签数组；解析失败时按常见分隔符兜底"""
    if not answer:
        return []
    text = answer.strip()
    # 去掉 markdown 代码块包裹
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    # 优先取第一个 JSON 数组
    match = re.search(r"\[.*?]", text, re.S)
    if match:
        try:
            data = json.loads(match.group(0))
            if isinstance(data, list):
                return dedupe([str(item).strip() for item in data if str(item).strip()])[:limit]
        except json.JSONDecodeError:
            pass
    # 兜底：按常见分隔符切分
    for sep in ["、", "，", ",", "；", ";", "\n"]:
        if sep in text:
            parts = [item.strip(" \"'[]") for item in text.split(sep)]
            return dedupe([item for item in parts if item])[:limit]
    return [text] if text else []


def parse_content(answer: str) -> dict:
    """从模型回答里解析出 name / category / introduction / tags"""
    empty = {"name": "", "category": "", "introduction": "", "tags": []}
    if not answer:
        return empty
    text = answer.strip()
    # 去掉 markdown 代码块包裹
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    # 优先取第一个 JSON 对象
    match = re.search(r"\{.*}", text, re.S)
    if match:
        try:
            data = json.loads(match.group(0))
        except json.JSONDecodeError:
            data = None
        if isinstance(data, dict):
            tags = data.get("tags")
            return {
                "name": str(data.get("name") or "").strip(),
                "category": str(data.get("category") or "").strip(),
                "introduction": str(data.get("introduction") or "").strip(),
                "tags": dedupe([str(t).strip() for t in tags if str(t).strip()])[:8]
                if isinstance(tags, list)
                else [],
            }
    # 兜底：至少把标签捞出来，其余字段留空（是否采用由 Java 侧决定）
    return {**empty, "tags": parse_tags(text)}


def generate_content(image_base64: str) -> dict:
    """调用 Qwen-VL 生成名称、分类、简介与标签"""
    data = chat_with_images(INDEX_PROMPT, [image_base64])
    answer = data.get("answer", "")
    print(f"[index] 模型原始回答: {answer[:200]}")
    return parse_content(answer)


def build_text(req: PictureIndexRequest, content: dict) -> str:
    """拼出用于 embedding 的文本

    字段缺失时优先采用模型补全后的内容，保证向量和最终入库的信息一致
    """
    parts = [
        req.name or content.get("name") or "",
        req.introduction or content.get("introduction") or "",
        req.category or content.get("category") or "",
    ]
    parts.extend(content.get("tags") or [])
    parts.extend(req.tags or [])
    return " ".join(part.strip() for part in parts if part and part.strip())


# ──────────────────── 接口 ────────────────────

@app.post("/picture/index", response_model=PictureIndexResponse)
def index_picture(req: PictureIndexRequest):
    """建立或更新图片的向量索引（幂等）"""
    if not req.url:
        raise HTTPException(status_code=400, detail="url 不能为空")

    # 1. 下载图片
    try:
        image_base64 = download_image(req.url)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"下载图片失败: {e}")

    # 2. 生成名称/分类/简介/标签（失败不致命，降级为纯文本索引）
    message = ""
    try:
        content = generate_content(image_base64)
    except Exception as e:
        content = {"name": "", "category": "", "introduction": "", "tags": []}
        message = f"内容生成失败，已降级为纯文本索引: {e}"
        print(f"[index] {message}")

    # 3. 写入向量库：先按 pictureId 删除旧记录，保证重复索引不会产生脏数据
    doc_id = str(req.pictureId)
    tags = content.get("tags") or []
    text = build_text(req, content)
    metadata = {
        "pictureId": doc_id,
        "spaceId": "" if req.spaceId is None else str(req.spaceId),
        "userId": "" if req.userId is None else str(req.userId),
        # -1 表示未知；检索时据此过滤掉未过审的公共图库图片
        "reviewStatus": -1 if req.reviewStatus is None else int(req.reviewStatus),
        "tags": ",".join(tags),
        "name": req.name or content.get("name") or "",
        "category": req.category or content.get("category") or "",
    }
    try:
        vectorstore.delete(where={"pictureId": doc_id})
    except Exception as e:
        # 首次索引时没有旧记录，删除报错可以忽略
        print(f"[index] 清理旧向量失败（首次索引可忽略）: {e}")
    try:
        vectorstore.add_texts(ids=[doc_id], texts=[text], metadatas=[metadata])
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"写入向量库失败: {e}")

    print(f"[index] 索引完成 pictureId={doc_id} name={metadata['name']} tags={tags} text={text[:80]}")
    return PictureIndexResponse(
        indexed=True,
        name=content.get("name") or "",
        category=content.get("category") or "",
        introduction=content.get("introduction") or "",
        tags=tags,
        message=message,
    )


def build_where(req: PictureSearchRequest) -> Optional[dict]:
    """把请求里的过滤条件翻译成 Chroma 的 where 语法"""
    conditions = []
    if req.spaceId is not None:
        # 索引写入时公共图库的 spaceId 存的是空串，这里保持一致
        conditions.append({"spaceId": str(req.spaceId)})
    if req.reviewStatus is not None:
        conditions.append({"reviewStatus": int(req.reviewStatus)})
    if req.userId is not None:
        conditions.append({"userId": str(req.userId)})
    if not conditions:
        return None
    if len(conditions) == 1:
        return conditions[0]
    return {"$and": conditions}


def to_similarity(distance: float) -> float:
    """把 Chroma 的距离换算成 0~1 的相似度，便于理解和设置阈值"""
    if distance is None:
        return 0.0
    return round(1.0 / (1.0 + max(float(distance), 0.0)), 4)


@app.post("/picture/search", response_model=PictureSearchResponse)
def search_picture(req: PictureSearchRequest):
    """按语义检索图片

    只返回 pictureId 与相似度，不返回图片正文：
    向量库是派生数据，可能滞后（图片被删、改名、审核状态变化），
    所以调用方必须拿 id 回表 MySQL 取正文并做权限校验。
    """
    query = (req.query or "").strip()
    if not query:
        return PictureSearchResponse(hits=[], total=0, message="查询文本为空")

    top_k = max(1, min(int(req.topK or 50), 200))
    # 调用方没传就用默认下限；传 0 表示显式要求不过滤
    floor = req.minSimilarity if req.minSimilarity is not None else DEFAULT_MIN_SIMILARITY
    where = build_where(req)
    try:
        results = vectorstore.similarity_search_with_score(query, k=top_k, filter=where)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"向量检索失败: {e}")

    hits: List[PictureSearchHit] = []
    for doc, distance in results:
        picture_id = (doc.metadata or {}).get("pictureId")
        if not picture_id:
            continue
        similarity = to_similarity(distance)
        if floor > 0 and similarity < floor:
            continue
        hits.append(
            PictureSearchHit(
                pictureId=str(picture_id),
                similarity=similarity,
                distance=round(float(distance), 4),
            )
        )

    # 日志带上最高分与阈值：这是判断「阈值该不该调」最直接的依据
    best = to_similarity(results[0][1]) if results else 0.0
    print(f"[index] 检索完成 query={query!r} where={where} "
          f"命中={len(hits)}/{len(results)} 最高分={best:.4f} 阈值={floor}")
    return PictureSearchResponse(hits=hits, total=len(hits), message=f"minSimilarity={floor}")


@app.post("/picture/remove")
def remove_picture(req: PictureRemoveRequest):
    """删除图片的向量索引"""
    removed = 0
    for picture_id in req.pictureIds or []:
        doc_id = str(picture_id)
        try:
            vectorstore.delete(where={"pictureId": doc_id})
            removed += 1
        except Exception as e:
            print(f"[index] 删除向量失败 pictureId={doc_id}: {e}")
    print(f"[index] 已删除 {removed}/{len(req.pictureIds or [])} 条向量索引")
    return {"removed": removed}


@app.get("/health")
def health():
    return {"status": "ok"}


def main():
    """启动服务（8001）"""
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=Config.INDEX_API_PORT,
        # 超限直接拒绝：每条索引都要下载图片 + 调多模态模型，堆积只会一起变慢
        limit_concurrency=Config.INDEX_MAX_CONCURRENCY,
    )


if __name__ == "__main__":
    main()
