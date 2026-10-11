#!/usr/bin/env bash
# W7 测试批：四类关键项的**变异自证**（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）。
#
# ★ 口径（AGENTS §三.2 与本仓测试批先例 W5.5）：变异体只**临时**改 src/main，改完立刻从 pristine 副本还原，
#   每轮与收工都 `md5sum -c` ⇒ 交付面（工作树）一字节未动。这不是"改 main"，是"证明护栏有牙"。
set -u
ROOT=/home/cna/SimulatorMosire
PRISTINE=/tmp/w7t/pristine
LOG=/tmp/w7t/mutation.log
mkdir -p "$PRISTINE"
: > "$LOG"

MIG=simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java
POOL=simos-economy/src/main/java/io/mosire/simos/economy/time/MerchantCapacityPool.java
BOOK=simos-economy/src/main/java/io/mosire/simos/economy/time/OwnershipStakeBook.java
TPL=simos-economy/src/main/java/io/mosire/simos/economy/model/MerchantTradeTemplate.java

for f in "$MIG" "$POOL" "$BOOK" "$TPL"; do
  mkdir -p "$PRISTINE/$(dirname "$f")"
  cp "$ROOT/$f" "$PRISTINE/$f"
done
( cd "$PRISTINE" && md5sum "$MIG" "$POOL" "$BOOK" "$TPL" ) > "$PRISTINE/md5s.txt"
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
  ( cd "$ROOT" && timeout 1800 tools/mvn-lock.sh test -pl simos-economy -am \
      -Dtest='HaulGlobalPoolW7Test,MerchantOnDemandTradeW7Test' \
      -Dsurefire.failIfNoSpecifiedTests=false ) > "/tmp/w7t/mut-$label.log" 2>&1
  local rc=$?
  echo "    Maven rc=$rc" | tee -a "$LOG"
  grep -E "Tests run:.*(Failures|Errors)" "/tmp/w7t/mut-$label.log" | tail -2 | sed 's/^/    /' | tee -a "$LOG"
  for cls in MerchantOnDemandTradeW7Test HaulGlobalPoolW7Test; do
    local rep="$ROOT/simos-economy/target/surefire-reports/io.mosire.simos.economy.time.$cls.txt"
    [ -f "$rep" ] || continue
    grep -E "^io\.mosire.*<<< (FAILURE|ERROR)!" "$rep" | sed 's/^/    红: /' | tee -a "$LOG"
    grep -E "^\[|^java\.lang|^org\.opentest4j" "$rep" | head -4 | sed 's/^/       /' | tee -a "$LOG"
  done
  cp "$PRISTINE/$file" "$ROOT/$file"
  if ( cd "$PRISTINE" && md5sum -c --quiet md5s.txt ) >/dev/null 2>&1; then
    echo "    还原核验: md5sum -c OK（四份原件逐字节相同）" | tee -a "$LOG"
  else
    echo "    ★★ 还原核验失败：md5sum -c 不通过 —— 立即停手人工核对" | tee -a "$LOG"
  fi
}

# ── M1：守恒式（按人口比例的份额切分口径）──────────────────────────────────────────────
mutate "$MIG" \
'      long take =
          empties
              ? share.quantity()
              : Math.multiplyExact(share.quantity(), popTake) / Math.max(1L, populationBefore);' \
'      long take = share.quantity();' M1 | tee -a "$LOG"
round_ M1 "$MIG" "守恒式（跨 hex 随迁按人口比例 floor）" "「源留 5 / 目标得 3」那条比例断言"

# ── M1′：守恒式本体（目标侧凭空 +1 ⇒ Σ CATTLE 变了；书的 (industry,asset) 自检抓不到它）──
mutate "$BOOK" \
'              Math.addExact(before, movedIn.getOrDefault(id, 0L)), movedOut.getOrDefault(id, 0L));' \
'              Math.addExact(before, Math.addExact(movedIn.getOrDefault(id, 0L), 1L)), movedOut.getOrDefault(id, 0L));' \
  M1P | tee -a "$LOG"
round_ M1P "$BOOK" "守恒式本体（Σ CATTLE 凭空 +1）" "「CATTLE 总量前后逐值相同」那条 Σ 恒等式"

# ── M2：不丢失（配方/身份：跑商模板不再声明承运资产需求）────────────────────────────────
mutate "$TPL" \
'    capacityPerUnit.put(AssetKind.CATTLE, CATTLE_PER_UNIT);' \
'    // M2 变异：模板不再声明承运资产需求（配方身份丢失）' M2 | tee -a "$LOG"
round_ M2 "$TPL" "不丢失（配方/身份）" "「模板必须声明 CATTLE」那条断言"

# ── M3：静默付 0（业务拒绝从 INFO 降到 TRACE ⇒ 公开读数面看不见）────────────────────────
mutate "$MIG" \
'        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",' \
'        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",' M3 | tee -a "$LOG"
round_ M3 "$MIG" "静默付 0（拒绝不再具名可读）" "「ONDEMAND_TRADE_MIGRATION_SKIPPED 具名 INFO」那条断言"

# ── M4：断粮（运力到不了 ⇒ 货发不出：候选集退回逐格）────────────────────────────────────
mutate "$POOL" \
'    for (Entry item : globalOrder) {
      if (demandLeft <= 0L) {' \
'    for (Entry item : pool) {
      if (demandLeft <= 0L) {' M4 | tee -a "$LOG"
round_ M4 "$POOL" "断粮（全局池退回逐格 ⇒ 别格运力到不了）" "「跨格分配」那条正向断言"

# ── M5：口径一致（服务货口径 / 劳动+工具口径混接）──────────────────────────────────────
mutate "$POOL" \
'      if (haulServiceHexes.contains(from) != haulServiceHexes.contains(item.capacity.hex())) {' \
'      if (haulServiceHexes.contains(from) == haulServiceHexes.contains(item.capacity.hex())) {' \
  M5 | tee -a "$LOG"
round_ M5 "$POOL" "口径一致（两类承运方不许混接）" "「未定价 lane 不得被服务货口径承运方承接」那条负向"

echo "" | tee -a "$LOG"
echo "=== 收工：md5sum -c（必须逐条 OK）+ main 侧工作树状态 ===" | tee -a "$LOG"
( cd "$PRISTINE" && md5sum -c md5s.txt ) | tee -a "$LOG"
( cd "$ROOT" && git status --porcelain -- '*/src/main/*' ) | tee -a "$LOG"
echo "=== MUTATION ROUNDS DONE ===" | tee -a "$LOG"
