package io.mosire.simos.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.generate.MapGenerator;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexDirection;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.state.FieldDelta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ **M2 关账判据第一条的落地：GSimulator 的 L1~L9 逐条有对应用例守着**（spec §9.1）。
 *
 * <p>每条用例名带 {@code L1}~{@code L9} 前缀、注释指向它的缺陷来源，让"哪条守哪条"是可读的。判据形态分三类：
 *
 * <ul>
 *   <li>**行为**（L1/L7）：真跑一遍往返 / 落盘，断言内容活着；
 *   <li>**结构**（L2/L8/L3 代数/L9 词表）：反射 + 冻结字面量——"这个类只能长这样"；
 *   <li>**全仓只有一份**（L3/L4/L6/L9/L8 调用点）：**源码扫描**——反射只能证明"当前这个类长这样"， 证明不了"没有第二份"，故读 {@code src/main}
 *       的文件树（计划 Step 2 的原话：扫描要写在测试里，报告会失传）。
 * </ul>
 *
 * <p>★ 四条扫描共用同一套底座（R-14-c）：仓库根从 surefire 的工作目录（模块根 {@code simos-map/}）向上找 {@code artifactId ==
 * simos-parent} 的 {@code pom.xml}，**找不到就 fail**——默默跳过就是恒真的假护栏； 每条扫描都带"扫到的文件数 &gt;
 * 0"的自证，防路径写错导致零命中恒真。本机 {@code grep} 可能是 ugrep （尊重 .gitignore、跳隐藏目录），但用例读的是文件系统（{@code
 * Files.walk}），不受它影响。
 *
 * <p>★ L5 不在本文件：它的判据是**计数注入**（一次 {@code regionOf} 恰一次 {@code Map.get}），注入点是 {@code RegionIndex}
 * 的**包私有**构造器，本类在 {@code io.mosire.simos.map} 包进不去——故单独成 {@code
 * region/RegionIndexGuardTest}（R-14-a）。
 */
class RegressionGuardsTest {

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  private static final HexCoord H_C = new HexCoord(-3, 2);

  private static final HexCoord H_D = new HexCoord(2, -4);

  private static final EdgeRef EDGE_AB = new EdgeRef(H_A, H_B);

  private static final EdgeRef EDGE_BC = new EdgeRef(H_B, H_C);

  private static final EdgeRef EDGE_AC = new EdgeRef(H_A, H_C);

  private static final EdgeRef EDGE_BD = new EdgeRef(H_B, H_D);

  /** L7 的落盘种子：**非零**（GSimulator 丢的正是它，0 会与"空图规范种子"混淆）。 */
  private static final long PERSISTED_SEED = 42L;

  /** L7 夹具里 {@code H_A} 的海拔：非平凡值（既不是 0 也不是 1）。 */
  private static final double PERSISTED_HEIGHT = 0.37;

  /** L4② 的冻结字面量：region 包的顶层类型集合（控制器 2026-09-17 实测 {@code ls}）。 */
  private static final Set<String> REGION_PACKAGE_TYPES =
      Set.of("Region", "RegionId", "RegionIndex", "RegionBoundary", "RegionMeta");

  /** L6① 的冻结白名单：{@code "q_r"} 拼接唯一合法的落点（toString；HexCoord.java:85）。 */
  private static final String HEXCOORD_FILE =
      "simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java";

  /** L8② 的冻结白名单：{@code new GameMap(} 的逐文件调用点数（控制器实测 11 处）。 */
  private static final Map<String, Long> GAME_MAP_CONSTRUCTOR_CALL_SITES =
      Map.of(
          "simos-map/src/main/java/io/mosire/simos/map/GameMap.java", 9L,
          "simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java", 1L,
          "simos-map/src/main/java/io/mosire/simos/map/generate/MapGenerator.java", 1L);

  /** L9③ 的冻结白名单：只在 {@code TerrainCatalog} 里出现的四个 key（其余三词有合法常量，不进扫描）。 */
  private static final List<String> CATALOG_EXCLUSIVE_KEYS =
      List.of("low_hills", "mountains", "plateau", "plateau_mountains");

  // ── L1：子节点写 edges 静默丢失 ─────────────────────────────────────────────

