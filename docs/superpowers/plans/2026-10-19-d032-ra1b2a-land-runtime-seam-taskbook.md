# D-032 RA1b2a LAND 运行时接缝与原子写口 · 实现任务书（2026-10-19）

> **状态：可派单。** 前置 RA1a/RA1b1 已完成。本单是 RA1b2 的第一层：只建立**运行时会话 land 工作态、原子写口、数量型本轮取得标记**；不切 Seeder、不改债务/市场/生产语义、不删除旧 LAND block 链。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 父设计锚点与本单嵌入

- v2：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1（S1/S2/S7）、§4.1、§4.6、§5、§8、§10、§11。
- 复核：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` §2.2 E6–E10。
- RA1a/RA1b1：
  - `simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/`
  - `simos-economy/src/main/java/io/mosire/simos/economy/land/LandBook.java`
  - `EconomyData.landArableSupply()` / `.landHoldings()`
  - `.superpowers/sdd/2026-10-19-d032-ra1a-scalar-land/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b1-land-state-components/impl-ledger.md`

```text
父序列：S1 载入 LAND → S2 组织/迁移 → S3/S4 生产使用 → S7 市场/债务读写
本单：为后续 C1/C2/C3/C4 提供同一份 land 工作态与数量型 acquisition 标记；
      旧 LAND assetShares 主链在本单仍原样可用，行为不变。
