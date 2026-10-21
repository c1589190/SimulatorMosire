package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexAreas;
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
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L4：{@code map.SeedArableOwnership} 命令边界。
 *
 * <p>正例：把 {@code owner→quantity} 写进 aria 组件属性；负例：未知/重复 hex、重复 owner、非正数量、 ΣownerQuantity != Map
 * arable 总量、空白 owner、同 owner 多 component 非 owner 属性不同 ⇒ 全部具名 Rejected。
 */
class SeedArableOwnershipHandlerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HexCoord UNKNOWN = new HexCoord(9, 9);
  private static final SeedArableOwnershipHandler HANDLER = new SeedArableOwnershipHandler();

  @Test
  void seedsOwnerAttributesAndPreservesArableTotal() {
    GameMap base = twoPlainsHexes();
    long arable0 = HexAreas.arableMilliMu(base.hexes().get(H0), "plains");
    long arable1 = HexAreas.arableMilliMu(base.hexes().get(H1), "plains");
    String payload =
        "{\"entries\":["
            + entry(H0, owners(owner("hh-a", arable0 / 2), owner("hh-b", arable0 - arable0 / 2)))
            + ","
            + entry(H1, owners(owner("hh-a", arable1)))
            + "]}";

    HandlerOutcome outcome = HANDLER.handle(stateOf(base), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    GameMap after =
        MapChangeSet.apply((MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
    assertThat(after.hexes().get(H0).land().totalMilliMu(LandTypeId.ARABLE))
        .as("写 owner 不改该 hex 的 arable 总量")
        .isEqualTo(arable0);
    assertThat(after.hexes().get(H0).land().byType().get(LandTypeId.ARABLE).components())
        .extracting(LandComponentOperations::ownerOf)
        .containsExactly("hh-a", "hh-b");
    assertThat(after.hexes().get(H1).land().byType().get(LandTypeId.ARABLE).components())
        .extracting(LandComponentOperations::ownerOf)
        .containsExactly("hh-a");
    assertThat(
            after
                .hexes()
                .get(H1)
                .land()
                .byType()
                .get(LandTypeId.ARABLE)
                .components()
                .get(0)
                .attributes())
        .containsKey(LandAttributeKey.OWNER);
  }

  @Test
  void rejectsUnknownAndDuplicateHexes() {
    GameMap base = twoPlainsHexes();
    long arable = HexAreas.arableMilliMu(base.hexes().get(H0), "plains");
    assertRejected(
        "{\"entries\":[" + entry(UNKNOWN, owners(owner("hh-a", arable))) + "]}",
        "seed-arable-ownership-unknown-hex");
    assertRejected(
        "{\"entries\":["
            + entry(H0, owners(owner("hh-a", arable)))
            + ","
            + entry(H0, owners(owner("hh-b", arable)))
            + "]}",
        "seed-arable-ownership-duplicate-hex");
  }

  @Test
  void rejectsDuplicateOwnerBlankOwnerAndNonPositiveQuantity() {
    GameMap base = twoPlainsHexes();
    long arable = HexAreas.arableMilliMu(base.hexes().get(H0), "plains");
    assertRejected(
        "{\"entries\":["
            + entry(H0, owners(owner("hh-a", arable / 2), owner("hh-a", arable - arable / 2)))
            + "]}",
        "seed-arable-ownership-duplicate-owner");
    assertRejected(
        "{\"entries\":[" + entry(H0, owners(owner(" ", arable))) + "]}",
        "seed-arable-ownership-owner-blank");
    assertRejected(
        "{\"entries\":[" + entry(H0, owners(owner("hh-a", 0L))) + "]}",
        "seed-arable-ownership-quantity-nonpositive");
  }

  @Test
  void rejectsOwnerTotalThatDoesNotEqualMapArableTotal() {
    GameMap base = twoPlainsHexes();
    long arable = HexAreas.arableMilliMu(base.hexes().get(H0), "plains");
    assertRejected(
        "{\"entries\":[" + entry(H0, owners(owner("hh-a", arable - 1L))) + "]}",
        "seed-arable-ownership-arable-mismatch");
  }

  @Test
  void rejectsAggregatingSameOwnerComponentsWithDifferentNonOwnerAttributes() {
    Map<LandAttributeKey, LandAttributeValue> p1 = new LinkedHashMap<>();
    p1.put(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    p1.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(1L));
    Map<LandAttributeKey, LandAttributeValue> p2 = new LinkedHashMap<>();
    p2.put(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a"));
    p2.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(2L));
    HexLand land =
        new HexLand(
            Map.of(
                LandTypeId.ARABLE,
                new LandTypeArea(
                    LandTypeId.ARABLE,
                    List.of(
                        new LandComponent(new LandComponentId("arable@0_0:0"), 100L, p1),
                        new LandComponent(new LandComponentId("arable@0_0:1"), 200L, p2)),
                    2L)));
    GameMap base = mapWith(H0, new HexCell(0.5, land));

    assertRejected(
        base,
        "{\"entries\":[" + entry(H0, owners(owner("hh-a", 300L))) + "]}",
        "land-owner-aggregation-unsupported");
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private void assertRejected(String payload, String reasonFragment) {
    assertRejected(twoPlainsHexes(), payload, reasonFragment);
  }

  private void assertRejected(GameMap base, String payload, String reasonFragment) {
    HandlerOutcome outcome = HANDLER.handle(stateOf(base), payload);
    assertThat(outcome).as("payload 必须被拒: %s", payload).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reasonFragment);
  }

  private static String entry(HexCoord hex, String ownersJson) {
    return "{\"hex\":{\"q\":" + hex.q() + ",\"r\":" + hex.r() + "},\"owners\":" + ownersJson + "}";
  }

  private static String owners(String... ownerNodes) {
    return "[" + String.join(",", ownerNodes) + "]";
  }

  private static String owner(String owner, long quantity) {
    return "{\"owner\":\"" + owner + "\",\"quantityMilliMu\":" + quantity + "}";
  }

  private static GameMap twoPlainsHexes() {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, HexAreas.initialCell(H0, 0.4, "plains"));
    cells.put(H1, HexAreas.initialCell(H1, 0.5, "plains"));
    return mapWith(cells);
  }

  private static GameMap mapWith(HexCoord hex, HexCell cell) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(hex, cell);
    return mapWith(cells);
  }

  private static GameMap mapWith(Map<HexCoord, HexCell> cells) {
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

  private static SimulationState stateOf(GameMap map) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, at),
        Map.of("map", new MapSnapshot(ref, at, map)),
        InMemoryInfoSystem.empty());
  }
}
