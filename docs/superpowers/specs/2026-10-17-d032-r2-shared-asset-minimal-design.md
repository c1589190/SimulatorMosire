# D-032 R2 共享资产重复占用：最小 D4 约束设计（2026-10-17）

> **性质**：U22“先修 R2”所需的**最小 D4 约束设计**，不是完整 D4，不是实现派单，不改 Java/XML/POM/测试，不修 R2 本身。
> **输入**：`2026-10-17-d032-final-independent-review.md` R2；`2026-10-17-d032-d3-integration-precheck.md` §5；`2026-10-17-d032-wage-idle-barter-decision.md` U23；当前侧线源码 `0128b0cc`。
> **用户口径**：U22「我决定先修bug」；U23 的模块边界不改变 R2 优先级。本文的“裁决”均为控制方工程设计，不是新玩法裁定。

## §1 现状事实（回代码核过）

| # | 位置 | 事实 | 影响 |
|---|---|---|---|
| 1 | `simos-economy/.../time/SettlementIndex.java:353-382` | `usableAssets(units, assetShares)` 按 `AssetScope(industry, operator)` 把同一份份额聚合后，**复制给 scope 内每个 unit** | 同 scope 两个 unit 各拿整份资产 |
| 2 | `simos-economy/.../time/ProductionProcessBook.java:46-55`、`:63-65`、`:96-110` | `usableAssets(unit, shares)` / `usableAssets(unit, index)` / `capacityScaleOf` 都直接读该 unit 的“可用资产表” | 表若是整池复制，规模通道也整池复制 |
| 3 | `simos-economy/.../time/EconomySettlement.java:4274-4283` | `driveProductionDay` 把 `ProductionProcessBook.usableAssets(unit, settlementIndex)` 原样放进 `ProductionDayRequest.Step.assetOccupancy` | 同一份资产进入两个实例的实际执行 |
| 4 | `simos-economy/.../time/LaborQueueBook.java:425`、`:595`；`ExpectedProfitBook.java:430`；`HouseholdClassRule.java:563-564`；`MarketSettlement.java:9772`；`DebtCapacityBook.java:442`；`EconomyEnterpriseSettlement.java:599` | 预计、排队、执行、读口、债务容量各自读 “usable assets” | 只修 `SettlementIndex` 不够，必须统一消费面 |
| 5 | `simos-economy/.../model/OwnershipStake.java:35-42` | 份额有 `industry/asset/owner/operator/quantity/kind`，**没有 location/占用期** | 没有足够事实做“按地点/按真实需求”的拆分 |
| 6 | `simos-production/.../contract/ProductionProcess.java:75-82` | 实例自带 `location`，但资产份额没有地点 | 不能从 `(industry, operator)` 直接推出唯一地点 |
| 7 | `simos-production/.../contract/ProductionExecutionRequest.java:37-49`、`ProductionProbeResult.java:38` | 执行请求收 `assetOccupancy`；试算回执有 `assetRequired` | 预计/实际必须读同一份占用预算，不能各算一套 |

**R2 复现**：同一共享方案、同一经营者的两个 unit；只有 `LAND=100` 一条份额；当前 `ProductionProcessBook` 给两个 unit 各返回 `LAND=100`，合计 200。

## §2 最小裁决：共享 scope 先 fail-closed，不伪造等分

### 2.1 为什么不做临时等分

在现有事实下，`(industry, operator)` 是唯一资产作用域，份额没有地点/占用期，unit 也没有“本 tick 要多少资产”的独立预算输入。此时把 100 土地按 unit 等分成 50/50，会发明一条没有用户口径和地点事实支撑的分配政策；按需求比例拆也没有可信需求输入。两者都会把“防止重复占用”变成新的隐性玩法。

因此 R2 最小修复采用**安全收口**：

```text
scope 内恰好 1 个 unit  ⇒ 沿用现有整池可用资产（单实例行为逐值不变）
scope 内 >1 个 unit     ⇒ 该 scope 的资产预算 = 0
                          所有 unit 得到具名拒绝 asset-scope-shared:awaiting-D4
                          unallocated 保留全部数量（只读，不搬走、不改 OwnershipStake）
```

