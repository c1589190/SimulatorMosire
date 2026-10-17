package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRecipe;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;

/**
 * ★★ **R3a 日结算 + R4a 周期收获与制度分配**（聚合式经济重设计 §四 的日/周期步骤，v1 口径）——纯函数：拿 {@link EconomyData} 交**新**的
 * {@link EconomyData}，**不写状态、不碰核心**。
 *
 * <p>★★ **一次 {@code AdvanceTime} 按区间逐日跑**（2026-09-25 §十一：一次推进 N 天，内部逐日；见 {@link #settle}）：
 *
 * <ol>
 *   <li>**播种**：周期的第一天（{@code progressDays == 0}）先扣种子（{@link #sowIfCycleStart}）——**先于当天消费** （{@link
 *       #PLANTING_DRAWS_BEFORE_CONSUMPTION}，v2 spec §3.2）。种子粮与口粮是同一个商品，优先性来自**时点**。
 *   <li>**消费**：每行扣当天口粮 = app 在本日 step 之前注入的 {@link HouseholdEconomy#naturalNeeds()}（2026-10-09
 *       家户结构修复 Batch 3：逐户、逐商品、按 Social 成员展开，经济侧不再按 {@code population} 反推），消费开始时把粮需求 累加进 {@link
 *       HouseholdEconomy#cycleNaturalNeedMilli()}（一天一次，见 {@link #consumeOneHousehold}）。
 *   <li>**缺口**：库存不够 ⇒ 记入本行流水的 {@code unmetNeed}（毫粮、逐日累加；★ W3 起它同时是**报给 Social 的口粮缺口那一支**的当日分子 —— 见
 *       {@link EconomyHouseholdGap}，经济侧自己不据此判死）。 ★★★
 *       <b>W5.5（2026-10-11，S-12①）：这里原有"同格借粮"那一档</b>（借到的粮当日吃掉、并累加进同一条 {@link DebtContract}）——
 *       用户原话「把借粮这条路给取缔掉，这条路才是真的不知道哪里冒出来的」⇒ <b>整段退役</b>。 取代它的是<b>市场轮内的货币腿</b>（挂商品 → 挂钱 → 借钱 → 买货，见
 *       {@code MoneyMarketSettlement}）：缺现金的家户在 <b>买货之前</b>借到钱，买货时现金腿自然通过；借不到的那部分仍由「未付价款 ⇒
 *       对卖方的债务」兜底（S-12⑦）。 ⇒ 消费这一步只把缺口记成读数，<b>不再有任何借贷</b>。
 *   <li>**进度**：每个产业 {@code progressDays + 1}。
 *   <li>**劳动投入**：本产业当日实际劳动 = **该产业名下全部 {@code HouseholdLaborCommitment} 的 {@code laborMilli}
 *       之和**（R2 改口径；改前是"Σ(行 {@code laborMilli × participationPerMille / 1000})"，两者在创世逐值相同）—— 累加进
 *       {@link IndustryDefinitionView#cycleLaborMilli()}（供收获时算劳动瓶颈）。★
 *       于是"同一批人的劳动"**只有一处真相**：配额表；而"配额之和 ≤ 该批次的可用劳动"是状态的不变量（{@code EconomyData} 构造期判）。
 * </ol>
 *
 * <p>★★ **周期末追加**（{@code progressDays + 1 == cycleDays} 那一天，同一次日结算里）：
 *
 * <ol>
 *   <li>★★ **规模 = 最紧约束**（**R3/V7 起泛化**，spec §五）：把原来写死在 {@code harvest} 里的"三路全是亩"换成 {@link
 *       ProductionRecipe} 的四路归一（**每种 capacity 一路 + 劳动一路 + 每种投入一路**）：
 *       <pre>
 * scale = min( ⌊industry.capacity[k] ÷ capacityPerUnit[k]⌋  …每种生产资料一路（农业 = 土地、织机/作坊 = 件数；★ K3：产能住产业）
 *            , ⌊平均每日实际劳动 ÷ laborPerUnit⌋               …劳动一路
 *            , ⌊本周期实际扣到的投入_j ÷ inputPerUnit[j]⌋        …每种投入一路（种子 / 纤维 / 铁）)
 *       </pre>
 *       毛产 = {@code 规模 × outputPerUnit[j] × 1000 毫/单位}（**逐商品**；亩产 67 粮/亩 是 v2 spec §10.3 的标定值，而
 *       "每亩"从此是 {@code capacityPerUnit} 里的**数据**而不是隐式约定）。 **取小后向下取整** ⇒ 规模是整数。 ★ 某一路的"每单位需求"为
 *       {@code 0} ⇒ **不施加那一路约束**（不是"规模 0"）—— 旧档与未配投入的产业据此与 V2 逐值一致。 ★★ <b>Z2（2026-10-23）</b>： 这段三路
 *       min 是 {@link ProductionEfficiencyBook} §6.2 的 {@code scaleBase}；周期末公式还含 ① 平均修正、② 劳动链 +
 *       四余数结转、⑤ 修正乘算 + 余数，统一由该 Book 给出（见 {@code harvest} 的"已知边界"）；⑥ 的数量_j 默认取配方、GM 覆盖表命中则取覆盖值。
 *   <li>**生产消耗**：扣 {@code 饲料 + 折旧}（{@link #FEED_PER_MILLE} + {@link
 *       #DEPRECIATION_PER_MILLE}，**逐商品按同一千分比**）——**明文记入本期流水** （{@link FlowRow#consumed()}），不静默丢弃。★
 *       **留种不在这一项里**（v2 spec §3.4）：它在下一周期第 1 天以 {@code cycleInputPerUnit} 的形式现扣。
 *   <li>★★ **T4 起产出不再分配进阶层行**（裁定 R5 / 计划 R4）：**净产 → operator 的产出计提**（{@link
 *       ProductionLedger#outputAccruals()}），随后按该产业的 {@code relation} 逐条结算（{@link
 *       ProductionSettlement}）—— ★★ <b>H2 起每条实付 = 一条转移</b>（{@link ProductionLedger#transfers()}：
 *       {@code from=operator, to=受方}，受方恒为 actor —— 裁定 D1-A），而"净产"本身<b>不是转移</b>（产出是造出来的、没有对端）。 ★★
 *       <b>H1.3 起受方只有一条落点：actor</b>（{@code ToCohort} 的家户 actor = {@code
 *       HouseholdActors.of(cohort)}）⇒ 改前那两族（"actor 受方走产权条目 / cohort 受方落消费行"）与 {@code
 *       deliverCohortIntake} 的 {@code +unresolved} 兜底**一起删除**；家户那一笔**同时**计进会话工作副本与 {@code
 *       FlowRow.income}（见 {@link #harvest}）。★ <b>旧口径的 {@link AllocationRule.Split}
 *       本阶段起不由结算读取</b>（载荷/编码/往返全不动）：它已是**死数据**， 删它是另一件事（计划 Review Focus ①）。★★ <b>H0（2026-09-27，裁定
 *       K2/R-N1-A）起受方就是家户</b>：cohort 键（格 + 居住类型 + 阶层）与行键 <b>一一对应</b> ⇒ 改前那套"受方产业集 + 按人口分派"（E24 的
 *       {@code receiverIndustriesOf} / {@code
 *       classRowsOfCohort}）<b>整个删除</b>；"哪些行属于这个产业"改由**劳动配额表**推（{@link
 *       SettlementIndex#householdsOf}）。
 *   <li>**计息**（v2 spec §7.1 第三处 + §四 周期结算第 6 步；V6 落地）：全部债务按 {@code principal × ratePerMillePerCycle
 *       ÷ 1000} 计**一次**、**并入本金**（纯数学：不搬运粮、**不动任何库存**），同额记入**债务人**本行流水的 {@code interestDue} —— 见
 *       {@link #chargeInterest}。★ **偿还在计息之前**（关账日、所得到账后，D-030 §3.5）：逐条债按未偿共同价值升序、 逐 medium
 *       先全部货币（余额降序）再全部商品（数量降序），任意库存都可折还，估值 = 债务人价目表优先、否则该户市场区 默认价目表（见 {@link #repayDebts} 与 {@link
 *       DebtValuation}）；粮另扣一日口粮保留、全币种余额可用、冻结不越， 缺价 medium 具名跳过不静默付 0；不足部分顺延到下一周期。
 *   <li>★★ **报缺口**（W3 2026-10-11；**取代**原"饿死判据"）：在**全部救济通道之后**（市场 → 借粮 → 还债）逐日、逐户 算出口粮缺口那一支（当日
 *       {@code unmetNeed[grain]} ÷ 当日粮需求）与当日市场轮的"生产花费未满足量"（逐商品）， 写进会话的瞬态 outbox（{@link
 *       EconomySession#recordHouseholdGap}）交给 app ⇒ Social。★ <b>经济侧不判死、不缩劳动、 不动人口</b>（死亡率与劳动是
 *       Social 的唯一权威，AGENTS §〇 模块表；约束设计书 S-11 第②半）。**顺序**：在收获/分配 **之后**，同一次结算内完成。
 *   <li>★ 产出**进当天的 {@link ProductionLedger}**（毛产/损耗/投入/产权条目/货币待办）—— 由调用方落账；{@code progressDays}
 *       归零、周期劳动清零、{@link EconomyMeta#lastClosedCycle()} +1。
 * </ol>
 *
 * <p>★★ **本期流水按周期清零，清零点在"新周期第一天"**（§八.5）：
 *
 * <ul>
 *   <li>**关账日读得到整周期**：{@code progressDays + 1 == cycleDays} 那一支**自己**就产生本周期最大的一笔所得（收获的毛产分配） ⇒
 *       在那里清零等于把刚收获的那笔当场抹掉（关账日读到的 {@code income} 会变成 0）。
 *   <li>**次日从 0 起**：{@code progressDays == 0}（新周期第一天，含创世）时该行流水**整行从 0 重记** —— 语义 = spec 原文的"周期结算后
 *       **归档/清零**"（关账日归档、次日清零）。{@code unmetNeed}/{@code deaths} 与其它字段同口径（都是"本期"的量）。
 *   <li>**等价性不受影响**（§十一）：清零点由 {@code progressDays} 决定，而它是**天数的纯函数** ⇒ 一次推 N 天与 N 次单日清在同一处。
 * </ul>
 *
 * <p>★ **税（v1 明确不做）**：{@code government} 切片还不存在 ⇒ **不造假账**（{@code FlowRow.taxPaid} 恒 0；R7
 * 与政府切片一起做）。 市场定价、阶层流动、产业转换、矿业、日原料消耗同样不做（§四 的后续增量）。
 *
 * <p>★★ **量纲（§7 + §十）**：人口「人」；劳动「千分劳动」；土地「千分亩」；粮库存「**毫粮**」（1 粮 = 1000 毫粮， {@link
 * #MILLI_PER_GRAIN}）；利率/权重/投入率「千分」。**一切整数运算，禁 double**。
 *
 * <p>★★ **守恒（§6.1，账要平）—— H1 起是"**没有过渡项**"的那一式**（裁定 I6.1/I7.1；T4 的过渡形态已收口）。**逐商品、逐周期**：
 *
 * <pre>
 * 【H1 式｜覆盖家户账（会话副本） + 其它 actor 账户】
 *   ΔΣActorGoods_j + ΣFinalConsumption_j + ΣLoss_j == ΣOutput_j − ΣProductionInputs_j
 *
 *   ΣOutput_j            本期毛产（规模 × outputPerUnit_j × 1000；{@link ProductionLedger#gross()}）
 *   ΣProductionInputs_j  本期现扣投入（现扣步；★ H1 起**从家户账扣** ⇒ 它不再进任何行侧库存）
 *   ΣLoss_j              本期生产损耗 = 毛产 × (饲料 + 折旧)‰（{@link ProductionLedger#losses()}；★ 不在 consumed 里）
 *   ΣFinalConsumption_j  真正被吃掉的 = Σ FlowRow.consumed_j − Σ ProductionInputs_j
 *                        （★★ H3：**"同格取材转出"那一项已删** —— 那条通道整块删掉了（理由见 {@link
 *                          #drawCycleInputs} 的方法注释：它会跨主体抢料，实测把城里作坊的纤维取走进而导致
 *                          工具产量掉到 0）。⇒ 式子少一项，**读数与结算仍然同源**：`consumed` 里现在只有
 *                          日耗 + 现扣投入（投入在下一步减掉 ⇒ 它不算最终消费），`income` 里只有关系实付）
 *   ΔΣActorGoods_j       **全部 actor 侧账本的库存变化** = 家户账副本的 ΔΣ + 其它 actor（operator）的产权条目净额
 *                        （★ H1 起**没有** {@code ΔΣRowGoods} 这一项了：行里没有商品 —— 它还在 ⇒ 还有一本账没搬完）
 * </pre>
 *
 * <p>★★ <b>H2 的一句补充（式子一个字没改）</b>："其它 actor 的产权条目净额"现在 = <b>产出计提</b>（{@link
 * ProductionLedger#outputAccruals()}；{@code +净产 → operator}）<b>+ 转移腿</b>（{@link
 * ProductionLedger#transfers()}）。★ <b>转移在总量上恒相消</b>（一条转移的两端一正一负、且必落两本账）⇒ 它<b>不改变</b>上面那一式的任何一项；
 * 它换来的是<b>逐 actor 可对账</b>：每一笔库存换手都有凭据与制度原因（这就是"任何库存变动必有对应转移记录"那条新不变量）。
 *
 * <p>★★ **五项必须取自同一个窗口**（I4.2 的收口；这条不是废话）：前两项读的是<b>状态差</b>，中间三项读的是 <b>该窗口内各天 ledger
 * 的累加</b>与<b>同窗口的流水差</b>。任一项按别的窗口算，等式就<b>恒不成立</b>—— 上一版报告（{@code task-4-5-report.md} §2.2）正是这么栽的：它的
 * {@code ΔΣRowGoods} / {@code ΣOutput} / {@code ΣLoss} 取的是<b>收获那一天</b>，而手算的 {@code
 * ΣFinalConsumption} 是"27,500 日耗 + 375,000 整周期借来吃掉的" = 402,500 ⇒ 与右式差
 * <b>385,833</b>。<b>那一笔的收口</b>：同一个窗口（第 119 → 120 天）里真实的实吃是 <b>16,667</b>，而 {@code 402,500 − 16,667
 * = 385,833} —— <b>逐值等于那个差额</b>。差的是<b>窗口</b>，不是账： 375,000 那笔借粮是在第 31~40
 * 天吃掉的，它属于<b>整周期</b>那个窗口（见下面第二例），不属于收获日。
 *
 * <p>★★ **逐值算例**（5 格端到端夹具，(0,0) 平原 3,100 亩、粮；数字与算式都逐项可核）： ★
 * <b>时效标注（2026-09-27，H0/K3）</b>：下面的数字是**改前**（产能还散在各行 {@code meansOfProduction} 上、 收获规模按
 * Σ各行产能算）的实测。K3 把产能搬到 {@link IndustryDefinitionView#capacity()} 之后，**算式形状一字未改**，但 "各行想扣多少"改按人口占比折算
 * ⇒ <b>真档数值会变</b>（K3 已认这个代价，见 {@code HouseholdEconomy} 的注释；★ H3 起"各行想扣多少"这条口径 <b>整块删掉</b>（改前的
 * {@code rowSharesOf} 已不存在），改由 relation 指名供方）。 ★★ <b>时效标注（2026-09-27，H1）</b>：下面两式的**第一项已换名换物** ——
 * {@code ΔΣRowGoods} 不再存在，同一个位置上现在是 {@code ΔΣActorGoods}（家户账副本 + operator 条目）。⇒
 * **这些数不再当作判据，只当作"算式怎么读"的例子**。
 *
 * <pre>
 * 窗口 = 第 119 → 120 天（收获那一天，**改前口径**，留痕不改）：
 *   ΔΣRowGoods   = 71,095,930 − 183,333 = 70,912,597     （= 行侧入账 70,929,264 − 当日实吃 16,667）
 *   ΔΣActorGoods = 130,539,736 − 0      = 130,539,736    （= 净产 201,469,000 − 行侧入账 70,929,264）
 *   ΣFinalConsumption = 16,667   （consumed 16,667 − 投入 0 − 转出 0；= 富农 12,500 + 地主 4,167）
 *   ΣLoss = 6,231,000（= 毛产 207,700,000 × 30‰）· ΣOutput = 207,700,000 · ΣProductionInputs = 0
 *   左：70,912,597 + 130,539,736 + 16,667 + 6,231,000 = 207,700,000 = 右 ∎
 *
 * 窗口 = 创世 → 第 120 天（整周期，同一份夹具、同一格，**改前口径**）：
 *   ΔΣRowGoods   = 71,095,930 − 5,416,666 = 65,679,264   （★ 期初 5,416,666 = 四行 30/60/120/250 天储备）
 *   ΔΣActorGoods = 130,539,736 · ΣFinalConsumption = 5,250,000 · ΣLoss = 6,231,000 · ΣOutput = 207,700,000
 *   左：65,679,264 + 130,539,736 + 5,250,000 + 6,231,000 = 207,700,000 = 右 ∎
 *   ★ ΣFinalConsumption 的**独立旁证**（不经过上面任何一项）：期初 5,416,666 − 期末剩的 166,666 = 5,250,000
 *
 * 全系统（5 格 × 5 商品 × 上面两个窗口，共十次）：**差 = 0 十次**。抽三条看形状（整周期窗口，改前口径）：
 *   grain 233,555,158 + 457,741,986 + 15,079,166 + 21,846,690 = 728,223,000 = ΣOutput ∎
 *   cloth   3,692,783 +   1,632,517 +          0 +    164,700 =   5,490,000 = ΣOutput ∎
 *   iron      −60,000 +           0 +          0 +          0 =     −60,000 = 0 − ΣInputs 60,000 ∎
 * </pre>
 *
 * <p>★★ **代数（逐项当场可核；H1 起第一项换成 actor 侧）**：
 *
 * <pre>
 * 家户： ΔΣHousehold = 关系实付收进 − 投入 − 消费        （H3：投入从**relation 指名的供方**账上出 —— 见 drawCycleInputs）
 * actor： ΔΣOperator  = 毛产 − 损耗 − 关系实付付出        （+净产 → operator，再 −实付）
 * 相加：  ΔΣActorGoods = 毛产 − 损耗 − 投入 − 消费        （实付那一项两侧相消 ⇒ 它不影响总量）
 * ⇒ ΔΣActorGoods + 消费 + 损耗 = 毛产 − 投入 ∎
 * </pre>
 *
 * <p>★★ <b>spec §五 的那一式现在与本式**同形**</b>：它要求 {@code ΔΣRowGoods ≡ 0}（行里没有商品 —— H1 达成），
 * 于是本式就是它（只是把"家户账"明确算进 actor 侧）。★ 反过来：**这一项一旦回到式子里，就说明还有一本账没搬完。**
 *
 * <p>★★ <b>生产侧逐 actor（I4.1）</b>：{@code ΔActorGoods(a,j) = Output(a,j) − Input(a,j) −
 * TransfersOut(a,j) + TransfersIn(a,j)}。★ H1 起两条腿各有落点：
 *
 * <ul>
 *   <li><b>operator</b>（经营主体）：{@code Output = 净产}、{@code TransfersOut = 实付}、{@code Input ≡
 *       0}（投入不是它出的） ⇒ {@code Δ账本 = 净产 − 实付}，逐值等于该 actor 名下产权条目的代数和 —— 这就是 app 的 {@code
 *       OwnershipBooks} 落下去的那个数；
 *   <li><b>家户</b>：{@code TransfersIn = 关系实付}、{@code Input = 本期现扣投入}、另减**日耗**与**借出的粮** ⇒ {@code Δ副本
 *       = 实收 − 投入 − 日耗 − 借出}（借入那一笔当日即被吃掉，只进 {@code consumed}）。
 * </ul>
 *
 * <p>★★ **付款上限 = 本周期收到的产出（R6）** ⇒ {@code TransfersOut ≤ Output}，operator 的 Δ 结构性 ≥ 0。
 *
 * <p>★ <b>两种表都是逐商品的</b>（R3 起）：田里同时出粮与纤维 ⇒ "一条标量"表达不了"所得是什么"。★★ 而 {@link FlowRow#income()} 的**口径自 T4
 * 起改了**：它记的是**实物入账**（关系结算给本家户的量 + 同格取材的转入），<b>不再是</b>毛产分成 —— 读口因此不再撒谎。
 *
 * <p>★ **未激活**（{@code meta} 空）：原样返回（不做任何公式，§6.6）。
 */
public final class EconomySettlement {

  /** 1 商品单位 = 1000 最小计量单位（§7：库存按最小计量单位；{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;

  /**
   * 1 标准劳动（1000 千分劳动）能经营的亩数：§十 / 资料 §十 的「1 标准劳动支持 7 亩」。
   *
   * <p>★★ **R3（V7）起它只是"每亩需多少劳动"的推导源**（配方字段里放的是它的**倒数**）：{@link ProductionRecipe#laborPerUnit()}
   * 的口径是"每 1 单位规模需要多少劳动"，而 1000 ÷ 7 除不尽 ⇒ 由 {@code EconomySeeder.LABOR_MILLI_PER_MU} 取**向上取整
   * 143**（比旧口径略紧：每 7 亩要 1001 千分劳动而不是 1000）。 ★ 后果只落在"**劳动是瓶颈**"的格上：真档的分母是土地（可经营 51,646 亩 ≫ 3,100
   * 亩），故真档收获一分不动。
   */
  public static final long LAND_MU_PER_LABOR = 7L;

  /**
   * ★★ **每亩需要的劳动**（千分劳动）：{@code ⌈1000 ÷ }{@link #LAND_MU_PER_LABOR}{@code ⌉ = 143} —— 即上面那条口径的
   * **倒数**（{@code ProductionRecipe.laborPerUnit} 要的是"每 1 单位规模需要多少劳动"）。
   *
   * <p>★ 唯一拼写点在这里（{@code LAND_MU_PER_LABOR} 的派生量），{@code EconomySeeder} 只是引用它写进农业配方。 取**向上取整** ⇒
   * 比旧口径略紧（每 7 亩要 1001 千分劳动而不是 1000）。
   */
  public static final long LABOR_MILLI_PER_MU =
      (1000L + LAND_MU_PER_LABOR - 1L) / LAND_MU_PER_LABOR;

  /**
   * ★★ **收获时的饲料消耗**（千分数）：**0‰**。
   *
   * <p>★★ **为 0 是因为 v1 不做耕牛，不是漏掉了**（用户 2026-09-25：「耕牛系统觉得复杂现阶段就别做」；v2 spec §3.1 明写「故 {@code
   * AssetKind.CATTLE/SMALL_TOOL/LARGE_TOOL/WORKSHOP/MACHINE/SHIP} v1 保持声明但不启用」）：没有牲口就没有饲料口径，
   * 本常量先**存在但取值 0**，V7 参数目录落地后由参数表供给（届时耕牛一起做，这个数才有依据）。 有一条用例把它钉住（{@code
   * EconomySettlementTest.productionLossSplitsIntoFeedAndDepreciation}）。
   */
  public static final int FEED_PER_MILLE = ProductionOutputNetting.FEED_PER_MILLE;

  /**
   * ★★ **收获时的农具折旧**（千分数）：**30‰（3%）**。
   *
   * <p>★ 来源：v1 的 15%（旧常量 {@code PRODUCTION_CONSUMPTION_PER_MILLE}，原语义里含**种子**）在本版**拆开**
   * ——**留种**移出收获扣减、改在**播种日**以 {@code cycleInputPerUnit} 的 LAND 档现扣（v2 spec §3.4 的"最重要的口径修正"）， 剩下的
   * 3% 即农具折旧。用户 2026-09-25 裁定「现定」的定案数（见计划 2 的「修订与新增」）。
   */
  public static final int DEPRECIATION_PER_MILLE = ProductionOutputNetting.DEPRECIATION_PER_MILLE;

  /** 同格借粮的每周期利率（千分数）：20‰；字面量的唯一拼写点在 {@link DebtTerms}（旧公开常量为兼容别名）。 */
  public static final int BORROW_RATE_PER_MILLE_PER_CYCLE =
      DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE;

  /**
   * ★★ <b>E4c：欠租/欠薪资本化所用条款的来源名</b>——历史常量 {@code "E4c_LEGACY_DEFAULT"}。
   *
   * <p>★★ D-032 W3/R-11 起，条款按 {@code Arrear.Kind} 分派：{@code WAGE ⇒ WAGE_ARREARS +
   * wageArrearsDefault()}（0‰）；{@code RENT ⇒ RENT_ARREARS + legacyDefault()}。未分类类别 fail-closed
   * {@code debt-source-missing}，不静默并入默认条。该来源名只作为既有 {@code
   * ProductionLedger.DebtCapitalization.termsSource} 的审计标签保留。
   */
  public static final String CAPITALIZATION_TERMS_SOURCE = "E4c_LEGACY_DEFAULT";

  /**
   * ★★ <b>偿还比例（千分数；H5 ②，R3 续修改为"按可用粮"）</b>：<b>从本户可用粮里取出这么多来还本</b>。
   *
   * <pre>
   * 可用粮_h = max(0, 当前粮库存_h − 一日最低口粮_h)
   * 还款_h   = min( ⌊可用粮_h × 本常量 ÷ 1000⌋ , 该家户剩余债务本金 )
   * </pre>
   *
   * <p>★★ <b>为什么不再只按"本周期所得 × 20%"</b>：真档债务人是城镇粮缺口户 —— 它们的关系产出是布与货币，{@code income[grain]} 恒为 0 ⇒
   * 旧预算恒 0，{@code repaid} 永远接近 0。R3 续修改成读取<strong>关账日已经到帐的粮库存</strong>（所得、分配与收获都已在其内），
   * 先扣下一日最低口粮（不把"今天吃完明天"的口粮也抢去还债），其余按本常量比例先还本，再计息。
   *
   * <p>★★ <b>上限为什么还是"当期可用"</b>（H5 的判据原话）：还款是**转移**，不是"凭空多付" —— 余额扣成负会当场炸在唯一 applier 的守卫上（透支 =
   * 发行，而本批没有发行人）。⇒ 预算 ≤ 库存，且逐笔经唯一写口扣减，**结构性地**不可能扣成负。
   *
   * <p>★ <b>出厂值 1000‰ = 可用粮全部先还本</b>（"先还债、后开销"的债务纪律）；取 {@code 0} = 不偿还； 取 {@code 500} = 只还可用粮的一半。V7
   * 迁入参数表、GM 可调（与上面那条同处置）。
   */
  public static final int DEBT_REPAYMENT_SHARE_PER_MILLE = 1000;

  /*
   * ★★ W3（2026-10-11）：`FAMINE_MORTALITY_PER_MILLE`（饿死判据的致死率默认值，旧值 0‰）**已删**。
   *
   * <p><b>为什么删而不是留成 0</b>：死亡率是 <b>Social 的唯一权威</b>（AGENTS §〇 模块表；约束设计书 §2 的 S-11 第②半：
   * 「经济侧不自己扣劳动/人口」）。经济侧保留一个"致死率"旋钮 —— 哪怕默认 0 —— 就是留了一条绕过 Social 的判死通道，
   * 而这正是 W3 要关闭的。经济侧现在只做一件事：<b>报缺口</b>（{@link EconomyHouseholdGap} / {@link
   * EconomySession#drainPendingHouseholdGaps()}）。
   *
   * <p>★ 旧口径的历史留痕（用户 2026-09-25「可以先不做什么饿死人系统、青黄不接系统」⇒ 当时把致死率做成默认 0‰ 的包内入参）
   * 在 git 历史与本批账本里，不在这里复述；`settle(EconomyData,long,long,int)` 这个 4 参重载也随之一并删除。
   */

  /**
   * ★★ <b>P5：死亡按人口比例删债的具名原因</b>（唯一拼写点）：写入 {@link DebtContractBook#forgive} 的 {@code reason}，
   * 供审计/读口把这一族本金下降与偿还（还款）、GM 减免分开。
   *
   * <p>★ 口径与探针一致（探针只记 {@code debtDeleted} 发生额，不记原因）：数值行为 = 逐笔 {@code newPrincipal = ⌊principal ×
   * survivors ÷ population⌋}；本常量只命名"为什么少了"。★ 不改债的计量单位、 不搬粮/钱、不产生利息 —— 它只写合同本金与状态。
   */
  public static final String DEATH_DEBT_WRITE_OFF_REASON = "death:proportionalWriteOff";

  /**
   * ★★ <b>W8（2026-10-11）：户绝时消除"死者作为债权人"的应收的具名原因</b>（唯一拼写点）。
   *
   * <p>★ <b>它是控制方口径，可被用户推翻</b>（用户原话只说了"死了账一并消除"，没说方向）：本批按"户绝 ⇒ 该户作为 <b>债务人</b>的债 +
   * 作为<b>债权人</b>的应收<b>一并消除</b>"实现，理由是户绝后该户没有任何主体能主张/被主张这笔账， 留着只会让"死者是 1.04×10¹² silver
   * 的债权人、而活着的债务人还在为它复利"这种读数继续存在（调查报告 §2.5）。 ★ 与 {@link #DEATH_DEBT_WRITE_OFF_REASON}
   * 分开命名，便于读口/审计把两个方向分开统计，也便于用户日后只推翻一侧。
   */
  public static final String CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON =
      "death:creditorExtinctReceivableWriteOff";

  /**
   * ★★ **播种是否先于当日消费扣种**（v2 spec §3.2 的行为预设；用户 2026-09-25 定案：先用常量，默认 {@code true}）。
   *
   * <p>★ **V7 参数目录（spec §四）落地后，它迁入 {@code economy} 切片的参数表并成为 GM 可调**（spec §3.2：
   * "凡行为一律做成预设"，故它**不许**被写死成"代码选一个聪明的"）。届时本常量只作默认值。
   *
   * <p>★ 取 {@code false} = "吃饭优先、种子看运气"：那是 GM 的选择，不是代码该替他做的判断。 无论取真取假，**播种日这个步骤都在**（spec
   * §3.2：参数化不许改变流程形状），只是扣减次序不同。 那条"取假"的路**不是死代码**：它经包内可见的 {@code settleOneDayInto} 重载与 {@link
   * EconomyDayStepper} 的构造器注入（W2-fix6：原直测的 {@code settleOneDay} 短重载零读者已删； 原 javadoc 点名的 {@code
   * EconomySowingTest.drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown} 随 R3a 已不在测试树里 ⇒
   * 两种次序**当前没有逐值用例钉住**，具名缺口）。
   */
  public static final boolean PLANTING_DRAWS_BEFORE_CONSUMPTION = true;

  /** 粮食商品 id（§十"单位"行：粮 = 1 公斤；本轮只结算这一种商品）。唯一拼写点在 {@link EconomyVocabulary}。 */
  public static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /**
   * ★★ **布的商品 id**（R4 的 T2）：{@code cloth} —— 唯一拼写点同样在 {@link EconomyVocabulary}。
   *
   * <p>★★ **R4 起它是"真的被消费"的那种商品**（R3 只把它的需求写进了 {@code naturalNeeds}，形状有了但不消费、不进任何判据）： 每天的衣着需求（每人每
   * 365 天 1 匹）**从库存里扣**，缺口进 {@code unmetNeed} 的**布那一维** —— 与粮**同一套记账**、但**各自一条**（spec
   * §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）。
   */
  public static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);

  /**
   * ★★ <b>日结算阶段日志</b>（2026-10-04 起走 {@link EconomyLog} 的分类门面）：{@code settlement} 记阶段边界与聚合读数， {@code
   * trace} 记逐笔原始事件；级别约定与打开方式见 {@link EconomyLog}。
   *
   * <p>本常量保留为<b>逐笔 trace logger 名</b>（旧测试/读口的稳定别名）。
   */
  public static final String TRACE_LOGGER_NAME = EconomyLog.TRACE_LOGGER_NAME;

  /** 阶段/事件日志：settlement 分类（INFO 生命周期、DEBUG 池子/汇总）。 */
  private static final Logger TRACE = EconomyLog.settlement();

  /** 逐笔原始事件日志：trace 分类（每笔转移/成交槽/债务变动）。 */
  private static final Logger RAW = EconomyLog.trace();

  /** B4：外汇窗口装配（fx 分类 —— 与 {@code FxSettlement} 的 {@code FX_ROUND} 同一个 logger，便于一处看全外汇面）。 */
  private static final Logger FX = EconomyLog.fx();

  /** ★★ R1：政府市场授权的生命周期（market 分类 —— 与 {@code MarketSettlement} 的挂单/成交事件同一个 logger）。 */
  private static final Logger MANDATE = EconomyLog.market();

  /**
   * ★★ W5-fix6（D5）：<b>债务状态面</b>（debt 分类 —— 与 {@code DebtContractBook} / {@code
   * EconomyLiquidationSettlement} 同一个 logger）—— 自持合同的"不计息"具名 DEBUG 走它。
   */
  private static final Logger DEBT = EconomyLog.debt();

  private EconomySettlement() {}

  /** 追踪日志辅助：家户 → 商品 → 数量的两层表求和（只在 DEBUG 打开时调用）。 */
  private static long traceTotalGoods(Map<HouseholdId, Map<CommodityId, Long>> table) {
    long total = 0L;
    for (Map<CommodityId, Long> goods : table.values()) {
      for (long quantity : goods.values()) {
        total += quantity;
      }
    }
    return total;
  }

  /**
   * ★★ <b>A1（2026-10-10）：运输服务产出的 INFO + DEBUG（AGENTS §一.9"新增/修改经济阶段必须同时加日志"）</b>。
   *
   * <p>★★ <b>它记的是哪一件事</b>：{@code trade@hex}（跑商/承运产业）在**收获**这一步产出了商品 {@code haul}（运输服务） —— 即用户
   * 2026-10-10 那句「跑商不是生产方式吗？」的落点：跑商的产出从此走**标准生产管线**的同一段代码（{@code harvest} 的净产入账 + relation
   * 结算），不再是市场轮里的私有分支。
   *
   * <pre>
   * INFO  HAUL_SERVICE_PRODUCED        （毛产里有 haul 才刷）**发生了什么 + 具名计数**：几个产业产出、毛产/净产（毫）、
   *                                    产出计提条数与合计（毫）、有牌价的格数
   * DEBUG HAUL_SERVICE_PRODUCED_DETAIL **为什么**：逐产业给 haul 的每规模单位产出/劳动/投入与该格的**牌价有无** ——
   *                                    缺价 ⇒ 按既有 MarketTable.prices 口径"不交易" ⇒ 本批世界里它只入账、不成交（I-H3 缺省中性）
   * </pre>
   *
   * <p>★ <b>只读</b>：不写状态、不改任何公式、失败不影响结算（{@code EventLog} 的口径）。★ 调用点只在 {@code harvestWorks}
   * 非空时；且**毛产里没有 haul 就一条都不刷** ⇒ 无跑商/无该产出的世界日志面逐字不变。
   *
   * <p>★★ <b>A5（2026-10-10）：返回值 = 本日运输服务的净产</b>（毫服务）—— 当日对账 INFO（{@code HAUL_SERVICE_SETTLED}）的
   * "产出"一栏。没有产出 ⇒ 0（不是"没算"）。
   *
   * @param day 当日日号（两级日志都必带）
   * @param ledger 当日**收获**这一段的发生额（只读）
   * @param view ★★ B2：当日视图（当刻市场表 + 当刻产业表）—— 本方法是**逐日**读者（每天都可能刷）， 故不得读段首 {@code
   *     EconomyData}（那会把"今天的定价/今天的产出"读成段首那一份）
   * @return 本日运输服务的净产（毫服务；毛产 − 收获损耗，逐产业求和；没有产出 ⇒ 0）
   */
  private static long logHaulServiceOutput(long day, ProductionLedger ledger, EconomyDayView view) {
    long grossMilli = 0L;
    long netMilli = 0L;
    long industries = 0L;
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      Long produced = entry.getValue().get(EconomyCommodities.HAUL);
      if (produced == null || produced <= 0L) {
        continue;
      }
      industries++;
      long loss =
          ledger
              .losses()
              .getOrDefault(entry.getKey(), Map.of())
              .getOrDefault(EconomyCommodities.HAUL, 0L);
      grossMilli = Math.addExact(grossMilli, produced);
      netMilli = Math.addExact(netMilli, produced - loss);
    }
    if (industries <= 0L) {
      return 0L; // 本日没有任何产业产出运输服务 ⇒ 一条都不刷、净产读数 0
    }
    long accrualLegs = 0L;
    long accrualMilli = 0L;
    for (ProductionLedger.ActorEntry accrual : ledger.outputAccruals()) {
      if (EconomyCommodities.HAUL.equals(accrual.commodity())) {
        accrualLegs++;
        accrualMilli = Math.addExact(accrualMilli, accrual.delta());
      }
    }
    long pricedMarkets = 0L;
    for (MarketTable market : view.marketTables().values()) {
      if (market.hasPrice(EconomyCommodities.HAUL)) {
        pricedMarkets++;
      }
    }
    // ── INFO：发生了什么 + 具名计数 ──────────────────────────────────────────────────────────
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "HAUL_SERVICE_PRODUCED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "industries",
                industries,
                "grossMilli",
                grossMilli,
                "netMilli",
                netMilli,
                "accrualLegs",
                accrualLegs,
                "accrualMilli",
                accrualMilli,
                "pricedMarkets",
                pricedMarkets));
    // ── DEBUG：为什么（逐产业的配方读数 + 牌价有无 ⇒ 交易/不交易）────────────────────────────
    if (!TRACE.isDebugEnabled()) {
      return netMilli;
    }
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      Long produced = entry.getValue().get(EconomyCommodities.HAUL);
      if (produced == null || produced <= 0L) {
        continue;
      }
      IndustryDefinitionView industry = view.industries().get(entry.getKey());
      long haulPerScaleUnit =
          industry == null
              ? 0L
              : industry.recipe().outputPerUnit().getOrDefault(EconomyCommodities.HAUL, 0L);
      long impliedScale =
          haulPerScaleUnit <= 0L
              ? 0L
              : produced / Math.multiplyExact(haulPerScaleUnit, MILLI_PER_GRAIN);
      HexCoord hex = IndustryHexKeys.hexKeyOf(entry.getKey()).map(HexCoord::parse).orElse(null);
      // ★★ W1e（2026-10-11）：`hex` **可能为 null**（产业 id 不含 `@`、或 `@` 之后为空 ⇒ hexKeyOf 返回空），
      //   必须在**任何解引用之前**判掉，且**只判这一次**：null ⇒ 不查牌价表（priced=false，不猜牌价）、
      //   hex 栏记空串、reason 具名 —— 改动前 `:595` 把 null 直接交给 MarketTables.atOrNull，
      //   后者经 marketIdAt 的 `Objects.requireNonNull(hex, "hex")` 会当场 NPE（SpotBugs 报的
      //   RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE 就是这个真隐患，不是误报）。
      boolean hexResolved = hex != null;
      MarketTable market = hexResolved ? MarketTables.atOrNull(view, hex) : null;
      boolean priced = market != null && market.hasPrice(EconomyCommodities.HAUL);
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HAUL_SERVICE_PRODUCED_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "industry",
                  entry.getKey().value(),
                  "hex",
                  hexResolved ? hex.q() + "," + hex.r() : "",
                  "grossMilli",
                  produced,
                  "lossMilli",
                  ledger
                      .losses()
                      .getOrDefault(entry.getKey(), Map.of())
                      .getOrDefault(EconomyCommodities.HAUL, 0L),
                  "haulPerScaleUnit",
                  haulPerScaleUnit,
                  "impliedScale",
                  impliedScale,
                  "laborPerUnit",
                  industry == null ? 0L : industry.recipe().laborPerUnit(),
                  "inputPerUnit",
                  industry == null ? Map.of() : industry.recipe().inputPerUnit(),
                  "marketPriced",
                  priced,
                  "reason",
                  hexResolved
                      ? (priced ? "priced" : "no-haul-price-no-trade")
                      : "industry-hex-unparsable"));
    }
    return netMilli;
  }

  /**
   * ★★★ <b>A5 + A7（2026-10-10）：周期边界作废"上一周期没卖出去的运输服务"</b>（用户裁定「运力作为特殊商品不可储存转卖」； 约束设计书 §3.1 v1.2 /
   * I-H3 v1.2 / v1.5 的 A7 收口）。
   *
   * <pre>
   * 触发   = 产出运输服务的 unit 关账那天（{@code progressed >= cycleDays}），在**收获入账之前**
   * 扫谁   = **任何持有 haul 的家户**（A7 扩域；= 会话里全部已登记家户的 haul 键，不再限于关账 unit 的主体家户）
   * 扫多少 = 每户 {@code max(0, haul 现货 − haul 冻结)}（被冻结的那一份是别处的明确占用，不动它）
   * 落点   = {@link EconomySettlement#consumeForLoss}（非换手损耗唯一写口）+ {@link HaulService#SERVICE_EXPIRED_ACCOUNT}
   * </pre>
   *
   * <p>★★ <b>A7 为什么要扩到"任何持 haul 的家户"（A6 真实 world 实测的逃逸口）</b>：A5 的作用域是"关账 unit 的账户主体家户"，
   * 而服务货会**离开**产它的家户 —— B 世界 day 240 的 5,249 毫陈货经 {@code loan_repayment}（实物还债）转移到已退出商家名册的持货户， 之后跨
   * day 360 / day 480 两次边界都没被作废（A6 账本 §1.2）：效果上卖不掉（该户 480 天 0 条池行），字面上没销毁。 ★ 一旦该户被选回跑商（本仓确有 {@code
   * pureMerchant} 状态机），陈货**立刻**变成可卖运力 ⇒ 按"任何持货家户"收口。
   *
   * <p>★★ <b>为什么必须在"收获之前"</b>：关账日的收获会把**本周周期的净产**记进同一批账户。若作废排在收获之后， 刚产出的服务会被当成陈货一起作废（新货当陈货）。排在收获之前
   * ⇒ 作废的**恰好**是上一周期留下、没卖出去的那一份； 而"谁有服务货可卖"（{@link MerchantCapacityPool#of} 的服务口径：{@code max(0, 现货
   * − 冻结)}）因此**只可能**看到 本周期的产出 —— 这就是"不得跨周期留存、不得再卖"的结构性落点，不需要给池加任何周期过滤（也就没有第二处判据）。 ★
   * 全局清扫落在"收获之前"仍然成立：本阶段的**唯一**产出写入口是 {@code harvestPartitioned}（在调用点之后）， 而它尚未开始 ⇒ 此刻账上的 haul
   * **一律**是"本周周期收获之前就存在"的存货 ⇒ 清扫不会误伤当期新产（详见实现账本 §3）。
   *
   * <p>★★ <b>为什么不是静默销毁（I-H2）</b>：走的是与货损 / 服务成交消耗**同一个**写口 —— 账户减与 {@code
   * ledger.addLoss(SERVICE_EXPIRED_ACCOUNT, haul, amount)} 在同一句话里做完 ⇒ Σ家户余额 + Σ损耗账守恒。
   * 具名账户与"卖出去时消耗"的那一项（{@code market-haul-service}）分开 ⇒ "卖了多少 / 烂了多少"在账上分得开。
   *
   * <p>★ <b>为什么"只影响 haul"</b>：读写的商品键恒为 {@link HaulService#HAUL_COMMODITY}；清扫只对"该户有 haul 键"的户写
   * （别的商品一个字节不动）；且**没有**任何跨商品分支。无跑商 unit 的世界 ⇒ {@code triggeringUnits} 为空 ⇒
   * 本方法连一次表查询都不做（缺省中性，I-H3）。
   *
   * <p>★ <b>只作用于"没卖出去"的那一份</b>：{@code available = max(0, stock − frozen)}，冻结量不动（它是别处已明确的占用；
   * 服务不进订单簿，故现实中它为 0）；可用量为 0 的户连损耗条目都不落（不制造 0 额账）。
   *
   * <p>★★ <b>确定性（I7 / I-H4）</b>：清扫顺序 = 家户 id 规范串升序（与 {@code MerchantCapacityPool} 的逐户顺序同一把尺），
   * 是**内容的纯函数**；不依赖哈希序，也不用 {@code Map.copyOf}/{@code Set.copyOf}。
   *
   * @param day 当日日号
   * @param triggeringUnits 本日关账且产出运输服务的 unit（**只当触发开关与日志归因用**；A7 起不再是清扫范围）
   * @param accounts 会话账户（活表；本方法在收获阶段取快照**之前**写它）
   * @param ledger 当日发生额累加器（损耗账的唯一写入点）
   * @return 本日作废的服务总量（毫服务；没有作废 ⇒ 0）—— 当日对账 INFO 的"作废"一栏
   */
  private static long expireUnservedHaulService(
      long day,
      List<ProductionProcess> triggeringUnits,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger) {
    if (triggeringUnits.isEmpty()) {
      return 0L; // 本日没有跑商 unit 关账 ⇒ 一行不跑、一账不动（缺省中性）
    }
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = accounts.householdGoods();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = accounts.householdFrozenGoods();
    // ★★ A7：清扫范围 = 会话里**全部**已登记家户里"有 haul 存货"的那些（键的存在即"持有"——
    //   余额归零即去键，见 setStock 的口径）⇒ 不再做任何主体解析，持货不再需要与"谁产的"挂钩。
    List<HouseholdId> holders = new ArrayList<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : householdGoods.entrySet()) {
      if (entry.getValue().containsKey(HaulService.HAUL_COMMODITY)) {
        holders.add(entry.getKey());
      }
    }
    if (holders.isEmpty()) {
      return 0L; // 全世界没有一户持 haul ⇒ 不排序、不落账、不改一个字节（"没有 haul 持有者 ⇒ 中性"）
    }
    holders.sort(Comparator.comparing(HouseholdId::value)); // ★ I7：顺序是内容的纯函数（不许依赖哈希/插入序）
    long total = 0L;
    long householdCount = 0L;
    for (HouseholdId household : holders) {
      long stock = stockOf(householdGoods, household, HaulService.HAUL_COMMODITY);
      long frozen = frozenGoodsOf(householdFrozenGoods, household, HaulService.HAUL_COMMODITY);
      long available = Math.max(0L, stock - frozen);
      if (available <= 0L) {
        continue; // 这个户没有"没卖出去的"服务货（或全部被冻结）⇒ 不落 0 额账、不改一个字节
      }
      EconomySettlement.LossConsumption consumed =
          consumeForLoss(
              householdGoods,
              householdFrozenGoods,
              ledger,
              HaulService.SERVICE_EXPIRED_ACCOUNT,
              household,
              HaulService.HAUL_COMMODITY,
              available);
      if (consumed.consumedMilli() <= 0L) {
        // ★ fail-closed 的写口在"可用量 < 请求量"时一点也不扣；这里请求量**就是**可用量 ⇒ 结构上取不到 0。
        //   真取到 0 说明账在两行之间被改了（契约故障）⇒ 具名 ERROR，绝不静默少作废。
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_EXPIRY_FAULT",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "requestedMilli",
                    available,
                    "stockMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "scope",
                    "all-haul-holders",
                    "reason",
                    "haul-service-expiry-blocked-by-live-table-change"));
        continue;
      }
      total = Math.addExact(total, consumed.consumedMilli());
      householdCount++;
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "HAUL_SERVICE_EXPIRED_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "expiredMilli",
                    consumed.consumedMilli(),
                    "stockBeforeMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "lossAccount",
                    HaulService.SERVICE_EXPIRED_ACCOUNT.value(),
                    "scope",
                    "all-haul-holders",
                    "reason",
                    "unsold-haul-service-cannot-be-stored-carried-over"));
      }
    }
    if (total <= 0L) {
      return 0L; // 有持货户但都没有"没卖出去的"那一份 ⇒ 不刷 INFO（"没发生"不进日志；对账行仍会报 0）
    }
    // ── INFO（§一.9：新状态写口至少一条"发生了什么 + 具名计数"）────────────────────────────
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "HAUL_SERVICE_EXPIRED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "households",
                householdCount,
                "triggerUnits",
                triggeringUnits.size(),
                "holders",
                holders.size(),
                "scope",
                "all-haul-holders",
                "expiredMilli",
                total,
                "lossAccount",
                HaulService.SERVICE_EXPIRED_ACCOUNT.value(),
                "reason",
                "unsold-haul-service-cannot-be-stored-carried-over"));
    return total;
  }

  /**
   * ★★ <b>A2：本轮"运输服务成市"的格集</b>（= 本格市场给 {@code haul} 定过价）—— 逐轮现算，不落状态、不进任何组件。
   *
   * <p>★ <b>它是本批缺省中性的唯一开关</b>：本集为空 ⇒ 运力池按既有"劳动 + 工具"算式、运费走既有 {@code CARRIER_FEE} 腿 ⇒
   * <b>逐值等于改前</b>（I-H3 的第一条腿"无牌价"）。★ 成市的格则改走服务商品口径（运力 = 商家户手上的服务货， 运费 = 服务成交），见 {@link
   * MerchantCapacityPool#of}（第 7 参）与 {@code MarketSettlement} 的服务分支。
   *
   * <p>★ 判据的唯一拼写点是 {@link HaulService#pricedAt}（本方法只负责"遍历哪些市场"）。
   */
  private static Set<HexCoord> haulServiceHexes(EconomyDayView view) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    // ★ W1：遍历的格 = "有市场的格"（持久形态 = 区成员格；派生形态 = 价表成员格），逐格取本格所属市场的价表。
    for (HexCoord hex : MarketTables.allMarketHexes(view.marketTables(), view.marketZones())) {
      if (HaulService.pricedAt(MarketTables.atOrNull(view, hex))) {
        hexes.add(hex);
      }
    }
    return Collections.unmodifiableSet(hexes);
  }

  /**
   * ★★ <b>A5：逐户服务收入 → 逐币总额</b>（当日对账 INFO 的 {@code paidByCurrency} 一栏）—— 只读求和，不写任何状态。
   *
   * <p>★ <b>为什么在这里求和而不是再记一份</b>：金额的唯一事实是逐笔钱腿（{@code carrierPool.recordFee} 按户归集）； 逐币总额是它的纯派生量。★ 保序
   * = {@code LinkedHashMap} 的首次出现序，而 {@code revenueByHousehold()} 已按 （家户 id → 币种规范串）冻结 ⇒
   * 键序是内容的纯函数（I7；禁 {@code Map.copyOf}）。
   */
  private static Map<CurrencyId, Long> haulServicePaidByCurrency(
      MarketSettlement.HaulServiceRound round) {
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (Map<CurrencyId, Long> legs : round.revenueByHousehold().values()) {
      for (Map.Entry<CurrencyId, Long> leg : legs.entrySet()) {
        totals.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /** 追踪日志辅助：产业 → 商品 → 数量的两层表求和（只在 DEBUG 打开时调用）。 */
  private static long traceTotalByIndustry(Map<IndustryId, Map<CommodityId, Long>> table) {
    long total = 0L;
    for (Map<CommodityId, Long> goods : table.values()) {
      for (long quantity : goods.values()) {
        total += quantity;
      }
    }
    return total;
  }

  /** 追踪日志辅助：家户 → 标量求和（只在 DEBUG 打开时调用）。 */
  private static long traceTotalLongs(Map<HouseholdId, Long> table) {
    long total = 0L;
    for (long value : table.values()) {
      total += value;
    }
    return total;
  }

  /**
   * 追踪日志辅助：家户 → 币种 → 余额的两层表**按币分列**求和（只在 DEBUG/TRACE 打开时调用）。
   *
   * <p>★★ <b>P-T4：不再跨币相加</b>（改前是把所有币种 1:1 加成一个 {@code long}）。键按币种 id 升序 = 内容的纯函数 （不用 map
   * 迭代序）。零额条目不发（"没有这种钱"与"这种钱动了 0"分得开）。
   */
  private static Map<String, Long> traceMoneyByCurrency(
      Map<HouseholdId, Map<CurrencyId, Long>> table) {
    Map<String, Long> totals = new TreeMap<>();
    for (Map<CurrencyId, Long> money : table.values()) {
      for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
        if (entry.getValue() != 0L) {
          totals.merge(entry.getKey().value(), entry.getValue(), Long::sum);
        }
      }
    }
    return totals;
  }

  /**
   * 追踪日志辅助：这两层表里有没有**正**额（= 改前"全部币 1:1 标量和 &gt; 0"那个门槛的等价形态）。 ★ 等价性：还款腿只累加正额 ⇒ "有一笔正的" 与 "标量和 &gt;
   * 0" 同真同假。
   */
  private static boolean anyPositiveMoney(Map<HouseholdId, Map<CurrencyId, Long>> table) {
    for (Map<CurrencyId, Long> money : table.values()) {
      for (long quantity : money.values()) {
        if (quantity > 0L) {
          return true;
        }
      }
    }
    return false;
  }

  // ★★★ D-032 B3（§13.13.7 第 8 条 / §13.3 第 2 条）：静态单模块入口 `settle(EconomyData, from, to)` 已**删除**。
  //   它本来就 fail-closed（没有家户账 / 没有产权落账口，见原 javadoc），main 里 0 个调用点，且它构造的是
  //   **runtime = null** 的会话（"能编译、实际不能履约"的入口）⇒ 按裁定删除，不保留兼容位。正确入口 =
  //   `EconomyDayStepper`（家户账是会话状态 + 显式 runtime）或 app 协调器。

  /**
   * ★★ <b>结算一天（S1 完整入口，单线程退化路径）</b>：等价于传 {@link EconomyParallelism#singleThreaded()} 的 R2 重载 ——
   * 保留给旧调用方/测试，分区与提交序与并行路径共用同一套（见下一个重载）。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        EconomyParallelism.singleThreaded(),
        composition);
  }

  /**
   * ★★ <b>结算一天（S1 完整入口 + R2 并行度）</b>：账户工作副本统一为 {@link AccountSession}（键 = {@code (ActorRef,
   * HexCoord)}，支持家户 / 经营者 / GOV / UNIT 任意 actor）；本方法只从它取八个活视图（同一个 AccountSession 的活视图，不是八份独立副本）。
   *
   * <p>★★ <b>R2 的并行结构</b>：日序里的"可按 hex 并行"阶段（到货、周期投入、收获/分账、消费、同格借粮）统一走 {@link
   * SettlementExecutor#execute} + {@link AccountIntentBuffer}：worker 只读 {@link AccountSnapshot}、
   * 只写线程本地意向与本地状态副本，协调器按 {@link CommitOrder} 稳定提交（唯一写口）。{@code workerCount == 1} 走同一份 {@link
   * PartitionPlan}（固定 32 个结构分区），只是在调用线程按分区顺序跑。
   *
   * <p>★ <b>仍为协调器单线程的阶段</b>（各自的原因写在对应调用点）：劳动再分配（写配额表、无账户冲突但收益小）、 跨区市场、偿还/计息、人口回写 ——
   * 它们要么跨分区（债权人/买方可能在别的区），要么要按全天的全局序铸造转移号。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition) {
    // ★ P10.2：旧调用方（单模块/测试/批量 settle）没有周期累加器 ⇒ 不接迁移钩子；旧路径逐值不变。
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        null,
        composition,
        Set.of());
  }

  /**
   * ★★ <b>P10.2：带周期利润累加器的入口</b>（{@code profitCycle == null} 时与上一个重载逐值相同）—— 关账日在本方法末尾按 ⑦真实利润汇总 →
   * ⑧迁移计划 → ⑨迁移执行 接线；保证在<b>下一周期投入开扣之前</b>完成（下一次 {@code step()} 才开扣）。
   *
   * <p>★★ <b>Z7b / R2：{@code marketExcludedHouseholds} 是组合根传入的<b>单位户集合</b></b>（单位户 economy 编译期看不见，
   * 由 app 从 {@code Unit.households()} 算好）。★★ <b>控制方裁定 R2（2026-10-11）起它不再表示"退出商品市场"</b>： 那些家户按{@link
   * MarketSettlement.MarketRound#householdMarketRole}分成"只买不卖"（军户/官吏户）与"只按授权下单" （政府国库户，权威 = {@code
   * governments}）；本方法<b>原样照收</b>，不做任何再分类。 它只影响市场订单/参与者生成，不改账户、冻结、税/预算/转移语义。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds) {
    // ★ R2 兼容：没有口岸面的旧入口 ⇒ PortEnforcementInput.none()（逐值退回改前行为）。
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds,
        PortEnforcementInput.none());
  }

  /**
   * ★★ <b>R2：带口岸实际管制力的日结算入口</b>（唯一生产者 = {@code EconomyDayStepper.step}，其值由 app 组合根注入）。
   *
   * <p>★ {@code portEnforcement} 只影响<b>市场轮</b>（{@code CurrencyValuation} 的家户外币估值减项），不改账户/冻结/税/预算语义；
   * 缺省 {@link PortEnforcementInput#none()} ⇒ 逐值退回改前行为（I-P8）。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds,
        portEnforcement,
        PortTaxInput.none());
  }

  /**
   * ★★ <b>P-T1b：带三层税税率的日结算入口</b>（唯一生产者 = {@code EconomyDayStepper.step}，其值由 app 组合根按口岸政策折算后注入）。
   *
   * <p>★ {@code portTax} 只影响<b>市场轮的成交结算</b>（三层税真收款：出口税 / 进口税 / 区内市场税 ⇒ 买方多付、国库到账）； 缺省 {@link
   * PortTaxInput#none()} ⇒ 三层税一分不收 ⇒ 逐值退回改前行为（I-C2）。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds,
        portEnforcement,
        portTax,
        ProcurementPriorityInput.none());
  }

  /**
   * ★★ <b>P-T1d：带政府采购优先级的日结算入口</b>（唯一生产者 = {@code EconomyDayStepper.step}，其值由 app 组合根从 gov
   * 政策与本政府编制劳动力折算后注入）。
   *
   * <p>★ {@code procurementPriority} 只影响<b>市场轮的撮合顺序</b>（要求管控市场的政府，其国库户挂单在行政力池余量内强制置顶；
   * 只改顺序、不改价格与量规则，见 {@code ProcurementPriorityOrder}）；缺省 {@link ProcurementPriorityInput#none()} ⇒
   * 无置顶、无消耗 ⇒ 逐值退回改前行为（I-C2）。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax,
      ProcurementPriorityInput procurementPriority) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds,
        portEnforcement,
        portTax,
        procurementPriority,
        SurvivalSocialFacts.unavailable());
  }

  /**
   * ★★★ <b>D-032 W4：带 Social 下一 tick 生存投影的日结算入口</b>（唯一真实生产调用点 = {@code EconomyDayStepper.step}）。
   *
   * <p>{@code survivalSocialFacts} 只承载 Social 已算好的下一 tick 需求/劳动预算；匹配层的 {@code SurvivalOutlook} 由
   * economy 用 W2b 的纯 evaluator 组装，不在这里重写任何生存/匹配公式。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax,
      ProcurementPriorityInput procurementPriority,
      SurvivalSocialFacts survivalSocialFacts) {
    Objects.requireNonNull(
        survivalSocialFacts,
        "survivalSocialFacts（没有 Social 投影请显式传 SurvivalSocialFacts.unavailable()）");
    Objects.requireNonNull(
        portEnforcement, "portEnforcement（没有口岸面给 PortEnforcementInput.none()，不得为 null）");
    Objects.requireNonNull(portTax, "portTax（没有税给 PortTaxInput.none()，不得为 null）");
    Objects.requireNonNull(
        procurementPriority,
        "procurementPriority（没有政府管控给 ProcurementPriorityInput.none()，不得为 null）");
    Objects.requireNonNull(session, "session（S1：revision 级会话持有可变工作表）");
    Objects.requireNonNull(accounts, "accounts（S1：账户会话是会话状态，必须由调用方载入）");
    Objects.requireNonNull(topology, "topology（M2.3：区域拓扑是只读输入；单格世界用 MarketTopology.singleHex）");
    Objects.requireNonNull(parallelism, "parallelism（R2：并行度配置）");
    Objects.requireNonNull(marketExcludedHouseholds, "marketExcludedHouseholds（无排除给空集，不得为 null）");
    EconomyData base = session.base();
    // ── ★★ B2（2026-10-10）**当日视图**：逐日循环里"当刻状态"的唯一读口 ───────────────────────────────
    //   形态 = 工作表的只读门面（WorkingDayView）：构造只持一个引用（引用级、与状态规模无关），
    //   每次读取现场解析"工作副本优先、未物化则复用 base 的不可变表" ⇒ **零拷贝/零序列化/零落盘/零 Revision**。
    //   ★★ 铁律（B2 §3.2）：本方法内所有**逐日**读者（迁移/择业/清算/前瞻/运力/市场装配/日志）一律读 dayView；
    //     段首 `base` 只允许**段级**语义（审计/差分/回放起点），每一处仍读它的地方都在注释里写明理由
    //     （完整清单见 .superpowers/sdd/2026-10-10-b2-per-day-view/impl-ledger.md）。
    //   ★ 为什么必须在**每一天开始时**派生：一次 advance 的 N 天共用同一个会话工作表（整段一条 revision），
    //     而 base 是段首快照 —— 段内第 2 天起再读 base 就是读"上一天以前的旧世界"（这正是路径效应的根因）。
    //   ★ 为什么不是"构造时把表拍进字段"：工作副本是惰性物化的，拍快照会在它物化的那一刻变成过期引用。
    EconomyDayView dayView = new WorkingDayView(session.sheet());
    // ── ★★★ D-032 D3 §7（W3）：MONEY_WAGE 的 B2 整 tick 拒绝已由真实工资结算取代 ─────────────
    //   真实结算在 `driveProductionDay`/关账之后、市场轮/还债之前执行（见下面的 WAGE_SETTLEMENT 段）。
    // ★★★ R2（2026-10-11，控制方裁定）：**组合根注入的 Z7b 集合原样照收** —— 分类只发生在**一处**：
    //   {@code MarketRound.householdMarketRole}（国库户 ⇒ 只按授权下单；其余单位户 ⇒ 只买不卖；其余 ⇒ 普通家户）。
    //   ★ 改前这里有第二处分类：`unitExclusionsMinusGovernmentTreasuries`（把"是政府国库户"的单位户从集合里摘出来）。
    //     它随 R2 删除 —— 角色的权威是 {@code Government.treasury()}（经 {@code govMandates} 注入），在角色判据里
    //     **优先于**单位户集合判定（更严的一条赢，fail-closed）⇒ 摘不摘都得到同一个角色；留着就是"谁被排除"的第二个拼法。
    //   ★ 语义（R2 冻结）：军户/官吏户按普通家户参与**买盘**（口粮通道），**卖侧维持 Z7b 原口径**（公家/单位实物不得被
    //     当余量清仓 —— run6 day50 中央粮 271,393 → 0 的根因守护）；国库户（hh-gov-*）两侧都不生成自动订单。
    Set<HouseholdId> effectiveMarketExcludedHouseholds = marketExcludedHouseholds;
    // ★★ E3：发行主体的权威答案是当前世界状态（governments），不是进程里的旧登记。
    //   日结算开始按**当日视图**重建登记表：旧世界/旧档 governments 为空 ⇒ 清空登记 ⇒ requireIssuerOf 逐字保留旧 fail-closed 行为。
    //   ★ B2：这是**逐日**读者（每天都可能因 GM 命令/发行腿改政府表）⇒ 读当刻值，不读段首 base。
    MoneyIssuance.syncAuthorities(dayView.governments().values());
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = accounts.householdGoods();
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = accounts.householdMoney();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = accounts.householdFrozenGoods();
    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney = accounts.householdFrozenMoney();
    EconomyMeta meta = session.sheet().meta().orElseThrow();
    long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    long dueCycle = currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1
    // ★★ 2026-10-07 GOV 非生产家户试点：周期开始日的政府铸币。
    //   位置：在任何转移/市场之前 —— 政府先按政策“印”出本周期可花的钱并落 FISCAL_ISSUE 审计；
    //   若政策量不够覆盖需求，后面的市场信用路径照常让它向家户借（= 政府发行债务）。
    //   ★★ B2：三个入口都收**当日视图** —— 铸币读当刻产业表（周期第一天的产业关账口径）、发债读当刻政府表。
    //   ★★★ W5（2026-10-11）：上面那一段（`GovernmentSeigniorage` / `GovernmentDebtIssuance` 双轨）**已整段删除** ——
    //     它是用户点名的"印钱绕过市场"的旁路，且与发债构成双轨（§一.11：旧设计直接删）。
    //     铸币现在的落点是**产业关账处**（见下面 harvest 之后调用的 {@link MintSettlement#settleCycleClose}）：
    //     钱是**生产出来的**（scale ×
    // IndustryDefinitionView.moneyOutputPerUnit），投入走既有的周期投入管线，不再有"周期开始直接加钱"。
    // ★★ E3：本次 revision 的发行审计收集器（id 由 transfer id + 币种确定性派生；并行分区也安全）。
    //   ★ 发行腿只在付方余额不足且付方 = 当前政府国库时才会用到；旧路径（无 issuer）不产生任何记录。
    MoneyIssuanceJournal issuanceJournal =
        new MoneyIssuanceJournal(dayView.governments(), currentCycle);

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    // ★★ R3B.2：生产单元表工作副本（进度/劳动/投入的唯一写点；IndustryDefinitionView 只留模板）。
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    // ★★ **计息的"昨日本金" = 当日起始快照**（M0.5 权威口径：计息日 {@code + ⌊昨 × 率 ÷ 1000⌋}，"昨"是当日开始时
    //   的本金）。必须在 4b 放贷 / 4c 偿还之前取；否则当天新借的债当天就被计息，与守恒式不符（实测：第 120 天
    //   新债 8,750 被记成 8,925，多出 175 = ⌊8,750 × 20‰⌋）。
    Map<DebtContractId, Long> principalAtDayStart = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> debtEntry : debts.entrySet()) {
      principalAtDayStart.put(debtEntry.getKey(), debtEntry.getValue().principal());
    }
    // ★★ **R2：劳动配额表**——日结算**读**它（当日劳动的唯一来源）。★ W3 起结算**不再**按存活比例缩它
    //   （见 {@link #scaleLaborOfIndustry}："人死了劳动没减"这条旧账的收口）⇒ 需要工作副本。
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    // ★ P2-A A3：成员份额不再是 Economy 状态组件 —— 家户人口组成由调用方作为**只读投影**传入（见 composition）。
    LinkedHashMap<AssetShareId, OwnershipStake> assetShares = session.sheet().assetShares();
    // ★★ S3：经营者状态机的工作副本 —— 与 industries/rows 同一条"日结算就地更新、结束后整体交出"的口径；
    //   状态转移读市场报告与债务/库存的可观察量，不在这里重算生产公式。
    LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    // ★★ **M2.6（自适应价格的写回点）**：市场表的工作副本 —— 固定模式下结算只读它、终态与 {@code base.marketTables()} 逐值相同；
    //   自适应打开时 {@code MarketSettlement} 返回更新后的价格表，本方法把**这一份**交给终态 ⇒ 改价只经
    //   "状态 → 变更集（markets 是既有 FieldDelta 组件）"这一条路，没有第二处改价。
    LinkedHashMap<MarketZoneId, MarketTable> markets = session.sheet().marketTables();
    // ★★ R4-E2b：生产关系表视图 —— 默认直接读 base 的不可变表；只有 E2b 进入执行真的新建 relation 时才物化工作副本
    //   （空表基线因此不产生任何拷贝）。下游所有日结算读取点统一走这个局部量，不再各处写 session.base().relations()。
    Map<ProductionUnitId, ProductionRules> relations = session.sheet().relationsOrBase();

    // ★★ R4-B.3a-perf：日结算的只读派生索引 —— **每天入口构建一次**。它把“unit 可用资产/产能、unit↔家户、
    //   格↔unit/产业、债务人↔债务”这些一天内不变的问题一次算好，串行热路径与并行 worker 都只查表。
    //   ★ 不进 EconomyData/ChangeSet/Codec，不作为第二份状态；OwnershipStake/IndustryDefinitionView/unit
    // 本步不改写，故这一份在
    //     日结算内始终有效。HouseholdLaborCommitment 会在劳动再分配后被改写 ⇒ 那之后用 withLabor(...) 换一次配额侧视图；
    //     DebtContract 会在借粮/偿还/计息后被改写 ⇒ 状态机之前用 withDebtContracts(...) 换一次债务视图。
    SettlementIndex settlementIndex =
        SettlementIndex.build(
            units,
            industries,
            assetShares,
            laborCommitments,
            householdEconomies,
            debts,
            relations,
            session.sheet().productionOrganizations(),
            session.sheet().assetOccupanciesOrBase(),
            session.sheet().productionRostersOrBase(),
            day);

    // ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）——
    //   放在任何公式之前（与 E14 的"在任何数量计算之前"同款）：副本缺键时若继续跑，缺的那一家会被当成"库存 0"
    //   ⇒ 它当天"吃 0、投入 0"，账面看不出少了谁。那正是本仓最反对的形态，故当场抛。
    requireHouseholdAccounts(householdEconomies, householdGoods);
    // ★★ **H4 的第一条守卫：货币账必须覆盖每一个"可能要花钱的家户"**（与商品那条同款、同一条理由）：
    //   缺键时若继续跑，那一家的可花余额会被当成 0 —— 它当天的有效需求因此是 0、市场买不到粮、
    //   而账面（缺口只多不少）看起来完全正常。⇒ 缺键 ⇒ 当场抛（"没有账"与"账是空的"是两件事）。
    requireHouseholdMoney(householdEconomies, householdMoney);

    long dayStartPopulation = 0L;
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      dayStartPopulation += householdEconomy.population();
    }
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DAY_START",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "mapId",
                meta.mapId(),
                "rows",
                householdEconomies.size(),
                "population",
                dayStartPopulation,
                "units",
                units.size(),
                "markets",
                markets.size(),
                "organizations",
                session.sheet().productionOrganizations().size(),
                "debtContracts",
                debts.size(),
                "modes",
                // ★★ B5（裁定十八）：本组件（生产方式定义）**已不在 economy 状态里**（唯一权威在 production）⇒ 日视图只读；
                //   本行是逐日日志的只读计数，不存在"当刻与段首不同"的可能。
                dayView.modes().size(),
                "topologyRegions",
                topology.regions().size()));

    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SETTLEMENT_START_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "rows",
                  householdEconomies.size(),
                  "units",
                  units.size(),
                  "markets",
                  markets.size(),
                  "organizations",
                  session.sheet().productionOrganizations().size(),
                  "debtContracts",
                  debts.size(),
                  "modes",
                  // ★★ B5（裁定十八）：同上一处（生产方式定义表在 production，日视图只读；逐日日志只读计数）。
                  dayView.modes().size(),
                  "topologyRegions",
                  topology.regions().size()));
    }

    // ── 0-entry. ★★ R4-E2b：候选预设的实际采用（GM 预设 → 合条件家户 → TRIALING 试产）──────────────
    //   ★ 位置：**在现扣周期投入与劳动再分配之前**（下面 `if (plantingDrawsFirst)` / `else` 两支都会在
    //     当天各自的第一阶段扣投入并重排劳动）—— 新 unit 因此当天就能进入本周期：progressDays=0 ⇒ 当天
    //     drawCycleInputs 会为它扣一次投入，随后 reallocateLabor 按它的 need 保留配额。
    //   ★ 两段式：planEntries 只读评估并产出意向/具名拒绝；execute 对意向**重新评估**后写工作副本。
    //   ★ 触发门槛：demands/candidates 任一为空 ⇒ 连评估都不做（空表基线逐值不变），只投递空审计。
    //   ★ 新 unit 触发 reallocateLabor 的例外集见下面的 `enteredToday`：只建了它的那个 hex 不再因它
    //     "看起来像周期第一天"而重排一个本已跑了一半的周期（既有 unit 的配额因此不被进入动作改写）。
    Set<ProductionUnitId> enteredToday = Set.of();
    List<EntryOutcome> entryOutcomes = List.of();
    // ★★ B2c：触发门槛 = 有需求 **且** 有"可以被采用的方案"（= production 定义里 hexKeyOf(id) 为空的那些，
    //   唯一拼写点见 EconomyEntrySettlement.plans）。★ 段首 base 的 demands/方案池在段内不被本段写
    //   （写入口只有命令面/定义写面）⇒ 读它是"静态模板"而不是"跳过当日视图"。
    Map<IndustryId, IndustryDefinitionView> planPool = EconomyEntrySettlement.plans(industries);
    Map<IndustryId, io.mosire.simos.actor.api.actor.ActorRef> planAssetSources =
        base.meta().map(EconomyMeta::planAssetSources).orElseGet(Map::of);
    // ★★ B3（裁定十二）：按方案声明的**劳动来源**（缺键 ⇒ SELF = 旧候选缺省，逐值不变）。
    Map<IndustryId, io.mosire.simos.economy.api.relation.LaborSource> planLaborSources =
        base.meta().map(EconomyMeta::planLaborSources).orElseGet(Map::of);
    if (!base.demands().isEmpty() && !planPool.isEmpty()) {
      EconomyEntrySettlement.Plan entryPlan =
          EconomyEntrySettlement.planEntries(
              session.productionRuntime(),
              base.demands(),
              industries,
              planAssetSources,
              planLaborSources,
              settlementIndex,
              MarketIndex.of(dayView),
              householdEconomies,
              householdGoods,
              householdMoney,
              composition,
              units,
              assetShares,
              laborCommitments,
              operatorConditions,
              relations,
              day);
      List<EntryOutcome> outcomes = new ArrayList<>(entryPlan.outcomes());
      if (!entryPlan.intents().isEmpty()) {
        EconomyEntrySettlement.Execution execution =
            EconomyEntrySettlement.execute(
                session.productionRuntime(),
                entryPlan.intents(),
                base.demands(),
                industries,
                planAssetSources,
                planLaborSources,
                units,
                assetShares,
                laborCommitments,
                operatorConditions,
                session.sheet().relations(),
                householdEconomies,
                householdGoods,
                householdMoney,
                composition,
                MarketIndex.of(dayView),
                day,
                session.sheet().productionRosters());
        outcomes.addAll(execution.outcomes());
        enteredToday = execution.enteredUnitIds();
        if (execution.changed()) {
          // unit/relation/份额/配额都变了 ⇒ 换一份索引再进现有日结算（进入日是低频事件，一次 O(状态) 重建）。
          relations = session.sheet().relationsOrBase();
          settlementIndex =
              SettlementIndex.build(
                  units,
                  industries,
                  assetShares,
                  laborCommitments,
                  householdEconomies,
                  debts,
                  relations,
                  session.sheet().productionOrganizations(),
                  session.sheet().assetOccupanciesOrBase(),
                  session.sheet().productionRostersOrBase(),
                  day);
        }
      }
      entryOutcomes = outcomes;
    }
    EntryOutcomeFeed.publish(meta.mapId(), day, entryOutcomes);
    if (!entryOutcomes.isEmpty()) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "ENTRY",
                  EconomyLogSource.ECONOMY_ENTRY,
                  "day",
                  day,
                  "outcomes",
                  entryOutcomes.size(),
                  "enteredUnits",
                  enteredToday.size(),
                  "rejected",
                  entryOutcomes.stream().filter(outcome -> !outcome.accepted()).count()));
    }
    if (TRACE.isDebugEnabled() && !entryOutcomes.isEmpty()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "ENTRY_DETAIL",
                  EconomyLogSource.ECONOMY_ENTRY,
                  "day",
                  day,
                  "outcomes",
                  entryOutcomes.size(),
                  "enteredUnits",
                  enteredToday.size()));
    }
    if (RAW.isTraceEnabled()) {
      for (EntryOutcome outcome : entryOutcomes) {
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "ENTRY_OUTCOME",
                    EconomyLogSource.ECONOMY_ENTRY,
                    "day",
                    day,
                    "household",
                    outcome.household().value(),
                    "plan",
                    outcome.plan().value(),
                    "accepted",
                    outcome.accepted(),
                    "modeKey",
                    outcome.plan().value(),
                    "industry",
                    outcome.industryId().value(),
                    "trialScale",
                    outcome.trialScale(),
                    "expectedDay",
                    outcome.expectedDay(),
                    "laborMilli",
                    outcome.laborMilli(),
                    "reason",
                    logReason(outcome.reason())));
      }
    }

    // 逐行当日发生额（流水的事后组装）。★ R3 起两张实物表都是**逐商品**的（{@link FlowRow#income()} 由标量改成 Map）。
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods = new LinkedHashMap<>();
    // ★★★ W5.5（2026-10-11）：这里原有 {@code borrowing}（逐户"本日新借入粮"的累加器）—— 它**只服务借粮腿**，
    //   随该腿整段删除（AGENTS §一.11）。★ 后果具名：{@code FlowRow.newBorrowing}（毫粮口径的持久读数）从此由
    //   结算路径**恒写 0**，而货币腿借入的是**钱**（不是粮）⇒ 换标的会让那个字段的量纲变成谎话，故本批不喂它。
    //   需要"这一户这一周期借了多少钱"的读者应从 {@code debtContracts}（{@code DebtUnit.Money}）取 —— 见账本"连带物"。
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income = new LinkedHashMap<>();
    // ★★ **R5/T4：生产损耗不再有"逐行累加器"** —— 损耗不是"谁消费了"，是"蒸发了"，它只进 {@link
    //   ProductionLedger#losses()}（守恒式里自成一项，见类注 §6.1 的新式）。留一个"分了损耗但没人收"的中间残留
    //   （旧口径里它是 consumed 的一部分）会让 I4.2 的 ΣLoss 被算两遍。
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Long> birthsToday = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Long> interestToday = new LinkedHashMap<>(); // 周期末计息那一笔（§7.1③）
    // ★★ **H5：周期末偿还的那一笔**（{@code FlowRow.repaid} 的第一个写入点 —— 改前它恒 0）。
    LinkedHashMap<HouseholdId, Long> repaidToday = new LinkedHashMap<>();
    // ★★ E4c：货币债偿还的逐币种读数（**不塞进粮口径的 repaidToday**；见 FlowRow.repaidMoney）。
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> repaidMoneyToday = new LinkedHashMap<>();
    // ★★ E5b：E4c 本日逐合同的本金偿还读数（压力判定"本金未下降"的具名证据；只进审计，不改状态身份）。
    LinkedHashMap<DebtContractId, Long> repaidPrincipalByDebt = new LinkedHashMap<>();
    // ★★ E4c：本日资本化的欠租/欠薪（按 unit.key() 分组；资本化不搬库存/货币，故只进 FlowRow.capitalizedArrears）。
    LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedArrearsToday = new LinkedHashMap<>();
    // ★★ **当日的缺口**（"今天这一顿没吃上多少"）—— 它只是**读数**（进 unmetNeed / 报 Social 的口粮缺口）。
    //   ★★★ W5.5 起它不再触发任何借贷：借粮腿已按 S-12① 整段退役；借钱发生在市场轮里、买货之前。
    LinkedHashMap<HouseholdId, Long> deficitToday = new LinkedHashMap<>();
    // ── 0-entry.4. ★★ E6a 模式变迁执行（理想架构 §4.4；计划 E6a）────────────────────────────────
    //   ★★ 位置：**必须在自动组织阶段之前** —— 变迁写回 EXITING/ACTIVE 组织、同一 unit 的新 modeKey 与新
    //      HouseholdClassMembership；随后 organize 以新归属做幂等检查（新组织已 ACTIVE ⇒ 不重建），retain=1000 的旧
    //      EXITING 组织也不会被重建（organize 对该状态显式跳过）。
    //   ★★ 闸门：`modeTransitions` 为空时连工作副本都不建（空表基线逐值不变）；apply 内部再判一次到期 PENDING。
    //   ★ 只对"**当刻**表里真有一条到期 PENDING"才进入：APPLIED/FAILED 的历史变迁不产生任何拷贝（跨 revision 的稳定基线）。
    //   ★★ B2：判据读**当日视图**（= 会话工作副本优先）—— 段内第 D 天执行过的变迁在第 D+1 天已是 APPLIED，
    //     读段首 base 会让它在整段里每天都"看起来到期"（每天白跑一次 apply + 白建六张工作副本），
    //     与"逐日推进"路径的行为也不同源（那边每天看到的都是前一天终态）。
    //   ★ 它只写 enterprises / units / pledges / classStandings / modeTransitions / classShares
    // 六张工作副本。
    boolean dueModeTransition = false;
    for (ModeTransition transition : dayView.modeTransitions().values()) {
      if (transition.status() == ModeTransition.Status.PENDING
          && transition.effectiveDay() <= day) {
        dueModeTransition = true;
        break;
      }
    }
    if (dueModeTransition) {
      EconomyModeTransitionSettlement.Outcome transitionOutcome =
          EconomyModeTransitionSettlement.apply(
              session.productionRuntime(),
              dayView,
              day,
              householdEconomies,
              session.sheet().productionOrganizations(),
              units,
              assetShares,
              session.sheet().pledges(),
              session.sheet().classMemberships(),
              session.sheet().modeTransitions(),
              session.sheet().classShares());
      if (transitionOutcome.changed()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MODE_TRANSITION",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "applied",
                    transitionOutcome.applied(),
                    "failed",
                    transitionOutcome.failed()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MODE_TRANSITION_DETAIL",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "applied",
                    transitionOutcome.applied(),
                    "failed",
                    transitionOutcome.failed()));
      }
    }

    // ── 0-entry.5. ★★ E2 自动生产组织（理想架构 §4.2 ①；计划 E2）────────────────────────────
    //   ★★ 位置：在 **0-entry 候选采用之后、现扣投入/劳动再分配/消费之前** —— 今天新建的 unit 因此当天就能进入本周期
    //      （progressDays=0 ⇒ 现扣投入会为它扣一次料，随后劳动再分配按它的 need 保留配额）。
    //   ★★ 闸门：`modes` 为空时**这一整段不执行**（连工作副本都不建）⇒ 旧档/未接线世界的 HouseholdClassRule
    //      与全部 settle* 路径逐值不变。闸门在调用点与 organize 内各判一次（显式、可读）。
    //   ★ 自动组织只写五张工作副本：units / relations / assetShares（租佃拆分）/ allocations /
    //      productionOrganizations；不新建 IndustryDefinitionView 模板、不写 markets/rows/debts。
    EconomyEnterpriseSettlement.Outcome enterpriseOutcome =
        EconomyEnterpriseSettlement.Outcome.empty();
    // ★★ B5（裁定十八）：`modes` 读日视图（production 定义权威）⇒ 本闸门在段内恒定；它判的是"这个世界接没接线 E1–E6"。
    if (!dayView.modes().isEmpty()) {
      enterpriseOutcome =
          EconomyEnterpriseSettlement.organize(
              session.productionRuntime(),
              dayView,
              // ★ E6a：变迁刚写过的 standing 工作副本优先（无变迁时 = base 的不可变表，旧路径逐值不变）。
              session.sheet().classMembershipsOrBase(),
              householdEconomies,
              industries,
              // ★ 2026-10-09：传入**可写**质押工作副本 —— 租佃拆分会把 ACTIVE 质押按比例跟到新份额（OwnershipStakeBook 就地写）。
              session.sheet().pledges(),
              units,
              session.sheet().relations(),
              assetShares,
              laborCommitments,
              composition,
              householdGoods,
              MarketIndex.of(dayView),
              session.sheet().productionOrganizations(),
              session.sheet().productionRosters());
      if (enterpriseOutcome.changed()) {
        // unit/关系/份额/配额/组织任一变了 ⇒ 换一份索引再进现有日结算（与 0-entry 的执行后重建同款）。
        // ★ P2：条件从"新建了 unit"放宽到 changed()，确保今天新落的 SHORTAGE/ACTIVE 组织也进
        //   DebtPartyResolver 的组织视图（只有组织变化、没有新 unit 时旧索引会漏掉它们）。
        relations = session.sheet().relationsOrBase();
        settlementIndex =
            SettlementIndex.build(
                units,
                industries,
                assetShares,
                laborCommitments,
                householdEconomies,
                debts,
                relations,
                session.sheet().productionOrganizations(),
                session.sheet().assetOccupanciesOrBase(),
                session.sheet().productionRostersOrBase(),
                day);
        // ★ 今天由组织阶段新建的 unit 加进"刚进入"豁免集：不得让它触发/参与同格既有周期的重排
        //   （与 R4-E2b 的 enteredToday 同一条口径；下一个日结算日它自然成为普通 unit）。
        Set<ProductionUnitId> withOrganized = new LinkedHashSet<>(enteredToday);
        withOrganized.addAll(enterpriseOutcome.createdUnitIds());
        enteredToday = withOrganized;
      }
      if (enterpriseOutcome.changed()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "ORGANIZE",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "createdUnits",
                    enterpriseOutcome.createdUnitIds().size(),
                    "organizations",
                    session.sheet().productionOrganizations().size(),
                    "rows",
                    householdEconomies.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ORGANIZE_DETAIL",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "changed",
                    enterpriseOutcome.changed(),
                    "createdUnits",
                    enterpriseOutcome.createdUnitIds().size(),
                    "organizations",
                    session.sheet().productionOrganizations().size()));
      }
      if (RAW.isTraceEnabled() && enterpriseOutcome.changed()) {
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "ORGANIZE_RESULT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "createdUnits",
                    enterpriseOutcome.createdUnitIds(),
                    "organizations",
                    session.sheet().productionOrganizations().size()));
      }
    }

    // ── 0. 现扣周期投入（周期的第一天）：**在当天吃饭之前**把种子/原料划走（v2 spec §3.2）──────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    //   ★★ H3（C3）：这一步里"谁出料"由 relation.inputSupplier 说 —— 家户供方取它自己那一本账（缸不够就扣光 ⇒ 规模缩）；
    //     聚合主体（庄园/作坊/产业型家户）的账 economy 看不见 ⇒ 由该产业名下的家户账代理；第一层取不满时同格兜底（粮除外）。
    //     逐条口径见 {@link #drawCycleInputs} 的方法注释。
    // ★★ **H0：家户 → 它供给的产业**（从配额表推，唯一拼写点见 {@link #industriesOfHouseholds}）——
    //   本日的两处都要它：① 各行流水"本期"何时翻篇（家户没有自己的周期，见流水循环的注释）；
    //   ② 借粮的"本周期自需"要一个 cycleDays（见 {@link #cycleDaysByHousehold}）。
    Map<HouseholdId, Set<ProductionUnitId>> unitsOfHousehold = settlementIndex.unitsByHousehold();

    // ★★ **H2：家户 actor 的反查表**（一天建一次）—— "这条转移的某一端是不是家户、是哪一家"这个问题
    //   在三个落点（关系实付 / 同格取材 / 同格借粮）**只能有一个答案**，故它在这里建好、逐处传下去。
    //   ★ 键集一天不变（人口的增减不改行的身份），故建一次就够。
    Map<ActorRef, HouseholdId> householdOfActor = settlementIndex.householdByActor();

    // ── 0b. 到货（M2.4：在途是跨 tick 状态；到达日"在途减、目的地库存增"）────────────────────
    //   ★ 必须排在消费/市场/借粮**之前**：到货的粮当天就能吃、当天就能再挂牌（但到货前消费不到它）。
    //   ★ 损耗逐票由买方承担（M2.5）：到达时从在途量里扣、记进 ledger 的损耗账户；买方只收到净额。
    //   ★ 迟到（手工搭的状态里 arrivalTick < day）照样补投，不让货卡在在途表里。
    LinkedHashMap<ShipmentId, ShipmentBatch> shipments = session.sheet().shipments();
    int shipmentsBefore = shipments.size();
    deliverShipments(day, session, accounts, ledger, parallelism);
    if (shipmentsBefore > 0) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "SHIPMENTS_ARRIVED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "arrivedBatches",
                  shipmentsBefore - shipments.size(),
                  "remainingInTransit",
                  shipments.size()));
    }
    if (TRACE.isDebugEnabled() && shipmentsBefore > 0) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SHIPMENTS_ARRIVED_DETAIL",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "arrivedBatches",
                  shipmentsBefore - shipments.size(),
                  "remainingInTransit",
                  shipments.size()));
    }

    // ★★ 2026-10-08（阶段 1）：本日套利决定的收集器 —— 排序阶段（LaborQueueSettlement）往里放，
    //   市场阶段（MarketSettlement）取走。逐日瞬态、不进状态（§4.3.5 的快照纪律）。
    MarketArbitragePlan.Collector arbitrageCollector = new MarketArbitragePlan.Collector();
    // ★★★ B8（缺陷 DEF-T2b-2）：**本日实扣读数（`drawnProductionInputs`）的唯一清零点** —— 位置是决定性的：
    //   必须在**本日两处现扣之前**（下面 `plantingDrawsFirst` 两支各自的第一阶段），且**只在本段入口清一次**。
    //   ★ 为什么不能留在后面（`driveProductionDay` 之前）清：那会把本日刚落进去的实扣量抹掉，使
    //     `executeDay` 的 `finalInputsMilli` 恒空 ⇒ 投入那一路规模恒 0 ⇒ 该生产日不推进、毛产恒 0。
    //   ★ 为什么不能不清：读数按"本生产日"语义（见 `EconomySession#clearDrawnProductionInputs`），
    //     同一会话跨多日（`EconomyDayStepper#step` 连推）时不得把昨天的实扣量当成今天的。
    session.clearDrawnProductionInputs();
    // ★★★ D-032 W4：匹配与 W3 共用同一权威可支付库存读口；快照绑定本日 AccountSession，不跨日复用。
    AccountSnapshot matchingAccountSnapshot = accounts.snapshot();
    WagePayableInventoryLookup matchingWagePayableInventoryLookup =
        new WagePayableInventoryLookupImpl(
            dayView.inventoryReservations(), day, () -> Optional.of(matchingAccountSnapshot));
    if (plantingDrawsFirst) {
      drawCycleInputsPartitioned(
          session,
          accounts,
          consumedGoods,
          householdOfActor,
          ledger,
          day,
          parallelism,
          settlementIndex);
      // ★★ P2-B：有 modes 的世界走"每 tick、按家户的利润率排队"（§13.4/§13.5）；旧档/未接线世界保留
      //   hex 分区再分配（换入空集逐值等价旧路径）。
      // ★★ B5（§13.15.2 裁定十八）：`modes` 读日视图（default → production 定义权威）⇒ 段内恒定。
      if (dayView.modes().isEmpty()) {
        // ★ R2：劳动再分配已按 hex 并行（配额键含产业 id ⇒ 跨 hex 无冲突；同一批次跨 hex 的全局协调留给 R3）。
        // ★★ R4-E2b：今天刚进入的 unit 不触发"本周期是第一天"的重排判定（它今天确实是 0，但它不属于既有周期的重排对象）。
        reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      } else {
        // ★★ §4.3.1：劳动分配、库存分配、投入扣减是**同一次遍历**的三个动作 —— 本调用点在投入扣减**之后**
        //   （PLANTING_DRAWS_BEFORE_CONSUMPTION = true）⇒ 套利快照里的库存已是扣完料的余额（I14 由此结构性成立）。
        LaborQueueSettlement.apply(
            session,
            settlementIndex,
            day,
            composition,
            enteredToday,
            accounts,
            arbitrageCollector,
            survivalSocialFacts,
            matchingWagePayableInventoryLookup);
      }
      // ★ 配额被改写 ⇒ 换一份“配额侧”视图，后续（unit 家户归属/市场参与者/人口回写）继续 O(1) 查表。
      settlementIndex = settlementIndex.withLabor(units, householdEconomies, laborCommitments);
    }

    // ── 1~2. 消费（各自吃自己的库存；缺口**先记下不借**）──────────────────────────────
    //   ★ H4：每条家户行的 cycleDays 提成局部量 —— 它喂"市场自留"（MarketSettlement），
    //     别处各算一遍就是同一个量的第二处拼写点（算错不会报错，只会让两处口径悄悄漂开）。
    Map<HouseholdId, Long> cycleDaysByHousehold =
        cycleDaysByHousehold(
            householdEconomies,
            industries,
            units,
            unitsOfHousehold,
            settlementIndex.industriesByHex());
    consumeOwnStockPartitioned(
        householdEconomies, accounts, consumedGoods, unmetToday, deficitToday, parallelism);

    if (!plantingDrawsFirst) {
      drawCycleInputsPartitioned(
          session,
          accounts,
          consumedGoods,
          householdOfActor,
          ledger,
          day,
          parallelism,
          settlementIndex);
      // ★★ B5（§13.15.2 裁定十八）：`modes` 读日视图（default → production 定义权威）⇒ 段内恒定。
      if (dayView.modes().isEmpty()) {
        reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      } else {
        // ★★ P2-B §13.4：每 tick 重算 —— 不在生产周期开始时锁死；投入已扣（本支在消费后），判定有据。
        // ★★ 2026-10-08（阶段 1）：套利活动与生产进同一个排序器；快照 = 本刻（消费后、投入已扣）的可动余额。
        LaborQueueSettlement.apply(
            session,
            settlementIndex,
            day,
            composition,
            enteredToday,
            accounts,
            arbitrageCollector,
            survivalSocialFacts,
            matchingWagePayableInventoryLookup);
      }
      // ★ 配额被改写 ⇒ 换一份“配额侧”视图（与 plantingDrawsFirst 分支同一条阶段边界）。
      settlementIndex = settlementIndex.withLabor(units, householdEconomies, laborCommitments);
    }

    if (TRACE.isDebugEnabled()) {
      long deficitHouseholds = 0L;
      long deficitGrain = 0L;
      for (long deficit : deficitToday.values()) {
        if (deficit > 0L) {
          deficitHouseholds++;
          deficitGrain += deficit;
        }
      }
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "INPUTS_CONSUMPTION",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "consumedQuantities",
                  traceTotalGoods(consumedGoods),
                  "deficitHouseholds",
                  deficitHouseholds,
                  "deficitGrainMilli",
                  deficitGrain));
    }

    // ★★★ B1（D-032 §4.3 数据流第 5 段）：**本生产日的推进改经 production 的两步契约** ——
    //   `probe`（无副作用）→ economy 排序与统一分配 → `executeDay`（按最终投入，一实例一次 = 一个生产日）→ 回执。
    //   ★ 位置是决定性的：必须在**劳动分配与现扣投入都落地之后**（那是"最终投入"），且在主循环读实例状态**之前**。
    //   ★ economy 从此**不写实例状态**：进度/周期台账/效率全部由 production 的候选态承载，
    //     主循环只读回执（见下面 3~4 段的 receipt 分支）。
    //   ★★ B2b（§13.11.4 第 4 条）：建档类写者（进入/组织/变迁/迁移）已**直调 production 契约口**
    //     （`createInstance` / `removeInstance` / `reoperateInstance`）⇒ 本处不再有"推回工作副本"的 flush 桥
    //     （`unitsWorkCopy` / `flushUnitWorkCopy` 已整体删除），实例当刻就在权威候选态里。
    //   ★★★ B8（缺陷 DEF-T2b-2）：**本日实扣读数（`drawnProductionInputs`）必须在它被读走之后才清**。
    //     改前这里就调 `session.clearDrawnProductionInputs()` —— 那一步把本日现扣阶段（上面
    //     `drawCycleInputsPartitioned`）刚落进去的实扣量**当场抹掉**，于是 `driveProductionDay` 读到的
    //     `finalInputsMilli` 恒为空 ⇒ 投入那一路规模恒 0 ⇒ 该生产日 `progressDays=0`、`cycleInputUsedMilli={}`
    //     ⇒ **毛产恒 0**（"声明了周期投入的生产"整类玩法不可用）。清零点已在**本段之前**（现扣之前的
    //     `session.clearDrawnProductionInputs()`，见那里），故这里不再清 —— 清在这里就是把本日读数删掉。
    io.mosire.simos.production.contract.ProductionDayResult productionDay =
        driveProductionDay(session, day, settlementIndex, laborCommitments, operatorConditions);
    if (!productionDay.isEmpty() && TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "PRODUCTION_DAY_DRIVEN",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  day,
                  "steps",
                  productionDay.receipts().size() + productionDay.rejectionCount(),
                  "executed",
                  productionDay.receipts().size(),
                  "rejected",
                  productionDay.rejectionCount(),
                  "closed",
                  productionDay.closedUnitScales().size()));
    }
    for (Map.Entry<ProductionUnitId, String> rejection : productionDay.rejections().entrySet()) {
      // ★ I15：业务拒绝 = INFO 具名理由（世界照常推进；释放掉的资源由 economy 后续重新分配）。
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "PRODUCTION_DAY_REJECTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  day,
                  "unit",
                  rejection.getKey().value(),
                  "reason",
                  rejection.getValue()));
    }

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 ────────────────────────────────────
    boolean anyCycleClosed = false;
    Set<ProductionUnitId> newCycleUnits = new LinkedHashSet<>();
    // ★★★ A5（2026-10-10）+ A7：本日关账、且**产出运输服务**的 unit（`trade@hex`）—— 它们是"本日到周期边界"的
    //   触发条件（A7 起不再是清扫范围）：清扫扫的是**任何持有 haul 的家户**，在本日收获**之前**作废其陈货
    //   （用户裁定「不可储存转卖」；见 expireUnservedHaulService 的类注）。
    List<ProductionProcess> haulExpiryUnits = new ArrayList<>();
    // ★★★ A5：当日运输服务的三个对账量（产出 / 交付 / 作废，毫服务）—— 交付量由市场轮交出（MarketOutcome.haulService()），
    //   三者一起进当日对账 INFO（见 4 段末的 HAUL_SERVICE_SETTLED）。
    long haulServiceProducedMilli = 0L;
    long haulServiceExpiredMilli = 0L;
    // ★★ **R2：当日劳动的唯一来源 = 劳动分配表**（第三阶段设计稿 §四）。按 actor id 归集一次（O(配额条数)），
    //   再逐产业取用 —— 产业 id 与 actor id 的对应关系由 EconomyData 的构造期守卫判死
    //   （产业型主体必须指名已存在的产业、非产业型主体不得与产业 id 撞名）。
    Map<String, Long> laborByUnit = settlementIndex.laborByUnit();
    // ★★ **H0：周期刚翻篇的 unit**（progressDays 归 0，含创世）—— 先算好，因为"家户的流水何时翻篇"要读它。
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : units.entrySet()) {
      if (entry.getValue().progressDays() == 0L) {
        newCycleUnits.add(entry.getKey());
      }
    }
    // ★★ **H0：家户的流水何时整行重记** —— 它供给的**任一**产业翻篇的那一天（§八.5 的清零点）。
    //   ★ 为什么"任一"：家户自己没有周期字段（K2 之后周期仍住在 IndustryDefinitionView 上），而它的所得来自它供给的那些产业的收获
    //     ⇒ 只要有一个产业翻篇，这本账的"本期"就跟着翻。★ 代价如实记：产业周期**不同步**时这是近似（真档三个产业都是
    //     120 天且同时创世 ⇒ 与改前**逐值相同**；改前是"该行所属那一个产业"翻篇）。
    //   ★ 没有配额的产业的家户（供给集合为空）**从不翻篇**：宁可让它逐字累计（读得出来），也不每天清零把发生额抹掉。
    //   ★★ **兜底**：一条配额都没有的家户（手工搭的状态、或全部产业都不给它配额）退回**它住的那一格的产业**
    //     （与 {@link #cycleDaysByHousehold} 同一条兜底）—— 否则它的流水**永不翻篇**，利息/出生死亡会一直累加
    //     （实测：`EconomyDebtTest` 的 interestDue 读成两个周期之和）。
    Map<String, List<IndustryId>> hexToIndustries = settlementIndex.industriesByHex();
    Set<HouseholdId> newCycleHouseholds = new LinkedHashSet<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry : householdEconomies.entrySet()) {
      HouseholdId key = householdEntry.getKey();
      Set<ProductionUnitId> supplied = unitsOfHousehold.getOrDefault(key, Set.of());
      if (supplied.isEmpty()) {
        // ★ 兜底：没有配额的户按它住的那一格的产业找 unit（与 cycleDaysByHousehold 同一条兜底）。
        HouseholdEconomy cycleHouseholdEconomy = householdEntry.getValue();
        if (cycleHouseholdEconomy != null) {
          // ★ R4-B.3a-perf：格 → unit 由入口索引一次给出（旧实现逐无配额家户全量扫 8,940 个 unit）。
          supplied =
              new LinkedHashSet<>(
                  settlementIndex.unitsInHex(
                      IndustryHexKeys.hexKey(
                          cycleHouseholdEconomy.view().hex().q(),
                          cycleHouseholdEconomy.view().hex().r())));
        }
      }
      for (ProductionUnitId unitId : supplied) {
        if (newCycleUnits.contains(unitId)) {
          newCycleHouseholds.add(key);
          break;
        }
      }
    }
    // ★★ H5：本日关账的产业（它们的缺口读数要等救济通道走完才报 —— 见下面的 4d）。
    List<ClosedUnit> closed = new ArrayList<>();
    // ★★ P10.2：关账 unit 的周期劳动/投入快照（在下面把 unit 周期状态清零**之前**抓；⑦ 利润汇总用）。
    List<EnterpriseProfitBook.CloseFact> closedFacts = new ArrayList<>();
    // ★★ R2：关账产业先收集成工作单元，进度/周期状态按原序落回 industries；收获/关系分账在收集完后
    //   按 hex 并行执行（同一 hex 内的多个产业共用家户账/关系账 ⇒ 同分区串行，见 runHarvestStage）。
    List<HarvestWork> harvestWorks = new ArrayList<>();
    int harvestOrder = 0;
    // ★★★ W5（2026-10-11）：本日关账的 unit → 关账处求好的规模（scale）。铸币产出走**同一个 scale**
    //   （{@link MintSettlement#settleCycleClose}）—— 这是"投入真实消耗、产出 = 货币"两半不漂开的落点：
    //   投入侧由既有的周期投入管线（drawCycleInputs + ProductionEfficiencyBook.evaluateHarvest）扣，
    //   产出侧读的正是同一份求好的 scale。★ 没关账的日子这张表是空的 ⇒ 铸币一个字节都不动。
    Map<ProductionUnitId, Long> closedUnitScales = new LinkedHashMap<>();
    // ★★ Z2（§6.1）：当日注入的修正集（缺省 1000‰ = 中性；读取的是本 tick 的只读视图）与效率表工作副本。
    //   效率表是结算工作副本（Z1 已在 EconomyStateBuilder 备好）；缺行 = 全 0 余数 + 本周期全 1000‰。
    // ★★ Z2（§6.2 ⑥）：产品产出数量 GM 覆盖表（只读；缺产业 = 该产业全部回落配方默认值）。
    // ★★★ B2b：唯一权威在 production（economy 侧组件已删）⇒ 从本会话的运行时常量投影取；
    //   ★ 同源：production 的 `executeDay` 用它算毛产（`effectiveOutputPerUnit`），economy 的收获腿
    //     读的是**同一份**（I6：预计与实际共用同一份覆盖参数；economy 不再有第二份表）。
    Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides =
        session.productionRuntime().outputQuantityOverrides();
    // ★★★ B1（D-032）：实例的进度 / 周期台账 / 效率现在全部由 production 的候选态承载（见上面执行段的
    //   `executeDay`）。本循环只做三件事：
    //   ① 从**回执**读本实例的推进结果（进度、是否关账、关账规模、实际投入）；
    //   ② 把"本日关账"翻译成 economy 侧的下游工单（收获/铸币/缺口/利润快照）；
    //   ③ 不再写任何实例状态（`units.put(...)` 已整段删除）。
    //   ★ 回执缺失 = 本实例今天没被推进（业务拒绝或本就不在步表里）⇒ 跳过它的关账工单；
    //     这一支**不是**静默降级：拒绝已在上面逐条 INFO 具名。
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionProcess unit = units.get(id);
      IndustryDefinitionView industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      java.util.Optional<io.mosire.simos.production.contract.ProductionReceipt> receiptOrEmpty =
          productionDay.receiptOf(id);
      if (receiptOrEmpty.isEmpty()) {
        continue; // 本实例今天没被推进（业务拒绝已具名 INFO）⇒ 无进度、无关账
      }
      io.mosire.simos.production.contract.ProductionReceipt receipt = receiptOrEmpty.get();
      // ★★ **H0：这个 unit 的家户行 = 由劳动配额表推**（{@code SettlementIndex.householdsOf}：配额 activity == unit
      // id）。
      //   ★ R4-B.3a-perf：入口索引已按 unit 聚好（去重/排序口径与 householdKeysOf 逐值相同）。
      List<HouseholdId> keys = settlementIndex.householdsOf(id);
      // ★★ **当日实际劳动取自该 unit 名下的全部配额**（不再从"本产业各行 laborMilli × participation"独立算）。
      long laborToday = laborByUnit.getOrDefault(id.value(), 0L);
      long cycledLabor =
          unit.cycleContributions().values().stream()
              .mapToLong(io.mosire.simos.production.contract.CycleContribution::laborMilli)
              .sum();
      long progressed = receipt.progressDaysAfter();
      // ★★ production 已经用**同一份**实际投入累计好了周期投入台账 ⇒ 这里读它，不再自己 merge 一遍
      //   （旧口径在现扣阶段 `units.put(withCycleState(..., accumulated))`，那是 economy 写实例状态的最后一处）。
      Map<CommodityId, Long> nextInputUsed = receipt.consumedInputs();
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**：>= 才把合法状态收获掉。
      //   ★ B1 起"关不关账"是 production 的判定（回执 `cycleClosed`）——economy 不再自己拿
      //     `progressDays + 1 >= cycleDays` 重算一遍（那是第二份口径）。
      if (receipt.cycleClosed()) {
        // ── 周期末：产出 → 产权条目 + 关系规则入账（★ 缺口读数在**所有救济通道之后**，见下面的 4d）──
        // ★★★ B1：规模由 production 的唯一入口（`ProductionEfficiency`）给出（回执 `scale` = finalScale）；
        //   economy 侧的旧效率书（`ProductionEfficiencyBook.evaluateHarvest`）**已整条退役**
        //   —— 它按 `[0,2000]‰` 的旧上限与旧加成位置算，留着就是第二份公式。
        long closedScale = receipt.scale();
        // ★★★ D-032 B3（I9）：收获阶段的两个"实际事实"一律取自**回执**（唯一交接面）：
        //   ① 本周期逐家户实际劳动 = `receipt.cycleContributions()`（逐 tick 累计、含中途换出工户、不拿关账日反推）；
        //   ② 本周期实际投入 = `receipt.consumedInputs()`（production 的实际扣量）。
        Map<HouseholdId, Long> cycleLaborByHousehold = new LinkedHashMap<>();
        Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
            cycleLaborByParticipant = new LinkedHashMap<>();
        for (io.mosire.simos.production.contract.CycleContribution contribution :
            receipt.cycleContributions().values()) {
          if (contribution.laborMilli() > 0L) {
            cycleLaborByHousehold.merge(
                contribution.household(), contribution.laborMilli(), Math::addExact);
            cycleLaborByParticipant.put(contribution.participantKey(), contribution.laborMilli());
          }
        }
        harvestWorks.add(
            new HarvestWork(
                unit,
                industry,
                closedScale,
                outputQuantityOverrides.getOrDefault(industry.id(), Map.of()),
                hexOfIndustry(unit.industry()),
                harvestOrder++,
                cycleLaborByHousehold,
                receipt.consumedInputs(),
                cycleLaborByParticipant,
                session.sheet().productionRostersOrBase().get(id)));
        // ★★★ W5：把这一份 scale 记下来给铸币用（只有真的铸币的产业会在 MintSettlement 里被读；
        //   记全部关账 unit 是刻意的 —— 判据"这个 unit 铸不铸币"只在一个地方回答：IndustryDefinitionView.moneyOutputPerUnit
        // 是否为空）。
        if (!industry.moneyOutputPerUnit().isEmpty()) {
          closedUnitScales.put(id, closedScale);
        }
        // ★★ **H5：关账的 unit 先记下来 —— 4d 的报缺口等所有救济通道走完再算**（W3 起不再判死）。
        closed.add(
            new ClosedUnit(
                id,
                industry.id(),
                keys,
                inputShortfallOf(unit, industry, settlementIndex, operatorConditions.get(id))));
        // ★★ P10.2：周期劳动/投入在 unit.cycleState 清零前抓（清零现在发生在 production 的 `execute` 里，
        //   故这里读的是**回执**带回来的本周期事实）。
        closedFacts.add(
            new EnterpriseProfitBook.CloseFact(
                id,
                industry.id(),
                unit.operator(),
                hexOfIndustry(unit.industry()),
                unit.modeKey(),
                cycledLabor,
                nextInputUsed));
        anyCycleClosed = true;
        // ★★★ A5（2026-10-10）：本日关账的产业**产出运输服务**（= 它是跑商 unit）⇒ 记下来：
        //   它的账户主体手上"上一周期没卖出的"服务货在本日收获入账**之前**作废（不可储存转卖）。
        //   ★ 判据读配方（产出表含 haul），不另造开关；别的产业一个字节都不动。
        if (industry.recipe().outputPerUnit().containsKey(HaulService.HAUL_COMMODITY)) {
          haulExpiryUnits.add(unit);
        }
      }
    }
    // ★★ R2：收获/关系分账按 hex 并行（唯一写口仍是 applyTransfer；转移在分区累加器里铸号，协调器按分区序吸收）。
    // ★★ P2-A §13.3：收获阶段的两份只读派生索引 —— unit → ProductionEnterprise（组织者家户解析）与
    //   旧 CohortKey 视图 → 家户（旧 ToCohort 规则的唯一合法读法）。
    Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess =
        enterprisesByProcess(session.sheet().productionOrganizations());
    Map<CohortKey, HouseholdId> viewIndex = viewToHousehold(householdEconomies);
    // ── 3a'. ★★★ A5（2026-10-10）+ A7：周期边界作废上一周期未卖出的运输服务（用户裁定「不可储存转卖」）────
    //   ★ 位置是决定性的：必须在**本周期新产出被记入货物账之前**（下面 harvestPartitioned）—— 否则刚产出的那一份
    //     会被当成"上一周期的存货"一并作废（那是把新货当陈货，方向上正好反了）。
    //   ★★ A7 扩域：作用域 = **任何持有 haul 的家户**（不再是"关账 unit 的账户主体家户"）—— A6 实测的逃逸口正是
    //     "服务货经实物还债转移到已退出商家名册的持货户"（B 世界 day 240 的 5,249 毫，跨 day 360/480 两次边界未作废）。
    //     别的商品一个字节都不动 ⇒ "只影响 haul"仍然结构性成立（读写的商品键恒为 HAUL_COMMODITY）。
    //   ★ 落点 = 既有非换手损耗唯一写口 consumeForLoss（账户减 + 损耗账加在同一句话里做完，
    //     账 = HaulService.SERVICE_EXPIRED_ACCOUNT 具名项）⇒ Σ余额 + losses 守恒（I-H2，不静默消失）。
    haulServiceExpiredMilli = expireUnservedHaulService(day, haulExpiryUnits, accounts, ledger);
    harvestPartitioned(
        harvestWorks,
        householdEconomies,
        settlementIndex,
        relations,
        enterpriseByProcess,
        viewIndex,
        householdOfActor,
        accounts,
        income,
        ledger,
        issuanceJournal,
        day,
        parallelism,
        assetShares);
    // ── ★★★ W5（2026-10-11）：铸币生产方式的收获 ────────────────────────────────────────────────────
    //   ★ 位置 = **产业关账之后**（`harvestPartitioned` 已把本轮的净产计进各主体账）：
    //     铸币读的是关账处求好的 scale（与粮/布同一次 evaluateHarvest 的产物），因此"投入真实消耗"与
    //     "产出 = 货币"是同一次关账的两面，不可能漂开。
    //   ★ 它写三件事实（三者同一次调用里做完，见 MintSettlement.credit）：
    //     ① 国库家户账户余额（货币产出走账户余额，与商品产出走货物账同一条"产出计入组织者"的口径）
    //     ② MoneyIssuanceRecord（kind = MINT_PRODUCTION，id 由 (政府, 日, 币种) 确定性派生）
    //     ③ 同一本 debtContracts 的一条合同（货币 = 发行政府的债务）
    //   ★ "本轮能新投入多少 ≤ 上一轮印了多少"的闸门也在那里（从 MoneyIssuanceRecord 现算，判据 ③）。
    //   ★ 没关账 / 没铸币产业 / 旋钮 0 ⇒ 空表 ⇒ 一个字节都不动（判据 ②：规模 0 ⇒ 新钱 0 毫）。
    if (!closedUnitScales.isEmpty()) {
      MintSettlement.settleCycleClose(
          dayView, session, accounts, day, currentCycle, closedUnitScales);
    }
    if (!harvestWorks.isEmpty()) {
      ProductionLedger harvestLedger = ledger.toLedger();
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "HARVEST",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "closedUnits",
                  harvestWorks.size(),
                  "gross",
                  traceTotalByIndustry(harvestLedger.gross()),
                  "losses",
                  traceTotalByIndustry(harvestLedger.losses()),
                  "inputs",
                  traceTotalByIndustry(harvestLedger.inputs()),
                  "outputAccruals",
                  harvestLedger.outputAccruals().size(),
                  "transfers",
                  harvestLedger.transfers().size(),
                  "ruleSettlements",
                  harvestLedger.ruleSettlements().size()));
      if (TRACE.isDebugEnabled()) {
        for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry :
            harvestLedger.gross().entrySet()) {
          IndustryId industry = entry.getKey();
          EventLog.channel(TRACE)
              .debug(
                  LogEvent.of(
                      "HARVEST_BY_INDUSTRY",
                      EconomyLogSource.ECONOMY_SETTLEMENT,
                      "day",
                      day,
                      "industry",
                      industry.value(),
                      "gross",
                      entry.getValue(),
                      "losses",
                      harvestLedger.losses().getOrDefault(industry, Map.of()),
                      "inputs",
                      harvestLedger.inputs().getOrDefault(industry, Map.of())));
        }
      }
      // ★★ A1（2026-10-10）：**运输服务产出**的 INFO/DEBUG（AGENTS §一.9；口径见 logHaulServiceOutput 的类注）。
      //   ★★★ A5：返回值 = 本日运输服务的**净产**（毫服务）—— 当日对账 INFO 的"产出"一栏（产出 − 交付 − 作废
      //     就是当期留下的服务货，三者必须能对上）。
      haulServiceProducedMilli = logHaulServiceOutput(day, harvestLedger, dayView);
    }

    // ── ★★★ W3（D-032 D3 §6/§7）：工资直付 → 抵物 → 余款成债 ────────────────────────────────
    //   ★ 位置钉死：`driveProductionDay` 与关账/收获/铸币之后、市场轮/还债之前。due 只读本 tick
    //     ProductionReceipt 的实际劳动；工资债在本日还款轮被 `sameDayWageDebtIds` 排除，次日才可还。
    //   ★ 账户权威 = AccountSession 快照；库存权威 = D4 的 WagePayableInventoryLookup（一次/付款人组）。
    AccountSnapshot wageAccountSnapshot = accounts.snapshot();
    WagePayableInventoryLookup wagePayableInventoryLookup =
        new WagePayableInventoryLookupImpl(
            dayView.inventoryReservations(), day, () -> Optional.of(wageAccountSnapshot));
    // ★★★ W5-A（缺陷：W3 用 tick 前 unit 快照）：这里**必须**读当刻权威候选态，不得传日结算入口抓的 `units`。
    //   本 tick 更早的阶段（进入执行 / 自动组织 / 模式变迁 / 迁移搬迁）会经契约口 `createInstance` 新建实例；
    //   它们的 ProductionReceipt 就在 `productionDay` 里。旧快照看不见那些实例 ⇒ collectObligations 会把
    //   "回执 unit 不在表里" 误报成 `wage-posting-unit-unknown`（或跳过本应支付的工资义务）。
    //   ★ 只换数据来源：W3 的支付/抵物/债公式与调用参数一律不动。
    Map<ProductionUnitId, ProductionProcess> wageSettlementUnits = session.sheet().units();
    WageSettlement.Result wageSettlement =
        WageSettlement.settle(
            day,
            dueCycle,
            dayView,
            wageSettlementUnits,
            wageAccountSnapshot,
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            householdOfActor,
            debts,
            ledger,
            productionDay,
            topology,
            wagePayableInventoryLookup);
    var wageCarries = session.sheet().wageCarryRemainders();
    wageCarries.clear();
    wageCarries.putAll(wageSettlement.carryRemainders());
    Set<DebtContractId> sameDayWageDebtIds = new LinkedHashSet<>(wageSettlement.newWageDebtIds());
    // ★★★ D-032 W4：把 W3 已算出的读数原样投递给 app 只读面（不重算、不写状态；同日 tick 才可信）。
    WageSettlementReadoutFeed.publish(meta.mapId(), day, wageSettlement.readouts());
    if (TRACE.isTraceEnabled() && !wageSettlement.readouts().isEmpty()) {
      for (WageSettlementReadout readout : wageSettlement.readouts()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "WAGE_SETTLEMENT_READOUT",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "unit",
                    readout.unit().value(),
                    "role",
                    readout.role().value(),
                    "worker",
                    readout.worker().value(),
                    "payer",
                    readout.payer().value(),
                    "dueMilli",
                    readout.dueMilli(),
                    "paidMoneyMilli",
                    readout.paidMoneyMilli(),
                    "paidBarterValueMilli",
                    readout.barterPaidValueMilli(),
                    "arrearsMilli",
                    readout.arrearsMilli(),
                    "debtContractId",
                    readout.debtContractId().map(DebtContractId::value).orElse("-")));
      }
    }

    // ── 3b. ★★ E4c：欠租/欠薪资本化（生产/租金阶段之后）─────────────────────────────────────
    //   ★★ 只对**本日 ledger 的 Arrear 读数**（owed > 0）执行：把"制度规定未付"落成连续合同债权；
    //     **不移动任何商品/货币库存**（欠款本来就是未付），也**不清零 Arrear 读数**（读数仍是制度事实）。
    //   ★★ P2：端点解析改走 {@link DebtPartyResolver}（唯一解析点）：家户 actor 直取；聚合主体先查
    //     E2 生产组织，再按 ESTATE/WORKSHOP 的人口成分回退；多户按人口最大余数拆分。解析不到 ⇒ 具名跳过（不伪造）。
    //   ★ 位置在**借粮/偿还之前**：新增的既有债因此同日进入偿还排序（D-031 起不再进入任何借款额度门）；
    //     但不在当日起始本金快照里 ⇒ 当天不计息（与借粮同口径，见 principalAtDayStart）。
    capitalizeArrears(
        dayView,
        settlementIndex,
        ledger,
        householdEconomies,
        debts,
        capitalizedArrearsToday,
        day,
        dueCycle,
        sameDayWageDebtIds);
    ProductionLedger capitalized = ledger.toLedger();
    if (!capitalized.debtCapitalizations().isEmpty()
        || !capitalized.unresolvedDebtCapitalizations().isEmpty()) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "CAPITALIZE_ARREARS",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  day,
                  "capitalized",
                  capitalized.debtCapitalizations().size(),
                  "unresolved",
                  capitalized.unresolvedDebtCapitalizations().size()));
    }
    if (TRACE.isDebugEnabled()) {
      if (!capitalized.debtCapitalizations().isEmpty()
          || !capitalized.unresolvedDebtCapitalizations().isEmpty()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CAPITALIZE_ARREARS_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "capitalized",
                    capitalized.debtCapitalizations().size(),
                    "unresolved",
                    capitalized.unresolvedDebtCapitalizations().size()));
      }
    }

    // ── 4. 区域市场清算（M2.3/M2.4：每 5 天一轮 + 低库存追加轮；区内即时 / 跨区 ETA）────────────
    //   ★★ 调度只依赖**绝对世界日 + 当前状态**（M0.1）：两条推进路径在同一天必然同轮。
    //   ★ 它读**当刻**市场表（价格是数据）：缺格的格没有市场 ⇒ 不触发（不造默认价）。
    //     ★ B2：本段的市场表来自会话工作副本（见上面的 `markets` 局部量），不是段首 base。
    //   ★★ 买卖**只走唯一的 applier**（applyTransfer）：本步绝不直接改副本 ——
    //     "任何库存变动必有对应转移记录"这条不变量的落点因此仍是一处（见 MarketSettlement 的类注）。
    //   ★★ M2.1 起这一支不再按"本周期缺口/余量"就地配对，而是**主体各自生成订单**（家户 + 经营者，见
    //     {@link MarketSettlement} 的类注）：生活保留 = 5 天撮合间隔 + 30 天安全库存，旧 1000‰ 整周期自留已退休。
    //   ★★ M2 的冻结写者从这里接上：挂单冻结 → 成交/发运/轮末释放（冻结表随后原样带出，净额必然回到基值）。
    //   ★★★ W5.5（2026-10-11，S-12②）：**货币腿在撮合之前**（挂商品 → 挂钱 → 借钱 → 买货）——
    //     见 {@code MarketSettlement} 的 1d 段与 {@code MoneyMarketSettlement}；借粮腿已整段退役（S-12①）。
    MarketSettlement.MarketRound marketRound =
        new MarketSettlement.MarketRound(
            day,
            householdEconomies,
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            unmetToday,
            householdOfActor,
            industries,
            units,
            assetShares,
            relations,
            laborCommitments,
            shipments,
            ledger,
            operatorConditions,
            settlementIndex,
            // ★★ R4-E2：当日有效需求来自状态组件的只读账本（订单路径据此把"生活保留基线 + 需求目标"合成买卖目标）。
            // ★ B2-保留段首base：`demands` 无工作副本（写入口只有命令面）⇒ 段内不变。
            base.demands(),
            // ★★ P-T1c：生产路径默认区内市场规则（单区锚格 = markets 规范序第一个 hex；无税费 ⇒ 逐值现状）。
            //   跨市场区/自定义税费由后续批次经 GM 命令面注入同一入口。
            MarketRegulation.defaultsFor(markets, base.marketZones()),
            // ★★ R1：本入口不预置债务记账能力（开市判定后由 withDeferredPayments 补）；排除集 = **只有**组合根传入的单位户
            //   （国库户不再并入：它现在是"只按授权下单"的市场参与者）。
            null,
            null,
            effectiveMarketExcludedHouseholds);
    // ★★ M-A1：逐 hex 运力池（派生量，一轮一份、不落状态）—— 成员 = 该格"选了跑商"的家户（主业或副业含 merchant
    //   生产方式的位置，J-A）；运力 = f(劳动投入, 工具可投入量)（见 MerchantCapacity）。
    //   ★ 位置 = 生产/欠租阶段之后、市场装配之前：它必须在撮合时可用，且必须读**当刻**的劳动与商品账（每轮重算、不累积）。
    //   ★ 工具维读会话商品账（tool 商品；V-22：不动 AssetKind.SMALL_TOOL 产权份额）。
    // ★★★ A3（2026-10-10）：原来的"运力报价表"（{@code CapacityQuote}/{@code CapacityQuoteBook}，逐户自报价簿）
    //   **已整族退役** —— 限价改为**纯派生量**（{@code MerchantCapacityPool.askPerMilleOf}：派生承运成本 × 规模档
    //   + 具名固定"上门"附加费），服务成市 lane 上的钱由**市场牌价**定（{@code MarketTable.prices[haul]}，见 A2）。
    //   ⇒ 生产路径传 {@code priced = true}（第 6 参）；夹具 / 纯状态读者传 {@code false} ⇒ 逐值退回 M-A1（I-C2）。
    //   ★★ 2026-10-10 G3-fix-1 + G3-fix-2：工具维必须读**可用量**（现货 − 冻结），且必须在**承运选择点**现读 ——
    //     与提交侧的实扣判据（{@link #consumeForLoss}：可用量 < 一趟 ⇒ 一点也不烧）同口径、同活表。
    //     ★★ 时点（这里曾被写错、并因此让修复空转了一整轮）：本处装配在市场轮**之前**，而本轮的卖单冻结由
    //       {@code MarketSettlement.commitFreezes} 在市场轮**内**才落表 ⇒ 在装配点读冻结，减项**结构上不可能**
    //       含本轮承诺（G3b 复验：真实世界 `TOOL_SHORT_AT_COMMIT` 3,589 条逐值未变、708 组连运费腿一起成立）。
    //       ⇒ 口径落在 {@link MerchantCapacityPool#select}：它拿到的 goods/frozenGoods 是**活视图**
    //         （{@code accounts.householdGoods()/householdFrozenGoods()}，与市场轮写的是同一张表），
    //         在每条承运被判定的那一刻现读 {@code max(0, 现货 − 冻结)}。
    //     ★ 本处仍把活视图传进池（第 4/5 参 = 活视图的来源）；逐户装配读数（{@code MERCHANT_CAPACITY_HOUSEHOLD}，
    //       toolMilli = 装配时的可用量）由紧接着的那一行发出（见下）。
    // ★★★ A5（2026-10-10）：本日"服务成市"的格集**只算一次**，同一份值同时喂 ① 运力池的装配（下面第 7 参）
    //   ② 当日对账 INFO 一行的"要不要刷"判据（见 4 段末）—— 一处拼写、两处用它，不可能漂开。
    Set<HexCoord> haulServiceMarketHexes = haulServiceHexes(dayView);
    MerchantCapacityPool carrierPool =
        MerchantCapacityPool.of(
            session.sheet().classMemberships(),
            // ★ B2：阶层位置模板（无工作副本 ⇒ 段内不变）也统一走当日视图 —— 逐日读者只认一个"当刻值"来源。
            dayView.classPositions(),
            householdEconomies,
            householdGoods,
            householdFrozenGoods,
            true,
            // ★★ A2（2026-10-10）：**服务成市的格**（本格市场给 haul 定过价；唯一判据 = HaulService.pricedAt）。
            //   这些格的运力预算改成"该户手上的运输服务货"（服务商品账，I-H2）；其余格一个判据都不变（I-H3）。
            //   ★ 逐轮现算（价格是 GM 数据，可随时改）：不落任何状态、不进任何组件。
            haulServiceMarketHexes,
            // ★★ W7-fix3（2026-10-11）：把**本轮的世界日**交给池 —— {@code select} 的
            //   MERCHANT_CAPACITY_LANE_TRUNCATED 一行据此补上 `day`（同族三条 INFO/DEBUG 都带日）。
            //   ★ 与下面 MarketRound 的 `day` **同源**（本方法同一个 `day`）⇒ 池的 lane 读数与轮级
            //     汇总读数不可能按不同的日对齐。★ 只被日志读：不改任何判据、不落状态。
            day);
    // ★★ G3-leftovers（2026-10-10）：逐户装配读数（DEBUG；事件 MERCHANT_CAPACITY_HOUSEHOLD）。★ 由 tick 面的本处发
    //   （与下面那条 CAPACITY_PRICING_ASSEMBLY 同位；池只把日**记下来**供 {@code select} 的 lane 读数用，本事件的
    //   发射点不动）⇒ 该事件必须带 `day` 才能与同日的 MARKET_* 逐户按日对齐。
    carrierPool.logHouseholdAssembly(day);
    // ★★★ A3（2026-10-10）：原来这条 DEBUG 叫 {@code CAPACITY_QUOTE_BOOK}（报"报价表挂了多少户"）——
    //   自报价簿已整族退役（{@code CapacityQuote}/{@code CapacityQuoteBook}）⇒ 改名
    //   {@code CAPACITY_PRICING_ASSEMBLY}，报"限价是怎么派生的"（不再有逐户挂单这回事），并带上退役族/取代者的
    //   具名行（§一.9 的 INFO/DEBUG 口径）。分类 logger = market（与池内运力事件同一门面）。
    if (MANDATE.isDebugEnabled()) {
      EventLog.channel(MANDATE)
          .debug(
              LogEvent.of(
                  "CAPACITY_PRICING_ASSEMBLY",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "priced",
                  carrierPool.isPriced(),
                  "households",
                  carrierPool.householdCount(),
                  "maxAskPerMille",
                  carrierPool.maxAskPerMille(),
                  "getReadySurchargePerMille",
                  MerchantCapacityPool.GET_READY_SURCHARGE_PER_MILLE,
                  "pricing",
                  "derived-carrier-cost-per-tier+flat-get-ready",
                  "capacityBudgetMilli",
                  carrierPool.totalCapacityMilli(),
                  "retiredFamilies",
                  MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_FAMILIES,
                  "replacedBy",
                  MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_REPLACEMENTS));
    }
    MarketTrigger marketTrigger =
        MarketSettlement.triggerFor(day, anyCycleClosed, markets, topology, marketRound);
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "MARKET_SCHEDULE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "trigger",
                  marketTrigger,
                  "anyCycleClosed",
                  anyCycleClosed,
                  "markets",
                  markets.size()));
    }
    // ★★ R1：本轮政府授权挂单的成交累计（市场未触发 ⇒ 空表）；只用于把 filledMilli 累加回授权行。
    Map<MarketMandateId, Long> govMandateFills = Map.of();
    if (marketTrigger != MarketTrigger.NONE) {
      // ★★ D-031：借款人侧不再有信用额度上限 —— 市场信用只带到期周期与当日债务工作副本；唯一上限 = 放贷人
      //   实际可借的货币/卖单剩余。`DebtCapacity` 不再是任何借出路径的门。
      marketRound = marketRound.withDeferredPayments(dueCycle, debts);
      // ★★ 2026-10-08（阶段 1）：把排序产出的套利决定注入本轮市场（订单生成的输入；不进状态）。
      //   ★ 位置 = 市场轮之前（设计书 §9 的冻结落点）：它必须在撮合之前（它的输出就是订单），且依赖"到货已结算"
      //     （:1240 的 deliverShipments 先于它）。
      MarketArbitragePlan arbitragePlan = arbitrageCollector.toPlan();
      marketRound = marketRound.withArbitrage(arbitragePlan);
      logArbitrageRound(day, arbitragePlan);
      // ★★ A2a（阶段 2）+ B4（2026-10-08）：把"这个世界有哪些政府外汇窗口、储备上限多少"带进本轮 ——
      //   唯一来源 = EconomyData.governments()（GOV 级报价是状态）+ marketZones（B2 的区级覆盖）+ moneyIssuances
      //   （累计发行量 ⇒ 储备上限）。★ B4 起报价口径 = 区级优先、按币对回落该区发行 GOV 的 GOV 级报价。
      //   ★ 一条生效报价都没有 ⇒ FxRoundInput.none() ⇒ 只有"没有政府窗口"这一件事；★ P-T5 起家户民间簿不依赖它
      //     （逐格逐户按 F-1 购买力自报价），本段仍会跑 —— 缺省中性（单币世界 / 无价可比）由 FxSettlement 自己守。
      //   ★★ B2：三张表全部走**当日视图** —— 政府表与发行审计表都是会话工作副本（当日发行腿会追加记录，
      //     读段首 base 会把"今天的储备上限"按段首累计发行量算），市场区表无工作副本（段内不变）。
      FxRoundInput fxInput =
          FxRoundInput.of(dayView.governments(), dayView.moneyIssuances(), dayView.marketZones());
      marketRound = marketRound.withFx(fxInput);
      logFxWindows(day, dayView, fxInput);
      // ★★★ A3（2026-10-10）：此处原有"注入本轮跑商家户集合"（§16.4 ①，提交 b22da5b7，
      //   {@code marketRound.withMerchantHouseholds(MerchantIdentity.merchants(...))}）—— **已随
      //   {@code MarketRound.merchantHouseholds} 字段一并撤回**：它只服务 {@code necessaryInputsOf} 里
      //   "给跑商家户下夹一趟工具"那段特例，而该特例已删（预留改由产业声明的投入经标准循环覆盖，I-H6）。
      //   ★ 撤回理由与三支直证见 {@code MarketSettlement.necessaryInputsOf} 尾部的注（不再需要"范围"这个概念）。
      // ★★ R1（2026-10-09）：政府市场授权计划 = 本日生效的"明确挂单"（逐轮瞬态，不落盘）。
      //   ★ 它必须**最后**注入：withDeferredPayments/withArbitrage/withFx 三处各自逐字段带过它（克隆丢字段是本类踩过的坑），
      //     而这里注入之后不再有别的 withX。
      //   ★★ 名单上**所有**政府的国库户都在里面（哪怕今天没有授权）：它们是"只按授权下单"的家户，
      //     自动买卖单、市场信用放贷、家户外汇单全部不生成。
      //   ★★ 读**会话工作副本**（不是 base）：一次 advance 的多日共用同一个会话，授权在本段内被耗尽/清除必须
      //     当日就对后续各日生效（读 base 会把已清掉的行再挂一遍 —— 探针实测踩到并已修）。
      GovernmentMarketMandatePlan govMandatePlan =
          GovernmentMarketMandatePlan.of(
              session.sheet().govMarketMandatesOrBase(), dayView.governments(), day);
      // ★★ R2：口岸实际管制力随授权计划一起注入（各自逐字段带过 ⇒ 两个字段都在；withPortEnforcement 不覆盖 govMandates）。
      //   ★★ P-T1b：三层税的税率与收税政府同样在这里注入（`withPortTax` 逐字段带过前四个字段）——
      //     闸（E）与税（税率）是两个量，缺省各管各的，合成一个字段会让"只设了税"静默丢掉。
      marketRound =
          marketRound
              .withGovMandates(govMandatePlan)
              .withPortEnforcement(portEnforcement)
              .withPortTax(portTax)
              // ★★ P-T1d：政府采购优先级（谁要求管控市场 + 各自的行政力池）随授权计划一起注入 ——
              //   `withProcurementPriority` 逐字段带过前五个字段（丢了它 = 置顶整段不生效且毫无报错）。
              .withProcurementPriority(procurementPriority);
      // ★★ P-T1d 防复发守卫：与 govMandates / portTax 同一条（丢了它 = 政府要求管控却一户也置不了顶，契约故障）。
      //   契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (procurementPriority.isActive()
          && marketRound.procurementPriority() != procurementPriority) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "MARKET_PROCUREMENT_PRIORITY_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "reason",
                    "procurement-priority-input-lost-by-with-chain",
                    "expectedGovernments",
                    procurementPriority.governmentCount(),
                    "actualGovernments",
                    marketRound.procurementPriority().governmentCount()));
        throw new IllegalStateException("政府采购优先级的注入表在本轮装配里被丢掉了（置顶不会生效，契约故障）: day=" + day);
      }
      // ★★ P-T1b 防复发守卫：与 govMandates 同一条（丢了税 = 三层税一分不收且毫无报错）。
      //   契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (portTax.isActive() && marketRound.portTax() != portTax) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "MARKET_PORT_TAX_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "reason",
                    "port-tax-input-lost-by-with-chain",
                    "expectedZones",
                    portTax.zoneCount(),
                    "actualZones",
                    marketRound.portTax().zoneCount()));
        throw new IllegalStateException("三层税的注入表在本轮装配里被丢掉了（税会一分不收，契约故障）: day=" + day);
      }
      if (fxInput.isActive() && TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MARKET_FX_WINDOWS",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    day,
                    "windows",
                    fxInput.windows().size()));
      }
      // ★★ R1 防复发守卫：**本轮注入的授权计划必须真的在 round 上**。withDeferredPayments/withArbitrage/withFx 三处各自
      //   逐字段带过它（"克隆丢字段"是本类与 MarketRound 都踩过的坑：丢了它 = 国库户当场退回"自动清仓"且毫无报错）。
      //   ★ 契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (!marketRound.govMandates().equals(govMandatePlan)) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "reason",
                    "authorization-plan-lost-by-with-chain",
                    "expectedHouseholds",
                    govMandatePlan.authorizationOnlyHouseholds(),
                    "actualHouseholds",
                    marketRound.govMandates().authorizationOnlyHouseholds()));
        throw new IllegalStateException("政府市场授权计划在本轮装配里被丢掉了（国库户会退回自动下单，契约故障）: day=" + day);
      }
      // ★★ M-C：本轮的纯商号集合（H-2；免运费判据 H-A/H-G 的**范围**）—— 判据的唯一拼写点是
      //   {@code MerchantIdentity}，这里只把它从同一份 classMemberships × classPositions 现算一次。
      Set<HouseholdId> pureMerchantHouseholds =
          MerchantIdentity.pureMerchants(
              session.sheet().classMemberships(), dayView.classPositions());
      // ★★★ W4（2026-10-11，S-4 / N-1）：**开盘前结算序快照** —— 家户按"资产 − 负债净值"、区按"上周期名义成交额"。
      //   ★ 位置（禁自指的全部理由）：它必须在 `clearOncePerCycle` **之前**构造一次 —— 快照一落，本轮撮合/改价/记账
      //     一个字节都进不了排序量（"把当日成交额翻倍 ⇒ 排序不变"由结构保证，不靠纪律）；
      //   ★ 区序的量取自 W1 统计簿的 `previousCycle`（上周期累计，关账轮轮转）→ 同值退 `cycleToDate` → 再退区 id；
      //     两者都在**盘前**读 ⇒ 不含当轮。★ 家户净值与 {@code ClassSlideSettlement} 的债务/资产**同一把尺**
      //     （同一份快照，不另算第二套），见 {@link SettlementOrderBook}。
      SettlementOrderBook ordering =
          SettlementOrderBook.snapshot(
              day, topology, markets, householdEconomies, debts, accounts, dayView);
      MarketSettlement.MarketOutcome outcome =
          MarketSettlement.clearOncePerCycle(
              markets,
              marketRound,
              marketTrigger,
              topology,
              parallelism,
              carrierPool,
              pureMerchantHouseholds,
              // ★★ W2：本轮的持久市场区表（自适应定价逐区读步长 α = MarketZone.priceAlphaPerMille）。
              //   ★ B2 保留段首 base：α 是命令面组件，段内无工作副本（改 α ⇒ 下一段/下一轮生效）。
              //   ★★ W5.5：货币市场的利率/α **不在区表上**，在价表上（逐市场持久，派生形态的世界也有它）——
              //     它随 {@code outcome.marketTables()} 交回、由上面那段既有的价表写回落到会话工作副本。
              base.marketZones(),
              // ★★ W4：结算序快照（盘前构造、不可变；撮合期间不回读任何当轮量）。
              ordering);
      // ★ L2 只把报告留给 L3 的读数组件（不落盘）；不聚合丢失（见 MarketReport 的类注）。
      ledger.recordMarketReport(outcome.report());
      govMandateFills = outcome.mandateFills();
      MarketReport report = outcome.report();
      // ── 4a0d'. ★★★ A5（2026-10-10）：**每个开市日一行可对账 INFO**（产出 / 交付 / 作废 三个量）──────────
      //   ★ 它取代了市场轮里那条只在 {@code trades > 0} 时才刷的 HAUL_SERVICE_SETTLED（A4 账本 §5-2：B 世界
      //     43 个有交付的日子只刷了 33 行、少报 91,383 毫服务 = 33.4%）—— 事件名不变，位置改到"三个量的最全来源"
      //     处（产出与作废都发生在生产阶段，市场轮看不到它们；交付量经 {@link MarketSettlement.HaulServiceRound}
      //     随交还物带出来）。
      //   ★ 一行不刷的条件**只剩一个**：本格连一个"服务成市的格"都没有（缺省 / 无牌价世界 ⇒ 日志逐字不变，I-H3）。
      //     当天三量全为 0 的日子照刷 —— "这一天什么都没发生"本身就是审计要能读到的事实（旧行为正是丢掉了它）。
      //   ★ 三个量必须能对上：Σ净产 − Σ交付 − Σ作废 == 期末服务货现货（交付那一份同时进
      //     {@code market-haul-service}、作废那一份进 {@code market-haul-service-expired}，各自账户减、账加）。
      if (!haulServiceMarketHexes.isEmpty() && MANDATE.isInfoEnabled()) {
        MarketSettlement.HaulServiceRound haulService = outcome.haulService();
        EventLog.channel(MANDATE)
            .info(
                LogEvent.of(
                    "HAUL_SERVICE_SETTLED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    // ★ 产出/作废发生在生产阶段（本方法上半段），交付发生在市场轮 —— 三个量在同一行按日对齐。
                    "producedMilli",
                    haulServiceProducedMilli,
                    "deliveredMilli",
                    haulService.deliveredMilli(),
                    "expiredMilli",
                    haulServiceExpiredMilli,
                    // ★ trades = 铸了几条服务钱腿（免运费/自承运的交付不计）⇒ 与 deliveredMilli 是两个量，都在。
                    "trades",
                    haulService.trades(),
                    // ★ 逐币总额由逐户实收求和得到（同一批金额，不再另记一份；见 ctx 字段注）。
                    "paidByCurrency",
                    haulServicePaidByCurrency(haulService),
                    "providers",
                    carrierPool.householdCount(),
                    "deliveryFaults",
                    haulService.deliveryFaults(),
                    // ★ 缺省/无牌价世界一行不打（I-H3）；本行是"当日的三量对账行"，不是"成交事件行"。
                    "reason",
                    "haul-service-day-reconciliation-produced-delivered-expired"));
      }
      Map<MarketUnfilledReason, Long> reasonCounts = new TreeMap<>();
      // ★★ F-2（2026-10-10）：**"卡在运力"这一档的具名细分**（键 = MerchantCapacityPool.LogisticsBlock 的字面量）——
      //   改前"没有服务卖"与"运力不足"在读数里同为 LOGISTICS_CAPACITY；这里按**具名档**分列未成交量，
      //   让两者在公开读数面（本条 INFO/TRACE 的 MARKET 汇总）可分辨（§一.9：业务拒绝本该 INFO 可读）。
      //   ★ 保序 = LinkedHashMap（I7：键序是内容的纯函数，禁 Map.copyOf/无序表）。
      Map<String, Long> logisticsDetailCounts = new LinkedHashMap<>();
      for (MarketReport.Unfilled unfilled : report.unfilled()) {
        reasonCounts.merge(unfilled.reason(), 1L, Long::sum);
        if (unfilled.reason() == MarketUnfilledReason.LOGISTICS_CAPACITY
            && unfilled.logisticsDetail() != null) {
          logisticsDetailCounts.merge(unfilled.logisticsDetail(), unfilled.quantity(), Long::sum);
        }
      }
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "MARKET",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "trigger",
                  report.trigger(),
                  "fills",
                  report.fills().size(),
                  "immediateCrossHex",
                  report.immediateCrossHexFills(),
                  "immediateCrossHexLossMilli",
                  report.immediateCrossHexLossMilli(),
                  "scheduledLossMilli",
                  report.scheduledLossMilli(),
                  "unfilled",
                  report.unfilled().size(),
                  "reasons",
                  reasonCounts,
                  // ★★ F-2：LOGISTICS_CAPACITY 的具名细分（空表 = 本轮没有卡在运力的未成交；缺省中性）。
                  "logisticsDetailQuantities",
                  logisticsDetailCounts,
                  "regulatedTariffByCurrency",
                  report.regulatedTariffByCurrency(),
                  // ★★ P-T4：运费读数按币分列（禁跨币相加）—— 一并进"这一轮发生了什么"。
                  "freightPaidByCurrency",
                  report.freightPaidByCurrency(),
                  "freightUncollectedByCurrency",
                  report.freightUncollectedByCurrency(),
                  // ★★ P-T1b：三层税的真收总额（按币分列；= 买方多付的那一部分）。
                  "taxByCurrency",
                  report.taxByCurrency()));
      // ★★ P-T1b（§一.9 的 INFO 档）：**当日分层的税汇总** —— 每一行 = 一个（层 × 收款政府 × 币种）的实收金额。
      //   ★ 只在真的收了税时发（缺省 0 ⇒ 一行都不发，旧世界日志逐字不变）；禁跨币求和（I-C10）由键的第三段保证。
      logTaxCollected(day, report);
      if (RAW.isTraceEnabled()) {
        for (MarketReport.Fill fill : report.fills()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_FILL",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "from",
                      fill.from().q() + "," + fill.from().r(),
                      "to",
                      fill.to().q() + "," + fill.to().r(),
                      "commodity",
                      fill.commodity().value(),
                      "seller",
                      fill.seller().id(),
                      "buyer",
                      fill.buyer().id(),
                      "quantity",
                      fill.quantity(),
                      // ★★ P-T4：每一栏钱都带币种（读口不混币）—— 异币成交时"单价"与"实付"是两种钱。
                      "unitCurrency",
                      fill.unitCurrency().value(),
                      "unitPriceMilli",
                      fill.unitPriceMilli(),
                      "paymentCurrency",
                      fill.paymentCurrency().value(),
                      "goodsPaymentMilli",
                      fill.goodsPaymentMilli(),
                      "freightCurrency",
                      fill.freightCurrency().value(),
                      "freightPerUnitMilli",
                      fill.freightPerUnitMilli(),
                      "freightMilli",
                      fill.freightMilli(),
                      "immediate",
                      fill.immediate(),
                      "arrivalTick",
                      fill.arrivalTick(),
                      "shipmentId",
                      fill.shipmentId(),
                      "lossMilli",
                      fill.lossMilli()));
        }
        for (MarketReport.Unfilled unfilled : report.unfilled()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_UNFILLED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "side",
                      unfilled.buyerSide() ? "buy" : "sell",
                      "actor",
                      unfilled.actor().id(),
                      "commodity",
                      unfilled.commodity().value(),
                      "quantity",
                      unfilled.quantity(),
                      "reason",
                      unfilled.reason(),
                      "hex",
                      unfilled.hex().q() + "," + unfilled.hex().r()));
        }
        for (MarketReport.SellerOutcome seller : report.sellerOutcomes()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_SELLER_OUTCOME",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "actor",
                      seller.actor().id(),
                      "unit",
                      seller.unitId().map(ProductionUnitId::value).orElse("-"),
                      "hex",
                      seller.hex().q() + "," + seller.hex().r(),
                      "commodity",
                      seller.commodity().value(),
                      "offered",
                      seller.offeredQty(),
                      "filled",
                      seller.filledQty(),
                      "unfilled",
                      seller.unfilledQty(),
                      "unitPriceMilli",
                      seller.unitPriceMilli(),
                      "unitCostEstimateMilli",
                      seller.unitCostEstimateMilli(),
                      "freightPerUnitMilli",
                      seller.freightPerUnitMilli(),
                      "bestAcceptedLandedPriceMilli",
                      seller.bestAcceptedLandedPriceMilli(),
                      "costRank",
                      seller.costRank(),
                      "reason",
                      seller.unfilledReason().map(Enum::name).orElse("-"),
                      "outcompetedByActors",
                      seller.outcompetedByActorCount(),
                      "outcompetedQty",
                      seller.outcompetedQty(),
                      "priceMissing",
                      seller.priceMissing(),
                      "costKnown",
                      seller.costKnown()));
        }
        for (MarketReport.BuyerOutcome buyer : report.buyerOutcomes()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_BUYER_OUTCOME",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "actor",
                      buyer.actor().id(),
                      "household",
                      buyer.household().map(HouseholdId::value).orElse("-"),
                      "hex",
                      buyer.hex().q() + "," + buyer.hex().r(),
                      "commodity",
                      buyer.commodity().value(),
                      "stockOnHandMilli",
                      buyer.stockOnHandMilli(),
                      "stockCoverDays",
                      buyer.stockCoverDays(),
                      "gapQty",
                      buyer.gapQty(),
                      "desiredQty",
                      buyer.desiredQty(),
                      "spendableMoneyMilli",
                      buyer.spendableMoneyMilli(),
                      "affordableQty",
                      buyer.affordableQty(),
                      "orderedQty",
                      buyer.orderedQty(),
                      "filledQty",
                      buyer.filledQty(),
                      "reason",
                      buyer.unfilledReason().map(Enum::name).orElse("-")));
        }
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MARKET_REPORT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "fills",
                    report.fills().size(),
                    "immediateCrossHex",
                    report.immediateCrossHexFills(),
                    "immediateCrossHexLossMilli",
                    report.immediateCrossHexLossMilli(),
                    "scheduledLossMilli",
                    report.scheduledLossMilli(),
                    "unfilled",
                    report.unfilled().size(),
                    "reasons",
                    reasonCounts,
                    "regulatedTariffByCurrency",
                    report.regulatedTariffByCurrency(),
                    "freightPaidByCurrency",
                    report.freightPaidByCurrency(),
                    "freightUncollectedByCurrency",
                    report.freightUncollectedByCurrency()));
      }
      // ★★ S3 修复：把本轮的逐卖方证据累加进经营者条件的"本周期累计"字段。一个周期有多轮市场，关账日那轮
      //   很可能已经看不到更早轮里的滞销/被挤出 ⇒ 不在这里累加，状态机的连续计数就永远不涨。
      //   ★★★ A5（T-H3）：服务成交的钱腿也走这个口（服务不进订单簿 ⇒ 它不在 report.fills() 里）——
      //   见 {@code OperatorSettlement.accumulateHaulServiceRevenue}。
      OperatorSettlement.accumulateMarketEvidence(
          operatorConditions, units, industries, outcome.report(), outcome.haulService());
      // ★★ M2.6：自适应模式把价格表工作副本换成 outcome 交回的新表（默认固定模式下两者逐值相同 ⇒ 无状态变化）。
      //   ★★ 2026-10-07 修：`markets` 是 `session.sheet().marketTables()` 的**工作副本引用**，不能整个重新绑定 ——
      //     重新绑定只改本地变量，`EconomyStateBuilder` 仍持旧表 ⇒ 自适应价算出来却永远不落盘。
      //     `outcome.marketTables()` 在固定模式下就是同一实例；只有真的换了实例才 clear+putAll 回工作副本。
      Map<MarketZoneId, MarketTable> updatedMarkets = outcome.marketTables();
      if (updatedMarkets != markets) {
        markets.clear();
        markets.putAll(updatedMarkets);
      }
      // ★★ W1（2026-10-10）：**市场轮统计簿**——每轮把本轮读数折进状态（最近一轮明细 + 本周期累计 + 上周期累计）。
      //   ★ 窗口固定（三段覆盖写 + 关账轮轮转）⇒ 状态大小与推进天数无关（性能红线：Revision Δ 不随天数增长；
      //     本写入在**同一次 advance 的工作副本**里，不产生额外 revision）。
      //   ★ 关账轮的语义：本轮先计入"本周期累计"，若本轮同时是关账轮 ⇒ 再把本周期轮转成上周期（关账发生在市场轮之后）。
      if (!outcome.roundStats().isEmpty()) {
        Map<MarketZoneId, MarketRoundStats> statsByMarket = session.sheet().marketRoundStats();
        long recorded = 0L;
        long tradedTotal = 0L;
        long turnoverTotal = 0L;
        long cashTurnoverTotal = 0L;
        for (Map.Entry<MarketZoneId, MarketRoundStats> entry : outcome.roundStats().entrySet()) {
          MarketRoundStats round = entry.getValue();
          MarketRoundStats current =
              statsByMarket.getOrDefault(entry.getKey(), MarketRoundStats.EMPTY);
          // ★★ W2-fix7 / D4（v3.10 §7）：两栏都写 —— `turnover` = 名义货值（全额货款，含赊购）、
          //   `cashTurnover` = 实付现金（改前 `turnover` 的口径，逐值搬到新字段）。
          statsByMarket.put(
              entry.getKey(),
              current.withRound(
                  day,
                  round.lastRound(),
                  round.lastRoundTurnoverMilli(),
                  round.lastRoundCashTurnoverMilli(),
                  anyCycleClosed));
          recorded++;
          turnoverTotal = Math.addExact(turnoverTotal, round.lastRoundTurnoverMilli());
          cashTurnoverTotal = Math.addExact(cashTurnoverTotal, round.lastRoundCashTurnoverMilli());
          for (CommodityRound commodityRound : round.lastRound().values()) {
            tradedTotal = Math.addExact(tradedTotal, commodityRound.tradedMilli());
          }
        }
        EventLog.channel(MANDATE)
            .info(
                LogEvent.of(
                    "MARKET_ROUND_STATS_RECORDED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "markets",
                    recorded,
                    "commodities",
                    outcome.roundStats().values().stream()
                        .mapToInt(stats -> stats.lastRound().size())
                        .sum(),
                    "tradedMilli",
                    tradedTotal,
                    // ★ D4：两栏都给（名义货值 / 实付现金）—— 只打一栏会让读者以为"成交额 = 现金"
                    "turnoverMilli",
                    turnoverTotal,
                    "cashTurnoverMilli",
                    cashTurnoverTotal,
                    "cycleClosed",
                    anyCycleClosed,
                    "window",
                    "last-round+cycle-to-date+previous-cycle"));
        if (MANDATE.isDebugEnabled()) {
          // ★★ W1 收尾批（2026-10-11）：这一行打印的必须是**刚落盘的那份统计**（上文 `statsByMarket` 里
          //   `current.withRound(...)` 的返回值），**不是**本轮入参 `outcome.roundStats()` —— 入参只带本轮读数
          //   （`lastRound`），它的 `cycleToDate` / `previousCycle` 必然为空 ⇒ 旧写法让 `describe()` 的
          //   "本周期/上周期"两栏**恒 0**，与同 tick 落盘的 `marketRoundStats` 直接矛盾（验收报告 §7.2：
          //   day=360 的 DETAIL 写 `上周期商品=0`，而同 tick 的落盘原文里 `previousCycle` 有 5 商品、
          //   `previousCycleTurnoverMilli=5601`）—— 那是"字段名与所指对象不一致"的误导，读者会以为窗口是空的。
          //   ★ 只改**打印对象**：状态、公式、写口一字未动，级别仍是 DEBUG。
          for (Map.Entry<MarketZoneId, MarketRoundStats> entry : outcome.roundStats().entrySet()) {
            MarketRoundStats stored = statsByMarket.get(entry.getKey());
            EventLog.channel(MANDATE)
                .debug(
                    LogEvent.of(
                        "MARKET_ROUND_STATS_DETAIL",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        day,
                        "marketId",
                        entry.getKey().value(),
                        "stats",
                        // 写入就在上面同一个块里完成 ⇒ 正常情况下恒非空；真取不到也只如实写"未写入"，
                        // 不回落到本轮入参（那正是这条日志原先会误导人的地方）。
                        stored == null ? "未写入" : stored.describe()));
          }
        }
      }
      // ── ★★★ W4（2026-10-11，S-1）：**阶层滑落** —— 速度 = f(债务/资产)，结算点 = **市场轮最末** ────────────
      //   ★ 位置为什么是"这里"（用户原话「这个速度的结算必须在当前经济操作搞完之后最后再结算…当轮算出这个家户的
      //     债务规模后进行」）：上面这一段已经跑完 ① 撮合（本轮赊购 ⇒ 本轮的 debtContracts 已建）② 自适应价格写回
      //     ③ 统计簿落盘 ⇒ "当轮债务规模"与"本轮价"都已是事实；而 4b 借粮 / 4c 偿还 / 计息都在**下面** ——
      //     滑落读的是"市场轮结束时点"的债务/资产，不掺当天后续的借还（口径唯一、可复现）。
      //   ★ 逐级不跳级：{@link ClassSlideSettlement} 每次调用对同一户只写一次、只写一个"最近严格低位"。
      ClassSlideSettlement.SlideRound slideRound =
          ClassSlideSettlement.settleAtMarketRoundEnd(
              base, session.sheet(), day, householdEconomies, debts, accounts, topology, markets);
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CLASS_SLIDE_SETTLED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "trigger",
                    marketTrigger,
                    "evaluated",
                    slideRound.evaluated(),
                    "slid",
                    slideRound.slid(),
                    "skippedNoDebt",
                    slideRound.skippedNoDebt(),
                    "skippedNoPosition",
                    slideRound.skippedNoPosition(),
                    "skippedCooldown",
                    slideRound.skippedCooldown(),
                    "skippedNoLowerTarget",
                    slideRound.skippedNoLowerTarget(),
                    "unpricedDebtHouseholds",
                    slideRound.unpricedDebtHouseholds(),
                    "at",
                    "market-round-end-after-price-writeback"));
      }
    }

    // ── ★★ R1：政府市场授权的生命周期（成交累加 + 耗尽/到期清除）────────────────────────────────
    //   ★ 无条件调用（不只在开市日）：到期是**日历事实**，闭市/没有市场也不能让一条授权永远挂在表里。
    //   ★ 位置：市场之后（成交已落账）、债务/预算之前 —— 它只动授权表本身，不参与任何货/钱转移。
    applyGovMarketMandateLifecycle(session, day, govMandateFills);

    // ★★ D-030 §3.4/§3.5：本日起所有“债务折价/还款折算”共用同一份家户价目表索引 ——
    //   有本格市场用本格，否则回落该格所在市场区的锚格默认价目表（单区 = 该区默认价）。
    //   ★ 这是 EconomySettlement 里唯一构造 debt lookup 的地方；算式在本类之外（DebtValuation）。
    Map<HouseholdId, MarketTable> marketByHousehold =
        marketIndexByHousehold(householdEconomies, markets, topology);
    DebtCapacityBook.DebtUnitValueLookup debtUnitValueLookup =
        DebtCapacityBook.marketPriceLookup(marketByHousehold);

    // ── 4b. ★★★ W5.5（2026-10-11，S-12①）：这里原有**同格借粮腿**（{@code lendDeficitsPartitioned} /
    //   {@code lendDeficitsInHex} / {@code lendableOf} / {@code
    // LENDER_SUBSISTENCE_RESERVE_PER_MILLE} /
    //   {@code DEFICIT_LENDING} 日志）—— **整段退役**（AGENTS §一.11：旧设计直接删，不留兼容位）。
    //   ★ 用户原话：「**把借粮这条路给取缔掉，这条路才是真的不知道哪里冒出来的**」—— 它**不是**用户裁定：
    //     它来自 2026-09-25 那批聚合经济引擎计划，代码注释里引用的「D-031（2026-10-04 用户裁定）」在用户设计原稿
    //     （`2026-10-02-architecture-design-source.md`，只到 D-030）里**不存在** ⇒ 该腿连同它的自造编号一并退役
    //     （约束设计书 §7 v3.19 已留痕；§五.5：自造的规矩不得冒充用户裁定）。
    //   ★ 取代它的是**货币腿**：钱在**市场轮内**（挂商品 → 挂钱 → 借钱 → 买货）由放贷人以真钱出借，
    //     借到的钱当天就能买货（{@code MarketSettlement} 的 1d 段 → {@code MoneyMarketSettlement}）；
    //     借不到的那部分仍由买货时的「未付价款 ⇒ 对卖方的债务」兜底（S-12⑦，同一本 {@code debtContracts}）。
    //   ★ 这里不再有那支"先再吃一口"：它服务的正是借粮腿（"关账日买到粮就不该再借"），而买货已在市场轮里
    //     发生、借钱发生在**买货之前** ⇒ 该分支没有存在的理由（它不再搬粮，也不再动 consumed/unmet 读数）。

    // ── 4c. 偿还（★ P11.1 / D-023 / D-030：所得到账后、计息前，“有啥付啥”的任意介质偿还）────────────
    //   ★★ 它挂在"今天有产业关账"上（利息／市场的同一处日级事实）：所得是**整周期**结算出来的
    //     （收获与分配在本步之前刚发生）⇒ 还债的时点就是所得到手的那一刻。
    //   ★★ v3.6（2026-10-11 用户第六批裁定）：**可动用资产 = 家户手头库存（含货币）—— 不扣任何保留量**；
    //     冻结不越；介质序 = S-5 的"预估价值最低优先"（见 {@link #repaymentMediumOrder}）；
    //     债序 = 折成共同价值升序（不可定价排最后）；估值 = 家户价目表（可选）→ 该户市场区默认价目表。
    //     ★ 用户原话：「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」⇒ D-030 §4 的"一日口粮保留"
    //       与 v3.5 的"35 天窗口"**一并作废**（见约束设计书 v3.6/v3.7 §4.1 的"还债保全"行）。
    //     ★ 唯一保留的"前瞻扣除"在**卖侧**（用户 0–8 第 5 条：投入市场的商品 = 扣除下一 tick 生产需要后的）。
    //   ★ 本批没有家户价目表字段 ⇒ {@code householdPrices = null} 走市场默认；外部/政府收款口由
    //     {@link DebtValuation.RepayeeResolver#HOUSEHOLD_ACTORS} 起步（账户在会话/actor 账里可收即可）。
    if (anyCycleClosed) {
      // ★★★ W2（2026-10-11）：还款轮的两项口径来源（都在这里一次装配，逐户只读）——
      //   ① ★ **家户价目表**（{@link HouseholdValuationBook}，逐 tick 现算、不进状态）：改动前这里传 {@code null}
      //      ⇒ 估值一律回落"市场区默认价目表"，与用户 D-023 §2 的"先看家户自己的价目表"不一致（W1 账本 §3 记过）。
      //      传入之后，债务折价与 S-5 介质序都按**该户自己的保留价**估值（说不出价 ⇒ 0 ⇒ 排最前/具名 skip，不静默）。
      //   ② ★★ **没有保留量**（v3.6，用户第六批）：还债可用量 = 家户手头库存本身 ⇒ 本处不再装配
      //      "下轮生产花费"（{@code productionCostsByHousehold} 已随该口径一并删除，见 AGENTS §一.11：
      //      旧设计不留）。
      HouseholdValuationBook repaymentPrices =
          HouseholdValuationBook.derive(
              householdEconomies,
              repaymentSnapshots(
                  day,
                  householdEconomies,
                  householdGoods,
                  householdFrozenGoods,
                  householdMoney,
                  householdFrozenMoney,
                  marketByHousehold),
              HouseholdValuationBook.NO_TRADE_HISTORY);
      repayDebts(
          day,
          householdEconomies,
          debts,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          repaidToday,
          repaidMoneyToday,
          repaidPrincipalByDebt,
          householdOfActor,
          marketByHousehold,
          DebtValuation.RepayeeResolver.HOUSEHOLD_ACTORS,
          repaymentPrices,
          ledger,
          issuanceJournal,
          sameDayWageDebtIds);
      long repaidGrainTotal = traceTotalLongs(repaidToday);
      // ★★ P-T4：还款读数**按币分列**（禁跨币相加）—— 门槛仍与改前同真同假（还款腿只累加正额）。
      Map<String, Long> repaidMoneyByCurrency = traceMoneyByCurrency(repaidMoneyToday);
      int repaymentSkips = ledger.toLedger().debtRepaymentSkips().size();
      if (repaidGrainTotal > 0L || anyPositiveMoney(repaidMoneyToday) || repaymentSkips > 0) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "REPAY",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "households",
                    repaidToday.size(),
                    "repaidGrainMilli",
                    repaidGrainTotal,
                    "repaidMoneyByCurrency",
                    repaidMoneyByCurrency,
                    "skippedMediums",
                    repaymentSkips,
                    "debtContracts",
                    debts.size()));
      }
      if (TRACE.isDebugEnabled()) {
        // ★★ W2（§一.9：关键判据 DEBUG = 为什么）—— 还款轮的三条新口径逐条具名，供"为什么这样还"的现场判读。
        //   ★★ v3.6：`reserve` 由 "next-round-production-cost-including-rations" 改为 **none**（用户第六批裁定：
        //     有库存就还、没库存记债 ⇒ 保留量恒 0）。
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "REPAY_CRITERIA",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "valuation",
                    "household-valuation-book→market-zone-default",
                    "reserve",
                    "none",
                    "mediumOrder",
                    "ascending-estimated-unit-value",
                    "sharePerMille",
                    DEBT_REPAYMENT_SHARE_PER_MILLE,
                    "householdsWithRepayment",
                    repaidToday.size(),
                    "why",
                    "S5-medium-order-by-lowest-estimated-value + v3.6-no-reserve-window"
                        + " (stock-only: 有库存就还、没库存记债)"));
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "REPAY_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "households",
                    repaidToday.size(),
                    "repaidGrainMilli",
                    repaidGrainTotal,
                    "repaidMoneyByCurrency",
                    repaidMoneyByCurrency,
                    "skippedMediums",
                    repaymentSkips,
                    "debtContracts",
                    debts.size()));
      }
    }

    // ── 4d. 报缺口（★★ W3 2026-10-11：原"饿死判据 + 劳动缩放"整段已删 —— 经济侧**不判死、不缩劳动**）──────
    //   ★ 位置不变（★ H5：**所有救济通道之后** —— 市场（4）→ 借（4b）→ 还（4c）之后）：这里算出的缺口就是
    //     "救济全走完还差多少"，与旧判据同一个时点、同一个当日分子（`unmetToday[grain]`）。旧判据另外把
    //     本周期累计（`carried`）折进"饿满整周期"的比例里 —— 那条**周期口径随判死一起走了**：缺口现在按**当日**
    //     报（分子=当日缺口、分母=当日粮需求，同窗口），由 Social 自己决定怎么折成死亡率。
    //   ★ 报出去的形状见 {@link EconomyHouseholdGap}：① 生产花费未满足量（逐商品，取 W2 的市场读口，**不重算**）
    //     ② 口粮缺口那一支（当日）③ 家户稳定身份 + 当日人口。
    //   ★ 它是瞬态 outbox（不进 EconomyData/变更集/Codec），app 组合根在 step(day) 之后取走（S-11 第②半）。
    reportHouseholdGaps(
        session, householdEconomies, flows, unmetToday, newCycleHouseholds, ledger, day);
    // ★★ Z3a/C7：政府承诺整额保留后若越预算，这里统一具名 ERROR + fail-closed（有 day 上下文 ⇒ 来源 TICK、
    //   事件带 day；与 Z1b 队列口径同事件名）。★ W3 起它不再兼"死亡缩放的收口"——那条缩放已删（见上）。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION, day);

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    //   ★ 次序：**偿还先于计息**（还掉的那部分本金不再生息 —— 这是"先还后计"的标准序，也是改后债务曲线下降的一半原因）。
    if (anyCycleClosed) {
      chargeInterest(debts, principalAtDayStart, interestToday, householdEconomies, day);
      long interestTotal = traceTotalLongs(interestToday);
      if (interestTotal > 0L) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "INTEREST",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "households",
                    interestToday.size(),
                    "interestDueTotalMilli",
                    interestTotal,
                    "debtContracts",
                    debts.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "INTEREST_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "households",
                    interestToday.size(),
                    "interestDueTotalMilli",
                    interestTotal));
      }
    }

    // ── 5b. S3 经营者状态机（可观察量 → IndustryStatus；退出处置先偿债、不足才 defaulted）──────────
    //   ★ 只在关账日前进，而且只更新**本日关账**的产业：每个产业的连续计数以它自己的关账周期为单位，
    //     未关账产业的周期证据不能被别人关账时顺手消费掉。
    //   ★ 市场证据取的是**本周期累计**（每轮市场结束后累加到 OperatorCondition.cycle*），不再要求 evidence.day == day；
    //     若本周期一个市场轮都没有（cycleMarketRounds == 0），连续计数保持、不用 0 覆盖。
    //   ★ 缩产/停业只改"计划规模系数"（StressPolicy），capacity 与 OwnershipStake 一字不动。
    Set<ProductionUnitId> closingUnits = new LinkedHashSet<>();
    if (anyCycleClosed) {
      Map<ProductionUnitId, Boolean> shortfallByUnit = new LinkedHashMap<>();
      for (ClosedUnit closing : closed) {
        closingUnits.add(closing.unit());
        shortfallByUnit.put(closing.unit(), closing.inputShortfall());
      }
      SettlementIndex stateIndex = settlementIndex.withDebtContracts(debts);
      List<OperatorSettlement.Exit> exits =
          OperatorSettlement.advance(
              operatorConditions,
              units,
              industries,
              relations,
              householdEconomies,
              stateIndex,
              householdGoods,
              householdMoney,
              MarketIndex.of(dayView),
              closingUnits,
              shortfallByUnit);
      if (!exits.isEmpty()) {
        // ★★ E1：退出事实处置（劳动释放 → 资产退回/留 owner → 债务偿还/违约 → 状态与理由）。
        settleOperatorExits(
            exits,
            operatorConditions,
            session.sheet().productionOrganizations(),
            assetShares,
            laborCommitments,
            debts,
            householdGoods,
            householdMoney,
            householdOfActor,
            stateIndex,
            ledger,
            day,
            issuanceJournal);
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "OPERATOR_EXITS",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "exits",
                    exits.size(),
                    "households",
                    exits.stream().map(OperatorSettlement.Exit::household).distinct().count()));
        if (RAW.isTraceEnabled()) {
          for (OperatorSettlement.Exit exit : exits) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "OPERATOR_EXIT",
                        EconomyLogSource.ECONOMY_ORGANIZATION,
                        "day",
                        day,
                        "unit",
                        exit.unit().value(),
                        "industry",
                        exit.industry().value(),
                        "operator",
                        exit.operator().id(),
                        "household",
                        exit.household().value(),
                        "reason",
                        logReason(exit.reason())));
          }
        }
        if (TRACE.isDebugEnabled()) {
          EventLog.channel(TRACE)
              .debug(
                  LogEvent.of(
                      "OPERATOR_EXITS_DETAIL",
                      EconomyLogSource.ECONOMY_ORGANIZATION,
                      "day",
                      day,
                      "exits",
                      exits.size()));
        }
        // ★ 资产 operator / 劳动配额刚被改写 ⇒ 换一份索引，后面的阶层写回（读 unit 可用资产）不拿旧快照。
        //   这是**退出日的一次重建**（O(unit + 份额 + 配额)），不是逐查询重扫；退出本身是低频事件。
        settlementIndex =
            SettlementIndex.build(
                units,
                industries,
                assetShares,
                laborCommitments,
                householdEconomies,
                debts,
                relations,
                session.sheet().productionOrganizations(),
                session.sheet().assetOccupanciesOrBase(),
                session.sheet().productionRostersOrBase(),
                day);
      }
    }

    // ── 5b.5. ★★ E5b：清算 + 阶层下滑 + hex 危机信号（关账日、退出处置之后、阶层写回之前）────────
    //   ★ 位置理由：债务结算（4c 偿还 / 5 计息 / 5b 退出处置）已经结束 ⇒ 这里只对"此刻本金 > 0"的合同选路；
    //     5b 已结清的合同本金为 0，天然不会被二次处置（去重口径见 EconomyLiquidationSettlement 类注）。
    //   ★ 只在关账日推进（与 5 计息、5c 阶层写回同窗口）；新表全空 = 旧档 ⇒ 整段 no-op，旧路径逐值不变。
    //   ★ F/headroom 用 E4b/D-030 的唯一算法（DebtCapacityBook）；容量按**当刻债务终态**重算，
    //     且与 4b 共用同一份市场价目表 lookup（可定价债务全部计入；任一不可定价仍由借贷口径 fail-closed）。
    // ★ B2-保留段首base：E5b 的**接线闸门**（六张表任一非空即启用），不是逐日读数：段内它只可能因
    //   "全空世界中途长出归属行"而翻转，而那种世界在两条推进路径下都同样不启用 E5b。
    //   ★ B1 账本（2026-10-10 fix-advance-path-dependence §3 末行）已裁定本处**不动**，留待接线面统一裁决。
    if (anyCycleClosed && EconomyLiquidationSettlement.isActive(base, dayView.definitions())) {
      Map<HouseholdId, DebtCapacity> closeDebtCapacities =
          debtCapacitiesForDay(
              householdEconomies,
              flows,
              newCycleHouseholds,
              income,
              consumedGoods,
              householdGoods,
              cycleDaysByHousehold,
              debts,
              units,
              industries,
              assetShares,
              operatorConditions,
              settlementIndex,
              debtUnitValueLookup);
      EconomyLiquidationSettlement.settle(
          session,
          // ★★ B5（裁定十八）：生产方式定义的唯一权威在 production ⇒ 显式传当刻定义来源。
          dayView.definitions(),
          day,
          currentCycle,
          householdEconomies,
          assetShares,
          session.sheet().pledges(),
          debts,
          principalAtDayStart,
          repaidPrincipalByDebt,
          closeDebtCapacities,
          industries,
          ledger);
      int liquidationAudits = ledger.toLedger().liquidationAudits().size();
      if (liquidationAudits > 0) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "LIQUIDATION",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "audits",
                    liquidationAudits));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "LIQUIDATION_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "audits",
                    liquidationAudits));
      }
    }

    // ── 5c. S3 阶层写回（关账日、经营者状态机之后）────────────────────────────────────────
    //   ★ 只改 HouseholdEconomy.view：稳定 HouseholdId、actor/账户/债务/劳动配额/人口/成员份额一字不动（withView 的方法承诺）。
    //   ★ E5b：有 HouseholdClassMembership 的家户以 standing 为权威 —— 把 currentPositionId 投影回 view.stratum；
    //     投影不到的非 legacy 位置保留旧 view 并写具名审计，**不**把该户再交给 HouseholdClassRule（禁止双真相）。
    //   ★ 没有 standing 的旧档才继续走 HouseholdClassRule（投影不到 = 有显式归属但表达不了，不是"退回旧分类器"）。
    //   ★ 分类用本日 ledger（关账日 = 本周期收获/分配的结算账本，租金实付是整周期口径）；读不到时不填 0。
    //   ★ 原四档允许跳变；这里不新增人口、不改任何守恒量。
    if (anyCycleClosed) {
      ProductionLedger classLedger = ledger.toLedger();
      Map<HouseholdId, HouseholdClassMembership> classMemberships =
          session.sheet().classMembershipsOrBase();
      HouseholdClassRule.Index classIndex =
          HouseholdClassRule.Index.of(
              assetShares,
              laborCommitments,
              units,
              industries,
              relations,
              householdEconomies,
              debts,
              settlementIndex);
      List<ClassTransition> classTransitions = new ArrayList<>();
      for (HouseholdId key : new ArrayList<>(householdEconomies.keySet())) {
        HouseholdEconomy householdEconomy = householdEconomies.get(key);
        if (householdEconomy == null) {
          continue;
        }
        HouseholdClassMembership classMembership = classMemberships.get(key);
        SocialClassId derived;
        String reason;
        if (classMembership != null) {
          Optional<SocialClassId> projected =
              LegacyClassStructure.socialClassOf(classMembership.currentPositionId());
          if (projected.isEmpty()) {
            EconomyLiquidationSettlement.recordClassProjectionFallback(
                ledger,
                day,
                key,
                classMembership.currentPositionId(),
                householdEconomy.view().stratum());
            continue; // ★ 保留旧 view，不改旧权威也不另造一个投影
          }
          derived = projected.get();
          reason = "classStanding-authority:" + classMembership.currentPositionId().value();
        } else {
          HouseholdClassRule.Classification classification =
              classIndex.classify(key, Optional.of(classLedger));
          derived = classification.stratum();
          reason = classification.reason();
        }
        if (derived.equals(householdEconomy.view().stratum())) {
          continue;
        }
        ClassTransition transition =
            new ClassTransition(day, key, householdEconomy.view().stratum(), derived, reason);
        householdEconomies.put(
            key,
            householdEconomy.withView(
                new CohortKey(
                    householdEconomy.view().hex(), householdEconomy.view().residence(), derived)));
        classTransitions.add(transition);
      }
      // ★ 具名审计读数：即使没有写回也投递空表（读口才分得清"这次关账没有变化"与"没读到"）。
      ClassTransitionFeed.publish(meta.mapId(), day, classTransitions);
      if (!classTransitions.isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "CLASS_TRANSITIONS",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "count",
                    classTransitions.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CLASS_TRANSITIONS_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "count",
                    classTransitions.size()));
      }
    }

    // ── 流水：每行一条（本期发生额；税 v1 恒 0、利息见上一步）────────────────────────────
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      HouseholdId key = householdEconomyEntry.getKey();
      // ★★ **M2.7（丙条仪器）：周期累加器的清零点与流水同一天** —— 新周期第一天把
      //   {@code cycleNaturalNeedMilli} 重置为"今天这一份"（消费步刚按注入的 {@code naturalNeeds[grain]} 累加过）。
      //   ★ 放在这里而不是消费步里：只有这里同时看得见 {@code newCycleHouseholds}
      //     （由产业 progressDays 推、且与 FlowRow 的清零同一判据）。重置为"当天那一份"而不是 0 —— 理由见
      //     {@link #withCycleNaturalNeed}。
      if (newCycleHouseholds.contains(key)) {
        HouseholdEconomy cycleHouseholdEconomy = householdEconomyEntry.getValue();
        if (cycleHouseholdEconomy != null) {
          householdEconomyEntry.setValue(
              withCycleNaturalNeed(
                  cycleHouseholdEconomy,
                  cycleHouseholdEconomy.naturalNeeds().getOrDefault(GRAIN, 0L)));
        }
      }
      // ★★ **T4 起两张实物表的口径都变了**（R1 的"行侧、形状不变、口径改"）：
      //   · `consumed` = 现扣投入 + 日耗 + 同格取材的**转出**（生产损耗**不在里面**了：它只进 ledger）；
      //   · `income`   = **实物入账**（关系给本行的 cohort 入账 + 同格取材的**转入**）—— **不再是**毛产分成。
      Map<CommodityId, Long> consumed = consumedGoods.getOrDefault(key, Map.of());
      Map<CommodityId, Long> earned = income.getOrDefault(key, Map.of());
      // ★★ W5.5：`borrowed`（本日新借入粮）随借粮腿一并删除 ⇒ 本行只把上期累计原样带过（见下面 FlowRow 的构造）。
      long repaid = repaidToday.getOrDefault(key, 0L);
      long interest = interestToday.getOrDefault(key, 0L);
      // ★ netSurplus = income[grain] − 消费[grain] − 税(0) − 利息（§3.3 的口径；税要等 government 切片）。
      //   ★★ **口径 = 粮**（见 {@link FlowRow#netSurplus()}）：把两种商品折成一个数需要**价格**，而市场与价格属 R4 的 V8
      //     （本轮"不做城乡交换/市场/价格"）⇒ 硬折会编造一个本轮没有的换算率。其余商品的净额在两张表里分别读得到。
      //   ★ 利息是**并入本金**的（没支付、粮库存不动）⇒ 它不进 consumed，守恒式（§6.1）不受影响；
      //     但它照样进"本期盈余/赤字"：债务人**确实**比期初更穷了（欠得更多）。
      long netSurplus =
          earned.getOrDefault(GRAIN, 0L) - consumed.getOrDefault(GRAIN, 0L) - interest;
      Map<CommodityId, Long> dayUnmet = unmetToday.getOrDefault(key, Map.of());
      // ★★ W3（2026-10-11）：经济侧**不再**产生任何死亡（判死归 Social）⇒ 本步当日死亡恒 0。
      //   原 `deathsToday` 累加器与 `applyFamine` 一并删除（AGENTS §一.11：旧设计直接删）。
      //   ★ W3-fix（2026-10-11）：`FlowRow.deaths` 的**非 0 值来自"社会报来的死亡"**，由逐户回写路径
      //     applyHouseholdPopulationDeltasInto（四参）→ withLifecycle 在**本步之后**写进同一行流水；
      //     故下面的 `acc.deaths() + dayDeaths` 是"把本步没动的那一列原样带过"，不是"清账"
      //     （清账只发生在 newCycleHouseholds 那一支）。
      long dayDeaths = 0L;
      long dayBirths = birthsToday.getOrDefault(key, 0L);
      // ★★ E4c：货币债偿还与资本化读数的当日量（与其余字段同窗口：本周期累加、新周期第一天归零）。
      Map<CurrencyId, Long> dayRepaidMoney = repaidMoneyToday.getOrDefault(key, Map.of());
      Map<String, Long> dayCapitalized = capitalizedArrearsToday.getOrDefault(key, Map.of());
      // ★ 多日推进（§十一）：当天的流水**累加**进本期流水，不能覆盖（否则"推进 100 天"只显示最后一天）。
      //   ★★ **本期口径（§八.5）**：新周期的第一天（progressDays == 0，含创世）该行**整行从 0 重记** ——
      //      上周期末的读数在**关账那一支的 revision 里**读得到（归档），次日才归零（清零）。
      //      清零点必须落在"新周期第一天"而不是"关账那一支"：后者自己产生本周期最大的一笔所得（收获的毛产分配），
      //      在那里清零会把刚收获的那笔当场抹掉。粒度是**按家户**（H0：行就是家户），由它供给的那些产业的 progressDays
      //      决定 ⇒ 与 §十一 等价性相容。
      FlowRow acc = newCycleHouseholds.contains(key) ? null : flows.get(key);
      flows.put(
          key,
          new FlowRow(
              key,
              mergeGoods(acc == null ? null : acc.income(), earned),
              mergeGoods(acc == null ? null : acc.consumed(), consumed),
              acc == null ? 0L : acc.taxPaid(),
              (acc == null ? 0L : acc.interestDue()) + interest,
              // ★★ W5.5：借粮腿已删 ⇒ 本日新借入粮恒 0（货币腿的借入是**钱**，量纲不同，不塞进这个粮字段）。
              acc == null ? 0L : acc.newBorrowing(),
              acc == null ? 0L : acc.repaid() + repaid,
              (acc == null ? 0L : acc.netSurplus()) + netSurplus,
              mergeGoods(acc == null ? null : acc.unmetNeed(), dayUnmet),
              (acc == null ? 0L : acc.deaths()) + dayDeaths,
              (acc == null ? 0L : acc.births()) + dayBirths,
              mergeMoney(acc == null ? null : acc.repaidMoney(), dayRepaidMoney),
              mergeNamedQuantities(acc == null ? null : acc.capitalizedArrears(), dayCapitalized)));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "FLOWS",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "rows",
                  flows.size(),
                  "newCycleHouseholds",
                  newCycleHouseholds.size()));
    }

    OptionalLong lastClosed =
        anyCycleClosed ? OptionalLong.of(currentCycle) : meta.lastClosedCycle();
    EconomyMeta nextMeta =
        new EconomyMeta(
            meta.mapId(),
            meta.activatedDay(),
            lastClosed,
            meta.rulesVersion(),
            meta.migrationSource(),
            // ★★★ W10-fix（2026-10-11）：政策表**原样带过**。缺这一行 ⇒ 本行用 5 参便捷构造器
            //   （= 空表）重建 meta，逐日结算把 GM 写进 economyPolicies 的政策**静默清空** ——
            //   政策只在"写入的那一个 tick"内成立，下一个 advance 之后就不见了（W10-收口批实测
            //   240/240 个结算日全中）。★ 本类型的"逐字段手写重建"是铁律 5 那一族的老坑：
            //   再加第 7 个组件时，这里同样要跟着加。
            meta.economyPolicies(),
            // ★★ D-032 B2c：按方案声明的"闲置份额来源主体"**原样带过**（同 W10 政策表那条教训：
            //   漏这一行 = 逐日结算把 GM 的声明静默清空）。
            meta.planAssetSources(),
            // ★★ D-032 B3（裁定十二）：按方案声明的"劳动来源"**原样带过**（同上一条教训）。
            meta.planLaborSources());
    // ★ R2：劳动供给表与配额表**原样带过结算的日常部分**（配额属命令层、供给属 social 的人口真值源）；
    //   ★ W3 起结算**不再**按死亡比例缩配额（判死归 Social）⇒ 这里交出的是**工作副本**。
    // ★★ E3：把本次结算日产生的发行审计记录落进状态表（按 id 确定性排序；map 键 = 记录 id）。
    //   ★ 只有真的发生单边发行才有记录；没有发行时连工作副本都不碰（旧路径逐值不变）。
    List<MoneyIssuanceRecord> issuedToday = issuanceJournal.drainSorted();
    if (!issuedToday.isEmpty()) {
      LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> issuanceTable =
          session.sheet().moneyIssuances();
      for (MoneyIssuanceRecord record : issuedToday) {
        MoneyIssuanceRecord existing = issuanceTable.putIfAbsent(record.id(), record);
        if (existing != null && !existing.equals(record)) {
          throw new IllegalStateException("同一天同一笔转移产生了两条不同的发行记录（id 冲突）: " + record.id());
        }
      }
    }
    // ★ T2：生产关系表**原样带过**（本任务行为不变：{@code harvest} 还没读它 —— 那时 T4 的事）。
    session.sheet().meta(Optional.of(nextMeta));

    // ── ★★ P10.2 周期关账钩子：⑦真实利润汇总 → ⑧迁移计划 → ⑨迁移执行 ─────────────────────────
    //   ★ 位置：在 ⑥饥饿/死亡、5b/5b.5/5c 全部之后、下一个 step() 的"投入开扣"之前。
    //   ★ 闸门：旧档（modes 空）profitCycle 恒 null ⇒ 这一整段不执行，逐值不变。
    if (profitCycle != null) {
      profitCycle.recordDay(day, ledger.toLedger(), ledger.marketReport());
    }
    // ★★ B5（§13.15.2 裁定十八）：`modes` 读日视图（default → production 定义权威）⇒ 段内恒定；本闸门判"这个世界接没接线 E1–E6"。
    if (profitCycle != null && anyCycleClosed && !dayView.modes().isEmpty()) {
      profitCycle.recordCloseFacts(day, closedFacts);
      // ⑦a ★★ M-A1：商号周期结算（运费实收/porter 工资/upkeep/运力写回）**已整体退役** —— 它依赖的商号行
      //   （merchantFirms）与 merchant 载体都不存在了：运力改为每轮派生（MerchantCapacityPool），运费在成交当时
      //   直接付给提供运力的家户。★ 「纯商号/顺便分流 + 利润算式（含三层税）」属 **M-C**，本批不发明公式
      //   （见实现账本 D-3）；因此这里不再有任何"周期末把利润写回状态"的动作。
      // ⑦b 真实利润汇总（只读本周期真实账）。
      EnterpriseProfitBook.Book profitBook =
          EnterpriseProfitBook.collect(
              profitCycle,
              session.sheet().productionOrganizations(),
              units,
              householdEconomies,
              industries,
              MarketIndex.of(dayView),
              accounts);
      // ⑧ 迁移计划（真实利润权重 + A 规则 + 目标选择）。
      //    ★★ D-032 §15.12 B（R1）：把本会话的生产运行时一起交下去 —— 当前户读数与全部候选的
      //      产出/所需劳动/所需投入都取自 production 的试算（熟练度/GM 覆盖/解锁条件因此进入选择链）。
      ModeMigrationPolicy.MigrationPlan migrationPlan =
          ModeMigrationPolicy.plan(
              dayView,
              session.sheet().productionOrganizations(),
              units,
              householdEconomies,
              session.sheet().classMemberships(),
              assetShares,
              session.sheet().relations(),
              laborCommitments,
              markets,
              session.sheet().debtContracts(),
              accounts,
              profitBook,
              day,
              topology,
              profitCycle.marketReports(),
              session.productionRuntime(),
              settlementIndex
                  .d4AssetOccupancyBudget()
                  .orElseThrow(
                      () -> new IllegalStateException("asset-occupancy-unavailable: migration")));
      // ⑨ 迁移执行（只执行计划；源户 mode/standing/org/unit.modeKey 一字不改）。
      //    ★ D-023：把当天的瞬态 ledger 传进去记“留原户资产/关系模板回退”的具名读数（不新增持久组件）。
      if (!migrationPlan.moves().isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MIGRATION_PLAN",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "moves",
                    migrationPlan.moves().size(),
                    "organizations",
                    profitBook.byOrganization().size(),
                    "modeHexProfits",
                    profitBook.netByModeHex().size()));
        if (RAW.isTraceEnabled()) {
          for (ModeMigrationPolicy.MigrationMove move : migrationPlan.moves()) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "MIGRATION_MOVE",
                        EconomyLogSource.ECONOMY_MIGRATION,
                        "day",
                        day,
                        "source",
                        move.source().value(),
                        "target",
                        move.target().value(),
                        "targetHex",
                        move.targetHex().q() + "," + move.targetHex().r(),
                        "targetMode",
                        move.targetMode().value(),
                        "population",
                        move.population(),
                        "moneyMilli",
                        move.moneyMilli(),
                        "moneyByCurrency",
                        move.moneyByCurrency(),
                        "debtMilli",
                        move.debtMilli(),
                        "speedPerMille",
                        move.transferSpeedPerMille(),
                        // ★★★ W9-fix（U3 修复，控制方裁定 v3.32 §7 第 5 条）：**谁落到目标产业的第几档**
                        //   （0 = 贫农/兜底档 … 3 = 地主档；规划期按"带得走的作业资本"算，见
                        //   ModeMigrationPolicy.supportedTierIndex）。改前这一维**只存在于瞬态计划类型里**，
                        //   没有任何日志/落盘 ⇒ 真世界读不到"按库存定档"是否发生（W9d 只能靠单测替代）。
                        //   ★ 同族字段 speedPerMille 已在（上面一行），无需补。
                        "targetTierIndex",
                        move.targetTierIndex(),
                        "reason",
                        move.reason()));
          }
        }
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "PROFIT_MIGRATION_PLAN",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "organizations",
                    profitBook.byOrganization().size(),
                    "modeHexProfits",
                    profitBook.netByModeHex().size(),
                    "moves",
                    migrationPlan.moves().size()));
      }
      int rowsBeforeMigration = householdEconomies.size();
      ModeMigrationSettlement.apply(session, accounts, migrationPlan, dayView, day, ledger);
      if (!migrationPlan.moves().isEmpty()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MIGRATION_APPLIED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "moves",
                    migrationPlan.moves().size(),
                    "rowsBefore",
                    rowsBeforeMigration,
                    "rowsAfter",
                    householdEconomies.size(),
                    "transfersToday",
                    ledger.toLedger().transfers().size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MIGRATION_APPLIED_DETAIL",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "rowsAfter",
                    householdEconomies.size(),
                    "transfersToday",
                    ledger.toLedger().transfers().size()));
      }
      profitCycle.resetForNextCycle();
    }
    // ★★★ W5-F：迁移/组织等非 D4 写者可能在本日更早的预算之后拆分份额。grant 一旦大于其 share.quantity，
    //   候选态就会被 EconomyReferenceValidation 整批拒绝；在日结算收尾处按当前份额表统一收口 grant 数量。
    //   ★ 只截掉不可用的超出量并记 asset-short:<kind>；不改变 D4 分配/释放/整池/最大余数语义，也不放宽校验。
    AssetOccupancyBook.reconcileGrantQuantitiesWithShares(
        session.sheet().assetOccupancies(), session.sheet().assetShares());
    long dayEndPopulation = 0L;
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      dayEndPopulation += householdEconomy.population();
    }
    MarketReport dayMarketReport = ledger.marketReport();
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DAY_END",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "population",
                dayEndPopulation,
                "deaths",
                0L, // ★ W3-fix：本字段 = **本阶段（经济日结算）自己产生的死亡数** ⇒ 结构性恒 0（经济侧不判死）。
                //   社会报来的死亡不在这里读：它们由同一次修订里 step 之后的逐户回写写进 FlowRow.deaths，
                //   并另发一条 HOUSEHOLD_VITAL_WRITEBACK（见
                // applyHouseholdPopulationDeltasInto，INFO/DEBUG）。
                //   ⇒ 别把这里的 deaths=0 读成"今天没人死"。
                "unmet",
                traceTotalGoods(unmetToday),
                "borrowedGrainMilli",
                0L, // ★ W5.5：借粮腿已删 ⇒ 本日新借入粮恒 0（货币腿的借入额见 MONEY_MARKET_ROUND / debtContracts）。
                "repaidGrainMilli",
                traceTotalLongs(repaidToday),
                "repaidMoneyByCurrency",
                traceMoneyByCurrency(repaidMoneyToday),
                "debtContracts",
                debts.size(),
                "transfers",
                ledger.toLedger().transfers().size(),
                "marketFills",
                dayMarketReport == null ? 0 : dayMarketReport.fills().size(),
                "marketUnfilled",
                dayMarketReport == null ? 0 : dayMarketReport.unfilled().size()));
    if (TRACE.isDebugEnabled()) {
      Map<TransferReason, Long> transferCounts = new TreeMap<>();
      Map<TransferReason, Long> transferGoods = new TreeMap<>();
      Map<TransferReason, Long> transferMoney = new TreeMap<>();
      for (Transfer transfer : ledger.toLedger().transfers()) {
        transferCounts.merge(transfer.reason(), 1L, Long::sum);
        long goods = 0L;
        for (long quantity : transfer.goods().values()) {
          goods += quantity;
        }
        long money = 0L;
        for (long quantity : transfer.money().values()) {
          money += quantity;
        }
        transferGoods.merge(transfer.reason(), goods, Long::sum);
        transferMoney.merge(transfer.reason(), money, Long::sum);
      }
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SETTLEMENT_END_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "rows",
                  householdEconomies.size(),
                  "units",
                  units.size(),
                  "debts",
                  debts.size(),
                  "transfers",
                  ledger.toLedger().transfers().size(),
                  "byReasonCount",
                  transferCounts,
                  "byReasonGoods",
                  transferGoods,
                  "byReasonMoney",
                  transferMoney));
    }
    // ★★ Z2（§5.2/§6.1）：当日结算成功后清空会话注入集 —— 未再注入的次日 = 1000‰ 中性。
    //   放在本方法（唯一日结算实现）末尾：直接 settleOneDayInto 的调用方也得到同一"当日消费后清空"语义；
    //   抛异常时不清空（本会话随本次推进丢弃，不落 revision）。
    // ★★ 机械修正（D-032 A 批续做）：D2 退役了整条"逐 tick 生产效率修正注入"通路 ⇒ EconomySession 不再有
    //   productionModifiers，也没有 clearProductionModifiers()。**"当日结算成功后清空注入集"这件事随通路一起
    //   消失**（没有注入集可清）—— 不是"漏了一次清空"：注入面已不存在，故不存在"未清空而残留"的路径。
  }

  /**
   * ★★ <b>到货处理</b>（M2.4/M2.5）：把 {@code arrivalTick <= day} 的在途批次逐票落回买方账户。
   *
   * <pre>
   * loss    = ⌊本票数量 × lossPerMille ÷ 1000⌋        // 在途实物减少
   * arrived = 本票数量 − loss                         // 买方只收到净额（M2.5：买方承担损耗）
   * 记损耗：ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, commodity, loss)   // 进损耗账户，不静默蒸发
   * 记到货：买方账 += arrived（家户或经营者）★ M6：能定位的买方/卖方由 app 侧 loadAccountSession 一并载入
   * （缺 actor 账建零账）；这里只在**真正无主**时抛，货不静默丢
   * </pre>
   *
   * <p>★ <b>为什么在日循环里、且排在消费之前</b>：到货日是"目的地第一次能消费它"的那一天（M2.4 的判据）。 ★ <b>补投</b>：手工搭的状态可能给 {@code
   * arrivalTick < day}，本方法照样投递，不让货卡在在途表里。 ★ 本批只产生 {@link
   * io.mosire.simos.economy.api.market.LossBearer#BUYER}；其他分担方留位，见到即抛（不假装已实现）。
   */
  /**
   * ★★ <b>到货模块的分区数</b>：R2 起由 {@link EconomyParallelism#partitionCount()} 统一给（固定 {@link
   * EconomyParallelism#STRUCTURAL_PARTITIONS} 个结构分区，1/4/8 线程共用同一份分区计划与提交序）。
   *
   * <p>★ 到货按<b>目的账户</b>分区（不是 hex：同一买方多批到货必须并到同一分区，而买方可能在任意格）； 其它阶段按 hex / 市场区 / PeopleLotId 分区，见
   * {@link SettlementStage} 的枚举注释。
   */

  /** 到货阶段的一条工作单元（按目的地账户分区；同一买方的多票并到同一分区）。 */
  private record DeliveryWork(
      AccountPartitionKey buyerKey,
      ShipmentId shipmentId,
      int allocationIndex,
      CommodityId commodity,
      long arrived,
      long loss) {}

  /** 到货阶段的一条在途损耗（提交时按稳定序落 {@code ProductionLedger.losses}）。 */
  private record DeliveryLoss(
      ShipmentId shipmentId, int allocationIndex, CommodityId commodity, long amount) {}

  /** 一个分区的到货产出：线程本地意向 + 损耗读数（都不可变，提交前不触碰共享状态）。 */
  private record DeliveryPartition(List<OrderedAccountIntent> intents, List<DeliveryLoss> losses) {}

  /**
   * ★★ <b>到货：在途 → 买方账户</b>（M2.4；R1 起是本仓第一条真正按账户分区的阶段）。
   *
   * <pre>
   * ① 协调器在 AccountSnapshot 上收集"到达日 ≤ day"的逐票工作单元，按 (买方账户 canonical, shipmentId, 票序)
   *    排序 ⇒ 分区计划只依赖 canonical 串（1/4/8 线程同分区）；
   * ② worker 只读快照、只写自己的 AccountIntentBuffer（creditGoods）⇒ 共享账户 Map 在 worker 期间没有写者；
   * ③ 提交：SettlementExecutor.commit 按 (stage, partitionIndex, canonicalKey, intraIndex) 单线程落账；
   * ④ 损耗按同一稳定序落 ledger；到达批次按 canonical 序销账。
   * </pre>
   *
   * <p>★ <b>语义与逐条实现的关系</b>：到货加库存原本就走 {@code addStock}（家户账）/{@code addOperatorStock}
   * （经营者账）；本实现把"买方是家户还是经营者"统一收敛到 {@code (ActorRef, location)} 账户键，故只有一条路径。 ★
   * 到达前消费不到它；到货日"在途减、目的地库存增"的守恒口径不变，损耗仍由买方承担。
   */
  private static void deliverShipments(
      long day,
      EconomySession session,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism) {
    LinkedHashMap<ShipmentId, ShipmentBatch> shipments = session.sheet().shipments();
    AccountSnapshot snapshot = accounts.snapshot();
    List<DeliveryWork> works = new ArrayList<>();
    LinkedHashSet<ShipmentId> arrivingBatches = new LinkedHashSet<>();
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : shipments.entrySet()) {
      ShipmentBatch batch = entry.getValue();
      if (batch.arrivalTick() > day) {
        continue;
      }
      arrivingBatches.add(entry.getKey());
      List<ShipmentAllocation> allocations = batch.allocations();
      for (int i = 0; i < allocations.size(); i++) {
        ShipmentAllocation allocation = allocations.get(i);
        if (allocation.lossBearer() != io.mosire.simos.economy.api.market.LossBearer.BUYER) {
          throw new IllegalStateException(
              "本批只支持买方承担在途损耗（M2.5 基线合同），见到其他分担方: " + allocation.lossBearer());
        }
        long loss = allocation.quantity() * batch.route().lossPerMille() / 1000L;
        long arrived = allocation.quantity() - loss;
        AccountPartitionKey buyerKey = snapshot.actorKeyOrNull(allocation.buyer());
        if (buyerKey == null) {
          // ★★ M6：货仍然不能丢，但"缺账"只在**真正无主**时才抛 —— 能在 EconomyData 里定位的买方/卖方
          //   （家户行 / 产业 operator）已由 app 的 OwnershipBooks.loadAccountSession 一并载入（缺 actor 账时建零账），
          //   到货日不会再因"跨区经营者未播种"这种口径缺口整条推进失败。
          throw new IllegalStateException(
              "在途到货时买方在 economy 侧也定位不到账户（真正无主，货不能静默丢）：批次="
                  + entry.getKey()
                  + " 买方="
                  + allocation.buyer()
                  + " 收货格="
                  + allocation.deliverTo());
        }
        works.add(
            new DeliveryWork(
                buyerKey, entry.getKey(), i, batch.commodity(), arrived, loss > 0L ? loss : 0L));
      }
    }
    if (works.isEmpty()) {
      return;
    }
    // ★ 分区内的遍历序 = canonical 升序（先按账户键、同账户再按票的稳定序）——意向生成序是内容的纯函数。
    TreeMap<String, List<DeliveryWork>> worksByKey = new TreeMap<>();
    for (DeliveryWork work : works) {
      worksByKey
          .computeIfAbsent(work.buyerKey().canonical(), ignored -> new ArrayList<>())
          .add(work);
    }
    for (List<DeliveryWork> bucket : worksByKey.values()) {
      bucket.sort(
          Comparator.comparing((DeliveryWork work) -> work.shipmentId().value())
              .thenComparingInt(DeliveryWork::allocationIndex));
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.DELIVER_SHIPMENTS, worksByKey.keySet(), parallelism.partitionCount());
    List<DeliveryPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.on(
                      snapshot,
                      SettlementStage.DELIVER_SHIPMENTS,
                      partition.partitionIndex(),
                      plan.partitionCount());
              List<DeliveryLoss> losses = new ArrayList<>();
              for (String canonical : partition.canonicalKeys()) {
                AccountPartitionKey key = AccountPartitionKey.parseCanonical(canonical);
                for (DeliveryWork work : worksByKey.get(canonical)) {
                  if (work.arrived() > 0L) {
                    // ★ 全损到货（arrived == 0）不产出一笔 0 增量：与旧 addStock 的"delta == 0 直接返回"逐值同口径。
                    buffer.creditGoods(key, work.commodity(), work.arrived());
                  }
                  if (work.loss() > 0L) {
                    losses.add(
                        new DeliveryLoss(
                            work.shipmentId(),
                            work.allocationIndex(),
                            work.commodity(),
                            work.loss()));
                  }
                }
              }
              return new DeliveryPartition(buffer.drainIntents(), losses);
            },
            parallelism.poolOrNull());
    // ★★ 单线程稳定提交：转移/增量/冻结同走 SettlementExecutor.commit 的字典序；到货只产生增量。
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (DeliveryPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (DeliveryPartition partition : partitions) {
      for (DeliveryLoss loss : partition.losses()) {
        ledger.addLoss(MarketSettlement.TRANSPORT_LOSS_ACCOUNT, loss.commodity(), loss.amount());
      }
    }
    List<ShipmentId> removals = new ArrayList<>(arrivingBatches);
    removals.sort(Comparator.comparing(ShipmentId::value));
    for (ShipmentId id : removals) {
      shipments.remove(id);
    }
  }

  // ── R2：按 hex 分区的日结算阶段（统一执行器 + 线程本地意向 + 协调器稳定提交）──────────────────
  //
  // ★★ 三个阶段共用同一条架构（照 §4.4）：
  //   ① 协调器在 AccountSnapshot 上按 hex canonical 串建 PartitionPlan（固定结构分区数，1/4/8 同一份）；
  //   ② worker 只读快照/只读共享状态表，写自己的 AccountIntentBuffer 与本地状态副本；
  //   ③ 协调器按分区序 commit 意向、合并本地副本 —— 提交序只由 CommitOrder 决定，与线程到达序无关。
  // ★ 冲突表判据：同一 hex 内的产业/家户共用账户与行，必须同分区串行；跨 hex 没有共享 (actor, location) 账户。
  // ★ 本阶段的转移仍走唯一写口 applyTransfer（worker 把它应用在 BufferedAccountTables 的意向视图上），
  //   铸号走分区累加器（id 带 p<partition> 段，1/4/8 同 id），协调器按分区序吸收进当天账本。

  /** 消费阶段一个分区的产出（意向 + 行更新 + 三个读数累加器）。 */
  private record ConsumptionPartition(
      List<OrderedAccountIntent> intents,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Map<CommodityId, Long>> unmet,
      Map<HouseholdId, Long> deficits) {}

  /**
   * ★★ <b>消费：吃自家库存</b>（按 hex 并行；缺口的借贷已按 S-12① 移到市场轮的货币腿）。
   *
   * <p>每个分区处理若干 hex；同 hex 内按 {@link #rowsByHex} 的既有行序逐户消费（户与户之间无共享账户），
   * 写的是线程本地意向。缺口/消费/未满足三个累加器随分区返回，协调器按分区序合并（整数加法可交换）。
   */
  private static void consumeOwnStockPartitioned(
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<HouseholdId, Long> deficitToday,
      EconomyParallelism parallelism) {
    Map<String, List<HouseholdId>> hexToRows = rowsByHex(householdEconomies);
    if (hexToRows.isEmpty()) {
      return;
    }
    AccountSnapshot snapshot = accounts.snapshot();
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.CONSUMPTION, hexToRows.keySet(), parallelism.partitionCount());
    List<ConsumptionPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.onHexPartition(
                      snapshot,
                      SettlementStage.CONSUMPTION,
                      partition.partitionIndex(),
                      plan.partitionCount());
              BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
              LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomyUpdates =
                  new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmet = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Long> deficits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (HouseholdId key : hexToRows.get(hex)) {
                  consumeOneHousehold(
                      householdEconomies,
                      tables.householdGoods,
                      key,
                      householdEconomyUpdates,
                      consumed,
                      unmet,
                      deficits);
                }
              }
              return new ConsumptionPartition(
                  buffer.drainIntents(), householdEconomyUpdates, consumed, unmet, deficits);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (ConsumptionPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (ConsumptionPartition partition : partitions) {
      for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyUpdate :
          partition.householdEconomyUpdates().entrySet()) {
        householdEconomies.put(householdEconomyUpdate.getKey(), householdEconomyUpdate.getValue());
      }
      mergeGoodsInto(consumedGoods, partition.consumed());
      mergeGoodsInto(unmetNeed, partition.unmet());
      for (Map.Entry<HouseholdId, Long> deficit : partition.deficits().entrySet()) {
        deficitToday.merge(deficit.getKey(), deficit.getValue(), Long::sum);
      }
    }
  }

  /**
   * 消费一户（{@link #consumeOwnStockPartitioned} 的逐户版；读共享行/意向视图，写本地累加器）。
   *
   * <p>★★ <b>2026-10-09 家户结构修复 Batch 3：需求不再按 {@code population} 现算</b> —— 直接读 app 在本日 {@link
   * #applyNaturalNeedsInto} 注入的 {@code naturalNeeds}；消费开始时把当日粮需求累加进 {@code
   * cycleNaturalNeedMilli}（一天只此一次；新周期第一天的重置仍在流水清零点由 {@link #withCycleNaturalNeed} 完成）。{@code
   * population <= 0} 的行只做这次 0 增量写回，不读账、不消费。
   */
  private static void consumeOneHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      Map<HouseholdId, Long> deficitToday) {
    HouseholdEconomy current = householdEconomies.get(key);
    if (current == null) {
      throw new IllegalStateException("消费步的家户行不存在（分区键与工作表不一致，拒绝把缺行当成 0 需求）: " + key);
    }
    Map<CommodityId, Long> needs = current.naturalNeeds();
    long need = needs.getOrDefault(GRAIN, 0L);
    HouseholdEconomy householdEconomy =
        current.withNaturalNeedsAndCycle(
            needs, Math.addExact(current.cycleNaturalNeedMilli(), need));
    householdEconomyUpdates.put(key, householdEconomy);
    if (householdEconomy.population() <= 0L) {
      return;
    }
    long stock = stockOf(householdGoods, key, GRAIN);
    long eaten = Math.min(stock, need);
    setStock(householdGoods, key, GRAIN, stock - eaten);
    addGoods(consumedGoods, key, GRAIN, eaten);
    long clothNeed = needs.getOrDefault(CLOTH, 0L);
    long clothStock = stockOf(householdGoods, key, CLOTH);
    long clothGot = Math.min(clothStock, clothNeed);
    if (clothNeed > 0L) {
      setStock(householdGoods, key, CLOTH, clothStock - clothGot);
      addGoods(consumedGoods, key, CLOTH, clothGot);
    }
    if (clothNeed - clothGot > 0L) {
      addGoods(unmetNeed, key, CLOTH, clothNeed - clothGot);
    }
    if (need - eaten > 0L) {
      addGoods(unmetNeed, key, GRAIN, need - eaten);
      deficitToday.put(key, need - eaten);
    }
  }

  /** 周期投入阶段一个分区的产出（意向 + 本地产业写回 + 本地消费累加器 + 本地账本 + "扣产能"日志读数）。 */
  private record InputDrawPartition(
      List<OrderedAccountIntent> intents,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      ProductionLedger.Accumulator ledger,
      CapacityDeductionSummary deductions) {}

  /**
   * ★★ <b>现扣周期投入按 hex 并行</b>（{@link #drawCycleInputs} 的并行外壳）。
   *
   * <p>同格争用（H6-lite 的池子）只在同一 hex 内成立 ⇒ 每个 worker 只拿自己分区那些 hex 的产业副本， 三遍式（调查 → 配给 →
   * 落账）逐字复用；账户写走意向视图，产业写回与消费/账本随分区交回协调器合并。 ★ 产业 id 里没有 {@code @<q>_<r>}
   * 的旧档条目没有格、不参与争用：它们在并行部分之后由协调器按原路径单线程处理。
   */
  private static void drawCycleInputsPartitioned(
      EconomySession session,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day,
      EconomyParallelism parallelism,
      SettlementIndex index) {
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    // ★★ R3B.2：产业表只读（模板；日结算不再改它）。
    Map<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    // ★★ R4-E2b：走 relation 的**工作副本选择**（进入执行可能刚插入新 unit 的 relation；未物化时等于 base）。
    Map<ProductionUnitId, ProductionRules> relations = session.sheet().relationsOrBase();
    // ★ S3：缩产/停业后的"计划规模"要进投入调查（条件缺失 ⇒ 系数 1000‰ ⇒ 旧行为逐值相同）。
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, LinkedHashMap<ProductionUnitId, ProductionProcess>> byHex = new TreeMap<>();
    List<ProductionUnitId> noHex = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : units.entrySet()) {
      Optional<String> hexKey = IndustryHexKeys.hexKeyOf(entry.getValue().industry());
      if (hexKey.isEmpty()) {
        noHex.add(entry.getKey());
        continue;
      }
      byHex
          .computeIfAbsent(hexKey.get(), ignored -> new LinkedHashMap<>())
          .put(entry.getKey(), entry.getValue());
    }
    // ★★ W2-fix3：本轮"扣产能"日志读数 —— 分区各记各的（worker 内），在这里按分区序合并后**只发一条 INFO**。
    CapacityDeductionSummary deductions = new CapacityDeductionSummary(day);
    if (!byHex.isEmpty()) {
      AccountSnapshot snapshot = accounts.snapshot();
      PartitionPlan plan =
          PartitionPlan.of(
              SettlementStage.INPUT_DRAW, byHex.keySet(), parallelism.partitionCount());
      List<InputDrawPartition> partitions =
          SettlementExecutor.execute(
              plan,
              partition -> {
                AccountIntentBuffer buffer =
                    AccountIntentBuffer.onHexPartition(
                        snapshot,
                        SettlementStage.INPUT_DRAW,
                        partition.partitionIndex(),
                        plan.partitionCount());
                BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
                LinkedHashMap<ProductionUnitId, ProductionProcess> localUnits =
                    new LinkedHashMap<>();
                for (String hex : partition.canonicalKeys()) {
                  localUnits.putAll(byHex.get(hex));
                }
                LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
                ProductionLedger.Accumulator partitionLedger =
                    new ProductionLedger.Accumulator(day, partition.partitionIndex());
                CapacityDeductionSummary partitionDeductions =
                    drawCycleInputs(
                        localUnits,
                        industries,
                        householdEconomies,
                        relations,
                        operatorConditions,
                        index,
                        tables.householdGoods,
                        tables.householdMoney,
                        consumed,
                        householdOfActor,
                        partitionLedger,
                        day);
                return new InputDrawPartition(
                    buffer.drainIntents(),
                    localUnits,
                    consumed,
                    partitionLedger,
                    partitionDeductions);
              },
              // ★★★ D-032 B3（§13.13.7 第 3 条）：**本批确定的串行实现** —— 现扣周期投入这一段不再起 worker。
              //   ★ 只动并行度：分区计划（同一个 `PartitionPlan.of(..., parallelism.partitionCount())`，结构分区数
              //     仍是常量 32）、同一个 `AccountIntentBuffer`、同一个 `SettlementExecutor#commit` 提交序一字未改；
              //     `pool == null` ⇒ 就地按**分区序**逐个跑（见 `SettlementExecutor` 的类注）⇒ 逐值结果与
              //     "确定性归并的并行"同一套新规则下一致（协调器侧的合并循环本来就按分区序 absorb）。
              //   ★ 业务日序不变（分段位置、到货、消费/市场次序均未动）；★ 未来恢复并行必须保持"同 tick 生效条件 /
              //     全局预算 / 候选态与归并语义"，本批**不**为它预加框架。
              null);
      List<OrderedAccountIntent> intents = new ArrayList<>();
      for (InputDrawPartition partition : partitions) {
        intents.addAll(partition.intents());
      }
      accounts.commit(intents);
      // ★ B1：本日"现扣周期投入"的**唯一权威**现在由 production 的候选态承载（见 {@code executeDay}）。
      //   本循环只把账户侧的消费并入全局累加器；分区副本里的 unit 不再写回主表。
      for (InputDrawPartition partition : partitions) {
        mergeGoodsInto(consumedGoods, partition.consumed());
        // ★★ M7：协调器按分区序 absorb ⇒ 列表序 = (阶段 → 分区序 → 分区内生成序)；转移 id 带 p<partition> 段，
        //   seq 是分区内序号，与旧串行号的对应关系不保证。同代码态内 1/4/8 线程确定；V 阶段跨代码态比较
        //   transfers/outputAccruals/ruleSettlements 一律"按业务键排序后比较集合"，不得按 id 或列表下标逐条比。
        ledger.absorb(partition.ledger());
        // ★★ W2-fix3：分区账按**分区计划序**合并（{@code SettlementExecutor.execute} 保证结果序 = 计划序）。
        deductions.merge(partition.deductions());
      }
      // ★★★ B1（D-032 §4.3 数据流第 5 段）：现扣阶段的结果**经 production 的契约写回本域候选态** ——
      //   这是 economy 侧"最终投入"的落点：`executeDay` 的 `finalInputsMilli` 就是这一份实扣量。
      //   ★ 为什么必须经过契约而不是就地写实例表：现扣发生在**并行分区**里（每分区一份实例副本），
      //     而 production 的候选态是**单线程独占**的（引擎不是线程安全的"每分区一个副本"形态）
      //     ⇒ 分区结果先在协调器线程汇总，再一次性推回；顺序由此确定（分区序 → 分区内生成序）。
      for (InputDrawPartition partition : partitions) {
        for (Map.Entry<ProductionUnitId, ProductionProcess> entry : partition.units().entrySet()) {
          session.recordDrawnProductionInput(
              entry.getKey(), entry.getValue().cycleInputUsedMilli());
        }
      }
    }
    if (!noHex.isEmpty()) {
      // ★ 旧档/手搭状态的产业 id 没有格键：没有位置就没有账户可安全分区 ⇒ 协调器按原路径单线程处理。
      LinkedHashMap<ProductionUnitId, ProductionProcess> local = new LinkedHashMap<>();
      for (ProductionUnitId id : noHex) {
        local.put(id, units.get(id));
      }
      deductions.merge(
          drawCycleInputs(
              local,
              industries,
              householdEconomies,
              relations,
              operatorConditions,
              index,
              accounts.householdGoods(),
              accounts.householdMoney(),
              consumedGoods,
              householdOfActor,
              ledger,
              day));
      for (Map.Entry<ProductionUnitId, ProductionProcess> entry : local.entrySet()) {
        session.recordDrawnProductionInput(entry.getKey(), entry.getValue().cycleInputUsedMilli());
      }
    }
    // ★★ W2-fix3（§一.9）：轮末**一条 INFO 汇总**（协调器线程；与分区数无关 ⇒ 行数恒为 1）。
    //   逐 unit 明细已在各分区里按 DEBUG 发出（PRODUCTION_CAPACITY_DEDUCTED_DETAIL）。
    deductions.logSummary();
  }

  /** 劳动再分配阶段一个分区的产出（本地配额表 + 该分区 seed 过的键，用于识别"整条回池"的删除）。 */
  private record LaborPartition(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Set<LaborAllocationId> seededKeys) {}

  /**
   * ★★ <b>R4-E2b：这个 unit 是否由候选预设进入</b>——旧档/主副 unit 的 {@code modeKey == industry.id()}（世界播种与
   * 旧档迁移的不变式），候选 unit 的 {@code modeKey == candidateId@version}（见 {@code ProductionProcess} 类注）。 候选
   * unit 的 {@code cycleDays} 可以与同格旧产业不同，故它自己的周期边界不应当触发整格重排。
   */
  private static boolean isPresetOrigin(ProductionProcess unit) {
    return !unit.modeKey().equals(unit.industry().value());
  }

  /**
   * ★★ <b>劳动再分配按 hex 并行</b>（{@link #reallocateLabor} 的并行外壳；R3B.2 起键 = unit id）。
   *
   * <p>★ <b>R4-E2b：{@code cycleStartExempt}</b> = 今天刚由 E2b 进入执行新建的 unit。它们 {@code progressDays==0}
   * 是事实，但本步<b>整条把它们排除在本次重排之外</b>：既不让它们单独触发"这一格本周期第一天"的判定，也不让它们进 本格的再分配集合。理由两条：
   *
   * <ol>
   *   <li>一个进入动作不得把同格<b>既有 unit</b> 从半周期/周期初重新排一次劳动 —— 既有 unit 看到的重排结果必须与"没有进入动作"
   *       的世界逐值相同（进入的代价只应落在新 unit 自己与显式 grant 的 assetSource 份额上）；
   *   <li>新 unit 自己的配额就是试产计划的 {@code trialScale × laborPerUnit}（由 E2b 显式发放），也不需要参与 pool 再分配 —— 它若被
   *       reallocation 按"投入缩过后的 need"削一刀，反而让试产口径多一个隐藏写者。
   * </ol>
   *
   * <p>★ <b>触发条件</b>：本格任一<b>旧档/主副 unit</b>（{@code modeKey == industry.id()}，见 {@link
   * #isPresetOrigin}） 在周期第一天（{@code progressDays == 0}）就重排 —— 这是既有口径。★ <b>E2b 候选 unit 不单独触发重排</b>：
   * 它们可以有与既有产业不同的 {@code cycleDays}，若让"试产 unit 自己的周期边界"触发，就会把同格既有 unit 从半周期里
   * 重排一次（违反"旧经营者延续"）。它们仍会在**本格因旧 unit 触发而进入重排**时作为普通 unit 参与（need 照算）。
   *
   * <p>★★ <b>与"空集逐值等价旧路径"的关系</b>：豁免集为空、且世界里没有候选 unit（{@code isPresetOrigin} 全为 false）时，
   * 本方法逐字等于旧路径（触发条件、单位集合、分配顺序都不变）。候选 unit 只由 R4-E2b 新建，故旧档/空表基线不受影响。
   */
  private static void reallocateLaborPartitioned(
      EconomySession session,
      EconomyParallelism parallelism,
      SettlementIndex index,
      Set<ProductionUnitId> cycleStartExempt) {
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    Map<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, List<ProductionUnitId>> unitsByHex = new TreeMap<>();
    for (ProductionProcess unit : units.values()) {
      if (cycleStartExempt.contains(unit.id())) {
        continue; // ★ 今天刚进入的试产 unit：不触发重排、不进本格再分配集合（见方法注释）
      }
      IndustryHexKeys.hexKeyOf(unit.industry())
          .ifPresent(
              hex -> unitsByHex.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(unit.id()));
    }
    TreeMap<String, List<ProductionUnitId>> active = new TreeMap<>();
    for (Map.Entry<String, List<ProductionUnitId>> entry : unitsByHex.entrySet()) {
      // ★ 触发者只认旧档/主副 unit（既有口径）；候选 unit 自己翻篇不触发本格重排（见方法注释）。
      for (ProductionUnitId id : entry.getValue()) {
        ProductionProcess unit = units.get(id);
        if (unit != null && unit.progressDays() == 0L && !isPresetOrigin(unit)) {
          active.put(entry.getKey(), entry.getValue());
          break;
        }
      }
    }
    if (active.isEmpty()) {
      return;
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.LABOR_REALLOCATION, active.keySet(), parallelism.partitionCount());
    List<LaborPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              LinkedHashMap<ProductionUnitId, ProductionProcess> localUnits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (ProductionUnitId id : active.get(hex)) {
                  // ★★★ B8（缺陷 DEF-T2b-2 的同源一处）：本步的"最大可吸收劳动"
                  //   （{@link LaborQueueBook#maxAbsorbableLaborMilli}）要读**本生产日实扣的周期投入**，
                  //   而本日现扣阶段（上面 `drawCycleInputsPartitioned`）的落点是**会话**
                  //   （`EconomySession#drawnProductionInputs`）——实例自己的 `cycleInputUsedMilli`
                  //   要等本 tick 的 `executeDay` 才被写，本步跑在它**之前**。
                  //   ⇒ 把"本日实扣量"镜像进**分区副本**再交给本步（只读副本，不回写实例、不改签名）。
                  //   ★ 为什么必须在**分区副本**上镜像而不是在权威表上：实例状态的唯一写者是 production
                  //     （I1），economy 就地写实例表正是 B1 删掉的那条老路。
                  //   ★ 为什么这样不"造第二份权威"：这份副本只活在本方法内、只喂"本 tick 最多能吸收多少劳动"
                  //     这一个只读判定；周期投入的权威仍由 `executeDay` 的 `finalInputsMilli` 落到 production。
                  ProductionProcess plannerUnit = units.get(id);
                  Map<CommodityId, Long> drawnToday = session.drawnProductionInputsOf(id);
                  localUnits.put(
                      id,
                      drawnToday.isEmpty()
                          ? plannerUnit
                          : plannerUnit.withCycleState(
                              plannerUnit.progressDays(), new LinkedHashMap<>(drawnToday)));
                }
              }
              LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> localLaborCommitments =
                  new LinkedHashMap<>();
              Set<LaborAllocationId> seeded = new LinkedHashSet<>();
              for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
                if (localUnits.containsKey(new ProductionUnitId(laborCommitment.activity()))) {
                  localLaborCommitments.put(laborCommitment.id(), laborCommitment);
                  seeded.add(laborCommitment.id());
                }
              }
              reallocateLabor(
                  localUnits,
                  industries,
                  householdEconomies,
                  localLaborCommitments,
                  operatorConditions,
                  index);
              return new LaborPartition(localLaborCommitments, seeded);
            },
            parallelism.poolOrNull());
    for (LaborPartition partition : partitions) {
      for (LaborAllocationId id : partition.seededKeys()) {
        if (!partition.laborCommitments().containsKey(id)) {
          laborCommitments.remove(id); // 整条回池（原算法 remove；本地副本里没有它）
        }
      }
      laborCommitments.putAll(partition.laborCommitments());
    }
  }

  /**
   * 收获阶段的一条工作单元（关账前的 unit/模板快照 + {@link ProductionEfficiencyBook} 求好的规模 + 该产业的产出数量覆盖 + 所在格 + 原遍历序）。
   *
   * <p>★ Z2 起 {@code scale} 不再由本阶段现算：周期末求值（§6.2 ①~⑤）在日循环里完成（那里是效率状态工作副本的唯一写点， 且要 fail-closed
   * 校验余数有效域）；本记录把结果带进按 hex 并行的收获阶段。
   */
  private record HarvestWork(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
      HexCoord location,
      int order,
      // ── ★★★ D-032 B3（I9 / §13.13.7 第 5 条）：**回执里的实际出工家户与各自贡献** ──────────────────
      //   `laborByHousehold` = 本周期对该 unit 实际出过工的家户 → 实际劳动（千分劳动·日，逐 tick 累计）。
      //   `cycleInputUsed` = 本周期**实际扣到**的投入（毫单位，逐商品）。
      //   ★ 为什么必须由回执带进来（而不是收获阶段现算）：旧口径用
      //     `laborOfCohort(householdEconomies, location, cycleDays)` = "该格**所有**家户行 × 每日劳动 × 周期天数"
      //     —— 那是"住在这一格"的投影，不是"实际出工"的事实：没出工的户也进分母、最后一天出工的户被当成整周期
      //     出工。I9 要的是回执里的实际贡献（`ProductionReceipt.cycleContributions` / `consumedInputs`）。
      Map<HouseholdId, Long> laborByHousehold,
      Map<CommodityId, Long> cycleInputUsed,
      // ── ★★★ D-032 C2：逐 (家户 × 角色) 周期劳动 + 该 unit 的名册（无 rosters ⇒ 旧路径）────────────
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      io.mosire.simos.economy.model.ProductionRoster roster) {}

  /** 收获阶段一个分区的产出（意向 + 本地实物入账 + 本地账本）。 */
  private record HarvestPartition(
      List<OrderedAccountIntent> intents,
      Map<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger) {}

  /**
   * ★★ <b>收获/关系分账按 hex 并行</b>（{@link #harvest} 的并行外壳）。
   *
   * <p>同一 hex 内 farm/weave/craft 共用家户账与关系表 ⇒ 必须同分区串行（按关账原遍历序）；不同 hex 无共享账户。 转移在分区账本里铸号（{@code
   * tr-<day>-p<partition>-<seq>}），协调器按分区序吸收 ⇒ 1/4/8 同 id、同序。
   */
  private static void harvestPartitioned(
      List<HarvestWork> works,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      SettlementIndex index,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      Map<ActorRef, HouseholdId> householdOfActor,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal,
      long day,
      EconomyParallelism parallelism,
      Map<AssetShareId, OwnershipStake> assetShares) {
    if (works.isEmpty()) {
      return;
    }
    TreeMap<String, List<HarvestWork>> byHex = new TreeMap<>();
    for (HarvestWork work : works) {
      byHex.computeIfAbsent(work.location().toString(), ignored -> new ArrayList<>()).add(work);
    }
    for (List<HarvestWork> members : byHex.values()) {
      members.sort(Comparator.comparingInt(HarvestWork::order));
    }
    AccountSnapshot snapshot = accounts.snapshot();
    PartitionPlan plan =
        PartitionPlan.of(SettlementStage.HARVEST, byHex.keySet(), parallelism.partitionCount());
    List<HarvestPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.onHexPartition(
                      snapshot,
                      SettlementStage.HARVEST,
                      partition.partitionIndex(),
                      plan.partitionCount());
              BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> localIncome =
                  new LinkedHashMap<>();
              ProductionLedger.Accumulator partitionLedger =
                  new ProductionLedger.Accumulator(day, partition.partitionIndex());
              for (String hex : partition.canonicalKeys()) {
                for (HarvestWork work : byHex.get(hex)) {
                  harvest(
                      day,
                      work.unit(),
                      work.industry(),
                      work.scale(),
                      work.outputQuantityOverrides(),
                      work.laborByHousehold(),
                      work.cycleInputUsed(),
                      work.cycleLaborByParticipant(),
                      work.roster(),
                      assetShares,
                      householdEconomies,
                      index,
                      localIncome,
                      tables.householdGoods,
                      tables.householdMoney,
                      relations,
                      enterpriseByProcess,
                      viewIndex,
                      householdOfActor,
                      partitionLedger,
                      issuanceJournal);
                }
              }
              return new HarvestPartition(buffer.drainIntents(), localIncome, partitionLedger);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (HarvestPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (HarvestPartition partition : partitions) {
      mergeGoodsInto(income, partition.income());
      // ★★ M7：同投入阶段 —— 列表序由协调器的分区遍历序决定；跨代码态比较按业务键排序后比较集合（见投入阶段的注释）。
      ledger.absorb(partition.ledger());
    }
  }

  /**
   * ★★★ <b>B1（D-032 §4.3 数据流第 5 段）：把本生产日交给 production 执行 —— economy 不再写实例状态</b>。
   *
   * <p>★★ <b>为什么这是 economy 侧"最终投入"的落点</b>：本方法在<b>劳动分配</b>（{@code LaborQueueSettlement} / {@code
   * reallocateLabor}）与<b>现扣周期投入</b>（{@code drawCycleInputs}）都落地之后调用，所以三样输入 都是"已经决定好的"：
   *
   * <ul>
   *   <li><b>劳动贡献</b>：逐 {@code HouseholdLaborCommitment}（{@code activity == unit id}）按家户汇总 ——
   *       <b>不是</b>一个总数。（I9：回执必须保留各家户实际贡献；经验也按<b>实际贡献</b>累加。）
   *   <li><b>资产占用</b>：{@link AssetOccupancyBudget} 给出的该 unit 资产分配（与 economy 自己算产能规模读的是同一份表 ——
   *       预计与实际同源）。
   *   <li><b>原料投入</b>：现扣阶段实际扣到的量（读当刻候选态的 {@code cycleInputUsedMilli}）。
   * </ul>
   *
   * <p>★★ <b>失败语义</b>（§13.2 第 1 条④）：业务拒绝由 {@code executeDay} 逐项具名放进结果；<b>契约故障</b> （{@code
   * ProductionContractFault}）<b>不</b>在这里捕获 —— 它一路冒泡到提案层，让整个 Advance 被拒 （禁止在污染态上收尾）。
   *
   * @return 本生产日的结果（回执 / 拒绝 / 关账规模）
   */
  private static io.mosire.simos.production.contract.ProductionDayResult driveProductionDay(
      EconomySession session,
      long day,
      SettlementIndex settlementIndex,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions) {
    io.mosire.simos.production.contract.ProductionRuntime runtime = session.productionRuntime();
    // ★★ B3：`runtime` 由 `EconomySession` 的构造器保证非 null（Objects.requireNonNull）⇒ 旧的
    //   "本会话没有运行时" 分支已不可达、随之删除 —— fail-closed 的落点前移到**装配边界**（构造会话时当场抛），
    //   而不是等到日推进中途才发现。
    Objects.requireNonNull(runtime, "runtime（EconomySession 保证非 null；此处为防御性读取）");
    // ★★★ B8（缺陷 DEF-T2b-2 的同源一处）：步表的键集必须读**当刻**权威候选态，不得用日结算入口抓的那份快照。
    //   本日更早的阶段（进入执行 / 自动组织 / 模式变迁 / 迁移搬迁）会经契约口 `createInstance` **新建实例**
    //   —— 那些实例当刻就在权威候选态里（`session.sheet().units()` 直读 runtime），而入口快照看不见它们。
    Map<ProductionUnitId, ProductionProcess> stepUnits = session.sheet().units();
    if (stepUnits.isEmpty()) {
      return io.mosire.simos.production.contract.ProductionDayResult.empty();
    }
    // ① 按名册/配额表把实际劳动贡献汇总成**逐 (家户 × 角色)** 列表（D2 §6.1/§6.2）。
    //   ★ 角色只从 `HouseholdLaborCommitment.productionRole()` 读（D2 §5.6 的唯一权威）；
    //     漏角色 = 多角色同户静默合并，故这里 fail-closed。
    //   ★★★ W5-F：同户可有多个 PeopleLot（commitment id 含 group），故同一个 `(activity, household,
    //     productionRole)` 可能有多行合法承诺。生产契约的贡献键只有 `(household, role)` —— 必须先在
    //     本适配层按该键稳定聚合（首见序 = 输出序），否则合法多 lot 户会被 Step 构造期当成重复键拒掉。
    Map<String, LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>>
        aggregatedByUnit = new LinkedHashMap<>();
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      // ★★★ W5-A（缺陷：GOV_SERVICE 被当成生产承诺）：本驱动只接 `LaborCommitmentKind.PRODUCTION`。
      //   `GOV_SERVICE` 按契约**禁止** productionRole（见 HouseholdLaborCommitment 的 kind 注），也不喂任何生产
      //   unit ⇒ 必须在角色校验与生产提交之前跳过：既不抛，也不进 contributionsByUnit。
      //   ★ 本判断**只**收窄本驱动的输入面：GOV_SERVICE 的配额/守恒/政府逻辑不在本方法，逐字不受影响。
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue;
      }
      long amount = commitment.laborMilli();
      if (amount <= 0L) {
        continue;
      }
      if (commitment.productionRole().isEmpty()) {
        throw new IllegalStateException(
            "生产承诺缺 productionRole（D2 §5.6 不变量被绕开；状态已被改坏）: id="
                + commitment.id().value()
                + " activity="
                + commitment.activity());
      }
      io.mosire.simos.production.contract.ProductionParticipantKey participantKey =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              commitment.household(), commitment.productionRole().get());
      LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          byParticipant =
              aggregatedByUnit.computeIfAbsent(
                  commitment.activity(), ignored -> new LinkedHashMap<>());
      Long previous = byParticipant.get(participantKey);
      byParticipant.put(
          participantKey, previous == null ? amount : Math.addExact(previous, amount));
    }
    Map<String, List<io.mosire.simos.production.contract.ParticipantContribution>>
        contributionsByUnit = new LinkedHashMap<>();
    for (Map.Entry<
            String,
            LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>>
        unitEntry : aggregatedByUnit.entrySet()) {
      List<io.mosire.simos.production.contract.ParticipantContribution> contributions =
          new ArrayList<>();
      for (Map.Entry<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          participantEntry : unitEntry.getValue().entrySet()) {
        contributions.add(
            new io.mosire.simos.production.contract.ParticipantContribution(
                participantEntry.getKey().household(),
                participantEntry.getKey().role(),
                participantEntry.getValue()));
      }
      contributionsByUnit.put(unitEntry.getKey(), contributions);
    }
    // ② 按**稳定序**（实例 id 升序）建步表 —— 序是内容的纯函数（引擎不重排）。
    List<ProductionUnitId> ordered = new ArrayList<>(stepUnits.keySet());
    ordered.sort(Comparator.comparing(ProductionUnitId::value));
    Map<ProductionUnitId, io.mosire.simos.economy.model.ProductionRoster> rosters =
        session.sheet().productionRostersOrBase();
    Map<HouseholdId, io.mosire.simos.economy.model.HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomiesOrBase();
    List<io.mosire.simos.production.contract.ProductionDayRequest.Step> steps = new ArrayList<>();
    Map<ProductionUnitId, String> assetScopeRejections = new LinkedHashMap<>();
    java.util.Optional<AssetOccupancyBudget> d4Budget = settlementIndex.d4AssetOccupancyBudget();
    for (ProductionUnitId id : ordered) {
      ProductionProcess unit = stepUnits.get(id);
      // ★★★ D4-Ib2a：预计/排队/执行只认同一份 D4 占用预算；预算缺失 ⇒ 具名不可用，绝不回退旧整池扫描。
      if (d4Budget.isEmpty()) {
        assetScopeRejections.put(id, "asset-occupancy-unavailable");
        continue;
      }
      java.util.Optional<String> assetRejection = d4Budget.get().rejectionOf(id);
      if (assetRejection.isPresent()) {
        assetScopeRejections.put(id, assetRejection.get());
        continue;
      }
      IndustryDefinitionView industry =
          session.sheet().definitions().industry(unit.industry()).orElse(null);
      if (industry == null) {
        // ★ 实例引用悬空 = 契约故障（不是业务拒绝）：本域状态自相矛盾，必须拒绝整个候选提交（I15）。
        //   这里当场抛，让 {@code executeDay} 的同一条口径在 economy 侧也生效（不静默跳过这一格）。
        throw new IllegalStateException(
            "生产实例引用的产业定义不存在（production 当刻权威；状态已被改坏）: unit="
                + id.value()
                + " plan="
                + unit.industry().value());
      }
      io.mosire.simos.economy.model.ProductionRoster roster = rosters.get(id);
      List<io.mosire.simos.production.contract.ParticipantContribution> contributions =
          contributionsByUnit.getOrDefault(id.value(), List.of());
      if (roster != null) {
        // ★★ D2-I17：名册方案的 `ProductionRules.rules` 必须为空（旧 CompensationRule 产出分配禁用）。
        //   双分 = 契约故障，必须在任何写入之前拒绝整个候选提交。
        io.mosire.simos.economy.api.relation.ProductionRules relation =
            session.sheet().relationsOrBase().get(id);
        if (relation != null && !relation.rules().isEmpty()) {
          throw new IllegalStateException(
              "legacy-compensation-rules-present: 名册方案不得同时保留旧补偿规则（D2-I17）: unit="
                  + id.value()
                  + " rules="
                  + relation.rules().size());
        }
        // ★★ 名册与名册内角色的轻量执行前重查（D2 §7.3：execute 前查；完整角色资格由 production 执行面重查）。
        for (io.mosire.simos.production.contract.ParticipantContribution contribution :
            contributions) {
          if (roster.participant(contribution.household(), contribution.role()).isEmpty()) {
            throw new IllegalStateException(
                "role-unknown: 劳动承诺的角色不在名册里（跨切片不一致）: unit="
                    + id.value()
                    + " household="
                    + contribution.household().value()
                    + " role="
                    + contribution.role().value());
          }
        }
      }
      // ★★ 执行入参：逐参与户属性事实（属性桥；读不到 ⇒ 不填 EMPTY 冒充）+ 逐户劳动预算。
      java.util.Set<HouseholdId> participantHouseholds = new java.util.LinkedHashSet<>();
      if (roster != null) {
        participantHouseholds.addAll(roster.households());
        participantHouseholds.add(roster.organizerHousehold());
      }
      for (io.mosire.simos.production.contract.ParticipantContribution contribution :
          contributions) {
        participantHouseholds.add(contribution.household());
      }
      Map<HouseholdId, io.mosire.simos.production.contract.ExternalAttributeFacts>
          participantAttributes = new LinkedHashMap<>();
      Map<HouseholdId, Long> householdLaborBudgetMilli = new LinkedHashMap<>();
      Map<HouseholdId, Long> managementLaborMilli = new LinkedHashMap<>();
      for (HouseholdId household : participantHouseholds) {
        io.mosire.simos.economy.model.HouseholdEconomy row = householdEconomies.get(household);
        if (row == null) {
          throw new IllegalStateException(
              "参与户不在 economy 行表里（跨切片不一致）: unit=" + id.value() + " household=" + household.value());
        }
        session
            .participantAttributeLookup()
            .factsOf(household)
            .ifPresent(facts -> participantAttributes.put(household, facts));
        householdLaborBudgetMilli.put(household, row.laborMilli());
      }
      // ★★ 管理劳动：C2 没有管理劳动来源。countsManagement 角色缺该项 ⇒ 具名业务拒绝（不按 0 静默）。
      if (roster != null) {
        for (io.mosire.simos.economy.model.ProductionParticipant participant :
            roster.participants()) {
          boolean countsManagement =
              industry.roles().stream()
                  .filter(definition -> definition.id().equals(participant.role()))
                  .findFirst()
                  .flatMap(definition -> definition.experienceGrowth())
                  .map(io.mosire.simos.production.contract.ExperienceGrowth::countsManagement)
                  .orElse(false);
          if (countsManagement) {
            assetScopeRejections.put(
                id,
                "management-labor-missing:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value());
            break;
          }
        }
        if (assetScopeRejections.containsKey(id)) {
          continue;
        }
      }
      // ★★★ D4-Ib2a：执行请求写入的占用 = 同一份 budget 的 allocations（不再由本方法重算，
      //   不先给整池、不伪造等分）。空 Map 是合法零分配；有拒绝的 unit 已在上面短路。
      Map<io.mosire.simos.actor.api.asset.AssetKind, Long> assetOccupancy =
          d4Budget.get().allocationOf(id);
      Map<CommodityId, Long> finalInputs = session.drawnProductionInputsOf(id);
      // ★★ 计划级属性主体 = organizer（D2 §6.2）：有属性桥读数才填，读不到时仍传 EMPTY 作为
      //   `attributes`（production 的计划级条件会因缺属性 fail-closed）；participantAttributes 只装权威读数。
      io.mosire.simos.production.contract.ExternalAttributeFacts planAttributes =
          roster == null
              ? io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY
              : participantAttributes.getOrDefault(
                  roster.organizerHousehold(),
                  io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY);
      // ★★ grantedScale 的口径 = **基准规模（投入侧）上限**（§13.8 裁定一）。
      //   本批不额外设上限：economy 的"统一预算"已经落在**实际投入**上（劳动配额 + 实扣原料 + 实际占用），
      //   再叠一个授予上限只会变成第二份口径；计划规模（缩产/停业）的作用点在劳动需求与投入需求那一侧，
      //   那两处已经在分配前生效。⇒ 给 Long.MAX_VALUE（"不额外夹"），分配结果本身就是上限。
      steps.add(
          new io.mosire.simos.production.contract.ProductionDayRequest.Step(
              java.util.Optional.of(id),
              java.util.Optional.empty(),
              Long.MAX_VALUE,
              contributions,
              assetOccupancy,
              finalInputs,
              planAttributes,
              participantAttributes,
              householdLaborBudgetMilli,
              managementLaborMilli));
    }
    io.mosire.simos.production.contract.ProductionDayResult productionDay =
        steps.isEmpty()
            ? io.mosire.simos.production.contract.ProductionDayResult.empty()
            : runtime.executeDay(
                new io.mosire.simos.production.contract.ProductionDayRequest(steps));
    // ★★★ D4-Ib2a：同一 ChangeSet 写 grant/release；被业务拒绝（economy 预检或 production 回执）的 unit
    //   不留下新 grant，释放行照常回填。写工作副本 = 随后 session.build() 的唯一 ChangeSet。
    java.util.Set<ProductionUnitId> rejectedUnits =
        new LinkedHashSet<>(assetScopeRejections.keySet());
    rejectedUnits.addAll(productionDay.rejections().keySet());
    if (d4Budget.isPresent()) {
      applyAssetOccupancyBudget(session, d4Budget.get(), rejectedUnits);
    }
    if (assetScopeRejections.isEmpty()) {
      return productionDay;
    }
    // ★★ R2：业务拒绝与 production 自己的业务拒绝合并，键序仍按 unit id 稳定序；回执/关账表原样保留。
    Map<ProductionUnitId, String> mergedRejections = new LinkedHashMap<>();
    for (ProductionUnitId id : ordered) {
      String assetReason = assetScopeRejections.get(id);
      if (assetReason != null) {
        mergedRejections.put(id, assetReason);
        continue;
      }
      String engineReason = productionDay.rejections().get(id);
      if (engineReason != null) {
        mergedRejections.put(id, engineReason);
      }
    }
    return new io.mosire.simos.production.contract.ProductionDayResult(
        productionDay.receipts(),
        mergedRejections,
        productionDay.closedUnitScales(),
        productionDay.closedGrossOutput(),
        productionDay.closedMintCandidates());
  }

  /** ★★★ D4-Ib2a：把同一份预算的 grant/release 写入 economy 工作副本（最终进同一 ChangeSet）。 */
  private static void applyAssetOccupancyBudget(
      EconomySession session,
      AssetOccupancyBudget budget,
      java.util.Set<ProductionUnitId> rejectedUnits) {
    java.util.Objects.requireNonNull(session, "session");
    java.util.Objects.requireNonNull(budget, "budget");
    java.util.Objects.requireNonNull(rejectedUnits, "rejectedUnits");
    java.util.LinkedHashMap<
            io.mosire.simos.economy.model.AssetOccupancyGrantId,
            io.mosire.simos.economy.model.AssetOccupancyGrant>
        grants = session.sheet().assetOccupancies();
    for (io.mosire.simos.economy.model.AssetOccupancyGrant release : budget.grantsToRelease()) {
      grants.put(release.id(), release);
    }
    for (io.mosire.simos.economy.model.AssetOccupancyGrant grant : budget.grantsToWrite()) {
      if (!rejectedUnits.contains(grant.unitId())) {
        grants.put(grant.id(), grant);
      }
    }
  }

  /** 把"逐家户 × 逐商品"的本地累加器并进全局累加器（整数加法；键序 = 首次出现序）。 */
  private static void mergeGoodsInto(
      Map<HouseholdId, Map<CommodityId, Long>> target,
      Map<HouseholdId, Map<CommodityId, Long>> source) {
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : source.entrySet()) {
      for (Map.Entry<CommodityId, Long> quantity : entry.getValue().entrySet()) {
        addGoods(target, entry.getKey(), quantity.getKey(), quantity.getValue());
      }
    }
  }

  // ── 人口变动回写（R4：社会侧的出生/死亡 → 经济侧的行人口、劳动配额与流水）──────────────────
  //   ★★ W3-fix（2026-10-11）语义收口：本区两条入口（批次侧 applyPopulationChange / 逐户侧
  //     applyHouseholdPopulationDeltasInto）都是**接收社会报来的生死**，经济侧**不判定死亡、不缩放劳动**。
  //     原 applyPopulationChange 的 javadoc 悬在这里（离方法 180 行、语法上根本没挂到它身上）⇒ 已移到方法正上方。

  /**
   * ★★ <b>P2-A §13.4：每个 tick 重算家户时间预算</b>（毫小时）—— 输入 = 协调器从 Social 人口组成 × {@code SocialProvisioning}
   * 的劳动权威（C8；{@code HouseholdLaborTimeTable} 只是 legacy 值载体）现算的「household → 预算」；本方法把它写进 {@code
   * HouseholdEconomy.laborMilli} （唯一投影），并把超预算的家户配额**按比例缩到预算内**（保持 {@code Σallocations ≤ budget}
   * 不变量）。
   *
   * <p>★★ <b>为什么在这里缩</b>：预算每 tick 会随出生/死亡/成年变化；配额是周期粒度的。若只改行预算不缩配额， {@code EconomyData}
   * 的构造期不变量会在下一个 revision 边界当场拒。缩法是确定性的最大余数法 （同权重按 allocation id 升序），<b>不</b>做"缺口优先"的新分配 —— 那个排序属
   * P2-B 的利润率排队。
   *
   * <p>★★ <b>Z7d-1（饥饿折算）</b>：{@code GOV_SERVICE} 承诺是**职位/诉求**，预算被饿少后允许 `Σ GOV_SERVICE >
   * budget`（不缩/不删，C7）；此时先把 GOV_SERVICE 整额留在活表里，PRODUCTION 可用量按 0 处理（只缩/删 PRODUCTION）。有效供给不足由 app
   * 的供给桥按 `min(承诺, 实际劳动)` cap 并记录 underfed。
   */
  static void applyLaborBudgetsInto(
      EconomySession session, Map<HouseholdId, Long> budgetsByHousehold) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(budgetsByHousehold, "budgetsByHousehold");
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    List<HouseholdId> households = new ArrayList<>(budgetsByHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        throw new IllegalStateException("时间预算指向不存在的家户（协调器投影必须与经济行同键）：" + household);
      }
      long budget = Math.max(0L, budgetsByHousehold.getOrDefault(household, 0L));
      householdEconomies.put(
          household,
          householdEconomy.withPopulationAndLabor(householdEconomy.population(), budget));
    }
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    Map<HouseholdId, List<LaborAllocationId>> byHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      byHousehold
          .computeIfAbsent(laborCommitment.household(), ignored -> new ArrayList<>())
          .add(laborCommitment.id());
    }
    for (Map.Entry<HouseholdId, List<LaborAllocationId>> entry : byHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomies.get(entry.getKey());
      if (householdEconomy == null) {
        continue;
      }
      List<LaborAllocationId> ids = new ArrayList<>(entry.getValue());
      ids.sort(Comparator.comparing(LaborAllocationId::value));
      // ★★ Z3a/C7：先整额扣 GOV_SERVICE（不可缩/最高优先级），只对剩余预算里的 PRODUCTION 做比例缩；
      //   Σ GOV_SERVICE > budget ⇒ 具名 LABOR_COMMITMENT_CONTRACT ERROR + fail-closed（绝不静默缩政府承诺）。
      long govServiceSum = 0L;
      long productionSum = 0L;
      for (int i = 0; i < ids.size(); i++) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
        if (laborCommitment == null) {
          continue;
        }
        long amount = Math.max(0L, laborCommitment.laborMilli());
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          govServiceSum = Math.addExact(govServiceSum, amount);
        } else {
          productionSum = Math.addExact(productionSum, amount);
        }
      }
      long total = Math.addExact(govServiceSum, productionSum);
      long budget = Math.max(0L, householdEconomy.laborMilli());
      if (total <= budget || total <= 0L) {
        continue;
      }
      // ★★ Z7d-1：GOV_SERVICE 是职位/诉求，允许饿少后的预算 `budget < Σ GOV_SERVICE`（C7 不缩/不删）；
      //   此时 PRODUCTION 可用量 = 0（只缩/删 PRODUCTION，政府承诺行原样保留）。
      long availableForProduction = Math.max(0L, budget - govServiceSum);
      if (productionSum <= availableForProduction || productionSum <= 0L) {
        continue; // GOV_SERVICE 已整额装下（或 over-budget 但没有可缩 PRODUCTION）：防御性 no-op
      }
      // ★★ D-032 W5-E：实际既有 PRODUCTION 配额超过**当刻**预算 ⇒ 按比例缩到预算内，并留具名读数。
      //   这是实际 labor 不超预算的兜底点之一（另一处是 LaborQueueBook.plan 的预算封顶）。
      if (EconomyLog.settlement().isInfoEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "HOUSEHOLD_LABOR_CAPPED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "stage",
                    "settlement-budget-rescale",
                    "household",
                    entry.getKey().value(),
                    "productionLaborMilli",
                    productionSum,
                    "availableForProductionMilli",
                    availableForProduction,
                    "budgetMilli",
                    budget,
                    "govServiceLaborMilli",
                    govServiceSum,
                    "reason",
                    "household-labor-capped"));
      }
      List<LaborAllocationId> productionIds = new ArrayList<>();
      long[] productionWeights = new long[ids.size()];
      int productionCount = 0;
      for (int i = 0; i < ids.size(); i++) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
        if (laborCommitment == null || laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          continue;
        }
        productionIds.add(ids.get(i));
        productionWeights[productionCount] = Math.max(0L, laborCommitment.laborMilli());
        productionCount++;
      }
      long[] weightArray = new long[productionCount];
      System.arraycopy(productionWeights, 0, weightArray, 0, productionCount);
      long[] parts =
          ProportionalSplit.byDenominator(availableForProduction, weightArray, productionSum);
      for (int i = 0; i < productionIds.size(); i++) {
        LaborAllocationId id = productionIds.get(i);
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(id);
        if (laborCommitment == null) {
          continue;
        }
        if (parts[i] <= 0L) {
          laborCommitments.remove(id);
        } else {
          laborCommitments.put(id, withLaborMilli(laborCommitment, parts[i]));
        }
      }
    }
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：把 app 注入的当日逐户需求写进家户行</b>（{@code naturalNeeds} 的唯一写入点）。
   *
   * <pre>
   * 入参：Map&lt;HouseholdId, Map&lt;CommodityId, Long&gt;&gt;   // app 逐户调 Social.householdNaturalNeeds(...) 的展开结果
   * 逐行：row.naturalNeeds = 入参.get(row.id())          // 缺键 = 空 map = 无需求（调用方给的 map 可能为空）
   * </pre>
   *
   * <p>★★ <b>本方法只换 {@code naturalNeeds}，绝不在这里累加 {@code cycleNaturalNeedMilli}</b>：周期累加按"消费步对
   * 当日粮需求执行一次"（见 {@link #consumeOneHousehold}）；若注入也加一遍，周期分母会翻倍。
   *
   * <p>★ <b>拒绝语义</b>：入参 key 不在经济家户行里 ⇒ 具名 {@link IllegalStateException}（Social/Economy 投影不一致，不静默丢）；
   * 需求表为 null ⇒ 具名 {@link IllegalArgumentException}（无需求用空 map）；map 的键/值/非负由 {@link
   * HouseholdEconomy} 规范构造器当场拒。★ 日志只把计数放 DEBUG（逐户明细由 app 展开日志承担），不刷 INFO；拒绝路径另记 ERROR 具名。
   */
  static void applyNaturalNeedsInto(
      EconomySession session, Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(needsByHousehold, "needsByHousehold");
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : needsByHousehold.entrySet()) {
      HouseholdId household = entry.getKey();
      if (household == null) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-household-key"));
        throw new IllegalArgumentException("自然需求注入含 null 家户键（app 展开表不得含 null）");
      }
      if (!householdEconomies.containsKey(household)) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "unknown-household-row",
                    "household",
                    household));
        throw new IllegalStateException(
            "自然需求注入指向不存在的经济家户行（Social/Economy 投影不一致，拒绝静默丢弃）: " + household);
      }
      if (entry.getValue() == null) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-needs",
                    "household",
                    household));
        throw new IllegalArgumentException(
            "自然需求注入的家户需求表不得为 null（无需求用空 map）: household=" + household);
      }
    }
    for (Map.Entry<HouseholdId, HouseholdEconomy> row : householdEconomies.entrySet()) {
      Map<CommodityId, Long> needs = needsByHousehold.get(row.getKey());
      row.setValue(row.getValue().withNaturalNeeds(needs == null ? Map.of() : needs));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_NATURAL_NEEDS_INJECTED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "households",
                  needsByHousehold.size(),
                  "rows",
                  householdEconomies.size()));
    }
  }

  // ★★★ D-032 B3（§13.13.7 第 8 条 / §13.3 第 2 条）：静态入口 `applyPopulationChange(EconomyData, changes)`
  //   已**删除**（同 `settle`：main 里 0 个调用点、runtime = null 的会话）。会话形态的
  //   `applyPopulationChangeInto(EconomySession, changes)` 保留（`EconomyDayStepper` 走它，runtime 合法）。

  /**
   * ★ session 形态（{@link EconomyDayStepper} 走它）：就地更新工作表与流水，不构造中间状态。
   *
   * <p>★ <b>语义同 {@link #applyPopulationChange}</b>：接收外部报来的逐批次生死；经济侧不判定死亡（见那条的类注第②段）。 {@code
   * deathsParts[j] > 0} 时删债是**经济侧对"人已死"这一既成事实的后果处理**（P5），不是"经济侧决定谁死"。
   */
  static void applyPopulationChangeInto(EconomySession session, List<LotChange> changes) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(changes, "changes");
    if (changes.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    // ★ P2-A A3：成员份额不再是 Economy 状态组件 —— 家户人口组成由调用方作为**只读投影**传入（见 composition）。
    LinkedHashMap<AssetShareId, OwnershipStake> assetShares = session.sheet().assetShares();
    // ★★ P5：死亡删债的两个工作输入 —— 合同表工作副本与「债务人 → 合同 id」的只读索引。
    //   索引只在这里建一次（月度调用）；合同本金在下面逐笔改写时，索引里的 id 仍有效（forgive 只换值不删键）。
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor =
        debts.isEmpty() ? Map.of() : DebtIndex.byDebtor(debts);
    // ★★ R4-B.3a-perf：人口回写是“逐 LotChange 摊到目标家户”，旧实现对每条 change 扫全量配额
    //   （真档月度 6,000+ 条 × 44,000+ 配额）。这里在入口建一次只读索引：批次→unit、格→unit、
    //   unit→配额，随后每条 change 只碰与自己目标集合相关的行。
    SettlementIndex index =
        SettlementIndex.buildWithoutAssetOccupancy(
            session.sheet().units(),
            session.sheet().industries(),
            assetShares,
            laborCommitments,
            householdEconomies,
            null,
            // ★★ R4-E2b：同一会话里 settleOneDayInto 可能刚由进入执行插入新 unit 的 relation ⇒ 读工作副本选择，
            //   不再直读 base.relations()（否则这一次人口回写的经济家户索引会漏掉刚建的 unit）。
            session.sheet().relationsOrBase());
    // 批次 → 它供给的 unit（保序、去重；只认 activity 命中现存 unit 的配额）。
    Map<PeopleLotId, List<ProductionUnitId>> unitsOf = index.unitsByGroup();
    for (LotChange change : changes) {
      if (change.isEmpty()) {
        continue;
      }
      List<ProductionUnitId> targets = unitsOf.getOrDefault(change.group(), List.of());
      if (targets.isEmpty()) {
        // ★★ **兜底：摊到"它住的那一格"的 unit 家户行上**（见 {@link LotChange} 的类注）—— 没有劳动配额的批次
        //   （0-14 岁那一档）照样要吃饭、照样会死。
        //   ★ R4-B.3a-perf：格 → unit 由索引一次给出（旧实现逐 change 全量扫 unit）。
        targets = index.unitsInHex(IndustryHexKeys.hexKey(change.at().q(), change.at().r()));
        if (targets.isEmpty()) {
          continue; // 该格本来就没有任何经济状态（世界还没播种到这里）⇒ 没有可摊的行
        }
      }
      // ★★ **H0/S3：这批人的家户行 = 供给目标产业的配额里、居住类型匹配的 HouseholdLaborCommitment.household 并集**，
      //   **并集去重** —— 不按四阶层枚举、也不读 HouseholdEconomy.view.stratum（阶层写回后仍不漏行/错行）。
      //   农村批次同时供给农业与家庭纺织（两者落在**同一批农村家户行**上）⇒ 不去重就会把它的人与生死**算两遍**。
      List<HouseholdId> keys =
          householdKeysOfLot(
              householdEconomies, targets, ResidenceKind.ofLot(change.group()), index);
      if (keys.isEmpty()) {
        continue; // 那些产业在这一格没有家户行（行还没种下）⇒ 没有可摊的行
      }
      long[] rowPopulations = new long[keys.size()];
      long populationBefore = 0L;
      for (int j = 0; j < keys.size(); j++) {
        rowPopulations[j] = householdEconomies.get(keys.get(j)).population();
        populationBefore += rowPopulations[j];
      }
      long[] birthsParts = allocate(change.births(), rowPopulations);
      long[] deathsParts = allocate(change.deaths(), rowPopulations);
      for (int j = 0; j < keys.size(); j++) {
        HouseholdId key = keys.get(j);
        HouseholdEconomy householdEconomy = householdEconomies.get(key);
        long population = householdEconomy.population();
        if (population <= 0L) {
          continue;
        }
        long remaining = population - deathsParts[j]; // deathsParts ≤ row 人口（按人口权重切，见 allocate）
        long labor = householdEconomy.laborMilli() * remaining / population; // 死亡同比例缩；出生不加劳动
        householdEconomies.put(
            key,
            withPopulationAndLabor(
                householdEconomy, remaining + birthsParts[j], Math.max(0L, labor)));
        if (birthsParts[j] != 0L || deathsParts[j] != 0L) {
          flows.put(key, withLifecycle(flows.get(key), key, birthsParts[j], deathsParts[j]));
        }
        // ★★ P5：死亡按人口比例删债 —— 用**死亡前**的 population 与 survivors = population − deathsParts[j]，
        //   只缩减「debtor == 本行」的活跃合同（逐笔 floor；survivors == 0 ⇒ 全额减免）。不搬粮/钱、不碰账户。
        if (deathsParts[j] > 0L) {
          writeOffDebtsForDeaths(
              debts,
              debtsByDebtor.getOrDefault(key, List.of()),
              key,
              population,
              deathsParts[j],
              session.debtWriteOffs());
        }
      }
      scaleLaborOfGroup(
          change.group(),
          populationBefore,
          populationBefore - change.deaths(),
          laborCommitments,
          index);
      // ★ P2-A A3：出生/死亡只改 HouseholdEconomy.population 与派生量（工时/配额/债务）；家户成员份额由 Social 权威维护。
    }
    // ★★ Z3a/C7：全部批次人口缩放已跑完 —— 政府承诺整额保留后若越预算，统一具名 ERROR + fail-closed。
    //   本方法签名无 day ⇒ 按日志纪律用 system 来源、事件不带 day。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION_WRITE, -1L);
    // ★ 工作表与流水都在 session 里就地更新；构造与全量守卫由 revision 边界（build）负责（P1.5a）。
  }

  /**
   * ★★ <b>2026-10-09 每 tick 生死 Batch B：把 Social 的逐家户净人口变化直接落到经济行</b>（新主路径；旧 {@link
   * #applyPopulationChangeInto} 的批次摊派路径保留给迁移/兼容调用）。
   *
   * <pre>
   * 逐条：row.population += delta      // delta = 出生 − 死亡，由 Social 按 HouseholdId 算好
   *       结果 &lt; 0 或行不存在 ⇒ 具名拒（fail-closed，不静默跳过）
   * </pre>
   *
   * <p>★★ <b>本重载不带生死流水</b>（出生/死亡两表当空表）：调用方是"人在户与户之间搬"的两条路 —— P0 迁移桥（{@code ModeMigrationSettlement}
   * 的 outbox）与 Z7d-2 逃亡（{@code GovernmentServiceDesertionBridge}）。搬人不产生出生/死亡， 故 {@code
   * FlowRow.births}/{@code FlowRow.deaths} 不该因它变化。★ 逐 tick 的**生死**走五参重载 {@link
   * #applyHouseholdPopulationDeltasInto(EconomySession, Map, Map, Map, long)}。
   *
   * <p>★★ <b>为什么不像旧路径那样摊批次/缩劳动/删债</b>：调用方（app 日循环）在调用本方法**之前**已经用新 Social 刷新了 {@code composition} /
   * {@code laborMilli} / {@code naturalNeeds}——劳动权威已经是结算后 Social；这里再按人口 比例缩一次会把当日权威缩两遍。人口 delta
   * 只改行人口这一项投影，其它派生量由调用方的刷新与后续 step 承担。
   *
   * <p>★ <b>拒绝语义</b>：null 键/值、0 delta、缺经济行、结果为负都具名拒（ERROR 日志 + 异常）；空表是合法输入 （当天无生死）。★ 汇总只记
   * DEBUG（逐户明细由 Social 侧事件日志承担）。
   *
   * <p>★★ <b>W8（2026-10-11）：本重载是"迁移把某户搬空"的入口</b> —— 两表恒空 ⇒ 五参重载里的户绝判据②（社会报来的死亡 &gt; 0）**恒不成立** ⇒
   * 只管到"债务保留"那一条具名 INFO（{@code HOUSEHOLD_EMPTIED_DEBT_RETAINED}），
   * <b>绝不销账</b>。这正是"迁移不销账、只有死亡导致的归零才销账"那条判据的落点。
   *
   * <p>★ <b>D4（W3-fix2，2026-10-11）：本重载不带世界日</b>——它的两表恒空 ⇒ {@code HOUSEHOLD_VITAL_WRITEBACK}
   * 永不发射（见下面的守卫），故 {@link #NO_WORLD_DAY} 不进任何输出。真发生生死的路径必须给真日。
   */
  static void applyHouseholdPopulationDeltasInto(
      EconomySession session, Map<HouseholdId, Long> deltas) {
    applyHouseholdPopulationDeltasInto(session, deltas, Map.of(), Map.of(), NO_WORLD_DAY);
  }

  /**
   * ★ <b>"这条调用路径没有世界日"的哨兵</b>（只给"人在户与户之间搬"那条路用；那条路 births/deaths 恒空 ⇒ {@code
   * HOUSEHOLD_VITAL_WRITEBACK} 永不发射）。★ <b>D4</b>：真发生生死的调用必须给真日 —— 否则下面的守卫当场拒， 绝不把假日写进日志。
   */
  private static final long NO_WORLD_DAY = -1L;

  /**
   * ★★★ <b>W3-fix（2026-10-11）：把 Social（人口权威）报来的逐户净变化 + 逐户出生/死亡一起落到经济侧</b>。
   *
   * <pre>
   * ① row.population += delta                            // 净变化（出生 − 死亡）
   * ② FlowRow.births += birthsByHousehold[household]     // 社会报来的出生原值 → 流水
   *    FlowRow.deaths += deathsByHousehold[household]     // 社会报来的死亡原值 → 流水（含饥荒那一份）
   * ③ ★★ W8：本行人口由 &gt; 0 变 0 且**社会报来的死亡 &gt; 0** ⇒ 户绝 ⇒ 该户作为债务人/债权人的账**双向销账**
   *         （{@link #writeOffExtinctHouseholdDebts}）；由**迁移**归零的只具名记"债务保留"，不销账。
   * </pre>
   *
   * <p>★★ <b>为什么要有 ②</b>：净变化读不出"死了多少人"（一户同时生死时只报差），而经济侧的危机读数与流水要的正是死亡原值； 只报净变化 ⇒ {@code
   * FlowRow.deaths} 永远是 0 ⇒ {@code CrisisMonitor} 的 MORTALITY 灯**永远不会亮**（W3b 报的读数缺口）。
   * 本步把它接上：**经济侧仍然一个字都不判**（判死在 Social），只把社会报来的数落进流水（唯一写口 {@link #withLifecycle}）。 ★ <b>{@code
   * VitalSettlementResult#famineDeaths()} ≤ 这里的死亡人数</b>（它是"cause 含饥荒的死亡人数"，W3-fix2 / D2 + W3-fix3
   * 裁定），故"饿死多少"在经济读数里也读得到；明细仍在 Social 的 {@code FAMINE_MORTALITY} 日志里。
   *
   * <p>★ <b>不做</b>：不摊批次、不缩劳动（劳动权威是结算后的 Social，调用方已刷新）、不改 {@code naturalNeeds}。 ★★ <b>W8
   * 改口（2026-10-11）：本方法<b>现在会删债</b></b> —— 改前这里的 javadoc 写着"不删债"，那正是调查报告 D1 指出的缺陷（活路径不核销、核销路径不可达）。现在
   * ③ 只在"**死亡**把本行打到 0"时删债（双向），迁移归零照旧不删。
   *
   * <p>★★ <b>拒绝语义（fail-closed，全部在写任何一行之前）</b>：三张表的 null 键/值、0 值、值 &lt; 0、缺经济行，以及 "净变化 ≠ 出生 −
   * 死亡"（逐户交叉核对）都具名拒（ERROR 日志 + 异常）。★ 三张表都空是合法输入（当天无生死、无人搬家）。
   *
   * <p>★ <b>日志</b>：报来的死亡 &gt; 0 ⇒ 一条 INFO「这一轮死了多少人、落在哪些行」（§一.9：生命周期事件走 INFO）； 只有出生 ⇒ 同事件名
   * DEBUG。逐户明细由 Social 侧事件日志承担。
   *
   * @param deltas 逐家户净人口变化（出生 − 死亡；只含非 0 项）；不得为 null
   * @param birthsByHousehold 逐家户出生原值（只含 &gt; 0 的家户）；不得为 null
   * @param deathsByHousehold 逐家户死亡原值（只含 &gt; 0 的家户；<b>含饥荒那一份</b>）；不得为 null
   * @param day ★★ <b>W3-fix2（D4，2026-10-11）：世界日</b> —— {@code HOUSEHOLD_VITAL_WRITEBACK} 的 {@code
   *     day} 字段（改前 162/162 条都没有该字段 ⇒ 这条读数无法按天检索/对账）；<b>真发生生死的调用必须 ≥ 0</b> （{@link #NO_WORLD_DAY}
   *     只许给两表恒空的搬迁路径）
   */
  static void applyHouseholdPopulationDeltasInto(
      EconomySession session,
      Map<HouseholdId, Long> deltas,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(deltas, "deltas");
    Objects.requireNonNull(birthsByHousehold, "birthsByHousehold");
    Objects.requireNonNull(deathsByHousehold, "deathsByHousehold");
    // ★★ D4 守卫（fail-closed）：只有"真的要把生死写进流水"时才需要世界日 —— 缺了它绝不用假日凑数。
    if (day < 0L && (!birthsByHousehold.isEmpty() || !deathsByHousehold.isEmpty())) {
      throw new IllegalArgumentException(
          "HOUSEHOLD_VITAL_WRITEBACK 需要世界日（day >= 0），但调用方给的是 day="
              + day
              + "（births="
              + birthsByHousehold.size()
              + " 户, deaths="
              + deathsByHousehold.size()
              + " 户）");
    }
    if (deltas.isEmpty() && birthsByHousehold.isEmpty() && deathsByHousehold.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    // ① 先做全量校验：任何坏输入都在写任何一行之前具名拒（不留半落账状态）。
    long deltaSum = 0L;
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      Long delta = entry.getValue();
      if (household == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-household-key"));
        throw new IllegalArgumentException("逐户人口变化含 null 家户键（Social 结算结果不得含 null）");
      }
      if (delta == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化的值不得为 null: household=" + household);
      }
      if (delta == 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "zero-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化不应含 0（只给非 0 净变化）: household=" + household);
      }
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "unknown-household-row",
                    "household",
                    household));
        throw new IllegalStateException(
            "逐户人口变化指向不存在的经济家户行（Social/Economy 投影不一致，拒绝静默丢弃）: " + household);
      }
      long nextPopulation = Math.addExact(row.population(), delta);
      if (nextPopulation < 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "negative-result",
                    "household",
                    household,
                    "population",
                    row.population(),
                    "delta",
                    delta,
                    "next",
                    nextPopulation));
        throw new IllegalStateException(
            "逐户人口变化后结果为负（拒绝落账）: household="
                + household
                + " population="
                + row.population()
                + " delta="
                + delta);
      }
      deltaSum = Math.addExact(deltaSum, delta);
    }
    // ①b ★★ W3-fix：社会报来的两张生死原值表也在这里一次校验（同样是"写任何一行之前"）。
    requireReportedVitalCounts(birthsByHousehold, "birthsByHousehold", householdEconomies);
    requireReportedVitalCounts(deathsByHousehold, "deathsByHousehold", householdEconomies);
    // ①c 交叉核对：净变化必须等于"报来的出生 − 报来的死亡"（缺键按 0 计）。Social 侧的同一条不变量在这里做**接收侧复核** ——
    //   两侧对不上就当场拒，绝不让"落进人口的是一个数、落进流水的是另一个数"这种半落账状态存在。
    Set<HouseholdId> lifecycleHouseholds = new LinkedHashSet<>(birthsByHousehold.keySet());
    lifecycleHouseholds.addAll(deathsByHousehold.keySet());
    for (HouseholdId household : lifecycleHouseholds) {
      long births = birthsByHousehold.getOrDefault(household, 0L);
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      long delta = deltas.getOrDefault(household, 0L);
      if (Math.subtractExact(births, deaths) != delta) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_VITAL_WRITEBACK_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "lifecycle-delta-mismatch",
                    "household",
                    household,
                    "births",
                    births,
                    "deaths",
                    deaths,
                    "delta",
                    delta));
        throw new IllegalArgumentException(
            "报来的逐户生死与净人口变化不一致（拒绝落账）: household="
                + household
                + " births="
                + births
                + " deaths="
                + deaths
                + " delta="
                + delta);
      }
    }
    // ② 校验全过后再写：population += delta；laborMilli 原样（调用方已按新 Social 重算）。
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      HouseholdEconomy row = householdEconomies.get(household);
      long nextPopulation = Math.addExact(row.population(), entry.getValue());
      householdEconomies.put(
          household, withPopulationAndLabor(row, nextPopulation, row.laborMilli()));
    }
    // ③ ★★ W3-fix：社会报来的**出生/死亡**逐行追进流水（唯一写口 withLifecycle）。
    //   键序 = 家户行序（与 settleOneDayInto 的流水键序同一规则）⇒ 状态键序仍是输入的确定性函数。
    //   ★ 这里只"落账"：不判定谁死、不缩劳动、不删债 —— 那些都已由 Social 判定/由调用方刷新。
    if (!birthsByHousehold.isEmpty() || !deathsByHousehold.isEmpty()) {
      long reportedBirths = 0L;
      long reportedDeaths = 0L;
      long lifecycleRows = 0L;
      for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
        HouseholdId household = entry.getKey();
        long births = birthsByHousehold.getOrDefault(household, 0L);
        long deaths = deathsByHousehold.getOrDefault(household, 0L);
        if (births == 0L && deaths == 0L) {
          continue; // 绝大多数行与今天无关：不碰它们的流水（保住"没发生额就不建行"）
        }
        flows.put(household, withLifecycle(flows.get(household), household, births, deaths));
        reportedBirths = Math.addExact(reportedBirths, births);
        reportedDeaths = Math.addExact(reportedDeaths, deaths);
        lifecycleRows++;
      }
      // ★ §一.9：死亡是生命周期事件 ⇒ INFO；只有出生 ⇒ DEBUG（同事件名、同字段，避免每天一条 INFO 噪声）。
      //   ★★ D4（W3-fix2）：补 `day` —— 与同写口的邻居（POPULATION_SETTLE / FAMINE_MORTALITY /
      //   HOUSEHOLD_GAP_*）同字段名同语义，这条读数从此可按天检索/对账。
      LogEvent vital =
          LogEvent.of(
              "HOUSEHOLD_VITAL_WRITEBACK",
              EconomyLogSource.ECONOMY_POPULATION_WRITE,
              "day",
              day,
              "households",
              lifecycleRows,
              "births",
              reportedBirths,
              "deaths",
              reportedDeaths,
              "unit",
              "person",
              "why",
              "w3-fix: social-is-population-authority; economy-receives-births-deaths-into-flowrow");
      if (reportedDeaths > 0L) {
        EventLog.channel(EconomyLog.population()).info(vital);
      } else if (EconomyLog.population().isDebugEnabled()) {
        EventLog.channel(EconomyLog.population()).debug(vital);
      }
    }
    if (EconomyLog.population().isDebugEnabled()) {
      EventLog.channel(EconomyLog.population())
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_POPULATION_DELTAS_APPLIED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "households",
                  deltas.size(),
                  "deltaNet",
                  deltaSum,
                  "unit",
                  "person"));
    }
    // ④ ★★★ W8（2026-10-11 用户裁定「穷人的话，死了账一并消除」）：**户绝核销接通到活路径**。
    //   改前：核销只在批次入口 applyPopulationChangeInto（死代码）里，活路径一个字都不动 ⇒ 两个世界 × 600 天
    //   DEBT_FORGIVE = 0，唯一一例死亡户绝（hh-2_-2-rural-landlord，d550）的债到 d600 变成 ×153.93（调查报告 D1/D2）。
    //   ★ 判据（唯一，且必须用**社会侧权威**）：
    //     ① 本行人口**由 > 0 变 0**（populationBefore = −delta > 0 且写后 == 0）；
    //     ② 且**社会报来的死亡 > 0**（deathsByHousehold）⇒ 这是"人死光了"，不是"人搬走了"。
    //   ★ 为什么②必须有（调查报告 §2.2 的口径陷阱）：本世界里"人口 → 0"里 **19/21 是迁移**。迁移时债务按比例
    //     随人走（LotMigrationBook.moveDebts / ModeMigrationSettlement.moveDebt，目标恰是债权人时走自债净额）
    //     ⇒ 迁移归零**不销账**（用户口径），只具名记一条"空了、债务保留"。区分落在这条 if 上，不靠形态学猜。
    //   ★ 键序 = 家户行序（deltas 只用来查"这一行今天动没动"）—— 与 ③ 的流水键序同一规则 ⇒ 确定性 I7
    //     （不引入 Map.copyOf/Set.copyOf，也不按调用方给的表序走）。
    //   ★ 两个分支都真实可达：迁移走二参重载（births/deaths 两表恒空 ⇒ deaths == 0），生死走五参重载。
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId household = entry.getKey();
      Long delta = deltas.get(household);
      if (delta == null || entry.getValue().population() != 0L) {
        continue; // 这一行今天没动、或没归零 ⇒ 与户绝无关
      }
      long populationBefore = Math.negateExact(delta);
      if (populationBefore <= 0L) {
        continue; // 本来就是空户（空槽位）⇒ 不是"归零"事件，不销账
      }
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      if (deaths > 0L) {
        writeOffExtinctHouseholdDebts(session, household, populationBefore, deaths, day);
      } else {
        logEmptiedHouseholdDebtRetained(session, household, populationBefore, day);
      }
    }
  }

  /**
   * ★★★ <b>W8：户绝 ⇒ <b>双向</b>销账（用户裁定「穷人的话，死了账一并消除」）</b>。
   *
   * <pre>
   * ① 债务人侧：复用 P5 既有语义（{@link #writeOffDebtsForDeaths}，population = 死亡前人口、deaths = 社会报来的死亡）
   *    —— 本分支只在"写后人口 == 0"时进入 ⇒ deaths ≥ populationBefore ⇒ survivors = populationBefore − deaths ≤ 0
   *    ⇒ 逐笔**全额减免**（forgive 到 0 ⇒ 状态 FORGIVEN），写口仍是 {@link DebtContractBook#forgive}。
   * ② 债权人侧：该户作为**债权人**的全部应收一并减免（同一条写口，原因 {@link #CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON}）。
   * </pre>
   *
   * <p>★★ <b>为什么②是控制方口径、可被用户推翻</b>：用户原话只说了"死了账一并消除"，没说方向。本批按双向实现， 理由是户绝后<b>没有任何主体</b>能主张或承担这笔账 ——
   * 留着它就会出现调查报告 §2.5 那种读数："一个 0 人口的家户 仍是 1.04×10¹² silver 的债权人，而活着的债务人还在为它复利"。★ 两个方向用**不同**的 reason
   * 常量命名 ⇒ 用户日后 只推翻一侧时，审计/读口能把它们分开。★ 见实现账本的"偏离记录"。
   *
   * <p>★★ <b>只动本金与状态</b>：不碰账户、库存、发行审计、转移流水（调查报告 §2.8 已证 {@code forgive} 的语义如此） ⇒ 货币守恒读数逐值不变；变的是
   * {@code debtContracts} 本金合计、{@code DebtStock.totalPrincipal} 与会话瞬态 {@code debtWriteOffs}。
   *
   * <p>★ <b>{@code debtWriteOffs} 的键仍是债务人</b>（那是 {@link EconomySession#debtWriteOffs()} 的既有契约： "键 =
   * 本金被删掉的债务人"）。债权人侧消除的受益人是**债务人**（它的本金掉了）⇒ 记在债务人名下，方向由本方法的 具名 INFO 的 {@code
   * creditorPrincipalByUnit} 字段区分，不靠累加器的键。
   *
   * @param session 经济会话（就地更新合同表工作副本与瞬态删债累加器）
   * @param household 户绝的家户（= 债务人侧与债权人侧的两个身份）
   * @param populationBefore 户绝前人口（&gt; 0）
   * @param deaths 社会报来的死亡数（&gt; 0；本分支保证 ≥ populationBefore）
   * @param day 世界日（具名 INFO 的 day 字段）
   */
  private static void writeOffExtinctHouseholdDebts(
      EconomySession session, HouseholdId household, long populationBefore, long deaths, long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(household, "household");
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<HouseholdId, Long> debtWriteOffs = session.debtWriteOffs();
    // ★ 先量后删：本分支保证 survivors ≤ 0 ⇒ 逐笔全额减免，故"删前本金"逐值等于"被减免额"（不重写 P5 的算式）。
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor =
        debts.isEmpty() ? Map.of() : DebtIndex.byDebtor(debts);
    LinkedHashMap<String, Long> debtorPrincipalByUnit = new LinkedHashMap<>();
    long debtorContracts = 0L;
    for (DebtContractId id : debtsByDebtor.getOrDefault(household, List.of())) {
      DebtContract debt = debts.get(id);
      if (debt == null || !debt.debtor().equals(household) || debt.principal() <= 0L) {
        continue;
      }
      debtorPrincipalByUnit.merge(debt.unit().key(), debt.principal(), Math::addExact);
      debtorContracts++;
    }
    // ① 债务人侧（复用 P5：population=死亡前人口、deaths=社会报来的死亡 ⇒ survivors=0 ⇒ 全额减免）。
    writeOffDebtsForDeaths(
        debts,
        debtsByDebtor.getOrDefault(household, List.of()),
        household,
        populationBefore,
        deaths,
        debtWriteOffs);
    // ② 债权人侧（控制方口径）：户绝后没有主体能主张这笔应收。
    LinkedHashMap<String, Long> creditorPrincipalByUnit = new LinkedHashMap<>();
    long creditorContracts = 0L;
    if (!debts.isEmpty()) {
      for (Map.Entry<HouseholdId, List<DebtContractId>> side :
          DebtIndex.byCreditor(debts).entrySet()) {
        if (!side.getKey().equals(household)) {
          continue;
        }
        for (DebtContractId id : side.getValue()) {
          DebtContract debt = debts.get(id);
          if (debt == null || !debt.creditor().equals(household)) {
            continue;
          }
          long principal = debt.principal();
          if (principal <= 0L) {
            continue; // 含自持合同（debtor == creditor == 本户）：① 已把它全额减免 ⇒ 这里本金已是 0，不重复计
          }
          DebtContractBook.forgive(debts, id, principal, CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON);
          debtWriteOffs.merge(debt.debtor(), principal, Math::addExact);
          creditorPrincipalByUnit.merge(debt.unit().key(), principal, Math::addExact);
          creditorContracts++;
        }
      }
    }
    EventLog.channel(EconomyLog.population())
        .info(
            LogEvent.of(
                "HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF",
                EconomyLogSource.ECONOMY_POPULATION_WRITE,
                "day",
                day,
                "household",
                household.value(),
                "populationBefore",
                populationBefore,
                "deaths",
                deaths,
                "cause",
                "death",
                "debtorSideContracts",
                debtorContracts,
                "debtorPrincipalByUnit",
                debtorPrincipalByUnit,
                "creditorSideContracts",
                creditorContracts,
                "creditorPrincipalByUnit",
                creditorPrincipalByUnit,
                "why",
                "w8:extinct-household-debt-and-receivable-eliminated (social-authoritative deaths>0)"));
  }

  /**
   * ★★ <b>W8：某户人口归零但<b>不是死亡</b>（迁移/搬走）⇒ 具名记"债务保留"</b>——这是"迁移不销账"那条判据的 可观测面（与 {@link
   * #writeOffExtinctHouseholdDebts} 的 INFO 成对；调查报告 D6：不加分辨就会把阶层/户籍流动读成户绝）。
   *
   * <p>★ 债务按比例随人走（{@code LotMigrationBook.moveDebts} / {@code ModeMigrationSettlement.moveDebt}），
   * 留在空户名下的那部分**不减记**、但也不再计息（W8 ③：{@code chargeInterest} 跳过 population ≤ 0 的行）。
   *
   * <p>★ <b>day 字段只在知道世界日时写</b>（D4 纪律：绝不拿假日凑数）—— 迁移走二参重载时调用方给的是 {@link #NO_WORLD_DAY}，此时本事件**不带
   * day**（与 {@code HOUSEHOLD_VITAL_WRITEBACK} 的守卫同一口径）。
   */
  private static void logEmptiedHouseholdDebtRetained(
      EconomySession session, HouseholdId household, long populationBefore, long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(household, "household");
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<String, Long> retainedPrincipalByUnit = new LinkedHashMap<>();
    long contracts = 0L;
    for (DebtContract debt : debts.values()) {
      if (!debt.debtor().equals(household) || debt.principal() <= 0L) {
        continue;
      }
      retainedPrincipalByUnit.merge(debt.unit().key(), debt.principal(), Math::addExact);
      contracts++;
    }
    List<Object> fields = new ArrayList<>();
    if (day >= 0L) {
      fields.add("day");
      fields.add(day);
    }
    fields.add("household");
    fields.add(household.value());
    fields.add("populationBefore");
    fields.add(populationBefore);
    fields.add("cause");
    fields.add("relocation-not-death");
    fields.add("contracts");
    fields.add(contracts);
    fields.add("retainedPrincipalByUnit");
    fields.add(retainedPrincipalByUnit);
    fields.add("why");
    fields.add("w8:empty-household-without-deaths-keeps-debt (migration-carries-its-share)");
    EventLog.channel(EconomyLog.population())
        .info(
            LogEvent.of(
                "HOUSEHOLD_EMPTIED_DEBT_RETAINED",
                EconomyLogSource.ECONOMY_POPULATION_WRITE,
                fields.toArray()));
  }

  /**
   * ★★ <b>W3-fix：校验"社会报来的逐户生死原值表"</b>（键必须落在经济家户行上；值非 null、&gt; 0）——坏项在写任何一行之前具名拒 （ERROR 日志 + 异常，与逐户
   * delta 表同一条 fail-closed 口径）。
   *
   * <p>★ <b>为什么键必须在行上</b>：本步只往**已存在的家户行**落流水（不凭空建行）—— 报来一个没有行的家户 = Social/Economy
   * 投影不一致的装配故障，静默跳过会让"社会说死了人、经济流水里没有"这种半落账状态留下来。
   *
   * @param counts 逐户出生或死亡原值表（只含 &gt; 0 的家户）；不得为 null
   * @param field 表名（只进日志/异常文本，便于具名定位）
   * @param householdEconomies 经济家户行工作表
   */
  private static void requireReportedVitalCounts(
      Map<HouseholdId, Long> counts,
      String field,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies) {
    for (Map.Entry<HouseholdId, Long> entry : counts.entrySet()) {
      HouseholdId household = entry.getKey();
      Long count = entry.getValue();
      String reason = null;
      if (household == null) {
        reason = "null-household-key";
      } else if (count == null) {
        reason = "null-count";
      } else if (count < 0L) {
        reason = "negative-count";
      } else if (count == 0L) {
        reason = "zero-count";
      } else if (!householdEconomies.containsKey(household)) {
        reason = "unknown-household-row";
      }
      if (reason == null) {
        continue;
      }
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "HOUSEHOLD_VITAL_WRITEBACK_REJECTED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "reason",
                  reason,
                  "field",
                  field,
                  "household",
                  household == null ? "-" : household));
      throw new IllegalArgumentException(
          "报来的逐户生死原值表不合法（拒绝落账）: field="
              + field
              + " reason="
              + reason
              + " household="
              + household
              + " count="
              + count);
    }
  }

  /**
   * ★★ <b>P5：死亡按人口比例删债（逐笔整数口径）</b>——只缩减「{@code debtor ==} 本行」的全部活跃合同。
   *
   * <pre>
   * survivors   = population − deaths                     // population = 本行死亡前人口
   * newPrincipal = survivors == 0 ? 0 : ⌊principal × survivors ÷ population⌋   // 逐笔向下取整
   * forgiven     = principal − newPrincipal                // 0 本金合同不产生"空减免"
   * </pre>
   *
   * <p>★★ <b>为什么逐笔算、不先合计再摊</b>：合同是各自独立的连续欠账，逐笔 floor 是探针口径的逐值投影 （探针 {@code household.debt = debt ×
   * afterPop ÷ beforePop} 也是整户一笔、直接乘除）。同一家户多笔合同用 <b>同一个</b> survivors/population ⇒ 比例一致；合计删债 = Σ
   * 逐笔差值（由调用方逐笔累加，不再二次取整）。
   *
   * <p>★★ <b>只走唯一写口</b>：{@link DebtContractBook#forgive} 负责本金下溢守卫、状态迁移（减到 0 ⇒ {@code
   * FORGIVEN}）与具名原因；本方法<b>不碰</b>账户、库存、流水或任何发行审计 —— 死亡删债不是还款、也不是发行。
   *
   * <p>★ <b>确定性</b>：{@code debtorContracts} 来自只读索引（按 id 规范串排序，见 {@link DebtIndex#byDebtor}），
   * 且逐笔只依赖自己的本金；因此同一份输入无论分区/迭代序都给出同一组删债额。
   *
   * @param debts 合同表工作副本（就地更新；唯一写口）
   * @param debtorContracts 该债务人的合同 id 清单（只读派生；合同表仍是权威）
   * @param debtor 本行家户（＝债务人）
   * @param population 本行死亡前人口（&gt; 0）
   * @param deaths 本行摊到的死亡数（**社会报来的死亡**按人口权重摊到本行的那一份；≤ population；来自 {@link #allocate}）——
   *     它是"经济侧为**已死的人**做债务善后"的输入，不是"经济侧决定死多少人"（判死在 Social）
   * @param debtWriteOffs 会话瞬态删债累加器（就地更新；键 = 债务人）
   */
  private static void writeOffDebtsForDeaths(
      Map<DebtContractId, DebtContract> debts,
      List<DebtContractId> debtorContracts,
      HouseholdId debtor,
      long population,
      long deaths,
      LinkedHashMap<HouseholdId, Long> debtWriteOffs) {
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(debtor, "debtor");
    Objects.requireNonNull(debtWriteOffs, "debtWriteOffs");
    if (deaths <= 0L || debtorContracts.isEmpty()) {
      return;
    }
    if (population <= 0L) {
      throw new IllegalStateException(
          "死亡删债的死亡前人口必须 > 0：家户=" + debtor + " 人口=" + population + " 死亡=" + deaths);
    }
    long survivors =
        population - deaths; // 调用方保证 deaths ≤ population（allocate 按人口权重切，见 allocate 的注释）
    for (DebtContractId id : debtorContracts) {
      DebtContract debt = debts.get(id);
      if (debt == null || !debt.debtor().equals(debtor)) {
        continue; // 索引是只读派生；合同表是权威。取不到/债务人漂开都不在这里"顺手修"
      }
      long principal = debt.principal();
      if (principal <= 0L) {
        continue; // 已结清/已减免/无本金：没有可删的活跃本金
      }
      long after =
          survivors <= 0L ? 0L : proportionalPrincipalAfterDeaths(principal, survivors, population);
      long forgiven = principal - after;
      if (forgiven <= 0L) {
        continue; // 人口太小、floor 后本金未变：不写 0 减免（forgive 的 amount 必须 > 0）
      }
      DebtContractBook.forgive(debts, id, forgiven, DEATH_DEBT_WRITE_OFF_REASON);
      debtWriteOffs.merge(debtor, forgiven, Math::addExact);
    }
  }

  /**
   * ★ P5：{@code ⌊principal × survivors ÷ population⌋} 的**不溢出**逐笔整数算法（结果与探针的直接乘除逐值相同）。
   *
   * <pre>
   * whole = principal ÷ population；remainder = principal mod population
   * result = whole × survivors + ⌊remainder × survivors ÷ population⌋
   * </pre>
   *
   * <p>{@code whole × survivors ≤ principal}（因 {@code survivors ≤ population}）⇒ 主项不溢出；尾项 {@code
   * remainder × survivors} 用 {@link Math#multiplyExact} 在极端值上 fail-closed（不静默绕回）。
   */
  private static long proportionalPrincipalAfterDeaths(
      long principal, long survivors, long population) {
    if (survivors <= 0L) {
      return 0L;
    }
    if (survivors >= population) {
      return principal;
    }
    long whole = principal / population;
    long remainder = principal % population;
    long tail = Math.multiplyExact(remainder, survivors) / population;
    return Math.addExact(Math.multiplyExact(whole, survivors), tail);
  }

  // ★★★ B5（§13.15.4 裁定二十）：原三个 **main + test 全仓 0 调用点**的公开读口
  //   —— `industriesAt(definitions, HexCoord)` / `industriesAt(industriesByHex, HexCoord)` /
  //   `industriesByHex(definitions)` —— **整段删除**（能力不再"静默保留"：0 调用点的公开面 = 噪声）。
  //   ★ 日结算真正在用的那条路**一字未动**：`SettlementIndex` 自己建同一份"格 → 产业"索引
  //     （`SettlementIndex:635` 的私有 `industriesByHex(industries)`，由 `:193` 从当刻定义来源构建），
  //     逐日 `settlementIndex.industriesByHex()` 读它。

  /**
   * ★★ <b>{@code FlowRow.births}/{@code FlowRow.deaths} 的唯一写口</b>：把**外部（社会，人口权威）报来的**出生/死亡原值
   * 追加进一条流水行 ——**保留**该行本期的其它发生额（不覆盖）。
   *
   * <p>★★ <b>语义（W3-fix 2026-10-11）：接收方，不是判定方</b>：本方法**不产生**任何生死判定，{@code births}/{@code deaths}
   * 两个数由调用方（{@code VitalSettlementResult} 的逐户原值 / {@code LotChange} 的逐批次账）原样传来；经济侧也不在这里缩劳动、 不删债 ——
   * 它只把"社会说死了/生了多少"落进流水读数（口径见 {@link FlowRow#deaths()}）。
   *
   * <p>★ 流水行缺席（该行本期还没有任何发生额）⇒ 以一条全零的流水起步：{@code FlowRow} 的其余字段本来就有合法零值， 而"这一行这个月死了人"必须**读得出来**。
   *
   * <p>★ 累加口径 = 本周期累计（新周期第一天由 {@code settleOneDayInto} 整行归零，与 {@code unmetNeed} 同窗口）。
   */
  private static FlowRow withLifecycle(FlowRow flow, HouseholdId key, long births, long deaths) {
    FlowRow base =
        flow == null
            ? new FlowRow(
                key, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, 0L, Map.of(), Map.of())
            : flow;
    return new FlowRow(
        base.key(),
        base.income(),
        base.consumed(),
        base.taxPaid(),
        base.interestDue(),
        base.newBorrowing(),
        base.repaid(),
        base.netSurplus(),
        base.unmetNeed(),
        base.deaths() + deaths,
        base.births() + births,
        base.repaidMoney(),
        base.capitalizedArrears());
  }

  /**
   * ★★ **把一个批次在两个产业上的配额按"该批次的存活比例"缩**（R4 的出生/死亡回写路径用；与 {@link #scaleLaborOfIndustry}
   * 同一条口径，只是键换成了批次）。
   *
   * <p>★ 人口真值源在 social ⇒ 本步的**唯一输入**是一份 {@code group → (出生, 死亡)} 的账（见 {@link
   * #applyPopulationChange}）： economy 不需要认识 {@code PopulationGroup}，只需要它的稳定身份。
   *
   * <p>★★ <b>Z3a/C7：{@code GOV_SERVICE} 行整额跳过</b>；批次缩放完成后由调用方统一 {@link
   * #requireGovServiceCommitmentsWithinBudgets} fail-closed 校验。
   */
  private static void scaleLaborOfGroup(
      PeopleLotId group,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      SettlementIndex index) {
    if (before <= 0L || after >= before) {
      return;
    }
    // ★ R4-B.3a-perf：按批次查配额 id（旧实现每条 LotChange 扫全量配额）；id 回活表取当前 record，
    //   故同一次回写里前面的缩放不会被缓存的旧值覆盖。
    for (LaborAllocationId allocationId : index.allocationIdsOfGroup(group)) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
      if (laborCommitment == null) {
        continue; // 索引与活表同源；这里只防御中途被移除
      }
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        continue; // ★ Z3a/C7：政府行政岗位承诺不参与人口/死亡比例缩
      }
      long scaled = laborCommitment.laborMilli() * after / before;
      laborCommitments.put(
          allocationId,
          new HouseholdLaborCommitment(
              laborCommitment.id(),
              laborCommitment.group(),
              laborCommitment.household(),
              laborCommitment.actor(),
              laborCommitment.activity(),
              scaled,
              laborCommitment.period(),
              laborCommitment.kind(),
              laborCommitment.productionRole()));
    }
  }

  /**
   * ★★ <b>C7 统一校验：政府承诺整额保留后，PRODUCTION 不得超过"政府预留后的可用时间"</b>（Z7d-1 放宽）。
   *
   * <p>凡有 {@code GOV_SERVICE} 承诺的家户，逐户算 {@code Σ GOV_SERVICE}（职位/诉求）与 {@code Σ PRODUCTION}； Z7d-1 起
   * {@code Σ GOV_SERVICE > HouseholdEconomy.laborMilli} 是**合法的 underfed 状态**（饥饿把预算饿少；承诺行
   * 不缩/不删，有效供给由 app 供给桥 cap 到实际劳动）。真契约故障只剩一条：{@code Σ PRODUCTION > budget − min(Σ GOV_SERVICE,
   * budget)} ⇒ 发 {@code LABOR_COMMITMENT_CONTRACT} ERROR 并抛 {@link
   * IllegalStateException}（fail-closed，绝不静默缩/删政府承诺）。没有 {@code GOV_SERVICE} 的家户不检查 ⇒ 既有路径逐值不变。
   *
   * <p>★ <b>为什么放在整批缩放之后而不是缩放循环里</b>：同一家户可能同时供给多个 unit/批次，前面一次缩放看到的中途值可能被 后面的缩放继续缩小；只有在"这一轮所有 {@code
   * scaleLaborOf*} 都已跑完"的点上，越界判定才不是假阳性。
   *
   * @param source 无 day 上下文的调用方按日志纪律用 {@link EconomyLogSource#ECONOMY_POPULATION_WRITE}（system 档）；
   *     有 day 的调用方用 {@link EconomyLogSource#ECONOMY_POPULATION}
   * @param day 调用方的日锚点；方法签名无 day 的路径传 {@code -1}（事件不带 {@code day} 字段）
   */
  private static void requireGovServiceCommitmentsWithinBudgets(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      EconomyLogSource source,
      long day) {
    Map<HouseholdId, Long> govServiceByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> productionByHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        govServiceByHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Math::addExact);
      } else {
        productionByHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Math::addExact);
      }
    }
    if (govServiceByHousehold.isEmpty()) {
      return;
    }
    for (Map.Entry<HouseholdId, Long> entry : govServiceByHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomies.get(entry.getKey());
      if (householdEconomy == null) {
        continue; // 旧档迁移期占位行（运行期 GOV_SERVICE 写入者要求先有 classes 行）
      }
      long govServiceSum = entry.getValue();
      long productionSum = productionByHousehold.getOrDefault(entry.getKey(), 0L);
      long budget = Math.max(0L, householdEconomy.laborMilli());
      // ★★ Z7d-1：GOV_SERVICE 允许饿少后 over-budget（职位保留，C7 不缩/删）；只要求 PRODUCTION 不超过
      //   "扣除政府承诺最高优先预留后的可用时间"。有效供给不足由 app 供给桥 cap 并记录 underfed。
      long reservedGovService = Math.min(govServiceSum, budget);
      long productionBudget = budget - reservedGovService;
      if (productionSum > productionBudget) {
        throw laborCommitmentBudgetFault(
            entry.getKey(),
            govServiceSum,
            Math.addExact(govServiceSum, productionSum),
            budget,
            "GOV_SERVICE 最高优先预留后 PRODUCTION 超过可用时间预算（只缩 PRODUCTION 仍不足）",
            source,
            day);
      }
    }
  }

  /**
   * ★★ <b>C7 具名契约故障出口</b>（与 Z1b {@code LaborQueueSettlement.laborCommitmentContractFault} 同事件名）： 先发
   * {@code LABOR_COMMITMENT_CONTRACT} ERROR，再返回 {@link IllegalStateException} 供调用方 fail-closed。
   * {@code day < 0} ⇒ 事件不带 {@code day}（调用方方法签名无日锚点，按日志纪律归 system 来源）。
   */
  private static IllegalStateException laborCommitmentBudgetFault(
      HouseholdId household,
      long govServiceLaborMilli,
      long totalLaborMilli,
      long budgetLaborMilli,
      String reason,
      EconomyLogSource source,
      long day) {
    if (day >= 0L) {
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "LABOR_COMMITMENT_CONTRACT",
                  source,
                  "day",
                  day,
                  "household",
                  household.value(),
                  "govServiceLaborMilli",
                  govServiceLaborMilli,
                  "totalLaborMilli",
                  totalLaborMilli,
                  "budgetLaborMilli",
                  budgetLaborMilli,
                  "reason",
                  reason));
    } else {
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "LABOR_COMMITMENT_CONTRACT",
                  source,
                  "household",
                  household.value(),
                  "govServiceLaborMilli",
                  govServiceLaborMilli,
                  "totalLaborMilli",
                  totalLaborMilli,
                  "budgetLaborMilli",
                  budgetLaborMilli,
                  "reason",
                  reason));
    }
    return new IllegalStateException(
        "家庭劳动承诺契约违约："
            + reason
            + "（household="
            + household.value()
            + "，GOV_SERVICE="
            + govServiceLaborMilli
            + "，总承诺="
            + totalLaborMilli
            + "，预算="
            + budgetLaborMilli
            + "）");
  }

  // ── 现扣周期投入（周期的第一天）────────────────────────────────────────────────────────

  /**
   * ★★ **现扣周期投入步**（v2 spec §3.2/§3.3；**H3 起"谁出料"由 relation 明说**，裁定 C3）：**周期的第一天**（{@code
   * progressDays == 0}）把 {@link ProductionRecipe#inputPerUnit()}（农业 = 种子，织机 = 纤维，作坊 = 纤维 + 铁）
   * 从<b>关系指名的那一个主体的账</b>上划给该产业，并把实际划到的量**按商品**累加进 {@link
   * IndustryDefinitionView#cycleInputUsedMilli()}。
   *
   * <pre>
   * 供方       = relation.inputSupplier（缺 relation ⇒ 退回缺省 = operator；四档默认 = operator，见 RegimeRelations）
   * usableScale = min( 产能那一路 , ⌊可供量_j ÷ inputPerUnit[j]⌋ …每种投入一路 )     // "不把料倒进空转的产业"
   * need_j     = usableScale × inputPerUnit[j]                                      // ★ 整个产业的周期需求 —— 没有"人口占比"
   * drawn_j    = min( 供方余额_j , need_j )                                         // ★ 扣不动就扣光余额（不凭空造）
   * </pre>
   *
   * <p>★★ <b>改前的口径与它实测得到的病（H3 要修的就是这个）</b>：改前按 {@code rowSharesOf}（<b>该产业各行的人口占比 +
   * 逐行向下取整</b>）把需求摊给"供给该产业的家户"，每行再各扣各的。后果实测得到：① 分摊比例是<b>算</b>出来的 —— 没有哪一处 "说"过谁出料；② 逐行向下取整把需求切碎 ⇒
   * <b>取不满</b>：小夹具 6 座作坊只开 <b>4</b> 座、50 台织机只开 <b>48</b> 台； 真档年末剩 <b>24,001,080</b>
   * 毫纤维（旧口径"被织机取光"不再成立）。H3 起：**谁出料写在数据里** （{@link
   * ProductionRules#inputSupplier()}），需求是<b>整个产业</b>的，逐户只做一次"按人口占比 + 最大余数法"的分派 （Σ分派 ==
   * 需求，**不再逐户丢弃余数**）。
   *
   * <p>★★ <b>"从该主体自己的账出"的两处实现边界（如实记）</b>：
   *
   * <ol>
   *   <li>★ <b>供方是家户</b>（{@code ToCohort}；或本世界现存的 {@code HOUSEHOLD} 家户 actor —— 唯一拼写点 {@code
   *       HouseholdActors}）：就取它<b>那一本账</b>（会话工作副本，裁定 K1），需求 = <b>整份</b>，缸里不够就扣光 ⇒ 收获日投入那一路自然缩。 ★
   *       这就是"<b>佃农穷 ⇒ 地荒</b>"：tenant 档的 operator 就是佃农家户 ⇒ 它的缸空 ⇒ 规模 0，且**没有"同层"可补** （单一主体 ⇒
   *       ③（二）那道补齐无事可做）；
   *   <li>★★ <b>供方是聚合主体</b>（{@code ESTATE} / {@code WORKSHOP} / 产业型 {@code HOUSEHOLD}：id 是产业 id，如
   *       {@code farm@0_0}）：<b>economy 看不见它的账</b> —— 那本账住在 actor 切片的 {@code HouseholdInventory} 上（键
   *       {@code (actor, location)}），而会话工作副本按 {@code HouseholdId} 索引、只有家户账进得来（铁律 3：economy 不认识
   *       actor 切片；S1 起家户账经 {@code HouseholdActors.of} 派生 actor 后落进 AccountSession）。⇒ 由
   *       <b>该产业名下的家户账代理它</b>（"这个主体的缸" = 它名下那些家户的缸，与"谁供给这个产业"同一条唯一事实 {@link
   *       SettlementIndex#householdsOf}）。★ 这<b>不是</b>"按人口猜<b>谁</b>出料"（谁是供方已经由 relation 说了），而是"指名的主体
   *       <b>内部</b>怎么摊"的管道口径：先按人口占比问一遍（负担按人头摊），再由**同层还有货的账**补齐 ⇒ 只要这个主体**整体**出得起，
   *       需求就<b>一分不少</b>地筹齐（改前那种"逐行取整 ⇒ 取不满"不再发生）；
   * </ol>
   *
   * <p>★★ <b>H3 删掉的那条通道：同格取材（R4 的 T0）—— 为什么删</b>：改前 {@code transferIntraHexInputs} 会把"同格富余的
   * 纤维"搬给缺料的家户（R4 用它把田里的纤维送到城里的作坊）。H3 把它<b>整块删掉</b>，理由两条，都是实测得到的：
   *
   * <ol>
   *   <li>★ <b>它按 rowSharesOf 分摊</b>（"该行想要多少"），而那正是本批要消灭的口径 —— 留着它，本批的改动就只改了一半；
   *   <li>★★ <b>它会跨主体抢料（实测）</b>：把"第一层不够就从同格别人那里补"接进本步之后，真档出现 —— 织机（农村家户的账， 自己缸里 18,600,000）按"整个产业的
   *       740 台"去取料 ⇒ 缺口 3,600,000 ⇒ **把城里作坊自己的 2,100,000 纤维取走** ⇒ 作坊缺纤维 ⇒ 规模 0 ⇒ **工具产量 0**（{@code
   *       EconomyRealScaleClothTest} 实测：{@code heldByOperator(CRAFT, SMALL_TOOL)} 由 169,750 掉到
   *       <b>0</b>）。⇒ "谁出料"已经由 relation 说了，取料就<b>只从那个主体的账上取</b>；跨主体的余缺调剂是**市场** 的活（H4 的同格池），不是本步的活。
   * </ol>
   *
   * <p>★ <b>删掉它之后 R4 那条判据仍然成立</b>（有实测）：织机吃的纤维来自**它自己名下那些家户**（农村四行），而农田的纤维副产 经关系规则<b>正落在那些行上</b> ⇒
   * 每个周期都拿得到新料，{@code weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms} 照旧绿（那正是 R4 的 T0
   * 要表达的事，只是通道从"跨行搬运"变成了"同一本账"）。
   *
   * <p>★★ <b>"只取用得上的量"</b>：{@code usableScale} 的上限里含**除本商品之外**每种投入的可供量（改前是逐行的 {@code
   * rowUsageScale}，H3 起按产业算一次）—— 真档里城市作坊缺铁时，纤维<b>搬过去也是白扔</b>（会在现扣步被当投入扣掉而产出为 0， 织机反而拿不到料）。★
   * <b>劳动那一路刻意不算进来</b>：周期第一天 {@code cycleLaborMilli} 还是 0，把它算进来会让**所有**产业的 上限都是 0（取材永不发生）；劳动瓶颈在周期末的
   * {@link ProductionEfficiencyBook} 里照旧生效。
   *
   * <p>★★ <b>落账（P2-A §13.3：双方都是家户账）</b>—— 见 {@link #recordInputDraw}：
   *
   * <pre>
   * 供方家户 ≠ 经营者家户（"地主出种"那类）⇒ 一条 INPUT_REQUISITION 转移（供方家户 → 经营者家户），
   *   由**唯一** applier（{@link #applyTransferToHouseholds}）落到副本上（供方 −、经营者 +），随后**在经营者账上消费掉**
   *   这一笔（{@code consumed} 记在经营者：它是这批料的消费者）⇒ 净额：供方 −drawn、经营者 0；
   * 其余情形（供方家户 == 经营者家户；或经营者解析不到家户 —— 聚合主体）⇒ **不产生转移**：投入是"被生产直接吃掉"的，
   *   按 H2 的既有口径记账（供方 −余额、{@code consumed} 记在供方、{@code ledger.addInput}）。
   * </pre>
   *
   * <p>★★ <b>H6-lite：同格中间品的争用 ⇒ 一个池子 + 按需求比例配给（改前是"先到先得"）</b>。★ 它修的是一个**实测到**的病 （不是推测）：真档一格 14,806
   * 人 + 一座城（1,777 人）里，作坊第 2 个周期起**永久停工**，而原因**不是**"织机把纤维抢走"（H5 台账里那句是错的， 见下），而是 <b>作坊按 relation
   * 指名的那本账（= 它的经营者，由城镇家户代理）从创世那一份起**再也没有进项**</b> —— 纤维是农田的副产， 经关系规则落在**农村**家户的账上（ {@code
   * EconomyRealScaleClothTest#probeFibreAccountsPerCycle} 的实测：城镇缸 2,100,000 → <b>0</b>、农村缸逐周期
   * 18,600,000 → 18,042,000 → …，作坊的布入账 130,926 → <b>0</b>、<b>0</b>）。而织机与作坊**在同一格**、要的是**同一种商品** ⇒
   * 谁先跑谁先拿（{@code industries} 的插入序 = farm → weave → craft）。
   *
   * <pre>
   * ① 调查（只读）：逐产业算出"它能取的那些账"（relation 名下的**家户账**，聚合主体由劳动家户代理）与逐商品的可供量
   * ② 争用     ：★ **同一格里 ≥2 个产业要同一种投入** ⇒ 这一种投入在本格是一个**池子**
   *              池 = 这些需求方各自的账之**并集**（关系仍是"谁能进池"的权威：池里只有被 relation 指名过的账）
   *              逐需求方的可供量 := **整个池子**（各家户账之和）                 （⇒ 规模可以按池子算）
   * ③ 配给     ：D = Σ need、P = 池内余额；D &gt; P ⇒ quota = 按 need 比例（最大余数法，**Σquota == P**）；D ≤ P ⇒ quota = need
   * ④ 落账     ：逐产业、逐商品，需求上限 = quota（无争用 ⇒ 就是 need），取用次序见下（自己名下先、池里别人名下后）
   * </pre>
   *
   * <p>★ <b>为什么"池"只含被 relation 指名过的账</b>：这是 H3 那条"谁出料写在数据里"的**下限守住** —— 拓宽的只是**同一池子内的取用次序**，
   * 不是"谁出料"；且**只有争用才开池**（一格只有一个需求方时，逐值 = 改前）。
   *
   * <p>★ <b>它保住了的两条既有机构</b>：① <b>缸空 ⇒ 地荒</b>（tenant 档的种子只从佃农家户那一本账出；种子只有农业要 ⇒ **从不争用** ⇒ 从不进池）；②
   * <b>无争用 ⇒ 逐值不变</b>（今天的全部夹具与判据都在这一档里）。
   *
   * <p>★ <b>本轮明确不做的（如实记）</b>：中间品**仍不走市场**（没有价格、没有货款流转 —— 市场只管口粮/布的缺口），
   * 故这条池子配给是"跨主体余缺调剂"的**权宜口径**；正解是让经营者参与市场（H4 的市场今天只有家户参与）。★ 边界：**只有城市的格** （无农村人口 ⇒ 没有织机 ⇒
   * 纤维无人争用）里的作坊**照旧第 2 周期停工** —— 它那一格没有任何一条 relation 把乡下的纤维指给它。
   *
   * <p>★ {@code population == 0} 的家户**不分摊**（份额恒 0 ⇒ 与"它不出工"同义 —— 与改前的门槛逐字一致），但**可以是供方** （relation
   * 指名它，或它缸里有货要兜底同格时）：<b>出料不需要人口</b>。
   *
   * <p>★ **扣掉的量并入当日 {@code consumed}**：投入是**本期的消费**（spec §二 把"留种的计量"列在"数"里），记进去才能保住 §6.1
   * 的守恒式（否则配了投入的世界上那条等式不成立 ⇒ 端到端的守恒用例会变成假绿）。
   *
   * <p>★ <b>为什么不在这里扣"每日原料"</b>：{@code dailyInputPerUnit} 是**每日**口径，本轮仍是零读取点（spec §3.3
   * 明说两个字段并存、语义各自清楚），不在本步范围。
   *
   * <p>★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）的落点就在第一遍</b>：{@code InputPlan.capacityScale}
   * 不再是"计划规模"本身， 而是 <b>计划规模 × 投入满足率</b>（最短缺那一项投入的 Leontief 满足率，逐户按手头库存现算）—— 详见 {@link
   * #inputSatisfactionPerMille}。满足率为 0 的 unit 与"本格没有产能"同支（不进池、不要料、不产出）。 ★ 口粮缺口**不**走这条（劳动与人口是
   * Social 的唯一权威，W3 另报）。
   *
   * <p>★ <b>W2-fix3</b>：返回"产能被投入短缺扣掉"的日志读数（{@link CapacityDeductionSummary}；只服务 §一.9 的 INFO 汇总，
   * 不进任何算式）。★ 本方法的调用者是**分区 worker**（一个分区一次调用），故它只把读数交回去、<b>不</b>发射 INFO —— 由协调器把全部分区（+
   * 无格那一批）合并后**一次**发射，否则分区数会把"一条 INFO"变成"每分区一条"。
   */
  private static CapacityDeductionSummary drawCycleInputs(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day) {
    // ── 第一遍：调查（**只读**：不改任何账）────────────────────────────────────────────
    List<InputPlan> plans =
        surveyInputDemands(
            units,
            industries,
            householdEconomies,
            relations,
            operatorConditions,
            index,
            householdGoods,
            householdOfActor);
    // ── 第二遍：同格争用 ⇒ 开池 + 按需求比例配给（H6-lite；无争用 ⇒ 一个字段都不动）────────
    rationContestedInputs(plans, householdGoods);
    // ── 第二遍b：★★★ S-11 定规模 —— 实际产能 = 计划产能 × 投入满足率（最短缺那一项投入）。
    //   位置必须在开池**之后**：池子把 available 换成"取料口径"的可供量，S-11 的"拿不到"必须按同一份量算
    //   （否则"自己账上没料、同格池子里有"的 unit 会被误判成产能 0 ⇒ 把 H6-lite 那条实测修好的通道堵死）。
    //   ★★ W2-fix3：逐 unit 明细下 DEBUG；INFO 只发**一条** —— 读数在这里累计、由调用方（协调器线程）在
    //   全部分区合并后发射（本方法是一次分区调用，自己发射就会变成"每分区一条 INFO"）。
    CapacityDeductionSummary deductions = new CapacityDeductionSummary(day);
    for (InputPlan plan : plans) {
      applyInputSatisfaction(plan, day, deductions);
    }
    // ── 第三遍：逐 unit 落账（需求上限 = 配给额；无争用 ⇒ 就是它自己算出来的 need）──────────
    for (InputPlan plan : plans) {
      drawOneProductionProcessInputs(
          plan,
          units,
          householdEconomies,
          householdGoods,
          householdMoney,
          consumedGoods,
          householdOfActor,
          ledger);
    }
    return deductions;
  }

  /**
   * ★★ <b>一个 unit 本周期的投入计划</b>（H6-lite 的三遍式里"调查"的产物）。
   *
   * <p>★ <b>为什么要有它</b>：H6-lite 的配给要**先看清全部需求方**才谈得上"按需求比例" —— 逐 unit 就地算就地扣的旧写法
   * 天然是"先到先得"。故把"算"与"扣"分成两遍：本类只在第一遍被读写，第二遍改 {@link #available}（争用的商品换成池子） 与 {@link #quota}，第三遍才动账。
   *
   * <p>★ 字段的可变性是**有意的、且被限制在三处**：{@code available} 只被 {@link #rationContestedInputs} 覆盖， {@code
   * quota}/{@code sharedKeys} 只由它填；★ S-11 起 {@code capacityScale} 只被 {@link
   * #applyInputSatisfaction} 覆盖 ⇒ 第三遍是纯读。
   */
  private static final class InputPlan {

    /** unit id（唯一键）。 */
    private final ProductionUnitId id;

    /** unit 本体（**第一遍的那一份**：第三遍只读它的 operator / cycleInputUsedMilli 并写回新状态）。 */
    private final ProductionProcess unit;

    /** 本格（unit.industry 解析出来的那一格；"同格争用"的分组键）。★ 产业 id 里没有格键 ⇒ null（它不属于任何一格 ⇒ 不参与争用）。 */
    private final HexCoord hex;

    /** 逐单位规模要的商品（= {@code industry.inputPerUnit()}；只读）。 */
    private final Map<CommodityId, Long> perScale;

    /** 产业模板（S-11 的 INFO 日志要报它；只读）。 */
    private final IndustryDefinitionView industry;

    /** ★★ <b>计划规模</b>（= {@code ProductionProcessBook.plannedCapacityScaleOf}；只读）。 */
    private final long plannedScale;

    /**
     * ★★★ S-11（v3.7）：<b>实际产能</b> = 计划规模 × 投入满足率（{@link #inputSatisfactionPerMille}，最短缺那一项投入）。 ★
     * 可变、且**唯一写点**是 {@link #applyInputSatisfaction}（H6-lite 第二遍之后：必须等池子把 available 换成取料口径）。
     */
    private long capacityScale;

    /** relation 名下的家户账（保序去重；取料的第二层与"补齐"层）。 */
    private final List<HouseholdId> ownKeys;

    /** ② 逐商品可供量：{@link #ownKeys} 的家户账（争用的商品在第二遍被换成池子）。 */
    private final Map<CommodityId, Long> available;

    /** ★ 只有**争用**的商品才进表：本 unit 该商品的取料上限（第二遍填）。 */
    private final Map<CommodityId, Long> quota = new LinkedHashMap<>();

    /** ★ 只有**争用**的商品才进表：这一种料的池子（= 各需求方 {@link #ownKeys} 的并集）。 */
    private final Map<CommodityId, List<HouseholdId>> sharedKeys = new LinkedHashMap<>();

    private InputPlan(
        ProductionUnitId id,
        ProductionProcess unit,
        HexCoord hex,
        IndustryDefinitionView industry,
        Map<CommodityId, Long> perScale,
        long plannedScale,
        List<HouseholdId> ownKeys,
        Map<CommodityId, Long> available) {
      this.id = id;
      this.unit = unit;
      this.hex = hex;
      this.industry = industry;
      this.perScale = perScale;
      this.plannedScale = plannedScale;
      this.capacityScale = plannedScale; // 先记计划规模；applyInputSatisfaction 折算成实际产能
      this.ownKeys = ownKeys;
      this.available = available;
    }

    /**
     * 本 unit 在 {@code commodity} 上的取料上限：争用的商品 = 配给额，其余 = 规模折出来的需求。 ★ S-11（v3.7）起再对**实际产能**折出的需求取
     * min：配给额是按**计划规模**算的，而 S-11 可能把这一轮的产能 调低（拿不到料）⇒ 取料不得越过"实际产能需要的量"（旧代码里 quota ≤ need 恒成立，这一层 min
     * 是恒等的）。
     */
    private long limitOf(CommodityId commodity, long usableScale) {
      long need = usableScale * perScale.getOrDefault(commodity, 0L);
      Long ration = quota.get(commodity);
      return ration != null ? Math.min(ration, need) : need;
    }
  }

  /**
   * ★★ <b>调查：逐 unit 算出它本周期"想开多大、要什么料、能从哪些账取"</b>（H6-lite 第一遍；**只读**）。
   *
   * <pre>
   * available_j = (供方自己的账在副本里 ? 它在 j 上的余额 : 0) + Σ_{k ∈ ownKeys} 库存_k,j
   * </pre>
   *
   * <p>★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：供不应求才扣产能 —— 生产阶段的落点是 {@link
   * #applyInputSatisfaction}</b>（本方法只记计划规模；折算在 H6-lite
   * 的池子成形之后）。用户原话：「如果市场供不应求了，单个家户拿不到足够的生产资料/口粮，这时候才算开始扣产能」。本批只做<b>生产资料那一支</b>：
   *
   * <pre>
   * 计划规模 S0 = plannedCapacityScaleOf(unit, industry, index, condition)        （= 本格产能 × 状态机计划系数）
   * 计划需求 q_j = S0 × inputPerUnit_j                                            （固定配方 / Leontief）
   * 实际到手 h_j = Σ_{k ∈ ownKeys} 库存_k,j                                       （★ 家户手头库存**现算**，不新增状态）
   * 逐项满足率 r_j = q_j ≤ 0 ? 1000 : min(1000, ⌊h_j × 1000 ÷ q_j⌋)
   * 投入满足率 r   = min_j r_j                                                     （★ 最短缺那一项，不取加权平均）
   * 实际产能 S     = ⌊S0 × r ÷ 1000⌋                                               （= 计划产能 × 满足率）
   * </pre>
   *
   * <p>★ <b>"拿不到"= 物理短缺，不是穷</b>：本算式只读<b>库存</b>，一分钱都不看 —— 债务式清算下"没钱也能买" （未付价款记债，见 {@code
   * MarketSettlement.executeTrade}），买不到只可能是没货/运力不足/口岸拦下。
   *
   * <p>★ <b>口粮缺口不进这条</b>（S-11 第②半）：劳动与人口是 Social 的唯一权威，本方法既不改人口也不改可提供劳动； 口粮缺口由 W3 的通道报社会。
   *
   * <p>★ <b>谁是"需求方"</b>：周期第一天、配了投入、且**实际产能 &gt; 0**（{@code capacityScale > 0}）的 unit。★ 产能为 0 的 unit
   * **不进池也不参与争用** —— 它一根料都不会要（旧口径在 {@code usableScale <= 0} 那一支被跳过，读数逐值相同）。
   */
  private static List<InputPlan> surveyInputDemands(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, HouseholdId> householdOfActor) {
    List<InputPlan> plans = new ArrayList<>();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionProcess unit = units.get(id);
      IndustryDefinitionView industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      if (index.assetRejectionOf(id).isPresent()) {
        continue; // ★★★ D4：预算具名拒绝的 unit 在执行前不扣任何周期投入（本日不半推进）。
      }
      if (unit.progressDays() != 0L) {
        continue; // 只有周期的第一天扣投入
      }
      Map<CommodityId, Long> perScale = industry.inputPerUnit();
      if (perScale.isEmpty()) {
        continue; // ★ 空表与"缺键"同义：不扣、不缩规模 ⇒ 未配投入的 unit 行为与 V2 一字不差
      }
      long plannedScale =
          ProductionProcessBook.plannedCapacityScaleOf(
              unit, industry, index, operatorConditions.get(id));
      if (plannedScale <= 0L) {
        continue; // 本格没有产能 / 已缩到 0 ⇒ 它不产出、也不要料（"地荒着"）
      }
      // ★★ H3：**谁出料由 relation 说**。缺 relation（合法状态）⇒ 退回缺省 = operator。
      ProductionRules relation = relations.get(id);
      Payee supplier =
          relation == null ? new Payee.ToActor(unit.operator()) : relation.inputSupplier();
      List<HouseholdId> ownKeys =
          supplierAccountsOf(supplier, unit, industry, householdEconomies, householdOfActor, index);
      // ① 可供量（逐商品）= relation 名下的家户账（聚合主体已由 supplierAccountsOf 解析到 unit 名下的家户账）。
      Map<CommodityId, Long> available = new LinkedHashMap<>();
      for (CommodityId commodity : perScale.keySet()) {
        long sum = 0L;
        for (HouseholdId key : ownKeys) {
          sum += stockOf(householdGoods, key, commodity);
        }
        available.put(commodity, sum);
      }
      // ② ★★★ S-11：这里先记**计划规模**；"实际产能 = 计划产能 × 投入满足率"在第二遍之后统一折算
      //   （{@link #applyInputSatisfaction}）—— 必须等 H6-lite 的池子把 available 换成"取料口径"之后再算，
      //   否则"自己账上一根料都没有、但同格池子里有"的 unit 会被误判成产能 0（那正是 H6-lite 要修的实测病）。
      plans.add(
          new InputPlan(
              id,
              unit,
              // ★ 拿不到格键 ⇒ null（**不抛**：改前这条路径也不需要格，{@code householdKeysOf} 会给空表 ⇒ 逐值同旧），
              //   它因此不属于任何一格、也就不参与"同格争用"。
              IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null),
              industry,
              perScale,
              plannedScale,
              ownKeys,
              available));
    }
    return plans;
  }

  /**
   * ★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：定规模 —— 实际产能 = 计划产能 × 投入满足率</b> （H6-lite 第二遍之后、落账之前；逐 plan
   * 一次）。
   *
   * <p>它读的 {@code available} 是<b>取料口径</b>的可供量（争用的商品在第二遍已被换成池子）—— 与第三遍实际取料、 与 {@link #usableScaleOf}
   * 用的是同一份量，三处不可能漂开。★ 它同时把"拿不到多少"记进 {@link CapacityDeductionSummary}（逐 unit 明细下 DEBUG， INFO
   * 一条轮汇总由协调器发射）。
   */
  private static void applyInputSatisfaction(
      InputPlan plan, long day, CapacityDeductionSummary deductions) {
    long satisfactionPerMille =
        inputSatisfactionPerMille(plan.perScale, plan.available, plan.plannedScale);
    long actualScale = plan.plannedScale * satisfactionPerMille / 1_000L;
    plan.capacityScale = actualScale;
    if (satisfactionPerMille < 1_000L && (TRACE.isInfoEnabled() || TRACE.isDebugEnabled())) {
      // ★★ W2-fix3（§一.9）：扣减发生 ⇒ ① 逐 unit 明细下 DEBUG（PRODUCTION_CAPACITY_DEDUCTED_DETAIL）；
      //   ② 累进"本轮汇总"，轮末由协调器发一条 INFO（PRODUCTION_CAPACITY_DEDUCTED）。
      //   ★ 两个级别都关 ⇒ 连"最短缺那一项"都不复算（结算路径一个字段都不多算；它纯属日志读数，不进任何算式）。
      CommodityId scarcest = scarcestInputOf(plan.perScale, plan.available, plan.plannedScale);
      deductions.record(plan, scarcest, satisfactionPerMille, actualScale);
      logCapacityDeductionDetail(day, plan, scarcest, satisfactionPerMille, actualScale);
    }
  }

  /**
   * ★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：投入品满足率 —— "最短缺那一项投入"的 Leontief 口径</b>。
   *
   * <pre>
   * 计划需求 q_j = plannedScale × inputPerUnit_j        （固定配方；逐商品一条）
   * 逐项满足率 r_j = q_j ≤ 0 ? 1000 : min(1000, ⌊hand_j × 1000 ÷ q_j⌋)
   * 投入满足率 r   = min_j r_j                          （★ 最短缺那一项；**不取加权平均**）
   * </pre>
   *
   * <p>★ 语义（用户原话「单个家户拿不到足够的生产资料…这时候才算开始扣产能」）：<b>拿不到 = 物理短缺</b>。本算式只看 <b>库存</b>，不看钱 ——
   * 债务式清算下"没钱也能买"（未付价款记债），所以"买不到"只能是没货/运力不足/口岸拦下。
   *
   * <p>★ 缺省中性：没有任何一项投入（{@code inputPerUnit} 为空、或每单位需求 ≤ 0）⇒ 返回 1000 ⇒ 实际产能 = 计划产能 （与改前逐值相同）。
   *
   * <p>★ 与 {@link #usableScaleOf} 的分工：本方法把"<b>产能</b>那一路"从计划规模折成实际产能， {@code usableScaleOf}
   * 仍把"可供量那一路"（含 H6-lite 的池子）取 min —— 两者读<b>同一份</b> {@code available}（第二遍之后的取料口径）， 因此缺省中性（料够 ⇒ 满足率
   * 1000）与"缸空 ⇒ 地荒"两支逐值不变，差异只到<b>千分比取整</b>这一级。 ★ <b>刻意不按"本户自己的账"算</b>：relation 名下的账在争用时会被 H6-lite
   * 换成同格池子（同格作坊+织机的纤维之争）， 按自家账算会把"自己账上没料、池子里有"的 unit 判成产能 0 ⇒ 堵死那条已实测修好的通道（见 {@link
   * #drawCycleInputs} 的注）。 ★ 也不按"家户合并算"：Leontief 的固定比例是<b>每条生产方式（unit）</b>的口径，把一户名下多个 unit
   * 的投入并成一份最小 满足率会让一条产线的缺料去砍另一条产线（本批不做，记为待裁定）。
   *
   * @param perScale 逐商品的单位规模需求（{@code industry.inputPerUnit()}；只读）
   * @param hand 逐商品的实际到手量（= {@code available}：relation 指名账上的库存合计）
   * @param plannedScale 计划规模（≥ 0）
   * @return 满足率（0..1000 的千分比）
   */
  static long inputSatisfactionPerMille(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> hand, long plannedScale) {
    if (plannedScale <= 0L) {
      return 0L;
    }
    long satisfaction = 1_000L;
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      Long per = entry.getValue();
      if (per == null || per <= 0L) {
        continue; // 每单位需求 ≤ 0 ⇒ 这一路不构成约束（与 usableScaleOf / 收获日公式同口径）
      }
      long required = plannedScale * per; // 计划的需求量（毫商品）
      if (required <= 0L) {
        continue;
      }
      long onHand = hand.getOrDefault(entry.getKey(), 0L);
      long rate = onHand >= required ? 1_000L : Math.max(0L, onHand) * 1_000L / required;
      satisfaction = Math.min(satisfaction, Math.min(1_000L, rate));
    }
    return satisfaction;
  }

  /**
   * ★★ W2-fix3：<b>最短缺那一项投入</b>（与 {@link #inputSatisfactionPerMille} 同一判据；只为日志复算一次，不改算式）。
   *
   * <p>★ 它是"扣产能"两条日志的共同输入：轮汇总的商品分布计数（{@link CapacityDeductionSummary}）与逐 unit DEBUG 明细的 {@code
   * scarcestInput/scarcestOnHand/scarcestRequired} 三个字段。
   *
   * @return 最短缺的商品（没有任何一项构成约束 ⇒ null）
   */
  private static CommodityId scarcestInputOf(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> hand, long plannedScale) {
    CommodityId scarcest = null;
    long scarcestPerMille = 1_000L;
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      Long per = entry.getValue();
      if (per == null || per <= 0L) {
        continue;
      }
      long required = plannedScale * per;
      if (required <= 0L) {
        continue;
      }
      long onHand = hand.getOrDefault(entry.getKey(), 0L);
      long rate = onHand >= required ? 1_000L : Math.max(0L, onHand) * 1_000L / required;
      rate = Math.min(1_000L, rate);
      if (rate < scarcestPerMille) {
        scarcestPerMille = rate;
        scarcest = entry.getKey();
      }
    }
    return scarcest;
  }

  /**
   * ★★ W2-fix3：<b>逐 unit 的"扣产能"明细 —— DEBUG（{@code PRODUCTION_CAPACITY_DEDUCTED_DETAIL}）</b>。
   *
   * <p>改前 {@code PRODUCTION_CAPACITY_DEDUCTED} 是<b>逐 unit 一条 INFO</b>（真档一轮可能上百行）；现在它的字段整体下到
   * DEBUG，INFO 只留轮末一条汇总（{@link CapacityDeductionSummary}）。★ <b>一个字段都没丢</b>：本事件的字段 = 改前 INFO 的全部字段
   * （unit/industry/households/scarcestInput/scarcestOnHand/scarcestRequired/satisfactionPerMille/plannedScale/actualScale/
   * deductedScale/why）+ 原有的逐商品两张表（perUnit/onHand）。
   *
   * <p>只在<b>真的发生扣减</b>（满足率 &lt; 1000‰）时被调用；DEBUG 关着 ⇒ 直接返回（日志失败不得影响结算）。
   */
  private static void logCapacityDeductionDetail(
      long day, InputPlan plan, CommodityId scarcest, long satisfactionPerMille, long actualScale) {
    if (!TRACE.isDebugEnabled()) {
      return; // 日志关闭不得影响结算
    }
    // ★ W2-fix3（①）：两个"最短缺"读数先在**基本型 long** 上落定，再进事件表 —— 改前这里写的是
    //   `scarcest == null ? 0L : hand.getOrDefault(scarcest, 0L)`：三元表达式把 Long 拆箱、随即又为
    //   事件表的 Object 形参装箱（SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED @ 5898）。数值与字段语义一字不变。
    long scarcestOnHand = 0L;
    long scarcestRequired = 0L;
    if (scarcest != null) {
      scarcestOnHand = plan.available.getOrDefault(scarcest, 0L);
      scarcestRequired = plan.plannedScale * plan.perScale.getOrDefault(scarcest, 0L);
    }
    Map<String, Long> perInput = new LinkedHashMap<>();
    Map<String, Long> handByInput = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
      perInput.put(entry.getKey().value(), entry.getValue());
      handByInput.put(entry.getKey().value(), plan.available.getOrDefault(entry.getKey(), 0L));
    }
    EventLog.channel(TRACE)
        .debug(
            LogEvent.of(
                "PRODUCTION_CAPACITY_DEDUCTED_DETAIL",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                day,
                "unit",
                plan.id.value(),
                "industry",
                plan.industry.id().value(),
                "households",
                plan.ownKeys.stream().map(HouseholdId::value).toList(),
                "scarcestInput",
                scarcest == null ? "none" : scarcest.value(),
                "scarcestOnHand",
                scarcestOnHand,
                "scarcestRequired",
                scarcestRequired,
                "satisfactionPerMille",
                satisfactionPerMille,
                "plannedScale",
                plan.plannedScale,
                "actualScale",
                actualScale,
                "deductedScale",
                plan.plannedScale - actualScale,
                "why",
                "S-11：计划产能 × 最短缺投入的满足率（拿不到 = 物理短缺，非因穷）",
                "perUnit",
                perInput,
                "onHand",
                handByInput));
  }

  /**
   * ★★ W2-fix3（§一.9）：<b>本轮"产能被投入短缺扣掉"的汇总账 —— INFO 只发一条</b>。
   *
   * <p>改前 {@code PRODUCTION_CAPACITY_DEDUCTED} 是<b>逐 unit 一条 INFO</b>（真档一轮可能上百行）。现在：
   *
   * <pre>
   * 逐 unit 明细 → DEBUG  PRODUCTION_CAPACITY_DEDUCTED_DETAIL（字段见 {@link #logCapacityDeductionDetail}）
   * 轮汇总       → INFO   PRODUCTION_CAPACITY_DEDUCTED（事件名保留；语义从"逐 unit"变成"轮汇总"）
   *                      day / units（被扣 unit 数）/ households（被扣家户数，去重）/ totalPlannedScale /
   *                      totalActualScale / totalDeductedScale / maxDeductedPerMille（最大扣减比例，千分比）/
   *                      scarcestInputs（"最短缺投入"的商品分布计数）/ why
   * </pre>
   *
   * <p>★ <b>它只是日志读数</b>：{@code capacityScale} 早已由 {@link #applyInputSatisfaction}
   * 定完，本类不参与任何算式、不改任何状态。 ★ 每份实例只被<b>一个</b>分区 worker（或协调器单线程那一段）触碰，分区之间在协调器上经 {@link #merge} 合并 ⇒
   * 没有跨线程共享的可变状态。
   *
   * <p>★ <b>为什么必须合并后再发</b>：{@link #drawCycleInputs} 是分区 worker（一次调用 = 一个分区）⇒ 就地发射会变成"每分区一条
   * INFO"；合并由协调器按分区计划序做 ⇒ 行数恒为 1，且 {@code scarcestInputs} 用 {@link TreeMap}（商品 id 升序）⇒ 字段值与线程调度、
   * 合并次序都无关。
   */
  private static final class CapacityDeductionSummary {

    /** 本轮的 day（全部分区同一天）。 */
    private final long day;

    /** 被扣的 unit 数。 */
    private int units;

    /** 被扣的家户（去重；只读它的 size = "几户被扣"）。 */
    private final Set<HouseholdId> households = new LinkedHashSet<>();

    /** 计划规模合计（与 {@link InputPlan#plannedScale} 同单位）。 */
    private long totalPlannedScale;

    /** 实际产能合计（与 {@link InputPlan#capacityScale} 同单位）。 */
    private long totalActualScale;

    /** 最大扣减比例（千分比 = 1000 − 最低满足率）。 */
    private long maxDeductedPerMille;

    /** "最短缺投入"的商品分布计数（{@link TreeMap} ⇒ 按商品 id 升序，跨分区合并次序不影响渲染）。 */
    private final Map<String, Integer> scarcestInputs = new TreeMap<>();

    private CapacityDeductionSummary(long day) {
      this.day = day;
    }

    /**
     * 记一个<b>真被扣了产能</b>的 unit（只被 {@link #applyInputSatisfaction} 在满足率 &lt; 1000‰ 时调用）。
     *
     * <p>★ 用基本型 {@code +=} 而不是 {@code Math.addExact}：这是日志读数，<b>任何情况下都不许抛</b>（日志失败不得影响结算）。
     */
    private void record(
        InputPlan plan, CommodityId scarcest, long satisfactionPerMille, long actualScale) {
      units++;
      households.addAll(plan.ownKeys);
      totalPlannedScale += plan.plannedScale;
      totalActualScale += actualScale;
      maxDeductedPerMille = Math.max(maxDeductedPerMille, 1_000L - satisfactionPerMille);
      scarcestInputs.merge(scarcest == null ? "none" : scarcest.value(), 1, Integer::sum);
    }

    /** ★ 分区账合并（协调器按分区计划序调用；各分区同一天）。 */
    private void merge(CapacityDeductionSummary other) {
      units += other.units;
      households.addAll(other.households);
      totalPlannedScale += other.totalPlannedScale;
      totalActualScale += other.totalActualScale;
      maxDeductedPerMille = Math.max(maxDeductedPerMille, other.maxDeductedPerMille);
      for (Map.Entry<String, Integer> entry : other.scarcestInputs.entrySet()) {
        scarcestInputs.merge(entry.getKey(), entry.getValue(), Integer::sum);
      }
    }

    /** ★ §一.9：轮末<b>一条 INFO 汇总</b>；本轮一次扣减都没发生（或 INFO 关着）⇒ 一条都不发（与改前"只在真扣减时发射"同口径）。 */
    private void logSummary() {
      if (units == 0 || !TRACE.isInfoEnabled()) {
        return;
      }
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "PRODUCTION_CAPACITY_DEDUCTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  day,
                  "units",
                  units,
                  "households",
                  households.size(),
                  "totalPlannedScale",
                  totalPlannedScale,
                  "totalActualScale",
                  totalActualScale,
                  "totalDeductedScale",
                  totalPlannedScale - totalActualScale,
                  "maxDeductedPerMille",
                  maxDeductedPerMille,
                  "scarcestInputs",
                  scarcestInputs,
                  "why",
                  "S-11：实际产能 = 计划产能 × 最短缺投入的满足率（拿不到 = 物理短缺，非因穷）；逐 unit 明细见 PRODUCTION_CAPACITY_DEDUCTED_DETAIL"));
    }
  }

  /**
   * ★★ <b>同格争用 ⇒ 开池 + 按需求比例配给</b>（H6-lite 第二遍；见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <pre>
   * 争用 = 同一格里 ≥2 个需求方要同一种投入（逐商品判；只有本周期真开工的 unit 算需求方）
   * 池   = 这些需求方 ownKeys 的**并集**（保序去重：需求方序 × 各自的 ownKeys 序）  ← relation 决定谁能进池
   * 每家的可供量_j := 池内余额（各家户账之和）⇒ 规模可以按"本格这一种料的总量"算
   * D = Σ need_j、P = 池内余额；D > P ⇒ quota = 按 need 比例（最大余数法，Σquota == P）；否则 quota = need_j
   * </pre>
   *
   * <p>★ <b>无争用 ⇒ 一个字段都不动</b>（{@code quota}/{@code sharedKeys} 都留在空表里）⇒ 单需求方的世界的全部读数逐值不变。
   */
  private static void rationContestedInputs(
      List<InputPlan> plans, Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    // ① 归集：格 → 商品 → 需求方（保序；键是格 + 商品，值是该商品在本格的全部需求方）。
    Map<HexCoord, Map<CommodityId, List<InputPlan>>> byHex = new LinkedHashMap<>();
    for (InputPlan plan : plans) {
      if (plan.hex == null || plan.ownKeys.isEmpty()) {
        continue; // ★ 产业 id 里没有格键、或 relation **一本账都没指名**（没有人供给它）⇒ 它不进池、也不从池里取
      }
      Map<CommodityId, List<InputPlan>> byCommodity =
          byHex.computeIfAbsent(plan.hex, key -> new LinkedHashMap<>());
      for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue; // 每单位需求为 0 ⇒ 它**不是**这一种料的需求方（同收获日公式的口径）
        }
        byCommodity.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(plan);
      }
    }
    // ② 逐（格，商品）判争用 ⇒ 开池 + 配给。
    for (Map<CommodityId, List<InputPlan>> byCommodity : byHex.values()) {
      for (Map.Entry<CommodityId, List<InputPlan>> entry : byCommodity.entrySet()) {
        CommodityId commodity = entry.getKey();
        List<InputPlan> demanders = entry.getValue();
        if (demanders.size() < 2) {
          continue; // ★ 只有一个需求方 ⇒ 逐值 = 改前（"缸空 ⇒ 地荒"那条机构也在这一档里）
        }
        List<HouseholdId> pool = new ArrayList<>();
        for (InputPlan plan : demanders) {
          for (HouseholdId key : plan.ownKeys) {
            if (!pool.contains(key)) {
              pool.add(key);
            }
          }
        }
        long poolStock = 0L;
        for (HouseholdId key : pool) {
          poolStock += stockOf(householdGoods, key, commodity);
        }
        // 逐需求方：可供量换成池子 ⇒ 规模按池子算；同时记下池子（第三遍取料要用）。
        for (InputPlan plan : demanders) {
          plan.available.put(commodity, poolStock);
          plan.sharedKeys.put(commodity, pool);
        }
        // 需求 = 规模 × 每单位用量，而"规模"与第三遍同一条算式（产能那一路 × 每种投入的可供量那一路，取小）。
        long[] needs = new long[demanders.size()];
        long totalNeed = 0L;
        for (int i = 0; i < demanders.size(); i++) {
          InputPlan plan = demanders.get(i);
          needs[i] = usableScaleOf(plan) * plan.perScale.getOrDefault(commodity, 0L);
          totalNeed += needs[i];
        }
        if (totalNeed <= 0L) {
          continue; // 谁都用不上这一种料（池子空、或每单位需求为 0）⇒ 不必配给
        }
        long[] quotas =
            totalNeed > poolStock
                ? ProportionalSplit.byDenominator(poolStock, needs, totalNeed)
                : needs;
        for (int i = 0; i < demanders.size(); i++) {
          demanders.get(i).quota.put(commodity, quotas[i]);
        }
      }
    }
  }

  /**
   * 规模 = <b>min(产能那一路, 逐投入的可供量那一路)</b>（= 改前 {@code rowUsageScale} 的 unit 版）。
   *
   * <p>★★ S-11（v3.7）起"产能那一路" = {@link InputPlan#capacityScale} = **计划规模 × 投入满足率**（见 {@link
   * #inputSatisfactionPerMille}）；"可供量那一路"照旧按 {@code available} 算（争用的商品在第二遍被换成池子）。
   *
   * <p>★★ <b>它是唯一拼写点</b>：第二遍"这一家想要多少"与第三遍"这一家实际取多少"必须是**同一把尺**。
   */
  private static long usableScaleOf(InputPlan plan) {
    long usable = plan.capacityScale;
    for (Map.Entry<CommodityId, Long> per : plan.perScale.entrySet()) {
      if (per.getValue() <= 0L) {
        continue; // 每单位需求为 0 ⇒ 这一路不构成约束（同收获日公式的口径）
      }
      usable = Math.min(usable, plan.available.getOrDefault(per.getKey(), 0L) / per.getValue());
    }
    return usable;
  }

  /**
   * ★★ <b>落账：把一个 unit 的投入计划扣成实际发生额</b>（H6-lite 第三遍；口径见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <p>取用次序（四层，越靠前越优先；每一笔都经 {@code recordInputDraw} 落账 ⇒ "任何库存变动必有对应转移记录"那条不变量的落点仍然只有一处）：
   *
   * <pre>
   * ③ 一  ：relation 名下的家户账 —— 按人口占比 + 最大余数法分派（Σ分派 == remaining，不再逐户丢余数）
   * ③ 一b ：同层还有货的账**补齐**（持仓降序）
   * ③ 二  ：★ H6-lite：**争用**的商品的**池子里、别人名下的**账（持仓降序）—— 只在上面取不满时进场
   * </pre>
   */
  private static void drawOneProductionProcessInputs(
      InputPlan plan,
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    long usableScale = usableScaleOf(plan);
    if (usableScale <= 0L) {
      return; // 一点料都筹不到（或本格没有产能）⇒ 不扣、规模自然 0 —— "地荒着"
    }
    ProductionProcess unit = plan.unit;
    Map<CommodityId, Long> drawnTotal = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
      CommodityId commodity = entry.getKey();
      long perUnit = entry.getValue();
      if (perUnit <= 0L) {
        continue;
      }
      // 毫单位（★ 单位规模 × 毫单位/单位规模）—— 整个 unit 本周期要用的料（争用 ⇒ 已被第二遍换成配给额）。
      long remaining = plan.limitOf(commodity, usableScale);
      if (remaining <= 0L) {
        continue; // ★ 配给的整数份额可能是 0 ⇒ 这一家这一种料一根都不取
      }
      // ③ 一：按人口占比 + **最大余数法**分派（Σ分派 == remaining，不再逐户丢余数），逐户上限 = 它的余额。
      for (Map.Entry<HouseholdId, Long> ask :
          apportionToHouseholds(plan.ownKeys, householdEconomies, remaining).entrySet()) {
        long drawn = Math.min(stockOf(householdGoods, ask.getKey(), commodity), ask.getValue());
        if (drawn <= 0L) {
          continue; // 缸空/不够 ⇒ 这一户出不起它那一份（下面的补齐可能从**同层的别人**那里补上）
        }
        recordInputDraw(
            ask.getKey(),
            unit,
            commodity,
            drawn,
            householdEconomies,
            householdGoods,
            householdMoney,
            consumedGoods,
            householdOfActor,
            ledger);
        remaining -= drawn;
        drawnTotal.merge(commodity, drawn, Long::sum);
      }
      // ③ 一b：缺口由**同层还有货的账**补齐（持仓量降序、取多少 = 缺多少）。
      if (remaining > 0L) {
        remaining =
            drawFromKeys(
                plan,
                byStockDescending(plan.ownKeys, householdGoods, commodity),
                commodity,
                remaining,
                drawnTotal,
                householdEconomies,
                householdGoods,
                householdMoney,
                consumedGoods,
                householdOfActor,
                ledger);
      }
      // ③ 二：★ H6-lite —— **争用**的商品的池子里、**别人名下**的账（持仓降序）。
      List<HouseholdId> shared = plan.sharedKeys.get(commodity);
      if (remaining > 0L && shared != null) {
        List<HouseholdId> others = new ArrayList<>(shared.size());
        for (HouseholdId key : shared) {
          if (!plan.ownKeys.contains(key)) {
            others.add(key);
          }
        }
        drawFromKeys(
            plan,
            byStockDescending(others, householdGoods, commodity),
            commodity,
            remaining,
            drawnTotal,
            householdEconomies,
            householdGoods,
            householdMoney,
            consumedGoods,
            householdOfActor,
            ledger);
      }
    }
    if (!drawnTotal.isEmpty()) {
      // ★★★ B1（D-032）：旧代码在这里 `units.put(plan.id, unit.withCycleState(..., accumulated))`
      //   ——那是 economy **就地写实例状态**的最后一处。B1 起现扣结果只累进**本分区副本**
      //   （{@code partition.units()} 带回去），由协调器经 {@code EconomySession} 汇总后交给
      //   {@code ProductionRuntime#executeDay} 的 {@code finalInputsMilli} ⇒ 周期投入的权威落在 production。
      Map<CommodityId, Long> accumulated = new LinkedHashMap<>(unit.cycleInputUsedMilli());
      for (Map.Entry<CommodityId, Long> entry : drawnTotal.entrySet()) {
        accumulated.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      units.put(plan.id, unit.withCycleState(unit.progressDays(), accumulated));
    }
  }

  /**
   * 从一串候选账里按序取料直到 {@code remaining} 满足（{@link #drawOneIndustryInputs} 的 ③一b 与 ③二 共用）。
   *
   * @return 还没取满的余量（0 = 取满了）
   */
  private static long drawFromKeys(
      InputPlan plan,
      List<HouseholdId> keys,
      CommodityId commodity,
      long remaining,
      Map<CommodityId, Long> drawnTotal,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    long left = remaining;
    for (HouseholdId key : keys) {
      if (left <= 0L) {
        break;
      }
      long drawn = Math.min(stockOf(householdGoods, key, commodity), left);
      if (drawn <= 0L) {
        continue;
      }
      recordInputDraw(
          key,
          plan.unit,
          commodity,
          drawn,
          householdEconomies,
          householdGoods,
          householdMoney,
          consumedGoods,
          householdOfActor,
          ledger);
      left -= drawn;
      drawnTotal.merge(commodity, drawn, Long::sum);
    }
    return left;
  }

  /**
   * ★★ <b>relation 指名的投入供方 → 它那一本（或那一批）账</b>（H3；两处边界见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <pre>
   * ToCohort(c)                  ⇒ {c}（**必须存在**、且必须住在该 unit 那一格 —— 见下）
   * ToActor(a) 且 a 是本世界现存的家户 actor ⇒ {cohortOf(a)}（唯一拼写点 HouseholdActors）
   * ToActor(a) 其余（聚合主体：庄园/作坊/产业型家户）⇒ **该 unit 名下的家户账**（householdKeysOf，唯一事实）
   * </pre>
   *
   * <p>★★ <b>两条 fail-closed</b>：命名的 cohort 必须有行；命名的账必须住在该 unit 那一格。
   */
  private static List<HouseholdId> supplierAccountsOf(
      Payee supplier,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ActorRef, HouseholdId> householdOfActor,
      SettlementIndex index) {
    if (supplier instanceof Payee.ToHousehold toHousehold) {
      HouseholdId key = toHousehold.household();
      if (!householdEconomies.containsKey(key)) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账，"
                + "把'配置错'静默当成'缸里没有'会让账面上看不出问题）：unit="
                + unit.id()
                + " 供方="
                + key);
      }
      requireSupplierHex(householdEconomies.get(key).view().hex(), industry);
      return List.of(key);
    }
    if (supplier instanceof Payee.ToCohort toCohort) {
      // ★ S1 兼容档：旧档的 ToCohort 按视图反查**恰一个**家户（构造期归一化已把一对一的转成
      //   ToHousehold；走到这里说明视图有歧义或状态还没归一，故 fail-closed，不猜第几个）。
      HouseholdId matched = null;
      for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
        if (householdEconomy.view().equals(toCohort.cohort())) {
          if (matched != null) {
            throw new IllegalStateException(
                "旧档 ToCohort 视图对上了多个家户（S1 起视图不再是唯一身份）："
                    + toCohort.cohort()
                    + " ⇒ "
                    + matched
                    + " / "
                    + householdEconomy.id());
          }
          matched = householdEconomy.id();
        }
      }
      if (matched == null) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账）：unit="
                + unit.id()
                + " 供方视图="
                + toCohort.cohort());
      }
      requireSupplierHex(householdEconomies.get(matched).view().hex(), industry);
      return List.of(matched);
    }
    ActorRef actor = ((Payee.ToActor) supplier).actor();
    HouseholdId named = householdOfActor.get(actor);
    if (named != null) {
      requireSupplierHex(householdEconomies.get(named).view().hex(), industry);
      return List.of(named);
    }
    // ★ 聚合主体：它的账在 actor 切片上（economy 看不见）⇒ 由**该 unit 名下的家户账**代理（见方法注释）。
    List<HouseholdId> proxied = index.householdsOf(unit.id());
    for (HouseholdId key : proxied) {
      requireSupplierHex(householdEconomies.get(key).view().hex(), industry);
    }
    return proxied;
  }

  /** 供方账必须在该 unit 的产业那一格（一条转移只有一个 {@code location}；见 {@link #supplierAccountsOf}）。 */
  private static void requireSupplierHex(
      HexCoord supplierLocation, IndustryDefinitionView industry) {
    HexCoord location = hexOfIndustry(industry.id());
    if (!supplierLocation.equals(location)) {
      throw new IllegalStateException(
          "关系指名的投入供方不住在这个 unit 那一格（H3 fail-closed：账户 = (actor, location)，"
              + "跨格的供方说不清料从哪本账出）：activity="
              + industry.id()
              + " 供方的格="
              + supplierLocation
              + " 产业的格="
              + location);
    }
  }

  /**
   * ★★ <b>把一笔需求按各家的<b>人口</b>分派</b>（{@link ProportionalSplit#byDenominator}：**最大余数法** ⇒ Σ分派 ==
   * total）。
   *
   * <p>★ <b>口径与改前的分界</b>：改前这件事发生在 {@code rowSharesOf} 里、且分母是"该产业家户的总人口"、**逐行向下取整后余数丢弃** ⇒
   * 需求被切碎（实测：50 台织机只开 48 台）。现在同一件事只做一次、余数按**最大余数法**分派 ⇒ <b>总量一分不丢</b>。
   *
   * <p>★ 人口为 0 的家户**不进表**（份额恒 0 ⇒ 与"它不出工"同义 —— 与改前的门槛逐字一致）；Σ人口为 0 ⇒ 空表（没有人 ⇒ 没有分摊， 于是"料筹不到"由 {@code
   * usableScale} 那一路表达）。
   *
   * @return 键序 = {@code keys} 的传入序（保序，可复现）
   */
  private static Map<HouseholdId, Long> apportionToHouseholds(
      List<HouseholdId> keys, Map<HouseholdId, HouseholdEconomy> householdEconomies, long total) {
    Map<HouseholdId, Long> apportioned = new LinkedHashMap<>();
    if (keys.isEmpty() || total <= 0L) {
      return apportioned;
    }
    long population = 0L;
    for (HouseholdId key : keys) {
      population += householdEconomies.get(key).population();
    }
    if (population <= 0L) {
      return apportioned;
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      weights[i] = householdEconomies.get(keys.get(i)).population();
    }
    long[] parts = ProportionalSplit.byDenominator(total, weights, population);
    for (int i = 0; i < keys.size(); i++) {
      if (parts[i] > 0L) {
        apportioned.put(keys.get(i), parts[i]);
      }
    }
    return apportioned;
  }

  /**
   * 第二层兜底的取用次序：**持仓量降序**（谁缸里多谁先出）；<b>并列时保持传入序</b>（{@code List.sort} 是稳定排序 ⇒ 结果是"传入序 +
   * 持仓量"的纯函数，可复现）。
   */
  private static List<HouseholdId> byStockDescending(
      List<HouseholdId> keys,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      CommodityId commodity) {
    List<HouseholdId> sorted = new ArrayList<>(keys);
    sorted.sort(
        Comparator.comparingLong((HouseholdId key) -> stockOf(householdGoods, key, commodity))
            .reversed());
    return sorted;
  }

  /**
   * ★★ <b>把一笔投入的划转落账</b>（H3；两条路径，见 {@link #drawCycleInputs} 的方法注释）：
   *
   * <pre>
   * ① 供方 ≠ 经营者，且经营者的账**在会话副本里**（"地主出种"那类）⇒ 走转移：
   *      mint(INPUT_REQUISITION: 供方 actor → 经营者 actor) → applyTransferToHouseholds（唯一 applier：供方 −、经营者 +）
   *      → 随后在**经营者**账上消费掉这一笔（consumed 记在经营者：它是这批料的消费者）；
   * ② 其余情形（供方 == 经营者；或经营者的账不在副本里 —— 聚合主体）⇒ 不产生转移：
   *      供方 −余额、consumed 记在**供方**（H2 的既有口径："日耗与投入不是转移"）。
   * </pre>
   *
   * <p>★ 两条路径的<b>共同点</b>（也是守恒式成立的关键）：被扣的账**一定**是会话副本里的那一本（或转移的两端都在副本里）， 且 {@code consumed}
   * <b>恰好记一次</b>（记在"这批料的消费者"那本账上）⇒ {@code Σ(前库存) − Σ(后库存) == Σconsumed − Σincome} 那条逐商品守恒式两条路都成立。
   *
   * @param supplierKey 出料的那个家户（余额由此扣除）
   * @param drawn 已经算好的实扣量（&gt; 0，且 ≤ 该家户在该商品上的余额）
   */
  private static void recordInputDraw(
      HouseholdId supplierKey,
      ProductionProcess unit,
      CommodityId commodity,
      long drawn,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    HouseholdId operatorKey = householdOfActor.get(unit.operator());
    // ★★ **T4：投入同时记进当天的 ledger**（I4.2 的 `ΣProductionInputs` 的唯一来源）——
    //   它与"这东西从谁的账上出"是两件事：前者是**读数**，后者是**转移凭据**（H2 的口径）。
    //   ★ ledger 的键仍是**产业模板 id**（读数按产业汇总），不是 unit id。
    ledger.addInput(unit.industry(), commodity, drawn);
    if (operatorKey != null && !operatorKey.equals(supplierKey)) {
      // ① 两个主体都在家户账里 ⇒ 走转移（唯一 applier 落副本），随后由经营者消费掉。
      HouseholdEconomy supplierHouseholdEconomy = householdEconomies.get(supplierKey);
      if (supplierHouseholdEconomy == null) {
        throw new IllegalStateException(
            "投入供方不在家户表里（账户 must 有 location，拒绝静默用 (0,0)）: " + supplierKey);
      }
      Transfer requisition =
          ledger.mint(
              HouseholdActors.of(supplierKey),
              unit.operator(),
              supplierHouseholdEconomy.view().hex(),
              Map.of(commodity, drawn),
              TransferReason.INPUT_REQUISITION);
      applyTransfer(householdGoods, householdMoney, householdOfActor, requisition);
      consumeFromHousehold(householdGoods, consumedGoods, operatorKey, commodity, drawn);
      return;
    }
    // ② 其余情形：投入被生产直接吃掉（不写"给产业"的那条转移 —— 收端的账不在家户副本里，见方法注释）。
    long stock = stockOf(householdGoods, supplierKey, commodity);
    setStock(householdGoods, supplierKey, commodity, stock - drawn);
    addGoods(consumedGoods, supplierKey, commodity, drawn);
  }

  /** ★★ 索引口径的产能规模（R4-B.3a-perf；算式仍由 {@link ProductionProcessBook} 唯一拼写点给出）。 */
  static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, SettlementIndex index) {
    return ProductionProcessBook.capacityScaleOf(unit, industry, index);
  }

  /** ★★ 索引口径的计划规模（R4-B.3a-perf；产能规模查入口索引，计划系数算式不变）。 */
  static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
  }

  /** ★★ 索引口径的“投入没凑齐”判据（R4-B.3a-perf；算式与旧签名逐字相同，只是计划规模查索引）。 */
  static boolean inputShortfallOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    long planned = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    if (planned <= 0L) {
      return false;
    }
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long required = entry.getValue() * planned;
      long used = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      if (used < required) {
        return true;
      }
    }
    return false;
  }

  /** ★★ 索引口径的“一个批次摊到哪些家户行”（R4-B.3a-perf；只有调用方仍需给 target 集合，不再扫全量配额）。 */
  private static List<HouseholdId> householdKeysOfLot(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      List<ProductionUnitId> units,
      ResidenceKind residence,
      SettlementIndex index) {
    LinkedHashSet<ProductionUnitId> targets = new LinkedHashSet<>(units);
    LinkedHashSet<HouseholdId> keys = new LinkedHashSet<>();
    for (ProductionUnitId target : targets) {
      for (HouseholdLaborCommitment laborCommitment : index.allocationsOfUnit(target)) {
        if (ResidenceKind.ofLot(laborCommitment.group()) == residence
            && householdEconomies.containsKey(laborCommitment.household())) {
          keys.add(laborCommitment.household());
        }
      }
    }
    List<HouseholdId> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  // ★ B12 / DEF-T6-4 #5（UPM_UNCALLED_PRIVATE_METHOD）：原 `unitsOfHouseholds(...)` 已**删除**。
  //   读者—写者对照（§14.18.3 要求的处置形制）：
  //   · 读者：本类内 **0 处**；它是 `private static`，类外不可达 ⇒ 全仓读者 **0**（`grep` 只命中
  //     `SettlementIndex` 的注释与 `DebtCapacityBook` 的同名局部变量，不是本方法）。
  //   · 写者：它只**读**三张入参表（classes / production 实例投影 / 配额表），自身不写任何状态。
  //   · 替代：同一事实的当刻索引由 `SettlementIndex#unitsOfHousehold`（日结算入口一次建好）承担；
  //     周期翻篇判定读 `settlementIndex.industriesByHex()` + `unitsOfHousehold`，不再现扫配额表。
  //   ⇒ 纯死代码：无读者即无行为，删除不改变任何结算路径。

  /**
   * ★★ <b>每条家户行的 {@code cycleDays}</b>：取**它供给的那些产业的最长周期**。
   *
   * <p>★★ <b>W5.5 后的用途收窄（如实记）</b>：它原先服务两处 —— ① 借粮腿放贷方的"本周期自需"（{@code lendableOf}， 已随该腿删除）；②
   * 各行流水的"本期"何时翻篇（{@link FlowRow} 的清零窗口，**仍在用**）。⇒ 本方法保留，但它的 {@code max} 口径现在只服务流水翻篇；{@code
   * DebtCapacityBook}（质押/headroom 的唯一算法，服务清算与只读诊断） 仍按同一份口径自算一次（它有自己的 {@code
   * cycleDaysByHousehold(EconomyData, Map, ProductionDefinitionLookup)}）。
   *
   * <p>★ <b>为什么取 max</b>：多产业的家户没有一个自己的周期 ⇒ 取**最长**的那个是保守方向（不会把它这一周期的口粮当余粮）。 真档三个产业的 {@code
   * cycleDays} 都是 120 ⇒ 与改前逐值相同。
   *
   * <p>★ <b>没有配额的家户</b>（供给集合为空）：退回**本格产业的最长周期**（那格至少有一个产业 —— 见 {@code EconomyData} 的
   * 构造期守卫）；连产业都没有的格 ⇒ 记 0（保留额 0，与改前的隐含口径同侧：没有周期就没有"整周期自需"）。
   *
   * @param industriesByHex 格键 → 该格产业 id（日结算由 {@link SettlementIndex#industriesByHex()}
   *     一次给出；读口见公开重载）
   */
  private static Map<HouseholdId, Long> cycleDaysByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, Set<ProductionUnitId>> unitsOfHousehold,
      Map<String, List<IndustryId>> industriesByHex) {
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry : householdEconomies.entrySet()) {
      HouseholdId key = householdEntry.getKey();
      Set<ProductionUnitId> supplied = unitsOfHousehold.getOrDefault(key, Set.of());
      long cycleDays = 0L;
      for (ProductionUnitId unitId : supplied) {
        ProductionProcess unit = units.get(unitId);
        IndustryDefinitionView industry = unit == null ? null : industries.get(unit.industry());
        if (industry != null) {
          cycleDays = Math.max(cycleDays, industry.cycleDays());
        }
      }
      if (cycleDays == 0L) {
        HouseholdEconomy householdEconomy = householdEntry.getValue();
        if (householdEconomy != null) {
          // ★ R4-B.3a-perf：本格产业由入口索引一次给出（旧实现逐无配额家户扫全量产业表）。
          for (IndustryId industryId :
              industriesByHex.getOrDefault(
                  IndustryHexKeys.hexKey(
                      householdEconomy.view().hex().q(), householdEconomy.view().hex().r()),
                  List.of())) {
            IndustryDefinitionView industry = industries.get(industryId);
            if (industry != null) {
              cycleDays = Math.max(cycleDays, industry.cycleDays());
            }
          }
        }
      }
      byHousehold.put(key, cycleDays);
    }
    return byHousehold;
  }

  // ★★★ B5（§13.15.4 裁定二十）：原公开读口 `cycleDaysByHousehold(EconomyData, units, definitions)`
  //   （main + test 全仓 0 调用点）**整段删除**。★ 日结算真正在用的那条**私有**算法
  //   `cycleDaysByHousehold(householdEconomies, industries, units, unitsOfHousehold,
  // industriesByHex)`
  //   一字未动（`EconomySettlement` 内的逐日调用点仍读它）。

  // ── 消费（缺现金的家户在**市场轮**里先借钱再买货；本段不再有任何借贷）──────────────────────

  /**
   * ★ <b>本日关账的一个 unit</b>（H5/R3B.2）：它的 id、产业模板 id、它名下的家户行 —— 关账日的结算与读数要等
   * <b>市场与借粮</b>走完才跑，故先把这几样收起来。
   *
   * <p>★★ <b>W3（2026-10-11）更正：本记录不再服务任何"判死/缩劳动"</b> —— 那条路径已整体删除（经济侧只 {@linkplain
   * #reportHouseholdGaps 报缺口}，死亡率归 Social）。★ Batch 3 起饿死判据的分母口径（{@code
   * cycleNaturalNeedMilli}）也随之一并作废：缺口现在按<b>当日</b>报（见 {@link EconomyHouseholdGap}）。
   */
  private record ClosedUnit(
      ProductionUnitId unit, IndustryId industry, List<HouseholdId> keys, boolean inputShortfall) {}

  /**
   * ★★ <b>E4b：本日借粮用的 {@link DebtCapacity} 映射</b>（唯一算法在 {@link DebtCapacityBook}）。
   *
   * <p>★★ <b>三个“流量”的窗口为什么这样取</b>（与终态 {@code FlowRow} 的写入逐字对齐）：
   *
   * <pre>
   * afterAllocationGrainIncome = (本周期翻篇 ? 0 : 流水中的 income[grain])  + 今日 earned[grain]
   * cycleToDateGrainConsumed   = (本周期翻篇 ? 0 : 流水中的 consumed[grain]) + 今日 consumed[grain]
   * taxPaid                    = (本周期翻篇 ? 0 : 流水中的 taxPaid)          // 当前生产路径恒 0，照实读
   * </pre>
   *
   * ★ 三条与 {@code settleOneDayInto} 末尾写 {@code FlowRow} 时的 {@code acc =
   * newCycleHouseholds.contains(key) ? null : flows.get(key)} 是同一条口径 ⇒ 借粮读到的“本周期” 与关账那一支读到的不是两个窗口（旧
   * {@code creditLinesOf} 只读当日 {@code income} 局部量，是另一个窗口 —— 见交付报告）。
   *
   * <p>★ 库存由调用点保证（{@code requireHouseholdAccounts}）⇒ 本方法里每行都“读得到”；读口那边才可能出现空（具名缺失）。
   *
   * <p>★★ <b>债务价格钩子由调用方注入</b>（D-030 §3.4）：生产借贷/清算路径传 {@link
   * DebtCapacityBook#marketPriceLookup(java.util.Map)} 的市场价目表 lookup；旧读口仍可传 {@link
   * DebtCapacityBook#NO_UNIT_PRICES} 保留旧口径。
   */
  private static Map<HouseholdId, DebtCapacity> debtCapacitiesForDay(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, FlowRow> flows,
      Set<HouseholdId> newCycleHouseholds,
      Map<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex settlementIndex,
      DebtCapacityBook.DebtUnitValueLookup debtUnitValueLookup) {
    Map<HouseholdId, Long> cycleToDateIncome = new LinkedHashMap<>();
    Map<HouseholdId, Long> cycleToDateConsumed = new LinkedHashMap<>();
    Map<HouseholdId, Long> taxPaid = new LinkedHashMap<>();
    for (HouseholdId key : householdEconomies.keySet()) {
      FlowRow accrual = newCycleHouseholds.contains(key) ? null : flows.get(key);
      long earnedToday = income.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L);
      long consumedToday = consumed.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L);
      cycleToDateIncome.put(
          key,
          Math.addExact(
              accrual == null ? 0L : accrual.income().getOrDefault(GRAIN, 0L), earnedToday));
      cycleToDateConsumed.put(
          key,
          Math.addExact(
              accrual == null ? 0L : accrual.consumed().getOrDefault(GRAIN, 0L), consumedToday));
      taxPaid.put(key, accrual == null ? 0L : accrual.taxPaid());
    }
    return DebtCapacityBook.capacities(
        householdEconomies,
        cycleToDateIncome,
        cycleToDateConsumed,
        taxPaid,
        key -> OptionalLong.of(grainOf(householdGoods, key)),
        cycleDaysByHousehold,
        debts,
        units,
        industries,
        assetShares,
        operatorConditions,
        settlementIndex
            .d4AssetOccupancyBudget()
            .orElseThrow(
                () -> new IllegalStateException("asset-occupancy-unavailable: debt-capacity")),
        DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED,
        debtUnitValueLookup);
  }

  /*
   * ★ E4b/D-030/D-031：新口径的唯一算法仍在 DebtCapacityBook.capacities，但现在只服务清算/阶层下滑/只读诊断；
   * 借粮（4b）与市场信用都已不再把 headroom 当借款门（D-031：借款人无上限，唯一上限 = 放贷人实际可借头寸）。
   * 旧 {@code LOAN_INCOME_MULTIPLE_PER_MILLE} 兼容别名已删（W2-fix6；κ 的唯一拼写点是 {@code DebtCapacity.CREDIT_F_MULTIPLE_PER_MILLE}）。
   */

  /**
   * ★★ <b>D-030 §3.4/§3.5 的家户 → 市场区默认价目表索引</b>：
   *
   * <pre>
   * ① 该家户本格有市场条目 ⇒ 用它；
   * ② 否则若该格在本日拓扑的成员表里 ⇒ 用该区锚格的市场条目（区默认价目表）；
   * ③ 都没有 ⇒ 缺键（该债 unpriced、identity 腿仍可付）。
   * </pre>
   *
   * <p>★ 只读派生、不写市场表；单区（D-027）时锚格就是规范序第一个市场格。缺格不是坏数据：没有市场默认价目表的家户 仍可原物原还。
   */
  private static Map<HouseholdId, MarketTable> marketIndexByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    LinkedHashMap<HouseholdId, MarketTable> byHousehold = new LinkedHashMap<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      MarketTable market = marketForHex(householdEconomy.view().hex(), markets, topology);
      if (market != null) {
        byHousehold.put(householdEconomy.id(), market);
      }
    }
    return byHousehold;
  }

  /** 单格 → 市场区默认价目表：本格优先，缺则区锚格；没有归属 ⇒ null（合法状态）。 */
  private static MarketTable marketForHex(
      HexCoord hex, Map<MarketZoneId, MarketTable> markets, MarketTopology topology) {
    if (topology == null || !topology.contains(hex)) {
      return null;
    }
    // ★ W1：本格价表 = 本格所属市场的那一张（区内任何格同一张；区 id = 拓扑节点 id）。
    return markets.get(new MarketZoneId(topology.regionOf(hex).node().nodeId()));
  }

  /**
   * ★★ <b>E1：退出经营者的固定顺序事实处置</b>（{@code SUSPENDED -> EXITED} / {@code EXITING -> EXITED}）。
   *
   * <pre>
   * 1. 劳动释放   ：删除 activity == exit.unit 的全部 HouseholdLaborCommitment（laborSupply 不动），释放量进读数
   * 2. 资产处置   ：unit 名下（industry + operator）份额里，owner != operator 的只把 operator 改回 owner；
   *                owner == operator 的份额原样留在 owner 名下（unit 已停业，计划系数 0）；
   *                并同步把退回份额从该 operator 的生产组织 assetSources 里摘掉（GAP-2：不得留下 operator != organizer 的引用）
   * 3. 债务处置   ：只对解析出的 exit.household 执行（null = 聚合主体 ⇒ 跳过，不伪造家户债）：
   *                逐条按 (到期周期, 商品维, id) canonical 升序；paid = min(本金, 可用余额)；
   *                paid > 0 ⇒ 铸 LOAN_REPAYMENT 转移（唯一写口 applyTransfer）；不足才 defaulted=true
   * 4. 库存/货币  ：剩余留在原主体账上，不没收、不蒸发
   * 5. 状态与理由 ：状态机已置 EXITED；这里只把处置摘要追加进 lastReason
   * </pre>
   *
   * <p>★★ <b>五条硬边界</b>：① 债务只处置解析出的家户债务人（聚合主体的债不在 economy 会话里，不能凭空给它销债）；② 还不起的部分**只标 {@code
   * defaulted}**，不从表里删、不由结算“核销”； ③ 剩余库存/货币留在原主体账上（没有“没收”规则，不凭空造也不删）；④ 资产份额**只改
   * operator**，owner/quantity/kind 与 id 不变； ⑤ 每个 unit 只在本列表里处置一次（状态机被判 EXITED 后不再自转）。
   *
   * <p>★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link OwnershipStakeBook}</b>。理由：它做的是 <b>id 保持不变的
   * operator 回主</b>（份额身份编码了 operator；{@code OwnershipStakeBook.transfer} 会按新 tuple 生成新
   * id，与本方法的契约「id 不变」冲突）。这是对"资产份额转移只走唯一写口"的<b>显式记为遗留的例外</b>，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link
   * OwnershipStakeBook}。若要收口，应由 Book 提供一个批量、原子、id 保持的 operator 重指派口（E5a 未做，避免在无测试保护的阶段改这条低频处置路径的 id
   * 语义）。
   */
  private static void settleOperatorExits(
      List<OperatorSettlement.Exit> exits,
      LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      SettlementIndex index,
      ProductionLedger.Accumulator ledger,
      long day,
      MoneyIssuanceJournal issuanceJournal) {
    for (OperatorSettlement.Exit exit : exits) {
      // ── 1. 释放劳动：删除 activity == 本 unit 的全部配额（laborSupply 一字不动）────────────────
      long releasedLaborMilli = 0L;
      int releasedAllocations = 0;
      for (LaborAllocationId allocationId : index.allocationIdsOfUnit(exit.unit())) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
        if (laborCommitment == null || !laborCommitment.activity().equals(exit.unit().value())) {
          continue; // 索引是入口快照；处置过程中可能已被前一个 exit 改动 ⇒ 按当前行再核一次
        }
        releasedLaborMilli += laborCommitment.laborMilli();
        laborCommitments.remove(allocationId);
        releasedAllocations++;
      }

      // ── 2. 资产处置：TENANCY/委托份额 operator 改回 owner；OWNED 份额留在 owner 名下 ──────────
      //   不得删份额、不得改 owner/quantity/kind；id 不变（opaque 身份，operator 只是行内一栏）。
      int returnedShares = 0;
      long returnedQuantity = 0L;
      int keptOwnedShares = 0;
      Set<AssetShareId> returnedShareIds = new LinkedHashSet<>();
      for (AssetShareId shareId : index.ownershipStakeIdsOfProcess(exit.unit())) {
        OwnershipStake share = assetShares.get(shareId);
        if (share == null
            || !share.industry().equals(exit.industry())
            || !share.operator().equals(exit.operator())) {
          continue; // 同上：用当前行再核作用域，避免处理已被前一个 exit 改过的份额
        }
        if (!share.owner().equals(exit.operator())) {
          assetShares.put(
              shareId,
              new OwnershipStake(
                  share.id(),
                  share.industry(),
                  share.asset(),
                  share.owner(),
                  share.owner(), // ★ 只改 operator：份额回到所有者、可再出租
                  share.quantity(),
                  share.kind(),
                  share.location()));
          returnedShares++;
          returnedQuantity += share.quantity();
          returnedShareIds.add(shareId);
        } else {
          keptOwnedShares++; // owner == operator：本来就是它自己的，unit 不再运行（计划系数 0），份额原样留下
        }
      }
      // ★★ GAP-2：退回 owner 的份额已不由 exit.operator 经营；该 operator 的生产组织 assetSources
      //   不得继续指名它们（否则 EconomyData 的 operator==organizer 守卫会在 revision 边界 fail-closed）。
      detachReturnedSharesFromEnterprises(enterprises, exit.operator(), returnedShareIds);

      // ── 3. 债务处置：保留既有"剩余库存/货币先偿债、不足才 defaulted"逻辑 ──────────────────────
      HouseholdId debtor = exit.household();
      long debtPaidMilli = 0L;
      long debtDefaultedMilli = 0L;
      int debtDefaultedItems = 0;
      HexCoord location =
          IndustryHexKeys.hexKeyOf(exit.industry()).map(HexCoord::parse).orElse(null);
      if (debtor != null && location != null) {
        // ★ 索引给出该债务人的债务 id 序（全局债务表序）；再逐条取活表当前值（前面的 exit 可能已改本金）。
        List<DebtContract> owed =
            new ArrayList<>(index.debtsByDebtor().getOrDefault(debtor, List.of()));
        owed.sort(
            Comparator.comparingLong((DebtContract debt) -> debt.dueCycle().orElse(Long.MAX_VALUE))
                .thenComparing(
                    debt ->
                        switch (debt.unit()) {
                          case DebtUnit.Commodity commodity -> commodity.commodity().value();
                          case DebtUnit.Money ignored -> "~money";
                        })
                .thenComparing(debt -> debt.id().value()));
        for (DebtContract snapshot : owed) {
          DebtContract debt = debts.get(snapshot.id());
          if (debt == null || !debt.debtor().equals(debtor)) {
            continue;
          }
          if (DebtContractBook.isSelfHeld(debt)) {
            // ★★★ W5-fix（BLOCKED-2 同族，2026-10-11）：**自持合同（debtor == creditor）不是可执行的债权**（见
            //   `repayDebts` 里 DEBT_REPAY_SELF_HELD_SKIPPED 那一段的同一口径）⇒ 这里若照旧铸
            //   debtor → creditor 的清偿转移，`Transfer` 会因"两端相等"当场抛（退出清算把自持债算进去 = 同一个坑）。
            //   处置**与还债轮同一口径**：排除、不铸转移、**不判违约**（它是记账事实，不是坏数据），
            //   本金与状态一概不动；**不静默**：DEBUG 具名一条。
            if (TRACE.isDebugEnabled()) {
              EventLog.channel(TRACE)
                  .debug(
                      LogEvent.of(
                          "ENTERPRISE_EXIT_SELF_HELD_DEBT_SKIPPED",
                          EconomyLogSource.ECONOMY_DEBT_STATE,
                          "day",
                          day,
                          "household",
                          debtor.value(),
                          "debtContract",
                          debt.id().value(),
                          "unit",
                          debt.unit().key(),
                          "outstandingPrincipal",
                          debt.principal(),
                          "why",
                          "self-held-not-executable"));
            }
            continue;
          }
          long available =
              switch (debt.unit()) {
                case DebtUnit.Commodity commodity ->
                    stockOf(householdGoods, debtor, commodity.commodity());
                case DebtUnit.Money money ->
                    // ★ 货币腿按合同自己的币种判余额，不拿“逐币种合计”冒充（后者会让一笔银转移超过银余额，
                    //   唯一 applier 当场抛）。旧档迁移的货币债币种 = 银，与旧“只按银余额判”逐值相同。
                    householdMoney
                        .getOrDefault(debtor, Map.of())
                        .getOrDefault(money.currency(), 0L);
              };
          long paid = Math.min(debt.principal(), Math.max(0L, available));
          if (paid <= 0L) {
            if (debt.principal() > 0L) {
              DebtContractBook.markStatus(debts, debt.id(), DebtStatus.DEFAULTED);
              debtDefaultedMilli += debt.principal();
              debtDefaultedItems++;
            }
            continue;
          }
          Transfer repayment =
              switch (debt.unit()) {
                case DebtUnit.Commodity commodity ->
                    ledger.mint(
                        HouseholdActors.of(debt.debtor()),
                        HouseholdActors.of(debt.creditor()),
                        location,
                        Map.of(commodity.commodity(), paid),
                        Map.of(),
                        TransferReason.LOAN_REPAYMENT);
                case DebtUnit.Money money ->
                    ledger.mint(
                        HouseholdActors.of(debt.debtor()),
                        HouseholdActors.of(debt.creditor()),
                        location,
                        Map.of(),
                        Map.of(money.currency(), paid),
                        TransferReason.LOAN_REPAYMENT);
              };
          applyTransfer(
              householdGoods, householdMoney, householdOfActor, repayment, issuanceJournal);
          long remaining = debt.principal() - paid;
          // ★ 不足才违约；还清 = SETTLED、本金 0（历史留痕，不从表里删）。E4c：写口收在 DebtContractBook。
          DebtContractBook.reduce(debts, debt.id(), paid);
          DebtContractBook.markStatus(
              debts, debt.id(), remaining > 0L ? DebtStatus.DEFAULTED : DebtStatus.SETTLED);
          debtPaidMilli += paid;
          if (remaining > 0L) {
            debtDefaultedMilli += remaining;
            debtDefaultedItems++;
          }
        }
      }

      // ── 4./5. 留存库存读数 + 状态与理由（只追加摘要，不改状态机的判据字段）────────────────────
      long keptGoodsMilli = 0L;
      long keptMoneyMilli = 0L;
      if (debtor != null) {
        for (long value : householdGoods.getOrDefault(debtor, Map.of()).values()) {
          keptGoodsMilli += value;
        }
        for (Long value : householdMoney.getOrDefault(debtor, Map.of()).values()) {
          keptMoneyMilli += value == null ? 0L : value;
        }
      }
      // ★★ P2-A §13.3：解析不到经济家户的聚合主体不持账 ⇒ 留存读数恒 0（不伪造经营者账）。
      OperatorCondition condition = operatorConditions.get(exit.unit());
      if (condition != null) {
        operatorConditions.put(
            exit.unit(),
            condition.withLastReason(
                exit.reason()
                    + ";disposition{laborReleasedMilli="
                    + releasedLaborMilli
                    + ",releasedAllocations="
                    + releasedAllocations
                    + ",assetSharesReturned="
                    + returnedShares
                    + ",assetQuantityReturned="
                    + returnedQuantity
                    + ",assetSharesKeptOwned="
                    + keptOwnedShares
                    + ",debtPaidMilli="
                    + debtPaidMilli
                    + ",debtDefaultedMilli="
                    + debtDefaultedMilli
                    + ",debtDefaultedItems="
                    + debtDefaultedItems
                    + ",keptGoodsMilli="
                    + keptGoodsMilli
                    + ",keptMoneyMilli="
                    + keptMoneyMilli
                    + "}"));
      }
    }
  }

  /**
   * ★★ <b>GAP-2：退出处置后的组织引用清理</b>。{@link #settleOperatorExits} 会把 TENANCY/委托份额的 {@code operator} 改回
   * {@code owner}；这些份额随即不再由退出 operator 经营，而它的 {@link ProductionEnterprise#assetSources()} 里可能仍留着旧引用
   * ⇒ revision 边界的 {@code EconomyData} 守卫会以「份额 operator 必须等于
   * organizer」fail-closed。这里按份额回主的事实把引用摘掉，<b>不改份额本身、
   * 不改组织身份/unit/laborSources</b>；份额退主后重新成为可租赁的闲置资产，下一期计划可见。
   */
  private static void detachReturnedSharesFromEnterprises(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      ActorRef operator,
      Set<AssetShareId> returnedShareIds) {
    if (returnedShareIds.isEmpty()) {
      return;
    }
    List<ProductionOrganizationId> enterpriseIds = new ArrayList<>(enterprises.keySet());
    enterpriseIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : enterpriseIds) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null || !enterprise.organizer().equals(operator)) {
        continue;
      }
      List<AssetShareId> kept = new ArrayList<>(enterprise.assetSources());
      if (!kept.removeIf(returnedShareIds::contains)) {
        continue; // 该组织没有引用这批退回份额：一字不动
      }
      enterprises.put(
          organizationId,
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              kept,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
  }

  /**
   * ★★ <b>E4c：同一天内资本化事件的稳定去重键</b>——{@code (payer, payee, unit, activity, rule)}。
   *
   * <p>★ 合同身份仍是 {@code DebtContractId} 的四元组 {@code (debtor, creditor, unit, terms)}；这里多出的 {@code
   * activity} / {@code rule} 只用于<b>同一份日账本内</b>识别"两笔读数是不是同一次事件"： 同一 unit +
   * 同一规则只有同一次事件才会重复，重复则累加、只写一次合同。跨周期同四元组照旧连续累加（E4a 语义不变）。
   */
  private record CapitalizationEventKey(
      ActorRef payer,
      ActorRef payee,
      String unitKey,
      ProductionUnitId activity,
      CompensationRule rule) {}

  /** 契约/跨切片一致性故障：ERROR 不降级，带稳定词返回给调用方抛出。 */
  private static IllegalStateException contractFault(String word, String detail) {
    EventLog.channel(TRACE)
        .error(
            LogEvent.of(
                "ECONOMY_SETTLEMENT_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_DEBT,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(word + ": " + detail);
  }

  /**
   * ★★ <b>E4c：欠租/欠薪资本化</b>（生产/租金阶段之后；见 {@code settleOneDayInto} 的 3b）。
   *
   * <pre>
   * 输入   = 本日 ProductionLedger 的 Arrear 读数（owed = 应付 − 实付 > 0）
   * 去重   = 同一天内 (payer, payee, unit.key(), activity, rule) 相同 ⇒ 读数额相加、只 upsert 一次
   * unit   = Arrear.currency 有值 ⇒ DebtUnit.Money(currency)；否则 DebtUnit.Commodity(commodity)
   * terms  = Arrear.Kind.WAGE ⇒ DebtTerms.wageArrearsDefault()（0‰）+ DebtSource.WAGE_ARREARS
   *          Arrear.Kind.RENT ⇒ DebtTerms.legacyDefault()（20‰）+ DebtSource.RENT_ARREARS
   *          其余类别 ⇒ fail-closed debt-source-missing（不得猜来源，也不得套工资债）
   * amount = owed；day = 当日；dueCycle = 当前周期 + 1
   * 端点   = P2：{@link DebtPartyResolver} 唯一解析点到责任家户；多户按人口最大余数拆 owed，
   *          再对每个 debtor × creditor 组合经 {@link DebtPartyResolver#splitDebtAmounts} 守恒拆分。
   *          任一端解析不到 ⇒ 具名 unresolved，跳过，不伪造端点；同户自债显式净额（不落合同）。
   * 库存   = **不动**：资本化只写债权本金；不铸转移、不扣库存/货币，也不清零 Arrear 读数
   * </pre>
   *
   * <p>★★ <b>手算例子</b>：某作坊 operator（家户 H1）本周期应付实物工资 1,000 毫粮、实付 400 ⇒ Arrear{@code owed = 600}。资本化后
   * {@code DebtContractId.idOf(H1, H2, Commodity(grain), legacyDefault)} 这条合同的本金 +600、 状态
   * NORMAL、{@code dueCycle = 当前周期 + 1}；H1 的行流水 {@code capitalizedArrears["commodity:grain"] +=
   * 600}； 粮库存一分不动（H1 与 H2 的账都保持原值）。同一日如果又读到一条完全相同的 600 欠款读数（同 activity/rule）， 去重后只写一次、金额
   * 1,200（{@code eventCount = 2}）。
   *
   * <p>★★ <b>多户 × 多户的金额守恒</b>：{@code owed} 先按债务人份额、再逐债务人按债权人份额走最大余数法；每个组合金额 {@code > 0} 才落合同，Σ合同金额
   * + 自债净额 == owed 由 {@link DebtPartyResolver#splitDebtAmounts} 用 {@code Math.addExact} 当场核对。组合数超过
   * {@link DebtPartyResolver#MAX_PARTY_COMBINATIONS} 时具名拒绝 （整笔进 unresolved，不静默丢）。
   *
   * @param data 结算前的不可变状态（读 classes / classPositions / classStandings /
   *     productionOrganizations）；不得为 null
   * @param index 当日只读派生索引（主体↔unit / 主体↔组织 / 家户 actor）；不得为 null
   * @param ledger 当日累加器（读 {@code arrears()}、写资本化/跳过审计）；不得为 null
   * @param rows 家户行工作表（解析债务人后补派生引用）；不得为 null
   * @param debts 债务工作表（唯一写口 {@link DebtContractBook#upsert}）；不得为 null
   * @param capitalizedByHousehold 本日逐户逐 unit 的资本化累加器（就地更新 ⇒ 进 {@code FlowRow.capitalizedArrears}）；
   *     不得为 null
   * @param day 资本化发生日（进新合同 {@code openedDay}）；不得为负
   * @param dueCycle 资本化合同的到期周期（当前周期 + 1）
   */
  private static void capitalizeArrears(
      EconomyDayView data,
      SettlementIndex index,
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedByHousehold,
      long day,
      long dueCycle,
      Set<DebtContractId> sameDayWageDebtIds) {
    List<ProductionLedger.Arrear> arrears = ledger.arrears();
    if (arrears.isEmpty()) {
      return; // 没有欠款读数 ⇒ 连去重表都不建（旧路径逐值不变）
    }
    // ★★ 第一步：按事件键稳定去重并累加（键序 = 读数在当日账本里的出现序 ⇒ 可复现）。
    LinkedHashMap<CapitalizationEventKey, Long> owedByEvent = new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, ProductionLedger.Arrear> sampleByEvent =
        new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, Integer> countByEvent = new LinkedHashMap<>();
    for (ProductionLedger.Arrear arrear : arrears) {
      DebtUnit unit = unitOf(arrear);
      CapitalizationEventKey key =
          new CapitalizationEventKey(
              arrear.payer(), arrear.payee(), unit.key(), arrear.activity(), arrear.rule());
      owedByEvent.merge(key, arrear.owed(), Math::addExact);
      sampleByEvent.putIfAbsent(key, arrear);
      countByEvent.merge(key, 1, Integer::sum);
    }
    // ★★ 第二步：逐事件解析两端 → 守恒拆金额 → 逐组合 upsert → 补派生引用 → 记审计。
    for (Map.Entry<CapitalizationEventKey, Long> entry : owedByEvent.entrySet()) {
      CapitalizationEventKey key = entry.getKey();
      ProductionLedger.Arrear sample = sampleByEvent.get(key);
      long owed = entry.getValue();
      DebtUnit unit = unitOf(sample);
      Optional<ProductionUnitId> activity = Optional.of(sample.activity());
      DebtPartyResolver.Resolution debtorShares =
          DebtPartyResolver.resolveActor(data, index, sample.payer(), null, activity);
      DebtPartyResolver.Resolution creditorShares =
          DebtPartyResolver.resolvePayee(data, index, sample.rule().recipient(), null, activity);
      if (!debtorShares.isResolved() || !creditorShares.isResolved()) {
        String reason = unresolvedDebtCapitalizationReason(debtorShares, creditorShares);
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                owed,
                reason));
        continue;
      }
      DebtPartyResolver.DebtSplit split =
          DebtPartyResolver.splitDebtAmounts(owed, debtorShares.shares(), creditorShares.shares());
      if (split.rejected()) {
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                owed,
                "split-rejected:" + split.rejectionReason()));
        continue;
      }
      if (split.selfNettedAmount() > 0L) {
        // ★ 同户自债没有可落合同的真实债权；金额仍守恒（split 内已核），这里显式记审计、不伪造对方。
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                split.selfNettedAmount(),
                "self-party-netted:" + split.selfHouseholds().size() + "-households"));
      }
      DebtTerms terms;
      DebtSource source;
      switch (sample.kind()) {
        case WAGE -> {
          // ★★★ D3/U23/R-11：旧 wage 路径也不再生息；必须同时写 WAGE_ARREARS 与 0‰。
          if (sample.currency().isEmpty()) {
            throw contractFault(
                "wage-debt-terms-unsupported",
                "工资欠款必须是 DebtUnit.Money(currency)，但该 Arrear 无货币: activity="
                    + sample.activity().value());
          }
          terms = DebtTerms.wageArrearsDefault();
          source = DebtSource.WAGE_ARREARS;
        }
        case RENT -> {
          terms = DebtTerms.legacyDefault();
          source = DebtSource.RENT_ARREARS;
        }
        default ->
            throw contractFault(
                "debt-source-missing",
                "capitalizeArrears 无法为欠款类别确定 DebtSource（不得猜）：kind="
                    + sample.kind()
                    + " activity="
                    + sample.activity().value());
      }
      for (DebtPartyResolver.DebtAmount amount : split.amounts()) {
        DebtContract contract =
            DebtContractBook.upsert(
                debts,
                amount.debtor(),
                amount.creditor(),
                unit,
                terms,
                source,
                amount.amount(),
                day,
                OptionalLong.of(dueCycle));
        if (source == DebtSource.WAGE_ARREARS) {
          // ★ D3-I13：旧 wage 路径同日新资本化的工资债也不得在同一 tick 还款轮被再付。
          sameDayWageDebtIds.add(contract.id());
        }
        HouseholdEconomy debtorHouseholdEconomy = householdEconomies.get(amount.debtor());
        if (debtorHouseholdEconomy == null) {
          // 端点来自 DebtPartyResolver（同源于 data.classes），解析到却查不到行 = 状态已被改坏，fail-closed。
          throw new IllegalStateException(
              "资本化解出的债务人不在 rows 里（DebtPartyResolver 与 rows 漂开）: " + amount.debtor());
        }
        if (!householdEconomies.containsKey(amount.creditor())) {
          throw new IllegalStateException(
              "资本化解出的债权人不在 rows 里（DebtPartyResolver 与 rows 漂开）: " + amount.creditor());
        }
        // ★★ 2026-10-09 选项 A：改前这里把资本化新合同的派生引用补进债务人行；拆表后引用表由构造期对账
        //   按 debts 工作表整表重建 ⇒ 不必再补（端点存在性校验照旧保留在上面）。
        ledger.addDebtCapitalization(
            new ProductionLedger.DebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                amount.debtor(),
                amount.creditor(),
                contract.id(),
                unit,
                amount.amount(),
                day,
                dueCycle,
                terms,
                CAPITALIZATION_TERMS_SOURCE,
                contract.principal(),
                countByEvent.get(key)));
        capitalizedByHousehold
            .computeIfAbsent(amount.debtor(), ignored -> new LinkedHashMap<>())
            .merge(unit.key(), amount.amount(), Math::addExact);
      }
    }
  }

  /** ★★ P2：未解析端点的具名 reason（payer / payee 各自带解析器内部原因，便于逐跳诊断）。 */
  private static String unresolvedDebtCapitalizationReason(
      DebtPartyResolver.Resolution debtors, DebtPartyResolver.Resolution creditors) {
    if (!debtors.isResolved() && !creditors.isResolved()) {
      return "payer-not-resolvable:"
          + debtors.reason()
          + ";payee-not-resolvable:"
          + creditors.reason();
    }
    if (!debtors.isResolved()) {
      return "payer-not-resolvable:" + debtors.reason();
    }
    return "payee-not-resolvable:" + creditors.reason();
  }

  /** ★ E4c：Arrear 的显式 unit（与 {@code RuleSettlement} 的"commodity/currency 恰其一"同源；坏数据 ⇒ 具名抛）。 */
  private static DebtUnit unitOf(ProductionLedger.Arrear arrear) {
    if (arrear.currency().isPresent()) {
      return DebtUnit.money(arrear.currency().orElseThrow());
    }
    if (arrear.commodity().isPresent()) {
      return DebtUnit.commodity(arrear.commodity().orElseThrow());
    }
    throw new IllegalStateException("Arrear 既没有 currency 也没有 commodity（构造期应已判死）: " + arrear);
  }

  /**
   * ★★ <b>P11.1 / D-023 / D-030：周期末偿还 —— “有啥付啥”，介质不设限</b>。
   *
   * <pre>
   * 债务人序 = HouseholdId.value 升序（稳定）
   * 合同序   = 未偿本金按共同价值升序；不可定价排最后（unit id 升序）
   *            tie = 债权人 id 升序 → 债的单位 id 升序 → DebtContractId.value
   * 条款闸   = 只走 RepaymentRule.AVAILABLE_SURPLUS_SHARE + InterestTiming.AFTER_REPAYMENT_ON_CLOSE
   *            其它组合 fail-closed 具名抛（不静默当默认档）
   * 可动用   = 商品：★★ v3.6（用户第六批裁定）= max(0, 余额 − 冻结) —— **不扣任何保留量**（有库存就还）
   *            × 还款份额千分比
   *            货币：max(0, 逐币种余额 − 冻结)（全部币种；不跨币种求和/折换）
   * 介质序   = ★ W2 起 = 该户**预估价值最低**的单位优先（S-5，用户 2026-10-10 第二批第 5 条）：
   *            {@code DebtValuation.estimatedUnitValueMilli} 升序，同值按单位 canonical 串升序。
   *            （旧口径"全部货币按余额降序 → 全部商品按余额降序、钱永远第一"= D-030 §4，已被取代）
   * 选择     = 逐条债（合同序）× 逐 medium（介质序）：identity 直接 1:1；否则用债务人价目表折成共同价值、
   *            整数 floor、最多还清本金；缺价 medium 具名跳过，换下一条
   * 付款     = 一组实际商品/货币腿铸成**唯一一条** LOAN_REPAYMENT 转移 → applyTransfer（带冻结/发行审计）
   * 本金     = 只减合同唯一的 principal（走 DebtContractBook.reduce；无 interest/principal 双账）
   * 缺价     = 持有资产但没有稳定价格 ⇒ 不折算、不静默付 0；记具名 DebtRepaymentSkip
   * 还不上   = **挂账、计息、不清零**（用户 0–8 第 7 条「没库存就记为实际债务」）—— 本方法不核销
   * </pre>
   *
   * <p>★★ <b>为什么不按 DebtUnit/币种分别核算</b>（D-023 第 3 条）：合同仍只有一条连续本金；利息仍按现有 {@link #chargeInterest}
   * 资本化进本金。介质自由只发生在“这笔本金用什么资产折付”这一层。
   *
   * <p>★★ <b>外部放贷主体 / 政府 / 家户一视同仁</b>（D-023 第 1 条）：收款人由 {@link DebtValuation.RepayeeResolver} 解析（默认
   * = 家户 actor）；只要其账户在本日 {@code AccountSession}/{@code actor} 账里可收，任意商品/货币腿都照收。收不了 ⇒ 具名
   * fail-closed，绝不静默吞款。
   *
   * <p>★★ <b>★★ v3.6（2026-10-11 用户第六批裁定）：还债不设任何保留窗口</b> —— 用户原话：「<b>为啥要设还债窗口，应当是有库存就还，
   * 没库存就不还啊</b>」⇒ 可动额 = 手头库存（含货币，逐 medium 按 S-5 估值升序取用），**不扣"下轮生产花费"、不扣"一日口粮"、不扣任何 前瞻量**（D-030 §4
   * 的"一日口粮保留"与 v3.5 的"35 天窗口"一并作废）。★ 唯一保留的前瞻扣除在<b>卖侧</b>（用户 0–8 第 5 条：投入市场的商品 = 扣除下一 tick
   * 生产需要后的），本方法不碰它。★ 冻结口径不弱化：任何腿都不越过冻结；还清后 {@link DebtContract} 留在表里（本金 0 的历史条不删，见 {@link
   * DebtContractBook#reduce}）。
   *
   * <p>★ <b>读数</b>：{@code repaid} 记本日实际走粮腿的毫粮（含以粮折付其它计价口径）；{@code repaidMoney}
   * 记本日实际走货币腿的逐币种毫钱；其它商品腿从合同 principal 下降 + 转移凭据读出。
   *
   * @param repaid 本日实际粮腿的逐户累加器（就地更新 ⇒ 进 {@code FlowRow.repaid}）；不得为 null
   * @param repaidMoney 本日实际货币腿的逐户逐币种累加器（就地更新 ⇒ 进 {@code FlowRow.repaidMoney}）；不得为 null
   * @param marketsByHousehold 债务人 → 其所在市场区默认价目表（缺键 = 没有市场默认价目表 ⇒ 只有 identity 腿可付）
   * @param repayeeResolver 债权人 → 收款 actor 的解析口（默认 {@link
   *     DebtValuation.RepayeeResolver#HOUSEHOLD_ACTORS}； 外部放贷主体/政府账户可换一份 resolver）
   * @param householdPrices 家户价目表（可选；W2 起由 {@link HouseholdValuationBook} 现算注入 —— 它是 S-5
   *     介质序与债务折价的<b>唯一</b>估值尺）
   * @param day 本日的绝对世界日（★ W2-fix7 / D5：还债的每一条事件都带 {@code day} —— 现场可按天直接检索/对账， 不再依赖"日志时间序的最近一个
   *     day"去推）
   */
  private static void repayDebts(
      long day,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      LinkedHashMap<HouseholdId, Long> repaid,
      LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> repaidMoney,
      LinkedHashMap<DebtContractId, Long> repaidPrincipalByDebt,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HouseholdId, MarketTable> marketsByHousehold,
      DebtValuation.RepayeeResolver repayeeResolver,
      DebtValuation.HouseholdPriceTable householdPrices,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal,
      Set<DebtContractId> sameDayWageDebtIds) {
    Objects.requireNonNull(sameDayWageDebtIds, "sameDayWageDebtIds（没有同日工资债给空集，不得为 null）");
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = DebtIndex.byDebtor(debts);
    List<HouseholdId> debtors = new ArrayList<>(householdEconomies.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value)); // 稳定债务人序（不沿用 Map 插入序）
    // ★★ §一.9：还债轮的三个具名计数（INFO 一条用；只读累加，不改任何口径）。
    long debtorsWithOutstanding = 0L;
    long householdsRepaid = 0L;
    long householdsWithNoStockToRepay = 0L;
    // ★★ W5-fix（BLOCKED-2）：本轮被排除的**自持合同**条数（见下面 `debtor.equals(creditor)` 那一段）。
    long selfHeldExcluded = 0L;
    Set<HouseholdId> debtorsWithPayment = new LinkedHashSet<>();
    for (HouseholdId debtor : debtors) {
      HouseholdEconomy householdEconomy = householdEconomies.get(debtor);
      if (householdEconomy == null) {
        continue;
      }
      List<DebtContract> owed = new ArrayList<>();
      for (DebtContractId debtId : debtsByDebtor.getOrDefault(debtor, List.of())) {
        DebtContract debt = debts.get(debtId);
        if (debt == null || debt.principal() <= 0L) {
          continue;
        }
        if (DebtContractBook.isSelfHeld(debt)) {
          // ★★★ W5-fix（BLOCKED-2，2026-10-11）：**自持合同（debtor == creditor）不是可执行的债权** ——
          //   它是"发行额 = 政府负债"这一类**记账事实**（铸币生产方式是其唯一生产者：`MintSettlement.credit` 的
          //   债务人是发行政府的国库家户、债权人是铸币这一路的组织者，常态世界里两者同一户）。
          //   对它清偿 = 铸一条 debtor → creditor 的转移 ⇒ `Transfer` 判死（两端相等不是一条发生额，是坏数据），
          //   而经济上它本来就是"自己的钱还给自己"（什么也没发生）⇒ **还债轮排除**（控制方裁定，设计书 v3.15 §7）。
          //   ★ **不静默**：逐条 DEBUG 具名（谁 / 哪条合同 / 为什么不动它）。
          //   ★ 排除**不改任何状态**：合同原样留在 `debts` 里（本金不减、状态不改）—— 它是记账事实，不是坏数据。
          //   ★ 同族先例（同一口径已存在于别处）：`DebtPartyResolver.splitDebtAmounts` 的自债净额
          //     （`capitalizeArrears` 用它，永不落自持合同）、`ModeMigrationSettlement.moveDebt` 的
          //     `MIGRATION_DEBT_SELF_NETTED`（目标恰是债权人 ⇒ 不落合同）。
          selfHeldExcluded++;
          if (TRACE.isDebugEnabled()) {
            EventLog.channel(TRACE)
                .debug(
                    LogEvent.of(
                        "DEBT_REPAY_SELF_HELD_SKIPPED",
                        EconomyLogSource.ECONOMY_DEBT_STATE,
                        "day",
                        day,
                        "household",
                        debtor.value(),
                        "debtContract",
                        debt.id().value(),
                        "unit",
                        debt.unit().key(),
                        "outstandingPrincipal",
                        debt.principal(),
                        "why",
                        "self-held-not-executable"));
          }
          continue;
        }
        if (sameDayWageDebtIds.contains(debt.id())) {
          // ★★★ D3-I13 / §6.5（E12）：本日 W3 新建的工资债不得在同日还款轮被再付一次。
          EventLog.channel(TRACE)
              .info(
                  LogEvent.of(
                      "WAGE_DEBT_SAME_DAY_REPAY_BLOCKED",
                      EconomyLogSource.ECONOMY_DEBT,
                      "day",
                      day,
                      "worker",
                      DebtContractBook.isSelfHeld(debt) ? "-" : debt.creditor().value(),
                      "creditor",
                      debt.creditor().value(),
                      "debtContract",
                      debt.id().value(),
                      "amount",
                      debt.principal(),
                      "reason",
                      "wage-debt-same-day-repay-blocked"));
          continue;
        }
        owed.add(debt);
      }
      if (owed.isEmpty()) {
        continue;
      }
      debtorsWithOutstanding++;
      MarketTable market = marketsByHousehold.get(debtor);
      owed.sort(repaymentOrder(debtor, market, householdPrices));

      // ★★★ v3.6（2026-10-11，用户第六批原话：「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」）：
      //   **不扣任何保留量** —— 可动商品 = max(0, 余额 − 冻结)（含冻结不越），逐商品同式；
      //   ★ 作废的两条：D-030 §4 的"一日口粮保留"、v3.5 裁定的"35 天窗口"（见约束设计书 v3.6/v3.7 §4.1）。
      //   ★ 还不上 ⇒ 挂账、计息、不清零（0–8 第 7 条）；本方法不核销、不留位。
      Map<CommodityId, Long> spendableGoods =
          spendableGoods(debtor, householdGoods, householdFrozenGoods);
      Map<CurrencyId, Long> spendableMoney =
          spendableMoney(debtor, householdMoney, householdFrozenMoney);
      // ★★ S-5（用户 2026-10-10 第二批第 5 条）：介质序 = **该户预估价值最低的商品优先** ——
      //   {@link DebtValuation#estimatedUnitValueMilli} 升序（估算尺 = 家户价目表 → 市场区默认；
      //   说不出价的排最后，同值按单位 canonical 串升序保证可复现）。★ 旧口径"钱永远第一 → 商品按数量降序"
      //   （D-030 §4）**已被取代**，见 {@link #repaymentMediumOrder}。
      List<DebtUnit> mediumOrder =
          repaymentMediumOrder(debtor, spendableMoney, spendableGoods, market, householdPrices);
      Map<DebtUnit, Long> availableByUnit = availableByUnit(spendableMoney, spendableGoods);
      // ★★ §一.9：无货可还 ⇒ 挂账（有未偿本金、而可动用介质一件都没有）。只计数，不改口径。
      if (availableByUnit.isEmpty()) {
        householdsWithNoStockToRepay++;
      }
      if (RAW.isTraceEnabled()) {
        // ★★ W2（§一.9：逐笔明细 TRACE）—— S-5 介质序的现场证据：这一户这一轮按什么次序付、为什么是这个次序。
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "DEBT_REPAY_MEDIUM_ORDER",
                    EconomyLogSource.ECONOMY_DEBT_STATE,
                    "day",
                    day,
                    "debtor",
                    debtor.value(),
                    "mediumOrder",
                    mediumOrder.stream().map(DebtUnit::key).toList(),
                    "reserveCommodities",
                    0,
                    "why",
                    "ascending-estimated-unit-value (S-5) + v3.6-no-reserve-window"));
      }

      for (DebtContract debt : owed) {
        requireRepayableTerms(debt);
        DebtValuation.PaymentPlan plan =
            DebtValuation.choosePayment(
                debt.principal(),
                debt.unit(),
                debtor,
                mediumOrder,
                availableByUnit,
                market,
                householdPrices);
        long paid = plan.totalContractUnits();
        if (paid > 0L) {
          if (debtorsWithPayment.add(debtor)) {
            householdsRepaid++; // ★ §一.9：逐户只计一次（同一户可能有多条合同）
          }
          ActorRef payee =
              requireReceivableRepayee(
                  repayeeResolver, debt, householdOfActor, householdGoods, householdMoney);
          Transfer repayment =
              ledger.mint(
                  HouseholdActors.of(debtor),
                  payee,
                  householdEconomy.view().hex(),
                  plan.goodsLegs(),
                  plan.moneyLegs(),
                  TransferReason.LOAN_REPAYMENT);
          applyTransfer(
              householdGoods,
              householdMoney,
              householdFrozenGoods,
              householdFrozenMoney,
              householdOfActor,
              repayment,
              issuanceJournal);
          DebtContractBook.reduce(debts, debt.id(), paid);
          repaidPrincipalByDebt.merge(debt.id(), paid, Long::sum);
          for (DebtValuation.PaymentLeg leg : plan.legs()) {
            debitRepaymentMedium(availableByUnit, leg);
            if (leg instanceof DebtValuation.CommodityLeg commodityLeg) {
              if (commodityLeg.commodity().equals(GRAIN)) {
                repaid.merge(debtor, commodityLeg.quantityMilli(), Long::sum);
              }
            } else if (leg instanceof DebtValuation.MoneyLeg moneyLeg) {
              repaidMoney
                  .computeIfAbsent(debtor, ignored -> new LinkedHashMap<>())
                  .merge(moneyLeg.currency(), moneyLeg.amountMilli(), Long::sum);
            }
          }
        }
        if (RAW.isTraceEnabled()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "DEBT_REPAY_DECISION",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtor",
                      debtor.value(),
                      "creditor",
                      debt.creditor().value(),
                      "unit",
                      debt.unit().key(),
                      "outstandingBefore",
                      debt.principal(),
                      "paid",
                      paid,
                      "remaining",
                      debt.principal() - paid,
                      "goodsLegs",
                      plan.goodsLegs(),
                      "moneyLegs",
                      plan.moneyLegs()));
          if (!plan.unpricedAssets().isEmpty()) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "DEBT_REPAY_UNPRICED",
                        EconomyLogSource.ECONOMY_DEBT_STATE,
                        "day",
                        day,
                        "debtor",
                        debtor.value(),
                        "creditor",
                        debt.creditor().value(),
                        "unit",
                        debt.unit().key(),
                        "remaining",
                        debt.principal() - paid,
                        "assets",
                        plan.unpricedAssets()));
          }
        }
        // ★ 没有稳定价格 ⇒ 不折算、不静默付 0：持有但缺价的资产落具名 skip（有腿时也报剩余本金）。
        recordUnpricedRepaymentSkipIfNeeded(
            ledger, debtor, debt, debt.principal() - paid, plan.unpricedAssets());
      }
    }
    // ★★ §一.9（v3.6 落地）：还债轮 INFO 一条 —— "本轮还了多少 / 有几户无货可还（挂账）"。
    //   ★ 具名口径：`householdsWithNoStockToRepay` = 有未偿本金、而手头可动用介质**一件都没有**的家户数
    //     （挂账、计息、不清零 —— 用户 0–8 第 7 条）⇒ 这就是"没库存就不还"的现场读数。
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DEBT_REPAY_ROUND",
                EconomyLogSource.ECONOMY_DEBT,
                "day",
                day,
                "debtorsWithOutstanding",
                debtorsWithOutstanding,
                "householdsRepaid",
                householdsRepaid,
                "householdsWithNoStockToRepay",
                householdsWithNoStockToRepay,
                // ★★ W5-fix（BLOCKED-2）：本轮被排除的自持合同条数（`0` = 本世界没有这种合同）。
                "selfHeldExcluded",
                selfHeldExcluded,
                "repaidGrainMilli",
                traceTotalLongs(repaid),
                "debtContracts",
                debts.size(),
                "reserveCommodities",
                0,
                "why",
                "v3.6-no-reserve-window (有库存就还、没库存记债)"));
    if (TRACE.isDebugEnabled()) {
      // ★ 保留量口径 DEBUG 一条（为什么"一件都不扣"）：供现场判读"还债额为什么比上一版大"。
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "DEBT_REPAY_RESERVE_POLICY",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  day,
                  "reserveCommodities",
                  0,
                  "reserveMoney",
                  0,
                  "spendableGoodsRule",
                  "max(0, 余额 − 冻结)",
                  "spendableMoneyRule",
                  "max(0, 逐币种余额 − 冻结)",
                  "why",
                  "v3.6：D-030 §4 一日口粮保留 + v3.5 的 35 天窗口一并作废；卖侧前瞻未动"));
    }
  }

  /**
   * ★★★ <b>W2（2026-10-11）：S-5 的介质序 —— 该户预估价值最低的商品优先</b>。
   *
   * <pre>
   * 逐介质估值 = {@link DebtValuation#estimatedUnitValueMilli}(债务人, 单位, 市场默认价目表, 家户价目表)
   * 排序       = 估值升序 → 单位 canonical 串升序（同值/不可定价的稳定 tie-break，I7：序是内容的纯函数）
   * </pre>
   *
   * <p>★★ <b>它取代了什么</b>：改动前是 D-030 §4 的「全部货币（余额降序、币种 id 升序）→ 全部商品（余额降序、商品 id 升序）」 ——
   * <b>钱永远第一位</b>，商品内部只看数量、不看价值。用户 2026-10-10 第二批第 5 条已取代该口径：
   * 「偿还顺序不是先还钱或者先还粮还工具之类的，而是只取决于这个家户的哪个种类商品，预估价值最低」。
   *
   * <p>★ <b>不可定价（估值为 0）排最前</b>：0 价商品与"说不出价的资产"在"值不值钱"这一维上是同一档（都换不来钱）， 先付掉它们对债务人最有利。★ 与 {@code
   * choosePayment} 的分工不变：本方法只定<b>次序</b>， 逐笔能不能折、折多少仍由 {@code choosePayment}/{@code
   * commonValueMilli} 判（缺价 ⇒ 具名 skip，不静默付 0）。
   */
  private static List<DebtUnit> repaymentMediumOrder(
      HouseholdId debtor,
      Map<CurrencyId, Long> spendableMoney,
      Map<CommodityId, Long> spendableGoods,
      MarketTable market,
      DebtValuation.HouseholdPriceTable householdPrices) {
    List<DebtUnit> order = new ArrayList<>(spendableMoney.size() + spendableGoods.size());
    for (CurrencyId currency : spendableMoney.keySet()) {
      order.add(DebtUnit.money(currency));
    }
    for (CommodityId commodity : spendableGoods.keySet()) {
      order.add(DebtUnit.commodity(commodity));
    }
    order.sort(
        Comparator.comparingLong(
                (DebtUnit unit) ->
                    DebtValuation.estimatedUnitValueMilli(debtor, unit, market, householdPrices))
            .thenComparing(DebtUnit::key));
    return order;
  }

  /** 可动余额的逐 unit 视图（D-030 §3.5 的逐 medium 扣减表）。 */
  private static Map<DebtUnit, Long> availableByUnit(
      Map<CurrencyId, Long> spendableMoney, Map<CommodityId, Long> spendableGoods) {
    LinkedHashMap<DebtUnit, Long> available = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : spendableMoney.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        available.put(DebtUnit.money(entry.getKey()), entry.getValue());
      }
    }
    for (Map.Entry<CommodityId, Long> entry : spendableGoods.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        available.put(DebtUnit.commodity(entry.getKey()), entry.getValue());
      }
    }
    return available;
  }

  /** 从逐 medium 余额表里扣掉一条已落账的付款腿（必须扣得动；不制造负余额）。 */
  private static void debitRepaymentMedium(
      Map<DebtUnit, Long> availableByUnit, DebtValuation.PaymentLeg leg) {
    DebtUnit unit;
    long amount;
    if (leg instanceof DebtValuation.CommodityLeg commodityLeg) {
      unit = DebtUnit.commodity(commodityLeg.commodity());
      amount = commodityLeg.quantityMilli();
    } else if (leg instanceof DebtValuation.MoneyLeg moneyLeg) {
      unit = DebtUnit.money(moneyLeg.currency());
      amount = moneyLeg.amountMilli();
    } else {
      throw new IllegalStateException("未知偿还腿类型（拒绝静默跳过）: " + leg);
    }
    long remaining = availableByUnit.getOrDefault(unit, 0L) - amount;
    if (remaining < 0L) {
      throw new IllegalStateException(
          "偿还介质余额不足（唯一 applier 已落账，拒绝把余额扣成负）: medium="
              + unit.key()
              + " 余额="
              + availableByUnit.getOrDefault(unit, 0L)
              + " 付款="
              + amount);
    }
    availableByUnit.put(unit, remaining);
  }

  /**
   * ★★ <b>W2：还款轮的逐户资源快照</b>（{@link HouseholdValuationBook#derive} 的入参）—— 只读、不写状态。
   *
   * <pre>
   * market = 该户所在市场区的默认价目表（{@code marketsByHousehold}；缺 ⇒ 该户整户不给价，回落市场默认）
   * goods  = max(0, 现货 − 冻结)   money = max(0, 余额 − 冻结)
   * labor  = 该户当刻的劳动预算（估值不用它，照快照契约带上）
   * </pre>
   *
   * <p>★ 与 {@code LaborQueueSettlement.buildArbitrageSnapshots} 同一条"净额"口径（余额 − 冻结，夹到 ≥ 0）；
   * 那份是套利时刻、这份是还款时刻 —— 两者都不进状态，故不共享实例（时点不同）。
   */
  private static Map<HouseholdId, HouseholdResourceSnapshot> repaymentSnapshots(
      long day,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<HouseholdId, MarketTable> marketsByHousehold) {
    List<HouseholdId> ordered = new ArrayList<>(householdEconomies.keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, HouseholdResourceSnapshot> snapshots = new LinkedHashMap<>();
    for (HouseholdId household : ordered) {
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null) {
        continue;
      }
      snapshots.put(
          household,
          new HouseholdResourceSnapshot(
              day,
              household,
              marketsByHousehold.get(household),
              netOf(householdGoods.get(household), householdFrozenGoods.get(household)),
              netOf(householdMoney.get(household), householdFrozenMoney.get(household)),
              Math.max(0L, row.laborMilli())));
    }
    return snapshots;
  }

  /** {@code 余额 − 冻结}（逐键；缺冻结 = 0；结果夹到 ≥ 0；保序；空 ⇒ 空表）。 */
  private static <K> Map<K, Long> netOf(Map<K, Long> stock, Map<K, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<K, Long> net = new LinkedHashMap<>();
    for (Map.Entry<K, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /**
   * ★★★ <b>v3.6（2026-10-11）：可动商品 = max(0, 余额 − 冻结)</b> —— <b>不扣任何保留量</b>。
   *
   * <p>★★ <b>用户原话（第六批）</b>：「<b>为啥要设还债窗口，应当是有库存就还，没库存就不还啊</b>」⇒ 还债可用量 = 家户手头库存本身 （含货币；介质序仍按 S-5 =
   * 预估价值最低优先）。
   *
   * <p>★★ <b>它作废了什么（两条，均不再保留）</b>：① 更早的 "自然口粮日需求 × {@code
   * DEBTOR_SUBSISTENCE_RESERVE_DAYS}"（那条独立的一日口粮账）；② W2 上一版的"下轮生产方式的生产花费（内含口粮，S-10）"保留 —— 后者连同
   * {@code productionCostsByHousehold} 一并删除（AGENTS §一.11：旧设计不留）。 ★ 唯一保留的"前瞻扣除"在<b>卖侧</b>（{@code
   * MarketSettlement} 的 {@code retention}，用户 0–8 第 5 条），本方法不碰它。
   *
   * <p>★ 冻结不越：任何腿都不越过 {@code householdFrozenGoods}。还款份额（{@link #DEBT_REPAYMENT_SHARE_PER_MILLE}，出厂
   * 1000‰ = 全还）对**每一种可动商品**同式（出厂值 ⇒ 逐值等于"不乘"）。
   */
  private static Map<CommodityId, Long> spendableGoods(
      HouseholdId debtor,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods) {
    Map<CommodityId, Long> stock = householdGoods.getOrDefault(debtor, Map.of());
    if (stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> spendable = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stock.entrySet()) {
      long amount = entry.getValue() == null ? 0L : entry.getValue();
      if (amount <= 0L) {
        continue;
      }
      long free =
          Math.max(0L, amount - frozenGoodsOf(householdFrozenGoods, debtor, entry.getKey()));
      // ★★ v3.6：**这里不再扣任何保留量**（旧的两条：一日口粮 / 下轮生产花费 —— 均已作废）。
      // ★ 还款份额（{@link #DEBT_REPAYMENT_SHARE_PER_MILLE}，出厂 1000‰ = 全还）。
      free = free * DEBT_REPAYMENT_SHARE_PER_MILLE / 1000L;
      if (free > 0L) {
        spendable.put(entry.getKey(), free);
      }
    }
    return spendable;
  }

  /** 可动货币 = max(0, 逐币种余额 − 冻结)；不跨币种求和/折换（D-023：不做 FX）。 */
  private static Map<CurrencyId, Long> spendableMoney(
      HouseholdId debtor,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney) {
    Map<CurrencyId, Long> wallet = householdMoney.getOrDefault(debtor, Map.of());
    if (wallet.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CurrencyId, Long> spendable = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : wallet.entrySet()) {
      long amount = entry.getValue() == null ? 0L : entry.getValue();
      if (amount <= 0L) {
        continue;
      }
      long free =
          Math.max(0L, amount - frozenMoneyOf(householdFrozenMoney, debtor, entry.getKey()));
      if (free > 0L) {
        spendable.put(entry.getKey(), free);
      }
    }
    return spendable;
  }

  /**
   * ★★ <b>收款人账户闸门</b>（D-023 第 1 条）：债权人 → actor 由 {@link DebtValuation.RepayeeResolver}
   * 解析；收款账户必须在本日会话账里真的可收（家户账 or 经营者/actor 账）。否则具名 fail-closed，不让 {@code applyTransfer}
   * 的“两端缺一就跳过”把付款静默吞掉。
   */
  private static ActorRef requireReceivableRepayee(
      DebtValuation.RepayeeResolver repayeeResolver,
      DebtContract debt,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    ActorRef payee;
    try {
      payee = repayeeResolver.resolve(debt.creditor());
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "偿还收款方解析失败（拒绝静默吞款）: 合同=" + debt.id() + " 债权人=" + debt.creditor(), failure);
    }
    if (payee == null) {
      throw new IllegalStateException(
          "偿还收款方为 null（拒绝静默吞款）: 合同=" + debt.id() + " 债权人=" + debt.creditor());
    }
    // ★★ P2-A §13.3：收款账户只有家户账 —— 解析不到家户 ⇒ 不可收（具名抛，不静默吞款）。
    HouseholdId householdRoute = householdOfActor.get(payee);
    boolean receivable =
        householdRoute != null
            && (householdGoods.containsKey(householdRoute)
                || householdMoney.containsKey(householdRoute));
    if (!receivable) {
      throw new IllegalStateException(
          "偿还收款方在本日账户会话里不可收（拒绝静默吞款）: 合同="
              + debt.id()
              + " creditor="
              + debt.creditor()
              + " payee="
              + payee
              + " householdRoute="
              + householdRoute);
    }
    return payee;
  }

  /** ★★ P11.1：持有资产缺稳定价格时的具名 skip（原因含资产清单；绝不把缺价静默成 0 付款）。 */
  private static void recordUnpricedRepaymentSkipIfNeeded(
      ProductionLedger.Accumulator ledger,
      HouseholdId debtor,
      DebtContract debt,
      long remaining,
      List<String> unpricedAssets) {
    if (remaining <= 0L || unpricedAssets.isEmpty()) {
      return;
    }
    ledger.addDebtRepaymentSkip(
        new ProductionLedger.DebtRepaymentSkip(
            debtor,
            debt.id(),
            debt.unit(),
            remaining,
            DebtValuation.UNPRICED_ASSET_REASON_PREFIX + String.join(",", unpricedAssets)));
  }

  /**
   * ★★ <b>D-030 §3.5 的债排序</b>：未偿本金按共同价值升序；不可定价的排最后（unit id 升序）；tie = 债权人 id → 债的单位 id → 合同 id。
   *
   * <p>★ 共同价值用债务人自己的价目表（{@link DebtValuation#commonValueMilli}：家户表优先、市场区默认回退）；无法定价 ⇒ {@link
   * OptionalLong#empty()} ⇒ 排最后。本合同自己的单位不需要 price 吗？需要：排序要拿它和别的债比，故 仍走同一份价目表；真正“不需要 price”的是付款时的
   * identity 腿（见 {@link DebtValuation#choosePayment}）。
   */
  private static Comparator<DebtContract> repaymentOrder(
      HouseholdId debtor, MarketTable market, DebtValuation.HouseholdPriceTable householdPrices) {
    return (left, right) -> {
      OptionalLong leftValue =
          DebtValuation.commonValueMilli(
              left.principal(), left.unit(), debtor, market, householdPrices);
      OptionalLong rightValue =
          DebtValuation.commonValueMilli(
              right.principal(), right.unit(), debtor, market, householdPrices);
      if (leftValue.isPresent() != rightValue.isPresent()) {
        return leftValue.isPresent() ? -1 : 1;
      }
      if (leftValue.isPresent()) {
        int byValue = Long.compare(leftValue.getAsLong(), rightValue.getAsLong());
        if (byValue != 0) {
          return byValue;
        }
      } else {
        int byUnit = left.unit().key().compareTo(right.unit().key());
        if (byUnit != 0) {
          return byUnit;
        }
      }
      int byCreditor = left.creditor().value().compareTo(right.creditor().value());
      if (byCreditor != 0) {
        return byCreditor;
      }
      int byUnit = left.unit().key().compareTo(right.unit().key());
      if (byUnit != 0) {
        return byUnit;
      }
      return left.id().value().compareTo(right.id().value());
    };
  }

  /**
   * ★★ <b>E4c：只对已接线的 legacy 条款走现有语义</b>——{@link RepaymentRule#AVAILABLE_SURPLUS_SHARE} + {@link
   * InterestTiming#AFTER_REPAYMENT_ON_CLOSE}。任何其它组合当场具名抛（fail-closed），不静默当默认档。
   */
  private static void requireRepayableTerms(DebtContract debt) {
    if (debt.terms().repaymentRule() != RepaymentRule.AVAILABLE_SURPLUS_SHARE) {
      throw new IllegalStateException(
          "E4c 尚未接线非 legacy 偿还规则：合同="
              + debt.id()
              + " rule="
              + debt.terms().repaymentRule()
              + "（fail-closed，不静默当成默认档）");
    }
    if (debt.terms().interestTiming() != InterestTiming.AFTER_REPAYMENT_ON_CLOSE) {
      throw new IllegalStateException(
          "E4c 尚未接线非 legacy 计息时点：合同="
              + debt.id()
              + " timing="
              + debt.terms().interestTiming()
              + "（fail-closed，不静默当成默认档）");
    }
  }

  /**
   * ★★ <b>劳动再分配（P2-A §13.4 的小时口径版本；★ P2-B 起只服务 {@code modes} 为空的旧档世界）</b>。
   *
   * <p>★★ <b>P2-B §13.5 的接替者</b>：有 {@code modes} 的世界不再走本方法，改走 {@link LaborQueueSettlement}（每
   * tick、按家户利润率排队 + 逐 unit 最大可吸收量全局封顶）。本方法保留为 <b>旧路径</b>：空 modes 世界的逐值行为一字不变（同一批配额、同一修剪、同一封顶）。
   *
   * <p>★★ <b>本批的语义（判据就是它）</b>：
   *
   * <pre>
   * ① 逐 unit 算"这一周期真的用得上的劳动"（本 tick 最大可吸收量）：
   *      usableScale_i = min( 产能那一路, ⌊本周期已扣到的投入_j ÷ inputPerUnit_j⌋ … )
   *      need_i        = usableScale_i × 配方 laborPerUnit_i       // 与配额同量纲（本批仍为毫小时刻度）
   * ② 逐 (批次, unit) 配额：保留 = min(配额, 该 unit**剩余**可吸收量)；超出的缩掉/整条删掉
   * ③ 按**家户每 tick 时间预算**（{@code HouseholdEconomy.laborMilli}）封顶：Σallocations(household) ≤ budget，
   *      超出时按现有配额权重的最大余数法缩 —— Σ 从不凭空增加
   * </pre>
   *
   * <p>★★ <b>已删除的旧语义</b>：改前第 ③/④ 步是"回池的劳动按**缺口大者先得**、兜底给产粮 unit（最后雇主）"。 那正是计划 §13.5
   * 要求替换掉的"按缺口优先"口径；本批**不**用它冒充利润率排队 —— "预期单位劳动净收益降序 × 各生产方式最大可吸收劳动"的排序接线属 P2-B。⇒ 释放出来的时间**留在空缺**
   * （不自动塞给别的 unit），这与"没有可吸收的生产方式 ⇒ 劳动空缺"同侧。
   *
   * <p>★ <b>只在本格的产业处于"周期第一天"时重排</b>（{@code progressDays == 0}，与现扣投入同一天）： 那时"这一周期开得起来多大"已由现扣步写进
   * {@code cycleInputUsedMilli} ⇒ 判定有据。
   *
   * <p>★ <b>保序与幂等</b>：全部遍历走保序表 + id 序 ⇒ 同一份状态两次调用逐值相同。
   */
  private static void reallocateLabor(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index) {
    Map<String, List<ProductionUnitId>> hexToUnits = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> unitEntry : units.entrySet()) {
      IndustryHexKeys.hexKeyOf(unitEntry.getValue().industry())
          .ifPresent(
              hex ->
                  hexToUnits
                      .computeIfAbsent(hex, ignored -> new ArrayList<>())
                      .add(unitEntry.getKey()));
    }
    for (Map.Entry<String, List<ProductionUnitId>> hex : hexToUnits.entrySet()) {
      List<ProductionUnitId> ids = hex.getValue();
      boolean cycleStart = false;
      for (ProductionUnitId id : ids) {
        if (units.get(id).progressDays() == 0L) {
          cycleStart = true;
          break;
        }
      }
      if (!cycleStart) {
        continue; // 周期中途：这一周期的劳动口径已经开跑，不重排（见方法注释）
      }
      // ① 每 unit "这一周期真的用得上的劳动"（缺口的来源）
      //   ★ R4-B.3a-perf：先把本 hex 的 unit id 放进 Set，再逐配额做 O(1) 判定（旧实现对每条配额做
      //     {@code ids.contains} 线性查找；插入序/求和/取整口径不变）。
      Set<ProductionUnitId> idSet = new LinkedHashSet<>(ids);
      Map<ProductionUnitId, Long> allocated = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (idSet.contains(unitId)) {
          allocated.merge(unitId, laborCommitment.laborMilli(), Long::sum);
        }
      }
      Map<ProductionUnitId, Long> need = new LinkedHashMap<>();
      for (ProductionUnitId id : ids) {
        ProductionProcess unit = units.get(id);
        IndustryDefinitionView industry = industries.get(unit.industry());
        if (industry == null) {
          throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
        }
        need.put(
            id,
            laborNeedOf(
                unit, industry, index, allocated.getOrDefault(id, 0L), operatorConditions.get(id)));
      }
      // ② 按 unit 的**最大可吸收劳动**修剪（这是 §13.5 的"本 tick 最大可吸收量"，不是按缺口抢）
      //   ★★ Z3a/C7：GOV_SERVICE 先整额占住该 unit 的 room（最高优先级）且不参与修剪/删除；
      //      PRODUCTION 只吃剩余 room（"先扣承诺再排生产"）。
      Map<ProductionUnitId, Long> kept = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (!need.containsKey(unitId)
            || laborCommitment.kind() != LaborCommitmentKind.GOV_SERVICE) {
          continue;
        }
        kept.merge(unitId, laborCommitment.laborMilli(), Long::sum);
      }
      for (LaborAllocationId allocId : new ArrayList<>(laborCommitments.keySet())) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocId);
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (!need.containsKey(unitId)) {
          continue; // 不是本格的配额（另一格的 unit）
        }
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          continue; // ★ Z3a/C7：政府行政岗位承诺不参与按最大可吸收量修剪
        }
        long room = Math.max(0L, need.get(unitId) - kept.getOrDefault(unitId, 0L));
        long keep = Math.min(laborCommitment.laborMilli(), room);
        kept.merge(unitId, keep, Long::sum);
        if (keep <= 0L) {
          laborCommitments.remove(allocId); // 这个 unit 这一周期一点也用不上
        } else if (keep < laborCommitment.laborMilli()) {
          laborCommitments.put(allocId, withLaborMilli(laborCommitment, keep));
        }
      }
      // ③ ★★ P2-A §13.4：按**家户每 tick 时间预算**封顶（毫小时）—— Σallocations(household) ≤ row.laborMilli。
      //   ★ 本步**不**按缺口把释放出来的时间再分给别的 unit：预期单位劳动净收益降序 + 各生产方式最大可吸收
      //     劳动的排队属 P2-B（计划 §13.5）；本批只保证小时口径与预算不变量，旧的"缺口大者先得 + 最后兜底产粮
      //     unit"语义已删除（不得以任何形式冒充利润率排队）。
      Map<HouseholdId, List<LaborAllocationId>> byHousehold = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
        if (!idSet.contains(new ProductionUnitId(laborCommitment.activity()))) {
          continue; // 另一格的配额
        }
        byHousehold
            .computeIfAbsent(laborCommitment.household(), ignored -> new ArrayList<>())
            .add(laborCommitment.id());
      }
      for (Map.Entry<HouseholdId, List<LaborAllocationId>> household : byHousehold.entrySet()) {
        HouseholdEconomy householdEconomy = householdEconomies.get(household.getKey());
        if (householdEconomy == null) {
          continue;
        }
        List<LaborAllocationId> householdAllocationIds = new ArrayList<>(household.getValue());
        householdAllocationIds.sort(Comparator.comparing(LaborAllocationId::value));
        // ★★ Z3a/C7：GOV_SERVICE 整额保留、不进比例权重；只对 PRODUCTION 按剩余预算缩。
        long govServiceSum = 0L;
        long productionSum = 0L;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          HouseholdLaborCommitment laborCommitment =
              laborCommitments.get(householdAllocationIds.get(i));
          long amount = laborCommitment == null ? 0L : Math.max(0L, laborCommitment.laborMilli());
          if (laborCommitment != null
              && laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            govServiceSum = Math.addExact(govServiceSum, amount);
          } else {
            productionSum = Math.addExact(productionSum, amount);
          }
        }
        long sum = Math.addExact(govServiceSum, productionSum);
        long budget = Math.max(0L, householdEconomy.laborMilli());
        if (sum <= budget || sum <= 0L) {
          continue;
        }
        // ★★ Z7d-1：同 applyLaborBudgetsInto —— GOV_SERVICE 允许 over-budget（职位保留），PRODUCTION 可用量归 0。
        long availableForProduction = Math.max(0L, budget - govServiceSum);
        if (productionSum <= availableForProduction || productionSum <= 0L) {
          continue; // GOV_SERVICE 已整额装下（或 over-budget 但没有可缩 PRODUCTION）：防御性 no-op
        }
        int productionCount = 0;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          HouseholdLaborCommitment laborCommitment =
              laborCommitments.get(householdAllocationIds.get(i));
          if (laborCommitment != null
              && laborCommitment.kind() != LaborCommitmentKind.GOV_SERVICE) {
            productionCount++;
          }
        }
        long[] productionWeights = new long[productionCount];
        List<LaborAllocationId> productionIds = new ArrayList<>(productionCount);
        int at = 0;
        for (int i = 0; i < householdAllocationIds.size(); i++) {
          LaborAllocationId allocationId = householdAllocationIds.get(i);
          HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
          if (laborCommitment == null
              || laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            continue;
          }
          productionIds.add(allocationId);
          productionWeights[at] = Math.max(0L, laborCommitment.laborMilli());
          at++;
        }
        long[] parts =
            ProportionalSplit.byDenominator(
                availableForProduction, productionWeights, productionSum);
        for (int i = 0; i < productionIds.size(); i++) {
          LaborAllocationId allocationId = productionIds.get(i);
          HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
          if (laborCommitment == null) {
            continue;
          }
          if (parts[i] <= 0L) {
            laborCommitments.remove(allocationId);
          } else {
            laborCommitments.put(allocationId, withLaborMilli(laborCommitment, parts[i]));
          }
        }
      }
    }
    // ★★ Z3a/C7：本分区的修剪/预算缩放已跑完 —— 政府承诺整额保留后若越预算，统一具名 ERROR + fail-closed。
    //   本方法签名无 day ⇒ system 来源、事件不带 day。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION_WRITE, -1L);
  }

  /**
   * ★ <b>一个 unit "这一周期真的用得上的劳动"</b>（千分劳动·日）= {@code min(产能那一路, 投入那几路) × laborPerUnit}。
   *
   * <pre>
   * usableScale = min( ⌊assetAllocation[k] ÷ capacityPerUnit[k]⌋ …, ⌊cycleInputUsedMilli[j] ÷ inputPerUnit[j]⌋ … )
   * need        = usableScale × 配方.laborPerUnit
   * </pre>
   *
   * <p>★ 与收获日公式（{@link ProductionEfficiencyBook}）是**同一个算式的两个前缀**：这里**刻意不含劳动那一路**（劳动正是本步要求解的量）。
   */
  private static long laborNeedOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      long allocated,
      OperatorCondition condition) {
    if (industry.recipe().laborPerUnit() <= 0L) {
      return allocated; // ★ 劳动那一路**不施加约束**（与收获日公式的同款口径）
    }
    // ★★ P2-B：产能×投入那一路的算式**只有一处拼写点** —— {@link LaborQueueBook#maxAbsorbableLaborMilli}
    //   （本方法保留"laborPerUnit ≤ 0 ⇒ 返回已分配量"的旧包装语义，旧档逐值不变）。
    return LaborQueueBook.maxAbsorbableLaborMilli(unit, industry, index, condition);
  }

  /** 换劳动量（其余字段原样带过）—— 配额缩小与累加共用。 */
  private static HouseholdLaborCommitment withLaborMilli(
      HouseholdLaborCommitment laborCommitment, long laborMilli) {
    return new HouseholdLaborCommitment(
        laborCommitment.id(),
        laborCommitment.group(),
        laborCommitment.household(),
        laborCommitment.actor(),
        laborCommitment.activity(),
        laborMilli,
        laborCommitment.period(),
        laborCommitment.kind(),
        laborCommitment.productionRole());
  }

  // ── 周期收获与制度分配 ─────────────────────────────────────────────────────────────

  // ── P2-A §13.3：收获阶段的账户路由（unit 主体 → 家户；转账两端的家户化）────────────────────

  /** unit → 生产组织（按 unitId 唯一；同 unit 多条组织取 id 升序第一条 —— 与组织阶段的"一个 unit 一条"同侧）。 */
  private static Map<ProductionUnitId, ProductionEnterprise> enterprisesByProcess(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    Map<ProductionUnitId, ProductionEnterprise> byUnit = new LinkedHashMap<>();
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : ids) {
      ProductionEnterprise enterprise = enterprises.get(id);
      if (enterprise != null) {
        enterprise.unitId().ifPresent(unitId -> byUnit.putIfAbsent(unitId, enterprise));
      }
    }
    return byUnit;
  }

  /** 旧 {@link CohortKey} 视图 → 家户（**恰一户**才登记；多于一户 = 歧义，不登记 —— {@link #requireCohortRows} 会先抛）。 */
  private static Map<CohortKey, HouseholdId> viewToHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<CohortKey, HouseholdId> index = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : keys) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        continue;
      }
      CohortKey view = householdEconomy.view();
      if (ambiguous.contains(view)) {
        continue;
      }
      HouseholdId previous = index.putIfAbsent(view, household);
      if (previous != null && !previous.equals(household)) {
        index.remove(view);
        ambiguous.add(view);
      }
    }
    return index;
  }

  /** 主体的可花货币合计（逐币种；单一主体 = 它自己的钱包，集体 = 各户之和）。 */
  private static Map<CurrencyId, Long> availableMoneyOf(
      HouseholdRouting.Subject subject, Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    Map<CurrencyId, Long> sums = new LinkedHashMap<>();
    for (HouseholdId household : subject.all()) {
      for (Map.Entry<CurrencyId, Long> entry :
          householdMoney.getOrDefault(household, Map.of()).entrySet()) {
        // ★ D5 并发读到的 null 值（活表被并发清空/写入的窗口）按"该币种此刻不可用"处理，fail-closed 不 NPE；
        //   正常路径的账户写入只写非 null（见 AccountSession.ActorAccount.replaceMoney）。
        if (entry.getValue() == null) {
          continue;
        }
        sums.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return sums;
  }

  /**
   * ★★ <b>W2-fix7 / D1②：主体的实物库存合计（逐商品；单一主体 = 它自己的库存，集体 = 各户之和）</b>。
   *
   * <p>★ 与 {@link #availableMoneyOf} 逐字同形、同一条口径：它喂 {@link
   * ProductionSettlement.Facts#payerStock()}（实物租腿的 三级支付链第二级），因此<b>必须是"结算前这一刻"的会话库存</b> ——
   * 调用点（{@code harvest}）在 {@code creditOutput} 之后、{@code settle} 之前取它 ⇒ 表里已含本周期刚计提的净产。
   */
  private static Map<CommodityId, Long> availableGoodsOf(
      HouseholdRouting.Subject subject, Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    Map<CommodityId, Long> sums = new LinkedHashMap<>();
    for (HouseholdId household : subject.all()) {
      for (Map.Entry<CommodityId, Long> entry :
          householdGoods.getOrDefault(household, Map.of()).entrySet()) {
        // ★ 与 availableMoneyOf 同款：并发读到的 null 值按"此刻不可用"处理（fail-closed 不 NPE）。
        if (entry.getValue() == null) {
          continue;
        }
        sums.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return sums;
  }

  /** 净产入账：单一主体 ⇒ 一条计提；集体主体 ⇒ 按劳动权重分给各家家户；解析不到主体 ⇒ <b>具名缺口</b> （WARN + 跳过，不凭空造账、不静默塞给某个家户）。 */
  private static void creditOutput(
      HouseholdRouting.Subject subject,
      Map<HouseholdId, Long> weights,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      ActorRef operator,
      CommodityId commodity,
      long net,
      HexCoord location,
      ProductionLedger.Accumulator ledger) {
    if (subject.isEmpty()) {
      EventLog.channel(TRACE)
          .warn(
              LogEvent.of(
                  "ACCOUNT_SUBJECT_UNRESOLVED",
                  EconomyLogSource.ECONOMY_OUTPUT_CREDIT,
                  "kind",
                  "output",
                  "operator",
                  operator,
                  "commodity",
                  commodity.value(),
                  "amount",
                  net,
                  "hex",
                  location.q() + "," + location.r(),
                  "reason",
                  "no-household-organizer-or-labor"));
      return;
    }
    for (Map.Entry<HouseholdId, Long> share :
        HouseholdRouting.apportion(subject, net, weights).entrySet()) {
      HouseholdId household = share.getKey();
      addStock(householdGoods, household, commodity, share.getValue());
      ledger.addOutputAccrual(
          new ProductionLedger.ActorEntry(
              HouseholdActors.of(household), location, commodity, share.getValue()));
    }
  }

  /**
   * ★★★ <b>D-032 C2（D2 §8.3 / §6.3）：名册方案的两层分配落账</b>。
   *
   * <p>★★ <b>唯一算术来源</b>：{@code production.contract.RoleDistribution.allocate}（角色池 + 角色内最大余数法 +
   * 份额/整数余数规则）；economy 在这里只做两件事：把当刻事实（计划劳动/权益权重/净池）装进去，把结果逐户落账。 <b>不</b>复制任何角色池/最大余数公式。
   *
   * <p>★ <b>不双记</b>：净产直接按份额计入 participant 家户库存（不先 credit 给 operator 再转出）；未分配量 （{@code
   * unallocated}，含零权重角色池与余数）全部归 organizer residual，并具名 {@code ROLE_SHARE_UNALLOCATED_RESIDUAL} 记入
   * organizer 的流水。
   *
   * <p>★ <b>守恒</b>：{@code RoleDistributionResult} 构造期已复核逐商品 {@code Σshares + unallocated ==
   * poolTotal}； 本方法再按 gross/net 口径与 ledger 对齐（loss 已在 harvest 记入 ΣLoss）。
   */
  private static void creditRosterNetPool(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      io.mosire.simos.economy.model.ProductionRoster roster,
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<CommodityId, Long> netByCommodity,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(roster, "roster");
    HexCoord location = hexOfIndustry(industry.id());
    List<io.mosire.simos.production.contract.RoleDistributionParticipant> participants =
        new ArrayList<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> rightsWeights =
        new LinkedHashMap<>();
    for (io.mosire.simos.economy.model.ProductionParticipant participant : roster.participants()) {
      participants.add(
          new io.mosire.simos.production.contract.RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      long rights = 0L;
      for (AssetShareId share : participant.rightsStakes()) {
        OwnershipStake stake = assetShares.get(share);
        if (stake == null) {
          throw new IllegalStateException(
              "BY_RIGHTS 引用的资产份额不存在（状态已被改坏）: unit="
                  + unit.id().value()
                  + " share="
                  + share.value());
        }
        rights = Math.addExact(rights, stake.quantity());
      }
      if (rights > 0L) {
        rightsWeights.put(
            new io.mosire.simos.production.contract.ProductionParticipantKey(
                participant.household(), participant.role()),
            rights);
      }
    }
    io.mosire.simos.production.contract.RoleDistributionResult distribution =
        io.mosire.simos.production.contract.RoleDistribution.allocate(
            new io.mosire.simos.production.contract.RoleDistributionRequest(
                industry, participants, cycleLaborByParticipant, rightsWeights, netByCommodity));
    for (Map.Entry<
            io.mosire.simos.production.contract.ProductionParticipantKey, Map<CommodityId, Long>>
        entry : distribution.shares().entrySet()) {
      HouseholdId household = entry.getKey().household();
      for (Map.Entry<CommodityId, Long> share : entry.getValue().entrySet()) {
        addStock(householdGoods, household, share.getKey(), share.getValue());
        addGoods(income, household, share.getKey(), share.getValue());
        ledger.addOutputAccrual(
            new ProductionLedger.ActorEntry(
                HouseholdActors.of(household), location, share.getKey(), share.getValue()));
      }
    }
    HouseholdId residualOwner = roster.organizerHousehold();
    for (Map.Entry<CommodityId, Long> residual : distribution.unallocated().entrySet()) {
      addStock(householdGoods, residualOwner, residual.getKey(), residual.getValue());
      addGoods(income, residualOwner, residual.getKey(), residual.getValue());
      ledger.addOutputAccrual(
          new ProductionLedger.ActorEntry(
              HouseholdActors.of(residualOwner), location, residual.getKey(), residual.getValue()));
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "ROLE_SHARE_UNALLOCATED_RESIDUAL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "unit",
                  unit.id().value(),
                  "commodity",
                  residual.getKey().value(),
                  "amount",
                  residual.getValue(),
                  "organizer",
                  residualOwner.value()));
    }
  }

  /** 一个已解析的转账端：主体 + 分配权重（权重只对 collective 有意义）。 */
  private record ResolvedAccountSubject(
      HouseholdRouting.Subject subject, Map<HouseholdId, Long> weights) {}

  /** 结转一个 actor（旧 cohort 视图 / 组织者 actor / 现存家户 actor）→ 家户主体；解析不到 ⇒ 具名抛。 */
  private static ResolvedAccountSubject resolveAccountCounterparty(
      ActorRef actor,
      ProductionProcess unit,
      HouseholdRouting.Subject unitSubject,
      Map<HouseholdId, Long> unitWeights,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      SettlementIndex index) {
    Optional<HouseholdId> direct =
        HouseholdRouting.householdOfActorOrNull(actor, householdEconomies);
    if (direct.isPresent()) {
      HouseholdId household = direct.get();
      return new ResolvedAccountSubject(
          HouseholdRouting.Subject.single(household), Map.of(household, 1L));
    }
    if (actor.equals(unit.operator())) {
      return new ResolvedAccountSubject(unitSubject, unitWeights);
    }
    // 组织者 actor（组织记录里写的是它的 actor）：组织者本人必须是家户 actor。
    for (ProductionEnterprise enterprise : enterpriseByProcess.values()) {
      if (enterprise.organizer().equals(actor)) {
        Optional<HouseholdId> organizer =
            HouseholdRouting.householdOfActorOrNull(enterprise.organizer(), householdEconomies);
        if (organizer.isPresent()) {
          return new ResolvedAccountSubject(
              HouseholdRouting.Subject.single(organizer.get()), Map.of(organizer.get(), 1L));
        }
      }
    }
    // 旧 ToCohort 规则：{@code HouseholdActors.of(CohortKey)} 的三段 actor id → 视图 → 恰一个家户。
    if (actor.kind() == io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
      try {
        CohortKey view = HouseholdActors.cohortOf(actor);
        HouseholdId household = viewIndex.get(view);
        if (household != null) {
          return new ResolvedAccountSubject(
              HouseholdRouting.Subject.single(household), Map.of(household, 1L));
        }
      } catch (RuntimeException notLegacyCohortActor) {
        // 落到后面的 unit 关联解析（新档家户 actor 的 id 不是三段旧格式）。
      }
    }
    // 其他 unit 的经营者 actor：用该 unit 的经济家户 / 劳动家户解析。
    for (ProductionUnitId related : index.unitsRelatedTo(actor)) {
      Optional<HouseholdId> single = index.economicHouseholdOf(related);
      if (single.isPresent()) {
        return new ResolvedAccountSubject(
            HouseholdRouting.Subject.single(single.get()), Map.of(single.get(), 1L));
      }
      List<HouseholdId> collective = index.householdsOf(related);
      if (!collective.isEmpty()) {
        return new ResolvedAccountSubject(
            HouseholdRouting.Subject.collective(collective),
            HouseholdRouting.weightsOf(related, index, householdEconomies));
      }
    }
    throw new IllegalStateException(
        "关系结算的一端无法解析到家户账户（账户主体只有家户；庄园/作坊/商号必须能解析到组织者/经营者家户）："
            + "unit="
            + unit.id().value()
            + " actor="
            + actor
            + "（单位主体="
            + unitSubject
            + "）");
  }

  /** 支付能力读口（按家户取可用量；payerShares 的瀑布式封顶用）。 */
  private interface Availability {
    long of(HouseholdId household);
  }

  /** 付方份额：按权重比例的目标额先取 min(目标, 可用)，不足由**同主体其余成员**按序补齐； 仍不足 ⇒ 具名抛（调用方应已用主体合计封顶，走到这里说明状态已坏）。 */
  private static Map<HouseholdId, Long> payerShares(
      HouseholdRouting.Subject subject,
      long total,
      Map<HouseholdId, Long> weights,
      Availability available) {
    Map<HouseholdId, Long> taken = new LinkedHashMap<>();
    if (total <= 0L) {
      return taken;
    }
    Map<HouseholdId, Long> targets = HouseholdRouting.apportion(subject, total, weights);
    long shortfall = 0L;
    for (Map.Entry<HouseholdId, Long> target : targets.entrySet()) {
      long room = Math.max(0L, available.of(target.getKey()));
      long take = Math.min(target.getValue(), room);
      if (take > 0L) {
        taken.put(target.getKey(), take);
      }
      shortfall = Math.addExact(shortfall, target.getValue() - take);
    }
    if (shortfall > 0L) {
      for (HouseholdId household : subject.all()) {
        if (shortfall <= 0L) {
          break;
        }
        long already = taken.getOrDefault(household, 0L);
        long room = Math.max(0L, available.of(household) - already);
        long extra = Math.min(shortfall, room);
        if (extra > 0L) {
          taken.merge(household, extra, Math::addExact);
          shortfall -= extra;
        }
      }
    }
    if (shortfall > 0L) {
      throw new IllegalStateException(
          "关系结算的付方主体可付量不足（主体合计应已封顶；走到这里说明状态已坏）：subject="
              + subject
              + " total="
              + total
              + " shortfall="
              + shortfall);
    }
    return taken;
  }

  /**
   * ★★ <b>把 {@link ProductionSettlement} 铸的一条转移路由到家户</b>：两端各自解析成主体后按权重拆腿， 每条腿经 {@code ledger.mint}
   * 落账并收进 {@code sink}；返回第一条腿（满足 {@code TransferMint} 的返回契约）。
   */
  private static Transfer routeRelationTransfer(
      ProductionProcess unit,
      HouseholdRouting.Subject unitSubject,
      Map<HouseholdId, Long> unitWeights,
      ActorRef from,
      ActorRef to,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      TransferReason reason,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      SettlementIndex index,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      List<Transfer> sink,
      Map<HouseholdId, Map<CommodityId, Long>> goodsDebited,
      Map<HouseholdId, Map<CurrencyId, Long>> moneyDebited) {
    ResolvedAccountSubject payer =
        resolveAccountCounterparty(
            from,
            unit,
            unitSubject,
            unitWeights,
            householdEconomies,
            enterpriseByProcess,
            viewIndex,
            index);
    ResolvedAccountSubject payee =
        resolveAccountCounterparty(
            to,
            unit,
            unitSubject,
            unitWeights,
            householdEconomies,
            enterpriseByProcess,
            viewIndex,
            index);
    List<Transfer> legs = new ArrayList<>();
    for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      Map<HouseholdId, Long> payerParts =
          payerShares(
              payer.subject(),
              amount,
              payer.weights(),
              household ->
                  stockOf(householdGoods, household, leg.getKey())
                      - goodsDebited
                          .getOrDefault(household, Map.of())
                          .getOrDefault(leg.getKey(), 0L));
      Map<HouseholdId, Long> payeeParts =
          HouseholdRouting.apportion(payee.subject(), amount, payee.weights());
      emitLegs(
          payerParts,
          payeeParts,
          location,
          Map.of(leg.getKey(), 1L),
          Map.of(),
          reason,
          ledger,
          legs);
    }
    for (Map.Entry<CurrencyId, Long> leg : money.entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      Map<HouseholdId, Long> payerParts =
          payerShares(
              payer.subject(),
              amount,
              payer.weights(),
              household ->
                  moneyOf(householdMoney, household, leg.getKey())
                      - moneyDebited
                          .getOrDefault(household, Map.of())
                          .getOrDefault(leg.getKey(), 0L));
      Map<HouseholdId, Long> payeeParts =
          HouseholdRouting.apportion(payee.subject(), amount, payee.weights());
      emitLegs(
          payerParts,
          payeeParts,
          location,
          Map.of(),
          Map.of(leg.getKey(), 1L),
          reason,
          ledger,
          legs);
    }
    if (legs.isEmpty()) {
      throw new IllegalStateException(
          "关系结算铸了一条空转移（两端都没有可分配的量）：unit=" + unit.id().value() + " from=" + from + " to=" + to);
    }
    sink.addAll(legs);
    for (Transfer leg : legs) {
      HouseholdId payerHousehold = HouseholdRouting.requireHouseholdOf(leg.from());
      for (Map.Entry<CommodityId, Long> entry : leg.goods().entrySet()) {
        goodsDebited
            .computeIfAbsent(payerHousehold, ignored -> new LinkedHashMap<>())
            .merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> entry : leg.money().entrySet()) {
        moneyDebited
            .computeIfAbsent(payerHousehold, ignored -> new LinkedHashMap<>())
            .merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return legs.get(0);
  }

  /** 付方/受方份额的笛卡尔拆腿（贪心：逐付方把它的量按受方剩余额填满；金额按份额乘数缩放）。 */
  private static void emitLegs(
      Map<HouseholdId, Long> payerParts,
      Map<HouseholdId, Long> payeeParts,
      HexCoord location,
      Map<CommodityId, Long> goodsUnit,
      Map<CurrencyId, Long> moneyUnit,
      TransferReason reason,
      ProductionLedger.Accumulator ledger,
      List<Transfer> sink) {
    Map<HouseholdId, Long> payeeRemaining = new LinkedHashMap<>(payeeParts);
    for (Map.Entry<HouseholdId, Long> payer : payerParts.entrySet()) {
      long left = payer.getValue();
      for (Map.Entry<HouseholdId, Long> payee : payeeRemaining.entrySet()) {
        if (left <= 0L) {
          break;
        }
        long remaining = payee.getValue();
        if (remaining <= 0L) {
          continue;
        }
        long take = Math.min(left, remaining);
        Map<CommodityId, Long> goods = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> unitLeg : goodsUnit.entrySet()) {
          goods.put(unitLeg.getKey(), Math.multiplyExact(unitLeg.getValue(), take));
        }
        Map<CurrencyId, Long> moneyMap = new LinkedHashMap<>();
        for (Map.Entry<CurrencyId, Long> unitLeg : moneyUnit.entrySet()) {
          moneyMap.put(unitLeg.getKey(), Math.multiplyExact(unitLeg.getValue(), take));
        }
        sink.add(
            ledger.mint(
                HouseholdActors.of(payer.getKey()),
                HouseholdActors.of(payee.getKey()),
                location,
                goods,
                moneyMap,
                reason));
        payee.setValue(remaining - take);
        left -= take;
      }
      if (left > 0L) {
        throw new IllegalStateException(
            "关系转移拆腿后付方仍有剩余（受方份额不足；状态已坏）：payer=" + payer.getKey() + " left=" + left);
      }
    }
  }

  /**
   * ★★ <b>周期末的产出（§四 周期结算 1~5；税明确不做）</b>—— T4 起**产出不再写进阶层行**（R5 ②）。
   *
   * <p>★★ <b>本方法的形状（一步都不能少）</b>：
   *
   * <pre>
   * ① 规模 = {@link ProductionEfficiencyBook} 在关账处按 §6.2 求好的 scale（最紧约束 ④ + 修正乘算 ⑤；含四个余数结转）
   * ② 逐商品：数量_j = GM 覆盖表命中则覆盖值、否则配方默认（§6.2 ⑥）；
   *    毛产 = 规模 × 数量_j × 1000；损耗 = 毛产 × (饲料 + 折旧)‰；净产 = 毛产 − 损耗
   *      · 毛产 / 损耗 → ledger（I4.2 的 ΣOutput / ΣLoss）
   *      · **净产 → operator 的产权条目**（+net 一条；★ 产出离开 HouseholdEconomy 的**唯一去处**）
   * ③ 按 relation 结算（{@link ProductionSettlement}）：转出/收入**都是产权条目**（H1：受方恒为 actor，
   *      {@code ToCohort} 的家户 actor = {@code HouseholdActors.of(cohort)}）
   * ④ 家户那一条**同时记进两处**：会话工作副本（{@code householdGoods}，下一日消费读它）+ 本行流水 {@code income}
   *      （读口）—— ★ 条目本身照旧进 ledger（app 侧落盘的口径见 {@code EconomyDayStepper#step}）
   * </pre>
   *
   * <p>★★ <b>为什么 ② 与 ③ 必须在同一个方法里</b>（T4 与 T5 必须同批落地的全部理由）：产出一旦离开 {@code HouseholdEconomy}，
   * 家户唯一还有实物的通道就只剩 ③ 的实付 —— 少了它，**家户当场断粮**（而账面上看不出少了谁：产权条目照旧生成）。 由 {@code
   * ProductionLedgerTest#theCohortGetsItsPaidShareIntoTheConsumptionRow} 逐值守着。
   *
   * <p>★★ <b>H1.3：{@code deliverCohortIntake} 已删</b>（那一步做的是"按人口把 cohort 入账分派到行"）：受方就是**唯一那个家户** ⇒
   * 分派、取整余数、以及"解析不到行 ⇒ 补一条 {@code +unresolved} 留在 operator"的兜底**全都不再需要**。 取而代之的是**两条
   * fail-closed**（都在 {@link #requireCohortRows} 里，判在所有公式之前）：
   *
   * <ul>
   *   <li>规则指名的 cohort **必须有行**（家户不存在 ⇒ 当场抛；不许静默留在 operator —— 那会把"配置错了"伪装成"operator 自留"）；
   *   <li>该 cohort **必须住在这一格**（{@code cohort.hex() == facts.location()}）：家户账只住在它自己的格 （{@code
   *       HouseholdAccountKey(actor, cohort.hex())}），否则条目与账本会落在两个地方。
   * </ul>
   *
   * <p>★ <b>缺 {@code relation} 的产业</b>（{@code relations} 表里没有它）：<b>没有规则要结算</b>，产出全部留在 operator ——
   * 与"空规则表 ⇒ 全归 {@code residualOwner}"是**同一条等价路径**（裁定 E9）。
   *
   * <p>★ <b>如实记的边界</b>：{@link ProductionSettlement.Facts#inputs()} 本阶段没有公式读它（表里没有用到它的档）。
   *
   * <p>★★ <b>已知边界（Z2 记录，不在本批修）</b>：本批只让<b>生产路径</b>看见修正参数与 GM 产出数量覆盖；劳动/市场/债务/预期利润等 <b>规划读数</b>仍走
   * {@link ProductionProcessBook#plannedCapacityScaleOf} 与配方默认数量（§13.7），看不到这两个参数 ——
   * 读数与实收产出的差额待另批对齐。
   *
   * @param scale 关账处按 §6.2 求好的规模（含平均修正与余数结转）
   * @param outputQuantityOverrides 该产业的 GM 产出数量覆盖（缺商品 = 回落配方 {@code outputPerUnit()}）
   * @param income 逐家户的实物入账累加器（关系实付那几笔落在这里 ⇒ 读口与守恒式都读它）
   * @param householdGoods ★★ 家户账的会话工作副本（**就地更新**：关系实付计进来）
   * @param relations 生产关系表（键 = 产业 id；缺键 ⇒ 无规则）
   * @param ledger 当天的发生额累加器（毛产 / 损耗 / 产权条目 / 货币待办都进这里）
   */
  private static void harvest(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
      Map<HouseholdId, Long> cycleLaborByHousehold,
      Map<CommodityId, Long> cycleInputUsed,
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      io.mosire.simos.economy.model.ProductionRoster roster,
      Map<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      SettlementIndex index,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal) {
    ProductionRecipe recipe = industry.recipe();
    HexCoord location = hexOfIndustry(industry.id());
    ActorRef operator = unit.operator();
    // ★★ P2-A §13.3：unit 的账户主体 = 组织者/经营者家户（单一或集体）。解析不到 ⇒ 具名缺口（见 creditOutput）。
    HouseholdRouting.Subject subject =
        HouseholdRouting.subjectOf(unit, householdEconomies, enterpriseByProcess, index);
    Map<HouseholdId, Long> subjectWeights =
        HouseholdRouting.weightsOf(unit.id(), index, householdEconomies);
    // ★★ **逐商品产出入账**：数量_j = GM 覆盖命中则覆盖值、否则配方默认（§6.2 ⑥）；
    //   毛产 = 规模 × 数量_j × 1000 毫/单位（其余算式一字未改）。
    Map<CommodityId, Long> grossByCommodity = new LinkedHashMap<>();
    Map<CommodityId, Long> netByCommodity = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> output : recipe.outputPerUnit().entrySet()) {
      CommodityId commodity = output.getKey();
      long quantityPerUnit = outputQuantityOverrides.getOrDefault(commodity, output.getValue());
      long gross = scale * quantityPerUnit * MILLI_PER_GRAIN; // 毫单位（毛产出）
      if (gross <= 0L) {
        continue;
      }
      // ★★ 生产消耗 = **饲料 + 农具折旧**（v2 spec §3.4：留种已移出收获扣减，改在现扣投入步）——
      //   两项各自具名，此处取**两者之和**、**逐商品按同一千分比**（作坊的 3% 即织机磨损）。
      // ★★★ D-032 C2（D2 §8.3）：毛产 → 净产只走 `ProductionOutputNetting`（全仓唯一拼写点）。
      ProductionOutputNetting.Netted nettedOutput = ProductionOutputNetting.net(gross);
      long loss = nettedOutput.loss();
      long net = nettedOutput.net();
      ledger.addGross(industry.id(), commodity, gross);
      ledger.addLoss(industry.id(), commodity, loss);
      grossByCommodity.put(commodity, gross);
      netByCommodity.put(commodity, net);
    }
    if (netByCommodity.isEmpty()) {
      return; // 规模 0（或产出表为空）⇒ 没有产出、也没有可付的：连规则都不必结算
    }
    // ★★★ D-032 C2（D2 §8.3）：名册方案只走 RoleDistribution —— 先把 net 按角色池/角色内拆到户，
    //   直接逐户 credit（不先记给 operator 再转出，避免双记），并且绝不调用旧 `ProductionSettlement` 产出分配。
    if (roster != null) {
      creditRosterNetPool(
          day,
          unit,
          industry,
          roster,
          cycleLaborByParticipant,
          assetShares,
          netByCommodity,
          householdEconomies,
          householdGoods,
          income,
          ledger);
      return;
    }
    // ★★ **R5 ②：净产进产出计提（+净产 → 账户主体家户），不再写进本产业的行**。
    //   ★ 单一主体 ⇒ 一条计提；集体主体 ⇒ 按劳动权重分给各家家户（产出归属与投入代理同一批人）。
    for (Map.Entry<CommodityId, Long> netEntry : netByCommodity.entrySet()) {
      creditOutput(
          subject,
          subjectWeights,
          householdGoods,
          operator,
          netEntry.getKey(),
          netEntry.getValue(),
          location,
          ledger);
    }
    ProductionRules relation = relations.get(unit.id());
    if (relation == null) {
      return; // 缺 relation ⇒ 没有规则：产出全部留在 operator（等价路径，见方法注释）
    }
    // ★★ **H1.3 的 fail-closed**：受方家户必须存在、且住在这一格（判在所有公式之前 —— 与 E14 同款）。
    requireCohortRows(relation, householdEconomies, location);
    // ★★ **R5 ③：按 relation 结算**（priority 序、付款上限 = 本周期收到的产出、E14 的守卫都在 {@link
    //   ProductionSettlement} 里 —— 本方法只负责"把事实递给它、把结果落到该落的地方"）。
    //   ★★ P2-A §13.3：结算的 from 恒为 relation.operator()（可能是组织 actor）⇒ 这里用一个**路由铸造口**
    //   把它解析成家户：单一主体一条腿；集体主体按劳动权重拆成多条腿（每条的两端恒为家户 actor）。
    List<Transfer> routedTransfers = new ArrayList<>();
    // ★ 同一 unit 的多条规则共用一批家户时，付方可用量必须在**本轮结算内**累计扣减（否则同一笔钱会被两条规则各花一次）。
    Map<HouseholdId, Map<CommodityId, Long>> routedGoodsDebited = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> routedMoneyDebited = new LinkedHashMap<>();
    // ★★★ W2-fix7 / D1②（v3.10 §7 裁定②）：**实物租腿三级支付链的②级** —— 付方主体结算前的实物库存（逐商品合计）。
    //   ★ 上面的 creditOutput 已把本周期净产计进这批家户的账 ⇒ ①"本 unit 产出"与 ②"家户库存"在这张表里是
    //     同一份数的两级（与 ProductionSettlement.availableOf 的口径注同源）。★ 主体 = unit 的账户主体，
    //     而 {@code EconomyData} 的构造期守卫已判死 {@code relation.operator() == unit.operator()} ⇒ 与路由付方同源。
    Map<CommodityId, Long> payerStock = availableGoodsOf(subject, householdGoods);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation,
            new ProductionSettlement.Facts(
                location,
                grossByCommodity,
                netByCommodity,
                // ★★★ D-032 B3（I9）：投入与"谁出了多少工"一律取**回执**的实际事实（同源、同一次关账）。
                cycleInputUsed,
                cycleLaborByHousehold,
                industry.outputPerUnit(),
                // ★★ 货币档的付款上限 = **账户主体家户们的可花货币合计**（逐币种）；解析不到主体 ⇒ 空表（实付 0、
                //   欠额进读数 —— 如实报，不是静默付 0）。
                availableMoneyOf(subject, householdMoney),
                // ★ W2-fix7 / D1②：实物租腿的第二级（见上面的 payerStock 口径注）
                payerStock),
            (from, to, transferLocation, goods, money, reason) -> {
              Transfer first =
                  routeRelationTransfer(
                      unit,
                      subject,
                      subjectWeights,
                      from,
                      to,
                      transferLocation,
                      goods,
                      money,
                      reason,
                      householdEconomies,
                      householdGoods,
                      householdMoney,
                      enterpriseByProcess,
                      viewIndex,
                      index,
                      householdOfActor,
                      ledger,
                      routedTransfers,
                      routedGoodsDebited,
                      routedMoneyDebited);
              return first;
            });
    // ★★ **H2：实付一律是转移**（每条两端恒为家户）—— 铸的时候已经进了当天的账，这里只需
    //   ① 把两端落到会话副本上（唯一 applier）② 把收方的实物腿记进流水（实物入账读数）。
    for (Transfer transfer : routedTransfers) {
      applyTransfer(householdGoods, householdMoney, householdOfActor, transfer, issuanceJournal);
      HouseholdId cohort = householdOfActor.get(transfer.to());
      if (cohort != null) {
        for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
          addGoods(income, cohort, leg.getKey(), leg.getValue());
        }
      }
    }
    // ★★ **S4：逐规则的实得读数**（应付 / 实付 / 欠）—— 只读、不影响守恒、不落债权。
    for (ProductionLedger.RuleSettlement reading : outcome.ruleSettlements()) {
      ledger.addRuleSettlement(reading);
    }
    for (CompensationRule rule : outcome.deferredMoney()) {
      ledger.addDeferred(rule);
    }
    // ★★★ W2-fix7 / D1②（v3.10 §7 裁定②）：**实物租腿三级支付链的日志面** —— ①本 unit 产出 → ②家户库存 →
    //   ③具名缺口。"付不出"绝不静默：缺口一条 INFO（默认档就看得见，含 应付/实付/缺口/产出/库存/是否可产），
    //   付得出来的逐条在 DEBUG（现场判"租金吃的是产出还是老库存"）。★ 缺口同时进 RuleSettlement.owed（S4 读数）
    //   并在关账处被 capitalizeArrears 折成债权 —— 三处都读得到，不是只有日志。
    logInKindRentChain(day, unit, industry, outcome, netByCommodity, payerStock);
  }

  /**
   * ★★★ <b>W2-fix7 / D1②（控制方裁定 v3.10 §7）：实物租腿「三级支付链」的日志面</b>。
   *
   * <pre>
   * ① 本 unit 产出 = netByCommodity[commodity]（本周期这条产业的净产；creditOutput 已计进家户账）
   * ② 家户库存     = payerStock[commodity]（结算前的会话库存合计，含 ①）
   * ③ 具名缺口     = 应付 − 实付 ⇒ **INFO 一条**（默认档可见）+ RuleSettlement.owed（S4 读数）+ 关账资本化成债权
   * </pre>
   *
   * <p>★ <b>为什么逐条打、而不是只打缺口</b>：现场要能回答"这一轮租金吃的是新产出还是老库存" —— 只打缺口时 "付得出"的那些一条都看不见，读者无法判断 D1
   * 的三级链到底有没有被用到（旧实现里这类租腿是当场抛）。
   *
   * <p>★ <b>只读</b>：不改任何状态、不改任何公式、不影响守恒；日志失败不得影响结算（§一.9）。
   *
   * @param outcome 已结算的读数（{@link ProductionLedger.RuleSettlement} 逐规则一条，连实付 0 的也在）
   * @param netByCommodity 本 unit 本周期逐商品净产（①）
   * @param payerStock 付方主体结算前逐商品库存（②；含 ①）
   */
  private static void logInKindRentChain(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionSettlement.Outcome outcome,
      Map<CommodityId, Long> netByCommodity,
      Map<CommodityId, Long> payerStock) {
    for (ProductionLedger.RuleSettlement reading : outcome.ruleSettlements()) {
      if (reading.rule().type() != RuleType.FIXED_IN_KIND_RENT) {
        continue; // 三级支付链只服务实物租腿；其余档的读数走 S4 的既有面
      }
      if (reading.commodity().isEmpty()) {
        continue; // 实物租腿必有商品（CompensationRule 的构造期守卫）；这里是兜底，不猜
      }
      CommodityId commodity = reading.commodity().get();
      boolean producible = industry.outputPerUnit().containsKey(commodity);
      long gap = reading.owed();
      if (gap > 0L) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "IN_KIND_RENT_CHAIN_GAP",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "unit",
                    unit.id().value(),
                    "industry",
                    industry.id().value(),
                    "commodity",
                    commodity.value(),
                    "payee",
                    reading.payee().toString(),
                    "dueMilli",
                    reading.dueAmount(),
                    "paidMilli",
                    reading.paidNow(),
                    "gapMilli",
                    gap,
                    "unitOutputMilli",
                    netByCommodity.getOrDefault(commodity, 0L),
                    "payerStockMilli",
                    payerStock.getOrDefault(commodity, 0L),
                    "commodityInIndustryOutput",
                    producible,
                    "why",
                    "in-kind-rent-chain:unit-output-then-household-stock-remainder-is-a-named-gap"
                        + (producible ? "" : " (该商品不在本产业产出表里)")));
        continue;
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "IN_KIND_RENT_CHAIN",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "unit",
                    unit.id().value(),
                    "industry",
                    industry.id().value(),
                    "commodity",
                    commodity.value(),
                    "payee",
                    reading.payee().toString(),
                    "dueMilli",
                    reading.dueAmount(),
                    "paidMilli",
                    reading.paidNow(),
                    "unitOutputMilli",
                    netByCommodity.getOrDefault(commodity, 0L),
                    "payerStockMilli",
                    payerStock.getOrDefault(commodity, 0L),
                    "commodityInIndustryOutput",
                    producible,
                    "why",
                    "in-kind-rent-paid-in-full (①本 unit 产出 → ②家户库存)"));
      }
    }
  }

  // ── 套利活动的日志（AGENTS §一.9：新阶段必须有 INFO/DEBUG/TRACE 三级）──────────────────────

  /**
   * ★★ <b>本轮套利决定的 INFO 汇总</b>（谁、哪个方向、多少量 —— "这一轮发生了什么"）。
   *
   * <p>★ <b>只看 INFO 的人能回答</b>：本日有几户在套利、共要买多少货、为此花了多少劳动、预期净收益多少。 逐户理由在 {@code LaborQueueSettlement}
   * 的 DEBUG（{@code ARBITRAGE_OPPORTUNITY} / {@code ARBITRAGE_GRANTED}）， 逐笔订单在 {@code
   * MarketSettlement} 的 TRACE（{@code ARBITRAGE_BUY_ORDER}）。
   */
  private static void logArbitrageRound(long day, MarketArbitragePlan plan) {
    if (plan.isEmpty()) {
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ARBITRAGE_ROUND",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "households",
                    0,
                    "buys",
                    0,
                    "totalQuantityMilli",
                    0,
                    "totalLaborMilli",
                    0,
                    "reason",
                    "no-opportunity-or-no-market"));
      }
      return; // 没有套利：不刷 INFO（"今天没有套利"不是生命周期事件）
    }
    long buys = 0L;
    long holds = 0L;
    long totalQuantity = 0L;
    long totalLabor = 0L;
    long totalNetMicro = 0L;
    java.util.TreeSet<String> commodities = new java.util.TreeSet<>();
    for (MarketArbitragePlan.Instruction instruction : plan.instructions()) {
      if (instruction.direction() == HouseholdActivity.Direction.BUY) {
        buys++;
      } else {
        holds++;
      }
      totalQuantity = Math.addExact(totalQuantity, instruction.quantityMilli());
      totalLabor = Math.addExact(totalLabor, instruction.laborMilli());
      // ★★ 量纲（2026-10-08 诊断缺陷修复；改前这里是"毫商品 × 毫/单位 = 毫²"却叫 Micro）：
      //     量（毫商品）× 单位价差（**微** numeraire / **商品单位**）÷ 1000（毫商品/商品单位）
      //     = **微 numeraire**  ⇒ 变量名 totalNetMicro 与算式从此同名同尺。
      totalNetMicro =
          Math.addExact(
              totalNetMicro,
              Math.multiplyExact(instruction.quantityMilli(), instruction.edgeMicro())
                  / MILLI_PER_GRAIN);
      commodities.add(instruction.commodity().value());
    }
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "ARBITRAGE_ROUND",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "day",
                day,
                "households",
                plan.size(),
                "buys",
                buys,
                "holds",
                holds,
                "totalQuantityMilli",
                totalQuantity,
                "totalLaborMilli",
                totalLabor,
                // 预期净收益（**毫** numeraire）= 微 numeraire ÷ 1000（微/毫是价格刻度，与"毫商品/商品单位"无关，
                // 故这里用的是 TradeArbitrageActivity.MICRO_PER_MILLI 而不是 MILLI_PER_GRAIN —— 两者数值同为 1000，
                // 量纲完全不同，混用正是本次修的缺陷类型）。
                "totalExpectedNetMilli",
                totalNetMicro / TradeArbitrageActivity.MICRO_PER_MILLI,
                "commodities",
                String.join(",", commodities)));
    if (TRACE.isDebugEnabled()) {
      for (MarketArbitragePlan.Instruction instruction : plan.instructions()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ARBITRAGE_INSTRUCTION",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "household",
                    instruction.household().value(),
                    "direction",
                    instruction.direction(),
                    "commodity",
                    instruction.commodity().value(),
                    "quantityMilli",
                    instruction.quantityMilli(),
                    // ★ 2026-10-08 诊断缺陷修复：字段名 = 真实量纲（微 numeraire / 商品单位）
                    "reservationMicro",
                    instruction.reservationMicro(),
                    "marketMicro",
                    instruction.marketMicro(),
                    "edgeMicro",
                    instruction.edgeMicro(),
                    "laborMilli",
                    instruction.laborMilli()));
      }
    }
  }

  /**
   * ★★ <b>B4（2026-10-08）：本轮外汇窗口装配的日志</b>（§一.9）—— 新接线（区级汇率参与报价）必须能从日志看出来：
   *
   * <ul>
   *   <li><b>INFO</b>（有窗口时一条 {@code FX_WINDOWS_ASSEMBLED}）：这一轮发生了什么 —— 几个窗口、区表几条、
   *       其中几个区带区级覆盖、政府表几个（"有窗口"是生命周期事件；"没有窗口"不刷 INFO，与 {@code logArbitrageRound} 同款）；
   *   <li><b>DEBUG</b>（无论有没有窗口都一条 {@code FX_WINDOWS_ASSEMBLED_DETAIL}）：为什么 —— 没有窗口时写明"没有任何生效报价 ⇒
   *       fail-closed 不开张"，逐窗口一条 {@code FX_WINDOW_ASSEMBLED} 记属主/币对/买价/卖价/储备上限，以及
   *       <b>这个价来自区级覆盖（哪个区）还是 GOV 级</b>（区级优先回落的现场判据）。
   * </ul>
   *
   * <p>★ 日志只读状态：不写状态、不改公式、失败不影响结算（{@code EventLog} 的口径）。
   *
   * <p>★★ <b>B2（2026-10-10）：本方法是**逐日**读者 ⇒ 入参是当日视图</b> —— 政府表在段内会变（GM 设报价、发行腿 追加记录），读段首 {@code
   * EconomyData} 会把"今天有几个政府窗口/几条区级覆盖"读成段首那一份。
   */
  private static void logFxWindows(long day, EconomyDayView view, FxRoundInput fxInput) {
    List<MarketZone> zones = MarketZoneBook.zones(view.marketZones());
    int zonesWithRates = 0;
    for (MarketZone zone : zones) {
      if (!zone.officialRates().isEmpty()) {
        zonesWithRates++;
      }
    }
    if (fxInput.isActive()) {
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_WINDOWS_ASSEMBLED",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  day,
                  "windows",
                  fxInput.windows().size(),
                  "zones",
                  zones.size(),
                  "zonesWithOfficialRates",
                  zonesWithRates,
                  "governments",
                  view.governments().size()));
    }
    if (!FX.isDebugEnabled()) {
      return;
    }
    int governmentsWithRates = 0;
    for (Government government : view.governments().values()) {
      if (!government.officialRates().isEmpty()) {
        governmentsWithRates++;
      }
    }
    EventLog.channel(FX)
        .debug(
            LogEvent.of(
                "FX_WINDOWS_ASSEMBLED_DETAIL",
                EconomyLogSource.ECONOMY_FX,
                "day",
                day,
                "windows",
                fxInput.windows().size(),
                "zones",
                zones.size(),
                "zonesWithOfficialRates",
                zonesWithRates,
                "governmentsWithRates",
                governmentsWithRates,
                "reason",
                fxInput.isActive()
                    ? "有生效报价（区级优先、按币对回落 GOV 级）"
                    : "没有任何生效报价 ⇒ 本轮没有外汇窗口（fail-closed：没有政策价可锚）"));
    for (FxRoundInput.Window window : fxInput.windows()) {
      OfficialRate rate = window.rate();
      List<MarketZone> covering =
          MarketZoneBook.zonesCovering(view, window.governmentId(), rate.base(), rate.quote());
      EventLog.channel(FX)
          .debug(
              LogEvent.of(
                  "FX_WINDOW_ASSEMBLED",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  day,
                  "government",
                  window.governmentId().value(),
                  "base",
                  rate.base().value(),
                  "quote",
                  rate.quote().value(),
                  "buyPerMille",
                  rate.buyPerMille(),
                  "sellPerMille",
                  rate.sellPerMille(),
                  "reserveCapBaseMilli",
                  window.reserveCapBaseMilli(),
                  "rateSource",
                  covering.isEmpty()
                      ? "government"
                      : "market-zone:" + covering.get(0).zoneId().value()));
      if (covering.size() > 1) {
        // ★ 一个 GOV 能被多个区的区级报价覆盖（它能发行多个区的法定币）、同币对给了不同报价 ⇒ 规范序第一个区胜出，
        //   其余被覆盖：被丢掉的那几条政策价必须具名出现在日志里（不许静默吞掉另一条政府报价）。
        EventLog.channel(FX)
            .debug(
                LogEvent.of(
                    "FX_ZONE_RATE_CONFLICT",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    day,
                    "government",
                    window.governmentId().value(),
                    "base",
                    rate.base().value(),
                    "quote",
                    rate.quote().value(),
                    "chosenZone",
                    covering.get(0).zoneId().value(),
                    "chosenBuyPerMille",
                    rate.buyPerMille(),
                    "chosenSellPerMille",
                    rate.sellPerMille(),
                    "droppedZoneRates",
                    droppedZoneRates(covering, rate.base(), rate.quote()),
                    "rule",
                    "MarketZoneBook#zoneCovering：规范序第一个覆盖该币对的区胜出"));
      }
    }
  }

  /** 冲突里被覆盖掉的区级报价（{@code zone=buy/sell,…}；只用于 DEBUG 日志，不改任何判定）。 */
  private static String droppedZoneRates(
      List<MarketZone> covering, CurrencyId base, CurrencyId quote) {
    StringBuilder text = new StringBuilder();
    for (MarketZone zone : covering.subList(1, covering.size())) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(zone.zoneId().value())
          .append('=')
          .append(
              zone.officialRate(base, quote)
                  .map(rate -> rate.buyPerMille() + "/" + rate.sellPerMille())
                  .orElse("?"));
    }
    return text.toString();
  }

  // ── 转移的落账（H2：唯一写会话副本的地方）────────────────────────────────────────────

  /**
   * ★★ <b>把一条转移的两端落到家户账户工作副本上</b>（P2-A §13.3：账户主体只有家户）—— <b>全模块唯一</b>写这些副本库存的 "换手"路径（另一类是消费/投入的扣减，见
   * {@link #consumeFromHousehold} 与 {@code drawCycleInputs}）。
   *
   * <p>★★ <b>两端必须解析到已登记家户</b>：{@code householdOfActor}（= 现存家户 actor 反查表）命中 ⇒ 家户账； 命不中 ⇒
   * <b>具名抛</b>（不再有"经营者账"旁路、也不再有"跳过这一端交给 app 落账"的静默口径 —— 结算侧必须在 铸转移之前就解析到家户）。
   *
   * <p>★★ <b>M1.4：本方法是两遍式</b>：第一遍 {@link #validateApplyTransfer} <b>只读</b>地判"每一条付方腿是否扣得动" （余额不足 /
   * 货币不足 ⇒ 走发行闸门），任一条不合法都在<b>会话活表一字未动</b>时抛出；第二遍才统一落账。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    applyTransfer(
        householdGoods, householdMoney, Map.of(), Map.of(), householdOfActor, transfer, null);
  }

  /** ★★ E3：带发行审计落点的入口（无冻结）。 */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    applyTransfer(
        householdGoods, householdMoney, Map.of(), Map.of(), householdOfActor, transfer, journal);
  }

  /** ★★ <b>M2：带冻结表的 {@code applyTransfer}</b>（无发行落点）。 */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    applyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        householdOfActor,
        transfer,
        null);
  }

  /**
   * ★★ <b>E3：唯一转移写口（带冻结 + 发行审计落点）</b>：
   *
   * <pre>
   * 第一遍（只读校验）：付方余额不足时先 MoneyIssuance.requireIssuerOf(currency)；
   *   · 付方 ≠ 发行源 ⇒ 保留旧 fail-closed 抛；
   *   · 付方 = 发行源 ⇒ 允许单边发行，但必须带 journal（否则当场抛，不静默造钱）。
   * 第二遍（统一落账）：普通腿逐值不变；发行腿只扣发行源实际持有的部分（不越过冻结），
   *   收方仍足额到账，差额写一条 FISCAL_ISSUE 审计记录。
   * </pre>
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    Objects.requireNonNull(transfer, "transfer");
    HouseholdId from = requireHouseholdOf(transfer.from(), householdOfActor);
    HouseholdId to = requireHouseholdOf(transfer.to(), householdOfActor);
    // ★★ M1.4 第一遍：**全量校验**（只读）—— 任一条腿不合法都在会话活表一字未动时抛出。
    validateApplyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        from,
        transfer,
        journal);
    // ★★ M1.4 第二遍：**统一落账** —— 此刻所有付方腿的可扣性都已验证过 ⇒ 下面只写、不再判。
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      setStock(
          householdGoods,
          from,
          leg.getKey(),
          stockOf(householdGoods, from, leg.getKey()) - leg.getValue());
    }
    debitHouseholdMoney(householdMoney, householdFrozenMoney, from, transfer, journal);
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      addStock(householdGoods, to, leg.getKey(), leg.getValue());
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      addMoney(householdMoney, to, leg.getKey(), leg.getValue());
    }
  }

  /** 家户 actor → 家户身份；不在现存家户表里 ⇒ 具名抛（账户主体只有家户）。 */
  private static HouseholdId requireHouseholdOf(
      ActorRef actor, Map<ActorRef, HouseholdId> householdOfActor) {
    HouseholdId household = householdOfActor.get(Objects.requireNonNull(actor, "actor"));
    if (household == null) {
      throw new IllegalStateException(
          "转移的一端不是已登记家户（账户主体只有家户；庄园/作坊/商号必须解析到组织者/经营者家户）："
              + actor
              + "；家户表键数="
              + householdOfActor.size());
    }
    return household;
  }

  /** 家户货币付端：普通腿照旧扣；发行腿只扣实际持有（不越过冻结），差额交 journal 记发行。 */
  private static void debitHouseholdMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId from,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      CurrencyId currency = leg.getKey();
      long amount = leg.getValue();
      long balance = moneyOf(householdMoney, from, currency);
      long frozen = householdFrozenMoney.getOrDefault(from, Map.of()).getOrDefault(currency, 0L);
      long debit = amount;
      if (balance < amount) {
        ActorRef issuer = MoneyIssuance.requireIssuerOf(currency);
        if (!transfer.from().equals(issuer)) {
          throw new IllegalStateException(
              "转移把家户的货币扣成了负数，而付方不是发行源（透支 = 发行）：家户="
                  + from
                  + " 币种="
                  + currency
                  + " 余额="
                  + balance
                  + " 扣减="
                  + amount
                  + " 发行源="
                  + issuer
                  + "；转移="
                  + transfer);
        }
        debit = Math.min(amount, Math.max(0L, balance - frozen));
        if (debit < amount) {
          if (journal == null) {
            throw new IllegalStateException("这条入口没有发行审计落点，拒绝单边发行（不许造钱而没有审计记录）：转移=" + transfer);
          }
          journal.recordIssuance(transfer, currency, amount - debit);
        }
      }
      setMoney(householdMoney, from, currency, balance - debit);
    }
  }

  /**
   * ★★ <b>M1.4 第一遍：把"这一次转移扣得动吗"一次判完</b>（只读）。
   *
   * <p>★ E3 起货币不足的判据收成两条：付方不是发行源 ⇒ 保留旧 fail-closed 文案；付方是发行源 ⇒ 必须带 {@link
   * MoneyIssuanceJournal}（否则不静默发行），且只扣实际持有的部分（冻结仍然受保护）。
   */
  private static void validateApplyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId from,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      long stock = stockOf(householdGoods, from, leg.getKey());
      if (stock < leg.getValue()) {
        throw new IllegalStateException(
            "转移把家户账扣成了负数（不许凭空吞）：家户="
                + from
                + " 商品="
                + leg.getKey()
                + " 余额="
                + stock
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
      long frozen =
          householdFrozenGoods.getOrDefault(from, Map.of()).getOrDefault(leg.getKey(), 0L);
      if (stock - leg.getValue() < frozen) {
        throw new IllegalStateException(
            "转移会花掉家户账上已冻结的商品（冻结只表达已明确的占用）：家户="
                + from
                + " 商品="
                + leg.getKey()
                + " 余额="
                + stock
                + " 冻结="
                + frozen
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      long balance = moneyOf(householdMoney, from, leg.getKey());
      long frozen =
          householdFrozenMoney.getOrDefault(from, Map.of()).getOrDefault(leg.getKey(), 0L);
      if (balance < leg.getValue()) {
        ActorRef issuer = MoneyIssuance.requireIssuerOf(leg.getKey());
        if (!transfer.from().equals(issuer)) {
          throw new IllegalStateException(
              "转移把家户的货币扣成了负数，而付方不是发行源（透支 = 发行）：家户="
                  + from
                  + " 币种="
                  + leg.getKey()
                  + " 余额="
                  + balance
                  + " 扣减="
                  + leg.getValue()
                  + " 发行源="
                  + issuer
                  + "；转移="
                  + transfer);
        }
        if (journal == null) {
          throw new IllegalStateException(
              "家户付方是发行源，但这条入口没有发行审计落点，拒绝单边发行（不许造钱而没有审计记录）：转移=" + transfer);
        }
        continue; // 发行腿：只扣实际持有的部分，冻结仍受 debitHouseholdMoney 保护
      }
      if (balance - leg.getValue() < frozen) {
        throw new IllegalStateException(
            "转移会花掉家户账上已冻结的货币（冻结只表达已明确的占用）：家户="
                + from
                + " 币种="
                + leg.getKey()
                + " 余额="
                + balance
                + " 冻结="
                + frozen
                + " 扣减="
                + leg.getValue()
                + "；转移="
                + transfer);
      }
    }
  }

  /**
   * ★ <b>为什么还活着（W2-fix6 裁定保留，不是孤儿）</b>：计划内待接线入口 —— 消费者 = **P7 的 GM 编辑工具**（回笼写口） 与日结算/命令层的同包代码（批次：P5
   * 立口、P7/GM 工具批接线；接上之前零自动调用方，属预期）。
   *
   * <p>★★ <b>P5：回笼（WITHDRAWAL）写口的包内入口</b> —— 薄转发到唯一实现 {@link TreasuryWithdrawal#withdraw}；
   * 发行授权、余额/冻结守卫、审计 id 与重放幂等全部在那一处，本方法不再写第二份算式。
   *
   * <p>★ <b>为什么保留这个入口</b>：日结算/命令层的同包代码与 P7 的 GM 编辑工具可以只依赖 {@code EconomySettlement} 这一个门面；跨包调用方直接走
   * {@link TreasuryWithdrawal}（public）。两条路落到同一实现、 同一张发行审计表。★ P7/GM 接上之前，本批没有自动调用方。
   */
  static MoneyIssuanceRecord withdrawFromTreasury(
      EconomySession session,
      AccountSession accounts,
      GovernmentId governmentId,
      CurrencyId currency,
      long amount,
      long day,
      String operationRef) {
    return TreasuryWithdrawal.withdraw(
        session, accounts, governmentId, currency, amount, day, operationRef);
  }

  /**
   * ★★ <b>E3：一次结算会话的发行审计收集器</b>（线程安全，供并行分区提交发行腿）。id 由 {@link
   * MoneyIssuanceId#forTransfer(TransferId, CurrencyId)} 确定性派生，因此重放/分支不会因线程调度产生不同记录。
   *
   * <p>★★ <b>P5 补强的授权校验</b>：{@link #recordIssuance} 不再只按国库 actor 找到政府就记 —— 它还显式要求 {@code
   * government.issuable()} <b>包含</b>这次记录的币种（具名拒绝）。跑在前面的 {@code
   * MoneyIssuance.requireIssuerOf(currency)} 只保证"这个付方是某个已登记发行人"，本检查把"该政府的币种集合也必须
   * 授权这个币种"钉在同一处；两道闸同时成立才写 FISCAL_ISSUE。
   */
  static final class MoneyIssuanceJournal {

    private final long period;
    private final Map<ActorRef, Government> governmentByTreasury;
    private final ConcurrentLinkedQueue<MoneyIssuanceRecord> records =
        new ConcurrentLinkedQueue<>();

    MoneyIssuanceJournal(Map<GovernmentId, Government> governments, long period) {
      this.period = period;
      LinkedHashMap<ActorRef, Government> byTreasury = new LinkedHashMap<>();
      for (Government government : governments.values()) {
        Government previous = byTreasury.putIfAbsent(government.treasury(), government);
        if (previous != null && !previous.id().equals(government.id())) {
          throw new IllegalStateException(
              "同一个国库 actor 对应两个政府，无法写发行审计："
                  + government.treasury()
                  + " → "
                  + previous.id()
                  + " / "
                  + government.id());
        }
      }
      this.governmentByTreasury = Map.copyOf(byTreasury);
    }

    /** 记录一条单边发行差额（金额 &gt; 0；发行主体必须是当前政府表里的国库，且该政府的 {@code issuable} 必须包含币种）。 */
    void recordIssuance(Transfer transfer, CurrencyId currency, long amount) {
      Objects.requireNonNull(currency, "currency");
      if (amount <= 0L) {
        throw new IllegalArgumentException("发行差额必须 > 0: " + amount);
      }
      Government government = governmentByTreasury.get(transfer.from());
      if (government == null) {
        throw new IllegalStateException(
            "发行腿的付方不在当前政府表里（说不出是哪届政府在发行）：" + transfer.from() + "；转移=" + transfer);
      }
      if (!government.issuable().contains(currency)) {
        throw new IllegalArgumentException(
            "发行审计的币种不在该政府的 issuable 集合内（拒绝按国库归属静默记一笔它无权发行的币种）：政府="
                + government.id()
                + " 币种="
                + currency
                + " issuable="
                + government.issuable()
                + "；转移="
                + transfer);
      }
      records.add(
          new MoneyIssuanceRecord(
              MoneyIssuanceId.forTransfer(transfer.id(), currency),
              government.id(),
              transfer.day(),
              period,
              currency,
              amount,
              MoneyIssuanceKind.FISCAL_ISSUE,
              "发行腿：" + transfer.reason().name()));
    }

    /** 取出并按 id 稳定排序（跨并行分区也得到确定性顺序）。 */
    List<MoneyIssuanceRecord> drainSorted() {
      List<MoneyIssuanceRecord> drained = new ArrayList<>(records);
      records.clear();
      drained.sort(Comparator.comparing(record -> record.id().value()));
      return drained;
    }
  }

  /** 家户消费扣减（把 {@code amount} 从该家户库存移进 consumed 流水；缺账 ⇒ 0，不造负库存）。 */
  private static void consumeFromHousehold(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    long stock = stockOf(householdGoods, key, commodity);
    long eaten = Math.min(stock, amount);
    setStock(householdGoods, key, commodity, stock - eaten);
    addGoods(consumedGoods, key, commodity, eaten);
  }

  /**
   * ★★ <b>T-fix：非换手损耗扣减的唯一写口</b>（"货物离开某家户的账户、不换手给任何人"这一类；与 {@link #consumeFromHousehold}
   * 的消费/投入扣减同族）。
   *
   * <pre>
   * 可用量 = max(0, stock − frozen)      ← 冻结只表达已明确的占用：**被冻结的货不许被损耗扣掉**
   * 可用量 &lt; requested ⇒ **一点也不扣**（fail-closed，绝不允许部分扣）⇒ consumedMilli = 0，两本账一字未动
   * 可用量 ≥ requested ⇒ 扣 requested **并同址**记 ledger.addLoss(lossAccount, commodity, requested)
   *                       ⇒ Σ余额 + losses 守恒（"账户减 + 损耗账加"在同一个落点里做完，调用方不可能只做一半）
   * </pre>
   *
   * <p>★★ <b>为什么它不能是一个 {@link #applyTransfer}</b>（如实记，T-fix 的核心结论）：{@code Transfer} 的两端
   * <b>恒为两个不同的已登记家户</b>（{@link Transfer} 的构造不变式 {@code from != to} + 本类 {@link #requireHouseholdOf}
   * 的键集 = 现存家户行），而"损耗"<b>没有对端</b> —— 硬塞一个接收方等于伪造一笔转移： 接收方凭空多出货、账面上还看不出破绽（正是本仓最贵的那类账）。⇒ 损耗按 spec
   * §3.2 的「守恒实现口径（唯一写口 + 非换手落点）」落在<b>非换手</b>那一半，且收成<b>一个</b>口：改前是市场轮自己 {@code setHouseholdStock}
   * 直改会话账， 那条旁路既绕开写口、又不知道冻结（T-fix 要拆掉的正是它）。
   *
   * <p>★ 账户 = {@code (家户, 格)}：本方法的键是家户身份（与 {@link #applyTransfer} 同款），"落在哪一格"由调用方保证。
   */
  static LossConsumption consumeForLoss(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      ProductionLedger.Accumulator ledger,
      IndustryId lossAccount,
      HouseholdId household,
      CommodityId commodity,
      long requestedMilli) {
    Objects.requireNonNull(householdGoods, "householdGoods");
    Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(lossAccount, "lossAccount");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(commodity, "commodity");
    if (requestedMilli <= 0L) {
      throw new IllegalArgumentException("损耗扣减量必须为正（0 不是一次扣减）: " + requestedMilli);
    }
    long stock = stockOf(householdGoods, household, commodity);
    long frozen = frozenGoodsOf(householdFrozenGoods, household, commodity);
    if (Math.max(0L, stock - frozen) < requestedMilli) {
      return new LossConsumption(stock, frozen, 0L); // ★ fail-closed：一点也不扣（不许部分扣）
    }
    setStock(householdGoods, household, commodity, stock - requestedMilli);
    // ★ 守恒：账户减、损耗账加（同一句话里的两条腿，调用方拿不到"只减不记"的中间态）
    ledger.addLoss(lossAccount, commodity, requestedMilli);
    return new LossConsumption(stock, frozen, requestedMilli);
  }

  /**
   * ★ <b>T-fix：一次非换手损耗扣减的读数</b>（{@code consumedMilli} 只可能是 {@code 0} 或请求量，<b>绝无中间值</b> ——
   * 判别力就靠这一条：出现 {@code 0 < consumed < requested} 即"部分扣"，那是缺陷不是读数）。
   *
   * @param stockMilli 扣减前的余额（<b>未减冻结</b>；归因用）
   * @param frozenMilli 扣减前同一 {@code (家户, 商品)} 轴上的冻结量（归因用）
   * @param consumedMilli 实际扣掉的量（{@code 0} = 该次不成立，账未动）
   */
  record LossConsumption(long stockMilli, long frozenMilli, long consumedMilli) {

    /** 扣减前的可用量 {@code max(0, stock − frozen)}（只读算式；不写状态）。 */
    long availableMilli() {
      return Math.max(0L, stockMilli - frozenMilli);
    }

    /** 这次扣减是否被 fail-closed 挡下（= 一点也不扣）。 */
    boolean blocked() {
      return consumedMilli == 0L;
    }
  }

  /**
   * ★★ <b>受方家户的 fail-closed 守卫</b>（H1.3；{@code deliverCohortIntake} 的替代品）：逐条 {@code Payee.ToCohort}
   * 规则判两件事 —— <b>行在</b>、<b>它住在本格</b>。
   *
   * <p>★★ <b>为什么这条守卫必须存在</b>：改前"解析不到行"会补一条 {@code +unresolved} 留在 operator（E17 的等价路径）——
   * 那在"受方是一池人、要按人口分派"的时代是合理的兜底；H1 之后受方就是**那一个家户**（身份一一对应）， "这个家户不存在"只可能是**配置错**（规则指了一个没人住的
   * cohort），把它静默变成"operator 自留"会让账面上完全看不出问题。 本仓纪律：配置错一律当场抛。
   *
   * <p>★ <b>人口为 0 的家户照旧合法</b>（"分配仍可能把钱/物给付给人口 0 的家户" —— 例如地租那条规则）：本守卫只判"行在不在"，
   * 不判人口（判人口会让合法状态被误杀；消费与投入那两条路才按 {@code population > 0} 走，见 {@code settleHexes} / {@code
   * drawCycleInputs}）。
   *
   * <p>★ <b>"住在本格"这一条</b>：家户账的键是 {@code (HouseholdActors.of(cohort), cohort.hex())}，而条目落在 {@code
   * facts.location()} —— 两者不同时，钱会落到这个家户在**别处**的账户上（而它的消费读的是本格的账）⇒ 静默丢失。
   */
  private static void requireCohortRows(
      ProductionRules relation,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HexCoord location) {
    for (CompensationRule rule : relation.rules()) {
      HouseholdId household;
      String source;
      if (rule.recipient() instanceof Payee.ToHousehold toHousehold) {
        household = toHousehold.household();
        source = "家户";
      } else if (rule.recipient() instanceof Payee.ToCohort toCohort) {
        // ★ 旧档视图（S1 迁移前）：按视图反查**恰一个**家户；多于一户 ⇒ fail-closed（视图不再是身份）。
        household = null;
        for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
          if (householdEconomy.view().equals(toCohort.cohort())) {
            if (household != null) {
              throw new IllegalStateException(
                  "规则指名的旧 cohort 视图对上了多个家户（S1 起视图不再是唯一身份）：" + toCohort.cohort() + "；规则=" + rule);
            }
            household = householdEconomy.id();
          }
        }
        if (household == null) {
          throw new IllegalStateException(
              "规则指名的 cohort 没有家户行（H1 fail-closed：受方就是那一个家户，交付不出去就当场抛）："
                  + toCohort.cohort()
                  + "；规则="
                  + rule);
        }
        source = "旧 cohort 视图";
      } else {
        continue;
      }
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        throw new IllegalStateException(
            "规则指名的家户没有行（S1 fail-closed：受方就是那一个家户，交付不出去就当场抛）："
                + source
                + "="
                + household
                + "；规则="
                + rule);
      }
      if (!householdEconomy.view().hex().equals(location)) {
        throw new IllegalStateException(
            "规则指名的家户不住在本格（H1：家户账的 location 取自它的视图，条目却落在产业所在格）：activity="
                + relation.activity()
                + " "
                + source
                + "="
                + household
                + " 视图格="
                + householdEconomy.view().hex()
                + " 产业的格="
                + location
                + "；规则="
                + rule);
      }
    }
  }

  /**
   * ★★ <b>本格各家户本期劳动量</b>（{@code LABOR_AMOUNT} 那一族的分子/分母，也是 M1.7 给养义务的"按什么量"）：键 = **行键本身**（H0：行就是
   * cohort），值 = 该行的 {@code rowLabor}（= <b>按参与率折算后的每日可用劳动</b> × cycleDays）。
   *
   * <p>★★ <b>M1.8：折算只有一处拼写点</b> —— {@link HouseholdEconomy#participationAdjustedLaborMilli()}（=
   * {@code laborMilli × participationPerMille ÷
   * 1000}）。本方法<b>不再自己乘一次</b>参与率：否则读口/配额与这里会各折算一遍，真档数字会崩。
   *
   * <p>★★ <b>H0 起它不再"按 (格, 阶层) 并池"（E28 的收口）</b>：改前两池人的劳动被并进同一个 {@code (格, 阶层)}
   * 键（农村行与城镇行），而**受方行**那一侧已由 E24 分开 ⇒ 城市格上"这一格的产出在四个阶层之间怎么分"被另一池人的劳动**参与计权** （自述量级 ≤ 0.04‰）。现在键 =
   * 行键（含居住维）⇒ <b>规则指名的 cohort（含居住类型）只被它自己那批人的劳动计权</b>，并池消失。
   *
   * <p>★ <b>仍然取"本格"的行</b>（判据不变）：「谁在干活」在真档里只写在有人口的那些行上，而规则不区分产业的劳动 —— {@code LABOR_AMOUNT}
   * 的分母是**这一格全部家户**的劳动之和（口径与改前逐字相同，只是键不合并）。★ 于是同一格的农业家户与作坊家户各按自己的劳动 拿各自规则的份额，而不会互相顶替。
   *
   * <p>★ 只放**非零**的行：{@code ProductionSettlement} 的 {@code laborOf} 查不到即 0，而 {@code Σ劳动} 是分母 —— 塞 0
   * 进去不改变任何一个数，只会把表弄脏。
   *
   * <p>★ <b>可见性 public</b>（M1.7）：读口（{@code ApiViews.industryView} 的给养义务一栏）要用**同一个函数**算"按什么劳动量"，
   * 不许在视图层另写一套（两处各写一套 = 读到的义务与实付的义务会漂开）。
   *
   * @param location 产业所在的那一格（{@code IndustryHexKeys.hexKeyOf} 是唯一拼写点）
   * @param cycleDays 该产业的周期天数（把"每日劳动"折成"本周期劳动"；见下面的量纲注释）
   */
  public static Map<HouseholdId, Long> laborOfCohort(
      Map<HouseholdId, HouseholdEconomy> householdEconomies, HexCoord location, long cycleDays) {
    Map<HouseholdId, Long> byCohort = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (!householdEconomyEntry.getValue().view().hex().equals(location)) {
        continue; // 只取本格的家户（见方法注释：分母的口径与改前逐字相同）
      }
      HouseholdEconomy householdEconomy = householdEconomyEntry.getValue();
      // ★★ **每日口径 × 周期天数 = 本周期口径**（量纲的收口）：行的 `laborMilli` 与发放的配额同口径
      //   （**每日**千分劳动；结算逐日把它累加进 `cycleLaborMilli`），而 {@code LABOR_AMOUNT} 那一族量的是
      //   **本周期**的劳动量（{@code FIXED_IN_KIND_PER_LABOR} 的"每周期一笔"就写在名字里）。
      //   ★ 少了这个乘数，给养只有应有值的 1/cycleDays —— 实测（真档 14,806 人的格）：一周期只拿到 0.8% 的口粮，
      //     第 2 周期起人吃不饱、也播不下种 ⇒ 生产逐周期崩掉（`EconomyRealScaleClothTest` / `WorldgenInitializeToolTest`
      //     的跨周期用例当场红）。★ 分成类（`OUTPUT_SHARE`）不受影响：那一路是比值，量纲自消。
      //   ★ M1.8：参与率的折算收进 HouseholdEconomy 的唯一算法（上面乘一次，这里不许再乘）。
      long rowLabor = householdEconomy.participationAdjustedLaborMilli() * cycleDays;
      if (rowLabor <= 0L) {
        continue;
      }
      byCohort.put(householdEconomyEntry.getKey(), rowLabor);
    }
    return byCohort;
  }

  /** 产业 id 里的格键 → 坐标（{@link IndustryHexKeys} 是唯一拼写点）；拿不到 ⇒ 抛（产权账户必须有地点）。 */
  static HexCoord hexOfIndustry(IndustryId id) {
    return HexCoord.parse(
        IndustryHexKeys.hexKeyOf(id)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "产业 id 里没有格键，无法为产出落产权账户（账户 = (actor, location)，不许拿 (0,0) 顶替）: " + id)));
  }

  /**
   * ★★★ <b>W3（2026-10-11）：报缺口 —— 经济侧对"人"的唯一动作</b>（原 {@code applyFamine} 的替代者）。
   *
   * <p>★★ <b>本方法不改任何状态、不判死、不缩劳动、不动人口</b>：它只把"这一天经济腿看见的逐户缺口"写进 {@link EconomySession} 的瞬态
   * outbox（{@link EconomySession#recordHouseholdGap}），由 {@code simos-app} 的组合根在 {@code step(day)}
   * 之后取走、翻成 Social 的饥荒输入（约束设计书 §2 的 S-11 第②半：<b>口粮缺口 ⇒ 报社会</b>， 社会据此扣劳动/人口；经济侧不自己扣）。
   *
   * <pre>
   * 逐户（只报"口粮缺口 &gt; 0 或 生产花费缺口非空"的户；两条腿都干净的户不进 outbox = 缺省中性）：
   *   ① productionCostGapByCommodity = 当日市场轮 MarketReport.unmetProductionCostByHousehold()
   *        ★ W2 的读口原样取用（"生产花费（内含口粮）的未满足量"）；**没开市 ⇒ 空表**（不是"缺口 0"）
   *   ② rationGapMilli  = 当日 unmetToday[grain]（"家户吃不上"的量；救济全部走完之后）
   *      rationNeedMilli = 当日 naturalNeeds[grain]（同窗口的分母，由 app 从 Social 注入）
   *   ③ household + population（稳定身份；铁律 1）
   * </pre>
   *
   * <p>★★ <b>窗口口径（与旧判据的唯一差异，具名）</b>：旧判据把"本周期累计缺口 ÷ 本周期累计需求"折成"饿满整周期的比例"， 只在**产业关账日**、只对**关账 unit
   * 名下的家户**跑；本方法按<b>当日</b>报（分子分母同窗口 = 那一天）， <b>逐日对全部家户</b>跑。⇒ 缺口的"结算窗口"从"周期"变成"日"，与"每 tick
   * 生死"（Social 的 ppm/tick + 余数累加器） 同一个时间基准；这正是 W3「两段式 tick + 死亡率移交」要的形态。
   *
   * <p>★ <b>为什么读 {@code unmetToday} 而不是"再算一遍"</b>：它就是消费步（{@link #consumeOneHousehold}）
   * 写下的同一笔账（{@code need − eaten}），并且已经在**借粮/还债之后**（本步的位置）：借到粮的人不再算缺口。 本方法只做"按家户取出 +
   * 报出去"，不引入第二个缺口口径。
   *
   * <p>★ <b>日志（AGENTS §一.9）</b>：逐户明细 TRACE（{@code HOUSEHOLD_GAP}）；当日池子/为什么 DEBUG（{@code
   * HOUSEHOLD_GAP_POOL}）；真有缺口时 INFO 一条具名计数（{@code HOUSEHOLD_GAP_REPORTED}）—— "谁因什么缺了多少"。
   * 全部只读，日志失败不影响结算。
   */
  private static void reportHouseholdGaps(
      EconomySession session,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
      Set<HouseholdId> newCycleHouseholds,
      ProductionLedger.Accumulator ledger,
      long day) {
    Objects.requireNonNull(session, "session（报缺口要落进会话 outbox）");
    // ★ ① W2 的读口：没开市 ⇒ ledger.marketReport() == null ⇒ 空表（"那一天根本没有市场这件事"，
    //   与"市场开了但大家都买到了"是两件事，后者是空 Map —— 两者在缺口语义上等价，在归因上不同）。
    MarketReport marketReport = ledger.marketReport();
    Map<HouseholdId, Map<CommodityId, Long>> productionCostGaps =
        marketReport == null ? Map.of() : marketReport.unmetProductionCostByHousehold();
    long householdsEvaluated = 0L;
    long householdsWithRationGap = 0L;
    long householdsWithProductionCostGap = 0L;
    long rationGapTotalMilli = 0L;
    long rationNeedTotalMilli = 0L;
    long productionCostGapTotalMilli = 0L;
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId key = entry.getKey();
      HouseholdEconomy row = entry.getValue();
      householdsEvaluated++;
      Map<CommodityId, Long> costGap = productionCostGaps.getOrDefault(key, Map.of());
      long rationGapMilli =
          unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L); // 当日缺口（毫粮）
      if (rationGapMilli <= 0L && costGap.isEmpty()) {
        continue; // 两条腿都没有 ⇒ 不进 outbox（"没缺口"就是不报，缺省中性）
      }
      long rationNeedMilli = row.naturalNeeds().getOrDefault(GRAIN, 0L); // 当日粮需求（同窗口分母）
      session.recordHouseholdGap(
          new EconomyHouseholdGap(key, row.population(), rationGapMilli, rationNeedMilli, costGap));
      if (rationGapMilli > 0L) {
        householdsWithRationGap++;
        rationGapTotalMilli = Math.addExact(rationGapTotalMilli, rationGapMilli);
      }
      if (!costGap.isEmpty()) {
        householdsWithProductionCostGap++;
        for (long gap : costGap.values()) {
          productionCostGapTotalMilli = Math.addExact(productionCostGapTotalMilli, gap);
        }
      }
      rationNeedTotalMilli = Math.addExact(rationNeedTotalMilli, rationNeedMilli);
      if (EventLog.channel(RAW).isTraceEnabled()) {
        // ★ TRACE：逐户明细（谁、多少、为什么）—— 默认关闭，可单独开（-Dsimos.economy.traceLevel=TRACE）。
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_GAP",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    key.value(),
                    "population",
                    row.population(),
                    "rationGapMilli",
                    rationGapMilli,
                    "rationNeedMilli",
                    rationNeedMilli,
                    "productionCostGapByCommodity",
                    costGap,
                    "marketRan",
                    marketReport != null,
                    "why",
                    rationGapMilli > 0L
                        ? "ration-short-after-all-relief"
                        : "production-cost-short-after-all-relief"));
      }
    }
    // ★ 回写窗口的基线差分要用到"本周期累计"（app 侧读 FlowRow.unmetNeed），这里只做一条池子 DEBUG。
    long cycleCarried = 0L;
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      if (newCycleHouseholds.contains(entry.getKey())) {
        continue; // 新周期第一天的流水从 0 重记 ⇒ 没有"上期累计"可读
      }
      cycleCarried =
          Math.addExact(cycleCarried, entry.getValue().unmetNeed().getOrDefault(GRAIN, 0L));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_GAP_POOL",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "householdsEvaluated",
                  householdsEvaluated,
                  "householdsWithRationGap",
                  householdsWithRationGap,
                  "householdsWithProductionCostGap",
                  householdsWithProductionCostGap,
                  "rationGapTotalMilli",
                  rationGapTotalMilli,
                  "rationNeedTotalMilli",
                  rationNeedTotalMilli,
                  "productionCostGapTotalMilli",
                  productionCostGapTotalMilli,
                  "marketRan",
                  marketReport != null,
                  "cycleGrainUnmetCarriedMilli",
                  cycleCarried,
                  "why",
                  "w3-report-only: economy-no-longer-kills-or-scales-labor"));
    }
    if (householdsWithRationGap > 0L) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_GAP_REPORTED",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "householdsWithRationGap",
                  householdsWithRationGap,
                  "householdsWithProductionCostGap",
                  householdsWithProductionCostGap,
                  "rationGapTotalMilli",
                  rationGapTotalMilli,
                  "rationNeedTotalMilli",
                  rationNeedTotalMilli,
                  "productionCostGapTotalMilli",
                  productionCostGapTotalMilli,
                  "marketRan",
                  marketReport != null,
                  "why",
                  "S11-second-half: ration-gap-goes-to-social"));
    }
  }

  /**
   * ★★ **周期末计息**（v2 spec §7.1 第三处 + §四 周期结算第 6 步；V6 落地）：
   *
   * <pre>
   * interest = principal × ratePerMillePerCycle ÷ 1000      // 向下取整；0 ⇒ 这一条本轮不计
   * principal' = principal + interest                       // ★ **并入本金**（复利；V1 的 dueCycle 语义不变）
   * 债务人本行流水.interestDue += interest                   // 同一笔数，流量口径
   * </pre>
   *
   * <p>★★ **一处 spec 内部张力，这里的取舍是明确的**：v1 spec §3.3 末条说「绝不用'生产成本'或'资产减少'冒充负债—— **债务只能由 借入/赊购 产生**」，而
   * §四 周期结算第 6 步允许"计息（写新应付款或**并入本金**）"。本批取 §四： 计息**并入本金**。理由：§3.3
   * 那条防的是"**凭空**造负债"（拿成本/折旧冒充欠款），而利息是**合同约定的真实义务** —— 它挂在一条**已经由借入产生**的债务上，不是"第二个来源"。若不计息，{@code
   * ratePerMillePerCycle = 20} 就只是一个写进去、谁也不读的数（账面记成"债务"、语义却是**无限量无偿赈济**）。
   *
   * <p>★★ **它不搬运任何粮、不动任何库存**（纯数学）⇒ §6.1 的守恒式不受影响；债务人的"更穷"体现在 ①本金变大、②本行流水的 {@code interestDue}（以及
   * {@code netSurplus}：见 {@code settleOneDayInto} 里那条注释）。 **债权人这一侧本批不记**：它的资产增值体现在债务表里，而 {@code
   * FlowRow.income} 是**粮食**口径 （往里加"利息收入"会让守恒式当场不成立）⇒ 记在债权侧要等 §五 的索取源协议/市场折算（V7+）。
   *
   * <p>★ **偿还先于计息**（R3 续修）：关账日的日序是 借粮 → 偿还 → 报缺口 → 计息（见 {@link #repayDebts}）， 这里只负责把利息并入本金； {@link
   * DebtContract#dueCycle()} 由借款路径滚动写入（读口与退出处置的排序会读它），{@link DebtContract#lastInterestDay()}
   * 在本步真的并入利息时写； E4a 只接线 {@link InterestTiming#AFTER_REPAYMENT_ON_CLOSE}，其它计息时点 fail-closed 具名抛（E4b
   * 接线）。
   *
   * <p>★★ **计息本金取"当日起始"快照，不取当日 lend/repay 之后的本金**（M0.5 守恒式的权威口径：计息日 {@code + ⌊昨 × 率 ÷ 1000⌋}，"昨" =
   * 当日开始时的本金）：当天新借的债当天不计息；当天还掉的那部分本金 **今天照样计息**（"先还后计"的偿还次序不变，只是计息基数不跟着偿还缩水）。计息仍并入**当前**本金 ⇒ 与守恒式
   * {@code 本金_今 = 昨 + 放出 − 偿还 + ⌊昨 × 率 ÷ 1000⌋} 逐值一致。★ 快照由 {@code settleOneDayInto} 在任何 lend/repay
   * 之前建好传入。
   *
   * <p>★★ <b>W8（2026-10-11）：户已绝（债务人行人口 ≤ 0）⇒ 不计息</b>（见 body 里的 {@code
   * DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED}）。判据取社会侧权威（行人口），与"迁移不销账、死亡才销账"那条口径配套： 这里对**任何**成因为 0
   * 的空户都停息（②只处理死亡户绝的本金消除，③兜住迁移归零与历史空户）。
   *
   * @param debts 债务表（就地更新：本金并入利息）
   * @param principalAtDayStart 当日起始的逐债本金快照（当日新借的债不在快照里 ⇒ 今天不计息）
   * @param interest 本日利息的逐行累加器（**只记债务人**那一侧）
   * @param householdEconomies 经济家户行工作表（★ W8：只读它判"债务人户是否已绝"）
   * @param day 本结算日（真的并入利息时写进 {@code lastInterestDay}）
   */
  private static void chargeInterest(
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      LinkedHashMap<HouseholdId, Long> interest,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      long day) {
    for (Map.Entry<DebtContractId, DebtContract> entry : new ArrayList<>(debts.entrySet())) {
      DebtContractId id = entry.getKey();
      Long startPrincipal = principalAtDayStart.get(id);
      if (startPrincipal == null || startPrincipal <= 0L) {
        continue; // 当日新借（不在快照里）或本金本来就是 0 ⇒ 今天不计息
      }
      DebtContract debt = entry.getValue();
      if (DebtContractBook.isSelfHeld(debt)) {
        // ★★★ W5-fix6（D5，2026-10-11）：**自持合同不计息**（同族漏网之一；还债轮与退出清算已在 W5-fix 排除）。
        //   它是"发行额 = 政府负债"的记账事实 ⇒ 未偿额必须恰好等于累计发行额；计息会让政府负债每周期凭空长出一笔
        //   **没有对应新钱**的利息（W5 验收报告 §3-D5 的真读数：500 → 1,624@d360，全是利息）。
        //   ★ 不静默：逐条 DEBUG 具名（谁 / 哪条合同 / 为什么不动它）。
        if (DEBT.isDebugEnabled()) {
          EventLog.channel(DEBT)
              .debug(
                  LogEvent.of(
                      "DEBT_INTEREST_SELF_HELD_SKIPPED",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtContract",
                      id.value(),
                      "debtor",
                      debt.debtor().value(),
                      "unit",
                      debt.unit().key(),
                      "startPrincipal",
                      startPrincipal,
                      "wouldCharge",
                      startPrincipal * debt.terms().interestRatePerMillePerCycle() / 1000L,
                      "outstandingPrincipal",
                      debt.principal(),
                      "why",
                      "self-held-is-the-issuance-bookkeeping-fact-not-interest-bearing"));
        }
        continue;
      }
      if (debt.terms().interestTiming() != InterestTiming.AFTER_REPAYMENT_ON_CLOSE) {
        throw new IllegalStateException(
            "E4a 尚未接线非 legacy 计息时点：合同="
                + id
                + " timing="
                + debt.terms().interestTiming()
                + "（E4b 接线；此处 fail-closed，不静默当成默认档）");
      }
      // ★★★ W8（2026-10-11 用户裁定「穷人的话，死了账一并消除」）：**户已绝（人口 ≤ 0）⇒ 不计息**。
      //   判据取**社会侧权威**：经济家户行的 population 是 app 每日从 Social 展开后注入的当日物化视图
      //   （AGENTS §〇 的 social 权威条款），不是"债务表里有没有对应的 classes 行"这类间接形态判据。
      //   ★ 为什么必须在这里兜底（调查报告 D2）：活路径的户绝核销（applyHouseholdPopulationDeltasInto 的
      //     W8 双向销账）只在"**死亡**把人口打到 0"的那一天触发；而"人口 → 0"里绝大多数是**迁移**（sw 21 例里
      //     19 例），迁移不销账（用户口径）⇒ 那些空户名下的余额若不在这里跳过，就会继续无限复利
      //     （真读数：hh-2_-2-rural-landlord 户绝后 50 天 ×153.93）。
      //   ★ 与"先销账还是先跳过"的次序（W8 实现账本）：两者**不在同一相位** —— ③ 在日结算的周期末计息（step 内），
      //     ② 在 step **之后**的社会生死回写里；经济侧先关账、Social 后判死是 W3 两段式裁定。所以日序上 ③ 必然
      //     先于 ②，而 ③ **不依赖** ②：② 把本金删成 0 之后 ③ 对那条合同本就无事可做（上面的 startPrincipal ≤ 0 已 continue）。
      //     反过来 ③ 也不是 ② 的替代：③ 只"停止增长"，② 才"消除这笔账"。
      //   ★ 行缺席（拿不到该债务人的人口）⇒ **照旧计息**：说不出"已绝"就不许当作已绝（fail-closed 取"不改行为"那一侧）；
      //     非家户端点（政府/单位户）本来就没有经济家户行，行为与改前逐值相同。
      HouseholdEconomy debtorEconomy = householdEconomies.get(debt.debtor());
      if (debtorEconomy != null && debtorEconomy.population() <= 0L) {
        if (DEBT.isDebugEnabled()) {
          EventLog.channel(DEBT)
              .debug(
                  LogEvent.of(
                      "DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtContract",
                      id.value(),
                      "debtor",
                      debt.debtor().value(),
                      "creditor",
                      debt.creditor().value(),
                      "unit",
                      debt.unit().key(),
                      "startPrincipal",
                      startPrincipal,
                      "outstandingPrincipal",
                      debt.principal(),
                      "debtorPopulation",
                      debtorEconomy.population(),
                      "why",
                      "w8:household-extinct-population<=0-no-interest (social-side-authority)"));
        }
        continue;
      }
      long charged = startPrincipal * debt.terms().interestRatePerMillePerCycle() / 1000L;
      if (charged <= 0L) {
        continue; // 本金小到算不出 1 毫粮（或利率 0）⇒ 本轮不记：不写"看起来在记、其实永远是 0"的流水
      }
      // ★★ E4c：利息并入本金也走唯一写口（唯一额外语义：写 lastInterestDay）。
      DebtContractBook.compoundInterest(debts, id, charged, day);
      interest.merge(debt.debtor(), charged, Long::sum);
    }
  }

  /**
   * 两张**逐商品**发生额表相加（{@code first} 可为 null = 无这一份；两份都保序）。
   *
   * <p>→ R3 的用途：① 当日的 {@code consumed} = 现扣投入 + 日耗 + 生产损耗；② 跨日把当天发生额并入本期流水（§十一）。 空表进空表出（{@code 空 +
   * 空 == 空}），故 {@code Map.of()} 的纯形态不会退化成"一个键值为 0 的假键"。
   */
  private static Map<CommodityId, Long> mergeGoods(
      Map<CommodityId, Long> first, Map<CommodityId, Long> second) {
    if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> merged = new LinkedHashMap<>();
    if (first != null) {
      merged.putAll(first);
    }
    if (second != null) {
      for (Map.Entry<CommodityId, Long> entry : second.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return merged;
  }

  /** ★ E4c：逐币种偿还表相加（与 {@link #mergeGoods} 同一条"空表进空表出、同 key 相加"口径）。 */
  private static Map<CurrencyId, Long> mergeMoney(
      Map<CurrencyId, Long> first, Map<CurrencyId, Long> second) {
    return mergeLongMaps(first, second);
  }

  /** ★ E4c：按稳定 unit 串分组的资本化读数相加（键 = {@code DebtUnit.key()}，如 {@code "money:silver"}）。 */
  private static Map<String, Long> mergeNamedQuantities(
      Map<String, Long> first, Map<String, Long> second) {
    return mergeLongMaps(first, second);
  }

  /** 任意键 → long 的发生额表相加（整数加法；两份都保序；空表进空表出）。 */
  private static <K> Map<K, Long> mergeLongMaps(Map<K, Long> first, Map<K, Long> second) {
    if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
      return Map.of();
    }
    LinkedHashMap<K, Long> merged = new LinkedHashMap<>();
    if (first != null) {
      merged.putAll(first);
    }
    if (second != null) {
      for (Map.Entry<K, Long> entry : second.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return merged;
  }

  /** 把一笔发生额记进"逐行 × 逐商品"的累加器（{@code amount == 0} ⇒ 不落键，保持空表的纯形态）。 */
  private static void addGoods(
      Map<HouseholdId, Map<CommodityId, Long>> acc,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    if (amount == 0L) {
      return;
    }
    acc.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(commodity, amount, Long::sum);
  }

  /**
   * ★★ **定点整数分配**（§4 周期结算第 4 步；v2 spec §八.7）：{@code parts[i] = total × weights[i] ÷
   * Σ权重}，残差按**最大余数法** 分派（余数 {@code total × weights[i] mod Σ权重} 大者先得、同余数按下标序）⇒ **Σparts == total**。
   *
   * <p>★★ **分母是 Σ权重**（这与 v1 不同：v1 的分母是恒定的 1000‰，故 {@code Σ权重 < 1000} 时那个缺口会被"人人均摊" —— 按格净产
   * 5,950,000 算，地主实得 2,975 而按权重只该得 429，**实得是应得的 7 倍**，制度分配被摊成了平均分配）。 用 Σ权重 后，各行所得**恰好**与权重成比例（差 ≤ 1
   * 个最小单位），残差只是"取整余数"。
   *
   * <p>★ **与 {@code EconomySeeder.splitProportional} 是同一个函数**（都走 {@link
   * ProportionalSplit#byDenominator}），故 "口径统一"是一条**可执行**的事实而不是注释 —— 跨模块一致性用例逐值对拍（{@code
   * EconomyAllocationConsistencyTest}）。
   *
   * @param total 待分配的总量（毫粮）；不得为负
   * @param weights 各行的分配权重（非负；v1 = 生产资料权重 × 土地占比 + 劳动权重 × 劳动占比，量纲是千分）
   */
  public static long[] allocate(long total, long[] weights) {
    long weightSum = 0L;
    for (long weight : weights) {
      weightSum += weight;
    }
    return ProportionalSplit.byDenominator(total, weights, weightSum);
  }

  // ── 分组与排序 ─────────────────────────────────────────────────────────────────────

  /**
   * 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。
   *
   * <p>★ H4：可见性从 {@code private} 放宽到**包内** —— {@code MarketSettlement} 要问同一个问题（"这一格有哪些家户"），
   * 而它**只能有一个答案**（两处各写一份分组 = 同一个量的第二处拼写点）。
   */
  static Map<String, List<HouseholdId>> rowsByHex(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<String, List<HouseholdId>> byHex = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      HexCoord hex = householdEconomyEntry.getValue().view().hex();
      // ★ S1：格来自行的**视图**（键本身不再带格；{@link IndustryHexKeys#hexKey} 是拼写点）。
      byHex
          .computeIfAbsent(IndustryHexKeys.hexKey(hex.q(), hex.r()), ignored -> new ArrayList<>())
          .add(householdEconomyEntry.getKey());
    }
    LinkedHashMap<String, List<HouseholdId>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<HouseholdId> members = byHex.get(hexKey);
      // ★ 行序 = 阶层（字典序）→ 居住类型（直接读视图；键不再带这两维）。
      //   ★ S3：landless_laborer/artisan/official 也按同一个 value 字典序参与，只用于确定性；不映射回旧四档。
      members.sort(
          Comparator.comparing(
                  (HouseholdId id) -> householdEconomies.get(id).view().stratum().value())
              .thenComparing(id -> householdEconomies.get(id).view().residence()));
      sorted.put(hexKey, members);
    }
    return sorted;
  }

  /**
   * ★★ <b>R1：政府市场授权的生命周期</b>（成交累加 → 耗尽/到期清除）—— 每个世界日**无条件**跑一次。
   *
   * <p>★★ <b>它做什么</b>：
   *
   * <ol>
   *   <li><b>成交累加</b>：把本轮（可能多轮）撮合里该授权的成交毫商品累加进 {@code filledMilli}；
   *   <li><b>耗尽清除</b>：{@code filledMilli ≥ quantityMilli} ⇒ <b>删除该行</b>（"量用完就撤单"）；
   *   <li><b>到期清除</b>：{@code day > expiresOnDay} ⇒ <b>删除该行</b>（不许留永久挂单）。
   * </ol>
   *
   * <p>★ <b>为什么是无条件调用</b>：到期是日历事实 —— 闭市日、没有市场的格、没有对手方，都不能让一条授权永远挂在状态里。
   *
   * <p>★ 每一次清除都记 INFO（谁/商品/方向/量/成交量/为何清除，§一.9 的"新状态写口至少一条具名 INFO"）； 一条都没有 ⇒ 整段
   * no-op（不产生任何状态差异，也不刷日志）。
   *
   * @param fills 本日成交累计（{@code mandateId → 毫商品}；市场未触发 / 无授权 ⇒ 空表）
   */
  private static void applyGovMarketMandateLifecycle(
      EconomySession session, long day, Map<MarketMandateId, Long> fills) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(fills, "fills（没有成交给空表）");
    Map<MarketMandateId, GovernmentMarketMandate> base = session.sheet().govMarketMandatesOrBase();
    boolean anyFill = fills.values().stream().anyMatch(value -> value != null && value > 0L);
    boolean anyExpiry = false;
    for (GovernmentMarketMandate mandate : base.values()) {
      if (mandate.expiredOn(day) || mandate.exhausted()) {
        anyExpiry = true;
        break;
      }
    }
    if (!anyFill && !anyExpiry) {
      return; // 没有成交、也没有要清的 ⇒ 不物化工作副本（无授权世界零拷贝）
    }
    LinkedHashMap<MarketMandateId, GovernmentMarketMandate> mandates =
        session.sheet().govMarketMandates();
    long filledRows = 0L;
    long exhaustedRows = 0L;
    long expiredRows = 0L;
    for (Map.Entry<MarketMandateId, Long> entry : fills.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      GovernmentMarketMandate mandate = mandates.get(entry.getKey());
      if (mandate == null) {
        // 市场报告里出现了状态里没有的授权 ⇒ 契约故障（不静默丢成交量，也不凭空建一行）。
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "mandate",
                    entry.getKey().value(),
                    "reason",
                    "filled-authorization-not-in-state",
                    "filledMilli",
                    entry.getValue()));
        throw new IllegalStateException("政府授权成交找不到状态行（契约故障）: " + entry.getKey().value());
      }
      GovernmentMarketMandate updated = mandate.withFill(entry.getValue());
      mandates.put(entry.getKey(), updated);
      filledRows++;
      if (TRACE.isTraceEnabled()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_FILLED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "mandate",
                    updated.id().value(),
                    "government",
                    updated.government().value(),
                    "side",
                    updated.side().name(),
                    "commodity",
                    updated.commodity().value(),
                    "filledDelta",
                    entry.getValue(),
                    "filledMilli",
                    updated.filledMilli(),
                    "quantityMilli",
                    updated.quantityMilli(),
                    "remainingMilli",
                    updated.remainingMilli()));
      }
    }
    // 清除：耗尽优先判（"量用完了"比"到期了"更能解释这条行为什么消失）。
    for (GovernmentMarketMandate mandate : new ArrayList<>(mandates.values())) {
      String reason = null;
      if (mandate.exhausted()) {
        reason = "exhausted";
        exhaustedRows++;
      } else if (mandate.expiredOn(day)) {
        reason = "expired";
        expiredRows++;
      }
      if (reason == null) {
        continue;
      }
      mandates.remove(mandate.id());
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_CLEARED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "mandate",
                  mandate.id().value(),
                  "government",
                  mandate.government().value(),
                  "side",
                  mandate.side().name(),
                  "commodity",
                  mandate.commodity().value(),
                  "quantityMilli",
                  mandate.quantityMilli(),
                  "filledMilli",
                  mandate.filledMilli(),
                  "expiresOnDay",
                  mandate.expiresOnDay(),
                  "reason",
                  reason));
    }
    if (MANDATE.isDebugEnabled()) {
      EventLog.channel(MANDATE)
          .debug(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_LIFECYCLE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "filledRows",
                  filledRows,
                  "exhaustedCleared",
                  exhaustedRows,
                  "expiredCleared",
                  expiredRows,
                  "remaining",
                  mandates.size()));
    }
  }

  // ── P-T1b：三层税的当日汇总日志（§一.9 的 INFO 档）────────────────────────────────────

  /**
   * ★★ <b>P-T1b：当日分层的税汇总</b>（§一.9：INFO = "这一轮发生了什么"）—— <b>一行 = 一个（层 × 收款政府 × 币种）</b>
   * 的实收金额（毫），外加一行按币分列的总额（= 买方多付的那一部分）。
   *
   * <p>★★ <b>为什么要这样分组</b>：读日志的人要能直接回答三个问题 —— ① 收的是哪一层（出口/进口/区内）； ② <b>进了哪个国库</b>；③
   * 什么币、多少。跨币求和会把这三种钱当一种（I-C10 / N5），所以币种进键。
   *
   * <p>★ <b>缺省不发</b>：没有税（或本轮没有成交）⇒ {@code taxItems} 为空 ⇒ 一行都不发，旧世界的日志逐字不变。
   */
  private static void logTaxCollected(long day, MarketReport report) {
    Map<MarketReport.TaxKey, Long> byLayer = report.taxByLayerGovernmentCurrency();
    if (byLayer.isEmpty()) {
      return;
    }
    for (Map.Entry<MarketReport.TaxKey, Long> entry : byLayer.entrySet()) {
      MarketReport.TaxKey key = entry.getKey();
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "MARKET_TAX_COLLECTED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "layer",
                  key.layer().value(),
                  "government",
                  key.government(),
                  "currency",
                  key.currency().value(),
                  "amountMilli",
                  entry.getValue(),
                  "reason",
                  "three-layer-tax-transferred-to-treasury"));
    }
    for (Map.Entry<CurrencyId, Long> entry : report.taxByCurrency().entrySet()) {
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "MARKET_TAX_COLLECTED_TOTAL",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "currency",
                  entry.getKey().value(),
                  "amountMilli",
                  entry.getValue(),
                  "reason",
                  "buyer-paid-above-goods-price",
                  "items",
                  report.taxItems().size()));
    }
  }

  // ★★★ R2（2026-10-11，控制方裁定）：原 `unitExclusionsMinusGovernmentTreasuries` /
  //   `governmentTreasuryHouseholds` 一对助手**已删除** —— 它们做的"把政府国库户从单位户集合里摘出来"这一步，
  //   现在由**唯一判据** `MarketRound.householdMarketRole` 承担（国库户口径优先于单位户集合判定，更严的一条赢）。
  //   ★ 删掉而不是留着：多一份"谁退出/谁只买"的拼法就是第二个权威（与 R1 删 `mergeMarketExclusions` 同一条理由）。
  //   ★ 组合根注入的 Z7b 集合自本批起**原样照收**（`settleOneDayInto` 的 `effectiveMarketExcludedHouseholds`）。

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────

  // ★★ R1（2026-10-09）：{@code mergeMarketExclusions}（"单位户 ∪ 政府国库户"的有效排除集）**已删除** ——
  //   本批撤销的正是"国库户退出商品市场"那一半。国库户现在的身份是"只按授权下单的市场参与者"（见
  //   GovernmentMarketMandatePlan 与 MarketSettlement 的同名分支），不再是排除集的一员；单位户那一半由组合根
  //   经 marketExcludedHouseholds 照旧传入。★ 删掉它而不是留着：多一份"谁退出商品市场"的拼法就是第二个权威。

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────
  //
  // ★★ H1：商品库存在**会话工作副本**里（|Map<HouseholdId, Map<CommodityId, Long>>|；裁定 K1），行里没有 goods 了。
  //   三个助手是这份副本的**唯一读写点**（口径只有一处）：
  //     · 缺失键 = 该家户没有该商品（同 HouseholdEconomy.goods 原来的口径，见 EconomyDayStepper 的类注）；
  //     · 写 ≤ 0 ⇒ **去掉该键**（保持"空商品表"的纯形态，不落一个值为 0 的假键）；
  //     · 内层表**只读**：换值一律 put 一张新表（绝不在调用方给的表上做增删）⇒ 任何拿到的快照都不会被后续结算改掉。

  /** 某家户在某商品上的余额（没有这个键 ⇒ 0）。 */
  private static long stockOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity) {
    return householdGoods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 粮的余额（{@link #stockOf} 的粮特化 —— 借粮那一段读得最频繁）。 */
  private static long grainOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods, HouseholdId key) {
    return stockOf(householdGoods, key, GRAIN);
  }

  /** ★ E4c：某家户在某商品上的冻结量（没有键 ⇒ 0）—— 偿还预算不得越过冻结。 */
  private static long frozenGoodsOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      HouseholdId key,
      CommodityId commodity) {
    Map<CommodityId, Long> frozen = householdFrozenGoods.get(key);
    return frozen == null ? 0L : frozen.getOrDefault(commodity, 0L);
  }

  /**
   * 把某家户在某商品上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该键）。
   *
   * <p>★ **只动那一个键**：同一个家户的账上同时住着粮、纤维、布、工具…… ⇒ 换一种商品绝不能把别的商品顺手抹掉 （旧版 {@code withGoodsGrain}
   * 只搬粮，多商品下那样写会静默清空其它商品）。
   */
  private static void setStock(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(householdGoods.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      goods.remove(commodity);
    } else {
      goods.put(commodity, amount);
    }
    householdGoods.put(key, goods);
  }

  /** 在某家户的账上**加一笔**（{@code delta} 可为负；走 {@link #setStock} 的同一口径 ⇒ 归零即去键）。 */
  private static void addStock(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setStock(householdGoods, key, commodity, stockOf(householdGoods, key, commodity) + delta);
  }

  // ── 家户**货币**账（会话工作副本）的读写助手（H4）────────────────────────────────────
  //
  // ★★ 形制与上面那三个**逐字同款**（K14：货币副本与商品副本同形、同生命周期、同样不进 EconomyData）：
  //   · 外层键缺失 = 该家户没有账 ⇒ **fail-closed**（requireHouseholdMoney 判死，同 requireHouseholdAccounts）；
  //   · 内层缺失币种 = 该币种余额 0；
  //   · 写 ≤ 0 ⇒ **去掉该币种键**（保持"空钱包"的纯形态）；
  //   · 内层表**只读**：换值一律 put 一张新表。

  /** 某家户在某币种上的余额（没有这个键 ⇒ 0）。 */
  private static long moneyOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency) {
    return householdMoney.getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
  }

  /** ★ E4c：某家户在某币种上的冻结量（没有键 ⇒ 0）—— 货币债偿还预算不得越过冻结。 */
  private static long frozenMoneyOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId key,
      CurrencyId currency) {
    Map<CurrencyId, Long> frozen = householdFrozenMoney.get(key);
    return frozen == null ? 0L : frozen.getOrDefault(currency, 0L);
  }

  /** 把某家户在某币种上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该币种键）。 */
  private static void setMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency,
      long amount) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>(householdMoney.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      wallet.remove(currency);
    } else {
      wallet.put(currency, amount);
    }
    householdMoney.put(key, wallet);
  }

  /** 在某家户的钱包上**加一笔**（{@code delta} 可为负；走 {@link #setMoney} 的同一口径 ⇒ 归零即去键）。 */
  private static void addMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setMoney(householdMoney, key, currency, moneyOf(householdMoney, key, currency) + delta);
  }

  /**
   * ★★ <b>H4 的第一条守卫：货币账必须覆盖每一个"有人口"的家户</b>（与 {@link #requireHouseholdAccounts} 逐字同款的口径与理由）。
   *
   * <p>★★ <b>为什么必须抛而不是"当成 0"</b>：没有账的一家人可花余额被读成 0 ⇒ 它的有效需求是 0 ⇒ 市场买不到粮、
   * 货币工资也付不出去，而账面（缺口、读数）看起来完全正常。★ 0 人口的家户**可以缺席**（它们不消费、不出工）。
   */
  private static void requireHouseholdMoney(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (householdEconomyEntry.getValue().population() <= 0L) {
        continue; // 0 人口：不消费、不出工 ⇒ 允许没有钱包
      }
      if (!householdMoney.containsKey(householdEconomyEntry.getKey())) {
        missing.add(
            householdEconomyEntry.getKey()
                + "（人口 "
                + householdEconomyEntry.getValue().population()
                + "）");
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "家户**货币**账缺失（H4 fail-closed，裁定 K14）：有 "
            + missing.size()
            + " 个「有人口」的家户在会话货币副本里没有键 —— 不许把'没有账'静默当成'余额 0'"
            + "（那会让这一家人的有效需求恒为 0：市场买不到粮、货币工资收不到，而账面上看不出少了谁）。"
            + "app 协调器必须在推进前从 actor 侧载入货币账（创世禀赋在播种时给它）；"
            + "单模块用例请用 EconomyDayStepper 的 householdMoney 参数显式给账。缺失的家户（最多列 8 个）："
            + missing.subList(0, Math.min(8, missing.size())));
  }

  /**
   * ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）。
   *
   * <pre>
   * 逐行：population > 0 且 householdGoods 里没有该键 ⇒ 抛
   *       population == 0            ⇒ 放行（它们不吃饭、不出工 ⇒ 可以没有账）
   * </pre>
   *
   * <p>★★ **为什么必须抛而不是"当成 0"**：库里没有这一家的账时，若继续跑，它当天的消费是 0、投入也是 0 —— 而账面（流水 / 缺口 /
   * 收获）看起来完全正常，只有那一家人**静默地**没吃饭。那正是本仓最反对的"静默付 0"形态 （同 E14 的守卫、{@code EconomyData} 的构造期守卫）。★
   * 例外只有一个：{@code population == 0} 的行本来就不消费（见 {@code settleHexes} 与 {@code drawCycleInputs} 的显式跳过）。
   *
   * <p>★ **消息里给出前几个缺账的家户**（不是全部 —— 真档可能有几百个）：足够定位是哪一批人没被播种。
   */
  private static void requireHouseholdAccounts(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (householdEconomyEntry.getValue().population() <= 0L) {
        continue; // 0 人口：不吃饭、不出工 ⇒ 允许没有账
      }
      if (!householdGoods.containsKey(householdEconomyEntry.getKey())) {
        missing.add(
            householdEconomyEntry.getKey()
                + "（人口 "
                + householdEconomyEntry.getValue().population()
                + "）");
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "家户账缺失（H1 fail-closed，裁定 K1）：有 "
            + missing.size()
            + " 个「有人口」的家户在会话工作副本里没有键 —— 不许把'没有账'静默当成'库存 0'"
            + "（那会让这一家人当天静默地不吃饭）。app 协调器必须在推进前从 actor 侧的 HouseholdInventory 载入家户账；"
            + "单模块用例请用 EconomyDayStepper 的 householdGoods 参数显式给账。缺失的家户（最多列 8 个）："
            + missing.subList(0, Math.min(8, missing.size())));
  }

  /** 换人口与有效劳动（其余字段原样带过）。★ W3 起经济侧**没有**"判死"调用者了（那条路径已删）—— 仍服务迁移等路径。 */
  private static HouseholdEconomy withPopulationAndLabor(
      HouseholdEconomy householdEconomy, long population, long laborMilli) {
    return new HouseholdEconomy(
        householdEconomy.id(),
        householdEconomy.view(),
        population,
        laborMilli,
        householdEconomy.participationPerMille(),
        householdEconomy.money(),
        householdEconomy.naturalNeeds(),
        householdEconomy.effectiveDemand(),
        householdEconomy.cycleNaturalNeedMilli());
  }

  /**
   * ★★ <b>M2.7：把周期累加器重置为"今天这一份"</b>（新周期第一天用）。
   *
   * <p>★ 为什么不是置 0：今天已经吃掉的这一份**属于新周期**（消费步按注入的 {@code naturalNeeds[grain]} 刚累加过）—— 置 0 会把新周期第一天的需要
   * 抹掉，整周期分母因此少一天。调用点必须用"最近结算日写下的 {@code naturalNeeds[grain]}"作为参数（同源，不另算）。
   */
  private static HouseholdEconomy withCycleNaturalNeed(
      HouseholdEconomy householdEconomy, long cycleNaturalNeedMilli) {
    return new HouseholdEconomy(
        householdEconomy.id(),
        householdEconomy.view(),
        householdEconomy.population(),
        householdEconomy.laborMilli(),
        householdEconomy.participationPerMille(),
        householdEconomy.money(),
        householdEconomy.naturalNeeds(),
        householdEconomy.effectiveDemand(),
        cycleNaturalNeedMilli);
  }

  // ★★ R3B.2：旧的 withCycleState(IndustryDefinitionView…) 已删除 ——
  // 周期状态（progress/cycleLabor/cycleInputUsed）
  //   现在属于 ProductionProcess（见 ProductionProcess#withCycleState），IndustryDefinitionView
  // 只留模板、不再每天重建。

  /**
   * ★ <b>日志安全的拒绝理由</b>（照 L2 的 {@code logReason} 形态）：结算内部消息可能回显字段值/载荷片段；日志只保留可读前缀 ——截到第一个 JSON
   * 起始符/换行，避免把载荷原文带进日志。截断只影响日志文本，不影响异常与控制流。
   */
  private static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    if (text.startsWith("payload ")) {
      int colon = text.indexOf(':');
      if (colon >= 0 && colon < cut) {
        cut = colon;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }
}
