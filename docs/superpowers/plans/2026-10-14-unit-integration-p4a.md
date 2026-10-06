# 2026-10-14 Unit 对接 P4a：通用周期家户库存扣增（规则表 + 无状态到期 + 部分支付）

> 依据：`docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md` §7.3 P4；
> 用户 2026-10-14 确认路线 **C+PARTIAL**：先做 Economy 通用规则表 + 无状态到期执行器，接入 app 日循环；
> 余额不足默认**部分支付 + shortfall 读数**；Unit 政策/决策人工具端留 P4b。
> 基线：`HEAD d1b2ba17`（P1/P2/P3 已完成）。

## 1. 目标

- 提供**通用的**“周期家户库存/货币扣增”规则状态与到期执行器，不写死 Army/GOV 语义；
- 规则字段显式携带 payer / payee / 商品 / 币种 / periodDays / phase / reason / policySource；
- 到期判据只依赖绝对世界日与规则字段（无状态，不存 lastPaidTick）；
- 缺额默认部分支付：逐腿 `paid = min(requested, available)`，shortfall 只读数、不阻断其它规则；
- 复用现有 `HouseholdStockDeduction` + `StockDeductionService` + `TAX_AND_UPKEEP` 阶段，不另造扣账路径；
- Unit 侧政策、GM/决策人工具、军职人头/按 kind 分摊均留 P4b。

## 2. 数据模型

### 2.1 economy-api 新契约（放 `io.mosire.simos.economy.api.stock`）

```text
PeriodicHouseholdAdjustmentId(String value)     // 稳定 id，非空白
HouseholdPeriodicAdjustment(
    id,
    HouseholdId payer,                          // 必填；被扣方
    Optional<HouseholdId> payee,                // 空 = sink；非空 = 原子转移
    Map<CommodityId, Long> goodsPerCycle,       // 可空；逐值 > 0
    Map<CurrencyId, Long> moneyPerCycle,        // 可空；逐值 > 0
    DeductionReason reason,                     // 词表内；P4 用 MILITARY_SALARY
    long periodDays,                            // > 0
    long phaseDay,                              // [0, periodDays)
    long startsOnDay,                           // >= 0
    OptionalLong expiresOnDay,                  // 空 = 永久；给了 >= startsOnDay
    String policySource)                        // 非空白，审计用（如 army:<unitId> / gm:<id>）
```

- 构造期：payer/payee 不得相同；goods+money 至少一腿非空；键值不得为 null/0/负；
  periodDays/phase/startsOnDay/expires 边界逐条判；
- 与 `HouseholdStockDeduction` 的关系：规则只描述“周期请求额”，执行时按可用量折算成
  一条 `HouseholdStockDeduction`；规则本身不落账户。

### 2.2 EconomyData 第 31 组件

- 新增 `Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustments`；
- 保留一个**旧 30 参便捷构造器**（委托 canonical，periodicAdjustments = 空表），减少无关调用点改动；
  所有 `with*` 方法必须把该组件**原样带过**；
- `EconomyCodec` 编码/解码新组件；旧档缺键 ⇒ 空表（与其余可选组件同口径）；
- `EconomyChangeSet` 参与 diff/apply；键 == 值内 id 的跨表守卫。

### 2.3 命令（GM-only；P4b 再做工具端）

- `economy.UpsertHouseholdPeriodicAdjustment`：
  载荷 = 2.1 全字段（`payee`/`expiresOnDay` 可缺省）；同 id 全量替换；id 已存在 = upsert（不是静默合并）；
- `economy.RemoveHouseholdPeriodicAdjustment`：载荷 `{id, reason?}`；不存在 ⇒ 具名拒（不静默成功）；
- 两条都写 economy 命名空间，注册进 `Shell` + `CatalogTool.PAYLOAD_HINTS`；
- P4a 只提供 GM 命令面，不提供 GM/决策人窄工具（用户已明确工具后置）。

## 3. 到期与执行语义

### 3.1 到期判据

```text
due(rule, day) := day >= startsOnDay
              && (expiresOnDay 为空 || day <= expiresOnDay)
              && ((day - startsOnDay) % periodDays == phaseDay)
```

