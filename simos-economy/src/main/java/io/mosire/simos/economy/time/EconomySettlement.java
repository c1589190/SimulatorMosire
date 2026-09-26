package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionRecipe;
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
 * scale = min( ⌊Σ行 meansOfProduction[k] ÷ capacityPerUnit[k]⌋  …每种生产资料一路（农业 = 土地、织机/作坊 = 件数）
 *            , ⌊平均每日实际劳动 ÷ laborPerUnit⌋               …劳动一路
 *            , ⌊本周期实际扣到的投入_j ÷ inputPerUnit[j]⌋        …每种投入一路（种子 / 纤维 / 铁）)
 *       </pre>
 *       毛产 = {@code 规模 × outputPerUnit[j] × 1000 毫/单位}（**逐商品**；亩产 67 粮/亩 是 v2 spec §10.3 的标定值，而
 *       "每亩"从此是 {@code capacityPerUnit} 里的**数据**而不是隐式约定）。 **取小后向下取整** ⇒ 规模是整数。 ★ 某一路的"每单位需求"为
 *       {@code 0} ⇒ **不施加那一路约束**（不是"规模 0"）—— 旧档与未配投入的产业据此与 V2 逐值一致。
 *   <li>**生产消耗**：扣 {@code 饲料 + 折旧}（{@link #FEED_PER_MILLE} + {@link
 *       #DEPRECIATION_PER_MILLE}，**逐商品按同一千分比**）——**明文记入本期流水** （{@link FlowRow#consumed()}），不静默丢弃。★
 *       **留种不在这一项里**（v2 spec §3.4）：它在下一周期第 1 天以 {@code cycleInputPerUnit} 的形式现扣。
 *   <li>**分配**：按 {@link AllocationRule.Split}：{@code 行得 = 剩余产出 × (生产资料权重 × 该行土地占比 + 劳动权重 × 该行劳动占比)
 *       / 1000}（**定点整数、残差按最大余数法分派、Σ行得 = 剩余产出**，§八.7）。
 *   <li>**计息**（v2 spec §7.1 第三处 + §四 周期结算第 6 步；V6 落地）：全部债务按 {@code principal × ratePerMillePerCycle
 *       ÷ 1000} 计**一次**、**并入本金**（纯数学：不搬运粮、**不动任何库存**），同额记入**债务人**本行流水的 {@code interestDue} —— 见
 *       {@link #chargeInterest}。★ **偿还行为不做**（它要"有粮才还"的判断，属 V7+）。
 *   <li>**饿死判据**（2026-09-25 用户点名；**默认不致命**）：按本周期累加的 {@code unmetNeed} 折出"饿满整周期"的人口比例，在这一比例里按 {@code
 *       famineMortalityPerMille}（**默认 {@link #FAMINE_MORTALITY_PER_MILLE} = 0‰**）致死；人口减少、有效劳动同比例缩，
 *       死亡数记入 {@link FlowRow#deaths()}。**顺序**：在收获/分配**之后**（本期产出照分给幸存者，死亡不回溯产量），同一次结算内完成。
 *   <li>产出进各行粮库存；{@code progressDays} 归零、周期劳动清零、{@link EconomyMeta#lastClosedCycle()} +1。
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
 * <p>★★ **守恒（§6.1，账要平）**：本函数不凭空造物、也不凭空销物。把每日/每期的发生额记进 {@link FlowRow} 后，**逐商品**恒有 {@code Σ(推进前库存_j)
 * − Σ(推进后库存_j) == Σ(流水消费_j) − Σ(流水所得_j)}：日耗、**播种日扣掉的投入**与生产消耗在 {@code consumed} 里、收获的**毛产出**在
 * {@code income} 里（净产出进库存，差额 = 生产消耗）。买/借/税等跨主体转移不改变总和（同格借贷是内部划转）。 ★ **R3 起两种表都是逐商品的**（{@link
 * FlowRow#income()} 由标量改成 Map）：田里同时出粮与纤维 ⇒ "一条标量"表达不了"所得是什么"。
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
   * 同 {@link #settle(EconomyData, long, long)}，但**致死率可注入**（**包内可见**的旋钮，见 {@link
   * #FAMINE_MORTALITY_PER_MILLE}）。
   *
   * <p>★ 公开入口恒喂默认值；本重载服务用例（"改成非 0 ⇒ 死亡逐值可预测"必须**真的走得到**）与将来的 V7 参数目录。
   *
   * @param famineMortalityPerMille 致死率（千分数；∈ [0, 1000] ⇒ 死亡 ≤ 需求未被满足的那部分人口）
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
    // ★ 本期流水的逐日累加器：从 base 已累计的流水起步，在日循环里逐日并入后统一带出（§十一）。
    LinkedHashMap<ClassKey, FlowRow> flows = new LinkedHashMap<>(base.flows());
    EconomyData data = base;
    for (long day = fromTick + 1L; day <= toTick; day++) {
      data =
          settleOneDay(
              data, day, flows, PLANTING_DRAWS_BEFORE_CONSUMPTION, famineMortalityPerMille);
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
   * @return 结算后的新状态（{@code flows} 由 {@link #settle} 在循环结束后统一挂上）
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<ClassKey, FlowRow> flows,
      boolean plantingDrawsFirst) {
    return settleOneDay(base, day, flows, plantingDrawsFirst, FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * 同 {@link #settleOneDay(EconomyData, long, LinkedHashMap, boolean)}，但**致死率可注入**（**包内可见**的旋钮， 见
   * {@link #FAMINE_MORTALITY_PER_MILLE}）—— 这条入参是"旋钮"而非"死分支"的全部理由见该常量的注释。
   *
   * @param famineMortalityPerMille 饿死判据的致死率（千分数；**默认 0‰ = 不致命**）
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<ClassKey, FlowRow> flows,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille) {
    EconomyMeta meta = base.meta().orElseThrow();
    long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    long dueCycle = currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>(base.industries());
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>(base.classes());
    LinkedHashMap<DebtId, Debt> debts = new LinkedHashMap<>(base.debts());
    // ★★ **R2：劳动配额表**——日结算**读**它（当日劳动的唯一来源），R4 起在**饿死**那一步**按存活比例缩**它
    //   （见 {@link #scaleLaborOfIndustry}："人死了劳动没减"这条旧账的收口）⇒ 需要工作副本。
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations =
        new LinkedHashMap<>(base.allocations());
    LinkedHashMap<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>(base.laborSupply());

    // 逐行当日发生额（流水的事后组装）。★ R3 起两张实物表都是**逐商品**的（{@link FlowRow#income()} 由标量改成 Map）。
    LinkedHashMap<ClassKey, Map<CommodityId, Long>> consumedGoods = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> borrowing = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Map<CommodityId, Long>> income = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Map<CommodityId, Long>> productionLoss = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> deathsToday = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> birthsToday = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> interestToday = new LinkedHashMap<>(); // 周期末计息那一笔（§7.1③）

    // ── 0. 现扣周期投入（周期的第一天）：**在当天吃饭之前**把种子/原料划走（v2 spec §3.2）──────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    //   ★★ R4（T0）：这一步里**先做一次同格取材**（把田里的纤维搬到织机上），再各扣各的（见 drawCycleInputs）。
    if (plantingDrawsFirst) {
      drawCycleInputs(industries, rows, consumedGoods, income);
    }

    // ── 1~2. 消费 + 同格缺口（借粮 / 记未满足需求）────────────────────────────────────
    settleHexes(
        industries, rows, debts, consumedGoods, borrowing, unmetToday, day, currentCycle, dueCycle);

    if (!plantingDrawsFirst) {
      drawCycleInputs(industries, rows, consumedGoods, income);
    }

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 + 饿死惩罚 ────────────────────────────
    boolean anyCycleClosed = false;
    Set<IndustryId> newCycleIndustries = new HashSet<>();
    // ★★ **R2：当日劳动的唯一来源 = 劳动分配表**（第三阶段设计稿 §四）。按 actor id 归集一次（O(配额条数)），
    //   再逐产业取用 —— 产业 id 与 actor id 的对应关系由 EconomyData 的构造期守卫判死
    //   （产业型主体必须指名已存在的产业、非产业型主体不得与产业 id 撞名）。
    Map<String, Long> laborByActor = laborByActor(allocations);
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      // ★ 新一轮周期的第一天：progressDays 归 0（创世亦然）⇒ 该产业各行流水**整行从 0 重记**（§八.5）。
      //   ★ 清零点**不在关账那一支**：那一支自己产生本周期最大的一笔所得（收获的毛产分配），
      //     在那里清零会把刚收获的那笔当场抹掉（关账日读到 income = 0，而 V5 判据要的正是关账日读到**整周期**的量）。
      if (industry.progressDays() == 0L) {
        newCycleIndustries.add(id);
      }
      List<ClassKey> keys = classKeysOf(rows, id);
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
      //   异常穿出 EconomyTimeParticipant.simulateWorld ⇒ **整条推进 revision 失败**。
      if (progressed >= industry.cycleDays()) {
        // ── 周期末：产出 / 生产消耗 / 制度分配 —— 再算饿死（分配/收获不受死亡影响，本期产出照分给幸存者）──
        harvest(industry, rows, keys, cycledLabor, income, productionLoss);
        long populationBefore = 0L;
        for (ClassKey key : keys) {
          populationBefore += rows.get(key).population();
        }
        for (ClassKey key : keys) {
          long carried =
              newCycleIndustries.contains(id) || flows.get(key) == null
                  ? 0L
                  : flows.get(key).unmetNeed().getOrDefault(GRAIN, 0L);
          applyFamine(
              rows,
              deathsToday,
              key,
              rows.get(key),
              carried + unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L),
              day,
              industry.cycleDays(),
              famineMortalityPerMille);
        }
        long populationAfter = 0L;
        for (ClassKey key : keys) {
          populationAfter += rows.get(key).population();
        }
        // ★★ **R4：饿死之后劳动按同比例缩 —— 而且这次真的缩到配额上**（R2 如实记下的那条旧账：
        //   `applyFamine` 缩的是**行**劳动，而当日劳动自 R2 起取自**劳动分配表** ⇒ "人死了劳动没减"）。
        //   本步把该产业名下的**全部配额**与对应批次的**劳动供给**按同一个存活比例缩（见 {@link #scaleLaborOfIndustry}）。
        if (populationAfter < populationBefore) {
          scaleLaborOfIndustry(id, populationBefore, populationAfter, allocations, laborSupply);
        }
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextInputUsed = Map.of(); // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的投入瓶颈凭空变大）
        anyCycleClosed = true;
      }
      industries.put(id, withCycleState(industry, nextProgress, nextCycleLabor, nextInputUsed));
    }

    // ── 5. 周期末计息（§7.1③ / §四 周期结算第 6 步）────────────────────────────────────
    //   ★ **一天只计一次**：结算日循环里可能有多个产业在同一天关账（各产业 cycleDays 可以不同）⇒ 计息挂在
    //     "今天有产业关账"这个**日级**事实上，不在那个逐产业的 for 里（否则同格的 farm + craft 会各计一遍）。
    if (anyCycleClosed) {
      chargeInterest(debts, interestToday);
    }

    // ── 流水：每行一条（本期发生额；税 v1 恒 0、利息见上一步）────────────────────────────
    for (ClassKey key : rows.keySet()) {
      // ★ R3：两张实物表逐商品（消费 = 现扣投入 + 日耗 + 生产损耗；所得 = 收获的毛产分配）。
      Map<CommodityId, Long> consumed = mergeGoods(consumedGoods.get(key), productionLoss.get(key));
      Map<CommodityId, Long> earned = income.getOrDefault(key, Map.of());
      long borrowed = borrowing.getOrDefault(key, 0L);
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
      //      在那里清零会把刚收获的那笔当场抹掉。粒度是**按产业、按周期**，由 progressDays 决定 ⇒ 与 §十一 等价性相容。
      FlowRow acc = newCycleIndustries.contains(key.industry()) ? null : flows.get(key);
      flows.put(
          key,
          new FlowRow(
              key,
              mergeGoods(acc == null ? null : acc.income(), earned),
              mergeGoods(acc == null ? null : acc.consumed(), consumed),
              acc == null ? 0L : acc.taxPaid(),
              (acc == null ? 0L : acc.interestDue()) + interest,
              (acc == null ? 0L : acc.newBorrowing()) + borrowed,
              acc == null ? 0L : acc.repaid(),
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
    return new EconomyData(
        Optional.of(nextMeta), industries, rows, debts, flows, laborSupply, allocations);
  }

  // ── 人口变动回写（R4：社会侧的出生/死亡 → 经济侧的行人口、劳动配额与流水）──────────────────

  /**
   * ★★ **把一份"逐批次的出生/死亡"回写到经济侧**（R4 的接缝；**纯函数**：{@code base} 一字不改）。
   *
   * <pre>
   * 对每个批次 g（只处理两侧不全为 0 的那些）：
   *   ① 它在经济侧的"人"住在**它供给的那些产业**的行里（权重 = 各产业的行人口之和）
   *      —— 真档里农村批次供给 农业+家庭纺织（纺织行人口为 0）⇒ 人全在农业行；城镇批次供给手工业 ⇒ 全在手工业行。
   *   ② 出生/死亡按该权重摊到行上；行人口 ∓、**行的 laborMilli 按存活比例缩**（新生儿不干活）
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
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>(base.classes());
    LinkedHashMap<ClassKey, FlowRow> flows = new LinkedHashMap<>(base.flows());
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
        // ★★ **兜底：摊到"它住的那一格"的产业行上**（见 {@link LotChange} 的类注）—— 没有劳动配额的批次
        //   （0-14 岁那一档：劳动系数 0 ⇒ 创世不发配额）照样要吃饭、照样会死；不摊它，那一格的阶层行就会
        //   "人少了、饭照吃"，而两侧的人口账当场对不上（实测：真档一年差 23,452 人，全部是未成年那一档）。
        targets = industriesAt(base, change.at());
        if (targets.isEmpty()) {
          continue; // 该格本来就没有任何经济状态（世界还没播种到这里）⇒ 没有可摊的行
        }
      }
      long[] industryPopulations = new long[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        for (ClassKey key : classKeysOf(rows, targets.get(i))) {
          industryPopulations[i] += rows.get(key).population();
        }
      }
      long populationBefore = 0L;
      for (long population : industryPopulations) {
        populationBefore += population;
      }
      long[] birthsByIndustry = allocate(change.births(), industryPopulations);
      long[] deathsByIndustry = allocate(change.deaths(), industryPopulations);
      for (int i = 0; i < targets.size(); i++) {
        List<ClassKey> keys = classKeysOf(rows, targets.get(i));
        long[] rowPopulations = new long[keys.size()];
        for (int j = 0; j < keys.size(); j++) {
          rowPopulations[j] = rows.get(keys.get(j)).population();
        }
        long[] birthsParts = allocate(birthsByIndustry[i], rowPopulations);
        long[] deathsParts = allocate(deathsByIndustry[i], rowPopulations);
        for (int j = 0; j < keys.size(); j++) {
          ClassKey key = keys.get(j);
          ClassRow row = rows.get(key);
          long population = row.population();
          if (population <= 0L) {
            continue;
          }
          long remaining = population - deathsParts[j]; // deathsParts ≤ row 人口（按人口权重切，见 allocate）
          long labor = row.laborMilli() * remaining / population; // 死亡同比例缩；出生不加劳动
          rows.put(
              key, withPopulationAndLabor(row, remaining + birthsParts[j], Math.max(0L, labor)));
          if (birthsParts[j] != 0L || deathsParts[j] != 0L) {
            flows.put(key, withLifecycle(flows.get(key), key, birthsParts[j], deathsParts[j]));
          }
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
        base.meta(), base.industries(), rows, base.debts(), flows, laborSupply, allocations);
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

  /**
   * ★★ **按格索引全部产业**（{@code "q_r" → 产业表}，保序：产业表的插入序）。
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
  private static FlowRow withLifecycle(FlowRow flow, ClassKey key, long births, long deaths) {
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
   * ★★ **现扣周期投入步**（v2 spec §3.2/§3.3；**R3/V7 起泛化**）：**周期的第一天**（{@code progressDays == 0}）逐 {@link
   * ClassRow} 从它**自己的** {@code goods} 里扣 {@link ProductionRecipe#inputPerUnit()}（农业 = 种子，织机 = 纤维，作坊
   * = 纤维 + 铁），并把实际扣到的量**按商品**累加进 {@link Industry#cycleInputUsedMilli()}。
   *
   * <pre>
   * rowScale  = min over k ∈ capacityPerUnit: ⌊row.meansOfProduction[k] ÷ capacityPerUnit[k]⌋   // 本行自己的规模上限
   * need[j]   = rowScale × inputPerUnit[j]           // 该行按自己的产能**想**扣多少商品 j
   * 库存_j ≥ need[j] ⇒ 扣 need[j]；库存_j &lt; need[j] ⇒ **扣光库存_j**（⇒ 收获日的投入那一路瓶颈自然缩小）
   * </pre>
   *
   * ★★ **旧口径逐值复刻**：{@code capacityPerUnit = {LAND: 1000}} 且 {@code inputPerUnit = {GRAIN: 8000}} 时，
   * {@code rowScale = ⌊行土地千分亩 ÷ 1000⌋ = 行的亩数}、{@code need = 亩数 × 8000 毫粮} —— 与 V3 的两行逐值相同（880/1000
   * 的向下取整也 同处）。
   *
   * <p>★★ **投入各扣各的**（定案）：逐 {@code ClassRow} 从它自己的 {@code goods} 里扣，**不从全格池子扣**。
   * 理由：与"粮住在阶层行里"一致，且能自然产生阶级差异——贫农缸空 ⇒ 它的地荒着、地主的地照种 （收获日按 {@code Σ实际扣到的投入 / inputPerUnit} 算可支撑规模）。
   *
   * <p>★ **扣掉的量并入当日 {@code consumed}**：投入是**本期的消费**（spec §二 把"留种的计量"列在"数"里）， 记进去才能保住 §6.1 的守恒式
   * {@code 逐商品库存减少 == Σ消费 − Σ所得}（否则配了投入的世界上那条等式不成立 ⇒ 端到端的守恒用例会变成假绿）。
   *
   * <p>★ **为什么不在这里扣"每日原料"**：{@code dailyInputPerUnit} 是**每日**口径，本轮仍是零读取点（spec §3.3
   * 明说两个字段并存、语义各自清楚），不在本步范围。
   */
  private static void drawCycleInputs(
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> income) {
    // ★★ **R4（T0）：先做一次"同格按需取材"，再各扣各的**（见 transferIntraHexInputs）——
    //   它把**田里的纤维搬到同格的织机上**（R3 的遗留：原料原先只有创世那一次性的一份 ⇒ 第 2 周期起停工）。
    transferIntraHexInputs(industries, rows, consumedGoods, income);
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry.progressDays() != 0L) {
        continue; // 只有周期的第一天扣投入
      }
      Map<CommodityId, Long> perScale = industry.inputPerUnit();
      if (perScale.isEmpty()) {
        continue; // ★ 空表与"缺键"同义：不扣、不缩规模 ⇒ 未配投入的产业行为与 V2 一字不差
      }
      Map<CommodityId, Long> drawnTotal = new LinkedHashMap<>();
      for (ClassKey key : classKeysOf(rows, id)) {
        ClassRow row = rows.get(key);
        long rowScale = rowScaleOf(row, industry);
        if (rowScale == 0L) {
          continue; // 没有产能 ⇒ 没有投入需求（真档里每座城的手工业行都是这一形态）
        }
        for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
          if (entry.getValue() == 0L) {
            continue;
          }
          CommodityId commodity = entry.getKey();
          long need = rowScale * entry.getValue(); // 毫单位（★ 单位规模 × 毫单位/单位规模）
          long stock = row.goods().getOrDefault(commodity, 0L);
          long drawn = Math.min(stock, need); // ★ 扣不动就扣光库存（投入那一路瓶颈会跟着缩）
          if (drawn == 0L) {
            continue;
          }
          row = withGoods(row, commodity, stock - drawn);
          addGoods(consumedGoods, key, commodity, drawn);
          drawnTotal.merge(commodity, drawn, Long::sum);
        }
        rows.put(key, row);
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
   * ★★ **同格按需取材**（R4 的 T0；R3 遗留的收口）：**织机缺 FIBER ⇒ 从同格有富余的行取**（取多少 = 缺多少，上限 = 供方的富余）。
   *
   * <pre>
   * 逐格：① 算出每一行**本周期想扣多少**（{@code rowScale × inputPerUnit}，口径与现扣步同一个算式）
   *      ② 逐商品、逐缺口行：从同格**其它产业**的富余行取 min(缺口, 富余)
   *      富余 = max(0, 库存 − 该行自己的本周期投入需求 − （粮）该行整周期口粮)
   * </pre>
   *
   * <p>★★ **它解决的是 R3 如实记下的那个遗留**：织机与作坊吃的原料原先只有**创世一次性给的**那一份（= 本格农田一个周期的纤维副产），
   * 而"把田里的纤维搬到织机上"是**跨行的实物转移** ⇒ 第 2 个周期起织机停工、农田自己产的纤维照常累积在农业行里 （R3
   * 报告原文）。本步之后，**每个周期**织机都能从同格农田拿到新一期的纤维 ⇒ 纺织持续。
   *
   * <p>★★ **对称记账**（**R2 与 V3 都栽过"单侧扣减"**，故两侧都写清楚）：
   *
   * <ul>
   *   <li>**供方**：库存 −X，且 **{@code consumed} += X**（它出去的是实物，不是"债权"）；
   *   <li>**受方**：库存 +X，且 **{@code income} += X**。
   * </ul>
   *
   * <p>★★ **为什么受方那一侧也必须落账**：守恒式是 {@code Σ(前库存) − Σ(后库存) == Σ消费 − Σ所得}。只记供方的消费而不记受方的所得， 会让右边凭空多出
   * X（而左边的库存总账是平的）⇒ 逐商品的守恒用例当场红。两侧**同时**记，X 恰好抵消，剩下的差额正好等于受方 随后真的扣掉的那一笔 —— 这就是"同一份变更集里的原子转移"在账上的样子。
   *
   * <p>★★ **粮不走这条通道**（**有意的收窄**）：粮的跨行流动已经有制度（同格借粮：债权人序列、余粮口径、债务记账）， 而"从同格富余的行取粮"是一条**无偿**通道 ——
   * 两者并存会让同一批粮有两条路（一条要还、一条不用还）， 并抹平"投入各扣各的"那条阶层口径（贫农缸空 ⇒ 它的地荒着）。本轮要接的缺口本来就是**纤维**（织机的原料）。
   *
   * <p>★ **供方不含同一产业的行**（**有意的收窄**）："投入各扣各的"是既有口径（贫农缸空 ⇒ 它的地荒着、地主的地照种 ——
   * 阶层差异正来自这里），让同产业的行互相补原料会把它抹平。而本轮要接的缺口本来就是**跨产业**的那一条（田里的纤维 → 织机）。
   *
   * <p>★ **取不到就停工**（"拒凭空造"）：本步**不造**任何东西，取不满的行照旧按它自己的库存扣 ⇒ 收获时投入那一路瓶颈自然缩小 （与 {@link #scaleOf}
   * 的既有口径一致）。★ 遍历序 = 格（字典序）→ 槽位 → 产业（字典序）→ 行序（同前）⇒ 可复现。
   */
  private static void transferIntraHexInputs(
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> income) {
    // ① 只有**周期第一天**的产业才会现扣投入 ⇒ 只有它们有"取材需求"（与现扣步同一个门槛）。
    LinkedHashMap<IndustryId, Map<CommodityId, Long>> perUnitByIndustry = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      if (entry.getValue().progressDays() != 0L) {
        continue;
      }
      Map<CommodityId, Long> perUnit = entry.getValue().inputPerUnit();
      if (!perUnit.isEmpty()) {
        perUnitByIndustry.put(entry.getKey(), perUnit);
      }
    }
    if (perUnitByIndustry.isEmpty()) {
      return;
    }
    for (Map.Entry<String, List<ClassKey>> hex : rowsByHex(rows.keySet()).entrySet()) {
      List<ClassKey> keys = hex.getValue();
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> need = new LinkedHashMap<>();
      LinkedHashSet<CommodityId> commodities = new LinkedHashSet<>();
      for (ClassKey key : keys) {
        Map<CommodityId, Long> perUnit = perUnitByIndustry.get(key.industry());
        if (perUnit == null) {
          continue;
        }
        long rowScale = rowScaleOf(rows.get(key), industries.get(key.industry()));
        if (rowScale == 0L) {
          continue; // 没有产能 ⇒ 没有投入需求（真档里没有织机的行就是这一形态）
        }
        Map<CommodityId, Long> mine = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> entry : perUnit.entrySet()) {
          if (entry.getValue() > 0L) {
            // ★★ **只搬它真的用得上的量**（上限 = 该商品**之外**的最紧约束）：否则搬过去也是白扔 ——
            //   真档里城市作坊缺铁时，纤维搬过去会在现扣步被当投入扣掉而**产不出任何东西**（规模那一路是 0），
            //   于是"从田里取纤维"变成了"把纤维倒进一个空转的作坊"。见 {@link #rowUsageScale}。
            long usage =
                rowUsageScale(rows.get(key), industries.get(key.industry()), entry.getKey());
            if (usage == 0L) {
              continue;
            }
            mine.put(entry.getKey(), usage * entry.getValue());
            commodities.add(entry.getKey());
          }
        }
        if (!mine.isEmpty()) {
          need.put(key, mine);
        }
      }
      if (commodities.isEmpty()) {
        continue;
      }
      for (CommodityId commodity : commodities) {
        if (commodity.equals(GRAIN)) {
          // ★★ **粮不走这条通道**（有意的收窄）：粮的跨行流动**已经有制度** —— 同格借粮（债权人序列、余粮口径、
          //   债务记账，R2/V6 建的）。再开一条"从同格有富余的行取粮当种子"的**无偿**通道，同一批粮就会有两条路
          //   （一条要还、一条不用还），而"贫农缸空 ⇒ 它的地荒着、地主的地照种"那条既有口径会被抹平。
          //   ⇒ 本轮要接的缺口本来就是**纤维**（织机的原料）：它不是口粮、没有既有制度、也没有替代通道。
          continue;
        }
        for (ClassKey receiver : keys) {
          Map<CommodityId, Long> mine = need.get(receiver);
          if (mine == null) {
            continue;
          }
          long gap =
              mine.getOrDefault(commodity, 0L)
                  - rows.get(receiver).goods().getOrDefault(commodity, 0L);
          if (gap <= 0L) {
            continue; // 自己缸里就够了 ⇒ 不取（"按需"：取多少 = 缺多少）
          }
          for (ClassKey supplier : keys) {
            if (gap <= 0L) {
              break;
            }
            if (supplier.industry().equals(receiver.industry())) {
              continue; // ★ 供方不含同产业（见方法注释：那会抹平"投入各扣各的"）
            }
            long surplus = transferSurplusOf(rows.get(supplier), commodity, need.get(supplier));
            if (surplus <= 0L) {
              continue;
            }
            long moved = Math.min(gap, surplus);
            rows.put(
                supplier,
                withGoods(
                    rows.get(supplier),
                    commodity,
                    rows.get(supplier).goods().getOrDefault(commodity, 0L) - moved));
            rows.put(
                receiver,
                withGoods(
                    rows.get(receiver),
                    commodity,
                    rows.get(receiver).goods().getOrDefault(commodity, 0L) + moved));
            addGoods(consumedGoods, supplier, commodity, moved); // 供方：减库存 + 记消费（不许单侧扣减）
            addGoods(income, receiver, commodity, moved); // 受方：加库存 + 记所得（守恒式的另一侧）
            gap -= moved;
          }
        }
      }
    }
  }

  /**
   * ★★ **这一行的"用得上"上限**（同格取材用）：{@code min( 各 capacity 那一路 , 除该商品之外的每种投入那一路 )} ——
   * 即"**把该商品给足**之后，这一行最多还能干多少活"。
   *
   * <pre>
   * rowUsageScale(row, industry, j) = min( ⌊means[k] ÷ capacityPerUnit[k]⌋           …每种生产资料一路
   *                                       , ⌊row.goods[j'] ÷ inputPerUnit[j']⌋  j'≠j …**除 j 之外**的每种投入一路 )
   * </pre>
   *
   * <p>★★ **它挡的是一类"搬了也白搬"的浪费**（不是洁癖，是真档里量得到的）：城市作坊的配方是 {@code FIBER + IRON + LABOR + WORKSHOP →
   * CLOTH + TOOL}，而**铁只有创世那一份** —— 第 2 个周期起铁为 0， 作坊的规模那一路恒
   * 0（产不出任何东西）。若取材步按"作坊名义上想要多少纤维"搬，这套纤维会在现扣步被 **当投入扣掉**（consumed 记一笔）而产出为 0 ⇒
   * 田里的纤维被倒进一个空转的作坊，织机反而拿不到料。 用本上限后，那种作坊的取材需求是 **0**，纤维留给真的用得上的织机。
   *
   * <p>★ **劳动那一路刻意不算进来**：周期第一天 {@code cycleLaborMilli} 还是 0，把它算进来会让**所有**产业的用得上上限 都是
   * 0（取材永不发生）。劳动瓶颈在周期末的 {@link #scaleOf} 里照旧生效 —— 那里才是它该在的地方。
   *
   * <p>★ 与 {@link #rowScaleOf} 的关系：那是"该行自己的产能"，这是"该行拿到这个商品之后能干多少"（≤ 前者）。
   */
  private static long rowUsageScale(ClassRow row, Industry industry, CommodityId excluded) {
    long scale = rowScaleOf(row, industry);
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L || entry.getKey().equals(excluded)) {
        continue;
      }
      scale = Math.min(scale, row.goods().getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return Math.max(0L, scale);
  }

  /**
   * 供方的**可取材量**：{@code max(0, 库存 − 自己的本周期投入需求)}。
   *
   * <p>★ **粮不在本通道里**（调用方先挡掉，理由见 {@code transferIntraHexInputs}）：故这里没有"口粮保留额"那一项 ——
   * 本通道只走**工业原料**（纤维/铁/木），它们不是任何人的口粮，也没有"余粮"语义。
   *
   * @param ownNeed 该供方**自己**在本周期想扣的投入（可为 null = 它不扣投入）
   */
  private static long transferSurplusOf(
      ClassRow supplier, CommodityId commodity, Map<CommodityId, Long> ownNeed) {
    long available = supplier.goods().getOrDefault(commodity, 0L);
    if (available <= 0L) {
      return 0L;
    }
    if (ownNeed != null) {
      available -= ownNeed.getOrDefault(commodity, 0L);
    }
    return Math.max(0L, available);
  }

  /**
   * **一行自己的规模上限**（= 由它**自己**的生产资料决定的那一份）：{@code min over k ∈ capacityPerUnit: ⌊means[k] ÷
   * capacityPerUnit[k]⌋}（无产能约束 ⇒ {@link Long#MAX_VALUE}，由调用方的其它路约束兜住）。
   *
   * <p>★ 与收获时的产业级规模是**同一个算式**（只是把 {@code Σ行 means} 换成这一行的 {@code means}）—— 于是一行"想扣多少"
   * 与产业"能产多少"用同一把尺。
   */
  private static long rowScaleOf(ClassRow row, Industry industry) {
    // ★ capacityPerUnit 非空且逐值 > 0（构造期守卫）⇒ 循环至少跑一次、scale 必然被赋一个有限值。
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      scale =
          Math.min(
              scale, row.meansOfProduction().getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return scale;
  }

  // ── 消费 + 同格借粮 ─────────────────────────────────────────────────────────────────

  /**
   * 每个格一次：先各自吃自己的库存，库存不够的**在同格内借**（地主 → 富农 → 中农 的**余粮** —— 见 {@link #lendableOf}）；借到的**累加进同一条**
   * {@link Debt}（§7.2 的聚合）；**仍补不上的** 记入未满足需求（{@code unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表， **不进放贷行的
   * {@code consumed}** —— 它出去的是"债权"不是"消费"）。故 §6.1 的守恒式在**格/全局**上成立、**逐行不成立**。
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
   * eaten = min(stock, need)；差额进 deficit（借粮/缺口）
   * </pre>
   *
   * @param industries 产业表（只为取放贷行的 {@code cycleDays} 算它**本周期自需**）
   * @param currentCycle 正在进行的**周期序号**（{@code lastClosedCycle + 1}）：进 {@code DebtId} ⇒ 跨周期必然不同、
   *     同周期必然相同
   * @param dueCycle 借粮的到期周期 = {@code currentCycle + 1}（只写进**新条**；老条原样带过）
   */
  private static void settleHexes(
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<DebtId, Debt> debts,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<ClassKey, Long> borrowing,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> unmetNeed,
      long day,
      long currentCycle,
      long dueCycle) {
    Map<String, List<ClassKey>> hexToRows = rowsByHex(rows.keySet());
    for (Map.Entry<String, List<ClassKey>> hex : hexToRows.entrySet()) {
      List<ClassKey> keys = hex.getValue();
      LinkedHashMap<ClassKey, Long> deficit = new LinkedHashMap<>();
      // ① 各自消费：扣 min(库存, 需求)；差额入 deficit（粮）/ 直接记缺口（布）。
      //   ★ 需求的口径 = **当天口粮/衣着**（每人每 120 天 10 粮、每 365 天 1 匹布 ⇒ 累计的逐日差分；不再有"每人每日 83"这个常量）。
      //     并**写回** naturalNeeds ⇒ 读口的"日耗"与结算当日用的是**同一个数**（§八.8 的"一条真相"）。
      //   ★★ **R4（T2）：布也真的被消费**（R3 只做出形状、不消费）：库存不够就照记缺口，走同一套 unmetNeed 记账。
      //     ★ 布**不参与同格借粮**：借贷制度在本仓只对**粮**有规则（债权人序列、余粮口径都是粮的口径）⇒ 布的缺口直接记下。
      for (ClassKey key : keys) {
        ClassRow row = withDailyNeed(rows.get(key), day);
        long need = row.naturalNeeds().getOrDefault(GRAIN, 0L);
        long stock = grainOf(row);
        long eaten = Math.min(stock, need);
        row = withGoods(row, GRAIN, stock - eaten);
        // ★ **必须 merge 不能 put**：这张累加器现在与现扣投入步共享（后者先跑时它已经记了种子/原料那一笔），
        //   `put` 会把投入从当日消费里抹掉 ⇒ §6.1 的守恒式当场不成立（"投入要看得见"）。
        addGoods(consumedGoods, key, GRAIN, eaten);
        long clothNeed = row.naturalNeeds().getOrDefault(CLOTH, 0L);
        long clothStock = row.goods().getOrDefault(CLOTH, 0L);
        long clothGot = Math.min(clothStock, clothNeed);
        if (clothNeed > 0L) {
          row = withGoods(row, CLOTH, clothStock - clothGot);
          addGoods(consumedGoods, key, CLOTH, clothGot);
        }
        rows.put(key, row);
        if (clothNeed - clothGot > 0L) {
          addGoods(unmetNeed, key, CLOTH, clothNeed - clothGot);
        }
        if (need - eaten > 0L) {
          deficit.put(key, need - eaten);
        }
      }
      if (deficit.isEmpty()) {
        continue;
      }
      // ② 放贷序列：地主 → 富农 → 中农；同档按产业 id 定序。
      //   ★★ **可取的不是"全部库存"、也不是"当日盈余"**（V1 的注释与代码相反，V6 一并修）：可取的是
      //     **余粮 = 库存 − 本周期自需 × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000**（{@link
      // #lendableOf}）。
      List<ClassKey> lenders = new ArrayList<>();
      for (SocialClassId slot : LENDER_SLOT_PRIORITY) {
        for (ClassKey key : keys) {
          if (key.slot().equals(slot) && grainOf(rows.get(key)) > 0L) {
            lenders.add(key);
          }
        }
      }
      // ③ 逐缺口行（槽位 id 序）借：借到多少累加多少债；没人有**余粮** ⇒ 剩下的只留作未满足的自然需求。
      List<ClassKey> debtors = new ArrayList<>(deficit.keySet());
      debtors.sort(
          Comparator.comparing((ClassKey k) -> k.slot().value())
              .thenComparing(k -> k.industry().value()));
      for (ClassKey debtor : debtors) {
        long remaining = deficit.get(debtor);
        for (ClassKey lender : lenders) {
          if (remaining <= 0L) {
            break;
          }
          long available = lendableOf(rows.get(lender), industries);
          if (available <= 0L) {
            continue; // 只剩口粮/已经没有余粮 ⇒ 不贷（V1 是在这里把全部库存贷出去）
          }
          long lent = Math.min(remaining, available);
          rows.put(lender, withGoods(rows.get(lender), GRAIN, grainOf(rows.get(lender)) - lent));
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
          // 借到的粮当日吃掉 ⇒ 计入当日消费。
          addGoods(consumedGoods, debtor, GRAIN, lent);
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 没人有**余粮**：不造粮、不造债；记入**未满足需求**（周期末据此算饿死比例）。
        if (remaining > 0L) {
          addGoods(unmetNeed, debtor, GRAIN, remaining);
        }
      }
    }
  }

  /**
   * ★★ **放贷行的可贷额（余粮）**（v2 spec §7.1 第一处；V6 落地）：
   *
   * <pre>
   * reserve  = EconomyVocabulary.cumulativeRationMilli(放贷行人口, 该行产业的 cycleDays)
   *            × {@link #LENDER_SUBSISTENCE_RESERVE_PER_MILLE} ÷ 1000        // 毫粮
   * lendable = max(0, 库存 − reserve)
   * </pre>
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
  private static long lendableOf(ClassRow lender, Map<IndustryId, Industry> industries) {
    long stock = grainOf(lender);
    if (stock <= 0L) {
      return 0L;
    }
    long cycleDays = industries.get(lender.key().industry()).cycleDays();
    long reserve =
        EconomyVocabulary.cumulativeRationMilli(lender.population(), cycleDays)
            * LENDER_SUBSISTENCE_RESERVE_PER_MILLE
            / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /**
   * ★★ **债务 id 的唯一拼写点**（v2 spec §7.2）：{@code (周期, 债务人, 债权人, 商品)} ⇒ {@code DebtId}，三条硬要求逐条满足：
   *
   * <ol>
   *   <li>**确定性**：本方法是纯函数（无计数器、无时间、无迭代序）⇒ 同一元组**必然**给出同一个 id（重放/分支可比）；
   *   <li>**不含 {@code "."}**：{@link ClassKey#toString()} 用 {@code "|"} 分隔两段，本方法另用 {@code "-"}/
   *       {@code ">"} 分隔各段 ⇒ 拼出来**没有点**。★ 这不是审美问题：{@code AddressParser} 把 {@code debt.<id>} 读成
   *       {@code Entity(kind="debt", name=<id>)} 时**在第一个 "." 处切**，id 里带点会把名字截断成另一个名字，
   *       该债务的地址从此**解析不到**（{@link io.mosire.simos.economy.resolve.EconomyResolver} 只会返回空候选）；
   *   <li>**跨周期不同**：周期号是首段 ⇒ 新周期的借入**不会覆盖**旧条（"哪一周期借的"这条信息因此保住， 供 V7+ 的到期/偿还读）。
   * </ol>
   *
   * <pre>
   * debt-c&lt;周期&gt;-&lt;债务人 industry|slot&gt;&gt;&lt;债权人 industry|slot&gt;-&lt;商品&gt;
   * 例：debt-c1-farm@0_0|poor_peasant>farm@0_0|landlord-grain
   * </pre>
   *
   * <p>★ **货币债（{@code commodity} 空；v1 不产生）**用同位置的哨兵段 {@code money}。
   */
  static DebtId debtIdOf(
      long cycle, ClassKey debtor, ClassKey creditor, Optional<CommodityId> commodity) {
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
   * 周期末的产出、生产消耗与制度分配（§四 周期结算 1~5；税明确不做）。
   *
   * <p>★★ **规模由最紧约束决定**（R3/V7）：{@link #scaleOf} 逐路取小（每种 capacity 一路 + 劳动一路 + 每种投入一路）。 旧口径（"可用亩 /
   * 劳动可经营亩 / 种子可支撑亩"三路）是它在 {@code capacityPerUnit = {LAND: 1000}}、{@code inputPerUnit = {GRAIN:
   * 8000}} 下的特例。
   *
   * <p>★★ **产出逐商品**：{@code 毛产_j = 规模 × outputPerUnit[j] × 1000 毫/单位}，各自扣生产损耗后按**同一组权重**
   * 分配给本产业的各行（{@code Σ行得 == 剩余产出}，残差按最大余数法）。
   *
   * @param cycledLabor 本周期累计实际劳动（千分劳动·日）；`/cycleDays` 得**平均每日实际劳动**
   */
  private static void harvest(
      Industry industry,
      LinkedHashMap<ClassKey, ClassRow> rows,
      List<ClassKey> keys,
      long cycledLabor,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> income,
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> productionLoss) {
    ProductionRecipe recipe = industry.recipe();
    // ★★ **分配权重**（R3 起对**所有**产业都有意义）：生产资料那一路按**该产业声明的** capacity 种类求和
    //   （原来只有"土地"一个键 ⇒ 非土地产业的权重恒 0，产出全凭劳动那一路）。
    long[] rowMeans = new long[keys.size()]; // 各行在**本产业所有 capacity 种类**上的份额之和（未归一）
    long[] rowLabor = new long[keys.size()];
    long totalMeans = 0L;
    long totalLabor = 0L;
    for (int i = 0; i < keys.size(); i++) {
      ClassRow row = rows.get(keys.get(i));
      for (AssetKind kind : recipe.capacityPerUnit().keySet()) {
        rowMeans[i] += row.meansOfProduction().getOrDefault(kind, 0L);
      }
      totalMeans += rowMeans[i];
      // ★★ **R2 起这两件事分家，写清楚各自的角色**（免得后来者以为其中一个是漏改的旧算法）：
      //   · `rowLabor`（= 行 laborMilli × 投入率）**只喂分配权重** —— 它回答"本产业的产出在**阶层之间**怎么分"
      //     （贫农出多少工 vs 地主出多少工），是阶层关系，不是"这个产业投了多少劳动"；
      //   · 产业**总共**投了多少劳动由 `cycledLabor`（= 劳动分配表的配额之和，见 settleOneDay）承担 —— 它回答
      //     "劳动瓶颈允许多大生产规模"。
      //   创世时两者逐值相等（配额就是按这条链算出来的），故改口径不改数。
      rowLabor[i] = row.laborMilli() * row.participationPerMille() / 1000L;
      totalLabor += rowLabor[i];
    }
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    long scale = scaleOf(industry, rows, keys, avgLaborMilli); // ★ 最紧约束
    // ★★ 生产消耗 = **饲料 + 农具折旧**（v2 spec §3.4：留种已移出收获扣减，改在现扣投入步）——
    //   两项各自具名（V7 参数目录落地后各自可调），此处取**两者之和**、**逐商品按同一千分比**（作坊的 3% 即织机磨损）。

    AllocationRule rule = industry.allocation();
    if (!(rule instanceof AllocationRule.Split split)) {
      // v1 只结算 Split（小农/封建租佃/手工业）；资本主义 WageFirst 是 §五 的后续增量。
      throw new UnsupportedOperationException(
          "v1 的周期分配只支持 AllocationRule.Split，产业 " + industry.id() + " 是 " + rule);
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      long meansPerMille = totalMeans == 0L ? 0L : rowMeans[i] * 1000L / totalMeans;
      long laborPerMille = totalLabor == 0L ? 0L : rowLabor[i] * 1000L / totalLabor;
      weights[i] =
          (split.meansWeightPerMille() * meansPerMille
                  + split.laborWeightPerMille() * laborPerMille)
              / 1000L;
    }
    // ★★ **逐商品产出入账**（V7 的关键一步）：毛产 = 规模 × outputPerUnit[j] × 1000 毫/单位。
    for (Map.Entry<CommodityId, Long> output : recipe.outputPerUnit().entrySet()) {
      CommodityId commodity = output.getKey();
      long gross = scale * output.getValue() * MILLI_PER_GRAIN; // 毫单位（毛产出）
      if (gross <= 0L) {
        continue;
      }
      long loss = gross * (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) / 1000L;
      long net = gross - loss; // 剩余产出（待分配）
      long[] netParts = allocate(net, weights);
      long[] lossParts = allocate(loss, weights);
      for (int i = 0; i < keys.size(); i++) {
        ClassKey key = keys.get(i);
        rows.put(
            key,
            withGoods(
                rows.get(key),
                commodity,
                rows.get(key).goods().getOrDefault(commodity, 0L) + netParts[i]));
        // 所得记**毛产出**（净得 + 其份额的生产消耗）⇒ 与 consumed 里的生产消耗配平（§6.1 的账要平）。
        addGoods(income, key, commodity, netParts[i] + lossParts[i]);
        addGoods(productionLoss, key, commodity, lossParts[i]);
      }
    }
  }

  /**
   * ★★ **规模 = 最紧约束**（spec §五 原文；R3/V7 把 {@code harvest} 里的"三路全是亩"泛化成"每种 capacity 一路 + 劳动一路 +
   * 每种投入一路"）：
   *
   * <pre>
   * scale = min( ⌊Σ行 meansOfProduction[k] ÷ capacityPerUnit[k]⌋  …每种生产资料一路
   *            , ⌊平均每日实际劳动 ÷ laborPerUnit⌋               …劳动一路
   *            , ⌊本周期实际扣到的投入_j ÷ inputPerUnit[j]⌋        …每种投入一路 )
   * </pre>
   *
   * <p>★★ **每一路都可以"不施加"**（该路的"每单位需求"为 {@code 0} 或该路的表里没有这一项）：这与旧代码 {@code seedPerMu == 0 ⇒ 不加约束}
   * 是**同一条口径** —— 旧档与未配投入/未配劳动的产业据此与 V2 逐值一致，**不是**"规模 0"（写成 0 会让它们颗粒无收）。
   *
   * <p>★ 整数运算、向下取整；{@code capacityPerUnit} 非空且逐值 &gt; 0（构造期守卫）⇒ 结果必有上界。
   *
   * @param avgLaborMilli 平均每日实际劳动（千分劳动）= 本周期配额之和 ÷ cycleDays
   */
  private static long scaleOf(
      Industry industry, Map<ClassKey, ClassRow> rows, List<ClassKey> keys, long avgLaborMilli) {
    ProductionRecipe recipe = industry.recipe();
    long scale = Long.MAX_VALUE;
    long[] totals = new long[AssetKind.values().length];
    for (ClassKey key : keys) {
      ClassRow row = rows.get(key);
      for (Map.Entry<AssetKind, Long> entry : recipe.capacityPerUnit().entrySet()) {
        totals[entry.getKey().ordinal()] +=
            row.meansOfProduction().getOrDefault(entry.getKey(), 0L);
      }
    }
    for (Map.Entry<AssetKind, Long> entry : recipe.capacityPerUnit().entrySet()) {
      scale = Math.min(scale, totals[entry.getKey().ordinal()] / entry.getValue());
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
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<ClassKey, Long> deaths,
      ClassKey key,
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
      LinkedHashMap<DebtId, Debt> debts, LinkedHashMap<ClassKey, Long> interest) {
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
      LinkedHashMap<ClassKey, Map<CommodityId, Long>> acc,
      ClassKey key,
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

  /** 该产业的阶层行键，**按槽位 id 字典序**（可复现；也是最大余数法"同余数按下标序"的那个下标序）。 */
  private static List<ClassKey> classKeysOf(Map<ClassKey, ClassRow> rows, IndustryId id) {
    List<ClassKey> keys = new ArrayList<>();
    for (ClassKey key : rows.keySet()) {
      if (key.industry().equals(id)) {
        keys.add(key);
      }
    }
    keys.sort(Comparator.comparing(key -> key.slot().value()));
    return keys;
  }

  /** 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。 */
  private static Map<String, List<ClassKey>> rowsByHex(Iterable<ClassKey> keys) {
    Map<String, List<ClassKey>> byHex = new LinkedHashMap<>();
    for (ClassKey key : keys) {
      byHex
          .computeIfAbsent(
              IndustryHexKeys.hexKeyOf(key.industry()).orElse(key.industry().value()),
              ignored -> new ArrayList<>())
          .add(key);
    }
    LinkedHashMap<String, List<ClassKey>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<ClassKey> rows = byHex.get(hexKey);
      rows.sort(
          Comparator.comparing((ClassKey k) -> k.slot().value())
              .thenComparing(k -> k.industry().value()));
      sorted.put(hexKey, rows);
    }
    return sorted;
  }

  // ── 行与产业的不可变替换 ───────────────────────────────────────────────────────────

  private static long grainOf(ClassRow row) {
    return row.goods().getOrDefault(GRAIN, 0L);
  }

  /**
   * 换**某一种商品**的库存（{@code ≤ 0} ⇒ 去掉该键，保持"空商品表"的纯形态）。
   *
   * <p>★ **只动那一个键**：R3 起的行里同时住着粮、纤维、布、工具…… ⇒ 换一种商品绝不能把别的商品顺手抹掉（旧版 {@code withGoodsGrain}
   * 只搬粮，多商品下那样写会静默清空其它商品）。
   */
  private static ClassRow withGoods(ClassRow row, CommodityId commodity, long amount) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(row.goods());
    if (amount <= 0L) {
      goods.remove(commodity);
    } else {
      goods.put(commodity, amount);
    }
    return new ClassRow(
        row.key(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.meansOfProduction(),
        goods,
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /**
   * 换**当日自然需求**（**逐商品**；{@code 0} 的那一项不落键）。
   *
   * <p>★★ 这是 {@link ClassRow#naturalNeeds()} 的**唯一写入点**（v2 spec §八.8 的"一条真相"）：结算每天把当日需求写进去，
   * 读口（{@code ApiViews.economyHex} / GUI / MCP）直接读它，不再各算一遍 —— 否则人口一变（饿死、将来的任何人口变动）
   * 同一面板上的"人口"与"日耗"就会分叉。
   *
   * <p>★★ **R3 起口径来自 {@link EconomyVocabulary#dailyNeedsMilli}**（每商品一条：粮 + 布）："粮食不足与衣物不足对死亡的时间尺度
   * 显然不能一样"（spec §七）—— 形状先做出来，**阈值与死亡作用留 R4**（故布的缺口本轮只被记下来、不参与饿死判据）。
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
        row.meansOfProduction(),
        row.goods(),
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
        row.meansOfProduction(),
        row.goods(),
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
        row.meansOfProduction(),
        row.goods(),
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /** 换进度、周期劳动累计与周期投入累计（其余字段原样带过；★ 配方四段与两张投入表都必须透传，丢了 = 静默清零）。 */
  private static Industry withCycleState(
      Industry industry, long progress, long cycleLabor, Map<CommodityId, Long> cycleInputUsed) {
    return new Industry(
        industry.id(),
        industry.name(),
        industry.regime(),
        industry.cycleDays(),
        progress,
        industry.capacityPerUnit(),
        industry.dailyInputPerUnit(),
        industry.dailyLaborPerUnit(),
        industry.laborPerUnit(),
        industry.outputPerUnit(),
        industry.cycleInputPerUnit(),
        industry.slots(),
        industry.allocation(),
        cycleLabor,
        cycleInputUsed);
  }
}
