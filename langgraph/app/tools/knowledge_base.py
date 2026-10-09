# app/tools/knowledge_base.py
"""知识库检索工具（RAG）。

只索引 docs 目录下的 .txt 文件，并做增量索引：
用「文件名 -> mtime」的记录判断哪些文件是新增或修改过的，避免每次启动都重新 embedding。
中文按字符切分，chunk_size 取 100/overlap 20 是为了适配短文档；
如果以后接入长文档，建议把 chunk_size 调大到 300~500。
"""
import json
import os

from langchain_chroma import Chroma
from langchain_community.document_loaders import TextLoader
from langchain_core.tools import tool
from langchain_text_splitters import RecursiveCharacterTextSplitter

from common.config import Config
from common.embeddings import get_embeddings

PERSIST_DIR = Config.CHROMA_PERSIST_DIR
DOCS_DIR = Config.DOCS_DIR
INDEX_TRACKER_FILE = os.path.join(PERSIST_DIR, "indexed_files.json")


def _get_docs_files() -> dict:
    """扫描 docs 目录，返回 {文件名: 绝对路径}（仅 .txt）"""
    if not os.path.isdir(DOCS_DIR):
        return {}
    return {
        f: os.path.join(DOCS_DIR, f)
        for f in os.listdir(DOCS_DIR)
        if f.endswith(".txt")
    }


def _load_indexed_files() -> dict:
    """加载已索引文件跟踪记录，返回 {文件名: mtime}"""
    if os.path.isfile(INDEX_TRACKER_FILE):
        with open(INDEX_TRACKER_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def _save_indexed_files(record: dict) -> None:
    """保存已索引文件跟踪记录"""
    os.makedirs(PERSIST_DIR, exist_ok=True)
    with open(INDEX_TRACKER_FILE, "w", encoding="utf-8") as f:
        json.dump(record, f, ensure_ascii=False, indent=2)


def create_vectorstore() -> Chroma:
    """创建或加载向量库，自动检测 docs 目录下的新文件/修改文件并增量索引"""
    embeddings = get_embeddings()
    current_files = _get_docs_files()
    indexed_record = _load_indexed_files()

    new_or_modified = {
        fname: fpath
        for fname, fpath in current_files.items()
        if fname not in indexed_record or indexed_record[fname] != os.path.getmtime(fpath)
    }
    deleted_files = set(indexed_record.keys()) - set(current_files.keys())

    # ── 情况1：数据库已存在 ──
    if os.path.isdir(PERSIST_DIR) and os.listdir(PERSIST_DIR):
        print("发现已有 Chroma 知识库，直接加载...")
        vectorstore = Chroma(persist_directory=PERSIST_DIR, embedding_function=embeddings)

        # 首次迁移：没有跟踪记录时，假设当前文件已全部索引，避免重复 embedding
        if not indexed_record:
            print("  首次运行增量索引（无历史记录），将当前文档标记为已索引。")
            _save_indexed_files({
                fname: os.path.getmtime(fpath) for fname, fpath in current_files.items()
            })
            return vectorstore

        if deleted_files:
            print(f"  ⚠ 检测到 {len(deleted_files)} 个文件已被删除: {deleted_files}")
        if not new_or_modified:
            print(f"  所有 {len(current_files)} 个文档已是最新，无需重新索引。")
        else:
            print(f"  检测到 {len(new_or_modified)} 个新文件/已修改文件，开始增量索引...")
            all_docs = []
            text_splitter = RecursiveCharacterTextSplitter(chunk_size=100, chunk_overlap=20)
            for fname, fpath in new_or_modified.items():
                print(f"    正在处理: {fname}")
                documents = TextLoader(fpath, encoding="utf-8").load()
                docs = text_splitter.split_documents(documents)
                print(f"    分割为 {len(docs)} 个片段")
                all_docs.extend(docs)
            if all_docs:
                vectorstore.add_documents(all_docs)
                print(f"  已添加 {len(all_docs)} 个新片段到向量数据库。")

        _save_indexed_files({
            fname: os.path.getmtime(fpath) for fname, fpath in current_files.items()
        })
        return vectorstore

    # ── 情况2：数据库不存在，从头创建 ──
    print("未找到 Chroma 知识库，开始创建...")
    if not current_files:
        raise FileNotFoundError(f"docs 目录下没有找到 .txt 文件: {DOCS_DIR}")

    all_docs = []
    text_splitter = RecursiveCharacterTextSplitter(chunk_size=100, chunk_overlap=20)
    for fname, fpath in current_files.items():
        print(f"  正在加载: {fname}")
        documents = TextLoader(fpath, encoding="utf-8").load()
        docs = text_splitter.split_documents(documents)
        print(f"  分割为 {len(docs)} 个片段")
        all_docs.extend(docs)

    print(f"共 {len(all_docs)} 个片段，正在创建向量数据库...")
    vectorstore = Chroma.from_documents(
        embedding=embeddings,
        persist_directory=PERSIST_DIR,
        documents=all_docs,
    )
    _save_indexed_files({
        fname: os.path.getmtime(fpath) for fname, fpath in current_files.items()
    })
    print("向量数据库创建成功！")
    return vectorstore


_vectorstore: Chroma | None = None
_retriever = None


def get_retriever():
    """惰性初始化知识库检索器（首次调用时才加载/构建向量库）"""
    global _vectorstore, _retriever
    if _retriever is None:
        _vectorstore = create_vectorstore()
        _retriever = _vectorstore.as_retriever(search_kwargs={"k": 3})
    return _retriever


@tool(description="""从知识库中检索系统相关的知识，当用户询问系统具有什么功能时应当运用此知识库进行回答。参数 query 应为用户的问题或从问题中提取的关键搜索词。""",
      response_format="content_and_artifact")
def retrieve_from_knowledge_base(query: str):
    """按语义检索系统知识库"""
    retrieved_docs = get_retriever().invoke(query)
    serialized = "\n\n".join(
        f"Source: {doc.metadata.get('source', 'Unknown')}\nContent: {doc.page_content}"
        for doc in retrieved_docs
    )
    return serialized, retrieved_docs