  /**
   * L1（GSimulator 的 {@code MapDiff} 手工维护，对非 root 子节点写连通性静默丢失——四个漂移字段里最贵的一个）： **只有 {@code edges}
   * 一个组件变**的图对，变更集必须把它完整带过去。
   *
   * <p>★ 这就是 simos 侧的"非 root"形态：GSimulator 只在**非 root 写**时丢数据（root 走全量保存，root 用例会掩盖它）； 这里"其余 6
   * 个组件逐字段相同"正是那个不被全量保存掩盖的形态。全组件都变的往返（Task 7 的 {@code
   * everyGameMapComponentParticipatesInTheChangeSet}）会被别的组件的重建**掩盖**，故本条单独存在。
   *
   * <p>★ 夹具刻意让 {@code edges} 的差异是**增删并存**（Patch）：删一条、改一条、加一条、留一条—— "只改了一条边产生空 diff / 只保增不保删"这两个
   * GSimulator 的病灶形态都会在这里现形。
   */
  @Test
  void L1_edgesSurviveRoundTripOnNonRoot() {
    Map<EdgeRef, EdgeTags> baseEdges = new LinkedHashMap<>();
    baseEdges.put(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2))));
    baseEdges.put(EDGE_BC, new EdgeTags(Map.of("river", Map.of("depth", 3))));
    baseEdges.put(EDGE_AC, new EdgeTags(Map.of("rail", Map.of("gauge", 1435))));
    GameMap base = GameMap.empty().withHexes(fourHexes()).withEdges(baseEdges);

    Map<EdgeRef, EdgeTags> targetEdges = new LinkedHashMap<>();
    targetEdges.put(EDGE_BC, new EdgeTags(Map.of("river", Map.of("depth", 7)))); // 改
    targetEdges.put(EDGE_AC, base.edges().get(EDGE_AC)); // 留（与 base 同值 ⇒ 不进 diff）
    targetEdges.put(EDGE_BD, new EdgeTags(Map.of("sea", Map.of("depth", 9)))); // 加
    GameMap target = base.withEdges(targetEdges);

    MapChangeSet cs = MapChangeSet.between(base, target);
    assertThat(cs.edges().changed()).as("edges 必须真的进了 diff（不是 Unchanged）").isTrue();
    assertThat(cs.edges())
        .as("增删并存 ⇒ Patch（丢删除正是 GSimulator 只改一条边产出空 diff 的病根）")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.hexes().changed()).as("非 root 形态：其余 6 个组件一个都不许进 diff").isFalse();
    assertThat(cs.regions().changed()).isFalse();
    assertThat(cs.cities().changed()).isFalse();
    assertThat(cs.terrainTypes().changed()).isFalse();
    assertThat(cs.pathways().changed()).isFalse();
    assertThat(cs.pathwayGroups().changed()).isFalse();

    GameMap rebuilt = MapChangeSet.apply(cs, base);
    assertThat(rebuilt.edges()).as("往返后 edges 与 target 逐项相等").isEqualTo(target.edges());
    assertThat(rebuilt).as("铁律 5：apply(between(base, target), base) == target").isEqualTo(target);
  }

  // ── L2：双份连通性存储 ─────────────────────────────────────────────────────

  /**
   * L2（GSimulator 的 {@code HexCell.edgeTags}/{@code riverMask} 是第二份连通性存储，Java 侧只读不写、 前端一存就把所有边的
   * props 抹平）：{@code HexCell} 的组件清单**恰为** {@code [terrain, height]}（冻结字面量）。
   */
  @Test
  void L2_hexCellHasNoConnectivityField() {
    assertThat(recordComponentNames(HexCell.class))
        .as("HexCell 不含任何连通性字段（主存储只有 GameMap.edges 一份）")
        .containsExactly("terrain", "height");
  }

  // ── L3：方向数组错位 ───────────────────────────────────────────────────────

  /**
   * L3（GSimulator 全仓 8 份方向表、两种互逆索引序，根因是两张主表都 package-private 不导出 API，消费方只能复制）： ① {@code
   * HexDirection} 是 enum 且恰 6 项；② 代数自洽（{@code opposite}/{@code next}/{@code prev} 的恒等式）； ③
   * **源码扫描**：{@code simos-map} 与 {@code simos-util} 的 {@code src/main} 里 {@code int[][]} 为 **0**。
   *
   * <p>★ ③ 剔注释：实测现状连注释里也是 0，但 {@code HexVertex} 的 Javadoc 证明注释会携带代码形态的文本，
   * 剔掉后"表"与"谈表"分开，判别力不被稀释。反射证不了"没有第二份"，这条只能读源码树。
   */
  @Test
  void L3_thereIsExactlyOneDirectionTable() {
    assertThat(HexDirection.class.isEnum()).as("方向表是 enum（索引即边序号）").isTrue();
    assertThat(HexDirection.values()).as("六条边").hasSize(6);
    for (HexDirection d : HexDirection.values()) {
      assertThat(d.opposite().opposite()).as("%s 的反向之反向是自身", d).isEqualTo(d);
      assertThat(d.next().prev()).as("%s 的顺时针下一格再逆时针回来是自身", d).isEqualTo(d);
      HexDirection sixSteps = d;
      for (int i = 0; i < 6; i++) {
        sixSteps = sixSteps.next();
      }
      assertThat(sixSteps).as("%s 顺时针连走 6 步回原项", d).isEqualTo(d);
    }

    List<Path> trees = new ArrayList<>();
    trees.addAll(javaFilesUnder(mapMain()));
    trees.addAll(javaFilesUnder(utilMain()));
    Map<String, Long> hits = occurrencesByFile(trees, "int[][]");
    assertThat(hits).as("src/main 里不许有第二个 int[][] 方向表（HexDirection 是唯一一份）").isEmpty();
  }

  // ── L4：三个 region 概念 ───────────────────────────────────────────────────

  /**
   * L4（GSimulator 的 region 概念实测 4 活 + 1 死：{@code Province}/{@code CompressedRegion}/……）： ① {@code
   * simos-map/src/main} 声明的类型名里含 {@code Province}/{@code Territory}/{@code Zone} 的为 **0**
   * （剔注释后提取声明——注释里提到 GSimulator 的 Province 是叙述，不是第二份实现）； ② {@code region} 包的顶层类型集合**恰为**冻结字面量（五个）。
   */
  @Test
  void L4_thereIsExactlyOneRegionType() {
    List<String> declared = new ArrayList<>();
    for (Path file : javaFilesUnder(mapMain())) {
      declared.addAll(declaredTypeNames(file));
    }
    assertThat(declared).as("类型声明的提取不能是空的（空 = 正则失效，护栏恒真）").isNotEmpty();
    List<String> forbidden =
        declared.stream().filter(RegressionGuardsTest::isASecondRegionConcept).toList();
    assertThat(forbidden)
        .as("simos-map 里不许再长出 Province/Territory/Zone 任何一个名字的第二个 region 概念")
        .isEmpty();

    Set<String> regionTypes = new LinkedHashSet<>();
    for (Path file :
        javaFilesUnder(repoRoot().resolve("simos-map/src/main/java/io/mosire/simos/map/region"))) {
      regionTypes.addAll(declaredTypeNames(file));
    }
    assertThat(regionTypes)
        .as("region 包的顶层类型集合恰为五个（多一个就是第二个概念）")
        .containsExactlyInAnyOrderElementsOf(REGION_PACKAGE_TYPES);
  }

  // ── L6：坐标表述不一致 ─────────────────────────────────────────────────────

  /**
   * L6（GSimulator 的坐标串散落各处：{@code q_r} 拼接、{@code minQ_minR|maxQ_maxR}、浮点舍入的 {@code
   * cornerKey}——同一格在不同入口对不上键）：① {@code "q_r"} 拼接（形如 {@code q + "_" + r}）在 {@code
   * simos-map/src/main} **剔注释后**命中**恰 1 处**，且就在 {@code hex/HexCoord.java}（{@code toString}）； ②
   * {@code HexCoord} 的 record 组件恰为 {@code [q, r]}。
   *
   * <p>★ **必须剔注释**：实测 {@code HexVertex.java} 的 Javadoc 里就有一句 GSimulator 的 {@code cornerKey(...) +
   * "_" + ...}，不过滤就得把 Javadoc 列进白名单，判别力被稀释。
   */
  @Test
  void L6_hexCoordIsTheOnlyCoordinateType() {
    Map<String, Long> hits = occurrencesByFile(javaFilesUnder(mapMain()), "\"_\"");
    long total = hits.values().stream().mapToLong(Long::longValue).sum();
    assertThat(total).as("q_r 拼接全 simos-map/src/main 恰 1 处（多了就是第二份坐标串实现）").isEqualTo(1);
    assertThat(hits.keySet())
        .as("唯一的落点是 HexCoord.toString（它的 parse 是另一半，JSON 边界只此一对）")
        .containsExactly(HEXCOORD_FILE);

    assertThat(recordComponentNames(HexCoord.class)).containsExactly("q", "r");
  }

  // ── L7：无海拔无种子落盘 ───────────────────────────────────────────────────

  /**
   * L7（GSimulator 的海拔只活在内存 LRU、问到就丢；入参 seed 从不落盘、MCP 路径连 contour 都不写 ⇒ 不可复现）： ① 带**非平凡 height**
   * 与**非零 seed** 的 {@code GameMap} 经 Jackson 序列化→反序列化，整图相等，并**逐字段**断言某格 height 与 {@code
   * spec().seed()}； 键序用 JSON 字段名序钉住（见下）； ② 生成图同样往返；③ **同 seed 生成两次结果相同**（在本条里独立再钉一次，不是一句"别处已有"的注释）。
   *
   * <p>★ map 的 key 序列化走 {@code toString()}、反序列化**委托给五个 key 类型各自的 {@code static parse}**——
   * 这正是"{@code q_r} 只在 JSON 边界出现"的那条边界本身，全部注册在测试侧，{@code src/main} 不加任何 Jackson 注解。
   *
   * <p>★ **保序断言的边界**（变异自证期实测换来的裁定）：设计上保序的是 {@code GameMap} 的 7 个插入序 map 与 {@code TerrainCatalog}
   * 词表（{@code GameMap} 的 Javadoc 明说弃 {@code Map.copyOf} 就是为了字节级往返）； 唯独 {@code Region.hexes} 用
   * {@code Set.copyOf}，**迭代序明确不许依赖**——实测它随哈希槽位与 JVM 盐漂（两组候选键各实测 40 个独立 JVM，一组翻转
   * 12/40，另一组换实参次序仍翻转），把它纳入逐字节断言等于要求一个设计上不成立的性质， 故序断言只落在 设计声明保序的组件上，以 JSON 字段名序（冻结字面量）为准。
   */
  @Test
  void L7_heightAndSeedSurvivePersistence() throws Exception {
    ObjectMapper mapper = jsonMapper();
    GameMap original = persistedShape();

    byte[] first = mapper.writeValueAsBytes(original);
    GameMap back = mapper.readValue(first, GameMap.class);
    assertThat(back).as("整图 JSON 往返后逐组件相等").isEqualTo(original);
    JsonNode firstTree = mapper.readTree(first);
    JsonNode secondTree = mapper.readTree(mapper.writeValueAsBytes(back));
    assertThat(fieldNames(firstTree, "hexes"))
        .as("落盘的 hexes 键序 = 插入序（GameMap 保序的正是这个，Map.copyOf 形态会散掉）")
        .containsExactly("5_5", "0_0", "-3_2", "2_-4");
    assertThat(fieldNames(secondTree, "hexes"))
        .as("hexes 键序在往返后不漂移")
        .containsExactlyElementsOf(fieldNames(firstTree, "hexes"));
    assertThat(fieldNames(firstTree, "terrainTypes"))
        .as("落盘的地形词表序 = TerrainCatalog 的定序")
        .containsExactly(
            "ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");
    assertThat(fieldNames(secondTree, "terrainTypes"))
        .as("地形词表序在往返后不漂移")
        .containsExactlyElementsOf(fieldNames(firstTree, "terrainTypes"));
    assertThat(back.hexes().get(H_A).height())
        .as("海拔逐字段活着（GSimulator 的海拔问到就丢）")
        .isEqualTo(PERSISTED_HEIGHT);
    assertThat(back.spec().seed()).as("seed 随结果落盘").isEqualTo(PERSISTED_SEED);

    GenerationSpec tiny = specWithRadius(PERSISTED_SEED, 2);
    GameMap generated = MapGenerator.generate(tiny);
    GameMap generatedBack = mapper.readValue(mapper.writeValueAsBytes(generated), GameMap.class);
    assertThat(generatedBack).as("生成图整图往返相等").isEqualTo(generated);
    assertThat(generatedBack.spec().seed()).isEqualTo(PERSISTED_SEED);
    assertThat(MapGenerator.generate(tiny))
        .as("同 seed 生成两次结果相同（seed 落盘的意义就是拿它能再算出一模一样的图）")
        .isEqualTo(MapGenerator.generate(tiny));
  }

  // ── L8：12 参数构造复制 ────────────────────────────────────────────────────

  /**
   * L8（GSimulator 的 {@code MapData} 12 参数构造、主源码 17 处逐字段复制，改一处漏一处）： ① 反射——{@code GameMap} 的构造器**恰 1
   * 个**且形参数为 **8**（record 规范构造器，别无分店）； ② 源码扫描——{@code new GameMap(} 在 {@code simos-map/src/main}
   * 的**逐文件调用点数**冻结为白名单 （GameMap 9、MapChangeSet 1、MapGenerator 1，剔注释）；新增调用点即红，替换走 {@code with*}。
   */
  @Test
  void L8_gameMapHasNoTwelveArgConstructor() {
    Constructor<?>[] constructors = GameMap.class.getDeclaredConstructors();
    assertThat(constructors).as("GameMap 只有规范构造器（12 参数的复制形态不许回来）").hasSize(1);
    assertThat(constructors[0].getParameterCount()).as("8 个组件，一个不多").isEqualTo(8);

    Map<String, Long> callSites = occurrencesByFile(javaFilesUnder(mapMain()), "new GameMap(");
    assertThat(callSites)
        .as("new GameMap( 的逐文件调用点数冻结（新增即红：换组件走 with*，从零建图走这两处登记点）")
        .containsExactlyInAnyOrderEntriesOf(GAME_MAP_CONSTRUCTOR_CALL_SITES);
  }

  // ── L9：地形词表分裂 ───────────────────────────────────────────────────────

  /**
   * L9（GSimulator 至少 9 份互不相同的地形词表，两份在同一批 key 上完全分叉、兜底色串味）：① {@code KEYS} 恰 7 项且**写死**（U1 词表，迭代序 =
   * 高度升序 = 落盘序）；② 阈值结构性断言——并集恰 {@code [0,1]}、 两两不重叠、逐项升序（本文件独立成立，不引用 {@code TerrainCatalogTest}
   * 的结论）； ③ 源码扫描——**只在 {@code TerrainCatalog} 出现的四项 key** 不得出现在 {@code simos-map/src/main}
   * 的其它文件里（{@code ocean}/{@code plains}/{@code desert} 有合法常量，不进扫描）。
   *
   * <p>★ ③ **不剔注释**：实测四词连注释里都是零命中；注释里出现同样算词表泄漏，判据取更强的一侧。
   */
  @Test
  void L9_thereIsExactlyOneTerrainCatalog() {
    assertThat(TerrainCatalog.KEYS)
        .as("U1 词表：7 项、此序（高度升序）")
        .containsExactly(
            "ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");

    List<TerrainType> byHeight = new ArrayList<>(TerrainCatalog.defaults().values());
    byHeight.sort(Comparator.comparingDouble(TerrainType::minHeight));
    assertThat(byHeight).hasSize(7);
    assertThat(byHeight.getFirst().minHeight()).as("并集恰 [0,1]：最低带从 0 起").isZero();
    assertThat(byHeight.getLast().maxHeight()).as("并集恰 [0,1]：最高带到 1 止").isEqualTo(1.0);
    for (int i = 0; i + 1 < byHeight.size(); i++) {
      TerrainType lower = byHeight.get(i);
      TerrainType upper = byHeight.get(i + 1);
      assertThat(lower.minHeight())
          .as("%s 在 %s 之前（逐项升序）", lower.key(), upper.key())
          .isLessThan(upper.minHeight());
      assertThat(lower.maxHeight())
          .as("%s 与 %s 相接（同一批字面量，浮点 == 成立；有缝 = 并集盖不住 [0,1]）", lower.key(), upper.key())
          .isEqualTo(upper.minHeight());
    }
    for (int i = 0; i < byHeight.size(); i++) {
      for (int j = i + 1; j < byHeight.size(); j++) {
        assertThat(byHeight.get(i).maxHeight())
            .as("%s 与 %s 两两不重叠", byHeight.get(i).key(), byHeight.get(j).key())
            .isLessThanOrEqualTo(byHeight.get(j).minHeight());
      }
    }

    String catalogFile = "simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java";
    Map<String, Long> leaked = new LinkedHashMap<>();
    for (Path file : javaFilesUnder(mapMain())) {
      if (relative(file).equals(catalogFile)) {
        continue;
      }
      for (String key : CATALOG_EXCLUSIVE_KEYS) {
        if (rawContent(file).contains(key)) {
          leaked.merge(relative(file) + " 含 " + key, 1L, Long::sum);
        }
      }
    }
    assertThat(leaked).as("四个独占 key 不得出现在 TerrainCatalog 之外的任何文件（连注释都算）").isEmpty();
  }

  // ── R1：全仓恰一份 FieldDelta（M3 spec §六；其落点在 util.state，C7） ──────────

  /**
   * ★ **R1（M3）**：全仓恰一份 {@code FieldDelta} 声明。
   *
   * <p>病灶形态：M3 有三个变更集（map / social / unit）共用这一套差异语义——若哪个模块自己再写一份， 两份的语义立刻开始漂移（`Patch`
   * 的先删后增、`Upsert` 的保序冻结都可能只改一边），而且**没有任何东西会响**。
   */
  @Test
  void R1_thereIsExactlyOneFieldDelta() {
    Map<String, Long> hits = new LinkedHashMap<>();
    for (String module : List.of("simos-util", "simos-map", "simos-social", "simos-unit")) {
      for (Path file : javaFilesUnder(repoRoot().resolve(module + "/src/main"))) {
        for (String line : rawLines(file)) {
          if (line.contains("interface FieldDelta")) {
            hits.merge(relative(file), 1L, Long::sum);
          }
        }
      }
    }
    assertThat(hits)
        .as("四个模块的 src/main 里必须恰有一份 FieldDelta 声明（第二份 = 语义必然漂移）")
        .containsExactly(
            entry("simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java", 1L));
  }

  // ── 源码扫描的公共底座（R-14-c：四条扫描共用） ─────────────────────────────

  /** surefire 的工作目录是模块根 {@code simos-map/}，仓库根要向上找；**找不到就 fail**（默默跳过 = 恒真）。 */
  private static Path repoRoot() {
    Path start = Path.of("").toAbsolutePath();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      Path pom = dir.resolve("pom.xml");
      if (!Files.isRegularFile(pom)) {
        continue;
      }
      try {
        if (isRepoRootPom(Files.readString(pom))) {
          return dir;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return fail("找不到 simos-parent 仓库根（从 " + start + " 向上）：源码扫描无法进行");
  }

  /**
   * 只认**自己的** artifactId 是 {@code simos-parent} 的 pom——模块 pom 的 {@code <parent>} 块里也有这个名字，
   * 首跑实测就因此把模块根误判成了仓库根（{@code simos-map/simos-map/src/main} 不存在）。判据：该名字出现在 {@code </parent>} 之后（根
   * pom 没有 parent 块，亦成立）。
   */
  private static boolean isRepoRootPom(String pomText) {
    int own = pomText.indexOf("<artifactId>simos-parent</artifactId>");
    int parentEnd = pomText.indexOf("</parent>");
    return own >= 0 && own > parentEnd;
  }

  private static Path mapMain() {
    return repoRoot().resolve("simos-map/src/main");
  }

  private static Path utilMain() {
    return repoRoot().resolve("simos-util/src/main");
  }

  /** 目录下的全部 {@code .java}（有序）；**空目录即 fail**——路径写错时扫描会变成零命中恒真。 */
  private static List<Path> javaFilesUnder(Path dir) {
    try (Stream<Path> stream = Files.walk(dir)) {
      List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      assertThat(files).as("扫描目录 %s 里必须有 .java（空 = 路径写错，护栏恒真）", dir).isNotEmpty();
      return files;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 只保留代码行：trim 后以注释标记开头的行剔除（L6 必须剔，L3/L4/L8 沿用同一底座）。 */
  private static List<String> codeLines(Path file) {
    return rawLines(file).stream().filter(line -> !isCommentLine(line)).toList();
  }

  private static List<String> rawLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String rawContent(Path file) {
    return String.join("\n", rawLines(file));
  }

  private static boolean isCommentLine(String line) {
    String t = line.stripLeading();
    return t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("//");
  }

  private static String relative(Path file) {
    return repoRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
  }

  /** 逐文件数某子串在**代码行**里的出现次数（只收 &gt;0 的文件，键为仓库相对路径）。 */
  private static Map<String, Long> occurrencesByFile(List<Path> files, String token) {
    Map<String, Long> hits = new LinkedHashMap<>();
    for (Path file : files) {
      long n = 0;
      for (String line : codeLines(file)) {
        for (int i = line.indexOf(token); i >= 0; i = line.indexOf(token, i + 1)) {
          n++;
        }
      }
      if (n > 0) {
        hits.put(relative(file), n);
      }
    }
    return hits;
  }

  private static final Pattern TYPE_DECL =
      Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

  /** 一份源文件（剔注释后）声明的全部类型名，含嵌套（L4 的判据对象）。 */
  private static List<String> declaredTypeNames(Path file) {
    List<String> names = new ArrayList<>();
    Matcher m = TYPE_DECL.matcher(String.join("\n", codeLines(file)));
    while (m.find()) {
      names.add(m.group(2));
    }
    return names;
  }

  private static boolean isASecondRegionConcept(String typeName) {
    String low = typeName.toLowerCase(Locale.ROOT);
    return low.contains("province") || low.contains("territory") || low.contains("zone");
  }

  private static List<String> recordComponentNames(Class<? extends Record> type) {
    List<String> names = new ArrayList<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── L7 的夹具与 JSON 边界 ──────────────────────────────────────────────────

  /** 八组件全非空、带非平凡 height 与非零 seed 的落盘形态（L7 的靶子就是它）。 */
  private static GameMap persistedShape() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell("plains", PERSISTED_HEIGHT));
    hexes.put(H_B, new HexCell("mountains", 0.62));
    hexes.put(H_C, new HexCell("ocean", 0.21));
    hexes.put(H_D, new HexCell("desert", 0.50));
    return new GameMap(
        hexes,
        Map.of(
            new RegionId("r1"),
            Region.of(new RegionId("r1"), "区域 r1", Set.of(H_A, H_B), RegionMeta.empty())),
        Map.of(
            new CityId("c1"),
            new City(
                new CityId("c1"), "城 c1", H_A, new RegionId("r1"), Map.of("population", 1000))),
        TerrainCatalog.defaults(),
        Map.of(
            new PathwayId("p1"),
            new Pathway(
                new PathwayId("p1"),
                "线 p1",
                "road",
                List.of(EDGE_AB, EDGE_BC),
                Map.of("width", 2))),
        Map.of("road", new PathwayGroup("road", "组 road", "#8B7355", null, true, Map.of())),
        Map.of(EDGE_AB, new EdgeTags(Map.of("road", Map.of("width", 2)))),
        GenerationSpec.defaults(PERSISTED_SEED));
  }

  private static Map<HexCoord, HexCell> fourHexes() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell("plains", 0.35));
    hexes.put(H_B, new HexCell("ocean", 0.10));
    hexes.put(H_C, new HexCell("mountains", 0.70));
    hexes.put(H_D, new HexCell("desert", 0.50));
    return hexes;
  }

  /** 只换半径的 spec（L7 的生成部分用小图，其余组件原样取自默认值）。 */
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

  /** 某组件的 JSON 字段名**按落盘序**读出（ObjectNode 内部是 LinkedHashMap，遇序即存序）。 */
  private static List<String> fieldNames(JsonNode tree, String field) {
    List<String> names = new ArrayList<>();
    tree.get(field).fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** key 的反序列化委托给五个 key 类型各自的 {@code static parse}——JSON 边界只此一对（toString/parse）。 */
  private static ObjectMapper jsonMapper() {
    SimpleModule keys = new SimpleModule();
    keys.addKeyDeserializer(HexCoord.class, parsed(HexCoord::parse));
    keys.addKeyDeserializer(EdgeRef.class, parsed(EdgeRef::parse));
    keys.addKeyDeserializer(RegionId.class, parsed(RegionId::parse));
    keys.addKeyDeserializer(CityId.class, parsed(CityId::parse));
    keys.addKeyDeserializer(PathwayId.class, parsed(PathwayId::parse));
    return new ObjectMapper().registerModule(keys);
  }

  private static KeyDeserializer parsed(Function<String, ?> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext ctxt) {
        return parse.apply(key);
      }
    };
  }
}
