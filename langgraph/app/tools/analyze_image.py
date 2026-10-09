import requests
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from common.config import Config
from common.clients.qwen import chat_with_images

from ..session_store import get_analysis_images, session_id_from_config


@tool(description="""基于多模态大模型（Qwen-VL）对图片进行分析、推理、比较、描述等操作。
当你需要理解用户提供的图片内容、回答关于图片的问题、比较多张图片的异同、识别图片中的物体/文字/场景时，调用本工具。

⚠️ 重要：每次调用必须基于用户当前消息中的图片和问题，绝对禁止使用历史对话中的旧图片或旧问题。
如果用户只提供了图片但没有明确的问题，默认问题为“请描述这张图片的内容”。

参数说明：
- question: 你对图片提出的问题或指令，例如“这张图里有什么？”、“请比较这两张图片的异同”、“识别图中的文字”等。
- enable_reasoning: 是否返回模型的思考过程（默认 False，只返回最终答案）。如果为 True，返回结果会包含思考过程。

返回：模型的文本回答（以及可选的思考过程）。
""")
def analyze_images(
        # 注入参数放最前面（不能有默认值，否则会被当成普通参数暴露给模型）
        config: RunnableConfig,
        question: str,
        enable_reasoning: bool = False,
) -> str:
    """调用 Qwen-VL 图片分析接口（图片取自当前会话的待分析队列）"""
    print("使用了qwen工具")

    session_id = session_id_from_config(config)
    if not session_id:
        return "错误：无法获取会话信息，请重试。"

    image_base64_list = get_analysis_images(session_id)
    if not image_base64_list:
        return "没有待上传的图片。请先发送图片（base64格式），然后再次调用本工具。"

    if not question or not question.strip():
        return "错误：question 参数不能为空。"

    try:
        data = chat_with_images(question.strip(), image_base64_list)
        answer = data.get("answer", "")
        reasoning = data.get("reasoning", "")

        if not answer:
            return "模型未返回有效回答。"

        if enable_reasoning and reasoning:
            return f"【思考过程】\n{reasoning}\n\n【最终回答】\n{answer}"
        return answer

    except requests.exceptions.Timeout:
        return "图片分析超时，请稍后重试。"
    except requests.exceptions.HTTPError as e:
        return f"图片分析服务异常：{e}"
    except requests.exceptions.ConnectionError:
        return f"无法连接到图片分析服务，请检查服务是否启动（{Config.QWEN_IMAGE_API_URL}）。"
    except Exception as e:
        return f"图片分析失败：{str(e)}"
