# 实现架构账本 —— B2：逐日循环的「当日视图」（修 advance 路径效应 + 性能红线）

> 责任区：**B2**（约束设计书 = `docs/superpowers/plans/2026-10-10-all-modules-target-completion-plan.md` §3）。
> 上游依据：`.superpowers/sdd/2026-10-10-investigate-advance-path-dependence/impl-ledger.md`（根因/候选）、
> `.superpowers/sdd/2026-10-10-fix-advance-path-dependence/impl-ledger.md`（B1 已修 + **剩余源头**）。
> 日期 2026-10-10。交付边界：只写 `simos-economy/src/main/**`（本批**未动** `simos-app/src/main/**`）；
> 只到**编译过**（**未跑** `test`/`verify`/world —— P-1..P-4 归控制方下一轮实测）。

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据 | 结论 |
|---|---|---|
| 1 | `EconomySettlement.java:1251` `EconomyData base = session.base()`；`settleOneDayInto` 是**唯一日结算实现**（`:1223`，由 `EconomyDayStepper.step(day)` 逐日调用） | 段首 `base` 在**日循环里被反复读**：一次 advance 的 N 天共用同一个 `EconomySession`（整段一条 revision）⇒ 段内第 2 天起读 `base` = 读"上一天以前的旧世界" |
| 2 | 迁移/排序整条：`EconomySettlement:3453` `ModeMigrationPolicy.plan(base,…)` → `prospect`/`hasMerchantCapacityAt`/`PrimaryModeRanking`/`industries()` | 这一族同时读**逐日会变**的表（`classes`/`classStandings`/`industries`/`productionOrganizations`/`shipments`/`markets`）⇒ 是调查账本 §3.2 的"次源"（首个关账日 day=30 的 `PRIMARY_MODE_RANKING_ROW.yieldPerLaborScaled` 变化，可由 `prospect` 读 `base.classes()` 逐值解释：t1 的 base=day0、t3 的 base=day5） |
| 3 | `MarketDemandBook.build(...)` 只经 `latestReport(reports)`（`MarketDemandBook.java:240/437-445`）用报告表；`profitCycle.marketReports()` = 本会话逐日报告 | **不是**路径效应源：关账日必有市场轮（`MarketSettlement.triggerFor:1272` 的 `day % 5 == 0` 与 `:1275` 的 `anyCycleClosed` 两条都命中）⇒ 各分段下"最新报告"都是关账日当天那一份 |
| 4 | `EnterpriseProfitBook.CycleAccumulator.recordDay`（`:321`）**跨日累加** `ledgers`，只在关账日 `resetForNextCycle()`（`:415`）；`collect(...)` 遍历 `cycle.ledgers()`（`:504`） | ★ **潜在路径依赖**（1 段 0→360 的第 1 个关账日汇总 30 天，逐 tick 分段只汇总 1 天）。**但今天不改变状态**：`ModeMigrationPolicy.plan` 收了 `EnterpriseProfitBook.Book book` 却**只用它做日志计数**（`ModeMigrationPolicy.java:285/299`，`book` 无任何读点；`EconomySettlement:3484-3486/3528-3530` 只读 `.size()`）⇒ 列 OPEN（§9-2），不改 |
| 5 | `sheet().classMembershipsOrBase()/operatorConditions()` 已是 B1 的权威读口；`OperatorSettlement.java:375` 的 `prev` 来自调用方传入的工作副本（`EconomySettlement:1344/4011/4155`） | 两个"连续周期计数器"读的都是**工作副本** ⇒ 满足"累计计数只依赖世界历史"（本批只做逐处确认，未改） |
| 6 | app 侧 `PopulationEconomyTimeParticipant:607/707` 的 `previousCumulativeGrainUnmet` **每日刷新**；`MarketTopologyBook.from(state)`（`:570`）只读 map/城市 + `markets` 的**键集与 numeraire**，而段内市场表只改价格（`MarketSettlement:2058` `new Market(current.numeraire(), prices)`，键集不变） | 这三条"按调用锚定"的候选**逐一排除**（账本 §9-3 留作未实测边界） |

## 2. 我的实现架构（形态 / 数据流 / 次序）

**当日视图 = 一个窄只读接口 + 一个引用级门面**（两层都在 `simos-economy`，零新模块依赖）：

