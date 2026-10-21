# RC8a 任务书：运输服务成市/交付契约闭合

> **状态：约束设计书/实现任务书；只写生产代码到 `-DskipTests compile` 通过。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1.3、§5、§8.10；
> 本文：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4 RC8a。
> 本责任区是后续 360 数据可解释性的硬前置。

---

## 1. 目标

修复当前 32hex 世界里的运输契约断裂：

```text
MerchantCapacityCore 给出 productive fallback 运力
→ MarketShipmentBook 交付时仍强制扣 haul 服务货
→ 服务货为 0
→ HAUL_SERVICE_DELIVERY_FAULT
→ 该笔成交作废
→ 市场 unfilled / 家户断粮
```

本单必须做到：

1. **服务市场只在“该 hex 当前确实有可交付 haul 服务货”时成立**；
2. 没有 haul 服务货的 hex 走父 v2 的**简单生产性运力 + CARRIER_FEE**路径；
3. 不再允许“服务成市 + productive fallback + 交付强制 haul 商品”的混合态；
4. 若仍出现 `HAUL_SERVICE_DELIVERY_FAULT`，不得只打 ERROR 后 `status=OK`：
   - 优先修到 0；
   - 若仍有具名契约故障，必须 fail-closed，让 advance 拒绝。

---

## 2. 当前事实（file:line）

- `MerchantCapacityCore.java:108-151`：
  - `servicePriced` 时 `effectiveMilli = max(serviceAvailableMay, productiveMilli)`；
  - `serviceAvailable=0` 仍可用 labor/tool fallback 造运力；
  - 同时把 `workBudgetMilli = effectiveMilli`。
- `MarketShipmentBook.java:295-330`：
  - 对 `allocation.choices()` 的每一条 `consumedWorkMilli` 调 `consumeForLoss(..., HAUL_COMMODITY, service)`；
  - 取不到就 `ERROR HAUL_SERVICE_DELIVERY_FAULT` 并 `return -1L`。
- `MarketRoundRunner.java:600-615`：
  - 只汇总 `HAUL_SERVICE_DELIVERY_FAULTS` ERROR，不抛异常。
- `ShipmentSettlement.java:204-213`：
  - `haulServiceHexes(EconomyDayView)` 只按 `HaulService.pricedAt`（市场有 haul 价）判断成市格。
- `SettlementDayMarketPipeline.java:115`：
  - `ctx.haulServiceMarketHexes = haulServiceHexes(ctx.dayView)`。
- 当前 32hex 市场表有 `haul` 牌价，但 31 个农村 hex 没有 `trade` / haul 生产链；
  `ProductionRuntimeSeeder.java:204-205` 的 trade 只在 `hasCraft`（有城镇人口）时创建。

### 2.1 修复前基线（最终 360）

```text
HAUL_SERVICE_DELIVERY_FAULT = 16,271（72/72 个市场日）
HAUL_SERVICE_SETTLED：72 天，produced/delivered/expired 全 0
MARKET fills=3,380 / unfilled=21,987（槽位计数）
```

---

## 3. 本单采用的目标语义

### 3.1 服务成市判据

`haulServiceHexes` 必须同时满足：

```text
① 该 hex 的市场有 haul 牌价（HaulService.pricedAt）
② 该 hex 当前至少有一户有可交付 haul 服务货：
   available = max(0, haulStock − haulFrozen) > 0
```

只有这两条同时成立，才把该 hex 放进 `ctx.haulServiceMarketHexes`。

这样：

```text
有 haul 生产/库存的 hex → 服务商品腿（MARKET_TRADE）
无 haul 可交付货的 hex → 简单生产性运力 + CARRIER_FEE
```

### 3.2 `MerchantCapacityCore`

保留：

```text
servicePriced=false  → MerchantCapacity.of(...)（劳动+工具）
servicePriced=true   → 只允许 serviceAvailableMilli 作为可用服务货
```

**禁止**：

```text
servicePriced=true 且 serviceAvailableMilli=0
→ 仍用 productiveMilli 造出可交付服务容量
```

即：

```text
effectiveMilli = servicePriced ? serviceAvailableMilli : 0L
```

或等价地在 `serviceAvailableMilli <= 0` 时跳过该 provider 的服务容量。

`serviceAbsentTally` 可以保留为读数，但不得再产生可分配的 productive fallback 服务容量。

### 3.3 `MarketShipmentBook` / `MarketRoundRunner`

- `deliverHaulService` 只在 `route.haulService()` 为 true 时走服务商品扣减；
- `haulServiceMarketHexes` 为空/不含该发货格时，不调用该交付腿，走既有 CARRIER_FEE；
- `HAUL_SERVICE_DELIVERY_FAULT` 必须为 0；
- `MarketRoundRunner` 发现 `ctx.haulServiceDeliveryFaults > 0` 时：
  - 本次修复目标是 0；
  - 作为契约故障，建议 **抛 `IllegalStateException` 使其 fail-closed**，不能只打 ERROR 后继续。

