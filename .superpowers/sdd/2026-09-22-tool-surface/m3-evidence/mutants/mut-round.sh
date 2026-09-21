#!/usr/bin/env bash
# M3 变异轮装置（纪律见 CLAUDE.md「护栏必须自证」形态 1）：
#   ①按**白名单**把变异体推成**目标类名**（不是按变异体文件名拷入 —— 那会让「红」变成编译错误）；
#   ②每轮先清掉 write/ 下规范名之外的 .java，并把基线字节逐字节拷回（干净世界 + md5 比对）；
#   ③强制断言 `COMPILATION ERROR` 计数为 0，不为 0 即当场作废这一轮；
#   ④每份产物**自指**：把「这一轮跑的是哪份字节（md5）」追加进日志本身。
#
# 用法: mut-round.sh <轮次标签> <surefire 选择器> <尝试次数>
set -uo pipefail

LABEL="$1"
SELECTOR="$2"
ATTEMPT="${3:-1}"

WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV="$WT/.superpowers/sdd/2026-09-22-tool-surface/m3-evidence"
MUT="$EV/mutants"
PRISTINE="$MUT/pristine"
BASELINE="$MUT/baseline.md5"
WHITELIST="$MUT/write-whitelist.txt"
WRITE_DIR="$WT/simos-app/src/main/java/io/mosire/simos/app/tools/write"
LOG="$MUT/logs/$LABEL.attempt$ATTEMPT.log"

md5of() { md5sum "$1" | cut -d' ' -f1; }

# ── 干净世界：逐字节拷回基线字节 + 清掉规范名之外的 .java ─────────────────
restore_world() {
  while read -r base path; do
    cp "$PRISTINE/$path" "$WT/$path" || { echo "还原失败: $path"; return 1; }
  done < "$BASELINE"
  local f base
  for f in "$WRITE_DIR"/*.java; do
    base=$(basename "$f")
    grep -qxF "$base" "$WHITELIST" || { echo "清掉规范名之外的 .java: $base"; rm -f "$f"; }
  done
  # 断言全量 md5 == 基线（不等即「世界不干净」，当场作废）
  local bad=0
  while read -r base path; do
    [ "$(md5of "$WT/$path")" = "$base" ] || { echo "世界不干净: $path"; bad=1; }
  done < "$BASELINE"
  return $bad
}

{
  echo "════════════════════════════════════════════════════════════════════"
  echo "M3 变异轮 label=$LABEL attempt=$ATTEMPT selector=$SELECTOR"
  echo "起跑时刻: $(date '+%F %T')"
  echo "──────────────────────── 干净世界 ────────────────────────"
  if ! restore_world; then
    echo "VOID: 基线还原失败（世界不干净）—— 本轮作废"
    exit 9
  fi
  echo "干净世界 OK：16 个基线文件 md5 全部与 baseline.md5 一致"
  echo "baseline.md5 自记 md5 = $(md5of "$BASELINE")"

  echo "──────────────────────── 推送变异体 ────────────────────────"
  echo "变异体脚本: $MUT/$LABEL.py  (md5=$(md5of "$MUT/$LABEL.py"))"
  python3 "$MUT/$LABEL.py" "$WT" || { echo "VOID: 变异体推送失败"; restore_world; exit 9; }

  echo "── 推送后逐文件比对（必须与基线**不同**，否则等于没推）──"
  changed=0
  while read -r base path; do
    now=$(md5of "$WT/$path")
    if [ "$now" != "$base" ]; then
      echo "  CHANGED  $path"
      echo "           base=$base"
      echo "           now =$now"
      changed=$((changed + 1))
    fi
  done < "$BASELINE"
  for f in "$WRITE_DIR"/*.java; do
    base=$(basename "$f")
    if ! grep -qxF "$base" "$WHITELIST"; then
      echo "  ADDED    tools/write/$base (md5=$(md5of "$f"))"
      changed=$((changed + 1))
    fi
  done
  echo "  ⇒ 共 $changed 处字节变化"
  if [ "$changed" -eq 0 ]; then
    echo "VOID: 变异体没有改变任何一个字节 —— 本轮作废（三向全绿的典型形态）"
    restore_world
    exit 9
  fi

  echo "──────────────────────── Maven ────────────────────────"
  cd "$WT" || exit 9
  ./mvnw -pl simos-app -am -Dtest="$SELECTOR" -Dsurefire.failIfNoSpecifiedTests=false test
  rc=$?
  echo "──────────────────────── Maven rc=$rc ────────────────────────"

  comp=$(grep -c "COMPILATION ERROR" "$LOG" || true)
  echo "COMPILATION ERROR 计数 = $comp （必须为 0，否则本轮作废）"
  if [ "$comp" != "0" ]; then
    echo "VOID: 变异体没编过 —— 「红」会是编译错误，不算数"
    restore_world
    exit 9
  fi
  # ★★ 第二道作废闸：**surefire 到底跑没跑**。「没跑到」≠「没红」——
  #    Checkstyle / enforcer / 装配期失败都会让 rc≠0 却一行 `Tests run` 都没有
  #    （m1 的 attempt1 就是这样：UnusedImports 在 surefire 之前拦下）。
  ran=$(grep -c "Tests run" "$LOG" || true)
  echo "surefire `Tests run` 行数 = $ran （为 0 即本轮作废，不许读成被杀）"
  if [ "$ran" = "0" ]; then
    echo "VOID: surefire 根本没跑到 —— 这一轮的 rc=$rc 不是「红」，是「没跑」"
    restore_world
    exit 9
  fi
  echo "本轮 rc=$rc（0=存活 / 非 0=被杀）——★ 判杀前先读日志红点是不是被保护的那行本身"
} > "$LOG" 2>&1

# 上一段是**追加式**：日志里可能留有同名轮次的旧自记块，引用时必须取**本名轮那一块**。
restore_world >> "$LOG" 2>&1
# 自记段落笔**之前**的字节 md5：自指要能被独立复核（把日志截到「装置自记」标记前再算一次即得）。
PREFIX_MD5=$(md5of "$LOG")
{
  echo "════════════════════════════════════════════════════════════════════"
  echo "── 装置自记（本段由 mut-round.sh 追加；log_prefix = 本段之前的字节）──"
  echo "log_prefix_md5=$PREFIX_MD5"
  echo "harness_md5   =$(md5of "$MUT/mut-round.sh")"
  echo "spec_md5      =$(md5of "$MUT/$LABEL.py")"
  echo "baseline_md5  =$(md5of "$BASELINE")"
  while read -r base path; do
    echo "  file $path"
    echo "       baseline=$base"
    echo "       now     =$(md5of "$WT/$path")"
  done < "$BASELINE"
  echo "结束时刻: $(date '+%F %T')"
} >> "$LOG" 2>&1

echo "日志: $LOG"
echo "log_md5=$(md5of "$LOG")"
tail -n 40 "$LOG"
