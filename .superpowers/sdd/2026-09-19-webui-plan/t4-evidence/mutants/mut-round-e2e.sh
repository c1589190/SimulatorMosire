#!/usr/bin/env bash
# M7 T4 变异轮装置（资源类目标 map.js，用 e2e 观察红点）。
# 护栏是 e2e 行为断言（不是 Maven 用例）⇒ 变异体推给 **源码 + classpath 两份**
#   （StaticHandler 从 classpath /webui 读字节），起真 ShellMain + Playwright 观察红点。
# 门禁（照 CLAUDE.md）：
#   干净世界（源与 classpath 两份 md5 一致）/ 变异体字节不同 / 两份推送一致 /
#   服务器真起（日志有「GUI 服务器已启动」）/ e2e 真跑 / 红点落在被保护断言 /
#   源与 classpath 均逐字节还原 / 日志自指（本轮字节的 md5 写进日志）。
# 用法: mut-round-e2e.sh <round-id> <mutant-file> <expect-step> <port>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7t4
cd "$ROOT" || exit 2
RID="$1"; MUT="$2"; EXPECT_STEP="$3"; PORT="$4"
EV="$ROOT/.superpowers/sdd/2026-09-19-webui-plan/t4-evidence"
LOG="$EV/logs/$RID.log"
SERVER_LOG="$EV/logs/$RID.server.log"
OUT="$EV/mut-runs/$RID"
TGT="simos-app/src/main/resources/webui/map.js"
SRC="$ROOT/$TGT"
CLS="$ROOT/simos-app/target/classes/webui/map.js"
ORIG_BACKUP="$EV/mutants/orig/map.js"
CLS_BACKUP="$EV/mutants/orig/map.js.classpath"
STORE="/tmp/m7t4-mut-store-$RID"
mkdir -p "$EV/logs" "$OUT" "$EV/mutants/orig"

restore() {
  cp "$ORIG_BACKUP" "$SRC" 2>/dev/null || true
  cp "$CLS_BACKUP" "$CLS" 2>/dev/null || true
}
trap restore EXIT

# 门禁 1：干净世界——备份必须与工作树两份副本逐字节一致
ORIG_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
BACKUP_MD5=$(md5sum "$ORIG_BACKUP" | cut -d' ' -f1)
CLS_MD5=$(md5sum "$CLS" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BACKUP_MD5" ]; then echo "FATAL: 备份与工作树不一致"; exit 2; fi
if [ "$ORIG_MD5" != "$CLS_MD5" ]; then echo "FATAL: 源与 classpath 副本不一致（构建陈旧？）"; exit 2; fi
cp "$SRC" "$ORIG_BACKUP"
cp "$CLS" "$CLS_BACKUP"

# 门禁 2：变异体字节不同；门禁 3：两份推送一致
cp "$MUT" "$SRC"
cp "$MUT" "$CLS"
PUSHED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
PUSHED_CLS=$(md5sum "$CLS" | cut -d' ' -f1)
[ "$PUSHED_SRC" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }
[ "$PUSHED_SRC" = "$PUSHED_CLS" ] || { echo "FATAL: 源/classpath 推送不一致"; exit 2; }

ROUND_START=$(date +%s)
bash "$EV/e2e/run-e2e.sh" demo "$PORT" "$STORE" "$OUT" "$SERVER_LOG" > "$LOG" 2>&1
RC=$?
restore
trap - EXIT
RESTORED_SRC=$(md5sum "$SRC" | cut -d' ' -f1)
RESTORED_CLS=$(md5sum "$CLS" | cut -d' ' -f1)
SERVER_MTIME=$( [ -f "$SERVER_LOG" ] && stat -c %Y "$SERVER_LOG" || echo 0 )

echo "──── $RID ────"
echo "orig_md5=$ORIG_MD5  mutant_md5=$PUSHED_SRC  classes_md5=$PUSHED_CLS"
echo "rc=$RC  round_start=$ROUND_START  server_log_mtime=$SERVER_MTIME"
echo "--- 变红的步骤 ---"
grep -E "^STEP .*: FAIL" "$LOG" | head -8
grep -E "^E2E RESULT:" "$LOG" | head -2

# 门禁 9：装置自指——把本轮推送的字节写进日志本身
{
  echo ""
  echo "===== 装置补记（$RID）：本轮推送的正是以下字节 ====="
  echo "target=$TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$PUSHED_SRC"
  echo "pushed_classes_md5=$PUSHED_CLS"
  echo "restored_src=$RESTORED_SRC"
  echo "restored_classes=$RESTORED_CLS"
  echo "round_start=$ROUND_START"
  echo "server_log_mtime=$SERVER_MTIME"
  echo "e2e_rc=$RC"
  echo "server_log=$SERVER_LOG"
} >> "$LOG"

# 门禁：服务器真起 / e2e 真红 / 红点是被保护断言 / 两份还原
grep -q "GUI 服务器已启动" "$SERVER_LOG" || { echo "⇒ 本轮作废：服务器没起"; exit 2; }
grep -q "E2E RESULT: FAIL" "$LOG" || { echo "⇒ 本轮作废：e2e 没红"; exit 2; }
grep -q "STEP $EXPECT_STEP: FAIL" "$LOG" || { echo "⇒ 本轮作废：红点不是 $EXPECT_STEP"; exit 2; }
[ "$SERVER_MTIME" -ge "$ROUND_START" ] || { echo "⇒ 本轮作废：server 日志 mtime 早于本轮"; exit 2; }
[ "$RESTORED_SRC" = "$ORIG_MD5" ] || { echo "⇒ 本轮作废：源还原不等"; exit 2; }
[ "$RESTORED_CLS" = "$ORIG_MD5" ] || { echo "⇒ 本轮作废：classpath 还原不等"; exit 2; }
echo "⇒ 杀死（红点 STEP $EXPECT_STEP），源与 classpath 均逐字节还原"
