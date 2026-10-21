# D-032 C7b2 责任区任务书：lane 硬约束、运费债务与旧 haul 退役（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §3.2/§7.1–§7.4/§10/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C7
> **上游：** C1–C6、C7a、C7b1 已完成且 compile 绿；C7b1 已产出瞬态
> `ctx.transportRoundPlan.demands/commitments/observationsByShippingHex/demandSupplyByZone`。
> **本任务完成 C7；不做 C8/C9。**

---

## 0. 用户原话与冻结口径

```text
运输能力是商品撮合的前置硬约束；LaneCapacityCommitment 先于 goods matching 产生。
商品成交不得超出该 lane 已承诺的 capacityWork；运力不足的余量不成交、不记货物债、不记运费债、具名 LOGISTICS_CAPACITY。
运费由买方支付给 carrier owner household；买方现金不足的运费部分按既有“未付成交款”原则成普通债；无运力则不成债。
```

```text
同 hex 交易不产生运输需求；LAND 本身不付运费。
承运资格 = carrier.homeHex == shippingHex；禁止跨 hex 调拨。
```

冻结：

- 商品成交只能消费 `ctx.transportRoundPlan` 的本地 lane 承诺；
- 无本地承诺 ⇒ 该笔不成交、不建货债/运费债；
- 运费按 C7a 锚价（每毫 work）计算，不是旧 haul 牌价；
- 运费付给实际 carrier OWNER 户；现金不足的运费走既有普通债务口径；
- 旧 `haul` 服务库存、`CARRIER_FEE` 私有腿、`MerchantCapacityPool` 的旧生产性/服务货容量来源退出活动路径；
- C7b2 不 seed transport unit/roster，也不做 C8 马匹向上。

---

## 1. 目标运行序列

```text
S7-A 订单构建
S7-T/CAP（C7b1 已做）
  ctx.transportRoundPlan = planRound(...)   // 瞬态
S7-MATCH（本责任区）
  1. 由当前 C6 batches 构造**运输池**（唯一活动运力来源）：
       entry = (carrier household, shippingHex=homeHex, capacityWorkMilli)
       radius = 0；只允许 homeHex == demand.shippingHex
  2. MatchContext 注入：
       transportRoundPlan
       zone → anchor price（TransportPriceAnchorBook.pricePerWorkMilli）
       transportPool（替代旧 MerchantCapacityPool 的活动装配）
  3. goods/lane 撮合：
       - 只对 plan 中存在的本地 lane 使用容量
       - 成交 quantity 不得超过 lane commitment/total local capacity
       - 无容量 ⇒ 不成交、不建 debt、不记价
       - route 单位运费 = HaulService.unitFreightMilli(workPerGood, anchorPrice)
       - 服务交付不再扣 householdGoods 的 haul；它只是 work capacity 的消耗
       - 运费钱腿照既有 buyer → carrier household 路径落地；不足额走普通债务
S7-END
  - 写 MarketRoundStats.lastTransportByHex（逐 zone/shippingHex observation）
  - 用 plan 的 D/S 更新 C7a 锚价
  - 清 C6 当前市场轮批次
```

### 对父序列影响表

| 维度 | C7b2 影响 |
|---|---|
| 顺序 | S7-T/CAP 不变；matcher 读取同一 plan；S7-END 在清批次前写 stats/锚价。 |
| 预算/冻结 | 商品预算/冻结共用既有路径；新增的唯一硬约束是本地 lane 容量；无运力不成交、不成债。 |
| 身份/持久化 | `MarketRoundStats` 增加 `lastTransportByHex`（纯读数，不新增 EconomyData 组件）；transport pool/plan 仍瞬态。 |
| 失败/回滚 | lane 超卖/跨 hex/无 plan 却走运输 ⇒ fail-closed；整次 advance 丢弃。 |
| 日志 | `TRANSPORT_LANE_COMMITTED`/`TRANSPORT_LANE_REJECTED`/`TRANSPORT_FREIGHT_DEBT`；业务拒绝 INFO，契约故障 ERROR。 |
| 测试 | C7b2 验收判据交测试 owner；本任务不运行。 |

---

## 2. 责任区六要素

