# -*- coding: utf-8 -*-
import os

from langchain_community.document_loaders import TextLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter

os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'

from dotenv import load_dotenv
from typing import Annotated, TypedDict, Literal

from langchain_chroma import Chroma
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain_core.messages import HumanMessage, AIMessage, ToolMessage
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.graph import StateGraph, END, START
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode

# 1. 加载环境变量
load_dotenv()


# 2. 定义状态（State）
class AgentState(TypedDict):
    messages: Annotated[list, add_messages]  # 自动追加消息

# ============================
# 2.5. 准备向量数据库（Chroma）
# ============================
PERSIST_DIR = "../chroma_rag_db"   # Chroma 持久化目录

#embedding模型
model_name = "BAAI/bge-small-zh-v1.5"  # ⭐ 在这里替换你想用的模型
model_kwargs = {'device': 'cpu'}          # 强制使用 CPU，如果有 NVIDIA 显卡可改为 'cuda:0'
encode_kwargs = {'normalize_embeddings': True} # BGE 系列模型建议归一化[reference:13][reference:14]
embeddings = HuggingFaceEmbeddings(
    model_name=model_name,
    model_kwargs=model_kwargs,
    encode_kwargs=encode_kwargs,
)


def create_vectorstore():
    """加载文档，分割，存入 Chroma（如果目录已存在则直接加载）"""
    if os.path.exists(PERSIST_DIR) and os.listdir(PERSIST_DIR):
        print("发现已有 Chroma 数据库，直接加载...")
        return Chroma(persist_directory=PERSIST_DIR, embedding_function=embeddings)

    print("未找到 Chroma 数据库，开始创建...")
    file_path = "../docs/journey_to_the_west.txt"
    loader = TextLoader(file_path, encoding='utf-8')
    documents = loader.load()
    text_splitter = RecursiveCharacterTextSplitter(
        chunk_size=100,
        chunk_overlap=20,
        length_function=len,
    )
    docs = text_splitter.split_documents(documents)
    print(f"   文档已分割为 {len(docs)} 个片段。")
    vectorstore = Chroma.from_documents(
        embedding=embeddings,
        persist_directory=PERSIST_DIR,
        documents=docs,
    )
    return vectorstore
vectorstore = create_vectorstore()
retriever = vectorstore.as_retriever(search_kwargs={"k": 3})
    # # 存入 Chroma
    # vectorstore = Chroma.from_documents(
    #     documents=doc_splits,
    #     embedding=embeddings,
    #     persist_directory=PERSIST_DIR
    # )
    # print(f"Chroma 数据库已创建，持久化目录: {PERSIST_DIR}")
    # return vectorstore
# 3. 定义工具（简单示例：模拟天气查询）
@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气情况"""
    # 这里模拟返回结果，你可以换成真实 API
    if "上海" in city:
        return "上海今天晴天，气温25度，湿度60%。"
    elif "北京" in city:
        return "北京今天多云，气温22度，空气质量良好。"
    else:
        return f"{city}的天气：晴转多云，气温18~26度。"

@tool(response_format="content_and_artifact")
def retrieve_from_knowledge_base(query: str):
    """从知识库中检索与问题相关的信息。当你需要外部知识来回答用户问题时，请使用此工具。"""
    retrieved_docs = retriever.invoke(query)
    serialized = "\n\n".join(
        f"Source: {doc.metadata.get('source', 'Unknown')}\nContent: {doc.page_content}"
        for doc in retrieved_docs
    )
    return serialized, retrieved_docs


tools = [get_weather,retrieve_from_knowledge_base]
tool_node = ToolNode(tools)  # LangGraph 预置的工具节点

# 4. 初始化 LLM（需要支持工具调用）
load_dotenv()
llm = ChatOpenAI(
    model="deepseek-chat",
    temperature=0.7,
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com/v1"
)
# llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
# 将工具绑定到模型，这样模型可以生成 tool_calls
llm_with_tools = llm.bind_tools(tools)


# 5. 定义节点函数

def call_model(state: AgentState):
    """LLM 节点：根据当前消息列表调用模型"""
    messages = state["messages"]
    response = llm_with_tools.invoke(messages)
    # 返回的消息会被 add_messages 自动追加到 state["messages"] 中
    return {"messages": [response]}


def should_continue(state: AgentState) -> Literal["tools", END]:
    """条件边：判断下一步是继续调用工具还是结束"""
    last_message = state["messages"][-1]
    # 如果 AI 消息中包含工具调用请求，则去执行工具节点
    if hasattr(last_message, "tool_calls") and last_message.tool_calls:
        return "tools"
    # 否则直接结束
    return END


# 6. 构建图（StateGraph）
workflow = StateGraph(AgentState)

# 添加节点
workflow.add_node("agent", call_model)
workflow.add_node("tools", tool_node)

# 设置入口点
workflow.add_edge(START, "agent")

# 添加条件边：agent 节点后根据 should_continue 决定去 tools 还是 END
workflow.add_conditional_edges(
    "agent",
    should_continue,
    {
        "tools": "tools",
        END: END
    }
)

# 添加普通边：tools 节点执行后必须回到 agent 节点（让模型看到工具结果）
workflow.add_edge("tools", "agent")

# 7. 编译图（可以传入 checkpointer 实现记忆，这里先不用）
# 构建连接字符串。如果密码包含特殊字符（如 @, & 等），需要进行 URL 编码，详见注释



# ========== 运行测试 ==========
if __name__ == "__main__":
    DATABASE_URL = "postgresql://langgraph_user:123456@localhost:5432/langgraph_db"
    with PostgresSaver.from_conn_string(DATABASE_URL) as checkpointer:
        checkpointer.setup()
        app = workflow.compile(checkpointer=checkpointer)

        print("🤖 LangGraph Agent 已启动！输入 'exit' 退出\n")
        config = {"configurable": {"thread_id": "my_session"}}
        while True:
            user_input = input("👤 你: ")
            if user_input.lower() in ["exit", "quit"]:
                print("👋 再见！")
                break
            final_state = app.invoke(
                {"messages": [HumanMessage(content=user_input)]},
                config=config
            )
            last_message = final_state["messages"][-1]
            print(f"🤖 Agent: {last_message.content}\n")