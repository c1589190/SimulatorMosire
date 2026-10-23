# D-032 生产资料承载生产状态的目标架构 v1（2026-10-22）

> 状态：用户已确认方向 B，目标架构；未改 Java/XML/POM/测试，未提交。
> 父设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S0–S8 主链。
> 本文取代：
> - `2026-10-22-d032-lot-carried-production-state-design.md`（调查/提案稿，由本文收口）；
> - `2026-10-22-d032-unified-lot-inventory-market-design.md` 中“`ProductionProcess.stageState` 作为阶段状态载体”的部分；
> - 现有 `ProductionProcess` / `ProductionData.instances` 作为土地长期生产进度权威的部分。
> 旧文保留历史留痕；在实现切换完成前，旧代码仍存在。用户原话高于本文。

## 0. 用户最终裁定

1. **走 B**：库存 lot 保存 owner + 物理生产状态；`operatorHousehold` 不写进 lot 物理状态，放在每轮 claim/opportunity/batch。
2. 一个库存产品单位只能携带**一种**单一生产方式过程属性。
3. 要换生产方式必须在**下一轮**，不能在当前轮中途换。
4. 库存产品作为生产资料，承载的生产阶段**不因交易改变**。
5. 获得该生产资料的家户可以在下一轮围绕它继续组织生产。
6. **先把 LAND 做通**，再考虑非土地 durable/stateful goods。

## 1. 目标运行序列（先写运行，再写子设计）

### 1.1 S1：载入并形成 InventoryLotView

```text
Map LandComponent
  → LAND lot：
      lotId = LandComponentId
      owner = attributes[OWNER]
      quantity = quantityMilliMu
      productionState = attributes[CROP/STAGE/PROGRESS/CYCLE_PROGRESS/START_INPUT_PAID]

Actor HouseholdInventory.balances
  → COMMODITY/COMPLETE lot（fungible，无生产状态）

Actor durable/stateful lot store（LAND 之后再做）
  → ASSET/STATEFUL lot（工具、机器、运输马等）
```

规则：

- `InventoryLotView` 是只读合成视图，不是第二份持久权威；
- LAND lot 的物理生产状态来自 Map component attributes；
- 普通商品 `state=COMPLETE`，不携带生产状态；
- 同一 owner 的相同状态 component 可以被 Map normalize 合并，但不同 stage/progress 必须保持分离。

### 1.2 S1b：派生本 tick 的 ProductionOpportunityBook

`ProductionOpportunityBook` 是 economy session 内的**本 tick 内存态**，不落 ChangeSet/Codec：

```text
ProductionOpportunity
  opportunityId
  planId
  location
  ownerHousehold
  carrierLotIds
  quantityMilli
  currentStage
  stageProgressDays
  expectedInputs
  expectedOutputs
  expectedNet
  laborRequirement
  marketValue
  score
```

规则：

- 一个 land lot 至少产生一个机会；未进入生产的 IDLE lot 也可以产生“新开机会”；
- 同一 lot 不能同时有多个生产状态；
- 机会中的 `expectedInputs/outputs` 按 lot 当前 stage/progress 计算剩余投入与最终预估产出；
- 机会只活本 tick；下一 tick 从 lot state 重新派生。

### 1.3 S3：组织形式、市场预期、劳动/位置选择

顺序：

```text
InventoryLotView
  → ProductionOpportunityBook
  → 市场预期（上一轮价格/需求，只读）
  → 家户比较机会的综合收益
  → 选择 operator/参与者
  → 生成 ProductionClaim（本轮组织关系）
  → 生成 ProductionBatch（本轮执行计划）
```

`ProductionClaim`：

```text
ProductionClaim
  claimId
  lotId
  planId
  modeId
  operatorHousehold
  participantHouseholds
  fromTick
  toTickExclusive（本轮）
```

- `ownerHousehold` 来自 lot；
- `operatorHousehold` 是本轮主导者，不写入 lot 的物理状态；
- 一个 lot 在一个 tick 只能有一个 active claim；
- 同一 lot 的 claim 只能覆盖整个 lot；部分生产必须先拆 lot；
- claim 不保存 stage/progress，只表达组织关系；
- 市场预期不得使用本 tick 尚未发生的成交价。

### 1.4 S4：执行并把状态写回 carrier lot

每 tick：