- 无 lastPaidTick / 无进度状态；`periodDays=1` ⇒ 每天一笔；`phaseDay` 只影响相位。
- 一天内多规则按 `id.value()` 升序执行。

### 3.2 部分支付

逐条规则：
1. 先检查 payer 账户是否在 `AccountSession` 已登记；payee 给出时同样检查；缺任一 ⇒ 具名 gap、跳过该规则
   （`reason=payer-account-missing` / `payee-account-missing`），不阻断其它规则；
2. 逐商品：`paidGoods[c] = min(requested, AvailableStock.available(payer, c))`；
   逐币种：`paidMoney[cur] = min(requested, available)`；
   `shortfall = requested - paid`（逐键，缺额进读数）；paid ≤ 0 的腿不进 `HouseholdStockDeduction`；
3. 若 paidGoods + paidMoney 皆空 ⇒ 不调服务，只记 gap/shortfall；
4. 否则构造 `HouseholdStockDeduction.transfer/sink(payer, payee, paidGoods, paidMoney, reason, detail)`，
   调 `StockDeductionService.deduct`（默认 `TAX_AND_UPKEEP`）；
   - `detail` = `policySource + ":" + rule.id`；
   - 服务若因防御性原因拒绝（理论上前置已满足），**捕获具名 IllegalArgumentException**、记 gap、
     继续执行后续规则；不把一次 advance 打红（C+PARTIAL 口径）；
5. 每条规则一条 INFO/DEBUG 读数 + 逐腿 TRACE；一天结束一条 INFO 汇总
   `PERIODIC_ADJUSTMENT_DAY due=… executed=… skipped=… paidGoods=… paidMoney=… shortfallGoods=… shortfallMoney=…`。

### 3.3 执行位置

- app `PopulationEconomyTimeParticipant` 日循环：在 **GovDaily 支出之后**、`MarketReportFeed/DAY_END` 之前，
  调 `PeriodicHouseholdAdjustmentExecutor.applyDue(economy, stepper.accounts(), day)`；
- 与 `JurisdictionDailyTax`/`GovernmentUpkeepOracle` 共用同一个 `AccountSession` 与 `TAX_AND_UPKEEP` 阶段；
- 不塞进 `EconomySettlement`、`GovDaily`、`UnitTimeParticipant`。

### 3.4 与 GovDaily 的关系

- P4a 不自动给任何单位建规则、不读 `OfficePolicy`/`ArmyFormation`；规则必须由命令/场景显式注册；
- GOV 行政俸禄继续走 `GovernmentUpkeepOracle`；只有 GM 明确注册了同账户规则才会再扣一笔，
  不存在“同一政策自动扣两次”；
- 规则可写任意 `payer/payee/reason`，结构上不禁止 GOV 账户；P4b 的工具/政策层再负责围栏。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. fresh harness（沿用 P3 的 `Shell + gmToolAuthorizer/command.submit` 模式）：
   - 建一个 GOV/或直接用 P3 raiseUnit 的 `hh-unit:<id>` 家户 + 账户；
   - 用 `simos.command.submit` 注册两条规则：
     - 规则 A：`periodDays=3, phase=0, startsOnDay=0`，payer = 某家户，payee = `hh-unit:<id>`，
       金额 > payer 可用量 ⇒ 断言到期日**部分支付**，shortfall = requested − available；
     - 规则 B：同样周期但 sink（无 payee）⇒ 断言总量减少 = paid；
   - advance 到 `due-1/day due/due+1`：
     - 非到期日账户逐值不变、无 `HOUSEHOLD_STOCK_DEDUCTED reason=military_salary`；
     - 到期日恰好扣一笔，payee 增/减守恒（transfer）或总量减（sink），shortfall 读数正确；
     - 到期日+1 不再扣；
   - 1×N == N×1：同一 base 一次 advance N 天 vs N 次 advance 1 天，最终账户逐值相等；
   - 重启同 store：规则表/账户重载一致，head 一致；
   - 非法规则载荷（period≤0 / phase 越界 / 自转 / 空腿 / 值≤0）⇒ 命令 Rejected、零 revision；
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 5. 文件范围

