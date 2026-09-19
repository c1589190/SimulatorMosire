#!/usr/bin/env bash
# 从一份**跑完的** `./mvnw clean verify` 日志里逐条**原文**取数（报告 §六 专用）。
# ★ 铁律：日志里没有 `BUILD SUCCESS`/`BUILD FAILURE` 行 ⇒ 拒绝工作（被杀/半截日志一个数都不许引用）。
set -u
LOG="${1:-}"
if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then echo "usage: extract-verify.sh <verify.log>"; exit 1; fi
# ★ 注意 Maven 的拼写：**失败那行也是 `[INFO] BUILD FAILURE`**，不是 `[ERROR] BUILD FAILURE`
#   （`[ERROR]` 只出现在下面的明细块里）。2026-09-20 实测：`final-clean-verify.log`（红轮）第 701 行是
#   `[INFO] BUILD FAILURE`，而我原先的正则只认 `[ERROR]` 版本 ⇒ 会把**跑完了的红轮**误判成"没跑完"并拒绝，
#   即"把存在伪装成不存在"。两种拼写都收，`[ERROR]` 那份留作兼容。
if ! grep -qE "^\[INFO\] BUILD SUCCESS$|^\[INFO\] BUILD FAILURE$|^\[ERROR\] BUILD FAILURE$" "$LOG"; then
  echo "REFUSE: $LOG 里没有 BUILD 行（未跑完/被杀）—— 一个数都不许引用"; exit 1
fi

echo "===== ① BUILD 行 ====="
grep -nE "^\[INFO\] BUILD SUCCESS$|^\[INFO\] BUILD FAILURE$|^\[ERROR\] BUILD FAILURE$" "$LOG"
echo "===== ② 退出码行（若有）====="
grep -nE "^clean_verify_rc=" "$LOG" || echo "(日志里没有 rc 行——rc 由调用方自己记)"
echo "===== ③ 逐模块 surefire 汇总（模块名 + Tests run + Packaging）====="
awk '
  /^\[INFO\] Building / { mod=$0; sub(/^\[INFO\] Building /,"",mod) }
  /^\[INFO\] Tests run: / { print mod " | " $0 }
  /^\[ERROR\] Tests run: / { print mod " | " $0 }
' "$LOG"
echo "===== ④ 每个模块的 Tests run 行数（应与模块数相等）====="
grep -cE "^\[INFO\] Tests run: |^\[ERROR\] Tests run: " "$LOG"
echo "===== ⑤ SpotBugs：BugInstance size is 0 次数 ====="
grep -cE "BugInstance size is 0" "$LOG"
grep -nE "BugInstance size is 0" "$LOG"
echo "===== ⑥ \`[ERROR]\` 行数（逐行原文，便于人看）====="
grep -cE "^\[ERROR\]" "$LOG"
grep -nE "^\[ERROR\]" "$LOG" | head -40
echo "===== ⑦ Spotless 的 keeping N files clean（逐行原文）====="
grep -nE "Spotless\.Java is keeping" "$LOG"
echo "===== ⑧ 前端门禁行 ====="
grep -nE "\[frontend-gate\]" "$LOG"
echo "===== ⑨ Reactor Summary ====="
grep -nE "^\[INFO\] (simos-[a-z]+|SimulatorMosire|Building Simulator)" "$LOG" | tail -20
echo "===== ⑩ 模块数（Packaging 行）====="
grep -cE "^\[INFO\] Packaging " "$LOG" || true
