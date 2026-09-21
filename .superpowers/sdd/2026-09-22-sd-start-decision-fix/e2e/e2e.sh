#!/usr/bin/env bash
# e2e.sh —— ★ 唯一打真外网的一步（显式标注）：无战斗世界里点一次「开始决策」⇒ 真 LLM 判决落 revision。
#   provider: /tmp/small/agentlib/config.json 的 mosire-flash（baseUrl http://121.40.130.178:3000/v1）
#   世界: /tmp/small 的一致副本（2 个决策人、**0 场战斗**）。
#   ★ 先决条件：副本里的决策人 providerId=null（= 用户点不出判决的真正前置缺口）⇒ 本脚本先经
#     sd.SetDecisionMakerProvider 把 dm-dashu 绑到 mosire-flash（世界写，落 revision），再点一次「开始决策」。
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/sdfix
EV=$WT/.superpowers/sdd/2026-09-22-sd-start-decision-fix/e2e
JAR=$WT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
STORE=/tmp/sdfix-e2e
PORT=5831
BASE=http://127.0.0.1:$PORT
: > "$EV/app.log"
: > "$EV/run-summary.txt"

rm -rf "$STORE"; mkdir -p "$STORE"
python3 - <<'PY'
import sqlite3, shutil
src = sqlite3.connect('file:/tmp/small/simos.db?mode=ro', uri=True)
dst = sqlite3.connect('/tmp/sdfix-e2e/simos.db')
src.backup(dst); dst.close(); src.close()
shutil.copytree('/tmp/small/checkpoints', '/tmp/sdfix-e2e/checkpoints', dirs_exist_ok=True)
shutil.copytree('/tmp/small/agentlib', '/tmp/sdfix-e2e/agentlib', dirs_exist_ok=True)
PY

setsid java -jar "$JAR" --store "$STORE" --gui-port 5831 --mcp-port 5832 \
  --approval-port 5833 --decision-agent-mcp-port 5834 > "$EV/app.log" 2>&1 &
PID=$!
echo "$PID" > "$EV/app.pid"
echo "pid=$PID" >> "$EV/run-summary.txt"

for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/state" || true)
  [ "$code" = "200" ] && { echo "up_after=$i" >> "$EV/run-summary.txt"; break; }
  sleep 0.5
done

head_of() { curl -s "$BASE/api/state" | python3 -c 'import sys,json; print(json.load(sys.stdin)["heads"]["main"])'; }

{
  echo "=== GET /api/sd/decision-makers (before binding; providerId=null) ==="
  curl -s "$BASE/api/sd/decision-makers"; echo
  echo "=== GET /api/sd/verdicts (before) ==="
  curl -s "$BASE/api/sd/verdicts"; echo
} > "$EV/state-before.txt" 2>&1

H0=$(head_of); echo "head_before_binding=$H0" >> "$EV/run-summary.txt"
{
  echo "=== POST /api/sd/set-decision-maker-provider dm-dashu -> mosire-flash (expectedRevision=$H0) ==="
  curl -s -w '\nhttp_status=%{http_code}\n' -X POST "$BASE/api/sd/set-decision-maker-provider" \
    -H 'Content-Type: application/json' \
    -d "{\"branch\":\"main\",\"expectedRevision\":$H0,\"decisionMakerId\":\"dm-dashu\",\"providerId\":\"mosire-flash\"}"
} > "$EV/bind-provider.json" 2>&1

H1=$(head_of); echo "head_after_binding=$H1" >> "$EV/run-summary.txt"
{
  echo "=== GET /api/sd/decision-makers (after binding; providerId=mosire-flash) ==="
  curl -s "$BASE/api/sd/decision-makers"; echo
} > "$EV/state-after-binding.txt" 2>&1

# ★★ 真外网：点一次「开始决策」（等价于前端按钮的 POST /api/sd/start-decision）
{
  echo "=== [409 证据] POST /api/sd/start-decision dm-dashu 用一个**过期** expectedRevision=$((H1-1)) ==="
  curl -s -w '\nhttp_status=%{http_code}\n' -X POST "$BASE/api/sd/start-decision" \
    -H 'Content-Type: application/json' \
    -d "{\"branch\":\"main\",\"expectedRevision\":$((H1-1)),\"decisionMakerId\":\"dm-dashu\"}"
  echo "=== [重试] POST /api/sd/start-decision dm-dashu 用服务端 current.revision=$H1 ==="
  curl -s -w '\nhttp_status=%{http_code}\n' -X POST "$BASE/api/sd/start-decision" \
    -H 'Content-Type: application/json' \
    -d "{\"branch\":\"main\",\"expectedRevision\":$H1,\"decisionMakerId\":\"dm-dashu\"}"
} > "$EV/start-decision.json" 2>&1

{
  echo "=== GET /api/state (after) ==="; curl -s "$BASE/api/state"; echo
  echo "=== GET /api/sd/verdicts (after) ==="; curl -s "$BASE/api/sd/verdicts"; echo
} > "$EV/state-after.txt" 2>&1

head_of > "$EV/head-after.txt" 2>&1
grep -E "LLM 判决响应|SubmitVerdict|access POST /api/sd/start-decision" "$EV/app.log" > "$EV/llm-responses.log" 2>&1 || true

kill "$PID" 2>/dev/null || true; sleep 1; kill -9 "$PID" 2>/dev/null || true
echo "killed=$PID" >> "$EV/run-summary.txt"

echo "=== run-summary ==="; cat "$EV/run-summary.txt"
echo "=== bind ==="; cat "$EV/bind-provider.json"
echo "=== start-decision ==="; cat "$EV/start-decision.json"
echo "=== llm responses ==="; cat "$EV/llm-responses.log"
echo "=== verdicts after ==="; cat "$EV/state-after.txt"
