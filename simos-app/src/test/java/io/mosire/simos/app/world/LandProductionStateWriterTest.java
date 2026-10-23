package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L2a：LAND 生产状态写回原语的独立验收。
 *
 * <p>写回 → Builder 读回必须逐字段一致；IDLE 必须清空生产属性与全部 cycle_input:*；失败必须具名且原图不变。
 */
class LandProductionStateWriterTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HouseholdId HOUSEHOLD_OWNER = new HouseholdId("h1");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId FIBER = new CommodityId("fiber");
  private static final LandAttributeKey FERTILITY = LandAttributeKey.FERTILITY;
  private static final LandAttributeKey NOTE = new LandAttributeKey("note");
  private static final LandAttributeKey IRRIGATION = new LandAttributeKey("irrigation");

  @Test
  void writesThreeArableLotsAndBuilderReadsThemBack() {
    LandComponentId aId = id("arable@0_0:0");
    LandComponentId bId = id("arable@0_0:1");
    LandComponentId cId = id("arable@0_0:2");
    LandComponentId otherId = id("arable@1_0:0");

    LandComponent a =
        component(
            aId,
            10_000L,
            attrs(
                LandAttributeKey.OWNER, text("h1"), FERTILITY, number(80L), NOTE, text("keep-a")));
    LandComponent b =
        component(
            bId,
            3_000L,
            attrs(
                LandAttributeKey.OWNER, text("h1"), FERTILITY, number(70L), NOTE, text("keep-b")));
    LandComponent c =
        component(cId, 1_000L, attrs(LandAttributeKey.OWNER, text("h1"), FERTILITY, number(60L)));
    LandComponent other =
        component(
            otherId,
            2_000L,
            attrs(
                LandAttributeKey.OWNER,
                text("h9"),
                LandAttributeKey.CROP,
                text("barley"),
                LandAttributeKey.STAGE,
                text("stage3"),
                LandAttributeKey.PROGRESS,
                number(4L),
                LandAttributeKey.CYCLE_PROGRESS,
                number(5L),
                LandAttributeKey.START_INPUT_PAID,
                bool(true)));

    GameMap base = mapWith(Map.of(H0, arableLand(a, b, c), H1, arableLand(other)));
    HexCell otherBefore = base.hexes().get(H1);
    LandComponent otherBeforeComponent =
        LandComponentOperations.componentOf(otherBefore.land(), LandTypeId.ARABLE, otherId);

    GameMap written =
        LandProductionStateWriter.apply(
            base,
            List.of(
                new LandProductionStateWriter.Write(
                    aId,
                    new LotState.InProduction(
                        "grain_farm", "stage1", 9L, 9L, Map.of(), true, Map.of(GRAIN, 123L))),
                new LandProductionStateWriter.Write(
                    bId,
                    new LotState.InProduction(
                        "grain_farm", "stage2", 28L, 38L, Map.of(), false, Map.of(GRAIN, 456L))),
                new LandProductionStateWriter.Write(cId, LotState.IDLE)));

    assertThat(written).as("纯函数必须返回新图").isNotSameAs(base);
    assertThat(written.hexes().get(H1)).as("非目标 hex 原样保留").isEqualTo(otherBefore);
    assertThat(
            LandComponentOperations.componentOf(
                written.hexes().get(H1).land(), LandTypeId.ARABLE, otherId))
        .as("非目标 component 逐字段不变")
        .isEqualTo(otherBeforeComponent);

    InventoryLotView view =
        InventoryLotViewBuilder.build(ActorData.empty(), EconomyData.empty(), written);
    InventoryLot rowA = view.require(InventoryLotId.forLand(aId));
    InventoryLot rowB = view.require(InventoryLotId.forLand(bId));
    InventoryLot rowC = view.require(InventoryLotId.forLand(cId));

    assertThat(rowA.owner()).as("OWNER 不丢").isEqualTo(HOUSEHOLD_OWNER);
    assertThat(rowA.quantityMilli()).isEqualTo(10_000L);
    assertThat(rowA.state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.planId()).isEqualTo("grain_farm");
              assertThat(state.stageId()).isEqualTo("stage1");
              assertThat(state.stageProgressDays()).isEqualTo(9L);
              assertThat(state.cycleProgressDays()).isEqualTo(9L);
              assertThat(state.startInputPaid()).isTrue();
              assertThat(state.facts()).isEmpty();
              assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 123L));
            });

    assertThat(rowB.owner()).as("OWNER 不丢").isEqualTo(HOUSEHOLD_OWNER);
    assertThat(rowB.quantityMilli()).isEqualTo(3_000L);
    assertThat(rowB.state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            state -> {
              assertThat(state.planId()).isEqualTo("grain_farm");
              assertThat(state.stageId()).isEqualTo("stage2");
              assertThat(state.stageProgressDays()).isEqualTo(28L);
              assertThat(state.cycleProgressDays()).isEqualTo(38L);
              assertThat(state.startInputPaid()).isFalse();
              assertThat(state.facts()).isEmpty();
              assertThat(state.cycleInputUsedMilli()).containsExactlyEntriesOf(Map.of(GRAIN, 456L));
            });

    assertThat(rowC.owner()).isEqualTo(HOUSEHOLD_OWNER);
    assertThat(rowC.quantityMilli()).isEqualTo(1_000L);
    assertThat(rowC.state()).as("C 无生产状态 ⇒ IDLE").isEqualTo(LotState.IDLE);

    LandComponent rawA =
        LandComponentOperations.componentOf(written.hexes().get(H0).land(), LandTypeId.ARABLE, aId);
    LandComponent rawB =
        LandComponentOperations.componentOf(written.hexes().get(H0).land(), LandTypeId.ARABLE, bId);
    LandComponent rawC =
        LandComponentOperations.componentOf(written.hexes().get(H0).land(), LandTypeId.ARABLE, cId);
    assertThat(rawA.attributes())
        .containsEntry(LandAttributeKey.OWNER, text("h1"))
        .containsEntry(FERTILITY, number(80L))
        .containsEntry(NOTE, text("keep-a"));
    assertThat(rawB.attributes())
        .containsEntry(LandAttributeKey.OWNER, text("h1"))
        .containsEntry(FERTILITY, number(70L))
        .containsEntry(NOTE, text("keep-b"));
    assertThat(rawC.attributes())
        .containsEntry(LandAttributeKey.OWNER, text("h1"))
        .containsEntry(FERTILITY, number(60L));
    assertThat(written.hexes().get(H0).land().totalMilliMu(LandTypeId.ARABLE)).isEqualTo(14_000L);
  }

  @Test
  void idleWriteClearsAllProductionAndCycleInputButPreservesOtherAttributesAndQuantity() {
    LandComponentId cId = id("arable@0_0:0");
    LandComponent c =
        component(
            cId,
            1_000L,
            attrs(
                LandAttributeKey.OWNER,
                text("h1"),
                FERTILITY,
                number(60L),
                NOTE,
                text("keep"),
                IRRIGATION,
                text("ditch-7"),
                LandAttributeKey.CROP,
                text("grain_farm"),
                LandAttributeKey.STAGE,
                text("stage2"),
                LandAttributeKey.PROGRESS,
                number(17L),
                LandAttributeKey.CYCLE_PROGRESS,
                number(27L),
                LandAttributeKey.START_INPUT_PAID,
                bool(true),
                new LandAttributeKey(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + "grain"),
                number(123L)));
    GameMap base = mapWith(Map.of(H0, arableLand(c)));

    GameMap written =
        LandProductionStateWriter.apply(
            base, List.of(new LandProductionStateWriter.Write(cId, LotState.IDLE)));

    LandComponent cleared =
        LandComponentOperations.componentOf(written.hexes().get(H0).land(), LandTypeId.ARABLE, cId);
    assertThat(cleared).isNotNull();
    assertThat(cleared.quantityMilliMu()).as("数量不变").isEqualTo(1_000L);
    assertThat(written.hexes().get(H0).land().byType().get(LandTypeId.ARABLE).components())
        .as("component 数量不变，未误删整块地")
        .hasSize(1);
    assertThat(cleared.attributes())
        .containsEntry(LandAttributeKey.OWNER, text("h1"))
        .containsEntry(FERTILITY, number(60L))
        .containsEntry(NOTE, text("keep"))
        .containsEntry(IRRIGATION, text("ditch-7"));
    assertThat(cleared.attributes())
        .doesNotContainKeys(
            LandAttributeKey.CROP,
            LandAttributeKey.STAGE,
            LandAttributeKey.PROGRESS,
            LandAttributeKey.CYCLE_PROGRESS,
            LandAttributeKey.START_INPUT_PAID);
    assertThat(cleared.attributes().keySet())
        .as("全部 cycle_input:* 必须消失")
        .noneMatch(
            key -> key.wireValue().startsWith(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX));
    assertThat(cleared.attributes()).hasSize(4);

    InventoryLot row =
        InventoryLotViewBuilder.build(ActorData.empty(), EconomyData.empty(), written)
            .require(InventoryLotId.forLand(cId));
    assertThat(row.state()).isEqualTo(LotState.IDLE);
    assertThat(row.owner()).isEqualTo(HOUSEHOLD_OWNER);
  }

  @Test
  void cycleInputMapRoundTripsThroughBuilderAndReplacesOldCycleInputKeys() {
    LandComponentId cId = id("arable@0_0:0");
    LandAttributeKey oldRye =
        new LandAttributeKey(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + "rye");
    LandComponent c =
        component(
            cId,
            1_000L,
            attrs(
                LandAttributeKey.OWNER,
                text("h1"),
                LandAttributeKey.CROP,
                text("grain_farm"),
                LandAttributeKey.STAGE,
                text("stage1"),
                LandAttributeKey.PROGRESS,
                number(3L),
                LandAttributeKey.CYCLE_PROGRESS,
                number(3L),
                LandAttributeKey.START_INPUT_PAID,
                bool(true),
                oldRye,
                number(999L)));
    GameMap base = mapWith(Map.of(H0, arableLand(c)));

    LotState.InProduction state =
        new LotState.InProduction(
            "grain_farm", "stage1", 3L, 3L, Map.of(), true, Map.of(GRAIN, 123L, FIBER, 7L));
    GameMap written =
        LandProductionStateWriter.apply(
            base, List.of(new LandProductionStateWriter.Write(cId, state)));

    LandComponent raw =
        LandComponentOperations.componentOf(written.hexes().get(H0).land(), LandTypeId.ARABLE, cId);
    assertThat(raw.attributes()).doesNotContainKey(oldRye);
    assertThat(raw.attributes())
        .containsEntry(
            new LandAttributeKey(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + "grain"),
            number(123L))
        .containsEntry(
            new LandAttributeKey(LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + "fiber"),
            number(7L));

    InventoryLot row =
        InventoryLotViewBuilder.build(ActorData.empty(), EconomyData.empty(), written)
            .require(InventoryLotId.forLand(cId));
    assertThat(row.state())
        .isInstanceOfSatisfying(
            LotState.InProduction.class,
            readBack ->
                assertThat(readBack.cycleInputUsedMilli())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 123L, FIBER, 7L)));
  }

  @Test
  void unknownLotAfterAValidWriteIsRejectedNamedAndBaseIsUnchanged() {
    LandComponentId present = id("arable@0_0:0");
    LandComponentId missing = id("arable@0_0:9");
    LandComponent presentComponent =
        component(
            present,
            10_000L,
            attrs(
                LandAttributeKey.OWNER,
                text("h1"),
                LandAttributeKey.CROP,
                text("grain_farm"),
                LandAttributeKey.STAGE,
                text("stage1"),
                LandAttributeKey.PROGRESS,
                number(4L),
                LandAttributeKey.CYCLE_PROGRESS,
                number(4L),
                LandAttributeKey.START_INPUT_PAID,
                bool(true)));
    GameMap base = mapWith(Map.of(H0, arableLand(presentComponent)));

    assertRejectedAndBaseUnchanged(
        base,
        List.of(
            new LandProductionStateWriter.Write(present, LotState.IDLE),
            new LandProductionStateWriter.Write(missing, LotState.IDLE)),
        "land-production-lot-missing");
  }

  @Test
  void duplicateWriteIsRejectedNamedAndBaseIsUnchanged() {
    LandComponentId aId = id("arable@0_0:0");
    GameMap base = mapWith(Map.of(H0, arableLand(component(aId, 10_000L, ownerAttrs("h1")))));

    assertRejectedAndBaseUnchanged(
        base,
        List.of(
            new LandProductionStateWriter.Write(aId, LotState.IDLE),
            new LandProductionStateWriter.Write(aId, LotState.IDLE)),
        "land-production-write-duplicate");
  }

  @Test
  void completeStateIsRejectedNamedAndBaseIsUnchanged() {
    LandComponentId aId = id("arable@0_0:0");
    GameMap base = mapWith(Map.of(H0, arableLand(component(aId, 10_000L, ownerAttrs("h1")))));

    assertRejectedAndBaseUnchanged(
        base,
        List.of(new LandProductionStateWriter.Write(aId, LotState.COMPLETE)),
        "land-production-state-invalid");
  }

  @Test
  void sameLotIdOnTwoHexesIsRejectedAmbiguousAndBaseIsUnchanged() {
    LandComponentId duplicated = id("arable@0_0:0");
    GameMap base =
        mapWith(
            Map.of(
                H0, arableLand(component(duplicated, 10_000L, ownerAttrs("h1"))),
                H1, arableLand(component(duplicated, 3_000L, ownerAttrs("h2")))));

    assertRejectedAndBaseUnchanged(
        base,
        List.of(new LandProductionStateWriter.Write(duplicated, LotState.IDLE)),
        "land-production-lot-ambiguous");
  }

  private static void assertRejectedAndBaseUnchanged(
      GameMap base, List<LandProductionStateWriter.Write> writes, String reason) {
    List<ComponentSnapshot> before = snapshot(base);
    assertThatThrownBy(() -> LandProductionStateWriter.apply(base, writes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(reason);
    assertThat(snapshot(base))
        .as("失败时原 map 的全部 component（含 attrs/quantity/hex）必须逐字段不变")
        .isEqualTo(before);
  }

  private static List<ComponentSnapshot> snapshot(GameMap map) {
    List<ComponentSnapshot> snapshots = new ArrayList<>();
    for (Map.Entry<HexCoord, HexCell> hexEntry : map.hexes().entrySet()) {
      for (LandTypeArea area : hexEntry.getValue().land().byType().values()) {
        for (LandComponent component : area.components()) {
          snapshots.add(new ComponentSnapshot(hexEntry.getKey(), component));
        }
      }
    }
    return snapshots;
  }

  private record ComponentSnapshot(HexCoord hex, LandComponent component) {}

  private static GameMap mapWith(Map<HexCoord, HexLand> lands) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, HexLand> entry : lands.entrySet()) {
      cells.put(entry.getKey(), new HexCell(0.5, entry.getValue()));
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static HexLand arableLand(LandComponent... components) {
    LandTypeArea area = new LandTypeArea(LandTypeId.ARABLE, List.of(components), components.length);
    return new HexLand(Map.of(LandTypeId.ARABLE, area));
  }

  private static LandComponent component(
      LandComponentId id,
      long quantityMilliMu,
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    return new LandComponent(id, quantityMilliMu, attributes);
  }

  private static Map<LandAttributeKey, LandAttributeValue> ownerAttrs(String owner) {
    return attrs(LandAttributeKey.OWNER, text(owner));
  }

  private static Map<LandAttributeKey, LandAttributeValue> attrs(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("attrs 需要成对的 key/value");
    }
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      attributes.put((LandAttributeKey) keyValues[i], (LandAttributeValue) keyValues[i + 1]);
    }
    return attributes;
  }

  private static LandAttributeValue text(String value) {
    return LandAttributeValue.ofString(value);
  }

  private static LandAttributeValue number(long value) {
    return LandAttributeValue.ofLong(value);
  }

  private static LandAttributeValue bool(boolean value) {
    return LandAttributeValue.ofBoolean(value);
  }

  private static LandComponentId id(String value) {
    return new LandComponentId(value);
  }
}
