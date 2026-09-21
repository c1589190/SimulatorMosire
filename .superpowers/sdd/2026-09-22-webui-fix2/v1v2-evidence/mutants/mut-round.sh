#!/usr/bin/env bash
# mut-round.sh —— V1/V2 变异轮（九道门禁）。
#   用法: mut-round.sh <mutant-id> <target-relpath> <old-literal> <new-literal> <expected-failing-test>
# 九道：①干净世界 ②变异体 md5≠原件且落盘的是变异体 ③白名单推成目标文件 ④断言真的跑到（TAP 有 # tests 且 ==187）
#       ⑤红点落被保护断言 ⑥逐字节 cp 还原并比 md5 ⑦日志自指（本轮字节 md5 写进日志）⑧红/没红都要问为什么（终端输出）
#       ⑨清陈旧状态（每轮从锚点恢复，不靠上一轮遗留）
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf2-v1v2
EV=$WT/.superpowers/sdd/2026-09-22-webui-fix2/v1v2-evidence/mutants
JS=$WT/simos-app/src/test/js
MID=$1; TGT=$2; OLD=$3; NEW=$4; EXPECT=$5
FILE="$WT/$TGT"
LOG="$EV/logs/$MID.log"
BAK="$EV/orig-$(echo "$TGT" | tr '/' '_').bak"

md5f() { md5sum "$1" | cut -d' ' -f1; }

[ -f "$BAK" ] || cp "$FILE" "$BAK"
cp "$BAK" "$FILE"
ORIG_PRE=$(md5f "$FILE"); ORIG_BAK=$(md5f "$BAK")
{
  echo "=== mut-round $MID ==="
  echo "target=$TGT"
  echo "orig_bak_md5=$ORIG_BAK"
  echo "restored_before_round_md5=$ORIG_PRE"
  echo "old_literal=$OLD"
  echo "new_literal=$NEW"
} > "$LOG"
[ "$ORIG_PRE" = "$ORIG_BAK" ] || { echo "$MID: CLEAN_WORLD_MISMATCH" | tee -a "$LOG"; exit 7; }

python3 - "$FILE" "$OLD" "$NEW" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
if s.count(old) != 1:
    sys.stderr.write("BAD_MATCH count=%d\n" % s.count(old)); sys.exit(3)
open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
RC=$?
if [ $RC -ne 0 ]; then echo "PUSH_FAILED rc=$RC" >> "$LOG"; cp "$BAK" "$FILE"; echo "$MID: PUSH_FAILED (VOID)"; exit 3; fi
MUTANT_MD5=$(md5f "$FILE")
echo "mutant_md5=$MUTANT_MD5" >> "$LOG"
[ "$MUTANT_MD5" != "$ORIG_BAK" ] || { echo "$MID: EQUAL_MD5 (VOID — 落盘的还是原件)"; cp "$BAK" "$FILE"; exit 4; }

OUT=$(cd "$JS" && node run-gate.cjs 2>&1)
echo "$OUT" >> "$LOG"
TESTS=$(printf '%s\n' "$OUT" | sed -n 's/^# tests \([0-9]*\)$/\1/p' | tail -1)
FAILS=$(printf '%s\n' "$OUT" | sed -n 's/^# fail \([0-9]*\)$/\1/p' | tail -1)
echo "tests=$TESTS fail=$FAILS" >> "$LOG"
if [ -z "$TESTS" ] || [ "$TESTS" != "187" ]; then echo "$MID: NO_TAP_OR_WRONG_COUNT tests=$TESTS (VOID — 没跑到/数不对)"; cp "$BAK" "$FILE"; exit 5; fi

HIT=$(printf '%s\n' "$OUT" | grep -c "^not ok .* - $EXPECT$")
cp "$BAK" "$FILE"
RESTORED_MD5=$(md5f "$FILE")
echo "restored_after_round_md5=$RESTORED_MD5" >> "$LOG"
if [ "$RESTORED_MD5" != "$ORIG_BAK" ]; then echo "$MID: RESTORE_MISMATCH (严重)" | tee -a "$LOG"; exit 6; fi

if [ "$HIT" -ge 1 ]; then
  echo "$MID: KILLED (red on '$EXPECT'; fail=$FAILS; mutant=$MUTANT_MD5 restored=$RESTORED_MD5)"
  exit 0
else
  echo "$MID: SURVIVED (fail=$FAILS; expected '$EXPECT' not red)"
  exit 1
fi
