package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
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
 * 既有配额、再按家户预算等比例封顶（释放出来的时间留在空缺，不重排）—— 没有"这家户这几小时为什么给这个 mode"的 利润判据。本类把 §13.5 的两步显式化：
 *
 * <pre>
 * ① 每个生产单元先算"本 tick 最大可吸收劳动"
 *      usableScale = min( 产能/资产那一路（ProductionProcessBook.plannedCapacityScaleOf）,
 *                        每项投入那一路（⌊本周期已扣到的投入 ÷ 单位规模需求⌋） )
 *      maxAbsorbableLaborMilli = usableScale × perScaleLabor      // perScaleLabor = probe 的每 1 基准规模劳动
 *    ★ 投入那一路为 0（没有、也拿不到）⇒ maxAbsorbableLaborMilli = 0 ⇒ 对应劳动就是空缺（不凭空开工）。
 * ② 排序键 = 预期净收益 ÷ 预期消耗的家户劳动小时（不是单位产出利润率）
 *      outputValueMilli = Σ_j perScaleOutput_j（毫商品/规模） × bidPrice_j（毫钱/商品单位） ÷ 1000
 *      inputCostMilli   = Σ_j ⌊perScaleInput_j（毫商品/规模） × askPrice_j（毫钱/商品单位） ÷ 1000⌋
 *      netMilli         = outputValueMilli − inputCostMilli
 *      key              = ⌊netMilli × 1_000_000 ÷ max(1, perScaleLabor)⌋   （百万分之一毫钱 / 单位劳动）
 * ③ 从 key 最高的开始填，填满它的最大可吸收量，再填下一个，直到家户预算用完或没有可吸收的生产方式
 *      并列按 modeId（无 mode 的旧 unit 按 modeKey）/ unit id 字典序升序
 *      不再设经济盈利预过滤：零/负收益 offer 保留在队列中，由带符号 yield 排序并具名读出
 * </pre>
 *
 * <p>★★★ <b>D-032 §15.12 A（R1）：「物理量」一律取自 {@link ProductionRuntime#probe}，economy 只保留估值</b> ——
 * {@code perScaleOutput/perScaleInput/perScaleLabor} 三件来自一次<b>参考基准规模</b>（{@link #REFERENCE_SCALE} =
 * 1000）的试算 （{@code occupancy_k = capacityPerUnit_k × REF}、{@code inputs_c = inputPerUnit_c × REF}、
 * {@code laborMilli = laborPerUnit × REF × cycleDays}），结果再除回"每 1 基准规模"：
 *
 * <pre>
 *   perScaleLabor  = probe.laborRequiredMilli() ÷ REF
 *   perScaleOutput = probe.expectedOutput_c   ÷ REF
 *   perScaleInput  = probe.inputRequired_c    ÷ REF
 * </pre>
 *
 * <p>★ <b>为什么用参考规模而不是"按真实规模试算一次"</b>：整数下取整在真实规模很小时会把"每单位"读成 0（失真可达 100%）， REF 把它压到 ≤ 1/1000 规模；且
 * E（熟练度倍率）只把<b>产出</b>通道乘一次、投入/劳动按基准规模计（A′ 批口径）⇒ "每 1 基准规模的劳动/投入"与参考规模无关，产出则随 E 变化 ——
 * 这正是"熟练度进选择链"（R1）要的形状。
 *
 * <p>★ <b>E = 1000（无熟练度加成）时逐值中性</b>：{@code perScaleLabor = laborPerUnit}、{@code perScaleOutput =
 * outputPerUnit × 1000}、{@code perScaleInput = inputPerUnit} ⇒ 改前的 {@code Offer} 每个字段逐值相同（推导见
 * {@code impl-fix/f2-batch-impl-ledger.md}）。★ 余数（{@code efficiencyOf} 的三段 carry）在 REF 下不进规模：
 *
 * <pre>
 *   carry₁ &lt; cycleDays        ⇒ avgLabor      = REF × laborPerUnit（不进位）
 *   carry₂ &lt; laborPerUnit     ⇒ baseLaborScale = REF（不进位）
 *   carry₃ &lt; 1000             ⇒ finalScale    = REF（不进位）
 * </pre>
 *
 * <p>★ <b>不可行/退化</b>：probe 判不可行 ⇒ {@code maxAbsorbable = 0} 且理由 {@code PROBE_REJECTED:<reason>}
 * （{@code locked}/{@code condition-unsatisfied:*} 因此不参与利润队列）；可行但规模 0 ⇒ {@code PROBE_SCALE_ZERO}。 既有
 * {@code LABOR_FREE}/{@code NO_RECIPE_OUTPUT}/{@code NO_CAPACITY}/{@code NO_INPUT*} 具名分支序保留。
 *
 * <p>★★ <b>首版的具名近似（如实记，不静默）</b>：
 *
 * <ol>
 *   <li><b>净收益只减投入成本</b>：租金/工资等 relation 分账（{@code ProductionSettlement} 的那一套）不在本类重算 —— 跨主体分账需要每条
 *       relation 的规则与付款能力，属 {@code ExpectedProfitBook.prospect} 的职责；本类保证排序键 与"劳动约束下每小时产出"一致，分账差异留待把
 *       prospect 接进排队时收口；
 *   <li><b>需求封顶不建模</b>：产出按本格 bid 价全额估值，不计"卖不掉"的部分（对应 {@code ExpectedProfitBook} 的 {@code
 *       sellable}/{@code DEMAND_CAPPED}）；
 *   <li><b>未定价产出/投入</b>按 0 计并标具名 {@code NO_PRICE}/{@code NO_INPUT_PRICE}（与 {@code MarketTable} 的
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
   * ★★ <b>D-032 §15.12 A：喂给 {@link ProductionRuntime#probe} 的参考基准规模</b>（合成占用/投入/劳动都按它放大， probe
   * 结果再除回"每 1 基准规模"）。
   *
   * <p>★ 为什么是 1000：整数下取整在真实规模上会把"每单位"读成 0（小规模世界失真可达 100%），REF 把失真压到 ≤ 1/1000 规模；同时它让三段精度余数（{@code
   * cycleDays}/{@code laborPerUnit}/1000）在除法里都吃不满一位 ⇒ <b>E = 1000 时 换回值逐值等于配方值</b>（中性推导见 {@code
   * f2-batch-impl-ledger.md}）。
   */
  public static final long REFERENCE_SCALE = 1_000L;

  /** 一条"这个 unit 本 tick 最多能吸收多少家户劳动、每单位劳动的预期净收益是多少"的读数。 */
  public record Offer(
      ProductionUnitId unitId,
      ProductionRoleId role,
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
      Objects.requireNonNull(role, "Offer.role 不得为 null（D2 §5.6：候选按名册角色分流）");
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

    /** 本 offer 进利润队列吗（D3 §4.3：只判物理可吸收量，不保留经济盈利门槛）。 */
    public boolean queueable() {
      return maxAbsorbableLaborMilli > 0L;
    }
  }

  /** 排队结果里的一条决定（含"为什么没给/给了多少"，读口与日志共用同一份）。 */
  public record Decision(int rank, Offer offer, long grantedLaborMilli, String outcome) {

    public Decision {
      Objects.requireNonNull(offer, "Decision.offer 不得为 null");
      Objects.requireNonNull(outcome, "Decision.outcome 不得为 null");
      if (grantedLaborMilli < 0L) {
        throw new IllegalArgumentException("Decision.grantedLaborMilli 不得为负: " + grantedLaborMilli);
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
      boolean idleChosen,
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

    /** 兼容 W2b 之前的五参构造：idleChosen 缺省 false。 */
    public Plan(
        HouseholdId household,
        long budgetMilli,
        long preservedLaborMilli,
        long allocatedLaborMilli,
        long idleLaborMilli,
        List<Decision> decisions) {
      this(
          household,
          budgetMilli,
          preservedLaborMilli,
          allocatedLaborMilli,
          idleLaborMilli,
          false,
          decisions);
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

    /** ★ D-032 C2：给出某个 {@code (unit, role)} 的排队决定（同 unit 多角色分行）。 */
    public Optional<Decision> decisionOf(ProductionUnitId unitId, ProductionRoleId role) {
      for (Decision decision : decisions) {
        if (decision.offer().unitId().equals(unitId) && decision.offer().role().equals(role)) {
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
   * <p>★★ <b>§15.12 A（R1）：物理量（产出/投入/劳动）全部取自 production 的试算</b> —— 三件按 {@link #REFERENCE_SCALE} 的一次
   * probe 换回"每 1 基准规模"（公式与中性推导见类注）；本方法只做估价、排序键与"最大可吸收量"。
   *
   * @param unit 生产单元（投入那一路读它的 {@code cycleInputUsedMilli}；probe 的余数取它的 {@code efficiencyOf}）
   * @param industry 技术模板（配方与周期；probe 用它的 id 查 production 的当刻定义）
   * @param market 该家户居住格的价表；可为 null（= 无价，产出按 0 计并标 NO_PRICE）
   * @param condition 经营者状态（产能那一路的计划系数）；可为 null（按 ACTIVE）
   * @param index 当天的派生索引（可用资产/产能只查表）
   * @param modeId 该 unit 所属生产方式（按组织表推；旧 unit 可为空）
   * @param rankModeKey 并列时的 mode 键（modeId 值；旧 unit 用 modeKey）
   * @param household ★ §15.12 A 新增：这家户（熟练度/经验由它决定；本 tick 冻结条件优先，见 {@link ProductionProbeInputs}）
   * @param session 结算会话（只读：probe 的运行时 + 本生产日实扣的周期投入）
   */
  public static Offer offer(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      MarketTable market,
      OperatorCondition condition,
      SettlementIndex index,
      Optional<ProductionModeId> modeId,
      String rankModeKey,
      HouseholdId household,
      ProductionRoleId role,
      EconomySession session) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role（D2 §5.6：候选按名册角色分流）");
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(rankModeKey, "rankModeKey");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(session, "session");

    // ★★ R2：共享资产 scope 的 unit 在预计阶段就具名不可行，绝不进入劳动分配/利润队列。
    Optional<String> assetRejection = index.assetRejectionOf(unit.id());
    if (assetRejection.isPresent()) {
      return new Offer(
          unit.id(),
          role,
          modeId,
          rankModeKey,
          0L,
          false,
          0L,
          0L,
          0L,
          0L,
          0L,
          assetRejection.get());
    }

    long capacityScale =
        ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    long inputScale = Long.MAX_VALUE;
    CommodityId bindingInput = null;
    long bindingDrawn = 0L;
    long bindingPerScale = 0L;
    // ★★★ B8（缺陷 DEF-T2b-2 的同源一处）：投入读数取"本生产日实扣量"的权威视图
    //   （见 {@link #cycleInputUsedMilliOf}）—— 直接读实例的 {@code cycleInputUsedMilli()} 会读到
    //   "本 tick 还没扣料"的旧值 ⇒ {@code inputScale = 0} ⇒ 这条 offer 被判 {@code NO_INPUT} ⇒
    //   该 unit 的既有配额在写回阶段被整条删掉。
    Map<CommodityId, Long> drawnInputs = cycleInputUsedMilliOf(session, unit);
    for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
      long perScale = input.getValue() == null ? 0L : input.getValue();
      if (perScale <= 0L) {
        continue;
      }
      long drawn = drawnInputs.getOrDefault(input.getKey(), 0L);
      long scale = drawn / perScale;
      if (scale < inputScale) {
        inputScale = scale;
        bindingInput = input.getKey();
        bindingDrawn = drawn;
        bindingPerScale = perScale;
      }
    }
    long absorbableScale = Math.min(capacityScale, inputScale);
    long laborPerUnit = laborPerUnitOf(industry);

    // ★★★ §15.12 A（R1）：一次参考基准规模的试算 —— 产出/投入/劳动的**唯一**物理来源。
    //   ★ 真实规模不参与这次试算：REF 让整数下取整的失真 ≤ 1/1000 规模，且 E=1000 时换回值逐值等于配方值。
    ProductionProbeResult probe = probeAtReferenceScale(unit, industry, household, index, session);
    long perScaleLabor = Math.max(0L, probe.laborRequiredMilli() / REFERENCE_SCALE);

    String reason = "";
    long maxAbsorbable;
    if (!probe.feasible()) {
      // ★ R1：未解锁 / 可用性条件不满足 / 引用悬空 ⇒ 这条活动不参与利润队列（既有配额按 preserved 处理）。
      //   理由词表 = PROBE_REJECTED:<production 的具名理由>（locked / condition-unsatisfied:* / labor-short
      // …）。
      maxAbsorbable = 0L;
      reason = "PROBE_REJECTED:" + probe.reason();
    } else if (!probe.producible()) {
      // 可行、只是连一个规模单位都做不了（如方案把零熟练度的倍率配成 0）：没有可吸收劳动。
      maxAbsorbable = 0L;
      reason = "PROBE_SCALE_ZERO";
    } else if (laborPerUnit <= 0L) {
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
      // ★ §15.12 A：劳动的量纲从配方移到 probe 的"每 1 基准规模劳动"（E=1000 时逐值等于 laborPerUnit）。
      maxAbsorbable = multiplyCapped(Math.max(0L, absorbableScale), perScaleLabor);
      if (maxAbsorbable <= 0L) {
        reason = "NO_CAPACITY";
      }
    }

    // ② 每 1 基准规模的预期价值（probe 换回值；量纲见类注）。
    long outputValue = 0L;
    boolean priceMissing = false;
    boolean outputPriced = false;
    for (Map.Entry<CommodityId, Long> output : probe.expectedOutput().entrySet()) {
      long perScale = output.getValue() / REFERENCE_SCALE;
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
      // perScale 是毫商品/规模，price 是毫钱/商品单位 ⇒ ÷1000 换毫钱（下取整）。
      outputValue =
          addCapped(
              outputValue,
              multiplyCapped(perScale, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    long inputCost = 0L;
    boolean inputPriceMissing = false;
    for (Map.Entry<CommodityId, Long> input : probe.inputRequired().entrySet()) {
      long perScale = input.getValue() / REFERENCE_SCALE;
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
      inputCost =
          addCapped(
              inputCost,
              multiplyCapped(perScale, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    long net = subtractCapped(outputValue, inputCost);
    long expectedLabor = perScaleLabor;
    long scaled = scaledNetPerLabor(net, expectedLabor);

    String fullReason = joinReasons(reason, priceMissing, inputPriceMissing, market);
    return new Offer(
        unit.id(),
        role,
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

  /**
   * ★★★ <b>§15.12 A：这个 unit 在参考基准规模 {@link #REFERENCE_SCALE} 上的一次 probe</b>（无副作用）。
   *
   * <pre>
   * occupancy_k = capacityPerUnit_k × REF          （合成占用：让资产通道恰好支持 REF 规模）
   * inputs_c    = inputPerUnit_c    × REF          （合成投入：让原料通道恰好支持 REF 规模）
   * laborMilli  = laborPerUnit × REF × cycleDays  （本周期累计口径；probe 内部 ÷cycleDays 折回日均）
   * mastery/experience = 本 tick 冻结条件优先（R5），否则运行时读口（见 ProductionProbeInputs）
   * carry       = runtime.efficiencyOf(unit.id())  （与 execute 同一份精度余数：I6 同源）
   * attributes  = EMPTY                            （正式日循环的属性通路本就未接）
   * </pre>
   *
   * <p>★ <b>为什么占用/投入是合成值而不是真实份额</b>：这里求的是"每 1 基准规模的物理需求"，规模本身由调用方按 {@code usableScale}
   * 决定；用真实占用去试算只会把规模夹到真实值、拿不到"每单位"读数（除以 0 风险也在这里）。 真实占用仍进 {@code
   * HouseholdProductionConditions.assets}（家户手上资产，probe 当前不读它，见 §15.12 A 的额度口径）。
   *
   * <p>★ <b>溢出</b>：三个乘积用 {@link Math#multiplyExact}（定义里的量级是常数级，溢出即配置故障，响亮失败而不是静默夹值）。
   */
  private static ProductionProbeResult probeAtReferenceScale(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      HouseholdId household,
      SettlementIndex index,
      EconomySession session) {
    ProductionRuntime runtime = session.productionRuntime();
    Map<AssetKind, Long> occupancy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> capacity : industry.capacityPerUnit().entrySet()) {
      occupancy.put(capacity.getKey(), Math.multiplyExact(capacity.getValue(), REFERENCE_SCALE));
    }
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
      long perScale = input.getValue() == null ? 0L : input.getValue();
      if (perScale <= 0L) {
        continue;
      }
      inputs.put(input.getKey(), Math.multiplyExact(perScale, REFERENCE_SCALE));
    }
    long laborMilli =
        Math.multiplyExact(
            Math.multiplyExact(laborPerUnitOf(industry), REFERENCE_SCALE), industry.cycleDays());
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            household,
            laborMilli,
            ProductionProbeInputs.positiveAssets(index.usableAssetsOf(unit)),
            inputs,
            ProductionProbeInputs.masteryOf(runtime, household, industry),
            ProductionProbeInputs.experienceOf(runtime, household, industry));
    return runtime.probe(
        new ProductionProbeRequest(
            industry.id(),
            conditions,
            occupancy,
            runtime.efficiencyOf(unit.id()),
            ExternalAttributeFacts.EMPTY));
  }

  /** 技术模板的只读劳动分量（公式只读这一项；投入/产出的遍历直接从 {@code industry.recipe()} 取，保序同源）。 */
  private static long laborPerUnitOf(IndustryDefinitionView industry) {
    return industry.recipe().laborPerUnit();
  }

  private static String joinReasons(
      String base, boolean priceMissing, boolean inputPriceMissing, MarketTable market) {
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
   * <p>★★ <b>C7 的分工</b>：本方法只看见聚合的 {@code preservedLaborMilli}，看不见承诺的 {@code kind}—— <b>GOV_SERVICE
   * 的整额保护与 PRODUCTION 的比例缩在 {@code LaborQueueSettlement} 先做完</b>（先整额保 GOV_SERVICE，只对 PRODUCTION
   * 缩），传到这里的 {@code preservedLaborMilli} 必须已经 ≤ {@code budgetMilli}。 本方法**不静默按预算缩 preserved**：超预算 ⇒
   * 具名 {@link IllegalArgumentException}（调用方不许把"超预算" 当成"静默缩"）。GOV_SERVICE 越界另有具名契约 ERROR +
   * fail-closed（在 {@code LaborQueueSettlement}）。
   *
   * @param household 家户
   * @param budgetMilli 本 tick 时间预算（毫小时）
   * @param preservedLaborMilli 不参与排队、原样保留的既有配额（GOV_SERVICE 整额 + 不排队的 PRODUCTION；必须 ≤ 预算）
   * @param offers 候选读数（本方法内部复制后排序，不改入参序）
   * @return 排队结果（逐条给出 granted / IDLE 理由）
   * @throws IllegalArgumentException {@code preservedLaborMilli > budgetMilli}（调用方必须先完成保护/缩，不得静默缩）
   */
  public static Plan plan(
      HouseholdId household, long budgetMilli, long preservedLaborMilli, List<Offer> offers) {
    return plan(household, budgetMilli, preservedLaborMilli, offers, IdleOption.notEligible());
  }

  /**
   * ★★★ <b>D3 §4.4：把 IdleOption 作为独立控制候选接入唯一排序器</b>。
   *
   * <p>idle 不进入 {@code offers}、不经过 {@code queueable()/maxAbsorbable}，只在生产 offer 排序之后由策略显式决定胜负：
   * 默认策略下资格成立即放弃可自由分配劳动并记 {@code IDLE_CHOSEN}；BEST_EFFORT 下正收益先做，只有零/负收益工作可由 idle 击败。
   */
  public static Plan plan(
      HouseholdId household,
      long budgetMilli,
      long preservedLaborMilli,
      List<Offer> offers,
      IdleOption idleOption) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(offers, "offers");
    Objects.requireNonNull(idleOption, "idleOption");
    long budget = Math.max(0L, budgetMilli);
    long preserved = Math.max(0L, preservedLaborMilli);
    if (preserved > budget) {
      throw new IllegalArgumentException(
          "LaborQueueBook.plan 的 preservedLaborMilli 不得超过 budgetMilli（不静默缩；"
              + "GOV_SERVICE 整额保护与 PRODUCTION 比例缩由调用方先完成）: preserved="
              + preserved
              + " budget="
              + budget);
    }
    long remaining = budget - preserved;
    List<Offer> sorted = ActivitySelector.rank(new ArrayList<>(offers), LaborQueueBook::rankKeyOf);
    List<Decision> decisions = new ArrayList<>();
    boolean idleChosen = false;
    int rank = 0;

    if (idleOption.idleEligible()
        && idleOption.policy() == SurvivalIdlePolicy.PREFER_IDLE_WHEN_SUBSISTENCE_UNMET) {
      idleChosen = remaining > 0L;
      for (Offer offer : sorted) {
        rank++;
        String outcome = nonGrantableOutcome(offer);
        if (outcome == null && offer.netPerLaborScaled() > 0L) {
          outcome = "IDLE_FOREGONE_POSITIVE";
        } else if (outcome == null) {
          outcome = "IDLE_CHOSEN";
        }
        decisions.add(new Decision(rank, offer, 0L, outcome));
      }
      return new Plan(household, budget, preserved, preserved, remaining, idleChosen, decisions);
    }

    if (idleOption.idleEligible() && idleOption.policy() == SurvivalIdlePolicy.BEST_EFFORT_WORK) {
      // 第一遍只给正收益且物理可吸收的 offer；有正收益被授予时 idle 不再胜出，剩余劳动记 UNALLOCATED_LABOR。
      boolean grantedPositive = false;
      Map<Offer, Long> grants = new LinkedHashMap<>();
      for (Offer offer : sorted) {
        if (nonGrantableOutcome(offer) != null || offer.netPerLaborScaled() <= 0L) {
          continue;
        }
        if (remaining <= 0L) {
          break;
        }
        long grant = Math.min(remaining, offer.maxAbsorbableLaborMilli());
        if (grant > 0L) {
          grants.put(offer, grant);
          remaining -= grant;
          grantedPositive = true;
        }
      }
      idleChosen = !grantedPositive && remaining > 0L;
      rank = 0;
      for (Offer offer : sorted) {
        rank++;
        String outcome = nonGrantableOutcome(offer);
        long grant = grants.getOrDefault(offer, 0L);
        if (outcome == null) {
          if (grant > 0L) {
            outcome = "GRANTED";
          } else if (offer.netPerLaborScaled() <= 0L) {
            outcome = idleChosen ? "IDLE_CHOSEN" : "UNALLOCATED_LABOR";
          } else {
            outcome = "UNALLOCATED_LABOR";
          }
        }
        decisions.add(new Decision(rank, offer, grant, outcome));
      }
      long allocated = budget - remaining;
      return new Plan(household, budget, preserved, allocated, remaining, idleChosen, decisions);
    }

    // idleEligible=false：不构造可胜出的 idle；零/负收益工作也可按唯一排序器被选择，并具名读数。
    for (Offer offer : sorted) {
      rank++;
      String outcome;
      long grant = 0L;
      String nonGrantable = nonGrantableOutcome(offer);
      if (nonGrantable != null) {
        outcome = nonGrantable;
      } else if (remaining <= 0L) {
        outcome = "UNALLOCATED_LABOR";
      } else {
        grant = Math.min(remaining, offer.maxAbsorbableLaborMilli());
        remaining -= grant;
        outcome =
            offer.netPerLaborScaled() > 0L
                ? "GRANTED"
                : offer.netPerLaborScaled() == 0L ? "GRANTED_ZERO" : "GRANTED_NEGATIVE";
      }
      decisions.add(new Decision(rank, offer, grant, outcome));
    }
    long allocated = budget - remaining;
    return new Plan(household, budget, preserved, allocated, remaining, false, decisions);
  }

  /** 物理/资格不可授予的稳定读数；可进入策略比较时返回 null。 */
  private static String nonGrantableOutcome(Offer offer) {
    if (offer.maxAbsorbableLaborMilli() <= 0L) {
      return offer.reason().startsWith(AssetBudgetBook.SHARED_SCOPE_REJECTION)
          ? "REJECTED:" + offer.reason()
          : "IDLE_MAX_ABSORBABLE_ZERO";
    }
    return null;
  }

  /**
   * ★★ <b>并列 tie-break</b>：排序键（预期单位劳动净收益）降序 → mode 键字典序 → unit id 字典序（计划 §13.5）。
   *
   * <p>★★ <b>2026-10-08（阶段 1 统一活动选择器）</b>：本比较器<b>不再是第二个排序口径</b> —— 它只把生产 offer 投影成 {@link
   * ActivitySelector.RankKey}（{@code netPerLaborScaled / rankModeKey / unitId}）后<b>委托</b>给 {@link
   * ActivitySelector#compareKeys}。套利活动（{@link TradeArbitrageActivity}）走同一个比较式 ⇒ "生产与套利共用一个排序器"（设计书
   * §4.3.4）在结构上成立，而不是靠两处各写一遍。
   *
   * <p>★ 排序键的<b>刻度</b>是 {@link ActivitySelector#PER_LABOR_SCALE}（与 {@link #PER_LABOR_SCALE} 同值）：
   * 两把尺不同，"最划算的先做"就会变成"谁的刻度大谁先做"。
   */
  public static Comparator<Offer> offerOrder() {
    return (left, right) -> ActivitySelector.compareKeys(rankKeyOf(left), rankKeyOf(right));
  }

  /** 把一条生产 offer 投影成唯一排序器的键（唯一投影点；套利活动自带自己的键）。 */
  static ActivitySelector.RankKey rankKeyOf(Offer offer) {
    String activityId = "unit:" + offer.unitId().value() + "/role:" + offer.role().value();
    String tieId = offer.unitId().value() + "/" + offer.role().value();
    return new ActivitySelector.RankKey(offer.netPerLaborScaled(), activityId, tieId);
  }

  // ── ③ 最大可吸收劳动的唯一算式 ─────────────────────────────────────────────────────────────

  /**
   * ★★ <b>一个 unit 本 tick 最多能吸收多少家户劳动</b>（毫小时；= 旧 {@code EconomySettlement.laborNeedOf}
   * 的同一算式，唯一拼写点移到这里）。
   *
   * <pre>
   * capacityScale = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition)
   * inputScale    = min over j: ⌊cycleInputUsedMilliOf(state, unit)[j] ÷ recipe.inputPerUnit[j]⌋（无投入表 ⇒ 不约束）
   * scale         = min(capacityScale, inputScale)
   * maxAbsorbable = scale × recipe.laborPerUnit                       （laborPerUnit ≤ 0 ⇒ 0，不施加约束）
   * </pre>
   *
   * <p>★★★ <b>B8（缺陷 DEF-T2b-2 的同源一处）：投入那一份读数必须取"本生产日实扣量"的权威视图</b>， 即 {@link
   * #cycleInputUsedMilliOf(EconomySession, ProductionProcess)} —— **不得**直接读 {@code
   * unit.cycleInputUsedMilli()}。
   *
   * <p>★ <b>为什么</b>：现扣周期投入（{@code EconomySettlement#drawCycleInputs}）落在 economy 的账户会话上，而实例的 {@code
   * cycleInputUsedMilli} 要等本 tick 的 {@code ProductionRuntime#executeDay} 才被写 —— 本类却跑在它**之前** ⇒
   * 直接读实例 = 读到"本 tick 还没扣料"的旧值 ⇒ 投入那一路恒 0 ⇒ {@code maxAbsorbable = 0} ⇒ 该 unit 的既有劳动配额
   * 被当"一点也用不上"整条删掉 ⇒ 到了执行段没有劳动贡献 ⇒ 规模 0、毛产 0（"声明了周期投入的生产"整类玩法不可用）。
   *
   * <p>★ 与收获日的规模算式是同一个前缀：这里**刻意不含劳动那一路** —— 劳动正是要求解的量。
   *
   * <p>★ 本重载 = "投入读数由调用方按当刻权威给"（{@code EconomySettlement#reallocateLabor} 走的正是它 —— 那里 分区 worker 的
   * {@code units} 副本的周期投入已在协调器侧被镜像成**本生产日实扣量**，见 {@code
   * EconomySettlement#reallocateLaborPartitioned}），故**不**再自己解析会话。
   */
  public static long maxAbsorbableLaborMilli(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return maxAbsorbableLaborMilli(unit, industry, index, condition, null);
  }

  /**
   * ★★ 同 {@link #maxAbsorbableLaborMilli(ProductionProcess, IndustryDefinitionView,
   * SettlementIndex, OperatorCondition)}，但<b>自己</b>从会话解析"本生产日实扣的周期投入"（{@code session == null} ⇒ 用
   * {@code unit} 自己带进来的那一份）。
   *
   * <p>★ 为什么保留这条：{@code LaborQueueSettlement} 拿到的是**权威实例表里的那一份** unit（实例的周期投入要等本 tick 的 {@code
   * executeDay} 才更新）⇒ 它必须显式解析会话里的实扣量（见 {@link #cycleInputUsedMilliOf}）。
   */
  public static long maxAbsorbableLaborMilli(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition,
      EconomySession session) {
    // ★★ R2：共享 scope 的 unit 不可吸收劳动（预计/排队同一份资产预算）。
    if (index.assetRejectionOf(unit.id()).isPresent()) {
      return 0L;
    }
    long laborPerUnit = industry.recipe().laborPerUnit();
    if (laborPerUnit <= 0L) {
      return 0L;
    }
    long scale = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    Map<CommodityId, Long> inputs =
        session == null ? unit.cycleInputUsedMilli() : cycleInputUsedMilliOf(session, unit);
    for (Map.Entry<CommodityId, Long> entry : industry.recipe().inputPerUnit().entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      long drawn = inputs.getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / entry.getValue());
    }
    if (scale <= 0L) {
      return 0L;
    }
    return multiplyCapped(scale, laborPerUnit);
  }

  /**
   * ★★★ <b>B8：这个 unit 本生产日的周期投入读数 —— 唯一权威视图</b>。
   *
   * <p>口径（两档，都是"本生产日实扣"的意思，**不是**第二份可写权威）：
   *
   * <ol>
   *   <li><b>本 tick 已经现扣过</b>（{@code EconomySession#drawnProductionInputsOf} 记过它）⇒ 读**本日实扣量** —— 与
   *       {@code ProductionRuntime#executeDay} 的 {@code finalInputsMilli} 是**同一份**读数（economy
   *       侧"最终投入" 的落点，见 {@code EconomySession#drawnProductionInputs}）；它可能比实例里的周期累计**少**（今天扣得少）也可能
   *       多（今天扣到了新料，而实例的累计要等 {@code executeDay} 才更新）⇒ 必须取这一份。
   *   <li><b>本 tick 还没现扣过它</b> ⇒ 读实例自己的 {@code cycleInputUsedMilli()}（未开启新生产日时的既有语义；
   *       手搭夹具/无现扣阶段的世界逐值不变）。这条**不是**"放宽"：那一档里实例的值本来就是本周期唯一的读数。
   * </ol>
   *
   * <p>★ 为什么用"记过没记过"而不是 {@code containsKey}：{@code EconomySession#recordDrawnProductionInput} 对空表是
   * <b>移除</b>键 ⇒ "记过但一根没扣到"与"没记过"在会话里同形。故本方法只认"扣到了料"那一档，其余退回实例读数 —— 这正好是既有语义（实例值为空 ⇒ 两档同值）。
   */
  public static Map<CommodityId, Long> cycleInputUsedMilliOf(
      EconomySession session, ProductionProcess unit) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(unit, "unit");
    Map<CommodityId, Long> drawn = session.drawnProductionInputsOf(unit.id());
    return drawn.isEmpty() ? unit.cycleInputUsedMilli() : drawn;
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
    return new Plan(
        household, Math.max(0L, budgetMilli), 0L, 0L, Math.max(0L, budgetMilli), List.of());
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
        .append(plan.idleLaborMilli())
        .append(" idleChosen=")
        .append(plan.idleChosen());
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
   * <p>三类都原样保留既有配额；零/负收益不再被本判据预过滤，而是保留在队列中由 idle 策略显式比较。
   *
   * <p>★★ <b>C7 的边界</b>：本判据只看 unit 的 offer（是否缺价格/配方/劳动），**看不见承诺的 {@code kind}**。 {@code
   * GOV_SERVICE} 承诺"不进队列、不可缩"的保护在 {@code LaborQueueSettlement} 按 {@code kind} 直接判定 （带 GOV_SERVICE
   * 承诺的 activity 根本不进候选 / 不计入比例缩），不经过本方法。
   */
  public static boolean isPreservedByQueue(Offer offer) {
    // ★★ R2：共享 scope 的具名拒绝**不是**“估不出价 ⇒ 原样保留”；它必须进队列并以 maxAbsorbable=0
    //   清掉既有劳动配额，最后在执行段再次 fail-closed。
    if (offer.reason().startsWith(AssetBudgetBook.SHARED_SCOPE_REJECTION)) {
      return false;
    }
    return offer.reason().startsWith("LABOR_FREE")
        || offer.reason().startsWith("NO_RECIPE_OUTPUT")
        || !offer.outputPriced();
  }
}
