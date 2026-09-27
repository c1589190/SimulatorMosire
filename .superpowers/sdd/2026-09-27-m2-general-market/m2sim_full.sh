#!/usr/bin/env bash
# M2 一年期批次：只推进 tick 180 / 360（用户 2026-09-28 指令）。
# ★ 服务必须以**仓根**为 CWD 起（worldgen 配置按工作目录拼：config/worldgen/v17levant-nations.json）。
# ★ store 与读数落持久目录；worldgen 失败即中止（不许空世界推进）。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
JAR="$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
STORE="$HERE/store-m2"
LOG="$HERE/m2sim-server.log"
ADVANCE="$ROOT/.superpowers/sdd/2026-09-26-year-one-simulation/v3curve_advance.py"
SETUP="$ROOT/.superpowers/sdd/2026-09-26-year-one-simulation/v3curve_setup.py"
DUMP="$ROOT/.superpowers/sdd/2026-09-27-economic-cycle-impl/h6sim_dump.py"
GUI=5827
SERVER_PID=""

start_server() {
  echo "[m2sim] 起服务 store=$STORE cwd=$ROOT（$(date '+%F %T')）"
  mkdir -p "$STORE"
  setsid bash -c "cd '$ROOT' && exec '$ROOT/tools/run-shaded.sh' '$JAR' --store '$STORE' --gui-port $GUI --mcp-port 5725 --approval-port 5723" >>"$LOG" 2>&1 </dev/null &
  SERVER_PID=$!
  for _ in $(seq 1 60); do
    sleep 3
    if curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:$GUI/index.html"; then
      echo "[m2sim] 服务就绪 pid=$SERVER_PID（$(date '+%T')）"
      return 0
    fi
  done
  echo "[m2sim] ★ 服务 180s 没起来 ⇒ 看 $LOG"
  return 1
}
alive() { curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:$GUI/index.html"; }
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null' EXIT

start_server || exit 1

if [ ! -f "$HERE/.worldgen-done" ]; then
  echo "[m2sim] worldgen initialize 三国（$(date '+%T')）"
  python3 "$SETUP" > "$HERE/m2-worldgen.log" 2>&1
  rc=$?
  tail -15 "$HERE/m2-worldgen.log"
  if [ "$rc" -ne 0 ]; then echo "[m2sim] ★★ worldgen 失败（rc=$rc）⇒ 中止"; exit 1; fi
  n_ok=$(grep -c '✓ revision=' "$HERE/m2-worldgen.log" || true)
  if [ "$n_ok" -lt 3 ]; then echo "[m2sim] ★★ worldgen 只成功 $n_ok/3 ⇒ 中止"; exit 1; fi
  touch "$HERE/.worldgen-done"
  echo "[m2sim] worldgen 完成（3/3，$(date '+%T')）"
fi

for t in 180 360; do
  label="m2t$t"
  if [ -f "$HERE/${label}.json" ]; then echo "[m2sim] $label 已在 ⇒ 跳过"; continue; fi
  ok=0
  for attempt in 1 2 3 4 5; do
    if ! alive; then echo "[m2sim] 服务不在 ⇒ 重启（第 $attempt 次）"; start_server || exit 1; fi
    echo "===== advance -> $t（第 $attempt 次；每 30 天一段，$(date '+%T')）====="
    chunks_ok=1
    for c in $(seq 30 30 "$t"); do
      echo "  -- advance -> $c（$(date '+%T')）"
      if ! /usr/bin/time -f "  chunk 耗时 %E" python3 "$ADVANCE" "$c" 2>&1 | tail -4; then
        echo "  ★ chunk -> $c 失败 ⇒ 本轮中止，重试"; chunks_ok=0; break
      fi
    done
    if [ "$chunks_ok" -ne 1 ]; then
      if ! alive; then echo "[m2sim] 服务不在 ⇒ 重启"; start_server || exit 1; fi
      continue
    fi
    echo "===== dump $label（$(date '+%T')）====="
    if python3 "$DUMP" "$label" > "$HERE/dump-$label.log" 2>&1 && [ -f "/tmp/h6raw_${label}.json" ]; then
      tail -5 "$HERE/dump-$label.log"
      cp -f "/tmp/h6raw_${label}.json" "$HERE/${label}.json"
      echo "[m2sim] 持久读数 $HERE/${label}.json（$(stat -c%s "$HERE/${label}.json") B，$(date '+%T')）"
      ok=1
      break
    fi
    echo "[m2sim] ★ 落盘失败 ⇒ 重试"; tail -5 "$HERE/dump-$label.log" 2>/dev/null; sleep 5
  done
  if [ "$ok" -ne 1 ]; then echo "[m2sim] ★★ $label 未取得读数"; fi
done
echo "[m2sim] 本批结束（$(date '+%T')）"
