#!/usr/bin/env bash
#
# shaded jar 端到端「优雅关闭无 ClassNotFoundException」检查（真实起进程 + 真实 SIGTERM）。
#
# 为什么是脚本、不进 Maven 门禁：它要**真起 HTTP 进程 + 绑端口 + 发信号**，
# 放进 surefire/exec 会让门禁变脆（端口占用、时序、跨平台）。故作为独立验收脚本 +
# 证据留档（docs/shade-evidence/）。**Maven 门禁里的对应物是
# simos-app/src/verify/shaded-jar-guard.sh**（构建期 jar 内容不变量）。
#
# 覆盖的失败模式：shaded jar 缺运行期类（reactor/log4j/…）⇒ 关闭路径炸。
# 用法：tools/shaded-close-check.sh <shaded-jar> [store] [gui端口] [mcp端口] [approval端口]
# 依赖：decision MCP 端口固定 5717（Shell 无 CLI 开关），运行时必须空闲。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="${1:-}"
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "用法: shaded-close-check.sh <shaded-jar> [store] [gui] [mcp] [approval]" >&2
  exit 2
fi
store="${2:-$(mktemp -d /tmp/simos-close-store-XXXXXX)}"
gui="${3:-5821}"
mcp="${4:-5722}"
approval="${5:-5723}"

log="$(mktemp /tmp/simos-close-XXXXXX.log)"
mkdir -p "$store"
echo "[close-check] jar=$jar store=$store gui=$gui mcp=$mcp approval=$approval"
echo "[close-check] log=$log"

# 经快照运行器起（同 tools/run-shaded.sh），规避就地重写；进程 PID 与包装脚本同（exec）。
"$here/run-shaded.sh" "$jar" --store "$store" \
  --gui-port "$gui" --mcp-port "$mcp" --approval-port "$approval" > "$log" 2>&1 &
pid=$!

cleanup() { kill -TERM "$pid" 2>/dev/null || true; }
trap cleanup EXIT

# 就绪：GUI 200 **且**日志出现「WebUI 就绪」。后者不可省——E3 起**空库就地初始化**（bootstrapGenesis）与
# shutdown hook 注册都在 GUI 起之后（ShellMain.run 的次序），只等 GUI 200 会在种子未完成时发信号，
# 那时 hook 还没挂上，SIGTERM 直接默认终止，日志自然没有「收到停止信号」。
ready=0
for _ in $(seq 1 60); do
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${gui}/" || true)"
  if [ "$code" = "200" ] && grep -q 'WebUI 就绪' "$log"; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "[close-check] FAIL 实例未在 60s 内就绪（GUI / 200 且日志『WebUI 就绪』）" >&2
  tail -30 "$log" >&2
  exit 1
fi
echo "[close-check] OK   GUI / -> 200 且已就绪"

# 优雅关闭：SIGTERM（ShutdownHook 走 Shell.close）。
kill -TERM "$pid"
for _ in $(seq 1 30); do
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  sleep 1
done
if kill -0 "$pid" 2>/dev/null; then
  echo "[close-check] FAIL SIGTERM 后 30s 进程仍存活（关闭路径挂住）" >&2
  tail -30 "$log" >&2
  exit 1
fi
echo "[close-check] OK   SIGTERM 后进程已退出"

fail=0
if grep -q '收到停止信号，关闭 Shell' "$log"; then
  echo "[close-check] OK   日志含『收到停止信号，关闭 Shell』"
else
  echo "[close-check] FAIL 日志未见关闭信号处理" >&2
  fail=1
fi
if grep -qE 'ClassNotFoundException|NoClassDefFoundError' "$log"; then
  echo "[close-check] FAIL 关闭日志含 ClassNotFoundException/NoClassDefFoundError：" >&2
  grep -nE 'ClassNotFoundException|NoClassDefFoundError' "$log" >&2
  fail=1
else
  echo "[close-check] OK   关闭日志无 ClassNotFoundException / NoClassDefFoundError"
fi

if [ "$fail" -ne 0 ]; then
  echo "[close-check] 结论：FAIL" >&2
  exit 1
fi
echo "[close-check] 结论：PASS（优雅关闭无该异常；日志 $log）"
