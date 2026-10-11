#!/usr/bin/env bash
# W9f ⑤（2026-10-11）：**构造一条可控的真 500**，用来复核 W9-fix2 §4 的「GUI_REQUEST_FAILED 自带异常栈」。
#
# ★ 为什么需要它：W9-fix2 的证据是**渲染探针**（同一份 jar 的 log4j2 配置 + 同一 logger 名，
#   手工调 `warn(line, throwable)` 证明栈会打出来），**不是真 500**。派单要求：能构造真 500 就补一条真样本。
#
# 做法（不改任何 main）：起服务（small-world，端口 11001，appLevel=INFO，独立 store）
#   → advance 0→10（落一份 checkpoint）
#   → **删掉整个 store 目录**（= 一次对真实运行实例受管卷的"磁盘消失"故障注入）
#   → advance 10→20（写 checkpoint 时 IOException ⇒ GuiServer.handle 的 catch (Exception) ⇒ 500）
#   → 断言 HTTP 状态码 + 抓日志里 GUI_REQUEST_FAILED 行**及其后的栈行**
# ★ 收工：kill <PID>（不用 pkill -f）；不覆盖任何运行中 jar（run-shaded.sh 自己拷快照）。
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w9f-test-batch
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts/shell-sw.json
STORE=/tmp/w9f/gui500/store
LOG=$BATCH/logs/gui500.log
OUT=$BATCH/evidence/07-gui500-real-stack.txt
GUI=11001

rm -rf /tmp/w9f/gui500 && mkdir -p /tmp/w9f/gui500
echo "=== W9f ⑤ 真 500 现场（$(date -Is)）===" > "$OUT"
echo "jar md5 = $(md5sum "$JAR" | cut -d' ' -f1)；store=$STORE；gui-port=$GUI；-Dsimos.app.logLevel=INFO" >> "$OUT"

JAVA_TOOL_OPTIONS="-Dsimos.app.logLevel=INFO" nohup bash "$ROOT/tools/run-shaded.sh" "$JAR" \
  --store "$STORE" --world=small-world --config "$CFG" \
  --gui-port "$GUI" --mcp-port 11002 --approval-port 11003 > "$LOG" 2>&1 &
PID=$!
echo "PID=$PID" >> "$OUT"
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:$GUI/api/status" >/dev/null 2>&1; then break; fi
  sleep 0.5
done

post() { # post <from> <to>
  curl -s -o /tmp/w9f/gui500/body.json -w "%{http_code}" -X POST "http://127.0.0.1:$GUI/api/advance" \
    -H 'Content-Type: application/json' \
    -d "{\"branch\":\"main\",\"expectedRevision\":$2,\"from\":$1,\"to\":$3}"
}

echo "" >> "$OUT"
echo "① 正常轮：advance 0→10（expectedRevision=1）⇒ HTTP $(post 0 1 10)" >> "$OUT"
sleep 1
echo "   checkpoint 文件：" >> "$OUT"; ls "$STORE/checkpoints/main" >> "$OUT" 2>&1

echo "" >> "$OUT"
echo "② 故障注入：rm -rf $STORE（运行中的实例，磁盘消失）" >> "$OUT"
rm -rf "$STORE"

echo "③ 同一实例：advance 10→20（expectedRevision=2）⇒ HTTP $(post 10 2 20)" >> "$OUT"
echo "   响应体：$(cat /tmp/w9f/gui500/body.json)" >> "$OUT"

echo "" >> "$OUT"
echo "④ 日志（GUI_REQUEST_FAILED 行 + 其后 12 行 = 栈）：" >> "$OUT"
grep -n -A 12 "GUI_REQUEST_FAILED" "$LOG" | head -30 >> "$OUT" 2>&1
echo "" >> "$OUT"
echo "   日志里 \\tat 行数 = $(grep -c $'\tat ' "$LOG")" >> "$OUT"

echo "" >> "$OUT"
echo "⑤ 收工：kill $PID" >> "$OUT"
kill "$PID" 2>/dev/null
sleep 1
if kill -0 "$PID" 2>/dev/null; then echo "   ★ 仍在运行 ⇒ 再 kill -9" >> "$OUT"; kill -9 "$PID" 2>/dev/null; else echo "   已退出" >> "$OUT"; fi
cat "$OUT"
