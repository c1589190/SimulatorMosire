# W3 实现架构账本 —— 人的进出（两段式 tick + 死亡率移交）

> 责任区：约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（v3.10）§6.1 的 **W3 行**
> （① 日循环两段式 ② 缺口 = 生产花费（内含口粮）未满足量，逐户报 ③ `FAMINE_MORTALITY` 缩为"报缺口"
> ④ Social 据此算死亡率（含饿死）与劳动；S-11 第②半）。
> 写范围：`simos-economy/src/main/**`、`simos-social/src/main/**`、`simos-app/src/main/**`（未碰 api 契约模块）。
> 基线：`HEAD e3279670`（W2-fix7）；只跑 `spotless:apply` + `-DskipTests compile -am`；不写/不跑测试；不 commit。

---

## 一、关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（改前 `file:line`） | 结论 | 对 W3 的影响 |
|---|---|---|---|
| 1 | `simos-app/.../PopulationEconomyTimeParticipant.java:616-624`（旧） | 日循环旧次序 = **日初先 `HouseholdBook.settleOneTick`**，再刷新 composition/labor/needs，再 `applyHouseholdPopulationDeltas`，**然后**才 `stepper.step(day)` | 与用户 0–8 第 0 条原话相反 ⇒ 必须把生死结算整段挪到经济之后（本批第①件事） |
| 2 | `simos-economy/.../EconomySettlement.java:2944-2993`（旧 `4d`） | 旧"饿死判据"= `applyFamine`（改人口 + 缩行劳动）+ `scaleLaborOfUnit`（缩产业全部配额），只在**关账日**、只对**关账 unit 名下的家户**跑；分子 = `carried + unmetToday[grain]`、分母 = `cycleNaturalNeedMilli`（**周期**窗口） | 这就是"经济侧判死 + 缩劳动"的全部代码点 ⇒ 本批整段删除，换成"报缺口" |
| 3 | `EconomySettlement.java:397`（旧）`FAMINE_MORTALITY_PER_MILLE = 0`；`:919`（旧）4 参 `settle(..., int)`；`:160-176`（旧）6 参 `EconomyDayStepper` | 致死率是一个**包内可注入旋钮**（默认 0‰），入口链一直通到 `EconomyDayStepper` | "不再判死"必须是**删旋钮**，不是"默认 0" —— 留一条可注入的判死通道就等于没移交（§一.11：旧设计直接删） |
| 4 | `simos-economy/.../MarketReport.java:435` `unmetProductionCostByHousehold()`（W2 新增） | 逐户"生产花费（内含口粮）未满足量"的读口**已经存在**（`Σ BuyerOutcome.gapQty`，`desiredQty` 自 W2 = 生产花费） | W3 的①**不另算**：直接取这个读口（任务书 §1② 的要求） |
| 5 | `EconomySettlement.java:3925-3955`（旧，`consumeOneHousehold`） | 当日"吃不上"的量 = `unmetToday[grain] = naturalNeeds[grain] − min(库存, 需求)`；借贷阶段还会 `trimUnmet` 它 | 这就是任务书 §1② 的"家户吃不上的量"，且**已在全部救济之后** ⇒ 报缺口的两支都取现成量，零重算 |
| 6 | `ProductionLedger.Accumulator.marketReport()` + `EconomyDayStepper.step`（`:441-444` 旧） | `ledger.marketReport()` 是**当日**的（`new ProductionLedger.Accumulator(day)` 每天一个），`MarketSettlement.triggerFor` 决定开不开市（每 5 天 / 关账日 / 粮紧张日，`MarketSettlement.java:1296-1319`） | ⇒ 缺口报告里的"逐商品"那一支必须**当日取当日用**；**绝不能**读 `stepper.lastMarketReport()`（那个跨日留存 ⇒ 会破 B2 分段等价，W2 账本 §三-5 已点名这条坑） |
| 7 | `simos-social/.../HouseholdBook.java:728-968`（旧 3 参 `settleVitalEventsResult`） | Social 已有每 tick 生死：`基准率（SocialVitalRates/家户覆盖）→ numerator = 余数 + 份额 × ppm → applyEvents`；余数键 `(家户, 批次, DEATH)` | 饥荒输入只需**改当 tick 的分子**（`+ faminePpm`）⇒ 不新增持久组件、不动余数键/事件形状 ⇒ **铁律 5 不触发** |
| 8 | `simos-social/.../SocialData.java:755-800`（`householdLaborMilli`） | 劳动预算 = `基础劳动 × satietyPerMille ÷ 1000`（Z7d-1 唯一折算点），输入来自 `simos-app` 的 `HouseholdSatietyBridge`（断顿 → satiety，−150‰/日） | "社会据此扣**劳动**"这一半**已经存在**（不缺工）；W3 补的是**人口/死亡率**那一半。两条腿吃的都是同一个"当日粮缺口"量（口径一致，见 §三-3） |
| 9 | P0 先例 `docs/superpowers/plans/2026-10-10-p0-mode-migration-social-outbox.md` + `EconomySession` 的 `populationTransfers` 瞬态 outbox | "经济只推投影账 + 瞬态 outbox；app 同 revision 落另一域并回写 delta"的形制**已被验证**（逐户 138/138） | W3 的桥照抄这个形制（`EconomySession.recordHouseholdGap` / `drainPendingHouseholdGaps` / app 桥 / 同 revision 回写） |
| 10 | `simos-economy/pom.xml` 的 enforcer `<message>`："**方案 A（2026-10-09）：允许直接依赖 simos-social（人员/家户唯一域）**及其低层契约 social-api" | 任务书写的"economy 不得 import social"**与代码实际不符**（§四：机制性描述一律回代码核）；实际禁令是**反向**（social 不得依赖 economy） | 本批仍按**更严**的一侧实现：新代码里 economy 只用 `social.api.id.HouseholdId` 契约、social 只用 `economy.api.id.CommodityId` 契约、翻译只在 app |

---

## 二、实现架构（组件 / 数据流 / 调用次序）

### 2.1 组件清单

| 层 | 新增 / 改动 | 文件 |
|---|---|---|
| economy | **新增** 瞬态事实记录 `EconomyHouseholdGap`（逐户缺口：① 逐商品生产花费未满足量 ② 口粮缺口 + 当日需求 ③ 家户 id + 当日人口） | `simos-economy/.../time/EconomyHouseholdGap.java` |
| economy | `EconomySession` 加瞬态 outbox（`recordHouseholdGap` / `pendingHouseholdGaps` / `drainPendingHouseholdGaps`）；`EconomyDayStepper` 转发 `drainPendingHouseholdGaps()` | `EconomySession.java`、`EconomyDayStepper.java` |
| economy | `4d` 阶段**整段替换**：删 `applyFamine` / `scaleLaborOfUnit` / `deathsToday` / `FAMINE_MORTALITY_PER_MILLE` / 致死率入参链，改为 `reportHouseholdGaps(...)`；`SettlementStage.FAMINE_MORTALITY` 改名 `RATION_GAP_REPORT`；`FlowRow.deaths` 文档改口径 | `EconomySettlement.java`、`SettlementStage.java`、`FlowRow.java` |
| social | **新增** Social 自己的饥荒输入类型 `FamineMortalityInput`（`household → Shortage(rationGap, rationNeed, productionCostGapByCommodity)`）+ 缺省中性 `none()` | `simos-social/.../population/FamineMortalityInput.java` |
| social | **新增** 口径唯一拼写点 `FamineMortality`（断顿比例 + 线性 ppm 折算 + 具名常量） | `simos-social/.../population/FamineMortality.java` |
| social | `HouseholdBook`：4 参 `settleVitalEventsResult` / `settleOneTick(..., famineInput)`；死亡率 = 基准 + 饥荒项；INFO `FAMINE_MORTALITY` + DEBUG `FAMINE_MORTALITY_HOUSEHOLD`；`VitalSettlementResult` 加 `famineDeaths` 读数 | `HouseholdBook.java`、`VitalSettlementResult.java` |
| app（组合根） | **新增** 纯函数桥 `FamineMortalityBridge`（经济事实 → Social 输入 + 具名计数） | `simos-app/.../household/FamineMortalityBridge.java` |
| app（组合根） | `PopulationEconomyTimeParticipant` 日循环**重排为两段式**；`EconomyOwnershipTimeParticipant`（孤儿参与者）取走并丢弃缺口 outbox（防瞬态表无界增长） | 两个 `.../app/time/*TimeParticipant.java` |

### 2.2 两段式日循环（实际调用链，`PopulationEconomyTimeParticipant:616-780`）

```text
for day in (from+1 .. to]:                      // 同一次 simulateWorld = 同一条 revision
  ① Social 出当日视图      stepper.updateComposition / recomputeLaborBudgets / updateNaturalNeeds
                            （全部取自**当刻** currentSocial；此刻还没有算今日生死）
  ② 经济结算一天           ProductionLedger ledger = stepper.step(day)
                            └─ EconomySettlement.settleOneDayInto(...)
                                 ├─ … 消费 / 市场 / 借粮 / 还债 / 收获 / 迁移 …
                                 └─ 4d reportHouseholdGaps(...)        ← 只写会话瞬态 outbox，不动人
  ③ 经济报逐户缺口          List<EconomyHouseholdGap> = stepper.drainPendingHouseholdGaps()
                            FamineMortalityBridge.Report = FamineMortalityBridge.apply(gaps)
  ④ 断顿→饱食度回写（既有）  HouseholdSatietyBridge（劳动口径；Z7d-1）
     P0 迁移桥（既有）       MigrationSocialBridge → 经济行人口回写
     缺口基线刷新（既有）     previousCumulativeGrainUnmet = cumulativeGrainUnmetOf(stepper.flows())
  ⑤ Social 算死亡率（含饿死）VitalSettlementResult vital =
                              HouseholdBook.settleOneTick(currentSocial, day, clock, report.input())
                            └─ 逐户：effectivePpm = 基准率 + FamineMortality.famineRatePerMillionPerTick(gap, need)
  ⑥ 回写 + 刷新投影         stepper.applyHouseholdPopulationDeltas(vital.populationDeltas())
                            stepper.updateComposition / recomputeLaborBudgets / updateNaturalNeeds
  （之后仍是既有的 gov 段：税 / 预算 / 俸禄 / 逃亡；逃亡的 delta 走同一条回写口）
```

**原子性**：①–⑥ 全在同一次 `simulateWorld`（同一条 revision）内；任一步具名抛 ⇒ 整次 advance 不落 revision
（经济腿与 Social 腿一并作废）—— 与 P0 的同款语义。**B2**：缺口 outbox **逐日现算、逐日取走**，
`advance(0,360)` 一次 / 3 段 / 逐 tick 三份 dump 看到的每一天都是"那一天自己"的缺口。

### 2.3 报给 Social 的载荷形状（逐字段）

**app 内部契约**（两端都不是新持久类型）：economy 侧 = `EconomyHouseholdGap`（`simos-economy` 实现模块，瞬态）；
Social 侧 = `FamineMortalityInput`（`simos-social` 实现模块，入参）。`simos-app` 的 `FamineMortalityBridge` 是唯一翻译点。

| 字段 | 类型 | 来源（不重算） | 语义 |
|---|---|---|---|
| `EconomyHouseholdGap.household` | `HouseholdId`（`social-api` 契约） | 经济行键 | 家户**稳定身份**（铁律 1）；载荷按它对齐 |
| `EconomyHouseholdGap.population` | `long` | `HouseholdEconomy.population()` | 该户当日经济行人口（**只作核对/日志事实**） |
| `EconomyHouseholdGap.rationGapMilli` | `long ≥ 0` | 当日 `unmetToday[grain]`（消费步写、借贷 `trimUnmet` 之后） | **② 口粮缺口那一支**：家户当日"吃不上"的量 |
| `EconomyHouseholdGap.rationNeedMilli` | `long ≥ 0` | `HouseholdEconomy.naturalNeeds[grain]`（app 从 Social 注入） | 同窗口分母（Social 自己算断顿比例） |
| `EconomyHouseholdGap.productionCostGapByCommodity` | `Map<CommodityId, Long>`（值 > 0） | 当日 `MarketReport.unmetProductionCostByHousehold()`（W2 读口） | **① 生产花费（内含口粮）未满足量**，逐商品；没开市 ⇒ 空表 |
| `FamineMortalityInput.Shortage.rationGapMilli / rationNeedMilli` | 同上 | 桥原样搬运 | Social 的饥荒死亡率输入 |
| `FamineMortalityInput.Shortage.productionCostGapByCommodity` | 同上 | 桥原样搬运 | 进 Social 的审计面（DEBUG `FAMINE_MORTALITY_HOUSEHOLD` 打印）；**不入死亡率算式**（§三-2） |

