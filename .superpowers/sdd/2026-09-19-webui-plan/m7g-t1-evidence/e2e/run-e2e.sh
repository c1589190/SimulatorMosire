#!/usr/bin/env bash
# M7g T1 e2e 装置：起真 ShellMain（--demo，全新 store）→ node+Playwright 驱动真页面（a~h）。
# 覆盖：全屏底图 / 浮层不穿透 / 时间轴常驻 / resize 不重置 / 回中心入口 / 回归（M7e/M7f/M7b/T4）/ R8 allowlist。
# 用法: run-e2e.sh <port> <store> <out-dir> <server-log> [shot-dir] [WxH]
# 退出码 = node e2e 的退出码（0=全部必测步骤 PASS，1=有 FAIL）。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7gt1
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/m7g-t1-evidence"
cd "$ROOT" || exit 2
PORT="$1"; STORE="$2"; OUT="$3"; SERVER_LOG="$4"
SHOT="${5:-$OUT}"
SIZE="${6:-1280x800}"
CP="simos-app/target/classes:simos-util/target/classes:simos-map/target/classes:simos-social/target/classes:simos-unit/target/classes:simos-core/target/classes:$(cat /tmp/m7f-cp.txt)"

rm -rf "$STORE"; mkdir -p "$STORE" "$OUT" "$SHOT"

java -cp "$CP" io.mosire.simos.app.ShellMain --store "$STORE" --demo --gui-port "$PORT" --mcp-port 0 --approval-port 0 > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true' EXIT

CODE="none"
for i in $(seq 1 80); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)
  [ "$CODE" = "200" ] && break
  sleep 0.5
done
echo "server_ready_tries=$i code=$CODE pid=$SERVER_PID"

NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules \
  node "$EV/e2e/e2e.cjs" "http://127.0.0.1:$PORT" "$STORE" "$OUT" "$SHOT" "$SIZE"
RC=$?
echo "e2e_rc=$RC"

kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT
exit $RC
