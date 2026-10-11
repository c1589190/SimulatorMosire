#!/usr/bin/env bash
# W7e 测试批：变异自证（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）。
#
# ★ 口径（AGENTS §三.2 与先例 W5.5/W7d）：变异体只**临时**改 src/main，每轮从 pristine 副本还原并立刻 `md5sum -c`；
#   收工 `md5sum -c` 必须逐条 OK ⇒ 交付面（工作树 main 侧）与变异前**逐字节相同**。
#   ★ 硬边界说明：任务书禁改 src/main ⇒ 本脚本**只做临时变异体**，任何一轮结束后都必须还原（trap 兜底）。
set -u
ROOT=/home/cna/SimulatorMosire
PRISTINE=/tmp/w7e/pristine
LOGDIR=/tmp/w7e
LOG=$LOGDIR/mutation.log
mkdir -p "$PRISTINE" "$LOGDIR"
: > "$LOG"

TPL=simos-economy/src/main/java/io/mosire/simos/economy/model/MerchantTradeTemplate.java
EPB=simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java
MIG=simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java
SEEDER=simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java

FILES="$TPL $EPB $MIG $SEEDER"
for f in $FILES; do
  mkdir -p "$PRISTINE/$(dirname "$f")"
  cp "$ROOT/$f" "$PRISTINE/$f"
done
( cd "$PRISTINE" && md5sum $FILES ) > "$PRISTINE/md5s.txt"

restore_all() {
  for f in $FILES; do
    cp "$PRISTINE/$f" "$ROOT/$f"
  done
}
trap restore_all EXIT INT TERM

{ echo "=== pristine md5（变异前的原件字节）==="; cat "$PRISTINE/md5s.txt"; } | tee -a "$LOG"

mutate() { # mutate <file> <old> <new> <label>
  python3 - "$ROOT/$1" "$2" "$3" "$4" <<'PY'
import sys
path, old, new, label = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
s = open(path).read()
n = s.count(old)
if n != 1:
    print("!! [%s] 变异点命中 %d 次（必须恰好 1 次）⇒ 本轮作废" % (label, n))
    sys.exit(9)
open(path, 'w').write(s.replace(old, new))
print("   [%s] 变异写入 OK（命中 1 次）" % label)
PY
}

round_() { # round_ <label> <file> <关键项> <期望红>
  local label=$1 file=$2 item=$3 expect=$4
  {
    echo ""
    echo "### $label ｜ $item"
    echo "    期望红：$expect"
    echo -n "    变异体 md5: "; md5sum "$ROOT/$file" | awk '{print $1}'
  } | tee -a "$LOG"
  # ★ W7e 修正（第一轮实测踩到，假证据）：每轮先删掉上一轮的 surefire 报告。否则「reactor 在 economy 就失败
  #   ⇒ simos-app 根本没跑」时，仍会打印 app 侧**上一轮**的报告（M3–M6 会打印出 M2 的 91L/100L 红）。
  rm -f "$ROOT/simos-economy/target/surefire-reports/io.mosire.simos.economy.time.MerchantOnDemandTradeW7Test.txt" \
        "$ROOT/simos-economy/target/surefire-reports/io.mosire.simos.economy.time.HaulGlobalPoolW7Test.txt" \
        "$ROOT/simos-app/target/surefire-reports/io.mosire.simos.app.world.MerchantTradeScaleAnchorCouplingTest.txt"
  ( cd "$ROOT" && timeout 1800 tools/mvn-lock.sh test -pl simos-app -am \
      -Dtest='MerchantOnDemandTradeW7Test,MerchantTradeScaleAnchorCouplingTest,HaulGlobalPoolW7Test' \
      -Dsurefire.failIfNoSpecifiedTests=false ) > "$LOGDIR/mut-$label.log" 2>&1
  local rc=$?
  echo "    Maven rc=$rc" | tee -a "$LOG"
  grep -E "^\[INFO\] Tests run:|^\[ERROR\] Tests run:" "$LOGDIR/mut-$label.log" | tail -2 | sed 's/^/    /' | tee -a "$LOG"
  for rep in \
    "$ROOT/simos-economy/target/surefire-reports/io.mosire.simos.economy.time.MerchantOnDemandTradeW7Test.txt" \
    "$ROOT/simos-economy/target/surefire-reports/io.mosire.simos.economy.time.HaulGlobalPoolW7Test.txt" \
    "$ROOT/simos-app/target/surefire-reports/io.mosire.simos.app.world.MerchantTradeScaleAnchorCouplingTest.txt"; do
    if [ ! -f "$rep" ]; then
      echo "    ── $(basename "$rep")：本轮**没跑到**（reactor 在更前面的模块就停了）" | tee -a "$LOG"
      continue
    fi
    echo "    ── $(basename "$rep")" | tee -a "$LOG"
    grep -E "^Tests run:" "$rep" | sed 's/^/      /' | tee -a "$LOG"
    grep -E "^[a-zA-Z].*<<< (FAILURE|ERROR)!" "$rep" | sed 's/^/    红: /' | tee -a "$LOG"
    grep -E "^org\.opentest4j|^java\.lang\.AssertionError|^expected:|^ but was:" "$rep" | head -4 | sed 's/^/       /' | tee -a "$LOG"
  done
  cp "$PRISTINE/$file" "$ROOT/$file"
  if ( cd "$PRISTINE" && md5sum -c --quiet md5s.txt ) >/dev/null 2>&1; then
    echo "    还原核验: md5sum -c OK（四份原件逐字节相同）" | tee -a "$LOG"
  else
    echo "    ★★ 还原核验失败：md5sum -c 不通过 —— 立即停手人工核对" | tee -a "$LOG"
  fi
}

