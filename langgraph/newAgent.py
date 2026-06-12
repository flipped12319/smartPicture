# -*- coding: utf-8 -*-
import os
import re

import json
from contextlib import asynccontextmanager
from typing import Optional, List, Annotated, TypedDict, Literal, Any

from langgraph.checkpoint.memory import MemorySaver

from session_utils import current_session_id, get_session
from tools.analyze_image import analyze_images
from tools.upload_image import upload_image
from tools.create_pdf_from_story import create_pdf_from_story
from tools.search_image import search_images
from tools.delete_images import delete_images
from tools.remember_info import remember_info
from tools.recall_info import recall_info


from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from dotenv import load_dotenv

from langchain_community.document_loaders import TextLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_chroma import Chroma
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain_core.messages import HumanMessage
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import StateGraph, END, START
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from fastapi.staticfiles import StaticFiles
from app_config import Config
from memory_manager import MemoryManager, set_memory_manager
load_dotenv()


# # 资源挂载位置
STATIC_DIR = "./static"
os.makedirs(STATIC_DIR, exist_ok=True)
BASE_URL = "http://localhost:8000"

# ==================== 向量数据库（RAG）====================
# 基于脚本所在目录计算绝对路径，避免 CWD 不同导致的路径错误
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_DIR = os.path.dirname(_BASE_DIR)
PERSIST_DIR = os.path.join(_PROJECT_DIR, "chroma_rag_db")
DOCS_DIR = os.path.join(_PROJECT_DIR, "docs")
INDEX_TRACKER_FILE = os.path.join(PERSIST_DIR, "indexed_files.json")

model_name = "BAAI/bge-small-zh-v1.5"
model_kwargs = {'device': 'cpu'}
encode_kwargs = {'normalize_embeddings': True}
embeddings = HuggingFaceEmbeddings(
    model_name=model_name,
    model_kwargs=model_kwargs,
    encode_kwargs=encode_kwargs,
)


def _get_docs_files() -> dict:
    """扫描 docs 目录，返回 {文件名: 绝对路径} 的字典（仅 .txt 文件）"""
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


def create_vectorstore():
    """创建或加载向量数据库。自动检测 docs 目录下的新文件/修改文件并增量索引。"""
    current_files = _get_docs_files()
    indexed_record = _load_indexed_files()

    # 找出新增或被修改的文件
    new_or_modified = {
        fname: fpath
        for fname, fpath in current_files.items()
        if fname not in indexed_record
        or indexed_record[fname] != os.path.getmtime(fpath)
    }
    deleted_files = set(indexed_record.keys()) - set(current_files.keys())

    # --- 情况1：数据库已存在 ---
    if os.path.isdir(PERSIST_DIR) and os.listdir(PERSIST_DIR):
        print("发现已有 Chroma 数据库，直接加载...")
        vectorstore = Chroma(
            persist_directory=PERSIST_DIR, embedding_function=embeddings
        )

        # 首次迁移：没有跟踪记录时，假设当前文件已全部索引，避免重复 embedding
        if not indexed_record:
            print("  首次运行增量索引（无历史记录），将当前文档标记为已索引。")
            new_record = {
                fname: os.path.getmtime(fpath)
                for fname, fpath in current_files.items()
            }
            _save_indexed_files(new_record)
            return vectorstore

        if deleted_files:
            print(f"  ⚠ 检测到 {len(deleted_files)} 个文件已被删除: {deleted_files}")
        if not new_or_modified:
            print(f"  所有 {len(current_files)} 个文档已是最新，无需重新索引。")
        else:
            print(f"  检测到 {len(new_or_modified)} 个新文件/已修改文件，开始增量索引...")
            all_docs = []
            text_splitter = RecursiveCharacterTextSplitter(
                chunk_size=100, chunk_overlap=20
            )
            for fname, fpath in new_or_modified.items():
                print(f"    正在处理: {fname}")
                loader = TextLoader(fpath, encoding="utf-8")
                documents = loader.load()
                docs = text_splitter.split_documents(documents)
                print(f"    分割为 {len(docs)} 个片段")
                all_docs.extend(docs)

            if all_docs:
                vectorstore.add_documents(all_docs)
                print(f"  已添加 {len(all_docs)} 个新片段到向量数据库。")

        # 更新跟踪记录
        new_record = {
            fname: os.path.getmtime(fpath)
            for fname, fpath in current_files.items()
        }
        _save_indexed_files(new_record)
        return vectorstore

    # --- 情况2：数据库不存在，从头创建 ---
    print("未找到 Chroma 数据库，开始创建...")
    if not current_files:
        raise FileNotFoundError(f"docs 目录下没有找到 .txt 文件: {DOCS_DIR}")

    all_docs = []
    text_splitter = RecursiveCharacterTextSplitter(chunk_size=100, chunk_overlap=20)
    for fname, fpath in current_files.items():
        print(f"  正在加载: {fname}")
        loader = TextLoader(fpath, encoding="utf-8")
        documents = loader.load()
        docs = text_splitter.split_documents(documents)
        print(f"  分割为 {len(docs)} 个片段")
        all_docs.extend(docs)

    print(f"共 {len(all_docs)} 个片段，正在创建向量数据库...")
    vectorstore = Chroma.from_documents(
        embedding=embeddings,
        persist_directory=PERSIST_DIR,
        documents=all_docs,
    )

    # 保存跟踪记录
    new_record = {
        fname: os.path.getmtime(fpath) for fname, fpath in current_files.items()
    }
    _save_indexed_files(new_record)
    print("向量数据库创建成功！")
    return vectorstore


