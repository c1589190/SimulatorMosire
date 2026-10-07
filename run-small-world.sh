#!/usr/bin/env bash
#
# P1.4 小世界（small-world）真实运行脚本 —— 建 DB（空库才 bootstrap）→ 初始化 19 hex 小世界（含中央/省两级 GOV 行政链）→
# 起 Shell → 浏览器打开 WebUI。
#
# 口径（用户 2026-10-08/09）：
#   · 世界选择走 --world=small-world（本脚本显式传；config/shell.json 也已切到小世界，两处同向）；
#   · 不使用 --demo（该开关已从 ShellMain 拔掉）；
#   · 独立 store（缺省 run/small-world-db）——非空库绝不覆盖：ShellMain 只在空库 bootstrap，
#     本脚本不删任何已有库；库非空时 --world 无作用（直接打开已有世界）；
#   · GUI 用非 5711 的演示端口（缺省 5811），避免踩到别的在跑实例；
#   · 不覆盖运行中的 jar / 服务：本脚本**不跑 package**（package 会就地重写 target 下的 jar，
#     弄坏正在使用同一路径的进程），只消费已构建的 shaded jar，并交给 tools/run-shaded.sh
#     复制成进程独占快照；端口被占时直接拒绝，不 kill 别人。
#
# 前置（由控制方在**没有服务占用该 jar 路径**的窗口里做，本脚本故意不替你做）：
#     ./mvnw -q -pl simos-app -am -DskipTests package
#
# 可覆盖的环境变量：
#     SIMOS_SMALL_WORLD_JAR / _STORE / _GUI_PORT / _MCP_PORT / _APPROVAL_PORT
#
# 用法：./run-small-world.sh          # 前台跑；Ctrl-C 停
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

WORLD="small-world"
STORE="${SIMOS_SMALL_WORLD_STORE:-run/small-world-db}"
GUI_PORT="${SIMOS_SMALL_WORLD_GUI_PORT:-5811}"
MCP_PORT="${SIMOS_SMALL_WORLD_MCP_PORT:-5815}"
APPROVAL_PORT="${SIMOS_SMALL_WORLD_APPROVAL_PORT:-5813}"
URL="http://127.0.0.1:${GUI_PORT}/"

# ── 1) 找到已构建的 shaded jar（不自己构建：见文件头）────────────────────────────────────
JAR="${SIMOS_SMALL_WORLD_JAR:-}"
if [ -z "$JAR" ]; then
  # 取最新构建的那一件；没有就是没有，不猜版本号。
  for candidate in simos-app/target/simos-app-*-shaded.jar; do
    [ -f "$candidate" ] || continue
    if [ -z "$JAR" ] || [ "$candidate" -nt "$JAR" ]; then
      JAR="$candidate"
    fi
  done
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  cat >&2 <<'EOF'
[run-small-world] 没找到 shaded jar。
  请先在**没有在跑的服务占用该 jar 路径**的窗口里构建（本脚本不替你跑 package）：
      ./mvnw -q -pl simos-app -am -DskipTests package
  或显式指定已有 jar：
      SIMOS_SMALL_WORLD_JAR=/path/to/simos-app-*-shaded.jar ./run-small-world.sh
EOF
  exit 2
fi
[ -x tools/run-shaded.sh ] || {
  echo "[run-small-world] 缺 tools/run-shaded.sh（快照运行器）" >&2
  exit 2
}

# ── 2) 端口占用检查：不覆盖、不 kill 任何已有服务 ────────────────────────────────────────
port_in_use() {
  local port="$1"
  local flag="$2"
  if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -qE "[:.]${port}([[:space:]]|$)"; then
    return 0
  fi
  pgrep -f -- "${flag}[= ]${port}([[:space:]]|$)" >/dev/null 2>&1
}
require_port_free() {
  local port="$1"
  local flag="$2"
  if port_in_use "$port" "$flag"; then
    echo "[run-small-world] 端口 ${port}（${flag}）已被占用：拒绝启动（不覆盖运行中的服务；换 SIMOS_SMALL_WORLD_*_PORT）。" >&2
    exit 3
  fi
}
require_port_free "$GUI_PORT" "--gui-port"
require_port_free "$MCP_PORT" "--mcp-port"
require_port_free "$APPROVAL_PORT" "--approval-port"

# ── 3) store 准备：只建目录，不删、不覆盖；非空库由 ShellMain 按数据安全线处理 ──────────────
STORE_PARENT="$(dirname "$STORE")"
mkdir -p "$STORE_PARENT"
if [ -e "$STORE" ] && [ ! -d "$STORE" ]; then
  echo "[run-small-world] store 路径存在但不是目录：${STORE}" >&2
  exit 2
fi
if [ -e "${STORE}/simos.db" ]; then
  echo "[run-small-world] 注意：${STORE} 已有库 ⇒ 直接打开已有世界，绝不覆盖（--world=${WORLD} 只在空库生效）。" >&2
else
  echo "[run-small-world] 空库 ⇒ 将以 --world=${WORLD} bootstrap 小世界（19 hex / 1 Region / 首都+镇 / 4,800 人 / 两级 GOV 行政链内置）。"
fi

echo "[run-small-world] jar      : ${JAR}"
echo "[run-small-world] store    : ${STORE}"
echo "[run-small-world] WebUI    : ${URL}"
echo "[run-small-world] （GUI ${GUI_PORT} / MCP ${MCP_PORT} / 审批 ${APPROVAL_PORT}；启动完成后浏览器打开上面的 URL）"

# ── 4) 前台起 Shell（快照 jar 运行器：此后 target 下的 jar 被重写也不影响本进程）────────────
exec "$ROOT/tools/run-shaded.sh" "$JAR" \
  --store "$STORE" \
  --world="$WORLD" \
  --gui-port "$GUI_PORT" \
  --mcp-port "$MCP_PORT" \
  --approval-port "$APPROVAL_PORT"
