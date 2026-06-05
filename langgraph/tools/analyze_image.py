import requests
from langchain_core.tools import tool

from session_utils import current_session_id, get_session


@tool(description="""基于多模态大模型（Qwen-VL）对图片进行分析、推理、比较、描述等操作。
当你需要理解用户提供的图片内容、回答关于图片的问题、比较多张图片的异同、识别图片中的物体/文字/场景时，调用本工具。

⚠️ 重要：每次调用必须基于用户当前消息中的图片和问题，绝对禁止使用历史对话中的旧图片或旧问题。
如果用户只提供了图片但没有明确的问题，默认问题为“请描述这张图片的内容”。

参数说明：
- question: 你对图片提出的问题或指令，例如“这张图里有什么？”、“请比较这两张图片的异同”、“识别图中的文字”等。
- image_base64_list: 图片的 Base64 编码字符串列表（纯编码，不含 data:image/ 前缀）。顺序与问题中的图片对应。
- enable_reasoning: 是否返回模型的思考过程（默认 False，只返回最终答案）。如果为 True，返回结果会包含思考过程。

返回：模型的文本回答（以及可选的思考过程）。
""")
def analyze_images(
        question: str,
        enable_reasoning: bool = False
) -> str:
    """调用 Qwen-VL 图片分析接口"""
    print("使用了qwen工具")

    session_id = current_session_id.get()
    if not session_id:
        return "错误：无法获取会话信息，请重试。"

    sess = get_session(session_id)
    image_base64_list = sess.get("image_base64_list", [])
    if not image_base64_list:
        return "没有待上传的图片。请先发送图片（base64格式），然后再次调用本工具。"

    # 1. 参数校验
    if not question or not question.strip():
        return "错误：question 参数不能为空。"
    if not image_base64_list:
        return "错误：image_base64_list 不能为空，请提供至少一张图片的 Base64。"

    # 2. 构造请求 payload（接口要求纯 base64，无需 data: 前缀）

    payload = {
        "text": question.strip(),
        "images": image_base64_list  # 已经是纯 base64 字符串
    }
    print("payload ",payload)

    # 3. 调用 API
    try:
        resp = requests.post(
            "http://localhost:8080/chat/image",  # 你的 API 地址
            json=payload,
            timeout=120  # 模型推理可能较慢，设置长超时
        )
        if resp.status_code != 200:
            return f"图片分析服务异常，HTTP {resp.status_code}: {resp.text}"

        data = resp.json()
        # 根据你的 API 返回格式提取内容
        # 假设返回格式: {"answer": "...", "reasoning": "..."}
        answer = data.get("answer", "")
        reasoning = data.get("reasoning", "")

        if not answer:
            return "模型未返回有效回答。"

        if enable_reasoning and reasoning:
            return f"【思考过程】\n{reasoning}\n\n【最终回答】\n{answer}"
        else:
            return answer

    except requests.exceptions.Timeout:
        return "图片分析超时，请稍后重试。"
    except requests.exceptions.ConnectionError:
        return "无法连接到图片分析服务，请检查服务是否启动（http://localhost:8080）。"
    except Exception as e:
        return f"图片分析失败：{str(e)}"