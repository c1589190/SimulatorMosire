#!/bin/bash
# Task 8 变异实验室（与 Task 4~7 的装置同形，只换 TARGET / 镜像范围 / 变异动作）。每轮：
#   干净世界（**从工作树 rsync 一份全新副本**，排除 target/ ⇒ 没有上一轮的 .class 能活到下轮）
#   → 逐文件比 md5 清单 + 文件数不得多不得少（"规范名之外的 .java"一律算违规）
#   → **改前**：先在干净世界里跑一遍（护栏必须是绿的）
#   → 变异（写进**目标类名**的文件）→ md5 自证落盘字节与原件**不同** → 断言只有**声明的**文件变了
#   → **改后**：跑同一套测试
#   → 断言 `grep -c "COMPILATION ERROR"` 为 0 → 断言 simos-map 的用例**真的跑过**
#     （"红了"必须是断言红，不是构建挂在用例之前）
#
# ★ 与 Task 4~6 的唯一形态差别：**变异在 /tmp 的副本上做，工作树一字不动**（派单明文要求）。
#   故每轮开头是"重建副本"，而不是"恢复 pristine 镜像"；"干净世界"由 rsync 保证、由 md5 清单自证。
#   `refresh_repo` 排除 `.superpowers` ⇒ 本脚本往下写 .kept 不会破坏下一轮的干净世界。
#
# 用法：
#   bash /tmp/m8lab/run.sh init          # 建 md5 清单（务必在工作树干净、且**新文件已就位**时跑）
#   bash /tmp/m8lab/run.sh               # 跑全部轮次（ROUNDS="m8v-4" bash run.sh 只跑一轮）
set -u
ROOT=/home/cna/SimulatorMosire
LAB=/tmp/m8lab
REPO=$LAB/repo
MANIFEST=$LAB/manifest.txt
HERE=$(cd "$(dirname "$0")" && pwd)
ROUNDS_DIR=$ROOT/.superpowers/sdd/2026-09-16-map-simos-plan/task-8-evidence/rounds
GATE_LOG=$ROOT/.superpowers/sdd/2026-09-16-map-simos-plan/task-8-evidence/gate-clean-verify.txt

declare -A TARGET=(
  [m8v-1]="generate/GenerationSpec.java"
  [m8v-2]="generate/GenerationSpec.java"
  [m8v-3]="generate/GenerationSpec.java + GenerationSpecTest.java（构造点共适应）"
  [m8v-4]="generate/NoiseBands.java + GenerationSpec.java + GenerationSpecTest.java"
  [m8v-5]="generate/GenerationSpec.java（含删 import）"
  [m8v-6]="generate/NoiseBands.java"
  [m8v-7]="generate/NoiseBands.java + GenerationSpec.java + GenerationSpecTest.java"
  [m8v-8]="generate/GenerationSpec.java"
  [m8v-9]="generate/GenerationSpec.java"
  [m8v-10]="generate/GenerationSpec.java"
  [m8v-11]="generate/GenerationSpec.java（只加组件，构造点不动）"
  [m8v-12]="generate/FragmentParams.java"
)
ORDER="${ROUNDS:-m8v-1 m8v-2 m8v-3 m8v-4 m8v-5 m8v-6 m8v-7 m8v-8 m8v-9 m8v-10 m8v-11 m8v-12}"

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
  (cd "$REPO" && timeout 900 ./mvnw -pl simos-map -am test) > "$1" 2>&1
}

# 从一份 maven 日志里摘出"跑过哪些测试类 / 各模块汇总 / 红了哪些用例"
digest() { # $1 = 日志文件
  grep -E "^\[INFO\] Running io\.mosire\.simos\.map" "$1" | sed 's/^\[INFO\] //'
  grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$1"
  grep -E "BUILD (SUCCESS|FAILURE)" "$1" | tail -1
  grep -E "Tests run:" "$1" | grep -vE "Failures: 0, Errors: 0" | sed 's/^\[INFO\] //;s/^\[ERROR\] //'
  grep -E "^\[ERROR\]   [A-Z]" "$1" | sed 's/^\[ERROR\] //'
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
  say "--- 改前（HEAD c1a2607 的工作树 + 本任务新文件，未变异）---"
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
  say "--- surefire 明说（GenerationSpecTest：断言消息 expected/but was，或异常类型，去掉栈帧）---"
  for f in "$REPO"/simos-map/target/surefire-reports/*GenerationSpecTest.txt; do
    [ -e "$f" ] || continue
    grep -vE '^\s+at |^\s*$' "$f" | head -80 | tee -a "$KEPT"
  done
done

echo "=================== 工作树状态（本实验从未被变异触碰）==================="
(cd "$ROOT" && git status --short)
