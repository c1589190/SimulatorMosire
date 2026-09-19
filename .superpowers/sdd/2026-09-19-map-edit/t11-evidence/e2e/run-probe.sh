#!/usr/bin/env bash
# M8 T11 探针装置：起真 ShellMain（--demo 空库）→ node probe.cjs 打 API 量语义。
# ★ 与 run-e2e.sh 是**两件装置**：本装置不驱动浏览器，只证后端语义（e2e 证 UI 发不发命令）。
# ★ 杀进程一律按 PID（`pkill -f` 会匹配到**本脚本自己的命令行**，本机实测把自己杀掉过：rc=144）。
# 用法: run-probe.sh <port> <out-json> <server-log>
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t11-evidence"
cd "$ROOT" || exit 2
PORT="$1"
OUT="$2"
SERVER_LOG="$3"
STORE="/tmp/m8t11-probe-store-$PORT"
rm -rf "$STORE"
mkdir -p "$STORE" "$(dirname "$OUT")"

CP="simos-app/target/classes:simos-util/target/classes:simos-map/target/classes:simos-social/target/classes:simos-unit/target/classes:simos-core/target/classes:$(cat /tmp/m7f-cp.txt)"
java -cp "$CP" io.mosire.simos.app.ShellMain \
  --store "$STORE" --demo --gui-port "$PORT" --mcp-port 0 --approval-port 0 </dev/null > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

CODE="none"
for i in $(seq 1 80); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)
  [ "$CODE" = "200" ] && break
  sleep 0.5
done
echo "server_ready_tries=$i code=$CODE pid=$SERVER_PID store=$STORE"

NODE_PATH=/home/dev/.npm/_npx/06d4b2c446e40bbc/node_modules \
  node "$EV/e2e/probe.cjs" "http://127.0.0.1:$PORT" "$OUT"
RC=$?
echo "probe_rc=$RC"

kill "$SERVER_PID" 2>/dev/null || true
trap - EXIT
exit $RC
