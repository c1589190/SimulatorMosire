#!/usr/bin/env bash
# ★★ Maven 包装器：**每棵工作树各自一个本地仓库** ⇒ **各树可以【同时】编译**（不再互相排队）。
#
# 用户裁定（2026-10-16）：「要不把协作原则改成那种能各自跑编译的类型？」⇒ 本文即该项落地。
#
# ── 模型（三段，缺一不可）────────────────────────────────────────────────
# ① **每棵树自己的 Maven 本地仓库** `$ROOT/.m2-repo`：
#    首次用时从 `~/.m2/repository` 用 **`cp -al`（hardlink）播种** ⇒ 块共享、几乎不占盘
#    （★ 实测：8267 文件 / 表观 736 MB / **真实占用仅 11 MB** / 播种 0.2 s）。
#    ⇒ 树与树之间**不再争 `~/.m2`**（Maven 本地仓库不是并发安全的，这是本来的病根）。
# ② **锁 = 每棵树一把**（`$ROOT/.mvn-lock.d`）：同一棵树内仍串行（防两轮同抢 `target/` 让计数失真），
#    但**跨树不再互相阻塞** ⇒ ★ 这才是"各自跑编译"。
# ③ ★★ **副产品（安全收益，比提速更值钱）**：新树的仓库里**没有本项目自己的构件** ⇒
#    本仓那条著名陷阱 —— 「`-pl` 不带 `-am` ⇒ Maven 从 `~/.m2` 拿到**旧字节码**、你验的不是工作树里的改动」
#    —— **结构上不可能再发生**：它会**响亮地失败**（找不到构件），而不是**静默测旧件**。
#
# ★ 作用域说明（邻居口径不变）：`~/.m2` 只被**读**（hardlink 播种）⇒ **隔壁仓库**照旧不受影响；
#   本包装器只作用于**它所在的那棵树**。
#
# 用法：把 `./mvnw` 换成 `tools/mvn-lock.sh`，其余参数原样透传：
#     tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
#     tools/mvn-lock.sh clean verify
# ★ 调用方自己给了 `-Dmaven.repo.local=…` ⇒ 尊重它、不覆盖。
# ★ 可用 `MVN_TREE_REPO=<path>` 换本树的仓库位置；`MVN_SEED_REPO=<path>` 换播种源。
#
# 行为：拿不到锁就等（默认等到天荒地老；用 `MVN_LOCK_WAIT` 秒数设上限）。
# ★ 为什么不用 `flock`：本机可能没有 util-linux 的 flock；
#   这里用 mkdir 的原子性做锁（POSIX 保证 mkdir 原子），兼容面最大。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── ① 本树自己的 Maven 本地仓库 ────────────────────────────────────────────
TREE_REPO="${MVN_TREE_REPO:-$ROOT/.m2-repo}"
SEED_REPO="${MVN_SEED_REPO:-$HOME/.m2/repository}"
if [ ! -d "$TREE_REPO" ]; then
  if [ -d "$SEED_REPO" ]; then
    # hardlink 播种（块共享）。★ 失败不致命：退化成空仓库，Maven 自己会下。
    cp -al "$SEED_REPO" "$TREE_REPO" 2>/dev/null || mkdir -p "$TREE_REPO"
  else
    mkdir -p "$TREE_REPO"
  fi
fi
[ -d "$TREE_REPO" ] || mkdir -p "$TREE_REPO"

# ── ② 锁：**每棵树一把**（跨树并行；同树内串行）────────────────────────────
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
  [ "$waited" -eq 0 ] && echo "[mvn-lock] 同一棵树里另一个 Maven 在跑，排队等锁…" >&2
  sleep 5
  waited=$((waited + 5))
done

cleanup() { rm -rf "$LOCK_DIR"; }
trap cleanup EXIT INT TERM

cd "$ROOT"
# ★ **不能用 `exec`**：exec 会用 mvnw 替换本进程，EXIT trap 随之失效 ⇒ 锁永远不释放（实测踩到）。
#   这里保留本 shell 作为持锁者，跑完再退出，trap 才有机会跑。
set +e
if printf '%s ' "$@" | grep -q -- '-Dmaven.repo.local'; then
  ./mvnw "$@"                                   # 调用方指定了仓库 ⇒ 尊重
else
  ./mvnw "-Dmaven.repo.local=$TREE_REPO" "$@"
fi
status=$?
exit "$status"
