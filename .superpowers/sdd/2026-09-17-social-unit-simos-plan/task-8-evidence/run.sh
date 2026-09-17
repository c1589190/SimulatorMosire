#!/bin/bash
# M3 Task 8 变异实验室（Task 7 装置的拷贝，按 R-8-e 只改几处：
#   ① LAB 与 ROUNDS_DIR → 本任务（/tmp/m3t8lab、task-8-evidence/rounds）；
#   ② TARGET → m3t8v-1(m1 测量)/m3t8v-2(m1' 真变异)/m3t8v-3(m2)/m3t8v-4(m3+探针)；
#   ③ manifest 范围不变（util+map+unit，reactor 三模块）；
#   ④ "surefire 明说"抽取保留（实际失败类：日志 FAILURE! 行 + simos-unit surefire-reports 摘要）。
#   其余（干净世界 extras=0、并集自证、COMPILATION ERROR count = 0、红点抽取、.kept 格式）不动。
# 每轮：
#   干净世界（**从工作树 rsync 一份全新副本**，排除 target/ ⇒ 没有上一轮的 .class 能活到下轮）
#   → 逐文件比 md5 清单 + 文件数不得多不得少 + **断言无清单之外的 .java**
#   → **改前**：先在干净世界里跑一遍（护栏必须是绿的）
#   → 变异（m1：scale→Math.round 形态；m1'：costOf 丢 ×1000；m2：>=→>；m3：删跳过守卫+落探针用例）
#     → 修改的文件：与原件**字节不同**；新增的文件：已落盘且非空
#     → **并集自证**：实际(改动 ∪ 新增) == 声明集合
#   → **改后**：跑同一套测试
#   → 断言 `grep -c "COMPILATION ERROR"` 为 0 → 断言 simos-unit 的用例**真的跑过**
#
# ★ 变异在 /tmp 的副本上做，工作树一字不动。`refresh_repo` 排除 `.superpowers`
#   ⇒ 本脚本往下写 .kept 不会破坏下一轮的干净世界。
#
# 用法：
#   bash run.sh init          # 建 md5 清单（务必在工作树干净、且**新测试文件已就位**时跑）
#   bash run.sh               # 跑全部轮次（ROUNDS="m3t8v-1" bash run.sh 只跑一轮）
set -u
ROOT=/home/cna/SimulatorMosire
LAB=/tmp/m3t8lab
REPO=$LAB/repo
MANIFEST=$LAB/manifest.txt
HERE=$(cd "$(dirname "$0")" && pwd)
ROUNDS_DIR=$ROOT/.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-8-evidence/rounds

declare -A TARGET=(
  [m3t8v-1]="R-8-a ① m1 原形态：scale 的 floorDiv(millis×‰+500,1000) → Math.round(millis×‰/1000.0)。代数结论（R-8-a）：v 恒为 moveCost×1000 ⇒ +500 永不进位 ⇒ floorDiv 恒等于精确乘法 moveCost×‰；v×‰ ≤ 998000×(2^31-1) ≈ 2.1e15 < 2^53 ⇒ double 两侧全精确 ⇒ **预计存活（等价变体、无判别输入）**——判别输入的实测扫描另存 task-8-evidence/m1-scale-scan.txt，红点以日志为准"
  [m3t8v-2]="R-8-a ③ m1' 真变异：costOf 里 scale(type.moveCost() * 1000L, …) → scale(type.moveCost(), …)（丢 ×1000，单位错误）⇒ 期望 stepCostsMatchTheFrozenFixture 红（12500→floorDiv(25×500+500,1000)=13、32500→33）；impassable（在 scale 之前 return empty）与 minStep（用自己的 ×1000L 调用点）应仍绿——实际红点以日志为准"
  [m3t8v-3]="R-8-b m2：costOf 的 >= → >（999 漏判为可通行）⇒ 期望 impassableTerrainHasNoCost 红（empty → OptionalLong.of(scale(999000,500)=499500)）；minStepCost…（map(STEEP_65) 无不可通行格）与其余用例应仍绿——实际红点以日志为准"
  [m3t8v-4]="R-8-b m3（fix-1 形态）：删 minStepCostMillis 的『跳过不可通行』守卫。fix-1 已把『全不可通行 ⇒ 0』探针转正为提交用例 allImpassableMapHasZeroLowerBound（夹具 allImpassableMap()）⇒ 期望红点落**提交在案**的该用例（expected 0L, but was 499500L）。判别力分析不变：计划自带 minStep 用例（map(STEEP_65)）对 m3 无判别输入（min 对更大值不敏感 + 无 999 格），预计仍绿——实际红点以日志为准（fix-1 前的探针形态 .kept 已被本轮取代，轨迹见报告 fix-1 节）"
)
ORDER="${ROUNDS:-m3t8v-1 m3t8v-2 m3t8v-3 m3t8v-4}"