1. 读 carrier lot 的 `ProductionLotState`；
2. 用 claim 的 operator/participants 和 lot 当前 stage/progress 计算投入/劳动；
3. 从实际库存扣实际投入；
4. 实际推进 lot 的 stage/progress；
5. 阶段结束：
   - `NEXT_STAGE` → 修改 lot stage，stageProgress 归零；
   - `HARVEST` → 产出进入库存/服务 sink，lot 回到 IDLE。

不足缩规模：

- 覆盖部分保留原 lot；
- 未覆盖部分从原 lot 拆出，生产状态清零（IDLE）；
- 拆出的 lot 仍保留 owner；
- 下一轮由 S3 决定是否重新 claim。

失败语义：

- 任何 lot 状态、库存、货币/债务、劳动分配写入必须在同一 revision；
- 失败没有半份输入/进度/land state。

### 1.5 S5：按 lot 产出

- 每个 carrier lot 的 `quantity` 和 `ProductionLotState` 独立计算产出；
- 不再用一个 unit 的聚合 `scale` 抹平多个 lot 的不同进度；
- 普通商品产出 -> owner/actor inventory；
- `transport_capacity_service` -> `TransportCapacitySink`；
- HARVEST 后 lot 清空生产状态，保留 owner/quantity。

### 1.6 S7：市场交易保留 lot 状态

- SELL order 的 `sourceLotId` 指向具体 lot；
- 土地成交只改 Map component 的 OWNER；
- crop/stage/progress/start inputs 等属性原样保留；
- 旧 `ProductionClaim` 在成交时失效/结束；
- 新 owner 在下一 tick S3 可重新 claim 并继续同一 stage/progress；
- 订单不复制 stage/progress。

## 2. 数据形状

### 2.1 `ProductionLotState`

LAND v1：

```text
ProductionLotState
  planId              // 物理生产方案/阶段定义
  stageId
  stageProgressDays
  cycleProgressDays
  startInputsPaid     // 本阶段开始投入是否已支付
  cycleInputUsedMilli // 本周期累计真实投入，按商品
```

说明：

- `planId` + `stageId` 是物理生产状态；一个 lot 只能有一个；
- `cycleInputUsedMilli` 是生产实际消耗历史，仍属于 lot 的生产状态；
- **不含 operatorHousehold**；
- `modeId` 不在 lot 物理状态中，放在 `ProductionClaim`；换 mode 下一轮改 claim，不重写土地物理阶段；
- 若换的是物理 `planId`/阶段体系，则不能在同一生产过程中直接换；必须在 lot IDLE/周期结束后开始新过程，或显式放弃旧过程。

### 2.2 `InventoryLot`

```text
InventoryLot
  lotId
  itemKind
  itemTypeId
  ownerHousehold
  location
  quantityMilli
  state
  origin
```

LAND：

```text
lotId = LandComponentId
owner = LandComponent.attributes[OWNER]
state = IN_PRODUCTION(ProductionLotState) | IDLE
```

普通商品：

```text
state = COMPLETE
```

### 2.3 `ProductionOpportunity`

只在本 tick 内存：

```text
ProductionOpportunity
  opportunityId
  planId
  location
  ownerHousehold
  carrierLotIds
  quantityMilli
  currentStageId
  stageProgressDays
  expectedInputs
  expectedOutputs
  expectedNet
  laborRequirement
  marketValue
  score
```

不落 ChangeSet/Codec；不成为第二份 lot state。

### 2.4 `ProductionClaim`

本轮组织关系：

```text
ProductionClaim
  claimId
  lotId
  planId
  modeId
  operatorHousehold
  participantHouseholds
  fromTick
  toTickExclusive
```

- 一个 lot 一个 tick 最多一个 active claim；
- claim 不存 stage/progress；
- owner 与 operator 可以不同；
- 交易时旧 claim 结束，新 owner 下一轮可重新组织。

### 2.5 `ProductionBatch`

本轮执行计划：

```text
ProductionBatch
  batchId
  claimId
  carrierLotIds
  operatorHousehold
  actualLabor
  actualInputs
  actualOutputs
  status
```

- 不复制 lot 的 stage/progress；
- 引擎从 carrier lot 读取状态；
- 执行结果写回 lot state；
- 可以只活一个 tick。

## 3. 农业 10/3/1 面积示例

```text
LandComponent A:
  quantity = 10,000 毫亩
  state = IN_PRODUCTION(plan=grain, stage=1, progress=80%)

LandComponent B:
  quantity = 3,000 毫亩
  state = IN_PRODUCTION(plan=grain, stage=2, progress=30%)

LandComponent C:
  quantity = 1,000 毫亩
  state = IDLE
```

