#!/bin/bash
# Task 5 变异实验室。每轮：
#   干净世界（恢复 pristine 镜像 + **逐文件**比 md5 清单 + 文件数不得多不得少）
#   → 清 target/{classes,test-classes}（旧 .class 会活到下一轮）
#   → 变异（原地写入目标类名的文件）→ md5 自证落盘字节与原件**不同**
#   → 断言只有目标文件变了 → 跑模块测试 → 断言 `grep -c "COMPILATION ERROR"` 为 0
#
# 用法：
#   bash run.sh init    # 从**当前源码状态**建 pristine 镜像 + md5 清单（务必在干净世界下跑）
#   bash run.sh         # 跑全部轮次（ROUNDS="m5v-4" bash run.sh 可只跑一轮）
#
# 与 Task 4 的装置同形（逐字继承，只换 TARGET / 镜像范围 / 变异动作）：
# 变异**按目标类名**推入（不按变异文件名拷入，那会让"红"变成编译错误），每轮从干净世界重来，
# 读结果前先比 md5。
set -u
ROOT=/home/cna/SimulatorMosire
SRC=$ROOT/simos-map/src/main/java/io/mosire/simos
TESTROOT=$ROOT/simos-map/src/test/java/io/mosire/simos
PRISTINE=/tmp/m5lab/pristine
LAB=/tmp/m5lab
MAIN_MANIFEST=$LAB/manifest-main.txt
TEST_MANIFEST=$LAB/manifest-test.txt

declare -A TARGET=(
  [m5v-1]=map/HexCell.java
  [m5v-2]=map/GameMap.java
  [m5v-3]=map/GameMap.java
  [m5v-4]=map/GameMap.java
  [m5v-5]=map/GameMap.java
  [m5v-6]=map/GameMap.java
  [m5v-7]=map/City.java
)
ORDER="${ROUNDS:-m5v-1 m5v-2 m5v-3 m5v-4 m5v-5 m5v-6 m5v-7}"

init() {
  rm -rf "$PRISTINE" "$LAB/logs"
  mkdir -p "$PRISTINE" "$LAB/logs"
  cp -rf "$SRC/map" "$PRISTINE/"
  cp -f "$(dirname "$0")/mutate.py" "$LAB/mutate.py"
  (cd "$SRC" && find map -name '*.java' | sort | xargs md5sum > "$MAIN_MANIFEST")
  (cd "$TESTROOT" && find map -name '*.java' | sort | xargs md5sum > "$TEST_MANIFEST")
  echo "pristine 镜像与 md5 清单已建：$(wc -l < "$MAIN_MANIFEST") 个主源文件 + $(wc -l < "$TEST_MANIFEST") 个测试文件"
}

restore_pristine() {
  cp -rf "$PRISTINE/map" "$SRC/"
}

# 干净世界：每个文件与清单逐字节一致，且目录里没有清单之外的 .java
verify_clean() {
  local ok=0
  (cd "$SRC" && md5sum -c --status "$MAIN_MANIFEST") || { echo "!! 主源码与清单不符"; ok=1; }
  (cd "$TESTROOT" && md5sum -c --status "$TEST_MANIFEST") || { echo "!! 测试源码与清单不符"; ok=1; }
  local want_main have_main want_test have_test
  want_main=$(wc -l < "$MAIN_MANIFEST")
  have_main=$(cd "$SRC" && find map -name '*.java' | wc -l)
  want_test=$(wc -l < "$TEST_MANIFEST")
  have_test=$(cd "$TESTROOT" && find map -name '*.java' | wc -l)
  if [ "$want_main" != "$have_main" ] || [ "$want_test" != "$have_test" ]; then
    echo "!! 文件数不符：主 $want_main/$have_main，测试 $want_test/$have_test（有清单之外的 .java？）"
    ok=1
  fi
  return $ok
}

# 与清单不符的文件清单（用于断言"只有目标文件变了"）
changed_files() {
  (cd "$SRC" && md5sum -c --quiet "$MAIN_MANIFEST" 2>/dev/null) \
    | grep -v '^md5sum: ' | sed 's/: FAILED$//'
}

if [ "${1:-}" == "init" ]; then
  init
  exit 0
fi

