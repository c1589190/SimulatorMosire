#!/usr/bin/env bash
# ★★ Maven 串行化锁（AGENTS.md §一.1：本机一次只能跑一个 Maven，两轮同抢 target/ 会让测试计数失真）。
# ★★ 作用域 = **本项目**（同一个 `.git` 的**全部工作树**共享一把锁），**不是**单棵工作树。
#   用户原话（2026-10-23）：「那是隔壁项目，编译门禁只是用于同一个项目文件！」
#   ⇒ **隔壁仓库**（另一个 `.git`）不参与此锁、不阻塞本仓构建 ✔
#   ⇒ ★★ 但**本仓的工作树**属于**同一个项目** ⇒ 它们**必须共用一把锁**，原因两条：
#     ① 工作树们共享 `~/.m2/repository`（773 MB）—— **Maven 本地仓库不是并发安全的**，
#        两个工作树同时构建会争它（per-tree 锁拦不住）⇒ 2026-10-16 实测并修掉；
#     ② 同一项目的 target/ 与测试计数本来就该串行（本锁的初衷）。
#   ★ 锁落在**共享 `.git`** 里（`$(git rev-parse --git-common-dir)`）⇒ 天然"一项目一把"。
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
# ★★ 锁目录 = **共享 .git** 之下 ⇒ 同一项目的**所有工作树共用一把锁**（见文件头注释）。
#   取不到 git（不是仓库 / 没装 git）⇒ 退化成"单棵工作树一把锁"（与旧行为一致，不会更坏）。
GIT_COMMON="$(git -C "$ROOT" rev-parse --git-common-dir 2>/dev/null || true)"
if [ -n "$GIT_COMMON" ]; then
  case "$GIT_COMMON" in
    /*) : ;;                                  # 已是绝对路径
    *)  GIT_COMMON="$ROOT/$GIT_COMMON" ;;    # 相对路径按本工作树解析
  esac
  LOCK_DIR="$GIT_COMMON/.mvn-lock.d"
else
  LOCK_DIR="$ROOT/.mvn-lock.d"
fi
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
