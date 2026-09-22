#!/usr/bin/env bash
# 变异装置（缺陷 #3：LLM 工具名线格式转义）——每轮：留档基线字节 → 改一处 → 编译+跑靶用例 → 还原 → 自证。
#
# ★ 自指纪律（本仓 M4 Task 12 的形态 6）：把"这一轮跑的是哪份字节（md5）"**追加进日志本身**；
#   本装置的产物（日志）自带 baseline/mutant/restored 三个 md5 与 restored_identical、compilation_errors、report_files。
# ★ report_files=0 ⇒ VOID（没跑到断言就不是"红"）。
set -u

ROOT=/home/cna/SimulatorMosire
cd "$ROOT" || exit 1

EV=.superpowers/sdd/2026-09-22-agent-permission/defect3-toolnames-evidence/mutants
LOGS="$EV/logs"
PRISTINE="$EV/pristine"
mkdir -p "$LOGS" "$PRISTINE"

NAMES=simos-app/src/main/java/io/mosire/simos/app/llm/LlmToolNames.java
RUNNER=simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java
TESTS='LlmToolNamesTest,DecisionAgentRunnerTest,RunDecisionEndToEndTest'
CLASSES='LlmToolNamesTest|DecisionAgentRunnerTest|RunDecisionEndToEndTest'

# 0) 干净世界：先按**唯一名**留档两份原件的字节（后续每轮都从这里还原）
hr=0
for f in "$NAMES" "$RUNNER"; do
  base=$(basename "$f")
  if [ ! -f "$PRISTINE/$base" ]; then cp -p "$f" "$PRISTINE/$base"; fi
  # 首轮：若留档与工作树不一致，说明留下来的不是干净世界 ⇒ 当场作废
  if ! cmp -s "$PRISTINE/$base" "$f"; then
    echo "VOID: $base 的留档与工作树不一致（装置自己带状态了）" | tee -a "$LOGS/round-0-preflight.log"
    exit 2
  fi
done

mutate() {  # $1=文件  $2=旧串  $3=新串
  python3 - "$1" "$2" "$3" <<'PY'
import sys, pathlib
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path(path)
s = p.read_text()
n = s.count(old)
if n != 1:
    print(f"VOID: 靶点出现 {n} 次（要求恰好 1 次）")
    sys.exit(3)
p.write_text(s.replace(old, new))
PY
}

