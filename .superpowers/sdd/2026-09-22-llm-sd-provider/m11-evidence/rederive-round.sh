#!/usr/bin/env bash
# rederive-round.sh —— 裁定 42：把**既有**变异轮在**新字节**上重派生并重跑（目标文件被本任务改过 ⇒ 旧证据失效）。
# 两种模式：
#   patch: 用已归档的 (orig, mutant) 求 diff，再 `patch <新目标>` 施加（锚点须在新字节里仍在）。
#   py:    用 python 片段对新目标做与旧轮语义相同的改写。
# 每轮：备份 → 施加 → 断言字节确实不同 → 跑见证测试 → 断言红点命中 → 无编译错误 → 逐字节还原 + 复核 md5。
# usage: rederive-round.sh <name> <mode:patch|py> <target> <arg4> <arg5> <module-csv> <tests-csv> <pattern>
#   mode=patch: arg4=orig  arg5=mutant
#   mode=py:    arg4=pycode(用 sys.argv[1]=target)  arg5=-
set -u
name="$1"; mode="$2"; target="$3"; a4="$4"; a5="$5"; modules="$6"; tests="$7"; pat="$8"
here="$(cd "$(dirname "$0")" && pwd)"
EVID="$(cd "$here/.." && pwd)"
WT="$(git -C "$here" rev-parse --show-toplevel)"
cd "$WT" || exit 9
logs="$EVID/rederive/logs"; mkdir -p "$logs"
bak="$EVID/rederive/${name}.orig"
cp "$target" "$bak"
orig_md5=$(md5sum "$target" | cut -d' ' -f1)
if [ "$mode" = patch ]; then
  diff -u "$a4" "$a5" > "$logs/$name.patch"
  patch "$target" < "$logs/$name.patch" > "$logs/$name.patchout" 2>&1 || { echo "$name VOID patch-failed" | tee -a "$logs/$name.self"; cp "$bak" "$target"; exit 2; }
else
  python3 -c "$a4" "$target" > "$logs/$name.pyout" 2>&1 || { echo "$name VOID python-failed" | tee -a "$logs/$name.self"; cat "$logs/$name.pyout"; cp "$bak" "$target"; exit 2; }
fi
mut_md5=$(md5sum "$target" | cut -d' ' -f1)
if [ "$orig_md5" = "$mut_md5" ]; then echo "$name VOID no-byte-change" | tee -a "$logs/$name.self"; cp "$bak" "$target"; exit 2; fi
./mvnw -pl "$modules" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false test > "$logs/$name.log" 2>&1
rc=$?
cp "$bak" "$target"
rest_md5=$(md5sum "$target" | cut -d' ' -f1)
comp_err=$(grep -c "COMPILATION ERROR" "$logs/$name.log")
hits=$(grep -c "$pat" "$logs/$name.log")
{
  echo "== $name (rederive of prior mutant) =="
  echo "target=$target"
  echo "orig_md5=$orig_md5"
  echo "mutant_md5=$mut_md5"
  echo "restored_md5=$rest_md5"
  echo "compilation_error_count=$comp_err"
  echo "red_pattern='$pat' hits=$hits"
  echo "mvn_rc=$rc"
} >> "$logs/$name.self"
echo "RESULT $name rc=$rc comp_err=$comp_err red_hits=$hits restored==orig=$([ "$rest_md5" = "$orig_md5" ] && echo yes || echo NO)"
