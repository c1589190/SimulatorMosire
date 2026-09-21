#!/usr/bin/env bash
# M2 变异轮装置（简报 §7 纪律 + 本仓形态清单）：
#   ① 每轮开跑前把工作目录恢复成干净世界（从 pristine 逐字节 cp 回去 + 比 md5；m3 的"新增文件"按名删除）
#   ② 按**白名单**把变异体推成**目标类名**（目标路径文件名必须 == 目标类名；推送后 md5 必须 == 变异体 md5）
#   ③ 强制断言日志里 "[ERROR] COMPILATION ERROR" 计数为 0，不为 0 当场作废该轮（VOID）
#   ④ 判定 = KILLED 仅当 rc!=0 **且**断言失败数 > 0（红在门禁不算杀：红点必须是被保护的那行本身）
#   ⑤ 报告新鲜度：只读**本轮 mtime** 的 surefire 报告（陈旧报告既会造假红也会造假绿）
#   ⑥ 日志自带自指段：本轮推的是哪份字节（orig/mutant/pushed md5）、rc、判定、红点
# 用法: bash mut-round.sh <m1|m2|m3|m4> [轮次后缀]
set -u
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV=$WT/.superpowers/sdd/2026-09-22-tool-surface/m2-evidence
PRI=$EV/mutants/pristine
WL=$EV/mutants/whitelist
LOGS=$EV/mutants/logs
MAIN=$WT/simos-app/src/main/java/io/mosire/simos/app/tools
TESTW=$WT/simos-app/src/test/java/io/mosire/simos/app/tools
TESTA=$WT/simos-app/src/test/java/io/mosire/simos/app
REP=$WT/simos-app/target/surefire-reports

MID=${1:?用法: mut-round.sh <m1..m4> [后缀]}
SUF=${2:-}
LOG=$LOGS/$MID$SUF.log
mkdir -p "$LOGS"
exec 3>&1
exec >> "$LOG" 2>&1
trap 'tail -n 100 "$LOG" >&3' EXIT

case "$MID" in
  m1) TARGETS=(SimosToolSource.java)
      TESTS="SimosToolsTest McpServerTest McpPortTopologyTest" ;;
  m2) TARGETS=(UnitCancelRouteTool.java)
      TESTS="SimosToolsTest McpServerTest" ;;
  m2b) TARGETS=(UnitCancelRouteTool.java)
      TESTS="SimosToolsTest" ;;
  m3) TARGETS=(UnitFooTool.java)
      TESTS="SimosToolsTest" ;;
  m4) TARGETS=(SimosToolSource.java McpPortTopologyTest.java)
      TESTS="SimosToolsTest McpPortTopologyTest"
      # ★ 语义落点探针（m4 首轮实测新增）：变异体必须**落在语句位置**。
      #   首轮 m4 的插入行被并进了上一行的 `//` 注释里（生成器切分键少一个换行）⇒ 变异体
      #   **合法、可编译、却不生效**（决策桶仍 31 条），而装置一路全绿报 KILLED。
      #   ⇒ 每轮推送后必须证明"改动确实落在语句位置"，否则判 VOID（空操作不算红也不算绿）。
      #   ★ 探针模式要**唯一指向插入的那条语句**：GM 桶本来就有一条 `MapSetTerrainTool`（无尾注），
      #     故带上插入行自己的尾注 `// m4：`（首测按裸语句名数出 2 ⇒ 探针自己会假红）。
      PROBE_SRC=SimosToolSource.java
      PROBE_PAT='^    built\.add\(new MapSetTerrainTool.*// m4：'
      PROBE_WANT=1 ;;
  *) echo "未知变异体 $MID"; exit 2 ;;
esac

md5of() { md5sum "$1" | cut -d' ' -f1; }
repof() { case "$1" in SimosToolsTest) echo "$REP/io.mosire.simos.app.tools.$1.txt" ;;
                                  *) echo "$REP/io.mosire.simos.app.$1.txt" ;; esac; }

