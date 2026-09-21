#!/usr/bin/env bash
# T9 变异轮装置（十道门禁，照 T8 的 mut-round.sh；差异四处）：
#   ① 白名单换成 T9 的唯一靶文件 Shell.java（改 Shell = 改动既有装配点 ⇒ 裁定 42 自带重跑轮）
#   ② 模块面从 `-pl simos-unit,simos-core` 换成 `-pl simos-app -am`（T9 改的是组合根）
#   ③ 选择器 = T9 直接触及的两个测试类（catalog/注册面的两条判据都在这里）
#   ④ 其余九道与 T8 逐字同法（baseline md5 锚定 / 逐字节还原 / 日志自指 / ⑩ 按片段+方向自证）
#
# 十道门禁：
#   ① 干净世界：本轮开跑前 TARGET 必须与 baseline-md5.txt 里记的那份**逐字节相同**
#   ② 变异体字节确实不同（md5 比对，相同即作废）
#   ③ 按**白名单**推成目标路径（TARGET 只准是下面 case 里的规范路径）
#   ④ 清陈旧 .class / surefire + 扫源树里规范名之外的 .java（不得有 t9m*_*.java 残留）
#   ⑤ 强制断言 COMPILATION ERROR = 0，不为 0 当场作废这一轮
#   ⑥ surefire 报告 mtime 必须落在本轮内（round_start 起）+ 汇总行非空
#   ⑦ 红点落被保护断言：本装置把失败清单原文留全，由人读日志核对（manifest 的 expect_red）
#   ⑧ 逐字节 cp 还原（绝不 git checkout --）+ 复测 md5 并与基线比
#   ⑨ 日志自指：把本轮 orig/mutant/pushed/restored 四个 md5 追加进日志本身
#   ⑩ **自证**：按 manifest 里**本条**的 frag + 方向判
#      revert ⇒ 原件 0 次 / 变异体 ≥1 次；delete ⇒ 原件 ≥1 次 / 变异体 0 次。
#      ★ 只数**代码片段**，不数整份文件里的词（注释里合法提到会把整文件计数顶成假阳性）。
set -u
LABEL="$1"; TARGET="$2"; MUTANT="$3"; LOG="$4"; BASELINE="${5:-}"
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO" || exit 2
PL="simos-app"
MANIFEST="$REPO/.superpowers/sdd/2026-09-20-unit-extension/t9-evidence/mutants/manifest.txt"
SEL="McpCoverageTest,SimosToolsTest"

# ── ③ 白名单：目标只准是这一条规范路径 ─────────────────────────────
case "$TARGET" in
  simos-app/src/main/java/io/mosire/simos/app/Shell.java) ;;
  *) echo "ABORT($LABEL): TARGET 不在白名单内: $TARGET"; exit 3 ;;
esac

