#!/usr/bin/env bash
# T5 变异轮装置（九道门禁，照 T3/T4 的 mut-round.sh；差异只有两处：白名单扩到八个靶文件、⑩ 新增
# T5-U2 还原自证）。九道门禁：
#   ① 干净世界：本轮开跑前 TARGET 必须与 baseline-md5.txt 里记的那份**逐字节相同**（不许从上一轮的残留出发）
#   ② 变异体字节确实不同（md5 比对，相同即作废）
#   ③ 按**白名单**推成目标类名（TARGET 只准是下面 case 里的八条规范路径之一）
#   ④ 清陈旧 .class / surefire + 扫源树里规范名之外的 .java（不得有 m?_*.java / tNm*_*.java 残留）
#   ⑤ 强制断言 COMPILATION ERROR = 0，不为 0 当场作废这一轮
#   ⑥ surefire 报告 mtime 必须落在本轮内（round_start 起）+ 汇总行非空（先断言读到非空，再下结论）
#   ⑦ 红点落被保护断言：本装置把失败清单原文留全，由人读日志核对
#   ⑧ 逐字节 cp 还原（绝不 git checkout --）+ 复测 md5 并与基线比
#   ⑨ 日志自指：把本轮 orig/mutant/pushed/restored 四个 md5 追加进日志本身
#   ⑩ **T5-U2 还原自证**（裁定 T5 第 (2) 条）：site 1~5 的"还原成 1 参兼容构造器"变异体，
#      推送后必须**真的出现**该还原片段（只可能出现在代码里的那种），而原件里它是 **0** 次
#      —— 证明"变异体不含 T5 的修复"。★ 判据是**逐条自己的代码片段**，不是整份文件的
#      `new UnitState(` 计数（site 5 的注释里合法地写着这个词，见下面装置修正段的实测）。
set -u
LABEL="$1"; TARGET="$2"; MUTANT="$3"; SEL="$4"; LOG="$5"; BASELINE="${6:-}"
REPO="$(git rev-parse --show-toplevel)"
cd "$REPO" || exit 2
MOD="simos-unit"

# ── ③ 白名单：目标只准是这八条规范路径 ──────────────────────────────
case "$TARGET" in
  simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/ReparentSubtreeHandler.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/SplitFormationHandler.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/MergeFormationHandler.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/SetFormationOffsetHandler.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java) ;;
  simos-unit/src/main/java/io/mosire/simos/unit/codec/UnitCodec.java) ;;
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

# ── ① 干净世界（基线锚定）：这一轮必须从**记过档的那份字节**出发 ──────────
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

# ── ④ 源树里规范名之外的 .java（变异体只准活在证据目录） ───────────────
stray=$(find "$MOD/src" \( -name 'm[0-9]*_*.java' -o -name 't[0-9]m[0-9]*_*.java' \) 2>/dev/null | wc -l)
if [ "$stray" != "0" ]; then
  echo "ABORT($LABEL): 源树内有 $stray 个规范名之外的 .java —— 先清干净世界"; exit 3
fi

rm -rf "$MOD/target/classes" "$MOD/target/test-classes" "$MOD/target/surefire-reports"
cp "$MUTANT" "$TARGET"
pushed_md5=$(md5sum "$TARGET" | awk '{print $1}')
if [ "$pushed_md5" != "$mut_md5" ]; then
  echo "ABORT($LABEL): 推送后 md5 不符 —— 该轮作废"; cp "$BACKUP" "$TARGET"; exit 3
fi

# ── ⑩ T5-U2 还原自证（只对五个 site 还原体） ────────────────────────
# ★ 装置修正（T5 第 19 轮实测）：原写法数的是整份文件的 `new UnitState(` —— 对 UnitOperations 成立
#   （原件 0 次），但 site 5 的靶文件 UnitTimeParticipant.java 里，T5 自己的注释**合法地**写着
#   "（旧写法 new UnitState(units)" ⇒ 原件计数 = 1，门禁当场把该轮作废（该轮日志 0 字节、未跑 Maven）。
#   ⇒ 判据改成**每条还原体各自的代码片段**：这类片段只可能出现在代码里，注释里的说明文字不算数。
site_revert_proof="n/a"
case "$(basename "$MUTANT")" in
  t5m04_*|t5m05_*|t5m06_*|t5m08_*|t5m09_*)
    case "$(basename "$MUTANT")" in
      t5m05_*) frag='UnitState target = new UnitState(units);' ;;
      *)       frag='return new UnitState(next);' ;;   # site 1/2/3/4 的还原片段
    esac
    orig_hits=$(grep -cF "$frag" "$BACKUP")
    pushed_hits=$(grep -cF "$frag" "$TARGET")
    site_revert_proof="frag=[$frag] orig_hits=$orig_hits pushed_hits=$pushed_hits"
    if [ "$orig_hits" != "0" ] || [ "$pushed_hits" -lt 1 ]; then
      echo "ABORT($LABEL): T5-U2 还原自证未过（原件应 0 次、变异体应 ≥1 次）: $site_revert_proof"
      cp "$BACKUP" "$TARGET"; exit 3
    fi
    ;;
