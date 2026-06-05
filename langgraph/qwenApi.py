# server.py
import os
import base64
from typing import List, Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from openai import OpenAI

app = FastAPI(title="Multi-Image VL API")

# 初始化 OpenAI 客户端（兼容 DashScope）
client = OpenAI(
    api_key=os.getenv("DASHSCOPE_API_KEY", "sk-5b983da384d14cae8c572bfb149cdb19"),
    base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"
)

# ------------------ 请求/响应模型 ------------------
class ImageChatRequest(BaseModel):
    text: str                     # 用户文本指令
    images: List[str]             # Base64 编码的图片列表（可含 data:image/... 前缀）

class ImageChatResponse(BaseModel):
    reasoning: str                # 思考过程（如果模型返回）
    answer: str                   # 最终回复内容

# ------------------ 辅助函数 ------------------
def prepare_data_url(base64_str: str) -> str:
    """
    将用户传入的 Base64 字符串转换为标准的 data URL。
    如果已包含 'data:image/' 前缀则直接返回，否则自动添加 image/jpeg 前缀。
    """
    if base64_str.startswith("data:image/"):
        return base64_str
    # 尝试根据头部特征猜测图片格式（简单实现）
    # 常见图片的 Base64 前几个字符特征（解码后）
    # 更健壮的做法可用 imghdr 或用户传递 mime，此处简化，默认 jpeg
    return f"data:image/jpeg;base64,{base64_str}"

def call_vlm(text: str, image_base64_list: List[str]) -> tuple[str, str]:
    """
    调用多模态模型，返回 (思考过程, 最终回答)
    """
    # 构建 content 数组
    content = []
    for img_b64 in image_base64_list:
        data_url = prepare_data_url(img_b64)
        content.append({
            "type": "image_url",
            "image_url": {"url": data_url}
        })
    content.append({"type": "text", "text": text})

    messages = [{"role": "user", "content": content}]

    # 发起流式请求（为了同时获取思考过程和回答）
    completion = client.chat.completions.create(
        model="qwen3-vl-flash",
        messages=messages,
        stream=True,
        extra_body={
            'enable_thinking': True,
            "thinking_budget": 81920
        }
    )

    reasoning = ""
    answer = ""
    is_answering = False

    for chunk in completion:
        # 忽略 usage 信息（非 choices）
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        # 处理思考内容
        if hasattr(delta, 'reasoning_content') and delta.reasoning_content:
            reasoning += delta.reasoning_content
        else:
            # 首次遇到普通内容时开始收集回答
            if delta.content and not is_answering:
                is_answering = True
            if delta.content:
                answer += delta.content

    return reasoning, answer

# ------------------ 接口 ------------------
@app.post("/chat/image", response_model=ImageChatResponse)
async def chat(request: ImageChatRequest):
    """
    接收用户文本和 Base64 图片列表，返回模型思考过程和最终回复。
    """
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="text field cannot be empty")
    if not request.images:
        raise HTTPException(status_code=400, detail="at least one image is required")

    try:
        reasoning, answer = call_vlm(request.text, request.images)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Model invocation failed: {str(e)}")

    return ImageChatResponse(reasoning=reasoning, answer=answer)

# ------------------ 启动入口（方便直接运行）------------------
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)