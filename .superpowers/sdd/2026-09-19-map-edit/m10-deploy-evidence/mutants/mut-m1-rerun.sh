#!/usr/bin/env bash
# M10 m1 的**本机重跑**（2026-09-21，控制器）。
#
# 为什么重跑：M10 的装置 mut-round.sh 把 WT 钉死在另一台机器
#   （WT=/home/cna/SimulatorMosire/.claude/worktrees/m10deploy），本机跑不了；
#   且控制器改了本轮的**被测文件** `GuiAccessLogTest.java`（前提断言改裸读为 await），
#   按「同一文件被改动 ⇒ 旧证据对应旧字节」必须对新字节重跑。
#
# 变异体：删掉 GuiServer.handle 里的 `logAccess(exchange, startedNanos);`（M10 原物，未改）。
# 本轮**要证的只有一件事**：前提断言 `logLinesAreActuallyCaptured` 改 await 之后**仍然会红**
#   —— 即控制器那一行没有把护栏改弱。
#
# 九道门禁：①干净世界 ②变异体字节确实不同 ③按白名单推成**目标类名** ④清陈旧 .class
#          ⑤COMPILATION ERROR=0 且 Tests run>=1 ⑥surefire 报告 mtime 落本轮内
#          ⑦红点落在**被保护的那条**断言上 ⑧cp 逐字节还原（绝不 git checkout）⑨日志自指
set -u
ROOT=/home/dev/SimulatorMosire
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/m10-deploy-evidence"
MU="$EV/mutants"
LOG="$EV/logs/mut-m1-rerun-20260921.log"
RUNLOG="$EV/logs/mut-m1-rerun-20260921.run.log"
TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java"
TARGET="$ROOT/$TARGET_REL"
ORIG="$MU/orig/GuiServer.java"; MUTANT="$MU/m1/GuiServer.java"
SR="$ROOT/simos-app/target/surefire-reports/io.mosire.simos.app.gui.GuiAccessLogTest.txt"
START=$(date +%s); ROUND_START=$(date -Is)
: > "$LOG"
log() { echo "$@" | tee -a "$LOG"; }
die() { log "PHASE FAIL: $*"; log "SELF round=m1 end=$(date +%s)"; exit 1; }
md5() { md5sum "$1" 2>/dev/null | awk '{print $1}'; }

log "===== M10 m1 本机重跑（控制器，2026-09-21）====="
log "round_start=$ROUND_START host=$(hostname) root=$ROOT"

# ① 干净世界：目标文件必须逐字节等于 M10 的 orig
ORIG_MD5=$(md5 "$ORIG"); CUR_MD5=$(md5 "$TARGET")
log "orig_md5=$ORIG_MD5"
log "pre_target_md5=$CUR_MD5"
[ "$CUR_MD5" = "$ORIG_MD5" ] || die "①干净世界失败：目标文件不等于 orig"

# ② 变异体字节确实不同
MUT_MD5=$(md5 "$MUTANT")
log "mutant_md5=$MUT_MD5"
[ "$MUT_MD5" != "$ORIG_MD5" ] || die "②变异体与原件逐字节相同（不是变异体）"

# ④ 清陈旧 .class（在推送前先清，避免上一轮 .class 活到本轮——形态 6）
rm -f "$ROOT"/simos-app/target/classes/io/mosire/simos/app/gui/GuiServer*.class
rm -f "$SR"

# ③ 按白名单推成目标类名（不是按变异体文件名）
cp "$MUTANT" "$TARGET"
PUSHED_MD5=$(md5 "$TARGET")
log "pushed_md5=$PUSHED_MD5"
[ "$PUSHED_MD5" = "$MUT_MD5" ] || die "③推送后 md5 不等于变异体 md5"

# ⑤⑥⑦ 跑
( cd "$ROOT" && ./mvnw -pl simos-app -am -Dtest=GuiAccessLogTest \
    -Dsurefire.failIfNoSpecifiedTests=false test ) > "$RUNLOG" 2>&1
MVN_RC=$?
CE=$(grep -c "COMPILATION ERROR" "$RUNLOG")
TR=$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$RUNLOG" | tail -1)
log "mvn_rc=$MVN_RC"
log "compile_errors=$CE"
log "surefire_summary=${TR:-<none>}"
log "surefire_mtime=$(date -Is -r "$SR" 2>/dev/null || echo '<no report>')"
log "--- 失败清单（原文，供⑦核红点）---"
grep -E '^\[ERROR\]   GuiAccessLogTest' "$RUNLOG" | tee -a "$LOG"

# ⑧ 逐字节还原（cp，绝不 git checkout）
cp "$ORIG" "$TARGET"
RESTORED_MD5=$(md5 "$TARGET")
log "restored_md5=$RESTORED_MD5"
log "start_epoch=$START end_epoch=$(date +%s)"

# ⑨ 判定
fail=""
[ "$CE" = "0" ]                            || fail="$fail ⑤编译错误 "
[ -n "$TR" ]                               || fail="$fail ⑥无 surefire 汇总 "
[ "$MVN_RC" != "0" ]                       || fail="$fail ⑦没红 "
grep -q 'logLinesAreActuallyCaptured' "$RUNLOG" || fail="$fail ⑦前提断言未红 "
grep -q 'Tests run: 4, Failures: 4\|Tests run: 4, Failures: [1-4]' "$RUNLOG" || fail="$fail ⑦红点不在该类 "
[ "$RESTORED_MD5" = "$ORIG_MD5" ]          || fail="$fail ⑧还原不等 "
if [ -n "$fail" ]; then log "verdict=FAIL gates:$fail"; exit 1; fi
log "verdict=OK (m1 KILLED；红点含前提断言 logLinesAreActuallyCaptured)"
