# D-032 RF3b 任务书：让土地取得者的 LAND 占用请求进入 D4

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F3
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF3 已加入动态劳动回退 helper，但 360 tick 复跑结果与 RF2e **逐字节相同**，且日志中
`LAND_OWNER_LABOR_FALLBACK = 0` 条。原因是一个循环：

- `ProductionProcessBook.requestedScaleFromNonAssetFacts()` 对 farm 的 roster planned labor = 0 计算出
  `requestedScale = 0`；
- 因此 `AssetOccupancyAllocator` 不对该 unit 发出/分配 LAND；
- `SettlementIndex.assetAllocationOf(unit.id()).get(LAND) = 0`；
- RF3 helper 又要求 `LAND allocation > 0` 才回退；
- 所以 landless / 新地主拿到了 `landHoldings`，但 D4 永远不给它 LAND 占用，M3 试算仍 outputScale=0。

## 2. 目标

- 对 **land-based farm unit**，当：
  - roster 存在且 organizer `plannedLaborMilliPerDay == 0`；
  - 该 unit operator 家户在 unit 所在 hex 有 `landHoldings` 数量；
  - 该家户当刻 `laborMilli > 0`；
  则 D4 资产预算请求使用“该家户在本 unit hex 的 LAND 持有量 ÷ `capacityPerUnit[LAND]`”得到的规模（floor ≥ 0），
  而不是 roster 的 0 计划劳动。
- 这样 `landAllocationOf(unit).LAND > 0`，RF3 的 `effectivePlannedLaborMilliPerDay` 才能触发；
- 不改变 roster 历史快照，不新增持久组件。
- 对 roster planned >0 的普通 farm、非土地型 unit、无 LAND 的 unit、无劳动家户：逐值不变。

## 3. 约束设计

### 3.1 派生 map

在 `SettlementIndex.build(...)` 内部（已有 `units/industries/households/landHoldings` 与 `householdOfActor`）
计算一个**瞬态**：
```text
fallbackLandScaleByUnit: Map<ProductionUnitId, Long>
```
规则：

- 只看 `industry.capacityPerUnit()` 含 `AssetKind.LAND`；
- `productionRosters.get(unit.id())` 存在且 organizer 的 `plannedLaborMilliPerDay == 0`；
- `unit.operator()` 能解析到 household，且该 household 当刻 `laborMilli > 0`；
- 从 `landHoldings` 找 `(owner=household, hex=unit.location)` 的数量；unit location 用
  `IndustryHexKeys.hexKeyOf(unit.industry())` / `HexCoord` 解析，保持稳定序；
- `scale = landQuantity / capacityPerUnit[LAND]`，`capacityPerUnit[LAND] > 0`，`scale > 0` 才入 map；
- 这是 D4 占用请求的派生读数，不是第二份土地权威；不落状态、不进 ChangeSet/Codec。

### 3.2 D4 请求接入

- 给 `ProductionProcessBook.assetOccupancyBudget` 增加一个窄的 overload/参数：
  `Map<ProductionUnitId, Long> fallbackLandScaleByUnit`。
- 在逐 unit 计算 `requestedScale` 时：
  - 若 `fallbackLandScaleByUnit` 有该 unit 且 roster planned=0 ⇒ 使用 fallback scale；
  - 否则继续原来的 `requestedScaleFromNonAssetFacts`；
  - 不得把 fallback 应用到正常有 planned labor 的 unit。
- `SettlementIndex` 将 map 传给唯一 D4 预算调用点；旧重载传空表，保持旧世界逐值不变。
- `LandProductionProjection` 仍是 LAND 份额唯一瞬态投影；不得新增 LAND 到 `assetShares`。

### 3.3 与 RF3 的接口

- RF3 的 `LAND_OWNER_LABOR_FALLBACK` 条件保持：allocated LAND >0、organizer planned=0、户 labor>0；
- 本批不修改 RF3 helper 的触发条件；只让前置 allocation 变成非 0；
- 不写回 roster，也不把 `plannedLabor` 持久化改成正数。

### 3.4 非目标

- 不实现完整 v2 单 hex 标准农业聚合；
- 不改土地买卖/债务/租佃/抵押；
- 不改 craft/weave roster；
- 不删除 0 地 unit；
- 不修改 F1/F2 需求、种子、口粮、work-first 语义。

## 4. 允许文件

- `simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProcessBook.java`
- 仅编译必需时：同目录最小相邻文件（先报告）

禁止：测试、EconomyData/ChangeSet/Codec、production 定义、app seeder、主树、GUI/服务。

## 5. 日志

- 新增 DEBUG `LAND_FALLBACK_OCCUPANCY_REQUEST`：
  `unit`、`household`、`landQuantity`、`capacityPerLand`、`requestedScale`、`reason=zero-planned-land-owner`；
- 同 unit 每 tick 一条；只报读数，不改结算。

## 6. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告：改动文件/方法、fallback map 规则、D4 请求接入、数值行为变化、受影响硬编码/既有断言、未完成；
账本 `.superpowers/sdd/2026-10-20-d032-rf3b-land-fallback-occupancy/impl-ledger.md`。

## 7. 验收锚点（测试 owner 后补）

- landless/新地主户持有 LAND 且有劳动 ⇒ D4 allocation LAND >0；
- `LAND_OWNER_LABOR_FALLBACK` 事件出现，farm 位置进入 M3/M6；
- 其 farm 能得到劳动 grant，并在周期末有正 finalScale/毛产；
- roster plannedLabor 原值仍为 0（不落盘改写）；
- 普通有 planned labor 的 farm、非土地型 unit、无地 unit 逐值不变。
