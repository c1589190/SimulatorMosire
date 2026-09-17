package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.util.state.FieldDelta;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 本类守的是水系的四件套：**降**（出边必然降高度）、**源**（河从最高格起）、**可寻址**（每条河有自己的 {@link PathwayId}——GSimulator 所有河流共享
 * {@code "river"} 一个身份，正是被取代的形态）、**分支是独立的线**。
 *
 * <p>主夹具是**锥形图**（半径 3、37 格）：高度随到中心的距离严格递减（0.9 − 0.2d），全陆地。锥上每格都有更低的在图纸邻格 ⇒ 终点只能是图缘格 —— {@link
 * #riverEndsAtOceanOrBoundary} 因此是**稳的**（不是恒真：「走 N 步就停」「停在洼地」都会红）。
 *
 * <p>★ 夹具的地形 key 与高度带**有意不对应**（全取 {@code plains} 而高度 0.3~0.9）：带边界是分类器的事，{@link HexCell} 不校验；对
 * RiverBuilder 有意义的只有 terrain 是不是 {@code "ocean"}。
 *
 * <p>★ 实测数字（2026-09-17，本机）：锥形图 seed 7 ⇒ **14 条河 / 19 条边**，最高格 {@code (0,0)} 所在链是 {@code
 * river-7-9}、**3 条边**（满足"长度 ≥ 2 才可断言流向"），4 条多边链的终点全是图缘格；seed 7 与 seed 8 的边集 **19 条中 13 条不同**；半径 6
 * 的生成图（seed 7）⇒ 127 格 / 44 格陆地 / **30 条河 / 44 条边**。
 */
class RiverBuilderTest {

  /** 主夹具的种子。实测见类注释。 */
  private static final long SEED = 7L;

  /** 与 {@link #SEED} 比形状的另一个种子（实测边集不同，见类注释）。 */
  private static final long OTHER_SEED = 8L;

  // ── 流向：降、源、终点 ───────────────────────────────────────────────────────

  /**
   * ★ 最高格必须是某条河的**上游端**。
   *
   * <p>断言三件：（1）夹具自证——全图扫出的最高格确是 {@code (0,0)}，且它的在图纸邻格**全部更低**（它没有更高邻格）；（2）存在 {@link Pathway} 使
   * {@code start() == (0,0)}；（3）该链长度 ≥ 2（实测 3，{@code river-7-9}）—— 长度 1
   * 的链不定义流向（R-11-h），夹具必须让断言落在有向的那一侧。
   */
  @Test
  void riverStartsAtHighestHex() {
    GameMap map = cone();
    MapChangeSet cs = RiverBuilder.build(map, SEED);
    List<Pathway> rivers = riversOf(cs);

    HexCoord peak =
        map.hexes().entrySet().stream()
            .max(Map.Entry.comparingByValue(java.util.Comparator.comparingDouble(HexCell::height)))
            .orElseThrow()
            .getKey();
    assertThat(peak).as("锥形图的最高格").isEqualTo(new HexCoord(0, 0));
    for (HexCoord nb : peak.neighbors()) {
      HexCell cell = map.hexes().get(nb);
      if (cell != null) {
        assertThat(cell.height())
            .as("最高格 %s 的邻格 %s 必须更低（夹具自证）", peak, nb)
            .isLessThan(map.hexes().get(peak).height());
      }
    }

    List<HexCoord> starts = new ArrayList<>();
    for (Pathway river : rivers) {
      starts.add(river.start());
    }
    assertThat(starts).as("某条河的上游端必须是最高格").contains(peak);
    Pathway peakRiver = rivers.get(starts.indexOf(peak));
    assertThat(peakRiver.length()).as("最高格所在链的边数（实测 river-7-9 为 3）").isGreaterThanOrEqualTo(2);
  }

  /**
   * 沿每条河的边序走出格序列，逐段断言高度**严格下降**（单边链跳过：一条边无所谓方向，R-11-h）。
   *
   * <p>★ 走法是游标式（不调 {@code start()}）：每条边必须接得上当前游标，接不上当场红 —— 分叉链（变异靶子）在这里也是干净的红。
   */
  @Test
  void riverNeverGoesUphill() {
    GameMap map = cone();

    for (Pathway river : riversOf(RiverBuilder.build(map, SEED))) {
      if (river.length() < 2) {
        continue;
      }
      List<EdgeRef> edges = river.edges();
      HexCoord cursor =
          edges.get(1).a().equals(edges.get(0).a()) || edges.get(1).b().equals(edges.get(0).a())
              ? edges.get(0).b()
              : edges.get(0).a();
      for (int i = 0; i < edges.size(); i++) {
        EdgeRef e = edges.get(i);
        HexCoord next = e.a().equals(cursor) ? e.b() : e.b().equals(cursor) ? e.a() : null;
        assertThat(next)
            .as("河 %s 的第 %d 条边 %s 接不上（游标在 %s）", river.id(), i + 1, e, cursor)
            .isNotNull();
        assertThat(map.hexes().get(next).height())
            .as("河 %s 第 %d 步必须严格下降（%s → %s）", river.id(), i + 1, cursor, next)
            .isLessThan(map.hexes().get(cursor).height());
        cursor = next;
      }
    }
  }

  /**
   * 每条多边河的 {@code end()} 是 ocean 格**或图缘格**（六邻至少一个不在图里）。
   *
   * <p>单边链跳过（R-11-h）：它的 {@code end()} 是规范序较大的那端，不是流向定义的下游。锥形图上不存在内陆洼地（每格都有更低的在图纸邻格） ⇒ 多边河的终点必然是图缘格
   * —— 实测 seed 7 的 4 条多边河终点为 {@code -3_2 / 3_-2 / 0_3 / 3_-1}，全是图缘。
   */
  @Test
  void riverEndsAtOceanOrBoundary() {
    GameMap map = cone();

    int checked = 0;
    for (Pathway river : riversOf(RiverBuilder.build(map, SEED))) {
      if (river.length() < 2) {
        continue;
      }
      HexCoord end = river.end();
      assertThat(isOceanOrBoundary(map, end))
          .as("河 %s 的终点 %s 必须是 ocean 或图缘格", river.id(), end)
          .isTrue();
      checked++;
    }
    assertThat(checked).as("多边河的条数（实测 4）").isEqualTo(4);
  }

  // ── 身份与结构 ───────────────────────────────────────────────────────────────

  /**
   * ★ 每条河有**互不相同**的 {@link PathwayId}，且恰为 {@code river-<seed>-1 .. river-<seed>-<n>}（发现序连续）。
   *
   * <p>这正是 GSimulator 做不到的事：那边所有河流共享 {@code "river"} 一个身份。ID 带 seed（spec §5.2「生成期由 (generationSeed,
   * 序号) 确定性派生」），故同一格式的 ID 只在同 seed 下出现。
   */
  @Test
  void riverIsAddressable() {
    List<Pathway> rivers = riversOf(RiverBuilder.build(cone(), SEED));

    List<PathwayId> ids = rivers.stream().map(Pathway::id).toList();
    assertThat(ids.stream().distinct().count()).as("PathwayId 必须互不相同").isEqualTo(ids.size());
    Set<String> expected = new LinkedHashSet<>();
    for (int i = 1; i <= rivers.size(); i++) {
      expected.add("river-" + SEED + "-" + i);
    }
    assertThat(ids.stream().map(PathwayId::value).toList())
        .as("ID 恰为 river-%s-1 .. river-%s-%d（发现序连续）", SEED, SEED, rivers.size())
        .containsExactlyElementsOf(expected);
  }

  /**
   * ★ 分支是**多条** Pathway（不是一条带分叉的），且没有任何 Pathway **内部**含度 ≥ 3 的格（度数用该 Pathway 自己的边集算）。
   *
   * <p>(2) 才是"分支不切开"的判别器：把分叉塞进一条线，构造期不拦、{@code start()} 到取端点时才抛 —— 这里直接从边集算度数，红得干净。
   */
  @Test
  void branchesAreSeparatePathways() {
    List<Pathway> rivers = riversOf(RiverBuilder.build(cone(), SEED));

    assertThat(rivers.size()).as("锥形图上分支点必然存在 ⇒ 多条河（实测 14）").isGreaterThanOrEqualTo(2);
    for (Pathway river : rivers) {
      assertThat(maxDegreeWithin(river))
          .as("河 %s 内部不得有度 ≥ 3 的格（分支点应把链断开）", river.id())
          .isLessThanOrEqualTo(2);
    }
  }

  /** 同 seed 同图两次 {@code build} ⇒ 变更集逐字段相等（record 值语义）。 */
  @Test
  void sameSeedGivesSameRivers() {
    GameMap map = cone();

    assertThat(RiverBuilder.build(map, SEED)).isEqualTo(RiverBuilder.build(map, SEED));
  }

  // ── 变更集形态 ───────────────────────────────────────────────────────────────

  /**
   * ★ 全平图 ⇒ **空变更集**：{@code isEmpty()} 为 true（不是 null、不是空 Upsert —— {@code Upsert} 构造期拒空）， 7 个组件全部
   * {@code Unchanged}。等高 ⇒ 无严格更低邻格 ⇒ 无出边 ⇒ 无河。
   */
  @Test
  void producesNoRiversOnFlatMap() {
    MapChangeSet cs = RiverBuilder.build(flatMap(), SEED);

    assertThat(cs).as("无河时返回空变更集，不是 null").isNotNull();
    assertThat(cs.isEmpty()).as("全平图 ⇒ isEmpty()").isTrue();
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathways())
        .as("无河时 pathways 必须 Unchanged（Upsert 拒空）")
        .isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathwayGroups()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.edges())
        .as("无河时 edges 必须 Unchanged（Upsert 拒空）")
        .isInstanceOf(FieldDelta.Unchanged.class);
  }

  /**
   * ★ 先 {@code apply} 再对账（铁律 5 的消费侧）：
   *
   * <p>(1) {@code after.pathways()} 与变更集的 upsert 集合一致；(2) 每条 Pathway 的每条边在 {@code after.edges()}
   * 里都有以**该 PathwayId** 为键的标注（不是 groupId —— spec §5.4 拆的就是"组定义 / 线的实例 / 边上标注"三层）；(3)
   * 边集**无重复**（链是边集的划分，两条河不共用任何一条边）。
   */
  @Test
  void edgesAreConsistentWithPathways() {
    GameMap map = cone();
    MapChangeSet cs = RiverBuilder.build(map, SEED);
    List<Pathway> rivers = riversOf(cs);

    GameMap after = MapChangeSet.apply(cs, map);
    Map<PathwayId, Pathway> expected = new LinkedHashMap<>();
    for (Pathway river : rivers) {
      expected.put(river.id(), river);
    }
    assertThat(after.pathways()).as("(1) apply 后的 pathways 与 upsert 集合一致").isEqualTo(expected);

    List<EdgeRef> allEdges = new ArrayList<>();
    for (Pathway river : rivers) {
      for (EdgeRef e : river.edges()) {
        EdgeTags tags = after.edges().get(e);
        assertThat(tags).as("(2) 边 %s 必须有标注", e).isNotNull();
        assertThat(tags.byPathway().keySet())
            .as("(2) 边 %s 的标注必须以河 %s 的 PathwayId 为键", e, river.id())
            .containsExactly(river.id().value());
        allEdges.add(e);
      }
    }
    assertThat(new HashSet<>(allEdges).size()).as("(3) 边集无重复（两条河不共用边）").isEqualTo(allEdges.size());
  }

  /**
   * ★ 不改输入的真靶子：夹具用**调用方持有的可变 {@code LinkedHashMap}** 构造（{@code GameMap} 构造期会拷贝冻结）， 调用后断言那份可变 map
   * 的内容与尺寸未变 —— 拿不可变对象自比是恒真的装饰。
   */
  @Test
  void doesNotMutateInput() {
    Map<HexCoord, HexCell> held = new LinkedHashMap<>();
    for (HexCoord c : HexGrid.withinRadius(new HexCoord(0, 0), 3).stream().sorted().toList()) {
      held.put(c, new HexCell("plains", 0.9 - 0.2 * new HexCoord(0, 0).distanceTo(c)));
    }
    Map<HexCoord, HexCell> before = new LinkedHashMap<>(held);
    GameMap map = GameMap.empty().withHexes(held).withTerrainTypes(TerrainCatalog.defaults());

    RiverBuilder.build(map, SEED);

    assertThat(held).as("调用方持有的可变 hexes 必须原样").isEqualTo(before);
    assertThat(held.size()).isEqualTo(before.size());
  }

  // ── 确定性与集成 ─────────────────────────────────────────────────────────────

  /**
   * ★ R-11-g 的护栏：同夹具换 seed ⇒ **边集不同**。★ 比边集、不比 ID —— ID 里带 seed，比 ID 会恒真。 实测 seed 7 与 seed 8 各 19
   * 条边、**13 条不同**（重合 6 条：两套随机选择只在一部分格上分叉）。
   */
  @Test
  void differentSeedChangesRiverShape() {
    GameMap map = cone();

    Set<String> edgesOf7 = edgeKeysOf(RiverBuilder.build(map, SEED));
    Set<String> edgesOf8 = edgeKeysOf(RiverBuilder.build(map, OTHER_SEED));

    assertThat(edgesOf7).isNotEqualTo(edgesOf8);
    Set<String> onlyIn7 = new LinkedHashSet<>(edgesOf7);
    onlyIn7.removeAll(edgesOf8);
    assertThat(onlyIn7.size()).as("实测 seed 7 有而 seed 8 没有的边数").isEqualTo(13);
  }

  /**
   * 集成冒烟：半径 6 的生成图（127 格，别用默认半径 80 的 13 秒图）上构建河网。
   *
   * <p>断言：无内部度 ≥ 3 的 Pathway、{@link #edgesAreConsistentWithPathways} 的 (2)(3)、两次调用相等。实测 seed 7：44
   * 格陆地 ⇒ 30 条河 / 44 条边（每个陆地格恰有一条出边）；多边河的终点里 11 条入海、19 条单边链跳过 —— **不断言** "ocean 或图缘"：真实图上有内陆洼地（7
   * 个，seed 42 实测），那是 R-11-a 认可的终点。
   */
  @Test
  void worksOnGeneratedMap() {
    GameMap map = MapGenerator.generate(specWithRadius(SEED, 6));
    MapChangeSet cs = RiverBuilder.build(map, SEED);
    List<Pathway> rivers = riversOf(cs);

    assertThat(rivers.size()).as("实测 30 条河").isEqualTo(30);
    for (Pathway river : rivers) {
      assertThat(maxDegreeWithin(river)).isLessThanOrEqualTo(2);
    }

    GameMap after = MapChangeSet.apply(cs, map);
    List<EdgeRef> allEdges = new ArrayList<>();
    for (Pathway river : rivers) {
      for (EdgeRef e : river.edges()) {
        EdgeTags tags = after.edges().get(e);
        assertThat(tags).as("边 %s 必须有标注", e).isNotNull();
        assertThat(tags.byPathway().keySet()).containsExactly(river.id().value());
        allEdges.add(e);
      }
    }
    assertThat(new HashSet<>(allEdges).size()).isEqualTo(allEdges.size());
    assertThat(RiverBuilder.build(map, SEED)).as("同 seed 同图 ⇒ 同变更集").isEqualTo(cs);
  }

  // ── 夹具与断言辅助 ───────────────────────────────────────────────────────────

  /**
   * 锥形夹具：半径 3（37 格），高度随到中心的距离严格递减（0.9 − 0.2d，d=0..3 ⇒ 0.9/0.7/0.5/0.3），全陆地。 同环格高度相等 ——
   * 等高的邻格不是出边候选，出边必然指向外环。
   */
  private static GameMap cone() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    HexCoord center = new HexCoord(0, 0);
    for (HexCoord c : HexGrid.withinRadius(center, 3).stream().sorted().toList()) {
      hexes.put(c, new HexCell("plains", 0.9 - 0.2 * center.distanceTo(c)));
    }
    return GameMap.empty().withHexes(hexes).withTerrainTypes(TerrainCatalog.defaults());
  }

  /** 全平图：同形状、全部 0.5 ⇒ 无严格更低邻格 ⇒ 无出边 ⇒ 无河。 */
  private static GameMap flatMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord c : HexGrid.withinRadius(new HexCoord(0, 0), 3).stream().sorted().toList()) {
      hexes.put(c, new HexCell("plains", 0.5));
    }
    return GameMap.empty().withHexes(hexes).withTerrainTypes(TerrainCatalog.defaults());
  }

  /** 只换半径的 spec（与 {@code MapGeneratorTest} 同形制）：其余组件原样取自默认值。 */
  private static GenerationSpec specWithRadius(long seed, int radius) {
    GenerationSpec d = GenerationSpec.defaults(seed);
    return new GenerationSpec(
        seed,
        radius,
        d.baseSeaLevel(),
        d.mainRidges(),
        d.fragments(),
        d.bands(),
        d.ridges(),
        d.fragmentParams(),
        d.contourCacheMax());
  }

  /** 从变更集里取出 pathways 的 Upsert 值（按发现序）。调用前应已断言是 Upsert。 */
  private static List<Pathway> riversOf(MapChangeSet cs) {
    assertThat(cs.pathways()).as("有河时 pathways 必须是 Upsert").isInstanceOf(FieldDelta.Upsert.class);
    @SuppressWarnings("unchecked")
    FieldDelta.Upsert<Pathway> upsert = (FieldDelta.Upsert<Pathway>) cs.pathways();
    return new ArrayList<>(upsert.entries().values());
  }

  /** 从变更集里取出 edges 的 Upsert key 集（{@code "a|b"} 串）。 */
  private static Set<String> edgeKeysOf(MapChangeSet cs) {
    assertThat(cs.edges()).as("有河时 edges 必须是 Upsert").isInstanceOf(FieldDelta.Upsert.class);
    @SuppressWarnings("unchecked")
    FieldDelta.Upsert<EdgeTags> upsert = (FieldDelta.Upsert<EdgeTags>) cs.edges();
    return new LinkedHashSet<>(upsert.entries().keySet());
  }

  /** 该 Pathway 自己的边集里出现的最大度数（分支不切开 ⇒ ≤ 2）。 */
  private static int maxDegreeWithin(Pathway river) {
    Map<HexCoord, Integer> degree = new LinkedHashMap<>();
    for (EdgeRef e : river.edges()) {
      degree.merge(e.a(), 1, Integer::sum);
      degree.merge(e.b(), 1, Integer::sum);
    }
    return degree.values().stream().max(Integer::compareTo).orElse(0);
  }

  /** 是 ocean 格，或六邻至少一个不在图里（图缘格）。 */
  private static boolean isOceanOrBoundary(GameMap map, HexCoord at) {
    if (map.hexes().get(at).terrain().equals("ocean")) {
      return true;
    }
    for (HexCoord nb : at.neighbors()) {
      if (!map.hexes().containsKey(nb)) {
        return true;
      }
    }
    return false;
  }
}