# ── M1：跨模块耦合守卫（economy 侧锚改坏：120 → 100）────────────────────────────────────
mutate "$TPL" \
'  public static final long TOOL_PER_UNIT =
      12_000L / 100L; // = 120：分子 = 创世作业资本（毫工具），分母 = 要保住的规模锚（100 单位）' \
'  public static final long TOOL_PER_UNIT = 12_000L / 120L; // M1 变异：锚退回"一个周期的工具投入"' M1 | tee -a "$LOG"
round_ M1 "$TPL" "跨模块耦合守卫（锚 120 → 100 ⇒ 规模 100 → 120）" "MerchantTradeScaleAnchorCouplingTest 的比值/绝对值断言" 

# ── M2：跨模块耦合守卫（app 侧禀赋改坏：12 趟 → 11 趟）──────────────────────────────────
mutate "$SEEDER" \
'  public static final long MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI =
      12L' \
'  public static final long MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI =
      11L' M2 | tee -a "$LOG"
round_ M2 "$SEEDER" "跨模块耦合守卫（禀赋 12,000 → 11,000）" "MerchantTradeScaleAnchorCouplingTest 的比值断言（含真播种世界那条）"

# ── M3：新判据本体（工具维判据失效 ⇒ 缺工具的户被误判可行）──────────────────────────────
mutate "$EPB" \
'    if (toolMilli <= 0L) {
      return "NO_MERCHANT_CAPACITY:no-tool-input";
    }' \
'    if (false) {
      return "NO_MERCHANT_CAPACITY:no-tool-input";
    }' M3 | tee -a "$LOG"
round_ M3 "$EPB" "新判据（劳动 + 工具）的**工具维**" "prospectWithoutToolInputIsInfeasibleByName 的 no-tool-input 断言"

# ── M4：作业资本那一维（TOOL → CATTLE，退回旧口径）──────────────────────────────────────
mutate "$TPL" \
'    return List.of(AssetKind.TOOL);' \
'    return List.of(AssetKind.CATTLE); // M4 变异：作业资本退回牲口' M4 | tee -a "$LOG"
round_ M4 "$TPL" "作业资本 = TOOL（用户裁定取消牲口）" "cattleShareDoesNotSatisfyTheToolInputGate + 迁移正向（带 TOOL 份额）"

# ── M5：理由字面量改名（新名 → 旧名）────────────────────────────────────────────────────
mutate "$MIG" \
'                    "no-tool-input-to-host-on-demand-trade-industry"));' \
'                    "no-carrier-asset-to-host-on-demand-trade-industry"));' M5 | tee -a "$LOG"
round_ M5 "$MIG" "具名理由随裁定改名（§一.11 不留旧名）" "两条 reason=no-tool-input… 的断言"

# ── M6：静默付 0（业务拒绝从 INFO 降到 TRACE ⇒ 公开读数面看不见）────────────────────────
mutate "$MIG" \
'        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",' \
'        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",' M6 | tee -a "$LOG"
round_ M6 "$MIG" "静默付 0（拒绝不再具名可读）" "「ONDEMAND_TRADE_MIGRATION_SKIPPED 具名 INFO」那条断言"

echo "" | tee -a "$LOG"
echo "=== 收工：md5sum -c（必须逐条 OK）+ main 侧工作树状态 ===" | tee -a "$LOG"
( cd "$PRISTINE" && md5sum -c md5s.txt ) | tee -a "$LOG"
( cd "$ROOT" && git status --porcelain -- '*/src/main/*' ) | tee -a "$LOG"
echo "=== MUTATION ROUNDS DONE ===" | tee -a "$LOG"
