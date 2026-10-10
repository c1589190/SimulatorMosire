package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>D-027 区内市场/单 hex 贸易成本的最小真实夹具</b>（测试专用，不是生产 API）。
 *
 * <p>它只搭"两三个家户 + 逐格市场 + 真实 {@code MarketSettlement.MarketRound}"：不手搭 {@code
 * EconomyData}、不复制撮合逻辑；成交仍走 {@code MarketSettlement.clearOncePerCycle} 与唯一写口 {@code
 * EconomySettlement.applyTransfer}。所有账户/冻结表都是活的可变表，测试在结算后直接读终态。
 *
 * <p>★ 每个家户的"生活保留"由 {@code HouseholdEconomy.population} 经 {@code
 * EconomyVocabulary.cumulativeRationMilli(pop, 35)} 现算 —— 与生产路径同一条算式；夹具不写死保留量。
 */
final class MarketSettlementFixtures {

  static final HexCoord H1 = new HexCoord(0, 0);
  static final HexCoord H2 = new HexCoord(1, 0);
  static final HexCoord H3 = new HexCoord(2, 0);

  static final CommodityId GRAIN = EconomySettlement.GRAIN;
  static final CommodityId CLOTH = EconomyVocabularyCommodity.CLOTH;
  static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  /** 市场轮的世界日：随便一个 >0 的日子；只影响 deadline 与 Fill 日号。 */
  static final long DAY = 5L;

  /**
   * ★ 2026-10-09 家户结构修复后的口径：运行时生活保留 = <b>本户当日注入 {@code naturalNeeds} × 补货窗口</b> （{@code
   * HouseholdEconomy.expectedNeedMilli}），不再按 {@code population × 全局人均定额} 现算。 ⇒ 夹具把"1 人 1 天的口粮"注入
   * {@code naturalNeeds[grain]}，保留额 = 日额 × {@link MarketSettlement#MARKET_LIFE_RESERVE_DAYS}（35 天）。
   */
  static long dailyRationGrain(long population) {
    return io.mosire.simos.util.economy.EconomyVocabulary.dailyRationMilli(population, 1L);
  }

  /** 一个家户的生活保留（35 天 × 当日注入需求）——测试断言的唯一算式来源。 */
  static long lifeReserveGrain(long population) {
    return dailyRationGrain(population) * MarketSettlement.MARKET_LIFE_RESERVE_DAYS;
  }

  private MarketSettlementFixtures() {}

  static Builder builder() {
    return new Builder();
  }

  /** 单格市场：只给 grain 一个价（其它商品在本夹具不交易）。 */
  static Market grainMarket(long grainPrice) {
    return new Market(SILVER, new LinkedHashMap<>(Map.of(GRAIN, grainPrice)));
  }