for id in $ORDER; do
  KEPT=$LAB/logs/$id.kept
  : > "$KEPT"
  say() { echo "$@" | tee -a "$KEPT"; }

  say "=================== ROUND $id ==================="
  restore_pristine
  if ! verify_clean; then
    say "!! 干净世界校验失败 —— 本轮作废"
    continue
  fi
  say "干净世界 OK（pristine 镜像 + md5 清单逐文件一致，无多余 .java）"
  # ★ surefire-reports 也要清：留着的话，"本轮作废"时会打印**上一轮**的用例报告当作本轮的（m5v-7 第 1 次跑实测）。
  rm -rf "$ROOT/simos-map/target/classes" "$ROOT/simos-map/target/test-classes" \
         "$ROOT/simos-map/target/surefire-reports"

  rel=${TARGET[$id]}
  before=$(md5sum "$SRC/$rel" | awk '{print $1}')
  python3 "$LAB/mutate.py" "$id" || { say "!! 变异脚本失败 —— 本轮作废"; continue; }
  after=$(md5sum "$SRC/$rel" | awk '{print $1}')
  say "目标文件 $rel"
  say "md5 原件=$before  变异体=$after"
  if [ "$before" == "$after" ]; then
    say "!! 变异体与原件字节相同 —— 本轮作废"
    continue
  fi
  say "自证：落盘字节与原件不同 OK"

  changed=$(changed_files | sort)
  if [ "$changed" != "$rel" ]; then
    say "!! 与清单不符的文件不止目标那一个（或目标没变）：[$changed] —— 本轮作废"
    continue
  fi
  say "自证：除目标外无文件被改动（清单其余项全部一致）OK"

  (cd "$ROOT" && timeout 900 ./mvnw -pl simos-map -am test > "$LAB/logs/$id.log" 2>&1)
  ce=$(grep -c "COMPILATION ERROR" "$LAB/logs/$id.log")
  say "COMPILATION ERROR count = $ce"
  if [ "$ce" != "0" ]; then
    say "!! 编译错误 —— 本轮作废（红了也不是断言失败）"
    grep -A3 "COMPILATION ERROR" "$LAB/logs/$id.log" | head -20 | tee -a "$KEPT"
    continue
  fi
  # ★ 还有一类"红了但不是断言红"：构建挂在**用例之前的某个阶段**（checkstyle 在 validate、
  #   enforcer 更早）—— 那时 simos-map 的 surefire 一次都没跑，日志里没有一条 "Running io.mosire.simos.map…"。
  #   m5v-7 第 1 次跑就是这样：未使用的 import 让 checkstyle 先炸。故强制断言"测试真的跑过"。
  #   （不能用 `grep -c "Tests run:"` —— `-am` 会把 simos-util 的 156 条用例也算进来，
  #    实测退化成"计数 18 但 simos-map 一条没跑"的假绿。）
  tr=$(grep -c "Running io.mosire.simos.map" "$LAB/logs/$id.log")
  say "simos-map 跑过的测试类数 = $tr"
  if [ "$tr" == "0" ]; then
    say "!! simos-map 的用例压根没跑（构建挂在用例之前：checkstyle/enforcer 等）—— 本轮作废，红的不是断言"
    grep -E "^\[ERROR\]" "$LAB/logs/$id.log" | head -10 | tee -a "$KEPT"
    continue
  fi

  say "--- 红了的用例（Tests run 行里 Failures/Errors 非 0 的）---"
  grep -E "Tests run:" "$LAB/logs/$id.log" | grep -vE "Failures: 0, Errors: 0" \
    | sed 's/^\[INFO\] //;s/^\[ERROR\] //' | tee -a "$KEPT"
  say "--- 失败详情（Failures=断言红 / Errors=异常红，两者分开）---"
  grep -E "^\[ERROR\]" "$LAB/logs/$id.log" \
    | grep -vE "Tests run:|Reactor|BUILD|mvnw|help|^\[ERROR\] $|For more information|Full documentation|\[Help 1\]|after earlier|To see|-> \[Help" \
    | head -30 | tee -a "$KEPT"
  say "--- 模块汇总（各模块 Results 段的合计行）---"
  grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" \
    "$LAB/logs/$id.log" | tee -a "$KEPT"
  grep -E "BUILD (SUCCESS|FAILURE)" "$LAB/logs/$id.log" | tail -1 | tee -a "$KEPT"
  say "--- surefire 明说（断言消息：expected/but was 或异常类型，去掉栈帧）---"
  for f in "$ROOT"/simos-map/target/surefire-reports/*.txt; do
    [ -e "$f" ] || continue
    case "$f" in
      *HexCellTest*|*GameMapTest*|*CityTest*) grep -vE '^\s+at |^\s*$' "$f" | head -40 | tee -a "$KEPT" ;;
    esac
  done
done

echo "=================== 恢复干净世界 ==================="
restore_pristine
if verify_clean; then
  echo "恢复后与清单一致 OK"
else
  echo "!! 恢复失败 —— 源码已偏离清单"
fi
rm -rf "$ROOT/simos-map/target/classes" "$ROOT/simos-map/target/test-classes"
(cd "$SRC" && md5sum map/HexCell.java map/City.java map/CityId.java map/GameMap.java map/generate/GenerationSpec.java)