# 干净世界的原件：basename -> 工作树绝对路径（restore 用它；m3 的新增文件不在其中）
declare -A DEST_OF=(
  [SimosToolSource.java]="$MAIN/SimosToolSource.java"
  [UnitCancelRouteTool.java]="$MAIN/write/UnitCancelRouteTool.java"
  [SimosToolsTest.java]="$TESTW/SimosToolsTest.java"
  [McpPortTopologyTest.java]="$TESTA/McpPortTopologyTest.java"
)
# m3 的"新增文件"：restore 时必须**删除**（它不在 pristine 里）
ADDED_OF=( "$MAIN/write/UnitFooTool.java" )

want_md5() { grep -E "  .*/$1$" "$PRI/orig_md5.txt" | cut -d' ' -f1; }

restore() {
  for b in "${!DEST_OF[@]}"; do cp "$PRI/$b" "${DEST_OF[$b]}"; done
  for f in "${ADDED_OF[@]}"; do rm -f "$f"; done
}

echo "===== 变异轮 $MID$SUF（目标类 ${TARGETS[*]}）====="
echo "--- ① 干净世界还原 ---"
restore
restore_ok=1
for b in "${!DEST_OF[@]}"; do
  w=$(want_md5 "$b"); n=$(md5of "${DEST_OF[$b]}")
  if [ -z "$w" ]; then echo "  ★ orig_md5.txt 里取不到 $b 的期望值（读取失败≠相符）"; restore_ok=0; continue; fi
  echo "  restore $b  want=$w now=$n  $([ "$n" = "$w" ] && echo OK || { echo MISMATCH; restore_ok=0; })"
done
for f in "${ADDED_OF[@]}"; do
  [ -f "$f" ] && { echo "  ★ 新增文件仍在：$f"; restore_ok=0; } || echo "  absent  $f（须为 absent）OK"
done
echo "  restore_ok=$restore_ok"
[ "$restore_ok" = 1 ] || { echo "verdict=VOID(restore)"; exit 3; }

echo "--- ② 白名单推送 ---"
# 工作目录里不许有按变异名命名的 .java（"按变异文件名拷入"那个老坑）
stray=$(find "$MAIN" "$MAIN/write" "$TESTW" "$TESTA" -name '*.java' | grep -ciE 'mut|variant|foo' || true)
echo "  stray_mutant_named_javas=$stray （须为 0）"
[ "$stray" = 0 ] || { echo "★ 工作目录里有按变异名命名的 .java"; echo "verdict=VOID(stray)"; exit 3; }

declare -A PUSHED_MD5=()
for b in "${TARGETS[@]}"; do
  WLF=$WL/$MID/$b
  [ -f "$WLF" ] || { echo "★ 白名单里没有 $WLF"; echo "verdict=VOID(no-mutant)"; exit 3; }
  if [ -n "${DEST_OF[$b]:-}" ]; then DEST=${DEST_OF[$b]}; else DEST=$MAIN/write/$b; fi
  cp "$WLF" "$DEST"
  mut_md5=$(md5of "$WLF"); dst_md5=$(md5of "$DEST")
  orig_this=$(want_md5 "$b")
  echo "  target=$b"
  echo "    dest=$DEST"
  echo "    orig_md5_of_target=${orig_this:-（新增文件，无原件）}"
  echo "    mutant_md5=$mut_md5"
  echo "    pushed_md5=$dst_md5"
  [ "$mut_md5" = "$dst_md5" ] || { echo "★ 推送后字节 != 变异体"; echo "verdict=VOID(push)"; exit 3; }
  if [ -n "$orig_this" ]; then
    [ "$mut_md5" != "$orig_this" ] || { echo "★ 变异体与原件逐字节相同"; echo "verdict=VOID(same-as-orig)"; exit 3; }
  fi
  PUSHED_MD5[$b]=$dst_md5
done
echo "  （变异体与原件字节不同 / 新增文件确已落盘：OK）"

# ── ②b 语义落点探针：证明改动落在**语句位置**而不是被注释吞掉（空操作变异体）──
if [ -n "${PROBE_PAT:-}" ]; then
  got=$(grep -cE "$PROBE_PAT" "${DEST_OF[$PROBE_SRC]}" || true)
  echo "  语义落点探针: grep -cE '$PROBE_PAT' $PROBE_SRC => $got （须为 $PROBE_WANT）"
  [ "$got" = "$PROBE_WANT" ] || {
    echo "★ 变异体没落在语句位置（被注释吞掉 / 静默空操作）——空操作既不是红也不是绿"
    echo "verdict=VOID(probe)"
    exit 3
  }
