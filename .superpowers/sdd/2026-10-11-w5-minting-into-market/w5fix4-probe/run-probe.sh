#!/usr/bin/env bash
# W5-fix4 只读探针（**不进仓、不进 Maven 门禁**）：4 个铸币轮验证"本轮 ≤ 上一轮印量"。
#
# ★ classpath 口径（W5-fix3 留下的坑，必须照做）：模块 target/classes 前置 + 剔除 ~/.m2/io/mosire/simos-*
#   （只保留 agentlib-mosire，它不是 simos-* 模块）。
#
# 前置：tools/mvn-lock.sh -q spotless:apply && tools/mvn-lock.sh -DskipTests compile -am
# 用法：bash /tmp/w5fix4-probe/run-probe.sh [INFO|DEBUG]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${ROOT:-/home/cna/SimulatorMosire}"
CP="$(cat /tmp/simos-clean-cp.txt)"
LEVEL="${1:-INFO}"

# ── 自证 classpath 口径：不许出现 ~/.m2 里的 simos-* 构件 ──────────────────────────────
STALE="$(printf '%s' "$CP" | tr ':' '\n' | grep -c 'm2/repository/io/mosire/simos-' || true)"
echo "[probe] stale simos-* jars on classpath = $STALE (must be 0)"
if [ "$STALE" != "0" ]; then
  echo "[probe] REFUSE: classpath 含 ~/.m2 里的 simos-* 构件（W5-fix3 的坑）" >&2
  exit 2
fi
FRONT="$(printf '%s' "$CP" | tr ':' '\n' | head -1)"
echo "[probe] first classpath entry = $FRONT (module target/classes 前置)"
echo "[probe] economy classes mtime = $(stat -c '%y' "$ROOT/simos-economy/target/classes/io/mosire/simos/economy/time/MintSettlement.class")"
echo "[probe] economy classes md5   = $(md5sum "$ROOT/simos-economy/target/classes/io/mosire/simos/economy/time/MintSettlement.class" | cut -d' ' -f1)"

# ★ /tmp/simos-clean-cp.txt 里的模块条目是**相对仓根**的路径 ⇒ 必须在仓根下编译/运行。
cd "$ROOT"
OUT="$HERE/classes"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$HERE/W5Fix4Probe.java"

java -cp "$OUT:$CP" -Dsimos.economy.logLevel="$LEVEL" W5Fix4Probe "${2:-4}" "${3:-}"
