#!/usr/bin/env bash
# mut-run-2.sh —— 需要同时改两个文件的变异轮（m1：故意违例 + 把守卫改成永真）。
# 用法: mut-run-2.sh <name> <targetA> <exprA> <targetB> <exprB>
# 语义：A 与 B 各自落变异体、自证 md5 不同；跑门禁；两文件都还原并自证 md5 回到原件。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t2
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t2-evidence"
LOGDIR="$EV/mutants/logs"
mkdir -p "$LOGDIR"
cd "$ROOT/simos-app" || exit 2

NAME="$1"; A="$2"; EXPR_A="$3"; B="$4"; EXPR_B="$5"
LOG="$LOGDIR/$NAME.log"
md5() { md5sum "$1" | awk '{print $1}'; }

ORIG_A=$(md5 "$A"); ORIG_B=$(md5 "$B")
cp "$A" "$LOGDIR/$NAME.origA"; cp "$B" "$LOGDIR/$NAME.origB"

python3 - "$A" "$EXPR_A" <<'PY'
import sys
p, e = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read(); b = s
s = eval(e, {"src": s})
sys.exit(2) if s == b else open(p, "w", encoding="utf-8").write(s)
PY
[ $? -ne 0 ] && { echo "DEVICE FAIL: A 未变"; cp "$LOGDIR/$NAME.origA" "$A"; cp "$LOGDIR/$NAME.origB" "$B"; exit 2; }
python3 - "$B" "$EXPR_B" <<'PY'
import sys
p, e = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read(); b = s
s = eval(e, {"src": s})
sys.exit(2) if s == b else open(p, "w", encoding="utf-8").write(s)
PY
[ $? -ne 0 ] && { echo "DEVICE FAIL: B 未变"; cp "$LOGDIR/$NAME.origA" "$A"; cp "$LOGDIR/$NAME.origB" "$B"; exit 2; }

MUT_A=$(md5 "$A"); MUT_B=$(md5 "$B")
{
  echo "=== $NAME (two-file) ==="
  echo "targetA=$A origA=$ORIG_A mutantA=$MUT_A"
  echo "targetB=$B origB=$ORIG_B mutantB=$MUT_B"
} > "$LOG"
if [ "$ORIG_A" = "$MUT_A" ] || [ "$ORIG_B" = "$MUT_B" ]; then echo "DEVICE FAIL: 变异体==原件"; cp "$LOGDIR/$NAME.origA" "$A"; cp "$LOGDIR/$NAME.origB" "$B"; exit 2; fi

node src/test/js/run-gate.cjs >> "$LOG" 2>&1
RC=$?
REDS=$(grep -cE '^not ok' "$LOG" || true)
REDLIST=$(grep -E '^not ok' "$LOG" || true)
{
  echo "gate_rc=$RC"
  echo "red_points=$REDS"
  echo "$REDLIST"
} >> "$LOG"

cp "$LOGDIR/$NAME.origA" "$A"; cp "$LOGDIR/$NAME.origB" "$B"
R_A=$(md5 "$A"); R_B=$(md5 "$B")
{ echo "restoredA=$R_A ok=$([ "$R_A" = "$ORIG_A" ] && echo 1 || echo 0)"
  echo "restoredB=$R_B ok=$([ "$R_B" = "$ORIG_B" ] && echo 1 || echo 0)"; } >> "$LOG"
echo "$NAME rc=$RC origA=$ORIG_A mutantA=$MUT_A origB=$ORIG_B mutantB=$MUT_B restoredA=$R_A restoredB=$R_B"
[ "$R_A" = "$ORIG_A" ] && [ "$R_B" = "$ORIG_B" ] || exit 3
exit $RC
