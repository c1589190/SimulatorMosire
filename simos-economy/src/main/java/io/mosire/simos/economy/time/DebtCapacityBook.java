package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtCapacity.NextRoundNecessaryInputSource;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>E4b：从状态现值算 {@link DebtCapacity} 的唯一算法</b>（理想架构 §5.2／§5.3；2026-09-29）。
 *
 * <p>★★ <b>本类只读、只算</b>：不写任何库存／货币／债务，不产生转移，不改调用方传进来的任何 Map。日结算的借粮路径与 app 读口（{@code ApiViews}）都调本类 ⇒
 * “F／headroom 的算法只有一处”。
 *
 * <pre>
 * 配方口径（该家户有可解析的 operator unit 时）：
 *   nextRoundNecessaryInput = Σ_{(industry, operator=本户)} ⌊plannedCapacityScale × industry.inputPerUnit[GRAIN]⌋
 *   其中 plannedCapacityScale = ProductionUnitBook.plannedCapacityScaleOf(unit, industry, AssetShare 总账, OperatorCondition)
 * 代理口径（该家户名下没有任何可解析 operator unit 时）：
 *   nextRoundNecessaryInput = max(0, 本周期 consumed[grain] − cycleNaturalNeedMilli)
 * </pre>
 *
 * <p>★★ <b>为什么“下一轮投入”只能尽量从配方算</b>：真档的 unit 有两种 operator（家户 actor 与聚合主体 ESTATE／WORKSHOP）。本类只认 {@code
 * unit.operator() == HouseholdActors.of(household)} 的那些 unit；<b>认不到就退回代理口径</b>，并把口径来源原样写进 {@link
 * NextRoundNecessaryInputSource}，读口按枚举名发出 —— 绝不让代理数被读成真实下一轮投入。
 *
 * <p>★★ <b>库存从调用方传入</b>：{@link #capacitiesForState} 的 {@code grainStockMilliOf} 由 app 读 actor
 * 切片后给出； economy 自身不认识 {@code ActorData}（铁律 3）。库存读不到（函数返回 {@link OptionalLong#empty()}）⇒ {@link
 * DebtCapacity#pledgeableGrainSurplusValue()} 为空，headroom 也空（读口标具名缺失），不用 0 冒充。
 *
 * <p>★★ <b>既有债务怎么算</b>（D-030 §3.4）：
 *
 * <ul>
 *   <li>价格源由调用方给的 {@link DebtUnitValueLookup} 决定：缺省重载传 {@link #NO_UNIT_PRICES} 时，粮 unit 按
 *       identity 原值计入（旧 E4b 行为逐值保留），其它 unit 一律无法折价；
 *   <li>生产路径改传 {@link #marketPriceLookup(Map)} ⇒ 用该家户所在市场的价格表按 §3.4 公式折算成粮等值：
 *       {@code Commodity(c): principal × price(c) / price(grain)}；{@code Money(cur): principal × 1000 /
 *       price(grain)}（只在 {@code cur == market.numeraire} 时；市场缺该单位价 ⇒ 不可定价）；
 *   <li><b>可定价债务全部进 {@link DebtCapacity#existingDebt()}</b>；仍有任一不可定价债务 ⇒ 该户新信用额度按 0
 *       fail-closed，见 {@link #newCreditHeadroomMilli(DebtCapacity)}；不可定价的本金原始和与条数仍从 {@link
 *       DebtCapacity#unpricedDebtAmount()}／{@link DebtCapacity#unpricedDebtCount()} 读出。
 * </ul>
 *
 * <p>★ <b>确定性</b>：输出按输入 {@code rows} 的迭代序（调用方拿 {@code HouseholdId} 键取值，顺序不参与身份）；内部索引 只做“一次派生、逐户
 * O(1) 查表”，不做任何依赖哈希序的聚合。
 */
public final class DebtCapacityBook {

  private DebtCapacityBook() {}

  /**
   * ★★ <b>债务标的价格钩子</b>：返回该债本金折成粮的等值（毫粮）；返回负数 = <b>没有有效价格</b> ⇒ 记 unpriced。
   *
   * <p>生产路径不再传本常量：它只保留给旧读口/旧测试（粮 identity、其它 unit 不折的旧口径）。生产借贷路径用
   * {@link #marketPriceLookup(Map)} 按市场价目表现算。
   */
  @FunctionalInterface
  public interface DebtUnitValueLookup {

    /**
     * @return 本金折粮等值（毫粮），或负数 = 无有效价格（不折、计 unpriced）
     */
    long grainValueMilliOf(DebtContract debt);
  }

  /**
   * ★ 未落地价格源的旧口径：任何 unit 都返回负数（不折）。{@link #capacities} 对 {@code null} 或本常量保留旧
   * “粮 identity、其它 unit 不折”的行为；直接调用本 lambda 则按契约恒返回负数。
   */
  public static final DebtUnitValueLookup NO_UNIT_PRICES = debt -> -1L;

  /**
   * ★★ <b>从市场价目表构造的债务折粮 lookup</b>——D-030 §3.4 的<b>唯一拼写点</b>。
   *
   * <p>价格来源 = {@code marketByHousehold} 给该债务人家户的市场；公式复用 {@link
   * DebtValuation#grainEquivalentMilli(long, DebtUnit, Market)}（毫粮等值），因此与偿还侧的任意 medium 折算是同一套价目表
   * 口径。家户没有市场、没有 grain 价、没有该单位价 ⇒ 返回负数（该债计 unpriced；该户新信用 fail-closed）。
   *
   * @param marketByHousehold 家户 → 其所在市场区默认价目表（缺键或值 null = 没有价目表）
   */
  public static DebtUnitValueLookup marketPriceLookup(Map<HouseholdId, Market> marketByHousehold) {
    Objects.requireNonNull(marketByHousehold, "marketByHousehold 不得为 null");
    return debt -> {
      Objects.requireNonNull(debt, "debt 不得为 null");
      Market market = marketByHousehold.get(debt.debtor());
      return DebtValuation.grainEquivalentMilli(debt.principal(), debt.unit(), market).orElse(-1L);
    };
  }

  /**
   * ★★ <b>新信用 headroom 的生产口径</b>（D-030 §3.4 的 fail-closed 点）：
   *
   * <pre>
   * unpricedDebtCount > 0 ⇒ 0（任一不可定价债务 ⇒ 该户不借，不超借）
   * 否则                    ⇒ capacity.headroom()（读不到 ⇒ 0，与旧调用点“缺键/读不到 = 0”一致）
   * </pre>
   *
   * <p>★ {@link DebtCapacity} 的字段与公式未变：headroom 仍只减 “可定价债务合计”；本方法把“仍有不可定价债务” 这一条
   * fail-closed 规则收在唯一拼写点，借贷调用方必须用它而不是直接读 {@code capacity.headroom()}。
   */
  public static long newCreditHeadroomMilli(DebtCapacity capacity) {
    if (capacity == null || capacity.unpricedDebtCount() > 0) {
      return 0L;
    }
    return capacity.headroom().orElse(0L);
  }

  /**
   * ★★ <b>读口便捷入口</b>：从 {@link EconomyData} 的当前值算全部家户的容量（时点口径；E4b 的库存由 app 传入）。
   *
   * <p>窗口：{@code afterAllocationGrainIncome}／{@code consumed}／{@code taxPaid} 直接读 {@code
   * FlowRow}（本周期已实现／本周期实缴），{@code basicRation} 读 {@code ClassRow.cycleNaturalNeedMilli}
   * （本周期累计）；库存在函数被调用的那一刻读（时点）。
   */
  public static Map<HouseholdId, DebtCapacity> capacitiesForState(
      EconomyData data, Function<HouseholdId, OptionalLong> grainStockMilliOf) {
    return capacitiesForState(
        data,
        grainStockMilliOf,
        DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED,
        NO_UNIT_PRICES);
  }

  /** ★★ 读口/审计入口（显式给政策钩子与债务价格钩子；见 {@link #NO_UNIT_PRICES}）。 */
  public static Map<HouseholdId, DebtCapacity> capacitiesForState(
      EconomyData data,
      Function<HouseholdId, OptionalLong> grainStockMilliOf,
      long pledgeableAssetPolicyValue,
      DebtUnitValueLookup debtUnitValueLookup) {
    Objects.requireNonNull(data, "data 不得为 null");
    return capacitiesForState(
        data,
        data.classes().keySet(),
        grainStockMilliOf,
        pledgeableAssetPolicyValue,
        debtUnitValueLookup);
  }

  /**
   * ★★ <b>只算指定家户</b>（读口用：{@code economyHex}／{@code economyOwnership} 一次只展示一格的几行， 不必为整张表建容量映射；算法仍是
   * {@link #capacities} 的唯一实现）。
   *
   * <p>缺行的键（状态不完整）跳过 —— 读口会在逐户读数里把它标成具名缺失，而不是在这里造一个 0。
   */
  public static Map<HouseholdId, DebtCapacity> capacitiesForState(
      EconomyData data,
      Iterable<HouseholdId> householdKeys,
      Function<HouseholdId, OptionalLong> grainStockMilliOf) {
    return capacitiesForState(
        data,
        householdKeys,
        grainStockMilliOf,
        DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED,
        NO_UNIT_PRICES);
  }

  /** ★★ 只算指定家户的完整入口（显式政策钩子与价格钩子；见类注的窗口说明）。 */
  public static Map<HouseholdId, DebtCapacity> capacitiesForState(
      EconomyData data,
      Iterable<HouseholdId> householdKeys,
      Function<HouseholdId, OptionalLong> grainStockMilliOf,
      long pledgeableAssetPolicyValue,
      DebtUnitValueLookup debtUnitValueLookup) {
    Objects.requireNonNull(data, "data 不得为 null");
    Objects.requireNonNull(householdKeys, "householdKeys 不得为 null");
    Map<HouseholdId, ClassRow> rows = new LinkedHashMap<>();
    Map<HouseholdId, Long> income = new LinkedHashMap<>();
    Map<HouseholdId, Long> consumed = new LinkedHashMap<>();
    Map<HouseholdId, Long> taxPaid = new LinkedHashMap<>();
    for (HouseholdId key : householdKeys) {
      ClassRow row = data.classes().get(key);
      if (row == null) {
        continue; // 状态不完整：逐户读数标具名缺失，不在这里造 0
      }
      rows.put(key, row);
      FlowRow flow = data.flows().get(key);
      // ★ 用 long 局部再装箱：避免 Map<..., Long> 的 getOrDefault 先拆箱、put
      // 立刻重装箱（BX_UNBOXING_IMMEDIATELY_REBOXED）。
      long grainIncome = 0L;
      long grainConsumed = 0L;
      long paidTax = 0L;
      if (flow != null) {
        grainIncome = flow.income().getOrDefault(EconomyCommodities.GRAIN, 0L);
        grainConsumed = flow.consumed().getOrDefault(EconomyCommodities.GRAIN, 0L);
        paidTax = flow.taxPaid();
      }
      income.put(key, grainIncome);
      consumed.put(key, grainConsumed);
      taxPaid.put(key, paidTax);
    }
    return capacities(
        rows,
        income,
        consumed,
        taxPaid,
        grainStockMilliOf,
        cycleDaysByHousehold(data),
        data.debtContracts(),
        data.units(),
        data.industries(),
        data.assetShares(),
        data.operatorConditions(),
        pledgeableAssetPolicyValue,
        debtUnitValueLookup);
  }

  /**
   * ★★ <b>唯一算法</b>：逐家户算 {@link DebtCapacity}。调用方负责把三个“流量”取成正确的窗口值：
   *
   * <ul>
   *   <li>{@code afterAllocationGrainIncome}：将写进终态 {@code FlowRow.income[grain]} 的本周期值（含当日）；没有 = 0；
   *   <li>{@code cycleToDateGrainConsumed}：同窗口的 {@code FlowRow.consumed[grain]}（只在代理口径里用）；
   *   <li>{@code taxPaidFromFlowRow}：同窗口的 {@code FlowRow.taxPaid}（当前生产路径恒 0；照实读）。
   * </ul>
   *
   * @param rows 家户行（按 {@code HouseholdId} 键；输出与它同键、同序）
   * @param afterAllocationGrainIncome 本周期已实现粮所得（毫粮；缺键 = 0）
   * @param cycleToDateGrainConsumed 本周期累计粮消费（毫粮；缺键 = 0；代理口径输入）
   * @param taxPaidFromFlowRow 本周期实缴税（毫粮；缺键 = 0）
   * @param grainStockMilliOf 粮库存（毫粮）；返回空 = 读不到（具名缺失，不用 0 冒充）
   * @param cycleDaysByHousehold 各家户的 {@code cycleDays}（本周期自需的输入；缺键 = 0，与旧口径同侧）
   * @param debts 债务合同表（只读）
   * @param units 生产单元表（只读；配方口径用）
   * @param industries 产业模板表（只读；配方口径用）
   * @param assetShares 实物资产总账（只读；规模由它派生）
   * @param operatorConditions 经营者状态（只读；计划规模系数用；缺键按 ACTIVE）
   * @param pledgeableAssetPolicyValue 可质押资产政策价值钩子（毫粮；E4b 恒 0）
   * @param debtUnitValueLookup 债务折粮钩子；旧读口用 {@link #NO_UNIT_PRICES}，生产借贷路径用 {@link
   *     #marketPriceLookup(Map)}
   */
  public static Map<HouseholdId, DebtCapacity> capacities(
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Long> afterAllocationGrainIncome,
      Map<HouseholdId, Long> cycleToDateGrainConsumed,
      Map<HouseholdId, Long> taxPaidFromFlowRow,
      Function<HouseholdId, OptionalLong> grainStockMilliOf,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      long pledgeableAssetPolicyValue,
      DebtUnitValueLookup debtUnitValueLookup) {
    Objects.requireNonNull(rows, "rows 不得为 null");
    Objects.requireNonNull(afterAllocationGrainIncome, "afterAllocationGrainIncome 不得为 null");
    Objects.requireNonNull(cycleToDateGrainConsumed, "cycleToDateGrainConsumed 不得为 null");
    Objects.requireNonNull(taxPaidFromFlowRow, "taxPaidFromFlowRow 不得为 null");
    Objects.requireNonNull(cycleDaysByHousehold, "cycleDaysByHousehold 不得为 null");
    Objects.requireNonNull(debts, "debts 不得为 null");
    Objects.requireNonNull(units, "units 不得为 null");
    Objects.requireNonNull(industries, "industries 不得为 null");
    Objects.requireNonNull(assetShares, "assetShares 不得为 null");
    Objects.requireNonNull(operatorConditions, "operatorConditions 不得为 null");

    // ★ 旧调用点传 null / NO_UNIT_PRICES 时保留旧 E4b 行为：粮 unit 按 identity 计入，其它 unit 不折；生产路径显式传
    //   marketPriceLookup（走 §3.4 公式，缺 grain 价/缺单位价一律 unpriced）。
    boolean legacyGrainIdentity =
        debtUnitValueLookup == null || debtUnitValueLookup == NO_UNIT_PRICES;

    // ★ 两个一次派生的索引：债务人 → 债务、operator → unit。逐户 O(债务 + unit) 查表，不做 O(户 × unit) 全扫。
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = DebtIndex.byDebtor(debts);
    Map<ActorRef, List<ProductionUnit>> unitsByOperator = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      unitsByOperator.computeIfAbsent(unit.operator(), ignored -> new ArrayList<>()).add(unit);
    }

    Map<HouseholdId, DebtCapacity> capacities = new LinkedHashMap<>();
    for (ClassRow row : rows.values()) {
      HouseholdId key = row.id();
      OptionalLong grainStock =
          grainStockMilliOf == null ? OptionalLong.empty() : grainStockMilliOf.apply(key);
      if (grainStock == null) {
        grainStock = OptionalLong.empty(); // 调用方的缺省只准表达“读不到”
      }
      long realizedGrainIncome = afterAllocationGrainIncome.getOrDefault(key, 0L);
      long consumedGrain = cycleToDateGrainConsumed.getOrDefault(key, 0L);
      long taxPaid = taxPaidFromFlowRow.getOrDefault(key, 0L);
      long cycleDays = cycleDaysByHousehold.getOrDefault(key, 0L);

      NextRoundNeed nextRound =
          nextRoundNecessaryInput(
              key,
              unitsByOperator,
              industries,
              assetShares,
              operatorConditions,
              consumedGrain,
              row.cycleNaturalNeedMilli());

      long existingDebt = 0L;
      long unpricedDebtAmount = 0L;
      int unpricedDebtCount = 0;
      for (DebtContractId debtId : debtsByDebtor.getOrDefault(key, List.of())) {
        DebtContract debt = debts.get(debtId);
        if (debt == null) {
          continue; // 防御性判空（债务表在本次调用内只读；缺条 = 坏状态 ⇒ 不猜、不计）
        }
        // ★ D-030 §3.4：市场 lookup 走统一价格钩子；旧 NO_UNIT_PRICES/null 走“粮 identity、其它 unit 不折”的旧口径
        //   （旧行为逐值保留）。不再读 terms.monetaryConversion（“可定价债务全部进入 existingDebt”）。
        long converted = -1L;
        if (legacyGrainIdentity) {
          if (debt.unit() instanceof DebtUnit.Commodity commodity
              && commodity.commodity().equals(EconomyCommodities.GRAIN)) {
            converted = debt.principal();
          }
        } else {
          converted = debtUnitValueLookup.grainValueMilliOf(debt);
        }
        if (converted >= 0L) {
          existingDebt = Math.addExact(existingDebt, converted);
        } else {
          unpricedDebtAmount = Math.addExact(unpricedDebtAmount, debt.principal());
          unpricedDebtCount++;
        }
      }

      // ★ 可自用余粮与旧放贷方的余粮**同一算式、同一保留额**（R3a 从 旧结算引擎（R3a 已删除） 原样搬来，见下面的
      //   {@link #lendableOf(ClassRow, long, long)}）；
      //   库存读不到 ⇒ 空（不是 0）。
      OptionalLong selfUsable =
          grainStock.isPresent()
              ? OptionalLong.of(lendableOf(row, grainStock.getAsLong(), cycleDays))
              : OptionalLong.empty();

      capacities.put(
          key,
          new DebtCapacity(
              realizedGrainIncome,
              row.cycleNaturalNeedMilli(),
              nextRound.input(),
              nextRound.source(),
              taxPaid,
              selfUsable,
              pledgeableAssetPolicyValue,
              existingDebt,
              unpricedDebtAmount,
              unpricedDebtCount));
    }
    return capacities;
  }

  /**
   * ★★ <b>下一轮必要粮投入</b>（两种口径恰选其一，来源随结果一起返回）。
   *
   * <pre>
   * 配方口径：遍历 unit.operator == HouseholdActors.of(household) 的 unit；
   *           同一 (industry, operator) 的多 unit 共享同一份可用资产（ProductionUnitBook 按 scope 聚合）
   *           ⇒ 该 scope 只计一次，不按 unit 条数倍增；
   *           每 scope 取 plannedCapacityScale × industry.inputPerUnit[GRAIN]。
   * 代理口径：上面一个“有产业模板且带配方”的 unit 都没有 ⇒ max(0, 本周期 consumed[grain] − cycleNaturalNeedMilli)。
   * </pre>
   *
   * <p>★ <b>只算粮那一维</b>：布／纤维／铁等非粮投入没有价格，折成粮就是编换算率（同 {@code FlowRow.netSurplus} 的立场）；
   * 它们在被扣掉/买到之前不计入“粮口径的下一轮必要投入”。粮那一维用 {@code inputPerUnit[GRAIN]}（如农业 = 种子）。 ★
   * 配方口径只回答“按当前资产/状态规模运行下一轮需要多少粮投入”，<b>不含</b>下一轮劳动可得性（劳动不耗粮；人手不足体现在产出规模， 不是这里的粮投入）。
   *
   * <p>★ 命中 unit 但产业模板缺席、或模板的 {@code cycleInputPerUnit} 为空（旧档/坏数据 ⇒ 配方数据不足）⇒ 该 unit 不算
   * “数据在”；若一个可用配方都没有，退回代理口径而不是谎报 0 下一轮投入。
   */
  private static NextRoundNeed nextRoundNecessaryInput(
      HouseholdId household,
      Map<ActorRef, List<ProductionUnit>> unitsByOperator,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      long consumedGrain,
      long cycleNaturalNeedMilli) {
    List<ProductionUnit> mine =
        unitsByOperator.getOrDefault(HouseholdActors.of(household), List.of());
    Set<IndustryId> countedIndustries = new LinkedHashSet<>();
    long recipeNeed = 0L;
    boolean anyResolved = false;
    boolean anyRecipeSeen = false;
    for (ProductionUnit unit : mine) {
      Industry industry = industries.get(unit.industry());
      if (industry == null) {
        continue; // 状态坏：不猜规模，也不把它算成“没有 unit”
      }
      anyResolved = true;
      if (!countedIndustries.add(unit.industry())) {
        continue; // 同一 (industry, operator) 的多个 unit 共享同一份资产 ⇒ 只计一次
      }
      Map<CommodityId, Long> recipeInputs = industry.inputPerUnit();
      if (!recipeInputs.isEmpty()) {
        anyRecipeSeen = true; // 这个产业的配方存在（哪怕粮那一维为 0，也是“数据在”）
      }
      long perScaleGrain = recipeInputs.getOrDefault(EconomyCommodities.GRAIN, 0L);
      if (perScaleGrain <= 0L) {
        continue; // 这个产业不耗粮（布/工具）：粮口径下需求为 0，不是“数据不足”
      }
      long scale =
          ProductionUnitBook.plannedCapacityScaleOf(
              unit, industry, assetShares, operatorConditions.get(unit.id()));
      if (scale <= 0L) {
        continue;
      }
      recipeNeed = Math.addExact(recipeNeed, Math.multiplyExact(scale, perScaleGrain));
    }
    if (anyResolved && anyRecipeSeen) {
      return new NextRoundNeed(recipeNeed, NextRoundNecessaryInputSource.RECIPE);
    }
    return new NextRoundNeed(
        Math.max(0L, consumedGrain - cycleNaturalNeedMilli),
        NextRoundNecessaryInputSource.NON_RATION_CONSUMED_PROXY);
  }

  /** 内部结果对：数值 + 口径，保证两者一起产生、一起落进 {@link DebtCapacity}。 */
  private record NextRoundNeed(long input, NextRoundNecessaryInputSource source) {}

  /**
   * ★★ <b>每条家户行的 {@code cycleDays}</b>（{@link #lendableOf} 的"本周期自需"要用它）：取**它供给的那些产业的最长周期**。
   *
   * <p>★ R3a：本方法从{@code 旧结算引擎（R3a 已删除）} 原样搬来（唯一调用方是 {@link #capacitiesForState} 这条只读派生）； 旧引擎删除后，
   * 它是读口按"同一份保留额"算可自用余粮的唯一来源。
   *
   * <p>★ <b>没有配额的家户</b>（供给集合为空）：退回**本格产业的最长周期**；连产业都没有的格 ⇒ 记 0（保留额 0）。
   */
  static Map<HouseholdId, Long> cycleDaysByHousehold(EconomyData data) {
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
    Map<HouseholdId, Set<ProductionUnitId>> unitsOfHouseholds = new LinkedHashMap<>();
    for (LaborAllocation allocation : data.allocations().values()) {
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (!data.units().containsKey(unitId)
          || !data.classes().containsKey(allocation.household())) {
        continue;
      }
      unitsOfHouseholds
          .computeIfAbsent(allocation.household(), ignored -> new LinkedHashSet<>())
          .add(unitId);
    }
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (HouseholdId key : data.classes().keySet()) {
      Set<ProductionUnitId> supplied = unitsOfHouseholds.getOrDefault(key, Set.of());
      long cycleDays = 0L;
      for (ProductionUnitId unitId : supplied) {
        ProductionUnit unit = data.units().get(unitId);
        Industry industry = unit == null ? null : data.industries().get(unit.industry());
        if (industry != null) {
          cycleDays = Math.max(cycleDays, industry.cycleDays());
        }
      }
      if (cycleDays == 0L) {
        ClassRow row = data.classes().get(key);
        if (row != null) {
          for (IndustryId industryId :
              industriesByHex.getOrDefault(
                  IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r()), List.of())) {
            Industry industry = data.industries().get(industryId);
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
   * ★★ <b>放贷行的可贷额（余粮）</b>：{@code reserve = 整周期口粮 × 1000‰ ÷ 1000；lendable = max(0, 库存 − reserve)}。
   *
   * <p>★ R3a：本方法从旧 {@code 旧结算引擎（R3a 已删除）.lendableOf} 原样搬来（算式与保留额一字不改）；它是 {@link
   * #capacitiesForState} 里"可自用余粮"那一栏的唯一实现，不新增第二处口径。
   */
  static long lendableOf(ClassRow lender, long stock, long cycleDays) {
    if (stock <= 0L) {
      return 0L;
    }
    long reserve =
        io.mosire.simos.util.economy.EconomyVocabulary.cumulativeRationMilli(
                lender.population(), cycleDays)
            * LENDER_SUBSISTENCE_RESERVE_PER_MILLE
            / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /** 旧 {@code 旧结算引擎（R3a 已删除）.LENDER_SUBSISTENCE_RESERVE_PER_MILLE} 的同值搬移（放贷方自留整周期口粮的千分比）。 */
  private static final int LENDER_SUBSISTENCE_RESERVE_PER_MILLE = 1000;
}