这不是“R2 已完成/多实例生产已支持”，而是**不再把同一份资产复制给多个实例**。完整 R2 的实例/地点实占、占用期、释放和统一预算由 D4/A 后续关闭。

### 2.2 唯一纯函数

新增 economy 内部纯计算（类名由实现定，语义面如下）：

```java
record AssetScope(IndustryId industry, ActorRef operator) {}
record AssetBudget(
    Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
    Map<AssetScope, Map<AssetKind, Long>> unallocated,
    Map<ProductionUnitId, String> rejections) {}

AssetBudget AssetBudgetBook.allocate(
    Map<ProductionUnitId, ProductionProcess> units,
    Map<AssetShareId, OwnershipStake> shares)
```

规则：

1. 按 `(unit.industry(), unit.operator())` 分组；unit 按稳定 id 升序。
2. 每个 scope 汇总全部匹配 `(share.industry, share.operator)` 的份额数量，按 `AssetKind` 求和。
3. scope 有且仅有 1 个 unit：该 unit 得到整份汇总表；`unallocated` 该 scope 为空。
4. scope 有 >1 个 unit：每个 unit 的分配表为空；`unallocated[scope]` = 汇总表；每个 unit 的 `rejections[unit]` = `asset-scope-shared:awaiting-D4`。
5. 没有匹配 unit 的份额：保留在 `unallocated`（读口可见），不删除、不改账。
6. 同一 scope 的 unit 集合顺序只影响遍历，不影响结果；不得用 HashMap 迭代序做并列规则。
7. 该函数不写任何状态，不产生第二份资产账；每次 revision 从 `units + assetShares` 重新派生。

### 2.3 统一消费面

`SettlementIndex` 改为持有一次 `AssetBudget` 结果；所有“可用资产”读取必须走同一份结果：

- `ProductionProcessBook.usableAssets(unit, index)` / `capacityScaleOf(unit, industry, index)` 读分配表；
- `LaborQueueBook` 的 probe/maxAbsorbable 读分配表；
- `ExpectedProfitBook` 的 `productionProspect`/existing-unit 估价读分配表（不得再直接扫 `assetShares`）；
- `EconomySettlement.driveProductionDay` 的 `assetOccupancy` 读分配表；
- `HouseholdClassRule`、`DebtCapacityBook`、`MarketSettlement` 等现有 `usableAssetsOf` 消费者读同一份；
- app 只读视图读 `AssetBudget` 的 allocations/rejections/unallocated，不得自己按 scope 现扫；
- `ProductionProcessBook.usableAssets(unit, assetShares)` 的“直接扫份额”重载只能服务**单 unit 夹具/无索引窄入口**；生产日、预计、排队、执行、读口不得使用它。实现架构账本必须逐调用点列出；若无法证明单 unit，必须改用索引版。

`ownershipStakeIdsByProcess` 仍可保留“scope 全量份额 id”供退出/迁移使用，但它不是容量/可用量；不得与 `AssetBudget` 混用。

### 2.4 拒绝边界

- **预计/排队**：unit 有 `asset-scope-shared:awaiting-D4` ⇒ probe/offer 记为不可行；不进入劳动分配，不产生“预计能产”的候选。
- **执行**：若运行期仍把共享 scope 的 unit 送进 `driveProductionDay`，必须在**进度、经验、消耗、实际劳动写入之前**业务拒绝；本日不推进该 unit、不产生产出，记录 INFO 具名原因。
- **创建/组织**：同一 `(industry, operator)` 已有一个 unit 时，不得自动创建第二个同 scope unit；显式创建/种子若形成共享 scope，允许创建但后续执行具名拒绝，或由创建命令直接拒绝。最小批至少保证“不会因为创建而复制资产”。
- **单实例**：行为逐值不变；不得因为 R2 收口把单 unit 的资产也清零。

## §3 不变量

