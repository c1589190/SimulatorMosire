#!/usr/bin/env bash
# 证明②（控制器独立撰写，方法与证明①**不同**）：
#   证明① 是**文本级**——剥掉注释再比剩余字节（怕剥注释的脚本自己有 bug）。
#   证明② 是**字节码级**——两份源码各自 javac 一遍，比 .class 的 md5。
#   注释**根本不进字节码** ⇒ 若两个 .class 逐字节相同，则"只动注释"是**编译器的结论**，不是我的结论。
#
# ★ 干净世界纪律：每轮开跑前**先删掉那两个 .class**，逼 javac 真的重编
#   （否则读到的是上一轮留下的陈旧 class —— 本仓踩过，M2 Task 1 的假发现）。
set -uo pipefail

SRC_SS=simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java
SRC_SH=simos-app/src/main/java/io/mosire/simos/app/Shell.java
CLS_SS=simos-app/target/classes/io/mosire/simos/app/tools/SimosToolSource.class
CLS_SH=simos-app/target/classes/io/mosire/simos/app/Shell.class
CD=.superpowers/sdd/2026-09-22-tool-surface/m3-evidence/comment-drift

round() {
  local label="$1"
  echo "=== 第 ${label} 轮 ==="
  echo "  源文件 md5（本轮编译的输入）："
  md5sum "$SRC_SS" "$SRC_SH" | sed 's/^/    /'
  rm -f "$CLS_SS" "$CLS_SH"
  # 断言：删干净了
  if [ -e "$CLS_SS" ] || [ -e "$CLS_SH" ]; then
    echo "  VOID: .class 删不掉 —— 本轮作废"; return 1
  fi
  ./mvnw -q -pl simos-app -am compile > "$CD/compile-${label}.log" 2>&1
  local rc=$?
  echo "  mvn compile rc=$rc"
  if [ "$rc" -ne 0 ]; then
    echo "  VOID: 编译失败 —— 本轮作废（尾部日志）"; tail -20 "$CD/compile-${label}.log"; return 1
  fi
  # 断言：.class 真的被重新生成了（非空且存在）
  for f in "$CLS_SS" "$CLS_SH"; do
    if [ ! -s "$f" ]; then echo "  VOID: $f 不存在或为空 —— javac 没跑（不拿旧 class 当证据）"; return 1; fi
  done
  md5sum "$CLS_SS" "$CLS_SH" | sed 's/^/    /'
  md5sum "$CLS_SS" "$CLS_SH" > "$CD/class-${label}.md5"
  echo "  字节数：$(stat -c%s "$CLS_SS") / $(stat -c%s "$CLS_SH")"
  return 0
}

echo "cwd = $PWD"
# ---- 轮 A：改动前的字节 ----
cp "$CD/pre-SimosToolSource.java" "$SRC_SS"
cp "$CD/pre-Shell.java"          "$SRC_SH"
round A || exit 1

# ---- 轮 B：改动后的字节 ----
# 从 HEAD 取不回"改动后"（尚未提交），故用 unpin-counts.py 在**干净的 pre 副本**上重放
cp "$CD/pre-SimosToolSource.java" "$SRC_SS"
cp "$CD/pre-Shell.java"          "$SRC_SH"
python3 "$CD/unpin-counts.py" "$PWD" > "$CD/replay.log" 2>&1 || { echo "VOID: 重放脚本失败"; cat "$CD/replay.log"; exit 1; }
cat "$CD/replay.log" | sed 's/^/  /'
round B || exit 1

echo
echo "=== 结论 ==="
cat "$CD/class-A.md5" "$CD/class-B.md5"
if diff <(awk '{print $1}' "$CD/class-A.md5") <(awk '{print $1}' "$CD/class-B.md5") > /dev/null; then
  echo "VERDICT: BYTECODE-IDENTICAL（两份源码各自编译出的 .class 逐字节相同 ⇒ 只动注释）"
  rc=0
else
  echo "VERDICT: BYTECODE-DIFFERS（字节码变了 ⇒ 改动不只是注释）"
  rc=1
fi
echo
echo "=== 收尾自检：工作树现在必须是「改动后」==="
md5sum "$SRC_SS" "$SRC_SH"
exit $rc