**铁律 5 判定：不触发**。理由（三件都成立）：① 无新增/改动 `EconomyData` / `SocialData` 组件；
② `EconomyCodec` / `SocialCodec` / 两边的 ChangeSet **一个字未改**（本批 `git diff` 不含任何 codec/change 文件）；
③ 饥荒输入是**每 tick 入参**（与 `CalendarClock` 同制），不落盘、不跨 revision 存活。
`VitalSettlementResult` 加了一个组件（`famineDeaths`）——它是**瞬时返回值**（全仓 `new VitalSettlementResult` 仅 1 处、
不进 Snapshot/Codec），已核对无持久化路径。

---

## 三、关键判断（为什么这样拆 / 推翻过什么）

1. **桥落在 app，且只用瞬态 outbox**：与 P0 逐条同形制（先例已实测 138/138）。**不用 Social 工单**：
   工单是"持久事件的写口"（`work-order:` 幂等标记进状态），而饥荒输入是**每日入参**；走工单会把"每天的缺口"
   变成持久事件表（形状级改动 + 铁律 5 + 状态无界增长）。
2. **★★ 死亡率只认"口粮缺口那一支"（当日已实现缺口），不认"逐商品生产花费缺口"里的口粮维**（具名口径，可一行推翻）：
   后者是 `naturalNeeds × 35 天`（W2 的 `MARKET_LIFE_RESERVE_DAYS`）的**前瞻保留**，拿它当"今天吃不上"的比例，
   一个"还剩 3 天存粮"的户会得到 ≈914‰ 的断顿比例 ⇒ 饥荒从"逐步"变成"一次性团灭"，与"逐步涨/逐步死"的机制口径冲突。
   ⇒ 取**已实现的那一段**（当日真的没吃到，且已在市场/借粮/还债全部救济之后）。★ 任务书 §1② 正是把它定义为
   "家户**吃不上**的量"；① 仍按 §1① 逐商品**报到** Social（审计面）。要改用市场缺口 = 改 `HouseholdBook` 取哪个字段，算式不动。
3. **两条腿吃同一个量**：`rationGapMilli`（当日 `unmetToday[grain]`）与 `HouseholdSatietyBridge` 的"当日粮缺口"
   （`FlowRow.unmetNeed[grain]` 的日增量）在正常路径上**是同一笔账**（都在救济之后、都是当日窗口）⇒ "一个量贯穿买卖、还债、饿死"
   （S-10 的口径）成立；劳动（satiety）与人口（饥荒死亡率）各自折算、互不重复扣。
4. **饥荒项走既有 DEATH 余数档，不新增 `VitalKind`**：新增枚举档 = 改**持久**余数表的线格式（铁律 5 三件套）；
   而"基准死亡 + 饥荒死亡"在余数累加器里天然可加（同一个 `numerator`），且 sub-1 死亡仍跨 tick 累加
   ⇒ 长期速率精确、同状态重放逐字节相同。**死亡事件一条不多、一条不少**，只是 `reason` 里多了 `faminePpm=` 与 `cause=`。
5. **`rationGapMilli` 用当日（`unmetToday`）而不是周期累计（`flow.unmetNeed`）**：旧判据是"饿满整周期"的**周期**窗口，
   而"每 tick 生死"是**日**窗口；两者混用会让"日死亡率"依赖周期边界。⇒ 缺口报告按**当日**（分子分母同窗口），
   并把这条例外**具名**写进报告（它会改变数值行为）。
6. **`SettlementStage.FAMINE_MORTALITY` 改名 `RATION_GAP_REPORT` 而不是"留名改内容"**：阶段枚举是**可审计的具名序**（类注即用途）；
   名字写着"饿死/劳动缩放"而实际只报缺口 = 把假话钉在编译期常量上（§四 的同族教训）。
7. **`EconomyOwnershipTimeParticipant`（main 作用域的孤儿，只有测试夹具实例化它）也要 drain**：它没有 Social 一侧，
   ⇒ "取走并丢弃 + DEBUG 计数"。不这么做，瞬态 outbox 会在一次长推进里按"天数 × 家户数"无界增长（内存，不是状态）。
8. **推翻过的一版**：最初想让 Social 的饥荒输入直接吃"逐商品生产花费缺口的 grain 维 ÷ 35 天"（把前瞻折算成日均），
   随后否决 —— 那既引入一个我自造的除法口径，又与"当日已经吃不上"这条**已实现**的事实重复计一次。

---

## 四、偏离记录（与约束设计书 / 任务书不一致之处，主动记录）

1. **任务书 §2 写的"`simos-economy` 不得 import social"与 pom enforcer 实际相反**（`simos-economy/pom.xml` 的 ban message：
   "方案 A（2026-10-09）：**允许**直接依赖 simos-social…"）。本批按**更严**的一侧实现（economy 新代码只 import
   `social.api.id.HouseholdId`），故不构成越界；但这条**口径差**请控制方回填 AGENTS/任务书（§四：机制性描述回代码核）。
2. **死亡率口径的常数值是我定的（控制方实现口径，不是用户裁定）**：`FamineMortality.FAMINE_MAX_PPM_PER_TICK = 1000`
   （= 断顿 1000‰ 时 1‰/tick，线性、向下取整、缺口 0 ⇒ 恒 0）。它是**W6 标定项**；"线性 vs 带阈值/超线性"也在同一格。
3. **`FAMINE_MORTALITY` 的 INFO 触发门槛 = 当日有任一户带口粮缺口**（不是"必须真死了人"）：真死数常为 0（余数未满），
   只报"死了人"会把这个机制**藏起来**。⇒ INFO 报"谁在挨饿 + 死数（可能 0）"，逐户明细在 DEBUG/TRACE。
4. **旧"周期累计缺口 ÷ 周期需求"的分母口径（`cycleNaturalNeedMilli`）随判死一起作废**：缺口改按当日报。
   `HouseholdEconomy.cycleNaturalNeedMilli` 字段**保留**（它仍是"本周期总需求"的合法读数，别处仍在累加），只是**不再**是本判据的分母。
5. **`FlowRow.deaths` 字段保留、结构性恒 0**（不删字段）：它的形状属 v1 spec §3.3 的读口与守恒式，删它会动铁律 5 的形状；
   本批只把"谁写它"整段删掉（经济侧再不产生死亡）。★ 读口读到的 0 现在是**结构结论**。
6. **`SettlementStage.RATION_GAP_REPORT` 无代码引用**（与既有的 `ORGANIZE_PRODUCTION` / `REPAYMENT` / `INTEREST` /
   `POPULATION_WRITEBACK` 等一样）：它声明的是**日序**，不是分区依据；旧的 `FAMINE_MORTALITY` 同样零引用。**不删**（保留具名序）。
7. **`EconomyOwnershipTimeParticipant` 的"取走并丢弃"**（§三-7）：这是"孤儿参与者 + 无下家"的处置，
   不是"缺口不重要"；该参与者的唯一实例化点是测试夹具。

---

## 五、会改变数值行为的清单（本批预期非空；逐条具名）

| # | 条目 | 方向与幅度（算式级推断，**未跑真实 world**） |
|---|---|---|
| 1 | **日初 → 日末（两段式）**：经济结算当天读到的是**上一天末**的 Social 投影（composition / 劳动预算 / 当日自然需求 / 行人口 delta） | 人口与劳动的经济侧投影**整体滞后 1 天**；死亡/出生当天不再影响当天经济。逐户形态随轨迹变化（谁先动、谁拿到稀缺货） |
| 2 | **饥荒死亡率上线**（新机制）：`faminePpm = ⌊1000 × min(1000, ⌊gap×1000÷need⌋) ÷ 1000⌋` 加到基准死亡率上 | **新增死亡**：断顿 100% ⇒ +1‰/tick（≈+3%/月）；断顿 10% ⇒ +100 ppm/tick（对成年基准 33 ppm 是 +300%）。长期效应 = 缺粮户人口下降、总人口下降、劳动随之下降（S-11 第②半的"扣人口"真的发生） |
| 3 | **判死窗口：周期 → 当日** | 旧判据只在关账日、只覆盖关账 unit 名下的户、按周期累计；新判据**逐日、全部家户**、按当日。⇒ 即使致死率相同，触发时点与覆盖户集都不同 |
| 4 | **经济侧不再缩劳动**（`scaleLaborOfUnit` 删除；`applyFamine` 的行劳动同比例缩也删除） | 旧路径（致死率 0‰）本来恒不触发 ⇒ **默认世界逐值不变**；但 non-0 致死率的世界（若有 GM/测试注入）**不再**缩配额与行劳动。★ W2 的 `scaleLaborOfGroup`（外部逐批次死亡回写）**保留**，那条路不受影响 |
| 5 | **`FlowRow.deaths` 恒 0**（结构） | 读口/流水里"死亡"这一列不再变化（此前默认路径也恒 0 ⇒ 数值上等价，但"是结论"而非"旋钮为 0"） |
| 6 | **`FAMINE_MORTALITY` 判据的 `carried`（本周期累计）不再参与** | 旧口径下"上周期/本周期前期饿过"会累积到关账日一次结算；新口径只吃当日 ⇒ 长周期累计效应消失（部分被"逐日都算"抵消） |
| 7 | **`VitalSettlementResult` 加 `famineDeaths` 组件** | 瞬时返回值形状变化；数值行为零影响（只作读数/日志） |
| 8 | **日志新增 5 个事件名**（economy `HOUSEHOLD_GAP` / `_POOL` / `_REPORTED`；social `FAMINE_MORTALITY` / `_HOUSEHOLD`；app `HOUSEHOLD_GAP_BRIDGED` / `_DETAIL` / `FAMINE_MORTALITY_BRIDGED`） | **零数值影响**（只读、不写状态、不进算式） |
| 9 | **未受影响（具名）**：W2 的债务式清算 / §2.1 动态价 / D 全算 / 0 价 / 还债介质序 / 卖侧前瞻扣除 / `scaleLaborOfGroup` / `FlowRow.unmetNeed` 的累加口径 / `SocialVitalRates` 与家户覆盖 / 余数累加器（键与初相位哈希）/ 事件 id 与幂等键 |

---

## 六、受影响的硬编码字面量（给测试 Agent 当输入）

| 常量 | 值 | 落点 | 说明 |
|---|---|---|---|
| `FamineMortality.FAMINE_MAX_PPM_PER_TICK` | **1000**（ppm/tick @ 断顿 1000‰） | `simos-social/.../population/FamineMortality.java` | **新增**；本批唯一的死亡率标定值（控制方实现口径，W6 标定项） |
| `FamineMortality.FULL_SHORTAGE_PER_MILLE` | 1000 | 同上 | 断顿比例上限 |
| ~~`EconomySettlement.FAMINE_MORTALITY_PER_MILLE`~~ | ~~0~~ | 已删 | 连同 `settle(...,int)` 4 参重载、`EconomyDayStepper` 的致死率入参、`EconomySettlement` 的 `famineMortalityPerMille` 参数链 |
| `EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION` | `true` | 不变 | `EconomyDayStepper` 构造器的 5 参/4 参形态随致死率入参一起变化（**签名变化，值不变**） |
| `MARKET_LIFE_RESERVE_DAYS` | 35 天 | 不变 | ★ 它现在是"① 逐商品缺口"的量级来源（被点名**不**进死亡率） |
| `SocialVitalRates.defaults()` | 67/33/667 ppm、667 ppm 出生 | 不变 | 饥荒项是**加项**，基准表一字未改 |
| `HouseholdSatietyBridge.FAST_DROP_PER_DAY_PER_MILLE` / `SLOW_RECOVER_PER_DAY` | 150 / 20 | 不变 | 劳动那一半的既有标定 |

---

## 七、会让既有测试断言失效的清单（**只报不改**，按类名）

