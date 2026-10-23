# D-032 生产资料承载生产状态：调查与目标模型（2026-10-22）

> **取代者：`docs/superpowers/specs/2026-10-22-d032-lot-carried-production-architecture-v1.md`（2026-10-22）** —— 本文是调查/提案留痕，**勿据此开工**；正式目标架构以新文档为准。

> 状态：调查/提案；未改 Java/XML/POM/测试，未提交。
> 触发：用户指出当前“单个生产方式/生产 unit 独立记录生产状态”不符合生产资料组织生产的目标。
> 本文若获批准，将取代：
> - `2026-10-22-d032-unified-lot-inventory-market-design.md` 中“`ProductionProcess.stageState` 作为阶段状态载体”的部分；
> - 现有 `ProductionProcess`/`ProductionData.instances` 作为土地长期生产进度权威的部分。
> 旧文保留历史留痕；在批准前仍以旧文为实现入口。

## 0. 用户口径（2026-10-22，逐字）

```text
我想说的是这样的生产方式定义很差劲。单个生产方式组织起的生产不但要有主导者家户，还是高度跟随生产资料的，这里可以沿用我之前提到的附着在库存物品上的生产状态，生产状态完全不应该单独记录，而是记录在生产资料库存产品上，例如农业耕地，10面积位于1阶段80%，3面积位于2阶段30%，1面积没有生产属性，也就是刚拿到手没有进入生产，那么就分别根据生产进度生成对应生产状态（生产投入、预估生产产出），然后再做市场预期，提供给家户投入劳动力和实际库存的真实生产位置——经过这么一轮，我发现单tick的抽象生产方式虽然和生产资料脱不开关系，但是还是有一个储存起来的内存中间态，不过总分比你现在完全独立好，调查一下
```

核心裁定：

1. 生产状态属于**生产资料库存 lot**，不属于独立的 `ProductionProcess`/unit。
2. 一个生产方式必须有**主导者家户**，并围绕实际生产资料组织。
3. 农业例子：
   - 10 面积，阶段 1，进度 80%；
   - 3 面积，阶段 2，进度 30%；
   - 1 面积，没有生产状态（刚拿到，未进入生产）。
4. 这些面积分别生成生产投入/预估产出/市场预期，再供家户分配劳动力和形成真实生产位置。
5. 允许存在**本 tick 内存中间态**（机会/排序/预期），但不得把生产进度再独立持久化。

## 1. 目标运行序列（先运行，后裁定）

### 1.1 S1：载入并形成 InventoryLotView

```text
Map LandComponent
  → LAND lot（LandComponentId, quantity, owner, state attrs）

Actor HouseholdInventory.balances
  → COMMODITY/COMPLETE lot（fungible）

Actor durable/stateful lot store（需新增）
  → ASSET/STATEFUL lot（工具、机器、运输马等）
```

`InventoryLotView` 是只读投影，不成为第二份持久权威。
土地 lot 的状态字段直接来自 Map component attributes。

### 1.2 S1b：由库存 lot 派生 ProductionOpportunityBook（本 tick 内存态）

`ProductionOpportunityBook` 只活在本 tick 的 `SettlementDayContext`/EconomySession：

```text
ProductionOpportunity
  opportunityId
  planId
  modeId
  operatorHousehold          // 主导者家户
  ownerHousehold
  location
  carrierLotIds              // 至少一个 LAND/ASSET lot
  quantityMilli              // 本次可用面积/数量
  currentStageId
  stageProgressDays
  cycleProgressDays
  expectedInputs
  expectedOutputs
  expectedNet
  laborRequirement
  score
```

同一 lot 可以出现在多个候选机会中（例如可选择继续当前阶段或重置重种），但一旦被选中，必须有唯一 claim；不同 lot 的进度不能合并成一个标量。

### 1.3 S3：从机会簿做生产位置/劳动选择

- 家户按综合效益排序机会，而不是按一个 unit 的聚合进度排序；
- 每个机会的 `expectedInputs/outputs` 来自该 lot 的当前阶段与剩余阶段；
- 市场预期用上一轮市场价格/需求（本 tick 未成交前不得伪造新价）；
- 主导者家户对机会负责；其他参与户通过劳动承诺参与；
- 选中的机会形成本 tick 的 `ProductionBatch`（瞬态或最小执行状态），引用 `carrierLotIds`，**不复制 stage/progress**。

