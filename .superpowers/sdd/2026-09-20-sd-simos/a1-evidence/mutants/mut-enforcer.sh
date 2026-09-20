#!/usr/bin/env bash
# A1 enforcer 变异装置（构建期门禁；九道门禁的最小可用版）
# ★ 执行期取代说明（记台账 + 报告）：
#   计划 m2 写的是"把 io.mosire:simos-core 从 simos-sd 的 excludes 删掉、并给 sd 加 core 依赖 ⇒ 期望 sd enforcer 报错"。
#   这不可能报错——**删掉 exclude 正是解除禁令**（实测 plan-as-written 轮 rc=0，日志见 m2-plan-as-written/）。
#   ⇒ 本装置把 m2 改成**保留 exclude、给 sd 加 core 依赖**（这才检验"sd 不得依赖 core"这条守卫真的守着）。
# 纪律：干净世界 / md5 自证字节不同 / 白名单推成目标文件名 / cp 逐字节还原（绝不 git checkout --）/
#      日志自指 / 还原后 md5 断言相等 / 拒收与接收两侧都要有真样本 / 命中 0 先怀疑正则（首轮 m1 即因
#      grep 写成小写 bannedDependencies 而假判"未触发"）。
set -u
WT="/home/cna/SimulatorMosire/.claude/worktrees/sda1a2"
EV="$WT/.superpowers/sdd/2026-09-20-sd-simos/a1-evidence"
MUT="$EV/mutants"
LOG="$EV/enforcer-effective.log"
mkdir -p "$MUT/orig" "$MUT/m1" "$MUT/m2"
: > "$LOG"

md5() { md5sum "$1" | awk '{print $1}'; }

# ---------- 变异体生成 ----------
# m1: 往 simos-core/pom.xml 的 <dependencies> 里插一条 simos-sd（main scope）
python3 - "$WT/simos-core/pom.xml" "$MUT/m1/simos-core.pom.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
needle = "  <dependencies>\n"
assert text.count(needle) == 1, text.count(needle)
dep = ("  <dependencies>\n"
       "    <dependency>\n"
       "      <groupId>io.mosire</groupId><artifactId>simos-sd</artifactId>\n"
       "    </dependency>\n")
open(dst, "w", encoding="utf-8").write(text.replace(needle, dep, 1))
PY

# m2（取代写法）: 保留 simos-sd 的 exclude，只加 core 依赖 —— 这才检验 sd enforcer 真的禁 core
python3 - "$WT/simos-sd/pom.xml" "$MUT/m2/simos-sd.pom.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
assert "<exclude>io.mosire:simos-core</exclude>" in text
needle = "  <dependencies>\n"
assert text.count(needle) == 1
dep = ("  <dependencies>\n"
       "    <dependency>\n"
       "      <groupId>io.mosire</groupId><artifactId>simos-core</artifactId>\n"
       "    </dependency>\n")
open(dst, "w", encoding="utf-8").write(text.replace(needle, dep, 1))
PY

# ---------- 干净世界基线 ----------
cp "$WT/simos-core/pom.xml" "$MUT/orig/simos-core.pom.xml"
cp "$WT/simos-sd/pom.xml"   "$MUT/orig/simos-sd.pom.xml"
ORIG_CORE=$(md5 "$MUT/orig/simos-core.pom.xml")
ORIG_SD=$(md5 "$MUT/orig/simos-sd.pom.xml")
echo "baseline orig_core_md5=$ORIG_CORE" | tee -a "$LOG"
echo "baseline orig_sd_md5=$ORIG_SD"     | tee -a "$LOG"

# 接收侧真样本：干净树的 validate 必须绿、且 enforcer 明确 passed
( cd "$WT" && ./mvnw -pl simos-core,simos-sd validate ) > "$MUT/clean-validate.log" 2>&1
CLEAN_RC=$?
CLEAN_PASS=$(grep -c "BannedDependencies passed" "$MUT/clean-validate.log")
echo "clean_validate rc=$CLEAN_RC BannedDependencies_passed=$CLEAN_PASS" | tee -a "$LOG"

run_round() {
  local name="$1" target="$2" mutant="$3" orig_md5="$4" mvn_args="$5"
  local mutant_md5; mutant_md5=$(md5 "$mutant")
  if [ "$mutant_md5" = "$orig_md5" ]; then
    echo "ROUND $name ABORT: mutant bytes identical to orig" | tee -a "$LOG"; return 2
  fi
  cp "$mutant" "$target"
  local pushed_md5; pushed_md5=$(md5 "$target")
  if [ "$pushed_md5" != "$mutant_md5" ]; then
    echo "ROUND $name ABORT: push mismatch pushed=$pushed_md5 mutant=$mutant_md5" | tee -a "$LOG"; return 2
  fi
  local rlog="$MUT/$name/run.log"
  ( cd "$WT" && ./mvnw -pl $mvn_args validate ) > "$rlog" 2>&1
  local rc=$?
  if [ "$name" = "m1" ]; then cp "$MUT/orig/simos-core.pom.xml" "$WT/simos-core/pom.xml";
  else cp "$MUT/orig/simos-sd.pom.xml" "$WT/simos-sd/pom.xml"; fi
  local restored_md5; restored_md5=$(md5 "$target")
  local hit; hit=$(grep -ci "BannedDependencies failed" "$rlog")
  local banned; banned=$(grep -c "banned via the exclude/include list" "$rlog")
  local compile_err; compile_err=$(grep -c "COMPILATION ERROR" "$rlog")
  {
    echo "ROUND $name mvn='./mvnw -pl $mvn_args validate' rc=$rc"
    echo "ROUND $name orig_md5=$orig_md5 mutant_md5=$mutant_md5 pushed_md5=$pushed_md5 restored_md5=$restored_md5"
    echo "ROUND $name BannedDependencies_failed=$hit banned_lines=$banned COMPILATION_ERROR=$compile_err"
  } | tee -a "$LOG"
  if [ "$restored_md5" != "$orig_md5" ]; then
    echo "ROUND $name ABORT: restore mismatch" | tee -a "$LOG"; return 3
  fi
  if [ "$rc" -eq 0 ]; then
    echo "ROUND $name FAIL: enforcer did not fire (rc=0)" | tee -a "$LOG"; return 1
  fi
  if [ "$hit" -eq 0 ]; then
    echo "ROUND $name FAIL: red is not the enforcer line" | tee -a "$LOG"; return 1
  fi
  echo "ROUND $name OK: enforcer fired, red line = BannedDependencies failed" | tee -a "$LOG"
}

run_round m1 "$WT/simos-core/pom.xml" "$MUT/m1/simos-core.pom.xml" "$ORIG_CORE" "simos-core,simos-sd"
run_round m2 "$WT/simos-sd/pom.xml"   "$MUT/m2/simos-sd.pom.xml"   "$ORIG_SD"   "simos-core,simos-sd"

echo "== post-restore md5 check ==" | tee -a "$LOG"
echo "post_core_md5=$(md5 "$WT/simos-core/pom.xml")" | tee -a "$LOG"
echo "post_sd_md5=$(md5 "$WT/simos-sd/pom.xml")"     | tee -a "$LOG"
