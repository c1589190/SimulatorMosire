package io.mosire.simos.map.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * R-3-map：map 模块的 JSON 往返守卫（M4 Task 3 Step 4，spec §13.1 风险的兑现点之一）。
 *
 * <p>★ **往返只断 {@code equals}，绝不拿字节当断言**（台账裁定 12：跨 JVM 实测 2 种字节）。"非平凡"判据的覆盖：{@code
 * SimosTimestamp.calendarLabel} 的 {@code Optional}（present 与 empty 两个方向都有用例）、五个自定义键的 {@code
 * Map}（hexes/regions/cities/pathways/edges）、密封接口 {@code FieldDelta}（变更集用例里四条变体全走一遍）。
 */
class MapCodecTest {

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  private static final HexCoord H_C = new HexCoord(2, 1);

  private static final EdgeRef EDGE_AB = new EdgeRef(H_A, H_B);

  private static final EdgeRef EDGE_BC = new EdgeRef(H_B, H_C);

  private static final MapCodec CODEC = new MapCodec();

  @Test
  void namespaceIsMap() {
    assertThat(CODEC.namespace()).isEqualTo("map");
  }

  /** 非平凡快照往返：带历注（Optional present）+ 七个 Map 全非空（五个自定义键类型全在键位置上）。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    MapSnapshot snapshot = snapshotOf(fixturedMap(), SimosTimestamp.of(10, "弘光元年"));
    MapSnapshot back = (MapSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    MapSnapshot snapshot = snapshotOf(fixturedMap(), SimosTimestamp.of(11));
    MapSnapshot back = (MapSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /**
   * 变更集往返：一条变更集里**四条 FieldDelta 变体各占一个组件**—— hexes=Upsert（新增 H_B）、regions=Unchanged、cities=Remove（删
   * c1）、edges=Patch（删 AB 增 BC）。
   */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    GameMap base = fixturedMap();
    GameMap target =
        withExtraHex(base)
            .withCities(Map.of())
            .withEdges(Map.of(EDGE_BC, new EdgeTags(Map.of("road", Map.of("width", 2)))));
    MapChangeSet changeSet = MapChangeSet.between(base, target);

    // 前置：夹具确实落在了四条变体上（否则下面的往返是在保护一个没兑现的形态）
    assertThat(changeSet.hexes()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(changeSet.cities()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(changeSet.edges()).isInstanceOf(FieldDelta.Patch.class);

    MapChangeSet back = (MapChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(back).isEqualTo(changeSet);
  }

  /** 值类型的绑定不能在读入侧丢成 Map（裁定 D 点的姊妹形态：泛型 T 丢了 equals 不会响，得单独看类型）。 */
  @Test
  void deltaValuesSurviveAsHexCellNotAsMaps() {
    GameMap base = GameMap.empty();
    GameMap target = fixturedMap();
    MapChangeSet back =
        (MapChangeSet)
            CODEC.decodeChangeSet(CODEC.encodeChangeSet(MapChangeSet.between(base, target)));
    assertThat(back.hexes().lookup(H_A.toString())).contains(new HexCell(0.35));
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    MapSnapshot base = snapshotOf(fixturedMap(), SimosTimestamp.of(10));
    GameMap target = withExtraHex(base.map());
    MapChangeSet changeSet = MapChangeSet.between(base.map(), target);
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    MapSnapshot applied = (MapSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.map()).isEqualTo(MapChangeSet.apply(changeSet, base.map()));
    // base 不得被就地改
    assertThat(base.map()).isEqualTo(fixturedMap());
  }

  /**
   * ★ 下转型守卫的自证：喂一个**别的模块的切片**，{@code apply} 与 {@code encodeSnapshot} 都必须当场 {@link
   * IllegalStateException}。
   *
   * <p>这条用例**只在改成 {@code instanceof} 之后**才有判别力——裸 cast 同样会抛（{@code ClassCastException}），
   * 所以断言钉的是**异常类型 + 消息**，不是"抛了就算"。
   */
  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    GameMap map = fixturedMap();
    MapChangeSet changeSet = MapChangeSet.between(map, map);
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 MapSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 MapSnapshot");
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "unit";
    }
  }

  // ── 夹具 ──

  /** 九个组件全非空的图（照 M2 {@code RoundTripComponentsTest} 的单件夹具，六个自定义键类型全在键位上）。 */
  private static GameMap fixturedMap() {
    return new GameMap(
        Map.of(H_A, new HexCell(0.35)),
        TerrainBlocks.uniform(Set.of(H_A), "plains"),
        Map.of(
            new RegionId("r1"),
            Region.of(new RegionId("r1"), "区域 r1", Set.of(H_A), RegionMeta.empty())),
        Map.of(
            new CityId("c1"),
            new City(
                new CityId("c1"), "城 c1", H_A, new RegionId("r1"), Map.of("population", 1000))),
        Map.of("plains", TerrainCatalog.of("plains")),
        Map.of(
            new PathwayId("p1"),
            new Pathway(new PathwayId("p1"), "线 p1", "road", List.of(EDGE_AB), Map.of("width", 2))),
        Map.of("road", new PathwayGroup("road", "组 road", "#8B7355", null, true, Map.of())),
        Map.of(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2)))),
        GenerationSpec.defaults(0L));
  }

  /** 加一格（hexes 与 terrainBlocks **原子**更新：两者受分割不变式约束，不能分两步 with）。 */
  private static GameMap withExtraHex(GameMap base) {
    LinkedHashMap<HexCoord, HexCell> hexes = new LinkedHashMap<>(base.hexes());
    hexes.put(H_B, new HexCell(0.1));
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    terrain.put(H_A, "plains");
    terrain.put(H_B, "ocean");
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrain),
        base.regions(),
        base.cities(),
        base.terrainTypes(),
        base.pathways(),
        base.pathwayGroups(),
        base.edges(),
        base.spec());
  }

  private static MapSnapshot snapshotOf(GameMap map, SimosTimestamp timestamp) {
    return new MapSnapshot(new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, map);
  }
}