refresh_repo() {
  rm -rf "$REPO"
  rsync -a --exclude 'target' --exclude '.git' --exclude '.serena' --exclude '.superpowers' \
        "$ROOT/" "$REPO/"
}

repo_java_files() {
  (cd "$REPO" && find simos-util/src simos-map/src simos-unit/src -name '*.java' | sort)
}

init() {
  mkdir -p "$LAB/logs" "$ROUNDS_DIR"
  (cd "$ROOT" && find simos-util/src simos-map/src simos-unit/src -name '*.java' | sort | xargs md5sum) \
    > "$MANIFEST"
  cp -f "$HERE/mutate.py" "$LAB/mutate.py"
  echo "md5 清单已建：$(wc -l < "$MANIFEST") 个 .java"
}

# 清净世界三连：清单逐文件一致 + 文件数一致 + **无清单之外的新增 .java**（Task 14 升级保留）
verify_clean() {
  local ok=0
  (cd "$REPO" && md5sum -c --status "$MANIFEST") || { echo "!! 副本与清单不符"; ok=1; }
  local want have
  want=$(wc -l < "$MANIFEST")
  have=$(repo_java_files | wc -l)
  if [ "$want" != "$have" ]; then
    echo "!! 文件数不符：清单 $want / 副本 $have（有规范名之外的 .java？）"
    ok=1
  fi
  local extras
  extras=$(comm -13 <(awk "{print \$2}" "$MANIFEST" | sort) <(repo_java_files))
  if [ -n "$extras" ]; then
    echo "!! 副本里有清单之外的新增 .java（上一轮的变异文件没清掉）：$extras"
    ok=1
  fi
  return $ok
}

# 实际改动 = md5 失配（修改）∪ 清单之外的新文件（新增）——与声明集合比并集（Task 14 升级保留）
actual_changes() {
  { (cd "$REPO" && md5sum -c --quiet "$MANIFEST" 2>/dev/null) \
      | grep -v '^md5sum: ' | sed 's/: FAILED$//' | sort
    comm -13 <(awk "{print \$2}" "$MANIFEST" | sort) <(repo_java_files)
  } | sort -u
}

run_tests() { # $1 = 日志文件
  (cd "$REPO" && timeout 1800 ./mvnw -pl simos-unit -am test) > "$1" 2>&1
}

# 红点：测试名 + 行号（surefire 的 [ERROR] 摘要行）。
# ★ 过滤掉 Maven 的 Reactor 续跑提示行 `[ERROR]   mvn <args> -rf :simos-unit` —— 它也以
#   `[ERROR]   ` 开头且以字母起首，会混进"红点"清单（M2 Task 9 首轮实测），故排除。
red_points() { # $1 = 日志文件
  grep -E "^\[ERROR\]   [A-Za-z]" "$1" | grep -vE "^\[ERROR\]   mvn <args>" | sed 's/^\[ERROR\]   //'
}

digest() { # $1 = 日志文件
  grep -E "^\[INFO\] Running io\.mosire\.simos\." "$1" | sed 's/^\[INFO\] //'
  grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$1"
  grep -E "BUILD (SUCCESS|FAILURE)" "$1" | tail -1
  grep -E "Tests run:" "$1" | grep -vE "Failures: 0, Errors: 0" | sed 's/^\[INFO\] //;s/^\[ERROR\] //'
  red_points "$1"
}

if [ "${1:-}" == "init" ]; then
  init
  exit 0
fi

cp -f "$HERE/mutate.py" "$LAB/mutate.py"
mkdir -p "$LAB/logs" "$ROUNDS_DIR"

