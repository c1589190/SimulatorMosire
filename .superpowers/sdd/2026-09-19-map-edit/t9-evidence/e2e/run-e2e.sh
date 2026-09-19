#!/usr/bin/env bash
# M8 T9 e2e 装置（承 T11 模板）：起真 ShellMain（--demo 空库 ⇒ 种入演示世界）→ node+Playwright 驱动真页面
#   （区域查看模式的多从属高亮 + 其它淡色；夹具经真写命令通路造）。端口自选，勿用 5817/5818。
# 用法: run-e2e.sh <port> <out-dir> <server-log>
# 退出码 = node e2e 的退出码（0=全部 PASS，1=有 FAIL，2=装置/崩溃）。
# ★ 本机化：ROOT 与 NODE_PATH 是**按机器**的（另一台机器上是 /home/cna/...），别照抄别处的值。
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t9-evidence"
cd "$ROOT" || exit 2
PORT="$1"
OUT="$2"
SERVER_LOG="$3"

# ★ 装置自证（形态 1）：服务端从 target/classes/webui 取资源 ⇒ 起服务前必须让它与 src 逐字节一致，
#   否则跑的是**上一次编译**留下的旧字节（旧字节能同时骗过"绿"和"红"）。
CLASSES_WEBUI="simos-app/target/classes/webui"
if [ ! -d "$CLASSES_WEBUI" ]; then
  echo "DEVICE FAIL: $CLASSES_WEBUI 不存在（先 ./mvnw -pl simos-app -am -DskipTests compile）"
  exit 2
fi
rm -rf "$CLASSES_WEBUI"
mkdir -p "$CLASSES_WEBUI"
cp -a simos-app/src/main/resources/webui/. "$CLASSES_WEBUI/"
# 逐文件 md5 对拍（全量，不只 6 个）+ 聚合 md5（先断言非空，别让"空==空"变成恒真）。
AGG_SRC=$(cd simos-app/src/main/resources/webui && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum | awk '{print $1}')
AGG_DST=$(cd "$CLASSES_WEBUI" && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum | awk '{print $1}')
if [ -z "$AGG_SRC" ] || [ -z "$AGG_DST" ] || [ "$AGG_SRC" != "$AGG_DST" ]; then
  echo "DEVICE FAIL: webui src != classes（agg_src=$AGG_SRC agg_dst=$AGG_DST）"
  exit 2
fi
echo "webui_synced agg_md5=$AGG_DST map_js_md5=$(md5sum "$CLASSES_WEBUI/map.js" | awk '{print $1}') index_html_md5=$(md5sum "$CLASSES_WEBUI/index.html" | awk '{print $1}')"
# 装置自指：这一轮**跑的断言**是哪份字节（log 里能读出 e2e.cjs / run-e2e.sh 的 md5，免得日后
# 只能靠推导回答"改过装置之后旧日志还算不算"）。
echo "harness_md5 e2e_cjs=$(md5sum "$EV/e2e/e2e.cjs" | awk '{print $1}') run_e2e_sh=$(md5sum "$EV/e2e/run-e2e.sh" | awk '{print $1}')"

STORE="/tmp/m8t9-e2e-store-$PORT"
rm -rf "$STORE"
mkdir -p "$STORE" "$OUT"
# ★ 本机没有"真档"（无 /tmp/m6-import-verify/test_integration）⇒ 用 --demo 空库首启种入演示世界。
#   报告里必须写明：本 e2e 跑在 --demo 起的库上，**不是真档**。
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
  node "$EV/e2e/e2e.cjs" "http://127.0.0.1:$PORT" "$OUT"
RC=$?
echo "e2e_rc=$RC"

kill "$SERVER_PID" 2>/dev/null || true
trap - EXIT
exit $RC
