#!/usr/bin/env bash
# M1 变异轮装置（简报 §8 纪律）：
#   ① 每轮开跑前把工作目录恢复成干净世界（重编原件 + 比 md5，逐文件对 orig_md5.txt）
#   ② 按**白名单**把变异体推成**目标类名**（目标路径的文件名必须 == 目标类名；推送后 md5 必须 == 变异体 md5）
#   ③ 强制断言日志里 "COMPILATION ERROR" 计数为 0，不为 0 当场作废该轮（VOID）
#   ④ 日志自带自指段：本轮跑的是哪份字节（md5）、rc、判定、红点
# 用法: bash mut-round.sh <m1|m2|m3|m4|m5> [轮次后缀]
set -u
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV=$WT/.superpowers/sdd/2026-09-22-tool-surface/m1-evidence
PRI=$EV/mutants/pristine
WL=$EV/mutants/whitelist
LOGS=$EV/mutants/logs
MAIN=$WT/simos-app/src/main/java/io/mosire/simos/app/tools
TEST=$WT/simos-app/src/test/java/io/mosire/simos/app/tools
REP=$WT/simos-app/target/surefire-reports

MID=${1:?用法: mut-round.sh <m1..m5> [后缀]}
SUF=${2:-}
LOG=$LOGS/$MID$SUF.log
mkdir -p "$LOGS"
exec 3>&1                    # 保存真实 stdout（trap 要用）
exec >> "$LOG" 2>&1
trap 'tail -n 80 "$LOG" >&3' EXIT   # 无论怎么退出，日志尾部回到终端（全量留在日志文件里）

case "$MID" in
  m1) TARGET=SimosToolSource.java;            DEST=$MAIN/SimosToolSource.java
      TESTS="SimosToolsTest McpServerTest McpPortTopologyTest" ;;
  m2|m2b) TARGET=MapSetTerrainTool.java;       DEST=$MAIN/write/MapSetTerrainTool.java
      TESTS="SimosToolsTest McpServerTest" ;;
  m3) TARGET=SimosToolsTest.java;             DEST=$TEST/SimosToolsTest.java
      TESTS="SimosToolsTest" ;;
  m4|m5) TARGET=AbstractNarrowWriteTool.java; DEST=$MAIN/write/AbstractNarrowWriteTool.java
      TESTS="SimosToolsTest" ;;
  *) echo "未知变异体 $MID"; exit 2 ;;
esac

md5of() { md5sum "$1" | cut -d' ' -f1; }
# 报告路径：SimosToolsTest 在 .tools 包，其余在 .app 包
repof() { case "$1" in SimosToolsTest) echo "$REP/io.mosire.simos.app.tools.$1.txt" ;;
                                  *) echo "$REP/io.mosire.simos.app.$1.txt" ;; esac; }

# 干净世界的 4 个原件：basename -> 工作目录绝对路径
declare -A DEST_OF=(
  [SimosToolSource.java]="$MAIN/SimosToolSource.java"
  [MapSetTerrainTool.java]="$MAIN/write/MapSetTerrainTool.java"
  [AbstractNarrowWriteTool.java]="$MAIN/write/AbstractNarrowWriteTool.java"
  [SimosToolsTest.java]="$TEST/SimosToolsTest.java"
)
restore() {
  for b in "${!DEST_OF[@]}"; do cp "$PRI/$b" "${DEST_OF[$b]}"; done
}
# 期望 md5 按 basename 到 orig_md5.txt 里取（★ 不按整行比对，避免拿原件路径自己比自己）
want_md5() { grep -E "  .*/$1$" "$PRI/orig_md5.txt" | cut -d' ' -f1; }

echo "===== 变异轮 $MID$SUF（目标类 $TARGET）====="
echo "--- ① 干净世界还原 ---"
restore
restore_ok=1
for b in "${!DEST_OF[@]}"; do
  w=$(want_md5 "$b"); n=$(md5of "${DEST_OF[$b]}")
  if [ -z "$w" ]; then echo "  ★ orig_md5.txt 里取不到 $b 的期望值（读取失败≠相符）"; restore_ok=0; continue; fi
  echo "  restore $b  want=$w now=$n  $([ "$n" = "$w" ] && echo OK || { echo MISMATCH; restore_ok=0; })"
done
echo "  restore_ok=$restore_ok"
[ "$restore_ok" = 1 ] || { echo "verdict=VOID(restore)"; exit 3; }

echo "--- ② 白名单推送 ---"
WLF=$WL/$MID/$TARGET
[ -f "$WLF" ] || { echo "★ 白名单里没有 $WLF"; echo "verdict=VOID(no-mutant)"; exit 3; }
cnt=$(find "$WL/$MID" -name '*.java' | wc -l)
echo "  whitelist_dir_javas=$cnt （须为 1）文件名=$TARGET"
[ "$cnt" = 1 ] || { echo "★ 白名单目录形态不对"; echo "verdict=VOID(whitelist-shape)"; exit 3; }
stray=$(find "$MAIN" "$MAIN/write" "$TEST" -name '*.java' | grep -ciE 'mut|variant' || true)
echo "  stray_mutant_named_javas=$stray （须为 0）"
[ "$stray" = 0 ] || { echo "★ 工作目录里有按变异名命名的 .java（按变异名拷入的老坑）"; echo "verdict=VOID(stray)"; exit 3; }

