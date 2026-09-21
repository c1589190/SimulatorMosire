#!/usr/bin/env bash
#
# shaded jar 快照运行器 —— 修「运行中的 JVM 读到被就地重写的 jar」这个真缺陷。
#
# 由来（2026-09-21 实测，决定性复现见 docs/shade-evidence/report.md）：
#   `java -jar target/...-shaded.jar` 起的实例，在**同一条路径的 jar 被就地重写之后**
#   （maven-shade 每次 `mvn package` 都会重写它），再懒加载任何尚未加载的类会抛
#   `NoClassDefFoundError` / `ClassNotFoundException`：
#     · 5817：关闭时首懒加载 `reactor.core.publisher.LambdaMonoSubscriber` ⇒ Shell.close 处炸；
#     · 5818：GUI 请求时首懒加载 `org.apache.logging.log4j.message.ParameterizedMessage` ⇒ 请求全 -1。
#   根因：JVM 打开 jar 时缓存了中央目录里的**偏移量**，文件被就地重写后偏移全错位；
#   即使新 jar 里那些类都在，也读不出。
#
# 用法：tools/run-shaded.sh <shaded-jar> [ShellMain 参数...]
# 行为：把 jar 复制成一份**本次进程独占**的快照再 `exec java -jar <快照>`。
#      此后无论 target 下的 jar 怎么被重写，本进程都不受影响。
set -euo pipefail

jar="${1:-}"
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "用法: run-shaded.sh <shaded-jar> [ShellMain 参数...]" >&2
  exit 2
fi
shift

snap="$(mktemp /tmp/simos-shaded-XXXXXX.jar)"
cp -p "$jar" "$snap"
echo "[run-shaded] 快照 $jar -> $snap（本次进程独占，免受就地重写影响）" >&2
exec java -jar "$snap" "$@"
