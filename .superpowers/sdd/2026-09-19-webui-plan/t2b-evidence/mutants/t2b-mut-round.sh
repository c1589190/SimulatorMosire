#!/usr/bin/env bash
# M7b T2 变异轮装置（Java 目标 + 前端资源目标都支持；红点用 e2e 观察）。
# 九道门禁：干净世界（备份==工作树，Java 含 .class）→ 变异体字节不同 → 清陈旧 .class（Java）→
#   编译 COMPILATION ERROR=0 且 .class 产出（Java）→ 服务器真起 → e2e 真红 → 红点落在被保护步骤 →
#   源与 class/资源逐字节还原 → 日志自指（本轮推送字节的 md5 写进日志本身）。
# 用法: t2b-mut-round.sh <kind:java|resource> <round-id> <mutant-file> <target-relpath> <expect-step> <port>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7bt2
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/t2b-evidence"
KIND="$1"; RID="$2"; MUT="$3"; TGT="$4"; EXPECT_STEP="$5"; PORT="$6"
cd "$ROOT" || exit 2
LOG="$EV/logs/$RID.log"
JAVAC_LOG="$EV/logs/$RID.javac.log"
SERVER_LOG="$EV/logs/$RID.server.log"
OUT="$EV/mut-runs/$RID"
SRC="$ROOT/$TGT"
BASE="$(basename "$TGT")"
mkdir -p "$EV/logs" "$OUT" "$EV/mutants/orig" "$EV/mutants/orig/classes"

