import base64
import io
import os
import re
import uuid

import requests
from PIL import Image
from langchain_core.tools import tool
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate

from session_utils import current_session_id, get_session
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Image as RLImage, PageBreak

# ---------- 中文字体配置（根据你的环境修改）----------
FONT_PATH = "C:/Windows/Fonts/simhei.ttf"  # 请改为实际路径
if os.path.exists(FONT_PATH):
    try:
        pdfmetrics.registerFont(TTFont('ChineseFont', FONT_PATH))
        FONT_NAME = 'ChineseFont'
        print("中文字体加载成功")
    except Exception as e:
        print(f"字体加载失败: {e}")
        FONT_NAME = 'Helvetica'
else:
    print("字体文件不存在，使用默认字体")
    FONT_NAME = 'Helvetica'
# 创建段落样式
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(
    name='Chinese',
    fontName=FONT_NAME,
    fontSize=12,
    leading=16,
    alignment=0,  # 左对齐
    spaceAfter=6,
))
# 资源挂载位置
STATIC_DIR = "./static"
os.makedirs(STATIC_DIR, exist_ok=True)
BASE_URL = "http://localhost:8000"
@tool(description="""这是一个pdf生成工具,将故事文本和图片合成一个 PDF 文件，返回可直接在浏览器下载的 Data URL 链接。
这个工具不负责生成故事内容，只负责排版和生成 PDF。

参数说明：
- story_text: 故事文本（字符串）
- title: PDF 标题（可选，默认“故事创作”）
- filename: 下载时的文件名（可选，默认“story.pdf”）

返回：一个 Markdown 下载链接，用户点击即可下载 PDF。
""")
def create_pdf_from_story(story_text: str, title: str = "故事创作", filename: str = "story.pdf") -> str:
    if not story_text.strip():
        return "错误：故事文本不能为空。"
    print("使用了pdf创作工具")
    session_id = current_session_id.get()
    if not session_id:
        return "错误：无法获取会话信息，请重试。"
    sess = get_session(session_id)
    image_base64_list = sess.get("image_base64_list", [])
    if not image_base64_list:
        return "错误：没有待处理的图片。"

    # 文件名生成
    file_id = uuid.uuid4().hex
    base_name = os.path.splitext(filename)[0]
    safe_title = "".join(c for c in title if c.isalnum() or c in (' ', '_', '-')).replace(' ', '_')
    final_filename = f"{safe_title}_{file_id}.pdf"
    filepath = os.path.join(STATIC_DIR, final_filename)

    # 创建文档
    doc = SimpleDocTemplate(filepath, pagesize=A4, title=title)
    story = []

    # 标题页
    story.append(Paragraph(title, styles['Title']))
    story.append(Spacer(1, 12*mm))

    # 清理故事文本（移除 emoji 等非常规字符）
    clean_text = re.sub(r'[\U00010000-\U0010FFFF]', ' ', story_text)
    # 按空行分割段落
    paragraphs = [p.strip() for p in clean_text.split('\n') if p.strip()]
    if not paragraphs:
        paragraphs = ["（无内容）"]

    # 为每张图片配一段文字
    for idx, b64_str in enumerate(image_base64_list):
        # 获取对应段落（若超出则取最后一段）
        para_text = paragraphs[idx] if idx < len(paragraphs) else paragraphs[-1]

        # 添加图片（修正：使用 BytesIO 文件对象）
        try:
            img_data = base64.b64decode(b64_str)
            pil_img = Image.open(io.BytesIO(img_data))
            # 计算缩放尺寸
            max_width = 400
            w, h = pil_img.size
            aspect = h / w
            draw_width = min(max_width, w)
            draw_height = draw_width * aspect

            # 将 PIL Image 保存为 BytesIO（统一转为 PNG 避免格式问题）
            img_bytesio = io.BytesIO()
            pil_img.save(img_bytesio, format='PNG')
            img_bytesio.seek(0)

            # RLImage 接受文件对象
            rl_img = RLImage(img_bytesio, width=draw_width, height=draw_height)
            story.append(rl_img)
            story.append(Spacer(1, 6 * mm))
        except Exception as e:
            story.append(Paragraph(f"图片 {idx + 1} 加载失败: {str(e)}", styles['Chinese']))
            story.append(Spacer(1, 6 * mm))

        # 添加文字段落
        story.append(Paragraph(para_text, styles['Chinese']))
        story.append(Spacer(1, 12*mm))

    # 若还有多余的文字段落（图片数量少于段落数）
    for para in paragraphs[len(image_base64_list):]:
        story.append(Paragraph(para, styles['Chinese']))
        story.append(Spacer(1, 12*mm))

    # 生成 PDF
    doc.build(story)
    download_url = f"{BASE_URL}/static/{final_filename}"
    return f"✅ PDF 已生成。\n\n[📄 点击下载《{title}》]({download_url})"