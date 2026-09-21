#!/usr/bin/env bash
#
# shaded jar 内容不变量守卫（挂门禁 verify 阶段，fail-closed）。
#
# 由来（2026-09-21 实测）：`5817` 关闭时抛
#   NoClassDefFoundError: reactor/core/publisher/LambdaMonoSubscriber
# 控制器初判为"shaded jar 缺 reactor-core"。实测**推翻**了该判断——shaded jar 里
# reactor-core 一直在（`mcp-core:2.0.1` 的传递依赖，`io.projectreactor:reactor-core:3.7.0`）；
# 真因是**运行中的 JVM 读到了被就地重写的 jar**（见 tools/shaded-close-check.sh 与
# docs/shade-evidence/ 的报告）。
#
# 但"shaded jar 必须含运行期用到的类"仍是一条**该被钉死的不变量**：`mcp-core` 哪天把
# reactor 改成 optional/provided，编译与单测都不会红，只会在运行期炸。本守卫在**构建期**
# 拦下这种漂移。它**不**、也不能覆盖"就地重写"那条真因。
#
# 用法：shaded-jar-guard.sh <shaded-jar 路径>
# 退出码：0 = 全部通过；1 = 有缺失；2 = 用法错。
set -euo pipefail

jar="${1:-}"
if [ -z "$jar" ]; then
  echo "[shaded-guard] 用法: shaded-jar-guard.sh <shaded-jar>" >&2
  exit 2
fi
if [ ! -f "$jar" ]; then
  echo "[shaded-guard] FAIL 找不到 shaded jar: $jar" >&2
  exit 1
fi
# 归一成绝对路径：后面要 `cd` 进临时目录用 `jar xf` 取 MANIFEST，相对路径会失效。
jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"
if ! command -v jar >/dev/null 2>&1; then
  echo "[shaded-guard] FAIL 需要 JDK 的 jar 命令（本仓一律用 jar tf，不用 unzip）" >&2
  exit 1
fi

# 运行期用到的类，逐条必在。左列 = jar 内条目，右列 = 为什么必须。
required_entries=(
  "reactor/core/publisher/LambdaMonoSubscriber.class"                              # MCP 关闭路径 Mono.subscribe 需要
  "reactor/core/publisher/Mono.class"                                              # reactor-core 本体
  "io/modelcontextprotocol/spec/McpStreamableServerTransportProvider.class"        # AgentToMcpServer.close 的调用点
  "io/modelcontextprotocol/server/McpSyncServer.class"                             # 同上
  "org/apache/logging/log4j/message/ParameterizedMessage.class"                     # 访问日志 warn 路径
  "org/slf4j/Logger.class"                                                         # 日志门面
  "org/sqlite/JDBC.class"                                                          # 存储驱动（替身：SQLite 原生库入口在 Driver 里）
)

fail=0
list="$(mktemp)"
trap 'rm -f "$list"' EXIT
jar tf "$jar" > "$list"

for entry in "${required_entries[@]}"; do
  if grep -qx -- "$entry" "$list"; then
    echo "[shaded-guard] OK   $entry"
  else
    echo "[shaded-guard] FAIL 缺少 $entry（running shaded jar 会在运行期 NoClassDefFoundError）" >&2
    fail=1
  fi
done

# Main-Class 必须是 ShellMain，否则 java -jar 直接起不来。
tmp="$(mktemp -d)"
( cd "$tmp" && jar xf "$jar" META-INF/MANIFEST.MF )
main_class="$(tr -d '\r' < "$tmp/META-INF/MANIFEST.MF" | awk -F': ' '/^Main-Class:/{print $2}')"
rm -rf "$tmp"
if [ "$main_class" = "io.mosire.simos.app.ShellMain" ]; then
  echo "[shaded-guard] OK   Main-Class=$main_class"
else
  echo "[shaded-guard] FAIL Main-Class 期望 io.mosire.simos.app.ShellMain，实得 '$main_class'" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "[shaded-guard] 结论：FAIL（shaded jar 不满足运行期不变量）" >&2
  exit 1
fi
echo "[shaded-guard] 结论：OK（$(wc -l < "$list") 个条目；${#required_entries[@]} 条运行期类 + Main-Class 全在）"
