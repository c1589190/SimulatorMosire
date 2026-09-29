package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtCapacity.NextRoundNecessaryInputSource;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
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
 * <p>★★ <b>既有债务怎么算</b>（E4b 只做粮信用线）：
 *
 * <ul>
 *   <li>同 unit（{@code DebtUnit.Commodity(GRAIN)}）= 粮 unit：本金进 {@link
 *       DebtCapacity#existingDebt()}，参与 headroom 减法；
 *   <li>货币／其它商品的债：<b>不硬折</b>。只有 terms 的 {@link MonetaryConversion} 明确允许折偿、且价格钩子给出有效粮等值时， 才折成粮加进
 *       {@code existingDebt}；否则进 {@code unpricedDebtAmount}／{@code unpricedDebtCount}。E4b 两侧调用点传
 *       {@link #NO_UNIT_PRICES}（价格源属 E5）⇒ 当前所有非粮债都落在 unpriced，规则本身已经接线、不是“以后再说”。
 * </ul>
 *
 * <p>★ <b>确定性</b>：输出按输入 {@code rows} 的迭代序（调用方拿 {@code HouseholdId} 键取值，顺序不参与身份）；内部索引 只做“一次派生、逐户
 * O(1) 查表”，不做任何依赖哈希序的聚合。
 */
public final class DebtCapacityBook {

  private DebtCapacityBook() {}

  /**
   * ★★ <b>债务标的价格钩子</b>：返回该债本金折成粮的等值（毫粮）；返回负数 = <b>没有有效价格／不允许折</b> ⇒ 记 unpriced。
   *
   * <p>E4b 尚无价格源，两个调用点都传 {@link #NO_UNIT_PRICES}；E5 的 {@code LiquidationPolicy}／价格源落地后实现本接口即可， 不需要改
   * {@link DebtCapacity} 的公式。
   */
  @FunctionalInterface
  public interface DebtUnitValueLookup {

    /**
     * @return 本金折粮等值（毫粮），或负数 = 无有效价格（不折、计 unpriced）
     */
    long grainValueMilliOf(DebtContract debt);
  }

  /** ★ 未落地价格源：任何非粮债都折不了（返回负数 ⇒ 全部计 unpriced，不硬折）。 */
  public static final DebtUnitValueLookup NO_UNIT_PRICES = debt -> -1L;

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
      income.put(key, flow == null ? 0L : flow.income().getOrDefault(EconomySettlement.GRAIN, 0L));
      consumed.put(
          key, flow == null ? 0L : flow.consumed().getOrDefault(EconomySettlement.GRAIN, 0L));
      taxPaid.put(key, flow == null ? 0L : flow.taxPaid());
    }
    return capacities(
        rows,
        income,
        consumed,
        taxPaid,
        grainStockMilliOf,
        EconomySettlement.cycleDaysByHousehold(data),
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
   * @param debtUnitValueLookup 非粮债折粮钩子（E4b 用 {@link #NO_UNIT_PRICES}）
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
        if (debt.unit() instanceof DebtUnit.Commodity commodity
            && commodity.commodity().equals(EconomySettlement.GRAIN)) {
          existingDebt = Math.addExact(existingDebt, debt.principal());
          continue;
        }
        // ★ 非粮 unit：只认“terms 明确允许折偿 + 价格钩子给出有效价”这一条；否则不硬折、计 unpriced。
        long converted = -1L;
        if (debtUnitValueLookup != null
            && debt.terms().monetaryConversion() != MonetaryConversion.NOT_ALLOWED) {
          converted = debtUnitValueLookup.grainValueMilliOf(debt);
        }
        if (converted >= 0L) {
          existingDebt = Math.addExact(existingDebt, converted);
        } else {
          unpricedDebtAmount = Math.addExact(unpricedDebtAmount, debt.principal());
          unpricedDebtCount++;
        }
      }

      // ★ 可自用余粮与放贷方的余粮**同一算式、同一保留额**（EconomySettlement.lendableOf 的唯一实现）；
      //   库存读不到 ⇒ 空（不是 0）。
      OptionalLong selfUsable =
          grainStock.isPresent()
              ? OptionalLong.of(
                  EconomySettlement.lendableOf(row, grainStock.getAsLong(), cycleDays))
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
      long perScaleGrain = recipeInputs.getOrDefault(EconomySettlement.GRAIN, 0L);
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
}
