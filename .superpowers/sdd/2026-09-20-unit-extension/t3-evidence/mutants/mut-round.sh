#!/usr/bin/env bash
# T3 变异轮装置（九道门禁，照 T2 的 mut-round.sh 加固）：
#   ① 干净世界（清 target/classes、test-classes、surefire-reports）
#   ② 变异体字节确实不同（md5 比对，相同即作废）
#   ③ 按**白名单**推成目标类名（TARGET 只准是下面 case 里的两条规范路径之一）
#   ④ 清陈旧 .class（见 ①）+ 扫工作目录里规范名之外的 .java（源树内不得有 m?_*.java 残留）
#   ⑤ 强制断言 COMPILATION ERROR = 0，不为 0 当场作废这一轮
#   ⑥ surefire 报告 mtime 必须落在本轮内（round_start 起）+ 汇总行非空（先断言读到非空，再下结论）
#   ⑦ 红点落被保护断言的判别由人读日志核对（本装置把失败清单原文留全）
#   ⑧ 逐字节 cp 还原（绝不 git checkout --）+ 复测 md5
#   ⑨ 日志自指：把本轮 orig/mutant/pushed/restored 四个 md5 追加进日志本身
set -u
LABEL="$1"; TARGET="$2"; MUTANT="$3"; SEL="$4"; LOG="$5"
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO" || exit 2
MOD="simos-unit"

# ── ③ 白名单：目标只准是这两条规范路径 ──────────────────────────────
case "$TARGET" in
  simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/SetFormationOffsetHandler.java) ;;
  *) echo "ABORT($LABEL): TARGET 不在白名单内: $TARGET"; exit 3 ;;
esac

orig_md5=$(md5sum "$TARGET" | awk '{print $1}')
mut_md5=$(md5sum "$MUTANT" | awk '{print $1}')
if [ -z "$orig_md5" ] || [ -z "$mut_md5" ]; then
  echo "ABORT($LABEL): md5 读取为空 —— 先怀疑读取，别怀疑被测物"; exit 3
fi
if [ "$orig_md5" = "$mut_md5" ]; then
  echo "ABORT($LABEL): 变异体与原件字节相同 —— 该轮作废"; exit 3
fi
BACKUP="/tmp/${LABEL}.orig.java"
cp "$TARGET" "$BACKUP"
if [ "$(md5sum "$BACKUP" | awk '{print $1}')" != "$orig_md5" ]; then
  echo "ABORT($LABEL): 备份 md5 不符 —— 该轮作废"; exit 3
fi

# ── ④ 工作目录里规范名之外的 .java（变异体只准活在证据目录） ─────────
stray=$(find "$MOD/src" -name 'm?_*.java' 2>/dev/null | wc -l)
if [ "$stray" != "0" ]; then
  echo "ABORT($LABEL): 源树内有 $stray 个规范名之外的 .java —— 先清干净世界"; exit 3
fi

# ── ① 干净世界 ────────────────────────────────────────────────────
rm -rf "$MOD/target/classes" "$MOD/target/test-classes" "$MOD/target/surefire-reports"
cp "$MUTANT" "$TARGET"
pushed_md5=$(md5sum "$TARGET" | awk '{print $1}')
if [ "$pushed_md5" != "$mut_md5" ]; then
  echo "ABORT($LABEL): 推送后 md5 不符 —— 该轮作废"; cp "$BACKUP" "$TARGET"; exit 3
fi

round_start=$(date +%s)
./mvnw -pl "$MOD" -am test -Dtest="$SEL" -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1
rc=$?
round_end=$(date +%s)

compile_errors=$(grep -c "COMPILATION ERROR" "$LOG")
summary=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
  "$MOD"/target/surefire-reports/*.txt 2>/dev/null | tail -1)
report_mtime_epoch=$(stat -c '%Y' "$MOD"/target/surefire-reports/*.txt 2>/dev/null | sort -n | tail -1)
report_mtime=$(date -d "@${report_mtime_epoch:-0}" -Iseconds 2>/dev/null)
failures=$(grep -hE "^\[ERROR\]   [A-Za-z]" "$LOG" 2>/dev/null | head -20)

cp "$BACKUP" "$TARGET"
restored_md5=$(md5sum "$TARGET" | awk '{print $1}')

# ── ⑤⑥ 门禁判定（作废理由写进日志，谁读谁看得见） ────────────────────
verdict="OK"
[ "$compile_errors" != "0" ] && verdict="VOID(编译错误)"
[ -z "${summary:-}" ] && verdict="VOID(无 surefire 汇总行)"
if [ -n "${report_mtime_epoch:-}" ] && [ "${report_mtime_epoch:-0}" -lt "$round_start" ]; then
  verdict="VOID(surefire 报告 mtime 落本轮之前：陈旧产物)"
fi
[ "$restored_md5" != "$orig_md5" ] && verdict="VOID(还原后 md5 不符)"

{
  echo ""
  echo "===== SELF-REFERENTIAL RECORD ($LABEL) ====="
  echo "label=$LABEL"
  echo "target=$TARGET"
  echo "mutant=$MUTANT"
  echo "selector=$SEL"
  echo "orig_md5=$orig_md5"
  echo "mutant_md5=$mut_md5"
  echo "pushed_md5=$pushed_md5"
  echo "restored_md5=$restored_md5"
  echo "mvn_rc=$rc"
  echo "compile_errors=$compile_errors"
  echo "surefire_summary=${summary:-<none>}"
  echo "surefire_mtime=${report_mtime:-<none>}"
  echo "round_start_epoch=$round_start round_end_epoch=$round_end"
  echo "verdict=$verdict"
  echo "--- 失败清单（原文，供⑦核红点） ---"
  echo "${failures:-<none>}"
  echo "round_end=$(date -Iseconds)"
} >> "$LOG"

echo "SELF $LABEL orig=$orig_md5 mut=$mut_md5 pushed=$pushed_md5 restored=$restored_md5 rc=$rc compile_errors=$compile_errors summary=${summary:-<none>} verdict=$verdict"
