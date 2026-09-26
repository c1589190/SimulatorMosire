"""S1 阶段 1 验收探针：种三国 → 推进到固定 tick → **逐格全量 dump**。

判据 I1.1「逐格人口、逐格劳动供给、逐格劳动配额与改型前逐值相同」的做法：
**不改型前后各跑一次同一个脚本**，产物两份 JSON，再用 `s1a_diff.py` 归一化词表后逐字节比。

用法:
    SIMOS_GUI=5857 SIMOS_MCP=5755 python3 s1a_probe.py seed
    SIMOS_GUI=5857 SIMOS_MCP=5755 python3 s1a_probe.py snapshot 240 /tmp/s1a-after.json

★ 端口走环境变量：基线实例与现役实例要**同时**可比，各占一套端口。
★ 收工用 PID 停（AGENT.md §8.1），curl/urllib 一律绕开本机代理。
"""

import json
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MCP = f"http://127.0.0.1:{os.environ.get('SIMOS_MCP', '5755')}/mcp"
GUI = f"http://127.0.0.1:{os.environ.get('SIMOS_GUI', '5857')}"
NATIONS = ["德意志第二帝国", "奥斯特马克侯国", "霍赫兰伯国"]
POOL = 16

# ★ 绕开本机代理（否则 127.0.0.1 被拦）
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _post(body, sid=None, timeout=1800):
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if sid:
        h["Mcp-Session-Id"] = sid
    req = urllib.request.Request(MCP, data=json.dumps(body).encode(), headers=h)
    r = _opener.open(req, timeout=timeout)
    return dict(r.headers), r.read().decode()


def handshake():
    h, _ = _post(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "s1a_probe", "version": "1"},
            },
        }
    )
    sid = h.get("Mcp-session-id") or h.get("Mcp-Session-Id")
    _post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    return sid


def call(sid, name, args):
    _, raw = _post(
        {"jsonrpc": "2.0", "id": 9, "method": "tools/call", "params": {"name": name, "arguments": args}},
        sid,
    )
    d = [ln[6:] for ln in raw.splitlines() if ln.startswith("data: ")]
    o = json.loads(d[-1])
    r = o.get("result", {})
    t = " ".join(c.get("text", "") for c in r.get("content", []))
    if r.get("isError"):
        raise RuntimeError(f"{name}: {t[:400]}")
    return t


def get(path):
    return json.load(_opener.open(GUI + path, timeout=300))


def timeline(sid):
    tl = json.loads(call(sid, "simos.timeline.revisions", {"branch": "main"}))
    nodes = tl.get("nodes") if isinstance(tl, dict) else tl
    return max(n["revision"] for n in nodes), max(n["tick"] for n in nodes)


def all_hexes(sid):
    seen = {}
    for n in NATIONS:
        for h in json.loads(call(sid, "simos.map.region", {"regionId": n}))["hexes"]:
            seen[(h["q"], h["r"])] = None
    return sorted(seen)


def snapshot(sid, hexes):
    """逐格取 social（含人口 + 劳动块）与 economy 两份读数。"""

    def one(h):
        q, r = h
        return f"{q},{r}", {
            "social": get(f"/api/social/population?q={q}&r={r}"),
            "economy": get(f"/api/economy/hex?q={q}&r={r}"),
        }

    with ThreadPoolExecutor(max_workers=POOL) as ex:
        return dict(ex.map(one, hexes))


def main():
    cmd = sys.argv[1]
    sid = handshake()
    if cmd == "seed":
        for n in NATIONS:
            print(f"  {n}: " + call(sid, "simos.worldgen.initialize", {"nation": n, "army": True, "dryRun": False})[:90])
        return

    if cmd == "snapshot":
        target, out = int(sys.argv[2]), sys.argv[3]
        rev, tick = timeline(sid)
        print(f"当前 rev={rev} tick={tick} ⇒ 目标 tick={target}")
        if target > tick:
            t0 = time.time()
            call(sid, "simos.advance", {"branch": "main", "expectedRevision": rev, "from": tick, "to": target})
            print(f"  推进完成 {time.time() - t0:.1f}s")
        rev, tick = timeline(sid)
        hexes = all_hexes(sid)
        t0 = time.time()
        snap = snapshot(sid, hexes)
        print(f"  dump {len(snap)} 格（{time.time() - t0:.1f}s） rev={rev} tick={tick}")
        json.dump(
            {"revision": rev, "tick": tick, "hexes": snap},
            open(out, "w"),
            ensure_ascii=False,
            sort_keys=True,
        )
        print(f"  → {out}")
        return

    raise SystemExit(f"未知子命令: {cmd}")


if __name__ == "__main__":
    main()
