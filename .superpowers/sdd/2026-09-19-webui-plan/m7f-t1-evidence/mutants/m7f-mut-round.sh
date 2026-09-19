#!/usr/bin/env bash
# M7f T1 变异轮装置（前端资源目标 timeline.js；红点用 e2e 观察）。
# 九道门禁：干净世界（源==classpath 副本，备份==工作树）→ 变异体语法有效且字节不同 → 两份推送一致 →
#   服务器真起 → e2e 真红 → 红点落在被保护步骤 → 源与资源逐字节还原 → 日志自指（本轮推送字节的 md5 写进日志本身）。
# 用法: m7f-mut-round.sh <round-id> <mutant-file> <expect-step> <port>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7ft1
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/m7f-t1-evidence"
RID="$1"; MUT="$2"; EXPECT_STEP="$3"; PORT="$4"
TGT="simos-app/src/main/resources/webui/timeline.js"
cd "$ROOT" || exit 2
LOG="$EV/logs/$RID.log"
SERVER_LOG="$EV/logs/$RID.server.log"
OUT="$EV/mut-runs/$RID"
SRC="$ROOT/$TGT"
BASE="$(basename "$TGT")"
mkdir -p "$EV/logs" "$OUT" "$EV/mutants/orig"
CLS_DIR="$ROOT/simos-app/target/classes/webui"
ORIG_BACKUP="$EV/mutants/orig/$BASE"
CLASS_BAK="$EV/mutants/orig/classes-resource/$BASE"

restore() {
  cp "$ORIG_BACKUP" "$SRC" 2>/dev/null || true
  cp "$CLASS_BAK" "$CLS_DIR/$BASE" 2>/dev/null || true
}
trap restore EXIT

# ── 门禁 1：干净世界 ──
[ -f "$ORIG_BACKUP" ] || { echo "FATAL: 缺原件备份 $ORIG_BACKUP"; exit 2; }
[ -f "$CLASS_BAK" ] || { echo "FATAL: 缺 classpath 原件备份 $CLASS_BAK"; exit 2; }
ORIG_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
BACKUP_MD5=$(md5sum "$ORIG_BACKUP" | cut -d' ' -f1)
CLS_BAK_MD5=$(md5sum "$CLASS_BAK" | cut -d' ' -f1)
for v in "$ORIG_MD5" "$BACKUP_MD5" "$CLS_BAK_MD5"; do
  [ -n "$v" ] || { echo "FATAL: md5 读取为空（装置读取失败）"; exit 2; }
done
[ "$ORIG_MD5" = "$BACKUP_MD5" ] || { echo "FATAL: 源备份与工作树不一致"; exit 2; }
CLS_BEFORE=$(md5sum "$CLS_DIR/$BASE" 2>/dev/null | cut -d' ' -f1)
[ -n "$CLS_BEFORE" ] || { echo "FATAL: classpath 副本缺失（读取失败）"; exit 2; }
[ "$CLS_BEFORE" = "$ORIG_MD5" ] || { echo "FATAL: 源与 classpath 副本不一致（干净世界不成立）"; exit 2; }

# ── 门禁 2：变异体语法有效且字节不同；两份推送一致 ──
node --check "$MUT" >/dev/null 2>&1 || { echo "FATAL: 变异体语法无效（作废）"; exit 2; }
MUT_MD5=$(md5sum "$MUT" | cut -d' ' -f1)
[ -n "$MUT_MD5" ] || { echo "FATAL: 变异体 md5 为空"; exit 2; }
[ "$MUT_MD5" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }
cp "$MUT" "$SRC"
cp "$MUT" "$CLS_DIR/$BASE"
PUSHED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
PUSHED_CLS=$(md5sum "$CLS_DIR/$BASE" | cut -d' ' -f1)
[ -n "$PUSHED_SRC" ] || { echo "FATAL: 推送后源 md5 为空"; exit 2; }
[ "$PUSHED_SRC" = "$MUT_MD5" ] || { echo "FATAL: 推送后的源不是变异体"; exit 2; }
[ "$PUSHED_CLS" = "$PUSHED_SRC" ] || { echo "FATAL: 源/classpath 推送不一致"; exit 2; }

# ── 门禁 5/6/7/9：起服务器 + e2e 真红 + 红点 + 日志自指 ──
ROUND_START=$(date +%s)
STORE="/tmp/m7f-mut-store-$RID"
bash "$EV/e2e/run-e2e.sh" "$PORT" "$STORE" "$OUT" "$SERVER_LOG" > "$LOG" 2>&1
RC=$?
restore
trap - EXIT

RESTORED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
RESTORED_CLS=$(md5sum "$CLS_DIR/$BASE" | cut -d' ' -f1)
SERVER_MTIME=$( [ -f "$SERVER_LOG" ] && stat -c %Y "$SERVER_LOG" || echo 0 )

echo "──── $RID ────"
echo "target=$TGT"
echo "orig_md5=$ORIG_MD5  mutant_md5=$MUT_MD5  pushed_src_md5=$PUSHED_SRC  pushed_classes_md5=$PUSHED_CLS"
echo "rc=$RC  round_start=$ROUND_START  server_log_mtime=$SERVER_MTIME"
echo "--- 变红的步骤 ---"
grep -E "^STEP .*: FAIL" "$LOG" | head -12
grep -E "^E2E RESULT:" "$LOG" | head -2

# ── 门禁 9：日志自指 ──
{
  echo ""
  echo "===== 装置补记（$RID）：本轮推送的正是以下字节 ====="
  echo "target=$TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$MUT_MD5"
  echo "pushed_src_md5=$PUSHED_SRC"
  echo "pushed_classes_md5=$PUSHED_CLS"
  echo "restored_src_md5=$RESTORED_SRC"
  echo "restored_classes_md5=$RESTORED_CLS"
  echo "class_backup_md5=$CLS_BAK_MD5"
  echo "round_start=$ROUND_START"
  echo "server_log_mtime=$SERVER_MTIME"
  echo "e2e_rc=$RC"
  echo "server_log=$SERVER_LOG"
} >> "$LOG"

# ── 红点必须落在本轮日志里（读取自证：非空才比较）──
grep -q "GUI 服务器已启动" "$SERVER_LOG" || { echo "⇒ 本轮作废：服务器没起"; exit 2; }
grep -q "E2E RESULT: FAIL" "$LOG" || { echo "⇒ 本轮作废：e2e 没红"; exit 2; }
grep -q "STEP $EXPECT_STEP: FAIL" "$LOG" || { echo "⇒ 本轮作废：红点不是 $EXPECT_STEP"; exit 2; }
[ "$SERVER_MTIME" -ge "$ROUND_START" ] || { echo "⇒ 本轮作废：server 日志 mtime 早于本轮"; exit 2; }
[ -n "$RESTORED_CLS" ] && [ -n "$CLS_BAK_MD5" ] || { echo "⇒ 本轮作废：聚合 md5 为空（装置读取失败）"; exit 2; }
[ "$RESTORED_SRC" = "$ORIG_MD5" ] || { echo "⇒ 本轮作废：源还原不等"; exit 2; }
[ "$RESTORED_CLS" = "$CLS_BAK_MD5" ] || { echo "⇒ 本轮作废：资源还原不等"; exit 2; }
echo "⇒ 杀死（红点 STEP $EXPECT_STEP），源与 classpath 资源均逐字节还原"