| 类 | 形态 | 原因 / 迁移方向 |
|---|---|---|
| `simos-economy/.../time/EconomyFixtures.java` | **编译错误**（不止断言） | `:110` 引用已删的 `EconomySettlement.FAMINE_MORTALITY_PER_MILLE`；`:119/:132` 用旧 5 参 `EconomyDayStepper`（致死率入参）。⇒ 迁移：删掉该重载/参数（fixture 的 4 参 `advance` 直接走新 5 参构造器）。★ 它会让 **simos-economy 整棵测试树** test-compile 失败，测试 Agent 必须先修这一处 |
| `simos-economy/.../time/GovServiceCommitmentC7Test.java` | 运行时失败（反射） | `:478` 反射查找已删的 `scaleLaborOfUnit`（`private static`）⇒ 该用例要么删、要么改成测 `applyPopulationChange`（`scaleLaborOfGroup` 仍在） |
| `simos-social/.../household/HouseholdVitalEventsTest.java`、`simos-social/.../codec/SocialHouseholdCodecTest.java` | **应仍然绿**（走 3 参 `settleVitalEvents`，缺省中性） | 无需迁移；★ 请测试 Agent 确认"3 参 = 饥荒项恒 0"这条缺省中性 |
| `simos-app/.../logging/SocialLoggingTest.java` | **应仍然绿** | `:182` 用 `assertEvent(lines, "POPULATION_SETTLE", "origin=social-settle", "originKind=tick", "day=0")`（包含式断言）；我在同一条日志**追加**了 famine 计数（旧字段一个没动）。★ 但"事件名覆盖面"要补：新增 `FAMINE_MORTALITY`（INFO）/ `FAMINE_MORTALITY_HOUSEHOLD`（DEBUG） |
| `simos-app/.../logging/EconomyLoggingTest.java` | **应仍然绿** | 只调 3 参 `EconomySettlement.settle`（签名未变；被删的是 4 参重载）。★ 新增事件名（`HOUSEHOLD_GAP*`）可能要补进清单 |
| `simos-app/.../world/EconomyOwnershipFixture.java` | **应仍然绿** | 只实例化 `EconomyOwnershipTimeParticipant`；该参与者新增的 drain 不改变其行为（且它本来就走"无 Social"的路） |
| `simos-economy/.../time/EconomyFixtures.java:372`、`simos-app/.../world/CompactThreeNationsWorld.java:575` | **应仍然绿** | 两处只是把 `flow.deaths()` **原样搬运/求和**进夹具的 FlowRow 与探针输出（无"deaths>0"的断言）；新代码该字段结构性恒 0 与旧默认路径（致死率 0‰）**逐值相同** |

---

## 八、判据自检（本批实测 = 只到"编译过"；其余如实记为未验）

- **编译门禁**：`tools/mvn-lock.sh -q spotless:apply` rc=0；`tools/mvn-lock.sh -q -DskipTests compile -am`
  rc=0（基线轮同样 rc=0，见 `/tmp/w3-baseline-compile.log`）。
- **economy 侧无判死（grep 证据）**：`grep -rn "applyFamine|scaleLaborOfUnit|FAMINE_MORTALITY_PER_MILLE|famineMortalityPerMille|deathsToday" simos-economy/src/main`
  ⇒ **7 命中，全部在注释/javadoc 里**（逐条已核），**零代码引用**。
- **模块边界（grep 证据）**：新增的 economy 文件只 import `social.api.id.HouseholdId`；新增的 social 文件只 import
  `economy.api.id.CommodityId`；翻译只在 `simos-app`。
- **未验（如实）**：真实 world 一条都没跑（本批不许跑 test/verify/package，也不许起服务）；逐户一致、B2 三份 dump md5、
  Revision Δ、日志真实行数、饥荒死亡幅度**全部未验** —— 交 W6/测试 Agent。

---

# W3b 测试树迁移

> 责任区：把 `src/test` 迁到 W3 的新行为、让**测试树能编译**（唯一硬指标）。基线 = `HEAD 57d74f26` + 工作树里 W3 的
> 13 个 main 改动（未提交，本批**一个字节没碰**）。只动 `simos-economy/src/test/**`、`simos-app/src/test/**`；
> 不 commit、不跑 `test`/`verify`/`package`/`clean`。允许命令仅 `tools/mvn-lock.sh -q spotless:apply` 与
> `tools/mvn-lock.sh -DskipTests test-compile -am`。

## W3b-1 逐类处置表

| 类（模块） | 处置 | 一句理由 |
|---|---|---|
| `simos-economy/.../time/EconomyFixtures.java` | **迁移（编译错误）** | `:110` 引用已删常量、`:132` 用旧 5 参 `EconomyDayStepper`（致死率入参）；删掉 5 参 `advance` 重载与 `famineMortalityPerMille` 形参，改走新 5 参构造器（`EconomyParallelism.singleThreaded()`）——**构造出的世界逐值不变**（旧默认致死率 0‰ 本就恒不触发；旧 5 参构造器也只配 `singleThreaded`） |
| `simos-economy/.../time/GovServiceCommitmentC7Test.java` | **删除 2 用例 + 2 反射助手 + 1 import；类注/④节改写** | `:478` 反射的 `EconomySettlement.scaleLaborOfUnit` 已随经济侧判死/缩劳动通道整段删除（§6.1 W3 行 + §7 v3.11 + AGENTS §一.11）；两个用例（`famineUnitScaleKeepsGovWhole` / `famineUnitScaleAllowsGovServiceOverBudgetOnceProductionIsScaledToZero`）断言的是**已不存在的机制本身**，无等价判据可迁 |
| `simos-app/.../time/Z7HungerTest.java` | **只改注释（时点口径）** | 新两段式 tick 下 day1 的 office 读数在 ⑥「用新 Social 重刷劳动预算」之后才算 ⇒ 原文"day1 用日初（未饿）预算算 ⇒ efficiency 仍 1000"已不成立；断言一条未动（本用例本来只对 day2 断言 `< 1000`） |
| `simos-social/.../household/HouseholdVitalEventsTest.java`、`simos-social/.../codec/SocialHouseholdCodecTest.java` | **不改（确认缺省中性）** | 走 3 参/2 参 `settleVitalEvents` ⇒ `FamineMortalityInput.none()` ⇒ `HouseholdBook.java:840-843` 取 `famine == null` 分支、`:924` `effectiveDeathPpm = baseDeathPpm + 0` ⇒ 与 W3 之前**逐值相同** |
| `simos-app/.../logging/EconomyLoggingTest.java`、`simos-app/.../logging/SocialLoggingTest.java` | **不改（确认不误红）** | 两类的断言全是 `anySatisfy`/`contains` 式；`POPULATION_SETTLE` 的旧字段一个没动（W3 只在同一行**追加** famine 计数字段，`HouseholdBook.java:1055-1087`）⇒ 新事件名不可能把包含式断言弄红 |
| `simos-app/.../world/EconomyOwnershipFixture.java` | **不改（确认无副作用）** | 只实例化 `EconomyOwnershipTimeParticipant`；W3 在该参与者新增的 `drainPendingHouseholdGaps()` 是"取走并丢弃 + DEBUG"（不写状态、不改数值）；且它与 `PopulationEconomyTimeParticipant` 从不同世界共存 |
| `simos-economy/.../time/DeathDebtWriteOffTest.java`、`GovServiceCommitmentC7Test` ③、`ProbeEconomy*` | **不改（确认不受影响）** | 走的是**保留**的 `applyPopulationChange`/批次缩放与测试自带探针引擎（`ProbeEconomy` 与生产结算零引用），W3 未动这两条 |

## W3b-2 被改动语义的断言清单（逐条：旧期望 → 新期望 + 依据）

1. `EconomyFixtures.advance(base, goods, from, to)`（4 参唯一入口）
   - 旧：内部转调 5 参重载 ⇒ `new EconomyDayStepper(..., PLANTING_DRAWS_BEFORE_CONSUMPTION, EconomySettlement.FAMINE_MORTALITY_PER_MILLE)`。
   - 新：直接 `new EconomyDayStepper(base, accounts, singleHex(...), PLANTING_DRAWS_BEFORE_CONSUMPTION, EconomyParallelism.singleThreaded())`。
   - **期望值不变**（不是"改成新数"）：改动前 `FAMINE_MORTALITY_PER_MILLE = 0`，且旧 5 参构造器把并行度也钉成 `singleThreaded()`（`git diff` 的删除块逐行可核）⇒ 夹具构造的世界逐值相同。依据：§6.1 W3 行（旋钮整条删除，不是"默认 0"）+ AGENTS §一.11。
   - `EconomyFixtures.stepper(base, goods)`（2 参）不受影响：新旧都落 `singleThreaded()` + 无致死率入参。
2. `GovServiceCommitmentC7Test`：删除的 2 个用例（旧期望："按存活比例缩 PRODUCTION 8000→7200 / 1000→0，GOV 整额跳过"）
   - 新：**判据不存在**（`scaleLaborOfUnit` 与整条经济侧缩劳动/判死通道已删）⇒ 无"新期望"可写，按 §3-②/§一.11 删除。
   - **判别力未被削弱**："GOV_SERVICE 不可缩 / underfed 合法"仍由本类 ①（`LaborQueueSettlement`）、②（`applyLaborBudgetsInto`）、③（`applyPopulationChangeInto` 的 `scaleLaborOfGroup`）、⑤（`reallocateLabor` pass②/pass③）四条**仍在生产代码里**的路径钉住；被删的只是第 4 条已不存在的路径。
   - 新语义（"经济侧不再缩劳动、只报缺口"，`EconomySettlement.reportHouseholdGaps` `:9714`）的判据属 W3 验收判据 ⇒ 归测试 Agent（§三.0 / §3-⑤）。
3. `Z7HungerTest`：**断言零改动**；仅把"day1 的 office 用日初未饿预算 ⇒ 1000"改为 W3 时点表述。
   - 依据（`file:line`）：`PopulationEconomyTimeParticipant.java:750`（⑥ 用当日新 Social 重刷劳动预算）在 `:817` 的 `computeGovEfficiency(...)` **之前**；而 `GovernmentServiceLaborBridge.java:286-289` 的实际劳动取的是 `currentHouseholdRows`（会话工作副本）⇒ day1 的 office 现在读到的就是 satiety=850 的劳动。
   - 该用例对 day1 efficiency 本就**没有断言**（只断言 day2 `< 1000`），故绿/红不受此注释影响。
4. `FlowRow.deaths`：**没有找到任何断言"经济侧有死亡数"的用例**（逐条已核）：
   - `deathsThisRound`（`LongRunMortalityProbeTest` / `NaturalMortalityProbeTest` / `ProbeGoldenReadingsFixtureTest`）来自**测试自带的 `ProbeEconomy`**（对 `EconomySettlement`/`EconomyDayStepper` **零引用**）⇒ 与 W3 无关，保留原样；
   - `EconomyFixtures.java:362` 只是把 `flow.deaths()` 原样搬运（读口，不判定）；`CompactThreeNationsWorld.java:575` 的 `cycleDeaths` 只进 `readings()` 输出，而 `readings()` 在测试树里**零调用点**（grep 证据）⇒ 没有断言；
   - `EconomyInvariantsTest.java:242` 是 `FlowRow` 构造函数对**负 deaths** 的具名拒（字段仍在 ⇒ 仍有效），不是"死亡数 > 0"的断言；
   - "人口守恒 / 逐户一致"类判据（如 `Z7FleeTest` 的源−2/目标+2、`CompactThreeNationsWorld` 的人口读数）**一条没删**。

## W3b-3 日志测试清单

- **结论：本仓没有"事件名清单"这种装置**（实测：`simos-app/src/test/.../logging/` 12 个类里没有 `List.of("EVENT_…")` 式清单，也没有任何按模块枚举事件名的用例；`grep -rn POPULATION_SETTLE` 在测试树只命中 `SocialLoggingTest:173`（注释）与 `:182`（单条 `assertEvent`））。
- 因而 W3 新增的事件名**不需要、也没有地方"补进清单"**；两类日志测试用的是包含式断言 ⇒ 新增事件**不会误红**（已核）。W3 新增、目前**无测试覆盖**的名字（如实留给测试 Agent）：
  - economy：`HOUSEHOLD_GAP`(TRACE) / `HOUSEHOLD_GAP_POOL`(DEBUG) / `HOUSEHOLD_GAP_REPORTED`(INFO)；
  - social：`FAMINE_MORTALITY`(INFO) / `FAMINE_MORTALITY_HOUSEHOLD`(DEBUG) / `FAMINE_MORTALITY_UNKNOWN_HOUSEHOLD`(WARN)；
  - app：`HOUSEHOLD_GAP_BRIDGED`(DEBUG) / `HOUSEHOLD_GAP_DETAIL`(TRACE) / `FAMINE_MORTALITY_BRIDGED`(INFO) / `HOUSEHOLD_GAP_DROPPED`(DEBUG，孤儿参与者)。
  - 给覆盖用例的前置条件（避免"空跑式假绿"）：`FAMINE_MORTALITY*` 只在**报来口粮缺口 > 0** 时出现（`HouseholdBook.java:1093`），走 3 参 `settleVitalEvents` 的现有夹具**永远不会**产生它。

