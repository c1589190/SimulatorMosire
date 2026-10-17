package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public final class EconomyFixtures {

  private EconomyFixtures() {}

  /** 旧视图 → 测试世界的稳定家户身份（本包旧用例的唯一转换点）。 */
  static HouseholdId hh(CohortKey view) {
    return HouseholdIds.ofLegacy(view);
  }

  /** 稳定身份 → 旧视图（只服务本包旧用例的读回）。 */
  static CohortKey view(HouseholdId id) {
    return HouseholdIds.legacyView(id)
        .orElseThrow(() -> new IllegalArgumentException("不是本夹具的旧档身份: " + id));
  }

  /**
   * ★★ P1：把 economy 状态与 production 定义拼成只读 {@link EconomyDayView}（定义权威在 production）。
   *
   * <p>这是既有用例从"定义住在 EconomyData"迁到"显式注入 ProductionDefinitionLookup"的唯一桥：状态表仍读 economy， 只有 {@code
   * definitions()}（及其派生的 {@code industries()/modes()}）来自传入的 production 定义。
   */
  public static EconomyDayView dayView(EconomyData base, ProductionDefinitionLookup definitions) {
    return new WorkingDayView(new EconomyStateBuilder(base, definitions, runtimeOf(definitions)));
  }

  /**
   * ★★ D-032 B3：{@code EconomyStateBuilder} 起必须显式注入合法 runtime（"本会话不读/不写生产实例"的两参构造器已删除）。
   *
   * <p>本桥的两条路径：① 传入的定义本身就是 production 切片（{@link ProductionData}）⇒ 直接用同一份候选态建运行时， 实例随之可见（对旧 {@code
   * dayView(base, defs)} 的逐值等价表达）；② 否则退化为"零实例"运行时—— 与旧两参形态的语义一致（只有定义、没有实例）。
   */
  static ProductionRuntime runtimeOf(ProductionDefinitionLookup definitions) {
    return new ProductionRuntimeEngine(
        definitions instanceof ProductionData data ? data : ProductionData.empty());
  }

  /**
   * ★★ <b>W1：一格一张派生价表</b>（键 = {@code "hex:<q>_<r>"}，与 {@link MarketTopology#singleHex} 的节点 id 同源）。
   *
   * <p>★ 它是"没有地图切片"的退化世界的价表形态（逐格一市场）；改动前那些夹具写的是 {@code Map.of(H, new Market(...))} ——
   * 本助手就是那个形状在新形状下的**逐值等价**表达（键从格变成派生的市场 id，成员格 = 该格）。
   */
  static Map<MarketZoneId, MarketTable> hexMarkets(Map<HexCoord, MarketTable> quotes) {
    Map<MarketZoneId, MarketTable> tables = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, MarketTable> entry : quotes.entrySet()) {
      tables.put(
          marketIdOf(entry.getKey()),
          MarketTable.singleHex(
              entry.getValue().numeraire(), entry.getKey(), entry.getValue().prices()));
    }
    return tables;
  }

  /** ★ W1：价表**模板**（只有计价币与价格表，成员格为空）—— 由 {@link #hexMarkets} 挂到具体格上。 */
  static MarketTable quote(CurrencyId numeraire, Map<CommodityId, Long> prices) {
    return new MarketTable(numeraire, Set.of(), prices);
  }

  /** ★ W1：派生市场的稳定 id（与 {@code MarketTopology.singleHex} 的节点 id 逐字同源）。 */
  static MarketZoneId marketIdOf(HexCoord hex) {
    return new MarketZoneId("hex:" + IndustryHexKeys.hexKey(hex.q(), hex.r()));
  }

  static AccountSession accountSession(
      EconomyData base, Map<CohortKey, Map<CommodityId, Long>> goods) {
    AccountSession accounts = AccountSession.empty();
    for (HouseholdEconomy row : base.classes().values()) {
      CohortKey key = goodsKeyFor(goods, row);
      accounts.registerHousehold(
          row.id(),
          row.view().hex(),
          goods.getOrDefault(key, Map.of()),
          Map.of(),
          Map.of(),
          Map.of());
    }
    // ★★ D-032：旧口径这里逐条遍历 `base.units()`（当时 economy 自己持有实例表）"登记经营者账"。
    //   ① 实例表已迁到 production（`EconomyData` 不再有 `units()`，I1）；
    //   ② 该循环体在删前就是**空操作**（两次 continue 之后没有任何写动作，`registeredOperators` 只被丢弃）：
    //      经营者账登记另有路径（`AccountSession.registerHousehold` 之外无二次登记）。
    //   ⇒ 删除该空循环是**逐值不变**的收口，不是放宽断言：本方法返回的 accounts 逐值等于删前。
    return accounts;
  }

  /**
   * 旧形状工作副本里属于这一行的稳定键：**按 {@code hh(key) == row.id()} 反查**，不按可变的 {@code row.view()}。
   *
   * <p>★ 家户阶层在周期关账时会被重分类（view 变），若按 view 写回会留下旧键、下一轮又读错一本账。
   */
  private static CohortKey goodsKeyFor(
      Map<CohortKey, Map<CommodityId, Long>> goods, HouseholdEconomy row) {
    for (CohortKey key : goods.keySet()) {
      if (hh(key).equals(row.id())) {
        return key;
      }
    }
    return row.view();
  }

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

  static HouseholdEconomy classRow(
      CohortKey view,
      long population,
      long laborMilli,
      int participationPerMille,
      long money,
      Map<CommodityId, Long> naturalNeeds,
      Map<CommodityId, Long> effectiveDemand,
      long cycleNaturalNeedMilli) {
    return new HouseholdEconomy(
        hh(view),
        view,
        population,
        laborMilli,
        participationPerMille,
        money,
        naturalNeeds,
        effectiveDemand,
        cycleNaturalNeedMilli);
  }

  /** ★ 旧 5 参 ProductionRelation（activity = 产业 id）→ 当前 unit 键。 */
  static ProductionRules relation(
      IndustryId activity,
      ActorRef operator,
      Payee inputSupplier,
      List<CompensationRule> rules,
      ActorRef residualOwner) {
    ProductionUnitId unit = ProductionUnitId.idOf(activity, operator);
    return new ProductionRules(unit, operator, inputSupplier, rules, residualOwner);
  }

  /**
   * ★★ D-032：实例表的唯一权威在 production ⇒ 本组读数助手的第一参从 {@link EconomyData} 改为 production 切片只读投影 （{@link
   * ProductionStateLookup}，其 {@code instances()} 与 {@code ProductionRuntime.units()} 逐值同义）。
   */
  static long progressDaysOf(ProductionStateLookup data, IndustryId industry) {
    long total = 0L;
    for (ProductionProcess unit : data.instances().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.progressDays();
      }
    }
    return total;
  }

  /** 旧视图 → 当前 {@link EconomyData.classes} 表里的行（找不到 ⇒ null，与 {@code Map.get} 同口径）。 */
  static HouseholdEconomy classOf(EconomyData data, CohortKey view) {
    return data.classes().get(hh(view));
  }

  /** 旧视图 → 当前 {@link EconomyData.flows} 表里的流水（找不到 ⇒ null，与 {@code Map.get} 同口径）。 */
  static FlowRow flowOf(EconomyData data, CohortKey view) {
    return data.flows().get(hh(view));
  }

  /** 按产业汇总 unit 的本周期累计劳动（千分劳动·日）。★ D-032：第一参见 {@link #progressDaysOf}。 */
  static long cycleLaborOf(ProductionStateLookup data, IndustryId industry) {
    long total = 0L;
    for (ProductionProcess unit : data.instances().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.cycleLaborMilli();
      }
    }
    return total;
  }

  /** 按产业汇总 unit 的本周期实扣投入（毫单位，按商品）。★ D-032：第一参见 {@link #progressDaysOf}。 */
  static long cycleInputUsedOf(
      ProductionStateLookup data, IndustryId industry, CommodityId commodity) {
    long total = 0L;
    for (ProductionProcess unit : data.instances().values()) {
      if (unit.industry().equals(industry)) {
        total += unit.cycleInputUsedMilli().getOrDefault(commodity, 0L);
      }
    }
    return total;
  }

  /** 按产业取第一个 unit（旧用例每产业一个 unit；无 unit ⇒ 抛，不静默）。★ D-032：第一参见 {@link #progressDaysOf}。 */
  static ProductionProcess unitOf(ProductionStateLookup data, IndustryId industry) {
    for (ProductionProcess unit : data.instances().values()) {
      if (unit.industry().equals(industry)) {
        return unit;
      }
    }
    throw new IllegalStateException("本产业没有 ProductionProcess（夹具形状不对）: " + industry);
  }

  /** 按产业取 unit 的经营者（旧用例每产业一个 unit）。★ D-032：第一参见 {@link #progressDaysOf}。 */
  static ActorRef operatorOf(ProductionStateLookup data, IndustryId industry) {
    return unitOf(data, industry).operator();
  }
}
