#!/usr/bin/env bash
# M8 T1 变异轮装置（Java 源 + 可选端点 e2e）。九道门禁：
#   干净世界基线绿 → 变异体字节不同 → 白名单推成目标类名并清陈旧 .class → COMPILATION ERROR=0 且 Tests run>=1
#   → surefire mtime 落本轮 → 红点落被保护断言 → cp 逐字节还原（绝不 git checkout --）→ 日志自指。
# 用法: mut-round.sh <round-id> <mutant-java> <target-relpath> <expect-regex> <with-e2e yes|no> <port>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t1
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t1-evidence"
RID="$1"
MUT="$2"
TGT="$3"
EXPECT_RE="$4"
WITH_E2E="${5:-no}"
PORT="${6:-5941}"
cd "$ROOT" || exit 2
LOG="$EV/logs/$RID.log"
OUT="$EV/mut-runs/$RID"
SRC="$ROOT/$TGT"
BASE="$(basename "$TGT")"
ORIG_BACKUP="$EV/mutants/orig/$BASE"
TESTS='RegionIndexTest,RegionIndexGuardTest,MapResolverTest,GameMapTest,GuiApiTest'
mkdir -p "$EV/logs" "$OUT"

exec >"$LOG" 2>&1
restore() { cp "$ORIG_BACKUP" "$SRC" 2>/dev/null || true; }
trap restore EXIT

echo "===== 变异轮 $RID ====="
echo "target=$TGT mutant=$MUT with_e2e=$WITH_E2E expect=$EXPECT_RE"

[ -f "$ORIG_BACKUP" ] || { echo "FATAL: 缺原件备份"; exit 2; }
ORIG_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
BACKUP_MD5=$(md5sum "$ORIG_BACKUP" | cut -d' ' -f1)
[ -n "$ORIG_MD5" ] || { echo "FATAL: 源 md5 读取为空"; exit 2; }
[ -n "$BACKUP_MD5" ] || { echo "FATAL: 备份 md5 读取为空"; exit 2; }
[ "$ORIG_MD5" = "$BACKUP_MD5" ] || { echo "FATAL: 源与备份不一致，干净世界不成立"; exit 2; }
echo "orig_md5=$ORIG_MD5 backup_md5=$BACKUP_MD5"

echo "--- 干净世界基线（变异前，期望绿）---"
./mvnw -q -pl simos-app -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG.clean" 2>&1
CLEAN_RC=$?
CLEAN_COMP_ERR=$(grep -c "COMPILATION ERROR" "$LOG.clean" || true)
CLEAN_TESTS=$(grep -oE "Tests run: [0-9]+" "$LOG.clean" | tail -1)
echo "clean_rc=$CLEAN_RC clean_compilation_error=$CLEAN_COMP_ERR clean_${CLEAN_TESTS}"
[ "$CLEAN_RC" = "0" ] || { echo "FATAL: 干净世界基线不绿（作废本轮）"; exit 2; }
[ "$CLEAN_COMP_ERR" = "0" ] || { echo "FATAL: 干净世界有编译错误（作废本轮）"; exit 2; }
cat "$LOG.clean" >> "$LOG"

MUT_MD5=$(md5sum "$MUT" | cut -d' ' -f1)
[ -n "$MUT_MD5" ] || { echo "FATAL: 变异体 md5 为空"; exit 2; }
[ "$MUT_MD5" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }
echo "mutant_md5=$MUT_MD5"

cp "$MUT" "$SRC"
MODULE="$(echo "$TGT" | cut -d/ -f1)"
CLASS_REL="$(echo "$TGT" | sed -E 's#^[^/]+/src/main/java/##; s#\.java$#.class#')"
CLS="$ROOT/$MODULE/target/classes/$CLASS_REL"
rm -f "$CLS"
PUSHED_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
[ -n "$PUSHED_MD5" ] || { echo "FATAL: 推送后源 md5 为空"; exit 2; }
[ "$PUSHED_MD5" = "$MUT_MD5" ] || { echo "FATAL: 推送后源不是变异体"; exit 2; }
echo "pushed_src_md5=$PUSHED_MD5 class_removed=$CLASS_REL"