**允许**
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/stock/PeriodicHouseholdAdjustmentId.java`（新）
- `.../stock/HouseholdPeriodicAdjustment.java`（新）
- `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`（第 31 组件 + 便捷构造器/with*）
- `simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCodec.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyUpsertPeriodicAdjustmentHandler.java`（新）
- `.../spi/EconomyRemovePeriodicAdjustmentHandler.java`（新）
- `.../spi/EconomyPayloads.java`（若需解析辅助，窄改）
- `simos-app/src/main/java/io/mosire/simos/app/time/PeriodicHouseholdAdjustmentExecutor.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java`（接线 + 日志）
- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（handler 注册）
- `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java`（PAYLOAD_HINTS）
- 必要的类注/javadoc 同步

**禁止**
- 改 `Unit`/`UnitState`/`ArmyFormation`/`GovernmentFormation`/`SocialData` 持久组件；
- 改 `StockDeductionService`/`HouseholdStockDeduction` 契约；
- 给 `EconomyData` 增加第二个平行“规则”形状；不把 due 状态/最后扣款日写进状态；
- 改测试；commit/push。

## 6. P4b 待办（本批不做，记录在案）

- Unit 政策字段（`MilitaryPayPolicy`）：周期/资金来源/按 `MilitaryDutyKind` 或家户权重分摊；
- GM 窄工具 `simos.economy.periodicAdjustment`（upsert/remove/预览/到期读口）；
- 决策人受限工具与审批链、`DirectiveWhitelist` 是否放开；
- 军职人头/按 kind 分摊的纯函数 `MilitaryPayAllocation`（不跨户平均，用稳定 id + `ProportionalSplit`）；
- 军俸是否写入 `FlowRow`/ledger 的独立维度；
- 规则与 GOV 行政俸禄的共享国库预算/缺口优先级。

---

## 7. 实施状态（2026-10-14）

✅ 已实现并验收（用户选定 C+PARTIAL）：

- `PeriodicHouseholdAdjustmentId` / `HouseholdPeriodicAdjustment`（economy-api）已落地；
- `EconomyData` 追加 `periodicAdjustments` 组件（实际 record 第 29 个；旧 28 参便捷构造器保留），
  `EconomyChangeSet` / `EconomyCodec` / `EconomyStateBuilder` / `EconomySeedHandler` /
  `EconomyClearRegionHandler` 同步带过；旧档缺键 ⇒ 空表；
- `economy.UpsertHouseholdPeriodicAdjustment` / `economy.RemoveHouseholdPeriodicAdjustment`（GM-only）
  已注册进 Shell + CatalogTool；
- app `PeriodicHouseholdAdjustmentExecutor` 已接入日循环（GovDaily 之后、MarketReportFeed 之前）：
  无状态到期、按 id 顺序、逐腿 `min(requested, available)`、部分支付 + shortfall 读数、缺户/缺账具名 gap 跳过；
- 主控独立 smoke（`/tmp/P4aSmoke.java`，92 断言全 PASS）：
  - 到期日部分支付：payer 7 ore→0、payee 0→7（transfer 守恒）、sink 4→0（总量减 4）；
    `PERIODIC_ADJUSTMENT_DAY due=2 executed=2 skipped=0 paidGoods={ore=11} ... shortfallGoods={ore=9}`；
  - 非到期日账户逐值不变、无日志；due+1 不再扣；
  - 1×N == N×1：一次 0→6 vs 六次单日，最终 ActorData.accounts 逐值相等；
  - 重启同 store：规则表/账户/head 重载一致；
  - 非法载荷（period≤0/phase 越界/自转/空腿/值 0/expires 倒置）全部 Rejected 零 revision；
- `compile` / `package` rc=0，前端 412/412；未跑 Java test/verify/spotless（`EconomyRoundTripTest`
  的 28 组件硬编码需在测试迁移批改 29）。

P4b 未做：Unit `MilitaryPayPolicy`、军职分摊纯函数、GM 窄工具、决策人白名单、FlowRow/ledger 维度。