## W3b-4 命令与结果（真命令 + rc + 关键输出）

```
$ tools/mvn-lock.sh -q spotless:apply          # rc=0（无输出；未产生格式改动）
$ tools/mvn-lock.sh -DskipTests test-compile -am > /tmp/w3b-compile-final.log 2>&1 ; echo rc=$?
rc=0
[INFO] --- compiler:3.16.0:testCompile (default-testCompile) @ simos-economy ---   # :360（改前红在这里）
[INFO] --- compiler:3.16.0:testCompile (default-testCompile) @ simos-app ---       # :441
[INFO] BUILD SUCCESS                                                                # :478
[INFO] Total time:  6.656 s     # 16/16 模块 SUCCESS（reactor summary 全绿）
```

- **改前基线轮**（同一命令，改前）：rc=1，唯一失败模块 = `simos-economy:testCompile`，两条 ERROR：
  `EconomyFixtures.java:[110,68] cannot find symbol: variable FAMINE_MORTALITY_PER_MILLE`；
  `EconomyFixtures.java:[132,13] incompatible types: int cannot be converted to EconomyParallelism`（日志 `/tmp/w3b-compile-1.log`）。
- 编译后核对 `target/test-classes` 里两类 `.class` 的 mtime 落在本轮（05:30:53/05:30:56）⇒ 不是拿旧产物当绿。
- `git status --porcelain` 复核：`src/main/**` 与 W3 的 13 个文件**一字未动**（本批改动只在 `src/test/**` 与本节）。

## W3b-5 未完成 / 未验证项（如实）

- **一条测试都没跑**（本批明令不许 `test`/`verify`）⇒ 全部"仍然绿"的判断都是**读代码 + 语义推理**，不是实测；W3 的
  两段式 tick 让"经济当天读到的是上一天末的 Social 投影"（本账本 §五-1），凡以**多个家户 × 多日 × 精确数值**为判据的
  用例都可能随轨迹漂移。**须由测试 Agent 实跑判红**。候选类（按"驱动真日循环 + 精确数值断言"筛）：
  `Z7HungerTest`、`Z7FleeTest`、`Z7RemittanceBridgeTest`、`Z7RemittanceE2ETest`、`Z7D1CapitalProvinceTest`、
  `Z7D4BudgetLedgerTest`、`GovernmentServiceLaborBridgeZ6Test`、`GovAdminSalaryBudgetZ6Test`、`GovGenesisZ6Test`、
  `GovToolsZ6Test`、`Z7MarketExclusionTest`、`ShellEndToEndTest`、`CrossProcessDeterminismTest`、`McpCoverageTest`。
- ★ **W3b 新发现（本账本 §五未列的第三条数值行为变化）**：**"断顿 ⇒ 劳动"对同日 gov 相位生效**。旧序里 day D 的
  office 效率用"日初预算"（satiety 截至 D−1 末）算，satiety 的当日写入只在日末 flight 刷新时才进经济行
  （`PopulationEconomyTimeParticipant.java:1057-1064`）；新序 ⑥（`:750`）在 gov 相位（`:817`）之前就用当日新 Social 重刷 ⇒
  **同一天**的 satiety 立刻影响当天的 GOV 覆盖率/效率。⇒ 唯一的"day1 效率 == 1000"断言
  `GovGenesisZ6Test.firstDaySettlesEfficiencyStipendAndSalaryWithoutShortfall:241` 属于**高危候选**（若该世界 day1 有任何
  户口粮缺口，W3 后该值会 < 1000）。
- ★ **台账 vs 代码（§四）**：本账本 §五-5 写 `FlowRow.deaths` "结构性恒 0" —— 对**可达**的生产路径成立（app 只走
  `applyHouseholdPopulationDeltas`，不写 deaths），但**未删的 `applyPopulationChange`/`withLifecycle`**
  （`EconomySettlement.java:4553/4941`，`:4644` 写 deaths）**仍能写出非 0 deaths**：它在 `src/main` 里**零调用点**
  （grep 证据），但被 `GovServiceCommitmentC7Test` ③ 与 `DeathDebtWriteOffTest` 直接调用 ⇒ 从测试看 `deaths` 并非恒 0。
  本批**不动生产代码**（越界），如实上报（若要让"恒 0"成为全仓结论，需 W4/W6 决定该 API 的去留）。
- 未做：`FlowRow.deaths` 读口的负向用例、`SettlementStage.RATION_GAP_REPORT` 的具名序用例、饥荒死亡率标定值用例 —— 均属
  新测试（§三.0 / §3-⑤ 明令不由本批写）。

---

# W3-fix（deaths 语义收口）

> 责任区：把 W3 账本 §五-5 与 W3b-5 写的「`FlowRow.deaths` **结构性恒 0**」换成**正确语义**（W3b 实测：未删的
> `applyPopulationChange` / `withLifecycle` 仍会写非 0 `deaths`），并把"社会报来的死亡（含饥荒那一份）"这条**读数**接进经济侧。
> 控制方裁定（本批任务书 §1）：`deaths` = 「**由社会（人口权威）报来的死亡数**」；经济侧是**接收方**，不是判定方。
> ★ 本批**不回头改** W3/W3b 的旧行（§五.4 留痕）——以本节为准。

## W3f-1 语义改动（`file:line` + 前后对照）

| # | 落点 | 改前 | 改后 |
|---|---|---|---|
| 1 | `simos-economy/.../model/FlowRow.java:35`（类注段） | 「`deaths` 自 W3 起**结构性恒为 0**…读口读到的 `deaths == 0` 是**结构结论**」 | 「`deaths` 的语义：**由社会（人口权威）报来的死亡数**；经济侧是**接收方，不是判定方**（不判死、不缩劳动）；唯一写口 `withLifecycle`，两条入口 = 逐户回写 + 批次回写；窗口 = 本周期累计」 |
| 2 | `FlowRow.java:81`（`@param deaths`） | 「W3 起恒为 0…它既不写这里的逐户 `births`/`deaths` 流水」 | 「**由社会报来**：Social 每 tick 结算（唯一判定方），App 把逐户净变化 + 逐户原值一起回写；经济侧只把报来的死亡数累加进本字段」 |
| 3 | `FlowRow.java:86`（`@param births`） | 「与 `deaths` 对称（R4 起人口两头都会动…）」 | 追加「**同一次回写里由社会报来**，经济侧同样只是接收方」 |
| 4 | `EconomySettlement.java:4537-4569`（`applyPopulationChange`） | javadoc **悬空**（写在 `:4349` 那一段，离方法 180 行；语法上根本没挂到方法上，方法本身无 javadoc） | 移到方法**正上方** + 新增一段「**接收方入口，不是判定方**」；`:4349-4352` 原位留指针注释说明已移动 |
| 5 | `EconomySettlement.java:4581-4586`（`applyPopulationChangeInto`） | 只有一句「session 形态」 | 追加「语义同 `applyPopulationChange`：接收外部报来的生死；删债是对**既成事实**的后果处理，不是决定谁死」 |
| 6 | `EconomySettlement.java:5022`（`writeOffDebtsForDeaths` 的 `@param deaths`） | 「本行的死亡数（≤ population；来自 `allocate`）」 | 「**社会报来的死亡**按人口权重摊到本行的那一份…这是经济侧为**已死的人**做债务善后，不是决定死多少人」 |
| 7 | `EconomySettlement.java:3229-3235`（日结算 `dayDeaths`） | 「经济侧不再产生任何死亡 ⇒ 当日死亡恒 0」 | 追加「`FlowRow.deaths` 的非 0 值来自**社会报来的死亡**，由逐户回写在**本步之后**写进同一行流水；`acc.deaths() + dayDeaths` 是原样带过，**不是清账**（清账只在 `newCycleHouseholds` 那一支）」 |
| 8 | `EconomySettlement.java:3458-3461`（`DAY_END` 的 `deaths` 字段） | 「本字段结构性恒 0，留作读口形状」 | 「本字段 = **本阶段（经济日结算）自己产生的死亡数** ⇒ 恒 0；社会报来的死亡另发 `HOUSEHOLD_VITAL_WRITEBACK`；**别读成"今天没人死"**」 |
| 9 | `EconomyDayStepper.java:528-533`、`:559-560`（两个入口的 javadoc） | 「把逐批次出生/死亡回写经济侧」 | 「把**外部（社会/命令面）报来的**…回写；这是**接收**入口，不是判死入口」；2 参重载加「不带生死流水（搬人 ≠ 生死）」 |
| 10 | `simos-app/.../crisis/CrisisMonitor.java:181-184`（读口） | 无注释 | 「`flow.deaths()` = **社会报来的本周期死亡数**；窗口 = 本周期累计，与 `population`（当日存量）**不同窗口**」 |
| 11 | `simos-app/.../gui/ApiViews.java:4125-4127`（读口） | 无注释 | 同上（语义 + 窗口 + 饥荒死亡是子集） |

**命名（任务书 §1 第三条）**：核对后**不改名**——经济侧没有任何"听起来像判死"的 API：两个入口（`applyPopulationChange` /
`applyHouseholdPopulationDeltas`）名字都是「apply（接收）报来的人口变化」，误导点在**javadoc 缺失/悬空**（已按上表补齐）；
`writeOffDebtsForDeaths` 是「为死者做债务善后」，也不是判死。⇒ 按任务书「改名**或**加明确注」的**后一支**走；**不留兼容别名**（§一.11）。

## W3f-2 `simos-economy/src/main` 里写 `deaths` 的点（逐个说明）

`grep -rn "\.deaths()\|new FlowRow(" simos-economy/src/main` 的全部命中，逐条定性：

| # | `file:line` | 是不是"判定" | 证据 |
|---|---|---|---|
| 1 | `FlowRow.java:106`（record 组件）/ `:136-137`（非负守卫） | ❌ 存储 + 守卫 | 只把调用方给的数存下来；`deaths < 0` 具名拒；**无任何公式** |
| 2 | `FlowRow.java:241`（`withAdditionalGrainTaxPaid`） | ❌ 原样搬运 | 新 record 的 `deaths` 槽位直接填 `deaths`（税那一支不动人口列） |
| 3 | `EconomySettlement.java:5147`（`withLifecycle`，**唯一累加点**） | ❌ 落账 | `base.deaths() + deaths`（`:5163`）；入参由调用方**原样**传来；方法内零判定、零公式、不缩劳动；缺席行以全零起步 |
| 3a | ↳ 调用点 `EconomySettlement.java:4665`（批次侧 `applyPopulationChangeInto`） | ❌ 接收 | 数来自 `LotChange.deaths()`（economy-api 桥类型，类注原文：「出生/死亡的**判定**在 social，**落实**在 economy」）；★ **实测 `src/main` 零调用点**，只有 `GovServiceCommitmentC7Test` / `DeathDebtWriteOffTest` 调用 |
| 3b | ↳ 调用点 `EconomySettlement.java:4902`（逐户侧，**本批新增**） | ❌ 接收 | `deathsByHousehold` 由 `VitalSettlementResult` 原样传来（Social 结算产物），经济侧只 `put` 进流水 |
| 4 | `EconomySettlement.java:3259`（`settleOneDayInto` 的流水重建） | ❌ 搬运 + 清账 | `(acc == null ? 0L : acc.deaths()) + dayDeaths`，而 `dayDeaths ≡ 0L`（`:3235`，本步不判死）；`acc == null` 只发生在**新周期第一天**（清账），不是重算 |
| 5 | `EconomyCodec.LegacyFlowRowDeserializer`（`:471-493`） | ❌ 反序列化 | Jackson 读回字段值（含旧键 `key` 整形）；不是判定 |

**「经济侧不再判死」的可核证据（grep）**：

```
$ grep -rnE "applyFamine|scaleLaborOfUnit|FAMINE_MORTALITY_PER_MILLE|famineMortalityPerMille|deathsToday" \
    --include=*.java simos-economy/src/main/java
6 命中，逐条已核全部是注释/javadoc：EconomyDayStepper:132、EconomySettlement:379 / 3230 / 4555 / 9893、FlowRow:37
⇒ 零代码引用：判死通道（致死率常量 + 按缺口处死 + 劳动缩放）确实不在生产代码里。
```

