# app/graph/build.py
"""图的构建与运行期持有。"""
from typing import Optional

from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.prebuilt import ToolNode

from ..tools import tools
from .nodes import call_model, should_continue
from .state import AgentState

# 编译后的图。由 init_graph() 在 FastAPI lifespan 里创建，
# 单独放一个模块级变量是为了避免 api 层与 main 层互相 import 形成环。
_compiled_app = None


def build_workflow():
    """构建 Agent 工作流（未编译）"""
    workflow = StateGraph(AgentState)
    workflow.add_node("agent", call_model)
    workflow.add_node("tools", ToolNode(tools))
    workflow.add_edge(START, "agent")
    workflow.add_conditional_edges(
        "agent",
        should_continue,
        {"tools": "tools", END: END},
    )
    workflow.add_edge("tools", "agent")
    return workflow


def init_graph() -> None:
    """编译图并持有

    注意：当前用的是内存 checkpointer（MemorySaver），进程重启会丢对话历史。
    要持久化就换成 langgraph.checkpoint.sqlite.SqliteSaver 并复用
    Config.SQLITE_DB_PATH（依赖已在 requirements.txt 里）。
    """
    global _compiled_app
    _compiled_app = build_workflow().compile(checkpointer=MemorySaver())


def get_graph():
    """获取编译后的图；未初始化时返回 None"""
    return _compiled_app


def read_prior_state(config: dict) -> dict:
    """读取该会话上一轮的状态

    跨轮保留的字段（picture_ids / search_space_target）需要在每次 invoke 时重新传进去：
    InjectedState 指向的字段一旦缺失会直接抛 KeyError，所以首轮必须给出默认值。
    """
    app = get_graph()
    if app is None:
        return {}
    try:
        snapshot = app.get_state(config)
        return dict(snapshot.values or {}) if snapshot else {}
    except Exception as e:
        print(f"[state] 读取历史状态失败，按首轮处理: {e}")
        return {}
