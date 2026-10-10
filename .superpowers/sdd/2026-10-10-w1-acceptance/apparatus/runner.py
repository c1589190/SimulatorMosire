#!/usr/bin/env python3
"""W1 验收装置（只读）：起 shaded jar（快照运行器）→ 等 GUI 就绪 → 走 HTTP 推进 → 取 checkpoint → 收进程。

用法：
  python3 runner.py --jar <shaded.jar> --tag <tag> --store <dir> --gui-port N [--days 360] [--segments 1]

行为：
  · store 一律**先删除再起**（§一.11：从创世新 store 起，旧档不看）；
  · 推进经 POST /api/advance（载荷 {branch, expectedRevision, from, to}），每段计时（墙钟，含服务端结算）；
  · 落盘读数取 store 里的 checkpoint 信封（完整状态树）+ revisions 表（Revision Δ 的真数）；
  · 结束后 SIGTERM 收进程。
产物：<out>/<tag>.result.json（含各段耗时、revision 行、checkpoint 路径与 md5）。
"""
import argparse
import hashlib
import json
import os
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

RUN_SHADED = "/home/cna/SimulatorMosire/tools/run-shaded.sh"


def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%H:%M:%S"), msg), flush=True)


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def port_free(port):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def get_json(url, timeout=60):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode())


def post_json(url, payload, timeout=7200):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    t0 = time.time()
    code = None
    body = ""
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            code = r.status
            body = r.read().decode()
    except urllib.error.HTTPError as e:
        code = e.code
        body = e.read().decode()
    return code, body, time.time() - t0