fi

echo "--- ③ 运行聚焦用例 -Dtest=${TESTS// /,} ---"
cd "$WT" || exit 3
round_start=$(date +%s)
./mvnw -o -pl simos-app -am -Dtest="${TESTS// /,}" -Dsurefire.failIfNoSpecifiedTests=false test
rc=$?
dur=$(( $(date +%s) - round_start ))
echo "rc=$rc  耗时=${dur}s"

# ── ④ 编译错误强制断言（模式带 [ERROR] 前缀：装置自己的 echo 行不含该前缀，
#      否则装置会拿自己的输出当证据 —— M1 的 m3 首轮即中过）──
COMP_PAT='\[ERROR\] COMPILATION ERROR'
comp=$(grep -c "$COMP_PAT" "$LOG" || true)
echo "--- ④ 编译错误计数强制断言 ---"
echo "compilation_error_count=$comp （须为 0）"

# ── ⑤ 判定 + 红点提取 ──
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
    grep -A12 -E '<<< (FAILURE|ERROR)!' "$f" | sed 's/^/      /' | head -80
  fi
done
echo "  本轮断言失败合计: Failures=$tot_f Errors=$tot_e  报告新鲜度=$fresh_ok"

# ── ⑤a 运行时自报（生产侧变异是否**真的生效**的运行期证据）──
#   Shell 启动日志会自报装配规模；若生产侧变异体是"空操作"，这里会原样显示旧值。
echo "  Shell 装配自报（本轮运行期取值）："
grep -oE '(decisionTool|tool)=[0-9]+' "$LOG" | sort | uniq -c | sed 's/^/    /' || echo "    （无自报行）"

# m4 专项：正向精确匹配必须绿、反向断言必须红（"双侧同改 ⇒ 只剩负向断言守得住"）
if [ "$MID" = m4 ]; then
  echo "--- ⑤b m4 专项：两条断言各自的结局 ---"
  for tag in "C7：决策人口" "C7 反向①" "C7 反向②" "C7 反向③"; do
    n=$(grep -c "$tag" "$REP/io.mosire.simos.app.McpPortTopologyTest.txt" || true)
    echo "  文本命中 '$tag' 于失败报告：$n （正向应为 0；被点名的负向应 ≥1）"
  done
fi

verdict=SURVIVED
if [ "$comp" != 0 ]; then
  verdict="VOID(compilation-error)"
elif [ "$rc" = 0 ]; then
  verdict=SURVIVED
elif [ $(( tot_f + tot_e )) -gt 0 ]; then
  verdict=KILLED
else
  verdict="VOID(gate-red)"
fi
echo "verdict=$verdict"

echo "--- ⑥ 还原干净世界 ---"
restore
back_ok=1
for b in "${!DEST_OF[@]}"; do
  w=$(want_md5 "$b"); n=$(md5of "${DEST_OF[$b]}")
  echo "  post_restore $b  want=$w now=$n  $([ "$n" = "$w" ] && echo OK || { echo MISMATCH; back_ok=0; })"
done
for f in "${ADDED_OF[@]}"; do
  [ -f "$f" ] && { echo "  ★ post_restore 新增文件仍在：$f"; back_ok=0; } || echo "  post_restore absent $f OK"
done
echo "  post_restore_ok=$back_ok"

echo "===== 装置自记 ====="
echo "round=$MID$SUF"
echo "target_classes=${TARGETS[*]}"
for b in "${TARGETS[@]}"; do echo "pushed_${b}=${PUSHED_MD5[$b]:-NA}"; done
echo "compilation_error_count=$comp"
echo "assert_failures=$tot_f"
echo "assert_errors=$tot_e"
echo "reports_fresh=$fresh_ok"
echo "rc=$rc"
echo "verdict=$verdict"
echo "post_restore_ok=$back_ok"
echo "===== 自记结束 ====="
