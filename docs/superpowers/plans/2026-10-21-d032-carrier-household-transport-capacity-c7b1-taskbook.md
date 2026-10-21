# D-032 C7b1 责任区任务书：运输需求计划、lane 承诺与本地观察（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §3.2/§7.1–§7.4/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C7
> **上游：** C1–C6、C7a 已完成且 compile 绿；C6 有 `transportCapacityBatches` 与 `marketRoundIdOf`；
> C7a 有 `TransportPriceAnchorBook.recordMarketRound`。
> **拆分说明：** C7b1 做需求派生/本地分配计划/本地观察/锚价接线；
> C7b2 做商品 matcher 强制使用 lane、运费支付/债务、旧 haul 退役、`MarketRoundStats.lastTransportByHex`。

---

## 0. 用户原话与冻结口径

```text
同 hex 交易不产生运输需求；LAND 本身不付运费。
shippingHex = 商品订单的发货 hex（order.fromHex），不是承运商 homeHex 的倒推。
承运资格 = carrier.homeHex == shippingHex。
禁止跨 hex 调拨、跨 hex 汇总后异地使用运力。
D/S 按市场区汇总；本地未服务/未售容量不得先互相抵消。
```

冻结：

- 需求只从跨 hex 商品订单派生；
- lane 分配只读当前 `marketRoundId` 的 `transportCapacityBatches`；
- 每次分配只允许 `batch.key.homeHex == shippingHex` 的本地批次参与；
- 同一批次容量可在本 hex 多个目的地 lane 间共享，扣完即止；
- 本任务只**计划/观察/更新锚价**，不改变商品成交、不收费、不建债务；
- C7b2 才把 `ctx.transportRoundPlan` 作为商品 matcher 的硬约束。

---

## 1. 目标运行序列

```text
S7-A 订单构建完成（ctx.marketRound 已装配）
S7-T/CAP（本责任区，唯一新增阶段）
  TransportDemandBook.planRound(ctx, round, transportCapacityBatches)
    1. 对每个市场区/商品，聚合 buy order deliverTo / sell order dispatchFrom
    2. 只取 shippingHex != arrivalHex
    3. demandWorkMilli = CapacityDemand.workMilliOf(quantity, existing work factor)
    4. 按 work desc → arrivalHex → commodity 稳定序处理 lane
    5. 对每条 lane，只取 (marketZone, homeHex=shippingHex, marketRoundId=current)
       的 C6 批次；按可售份额 desc → HouseholdId asc 扣减临时 remaining
    6. 产出 LaneCapacityCommitment；容量扣完即止
    7. 产出逐 (zone, shippingHex) TransportHexObservation
    8. plan 存 ctx.transportRoundPlan（瞬态，不进 EconomyData/ChangeSet）
S7-MATCH（C7b2 后续）
  ← 只读 ctx.transportRoundPlan；本任务不改 matcher
S7-END（本责任区接线）
  对每个 zone：TransportPriceAnchorBook.recordMarketRound(sheet, view, zone,
    currentMarketRoundId, D_zone=Σdemand, S_zone=Σoffered)
  写 observation INFO/DEBUG
  然后 C6 clearCurrentMarketRound 原顺序不变
```

---

## 2. 责任区六要素

### 2.1 问题现场（C7a 后，file:line 已核）

- C6 `TransportCapacityBatchBook` 已能按 `(household, homeHex, marketRoundId)` 读批次；
- C7a `TransportPriceAnchorBook` 已能按 zone 记录 D/S 更新锚价；
- `SettlementDayContext` 还没有运输轮计划字段；
- `MarketRound` 有买卖订单，但没有运输需求/lane 计划；
- C7b2 的商品 matcher 还没有 lane 约束。

### 2.2 责任归属

C7b1 writer 负责：
- 运输需求 key/value、lane 承诺、观察类型；
- 从当轮订单派生需求计划；
- 本地容量分配算法（不扣 C6 持久批次）；
- `ctx.transportRoundPlan` 瞬态接线；
- S7-END 锚价更新 + 观察日志；
- 不负责 matcher/运费/债务/旧 haul 退役。

C7b2 负责：
- matcher 只消费 plan 中的 lane 承诺；
- 运费支付、普通货币债；
- `MarketRoundStats.lastTransportByHex` 读口；
- 旧 haul/CARRIER_FEE/MerchantCapacityPool 退役。