run_round() {  # $1=轮名  $2=靶文件  $3=旧串  $4=新串
  local round="$1" target="$2" old="$3" new="$4"
  local log="$LOGS/$round.log"
  local base; base=$(basename "$target")
  : > "$log"

  local baseline_md5; baseline_md5=$(md5sum "$target" | cut -d' ' -f1)
  echo "=== round=$round file=$target" >> "$log"
  echo "baseline_md5=$baseline_md5" >> "$log"

  if ! mutate "$target" "$old" "$new" >> "$log" 2>&1; then
    echo "VOID: 变异未施加" >> "$log"
    cp -p "$PRISTINE/$base" "$target"
    return 4
  fi
  local mutant_md5; mutant_md5=$(md5sum "$target" | cut -d' ' -f1)
  echo "mutant_md5=$mutant_md5" >> "$log"
  if [ "$mutant_md5" = "$baseline_md5" ]; then
    echo "VOID: 变异体与原件逐字节相同（等于没改）" >> "$log"
    cp -p "$PRISTINE/$base" "$target"
    return 5
  fi

  local started; started=$(date +%s)
  timeout 900 ./mvnw -pl simos-app -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test \
    > "$EV/raw-$round.out" 2>&1
  local rc=$?
  local compilation_errors; compilation_errors=$(grep -c "COMPILATION ERROR" "$EV/raw-$round.out")
  echo "maven_rc=$rc" >> "$log"
  echo "compilation_errors=$compilation_errors" >> "$log"

  # 只认**本轮**写出的报告（mtime 落在本轮内）——旧报告会造出假红/假绿
  local report_files=0 reds=""
  while IFS= read -r rep; do
    [ -z "$rep" ] && continue
    if [ "$(stat -c %Y "$rep")" -ge "$started" ]; then
      report_files=$((report_files + 1))
      reds="$reds$(grep -h -E '^\[ERROR\]   [A-Za-z]+Test\.' "$EV/raw-$round.out" | sed 's/^\[ERROR\]   //' | cut -c1-140 | sort -u)"$'\n'
    fi
  done < <(ls simos-app/target/surefire-reports/*.txt 2>/dev/null | grep -E "$CLASSES" || true)
  echo "report_files=$report_files" >> "$log"
  echo "--- 红点（本轮断言名，去重）---" >> "$log"
  printf '%s' "$reds" | sed '/^$/d' >> "$log"
  echo "--- 本轮 Tests run 行 ---" >> "$log"
  grep -h -E "Tests run:.*-- in (io\.mosire\.simos\.app\.(llm\.LlmToolNamesTest|decision\.(DecisionAgentRunnerTest|RunDecisionEndToEndTest)))" "$EV/raw-$round.out" >> "$log"

  # 还原（逐字节 cp）并自证
  cp -p "$PRISTINE/$base" "$target"
  local restored_md5; restored_md5=$(md5sum "$target" | cut -d' ' -f1)
  local restored_identical=false
  [ "$restored_md5" = "$baseline_md5" ] && restored_identical=true
  echo "restored_md5=$restored_md5" >> "$log"
  echo "restored_identical=$restored_identical" >> "$log"
  if [ "$report_files" -eq 0 ]; then
    echo "verdict=VOID（report_files=0：没跑到断言）" >> "$log"
    return 6
  fi
  if [ "$compilation_errors" -ne 0 ]; then
    echo "verdict=VOID（编译错误 ≠ 判据红）" >> "$log"
    return 7
  fi
  if grep -q "^\[ERROR\]   [A-Za-z]*Test\." "$EV/raw-$round.out"; then
    echo "verdict=KILLED" >> "$log"
  else
    echo "verdict=SURVIVED（本轮装置没能杀掉它）" >> "$log"
  fi
  return 0
}

# m1 不转义：wireNameOf 原样返回（每个字符都不换）
run_round "m1-no-escape" "$NAMES" \
  'out.append(isWireSafe(c) ? c : ESCAPE_CHAR);' \
  'out.append(c);'
echo "m1 rc=$? (0=KILLED 4/5/6/7=VOID)" | tee -a "$LOGS/summary.txt"

# m2 转了但不映射回来：执行时直接用模型给的名字
run_round "m2-no-mapping-back" "$RUNNER" \
  'String toolName = toolNames.realNameOf(call.name()).orElse(call.name());' \
  'String toolName = call.name();'
echo "m2 rc=$? (0=KILLED 4/5/6/7=VOID)" | tee -a "$LOGS/summary.txt"

# m3 碰撞静默挑一个：不抛，直接采纳第一条
run_round "m3-collision-silent" "$NAMES" \
  '      String clash = wireToReal.putIfAbsent(wire, real);
      if (clash != null && !clash.equals(real)) {
        throw new IllegalStateException(
            "工具名转义碰撞（装配故障）——两个真实工具名落到同一个线名 "
                + wire
                + ": "
                + clash
                + " 与 "
                + real
                + "；模型说这个线名时我们无从知道它指哪一条，故不静默挑一个");
      }' \
  '      wireToReal.putIfAbsent(wire, real);'
echo "m3 rc=$? (0=KILLED 4/5/6/7=VOID)" | tee -a "$LOGS/summary.txt"

# m4 身份消息说真名（不回线格式）
run_round "m4-identity-uses-real-names" "$RUNNER" \
  '    String catalog = LlmToolNames.wireNameOf(CatalogTool.NAME);
    String branches = LlmToolNames.wireNameOf(BranchListTool.NAME);' \
  '    String catalog = CatalogTool.NAME;
    String branches = BranchListTool.NAME;'
echo "m4 rc=$? (0=KILLED 4/5/6/7=VOID)" | tee -a "$LOGS/summary.txt"

# m5 账里记线名（而不是平台真实名）
run_round "m5-trace-records-wire-name" "$RUNNER" \
  '        new ToolInvocation(call.id(), toolName, result.success(), result.code(), feedback));' \
  '        new ToolInvocation(call.id(), call.name(), result.success(), result.code(), feedback));'
echo "m5 rc=$? (0=KILLED 4/5/6/7=VOID)" | tee -a "$LOGS/summary.txt"

# 收尾自证：两份原件都回到留档字节
for f in "$NAMES" "$RUNNER"; do
  base=$(basename "$f")
  if cmp -s "$PRISTINE/$base" "$f"; then
    echo "final: $base IDENTICAL to pristine ($(md5sum "$f" | cut -d' ' -f1))" | tee -a "$LOGS/summary.txt"
  else
    echo "final: $base DIFFERS from pristine —— 装置没还原干净！" | tee -a "$LOGS/summary.txt"
  fi
done
