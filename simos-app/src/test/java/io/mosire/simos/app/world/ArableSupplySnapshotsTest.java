package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.land.LandComponentLedger;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D-032 L4：Map 土地组件树 ↔ economy 瞬态 {@link LandComponentLedger} 的组合根适配。
 *
 * <p>判据：map→ledger→map 后 owner/quantity/总量守恒；市场/债务/合户只改瞬态聚合，轮末写回 Map 后 Map owner 聚合与账本逐值一致；同 owner
 * 组件合并规则在写回时仍生效。
 */
class ArableSupplySnapshotsTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HouseholdId A = HouseholdId.parse("hh-a");
  private static final HouseholdId B = HouseholdId.parse("hh-b");

  @Test
  void mapToLedgerToMapPreservesOwnerQuantitiesAndTotal() {
    GameMap map = twoOwnerMap();
    LandComponentLedger ledger = ArableSupplySnapshots.landLedgerFrom(stateOf(map), "test");

    assertThat(ledger.holdingsOrEmpty())
        .containsEntry(new LandHoldingKey(H0, A), new LandHolding(A, H0, 600L))
        .containsEntry(new LandHoldingKey(H0, B), new LandHolding(B, H0, 400L))
        .containsEntry(new LandHoldingKey(H1, A), new LandHolding(A, H1, 1_000L));

    GameMap written = ArableSupplySnapshots.applyLedgerToMap(map, ledger, "test");
    assertThat(written).as("未改账本 ⇒ 写回逐值不变").isEqualTo(map);
  }

  @Test
  void transferThenWritebackKeepsMapOwnerAggregateAndArableTotalsConsistent() {
    GameMap map = twoOwnerMap();
    LandComponentLedger ledger = ArableSupplySnapshots.landLedgerFrom(stateOf(map), "test");

    // 市场/债务/合户都只改瞬态聚合：B 在 H0 买入 250，A 剩余 350。
    Map<LandHoldingKey, LandHolding> next = new LinkedHashMap<>(ledger.holdingsOrEmpty());
    next.put(new LandHoldingKey(H0, A), new LandHolding(A, H0, 350L));
    next.put(new LandHoldingKey(H0, B), new LandHolding(B, H0, 650L));
    ledger.replaceHoldings(next);

    GameMap written = ArableSupplySnapshots.applyLedgerToMap(map, ledger, "test");
    LandComponentLedger replayed = ArableSupplySnapshots.landLedgerFrom(stateOf(written), "test");

    assertThat(replayed.holdingsOrEmpty())
        .as("写回后 Map owner 聚合与瞬态账本逐值一致")
        .isEqualTo(ledger.holdingsOrEmpty());
    assertThat(written.hexes().get(H0).land().totalMilliMu(LandTypeId.ARABLE))
        .as("部分转移后该 hex arable 总量守恒")
        .isEqualTo(1_000L);
    assertThat(written.hexes().get(H0).land().byType().get(LandTypeId.ARABLE).components())
        .extracting(LandComponentOperations::ownerOf)
        .containsExactly("hh-a", "hh-b");
    assertThat(
            written.hexes().get(H0).land().byType().get(LandTypeId.ARABLE).nextComponentOrdinal())
        .as(
            "已有 owner 复用 id（买方合并后取字典序最小 id）；部分转移先分配 ordinal 2 生成买方 component，"
                + "normalize 合并同 owner 同属性 component 后不回收 ordinal，故下一个序号为 3")
        .isEqualTo(3L);
  }

  @Test
  void collapsingTwoOwnersToOneMergesWritebackComponents() {
    GameMap map = twoOwnerMap();
    LandComponentLedger ledger = ArableSupplySnapshots.landLedgerFrom(stateOf(map), "test");

    // 合户/全量卖出：H0 全归 A ⇒ 写回时必须把同 owner 同属性 component 合并成一个。
    Map<LandHoldingKey, LandHolding> next = new LinkedHashMap<>(ledger.holdingsOrEmpty());
    next.remove(new LandHoldingKey(H0, B));
    next.put(new LandHoldingKey(H0, A), new LandHolding(A, H0, 1_000L));
    ledger.replaceHoldings(next);

    GameMap written = ArableSupplySnapshots.applyLedgerToMap(map, ledger, "test");

    assertThat(written.hexes().get(H0).land().byType().get(LandTypeId.ARABLE).components())
        .singleElement()
        .satisfies(
            component -> {
              assertThat(LandComponentOperations.ownerOf(component)).isEqualTo("hh-a");
              assertThat(component.quantityMilliMu()).isEqualTo(1_000L);
            });
    assertThat(written.hexes().get(H0).land().totalMilliMu(LandTypeId.ARABLE)).isEqualTo(1_000L);
  }

  @Test
  void missingMapSliceAndMissingOwnerFailClosed() {
    assertThatThrownBy(
            () ->
                ArableSupplySnapshots.landLedgerFrom(
                    new SimulationState(
                        new StateMeta(
                            new StateRef(new BranchId("main"), new RevisionId(1)),
                            SimosTimestamp.of(0)),
                        Map.of(),
                        InMemoryInfoSystem.empty()),
                    "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-ledger-map-missing");

    HexLand missingOwner =
        LandComponentOperations.seedSingle(H0, LandTypeId.ARABLE, 100L, Map.of());
    GameMap broken = mapWith(Map.of(H0, new HexCell(0.5, missingOwner)));
    assertThatThrownBy(() -> ArableSupplySnapshots.landLedgerFrom(stateOf(broken), "test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("land-owner-missing");
  }

  private static GameMap twoOwnerMap() {
    HexLand h0 =
        LandComponentOperations.setArableOwners(
            HexLand.empty(), H0, Map.of("hh-a", 600L, "hh-b", 400L));
    HexLand h1 =
        LandComponentOperations.seedSingle(
            H1,
            LandTypeId.ARABLE,
            1_000L,
            Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString("hh-a")));
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, new HexCell(0.5, h0));
    cells.put(H1, new HexCell(0.5, h1));
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