def revisions(store):
    db = os.path.join(store, "simos.db")
    con = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
    try:
        rows = con.execute(
            "SELECT branch, revision, tick, command_type, length(changeset_json)"
            " FROM revisions ORDER BY branch, revision"
        ).fetchall()
    finally:
        con.close()
    return [
        {"branch": r[0], "revision": r[1], "tick": r[2], "commandType": r[3], "changesetBytes": r[4]}
        for r in rows
    ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--store", required=True)
    ap.add_argument("--gui-port", type=int, required=True)
    ap.add_argument("--mcp-port", type=int, default=None)
    ap.add_argument("--approval-port", type=int, default=None)
    ap.add_argument("--days", type=int, default=360)
    ap.add_argument("--segments", type=int, default=1)
    ap.add_argument("--world", default="small-world")
    ap.add_argument("--out", default="/tmp/w1acc/results")
    ap.add_argument("--config", default="/tmp/w1acc/shell.json")
    ap.add_argument("--keep-store", action="store_true")
    ap.add_argument("--resume", action="store_true", help="在已有 store 上从当前 tick 续跑（配合 --keep-store）")
    ap.add_argument("--readouts", action="store_true", help="额外抓 HTTP 读口（逐格 economy/hex 等）")
    ap.add_argument("--trace", action="store_true", help="JAVA_TOOL_OPTIONS 开 TRACE 档（两侧同档才可比）")
    args = ap.parse_args()

    gui = args.gui_port
    mcp = args.mcp_port or (gui + 1)
    approval = args.approval_port or (gui + 2)
    os.makedirs(args.out, exist_ok=True)
    log_path = os.path.join(args.out, "%s.log" % args.tag)

    for p in (gui, mcp, approval):
        if not port_free(p):
            log("端口 %d 被占用 ⇒ 拒绝启动（不 kill 别人的服务）" % p)
            sys.exit(3)

    if not args.keep_store and os.path.exists(args.store):
        shutil.rmtree(args.store)
    os.makedirs(args.store, exist_ok=True)

    jar_md5 = md5_file(args.jar)
    cmd = [
        "bash",
        RUN_SHADED,
        args.jar,
        "--store",
        args.store,
        "--world=%s" % args.world,
        "--config",
        args.config,
        "--gui-port",
        str(gui),
        "--mcp-port",
        str(mcp),
        "--approval-port",
        str(approval),
    ]
    log("起服务: %s" % " ".join(cmd))
    t_start = time.time()
    lf = open(log_path, "w")
    env = dict(os.environ)
    if args.trace:
        env["JAVA_TOOL_OPTIONS"] = "-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE"
    proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, cwd="/tmp/w1acc", env=env)

    result = {
        "tag": args.tag,
        "jar": os.path.abspath(args.jar),
        "jarMd5": jar_md5,
        "store": os.path.abspath(args.store),
        "guiPort": gui,
        "days": args.days,
        "segments": args.segments,
        "config": args.config,
        "log": log_path,
        "startedAt": datetime.now().isoformat(timespec="seconds"),
        "advances": [],
        "readySeconds": None,
        "genesisCheckpointSeconds": None,
        "checkpoints": [],
        "tick": None,
    }
    try:
        # ── 等 GUI 就绪（world 已在启动期 bootstrap）────────────────────────────
        deadline = time.time() + 300
        ready = None
        while time.time() < deadline:
            if proc.poll() is not None:
                log("进程提前退出 rc=%s，看日志 %s" % (proc.returncode, log_path))
                raise SystemExit(4)
            try:
                code, body = get_json("http://127.0.0.1:%d/api/economy/overview" % gui, timeout=10)
                if code == 200 and body.get("activated") is True:
                    ready = body
                    break
            except Exception:
                pass
            time.sleep(0.5)
        if ready is None:
            log("等 GUI 就绪超时")
            raise SystemExit(5)
        result["readySeconds"] = round(time.time() - t_start, 3)
        result["genesisTick"] = ready.get("tick")
        log("GUI 就绪（%.2fs），genesis tick=%s" % (result["readySeconds"], ready.get("tick")))

        # 创世可能在 GUI 监听之后才完成 ⇒ 等 revisions 表出现创世行（不靠睡眠猜）
        rev_rows = []
        gdeadline = time.time() + 300
        while time.time() < gdeadline:
            rev_rows = revisions(args.store)
            if rev_rows:
                break
            time.sleep(0.2)
        result["revisionsAfterGenesis"] = rev_rows
        log("创世后 revisions: %s" % rev_rows)
        head = rev_rows[-1]["revision"]

        # ── 分段推进 ────────────────────────────────────────────────────────
        step = args.days // args.segments
        frm = 0
        if args.resume:
            frm = rev_rows[-1]["tick"]
            step = (args.days - frm) // args.segments
            log("续跑：从 tick %d 推进到 %d（%d 段）" % (frm, args.days, args.segments))
        for i in range(args.segments):
            to = frm + step if i < args.segments - 1 else args.days
            payload = {"branch": "main", "expectedRevision": head, "from": frm, "to": to}
            log("POST /api/advance %s" % payload)
            code, body, dt = post_json("http://127.0.0.1:%d/api/advance" % gui, payload)
            entry = {
                "from": frm,
                "to": to,
                "httpStatus": code,
                "seconds": round(dt, 3),
                "response": body[:2000],
            }
            result["advances"].append(entry)
            log("  -> HTTP %s  %.3fs  %s" % (code, dt, body.strip()[:200]))
            if code != 200:
                raise SystemExit(6)
            head = json.loads(body)["ref"]["revision"]
            frm = to

        # ── 落盘读数 ────────────────────────────────────────────────────────
        time.sleep(0.5)
        result["revisions"] = revisions(args.store)
        head_row = result["revisions"][-1]
        result["headRevision"] = head_row["revision"]
        result["tick"] = head_row["tick"]
        result["revisionDelta"] = head_row["revision"] - result["revisions"][0]["revision"]
        cp_dir = os.path.join(args.store, "checkpoints")
        if os.path.isdir(cp_dir):
            for root, _dirs, files in os.walk(cp_dir):
                for name in sorted(files):
                    p = os.path.join(root, name)
                    rel = os.path.relpath(p, args.store)
                    result["checkpoints"].append(
                        {"path": rel, "bytes": os.path.getsize(p), "md5": md5_file(p)}
                    )
        log("revisions=%s" % result["revisions"])
        log("checkpoints=%s" % result["checkpoints"])

        if args.readouts:
            readouts = collect_readouts(gui, args.store, result["headRevision"])
            ro_path = os.path.join(args.out, "%s.readouts.json" % args.tag)
            with open(ro_path, "w") as f:
                json.dump(readouts, f, ensure_ascii=False, indent=1, sort_keys=True)
            result["readoutsPath"] = ro_path
            log("读口落盘 %s" % ro_path)
    finally:
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                log("SIGTERM 后 60s 未退 ⇒ SIGKILL")
                proc.kill()
                proc.wait(timeout=30)
        lf.close()

    out_path = os.path.join(args.out, "%s.result.json" % args.tag)
    with open(out_path, "w") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, sort_keys=True)
    log("结果落盘 %s" % out_path)
    print(json.dumps({k: result[k] for k in
                      ("tag", "jarMd5", "readySeconds", "advances", "revisionDelta",
                       "headRevision", "tick", "checkpoints")},
                     ensure_ascii=False, indent=2))


def collect_readouts(gui, store, revision):
    """按格抓只读读数（economy/hex + economy/ownership）：格清单取自 map 模块的 checkpoint。"""
    import glob

    cps = glob.glob(os.path.join(store, "checkpoints", "main", "*.json"))
    if not cps:
        return {"error": "没有 checkpoint 可读格清单"}
    cp = max(cps, key=lambda p: int(os.path.basename(p)[:-5]))
    with open(cp) as f:
        env = json.load(f)
    map_payload = json.loads(env["modules"]["map"])
    map_inner = map_payload.get("map", map_payload)
    hexes = sorted(map_inner.get("hexes", {}).keys())
    out = {"checkpointUsed": os.path.basename(cp), "hexes": hexes, "perHex": {}}
    for hx in hexes:
        q, r = hx.split("_")
        out["perHex"][hx] = {}
        for name, path in (("hex", "/api/economy/hex"), ("ownership", "/api/economy/ownership")):
            url = "http://127.0.0.1:%d%s?q=%s&r=%s&branch=main&revision=%s" % (
                gui, path, q, r, revision)
            try:
                code, body = get_json(url, timeout=60)
                out["perHex"][hx][name] = body
            except Exception as e:  # noqa: BLE001
                out["perHex"][hx][name] = {"error": str(e)}
    return out


if __name__ == "__main__":
    main()
