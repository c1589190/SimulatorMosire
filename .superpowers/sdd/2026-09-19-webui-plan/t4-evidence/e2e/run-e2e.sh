#!/usr/bin/env bash
# M7 T4 e2e 装置：起真 ShellMain → node+Playwright 驱动真页面。
# 用法: run-e2e.sh <mode: demo|realmap> <port> <store> <out-dir> <server-log>
# 退出码 = node e2e 的退出码（0=全 PASS，1=有 FAIL）。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7t4
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/t4-evidence"
cd "$ROOT" || exit 2
MODE="$1"; PORT="$2"; STORE="$3"; OUT="$4"; SERVER_LOG="$5"
CP="simos-app/target/classes:simos-util/target/classes:simos-map/target/classes:simos-social/target/classes:simos-unit/target/classes:simos-core/target/classes:$(cat /tmp/m7t3-cp.txt)"

mkdir -p "$OUT"
if [ "$MODE" = "demo" ]; then
  rm -rf "$STORE"; mkdir -p "$STORE"
  DEMO_FLAG="--demo"
else
  # realmap：从 M6 导入档复制一份（参考档不动）
  if [ ! -f "$STORE/simos.db" ]; then
    rm -rf "$STORE"; cp -r /tmp/m6-import-verify/test_integration "$STORE"
    rm -f "$STORE/simos.db-shm" "$STORE/simos.db-wal"
  fi
  DEMO_FLAG=""
fi

# shellcheck disable=SC2086
java -cp "$CP" io.mosire.simos.app.ShellMain --store "$STORE" $DEMO_FLAG --gui-port "$PORT" --mcp-port 0 --approval-port 0 > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true' EXIT

CODE="none"
for i in $(seq 1 120); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)
  [ "$CODE" = "200" ] && break
  sleep 0.5
done
echo "server_ready_tries=$i code=$CODE pid=$SERVER_PID"

NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules \
  node "$EV/e2e/e2e.cjs" "http://127.0.0.1:$PORT" "$MODE" "$STORE" "$OUT"
RC=$?
echo "e2e_rc=$RC"

kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT
exit $RC