esac

round_start=$(date +%s)
./mvnw -o -pl "$MOD" -am test -Dtest="$SEL" -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1
rc=$?
round_end=$(date +%s)

compile_errors=$(grep -c "COMPILATION ERROR" "$LOG")
summary=$(grep -hoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+" \
  "$MOD"/target/surefire-reports/*.txt 2>/dev/null | tail -1)
report_mtime_epoch=$(stat -c '%Y' "$MOD"/target/surefire-reports/*.txt 2>/dev/null | sort -n | tail -1)
report_mtime=$(date -d "@${report_mtime_epoch:-0}" -Iseconds 2>/dev/null)
# ★ 逐类报告取 tail -1 会抽到**恰好没红的那个类**（T5 首轮实测：唯一红的 UnitOperationsTest 被
#   UnitTimeParticipantTest 的 "10, Failures: 0" 盖住）⇒ 另记**模块级**汇总行（含 Failures 真值）。
module_summary=$(grep -hE "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures" "$LOG" 2>/dev/null | tail -1)
failures=$(grep -hE "^\[ERROR\]   [A-Za-z]" "$LOG" 2>/dev/null | head -20)

cp "$BACKUP" "$TARGET"
restored_md5=$(md5sum "$TARGET" | awk '{print $1}')

# ── ⑤⑥⑧ 门禁判定（作废理由写进日志，谁读谁看得见） ────────────────────
verdict="OK"
[ "$compile_errors" != "0" ] && verdict="VOID(编译错误)"
[ -z "${summary:-}" ] && verdict="VOID(无 surefire 汇总行)"
if [ -n "${report_mtime_epoch:-}" ] && [ "${report_mtime_epoch:-0}" -lt "$round_start" ]; then
  verdict="VOID(surefire 报告 mtime 落本轮之前：陈旧产物)"
fi
[ "$restored_md5" != "$orig_md5" ] && verdict="VOID(还原后 md5 不符)"
if [ -n "$base_md5" ] && [ "$restored_md5" != "$base_md5" ]; then
  verdict="VOID(还原后与基线不符)"
fi

# 灰名单：门禁全过时才下"被杀/存活"的结论（红 = rc 非零且日志里有失败清单）
outcome="n/a"
if [ "$verdict" = "OK" ]; then
  if [ "$rc" -eq 0 ]; then
    outcome="GREEN(存活)"
  elif [ -n "${failures:-}" ]; then
    outcome="RED(被杀)"
  else
    outcome="RED(rc 非零但失败清单为空 —— 先怀疑读取，别先下结论)"
  fi
fi

{
  echo ""
  echo "===== SELF-REFERENTIAL RECORD ($LABEL) ====="
  echo "label=$LABEL"
  echo "target=$TARGET"
  echo "mutant=$MUTANT"
  echo "selector=$SEL"
  echo "orig_md5=$orig_md5"
  echo "baseline_md5=${base_md5:-<none>}"
  echo "mutant_md5=$mut_md5"
  echo "pushed_md5=$pushed_md5"
  echo "restored_md5=$restored_md5"
  echo "site_revert_proof=$site_revert_proof"
  echo "mvn_rc=$rc"
  echo "compile_errors=$compile_errors"
  echo "surefire_summary=${summary:-<none>}"
  echo "module_summary=${module_summary:-<none>}"
  echo "surefire_mtime=${report_mtime:-<none>}"
  echo "round_start_epoch=$round_start round_end_epoch=$round_end"
  echo "verdict=$verdict"
  echo "outcome=$outcome"
  echo "--- 失败清单（原文，供⑦核红点） ---"
  echo "${failures:-<none>}"
  echo "round_end=$(date -Iseconds)"
} >> "$LOG"

echo "SELF $LABEL orig=$orig_md5 base=${base_md5:-<none>} mut=$mut_md5 pushed=$pushed_md5 restored=$restored_md5 $site_revert_proof rc=$rc compile_errors=$compile_errors module='${module_summary:-<none>}' verdict=$verdict outcome=$outcome"
