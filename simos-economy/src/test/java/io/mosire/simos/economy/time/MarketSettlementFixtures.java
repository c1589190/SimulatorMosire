package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        List.of(),
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

    Builder market(HexCoord hex, long grainPrice) {
      return market(hex, grainMarket(grainPrice));
    }

    Builder market(HexCoord hex, Market market) {
      markets.put(Objects.requireNonNull(hex, "hex"), Objects.requireNonNull(market, "market"));
      return this;
    }

    Builder household(HouseholdId id, HexCoord hex, long population, long grain, long silver) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(hex, "hex");
      rows.put(id, ruralRow(id, hex, population));
      goods.put(id, new LinkedHashMap<>(Map.of(GRAIN, grain)));
      money.put(id, new LinkedHashMap<>(Map.of(SILVER, silver)));
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
      MarketTopology topology =
          MarketTopology.singleRegion(
              markets,
              markets.keySet(),
              hex -> 1,
              (from, to) -> 0,
              TransportTariff.probeDefaults());
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
          operatorFrozenMoney);
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
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney) {

    long grainOf(HouseholdId household) {
      return goods.getOrDefault(household, Map.of()).getOrDefault(GRAIN, 0L);
    }

    long silverOf(HouseholdId household) {
      return money.getOrDefault(household, Map.of()).getOrDefault(SILVER, 0L);
    }
  }

  record Round(
      World world, MarketSettlement.MarketRound round, ProductionLedger.Accumulator ledger) {}

  static Round round(World world, MarketRegulation regulation) {
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
            regulation);
    return new Round(world, round, ledger);
  }

  static MarketSettlement.MarketOutcome settle(World world, Round round) {
    return MarketSettlement.clearOncePerCycle(
        world.markets(), round.round(), MarketTrigger.PERIODIC, world.topology());
  }

  /** 只服务夹具的 commodity id 常量拼写：避免测试里手写第二个字面量。 */
  private static final class EconomyVocabularyCommodity {
    private static final CommodityId CLOTH =
        new CommodityId(io.mosire.simos.util.economy.EconomyVocabulary.CLOTH_COMMODITY_ID);
  }
}