```
io.mosire.simos.economy.EconomyDayView       ← 接口，15 个读方法（:61-115）
  ├─ 实现者 A：EconomyData implements EconomyDayView（:359）  = "我就是我自己的当刻值"
  └─ 实现者 B：io.mosire.simos.economy.time.WorkingDayView（:59）= 挂在会话工作表上的门面
                 每个 accessor 现场问 EconomyStateBuilder（:68-143）
                   · 有工作副本的 9 张表 ⇒ sheet.xxxOrBase()（已物化读工作副本，未物化复用 base 的不可变表）
                   · 无工作副本的 6 张模板表 ⇒ sheet.base().xxx()（写入口只有创世/命令 ⇒ 段内不变）
```

**构造点（唯一）**：`EconomySettlement.settleOneDayInto:1261` `EconomyDayView dayView = new WorkingDayView(session.sheet())`
—— 在**每一天开始时**派生（该方法 = 每天的日结算体）。构造 = 一次分配 + 一次 null 检查。**不做**快照：工作副本是
**惰性物化**的，构造时拍表引用会在它物化的那一刻变成过期引用（这正是本批要消灭的 bug 形态）⇒ 门面是"活的解析器"。

**新增/复用的工作表读口**（`EconomyStateBuilder`，全部"工作副本优先、否则 base"）：新增
`industriesOrBase:132`、`householdEconomiesOrBase:145`、`productionOrganizationsOrBase:233`、`marketsOrBase:259`、
`shipmentsOrBase:272`、`governmentsOrBase:285`、`moneyIssuancesOrBase:298`、`modeTransitionsOrBase:350`；
复用 `classMembershipsOrBase:334`（B1 新增）、`unitsOrBase:201`、`relationsOrBase:217`、`govMarketMandatesOrBase:372`。

**为什么 `EconomyData` 自己也实现该接口**：接口是"读口"，不是"新状态"。让记录自己实现它 ⇒
① 段级/读口/GM 工具/测试那些**本来就在 revision 边界**的调用方传 `EconomyData` 时**逐源码不变**（含 app 的
`GovernmentServiceDesertionBridge:854` `ExpectedProfitBook.prospect(economy,…)` 与 `:714` 的 `claimedOwnershipStakes`）；
② 被迁移的函数**结构上无法**再把段首基态读回来（要读就得显式拿一个 `EconomyData`，那会在评审里露出来）。

## 3. 关键判断（为什么这样拆 / 推翻了什么）

1. **不用"每天构造一份 `EconomyData`"作视图**：`EconomyStateBuilder.build()` 走 `EconomyData` 规范构造器 ⇒ 全量守卫
   O(状态)，且 §3.4.1 明写"构造成本必须与状态规模无关或极低"。⇒ 接口 + 引用级门面。
2. **不用"宽接口（40 个组件全暴露）"**：只放**逐日读者真正需要**的 15 个组件 ⇒ 读口即文档（谁需要新的当刻组件，
   必须显式扩接口，而不是顺手摸 `base`）。
3. **不逐个改 `EconomyData` 语义**：`EconomyData` 一字不改形状（无新组件、无编解码变化、无 `Map.copyOf`），
   只加 `implements` ⇒ 铁律 5 的往返不变式不受影响。
4. **推翻了 B1 账本关于 `ExpectedProfitBook:974` 的判断**：B1 记"只换一半会造混合日视图 ⇒ 本批不修"。本批既然
   有了**整份**当刻视图，"混合"这一顾虑消失 ⇒ 改成整体读视图（这才是它的正解）。
5. **代价如实记**：被迁移函数的**形参类型**从 `EconomyData` 变成 `EconomyDayView`。源码兼容由第 2 节①保证；
   但**测试源码里若有人写 `(EconomyData) 某视图变量` 或反射取形参类型**，会在测试编译/运行期暴露（§8）。

## 4. 偏离记录（与约束设计书不一致之处）

- §3.2.1 说"`advance` 段首的 `base` 只允许段级语义"——本批留了 10 处仍读 `base` 的地方（§6 清单），它们**全部**
  是无会话工作副本的**静态模板/接线闸门**（段内不变，`base` 即当刻值），每处都在代码里用
  `★ B2-保留段首base：…` 注明理由（`grep -n "B2-保留段首base" EconomySettlement.java` = **10 行 = 10 处读点**）。
  ⇒ 不是"漏改"，是**逐点裁定的保留**。
