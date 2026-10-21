# D-032 32hex 单市场区 360 tick 数据表

用户 2026-10-19 要求：32 hex、1 个市场区、1 个地方政府，跑 360 tick；控制方只交表，不自行解读。

## 文件

- `rural-32hex-360d.csv`：表头 1 行 + 数据 360 行，33 列。
- `rural-32hex-360d.md`：表头 1 行 + 分隔 1 行 + 数据 360 行。
- `SHA256SUMS`：两份表的 sha256。

## 生成命令

```bash
SIMOS_MULTITICK_SKIP_BUILD=1 \
JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' \
tools/multitick-sim.sh rural-32hex-360d \
  > /tmp/d032-rural360-final-debug.log 2>&1
```

- rc=0；`MULTITICK_SIM_RESULT=PASS cases=1 failed=0`。
- 360 行 `status` 全部为 `OK`。
- `D032_TICK_CHAIN` 360 条；`D032_HOUSEHOLD_CHAIN` 104,566 条 DEBUG。
- 日志：`/tmp/d032-rural360-final-debug.log`。

## 列

```text
tick, marketDay, revision, status,
population, households, births, deaths,
moneyTotalMilli, grainStockMilli, fiberStockMilli, toolStockMilli,
landTotalMilliMu, landPriceMilliPerMu, landTradedMilliMu, landDebtTransferredBlocks,
productionGrainMilli, productionFiberMilli, seedInputMilli,
marketTrades, marketUnfilled, grainPriceMilli, fiberPriceMilli,
wageDueMilli, wagePaidMilli, wageArrearsMilli, wageDebtOutstandingMilli,
debtPrincipalMilli, debtContractCount,
productionLaborMilli, hiredLaborMilli, unemployedLaborMilli, note
```

本目录只是把模拟产物原样交付给用户核对；控制方未在此 README 或提交信息中作经济结论。