★ 经济侧对"人"的唯一动作仍是**报缺口**（`EconomySettlement.reportHouseholdGaps`，类注 `:9893`）；本批只多了一条
「接收社会报来的生死并落进流水」——**接收**，不是判定。

## W3f-3 famine 死亡是否已进经济读数：改前**否**，本批接上

**改前的事实链（读数缺口）**：Social 的饥荒死亡与基准死亡**共用 DEATH 余数**（`HouseholdBook.java:924-931`），逐户死亡进
`VitalSettlementResult.deaths()` / `famineDeaths()`；但 App 只把**净变化**回写经济（旧 `PopulationEconomyTimeParticipant.java:752`
= `applyHouseholdPopulationDeltas(vital.populationDeltas())`），而逐户入口**只写 `population`、不写流水**（旧 `:4778-4785`）
⇒ `FlowRow.deaths` 在**主路径上恒 0** ⇒ `CrisisMonitor` 的 MORTALITY 灯**结构性不可达**。

**接法（复用既有写口，不新增机制）**：

1. **Social 报出逐户原值**（净变化读不出原值）：
   - `HouseholdBook.java:832-833` 两张新表；`:1033-1041` 在**已经算好 `householdBirths`/`householdDeaths` 的同一处**填表（只填 > 0 的家户）；
   - `VitalSettlementResult.java:48-49` 两个新组件；`:111-152` 构造期逐户自洽核：`Σ births == births`、`Σ deaths == deaths`、
     `births − deaths == populationDeltas`（缺键按 0）⇒ 「报出去的生死」与「落到经济行的净 delta」不可能各说各话。
2. **App 一并回写**（同一次修订、同一次写入）：`PopulationEconomyTimeParticipant.java:758-760`
   `stepper.applyHouseholdPopulationDeltas(vital.populationDeltas(), vital.birthsByHousehold(), vital.deathsByHousehold())`。
3. **经济侧落账**：`EconomyDayStepper.java:582` → `EconomySettlement.applyHouseholdPopulationDeltasInto`（四参，`:4747`）
   - ①b/①c `:4843-4878` 先全量校验（三张表的 null / 0 / 负 / 缺行 + `net == births − deaths` 交叉核对），
     任何坏输入在**写任何一行之前**具名拒；`requireReportedVitalCounts` 在 `:4954`；
   - ③ `:4888-4905` 逐行 `withLifecycle`（唯一写口，调用点 `:4902`）把报来的出生/死亡累加进 `FlowRow.births` / `FlowRow.deaths`；
     循环走**家户行序**（与 `settleOneDayInto` 的流水键序同一规则）⇒ 状态键序仍是输入的确定性函数；
   - **不**摊批次、**不**缩劳动、**不**删债、**不**改 `naturalNeeds`。
   - 2 参重载 `:4715` 一字未改（迁移桥 `:713`、逃亡 `:1062` 走它 ⇒ 搬人**不**产生出生/死亡流水）。

⇒ **famine 死亡现在会落进 `FlowRow.deaths`**（它是子集）：`VitalSettlementResult.java:82-87` 的 `famineDeaths ≤ deaths` 不变量
+ `Σ deathsByHousehold == deaths`。★ **缺省中性**：某天完全无生死 ⇒ 三张表都空 ⇒ 早退，逐值不变。

**新增日志**（§一.9；只读、不写状态、失败不影响结算）：

- `HOUSEHOLD_VITAL_WRITEBACK`（`:4907-4926`）：`households / births / deaths / unit / why`；**死亡 > 0 ⇒ INFO**，
  只有出生 ⇒ 同事件名 DEBUG（避免每天一条 INFO 噪声）。
- `HOUSEHOLD_VITAL_WRITEBACK_REJECTED`（交叉核对 `:4857`、原值表校验 `:4979`，均 ERROR）：`reason / field / household`，
  取值 `null-household-key` / `null-count` / `negative-count` / `zero-count` / `unknown-household-row` / `lifecycle-delta-mismatch`；
  **先记 ERROR、再抛**（fail-closed）。
- 既有 `FAMINE_MORTALITY_BRIDGED`（app `:764`，INFO）仍在且 `famineDeaths` 字段未变 ⇒ "谁因什么死了多少"两条日志（Social + App）都在。

## W3f-4 命令与结果（真命令 + rc）

```
$ tools/mvn-lock.sh -q spotless:apply            # rc=0（第三次复跑确认无格式残留；只改了本批 8 个文件，无别模块连坐）
$ tools/mvn-lock.sh -DskipTests compile -am      # rc=0；[INFO] BUILD SUCCESS；16/16 模块 SUCCESS（含 economy/social/app）
```

- 日志：`/tmp/w3fix-compile3.log`（前两轮 `/tmp/w3fix-compile.log`、`/tmp/w3fix-compile2.log` 同为 rc=0）。
- `target/classes` 关键类 mtime 落在本轮（`05:46:18`–`05:46:22`）⇒ 不是拿旧产物当绿。
- `git status --porcelain` = **8 个文件**，全部在允许范围内（economy 3 + social 2 + app 3）；**未碰** `simos-unit` / `simos-sd` /
  `simos-core` / `docs/**` / `AGENTS.md`；**未 `git commit`**。

## W3f-5 会改变数值行为的清单

| # | 条目 | 是否改数值 |
|---|---|---|
| 1 | `FlowRow.births` / `FlowRow.deaths` 从"主路径恒 0"变为「**社会报来的逐户原值、本周期累计**」 | ❌ **不是公式变化**，是**状态列/读数**变化。已逐条核实：全仓 `src/main` 内**没有任何算式读** `flow.births()` / `flow.deaths()`（读点只有 `ApiViews.java:4128-4129`（GUI 视图）与 `CrisisMonitor.java:185`（GUI 危机灯；`lightsAt` 只在 `ApiViews.java:5674` 被调用 ⇒ 纯读、不回写状态）） |
| 2 | 受影响的**读数**（具名）：`ApiViews.flowView` 的 `deaths` / `births`；GUI 危机灯的 `MORTALITY` **现在可能亮**（`deaths×1000÷population > 20‰`；窗口 = 本周期累计 ÷ 当日人口，判据口径**未改**，只是分母/分子第一次有输入） | 读数变化（非模拟数值） |
| 3 | `VitalSettlementResult` **+2 组件**（`birthsByHousehold` / `deathsByHousehold`） | ❌ 瞬时返回值形状；不落状态、不进任何算式（唯一构造点 `HouseholdBook.java:1148`） |
| 4 | 新日志事件 2 个（`HOUSEHOLD_VITAL_WRITEBACK`、`HOUSEHOLD_VITAL_WRITEBACK_REJECTED`）+ 既有 `DAY_END` 注释订正 | ❌ 零数值影响 |
| 5 | 新入口重载（`EconomyDayStepper.applyHouseholdPopulationDeltas` 3 参 / `EconomySettlement.applyHouseholdPopulationDeltasInto` 4 参） | ❌ **纯增量**：2 参形态一字未改 |
| 6 | 日结算 `dayDeaths ≡ 0`、`DAY_END` 的 `deaths=0`、`acc.deaths() + dayDeaths` 算式 | ❌ 一字未改（只改注释） |

⇒ **模拟数值（人口 / 钱 / 货 / 债 / 价格 / 劳动）零变化**；变化只落在"经济侧的出生/死亡读数"与两条日志上。

## W3f-6 偏离记录 / 未完成 / 未验证（如实）

**偏离（主动记）**：

1. **没有改名，只加注**（任务书给的是"改名**或**加明确注"）：两个入口的名字都不含"判定"语义，误导点是 javadoc 缺失/悬空 ⇒ 走后一支。
2. **接线的载荷放在 `VitalSettlementResult`（social）+ 逐户入口（economy）**，而不是让 App 从 `vital.events()` 反推逐户生死：
   反推会在 Social 将来新增第三种生命事件时**静默漏记**；放进结果可带构造期不变量（Σ == 总数、逐户 == delta），
   并在经济侧再做一次**接收复核**（`EconomySettlement.java:4844-4878`）。这是读数缺口的 fail-closed 接法，不是新机制。
3. **`DAY_END` 的 `deaths` 键保留（值仍 0）**：`docs/superpowers/specs/2026-10-04-economy-logging.md:118` 把该字段写进了 `DAY_END`
   的字段清单，而 `docs/**` 本批**禁改** ⇒ 改名会造成"代码 vs 日志规格"漂移。改为：注释写清"这是**本阶段自己产生**的死亡数，
   别读成今天没人死"，并由 `HOUSEHOLD_VITAL_WRITEBACK` 承担"今天社会报来多少死亡"。
4. **`FlowRow.births` 一并接上**（不只 `deaths`）：同一张原值表、同一个写口；人口守恒读数（`年末人口 − 创世人口 == 出生 − 死亡`）
   要求两头都在。

**未完成 / 未验证**：

- **一条测试都没跑**（本批明令不许 `test` / `verify` / `package` / `clean`）；**也没跑 `test-compile`**（不在授权命令集内）
  ⇒ **测试树能否编译未实测**。静态评估：无任何测试构造 `VitalSettlementResult`（`grep -rn "new VitalSettlementResult(" .` 只 1 处，
  在 `HouseholdBook.java:1148`），两个旧入口的 2 参形态一字未删 ⇒ 预期不破编译；**仍须由测试 Agent 实跑确认**。
- **真实 world 一条没跑** ⇒ "接上后 `FlowRow.deaths > 0`"是**静态链条**（Social 逐户原值 → App 三参回写 → 经济 `withLifecycle`），
  **不是实测数字**。给测试 Agent 的候选判据：① 驱动真日循环 N 天后某户 `flows().get(h).deaths() > 0`，且 `Σ deaths` 与 Social 侧
  同周期 `deaths` 一致；② 把 `deathsByHousehold` 改坏 ⇒ 经济侧 `HOUSEHOLD_VITAL_WRITEBACK_REJECTED` 当场红（负向用例）；
  ③ 三张表空 ⇒ 逐值不变（缺省中性）；④ 高死亡率世界里 `CrisisMonitor.lightsAt` 能产出 `Kind.MORTALITY` 灯（改前结构性不可达）。
- **性能未测**：四参入口在"当天有生死"时多一次 O(家户行) 扫描（迁移/逃亡那两条路走 2 参、两表为空 ⇒ 不扫）。
- **`FlowRow` 的形状/往返不变式**未在本批验证（本批未改形状 ⇒ 不触及铁律 5 的新增面）。
- **文档漂移（只报不改）**：`docs/superpowers/specs/2026-10-04-economy-logging.md:110` 仍写「`13` 饿死｜有死亡 ⇒ INFO
  （closedUnits/deaths/unmet）」——该通道已随 W3 删除，"谁因什么死了多少"现在在 Social 的 `FAMINE_MORTALITY` 与 App 的
  `FAMINE_MORTALITY_BRIDGED`。请控制方决定何时追加标注（§五.4：不篡改历史行）。

---

# W3-fix2（D1 NPE + D2 famineDeaths 计数 + D3 文档漂移 + D4 day 字段）

- **批次**：W3-fix2（写码 Agent），日期 2026-10-11；父提交 `e1527e91`（工作树干净开工）
- **裁定来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.12 §7**；
  验收报告 `.superpowers/sdd/2026-10-11-w3-acceptance/report.md` §3
- **授权改动面**：`simos-app/src/main/**`、`simos-social/src/main/**`、`simos-economy/src/main/**`（**未**碰 `src/test/**`、simos-unit/sd/core、docs、AGENTS.md、simos-actor）
- **一次一个 Maven**：全程 `tools/mvn-lock.sh`，开工/收工 `pgrep -af "classworlds.launcher|surefirebooter"` = 空

## W3f2-1 D1 —— `CrisisMonitor` FOOD 灯的 null 撞 `Map.copyOf`（真缺陷，必修）

**根因**（验收报告 §3 D1 的栈原文）：`evidence.put("stressByAgeBracket", null)` → `new Light(...)` →
`Light` 紧凑构造器 `Map.copyOf(evidence)`（`CrisisMonitor.java:345`）**拒 null 值** ⇒ 只要 FOOD 灯亮，
`GET /api/social/population` **整条 500**（NPE），并把排在它后面的 **MORTALITY 灯整个遮住**。

