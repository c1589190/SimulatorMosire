package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionRecipe;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

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
 *   <li>**缺口**：库存不够 ⇒ 先在同格内借粮（地主 → 富农 → 中农 的顺序，从有粮的行的**余粮**划转 —— 余粮 = 库存 − **本周期自需** × {@link
 *       #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000，见 {@link
 *       #lendableOf}，**不是**"消费后的全部库存"，也不是"当日盈余"）；借到的**累加进同一条** {@link Debt}（同一周期内 同一对债权债务人只有一条，见
 *       {@link #debtIdOf}，本金递增）；**借完仍补不上**的部分记入本行流水的 {@code unmetNeed}（毫粮、逐日累加，供周期末的饿死判据 —— 见
 *       {@link #FAMINE_MORTALITY_PER_MILLE}，默认致命率 0‰）。
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
 *       {@link #chargeInterest}。★ **偿还行为不做**（它要"有粮才还"的判断，属 V7+）。
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

  /** 同格借粮的每周期利率（千分数）：20‰。 */
  public static final int BORROW_RATE_PER_MILLE_PER_CYCLE = 20;

  /**
   * ★★ <b>借粮的信用倍数（千分数；H5 ②）</b>：<b>额度 ≤ 预计下期收入 × 本常量 ÷ 1000</b>。
   *
   * <pre>
   * 信用线_h = 预计下期收入_h × {@link #LOAN_INCOME_MULTIPLE_PER_MILLE} ÷ 1000
   * 可借_h   = max(0, 信用线_h − 本周期已借_h)
   * </pre>
   *
   * <p>★★ <b>它为什么是"收入"的倍数而不是"缺口"的倍数</b>：借粮的归还来源**只能是未来的收入**（H5 的题目："只在未来有收入时借"） ——
   * 用缺口定额度等于让一个永远还不上的人借到债权人破产。出厂值 <b>1000‰ = 一个预计周期的收入</b> （"这一周期最多借到下一周期能挣回来的那么多"）。
   *
   * <p>★ 它是**制度层参数**（"借多少才算稳妥"是判断），与致死率/放贷自留/市场自留同处置：先做**具名常量**， <b>V7 参数目录（spec §四）落地后</b>迁入
   * {@code economy} 切片的参数表、成为 GM 可调（作用域 全局→国家→格/产业）。 ★ 取 {@code 0} = 禁止借粮；取 {@code 2000} =
   * 允许借到两个周期的收入（"宽信用"）。
   */
  public static final int LOAN_INCOME_MULTIPLE_PER_MILLE = 1000;

  /**
   * ★★ <b>偿还比例（千分数；H5 ②）</b>：<b>本周期到手的实物所得里取出这么多来还债</b>。
   *
   * <pre>
   * 还款_h = min( ⌊本周期粮所得_h × 本常量 ÷ 1000⌋ , 该家户**当前粮库存** , 该家户剩余债务本金 )
   * </pre>
   *
   * <p>★★ <b>上限为什么是"当期可用"</b>（H5 的判据原话）：还款是**转移**，不是"凭空多付" —— 余额扣成负会当场炸在唯一 applier 的守卫上（透支 =
   * 发行，而本批没有发行人）。⇒ 上限三路取小，**结构性地**不可能扣成负。
   *
   * <p>★★ <b>为什么按"本周期所得"取比例，而不是"有多少还多少"</b>：一次还清会让债务人把刚收获的粮全部吐出来 （下个周期自己变成缺口户 ⇒ 又借 ⇒
   * 债务曲线变成锯齿）。按比例先偿债是"先还债、后开销"的**制度**，出厂值 <b>200‰ = 所得的 20%</b>。
   *
   * <p>★ 与上面那条同处置（具名常量 ⇒ V7 迁入参数表、GM 可调）。★ 取 {@code 0} = 不偿还（改前的口径）； 取 {@code 1000} = 有收入就全部先还债。
   */
  public static final int DEBT_REPAYMENT_SHARE_PER_MILLE = 200;

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

  /**
   * **借粮优先序**（§四 第 8 步 / 用户口径）：地主 → 富农 → 中农。★ **贫农不在放贷序列**里（v1 明文：它没有余粮可贷）； 只有这三个阶层的行才可能是债权人。
   *
   * <p>★★ **升格为 {@code List<SocialClassId>}`（S1 阶段 1）**：原先是裸词 {@code
   * List.of("landlord","rich","middle")} —— 词表换成 {@code rich_peasant}/{@code middle_peasant}
   * 之后它**不会编译报错**，只在运行时**静默匹配不上** （债权序列退化成"只有地主"）。换成具名常量后，写错阶层**根本编译不过**。
   *
   * <p>★ **诚实边界**：这个回归是被 `EconomySettlementEndToEndTest`（**simos-app**，夹具形态正是"地主借、富农贷"）
   * 当场抓到的，不是"没人发现"；`simos-economy` 自己的用例全绿只是因为它的债务夹具里**只有贫农+地主两行**。 ⇒ 下面那条 {@code
   * eachLenderStratumInThePriorityListCanLend} 补的正是本模块内的判别力（尤其是 {@code middle_peasant}
   * 这一档：换装后全仓**没有任何夹具**用它做债权人）。
   */
  private static final List<SocialClassId> LENDER_SLOT_PRIORITY =
      List.of(SocialClassId.LANDLORD, SocialClassId.RICH_PEASANT, SocialClassId.MIDDLE_PEASANT);

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
    LinkedHashMap<CohortKey, Map<CommodityId, Long>> householdGoods = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : base.classes().entrySet()) {
      if (entry.getValue().population() > 0L) {
        throw new IllegalStateException(
            "本入口（多日静态入口）没有**家户账** —— 家户的商品库存住在 actor 切片的 GoodsAccount 上，"
                + "而日结算的消费与投入都要读它（H1/K1：家户账是会话状态）。"
                + "把'没有账'当成'库存 0'是本仓最反对的形态，故当场抛：家户="
                + entry.getKey()
                + " 人口="
                + entry.getValue().population()
                + "。**请走 EconomyDayStepper（家户账是会话状态）**："
                + "它的构造器收一份 Map<CohortKey, Map<CommodityId, Long>> 工作副本（由 app 协调器从 actor 侧载入），"
                + "step(day) 就地更新它、finish() 之后由协调器落回 actor；"
                + "单模块用例同样走它（step 交回当天的 ProductionLedger）");
      }
      // ★ 0 人口的家户：给一张空账（它们不吃饭、不出工 ⇒ 不需要库存；见 settleOneDay 的同款口径）。
      householdGoods.put(entry.getKey(), Map.of());
    }
    // ★ 本期流水的逐日累加器：从 base 已累计的流水起步，在日循环里逐日并入后统一带出（§十一）。
    LinkedHashMap<CohortKey, FlowRow> flows = new LinkedHashMap<>(base.flows());
    EconomyData data = base;
    for (long day = fromTick + 1L; day <= toTick; day++) {
      ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
      data =
          settleOneDay(
              data,
              day,
              flows,
              householdGoods,
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
    return data.withFlows(flows);
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
      LinkedHashMap<CohortKey, FlowRow> flows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger) {
    return settleOneDay(
        base, day, flows, householdGoods, plantingDrawsFirst, FAMINE_MORTALITY_PER_MILLE, ledger);
  }

  /**
   * 同 {@link #settleOneDay(EconomyData, long, LinkedHashMap, Map, boolean,
   * ProductionLedger.Accumulator)}，但**致死率可注入** （**包内可见**的旋钮，见 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）—— 这条入参是"旋钮"而非"死分支"的全部理由见该常量的注释。
   *
   * @param famineMortalityPerMille 饿死判据的致死率（千分数；**默认 0‰ = 不致命**）
   * @param ledger 当天的 {@link ProductionLedger} 累加器（**就地更新**；由调用方在日循环里逐日新建 ⇒ 它记的是**这一天**）
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<CohortKey, FlowRow> flows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger) {
    // ★★ **单模块入口：世界没有货币**（H4）—— 货币副本 = 每个家户一本**空账**。★ 这不是"忘了给钱"：
    //   它是"这个世界的货币总量是 0"这个合法状态（没有货币 ⇒ 没有有效需求、没有货币工资，见 MarketSettlement）。
    //   ★ app 协调器**不许**走这一支（它必须把 actor 侧的货币余额载进来）—— 故本重载是**包内可见**的。
    return settleOneDay(
        base,
        day,
        flows,
        householdGoods,
        emptyMoneyAccountsFor(base.classes().keySet()),
        plantingDrawsFirst,
        famineMortalityPerMille,
        ledger);
  }

  /**
   * ★★ <b>结算一天（H4 的完整入口）</b>：在商品账的会话工作副本之后，再接管**货币账的会话工作副本**（裁定 K14）。
   *
   * <p>★★ <b>H5：这一支的世界没有经营者账</b>（空表）—— 它与"这个世界没有货币"那一支同款：说的是"经营者一个都没有持账"这个**合法状态**
   * （单模块用例、手搭夹具），不是"忘了载入"。★ 真档由 app 协调器从 actor 侧载入（见 {@code OwnershipBooks}）后走下面那个 H5 入口。
   *
   * @param householdMoney 家户货币账工作副本：键 = 家户身份、值 = 逐币种余额（**缺失键 = 该币种 0**， 但**外层键必须覆盖每一个 {@code
   *     population > 0} 的家户** —— 见 {@link #requireHouseholdMoney}）；**就地更新**
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<CohortKey, FlowRow> flows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger) {
    return settleOneDay(
        base,
        day,
        flows,
        householdGoods,
        householdMoney,
        Map.of(),
        Map.of(),
        plantingDrawsFirst,
        famineMortalityPerMille,
        ledger);
  }

  /**
   * ★★ <b>结算一天（H5 的完整入口）</b>：在家户的两份会话副本之后，再接管**经营者账的两份会话工作副本**（承 H3/H4 的明账）。
   *
   * <p>★★ <b>经营者为什么也要有账</b>（H5）：{@code harvest} 把净产计提给 {@code operator}（{@code ESTATE: farm@0_0} /
   * {@code WORKSHOP: craft@0_0} / {@code HOUSEHOLD: weave@0_0}），而**关系实付与货币工资的付方也是它** —— 没有账时"可用 0 ⇒
   * 实付 0"（H4 如实记的边界），产出计提也会在同一天被别处的绝对落回抹掉。给它一本账之后：
   *
   * <ol>
   *   <li>净产**真的**记在它名下（跨周期可见）；
   *   <li>货币档的付款上限 = **它自己的钱包**（货币工资因此真的付得出来）；
   *   <li>投入供方是它时，料从**它自己的账**上出（H3 那条"聚合主体 ⇒ 由该产业的家户账代理"从此只是**兜底**）。
   * </ol>
   *
   * <p>★★ <b>键 = 主体本身（不是 (主体, 格)）</b>：{@code RegimeOperators} 用**产业 id**当主体的 id，而一个产业只在一格 ⇒
   * 一个经营者只可能有一本账（键里的"格"因此是冗余的）；★ 账户真源的键仍是 {@code (actor, location)}（app 侧拼）。
   *
   * <p>★ <b>缺席 = 这个世界没有那本账</b>（合法，同上面那一支）：读它按"可见 0"、写它**跳过**（产权条目那条路照旧落 actor）。
   *
   * @param operatorGoods 经营者商品账工作副本（键 = 经营者主体；**就地更新**）；没有就给空表
   * @param operatorMoney 经营者货币账工作副本（同上）；没有就给空表
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<CohortKey, FlowRow> flows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(householdGoods, "householdGoods（H1：家户账是会话状态，必须由调用方载入）");
    Objects.requireNonNull(householdMoney, "householdMoney（H4：货币账是会话状态，必须由调用方载入）");
    Objects.requireNonNull(operatorGoods, "operatorGoods（H5：经营者账是会话状态；没有就给空表）");
    Objects.requireNonNull(operatorMoney, "operatorMoney（H5：经营者账是会话状态；没有就给空表）");
    EconomyMeta meta = base.meta().orElseThrow();
    long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    long dueCycle = currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>(base.industries());
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>(base.classes());
    LinkedHashMap<DebtId, Debt> debts = new LinkedHashMap<>(base.debts());
    // ★★ **R2：劳动配额表**——日结算**读**它（当日劳动的唯一来源），R4 起在**饿死**那一步**按存活比例缩**它
    //   （见 {@link #scaleLaborOfIndustry}："人死了劳动没减"这条旧账的收口）⇒ 需要工作副本。
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations =
        new LinkedHashMap<>(base.allocations());
    LinkedHashMap<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>(base.laborSupply());

    // ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）——
    //   放在任何公式之前（与 E14 的"在任何数量计算之前"同款）：副本缺键时若继续跑，缺的那一家会被当成"库存 0"
    //   ⇒ 它当天"吃 0、投入 0"，账面看不出少了谁。那正是本仓最反对的形态，故当场抛。
    requireHouseholdAccounts(rows, householdGoods);
    // ★★ **H4 的第一条守卫：货币账必须覆盖每一个"可能要花钱的家户"**（与商品那条同款、同一条理由）：
    //   缺键时若继续跑，那一家的可花余额会被当成 0 —— 它当天的有效需求因此是 0、市场买不到粮、
    //   而账面（缺口只多不少）看起来完全正常。⇒ 缺键 ⇒ 当场抛（"没有账"与"账是空的"是两件事）。
    requireHouseholdMoney(rows, householdMoney);

    // 逐行当日发生额（流水的事后组装）。★ R3 起两张实物表都是**逐商品**的（{@link FlowRow#income()} 由标量改成 Map）。
    LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, Long> borrowing = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, Map<CommodityId, Long>> income = new LinkedHashMap<>();
    // ★★ **R5/T4：生产损耗不再有"逐行累加器"** —— 损耗不是"谁消费了"，是"蒸发了"，它只进 {@link
    //   ProductionLedger#losses()}（守恒式里自成一项，见类注 §6.1 的新式）。留一个"分了损耗但没人收"的中间残留
    //   （旧口径里它是 consumed 的一部分）会让 I4.2 的 ΣLoss 被算两遍。
    LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, Long> deathsToday = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, Long> birthsToday = new LinkedHashMap<>();
    LinkedHashMap<CohortKey, Long> interestToday = new LinkedHashMap<>(); // 周期末计息那一笔（§7.1③）
    // ★★ **H5：周期末偿还的那一笔**（{@code FlowRow.repaid} 的第一个写入点 —— 改前它恒 0）。
    LinkedHashMap<CohortKey, Long> repaidToday = new LinkedHashMap<>();
    // ★★ **H5：当日的缺口**（"今天这一顿没吃上多少"）—— 它先被记下、**不在吃饭那一步就借**
    //   （借是最后手段：自产/分配 → 市场 → 救济(留位) → 借；见 {@link #lendDeficits}）。
    LinkedHashMap<CohortKey, Long> deficitToday = new LinkedHashMap<>();
    // ── 0. 现扣周期投入（周期的第一天）：**在当天吃饭之前**把种子/原料划走（v2 spec §3.2）──────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    //   ★★ H3（C3）：这一步里"谁出料"由 relation.inputSupplier 说 —— 家户供方取它自己那一本账（缸不够就扣光 ⇒ 规模缩）；
    //     聚合主体（庄园/作坊/产业型家户）的账 economy 看不见 ⇒ 由该产业名下的家户账代理；第一层取不满时同格兜底（粮除外）。
    //     逐条口径见 {@link #drawCycleInputs} 的方法注释。
    // ★★ **H0：家户 → 它供给的产业**（从配额表推，唯一拼写点见 {@link #industriesOfHouseholds}）——
    //   本日的两处都要它：① 各行流水"本期"何时翻篇（家户没有自己的周期，见流水循环的注释）；
    //   ② 借粮的"本周期自需"要一个 cycleDays（见 {@link #cycleDaysByHousehold}）。
    Map<CohortKey, Set<IndustryId>> industriesOfHousehold =
        industriesOfHouseholds(rows, industries, allocations);

    // ★★ **H2：家户 actor 的反查表**（一天建一次）—— "这条转移的某一端是不是家户、是哪一家"这个问题
    //   在三个落点（关系实付 / 同格取材 / 同格借粮）**只能有一个答案**，故它在这里建好、逐处传下去。
    //   ★ 键集一天不变（人口的增减不改行的身份），故建一次就够。
    Map<ActorRef, CohortKey> householdOfActor = householdActorsOf(rows);

    if (plantingDrawsFirst) {
      drawCycleInputs(
          industries,
          rows,
          allocations,
          base.relations(),
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          consumedGoods,
          householdOfActor,
          ledger);
      reallocateLabor(industries, rows, allocations);
    }

    // ── 1~2. 消费（各自吃自己的库存；缺口**先记下不借** —— 借是最后手段，见 4b）──────────────
    //   ★ H4：每条家户行的 cycleDays 提成局部量 —— 它同时喂"放贷余粮"（lendDeficits）与"市场自留"（MarketSettlement），
    //     两处各算一遍就是同一个量的第二处拼写点（算错不会报错，只会让两处口径悄悄漂开）。
    Map<CohortKey, Long> cycleDaysByHousehold =
        cycleDaysByHousehold(rows, industries, industriesOfHousehold);
    consumeOwnStock(rows, householdGoods, consumedGoods, unmetToday, deficitToday, day);

    if (!plantingDrawsFirst) {
      drawCycleInputs(
          industries,
          rows,
          allocations,
          base.relations(),
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          consumedGoods,
          householdOfActor,
          ledger);
      reallocateLabor(industries, rows, allocations);
    }

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 + 饿死惩罚 ────────────────────────────
    boolean anyCycleClosed = false;
    Set<IndustryId> newCycleIndustries = new HashSet<>();
    // ★★ **R2：当日劳动的唯一来源 = 劳动分配表**（第三阶段设计稿 §四）。按 actor id 归集一次（O(配额条数)），
    //   再逐产业取用 —— 产业 id 与 actor id 的对应关系由 EconomyData 的构造期守卫判死
    //   （产业型主体必须指名已存在的产业、非产业型主体不得与产业 id 撞名）。
    Map<String, Long> laborByActor = laborByActor(allocations);
    // ★★ **H0：周期刚翻篇的产业**（progressDays 归 0，含创世）—— 先算好，因为"家户的流水何时翻篇"要读它。
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      if (entry.getValue().progressDays() == 0L) {
        newCycleIndustries.add(entry.getKey());
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
    Map<String, List<IndustryId>> hexToIndustries = industriesByHexMap(industries);
    Set<CohortKey> newCycleHouseholds = new HashSet<>();
    for (CohortKey key : rows.keySet()) {
      Set<IndustryId> supplied = industriesOfHousehold.getOrDefault(key, Set.of());
      if (supplied.isEmpty()) {
        supplied =
            new LinkedHashSet<>(
                industriesAt(hexToIndustries, new HexCoord(key.hex().q(), key.hex().r())));
      }
      for (IndustryId industryId : supplied) {
        if (newCycleIndustries.contains(industryId)) {
          newCycleHouseholds.add(key);
          break;
        }
      }
    }
    // ★★ H5：本日关账的产业（饿死判据与劳动缩放要等救济通道走完 —— 见下面的 4d）。
    List<ClosedIndustry> closed = new ArrayList<>();
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      // ★★ **H0：这个产业的家户行 = 由劳动配额表推**（{@link #householdKeysOf}：格 = 产业 id 的格键、居住类型 =
      //   {@code ResidenceKind.ofLot(group)}、阶层 = 四档全排）。★ 判据不是"行里记着哪个产业"—— 行里没有产业了（K2）。
      List<CohortKey> keys = householdKeysOf(rows, id, allocations);
      // ★★ **当日实际劳动取自该产业名下的全部配额**（不再从"本产业各行 laborMilli × participation"独立算）：
      //   改口径前那两处是同一个数的两种算法（构造性相等、零断言守护）⇒ 同一批人可以被两个产业各算一次满额。
      //   现在配额之和 ≤ 该批次的可用劳动是**状态的不变量**（EconomyData 构造期判），故"劳动不能凭空重复"成立。
      long laborToday = laborByActor.getOrDefault(id.value(), 0L);
      long cycledLabor = industry.cycleLaborMilli() + laborToday;
      long progressed = industry.progressDays() + 1L;
      long nextProgress = progressed;
      long nextCycleLabor = cycledLabor;
      Map<CommodityId, Long> nextInputUsed = industry.cycleInputUsedMilli(); // 非关账日：原样带过
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**（v1 spec §3.1 原文），
      //   cycleDays 的语义是"周期已满、待收获"。用 >= 才能把该合法状态收获掉；
      //   用 == 会让 progressDays == cycleDays 的下一日构造出 cycleDays + 1，在 Industry 构造期抛，
      //   异常穿出协调器的 simulateWorld ⇒ **整条推进 revision 失败**。
      if (progressed >= industry.cycleDays()) {
        // ── 周期末：产出 → 产权条目 + 关系规则入账（★ 饿死判据**挪到所有救济通道之后**，见下面的 4d）──
        harvest(
            industry,
            rows,
            cycledLabor,
            income,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            base.relations(),
            householdOfActor,
            ledger);
        // ★★ **H5：关账的产业先记下来，饿死判据等救济通道走完再算**（改前它挂在收获那一支里、早于市场与借粮）——
        //   理由：饿死是"**所有**救济都用尽了"之后的判据（自产/分配 → 市场 → 救济(留位) → 借）；把它排在借粮**之前**
        //   会让"当天借到粮的人"照样按缺口去死（改前的次序里借粮恰好在它之前 ⇒ 那个口径必须保住）。
        closed.add(new ClosedIndustry(id, keys, industry.cycleDays()));
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextInputUsed = Map.of(); // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的投入瓶颈凭空变大）
        anyCycleClosed = true;
      }
      industries.put(id, withCycleState(industry, nextProgress, nextCycleLabor, nextInputUsed));
    }

    // ── 4. 同格市场清算（H4；每周期一次，在收获与分配之后）────────────────────────────────
    //   ★★ 为什么挂在"今天有产业关账"这个**日级**事实上（与计息同款）：周期的定义住在产业上（cycleDays），
    //     而市场是"这一格这个周期的一次集市" ⇒ 一天之内几个产业同时关账也只开一次市。
    //   ★ 它读 base.markets()（价格是数据）：缺格的格没有市场 ⇒ 这一支整块跳过（不造默认价）。
    //   ★★ 买卖**只走唯一的 applier**（applyTransfer）：本步绝不直接改副本 ——
    //     "任何库存变动必有对应转移记录"这条不变量的落点因此仍是一处（见 MarketSettlement 的类注）。
    //   ★★ **H5 ①：需求口径 = 本周期剩余需求**（= 本周期累计缺口 − 现在手上的库存，下限 0）——
    //     改前读的是"当日缺口" ⇒ 关账日只补一天的口粮，其余日子照旧饿。逐条算式见 MarketSettlement 的类注。
    //   ★★ **H5 ②：市场排在借粮之前**（自产/分配 → 市场 → 救济(留位) → 借）—— 见下面的 {@link #lendDeficits}。
    if (anyCycleClosed) {
      MarketSettlement.clearOncePerCycle(
          base.markets(),
          rows,
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          unmetToday,
          flows,
          cycleDaysByHousehold,
          householdOfActor,
          ledger);
    }

    // ── 4b. 借粮（★ H5：**最后手段** —— 自产/分配 → 市场 → 救济(留位) → 借）──────────────────
    //   ★★ 它为什么必须晚于市场：关账日集市之后**手上真的还有粮**的家户不该再借（"只在未来有收入时借"）——
    //     改前借粮与消费挤在同一步（并行），市场买到的粮既不能顶当天的饭、也不能减少债务。
    //   ★ 非关账日没有集市 ⇒ 这一步就紧接着"吃饭"那一支（与改前的次序逐值相同）。
    //   ★ 救济是**留位**：本批没有任何救济制度（谁救济、救济多少都是判断 ⇒ 属 GM/参数目录），
    //     故这一步的次序是具名的四档，而第③档今天空着（如实记，不假装）。
    if (!deficitToday.isEmpty()) {
      lendDeficits(
          rows,
          debts,
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          consumedGoods,
          borrowing,
          unmetToday,
          deficitToday,
          currentCycle,
          dueCycle,
          cycleDaysByHousehold,
          // ★ 信用线在**这一步之前**算好（它读的是"到此刻为止"的本期所得 —— 关账日的收获与分配已经在上面发生了）。
          creditLinesOf(rows, income, cycleDaysByHousehold),
          householdOfActor,
          ledger);
    }

    // ── 4c. 偿还（★ H5：本期所得按比例先偿债）───────────────────────────────────────────
    //   ★★ 它挂在"今天有产业关账"上（利息／市场的同一处日级事实）：所得是**整周期**结算出来的
    //     （收获与分配在本步之前刚发生）⇒ 还债的时点就是所得到手的那一刻。
    //   ★ 上限 = 该主体**当期可用**（余额扣不成负 —— 与唯一 applier 的守卫同一条口径，见 {@link #repayDebts}）。
    if (anyCycleClosed) {
      repayDebts(
          rows,
          debts,
          householdGoods,
          householdMoney,
          operatorGoods,
          operatorMoney,
          income,
          repaidToday,
          householdOfActor,
          ledger);
    }

    // ── 4d. 饿死判据（★ H5：**所有救济通道之后** —— 市场（4）→ 借（4b）→ 还（4c）之后才判）────────────
    //   ★★ 口径与改前**逐值相同**：改前它排在借粮之后（只是那时借粮在收获之前）；本步把它排到市场与借粮之后 ⇒
    //     "借到粮的人不按缺口去死"这条语义一个字没变，而"关账日的集市买到的粮也算救济"这条**新**口径进来了。
    //   ★ 人口减少后劳动按同比例缩（`applyFamine` 缩**行**劳动 + 本步缩该产业名下的**全部配额**与对应批次的供给）。
    for (ClosedIndustry closing : closed) {
      long populationBefore = 0L;
      for (CohortKey key : closing.keys()) {
        populationBefore += rows.get(key).population();
      }
      for (CohortKey key : closing.keys()) {
        long carried =
            newCycleIndustries.contains(closing.id()) || flows.get(key) == null
                ? 0L
                : flows.get(key).unmetNeed().getOrDefault(GRAIN, 0L);
        applyFamine(
            rows,
            deathsToday,
            key,
            rows.get(key),
            carried + unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L),
            day,
            closing.cycleDays(),
            famineMortalityPerMille);
      }
      long populationAfter = 0L;
      for (CohortKey key : closing.keys()) {
        populationAfter += rows.get(key).population();
      }
      // ★★ **R4：饿死之后劳动按同比例缩 —— 而且这次真的缩到配额上**（R2 如实记下的那条旧账：
      //   `applyFamine` 缩的是**行**劳动，而当日劳动自 R2 起取自**劳动分配表** ⇒ "人死了劳动没减"）。
      if (populationAfter < populationBefore) {
        scaleLaborOfIndustry(
            closing.id(), populationBefore, populationAfter, allocations, laborSupply);
      }
    }

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    //   ★ 次序：**偿还先于计息**（还掉的那部分本金不再生息 —— 这是"先还后计"的标准序，也是改后债务曲线下降的一半原因）。
    if (anyCycleClosed) {
      chargeInterest(debts, interestToday);
    }

    // ── 流水：每行一条（本期发生额；税 v1 恒 0、利息见上一步）────────────────────────────
    for (CohortKey key : rows.keySet()) {
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
              (acc == null ? 0L : acc.births()) + dayBirths));
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
    // ★ T2：生产关系表**原样带过**（本任务行为不变：{@code harvest} 还没读它 —— 那时 T4 的事）。
    return new EconomyData(
        Optional.of(nextMeta),
        industries,
        rows,
        debts,
        flows,
        laborSupply,
        allocations,
        base.relations(),
        // ★ H4：市场表**原样带过**（价格是数据、不是结算产物 —— 结算只读它，见 MarketSettlement）。
        base.markets());
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
    LinkedHashMap<CohortKey, ClassRow> rows = new LinkedHashMap<>(base.classes());
    LinkedHashMap<CohortKey, FlowRow> flows = new LinkedHashMap<>(base.flows());
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations =
        new LinkedHashMap<>(base.allocations());
    LinkedHashMap<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>(base.laborSupply());
    // 批次 → 它供给的产业（保序、去重；只认**真的落在某个产业上**的 actor，见 EconomyData 的构造期守卫）。
    Map<PeopleLotId, List<IndustryId>> industriesOf = new LinkedHashMap<>();
    for (LaborAllocation allocation : base.allocations().values()) {
      IndustryId industryId = new IndustryId(allocation.actor().id());
      if (!base.industries().containsKey(industryId)) {
        continue;
      }
      List<IndustryId> list =
          industriesOf.computeIfAbsent(allocation.group(), k -> new ArrayList<>());
      if (!list.contains(industryId)) {
        list.add(industryId);
      }
    }
    for (LotChange change : changes) {
      if (change.isEmpty()) {
        continue;
      }
      List<IndustryId> targets = industriesOf.getOrDefault(change.group(), List.of());
      if (targets.isEmpty()) {
        // ★★ **兜底：摊到"它住的那一格"的产业的家户行上**（见 {@link LotChange} 的类注）—— 没有劳动配额的批次
        //   （0-14 岁那一档：劳动系数 0 ⇒ 创世不发配额）照样要吃饭、照样会死；不摊它，那一格的家户行就会
        //   "人少了、饭照吃"，而两侧的人口账当场对不上（实测：真档一年差 23,452 人，全部是未成年那一档）。
        targets = industriesAt(base, change.at());
        if (targets.isEmpty()) {
          continue; // 该格本来就没有任何经济状态（世界还没播种到这里）⇒ 没有可摊的行
        }
      }
      // ★★ **H0：这批人的家户行 = （它供给的那些产业的格）× 它自己的居住类型 × 四个阶层**，**并集去重** ——
      //   农村批次同时供给农业与家庭纺织（两者落在**同一批农村家户行**上）⇒ 不去重就会把它的人与生死**算两遍**。
      List<CohortKey> keys = householdKeysOfLot(rows, targets, ResidenceKind.ofLot(change.group()));
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
        CohortKey key = keys.get(j);
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
          laborSupply);
    }
    return new EconomyData(
        base.meta(),
        base.industries(),
        rows,
        base.debts(),
        flows,
        laborSupply,
        allocations,
        base.relations(), // ★ T2：生产关系表原样带过（人口变动不动关系）
        base.markets()); // ★ H4：市场表同理（人口变动不动价格）
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
  private static FlowRow withLifecycle(FlowRow flow, CohortKey key, long births, long deaths) {
    FlowRow base =
        flow == null
            ? new FlowRow(key, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, 0L)
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
        base.births() + births);
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
  private static void scaleLaborOfIndustry(
      IndustryId industryId,
      long before,
      long after,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<PeopleLotId, LaborSupply> laborSupply) {
    if (before <= 0L || after >= before) {
      return;
    }
    for (LaborAllocationId allocationId : new ArrayList<>(allocations.keySet())) {
      LaborAllocation allocation = allocations.get(allocationId);
      if (!allocation.actor().id().equals(industryId.value())) {
        continue; // 只缩"喂这个产业的"那些配额（别的产业的劳动没死）
      }
      long scaled = allocation.laborMilli() * after / before;
      allocations.put(
          allocationId,
          new LaborAllocation(
              allocation.id(),
              allocation.group(),
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
      LinkedHashMap<PeopleLotId, LaborSupply> laborSupply) {
    if (before <= 0L || after >= before) {
      return;
    }
    for (LaborAllocationId allocationId : new ArrayList<>(allocations.keySet())) {
      LaborAllocation allocation = allocations.get(allocationId);
      if (!allocation.group().equals(group)) {
        continue;
      }
      long scaled = allocation.laborMilli() * after / before;
      allocations.put(
          allocationId,
          new LaborAllocation(
              allocation.id(),
              allocation.group(),
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
   *       {@code (actor, location)}），而会话工作副本的键是 {@link CohortKey}，装不下它（铁律 3：economy 不认识 actor 切片）。⇒
   *       由 <b>该产业名下的家户账代理它</b>（"这个主体的缸" = 它名下那些家户的缸，与"谁供给这个产业"同一条唯一事实 {@link #householdKeysOf}）。★
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
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<CohortKey, ClassRow> rows,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<IndustryId, ProductionRelation> relations,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry.progressDays() != 0L) {
        continue; // 只有周期的第一天扣投入
      }
      Map<CommodityId, Long> perScale = industry.inputPerUnit();
      if (perScale.isEmpty()) {
        continue; // ★ 空表与"缺键"同义：不扣、不缩规模 ⇒ 未配投入的产业行为与 V2 一字不差
      }
      // ★★ H3：**谁出料由 relation 说**。缺 relation（合法状态：关系表里可以没有这个产业）⇒ 退回缺省 = operator
      //   （与 ProductionRelation 的构造期缺省**同值**、同一条道理 —— 本层不发明第二份缺省）。
      ProductionRelation relation = relations.get(id);
      Recipient supplier =
          relation == null ? new Recipient.ToActor(industry.operator()) : relation.inputSupplier();
      List<CohortKey> ownKeys =
          supplierAccountsOf(supplier, industry, rows, allocations, householdOfActor);
      // ★★ **H5：供方（经营者）自己的账** —— 它在会话副本里时**先**从它自己的缸里取（"经营者自己持账"），
      //   家户账那一路退成**代理**（H3 的既有口径原样保留：手搭夹具、或还没给经营者播种的世界照旧走代理）。
      //   ★ 为什么这层必须排在代理之前：作坊的**工具**是它自己产出的（净产 → operator）—— 若仍从城镇家户的缸里取，
      //     工具会在经营者账上越积越多、而作坊的家户缸越用越空 ⇒ **第 2 个周期起停工**（正是 H5 ④ 要消灭的外生断点）。
      ActorRef supplierActor = supplierActorOf(supplier);
      boolean supplierHoldsOwnAccount =
          supplierActor != null && operatorGoods.containsKey(supplierActor);
      // ① 可供量（逐商品）= 主体**自己的账** + 代理的那些家户账（见方法注释：不跨主体取料）。
      // ★★ **第三层兜底的准入条件：前两层（主体自己的账 + 它名下家户账）对**这一种料**一点都没有。**
      //   ★ 为什么必须这样收口（H5 ④ 的实测）：把兜底并进"可供量"之后，织机的可供纤维从 18,600,000（它名下那批人的缸）
      //     涨到 20,700,000（+ 城里作坊的 2,100,000）⇒ 它的规模从 620 台涨到 690 台 ⇒ 取材时把作坊的纤维**取光**
      //     ⇒ 作坊规模 0、工具产量 0 —— 那正是 H3 明文记过的那个病（`EconomyRealScaleClothTest` 实测
      //     {@code heldByOperator(CRAFT, TOOL)} 掉到 0）。⇒ 兜底只在"这种料根本不在这个主体手里"时才进场
      //     （作坊的纤维在后一种情形：它的缸里一根没有），而"手里有、只是不够"的产业**照旧**以自己的账为限。
      Map<CommodityId, Long> available = new LinkedHashMap<>();
      for (CommodityId commodity : perScale.keySet()) {
        long sum =
            supplierHoldsOwnAccount ? operatorStockOf(operatorGoods, supplierActor, commodity) : 0L;
        for (CohortKey key : ownKeys) {
          sum += stockOf(householdGoods, key, commodity);
        }
        available.put(commodity, sum);
      }
      // ② "真的用得上"的规模（= 改前 rowUsageScale 的产业版）：产能那一路 × 每种投入一路，取小。
      long usableScale = capacityScaleOf(industry);
      for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue; // 每单位需求为 0 ⇒ 这一路不构成约束（同 scaleOf 的口径）
        }
        usableScale =
            Math.min(usableScale, available.getOrDefault(entry.getKey(), 0L) / entry.getValue());
      }
      if (usableScale <= 0L) {
        continue; // 一点料都筹不到（或本格没有产能）⇒ 不扣、规模自然 0 —— "地荒着"
      }
      Map<CommodityId, Long> drawnTotal = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
        CommodityId commodity = entry.getKey();
        long perUnit = entry.getValue();
        if (perUnit <= 0L) {
          continue;
        }
        long remaining = usableScale * perUnit; // 毫单位（★ 单位规模 × 毫单位/单位规模）—— 整个产业本周期要用的料
        // ③ 第一层（零）：**主体自己的账**（经营者）—— 有多少取多少，取到需求满足为止（H5）。
        if (supplierHoldsOwnAccount) {
          long drawn =
              Math.min(operatorStockOf(operatorGoods, supplierActor, commodity), remaining);
          if (drawn > 0L) {
            recordOperatorInputDraw(
                supplierActor, industry, commodity, drawn, operatorGoods, ledger);
            remaining -= drawn;
            drawnTotal.merge(commodity, drawn, Long::sum);
          }
        }
        // ③ 第一层（一）：按人口占比 + **最大余数法**分派（Σ分派 == remaining，不再逐户丢余数），逐户上限 = 它的余额。
        for (Map.Entry<CohortKey, Long> ask :
            apportionToHouseholds(ownKeys, rows, remaining).entrySet()) {
          long drawn = Math.min(stockOf(householdGoods, ask.getKey(), commodity), ask.getValue());
          if (drawn <= 0L) {
            continue; // 缸空/不够 ⇒ 这一户出不起它那一份（下面的补齐可能从**同层的别人**那里补上）
          }
          recordInputDraw(
              ask.getKey(),
              industry,
              commodity,
              drawn,
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
        // ③ 第一层（二）：缺口由**同层还有货的账**补齐（持仓量降序、取多少 = 缺多少）。
        //   ★★ 为什么补：relation 指名的主体是**一个**（这里是该产业名下的家户账这个整体）—— 它内部某一户缸空
        //     不该让整个产业减产（"谁出料"已经由 relation 说了，这是"指名的主体内部怎么摊"）。
        //   ★ "佃农穷 ⇒ 地荒"那条机构由**单一主体**那一档表达（tenant 的 operator 就是佃农家户：它就是那一本账，
        //     没有"同层"可补 ⇒ 缸空即规模 0）—— 那一条不因本补齐而消失。
        if (remaining > 0L) {
          for (CohortKey key : byStockDescending(ownKeys, householdGoods, commodity)) {
            if (remaining <= 0L) {
              break;
            }
            long drawn = Math.min(stockOf(householdGoods, key, commodity), remaining);
            if (drawn <= 0L) {
              continue;
            }
            recordInputDraw(
                key,
                industry,
                commodity,
                drawn,
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
        }
      }
      if (!drawnTotal.isEmpty()) {
        // ★★ 必须写回 industries 工作副本：收获（同一次日结算里、稍后跑）读的就是这一份累加器。
        Map<CommodityId, Long> accumulated = new LinkedHashMap<>(industry.cycleInputUsedMilli());
        for (Map.Entry<CommodityId, Long> entry : drawnTotal.entrySet()) {
          accumulated.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        industries.put(
            id,
            withCycleState(
                industry, industry.progressDays(), industry.cycleLaborMilli(), accumulated));
      }
    }
  }

  /**
   * ★★ <b>关系指名的投入供方 → 它那一本（或那一批）账</b>（H3；两处边界见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <pre>
   * ToCohort(c)                  ⇒ {c}（**必须存在**、且必须住在该产业那一格 —— 见下）
   * ToActor(a) 且 a 是本世界现存的家户 actor ⇒ {cohortOf(a)}（唯一拼写点 HouseholdActors）
   * ToActor(a) 其余（聚合主体：庄园/作坊/产业型家户）⇒ **该产业名下的家户账**（householdKeysOf，唯一事实）
   * </pre>
   *
   * <p>★★ <b>两条 fail-closed（与规则受方那条同款，理由同源）</b>：
   *
   * <ul>
   *   <li>命名的 cohort **必须有行**："这个家户不存在"只可能是配置错 —— 静默当成"缸里没有"会让账面上完全看不出问题 （本仓最反对的"静默付 0"形态）；
   *   <li>命名的账**必须住在该产业那一格**：一条转移只有一个 {@code location}（账户 = {@code (actor, location)}），
   *       跨格的供方说不清料从哪本账出。
   * </ul>
   *
   * <p>★ <b>聚合主体为何不抛</b>：它不是配置错 —— 真档三个产业的经营者（{@code ESTATE:farm@x_y} / {@code WORKSHOP:craft@x_y}
   * / {@code HOUSEHOLD:weave@x_y}）全是这一形态（载荷边缘按 regime 推出来的），而它们的账 economy 根本看不见 ⇒ 由该产业的家户账代理（见
   * {@link #drawCycleInputs} ②）。
   */
  private static List<CohortKey> supplierAccountsOf(
      Recipient supplier,
      Industry industry,
      Map<CohortKey, ClassRow> rows,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<ActorRef, CohortKey> householdOfActor) {
    if (supplier instanceof Recipient.ToCohort toCohort) {
      CohortKey key = toCohort.cohort();
      if (!rows.containsKey(key)) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账，"
                + "把'配置错'静默当成'缸里没有'会让账面上看不出问题）：activity="
                + industry.id()
                + " 供方="
                + key
                + " 本格现有的家户="
                + rows.keySet().stream()
                    .filter(row -> row.hex().equals(hexOfIndustry(industry.id())))
                    .toList());
      }
      requireSupplierHex(key, industry);
      return List.of(key);
    }
    ActorRef actor = ((Recipient.ToActor) supplier).actor();
    CohortKey named = householdOfActor.get(actor);
    if (named != null) {
      requireSupplierHex(named, industry);
      return List.of(named);
    }
    // ★ 聚合主体：它的账在 actor 切片上（economy 看不见）⇒ 由**该产业名下的家户账**代理（见方法注释）。
    return householdKeysOf(rows, industry.id(), allocations);
  }

  /** 命名的供方必须住在该产业那一格（一条转移只有一个 {@code location}；见 {@link #supplierAccountsOf}）。 */
  private static void requireSupplierHex(CohortKey supplier, Industry industry) {
    HexCoord location = hexOfIndustry(industry.id());
    if (!supplier.hex().equals(location)) {
      throw new IllegalStateException(
          "关系指名的投入供方不住在这个产业那一格（H3 fail-closed：账户 = (actor, location)，"
              + "跨格的供方说不清料从哪本账出）：activity="
              + industry.id()
              + " 供方="
              + supplier
              + " 供方的格="
              + supplier.hex()
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
  private static Map<CohortKey, Long> apportionToHouseholds(
      List<CohortKey> keys, Map<CohortKey, ClassRow> rows, long total) {
    Map<CohortKey, Long> apportioned = new LinkedHashMap<>();
    if (keys.isEmpty() || total <= 0L) {
      return apportioned;
    }
    long population = 0L;
    for (CohortKey key : keys) {
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
  private static List<CohortKey> byStockDescending(
      List<CohortKey> keys,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      CommodityId commodity) {
    List<CohortKey> sorted = new ArrayList<>(keys);
    sorted.sort(
        Comparator.comparingLong((CohortKey key) -> stockOf(householdGoods, key, commodity))
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
      CohortKey supplierKey,
      Industry industry,
      CommodityId commodity,
      long drawn,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    CohortKey operatorKey = householdOfActor.get(industry.operator());
    // ★★ **T4：投入同时记进当天的 ledger**（I4.2 的 `ΣProductionInputs` 的唯一来源）——
    //   它与"这东西从谁的账上出"是两件事：前者是**读数**，后者是**转移凭据**（H2 的口径）。
    ledger.addInput(industry.id(), commodity, drawn);
    if (operatorKey != null && !operatorKey.equals(supplierKey)) {
      // ① 两个主体都在家户账里 ⇒ 走转移（唯一 applier 落副本），随后由经营者消费掉。
      Transfer requisition =
          ledger.mint(
              HouseholdActors.of(supplierKey),
              industry.operator(),
              supplierKey.hex(),
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
      Industry industry,
      CommodityId commodity,
      long drawn,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      ProductionLedger.Accumulator ledger) {
    ledger.addInput(industry.id(), commodity, drawn);
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
   */
  private static long capacityScaleOf(Industry industry) {
    // ★ capacityPerUnit 非空且逐值 > 0（构造期守卫）⇒ 循环至少跑一次、scale 必然被赋一个有限值。
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      scale =
          Math.min(scale, industry.capacity().getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * ★★ <b>"供给这个产业的家户行"的唯一算法</b>（H0；裁定 K2 + R-N1-A）：
   *
   * <pre>
   * ① 产业所在格 = IndustryHexKeys.hexKeyOf(industryId)      （唯一拼写点；拿不到格键 ⇒ 空表，不猜 (0,0)）
   * ② 居住类型   = ResidenceKind.ofLot(allocation.group())   （批次前缀的唯一拼写点在 ResidenceKind）
   * ③ 家户行     = (该格, 该居住类型) × SocialClassId.all() 四行，**只取真的存在的那些**
   * </pre>
   *
   * <p>★★ <b>它取代了改前的 {@code classKeysOf(rows, industryId)}</b>（按行键里的产业段过滤）：H0 之后**行里没有产业了** （键 = 格
   * + 居住类型 + 阶层）—— 一个家户给两个产业出劳动（农村家户既种地又织布）时它**只有一行**，而"哪些行属于这个产业"
   * 只能由**劳动配额表**回答：出劳动的那批人住哪儿，就是它的家户。
   *
   * <p>★ <b>行不存在 ⇒ 跳过</b>（不是坏数据）：逐组件增量落盘 ⇒ 配额先到、行后到是合法写序（同 {@code EconomyData} 的
   * "表与表之间没有引用完整性约束"）。★ <b>一条配额都没有的产业 ⇒ 空表</b>（没有家户 ⇒ 没有劳动者；收获的产出全留 operator）。
   *
   * <p>★ 序 = 阶层（{@code SocialClassId.all()} 的保序）× 居住类型（{@code ResidenceKind.all()}）—— <b>不是</b>插入序：
   * 它是"这一格这一居住类型的四行"的纯函数（可复现，与配额表的条数无关）。
   */
  static List<CohortKey> householdKeysOf(
      Map<CohortKey, ClassRow> rows,
      IndustryId industry,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Optional<HexCoord> hex = IndustryHexKeys.hexKeyOf(industry).map(HexCoord::parse);
    if (hex.isEmpty()) {
      return List.of(); // 产业 id 里没有格键 ⇒ 说不出它在哪一格（不许拿 (0,0) 顶替）
    }
    Set<ResidenceKind> residences = new LinkedHashSet<>();
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.actor().id().equals(industry.value())) {
        residences.add(ResidenceKind.ofLot(allocation.group()));
      }
    }
    return householdKeysAt(rows, hex.get(), residences);
  }

  /** ★★ 由"格 + 居住类型集合"摊出四行 cohort 键（只留真的存在的行）—— 见 {@link #householdKeysOf}。 */
  private static List<CohortKey> householdKeysAt(
      Map<CohortKey, ClassRow> rows, HexCoord hex, Set<ResidenceKind> residences) {
    List<CohortKey> keys = new ArrayList<>();
    for (SocialClassId stratum : SocialClassId.all()) {
      for (ResidenceKind residence : ResidenceKind.all()) {
        if (!residences.contains(residence)) {
          continue;
        }
        CohortKey key = new CohortKey(hex, residence, stratum);
        if (rows.containsKey(key)) {
          keys.add(key);
        }
      }
    }
    return keys;
  }

  /**
   * ★★ <b>一个批次的出生/死亡该摊到哪些家户行</b>（{@link #applyPopulationChange} 用）：它供给的那些产业的格 × 它自己的居住类型 ×
   * 四个阶层，**并集去重**。
   *
   * <p>★★ <b>去重不是优化，是正确性</b>：真档里农村批次同时供给 {@code farm@hex} 与 {@code weave@hex}，而两者落在**同一批农村家户行**
   * 上（H0 之后行不含产业段）—— 不去重会把这批人的出生/死亡**算两遍**（人口账当场对不上）。
   */
  private static List<CohortKey> householdKeysOfLot(
      Map<CohortKey, ClassRow> rows, List<IndustryId> industries, ResidenceKind residence) {
    LinkedHashSet<CohortKey> keys = new LinkedHashSet<>();
    for (IndustryId id : industries) {
      Optional<HexCoord> hex = IndustryHexKeys.hexKeyOf(id).map(HexCoord::parse);
      if (hex.isPresent()) {
        keys.addAll(householdKeysAt(rows, hex.get(), Set.of(residence)));
      }
    }
    return new ArrayList<>(keys);
  }

  /**
   * ★★ <b>家户 → 它供给的产业集合</b>（从配额表推 —— 与 {@link #householdKeysOf} 是**同一条事实的两个方向**）。
   *
   * <p>用途有两条：① 借粮的"本周期自需"要一个 {@code cycleDays}（见 {@link #cycleDaysByHousehold}）； ② 各行流水的"本期"何时翻篇（见
   * {@code settleOneDay} 的流水循环）。
   *
   * <p>★ <b>只认真的落在某个产业上的 actor</b>（同 {@link #applyPopulationChange} 的口径）：家户型 actor 的 id 可以只是消费主体
   * （{@code EconomyData} 的构造期守卫允许），那时它不进任何产业。
   */
  private static Map<CohortKey, Set<IndustryId>> industriesOfHouseholds(
      Map<CohortKey, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<CohortKey, Set<IndustryId>> byHousehold = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      IndustryId industryId = new IndustryId(allocation.actor().id());
      if (!industries.containsKey(industryId)) {
        continue;
      }
      Optional<HexCoord> hex = IndustryHexKeys.hexKeyOf(industryId).map(HexCoord::parse);
      if (hex.isEmpty()) {
        continue;
      }
      ResidenceKind residence = ResidenceKind.ofLot(allocation.group());
      for (SocialClassId stratum : SocialClassId.all()) {
        CohortKey key = new CohortKey(hex.get(), residence, stratum);
        if (rows.containsKey(key)) {
          byHousehold.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(industryId);
        }
      }
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
   */
  private static Map<CohortKey, Long> cycleDaysByHousehold(
      Map<CohortKey, ClassRow> rows,
      Map<IndustryId, Industry> industries,
      Map<CohortKey, Set<IndustryId>> industriesOfHousehold) {
    Map<CohortKey, Long> byHousehold = new LinkedHashMap<>();
    for (CohortKey key : rows.keySet()) {
      Set<IndustryId> supplied = industriesOfHousehold.getOrDefault(key, Set.of());
      long cycleDays = 0L;
      for (IndustryId id : supplied) {
        cycleDays = Math.max(cycleDays, industries.get(id).cycleDays());
      }
      if (cycleDays == 0L) {
        for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
          if (IndustryHexKeys.hexKeyOf(entry.getKey())
              .filter(IndustryHexKeys.hexKey(key.hex().q(), key.hex().r())::equals)
              .isPresent()) {
            cycleDays = Math.max(cycleDays, entry.getValue().cycleDays());
          }
        }
      }
      byHousehold.put(key, cycleDays);
    }
    return byHousehold;
  }

  // ── 消费 + 同格借粮 ─────────────────────────────────────────────────────────────────

  /**
   * 每个格一次：先各自吃自己的库存，库存不够的**在同格内借**（地主 → 富农 → 中农 的**余粮** —— 见 {@link #lendableOf}）；借到的**累加进同一条**
   * {@link Debt}（§7.2 的聚合）；**仍补不上的** 记入未满足需求（{@code unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表， **不进放贷行的
   * {@code consumed}** —— 它出去的是"债权"不是"消费"）。故 §6.1 的守恒式在**格/全局**上成立、**逐行不成立**。
   *
   * <p>★★ <b>H2：那一笔"放贷行扣库存、却不进它的 consumed"现在有一条转移记录相伴</b>（{@code from=放贷家户, to=借款家户}，原因 {@link
   * TransferReason#LOAN_PRINCIPAL}）—— 改前那条**刻意的不对称**在账上无从对账（库存少了，
   * 既不是消费、也不是转移），正是"任何库存变动必有对应转移记录"这条不变量要钉的东西。★ 借入方当天吃掉那一笔走 {@link #consumeFromHousehold}（转移 +
   * 消费两条痕，余额净 0 —— 与改前逐值相同）。
   *
   * <p>★★ **债务按 (周期, 债务人, 债权人, 商品) 聚合**（v2 spec §7.2）：同一对债权债务人在**同一周期内**的多次借入 **累加到同一条** {@link
   * Debt}（本金递增），**新周期开新条** ⇒ 条数上界从 {@code O(天数 × 格子)} 降到 {@code O(周期数 × 格子 × 债权人对数)}。id 由 {@link
   * #debtIdOf} 确定性算出（重放/分支可比）。
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
   * @param currentCycle 正在进行的**周期序号**（{@code lastClosedCycle + 1}）：进 {@code DebtId} ⇒ 跨周期必然不同、
   *     同周期必然相同
   * @param dueCycle 借粮的到期周期 = {@code currentCycle + 1}（只写进**新条**；老条原样带过）
   */
  /**
   * ★ <b>本日关账的一个产业</b>（H5）：它的 id、它名下的家户行、它的周期天数 —— 饿死判据（{@link #applyFamine}） 与死亡后的劳动缩放（{@link
   * #scaleLaborOfIndustry}）要等**市场与借粮**走完才跑，故先把这三样收起来。
   */
  private record ClosedIndustry(IndustryId id, List<CohortKey> keys, long cycleDays) {}

  /**
   * ★★ <b>各自吃自己的库存</b>（H5 把改前的 {@code settleHexes} 拆成两步的第一半）：逐家户扣 {@code min(库存,
   * 当天需求)}，并把**没吃上的那一口**记进 {@code deficitToday}（粮）与 {@code unmetNeed}（粮/布）。
   *
   * <p>★★ <b>为什么"吃"与"借"必须分开</b>（H5 ②）：改前它们挤在同一步里 —— 家户吃完自己那一口就**当场借**，
   * 于是市场买到的粮既不能顶当天的饭、也不能减少债务（"借粮是并行的"）。拆开之后，次序变成 <b>自产/分配 → 市场 → 救济(留位) →
   * 借</b>：缺口先记下，等关账日的集市开完、手上**真的还有粮**时才决定借不借（见 {@link #lendDeficits}）。
   *
   * <p>★★ **当日需求的唯一算法 + 唯一落点**（V5；v2 spec §八.6/§八.8）：
   *
   * <pre>
   * need = EconomyVocabulary.dailyRationMilli(row.population(), day)   // 逐日差分，残差不丢
   * row.naturalNeeds = { grain: need, cloth: ... }                     // ★ 结算写、读口读（同源）
   * eaten = min(家户账余额, need)；差额进 deficitToday（粮）
   * </pre>
   *
   * <p>★ <b>布不参与同格借粮</b>（借贷制度在本仓只对**粮**有规则）：布的缺口直接记进 {@code unmetNeed}。
   *
   * <p>★★ <b>H1：库存在会话工作副本里</b>（裁定 K1）；{@code population == 0} 的家户**跳过消费**（它们不吃饭、不穿衣 —— 需求本来也是
   * 0，这里显式挡一次免得读一个不存在的账）。
   *
   * @param deficitToday 出参：当日的粮缺口（逐家户；**尚未借** —— 借是最后手段，见 {@link #lendDeficits}）
   */
  private static void consumeOwnStock(
      LinkedHashMap<CohortKey, ClassRow> rows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<CohortKey, Long> deficitToday,
      long day) {
    for (CohortKey key : new ArrayList<>(rows.keySet())) {
      ClassRow row = withDailyNeed(rows.get(key), day);
      rows.put(key, row);
      if (row.population() <= 0L) {
        continue;
      }
      long need = row.naturalNeeds().getOrDefault(GRAIN, 0L);
      long stock = stockOf(householdGoods, key, GRAIN);
      long eaten = Math.min(stock, need);
      setStock(householdGoods, key, GRAIN, stock - eaten);
      // ★ **必须 merge 不能 put**：这张累加器现在与现扣投入步共享（后者先跑时它已经记了种子/原料那一笔），
      //   `put` 会把投入从当日消费里抹掉 ⇒ §6.1 的守恒式当场不成立（"投入要看得见"）。
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
        // ★★ **H5：当日的粮缺口先记进 {@code unmetNeed}**（改前是在借粮那一步"记残差"）——
        //   它现在必须**早于**救济通道可见：① 饿死判据要在救济走完之后读它（缺口 + 借到的会被冲减）；
        //   ② 关账日集市的需求窗口读的正是"本周期累计缺口"（{@code MarketSettlement} 的 demand）。
        //   ★ 后面的每一笔救济（集市买到的、再吃一口自家新到手的、借到的）都经 {@link #trimUnmet} **冲减**它
        //     ⇒ 终值仍 = 改前那个"借完还剩多少"的残差（逐值同口径，不是两笔记两遍）。
        addGoods(unmetNeed, key, GRAIN, need - eaten);
        deficitToday.put(key, need - eaten);
      }
    }
  }

  /**
   * ★★ <b>同格借粮：最后手段</b>（H5 ②；改前的 {@code settleHexes} 的第二半）—— 逐格：缺口行向**本格余粮** （地主 → 富农 → 中农，见 {@link
   * #lendableOf}）借，借到的**累加进同一条** {@link Debt}（§7.2 的聚合）； **仍补不上的** 记入未满足需求（{@code
   * unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★★ <b>四档次序（判据就是它）</b>：<b>① 自产/分配</b>（{@link #consumeOwnStock} 吃自有库存，收获与分配在它之前） → <b>②
   * 市场购买</b>（{@link MarketSettlement}，在关账日、本步之前）→ <b>③ 救济</b>（★ <b>留位</b>：本批没有任何救济制度， 谁救济、救济多少都是判断
   * ⇒ 属 GM 参数目录，今天这一档空着）→ <b>④ 借</b>（本方法）。
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
   * <p>★★ <b>债务按 (周期, 债务人, 债权人, 商品) 聚合</b>（v2 spec §7.2）：同一对债权债务人在**同一周期内**的多次借入 **累加到同一条** {@link
   * Debt}（本金递增），**新周期开新条** ⇒ 条数上界从 {@code O(天数 × 格子)} 降到 {@code O(周期数 × 格子 × 债权人对数)}。id 由 {@link
   * #debtIdOf} 确定性算出（重放/分支可比）。
   *
   * <p>★★ <b>H5 的额度（三路取小，GM 可调）</b>：
   *
   * <pre>
   * 额度_h = min( 剩余缺口_h ,
   *              放贷方余粮（逐债权人，见 {@link #lendableOf}）,
   *              ★ 信用线：预计下期收入_h × {@link #LOAN_INCOME_MULTIPLE_PER_MILLE} ÷ 1000 − 本周期已借_h )
   * 预计下期收入_h = max( 本周期已实现的实物所得_h , 该行**下一周期**的口粮_h )   // 见 {@link #expectedIncomeOf}
   * </pre>
   *
   * <p>★ <b>为什么要有信用线</b>：借粮是"未来有收入"时才成立的事（H5 的题目）—— 没有它，缺口行可以无限借 （一个永远还不上的人借到债权人破产）。★
   * <b>为什么"预计收入"取那个 max</b>：家户的收入在本模型里**只能**来自 ① 已经发生的关系实付（{@code flows.income}）②
   * 它的劳动（下一周期还能再挣回自己的口粮 → 用"下一周期口粮"作**下界**估计）。 两者取大 ⇒ 一个刚播种第一个周期、还没有任何所得的家户照样有一条**有限的**信用线（不是
   * 0，否则第一周期谁都借不到， "青黄不接时借粮"这条制度当场消失）。
   *
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（放贷行**本周期自需**的输入）
   * @param creditLines 每家的**信用线**（H5 的额度第三路；见 {@link #creditLinesOf}）—— 由调用方一次算好
   * @param currentCycle 正在进行的**周期序号**（{@code lastClosedCycle + 1}）：进 {@code DebtId} ⇒ 跨周期必然不同、
   *     同周期必然相同
   * @param dueCycle 借粮的到期周期 = {@code currentCycle + 1}（只写进**新条**；老条原样带过）
   */
  private static void lendDeficits(
      LinkedHashMap<CohortKey, ClassRow> rows,
      LinkedHashMap<DebtId, Debt> debts,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<CohortKey, Long> borrowing,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<CohortKey, Long> deficitToday,
      long currentCycle,
      long dueCycle,
      Map<CohortKey, Long> cycleDaysByHousehold,
      Map<CohortKey, Long> creditLines,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionSettlement.TransferMint mint) {
    Map<String, List<CohortKey>> hexToRows = rowsByHex(rows.keySet());
    for (Map.Entry<String, List<CohortKey>> hex : hexToRows.entrySet()) {
      List<CohortKey> keys = hex.getValue();
      LinkedHashMap<CohortKey, Long> deficit = new LinkedHashMap<>();
      for (CohortKey key : keys) {
        if (deficitToday.containsKey(key)) {
          deficit.put(key, deficitToday.get(key));
        }
      }
      if (deficit.isEmpty()) {
        continue;
      }
      // ② 放贷序列：地主 → 富农 → 中农；同档按行序（阶层 → 居住类型）定序。
      //   ★★ **可取的不是"全部库存"、也不是"当日盈余"**（V1 的注释与代码相反，V6 一并修）：可取的是
      //     **余粮 = 余额 − 本周期自需 × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000**（{@link
      // #lendableOf}）。
      //   ★ 付款路径**不按人口过滤**（H1 明文）：人口为 0 的家户照样可以是债权人（它有存粮、它借得出）。
      List<CohortKey> lenders = new ArrayList<>();
      for (SocialClassId slot : LENDER_SLOT_PRIORITY) {
        for (CohortKey key : keys) {
          if (key.stratum().equals(slot) && grainOf(householdGoods, key) > 0L) {
            lenders.add(key);
          }
        }
      }
      // ③ 逐缺口行（阶层 id 序 → 居住类型）借：借到多少累加多少债；没人有**余粮** ⇒ 剩下的只留作未满足的自然需求。
      List<CohortKey> debtors = new ArrayList<>(deficit.keySet());
      debtors.sort(
          Comparator.comparing((CohortKey k) -> k.stratum().value())
              .thenComparing(CohortKey::residence));
      for (CohortKey debtor : debtors) {
        long remaining = deficit.get(debtor);
        // ★★ **先再吃一口**（H5）：① 之后拿到手的粮（关账日的收获/分配、集市上买到的）先顶当天的饭 —— 不动信用。
        long jar = grainOf(householdGoods, debtor);
        long secondMeal = Math.min(jar, remaining);
        if (secondMeal > 0L) {
          consumeFromHousehold(householdGoods, consumedGoods, debtor, GRAIN, secondMeal);
          trimUnmet(unmetNeed, debtor, GRAIN, secondMeal);
          remaining -= secondMeal;
        }
        // ★★ **信用线**（H5）：本周期已借多少、还能再借多少 —— 见方法注释的三路取小。
        long creditLeft =
            Math.max(0L, creditLines.getOrDefault(debtor, 0L) - borrowing.getOrDefault(debtor, 0L));
        remaining = Math.min(remaining, creditLeft);
        for (CohortKey lender : lenders) {
          if (remaining <= 0L) {
            break;
          }
          long available =
              lendableOf(rows.get(lender), grainOf(householdGoods, lender), cycleDaysByHousehold);
          if (available <= 0L) {
            continue; // 只剩口粮/已经没有余粮 ⇒ 不贷（V1 是在这里把全部库存贷出去）
          }
          long lent = Math.min(remaining, available);
          // ★★ **H2：借粮 = 一条转移（债权人 → 债务人），再由唯一的 applier 落到副本上**。
          //   改前只写"放贷行扣库存"这一腿 —— 那条**刻意的不对称**正是"任何库存变动必有对应转移记录"
          //   这条不变量要钉的东西（借入方当天就吃掉，账面上只有消费与债务两条痕，看不出粮从谁那里来）。
          Transfer loan =
              mint.mint(
                  HouseholdActors.of(lender),
                  HouseholdActors.of(debtor),
                  debtor.hex(),
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
          // ★★ 聚合（§7.2）：id 是 (周期, 债务人, 债权人, 商品) 的**纯函数** ⇒ 同周期内重复借入命中同一条，
          //   本金递增；跨周期 id 必然不同 ⇒ 新条、旧条留着（保住"哪一周期借的"）。
          DebtId debtId = debtIdOf(currentCycle, debtor, lender, Optional.of(GRAIN));
          Debt standing = debts.get(debtId);
          if (standing == null) {
            debts.put(
                debtId,
                new Debt(
                    debtId,
                    debtor,
                    lender,
                    Optional.of(GRAIN),
                    lent,
                    BORROW_RATE_PER_MILLE_PER_CYCLE,
                    dueCycle,
                    false));
            rows.put(debtor, withExtraDebt(rows.get(debtor), debtId)); // 行里的引用也只加一次
          } else {
            debts.put(debtId, withPrincipal(standing, standing.principal() + lent));
          }
          // ★ 借到的粮当日吃掉 ⇒ 已在上面（转移之后）计入当日消费 —— 那里是**唯一**写这一笔的地方。
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 没人有**余粮**（或信用线用尽）：不造粮、不造债。
        // ★★ **H5：这里不再写 unmetNeed** —— 缺口在吃饭那一步（{@link #consumeOwnStock}）已经整笔记进去了，
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
   */
  private static long lendableOf(
      ClassRow lender, long stock, Map<CohortKey, Long> cycleDaysByHousehold) {
    if (stock <= 0L) {
      return 0L;
    }
    long cycleDays = cycleDaysByHousehold.getOrDefault(lender.key(), 0L);
    long reserve =
        EconomyVocabulary.cumulativeRationMilli(lender.population(), cycleDays)
            * LENDER_SUBSISTENCE_RESERVE_PER_MILLE
            / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /**
   * ★★ <b>各家的信用线（H5 ②）</b>：{@code 预计下期收入 × }{@link #LOAN_INCOME_MULTIPLE_PER_MILLE}{@code ÷ 1000}。
   *
   * <pre>
   * 预计下期收入_h = max( 本周期已实现的实物所得_h(粮) , 该行**下一周期**的口粮_h )
   * 下一周期口粮_h = EconomyVocabulary.cumulativeRationMilli(人口_h, cycleDays_h)      // 与放贷保留额同一个函数
   * </pre>
   *
   * <p>★★ <b>为什么是这两项的 max</b>（逐条理由）：
   *
   * <ol>
   *   <li><b>已实现的所得</b>是**已经发生**的事实（关账日的收获与分配就记在 {@code income} 累加器里）—— 一个账上真的收过 粮的家户，借得起更多；
   *   <li><b>下一周期口粮</b>是**下界估计**：家户在本模型里只要有劳动（或哪怕只是有人口）就至少能靠"给养/自留"再挣回自己那一口 ——
   *       拿它当预计收入的**地板**，于是"青黄不接时借粮"这条制度在第一周期（谁都还没有所得）照样成立， 而"永远还不上的人"拿不到一张无限的信用卡（额度 = 一个周期的口粮 ⇒
   *       借到头也就一个周期）。
   * </ol>
   *
   * <p>★ <b>非粮所得不计入</b>（布/纤维/工具）：债务在本仓是**粮**口径（{@code Debt.commodity} 至今恒为粮）， 折算其它商品需要**价格** ⇒
   * 硬折就是编造一个本轮没有的换算率（同 {@code FlowRow.netSurplus} 的立场）。
   */
  private static Map<CohortKey, Long> creditLinesOf(
      Map<CohortKey, ClassRow> rows,
      Map<CohortKey, Map<CommodityId, Long>> income,
      Map<CohortKey, Long> cycleDaysByHousehold) {
    Map<CohortKey, Long> lines = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : rows.entrySet()) {
      long realized = income.getOrDefault(entry.getKey(), Map.of()).getOrDefault(GRAIN, 0L);
      long nextRation =
          EconomyVocabulary.cumulativeRationMilli(
              entry.getValue().population(), cycleDaysByHousehold.getOrDefault(entry.getKey(), 0L));
      long expected = Math.max(realized, nextRation);
      lines.put(entry.getKey(), expected * LOAN_INCOME_MULTIPLE_PER_MILLE / 1000L);
    }
    return lines;
  }

  /**
   * ★ 把某个"逐行 × 逐商品"缺口累加器里的一笔**冲减**掉（封顶 = 已记的量，绝不改成负数、也不凭空抵消历史缺口）。
   *
   * <p>★ 用途（H5）：家户在"吃饭"那一步记下的当日缺口，可能当天又被补上 —— ② 关账日集市上买到的粮、 ③
   * 借粮前"再吃一口"自家新到手的粮（收获/分配）。两处都只冲减**记过的那一笔**。
   */
  private static void trimUnmet(
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmet,
      CohortKey key,
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
   * ★★ <b>周期末偿还（H5 ②；{@code FlowRow.repaid} 的第一个写入点）</b>。
   *
   * <pre>
   * 逐债务人（行序）：
   *   budget = min( ⌊本日到手的粮所得 × {@link #DEBT_REPAYMENT_SHARE_PER_MILLE} ÷ 1000⌋ , 该家户**当前粮库存** )
   *   逐条债（该行 {@code debts} 的引用序）：还 = min(budget, 本金)；铸 LOAN_REPAYMENT 转移（债务人 → 债权人）；
   *                                  本金 −还；{@code repaid} += 还；budget −还
   * </pre>
   *
   * <p>★★ <b>为什么还款是"一条转移"而不是"把本金改小"</b>：粮**真的从债务人的缸里进了债权人的缸** ——
   * 这正是"任何库存变动必有对应转移记录"那条不变量要钉的东西（改前那笔只有债务表里的一行数字在动，账上无从对账）。 {@code reason = }{@link
   * TransferReason#LOAN_REPAYMENT}（★ H5 给这一档的**第一个写者**）。
   *
   * <p>★★ <b>{@code Transfer.settles} 仍恒为 {@code Optional.empty()}</b>：借粮/偿还产生的是 {@link DebtId}，而
   * {@code settles} 的类型是 {@code ClaimId} —— 硬塞会编造一条本批不存在的 claim 体系（裁定见 {@code Transfer} 的类注）。
   *
   * <p>★★ <b>本金还清 ⇒ 那条 {@link Debt} 留在表里、本金为 0</b>：它是一条**已结清**的历史事实（"哪一周期借的、
   * 跟谁借的、还了多少"都还在），删掉它等于把发生过的事从账上抹去。★ 计息读本金 ⇒ 0 本金的条不再生息（{@link #chargeInterest} 自己挡掉）。
   *
   * <p>★ <b>上限三路取小</b>见 {@link #DEBT_REPAYMENT_SHARE_PER_MILLE}：所得按比例 → 库存（当期可用，扣不成负）→ 剩余本金。★
   * 库存那一路上限取自**循环开始前**的余额，而每一笔偿还都会经唯一 applier 扣掉它 ⇒ 当轮之和结构性 ≤ 余额。
   *
   * @param income 本日到手的实物所得（关账日的收获与分配 —— 也就是本期的所得）
   * @param repaid 本日偿还的逐行累加器（**就地更新** ⇒ 进 {@code FlowRow.repaid}）
   */
  private static void repayDebts(
      LinkedHashMap<CohortKey, ClassRow> rows,
      LinkedHashMap<DebtId, Debt> debts,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> income,
      LinkedHashMap<CohortKey, Long> repaid,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionSettlement.TransferMint mint) {
    for (CohortKey debtor : new ArrayList<>(rows.keySet())) {
      ClassRow row = rows.get(debtor);
      if (row.debts().isEmpty()) {
        continue;
      }
      long earned = income.getOrDefault(debtor, Map.of()).getOrDefault(GRAIN, 0L);
      long budget =
          Math.min(
              earned * DEBT_REPAYMENT_SHARE_PER_MILLE / 1000L, grainOf(householdGoods, debtor));
      if (budget <= 0L) {
        continue;
      }
      for (DebtId debtId : row.debts()) {
        if (budget <= 0L) {
          break;
        }
        Debt debt = debts.get(debtId);
        if (debt == null || debt.principal() <= 0L) {
          continue;
        }
        long paid = Math.min(budget, debt.principal());
        if (paid <= 0L) {
          continue;
        }
        Transfer repayment =
            mint.mint(
                HouseholdActors.of(debtor),
                HouseholdActors.of(debt.creditor()),
                debtor.hex(),
                Map.of(GRAIN, paid),
                TransferReason.LOAN_REPAYMENT);
        applyTransfer(
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            householdOfActor,
            repayment);
        debts.put(debtId, withPrincipal(debt, debt.principal() - paid));
        repaid.merge(debtor, paid, Long::sum);
        budget -= paid;
      }
    }
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
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<CohortKey, ClassRow> rows,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations) {
    Map<String, List<IndustryId>> hexToIndustries = industriesByHexMap(industries);
    for (Map.Entry<String, List<IndustryId>> hex : hexToIndustries.entrySet()) {
      List<IndustryId> ids = hex.getValue();
      boolean cycleStart = false;
      for (IndustryId id : ids) {
        if (industries.get(id).progressDays() == 0L) {
          cycleStart = true;
          break;
        }
      }
      if (!cycleStart) {
        continue; // 周期中途：这一周期的劳动口径已经开跑，不重排（见方法注释）
      }
      // ① 每产业"这一周期真的用得上的劳动"（缺口的来源）
      //   ★ 先数一遍本格各产业**现在**占着多少配额 —— 它是"劳动那一路不施加约束"时的返回值（见 laborNeedOf）。
      Map<IndustryId, Long> allocated = new LinkedHashMap<>();
      for (LaborAllocation allocation : allocations.values()) {
        IndustryId industryId = new IndustryId(allocation.actor().id());
        if (needContains(ids, industryId)) {
          allocated.merge(industryId, allocation.laborMilli(), Long::sum);
        }
      }
      Map<IndustryId, Long> need = new LinkedHashMap<>();
      for (IndustryId id : ids) {
        need.put(id, laborNeedOf(industries.get(id), allocated.getOrDefault(id, 0L)));
      }
      // ② 保留 + 回池（逐 (批次, 产业) 配额）
      Map<IndustryId, Long> kept = new LinkedHashMap<>();
      Map<PeopleLotId, Long> pool = new LinkedHashMap<>();
      for (LaborAllocationId allocId : new ArrayList<>(allocations.keySet())) {
        LaborAllocation allocation = allocations.get(allocId);
        IndustryId industryId = new IndustryId(allocation.actor().id());
        if (!need.containsKey(industryId)) {
          continue; // 不是本格的配额（另一格的产业）
        }
        long room = Math.max(0L, need.get(industryId) - kept.getOrDefault(industryId, 0L));
        long keep = Math.min(allocation.laborMilli(), room);
        kept.merge(industryId, keep, Long::sum);
        long released = allocation.laborMilli() - keep;
        if (keep <= 0L) {
          allocations.remove(allocId); // 整条回池：这个产业这一周期一点也用不上
        } else if (released > 0L) {
          allocations.put(allocId, withLaborMilli(allocation, keep));
        }
        if (released > 0L) {
          pool.merge(allocation.group(), released, Long::sum);
        }
      }
      if (pool.isEmpty()) {
        continue; // 每个产业都吃下了自己的配额 ⇒ 没有回池的劳动（真档多数格、多数周期是这一支）
      }
      // ③ 缺口大的先得（并列按产业 id 序；★ 信号 = need − 已保留）
      List<IndustryId> byGap = new ArrayList<>(ids);
      byGap.sort(
          Comparator.comparingLong(
                  (IndustryId id) -> -Math.max(0L, need.get(id) - kept.getOrDefault(id, 0L)))
              .thenComparing(IndustryId::value));
      // ④ 最后雇主：本格**产粮**的那个产业（"农业/普通劳动"那一档；不在产能上封顶）
      IndustryId lastResort = null;
      for (IndustryId id : ids) {
        if (industries.get(id).recipe().outputPerUnit().containsKey(GRAIN)) {
          lastResort = id;
          break;
        }
      }
      for (Map.Entry<PeopleLotId, Long> entry : pool.entrySet()) {
        PeopleLotId group = entry.getKey();
        long left = entry.getValue();
        for (IndustryId id : byGap) {
          if (left <= 0L) {
            break;
          }
          long gap = Math.max(0L, need.get(id) - kept.getOrDefault(id, 0L));
          long give = Math.min(left, gap);
          if (give <= 0L) {
            continue;
          }
          addLabor(allocations, industries, id, group, give);
          kept.merge(id, give, Long::sum);
          left -= give;
        }
        if (left > 0L && lastResort != null) {
          addLabor(allocations, industries, lastResort, group, left);
          kept.merge(lastResort, left, Long::sum);
          left = 0L;
        }
        // ⑤ left > 0 ⇒ 失业：不生成配额（那批劳动不再被任何产业占着）—— 这就是本步要的"不再卡死"。
      }
    }
  }

  /**
   * ★ <b>一个产业"这一周期真的用得上的劳动"</b>（千分劳动·日）= {@code min(产能那一路, 投入那几路) × laborPerUnit}。
   *
   * <pre>
   * usableScale = min( ⌊capacity[k] ÷ capacityPerUnit[k]⌋ …, ⌊cycleInputUsedMilli[j] ÷ inputPerUnit[j]⌋ … )
   * need        = usableScale × 配方.laborPerUnit
   * </pre>
   *
   * <p>★ 与收获日的 {@link #scaleOf} 是**同一个算式的两个前缀**：这里**刻意不含劳动那一路**（劳动正是本步要求解的量， 把它算进去会得到"需要多少劳动 =
   * 由现有劳动决定"这个循环）。
   *
   * <p>★★ <b>两条"不施加约束"的路（返回 {@code allocated} = 它现在占着多少就认多少）</b>——与 {@link #scaleOf}
   * 的既有口径**逐字同源**（"每一路都可以不施加：该路的每单位需求为 {@code 0}，或该路的表里没有这一项"）：
   *
   * <ol>
   *   <li>{@code laborPerUnit <= 0}：这个产业没有配"每单位规模要多少劳动" ⇒ 劳动不是它的瓶颈 ⇒ 它要多少给多少；
   *   <li>产能表为空（{@code capacityScaleOf} 给 {@code Long.MAX_VALUE}）：没有产能约束 ⇒ 同上。
   * </ol>
   *
   * <p>★ <b>反过来，产能 = 0 是"用不上劳动"</b>（不是"不施加"）：0 座作坊/0 亩地 ⇒ {@code scale == 0} ⇒ 需要 0 劳动 ⇒
   * 配额**全部回池**（这正是 H5 ③ 要的"作坊停工 ⇒ 那批劳动不再卡死"）。★ 数据缺项与数据为零是两件事： 前者不施加约束（读 {@code scaleOf}
   * 的同款口径），后者是一个**真的为 0** 的规模。
   */
  private static long laborNeedOf(Industry industry, long allocated) {
    long laborPerUnit = industry.recipe().laborPerUnit();
    if (laborPerUnit <= 0L) {
      return allocated; // ★ 劳动那一路**不施加约束**（与 scaleOf 的同款口径："每单位需求为 0 ⇒ 这一路不构成约束"）
    }
    long scale = capacityScaleOf(industry);
    if (scale == Long.MAX_VALUE) {
      return allocated; // 产能表为空 ⇒ 产能那一路同样不施加约束
    }
    for (Map.Entry<CommodityId, Long> entry : industry.recipe().inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long drawn = industry.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / entry.getValue());
    }
    if (scale <= 0L) {
      return 0L; // 产能 0 或一点料都没有 ⇒ 这一周期一点劳动也用不上（回池）
    }
    long need = scale * laborPerUnit;
    return need < 0L ? Long.MAX_VALUE : need; // 溢出兜底（手搭夹具可能给天文数字的产能）
  }

  /** 某个产业 id 在不在这批 id 里（保序表上的小工具；只为把"本格的配额"筛出来）。 */
  private static boolean needContains(List<IndustryId> ids, IndustryId id) {
    for (IndustryId candidate : ids) {
      if (candidate.equals(id)) {
        return true;
      }
    }
    return false;
  }

  /** 给某 (批次, 产业) 加劳动：已有配额 ⇒ 累加；没有 ⇒ **新发一条**（id 由 {@link LaborAllocation#idOf} 给出，唯一拼写点）。 */
  private static void addLabor(
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      Map<IndustryId, Industry> industries,
      IndustryId industryId,
      PeopleLotId group,
      long amount) {
    if (amount <= 0L) {
      return;
    }
    LaborAllocationId id = LaborAllocation.idOf(industryId, group);
    LaborAllocation existing = allocations.get(id);
    if (existing != null) {
      allocations.put(id, withLaborMilli(existing, existing.laborMilli() + amount));
      return;
    }
    // ★ 新配额的口径逐条照创世那一份（EconomySeeder.appendAllocation）：actor = 该产业的 operator、period 取**该批次自己的**
    //   供给期（EconomyData 的构造期守卫要求"配额必须有同期供给记录"）；activity 沿用该产业在别处的既有拼写（拿不到 ⇒ 用产业 id）。
    LaborAllocation template = templateAllocationOf(allocations, industryId);
    String activity = template == null ? industryId.value() : template.activity();
    long period = template == null ? 1L : template.period();
    allocations.put(
        id,
        new LaborAllocation(
            id, group, industries.get(industryId).operator(), activity, amount, period));
  }

  /**
   * 同一产业在配额表里的**既有条目**（用来抄 {@code activity} / {@code period} 两个字段）。
   *
   * <p>★ 为什么抄而不是自己造：这两个字段的值是**调用方给的词**（{@code EconomySeeder} 写 {@code farm}/{@code weave}/{@code
   * craft}， period 来自它发的供给记录）—— economy 侧再拍一个就是同一事实的第二处拼写点。★ 一个产业的配额必然有既有条目 （否则它这一格根本没有劳动来源 ⇒
   * 也不会有本步），拿不到时退回产业 id（不抛：那是合法的手搭状态）。
   */
  private static LaborAllocation templateAllocationOf(
      Map<LaborAllocationId, LaborAllocation> allocations, IndustryId industryId) {
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.actor().id().equals(industryId.value())) {
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
        allocation.actor(),
        allocation.activity(),
        laborMilli,
        allocation.period());
  }

  /**
   * ★★ **债务 id 的唯一拼写点**（v2 spec §7.2）：{@code (周期, 债务人, 债权人, 商品)} ⇒ {@code DebtId}，三条硬要求逐条满足：
   *
   * <ol>
   *   <li>**确定性**：本方法是纯函数（无计数器、无时间、无迭代序）⇒ 同一元组**必然**给出同一个 id（重放/分支可比）；
   *   <li>**不含 {@code "."}**：{@link CohortKey#toString()} 用 {@code "|"} 分隔三段（坐标段是 {@code
   *       q_r}），本方法另用 {@code "-"}/{@code ">"} 分隔各段 ⇒ 拼出来**没有点**。★ 这不是审美问题：{@code AddressParser} 把
   *       {@code debt.<id>} 读成 {@code Entity(kind="debt", name=<id>)} 时**在第一个 "." 处切**，id
   *       里带点会把名字截断成另一个名字， 该债务的地址从此**解析不到**（{@link io.mosire.simos.economy.resolve.EconomyResolver}
   *       只会返回空候选）；
   *   <li>**跨周期不同**：周期号是首段 ⇒ 新周期的借入**不会覆盖**旧条（"哪一周期借的"这条信息因此保住， 供 V7+ 的到期/偿还读）。
   * </ol>
   *
   * <pre>
   * debt-c&lt;周期&gt;-&lt;债务人家户键&gt;&gt;&lt;债权人家户键&gt;-&lt;商品&gt;
   * 例（H0 起键带居住维）：debt-c1-0_0|rural|poor_peasant>0_0|rural|landlord-grain
   * </pre>
   *
   * <p>★ **货币债（{@code commodity} 空；v1 不产生）**用同位置的哨兵段 {@code money}。
   */
  static DebtId debtIdOf(
      long cycle, CohortKey debtor, CohortKey creditor, Optional<CommodityId> commodity) {
    return new DebtId(
        "debt-c"
            + cycle
            + "-"
            + debtor
            + ">"
            + creditor
            + "-"
            + commodity.map(CommodityId::value).orElse("money"));
  }

  /** 换本金（其余字段原样带过）——聚合（本金递增）与计息（并入本金）共用。 */
  private static Debt withPrincipal(Debt debt, long principal) {
    return new Debt(
        debt.id(),
        debt.debtor(),
        debt.creditor(),
        debt.commodity(),
        principal,
        debt.ratePerMillePerCycle(),
        debt.dueCycle(),
        debt.defaulted());
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
      Industry industry,
      LinkedHashMap<CohortKey, ClassRow> rows,
      long cycledLabor,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> income,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<IndustryId, ProductionRelation> relations,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    ProductionRecipe recipe = industry.recipe();
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    // ★★ **K3：产能那一路读 {@code industry.capacity()}**（不再 Σ各行的 meansOfProduction —— 行里没有它了）。
    long scale = scaleOf(industry, avgLaborMilli); // ★ 最紧约束

    HexCoord location = hexOfIndustry(industry.id());
    ActorRef operator = industry.operator();
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
    ProductionRelation relation = relations.get(industry.id());
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
                industry.cycleInputUsedMilli(),
                laborOfCohort(rows, location, industry.cycleDays()),
                industry.outputPerUnit(),
                // ★★ H4/H5：货币档的付款上限 = **付方（operator）在本格可见的货币余额**（逐币种）：
                //   家户 actor ⇒ 它的家户钱包；★ H5 起聚合主体（ESTATE / WORKSHOP / 产业型 HOUSEHOLD，id 形如
                //   `farm@0_0`）⇒ **它自己的经营者钱包**（创世播的那一本，由协调器载入 operatorMoney）。
                //   ★ 这个世界没有那本账（手搭夹具）⇒ 可用 0（实付 0、欠额进读数 —— 如实报，不是静默付 0）。
                availableMoneyOf(
                    householdMoney, operatorMoney, householdOfActor, industry.operator())),
            ledger);
    // ★★ **H2：实付一律是转移**（每条 from=operator、to=受方）—— 铸的时候已经进了当天的账，这里只需
    //   ① 把两端落到会话副本上（唯一 applier）② 把"收方是家户"的那一笔记进流水（实物入账读数）。
    //   ★ app 落盘时按副本的**绝对值**写回，不许把这条 +paid 再叠加一次（叠加 = 同一笔粮记两遍；
    //     见 EconomyDayStepper#step 的类注）。
    for (Transfer transfer : outcome.transfers()) {
      applyTransfer(
          householdGoods, householdMoney, operatorGoods, operatorMoney, householdOfActor, transfer);
      CohortKey cohort = householdOfActor.get(transfer.to());
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
   *   <li>{@code householdOfActor} 命中 ⇒ <b>家户账</b>（键 = {@code CohortKey}）；
   *   <li>否则若 `operatorGoods` / `operatorMoney` 里有这个主体 ⇒ <b>经营者账</b>（键 = {@link ActorRef}）—— 这就是
   *       H3/H4 那条"聚合主体 ⇒ 可用 0 ⇒ 实付 0"的收口：经营者自己持账之后，实付真的从它账上出；
   *   <li>两者都没有（手搭夹具、或这个世界还没给经营者播种）⇒ **跳过这一端**：它的账住在 actor 切片上， 由 app 协调器按 {@code
   *       ledger.transfers()} 落账（H2 的既有口径，逐字不改）。★ 本类**只**写自己那两份会话副本。
   * </ol>
   *
   * <p>★ <b>减到负数 ⇒ 当场抛</b>（fail-closed，不静默）：转移是"把已有的东西换手"，扣不动说明上游算错了 （"凭空造"与"凭空吞"都属本仓明文反对的形态）。★
   * <b>货币腿也走这里</b>（H4）：扣成负数时唯一的合法解释是"付方在发行" ⇒ 去问 {@link
   * MoneyIssuance#requireIssuerOf(CurrencyId)}，而本批**没有任何发行人** ⇒ 恒抛。 ⇒ 逐币种守恒的守卫与商品守恒的守卫**落在同一处**。
   */
  static void applyTransfer(
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, CohortKey> householdOfActor,
      Transfer transfer) {
    CohortKey from = householdOfActor.get(transfer.from());
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
        setStock(householdGoods, from, leg.getKey(), stock - leg.getValue());
      }
      // ★★ H4：货币腿（同一套方向：from 付出、to 收到）。**付款不足 ⇒ 走发行闸门**：
      //   唯一能"钱不够还照付"的主体是发行源，而本批没有任何发行人 ⇒ MoneyIssuance 恒抛。
      //   ⇒ "除发行源外任何账户的货币余额不得为负"这条守卫落在这里（全模块唯一写货币副本的地方）。
      for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
        long balance = moneyOf(householdMoney, from, leg.getKey());
        if (balance < leg.getValue()) {
          ActorRef issuer = MoneyIssuance.requireIssuerOf(leg.getKey()); // ★ 本批恒抛（fail-closed）
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
        setMoney(householdMoney, from, leg.getKey(), balance - leg.getValue());
      }
    } else if (operatorGoods.containsKey(transfer.from())
        || operatorMoney.containsKey(transfer.from())) {
      debitOperator(
          operatorGoods,
          operatorMoney,
          transfer.from(),
          transfer,
          transfer.goods(),
          transfer.money());
    }
    CohortKey to = householdOfActor.get(transfer.to());
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

  /** 经营者账的**付端**：与家户那一端**逐字同一条口径**（扣不动 ⇒ 当场抛；货币扣不动 ⇒ 走发行闸门 ⇒ 本批恒抛）。 */
  private static void debitOperator(
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      ActorRef actor,
      Transfer transfer,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    if (operatorGoods.containsKey(actor)) {
      for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
        long stock = operatorStockOf(operatorGoods, actor, leg.getKey());
        if (stock < leg.getValue()) {
          throw new IllegalStateException(
              "转移把经营者账扣成了负数（不许凭空吞）：经营者="
                  + actor
                  + " 商品="
                  + leg.getKey()
                  + " 余额="
                  + stock
                  + " 扣减="
                  + leg.getValue()
                  + "；转移="
                  + transfer);
        }
        setOperatorStock(operatorGoods, actor, leg.getKey(), stock - leg.getValue());
      }
    }
    if (operatorMoney.containsKey(actor)) {
      for (Map.Entry<CurrencyId, Long> leg : money.entrySet()) {
        long balance = operatorMoneyOf(operatorMoney, actor, leg.getKey());
        if (balance < leg.getValue()) {
          ActorRef issuer = MoneyIssuance.requireIssuerOf(leg.getKey()); // ★ 本批恒抛（fail-closed）
          throw new IllegalStateException(
              "转移把经营者的货币扣成了负数，而付方不是发行源（透支 = 发行）：经营者="
                  + actor
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
        setOperatorMoney(operatorMoney, actor, leg.getKey(), balance - leg.getValue());
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
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> consumedGoods,
      CohortKey key,
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
  private static Map<ActorRef, CohortKey> householdActorsOf(Map<CohortKey, ClassRow> rows) {
    Map<ActorRef, CohortKey> householdOfActor = new LinkedHashMap<>();
    for (CohortKey key : rows.keySet()) {
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
      ProductionRelation relation, Map<CohortKey, ClassRow> rows, HexCoord location) {
    for (CompensationRule rule : relation.rules()) {
      if (!(rule.recipient() instanceof Recipient.ToCohort toCohort)) {
        continue;
      }
      CohortKey cohort = toCohort.cohort();
      if (!cohort.hex().equals(location)) {
        throw new IllegalStateException(
            "规则指名的 cohort 不住在本格（H1：家户账 = (actor, cohort.hex())，条目却落在产业所在格）：activity="
                + relation.activity()
                + " cohort="
                + cohort
                + " cohort 的格="
                + cohort.hex()
                + " 产业的格="
                + location
                + "；规则="
                + rule);
      }
      if (!rows.containsKey(cohort)) {
        throw new IllegalStateException(
            "规则指名的 cohort 没有家户行（H1 fail-closed：受方就是那一个家户，交付不出去就当场抛，"
                + "不许静默留在 operator —— 那会把配置错伪装成自留）：activity="
                + relation.activity()
                + " cohort="
                + cohort
                + " 本格现有的家户="
                + rows.keySet().stream().filter(key -> key.hex().equals(location)).toList()
                + "；规则="
                + rule);
      }
    }
  }

  /**
   * ★★ <b>本格各家户本期劳动量</b>（{@code LABOR_AMOUNT} 那一族的分子/分母）：键 = **行键本身**（H0：行就是 cohort）， 值 = 该行的
   * {@code rowLabor}（= {@code laborMilli × 投入率 ÷ 1000 × cycleDays}）。
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
   * @param location 产业所在的那一格（{@code IndustryHexKeys.hexKeyOf} 是唯一拼写点）
   * @param cycleDays 该产业的周期天数（把"每日劳动"折成"本周期劳动"；见下面的量纲注释）
   */
  static Map<CohortKey, Long> laborOfCohort(
      Map<CohortKey, ClassRow> rows, HexCoord location, long cycleDays) {
    Map<CohortKey, Long> byCohort = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : rows.entrySet()) {
      if (!entry.getKey().hex().equals(location)) {
        continue; // 只取本格的家户（见方法注释：分母的口径与改前逐字相同）
      }
      ClassRow row = entry.getValue();
      // ★★ **每日口径 × 周期天数 = 本周期口径**（量纲的收口）：行的 `laborMilli` 与发放的配额同口径
      //   （**每日**千分劳动；结算逐日把它累加进 `cycleLaborMilli`），而 {@code LABOR_AMOUNT} 那一族量的是
      //   **本周期**的劳动量（{@code FIXED_IN_KIND_PER_LABOR} 的"每周期一笔"就写在名字里）。
      //   ★ 少了这个乘数，给养只有应有值的 1/cycleDays —— 实测（真档 14,806 人的格）：一周期只拿到 0.8% 的口粮，
      //     第 2 周期起人吃不饱、也播不下种 ⇒ 生产逐周期崩掉（`EconomyRealScaleClothTest` / `WorldgenInitializeToolTest`
      //     的跨周期用例当场红）。★ 分成类（`OUTPUT_SHARE`）不受影响：那一路是比值，量纲自消。
      long rowLabor = row.laborMilli() * row.participationPerMille() / 1000L * cycleDays;
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
  private static long scaleOf(Industry industry, long avgLaborMilli) {
    ProductionRecipe recipe = industry.recipe();
    long scale = Long.MAX_VALUE;
    // ★★ **K3：产能那一路 = 本格该产业的产能总量 ÷ 每单位需求**（{@link #capacityScaleOf} 是同一个算式；
    //   缺键按 0 读 ⇒ "这一格没有这类产能"⇒ 那一路把规模压到 0，不生产 —— 不是"无约束"）。
    for (Map.Entry<AssetKind, Long> entry : recipe.capacityPerUnit().entrySet()) {
      scale =
          Math.min(scale, industry.capacity().getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    if (recipe.laborPerUnit() > 0L) {
      scale = Math.min(scale, avgLaborMilli / recipe.laborPerUnit());
    }
    for (Map.Entry<CommodityId, Long> entry : recipe.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue; // 每单位需求为 0 ⇒ 这一路不构成约束（与旧代码 seedPerMu == 0 同款）
      }
      long drawn = industry.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
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
      LinkedHashMap<CohortKey, ClassRow> rows,
      LinkedHashMap<CohortKey, Long> deaths,
      CohortKey key,
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
   * <p>★ **偿还行为不做**（brief 明列的"留位"）：它要"有粮才还"的判断（属 spec §二 的"判"，应做成 GM 可调预设）。 {@link Debt#dueCycle()}
   * 保留、但**当前不被任何代码读**（本仓禁"看起来在记、其实永远是 0"的静默字段， 故在 {@link Debt} 的注释里写明）。
   *
   * <p>★ **计息对象 = 债务表里的全部债务**（不是"本周期新借的那些"）：{@code ratePerMillePerCycle} 的字面意思
   * 就是"每周期一次"，一条在第一周期借的债在第二周期末**照样**要再计一次（复利）。用 id 里的周期号去筛"只给新债计息" 反而会让老债从此永不生息。
   *
   * @param debts 债务表（就地更新：本金并入利息）
   * @param interest 本日利息的逐行累加器（**只记债务人**那一侧）
   */
  private static void chargeInterest(
      LinkedHashMap<DebtId, Debt> debts, LinkedHashMap<CohortKey, Long> interest) {
    for (DebtId id : new ArrayList<>(debts.keySet())) {
      Debt debt = debts.get(id);
      long charged = debt.principal() * debt.ratePerMillePerCycle() / 1000L;
      if (charged <= 0L) {
        continue; // 本金小到算不出 1 毫粮（或利率 0）⇒ 本轮不记：不写"看起来在记、其实永远是 0"的流水
      }
      debts.put(id, withPrincipal(debt, debt.principal() + charged));
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

  /** 把一笔发生额记进"逐行 × 逐商品"的累加器（{@code amount == 0} ⇒ 不落键，保持空表的纯形态）。 */
  private static void addGoods(
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> acc,
      CohortKey key,
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
  private static Map<String, Long> laborByActor(
      Map<LaborAllocationId, LaborAllocation> allocations) {
    Map<String, Long> byActor = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      byActor.merge(allocation.actor().id(), allocation.laborMilli(), Long::sum);
    }
    return byActor;
  }

  /**
   * 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。
   *
   * <p>★ H4：可见性从 {@code private} 放宽到**包内** —— {@code MarketSettlement} 要问同一个问题（"这一格有哪些家户"），
   * 而它**只能有一个答案**（两处各写一份分组 = 同一个量的第二处拼写点）。
   */
  static Map<String, List<CohortKey>> rowsByHex(Iterable<CohortKey> keys) {
    Map<String, List<CohortKey>> byHex = new LinkedHashMap<>();
    for (CohortKey key : keys) {
      // ★ H0：格键不再从产业 id 里拆（行里没有产业了）—— 它就是键的那一维（{@link IndustryHexKeys#hexKey} 是拼写点）。
      byHex
          .computeIfAbsent(
              IndustryHexKeys.hexKey(key.hex().q(), key.hex().r()), ignored -> new ArrayList<>())
          .add(key);
    }
    LinkedHashMap<String, List<CohortKey>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<CohortKey> rows = byHex.get(hexKey);
      // ★ 行序 = 阶层（字典序，与改前的 `key.slot()` 同款）→ 居住类型（H0 新增的那一维；改前它藏在产业段里）。
      rows.sort(
          Comparator.comparing((CohortKey k) -> k.stratum().value())
              .thenComparing(CohortKey::residence));
      sorted.put(hexKey, rows);
    }
    return sorted;
  }

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────
  //
  // ★★ H1：商品库存在**会话工作副本**里（|Map<CohortKey, Map<CommodityId, Long>>|；裁定 K1），行里没有 goods 了。
  //   三个助手是这份副本的**唯一读写点**（口径只有一处）：
  //     · 缺失键 = 该家户没有该商品（同 ClassRow.goods 原来的口径，见 EconomyDayStepper 的类注）；
  //     · 写 ≤ 0 ⇒ **去掉该键**（保持"空商品表"的纯形态，不落一个值为 0 的假键）；
  //     · 内层表**只读**：换值一律 put 一张新表（绝不在调用方给的表上做增删）⇒ 任何拿到的快照都不会被后续结算改掉。

  /** 某家户在某商品上的余额（没有这个键 ⇒ 0）。 */
  private static long stockOf(
      Map<CohortKey, Map<CommodityId, Long>> householdGoods, CohortKey key, CommodityId commodity) {
    return householdGoods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 粮的余额（{@link #stockOf} 的粮特化 —— 借粮那一段读得最频繁）。 */
  private static long grainOf(
      Map<CohortKey, Map<CommodityId, Long>> householdGoods, CohortKey key) {
    return stockOf(householdGoods, key, GRAIN);
  }

  /**
   * 把某家户在某商品上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该键）。
   *
   * <p>★ **只动那一个键**：同一个家户的账上同时住着粮、纤维、布、工具…… ⇒ 换一种商品绝不能把别的商品顺手抹掉 （旧版 {@code withGoodsGrain}
   * 只搬粮，多商品下那样写会静默清空其它商品）。
   */
  private static void setStock(
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      CohortKey key,
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
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      CohortKey key,
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
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney, CohortKey key, CurrencyId currency) {
    return householdMoney.getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
  }

  /** 把某家户在某币种上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该币种键）。 */
  private static void setMoney(
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      CohortKey key,
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
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      CohortKey key,
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
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, CohortKey> householdOfActor,
      ActorRef owner,
      CommodityId commodity,
      long amount) {
    if (amount == 0L) {
      return;
    }
    CohortKey cohort = householdOfActor.get(owner);
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
  static LinkedHashMap<CohortKey, Map<CurrencyId, Long>> emptyMoneyAccountsFor(
      Iterable<CohortKey> keys) {
    LinkedHashMap<CohortKey, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    for (CohortKey key : keys) {
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
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, CohortKey> householdOfActor,
      ActorRef operator) {
    CohortKey cohort = householdOfActor.get(operator);
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
      Map<CohortKey, ClassRow> rows, Map<CohortKey, Map<CurrencyId, Long>> householdMoney) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<CohortKey, ClassRow> entry : rows.entrySet()) {
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
      Map<CohortKey, ClassRow> rows, Map<CohortKey, Map<CommodityId, Long>> householdGoods) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<CohortKey, ClassRow> entry : rows.entrySet()) {
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
   */
  private static ClassRow withDailyNeed(ClassRow row, long day) {
    Map<CommodityId, Long> needs = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry :
        EconomyVocabulary.dailyNeedsMilli(row.population(), day).entrySet()) {
      if (entry.getValue() > 0L) {
        needs.put(new CommodityId(entry.getKey()), entry.getValue());
      }
    }
    return new ClassRow(
        row.key(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.money(),
        row.debts(),
        needs,
        row.effectiveDemand());
  }

  /** 追加一条债务引用（其余字段原样带过）。 */
  private static ClassRow withExtraDebt(ClassRow row, DebtId debtId) {
    List<DebtId> debts = new ArrayList<>(row.debts());
    debts.add(debtId);
    return new ClassRow(
        row.key(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.money(),
        debts,
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /** 换人口与有效劳动（饿死惩罚用；其余字段原样带过）。 */
  private static ClassRow withPopulationAndLabor(ClassRow row, long population, long laborMilli) {
    return new ClassRow(
        row.key(),
        population,
        laborMilli,
        row.participationPerMille(),
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /** 换进度、周期劳动累计与周期投入累计（其余字段原样带过；★ 配方四段、产能两张表与两张投入表都必须透传，丢了 = 静默清零）。 */
  private static Industry withCycleState(
      Industry industry, long progress, long cycleLabor, Map<CommodityId, Long> cycleInputUsed) {
    return new Industry(
        industry.id(),
        industry.name(),
        industry.regime(),
        industry.cycleDays(),
        progress,
        industry.capacityPerUnit(),
        // ★★ **K3 的产能总量透传**：本方法是结算每天重建 Industry 的唯一一处 ⇒ 漏传 = 每天把本格产能静默清零
        //   （下一个收获日的规模那一路恒 0 ⇒ 全格绝收，而账面看不出"是谁弄丢的"）。
        industry.capacity(),
        industry.dailyInputPerUnit(),
        industry.dailyLaborPerUnit(),
        industry.laborPerUnit(),
        industry.outputPerUnit(),
        industry.cycleInputPerUnit(),
        industry.slots(),
        industry.allocation(),
        cycleLabor,
        cycleInputUsed,
        // ★★ **经营主体透传**（S1 阶段 3 Task 3 的"不丢失"面）：本方法是结算**每天**重建 Industry 的
        //   唯一一处 ⇒ 漏传 = 每个结算日把 operator 静默丢掉/换成别的。**不许**在这里改用
        //   `defaultOperator(...)`：那会把"漏传"退化成"重新推导"，抹掉"显式绑定"与"缺省"的区别。
        industry.operator());
  }
}
