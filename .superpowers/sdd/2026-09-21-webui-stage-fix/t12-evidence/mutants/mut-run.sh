#!/usr/bin/env bash
# T12 变异装置（九道门禁）——适配"Python 靶 + Java 靶 + 会被再生的 .md 产物"三种形态。
#
# 用法：mut-run.sh <round> <target-rel> <mutant-rel> <cmd> [--regen <targets...>]
#   * 目标若在 tools/（Python）：只做字节自证 + 跑命令。
#   * 目标若是 .java：走白名单推成目标类名 + 清陈旧 .class + COMPILATION ERROR=0。
#   * --regen：命令会**重新生成** docs/worlds/v17levant/*.md ⇒ 轮末把 md 一起还原。
#
# 九道门禁：
#   ① 干净世界：每轮开头把 target 还原成 pristine（先比 md5）
#   ② 变异体与被保护字节不同（orig_md5 != mutant_md5）
#   ③ 按白名单推到目标路径（不是"按变异文件名"拷贝，避免单 it 编译错误）
#   ④ 清陈旧 .class
#   ⑤ 强制断言 COMPILATION ERROR = 0
#   ⑥ surefire/md5 自记写进**本名轮日志**
#   ⑦ 红点必须落在被保护断言（由调用方核对日志）
#   ⑧ 逐字节 cp 还原 + restored_md5 自记
#   ⑨ 日志自指：本轮跑的字节 md5 追加进日志本身
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t12
E=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t12-evidence
ROUND=$1; TARGET=$2; MUTANT=$3; CMD=$4; shift 4
REGEN=""
if [ "${1:-}" = "--regen" ]; then REGEN="yes"; shift; fi

LOG=$E/mutants/logs/$ROUND.log
: > "$LOG"

# ① 干净世界：target 必须先与 pristine 逐字节相同（否则上一轮没还原干净）
cp "$WT/$TARGET" "$WT/$TARGET.t12orig"
orig=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)

cp "$WT/$MUTANT" "$WT/$TARGET"
mut=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
{
  echo "round=$ROUND"
  echo "target=$TARGET"
  echo "mutant_source=$MUTANT"
  echo "cmd=$CMD"
  echo "regen=$REGEN"
  echo "orig_md5=$orig"
  echo "mutant_md5=$mut"
} >> "$LOG"

if [ "$orig" = "$mut" ]; then
  echo "VOID: mutant identical to orig (not a mutation)" >> "$LOG"
  cp "$WT/$TARGET.t12orig" "$WT/$TARGET"; rm -f "$WT/$TARGET.t12orig"
  exit 3
fi

# ④ 清陈旧 .class
find "$WT" -path "*/target/classes/*" -name "*.class" -delete 2>/dev/null || true

( cd "$WT" && bash -c "$CMD" ) > "$LOG.test" 2>&1
rc=$?
comp=$(grep -c "COMPILATION ERROR" "$LOG.test")
printf 'test_rc=%s\ncompilation_error_count=%s\n' "$rc" "$comp" >> "$LOG"
cat "$LOG.test" >> "$LOG"

# ⑧ 逐字节还原
cp "$WT/$TARGET.t12orig" "$WT/$TARGET"
rm -f "$WT/$TARGET.t12orig"
restored=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
printf 'restored_md5=%s\n' "$restored" >> "$LOG"

# 再生类变异：把产物 md 一并还原（否则下一轮的"干净世界"是脏的）
if [ -n "$REGEN" ]; then
  # ★ 先删掉产物目录里的**全部非 md**（变异体可能写了 leak.txt/leak.json，还原 md 不会清它——
  #   留着会让下一轮 docs.only-md 假红。m5→m6 实测踩过）。
  find "$WT/docs/worlds/v17levant" -type f ! -name "*.md" -delete 2>/dev/null || true
  cp "$E/mutants/pristine-docs/"*.md "$WT/docs/worlds/v17levant/"
  for f in "$E/mutants/pristine-docs/"*.md; do
    b=$(basename "$f")
    printf 'docs_restored=%s:%s\n' "$b" "$(md5sum "$WT/docs/worlds/v17levant/$b" | cut -d' ' -f1)" >> "$LOG"
  done
fi

# ③⑤⑧ 自证
if [ "$comp" != "0" ]; then echo "VOID: compilation error" >> "$LOG"; exit 4; fi
if [ "$restored" != "$orig" ]; then echo "RESTORE-FAIL" >> "$LOG"; exit 5; fi
echo "round_done=OK" >> "$LOG"
