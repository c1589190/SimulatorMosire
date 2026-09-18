#!/usr/bin/env bash
# M5 T9b 演示运行取证：起真 ShellMain --demo（空库首启种世界）→ 抓页面/资产/JSON → node --check → 截图。
# 用法: bash t9b-demo-evidence.sh
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m5t9b
cd "$ROOT" || exit 2
EV="$ROOT/.superpowers/sdd/2026-09-19-shell-simos-plan/t9b-evidence"
LOG="$EV/logs/evidence.txt"
STORE=/tmp/t9b-demo-store
PORT=5817
BASE="http://127.0.0.1:$PORT"
PLAYWRIGHT_MODULE=${PLAYWRIGHT_MODULE:-/home/cna/.npm/_npx/9833c18b2d85bc59/node_modules/playwright}
PLAYWRIGHT_BROWSER=${PLAYWRIGHT_BROWSER:-/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome}

rm -rf "$STORE"
mkdir -p "$EV/logs" "$EV/screenshots"

CLASSES="simos-app/target/classes:simos-core/target/classes:simos-map/target/classes:simos-social/target/classes:simos-unit/target/classes:simos-util/target/classes"
CP="$CLASSES:$(cat /tmp/t9b-cp.txt)"

java -cp "$CP" io.mosire.simos.app.ShellMain --store "$STORE" --demo --gui-port "$PORT" > "$EV/logs/shell-run.log" 2>&1 &
JAVA_PID=$!
cleanup() { kill "$JAVA_PID" 2>/dev/null || true; wait "$JAVA_PID" 2>/dev/null || true; }
trap cleanup EXIT

# 等端口就绪
ready=0
for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/" 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then ready=1; break; fi
  sleep 1
done

{
  echo "# T9b 证据：--demo 首启 + 页面/JSON 抓取 + node --check + 截图"
  echo "date=$(date -Is)"
  echo "base=$BASE  store=$STORE  ready=$ready"
  echo ""
  echo "## 0 起动日志（应含"已种入演示世界"）"
  grep -E "已种入演示世界|WebUI 就绪|Shell 装配完成" "$EV/logs/shell-run.log" || true
  echo ""
  echo "## 1 页面与静态资产（HTTP 状态码）"
  for path in / /map /unit /social /api.js /app.js /styles.css /map.js /unit.js /social.js; do
    out=$(curl -s -o /dev/null -w "%{http_code} %{content_type} %{size_download}" "$BASE$path")
    printf "GET %-14s -> %s\n" "$path" "$out"
  done
  echo ""
  echo "## 2 关键 JSON（--demo 种子值的可断言形态）"
  for path in "/api/state" "/api/units" "/api/map/overview" "/api/map/hex?q=1&r=1" "/api/social/population?q=1&r=1"; do
    echo "GET $path"
    curl -s "$BASE$path"; echo
  done
  echo ""
  echo "## 3 node --check（5 个 JS）"
  ( cd simos-app/src/main/resources/webui && for f in api.js app.js map.js unit.js social.js; do
      if node --check "$f" 2>/dev/null; then echo "node --check $f rc=0"; else echo "node --check $f rc!=0"; fi
    done )
  echo ""
  echo "## 4 playwright 截图 + 单位标记像素核验"
  PLAYWRIGHT_MODULE="$PLAYWRIGHT_MODULE" PLAYWRIGHT_BROWSER="$PLAYWRIGHT_BROWSER" \
    node "$EV/t9b-screenshot.mjs" "$BASE" "$EV/screenshots"
} > "$LOG" 2>&1

echo "证据写入 $LOG"
cat "$LOG"