### 2.1 问题现场（C7b1 后）

- `ctx.transportRoundPlan` 已有需求/承诺/观察；
- matcher 仍使用旧 `MerchantCapacityPool` 装配（旧 merchant 角色 + `haul` 服务货 + 劳动/工具容量）；
- `MarketRouteMatcher` 仍用 `haulServiceAt`/`servicePriceAt` 读旧 `haul` 牌价与旧服务库存；
- `MarketTradeExecutor.deliverHaulService` 仍会扣 `householdGoods` 的 haul；
- `MarketRoundStats` 没有 `lastTransportByHex`；
- S7-END 只更新锚价/清批次，尚未把 observation 持久化到 stats。

### 2.2 责任归属

C7b2 writer 负责：
- 运输池从 C6 batches/plan 装配；
- matcher 本地 lane 硬约束；
- 锚价运费与普通运费债；
- 活动路径不再扣 haul/不再走旧生产性容量；
- `MarketRoundStats.lastTransportByHex` 持久读数；
- 旧 haul/CARRIER_FEE 活动路径退役的 main 编译修整。

不做 C8 马匹向上/混合、C9 seeder/读口/32hex、测试。

### 2.3 最终能力

1. 商品成交不超过本地 lane 承诺/批次容量；
2. 无本地运力 ⇒ 不成交、不建任何债；
3. 运费按 zone 锚价收取，付给实际 carrier owner；
4. 买方运费不足 ⇒ 普通货币债（不是货债、不是负余额）；
5. 服务交付不扣普通 haul 库存；
6. 不同 homeHex 不互相调拨；
7. `MarketRoundStats.lastTransportByHex` 可读；
8. 旧 haul/CARRIER_FEE 不再作为新世界活动路径；
9. compile 绿。

### 2.4 不可破坏机制

- 五条铁律；LAND 不进普通库存；人口只走 outbox；
- 商品/土地仍同一订单簿/同一撮合入口；
- 预算/冻结/账户仍唯一 `applyTransfer`；
- 不新增第二市场/价格权威；
- 服务不是普通商品库存；
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c7b2/impl-ledger.md`，先写目标运行段落+影响表；
报告编译、改动文件、lane 约束落点、运费债务落点、C8/C9 接口、测试失效面、未完成/blocker。

---

## 3. 冻结契约

### 3.1 运输池

新增（或扩展）活动运输池：

```text
TransportCarrierPool:
  key = (carrier household, shippingHex)
  capacityWorkMilli = Σ current C6 batch.capacityWorkMilli（该户、该 hex）
  radius = 0  // 只服务同 hex 的 from
