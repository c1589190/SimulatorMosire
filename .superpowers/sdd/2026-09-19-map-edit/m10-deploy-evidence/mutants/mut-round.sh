#!/usr/bin/env bash
# M10 部署三件事的变异轮装置 —— 九道门禁。
#
# 用法: mut-round.sh <m1|m2|m3>
#   m1  Java  GuiServer.handle 删掉 logAccess 调用        (杀点: GuiAccessLogTest 的两行访问日志断言)
#   m2  Java  ShellConfig.DEFAULT_BIND_ADDRESS -> 0.0.0.0 (杀点: 缺省必须回环)
#   m3  shade simos-app/pom.xml 去掉 shade 的 <mainClass>  (杀点: java -jar 报 no main manifest attribute)
#
# 九道门禁: ①干净世界 md5 ②变异体字节确实不同 ③按白名单推成目标名 ④清陈旧 .class
#          ⑤COMPILATION ERROR=0 且 Tests run>=1 ⑥surefire 报告 mtime 本轮内
#          ⑦红点落在被保护的那条断言/行为上 ⑧cp 逐字节还原(绝不 git checkout) ⑨日志自指且核对先断言非空
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/m10deploy
EV="$WT/.superpowers/sdd/2026-09-19-map-edit/m10-deploy-evidence"
MU="$EV/mutants"
ROUND="${1:?round id}"
LOG="$EV/logs/mut-$ROUND.log"
RUNLOG="$EV/logs/mut-$ROUND.run.log"
: > "$LOG"
START=$(date +%s)

log() { echo "$@" | tee -a "$LOG"; }
die() {
  log "PHASE FAIL: $*"
  log "SELF round=$ROUND restored_md5=$(md5sum "$TARGET" 2>/dev/null | awk '{print $1}') end=$(date +%s)"
  exit 1
}

case "$ROUND" in
  m1)
    TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java"
    ORIG="$MU/orig/GuiServer.java"; MUTANT="$MU/m1/GuiServer.java"; MODE=java
    TESTS="GuiAccessLogTest"
    CLASS_GLOB="simos-app/target/classes/io/mosire/simos/app/gui/GuiServer*.class"
    SR="$WT/simos-app/target/surefire-reports/io.mosire.simos.app.gui.GuiAccessLogTest.txt"
    EXPECT="两次请求 ⇒ 两行访问日志"
    ;;
  m2)
    TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/ShellConfig.java"
    ORIG="$MU/orig/ShellConfig.java"; MUTANT="$MU/m2/ShellConfig.java"; MODE=java
    TESTS="ShellMainParseTest,BindAddressTest"
    CLASS_GLOB="simos-app/target/classes/io/mosire/simos/app/ShellConfig*.class"
    SR="$WT/simos-app/target/surefire-reports/io.mosire.simos.app.ShellMainParseTest.txt"
    EXPECT="缺省必须回环"
    ;;
  m3)
    TARGET_REL="simos-app/pom.xml"
    ORIG="$MU/orig/pom.xml"; MUTANT="$MU/m3/pom.xml"; MODE=shade
    SR="$WT/simos-app/target/surefire-reports/io.mosire.simos.app.ShellMainParseTest.txt"
    ;;
  *) echo "unknown round: $ROUND" >&2; exit 2;;
esac
TARGET="$WT/$TARGET_REL"
JAR="$WT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"

restore() {
  cp "$ORIG" "$TARGET"
}

# 门禁① 干净世界：当前字节 == 原件
orig_md5="$(md5sum "$ORIG" | awk '{print $1}')"
cur_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ -n "$cur_md5" ] || die "clean-world: 读到空 md5"
[ "$cur_md5" = "$orig_md5" ] || die "clean-world: target=$cur_md5 != orig=$orig_md5"

# 门禁② 变异体字节确实不同
mut_md5="$(md5sum "$MUTANT" | awk '{print $1}')"
[ -n "$mut_md5" ] || die "mutant: 读到空 md5"
[ "$mut_md5" != "$orig_md5" ] || die "mutant==orig 字节（$mut_md5）"
log "SELF round=$ROUND mode=$MODE orig_md5=$orig_md5 mutant_md5=$mut_md5 start=$START"

# 门禁③ 按白名单推成目标名（精确落到规范路径，不是按变异文件名）
cp "$MUTANT" "$TARGET"
pushed_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ "$pushed_md5" = "$mut_md5" ] || die "push: $pushed_md5 != mutant $mut_md5"
log "SELF pushed_md5=$pushed_md5 target=$TARGET_REL"