for id in $ORDER; do
  KEPT=$ROUNDS_DIR/$id.kept
  : > "$KEPT"
  say() { echo "$@" | tee -a "$KEPT"; }

  say "=================== ROUND $id ==================="
  say "目标：${TARGET[$id]:-?}"
  say "★ 变异在 /tmp 副本上做：REPO=$REPO（工作树 $ROOT 一字不动）"
  refresh_repo
  if ! verify_clean; then
    say "!! 干净世界校验失败 —— 本轮作废"
    continue
  fi
  extras=$(comm -13 <(awk "{print \$2}" "$MANIFEST" | sort) <(repo_java_files) | wc -l)
  say "干净世界 OK（副本逐文件与 $ROOT 的 md5 清单一致，共 $(wc -l < "$MANIFEST") 个 .java；清单之外的 .java = $extras 个，上一轮新增的变异文件已清）"

  # ── 改前（护栏必须绿）──────────────────────────────────────────────────────
  run_tests "$LAB/logs/$id.before.log"
  say "--- 改前（工作树 + 本任务新测试文件，未变异）---"
  say "改前 COMPILATION ERROR count = $(grep -c 'COMPILATION ERROR' "$LAB/logs/$id.before.log")"
  digest "$LAB/logs/$id.before.log" | tee -a "$KEPT"

  # ── 变异 + 并集自证 ────────────────────────────────────────────────────────
  declared=$(python3 "$LAB/mutate.py" --files "$id")
  read -r -a declared_arr <<< "$declared"
  before_hashes=()
  new_files=()
  for f in "${declared_arr[@]}"; do
    if [ -f "$REPO/$f" ]; then
      before_hashes+=("$(md5sum "$REPO/$f" | awk '{print $1}')")
    else
      before_hashes+=("(新增)")
      new_files+=("$f")
    fi
  done
  python3 "$LAB/mutate.py" "$id" || { say "!! 变异脚本失败 —— 本轮作废"; continue; }
  say "声明要改/新增的文件（$((${#declared_arr[@]})) 个，其中新增 $((${#new_files[@]})) 个）："
  for i in "${!declared_arr[@]}"; do
    after=$(md5sum "$REPO/${declared_arr[$i]}" | awk '{print $1}')
    say "  ${declared_arr[$i]}  md5 原件=${before_hashes[$i]}  变异体=$after"
    if [ "${before_hashes[$i]}" == "(新增)" ]; then
      if [ ! -s "$REPO/${declared_arr[$i]}" ]; then
        say "!! 新增文件不存在或为空 —— 本轮作废"
        continue 2
      fi
    elif [ "${before_hashes[$i]}" == "$after" ]; then
      say "!! 该文件与原件字节相同 —— 本轮作废"
      continue 2
    fi
  done
  say "自证：修改的文件与原件字节不同 / 新增的文件已落盘且非空 OK"

  changed=$(actual_changes)
  expected=$(printf '%s\n' "${declared_arr[@]}" | sort -u)
  if [ "$changed" != "$expected" ]; then
    say "!! 实际（修改 ∪ 新增）与声明不一致：实际=[$changed] 声明=[$expected] —— 本轮作废"
    continue
  fi
  say "自证：实际（修改 ∪ 新增）== 声明集合，别无其它改动 OK"

  # ── 改后 ──────────────────────────────────────────────────────────────────
  run_tests "$LAB/logs/$id.after.log"
  ce=$(grep -c "COMPILATION ERROR" "$LAB/logs/$id.after.log")
  say "--- 改后（变异体）---"
  say "COMPILATION ERROR count = $ce"
  if [ "$ce" != "0" ]; then
    say "!! 编译错误 —— 本轮作废（红了也不是断言红）"
    grep -B2 -A8 "COMPILATION ERROR" "$LAB/logs/$id.after.log" | head -50 | tee -a "$KEPT"
    continue
  fi
  tr=$(grep -c "Running io.mosire.simos.unit" "$LAB/logs/$id.after.log")
  say "simos-unit 跑过的测试类数 = $tr"
  if [ "$tr" == "0" ]; then
    say "!! simos-unit 的用例压根没跑（构建挂在用例之前：checkstyle/enforcer 等）—— 本轮作废，红的不是断言"
    grep -E "^\[ERROR\]" "$LAB/logs/$id.after.log" | head -10 | tee -a "$KEPT"
    continue
  fi
  digest "$LAB/logs/$id.after.log" | tee -a "$KEPT"
  say "--- 红点（测试名 + 行号；无输出 = 全绿）---"
  red_points "$LAB/logs/$id.after.log" | tee -a "$KEPT"
  say "--- surefire 明说（实际失败类：日志 FAILURE! 行 + 单元全部测试类的 .txt 摘要，去栈帧）---"
  grep -E "FAILURE! -- in io\.mosire\.simos\.unit" "$LAB/logs/$id.after.log" | tee -a "$KEPT"
  for f in "$REPO"/simos-unit/target/surefire-reports/*Test.txt; do
    [ -e "$f" ] || continue
    grep -vE '^\s+at |^\s*$' "$f" | head -120 | tee -a "$KEPT"
  done
  say "--- 红/绿的判读（脚本不替你下结论）---"
  say "红点数 = $(red_points "$LAB/logs/$id.after.log" | wc -l)"
done

echo "=================== 工作树状态（本实验从未被变异触碰）==================="
(cd "$ROOT" && git status --short)
