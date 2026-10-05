# 2026-10-09 P0：迁移写人旁路关闭（Economy outbox ↔ Social 工单两条腿）

> 依据：`docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md` §7.2 / §7.2.1
> （用户 2026-10-09 已确认：债务默认 `FOLLOWS_POPULATION`、合同级 policy 预留、两条腿同一 revision）。
> 基线：`HEAD 91eb7aec`（日志 event 已收口 util）。
> 目标：把 `ModeMigrationSettlement` 从“直接改 `HouseholdEconomy.population`”改成“经济腿只改
> 资产/债务/钱/组织/劳动配额，人口转移以 outbox 交给 App；App 同 revision 提交 Social 工单并回写经济行人口”。
> 唯一验收口径：迁移发生当天，逐户 `SocialData.householdPopulation(id) == HouseholdEconomy.population`。

## 1. 现状（问题证据）

- `ModeMigrationSettlement.applySource` 直接把 `move.population()` 从源 `HouseholdEconomy.population`
  挪到目标行（`withPopulationAndLabor`），Social 完全不知道；
- 360 tick smoke（`3de6a88a`）实测 day 120/240/360 迁移后逐户漂移，例如：
  Social `hh-0_2-urban-middle_peasant` 110 / Economy 131；
- 这不是投影 bug，是经济域仍在承担人口权威。Unit 征兵/退伍/伤亡若接在这条旁路上会复制同样的错。

## 2. 目标形状

### 2.1 两个域各写各的

| 域 | 写什么 | 不写什么 |
|---|---|---|
| Social | 家户成员份额（`Household.members`）、`Household.members` 对应的批次/位置、人口事件 | 债务、资产、货币、经济行人口 |
| Economy | `HouseholdEconomy` 的非人口字段、债务合同、资产份额、组织/unit/关系、劳动配额 | **不把迁移人数写进 `population/laborMilli` 并不可逆地当权威**；人口变化只作为 outbox 事实 |

- Economy 的迁移执行仍需要知道“搬多少人、按什么比例搬债/钱/资产” ⇒ 这些比例从 `MigrationPlan` 现算，
  但人口/劳动随迁只在**工作副本的投影账**（`MigrationPopulationLedger`）里推进，不直接写入
  `HouseholdEconomy` 行；需要 `HouseholdEconomy` 对象的方法（建组织、选关系模板、劳动配额铺量）拿
  “投影行”视图对象。
- App 在 `stepper.step(day)` 之后 drain outbox，按 Social 工单执行成员转移；然后再把
  `Σ transfer` 的源/目标人口 delta 写回经济行（`EconomyDayStepper.applyHouseholdPopulationDeltas`），
  并用新 Social 重算 `laborBudgets/composition/naturalNeeds`。整条 advance 的 revision 只在最后落一次；
  任一腿失败 ⇒ 整次 advance 不落 revision（现状 `PopulationEconomyTimeParticipant` 的异常语义自然保证）。

### 2.2 outbox 形状

新增 `io.mosire.simos.economy.time.EconomyPopulationTransfer`：

```text
record EconomyPopulationTransfer(
    HouseholdId source,        // 源经济行（迁出）
    HouseholdId target,        // 目标经济行（已有或本批新建）
    long population,           // 本次搬的人数；> 0
    HexCoord targetHex,        // 目标经济视图所在格
    ProductionModeId targetMode,
    boolean newTarget,         // 本批新建的经济行（Social 侧对应 CREATE_HOUSEHOLD）
    String reason)             // 迁移原因
```

`EconomySession` 新增瞬态列表（不进持久状态/Codec/ChangeSet，与 `debtWriteOffs` 同制）：

```text
recordPopulationTransfer(EconomyPopulationTransfer)
List<EconomyPopulationTransfer> pendingPopulationTransfers()   // 只读 view
List<EconomyPopulationTransfer> drainPendingPopulationTransfers() // App 每日取走并清空
```

`EconomyDayStepper` 转发 `drainPendingPopulationTransfers()`；`ModeMigrationSettlement.applySource` 在每笔
move 成功校验后追加一条 outbox。

