# -*- coding: utf-8 -*-
"""并发压测脚本 —— 用来确认限流阈值是否合理、改动前后是否真的变快。

用法（在 langgraph/ 目录下执行）：

    # 1) 健康检查（免费、不碰模型），用来验证限流与并发上限
    python scripts/loadtest.py health -n 200 -c 50

    # 2) 搜索（走 Java 8123，需要登录 token）
    python scripts/loadtest.py search -n 200 -c 20 --token <JWT>

    # 3) 对话（走 Agent 8000）—— ⚠️ 每次都是真实模型调用，别压大
    python scripts/loadtest.py chat -n 10 -c 5 --token <JWT> --space-id <id>

参数：
    -n/--total        总请求数（默认 50）
    -c/--concurrency  并发数（默认 10）
    --token           登录 token（搜索与对话必填）
    --space-id        私人图库 id（对话必填，可传空串）
    --url             覆盖目标地址（默认按 kind 选择）
    --query           搜索用的关键词（默认「埃菲尔铁塔」）

输出：成功/失败数、各状态码分布、p50/p95/p99 延迟、吞吐。
"""
import argparse
import asyncio
import statistics
import sys
import time
from collections import Counter

import httpx

DEFAULT_URLS = {
    "health": "http://localhost:8000/health",
    "search": "http://localhost:8123/api/list/page/vo/cache",
    "chat": "http://localhost:8000/chat",
}


def build_request(kind: str, url: str, token: str, space_id: str, query: str, seq: int) -> dict:
    """构造一次请求（headers / json）"""
    if kind == "health":
        return {"method": "GET", "url": url, "headers": {}, "json": None}

    headers = {"Authorization": f"Bearer {token}"}

    if kind == "search":
        return {
            "method": "POST",
            "url": url,
            "headers": headers,
            "json": {
                "current": 1,
                "pageSize": 20,
                "searchText": query,
                "nullSpaceId": True,
            },
        }

    # chat：每次用不同的 session_id，避免互相干扰彼此的对话状态
    return {
        "method": "POST",
        "url": url,
        "headers": headers,
        "json": {
            "session_id": f"loadtest-{seq}",
            "message": "你好",
            "token": token,
            "spaceId": space_id,
        },
    }


async def one_request(client: httpx.AsyncClient, spec: dict, sem: asyncio.Semaphore,
                      results: list):
    """发一次请求并记录结果（状态码 + 耗时）"""
    async with sem:
        started = time.perf_counter()
        try:
            resp = await client.request(
                spec["method"], spec["url"], headers=spec["headers"], json=spec["json"]
            )
            status = resp.status_code
        except Exception as e:
            status = f"ERR:{type(e).__name__}"
        results.append((status, (time.perf_counter() - started) * 1000))


async def run(kind: str, url: str, total: int, concurrency: int,
              token: str, space_id: str, query: str) -> int:
    if kind != "health" and not token:
        print("错误：搜索与对话接口需要 --token")
        return 2

    sem = asyncio.Semaphore(concurrency)
    results: list = []
    limits = httpx.Limits(max_connections=concurrency * 2, max_keepalive_connections=concurrency)

    wall_started = time.perf_counter()
    async with httpx.AsyncClient(timeout=180.0, limits=limits) as client:
        tasks = [
            one_request(client, build_request(kind, url, token, space_id, query, i), sem, results)
            for i in range(total)
        ]
        await asyncio.gather(*tasks)
    wall = time.perf_counter() - wall_started

    statuses = Counter(status for status, _ in results)
    latencies = sorted(ms for _, ms in results)

    def pct(p: float) -> float:
        if not latencies:
            return 0.0
        idx = min(len(latencies) - 1, int(len(latencies) * p))
        return latencies[idx]

    ok_count = sum(count for status, count in statuses.items() if status == 200)

    print(f"\n=== 结果：{kind}  total={total} concurrency={concurrency} ===")
    print(f"  成功 200 : {ok_count}/{total}")
    print(f"  状态分布 : {dict(statuses)}")
    print(f"  延迟(ms) : p50={pct(0.50):.1f}  p95={pct(0.95):.1f}  p99={pct(0.99):.1f}  "
          f"max={latencies[-1] if latencies else 0:.1f}")
    print(f"  平均延迟 : {statistics.mean(latencies) if latencies else 0:.1f} ms")
    print(f"  总耗时   : {wall:.2f}s   吞吐 = {total / wall:.1f} 请求/秒")

    if any(str(s).startswith("429") for s in statuses):
        print("  提示：出现 429 说明触发了限流。若比例很高，说明并发上限设得过低；")
        print("        若延迟 p95 也很高，说明上限还是偏高（请求在排队而不是被拒绝）。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Smart Picture 并发压测")
    parser.add_argument("kind", choices=["health", "search", "chat"])
    parser.add_argument("-n", "--total", type=int, default=50)
    parser.add_argument("-c", "--concurrency", type=int, default=10)
    parser.add_argument("--token", default="")
    parser.add_argument("--space-id", default="")
    parser.add_argument("--url", default="")
    parser.add_argument("--query", default="埃菲尔铁塔")
    args = parser.parse_args()

    url = args.url or DEFAULT_URLS[args.kind]
    if args.kind == "chat" and not args.url:
        print("⚠️  对话压测会把请求直接打到 Agent(8000)，每次都是真实模型调用。")
    return asyncio.run(run(args.kind, url, args.total, args.concurrency,
                           args.token, args.space_id, args.query))


if __name__ == "__main__":
    sys.exit(main())
