# D-032 生产资料承载生产状态 L1 实施计划（2026-10-22）

> 状态：L1 实施派单；未改代码、未提交。
> 架构入口：`docs/superpowers/specs/2026-10-22-d032-lot-carried-production-architecture-v1.md`。
> 纪律：一个责任区一个写代码 owner；写者只写到编译过，不写/不跑测试；测试最后独立 owner。
> L1 只做 LAND；不碰 durable/stateful goods、不删除 `ProductionProcess`、不改市场。

## 1. L1 目标

让真实库存视图能读出土地 lot 的物理生产状态：

```text
LandComponent A: 10,000 毫亩，stage1 80%
LandComponent B:  3,000 毫亩，stage2 30%
LandComponent C:  1,000 毫亩，IDLE
```

`InventoryLotView` 三行，不再全部是 IDLE；随后从 lot 派生 `ProductionOpportunityBook`。

## 2. L1a：LAND lot state 读入

### 允许文件

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/LotState.java
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryLotViewBuilder.java
```

### 形状

`LotState.InProduction` 增加：

```text
startInputPaid: boolean
cycleInputUsedMilli: Map<CommodityId, Long>
```

保留旧 5 参构造器/静态工厂，默认 `false` + `Map.of()`，避免旧调用/测试编译失败。

### Builder 规则

- LAND component 有 `CROP` 或 `STAGE` ⇒ 解析为 `InProduction`：
  - `CROP` → planId（STRING，必填）
  - `STAGE` → stageId（STRING，必填）
  - `PROGRESS` → stageProgressDays（LONG，缺省 0）
  - `CYCLE_PROGRESS` → cycleProgressDays（LONG，缺省 0）
  - `START_INPUT_PAID` → startInputPaid（BOOLEAN，缺省 false）
  - `cycleInputUsedMilli`：L1 暂为空 map（Map 属性没有多商品表；后续另设计）
- 完全没有 CROP/STAGE ⇒ `IDLE`；
- 只有其中一个/状态不完整 ⇒ 具名 `inventory-lot-state-incomplete`；
- 属性类型不符 ⇒ 具名保留原异常上下文；
- 不写 Map；不读时钟/随机数；不改变 commodity 行。

### 验收

- 10/3/1 三行状态正确；
- 无状态 land 仍 IDLE；
- 缺 CROP/STAGE 的半个状态具名拒；
- commodity 行不变；
- 旧 `LotState.inProduction(...)` 调用仍编译。

## 3. L1b：ProductionOpportunity / OpportunityBook（LAND）

### 允许文件（新增）

```text
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionOpportunity.java
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionOpportunityBook.java
```

`opportunityId` 先用稳定 String（`lotId|planId`），后续若需要专用 ID 再加类型。

`ProductionOpportunity`：

```text
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

`ProductionOpportunityBook`：

```text
from(InventoryLotView, Map<IndustryId, IndustryDefinitionView>, Function<CommodityId, OptionalLong> price)
```

规则：

- 只处理 `InventoryLotKind.LAND`；
- `IDLE` lot 生成“从第一段新开”的机会；
- `InProduction` lot 生成“继续当前阶段”的机会；
- `expectedInputs` = 当前 stage 剩余 start/tick + 后续 stages 的 start/tick（per lot scale）；
- `expectedOutputs` = 最后 HARVEST output（per lot scale）；
- `expectedNet`/`marketValue` 由注入价格纯派生，缺价时 empty/0，不伪造；
- 机会不落 ChangeSet/Codec，不带 operatorHousehold；
- 一个 lot 只产生一个“继续”机会；不允许同一 lot 多 process。

### 验收

- 10/3/1 生成三条机会；
- A/B 预计投入按各自 stage/progress 不同；
- C 按完整周期；
- 价格缺失时 marketValue empty；
- 机会簿顺序稳定。

## 4. L1c：S3 只读接入（后续）

- `ProductionProspectBook`/`SettlementDayContext` 读 opportunity book；
- 本步只读派生，不替换现有 candidate/engine；
- 不改 S4/S5；
- 不改 `ProductionProcess`。

## 5. L1 做完后的边界

- S3/S4/S5 仍走现有 unit 引擎，直到 L2；
- `ProductionProcess.stageState` 仍是临时桥；
- 市场/land writeback 不动；
- durable 未做；
- 测试由独立 owner 补；写者只编译。
