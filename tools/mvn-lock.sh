#!/usr/bin/env bash
# ★★ Maven 串行化锁（AGENTS.md §一.1：本机一次只能跑一个 Maven，两轮同抢 target/ 会让测试计数失真）。
# ★ 作用域 = **本仓/同工作树**（用户 2026-10-23 更正：「那是隔壁项目，编译门禁只是用于同一个项目文件！」
#   ⇒ 隔壁仓库的 Maven 不参与此锁、不阻塞本仓构建；本锁只串行化落在本仓的 Maven）。
#
# 用法：把 `./mvnw` 换成 `tools/mvn-lock.sh`，其余参数原样透传：
#     tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
#     tools/mvn-lock.sh clean verify
#
# 行为：拿不到锁就等（默认等到天荒地老；用 MVN_LOCK_WAIT 秒数设上限）。
#   ★ 这不是为了并发加速，而是为了**允许多个子 Agent 并行改代码**时，
#     它们的编译动作自动排队 —— 编辑可以并行，Maven 必须串行。
#
# ★ 为什么不用 `flock` 命令：本机可能没有 util-linux 的 flock；
#   这里用 mkdir 的原子性做锁（POSIX 保证 mkdir 是原子的），兼容面最大。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_DIR="$ROOT/.mvn-lock.d"
WAIT_LIMIT="${MVN_LOCK_WAIT:-0}"   # 0 = 无限等
SELF=$$

acquired=0
waited=0
while [ "$acquired" -eq 0 ]; do
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    acquired=1
    echo "$SELF" > "$LOCK_DIR/pid"
    break
  fi
  # 陈旧锁清理：持锁进程已经没了（被 kill / 终端关掉）⇒ 抢过来
  if [ -f "$LOCK_DIR/pid" ]; then
    holder="$(cat "$LOCK_DIR/pid" 2>/dev/null || echo '')"
    if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then
      echo "[mvn-lock] 清理陈旧锁（持有者 $holder 已不在）" >&2
      rm -rf "$LOCK_DIR"
      continue
    fi
  fi
  if [ "$WAIT_LIMIT" -gt 0 ] && [ "$waited" -ge "$WAIT_LIMIT" ]; then
    echo "[mvn-lock] 等锁超过 ${WAIT_LIMIT}s，放弃" >&2
    exit 75
  fi
  [ "$waited" -eq 0 ] && echo "[mvn-lock] 另一个 Maven 在跑，排队等锁…" >&2
  sleep 5
  waited=$((waited + 5))
done

cleanup() { rm -rf "$LOCK_DIR"; }
trap cleanup EXIT INT TERM

cd "$ROOT"
# ★ **不能用 `exec`**：exec 会用 mvnw 替换本进程，EXIT trap 随之失效 ⇒ 锁永远不释放（实测踩到）。
#   这里保留本 shell 作为持锁者，跑完再退出，trap 才有机会跑。
set +e
./mvnw "$@"
status=$?
exit "$status"
