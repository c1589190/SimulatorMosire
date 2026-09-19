#!/usr/bin/env bash
# M7b T1 变异轮装置：源 + classpath 两份推送 → 跑 e2e → 逐字节还原 → 日志自指。
# 用法: mut-round.sh <mutant-name> <port> <round-dir>
# 退出码：0 = 本轮装置完整（不论 e2e 红绿）；2 = 装置自身失效（md5 不符 / 锚点漂 / 未还原）。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7bt1
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/t1b-evidence"
MUT="$EV/mutants"
NAME="$1"; PORT="$2"; ROUND="$3"
SRC="$ROOT/simos-app/src/main/resources/webui/timeline.js"
TGT="$ROOT/simos-app/target/classes/webui/timeline.js"
mkdir -p "$ROUND" "$ROUND/out" "$MUT/orig"

LOG="$ROUND/round.log"
: > "$LOG"

# 0) 备份 + 原件 md5（每轮开跑前先恢复干净世界）
cp -f "$SRC" "$MUT/orig/timeline.src.js"
cp -f "$TGT" "$MUT/orig/timeline.tgt.js"
ORIG_SRC=$(md5sum "$SRC" | awk '{print $1}')
ORIG_TGT=$(md5sum "$TGT" | awk '{print $1}')
MUT_MD5=$(md5sum "$MUT/$NAME.timeline.js" | awk '{print $1}')

# 1) 干净世界自证：源与 classpath 副本此刻必须逐字节相同
if [ "$ORIG_SRC" != "$ORIG_TGT" ]; then
  echo "DEVICE-FAIL 清理前 src/tgt 不同：src=$ORIG_SRC tgt=$ORIG_TGT" | tee -a "$LOG"
  exit 2
fi

# 2) 白名单推送：按**目标类名**推源与 classpath（不是变异文件名）
cp -f "$MUT/$NAME.timeline.js" "$SRC"
cp -f "$MUT/$NAME.timeline.js" "$TGT"
PUSH_SRC=$(md5sum "$SRC" | awk '{print $1}')
PUSH_TGT=$(md5sum "$TGT" | awk '{print $1}')
if [ "$PUSH_SRC" != "$MUT_MD5" ] || [ "$PUSH_TGT" != "$MUT_MD5" ]; then
  echo "DEVICE-FAIL 推送后 md5 != mutant：want=$MUT_MD5 src=$PUSH_SRC tgt=$PUSH_TGT" | tee -a "$LOG"
  cp -f "$MUT/orig/timeline.src.js" "$SRC"; cp -f "$MUT/orig/timeline.tgt.js" "$TGT"
  exit 2
fi

{
  echo "=== M7b T1 变异轮 $NAME @ $ROUND $(date -Iseconds) ==="
  echo "orig_src_md5=$ORIG_SRC"
  echo "orig_tgt_md5=$ORIG_TGT"
  echo "mutant_md5=$MUT_MD5"
  echo "pushed_src_md5=$PUSH_SRC"
  echo "pushed_tgt_md5=$PUSH_TGT"
  echo "clean_world_check=src==tgt==orig_before_push"
} >> "$LOG"

# 3) 跑 e2e（真 ShellMain，独立 store/port）
bash "$EV/e2e/run-e2e.sh" "$PORT" "/tmp/m7bt1-mut-$NAME-store" "$ROUND/out" "$ROUND/server.log" > "$ROUND/e2e.log" 2>&1
RC=$?

# 4) 逐字节还原并自证
cp -f "$MUT/orig/timeline.src.js" "$SRC"
cp -f "$MUT/orig/timeline.tgt.js" "$TGT"
REST_SRC=$(md5sum "$SRC" | awk '{print $1}')
REST_TGT=$(md5sum "$TGT" | awk '{print $1}')
{
  echo "e2e_rc=$RC"
  echo "restored_src_md5=$REST_SRC"
  echo "restored_tgt_md5=$REST_TGT"
  echo "restore_ok=$([ "$REST_SRC" = "$ORIG_SRC" ] && [ "$REST_TGT" = "$ORIG_TGT" ] && echo yes || echo NO)"
  echo "--- e2e FAIL/STEP lines ---"
  grep -E "^STEP .*: FAIL|E2E RESULT" "$ROUND/e2e.log" || true
} >> "$LOG"

cat "$LOG"
if [ "$REST_SRC" != "$ORIG_SRC" ] || [ "$REST_TGT" != "$ORIG_TGT" ]; then
  exit 2
fi
exit 0
