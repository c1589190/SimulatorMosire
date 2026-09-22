#!/usr/bin/env bash
# 变异轮装置（T5~T8）——自指：每轮把 baseline/mutant/restored 的 md5 与红点追加进日志本身。
# 纪律（本仓）：① 每轮先恢复干净世界（按 md5 核）；② 跑前删本轮 surefire 报告；③ 报告 mtime 必须属本轮；
#                ④ 强制断言编译错误为 0（否则该轮 VOID，不算"红"）；⑤ 还原后核 md5 逐字节相同。
set -u
ROOT=/home/cna/SimulatorMosire
EV=/tmp/t5t8-evidence
BACKUP=$EV/backup
PATCH=$EV/mutants/patches
LOGDIR=$EV/logs

round=$1
tests=$2
log="$LOGDIR/${round}-${tests//,/_}.log"
: > "$log"

restore_all() {
  (cd "$BACKUP" && find . -name '*.java' -print0 | while IFS= read -r -d '' f; do
      cp "$BACKUP/${f#./}" "$ROOT/${f#./}"
   done)
}

md5_of() { md5sum "$1" | awk '{print $1}'; }

manifest_ok() {  # 备份与工作树逐文件 md5 相等？
  local ok=1
  while read -r sum path; do
    rel=${path#./}
    now=$(md5_of "$ROOT/$rel")
    if [ "$sum" != "$now" ]; then ok=0; echo "MISMATCH $rel expected=$sum now=$now" >> "$log"; fi
  done < "$EV/baseline-manifest.txt"
  echo "$ok"
}

# ── ① 恢复干净世界并自证 ────────────────────────────────────────────────────────
restore_all
clean_ok=$(manifest_ok)
if [ "$clean_ok" != "1" ]; then echo "ROUND=VOID reason=clean-world-mismatch" >> "$log"; cat "$log"; exit 2; fi

# ── ② 打补丁 ──────────────────────────────────────────────────────────────────
python3 "$PATCH/${round}.py" >> "$log" 2>&1 || { echo "ROUND=VOID reason=patch-failed" >> "$log"; cat "$log"; exit 2; }

# ── ③ 记录这一轮跑的是哪份字节（自指）─────────────────────────────────────────
declare -A base_md5 mut_md5
changed=0
while read -r sum path; do
  rel=${path#./}
  b=$(md5_of "$ROOT/$rel")
  base_md5[$rel]=$sum
  mut_md5[$rel]=$b
  [ "$sum" != "$b" ] && changed=1
done < "$EV/baseline-manifest.txt"
if [ "$changed" != "1" ]; then echo "ROUND=VOID reason=patch-changed-nothing" >> "$log"; cat "$log"; exit 2; fi

# ── ④ 删本轮报告并跑测试 ───────────────────────────────────────────────────────
rm -f "$ROOT"/simos-app/target/surefire-reports/*.txt "$ROOT"/simos-app/target/surefire-reports/*.xml
started=$(date +%s)
timeout 900 "$ROOT/mvnw" -f "$ROOT/pom.xml" -pl simos-app -am \
  -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false test > "$log.mvn" 2>&1
rc=$?
ended=$(date +%s)

# ── ⑤ 编译错误强制为 0（"没跑到" ≠ "没红"）─────────────────────────────────────
comp_errs=$(grep -c "COMPILATION ERROR" "$log.mvn" || true)
no_symbol=$(grep -c "cannot find symbol" "$log.mvn" || true)

# ── ⑥ 读红点（报告 mtime 必须落在本轮窗口内）───────────────────────────────────
reds=""
stale=0
for f in "$ROOT"/simos-app/target/surefire-reports/*.txt; do
  [ -e "$f" ] || continue
  m=$(stat -c %Y "$f")
  if [ "$m" -lt "$started" ] || [ "$m" -gt "$ended" ]; then stale=$((stale+1)); continue; fi
  if grep -q "FAILURE\!" "$f"; then
    while IFS= read -r line; do reds="$reds $line"; done < <(grep -oE "Tests run:.*<<< FAILURE!" "$f" | head -1)
    while IFS= read -r line; do reds="$reds $line"; done < <(grep -oE "^io\.mosire[^ ]*\.[A-Za-z0-9_]+\.[a-zA-Z0-9_]+" "$f" | sort -u)
  fi
done
report_files=$(ls "$ROOT"/simos-app/target/surefire-reports/*.txt 2>/dev/null | wc -l)
# ★ ⑥-b 装置自证（2026-09-22 实测补上）：门禁可能在 surefire **之前**就红（Spotless/Checkstyle/编译），
#   那时 report_files=0 ⇒ 一条断言也没跑。若把它读成"存活"，就是把"没跑到"伪装成"没红"（本仓最贵的形态之一）。
gate_before_tests=$( [ "$report_files" = "0" ] && echo 1 || echo 0 )

# ── ⑦ 还原 + 自证逐字节相同 ────────────────────────────────────────────────────
restore_all
restored_ok=$(manifest_ok)

{
  echo "----------------------------------------------------------------"
  echo "round=$round tests=$tests"
  echo "started=$started ended=$ended rc=$rc"
  echo "compilation_errors=$comp_errs cannot_find_symbol=$no_symbol"
  echo "report_files=$report_files stale_reports_ignored=$stale"
  for rel in "${!base_md5[@]}"; do
    echo "file=$rel baseline_md5=${base_md5[$rel]} mutant_md5=${mut_md5[$rel]} restored_md5=$(md5_of "$ROOT/$rel")"
  done
  echo "restored_identical=$restored_ok"
  echo "red_points=${reds:-<none>}"
  if [ "$comp_errs" != "0" ] || [ "$no_symbol" != "0" ]; then
    echo "verdict=VOID(compile)"
  elif [ "$gate_before_tests" = "1" ]; then
    echo "verdict=VOID(no-surefire-reports: 门禁在测试前就红了)"
  elif [ "$restored_ok" != "1" ]; then
    echo "verdict=VOID(restore)"
  elif [ -z "$reds" ]; then
    echo "verdict=SURVIVED"
  else
    echo "verdict=KILLED"
  fi
} >> "$log"
cat "$log"