```

- 只从当前 `marketRoundId` 的 C6 batches 构造；不从旧 merchant role/服务货/劳动+工具装配；
- 不跨 hex 合并；
- `select(from,to,quantity,workPerGood)` 只取 `from == shippingHex` 的 entry；
- 同价序：份额 desc → HouseholdId asc；份额 = entry capacity / 本 hex 总 capacity；
- 分配扣减在**本轮瞬态池**，不写 C6 batch（S7-END 统一清）；
- 无容量 ⇒ 不成交、不成债、不计价，具名 `LOGISTICS_CAPACITY`。

### 3.2 Matcher 约束

- `MarketRoundRunner` 在 C7b1 plan 回调后，用 plan/current batches 构造活动池，并从该点起只用该池；
- `MatchContext` 增加 `transportRoundPlan` + `transportAnchorPrices`（或等价只读视图）；
- `haulServiceAt(ctx, hex)` 在 plan 有该 shippingHex 本地容量时返回 true，不再读旧 haul 库存；
- `servicePriceAt(ctx, hex)` 返回该 zone 的 C7a 锚价（0 合法），不再读 `MarketTable.prices[haul]`；
- `deliverHaulService` 对 transport plan 路径**只消耗 work capacity**，不扣 `householdGoods`/不建 haul 库存、
  不记旧 service loss；仍返回实际 delivered work 供读数；
- 无 plan lane 的跨 hex 路径不得回退旧 CARRIER_FEE 或生产性运力；
- service 产出/批次仍归 operator OWNER；hired_driver 不获得 capacity。

### 3.3 运费

- 单位运费 = `HaulService.unitFreightMilli(workPerGoodPerMille, anchorPricePerWorkMilli)`；
- 实际金额按现有 `freightOf`/charge split 骨架；
- 钱腿付款人 = buyer，收款人 = 实际 carrier household，币 = 买方支付币；
- 买方现金不足：
  - 不得负余额；
  - 不足的**运费**进入普通货币债（0‰，债权人 carrier household），不是货债；
  - 无本地容量 ⇒ 整笔不成交、不建任何债；
- 不走 `CARRIER_FEE` 活动路径；`freightTransferReason` 对 plan 路径使用服务成交/MARKET_TRADE 语义；
- 旧 `haul`/`CARRIER_FEE` 可保留编译死代码，但新世界活动路径不得调用。

### 3.4 MarketRoundStats.lastTransportByHex

`MarketRoundStats` 增加纯读数字段（末尾）：

```text
Map<HexCoord, TransportHexObservation> lastTransportByHex
```

- 由 S7-END 的 `ctx.transportRoundPlan.observationsByShippingHex()` 填入；
- 空表 = 本轮无运输（不是缺数据）；
- 保留旧构造器/withRound 重载为空表，避免无声改旧调用点；
- 不新增 EconomyData 组件；随现有 `marketRoundStats` 持久化/Codec；
- `MarketRoundStats.EMPTY` 空表；`withRound` 增加带 observation 的重载，旧重载委托空表。

### 3.5 旧路径退役

- 活动 matcher 不再调用 `MerchantCapacityPool` 的旧生产性/服务货容量装配；
- `HaulService`/`CARRIER_FEE` 相关旧类可保留编译，但新世界路径不得写 `householdGoods[haul]`；
- 生产/seed 的旧 trade/haul 路径由 C9 删除；C7b2 只保证活动 matcher 不改用它；
- 若为通过 compile 必须保留旧调用点，必须用 `transportRoundPlan != null` 分支把活动路径先截住，并在账本列明残留。

---

## 4. 不变量与失败语义

1. 成交 ≤ 本地 lane 承诺/容量。
2. 无本地容量 ⇒ 不成交、不建货债/运费债。
3. 不跨 hex 调拨/汇总。
4. 运费按锚价；0 价合法。
5. 运费不足成普通货币债（不是负余额、不是货债）。
6. 服务交付不扣 haul 库存。
7. 旧 CARRIER_FEE/旧生产性池不在活动路径。
8. `MarketRoundStats.lastTransportByHex` 来自同一 plan。
9. 失败整次回滚。
10. 所有排序确定性。

负例：

- 超 lane 成交 ⇒ 红；
- 无容量仍建货债/运费债 ⇒ 红；
- 跨 hex 用批次 ⇒ 红；
- 运费负余额/货债 ⇒ 红；
- 服务交付扣 haul 库存 ⇒ 红；
- 活动路径回落旧 CARRIER_FEE/生产性池 ⇒ 红。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C7b2-01：lane 容量硬上限，超量不成交。
- A-C7b2-02：无本地容量不建任何债。
- A-C7b2-03：跨 hex 批次不参与。
- A-C7b2-04：运费按 zone 锚价（0 价合法）。
- A-C7b2-05：运费不足成普通货币债，债权人为 carrier。
- A-C7b2-06：服务交付不扣 haul。
- A-C7b2-07：`lastTransportByHex` 与 plan 一致。
- A-C7b2-08：同价份额稳定序。
- A-C7b2-09：旧活动路径不再被调用。
- A-C7b2-10：失败回滚/无半状态。

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
- C7b2 不做 C8/C9；不新增 EconomyData 组件。
- 不新增测试/不跑测试。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、真实 rc/输出、活动运输池装配、lane 约束落点、运费/债务落点、
`lastTransportByHex` 形状、旧路径残留清单、测试失效清单、未完成/blocker。

---

## 8. 非目标

- 不做 C8 马匹向上/混合拆分。
- 不做 C9 seeder/读口/32hex。
- 不做运输马匹折旧/损耗。
- 不写测试/不跑测试/不 commit/push/不碰主树。