**改动点（前 → 后）**：

| # | 落点 | 改前 | 改后 |
|---|---|---|---|
| 1 | `CrisisMonitor.java:205`（原 :199） | `evidence.put("stressByAgeBracket", null);` | `evidence.put("stressByAgeBracket", Map.of());` |
| 2 | `CrisisMonitor.java:37-38` 类注判据表 | "键保留并给 `null`" | "键保留并给**空表** `Map.of()`" + 点名 D1 与 fail-closed 守卫 |
| 3 | `CrisisMonitor.java:83-86` `STRESS_BY_AGE_BRACKET_UNAVAILABLE` 类注 | "读口保留键并给 `null`" | "读口保留键并给**空表**（给 null 会撞守卫 ⇒ 500）" |
| 4 | `CrisisMonitor.java:331-335` `Light` record 类注 | （无）| **新增**：`Map.copyOf` 是 fail-closed 守卫、**不许放松**；产 evidence 的每一处必须自己遵守"缺值放空表或**不放键** + 另给 `…Unavailable`，绝不放 null" |

**`Light` 的 `Map.copyOf` 一字未改**（`CrisisMonitor.java:345`）——按裁定，它是契约守卫，放松它 = 把"依据"的契约放松。

**★ 同类 null 扫查结果（逐处点名）**：

1. **`CrisisMonitor` 类内**：`grep -n "put(.*null"` = **1 处**（就是原 :199，已修）；修后 = **0 处**。
   该类 `lightsAt` 的**全部** 5 盏灯的 evidence 逐键复核，除该处外全是 `long` 原语装箱值与 1 个非空常量：
   FOOD（`grainSatisfactionPerMille`/`grainUnmetMilli`/`elapsedDays`/`cycleDays`/`stressByAgeBracket`/`stressByAgeBracketUnavailable`）、
   CLOTH（4 键 long）、MORTALITY（3 键 long）、DEBT（4 键 long）、LABOR_BURDEN（3 键 long）
   ⇒ **修后不存在任何 null 能到达 `Map.copyOf`**（静态逐键核，非推理）。
2. **三个授权模块全量扫**（`grep -rn "\.put([^)]*,\s*null\s*)" --include=*.java simos-app/src/main simos-social/src/main simos-economy/src/main`）：
   命中 **62 处**，逐处定性 —— **全部落在"直接序列化成 JSON 的视图表"**里（`gui/ApiViews` 46 处、`gui/GuiServer` 5 处、
   `tools/write/RunDecisionMakersTool` 9 处、`tools/read/GovInfoTool` 8 处、`tools/write/GmPeriodicAdjustmentTool` 2 处、
   `world/EconomySeeder` 1 处），这些表**不进任何 `Map.copyOf`/`Map.of` 守卫**（上述 6 个文件里 `Map.copyOf` 的**代码**命中 = 0；
   `ApiViews` 那 1 处命中是**注释**，`:4929`）⇒ **不是 D1 同类缺陷**（JSON 里 null 合法）。
3. **另两处 fail-closed `Map.copyOf` 守卫**（同族形态，逐个核过、**均无 null 生产者**）：
   - `simos-economy/.../EconomyLiquidationSettlement.java:1901` `AuditEntry` 的 `Map.copyOf(evidence)`：值是 `Map<String, Long>`，
     生产者（`:394/:462-479`、`:616-622`、`:679+`）全是 `long` 原语装箱 ⇒ 不可能为 null；
   - `simos-economy/.../MarketSettlement.java:643` `Map.copyOf(operatorConditions)`：状态组件 `Map<ProductionUnitId, OperatorCondition>`，
     值由 codec/变更集构造，无 `put(…, null)` 生产者。
4. **结论**：D1 家族（null 撞 fail-closed 守卫 ⇒ 读口 500）在授权面内**只有 `CrisisMonitor` 那一处**，已修；未发现第二处。

**预期效果（静态，未跑）**：FOOD 灯亮时 `GET /api/social/population` 不再因 null 500；同格 MORTALITY 灯（若判据成立）
随同一响应一起被读到。响应体的变化是 `"stressByAgeBracket":{}` + 原本就有的 `"stressByAgeBracketUnavailable":"…"`。

## W3f2-2 D2 —— `famineDeaths` 恒 0（W3-fix 新接线 bug，必修）：改为按事件 cause 直接计数

**根因**：`HouseholdBook.java:960-963`（改前）`floorDiv(deaths × faminePpm, effectiveDeathPpm)` —— 单户单日死 1 人时
`floorDiv(1×1000, 1033) = 0` ⇒ 读数系统性为 0（sw180 的 377 条 famine 致死读出 0），且 `famineDeaths ≤ deaths`
仍成立 ⇒ **门禁抓不到**（验收报告 D2）。

**改动点（前 → 后）**：

| # | 落点 | 改前 | 改后 |
|---|---|---|---|
| 1 | `HouseholdBook.java:63-71` | （无）| **新增**两个私有常量 `DEATH_CAUSE_BASE` / `DEATH_CAUSE_BASE_PLUS_FAMINE` —— `reason` 串与"算不算饥荒致死"**同一个拼写点** |
| 2 | `HouseholdBook.java:952-953` | `reason` 里 `" cause=BASE_PLUS_FAMINE"` / `" cause=BASE"` 两处字面量 | `String deathCause = famineRate… > 0 ? BASE_PLUS_FAMINE : BASE;` 再由它拼 `cause=` |
| 3 | `HouseholdBook.java:977-981` | `floorDiv(deaths × faminePpm, effectiveDeathPpm)` 累加 | `if (DEATH_CAUSE_BASE_PLUS_FAMINE.equals(deathCause)) householdFamineDeaths += 1L;`（**逐事件计 1 条**） |
| 4 | `HouseholdBook.java:1046-1058` | （无）| **新增逐户 fail-closed 守卫**：`householdFamineDeaths > householdDeaths` ⇒ `vitalReject(day, …)` |
| 5 | `VitalSettlementResult.java:39-46` | "由饥荒项贡献的那一部分（人）…按 ppm 折算" | "cause 含饥荒的**死亡事件条数**（条 ≤ 人数）"+ 记明 D2 裁定与旧式为何错 |
| 6 | `VitalSettlementResult.java:85-93` | 断言文案"饥荒是死亡的子集" | 断言文案"饥荒致死事件条数 ≤ 死亡人数"（**断言本身一字未动**：仍是 `famineDeaths > deaths ⇒ throw`） |
| 7 | `FlowRow.java:44-47` / `EconomySettlement.java:4737-4741` / `PopulationEconomyTimeParticipant.java:754-757` | 三处把 `famineDeaths` 说成"死亡的子集/同值投影" | 改为"**≤** 死亡人数（条数 ≤ 人数）"，口径与代码一致（D3 同族纪律：文档跟着代码走） |

**不变量断言的两处落点（都是 fail-closed，不静默夹取）**：
1. **逐户**：`HouseholdBook.java:1049-1058`（结算循环里、每户出口）；
2. **总量**：`VitalSettlementResult.java:85-93`（记录构造期，`famineDeaths > deaths ⇒ IllegalArgumentException`）。
   逐户 == 净变化（`births − deaths == delta`）与两张原值表的既有核对一字未动。

**★ 判据自检（逐值表；静态推演自代码，**未跑任何测试/world**）**：
设 faminePpm = 饥荒项（缺口 0 ⇒ 0），basePpm = 33，`effectivePpm = base + famine`；
每条 DEATH 事件 = 一个 (户, 批次) 当 tick 的一条记录，`count = deaths`（人数）。

| 情形 | 输入（构造） | 事件流 | `famineDeaths`（新） | 旧式 `floorDiv` | 备注 |
|---|---|---|---|---|---|
| **A. deaths=1 且 famine 参与** | share×1033 ≥ 1e6（含余数），faminePpm=1000 | **1 条** DEATH，`count=1`，`cause=BASE_PLUS_FAMINE` | **1** | `floorDiv(1×1000,1033)=0` ← D2 | 正是验收报告 §3 D2 的最小复现（`ratePpm=33 faminePpm=1000`） |
| **B. deaths=0** | 余数 + share×effectivePpm < 1e6 | **0 条**（`if (deaths > 0)` 不进） | **0** | 0 | 不产生事件 ⇒ 不计数（缺省中性） |
| **C. 纯 BASE 致死** | faminePpm = 0（无缺口 / 缺口 0） | 1 条 DEATH，`cause=BASE` | **0** | 0 | 对应 `nogap` 世界（22 死全 `cause=BASE` ⇒ 0，验收报告称为"正确"） |
| **D. 混合（同户同日）** | 批次甲 famine 参与且 deaths≥1；批次乙 faminePpm=0 且 deaths≥1 | 2 条 DEATH：1×`BASE_PLUS_FAMINE` + 1×`BASE` | **1**（= famine 参与条数） | 可能 0 | 等于"cause 含饥荒的条数" |
| D'. 混合（反例） | 同上但 famine 参与 2 条 + BASE 1 条 | 3 条 | **2** | ≤1（系统性偏小） | 守卫 `2 ≤ 3` 通过 |

**与验收报告的量化对齐**：sw180 的 `Σdeaths = 428` 与"famine 事件 377 + BASE 事件 51 = 428"**逐值相等**
（报告 §2.4 ②）⇒ 该世界里**每条 DEATH 事件的 `count` 恰为 1** ⇒ 新口径下 `ΣfamineDeaths = 377`（= famine 致死事件数），
旧口径恒 0。**这是"应有读数"的推导，不是我实测的数字**（本批不许跑 world/test）。

**★ 一个口径选择点（主动上报控制方，不阻塞）**：裁定原文是"该日该户的死亡里 `cause` 含饥荒的**条数**"、设计书写"`famineDeaths`
== famine 致死**事件数**"，故本批实现为**逐事件计 1**。若控制方要的是"人数"（famine 参与的整条事件按 `count` 计入），
改动是**一行**（`Math.addExact(householdFamineDeaths, 1L)` → `…, deaths)`），且**在所有可达世界里两者同值**
（本世界 `count` 恒 1；`share × effectivePpm < 2e6` 时 deaths 不可能 ≥ 2）。★ 两种读法都不改"谁死"。

## W3f2-3 D3 —— `actor.AdjustAccounts` 的载荷形状与解析器对齐（以解析器为准）

**解析器（权威，读代码）**：`ActorPayloads.adjustments`（`simos-actor/.../spi/ActorPayloads.java:240-272`）→
`AccountPayloads.household(node,"household")`（`AccountPayloads.java:60-71`）⇒ **只有一种形态**：

```
{"entries":[{"household":"hh-…", "goods":{"<商品id>": <有符号整数≠0>}?, "money":{"<币种id>": <有符号整数≠0>}?}, …]}
```

- `entries`：必填、非空数组；每项必须是对象；
- `household`：必填、**顶层字符串**（`HouseholdId.parse` 只校验非空白）；**没有** `owner{kind,id}`、**没有** `q`/`r`；
- `goods`/`money`：缺键 / JSON null ⇒ 空表；至少一张非空；键 = `CommodityId`/`CurrencyId`；值 = 整数且 **≠ 0**；
- 同一 `household` 在一份载荷里**只能出现一次**；
- 命令**没有格资源目标**：`AdjustAccountsHandler.targetPaths`（`:99-102`）恒 `List.of()`。

**逐字段对照表（工具 description 改后 ↔ 解析器）**：

| 字段 | 解析器要求（`file:line`） | 改后描述 | 一致 |
|---|---|---|---|
| `entries` | 必填非空数组（`ActorPayloads.java:241-244`） | "entries 必填非空" | ✅ |
| `household` | 顶层字符串、非空白（`:253-258` + `AccountPayloads.java:60-71`） | "每项 household 必填（家户 id 字符串）" | ✅ |
| `owner`/`q`/`r` | **不存在**（`:249-252` 只认 `{household,goods?,money?}`） | "载荷里没有 owner/q/r（旧形状会被具名拒）" | ✅ |
| `goods`/`money` 至少一张 | `:264-267` | "至少一个非空" | ✅ |
| 值 ≠ 0 / 有符号整数 | `:288-320`（`deltas`）+ `requireIntegral` | "有符号净增量、不得为 0" | ✅ |
| 同一 household 不重复 | `:259-261` | "同一 household 不得重复" | ✅ |
| 缺账 + 纯正增量 ⇒ 新建；负增量不得 <0 / 侵占冻结 | `AdjustAccountsHandler:140-190` | 逐字保留原文 | ✅ |

