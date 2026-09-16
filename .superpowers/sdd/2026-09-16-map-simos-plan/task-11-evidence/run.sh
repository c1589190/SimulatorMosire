#!/bin/bash
# Task 11 变异实验室（复用 Task 10 的装置，只换 LAB / TARGET / 变异动作 / surefire 摘要范围）。每轮：
#   干净世界（**从工作树 rsync 一份全新副本**，排除 target/ ⇒ 没有上一轮的 .class 能活到下轮）
#   → 逐文件比 md5 清单 + 文件数不得多不得少（"规范名之外的 .java"一律算违规）
#   → **改前**：先在干净世界里跑一遍（护栏必须是绿的）
#   → 变异（写进**目标类名**的文件）→ md5 自证落盘字节与原件**不同** → 断言只有**声明的**文件变了
#   → **改后**：跑同一套测试
#   → 断言 `grep -c "COMPILATION ERROR"` 为 0 → 断言 simos-map 的用例**真的跑过**
#     （"红了"必须是断言红，不是构建挂在用例之前）
#
# ★ 与 Task 4~6 的形态差别照旧：**变异在 /tmp 的副本上做，工作树一字不动**。
#   `refresh_repo` 排除 `.superpowers` ⇒ 本脚本往下写 .kept 不会破坏下一轮的干净世界。
#
# 用法：
#   bash run.sh init          # 建 md5 清单（务必在工作树干净、且**新文件已就位**时跑）
#   bash run.sh               # 跑全部轮次（ROUNDS="m10v-4" bash run.sh 只跑一轮）
set -u
ROOT=/root/SimulatorMosire
LAB=/tmp/m11lab
REPO=$LAB/repo
MANIFEST=$LAB/manifest.txt
HERE=$(cd "$(dirname "$0")" && pwd)
ROUNDS_DIR=$ROOT/.superpowers/sdd/2026-09-16-map-simos-plan/task-11-evidence/rounds

declare -A TARGET=(
  [m11v-1]="generate/RiverBuilder.java（补充-1：出边不看高度——判据从『严格更低』换成坐标序）"
  [m11v-2]="generate/RiverBuilder.java（补充-2 等价形态：只从随机挑的一个源走一条线，不走全网络）"
  [m11v-3]="generate/RiverBuilder.java（补充-3：所有河共用 new PathwayId(\"river\")）"
  [m11v-4]="generate/RiverBuilder.java（补充-4：分支不切开——分支点其余入边并进同一条线，线内出现度 3 分叉）"
  [m11v-5]="generate/RiverBuilder.java（补充-5：rngFor 换成常量种子，不看 seed）"
  [m11v-6]="generate/RiverBuilder.java（补充-6：空也走 Upsert（构造期拒空 ⇒ 应当场抛））"
  [m11v-7]="generate/RiverBuilder.java（补充-7：edges 的 key 用 groupId 而不是 PathwayId）"
)
ORDER="${ROUNDS:-m11v-1 m11v-2 m11v-3 m11v-4 m11v-5 m11v-6 m11v-7}"

refresh_repo() {
  rm -rf "$REPO"
  rsync -a --exclude 'target' --exclude '.git' --exclude '.serena' --exclude '.superpowers' \
        "$ROOT/" "$REPO/"
}

init() {
  mkdir -p "$LAB/logs" "$ROUNDS_DIR"
  (cd "$ROOT" && find simos-map/src simos-util/src -name '*.java' | sort | xargs md5sum) \
    > "$MANIFEST"
  cp -f "$HERE/mutate.py" "$LAB/mutate.py"
  echo "md5 清单已建：$(wc -l < "$MANIFEST") 个 .java"
}

# 干净世界：每个文件与清单逐字节一致，且副本里没有清单之外的 .java
verify_clean() {
  local ok=0
  (cd "$REPO" && md5sum -c --status "$MANIFEST") || { echo "!! 副本与清单不符"; ok=1; }
  local want have
  want=$(wc -l < "$MANIFEST")
  have=$(cd "$REPO" && find simos-map/src simos-util/src -name '*.java' | wc -l)
  if [ "$want" != "$have" ]; then
    echo "!! 文件数不符：清单 $want / 副本 $have（有规范名之外的 .java？）"
    ok=1
  fi
  return $ok
}

# 与清单不符的文件清单（用于断言"只有声明的文件变了"）
changed_files() {
  (cd "$REPO" && md5sum -c --quiet "$MANIFEST" 2>/dev/null) \
    | grep -v '^md5sum: ' | sed 's/: FAILED$//' | sort
}

run_tests() { # $1 = 日志文件
  (cd "$REPO" && timeout 1200 ./mvnw -pl simos-map -am test) > "$1" 2>&1
}