### 2.3 App 编排（新类）

新增 `simos-app/src/main/java/io/mosire/simos/app/household/MigrationSocialBridge.java`（暂名）：

```text
static SocialData apply(SocialData base, List<EconomyPopulationTransfer> transfers, long day)
```

逐条 transfer（保持 outbox 顺序）：

1. `target = transfer.target()`；若 `base.households()` 不含 target：
   - 构建 `HouseholdWorkOrderPlan` 第一步 `CreateHousehold(target, HEX(transfer.targetHex()),
     HouseholdProfile("迁入户:" + target.value(), "mode-migration", metadata{source=MODE_MIGRATION}),
     HouseholdVitalRates(List.of()))`；
2. 从当前 `base.households().get(source)` 的成员份额（按 `PeopleLotId.value()` 升序）里，用
   `ProportionalSplit.byDenominator(transfer.population(), weights, sourceHouseholdPopulation)`
   确定性选出恰好 `population` 人；对每个 `take > 0` 的 lot 追加一条 `TransferMembers(source, target, lot, take)`；
3. 组装 `HouseholdWorkOrder(orderId = "mode-migration:" + day + ":" + 序号 + ":" + source + ":" + target,
   target, reason, source="MODE_MIGRATION", plan)`，调 `HouseholdWorkOrderBook.apply(base, order, day)`；
4. 源户人口不足 / 目标已存在但 id 冲突 / Social 守恒失败 ⇒ 具名 `IllegalStateException`；**不部分回滚**，
   整次 advance 失败。

App 在 `PopulationEconomyTimeParticipant` 的 `stepper.step(day)` 之后：

```text
transfers = stepper.drainPendingPopulationTransfers();
if (!transfers.isEmpty()) {
  currentSocial = MigrationSocialBridge.apply(currentSocial, transfers, day);
  stepper.applyHouseholdPopulationDeltas(netDeltasOf(transfers)); // 源 -pop / 目标 +pop，Σ 合并
  stepper.updateComposition(compositionOf(currentSocial));
  stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
  stepper.updateNaturalNeeds(naturalNeedsOf(currentSocial, day));
}
```

- `netDeltasOf` 逐户累加，0 不入表；缺行/负结果沿用
  `EconomySettlement.applyHouseholdPopulationDeltasInto` 的 fail-closed；
- Social 工单成功后，目标 Social 家户一定存在；目标经济行由 Economy 迁移创建（newTarget=true 时）
  或早已存在（合并）；源经济行即使计划人口清零也**必须保留 0 人口壳行**（见 §2.4），否则 delta 无处落。

### 2.4 `ModeMigrationSettlement` 内部改法（关键）

在 `apply(...)` 里建 **迁移投影账**，跨全部 source 共享：

```text
LinkedHashMap<HouseholdId, Long> plannedPopulation  // 初值 = 现 HouseholdEconomy.population()
LinkedHashMap<HouseholdId, Long> plannedLabor       // 初值 = 现 HouseholdEconomy.laborMilli()
```

`applySource`/`retireSource` 的所有迁移决策读投影账、写投影账：

- `sourcePopulation/laborLeft/populationBefore/empties/targetPopulationAfter` 一律取投影账；
- 删除 `householdEconomies.put(source, sourceRow.withPopulationAndLabor(…))` 与目标行的同类写入；
  改为 `plannedPopulation.merge(source, -popTake, Long::sum)` / `plannedPopulation.merge(target, +popTake,…)`，
  labor 同理；
- 需要 `HouseholdEconomy` 实例的调用点传 **投影行视图**：
  `actual.withPopulationAndLabor(plannedPopulation.get(id), plannedLabor.get(id))`；
  这些视图只作参数，不 `put` 回 `householdEconomies`；
- `source` 计划人口归零（`plannedPopulation.get(source) == 0`）时调 `retireSource(..., plannedPopulation)`：
  - 组织/unit/关系/劳动配额/商号照旧退役；
  - **始终保留** `HouseholdEconomy` + `FlowRow` + `HouseholdClassMembership`（0 人口壳户，沿用现有 shell 口径），
    不删行、不删 FlowRow、不摘 crisisSignal 引用；Social 侧同批会留下 0 成员家户；
  - 残留钱/债检查照旧（迁移后必须为 0）；HOUSEHOLD 范围 demand / ClassShare 引用照旧 fail-closed；
