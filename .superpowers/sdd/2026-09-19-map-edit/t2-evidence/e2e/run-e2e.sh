#!/usr/bin/env bash
# T2 行为不变 e2e 复跑装置（改编自 M8-S run-e2e.sh，指向 m8t2 worktree）。
# 目的：证明 map.js 的"可测性抽取"（regionBoundaryRings 上提 + 纯函数导出）未改页面行为。
# 用法: run-e2e.sh <port> <store-src> <out-dir> <server-log>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t2
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t2-evidence"
cd "$ROOT" || exit 2
PORT="$1"; STORE_SRC="$2"; OUT="$3"; SERVER_LOG="$4"

# 装置自证：服务端从 target/classes/webui 取资源 ⇒ 起服务前让它与 src 逐字节一致。
CLASSES_WEBUI="simos-app/target/classes/webui"
if [ ! -d "$CLASSES_WEBUI" ]; then echo "DEVICE FAIL: $CLASSES_WEBUI 不存在（先 ./mvnw -pl simos-app -am compile）"; exit 2; fi
rm -rf "$CLASSES_WEBUI"; mkdir -p "$CLASSES_WEBUI"
cp -a simos-app/src/main/resources/webui/. "$CLASSES_WEBUI/"
for f in api.js app.js blocks.js index.html map.js modes.js panels.js styles.css timeline.js unitTree.js; do
  a=$(md5sum "simos-app/src/main/resources/webui/$f" | awk '{print $1}')
  b=$(md5sum "$CLASSES_WEBUI/$f" | awk '{print $1}')
  [ "$a" = "$b" ] || { echo "DEVICE FAIL: $f src != classes"; exit 2; }
done
echo "webui_synced map.js_md5=$(md5sum "$CLASSES_WEBUI/map.js" | awk '{print $1}') timeline.js_md5=$(md5sum "$CLASSES_WEBUI/timeline.js" | awk '{print $1}')"

# Java 权威边界探针（只读 .class，不改仓内 Java）。
PROBE_CLASSES="$EV/e2e/probe-classes"
rm -rf "$PROBE_CLASSES"; mkdir -p "$PROBE_CLASSES"
javac -cp "simos-map/target/classes" -d "$PROBE_CLASSES" "$EV/e2e/BoundaryProbe.java" || { echo "PROBE COMPILE FAIL"; exit 2; }
export PROBE_CP="simos-map/target/classes:$PROBE_CLASSES"

SRC_MD5_BEFORE=$(md5sum "$STORE_SRC/simos.db" | awk '{print $1}')
echo "store_src_md5_before=$SRC_MD5_BEFORE"

STORE="/tmp/m8t2-e2e-store-$PORT"
rm -rf "$STORE"; mkdir -p "$STORE" "$OUT"; cp -a "$STORE_SRC/." "$STORE/"
CP="simos-app/target/classes:simos-util/target/classes:simos-map/target/classes:simos-social/target/classes:simos-unit/target/classes:simos-core/target/classes:$(cat /tmp/m7f-cp.txt)"

java -cp "$CP" io.mosire.simos.app.ShellMain --store "$STORE" --gui-port "$PORT" --mcp-port 0 --approval-port 0 </dev/null > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

CODE="none"
for i in $(seq 1 80); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/" 2>/dev/null || true)
  [ "$CODE" = "200" ] && break
  sleep 0.5
done
echo "server_ready_tries=$i code=$CODE pid=$SERVER_PID"

NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules node "$EV/e2e/e2e.cjs" "http://127.0.0.1:$PORT" "$OUT"
RC=$?
echo "e2e_rc=$RC"

kill "$SERVER_PID" 2>/dev/null || true
trap - EXIT

SRC_MD5_AFTER=$(md5sum "$STORE_SRC/simos.db" | awk '{print $1}')
echo "store_src_md5_after=$SRC_MD5_AFTER"
if [ "$SRC_MD5_BEFORE" != "$SRC_MD5_AFTER" ]; then echo "STORE_SRC CHANGED! before=$SRC_MD5_BEFORE after=$SRC_MD5_AFTER"; exit 3; fi
exit $RC
