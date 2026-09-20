#!/usr/bin/env bash
# T1 变异轮装置（九道门禁）：
#   ① 干净世界（清 target/classes、test-classes、surefire-reports）
#   ② 变异体字节确实不同（md5 比对，相同即作废）
#   ③ 按白名单推成目标类名（mutant 文件本身就是目标类名，直接 cp 到 TARGET）
#   ④ 清陈旧 .class（见 ①）
#   ⑤ 断言 COMPILATION ERROR = 0 且 surefire 有 Tests run
#   ⑥ 记录 surefire 报告 mtime（须落本轮）
#   ⑦ 红点落被保护断言的判别由人读日志核对（本装置把日志留全）
#   ⑧ 逐字节 cp 还原（绝不 git checkout --）
#   ⑨ 日志自指：把本轮 orig/mutant/pushed/restored 四个 md5 追加进日志本身
set -u
LABEL="$1"; TARGET="$2"; MUTANT="$3"; SEL="$4"; LOG="$5"
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO" || exit 2
MOD="simos-unit"

orig_md5=$(md5sum "$TARGET" | awk '{print $1}')
mut_md5=$(md5sum "$MUTANT" | awk '{print $1}')
if [ "$orig_md5" = "$mut_md5" ]; then
  echo "ABORT($LABEL): 变异体与原件字节相同 —— 该轮作废"
  exit 3
fi
BACKUP="/tmp/${LABEL}.orig.java"
cp "$TARGET" "$BACKUP"
backup_md5=$(md5sum "$BACKUP" | awk '{print $1}')
if [ "$backup_md5" != "$orig_md5" ]; then
  echo "ABORT($LABEL): 备份 md5 不符 —— 该轮作废"
  exit 3
fi

rm -rf "$MOD/target/classes" "$MOD/target/test-classes" "$MOD/target/surefire-reports"
cp "$MUTANT" "$TARGET"
pushed_md5=$(md5sum "$TARGET" | awk '{print $1}')
if [ "$pushed_md5" != "$mut_md5" ]; then
  echo "ABORT($LABEL): 推送后 md5 不符 —— 该轮作废"
  cp "$BACKUP" "$TARGET"
  exit 3
fi

./mvnw -pl "$MOD" -am test -Dtest="$SEL" -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1
rc=$?

compile_errors=$(grep -c "COMPILATION ERROR" "$LOG")
report_mtime=$(stat -c '%y' "$MOD"/target/surefire-reports/*.txt 2>/dev/null | tail -1)
summary=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
  "$MOD"/target/surefire-reports/*.txt 2>/dev/null | tail -1)

cp "$BACKUP" "$TARGET"
restored_md5=$(md5sum "$TARGET" | awk '{print $1}')

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
  echo "round_end=$(date -Iseconds)"
} >> "$LOG"

echo "SELF $LABEL orig=$orig_md5 mut=$mut_md5 pushed=$pushed_md5 restored=$restored_md5 rc=$rc compile_errors=$compile_errors summary=${summary:-<none>}"
