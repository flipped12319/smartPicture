# -*- coding: utf-8 -*-
"""启动图片索引服务（8001）。

用法（在 langgraph/ 目录下）：
    python run_index.py

Java 后端会调用这个服务（pictureIndex.api.base-url）。
不启动它时：语义搜索会自动降级为关键词搜索，但「智能补充」会直接报错。
"""
from services.index_api import main

if __name__ == "__main__":
    main()
