#!/usr/bin/env bash
# mut-run.sh —— T2 前端门禁变异装置（形态 1：先自证再下结论）。
#
# 每轮：① 记录原件 md5（断言非空）→ ② 落变异体、断言 md5 与原件**逐字节不同** →
#       ③ 跑门禁驱动 run-gate.cjs、收集 rc 与红点 → ④ 还原、断言 md5 回到原件 →
#       ⑤ 把"这一轮跑的是哪份字节"写进日志本身（装置产物自指）。
#
# 用法: mut-run.sh <name> <relative-target-file> <python-mutation-expr>
#   python-mutation-expr 形如: src.replace(A, B)  （对文本做替换；不存在 A ⇒ 报错）
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t2
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t2-evidence"
LOGDIR="$EV/mutants/logs"
mkdir -p "$LOGDIR"
cd "$ROOT/simos-app" || exit 2

NAME="$1"
TARGET="$2"
EXPR="$3"
LOG="$LOGDIR/$NAME.log"

md5() { md5sum "$1" | awk '{print $1}'; }

ORIG=$(md5 "$TARGET")
if [ -z "$ORIG" ]; then echo "DEVICE FAIL($NAME): 读不到原件 md5"; exit 2; fi
cp "$TARGET" "$LOGDIR/$NAME.orig"

python3 - "$TARGET" "$EXPR" <<'PY'
import sys
path, expr = sys.argv[1], sys.argv[2]
src = open(path, encoding="utf-8").read()
before = src
src = eval(expr, {"src": src})
if src == before:
    print("DEVICE FAIL: mutation 未改变文本（替换目标不存在？）")
    sys.exit(2)
open(path, "w", encoding="utf-8").write(src)
PY
MUT_RC=$?
MUTANT=$(md5 "$TARGET")
{
  echo "=== $NAME ==="
  echo "target=$TARGET"
  echo "orig_md5=$ORIG"
  echo "mutant_md5=$MUTANT"
  echo "mutation=$EXPR"
} > "$LOG"
if [ "$MUT_RC" != "0" ]; then echo "mutation_failed=1" >> "$LOG"; cp "$LOGDIR/$NAME.orig" "$TARGET"; exit 2; fi
if [ "$ORIG" = "$MUTANT" ]; then echo "DEVICE FAIL($NAME): 变异体与原件逐字节相同"; cp "$LOGDIR/$NAME.orig" "$TARGET"; exit 2; fi

node src/test/js/run-gate.cjs >> "$LOG" 2>&1
RC=$?
REDS=$(grep -cE '^not ok' "$LOG" || true)
REDLIST=$(grep -E '^not ok' "$LOG" || true)
{
  echo "gate_rc=$RC"
  echo "red_points=$REDS"
  echo "$REDLIST"
} >> "$LOG"

cp "$LOGDIR/$NAME.orig" "$TARGET"
RESTORED=$(md5 "$TARGET")
{
  echo "restored_md5=$RESTORED"
  echo "restore_ok=$([ "$RESTORED" = "$ORIG" ] && echo 1 || echo 0)"
} >> "$LOG"
echo "$NAME rc=$RC orig=$ORIG mutant=$MUTANT restored=$RESTORED"
[ "$RESTORED" = "$ORIG" ] || { echo "DEVICE FAIL($NAME): 还原后 md5 不一致"; exit 3; }
exit $RC
