package io.mosire.simos.map.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L1/L4：{@link HexLand} 三档树过 MapCodec / MapChangeSet 的往返。
 *
 * <p>判据：属性表的三档值（STRING/LONG/BOOLEAN）、自定义 key/type、component 顺序、`nextComponentOrdinal`
 * 都逐字段回来；ChangeSet 的 hexes 差异也按逐字重建。
 */
class LandComponentTreeCodecTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);

  @Test
  void hexLandComponentsRoundTripThroughMapCodecAndChangeSet() {
    GameMap base = twoHexMap();
    HexLand richer =
        LandComponentOperations.setArableOwners(
            base.hexes().get(H0).land(),
            H0,
            Map.of("hh-owner-a", 600_000L, "hh-owner-b", 400_000L));
    richer =
        LandComponentOperations.setComponentAttribute(
            richer,
            H0,
            LandTypeId.ARABLE,
            new LandComponentId("arable@0_0:0"),
            LandAttributeKey.PROGRESS,
            LandAttributeValue.ofLong(7L));
    richer =
        LandComponentOperations.setComponentAttribute(
            richer,
            H0,
            LandTypeId.ARABLE,
            new LandComponentId("arable@0_0:0"),
            LandAttributeKey.FERTILITY,
            LandAttributeValue.ofBoolean(true));
    HexLand withCustomType =
        new HexLand(
            append(
                richer.byType(),
                new LandTypeId("custom:orchard"),
                new LandTypeArea(
                    new LandTypeId("custom:orchard"),
                    List.of(
                        new LandComponent(
                            new LandComponentId("custom:orchard@0_0:0"),
                            42L,
                            Map.of(LandAttributeKey.CROP, LandAttributeValue.ofString("apple")))),
                    1L)));
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>(base.hexes());
    hexes.put(H0, base.hexes().get(H0).withLand(withCustomType));
    GameMap target = base.withHexes(hexes);

    MapSnapshot snapshot =
        new MapSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0), target);
    MapCodec codec = new MapCodec();
    MapSnapshot back = (MapSnapshot) codec.decodeSnapshot(codec.encodeSnapshot(snapshot));

    assertThat(back.map()).as("HexLand 三档树 + 属性三型 + 自定义 type 整图往返").isEqualTo(target);
    HexLand decoded = back.map().hexes().get(H0).land();
    assertThat(decoded.byType()).containsKeys(LandTypeId.ARABLE, new LandTypeId("custom:orchard"));
    assertThat(decoded.byType().get(LandTypeId.ARABLE).components())
        .extracting(component -> component.id().value())
        .containsExactly("arable@0_0:0", "arable@0_0:1");
    assertThat(decoded.byType().get(LandTypeId.ARABLE).components().get(0).attributes())
        .containsEntry(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-owner-a"))
        .containsEntry(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(7L))
        .containsEntry(LandAttributeKey.FERTILITY, LandAttributeValue.ofBoolean(true));
    assertThat(decoded.byType().get(LandTypeId.ARABLE).nextComponentOrdinal()).isEqualTo(2L);

    MapChangeSet changeSet = MapChangeSet.between(base, target);
    assertThat(MapChangeSet.apply(changeSet, base))
        .as("MapChangeSet 对 HexLand 的逐字段往返")
        .isEqualTo(target);
    assertThat(codec.decodeChangeSet(codec.encodeChangeSet(changeSet))).isEqualTo(changeSet);
  }

  private static Map<LandTypeId, LandTypeArea> append(
      Map<LandTypeId, LandTypeArea> byType, LandTypeId type, LandTypeArea area) {
    Map<LandTypeId, LandTypeArea> next = new LinkedHashMap<>(byType);
    next.put(type, area);
    return next;
  }

  private static GameMap twoHexMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(
        H0,
        new HexCell(
            0.5,
            LandComponentOperations.seedSingle(
                H0,
                LandTypeId.ARABLE,
                1_000_000L,
                Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-owner-a")))));
    hexes.put(H1, new HexCell(0.6));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}