### 2.3 最终能力

1. 跨 hex 商品订单可派生 `TransportDemand`；
2. 同 hex 不派生；LAND 不在本层派生；
3. lane 只从 `batch.homeHex == shippingHex` 的本地 C6 批次分配；
4. 一个批次可跨多个 lane 共享容量，扣完即止；
5. 产出 `LaneCapacityCommitment` 和 `TransportHexObservation`；
6. D/S 按 zone 汇总，不先做本地净额抵消；
7. S7-END 更新 C7a 锚价后清 C6 批次；
8. plan 瞬态，不新增 EconomyData 组件；
9. compile 绿。

### 2.4 不可破坏机制

- 不跨 hex 调拨/汇总；
- 不从承运商 homeHex 反推 shippingHex；
- 不改商品成交/预算/冻结/运费；
- 不扣 C6 持久批次（只做计划期 remaining 投影）；
- 不新增第二个市场/价格权威；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c7b1/impl-ledger.md`，先写目标运行段落与影响表；
报告编译、改动文件、实际类型/算法、C7b2 消费接口、测试失效面、未完成/blocker。

---

## 3. 冻结数据形状

建议包/名（账本记录实际）：

```text
TransportDemandKey {
  MarketZoneId marketZone,
  HexCoord shippingHex,
  HexCoord arrivalHex,
  CommodityId commodity
}

TransportDemand {
  TransportDemandKey key,
  long quantityMilli,
  long workMilli
}

LaneCapacityCommitment {
  TransportDemandKey demandKey,
  TransportCapacityBatchKey batchKey,
  HouseholdId carrierHousehold,
  long committedWorkMilli
}

TransportHexObservation {
  MarketZoneId marketZone,
  HexCoord shippingHex,
  long marketRoundId,
  long demandWorkMilli,
  long offeredWorkMilli,
  long committedWorkMilli,
  long unservedWorkMilli,
  long unsoldWorkMilli,
  long anchorPricePerWorkMilli
}