- 未动 `simos-app/src/main/**`：app 侧"按调用锚定"的三条候选已逐条排除（§1-6），故事实上无需改动。

## 5. 改动文件 + 落点

| # | 文件 | 落点 |
|---|---|---|
| 1 | `economy/EconomyDayView.java`（新） | 接口 15 方法 + 类注（形态/成本/混合视图禁令）`:61-115`（15 个读方法） |
| 2 | `economy/time/WorkingDayView.java`（新） | 门面 `:59-150` |
| 3 | `economy/EconomyData.java` | `implements EconomyDayView`（record 头 `:360`）+ 说明注 `:297-300` |
| 4 | `economy/time/EconomyStateBuilder.java` | 8 个新 `xxxOrBase()` 读口（见 §2） |
| 5 | `economy/time/EconomySettlement.java` | 视图构造 `:1261`；日循环读点改造（§6）；保留点注释（§6）；`logHaulServiceOutput:517`、`haulServiceHexes:796`、`capitalizeArrears:7027`、`logFxWindows:8613` 换当刻读口 |
| 6 | `economy/time/ModeMigrationPolicy.java` | 15 处 `EconomyData base` ⇒ `EconomyDayView base`（`:274/449/612/665/737/932/1007/1069/1203/1273/1282/1310/1478/1483/1603`） |
| 7 | `economy/time/ExpectedProfitBook.java` | `prospect:194`、`merchantProspect:613`、`currentPositionOf:973`、`choosePosition:981`、`resolveIndustry:1013`、`merchantEnterpriseOf:1352`（含类注改写：改读当刻组织表） |
| 8 | `economy/time/MerchantCapacityPool.java` | `hasCapacityAt:555`、`hexCapacityMilli:566`、`memberCapacityMilli:585`、`sharePerMilleAsProviderAt:618` |
| 9 | `economy/time/PrimaryModeRanking.java` | `merchantGateReason:282` |
| 10 | `economy/time/ModeMigrationSettlement.java` | 10 处 `EconomyData base` ⇒ 视图（`apply` 两处入口 + 内部） |
| 11 | `economy/time/DebtPartyResolver.java` | 8 处（`resolveActor/resolvePayee/resolveViaEnterprises/resolveViaPopulation/resolveCohort/splitByPopulation/isAggregateProductionActor/isFeudalIndustry`） |
| 12 | `economy/migrate/ProductionRoleResolver.java` | `resolveCurrent:76`（`seedLegacyClassMemberships`/`hasAnyNewState` **留** `EconomyData`：它们是**造状态**不是读当刻） |
| 13 | `economy/model/MarketZoneBook.java` | `zonesCovering(EconomyData,…):259` ⇒ 视图（其余 `zones/zoneIds/zoneOfHex/…` 未动） |
| 14 | `economy/time/GovernmentSeigniorage.java` | `isCycleStart:50`、`settleCycleStart:75` |
| 15 | `economy/time/GovernmentDebtIssuance.java` | `issueCycleStart:52` |
| 16 | `economy/time/EconomyEnterpriseSettlement.java` | 3 处（`organize` 入口 + 两个内部） |
| 17 | `economy/time/EconomyModeTransitionSettlement.java` | 2 处 |

## 6. ★ 逐日读者迁移清单

**（甲）改成读当日视图的（本批）**

