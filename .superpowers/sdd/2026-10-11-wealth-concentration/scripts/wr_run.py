#!/usr/bin/env python3
"""财富聚集调查装置（只读仓；store / 逐日读数 / dump / 日志全落本 SDD 目录）。

基于 W6 总验收的 /tmp/w6acc/w6run.py（同一台起停/推进装置），只做三处改动：
  · 逐日读数除聚合行（复用 w6lib.extract，字段与 W6 验收读数逐字段同源）外，
    另落一份**逐户行**（wrlib.extract_hh）；
  · 在 GRID = (0,120,240,360,480,600) 这些天把**全量信封 checkpoint 另存一份**到 dumps/；
  · 输出目录默认落在本 SDD 目录下（原装置写 /tmp）。

用法：
  python3 wr_run.py --jar <shaded.jar> --tag sw --store <dir> --gui-port N \
      --world small-world --config shell-sw.json --plan plan-sw.json \
      --daily-out <jsonl> --dump-dir <dir> --out <dir> \
      [--prune] [--log-keep RE]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from w6lib import extract as extract_agg          # noqa: E402
from wrlib import extract_hh, gov_totals, debt_summary  # noqa: E402

RUN_SHADED = "/home/cna/SimulatorMosire/tools/run-shaded.sh"
GRID = (0, 120, 240, 360, 480, 600)


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


def get_json(url, timeout=120):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.status, json.loads(r.read().decode())


def post_json(url, payload, timeout=14400):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode(), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), time.time() - t0


def cp_files(store):
    base = os.path.join(store, "checkpoints")
    out = []
    for root, _d, files in os.walk(base):
        for f in files:
            if f.endswith(".json"):
                out.append((int(f[:-5]), os.path.join(root, f)))
    return sorted(out)


def latest_checkpoint(store):
    fs = cp_files(store)
    return fs[-1] if fs else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--store", required=True)
    ap.add_argument("--gui-port", type=int, required=True)
    ap.add_argument("--mcp-port", type=int, default=None)
    ap.add_argument("--approval-port", type=int, default=None)
    ap.add_argument("--world", default="small-world")
    ap.add_argument("--config", required=True)
    ap.add_argument("--plan", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dump-dir", required=True)
    ap.add_argument("--daily-out", required=True)
    ap.add_argument("--log-level", default="INFO")
    ap.add_argument("--trace-level", default=None)
    ap.add_argument("--social-level", default=None)
    ap.add_argument("--social-trace-level", default=None)
    ap.add_argument("--app-level", default=None)
    ap.add_argument("--prune", action="store_true")
    ap.add_argument("--log-keep", default=None)
    args = ap.parse_args()

    gui = args.gui_port
    mcp = args.mcp_port or (gui + 1)
    approval = args.approval_port or (gui + 2)
    for d in (args.out, args.dump_dir, os.path.dirname(args.daily_out)):
        os.makedirs(d, exist_ok=True)
    log_path = os.path.join(args.out, "%s.log" % args.tag)
    with open(args.plan) as f:
        plan = json.load(f)

    for p in (gui, mcp, approval):
        if not port_free(p):
            log("端口 %d 被占用 ⇒ 拒绝启动" % p)
            sys.exit(3)

    if os.path.exists(args.store):
        shutil.rmtree(args.store)
    os.makedirs(args.store, exist_ok=True)

    daily_f = open(args.daily_out, "a")
    jar_md5 = md5_file(args.jar)
    cmd = ["bash", RUN_SHADED, args.jar, "--store", args.store, "--world=%s" % args.world,
           "--config", args.config, "--gui-port", str(gui), "--mcp-port", str(mcp),
           "--approval-port", str(approval)]
    log("起服务: %s" % " ".join(cmd))
    t_start = time.time()
    lf = open(log_path, "w")
    env = dict(os.environ)
    jvm = "-Dsimos.economy.logLevel=%s" % args.log_level
    for flag, val in (("simos.economy.traceLevel", args.trace_level),
                      ("simos.social.logLevel", args.social_level),
                      ("simos.social.traceLevel", args.social_trace_level),
                      ("simos.app.logLevel", args.app_level)):
        if val:
            jvm += " -D%s=%s" % (flag, val)
    env["JAVA_TOOL_OPTIONS"] = jvm
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            cwd=HERE, env=env)
    keep_re = re.compile(args.log_keep) if args.log_keep else None
    stats = {"lines": 0, "kept": 0}

    def _reader():
        for raw in proc.stdout:
            stats["lines"] += 1
            line = raw.decode("utf-8", "replace")
            if keep_re is None or keep_re.search(line):
                stats["kept"] += 1
                lf.write(line)
        lf.flush()

    threading.Thread(target=_reader, daemon=True).start()
    result = {"tag": args.tag, "jar": os.path.abspath(args.jar), "jarMd5": jar_md5,
              "store": os.path.abspath(args.store), "guiPort": gui, "world": args.world,
              "config": args.config, "log": log_path, "plan": args.plan, "jvmOpts": jvm,
              "pid": proc.pid, "startedAt": datetime.now().isoformat(timespec="seconds"),
              "logKeep": args.log_keep, "steps": [], "grid": [], "readySeconds": None}

    def snapshot(day):
        latest = latest_checkpoint(args.store)
        if latest is None:
            return
        rev, path = latest
        row = extract_agg(path, day=day)
        row["hh"] = extract_hh(path)
        row["gov"] = gov_totals(path)
        row["debt_s"] = debt_summary(path)
        row["cpMd5"] = md5_file(path)
        row["cpBytes"] = os.path.getsize(path)
        daily_f.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
        daily_f.flush()
        if day in GRID:
            dst = os.path.join(args.dump_dir, "%s-d%d.json" % (args.tag, day))
            shutil.copy2(path, dst)
            result["grid"].append({"day": day, "revision": rev, "file": dst,
                                   "envMd5": row["cpMd5"], "bytes": row["cpBytes"]})
            log("grid dump day=%s rev=%s → %s" % (day, rev, dst))

    try:
        deadline = time.time() + 900
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
        log("GUI 就绪（%.2fs）" % result["readySeconds"])

        head = tick = None
        gdeadline = time.time() + 600
        while time.time() < gdeadline:
            fs = cp_files(args.store)
            if fs:
                head, tick = fs[-1][0], None
                break
            time.sleep(0.2)
        if head is None:
            log("创世后无 checkpoint")
            raise SystemExit(7)
        # 从 checkpoint 里取真 tick
        tick = json.load(open(cp_files(args.store)[-1][1]))["timestamp"]["tick"]
        result["genesis"] = {"revision": head, "tick": tick}
        log("创世后 head rev=%s tick=%s" % (head, tick))
        snapshot(tick)

        for idx, act in enumerate(plan):
            op = act["op"]
            if op == "advance_daily":
                frm, to = act.get("from", tick), act["to"]
                t0 = time.time()
                for d in range(frm + 1, to + 1):
                    code, body, dt = post_json("http://127.0.0.1:%d/api/advance" % gui,
                                               {"branch": "main", "expectedRevision": head,
                                                "from": d - 1, "to": d})
                    if code != 200:
                        result["failedAt"] = {"op": "advance_daily", "day": d,
                                              "httpStatus": code, "response": body[:2000]}
                        log("advance_daily day=%s HTTP %s %s" % (d, code, body[:200]))
                        raise SystemExit(6)
                    head = json.loads(body)["ref"]["revision"]
                    tick = d
                    snapshot(d)
                    if args.prune:
                        keep = latest_checkpoint(args.store)[1]
                        for _rev, p in cp_files(args.store):
                            if p != keep:
                                os.remove(p)
                    if d % 60 == 0:
                        log("  day=%s head=%s 累计 %.1fs" % (d, head, time.time() - t0))
                result["steps"].append({"i": idx, "op": "advance_daily", "from": frm, "to": to,
                                        "seconds": round(time.time() - t0, 3)})
                log("advance_daily %s->%s 完成 head=%s（%.1fs）" % (frm, to, head, time.time() - t0))
            elif op == "dump":
                latest = latest_checkpoint(args.store)
                if latest is None:
                    result["steps"].append({"i": idx, "op": "dump", "error": "no-checkpoint"})
                    continue
                rev, path = latest
                dst = os.path.join(args.dump_dir, "%s-%s.json" % (args.tag, act["label"]))
                shutil.copy2(path, dst)
                result["steps"].append({"i": idx, "op": "dump", "label": act["label"],
                                        "revision": rev, "file": dst,
                                        "md5": md5_file(dst), "bytes": os.path.getsize(dst)})
                log("dump %s -> %s" % (act["label"], dst))
            elif op == "get":
                try:
                    code, body = get_json("http://127.0.0.1:%d%s" % (gui, act["path"]), timeout=60)
                    p = os.path.join(args.out, "%s-get-%s.json" % (
                        args.tag, act["path"].strip("/").replace("/", "_")))
                    with open(p, "w") as f:
                        json.dump(body, f, ensure_ascii=False, indent=1)
                    result["steps"].append({"i": idx, "op": "get", "path": act["path"],
                                            "httpStatus": code, "file": p})
                    log("get %s HTTP %s" % (act["path"], code))
                except Exception as e:  # noqa: BLE001
                    result["steps"].append({"i": idx, "op": "get", "path": act["path"],
                                            "error": str(e)})
    finally:
        try:
            proc.terminate()
            try:
                proc.wait(timeout=90)
            except subprocess.TimeoutExpired:
                proc.kill()
        except Exception:
            pass
        time.sleep(0.5)
        result["exitCode"] = proc.returncode
        result["endedAt"] = datetime.now().isoformat(timespec="seconds")
        result["stdoutLines"] = stats["lines"]
        result["logKeptLines"] = stats["kept"]
        result["elapsedSeconds"] = round(time.time() - t_start, 1)
        with open(os.path.join(args.out, "%s.result.json" % args.tag), "w") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        daily_f.close()
        lf.flush()
        lf.close()
        log("收工 rc=%s pid=%s elapsed=%.1fs stdout=%s kept=%s"
            % (proc.returncode, proc.pid, result["elapsedSeconds"], stats["lines"], stats["kept"]))


if __name__ == "__main__":
    main()