InventoryLotView：

```text
lotA LAND 10,000 IN_PRODUCTION(stage1,80%)
lotB LAND  3,000 IN_PRODUCTION(stage2,30%)
lotC LAND  1,000 IDLE
```

本 tick OpportunityBook：

```text
oppA:
  carrierLotIds=[A]
  stage1 剩余投入
  预计最终 grain 产出
oppB:
  carrierLotIds=[B]
  stage2 剩余投入
  预计最终 grain 产出
oppC:
  carrierLotIds=[C]
  若从 stage1 开始：完整投入
  预计最终 grain 产出
```

S3 可以分别决定：

```text
A 继续由 H1 组织
B 由 H2 claim
C 暂不生产
```

S4 后：

```text
A stage1 progress 81%
B stage2 progress 31%
C 仍 IDLE
```

不出现一个 `ProductionProcess.progressDays` 把三块地平均掉。

## 4. 交易语义

### 4.1 地主自耕

```text
owner = H1
operator claim = H1
```

### 4.2 租佃

```text
owner = H1
operator claim = H2
```

土地物理状态不因租佃改变。

### 4.3 卖地

```text
成交前：
  owner = H1
  lot A stage1 80%
  claim = H1

成交后：
  owner = H2
  lot A stage1 80% 不变
  旧 claim 结束

下一 S3：
  H2 可以 claim A
  从 stage1 80% 继续
```

不重置 crop/stage/progress。

### 4.4 换生产方式

- 同一物理 plan 内换 mode：下一轮 S3 改 claim 即可；
- 换物理 plan：不能在同一 in-production lot 上静默换；必须在 lot IDLE/周期结束后重开，或显式重置为 IDLE；
- 任何 mode/claim 改变不得发生在 S4 执行中途。

## 5. 状态权威与模块边界

| 数据 | 唯一权威 | 说明 |
|---|---|---|
| LAND 的 owner/quantity/productionState | Map `LandComponent` | 交易只改 owner，状态原样 |
| 普通商品余额 | actor `HouseholdInventory.balances` | 无 state，COMPLETE |
| 非土地 stateful lot | actor/economy 新增 lot 表 | LAND 之后再实现 |
| 生产定义/阶段定义 | production `IndustryDefinitionView.stages` | revision 状态 |
| ProductionOpportunity | 本 tick economy session | 内存态，不落盘 |
| ProductionClaim | 本 tick/本轮 economy session | 组织关系，不存 stage/progress |
| ProductionBatch | 本 tick economy session | 执行计划，不存 stage/progress |
| 经验/熟练度余数 | production 家户经验账 | 仍按家户×经验条，不塞进 lot |

### 5.1 `ProductionProcess` / `ProductionData.instances`

目标：

- `ProductionProcess` 不再作为土地生产的进度权威；
- `ProductionData.instances` 不再存 “unit 级 stage/progress”；
- 现有 `ProductionProcess.stageState` 是 U4b1 的临时桥，必须在 L2/L3 移除或降级；
- 无 carrier 的单 tick 生产可以保留瞬态 `ProductionBatch`，不要求持久跨 tick。

## 6. 当前代码差距（file:line）

| 现状 | 证据 | 影响 |
|---|---|---|
| `ProductionProcess` 保存 `progressDays/cycleInputUsedMilli/stageState` | `ProductionProcess.java:78-87` | unit 级状态，不能表达多 lot |
| `ProductionData.instances` 为 `Map<ProductionUnitId, ProductionProcess>` | `ProductionData.java:76-84` | 生产进度仍按 unit 持久化 |
| 候选按 `existingUnitId`/unit 建立 | `ProductionProspectBook.java:231-284` | 不能按 lot 生成机会 |
| S4 按 unit `plannedScale` 扣料 | `ProductionInputSettlement.java:420-455` | 无法表达 lot 当前 stage + 数量 |
| S5 按 unit `scale` 收获 | `ProductionRunSettlement.java:700-732` | 多 lot 不同进度会被一个 scale 平均 |
| LAND component 有 owner/state 属性 | `LandComponent.java:17-20`、`LandAttributeKey.java:16-32` | 状态载体已存在 |
| U3 已支持 component 拆/转复制属性 | `LandComponentOperations.java` | 交易保留状态的基础已有 |
| `LandComponentLedger.replaceHoldings` 不改 components | `LandComponentLedger.java:141-167` | 市场/债务仍是 owner 聚合写口 |
| `InventoryLotView` 只读 | `InventoryLotView.java:14-22` | 还没有 stateful lot 写口 |
| `HouseholdInventory` 只有 balances | `HouseholdInventory.java:102-107` | 非土地 durable 无状态持久表 |
| `ProductionModeDefinitionView` 只有 id/name/version/classStructureId | `ProductionModeDefinitionView.java:23-24` | mode 仍是空壳；组织关系未表达 |