| 读点 | 原读 | 现读 | 为什么必须当刻 |
|---|---|---|---|
| `EconomySettlement:1277` 有效市场排除集 | `base.governments()` | `dayView.governments()` | 国库户身份随段内 GM/发行腿变 |
| `:1282` `MoneyIssuance.syncAuthorities` | `base.governments()` | 视图 | 同上（发行主体登记） |
| `:1295-1299` 政府铸币/发债（3 入口） | `base` | 视图 | `GovernmentSeigniorage` 读 `industries()`（当刻产业关账口径）+ `governments()` |
| `:1319` `MoneyIssuanceJournal` | `base.governments()` | 视图 | 见上 |
| `:1597` E6a 到期变迁判定 + `:1607` `apply` | `base.modeTransitions()` / `base` | 视图 | 段内已 APPLIED 的变迁不得在次日再当"到期"；旧读法每天白跑一次 apply |
| `:1658` E2 自动组织 `organize` | `base` | 视图 | 它读 `classStandings()`（当刻归属）——旧读法 = 混合日视图 |
| `:2140` `logHaulServiceOutput`（日志） | `base.markets()/industries()` | 视图 | 当天定价/当天产出 |
| `:2151` `capitalizeArrears` → `DebtPartyResolver` | `base` | 视图 | 端点解析读 `classes()`/`industries()`：迁移过的户必须按**当刻**行解析 |
| `:2251` `haulServiceHexes`（"服务成市"格集） | `base.markets()` | 视图 | 价格是数据、逐日变 |
| `:2256` 运力池装配的 `classPositions` | `base.classPositions()` | 视图 | 静态模板，但统一"当刻值一个来源" |
| `:2335` `FxRoundInput.of` | `base.governments()/moneyIssuances()/marketZones()` | 视图 | 储备上限 = **当刻**累计发行量 |
| `:2337` `logFxWindows`（日志） | `base` | 视图 | 当刻政府窗口 |
| `:2352` `GovernmentMarketMandatePlan.of` | `base.governments()` | 视图 | 当刻国库户名单 |
| `:2435` `MerchantIdentity.pureMerchants` | `base.classPositions()` | 视图 | 静态模板，同上 |
| `:3456` `ModeMigrationPolicy.plan`（★ 主目标） | `base` | 视图 | 迁移/择业整条 + `prospect`/运力/排序表全读当刻行、产业、组织、在途 |
| `:3535` `ModeMigrationSettlement.apply` | `base` | 视图 | 目标户落点读当刻 `industries()` |
| `MarketZoneBook:259` `zonesCovering` | `EconomyData` | 视图 | 由 `logFxWindows` 逐日调用 |

**（乙）仍读段首 `base` 的（逐点理由；`grep -n "B2-保留段首base" EconomySettlement.java`）**

| 读点 | 理由（= 代码注释原文口径） |
|---|---|
| `:1404`、`:1429` `base.modes().size()`（两条逐日日志计数） | `modes` **没有**会话工作副本（写入口只有创世/GM 命令）⇒ 段首表即当刻表 |
| `:1445` `base.demands()/candidates()`（0-entry 入场闸门） | 两张表无工作副本（写入口只有命令面）⇒ 段内不变 |
| `:1654`、`:1803`、`:1841`、`:3435` `base.modes().isEmpty()`（4 个 E1–E6 接线闸门） | 同上：静态模板，段内恒定 |
| `:1947` `base.outputQuantityOverrides()` | 无工作副本（写入口只有 GM 命令） |
| `:2221` `base.demands()`（市场轮需求账） | 无工作副本 |
| `:3181` `EconomyLiquidationSettlement.isActive(base)` | E5b **接线闸门**（六张表任一非空），不是逐日读数；**B1 账本已裁定不动**（§3 末行），本批沿用 |

**（丙）语义自检（累计计数 vs 当刻位置/归属）——逐处确认**

| 机制 | 读的是哪一份 | 判定 |
|---|---|---|
| `HouseholdClassMembership.consecutiveDebtStressCycles`（E5b 计数） | `sheet().classMembershipsOrBase()`（B1 修） | ✅ 只依赖关账周期史 |
| `OperatorCondition.consecutiveDebtStressCycles`（S3 计数） | 调用方传 `sheet().operatorConditions()`（`OperatorSettlement:375` 的 `prev`） | ✅ |
| `FlowRow.*`（周期累计，持久） | 会话就地更新 + app 逐日差分基线**每天刷新** | ✅ |
| `EnterpriseProfitBook.CycleAccumulator`（会话跨日累加） | 只在**日志**用（§1-4） | ⚠ 潜在（OPEN，不改） |
| `profitCycle.marketReports()` → 需求簿 | 只取 `latestReport`；关账日必有市场轮 | ✅（空 markets 边界见 §9-3） |
| `ProductionProcess` 周期累计（progressDays/cycleLabor…，持久组件） | 状态本身，按周期关账清零 | ✅ |
| `debtWriteOffs` / `populationTransfers` / `productionModifiers`（会话瞬态） | 只读读数 / 每日 drain / 每 tick 替换 | ✅ |
| 当刻位置/归属（`classStandings`/`classes`） | 全族改读当日视图（§6 甲） | ✅ 同源，不再"当刻归属 + 段首人口" |

