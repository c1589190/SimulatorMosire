#!/usr/bin/env bash
# ★★ R0 / P0 性能基线测量装置（不依赖任何测试框架；本阶段只交付装置，不在开发期执行 package）。
#
# 用法（先按 AGENTS.md §二 停掉在跑的服务；本脚本不负责停服务）：
#   tools/r0-perf-measure.sh <shaded-jar> <store-dir> <from-tick> <to-tick>
#
# 推进命令由环境变量 ADVANCE_CMD 提供，其中可用 {from} / {to} 占位：
#   ADVANCE_CMD='python3 .superpowers/sdd/2026-09-26-year-one-simulation/v3curve_advance.py \
#                  --store /path --from {from} --to {to}' tools/r0-perf-measure.sh ...
#
# 口径（计划 §4.7 / §7.1）：同一 store、同一 JVM、同一 jar；每档跑 3 次取中位数；
# 输出同时落盘到 OUT（缺省 tools/r0-perf-<时间戳>.log），包含机器/JVM/代码态与每次墙钟。
# 本脚本不读 JFR、不改状态、不做统计判断——它只负责"把可比条件下的读数钉下来"。
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 4 ]]; then
  sed -n '2,20p' "$0"
  exit 0
fi

JAR=$1
STORE=$2
FROM=$3
TO=$4
ROUNDS=${ROUNDS:-3}
OUT=${OUT:-"tools/r0-perf-$(date +%Y%m%d-%H%M%S).log"}
ADVANCE_CMD=${ADVANCE_CMD:-}

[[ -f "$JAR" ]] || { echo "shaded jar 不存在: $JAR（R0 基线须用未改代码态的 jar）" >&2; exit 2; }
[[ -d "$STORE" ]] || { echo "store 目录不存在: $STORE" >&2; exit 2; }
[[ -n "$ADVANCE_CMD" ]] || { echo "必须用 ADVANCE_CMD 指定推进命令（见脚本头部注释）" >&2; exit 2; }

# ★ 一次只能跑一个 Maven（AGENTS.md §一.1）：有 Maven 在跑时读数不可信。
if pgrep -af 'surefirebooter|classworlds.launcher' >/dev/null 2>&1; then
  echo "检测到 Maven/Surefire 在跑：拒绝测量（读数不可信，AGENTS.md §一.1）" >&2
  pgrep -af 'surefirebooter|classworlds.launcher' >&2 || true
  exit 3
fi

{
  echo "# R0/P0 性能基线"
  echo "time=$(date -Is)"
  echo "git=$(git -C "$(dirname "$0")/.." rev-parse HEAD 2>/dev/null || echo '(not a git tree)')"
  echo "jar=$JAR"
  echo "jar_md5=$(md5sum "$JAR" | awk '{print $1}')"
  echo "store=$STORE from=$FROM to=$TO rounds=$ROUNDS"
  echo "advance_cmd=$ADVANCE_CMD"
  echo "uname=$(uname -a)"
  echo "nproc=$(nproc 2>/dev/null || echo '?')"
  free -h | sed 's/^/mem: /' | head -3
  java -version 2>&1 | sed 's/^/jvm: /'
  echo "# rounds"
} | tee "$OUT"

times=()
for ((i = 1; i <= ROUNDS; i++)); do
  cmd=${ADVANCE_CMD//\{from\}/$FROM}
  cmd=${cmd//\{to\}/$TO}
  echo "== round $i/$ROUNDS: $cmd" | tee -a "$OUT"
  start=$(date +%s.%N)
  # shellcheck disable=SC2086
  set +e
  bash -lc "$cmd" >>"$OUT" 2>&1
  rc=$?
  set -e
  end=$(date +%s.%N)
  wall=$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.3f", e - s }')
  times+=("$wall")
  echo "round=$i rc=$rc wall_s=$wall" | tee -a "$OUT"
done

printf '%s\n' "${times[@]}" | sort -n | awk -v out="$OUT" '
  { a[NR]=$1 }
  END {
    mid = (NR % 2 == 1) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2;
    printf "median_s=%.3f (n=%d; 各轮: %s)\n", mid, NR, a[1]; for (i=2;i<=NR;i++) printf ",%s", a[i];
  }' | tee -a "$OUT"