### 1.4 S4：执行并回写 lot state

每日：

1. 读 carrier lot 的当前 stage/progress；
2. 计算当前阶段开始投入/每 tick 投入/劳动；
3. 实际扣料、实际劳动；
4. 更新 carrier lot 的 Map/actor 状态；
5. 阶段结束：
   - NEXT_STAGE → 改 lot 的 stage，progress 归零；
   - HARVEST → 产出进入库存/服务 sink，lot 回到 IDLE。

劳动/投入不足：

- 按可用量缩规模；
- 覆盖部分保留原 lot；
- 未覆盖部分拆成新 lot，生产状态归零（owner/operator 保留）；
- 不得留下半边 revision。

### 1.5 S5：按 lot 产出/收获

- 每个 carrier lot 按自身 quantity/进度计算产出；
- 不再按 unit 的聚合 `scale` 分配整个 unit 的产出；
- 普通商品进入 inventory；
- `transport_capacity_service` 进入 `TransportCapacitySink`；
- 收获后 lot state 清空/IDLE。

### 1.6 S7：市场按 lot 状态交易

- 卖出 lot 时 `MarketOrder.sourceLotId` 指向具体 land/durable lot；
- 状态随 lot 转移；
- 买方下一 S3 可从同一 stage/progress 继续；
- 市场订单不复制 stage/progress。

## 2. 农业 10/3/1 面积的目标表示

假设同一 hex 同一 owner：

```text
LandComponent A: quantity=10000 milli-mu
  state = IN_PRODUCTION(plan=grain, stage=1, stageProgress=80%)

LandComponent B: quantity=3000 milli-mu
  state = IN_PRODUCTION(plan=grain, stage=2, stageProgress=30%)

LandComponent C: quantity=1000 milli-mu
  state = IDLE
```

`InventoryLotView` 产生三行，不合并：

```text
lotA  LAND  10,000  IN_PRODUCTION(stage1, 80%)
lotB  LAND   3,000  IN_PRODUCTION(stage2, 30%)
lotC  LAND   1,000  IDLE
```

`ProductionOpportunityBook` 产生三个机会：

```text
oppA：lotA，预计投入=stage1剩余投入，预计产出=完成至 harvest 的 grain
oppB：lotB，预计投入=stage2剩余投入，预计产出=完成至 harvest 的 grain
oppC：lotC，预计投入=从 stage1 开始的完整投入，预计产出=完整周期 grain
```

家户可以选择：

- 继续 oppA/oppB；
- 用 oppC 新开生产；
- 或暂不生产。

劳动、投入、市场预期按机会分别计算，最后再汇总到家户预算。

## 3. 状态归属与唯一权威

| 数据 | 唯一权威 | 说明 |
|---|---|---|
| LAND 的 stage/progress/crop/operator | Map `LandComponent.attributes` | Map 拥有土地；状态随 component 拆分/转移 |
| 非土地 durable lot 的 stage/progress | actor/economy 的 stateful lot 行 | 普通商品仍是 fungible balance，不塞 state |
| 生产定义/阶段定义 | production `IndustryDefinitionView.stages` | revision 状态 |
| 生产机会/市场预期 | 本 tick 内存态 | 不落 ChangeSet/Codec |
| 实际劳动/工资/市场/债务 | economy | 不变 |
| 普通商品库存 | actor `HouseholdInventory.balances` | 只保存 COMPLETE fungible 余额 |
| 地块 owner | Map component `OWNER` | 交易只改 owner，保留状态 |

“生产状态不单独记录”不等于“没有内存中间态”：

```text
持久权威：lot state
本 tick 中间态：ProductionOpportunity / ProductionBatch / 排序分数
```

中间态每 tick 从 lot view 重新派生，不能跨 revision 成为第二权威。

## 4. 当前实现为什么不满足

### 4.1 `ProductionProcess` 是 unit 级状态

`ProductionProcess` 当前携带：