## 7. ★ 性能红线自证（§3.4）

1. **落盘族 grep（去注释后，视图两文件 0 命中）**
   ```bash
   for f in EconomyDayView.java time/WorkingDayView.java; do sed -e 's://.*::' -e 's:^\s*\*.*::' "$f" \
     | grep -nE "save|checkpoint|persist|[Cc]odec|changeSet|ChangeSet|[Rr]evision"; done   # ⇒ 两文件均 0 命中
   ```
2. **一次 advance 的落盘/Revision 数不变**：本批**没有**新增任何 build/finish/codec 调用。
   - 日循环内**零** `session.build()/preview()`（`awk 'NR>=1262 && NR<=3620' | grep 'session.build()\|preview()'` = 空）；
   - app 每次 advance **一次** `stepper.finish()`（`PopulationEconomyTimeParticipant:1059`）⇒ `EconomySession.build()` 一次
     （`EconomyDayStepper:717`）⇒ 段级一条 revision，与改前**逐字相同**。
3. **构造成本**：`new WorkingDayView(sheet)` = 1 次分配 + `Objects.requireNonNull`；15 个 accessor = 1 次字段判空 + 1 次表引用返回
   （`xxxOrBase()` 对未物化组件**不拷表**）。与状态规模无关。
4. **没有新增写口**：视图接口 15 个方法全是读；`WorkingDayView` 不持有可变表字段（只持 `EconomyStateBuilder` 引用，
   接口上看不见写方法）；无 `Map.copyOf/Set.copyOf`（`grep copyOf` 两文件 = 0）。
5. **未测**：§3.4.3① 要求的"同世界 0→360 单段墙钟耗时 vs 改前基线"**本批没跑**（不归我跑 world）⇒ 如实列 §9-1。

## 8. 可能受影响的既有测试断言（**扫描结论，未跑 test**）

- **会改数值的（§9 清单）** ⇒ 断言"某关账日的迁移/择业/阶层/债务读数"的用例可能变值：
  高风险 `simos-app/.../time/EconomyCycleHealthTest`（真三国世界 240 天、8×30 天段；段内含 ≥2 关账日或段内
  发生迁移/模式变迁时读数会变）、`simos-economy` 的结算链用例。
- **编译面**：形参类型 `EconomyData` ⇒ `EconomyDayView` 的 13 个函数，**唯一可能红的是**：测试里显式写
  `EconomyData` 变量传给它们（仍编译，因 `EconomyData implements` 该接口）或**反射取形参类型/强制转换**（会红）。
  未逐类通读测试树 ⇒ 这是**扫描结论不是证明**。
- 无影响（已核）：`ProductionRoleResolver.seedLegacyClassMemberships` 仍收 `EconomyData`（测试可直接调用）；
  `EconomyData` 组件/形状/编解码零变化 ⇒ 往返不变式、`EconomyCodec` 相关用例不受影响。

## 9. 会改变数值行为的清单（给测试代理当输入）

1. **迁移/择业全链**（`ModeMigrationPolicy.plan` → `prospect` → 运力/议价权/排序表）：同一关账日现在按**当刻**家户行、
   归属、产业、组织、在途算 ⇒ 与"逐日推进"同源（改前按段首快照）。
2. **`ExpectedProfitBook.merchantEnterpriseOf`**：从"看不见当天新建的 merchant 组织"变成**看得见**（类注已改写）。
3. **`capitalizeArrears` 的端点解析**（`DebtPartyResolver.resolveActor/resolvePayee`）：按当刻 `classes()`/`industries()`
   解析债务人/债权人 ⇒ 段内迁移过或已消亡的家户可能解析结果不同（解析不到仍 fail-closed 抛，语义不变）。
4. **`haulServiceHexes`**（"服务成市"格集）与运力池装配：按当刻市场价判 ⇒ 自适应价格下集合可能变。
5. **`FxRoundInput`**：储备上限按当刻 `moneyIssuances()`（改前按段首累计）。
6. **E6a 模式变迁**：段内已执行的变迁不再被后续各日重复当"到期"（旧行为是每天白进一次 `apply`、内部 no-op）。
7. **政府铸币/发债**：按当刻 `industries()`/`governments()`。
8. ★ **逐日 advance（每段恰好 1 个关账日）的世界**：`base` 已含上次关账的值 ⇒ 本批绝大多数点**逐值不变**；
   `merchantEnterpriseOf`/`capitalizeArrears` 两类仍可能在"段内当天发生过组织/迁移"时变值。
