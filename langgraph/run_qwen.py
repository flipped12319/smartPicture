# -*- coding: utf-8 -*-
"""启动 Qwen-VL 网关（8080）。

用法（在 langgraph/ 目录下）：
    python run_qwen.py
"""
from services.qwen_api import main

if __name__ == "__main__":
    main()
