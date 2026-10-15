#!/usr/bin/env bash
# ★★ 批 M（2026-10-15）：多 tick 全真世界模拟的**独立跑法**（用户原话「要跑全真世界模拟自己额外创世界跑，别塞进测试里！」）。
#
# 它跑的是从 JUnit 测试类里搬出来的三段模拟（判据 M1：≥2 段 或 ≥60 tick = 违规）：
#   economy-cycle-health  three-powers 真世界 240 天 + 96 轮市场分布指纹（原 EconomyCycleHealthTest）
#   gov-genesis-120d      小世界 120 天逐日推进（原 GovGenesisZ6Test）
#   segment-equivalence   30 天一次推进 vs 30 次逐日推进（原 SegmentEquivalenceAcrossEconomyGovSocialTest）
#
# ★ 它**不进** `clean verify`：run 的是 test-scope 的 `*Sim`/`*Runner`（surefire 缺省 include 只收
#   Test 前缀 / Test 后缀 / Tests / TestCase 四种类名 ⇒ 门禁永远不会碰它们）。
# ★ 隔离：每个 case 一个自己的空 store，落在 `test-world/multitick/<case>/`（仓内代码测试副本那一侧，
#   .gitignore 排除），端口一律 0；**不碰** test-world/store（simos-testworld 服务在用）、
#   更不碰 ~/Simos-18Lvt/world（全真世界）。
#
# 用法：
#   tools/multitick-sim.sh                          # 三个 case 全跑（约 3~4 分钟）
#   tools/multitick-sim.sh economy-cycle-health      # 只跑一个（约 2 分钟，占大头）
#   tools/multitick-sim.sh all --store-root=/tmp/x   # 换 store 根
#   SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh gov-genesis-120d   # 跳过编译（刚编过时）
#
# 退出码：0 = 全部 case 通过；1 = 有 case 红（判据/断言失败，详见输出）；3 = 用法错误；Maven 失败 = 它的码。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CASE="all"
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --*) POSITIONAL+=("$arg") ;;
    *) if [ "$CASE" = "all" ]; then CASE="$arg"; else POSITIONAL+=("$arg"); fi ;;
  esac
done

# ── ① 编译（本仓一次一个 Maven：走锁；★ 不装 jar、不 shade）─────────────────────────────
if [ "${SIMOS_MULTITICK_SKIP_BUILD:-0}" != "1" ]; then
  echo "[multitick-sim] test-compile（simos-app -am）…"
  tools/mvn-lock.sh -q test-compile -pl simos-app -am
fi

# ── ② classpath：★ 本仓 target/classes 一律排在依赖 jar 之前 ────────────────────────────
#    （依赖里的 simos-* SNAPSHOT jar 来自 ~/.m2，可能是旧字节 —— 让本仓当前代码绝对优先）
CP_FILE="simos-app/target/test-classpath.txt"
if [ ! -s "$CP_FILE" ]; then
  echo "[multitick-sim] 生成依赖 classpath（$CP_FILE）…"
  tools/mvn-lock.sh -q -o dependency:build-classpath \
    -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test -pl simos-app -am
fi

CP="simos-app/target/test-classes:simos-app/target/classes"
for dir in simos-*/target/classes; do
  [ -d "$dir" ] && CP="$CP:$dir"
done
CP="$CP:$(cat "$CP_FILE")"

# ── ③ 跑（前台；退出码原样透出）────────────────────────────────────────────────────────
echo "[multitick-sim] case=$CASE store-root=${SIMOS_MULTITICK_STORE_ROOT:-test-world/multitick}"
set +e
java -cp "$CP" io.mosire.simos.app.sim.MultiTickSimRunner \
  "--case=$CASE" \
  "--store-root=${SIMOS_MULTITICK_STORE_ROOT:-test-world/multitick}" \
  ${POSITIONAL[@]+"${POSITIONAL[@]}"}
status=$?
set -e
echo "[multitick-sim] 退出码=$status"
exit "$status"
