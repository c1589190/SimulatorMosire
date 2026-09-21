#!/usr/bin/env bash
# T10 变异轮装置（十道门禁，照 T9 的 mut-round.sh；差异三处）：
#   ① 装置**按 manifest 参数化**：PL / SEL / runner 从 manifest 的 label 块取（T10 有 unit 与 app 两种靶模块）
#   ② 白名单 = manifest 里出现过的全部 `target` 路径（越界即 ABORT）
#   ③ runner 支持两种：`maven`（-Dtest 选择器）与 `frontend`（跑 exec:exec@frontend-unit-tests 那条前端门禁）
#
# 十道门禁：
#   ① 干净世界：TARGET 必须与 baseline-md5.txt 里记的那份逐字节相同
#   ② 变异体字节确实不同（md5 比对）
#   ③ 白名单：TARGET 只准是 manifest 里登记过的路径
#   ④ 清陈旧 .class / surefire + 扫源树里规范名之外的 .java
#   ⑤ 强制断言 COMPILATION ERROR = 0，不为 0 当场作废
#   ⑥ surefire 报告 mtime 落本轮内 + 汇总行非空（frontend 轮以 frontend-gate 行为准）
#   ⑦ 红点落被保护断言：失败清单原文留全，由人读日志核对（manifest 的 expect_red）
#   ⑧ 逐字节 cp 还原（绝不 git checkout --）+ 复测 md5
#   ⑨ 日志自指：orig/mutant/pushed/restored 四个 md5 追加进日志本身
#   ⑩ 自证：revert ⇒ 原件 0 / 变异体 ≥1；delete ⇒ 原件 ≥1 / 变异体 0；count ⇒ 与 manifest 的两个期望值逐值相等。
set -u
LABEL="$1"
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO" || exit 2
BASE_DIR="$REPO/.superpowers/sdd/2026-09-20-unit-extension/t10-evidence"
MANIFEST="$BASE_DIR/mutants/manifest.txt"
BASELINE="$BASE_DIR/mutants/baseline-md5.txt"
LOG="$BASE_DIR/logs/${LABEL}.log"

field() { awk -v l="$LABEL" -v k="$1" '
  $1=="label" {inblock=($2==l); next}
  inblock && $1==k {sub("^"k"[ \t]+",""); print; exit}
' "$MANIFEST"; }

TARGET=$(field target); MUTANT=$(field mutant); PL=$(field pl); SEL=$(field sel)
RUNNER=$(field runner); FRAG=$(field frag); FRAG_DIR=$(field frag_dir)
if [ -z "$TARGET" ] || [ -z "$MUTANT" ] || [ -z "$FRAG" ] || [ -z "$FRAG_DIR" ]; then
  echo "ABORT($LABEL): manifest 缺字段"; exit 3
fi
[ -z "$RUNNER" ] && RUNNER="maven"
MUTANT_PATH="$BASE_DIR/mutants/$MUTANT"

# ── ③ 白名单：TARGET 必须登记在 manifest 的 target 集合里 ──────────────
if ! awk '$1=="target"{print $2}' "$MANIFEST" | grep -qxF "$TARGET"; then
  echo "ABORT($LABEL): TARGET 不在白名单内: $TARGET"; exit 3
fi
if [ ! -f "$TARGET" ] || [ ! -f "$MUTANT_PATH" ]; then
  echo "ABORT($LABEL): 目标或变异体不存在"; exit 3
fi

orig_md5=$(md5sum "$TARGET" | awk '{print $1}')
mut_md5=$(md5sum "$MUTANT_PATH" | awk '{print $1}')
if [ -z "$orig_md5" ] || [ -z "$mut_md5" ]; then
  echo "ABORT($LABEL): md5 读取为空 —— 先怀疑读取，别怀疑被测物"; exit 3
fi
if [ "$orig_md5" = "$mut_md5" ]; then
  echo "ABORT($LABEL): 变异体与原件字节相同 —— 该轮作废"; exit 3
fi

# ── ① 干净世界（基线锚定） ─────────────────────────────────────────────
base_md5=$(awk -v p="$TARGET" '$2==p {print $1}' "$BASELINE")
if [ -z "$base_md5" ]; then
  echo "ABORT($LABEL): 基线文件里没有 $TARGET 的记录"; exit 3
fi
if [ "$orig_md5" != "$base_md5" ]; then
  echo "ABORT($LABEL): 本轮起点不是干净世界 —— 当前 $orig_md5 != 基线 $base_md5"; exit 3
fi
BACKUP="/tmp/${LABEL}.orig.bak"
cp "$TARGET" "$BACKUP"
if [ "$(md5sum "$BACKUP" | awk '{print $1}')" != "$orig_md5" ]; then
  echo "ABORT($LABEL): 备份 md5 不符 —— 该轮作废"; exit 3
fi

# ── ④ 源树里规范名之外的 .java（变异体只准活在证据目录） ───────────────
stray=$(find . -path '*/src/*' \( -name 't10*_*.java' -o -name 't10r-*.java' \) 2>/dev/null | wc -l)
if [ "$stray" != "0" ]; then
  echo "ABORT($LABEL): 源树内有 $stray 个规范名之外的 .java —— 先清干净世界"; exit 3
fi

MODULE_DIR="${TARGET%%/src/*}"
rm -rf "$MODULE_DIR/target/classes" "$MODULE_DIR/target/test-classes" "$MODULE_DIR/target/surefire-reports"
cp "$MUTANT_PATH" "$TARGET"
pushed_md5=$(md5sum "$TARGET" | awk '{print $1}')
if [ "$pushed_md5" != "$mut_md5" ]; then
  echo "ABORT($LABEL): 推送后 md5 不符 —— 该轮作废"; cp "$BACKUP" "$TARGET"; exit 3
fi

# ── ⑩ 自证（按方向判；只数**代码片段**） ──────────────────────────────
orig_hits=$(grep -cF "$FRAG" "$BACKUP" || true)
pushed_hits=$(grep -cF "$FRAG" "$TARGET" || true)
site_proof="frag=[$FRAG] dir=$FRAG_DIR orig_hits=$orig_hits pushed_hits=$pushed_hits"
case "$FRAG_DIR" in
  revert)
    if [ "$orig_hits" != "0" ] || [ "$pushed_hits" -lt 1 ]; then
      echo "ABORT($LABEL): 自证未过（revert 要求 原件 0 / 变异体 ≥1）: $site_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi ;;
  delete)
    if [ "$orig_hits" -lt 1 ] || [ "$pushed_hits" != "0" ]; then
      echo "ABORT($LABEL): 自证未过（delete 要求 原件 ≥1 / 变异体 0）: $site_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi ;;
  count)
    exp_o=$(field orig_hits_expected); exp_p=$(field pushed_hits_expected)
    if [ "$orig_hits" != "$exp_o" ] || [ "$pushed_hits" != "$exp_p" ]; then
      echo "ABORT($LABEL): 自证未过（count 要求 $exp_o/$exp_p）: $site_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi ;;
  *) echo "ABORT($LABEL): 未知 frag_dir: $FRAG_DIR"; cp "$BACKUP" "$TARGET"; exit 3 ;;
