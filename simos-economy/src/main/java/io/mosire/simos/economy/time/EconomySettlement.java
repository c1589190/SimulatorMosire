package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

  private EconomySettlement() {}

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
    EconomyDayPipeline.settle(
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
        survivalSocialFacts);
  }

  static void applyLaborBudgetsInto(
      EconomySession session, Map<HouseholdId, Long> budgetsByHousehold) {
    PopulationWritebackSettlement.applyLaborBudgetsInto(session, budgetsByHousehold);
  }

  static void applyNaturalNeedsInto(
      EconomySession session, Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold) {
    PopulationWritebackSettlement.applyNaturalNeedsInto(session, needsByHousehold);
  }

  static void applyPopulationChangeInto(EconomySession session, List<LotChange> changes) {
    PopulationWritebackSettlement.applyPopulationChangeInto(session, changes);
  }

  static void applyHouseholdPopulationDeltasInto(
      EconomySession session, Map<HouseholdId, Long> deltas) {
    PopulationWritebackSettlement.applyHouseholdPopulationDeltasInto(session, deltas);
  }

  static void applyHouseholdPopulationDeltasInto(
      EconomySession session,
      Map<HouseholdId, Long> deltas,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      long day) {
    PopulationWritebackSettlement.applyHouseholdPopulationDeltasInto(
        session, deltas, birthsByHousehold, deathsByHousehold, day);
  }

  static long inputSatisfactionPerMille(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> hand, long plannedScale) {
    return ProductionInputSettlement.inputSatisfactionPerMille(perScale, hand, plannedScale);
  }

  static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, SettlementIndex index) {
    return ProductionInputSettlement.capacityScaleOf(unit, industry, index);
  }

  static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return ProductionInputSettlement.plannedCapacityScaleOf(unit, industry, index, condition);
  }

  static boolean inputShortfallOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return ProductionInputSettlement.inputShortfallOf(unit, industry, index, condition);
  }

  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    AccountTransferSettlement.applyTransfer(
        householdGoods, householdMoney, householdOfActor, transfer);
  }

  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    AccountTransferSettlement.applyTransfer(
        householdGoods, householdMoney, householdOfActor, transfer, journal);
  }

  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer) {
    AccountTransferSettlement.applyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        householdOfActor,
        transfer);
  }

  static void applyTransfer(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Transfer transfer,
      MoneyIssuanceJournal journal) {
    AccountTransferSettlement.applyTransfer(
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        householdOfActor,
        transfer,
        journal);
  }

  static MoneyIssuanceRecord withdrawFromTreasury(
      EconomySession session,
      AccountSession accounts,
      GovernmentId governmentId,
      CurrencyId currency,
      long amount,
      long day,
      String operationRef) {
    return AccountTransferSettlement.withdrawFromTreasury(
        session, accounts, governmentId, currency, amount, day, operationRef);
  }

  static LossConsumption consumeForLoss(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      ProductionLedger.Accumulator ledger,
      IndustryId lossAccount,
      HouseholdId household,
      CommodityId commodity,
      long requestedMilli) {
    return ConsumptionSettlement.consumeForLoss(
        householdGoods,
        householdFrozenGoods,
        ledger,
        lossAccount,
        household,
        commodity,
        requestedMilli);
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
    return LaborSettlement.laborOfCohort(householdEconomies, location, cycleDays);
  }

  static HexCoord hexOfIndustry(IndustryId id) {
    return SettlementMath.hexOfIndustry(id);
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
    return SettlementMath.allocate(total, weights);
  }

  static Map<String, List<HouseholdId>> rowsByHex(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    return SettlementMath.rowsByHex(householdEconomies);
  }

  @SuppressFBWarnings(
      value = "UPM_UNCALLED_PRIVATE_METHOD",
      justification =
          "生产路径已改走 LaborSettlement.reallocateLaborPartitioned；本方法仍作为验收测试（GovServiceCommitmentC7Test）反射直调真实现的稳定入口，删除会破坏 T1 的反射夹具，故保留纯转发、不复制公式")
  private static void reallocateLabor(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index) {
    LaborSettlement.reallocateLabor(
        units, industries, householdEconomies, laborCommitments, operatorConditions, index);
  }

  @SuppressFBWarnings(
      value = "UPM_UNCALLED_PRIVATE_METHOD",
      justification =
          "生产路径直接调用 DebtRepaymentSettlement.repayDebts；本方法仍作为验收测试（DebtBasedClearingAcceptanceTest）反射直调真实现的稳定入口，删除会破坏 T1 的反射夹具，故保留纯转发、不复制公式")
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
    DebtRepaymentSettlement.repayDebts(
        day,
        householdEconomies,
        debts,
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        repaid,
        repaidMoney,
        repaidPrincipalByDebt,
        householdOfActor,
        marketsByHousehold,
        repayeeResolver,
        householdPrices,
        ledger,
        issuanceJournal,
        sameDayWageDebtIds);
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
  record ClosedUnit(
      ProductionUnitId unit, IndustryId industry, List<HouseholdId> keys, boolean inputShortfall) {}

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
}