## 7. 实施顺序

### L1：LAND lot state + opportunity book（先做）

- `InventoryLotView` 从 `LandComponent` 读生产状态；
- `ProductionLotState` contract；
- `ProductionOpportunityBook` 本 tick 内存态；
- S3 候选从 unit 改为 land lot opportunity；
- 不改变市场/actor/durable。

### L2：LAND 执行与交易写回

- S4 从 carrier lot 读 stage/progress，扣料并写回 LandComponent；
- S5 按 lot 收获；
- `ProductionClaim` 本轮组织关系；
- 市场卖地只改 owner，状态保留；
- 删除/降级 `ProductionProcess.stageState` 的土地路径。

### L3：清理 unit 进度权威

- `ProductionData.instances` 不再保存土地进度；
- 经验/效率余数改挂家户×plan/经验条；
- 无 carrier 的短周期生产只保留瞬态 batch。

### L4：durable/stateful goods

- 工具、机器、运输马等 stateful lot 表；
- 同机会/claim/batch 机制；
- 市场交易状态随 lot。

## 8. 非目标

- 本文件不改代码；
- 不立即删除 legacy `ProductionProcess`；
- 不把普通商品逐件 lot 化；
- 不重写 S0/S2/S6/S8；
- 不在 LAND 闭环前做 durable/stateful goods；
- 不做生产进度 tick mid-cycle 换 plan；
- 不在本文件引入运行时配置文件或 MCP 工具。

## 9. 验收锚点

1. 10/3/1 面积在 InventoryLotView 中三行，A/B 状态不同，C IDLE；
2. OpportunityBook 生成三个机会，expectedInputs/Outputs 分别按 A/B/C 的 stage/progress 计算；
3. S3 可分别选择 operator；A/B/C 不共享一个 `ProductionProcess.progressDays`；
4. S4 后 A 80%→81%、B 30%→31%、C 仍 IDLE；
5. A 卖出 4,000 毫亩，买卖双方 lot 的 stage/progress 不变，数量守恒；
6. 成交后旧 claim 失效，新 owner 下一轮可从同一状态 claim；
7. HARVEST 按 lot 产出，不按 unit 平均；
8. 普通商品仍 COMPLETE，不因承载生产状态变成 lot；
9. 所有写入仍是 Command → ChangeSet → Revision；
10. 没有第二份持久 stage/progress 权威。

## 10. 用户原话附录

```text
我想说的是这样的生产方式定义很差劲。单个生产方式组织起的生产不但要有主导者家户，还是高度跟随生产资料的，这里可以沿用我之前提到的附着在库存物品上的生产状态，生产状态完全不应该单独记录，而是记录在生产资料库存产品上，例如农业耕地，10面积位于1阶段80%，3面积位于2阶段30%，1面积没有生产属性，也就是刚拿到手没有进入生产，那么就分别根据生产进度生成对应生产状态（生产投入、预估生产产出），然后再做市场预期，提供给家户投入劳动力和实际库存的真实生产位置——经过这么一轮，我发现单tick的抽象生产方式虽然和生产资料脱不开关系，但是还是有一个储存起来的内存中间态，不过总分比你现在完全独立好，调查一下
```

```text
第一点我看不懂，第二点，不允许，一个库存产品单位只能带一种单一生产方式过程属性，如果要换生产方式应当在下一轮；库存产品作为生产资料，承载的生产阶段不因交易而改变，获得的家户完全可以继续围绕其进行组织；肯定是先把land做通，再考虑其他的，不过你先回答我的问题
```

```text
你理解的对，走B，出新的架构设计文档
```

## 11. 修订记录

- 2026-10-22：新建本文，收口调查稿；确认 B：owner + production state 在 lot，operator 在 claim；一 lot 一 process；下一轮换 mode；交易不改 stage；LAND 先行。
