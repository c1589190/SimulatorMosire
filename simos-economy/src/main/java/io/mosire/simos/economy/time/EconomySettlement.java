package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *   <li>**缺口**：库存不够 ⇒ 先在同格内借粮（地主 → 富农 → 中农 的顺序，从有粮的行的**当日盈余**划转），借到的记一条 {@link Debt}（本金 = 借到量、利率
 *       {@code 20‰}、{@code dueCycle = 当前周期 + 1}、标的 = 粮）；**借完仍补不上**的部分记入本行流水的 {@code
 *       unmetNeed}（毫粮、逐日累加，供周期末的饿死判据 —— 见 {@link #FAMINE_MORTALITY_PER_MILLE}，默认致命率 0‰）。
 *   <li>**进度**：每个产业 {@code progressDays + 1}。
 *   <li>**劳动投入**：本产业当日实际劳动 = Σ(行 {@code laborMilli × participationPerMille / 1000}) —— 累加进 {@link
 *       Industry#cycleLaborMilli()}（供收获时算劳动瓶颈）。
 * </ol>
 *
 * <p>★★ **周期末追加**（{@code progressDays + 1 == cycleDays} 那一天，同一次日结算里）：
 *
 * <ol>
 *   <li>**产出**：{@code 实际投入亩 × 亩产 × 1000 毫粮/粮}（亩产取自 {@code outputPerUnit}，标定值 67 粮/亩 —— v2 spec
 *       §10.3）。实际投入亩 = {@code min(可用亩, 平均每日实际劳动 × 7 亩/劳动, 本周期扣到的种子 ÷ 每亩需种)}（v2 spec §3.1
 *       的**三路瓶颈**），**取小后向下取整到亩**（{@link #LAND_MU_PER_LABOR} / {@link #MILLI_PER_GRAIN}）。 ★ 注意两个 7
 *       无关：{@link #LAND_MU_PER_LABOR}（一标准劳动能种几**亩**）一直是 7； 「每亩几**粮**」是标定值 67（v1 曾是
 *       7，两者数值巧合，极易误读成漏改）。
 *   <li>**生产消耗**：扣 {@code 饲料 + 折旧}（{@link #FEED_PER_MILLE} + {@link
 *       #DEPRECIATION_PER_MILLE}）——**明文记入本期流水** （{@link FlowRow#consumed()}），不静默丢弃。★
 *       **留种不在这一项里**（v2 spec §3.4）：它在下一周期第 1 天以 {@code cycleInputPerUnit} 的形式现扣。
 *   <li>**分配**：按 {@link AllocationRule.Split}：{@code 行得 = 剩余产出 × (生产资料权重 × 该行土地占比 + 劳动权重 × 该行劳动占比)
 *       / 1000}（**定点整数、残差按最大余数法分派、Σ行得 = 剩余产出**，§八.7）。
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
 * <p>★★ **守恒（§6.1，账要平）**：本函数不凭空造粮、不凭空销粮。把每日/每期的发生额记进 {@link FlowRow} 后，恒有 {@code Σ(推进前库存) −
 * Σ(推进后库存) == Σ(流水消费) − Σ(流水所得)}：日耗、**播种扣掉的种子**与生产消耗在 {@code consumed} 里、收获的**毛产出**在 {@code income}
 * 里（净产出进库存，差额 = 生产消耗）。买/借/税等跨主体转移不改变总和（同格借贷是内部划转）。
 *
 * <p>★ **未激活**（{@code meta} 空）：原样返回（不做任何公式，§6.6）。
 */
public final class EconomySettlement {

  /** 1 粮 = 1000 毫粮（§7：库存按最小计量单位；{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = EconomyVocabulary.MILLI_PER_GRAIN;

  /** 1 标准劳动（1000 千分劳动）能经营的亩数：§十 / 资料 §十 的「1 标准劳动支持 7 亩」。 */
  public static final long LAND_MU_PER_LABOR = 7L;

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

  /** **借粮优先序**（§四 第 8 步 / 用户口径）：地主 → 富农 → 中农。★ **贫农不在放贷序列**里（v1 明文：它没有余粮可贷）； 只有这三个槽位的行才可能是债权人。 */
  private static final List<String> LENDER_SLOT_PRIORITY = List.of("landlord", "rich", "middle");

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

    // 逐行当日发生额（流水的事后组装）。
    LinkedHashMap<ClassKey, Long> consumedGrain = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> borrowing = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> income = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> productionLoss = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> unmetToday = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> deathsToday = new LinkedHashMap<>();

    // ── 0. 播种（周期的第一天）：**在当天吃饭之前**把种子划走（v2 spec §3.2）──────────────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    if (plantingDrawsFirst) {
      sowIfCycleStart(industries, rows, consumedGrain);
    }

    // ── 1~2. 消费 + 同格缺口（借粮 / 记未满足需求）────────────────────────────────────
    settleHexes(rows, debts, consumedGrain, borrowing, unmetToday, day, dueCycle);

    if (!plantingDrawsFirst) {
      sowIfCycleStart(industries, rows, consumedGrain);
    }

    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 + 饿死惩罚 ────────────────────────────
    boolean anyCycleClosed = false;
    Set<IndustryId> newCycleIndustries = new HashSet<>();
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      // ★ 新一轮周期的第一天：progressDays 归 0（创世亦然）⇒ 该产业各行流水**整行从 0 重记**（§八.5）。
      //   ★ 清零点**不在关账那一支**：那一支自己产生本周期最大的一笔所得（收获的毛产分配），
      //     在那里清零会把刚收获的那笔当场抹掉（关账日读到 income = 0，而 V5 判据要的正是关账日读到**整周期**的量）。
      if (industry.progressDays() == 0L) {
        newCycleIndustries.add(id);
      }
      List<ClassKey> keys = classKeysOf(rows, id);
      long laborToday = 0L;
      for (ClassKey key : keys) {
        ClassRow row = rows.get(key);
        laborToday += row.laborMilli() * row.participationPerMille() / 1000L;
      }
      long cycledLabor = industry.cycleLaborMilli() + laborToday;
      long progressed = industry.progressDays() + 1L;
      long nextProgress = progressed;
      long nextCycleLabor = cycledLabor;
      long nextSeedUsed = industry.cycleSeedUsedMilli(); // 非关账日：原样带过
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**（v1 spec §3.1 原文），
      //   cycleDays 的语义是"周期已满、待收获"。用 >= 才能把该合法状态收获掉；
      //   用 == 会让 progressDays == cycleDays 的下一日构造出 cycleDays + 1，在 Industry 构造期抛，
      //   异常穿出 EconomyTimeParticipant.simulateWorld ⇒ **整条推进 revision 失败**。
      if (progressed >= industry.cycleDays()) {
        // ── 周期末：产出 / 生产消耗 / 制度分配 —— 再算饿死（分配/收获不受死亡影响，本期产出照分给幸存者）──
        harvest(industry, rows, keys, cycledLabor, income, productionLoss);
        for (ClassKey key : keys) {
          long carried =
              newCycleIndustries.contains(id) || flows.get(key) == null
                  ? 0L
                  : flows.get(key).unmetNeed();
          applyFamine(
              rows,
              deathsToday,
              key,
              rows.get(key),
              carried + unmetToday.getOrDefault(key, 0L),
              day,
              industry.cycleDays(),
              famineMortalityPerMille);
        }
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextSeedUsed = 0L; // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的 seedCap 凭空变大）
        anyCycleClosed = true;
      }
      industries.put(id, withCycleState(industry, nextProgress, nextCycleLabor, nextSeedUsed));
    }

    // ── 流水：每行一条（本期发生额；税/利息 v1 恒 0）──────────────────────────────────
    for (ClassKey key : rows.keySet()) {
      long grainConsumed =
          consumedGrain.getOrDefault(key, 0L) + productionLoss.getOrDefault(key, 0L);
      Map<CommodityId, Long> consumed =
          grainConsumed > 0L ? Map.of(GRAIN, grainConsumed) : Map.of();
      long earned = income.getOrDefault(key, 0L);
      long borrowed = borrowing.getOrDefault(key, 0L);
      long netSurplus = earned - grainConsumed; // income − 消费 − 税(0) − 利息(0)
      long dayUnmet = unmetToday.getOrDefault(key, 0L);
      long dayDeaths = deathsToday.getOrDefault(key, 0L);
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
              (acc == null ? 0L : acc.income()) + earned,
              mergeConsumed(acc, consumed),
              acc == null ? 0L : acc.taxPaid(),
              acc == null ? 0L : acc.interestDue(),
              (acc == null ? 0L : acc.newBorrowing()) + borrowed,
              acc == null ? 0L : acc.repaid(),
              (acc == null ? 0L : acc.netSurplus()) + netSurplus,
              (acc == null ? 0L : acc.unmetNeed()) + dayUnmet,
              (acc == null ? 0L : acc.deaths()) + dayDeaths));
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
    return new EconomyData(Optional.of(nextMeta), industries, rows, debts, flows);
  }

  // ── 播种（周期的第一天）──────────────────────────────────────────────────────────────

  /**
   * ★★ **播种步**（v2 spec §3.2/§3.3）：**周期的第一天**（{@code progressDays == 0}）逐 {@link ClassRow} 从它**自己的**
   * {@code goods} 里扣种，并把实际扣到的量累加进 {@link Industry#cycleSeedUsedMilli()}。
   *
   * <pre>
   * rowLandMu = meansOfProduction[LAND] / 1000        // 千分亩 ⇒ 亩（★ 与 cycleInputPerUnit 的"毫粮/亩"同侧）
   * seedPerMu = cycleInputPerUnit.getOrDefault(LAND, 0)// 毫粮/亩；0 ⇒ 不扣（旧档/未配种子 ⇒ 与 V2 一字不差）
   * need      = rowLandMu × seedPerMu                 // 毫粮
   * 库存 ≥ need ⇒ 扣 need；库存 &lt; need ⇒ **扣光库存**（⇒ 收获日的 seedCapMu 自然缩小）
   * </pre>
   *
   * <p>★★ **种子各扣各的**（定案）：逐 {@code ClassRow} 从它自己的 {@code goods} 里扣，**不从全格池子扣**。
   * 理由：与"粮住在阶层行里"一致，且能自然产生阶级差异——贫农缸空 ⇒ 它的地荒着、地主的地照种 （收获日按 {@code Σ实际扣到的种子 / seedPerMu} 算可支撑亩数）。
   *
   * <p>★ **扣掉的量并入当日 {@code consumedGrain}**：留种是**本期的消费**（spec §二 把"留种的计量"列在"数"里）， 记进去才能保住 §6.1 的守恒式
   * {@code 库存减少 == Σ消费 − Σ所得}（否则配了种子的世界上那条等式不成立 ⇒ 端到端的守恒用例会变成假绿）。
   *
   * <p>★ **为什么不在这里扣"每日原料"**：{@code dailyInputPerUnit} 是**每日**口径，v1 仍是零读取点（spec §3.3
   * 明说两个字段并存、语义各自清楚），不在本步范围。
   */
  private static void sowIfCycleStart(
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<ClassKey, Long> consumedGrain) {
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry.progressDays() != 0L) {
        continue; // 只有周期的第一天播种
      }
      long seedPerMu = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L);
      if (seedPerMu == 0L) {
        continue; // ★ 0 与"缺键"同义：不扣、不缩地 ⇒ 未配种子的产业行为与 V2 一字不差
      }
      long sown = 0L;
      for (ClassKey key : classKeysOf(rows, id)) {
        ClassRow row = rows.get(key);
        long rowLandMu = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L) / 1000L;
        long need = rowLandMu * seedPerMu; // 毫粮（★ 亩 × 毫粮/亩）
        if (need == 0L) {
          continue; // 没有地 ⇒ 没有种子需求（真档里每座城的手工业行都是这一形态）
        }
        long stock = grainOf(row);
        long drawn = Math.min(stock, need); // ★ 扣不动就扣光库存（seedCapMu 会跟着缩）
        if (drawn == 0L) {
          continue;
        }
        rows.put(key, withGoodsGrain(row, stock - drawn));
        consumedGrain.merge(key, drawn, Long::sum);
        sown += drawn;
      }
      if (sown > 0L) {
        // ★★ 必须写回 industries 工作副本：收获（同一次日结算里、稍后跑）读的就是这一份累加器。
        industries.put(
            id,
            withCycleState(
                industry,
                industry.progressDays(),
                industry.cycleLaborMilli(),
                industry.cycleSeedUsedMilli() + sown));
      }
    }
  }

  // ── 消费 + 同格借粮 ─────────────────────────────────────────────────────────────────

  /**
   * 每个格一次：先各自吃自己的库存，库存不够的**在同格内借**（地主 → 富农 → 中农 的当日盈余），借到的记债；**仍补不上的** 记入未满足需求（{@code
   * unmetNeed}，供周期末的饿死判据用）。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表）。
   *
   * <p>★★ **当日需求的唯一算法 + 唯一落点**（V5；v2 spec §八.6/§八.8）：
   *
   * <pre>
   * need = EconomyVocabulary.dailyRationMilli(row.population(), day)   // 逐日差分，残差不丢
   * row.naturalNeeds = { grain: need }                                 // ★ 结算写、读口读（同源）
   * eaten = min(stock, need)；差额进 deficit（借粮/缺口）
   * </pre>
   */
  private static void settleHexes(
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<DebtId, Debt> debts,
      LinkedHashMap<ClassKey, Long> consumedGrain,
      LinkedHashMap<ClassKey, Long> borrowing,
      LinkedHashMap<ClassKey, Long> unmetNeed,
      long day,
      long dueCycle) {
    Map<String, List<ClassKey>> hexToRows = rowsByHex(rows.keySet());
    int[] debtSeq = {0}; // 结算内的债务序号（与 day 一起保证 DebtId 唯一且可复现）
    for (Map.Entry<String, List<ClassKey>> hex : hexToRows.entrySet()) {
      List<ClassKey> keys = hex.getValue();
      LinkedHashMap<ClassKey, Long> deficit = new LinkedHashMap<>();
      // ① 各自消费：扣 min(库存, 需求)；差额入 deficit。
      //   ★ 需求的口径 = **当天口粮**（每人每 120 天 10 粮 ⇒ 累计的逐日差分；不再有"每人每日 83"这个常量）。
      //     并**写回** naturalNeeds ⇒ 读口的"日耗"与结算当日用的是**同一个数**（§八.8 的"一条真相"）。
      for (ClassKey key : keys) {
        ClassRow row = rows.get(key);
        long need = EconomyVocabulary.dailyRationMilli(row.population(), day);
        long stock = grainOf(row);
        long eaten = Math.min(stock, need);
        ClassRow withNeed = withDailyNeed(row, need);
        rows.put(key, withGoodsGrain(withNeed, stock - eaten));
        // ★ **必须 merge 不能 put**：这张累加器现在与播种步共享（播种先跑时它已经记了种子那一笔），
        //   `put` 会把种子从当日消费里抹掉 ⇒ §6.1 的守恒式当场不成立（"留种要看得见"）。
        consumedGrain.merge(key, eaten, Long::sum);
        if (need - eaten > 0L) {
          deficit.put(key, need - eaten);
        }
      }
      if (deficit.isEmpty()) {
        continue;
      }
      // ② 放贷序列：地主 → 富农 → 中农，可取"当日盈余"（消费后的余粮）；同档按产业 id 定序。
      List<ClassKey> lenders = new ArrayList<>();
      for (String slot : LENDER_SLOT_PRIORITY) {
        for (ClassKey key : keys) {
          if (key.slot().value().equals(slot) && grainOf(rows.get(key)) > 0L) {
            lenders.add(key);
          }
        }
      }
      // ③ 逐缺口行（槽位 id 序）借：借到多少记多少债；没人有粮 ⇒ 剩下的只留作未满足的自然需求。
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
          long available = grainOf(rows.get(lender));
          if (available <= 0L) {
            continue;
          }
          long lent = Math.min(remaining, available);
          rows.put(lender, withGoodsGrain(rows.get(lender), available - lent));
          DebtId debtId = new DebtId("debt-" + day + "-" + debtSeq[0]++);
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
          rows.put(debtor, withExtraDebt(rows.get(debtor), debtId));
          // 借到的粮当日吃掉 ⇒ 计入当日消费。
          consumedGrain.merge(debtor, lent, Long::sum);
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 没人有粮：不造粮、不造债；记入**未满足需求**（周期末据此算饿死比例）。
        if (remaining > 0L) {
          unmetNeed.merge(debtor, remaining, Long::sum);
        }
      }
    }
  }

  // ── 周期收获与制度分配 ─────────────────────────────────────────────────────────────

  /**
   * 周期末的产出、生产消耗与制度分配（§四 周期结算 1~5；税明确不做）。
   *
   * <p>★★ **三路瓶颈取小**（v2 spec §3.1）：实际投入亩 = {@code min(可用亩, 劳动可经营亩, 种子可支撑亩)}。第三路 {@code seedCapMu =
   * Industry#cycleSeedUsedMilli() / seedPerMu}（毫粮 ÷ 毫粮/亩 = 亩）读的是**本周期实际扣到的种子** （播种步 {@link
   * #sowIfCycleStart} 的累加器）；{@code seedPerMu == 0} ⇒ **不施加这一路约束**（取 {@code
   * availableMu}），旧档与未配种子的产业据此与 V2 逐值一致。
   *
   * @param cycledLabor 本周期累计实际劳动（千分劳动·日）；`/cycleDays` 得**平均每日实际劳动**
   */
  private static void harvest(
      Industry industry,
      LinkedHashMap<ClassKey, ClassRow> rows,
      List<ClassKey> keys,
      long cycledLabor,
      LinkedHashMap<ClassKey, Long> income,
      LinkedHashMap<ClassKey, Long> productionLoss) {
    long totalLandMilliMu = 0L;
    long[] rowLand = new long[keys.size()];
    long[] rowLabor = new long[keys.size()];
    long totalLabor = 0L;
    for (int i = 0; i < keys.size(); i++) {
      ClassRow row = rows.get(keys.get(i));
      rowLand[i] = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L);
      totalLandMilliMu += rowLand[i];
      rowLabor[i] = row.laborMilli() * row.participationPerMille() / 1000L;
      totalLabor += rowLabor[i];
    }
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    long availableMu = totalLandMilliMu / 1000L; // 千分亩 ⇒ 亩（向下取整）
    long ableMu = avgLaborMilli * LAND_MU_PER_LABOR / 1000L; // 劳动可经营亩数（向下取整）
    // ★★ **第三路瓶颈**（v2 spec §3.1/§3.2）：本周期**实际扣到的种子**能支撑多少亩。
    //   ★ seedPerMu == 0 ⇒ **不加约束**（取 availableMu，min 里它不可能更小），**不是**"0 亩"：
    //     旧档与未配种子的产业据此与 V2 逐值一致；写成 0 会让它们颗粒无收。
    //   量纲：毫粮 ÷ (毫粮/亩) = 亩（与上面两路同为"亩"，故能进同一个 min）。
    //   ★ 恒有 seedCapMu ≤ availableMu（扣到的种子最多是 Σ地亩 × seedPerMu）⇒ 第三路只会**缩**面积。
    long seedPerMu = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L);
    long seedCapMu = seedPerMu == 0L ? availableMu : industry.cycleSeedUsedMilli() / seedPerMu;
    long actualMu = Math.min(availableMu, Math.min(ableMu, seedCapMu)); // 三路取小
    long perMu = industry.outputPerUnit().getOrDefault(GRAIN, 0L); // 粮/亩
    long gross = actualMu * perMu * MILLI_PER_GRAIN; // 毫粮（毛产出）
    // ★★ 生产消耗 = **饲料 + 农具折旧**（v2 spec §3.4：留种已移出收获扣减，改在播种日现扣）——
    //   两项各自具名（V7 参数目录落地后各自可调），此处取**两者之和**。
    long loss = gross * (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) / 1000L;
    long net = gross - loss; // 剩余产出（待分配）

    AllocationRule rule = industry.allocation();
    if (!(rule instanceof AllocationRule.Split split)) {
      // v1 只结算 Split（小农/封建租佃/手工业）；资本主义 WageFirst 是 §五 的后续增量。
      throw new UnsupportedOperationException(
          "v1 的周期分配只支持 AllocationRule.Split，产业 " + industry.id() + " 是 " + rule);
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      long meansPerMille = totalLandMilliMu == 0L ? 0L : rowLand[i] * 1000L / totalLandMilliMu;
      long laborPerMille = totalLabor == 0L ? 0L : rowLabor[i] * 1000L / totalLabor;
      weights[i] =
          (split.meansWeightPerMille() * meansPerMille
                  + split.laborWeightPerMille() * laborPerMille)
              / 1000L;
    }
    long[] netParts = allocate(net, weights);
    long[] lossParts = allocate(loss, weights);
    for (int i = 0; i < keys.size(); i++) {
      ClassKey key = keys.get(i);
      rows.put(key, withGoodsGrain(rows.get(key), grainOf(rows.get(key)) + netParts[i]));
      // 所得记**毛产出**（净得 + 其份额的生产消耗）⇒ 与 consumed 里的生产消耗配平（§6.1 的账要平）。
      income.merge(key, netParts[i] + lossParts[i], Long::sum);
      productionLoss.merge(key, lossParts[i], Long::sum);
    }
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

  /** 本期流水 {@code consumed} 的**逐日累加**（逐商品求和；{@code acc} 为空 ⇒ 直接用当天的表）。 */
  private static Map<CommodityId, Long> mergeConsumed(FlowRow acc, Map<CommodityId, Long> day) {
    if (acc == null) {
      return day;
    }
    LinkedHashMap<CommodityId, Long> consumed = new LinkedHashMap<>(acc.consumed());
    for (Map.Entry<CommodityId, Long> e : day.entrySet()) {
      consumed.merge(e.getKey(), e.getValue(), Long::sum);
    }
    return consumed;
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

  /** 换粮库存（0 ⇒ 去掉该键，保持"空商品表"的纯形态）。 */
  private static ClassRow withGoodsGrain(ClassRow row, long grain) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(row.goods());
    if (grain <= 0L) {
      goods.remove(GRAIN);
    } else {
      goods.put(GRAIN, grain);
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
   * 换**当日自然需求**（毫粮；0 ⇒ 去掉该键）。
   *
   * <p>★★ 这是 {@link ClassRow#naturalNeeds()} 的**唯一写入点**（v2 spec §八.8 的"一条真相"）：结算每天把当日需求写进去，
   * 读口（{@code ApiViews.economyHex} / GUI / MCP）直接读它，不再各算一遍 —— 否则人口一变（饿死、将来的任何人口变动）
   * 同一面板上的"人口"与"日耗"就会分叉。
   */
  private static ClassRow withDailyNeed(ClassRow row, long need) {
    Map<CommodityId, Long> needs = new LinkedHashMap<>();
    if (need > 0L) {
      needs.put(GRAIN, need);
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

  /** 换进度、周期劳动累计与周期种子累计（其余字段原样带过）。 */
  private static Industry withCycleState(
      Industry industry, long progress, long cycleLabor, long cycleSeedUsed) {
    return new Industry(
        industry.id(),
        industry.name(),
        industry.regime(),
        industry.cycleDays(),
        progress,
        industry.dailyInputPerUnit(),
        industry.dailyLaborPerUnit(),
        industry.outputPerUnit(),
        industry.cycleInputPerUnit(), // ★ 透传：换进度时不许把它丢了（丢了 = 静默清零）
        industry.slots(),
        industry.allocation(),
        cycleLabor,
        cycleSeedUsed);
  }
}