# ── ⑩ 自证参数（从 manifest 取本条的 frag 与方向） ──────────────────
frag=$(awk -v l="$LABEL" '
  $1=="label" {inblock=($2==l); next}
  inblock && $1=="frag" {sub(/^frag[ \t]+/,""); print; exit}
' "$MANIFEST")
frag_dir=$(awk -v l="$LABEL" '
  $1=="label" {inblock=($2==l); next}
  inblock && $1=="frag_dir" {print $2; exit}
' "$MANIFEST")
if [ -z "$frag" ] || [ -z "$frag_dir" ]; then
  echo "ABORT($LABEL): manifest 里没有 frag/frag_dir 记录"; exit 3
fi

orig_md5=$(md5sum "$TARGET" | awk '{print $1}')
mut_md5=$(md5sum "$MUTANT" | awk '{print $1}')
if [ -z "$orig_md5" ] || [ -z "$mut_md5" ]; then
  echo "ABORT($LABEL): md5 读取为空 —— 先怀疑读取，别怀疑被测物"; exit 3
fi
if [ "$orig_md5" = "$mut_md5" ]; then
  echo "ABORT($LABEL): 变异体与原件字节相同 —— 该轮作废"; exit 3
fi

# ── ① 干净世界（基线锚定） ─────────────────────────────────────────
base_md5=""
if [ -n "$BASELINE" ]; then
  if [ ! -f "$BASELINE" ]; then
    echo "ABORT($LABEL): 基线文件不存在: $BASELINE"; exit 3
  fi
  base_md5=$(awk -v p="$TARGET" '$2==p {print $1}' "$BASELINE")
  if [ -z "$base_md5" ]; then
    echo "ABORT($LABEL): 基线文件里没有 $TARGET 的记录"; exit 3
  fi
  if [ "$orig_md5" != "$base_md5" ]; then
    echo "ABORT($LABEL): 本轮起点不是干净世界 —— 当前 $orig_md5 != 基线 $base_md5"; exit 3
  fi
fi
BACKUP="/tmp/${LABEL}.orig.java"
cp "$TARGET" "$BACKUP"
if [ "$(md5sum "$BACKUP" | awk '{print $1}')" != "$orig_md5" ]; then
  echo "ABORT($LABEL): 备份 md5 不符 —— 该轮作废"; exit 3
fi

# ── ④ 源树里规范名之外的 .java（变异体只准活在证据目录） ─────────────
stray=$(find simos-app/src \( -name 't9m*_*.java' -o -name 'm[0-9]*_*.java' \) 2>/dev/null | wc -l)
if [ "$stray" != "0" ]; then
  echo "ABORT($LABEL): 源树内有 $stray 个规范名之外的 .java —— 先清干净世界"; exit 3
fi

rm -rf simos-app/target/classes simos-app/target/test-classes simos-app/target/surefire-reports
cp "$MUTANT" "$TARGET"
pushed_md5=$(md5sum "$TARGET" | awk '{print $1}')
if [ "$pushed_md5" != "$mut_md5" ]; then
  echo "ABORT($LABEL): 推送后 md5 不符 —— 该轮作废"; cp "$BACKUP" "$TARGET"; exit 3
fi

# ── ⑩ 自证（按方向判） ─────────────────────────────────────────────
orig_hits=$(grep -cF "$frag" "$BACKUP")
pushed_hits=$(grep -cF "$frag" "$TARGET")
site_revert_proof="frag=[$frag] dir=$frag_dir orig_hits=$orig_hits pushed_hits=$pushed_hits"
case "$frag_dir" in
  revert)
    if [ "$orig_hits" != "0" ] || [ "$pushed_hits" -lt 1 ]; then
      echo "ABORT($LABEL): 自证未过（revert 要求 原件 0 / 变异体 ≥1）: $site_revert_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi
    ;;
  delete)
    if [ "$orig_hits" -lt 1 ] || [ "$pushed_hits" != "0" ]; then
      echo "ABORT($LABEL): 自证未过（delete 要求 原件 ≥1 / 变异体 0）: $site_revert_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi
    ;;
  *)
    echo "ABORT($LABEL): 未知 frag_dir: $frag_dir"; cp "$BACKUP" "$TARGET"; exit 3 ;;
esac

round_start=$(date +%s)
# ★ EXTRA：给某些轮补的 Maven 参数。**唯一用途**是 `-Dmaven.test.failure.ignore=true` ——
#   simos-app 先红时反应堆会在到别的模块之前收工，那样别的模块的被保护断言根本没跑。
#   ⚠ 该参数会让 mvn 返回 **rc=0**：带 EXTRA 的轮**不以 rc 判杀**，改以日志里的失败清单 + 汇总行为准。
# shellcheck disable=SC2086
./mvnw -o -pl "$PL" -am test -Dtest="$SEL" -Dsurefire.failIfNoSpecifiedTests=false ${EXTRA:-} > "$LOG" 2>&1
rc=$?
round_end=$(date +%s)

compile_errors=$(grep -c "COMPILATION ERROR" "$LOG")
summary=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
  simos-app/target/surefire-reports/*.txt 2>/dev/null | tail -1)
report_mtime_epoch=$(stat -c '%Y' simos-app/target/surefire-reports/*.txt 2>/dev/null | sort -n | tail -1)
report_mtime=$(date -d "@${report_mtime_epoch:-0}" -Iseconds 2>/dev/null)
# ★ 逐类报告 tail -1 会抽到恰好没红的那个类 ⇒ 另记**日志里**的模块级汇总行（含 Failures 真值）。
module_summary=$(grep -hE "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures" "$LOG" 2>/dev/null | tail -1)
failures=$(grep -hE "^\[ERROR\]   [A-Za-z]" "$LOG" 2>/dev/null | head -40)

restored_md5=""
cp "$BACKUP" "$TARGET"
restored_md5=$(md5sum "$TARGET" | awk '{print $1}')

{
  echo "──────────────────────────────────────────────────────────────"
  echo "round            $LABEL"
  echo "target           $TARGET"
  echo "mutant           $(basename "$MUTANT")"
  echo "started          $(date -d "@$round_start" -Iseconds)"
  echo "finished         $(date -d "@$round_end" -Iseconds)"
  echo "elapsed_s        $((round_end - round_start))"
  echo "mvn_rc           $rc"
  echo "extra_flags      ${EXTRA:-<无>}"
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
  echo "⑩ self_proof     $site_revert_proof"
  echo "⑦ failures:"
  printf '%s\n' "${failures:-<无失败行>}"
  echo "══════════════════════════════════════════════════════════════"
} >> "$LOG"

echo "ROUND $LABEL rc=$rc elapsed=$((round_end - round_start))s compile_errors=$compile_errors"
echo "  $site_revert_proof"
echo "  restored==orig: $([ "$restored_md5" = "$orig_md5" ] && echo yes || echo NO)"
echo "  $module_summary"
printf '%s\n' "${failures:-<无失败行>}"
