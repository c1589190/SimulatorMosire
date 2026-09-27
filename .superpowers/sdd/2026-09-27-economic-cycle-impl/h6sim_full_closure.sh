#!/usr/bin/env bash
# ★★ 一整批 600 天模拟：**服务由本脚本自己起**（作为本脚本的子进程）→ 逐关账日推进 + 落原始读数。
#
# 由来（本机实测两次）：把服务用 `nohup ... &` 单独起在一条 bash 调用里，它会在**别的后台作业结束时**
#   收到停止信号（日志 `ShellMain - 收到停止信号`）—— 两次都精确落在"某个受管作业结束"的那一刻。
#   ⇒ 把服务放进**同一个作业的进程树**里，并让它"死了就重启"，跑完由 trap 按 PID 收工。
#
# 用法: ./h6sim_full_closure.sh <标签前缀> <store 目录>
#   关账批（H6 代码）: ./h6sim_full_closure.sh r2 /tmp/simos-v3curve-h6r2
#   ★ 与 base 版的唯一区别：JAR 默认走 target 下**重打包后**的 H6 jar（基线批用 H6_JAR 指到备份的 H5 jar）。
set -uo pipefail
cd "$(dirname "$0")"
PREFIX="${1:?用法: h6sim_full.sh <标签前缀> <store 目录>}"
STORE="${2:?用法: h6sim_full.sh <标签前缀> <store 目录>}"
JAR="${H6_JAR:-/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar}"
LOG="/tmp/h6sim-server-$PREFIX.log"
GUI=5827
SERVER_PID=""

# ★★ 仓库根：`simos.worldgen.initialize` 的参数文件路径**相对 JVM 的 cwd**（Shell 里是 `Path.of("config",…)`）
#   ⇒ 服务必须**从仓根**起，否则世界初始化报"参数文件不存在"（实测踩到，空世界照推 120 天）。
REPO_ROOT="$(cd ../../.. && pwd)"
# ★ JAR 与 run-shaded.sh 都先取**绝对路径**（下面的 `cd` 只改子进程的 cwd，不改这两条路径的解析）
RUNNER="$(cd ../../../tools && pwd)/run-shaded.sh"
JAR_ABS="$JAR"
case "$JAR_ABS" in /*) ;; *) JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")" ;; esac

start_server() {
  echo "[sim] 起服务 store=$STORE（$(date +%H:%M:%S)）"
  (cd "$REPO_ROOT" && setsid "$RUNNER" "$JAR_ABS" --store "$STORE" \
    --gui-port 5827 --mcp-port 5725 --approval-port 5723 >>"$LOG" 2>&1 </dev/null) &
  SERVER_PID=$!
  for _ in $(seq 1 40); do
    sleep 3
    if curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:$GUI/index.html"; then
      # ★★ 记**监听端口那个进程**的 PID（真 JVM），不记子 shell 的 PID：`(cd … && setsid java) &` 的 `$!`
      #   是那个子 shell（它会立刻退出）⇒ 用 $! 收工在"外层是 `( … ) &`"时收不到 java。
      local listening
      listening="$(ss -ltnpH "sport = :$GUI" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)"
      if [ -n "$listening" ]; then
        SERVER_PID="$listening"
      fi
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
# ★★ 世界初始化（本脚本相对 h6sim_full.sh 的唯一加料）：`simos.worldgen.initialize` 的**参数文件路径是相对
#   JVM 进程的 cwd**（= 本脚本 `cd` 到的这个目录）⇒ 在别处起服务就会报"世界生成参数文件不存在"
#   （实测踩到：空世界照推 120 天、读数全是 404）。故这里显式从**仓库根**跑一次 v3curve_setup.py。
if [ ! -f "/tmp/h6raw_${PREFIX}120.json" ] && [ -n "${H6_SETUP:-}" ]; then
  echo "[sim] 世界初始化：$H6_SETUP（$(date +%H:%M:%S)）"
  python3 "$H6_SETUP" || {
    echo "[sim] ★ 世界初始化失败 ⇒ 本批不跑（空世界的读数是垃圾）"
    exit 1
  }
fi
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
