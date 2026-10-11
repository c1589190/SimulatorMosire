#!/usr/bin/env bash
# W6 标定批追加实验（2026-10-11）：**同机同会话三 jar 交错 A/B** —— 判"0→360 是否真变慢"。
#
# 由来：W9f 交账报 sw 0→360 = 10.650 s（jar ce0bf3ba…），W9e 同世界报 8.226 s（jar c29ba84c…）；
#   两者**同世界 dump 逐字节相同**（state md5 全等）⇒ 工作内容一样，只差墙钟。单样本跨会话比较不可判。
# 做法：三个 jar 的快照都还在 /tmp/simos-shaded-*.jar（run-shaded.sh 的副产品）⇒ **同一台机器、同一会话内交错各跑 3 次**：
#   · W9-fix   jar c29ba84c7499c5e990c66ce6fc36d73c（W9e 那个，8.226 s 的来源）
#   · W9-fix2  jar ce0bf3ba8f388492b0f0ab8ae079c22c（W9f 那个，10.650 s 的来源）
#   · HEAD     jar 24c8a0a2291f5cdbeaff49edca71a13b（本批 jar，5e64685e）
# 顺序 = fix, fix2, head ×3 轮交错（把热漂移摊到三个 jar 上，不偏袒任一个）。
# 每次跑完核对 d360 dump 的**整文件 md5 == 89bbecfa44e5e6320507d781dd5577c4**（⇒ 工作内容逐字节相同）。
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w6-calibration
RUNNER=$BATCH/scripts/w6run.py
CFG=$BATCH/scripts
KEEP='event=(MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_REPORT|MARKET_FILL|MARKET_ROUND_START|MARKET_UNFILLED|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|CLASS_[A-Z_]*|SLIDE_[A-Z_]*|STRATUM[A-Z_]*|FAMINE_[A-Z_]*|SEED_MINT[A-Z_]*|INDUSTRY_[A-Z_]*|DEBT_[A-Z_]*|HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|PROFIT_MIGRATION_PLAN|DAY_START|DAY_END|GUI_REQUEST_FAILED)|\tat |Caused by:|Suppressed:'
FIX=$(md5sum /tmp/simos-shaded-4OVYlv.jar | cut -d' ' -f1)
declare -A JAR
JAR[fix]=/tmp/simos-shaded-4OVYlv.jar
JAR[fix2]=/tmp/simos-shaded-4GZGPk.jar
JAR[head]=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
EXPECT=89bbecfa44e5e6320507d781dd5577c4
port=10920
for round in 1 2 3; do
  for which in fix fix2 head; do
    tag="ab360-$which-r$round"
    port=$((port+1))
    echo "=== [$(date +%H:%M:%S)] $tag jar=${JAR[$which]} port=$port ==="
    python3 "$RUNNER" --jar "${JAR[$which]}" --tag "$tag" --store "$BATCH/store/$tag" \
      --gui-port "$port" --mcp-port "$((port+1))" --approval-port "$((port+2))" \
      --world small-world --config "$CFG/shell-sw.json" --plan "$BATCH/plans/plan-sw360.json" \
      --out "$BATCH/logs" --dump-dir "$BATCH/dumps" \
      --log-level DEBUG --trace-level TRACE --social-level INFO --social-trace-level INFO \
      --app-level INFO --log-keep "$KEEP" 2>&1 | grep -E "advance |dump |readySeconds" 
    got=$(md5sum "$BATCH/dumps/$tag-d360-oneshot.json" | cut -d' ' -f1)
    if [ "$got" = "$EXPECT" ]; then echo "    ★ dump md5 == $EXPECT（工作内容逐字节相同）"; else echo "    ★★ dump md5 不一致: $got"; fi
    rm -f "$BATCH/logs/$tag.log" "$BATCH/logs/$tag.log.stripped"   # 只留 .result.json（本实验只要时间）
  done
done
echo "=== [$(date +%H:%M:%S)] AB DONE ==="