vectorstore = create_vectorstore()
retriever = vectorstore.as_retriever(search_kwargs={"k": 3})

# ---------- 工具函数 ----------

@tool(description="""从知识库中检索系统相关的知识，当用户询问系统具有什么功能时应当运用此知识库进行回答。参数 query 应为用户的问题或从问题中提取的关键搜索词。""", response_format="content_and_artifact")
def retrieve_from_knowledge_base(query: str):
    retrieved_docs = retriever.invoke(query)
    serialized = "\n\n".join(
        f"Source: {doc.metadata.get('source', 'Unknown')}\nContent: {doc.page_content}"
        for doc in retrieved_docs
    )
    return serialized, retrieved_docs


tools = [analyze_images, retrieve_from_knowledge_base, upload_image, create_pdf_from_story, search_images, delete_images, remember_info, recall_info]
tool_node = ToolNode(tools)

# ==================== LLM 和 Agent 图 ====================
class AgentState(TypedDict):
    messages: Annotated[list, add_messages]

llm = ChatOpenAI(
    model="deepseek-chat",
    temperature=0.7,
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com/v1"
)

# ==================== 长期记忆管理器（Chroma）====================
memory_manager = MemoryManager(
    persist_directory=Config.CHROMA_MEMORY_DIR,
    collection_name=Config.MEMORY_COLLECTION_NAME,
    embedding_function=embeddings,
    llm=llm,
)
set_memory_manager(memory_manager)
print(f"[MemoryManager] 长期记忆库已就绪，当前记忆数: {memory_manager.get_memory_count()}")

llm_with_tools = llm.bind_tools(tools)

def call_model(state: AgentState):
    """LLM 节点：调用模型，注入系统提示 + 长期记忆"""

    from langchain_core.messages import SystemMessage, HumanMessage

    messages = state["messages"]
    # 如果消息列表中没有 system 消息，则在本次调用中前置一条
    # （仅作用于本次推理，不会持久化到 state，避免重复累积）
    if not messages or getattr(messages[0], "type", None) != "system":
        # ── 1. 从长期记忆中检索相关信息 ──
        memories_text = ""
        # 提取用户最新消息作为搜索查询
        user_query = ""
        for msg in reversed(messages):
            if isinstance(msg, HumanMessage):
                user_query = msg.content or ""
                break

        if user_query:
            try:
                memories = memory_manager.retrieve(
                    user_query,
                    k=Config.MEMORY_RETRIEVAL_K,
                    threshold=Config.MEMORY_SIMILARITY_THRESHOLD,
                )
                if memories:
                    memories_text = "\n【长期记忆 —— 与当前对话相关的历史信息】\n"
                    for mem in memories:
                        mem_type = mem["metadata"].get("type", "fact")
                        importance = float(mem["metadata"].get("importance", 0.5))
                        stars = "★" * max(1, int(importance * 3))
                        memories_text += f"- [{mem_type}] {stars} {mem['content']}\n"
                    memories_text += "\n"
            except Exception as e:
                print(f"[LTM] 检索失败: {e}")

        # ── 2. 构建完整 system prompt ──
        system_prompt = (
            "你是一个智能助手，具备长期记忆能力，可以使用多种工具来帮助用户。\n\n"
            "⚠️ 重要规则：\n"
            "1. 当用户的问题涉及到有关系统功能的内容时，"
            "你必须优先调用 retrieve_from_knowledge_base 工具进行检索，然后严格基于检索结果回答。"
            "不要依赖你自己的内部知识，因为知识库中的信息才是权威来源。\n"
            "2. 当用户告知你的个人偏好、重要信息或值得记住的事实时，"
            "你应该使用 remember_info 工具将其存入长期记忆，以便未来对话中可以回忆。\n"
            "3. 当你需要回忆用户之前告诉过你的事情或任何历史对话中的信息时，"
            "使用 recall_info 工具从长期记忆中检索。\n\n"
            + memories_text +
            "可用工具：\n"
            "- remember_info: 将重要信息存入长期记忆（用户偏好、事实、实体等）\n"
            "- recall_info: 从长期记忆中检索历史信息\n"
            "- analyze_images: 分析用户上传的图片内容\n"
            "- upload_image: 上传图片到图库\n"
            "- search_images: 在图库中搜索图片\n"
            "- delete_images: 删除最近一次搜索到的图片（仅限私人图库）\n"
            "- create_pdf_from_story: 将故事文本和图片合成为 PDF 文件"
        )
        messages = [SystemMessage(content=system_prompt)] + list(messages)

    response = llm_with_tools.invoke(messages)
    return {"messages": [response]}