TransportRoundPlan {
  long marketRoundId,
  Map<TransportDemandKey, TransportDemand> demands,
  Map<TransportDemandKey, List<LaneCapacityCommitment>> commitments,
  Map<HexCoord, TransportHexObservation> observationsByShippingHex,
  Map<MarketZoneId, long[]> demandSupplyByZone,  // [D,S]
  long totalDemandWorkMilli,
  long totalOfferedWorkMilli,
  long totalCommittedWorkMilli
}
```

- 所有 map/list 保序不可变；
- 所有数量 ≥ 0；
- key 的 zone 与 hex 必须属于该 zone；
- 同一 demand 可有多条 commitment（多个 carrier 批次），总承诺 ≤ demand.work；
- 同一批次多 lane 的承诺总和 ≤ batch.capacityWorkMilli；
- 同一 `(zone,shippingHex)` 只有一条 observation；
- `anchorPricePerWorkMilli` 读取 C7a 当前价（0 合法）。

### 3.1 需求派生

- 输入：`ctx.marketRound` 的当轮买/卖订单（pre-match 快照）；
- 对每个 `zone + commodity`：
  - 按 `sell.dispatchFrom` 聚合 `sell.sellable`；
  - 按 `buy.deliverTo` 聚合 `buy.quantity`；
  - 对每个 `shippingHex != arrivalHex` 生成需求：
    `quantityMilli = min(可用卖量, 买入量)`（确定性、不重复扣同一卖量；具体分配由 lane 阶段限容）；
  - `workMilli = CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille)`；
- `shippingHex = sell.dispatchFrom`；`arrivalHex = buy.deliverTo`；
- 同 hex 不生成；LAND 不在本层生成；
- 无法解析 zone/homeHex 的订单跳过并 DEBUG 记录，不伪造。

### 3.2 Lane 分配

- 只读 `sheet.transportCapacityBatches()` 中 `marketRoundId == current` 的批次；
- 候选批次：`zone == demand.zone && homeHex == demand.shippingHex`；
- 批次稳定序：可售份额 desc → `household` asc；
- lane 稳定序：`workMilli` desc → `arrivalHex` → `commodity`；
- 每条 lane 按稳定序取批次 remaining，`committed = min(remaining, demandRemaining)`；
- 一个批次可在本 hex 多个 lane 共享，remaining 扣到 0；
- 不修改 C6 持久批次，只维护计划期 `Map<TransportCapacityBatchKey,Long> remaining`；
- 超出容量的需求留在 `unservedWorkMilli`；
- 未承诺容量留在 `unsoldWorkMilli`；
- 不得把不同 homeHex 的批次合并/调拨。

### 3.3 观察/锚价

- 对每个 `(zone, shippingHex)` 生成 observation；
- `D_zone = Σ demands.workMilli`；`S_zone = Σ 当前轮所有本地 batches.capacityWorkMilli`；
- S7-END 对每个 zone 调 C7a 的 `recordMarketRound`；
- 不得把本地 unserved/unsold 先抵消；
- 观察只用于日志/后续 C7b2 持久 `MarketRoundStats.lastTransportByHex`；本任务不新增组件。

### 3.4 瞬态接线

- `SettlementDayContext` 新增 `TransportRoundPlan transportRoundPlan`（瞬态，不进 Codec/ChangeSet）；
- `executeMarketRound` 在 `ctx.marketRound = preparedRound` 之后、matcher 之前调用 plan；
- 若 `marketTrigger == NONE` 或无订单 ⇒ `TransportRoundPlan.empty(marketRoundId)`；
- S7-END 用同一个 plan 更新锚价，更新后仍按 C6 原顺序清批次；
- 失败：契约故障 fail-closed；整次 advance 丢弃。

### 3.5 C7b2 消费接口

C7b2 只读：

```text
ctx.transportRoundPlan.demands
ctx.transportRoundPlan.commitments
ctx.transportRoundPlan.observationsByShippingHex
ctx.transportRoundPlan.demandSupplyByZone
```

不得重新派生需求/复制 lane 分配算法；matcher 只能消费承诺，不得超出。

---

## 4. 不变量与失败语义

1. 同 hex 零需求；LAND 零需求。
2. shippingHex 来自 sell.dispatchFrom，不是 carrier.homeHex。
3. 批次资格 = zone + homeHex=shippingHex + 当前 marketRoundId。
4. 不跨 hex 调拨/汇总。
5. 一个批次跨 lane 总承诺 ≤ capacity。
6. D/S 按 zone 汇总，不先净额抵消。
7. plan 瞬态，不新增 EconomyData 组件。
8. S7-END 先更新锚价再清批次。
9. 分配稳定序确定。
10. 失败不半写。

负例：

- 同 hex 生成需求 ⇒ 红；
- 用 carrier.homeHex 反推 shippingHex ⇒ 红；
- 汇总不同 homeHex 批次 ⇒ 红；
- 批次超卖 ⇒ 红；
- 本地 unserved/unsold 先抵消 ⇒ 红；
- 改 matcher/收费/旧 haul 退役 ⇒ 越界。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C7b1-01：同 hex 不生成需求；跨 hex 生成。
- A-C7b1-02：shippingHex 来自卖方发货格。
- A-C7b1-03：不同 homeHex 批次不互相调拨。
- A-C7b1-04：一个批次多 lane 共享不超卖。
- A-C7b1-05：D/S 按 zone 汇总。
- A-C7b1-06：observation 逐 shippingHex 正确。
- A-C7b1-07：S7-END 锚价更新后批次清零。
- A-C7b1-08：plan 不落盘/不进 ChangeSet。
- A-C7b1-09：C7b2 只读同一 plan。
- A-C7b1-10：失败不半写。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire/.claude/worktrees 之外
docs/**（除 `.superpowers` 账本）
```

- 约 316 项前序未提交改动；只改本责任区 main 文件，不回滚/格式化。
- 不改 matcher/运费/债务/旧 haul；C7b2 才做。
- 不新增测试/不跑测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、实际类型/算法、`transportRoundPlan` 接线位置、C7b2 消费接口、
测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不做 C7b2 的 matcher 强制、运费支付/债务、旧 haul 退役、`MarketRoundStats.lastTransportByHex`。
- 不做 C8 马匹向上/混合拆分。
- 不做 C9 seed/读口/32hex。
- 不写测试/不跑测试/不 commit/push/不碰主树。
