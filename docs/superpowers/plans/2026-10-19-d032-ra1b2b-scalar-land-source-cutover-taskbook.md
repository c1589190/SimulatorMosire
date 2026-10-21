# D-032 RA1b2b 标量 LAND 源切换 + 旧生产地权投影 · 实现任务书（2026-10-19）

> **状态：可派单。** 前置 RA1a/RA1b1/RA1b2a 已完成。本单把**新世界的 LAND 唯一来源**切到 `landArableSupply + landHoldings`，并从 `assetShares` 中彻底移除 LAND；同时给旧 D4/production 提供**只读、瞬态、不落盘**的 LAND 投影，保证本层仍能编译并让旧生产链读到土地。
> 本单**不**实现标量土地市场/债务、不删除旧的债务/市场 block 分支、不跑 360 tick；这些留给 RA1b2c/RA3。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 父设计锚点与本单嵌入

- v2：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1（S1/S2/S3/S4/S7）、§4.1、§4.6、§5、§8、§10、§11。
- 复核：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` §2.2 E6–E10。
- RA1a/RA1b1/RA1b2a 产物：
  - `simos-economy-api/.../api/land/`
  - `simos-economy/.../land/LandBook.java`、`LandLedgerState.java`
  - `EconomyData.landArableSupply()/landHoldings()/withAssetSharesAndLandState(...)`
  - `.superpowers/sdd/2026-10-19-d032-ra1a-scalar-land/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b1-land-state-components/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2a-land-runtime-seam/impl-ledger.md`

```text
父序列：S1 载入 LAND → S2 组织/迁移 → S3/S4 生产使用 → S7 市场/债务读写
本单：新世界 Seed 只产生 scalar LAND；assetShares 只保留非 LAND；
      旧 production/D4 通过瞬态投影读 LAND，不形成第二持久权威；
      市场/债务的 scalar 行为留 RA1b2c。
