package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
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
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
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
import io.mosire.simos.economy.api.production.ProductionEfficiencyModifier;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEfficiencyState;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRecipe;
import io.mosire.simos.map.hex.HexCoord;
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
 *   <li>**缺口**：库存不够 ⇒ 先在同格内借粮（**按可贷余粮降序**放贷 —— 旧"地主 → 富农 → 中农"的阶层白名单已由可观察余粮取代，见 {@link
 *       #lendDeficitsInHex}；从有粮的行的**余粮**划转 —— 余粮 = 库存 − **本周期自需** × {@link
 *       #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000，见 {@link
 *       #lendableOf}，**不是**"消费后的全部库存"，也不是"当日盈余"）；借到的**累加进同一条** {@link DebtContract}（同一 (债务人, 债权人,
 *       unit, terms) 跨周期恒同一条，见 {@link #legacyGrainDebtId}，本金递增）；**借完仍补不上**的部分记入本行流水的 {@code
 *       unmetNeed}（毫粮、逐日累加，供周期末的饿死判据 —— 见 {@link #FAMINE_MORTALITY_PER_MILLE}，默认致命率 0‰）。
 *   <li>**进度**：每个产业 {@code progressDays + 1}。
 *   <li>**劳动投入**：本产业当日实际劳动 = **该产业名下全部 {@code HouseholdLaborCommitment} 的 {@code laborMilli}
 *       之和**（R2 改口径；改前是"Σ(行 {@code laborMilli × participationPerMille / 1000})"，两者在创世逐值相同）—— 累加进
 *       {@link Industry#cycleLaborMilli()}（供收获时算劳动瓶颈）。★ 于是"同一批人的劳动"**只有一处真相**：配额表；而"配额之和 ≤
 *       该批次的可用劳动"是状态的不变量（{@code EconomyData} 构造期判）。
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
 *       classRowsOfCohort}）<b>整个删除</b>；"哪些行属于这个产业"改由**劳动配额表**推（{@link #householdKeysOf}）。
 *   <li>**计息**（v2 spec §7.1 第三处 + §四 周期结算第 6 步；V6 落地）：全部债务按 {@code principal × ratePerMillePerCycle
 *       ÷ 1000} 计**一次**、**并入本金**（纯数学：不搬运粮、**不动任何库存**），同额记入**债务人**本行流水的 {@code interestDue} —— 见
 *       {@link #chargeInterest}。★ **偿还在计息之前**（关账日、所得到账后，D-030 §3.5）：逐条债按未偿共同价值升序、 逐 medium
 *       先全部货币（余额降序）再全部商品（数量降序），任意库存都可折还，估值 = 债务人价目表优先、否则该户市场区 默认价目表（见 {@link #repayDebts} 与 {@link
 *       DebtValuation}）；粮另扣一日口粮保留、全币种余额可用、冻结不越， 缺价 medium 具名跳过不静默付 0；不足部分顺延到下一周期。
 *   <li>**饿死判据**（2026-09-25 用户点名；**默认不致命**）：按本周期累加的 {@code unmetNeed} 折出"饿满整周期"的人口比例，在这一比例里按 {@code
 *       famineMortalityPerMille}（**默认 {@link #FAMINE_MORTALITY_PER_MILLE} = 0‰**）致死；人口减少、有效劳动同比例缩，
 *       死亡数记入 {@link FlowRow#deaths()}。**顺序**：在收获/分配**之后**（本期产出照分给幸存者，死亡不回溯产量），同一次结算内完成。
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
 * Σ各行产能算）的实测。K3 把产能搬到 {@link Industry#capacity()} 之后，**算式形状一字未改**，但 "各行想扣多少"改按人口占比折算 ⇒
 * <b>真档数值会变</b>（K3 已认这个代价，见 {@code HouseholdEconomy} 的注释；★ H3 起"各行想扣多少"这条口径 <b>整块删掉</b>（改前的 {@code
 * rowSharesOf} 已不存在），改由 relation 指名供方）。 ★★ <b>时效标注（2026-09-27，H1）</b>：下面两式的**第一项已换名换物** —— {@code
 * ΔΣRowGoods} 不再存在，同一个位置上现在是 {@code ΔΣActorGoods}（家户账副本 + operator 条目）。⇒
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
   * AssetKind.CATTLE/TOOL/WORKSHOP/MACHINE/SHIP} v1 保持声明但不启用」）：没有牲口就没有饲料口径， 本常量先**存在但取值 0**，V7
   * 参数目录落地后由参数表供给（届时耕牛一起做，这个数才有依据）。 有一条用例把它钉住（{@code
   * EconomySettlementTest.productionLossSplitsIntoFeedAndDepreciation}）。
   */
  public static final int FEED_PER_MILLE = 0;

  /**
   * ★★ **收获时的农具折旧**（千分数）：**30‰（3%）**。
   *
   * <p>★ 来源：v1 的 15%（旧常量 {@code PRODUCTION_CONSUMPTION_PER_MILLE}，原语义里含**种子**）在本版**拆开**
   * ——**留种**移出收获扣减、改在**播种日**以 {@code cycleInputPerUnit} 的 LAND 档现扣（v2 spec §3.4 的"最重要的口径修正"）， 剩下的
   * 3% 即农具折旧。用户 2026-09-25 裁定「现定」的定案数（见计划 2 的「修订与新增」）。
   */
  public static final int DEPRECIATION_PER_MILLE = 30;

  /** 同格借粮的每周期利率（千分数）：20‰；字面量的唯一拼写点在 {@link DebtTerms}（旧公开常量为兼容别名）。 */
  public static final int BORROW_RATE_PER_MILLE_PER_CYCLE =
      DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE;

  /**
   * ★★ <b>E4c：欠租/欠薪资本化所用条款的来源名</b>——{@code "E4c_LEGACY_DEFAULT"}。
   *
   * <p>★★ E4c 的资本化条款明确取 {@link DebtTerms#legacyDefault()}（利率 {@code
   * DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE}‰/周期、关账日偿还后计息、NOT_ALLOWED、期限空）。
   * 这不是"未知条款静默并入默认档"：欠款读数 {@code Arrear} 本身不携带条款，E4c 只有这一条资本化路径； 该来源名随 {@code
   * ProductionLedger.DebtCapitalization.termsSource} 一起进读口，将来出现按规则/合同定条款的
   * 资本化时，必须新开一条带来源的路径，不得改这里悄悄换语义。
   */
  public static final String CAPITALIZATION_TERMS_SOURCE = "E4c_LEGACY_DEFAULT";

  /**
   * ★★ <b>借粮的信用倍数 κ（千分数；E4b 起已迁到 {@link DebtCapacity#CREDIT_F_MULTIPLE_PER_MILLE}）</b>。
   *
   * <p>★★ <b>E4b 起信用线不再是“可观察偿付基础 × κ”：</b>新公式是
   *
   * <pre>
   * F        = max(0, 本周期已实现粮所得 − 本周期累计口粮 − 下一轮必要投入 − 实缴税)
   * headroom = max(0, ⌊κ × F ÷ 1000⌋ + 可自用余粮 + 政策钩子 − 同 unit 既有本金)
   * </pre>
   *
   * <p>本常量保留为<b>兼容别名</b>（旧测试/旧读数的字面量），生产代码只读 {@link DebtCapacity#CREDIT_F_MULTIPLE_PER_MILLE}
   * 这一个拼写点。
   *
   * @deprecated E4b 用 {@link DebtCapacity#CREDIT_F_MULTIPLE_PER_MILLE}；本常量只是它的别名，旧 “可观察偿付基础 ×
   *     倍数”算式已不存在。
   */
  @Deprecated
  public static final int LOAN_INCOME_MULTIPLE_PER_MILLE = DebtCapacity.CREDIT_F_MULTIPLE_PER_MILLE;

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

  /**
   * ★★ <b>债务人最低口粮保留（天；R3 续修）</b>：{@link #repayDebts} 只把"超过本保留额"的粮视作<strong>可用粮</strong>（★ E4b 的信用额度
   * {@link DebtCapacity#headroom()} 走的是另一套口径：F 与可自用余粮，不读本保留额）。
   *
   * <p>★ 为什么是 1 天：分类/借粮都在"关账日"结算，而债务人在本模型中没有可靠的下一周期粮所得（城镇家户尤其如此）—— 保留整整一个周期会 让偿还结构性地恒为 0（正是 R3
   * 续修要修的读数）；一点也不留则会当天见底、把"还债"变成"立刻断粮"。一日最低口粮是<strong>可解释的下界</strong>： 只动员"今天吃完还有余"的那部分粮。
   *
   * <p>★ 它是**制度层参数**，与 {@link #DEBT_REPAYMENT_SHARE_PER_MILLE} 同处置（V7 迁入参数表、GM 可调）。★ 保留额按 {@code
   * HouseholdEconomy.naturalNeeds[GRAIN]}（结算日当天的自然口粮需要）取，不用"人口 × 拍出来的数"另算一份。
   */
  public static final int DEBTOR_SUBSISTENCE_RESERVE_DAYS = 1;

  /**
   * ★★ **放贷方必须留口粮的千分比**（相对**本周期自需**；v2 spec §7.1 第一处，V6 落地）：**默认 1000‰**。
   *
   * <pre>
   * 保留额 = lender.expectedNeedMilli(GRAIN, 该行产业的 cycleDays) × 本常量 ÷ 1000   // 毫粮
   * 可贷额 = max(0, 放贷行库存 − 保留额)
   * </pre>
   *
   * <p>★★ **2026-10-09 Batch 3：保留额来源 = 本户 app 注入的 {@code naturalNeeds[grain]}**（逐户）， <b>不再</b>按
   * {@code population × 人均口粮定额} 现算。
   *
   * <p>★★ **1000‰ 不是"不贷"，是"只贷余粮"**：放贷方先扣下**整周期**的口粮，剩下的才是余粮 —— 地主 250 天储备 − 120 天自需 = 130
   * 天的余粮照样贷得出去。取 {@code 0} 即 V1 的现状（消费后的**全部**库存都能借出 ⇒ 地主一次把 250 天存粮全借出去、次日自己变成缺口行 ⇒ 设计意图"地主最厚 =
   * 同格主要债权人"被反转成"**地主先破产**"）。
   *
   * <p>★ **一个可读的后果**：保留额按**整周期**算，却只在"借贷发生的那一天"被读 ⇒ 周期后半段会**多留** （=
   * 那时已经用不到的那部分），周期末缸里因此可能剩粮；端到端用例按新口径逐值钉住这个数。
   *
   * <p>★ 它是**制度层参数**（spec §4.1：借贷规则由生产关系双方决定），但**参数目录属 V7** ⇒ 与致死率、口粮系数、 播种次序同处置：先做**具名常量**，注释写明"V7
   * 迁入 {@code economy} 切片的参数表、成为 GM 可调、作用域 全局→国家→格/产业"。**不造半套目录**。
   *
   * <p>★★ <b>M1.2 边界：保留策略留在本层，账户层的 {@code frozen} 只表达"已明确的占用"</b> —— 本常量与 {@code lendableOf}
   * 都是<b>只读算式</b>（"保留额不是冻结起来的一笔粮"，见 {@code lendableOf} 的注释）， <b>不许</b>把它折进 {@code
   * HouseholdInventory.frozenBalances}。★ 两者的差别是<b>语义</b>的：这里是"<b>打算</b>留着的下界"（每天都可能变），
   * 冻结是"<b>已经</b>承诺出去的占用"（挂单/交付）——M2 的算式把它们当作**两项**、互不重复地各减一次。
   */
  public static final int LENDER_SUBSISTENCE_RESERVE_PER_MILLE = 1000;

  /**
   * ★★ **饿死判据的致死率默认值**（千分数）：**0‰（默认不致命）**。
   *
   * <p>★★ **为 0 是"先不做饿死人系统"，不是"删掉机制"**（v2 spec §1.3 + 用户 2026-09-25 两句话并读：
   * 「可以先不做什么饿死人系统、青黄不接系统」+「项目代码应当是自由的」）：**缺口照记**（{@link FlowRow#unmetNeed()}，
   * 逐日累计且读口可见），**致死率是一个旋钮** —— 旋钮做成**包内可见的入参**而不是"常量 + {@code if}"： {@code static final int = 0} 配
   * {@code if (常量 != 0)} 会让非 0 那一支成为**编译期死代码**，任何用例都到不了它 （v2 spec §3.2 与 plan2
   * 偏离③点名的同族：**看起来在、其实永远走不到**）。
   *
   * <p>★ **旋钮的入口**：{@link #settle(EconomyData, long, long, int)} 与 {@link
   * #settleOneDay(EconomyData, long, LinkedHashMap, boolean, int)}（包内可见）；公开入口 {@link
   * #settle(EconomyData, long, long)} 恒喂本默认值。 非 0 那条路由 {@code
   * EconomySettlementTest.famineKillsTheStarvationShareOfThoseWhoGoHungryTheWholeCycle}
   * **逐值**钉住（不是死分支）。
   *
   * <p>★ 口径：先把本周期逐日累加的 {@code unmetNeed} 折成 {@code faminePerMille = unmetNeed / 本周期总需求}（封顶
   * 1000‰），再在这一比例的人口里按致死率致死 ⇒ {@code deaths = 人口 × faminePerMille / 1000 × 致死率 / 1000}。
   *
   * <p>★ 改这个数 = 改规则口径（记入 {@code rulesVersion}），不写死进公式。**V7 参数目录（spec §四）落地后**它迁入 {@code economy}
   * 切片的参数表、成为 GM 可调（spec §1.3：致死率是"判断结果"⇒ 必须可调，默认取保守值）。
   */
  public static final int FAMINE_MORTALITY_PER_MILLE = 0;

  /**
   * ★★ <b>P5：死亡按人口比例删债的具名原因</b>（唯一拼写点）：写入 {@link DebtContractBook#forgive} 的 {@code reason}，
   * 供审计/读口把这一族本金下降与偿还（还款）、GM 减免分开。
   *
   * <p>★ 口径与探针一致（探针只记 {@code debtDeleted} 发生额，不记原因）：数值行为 = 逐笔 {@code newPrincipal = ⌊principal ×
   * survivors ÷ population⌋}；本常量只命名"为什么少了"。★ 不改债的计量单位、 不搬粮/钱、不产生利息 —— 它只写合同本金与状态。
   */
  public static final String DEATH_DEBT_WRITE_OFF_REASON = "death:proportionalWriteOff";

  /**
   * ★★ **播种是否先于当日消费扣种**（v2 spec §3.2 的行为预设；用户 2026-09-25 定案：先用常量，默认 {@code true}）。
   *
   * <p>★ **V7 参数目录（spec §四）落地后，它迁入 {@code economy} 切片的参数表并成为 GM 可调**（spec §3.2：
   * "凡行为一律做成预设"，故它**不许**被写死成"代码选一个聪明的"）。届时本常量只作默认值。
   *
   * <p>★ 取 {@code false} = "吃饭优先、种子看运气"：那是 GM 的选择，不是代码该替他做的判断。 无论取真取假，**播种日这个步骤都在**（spec
   * §3.2：参数化不许改变流程形状），只是扣减次序不同。 那条"取假"的路**不是死代码**：它由 {@code
   * EconomySowingTest.drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown} 逐值钉住（直测包内可见的 {@link
   * #settleOneDay(EconomyData, long, LinkedHashMap, boolean)}）。
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
   *                                    缺价 ⇒ 按既有 Market.prices 口径"不交易" ⇒ 本批世界里它只入账、不成交（I-H3 缺省中性）
   * </pre>
   *
   * <p>★ <b>只读</b>：不写状态、不改任何公式、失败不影响结算（{@code EventLog} 的口径）。★ 调用点只在 {@code harvestWorks}
   * 非空时；且**毛产里没有 haul 就一条都不刷** ⇒ 无跑商/无该产出的世界日志面逐字不变。
   *
   * @param day 当日日号（两级日志都必带）
   * @param ledger 当日**收获**这一段的发生额（只读）
   * @param base 当前世界状态（只用来判"这一格有没有给 haul 定价"，以及取产业模板的两个配方读数）
   */
  private static void logHaulServiceOutput(long day, ProductionLedger ledger, EconomyData base) {
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
      return; // 本日没有任何产业产出运输服务 ⇒ 一条都不刷
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
    for (Market market : base.markets().values()) {
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
      return;
    }
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      Long produced = entry.getValue().get(EconomyCommodities.HAUL);
      if (produced == null || produced <= 0L) {
        continue;
      }
      Industry industry = base.industries().get(entry.getKey());
      long haulPerScaleUnit =
          industry == null
              ? 0L
              : industry.recipe().outputPerUnit().getOrDefault(EconomyCommodities.HAUL, 0L);
      long impliedScale =
          haulPerScaleUnit <= 0L
              ? 0L
              : produced / Math.multiplyExact(haulPerScaleUnit, MILLI_PER_GRAIN);
      HexCoord hex = IndustryHexKeys.hexKeyOf(entry.getKey()).map(HexCoord::parse).orElse(null);
      Market market = hex == null ? null : base.markets().get(hex);
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
                  hex == null ? "" : hex.q() + "," + hex.r(),
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
                  priced ? "priced" : "no-haul-price-no-trade"));
    }
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
  private static Set<HexCoord> haulServiceHexes(EconomyData base) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (Map.Entry<HexCoord, Market> entry : base.markets().entrySet()) {
      if (HaulService.pricedAt(entry.getValue())) {
        hexes.add(entry.getKey());
      }
    }
    return Collections.unmodifiableSet(hexes);
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

  /**
   * 结算一个**区间** {@code (fromTick, toTick]}：**从 {@code fromTick + 1} 逐日跑到 {@code toTick}**（2026-09-25
   * §十一 裁定： 一次 {@code AdvanceTime} 可以推 N 天、只落一条 revision，但**结算语义不跳日**）。
   *
   * <p>★ 每一次内部迭代 = 上面 {@link #settleOneDay} 的一天（播种 → 消费 → 缺口/借粮/建债 → {@code progressDays + 1} → 到达
   * {@code cycleDays} 就收获 + 生产消耗（{@link #FEED_PER_MILLE} + {@link #DEPRECIATION_PER_MILLE}）+ 按
   * {@code Split} 分配；计息/到期不做）。**流水跨日累加**：{@code flows} 提到 日循环之外，逐日把当天发生额并入本期流水，循环结束后统一建 {@link
   * FlowRow}（否则"推进 N 天"只显示最后一天）。
   *
   * <p>★★ **等价性（§十一 的判据）**：{@code settle(base, from, from + N)} 的终态 == N 次 {@code settleOneDay}
   * 的终态。关键在**周期末**：天数是**绝对日**，不是"推进次数"——一条 100 天的推进只要跨过第 120 天那个周期末， **照样在那一天收获**（逐日循环里的 {@code
   * day} 就是绝对世界日）。流水同样满足：一次 N 天的累计 == N 次单日逐步并入 {@code base} 已有流水的累计。
   *
   * @param base 结算前的经济状态
   * @param fromTick 区间起点（世界日，左闭）；逐日循环从 {@code fromTick + 1} 起
   * @param toTick 区间终点（世界日，右闭）；{@code day} 绝对日，参与债务 id 去重（{@code debt-<day>-<seq>}）
   * @return 结算后的新状态；{@code meta} 空 ⇒ 原样返回
   * @throws IllegalArgumentException {@code toTick < fromTick + 1}（至少一天）
   */
  public static EconomyData settle(EconomyData base, long fromTick, long toTick) {
    return settle(base, fromTick, toTick, FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★★ **同 {@link #settle(EconomyData, long, long)}，但致死率可注入**（**包内可见**的旋钮，见 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）—— ★★ **并且 fail-closed**（裁定 E7 / 计划 R4；H1 起收紧到"第一天之前"）。
   *
   * <p>★★ <b>为什么本入口必须 fail-closed（H1 起是两层）</b>：
   *
   * <ol>
   *   <li>★★ <b>它没有家户账</b>（H1；裁定 K1）：日结算的消费与投入都从**家户账的会话工作副本**里读，而本入口手里没有它 —— 它只看经济切片，家户库存住在 actor
   *       切片的 {@code HouseholdInventory} 上。⇒ 只要这个世界有 {@code population > 0}
   *       的家户行，本入口就<b>在第一天之前当场抛</b>（把"没有账"当成"库存 0"正是本仓最反对的静默付 0）。 正确的入口是 {@link
   *       EconomyDayStepper}（家户账是**会话状态**：载入 → 逐日 step → 交回）；
   *   <li>产出<b>不再写进阶层行</b>（R5 ②）—— 它变成产权条目（{@code +净产 → operator}）与给家户 actor 的实付条目。 而"把产权条目落到
   *       {@code ActorData.accounts} 上"这件事，<b>本入口也做不到</b>。⇒ 一旦某一天真的关账并产出，那些条目
   *       <b>在账上静默消失</b>，故<b>当场抛</b>。这一层留着守"全零人口的世界照样不许静默丢产出"。
   * </ol>
   *
   * <p>★★ <b>正确的两条路径</b>（消息里一并点出）：
   *
   * <ol>
   *   <li>{@link EconomyDayStepper} —— economy 模块内的**会话入口**：它逐步交回当天的 {@link ProductionLedger}
   *       （调用方自己决定落到哪里）。单模块用例走它；
   *   <li>**app 协调器**（{@code EconomyOwnershipTimeParticipant} / {@code
   *       PopulationEconomyTimeParticipant}） —— 唯一同时看得见 {@code economy} 与 {@code actor}
   *       的地方：家户账由它载入/落回， 产权由它落账。
   * </ol>
   *
   * <p>★ 第二层的判据是"<b>要产出</b>"（{@link ProductionLedger#hasOutput()}），不是"是多日"：全零人口的世界里不跨周期末的区间照旧可用
   * （那些天没有任何产业关账、也没有人要吃饭）。
   *
   * @param famineMortalityPerMille 致死率（千分数；∈ [0, 1000] ⇒ 死亡 ≤ 需求未被满足的那部分人口）
   * @throws IllegalStateException 世界里存在 {@code population > 0} 的家户行（本入口没有家户账）、
   *     或者区间内**有产业关账并产出**（本入口没有产权落账口）—— 两者都当场抛，不静默
   */
  static EconomyData settle(
      EconomyData base, long fromTick, long toTick, int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    if (base.meta().isEmpty()) {
      return base; // 未激活：不做任何公式（§6.6）
    }
    if (toTick < fromTick + 1L) {
      throw new IllegalArgumentException("economy 结算区间至少一天: from=" + fromTick + "，to=" + toTick);
    }
    // ★★ **H1 的第一层 fail-closed：本入口没有家户账**（裁定 K1 / D3-C）。
    //   家户账是**会话状态**（{@link EconomyDayStepper} 的工作副本），不在 EconomyData 里、也不在本入口的入参里 ⇒
    //   "要吃粮的家户"一个都没有时本入口才可能自洽（全零人口的世界：没有人吃饭、没有人出工）。
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        base.classes().entrySet()) {
      if (householdEconomyEntry.getValue().population() > 0L) {
        throw new IllegalStateException(
            "本入口（多日静态入口）没有**家户账** —— 家户的商品库存住在 actor 切片的 HouseholdInventory 上，"
                + "而日结算的消费与投入都要读它（H1/K1：家户账是会话状态）。"
                + "把'没有账'当成'库存 0'是本仓最反对的形态，故当场抛：家户="
                + householdEconomyEntry.getKey()
                + " 人口="
                + householdEconomyEntry.getValue().population()
                + "。**请走 EconomyDayStepper（家户账是会话状态）**："
                + "它的构造器收一份 Map<HouseholdId, Map<CommodityId, Long>> 工作副本（由 app 协调器从 actor 侧载入），"
                + "step(day) 就地更新它、finish() 之后由协调器落回 actor；"
                + "单模块用例同样走它（step 交回当天的 ProductionLedger）");
      }
      // ★ 0 人口的家户：不需要账（它们不吃饭、不出工；见 settleOneDay 的同款口径）。
    }
    // ★ 本期流水与可变工作表都在 revision 级会话里：日循环不再逐日深拷贝，只在末尾构造一次 EconomyData（P1.5）。
    EconomySession session = new EconomySession(base);
    for (long day = fromTick + 1L; day <= toTick; day++) {
      ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
      settleOneDayInto(
          session,
          day,
          AccountSession.empty(),
          MarketTopology.singleHex(base.markets()),
          PLANTING_DRAWS_BEFORE_CONSUMPTION,
          famineMortalityPerMille,
          ledger,
          Map.of());
      if (ledger.toLedger().hasOutput()) {
        throw new IllegalStateException(
            "第 "
                + day
                + " 天有产业关账并产出，但本入口没有**产权落账口**：产出离开 ClassRow 之后必须由同时看得见"
                + " economy 与 actor 的那一处落账（app 协调器），否则它会在账上静默消失。"
                + "**请走 EconomyDayStepper（家户账是会话状态）**：它的 step(day) 交回当天的 ProductionLedger，"
                + "家户账在它的 householdGoods() 工作副本里");
      }
    }
    return session.build();
  }

  /**
   * 结算**一天**（**次序可注入**、致死率取默认值）：日流程 = {@code 播种（周期第一天）→ 消费/同格借粮 → 进度/劳动/周期末收获分配}。
   *
   * <p>★★ **为什么次序是一个参数而不是常量分支**：{@code plantingDrawsFirst == false} 的那条路是"吃饭优先、
   * 种子看运气"这个**预设**的实现（v2 spec §3.2：凡行为一律做成预设）。写成 {@code static final boolean} + {@code if}
   * 会让取假的那一支在编译期成为**死代码**，任何用例都到不了它 —— 那正是 spec §3.2 要防的"看起来在、其实永远走不到"。 故开放为**包内可见**的重载（仓库先例：{@link
   * #allocate}），由 {@code EconomySowingTest.drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown}
   * 逐值测到两种次序。
   *
   * <p>★ 公开入口 {@link #settle} 恒用常量默认值（{@link #PLANTING_DRAWS_BEFORE_CONSUMPTION} 与 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）。
   *
   * @param base 结算前的经济状态（**已激活**；{@link #settle} 已判过 meta）
   * @param day 推进到的世界日（1 tick = 1 天）；参与**口粮的逐日差分**与债务 id 的去重（{@code debt-<day>-<seq>}）
   * @param flows 本期流水的逐日累加器（跨日持有、**就地更新**；由 {@link #settle} 在循环结束后统一带回）
   * @param householdGoods ★★ **家户账的会话工作副本**（H1；裁定 K1）：键 = 家户身份、值 = 商品余额（缺失键 = 没有该商品）。 **就地更新**（与
   *     {@code flows} 同待遇）。★ 某行 {@code population > 0} 而这里没有它的键 ⇒ **当场抛**
   *     （fail-closed：不许把"没有账"静默当成"库存 0"）；{@code population == 0} 的行跳过消费与投入 ⇒ 可以缺席
   * @return 结算后的新状态（{@code flows} 由 {@link #settle} 在循环结束后统一挂上）
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<HouseholdId, FlowRow> flows,
      AccountSession accounts,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger) {
    return settleOneDay(
        base,
        day,
        flows,
        accounts,
        MarketTopology.singleHex(base.markets()),
        plantingDrawsFirst,
        FAMINE_MORTALITY_PER_MILLE,
        ledger);
  }

  /** 同上一支，但致死率可注入（包内旋钮，见 {@link #FAMINE_MORTALITY_PER_MILLE}）。 */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<HouseholdId, FlowRow> flows,
      AccountSession accounts,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger) {
    return settleOneDay(
        base,
        day,
        flows,
        accounts,
        MarketTopology.singleHex(base.markets()),
        plantingDrawsFirst,
        famineMortalityPerMille,
        ledger);
  }

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
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        famineMortalityPerMille,
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
      int famineMortalityPerMille,
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
        famineMortalityPerMille,
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
   * <p>★★ <b>Z7b：{@code marketExcludedHouseholds} 是国库/单位户退出商品市场的组合根入参</b>（单位户 economy 看不见， 由 app 从
   * {@code Unit.households()} 算好；政府国库户本方法再从 {@code base.governments()} 并入，任何调用方都不会漏掉）。
   * 它只影响市场订单/参与者生成，不改账户、冻结、税/预算/转移语义。
   */
  static void settleOneDayInto(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
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
        famineMortalityPerMille,
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
      int famineMortalityPerMille,
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
        famineMortalityPerMille,
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
      int famineMortalityPerMille,
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
        famineMortalityPerMille,
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
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax,
      ProcurementPriorityInput procurementPriority) {
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
    // ★★ R1（2026-10-09，约束设计书 §4.5 G8 / 不变量 I-P6）：**撤销 Z7b 对"国库户"的那一半** ——
    //   有效排除集回到"组合根传入的单位户**减** 已登记政府国库户"（单位户那一半的实质一个字不改：官吏户/军户的
    //   实物供给不得被市场当余量卖掉；被摘出来的只有"是政府国库户"的那些单位户，见下面 C9 那一段）。
    //   ★ 国库户（hh-gov-*）从此**回到商品市场**：它是参与者、能挂单、能被撮合；但它**不生成任何自动订单**
    //     （需求/库存差异一概不产生买卖），能出现的订单全部来自明确授权 —— 见下面 marketRound 的
    //     withGovMandates 与 MarketSettlement 的"只按授权下单"分支。
    //   ★★ 但**组合根注入的单位户集合里本来就含政府国库户**：GOV 单位的 {@code Unit.households()} 把它的国库家户
    //     也列了进去（官吏户 + 国库户；small-world 实测：{@code hh-gov-gov-central} / {@code hh-gov-gov-province}
    //     都在里面）⇒ 若原样照收，本轮撤销就被单位户那一半**悄悄抵消**（两个主政府的国库户仍不进市场，
    //     R1 的"行政家户回到市场"对它们落空）。故这里按**身份**重新分类：
    //       单位户 ∩ 已登记政府国库户  ⇒ 走国库户口径（回市场、只按授权下单）
    //       其余单位户（官吏户/军户…）  ⇒ 照旧排除（这一半一个字不改）
    //   ★ 这么做不改单位那一半的实质：国库户参与后**不生成任何自动订单**（含它的 GOV unit 经营者解析出来的
    //     必要投入/卖单），公家库存不会被自动清仓；FX 与市场信用同样不放行（见 MarketSettlement 的两处守卫）。
    Set<HouseholdId> effectiveMarketExcludedHouseholds =
        unitExclusionsMinusGovernmentTreasuries(marketExcludedHouseholds, base.governments());
    Objects.requireNonNull(effectiveMarketExcludedHouseholds, "marketExcludedHouseholds");
    // ★★ E3：发行主体的权威答案是当前世界状态（governments），不是进程里的旧登记。
    //   日结算开始按 base 重建登记表：旧世界/旧档 governments 为空 ⇒ 清空登记 ⇒ requireIssuerOf 逐字保留旧 fail-closed 行为。
    MoneyIssuance.syncAuthorities(base.governments().values());
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
    if (GovernmentSeigniorage.isCycleStart(base, day)) {
      long minted =
          GovernmentSeigniorage.settleCycleStart(base, session, accounts, day, currentCycle);
      long issued =
          GovernmentDebtIssuance.issueCycleStart(base, session, accounts, day, currentCycle);
      if ((minted > 0L || issued > 0L) && TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "GOV_POLICY",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "minted",
                    minted,
                    "debtIssued",
                    issued,
                    "period",
                    currentCycle));
      }
    }
    // ★★ E3：本次 revision 的发行审计收集器（id 由 transfer id + 币种确定性派生；并行分区也安全）。
    //   ★ 发行腿只在付方余额不足且付方 = 当前政府国库时才会用到；旧路径（无 issuer）不产生任何记录。
    MoneyIssuanceJournal issuanceJournal =
        new MoneyIssuanceJournal(base.governments(), currentCycle);

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, Industry> industries = session.sheet().industries();
    // ★★ R3B.2：生产单元表工作副本（进度/劳动/投入的唯一写点；Industry 只留模板）。
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = session.sheet().units();
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
    // ★★ **R2：劳动配额表**——日结算**读**它（当日劳动的唯一来源），R4 起在**饿死**那一步**按存活比例缩**它
    //   （见 {@link #scaleLaborOfIndustry}："人死了劳动没减"这条旧账的收口）⇒ 需要工作副本。
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    // ★ P2-A A3：成员份额不再是 Economy 状态组件 —— 家户人口组成由调用方作为**只读投影**传入（见 composition）。
    LinkedHashMap<AssetShareId, OwnershipStake> assetShares = session.sheet().assetShares();
    // ★★ S3：经营者状态机的工作副本 —— 与 industries/rows 同一条"日结算就地更新、结束后整体交出"的口径；
    //   状态转移读市场报告与债务/库存的可观察量，不在这里重算生产公式。
    LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    // ★★ **M2.6（自适应价格的写回点）**：市场表的工作副本 —— 固定模式下结算只读它、终态与 {@code base.markets()} 逐值相同；
    //   自适应打开时 {@code MarketSettlement} 返回更新后的价格表，本方法把**这一份**交给终态 ⇒ 改价只经
    //   "状态 → 变更集（markets 是既有 FieldDelta 组件）"这一条路，没有第二处改价。
    LinkedHashMap<HexCoord, Market> markets = session.sheet().markets();
    // ★★ R4-E2b：生产关系表视图 —— 默认直接读 base 的不可变表；只有 E2b 进入执行真的新建 relation 时才物化工作副本
    //   （空表基线因此不产生任何拷贝）。下游所有日结算读取点统一走这个局部量，不再各处写 session.base().relations()。
    Map<ProductionUnitId, ProductionRules> relations = session.sheet().relationsOrBase();

    // ★★ R4-B.3a-perf：日结算的只读派生索引 —— **每天入口构建一次**。它把“unit 可用资产/产能、unit↔家户、
    //   格↔unit/产业、债务人↔债务”这些一天内不变的问题一次算好，串行热路径与并行 worker 都只查表。
    //   ★ 不进 EconomyData/ChangeSet/Codec，不作为第二份状态；OwnershipStake/Industry/unit 本步不改写，故这一份在
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
            session.sheet().productionOrganizations());

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
                base.modes().size(),
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
                  base.modes().size(),
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
    if (!base.demands().isEmpty() && !base.candidates().isEmpty()) {
      EconomyEntrySettlement.Plan entryPlan =
          EconomyEntrySettlement.planEntries(
              base.demands(),
              base.candidates(),
              industries,
              settlementIndex,
              markets,
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
                entryPlan.intents(),
                base.demands(),
                industries,
                units,
                assetShares,
                laborCommitments,
                operatorConditions,
                session.sheet().relations(),
                householdEconomies,
                householdGoods,
                householdMoney,
                composition,
                markets,
                day);
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
                  session.sheet().productionOrganizations());
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
                    "candidate",
                    outcome.candidateId().value(),
                    "version",
                    outcome.version(),
                    "accepted",
                    outcome.accepted(),
                    "modeKey",
                    outcome.modeKey(),
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
    LinkedHashMap<HouseholdId, Long> borrowing = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income = new LinkedHashMap<>();
    // ★★ **R5/T4：生产损耗不再有"逐行累加器"** —— 损耗不是"谁消费了"，是"蒸发了"，它只进 {@link
    //   ProductionLedger#losses()}（守恒式里自成一项，见类注 §6.1 的新式）。留一个"分了损耗但没人收"的中间残留
    //   （旧口径里它是 consumed 的一部分）会让 I4.2 的 ΣLoss 被算两遍。
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Long> deathsToday = new LinkedHashMap<>();
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
    // ★★ **H5：当日的缺口**（"今天这一顿没吃上多少"）—— 它先被记下、**不在吃饭那一步就借**
    //   （借是最后手段：自产/分配 → 市场 → 救济(留位) → 借；见 {@link #lendDeficitsInHex}）。
    LinkedHashMap<HouseholdId, Long> deficitToday = new LinkedHashMap<>();
    // ── 0-entry.4. ★★ E6a 模式变迁执行（理想架构 §4.4；计划 E6a）────────────────────────────────
    //   ★★ 位置：**必须在自动组织阶段之前** —— 变迁写回 EXITING/ACTIVE 组织、同一 unit 的新 modeKey 与新
    //      HouseholdClassMembership；随后 organize 以新归属做幂等检查（新组织已 ACTIVE ⇒ 不重建），retain=1000 的旧
    //      EXITING 组织也不会被重建（organize 对该状态显式跳过）。
    //   ★★ 闸门：`modeTransitions` 为空时连工作副本都不建（空表基线逐值不变）；apply 内部再判一次到期 PENDING。
    //   ★ 只对"base 里真有一条到期 PENDING"才进入：APPLIED/FAILED 的历史变迁不产生任何拷贝（跨 revision 的稳定基线）。
    //   ★ 它只写 enterprises / units / pledges / classStandings / modeTransitions / classShares
    // 六张工作副本。
    boolean dueModeTransition = false;
    for (ModeTransition transition : base.modeTransitions().values()) {
      if (transition.status() == ModeTransition.Status.PENDING
          && transition.effectiveDay() <= day) {
        dueModeTransition = true;
        break;
      }
    }
    if (dueModeTransition) {
      EconomyModeTransitionSettlement.Outcome transitionOutcome =
          EconomyModeTransitionSettlement.apply(
              base,
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
    //      productionOrganizations；不新建 Industry 模板、不写 markets/rows/debts。
    EconomyEnterpriseSettlement.Outcome enterpriseOutcome =
        EconomyEnterpriseSettlement.Outcome.empty();
    if (!base.modes().isEmpty()) {
      enterpriseOutcome =
          EconomyEnterpriseSettlement.organize(
              base,
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
              markets,
              session.sheet().productionOrganizations());
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
                session.sheet().productionOrganizations());
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
      if (base.modes().isEmpty()) {
        // ★ R2：劳动再分配已按 hex 并行（配额键含产业 id ⇒ 跨 hex 无冲突；同一批次跨 hex 的全局协调留给 R3）。
        // ★★ R4-E2b：今天刚进入的 unit 不触发"本周期是第一天"的重排判定（它今天确实是 0，但它不属于既有周期的重排对象）。
        reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      } else {
        // ★★ §4.3.1：劳动分配、库存分配、投入扣减是**同一次遍历**的三个动作 —— 本调用点在投入扣减**之后**
        //   （PLANTING_DRAWS_BEFORE_CONSUMPTION = true）⇒ 套利快照里的库存已是扣完料的余额（I14 由此结构性成立）。
        LaborQueueSettlement.apply(
            session, settlementIndex, day, composition, enteredToday, accounts, arbitrageCollector);
      }
      // ★ 配额被改写 ⇒ 换一份“配额侧”视图，后续（unit 家户归属/市场参与者/人口回写）继续 O(1) 查表。
      settlementIndex = settlementIndex.withLabor(units, householdEconomies, laborCommitments);
    }

    // ── 1~2. 消费（各自吃自己的库存；缺口**先记下不借** —— 借是最后手段，见 4b）──────────────
    //   ★ H4：每条家户行的 cycleDays 提成局部量 —— 它同时喂"放贷余粮"（lendDeficits）与"市场自留"（MarketSettlement），
    //     两处各算一遍就是同一个量的第二处拼写点（算错不会报错，只会让两处口径悄悄漂开）。
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
      if (base.modes().isEmpty()) {
        reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      } else {
        // ★★ P2-B §13.4：每 tick 重算 —— 不在生产周期开始时锁死；投入已扣（本支在消费后），判定有据。
        // ★★ 2026-10-08（阶段 1）：套利活动与生产进同一个排序器；快照 = 本刻（消费后、投入已扣）的可动余额。
        LaborQueueSettlement.apply(
            session, settlementIndex, day, composition, enteredToday, accounts, arbitrageCollector);
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

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 + 饿死惩罚 ────────────────────────────
    boolean anyCycleClosed = false;
    Set<ProductionUnitId> newCycleUnits = new LinkedHashSet<>();
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
    //   ★ 为什么"任一"：家户自己没有周期字段（K2 之后周期仍住在 Industry 上），而它的所得来自它供给的那些产业的收获
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
    // ★★ H5：本日关账的产业（饿死判据与劳动缩放要等救济通道走完 —— 见下面的 4d）。
    List<ClosedUnit> closed = new ArrayList<>();
    // ★★ P10.2：关账 unit 的周期劳动/投入快照（在下面把 unit 周期状态清零**之前**抓；⑦ 利润汇总用）。
    List<EnterpriseProfitBook.CloseFact> closedFacts = new ArrayList<>();
    // ★★ R2：关账产业先收集成工作单元，进度/周期状态按原序落回 industries；收获/关系分账在收集完后
    //   按 hex 并行执行（同一 hex 内的多个产业共用家户账/关系账 ⇒ 同分区串行，见 runHarvestStage）。
    List<HarvestWork> harvestWorks = new ArrayList<>();
    int harvestOrder = 0;
    // ★★ Z2（§6.1）：当日注入的修正集（缺省 1000‰ = 中性；读取的是本 tick 的只读视图）与效率表工作副本。
    //   效率表是结算工作副本（Z1 已在 EconomyStateBuilder 备好）；缺行 = 全 0 余数 + 本周期全 1000‰。
    Map<ProductionUnitId, ProductionEfficiencyModifier> dayProductionModifiers =
        session.productionModifiersView();
    LinkedHashMap<ProductionUnitId, ProductionEfficiencyState> productionEfficiency =
        session.sheet().productionEfficiency();
    // ★★ Z2（§6.2 ⑥）：产品产出数量 GM 覆盖表（只读；缺产业 = 该产业全部回落配方默认值）。
    Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides =
        base.outputQuantityOverrides();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionProcess unit = units.get(id);
      Industry industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      // ★★ **H0：这个 unit 的家户行 = 由劳动配额表推**（{@link #householdKeysOf}：配额 activity == unit id）。
      //   ★ R4-B.3a-perf：入口索引已按 unit 聚好（去重/排序口径与 householdKeysOf 逐值相同）。
      List<HouseholdId> keys = settlementIndex.householdsOf(id);
      // ★★ **当日实际劳动取自该 unit 名下的全部配额**（不再从"本产业各行 laborMilli × participation"独立算）。
      long laborToday = laborByUnit.getOrDefault(id.value(), 0L);
      long cycledLabor = unit.cycleLaborMilli() + laborToday;
      long progressed = unit.progressDays() + 1L;
      // ★★ Z2（§6.1）：逐 tick 累计当日修正 —— m_t 缺省 1000‰；缺行 + 中性 ⇒ 不建行（避免给全部 unit 写零行），
      //   缺行 + 非中性 ⇒ 物化并把此前已流逝的中性 tick 补进累计（见 ProductionEfficiencyBook）。
      ProductionEfficiencyModifier injectedModifier = dayProductionModifiers.get(id);
      long modifierPerMille =
          injectedModifier == null
              ? ProductionEfficiencyBook.NEUTRAL_MODIFIER_PER_MILLE
              : injectedModifier.modifierPerMille();
      ProductionEfficiencyState nextEfficiency =
          ProductionEfficiencyBook.accrueTick(
              productionEfficiency.get(id), modifierPerMille, unit.progressDays());
      if (nextEfficiency == null) {
        productionEfficiency.remove(id);
      } else {
        productionEfficiency.put(id, nextEfficiency);
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "PRODUCTION_EFFICIENCY_TICK",
                    EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                    "day",
                    day,
                    "unit",
                    id.value(),
                    "modifierPerMille",
                    modifierPerMille,
                    "cycleModifierSumPerMille",
                    nextEfficiency == null
                        ? progressed * ProductionEfficiencyBook.NEUTRAL_MODIFIER_PER_MILLE
                        : nextEfficiency.cycleModifierSumPerMille()));
      }
      long nextProgress = progressed;
      long nextCycleLabor = cycledLabor;
      Map<CommodityId, Long> nextInputUsed = unit.cycleInputUsedMilli(); // 非关账日：原样带过
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**：>= 才把合法状态收获掉。
      if (progressed >= industry.cycleDays()) {
        // ── 周期末：产出 → 产权条目 + 关系规则入账（★ 饿死判据**挪到所有救济通道之后**，见下面的 4d）──
        OperatorCondition condition = operatorConditions.get(id);
        long plannedPerMille =
            StressPolicy.plannedScalePerMille(
                condition == null ? OperatorCondition.IndustryStatus.ACTIVE : condition.status());
        // ★★ Z2（§6.2）：周期末求值（① 平均修正 ② 劳动链 ③ 满足率 ④ scaleBase ⑤ 修正乘算），
        //   在此写回效率状态：清零周期和、保留四个余数（全零且无中性注入证据 ⇒ 可移除，见 Book）。
        ProductionEfficiencyBook.HarvestEvaluation efficiency =
            ProductionEfficiencyBook.evaluateHarvest(
                nextEfficiency,
                industry.cycleDays(),
                cycledLabor,
                capacityScaleOf(unit, industry, settlementIndex),
                plannedPerMille,
                industry.recipe().inputPerUnit(),
                unit.cycleInputUsedMilli(),
                industry.recipe().laborPerUnit());
        if (efficiency.nextState() == null) {
          productionEfficiency.remove(id);
        } else {
          productionEfficiency.put(id, efficiency.nextState());
        }
        if (TRACE.isDebugEnabled()) {
          logProductionEfficiencyHarvest(day, id, efficiency);
        }
        harvestWorks.add(
            new HarvestWork(
                unit,
                industry,
                efficiency.scale(),
                outputQuantityOverrides.getOrDefault(industry.id(), Map.of()),
                hexOfIndustry(unit.industry()),
                harvestOrder++));
        // ★★ **H5：关账的 unit 先记下来，饿死判据等救济通道走完再算**。
        closed.add(
            new ClosedUnit(
                id,
                industry.id(),
                keys,
                inputShortfallOf(unit, industry, settlementIndex, operatorConditions.get(id))));
        // ★★ P10.2：周期劳动/投入在 unit.cycleState 清零前抓（clear 的写就在下面几行）。
        closedFacts.add(
            new EnterpriseProfitBook.CloseFact(
                id,
                industry.id(),
                unit.operator(),
                hexOfIndustry(unit.industry()),
                unit.modeKey(),
                cycledLabor,
                unit.cycleInputUsedMilli()));
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextInputUsed = Map.of(); // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的投入瓶颈凭空变大）
        anyCycleClosed = true;
      }
      units.put(id, unit.withCycleState(nextProgress, nextCycleLabor, nextInputUsed));
    }
    // ★★ R2：收获/关系分账按 hex 并行（唯一写口仍是 applyTransfer；转移在分区累加器里铸号，协调器按分区序吸收）。
    // ★★ P2-A §13.3：收获阶段的两份只读派生索引 —— unit → ProductionEnterprise（组织者家户解析）与
    //   旧 CohortKey 视图 → 家户（旧 ToCohort 规则的唯一合法读法）。
    Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess =
        enterprisesByProcess(session.sheet().productionOrganizations());
    Map<CohortKey, HouseholdId> viewIndex = viewToHousehold(householdEconomies);
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
        parallelism);
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
      logHaulServiceOutput(day, harvestLedger, base);
    }

    // ── 3b. ★★ E4c：欠租/欠薪资本化（生产/租金阶段之后）─────────────────────────────────────
    //   ★★ 只对**本日 ledger 的 Arrear 读数**（owed > 0）执行：把"制度规定未付"落成连续合同债权；
    //     **不移动任何商品/货币库存**（欠款本来就是未付），也**不清零 Arrear 读数**（读数仍是制度事实）。
    //   ★★ P2：端点解析改走 {@link DebtPartyResolver}（唯一解析点）：家户 actor 直取；聚合主体先查
    //     E2 生产组织，再按 ESTATE/WORKSHOP 的人口成分回退；多户按人口最大余数拆分。解析不到 ⇒ 具名跳过（不伪造）。
    //   ★ 位置在**借粮/偿还之前**：新增的既有债因此同日进入偿还排序（D-031 起不再进入任何借款额度门）；
    //     但不在当日起始本金快照里 ⇒ 当天不计息（与借粮同口径，见 principalAtDayStart）。
    capitalizeArrears(
        base,
        settlementIndex,
        ledger,
        householdEconomies,
        debts,
        capitalizedArrearsToday,
        day,
        dueCycle);
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
    //   ★ 它读 base.markets()（价格是数据）：缺格的格没有市场 ⇒ 不触发（不造默认价）。
    //   ★★ 买卖**只走唯一的 applier**（applyTransfer）：本步绝不直接改副本 ——
    //     "任何库存变动必有对应转移记录"这条不变量的落点因此仍是一处（见 MarketSettlement 的类注）。
    //   ★★ M2.1 起这一支不再按"本周期缺口/余量"就地配对，而是**主体各自生成订单**（家户 + 经营者，见
    //     {@link MarketSettlement} 的类注）：生活保留 = 5 天撮合间隔 + 30 天安全库存，旧 1000‰ 整周期自留已退休。
    //   ★★ M2 的冻结写者从这里接上：挂单冻结 → 成交/发运/轮末释放（冻结表随后原样带出，净额必然回到基值）。
    //   ★★ **H5 ②：市场排在借粮之前**（自产/分配 → 市场 → 救济(留位) → 借）—— 见下面的 {@link #lendDeficitsInHex}。
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
            base.demands(),
            // ★★ P-T1c：生产路径默认区内市场规则（单区锚格 = markets 规范序第一个 hex；无税费 ⇒ 逐值现状）。
            //   跨市场区/自定义税费由后续批次经 GM 命令面注入同一入口。
            MarketRegulation.defaultsFor(markets),
            // ★★ R1：本入口不预置信用（开市判定后由 withCredit 补）；排除集 = **只有**组合根传入的单位户
            //   （国库户不再并入：它现在是"只按授权下单"的市场参与者）。
            null,
            null,
            effectiveMarketExcludedHouseholds);
    // ★★ M-A1：逐 hex 运力池（派生量，一轮一份、不落状态）—— 成员 = 该格"选了跑商"的家户（主业或副业含 merchant
    //   生产方式的位置，J-A）；运力 = f(劳动投入, 工具可投入量)（见 MerchantCapacity）。
    //   ★ 位置 = 生产/欠租阶段之后、市场装配之前：它必须在撮合时可用，且必须读**当刻**的劳动与商品账（每轮重算、不累积）。
    //   ★ 工具维读会话商品账（tool 商品；V-22：不动 AssetKind.TOOL 产权份额）。
    // ★★ M-A2：报价表（逐轮瞬态）= **跑商家户按其成本与规模自报价**（限价 = 派生承运成本 + 具名固定"上门"附加费，
    //   见 {@link CapacityQuote}）⇒ 买方按最低限价买运力（K-C）。报价每轮现算、跨轮不保留（与 FX 民间簿同形；
    //   运力不可储存不可转卖 ⇒ 运力单也不许跨轮存活）。传 CapacityQuoteBook.empty() 的调用方（夹具 / 纯状态读者）
    //   ⇒ 无报价 ⇒ 逐值退回 M-A1（I-C2 缺省语义中性）。
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
    MerchantCapacityPool carrierPool =
        MerchantCapacityPool.of(
            session.sheet().classMemberships(),
            base.classPositions(),
            householdEconomies,
            householdGoods,
            householdFrozenGoods,
            CapacityQuoteBook.selfQuoted(),
            // ★★ A2（2026-10-10）：**服务成市的格**（本格市场给 haul 定过价；唯一判据 = HaulService.pricedAt）。
            //   这些格的运力预算改成"该户手上的运输服务货"（服务商品账，I-H2）；其余格一个判据都不变（I-H3）。
            //   ★ 逐轮现算（价格是 GM 数据，可随时改）：不落任何状态、不进任何组件。
            haulServiceHexes(base));
    // ★★ G3-leftovers（2026-10-10）：逐户装配读数（DEBUG；事件 MERCHANT_CAPACITY_HOUSEHOLD）。★ 位置与下面那条
    //   CAPACITY_QUOTE_BOOK 同因：池本身不知道世界日（`of` 的入参里没有 tick），而该事件必须带 `day` 才能与同日的
    //   MARKET_* / MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT 逐户按日对齐 ⇒ 由 tick 面的本处发。★ 发射时点与改前
    //   **逐值一致**：仍是"装配完立刻发"（早于市场轮）⇒ 那两栏读数（toolRemainingMilli / runsAffordable）仍是
    //   装配时点的镜像，不是轮末残余（改前它就在这个时点发，只是没有 day）。
    carrierPool.logHouseholdAssembly(day);
    // ★★ M-A2（§一.9：DEBUG = 每阶段池子/汇总）：本轮运力报价表与运力预算的装配读数（带 day —— 它是 tick 面的
    //   装配，落在这里而不是池内，是为了让 TICK 来源的事件都带 day）。分类 logger = market（与池内运力事件同一门面）。
    if (MANDATE.isDebugEnabled()) {
      EventLog.channel(MANDATE)
          .debug(
              LogEvent.of(
                  "CAPACITY_QUOTE_BOOK",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "priced",
                  carrierPool.isPriced(),
                  "quotingHouseholds",
                  carrierPool.householdCount(),
                  "maxAskPerMille",
                  carrierPool.maxAskPerMille(),
                  "getReadySurchargePerMille",
                  CapacityQuote.GET_READY_SURCHARGE_PER_MILLE,
                  "capacityBudgetMilli",
                  carrierPool.totalCapacityMilli()));
    }
    MarketTrigger marketTrigger =
        MarketSettlement.triggerFor(day, anyCycleClosed, markets, marketRound);
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
      marketRound = marketRound.withCredit(dueCycle, debts);
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
      FxRoundInput fxInput =
          FxRoundInput.of(base.governments(), base.moneyIssuances(), base.marketZones());
      marketRound = marketRound.withFx(fxInput);
      logFxWindows(day, base, fxInput);
      // ★★ §16.4 ①（2026-10-10 用户裁定 6）：本轮的**跑商家户**集合 —— 挂单保留"工具至少一趟"的**范围**。
      //   ★ 判据的唯一拼写点是 {@code MerchantIdentity.selectsMerchant}（有效位置 = 主业 ∪ 副业含 {@code merchant.*}）；
      //     这里只把它从**同一份** classMemberships × classPositions 现算一次。
      //   ★★ 范围为什么不是 carrierPool 的成员表：池成员多一道"运力 > 0"的过滤，会漏掉"选了跑商但没有运力"的家户，
      //     而 §16.4 ① 要覆盖**所有**跑商家户（与有没有 trade unit / 有没有运力无关）。
      //   ★ 缺省中性：这一项没有 ⇒ necessary 里不追加任何键 ⇒ 逐值退回改前（I-C2）。
      Set<HouseholdId> merchantHouseholds =
          MerchantIdentity.merchants(session.sheet().classMemberships(), base.classPositions());
      marketRound = marketRound.withMerchantHouseholds(merchantHouseholds);
      // ★★ R1（2026-10-09）：政府市场授权计划 = 本日生效的"明确挂单"（逐轮瞬态，不落盘）。
      //   ★ 它必须**最后**注入：withCredit/withArbitrage/withFx 三处各自逐字段带过它（克隆丢字段是本类踩过的坑），
      //     而这里注入之后不再有别的 withX。
      //   ★★ 名单上**所有**政府的国库户都在里面（哪怕今天没有授权）：它们是"只按授权下单"的家户，
      //     自动买卖单、市场信用放贷、家户外汇单全部不生成。
      //   ★★ 读**会话工作副本**（不是 base）：一次 advance 的多日共用同一个会话，授权在本段内被耗尽/清除必须
      //     当日就对后续各日生效（读 base 会把已清掉的行再挂一遍 —— 探针实测踩到并已修）。
      GovernmentMarketMandatePlan govMandatePlan =
          GovernmentMarketMandatePlan.of(
              session.sheet().govMarketMandatesOrBase(), base.governments(), day);
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
      // ★★ R1 防复发守卫：**本轮注入的授权计划必须真的在 round 上**。withCredit/withArbitrage/withFx 三处各自
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
          MerchantIdentity.pureMerchants(session.sheet().classMemberships(), base.classPositions());
      MarketSettlement.MarketOutcome outcome =
          MarketSettlement.clearOncePerCycle(
              markets,
              marketRound,
              marketTrigger,
              topology,
              parallelism,
              carrierPool,
              pureMerchantHouseholds);
      // ★ L2 只把报告留给 L3 的读数组件（不落盘）；不聚合丢失（见 MarketReport 的类注）。
      ledger.recordMarketReport(outcome.report());
      govMandateFills = outcome.mandateFills();
      MarketReport report = outcome.report();
      long creditMoney = 0L;
      long creditGoods = 0L;
      for (MarketReport.CreditFill credit : report.creditFills()) {
        if (credit.unit() instanceof DebtUnit.Money) {
          creditMoney++;
        } else {
          creditGoods++;
        }
      }
      Map<MarketUnfilledReason, Long> reasonCounts = new TreeMap<>();
      for (MarketReport.Unfilled unfilled : report.unfilled()) {
        reasonCounts.merge(unfilled.reason(), 1L, Long::sum);
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
                  "creditFills",
                  report.creditFills().size(),
                  "creditMoney",
                  creditMoney,
                  "creditGoods",
                  creditGoods,
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
        for (MarketReport.CreditFill credit : report.creditFills()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_CREDIT_FILL",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "hex",
                      credit.hex().q() + "," + credit.hex().r(),
                      "commodity",
                      credit.commodity().value(),
                      "borrower",
                      credit.borrower().id(),
                      "lenderOrSeller",
                      credit.lenderOrSeller().id(),
                      "quantity",
                      credit.quantityMilli(),
                      "unit",
                      credit.unit().key(),
                      "debtId",
                      credit.debtId().value(),
                      "ratePerMille",
                      credit.ratePerMille(),
                      "dueCycle",
                      credit.dueCycle()));
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
                    "creditFills",
                    report.creditFills().size(),
                    "creditMoney",
                    creditMoney,
                    "creditGoods",
                    creditGoods,
                    "regulatedTariffByCurrency",
                    report.regulatedTariffByCurrency(),
                    "freightPaidByCurrency",
                    report.freightPaidByCurrency(),
                    "freightUncollectedByCurrency",
                    report.freightUncollectedByCurrency()));
      }
      // ★★ S3 修复：把本轮的逐卖方证据累加进经营者条件的"本周期累计"字段。一个周期有多轮市场，关账日那轮
      //   很可能已经看不到更早轮里的滞销/被挤出 ⇒ 不在这里累加，状态机的连续计数就永远不涨。
      OperatorSettlement.accumulateMarketEvidence(
          operatorConditions, units, industries, outcome.report());
      // ★★ M2.6：自适应模式把价格表工作副本换成 outcome 交回的新表（默认固定模式下两者逐值相同 ⇒ 无状态变化）。
      //   ★★ 2026-10-07 修：`markets` 是 `session.sheet().markets()` 的**工作副本引用**，不能整个重新绑定 ——
      //     重新绑定只改本地变量，`EconomyStateBuilder` 仍持旧表 ⇒ 自适应价算出来却永远不落盘。
      //     `outcome.markets()` 在固定模式下就是同一实例；只有真的换了实例才 clear+putAll 回工作副本。
      Map<HexCoord, Market> updatedMarkets = outcome.markets();
      if (updatedMarkets != markets) {
        markets.clear();
        markets.putAll(updatedMarkets);
      }
    }

    // ── ★★ R1：政府市场授权的生命周期（成交累加 + 耗尽/到期清除）────────────────────────────────
    //   ★ 无条件调用（不只在开市日）：到期是**日历事实**，闭市/没有市场也不能让一条授权永远挂在表里。
    //   ★ 位置：市场之后（成交已落账）、债务/预算之前 —— 它只动授权表本身，不参与任何货/钱转移。
    applyGovMarketMandateLifecycle(session, day, govMandateFills);

    // ★★ D-030 §3.4/§3.5：本日起所有“债务折价/还款折算”共用同一份家户价目表索引 ——
    //   有本格市场用本格，否则回落该格所在市场区的锚格默认价目表（单区 = 该区默认价）。
    //   ★ 这是 EconomySettlement 里唯一构造 debt lookup 的地方；算式在本类之外（DebtValuation）。
    Map<HouseholdId, Market> marketByHousehold =
        marketIndexByHousehold(householdEconomies, markets, topology);
    DebtCapacityBook.DebtUnitValueLookup debtUnitValueLookup =
        DebtCapacityBook.marketPriceLookup(marketByHousehold);

    // ── 4b. 借粮（★ H5：**最后手段** —— 自产/分配 → 市场 → 救济(留位) → 借）──────────────────
    //   ★★ 它为什么必须晚于市场：关账日集市之后**手上真的还有粮**的家户不该再借（"只在未来有收入时借"）——
    //     改前借粮与消费挤在同一步（并行），市场买到的粮既不能顶当天的饭、也不能减少债务。
    //   ★ 非关账日没有集市 ⇒ 这一步就紧接着"吃饭"那一支（与改前的次序逐值相同）。
    //   ★ 救济是**留位**：本批没有任何救济制度（谁救济、救济多少都是判断 ⇒ 属 GM/参数目录），
    //     故这一步的次序是具名的四档，而第③档今天空着（如实记，不假装）。
    if (!deficitToday.isEmpty()) {
      // ★★ R2：同格借粮按 hex 并行 —— 借贷双方同格（rowsByHex 分组 + requireSupplierHex 同源口径），
      //   债务键含债务人与债权人 ⇒ 跨格不可能撞同一条；每个分区用线程本地债务/行/流水副本，
      //   交出后由协调器按分区序合并（转移仍走唯一写口 applyTransfer，落账走 AccountSession.commit）。
      // ★★ D-031：同格借粮也不再有借款人额度门 —— 缺口户有多少缺口就借多少，直到本格放贷人的
      //   `lendableOf` 余粮被借空为止；债务风险由债权人承担，抵押物不再是前置条件。
      lendDeficitsPartitioned(
          householdEconomies,
          debts,
          accounts,
          consumedGoods,
          borrowing,
          unmetToday,
          deficitToday,
          dueCycle,
          cycleDaysByHousehold,
          householdOfActor,
          ledger,
          day,
          parallelism);
      ProductionLedger borrowed = ledger.toLedger();
      long loanTransfers = 0L;
      long lentTotal = 0L;
      for (Transfer transfer : borrowed.transfers()) {
        if (transfer.reason() == TransferReason.LOAN_PRINCIPAL) {
          loanTransfers++;
          lentTotal += transfer.goods().getOrDefault(GRAIN, 0L);
        }
      }
      if (loanTransfers > 0L) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "DEFICIT_LENDING",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "deficitHouseholds",
                    deficitToday.size(),
                    "loanTransfers",
                    loanTransfers,
                    "lentGrainMilli",
                    lentTotal,
                    "borrowingTotalMilli",
                    traceTotalLongs(borrowing),
                    "debtContracts",
                    debts.size(),
                    "unmetAfter",
                    traceTotalGoods(unmetToday)));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "DEFICIT_LENDING_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "deficitHouseholds",
                    deficitToday.size(),
                    "borrowingTotal",
                    traceTotalLongs(borrowing),
                    "loanTransfersToday",
                    loanTransfers,
                    "debtContracts",
                    debts.size(),
                    "unmetAfter",
                    traceTotalGoods(unmetToday)));
      }
    }

    // ── 4c. 偿还（★ P11.1 / D-023 / D-030：所得到账后、计息前，“有啥付啥”的任意介质偿还）────────────
    //   ★★ 它挂在"今天有产业关账"上（利息／市场的同一处日级事实）：所得是**整周期**结算出来的
    //     （收获与分配在本步之前刚发生）⇒ 还债的时点就是所得到手的那一刻。
    //   ★ 可动用资产 = 全部商品 + **全部币种**余额；粮保留一日口粮、冻结不越；介质序 = 货币降序 → 商品降序；
    //     债序 = 折成共同价值升序（不可定价排最后）；估值 = 家户价目表（可选）→ 该户市场区默认价目表。
    //   ★ 本批没有家户价目表字段 ⇒ {@code householdPrices = null} 走市场默认；外部/政府收款口由
    //     {@link DebtValuation.RepayeeResolver#HOUSEHOLD_ACTORS} 起步（账户在会话/actor 账里可收即可）。
    if (anyCycleClosed) {
      repayDebts(
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
          null,
          ledger,
          issuanceJournal);
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

    // ── 4d. 饿死判据（★ H5：**所有救济通道之后** —— 市场（4）→ 借（4b）→ 还（4c）之后才判）────────────
    //   ★★ 口径与改前**逐值相同**：改前它排在借粮之后（只是那时借粮在收获之前）；本步把它排到市场与借粮之后 ⇒
    //     "借到粮的人不按缺口去死"这条语义一个字没变，而"关账日的集市买到的粮也算救济"这条**新**口径进来了。
    //   ★ 人口减少后劳动按同比例缩（`applyFamine` 缩**行**劳动 + 本步缩该产业名下的**全部配额**与对应批次的供给）。
    for (ClosedUnit closing : closed) {
      long populationBefore = 0L;
      for (HouseholdId key : closing.keys()) {
        populationBefore += householdEconomies.get(key).population();
      }
      for (HouseholdId key : closing.keys()) {
        long carried =
            newCycleUnits.contains(closing.unit()) || flows.get(key) == null
                ? 0L
                : flows.get(key).unmetNeed().getOrDefault(GRAIN, 0L);
        HouseholdEconomy beforeFamineHouseholdEconomy = householdEconomies.get(key);
        long populationBeforeFamine =
            beforeFamineHouseholdEconomy == null ? 0L : beforeFamineHouseholdEconomy.population();
        long famineUnmet = carried + unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L);
        applyFamine(
            householdEconomies,
            deathsToday,
            key,
            householdEconomies.get(key),
            famineUnmet,
            famineMortalityPerMille);
        HouseholdEconomy afterFamineHouseholdEconomy = householdEconomies.get(key);
        long famineDeaths =
            populationBeforeFamine
                - (afterFamineHouseholdEconomy == null
                    ? 0L
                    : afterFamineHouseholdEconomy.population());
        if (famineDeaths > 0L) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "FAMINE_DEATH",
                      EconomyLogSource.ECONOMY_POPULATION,
                      "day",
                      day,
                      "household",
                      key.value(),
                      "unit",
                      closing.unit().value(),
                      "deaths",
                      famineDeaths,
                      "populationBefore",
                      populationBeforeFamine,
                      "populationAfter",
                      afterFamineHouseholdEconomy == null
                          ? 0L
                          : afterFamineHouseholdEconomy.population(),
                      "unmetGrainMilli",
                      famineUnmet));
        }
        // ★ P2-A A3：成员份额已迁 Social —— 饿死只改 HouseholdEconomy.population；Social 侧的家户成员回写由
        //   跨切片协调器负责（本批如实记为缺口，见交付报告）。
      }
      long populationAfter = 0L;
      for (HouseholdId key : closing.keys()) {
        populationAfter += householdEconomies.get(key).population();
      }
      // ★★ **R4：饿死之后劳动按同比例缩 —— 而且这次真的缩到配额上**（R2 如实记下的那条旧账：
      //   `applyFamine` 缩的是**行**劳动，而当日劳动自 R2 起取自**劳动分配表** ⇒ "人死了劳动没减"）。
      if (populationAfter < populationBefore) {
        scaleLaborOfUnit(
            closing.unit(), populationBefore, populationAfter, laborCommitments, settlementIndex);
      }
    }
    // ★★ Z3a/C7：全部关账 unit 的死亡缩放已跑完 —— 政府承诺整额保留后若越预算，这里统一具名 ERROR + fail-closed
    //   （有 day 上下文 ⇒ 来源 TICK、事件带 day；与 Z1b 队列口径同事件名）。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION, day);
    long famineDeathsTotal = traceTotalLongs(deathsToday);
    if (famineDeathsTotal > 0L) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "FAMINE",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "closedUnits",
                  closed.size(),
                  "deaths",
                  famineDeathsTotal,
                  "unmetTotalMilli",
                  traceTotalGoods(unmetToday)));
    }
    if (TRACE.isDebugEnabled() && anyCycleClosed) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "FAMINE_DETAIL",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "closedUnits",
                  closed.size(),
                  "deathsToday",
                  famineDeathsTotal,
                  "unmetTotalMilli",
                  traceTotalGoods(unmetToday)));
    }

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    //   ★ 次序：**偿还先于计息**（还掉的那部分本金不再生息 —— 这是"先还后计"的标准序，也是改后债务曲线下降的一半原因）。
    if (anyCycleClosed) {
      chargeInterest(debts, principalAtDayStart, interestToday, day);
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
              markets,
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
                session.sheet().productionOrganizations());
      }
    }

    // ── 5b.5. ★★ E5b：清算 + 阶层下滑 + hex 危机信号（关账日、退出处置之后、阶层写回之前）────────
    //   ★ 位置理由：债务结算（4c 偿还 / 5 计息 / 5b 退出处置）已经结束 ⇒ 这里只对"此刻本金 > 0"的合同选路；
    //     5b 已结清的合同本金为 0，天然不会被二次处置（去重口径见 EconomyLiquidationSettlement 类注）。
    //   ★ 只在关账日推进（与 5 计息、5c 阶层写回同窗口）；新表全空 = 旧档 ⇒ 整段 no-op，旧路径逐值不变。
    //   ★ F/headroom 用 E4b/D-030 的唯一算法（DebtCapacityBook）；容量按**当刻债务终态**重算，
    //     且与 4b 共用同一份市场价目表 lookup（可定价债务全部计入；任一不可定价仍由借贷口径 fail-closed）。
    if (anyCycleClosed && EconomyLiquidationSettlement.isActive(base)) {
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
              debtUnitValueLookup);
      EconomyLiquidationSettlement.settle(
          session,
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
      long borrowed = borrowing.getOrDefault(key, 0L);
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
      long dayDeaths = deathsToday.getOrDefault(key, 0L);
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
              (acc == null ? 0L : acc.newBorrowing()) + borrowed,
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
            meta.migrationSource());
    // ★ R2：劳动供给表与配额表**原样带过结算的日常部分**（配额属命令层、供给属 social 的人口真值源）；
    //   R4 起它们在**饿死**那一步会被按存活比例缩（上面的工作副本），故这里交出的是**工作副本**。
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
    if (profitCycle != null && anyCycleClosed && !base.modes().isEmpty()) {
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
              markets,
              accounts);
      // ⑧ 迁移计划（真实利润权重 + A 规则 + 目标选择）。
      ModeMigrationPolicy.MigrationPlan migrationPlan =
          ModeMigrationPolicy.plan(
              base,
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
              profitCycle.marketReports());
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
      ModeMigrationSettlement.apply(session, accounts, migrationPlan, base, day, ledger);
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
                traceTotalLongs(deathsToday),
                "unmet",
                traceTotalGoods(unmetToday),
                "borrowedGrainMilli",
                traceTotalLongs(borrowing),
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
                "marketCreditFills",
                dayMarketReport == null ? 0 : dayMarketReport.creditFills().size(),
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
    session.clearProductionModifiers();
  }

  /**
   * ★ 旧签名（测试/单模块调用方）：内部新建一次 {@link EconomySession}，跑完一天后构造一次 {@link EconomyData}。★ 多日推进请用 {@link
   * EconomyDayStepper}（它复用同一个会话，只有 revision 边界构造一次）。
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<HouseholdId, FlowRow> flows,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger) {
    EconomySession session = new EconomySession(base);
    session.flows().clear();
    session.flows().putAll(flows);
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        famineMortalityPerMille,
        ledger,
        Map.of());
    flows.clear();
    flows.putAll(session.flows());
    return session.build();
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
   * ★★ <b>消费 + 同格借贷的第一步：吃自家库存</b>（按 hex 并行）。
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

  /** 周期投入阶段一个分区的产出（意向 + 本地产业写回 + 本地消费累加器 + 本地账本）。 */
  private record InputDrawPartition(
      List<OrderedAccountIntent> intents,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      ProductionLedger.Accumulator ledger) {}

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
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    // ★★ R3B.2：产业表只读（模板；日结算不再改它）。
    Map<IndustryId, Industry> industries = session.sheet().industries();
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
                    partitionLedger);
                return new InputDrawPartition(
                    buffer.drainIntents(), localUnits, consumed, partitionLedger);
              },
              parallelism.poolOrNull());
      List<OrderedAccountIntent> intents = new ArrayList<>();
      for (InputDrawPartition partition : partitions) {
        intents.addAll(partition.intents());
      }
      accounts.commit(intents);
      for (InputDrawPartition partition : partitions) {
        // unit 写回：只覆盖本分区已有的键（键在，插入序不变）；跨 hex 的 unit 副本互不相交。
        for (Map.Entry<ProductionUnitId, ProductionProcess> update : partition.units().entrySet()) {
          if (!units.containsKey(update.getKey())) {
            throw new IllegalStateException("投入阶段写回了一个不存在的 unit 键（分区副本漂开）: " + update.getKey());
          }
          units.put(update.getKey(), update.getValue());
        }
        mergeGoodsInto(consumedGoods, partition.consumed());
        // ★★ M7：协调器按分区序 absorb ⇒ 列表序 = (阶段 → 分区序 → 分区内生成序)；转移 id 带 p<partition> 段，
        //   seq 是分区内序号，与旧串行号的对应关系不保证。同代码态内 1/4/8 线程确定；V 阶段跨代码态比较
        //   transfers/outputAccruals/ruleSettlements 一律"按业务键排序后比较集合"，不得按 id 或列表下标逐条比。
        ledger.absorb(partition.ledger());
      }
    }
    if (!noHex.isEmpty()) {
      // ★ 旧档/手搭状态的产业 id 没有格键：没有位置就没有账户可安全分区 ⇒ 协调器按原路径单线程处理。
      LinkedHashMap<ProductionUnitId, ProductionProcess> local = new LinkedHashMap<>();
      for (ProductionUnitId id : noHex) {
        local.put(id, units.get(id));
      }
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
          ledger);
      for (Map.Entry<ProductionUnitId, ProductionProcess> update : local.entrySet()) {
        units.put(update.getKey(), update.getValue());
      }
    }
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
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    Map<IndustryId, Industry> industries = session.sheet().industries();
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
                  localUnits.put(id, units.get(id));
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
      Industry industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
      HexCoord location,
      int order) {}

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
      EconomyParallelism parallelism) {
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
                      work.unit(),
                      work.industry(),
                      work.scale(),
                      work.outputQuantityOverrides(),
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

  /** 借粮阶段一个分区的产出（意向 + 本地债务/行/读数副本 + 分区账本 + 本分区家户键）。 */
  private record LendingPartition(
      List<OrderedAccountIntent> intents,
      Map<DebtContractId, DebtContract> debts,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Long> borrowing,
      Map<HouseholdId, Map<CommodityId, Long>> unmet,
      ProductionLedger.Accumulator ledger,
      List<HouseholdId> keys) {}

  /**
   * ★★ <b>同格借粮按 hex 并行</b>（{@link #lendDeficitsInHex} 的并行外壳）。
   *
   * <p>借贷双方必须同格（id 只含 debtor/creditor；跨格借粮不在本阶段）；每个分区只碰自己 hex 的债务条 （按 debtor
   * 归属）、行、未满足读数，交出后由协调器按分区序合并。账户/转移仍走意向 + 唯一写口。
   */
  private static void lendDeficitsPartitioned(
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<HouseholdId, Long> borrowing,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<HouseholdId, Long> deficitToday,
      long dueCycle,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day,
      EconomyParallelism parallelism) {
    Map<String, List<HouseholdId>> hexToRows = rowsByHex(householdEconomies);
    TreeMap<String, List<HouseholdId>> active = new TreeMap<>();
    for (Map.Entry<String, List<HouseholdId>> hex : hexToRows.entrySet()) {
      for (HouseholdId key : hex.getValue()) {
        if (deficitToday.containsKey(key)) {
          active.put(hex.getKey(), hex.getValue());
          break;
        }
      }
    }
    if (active.isEmpty()) {
      return;
    }
    AccountSnapshot snapshot = accounts.snapshot();
    PartitionPlan plan =
        PartitionPlan.of(SettlementStage.LENDING, active.keySet(), parallelism.partitionCount());
    List<LendingPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.onHexPartition(
                      snapshot,
                      SettlementStage.LENDING,
                      partition.partitionIndex(),
                      plan.partitionCount());
              BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
              List<HouseholdId> keys = new ArrayList<>();
              LinkedHashMap<HouseholdId, Long> deficit = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> localUnmet = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (HouseholdId key : active.get(hex)) {
                  keys.add(key);
                  if (deficitToday.containsKey(key)) {
                    deficit.put(key, deficitToday.get(key));
                  }
                  Map<CommodityId, Long> recorded = unmetNeed.getOrDefault(key, Map.of());
                  if (!recorded.isEmpty()) {
                    localUnmet.put(key, new LinkedHashMap<>(recorded));
                  }
                }
              }
              // ★ 债务条按 debtor 归属本分区 —— 读的是“快照 + 本分区本地写”的一致性副本。
              // ★★ E4c：复制口也收在 DebtContractBook（本方法不再直接对债务表 put）；worker 的写走 upsert，
              //   协调器用 absorb 落回。
              LinkedHashMap<DebtContractId, DebtContract> localDebts =
                  DebtContractBook.subset(debts, contract -> keys.contains(contract.debtor()));
              LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomyUpdates =
                  new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Long> localBorrowing = new LinkedHashMap<>();
              ProductionLedger.Accumulator partitionLedger =
                  new ProductionLedger.Accumulator(day, partition.partitionIndex());
              for (String hex : partition.canonicalKeys()) {
                List<HouseholdId> hexKeys = new ArrayList<>();
                LinkedHashMap<HouseholdId, Long> hexDeficit = new LinkedHashMap<>();
                for (HouseholdId key : active.get(hex)) {
                  hexKeys.add(key);
                  if (deficit.containsKey(key)) {
                    hexDeficit.put(key, deficit.get(key));
                  }
                }
                if (hexDeficit.isEmpty()) {
                  continue;
                }
                lendDeficitsInHex(
                    hexKeys,
                    householdEconomies,
                    householdEconomyUpdates,
                    localDebts,
                    tables.householdGoods,
                    tables.householdMoney,
                    consumed,
                    localBorrowing,
                    localUnmet,
                    hexDeficit,
                    day,
                    dueCycle,
                    cycleDaysByHousehold,
                    householdOfActor,
                    partitionLedger);
              }
              return new LendingPartition(
                  buffer.drainIntents(),
                  localDebts,
                  householdEconomyUpdates,
                  consumed,
                  localBorrowing,
                  localUnmet,
                  partitionLedger,
                  keys);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (LendingPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (LendingPartition partition : partitions) {
      DebtContractBook.absorb(debts, partition.debts());
      householdEconomies.putAll(partition.householdEconomyUpdates());
      mergeGoodsInto(consumedGoods, partition.consumed());
      for (Map.Entry<HouseholdId, Long> entry : partition.borrowing().entrySet()) {
        borrowing.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      // 未满足读数可能被 trim 到 0 ⇒ 必须按“本分区键的最终值”替换（而不是只累加），否则旧缺口会留在读数里。
      for (HouseholdId key : partition.keys()) {
        Map<CommodityId, Long> updated = partition.unmet().get(key);
        if (updated == null || updated.isEmpty()) {
          unmetNeed.remove(key);
        } else {
          unmetNeed.put(key, new LinkedHashMap<>(updated));
        }
      }
      // ★ M7：同投入/收获阶段 —— 分区序决定列表序与转移 id 的 p 段；跨代码态比较按业务键排序后比较集合。
      ledger.absorb(partition.ledger());
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

  /**
   * ★★ **把一份"逐批次的出生/死亡"回写到经济侧**（R4 的接缝；**纯函数**：{@code base} 一字不改）。
   *
   * <pre>
   * 对每个批次 g（只处理两侧不全为 0 的那些）：
   *   ① 它在经济侧的"人"住在**它供给的那些产业的家户行**里（H0：行 = (格, 居住类型, 阶层)；居住类型取自批次本身，
   *      格取自它供给的那些产业的 id）—— 真档里农村批次供给 农业+家庭纺织（**两者共用同一批农村家户行**）⇒ 只算一次；
   *      城镇批次供给手工业 ⇒ 落城镇家户行。
   *   ② 出生/死亡按**行人口**权重摊到那些家户行上；行人口 ∓、**行的 laborMilli 按存活比例缩**（新生儿不干活）
   *   ③ 该批次名下的**全部配额与劳动供给**按同一个存活比例缩（"人死了劳动没减"的收口，见 scaleLaborOfGroup）
   *   ④ 出生/死亡**逐行落进流水**（{@code FlowRow.births} / {@code FlowRow.deaths}）—— 人口守恒因此逐值可核
   *   ⑤ ★ P5：**死亡按人口比例删债** —— 每个 {@code deathsParts[j] > 0} 的债务人行，把它**作为债务人**的全部活跃合同
   *      逐笔按 {@code floor(本金 × 存活 ÷ 死亡前人口)} 缩减（走唯一写口 {@link DebtContractBook#forgive}；
   *      {@code 存活 == 0} ⇒ 全额减免），只动本金/状态、**不搬任何粮/钱、不产生利息**；删债额记进会话瞬态累加器。
   * </pre>
   *
   * <p>★★ **为什么经济侧必须跟着动**（而不是"人死了只在社会侧少几个人"）：行人口是**分配权重**与劳动缩放的来源； 当日口粮需求已由 app 按 Social
   * 成员逐户展开注入（Batch 3）⇒ 不回写人口/劳动，权重与配额仍会停在旧账上。
   *
   * <p>★★ **为什么配额要按 #③ 缩两次也不同**：{@code applyFamine}（直接按缺口处死的那条路径）缩的是**产业**那一侧， 本步缩的是**批次**那一侧 ——
   * 两条路径各自知道自己死了谁，各自缩自己那份账。两者都落在同一条不变量上 （{@code Σ allocated ≤ available}）。
   *
   * <p>★ **没有劳动配额的批次摊不出去**（{@code industriesOf} 里没有它）：本轮的世界里创世给每个批次都发了配额 （{@code
   * EconomySeeder}），故这条路径只服务"手工搭的、没有配额的状态"——那种状态本来也没有可缩的劳动。
   *
   * @param changes 逐批次的出生/死亡（social 侧的月度结算产物；键 = 批次身份）
   */
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

  public static EconomyData applyPopulationChange(EconomyData base, List<LotChange> changes) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(changes, "changes");
    if (changes.isEmpty()) {
      return base;
    }
    EconomySession session = new EconomySession(base);
    applyPopulationChangeInto(session, changes);
    return session.build();
  }

  /** ★ session 形态（{@link EconomyDayStepper} 走它）：就地更新工作表与流水，不构造中间状态。 */
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
        SettlementIndex.build(
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
   * <p>★★ <b>为什么不像旧路径那样摊批次/缩劳动/删债</b>：调用方（app 日循环）在调用本方法**之前**已经用新 Social 刷新了 {@code composition} /
   * {@code laborMilli} / {@code naturalNeeds}——劳动权威已经是结算后 Social；这里再按人口 比例缩一次会把当日权威缩两遍。人口 delta
   * 只改行人口这一项投影，其它派生量由调用方的刷新与后续 step 承担。
   *
   * <p>★ <b>拒绝语义</b>：null 键/值、0 delta、缺经济行、结果为负都具名拒（ERROR 日志 + 异常）；空表是合法输入 （当天无生死）。★ 汇总只记
   * DEBUG（逐户明细由 Social 侧事件日志承担）。
   */
  static void applyHouseholdPopulationDeltasInto(
      EconomySession session, Map<HouseholdId, Long> deltas) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(deltas, "deltas");
    if (deltas.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
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
    // ② 校验全过后再写：population += delta；laborMilli 原样（调用方已按新 Social 重算）。
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      HouseholdEconomy row = householdEconomies.get(household);
      long nextPopulation = Math.addExact(row.population(), entry.getValue());
      householdEconomies.put(
          household, withPopulationAndLabor(row, nextPopulation, row.laborMilli()));
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
   * @param deaths 本行的死亡数（≤ population；来自 {@link #allocate}）
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

  /**
   * ★★ **某一格的全部产业**（保序：产业表的插入序；无则空表）。
   *
   * <p>★★ **它是"某一格有哪些产业"这个问题的唯一算法**（历史上由 {@code applyPopulationChange} 的批次摊派与已删除的
   * 日压力路径共用；2026-10-09 Batch B 后调用方只剩旧批次回写这条兼容路径）—— 抽成一个方法是因为"**没有配额的批次该按哪一格算**"这个问题 **只能有一个答案**，
   * 两处各写一遍必然漂（S1 spec §十 的原文）。
   *
   * <p>★ 可见性是 {@code public} 而非包内：调用方在 {@code simos-app} 的另一个包里。
   */
  public static List<IndustryId> industriesAt(
      EconomyData base, io.mosire.simos.map.hex.HexCoord at) {
    return industriesAt(industriesByHex(base), at);
  }

  /**
   * 同 {@link #industriesAt(EconomyData, HexCoord)}，但用**预建的索引** —— 热路径（逐日 × 逐批次）用这个，免得每次重建整张表。
   *
   * <p>★ 两个重载**共用同一行取值逻辑**："某一格有哪些产业"的答案只有一个来源。
   */
  public static List<IndustryId> industriesAt(
      Map<String, List<IndustryId>> industriesByHex, io.mosire.simos.map.hex.HexCoord at) {
    return industriesByHex.getOrDefault(IndustryHexKeys.hexKey(at.q(), at.r()), List.of());
  }

  /**
   * ★★ **按格索引全部产业**（{@code q_r → 产业表}，保序：产业表的插入序）。
   *
   * <p>★★ **它是"某一格有哪些产业"这件事的唯一算法** —— {@link #industriesAt} 也从它取， 故两处（以及将来的第三处）不可能给出不同答案。
   *
   * <p>★ **为什么要单独暴露它**：调用方在**逐日 × 逐批次**的热路径上需要它 —— 没有配额的批次（0-14 档 + 新生儿）**每一个**都要
   * 走兜底，而它们的数量随新生批次**逐期累积**（真档实测 ≈ 6,263 个）。在那儿每次全表扫产业会 多出一项 O(批次 × 产业)；**建一次索引**就没有这一项。 ★
   * 2026-10-09 Batch B 删除了它的日压力调用方，本方法保留给旧批次回写等兼容路径。
   *
   * @param base 经济状态
   * @return 格键（{@code q_r}）→ 该格的产业（保序；无产业的格**不出现在表里**）
   */
  public static Map<String, List<IndustryId>> industriesByHex(EconomyData base) {
    Map<String, List<IndustryId>> byHex = new LinkedHashMap<>();
    for (IndustryId id : base.industries().keySet()) {
      IndustryHexKeys.hexKeyOf(id)
          .ifPresent(hex -> byHex.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(id));
    }
    return byHex;
  }

  /**
   * 追加一条流水行的人口变动（{@code births}/{@code deaths}）——**保留**该行本期的其它发生额（不覆盖）。
   *
   * <p>★ 流水行缺席（该行本期还没有任何发生额）⇒ 以一条全零的流水起步：{@code FlowRow} 的其余字段本来就有合法零值， 而"这一行这个月死了人"必须**读得出来**。
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
   * ★★ **把某个产业名下全部劳动配额（及对应批次的劳动供给）按存活比例缩**（R4 对 R2 那条旧账的收口）。
   *
   * <pre>
   * ratio = after ÷ before          // 该产业各阶层行的**人口**之和，饿死前 → 饿死后
   * 每条配额的 laborMilli        × ratio ÷ 1000 → 同比例缩
   * 每条供给的 grossLaborMilli   × ratio ÷ 1000 → 同比例缩（⇒ Σ allocated ≤ available 仍然成立）
   * </pre>
   *
   * <p>★★ **为什么两侧都要缩**：那条不变量是 {@code Σ allocated ≤ available}，而 {@code available} 就是供给的毛额减两项扣除 ——
   * 只缩配额、不缩供给，缩法对了但账对不上；只缩供给、不缩配额，则会造出"配额超过可支配劳动"的**非法状态** （{@code EconomyData} 的构造期守卫当场拒 ⇒
   * 整次推进回滚）。
   *
   * <p>★ **比例取整的方向安全**：{@code Σ floor(a_i × r) ≤ floor(Σ a_i × r) ≤ floor(available × r) =
   * available'} ⇒ 收缩后不变量自动成立，不需要"再夹一次"。
   *
   * <p>★ **它只覆盖"人死了"这一侧**：出生**不放大**配额（新生儿不干活，且"劳动力增长"要走发配额的命令层，不是结算顺手改）。
   *
   * <p>★★ <b>Z3a/C7：{@code GOV_SERVICE} 行整额跳过</b>（政府行政岗位承诺不可缩；v1 spec §10 C7 / spec §17.2）—— 本方法只缩
   * {@code PRODUCTION}；整批缩放完成后由 {@link #requireGovServiceCommitmentsWithinBudgets} 统一 fail-closed
   * 校验政府承诺仍在预算内（越界 ⇒ 具名 {@code LABOR_COMMITMENT_CONTRACT} ERROR）。
   *
   * @param before 饿死前该产业的行人口之和；必须 &gt; 0（为 0 时没有可缩的东西，调用方先挡）
   */
  private static void scaleLaborOfUnit(
      ProductionUnitId unitId,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      SettlementIndex index) {
    if (before <= 0L || after >= before) {
      return;
    }
    // ★ R4-B.3a-perf：按 unit 的配额 id 列表查活表（旧实现每个关账 unit 扫全量配额）。
    for (LaborAllocationId allocationId : index.allocationIdsOfUnit(unitId)) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
      if (laborCommitment == null) {
        continue; // 索引与活表同源；这里只防御中途被移除
      }
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        continue; // ★ Z3a/C7：政府行政岗位承诺不参与死亡比例缩；越预算由批次末尾统一 fail-closed
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
              laborCommitment.kind()));
    }
  }

  /**
   * ★★ **把一个批次在两个产业上的配额按"该批次的存活比例"缩**（R4 的出生/死亡回写路径用；与 {@link #scaleLaborOfIndustry}
   * 同一条口径，只是键换成了批次）。
   *
   * <p>★ 人口真值源在 social ⇒ 本步的**唯一输入**是一份 {@code group → (出生, 死亡)} 的账（见 {@link
   * #applyPopulationChange}）： economy 不需要认识 {@code PopulationGroup}，只需要它的稳定身份。
   *
   * <p>★★ <b>Z3a/C7：{@code GOV_SERVICE} 行整额跳过</b>（同 {@link #scaleLaborOfUnit}）；批次缩放完成后由调用方统一 {@link
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
              laborCommitment.kind()));
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
   * 从<b>关系指名的那一个主体的账</b>上划给该产业，并把实际划到的量**按商品**累加进 {@link Industry#cycleInputUsedMilli()}。
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
   *       <b>该产业名下的家户账代理它</b>（"这个主体的缸" = 它名下那些家户的缸，与"谁供给这个产业"同一条唯一事实 {@link #householdKeysOf}）。★
   *       这<b>不是</b>"按人口猜<b>谁</b>出料"（谁是供方已经由 relation 说了），而是"指名的主体
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
   *       EconomyRealScaleClothTest} 实测：{@code heldByOperator(CRAFT, TOOL)} 由 169,750 掉到
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
   */
  private static void drawCycleInputs(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
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
  }

  /**
   * ★★ <b>一个 unit 本周期的投入计划</b>（H6-lite 的三遍式里"调查"的产物）。
   *
   * <p>★ <b>为什么要有它</b>：H6-lite 的配给要**先看清全部需求方**才谈得上"按需求比例" —— 逐 unit 就地算就地扣的旧写法
   * 天然是"先到先得"。故把"算"与"扣"分成两遍：本类只在第一遍被读写，第二遍改 {@link #available}（争用的商品换成池子） 与 {@link #quota}，第三遍才动账。
   *
   * <p>★ 字段的可变性是**有意的、且被限制在两处**：{@code available} 只被 {@link #rationContestedInputs} 覆盖， {@code
   * quota}/{@code sharedKeys} 只由它填 ⇒ 第三遍是纯读。
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

    /** 产能折出的规模（= {@link ProductionProcessBook#plannedCapacityScaleOf}；**不含投入那一路** —— 那是"可供量"的事）。 */
    private final long capacityScale;

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
        Map<CommodityId, Long> perScale,
        long capacityScale,
        List<HouseholdId> ownKeys,
        Map<CommodityId, Long> available) {
      this.id = id;
      this.unit = unit;
      this.hex = hex;
      this.perScale = perScale;
      this.capacityScale = capacityScale;
      this.ownKeys = ownKeys;
      this.available = available;
    }

    /** 本 unit 在 {@code commodity} 上的取料上限：争用的商品 = 配给额，其余 = 规模折出来的需求。 */
    private long limitOf(CommodityId commodity, long usableScale) {
      Long ration = quota.get(commodity);
      return ration != null ? ration : usableScale * perScale.getOrDefault(commodity, 0L);
    }
  }

  /**
   * ★★ <b>调查：逐 unit 算出它本周期"想开多大、要什么料、能从哪些账取"</b>（H6-lite 第一遍；**只读**）。
   *
   * <pre>
   * available_j = (供方自己的账在副本里 ? 它在 j 上的余额 : 0) + Σ_{k ∈ ownKeys} 库存_k,j
   * </pre>
   *
   * <p>★ <b>谁是"需求方"</b>：周期第一天、配了投入、且**本格有产能**（{@code capacityScale > 0}）的 unit。★ 产能为 0 的 unit
   * **不进池也不参与争用** —— 它一根料都不会要（旧口径在 {@code usableScale <= 0} 那一支被跳过，读数逐值相同）。
   */
  private static List<InputPlan> surveyInputDemands(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, HouseholdId> householdOfActor) {
    List<InputPlan> plans = new ArrayList<>();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionProcess unit = units.get(id);
      Industry industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      if (unit.progressDays() != 0L) {
        continue; // 只有周期的第一天扣投入
      }
      Map<CommodityId, Long> perScale = industry.inputPerUnit();
      if (perScale.isEmpty()) {
        continue; // ★ 空表与"缺键"同义：不扣、不缩规模 ⇒ 未配投入的 unit 行为与 V2 一字不差
      }
      long capacityScale =
          ProductionProcessBook.plannedCapacityScaleOf(
              unit, industry, index, operatorConditions.get(id));
      if (capacityScale <= 0L) {
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
      plans.add(
          new InputPlan(
              id,
              unit,
              // ★ 拿不到格键 ⇒ null（**不抛**：改前这条路径也不需要格，{@code householdKeysOf} 会给空表 ⇒ 逐值同旧），
              //   它因此不属于任何一格、也就不参与"同格争用"。
              IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null),
              perScale,
              capacityScale,
              ownKeys,
              available));
    }
    return plans;
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
      // ★★ 必须写回 units 工作副本：收获（同一次日结算里、稍后跑）读的就是这一份累加器。
      Map<CommodityId, Long> accumulated = new LinkedHashMap<>(unit.cycleInputUsedMilli());
      for (Map.Entry<CommodityId, Long> entry : drawnTotal.entrySet()) {
        accumulated.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      units.put(
          plan.id, unit.withCycleState(unit.progressDays(), unit.cycleLaborMilli(), accumulated));
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
      Industry industry,
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
  private static void requireSupplierHex(HexCoord supplierLocation, Industry industry) {
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

  /**
   * ★★ <b>该产业"本格产能"折出的规模</b>（K3 之后"产能"只有这一处）：{@code min over k ∈ capacityPerUnit:
   * ⌊industry.capacity[k] ÷ capacityPerUnit[k]⌋}。
   *
   * <p>★ 与收获日公式（{@link ProductionEfficiencyBook}）的产能那一路是**同一个算式**（只是那里还要对劳动与投入取 min）—— 于是"一次想扣多少"
   * 与产业"能产多少"用同一把尺。
   *
   * <p>★ M2.1 起 {@link MarketSettlement} 也算经营者的"必要生产投入"（= 本方法 × {@code inputPerUnit}），故它从 {@code
   * private} 放宽到包内可见；算法一字未改。
   */
  static long capacityScaleOf(
      ProductionProcess unit, Industry industry, Map<AssetShareId, OwnershipStake> assetShares) {
    // ★★ R3B.2：产能从**实物总账**派生（{@link ProductionProcessBook} 是唯一拼写点）。
    return ProductionProcessBook.capacityScaleOf(unit, industry, assetShares);
  }

  /** ★★ 索引口径的产能规模（R4-B.3a-perf；算式仍由 {@link ProductionProcessBook} 唯一拼写点给出）。 */
  static long capacityScaleOf(ProductionProcess unit, Industry industry, SettlementIndex index) {
    return ProductionProcessBook.capacityScaleOf(unit, industry, index);
  }

  /**
   * ★★ <b>S3：计划规模</b> = 技术产能规模 × 状态机的计划系数（{@link StressPolicy}）。
   *
   * <p>★ 缩产/停业只影响"本周期的计划"（投入需求、劳动需求、收获规模），<b>不销毁</b> {@code capacity} / {@code OwnershipStake}：
   * 条件缺失（旧档）或状态回到 {@code ACTIVE} ⇒ 系数 1000‰ ⇒ 与旧行为逐值相同。
   */
  static long plannedCapacityScaleOf(
      ProductionProcess unit,
      Industry industry,
      Map<AssetShareId, OwnershipStake> assetShares,
      OperatorCondition condition) {
    return ProductionProcessBook.plannedCapacityScaleOf(unit, industry, assetShares, condition);
  }

  /** ★★ 索引口径的计划规模（R4-B.3a-perf；产能规模查入口索引，计划系数算式不变）。 */
  static long plannedCapacityScaleOf(
      ProductionProcess unit,
      Industry industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
  }

  /**
   * ★★ <b>S3：本周期"投入没凑齐"的可观察事实</b>（在周期状态被清零之前捕获）：按状态机的计划规模系数（缩产/停业）算应投， 与 {@code
   * cycleInputUsedMilli} 比。停业（系数 0）⇒ 不投也不构成"投入不足"（那是主动停，不是失败）。
   */
  static boolean inputShortfallOf(
      ProductionProcess unit,
      Industry industry,
      Map<AssetShareId, OwnershipStake> assetShares,
      OperatorCondition condition) {
    long planned =
        ProductionProcessBook.plannedCapacityScaleOf(unit, industry, assetShares, condition);
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

  /** ★★ 索引口径的“投入没凑齐”判据（R4-B.3a-perf；算式与旧签名逐字相同，只是计划规模查索引）。 */
  static boolean inputShortfallOf(
      ProductionProcess unit,
      Industry industry,
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

  /**
   * ★★ <b>"供给这个产业的家户行"的唯一算法</b>（H0；裁定 K2 + R-N1-A）：
   *
   * <pre>
   * ① 产业所在格 = IndustryHexKeys.hexKeyOf(industryId)      （唯一拼写点；拿不到格键 ⇒ 空表，不猜 (0,0)）
   * ② 居住类型   = ResidenceKind.ofLot(allocation.group())   （批次前缀的唯一拼写点在 ResidenceKind）
   * ③ 家户行     = 从 HouseholdLaborCommitment.household 取"actor 命中该产业"的家户，按稳定 HouseholdId 去重、只留真有行的
   * </pre>
   *
   * <p>★★ <b>它取代了改前的 {@code classKeysOf(rows, industryId)}</b>（按行键里的产业段过滤）：H0 之后**行里没有产业了** （键 = 格
   * + 居住类型 + 阶层）—— 一个家户给两个产业出劳动（农村家户既种地又织布）时它**只有一行**，而"哪些行属于这个产业"
   * 只能由**劳动配额表**回答：出劳动的那批人住哪儿，就是它的家户。
   *
   * <p>★ <b>行不存在 ⇒ 跳过</b>（不是坏数据）：逐组件增量落盘 ⇒ 配额先到、行后到是合法写序（同 {@code EconomyData} 的
   * "表与表之间没有引用完整性约束"）。★ <b>一条配额都没有的产业 ⇒ 空表</b>（没有家户 ⇒ 没有劳动者；收获的产出全留 operator）。
   *
   * <p>★★ <b>S3：本方法不读 {@code HouseholdEconomy.view.stratum}，也不按四阶层枚举</b> —— 归属的唯一事实源是 {@link
   * HouseholdLaborCommitment#household()}（H0 的裁定，S3 写回阶层后仍然成立）。故阶层改成 {@code
   * landless_laborer}/{@code artisan}/{@code official} 后，这里既不会漏行也不会错行。
   *
   * <p>★ 序 = 稳定 {@link HouseholdId#value()} 字典序（纯函数、与配额表插入序无关；不再按 {@code SocialClassId.all()}
   * 的四档顺序假想行集合）。
   */
  static List<HouseholdId> householdKeysOf(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      ProductionUnitId unit,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    // ★★ S1：家户归属的**唯一事实源 = HouseholdLaborCommitment.household**（不再从视角的阶层段反推）。
    //   ★★ R3B.2：归属判据从 {@code actor.id() == industryId} 改成 {@code activity == unit.id()} ——
    //     "这份劳动喂哪条生产活动"由 activity 回答（与结算按 activity 归集劳动同一个源）。
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.activity().equals(unit.value())) {
        households.add(laborCommitment.household());
      }
    }
    List<HouseholdId> keys = new ArrayList<>();
    for (HouseholdId household : households) {
      if (householdEconomies.containsKey(household)) {
        keys.add(household);
      }
    }
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  /** ★★ 索引口径的“供给这个 unit 的家户行”（R4-B.3a-perf；去重/排序在索引构建时已按旧口径完成）。 */
  static List<HouseholdId> householdKeysOf(ProductionUnitId unit, SettlementIndex index) {
    return index.householdsOf(unit);
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

  /**
   * ★★ <b>家户 → 它供给的 unit 集合</b>（从配额表推 —— 与 {@link #householdKeysOf} 是**同一条事实的两个方向**）。
   *
   * <p>用途有两条：① 借粮的"本周期自需"要一个 {@code cycleDays}（见 {@link #cycleDaysByHousehold}）； ② 各行流水的"本期"何时翻篇（见
   * {@code settleOneDay} 的流水循环）。
   *
   * <p>★ <b>只认 activity 命中现存 unit 的配额</b>：家户型 actor 的 id 可以只是消费主体，或 activity 是自由家户劳动词 ⇒ 那时它不进任何
   * unit（只进守恒与读口）。
   */
  private static Map<HouseholdId, Set<ProductionUnitId>> unitsOfHouseholds(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Map<HouseholdId, Set<ProductionUnitId>> byHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      if (!units.containsKey(unitId)
          || !householdEconomies.containsKey(laborCommitment.household())) {
        continue;
      }
      byHousehold
          .computeIfAbsent(laborCommitment.household(), ignored -> new LinkedHashSet<>())
          .add(unitId);
    }
    return byHousehold;
  }

  /**
   * ★★ <b>每条家户行的 {@code cycleDays}</b>（{@link #lendableOf} 的"本周期自需"要用它）：取**它供给的那些产业的最长周期**。
   *
   * <p>★ <b>为什么取 max</b>：保留额是"这一周期自己要吃的口粮"，多产业的家户没有一个自己的周期 ⇒ 取**最长**的那个是保守方向
   * （不会把它这一周期的口粮当余粮贷出去）。真档三个产业的 {@code cycleDays} 都是 120 ⇒ 与改前逐值相同。
   *
   * <p>★ <b>没有配额的家户</b>（供给集合为空）：退回**本格产业的最长周期**（那格至少有一个产业 —— 见 {@code EconomyData} 的
   * 构造期守卫）；连产业都没有的格 ⇒ 记 0（保留额 0，与改前的隐含口径同侧：没有周期就没有"整周期自需"）。
   *
   * @param industriesByHex 格键 → 该格产业 id（日结算由 {@link SettlementIndex#industriesByHex()}
   *     一次给出；读口见公开重载）
   */
  private static Map<HouseholdId, Long> cycleDaysByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, Industry> industries,
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
        Industry industry = unit == null ? null : industries.get(unit.industry());
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
            Industry industry = industries.get(industryId);
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

  /**
   * ★★ <b>读口入口：从当前状态算每条家户行的 {@code cycleDays}</b>（与日结算的私有算法逐值同一份：同一条 {@link #unitsOfHouseholds} 关系
   * + 同一段兜底）。
   *
   * <p>用途：{@code ApiViews} 的 debtCapacity 读数要按**同一份保留额**算“可自用余粮”（ {@link
   * DebtCapacityBook#capacitiesForState}）；若读口另写一套“家户的周期天数”，两个读数会悄悄漂开。
   *
   * <p>★ <b>成本与边界</b>：这是只读派生，按产业表建一次“格 → 产业”分组（O(产业)），不做任何写；没有配额的格照旧记 0。
   */
  public static Map<HouseholdId, Long> cycleDaysByHousehold(EconomyData data) {
    Objects.requireNonNull(data, "data 不得为 null");
    Map<String, List<IndustryId>> industriesByHex = new LinkedHashMap<>();
    for (IndustryId industryId : data.industries().keySet()) {
      IndustryHexKeys.hexKeyOf(industryId)
          .ifPresent(
              hex ->
                  industriesByHex
                      .computeIfAbsent(hex, ignored -> new ArrayList<>())
                      .add(industryId));
    }
    return cycleDaysByHousehold(
        data.classes(),
        data.industries(),
        data.units(),
        unitsOfHouseholds(data.classes(), data.units(), data.allocations()),
        industriesByHex);
  }

  // ── 消费 + 同格借粮 ─────────────────────────────────────────────────────────────────

  /**
   * 每个格一次：先各自吃自己的库存，库存不够的**在同格内借**（**按可贷余粮降序**找放贷人 —— 见 {@link #lendableOf}）；借到的**累加进同一条** {@link
   * DebtContract}（§7.2 的聚合）；**仍补不上的** 记入未满足需求（{@code unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表， **不进放贷行的
   * {@code consumed}** —— 它出去的是"债权"不是"消费"）。故 §6.1 的守恒式在**格/全局**上成立、**逐行不成立**。
   *
   * <p>★★ <b>H2：那一笔"放贷行扣库存、却不进它的 consumed"现在有一条转移记录相伴</b>（{@code from=放贷家户, to=借款家户}，原因 {@link
   * TransferReason#LOAN_PRINCIPAL}）—— 改前那条**刻意的不对称**在账上无从对账（库存少了，
   * 既不是消费、也不是转移），正是"任何库存变动必有对应转移记录"这条不变量要钉的东西。★ 借入方当天吃掉那一笔走 {@link #consumeFromHousehold}（转移 +
   * 消费两条痕，余额净 0 —— 与改前逐值相同）。
   *
   * <p>★★ **E4a 连续债务身份**：id 由 {@code (债务人, 债权人, unit, terms)} 的纯函数 {@link #legacyGrainDebtId} 给出 ⇒
   * 同一对主体**跨周期命中同一条** {@link DebtContract}（本金递增），不同 unit/terms 必然分开；旧“周期在 id
   * 里、新周期开新条”的行为到此结束（这是本阶段的**有意**行为变化，见交付报告）。
   *
   * <p>★★ **当日需求来自 app 注入的物化读模型**（2026-10-09 家户结构修复 Batch 3；V5/§八.8）：
   *
   * <pre>
   * need = row.naturalNeeds[grain]                    // app 逐户按 Social 成员展开后注入（唯一来源）
   * row.naturalNeeds 由 applyNaturalNeedsInto 写      // 结算不再按 population 反推、也不再重写
   * eaten = min(家户账余额, need)；差额进 deficit（借粮/缺口）
   * </pre>
   *
   * <p>★★ **H1：库存在会话工作副本里**（裁定 K1）：日耗从 {@code householdGoods} 读、**就地扣**（改前读写 {@code
   * HouseholdEconomy.goods}）；{@code population == 0} 的家户**跳过消费**（它们不吃饭、不穿衣 —— 需求本来也是 0，这里显式挡一次
   * 免得读一个不存在的账）。
   *
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（放贷行**本周期自需**的输入，见 {@link
   *     #cycleDaysByHousehold}；H0 之前这一项藏在"行 → 产业"的键里，现在行没有产业了）
   * @param day 借入发生日（进 {@code DebtContract.openedDay}；首次建条用）
   * @param dueCycle 借粮的到期周期 = 当前周期 + 1（滚动写进合同；连续余额只保留最新一笔的到期）
   */
  /**
   * ★ <b>本日关账的一个 unit</b>（H5/R3B.2）：它的 id、产业模板 id、它名下的家户行 —— 饿死判据（{@link #applyFamine}）
   * 与死亡后的劳动缩放（{@link #scaleLaborOfUnit}）要等**市场与借粮**走完才跑，故先把这三样收起来。 ★ Batch 3 起饿死判据分母取行上的 {@code
   * cycleNaturalNeedMilli}，不再需要该 unit 的周期天数。
   */
  private record ClosedUnit(
      ProductionUnitId unit, IndustryId industry, List<HouseholdId> keys, boolean inputShortfall) {}

  /**
   * ★★ <b>同格借粮：最后手段</b>（H5 ②；改前的 {@code settleHexes} 的第二半）—— 逐格：缺口行向**本格可贷余粮** （按余粮降序，见 {@link
   * #lendableOf}）借，借到的**累加进同一条** {@link DebtContract}（E4a 连续身份）； **仍补不上的** 记入未满足需求（{@code
   * unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★★ <b>四档次序（判据就是它）</b>：<b>① 自产/分配</b>（{@link #consumeOwnStockPartitioned} 吃自有库存，收获与分配在它之前） →
   * <b>② 市场购买</b>（{@link MarketSettlement}，在关账日、本步之前）→ <b>③ 救济</b>（★ <b>留位</b>：本批没有任何救济制度，
   * 谁救济、救济多少都是判断 ⇒ 属 GM 参数目录，今天这一档空着）→ <b>④ 借</b>（本方法）。
   *
   * <p>★★ <b>本步开头先"再吃一口"</b>：① 之后、本步之前，家户可能**又拿到了粮**（关账日的收获与分配、集市上买到的）—— 手里有粮就不该动信用。故先把 {@code
   * min(现在的库存, 当日缺口)} 吃掉（记进 {@code consumed}、冲减当日缺口读数）， 剩下的才借。★ 非关账日没有集市、也没有收获 ⇒ 这一口恒为 0 ⇒ 与改前逐值相同。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表， **不进放贷行的
   * {@code consumed}** —— 它出去的是"债权"不是"消费"）。故 §6.1 的守恒式在**格/全局**上成立、**逐行不成立**。
   *
   * <p>★★ <b>H2：那一笔"放贷行扣库存、却不进它的 consumed"现在有一条转移记录相伴</b>（{@code from=放贷家户, to=借款家户}，原因 {@link
   * TransferReason#LOAN_PRINCIPAL}）。★ 借入方当天吃掉那一笔走 {@link #consumeFromHousehold}（转移 + 消费两条痕，余额净 0）。
   *
   * <p>★★ <b>E4a 连续债务身份</b>：id 由 {@code (债务人, 债权人, unit, terms)} 的纯函数 {@link #legacyGrainDebtId} 给出
   * ⇒ 同一对主体**跨周期命中同一条** {@link DebtContract}（本金递增），不同 unit/terms 必然分开；旧“周期在 id
   * 里、新周期开新条”的行为到此结束（这是本阶段的**有意**行为变化，见交付报告）。
   *
   * <p>★★ <b>D-031：借款人侧无额度上限</b>：借出量 = {@code min(剩余缺口, 各放贷方实际余粮之和)}；放贷方余粮按 {@link #lendableOf}
   * 逐债权人递减，借空即止。不再读 {@code DebtCapacity}/headroom，也不要求抵押物。债务风险由债权人 承担；坏账留给既有清算/违约/迁移规则。
   *
   * <p>★★ <b>放贷人不再按阶层白名单选</b>（R3 续修；制度选择，理由与边界写明）：旧实现只认 {@code landlord/rich/middle} 三档当前 view，而 S3
   * 阶层写回把真档绝大多数行改成派生阶层后，"有粮可贷"的家户只要不在白名单里就借不出去，信贷集中到 799 个地主。 本版改为<strong>按可观察余粮选人</strong>：凡
   * {@code lendableOf(row, 库存, cycleDays) > 0} 的家户都可放贷（不按阶层名、不按旧档反推）， 并按可贷额降序、同额按 {@link
   * HouseholdId#value()} 升序作确定性 tie-break。★ 边界：这<b>不</b>凭空造粮；它只回答"谁有粮谁能贷"。
   *
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（放贷方**本周期自需**的输入）
   * @param day 借入发生日（进 {@code DebtContract.openedDay}；首次建条用）
   * @param dueCycle 借粮的到期周期 = 当前周期 + 1（滚动写进合同；连续余额只保留最新一笔的到期）
   */
  private static void lendDeficitsInHex(
      List<HouseholdId> keys,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<HouseholdId, Long> borrowing,
      Map<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      Map<HouseholdId, Long> deficit,
      long day,
      long dueCycle,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.TransferMint mint) {
    {
      // ② 放贷序列：按可观察余粮降序（R3 续修；制度选择，理由与边界见方法注释）。
      //   ★★ **可取的不是"全部库存"、也不是"当日盈余"**（V1 的注释与代码相反，V6 一并修）：可取的是
      //     **余粮 = 余额 − 本周期自需 × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000**（{@link
      // #lendableOf}）。
      //   ★ 付款路径**不按人口过滤**（H1 明文）：人口为 0 的家户照样可以是债权人（它有存粮、它借得出）。
      Map<HouseholdId, Long> lendableByLender = new LinkedHashMap<>();
      for (HouseholdId key : keys) {
        long available =
            lendableOf(
                householdEconomies.get(key), grainOf(householdGoods, key), cycleDaysByHousehold);
        if (available > 0L) {
          lendableByLender.put(key, available);
        }
      }
      List<HouseholdId> lenders = new ArrayList<>(lendableByLender.keySet());
      lenders.sort(
          Comparator.<HouseholdId, Long>comparing(lendableByLender::get, Comparator.reverseOrder())
              .thenComparing(HouseholdId::value));
      if (TRACE.isDebugEnabled()) {
        long lendableTotal = 0L;
        for (long available : lendableByLender.values()) {
          lendableTotal += available;
        }
        long debtorsWithGrainStock = 0L;
        for (HouseholdId debtor : deficit.keySet()) {
          if (grainOf(householdGoods, debtor) > 0L) {
            debtorsWithGrainStock++;
          }
        }
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "DEFICIT_POOL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "lendableLenders",
                    lendableByLender.size(),
                    "lendableTotalGrainMilli",
                    lendableTotal,
                    "debtors",
                    deficit.size(),
                    "debtorsWithGrainStock",
                    debtorsWithGrainStock));
      }
      // ③ 逐缺口行（阶层 id 序 → 居住类型）借：借到多少累加多少债；没人有**余粮** ⇒ 剩下的只留作未满足的自然需求。
      List<HouseholdId> debtors = new ArrayList<>(deficit.keySet());
      debtors.sort(
          Comparator.comparing(
                  (HouseholdId k) -> householdEconomies.get(k).view().stratum().value())
              .thenComparing(k -> householdEconomies.get(k).view().residence()));
      for (HouseholdId debtor : debtors) {
        long remaining = deficit.get(debtor);
        // ★★ **先再吃一口**（H5）：① 之后拿到手的粮（关账日的收获/分配、集市上买到的）先顶当天的饭 —— 不动信用。
        long jar = grainOf(householdGoods, debtor);
        long secondMeal = Math.min(jar, remaining);
        if (secondMeal > 0L) {
          consumeFromHousehold(householdGoods, consumedGoods, debtor, GRAIN, secondMeal);
          trimUnmet(unmetNeed, debtor, GRAIN, secondMeal);
          remaining -= secondMeal;
        }
        // ★★ **D-031：借款人侧无额度上限** —— 缺口户按剩余缺口借，直到本轮放贷人余粮全部借空。
        for (HouseholdId lender : lenders) {
          if (remaining <= 0L) {
            break;
          }
          long available = lendableByLender.getOrDefault(lender, 0L);
          if (available <= 0L) {
            continue; // 只剩口粮/已经没有余粮 ⇒ 不贷（V1 是在这里把全部库存贷出去）
          }
          long lent = Math.min(remaining, available);
          // ★ 放贷人的可贷额随本笔转移递减（本轮内只有这里会动它的库存）⇒ 后续债务人看到的余粮与
          //   "每次现算 lendableOf"逐值相同，但不重算。
          lendableByLender.put(lender, available - lent);
          // ★★ **H2：借粮 = 一条转移（债权人 → 债务人），再由唯一的 applier 落到副本上**。
          //   改前只写"放贷行扣库存"这一腿 —— 那条**刻意的不对称**正是"任何库存变动必有对应转移记录"
          //   这条不变量要钉的东西（借入方当天就吃掉，账面上只有消费与债务两条痕，看不出粮从谁那里来）。
          Transfer loan =
              mint.mint(
                  HouseholdActors.of(lender),
                  HouseholdActors.of(debtor),
                  householdEconomies.get(debtor).view().hex(),
                  Map.of(GRAIN, lent),
                  TransferReason.LOAN_PRINCIPAL);
          applyTransfer(householdGoods, householdMoney, householdOfActor, loan);
          // ★★ **借到的粮当日即被吃掉**：紧接在转移之后从借方副本扣掉同一笔（⇒ 借方余额净 0，
          //   与改前逐值相同：改前根本不写借方库存，只记 consumed 与债务）。⇒ 两条痕都在：转移（粮从谁来）
          //   与消费（粮到哪去）。
          consumeFromHousehold(householdGoods, consumedGoods, debtor, GRAIN, lent);
          // ★ 借到的那一笔当日即被吃掉 ⇒ 冲减当日缺口读数（缺口在吃饭那一步已经整笔记进去了 —— 见 consumeOwnStock）。
          trimUnmet(unmetNeed, debtor, GRAIN, lent);
          // ★★ E4a 连续身份：id 是 (债务人, 债权人, unit, terms) 的**纯函数** ⇒ 同一对主体跨周期命中同一条，
          //   本金递增；terms 不同（利率/规则/期限维）必然分开，不允许静默合并。
          // ★★ E4c：唯一写口 —— 新条/续借/再借激活/到期覆盖全部在 DebtContractBook.upsert 里；本方法不再碰债务表。
          // ★ 返回值（新/合并后的 DebtContract）**刻意不接**：拆表后债务行的派生引用由 EconomyData 构造期的
          //   DebtReferenceReconciler 整表重建（见下面一段），本方法拿不到也不需要那条合同的句柄
          //   —— 接了就只是 DLS_DEAD_LOCAL_STORE（SpotBugs）。调用本身（写入副作用）原样保留。
          DebtContractBook.upsert(
              debts,
              debtor,
              lender,
              DebtUnit.commodity(GRAIN),
              DebtTerms.legacyDefault(BORROW_RATE_PER_MILLE_PER_CYCLE),
              lent,
              day,
              OptionalLong.of(dueCycle));
          // ★★ 2026-10-09 选项 A：改前这里把新合同的派生引用补进债务人行（householdEconomyUpdates）；
          //   拆表后引用表由 EconomyData 构造期的 DebtReferenceReconciler 按 debts 工作表整表重建 ⇒ 不必再补。
          // ★ 借到的粮当日吃掉 ⇒ 已在上面（转移之后）计入当日消费 —— 那里是**唯一**写这一笔的地方。
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 本格放贷人的**余粮**已经借空（D-031 起不再有借款人额度门）：不造粮、不造债。
        // ★★ **H5：这里不再写 unmetNeed** —— 缺口在吃饭那一步（{@link #consumeOwnStockPartitioned}）已经整笔记进去了，
        //   本步每借到一笔/每吃一口自家粮就 {@link #trimUnmet} 冲减一笔 ⇒ 走到这里剩下的那些**已经**留在读数里
        //   （终值 = 改前那个"借完还剩多少"的残差）。★ 少了这条"不写"的说明，后来者会以为漏了一笔，
        //   然后在两处各记一遍 —— 读数翻倍，而没有任何一处报错。
      }
    }
  }

  /**
   * ★★ **放贷行的可贷额（余粮）**（v2 spec §7.1 第一处；V6 落地）：
   *
   * <pre>
   * reserve  = lender.expectedNeedMilli(GRAIN, 该家户的 cycleDays)
   *            × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000        // 毫粮
   * lendable = max(0, 余额 − reserve)
   * </pre>
   *
   * <p>★ <b>H0 起"该家户的 cycleDays"来自它供给的那些产业</b>（行里已经没有产业段了；取最长，见 {@link
   * #cycleDaysByHousehold}）。真档三个产业都是 120 天 ⇒ 与改前逐值相同。 ★ <b>H1 起"余额"由调用方从会话工作副本读好传进来</b> （{@code
   * stock}；行里已经没有库存了）。
   *
   * <p>★★ **"本周期自需"取的是整周期**（{@code expectedNeedMilli(GRAIN, cycleDays)}），不是"今日一餐"、也不是
   * "消费后的全部库存"：放贷方先把这一周期自己**全部**的口粮扣下来，剩下的才是余粮。默认千分比 1000 ⇒ 保留额就是整周期口粮（"地主 250 天储备 − 120 天自需 = 130
   * 天余粮仍贷得出去"）。
   *
   * <p>★★ **来源口径（2026-10-09 Batch 3）**：前瞻需求 = 该户<b>当前注入</b>的 {@code naturalNeeds[grain]} ×
   * 周期天数；逐户读取、逐户保留，<b>不再</b>按 {@code population × 人均定额} 现算（人口/系数变化由下一次 app 注入刷新，见 {@link
   * HouseholdEconomy#expectedNeedMilli(io.mosire.simos.economy.api.id.CommodityId, long)}）。
   *
   * <p>★ **它只读、不写**：保留额不是"冻结起来的一笔粮"，放贷行自己每天照吃不误 —— 它只是"可贷额"的下界。 ⇒
   * 周期后半段会**多留**（那时已经用不到整周期的口粮了），这是本口径的可读后果，端到端用例逐值钉着它。
   *
   * <p>★★ <b>E4b 修正（如实记）</b>：S1 把行键从 {@code CohortKey} 换成 {@code HouseholdId} 之后，本方法一直用 {@code
   * lender.key()}（= {@code HouseholdEconomy.view()}，视图）去查 {@code HouseholdId} 键的 {@code
   * cycleDaysByHousehold} ⇒ <b>查表恒不命中、保留额实际为 0</b>（与本节类注承诺的"整周期自留"相反）。E4b 的实现要按 {@code max(0, 库存 −
   * 本周期自需)} 算可质押余粮（{@link DebtCapacityBook}），故这里改用 {@code lender.id()} 取同一份 {@code
   * cycleDaysByHousehold}；<b>放贷方的可贷额因此真正开始扣整周期口粮</b>（这是 E4b 报告里逐条列出的有意行为变化， 不是隐藏改动）。
   */
  static long lendableOf(HouseholdEconomy lenderHouseholdEconomy, long stock, long cycleDays) {
    if (stock <= 0L) {
      return 0L;
    }
    long reserve =
        lenderHouseholdEconomy.expectedNeedMilli(GRAIN, cycleDays)
            * LENDER_SUBSISTENCE_RESERVE_PER_MILLE
            / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /**
   * ★ 带查表的旧签名：键 = {@code lender.id()}（E4b 修正；见 {@link #lendableOf(HouseholdEconomy, long, long)}
   * 的边界说明）。
   */
  static long lendableOf(
      HouseholdEconomy lenderHouseholdEconomy,
      long stock,
      Map<HouseholdId, Long> cycleDaysByHousehold) {
    return lendableOf(
        lenderHouseholdEconomy,
        stock,
        cycleDaysByHousehold.getOrDefault(lenderHouseholdEconomy.id(), 0L));
  }

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
   * ★ 三条与 {@code settleOneDay} 末尾写 {@code FlowRow} 时的 {@code acc = newCycleHouseholds.contains(key)
   * ? null : flows.get(key)} 是同一条口径 ⇒ 借粮读到的“本周期” 与关账那一支读到的不是两个窗口（旧 {@code creditLinesOf} 只读当日
   * {@code income} 局部量，是另一个窗口 —— 见交付报告）。
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
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
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
        DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED,
        debtUnitValueLookup);
  }

  /*
   * ★ E4b/D-030/D-031：新口径的唯一算法仍在 DebtCapacityBook.capacities，但现在只服务清算/阶层下滑/只读诊断；
   * 借粮（4b）与市场信用都已不再把 headroom 当借款门（D-031：借款人无上限，唯一上限 = 放贷人实际可借头寸）。
   * 旧 LOAN_INCOME_MULTIPLE_PER_MILLE 只留 @Deprecated 别名。
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
  private static Map<HouseholdId, Market> marketIndexByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HexCoord, Market> markets,
      MarketTopology topology) {
    LinkedHashMap<HouseholdId, Market> byHousehold = new LinkedHashMap<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      Market market = marketForHex(householdEconomy.view().hex(), markets, topology);
      if (market != null) {
        byHousehold.put(householdEconomy.id(), market);
      }
    }
    return byHousehold;
  }

  /** 单格 → 市场区默认价目表：本格优先，缺则区锚格；没有归属 ⇒ null（合法状态）。 */
  private static Market marketForHex(
      HexCoord hex, Map<HexCoord, Market> markets, MarketTopology topology) {
    Market direct = markets.get(hex);
    if (direct != null) {
      return direct;
    }
    if (topology == null || !topology.contains(hex)) {
      return null;
    }
    return markets.get(topology.regionOf(hex).anchor());
  }

  /**
   * ★ 把某个"逐行 × 逐商品"缺口累加器里的一笔**冲减**掉（封顶 = 已记的量，绝不改成负数、也不凭空抵消历史缺口）。
   *
   * <p>★ 用途（H5）：家户在"吃饭"那一步记下的当日缺口，可能当天又被补上 —— ② 关账日集市上买到的粮、 ③
   * 借粮前"再吃一口"自家新到手的粮（收获/分配）。两处都只冲减**记过的那一笔**。
   */
  private static void trimUnmet(
      Map<HouseholdId, Map<CommodityId, Long>> unmet,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    long recorded = unmet.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
    long reduced = Math.min(recorded, amount);
    if (reduced <= 0L) {
      return;
    }
    Map<CommodityId, Long> updated = new LinkedHashMap<>(unmet.getOrDefault(key, Map.of()));
    if (recorded - reduced <= 0L) {
      updated.remove(commodity);
    } else {
      updated.put(commodity, recorded - reduced);
    }
    unmet.put(key, updated);
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
                  share.kind()));
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

  /**
   * ★★ <b>E4c：欠租/欠薪资本化</b>（生产/租金阶段之后；见 {@code settleOneDayInto} 的 3b）。
   *
   * <pre>
   * 输入   = 本日 ProductionLedger 的 Arrear 读数（owed = 应付 − 实付 > 0）
   * 去重   = 同一天内 (payer, payee, unit.key(), activity, rule) 相同 ⇒ 读数额相加、只 upsert 一次
   * unit   = Arrear.currency 有值 ⇒ DebtUnit.Money(currency)；否则 DebtUnit.Commodity(commodity)
   * terms  = DebtTerms.legacyDefault()（20‰/周期、关账日偿还后计息、NOT_ALLOWED、期限空）
   *          —— E4c 的默认条款来源在 {@code DebtCapitalization.termsSource} 里显式标成
   *          {@value #CAPITALIZATION_TERMS_SOURCE}，不把未知条款静默并进默认条
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
      EconomyData data,
      SettlementIndex index,
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedByHousehold,
      long day,
      long dueCycle) {
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
      DebtTerms terms = DebtTerms.legacyDefault();
      for (DebtPartyResolver.DebtAmount amount : split.amounts()) {
        DebtContract contract =
            DebtContractBook.upsert(
                debts,
                amount.debtor(),
                amount.creditor(),
                unit,
                terms,
                amount.amount(),
                day,
                OptionalLong.of(dueCycle));
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
   * 可动用   = 商品：max(0, 余额 − 冻结)；粮再扣 max(0, … − 一日口粮 × DEBTOR_SUBSISTENCE_RESERVE_DAYS) × 千分比
   *            货币：max(0, 逐币种余额 − 冻结)（全部币种；不跨币种求和/折换）
   * 介质序   = ① 全部货币：余额原始数量降序，tie 币种 id 升序
   *            ② 全部商品：余额原始数量降序，tie 商品 id 升序
   *            （钱永远第一位；不是每条债内部先钱后货）
   * 选择     = 逐条债（合同序）× 逐 medium（介质序）：identity 直接 1:1；否则用债务人价目表折成共同价值、
   *            整数 floor、最多还清本金；缺价 medium 具名跳过，换下一条
   * 付款     = 一组实际商品/货币腿铸成**唯一一条** LOAN_REPAYMENT 转移 → applyTransfer（带冻结/发行审计）
   * 本金     = 只减合同唯一的 principal（走 DebtContractBook.reduce；无 interest/principal 双账）
   * 缺价     = 持有资产但没有稳定价格 ⇒ 不折算、不静默付 0；记具名 DebtRepaymentSkip
   * </pre>
   *
   * <p>★★ <b>为什么不按 DebtUnit/币种分别核算</b>（D-023 第 3 条）：合同仍只有一条连续本金；利息仍按现有 {@link #chargeInterest}
   * 资本化进本金。介质自由只发生在“这笔本金用什么资产折付”这一层。
   *
   * <p>★★ <b>外部放贷主体 / 政府 / 家户一视同仁</b>（D-023 第 1 条）：收款人由 {@link DebtValuation.RepayeeResolver} 解析（默认
   * = 家户 actor）；只要其账户在本日 {@code AccountSession}/{@code actor} 账里可收，任意商品/货币腿都照收。收不了 ⇒ 具名
   * fail-closed，绝不静默吞款。
   *
   * <p>★★ <b>粮保留与冻结口径不弱化</b>：粮永远保留一天口粮后才可动（无论合同计价是粮还是钱）；任何腿都不越过冻结； 还清后 {@link DebtContract} 留在表里（本金
   * 0 的历史条不删，见 {@link DebtContractBook#reduce}）。
   *
   * <p>★ <b>读数</b>：{@code repaid} 记本日实际走粮腿的毫粮（含以粮折付其它计价口径）；{@code repaidMoney}
   * 记本日实际走货币腿的逐币种毫钱；其它商品腿从合同 principal 下降 + 转移凭据读出。
   *
   * @param repaid 本日实际粮腿的逐户累加器（就地更新 ⇒ 进 {@code FlowRow.repaid}）；不得为 null
   * @param repaidMoney 本日实际货币腿的逐户逐币种累加器（就地更新 ⇒ 进 {@code FlowRow.repaidMoney}）；不得为 null
   * @param marketsByHousehold 债务人 → 其所在市场区默认价目表（缺键 = 没有市场默认价目表 ⇒ 只有 identity 腿可付）
   * @param repayeeResolver 债权人 → 收款 actor 的解析口（默认 {@link
   *     DebtValuation.RepayeeResolver#HOUSEHOLD_ACTORS}； 外部放贷主体/政府账户可换一份 resolver）
   * @param householdPrices 单个家户价目表（可选；本批正式状态没有该字段 ⇒ 传 {@code null} 走市场默认）
   */
  private static void repayDebts(
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
      Map<HouseholdId, Market> marketsByHousehold,
      DebtValuation.RepayeeResolver repayeeResolver,
      DebtValuation.HouseholdPriceTable householdPrices,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal) {
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = DebtIndex.byDebtor(debts);
    List<HouseholdId> debtors = new ArrayList<>(householdEconomies.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value)); // 稳定债务人序（不沿用 Map 插入序）
    for (HouseholdId debtor : debtors) {
      HouseholdEconomy householdEconomy = householdEconomies.get(debtor);
      if (householdEconomy == null) {
        continue;
      }
      List<DebtContract> owed = new ArrayList<>();
      for (DebtContractId debtId : debtsByDebtor.getOrDefault(debtor, List.of())) {
        DebtContract debt = debts.get(debtId);
        if (debt != null && debt.principal() > 0L) {
          owed.add(debt);
        }
      }
      if (owed.isEmpty()) {
        continue;
      }
      Market market = marketsByHousehold.get(debtor);
      owed.sort(repaymentOrder(debtor, market, householdPrices));

      // ★ 可动用资产与介质序都取“本轮还款开始时”的值：介质序按原始余额排序，随后在逐债循环里只做扣减。
      long dailyNeed = householdEconomy.naturalNeeds().getOrDefault(GRAIN, 0L);
      long grainReserve = Math.multiplyExact(dailyNeed, DEBTOR_SUBSISTENCE_RESERVE_DAYS);
      Map<CommodityId, Long> spendableGoods =
          spendableGoods(debtor, householdGoods, householdFrozenGoods, grainReserve);
      Map<CurrencyId, Long> spendableMoney =
          spendableMoney(debtor, householdMoney, householdFrozenMoney);
      List<DebtUnit> mediumOrder = repaymentMediumOrder(spendableMoney, spendableGoods);
      Map<DebtUnit, Long> availableByUnit = availableByUnit(spendableMoney, spendableGoods);

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
  }

  /** ★★ <b>D-030 §3.5 的介质序</b>：全部货币（余额降序、币种 id 升序）→ 全部商品（余额降序、商品 id 升序）。 钱永远第一位；商品内部按原始数量、不按价值。 */
  private static List<DebtUnit> repaymentMediumOrder(
      Map<CurrencyId, Long> spendableMoney, Map<CommodityId, Long> spendableGoods) {
    List<CurrencyId> currencies = new ArrayList<>(spendableMoney.keySet());
    currencies.sort(
        Comparator.comparingLong((CurrencyId currency) -> spendableMoney.getOrDefault(currency, 0L))
            .reversed()
            .thenComparing(CurrencyId::value));
    List<CommodityId> commodities = new ArrayList<>(spendableGoods.keySet());
    commodities.sort(
        Comparator.comparingLong(
                (CommodityId commodity) -> spendableGoods.getOrDefault(commodity, 0L))
            .reversed()
            .thenComparing(CommodityId::value));
    List<DebtUnit> order = new ArrayList<>(currencies.size() + commodities.size());
    for (CurrencyId currency : currencies) {
      order.add(DebtUnit.money(currency));
    }
    for (CommodityId commodity : commodities) {
      order.add(DebtUnit.commodity(commodity));
    }
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

  /** 可动商品 = max(0, 余额 − 冻结)；粮再扣一日口粮保留 × 千分比（唯一拼写点，与旧 E4c 口径逐值同式）。 */
  private static Map<CommodityId, Long> spendableGoods(
      HouseholdId debtor,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      long grainReserve) {
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
      if (entry.getKey().equals(GRAIN)) {
        free = Math.max(0L, free - grainReserve);
        free = free * DEBT_REPAYMENT_SHARE_PER_MILLE / 1000L;
      }
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
      HouseholdId debtor, Market market, DebtValuation.HouseholdPriceTable householdPrices) {
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
      Map<IndustryId, Industry> industries,
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
        Industry industry = industries.get(unit.industry());
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
   * usableScale = min( ⌊usableAssets[k] ÷ capacityPerUnit[k]⌋ …, ⌊cycleInputUsedMilli[j] ÷ inputPerUnit[j]⌋ … )
   * need        = usableScale × 配方.laborPerUnit
   * </pre>
   *
   * <p>★ 与收获日公式（{@link ProductionEfficiencyBook}）是**同一个算式的两个前缀**：这里**刻意不含劳动那一路**（劳动正是本步要求解的量）。
   */
  private static long laborNeedOf(
      ProductionProcess unit,
      Industry industry,
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
        laborCommitment.kind());
  }

  /**
   * ★★ <b>既有 runtime 借粮路径的合同 id（唯一拼写点之一）</b>：债务人 × 债权人 × 粮 × 默认 legacy terms。
   *
   * <p>★ 真正的四元组派生在 {@link DebtContractId#idOf}；本方法只固定“这条 runtime 路径用粮单位与默认 legacy
   * terms”这两个维度，避免结算里两处各拼一遍（利率/规则的唯一字面量在 {@link DebtTerms}）。 ★ 跨周期必然命中同一条（这是 E4a 的连续身份），旧“周期在 id
   * 里”的行为到此结束（见交付报告的行为差异）。
   */
  static DebtContractId legacyGrainDebtId(HouseholdId debtor, HouseholdId creditor) {
    return DebtContractId.idOf(
        debtor,
        creditor,
        DebtUnit.commodity(GRAIN),
        DebtTerms.legacyDefault(BORROW_RATE_PER_MILLE_PER_CYCLE));
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
      ProductionProcess unit,
      Industry industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
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
      long loss = gross * (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) / 1000L;
      long net = gross - loss;
      ledger.addGross(industry.id(), commodity, gross);
      ledger.addLoss(industry.id(), commodity, loss);
      grossByCommodity.put(commodity, gross);
      netByCommodity.put(commodity, net);
      // ★★ **R5 ②：净产进产出计提（+净产 → 账户主体家户），不再写进本产业的行**。
      //   ★ 单一主体 ⇒ 一条计提；集体主体 ⇒ 按劳动权重分给各家家户（产出归属与投入代理同一批人）。
      creditOutput(
          subject, subjectWeights, householdGoods, operator, commodity, net, location, ledger);
    }
    if (netByCommodity.isEmpty()) {
      return; // 规模 0（或产出表为空）⇒ 没有产出、也没有可付的：连规则都不必结算
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
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation,
            new ProductionSettlement.Facts(
                location,
                grossByCommodity,
                netByCommodity,
                unit.cycleInputUsedMilli(),
                laborOfCohort(householdEconomies, location, industry.cycleDays()),
                industry.outputPerUnit(),
                // ★★ 货币档的付款上限 = **账户主体家户们的可花货币合计**（逐币种）；解析不到主体 ⇒ 空表（实付 0、
                //   欠额进读数 —— 如实报，不是静默付 0）。
                availableMoneyOf(subject, householdMoney)),
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
  }

  /**
   * ★★ <b>Z2（§8）：{@code PRODUCTION_EFFICIENCY_HARVEST} DEBUG，逐 unit</b> —— day / unit / laborScale
   * / satisfaction / scaleBase / avgModifier / scale / 四个余数（外加 {@code modifierEffective}、 {@code
   * laborPerUnitZero} 两个审计位）。只在 {@code TRACE.isDebugEnabled()} 时调用。
   *
   * <p>★ {@code lpu == 0} 的退化按 §6.2 ② 另记一条具名 DEBUG {@code
   * PRODUCTION_EFFICIENCY_LABOR_PER_UNIT_ZERO} （修正不生效、走旧口径；不新增 WARN、不抛）。
   */
  private static void logProductionEfficiencyHarvest(
      long day, ProductionUnitId unit, ProductionEfficiencyBook.HarvestEvaluation evaluation) {
    EventLog.channel(TRACE)
        .debug(
            LogEvent.of(
                "PRODUCTION_EFFICIENCY_HARVEST",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                day,
                "unit",
                unit.value(),
                "laborScale",
                evaluation.laborScale(),
                "satisfaction",
                evaluation.satisfactionPerMille(),
                "scaleBase",
                evaluation.scaleBase(),
                "avgModifier",
                evaluation.avgModifierPerMille(),
                "scale",
                evaluation.scale(),
                "modifierRemainderMilli",
                evaluation.modifierRemainderMilli(),
                "laborDayRemainderMilli",
                evaluation.laborDayRemainderMilli(),
                "laborScaleRemainderMilli",
                evaluation.laborScaleRemainderMilli(),
                "scaleRemainderMilli",
                evaluation.scaleRemainderMilli(),
                "modifierEffective",
                evaluation.modifierEffective(),
                "laborPerUnitZero",
                evaluation.laborPerUnitZero()));
    if (evaluation.laborPerUnitZero()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "PRODUCTION_EFFICIENCY_LABOR_PER_UNIT_ZERO",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  day,
                  "unit",
                  unit.value(),
                  "laborScale",
                  evaluation.laborScale(),
                  "scaleBase",
                  evaluation.scaleBase(),
                  "scale",
                  evaluation.scale(),
                  "modifierEffective",
                  evaluation.modifierEffective()));
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
   */
  private static void logFxWindows(long day, EconomyData base, FxRoundInput fxInput) {
    List<MarketZone> zones = MarketZoneBook.zones(base.marketZones());
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
                  base.governments().size()));
    }
    if (!FX.isDebugEnabled()) {
      return;
    }
    int governmentsWithRates = 0;
    for (Government government : base.governments().values()) {
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
          MarketZoneBook.zonesCovering(base, window.governmentId(), rate.base(), rate.quote());
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
   * ★★ <b>P5：回笼（WITHDRAWAL）写口的包内入口</b> —— 薄转发到唯一实现 {@link TreasuryWithdrawal#withdraw}；
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
   * ★★ **周期末的饿死判据**（2026-09-25 用户点名；**默认不致命** —— 见 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）：把本周期逐日累加的未满足需求 {@code cycleUnmet} 折成"**饿满整个周期的那个比例**"，再在这一比例的人口里按
   * {@code famineMortalityPerMille} 致死。
   *
   * <pre>
   * long cycleNeed = householdEconomy.cycleNaturalNeedMilli(); // 本周期总需求：逐日注入 naturalNeeds[grain] 的累加
   * int  faminePerMille = cycleNeed == 0 ? 0 : min(1000, cycleUnmet × 1000 / cycleNeed);
   * long deaths = population × faminePerMille / 1000 × famineMortalityPerMille / 1000;
   * </pre>
   *
   * <p>★★ **2026-10-09 Batch 3：本周期总需求分母改读行上的 {@code cycleNaturalNeedMilli}** （每日 app 逐户注入的 {@code
   * naturalNeeds[grain]} 在消费步逐日累加，窗口 = 本周期实际经过的天）； <b>不再</b>用 {@code 人口 × 累计口粮定额} 现算 ——
   * 分子（本周期累计未满足）与分母从此同源、同窗口。
   *
   * <p>★ 人口减少后，**有效劳动按同一比例缩**（{@code labor = labor × (population − deaths) / population}；{@code
   * population == 0} ⇒ {@code labor = 0}，**不除零**）；死亡数记入本行流水（{@code deaths}）。**死亡不回溯产出**：
   * 本周期收获已在调用方先行分配（照分给幸存者）。
   *
   * <p>★ 不变量：{@code faminePerMille ≤ 1000} 且致死率 {@code ≤ 1000‰} ⇒ {@code deaths ≤
   * population}、{@code 人口 ≥ 0}、 {@code labor ≥ 0}（构造期由 {@link HouseholdEconomy} 再兜一层）。
   *
   * <p>★★ **R4 起这条旧账已收口**（R2 如实记过的那处不齐）：本节缩的**行**劳动之外，调用方还会把该产业名下的**全部劳动配额**
   * 与对应批次的**劳动供给**按同一个存活比例缩（{@link #scaleLaborOfIndustry}）—— 于是"人死了劳动没减"不再成立。 ★ 另一条人口变化路径 （Social 每
   * tick 生死，见 {@link #applyHouseholdPopulationDeltasInto}）只同步家户行人口：劳动预算由 app 用**结算后**的 Social
   * 重算，不在这里按比例缩。
   *
   * <p>★ **它现在还是"直接按缺口处死"那个独立旋钮**（默认 0‰）：2026-10-09 每 tick 生死起，日常出生/死亡走 Social 的 ppm/tick 率表 +
   * 余数累加器（不再有"生理压力抬死亡率"那条路）；本方法的致死率仍由 {@link #FAMINE_MORTALITY_PER_MILLE} 控制， 且**逐值用例仍钉着非 0
   * 那一条路**（不是死分支）。
   */
  private static void applyFamine(
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<HouseholdId, Long> deaths,
      HouseholdId key,
      HouseholdEconomy householdEconomy,
      long cycleUnmet,
      int famineMortalityPerMille) {
    long population = householdEconomy.population();
    // ★★ 2026-10-09 Batch 3：本周期总需求 = 行上的 cycleNaturalNeedMilli（消费步对逐日注入
    //   naturalNeeds[grain] 的累加，新周期第一天在流水清零点重置）；不再按 population 现算累计口粮差。
    long cycleNeed = householdEconomy.cycleNaturalNeedMilli();
    int faminePerMille =
        cycleNeed == 0L ? 0 : (int) Math.min(1000L, cycleUnmet * 1000L / cycleNeed);
    long dead = population * faminePerMille / 1000L * famineMortalityPerMille / 1000L;
    if (dead <= 0L) {
      return;
    }
    long nextPopulation = population - dead; // faminePerMille ≤ 1000 且致死率 ≤ 1000‰ ⇒ 必 ≥ 0
    long nextLabor =
        population == 0L
            ? 0L
            : householdEconomy.laborMilli() * nextPopulation / population; // 同比例缩，不除零
    householdEconomies.put(
        key, withPopulationAndLabor(householdEconomy, nextPopulation, nextLabor));
    deaths.merge(key, dead, Long::sum);
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
   * {@code netSurplus}：见 {@code settleOneDay} 里那条注释）。 **债权人这一侧本批不记**：它的资产增值体现在债务表里，而 {@code
   * FlowRow.income} 是**粮食**口径 （往里加"利息收入"会让守恒式当场不成立）⇒ 记在债权侧要等 §五 的索取源协议/市场折算（V7+）。
   *
   * <p>★ **偿还先于计息**（R3 续修）：关账日的日序是 借粮 → 偿还 → 饿死判据 → 计息（见 {@link #repayDebts}）， 这里只负责把利息并入本金； {@link
   * DebtContract#dueCycle()} 由借款路径滚动写入（读口与退出处置的排序会读它），{@link DebtContract#lastInterestDay()}
   * 在本步真的并入利息时写； E4a 只接线 {@link InterestTiming#AFTER_REPAYMENT_ON_CLOSE}，其它计息时点 fail-closed 具名抛（E4b
   * 接线）。
   *
   * <p>★★ **计息本金取"当日起始"快照，不取当日 lend/repay 之后的本金**（M0.5 守恒式的权威口径：计息日 {@code + ⌊昨 × 率 ÷ 1000⌋}，"昨" =
   * 当日开始时的本金）：当天新借的债当天不计息；当天还掉的那部分本金 **今天照样计息**（"先还后计"的偿还次序不变，只是计息基数不跟着偿还缩水）。计息仍并入**当前**本金 ⇒ 与守恒式
   * {@code 本金_今 = 昨 + 放出 − 偿还 + ⌊昨 × 率 ÷ 1000⌋} 逐值一致。★ 快照由 {@code settleOneDay} 在任何 lend/repay
   * 之前建好传入。
   *
   * @param debts 债务表（就地更新：本金并入利息）
   * @param principalAtDayStart 当日起始的逐债本金快照（当日新借的债不在快照里 ⇒ 今天不计息）
   * @param interest 本日利息的逐行累加器（**只记债务人**那一侧）
   * @param day 本结算日（真的并入利息时写进 {@code lastInterestDay}）
   */
  private static void chargeInterest(
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      LinkedHashMap<HouseholdId, Long> interest,
      long day) {
    for (Map.Entry<DebtContractId, DebtContract> entry : new ArrayList<>(debts.entrySet())) {
      DebtContractId id = entry.getKey();
      Long startPrincipal = principalAtDayStart.get(id);
      if (startPrincipal == null || startPrincipal <= 0L) {
        continue; // 当日新借（不在快照里）或本金本来就是 0 ⇒ 今天不计息
      }
      DebtContract debt = entry.getValue();
      if (debt.terms().interestTiming() != InterestTiming.AFTER_REPAYMENT_ON_CLOSE) {
        throw new IllegalStateException(
            "E4a 尚未接线非 legacy 计息时点：合同="
                + id
                + " timing="
                + debt.terms().interestTiming()
                + "（E4b 接线；此处 fail-closed，不静默当成默认档）");
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

  // ── Z7b：国库/单位户退出商品市场的排除集 ──────────────────────────────────────────

  /**
   * ★★ <b>政府国库户集合</b>（R1 起 = "只按授权下单"的名单来源）：{@code base.governments()} 里国库 actor 是 HOUSEHOLD 的那些
   * 稳定家户身份。★ 权威来自经济状态自己的 {@code Government.treasury}，不按 {@code hh-gov-} 前缀猜（前缀只是身份拼法，见 {@code
   * GovernmentHouseholds}）；非家户国库（{@code GOVERNMENT} actor）本来就不在市场参与者行里。
   *
   * <p>★★ <b>R1 的语义变更</b>：本集合原来被并进"退出商品市场"的排除集（Z7b）。现在它<b>不再是排除集</b>：这些家户回到商品市场， 但只按 {@code
   * EconomyData.govMarketMandates()} 的明确授权下单（自动买卖/放贷/家户外汇单全部不生成）—— 名单的实际消费点是 {@link
   * GovernmentMarketMandatePlan}。
   */
  /**
   * ★★ <b>R1：单位户排除集 − 已登记政府国库户</b>（保序、不可变）—— 把"是政府国库户"的单位户从排除集里摘出来。
   *
   * <p>★★ <b>为什么需要它</b>：GOV 单位的 {@code Unit.households()} 同时列出**官吏户与国库户**（国库是 GOV 单位的
   * 账房），而组合根注入的"退出商品市场的单位户集合" = {@code Σ Unit.households()} ⇒ 若不摘，Z7b 的"国库户退出" 会从单位户那一半**原路回来**：R1
   * 撤销了 economy 侧的并入，却仍被单位侧挡住（small-world 实测：{@code hh-gov-gov-central} 与 {@code
   * hh-gov-gov-province} 都在 {@code Unit.households()} 里，只有 {@code hh-gov-world-silver} 不在 ⇒
   * 撤销只对后者生效）。
   *
   * <p>★ <b>摘出来不等于放开</b>：摘出的家户改走"只按授权下单"（{@link GovernmentMarketMandatePlan}）—— 不生成任何自动订单、
   * 不放贷、不挂家户外汇单，公家库存与单位库存都不会被自动清仓。其余单位户（官吏户/军户…）**逐值不动**。
   */
  static Set<HouseholdId> unitExclusionsMinusGovernmentTreasuries(
      Set<HouseholdId> unitHouseholds, Map<GovernmentId, Government> governments) {
    Objects.requireNonNull(unitHouseholds, "unitHouseholds");
    Set<HouseholdId> treasuries = governmentTreasuryHouseholds(governments);
    LinkedHashSet<HouseholdId> remaining = new LinkedHashSet<>();
    for (HouseholdId household : unitHouseholds) {
      if (household == null) {
        throw new IllegalArgumentException("marketExcludedHouseholds 不得含 null");
      }
      if (!treasuries.contains(household)) {
        remaining.add(household);
      }
    }
    return Collections.unmodifiableSet(remaining);
  }

  static Set<HouseholdId> governmentTreasuryHouseholds(Map<GovernmentId, Government> governments) {
    Objects.requireNonNull(governments, "governments");
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (Map.Entry<GovernmentId, Government> entry : governments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("governments 的键/值不得为 null");
      }
      Government government = entry.getValue();
      if (government.treasury().kind() == ActorKind.HOUSEHOLD) {
        households.add(HouseholdActors.householdOf(government.treasury()));
      }
    }
    return Collections.unmodifiableSet(households);
  }

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
   * ★ <b>"这个世界没有货币"的会话副本</b>（每个家户一本空钱包）—— 只服务**包内**的单模块入口 （{@link #settleOneDay} 的短重载与 {@code
   * EconomyDayStepper} 的短构造器）。
   *
   * <p>★ 它<b>不是</b>"余额为 0 的默认值"：它说的是"世界的货币总量是 0"这个**合法状态** —— 没有货币 ⇒ 没有有效需求（市场买不动）、没有货币工资（可用 0）。★
   * <b>app 协调器不许走它</b>： 真档的货币（创世禀赋）住在 actor 侧，必须由协调器载入（否则钱会在账上静默消失）。
   */
  static LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> emptyMoneyAccountsFor(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    for (HouseholdId key : householdEconomies.keySet()) {
      money.put(key, Map.of());
    }
    return money;
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

  /** 换人口与有效劳动（饿死惩罚用；其余字段原样带过）。 */
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

  // ★★ R3B.2：旧的 withCycleState(Industry…) 已删除 —— 周期状态（progress/cycleLabor/cycleInputUsed）
  //   现在属于 ProductionProcess（见 ProductionProcess#withCycleState），Industry 只留模板、不再每天重建。

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
