package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC6/RC8d：S7-B LANDLESS 市场买地后的 LAND+人口转移</b>。
 *
 * <p>判据：多 hex 按成交 LAND 比例拆人口；LAND 全转对应 hex 的 LANDED target；migrants=0 仍清 LAND；0 人口 LANDED 壳可承接； 无
 * target 具名 fail-closed；动态 minLand 与 maxByWealth 财富门生效；buyer 残留 LAND 不得落盘。
 */
class Rc6PostLandTradeClassCheckTest {

  private static final HexCoord HEX_A = new HexCoord(0, 0);
  private static final HexCoord HEX_B = new HexCoord(1, 0);

  /** 既有 OWNER target 的资格锚：放在第三个格，避免污染被测量的目标 hex LAND 数量。 */
  private static final HexCoord OWNER_SEED_HEX = new HexCoord(9, 9);

  private static final CurrencyId SILVER = new CurrencyId("silver");
  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final HouseholdId BUYER = HouseholdId.parse("hh-post-land-buyer");
  private static final HouseholdId TARGET_A = HouseholdId.parse("hh-post-land-target-a");
  private static final HouseholdId TARGET_B = HouseholdId.parse("hh-post-land-target-b");
  private static final IndustryId FARM = IndustryHexKeys.id("rc6-grain-farm", 0, 0);
  private static final MarketZoneId MARKET_ID = new MarketZoneId("rc6-market");
  private static final ClassPositionId LANDED_POSITION =
      DefaultProductionModes.positionId(
              DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_OPERATOR)
          .orElseThrow();
  private static final ClassPositionId LANDLESS_POSITION =
      DefaultProductionModes.positionId(
              DefaultProductionModes.GRAIN_FARM, DefaultProductionModes.ROLE_HIRED_LABORER)
          .orElseThrow();
  private static final HouseholdCultureKey CULTURE =
      new HouseholdCultureKey("latium", "roman", List.of(), "本地农户");
  private static final HouseholdCultureKeyLookup CULTURE_LOOKUP = household -> Optional.of(CULTURE);

  /**
   * 夹具口径（可手算）：100 人日粮 10,000、周期 10 天 ⇒ 每人每周期 1,000 毫粮； 净产 4 × 1000 − 389 = 3,611 毫粮/千分亩 ⇒ minLand =
   * ceil(1,000 × 1000 / 3,611) = 277。
   */
  private static final long BUYER_NATURAL_NEED_GRAIN = 10_000L;

  private static final long MIN_LAND_PER_PERSON = 277L;

