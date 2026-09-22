#!/usr/bin/env python3
"""最小 MCP 客户端（**只用标准库**）：模拟外部 agent 连 Simos 的 MCP 口。

为什么自写而不是用官方 SDK：本机 Python 3.12 是 externally-managed 且**没装** `mcp` 包，
本仓既有 e2e 先例（`.superpowers/sdd/2026-09-22-llm-integration/e2e/e2e.py`）同样走标准库。

传输：MCP **streamable HTTP** —— POST JSON-RPC 2.0；响应可能是 `application/json`
或 `text/event-stream`（SSE，取最后一条 `data:` 行）。会话 id 从 `Mcp-Session-Id` 响应头取。

用法：
    python3 mcp_client.py <url> init                # 初始化并打印服务器信息
    python3 mcp_client.py <url> tools               # 列出工具（名字 + 描述）
    python3 mcp_client.py <url> call <tool> '<json-args>'
    python3 mcp_client.py <url> repl                # 交互：每行一个 `<tool> <json>`（保持同一会话）

URL 也可用环境变量 `SIMOS_MCP_URL`（默认 `http://127.0.0.1:5715/mcp`）。
"""

import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_URL = os.environ.get("SIMOS_MCP_URL", "http://127.0.0.1:5715/mcp")
# 服务器不认时会把支持的版本回给我们，按需在 initialize 里换（见 README 的实测记录）
PROTOCOL_VERSION = os.environ.get("SIMOS_MCP_PROTOCOL", "2025-06-18")

# 本机环境里可能有代理变量，会把 loopback 请求带走 —— 与既有 e2e 同款处理
os.environ["no_proxy"] = "*"
os.environ["NO_PROXY"] = "*"
for _k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
    os.environ.pop(_k, None)


class McpError(RuntimeError):
    pass


class McpClient:
    def __init__(self, url=DEFAULT_URL, timeout=300):
        self.url = url
        self.timeout = timeout
        self.session_id = None
        self._next_id = 0

    # ── 传输 ─────────────────────────────────────────────────────────────
    def _post(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(self.url, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                sid = resp.headers.get("Mcp-Session-Id")
                if sid:
                    self.session_id = sid
                raw = resp.read().decode("utf-8", "replace")
                ctype = resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            raise McpError(f"HTTP {exc.code}: {detail[:600]}") from None
        if not raw.strip():
            return None
        if "text/event-stream" in ctype:
            return self._parse_sse(raw)
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise McpError(f"响应不是 JSON（content-type={ctype}）: {raw[:300]}") from exc

    @staticmethod
    def _parse_sse(raw):
        """SSE：逐行取 `data:`，最后一条是本次响应（前面的可能是通知/心跳）。"""
        out = None
        for line in raw.splitlines():
            line = line.strip()
            if line.startswith("data:"):
                payload = line[5:].strip()
                if payload:
                    out = json.loads(payload)
        return out

    def _rpc(self, method, params=None):
        self._next_id += 1
        msg = {"jsonrpc": "2.0", "id": self._next_id, "method": method}
        if params is not None:
            msg["params"] = params
        resp = self._post(msg)
        if resp is None:
            raise McpError(f"{method}: 空响应")
        if "error" in resp:
            raise McpError(f"{method}: {json.dumps(resp['error'], ensure_ascii=False)}")
        return resp.get("result")

    def _notify(self, method, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self._post(msg)

    # ── MCP 方法 ─────────────────────────────────────────────────────────
    def initialize(self):
        result = self._rpc(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "simos-e2e-client", "version": "0.1"},
            },
        )
        self._notify("notifications/initialized")
        return result

    def list_tools(self):
        return self._rpc("tools/list", {}).get("tools", [])

    def call_tool(self, name, arguments):
        """返回 (isError, text)：MCP 的 tools/call 结果里 content[] 是文本块。"""
        result = self._rpc("tools/call", {"name": name, "arguments": arguments})
        if result is None:
            return True, "<空结果>"
        text = "\n".join(
            part.get("text", "")
            for part in result.get("content", [])
            if isinstance(part, dict) and part.get("type") == "text"
        )
        return bool(result.get("isError")), text


def _cmd_init(client):
    info = client.initialize()
    print(json.dumps(info, ensure_ascii=False, indent=2))
    print(f"session_id = {client.session_id}")
    return 0


def _cmd_tools(client):
    client.initialize()
    tools = client.list_tools()
    print(f"工具数 = {len(tools)}")
    for tool in tools:
        desc = (tool.get("description") or "").replace("\n", " ")[:90]
        print(f"  {tool['name']:<44} {desc}")
    return 0


def _cmd_call(client, name, args_json):
    client.initialize()
    args = json.loads(args_json) if args_json else {}
    is_error, text = client.call_tool(name, args)
    print(f"[{'ERROR' if is_error else 'OK'}] {name}")
    print(text)
    return 1 if is_error else 0


def _cmd_repl(client):
    client.initialize()
    print("每行一个 `<tool> <json>`；空行退出。")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            break
        try:
            name, _, rest = line.partition(" ")
            is_error, text = client.call_tool(name, json.loads(rest) if rest.strip() else {})
            print(f"[{'ERROR' if is_error else 'OK'}] {name}\n{text}\n")
        except Exception as exc:  # noqa: BLE001 —— REPL 不该被单条打挂
            print(f"[FAIL] {exc}\n")
    return 0


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    url, cmd = argv[1], argv[2]
    client = McpClient(url)
    if cmd == "init":
        return _cmd_init(client)
    if cmd == "tools":
        return _cmd_tools(client)
    if cmd == "call":
        return _cmd_call(client, argv[3], argv[4] if len(argv) > 4 else "")
    if cmd == "repl":
        return _cmd_repl(client)
    print(f"未知子命令: {cmd}")
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