ROUND_START=$(date +%s)
./mvnw -q -pl simos-app -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG.mut" 2>&1
MUT_RC=$?
cat "$LOG.mut" >> "$LOG"
MUT_COMP_ERR=$(grep -c "COMPILATION ERROR" "$LOG.mut" || true)
MUT_TESTS_LINE=$(grep -oE "Tests run: [0-9]+" "$LOG.mut" | tail -1)
MUT_TESTS_N=$(echo "$MUT_TESTS_LINE" | grep -oE "[0-9]+" || true)
echo "mut_rc=$MUT_RC mut_compilation_error=$MUT_COMP_ERR mut_${MUT_TESTS_LINE}"
[ "$MUT_COMP_ERR" = "0" ] || { echo "FATAL: 变异轮编译错误（作废本轮）"; restore; trap - EXIT; exit 2; }
[ -n "$MUT_TESTS_N" ] && [ "$MUT_TESTS_N" -ge 1 ] || { echo "FATAL: 变异轮没有跑到任何用例（作废本轮）"; restore; trap - EXIT; exit 2; }

NEW_REPORTS=$(find "$ROOT" -path '*/target/surefire-reports/*.txt' -newermt "@$ROUND_START" 2>/dev/null || true)
echo "--- 本轮 surefire 报告（mtime 落轮内）---"
echo "$NEW_REPORTS"
if [ -n "$NEW_REPORTS" ]; then
  FAIL_LINES=$(echo "$NEW_REPORTS" | xargs grep -hE "<<< FAILURE" 2>/dev/null || true)
  echo "--- 失败方法 ---"
  echo "$FAIL_LINES" | grep -oE "[A-Za-z0-9_]+\([A-Za-z0-9_.]+\)" | sort -u || true
  HIT=$(echo "$NEW_REPORTS" | xargs grep -hE "$EXPECT_RE" 2>/dev/null | grep -c "<<< FAILURE" || true)
else
  HIT=0
fi
echo "protected_assertion_hits=$HIT"
[ "$HIT" -ge 1 ] || { echo "FATAL: 红点未落在被保护断言（作废本轮）"; restore; trap - EXIT; exit 2; }

E2E_RC="skipped"
if [ "$WITH_E2E" = "yes" ]; then
  echo "--- 端点 e2e（变异类已编入 target/classes）---"
  bash "$EV/e2e/run-e2e.sh" "$PORT" /tmp/m6-import-verify/test_integration "$OUT" "$EV/logs/$RID.server.log" >> "$LOG" 2>&1
  E2E_RC=$?
  echo "e2e_rc=$E2E_RC"
  grep -E "^STEP .*FAIL|E2E RESULT" "$LOG" | tail -12 || true
fi

restore
trap - EXIT
RESTORED_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
[ -n "$RESTORED_MD5" ] || { echo "FATAL: 还原后 md5 读取为空"; exit 2; }
[ "$RESTORED_MD5" = "$ORIG_MD5" ] || { echo "FATAL: 还原后与原件不一致"; exit 2; }
echo "restored_md5=$RESTORED_MD5 (== orig)"

{
  echo ""
  echo "===== 装置补记（$RID）：本轮字节自指 ====="
  echo "target=$TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$MUT_MD5"
  echo "pushed_src_md5=$PUSHED_MD5"
  echo "restored_md5=$RESTORED_MD5"
  echo "mut_rc=$MUT_RC e2e_rc=$E2E_RC protected_assertion_hits=$HIT"
} >> "$LOG"
echo "ROUND $RID DONE mut_rc=$MUT_RC e2e_rc=$E2E_RC hits=$HIT restored=$RESTORED_MD5"