```

## 2. 已核实现场

- `EconomyPayloads.java:355-401` 仍把 entry.assetShares 解析成 OwnershipStake；`:645-647` 把 land 两组件硬编码为空。
- `EconomySeedHandler.java:262-265` 与 `EconomyClearRegionHandler.java:523-525` 对 land 两组件只原样带过，不合并/不清理。
- `LandOwnershipSeeder.java:77-120` 仍为 farm 生成 LAND `ownershipStakeNode`，`grainFarmUnits` 的 `shareNode` 进入 entry.assetShares；0 土地户也发 0 行。
- `ProductionRuntimeSeeder.java:183-189,312-318,344-362,526-538` 把 farm LAND 节点放进 `entry.assetShares`。
- `EconomyDataInvariants.java:304-352` 目前只在 scalar LAND 非空时拒绝 `assetShares` 的 LAND 行；RA1b2b 完成后必须无条件拒绝。
- `ProductionProcessBook.java:113-286` 通过 D4 `AssetOccupancyBudget` 给 production 提供 `Map<AssetKind,Long>`；`AssetOccupancyAllocator/*` 仍把 LAND 当作 `AssetShareId` 供给。
- `simos-production` main 只消费 `Map<AssetKind,Long>`，不引用 `AssetShareId`/`OwnershipStake`；本单不改 production。

## 3. 责任区六要素

### 3.1 责任归属

你负责完成 **RA1b2b source cutover**：

1. `economy.Seed` 支持 entry 级 scalar LAND 载荷；
2. app Seeder 写 scalar LAND，不再把 LAND 写进 `assetShares`；
3. `EconomySeedHandler` 合并 scalar LAND；`ClearRegion` 按 hex 清 arable+holdings；
4. `EconomyData` 不变量改为**无条件拒绝** `assetShares` 中的 LAND，并拒绝 LAND 出现在 `assetOccupancies`、`ProductionEnterprise.assetSources`、`ProductionParticipant.rightsStakes`；
5. 给旧 D4/production 建立**瞬态 LAND 投影**：只读 `landHoldings`，按 unit 的 `(industry, operator household, location)` 提供给 D4，合成 LAND `OwnershipStake` 只在结算内存中存在，绝不写 `EconomyData/ChangeSet/Codec/assetOccupancies`；
6. 旧 LAND block 市场/债务路径在本单**不扩展**；若它们遇到 no LAND shares，应保持空操作/具名拒绝，不得重新生成 LAND assetShares。

### 3.2 最终能力

- 新世界 `economy.Seed` 载荷显式含每个农业 hex 的 `landArableMilliMu` 与逐户 `landHoldings`；LAND 总量守恒。
- `EconomyData.assetShares` 中 LAND 计数恒为 0；`landHoldings` 是唯一 LAND 所有权数量权威。
- 清除/补种区域时 land supply/holdings 原子一致更新。
- 旧 production/D4 仍能在新世界为每个 household grain_farm unit 读到其 `(hex, owner)` 土地数量，landless unit 读到 0 并正常拒绝；没有第二个持久 LAND 表。
- 旧市场/债务 LAND 操作在新世界暂时不交易/不抵地（RA1b2c 再实现），不得崩溃、不得静默伪造 LAND assetShares。

### 3.3 不可破坏机制

- 五条铁律；所有持久状态走 ChangeSet/Revision；`landAcquiredQuantityThisRound` 保持瞬态。
- LAND 不进 `HouseholdGoods`、不再有 block id/AssetShareId/sequence；任何 `assetShares` 中的 LAND 行 = 构造期契约故障。
- D4/production 的瞬态 LAND 投影不是第二权威：只读、只在单次结算上下文内存在、不得进入 `EconomyData`、ChangeSet、Codec、`assetOccupancies`、明日报表或持久文件。
- 每 hex `Σ landHoldings == landArableSupply`；不产生 0 持有行。
- `simos-production` 不改；production 仍只收 `Map<AssetKind,Long>`。
- 不做旧档兼容；旧 LAND `assetShares` 不再合法。

### 3.4 可动范围

允许修改/新建（按实际编译需要最小化）：

```text
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataInvariants.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataStructureInvariants.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyProductionPayloads.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySeedHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyClearRegionHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyHouseholdPayloads.java（仅 LAND rights 拒绝）
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySetProductionRosterHandler.java（仅 LAND rights 拒绝）
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProcessBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/AssetOccupancyAllocator.java
simos-economy/src/main/java/io/mosire/simos/economy/time/AssetOccupancyBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/AssetOccupancyReadout.java
simos-economy/src/main/java/io/mosire/simos/economy/time/AssetOccupancyLookupImpl.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionReferenceValidation.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionRunSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayContext.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayEntryPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayClosePipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/land/（可新增只读投影 helper）
simos-app/src/main/java/io/mosire/simos/app/world/LandOwnershipSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/EconomySeederConditions.java
simos-app/src/main/java/io/mosire/simos/app/world/SeederModels.java（仅 land 节点形状）
.superpowers/sdd/2026-10-19-d032-ra1b2b-scalar-land-source-cutover/
```

禁止：

- `simos-production/**`；
- 测试、POM/XML、tools、docs；
- 债务/土地市场行为重写（RA1b2c）；
- 删除 `AssetShareId`/`OwnershipStake` 整类（非 LAND 仍用）；
- 当前脏文件：`EconomySession.java`、`LaborSurvivalBook.java`、`ProductionInputDrawSettlement.java`、`SettlementTickChainLog.java`、app test/tools。

若编译器证明需要修改清单外的一个 main 文件，先最小化并在报告中具名说明。

### 3.5 交账方式

`impl-ledger.md` 必须含：S1/S2/S3/S4/S7 嵌入点、`file:line` 调查结论、Seed 载荷/持久化/瞬态投影数据流、对父序列影响表（顺序/预算/身份/持久化/失败/日志/测试）、偏离、未做/未验证/blocker。

报告：改动文件、真实编译 rc、数值行为变化清单、旧测试失效类别、未完成项。

## 4. 冻结载荷与投影口径

### 4.1 entry 级 scalar LAND 载荷

新世界 `economy.Seed` 的每个 `entries[]` 节点新增：

```json
{
  "q": 0,
  "r": 0,
  "landArableMilliMu": 123000,
  "landHoldings": [
    {"owner": "hh-...", "quantityMilliMu": 41000},
    ...
  ]
}
```

- `landArableMilliMu`：该 hex 固定可耕总量，`>= 0`；同一 entry 只允许一个值；
- `landHoldings`：每个 owner 在该 hex 的数量，`quantityMilliMu > 0`；0 行省略；
- 同一 entry 内重复 owner 拒绝；跨 entry 的同一 `(hex, owner)` 由全局 map 再拒绝；
- `Σ quantityMilliMu == landArableMilliMu`；不满足 → fail-closed；
- 载荷仍接受空 land 字段（旧测试/非农业世界），此时 scalar 组件为空；但 `assetShares` 中 LAND 仍无条件非法。

### 4.2 Seeder 来源

- `LandOwnershipSeeder.grainFarmUnits` 的 LAND 数量继续由 `plan.capacity[LAND]` 与分配权重派生；`Σ shares == landMilliMu`；
- 每个 entry 生成 `landArableMilliMu = landMilliMu`；`landHoldings` 只写正份额（流民/0 地户不写 0 行）；
- farm LAND 不再加入 `entry.assetShares`；非农 assets（tool/fiber 等）仍照旧；
- 若 generic `ownershipStakesOf` 遇到 `AssetKind.LAND`，对 farm 以外路径也必须拒绝或过滤；新世界 `assetShares` 里 LAND 必须为 0；
- `EconomySeederConditions` 的复制/拆分若涉及 land 字段，必须同步复制或具名拒绝，不能只复制 assetShares。

### 4.3 Seed 合并/清区

- `EconomySeedHandler`：
  - 载入/append 时把 seeded `landArableSupply` 与 `landHoldings` 与 base 合并；
  - 对同一 hex 的 arable 值冲突、同一 `(hex,owner)` 重复、合并后总量不等 → 具名拒绝，不得后写覆盖前写；
  - 非 LAND `assetShares` 的 merge 语义不变。
- `EconomyClearRegionHandler`：
  - 对目标 hex 同时删除 `landArableSupply` 条目与所有 `landHoldings` key；
  - 同一次候选态构造中同时清理非 LAND assetShares/occupancies；不得留下单边 land 状态。

### 4.4 旧 D4/production 瞬态投影

允许在 `simos-economy/.../land/` 新增只读 helper（名称自定），必须满足：

```text
输入：units、landHoldings、landArableSupply、actor→household 映射、landAcquiredQuantityThisRound
输出：仅本结算用的 Map<AssetShareId, OwnershipStake> LAND 投影 + 识别集合
```

- 对每个需要 `capacityPerUnit[LAND] > 0` 的 unit：
  - 若 unit.operator actor 能解析为 household，取 `(unit.location, household)` 的当前持有；
  - 扣本轮 `landAcquiredQuantityThisRound` 后的可用数量；
  - 仅在可用数量 > 0 时生成一条投影；owner/operator = 该 household actor，industry = unit.industry，location = unit.location，kind = OWNED；
  - 合成 `AssetShareId` 只作本轮 D4 内部键，必须带明确前缀/识别集合；**绝不进入持久状态**。
- 无对应 household/无地/全被本轮取得排除 ⇒ 不生成投影，D4 正常给该 unit `LAND=0` 并具名拒绝；不得静默补 1 或伪造土地。
- D4/`SettlementIndex` 的合成输入 = `EconomyData.assetShares`（非 LAND） + LAND 投影；non-land behavior 不变。
- `AssetOccupancyBudget.grantsToWrite/grantsToRelease` 中凡指向投影 key 的 grant：
  - 不得写入 `assetOccupancies`；
  - 不得在构造新 `EconomyData` 时出现；
  - 必须在投影 helper 或 apply 阶段过滤，并有单元/计数 DEBUG 说明。
- production 仍只收到 `Map<AssetKind,Long>`，零改动。
- 该投影是**过渡桥**；RA2 用真实标准农业聚合替换它。不得给它新增 Codec/ChangeSet/持久组件。

### 4.5 引用不变量

`EconomyData` 构造期必须 fail-closed：

1. `assetShares` 中任何 `AssetKind.LAND`；
2. LAND scalar 非空且 `assetShares` 同时有 LAND；
3. `assetOccupancies` 的任何 grant 源指向 LAND（若 grant 只有 shareId，则查 share 表后判 LAND；无 share 引用则 fail-closed）；
4. `ProductionEnterprise.assetSources` 中任何 LAND share；
5. `ProductionParticipant.rightsStakes` 中任何 LAND share；
6. scalar LAND 已存在时每 hex 守恒、0 行、key/值一致。

业务拒绝须具名；不得 `ClassCastException`/NPE 当契约错误。

## 5. 验收判据（测试 owner 后续）

1. 新世界 Seed payload 的 land 分配每 hex Σ = arable，seed 后 `EconomyData.landHoldings` 正确、`assetShares` LAND=0。
2. 0 地 household 不产生 `LandHolding` 0 行；仍有 unit，生产侧读到 LAND=0。
3. ClearRegion 后目标 hex 的 supply/holdings 全部删除，非目标保留，LandBook 仍守恒。
4. Seed append 同一 hex 冲突/重复 owner/总量不等 → 具名拒绝。
5. 构造期拒绝 `assetShares` LAND、LAND grant、LAND assetSource、LAND rightsStake。
6. 旧 D4 production 在新世界单 tick：有地户得到 LAND allocation，landless 户得到具名拒绝/0；无 LAND grant 进入 `EconomyData`。
7. `Map<AssetKind,Long>` 仍传 production；`simos-production` main 未改。
8. 旧市场/债务遇到空 LAND assetShares 不崩溃、不伪造；land 交易/抵地行为留待 RA1b2c。
9. 全仓 main 编译绿；ChangeSet 组件数仍 36。
10. 现有非 LAND assetShares 行为逐值不变。

## 6. 编译与纪律

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不改/不跑测试；不跑 test/verify；不 commit/push/merge/stash/checkout；不碰主树。
- 看到别人的未提交改动不要接手、不还原、不格式化。
- 本单不实现 RA1b2c；如果债务/市场旧链因 LAND 缺失无法编译，优先做**最小具名拒绝/空操作**，不要顺手写新玩法。
- 发现设计冲突 ⇒ 停手报告，不得自裁。

## 7. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-19-d032-rural-production-land-labor-revision.md`，2026-10-19。

```text
5 是的，目前不做土地池，土地实际上只是一种无法生产、开局固定数量、和单个hex绑定的不动商品
我来说一下土地的数据结构吧，作为商品的土地记录在库存中，实际上写的是，什么hex的土地，例如一个家户有0,0的土地X亩，0,1的土地Y亩，在还债的时候如果排到土地，也是还具体hex的土地，实际可以通过罗列穷举推算出每一个hex固定的耕地量
共享收入这块完全不应该存在，生产关系影响收入在现在就是，在当前hex有土地的人按照土地多少获取对应耕地的粮食，没有土地的雇农只能获得雇佣者支付的钱或者债——这很不工业化思维，也无法直接体现租地，但是事实上租地种的环节已经被拆成两部分，首先是事实上的租地，没有地的家户可以在市场轮用债务换取土地，然后作为“小农”加入当地hex的生产，所以很合理，不需要额外增加环节
土地价为0就不能转移了吗？
你的意思是，买完地之后，下一轮生产规划里这个不作为可用于生产的条件？太逆天了
LAND在市场上不应该是一个标量的标准商品吗，想扣多少扣多少，为啥要引入块
P0更是逆天，地不是在具体的家户手上吗
```

## 8. 明确非目标

- 不实现标量土地市场订单/成交（RA1b2c）；
- 不实现标量土地抵债数量反解（RA1b2c）；
- 不删除旧 MarketLand*/DebtFirstSettlement（RA1b2c）；
- 不实现标准农业 S3–S6 聚合（RA2）；
- 不改 production 引擎/契约；
- 不更新全部 GUI/工具读口（RA4）；
- 不写测试/不跑 360 tick。
