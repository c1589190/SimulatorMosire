# 生产缺陷修复任务书：DEF-2b 卖冻结上限 + DEF-3 flow table 陈表清理

> 父序列锚点：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`
> S3/S4/S5/S6 → S7-A 市场；S7-B RC6；S7-C RC7。
> 本文件只写两处增量，不重抄父设计。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 测试 owner 已暂停，生产 writer 只写到 compile，不写/不跑测试。

## 1. 嵌入点

- DEF-2b：`MarketRoundRunner.clearOncePerCycle` 的 `commitFreezes(ctx)`，S7-A 撮合之前；卖冻结必须按当刻库存封顶，不能把 S3 stale sell 单全额冻结。
- DEF-3：`SettlementDayMarketPipeline.executeMarketRound` 中 `ClassSlideSettlement` 之后、返回前，清理 source 已不是 LANDED/0 人口的 `householdFlowTables` 陈表；保证最终 `EconomyStateBuilder.build()` 的不变量成立。

## 2. DEF-2b：卖冻结按当刻库存封顶

### 现象

T1 复跑 `EconomyCycleHealthTest` 仍报：

```text
转移会花掉家户账上已冻结的商品：家户=hh-0_-3-rural-landed_peasant
商品=grain 余额=166203 冻结=6052938 扣减=3162
```

`executeTrade` 已在执行时 cap，但 `commitFreezes` 已在撮合前把 stale `sellable` 全额写进 frozen：

```text
baseFrozenGoods = frozenGoodsOf(...)
frozenRemaining = sell.order.sellable()
frozen = base + Σ sell.frozenRemaining
```

⇒ frozen 可大于当刻 stock；即使 execute cap，冻结本身仍非法。

### 冻结修复

修改 `MarketFreezeBook.commitFreezes`：

1. 按 `(seller actor, commodity)` 轴先算当刻可卖上限：
   ```text
   base = frozenGoodsOf(round, seller, commodity)
   stock = stockOf(round, seller, commodity)
   axisAvailable = max(0, stock - base)
   ```
2. 按 `ctx.sells` 现有稳定插入序逐条分配：
   ```text
   sell.baseFrozenGoods = base
   sell.frozenRemaining = min(sell.order.sellable(), axisRemaining)
   axisRemaining -= sell.frozenRemaining
   if (sell.frozenRemaining == 0) {
       sell.remaining = 0
       sell.blocked = MarketUnfilledReason.ALL_RESERVED
   } else if (sell.remaining > sell.frozenRemaining) {
       sell.remaining = sell.frozenRemaining
   }
   sellFrozenSums.merge(axis, sell.frozenRemaining, Long::sum)
   ```
3. 同一轴多个卖单共享该上限，不得各自独立冻结全额；
4. `refreshSellFrozen` 仍只写 `base + 轴累计`；
5. `executeTrade` 已有 execution-time cap 保留作为第二道防线。

### 不可破坏

- `commitFreezes` 之前的所有账户/订单逻辑不变；
- 买方冻结逻辑不变；
- 正常 `stock >= Σsellable` 时逐值不变（capped == requested）；
- 不把必要投入/生活保留/其他冻结误当可卖；
- 不引入第二本库存/冻结权威。

## 3. DEF-3：flow table 陈表清理

### 现象

`Z7RemittanceE2ETest`：

```text
householdFlowTables.source 的 classNature 必须是 LANDED:
source=hh--1_-1-rural-landed_peasant nature=LANDLESS
at EconomyDataInvariants.validateHouseholdFlowTables:736
```

`HouseholdFlowBook.settle` 只维护 LANDED source；`ClassSlideSettlement` 在它之后把某个 source 改成 LANDLESS，陈表留在 sheet，build 期 fail-closed。

### 冻结修复

在 `HouseholdFlowBook` 增加包内静态清理入口：

```text
static int pruneStaleTables(SettlementDayContext ctx)
```

规则：

- 遍历 `ctx.session.sheet().householdFlowTables()` 工作副本；
- source row 不存在 / population <=0 / `ClassNatureBook.classNatureOf(ctx.dayView, source)` 不是 LANDED ⇒ 删除该表；
- 已有 target 不存在 / target population<=0 / target classNature 不是 LANDLESS / target hex != sourceHex ⇒ 清空 targets（保留 source 观察基线）；source 仍不合法则整表删除；
- 返回删除/清空计数，INFO `HOUSEHOLD_FLOW_TABLE_PRUNED`（source/reason）。

调用点：

- `SettlementDayMarketPipeline.executeMarketRound` 中 **`ClassSlideSettlement.settleAtMarketRoundEnd(...)` 之后**调用一次；
- `HouseholdFlowBook.settle` 开始时也先调用一次（防御上一轮陈表），但不得因此改变本轮合法表；
- 最终 build 前保证没有 source 非 LANDED 的表。

## 4. 对父序列的影响

| 维度 | 影响 |
|---|---|
| 顺序 | 不移动阶段；DEF-2b 在 S7-A 冻结段，DEF-3 在 S7-C 后收尾 |
| 预算/冻结 | 卖冻结总额被当刻 stock−base 封顶；买方/生产冻结不变 |
| 身份/持久化 | 无新组件；flow table 删除/清空现有组件 |
| 失败/回滚 | 陈表不再触发 build fail-closed；冻结超限在源头上消除；executeTrade 第二道防线保留 |
| 日志 | 新增 `HOUSEHOLD_FLOW_TABLE_PRUNED`；既有 `MARKET_SELL_STOCK_*` 保留 |
| 测试 | 测试 owner 复跑 `EconomyCycleHealthTest`/`Z7RemittanceE2ETest`，再 gate/360 |

## 5. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketFreezeBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdFlowBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java
```

必要时只改 `MarketTradeExecutor` 注释；**禁止**改 tests、seeder、EconomyData/ChangeSet/Codec、LAND/债务/工资/需求公式、主树。

## 6. 验收锚点（测试 owner 后补）

正例：

1. stock=1000、base=0、两条卖单各 700 ⇒ 冻结总和 ≤1000，第一条 700、第二条 300，第二条 remaining 同步 300；
2. stock=1000、base=200（其他冻结）、卖单 900 ⇒ 冻结 ≤800；
3. normal stock ≥ Σsellable ⇒ 逐值不变；
4. LANDED source 在本轮 ClassSlide 后变 LANDLESS ⇒ 其 flow table 在 build 前被删除；
5. target 变 0 人口/非 LANDLESS ⇒ targets 清空但 source 表可保留；
6. 无常量/无陈表时 prune 为 no-op。

负例：

- 两条卖单各自冻结 700（总 1400 > 1000）⇒ 红；
- flow table source 仍非 LANDED 就 build ⇒ 红；
- prune 把本轮合法 LANDED 表删掉 ⇒ 红；
- 改坏冻结上限但不改 remaining ⇒ 撮合空转/重复尝试 ⇒ 红。

## 7. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-def2b-def3-fix/impl-ledger.md`。
- 报告列：改动文件、compile rc、卖冻结分配规则、prune 规则、未验证项。