```text
id, plan, location, operator, modeKey,
progressDays, cycleContributions, cycleInputUsedMilli, stageState
```

证据：`simos-production/.../contract/ProductionProcess.java:78-87`。

`ProductionData.instances` 是：

```text
Map<ProductionUnitId, ProductionProcess>
```

证据：`simos-production/.../ProductionData.java:76-84`。

这意味着：

- 一个 `(plan, operator, location)` unit 只有一个 `progressDays/stageState`；
- 无法表达同一 hex 的 10 亩阶段 1、3 亩阶段 2、1 亩空闲；
- 10+3+1 若拆成多个 unit，又没有 lot identity；
- 阶段状态存在 `ProductionStageState`（`ProductionStageState.java:28-29`），仍是 process 独立状态。

**结论：U4b1 把 stageState 放进 ProductionProcess 是临时引擎层桥，不应作为最终权威继续扩写。**

### 4.2 候选/生产位置是一个 unit，不是 lot

`ProductionProspectBook.productionProspect(...)` 的输入是：

```text
household, modeId, position, hex, industry, units...
```

它先选 `existingUnitId`，再按一个 `ProductionProcess` 做试算：

`ProductionProspectBook.java:231-248, 252-284`。

因此候选规模/投入/产出是“这个 unit 的一份聚合”，不是每个 land lot 一份。

### 4.3 S4 扣料按 unit plannedScale

`ProductionInputSettlement` 用：

```text
unit, perScale, plannedScale
```

逐 unit 调查/扣料；`plannedScale` 来自 unit 的资产/durable 规模。

证据：`ProductionInputSettlement.java:420-455`。

没有“这个 lot 的面积 × 当前 stage 需求”的维度。

### 4.4 S5 收获按 unit/scale

`ProductionRunSettlement.harvest` 用：

```text
unit, industry.recipe(), scale, outputQuantityOverrides
```

生成 gross/net/service capacity。

证据：`ProductionRunSettlement.java:700-732`。

没有 per-lot output；多个 lot 不同进度会被一个 unit 的平均 scale 抹平。

### 4.5 Map 有状态属性，但生产不读它

Map component 已有：

```text
OWNER / PROGRESS / STAGE / CYCLE_PROGRESS / START_INPUT_PAID / CROP / FERTILITY
```

证据：`LandAttributeKey.java:16-32`；`LandComponent.java:17-20`。

U3 已支持 component 级转移/拆分复制属性。
但生产/经济路径主要读 `ProductionProcess`，不读这些 Map 属性作为生产状态。

### 4.6 土地账本仍是 owner 聚合

`LandComponentLedger` 有 `componentsByHex`，但公开写口仍是：

```text
holdings() / replaceHoldings()
```

`replaceHoldings` 明确“不改 componentsByHex”（`LandComponentLedger.java:141-167`）。
`ArableSupplySnapshots.applyLedgerToMap` 只按 owner 净差转移，不负责生产状态推进。

### 4.7 非土地 durable 状态没有持久 lot 表

`HouseholdInventory` 仍是：

```text
Map<CommodityId, Long> balances
```

证据：`HouseholdInventory.java:102-107`。

`InventoryLotView` 是只读视图（`InventoryLotView.java:14-22`），没有对应的 stateful lot 写口。
所以工具/机器/运输马等“长期生产资料”的自身进度无法挂到库存行上。

### 4.8 市场 lot 交易基础已有，但生产不产 stateful lot

U1b/U2a 已有：

- `InventoryTransferPort` + commodity/land adapters；
- `MarketOrder`/`MarketListing`/`sourceLotId`；
- `LandComponentOperations.splitComponentWithOverrides`。

但 production 仍不创建“带 IN_PRODUCTION 状态的 lot”，所以市场层只能交易普通余额/纯土地，不能交易“阶段 1 80% 的 10 亩”作为生产中的 lot。

### 4.9 `ProductionModeDefinitionView` 目前是空壳

当前 mode 只有：

```text
id, name, version, classStructureId
```

证据：`ProductionModeDefinitionView.java:23-24`。

它不包含：

- 主导者/组织规则；
- 可接受生产资料；
- 阶段/配方（这些在 IndustryDefinitionView 里）；
- 生产单位如何绑定 lot。