---

## 4. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/ShipmentSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MerchantCapacityCore.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketShipmentBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundRunner.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java
```

必要时只做编译适配。**禁止**：

- 改 production seeder / Rural32HexWorld / 市场价表 / 用户数据；
- 改 LAND 市场、债务、工资、需求账单公式；
- 写/改测试；
- 跑 `test` / `test-compile` / `verify` / `package`；
- `git commit` / `push` / 主树改动。

---

## 5. 具体实现步骤

### 5.1 新增 `haulServiceHexes(SettlementDayContext ctx)` 重载

在 `ShipmentSettlement` 中增加（或等价命名）：

```java
static Set<HexCoord> haulServiceHexes(SettlementDayContext ctx)
```

步骤：

1. 取 `haulServiceHexes(ctx.dayView)` 得到有 haul 牌价的 hex；
2. 遍历 `ctx.householdEconomies`，对每个家户：
   - 若 `ctx.accounts == null` 或 goods/frozen 为空 ⇒ 不添加；
   - `available = max(0, stock.getOrDefault(HAUL,0) - frozen.getOrDefault(HAUL,0))`；
   - `available > 0` ⇒ `hex = row.view().hex()` 加入结果；
3. 结果保序、不可变；只保留**有可交付服务货**的 hex。

注意：

- 这是**服务成市/交付能力**判据，不是“有 haul 牌价”判据；
- 不改变 `HaulService.pricedAt`；
- 不在经济状态里新增字段；
- 纯逐轮派生。

### 5.2 `SettlementDayMarketPipeline` 改用新重载

把：

```java
ctx.haulServiceMarketHexes = haulServiceHexes(ctx.dayView);
```

改为：

```java
ctx.haulServiceMarketHexes = haulServiceHexes(ctx);
```

确保调用时 `ctx.householdEconomies`、`ctx.accounts`、`ctx.marketRound` 已准备好。

### 5.3 `MerchantCapacityCore` 去掉服务口径 productive fallback

当前：

```java
long effectiveMilli = servicePriced ? Math.max(serviceAvailableMilli, productiveMilli) : 0L;
```

改为：

```java
long effectiveMilli = servicePriced ? serviceAvailableMilli : 0L;
```

或在 `servicePriced && serviceAvailableMilli <= 0` 时直接 `continue` 不加入服务池。

保留 `serviceAbsentTally` 读数；但服务成市格不再用 free productive capacity 冒充可交付服务货。

### 5.4 `MarketRoundRunner` fail-closed

`ctx.haulServiceDeliveryFaults > 0` 时：

- 先保留专门 ERROR 日志；
- 然后抛出 `IllegalStateException`（具名，包含 day/faults/reason）；
- 让 `advance` 整体拒绝，避免 status=OK 吞掉契约故障。

---

## 6. 验收锚点

### 正例

1. `SettlementDayMarketPipeline` 的 `haulServiceMarketHexes` 只包含有至少一份可交付 haul 服务货的 hex；
2. 没有 haul 服务货的 32hex 世界：`servicePriced=false` 分支生效，`MerchantCapacity` 走劳动+工具，`MarketShipmentBook` 走 CARRIER_FEE；
3. 32hex 360 每个市场日 `HAUL_SERVICE_DELIVERY_FAULT=0`；
4. `HAUL_SERVICE_SETTLED` 不再出现“produced=0/delivered=0/faults>0”；
5. `MARKET` 的 `LOGISTICS_CAPACITY` / `NO_ADJACENT_SUPPLY` 不再是 unfilled 主因。

### 负例

- 故意给一个无 haul 物资的 hex 放 haul 价 ⇒ 它不能被加入服务成市 hex；
- 服务成市 hex 的 provider 没有 haul 库存 ⇒ 不能被当作服务容量；
- 任何 `HAUL_SERVICE_DELIVERY_FAULT > 0` ⇒ 该次 advance 必须拒绝/红；
- 不允许通过扩大 `haul` 库存/改价格/改 seeder 来掩盖。

---

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；
- 不 commit/push；不碰主树；
- 账本落 `.superpowers/sdd/2026-10-20-d032-rc8a-transport-contract/impl-ledger.md`；
- 报告必须列：改动文件、compile rc、`haulServiceHexes` 判据、服务/非服务分支、fault 处理、未验证项。

---

## 8. 用户原话附录

来源：本会话 2026-10-20。

```text
2我认为是主要问题，单个家户的实际总提供劳动力、实际总口粮需求等，都是要根据据家户的人员规模（甚至根据Social的设计，还得分年龄段）来算的，这是不是没做？

5 可以适当减小，减到1%这个数量级

7 是之前一连串错误的必然结果，暂时不直接修

还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick
```

父 v2 运输口径（逐字）：

```text
跨hex先走确定性 transportCapacity/price 函数，不新增运输生产方式
```