cp "$WLF" "$DEST"
mut_md5=$(md5of "$WLF"); dst_md5=$(md5of "$DEST")
orig_this=$(want_md5 "$TARGET")
echo "  orig_md5_of_target=$orig_this"
echo "  mutant_md5=$mut_md5"
echo "  pushed_md5=$dst_md5  目标=$DEST"
[ -n "$orig_this" ] || { echo "★ 取不到原件 md5 ⇒ 作废"; echo "verdict=VOID(no-orig-md5)"; exit 3; }
[ "$mut_md5" = "$dst_md5" ] || { echo "★ 推送后字节 != 变异体"; echo "verdict=VOID(push)"; exit 3; }
[ "$mut_md5" != "$orig_this" ] || { echo "★ 变异体与原件逐字节相同"; echo "verdict=VOID(same-as-orig)"; exit 3; }
echo "  （变异体与原件字节不同：OK）"

echo "--- ③ 运行聚焦用例 -Dtest=${TESTS// /,} ---"
cd "$WT" || exit 3
round_start=$(date +%s)
./mvnw -o -pl simos-app -am -Dtest="${TESTS// /,}" -Dsurefire.failIfNoSpecifiedTests=false test
rc=$?
dur=$(( $(date +%s) - round_start ))
echo "rc=$rc  耗时=${dur}s"

# ── ④ 编译错误强制断言（★ 模式带 [ERROR] 前缀：本脚本自己的 echo 行不含该前缀，
#      否则装置会拿自己的输出当证据 —— 本轮 m3 首次即中，见 m3.log:533/535）──
COMP_PAT='\[ERROR\] COMPILATION ERROR'
comp=$(grep -c "$COMP_PAT" "$LOG")
echo "--- ④ 编译错误计数强制断言 ---"
echo "compilation_error_count=$comp （须为 0）"

# ── ⑤ 判定 + 红点提取（只读本轮 mtime 的报告：陈旧报告既会造假红也会造假绿）──
echo "--- ⑤ 判定 + 红点提取 ---"
tot_f=0; tot_e=0; fresh_ok=1
for cls in $TESTS; do
  f=$(repof "$cls")
  if [ ! -f "$f" ]; then echo "  --- $cls：无报告（★ 没跑到 ≠ 没红）"; fresh_ok=0; continue; fi
  mt=$(stat -c %Y "$f")
  fresh=$([ "$mt" -ge "$round_start" ] && echo 本轮 || { echo "陈旧★"; fresh_ok=0; })
  line=$(grep -m1 'Tests run' "$f")
  echo "  --- $cls  mtime=$(stat -c %y "$f" | cut -d. -f1)  报告新鲜度=$fresh  $line"
  tot_f=$(( tot_f + $(echo "$line" | sed -E 's/.*Failures: ([0-9]+).*/\1/') ))
  tot_e=$(( tot_e + $(echo "$line" | sed -E 's/.*Errors: ([0-9]+).*/\1/') ))
  if [ "$tot_f$tot_e" != "00" ] || [ "$rc" != 0 ]; then
    grep -A8 -E '<<< (FAILURE|ERROR)!' "$f" | sed 's/^/      /' | head -40
  fi
done
echo "  本轮断言失败合计: Failures=$tot_f Errors=$tot_e  报告新鲜度=$fresh_ok"

verdict=SURVIVED
if [ "$comp" != 0 ]; then
  verdict="VOID(compilation-error)"
elif [ "$rc" = 0 ]; then
  verdict=SURVIVED
elif [ $(( tot_f + tot_e )) -gt 0 ]; then
  verdict=KILLED
else
  verdict="VOID(gate-red)"   # ★ 红在门禁、不在断言 ⇒ 不算杀（红点必须是被保护的那行本身）
fi
echo "verdict=$verdict"

echo "--- ⑥ 还原干净世界 ---"
restore
back=$(md5of "$DEST")
echo "  post_restore_md5=$back  want=$orig_this  $([ "$back" = "$orig_this" ] && echo OK || echo MISMATCH)"
echo "===== 装置自记 ====="
echo "round=$MID$SUF"
echo "target_class=$TARGET"
echo "orig_md5=$orig_this"
echo "mutant_md5=$mut_md5"
echo "pushed_md5=$dst_md5"
echo "compilation_error_count=$comp"
echo "assert_failures=$tot_f"
echo "assert_errors=$tot_e"
echo "reports_fresh=$fresh_ok"
echo "rc=$rc"
echo "verdict=$verdict"
echo "post_restore_md5=$back"
echo "===== 自记结束 ====="
