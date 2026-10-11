#!/usr/bin/env python3
"""W6 标定批装置（只读仓；装置、store、dump 全落本批 SDD 目录）。

★ 出处（自指留痕）：本文件 = `/tmp/w6acc/w6run.py`（W6 总验收装置）的**逐字副本**，
  仅改三处落点：`OUT` / `DUMPS` / 子进程 `cwd`（`/tmp/w6acc` → 本批 SDD 目录；AGENTS §八.1）。
  原文 md5 = b7b8b0f501e19a643e8470fa342689d3（见 run-inventory.txt）；其余代码一字不动 ⇒ 与 W6/W9f 同一台尺。

基于 W5 验收的 runner.py（同一台尺），新增：
  · op `advance_daily`：逐日 POST /api/advance，**每一天抽一份紧凑读数**进 JSONL（--daily-out）；
  · `--keep-store`：不删 store（重启往返用）；`--prune`：每次抽完只留最新 checkpoint（省盘）；
  · 收工落 `log-stripped.md5`（去时间戳前缀的日志 md5，跨进程确定性用）。

用法：
  python3 w6run.py --jar <shaded.jar> --tag <tag> --store <dir> --gui-port N \
      --world small-world --config shell-sw.json --plan plan.json \
      [--daily-out x.jsonl] [--log-level INFO] [--trace-level INFO] [--social-level INFO] \
      [--keep-store] [--prune]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from w6lib import extract  # noqa: E402

RUN_SHADED = "/home/cna/SimulatorMosire/tools/run-shaded.sh"
# ★ W6 标定批（2026-10-11）：产物一律落 SDD 批目录（AGENTS §八.1：别只放 /tmp，本机重启清空 /tmp）。
BATCH = "/home/cna/SimulatorMosire/.superpowers/sdd/2026-10-11-w6-calibration"
OUT = BATCH + "/results"
DUMPS = BATCH + "/dumps"
TS = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3} ")


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


def revisions(store, ro=True):
    db = os.path.join(store, "simos.db")
    con = sqlite3.connect(("file:%s?mode=ro" % db) if ro else db, uri=ro)
    try:
        rows = con.execute("SELECT branch, revision, tick, command_type, length(changeset_json)"
                           " FROM revisions ORDER BY branch, revision").fetchall()
    finally:
        con.close()
    return [{"branch": r[0], "revision": r[1], "tick": r[2], "commandType": r[3],
             "changesetBytes": r[4]} for r in rows]


def cp_files(store):
    d = os.path.join(store, "checkpoints", "main")
    if not os.path.isdir(d):
        return []
    out = []
    for n in os.listdir(d):
        if n.endswith(".json"):
            out.append((int(n[:-5]), os.path.join(d, n)))
    return sorted(out)


def latest_checkpoint(store):
    cps = cp_files(store)
    return cps[-1] if cps else None


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
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--dump-dir", default=DUMPS)
    ap.add_argument("--daily-out", default=None)
    ap.add_argument("--log-level", default="INFO")
    ap.add_argument("--trace-level", default=None)
    ap.add_argument("--social-level", default=None)
    ap.add_argument("--social-trace-level", default=None)
    ap.add_argument("--app-level", default=None)
    ap.add_argument("--keep-store", action="store_true")
    ap.add_argument("--prune", action="store_true")
    ap.add_argument("--log-keep", default=None,
                    help="只把这些 event 行写进日志档（正则）；None = 全写。仍全程读走 stdout（不阻塞子进程）")
    ap.add_argument("--daily-full", action="store_true",
                    help="逐日读数里也带逐户 class_positions（默认带，除非 --daily-lean）")
    ap.add_argument("--no-daily-class", action="store_true")
    ap.add_argument("--hash-only", action="store_true",
                    help="逐日只记 revision + checkpoint md5/字节（不解析状态；跨进程逐日存档对拍用）")
    args = ap.parse_args()

    gui = args.gui_port
    mcp = args.mcp_port or (gui + 1)
    approval = args.approval_port or (gui + 2)
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.dump_dir, exist_ok=True)
    log_path = os.path.join(args.out, "%s.log" % args.tag)
    with open(args.plan) as f:
        plan = json.load(f)

    for p in (gui, mcp, approval):
        if not port_free(p):
            log("端口 %d 被占用 ⇒ 拒绝启动" % p)
            sys.exit(3)

    if not args.keep_store and os.path.exists(args.store):
        shutil.rmtree(args.store)
    os.makedirs(args.store, exist_ok=True)

    daily_f = open(args.daily_out, "a") if args.daily_out else None
    jar_md5 = md5_file(args.jar)
    cmd = ["bash", RUN_SHADED, args.jar, "--store", args.store, "--world=%s" % args.world,
           "--config", args.config, "--gui-port", str(gui), "--mcp-port", str(mcp),
           "--approval-port", str(approval)]
    log("起服务: %s" % " ".join(cmd))
    t_start = time.time()
    lf = open(log_path, "w")
    env = dict(os.environ)
    jvm = "-Dsimos.economy.logLevel=%s" % args.log_level
    if args.trace_level:
        jvm += " -Dsimos.economy.traceLevel=%s" % args.trace_level
    if args.social_level:
        jvm += " -Dsimos.social.logLevel=%s" % args.social_level
    if args.social_trace_level:
        jvm += " -Dsimos.social.traceLevel=%s" % args.social_trace_level
    if args.app_level:
        jvm += " -Dsimos.app.logLevel=%s" % args.app_level
    env["JAVA_TOOL_OPTIONS"] = jvm
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            cwd=BATCH, env=env)
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

    th = threading.Thread(target=_reader, daemon=True)
    th.start()
    result = {"tag": args.tag, "jar": os.path.abspath(args.jar), "jarMd5": jar_md5,
              "store": os.path.abspath(args.store), "guiPort": gui, "world": args.world,
              "config": args.config, "log": log_path, "plan": args.plan, "jvmOpts": jvm,
              "pid": proc.pid, "startedAt": datetime.now().isoformat(timespec="seconds"),
              "keepStore": args.keep_store, "logKeep": args.log_keep,
              "steps": [], "readySeconds": None}

    def snapshot(day):
        if daily_f is None:
            return
        latest = latest_checkpoint(args.store)
        if latest is None:
            return
        rev, path = latest
        if args.hash_only:
            row = {"day": day, "revision": rev}
        else:
            row = extract(path, day=day)
            if args.no_daily_class:
                row.pop("class_positions", None)
                row.pop("class_last_transition", None)
        row["cpMd5"] = md5_file(path)
        row["cpBytes"] = os.path.getsize(path)
        daily_f.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
        daily_f.flush()

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

        gdeadline = time.time() + 600
        row = None
        while time.time() < gdeadline:
            row = revisions(args.store, ro=False)[-1] if revisions(args.store) else None
            if row:
                break
            time.sleep(0.2)
        result["genesis"] = row
        head = row["revision"]
        tick = row["tick"]
        log("创世后 head rev=%s tick=%s" % (head, tick))
        snapshot(tick)

        for idx, act in enumerate(plan):
            op = act["op"]
            if op == "advance":
                to = act["to"]
                code, body, dt = post_json("http://127.0.0.1:%d/api/advance" % gui,
                                           {"branch": "main", "expectedRevision": head,
                                            "from": tick, "to": to})
                result["steps"].append({"i": idx, "op": "advance", "from": tick, "to": to,
                                        "httpStatus": code, "seconds": round(dt, 3),
                                        "response": body[:800]})
                log("advance %s->%s HTTP %s %.3fs" % (tick, to, code, dt))
                if code != 200:
                    result["failedAt"] = result["steps"][-1]
                    raise SystemExit(6)
                head = json.loads(body)["ref"]["revision"]
                tick = to
                if args.prune and latest_checkpoint(args.store):
                    keep = latest_checkpoint(args.store)[1]
                    for _rev, p in cp_files(args.store):
                        if p != keep:
                            os.remove(p)
                snapshot(tick)
            elif op == "advance_daily":
                frm, to = act.get("from", tick), act["to"]
                for d in range(frm + 1, to + 1):
                    code, body, dt = post_json("http://127.0.0.1:%d/api/advance" % gui,
                                               {"branch": "main", "expectedRevision": head,
                                                "from": d - 1, "to": d})
                    if code != 200:
                        result["failedAt"] = {"op": "advance_daily", "day": d, "httpStatus": code,
                                              "response": body[:2000]}
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
                result["steps"].append({"i": idx, "op": "advance_daily", "from": frm, "to": to,
                                        "seconds": round(time.time() - t_start, 3)})
                log("advance_daily %s->%s 完成 head=%s" % (frm, to, head))
            elif op == "dump":
                latest = latest_checkpoint(args.store)
                if latest is None:
                    result["steps"].append({"i": idx, "op": "dump", "error": "no-checkpoint"})
                    continue
                rev, path = latest
                label = act.get("label") or str(rev)
                dst = os.path.join(args.dump_dir, "%s-%s.json" % (args.tag, label))
                shutil.copyfile(path, dst)
                e = {"i": idx, "op": "dump", "label": label, "revision": rev, "dst": dst,
                     "bytes": os.path.getsize(dst), "md5": md5_file(dst)}
                result["steps"].append(e)
                log("dump %s rev=%s bytes=%s md5=%s" % (label, rev, e["bytes"], e["md5"]))
            elif op == "get":
                url = "http://127.0.0.1:%d%s" % (gui, act["path"])
                try:
                    with urllib.request.urlopen(url, timeout=120) as r:
                        code, raw = r.status, r.read().decode()
                except urllib.error.HTTPError as e:
                    code, raw = e.code, e.read().decode()
                keep = act.get("keep")
                try:
                    parsed = json.loads(raw)
                    trimmed = {k: parsed.get(k) for k in keep if k in parsed} if keep else parsed
                except Exception:
                    trimmed = raw[:2000]
                result["steps"].append({"i": idx, "op": "get", "path": act["path"],
                                        "httpStatus": code, "bytes": len(raw), "body": trimmed})
                with open(os.path.join(args.out, "%s-get%d.json" % (args.tag, idx)), "w") as gf:
                    gf.write(raw)
                log("get %s HTTP %s bytes=%s" % (act["path"], code, len(raw)))
            elif op == "sleep":
                time.sleep(act.get("seconds", 1))
            else:
                raise SystemExit("未知 op: %s" % op)

        result["revisions"] = revisions(args.store)
        head_row = result["revisions"][-1]
        result["headRevision"] = head_row["revision"]
        result["tick"] = head_row["tick"]
        result["revisionDelta"] = head_row["revision"] - result["revisions"][0]["revision"]
        result["checkpoints"] = [{"path": os.path.relpath(p, args.store), "bytes": os.path.getsize(p),
                                  "md5": md5_file(p)} for _r, p in cp_files(args.store)]
    finally:
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=180)
            except subprocess.TimeoutExpired:
                log("SIGTERM 后 180s 未退 ⇒ SIGKILL")
                proc.kill()
                proc.wait(timeout=30)
        try:
            proc.stdout.close()
        except Exception:  # noqa: BLE001
            pass
        th.join(timeout=60)
        try:
            lf.flush()
        finally:
            lf.close()
        result["logLines"] = stats["lines"]
        result["logKeptLines"] = stats["kept"]
        if daily_f:
            daily_f.close()
        # 去时间戳日志 md5 + 逐行落盘（跨进程确定性用）
        try:
            raw = open(log_path, "rb").read().decode("utf-8", "replace").splitlines()
            stripped = [TS.sub("", ln) for ln in raw]
            with open(os.path.join(args.out, "%s.log.stripped" % args.tag), "w") as f:
                f.write("\n".join(stripped) + "\n")
            with open(os.path.join(args.out, "%s.log.stripped.md5" % args.tag), "w") as f:
                f.write(hashlib.md5(("\n".join(stripped) + "\n").encode()).hexdigest() + "\n")
            result["logLines"] = len(stripped)
            result["logStrippedMd5"] = hashlib.md5(("\n".join(stripped) + "\n").encode()).hexdigest()
        except Exception as e:  # noqa: BLE001
            log("日志 md5 失败: %s" % e)
        try:
            result["revisions"] = revisions(args.store)
            with open(os.path.join(args.out, "%s.result.json" % args.tag), "w") as f:
                json.dump(result, f, ensure_ascii=False, indent=2, sort_keys=True)
        except Exception as e:  # noqa: BLE001
            log("结果落盘失败: %s" % e)

    log("结果落盘 %s/%s.result.json" % (args.out, args.tag))
    print(json.dumps({k: result.get(k) for k in ("tag", "jarMd5", "readySeconds", "revisionDelta",
                                                 "headRevision", "tick", "logStrippedMd5")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