```

## 2. 已核实现场

- 持久骨架已存在：`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:398-402,565-566`。
- `EconomyStateBuilder.java:676-679` 目前只把 land 两组件原样带过 base，没有工作副本。
- `SettlementDayContext.java:157-206` 只有 `LinkedHashSet<AssetShareId> landAcquiredThisRound`；`MarketRoundCore.java:161-167,668-677` 同样是 `AssetShareId` 集合。
- `EconomyDataTransforms.withLandState` 只原子写 arable+holdings（`:610-623`），但未来 Seeder/Clear 需要同时清掉旧 LAND assetShares；需要三表原子写口。
- `LandBook` 当前只暴露 `holdings()` 列表，没有稳定的 key→holding 视图；`from`/`apply` 已能守每 hex 守恒（`LandBook.java:74-95,138-168`）。
- 旧 `assetShares` 的 LAND 行仍在多个读写路径使用；本单**不切**它们。

## 3. 责任区六要素

### 3.1 责任归属

你负责建立 **LAND runtime seam**：

1. 一个不可变 `LandLedgerState`（arable supply + 可变工作副本/transfer 结果），或等价的 economy 内部纯 helper；
2. 一个 `EconomyData.withAssetSharesAndLandState(...)` 三表原子写口；
3. `EconomyStateBuilder` 的 land 工作副本与 build 透传；
4. `SettlementDayContext` / `MarketRoundCore` 的数量型 acquisition 标记；
5. 只读日视图对 land 状态的稳定访问口（可选，仅当编译/下游需要）。

### 3.2 最终能力

- 调用方可以：
  - 从 `EconomyData` 构造 `LandLedgerState`；
  - 查询某户某 hex 的可用量（持有量 − 本轮取得量）；
  - 施加一笔 `LandTransfer`，返回新账本；
  - 把所有 land 工作副本通过 `EconomyStateBuilder` 原样带到 build；
  - 在一次 `new EconomyData(...)` 中原子写入“非 LAND assetShares + arable supply + holdings”，不产生中间态。
- 旧 LAND `assetShares` 路径在本单仍可运行；新 acquisition 数量 map 与旧 `Set<AssetShareId>` 可在同一 tick 并存，但**新 map 暂不驱动任何现有业务**。

### 3.3 不可破坏机制

- 五条铁律；所有持久写仍经 EconomyData/ChangeSet/Revision。
- `landAcquiredThisRound` 是瞬态，不进 ChangeSet/Codec；数量必须按 `LandHoldingKey` 累加，不能用整 key 排除代替部分取得。
- `landHoldings` 每 hex 总量 == `landArableSupply`；LAND 不得再出现 block id/AssetShareId 语义。
- 本单不改任何现有公式/身份/排序/预算/债务/市场/production 行为。
- 不改 POM/enforcer/模块边界；不写/不改/不跑测试；不改现有未提交的他人文件。

### 3.4 可动范围

允许修改/新建：

```text
simos-economy/src/main/java/io/mosire/simos/economy/land/           // 新 helper 可放这里
simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDataTransforms.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyDayView.java
simos-economy/src/main/java/io/mosire/simos/economy/WorkingDayView.java
simos-economy/src/main/java/io/mosire/simos/economy/EconomyBoundaryView.java
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyStateBuilder.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayContext.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundCore.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundWitherBook.java
.superpowers/sdd/2026-10-19-d032-ra1b2a-land-runtime-seam/
```

禁止修改：

- Seeder/`simos-app/**`；
- Debt/Market/Production 结算逻辑（本单不切行为）；
- `EconomyChangeSet`/Codec（本单不新增持久组件）；
- `simos-production/**`；
- 任何 `src/test/**`、POM/XML、tools、docs；
- 当前脏文件：`EconomySession.java`、`LaborSurvivalBook.java`、`ProductionInputDrawSettlement.java`、`SettlementTickChainLog.java`、app test/tools。

若编译器证明必须多改一个 main 文件，先最小化并在报告中具名说明。

### 3.5 交账方式

`impl-ledger.md` 必须含：

1. S1/S2/S7 嵌入点；
2. `file:line` 调查结论；
3. land 工作态/原子写口/acquisition map 数据流；
4. 父序列影响表：顺序、预算/冻结、身份/持久化、失败/回滚、日志、测试；
5. 偏离记录；
6. 未做/未验证/blocker。

报告：改动文件、真实编译 rc、数值行为变化清单（应为空）、旧测试可能失效项、未完成项。

## 4. 冻结建议形状

### 4.1 `LandLedgerState`

内部不可变/工作副本两态均可，但必须提供等价语义：

```text
from(EconomyData) / from(LandArableSupply, Map<LandHoldingKey, LandHolding>)
holdingOf(hex, owner): long
usableQuantity(hex, owner, Map<LandHoldingKey, Long> acquiredThisRound): long
transfer(LandTransfer): LandLedgerState
holdingsByKey(): Map<LandHoldingKey, LandHolding>   // 防御/稳定序
```

- 转移走 `LandBook` 或与 `LandBook` 同一校验，禁止复制第二套每 hex 守恒公式。
- `usableQuantity = max(0, holdingOf - acquiredThisRound.getOrDefault(key, 0))`。
- 0 数量行不保留。

### 4.2 原子写口

`EconomyDataTransforms` 增加：

```text
withAssetSharesAndLandState(
    EconomyData data,
    Map<AssetShareId, OwnershipStake> assetShares,
    LandArableSupply landArableSupply,
    Map<LandHoldingKey, LandHolding> landHoldings)
```

以及 `EconomyData.withAssetSharesAndLandState(...)`。

- 必须在**一次** `new EconomyData(...)` 中交齐三表；
- 由构造器现有的 `validateLandState` 守卫双权威/守恒；
- 不改变旧 `withOwnershipStakes`/`withLandState` 的现有语义。

### 4.3 `EconomyStateBuilder`

- 增加 land 工作态 accessor（landArableSupply 只读、landHoldings 可写工作副本）；
- `build()` 使用工作副本（非 null）否则 base 值；
- 本单不把 land 工作副本接入 Seeder/债务/市场/生产；
- 工作副本仍必须通过 `new EconomyData(...)` 的构造守卫。

### 4.4 数量型 acquisition marker

在 `SettlementDayContext` 与 `MarketRoundCore` 增加：

```text
Map<LandHoldingKey, Long> landAcquiredQuantityThisRound
```

- 正值累加；0/负数拒绝；
- 保持旧 `Set<AssetShareId> landAcquiredThisRound` 不变，作为过渡；
- 在 `MarketRoundCore` 的复制/inherit 路径必须带上新 map；
- 不进入 ChangeSet/Codec，不跨 tick 持久。

### 4.5 日视图（按需）

若 `SettlementDayContext`/下游需要从 `EconomyDayView` 读 land，增加：

```text
default LandArableSupply landArableSupply() { return new LandArableSupply(Map.of()); }
default Map<LandHoldingKey, LandHolding> landHoldings() { return Map.of(); }
```

或等价接口；`EconomyData` 现有同名访问器自然满足。默认值只用于未实现视图，不得用来制造第二权威。

## 5. 验收判据（测试 owner 后续）

1. `LandLedgerState.from(EconomyData.empty())` 为空且合法。
2. 同一 hex 三 owner 持有合计 = arable；`transfer` 任意数量后守恒。
3. `usableQuantity`：原持有 100、本轮取得 30 ⇒ 可用 70；未取得 ⇒ 100。
4. 0 行/负数量/未知 hex/超额转移具名拒绝。
5. `EconomyData.withAssetSharesAndLandState` 一次构造成功；非 LAND assetShares 保留、LAND assetShares 受现有双权威守卫。
6. `EconomyStateBuilder` 的 land 工作副本经 build 往返不丢；未设置的组件回退 base。
7. `MarketRoundCore` 复制/inherit 后 acquisition map 逐值一致。
8. 本单前后现有结算行为/数值逐值不变（旧 LAND assetShares 路径未被切）。
9. `simos-economy,simos-app` main 编译绿；production 零改动。
10. 不新增持久组件，ChangeSet 组件数仍 36。

## 6. 编译与纪律

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不改/不跑测试；不跑 test/verify；不 commit/push/merge/stash/checkout；不碰主树。
- 看到别人的未提交改动不要接手、不还原、不格式化。
- 发现任务书/v2 与用户原话/五条铁律冲突 ⇒ 立即停手报告，不得自裁。
- 本单是 RA1b2 的接缝层；不要顺手切 Seeder/债务/市场/生产行为，也不要删除旧 LAND 主链。

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

- 不切 Seeder/清区；
- 不切债务/市场/production；
- 不删除 LAND `assetShares` / block API；
- 不新增持久组件；
- 不改 Codec/ChangeSet；
- 不写测试/不跑 360 tick。
