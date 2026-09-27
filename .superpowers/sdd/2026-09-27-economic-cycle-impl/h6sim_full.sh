#!/usr/bin/env bash
# ★★ 一整批 600 天模拟：**服务由本脚本自己起**（作为本脚本的子进程）→ 逐关账日推进 + 落原始读数。
#
# 由来（本机实测两次）：把服务用 `nohup ... &` 单独起在一条 bash 调用里，它会在**别的后台作业结束时**
#   收到停止信号（日志 `ShellMain - 收到停止信号`）—— 两次都精确落在"某个受管作业结束"的那一刻。
#   ⇒ 把服务放进**同一个作业的进程树**里，并让它"死了就重启"，跑完由 trap 按 PID 收工。
#
# 用法: ./h6sim_full.sh <标签前缀> <store 目录>
#   基线批（H5 代码）: ./h6sim_full.sh tick /tmp/simos-v3curve-h6
#   关账批（H6 代码）: ./h6sim_full.sh r2   /tmp/simos-v3curve-h6r2
set -uo pipefail
cd "$(dirname "$0")"
PREFIX="${1:?用法: h6sim_full.sh <标签前缀> <store 目录>}"
STORE="${2:?用法: h6sim_full.sh <标签前缀> <store 目录>}"
JAR=../../../simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
LOG="/tmp/h6sim-server-$PREFIX.log"
GUI=5827
SERVER_PID=""

start_server() {
  echo "[sim] 起服务 store=$STORE（$(date +%H:%M:%S)）"
  setsid ../../../tools/run-shaded.sh "$JAR" --store "$STORE" \
    --gui-port 5827 --mcp-port 5725 --approval-port 5723 >>"$LOG" 2>&1 </dev/null &
  SERVER_PID=$!
  for _ in $(seq 1 40); do
    sleep 3
    if curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:$GUI/index.html"; then
      echo "[sim] 服务就绪 pid=$SERVER_PID（$(date +%H:%M:%S)）"
      return 0
    fi
  done
  echo "[sim] ★ 服务在 120s 内没起来 ⇒ 看 $LOG"
  return 1
}

alive() { curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:$GUI/index.html"; }

trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null' EXIT
start_server || exit 1
for t in 120 240 360 480 600; do
  if [ -f "/tmp/h6raw_${PREFIX}${t}.json" ]; then
    echo "[sim] ${PREFIX}${t} 的原始读数已在 ⇒ 跳过"
    continue
  fi
  for attempt in 1 2 3 4 5; do
    if ! alive; then
      echo "[sim] ★ 服务不在了（第 $attempt 次尝试，$(date +%H:%M:%S)）⇒ 重启"
      start_server || exit 1
    fi
    echo "===== advance -> $t（第 $attempt 次，$(date +%H:%M:%S)）====="
    /usr/bin/time -f "  advance 耗时 %E" \
      python3 ../2026-09-26-year-one-simulation/v3curve_advance.py "$t" 2>&1 | tail -8
    echo "===== dump ${PREFIX}${t}（$(date +%H:%M:%S)）====="
    if python3 h6sim_dump.py "${PREFIX}${t}" 2>&1 | tail -6; then
      break
    fi
    echo "[sim] ★ 落盘失败 ⇒ 重试"
    sleep 5
  done
done
echo "[sim] 本批结束（$(date +%H:%M:%S)）"
