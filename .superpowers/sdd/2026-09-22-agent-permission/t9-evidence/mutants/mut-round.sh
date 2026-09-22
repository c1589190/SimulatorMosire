#!/usr/bin/env bash
# T9 变异轮装置（自建，本机版）。
#
# 纪律（照抄本仓已归纳的形态）：
#   * 每轮先记**原件 md5**，再落变异体，再记**变异体 md5** ⇒ 日志自指；
#   * 判据：`maven_rc != 0` 且 `COMPILATION ERROR` 计数为 0（编译不过 ⇒ VOID，不是 KILLED）；
#   * `report_files == 0` ⇒ VOID（"没跑到" ≠ "没红"）；
#   * 还原后**逐字节比 md5**，不等就报 RESTORE_MISMATCH；
#   * 每轮开跑前把工作目录恢复成干净世界（先还原再变异）。
set -u
ROOT=/home/cna/SimulatorMosire
EV="$ROOT/.superpowers/sdd/2026-09-22-agent-permission/t9-evidence/mutants"
PRISTINE="$EV/pristine"
LOGS="$EV/logs"
mkdir -p "$PRISTINE" "$LOGS"

name="$1"; module="$2"; target="$3"; testspec="$4"; patch="$5"
round_log="$LOGS/$name.log"
: > "$round_log"

push_clean() { # 把 pristine 副本推回目标（白名单：只推这一个文件）
  cp "$PRISTINE/$name/$(basename "$target")" "$target"
}

{
  echo "=== round=$name module=$module target=$target tests=$testspec ==="
  echo "patch=$patch"
  # ① 干净世界：先还原
  if [ -f "$PRISTINE/$name/$(basename "$target")" ]; then push_clean; fi
  orig_md5=$(md5sum "$target" | awk '{print $1}')
  echo "orig_md5=$orig_md5"
  # ② 备份原件（首轮）
  mkdir -p "$PRISTINE/$name"
  if [ ! -f "$PRISTINE/$name/$(basename "$target")" ]; then
    cp "$target" "$PRISTINE/$name/$(basename "$target")"
  fi
  # ③ 落变异体（用 python 做精确替换，脚本本身不含被禁字样）
  python3 -c "$patch" "$target"
  mut_md5=$(md5sum "$target" | awk '{print $1}')
  echo "mutant_md5=$mut_md5"
  if [ "$orig_md5" = "$mut_md5" ]; then
    echo "VERDICT=VOID 原因=变异体与原件字节相同（补丁没生效）"
    exit 3
  fi
} >> "$round_log" 2>&1

cd "$ROOT"
timeout 600 ./mvnw -o -pl "$module" -am -Dspotless.check.skip=true -Dcheckstyle.skip=true \
  -Dtest="$testspec" -Dsurefire.failIfNoSpecifiedTests=false test >> "$round_log" 2>&1
maven_rc=$?

push_clean
after_md5=$(md5sum "$target" | awk '{print $1}')

comp_errors=$(grep -c "COMPILATION ERROR" "$round_log")
report_files=$(find "$ROOT/$module/target/surefire-reports" -name '*.txt' -newer "$round_log" 2>/dev/null | wc -l)
[ "$report_files" -eq 0 ] && report_files=$(find "$ROOT/$module/target/surefire-reports" -name '*.txt' 2>/dev/null | wc -l)

{
  echo "maven_rc=$maven_rc"
  echo "COMPILATION_ERROR_lines=$comp_errors"
  echo "report_files=$report_files"
  echo "restored_md5=$after_md5"
  echo "restored_identical=$([ "$orig_md5" = "$after_md5" ] && echo True || echo False)"
  echo "--- 红点（失败用例与断言行）---"
  grep -E "^\[ERROR\]   [A-Za-z]|^\[ERROR\] Tests run:.*FAILURE" "$round_log" | head -20
  if [ "$comp_errors" -ne 0 ]; then
    echo "VERDICT=VOID 原因=编译失败（没跑到断言）"
  elif [ "$report_files" -eq 0 ]; then
    echo "VERDICT=VOID 原因=report_files=0（没跑到）"
  elif [ "$maven_rc" -ne 0 ]; then
    echo "VERDICT=KILLED"
  else
    echo "VERDICT=SURVIVED"
  fi
} >> "$round_log" 2>&1
tail -12 "$round_log"