  @Test
  void multipleHexAcquisitionsTransferAllLandAndSplitPopulationByLandWeight() {
    World world = World.multiHex();
    world.markAcquired(HEX_A, 30_000L);
    world.markAcquired(HEX_B, 20_000L);

    assertThat(PostLandTradeClassCheck.minLandPerPersonMilliMu(world.ctx, BUYER, HEX_A))
        .as("RC8d：动态 minLand = 277 千分亩/人（不是硬编码 2667）")
        .isEqualTo(MIN_LAND_PER_PERSON);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.buyers()).as("只有 LANDLESS buyer 进入 S7-B").isEqualTo(1);
    assertThat(result.hexes()).isEqualTo(2);
    assertThat(result.landTransferred()).isEqualTo(50_000L);
    assertThat(result.migrants()).as("maxBySpeed = 100‰ × 100 = 10").isEqualTo(10L);
    assertThat(result.targets())
        .extracting(PostLandTradeClassCheck.TargetMove::migrants)
        .as("按 LAND 权重 3:2 做最大余数拆分")
        .containsExactly(6L, 4L);
    assertThat(world.landOf(BUYER, HEX_A)).as("buyer 不得残留临时 LAND A").isZero();
    assertThat(world.landOf(BUYER, HEX_B)).as("buyer 不得残留临时 LAND B").isZero();
    assertThat(world.landOf(TARGET_A, HEX_A))
        .as("LAND 全转对应 hex 的 LANDED target A")
        .isEqualTo(30_000L);
    assertThat(world.landOf(TARGET_B, HEX_B))
        .as("LAND 全转对应 hex 的 LANDED target B")
        .isEqualTo(20_000L);
    assertThat(world.ctx.session.pendingPopulationTransfers())
        .as("每 hex 一笔人口 outbox，总人口守恒")
        .hasSize(2)
        .allMatch(
            transfer ->
                transfer
                    .reason()
                    .equals(PostLandTradeClassCheck.POST_LAND_CLASS_TRANSITION_REASON));
  }

  @Test
  void subMinimumLandStillTransfersAllLandWithZeroMigrants() {
    World world = World.zeroMigrants();
    world.markAcquired(HEX_A, MIN_LAND_PER_PERSON - 1L);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.migrants()).as("acquired LAND < minLand ⇒ maxByLand=0 ⇒ migrants=0").isZero();
    assertThat(world.landOf(BUYER, HEX_A)).as("即使 migrants=0，LAND 也必须清零").isZero();
    assertThat(world.landOf(TARGET_A, HEX_A)).isEqualTo(MIN_LAND_PER_PERSON - 1L);
    assertThat(world.ctx.session.pendingPopulationTransfers()).isEmpty();
  }

  @Test
  void zeroPopulationLandedShellCanReceiveLandAndMigrants() {
    World world = World.zeroPopulationShell();
    world.markAcquired(HEX_A, 10_000L);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.landTransferred()).isEqualTo(10_000L);
    assertThat(result.migrants()).as("正人口买家仍按速度/土地上限迁移").isEqualTo(10L);
    assertThat(world.landOf(TARGET_A, HEX_A)).as("0 人口 LANDED 壳可作为 LAND 目标").isEqualTo(10_000L);
    assertThat(world.ctx.session.pendingPopulationTransfers())
        .as("人口 outbox 指向 0 人口壳，由 Social 回写人口")
        .singleElement()
        .satisfies(transfer -> assertThat(transfer.target()).isEqualTo(TARGET_A));
  }

  @Test
  void ownerAtMarketTradeStartIsNotProcessedByS7B() {
    World world = World.landedBuyer();
    world.snapshotBuyerAs(OwnershipClass.OWNER);
    world.markAcquired(HEX_A, 10_000L);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.buyers()).as("S7-M2 后快照为 OWNER 的买方不触发 S7-B").isZero();
    assertThat(result.landTransferred()).isZero();
    assertThat(world.landOf(BUYER, HEX_A)).as("OWNER 买方保留正常买入的 LAND").isEqualTo(10_000L);
  }

  @Test
  void noLandedTargetCreatesDeterministicZeroPopulationOwnerShell() {
    World world = World.withoutTargets();
    world.markAcquired(HEX_A, 10_000L);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.buyers()).isEqualTo(1);
    assertThat(result.landTransferred()).isEqualTo(10_000L);
    assertThat(result.targets())
        .singleElement()
        .satisfies(
            move -> {
              assertThat(world.landOf(move.target(), HEX_A)).isEqualTo(10_000L);
              assertThat(
                      world
                          .ctx
                          .session
                          .sheet()
                          .householdEconomies()
                          .get(move.target())
                          .population())
                  .isZero();
              assertThat(world.ctx.session.sheet().classMemberships())
                  .as("OWNER 壳无 mode/currentPosition/class standing")
                  .doesNotContainKey(move.target());
            });
    HouseholdId shell = result.targets().get(0).target();
    assertThat(world.ctx.session.pendingPopulationTransfers())
        .as("0 人口壳首次接收人口时 outbox newTarget=true 且 targetMode 为空")
        .singleElement()
        .satisfies(
            transfer -> {
              assertThat(transfer.target()).isEqualTo(shell);
              assertThat(transfer.newTarget()).isTrue();
              assertThat(transfer.targetMode()).isEmpty();
            });
  }

  @Test
  void positiveLandPriceGatesMigrantsByBuyerWealth() {
    World poor = World.withLandPriceAndMoney(1_000L, MIN_LAND_PER_PERSON - 1L);
    poor.markAcquired(HEX_A, 10_000L);
    PostLandTradeClassCheck.PostLandResult poorResult =
        PostLandTradeClassCheck.settle(poor.ctx, poor.ctx.day);

    assertThat(poorResult.migrants()).as("财富不够一人份地价 ⇒ maxByWealth=0；LAND 仍转移").isZero();
    assertThat(poor.landOf(TARGET_A, HEX_A)).isEqualTo(10_000L);

    World rich = World.withLandPriceAndMoney(1_000L, 2_769L);
    rich.markAcquired(HEX_A, 10_000L);
    PostLandTradeClassCheck.PostLandResult richResult =
        PostLandTradeClassCheck.settle(rich.ctx, rich.ctx.day);

    assertThat(richResult.migrants()).as("财富 2,769 / 每人 277 ⇒ 财富门 9，低于速度门 10").isEqualTo(9L);
  }

  @Test
  void zeroLandPriceHasNoWealthGate() {
    World world = World.withLandPriceAndMoney(0L, 0L);
    world.markAcquired(HEX_A, 10_000L);

    PostLandTradeClassCheck.PostLandResult result =
        PostLandTradeClassCheck.settle(world.ctx, world.ctx.day);

    assertThat(result.migrants()).as("明确 0 地价 = 免费交易，不按财富门归零").isEqualTo(10L);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static final class World {
    final SettlementDayContext ctx;
    final MarketSettlement.MarketRound marketRound;

    World(SettlementDayContext ctx, MarketSettlement.MarketRound marketRound) {
      this.ctx = ctx;
      this.marketRound = marketRound;
      ctx.marketRound = marketRound;
    }

    static World multiHex() {
      return build(true, Map.of(HEX_A, 30_000L, HEX_B, 20_000L), 100L, 50L, 0L, null);
    }

    static World zeroMigrants() {
      return build(true, Map.of(HEX_A, MIN_LAND_PER_PERSON - 1L), 100L, 50L, 0L, null);
    }

    static World zeroPopulationShell() {
      return build(true, Map.of(HEX_A, 10_000L), 100L, 0L, 0L, null);
    }

    static World landedBuyer() {
      return build(true, Map.of(HEX_A, 10_000L), 100L, 50L, 0L, null);
    }

    static World withoutTargets() {
      return build(false, Map.of(HEX_A, 10_000L), 100L, 50L, 0L, null);
    }

    static World withLandPriceAndMoney(long landPrice, long buyerMoney) {
      return build(true, Map.of(HEX_A, 10_000L), 100L, 50L, buyerMoney, landPrice);
    }

    void markAcquired(HexCoord hex, long quantity) {
      marketRound.markLandAcquired(new LandHoldingKey(hex, BUYER), quantity);
    }

    void snapshotBuyerAs(OwnershipClass ownershipClass) {
      Map<HouseholdId, OwnershipClass> snapshot = new LinkedHashMap<>();
      for (HouseholdId household : ctx.householdEconomies.keySet()) {
        snapshot.put(household, ownershipClass);
      }
      ctx.ownershipClassAtMarketTradeStart = snapshot;
    }

    long landOf(HouseholdId household, HexCoord hex) {
      LandHolding holding =
          ctx.session.sheet().landHoldings().get(new LandHoldingKey(hex, household));
      return holding == null ? 0L : holding.quantityMilliMu();
    }

    private static World build(
        boolean withTargets,
        Map<HexCoord, Long> buyerHoldings,
        long buyerPopulation,
        long targetPopulation,
        long buyerMoney,
        Long landPrice) {
      Map<HouseholdId, HouseholdEconomy> rows = new LinkedHashMap<>();
      rows.put(BUYER, row(BUYER, HEX_A, buyerPopulation, SocialClassId.LANDLESS_LABORER));
      if (withTargets) {
        rows.put(TARGET_A, row(TARGET_A, HEX_A, targetPopulation, SocialClassId.LANDED_PEASANT));
        rows.put(TARGET_B, row(TARGET_B, HEX_B, 40L, SocialClassId.LANDED_PEASANT));
      }
      Map<HouseholdId, HouseholdClassMembership> memberships = new LinkedHashMap<>();
      memberships.put(BUYER, membership(BUYER, LANDLESS_POSITION));
      if (withTargets) {
        memberships.put(TARGET_A, membership(TARGET_A, LANDED_POSITION));
        memberships.put(TARGET_B, membership(TARGET_B, LANDED_POSITION));
      }

      Map<LandHoldingKey, LandHolding> holdings = new LinkedHashMap<>();
      for (Map.Entry<HexCoord, Long> entry : buyerHoldings.entrySet()) {
        holdings.put(
            new LandHoldingKey(entry.getKey(), BUYER),
            new LandHolding(BUYER, entry.getKey(), entry.getValue()));
      }
      if (withTargets) {
        // 目标户的 OWNER 资格由当刻资产派生：给每户一笔不污染测量 hex 的既有 LAND。
        holdings.put(
            new LandHoldingKey(OWNER_SEED_HEX, TARGET_A),
            new LandHolding(TARGET_A, OWNER_SEED_HEX, 1L));
        holdings.put(
            new LandHoldingKey(OWNER_SEED_HEX, TARGET_B),
            new LandHolding(TARGET_B, OWNER_SEED_HEX, 1L));
      }

      IndustryDefinitionView industry = grainIndustry();
      EconomyData base =
          EconomyData.empty()
              .withClassStructures(DefaultProductionModes.classStructures())
              .withProductionRoles(DefaultProductionModes.classPositions())
              .withHouseholdEconomies(rows)
              .withClassMemberships(memberships);
      EconomySession session =
          new EconomySession(
              base,
              ProductionData.empty(),
              new ProductionRuntimeEngine(ProductionData.empty()),
              new UnavailableAssetOccupancyLookup(),
              new UnavailableParticipantAttributeLookup(),
              null,
              CULTURE_LOOKUP,
              EconomyFixtures.landLedger(holdings));
      AccountSession accounts = AccountSession.empty();
      for (HouseholdId household : rows.keySet()) {
        accounts.registerHousehold(
            household,
            rows.get(household).view().hex(),
            Map.of(),
            household.equals(BUYER) ? Map.of(SILVER, buyerMoney) : Map.of(),
            Map.of(),
            Map.of());
      }
      ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(5L);
      SettlementIndex index =
          SettlementIndex.buildWithoutAssetOccupancy(
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              session.sheet().householdEconomies(),
              Map.of(),
              Map.of());
      Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
      Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
      MarketSettlement.MarketRound marketRound =
          new MarketSettlement.MarketRound(
              5L,
              session.sheet().householdEconomies(),
              goods,
              money,
              new LinkedHashMap<>(),
              new LinkedHashMap<>(),
              new LinkedHashMap<>(),
              new LinkedHashMap<>(),
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              Map.of(),
              ledger,
              Map.of(),
              index,
              Map.of(),
              MarketRegulation.none(),
              null,
              null,
              Set.of());
      SettlementDayContext ctx =
          new SettlementDayContext(
              session,
              5L,
              accounts,
              null,
              false,
              ledger,
              EconomyParallelism.singleThreaded(),
              null,
              Map.of(),
              Set.of(),
              null,
              null,
              null,
              null);
      ctx.base = base;
      ctx.dayView = new WorkingDayView(session.sheet());
      ctx.householdEconomies = session.sheet().householdEconomies();
      ctx.debts = session.sheet().debtContracts();
      ctx.householdGoods = accounts.householdGoods();
      ctx.assetShares = new LinkedHashMap<>();
      // 市场前快照：买方是 PROLETARIAN（buyerHoldings 是成交后待转的临时 LAND，不代表市场前资产）；
      // 目标户已有 LAND ⇒ OWNER。
      Map<HouseholdId, OwnershipClass> ownershipSnapshot = new LinkedHashMap<>();
      for (HouseholdId household : rows.keySet()) {
        ownershipSnapshot.put(household, OwnershipClass.PROLETARIAN);
      }
      if (withTargets) {
        ownershipSnapshot.put(TARGET_A, OwnershipClass.OWNER);
        ownershipSnapshot.put(TARGET_B, OwnershipClass.OWNER);
      }
      ctx.ownershipClassAtMarketTradeStart = ownershipSnapshot;
      ctx.industries = new LinkedHashMap<>(Map.of(FARM, industry));
      ctx.hexToIndustries =
          Map.of(
              IndustryHexKeys.hexKey(HEX_A.q(), HEX_A.r()), List.of(FARM),
              IndustryHexKeys.hexKey(HEX_B.q(), HEX_B.r()), List.of(FARM));
      if (landPrice != null) {
        MarketTable market =
            new MarketTable(
                SILVER,
                Set.of(HEX_A, HEX_B),
                Map.of(GRAIN, 10L),
                OptionalLong.of(landPrice),
                MarketTable.DEFAULT_LOAN_RATE_PER_MILLE,
                MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE);
        ctx.markets = new LinkedHashMap<>(Map.of(MARKET_ID, market));
      } else {
        ctx.markets = new LinkedHashMap<>();
      }
      return new World(ctx, marketRound);
    }
  }

  private static IndustryDefinitionView grainIndustry() {
    return IndustryViews.industry(
        FARM,
        "RC6 grain farm",
        new RegimeId("rc6"),
        10L,
        Map.of(AssetKind.LAND, 1L),
        Map.of(),
        0L,
        0L,
        Map.of(GRAIN, 4L),
        Map.of(AssetKind.LAND, Map.of(GRAIN, 389L)),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0));
  }

  private static HouseholdEconomy row(
      HouseholdId id, HexCoord hex, long population, SocialClassId stratum) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, stratum),
        population,
        1_000L,
        1_000,
        0L,
        Map.of(GRAIN, BUYER_NATURAL_NEED_GRAIN),
        Map.of(),
        0L);
  }

  private static HouseholdClassMembership membership(HouseholdId id, ClassPositionId position) {
    return new HouseholdClassMembership(id, position, position, Map.of(), 0L, 0L, "rc6-test");
  }
}
