#!/usr/bin/env bash
# mut-round.sh —— 变异轮装置（m11 / llm-sd-provider）。
# 纪律（形态 1）：按目标类名原地改；先证"字节确实变了"；强断言零 COMPILATION ERROR；跑完逐字节还原并复核 md5。
# usage: mut-round.sh <name> <module-csv> <tests-csv> <target-file> <python-code>
#   python-code 用 sys.argv[1] 指目标文件，做原地改写。
set -u
name="$1"; module="$2"; tests="$3"; file="$4"; pycode="$5"
here="$(cd "$(dirname "$0")" && pwd)"
EVID="$(cd "$here/.." && pwd)"
WT="$(git -C "$here" rev-parse --show-toplevel)"
cd "$WT" || exit 9
logs="$EVID/mutants/logs"; mkdir -p "$logs"
bak="$EVID/mutants/${name}.orig"
cp "$file" "$bak"
orig_md5=$(md5sum "$file" | cut -d' ' -f1)
python3 -c "$pycode" "$file" || { echo "$name VOID python-failed" | tee -a "$logs/$name.self"; cp "$bak" "$file"; exit 2; }
mut_md5=$(md5sum "$file" | cut -d' ' -f1)
if [ "$orig_md5" = "$mut_md5" ]; then
  echo "$name VOID mutation-did-not-change-bytes orig=$orig_md5" | tee -a "$logs/$name.self"
  cp "$bak" "$file"; exit 2
fi
./mvnw -pl "$module" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false test > "$logs/$name.log" 2>&1
rc=$?
cp "$bak" "$file"
rest_md5=$(md5sum "$file" | cut -d' ' -f1)
comp_err=$(grep -c "COMPILATION ERROR" "$logs/$name.log")
{
  echo "== $name =="
  echo "file=$file"
  echo "orig_md5=$orig_md5"
  echo "mutant_md5=$mut_md5"
  echo "restored_md5=$rest_md5"
  echo "compilation_error_count=$comp_err"
  echo "mvn_rc=$rc"
} >> "$logs/$name.self"
echo "RESULT $name rc=$rc comp_err=$comp_err restored==orig=$([ "$rest_md5" = "$orig_md5" ] && echo yes || echo NO)"