9. **日志面**：`HAUL_SERVICE_PRODUCED(_DETAIL)`、`FX_WINDOWS_*` 的读数按当刻算（只影响输出，不影响状态）。

## 10. 未完成 / 未验证（如实）

1. **P-1..P-4 未实测**（本批只到编译过；未跑 world/test/verify）。**预测**：P-3（单段 ≥2 由 B1 兑现）；P-4 变异方向
   明确（把任一 `dayView` 换回 `base` ⇒ 该读点回退到段首基态 ⇒ P-1/P-2 必红）；**P-1/P-2 是否全绿不由本批保证**
   —— §9-2 的 `CycleAccumulator` 与账本 §1-6 的"账户往返"两条残余候选未做端到端二分。
2. ⚠ **OPEN（本批不改，需控制方裁定）**：`EnterpriseProfitBook.CycleAccumulator` 是**会话锚定**的跨日累加器
   （`recordDay` 逐日累加、只在关账日 reset）⇒ 若将来把 `Book` 接进决策（现在只进日志），1 段/逐 tick 两条路径的
   汇总窗口不同（30 天 vs 1 天），**必然**产生路径效应。非持久修法只有一条：把窗口改成"关账日当天 + 关账 unit 的
   持久周期累计"（`closeFacts` 来自 `ProductionProcess` 的持久累计 ⇒ 路径无关），但那是**迁移权重口径**的设计改动
   ⇒ 停手上报，未动。
3. **未做端到端二分**：调查账本 §3.2 的三条候选只做了**静态排除**（§1-6），未在真实 world 上逐条摘除验证；
   `OwnershipBooks.loadAccountSession/landAccountSession` 的往返是否逐值无损**未验**（app 侧，本批未动）。
4. **未跑其它世界**（three-powers / corridor / v17levant / 3600 tick）；未验证 120 天周期世界。
5. **未做变异自证**（改坏 ⇒ 红 ⇒ 还原）——按纪律留到测试轮。
6. 测试树**未逐类通读**（§8 是扫描结论）。

## 11. 复现命令（本批）

```bash
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -DskipTests compile -am          # ⇒ BUILD SUCCESS（16 模块）
# 逐日读者清单
grep -n "dayView" simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
grep -n "B2-保留段首base" simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
# 性能红线
grep -rn "new WorkingDayView(" simos-economy/src/main/java            # 唯一构造点
grep -rn "session.build()\|\.preview()" simos-economy/src/main simos-app/src/main
```

## 12. 额外静态审计（搬函数后"别处还有裸调用点"那类漏改）

- **被迁移函数的全部 main 调用点都核过**（`grep` 全仓 main）：
  - `MerchantCapacityPool.{hasCapacityAt,hexCapacityMilli,memberCapacityMilli,sharePerMilleAsProviderAt}`
    ⇒ 只被 `ModeMigrationPolicy:673/1274` 与 `PrimaryModeRanking:286` 调用，实参全是视图；
  - `DebtPartyResolver.{resolveActor,resolvePayee}`（`:7062/7064`）只被 `capitalizeArrears(data=视图)` 调用；
  - `ProductionRoleResolver.resolveCurrent` ⇒ `EconomyEnterpriseSettlement:755`（视图）、`DebtPartyResolver:404`（视图）、
    `HouseholdEconomyCommands:157`（**命令面**，`base` = revision 边界的 `EconomyData` ⇒ 正确，不改）；
  - `ExpectedProfitBook.prospect` ⇒ `ModeMigrationPolicy`（视图）+ app `GovernmentServiceDesertionBridge:854`（revision 边界）。
- **`EconomyData implements EconomyDayView` 不改变 Jackson 形状**：接口 15 个方法名与 record 组件名**逐字相同**
  ⇒ 即使某台 mapper 把无 `get` 前缀的同名方法当属性，也只会落在既有组件属性上（不新增/不丢字段）。
  `EconomyCodec` 无自定义 visibility 设置（`grep setVisibility = 0`）。
