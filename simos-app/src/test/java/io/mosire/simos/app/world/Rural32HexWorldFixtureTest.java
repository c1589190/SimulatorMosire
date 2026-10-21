package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.land.LandComponentLedger;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T1（D-032 v4 R6）：32hex 测试世界的二元农村结构</b>。
 *
 * <p>判据：每个农村 hex 恰好 1 个有地农民户 + 1 个无地农民户；人口 160/40；无 displaced；LAND 全归 LANDED；landless 无 LAND、无自营
 * farm unit、只作为 landed 名册的 hired_laborer；城市/政府/市场配置保持；所有家户有统一默认文化 label。
 */
class Rural32HexWorldFixtureTest {

  private static final String MAP_ID = "d032-32hex-fixture-test";
  private static final ProductionModeId GRAIN_FARM = DefaultProductionModes.GRAIN_FARM;

  @Test
  void eachRuralHexHasExactlyOneLandedAndOneLandlessHouseholdWith160And40People() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> ruralByHex = ruralByHex(economy);

    assertThat(ruralByHex).as("32 个农村 hex 都必须出现在家户表里").hasSize(32);
    for (Map.Entry<HexCoord, Map<SocialClassId, HouseholdEconomy>> entry : ruralByHex.entrySet()) {
      Map<SocialClassId, HouseholdEconomy> rows = entry.getValue();
      assertThat(rows.keySet())
          .as(
              "hex %s 的农村户必须恰好是 landed_peasant + landless_laborer（无 displaced / 旧四阶层）",
              entry.getKey())
          .containsExactlyInAnyOrder(SocialClassId.LANDED_PEASANT, SocialClassId.LANDLESS_LABORER);
      assertThat(rows.get(SocialClassId.LANDED_PEASANT).population())
          .as("hex %s 有地农民人口 = 160", entry.getKey())
          .isEqualTo(160L);
      assertThat(rows.get(SocialClassId.LANDLESS_LABORER).population())
          .as("hex %s 无地农民人口 = 40", entry.getKey())
          .isEqualTo(40L);
    }
  }

  @Test
  void allRuralLandBelongsToLandedHouseholdsAndLandlessHaveNone() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    Map<HouseholdId, HouseholdEconomy> landed = new LinkedHashMap<>();
    Map<HouseholdId, HouseholdEconomy> landless = new LinkedHashMap<>();
    for (HouseholdEconomy row : economy.classes().values()) {
      if (row.view().residence() != ResidenceKind.RURAL) {
        continue;
      }
      if (row.view().stratum().equals(SocialClassId.LANDED_PEASANT)) {
        landed.put(row.id(), row);
      } else if (row.view().stratum().equals(SocialClassId.LANDLESS_LABORER)) {
        landless.put(row.id(), row);
      }
    }
    assertThat(landed).as("32 个有地户").hasSize(32);
    assertThat(landless).as("32 个无地户").hasSize(32);

    LandComponentLedger landLedger =
        ArableSupplySnapshots.landLedgerFrom(state, "Rural32HexWorldFixtureTest");
    Map<HexCoord, Long> heldByHex = new LinkedHashMap<>();
    for (Map.Entry<LandHoldingKey, LandHolding> entry : landLedger.holdingsOrEmpty().entrySet()) {
      HexCoord hex = entry.getKey().hex();
      LandHolding holding = entry.getValue();
      heldByHex.merge(hex, holding.quantityMilliMu(), Math::addExact);
      assertThat(landed)
          .as("LAND 持有者必须是 LANDED 户: %s", holding.owner().value())
          .containsKey(holding.owner());
      assertThat(landless)
          .as("LANDLESS 户不得持有 LAND: %s", holding.owner().value())
          .doesNotContainKey(holding.owner());
    }
    for (HexCoord hex : ruralByHex(economy).keySet()) {
      long arable = landLedger.arableSupply().arableOfHex(hex).orElseThrow();
      assertThat(heldByHex.getOrDefault(hex, 0L))
          .as("hex %s 的 LAND 总量必须 == arableMilliMu（全归 landed）", hex)
          .isEqualTo(arable)
          .isPositive();
    }
  }

  @Test
  void landlessAreOnlyHiredLaborersInLandedRostersAndNeverOwnAFarmUnit() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    ProductionData production = productionOf(state);
    Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> ruralByHex = ruralByHex(economy);

    for (Map.Entry<HexCoord, Map<SocialClassId, HouseholdEconomy>> entry : ruralByHex.entrySet()) {
      HouseholdEconomy landed = entry.getValue().get(SocialClassId.LANDED_PEASANT);
      HouseholdEconomy landless = entry.getValue().get(SocialClassId.LANDLESS_LABORER);
      HouseholdClassMembership landedStanding = economy.classStandings().get(landed.id());
      HouseholdClassMembership landlessStanding = economy.classStandings().get(landless.id());
      assertThat(landedStanding).as("landed 户必须有 class standing").isNotNull();
      assertThat(landlessStanding).as("landless 户必须有 class standing").isNotNull();

      ProductionRoster hiredRoster =
          economy.productionRosters().values().stream()
              .filter(roster -> roster.organizerHousehold().equals(landed.id()))
              .filter(
                  roster ->
                      roster.participants().stream()
                          .anyMatch(
                              participant ->
                                  participant.household().equals(landless.id())
                                      && participant
                                          .role()
                                          .equals(
                                              ProductionRoleId.parse(
                                                  DefaultProductionModes.ROLE_HIRED_LABORER))))
              .findFirst()
              .orElse(null);
      assertThat(hiredRoster)
          .as("hex %s：landless 必须作为 landed farm roster 的 hired_laborer 参与项", entry.getKey())
          .isNotNull();
      assertThat(hiredRoster.participants())
          .filteredOn(participant -> participant.household().equals(landless.id()))
          .allMatch(participant -> !participant.organizer());
    }

    for (ProductionProcess instance : production.instances().values()) {
      assertThat(instance.operator().id())
          .as("landless 户不得拥有自己的 farm unit: %s", instance.id().value())
          .isNotIn(landlessIds(economy));
    }
  }

  @Test
  void everyRuralHexHasCarrierPlanRosterWageAnchorAndLargeHorseStock() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    ProductionData production = productionOf(state);
    ActorData actors = actorDataOf(state);
    Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> ruralByHex = ruralByHex(economy);
    MarketZoneId zone = economy.marketTables().keySet().stream().findFirst().orElseThrow();

    assertThat(economy.marketTables()).as("32hex 仍是单市场区").hasSize(1);
    assertThat(zone.value()).isEqualTo("single-region");
    assertThat(
            EconomySeeder.MARKET_PRICES_FACTORY.get(
                new io.mosire.simos.economy.api.id.CommodityId(
                    EconomySeeder.COMMODITY_TRANSPORT_HORSE)))
        .as("transport_horse 有正的创世价格")
        .isEqualTo(EconomySeeder.MARKET_PRICE_TRANSPORT_HORSE)
        .isPositive();
    assertThat(EconomySeeder.TRANSPORT_HORSE_OWNER_STOCK_MILLI)
        .as("32hex fixture 与 main seeder 使用同一马匹库存合同")
        .isEqualTo(WorldBalanceSeeder.TRANSPORT_HORSE_OWNER_STOCK_MILLI);

    for (Map.Entry<HexCoord, Map<SocialClassId, HouseholdEconomy>> entry : ruralByHex.entrySet()) {
      HexCoord hex = entry.getKey();
      HouseholdId landed = entry.getValue().get(SocialClassId.LANDED_PEASANT).id();
      var industryId = CaravanTransportTemplate.industryId(hex);
      assertThat(production.industries())
          .as("每 hex 有 caravan_transport 产业定义: %s", hex)
          .containsKey(industryId);
      var unit =
          production.instances().values().stream()
              .filter(instance -> instance.plan().equals(industryId))
              .findFirst()
              .orElseThrow(() -> new AssertionError("每 hex 有运输 unit: " + hex));
      assertThat(unit.operator())
          .as("运输 operator 是本 hex 既有 OWNER 家户")
          .isEqualTo(HouseholdActors.of(landed));
      ProductionRoster roster = economy.productionRosters().get(unit.id());
      assertThat(roster).as("运输 unit 有 maker/roster: %s", hex).isNotNull();
      assertThat(roster.participants())
          .extracting(participant -> participant.role())
          .as("运输 roster 同时有 caravan_operator 与 hired_driver")
          .contains(
              CaravanTransportTemplate.ROLES.get(0).id(),
              CaravanTransportTemplate.ROLES.get(1).id());

      HouseholdInventory account = actors.accounts().get(new HouseholdAccountKey(landed));
      assertThat(account).as("OWNER 户有 actor 库存账: %s", landed.value()).isNotNull();
      assertThat(account.frozenBalances()).as("马匹不冻结 ⇒ 现有卖侧可形成可售余量").isEmpty();
      assertThat(account.balances().getOrDefault(CaravanTransportTemplate.TRANSPORT_HORSE, 0L))
          .as("每 hex 既有 OWNER 户获得大库存马匹")
          .isEqualTo(EconomySeeder.TRANSPORT_HORSE_OWNER_STOCK_MILLI)
          .isPositive();
      assertThat(economy.productionWageAnchors())
          .as("hired_driver 走既有工资锚口径")
          .containsKey(
              new WageAnchorKey(zone, industryId, CaravanTransportTemplate.ROLES.get(1).id()));
    }

    assertThat(production.industries().keySet())
        .as("新世界不再 seed 旧 trade/haul 产业")
        .noneMatch(id -> id.value().startsWith(EconomySeeder.TRADE + "@"));
  }

  @Test
  void apiViewsExposeOwnershipHorseStockAndNamedMissingTransportReadouts() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    HexCoord sample = ruralByHex(economy).keySet().iterator().next();
    Map<String, Object> view = ApiViews.economyHex(sample, state);
    var classes = (List<Map<String, Object>>) view.get("classes");
    Map<String, Object> landedRow =
        classes.stream()
            .filter(row -> SocialClassId.LANDED_PEASANT.value().equals(row.get("slot")))
            .findFirst()
            .orElseThrow();
    assertThat(landedRow.get("ownershipClass")).isEqualTo("OWNER");
    assertThat(landedRow.get("transportHorseMilli"))
        .isEqualTo(EconomySeeder.TRANSPORT_HORSE_OWNER_STOCK_MILLI);
    assertThat(landedRow.get("transportHorseUnavailable")).isNull();
    var market = (Map<String, Object>) view.get("market");
    assertThat(market.get("transportPriceAnchor")).as("创世还没锚价 ⇒ 不伪造 0 价来源").isNull();
    assertThat(market.get("transportPriceAnchorUnavailable"))
        .isEqualTo("transport-price-anchor-missing");
    assertThat(market.get("transportCapacityBatches")).isEqualTo(List.of());
    assertThat(market.get("lastTransportByHex")).as("没有市场轮统计 ⇒ 不伪造观察").isNull();
    assertThat(market.get("lastTransportByHexUnavailable")).isEqualTo("market-round-stats-missing");
    assertThat(market.get("transportDsoUnavailable")).isEqualTo("market-round-stats-missing");
  }

  @Test
  void cityGovernmentAndMarketConfigurationStayAtTheExistingShape() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    long urbanPopulation =
        economy.classes().values().stream()
            .filter(row -> row.view().residence() == ResidenceKind.URBAN)
            .mapToLong(HouseholdEconomy::population)
            .sum();
    assertThat(urbanPopulation).as("首都 700 城镇人口仍在（城市四类池本轮不重写）").isGreaterThanOrEqualTo(700L);
    // 32hex 世界沿用创世的派生单区形态：价表键 = single-region（成员格 = 全部有市场的格），
    // 持久 marketZones 为空是既有配置，不是丢掉市场。
    assertThat(economy.marketZones()).as("32hex 沿用派生单区形态（持久区为空）").isEmpty();
    assertThat(economy.governments()).as("单地方政府配置保持").hasSize(1);
    assertThat(economy.marketTables()).as("单市场区价表仍在").hasSize(1);
    assertThat(economy.marketTables().keySet().iterator().next().value())
        .as("价表键仍是 single-region（与创世 MarketSeeder 同源）")
        .isEqualTo("single-region");

    assertThat(
            java.util.Arrays.stream(
                    io.mosire.simos.production.contract.ProductionModeDefinitionView.class
                        .getRecordComponents())
                .map(component -> component.getName()))
        .as("C1 后 mode 定义不再持久化 class nature；ownershipClass 由资产派生")
        .doesNotContain("supportedClassNatures");
  }

  @Test
  void everyHouseholdGetsAUniformDefaultCultureLabel() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    CultureSnapshot culture = (CultureSnapshot) state.module("culture").orElseThrow();
    for (HouseholdId household : economy.classes().keySet()) {
      HouseholdCulture label = culture.state().householdCultures().get(household);
      assertThat(label)
          .as("家户 %s 必须有默认文化 label（R1 merge key 的真实靶子）", household.value())
          .isNotNull();
      assertThat(label.label()).as("默认文化 label 非空").isNotBlank();
    }
    assertThat(culture.state().householdCultures())
        .as("全部家户都写了文化标签")
        .hasSameSizeAs(economy.classes());
  }

  @Test
  void mapArableComponentsUseCoordAwareIdsAndTheSameOwnerAsTheEconomyRows() {
    SimulationState state = Rural32HexWorld.state(MAP_ID);
    EconomyData economy = economyOf(state);
    LandComponentLedger ledger =
        ArableSupplySnapshots.landLedgerFrom(state, "Rural32HexWorldFixtureTest");
    Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> ruralByHex = ruralByHex(economy);

    assertThat(ledger.componentsByHex()).as("32 格都必须有 Map arable component").hasSize(32);
    for (Map.Entry<HexCoord, io.mosire.simos.map.HexLand> entry :
        ledger.componentsByHex().entrySet()) {
      HexCoord hex = entry.getKey();
      var area = entry.getValue().byType().get(LandTypeId.ARABLE);
      assertThat(area).as("hex %s 必须有 arable 类型", hex).isNotNull();
      assertThat(area.components())
          .as("hex %s 的 arable 必须恰好一个 owner component（本世界一户有地）", hex)
          .singleElement()
          .satisfies(
              component -> {
                assertThat(component.id().value())
                    .as("component id 必须用真实格坐标，不是占位 0_0")
                    .startsWith("arable@" + hex.q() + "_" + hex.r() + ":")
                    .endsWith(":1");
                assertThat(component.attributes().get(LandAttributeKey.OWNER).stringValue())
                    .as("Map owner 必须与同一份 Seed 落下的 economy landed 户同源")
                    .isEqualTo(ruralByHex.get(hex).get(SocialClassId.LANDED_PEASANT).id().value());
              });
      assertThat(area.totalMilliMu())
          .as("hex %s 的 Map 总量 = 账本派生 arable", hex)
          .isEqualTo(ledger.arableSupply().arableOfHex(hex).orElseThrow());
    }
  }

  private static List<String> landlessIds(EconomyData economy) {
    List<String> ids = new ArrayList<>();
    for (HouseholdEconomy row : economy.classes().values()) {
      if (row.view().residence() == ResidenceKind.RURAL
          && row.view().stratum().equals(SocialClassId.LANDLESS_LABORER)) {
        ids.add(row.id().value());
      }
    }
    return ids;
  }

  private static Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> ruralByHex(
      EconomyData economy) {
    Map<HexCoord, Map<SocialClassId, HouseholdEconomy>> grouped = new LinkedHashMap<>();
    for (HouseholdEconomy row : economy.classes().values()) {
      if (row.view().residence() != ResidenceKind.RURAL) {
        continue;
      }
      grouped
          .computeIfAbsent(row.view().hex(), ignored -> new LinkedHashMap<>())
          .put(row.view().stratum(), row);
    }
    return grouped;
  }

  private static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  private static ActorData actorDataOf(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }
}
