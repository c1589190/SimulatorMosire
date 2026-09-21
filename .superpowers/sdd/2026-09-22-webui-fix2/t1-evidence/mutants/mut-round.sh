#!/usr/bin/env bash
# mut-round.sh —— JS 变异轮（九道门禁的最小实现）。
#   用法: mut-round.sh <mutant-id> <target-relpath> <old-literal> <new-literal> <expected-failing-test>
# 九道自证：干净世界 / 变异体 md5 ≠ 原件 md5 / 白名单推成目标文件 / 断言真的跑到 /
#           红点落被保护断言 / 逐字节 cp 还原并比 md5 / 日志自指（md5 写进日志）。
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf2
EV=$WT/.superpowers/sdd/2026-09-22-webui-fix2/t1-evidence/mutants
JS=$WT/simos-app/src/test/js
MID=$1; TGT=$2; OLD=$3; NEW=$4; EXPECT=$5
FILE="$WT/$TGT"
LOG="$EV/logs/$MID.log"
BAK="$EV/orig-$(basename "$TGT").bak"

md5f() { md5sum "$1" | cut -d' ' -f1; }

# 干净世界：从原件的字节锚点恢复（首次运行时从当前文件建锚）
[ -f "$BAK" ] || cp "$FILE" "$BAK"
cp "$BAK" "$FILE"
ORIG_PRE=$(md5f "$FILE")
ORIG_BAK=$(md5f "$BAK")
{
  echo "=== mut-round $MID ==="
  echo "target=$TGT"
  echo "orig_bak_md5=$ORIG_BAK"
  echo "restored_before_round_md5=$ORIG_PRE"
} > "$LOG"

# 应用变异（Python 做**逐字**单次替换，避免 sed 转义坑）
python3 - "$FILE" "$OLD" "$NEW" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
if s.count(old) != 1:
    sys.stderr.write("BAD_MATCH count=%d\n" % s.count(old))
    sys.exit(3)
open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
RC=$?
if [ $RC -ne 0 ]; then
  echo "PUSH_FAILED rc=$RC" >> "$LOG"
  cp "$BAK" "$FILE"
  echo "$MID: PUSH_FAILED (VOID)"
  exit 3
fi
MUTANT_MD5=$(md5f "$FILE")
echo "mutant_md5=$MUTANT_MD5" >> "$LOG"
if [ "$MUTANT_MD5" = "$ORIG_BAK" ]; then
  echo "$MID: EQUAL_MD5 (VOID — 落盘的还是原件)"
  cp "$BAK" "$FILE"
  exit 4
fi

# 跑该测试文件（TAP）
OUT=$(cd "$JS" && node --test --test-reporter=tap webui-fix2.test.cjs 2>&1)
echo "$OUT" >> "$LOG"
TESTS=$(printf '%s\n' "$OUT" | sed -n 's/^# tests \([0-9]*\)$/\1/p' | tail -1)
FAILS=$(printf '%s\n' "$OUT" | sed -n 's/^# fail \([0-9]*\)$/\1/p' | tail -1)
echo "tests=$TESTS fail=$FAILS" >> "$LOG"
if [ -z "$TESTS" ]; then
  echo "$MID: NO_TAP_SUMMARY (VOID — 没跑到)"
  cp "$BAK" "$FILE"
  exit 5
fi

HIT=$(printf '%s\n' "$OUT" | grep -c "^not ok .* - $EXPECT$")
# 逐字节还原 + 自证
cp "$BAK" "$FILE"
RESTORED_MD5=$(md5f "$FILE")
echo "restored_after_round_md5=$RESTORED_MD5" >> "$LOG"
if [ "$RESTORED_MD5" != "$ORIG_BAK" ]; then
  echo "$MID: RESTORE_MISMATCH (严重)" | tee -a "$LOG"
  exit 6
fi

if [ "$HIT" -ge 1 ]; then
  echo "$MID: KILLED (red on '$EXPECT'; fail=$FAILS)"
  exit 0
else
  echo "$MID: SURVIVED (fail=$FAILS; expected '$EXPECT' not red)"
  exit 1
fi
