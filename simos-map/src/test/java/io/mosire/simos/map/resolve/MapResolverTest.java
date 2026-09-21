package io.mosire.simos.map.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * map: 命名空间寻址。夹具：两格的小图 + 两个**重叠**区域（插入序与字典序**相反**）+ 一座城市，经 {@code MapSnapshot} 装进真实 {@code
 * SimulationState}——不用 Mockito 假状态，"键 == namespace()"的接缝 由此顺带被钉住。
 */
class MapResolverTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp TS = SimosTimestamp.of(5);
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord NOWHERE = new HexCoord(9, 9);

  private GameMap map;
  private ResolveContext ctx;

  @BeforeEach
  void setUp() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell(0.1));
    hexes.put(H10, new HexCell(0.2));
    // ★ 两个区域都盖住 H00（多从属，M8-U1），插入序 r2 在前、r1 在后——与 RegionId 字典序**相反**：
    // RegionIndex 全保留并按字典序 ⇒ [r1, r2]；沿插入序的线性扫描 ⇒ [r2, r1]（regionOfHexUsesTheIndex 的靶子）。
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(new RegionId("r2"), Region.of(new RegionId("r2"), "区域二", Set.of(H00), null));
    regions.put(new RegionId("r1"), Region.of(new RegionId("r1"), "区域一", Set.of(H00, H10), null));
    Map<CityId, City> cities = new LinkedHashMap<>();
    cities.put(new CityId("c1"), new City(new CityId("c1"), "城一", H00, null, Map.of()));
    map =
        new GameMap(
            hexes,
            TerrainBlocks.uniform(hexes.keySet(), "plain"),
            regions,
            cities,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));
    MapSnapshot snapshot = new MapSnapshot(REF, TS, map);
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, TS), Map.of("map", snapshot), InMemoryInfoSystem.empty());
    ctx = new ResolveContext(state, TS);
  }

  private QueryResult resolve(String text) {
    return new MapResolver().resolve(Address.parse(text), ctx);
  }

  @Test
  void resolvesHexByAddress() {
    QueryResult result = resolve("map:m1:hex.0_0");
    assertThat(result.candidates()).hasSize(1);
    ResolvedSubject subject = result.candidates().getFirst();
    assertThat(subject.id()).isEqualTo(new SubjectId("map.hex", "0_0"));
    assertThat(subject.typeName()).isEqualTo("Hex");
    assertThat(subject.canonicalAddress()).isEqualTo("map:m1:hex.0_0");
    // 别只断字符串：候选的 localId 必须解析回图里真实存在的那一格
    assertThat(map.hexes()).containsKey(HexCoord.parse(subject.id().localId()));
  }

  /** Index 段是 hex 的 Human 形式（总纲 §4.2：Human 进、canonical 出——canonical 不保留 `[0,0]` 形态）。 */
  @Test
  void resolvesIndexFormToTheSameHex() {
    QueryResult byIndex = resolve("map:m1:[0,0]");
    QueryResult byEntity = resolve("map:m1:hex.0_0");
    assertThat(byIndex.candidates()).hasSize(1);
    assertThat(byIndex.candidates().getFirst().id())
        .isEqualTo(byEntity.candidates().getFirst().id());
    assertThat(byIndex.candidates().getFirst().canonicalAddress())
        .isEqualTo("map:m1:hex.0_0")
        .isEqualTo(byEntity.candidates().getFirst().canonicalAddress());
  }

  @Test
  void resolvesRegionByAddress() {
    QueryResult result = resolve("map:m1:region.r1");
    assertThat(result.candidates()).hasSize(1);
    ResolvedSubject subject = result.candidates().getFirst();
    assertThat(subject.id()).isEqualTo(new SubjectId("map.region", "r1"));
    assertThat(subject.typeName()).isEqualTo("Region");
    assertThat(subject.canonicalAddress()).isEqualTo("map:m1:region.r1");
  }

  @Test
  void resolvesCityByAddress() {
    QueryResult result = resolve("map:m1:city.c1");
    assertThat(result.candidates()).hasSize(1);
    ResolvedSubject subject = result.candidates().getFirst();
    assertThat(subject.id()).isEqualTo(new SubjectId("map.city", "c1"));
    assertThat(subject.typeName()).isEqualTo("City");
    assertThat(subject.canonicalAddress()).isEqualTo("map:m1:city.c1");
  }

  @Test
  void resolvesMapItself() {
    QueryResult result = resolve("map:m1");
    assertThat(result.candidates()).hasSize(1);
    ResolvedSubject subject = result.candidates().getFirst();
    assertThat(subject.id()).isEqualTo(new SubjectId("map", "m1"));
    assertThat(subject.typeName()).isEqualTo("Map");
    assertThat(subject.canonicalAddress()).isEqualTo("map:m1");
  }

  /** 合法但不存在的坐标 ⇒ 空候选，**不抛**（空列表 = 没有候选，不是错误）。 */
  @Test
  void unknownHexGivesEmptyNotException() {
    assertThat(resolve("map:m1:hex.9_9").candidates()).isEmpty();
    assertThat(resolve("map:m1:region.no-such").candidates()).isEmpty();
    assertThat(resolve("map:m1:city.no-such").candidates()).isEmpty();
  }

  /** 非 map 命名空间的地址不由本解析器认领 ⇒ 空候选（未知命名空间抛是注册表的职责，不在这里）。 */
  @Test
  void wrongNamespaceIsRejected() {
    MapResolver resolver = new MapResolver();
    assertThat(resolver.namespace()).isEqualTo("map");
    assertThat(resolver.resolve(Address.parse("social:Map1:hex.4_3"), ctx).candidates()).isEmpty();
  }

  /**
   * ★ V3（取代 M8-Q6）：重叠区域 + 插入序与字典序**相反** ⇒ {@code regionOfHex} 必须按**定义序**给出 {@code [r2, r1]}，末位 =
   * 最顶层 = {@code r1}；字典序会给 {@code [r1, r2]}（末位 {@code r2}）⇒ "退回字典序"这条红。
   *
   * <p>★ L5 的"索引而非线性扫描"判据自 V3 起由 {@code RegionIndexGuardTest.L5_regionOfIsIndexedNotScanned} 的
   * **计数式**守卫承担（定义序的派生必然要扫一遍 {@code regions}，见 {@code MapResolver.regionOfHex}）——本用例改判定义序 /
   * 顶层，**不是**把原断言改成恒真。
   */
  @Test
  void regionOfHexFollowsDefinitionOrderWithTheTopRegionLast() {
    // 钉住夹具前提：regions 的迭代序（插入序）确实是 r2 在前——否则本用例失去判别力
    assertThat(map.regions().keySet()).containsExactly(new RegionId("r2"), new RegionId("r1"));
    List<RegionId> owners = MapResolver.regionOfHex(map, H00);
    assertThat(owners)
        .as("★ 定义序（插入序）：r2 先、r1 后")
        .containsExactly(new RegionId("r2"), new RegionId("r1"));
    assertThat(owners.getLast()).as("末位 = 最顶层区域").isEqualTo(new RegionId("r1"));
    // ★ 判别力：字典序的末位是 r2 ⇒ 两种口径在此分叉。
    assertThat(owners.getLast()).as("字典序会取 r2 ⇒ 若退回字典序这条红").isNotEqualTo(new RegionId("r2"));
    assertThat(MapResolver.regionOfHex(map, NOWHERE)).isEmpty();
  }

  /**
   * ★ V3：三从属、**定义序末位 ≠ 字典序末位**（定义序 {@code m,z,a} ⇒ 顶层 {@code a}；字典序 {@code a,m,z} ⇒ 末位 {@code z}）——
   * 覆盖 &gt;2 从属的顶层判定，与两从属那条各自独立可杀。
   */
  @Test
  void regionOfHexTopIsTheLastByDefinitionNotTheLexicographicMax() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell(0.1));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(new RegionId("m"), Region.of(new RegionId("m"), "M", Set.of(H00), null));
    regions.put(new RegionId("z"), Region.of(new RegionId("z"), "Z", Set.of(H00), null));
    regions.put(new RegionId("a"), Region.of(new RegionId("a"), "A", Set.of(H00), null));
    GameMap three =
        new GameMap(
            hexes,
            TerrainBlocks.uniform(hexes.keySet(), "plain"),
            regions,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));

    List<RegionId> owners = MapResolver.regionOfHex(three, H00);
    assertThat(owners)
        .as("定义序 = 插入序")
        .containsExactly(new RegionId("m"), new RegionId("z"), new RegionId("a"));
    assertThat(owners.getLast()).as("顶层 = 定义序末位 a").isEqualTo(new RegionId("a"));
    assertThat(owners)
        .as("字典序会是 [a,m,z]（末位 z）⇒ 两种口径分叉")
        .isNotEqualTo(List.of(new RegionId("a"), new RegionId("m"), new RegionId("z")));
  }

  /** 认领了的 kind 但名字非法 ⇒ 抛 {@code HexCoord.parse} **自己的** IAE（不包不吞、不改消息）。 */
  @Test
  void malformedHexIndexIsRejected() {
    assertThatThrownBy(() -> resolve("map:m1:hex.abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("非法坐标串: abc");
  }

  /** 源码级护栏：解析器不碰 IO（surefire 的工作目录是模块根 simos-map/）。 */
  @Test
  void resolverDoesNotDoIO() throws IOException {
    String source =
        Files.readString(Path.of("src/main/java/io/mosire/simos/map/resolve/MapResolver.java"));
    assertThat(source)
        .doesNotContain(
            "java.io", "java.nio", "Files.", "Paths.", "ProcessBuilder", "Runtime.getRuntime");
  }

  /** mapId 只回显、不校验（GameMap 没有 id 字段）——换任意串，canonical 里就是那个串。 */
  @Test
  void mapIdIsEchoedIntoCanonicalAddress() {
    for (String mapId : List.of("m1", "whatever-map", "地图甲")) {
      QueryResult result = resolve("map:" + mapId + ":hex.0_0");
      assertThat(result.candidates()).hasSize(1);
      assertThat(result.candidates().getFirst().canonicalAddress())
          .isEqualTo("map:" + mapId + ":hex.0_0");
    }
  }

  /** mapId 含 `:` 时 canonical 必须带引号——§3.4 的加引只能由 Address.canonical() 做，不许手拼。 */
  @Test
  void quotedMapIdIsCanonicalized() {
    QueryResult result = resolve("map:\"Map:1\":hex.0_0");
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().getFirst().canonicalAddress())
        .isEqualTo("map:\"Map:1\":hex.0_0");
  }

  /** state 里没有 map 切片 / 塞了别的 Snapshot ⇒ IAE（装配故障，不是"没有候选"）。 */
  @Test
  void missingMapModuleFailsLoudly() {
    MapResolver resolver = new MapResolver();
    SimulationState empty =
        new SimulationState(new StateMeta(REF, TS), Map.of(), InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () -> resolver.resolve(Address.parse("map:m1:hex.0_0"), new ResolveContext(empty, TS)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有 map 模块切片");
    SimulationState wrongType =
        new SimulationState(
            new StateMeta(REF, TS),
            Map.of("map", new AlienSnapshot(REF, TS)),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    Address.parse("map:m1:hex.0_0"), new ResolveContext(wrongType, TS)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MapSnapshot");
  }

  /** 纯转发型 SPI 要证明参数被原样转交：经注册表拿到的候选与直接调用一致。 */
  @Test
  void registeredThroughRegistry() {
    ResolverRegistry registry = new ResolverRegistry();
    MapResolver resolver = new MapResolver();
    registry.register(resolver);
    assertThat(registry.namespaces()).containsExactly("map");
    QueryResult viaRegistry = registry.resolve(Address.parse("map:m1"), ctx);
    assertThat(viaRegistry).isEqualTo(resolver.resolve(Address.parse("map:m1"), ctx));
    assertThat(viaRegistry.candidates()).hasSize(1);
    assertThat(viaRegistry.candidates().getFirst().id()).isEqualTo(new SubjectId("map", "m1"));
  }

  /** R-13-e：合法地址但没人服务 ⇒ 空候选、不抛。含 brief 的冒号形式（两个 Property 段）。 */
  @Test
  void unservedShapesGiveEmptyCandidates() {
    // 其它 kind：terra.Grass 是合法地址（总纲 §4.4 冻结表），M2 不服务
    assertThat(resolve("map:m1:terra.Grass").candidates()).isEmpty();
    // brief 的冒号形式：hex 与 0_0 判成两个 Property 段 ⇒ 4 段
    assertThat(resolve("map:m1:hex:0_0").candidates()).isEmpty();
    // 段数 > 3（属性访问）
    assertThat(resolve("map:m1:hex.0_0:height").candidates()).isEmpty();
    // Index 元数 ≠ 2
    assertThat(resolve("map:m1:[4]").candidates()).isEmpty();
    assertThat(resolve("map:m1:[0,0,1]").candidates()).isEmpty();
    // 第 3 段裸词 = Property；第 2 段不是根主体 Entity(∅,·)
    assertThat(resolve("map:m1:population").candidates()).isEmpty();
    assertThat(resolve("map:[4,3]").candidates()).isEmpty();
    assertThat(resolve("map:hex.4_3").candidates()).isEmpty();
  }

  /** 冒名顶替的切片：namespace() 对得上（能装进 SimulationState），但不是 MapSnapshot。 */
  private record AlienSnapshot(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }
}
