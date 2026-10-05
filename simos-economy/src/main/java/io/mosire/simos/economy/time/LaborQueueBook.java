package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>P2-B §13.5：家户劳动时间的"预期单位劳动净收益"排队簿</b>（纯函数；只读入参，不写状态）。
 *
 * <p>★★ <b>它替代的旧口径</b>：改前 {@code EconomySettlement.reallocateLabor} 只按"这一周期真用得上的劳动"修剪
 * 既有配额、再按家户预算等比例封顶（释放出来的时间留在空缺，不重排）—— 没有"这家户这几小时为什么给这个 mode"的
 * 利润判据。本类把 §13.5 的两步显式化：
 *
 * <pre>
 * ① 每个生产单元先算"本 tick 最大可吸收劳动"
 *      usableScale = min( 产能/资产那一路（ProductionProcessBook.plannedCapacityScaleOf）,
 *                        每项投入那一路（⌊本周期已扣到的投入 ÷ 单位规模需求⌋） )
 *      maxAbsorbableLaborMilli = usableScale × laborPerUnit        // laborPerUnit > 0
 *    ★ 投入那一路为 0（没有、也拿不到）⇒ maxAbsorbableLaborMilli = 0 ⇒ 对应劳动就是空缺（不凭空开工）。
 * ② 排序键 = 预期净收益 ÷ 预期消耗的家户劳动小时（不是单位产出利润率）
 *      outputValueMilli = Σ_j 产出_j（商品单位/规模） × bidPrice_j（毫钱/商品单位）
 *      inputCostMilli   = Σ_j ⌊投入_j（毫商品/规模） × askPrice_j（毫钱/商品单位） ÷ 1000⌋
 *      netMilli         = outputValueMilli − inputCostMilli
 *      key              = ⌊netMilli × 1_000_000 ÷ max(1, laborPerUnit)⌋   （百万分之一毫钱 / 单位劳动）
 * ③ 从 key 最高的开始填，填满它的最大可吸收量，再填下一个，直到家户预算用完或没有可吸收的生产方式
 *      并列按 modeId（无 mode 的旧 unit 按 modeKey）/ unit id 字典序升序
 *      key < {@link #MIN_NET_PER_LABOR_SCALED}（预期 ≤ 0）不参与利润队列（首版默认，可调参数）
 * </pre>
 *
 * <p>★★ <b>首版的具名近似（如实记，不静默）</b>：
 *
 * <ol>
 *   <li><b>净收益只减投入成本</b>：租金/工资等 relation 分账（{@code ProductionSettlement} 的那一套）不在本类重算 ——
 *       跨主体分账需要每条 relation 的规则与付款能力，属 {@code ExpectedProfitBook.prospect} 的职责；本类保证排序键
 *       与"劳动约束下每小时产出"一致，分账差异留待把 prospect 接进排队时收口；
 *   <li><b>需求封顶不建模</b>：产出按本格 bid 价全额估值，不计"卖不掉"的部分（对应 {@code ExpectedProfitBook} 的
 *       {@code sellable}/{@code DEMAND_CAPPED}）；
 *   <li><b>未定价产出/投入</b>按 0 计并标具名 {@code NO_PRICE}/{@code NO_INPUT_PRICE}（与 {@code Market} 的
 *       "未定价 vs 明确 0 价"口径同源）；
 *   <li><b>投入"能不能拿到"以已经扣进 unit 的 {@code cycleInputUsedMilli} 为准</b>：本 tick 的市场采购排在劳动分配
 *       之后，故"市场有价可买"不改变本 tick 的最大可吸收量（这正是 §13.4"借不到 ⇒ 劳动空缺"的实现口径）。
 * </ol>
 *
 * <p>★ <b>确定性</b>：全部整数运算，无随机、无时钟、无 UUID；遍历序按 offer 的规范比较器排序，同输入同输出。
 */
public final class LaborQueueBook {

  private LaborQueueBook() {}

  /** 单位劳动净收益的刻度（百万分之一毫钱 / 单位劳动；与 {@code EnterpriseProfitBook.PER_LABOR_SCALE} 同尺）。 */
  public static final long PER_LABOR_SCALE = 1_000_000L;

  /**
   * ★ 首版可调参数：预期单位劳动净收益低于它就**不参与利润队列**（0 = 白干、负 = 亏本）。
   *
   * <p>★ 为什么用 1 而不是 0：{@code scaledRatio} 是整数除法，0 恰好能被"刚好保本"与"略负"同时命中；取 1 让
   * "预期 ≤ 0 不参与"这条线在整数网格上不歧义（计划 §13.5："预期 ≤ 0 的生产方式不参与利润队列（首版默认，可写注释留调参）"）。
   */
  public static final long MIN_NET_PER_LABOR_SCALED = 1L;

  /** 一条"这个 unit 本 tick 最多能吸收多少家户劳动、每单位劳动的预期净收益是多少"的读数。 */
  public record Offer(
      ProductionUnitId unitId,
      Optional<ProductionModeId> modeId,
      String rankModeKey,
      long maxAbsorbableLaborMilli,
      boolean outputPriced,
      long expectedOutputValueMilli,
      long expectedInputCostMilli,
      long expectedNetMilli,
      long expectedLaborMilli,
      long netPerLaborScaled,
      String reason) {

    public Offer {
      Objects.requireNonNull(unitId, "Offer.unitId 不得为 null");
      Objects.requireNonNull(modeId, "Offer.modeId 不得为 null（没有请用 Optional.empty()）");
      Objects.requireNonNull(rankModeKey, "Offer.rankModeKey 不得为 null（没有 mode 用 modeKey）");
      Objects.requireNonNull(reason, "Offer.reason 不得为 null（没有就用空串）");
      if (maxAbsorbableLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "Offer.maxAbsorbableLaborMilli 不得为负: " + maxAbsorbableLaborMilli);
      }
      if (expectedLaborMilli < 0L) {
        throw new IllegalArgumentException("Offer.expectedLaborMilli 不得为负: " + expectedLaborMilli);
      }
    }

    /** 本 offer 进利润队列吗（有可吸收量且预期 > 0）。 */
    public boolean queueable() {
      return maxAbsorbableLaborMilli > 0L && netPerLaborScaled >= MIN_NET_PER_LABOR_SCALED;
    }
  }

  /** 排队结果里的一条决定（含"为什么没给/给了多少"，读口与日志共用同一份）。 */
  public record Decision(int rank, Offer offer, long grantedLaborMilli, String outcome) {

    public Decision {
      Objects.requireNonNull(offer, "Decision.offer 不得为 null");
      Objects.requireNonNull(outcome, "Decision.outcome 不得为 null");
      if (grantedLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "Decision.grantedLaborMilli 不得为负: " + grantedLaborMilli);
      }
    }
  }

  /** 一个家户本 tick 的分配结果（可读：预算、已分配、空缺、逐条理由）。 */
  public record Plan(
      HouseholdId household,
      long budgetMilli,
      long preservedLaborMilli,
      long allocatedLaborMilli,
      long idleLaborMilli,
      List<Decision> decisions) {

    public Plan {
      Objects.requireNonNull(household, "Plan.household 不得为 null");
      if (budgetMilli < 0L
          || preservedLaborMilli < 0L
          || allocatedLaborMilli < 0L
          || idleLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "Plan 的各项劳动量不得为负: "
                + budgetMilli
                + "/"
                + preservedLaborMilli
                + "/"
                + allocatedLaborMilli
                + "/"
                + idleLaborMilli);
      }
      decisions = List.copyOf(decisions);
    }

    /** 给出某个 unit 的排队决定；没有该 unit 的 offer ⇒ 空。 */
    public Optional<Decision> decisionOf(ProductionUnitId unitId) {
      for (Decision decision : decisions) {
        if (decision.offer().unitId().equals(unitId)) {
          return Optional.of(decision);
        }
      }
      return Optional.empty();
    }
  }

  // ── ① 单个 unit 的 offer ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>算一个 unit 本 tick 的排队读数</b>（纯读；不写任何状态）。
   *
   * @param unit 生产单元（投入那一路读它的 {@code cycleInputUsedMilli}）
   * @param industry 技术模板
   * @param market 该家户居住格的价表；可为 null（= 无价，产出按 0 计并标 NO_PRICE）
   * @param condition 经营者状态（产能那一路的计划系数）；可为 null（按 ACTIVE）
   * @param index 当天的派生索引（可用资产/产能只查表）
   * @param modeId 该 unit 所属生产方式（按组织表推；旧 unit 可为空）
   * @param rankModeKey 并列时的 mode 键（modeId 值；旧 unit 用 modeKey）
   */
  public static Offer offer(
      ProductionProcess unit,
      Industry industry,
      Market market,
      OperatorCondition condition,
      SettlementIndex index,
      Optional<ProductionModeId> modeId,
      String rankModeKey) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(rankModeKey, "rankModeKey");

    long capacityScale = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    long inputScale = Long.MAX_VALUE;
    CommodityId bindingInput = null;
    long bindingDrawn = 0L;
    long bindingPerScale = 0L;
    for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
      long perScale = input.getValue() == null ? 0L : input.getValue();
      if (perScale <= 0L) {
        continue;
      }
      long drawn = unit.cycleInputUsedMilli().getOrDefault(input.getKey(), 0L);
      long scale = drawn / perScale;
      if (scale < inputScale) {
        inputScale = scale;
        bindingInput = input.getKey();
        bindingDrawn = drawn;
        bindingPerScale = perScale;
      }
    }
    long absorbableScale = Math.min(capacityScale, inputScale);

    String reason = "";
    long maxAbsorbable;
    long laborPerUnit = laborPerUnitOf(industry);
    if (laborPerUnit <= 0L) {
      // 劳动那一路不施加约束（与旧 laborNeedOf 同款）：它不需要家户时间 ⇒ 不进利润队列，既有配额原样保留。
      maxAbsorbable = 0L;
      reason = "LABOR_FREE";
    } else if (industry.recipe().outputPerUnit().isEmpty()) {
      // ★ 没有产出配方的活动（承运/贸易）：它的收入走 MarketSettlement 的运费/路线路径，本排队簿按产出×价
      //   估值看不见 ⇒ 具名保留既有配额（不静默清零）；接 MarketReport 估值是下一批。
      maxAbsorbable = 0L;
      reason = "NO_RECIPE_OUTPUT";
    } else if (capacityScale <= 0L) {
      maxAbsorbable = 0L;
      reason = "NO_CAPACITY";
    } else if (!industry.recipe().inputPerUnit().isEmpty() && inputScale <= 0L) {
      maxAbsorbable = 0L;
      reason =
          "NO_INPUT:"
              + (bindingInput == null ? "?" : bindingInput.value())
              + ":drawn="
              + bindingDrawn
              + ":perScale="
              + bindingPerScale
              + (market != null && bindingInput != null && market.hasPrice(bindingInput)
                  ? ":market-priced-but-purchase-after-allocation"
                  : ":no-market-price");
    } else {
      maxAbsorbable = multiplyCapped(Math.max(0L, absorbableScale), laborPerUnit);
      if (maxAbsorbable <= 0L) {
        reason = "NO_CAPACITY";
      }
    }

    // ② 每单位规模的预期价值（与产出/投入的量纲见类注）。
    long outputValue = 0L;
    boolean priceMissing = false;
    boolean outputPriced = false;
    for (Map.Entry<CommodityId, Long> output : industry.recipe().outputPerUnit().entrySet()) {
      long perScale = output.getValue() == null ? 0L : output.getValue();
      if (perScale <= 0L) {
        continue;
      }
      if (market == null || !market.hasPrice(output.getKey())) {
        priceMissing = true;
        continue;
      }
      outputPriced = true;
      long price = market.bidPriceOf(output.getKey());
      if (price <= 0L) {
        continue; // 明确 0 价（免费）⇒ 该项价值 0；仍算"有价"
      }
      outputValue = addCapped(outputValue, multiplyCapped(perScale, price));
    }
    long inputCost = 0L;
    boolean inputPriceMissing = false;
    for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
      long perScale = input.getValue() == null ? 0L : input.getValue();
      if (perScale <= 0L) {
        continue;
      }
      if (market == null || !market.hasPrice(input.getKey())) {
        inputPriceMissing = true;
        continue;
      }
      long price = market.askPriceOf(input.getKey());
      if (price <= 0L) {
        continue; // 明确 0 价投入 ⇒ 免费
      }
      // perScale 是毫商品/规模，price 是毫钱/商品单位 ⇒ ÷1000 换毫钱（下取整）。
      inputCost = addCapped(inputCost, multiplyCapped(perScale, price) / 1000L);
    }
    long net = subtractCapped(outputValue, inputCost);
    long expectedLabor = laborPerUnit;
    long scaled = scaledNetPerLabor(net, expectedLabor);

    String fullReason = joinReasons(reason, priceMissing, inputPriceMissing, market);
    return new Offer(
        unit.id(),
        modeId,
        rankModeKey,
        maxAbsorbable,
        outputPriced,
        outputValue,
        inputCost,
        net,
        expectedLabor,
        scaled,
        fullReason);
  }

  /** 技术模板的只读劳动分量（公式只读这一项；投入/产出的遍历直接从 {@code industry.recipe()} 取，保序同源）。 */
  private static long laborPerUnitOf(Industry industry) {
    return industry.recipe().laborPerUnit();
  }

  private static String joinReasons(
      String base, boolean priceMissing, boolean inputPriceMissing, Market market) {
    List<String> parts = new ArrayList<>();
    if (!base.isBlank()) {
      parts.add(base);
    }
    if (market == null) {
      parts.add("NO_MARKET");
    }
    if (priceMissing) {
      parts.add("NO_PRICE");
    }
    if (inputPriceMissing) {
      parts.add("NO_INPUT_PRICE");
    }
    return String.join("|", parts);
  }

  // ── ② 排队填充 ──────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>把一份家户的候选 offers 按利润率排队填满预算</b>（纯函数；不动状态）。
   *
   * @param household 家户
   * @param budgetMilli 本 tick 时间预算（毫小时）
   * @param preservedLaborMilli 不参与排队、原样保留的既有配额（如 laborPerUnit = 0 的非劳动活动；占用预算）
   * @param offers 候选读数（本方法内部复制后排序，不改入参序）
   * @return 排队结果（逐条给出 granted / IDLE 理由）
   */
  public static Plan plan(
      HouseholdId household, long budgetMilli, long preservedLaborMilli, List<Offer> offers) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(offers, "offers");
    long budget = Math.max(0L, budgetMilli);
    long preserved = Math.min(Math.max(0L, preservedLaborMilli), budget);
    long remaining = budget - preserved;
    List<Offer> sorted = new ArrayList<>(offers);
    sorted.sort(offerOrder());
    List<Decision> decisions = new ArrayList<>();
    int rank = 0;
    for (Offer offer : sorted) {
      rank++;
      String outcome;
      long grant = 0L;
      if (offer.maxAbsorbableLaborMilli() <= 0L) {
        outcome = "IDLE_MAX_ABSORBABLE_ZERO";
      } else if (offer.netPerLaborScaled() < MIN_NET_PER_LABOR_SCALED) {
        outcome = "IDLE_EXPECTED_NON_POSITIVE";
      } else if (remaining <= 0L) {
        outcome = "IDLE_BUDGET_EXHAUSTED";
      } else {
        grant = Math.min(remaining, offer.maxAbsorbableLaborMilli());
        remaining -= grant;
        outcome = "GRANTED";
      }
      decisions.add(new Decision(rank, offer, grant, outcome));
    }
    long allocated = budget - remaining;
    return new Plan(household, budget, preserved, allocated, remaining, decisions);
  }

  /**
   * ★★ <b>并列 tie-break</b>：排序键（预期单位劳动净收益）降序 → mode 键字典序 → unit id 字典序（计划 §13.5）。
   */
  public static Comparator<Offer> offerOrder() {
    return Comparator.comparingLong(Offer::netPerLaborScaled)
        .reversed()
        .thenComparing(Offer::rankModeKey)
        .thenComparing(offer -> offer.unitId().value());
  }

  // ── ③ 最大可吸收劳动的唯一算式 ─────────────────────────────────────────────────────────────

  /**
   * ★★ <b>一个 unit 本 tick 最多能吸收多少家户劳动</b>（毫小时；= 旧 {@code EconomySettlement.laborNeedOf}
   * 的同一算式，唯一拼写点移到这里）。
   *
   * <pre>
   * capacityScale = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition)
   * inputScale    = min over j: ⌊unit.cycleInputUsedMilli[j] ÷ recipe.inputPerUnit[j]⌋   （无投入表 ⇒ 不约束）
   * scale         = min(capacityScale, inputScale)
   * maxAbsorbable = scale × recipe.laborPerUnit                       （laborPerUnit ≤ 0 ⇒ 0，不施加约束）
   * </pre>
   *
   * <p>★ 与收获日的规模算式是同一个前缀：这里**刻意不含劳动那一路** —— 劳动正是要求解的量。
   */
  public static long maxAbsorbableLaborMilli(
      ProductionProcess unit,
      Industry industry,
      SettlementIndex index,
      OperatorCondition condition) {
    long laborPerUnit = industry.recipe().laborPerUnit();
    if (laborPerUnit <= 0L) {
      return 0L;
    }
    long scale = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    for (Map.Entry<CommodityId, Long> entry : industry.recipe().inputPerUnit().entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      long drawn = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / entry.getValue());
    }
    if (scale <= 0L) {
      return 0L;
    }
    return multiplyCapped(scale, laborPerUnit);
  }

  // ── 整数运算小工具（防溢出；一切整数、无浮点）────────────────────────────────────────────

  /** 预期净收益 ÷ 预期劳动的定点化：{@code ⌊net × 1_000_000 ÷ max(1, labor)⌋}；溢出按符号夹到 long 边界。 */
  static long scaledNetPerLabor(long net, long laborPerUnit) {
    long denominator = Math.max(1L, laborPerUnit);
    try {
      return Math.multiplyExact(net, PER_LABOR_SCALE) / denominator;
    } catch (ArithmeticException overflow) {
      return net >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  private static long multiplyCapped(long left, long right) {
    if (left <= 0L || right <= 0L) {
      return 0L;
    }
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long addCapped(long left, long right) {
    if (right <= 0L) {
      return left;
    }
    if (left > Long.MAX_VALUE - right) {
      return Long.MAX_VALUE;
    }
    return left + right;
  }

  private static long subtractCapped(long left, long right) {
    if (right <= 0L) {
      return left;
    }
    if (left < Long.MIN_VALUE + right) {
      return Long.MIN_VALUE;
    }
    return left - right;
  }

  /** 一份"没有候选"的空计划（读口/日志用它表示"这家户本 tick 没有可排的生产方式"）。 */
  public static Plan emptyPlan(HouseholdId household, long budgetMilli) {
    return new Plan(household, Math.max(0L, budgetMilli), 0L, 0L, Math.max(0L, budgetMilli), List.of());
  }

  /** 只读：把 offer 的预期读数摆成一行可读文本（日志/报告共用；不参与任何状态）。 */
  public static String describe(Decision decision) {
    Offer offer = decision.offer();
    return "unit="
        + offer.unitId().value()
        + " mode="
        + offer.modeId().map(ProductionModeId::value).orElse("-")
        + " key="
        + offer.netPerLaborScaled()
        + " net="
        + offer.expectedNetMilli()
        + " labor="
        + offer.expectedLaborMilli()
        + " maxAbsorb="
        + offer.maxAbsorbableLaborMilli()
        + " granted="
        + decision.grantedLaborMilli()
        + " outcome="
        + decision.outcome()
        + (offer.reason().isBlank() ? "" : " reason=" + offer.reason());
  }

  /** 只读：计划的可读一行（预算/已分配/空缺）。 */
  public static String describe(Plan plan) {
    StringBuilder text = new StringBuilder();
    text.append("household=")
        .append(plan.household().value())
        .append(" budget=")
        .append(plan.budgetMilli())
        .append(" preserved=")
        .append(plan.preservedLaborMilli())
        .append(" allocated=")
        .append(plan.allocatedLaborMilli())
        .append(" idle=")
        .append(plan.idleLaborMilli());
    for (Decision decision : plan.decisions()) {
      text.append(" | ").append(describe(decision));
    }
    return text.toString();
  }

  /** 保序不可变：决策清单（读口投影用；不做第二份排序）。 */
  public static Map<ProductionUnitId, Decision> decisionsByUnit(Plan plan) {
    Map<ProductionUnitId, Decision> byUnit = new LinkedHashMap<>();
    for (Decision decision : plan.decisions()) {
      byUnit.put(decision.offer().unitId(), decision);
    }
    return Collections.unmodifiableMap(byUnit);
  }

  /**
   * ★ 只读：这个 offer 是否属于"被保留、不排队"的活动：
   *
   * <ul>
   *   <li>{@code LABOR_FREE}：laborPerUnit ≤ 0 —— 不需要家户时间，不进利润队列；
   *   <li>{@code NO_RECIPE_OUTPUT}：承运/贸易 —— 收益走 MarketSettlement 的运费路径，本簿估值看不见；
   *   <li>{@code outputPriced == false}：产出一个价都没有（该格无市场 / 该商品未定价）—— 本簿没有估值信息，
   *       <b>不拿"估不出价"当"不赚钱"</b>，既有配额原样保留。
   * </ul>
   *
   * <p>三类都原样保留既有配额；只有"确实定过价、且预期 ≤ 0"的活动才会被排队判为空缺（IDLE_EXPECTED_NON_POSITIVE）。
   */
  public static boolean isPreservedByQueue(Offer offer) {
    return offer.reason().startsWith("LABOR_FREE")
        || offer.reason().startsWith("NO_RECIPE_OUTPUT")
        || !offer.outputPriced();
  }
}
