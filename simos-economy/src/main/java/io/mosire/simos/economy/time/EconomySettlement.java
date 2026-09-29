package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionRecipe;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
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

/**
 * ★★ **R3a 日结算 + R4a 周期收获与制度分配**（聚合式经济重设计 §四 的日/周期步骤，v1 口径）——纯函数：拿 {@link EconomyData} 交**新**的
 * {@link EconomyData}，**不写状态、不碰核心**。
 *
 * <p>★★ **一次 {@code AdvanceTime} 按区间逐日跑**（2026-09-25 §十一：一次推进 N 天，内部逐日；见 {@link #settle}）：
 *
 * <ol>
 *   <li>**播种**：周期的第一天（{@code progressDays == 0}）先扣种子（{@link #sowIfCycleStart}）——**先于当天消费** （{@link
 *       #PLANTING_DRAWS_BEFORE_CONSUMPTION}，v2 spec §3.2）。种子粮与口粮是同一个商品，优先性来自**时点**。
 *   <li>**消费**：每行扣当天口粮 {@code EconomyVocabulary.dailyRationMilli(人口, 绝对日号)}（= 累计口粮的**逐日差分**， 口径"每人每
 *       120 天 10 粮"，v2 spec §八.6），**并把该数写进** {@link ClassRow#naturalNeeds()}（§八.8"读数与结算同源"
 *       的**一条真相**：读口直接读它，不再各算一遍）。
 *   <li>**缺口**：库存不够 ⇒ 先在同格内借粮（**按可贷余粮降序**放贷 —— 旧"地主 → 富农 → 中农"的阶层白名单已由可观察余粮取代，见 {@link
 *       #lendDeficitsInHex}；从有粮的行的**余粮**划转 —— 余粮 = 库存 − **本周期自需** × {@link
 *       #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000，见 {@link
 *       #lendableOf}，**不是**"消费后的全部库存"，也不是"当日盈余"）；借到的**累加进同一条** {@link DebtContract}（同一 (债务人, 债权人,
 *       unit, terms) 跨周期恒同一条，见 {@link #legacyGrainDebtId}，本金递增）；**借完仍补不上**的部分记入本行流水的 {@code
 *       unmetNeed}（毫粮、逐日累加，供周期末的饿死判据 —— 见 {@link #FAMINE_MORTALITY_PER_MILLE}，默认致命率 0‰）。
 *   <li>**进度**：每个产业 {@code progressDays + 1}。
 *   <li>**劳动投入**：本产业当日实际劳动 = **该产业名下全部 {@code LaborAllocation} 的 {@code laborMilli} 之和**（R2
 *       改口径；改前是"Σ(行 {@code laborMilli × participationPerMille / 1000})"，两者在创世逐值相同）—— 累加进 {@link
 *       Industry#cycleLaborMilli()}（供收获时算劳动瓶颈）。★ 于是"同一批人的劳动"**只有一处真相**：配额表；而"配额之和 ≤
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
 *       {@code 0} ⇒ **不施加那一路约束**（不是"规模 0"）—— 旧档与未配投入的产业据此与 V2 逐值一致。
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
 *       {@link #chargeInterest}。★ **偿还在计息之前**（关账日、所得到账后）：E4c 起按 **粮债 → 其它商品债 → 货币债** 的稳定序逐条偿还 （见
 *       {@link #repayDebts}；粮另扣一日口粮保留，货币只扣本币种可用余额、不越过冻结，无价格源不做货币折偿）； 不足部分顺延到下一周期，不静默减记。
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
 * <b>真档数值会变</b>（K3 已认这个代价，见 {@code ClassRow} 的注释；★ H3 起"各行想扣多少"这条口径 <b>整块删掉</b>（改前的 {@code
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
   * ClassRow.naturalNeeds[GRAIN]}（结算日当天的自然口粮需要）取，不用"人口 × 拍出来的数"另算一份。
   */
  public static final int DEBTOR_SUBSISTENCE_RESERVE_DAYS = 1;

  /**
   * ★★ **放贷方必须留口粮的千分比**（相对**本周期自需**；v2 spec §7.1 第一处，V6 落地）：**默认 1000‰**。
   *
   * <pre>
   * 保留额 = cumulativeRationMilli(放贷行人口, 该行产业的 cycleDays) × 本常量 ÷ 1000   // 毫粮
   * 可贷额 = max(0, 放贷行库存 − 保留额)
   * </pre>
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
   * GoodsAccount.frozenBalances}。★ 两者的差别是<b>语义</b>的：这里是"<b>打算</b>留着的下界"（每天都可能变），
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

  private EconomySettlement() {}

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
   *       切片的 {@code GoodsAccount} 上。⇒ 只要这个世界有 {@code population > 0}
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
    for (Map.Entry<HouseholdId, ClassRow> entry : base.classes().entrySet()) {
      if (entry.getValue().population() > 0L) {
        throw new IllegalStateException(
            "本入口（多日静态入口）没有**家户账** —— 家户的商品库存住在 actor 切片的 GoodsAccount 上，"
                + "而日结算的消费与投入都要读它（H1/K1：家户账是会话状态）。"
                + "把'没有账'当成'库存 0'是本仓最反对的形态，故当场抛：家户="
                + entry.getKey()
                + " 人口="
                + entry.getValue().population()
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
          ledger);
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
      ProductionLedger.Accumulator ledger) {
    settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        famineMortalityPerMille,
        ledger,
        EconomyParallelism.singleThreaded());
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
      EconomyParallelism parallelism) {
    Objects.requireNonNull(session, "session（S1：revision 级会话持有可变工作表）");
    Objects.requireNonNull(accounts, "accounts（S1：账户会话是会话状态，必须由调用方载入）");
    Objects.requireNonNull(topology, "topology（M2.3：区域拓扑是只读输入；单格世界用 MarketTopology.singleHex）");
    Objects.requireNonNull(parallelism, "parallelism（R2：并行度配置）");
    EconomyData base = session.base();
    // ★★ E3：发行主体的权威答案是当前世界状态（governments），不是进程里的旧登记。
    //   日结算开始按 base 重建登记表：旧世界/旧档 governments 为空 ⇒ 清空登记 ⇒ requireIssuerOf 逐字保留旧 fail-closed 行为。
    MoneyIssuance.syncAuthorities(base.governments().values());
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = accounts.householdGoods();
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney = accounts.householdMoney();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = accounts.householdFrozenGoods();
    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney = accounts.householdFrozenMoney();
    Map<ActorRef, Map<CommodityId, Long>> operatorGoods = accounts.operatorGoods();
    Map<ActorRef, Map<CurrencyId, Long>> operatorMoney = accounts.operatorMoney();
    Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods = accounts.operatorFrozenGoods();
    Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney = accounts.operatorFrozenMoney();
    EconomyMeta meta = session.sheet().meta().orElseThrow();
    long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    long dueCycle = currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1
    // ★★ E3：本次 revision 的发行审计收集器（id 由 transfer id + 币种确定性派生；并行分区也安全）。
    //   ★ 发行腿只在付方余额不足且付方 = 当前政府国库时才会用到；旧路径（无 issuer）不产生任何记录。
    MoneyIssuanceJournal issuanceJournal =
        new MoneyIssuanceJournal(base.governments(), currentCycle);

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, Industry> industries = session.sheet().industries();
    // ★★ R3B.2：生产单元表工作副本（进度/劳动/投入的唯一写点；Industry 只留模板）。
    LinkedHashMap<ProductionUnitId, ProductionUnit> units = session.sheet().units();
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
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
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations = session.sheet().allocations();
    LinkedHashMap<PeopleLotId, LaborSupply> laborSupply = session.sheet().laborSupply();
    // ★ S1：成员份额与资产份额的工作副本（死亡/人口回写要缩成员份额；资产份额本步原样带过，
    //   但日结算必须交出**同一份**状态，不能让两个组件在终态里漂开）。
    LinkedHashMap<MembershipId, Membership> memberships = session.sheet().memberships();
    LinkedHashMap<AssetShareId, AssetShare> assetShares = session.sheet().assetShares();
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
    Map<ProductionUnitId, ProductionRelation> relations = session.sheet().relationsOrBase();

    // ★★ R4-B.3a-perf：日结算的只读派生索引 —— **每天入口构建一次**。它把“unit 可用资产/产能、unit↔家户、
    //   格↔unit/产业、债务人↔债务”这些一天内不变的问题一次算好，串行热路径与并行 worker 都只查表。
    //   ★ 不进 EconomyData/ChangeSet/Codec，不作为第二份状态；AssetShare/Industry/unit 本步不改写，故这一份在
    //     日结算内始终有效。LaborAllocation 会在劳动再分配后被改写 ⇒ 那之后用 withLabor(...) 换一次配额侧视图；
    //     DebtContract 会在借粮/偿还/计息后被改写 ⇒ 状态机之前用 withDebtContracts(...) 换一次债务视图。
    SettlementIndex settlementIndex =
        SettlementIndex.build(units, industries, assetShares, allocations, rows, debts, relations);

    // ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）——
    //   放在任何公式之前（与 E14 的"在任何数量计算之前"同款）：副本缺键时若继续跑，缺的那一家会被当成"库存 0"
    //   ⇒ 它当天"吃 0、投入 0"，账面看不出少了谁。那正是本仓最反对的形态，故当场抛。
    requireHouseholdAccounts(rows, householdGoods);
    // ★★ **H4 的第一条守卫：货币账必须覆盖每一个"可能要花钱的家户"**（与商品那条同款、同一条理由）：
    //   缺键时若继续跑，那一家的可花余额会被当成 0 —— 它当天的有效需求因此是 0、市场买不到粮、
    //   而账面（缺口只多不少）看起来完全正常。⇒ 缺键 ⇒ 当场抛（"没有账"与"账是空的"是两件事）。
    requireHouseholdMoney(rows, householdMoney);

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
              rows,
              householdGoods,
              householdMoney,
              memberships,
              laborSupply,
              units,
              assetShares,
              allocations,
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
                allocations,
                operatorConditions,
                session.sheet().relations(),
                rows,
                householdGoods,
                householdMoney,
                memberships,
                laborSupply,
                markets,
                day);
        outcomes.addAll(execution.outcomes());
        enteredToday = execution.enteredUnitIds();
        if (execution.changed()) {
          // unit/relation/份额/配额都变了 ⇒ 换一份索引再进现有日结算（进入日是低频事件，一次 O(状态) 重建）。
          relations = session.sheet().relationsOrBase();
          settlementIndex =
              SettlementIndex.build(
                  units, industries, assetShares, allocations, rows, debts, relations);
        }
      }
      entryOutcomes = outcomes;
    }
    EntryOutcomeFeed.publish(meta.mapId(), day, entryOutcomes);

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
    //      ClassStanding；随后 organize 以新归属做幂等检查（新组织已 ACTIVE ⇒ 不重建），retain=1000 的旧
    //      EXITING 组织也不会被重建（organize 对该状态显式跳过）。
    //   ★★ 闸门：`modeTransitions` 为空时连工作副本都不建（空表基线逐值不变）；apply 内部再判一次到期 PENDING。
    //   ★ 只对"base 里真有一条到期 PENDING"才进入：APPLIED/FAILED 的历史变迁不产生任何拷贝（跨 revision 的稳定基线）。
    //   ★ 它只写 organizations / units / pledges / classStandings / modeTransitions / classShares
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
      EconomyModeTransitionSettlement.apply(
          base,
          day,
          rows,
          session.sheet().productionOrganizations(),
          units,
          assetShares,
          session.sheet().pledges(),
          session.sheet().classStandings(),
          session.sheet().modeTransitions(),
          session.sheet().classShares());
    }

    // ── 0-entry.5. ★★ E2 自动生产组织（理想架构 §4.2 ①；计划 E2）────────────────────────────
    //   ★★ 位置：在 **0-entry 候选采用之后、现扣投入/劳动再分配/消费之前** —— 今天新建的 unit 因此当天就能进入本周期
    //      （progressDays=0 ⇒ 现扣投入会为它扣一次料，随后劳动再分配按它的 need 保留配额）。
    //   ★★ 闸门：`modes` 为空时**这一整段不执行**（连工作副本都不建）⇒ 旧档/未接线世界的 HouseholdClassRule
    //      与全部 settle* 路径逐值不变。闸门在调用点与 organize 内各判一次（显式、可读）。
    //   ★ 自动组织只写五张工作副本：units / relations / assetShares（租佃拆分）/ allocations /
    //      productionOrganizations；不新建 Industry 模板、不写 markets/rows/debts。
    EconomyOrganizationSettlement.Outcome organizationOutcome =
        EconomyOrganizationSettlement.Outcome.empty();
    if (!base.modes().isEmpty()) {
      organizationOutcome =
          EconomyOrganizationSettlement.organize(
              base,
              // ★ E6a：变迁刚写过的 standing 工作副本优先（无变迁时 = base 的不可变表，旧路径逐值不变）。
              session.sheet().classStandingsOrBase(),
              rows,
              industries,
              base.pledges(),
              units,
              session.sheet().relations(),
              assetShares,
              allocations,
              memberships,
              laborSupply,
              householdGoods,
              markets,
              session.sheet().productionOrganizations());
      if (!organizationOutcome.createdUnitIds().isEmpty()) {
        // unit/关系/份额/配额都变了 ⇒ 换一份索引再进现有日结算（与 0-entry 的执行后重建同款）。
        relations = session.sheet().relationsOrBase();
        settlementIndex =
            SettlementIndex.build(
                units, industries, assetShares, allocations, rows, debts, relations);
        // ★ 今天由组织阶段新建的 unit 加进"刚进入"豁免集：不得让它触发/参与同格既有周期的重排
        //   （与 R4-E2b 的 enteredToday 同一条口径；下一个日结算日它自然成为普通 unit）。
        Set<ProductionUnitId> withOrganized = new LinkedHashSet<>(enteredToday);
        withOrganized.addAll(organizationOutcome.createdUnitIds());
        enteredToday = withOrganized;
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
    deliverShipments(day, session, accounts, ledger, parallelism);

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
      // ★ R2：劳动再分配已按 hex 并行（配额键含产业 id ⇒ 跨 hex 无冲突；同一批次跨 hex 的全局协调留给 R3）。
      // ★★ R4-E2b：今天刚进入的 unit 不触发"本周期是第一天"的重排判定（它今天确实是 0，但它不属于既有周期的重排对象）。
      reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      // ★ 配额被改写 ⇒ 换一份“配额侧”视图，后续（unit 家户归属/市场参与者/人口回写）继续 O(1) 查表。
      settlementIndex = settlementIndex.withLabor(units, rows, allocations);
    }

    // ── 1~2. 消费（各自吃自己的库存；缺口**先记下不借** —— 借是最后手段，见 4b）──────────────
    //   ★ H4：每条家户行的 cycleDays 提成局部量 —— 它同时喂"放贷余粮"（lendDeficits）与"市场自留"（MarketSettlement），
    //     两处各算一遍就是同一个量的第二处拼写点（算错不会报错，只会让两处口径悄悄漂开）。
    Map<HouseholdId, Long> cycleDaysByHousehold =
        cycleDaysByHousehold(
            rows, industries, units, unitsOfHousehold, settlementIndex.industriesByHex());
    consumeOwnStockPartitioned(
        rows, accounts, consumedGoods, unmetToday, deficitToday, day, parallelism);

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
      reallocateLaborPartitioned(session, parallelism, settlementIndex, enteredToday);
      // ★ 配额被改写 ⇒ 换一份“配额侧”视图（与 plantingDrawsFirst 分支同一条阶段边界）。
      settlementIndex = settlementIndex.withLabor(units, rows, allocations);
    }

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 + 饿死惩罚 ────────────────────────────
    boolean anyCycleClosed = false;
    Set<ProductionUnitId> newCycleUnits = new LinkedHashSet<>();
    // ★★ **R2：当日劳动的唯一来源 = 劳动分配表**（第三阶段设计稿 §四）。按 actor id 归集一次（O(配额条数)），
    //   再逐产业取用 —— 产业 id 与 actor id 的对应关系由 EconomyData 的构造期守卫判死
    //   （产业型主体必须指名已存在的产业、非产业型主体不得与产业 id 撞名）。
    Map<String, Long> laborByUnit = settlementIndex.laborByUnit();
    // ★★ **H0：周期刚翻篇的 unit**（progressDays 归 0，含创世）—— 先算好，因为"家户的流水何时翻篇"要读它。
    for (Map.Entry<ProductionUnitId, ProductionUnit> entry : units.entrySet()) {
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
    for (HouseholdId key : rows.keySet()) {
      Set<ProductionUnitId> supplied = unitsOfHousehold.getOrDefault(key, Set.of());
      if (supplied.isEmpty()) {
        // ★ 兜底：没有配额的户按它住的那一格的产业找 unit（与 cycleDaysByHousehold 同一条兜底）。
        ClassRow cycleRow = rows.get(key);
        if (cycleRow != null) {
          // ★ R4-B.3a-perf：格 → unit 由入口索引一次给出（旧实现逐无配额家户全量扫 8,940 个 unit）。
          supplied =
              new LinkedHashSet<>(
                  settlementIndex.unitsInHex(
                      IndustryHexKeys.hexKey(
                          cycleRow.view().hex().q(), cycleRow.view().hex().r())));
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
    // ★★ R2：关账产业先收集成工作单元，进度/周期状态按原序落回 industries；收获/关系分账在收集完后
    //   按 hex 并行执行（同一 hex 内的多个产业共用家户账/关系账 ⇒ 同分区串行，见 runHarvestStage）。
    List<HarvestWork> harvestWorks = new ArrayList<>();
    int harvestOrder = 0;
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionUnit unit = units.get(id);
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
      long nextProgress = progressed;
      long nextCycleLabor = cycledLabor;
      Map<CommodityId, Long> nextInputUsed = unit.cycleInputUsedMilli(); // 非关账日：原样带过
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**：>= 才把合法状态收获掉。
      if (progressed >= industry.cycleDays()) {
        // ── 周期末：产出 → 产权条目 + 关系规则入账（★ 饿死判据**挪到所有救济通道之后**，见下面的 4d）──
        OperatorCondition condition = operatorConditions.get(id);
        harvestWorks.add(
            new HarvestWork(
                unit,
                industry,
                cycledLabor,
                hexOfIndustry(unit.industry()),
                harvestOrder++,
                StressPolicy.plannedScalePerMille(
                    condition == null
                        ? OperatorCondition.IndustryStatus.ACTIVE
                        : condition.status())));
        // ★★ **H5：关账的 unit 先记下来，饿死判据等救济通道走完再算**。
        closed.add(
            new ClosedUnit(
                id,
                industry.id(),
                keys,
                industry.cycleDays(),
                inputShortfallOf(unit, industry, settlementIndex, operatorConditions.get(id))));
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextInputUsed = Map.of(); // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的投入瓶颈凭空变大）
        anyCycleClosed = true;
      }
      units.put(id, unit.withCycleState(nextProgress, nextCycleLabor, nextInputUsed));
    }
    // ★★ R2：收获/关系分账按 hex 并行（唯一写口仍是 applyTransfer；转移在分区累加器里铸号，协调器按分区序吸收）。
    harvestPartitioned(
        harvestWorks,
        rows,
        settlementIndex,
        relations,
        householdOfActor,
        accounts,
        income,
        ledger,
        issuanceJournal,
        day,
        parallelism);

    // ── 3b. ★★ E4c：欠租/欠薪资本化（生产/租金阶段之后）─────────────────────────────────────
    //   ★★ 只对**本日 ledger 的 Arrear 读数**（owed > 0）执行：把"制度规定未付"落成连续合同债权；
    //     **不移动任何商品/货币库存**（欠款本来就是未付），也**不清零 Arrear 读数**（读数仍是制度事实）。
    //   ★★ 端点必须解析到 HouseholdId（经 householdOfActor）；解析不到 ⇒ 具名跳过（不伪造）。
    //   ★ 位置在**借粮/偿还之前**：新增的既有债因此同日进入 DebtCapacity（借粮额度）与偿还排序；
    //     但不在当日起始本金快照里 ⇒ 当天不计息（与借粮同口径，见 principalAtDayStart）。
    capitalizeArrears(
        ledger, rows, debts, householdOfActor, capitalizedArrearsToday, day, dueCycle);

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
            rows,
            householdGoods,
            householdMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            operatorGoods,
            operatorMoney,
            operatorFrozenGoods,
            operatorFrozenMoney,
            unmetToday,
            householdOfActor,
            industries,
            units,
            assetShares,
            relations,
            allocations,
            shipments,
            ledger,
            operatorConditions,
            settlementIndex,
            // ★★ R4-E2：当日有效需求来自状态组件的只读账本（订单路径据此把"生活保留基线 + 需求目标"合成买卖目标）。
            base.demands());
    MarketTrigger marketTrigger =
        MarketSettlement.triggerFor(day, anyCycleClosed, markets, marketRound);
    if (marketTrigger != MarketTrigger.NONE) {
      MarketSettlement.MarketOutcome outcome =
          MarketSettlement.clearOncePerCycle(
              markets, marketRound, marketTrigger, topology, parallelism);
      // ★ L2 只把报告留给 L3 的读数组件（不落盘）；不聚合丢失（见 MarketReport 的类注）。
      ledger.recordMarketReport(outcome.report());
      // ★★ S3 修复：把本轮的逐卖方证据累加进经营者条件的"本周期累计"字段。一个周期有多轮市场，关账日那轮
      //   很可能已经看不到更早轮里的滞销/被挤出 ⇒ 不在这里累加，状态机的连续计数就永远不涨。
      OperatorSettlement.accumulateMarketEvidence(
          operatorConditions, units, industries, outcome.report());
      // ★★ M2.6：自适应模式把价格表工作副本换成 outcome 交回的新表（默认固定模式下两者逐值相同 ⇒ 无状态变化）。
      markets = new LinkedHashMap<>(outcome.markets());
    }

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
      // ★★ E4b：信用额度 = DebtCapacity.headroom（唯一算法在 DebtCapacityBook）。三个流量按**本周期终态窗口**取值：
      //   本周期已实现粮所得 = 上周期末流水余量（新周期翻篇 = 0）+ 今日 earned；consumed 同窗口；taxPaid 照读；
      //   库存取"到此刻为止"的会话工作副本（关账日收获/分配已在上面发生、市场也已结清）。
      Map<HouseholdId, DebtCapacity> debtCapacities =
          debtCapacitiesForDay(
              rows,
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
              operatorConditions);
      lendDeficitsPartitioned(
          rows,
          debts,
          accounts,
          consumedGoods,
          borrowing,
          unmetToday,
          deficitToday,
          dueCycle,
          cycleDaysByHousehold,
          debtCapacities,
          householdOfActor,
          ledger,
          day,
          parallelism);
    }

    // ── 4c. 偿还（★ R3 续修：所得到账后、计息前，按可用粮先还本；E4c 扩成粮→其它商品→货币）──────
    //   ★★ 它挂在"今天有产业关账"上（利息／市场的同一处日级事实）：所得是**整周期**结算出来的
    //     （收获与分配在本步之前刚发生）⇒ 还债的时点就是所得到手的那一刻。
    //   ★ E4c 的上限：粮债 = max(0, 粮库存 − 冻结 − 一日口粮)；其它商品 = max(0, 该商品库存 − 冻结)；
    //     货币债 = max(0, 该币种余额 − 冻结)。全部**不得越过冻结**，且余额扣不成负（与唯一 applier 同口径，
    //     见 {@link #repayDebts}）。
    if (anyCycleClosed) {
      repayDebts(
          rows,
          debts,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          operatorGoods,
          operatorMoney,
          operatorFrozenGoods,
          operatorFrozenMoney,
          repaidToday,
          repaidMoneyToday,
          repaidPrincipalByDebt,
          householdOfActor,
          ledger,
          issuanceJournal);
    }

    // ── 4d. 饿死判据（★ H5：**所有救济通道之后** —— 市场（4）→ 借（4b）→ 还（4c）之后才判）────────────
    //   ★★ 口径与改前**逐值相同**：改前它排在借粮之后（只是那时借粮在收获之前）；本步把它排到市场与借粮之后 ⇒
    //     "借到粮的人不按缺口去死"这条语义一个字没变，而"关账日的集市买到的粮也算救济"这条**新**口径进来了。
    //   ★ 人口减少后劳动按同比例缩（`applyFamine` 缩**行**劳动 + 本步缩该产业名下的**全部配额**与对应批次的供给）。
    for (ClosedUnit closing : closed) {
      long populationBefore = 0L;
      for (HouseholdId key : closing.keys()) {
        populationBefore += rows.get(key).population();
      }
      for (HouseholdId key : closing.keys()) {
        long carried =
            newCycleUnits.contains(closing.unit()) || flows.get(key) == null
                ? 0L
                : flows.get(key).unmetNeed().getOrDefault(GRAIN, 0L);
        ClassRow beforeFamineRow = rows.get(key);
        long populationBeforeFamine = beforeFamineRow == null ? 0L : beforeFamineRow.population();
        applyFamine(
            rows,
            deathsToday,
            key,
            rows.get(key),
            carried + unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L),
            day,
            closing.cycleDays(),
            famineMortalityPerMille);
        ClassRow afterFamineRow = rows.get(key);
        if (afterFamineRow != null) {
          // ★ S1：饿死的人同步减少成员份额（Σ Membership == 行人口）。
          scaleMembershipsOfHousehold(
              memberships, key, populationBeforeFamine, afterFamineRow.population());
        }
      }
      long populationAfter = 0L;
      for (HouseholdId key : closing.keys()) {
        populationAfter += rows.get(key).population();
      }
      // ★★ **R4：饿死之后劳动按同比例缩 —— 而且这次真的缩到配额上**（R2 如实记下的那条旧账：
      //   `applyFamine` 缩的是**行**劳动，而当日劳动自 R2 起取自**劳动分配表** ⇒ "人死了劳动没减"）。
      if (populationAfter < populationBefore) {
        scaleLaborOfUnit(
            closing.unit(),
            populationBefore,
            populationAfter,
            allocations,
            laborSupply,
            settlementIndex);
      }
    }

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    //   ★ 次序：**偿还先于计息**（还掉的那部分本金不再生息 —— 这是"先还后计"的标准序，也是改后债务曲线下降的一半原因）。
    if (anyCycleClosed) {
      chargeInterest(debts, principalAtDayStart, interestToday, day);
    }

    // ── 5b. S3 经营者状态机（可观察量 → IndustryStatus；退出处置先偿债、不足才 defaulted）──────────
    //   ★ 只在关账日前进，而且只更新**本日关账**的产业：每个产业的连续计数以它自己的关账周期为单位，
    //     未关账产业的周期证据不能被别人关账时顺手消费掉。
    //   ★ 市场证据取的是**本周期累计**（每轮市场结束后累加到 OperatorCondition.cycle*），不再要求 evidence.day == day；
    //     若本周期一个市场轮都没有（cycleMarketRounds == 0），连续计数保持、不用 0 覆盖。
    //   ★ 缩产/停业只改"计划规模系数"（StressPolicy），capacity 与 AssetShare 一字不动。
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
              rows,
              stateIndex,
              householdGoods,
              householdMoney,
              operatorGoods,
              operatorMoney,
              markets,
              closingUnits,
              shortfallByUnit);
      if (!exits.isEmpty()) {
        // ★★ E1：退出事实处置（劳动释放 → 资产退回/留 owner → 债务偿还/违约 → 状态与理由）。
        settleOperatorExits(
            exits,
            operatorConditions,
            assetShares,
            allocations,
            debts,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            householdOfActor,
            stateIndex,
            ledger,
            day,
            issuanceJournal);
        // ★ 资产 operator / 劳动配额刚被改写 ⇒ 换一份索引，后面的阶层写回（读 unit 可用资产）不拿旧快照。
        //   这是**退出日的一次重建**（O(unit + 份额 + 配额)），不是逐查询重扫；退出本身是低频事件。
        settlementIndex =
            SettlementIndex.build(
                units, industries, assetShares, allocations, rows, debts, relations);
      }
    }

    // ── 5b.5. ★★ E5b：清算 + 阶层下滑 + hex 危机信号（关账日、退出处置之后、阶层写回之前）────────
    //   ★ 位置理由：债务结算（4c 偿还 / 5 计息 / 5b 退出处置）已经结束 ⇒ 这里只对"此刻本金 > 0"的合同选路；
    //     5b 已结清的合同本金为 0，天然不会被二次处置（去重口径见 EconomyLiquidationSettlement 类注）。
    //   ★ 只在关账日推进（与 5 计息、5c 阶层写回同窗口）；新表全空 = 旧档 ⇒ 整段 no-op，旧路径逐值不变。
    //   ★ F/headroom 用 E4b 的唯一算法（DebtCapacityBook）；容量按**当刻债务终态**重算
    //     （4b 的容量是借粮/偿还之前的口径，不能用它判"本期利息超 F"）。
    if (anyCycleClosed && EconomyLiquidationSettlement.isActive(base)) {
      Map<HouseholdId, DebtCapacity> closeDebtCapacities =
          debtCapacitiesForDay(
              rows,
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
              operatorConditions);
      EconomyLiquidationSettlement.settle(
          session,
          day,
          currentCycle,
          rows,
          assetShares,
          session.sheet().pledges(),
          debts,
          principalAtDayStart,
          repaidPrincipalByDebt,
          closeDebtCapacities,
          industries,
          ledger);
    }

    // ── 5c. S3 阶层写回（关账日、经营者状态机之后）────────────────────────────────────────
    //   ★ 只改 ClassRow.view：稳定 HouseholdId、actor/账户/债务/劳动配额/人口/成员份额一字不动（withView 的方法承诺）。
    //   ★ E5b：有 ClassStanding 的家户以 standing 为权威 —— 把 currentPositionId 投影回 view.stratum；
    //     投影不到的非 legacy 位置保留旧 view 并写具名审计，**不**把该户再交给 HouseholdClassRule（禁止双真相）。
    //   ★ 没有 standing 的旧档才继续走 HouseholdClassRule（投影不到 = 有显式归属但表达不了，不是"退回旧分类器"）。
    //   ★ 分类用本日 ledger（关账日 = 本周期收获/分配的结算账本，租金实付是整周期口径）；读不到时不填 0。
    //   ★ 原四档允许跳变；这里不新增人口、不改任何守恒量。
    if (anyCycleClosed) {
      ProductionLedger classLedger = ledger.toLedger();
      Map<HouseholdId, ClassStanding> classStandings = session.sheet().classStandingsOrBase();
      HouseholdClassRule.Index classIndex =
          HouseholdClassRule.Index.of(
              assetShares, allocations, units, industries, relations, rows, debts, settlementIndex);
      List<ClassTransition> classTransitions = new ArrayList<>();
      for (HouseholdId key : new ArrayList<>(rows.keySet())) {
        ClassRow row = rows.get(key);
        if (row == null) {
          continue;
        }
        ClassStanding standing = classStandings.get(key);
        SocialClassId derived;
        String reason;
        if (standing != null) {
          Optional<SocialClassId> projected =
              LegacyClassStructure.socialClassOf(standing.currentPositionId());
          if (projected.isEmpty()) {
            EconomyLiquidationSettlement.recordClassProjectionFallback(
                ledger, day, key, standing.currentPositionId(), row.view().stratum());
            continue; // ★ 保留旧 view，不改旧权威也不另造一个投影
          }
          derived = projected.get();
          reason = "classStanding-authority:" + standing.currentPositionId().value();
        } else {
          HouseholdClassRule.Classification classification =
              classIndex.classify(key, Optional.of(classLedger));
          derived = classification.stratum();
          reason = classification.reason();
        }
        if (derived.equals(row.view().stratum())) {
          continue;
        }
        ClassTransition transition =
            new ClassTransition(day, key, row.view().stratum(), derived, reason);
        rows.put(
            key, row.withView(new CohortKey(row.view().hex(), row.view().residence(), derived)));
        classTransitions.add(transition);
      }
      // ★ 具名审计读数：即使没有写回也投递空表（读口才分得清"这次关账没有变化"与"没读到"）。
      ClassTransitionFeed.publish(meta.mapId(), day, classTransitions);
    }

    // ── 流水：每行一条（本期发生额；税 v1 恒 0、利息见上一步）────────────────────────────
    for (Map.Entry<HouseholdId, ClassRow> rowEntry : rows.entrySet()) {
      HouseholdId key = rowEntry.getKey();
      // ★★ **M2.7（丙条仪器）：周期累加器的清零点与流水同一天** —— 新周期第一天把
      //   {@code cycleNaturalNeedMilli} 重置为"今天这一份"（{@code withDailyNeed} 在消费步刚累加过）。
      //   ★ 放在这里而不是 {@code withDailyNeed} 里：只有这里同时看得见 {@code newCycleHouseholds}
      //     （由产业 progressDays 推、且与 FlowRow 的清零同一判据）。重置为"当天那一份"而不是 0 —— 理由见
      //     {@link #withCycleNaturalNeed}。
      if (newCycleHouseholds.contains(key)) {
        ClassRow cycleRow = rowEntry.getValue();
        if (cycleRow != null) {
          rowEntry.setValue(
              withCycleNaturalNeed(cycleRow, cycleRow.naturalNeeds().getOrDefault(GRAIN, 0L)));
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
        session, day, accounts, topology, plantingDrawsFirst, famineMortalityPerMille, ledger);
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
      Map<HouseholdId, ClassRow> rowUpdates,
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
      LinkedHashMap<HouseholdId, ClassRow> rows,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<HouseholdId, Long> deficitToday,
      long day,
      EconomyParallelism parallelism) {
    Map<String, List<HouseholdId>> hexToRows = rowsByHex(rows);
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
              LinkedHashMap<HouseholdId, ClassRow> rowUpdates = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmet = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Long> deficits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (HouseholdId key : hexToRows.get(hex)) {
                  consumeOneHousehold(
                      rows, tables.householdGoods, key, day, rowUpdates, consumed, unmet, deficits);
                }
              }
              return new ConsumptionPartition(
                  buffer.drainIntents(), rowUpdates, consumed, unmet, deficits);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (ConsumptionPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (ConsumptionPartition partition : partitions) {
      for (Map.Entry<HouseholdId, ClassRow> update : partition.rowUpdates().entrySet()) {
        rows.put(update.getKey(), update.getValue());
      }
      mergeGoodsInto(consumedGoods, partition.consumed());
      mergeGoodsInto(unmetNeed, partition.unmet());
      for (Map.Entry<HouseholdId, Long> deficit : partition.deficits().entrySet()) {
        deficitToday.merge(deficit.getKey(), deficit.getValue(), Long::sum);
      }
    }
  }

  /** 消费一户（{@link #consumeOwnStockPartitioned} 的逐户版；读共享行/意向视图，写本地累加器）。 */
  private static void consumeOneHousehold(
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      long day,
      Map<HouseholdId, ClassRow> rowUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      Map<HouseholdId, Long> deficitToday) {
    ClassRow row = withDailyNeed(rows.get(key), day);
    rowUpdates.put(key, row);
    if (row.population() <= 0L) {
      return;
    }
    long need = row.naturalNeeds().getOrDefault(GRAIN, 0L);
    long stock = stockOf(householdGoods, key, GRAIN);
    long eaten = Math.min(stock, need);
    setStock(householdGoods, key, GRAIN, stock - eaten);
    addGoods(consumedGoods, key, GRAIN, eaten);
    long clothNeed = row.naturalNeeds().getOrDefault(CLOTH, 0L);
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
      Map<ProductionUnitId, ProductionUnit> units,
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
    LinkedHashMap<ProductionUnitId, ProductionUnit> units = session.sheet().units();
    // ★★ R3B.2：产业表只读（模板；日结算不再改它）。
    Map<IndustryId, Industry> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
    // ★★ R4-E2b：走 relation 的**工作副本选择**（进入执行可能刚插入新 unit 的 relation；未物化时等于 base）。
    Map<ProductionUnitId, ProductionRelation> relations = session.sheet().relationsOrBase();
    // ★ S3：缩产/停业后的"计划规模"要进投入调查（条件缺失 ⇒ 系数 1000‰ ⇒ 旧行为逐值相同）。
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, LinkedHashMap<ProductionUnitId, ProductionUnit>> byHex = new TreeMap<>();
    List<ProductionUnitId> noHex = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, ProductionUnit> entry : units.entrySet()) {
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
                LinkedHashMap<ProductionUnitId, ProductionUnit> localUnits = new LinkedHashMap<>();
                for (String hex : partition.canonicalKeys()) {
                  localUnits.putAll(byHex.get(hex));
                }
                LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
                ProductionLedger.Accumulator partitionLedger =
                    new ProductionLedger.Accumulator(day, partition.partitionIndex());
                drawCycleInputs(
                    localUnits,
                    industries,
                    rows,
                    relations,
                    operatorConditions,
                    index,
                    tables.householdGoods,
                    tables.householdMoney,
                    tables.operatorGoods,
                    tables.operatorMoney,
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
        for (Map.Entry<ProductionUnitId, ProductionUnit> update : partition.units().entrySet()) {
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
      LinkedHashMap<ProductionUnitId, ProductionUnit> local = new LinkedHashMap<>();
      for (ProductionUnitId id : noHex) {
        local.put(id, units.get(id));
      }
      drawCycleInputs(
          local,
          industries,
          rows,
          relations,
          operatorConditions,
          index,
          accounts.householdGoods(),
          accounts.householdMoney(),
          accounts.operatorGoods(),
          accounts.operatorMoney(),
          consumedGoods,
          householdOfActor,
          ledger);
      for (Map.Entry<ProductionUnitId, ProductionUnit> update : local.entrySet()) {
        units.put(update.getKey(), update.getValue());
      }
    }
  }

  /** 劳动再分配阶段一个分区的产出（本地配额表 + 该分区 seed 过的键，用于识别"整条回池"的删除）。 */
  private record LaborPartition(
      Map<LaborAllocationId, LaborAllocation> allocations, Set<LaborAllocationId> seededKeys) {}

  /**
   * ★★ <b>R4-E2b：这个 unit 是否由候选预设进入</b>——旧档/主副 unit 的 {@code modeKey == industry.id()}（世界播种与
   * 旧档迁移的不变式），候选 unit 的 {@code modeKey == candidateId@version}（见 {@code ProductionUnit} 类注）。 候选
   * unit 的 {@code cycleDays} 可以与同格旧产业不同，故它自己的周期边界不应当触发整格重排。
   */
  private static boolean isPresetOrigin(ProductionUnit unit) {
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
    LinkedHashMap<ProductionUnitId, ProductionUnit> units = session.sheet().units();
    Map<IndustryId, Industry> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations = session.sheet().allocations();
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, List<ProductionUnitId>> unitsByHex = new TreeMap<>();
    for (ProductionUnit unit : units.values()) {
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
        ProductionUnit unit = units.get(id);
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
              LinkedHashMap<ProductionUnitId, ProductionUnit> localUnits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (ProductionUnitId id : active.get(hex)) {
                  localUnits.put(id, units.get(id));
                }
              }
              LinkedHashMap<LaborAllocationId, LaborAllocation> localAllocations =
                  new LinkedHashMap<>();
              Set<LaborAllocationId> seeded = new LinkedHashSet<>();
              for (LaborAllocation allocation : allocations.values()) {
                if (localUnits.containsKey(new ProductionUnitId(allocation.activity()))) {
                  localAllocations.put(allocation.id(), allocation);
                  seeded.add(allocation.id());
                }
              }
              reallocateLabor(
                  localUnits, industries, rows, localAllocations, operatorConditions, index);
              return new LaborPartition(localAllocations, seeded);
            },
            parallelism.poolOrNull());
    for (LaborPartition partition : partitions) {
      for (LaborAllocationId id : partition.seededKeys()) {
        if (!partition.allocations().containsKey(id)) {
          allocations.remove(id); // 整条回池（原算法 remove；本地副本里没有它）
        }
      }
      allocations.putAll(partition.allocations());
    }
  }

  /** 收获阶段的一条工作单元（关账前的 unit/模板快照 + 本周期劳动 + 所在格 + 原遍历序）。 */
  private record HarvestWork(
      ProductionUnit unit,
      Industry industry,
      long cycledLabor,
      HexCoord location,
      int order,
      long plannedPerMille) {}

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
      LinkedHashMap<HouseholdId, ClassRow> rows,
      SettlementIndex index,
      Map<ProductionUnitId, ProductionRelation> relations,
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
                      rows,
                      work.cycledLabor(),
                      work.plannedPerMille(),
                      index,
                      localIncome,
                      tables.householdGoods,
                      tables.householdMoney,
                      tables.operatorGoods,
                      tables.operatorMoney,
                      relations,
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
      Map<HouseholdId, ClassRow> rowUpdates,
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
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<HouseholdId, Long> borrowing,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<HouseholdId, Long> deficitToday,
      long dueCycle,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day,
      EconomyParallelism parallelism) {
    Map<String, List<HouseholdId>> hexToRows = rowsByHex(rows);
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
              LinkedHashMap<HouseholdId, ClassRow> rowUpdates = new LinkedHashMap<>();
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
                    rows,
                    rowUpdates,
                    localDebts,
                    tables.householdGoods,
                    tables.householdMoney,
                    tables.operatorGoods,
                    tables.operatorMoney,
                    consumed,
                    localBorrowing,
                    localUnmet,
                    hexDeficit,
                    day,
                    dueCycle,
                    cycleDaysByHousehold,
                    debtCapacities,
                    householdOfActor,
                    partitionLedger);
              }
              return new LendingPartition(
                  buffer.drainIntents(),
                  localDebts,
                  rowUpdates,
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
      rows.putAll(partition.rowUpdates());
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
   * </pre>
   *
   * <p>★★ **为什么经济侧必须跟着动**（而不是"人死了只在社会侧少几个人"）：行人口是**口粮需求**与**分配权重**的来源 （{@code
   * dailyRationMilli(row.population, day)}）⇒ 不回写就会得到"人已经死了、饭还照吃"这种更糟的账。
   *
   * <p>★★ **为什么配额要按 #③ 缩两次也不同**：{@code applyFamine}（直接按缺口处死的那条路径）缩的是**产业**那一侧， 本步缩的是**批次**那一侧 ——
   * 两条路径各自知道自己死了谁，各自缩自己那份账。两者都落在同一条不变量上 （{@code Σ allocated ≤ available}）。
   *
   * <p>★ **没有劳动配额的批次摊不出去**（{@code industriesOf} 里没有它）：本轮的世界里创世给每个批次都发了配额 （{@code
   * EconomySeeder}），故这条路径只服务"手工搭的、没有配额的状态"——那种状态本来也没有可缩的劳动。
   *
   * @param changes 逐批次的出生/死亡（social 侧的月度结算产物；键 = 批次身份）
   */
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
    EconomyData base = session.base();
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations = session.sheet().allocations();
    LinkedHashMap<PeopleLotId, LaborSupply> laborSupply = session.sheet().laborSupply();
    // ★ S1：成员份额与资产份额的工作副本（死亡/人口回写要缩成员份额；资产份额本步原样带过，
    //   但日结算必须交出**同一份**状态，不能让两个组件在终态里漂开）。
    LinkedHashMap<MembershipId, Membership> memberships = session.sheet().memberships();
    LinkedHashMap<AssetShareId, AssetShare> assetShares = session.sheet().assetShares();
    // ★★ R4-B.3a-perf：人口回写是“逐 LotChange 摊到目标家户”，旧实现对每条 change 扫全量配额
    //   （真档月度 6,000+ 条 × 44,000+ 配额）。这里在入口建一次只读索引：批次→unit、格→unit、
    //   unit→配额，随后每条 change 只碰与自己目标集合相关的行。
    SettlementIndex index =
        SettlementIndex.build(
            session.sheet().units(),
            session.sheet().industries(),
            assetShares,
            allocations,
            rows,
            null,
            // ★★ R4-E2b：同一会话里 settleOneDayInto 可能刚由进入执行插入新 unit 的 relation ⇒ 读工作副本选择，
            //   不再直读 base.relations()（否则这一次人口回写的经济家户索引会漏掉刚建的 unit）。
            session.sheet().relationsOrBase());
    // 批次 → 它供给的 unit（保序、去重；只认 activity 命中现存 unit 的配额）。
    Map<ProductionUnitId, ProductionUnit> units = session.sheet().units();
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
      // ★★ **H0/S3：这批人的家户行 = 供给目标产业的配额里、居住类型匹配的 LaborAllocation.household 并集**，
      //   **并集去重** —— 不按四阶层枚举、也不读 ClassRow.view.stratum（阶层写回后仍不漏行/错行）。
      //   农村批次同时供给农业与家庭纺织（两者落在**同一批农村家户行**上）⇒ 不去重就会把它的人与生死**算两遍**。
      List<HouseholdId> keys =
          householdKeysOfLot(rows, targets, ResidenceKind.ofLot(change.group()), index);
      if (keys.isEmpty()) {
        continue; // 那些产业在这一格没有家户行（行还没种下）⇒ 没有可摊的行
      }
      long[] rowPopulations = new long[keys.size()];
      long populationBefore = 0L;
      for (int j = 0; j < keys.size(); j++) {
        rowPopulations[j] = rows.get(keys.get(j)).population();
        populationBefore += rowPopulations[j];
      }
      long[] birthsParts = allocate(change.births(), rowPopulations);
      long[] deathsParts = allocate(change.deaths(), rowPopulations);
      for (int j = 0; j < keys.size(); j++) {
        HouseholdId key = keys.get(j);
        ClassRow row = rows.get(key);
        long population = row.population();
        if (population <= 0L) {
          continue;
        }
        long remaining = population - deathsParts[j]; // deathsParts ≤ row 人口（按人口权重切，见 allocate）
        long labor = row.laborMilli() * remaining / population; // 死亡同比例缩；出生不加劳动
        rows.put(key, withPopulationAndLabor(row, remaining + birthsParts[j], Math.max(0L, labor)));
        if (birthsParts[j] != 0L || deathsParts[j] != 0L) {
          flows.put(key, withLifecycle(flows.get(key), key, birthsParts[j], deathsParts[j]));
        }
      }
      scaleLaborOfGroup(
          change.group(),
          populationBefore,
          populationBefore - change.deaths(),
          allocations,
          laborSupply,
          index);
      // ★ S1.4.1：同一份出生/死亡按**份额权重**摊回该批次的成员份额（Σ Membership == 行人口 的守恒落点）。
      //   这份实现保的是 economy 内部守恒；social 把新生儿放进新的 born lot ⇒ app 协调器随后用
      //   {@code MembershipWriteback.reconcile} 把多计的 births 从旧 lot 移到新生 lot（跨切片对账）。
      applyMembershipChange(memberships, change.group(), change.births(), change.deaths());
    }
    // ★ 工作表与流水都在 session 里就地更新；构造与全量守卫由 revision 边界（build）负责（P1.5a）。
  }

  /**
   * ★★ <b>S1.4.1：把一个批次的出生/死亡摊回它的成员份额</b>（最大余数法、同余按 HouseholdId 字典序）。
   *
   * <pre>
   * before = Σ count(lot)      after = max(0, before + births − deaths)
   * parts  = ProportionalSplit.byDenominator(after, 各份额 count 为权重, before)
   * </pre>
   *
   * <p>★★ <b>两级分工</b>：本方法只保 economy 切片内部守恒（{@code Σ Membership == Σ ClassRow.population}，行侧同样
   * +births −deaths）；而 social 的月度结算把新生儿放进**新的 born lot**（本 lot 的社会人数只减死亡）⇒ app 协调器用 {@code
   * MembershipWriteback.reconcile} 把本方法多计的 births 从旧 lot **移到**新生 lot，然后逐 lot 硬校验。
   *
   * <p>★ <b>没有份额的批次</b>：本方法如实返回（不改表），不凭空造家户 —— 份额的建立属分配/迁移路径。
   */
  private static void applyMembershipChange(
      LinkedHashMap<MembershipId, Membership> memberships,
      PeopleLotId group,
      long births,
      long deaths) {
    if (births == 0L && deaths == 0L) {
      return;
    }
    List<Membership> members = new ArrayList<>();
    for (Membership membership : memberships.values()) {
      if (membership.lot().equals(group)) {
        members.add(membership);
      }
    }
    if (members.isEmpty()) {
      return; // 新批次/未建份额：交由协调器建份额（不猜一个家户收下全部新生儿）
    }
    members.sort(Comparator.comparing(membership -> membership.household().value()));
    long before = 0L;
    for (Membership membership : members) {
      before = Math.addExact(before, membership.count());
    }
    long after = Math.max(0L, before + births - deaths);
    long[] weights = new long[members.size()];
    long weightSum = 0L;
    for (int i = 0; i < members.size(); i++) {
      weights[i] = members.get(i).count();
      weightSum = Math.addExact(weightSum, weights[i]);
    }
    long[] parts;
    if (weightSum == 0L) {
      // 全部是 0 份额（空壳家户）而 still 有变动：均分（1 人权重）—— 否则会把新生儿静默丢掉。
      long[] equal = new long[members.size()];
      for (int i = 0; i < equal.length; i++) {
        equal[i] = 1L;
      }
      parts = ProportionalSplit.byDenominator(after, equal, equal.length);
    } else {
      parts = ProportionalSplit.byDenominator(after, weights, weightSum);
    }
    for (int i = 0; i < members.size(); i++) {
      Membership membership = members.get(i);
      memberships.put(
          membership.id(),
          new Membership(membership.id(), membership.lot(), membership.household(), parts[i]));
    }
  }

  /**
   * ★★ <b>S1：某个家户因饿死而减少人口时，按份额权重缩它的成员份额</b>（Σ Membership == 行人口 的守恒落点）。
   *
   * <p>★ 与 {@link #applyMembershipChange} 的分工：那条按**批次**摊（social 的月度账），本方法按**家户**摊 （{@code
   * applyFamine} 直接处死的那一条路，死的是这个家户的人）。
   */
  private static void scaleMembershipsOfHousehold(
      LinkedHashMap<MembershipId, Membership> memberships,
      HouseholdId household,
      long populationBefore,
      long populationAfter) {
    if (populationAfter >= populationBefore || populationBefore <= 0L) {
      return;
    }
    List<Membership> members = new ArrayList<>();
    for (Membership membership : memberships.values()) {
      if (membership.household().equals(household)) {
        members.add(membership);
      }
    }
    if (members.isEmpty()) {
      return;
    }
    members.sort(Comparator.comparing(membership -> membership.lot().value()));
    long sum = 0L;
    for (Membership membership : members) {
      sum = Math.addExact(sum, membership.count());
    }
    if (sum == 0L) {
      return;
    }
    long died = populationBefore - populationAfter;
    long deathsForMembers = Math.min(sum, died);
    long[] weights = new long[members.size()];
    for (int i = 0; i < members.size(); i++) {
      weights[i] = members.get(i).count();
    }
    long[] deathsParts = ProportionalSplit.byDenominator(deathsForMembers, weights, sum);
    for (int i = 0; i < members.size(); i++) {
      Membership membership = members.get(i);
      long next = Math.max(0L, membership.count() - deathsParts[i]);
      memberships.put(
          membership.id(),
          new Membership(membership.id(), membership.lot(), membership.household(), next));
    }
  }

  /**
   * ★★ **某一格的全部产业**（保序：产业表的插入序；无则空表）。
   *
   * <p>★★ **两处共用**（{@code applyPopulationChange} 与 {@code
   * io.mosire.simos.app.time.PopulationEconomyTimeParticipant#applyDailyStress}）——
   * 抽成一个方法是因为"**没有配额的批次该按哪一格算**"这个问题**只能有一个答案**， 两处各写一遍必然漂（S1 spec §十 的原文）。
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

  /** 同 {@link #industriesByHex(EconomyData)}，但吃**产业表本身**（日结算的工作副本不是 {@code EconomyData}）。 */
  private static Map<String, List<IndustryId>> industriesByHexMap(
      Map<IndustryId, Industry> industries) {
    Map<String, List<IndustryId>> byHex = new LinkedHashMap<>();
    for (IndustryId id : industries.keySet()) {
      IndustryHexKeys.hexKeyOf(id)
          .ifPresent(hex -> byHex.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(id));
    }
    return byHex;
  }

  /**
   * ★★ **按格索引全部产业**（{@code q_r → 产业表}，保序：产业表的插入序）。
   *
   * <p>★★ **它是"某一格有哪些产业"这件事的唯一算法** —— {@link #industriesAt} 也从它取， 故两处（以及将来的第三处）不可能给出不同答案。
   *
   * <p>★ **为什么要单独暴露它**：调用方 {@code PopulationEconomyTimeParticipant#applyDailyStress} 在**逐日 ×
   * 逐批次**的热路径上需要它 —— 没有配额的批次（0-14 档 + 全部新生儿）**每一个**都要 走兜底，而它们的数量随新生批次**逐月累积**（真档实测 ≈ 6,263
   * 个）。在那儿每次全表扫产业会 多出一项 O(批次 × 产业)；**建一次索引**就没有这一项。
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
   * @param before 饿死前该产业的行人口之和；必须 &gt; 0（为 0 时没有可缩的东西，调用方先挡）
   */
  private static void scaleLaborOfUnit(
      ProductionUnitId unitId,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<PeopleLotId, LaborSupply> laborSupply,
      SettlementIndex index) {
    if (before <= 0L || after >= before) {
      return;
    }
    // ★ R4-B.3a-perf：按 unit 的配额 id 列表查活表（旧实现每个关账 unit 扫全量配额）。
    for (LaborAllocationId allocationId : index.allocationIdsOfUnit(unitId)) {
      LaborAllocation allocation = allocations.get(allocationId);
      if (allocation == null) {
        continue; // 索引与活表同源；这里只防御中途被移除
      }
      long scaled = allocation.laborMilli() * after / before;
      allocations.put(
          allocationId,
          new LaborAllocation(
              allocation.id(),
              allocation.group(),
              allocation.household(),
              allocation.actor(),
              allocation.activity(),
              scaled,
              allocation.period()));
      scaleSupply(allocation.group(), after, before, laborSupply);
    }
  }

  /**
   * ★★ **把一个批次在两个产业上的配额按"该批次的存活比例"缩**（R4 的出生/死亡回写路径用；与 {@link #scaleLaborOfIndustry}
   * 同一条口径，只是键换成了批次）。
   *
   * <p>★ 人口真值源在 social ⇒ 本步的**唯一输入**是一份 {@code group → (出生, 死亡)} 的账（见 {@link
   * #applyPopulationChange}）： economy 不需要认识 {@code PopulationGroup}，只需要它的稳定身份。
   */
  private static void scaleLaborOfGroup(
      PeopleLotId group,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<PeopleLotId, LaborSupply> laborSupply,
      SettlementIndex index) {
    if (before <= 0L || after >= before) {
      return;
    }
    // ★ R4-B.3a-perf：按批次查配额 id（旧实现每条 LotChange 扫全量配额）；id 回活表取当前 record，
    //   故同一次回写里前面的缩放不会被缓存的旧值覆盖。
    for (LaborAllocationId allocationId : index.allocationIdsOfGroup(group)) {
      LaborAllocation allocation = allocations.get(allocationId);
      if (allocation == null) {
        continue; // 索引与活表同源；这里只防御中途被移除
      }
      long scaled = allocation.laborMilli() * after / before;
      allocations.put(
          allocationId,
          new LaborAllocation(
              allocation.id(),
              allocation.group(),
              allocation.household(),
              allocation.actor(),
              allocation.activity(),
              scaled,
              allocation.period()));
    }
    scaleSupply(group, after, before, laborSupply);
  }

  /** 一个批次的劳动供给毛额按存活比例缩（{@code served}/{@code committed} 原样带过：它们与人口无关）。 */
  private static void scaleSupply(
      PeopleLotId group,
      long after,
      long before,
      LinkedHashMap<PeopleLotId, LaborSupply> laborSupply) {
    LaborSupply supply = laborSupply.get(group);
    if (supply == null) {
      return;
    }
    laborSupply.put(
        group,
        new LaborSupply(
            supply.group(),
            supply.period(),
            supply.grossLaborMilli() * after / before,
            supply.servedLaborMilli(),
            supply.committedLaborMilli()));
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
   * ProductionRelation#inputSupplier()}），需求是<b>整个产业</b>的，逐户只做一次"按人口占比 + 最大余数法"的分派 （Σ分派 ==
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
   *       {@code farm@0_0}）：<b>economy 看不见它的账</b> —— 那本账住在 actor 切片的 {@code GoodsAccount} 上（键
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
   * {@link #scaleOf} 里照旧生效。
   *
   * <p>★★ <b>落账（H3：两个主体都在家户账里时走转移）</b>—— 见 {@link #recordInputDraw}：
   *
   * <pre>
   * 供方 ≠ 经营者，且经营者的账在会话副本里（"地主出种"那类）⇒ 一条 INPUT_REQUISITION 转移（供方 → 经营者），
   *   由**唯一** applier（{@link #applyTransferToHouseholds}）落到副本上（供方 −、经营者 +），随后**在经营者账上消费掉**
   *   这一笔（{@code consumed} 记在经营者：它是这批料的消费者）⇒ 净额：供方 −drawn、经营者 0；
   * 其余情形（供方 == 经营者；或经营者的账不在副本里 —— 聚合主体）⇒ **不产生转移**：投入是"被生产直接吃掉"的，
   *   按 H2 的既有口径记账（供方 −余额、{@code consumed} 记在供方、{@code ledger.addInput}）。
   *   ★ 理由（有意的边界）：转移的收端若是 economy **看不见**的账，那条腿会落在 actor 侧而**没有任何一处把它消费掉**
   *   （账面上"料收到了、却永远不 consumed"）⇒ 宁可不写那条转移，也不留一本对不上的账。
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
   * ① 调查（只读）：逐产业算出"它能取的那些账"（自己看得见的经营者账 + relation 名下的家户账）与逐商品的可供量
   * ② 争用     ：★ **同一格里 ≥2 个产业要同一种投入** ⇒ 这一种投入在本格是一个**池子**
   *              池 = 这些需求方各自的账之**并集**（关系仍是"谁能进池"的权威：池里只有被 relation 指名过的账）
   *              逐需求方的可供量 := 它自己那本经营者账 + **整个池子**        （⇒ 规模可以按池子算）
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
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    // ── 第一遍：调查（**只读**：不改任何账）────────────────────────────────────────────
    List<InputPlan> plans =
        surveyInputDemands(
            units,
            industries,
            rows,
            relations,
            operatorConditions,
            index,
            householdGoods,
            operatorGoods,
            householdOfActor);
    // ── 第二遍：同格争用 ⇒ 开池 + 按需求比例配给（H6-lite；无争用 ⇒ 一个字段都不动）────────
    rationContestedInputs(plans, householdGoods, operatorGoods);
    // ── 第三遍：逐 unit 落账（需求上限 = 配给额；无争用 ⇒ 就是它自己算出来的 need）──────────
    for (InputPlan plan : plans) {
      drawOneProductionUnitInputs(
          plan,
          units,
          rows,
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
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
    private final ProductionUnit unit;

    /** 技术模板（只读：inputPerUnit / cycleDays）。 */
    private final Industry industry;

    /** 本格（unit.industry 解析出来的那一格；"同格争用"的分组键）。★ 产业 id 里没有格键 ⇒ null（它不属于任何一格 ⇒ 不参与争用）。 */
    private final HexCoord hex;

    /** 逐单位规模要的商品（= {@code industry.inputPerUnit()}；只读）。 */
    private final Map<CommodityId, Long> perScale;

    /** 产能折出的规模（= {@link ProductionUnitBook#plannedCapacityScaleOf}；**不含投入那一路** —— 那是"可供量"的事）。 */
    private final long capacityScale;

    /** 供方是聚合主体时它自己的 actor（家户供方 ⇒ null）；它那本账在会话副本里时是取料的第一层。 */
    private final ActorRef supplierActor;

    /** 供方自己的账**在会话副本里**（H5：先取它自己那本）。 */
    private final boolean supplierHoldsOwnAccount;

    /** relation 名下的家户账（保序去重；取料的第二层与"补齐"层）。 */
    private final List<HouseholdId> ownKeys;

    /** ② 逐商品可供量：自己看得见的经营者账 + {@link #ownKeys}（争用的商品在第二遍被换成池子）。 */
    private final Map<CommodityId, Long> available;

    /** ★ 只有**争用**的商品才进表：本 unit 该商品的取料上限（第二遍填）。 */
    private final Map<CommodityId, Long> quota = new LinkedHashMap<>();

    /** ★ 只有**争用**的商品才进表：这一种料的池子（= 各需求方 {@link #ownKeys} 的并集）。 */
    private final Map<CommodityId, List<HouseholdId>> sharedKeys = new LinkedHashMap<>();

    private InputPlan(
        ProductionUnitId id,
        ProductionUnit unit,
        Industry industry,
        HexCoord hex,
        Map<CommodityId, Long> perScale,
        long capacityScale,
        ActorRef supplierActor,
        boolean supplierHoldsOwnAccount,
        List<HouseholdId> ownKeys,
        Map<CommodityId, Long> available) {
      this.id = id;
      this.unit = unit;
      this.industry = industry;
      this.hex = hex;
      this.perScale = perScale;
      this.capacityScale = capacityScale;
      this.supplierActor = supplierActor;
      this.supplierHoldsOwnAccount = supplierHoldsOwnAccount;
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
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, HouseholdId> householdOfActor) {
    List<InputPlan> plans = new ArrayList<>();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionUnit unit = units.get(id);
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
          ProductionUnitBook.plannedCapacityScaleOf(
              unit, industry, index, operatorConditions.get(id));
      if (capacityScale <= 0L) {
        continue; // 本格没有产能 / 已缩到 0 ⇒ 它不产出、也不要料（"地荒着"）
      }
      // ★★ H3：**谁出料由 relation 说**。缺 relation（合法状态）⇒ 退回缺省 = operator。
      ProductionRelation relation = relations.get(id);
      Recipient supplier =
          relation == null ? new Recipient.ToActor(unit.operator()) : relation.inputSupplier();
      List<HouseholdId> ownKeys =
          supplierAccountsOf(supplier, unit, industry, rows, householdOfActor, index);
      // ★★ **H5：供方（经营者）自己的账** —— 它在会话副本里时**先**从它自己的缸里取。
      ActorRef supplierActor = supplierActorOf(supplier);
      boolean supplierHoldsOwnAccount =
          supplierActor != null && operatorGoods.containsKey(supplierActor);
      // ① 可供量（逐商品）= 主体**自己的账** + 代理的那些家户账（**无争用时**这就是全部）。
      Map<CommodityId, Long> available = new LinkedHashMap<>();
      for (CommodityId commodity : perScale.keySet()) {
        long sum =
            supplierHoldsOwnAccount ? operatorStockOf(operatorGoods, supplierActor, commodity) : 0L;
        for (HouseholdId key : ownKeys) {
          sum += stockOf(householdGoods, key, commodity);
        }
        available.put(commodity, sum);
      }
      plans.add(
          new InputPlan(
              id,
              unit,
              industry,
              // ★ 拿不到格键 ⇒ null（**不抛**：改前这条路径也不需要格，{@code householdKeysOf} 会给空表 ⇒ 逐值同旧），
              //   它因此不属于任何一格、也就不参与"同格争用"。
              IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null),
              perScale,
              capacityScale,
              supplierActor,
              supplierHoldsOwnAccount,
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
   * 每家的可供量_j := 它自己那本经营者账 + 池内余额（⇒ 规模可以按"本格这一种料的总量"算）
   * D = Σ need_j、P = 池内余额；D > P ⇒ quota = 按 need 比例（最大余数法，Σquota == P）；否则 quota = need_j
   * </pre>
   *
   * <p>★ <b>无争用 ⇒ 一个字段都不动</b>（{@code quota}/{@code sharedKeys} 都留在空表里）⇒ 单需求方的世界的全部读数逐值不变。
   */
  private static void rationContestedInputs(
      List<InputPlan> plans,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
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
          continue; // 每单位需求为 0 ⇒ 它**不是**这一种料的需求方（同 scaleOf 的口径）
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
        // 逐需求方：可供量换成"**自己那本**经营者账 + 池子"⇒ 规模按池子算；同时记下池子（第三遍取料要用）。
        for (InputPlan plan : demanders) {
          long ownOperatorStock =
              plan.supplierHoldsOwnAccount
                  ? operatorStockOf(operatorGoods, plan.supplierActor, commodity)
                  : 0L;
          plan.available.put(commodity, ownOperatorStock + poolStock);
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
        continue; // 每单位需求为 0 ⇒ 这一路不构成约束（同 scaleOf 的口径）
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
   * ③ 零  ：供方**自己的经营者账**（H5：工具/周转料在它自己名下）
   * ③ 一  ：relation 名下的家户账 —— 按人口占比 + 最大余数法分派（Σ分派 == remaining，不再逐户丢余数）
   * ③ 一b ：同层还有货的账**补齐**（持仓降序）
   * ③ 二  ：★ H6-lite：**争用**的商品的**池子里、别人名下的**账（持仓降序）—— 只在上面取不满时进场
   * </pre>
   */
  private static void drawOneProductionUnitInputs(
      InputPlan plan,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    long usableScale = usableScaleOf(plan);
    if (usableScale <= 0L) {
      return; // 一点料都筹不到（或本格没有产能）⇒ 不扣、规模自然 0 —— "地荒着"
    }
    ProductionUnit unit = plan.unit;
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
      // ③ 零：**主体自己的账**（经营者）—— 有多少取多少，取到需求满足为止（H5）。
      if (plan.supplierHoldsOwnAccount) {
        long drawn =
            Math.min(operatorStockOf(operatorGoods, plan.supplierActor, commodity), remaining);
        if (drawn > 0L) {
          recordOperatorInputDraw(
              plan.supplierActor, unit, commodity, drawn, operatorGoods, ledger);
          remaining -= drawn;
          drawnTotal.merge(commodity, drawn, Long::sum);
        }
      }
      // ③ 一：按人口占比 + **最大余数法**分派（Σ分派 == remaining，不再逐户丢余数），逐户上限 = 它的余额。
      for (Map.Entry<HouseholdId, Long> ask :
          apportionToHouseholds(plan.ownKeys, rows, remaining).entrySet()) {
        long drawn = Math.min(stockOf(householdGoods, ask.getKey(), commodity), ask.getValue());
        if (drawn <= 0L) {
          continue; // 缸空/不够 ⇒ 这一户出不起它那一份（下面的补齐可能从**同层的别人**那里补上）
        }
        recordInputDraw(
            ask.getKey(),
            unit,
            commodity,
            drawn,
            rows,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
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
                rows,
                householdGoods,
                householdMoney,
                operatorGoods,
                operatorMoney,
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
            rows,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
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
      LinkedHashMap<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
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
          rows,
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
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
      Recipient supplier,
      ProductionUnit unit,
      Industry industry,
      Map<HouseholdId, ClassRow> rows,
      Map<ActorRef, HouseholdId> householdOfActor,
      SettlementIndex index) {
    if (supplier instanceof Recipient.ToHousehold toHousehold) {
      HouseholdId key = toHousehold.household();
      if (!rows.containsKey(key)) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账，"
                + "把'配置错'静默当成'缸里没有'会让账面上看不出问题）：unit="
                + unit.id()
                + " 供方="
                + key);
      }
      requireSupplierHex(rows.get(key).view().hex(), industry);
      return List.of(key);
    }
    if (supplier instanceof Recipient.ToCohort toCohort) {
      // ★ S1 兼容档：旧档的 ToCohort 按视图反查**恰一个**家户（构造期归一化已把一对一的转成
      //   ToHousehold；走到这里说明视图有歧义或状态还没归一，故 fail-closed，不猜第几个）。
      HouseholdId matched = null;
      for (ClassRow row : rows.values()) {
        if (row.view().equals(toCohort.cohort())) {
          if (matched != null) {
            throw new IllegalStateException(
                "旧档 ToCohort 视图对上了多个家户（S1 起视图不再是唯一身份）："
                    + toCohort.cohort()
                    + " ⇒ "
                    + matched
                    + " / "
                    + row.id());
          }
          matched = row.id();
        }
      }
      if (matched == null) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账）：unit="
                + unit.id()
                + " 供方视图="
                + toCohort.cohort());
      }
      requireSupplierHex(rows.get(matched).view().hex(), industry);
      return List.of(matched);
    }
    ActorRef actor = ((Recipient.ToActor) supplier).actor();
    HouseholdId named = householdOfActor.get(actor);
    if (named != null) {
      requireSupplierHex(rows.get(named).view().hex(), industry);
      return List.of(named);
    }
    // ★ 聚合主体：它的账在 actor 切片上（economy 看不见）⇒ 由**该 unit 名下的家户账**代理（见方法注释）。
    List<HouseholdId> proxied = index.householdsOf(unit.id());
    for (HouseholdId key : proxied) {
      requireSupplierHex(rows.get(key).view().hex(), industry);
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
      List<HouseholdId> keys, Map<HouseholdId, ClassRow> rows, long total) {
    Map<HouseholdId, Long> apportioned = new LinkedHashMap<>();
    if (keys.isEmpty() || total <= 0L) {
      return apportioned;
    }
    long population = 0L;
    for (HouseholdId key : keys) {
      population += rows.get(key).population();
    }
    if (population <= 0L) {
      return apportioned;
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      weights[i] = rows.get(keys.get(i)).population();
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
      ProductionUnit unit,
      CommodityId commodity,
      long drawn,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
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
      ClassRow supplierRow = rows.get(supplierKey);
      if (supplierRow == null) {
        throw new IllegalStateException(
            "投入供方不在家户表里（账户 must 有 location，拒绝静默用 (0,0)）: " + supplierKey);
      }
      Transfer requisition =
          ledger.mint(
              HouseholdActors.of(supplierKey),
              unit.operator(),
              supplierRow.view().hex(),
              Map.of(commodity, drawn),
              TransferReason.INPUT_REQUISITION);
      applyTransfer(
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          householdOfActor,
          requisition);
      consumeFromHousehold(householdGoods, consumedGoods, operatorKey, commodity, drawn);
      return;
    }
    // ② 其余情形：投入被生产直接吃掉（不写"给产业"的那条转移 —— 收端的账不在家户副本里，见方法注释）。
    long stock = stockOf(householdGoods, supplierKey, commodity);
    setStock(householdGoods, supplierKey, commodity, stock - drawn);
    addGoods(consumedGoods, supplierKey, commodity, drawn);
  }

  /**
   * ★★ <b>从<strong>经营者自己的账</strong>上划走一笔投入</b>（H5）—— 与家户那条路的区别只有一处： <b>不记 {@code consumed}</b>。
   *
   * <p>★★ <b>为什么不记 consumed</b>：{@code consumed} 是**行（家户）**的流水字段，而经营者没有行。守恒式 {@code ΔΣActorGoods +
   * ΣFinalConsumption + ΣLoss == ΣOutput − ΣProductionInputs}（类注 §6.1）里 这一笔落在**左边第一项**（actor
   * 侧账本的库存变化）与**右边最后一项**（{@code ΣProductionInputs}， 由上面的 {@code ledger.addInput} 记入）—— 两边各一次，刚好平。★
   * 硬塞进某个家户的 {@code consumed} 才是错的： 那会让 {@code ΣFinalConsumption} 多一笔"没人吃过的消费"。
   *
   * <p>★ <b>不产生转移</b>：供方 == 经营者（它就是这一笔投入的消费者）⇒ 自转移是坏数据（{@code Transfer} 的两端不许相等）， 这正是 H2 那条"供方 ==
   * 经营者 ⇒ 投入被生产直接吃掉"的同一档。
   */
  private static void recordOperatorInputDraw(
      ActorRef operator,
      ProductionUnit unit,
      CommodityId commodity,
      long drawn,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      ProductionLedger.Accumulator ledger) {
    ledger.addInput(unit.industry(), commodity, drawn);
    setOperatorStock(
        operatorGoods,
        operator,
        commodity,
        operatorStockOf(operatorGoods, operator, commodity) - drawn);
  }

  /** 供方指名的主体（{@code ToActor} ⇒ 它；{@code ToCohort} ⇒ null：家户供方没有"经营者账"这一路）。 */
  private static ActorRef supplierActorOf(Recipient supplier) {
    return supplier instanceof Recipient.ToActor toActor ? toActor.actor() : null;
  }

  /**
   * ★★ <b>该产业"本格产能"折出的规模</b>（K3 之后"产能"只有这一处）：{@code min over k ∈ capacityPerUnit:
   * ⌊industry.capacity[k] ÷ capacityPerUnit[k]⌋}。
   *
   * <p>★ 与收获日的 {@link #scaleOf} 的产能那一路是**同一个算式**（只是那里还要对劳动与投入取 min）—— 于是"一次想扣多少" 与产业"能产多少"用同一把尺。
   *
   * <p>★ M2.1 起 {@link MarketSettlement} 也算经营者的"必要生产投入"（= 本方法 × {@code inputPerUnit}），故它从 {@code
   * private} 放宽到包内可见；算法一字未改。
   */
  static long capacityScaleOf(
      ProductionUnit unit, Industry industry, Map<AssetShareId, AssetShare> assetShares) {
    // ★★ R3B.2：产能从**实物总账**派生（{@link ProductionUnitBook} 是唯一拼写点）。
    return ProductionUnitBook.capacityScaleOf(unit, industry, assetShares);
  }

  /** ★★ 索引口径的产能规模（R4-B.3a-perf；算式仍由 {@link ProductionUnitBook} 唯一拼写点给出）。 */
  static long capacityScaleOf(ProductionUnit unit, Industry industry, SettlementIndex index) {
    return ProductionUnitBook.capacityScaleOf(unit, industry, index);
  }

  /**
   * ★★ <b>S3：计划规模</b> = 技术产能规模 × 状态机的计划系数（{@link StressPolicy}）。
   *
   * <p>★ 缩产/停业只影响"本周期的计划"（投入需求、劳动需求、收获规模），<b>不销毁</b> {@code capacity} / {@code AssetShare}：
   * 条件缺失（旧档）或状态回到 {@code ACTIVE} ⇒ 系数 1000‰ ⇒ 与旧行为逐值相同。
   */
  static long plannedCapacityScaleOf(
      ProductionUnit unit,
      Industry industry,
      Map<AssetShareId, AssetShare> assetShares,
      OperatorCondition condition) {
    return ProductionUnitBook.plannedCapacityScaleOf(unit, industry, assetShares, condition);
  }

  /** ★★ 索引口径的计划规模（R4-B.3a-perf；产能规模查入口索引，计划系数算式不变）。 */
  static long plannedCapacityScaleOf(
      ProductionUnit unit, Industry industry, SettlementIndex index, OperatorCondition condition) {
    return ProductionUnitBook.plannedCapacityScaleOf(unit, industry, index, condition);
  }

  /**
   * ★★ <b>S3：本周期"投入没凑齐"的可观察事实</b>（在周期状态被清零之前捕获）：按状态机的计划规模系数（缩产/停业）算应投， 与 {@code
   * cycleInputUsedMilli} 比。停业（系数 0）⇒ 不投也不构成"投入不足"（那是主动停，不是失败）。
   */
  static boolean inputShortfallOf(
      ProductionUnit unit,
      Industry industry,
      Map<AssetShareId, AssetShare> assetShares,
      OperatorCondition condition) {
    long planned =
        ProductionUnitBook.plannedCapacityScaleOf(unit, industry, assetShares, condition);
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
      ProductionUnit unit, Industry industry, SettlementIndex index, OperatorCondition condition) {
    long planned = ProductionUnitBook.plannedCapacityScaleOf(unit, industry, index, condition);
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
   * ③ 家户行     = 从 LaborAllocation.household 取"actor 命中该产业"的家户，按稳定 HouseholdId 去重、只留真有行的
   * </pre>
   *
   * <p>★★ <b>它取代了改前的 {@code classKeysOf(rows, industryId)}</b>（按行键里的产业段过滤）：H0 之后**行里没有产业了** （键 = 格
   * + 居住类型 + 阶层）—— 一个家户给两个产业出劳动（农村家户既种地又织布）时它**只有一行**，而"哪些行属于这个产业"
   * 只能由**劳动配额表**回答：出劳动的那批人住哪儿，就是它的家户。
   *
   * <p>★ <b>行不存在 ⇒ 跳过</b>（不是坏数据）：逐组件增量落盘 ⇒ 配额先到、行后到是合法写序（同 {@code EconomyData} 的
   * "表与表之间没有引用完整性约束"）。★ <b>一条配额都没有的产业 ⇒ 空表</b>（没有家户 ⇒ 没有劳动者；收获的产出全留 operator）。
   *
   * <p>★★ <b>S3：本方法不读 {@code ClassRow.view.stratum}，也不按四阶层枚举</b> —— 归属的唯一事实源是 {@link
   * LaborAllocation#household()}（H0 的裁定，S3 写回阶层后仍然成立）。故阶层改成 {@code landless_laborer}/{@code
   * artisan}/{@code official} 后，这里既不会漏行也不会错行。
   *
   * <p>★ 序 = 稳定 {@link HouseholdId#value()} 字典序（纯函数、与配额表插入序无关；不再按 {@code SocialClassId.all()}
   * 的四档顺序假想行集合）。
   */
  static List<HouseholdId> householdKeysOf(
      Map<HouseholdId, ClassRow> rows,
      ProductionUnitId unit,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    // ★★ S1：家户归属的**唯一事实源 = LaborAllocation.household**（不再从视角的阶层段反推）。
    //   ★★ R3B.2：归属判据从 {@code actor.id() == industryId} 改成 {@code activity == unit.id()} ——
    //     "这份劳动喂哪条生产活动"由 activity 回答（与结算按 activity 归集劳动同一个源）。
    LinkedHashSet<HouseholdId> households = new LinkedHashSet<>();
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.activity().equals(unit.value())) {
        households.add(allocation.household());
      }
    }
    List<HouseholdId> keys = new ArrayList<>();
    for (HouseholdId household : households) {
      if (rows.containsKey(household)) {
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

  /**
   * ★★ S1：由"格 + 居住类型集合"筛出**该处的全部家户**（只留真的存在的行）—— 视图与身份分离后， 只有 {@link ClassRow#view()}
   * 还能回答"住哪"；键本身不再带格。
   */
  private static List<HouseholdId> householdKeysAt(
      Map<HouseholdId, ClassRow> rows, HexCoord hex, Set<ResidenceKind> residences) {
    List<HouseholdId> keys = new ArrayList<>();
    for (ClassRow row : rows.values()) {
      if (row.view().hex().equals(hex) && residences.contains(row.view().residence())) {
        keys.add(row.id());
      }
    }
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  /**
   * ★★ <b>一个批次的出生/死亡该摊到哪些家户行</b>（{@link #applyPopulationChange} 用）：它供给的那些产业名下、 {@code
   * LaborAllocation.household} 指名且居住类型匹配的家户，**并集去重**。
   *
   * <p>★★ <b>去重不是优化，是正确性</b>：真档里农村批次同时供给 {@code farm@hex} 与 {@code weave@hex}，而两者落在**同一批农村家户行**
   * 上（H0 之后行不含产业段）—— 不去重会把这批人的出生/死亡**算两遍**（人口账当场对不上）。
   *
   * <p>★ <b>S3：不按阶层枚举</b>——旧注释里的"× 四个阶层"在 H0 已作废；阶层写回只改 {@code ClassRow.view}， 而本方法的键来自 {@code
   * LaborAllocation.household} 与 {@code ResidenceKind.ofLot}，故新派生阶层不会漏行/错行。
   */
  private static List<HouseholdId> householdKeysOfLot(
      Map<HouseholdId, ClassRow> rows,
      List<ProductionUnitId> units,
      ResidenceKind residence,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    LinkedHashSet<ProductionUnitId> targets = new LinkedHashSet<>(units);
    LinkedHashSet<HouseholdId> keys = new LinkedHashSet<>();
    for (LaborAllocation allocation : allocations.values()) {
      if (targets.contains(new ProductionUnitId(allocation.activity()))
          && ResidenceKind.ofLot(allocation.group()) == residence
          && rows.containsKey(allocation.household())) {
        keys.add(allocation.household());
      }
    }
    List<HouseholdId> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  /** ★★ 索引口径的“一个批次摊到哪些家户行”（R4-B.3a-perf；只有调用方仍需给 target 集合，不再扫全量配额）。 */
  private static List<HouseholdId> householdKeysOfLot(
      Map<HouseholdId, ClassRow> rows,
      List<ProductionUnitId> units,
      ResidenceKind residence,
      SettlementIndex index) {
    LinkedHashSet<ProductionUnitId> targets = new LinkedHashSet<>(units);
    LinkedHashSet<HouseholdId> keys = new LinkedHashSet<>();
    for (ProductionUnitId target : targets) {
      for (LaborAllocation allocation : index.allocationsOfUnit(target)) {
        if (ResidenceKind.ofLot(allocation.group()) == residence
            && rows.containsKey(allocation.household())) {
          keys.add(allocation.household());
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
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<HouseholdId, Set<ProductionUnitId>> byHousehold = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (!units.containsKey(unitId) || !rows.containsKey(allocation.household())) {
        continue;
      }
      byHousehold
          .computeIfAbsent(allocation.household(), ignored -> new LinkedHashSet<>())
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
      Map<HouseholdId, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, Set<ProductionUnitId>> unitsOfHousehold,
      Map<String, List<IndustryId>> industriesByHex) {
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (HouseholdId key : rows.keySet()) {
      Set<ProductionUnitId> supplied = unitsOfHousehold.getOrDefault(key, Set.of());
      long cycleDays = 0L;
      for (ProductionUnitId unitId : supplied) {
        ProductionUnit unit = units.get(unitId);
        Industry industry = unit == null ? null : industries.get(unit.industry());
        if (industry != null) {
          cycleDays = Math.max(cycleDays, industry.cycleDays());
        }
      }
      if (cycleDays == 0L) {
        ClassRow row = rows.get(key);
        if (row != null) {
          // ★ R4-B.3a-perf：本格产业由入口索引一次给出（旧实现逐无配额家户扫全量产业表）。
          for (IndustryId industryId :
              industriesByHex.getOrDefault(
                  IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r()), List.of())) {
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
   * <p>★★ **当日需求的唯一算法 + 唯一落点**（V5；v2 spec §八.6/§八.8）：
   *
   * <pre>
   * need = EconomyVocabulary.dailyRationMilli(row.population(), day)   // 逐日差分，残差不丢
   * row.naturalNeeds = { grain: need }                                 // ★ 结算写、读口读（同源）
   * eaten = min(家户账余额, need)；差额进 deficit（借粮/缺口）
   * </pre>
   *
   * <p>★★ **H1：库存在会话工作副本里**（裁定 K1）：日耗从 {@code householdGoods} 读、**就地扣**（改前读写 {@code
   * ClassRow.goods}）；{@code population == 0} 的家户**跳过消费**（它们不吃饭、不穿衣 —— 需求本来也是 0，这里显式挡一次 免得读一个不存在的账）。
   *
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（放贷行**本周期自需**的输入，见 {@link
   *     #cycleDaysByHousehold}；H0 之前这一项藏在"行 → 产业"的键里，现在行没有产业了）
   * @param day 借入发生日（进 {@code DebtContract.openedDay}；首次建条用）
   * @param dueCycle 借粮的到期周期 = 当前周期 + 1（滚动写进合同；连续余额只保留最新一笔的到期）
   */
  /**
   * ★ <b>本日关账的一个 unit</b>（H5/R3B.2）：它的 id、产业模板 id、它名下的家户行、它的周期天数 —— 饿死判据（{@link #applyFamine}）
   * 与死亡后的劳动缩放（{@link #scaleLaborOfUnit}）要等**市场与借粮**走完才跑，故先把这四样收起来。
   */
  private record ClosedUnit(
      ProductionUnitId unit,
      IndustryId industry,
      List<HouseholdId> keys,
      long cycleDays,
      boolean inputShortfall) {}

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
   * <p>★★ <b>E4b 的额度（三路取小）</b>：
   *
   * <pre>
   * 额度_h = min( 剩余缺口_h ,
   *              放贷方余粮（逐债权人，见 {@link #lendableOf}）,
   *              ★ {@link DebtCapacity#headroom()}（唯一算法在 {@link DebtCapacityBook}，由调用方一次算好）
   *                 − 本周期已借_h )
   * headroom = max(0, ⌊{@link DebtCapacity#CREDIT_F_MULTIPLE_PER_MILLE} × F ÷ 1000⌋
   *                   + 可自用余粮 + 政策钩子 − 同 unit（粮）既有本金)
   * F        = max(0, 本周期已实现粮所得 − 本周期累计口粮 − 下一轮必要投入 − 实缴税)
   * </pre>
   *
   * <p>★★ <b>放贷人不再按阶层白名单选</b>（R3 续修；制度选择，理由与边界写明）：旧实现只认 {@code landlord/rich/middle} 三档当前 view，而 S3
   * 阶层写回把真档绝大多数行改成派生阶层后，"有粮可贷"的家户只要不在白名单里就借不出去，信贷集中到 799 个地主。 本版改为<strong>按可观察余粮选人</strong>：凡
   * {@code lendableOf(row, 库存, cycleDays) > 0} 的家户都可放贷（不按阶层名、不按旧档反推）， 并按可贷额降序、同额按 {@link
   * HouseholdId#value()} 升序作确定性 tie-break。★ 边界：这<b>不</b>凭空造粮；它只回答"谁有粮谁能贷"。
   *
   * <p>★ <b>为什么要有额度</b>：借粮是"未来有收入"时才成立的事（H5 的题目）—— 没有它，缺口行可以无限借 （一个永远还不上的人借到债权人破产）。★ E4b
   * 起额度只认<b>已经发生</b>的粮所得扣掉口粮/下一轮投入/税之后剩下的 F、<b>已经存在</b>的可自用余粮、以及<b>已经欠下</b>的同 unit 本金；三项都没有 ⇒
   * headroom 0（不借），既不凭未来推断发信用卡，也不靠旧信用线放大。
   *
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（放贷方**本周期自需**的输入）
   * @param debtCapacities 每家的 {@link DebtCapacity}（E4b 第三路的唯一算法；见 {@link DebtCapacityBook}）——
   *     由调用方一次算好；缺键或 headroom 读不到 ⇒ 当场记 0（不静默给额度）
   * @param day 借入发生日（进 {@code DebtContract.openedDay}；首次建条用）
   * @param dueCycle 借粮的到期周期 = 当前周期 + 1（滚动写进合同；连续余额只保留最新一笔的到期）
   */
  private static void lendDeficitsInHex(
      List<HouseholdId> keys,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassRow> rowUpdates,
      Map<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<HouseholdId, Long> borrowing,
      Map<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      Map<HouseholdId, Long> deficit,
      long day,
      long dueCycle,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionSettlement.TransferMint mint) {
    {
      // ② 放贷序列：按可观察余粮降序（R3 续修；制度选择，理由与边界见方法注释）。
      //   ★★ **可取的不是"全部库存"、也不是"当日盈余"**（V1 的注释与代码相反，V6 一并修）：可取的是
      //     **余粮 = 余额 − 本周期自需 × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000**（{@link
      // #lendableOf}）。
      //   ★ 付款路径**不按人口过滤**（H1 明文）：人口为 0 的家户照样可以是债权人（它有存粮、它借得出）。
      Map<HouseholdId, Long> lendableByLender = new LinkedHashMap<>();
      for (HouseholdId key : keys) {
        long available =
            lendableOf(rows.get(key), grainOf(householdGoods, key), cycleDaysByHousehold);
        if (available > 0L) {
          lendableByLender.put(key, available);
        }
      }
      List<HouseholdId> lenders = new ArrayList<>(lendableByLender.keySet());
      lenders.sort(
          Comparator.<HouseholdId, Long>comparing(lendableByLender::get, Comparator.reverseOrder())
              .thenComparing(HouseholdId::value));
      // ③ 逐缺口行（阶层 id 序 → 居住类型）借：借到多少累加多少债；没人有**余粮** ⇒ 剩下的只留作未满足的自然需求。
      List<HouseholdId> debtors = new ArrayList<>(deficit.keySet());
      debtors.sort(
          Comparator.comparing((HouseholdId k) -> rows.get(k).view().stratum().value())
              .thenComparing(k -> rows.get(k).view().residence()));
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
        // ★★ **E4b 信用额度**（第三路）：DebtCapacity.headroom（唯一算法在 DebtCapacityBook）减去**本周期已借**；
        //   缺键/读不到 ⇒ 0（不偷发额度）。★ 额度只影响“还能借多少”，借入仍必须有真实债权人库存转出。
        DebtCapacity capacity = debtCapacities.get(debtor);
        long headroom =
            capacity == null || capacity.headroom().isEmpty()
                ? 0L
                : capacity.headroom().getAsLong();
        long creditLeft = Math.max(0L, headroom - borrowing.getOrDefault(debtor, 0L));
        remaining = Math.min(remaining, creditLeft);
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
                  rows.get(debtor).view().hex(),
                  Map.of(GRAIN, lent),
                  TransferReason.LOAN_PRINCIPAL);
          applyTransfer(
              householdGoods, householdMoney, operatorGoods, operatorMoney, householdOfActor, loan);
          // ★★ **借到的粮当日即被吃掉**：紧接在转移之后从借方副本扣掉同一笔（⇒ 借方余额净 0，
          //   与改前逐值相同：改前根本不写借方库存，只记 consumed 与债务）。⇒ 两条痕都在：转移（粮从谁来）
          //   与消费（粮到哪去）。
          consumeFromHousehold(householdGoods, consumedGoods, debtor, GRAIN, lent);
          // ★ 借到的那一笔当日即被吃掉 ⇒ 冲减当日缺口读数（缺口在吃饭那一步已经整笔记进去了 —— 见 consumeOwnStock）。
          trimUnmet(unmetNeed, debtor, GRAIN, lent);
          // ★★ E4a 连续身份：id 是 (债务人, 债权人, unit, terms) 的**纯函数** ⇒ 同一对主体跨周期命中同一条，
          //   本金递增；terms 不同（利率/规则/期限维）必然分开，不允许静默合并。
          // ★★ E4c：唯一写口 —— 新条/续借/再借激活/到期覆盖全部在 DebtContractBook.upsert 里；本方法不再碰债务表。
          DebtContract contract =
              DebtContractBook.upsert(
                  debts,
                  debtor,
                  lender,
                  DebtUnit.commodity(GRAIN),
                  DebtTerms.legacyDefault(BORROW_RATE_PER_MILLE_PER_CYCLE),
                  lent,
                  day,
                  OptionalLong.of(dueCycle));
          ClassRow currentRow = rowUpdates.getOrDefault(debtor, rows.get(debtor));
          rowUpdates.put(
              debtor,
              DebtContractBook.withDebtReference(currentRow, contract.id())); // 派生引用只加一次（幂等）
          // ★ 借到的粮当日吃掉 ⇒ 已在上面（转移之后）计入当日消费 —— 那里是**唯一**写这一笔的地方。
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 没人有**余粮**（或 E4b 额度用尽）：不造粮、不造债。
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
   * reserve  = EconomyVocabulary.cumulativeRationMilli(放贷行人口, 该家户的 cycleDays)
   *            × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000        // 毫粮
   * lendable = max(0, 余额 − reserve)
   * </pre>
   *
   * <p>★ <b>H0 起"该家户的 cycleDays"来自它供给的那些产业</b>（行里已经没有产业段了；取最长，见 {@link
   * #cycleDaysByHousehold}）。真档三个产业都是 120 天 ⇒ 与改前逐值相同。 ★ <b>H1 起"余额"由调用方从会话工作副本读好传进来</b> （{@code
   * stock}；行里已经没有库存了）。
   *
   * <p>★★ **"本周期自需"取的是整周期**（{@code cumulativeRationMilli(pop, cycleDays)}），不是"今日一餐"、也不是
   * "消费后的全部库存"：放贷方先把这一周期自己**全部**的口粮扣下来，剩下的才是余粮。默认千分比 1000 ⇒ 保留额就是整周期口粮（"地主 250 天储备 − 120 天自需 = 130
   * 天余粮仍贷得出去"）。
   *
   * <p>★ **为什么用累计函数而不是"人口 × 一天的量 × 天数"**：日耗是逐日差分，乘不出来（§八.6）； {@code cumulativeRationMilli(pop,
   * cycleDays)} 才是"该行一整个周期的口粮"的唯一写法。
   *
   * <p>★ **它只读、不写**：保留额不是"冻结起来的一笔粮"，放贷行自己每天照吃不误 —— 它只是"可贷额"的下界。 ⇒
   * 周期后半段会**多留**（那时已经用不到整周期的口粮了），这是本口径的可读后果，端到端用例逐值钉着它。
   *
   * <p>★★ <b>E4b 修正（如实记）</b>：S1 把行键从 {@code CohortKey} 换成 {@code HouseholdId} 之后，本方法一直用 {@code
   * lender.key()}（= {@code ClassRow.view()}，视图）去查 {@code HouseholdId} 键的 {@code
   * cycleDaysByHousehold} ⇒ <b>查表恒不命中、保留额实际为 0</b>（与本节类注承诺的"整周期自留"相反）。E4b 的实现要按 {@code max(0, 库存 −
   * 本周期自需)} 算可质押余粮（{@link DebtCapacityBook}），故这里改用 {@code lender.id()} 取同一份 {@code
   * cycleDaysByHousehold}；<b>放贷方的可贷额因此真正开始扣整周期口粮</b>（这是 E4b 报告里逐条列出的有意行为变化， 不是隐藏改动）。
   */
  static long lendableOf(ClassRow lender, long stock, long cycleDays) {
    if (stock <= 0L) {
      return 0L;
    }
    long reserve =
        EconomyVocabulary.cumulativeRationMilli(lender.population(), cycleDays)
            * LENDER_SUBSISTENCE_RESERVE_PER_MILLE
            / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /**
   * ★ 带查表的旧签名：键 = {@code lender.id()}（E4b 修正；见 {@link #lendableOf(ClassRow, long, long)} 的边界说明）。
   */
  static long lendableOf(ClassRow lender, long stock, Map<HouseholdId, Long> cycleDaysByHousehold) {
    return lendableOf(lender, stock, cycleDaysByHousehold.getOrDefault(lender.id(), 0L));
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
   */
  private static Map<HouseholdId, DebtCapacity> debtCapacitiesForDay(
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, FlowRow> flows,
      Set<HouseholdId> newCycleHouseholds,
      Map<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions) {
    Map<HouseholdId, Long> cycleToDateIncome = new LinkedHashMap<>();
    Map<HouseholdId, Long> cycleToDateConsumed = new LinkedHashMap<>();
    Map<HouseholdId, Long> taxPaid = new LinkedHashMap<>();
    for (HouseholdId key : rows.keySet()) {
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
        rows,
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
        DebtCapacityBook.NO_UNIT_PRICES);
  }

  /*
   * ★ E4b：旧 creditLinesOf 已删除（不再有第二份信用公式）。
   *
   * 新口径的唯一算法在 DebtCapacityBook.capacities（经 DebtCapacity.headroom() 出额度）；借粮路径用 DebtCapacity
   * 的 headroom 作为三路取小的那一路。旧 LOAN_INCOME_MULTIPLE_PER_MILLE 只留 @Deprecated 别名。
   */

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
   * 1. 劳动释放   ：删除 activity == exit.unit 的全部 LaborAllocation（laborSupply 不动），释放量进读数
   * 2. 资产处置   ：unit 名下（industry + operator）份额里，owner != operator 的只把 operator 改回 owner；
   *                owner == operator 的份额原样留在 owner 名下（unit 已停业，计划系数 0）
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
   * <p>★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link AssetShareBook}</b>。理由：它做的是 <b>id 保持不变的 operator
   * 回主</b>（份额身份编码了 operator；{@code AssetShareBook.transfer} 会按新 tuple 生成新 id，与本方法的契约「id
   * 不变」冲突）。这是对"资产份额转移只走唯一写口"的<b>显式记为遗留的例外</b>，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link
   * AssetShareBook}。若要收口，应由 Book 提供一个批量、原子、id 保持的 operator 重指派口（E5a 未做，避免在无测试保护的阶段改这条低频处置路径的 id
   * 语义）。
   */
  private static void settleOperatorExits(
      List<OperatorSettlement.Exit> exits,
      LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
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
        LaborAllocation allocation = allocations.get(allocationId);
        if (allocation == null || !allocation.activity().equals(exit.unit().value())) {
          continue; // 索引是入口快照；处置过程中可能已被前一个 exit 改动 ⇒ 按当前行再核一次
        }
        releasedLaborMilli += allocation.laborMilli();
        allocations.remove(allocationId);
        releasedAllocations++;
      }

      // ── 2. 资产处置：TENANCY/委托份额 operator 改回 owner；OWNED 份额留在 owner 名下 ──────────
      //   不得删份额、不得改 owner/quantity/kind；id 不变（opaque 身份，operator 只是行内一栏）。
      int returnedShares = 0;
      long returnedQuantity = 0L;
      int keptOwnedShares = 0;
      for (AssetShareId shareId : index.assetShareIdsOfUnit(exit.unit())) {
        AssetShare share = assetShares.get(shareId);
        if (share == null
            || !share.industry().equals(exit.industry())
            || !share.operator().equals(exit.operator())) {
          continue; // 同上：用当前行再核作用域，避免处理已被前一个 exit 改过的份额
        }
        if (!share.owner().equals(exit.operator())) {
          assetShares.put(
              shareId,
              new AssetShare(
                  share.id(),
                  share.industry(),
                  share.asset(),
                  share.owner(),
                  share.owner(), // ★ 只改 operator：份额回到所有者、可再出租
                  share.quantity(),
                  share.kind()));
          returnedShares++;
          returnedQuantity += share.quantity();
        } else {
          keptOwnedShares++; // owner == operator：本来就是它自己的，unit 不再运行（计划系数 0），份额原样留下
        }
      }

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
              householdGoods,
              householdMoney,
              operatorGoods,
              operatorMoney,
              householdOfActor,
              repayment,
              issuanceJournal);
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
        for (long value : householdMoney.getOrDefault(debtor, Map.of()).values()) {
          keptMoneyMilli += value;
        }
      } else {
        for (long value : operatorGoods.getOrDefault(exit.operator(), Map.of()).values()) {
          keptGoodsMilli += value;
        }
        for (long value : operatorMoney.getOrDefault(exit.operator(), Map.of()).values()) {
          keptMoneyMilli += value;
        }
      }
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
   * 端点   = householdOfActor.get(payer/payee) ∈ 家户行；任一解析不到 ⇒ 具名 unresolved，跳过，不伪造端点
   * 库存   = **不动**：资本化只写债权本金；不铸转移、不扣库存/货币，也不清零 Arrear 读数
   * </pre>
   *
   * <p>★★ <b>手算例子</b>：某作坊 operator（家户 H1）本周期应付实物工资 1,000 毫粮、实付 400 ⇒ Arrear{@code owed = 600}。资本化后
   * {@code DebtContractId.idOf(H1, H2, Commodity(grain), legacyDefault)} 这条合同的本金 +600、 状态
   * NORMAL、{@code dueCycle = 当前周期 + 1}；H1 的行流水 {@code capitalizedArrears["commodity:grain"] +=
   * 600}； 粮库存一分不动（H1 与 H2 的账都保持原值）。同一日如果又读到一条完全相同的 600 欠款读数（同 activity/rule）， 去重后只写一次、金额
   * 1,200（{@code eventCount = 2}）。
   *
   * @param ledger 当日累加器（读 {@code arrears()}、写资本化/跳过审计）；不得为 null
   * @param rows 家户行工作表（解析债务人后补派生引用）；不得为 null
   * @param debts 债务工作表（唯一写口 {@link DebtContractBook#upsert}）；不得为 null
   * @param householdOfActor actor → 家户的当日反查表（E4c 身份解析的唯一来源）；不得为 null
   * @param capitalizedByHousehold 本日逐户逐 unit 的资本化累加器（就地更新 ⇒ 进 {@code FlowRow.capitalizedArrears}）；
   *     不得为 null
   * @param day 资本化发生日（进新合同 {@code openedDay}）；不得为负
   * @param dueCycle 资本化合同的到期周期（当前周期 + 1）
   */
  private static void capitalizeArrears(
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, ClassRow> rows,
      Map<DebtContractId, DebtContract> debts,
      Map<ActorRef, HouseholdId> householdOfActor,
      LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedByHousehold,
      long day,
      long dueCycle) {
    List<ProductionSettlement.Arrear> arrears = ledger.arrears();
    if (arrears.isEmpty()) {
      return; // 没有欠款读数 ⇒ 连去重表都不建（旧路径逐值不变）
    }
    // ★★ 第一步：按事件键稳定去重并累加（键序 = 读数在当日账本里的出现序 ⇒ 可复现）。
    LinkedHashMap<CapitalizationEventKey, Long> owedByEvent = new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, ProductionSettlement.Arrear> sampleByEvent =
        new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, Integer> countByEvent = new LinkedHashMap<>();
    for (ProductionSettlement.Arrear arrear : arrears) {
      DebtUnit unit = unitOf(arrear);
      CapitalizationEventKey key =
          new CapitalizationEventKey(
              arrear.payer(), arrear.payee(), unit.key(), arrear.activity(), arrear.rule());
      owedByEvent.merge(key, arrear.owed(), Math::addExact);
      sampleByEvent.putIfAbsent(key, arrear);
      countByEvent.merge(key, 1, Integer::sum);
    }
    // ★★ 第二步：逐事件解析端点 → upsert → 补派生引用 → 记审计。
    for (Map.Entry<CapitalizationEventKey, Long> entry : owedByEvent.entrySet()) {
      CapitalizationEventKey key = entry.getKey();
      ProductionSettlement.Arrear sample = sampleByEvent.get(key);
      long owed = entry.getValue();
      DebtUnit unit = unitOf(sample);
      HouseholdId debtor = householdOfActor.get(key.payer());
      HouseholdId creditor = householdOfActor.get(key.payee());
      if (debtor == null || creditor == null) {
        String reason =
            debtor == null && creditor == null
                ? "payer-and-payee-not-household"
                : debtor == null ? "payer-not-household" : "payee-not-household";
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
      DebtTerms terms = DebtTerms.legacyDefault();
      DebtContract contract =
          DebtContractBook.upsert(
              debts, debtor, creditor, unit, terms, owed, day, OptionalLong.of(dueCycle));
      ClassRow row = rows.get(debtor);
      if (row == null) {
        // 端点来自 householdOfActor（同源于 rows），解析到却查不到行 = 状态已被改坏，fail-closed。
        throw new IllegalStateException(
            "资本化解出的债务人不在 rows 里（householdOfActor 与 rows 漂开）: " + debtor);
      }
      rows.put(debtor, DebtContractBook.withDebtReference(row, contract.id()));
      ledger.addDebtCapitalization(
          new ProductionLedger.DebtCapitalization(
              sample.payer(),
              sample.payee(),
              sample.activity(),
              sample.rule(),
              debtor,
              creditor,
              contract.id(),
              unit,
              owed,
              day,
              dueCycle,
              terms,
              CAPITALIZATION_TERMS_SOURCE,
              contract.principal(),
              countByEvent.get(key)));
      capitalizedByHousehold
          .computeIfAbsent(debtor, ignored -> new LinkedHashMap<>())
          .merge(unit.key(), owed, Math::addExact);
    }
  }

  /** ★ E4c：Arrear 的显式 unit（与 {@code RuleSettlement} 的"commodity/currency 恰其一"同源；坏数据 ⇒ 具名抛）。 */
  private static DebtUnit unitOf(ProductionSettlement.Arrear arrear) {
    if (arrear.currency().isPresent()) {
      return DebtUnit.money(arrear.currency().orElseThrow());
    }
    if (arrear.commodity().isPresent()) {
      return DebtUnit.commodity(arrear.commodity().orElseThrow());
    }
    throw new IllegalStateException("Arrear 既没有 currency 也没有 commodity（构造期应已判死）: " + arrear);
  }

  /**
   * ★★ <b>周期末偿还（E4c：粮优先 + 其它商品 + 货币；唯一本金写口 {@link DebtContractBook#reduce}）</b>。
   *
   * <pre>
   * 债务人序 = HouseholdId.value 升序（稳定）
   * 合同序   = 粮 unit → 其它商品 unit → 货币 unit；同档 dueCycle（空 = 最后）→ DebtContractId.value
   * 条款闸   = 只走 RepaymentRule.AVAILABLE_SURPLUS_SHARE + InterestTiming.AFTER_REPAYMENT_ON_CLOSE
   *            其它组合 fail-closed 具名抛（不静默当默认档）
   * 预算     = 粮：max(0, 粮库存 − 冻结 − 当日口粮 × DEBTOR_SUBSISTENCE_RESERVE_DAYS) × 本常量 ÷ 1000
   *            其它商品：max(0, 该商品库存 − 冻结)；货币：max(0, 该币种余额 − 冻结)
   * 每笔     = min(预算, 本金) → 铸 LOAN_REPAYMENT 转移 → applyTransfer（带冻结）→ 本金 −还 → 预算 −还
   * 读数     = 粮进 FlowRow.repaid；货币进 FlowRow.repaidMoney（逐币种）；其它商品只从合同本金下降读
   * 不硬折   = 实物债条款允许货币折偿但没有稳定价格源 ⇒ 本金不动、记 DebtRepaymentSkip(unpriced…)
   * </pre>
   *
   * <p>★★ <b>为什么还款是"一条转移"而不是"把本金改小"</b>：粮/钱**真的从债务人的账上进了债权人的账** ——
   * 这正是"任何库存变动必有对应转移记录"那条不变量要钉的东西。{@code reason = }{@link TransferReason#LOAN_REPAYMENT}。
   *
   * <p>★★ <b>货币债不得用粮硬折</b>：货币腿只扣该合同自己的币种余额；粮预算只服务粮债（以及粮债优先的排序），
   * 不会因为"货币债还不上"就去动粮。其它商品的实物债用该商品自己的库存偿还，也不拿粮顶。
   *
   * <p>★★ <b>{@code Transfer.settles} 仍恒为 {@code Optional.empty()}</b>：借粮/偿还产生的是 {@link
   * DebtContractId}，而 {@code settles} 的类型是 {@code ClaimId} —— 硬塞会编造一条本批不存在的 claim 体系。
   *
   * <p>★★ <b>本金还清 ⇒ 那条 {@link DebtContract} 留在表里、本金为 0</b>：它是一条**已结清**的历史事实；删掉它等于把发生过的事从账上抹去。 计息读本金
   * ⇒ 0 本金的条不再生息（{@link #chargeInterest} 自己挡掉）。
   *
   * @param repaid 本日**粮债**偿还的逐行累加器（就地更新 ⇒ 进 {@code FlowRow.repaid}）；不得为 null
   * @param repaidMoney 本日**货币债**偿还的逐户逐币种累加器（就地更新 ⇒ 进 {@code FlowRow.repaidMoney}）；不得为 null
   */
  private static void repayDebts(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      LinkedHashMap<HouseholdId, Long> repaid,
      LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> repaidMoney,
      LinkedHashMap<DebtContractId, Long> repaidPrincipalByDebt,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal) {
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = DebtIndex.byDebtor(debts);
    List<HouseholdId> debtors = new ArrayList<>(rows.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value)); // 稳定债务人序（不沿用 Map 插入序）
    for (HouseholdId debtor : debtors) {
      ClassRow row = rows.get(debtor);
      if (row == null) {
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
      owed.sort(repaymentOrder());
      // ★ 预算一律"库存/余额 − 冻结 − 保留"：粮另扣一日口粮；其它商品没有口粮保留（粮保留仍是粮）。
      long grainBudget = -1L; // 懒算：该户没有粮债时不去读粮库存
      Map<CommodityId, Long> commodityBudgetLeft = new LinkedHashMap<>();
      Map<CurrencyId, Long> moneyBudgetLeft = new LinkedHashMap<>();
      for (DebtContract debt : owed) {
        requireRepayableTerms(debt);
        switch (debt.unit()) {
          case DebtUnit.Commodity commodity -> {
            CommodityId commodityId = commodity.commodity();
            boolean grain = commodityId.equals(GRAIN);
            long budget;
            if (grain) {
              if (grainBudget < 0L) {
                long dailyNeed = row.naturalNeeds().getOrDefault(GRAIN, 0L);
                long reserve = dailyNeed * DEBTOR_SUBSISTENCE_RESERVE_DAYS;
                long available =
                    Math.max(
                        0L,
                        stockOf(householdGoods, debtor, GRAIN)
                            - frozenGoodsOf(householdFrozenGoods, debtor, GRAIN)
                            - reserve);
                grainBudget = available * DEBT_REPAYMENT_SHARE_PER_MILLE / 1000L;
              }
              budget = grainBudget;
            } else {
              long stock = stockOf(householdGoods, debtor, commodityId);
              long frozen = frozenGoodsOf(householdFrozenGoods, debtor, commodityId);
              budget =
                  commodityBudgetLeft.computeIfAbsent(
                      commodityId, ignored -> Math.max(0L, stock - frozen));
            }
            long paid = Math.min(budget, debt.principal());
            if (paid > 0L) {
              Transfer repayment =
                  ledger.mint(
                      HouseholdActors.of(debtor),
                      HouseholdActors.of(debt.creditor()),
                      row.view().hex(),
                      Map.of(commodityId, paid),
                      Map.of(),
                      TransferReason.LOAN_REPAYMENT);
              applyTransfer(
                  householdGoods,
                  householdMoney,
                  operatorGoods,
                  operatorMoney,
                  householdFrozenGoods,
                  householdFrozenMoney,
                  operatorFrozenGoods,
                  operatorFrozenMoney,
                  householdOfActor,
                  repayment,
                  issuanceJournal);
              DebtContractBook.reduce(debts, debt.id(), paid);
              repaidPrincipalByDebt.merge(debt.id(), paid, Long::sum);
              if (grain) {
                grainBudget -= paid;
                repaid.merge(debtor, paid, Long::sum); // FlowRow.repaid 只记粮（其它 unit 不塞进这个标量）
              } else {
                commodityBudgetLeft.put(commodityId, budget - paid);
              }
            }
            // ★ 不硬折：条款允许货币折偿、但没有稳定价格源 ⇒ 具名跳过；实物腿已经尽力（可能部分/全额）。
            recordUnpricedConversionSkipIfNeeded(ledger, debtor, debt, debt.principal() - paid);
          }
          case DebtUnit.Money money -> {
            CurrencyId currency = money.currency();
            long free =
                Math.max(
                    0L,
                    moneyOf(householdMoney, debtor, currency)
                        - frozenMoneyOf(householdFrozenMoney, debtor, currency));
            long budget = moneyBudgetLeft.computeIfAbsent(currency, ignored -> free);
            long paid = Math.min(budget, debt.principal());
            if (paid <= 0L) {
              continue; // 该币种没有可用余额（或前面几条已用完）⇒ 不铸 0 转移
            }
            Transfer repayment =
                ledger.mint(
                    HouseholdActors.of(debtor),
                    HouseholdActors.of(debt.creditor()),
                    row.view().hex(),
                    Map.of(),
                    Map.of(currency, paid),
                    TransferReason.LOAN_REPAYMENT);
            applyTransfer(
                householdGoods,
                householdMoney,
                operatorGoods,
                operatorMoney,
                householdFrozenGoods,
                householdFrozenMoney,
                operatorFrozenGoods,
                operatorFrozenMoney,
                householdOfActor,
                repayment,
                issuanceJournal);
            DebtContractBook.reduce(debts, debt.id(), paid);
            repaidPrincipalByDebt.merge(debt.id(), paid, Long::sum);
            moneyBudgetLeft.put(currency, budget - paid);
            repaidMoney
                .computeIfAbsent(debtor, ignored -> new LinkedHashMap<>())
                .merge(currency, paid, Long::sum);
          }
        }
      }
    }
  }

  /**
   * ★★ <b>E4c 的偿还顺序</b>：先粮 unit、再其它商品、再货币；同档按 {@code dueCycle}（空 = 最后）再按 {@link
   * DebtContractId#value()} 稳定排序。
   *
   * <p>★ {@code dueCycle} 是合同当前的滚动到期周期（借入刷新）；空 = 没有写死期限 ⇒ 排在所有有期限的后面。
   */
  private static Comparator<DebtContract> repaymentOrder() {
    return Comparator.comparingInt((DebtContract debt) -> repaymentTier(debt.unit()))
        .thenComparingLong(debt -> debt.dueCycle().orElse(Long.MAX_VALUE))
        .thenComparing(debt -> debt.id().value());
  }

  /** 0 = 粮；1 = 其它商品；2 = 货币（顺序的单一拼写点）。 */
  private static int repaymentTier(DebtUnit unit) {
    return switch (unit) {
      case DebtUnit.Commodity commodity -> commodity.commodity().equals(GRAIN) ? 0 : 1;
      case DebtUnit.Money ignored -> 2;
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

  /** ★ E4c：无稳定价格源时货币折偿的具名原因（唯一拼写点）。 */
  static final String UNPRICED_MONETARY_CONVERSION_REASON =
      "unpriced-monetary-conversion-not-landed";

  /**
   * ★★ <b>E4c：不做硬折</b>——实物债条款允许货币折偿（{@code monetaryConversion != NOT_ALLOWED}）时，若还有未偿本金， 记一条具名
   * {@link ProductionLedger.DebtRepaymentSkip}：E4c 没有稳定市场价/合同价源，折偿路径未接线；
   * 实物腿该还的已经还了，剩余本金继续挂账。<b>绝不</b>拿粮价、旧价格或拍脑袋的汇率把实物折成钱。
   */
  private static void recordUnpricedConversionSkipIfNeeded(
      ProductionLedger.Accumulator ledger, HouseholdId debtor, DebtContract debt, long remaining) {
    if (remaining <= 0L) {
      return;
    }
    if (!(debt.unit() instanceof DebtUnit.Commodity)) {
      return; // 货币债本身没有"折成货币"这一步
    }
    if (debt.terms().monetaryConversion() == MonetaryConversion.NOT_ALLOWED) {
      return; // 合同本来就不允许折偿 ⇒ 不记"未定价"（那是合同事实，不是价格缺失）
    }
    ledger.addDebtRepaymentSkip(
        new ProductionLedger.DebtRepaymentSkip(
            debtor, debt.id(), debt.unit(), remaining, UNPRICED_MONETARY_CONVERSION_REASON));
  }

  /**
   * ★★ <b>劳动再分配：未吸收的劳动回池 + 缺口信号（H5 ③；裁定 C2）</b>。
   *
   * <p>★★ <b>改前的病</b>：劳动配额是**种下去的静态值**（创世发一次，此后只在人死时按存活比例缩）⇒ 一个停工/缩产的产业 （作坊没有原料）**照旧占着**那批人的全部劳动 ——
   * 城镇批次因此卡死在那个产业上：它的劳动不进任何还在开工的产业， 也不产生任何所得（"结构性失业 + 饿死"）。★ 而劳动本身是**日**口径的（{@code laborMilli}
   * 每天用一次），配额却从不重算。
   *
   * <p>★★ <b>形状（判据就是它）</b>：
   *
   * <pre>
   * ① 逐产业算"这一周期真的用得上的劳动"：
   *      usableScale_i = min( 产能那一路（{@link #capacityScaleOf}）, ⌊本周期已扣到的投入_j ÷ inputPerUnit_j⌋ … )
   *      need_i        = usableScale_i × 配方 laborPerUnit_i            // 千分劳动·日（与配额同量纲）
   * ② 逐 (批次, 产业) 配额：保留 = min(配额, 该产业**剩余**需求)；其余**回池**（配额缩小或整条删掉）
   * ③ 回池的劳动按**缺口信号**分派（唯一的一处信号：缺口 = need_i − 已保留之和）：
   *      **缺口大的先得**（并列按产业 id 序 ⇒ 可复现）
   * ④ 还分不出去的 ⇒ **农业/普通劳动**（本格**产粮**的那个产业；★ 它是"最后雇主"：不在它的产能上封顶）
   * ⑤ 连农业都没有 ⇒ **失业**（配额消失 —— 那批劳动不再被任何产业占着）
   * </pre>
   *
   * <p>★★ <b>"缺口信号"为什么必须有</b>：只有"回池"没有"吸收"，等于把劳动从停工产业里放出来之后就没人接 —— "工业化吸走劳动力"（织机/作坊有缺口 ⇒
   * 从农业手里把人吸过来）永远不会发生。本步的信号就是那个缺口， 而它是**数据算出来的**（产能 × 每单位劳动 − 现有配额），不是另拍的优先级。
   *
   * <p>★ <b>只在本格的产业处于"周期第一天"时重排</b>（{@code progressDays == 0}，与现扣投入同一天）：那时"这一周期 开得起来多大"已经由现扣步写进
   * {@code cycleInputUsedMilli} ⇒ 判定有据；周期中途重排会让同一周期内的劳动口径漂移。
   *
   * <p>★ <b>保序与幂等</b>：全部遍历都走保序表（配额表序 / 产业表序），并列用 id 序 ⇒ 同一份状态两次调用逐值相同 （§十一 的"一次 N 天 == N
   * 次单日"因此不受影响：重排只发生在周期第一天，而那一天的语义在两条路径上一致）。
   *
   * <p>★ <b>不改劳动供给表</b>：{@code Σ配额 ≤ available} 这条构造期不变量**构造性成立** —— 本步只把配额在产业之间搬、
   * 或把它删掉，**从不凭空增加**任何批次的配额总和。
   */
  private static void reallocateLabor(
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index) {
    Map<String, List<ProductionUnitId>> hexToUnits = new LinkedHashMap<>();
    for (ProductionUnitId id : units.keySet()) {
      IndustryHexKeys.hexKeyOf(units.get(id).industry())
          .ifPresent(hex -> hexToUnits.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(id));
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
      for (LaborAllocation allocation : allocations.values()) {
        ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
        if (idSet.contains(unitId)) {
          allocated.merge(unitId, allocation.laborMilli(), Long::sum);
        }
      }
      Map<ProductionUnitId, Long> need = new LinkedHashMap<>();
      for (ProductionUnitId id : ids) {
        ProductionUnit unit = units.get(id);
        Industry industry = industries.get(unit.industry());
        if (industry == null) {
          throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
        }
        need.put(
            id,
            laborNeedOf(
                unit, industry, index, allocated.getOrDefault(id, 0L), operatorConditions.get(id)));
      }
      // ② 保留 + 回池（逐 (批次, unit) 配额）
      Map<ProductionUnitId, Long> kept = new LinkedHashMap<>();
      Map<PeopleLotId, Map<HouseholdId, Long>> pool = new LinkedHashMap<>();
      for (LaborAllocationId allocId : new ArrayList<>(allocations.keySet())) {
        LaborAllocation allocation = allocations.get(allocId);
        ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
        if (!need.containsKey(unitId)) {
          continue; // 不是本格的配额（另一格的 unit）
        }
        long room = Math.max(0L, need.get(unitId) - kept.getOrDefault(unitId, 0L));
        long keep = Math.min(allocation.laborMilli(), room);
        kept.merge(unitId, keep, Long::sum);
        long released = allocation.laborMilli() - keep;
        if (keep <= 0L) {
          allocations.remove(allocId); // 整条回池：这个 unit 这一周期一点也用不上
        } else if (released > 0L) {
          allocations.put(allocId, withLaborMilli(allocation, keep));
        }
        if (released > 0L) {
          pool.computeIfAbsent(allocation.group(), ignored -> new LinkedHashMap<>())
              .merge(allocation.household(), released, Long::sum);
        }
      }
      if (pool.isEmpty()) {
        continue; // 每个 unit 都吃下了自己的配额 ⇒ 没有回池的劳动（真档多数格、多数周期是这一支）
      }
      // ③ 缺口大的先得（并列按 unit id 序；★ 信号 = need − 已保留）
      List<ProductionUnitId> byGap = new ArrayList<>(ids);
      byGap.sort(
          Comparator.comparingLong(
                  (ProductionUnitId id) -> -Math.max(0L, need.get(id) - kept.getOrDefault(id, 0L)))
              .thenComparing(ProductionUnitId::value));
      // ④ 最后雇主：本格**产粮**的那个 unit（"农业/普通劳动"那一档；不在产能上封顶）
      ProductionUnitId lastResort = null;
      for (ProductionUnitId id : ids) {
        Industry industry = industries.get(units.get(id).industry());
        if (industry != null && industry.recipe().outputPerUnit().containsKey(GRAIN)) {
          lastResort = id;
          break;
        }
      }
      for (Map.Entry<PeopleLotId, Map<HouseholdId, Long>> entry : pool.entrySet()) {
        PeopleLotId group = entry.getKey();
        for (Map.Entry<HouseholdId, Long> byHousehold : entry.getValue().entrySet()) {
          HouseholdId household = byHousehold.getKey();
          long left = byHousehold.getValue();
          for (ProductionUnitId id : byGap) {
            if (left <= 0L) {
              break;
            }
            long gap = Math.max(0L, need.get(id) - kept.getOrDefault(id, 0L));
            long give = Math.min(left, gap);
            if (give <= 0L) {
              continue;
            }
            addLabor(allocations, units.get(id), group, household, give);
            kept.merge(id, give, Long::sum);
            left -= give;
          }
          if (left > 0L && lastResort != null) {
            addLabor(allocations, units.get(lastResort), group, household, left);
            kept.merge(lastResort, left, Long::sum);
            left = 0L;
          }
          // ⑤ left > 0 ⇒ 失业：不生成配额（那批劳动不再被任何 unit 占着）—— 这就是本步要的"不再卡死"。
        }
      }
    }
  }

  /**
   * ★ <b>一个 unit "这一周期真的用得上的劳动"</b>（千分劳动·日）= {@code min(产能那一路, 投入那几路) × laborPerUnit}。
   *
   * <pre>
   * usableScale = min( ⌊usableAssets[k] ÷ capacityPerUnit[k]⌋ …, ⌊cycleInputUsedMilli[j] ÷ inputPerUnit[j]⌋ … )
   * need        = usableScale × 配方.laborPerUnit
   * </pre>
   *
   * <p>★ 与收获日的 {@link #scaleOf} 是**同一个算式的两个前缀**：这里**刻意不含劳动那一路**（劳动正是本步要求解的量）。
   */
  private static long laborNeedOf(
      ProductionUnit unit,
      Industry industry,
      SettlementIndex index,
      long allocated,
      OperatorCondition condition) {
    long laborPerUnit = industry.recipe().laborPerUnit();
    if (laborPerUnit <= 0L) {
      return allocated; // ★ 劳动那一路**不施加约束**（与 scaleOf 的同款口径）
    }
    long scale = ProductionUnitBook.plannedCapacityScaleOf(unit, industry, index, condition);
    for (Map.Entry<CommodityId, Long> entry : industry.recipe().inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long drawn = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / entry.getValue());
    }
    if (scale <= 0L) {
      return 0L; // 产能 0 或一点料都没有 ⇒ 这一周期一点劳动也用不上（回池）
    }
    long need = scale * laborPerUnit;
    return need < 0L ? Long.MAX_VALUE : need; // 溢出兜底（手搭夹具可能给天文数字的产能）
  }

  /**
   * 给某 (批次, unit) 加劳动：已有配额 ⇒ 累加；没有 ⇒ **新发一条**（id 由 {@link LaborAllocation#idOf(ProductionUnitId,
   * PeopleLotId, HouseholdId)} 给出）。
   *
   * <p>★★ <b>B.2c：旧档迁移过来的配额要按语义键命中，不能只看新 unit 型 id</b>。{@code
   * LegacyHouseholdMigration.canonicalizeAllocationActivities} 只改 activity/actor、**保留旧 id**（{@code
   * alloc-<产业>-<批次>-<家户>}）⇒ 旧档续跑时，同一 (unit, group, household) 的既有行不在 unit 型 id 上。 若这里只看 {@code
   * idOf(unit,...)}，会为同一语义键再发一条新 id：两行并存后，死亡缩放 （{@code scaleLaborOfGroup}/{@code
   * scaleLaborOfUnit}）会对两行**各取整一次**， 比旧口径的单行 {@code floor(ΣA×r)} 少 1 毫劳动（实测：799 条旧档 farm
   * 配额首次再分配后各多一行， tick30 月度死亡缩放时 388 条各少 1，且随时间可重复出现）。因此先按**旧口径的产业型 id** 找一次；找到后仍要核对 {@code
   * activity == 本 unit id}，防止同一产业将来有多 unit 时误并到别的 unit 的行上。
   */
  private static void addLabor(
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      ProductionUnit unit,
      PeopleLotId group,
      HouseholdId household,
      long amount) {
    if (amount <= 0L) {
      return;
    }
    LaborAllocationId id = LaborAllocation.idOf(unit.id(), group, household);
    LaborAllocation existing = allocations.get(id);
    if (existing == null) {
      // ★ B.2c：旧档 id 形如 alloc-<产业>-<批次>-<家户> ⇒ 用产业型 id 再找一次；activity 必须就是本 unit（见上）。
      LaborAllocation legacy =
          allocations.get(LaborAllocation.idOf(unit.industry(), group, household));
      if (legacy != null && legacy.activity().equals(unit.id().value())) {
        existing = legacy;
      }
    }
    if (existing != null) {
      allocations.put(existing.id(), withLaborMilli(existing, existing.laborMilli() + amount));
      return;
    }
    // ★ 新配额的 activity = unit id（结算按它归集）；period 取该 unit 既有条目的（没有 ⇒ 1）。
    LaborAllocation template = templateAllocationOf(allocations, unit.id());
    long period = template == null ? 1L : template.period();
    allocations.put(
        id,
        new LaborAllocation(
            id, group, household, unit.operator(), unit.id().value(), amount, period));
  }

  /** 同一 unit 在配额表里的**既有条目**（用来抄 {@code period}；activity 就是 unit id 本身，不再是"调用方给的词"）。 */
  private static LaborAllocation templateAllocationOf(
      Map<LaborAllocationId, LaborAllocation> allocations, ProductionUnitId unitId) {
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.activity().equals(unitId.value())) {
        return allocation;
      }
    }
    return null;
  }

  /** 换劳动量（其余字段原样带过）—— 配额缩小与累加共用。 */
  private static LaborAllocation withLaborMilli(LaborAllocation allocation, long laborMilli) {
    return new LaborAllocation(
        allocation.id(),
        allocation.group(),
        allocation.household(),
        allocation.actor(),
        allocation.activity(),
        laborMilli,
        allocation.period());
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

  /**
   * ★★ <b>周期末的产出（§四 周期结算 1~5；税明确不做）</b>—— T4 起**产出不再写进阶层行**（R5 ②）。
   *
   * <p>★★ <b>本方法的形状（一步都不能少）</b>：
   *
   * <pre>
   * ① 规模 = 最紧约束（{@link #scaleOf}，R3/V7 泛化后的那一条 —— **一字未改**）
   * ② 逐商品：毛产 = 规模 × outputPerUnit_j × 1000；损耗 = 毛产 × (饲料 + 折旧)‰；净产 = 毛产 − 损耗
   *      · 毛产 / 损耗 → ledger（I4.2 的 ΣOutput / ΣLoss）
   *      · **净产 → operator 的产权条目**（+net 一条；★ 产出离开 ClassRow 的**唯一去处**）
   * ③ 按 relation 结算（{@link ProductionSettlement}）：转出/收入**都是产权条目**（H1：受方恒为 actor，
   *      {@code ToCohort} 的家户 actor = {@code HouseholdActors.of(cohort)}）
   * ④ 家户那一条**同时记进两处**：会话工作副本（{@code householdGoods}，下一日消费读它）+ 本行流水 {@code income}
   *      （读口）—— ★ 条目本身照旧进 ledger（app 侧落盘的口径见 {@code EconomyDayStepper#step}）
   * </pre>
   *
   * <p>★★ <b>为什么 ② 与 ③ 必须在同一个方法里</b>（T4 与 T5 必须同批落地的全部理由）：产出一旦离开 {@code ClassRow}， 家户唯一还有实物的通道就只剩 ③
   * 的实付 —— 少了它，**家户当场断粮**（而账面上看不出少了谁：产权条目照旧生成）。 由 {@code
   * ProductionLedgerTest#theCohortGetsItsPaidShareIntoTheConsumptionRow} 逐值守着。
   *
   * <p>★★ <b>H1.3：{@code deliverCohortIntake} 已删</b>（那一步做的是"按人口把 cohort 入账分派到行"）：受方就是**唯一那个家户** ⇒
   * 分派、取整余数、以及"解析不到行 ⇒ 补一条 {@code +unresolved} 留在 operator"的兜底**全都不再需要**。 取而代之的是**两条
   * fail-closed**（都在 {@link #requireCohortRows} 里，判在所有公式之前）：
   *
   * <ul>
   *   <li>规则指名的 cohort **必须有行**（家户不存在 ⇒ 当场抛；不许静默留在 operator —— 那会把"配置错了"伪装成"operator 自留"）；
   *   <li>该 cohort **必须住在这一格**（{@code cohort.hex() == facts.location()}）：家户账只住在它自己的格 （{@code
   *       GoodsAccountKey(actor, cohort.hex())}），否则条目与账本会落在两个地方。
   * </ul>
   *
   * <p>★ <b>缺 {@code relation} 的产业</b>（{@code relations} 表里没有它）：<b>没有规则要结算</b>，产出全部留在 operator ——
   * 与"空规则表 ⇒ 全归 {@code residualOwner}"是**同一条等价路径**（裁定 E9）。
   *
   * <p>★ <b>如实记的边界</b>：{@link ProductionSettlement.Facts#inputs()} 本阶段没有公式读它（表里没有用到它的档）。
   *
   * @param cycledLabor 本周期累计实际劳动（千分劳动·日）；`/cycleDays` 得**平均每日实际劳动**
   * @param income 逐家户的实物入账累加器（关系实付那几笔落在这里 ⇒ 读口与守恒式都读它）
   * @param householdGoods ★★ 家户账的会话工作副本（**就地更新**：关系实付计进来）
   * @param relations 生产关系表（键 = 产业 id；缺键 ⇒ 无规则）
   * @param ledger 当天的发生额累加器（毛产 / 损耗 / 产权条目 / 货币待办都进这里）
   */
  private static void harvest(
      ProductionUnit unit,
      Industry industry,
      LinkedHashMap<HouseholdId, ClassRow> rows,
      long cycledLabor,
      long plannedPerMille,
      SettlementIndex index,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal) {
    ProductionRecipe recipe = industry.recipe();
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    // ★★ **R3B.2：产能那一路从 {@code AssetShare} 派生**（{@code ProductionUnitBook} 是唯一拼写点）。
    //   ★ S3：再乘状态机的计划规模系数（缩产/停业不改 AssetShare，只改本周期计划）。
    long scale = scaleOf(unit, industry, index, avgLaborMilli, plannedPerMille); // ★ 最紧约束

    HexCoord location = hexOfIndustry(industry.id());
    ActorRef operator = unit.operator();
    // ★★ **逐商品产出入账**：毛产 = 规模 × outputPerUnit[j] × 1000 毫/单位（算式一字未改）。
    Map<CommodityId, Long> grossByCommodity = new LinkedHashMap<>();
    Map<CommodityId, Long> netByCommodity = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> output : recipe.outputPerUnit().entrySet()) {
      CommodityId commodity = output.getKey();
      long gross = scale * output.getValue() * MILLI_PER_GRAIN; // 毫单位（毛产出）
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
      // ★★ **R5 ②：净产进产出计提（+净产 → operator），不再写进本产业的行**。
      //   ★ `location` = 该产业所在的那一格（{@link IndustryHexKeys} 是唯一拼写点）—— 账户 = (actor, location)。
      //   ★ H2：它**不是一条转移**（产出是造出来的、没有对端；转移两端不许相等）—— 见 ActorEntry 的类注。
      ledger.addOutputAccrual(
          new ProductionSettlement.ActorEntry(operator, location, commodity, net));
      // ★★ **H5：同一笔计提也记进会话副本**（家户 operator ⇒ 家户账；经营者 ⇒ 经营者账）——
      //   否则这台"副本 = 终值"的账在两种情形下都对不上：① 家户 operator 的那一笔会被绝对落回**抹掉**
      //   （H3 如实记下的静默丢产出）；② 经营者账里看不到自己刚产出的东西（随后的关系实付就从它账上扣不动）。
      creditOutput(householdGoods, operatorGoods, householdOfActor, operator, commodity, net);
    }
    if (netByCommodity.isEmpty()) {
      return; // 规模 0（或产出表为空）⇒ 没有产出、也没有可付的：连规则都不必结算
    }
    ProductionRelation relation = relations.get(unit.id());
    if (relation == null) {
      return; // 缺 relation ⇒ 没有规则：产出全部留在 operator（等价路径，见方法注释）
    }
    // ★★ **H1.3 的 fail-closed**：受方家户必须存在、且住在这一格（判在所有公式之前 —— 与 E14 同款）。
    requireCohortRows(relation, rows, location);
    // ★★ **R5 ③：按 relation 结算**（priority 序、付款上限 = 本周期收到的产出、E14 的守卫都在 {@link
    //   ProductionSettlement} 里 —— 本方法只负责"把事实递给它、把结果落到该落的地方"）。
    //   ★ H2：**铸造口传的是当天的累加器** ⇒ 每条实付的 id 是 `tr-<day>-<seq>`（序号按天、按本账本自增）。
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation,
            new ProductionSettlement.Facts(
                location,
                grossByCommodity,
                netByCommodity,
                unit.cycleInputUsedMilli(),
                laborOfCohort(rows, location, industry.cycleDays()),
                industry.outputPerUnit(),
                // ★★ H4/H5：货币档的付款上限 = **付方（operator）在本格可见的货币余额**（逐币种）：
                //   家户 actor ⇒ 它的家户钱包；★ H5 起聚合主体（ESTATE / WORKSHOP / 产业型 HOUSEHOLD，id 形如
                //   `farm@0_0`）⇒ **它自己的经营者钱包**（创世播的那一本，由协调器载入 operatorMoney）。
                //   ★ 这个世界没有那本账（手搭夹具）⇒ 可用 0（实付 0、欠额进读数 —— 如实报，不是静默付 0）。
                availableMoneyOf(householdMoney, operatorMoney, householdOfActor, operator)),
            ledger);
    // ★★ **H2：实付一律是转移**（每条 from=operator、to=受方）—— 铸的时候已经进了当天的账，这里只需
    //   ① 把两端落到会话副本上（唯一 applier）② 把"收方是家户"的那一笔记进流水（实物入账读数）。
    //   ★ app 落盘时按副本的**绝对值**写回，不许把这条 +paid 再叠加一次（叠加 = 同一笔粮记两遍；
    //     见 EconomyDayStepper#step 的类注）。
    for (Transfer transfer : outcome.transfers()) {
      applyTransfer(
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          householdOfActor,
          transfer,
          issuanceJournal);
      HouseholdId cohort = householdOfActor.get(transfer.to());
      if (cohort != null) {
        for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
          addGoods(income, cohort, leg.getKey(), leg.getValue());
        }
      }
    }
    // ★★ **S4：逐规则的实得读数**（应付 / 实付 / 欠）—— 只读、不影响守恒、不落债权。
    for (ProductionSettlement.RuleSettlement reading : outcome.ruleSettlements()) {
      ledger.addRuleSettlement(reading);
    }
    for (CompensationRule rule : outcome.deferredMoney()) {
      ledger.addDeferred(rule);
    }
  }

  // ── 转移的落账（H2：唯一写会话副本的地方）────────────────────────────────────────────

  /**
   * ★★ <b>把一条转移的两端落到会话工作副本上</b>（H2；★ H5 起**家户账 + 经营者账**）—— <b>全模块唯一</b>写这些副本库存的
   * "换手"路径（另一类是消费/投入的扣减，见 {@link #consumeFromHousehold} 与 {@code drawCycleInputs}）。
   *
   * <p>★★ <b>为什么必须收成一个函数</b>：改前"东西换手"有三处各写各的（结算的收支条目、同格取材的两处 {@code setStock}、 借粮的单腿扣减）—— 三处各写各的 ⇒
   * 加第四条路（市场）时没人拦得住它长成第四套写法。收成一处之后， "任何库存变动必有对应转移记录"这条不变量才有唯一的落点可审。
   *
   * <p>★★ <b>两端各在哪本账</b>（H5 的收口）：
   *
   * <ol>
   *   <li>{@code householdOfActor} 命中 ⇒ <b>家户账</b>（键 = {@code HouseholdId}，会话内部解析成 {@code (actor,
   *       location)} 账户键）；
   *   <li>否则若 `operatorGoods` / `operatorMoney` 里有这个主体 ⇒ <b>经营者账</b>（键 = {@link ActorRef}）—— 这就是
   *       H3/H4 那条"聚合主体 ⇒ 可用 0 ⇒ 实付 0"的收口：经营者自己持账之后，实付真的从它账上出；
   *   <li>两者都没有（手搭夹具、或这个世界还没给经营者播种）⇒ **跳过这一端**：它的账住在 actor 切片上， 由 app 协调器按 {@code
   *       ledger.transfers()} 落账（H2 的既有口径，逐字不改）。★ 本类**只**写自己那两份会话副本。
   * </ol>
   *
   * <p>★★ <b>M1.4：本方法是两遍式</b>（照 {@link #drawCycleInputs} 的三遍式先例）：第一遍 {@link #validateApplyTransfer}
   * **只读**地判"每一条付方腿是否扣得动"（余额不足 / 货币不足 ⇒ 走发行闸门）， 任一条不合法都在**会话活表一字未动**时抛出；第二遍才统一落账 ——
   * 于是"付方扣了、收方没加"的半笔在结构上不可能。 ★ <b>仍然只有一个 applier</b>：校验是只读的、落账仍只在本方法内（经营者那一端走的是既有的私有 {@code
   * debitOperator}，M1.4 把判据摘掉了，避免同一算式两处拼写）。
   *
   * <p>★ <b>成功路径逐值不变</b>：第二遍的写入次序与旧路径逐字相同（家户/经营者付端 → 收端），只是 "边判边扣"改成了"先判完再扣"。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    // ★ 旧的五副本入口 = "这个世界没有冻结"（M1.4 之前的调用点逐字不改）；带冻结的调用走下面的重载。
    applyTransfer(
        householdGoods,
        householdMoney,
        operatorGoods,
        operatorMoney,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        householdOfActor,
        transfer,
        null);
  }

  /**
   * ★★ E3：带发行审计落点的五副本入口（无冻结）—— 发行腿只在付方 = 当前政府国库、且余额不足时使用； {@code journal} 为 null ⇒ 发行腿
   * fail-closed（不许造钱而没有审计记录）。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    applyTransfer(
        householdGoods,
        householdMoney,
        operatorGoods,
        operatorMoney,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        householdOfActor,
        transfer,
        journal);
  }

  /**
   * ★★ <b>M2：带冻结表的 {@code applyTransfer}</b>（唯一写口的同一处实现，M1.4 的两遍式一字不改）—— 第一遍除了"余额够不够"，
   * 还判"扣完以后还剩多少、会不会花掉<b>已冻结</b>的那一部分"。
   *
   * <p>★ E3：本重载不接受发行审计落点（{@code journal = null}）—— 普通转移路径逐值不变；若真的需要发行腿，调用方走带 {@link
   * MoneyIssuanceJournal} 的重载。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    applyTransfer(
        householdGoods,
        householdMoney,
        operatorGoods,
        operatorMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        operatorFrozenGoods,
        operatorFrozenMoney,
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
   *
   * <p>★ 商品腿不受发行影响：商品余额不足依旧抛，不允许借发行路径吞掉商品。
   */
  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    Objects.requireNonNull(transfer, "transfer");
    // ★★ M1.4 第一遍：**全量校验**（只读）—— 任一条腿不合法都在会话活表一字未动时抛出。
    validateApplyTransfer(
        householdGoods,
        householdMoney,
        operatorGoods,
        operatorMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        operatorFrozenGoods,
        operatorFrozenMoney,
        householdOfActor,
        transfer,
        journal);
    // ★★ M1.4 第二遍：**统一落账** —— 此刻所有付方腿的可扣性都已验证过 ⇒ 下面只写、不再判。
    HouseholdId from = householdOfActor.get(transfer.from());
    if (from != null) {
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        setStock(
            householdGoods,
            from,
            leg.getKey(),
            stockOf(householdGoods, from, leg.getKey()) - leg.getValue());
      }
      debitHouseholdMoney(householdMoney, householdFrozenMoney, from, transfer, journal);
    } else if (operatorGoods.containsKey(transfer.from())
        || operatorMoney.containsKey(transfer.from())) {
      // ★ 经营者付端：商品腿纯落账；货币腿可能走发行腿（见 debitOperatorMoney）。
      if (operatorGoods.containsKey(transfer.from())) {
        for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
          setOperatorStock(
              operatorGoods,
              transfer.from(),
              leg.getKey(),
              operatorStockOf(operatorGoods, transfer.from(), leg.getKey()) - leg.getValue());
        }
      }
      if (operatorMoney.containsKey(transfer.from())) {
        debitOperatorMoney(operatorMoney, operatorFrozenMoney, transfer.from(), transfer, journal);
      }
    }
    HouseholdId to = householdOfActor.get(transfer.to());
    if (to != null) {
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        addStock(householdGoods, to, leg.getKey(), leg.getValue());
      }
      for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
        addMoney(householdMoney, to, leg.getKey(), leg.getValue());
      }
    } else if (operatorGoods.containsKey(transfer.to())
        || operatorMoney.containsKey(transfer.to())) {
      creditOperator(
          operatorGoods, operatorMoney, transfer.to(), transfer.goods(), transfer.money());
    }
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

  /** 经营者货币付端：与 {@link #debitHouseholdMoney} 逐字同一条口径（只是账键不同）。 */
  private static void debitOperatorMoney(
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      ActorRef from,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      CurrencyId currency = leg.getKey();
      long amount = leg.getValue();
      long balance = operatorMoneyOf(operatorMoney, from, currency);
      long frozen = operatorFrozenMoney.getOrDefault(from, Map.of()).getOrDefault(currency, 0L);
      long debit = amount;
      if (balance < amount) {
        ActorRef issuer = MoneyIssuance.requireIssuerOf(currency);
        if (!from.equals(issuer)) {
          throw new IllegalStateException(
              "转移把经营者的货币扣成了负数，而付方不是发行源（透支 = 发行）：经营者="
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
      setOperatorMoney(operatorMoney, from, currency, balance - debit);
    }
  }

  /**
   * ★★ <b>M1.4 第一遍：把"这一次转移扣得动吗"一次判完</b>（只读；照 {@link #drawCycleInputs} 的"调查 → 配给 → 落账"先例）。
   *
   * <p>★ E3 起货币不足的判据收成两条：付方不是发行源 ⇒ 保留旧 fail-closed 文案；付方是发行源 ⇒ 必须带 {@link
   * MoneyIssuanceJournal}（否则不静默发行），且只扣实际持有的部分（冻结仍然受保护）。
   */
  private static void validateApplyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    HouseholdId from = householdOfActor.get(transfer.from());
    if (from != null) {
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
      return;
    }
    if (!operatorGoods.containsKey(transfer.from())
        && !operatorMoney.containsKey(transfer.from())) {
      // ★ E3：发行主体不在本会话副本里 ⇒ 不能静默跳过（那会让收方足额到账却没有发行审计）。
      for (CurrencyId currency : transfer.money().keySet()) {
        ActorRef issuer = MoneyIssuance.issuerOfRegistered(currency);
        if (issuer != null && issuer.equals(transfer.from())) {
          throw new IllegalStateException(
              "发行主体的账不在本会话副本里，拒绝静默发行（账户必须先载入会话）：付方="
                  + transfer.from()
                  + " 币种="
                  + currency
                  + "；转移="
                  + transfer);
        }
      }
      return; // 两端都不在会话副本里 ⇒ 本类不落这一端（既有口径，见类注第 ③ 条）。
    }
    if (operatorGoods.containsKey(transfer.from())) {
      for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
        long stock = operatorStockOf(operatorGoods, transfer.from(), leg.getKey());
        if (stock < leg.getValue()) {
          throw new IllegalStateException(
              "转移把经营者账扣成了负数（不许凭空吞）：经营者="
                  + transfer.from()
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
            operatorFrozenGoods
                .getOrDefault(transfer.from(), Map.of())
                .getOrDefault(leg.getKey(), 0L);
        if (stock - leg.getValue() < frozen) {
          throw new IllegalStateException(
              "转移会花掉经营者账上已冻结的商品（冻结只表达已明确的占用）：经营者="
                  + transfer.from()
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
    }
    if (operatorMoney.containsKey(transfer.from())) {
      for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
        long balance = operatorMoneyOf(operatorMoney, transfer.from(), leg.getKey());
        long frozen =
            operatorFrozenMoney
                .getOrDefault(transfer.from(), Map.of())
                .getOrDefault(leg.getKey(), 0L);
        if (balance < leg.getValue()) {
          ActorRef issuer = MoneyIssuance.requireIssuerOf(leg.getKey());
          if (!transfer.from().equals(issuer)) {
            throw new IllegalStateException(
                "转移把经营者的货币扣成了负数，而付方不是发行源（透支 = 发行）：经营者="
                    + transfer.from()
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
                "经营者付方是发行源，但这条入口没有发行审计落点，拒绝单边发行（不许造钱而没有审计记录）：转移=" + transfer);
          }
          continue;
        }
        if (balance - leg.getValue() < frozen) {
          throw new IllegalStateException(
              "转移会花掉经营者账上已冻结的货币（冻结只表达已明确的占用）：经营者="
                  + transfer.from()
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
  }

  /**
   * ★★ <b>E3：一次结算会话的发行审计收集器</b>（线程安全，供并行分区提交发行腿）。id 由 {@link
   * MoneyIssuanceId#forTransfer(TransferId, CurrencyId)} 确定性派生，因此重放/分支不会因线程调度产生不同记录。
   */
  static final class MoneyIssuanceJournal {

    private final long period;
    private final Map<ActorRef, GovernmentId> governmentByTreasury;
    private final ConcurrentLinkedQueue<MoneyIssuanceRecord> records =
        new ConcurrentLinkedQueue<>();

    MoneyIssuanceJournal(Map<GovernmentId, Government> governments, long period) {
      this.period = period;
      LinkedHashMap<ActorRef, GovernmentId> byTreasury = new LinkedHashMap<>();
      for (Government government : governments.values()) {
        GovernmentId previous = byTreasury.putIfAbsent(government.treasury(), government.id());
        if (previous != null && !previous.equals(government.id())) {
          throw new IllegalStateException(
              "同一个国库 actor 对应两个政府，无法写发行审计："
                  + government.treasury()
                  + " → "
                  + previous
                  + " / "
                  + government.id());
        }
      }
      this.governmentByTreasury = Map.copyOf(byTreasury);
    }

    /** 记录一条单边发行差额（金额 &gt; 0；发行主体必须是当前政府表里的国库）。 */
    void recordIssuance(Transfer transfer, CurrencyId currency, long amount) {
      if (amount <= 0L) {
        throw new IllegalArgumentException("发行差额必须 > 0: " + amount);
      }
      GovernmentId governmentId = governmentByTreasury.get(transfer.from());
      if (governmentId == null) {
        throw new IllegalStateException(
            "发行腿的付方不在当前政府表里（说不出是哪届政府在发行）：" + transfer.from() + "；转移=" + transfer);
      }
      records.add(
          new MoneyIssuanceRecord(
              MoneyIssuanceId.forTransfer(transfer.id(), currency),
              governmentId,
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

  /** 经营者账的**收端**（商品 + 货币两条腿；缺哪本账就跳过哪条腿 —— 见 {@link #applyTransfer}）。 */
  private static void creditOperator(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      ActorRef actor,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    if (operatorGoods.containsKey(actor)) {
      for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
        addOperatorStock(operatorGoods, actor, leg.getKey(), leg.getValue());
      }
    }
    if (operatorMoney.containsKey(actor)) {
      for (Map.Entry<CurrencyId, Long> leg : money.entrySet()) {
        addOperatorMoney(operatorMoney, actor, leg.getKey(), leg.getValue());
      }
    }
  }

  /**
   * 经营者账的**付端纯落账**：与家户那一端**逐字同一条口径**。
   *
   * <p>★★ <b>调用前必须先过 {@link #validateApplyTransfer}（M1.4 两遍式）</b> —— 判据（商品余额够不够、货币够不够）
   * 只写在第一遍那一处；这里再写一遍就是同一算式的第二个拼写点，两边一旦漂移没人发现（旧版正是"边判边扣"）。
   */
  private static void debitOperator(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      ActorRef actor,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    if (operatorGoods.containsKey(actor)) {
      for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
        setOperatorStock(
            operatorGoods,
            actor,
            leg.getKey(),
            operatorStockOf(operatorGoods, actor, leg.getKey()) - leg.getValue());
      }
    }
    if (operatorMoney.containsKey(actor)) {
      for (Map.Entry<CurrencyId, Long> leg : money.entrySet()) {
        setOperatorMoney(
            operatorMoney,
            actor,
            leg.getKey(),
            operatorMoneyOf(operatorMoney, actor, leg.getKey()) - leg.getValue());
      }
    }
  }

  /**
   * ★ <b>从家户副本里吃掉一笔</b>（消费 / 当日借入即食）：扣余额 + 记当日消费 —— 两条痕在**同一处**写。
   *
   * <p>★ 借粮那一支靠它把"借入的那一笔"当场吃掉（转移 + 消费），于是借方余额净 0（与改前的口径逐值相同： 改前根本不给借方加库存）。
   *
   * @param amount 想吃的量（余额不足 ⇒ 只吃余额，<b>不抛</b>：消费不是转移，"缸里有多少吃多少"是既有的口径）
   */
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
   * ★ <b>家户 actor → 家户身份</b>的反查表（H2；一天建一次）：三个落点共用同一份 —— "这条转移的某一端是不是家户" 只能有一个答案。
   *
   * <p>★ 键 = {@code HouseholdActors.of(cohort)}（家户 actor id 的唯一拼写点，K9）；★ 表**不含**经营者 （它们的账在 actor
   * 切片上，见 {@link #applyTransferToHouseholds}）。
   */
  private static Map<ActorRef, HouseholdId> householdActorsOf(Map<HouseholdId, ClassRow> rows) {
    Map<ActorRef, HouseholdId> householdOfActor = new LinkedHashMap<>();
    for (HouseholdId key : rows.keySet()) {
      householdOfActor.put(HouseholdActors.of(key), key);
    }
    return householdOfActor;
  }

  /**
   * ★★ <b>受方家户的 fail-closed 守卫</b>（H1.3；{@code deliverCohortIntake} 的替代品）：逐条 {@code
   * Recipient.ToCohort} 规则判两件事 —— <b>行在</b>、<b>它住在本格</b>。
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
      ProductionRelation relation, Map<HouseholdId, ClassRow> rows, HexCoord location) {
    for (CompensationRule rule : relation.rules()) {
      HouseholdId household;
      String source;
      if (rule.recipient() instanceof Recipient.ToHousehold toHousehold) {
        household = toHousehold.household();
        source = "家户";
      } else if (rule.recipient() instanceof Recipient.ToCohort toCohort) {
        // ★ 旧档视图（S1 迁移前）：按视图反查**恰一个**家户；多于一户 ⇒ fail-closed（视图不再是身份）。
        household = null;
        for (ClassRow row : rows.values()) {
          if (row.view().equals(toCohort.cohort())) {
            if (household != null) {
              throw new IllegalStateException(
                  "规则指名的旧 cohort 视图对上了多个家户（S1 起视图不再是唯一身份）：" + toCohort.cohort() + "；规则=" + rule);
            }
            household = row.id();
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
      ClassRow row = rows.get(household);
      if (row == null) {
        throw new IllegalStateException(
            "规则指名的家户没有行（S1 fail-closed：受方就是那一个家户，交付不出去就当场抛）："
                + source
                + "="
                + household
                + "；规则="
                + rule);
      }
      if (!row.view().hex().equals(location)) {
        throw new IllegalStateException(
            "规则指名的家户不住在本格（H1：家户账的 location 取自它的视图，条目却落在产业所在格）：activity="
                + relation.activity()
                + " "
                + source
                + "="
                + household
                + " 视图格="
                + row.view().hex()
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
   * <p>★★ <b>M1.8：折算只有一处拼写点</b> —— {@link ClassRow#participationAdjustedLaborMilli()}（= {@code
   * laborMilli × participationPerMille ÷ 1000}）。本方法<b>不再自己乘一次</b>参与率：否则读口/配额与这里会各折算一遍，真档数字会崩。
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
      Map<HouseholdId, ClassRow> rows, HexCoord location, long cycleDays) {
    Map<HouseholdId, Long> byCohort = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      if (!entry.getValue().view().hex().equals(location)) {
        continue; // 只取本格的家户（见方法注释：分母的口径与改前逐字相同）
      }
      ClassRow row = entry.getValue();
      // ★★ **每日口径 × 周期天数 = 本周期口径**（量纲的收口）：行的 `laborMilli` 与发放的配额同口径
      //   （**每日**千分劳动；结算逐日把它累加进 `cycleLaborMilli`），而 {@code LABOR_AMOUNT} 那一族量的是
      //   **本周期**的劳动量（{@code FIXED_IN_KIND_PER_LABOR} 的"每周期一笔"就写在名字里）。
      //   ★ 少了这个乘数，给养只有应有值的 1/cycleDays —— 实测（真档 14,806 人的格）：一周期只拿到 0.8% 的口粮，
      //     第 2 周期起人吃不饱、也播不下种 ⇒ 生产逐周期崩掉（`EconomyRealScaleClothTest` / `WorldgenInitializeToolTest`
      //     的跨周期用例当场红）。★ 分成类（`OUTPUT_SHARE`）不受影响：那一路是比值，量纲自消。
      //   ★ M1.8：参与率的折算收进 ClassRow 的唯一算法（上面乘一次，这里不许再乘）。
      long rowLabor = row.participationAdjustedLaborMilli() * cycleDays;
      if (rowLabor <= 0L) {
        continue;
      }
      byCohort.put(entry.getKey(), rowLabor);
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
   * ★★ **规模 = 最紧约束**（spec §五 原文；R3/V7 把 {@code harvest} 里的"三路全是亩"泛化成"每种 capacity 一路 + 劳动一路 +
   * 每种投入一路"）：
   *
   * <pre>
   * scale = min( ⌊industry.capacity[k] ÷ capacityPerUnit[k]⌋    …每种生产资料一路（★ K3：产能住在产业上）
   *            , ⌊平均每日实际劳动 ÷ laborPerUnit⌋               …劳动一路
   *            , ⌊本周期实际扣到的投入_j ÷ inputPerUnit[j]⌋        …每种投入一路 )
   * </pre>
   *
   * <p>★★ <b>K3（2026-09-27）：产能那一份改读 {@link Industry#capacity()}</b>（本格该产业的产能总量），不再 Σ 各行的 {@code
   * meansOfProduction}（那个字段已随 K2/K3 删除）。★ <b>形状不变</b>（三路取 min 一字未改），变的只是产能的来源： 改前"Σ各行"隐含"贫农缸空 ⇒
   * 它的地荒着"的阶级差异，改后这份差异由**投入由谁出**表达（H3 起 = {@code relation.inputSupplier} 指名的那一个主体； ★ H0
   * 阶段那套"按人口占比折算"的 {@code rowSharesOf} 已随本批删除）⇒ 真档数值允许变（K3 已认）。
   *
   * <p>★★ **每一路都可以"不施加"**（该路的"每单位需求"为 {@code 0} 或该路的表里没有这一项）：这与旧代码 {@code seedPerMu == 0 ⇒ 不加约束}
   * 是**同一条口径** —— 旧档与未配投入/未配劳动的产业据此与 V2 逐值一致，**不是**"规模 0"（写成 0 会让它们颗粒无收）。
   *
   * <p>★ 整数运算、向下取整；{@code capacityPerUnit} 非空且逐值 &gt; 0（构造期守卫）⇒ 结果必有上界。
   *
   * @param avgLaborMilli 平均每日实际劳动（千分劳动）= 本周期配额之和 ÷ cycleDays
   */
  private static long scaleOf(
      ProductionUnit unit,
      Industry industry,
      SettlementIndex index,
      long avgLaborMilli,
      long plannedPerMille) {
    ProductionRecipe recipe = industry.recipe();
    // ★★ **R3B.2：产能那一路 = unit 的可用资产 ÷ 每单位需求**（{@link ProductionUnitBook} 是唯一拼写点）。
    long scale = ProductionUnitBook.capacityScaleOf(unit, industry, index);
    // ★★ S3：状态机的计划规模系数（缩产/停业）—— 只压"本周期计划"，AssetShare 原样保留。
    scale = scale * plannedPerMille / 1_000L;
    if (recipe.laborPerUnit() > 0L) {
      scale = Math.min(scale, avgLaborMilli / recipe.laborPerUnit());
    }
    for (Map.Entry<CommodityId, Long> entry : recipe.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue; // 每单位需求为 0 ⇒ 这一路不构成约束（与旧代码 seedPerMu == 0 同款）
      }
      long drawn = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / entry.getValue());
    }
    return scale;
  }

  /**
   * ★★ **周期末的饿死判据**（2026-09-25 用户点名；**默认不致命** —— 见 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）：把本周期逐日累加的未满足需求 {@code cycleUnmet} 折成"**饿满整个周期的那个比例**"，再在这一比例的人口里按
   * {@code famineMortalityPerMille} 致死。
   *
   * <pre>
   * long cycleNeed = cumulativeRationMilli(pop, day) − cumulativeRationMilli(pop, day − cycleDays); // 本周期总需求
   * int  faminePerMille = cycleNeed == 0 ? 0 : min(1000, cycleUnmet × 1000 / cycleNeed);
   * long deaths = population × faminePerMille / 1000 × famineMortalityPerMille / 1000;
   * </pre>
   *
   * <p>★ **本周期总需求用累计函数之差**（不是 {@code 人口 × 一天的量 × 天数}）：日耗是逐日差分的，乘不出来； 而两个累计值之差**恰好**等于本周期各日口粮之和
   * （逐日差分的望远镜求和）。
   *
   * <p>★ 人口减少后，**有效劳动按同一比例缩**（{@code labor = labor × (population − deaths) / population}；{@code
   * population == 0} ⇒ {@code labor = 0}，**不除零**）；死亡数记入本行流水（{@code deaths}）。**死亡不回溯产出**：
   * 本周期收获已在调用方先行分配（照分给幸存者）。
   *
   * <p>★ 不变量：{@code faminePerMille ≤ 1000} 且致死率 {@code ≤ 1000‰} ⇒ {@code deaths ≤
   * population}、{@code 人口 ≥ 0}、 {@code labor ≥ 0}（构造期由 {@link ClassRow} 再兜一层）。
   *
   * <p>★★ **R4 起这条旧账已收口**（R2 如实记过的那处不齐）：本节缩的**行**劳动之外，调用方还会把该产业名下的**全部劳动配额**
   * 与对应批次的**劳动供给**按同一个存活比例缩（{@link #scaleLaborOfIndustry}）—— 于是"人死了劳动没减"不再成立。 ★ 另一条死亡路径（生理压力，见
   * {@link #applyPopulationChange}）则按**批次**缩：两条路径各自缩自己那份账， 都落在同一条不变量（{@code Σ allocated ≤
   * available}）上。
   *
   * <p>★ **它现在还是"直接按缺口处死"那个独立旋钮**（默认 0‰）：R4 起真正的日常死亡走生理压力那条路 （{@code PopulationDynamics}
   * 的月度结算），本方法的致死率仍由 {@link #FAMINE_MORTALITY_PER_MILLE} 控制， 且**逐值用例仍钉着非 0 那一条路**（不是死分支）。
   */
  private static void applyFamine(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<HouseholdId, Long> deaths,
      HouseholdId key,
      ClassRow row,
      long cycleUnmet,
      long day,
      long cycleDays,
      int famineMortalityPerMille) {
    long population = row.population();
    // ★ 本周期总需求 = 本周期**实际经过的那些天**的口粮之和 = 累计(day) − 累计(周期起点)。
    //   ★ 周期起点取 max(0, day − cycleDays)：夹具/存档可以在"周期已满"（progressDays == cycleDays）处入场，
    //     那时起点算到第 0 天之前 —— 而世界之前没有天，需求与缺口都只覆盖真实经过的天（两者口径一致）。
    long cycleStart = Math.max(0L, day - cycleDays);
    long cycleNeed =
        EconomyVocabulary.cumulativeRationMilli(population, day)
            - EconomyVocabulary.cumulativeRationMilli(population, cycleStart);
    int faminePerMille =
        cycleNeed == 0L ? 0 : (int) Math.min(1000L, cycleUnmet * 1000L / cycleNeed);
    long dead = population * faminePerMille / 1000L * famineMortalityPerMille / 1000L;
    if (dead <= 0L) {
      return;
    }
    long nextPopulation = population - dead; // faminePerMille ≤ 1000 且致死率 ≤ 1000‰ ⇒ 必 ≥ 0
    long nextLabor =
        population == 0L ? 0L : row.laborMilli() * nextPopulation / population; // 同比例缩，不除零
    rows.put(key, withPopulationAndLabor(row, nextPopulation, nextLabor));
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
   * ★★ **按 actor id 归集全部劳动配额**（R2；第三阶段设计稿 §四）：{@code actor id → 承诺投入的劳动之和}。
   *
   * <p>★ **归属的唯一判据就是 {@code actor.id()}**：产业型主体的 id 就是该产业的 {@link IndustryId}（{@code EconomyData}
   * 的构造期守卫把这个对应关系判死 —— 产业型（庄园/作坊）必须指名已存在的产业，非产业型（家户）不得与产业 id 撞名）。
   * 于是"这一格的劳动被哪个产业占了多少"在结算侧**不需要**额外的映射表。
   *
   * <p>★ **本轮配额是常设的**（跨周期不变）：{@code LaborAllocation.period()} 是"哪一周期发的"，
   * 由构造期守卫判它必须与供给记录同期；"按周期重发配额"（设计稿 §四 的"同一 period 内"）要等产生它的命令落地，届时这里改成取当前周期的那些配额。
   *
   * <p>★ **非产业型主体（家户）的配额照归集**：它不进任何产业的 {@code cycleLaborMilli}（家户织布是 R3 的配方）， 但**照进守恒与读口** ——
   * 不是"记了没人看"的字段：它是 {@code Σ allocated ≤ available} 那条不变量的一部分（少算它，家户的配额就成了第二个可凭空重复的来源）。
   */
  private static Map<String, Long> laborByUnit(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<String, Long> byUnit = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      // ★★ R3B.2：按 activity（= unit id）归集；activity 不是现存 unit 的配额不进任何 unit 的劳动账。
      byUnit.merge(allocation.activity(), allocation.laborMilli(), Long::sum);
    }
    return byUnit;
  }

  /**
   * 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。
   *
   * <p>★ H4：可见性从 {@code private} 放宽到**包内** —— {@code MarketSettlement} 要问同一个问题（"这一格有哪些家户"），
   * 而它**只能有一个答案**（两处各写一份分组 = 同一个量的第二处拼写点）。
   */
  static Map<String, List<HouseholdId>> rowsByHex(Map<HouseholdId, ClassRow> rows) {
    Map<String, List<HouseholdId>> byHex = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      HexCoord hex = entry.getValue().view().hex();
      // ★ S1：格来自行的**视图**（键本身不再带格；{@link IndustryHexKeys#hexKey} 是拼写点）。
      byHex
          .computeIfAbsent(IndustryHexKeys.hexKey(hex.q(), hex.r()), ignored -> new ArrayList<>())
          .add(entry.getKey());
    }
    LinkedHashMap<String, List<HouseholdId>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<HouseholdId> members = byHex.get(hexKey);
      // ★ 行序 = 阶层（字典序）→ 居住类型（直接读视图；键不再带这两维）。
      //   ★ S3：landless_laborer/artisan/official 也按同一个 value 字典序参与，只用于确定性；不映射回旧四档。
      members.sort(
          Comparator.comparing((HouseholdId id) -> rows.get(id).view().stratum().value())
              .thenComparing(id -> rows.get(id).view().residence()));
      sorted.put(hexKey, members);
    }
    return sorted;
  }

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────
  //
  // ★★ H1：商品库存在**会话工作副本**里（|Map<HouseholdId, Map<CommodityId, Long>>|；裁定 K1），行里没有 goods 了。
  //   三个助手是这份副本的**唯一读写点**（口径只有一处）：
  //     · 缺失键 = 该家户没有该商品（同 ClassRow.goods 原来的口径，见 EconomyDayStepper 的类注）；
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

  // ── 经营者账（会话工作副本）的读写助手（H5）──────────────────────────────────────────
  //
  // ★★ 形制与家户那两组**逐字同款**（同一份 GoodsAccount 的两张余额表；裁定 K15）：
  //   · 外层键缺失 = **这个世界没有这本账**（合法状态：手搭夹具 / 没播种经营者的世界）⇒ 读按 0、写**跳过**
  //     （写进去会凭空造出一本 actor 侧不存在的账 —— 那正是"静默造账"，本仓明文禁止）；
  //   · 内层缺失 = 该商品/币种余额 0；写 ≤ 0 ⇒ 去掉键（保持空表的纯形态）；换值一律 put 一张新表。

  /** 某经营者在某商品上的余额（没有这个键 ⇒ 0）。 */
  private static long operatorStockOf(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods, ActorRef actor, CommodityId commodity) {
    return operatorGoods.getOrDefault(actor, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 把某经营者在某商品上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该键）。 */
  private static void setOperatorStock(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      ActorRef actor,
      CommodityId commodity,
      long amount) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(operatorGoods.getOrDefault(actor, Map.of()));
    if (amount <= 0L) {
      goods.remove(commodity);
    } else {
      goods.put(commodity, amount);
    }
    operatorGoods.put(actor, goods);
  }

  /** 在某经营者的账上**加一笔**（{@code delta} 可为负；走 {@link #setOperatorStock} 的同一口径）。 */
  private static void addOperatorStock(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      ActorRef actor,
      CommodityId commodity,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setOperatorStock(
        operatorGoods, actor, commodity, operatorStockOf(operatorGoods, actor, commodity) + delta);
  }

  /** 某经营者在某币种上的余额（没有这个键 ⇒ 0）。 */
  private static long operatorMoneyOf(
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney, ActorRef actor, CurrencyId currency) {
    return operatorMoney.getOrDefault(actor, Map.of()).getOrDefault(currency, 0L);
  }

  /** 把某经营者在某币种上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该币种键）。 */
  private static void setOperatorMoney(
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      ActorRef actor,
      CurrencyId currency,
      long amount) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>(operatorMoney.getOrDefault(actor, Map.of()));
    if (amount <= 0L) {
      wallet.remove(currency);
    } else {
      wallet.put(currency, amount);
    }
    operatorMoney.put(actor, wallet);
  }

  /** 在某经营者的钱包上**加一笔**（{@code delta} 可为负；走 {@link #setOperatorMoney} 的同一口径）。 */
  private static void addOperatorMoney(
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      ActorRef actor,
      CurrencyId currency,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setOperatorMoney(
        operatorMoney, actor, currency, operatorMoneyOf(operatorMoney, actor, currency) + delta);
  }

  /**
   * ★★ <b>把一笔产出计提（{@code +净产 → 所有者}）记进会话副本</b>（H5）。
   *
   * <pre>
   * 所有者是**家户 actor**（HouseholdActors.of(cohort) 命中）⇒ 记进家户账副本
   * 所有者是**经营者**且副本里有它的账                          ⇒ 记进经营者账副本
   * 两者都不是（没有那本账）                                     ⇒ **不写**（产权条目那条路照旧落 actor，见 applyTransfer）
   * </pre>
   *
   * <p>★★ <b>它修的是哪条旧账</b>（H5 ⑤）：改前产出计提**只**进 {@code ledger.outputAccruals()}，而家户账在 落盘时按**副本绝对值**写回
   * ⇒ 当 {@code operator} 恰好是一个家户 actor（{@code tenant} 档：佃农家户经营）时， 那笔计提**先被 {@code
   * OwnershipBooks.apply} 加上、又被绝对落回抹掉**（H3 如实记下的"静默丢产出"）。 记进副本之后，"副本 =
   * 这一天的终值"这条不变量对**产出**也成立（家户与经营者同一处口径）。
   */
  private static void creditOutput(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ActorRef owner,
      CommodityId commodity,
      long amount) {
    if (amount == 0L) {
      return;
    }
    HouseholdId cohort = householdOfActor.get(owner);
    if (cohort != null) {
      addStock(householdGoods, cohort, commodity, amount);
      return;
    }
    if (operatorGoods.containsKey(owner)) {
      addOperatorStock(operatorGoods, owner, commodity, amount);
    }
  }

  /**
   * ★ <b>"这个世界没有货币"的会话副本</b>（每个家户一本空钱包）—— 只服务**包内**的单模块入口 （{@link #settleOneDay} 的短重载与 {@code
   * EconomyDayStepper} 的短构造器）。
   *
   * <p>★ 它<b>不是</b>"余额为 0 的默认值"：它说的是"世界的货币总量是 0"这个**合法状态** —— 没有货币 ⇒ 没有有效需求（市场买不动）、没有货币工资（可用 0）。★
   * <b>app 协调器不许走它</b>： 真档的货币（创世禀赋）住在 actor 侧，必须由协调器载入（否则钱会在账上静默消失）。
   */
  static LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> emptyMoneyAccountsFor(
      Map<HouseholdId, ClassRow> rows) {
    LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    for (HouseholdId key : rows.keySet()) {
      money.put(key, Map.of());
    }
    return money;
  }

  /**
   * ★★ <b>付方（operator）在本格可见的货币</b>（H4；货币档"本期可用"的来源）：
   *
   * <pre>
   * operator 是一个**家户 actor**（HouseholdActors.of(cohort) 命中）⇒ 该家户的钱包（逐币种）
   * ★ H5：否则若经营者账副本里有它                              ⇒ **它自己的钱包**（真档的庄园/作坊/产业型家户）
   * 其余（这个世界没有那本账）                                    ⇒ **空表 = 每个币种可用 0**
   * </pre>
   *
   * <p>★ <b>为什么"看不见 ⇒ 0"而不是"不封顶"</b>：不封顶等于让 economy 承诺一笔它无权承诺的钱（钱住在 actor 切片上），
   * 而落账时会当场炸在**别人**的代码里；封顶 0 是如实报（读数里 {@code dueAmount > 0 && paidNow == 0} 看得见欠了多少）。 ★★ <b>H5
   * 之后这一支只剩"手搭夹具/未播种的世界"</b>：真档创世给每个经营主体播一本账（{@code HouseholdSeeder}）， 协调器把它载进 {@code
   * operatorMoney} ⇒ 货币工资真的付得出来。
   */
  static Map<CurrencyId, Long> availableMoneyOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      ActorRef operator) {
    HouseholdId cohort = householdOfActor.get(operator);
    if (cohort != null) {
      return householdMoney.getOrDefault(cohort, Map.of());
    }
    return operatorMoney.getOrDefault(operator, Map.of());
  }

  /**
   * ★★ <b>H4 的第一条守卫：货币账必须覆盖每一个"有人口"的家户</b>（与 {@link #requireHouseholdAccounts} 逐字同款的口径与理由）。
   *
   * <p>★★ <b>为什么必须抛而不是"当成 0"</b>：没有账的一家人可花余额被读成 0 ⇒ 它的有效需求是 0 ⇒ 市场买不到粮、
   * 货币工资也付不出去，而账面（缺口、读数）看起来完全正常。★ 0 人口的家户**可以缺席**（它们不消费、不出工）。
   */
  private static void requireHouseholdMoney(
      Map<HouseholdId, ClassRow> rows, Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      if (entry.getValue().population() <= 0L) {
        continue; // 0 人口：不消费、不出工 ⇒ 允许没有钱包
      }
      if (!householdMoney.containsKey(entry.getKey())) {
        missing.add(entry.getKey() + "（人口 " + entry.getValue().population() + "）");
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
      Map<HouseholdId, ClassRow> rows, Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      if (entry.getValue().population() <= 0L) {
        continue; // 0 人口：不吃饭、不出工 ⇒ 允许没有账
      }
      if (!householdGoods.containsKey(entry.getKey())) {
        missing.add(entry.getKey() + "（人口 " + entry.getValue().population() + "）");
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "家户账缺失（H1 fail-closed，裁定 K1）：有 "
            + missing.size()
            + " 个「有人口」的家户在会话工作副本里没有键 —— 不许把'没有账'静默当成'库存 0'"
            + "（那会让这一家人当天静默地不吃饭）。app 协调器必须在推进前从 actor 侧的 GoodsAccount 载入家户账；"
            + "单模块用例请用 EconomyDayStepper 的 householdGoods 参数显式给账。缺失的家户（最多列 8 个）："
            + missing.subList(0, Math.min(8, missing.size())));
  }

  /**
   * 换**当日自然需求**（**逐商品**；{@code 0} 的那一项不落键）。
   *
   * <p>★★ 这是 {@link ClassRow#naturalNeeds()} 的**唯一写入点**（v2 spec §八.8 的"一条真相"）：结算每天把当日需求写进去，
   * 读口（{@code ApiViews.economyHex} / GUI / MCP）直接读它，不再各算一遍 —— 否则人口一变（饿死、将来的任何人口变动）
   * 同一面板上的"人口"与"日耗"就会分叉。
   *
   * <p>★★ **R3 起口径来自 {@link EconomyVocabulary#dailyNeedsMilli}**（每商品一条：粮 + 布）："粮食不足与衣物不足对死亡的时间尺度
   * 显然不能一样"（spec §七）—— 形状先做出来，**阈值与死亡作用留 R4**（故布的缺口本轮只被记下来、不参与饿死判据）。 ★ H1：{@code population == 0}
   * 的行也照写（空表）—— 需求是人口的函数，读口不许留一个陈旧的旧值。
   *
   * <p>★★ <b>M2.7（丙条仪器）：同一步里累加 {@code cycleNaturalNeedMilli}</b> —— 粮的当日需要（同一份 {@code
   * dailyNeedsMilli}，不另立公式）加到行上的本周期累加器；<b>新周期的重置不在这里</b>，而在流水清零点（新周期第一天） 把它置为"当天那一份"（见 {@code
   * settleOneDay} 的流水循环旁注释）—— 那里才能同时看见"今天是不是新周期第一天"。
   */
  private static ClassRow withDailyNeed(ClassRow row, long day) {
    Map<CommodityId, Long> needs = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry :
        EconomyVocabulary.dailyNeedsMilli(row.population(), day).entrySet()) {
      if (entry.getValue() > 0L) {
        needs.put(new CommodityId(entry.getKey()), entry.getValue());
      }
    }
    long dayGrainNeed = needs.getOrDefault(GRAIN, 0L);
    return new ClassRow(
        row.id(),
        row.view(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.money(),
        row.debts(),
        needs,
        row.effectiveDemand(),
        row.cycleNaturalNeedMilli() + dayGrainNeed);
  }

  /** 追加一条债务引用（其余字段原样带过）。 */
  private static ClassRow withExtraDebt(ClassRow row, DebtContractId debtId) {
    if (row.debts().contains(debtId)) {
      return row; // 行里的引用只加一次（E4a 起同一合同跨周期恒同 id，幂等由这里兜底）
    }
    List<DebtContractId> debts = new ArrayList<>(row.debts());
    debts.add(debtId);
    return new ClassRow(
        row.id(),
        row.view(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.money(),
        debts,
        row.naturalNeeds(),
        row.effectiveDemand(),
        row.cycleNaturalNeedMilli());
  }

  /** 换人口与有效劳动（饿死惩罚用；其余字段原样带过）。 */
  private static ClassRow withPopulationAndLabor(ClassRow row, long population, long laborMilli) {
    return new ClassRow(
        row.id(),
        row.view(),
        population,
        laborMilli,
        row.participationPerMille(),
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand(),
        row.cycleNaturalNeedMilli());
  }

  /**
   * ★★ <b>M2.7：把周期累加器重置为"今天这一份"</b>（新周期第一天用）。
   *
   * <p>★ 为什么不是置 0：今天已经吃掉的这一份**属于新周期**（{@code withDailyNeed} 在消费步刚累加过）—— 置 0 会把新周期第一天的需要
   * 抹掉，整周期分母因此少一天。调用点必须用"最近结算日写下的 {@code naturalNeeds[grain]}"作为参数（同源，不另算）。
   */
  private static ClassRow withCycleNaturalNeed(ClassRow row, long cycleNaturalNeedMilli) {
    return new ClassRow(
        row.id(),
        row.view(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand(),
        cycleNaturalNeedMilli);
  }

  // ★★ R3B.2：旧的 withCycleState(Industry…) 已删除 —— 周期状态（progress/cycleLabor/cycleInputUsed）
  //   现在属于 ProductionUnit（见 ProductionUnit#withCycleState），Industry 只留模板、不再每天重建。
}
