package io.mosire.simos.app.inventory;

import static io.mosire.simos.app.inventory.InventoryTestFixtures.actorData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableComponent;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableLand;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.attrs;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.commodity;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.components;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.economyData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.household;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.inventory;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.mapWith;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.ownerAttrs;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.statefulAttrs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotOrigin;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * D-032 T1（U1a）：统一库存视图 builder 的正例 / 负例。
 *
 * <p>判据来自任务书 §8.1 / §3.6：商品行（正余额 / 0 余额）、ARABLE 土地行、同 owner 多状态 component 两行、稳定排序、缺 OWNER / 不可解析
 * OWNER / 重复 lotId / 负数量 / 无位置负例。
 *
 * <p>变异自证锚点：{@link #missingOwnerAttributeIsRejectedNamed()} 在“删掉 owner 缺失 fail-closed”的改坏下必须变红。
 */
class InventoryLotViewBuilderTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HouseholdId H1 = household("h1");
  private static final HouseholdId H2 = household("h2");
  private static final CommodityId GRAIN = commodity("grain");

  @Test
  void commodityAndLandRowsFromMinimalWorld() {
    ActorData actor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)));
    EconomyData economy = economyData(H1, HEX);
    GameMap map =
        mapWith(
            HEX,
            arableLand(components(arableComponent("arable@0_0:0", 10_000L, ownerAttrs("h1"))), 1L));

    InventoryLotView view = InventoryLotViewBuilder.build(actor, economy, map);

    assertThat(view.lots()).hasSize(2);
    assertThat(view.lots())
        .extracting(lot -> lot.itemKind())
        .containsExactly(InventoryLotKind.COMMODITY, InventoryLotKind.LAND);

    InventoryLot commodityRow = view.byKind(InventoryLotKind.COMMODITY).get(0);
    assertThat(commodityRow.lotId()).isEqualTo(InventoryLotId.forCommodity(H1, HEX, GRAIN));
    assertThat(commodityRow.itemTypeId()).isEqualTo("grain");
    assertThat(commodityRow.owner()).isEqualTo(H1);
    assertThat(commodityRow.location()).contains(HEX);
    assertThat(commodityRow.quantityMilli()).isEqualTo(1_000L);
    assertThat(commodityRow.state()).isEqualTo(LotState.COMPLETE);
    assertThat(commodityRow.fungible()).isTrue();
    assertThat(commodityRow.origin().kind())
        .isEqualTo(io.mosire.simos.economy.api.inventory.LotOriginKind.CURRENT_STOCK);

    InventoryLot landRow = view.byKind(InventoryLotKind.LAND).get(0);
    assertThat(landRow.lotId())
        .isEqualTo(InventoryLotId.forLand(new LandComponentId("arable@0_0:0")));
    assertThat(landRow.itemTypeId()).isEqualTo("arable");
    assertThat(landRow.owner()).isEqualTo(H1);
    assertThat(landRow.location()).contains(HEX);
    assertThat(landRow.quantityMilli()).isEqualTo(10_000L);
    assertThat(landRow.state()).isEqualTo(LotState.IDLE);
    assertThat(landRow.fungible()).isFalse();

    assertThat(view.require(commodityRow.lotId())).isEqualTo(commodityRow);
  }

  @Test
  void buildLandOnlyProjectsOnlyLandRowsWithoutActorOrEconomy() {
    GameMap map =
        mapWith(
            HEX,
            arableLand(
                components(
                    arableComponent(
                        "arable@0_0:0",
                        10_000L,
                        statefulAttrs("h1", "grain_farm@0_0", "stage1", 8L, 8L, true)),
                    arableComponent("arable@0_0:1", 1_000L, ownerAttrs("h1"))),
                2L));

    // 不构造 actor/economy：LAND-only 入口只依赖 map。
    InventoryLotView view = InventoryLotViewBuilder.buildLandOnly(map);

    assertThat(view.lots()).hasSize(2);
    assertThat(view.lots()).extracting(InventoryLot::itemKind).containsOnly(InventoryLotKind.LAND);
    assertThat(view.lots())
        .extracting(row -> row.lotId().value())
        .containsExactly("land:arable@0_0:0", "land:arable@0_0:1");
    assertThat(view.lots().get(0).state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.planId()).isEqualTo("grain_farm@0_0");
              assertThat(state.stageId()).isEqualTo("stage1");
              assertThat(state.stageProgressDays()).isEqualTo(8L);
              assertThat(state.cycleProgressDays()).isEqualTo(8L);
            });
    assertThat(view.lots().get(1).state()).isEqualTo(LotState.IDLE);
  }

  @Test
  void sameOwnerMultipleStateComponentsProduceTwoRows() {
    ActorData actor = ActorData.empty();
    EconomyData economy = economyData(H1, HEX);
    HexLand land =
        arableLand(
            components(
                arableComponent(
                    "arable@0_0:0",
                    10_000L,
                    // CROP + STAGE 必须同时给；只给 STAGE 会按新架构具名拒（见 incomplete 用例）。
                    attrs(
                        LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
                        LandAttributeKey.CROP, LandAttributeValue.ofString("grain_farm@0_0"),
                        LandAttributeKey.STAGE, LandAttributeValue.ofString("stage2"),
                        LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(10L))),
                arableComponent(
                    "arable@0_0:1",
                    1_000L,
                    attrs(
                        LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
                        LandAttributeKey.CROP, LandAttributeValue.ofString("grain_farm@0_0"),
                        LandAttributeKey.STAGE, LandAttributeValue.ofString("stage0"),
                        LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(0L)))),
            2L);

    InventoryLotView view = InventoryLotViewBuilder.build(actor, economy, mapWith(HEX, land));

    List<InventoryLot> landRows = view.byKind(InventoryLotKind.LAND);
    assertThat(landRows).as("同 owner 多状态 component 不得聚合丢失").hasSize(2);
    assertThat(landRows)
        .extracting(row -> row.lotId().value())
        .containsExactly("land:arable@0_0:0", "land:arable@0_0:1");
    assertThat(landRows).extracting(InventoryLot::quantityMilli).containsExactly(10_000L, 1_000L);
    assertThat(landRows).allSatisfy(row -> assertThat(row.owner()).isEqualTo(H1));
    assertThat(landRows)
        .allSatisfy(
            row ->
                assertThat(row.state())
                    .isInstanceOfSatisfying(
                        LotState.InProduction.class,
                        state -> {
                          assertThat(state.planId()).isEqualTo("grain_farm@0_0");
                        }));
  }

  @Test
  void tenThousandThreeThousandOneThousandArableStagesAndIdleProduceThreeRows() {
    ActorData actor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)));
    EconomyData economy = economyData(H1, HEX);
    HexLand land =
        arableLand(
            components(
                arableComponent(
                    "arable@0_0:0",
                    10_000L,
                    statefulAttrs("h1", "grain_farm@0_0", "stage1", 8L, 8L, true)),
                arableComponent(
                    "arable@0_0:1",
                    3_000L,
                    statefulAttrs("h1", "grain_farm@0_0", "stage2", 27L, 37L, false)),
                arableComponent("arable@0_0:2", 1_000L, ownerAttrs("h1"))),
            3L);

    InventoryLotView view = InventoryLotViewBuilder.build(actor, economy, mapWith(HEX, land));

    // 商品行不变：生产状态只影响 LAND 行。
    InventoryLot commodityRow = view.byKind(InventoryLotKind.COMMODITY).get(0);
    assertThat(commodityRow.itemTypeId()).isEqualTo("grain");
    assertThat(commodityRow.quantityMilli()).isEqualTo(1_000L);
    assertThat(commodityRow.state()).isEqualTo(LotState.COMPLETE);
    assertThat(commodityRow.fungible()).isTrue();

    List<InventoryLot> landRows = view.byKind(InventoryLotKind.LAND);
    assertThat(landRows).as("10/3/1 三块地必须是三行").hasSize(3);
    assertThat(landRows)
        .extracting(row -> row.lotId().value())
        .containsExactly("land:arable@0_0:0", "land:arable@0_0:1", "land:arable@0_0:2");

    InventoryLot rowA = landRows.get(0);
    assertThat(rowA.owner()).isEqualTo(H1);
    assertThat(rowA.quantityMilli()).isEqualTo(10_000L);
    assertThat(rowA.state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.planId()).isEqualTo("grain_farm@0_0");
              assertThat(state.stageId()).isEqualTo("stage1");
              assertThat(state.stageProgressDays()).isEqualTo(8L);
              assertThat(state.cycleProgressDays()).isEqualTo(8L);
              assertThat(state.startInputPaid()).isTrue();
              assertThat(state.facts()).isEmpty();
              assertThat(state.cycleInputUsedMilli()).isEmpty();
            });

    InventoryLot rowB = landRows.get(1);
    assertThat(rowB.owner()).isEqualTo(H1);
    assertThat(rowB.quantityMilli()).isEqualTo(3_000L);
    assertThat(rowB.state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.planId()).isEqualTo("grain_farm@0_0");
              assertThat(state.stageId()).isEqualTo("stage2");
              assertThat(state.stageProgressDays()).isEqualTo(27L);
              assertThat(state.cycleProgressDays()).isEqualTo(37L);
              assertThat(state.startInputPaid()).isFalse();
            });

    InventoryLot rowC = landRows.get(2);
    assertThat(rowC.owner()).isEqualTo(H1);
    assertThat(rowC.itemKind()).isEqualTo(InventoryLotKind.LAND);
    assertThat(rowC.location()).contains(HEX);
    assertThat(rowC.quantityMilli()).isEqualTo(1_000L);
    assertThat(rowC.state()).as("无 CROP/STAGE ⇒ IDLE").isEqualTo(LotState.IDLE);
  }

  @Test
  void partialProductionStateWithoutCropOrStageIsRejectedNamedIncomplete() {
    ActorData actor = ActorData.empty();
    EconomyData economy = economyData(H1, HEX);

    Map<LandAttributeKey, LandAttributeValue> stageOnly =
        attrs(
            LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
            LandAttributeKey.STAGE, LandAttributeValue.ofString("stage1"));
    assertThatThrownBy(
            () ->
                InventoryLotViewBuilder.build(
                    actor,
                    economy,
                    mapWith(
                        HEX,
                        arableLand(
                            components(arableComponent("arable@0_0:0", 10_000L, stageOnly)), 1L))))
        .as("缺 CROP")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-state-incomplete")
        .hasMessageContaining("crop");

    Map<LandAttributeKey, LandAttributeValue> cropOnly =
        attrs(
            LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
            LandAttributeKey.CROP, LandAttributeValue.ofString("grain_farm@0_0"));
    assertThatThrownBy(
            () ->
                InventoryLotViewBuilder.build(
                    actor,
                    economy,
                    mapWith(
                        HEX,
                        arableLand(
                            components(arableComponent("arable@0_0:0", 10_000L, cropOnly)), 1L))))
        .as("缺 STAGE")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-state-incomplete")
        .hasMessageContaining("stage");
  }

  @Test
  void wrongStateAttributeTypeIsRejectedNamedInvalid() {
    ActorData actor = ActorData.empty();
    EconomyData economy = economyData(H1, HEX);

    Map<LandAttributeKey, LandAttributeValue> badCrop =
        attrs(
            LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
            LandAttributeKey.CROP, LandAttributeValue.ofLong(7L),
            LandAttributeKey.STAGE, LandAttributeValue.ofString("stage1"));
    assertThatThrownBy(
            () ->
                InventoryLotViewBuilder.build(
                    actor,
                    economy,
                    mapWith(
                        HEX,
                        arableLand(
                            components(arableComponent("arable@0_0:0", 10_000L, badCrop)), 1L))))
        .as("CROP 必须是 STRING")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-state-invalid")
        .hasMessageContaining("key=crop")
        .hasMessageContaining("attributeKind=LONG");

    Map<LandAttributeKey, LandAttributeValue> badProgress =
        attrs(
            LandAttributeKey.OWNER, LandAttributeValue.ofString("h1"),
            LandAttributeKey.CROP, LandAttributeValue.ofString("grain_farm@0_0"),
            LandAttributeKey.STAGE, LandAttributeValue.ofString("stage1"),
            LandAttributeKey.PROGRESS, LandAttributeValue.ofString("8"));
    assertThatThrownBy(
            () ->
                InventoryLotViewBuilder.build(
                    actor,
                    economy,
                    mapWith(
                        HEX,
                        arableLand(
                            components(arableComponent("arable@0_0:0", 10_000L, badProgress)),
                            1L))))
        .as("PROGRESS 必须是 LONG")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-state-invalid")
        .hasMessageContaining("key=progress")
        .hasMessageContaining("attributeKind=STRING");
  }

  @Test
  void malformedCycleInputAttributesAreRejectedNamedInvalid() {
    assertInvalidCycleInput(
        new LandAttributeKey("cycle_input:"),
        LandAttributeValue.ofLong(1L),
        "cycle-input-commodity-blank");
    assertInvalidCycleInput(
        new LandAttributeKey("cycle_input:grain"),
        LandAttributeValue.ofString("7"),
        "key=cycle_input:grain",
        "attributeKind=STRING");
    assertInvalidCycleInput(
        new LandAttributeKey("cycle_input:grain"),
        LandAttributeValue.ofLong(-1L),
        "cycle-input-negative");
  }

  @Test
  void zeroBalanceProducesNoCommodityRowButNegativeBalanceIsRejected() {
    ActorData actor = actorData(inventory(H1, Map.of(GRAIN, 0L)));
    EconomyData economy = economyData(H1, HEX);

    InventoryLotView view =
        InventoryLotViewBuilder.build(actor, economy, InventoryTestFixtures.emptyMap());
    assertThat(view.lots()).as("0 余额不生成商品行").isEmpty();

    assertThatThrownBy(() -> inventory(H1, Map.of(GRAIN, -1L)))
        .as("负余额在账本构造期即拒")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stableSortingAcrossRepeatedBuilds() {
    ActorData actor =
        actorData(inventory(H2, Map.of(GRAIN, 500L)), inventory(H1, Map.of(GRAIN, 1_000L)));
    EconomyData economy =
        economyData(new java.util.LinkedHashMap<>(Map.of(H1, HEX, H2, new HexCoord(2, 0))));
    GameMap map =
        mapWith(
            HEX,
            arableLand(components(arableComponent("arable@0_0:0", 10_000L, ownerAttrs("h1"))), 1L));

    InventoryLotView first = InventoryLotViewBuilder.build(actor, economy, map);
    InventoryLotView second = InventoryLotViewBuilder.build(actor, economy, map);

    assertThat(first.lots()).as("两次 build 逐值相同").isEqualTo(second.lots());
    assertThat(first.lots())
        .extracting(lot -> lot.lotId().value())
        .as("稳定键：kind → type → location → owner → lotId")
        .containsExactly("commodity:h1:0_0:grain", "commodity:h2:2_0:grain", "land:arable@0_0:0");
  }

  @Test
  void missingOwnerAttributeIsRejectedNamed() {
    ActorData actor = ActorData.empty();
    EconomyData economy = economyData(H1, HEX);
    HexLand land =
        arableLand(
            components(
                arableComponent(
                    "arable@0_0:0",
                    10_000L,
                    attrs(LandAttributeKey.CROP, LandAttributeValue.ofString("wheat")))),
            1L);

    assertThatThrownBy(() -> InventoryLotViewBuilder.build(actor, economy, mapWith(HEX, land)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-owner-missing");
  }

  @Test
  void unparseableOwnerAttributeIsRejectedNamed() {
    ActorData actor = ActorData.empty();
    EconomyData economy = economyData(H1, HEX);
    HexLand land =
        arableLand(
            components(
                arableComponent(
                    "arable@0_0:0",
                    10_000L,
                    attrs(LandAttributeKey.OWNER, LandAttributeValue.ofLong(42L)))),
            1L);

    assertThatThrownBy(() -> InventoryLotViewBuilder.build(actor, economy, mapWith(HEX, land)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-owner-missing")
        .hasMessageContaining("ownerAttributeKind=LONG");
  }

  @Test
  void duplicateLotIdRejectedByView() {
    InventoryLot lot = commodityLot(H1, HEX, GRAIN, 1_000L);

    assertThatThrownBy(() -> new InventoryLotView(List.of(lot, lot)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-duplicate-id");
  }

  @Test
  void negativeOrZeroQuantityAndLandWithoutLocationRejected() {
    assertThatThrownBy(() -> commodityLot(H1, HEX, GRAIN, 0L))
        .as("0 数量")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantityMilli 必须大于 0");
    assertThatThrownBy(() -> commodityLot(H1, HEX, GRAIN, -5L))
        .as("负数量")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantityMilli 必须大于 0");
    assertThatThrownBy(
            () ->
                new InventoryLot(
                    InventoryLotId.forLand(new LandComponentId("arable@0_0:0")),
                    InventoryLotKind.LAND,
                    "arable",
                    H1,
                    Optional.empty(),
                    10_000L,
                    LotState.IDLE,
                    LotOrigin.currentStock(0L),
                    false))
        .as("LAND 无 location")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-land-location-missing");
  }

  @Test
  void commodityBalanceWithoutEconomyLocationIsRejectedNamed() {
    ActorData actor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)));
    EconomyData economy = EconomyData.empty();

    assertThatThrownBy(
            () -> InventoryLotViewBuilder.build(actor, economy, InventoryTestFixtures.emptyMap()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-location-missing")
        .hasMessageContaining("household=h1");
  }

  private static void assertInvalidCycleInput(
      LandAttributeKey cycleInputKey, LandAttributeValue value, String... expectedFragments) {
    Map<LandAttributeKey, LandAttributeValue> attributes =
        attrs(
            LandAttributeKey.OWNER,
            LandAttributeValue.ofString("h1"),
            LandAttributeKey.CROP,
            LandAttributeValue.ofString("grain_farm@0_0"),
            LandAttributeKey.STAGE,
            LandAttributeValue.ofString("stage1"),
            cycleInputKey,
            value);
    var thrown =
        assertThatThrownBy(
            () ->
                InventoryLotViewBuilder.build(
                    ActorData.empty(),
                    economyData(H1, HEX),
                    mapWith(
                        HEX,
                        arableLand(
                            components(arableComponent("arable@0_0:0", 10_000L, attributes)),
                            1L))));
    thrown
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("inventory-lot-state-invalid");
    for (String fragment : expectedFragments) {
      thrown.hasMessageContaining(fragment);
    }
  }

  private static InventoryLot commodityLot(
      HouseholdId owner, HexCoord location, CommodityId commodity, long quantityMilli) {
    return new InventoryLot(
        InventoryLotId.forCommodity(owner, location, commodity),
        InventoryLotKind.COMMODITY,
        commodity.value(),
        owner,
        Optional.of(location),
        quantityMilli,
        LotState.COMPLETE,
        LotOrigin.currentStock(0L),
        true);
  }
}