# 红点：测试名 + 行号（surefire 的 [ERROR] 摘要行）。
# ★ 过滤掉 Maven 的 reactor 续跑提示行 `[ERROR]   mvn <args> -rf :simos-map` —— 它也以
#   `[ERROR]   ` 开头且以字母起首，会混进"红点"清单（Task 9 首轮实测），故排除。
red_points() { # $1 = 日志文件
  grep -E "^\[ERROR\]   [A-Za-z]" "$1" | grep -vE "^\[ERROR\]   mvn <args>" | sed 's/^\[ERROR\]   //'
}

# 从一份 maven 日志里摘出"跑过哪些测试类 / 各模块汇总 / 红了哪些用例（含行号）"
digest() { # $1 = 日志文件
  grep -E "^\[INFO\] Running io\.mosire\.simos\.map" "$1" | sed 's/^\[INFO\] //'
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
  say "目标（变异体写进**目标类名**的文件）：${TARGET[$id]:-?}"
  say "★ 变异在 /tmp 副本上做：REPO=$REPO（工作树 $ROOT 一字不动）"
  refresh_repo
  if ! verify_clean; then
    say "!! 干净世界校验失败 —— 本轮作废"
    continue
  fi
  say "干净世界 OK（副本逐文件与 $ROOT 的 md5 清单一致，共 $(wc -l < "$MANIFEST") 个 .java，无多余文件）"

  # ── 改前（护栏必须绿）──────────────────────────────────────────────────────
  run_tests "$LAB/logs/$id.before.log"
  say "--- 改前（工作树 + 本任务新文件，未变异）---"
  say "改前 COMPILATION ERROR count = $(grep -c 'COMPILATION ERROR' "$LAB/logs/$id.before.log")"
  digest "$LAB/logs/$id.before.log" | tee -a "$KEPT"

  # ── 变异 + 自证 ───────────────────────────────────────────────────────────
  declared=$(python3 "$LAB/mutate.py" --files "$id")
  read -r -a declared_arr <<< "$declared"
  before_hashes=()
  for f in "${declared_arr[@]}"; do
    before_hashes+=("$(md5sum "$REPO/$f" | awk '{print $1}')")
  done
  python3 "$LAB/mutate.py" "$id" || { say "!! 变异脚本失败 —— 本轮作废"; continue; }
  say "声明要改的文件（$((${#declared_arr[@]}))个）："
  for i in "${!declared_arr[@]}"; do
    after=$(md5sum "$REPO/${declared_arr[$i]}" | awk '{print $1}')
    say "  ${declared_arr[$i]}  md5 原件=${before_hashes[$i]}  变异体=$after"
    if [ "${before_hashes[$i]}" == "$after" ]; then
      say "!! 该文件与原件字节相同 —— 本轮作废"
      continue 2
    fi
  done
  say "自证：每个声明的文件都与原件**字节不同** OK"

  changed=$(changed_files)
  expected=$(printf '%s\n' "${declared_arr[@]}" | sort)
  if [ "$changed" != "$expected" ]; then
    say "!! 与清单不符的文件集合与声明的不一致：[$changed] —— 本轮作废"
    continue
  fi
  say "自证：除声明的文件外无文件被改动（清单其余项全部一致）OK"

  # ── 改后 ──────────────────────────────────────────────────────────────────
  run_tests "$LAB/logs/$id.after.log"
  ce=$(grep -c "COMPILATION ERROR" "$LAB/logs/$id.after.log")
  say "--- 改后（变异体）---"
  say "COMPILATION ERROR count = $ce"
  if [ "$ce" != "0" ]; then
    say "!! 编译错误 —— 本轮作废（红了也不是断言红）"
    grep -B2 -A8 "COMPILATION ERROR" "$LAB/logs/$id.after.log" | head -50 | tee -a "$KEPT"
    digest "$LAB/logs/$id.after.log" >> "$KEPT"
    continue
  fi
  tr=$(grep -c "Running io.mosire.simos.map" "$LAB/logs/$id.after.log")
  say "simos-map 跑过的测试类数 = $tr"
  if [ "$tr" == "0" ]; then
    say "!! simos-map 的用例压根没跑（构建挂在用例之前：checkstyle/enforcer 等）—— 本轮作废，红的不是断言"
    grep -E "^\[ERROR\]" "$LAB/logs/$id.after.log" | head -10 | tee -a "$KEPT"
    continue
  fi
  digest "$LAB/logs/$id.after.log" | tee -a "$KEPT"
  say "--- 红点（测试名 + 行号；无输出 = 全绿）---"
  red_points "$LAB/logs/$id.after.log" | tee -a "$KEPT"
  say "--- surefire 明说（RiverBuilderTest：断言消息，去掉栈帧）---"
  for f in "$REPO"/simos-map/target/surefire-reports/*RiverBuilderTest.txt; do
    [ -e "$f" ] || continue
    grep -vE '^\s+at |^\s*$' "$f" | head -80 | tee -a "$KEPT"
  done
  say "--- 红/绿的判读（脚本不替你下结论）---"
  say "红点数 = $(red_points "$LAB/logs/$id.after.log" | wc -l)"
done

echo "=================== 工作树状态（本实验从未被变异触碰）==================="
(cd "$ROOT" && git status --short)