esac

round_start=$(date +%s)
if [ "$RUNNER" = "frontend" ]; then
  # 只跑前端门禁：-Dtest 选一个不存在的类 ⇒ surefire 空跑，exec:exec 的前端门禁照跑（rc 反映它）。
  ./mvnw -o -pl simos-app -am test -Dtest=NoSuchJavaTest -Dsurefire.failIfNoSpecifiedTests=false \
    > "$LOG" 2>&1
  rc=$?
else
  ./mvnw -o -pl "$PL" -am test -Dtest="$SEL" -Dsurefire.failIfNoSpecifiedTests=false ${EXTRA:-} \
    > "$LOG" 2>&1
  rc=$?
fi
round_end=$(date +%s)

compile_errors=$(grep -c "COMPILATION ERROR" "$LOG")
frontend_line=$(grep -hE "^\[frontend-gate\]" "$LOG" | tail -1)
summary=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
  "$MODULE_DIR/target/surefire-reports/"*.txt 2>/dev/null | tail -1)
report_mtime_epoch=$(stat -c '%Y' "$MODULE_DIR/target/surefire-reports/"*.txt 2>/dev/null | sort -n | tail -1)
report_mtime=$(date -d "@${report_mtime_epoch:-0}" -Iseconds 2>/dev/null)
module_summary=$(grep -hE "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures" "$LOG" 2>/dev/null | tail -1)
failures=$(grep -hE "^\[ERROR\]   [A-Za-z]" "$LOG" 2>/dev/null | head -40)

restored_md5=""
cp "$BACKUP" "$TARGET"
restored_md5=$(md5sum "$TARGET" | awk '{print $1}')

{
  echo "──────────────────────────────────────────────────────────────"
  echo "round            $LABEL"
  echo "target           $TARGET"
  echo "mutant           $MUTANT"
  echo "runner           $RUNNER  pl=$PL sel=${SEL:-<frontend>}"
  echo "started          $(date -d "@$round_start" -Iseconds)"
  echo "finished         $(date -d "@$round_end" -Iseconds)"
  echo "elapsed_s        $((round_end - round_start))"
  echo "mvn_rc           $rc"
  echo "① orig_md5       $orig_md5"
  echo "    baseline     ${base_md5:-<未提供>}"
  echo "② mutant_md5     $mut_md5"
  echo "⑧ pushed_md5     $pushed_md5"
  echo "⑧ restored_md5   $restored_md5"
  echo "⑧ restored==orig $([ "$restored_md5" = "$orig_md5" ] && echo yes || echo NO)"
  echo "⑤ compile_errors $compile_errors"
  echo "⑥ report_mtime   $report_mtime  (round_start=$(date -d "@$round_start" -Iseconds))"
  echo "⑥ summary        $summary"
  echo "⑥ module_summary $module_summary"
  echo "⑥ frontend_gate  ${frontend_line:-<无>}"
  echo "⑩ self_proof     $site_proof"
  echo "⑦ failures:"
  printf '%s\n' "${failures:-<无失败行>}"
  echo "══════════════════════════════════════════════════════════════"
} >> "$LOG"

echo "ROUND $LABEL rc=$rc elapsed=$((round_end - round_start))s compile_errors=$compile_errors"
echo "  $site_proof"
echo "  restored==orig: $([ "$restored_md5" = "$orig_md5" ] && echo yes || echo NO)"
echo "  ${module_summary:-$frontend_line}"
printf '%s\n' "${failures:-<无失败行>}"