**改了哪些描述（file:line）**：

| # | 落点 | 改前 | 改后 |
|---|---|---|---|
| 1 | `simos-app/.../tools/write/ActorAdjustAccountsTool.java:16-25`（类注"载荷形状"） | `{entries:[{owner:{kind,id}, q, r, goods…}]}`；"每项 owner/q/r 必填" | `{entries:[{household, goods?, money?}…]}` + 逐字段必填口径 + **新增一段**"为什么不再有 owner+q/r（P2-A §13.3：位置从 `Household.location` 派生；旧形状不做兼容解析）+ 形状权威是解析器" |
| 2 | 同文件 `:53-59`（`description()`，即模型看得到的工具描述） | "每项 owner/q/r 必填；同一 (owner,q,r) 不得重复" | "每项 household 必填（家户 id 字符串）；**没有** owner/q/r（旧形状会被具名拒）；同一 household 不得重复" |

**★ `CatalogTool` 提示：查过，本来就是对的、一字未改**——`CatalogTool.java:684-692` 的 `PAYLOAD_HINTS["actor.AdjustAccounts"]`
已写 `entries[{household, goods{商品:有符号净增量}?, money{币种:有符号净增量}?}...]`（与解析器一致），演员形状的那处漂移**不在** catalog 里。
同族查过：`simos-app/src/main` 全量 `grep -rn "owner:{kind,id}" ... | grep -i adjust` 现在**只剩"没有 owner"这类说明句**；
`gov/army/Social` 等其它工具描述里的 `owner{kind,id}` 属于**它们各自的**命令形状（未受影响、未改）。

**★ 未做（越权项，需控制方裁定）**：`D3` 的第三处 —— **handler 类注**在 `simos-actor/src/main/java/io/mosire/simos/actor/spi/AdjustAccountsHandler.java:31-47`
（示例在 `:34-38`，仍写 `owner{kind,id}+q+r`），另有同文件 `:77-79` 说目标来自 `ActorPayloads#entryHexKeys`（而 `targetPaths` 恒空）。
**`simos-actor` 不在本批授权改动面内**（任务书 §2 的"允许改"只列了 app/social/economy 三个 main），故**只报不改**。修法（控制方授权后一行级）：
把 `:31-47` 的 pre 块换成 `{"entries":[{"household":"hh-1","goods":{"grain":-120},"money":{"silver":-30}}]}` 与逐字段说明，
把 `:77-79` 改成"家户账户无格 ⇒ `targetPaths` 恒空"。

## W3f2-4 D4 —— `HOUSEHOLD_VITAL_WRITEBACK` 补 `day` 字段

**改动点**：事件发射在 `EconomySettlement.java:4929-4943`（写口 `applyHouseholdPopulationDeltasInto`）。
`day` 往上两跳都在调用方手里（app 日循环的 `day`），故按**显式入参**往下传（不猜、不从状态反推）：

| # | 落点 | 改动 |
|---|---|---|
| 1 | `EconomySettlement.java:4934-4935` | `LogEvent.of("HOUSEHOLD_VITAL_WRITEBACK", source, **"day", day**, "households", …)` —— 字段名与同写口邻居（`POPULATION_SETTLE`/`FAMINE_MORTALITY`/`HOUSEHOLD_GAP_*`）一致 |
| 2 | `EconomySettlement.java:4758-4780` | 结算方法签名加 `long day`（第 5 参）+ **fail-closed 守卫**：`day < 0 && (births/deaths 非空)` ⇒ 具名拒（绝不用假日凑数） |
| 3 | `EconomySettlement.java:4715-4727` | 2 参重载（"搬人"路径，两表恒空）改用哨兵 `NO_WORLD_DAY = -1L`（该路径事件永不发射 ⇒ 哨兵不进任何输出）；签名保留 ⇒ 既有测试调用点不动 |
| 4 | `EconomyDayStepper.java:582-595` | 公开入口 `applyHouseholdPopulationDeltas(deltas, births, deaths, **day**)`（加第 4 参）+ javadoc |
| 5 | `PopulationEconomyTimeParticipant.java:759-760` | 调用点补 `day`（app 日循环里本来就有） |

**既有测试调用点静态核对**（保持可编译）：`ModeMigrationPolicyD024Test.java:581` 走 **2 参**重载（签名未动）、
`EconomyLoggingTest.java:241` 走 `EconomyDayStepper.applyHouseholdPopulationDeltas(Map)` **1 参**重载（签名未动）；
全仓 `grep -rn "applyHouseholdPopulationDeltas"` 已无第 3 处旧签名调用。

## W3f2-5 命令与结果（真命令 + rc + 关键输出）

```
$ pgrep -af "classworlds.launcher|surefirebooter"        # 开工与收工各一次
(no maven running)

$ tools/mvn-lock.sh -q spotless:apply
[spotless rc=0]

$ git status --porcelain                                  # spotless 未越界改别的模块
 M simos-app/src/main/java/io/mosire/simos/app/crisis/CrisisMonitor.java
 M simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java
 M simos-app/src/main/java/io/mosire/simos/app/tools/write/ActorAdjustAccountsTool.java
 M simos-economy/src/main/java/io/mosire/simos/economy/model/FlowRow.java
 M simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java
 M simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
 M simos-social/src/main/java/io/mosire/simos/social/household/HouseholdBook.java
 M simos-social/src/main/java/io/mosire/simos/social/household/VitalSettlementResult.java

$ tools/mvn-lock.sh -DskipTests compile -am 2>&1 | tail -45 ; echo "[PIPESTATUS0=${PIPESTATUS[0]}]"
[INFO] You have 0 Checkstyle violations.
[INFO] Compiling 330 source files with javac [debug release 21] to target/classes
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
  ... 16/16 全部 SUCCESS（Util/Map/Calendar/SocialApi/ActorApi/EconomyApi/Social/Unit/Core/SD/Actor/Economy/Gov/Army/App）
[INFO] BUILD SUCCESS
[INFO] Total time:  8.399 s
[PIPESTATUS0=0]
```

★ **未跑**（守任务书 §2）：`test` / `verify` / `package` / `clean` / `test-compile`；**未起任何服务、未跑任何 world**
（`simos-app/target/*.jar` 一字未动 ⇒ 不存在覆盖运行中 jar 的风险）。8 个文件的改动全部为 `src/main`。

## W3f2-6 会改变数值行为的清单（逐条具名）

**结论：本批不改任何模拟状态、不改"谁死"，只改读数与日志。** 逐条：

| 改动 | 会变的数 | 不会变的东西（依据） |
|---|---|---|
| **D1**（`CrisisMonitor`） | `GET /api/social/population` 的响应体：FOOD 灯的 evidence 从"整条 500"变为 `"stressByAgeBracket":{}`（+ 原有 unavailable 原因） | 零状态改动；`lightsAt` 全是只读聚合；`Light` 的守卫未动 |
| **D2**（`famineDeaths`） | **只改读数**：`VitalSettlementResult.famineDeaths`（→ Social `POPULATION_SETTLE`/`FAMINE_MORTALITY` 两条 INFO、App `FAMINE_MORTALITY_BRIDGED` INFO、`POPULATION_SETTLE_APP` DEBUG 的同名字段），以及 App 那条 INFO 的**发射条件**（`famineDeaths > 0` 由"几乎恒假"变为真 ⇒ 这些行会重新出现/变多——**日志行数会变，数值状态不变**） | `deaths`/`births`/`populationDeltas`/余数表/事件 `count` **一字未动**（只读 `deathCause` 本地变量与 `deaths` 累加之外的第三个局部量）；谁死谁活与 D2 无关（判死输入仍是 `effectiveDeathPpm`） |
| **D3**（工具描述/类注） | 无（纯文本） | 工具名/命令类型/权限（`ACTOR_NAMESPACE_WRITE`）/解析器/域层语义一字未动；**没有**为迁就文档去改解析器 |
| **D4**（`day` 字段） | **只加一个日志字段**（`HOUSEHOLD_VITAL_WRITEBACK` 多 `day=<世界日>`）；新增 fail-closed 守卫（`day<0` + 有生死 ⇒ 抛）在既有调用路径上**永不触发** | 三张表的校验/写口/`FlowRow` 形状未动；`NO_WORLD_DAY` 只走"搬人"路径且该路径不发射事件 |
| 文档同步（`FlowRow`/`EconomySettlement`/App 注释） | 无 | 注释与文本 |
| `HouseholdBook` 新增逐户守卫 | 无（越界才抛；结构上不可能越界：条数 ≤ 人数） | — |

**唯一"可能被读成数值变化"的一处**：D2 让 App 的 `FAMINE_MORTALITY_BRIDGED` INFO **重新开始发射**（此前 `famineDeaths=0` 且
`householdsWithRationGap>0` 时它本来就发；缺口 0 的那 43 天不发）。这是**日志面**变化，不是状态变化。

## W3f2-7 未完成 / 未验证（如实）

1. **一条测试都没跑、一个 world 都没跑**（本批明令只许 `spotless:apply` + `compile -am`）⇒
   D1 的"500 不再来自 null"、D2 的"ΣfamineDeaths = 377"、D4 的"日志里有 `day=`"**都是静态推演，不是实测**"。
   给验收批的候选判据（按判据写、别按代码反推）：
   - D1：高缺粮世界里 `GET /api/social/population?q=&r=` 在**有 FOOD 灯的格**返回 200 且 evidence 含
     `stressByAgeBracket={}` + `stressByAgeBracketUnavailable`；**负向**：同一响应里若同时满足死亡率判据，`crisis[]` 里必须**同时**能看到
     `kind=MORTALITY`（这条正是"遮蔽"的回归判据）。★ 断言"**500 的响应体不是 `{"error":"internal error"}`**"这类**负向**判据更有判别力。
   - D2：driving N 天后 `Σ famineDeaths`（把当日 INFO 逐条相加）== `populationEvents` 里 `cause=BASE_PLUS_FAMINE` 的**条数**；
     三种负向：缺口 0 的世界 ⇒ 恒 0；纯 BASE 死 ⇒ 0；把 `DEATH_CAUSE_BASE_PLUS_FAMINE` 或那行 `equals` 改坏 ⇒ 当场红（变异自证）。
     ★ 口径若按"人数"读，请先看 W3f2-2 末尾的**口径选择点**。
   - D4：`HOUSEHOLD_VITAL_WRITEBACK` 行里 `day=` 存在，且与同日 `POPULATION_SETTLE.day` / `FAMINE_MORTALITY_BRIDGED.day` **逐值相等**；
     负向：`day` 不得为 `-1`（哨兵不得出现在任何发射行里）。
2. **`test-compile` 未跑**（不在授权命令集内）⇒ 测试树能否编译**未实测**。静态核对：两处签名变化的旧形态调用点均已在上文点名且不存在
   （`ModeMigrationPolicyD024Test:581` 走 2 参、`EconomyLoggingTest:241` 走 1 参）；无测试引用 `stressByAgeBracket`/`cause=BASE`/
   `HOUSEHOLD_VITAL_WRITEBACK`/`famineDeaths`（全仓 `src/test` grep = 0）⇒ 预期不破，**仍须测试 Agent 实跑确认**。
3. **D3 的 handler 类注（`simos-actor`）未改** —— 越权（见 W3f2-3 末段），需控制方授权或另派。
4. **未验**：`Light` 的 evidence 在 GUI/JSON 序列化下 `{}` 的展示是否被前端误读成"压力为 0"（前端 `webui/*.js` 全仓
   `grep stressByAgeBracket` = 0 命中 ⇒ 当前无读者，但**读的人**要看到旁边的 `…Unavailable` 才不误读——这是文档面提醒）。
5. **未做**：D5–D8（验收报告 §3 的其余条目）不在本批任务书内，一字未碰。

# W3-fix3（famineDeaths 按人数 + actor 类注）

> 控制方两处裁定（本批任务书 §1/§2）：① D2 的 `famineDeaths` 计数口径**由"条数"改为"人数"**；
> ② 授权改 `simos-actor/.../AdjustAccountsHandler.java` 的**注释**（本批唯一越界授权，只改注释/描述，不改解析器、不改权限）。
> 纪律：不 `git commit`；不跑 `test`/`verify`/`package`/`clean`；工作树里 W3-fix2 的未提交改动**不回退、不重做**。

## W3f3-1 ① 口径改动点（`file:line` + 前后）

