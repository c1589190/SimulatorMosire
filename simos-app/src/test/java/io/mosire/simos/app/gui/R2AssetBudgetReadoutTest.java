package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.economy.model.WageQuoteSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D4-Ib2b 经济格只读视图</b>：{@code ApiViews.economyHex} 必须把 {@code assetOccupancies}/{@code
 * inventoryReservations}/{@code productionWagePostings} 作为**只读派生视图** 发出，且同时满足：
 *
 * <ul>
 *   <li><b>稳定序</b>：与状态表插入序无关；同一 revision 反复读得到同一顺序；
 *   <li><b>本格过滤</b>：只发焦点格的事实，邻格事实不得混入；
 *   <li><b>空表合法</b>：三类事实都为空时发空数组（不是缺键、不是 null、不是抛错）。
 * </ul>
 *
 * <p>★ 本文件取代旧 {@code AssetBudgetBook} 读口断言：{@code assetOccupancies} 从 grant + 份额 + 实例派生， {@code
 * inventoryReservations} 从预留事实派生，{@code productionWagePostings} 从执行期 posting 派生；三者都不得在 读口重算分配或改写状态。
 */
class R2AssetBudgetReadoutTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);
  private static final ProductionLocation HEX_LOCATION = ProductionLocation.parse("0_0");
  private static final ProductionLocation OTHER_LOCATION = ProductionLocation.parse("1_0");
  private static final IndustryId FARM = IndustryHexKeys.id("r2-readout-farm", 0, 0);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, "org-r2-readout");
  private static final ActorRef PAYER = new ActorRef(ActorKind.HOUSEHOLD, "household-r2-readout");
  private static final ProductionUnitId FIRST = new ProductionUnitId("unit-a-readout-first");
  private static final ProductionUnitId SECOND = new ProductionUnitId("unit-b-readout-second");
  private static final ProductionUnitId OTHER = new ProductionUnitId("unit-c-readout-other");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final long LAND = 100L;

  @Test
  void economyHexEmitsOnlyThisHexesReadoutFactsInStableOrder() {
    SimulationState state = stateWithFacts();

    @SuppressWarnings("unchecked")
    Map<String, Object> body = ApiViews.economyHex(HEX, state);
    assertThat(body)
        .as("D4-Ib2b：三类新只读栏必须都在 economyHex 中")
        .containsKeys("assetOccupancies", "inventoryReservations", "productionWagePostings");

    List<Map<String, Object>> occupancies = rows(body, "assetOccupancies");
    assertThat(occupancies).as("只发焦点格；邻格份额的占用不得混入").hasSize(3);
    assertThat(occupancies)
        .extracting(row -> row.get("unit"), row -> row.get("fromTick"))
        .as("稳定序 = unit → share → fromTick → toTickExclusive → grantId")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), 2L),
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), 10L),
            org.assertj.core.groups.Tuple.tuple(SECOND.value(), 5L));
    assertThat(fieldNames(occupancies.get(0)))
        .as("占用行只读键集")
        .containsExactly(
            "grantId",
            "shareId",
            "industry",
            "assetKind",
            "owner",
            "operator",
            "location",
            "unit",
            "quantity",
            "fromTick",
            "toTickExclusive",
            "status");
    assertThat(occupancies.get(0))
        .containsEntry("industry", FARM.value())
        .containsEntry("assetKind", "LAND")
        .containsEntry("location", HEX_LOCATION.value())
        .containsEntry("unit", FIRST.value())
        .containsEntry("quantity", 4L)
        .containsEntry("fromTick", 2L)
        .containsEntry("toTickExclusive", 10L)
        .containsEntry("status", "ACTIVE");
    assertThat(fieldNames((Map<String, Object>) occupancies.get(0).get("owner")))
        .as("嵌套主体键序固定")
        .containsExactly("kind", "id");

    List<Map<String, Object>> reservations = rows(body, "inventoryReservations");
    assertThat(reservations).as("只发焦点格；邻格预留不得混入").hasSize(3);
    assertThat(reservations)
        .extracting(row -> row.get("unit"), row -> row.get("fromTick"))
        .as("稳定序 = unit → payer → commodity → location → fromTick → toTickExclusive → id")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), 2L),
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), 10L),
            org.assertj.core.groups.Tuple.tuple(SECOND.value(), 5L));
    assertThat(fieldNames(reservations.get(0)))
        .as("库存预留行只读键集")
        .containsExactly(
            "id",
            "unit",
            "payer",
            "location",
            "commodity",
            "quantity",
            "fromTick",
            "toTickExclusive",
            "status",
            "releasedAtTick");
    assertThat(reservations.get(0))
        .containsEntry("unit", FIRST.value())
        .containsEntry("location", HEX_LOCATION.value())
        .containsEntry("commodity", GRAIN.value())
        .containsEntry("quantity", 7L)
        .containsEntry("status", "ACTIVE")
        .containsEntry("releasedAtTick", -1L);
    assertThat(fieldNames((Map<String, Object>) reservations.get(0).get("payer")))
        .as("付款人嵌套主体键序固定")
        .containsExactly("kind", "id");

    List<Map<String, Object>> postings = rows(body, "productionWagePostings");
    assertThat(postings).as("只发本格实例的 posting；邻格实例的 posting 不得混入").hasSize(3);
    assertThat(postings)
        .extracting(row -> row.get("unit"), row -> row.get("role"))
        .as("稳定序 = unit → role")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), "alpha"),
            org.assertj.core.groups.Tuple.tuple(FIRST.value(), "zeta"),
            org.assertj.core.groups.Tuple.tuple(SECOND.value(), "beta"));
    assertThat(fieldNames(postings.get(0)))
        .as("工资 posting 行只读键集")
        .containsExactly(
            "unit",
            "role",
            "targetLaborMilliPerDay",
            "currency",
            "wagePerLaborMilli",
            "quoteSource",
            "shortageStreak",
            "satisfiedStreak",
            "lastAdjustedTick",
            "lastAdjustmentDelta",
            "lastObservedMatchedLabor",
            "lastObservedEffectiveTarget",
            "lastMaxAbsorbable");
    assertThat(postings.get(0))
        .containsEntry("unit", FIRST.value())
        .containsEntry("role", "alpha")
        .containsEntry("targetLaborMilliPerDay", 1_000L)
        .containsEntry("currency", "silver")
        .containsEntry("wagePerLaborMilli", 3L)
        .containsEntry("quoteSource", "EXPLICIT");
  }

  @Test
  void emptyReadoutTablesAreLegal() {
    @SuppressWarnings("unchecked")
    Map<String, Object> body = ApiViews.economyHex(HEX, state());

    assertThat(rows(body, "assetOccupancies")).as("无占用 ⇒ 空表，不是缺键/ null").isEmpty();
    assertThat(rows(body, "inventoryReservations")).as("无预留 ⇒ 空表，不是缺键/ null").isEmpty();
    assertThat(rows(body, "productionWagePostings")).as("无 posting ⇒ 空表，不是缺键/ null").isEmpty();
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  private static SimulationState stateWithFacts() {
    IndustryDefinitionView plan = plan();
    ProductionData production =
        ProductionData.empty()
            .withIndustry(plan)
            .withInstance(unit(FIRST, HEX_LOCATION))
            .withInstance(unit(SECOND, HEX_LOCATION))
            .withInstance(unit(OTHER, OTHER_LOCATION));

    OwnershipStake firstShare = share(FIRST, HEX_LOCATION, 0L);
    OwnershipStake secondShare = share(SECOND, HEX_LOCATION, 1L);
    OwnershipStake otherShare = share(OTHER, OTHER_LOCATION, 2L);

    AssetOccupancyGrant firstAtTwo = occupancy(firstShare.id(), FIRST, 4L, 2L, 10L);
    AssetOccupancyGrant firstAtTen = occupancy(firstShare.id(), FIRST, 6L, 10L, 30L);
    AssetOccupancyGrant secondAtFive = occupancy(secondShare.id(), SECOND, 8L, 5L, 15L);
    AssetOccupancyGrant otherAtOne = occupancy(otherShare.id(), OTHER, 9L, 1L, 11L);
    // 插入序刻意乱序；读口必须自行稳定排序。
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> grants = new LinkedHashMap<>();
    grants.put(otherAtOne.id(), otherAtOne);
    grants.put(secondAtFive.id(), secondAtFive);
    grants.put(firstAtTen.id(), firstAtTen);
    grants.put(firstAtTwo.id(), firstAtTwo);

    InventoryReservation firstReservationAtTwo =
        reservation(FIRST, HEX_LOCATION, GRAIN, 7L, 2L, 12L);
    InventoryReservation firstReservationAtTen =
        reservation(FIRST, HEX_LOCATION, GRAIN, 5L, 10L, 20L);
    InventoryReservation secondReservationAtFive =
        reservation(SECOND, HEX_LOCATION, GRAIN, 3L, 5L, 15L);
    InventoryReservation otherReservationAtOne =
        reservation(OTHER, OTHER_LOCATION, GRAIN, 2L, 1L, 11L);
    Map<InventoryReservationId, InventoryReservation> reservations = new LinkedHashMap<>();
    reservations.put(otherReservationAtOne.id(), otherReservationAtOne);
    reservations.put(secondReservationAtFive.id(), secondReservationAtFive);
    reservations.put(firstReservationAtTen.id(), firstReservationAtTen);
    reservations.put(firstReservationAtTwo.id(), firstReservationAtTwo);

    ProductionWagePosting firstZeta = posting(FIRST, "zeta", 900L, 4L);
    ProductionWagePosting firstAlpha = posting(FIRST, "alpha", 1_000L, 3L);
    ProductionWagePosting secondBeta = posting(SECOND, "beta", 800L, 2L);
    ProductionWagePosting otherAlpha = posting(OTHER, "alpha", 700L, 1L);
    Map<WagePostingKey, ProductionWagePosting> postings = new LinkedHashMap<>();
    postings.put(otherAlpha.key(), otherAlpha);
    postings.put(secondBeta.key(), secondBeta);
    postings.put(firstZeta.key(), firstZeta);
    postings.put(firstAlpha.key(), firstAlpha);

    EconomyData economy =
        EconomyData.empty()
            .withMeta(
                Optional.of(
                    new EconomyMeta(
                        "r2-readout",
                        0L,
                        OptionalLong.empty(),
                        EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2,
                        Optional.empty())))
            .withOwnershipStakes(
                Map.of(
                    firstShare.id(),
                    firstShare,
                    secondShare.id(),
                    secondShare,
                    otherShare.id(),
                    otherShare))
            .withAssetOccupancies(grants)
            .withInventoryReservations(reservations)
            .withProductionWagePostings(postings);
    return state(production, economy);
  }

  private static SimulationState state() {
    return state(ProductionData.empty(), EconomyData.empty());
  }

  private static SimulationState state(ProductionData production, EconomyData economy) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, T0, economy));
    modules.put("production", new ProductionSnapshot(REF, T0, production));
    modules.put("actor", new ActorSnapshot(REF, T0, ActorData.empty()));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static OwnershipStake share(
      ProductionUnitId unit, ProductionLocation location, long sequence) {
    AssetShareId id =
        OwnershipStake.idOf(
            FARM, AssetKind.LAND, OPERATOR, OPERATOR, AssetRightKind.OWNED, location, sequence);
    return new OwnershipStake(
        id, FARM, AssetKind.LAND, OPERATOR, OPERATOR, LAND, AssetRightKind.OWNED, location);
  }

  private static AssetOccupancyGrant occupancy(
      AssetShareId shareId,
      ProductionUnitId unit,
      long quantity,
      long fromTick,
      long toTickExclusive) {
    return new AssetOccupancyGrant(
        AssetOccupancyGrantId.idOf(unit, shareId, fromTick),
        shareId,
        unit,
        quantity,
        fromTick,
        toTickExclusive,
        AssetOccupancyStatus.ACTIVE,
        -1L);
  }

  private static InventoryReservation reservation(
      ProductionUnitId unit,
      ProductionLocation location,
      CommodityId commodity,
      long quantity,
      long fromTick,
      long toTickExclusive) {
    return new InventoryReservation(
        InventoryReservationId.idOf(unit, PAYER, location, commodity, fromTick),
        unit,
        PAYER,
        location,
        commodity,
        quantity,
        fromTick,
        toTickExclusive,
        InventoryReservationStatus.ACTIVE,
        -1L);
  }

  private static ProductionWagePosting posting(
      ProductionUnitId unit, String role, long targetLaborMilliPerDay, long wagePerLaborMilli) {
    return new ProductionWagePosting(
        unit,
        new ProductionRoleId(role),
        targetLaborMilliPerDay,
        new io.mosire.simos.economy.api.id.CurrencyId("silver"),
        wagePerLaborMilli,
        WageQuoteSource.EXPLICIT,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L);
  }

  private static ProductionProcess unit(ProductionUnitId id, ProductionLocation location) {
    return new ProductionProcess(
        id,
        FARM,
        location,
        OPERATOR,
        FARM.value(),
        0L,
        new LinkedHashMap<>(),
        new LinkedHashMap<>());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
    return (List<Map<String, Object>>) body.get(key);
  }

  private static List<String> fieldNames(Map<String, Object> row) {
    return new ArrayList<>(row.keySet());
  }

  private static IndustryDefinitionView plan() {
    return new IndustryDefinitionView(
        FARM,
        "R2 读口夹具",
        new RegimeId("tenant"),
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        1_000L,
        Map.of(new CommodityId("grain"), 5L),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1_000)),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }
}