MODULE="${TGT%%/*}"
ORIG_BACKUP="$EV/mutants/orig/$BASE"
CLASS_NAME="$(basename "${BASE%.java}")"
# 逐字节聚合：只取每文件的 md5（丢掉路径——两份副本的目录不同），排序后聚合。
agg() { md5sum "$@" | cut -d' ' -f1 | sort | md5sum | cut -d' ' -f1; }
if [ "$KIND" = "java" ]; then
  PKG_DIR="$(dirname "${TGT#*/src/main/java/}")"
  CLS_DIR="$ROOT/$MODULE/target/classes/$PKG_DIR"
  CLASS_BAK="$EV/mutants/orig/classes"
else
  CLS_DIR="$ROOT/$MODULE/target/classes/webui"
  CLASS_BAK="$EV/mutants/orig/classes-resource"
fi

restore() {
  cp "$ORIG_BACKUP" "$SRC" 2>/dev/null || true
  if [ "$KIND" = "java" ]; then
    for c in "$CLASS_BAK/$CLASS_NAME"*.class; do
      [ -e "$c" ] || continue
      cp "$c" "$CLS_DIR/$(basename "$c")" 2>/dev/null || true
    done
  else
    cp "$CLASS_BAK/$BASE" "$CLS_DIR/$BASE" 2>/dev/null || true
  fi
}
trap restore EXIT

# ── 门禁 1：干净世界 ──
ORIG_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
BACKUP_MD5=$(md5sum "$ORIG_BACKUP" 2>/dev/null | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BACKUP_MD5" ]; then echo "FATAL: 备份与工作树不一致（$BASE）"; exit 2; fi
if [ "$KIND" = "java" ]; then
  for c in "$CLASS_BAK/$CLASS_NAME"*.class; do
    [ -e "$c" ] || { echo "FATAL: 缺 .class 备份"; exit 2; }
  done
else
  CLS_BEFORE=$(md5sum "$CLS_DIR/$BASE" 2>/dev/null | cut -d' ' -f1)
  [ "$CLS_BEFORE" = "$ORIG_MD5" ] || { echo "FATAL: 源与 classpath 副本不一致"; exit 2; }
fi

# ── 门禁 2：变异体字节不同；推源码 ──
cp "$MUT" "$SRC"
PUSHED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
[ "$PUSHED_SRC" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

if [ "$KIND" = "java" ]; then
  # ── 门禁 3：清陈旧 .class，逼重编 ──
  find "$CLS_DIR" -name "$CLASS_NAME*.class" -delete 2>/dev/null || true
  # ── 门禁 4：编译，COMPILATION ERROR=0 且产出新 .class ──
  CP="$ROOT/$MODULE/target/classes:$ROOT/simos-util/target/classes:$ROOT/simos-map/target/classes:$ROOT/simos-social/target/classes:$ROOT/simos-unit/target/classes:$ROOT/simos-core/target/classes:$(cat /tmp/m7t3-cp.txt)"
  javac -cp "$CP" -d "$ROOT/$MODULE/target/classes" "$SRC" > "$JAVAC_LOG" 2>&1
  JRC=$?
  CE=$(grep -cE "error:|COMPILATION ERROR" "$JAVAC_LOG")
  CLASS_OUT=$(ls "$CLS_DIR/$CLASS_NAME"*.class 2>/dev/null | wc -l)
  if [ "$JRC" -ne 0 ] || [ "$CE" -ne 0 ] || [ "$CLASS_OUT" -lt 1 ]; then
    echo "⇒ 本轮作废：编译不干净（jrc=$JRC errors=$CE classes=$CLASS_OUT）"; restore; trap - EXIT; exit 2
  fi
else
  # ── 门禁 3'/4'：资源两份推送一致 ──
  cp "$MUT" "$CLS_DIR/$BASE"
fi
PUSHED_CLS=""
if [ "$KIND" = "java" ]; then
  PUSHED_CLS=$(agg "$CLS_DIR/$CLASS_NAME"*.class)
else
  PUSHED_CLS=$(md5sum "$CLS_DIR/$BASE" | cut -d' ' -f1)
  [ "$PUSHED_CLS" = "$PUSHED_SRC" ] || { echo "FATAL: 源/classpath 推送不一致"; exit 2; }
fi
[ "$PUSHED_SRC" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }
[ -n "$PUSHED_CLS" ] || { echo "FATAL: 推送后 class 聚合 md5 为空（装置读取失败）"; exit 2; }

# ── 门禁 5/6/7：起服务器 + e2e 真红 + 红点 ──
ROUND_START=$(date +%s)
STORE="/tmp/t2b-mut-store-$RID"
bash "$EV/e2e/run-e2e.sh" "$PORT" "$STORE" "$OUT" "$SERVER_LOG" > "$LOG" 2>&1
RC=$?
restore
trap - EXIT

RESTORED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
if [ "$KIND" = "java" ]; then
  RESTORED_CLS=$(agg "$CLS_DIR/$CLASS_NAME"*.class)
  BACKED_CLS=$(agg "$CLASS_BAK/$CLASS_NAME"*.class)
else
  RESTORED_CLS=$(md5sum "$CLS_DIR/$BASE" | cut -d' ' -f1)
  BACKED_CLS="$ORIG_MD5"
fi
SERVER_MTIME=$( [ -f "$SERVER_LOG" ] && stat -c %Y "$SERVER_LOG" || echo 0 )

echo "──── $RID ────"
echo "kind=$KIND target=$TGT"
echo "orig_md5=$ORIG_MD5  pushed_src_md5=$PUSHED_SRC  pushed_classes_agg=$PUSHED_CLS"
echo "rc=$RC  round_start=$ROUND_START  server_log_mtime=$SERVER_MTIME"
echo "--- 变红的步骤 ---"
grep -E "^STEP .*: FAIL" "$LOG" | head -12
grep -E "^E2E RESULT:" "$LOG" | head -2

# ── 门禁 9：日志自指 ──
{
  echo ""
  echo "===== 装置补记（$RID）：本轮推送的正是以下字节 ====="
  echo "kind=$KIND"
  echo "target=$TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "pushed_src_md5=$PUSHED_SRC"
  echo "pushed_classes_agg=$PUSHED_CLS"
  echo "restored_src_md5=$RESTORED_SRC"
  echo "restored_classes_agg=$RESTORED_CLS"
  echo "class_backup_agg=$BACKED_CLS"
  echo "round_start=$ROUND_START"
  echo "server_log_mtime=$SERVER_MTIME"
  echo "e2e_rc=$RC"
  echo "server_log=$SERVER_LOG"
  echo "javac_log=$JAVAC_LOG"
} >> "$LOG"

# ── 门禁 5：服务器真起；门禁 6：e2e 真红；门禁 7：红点是被保护步骤；门禁 8：逐字节还原 ──
grep -q "GUI 服务器已启动" "$SERVER_LOG" || { echo "⇒ 本轮作废：服务器没起"; exit 2; }
grep -q "E2E RESULT: FAIL" "$LOG" || { echo "⇒ 本轮作废：e2e 没红"; exit 2; }
grep -q "STEP $EXPECT_STEP: FAIL" "$LOG" || { echo "⇒ 本轮作废：红点不是 $EXPECT_STEP"; exit 2; }
[ "$SERVER_MTIME" -ge "$ROUND_START" ] || { echo "⇒ 本轮作废：server 日志 mtime 早于本轮"; exit 2; }
[ -n "$RESTORED_CLS" ] && [ -n "$BACKED_CLS" ] || { echo "⇒ 本轮作废：class 聚合 md5 为空（装置读取失败）"; exit 2; }
[ "$RESTORED_SRC" = "$ORIG_MD5" ] || { echo "⇒ 本轮作废：源还原不等"; exit 2; }
[ "$RESTORED_CLS" = "$BACKED_CLS" ] || { echo "⇒ 本轮作废：class/资源还原不等"; exit 2; }
echo "⇒ 杀死（红点 STEP $EXPECT_STEP），源与 class/资源均逐字节还原"