def should_continue(state: AgentState) -> Literal["tools", END]:
    last_message = state["messages"][-1]
    if hasattr(last_message, "tool_calls") and last_message.tool_calls:
        return "tools"
    return END

def build_workflow():
    workflow = StateGraph(AgentState)
    workflow.add_node("agent", call_model)
    workflow.add_node("tools", tool_node)
    workflow.add_edge(START, "agent")
    workflow.add_conditional_edges(
        "agent",
        should_continue,
        {"tools": "tools", END: END}
    )
    workflow.add_edge("tools", "agent")
    return workflow

# ==================== FastAPI 生命周期与全局变量 ====================
checkpointer = None
compiled_app = None


@asynccontextmanager

async def lifespan(app: FastAPI):
    global checkpointer, compiled_app
    checkpointer = MemorySaver()  # 直接实例化，不需要上下文管理器
    workflow = build_workflow()
    compiled_app = workflow.compile(checkpointer=checkpointer)
    print("Agent 已加载，API 服务启动")
    yield
    print("服务已关闭")

app = FastAPI(title="LangGraph Agent with Batch Image Upload", lifespan=lifespan)
# 2. 挂载静态文件目录（这一行必须写在 app 创建之后）
app.mount("/static", StaticFiles(directory="./static"), name="static")

# ==================== 请求/响应模型 ====================
class ChatRequest(BaseModel):
    session_id: str = Field(..., description="会话ID")
    message: str = Field(..., description="用户输入的文本")
    images: Optional[List[str]] = Field(None, description="可选的图片 base64 列表（支持多张）")
    token: str = Field(..., description="token字段")
    spaceId: str = Field(..., description="私人图库的id")

class ChatResponse(BaseModel):
    reply: str = Field(..., description="Agent 的回复文本")
    session_id: str = Field(..., description="会话ID")
    image_urls: Optional[List[str]] = Field(None, description="可选的图片 url 列表（支持多张）")

# ==================== 辅助函数：清理消息中的 base64 占位符 ====================
def clean_message_with_images(message: str, images: Optional[List[str]]) -> tuple[str, Optional[List[str]]]:
    """移除消息中可能存在的 base64 字符串，并返回清理后的文本和完整的图片列表"""
    if not images:
        return message, None
    # 移除所有 base64 块
    pattern = r'data:image/\w+;base64,[A-Za-z0-9+/=]+'
    cleaned = re.sub(pattern, '', message).strip()
    if not cleaned:
        cleaned = "用户发来多张图片"
    else:
        cleaned += " [多张图片]"
    return cleaned, images

# ==================== API 端点 ====================
@app.post("/chat", response_model=ChatResponse)
def chat_endpoint(req: ChatRequest):
    if compiled_app is None:
        raise HTTPException(status_code=503, detail="Agent 未就绪")

    session_id = req.session_id
    # 设置上下文变量，供工具函数获取当前会话 ID
    current_session_id.set(session_id)

    # 1. 清理消息，获取图片列表
    cleaned_message, image_list = clean_message_with_images(req.message, req.images)

    # 2. 存储图片到会话队列
    sess = get_session(session_id)
    sess["spaceId"]=req.spaceId
    sess["token"] = req.token
    # 清空模型回复的图片列表
    sess["image_urls"]=[]


    if image_list:
        # 直接替换为新的列表（可根据需求改为追加）
        # 这个列表在调用完上传图片工具后就会清空
        sess["pending_base64_list"] = image_list
        #这个猎鸟调用完上传图片工具后不会清空
        sess["image_base64_list"] = image_list
    # 如果本次没有图片，保留原有队列（支持多轮补充信息）

    # 3. 调用 Agent Graph
    config = {"configurable": {"thread_id": session_id}}
    try:
        final_state = compiled_app.invoke(
            {"messages": [HumanMessage(content=cleaned_message)]},
            config=config
        )
        last_message = final_state["messages"][-1]
        reply = last_message.content if hasattr(last_message, "content") else str(last_message)

        # ── 4. 自动提取长期记忆（异步不阻塞响应）──
        if Config.MEMORY_AUTO_EXTRACT:
            try:
                memory_manager.extract_and_store(
                    messages=final_state["messages"],
                    session_id=session_id,
                )
            except Exception as e:
                print(f"[LTM] 自动提取记忆失败: {e}")

        return ChatResponse(reply=reply, session_id=session_id, image_urls=sess["image_urls"])
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Agent 执行失败: {str(e)}")
    finally:
        # 清理上下文变量，避免影响其他请求
        current_session_id.set("")

@app.get("/health")
def health():
    return {"status": "ok"}

# ==================== 运行入口 ====================
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)