- `source == target` 在 `MigrationMove` 构造期已拒；`newTarget` 行由 `createNewHousehold` 建，人口/劳动
  写 0，等 App 的 delta 回写。

### 2.5 顺序/幂等/日志

- 顺序：日初 Social 生死 →（App 刷新经济投影）→ `stepper.step(day)`（经济腿 + outbox）→ App drain
  → Social 工单落人 → 经济行 delta 回写 → 下一次循环；同一条 revision 内全部完成。
- 幂等：Social 工单 `orderId = mode-migration:<day>:<序号>:<source>:<target>`，由
  `HouseholdWorkOrderBook` 的 `work-order:` 标记事件挡重复提交；outbox 本身是瞬态，重启后由“同一 plan
  重放”产生同一 orderId。
- 日志：经济腿 `EconomyLog.migration()`（`MIGRATION_PLAN/MOVE/APPLIED`、逐笔 `MIGRATION_POPULATION_OUTBOX`），
  Social 腿 `SocialLog.workOrder()` / `SocialLog.population()`，App 跨域汇总用
  `EventLog.channel(AppLog.time()).info(LogEvent.of("MODE_MIGRATION_BRIDGED", ...))`（util 新 event 机制）。

## 3. 文件范围

**允许写**
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySession.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyPopulationTransfer.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/household/MigrationSocialBridge.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java`
- 必要的类注/javadoc 同步（仅上述文件）

**禁止**
- `simos-social` / `simos-social-api` 的状态或写口（Social 只被调用，不为迁移加字段）；
- `EconomyData` 持久组件/Codec/ChangeSet（outbox 是瞬态）；
- 测试、`simos-util` 的新日志机制（已落地，直接用）；
- commit/push。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；
2. fresh small-world `0→360`：
   - 0 Runtime ERROR / 0 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`；
   - `MIGRATION_APPLIED` 出现在 day 120/240/360；
   - 迁移后逐户 `SocialData.householdPopulation(id) == EconomyData.classes().get(id).population()`，
     尤其上述 3 个漂移户（可用 `/api/map/heatmap` + `/api/social/population` + `/api/economy/hex` 读口抽查）；
   - 全世界总人口守恒（Social 总 = Economy 总）；
3. 不跑 test/test-compile/verify；不 commit/push。

---

## 5. 实施状态（2026-10-10）

✅ 已实现并推送前验收：

- `EconomySession` 瞬态 outbox + `EconomyDayStepper.drainPendingPopulationTransfers()`；
- `ModeMigrationSettlement` 改投影账（`plannedPopulation/plannedLabor`），已有行的 `population/laborMilli`
  在 `apply` 前后逐值不变；`retireSource` 始终保留 0 人口壳行；
- `MigrationSocialBridge` 把 outbox 翻成 `CREATE_HOUSEHOLD` + `TRANSFER_MEMBERS` 工单，orderId
  `mode-migration:<day>:<序号>:<source>:<target>`；
- `PopulationEconomyTimeParticipant` 在 `step(day)` 后 drain → Social 工单 → 经济行 delta 回写 →
  刷新 composition/labor/needs；
- 实测（fresh small-world，0→360）：
  - compile rc=0、package rc=0（前端 412/412）；
  - day 120/240/360 有 `MIGRATION_APPLIED`；
  - 0 ERROR / 0 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`；
  - 逐户探针 138/138 户 Social == Economy；世界总人口 Social=Economy=4100；
  - `work-order:mode-migration:*` 标记事件 15 条，0 DUPLICATE / 0 REJECTED。

遗留（测试迁移批次）：旧测试若直接调 `ModeMigrationSettlement.apply` 并断言旧“经济行当场加减人口/删行”
语义，需要迁到两腿路径；`MIGRATION_POPULATION_OUTBOX` TRACE 在 DEBUG 级 smoke 未实测输出。