# 门禁④ 清陈旧 .class
if [ "$MODE" = java ]; then rm -f $CLASS_GLOB; fi

# 跑目标
set +e
if [ "$MODE" = java ]; then
  ( cd "$WT" && ./mvnw -pl simos-app -am test -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false ) >"$RUNLOG" 2>&1
  rc=$?
else
  ( cd "$WT" && ./mvnw -pl simos-app -am package ) >"$RUNLOG" 2>&1
  build_rc=$?
  # 门禁⑤/⑥ 的 package 轮：编译无错 + 有真跑过测试
  comp=$(grep -c "COMPILATION ERROR" "$RUNLOG" || true)
  if [ "$comp" != "0" ]; then restore; die "COMPILATION ERROR=$comp（本轮作废）"; fi
  [ -f "$SR" ] || { restore; die "无 surefire 报告"; }
  mtime=$(stat -c %Y "$SR"); [ "$mtime" -ge "$START" ] || { restore; die "surefire 陈旧"; }
  tests=$(grep -oE "Tests run: [0-9]+" "$SR" | head -1 | grep -oE "[0-9]+")
  [ -n "$tests" ] && [ "$tests" -ge 1 ] || { restore; die "Tests run=$tests < 1"; }
  log "SELF package_rc=$build_rc surefire mtime=$mtime Tests=$tests"
  # 门禁⑦ 红点：manifest 无 Main-Class + java -jar 起不来
  mc=$(unzip -p "$JAR" META-INF/MANIFEST.MF | grep -c '^Main-Class:' || true)
  log "SELF shade Main-Class count in mutated jar = $mc (期望 0)"
  [ "$mc" = "0" ] || { restore; die "变异 jar 仍带 Main-Class（变异未生效）"; }
  rm -rf /tmp/m10-mut-m3-store
  jarout="$(java -jar "$JAR" --store /tmp/m10-mut-m3-store 2>&1)"
  jarrc=$?
  log "SELF java -jar rc=$jarrc output=$jarout"
  echo "$jarout" | grep -q "no main manifest attribute" || { restore; die "红点不是 no main manifest attribute"; }
  rc=0  # 走到这里即"变异体确实跑不起来"=红
fi
set -e
cat "$RUNLOG" >> "$LOG"
log "SELF runner_rc=$rc"

if [ "$MODE" = java ]; then
  # 门禁⑤ 无编译错误 + Tests run>=1
  comp=$(grep -c "COMPILATION ERROR" "$LOG" || true)
  [ "$comp" = "0" ] || { restore; die "COMPILATION ERROR=$comp（本轮作废）"; }
  [ -f "$SR" ] || { restore; die "无 surefire 报告"; }
  tests=$(grep -oE "Tests run: [0-9]+" "$SR" | head -1 | grep -oE "[0-9]+")
  [ -n "$tests" ] && [ "$tests" -ge 1 ] || { restore; die "Tests run=$tests < 1"; }
  # 门禁⑥ 报告 mtime 本轮内 + 预期红
  mtime=$(stat -c %Y "$SR")
  [ "$mtime" -ge "$START" ] || { restore; die "surefire 报告 mtime=$mtime 早于本轮 start=$START（陈旧）"; }
  failures=$(grep -oE "Failures: [0-9]+" "$SR" | head -1 | grep -oE "[0-9]+")
  log "SELF surefire mtime=$mtime(start=$START) Tests=$tests Failures=$failures"
  [ "$failures" -ge 1 ] || { restore; die "预期红但 Failures=$failures"; }
  # 门禁⑦ 红点落在被保护的那条断言上
  echo "$EXPECT" | grep -q . || die "EXPECT 为空（核对脚本自证）"
  grep -q "$EXPECT" "$LOG" || { restore; die "红点不是被保护断言：未在日志见到 [$EXPECT]"; }
fi

# 门禁⑧ 逐字节还原（cp，绝不 git checkout）
restore
restored_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ -n "$restored_md5" ] || die "还原后读到空 md5"
[ "$restored_md5" = "$orig_md5" ] || die "还原失败: $restored_md5 != $orig_md5"

# 门禁⑨ 自指留痕
log "SELF RESULT round=$ROUND mode=$MODE KILLED orig_md5=$orig_md5 mutant_md5=$mut_md5 pushed_md5=$pushed_md5 restored_md5=$restored_md5 end=$(date +%s)"
echo "ROUND $ROUND: KILLED (rc=$rc)" | tee -a "$LOG"