  static HouseholdEconomy ruralRow(HouseholdId id, HexCoord hex, long population) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
        population,
        0L,
        0,
        0L,
        // ★ 2026-10-09：运行时需求不再由 population 折算 ⇒ 夹具必须显式注入当日自然需求，
        //   否则撮合看到的保留额/缺口恒 0（这正是本夹具旧口径失效的根因）。
        Map.of(GRAIN, dailyRationGrain(population)),
        Map.of(),
        0L);
  }

  static final class Builder {

    private final Map<HexCoord, Market> markets = new LinkedHashMap<>();
    private final Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<CommodityId, Long>> frozenGoods = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<CurrencyId, Long>> frozenMoney = new LinkedHashMap<>();
    private final Map<HouseholdId, Map<CommodityId, Long>> unmetToday = new LinkedHashMap<>();
    private final Map<ActorRef, HouseholdId> householdOfActor = new LinkedHashMap<>();
    private final Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    private final Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    private final Map<AssetShareId, io.mosire.simos.economy.model.OwnershipStake> assetShares =
        new LinkedHashMap<>();
    private final Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    private final Map<LaborAllocationId, io.mosire.simos.economy.api.labor.HouseholdLaborCommitment>
        allocations = new LinkedHashMap<>();
    private final Map<ShipmentId, ShipmentBatch> shipments = new LinkedHashMap<>();
    private final Map<ProductionUnitId, OperatorCondition> operatorConditions =
        new LinkedHashMap<>();
    private final Map<ActorRef, Map<CommodityId, Long>> operatorGoods = new LinkedHashMap<>();
    private final Map<ActorRef, Map<CurrencyId, Long>> operatorMoney = new LinkedHashMap<>();
    private final Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods = new LinkedHashMap<>();
    private final Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney = new LinkedHashMap<>();

    /** ★★ M-A1（2026-10-10）：跑商家户的阶层归属与位置表（运力池的两张输入表；缺省空 ⇒ 无运力池）。 */
    private final Map<HouseholdId, HouseholdClassMembership> classStandings = new LinkedHashMap<>();

    private final Map<ClassPositionId, ProductionRole> classPositions = new LinkedHashMap<>();

    /** ★★ P-T1a：显式声明的市场区（zoneId → 锚格）。空 ⇒ 沿用单区（既有 5 个用例的行为一字不改）。 */
    private final Map<String, HexCoord> regions = new LinkedHashMap<>();

    /**
     * ★★ <b>声明一个市场区</b>（P-T1a/P-T1b 的跨区用例）：一旦声明了 ≥1 个区，{@code build()} 走 {@link MarketTopology#of}
     * 的多区装配（每个锚格的半径 = 0 ⇒ 逐格各归其区），并且<b>区 id 就是注入表的区键</b> （组合根 {@code
     * MarketTopologyBook.byPersistentZones} 的口径）。
     */
    Builder region(String zoneId, HexCoord anchor) {
      if (zoneId == null || zoneId.isBlank()) {
        throw new IllegalArgumentException("区 id 不得为空白");
      }
      regions.put(zoneId, Objects.requireNonNull(anchor, "anchor"));
      return this;
    }

    Builder market(HexCoord hex, long grainPrice) {
      return market(hex, grainMarket(grainPrice));
    }

    Builder market(HexCoord hex, Market market) {
      markets.put(Objects.requireNonNull(hex, "hex"), Objects.requireNonNull(market, "market"));
      return this;
    }

    /**
     * ★★ <b>一个只提供运力的跑商家户</b>（M-A1：运力 = 派生量 = 劳动 + 工具；不再有商号行）。
     *
     * <p>它<b>不参与商品市场</b>：0 人口 + 空自然需求 + 空商品账 + 空货币账 ⇒ 不生成买单也不生成卖单，只在 {@link #settleWithCarriers}
     * 里进运力池。工具存量 ≥ {@code MerchantHaul.TOOL_MILLI_PER_HAUL} 是跑商门槛。
     */
    Builder carrier(HouseholdId id, HexCoord hex, long laborMilli, long toolMilli) {
      dormant(id, hex);
      laborOf(id, laborMilli);
      ClassPositionId position = ClassPositionId.parse("merchant-fixture:" + id.value());
      classPositions.putIfAbsent(
          position,
          new ProductionRole(
              position,
              DefaultProductionModes.MERCHANT,
              "跑商（夹具）",
              ProductionRole.RelationToMeans.MIXED,
              ProductionRole.LaborRole.ORGANIZER,
              ProductionRole.SurplusRole.SURPLUS_RECEIVER,
              Map.of()));
      classStandings.put(
          id,
          new HouseholdClassMembership(
              id, position, position, Set.of(), Map.of(), 0L, 0L, "fixture:carrier"));
      goods.put(id, new LinkedHashMap<>(Map.of(MerchantCapacityPool.TOOL_COMMODITY, toolMilli)));
      return this;
    }

    /**
     * ★★ <b>一个"睡着"的家户</b>：已登记（账户主体只有家户 ⇒ 国库户必须是家户）、不参与商品市场 （0 人口 / 空需求 / 空商品账 / 空货币账 ⇒
     * 不生成任何订单）。服务"国库户"这类只收钱的账户主体。
     */
    Builder dormant(HouseholdId id, HexCoord hex) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(hex, "hex");
      rows.put(
          id,
          new HouseholdEconomy(
              id,
              new CohortKey(hex, ResidenceKind.RURAL, new SocialClassId("official")),
              0L,
              0L,
              0,
              0L,
              Map.of(),
              Map.of(),
              0L));
      goods.put(id, new LinkedHashMap<>());
      money.put(id, new LinkedHashMap<>());
      frozenGoods.put(id, new LinkedHashMap<>());
      frozenMoney.put(id, new LinkedHashMap<>());
      unmetToday.put(id, new LinkedHashMap<>());
      householdOfActor.put(HouseholdActors.of(id), id);
      return this;
    }

    /** ★ 承运家户的劳动投入（M-A1 的运力来源之一；工具走商品账）。 */
    Builder laborOf(HouseholdId id, long laborMilli) {
      HouseholdEconomy row = rows.get(id);
      Objects.requireNonNull(row, "laborOf 的家户必须先登记: " + id);
      rows.put(
          id,
          new HouseholdEconomy(
              id,
              row.view(),
              row.population(),
              laborMilli,
              1_000,
              row.money(),
              row.naturalNeeds(),
              row.effectiveDemand(),
              row.cycleNaturalNeedMilli()));
      return this;
    }

    Builder household(HouseholdId id, HexCoord hex, long population, long grain, long silver) {
      return household(
          id,
          hex,
          population,
          Map.of(GRAIN, grain),
          Map.of(SILVER, silver),
          Map.of(GRAIN, dailyRationGrain(population)));
    }

    /**
     * ★★ <b>T6 / T4-T5（2026-10-10 测试批）：可自定义"商品账 / 货币账 / 当日自然需求篮子"的家户</b>。
     *
     * <p>三张表分开给，是因为多币与多商品用例要表达的恰好是"同一户持有两种币、需求篮子只含其中一种商品的价" 这类形状；{@link #household(HouseholdId,
     * HexCoord, long, long, long)} 只是它的单币单商品退化式 （逐值等同旧夹具口径：需求 = 1 人 1 天口粮、商品只有 grain、货币只有 silver）。
     */
    Builder household(
        HouseholdId id,
        HexCoord hex,
        long population,
        Map<CommodityId, Long> goodsByCommodity,
        Map<CurrencyId, Long> moneyByCurrency,
        Map<CommodityId, Long> naturalNeeds) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(hex, "hex");
      rows.put(
          id,
          new HouseholdEconomy(
              id,
              new CohortKey(hex, ResidenceKind.RURAL, new SocialClassId("poor_peasant")),
              population,
              0L,
              0,
              0L,
              naturalNeeds,
              Map.of(),
              0L));
      goods.put(id, new LinkedHashMap<>(goodsByCommodity));
      money.put(id, new LinkedHashMap<>(moneyByCurrency));
      frozenGoods.put(id, new LinkedHashMap<>());
      frozenMoney.put(id, new LinkedHashMap<>());
      unmetToday.put(id, new LinkedHashMap<>());
      householdOfActor.put(HouseholdActors.of(id), id);
      return this;
    }

    /**
     * 一个"成本已知"的 grain 卖户：挂一个只产出 grain、无投入、无劳动的 unit —— 它的 {@code ProducerCostBook.Estimate} 是
     * 0（已知成本），因此在按成本分层的撮合里排在"成本未知"卖户之前。 只服务配额用尽（需要第二个成本层才能看到剩余配额 = 0）的夹具，不改变任何成交价。
     */
    Builder knownCostGrainSeller(
        HouseholdId id, HexCoord hex, long population, long grain, long silver) {
      household(id, hex, population, grain, silver);
      ActorRef actor = HouseholdActors.of(id);
      IndustryId industryId = IndustryHexKeys.id("grainfarm", hex.q(), hex.r());
      industries.put(
          industryId,
          new Industry(
              industryId,
              "known-cost-grain-farm",
              new RegimeId("family_farm"),
              120L,
              Map.of(AssetKind.WORKSHOP, 1L),
              Map.of(),
              0L,
              0L,
              Map.of(GRAIN, 1L),
              Map.of(),
              List.of(new ClassSlot(new SocialClassId("poor_peasant"), "贫农", 1_000)),
              new AllocationRule.Split(1_000, 0)));
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, actor);
      ProductionProcess unit =
          new ProductionProcess(unitId, industryId, actor, "mode:known-cost", 0L, 0L, Map.of());
      units.put(unitId, unit);
      relations.put(
          unitId,
          new ProductionRules(
              unitId,
              actor,
              new io.mosire.simos.economy.api.relation.Payee.ToActor(actor),
              List.of(),
              actor));
      return this;
    }

    World build() {
      if (markets.isEmpty()) {
        throw new IllegalStateException("夹具至少需要一个市场格");
      }
      MarketTopology topology = topology();
      return new World(
          markets,
          topology,
          rows,
          goods,
          money,
          frozenGoods,
          frozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          allocations,
          shipments,
          operatorConditions,
          operatorGoods,
          operatorMoney,
          operatorFrozenGoods,
          operatorFrozenMoney,
          classStandings,
          classPositions);
    }

    /** ★★ <b>拓扑：没声明区 ⇒ 单区（既有 5 个用例逐值不变）；声明了区 ⇒ 逐锚格半径 0 的多区</b> （每个市场格各归其区，区 id = 注入表的区键）。 */
    private MarketTopology topology() {
      if (regions.isEmpty()) {
        return MarketTopology.singleRegion(
            markets, markets.keySet(), hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults());
      }
      List<MarketNode> nodes = new ArrayList<>();
      for (Map.Entry<String, HexCoord> entry : regions.entrySet()) {
        HexCoord anchor = entry.getValue();
        Market market = markets.get(anchor);
        if (market == null) {
          throw new IllegalStateException("区锚格没有市场表条目（该区不构成可交易区）: " + anchor);
        }
        nodes.add(
            new MarketNode(
                entry.getKey(),
                anchor,
                0,
                market.numeraire(),
                io.mosire.simos.economy.api.money.MoneyVocabulary.SILVER_SPECIE.id()));
      }
      return MarketTopology.of(
          nodes,
          markets,
          markets.keySet(),
          hex -> 1,
          (from, to) -> 0,
          hex -> 0,
          TransportTariff.probeDefaults());
    }
  }

  record World(
      Map<HexCoord, Market> markets,
      MarketTopology topology,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CurrencyId, Long>> money,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> frozenMoney,
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, io.mosire.simos.economy.model.OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<LaborAllocationId, io.mosire.simos.economy.api.labor.HouseholdLaborCommitment>
          allocations,
      Map<ShipmentId, ShipmentBatch> shipments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions) {

    long grainOf(HouseholdId household) {
      return goods.getOrDefault(household, Map.of()).getOrDefault(GRAIN, 0L);
    }

    long silverOf(HouseholdId household) {
      return money.getOrDefault(household, Map.of()).getOrDefault(SILVER, 0L);
    }

    /** ★ M-A1：本世界按"派生运力"装配的运力池（没有 carrier ⇒ 空池 ⇒ 跨格车道不建）。 */
    MerchantCapacityPool carrierPool() {
      return MerchantCapacityPool.of(classStandings, classPositions, rows, goods);
    }
  }

  record Round(
      World world, MarketSettlement.MarketRound round, ProductionLedger.Accumulator ledger) {}

  static Round round(World world, MarketRegulation regulation) {
    return round(world, regulation, Set.of());
  }

  /**
   * ★ Z7b：带"退出商品市场"家户集合的轮（生产路径的 22 参构造器）。旧无参重载 = {@code Set.of()}，与改动前逐值同行为；
   * 本重载只服务"国库/单位户买卖都不生成"的判别力用例。
   */
  static Round round(
      World world, MarketRegulation regulation, Set<HouseholdId> marketExcludedHouseholds) {
    Objects.requireNonNull(marketExcludedHouseholds, "marketExcludedHouseholds");
    SettlementIndex index =
        SettlementIndex.build(
            world.units(),
            world.industries(),
            world.assetShares(),
            world.allocations(),
            world.rows(),
            Map.of(),
            world.relations());
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(DAY);
    MarketSettlement.MarketRound round =
        new MarketSettlement.MarketRound(
            DAY,
            world.rows(),
            world.goods(),
            world.money(),
            world.frozenGoods(),
            world.frozenMoney(),
            world.unmetToday(),
            world.householdOfActor(),
            world.industries(),
            world.units(),
            world.assetShares(),
            world.relations(),
            world.allocations(),
            world.shipments(),
            ledger,
            world.operatorConditions(),
            index,
            Map.of(),
            regulation,
            null,
            null,
            Collections.unmodifiableSet(new LinkedHashSet<>(marketExcludedHouseholds)));
    return new Round(world, round, ledger);
  }

  static MarketSettlement.MarketOutcome settle(World world, Round round) {
    return MarketSettlement.clearOncePerCycle(
        world.markets(), round.round(), MarketTrigger.PERIODIC, world.topology());
  }

  /**
   * ★★ <b>M-A1：带派生运力池的结算入口</b>（有 {@code carrier(...)} 的世界走这一条）。
   *
   * <p>与 {@link #settle} 的唯一差别 = 运力池非空 ⇒ 跨格/跨区车道**建得起来**（否则具名 {@code LOGISTICS_CAPACITY}
   * 拦下，一个字都不成交）。纯商号集合传空 ⇒ 谁都不豁免运费（本批用例不测免运费，那是 M-C 的判据）。
   */
  static MarketSettlement.MarketOutcome settleWithCarriers(World world, Round round) {
    return MarketSettlement.clearOncePerCycle(
        world.markets(),
        round.round(),
        MarketTrigger.PERIODIC,
        world.topology(),
        EconomyParallelism.singleThreaded(),
        world.carrierPool(),
        Set.of());
  }

  /**
   * ★★ <b>T4-T5（2026-10-10 测试批）：把运力池交给调用方持有</b> —— 用同一个实例结算，用例才能在**轮末**读 {@code
   * remainingCapacityAt(hex)}（"这条车道到底有没有占过运力"是 J1 的判据面，读数必须在轮末读）。
   */
  static MarketSettlement.MarketOutcome settleWithPool(
      World world, Round round, MerchantCapacityPool pool) {
    return MarketSettlement.clearOncePerCycle(
        world.markets(),
        round.round(),
        MarketTrigger.PERIODIC,
        world.topology(),
        EconomyParallelism.singleThreaded(),
        pool,
        Set.of());
  }

  /**
   * ★★ <b>T4-T5：注入本轮外汇入参</b>（{@code FxRoundInput.none()} = 没有政府窗口、但民间簿照挂 —— P-T5 起这两件事已经分开；{@code
   * null} 则整个 FX 段早退）。
   */
  static Round withFx(Round round, FxRoundInput fx) {
    Objects.requireNonNull(fx, "fx（没有窗口就给 FxRoundInput.none()）");
    return new Round(round.world(), round.round().withFx(fx), round.ledger());
  }

  /**
   * ★★ <b>P-T1a/P-T1b/P-T1d：把逐轮瞬态的口岸/税/优先级入参注入这一轮</b>（三者都是 {@code MarketRound} 的 wither， 缺省 {@code
   * null} = 不注入 = 那一面逐值退回改前行为）。
   *
   * <p>★ 三个输入<b>分开注入</b>（闸 / 税 / 置顶各管各的缺省）—— 这正是"只设了税"的世界不被静默丢掉的原因。
   */
  static Round withPortInputs(
      Round round,
      PortEnforcementInput enforcement,
      PortTaxInput tax,
      ProcurementPriorityInput priority) {
    MarketSettlement.MarketRound session = round.round();
    if (enforcement != null) {
      session = session.withPortEnforcement(enforcement);
    }
    if (tax != null) {
      session = session.withPortTax(tax);
    }
    if (priority != null) {
      session = session.withProcurementPriority(priority);
    }
    return new Round(round.world(), session, round.ledger());
  }

  /** 只服务夹具的 commodity id 常量拼写：避免测试里手写第二个字面量。 */
  private static final class EconomyVocabularyCommodity {
    private static final CommodityId CLOTH =
        new CommodityId(io.mosire.simos.util.economy.EconomyVocabulary.CLOTH_COMMODITY_ID);
  }
}