所以“生产方式定义很差劲”在代码上表现为：
mode 只是 class structure 的引用，真正生产组织被 `ProductionProcess` 这个 unit 独立承担。

## 5. 推荐目标形状

### 5.1 `ProductionLotState`

```text
ProductionLotState
  planId
  modeId
  stageId
  stageProgressDays
  cycleProgressDays
  operatorHousehold
  startInputsPaid?（或由 lot 属性保存）
```

状态只放在：

- `LandComponent.attributes`（LAND）；
- stateful goods lot 行（ASSET/STATEFUL）。

普通商品 `COMPLETE` 没有生产状态。

### 5.2 `ProductionOpportunity`（本 tick 内存）

```text
ProductionOpportunity
  opportunityId
  planId / modeId
  ownerHousehold
  operatorHousehold
  location
  carrierLotIds
  quantityMilli
  currentStage
  stageProgress
  expectedInputs
  expectedOutputs
  expectedNet
  laborRequirement
  score
```

它由 `ProductionOpportunityBook` 从 lot view 派生，不落 revision。

### 5.3 `ProductionBatch`（执行瞬态/最小状态）

```text
ProductionBatch
  batchId
  opportunityId
  carrierLotIds
  operatorHousehold
  participantLabor
  actualInputs
  actualOutputs
  status
```

- 不复制 stage/progress；
- 状态读取/写回 carrier lot；
- 可以只活一个 tick；如果需要跨 tick 执行，它保存 lot id + claim，而不是第二份进度。

### 5.4 S3/S4 的边界

```text
InventoryLotView
  → ProductionOpportunityBook（每 tick 重算）
  → market valuation / profit score
  → household labor + position selection
  → ProductionBatch
  → lot state writes
```

市场预期只使用上一轮价格/需求，不回头修改本 tick 已完成的消费。

## 6. 迁移顺序（建议）

1. **停止扩写 `ProductionProcess.stageState`**；标记为临时桥。
2. 建立 `InventoryLotView` → `ProductionOpportunityBook` 只读派生，先在农业 land 上运行。
3. 让 S3 以 opportunity 为候选，不再以 unit 为候选。
4. 让 S4/S5 以 carrierLotIds 执行；把 state 写回 LandComponent。
5. 移除/降级 `ProductionData.instances` 的进度权威。
6. 为 durable goods 增加 stateful lot 表和写口。
7. 市场 lot trade 接 stateful lot。

## 7. 当前不做的

- 本调查不改代码、不改测试、不提交；
- 不立刻删除 legacy `ProductionProcess`；
- 不把 `ProductionModeDefinitionView` 与 `IndustryDefinitionView` 立即合并；
- 不把普通商品变成逐件 lot；
- 不改变 S0/S2/S6/S8 顺序。

## 8. 待用户确认

1. `operatorHousehold` 是否作为 lot state/attribute 随 lot 保存？还是由每 tick 的 claim 表表达、lot 只存 owner？
2. 一个 lot 是否允许多个同时生产状态（轮作/套种），还是 v1 只允许一个？
3. 生产中的土地出售时，`operatorHousehold` 保持原主、还是新 owner 可立即接管？
4. 非土地 durable lot 的 state 写口，是否本轮就做，还是先把 LAND 做通？

## 9. 验收锚点

1. 10,000 毫亩阶段 1 80%、3,000 毫亩阶段 2 30%、1,000 毫亩 IDLE 在 InventoryLotView 中三行；
2. OpportunityBook 生成三个候选，expectedInputs/Outputs 按各自 stage/progress 计算；
3. S3 可选择三个候选，劳动按机会分配；
4. S4 推进后，A/B 保持不同 stage/progress，C 仍 IDLE（除非被选中新开）；
5. 部分出售 A 的 4,000 毫亩，买卖双方 lot 保留 stage/progress，数量守恒；
6. 收获按 lot 产出，不按 unit 平均；
7. 没有第二份 `ProductionProcess.progressDays/stageState` 生产进度权威；
8. legacy 无 carrier 的单 tick 生产仍可用，其机会/批次只活在本 tick 内存态。
