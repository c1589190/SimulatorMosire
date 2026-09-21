package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.util.state.FieldDelta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code map.SetEdge} 的领域操作面：**{@code replace}/{@code merge} 语义显式**（Q2）、**merge 不丢既有 tag**（M2
 * 挂起项）、{@code kind} 词表 fail-closed、端点必须在图上、确定性。
 *
 * <p>夹具的既有标注用真 {@link EdgeTags} 构造（含 props），断言一律走 {@code apply} 后的 {@code edges} 组件——这是对外的稳定口径。
 */
class EdgeOperationsTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H01 = new HexCoord(0, 1);

  /** 三格链 H00—H10、H00—H01（都在图上）。 */
  private static final EdgeRef E_LEFT = new EdgeRef(H00, H10);

  private static final EdgeRef E_UP = new EdgeRef(H00, H01);

  // ── merge：只加不删 ─────────────────────────────────────────────────────────

  /**
   * ★ merge 不丢既有 tag：既有边**逐值**不动（**同 kind 的 props** 与别的 kind 都在），新边拿到 road。
   *
   * <p>★ 夹具**必须**让既有边带上本次 merge 的**同一 kind**：{@code replace} 是**逐 kind** 摘标注的，既有 tag 若只在 别的 kind
   * 上，则"merge 当 replace 使"在这份输入上**两种语义结果相同** ⇒ 用例成装饰。实测 m2 变异体（{@code REPLACE.equals(operation) ||
   * MERGE.equals(operation)}）在**旧夹具**（E_LEFT 只有 river、命令是 road）下本方法 **仍全绿**——那时它是靠 {@link
   * #mergeKeepsExistingPropertiesOfTheSameKind} 与 {@link #mergeLeavesUntargetedEdgesUntouched} 杀的。
   */
  @Test
  void mergeAddsTheNewTagAndKeepsEveryExistingTag() {
    Map<String, Object> roadProps = Map.of("width", 3);
    Map<String, Object> riverProps = Map.of("width", 2);
    GameMap base =
        graphOf(Map.of(E_LEFT, new EdgeTags(Map.of("road", roadProps, "river", riverProps))));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "road", Set.of(E_UP), "merge"));

    assertThat(after.edges())
        .as("既有边逐值不变：同 kind 的 props 未被重置，别的 kind 也一字不动")
        .containsEntry(E_LEFT, new EdgeTags(Map.of("road", roadProps, "river", riverProps)));
    assertThat(after.edges()).containsEntry(E_UP, tags("road", Map.of()));
    assertThat(after.edges()).as("既有边 + 新边，无别的边").containsOnlyKeys(E_LEFT, E_UP);
  }

  /** ★ merge 对同一 kind 的既有 props **只补不改**：已存在 ⇒ 原样保留（putIfAbsent）。 */
  @Test
  void mergeKeepsExistingPropertiesOfTheSameKind() {
    Map<String, Object> props = Map.of("width", 2);
    GameMap base = graphOf(Map.of(E_LEFT, tags("river", props)));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_LEFT), "merge"));

    assertThat(after.edges().get(E_LEFT).byPathway().get("river")).isEqualTo(props);
  }

  /** merge **不给** payload 之外的边加标注，也**不动**它们（只碰目标边）。 */
  @Test
  void mergeLeavesUntargetedEdgesUntouched() {
    GameMap base = graphOf(Map.of(E_LEFT, tags("river", Map.of()), E_UP, tags("road", Map.of())));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_UP), "merge"));

    assertThat(after.edges().get(E_LEFT)).as("未目标边一字不动").isEqualTo(base.edges().get(E_LEFT));
    assertThat(after.edges().get(E_UP).byPathway())
        .as("目标边两种 kind 并存")
        .containsOnlyKeys("river", "road");
  }

  // ── replace：整份覆盖该 kind ─────────────────────────────────────────────────

  /** ★ replace 覆盖**整张图**：目标之外的边被摘掉该 kind，其他 kind 一字不动，目标边拿到该 kind。 */
  @Test
  void replaceOverwritesTheKindAcrossTheWholeMapAndKeepsOtherKinds() {
    Map<String, Object> roadProps = Map.of("width", 1);
    GameMap base =
        graphOf(Map.of(E_LEFT, new EdgeTags(Map.of("river", Map.of(), "road", roadProps))));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_UP), "replace"));

    assertThat(after.edges())
        .as("E_LEFT 的 river 被摘掉、road 原样；E_UP 拿到空 river")
        .containsEntry(E_LEFT, tags("road", roadProps))
        .containsEntry(E_UP, tags("river", Map.of()));
  }

  /** ★ replace 后一条边别无标注 ⇒ 该边从 {@code edges} 组件里消失（不是留个空 EdgeTags）。 */
  @Test
  void replaceDropsEdgesThatLoseTheirOnlyTag() {
    GameMap base = graphOf(Map.of(E_LEFT, tags("river", Map.of())));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_UP), "replace"));

    assertThat(after.edges()).as("只剩被标注的 E_UP").containsOnlyKeys(E_UP);
  }

  /** replace 也覆盖**目标边**上同 kind 的既有 props（"整份覆盖"含 props）。 */
  @Test
  void replaceResetsPropertiesOfTheTargetEdge() {
    GameMap base = graphOf(Map.of(E_LEFT, tags("river", Map.of("width", 9))));

    GameMap after = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_LEFT), "replace"));

    assertThat(after.edges().get(E_LEFT).byPathway().get("river"))
        .as("props 被重置成空")
        .isEqualTo(Map.of());
  }

  // ── 逐组件独立性 + 确定性 ───────────────────────────────────────────────────

  /** 只有 {@code edges} 非 {@code Unchanged}；{@code hexes}（高度）/ {@code terrainBlocks} 不因标注而变。 */
  @Test
  void onlyTheEdgesComponentChanges() {
    GameMap base = graphOf(Map.of());

    MapChangeSet cs = EdgeOperations.setEdge(base, "river", Set.of(E_LEFT), "merge");

    assertThat(cs.edges()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).as("标注不动高度").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks()).as("标注不动地形").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathways()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathwayGroups()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** 同一 base + 同一命令两次 ⇒ {@code edges} 组件的键序与逐值**逐字节相同**。 */
  @Test
  void sameInputGivesByteIdenticalEdges() {
    GameMap base = graphOf(Map.of(E_LEFT, tags("road", Map.of())));

    GameMap first = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_UP), "merge"));
    GameMap second = apply(base, EdgeOperations.setEdge(base, "river", Set.of(E_UP), "merge"));

    assertThat(new ArrayList<>(second.edges().keySet()))
        .as("键序一致")
        .containsExactlyElementsOf(new ArrayList<>(first.edges().keySet()));
    assertThat(second.edges().toString()).as("edges 组件逐字节相同").isEqualTo(first.edges().toString());
  }

  // ── 词表 + 边界 ─────────────────────────────────────────────────────────────

  /** kind 大小写不敏感，一律归一成小写（真档与组 id 都是小写）。 */
  @Test
  void kindIsCaseInsensitiveAndNormalizedToLowercase() {
    GameMap base = graphOf(Map.of());

    for (String kind : List.of("RIVER", "River", "rIvEr")) {
      GameMap after = apply(base, EdgeOperations.setEdge(base, kind, Set.of(E_LEFT), "MERGE"));
      assertThat(after.edges().get(E_LEFT).byPathway())
          .as("kind=%s", kind)
          .containsOnlyKeys("river");
    }
  }

  @Test
  void rejectsUnknownKind() {
    GameMap base = graphOf(Map.of());

    for (String bad : List.of("sea", "canal", "river ", "")) {
      assertThatThrownBy(() -> EdgeOperations.setEdge(base, bad, Set.of(E_LEFT), "merge"))
          .as("词表外 kind: %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("未知连通性类型: " + bad);
    }
  }

  // ── ★ 词表 = 默认 + 可自定义（C34）─────────────────────────────────────────

  /**
   * ★★ C34 的要害：注册自定义组 {@code canal} 后 {@code map.SetEdge{kind:"canal"}} **成立**（落进 {@code edges}
   * 组件）。词表不再硬编码 ⇒ 这条在"KINDS 写回 {@code Set.of("river","road")}"的变异体下**必红**。
   */
  @Test
  void registeredCustomGroupBecomesAValidKind() {
    GameMap base = graphOf(Map.of());
    GameMap withCanal = apply(base, PathwayGroupOperations.register(base, canalGroup()));

    GameMap after =
        apply(withCanal, EdgeOperations.setEdge(withCanal, "canal", Set.of(E_LEFT), "merge"));

    assertThat(after.edges().get(E_LEFT).byPathway()).containsOnlyKeys("canal");
  }

  /** ★ 注册 canal **不**放宽别的：默认两组仍可、未注册的 {@code rail} 仍 fail-closed。 */
  @Test
  void registrationDoesNotWidenTheVocabularyBeyondRegisteredGroups() {
    GameMap base = graphOf(Map.of());
    GameMap withCanal = apply(base, PathwayGroupOperations.register(base, canalGroup()));

    GameMap afterRiver =
        apply(withCanal, EdgeOperations.setEdge(withCanal, "river", Set.of(E_LEFT), "merge"));
    assertThat(afterRiver.edges().get(E_LEFT).byPathway()).containsOnlyKeys("river");

    assertThatThrownBy(() -> EdgeOperations.setEdge(withCanal, "rail", Set.of(E_LEFT), "merge"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未知连通性类型: rail");
  }

  /** 自定义组的 kind 也大小写不敏感；tag 键取**注册的组 id 原文**（与组定义不脱节）。 */
  @Test
  void customGroupKindMatchesCaseInsensitivelyAndKeepsRegisteredId() {
    GameMap base = graphOf(Map.of());
    GameMap withCanal = apply(base, PathwayGroupOperations.register(base, canalGroup()));

    GameMap after =
        apply(withCanal, EdgeOperations.setEdge(withCanal, "CaNaL", Set.of(E_LEFT), "merge"));

    assertThat(after.edges().get(E_LEFT).byPathway()).containsOnlyKeys("canal");
  }

  /** 重复注册同一组 id ⇒ 拒绝（fail-closed，不静默覆盖组定义）。 */
  @Test
  void duplicateRegistrationIsRejected() {
    GameMap base = graphOf(Map.of());
    PathwayGroup river = PathwayGroup.defaults().get("river");

    assertThatThrownBy(() -> PathwayGroupOperations.register(base, river))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("组已存在: river");
  }

  /** 注册只换 {@code pathwayGroups} 组件，其余 7 个恒 {@code Unchanged}。 */
  @Test
  void registrationOnlyChangesThePathwayGroupsComponent() {
    GameMap base = graphOf(Map.of());

    MapChangeSet cs = PathwayGroupOperations.register(base, canalGroup());

    assertThat(cs.pathwayGroups()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.edges()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathways()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  private static PathwayGroup canalGroup() {
    return new PathwayGroup("canal", "运河", "#3A7BD5", "人工水道", true, Map.of());
  }

  @Test
  void rejectsInvalidMode() {
    GameMap base = graphOf(Map.of());

    assertThatThrownBy(() -> EdgeOperations.setEdge(base, "river", Set.of(E_LEFT), "overwrite"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知 mode: overwrite");
  }

  @Test
  void rejectsEmptyEdges() {
    GameMap base = graphOf(Map.of());

    assertThatThrownBy(() -> EdgeOperations.setEdge(base, "river", Set.of(), "merge"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("edges 不得为空：一条 map.SetEdge 至少要标注一条边");
  }

  @Test
  void rejectsEdgeWhoseEndpointIsOutsideTheMap() {
    GameMap base = graphOf(Map.of());

    assertThatThrownBy(
            () ->
                EdgeOperations.setEdge(
                    base, "river", Set.of(new EdgeRef(H00, new HexCoord(9, 9))), "merge"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("边的端点不在图上: 0_0|9_9");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static GameMap apply(GameMap base, MapChangeSet cs) {
    return MapChangeSet.apply(cs, base);
  }

  private static EdgeTags tags(String kind, Map<String, Object> props) {
    return new EdgeTags(Map.of(kind, props));
  }

  private static GameMap graphOf(Map<EdgeRef, EdgeTags> edges) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H00, new HexCell(0.3));
    cells.put(H10, new HexCell(0.6));
    cells.put(H01, new HexCell(0.7));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        PathwayGroup.defaults(),
        edges,
        GenerationSpec.defaults(0L));
  }
}
