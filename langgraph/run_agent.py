# -*- coding: utf-8 -*-
"""启动 Agent 服务（8000）。

用法（在 langgraph/ 目录下）：
    python run_agent.py

只是个薄壳，为了让启动命令保持简单；也可以用 `python -m app.main`，效果相同。
"""
from app.main import main

if __name__ == "__main__":
    main()
