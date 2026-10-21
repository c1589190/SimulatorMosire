# D-032 RF4a 任务书：土地买盘初始化 + 运力装配去死锁

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F4
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF3b 后 360 tick 全 OK，农业产出 4.096B，但仍有两个市场/运输死锁：

1. `SettlementDayContext.hexToIndustries` 唯一赋值在
   `SettlementDayProductionPipeline.closeAndHarvest`（约 `:459`，M7）。
   M3 的 `MarketLandIntentBook.minimumLandCapacityPerUnit` 在 `:319` 读它 ⇒ M3 时永远 `null`
   ⇒ `collectLandlessBuyDrafts` 永不生成买盘。
2. `MerchantCapacityCore.assemble` 在 `servicePriced` 格只把运力设为“手上 haul 服务货可用量”：
   ```java
   serviceAvailableMilli = max(0, haul goods - frozen)
   capacity = MerchantCapacity.ofService(..., serviceAvailableMilli, ...)
   ```
   当前标准农业世界没有 haul 生产链，64 个 merchant 户全部 `capacityMilli=0`；
   `MERCHANT_CAPACITY_POOL householdsWithDeliverableService=0`。
   但决策层 `MerchantCapacityMath.providerCapacityMilli` 已经明确使用
   `max(serviceAvailable, labor+tool productive)`；装配层却只取 service ⇒ 两把尺不一致、跨 hex 永远无运力。

## 2. 目标

### 2.1 M3 土地买盘初始化

- 在 `prepareProductionBase` / M3 之前设置 `ctx.hexToIndustries = ctx.settlementIndex.industriesByHex()`；
- 保留 `closeAndHarvest` 的现有赋值不动（幂等，避免改旧调用点）；
- 不改变土地订单/撮合语义，只让买盘路径拿到 hex→industries。

### 2.2 运力装配去死锁

- `servicePriced` 格的 `assemble` 运力改为：
  ```text
  productive = MerchantCapacity.capacityMilliOf(
      row.participationAdjustedLaborMilli(), toolMilli)
  effective = max(serviceAvailableMilli, productive)
  capacity = MerchantCapacity.ofService(household, hex, effective, labor, tool)
  ```
- 与 `MerchantCapacityMath.providerCapacityMilli` 的 max 口径一致；
- 保留 `serviceAbsent` 计数（有 merchant 无 haul 服务货的事实仍可读），但不再因此把整个池容量判 0；
- `askPerMilleOf` / tier / radius 仍由 effective capacity 派生；
- 非 servicePriced 格逐值不变。

## 3. 约束

- 只改 `SettlementDayProductionPipeline.java`、`MerchantCapacityCore.java`（必要时可改
  `MerchantCapacityMath` 只读 helper，但必须先报告）。
- 不新增持久组件/ChangeSet/Codec。
- 不改变商品+土地同一订单簿、5 tick 市场节奏、价格公式、债务/工资语义。
- 不实现完整运力生产方式（v2 P1-05 的简单函数已由现有劳动+工具派生出可交付量；本批只消除“有生产性运力但被服务口径判 0”的死锁）。
- 不写/改测试，不跑 test/verify。

## 4. 日志

- 土地：沿用 `D032_P3_LAND_INTENTS`，必要时 DEBUG 输出 `landBuys/landSells` 与 required；
- 运力：沿用 `MERCHANT_CAPACITY_POOL` / `MERCHANT_CAPACITY_HOUSEHOLD`，另加 DEBUG
  `MERCHANT_CAPACITY_FALLBACK`：`household`、`serviceAvailable`、`productive`、`effective`、`reason=no-haul-service-max-productive`；
- 日志失败不影响结算。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告改动方法、数值行为变化、受影响硬编码/既有断言、未完成；
账本 `.superpowers/sdd/2026-10-20-d032-rf4a-market-transport-wiring/impl-ledger.md`。

## 6. 验收锚点（测试 owner 后补）

- M3 `D032_P3_LAND_INTENTS` 的 `landBuys` 不再因 `hexToIndustries==null` 恒 0；
- `MERCHANT_CAPACITY_POOL.householdsWithDeliverableService > 0`（有劳动/工具的 merchant）；
- `MERCHANT_CAPACITY_LANE_TRUNCATED ... hexTotalCapacityMilli=0` 明显减少；
- servicePriced 格 `effective = max(service, productive)`，非服务格逐值不变；
- 360 tick 仍全 OK，无 advance exception。
