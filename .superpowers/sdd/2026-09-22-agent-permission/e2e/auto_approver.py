#!/usr/bin/env python3
"""自动批准器（验收用）：轮询 AgentLib 的审批面，对每一条待批按 `APPROVE_SESSION` 放行。

为什么需要它：`sd.RunDecision` 与决策人自己出的令（`sd.IssueDirective`）**都是敏感写**，
各自进一次审批（T11C 实测：一轮里不止一条）。验收要经 MCP 驱动整条链，就得有人（本脚本）代答。

`APPROVE_SESSION` 的作用域是 `(callerKey=桶, classKey=工具)` ⇒ 对**同一工具**的后续调用免批；
内外层是两个不同工具，故各自批一次。**不**用 `APPROVE_ONCE`（每条都批，拖慢验收）。

用法：python3 auto_approver.py [审批端口，缺省 5713] [--by <署名>]
"""

import json
import sys
import time
import urllib.error
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 5713
BY = "gm:acceptance"
if "--by" in sys.argv:
    BY = sys.argv[sys.argv.index("--by") + 1]

BASE = f"http://127.0.0.1:{PORT}"
for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
    import os

    os.environ.pop(k, None)
import os  # noqa: E402  （放在 pop 之后只为可读性）

os.environ["no_proxy"] = "*"
os.environ["NO_PROXY"] = "*"

seen = set()


def _get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return json.loads(r.read().decode())


def _post(path, body):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, r.read().decode()


print(f"[approver] watching {BASE}/api/approvals, by={BY}", flush=True)
while True:
    try:
        pending = _get("/api/approvals").get("pending", [])
        for row in pending:
            pid = row.get("id")
            if pid in seen:
                continue
            try:
                status, body = _post(
                    f"/api/approvals/{pid}",
                    # ★ AgentLib 的线格式：decision 只收 "approve"|"deny"；作用域是**独立字段** scope
                    # （"once"|"session"）—— 实测：写枚举名 `APPROVE_SESSION` 会被判 400
                    {"decision": "approve", "scope": "session", "by": BY},
                )
                seen.add(pid)
                print(
                    f"[approver] {row.get('tool')} id={pid} -> {status} {body[:120]}", flush=True
                )
            except urllib.error.HTTPError as exc:
                # 409 = 已被别人决议（幂等保护）——记下不再重试
                seen.add(pid)
                print(f"[approver] {row.get('tool')} id={pid} -> HTTP {exc.code}（视为已决议）", flush=True)
    except Exception as exc:  # noqa: BLE001 —— 服务未就绪/重启都不该把批准器打挂
        print(f"[approver] 轮询失败（继续）: {exc}", flush=True)
    time.sleep(0.4)