语义改动只有 **1 行**；同批把同口径的**文案**同步（措辞跟代码走，零行为）。

| # | 落点（改后行号） | 改前 | 改后 |
|---|---|---|---|
| 1 | `HouseholdBook.java:984-986` | `if (DEATH_CAUSE_BASE_PLUS_FAMINE.equals(deathCause)) { householdFamineDeaths = Math.addExact(householdFamineDeaths, 1L); }`（累加量 = 常量 `1L`，即"计 1 条"） | 同一个 `if`，累加量换成 **`deaths`**（该条 DEATH 事件携带的人数） |
| 2 | `HouseholdBook.java:978-983`（累加行正上方） | "饥荒那一份 = 本户本 tick 里 cause 含饥荒的死亡**事件条数**；条数 ≤ 人数（每条 deaths ≥ 1）" | "= 那些事件**携带的人数之和**（就是事件自己的 `deaths`）；按批次发时逐条累加；不变量由**子集**关系保证" |
| 3 | `HouseholdBook.java:950-953`（`deathCause` 上方） | "famineDeaths 直接数 cause 含饥荒的**条数**" | "只累加 cause 含饥荒的那些事件的**人数**" |
| 4 | `HouseholdBook.java:64-65`（常量注）/ `:784`（`famineDeathsTotal` 行内注）/ `:905-906`（逐户累加器注） | "条数" | "饿死人数" |
| 5 | `HouseholdBook.java:1051-1064`（**逐户守卫**） | 文案"饿死**条数**超过死亡人数" | 文案"饿死**人数**超过死亡人数"；**断言与抛出一字未动** |
| 6 | `VitalSettlementResult.java:39-45`（record javadoc） | "cause 含饥荒的**死亡事件条数**（条）；直接计 1 条" | "cause 含饥荒的**饿死人数**（人）；把那些事件的 `count` 逐条累加 —— 不是数事件条数" |
| 7 | `VitalSettlementResult.java:86-93`（**总量守卫**文案） | "饥荒致死事件条数 ≤ 死亡人数" | "饥荒致死人数 ≤ 死亡人数"；**断言一字未动** |
| 8 | `FlowRow.java:45-47`、`EconomySettlement.java:4740-4742`、`PopulationEconomyTimeParticipant.java:754-755` | 三处"（条数 ≤ 人数）" | 三处"（人数 ≤ 人数）" |

★ 为什么 #2–#8 也动：W3-fix2 把"条数"写进了 6 个文件的注释/拒因文案，口径一改这些句子就成了**假陈述**
（AGENTS §四"机制性描述一律回代码核"）。全部**只改字、不改任何 Java 语句**——本批唯一语句改动是 #1
（`git diff -U0` 过滤纯注释行后，这 6 个文件只剩 W3-fix2 早已落下的语句 + #1）。

**★ 与 W3f2-2 末段的关系**：那一节把"按条数"记为待裁定的**口径选择点**，并预告"若要人数，改动是一行
（`Math.addExact(householdFamineDeaths, 1L)` → `…, deaths)`）"。控制方 2026-10-11 裁定 = **按人数**，本批落地。
W3f2 的记录**不回头改**（§五.4 留痕），以本节为准。

## W3f3-2 不变量断言（两处，保留；fail-closed、不静默夹取）

| 层级 | 落点 | 断言 |
|---|---|---|
| 逐户 | `HouseholdBook.java:1055-1064` | `householdFamineDeaths > householdDeaths` ⇒ `vitalReject(day, "饿死人数超过死亡人数（逐户自洽被破坏）…")` |
| 总量 | `VitalSettlementResult.java:86-93` | `famineDeaths > deaths` ⇒ `IllegalArgumentException("…不得超过 deaths（饥荒致死人数 ≤ 死亡人数）…")` |

结构论证（人数口径下比"条数"更直接）：饥荒事件集 ⊆ 全部 DEATH 事件集，逐户两笔都是**同一批 `deaths` 的求和**
⇒ 子集和 ≤ 全集和；总量那两笔又各自是逐户同名量的求和 ⇒ 总量不变量同样由结构保证。越界即抛，**不夹取**。

## W3f3-3 逐值自检表（**静态推演自代码；本批未跑 test/world**）

读的是改后代码路径：`if (deaths > 0)`（`HouseholdBook.java:949`，守卫整条事件与计数）→ `deathCause`（`:954-955`）→ 累加（`:984-986`）。

| 情形 | 构造 | 事件流 | `famineDeaths`（改后=人数） | 旧"条数"口径 |
|---|---|---|---|---|
| **A. `deaths=1` 且 famine 参与** | faminePpm>0，单批死 1 人 | 1 条 DEATH，`count=1`，`cause=BASE_PLUS_FAMINE` | **1** | 1（同值） |
| **B. `deaths=0`** | 余数不足 1 人 | **0 条**（`if (deaths > 0)` 不进） | **0** | 0（同值） |
| **C. 纯 BASE** | faminePpm=0（缺口 0） | 1 条 DEATH，`cause=BASE` | **0** | 0（同值） |
| **D. 混合** | famine **2 条共 3 人** + BASE **1 条 1 人**（总 4 人） | 3 条 DEATH | **3** | 2（**不同值** ← 本裁定要修的就这一格） |
| D'. 混合（人数=条数） | famine 2 条各 1 人 + BASE 1 条 1 人 | 3 条 | **2** | 2（同值；sw180 的 377 属这一类） |

⇒ 可达世界（`share × effectivePpm < 2e6` ⇒ 每条事件 `deaths` 恒 1）两种读法同值；只在"一条事件含多人"时按人数才对。
**这是推导、不是实测**（本批明令不跑 test/world）。

## W3f3-4 ② `AdjustAccountsHandler` 类注（`file:line` + 改后原文）

**改后示例块（`AdjustAccountsHandler.java:33-38`，原文）**：

```java
 * <pre>{@code
 * {"entries":[
 *   {"household":"hh-1","goods":{"grain":-120,"cloth":5},"money":{"silver":-30}},
 *   {"household":"hh-2","goods":{"grain":120},"money":{"silver":30}}
 * ]}
 * }</pre>
```

**逐字段 ↔ 解析器对照（解析器 = 权威）**：

| 字段 | 解析器（`file:line`） | 改后类注写的 | 一致 |
|---|---|---|---|
| `entries` | 必填、非空数组（`ActorPayloads.java:241-244`） | "必填、非空数组：每项 = 一本家户账的净增量" | ✅ |
| 每项形状 | 必须是 `{household,goods?,money?}` 对象（`ActorPayloads.java:248-252`） | "每项是 `{household, goods?, money?}` 对象" | ✅ |
| `household` | 顶层**字符串**、`HouseholdId.parse`（`ActorPayloads.java:253-258` + `AccountPayloads.java:60-71`） | "必填、字符串、合法家户 id" | ✅ |
| `owner`/`q`/`r` | **不存在**（只认上面三个键） | "**没有** `owner{kind,id}`，也**没有** `q`/`r`" | ✅ |
| 重复 household | 同一份载荷里重复 ⇒ 拒（`ActorPayloads.java:259-261`） | "同一 `household` 只能出现一次" | ✅ |
| `goods`/`money` | 缺键/JSON null ⇒ 空表；至少一张非空（`:264-267`、`:283-293`） | "至少一个非空；缺键/null=空表；两个都空=这条没动作，拒" | ✅ |
| 键 / 值 | `CommodityId.parse`/`CurrencyId.parse`；整数；**0 拒**（`:288-320`） | "键走 `…parse`；值=有符号净增量(long)，0 不得出现" | ✅ |
| 账户键 | `new HouseholdAccountKey(entry.household())`（`AdjustAccountsHandler.java:148`）；键 = 家户、**无格** | "`household` 就是 `HouseholdAccountKey` 的全部内容；格从家户位置派生" | ✅ |

**改后 `:81-84`（原 `:77-79`）原文**：

> ★ **目标声明**（`CommandTargets`）：**家户账户无格 ⇒ `targetPaths` 恒为空列表**（本命令不动任何 hex 资源；账户键只有家户，
> 格由家户的位置派生），`targetResources` 走默认实现 ⇒ 目标清单同样为空（空 = 本命令没有可寻址目标）。
> `ActorPayloads#entryHexKeys`（`ResourcePaths#actor(int,int)` 那条逐格拼写）是 `actor.Seed` 载荷的路径，**本命令不经过它**
> ——本命令的载荷里没有 `q`/`r`。

依据：`targetPaths` 实际实现 `:103-107`（解析后 `return List.of()`，`:106`）；`entryHexKeys`（`ActorPayloads.java:134-145`）
读的是 `q`/`r`，只被 `ActorSeedHandler` 用（`:63`/`:139`）⇒ 与 `AdjustAccounts` 无关。`targetResources` 是接口默认实现
（`CommandTargets.java:79-88`：把 `targetPaths` 逐条包命名空间）⇒ 空进空出。

**未动**：解析器、`targetPaths` 实现、`CommandTargets`/`GmOnlyCommand`、权限围栏、任何 Java 语句
（本文件 diff **纯注释**：`git diff -U0 … | grep -v '^\s*\*'` 为空）。

## W3f3-5 命令与结果（真命令 + rc）

| 命令 | rc | 关键输出 |
|---|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **0** | 静默；事后 md5 比对：只重排了 `AdjustAccountsHandler.java` 与 `VitalSettlementResult.java`（google-java-format 折行），W3-fix2 的其余 8 个文件**一字未动** |
| `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`；16/16 模块 SUCCESS；各模块 `You have 0 Checkstyle violations.`；enforcer `BannedDependencies passed`（含 social/actor 的边界规则）；`SocialSimos` 编译 78 个源文件、`ActorSimos` 25 个 |

**未跑**（守任务书 §3）：`test` / `verify` / `package` / `clean`；未起服务、未跑 world
（`simos-app/target/*.jar` 未被动过 ⇒ 无覆盖运行中 jar 风险）。两轮命令均经 `tools/mvn-lock.sh` 串行锁、前台执行（一次只有一个 Maven）。

## W3f3-6 会改变数值行为的清单

**本批 Java 语句改动只有 1 处**（`HouseholdBook.java:985` 的累加量），且**在所有可达世界同值**——它只在"一条 DEATH 事件含多人"时与旧口径分叉：

| 改动 | 会变的数 | 不会变的东西（依据） |
|---|---|---|
| ① 累加量 `1L` → `deaths` | **只改读数**：`VitalSettlementResult.famineDeaths` 及其下游同名字段（Social `POPULATION_SETTLE`/`FAMINE_MORTALITY`、App `FAMINE_MORTALITY_BRIDGED`/`POPULATION_SETTLE_APP`），以及 App 那条 INFO 的发射条件 | `deaths`/`births`/`populationDeltas`/余数表/事件 `count`/`cause` **一字未动**——累加发生在事件已构造之后，只读局部量 `deaths`；"谁死"仍只由 `effectiveDeathPpm` 决定 |
| ② 注释/文案（6 个文件的注释 + 2 条异常文案） | 无 | 无 Java 语句改动 |

**唯一可能非空的数值差异**：若某条 DEATH 事件 `count ≥ 2` 且 famine 参与，读数从"1 条"变"该事件人数"（情形 D）。
可达世界实测 `count` 恒 1（W3f2-2 已核：sw180 的 428 死 = 428 条事件）⇒ 与 W3 验收的"死因分布 377 famine"**逐值不变**。

## W3f3-7 未完成 / 未验证（如实）

1. **一条测试没跑、一个 world 没跑**：W3f3-3 的逐值表与"377 不变"都是**静态推演**，不是实测。
2. **`test-compile` 未跑**（不在授权命令集）：本批改动里只有注释与 2 条异常文案，无签名/形状变化 ⇒ 预期测试树不受影响，**仍须测试 Agent 实跑确认**。
3. **给验收批的判据修正**：W3f2-7 第 1 条 D2 写的"Σ famineDeaths == `cause=BASE_PLUS_FAMINE` 的**条数**"**已被本裁定取代** ⇒
   应改为"== 那些事件的 `count` 之和（人数）"。★ 在 `count` 恒 1 的世界里两式同值 ⇒ **该判据没有判别力**，要区分两种口径须
   构造"一条 DEATH 事件含多人"的夹具（或直接断言 W3f3-3 的情形 D）。
4. **未做**：D5–D8 与 W3 验收报告其余条目一字未碰；`docs/**`（含报告里的"死因分布 377 famine"）按任务书禁改。