1. **R2-I1**：每个 unit 的每种 `AssetKind` 分配量 ≥ 0；同一 scope 的 `Σ allocations ≤ Σ shares.quantity`。
2. **R2-I2**：共享 scope 不得有正分配；`allocations` 为空、`unallocated` 等于汇总、`rejections` 具名。
3. **R2-I3**：单 unit scope 的分配逐值等于现有 `usableAssets(unit, shares)`。
4. **R2-I4**：预计、排队、执行、读口、债务容量使用同一份 `AssetBudget` 结果；不得各自现扫。
5. **R2-I5**：`OwnershipStake` 数量、owner/operator/kind 一字不改；`AssetBudget` 不落持久态。
6. **R2-I6**：共享 scope 的拒绝发生在产生产出/进度/经验/实际劳动之前；不得半批推进。
7. **R2-I7**：`unallocated` 只读可见，不静默丢弃、不自动转给任一 unit。
8. **R2-I8**：稳定序为 `(industry, operator, unitId, assetKind)`；无随机/时钟/UUID。
9. **R2-I9**：R2 最小修复不宣称完整 R2/D4 已关闭；多实例共享资产仍具名未支持。

## §4 验收判据

### 4.1 正向

| # | 判据 | 预期 |
|---|---|---|
| P1 | 单 unit、`LAND=100` | 该 unit 得到 100；与修改前逐值相同 |
| P2 | 同 scope 两 unit、`LAND=100` | 两个 unit 的 allocations 都为空；`unallocated=100`；两个 unit 都具名 `asset-scope-shared:awaiting-D4`；不再出现 100+100 |
| P3 | 两 unit 不同 `(industry, operator)`，各有 100 | 各得自己的 100；互不影响 |
| P4 | 共享 scope 的预计/排队 | 不产生可生产 offer；无劳动分配 |
| P5 | 共享 scope 的实际执行 | 无产出、无进度、无经验、无实际劳动消耗；INFO 具名 |
| P6 | app/readout | 显示 allocations/unallocated/rejections，不把整池复制成每 unit 一份 |
| P7 | 重复运行 | 同输入同输出；稳定序；`AssetBudget` 纯函数可复算 |

### 4.2 负向/变异

| 变异 | 应红 |
|---|---|
| 把整池复制回每个 unit | P2/P4/P5 |
| 只修 `SettlementIndex`，`ExpectedProfitBook`/`LaborQueueBook` 仍直接扫份额 | P4（预计/实际不一致） |
| 共享 scope 仍给正分配 | P2/P5 |
| 单 unit 被误清零 | P1 |
| 执行在 progress/experience 之后才拒绝 | P5（半状态） |
| `unallocated` 静默丢弃 | P2/P6 |
| 用 HashMap 遍历决定 unallocated/拒绝 | P7 |
| 把 R2-min 写成完整 R2 已关闭 | 文档验收红 |

## §5 文件所有权与批次

| 批次 | owner 可写 | 禁止/边界 |
|---|---|---|
| **R2-D0** | 本文（控制方） | 不写业务/测试 |
| **R2-I（economy）** | `simos-economy/.../time/SettlementIndex.java`、新增 `AssetBudgetBook.java`/`AssetBudget.java`（或同义内部类型）、`ProductionProcessBook.java`、`ExpectedProfitBook.java`、`LaborQueueBook.java`、`HouseholdClassRule.java`、`DebtCapacityBook.java`、`EconomyEnterpriseSettlement.java`、`EconomySettlement.java`、`MarketSettlement.java`；必要时 `simos-app` 只读视图 | 不写测试；不修 D3/工资链；不改 POM/XML/enforcer；不直接改 `OwnershipStake` 数量；不新增持久资产预算组件 |
| **R2-T（独立测试）** | `simos-economy/src/test/**`、`simos-app/src/test/**` | 按 §4 判据，不按实现反推；含原复现、负向/变异、单实例回归；不由实现 owner 写/跑 |
| **后续 D4/A** | 完整实例/地点占用、占用期、释放、统一预算 | 不在 R2-I 冒充完成 |

## §6 明确不做 / 未验证

- 不实现完整 D4 的实例/地点占用、占用期、释放、多实例实际分配；本轮只阻止重复占用。
- 不新增资产预算持久状态；`AssetBudget` 是 revision 内派生值。
- 不修改 `OwnershipStake` 的 owner/operator/quantity/kind。
- 不修 D3 工资时间尺度、idle、报价闭环；它们排在 R2 验收后。
- 未改业务/测试、未编译、未跑 Maven/测试/仿真；未提交/合并/推送/同步。
- 本设计通过前不派 R2-I；实现 owner 只到编译，测试归独立 owner。
