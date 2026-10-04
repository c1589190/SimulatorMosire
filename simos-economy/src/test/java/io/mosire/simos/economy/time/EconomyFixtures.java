package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>本包用例的推进写法</b>（R4 的会话形态；S1 后账户收敛为 {@link AccountSession}）。
 *
 * <p>本类只做两件事：① 把旧口径的"家户账工作副本"（键 = 视图 {@link CohortKey}）一次性载入 {@link AccountSession} 并在推进后写回；② 按
 * {@link EconomyDayStepper} 逐日推进。它<b>不是</b>生产 API，也<b>不是</b>第二套结算路径—— 日循环仍然只由 {@link
 * EconomyDayStepper} 跑。
 *
 * <p>★ <b>身份口径</b>：本包旧用例的常量仍是视图 {@link CohortKey}；测试世界用 {@link HouseholdId#ofLegacy(CohortKey)}
 * 给出稳定身份，actor 则由 {@link HouseholdActors#of(HouseholdId)} 拼。 这样旧用例的"视图 ↔ 账"叙述不变，而状态表的键已是 S1 的
 * {@link HouseholdId}。
 */
final class EconomyFixtures {

  private EconomyFixtures() {}

  /** 旧视图 → 测试世界的稳定家户身份（本包旧用例的唯一转换点）。 */
  static HouseholdId hh(CohortKey view) {
    return HouseholdIds.ofLegacy(view);
  }

  /** 稳定身份 → 旧视图（只服务本包旧用例的读回）。 */
  static CohortKey view(HouseholdId id) {
    return HouseholdIds.legacyView(id).orElseThrow(() -> new IllegalArgumentException("不是本夹具的旧档身份: " + id));
  }

  /**
   * 从 {@code fromTick + 1} 逐日推到 {@code toTick}，交出终态。
   *
   * <p>★ 家户账工作副本按视图键传入/写回（旧用例形状）；推进内部载入 {@link AccountSession}，日循环结束后
   * 把每本家户账的最终余额写回同一张表。多次调用之间因此可以继续用同一份副本。
   */
  static EconomyData advance(
      EconomyData base, Map<CohortKey, Map<CommodityId, Long>> goods, long fromTick, long toTick) {
    return advance(base, goods, fromTick, toTick, EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /** 同 {@link #advance(EconomyData, Map, long, long)}，但**致死率可注入**。 */
  static EconomyData advance(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> goods,
      long fromTick,
      long toTick,
      int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(goods, "goods");
    if (base.meta().isEmpty()) {
      return base; // 未激活：不做任何公式（§6.6）
    }
    AccountSession accounts = accountSession(base, goods);
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            base,
            accounts,
            MarketTopology.singleHex(base.markets()),
            EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
            famineMortalityPerMille);
    try {
      for (long day = fromTick + 1L; day <= toTick; day++) {
        stepper.step(day);
      }
      return stepper.finish();
    } finally {
      copyBack(base, accounts, goods);
      stepper.close();
    }
  }

  /** 推进器（测试直接拿当天 ledger 时用；账户会话已按本夹具口径载入）。 */
  static EconomyDayStepper stepper(EconomyData base, Map<CohortKey, Map<CommodityId, Long>> goods) {
    return new EconomyDayStepper(base, accountSession(base, goods));
  }

  /**
   * 按 {@link EconomyData.classes} 的视图把家户账载入账户会话；经营主体按 {@link EconomyData.units} 登记。
   *
   * <p>★ 经营者账必须登记：产出先计提进经营者账，关系实付再从它账上转给家户。少了经营者账， 转移会变成"收方凭空多出、付方没扣"。
   */
  static AccountSession accountSession(
      EconomyData base, Map<CohortKey, Map<CommodityId, Long>> goods) {
    AccountSession accounts = AccountSession.empty();
    for (ClassRow row : base.classes().values()) {
      CohortKey key = goodsKeyFor(goods, row);
      accounts.registerHousehold(
          row.id(),
          HouseholdActors.of(row.id()),
          row.view().hex(),
          goods.getOrDefault(key, Map.of()),
          Map.of(),
          Map.of(),
          Map.of());
    }
    Set<ActorRef> registeredOperators = new LinkedHashSet<>();
    for (ProductionUnit unit : base.units().values()) {
      ActorRef operator = unit.operator();
      if (!registeredOperators.add(operator)) {
        continue;
      }
      if (accounts.actorKeyOrNull(operator) != null) {
        continue; // 这个 actor 已有家户账（家户自营），不再重复登记经营者账。
      }
      accounts.registerOperator(
          operator, hexOfIndustry(unit.industry()), Map.of(), Map.of(), Map.of(), Map.of());
    }
    return accounts;
  }

  /** 把账户会话里的家户商品余额写回旧形状的工作副本（只写家户；经营者账不进本夹具）。 */
  private static void copyBack(
      EconomyData base, AccountSession accounts, Map<CohortKey, Map<CommodityId, Long>> goods) {
    for (ClassRow row : base.classes().values()) {
      Map<CommodityId, Long> balance = accounts.householdGoods().get(row.id());
      goods.put(goodsKeyFor(goods, row), balance == null ? Map.of() : new LinkedHashMap<>(balance));
    }
  }

  /**
   * 旧形状工作副本里属于这一行的稳定键：**按 {@code hh(key) == row.id()} 反查**，不按可变的 {@code row.view()}。
   *
   * <p>★ 家户阶层在周期关账时会被重分类（view 变），若按 view 写回会留下旧键、下一轮又读错一本账。
   */
  private static CohortKey goodsKeyFor(Map<CohortKey, Map<CommodityId, Long>> goods, ClassRow row) {
    for (CohortKey key : goods.keySet()) {
      if (hh(key).equals(row.id())) {
        return key;
      }
    }
    return row.view();
  }

  /** 产业 id 里的格键（与结算同源）。 */
  private static HexCoord hexOfIndustry(IndustryId industry) {
    return EconomySettlement.hexOfIndustry(industry);
  }

  /** 某家户在某商品上的余额（读口；没有这个键 ⇒ 0）。 */
  static long stockOf(
      Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key, CommodityId commodity) {
    return goods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 某家户的粮余额（{@link #stockOf} 的粮特化）。 */
  static long grainOf(Map<CohortKey, Map<CommodityId, Long>> goods, CohortKey key) {
    return stockOf(goods, key, new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID));
  }

  /** 给某个家户在某商品上放一笔余额（{@code amount <= 0} ⇒ **不落键**，保持"空商品表"的纯形态）。 */
  static void hold(
      Map<CohortKey, Map<CommodityId, Long>> goods,
      CohortKey key,
      CommodityId commodity,
      long amount) {
    LinkedHashMap<CommodityId, Long> inner = new LinkedHashMap<>(goods.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      inner.remove(commodity);
    } else {
      inner.put(commodity, amount);
    }
    goods.put(key, inner);
  }

  /** ★ 空的家户账工作副本（键 = 视图；在推进前由 {@link #accountSession} 载入）。 */
  static LinkedHashMap<CohortKey, Map<CommodityId, Long>> householdGoods() {
    return new LinkedHashMap<>();
  }

  /** 一份夹具 = 经济状态 + 它的家户账工作副本。 */
  record World(EconomyData data, Map<CohortKey, Map<CommodityId, Long>> goods) {}

  /**
   * ★ <b>旧测试的形状桥</b>：老顺序 17 参 Industry（progress/capacity 在模板参数之前）→ 当前旧档兼容 17 参。
   *
   * <p>本类只服务尚未迁移到新模板形状的旧用例；新用例请直接走 12 参模板构造器并显式给 {@code units}/{@code assetShares}。
   */
  static Industry industry(
      IndustryId id,
      String name,
      io.mosire.simos.economy.api.id.RegimeId regime,
      long cycleDays,
      long progressDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Long> capacity,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation,
      long cycleLaborMilli,
      Map<CommodityId, Long> cycleInputUsedMilli,
      ActorRef operator) {
    return new Industry(
        id,
        name,
        regime,
        cycleDays,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        operator,
        progressDays,
        capacity,
        cycleLaborMilli,
        cycleInputUsedMilli);
  }

  /** ★ 旧 9 参 ClassRow（无稳定 id）→ 当前 10 参；身份取 {@code HouseholdIds.ofLegacy(view)}。 */
  static ClassRow classRow(
      CohortKey view,
      long population,
      long laborMilli,
      int participationPerMille,
      long money,
      List<DebtContractId> debts,
      Map<CommodityId, Long> naturalNeeds,
      Map<CommodityId, Long> effectiveDemand,
      long cycleNaturalNeedMilli) {
    return new ClassRow(
        hh(view),
        view,
        population,
        laborMilli,
        participationPerMille,
        money,
        debts,
        naturalNeeds,
        effectiveDemand,
        cycleNaturalNeedMilli);
  }

  /** ★ 旧 5 参 ProductionRelation（activity = 产业 id）→ 当前 unit 键。 */
  static ProductionRelation relation(
      IndustryId activity,
      ActorRef operator,
      Recipient inputSupplier,
      List<CompensationRule> rules,
      ActorRef residualOwner) {
    ProductionUnitId unit = ProductionUnitId.idOf(activity, operator);
    return new ProductionRelation(unit, operator, inputSupplier, rules, residualOwner);
  }

  /**
   * ★ <b>旧 10 参 EconomyData</b>（R2 之前形状）→ 当前 29 组件；classes/flows 的视图键转稳定身份，relations 的产业键转 unit
   * 键。新增组件全部留空（走 {@link EconomyData#empty()} + {@code withX}，避免 record arity 漂移）。债务参数按 E4a 起的新形状传入。
   */
  static EconomyData data(
      Optional<EconomyMeta> meta,
      Map<IndustryId, Industry> industries,
      Map<CohortKey, ClassRow> classesByView,
      Map<DebtContractId, DebtContract> debtContracts,
      Map<CohortKey, FlowRow> flowsByView,
      Map<PeopleLotId, LaborSupply> laborSupply,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<?, ProductionRelation> relationsByIndustry,
      Map<HexCoord, Market> markets,
      Map<ShipmentId, ShipmentBatch> shipments) {
    LinkedHashMap<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : classesByView.entrySet()) {
      ClassRow row = entry.getValue();
      ClassRow fixed =
          row.id().equals(hh(entry.getKey()))
              ? row
              : new ClassRow(
                  hh(entry.getKey()),
                  entry.getKey(),
                  row.population(),
                  row.laborMilli(),
                  row.participationPerMille(),
                  row.money(),
                  row.debts(),
                  row.naturalNeeds(),
                  row.effectiveDemand(),
                  row.cycleNaturalNeedMilli());
      classes.put(hh(entry.getKey()), fixed);
    }
    LinkedHashMap<HouseholdId, FlowRow> flows = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, FlowRow> entry : flowsByView.entrySet()) {
      FlowRow flow = entry.getValue();
      HouseholdId id = hh(entry.getKey());
      flows.put(
          id,
          new FlowRow(
              id,
              flow.income(),
              flow.consumed(),
              flow.taxPaid(),
              flow.interestDue(),
              flow.newBorrowing(),
              flow.repaid(),
              flow.netSurplus(),
              flow.unmetNeed(),
              flow.deaths(),
              flow.births(),
              flow.repaidMoney(),
              flow.capitalizedArrears()));
    }
    LinkedHashMap<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Map.Entry<?, ProductionRelation> entry : relationsByIndustry.entrySet()) {
      ProductionRelation relation = entry.getValue();
      Object rawKey = entry.getKey();
      ActorRef operator = relation.operator();
      ProductionUnitId unit;
      if (rawKey instanceof ProductionUnitId productionUnitId) {
        unit = productionUnitId;
      } else if (rawKey instanceof IndustryId industryId) {
        Industry industry = industries.get(industryId);
        if (operator == null && industry != null) {
          operator =
              industry.operator() != null
                  ? industry.operator()
                  : RegimeOperators.defaultOperator(industry.regime(), industryId);
        }
        unit = ProductionUnitId.idOf(industryId, operator);
      } else {
        throw new IllegalArgumentException(
            "夹具 relations 的键只能是 IndustryId（旧）或 ProductionUnitId（新）: " + rawKey);
      }
      relations.put(
          unit,
          new ProductionRelation(
              unit,
              operator,
              relation.inputSupplier(),
              relation.rules(),
              relation.residualOwner(),
              relation.laborSource()));
    }
    return EconomyData.empty()
        .withMeta(meta)
        .withIndustries(industries)
        .withClasses(classes)
        .withDebtContracts(debtContracts)
        .withFlows(flows)
        .withLaborSupply(laborSupply)
        .withAllocations(allocations)
        .withRelations(relations)
        .withMarkets(markets)
        .withShipments(shipments);
  }

  /**
   * ★ R3B.2 起生产状态挂在 {@link ProductionUnit} 上：按产业汇总 unit 的进度。
   *
   * <p>旧用例夹具每产业一个 unit；这里用汇总形态，未来多 unit 夹具也能用（进度求和仍守恒）。
   */
  static long progressDaysOf(EconomyData data, IndustryId industry) {
    long total = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.progressDays();
      }
    }
    return total;
  }

  /** 旧视图 → 当前 {@link EconomyData.classes} 表里的行（找不到 ⇒ null，与 {@code Map.get} 同口径）。 */
  static ClassRow classOf(EconomyData data, CohortKey view) {
    return data.classes().get(hh(view));
  }

  /** 旧视图 → 当前 {@link EconomyData.flows} 表里的流水（找不到 ⇒ null，与 {@code Map.get} 同口径）。 */
  static FlowRow flowOf(EconomyData data, CohortKey view) {
    return data.flows().get(hh(view));
  }

  /** 按产业汇总 unit 的本周期累计劳动（千分劳动·日）。 */
  static long cycleLaborOf(EconomyData data, IndustryId industry) {
    long total = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.cycleLaborMilli();
      }
    }
    return total;
  }

  /** 按产业汇总 unit 的本周期实扣投入（毫单位，按商品）。 */
  static long cycleInputUsedOf(EconomyData data, IndustryId industry, CommodityId commodity) {
    long total = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.cycleInputUsedMilli().getOrDefault(commodity, 0L);
      }
    }
    return total;
  }

  /** 按产业取第一个 unit（旧用例每产业一个 unit；无 unit ⇒ 抛，不静默）。 */
  static ProductionUnit unitOf(EconomyData data, IndustryId industry) {
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        return unit;
      }
    }
    throw new IllegalStateException("本产业没有 ProductionUnit（夹具形状不对）: " + industry);
  }

  /** 按产业取 unit 的经营者（旧用例每产业一个 unit）。 */
  static ActorRef operatorOf(EconomyData data, IndustryId industry) {
    return unitOf(data, industry).operator();
  }

  /**
   * ★★ <b>本包夹具的共同约定</b>：给每个产业一条「净产按劳动全给该格贫农 cohort」的关系。
   *
   * <p>键 = {@link ProductionUnitId}（R3B.2 起关系结算挂在 unit 上）；本助手按产业旧档的 operator （缺省按制度推导）拼出与 {@code
   * EconomyData} 归一化一致的 unit 身份。
   */
  static Map<ProductionUnitId, ProductionRelation> laborShareToPeasant(
      Map<IndustryId, Industry> industries) {
    Map<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      IndustryId id = entry.getKey();
      Industry industry = entry.getValue();
      ActorRef operator =
          industry.operator() != null
              ? industry.operator()
              : RegimeOperators.defaultOperator(industry.regime(), id);
      ProductionUnitId activity = ProductionUnitId.idOf(id, operator);
      CompensationRule rule =
          new CompensationRule(
              RuleType.OUTPUT_SHARE,
              new Recipient.ToCohort(
                  new CohortKey(
                      hexOfIndustry(id), ResidenceKind.RURAL, new SocialClassId(PEASANT_SLOT))),
              Pool.NET_AFTER_INPUTS,
              Weight.LABOR_AMOUNT,
              1000,
              0L,
              Optional.of(new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID)),
              Optional.empty(),
              10);
      relations.put(
          activity, new ProductionRelation(activity, operator, null, List.of(rule), operator));
    }
    return relations;
  }

  /** 受方槽位：贫农（与 {@code EconomyVocabulary} 的阶层词表同源）。 */
  private static final String PEASANT_SLOT = "poor_peasant";
}
