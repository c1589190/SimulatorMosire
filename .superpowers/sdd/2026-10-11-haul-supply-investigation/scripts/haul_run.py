#!/usr/bin/env python3
"""运力调查小窗口装置（只读仓；只写本报告目录）。

复用 day600 store 的**副本**（`.superpowers/sdd/2026-10-11-wealth-concentration/stores/sw600`
的逐字节拷贝，checkpoint md5 已比对），从 day600 续跑到 --to，日志按运力/迁移白名单过滤。

用法：
  python3 haul_run.py --tag sw-replay --store <dir> --gui-port 10711 \
      --world small-world --config shell-sw.json --from 600 --to 725 --out <dir>
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

RUN_SHADED = "/home/cna/SimulatorMosire/tools/run-shaded.sh"
HERE = os.path.dirname(os.path.abspath(__file__))


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


def cp_meta(store):
    base = os.path.join(store, "checkpoints")
    out = []
    for root, _d, files in os.walk(base):
        for f in files:
            if f.endswith(".json"):
                out.append((int(f[:-5]), os.path.join(root, f)))
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default="/home/cna/SimulatorMosire/simos-app/target/"
                                     "simos-app-0.1.0-SNAPSHOT-shaded.jar")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--store", required=True)
    ap.add_argument("--gui-port", type=int, required=True)
    ap.add_argument("--world", default="small-world")
    ap.add_argument("--config", required=True)
    ap.add_argument("--from", dest="frm", type=int, required=True)
    ap.add_argument("--to", type=int, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--economy-level", default="DEBUG")
    ap.add_argument("--trace-level", default="TRACE")
    ap.add_argument("--social-level", default="INFO")
    ap.add_argument("--app-level", default="INFO")
    ap.add_argument("--prune", action="store_true")
    ap.add_argument("--full-log", action="store_true")
    ap.add_argument("--log-keep",
                    default=r"event=(MERCHANT_[A-Z_]*|CAPACITY_[A-Z_]*|LOGISTICS_[A-Z_]*"
                            r"|MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET |HAUL_[A-Z_]*"
                            r"|PRIMARY_MODE[A-Z_]*|DAY_START |DAY_END )")
    args = ap.parse_args()

    gui = args.gui_port
    mcp, approval = gui + 1, gui + 2
    for d in (args.out,):
        os.makedirs(d, exist_ok=True)
    log_path = os.path.join(args.out, "%s.log" % args.tag)

    if not os.path.isdir(args.store):
        log("store 不存在: %s" % args.store)
        sys.exit(2)
    for p in (gui, mcp, approval):
        if not port_free(p):
            log("端口 %d 被占用 ⇒ 拒绝启动" % p)
            sys.exit(3)

    metas = cp_meta(args.store)
    if not metas:
        log("store 里没有 checkpoint ⇒ 拒绝（本装置不创世）")
        sys.exit(4)
    before = {"headRevision": metas[-1][0], "cpMd5": md5_file(metas[-1][1]),
              "cpBytes": os.path.getsize(metas[-1][1])}
    log("续跑前 head=%s cp=%s md5=%s" % (before["headRevision"], metas[-1][1], before["cpMd5"]))

    jar_md5 = md5_file(args.jar)
    cmd = ["bash", RUN_SHADED, args.jar, "--store", args.store, "--world=%s" % args.world,
           "--config", args.config, "--gui-port", str(gui), "--mcp-port", str(mcp),
           "--approval-port", str(approval)]
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = (
        "-Dsimos.economy.logLevel=%s -Dsimos.economy.traceLevel=%s "
        "-Dsimos.social.logLevel=%s -Dsimos.app.logLevel=%s"
        % (args.economy_level, args.trace_level, args.social_level, args.app_level))
    log("起服务: %s" % " ".join(cmd))
    t0 = time.time()
    lf = open(log_path, "w")
    full_path = os.path.join(args.out, "%s.full.log" % args.tag)
    ff = open(full_path, "w") if args.full_log else None
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            cwd=HERE, env=env)
    keep_re = re.compile(args.log_keep)
    stats = {"lines": 0, "kept": 0}

    def _reader():
        for raw in proc.stdout:
            stats["lines"] += 1
            line = raw.decode("utf-8", "replace")
            if ff is not None:
                ff.write(line)
            if keep_re.search(line):
                stats["kept"] += 1
                lf.write(line)
        lf.flush()

    threading.Thread(target=_reader, daemon=True).start()
    result = {"tag": args.tag, "jar": args.jar, "jarMd5": jar_md5,
              "store": os.path.abspath(args.store), "guiPort": gui, "world": args.world,
              "config": os.path.abspath(args.config), "log": log_path,
              "jvmOpts": env["JAVA_TOOL_OPTIONS"], "pid": proc.pid, "before": before,
              "startedAt": datetime.now().isoformat(timespec="seconds"),
              "logKeep": args.log_keep, "fullLog": (full_path if args.full_log else None),
              "days": [], "after": None}
    try:
        deadline = time.time() + 900
        ready = None
        while time.time() < deadline:
            if proc.poll() is not None:
                log("进程提前退出 rc=%s，看日志 %s" % (proc.returncode, log_path))
                raise SystemExit(5)
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
            raise SystemExit(6)
        result["readySeconds"] = round(time.time() - t0, 3)
        log("GUI 就绪（%.2fs）" % result["readySeconds"])

        head = cp_meta(args.store)[-1][0]
        tick = json.load(open(cp_meta(args.store)[-1][1]))["timestamp"]["tick"]
        log("续跑起点 rev=%s tick=%s" % (head, tick))
        if tick != args.frm:
            log("★ 注意：store 的 tick(%s) != --from(%s)，按 store 的真 tick 走" % (tick, args.frm))
            args.frm = tick

        t_start = time.time()
        for d in range(args.frm + 1, args.to + 1):
            code, body, dt = post_json("http://127.0.0.1:%d/api/advance" % gui,
                                       {"branch": "main", "expectedRevision": head,
                                        "from": d - 1, "to": d})
            if code != 200:
                result["failedAt"] = {"day": d, "httpStatus": code, "response": body[:2000]}
                log("advance day=%s HTTP %s %s" % (d, code, body[:200]))
                raise SystemExit(7)
            head = json.loads(body)["ref"]["revision"]
            result["days"].append({"day": d, "revision": head, "seconds": round(dt, 3),
                                   "postOk": code == 200})
            if args.prune:
                metas_now = cp_meta(args.store)
                keep = metas_now[-1][1]
                for _rev, p in metas_now:
                    if p != keep:
                        os.remove(p)
            if d % 25 == 0:
                log("  day=%s head=%s 累计 %.1fs" % (d, head, time.time() - t_start))
        result["advanceSeconds"] = round(time.time() - t_start, 3)
        meta = cp_meta(args.store)[-1]
        result["after"] = {"headRevision": meta[0], "cpFile": meta[1],
                           "cpMd5": md5_file(meta[1]), "cpBytes": os.path.getsize(meta[1]),
                           "checkpoints": [m[0] for m in cp_meta(args.store)]}
        log("续跑完成 head=%s（%.1fs）" % (meta[0], result["advanceSeconds"]))
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
        result["elapsedSeconds"] = round(time.time() - t0, 1)
        with open(os.path.join(args.out, "%s.result.json" % args.tag), "w") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
        lf.flush()
        lf.close()
        if ff is not None:
            ff.flush()
            ff.close()
        log("收工 rc=%s elapsed=%.1fs stdout=%s kept=%s"
            % (proc.returncode, result["elapsedSeconds"], stats["lines"], stats["kept"]))


if __name__ == "__main__":
    main()
