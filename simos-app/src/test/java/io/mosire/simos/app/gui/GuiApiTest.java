package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GUI {@code /api} 端点的端到端验收（M5 T8 Step 5）：起真 {@link Shell}（{@code guiPort=0}），用 JDK {@link
 * HttpClient} 打真 HTTP。
 *
 * <p>夹具与 {@code QueryServiceTest} 同法：独立 store 种创世 {@code (main,1)} + 含 **map/unit/social** 三切片的 创世
 * checkpoint（state 时间戳 {@code of(7)}）。
 *
 * <p>覆盖：只读端点**逐值**（与 {@link io.mosire.simos.app.query.QueryService} 对拍）、写端点经 {@link CoreSimos} 提交且
 * {@code initiator=="player:gui"}（**独立 store + Timeline 读回**，R4）、404/405、静态首页、{@code close()} 释放端口。
 */
class GuiApiTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H14 = new HexCoord(1, 4);

  private static final UnitId U1 = new UnitId("u-1");
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 一年的天数（批次的年龄用天表示；与 {@code AgeBracket} 的边同口径）。 */
  private static final long YEAR_DAYS = 365L;

  /** H11 上的那座城（只用来拼批次 id 的 {@code urban:<cityId>:} 前缀；本夹具的 social.cities 为空）。 */
  private static final CityId CITY_1_1 = new CityId("c-1_1");

  /** ★ R2：H12 上的那座城（同上：只为拼批次 id；H12 是"第二个有批次的格"，挡"忘了按格筛落点"）。 */
  private static final CityId CITY_1_2 = new CityId("c-1_2");

  // ── R2 的劳动夹具字面量（读口 T4 的期望值全部由这几个数推出，算式写在用例里）────────────────
  //
  // 每人的年龄×性别劳动系数（R1 的表：未成年 0‰ / 青壮 1000‰ / 老年 300‰，默认两性同表）：
  //   H11：农村女 20 岁 200 人 = 200,000；农村男 70 岁 50 人 = 15,000；城镇女 30 岁 700 人 = 700,000
  //        （未成年两批的毛劳动为 0 ⇒ 不发供给，见 EconomySeeder.appendLabor 的口径）
  //   H12：农村女 20 岁 30 人 = 30,000；农村男 70 岁 5 人 = 1,500；城镇女 30 岁 60 人 = 60,000
  private static final long H11_RURAL_FEMALE_ADULT = 200_000L;
  private static final long H11_RURAL_MALE_ELDER = 15_000L;
  private static final long H11_URBAN_FEMALE_ADULT = 700_000L;
  private static final long H12_RURAL_FEMALE_ADULT = 30_000L;
  private static final long H12_RURAL_MALE_ELDER = 1_500L;
  private static final long H12_URBAN_FEMALE_ADULT = 60_000L;

  /** ★ R2 的 actor 身份：两个产业（农业 = 庄园、手工业 = 作坊）+ 两个家户（H11 / H12 各一）。 */
  private static final String FARM_1_1 = "farm@1_1";

  private static final String WORKSHOP_1_1 = "workshop@1_1";
  private static final String HOUSEHOLD_1_1 = "1_1";
  private static final String HOUSEHOLD_1_2 = "1_2";

  /** ★ 创世配额的发放周期（与 {@code EconomySeeder.FIRST_PERIOD} 同值：周期序号从 1 起）。 */
  private static final long FIRST_PERIOD = 1L;

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 读端点（逐值对拍 QueryService）────────────────────────────────────

  @Test
  void stateReportsBranchesAndHeads() throws Exception {
    JsonNode body = getJson("/api/state");

    assertThat(body.get("branches")).as("分支清单与 core.branches() 同源").hasSize(1);
    assertThat(body.get("branches").get(0).asText()).isEqualTo("main");
    assertThat(body.get("heads").get("main").asLong()).isEqualTo(1L);
    assertThat(body.get("meta").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("meta").get("revision").asLong()).isEqualTo(1L);
    assertThat(body.get("meta").get("timestamp").get("tick").asLong()).isEqualTo(T7.tick());
  }

  @Test
  void resolveReturnsCanonicalCandidates() throws Exception {
    String address = "map:Map1:[1,1]";
    JsonNode body = getJson("/api/resolve?address=" + enc(address));
    QueryResult expected = shell.queryService().resolve(address, QueryTarget.head(main()));

    assertThat(body.get("candidates")).hasSize(expected.candidates().size());
    JsonNode first = body.get("candidates").get(0);
    assertThat(first.get("canonicalAddress").asText()).isEqualTo("map:Map1:hex.1_1");
    assertThat(first.get("typeName").asText()).isEqualTo("Hex");
    assertThat(first.get("id").get("namespace").asText()).isEqualTo("map.hex");
  }

  @Test
  void facetsMatchQueryServicePerValue() throws Exception {
    String address = "map:Map1:hex.1_1";
    JsonNode body = getJson("/api/facets?address=" + enc(address));
    List<FacetEntry> expected = shell.queryService().facets(address, QueryTarget.head(main()));

    assertThat(body.get("entries")).hasSize(expected.size());
    JsonNode unit = body.get("entries").get(0);
    assertThat(unit.get("namespace").asText()).isEqualTo("unit");
    assertThat(unit.get("label").asText()).isEqualTo("第一连");
    assertThat(unit.get("typeName").asText()).isEqualTo("Unit");
    assertThat(unit.get("value").asText()).isEqualTo("unit:u-1");
    assertThat(body.get("entries").get(1).get("namespace").asText()).isEqualTo("social");
    // ★ R2（T0）：facet 与人口读口**同源**（SocialData.headlinePopulationAt）⇒ 有批次的格回报**批次求和**，
    //   不再是农村序列的取值。算式：Σ 该格批次 = 100 + 200 + 50 + 300 + 700 = 1,350（见 mixedGroups）。
    assertThat(body.get("entries").get(1).get("value").asLong())
        .as("facet 与 /api/social/population 的 population 是同一个数（R1.5 留下的'两个形状'就此收口）")
        .isEqualTo(1_350L);
  }

  @Test
  void mapHexBuildsTheCanonicalFacetSubject() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=1");

    assertThat(body.get("q").asInt()).isEqualTo(1);
    assertThat(body.get("r").asInt()).isEqualTo(1);
    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("height").asDouble()).isEqualTo(0.5);
    // ★ 非空即证明 hex 端点确实按 canonical 主体查了 facet（非 canonical ⇒ facet 为空列表）
    assertThat(body.get("facets")).hasSize(2);
    assertThat(body.get("facets").get(0).get("value").asText()).isEqualTo("unit:u-1");
    assertThat(body.get("facets").get(1).get("namespace").asText()).isEqualTo("social");
  }

  @Test
  void mapOverviewMatchesTheMapSlice() throws Exception {
    JsonNode body = getJson("/api/map/overview");

    assertThat(body.get("mapId").asText()).isEqualTo("Map1");
    assertThat(body.get("hexCount").asInt()).isEqualTo(4);
    assertThat(body.has("hexes")).as("M9 T13：逐格数组整个移除（地形归块多边形）").isFalse();
    JsonNode blocks = body.get("blocks");
    assertThat(blocks).as("夹具 4 格同地形且连通 ⇒ 恰 1 块").hasSize(1);
    assertThat(blocks.get(0).get("terrain").asText()).isEqualTo("desert");
    assertThat(blocks.get(0).get("hexCount").asInt()).isEqualTo(4);
    assertThat(blocks.get(0).get("boundaries")).isNotEmpty();
  }

  /**
   * M9 T3：overview 停发死重量 {@code height}（前端全仓零读取点）；M9 T13 起逐格 {@code hexes} 也整个停发。 单格 {@code
   * /api/map/hex} 的 {@code height} **必须保留**（{@code panels.js} 在用）。
   */
  @Test
  void mapOverviewOmitsHeightWhileMapHexKeepsIt() throws Exception {
    HttpResponse<String> overview = get("/api/map/overview");
    assertThat(overview.statusCode()).isEqualTo(200);
    assertThat(overview.body()).as("overview 整体不含 height 键").doesNotContain("\"height\"");
    assertThat(overview.body()).as("overview 不再发逐格数组（地形归块）").doesNotContain("\"hexes\"");

    JsonNode single = getJson("/api/map/hex?q=1&r=1");
    assertThat(single.get("height").asDouble()).as("单格端点保留 height").isEqualTo(0.5);
  }

  /** M9 T3：同一坐标连打两次 overview ⇒ 第二次命中读缓存、不读 checkpoint（计数可观测）。 */
  @Test
  void secondOverviewForTheSameTargetDoesNotReadACheckpoint() throws Exception {
    getJson("/api/map/overview");
    long readsAfterFirst = shell.coreSimos().checkpointReadCount();
    long hitsAfterFirst = shell.queryService().stateCacheHits();

    getJson("/api/map/overview");

    assertThat(shell.coreSimos().checkpointReadCount())
        .as("第二次同一坐标的 overview 不再读 checkpoint")
        .isEqualTo(readsAfterFirst);
    assertThat(shell.queryService().stateCacheHits()).isEqualTo(hitsAfterFirst + 1);
  }

  // ── M7 T1 只读扩展（spec §3.1/§3.2/§3.3）──────────────────────────────

  /** 判据②后端面 / R3：{@code /api/timeline} 的节点与库一致、升序、parent 形状正确、未知分支 404。 */
  @Test
  void timelineReportsNodesAndHeadAnd404sForUnknownBranch() throws Exception {
    appendRevision(ref("main", 2), ref("main", 1), SimosTimestamp.of(8L), "unit.RenameUnit");

    JsonNode body = getJson("/api/timeline?branch=main");
    assertThat(body.get("branch").asText()).isEqualTo("main");
    assertThat(body.get("head").asLong()).as("节点数与 head 一致").isEqualTo(2L);
    JsonNode nodes = body.get("nodes");
    assertThat(nodes).hasSize(2);
    JsonNode genesis = nodes.get(0);
    assertThat(genesis.get("revision").asLong()).isEqualTo(1L);
    assertThat(genesis.get("tick").asLong()).isEqualTo(T7.tick());
    assertThat(genesis.get("commandType").asText()).isEqualTo("core.AdvanceTime");
    assertThat(genesis.get("initiator").asText()).isEqualTo("player:local");
    assertThat(genesis.get("parent").isNull()).as("创世节点 parent 为 null").isTrue();
    assertThat(genesis.has("changesetJson")).as("changesetJson 不进节点（体积大且对 UI 无用）").isFalse();
    JsonNode second = nodes.get(1);
    assertThat(second.get("revision").asLong()).isEqualTo(2L);
    assertThat(second.get("commandType").asText()).isEqualTo("unit.RenameUnit");
    assertThat(second.get("parent").get("branch").asText()).isEqualTo("main");
    assertThat(second.get("parent").get("revision").asLong()).isEqualTo(1L);

    assertThat(getJson("/api/timeline").get("branch").asText())
        .as("branch 缺省 main")
        .isEqualTo("main");
    assertThat(get("/api/timeline?branch=ghost").statusCode()).as("分支不存在 ⇒ 404").isEqualTo(404);
  }

  /** 判据③ / R5：hex 详情带 {@code regions}（多从属）与完整 {@code terrainType} 定义。 */
  @Test
  void mapHexCarriesRegionsAndFullTerrainDefinition() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=1");

    JsonNode regions = body.get("regions");
    assertThat(regions).as("regions 是数组（键名就是 regions）").isNotNull();
    assertThat(regions).hasSize(2);
    assertThat(regions.get(0).asText()).as("H11 与 r-1/r-3 都从属 ⇒ 定义序（r-1 先建，故在前）").isEqualTo("r-1");
    assertThat(regions.get(1).asText()).isEqualTo("r-3");
    TerrainType expected = TerrainCatalog.of("desert");
    JsonNode terrain = body.get("terrainType");
    assertThat(terrain).as("完整地形定义在场").isNotNull();
    assertThat(terrain.get("key").asText()).isEqualTo(expected.key());
    assertThat(terrain.get("name").asText()).isEqualTo(expected.name());
    assertThat(terrain.get("color").asText()).isEqualTo(expected.color());
    assertThat(terrain.get("minHeight").asDouble()).isEqualTo(expected.minHeight());
    assertThat(terrain.get("maxHeight").asDouble()).isEqualTo(expected.maxHeight());
    assertThat(terrain.get("food").asInt()).isEqualTo(expected.food());
    assertThat(terrain.get("gold").asInt()).isEqualTo(expected.gold());
    assertThat(terrain.get("stone").asInt()).isEqualTo(expected.stone());
    assertThat(terrain.get("moveCost").asInt()).isEqualTo(expected.moveCost());
    assertThat(terrain.get("description").asText()).isEqualTo(expected.description());
  }

  /** ★ M8 T1：无从属的格 ⇒ {@code regions} 是**空数组**（不是 null、不是缺字段）。 */
  @Test
  void mapHexWithoutRegionGivesEmptyArray() throws Exception {
    JsonNode body = getJson("/api/map/hex?q=1&r=4");

    assertThat(body.get("regions")).as("空从属 ⇒ 空数组").isNotNull();
    assertThat(body.get("regions").isArray()).isTrue();
    assertThat(body.get("regions")).isEmpty();
  }

  /** ★ M8 T1：同一 revision 连续两次调 {@code /api/map/hex} ⇒ {@code regions} 逐字节相同（输出确定）。 */
  @Test
  void mapHexRegionsAreByteIdenticalAcrossTwoCalls() throws Exception {
    String first = get("/api/map/hex?q=1&r=1").body();
    String second = get("/api/map/hex?q=1&r=1").body();

    JsonNode regions = JSON.readTree(first).get("regions");
    assertThat(regions).as("先断言聚合非空，别把空==空当成功").isNotNull();
    assertThat(regions.size()).isEqualTo(2);
    assertThat(second).as("同一 revision 两次响应逐字节相同").isEqualTo(first);
  }

  /**
   * 判据④后端面：overview 的 region 项带 {@code meta}（可空），且 {@code terrainTypes} 已是**完整定义**（M7 T2 的 原子形状切换）。
   */
  @Test
  void mapOverviewRegionItemsCarryMeta() throws Exception {
    JsonNode body = getJson("/api/map/overview");
    JsonNode regions = body.get("regions");
    assertThat(regions).hasSize(3);

    JsonNode first = regionById(regions, "r-1");
    assertThat(first.get("meta").get("color").asText()).isEqualTo("#112233");
    assertThat(first.get("meta").get("tag").asText()).isEqualTo("核心");
    assertThat(first.get("meta").get("description").asText()).isEqualTo("测试区域");
    assertThat(first.get("meta").get("annexedBy").asText()).isEqualTo("u-1");

    JsonNode second = regionById(regions, "r-2");
    assertThat(second.get("meta").get("color").isNull()).isTrue();
    assertThat(second.get("meta").get("tag").isNull()).isTrue();
    assertThat(second.get("meta").get("description").isNull()).isTrue();
    assertThat(second.get("meta").get("annexedBy").isNull()).isTrue();

    JsonNode terrainTypes = body.get("terrainTypes");
    assertThat(terrainTypes).as("T2 起 terrainTypes 是完整定义，不再是 [key…]").hasSize(1);
    JsonNode desert = terrainTypes.get(0);
    assertThat(desert.isObject()).as("每项必须是对象（不是字符串 key）").isTrue();
    TerrainType expected = TerrainCatalog.of("desert");
    assertThat(desert.get("key").asText()).isEqualTo(expected.key());
    assertThat(desert.get("name").asText()).isEqualTo(expected.name());
    assertThat(desert.get("color").asText()).isEqualTo(expected.color());
    assertThat(desert.get("minHeight").asDouble()).isEqualTo(expected.minHeight());
    assertThat(desert.get("maxHeight").asDouble()).isEqualTo(expected.maxHeight());
    assertThat(desert.get("food").asInt()).isEqualTo(expected.food());
    assertThat(desert.get("gold").asInt()).isEqualTo(expected.gold());
    assertThat(desert.get("stone").asInt()).isEqualTo(expected.stone());
    assertThat(desert.get("moveCost").asInt()).isEqualTo(expected.moveCost());
    assertThat(desert.get("description").asText()).isEqualTo(expected.description());
  }

  /** 判据③后端面：区域详情回排序后的 hex 集合，未知区域 404。 */
  @Test
  void regionDetailReturnsSortedHexesAnd404sForUnknownId() throws Exception {
    JsonNode body = getJson("/api/map/region/r-1");

    assertThat(body.get("id").asText()).isEqualTo("r-1");
    assertThat(body.get("name").asText()).isEqualTo("第一区");
    assertThat(body.get("hexCount").asInt()).isEqualTo(2);
    assertThat(body.get("meta").get("tag").asText()).isEqualTo("核心");
    JsonNode hexes = body.get("hexes");
    assertThat(hexes).hasSize(2);
    assertThat(hexes.get(0).get("q").asInt()).isEqualTo(1);
    assertThat(hexes.get(0).get("r").asInt()).isEqualTo(1);
    assertThat(hexes.get(1).get("q").asInt()).isEqualTo(1);
    assertThat(hexes.get(1).get("r").asInt()).isEqualTo(3);

    assertThat(get("/api/map/region/r-ghost").statusCode()).as("区域不存在 ⇒ 404").isEqualTo(404);
  }

  @Test
  void unitsListAndDetailCarryEffectivePosition() throws Exception {
    JsonNode list = getJson("/api/units");
    assertThat(list.get("units")).hasSize(1);
    JsonNode unit = list.get("units").get(0);
    assertThat(unit.get("id").asText()).isEqualTo("u-1");
    assertThat(unit.get("name").asText()).isEqualTo("第一连");
    assertThat(unit.get("position").get("q").asInt()).isEqualTo(1);
    assertThat(unit.get("position").get("r").asInt()).isEqualTo(1);

    JsonNode detail = getJson("/api/unit/u-1");
    assertThat(detail.get("name").asText()).isEqualTo("第一连");
    assertThat(detail.get("member").asInt()).isEqualTo(100);
    // ★ B9：单位自身状态要真发出来（此前两处视图都没发；新建单位缺省 MOVING，见 Unit 的缺省）。
    assertThat(detail.get("status").asText()).isEqualTo("MOVING");
    assertThat(detail.get("position").get("q").asInt()).isEqualTo(1);
  }

  /**
   * M7b T2 判据：有路线 ⇒ {@code movement} 是对象，且 {@code status}/{@code currentHex}/{@code nextHex}/{@code
   * remainingMillis} 由 {@code UnitMoves.evaluate} 现算（同刻预算 0 ⇒ 全段未付）。
   */
  @Test
  void unitDetailExposesMovementObjectWithRouteAndInTransitState() throws Exception {
    String payload =
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}]}";
    String requestBody =
        "{\"type\":\"unit.PlanRoute\",\"payloadJson\":"
            + JSON.writeValueAsString(payload)
            + ",\"branch\":\"main\",\"expectedRevision\":1}";

    HttpResponse<String> response = post("/api/command", requestBody);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");

    JsonNode movement = getJson("/api/unit/u-1").get("movement");
    assertThat(movement.isObject()).as("movement 必须是对象（不再是布尔）").isTrue();
    JsonNode path = movement.get("route").get("path");
    assertThat(path).hasSize(3);
    assertThat(path.get(0).get("q").asInt()).isEqualTo(1);
    assertThat(path.get(0).get("r").asInt()).isEqualTo(1);
    assertThat(path.get(2).get("r").asInt()).isEqualTo(3);
    assertThat(movement.get("route").get("waypoints")).hasSize(3);
    assertThat(movement.get("departedAt").get("tick").asLong())
        .as("领域命令继承父行时刻 ⇒ 出发 tick == 创世 tick")
        .isEqualTo(T7.tick());
    assertThat(movement.get("speedAtDeparture").asInt()).isEqualTo(2);
    assertThat(movement.get("mobilityPerMilleAtDeparture").asInt()).isEqualTo(500);
    assertThat(movement.get("status").asText()).isEqualTo("IN_TRANSIT");
    assertThat(movement.get("currentHex").get("q").asInt()).isEqualTo(1);
    assertThat(movement.get("currentHex").get("r").asInt()).isEqualTo(1);
    assertThat(movement.get("nextHex").get("r").asInt()).isEqualTo(2);
    assertThat(movement.get("remainingMillis").asLong())
        .as("每格成本 = desert.moveCost(3) × 500‰ = 1500 毫 MP；同刻预算 0")
        .isEqualTo(1500L);

    JsonNode listed = getJson("/api/units").get("units").get(0).get("movement");
    assertThat(listed.get("route").get("path")).as("/api/units 与 /api/unit 同形").hasSize(3);
  }

  /** M7b T2 判据：无路线的单位 ⇒ {@code movement} 为 {@code null}（不是 false，也不是空对象）。 */
  @Test
  void unitWithoutRouteHasNullMovement() throws Exception {
    assertThat(getJson("/api/unit/u-1").get("movement").isNull()).isTrue();
    assertThat(getJson("/api/units").get("units").get(0).get("movement").isNull()).isTrue();
  }

  // ── M7b T3 只读寻路端点（/api/map/path）───────────────────────────────

  /** 判据：起点由服务端取（u-1 在 (1,1)）⇒ 到 (1,3) 的 A* 返回**逐格相邻、含首尾**的 3 点路径。 */
  @Test
  void mapPathReturnsStepByStepCorridorPath() throws Exception {
    JsonNode body = getJson("/api/map/path?unit=u-1&q=1&r=3");

    assertThat(body.get("reachable").asBoolean()).isTrue();
    JsonNode path = body.get("path");
    assertThat(path).hasSize(3);
    assertCoord(path.get(0), 1, 1);
    assertCoord(path.get(1), 1, 2);
    assertCoord(path.get(2), 1, 3);
    for (int i = 1; i < path.size(); i++) {
      assertThat(hexDistance(path.get(i - 1), path.get(i))).as("逐格相邻").isEqualTo(1);
    }
  }

  /** 判据：终点不在状态里（图外）⇒ {@code reachable:false,path:[]}（不是 404，也不是 500）。 */
  @Test
  void mapPathIsUnreachableForHexOutsideTheMap() throws Exception {
    JsonNode body = getJson("/api/map/path?unit=u-1&q=9&r=9");

    assertThat(body.get("reachable").asBoolean()).isFalse();
    assertThat(body.get("path")).isEmpty();
  }

  /** 判据：单位不存在 ⇒ 404（与 hex/unit 详情同口径）。 */
  @Test
  void mapPath404sForUnknownUnit() throws Exception {
    HttpResponse<String> response = get("/api/map/path?unit=ghost&q=1&r=3");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("unit not found");
  }

  /** ★ 只读：寻路请求不推进 revision、不落任何 revision 行。 */
  @Test
  void mapPathIsReadOnlyAndDoesNotAdvanceHead() throws Exception {
    long before = getJson("/api/state").get("heads").get("main").asLong();

    getJson("/api/map/path?unit=u-1&q=1&r=2");
    getJson("/api/map/path?unit=u-1&q=9&r=9");

    assertThat(getJson("/api/state").get("heads").get("main").asLong())
        .as("只读端点不得写盘")
        .isEqualTo(before);
    assertThat(getJson("/api/timeline?branch=main").get("nodes")).hasSize(1);
  }

  /** ★ 契约：端点返回的 path 可**原样**当 {@code unit.PlanRoute} 的 waypoints 提交并 committed。 */
  @Test
  void mapPathResultFeedsPlanRoute() throws Exception {
    JsonNode path = getJson("/api/map/path?unit=u-1&q=1&r=3").get("path");
    String payload = "{\"id\":\"u-1\",\"waypoints\":" + JSON.writeValueAsString(path) + "}";
    String requestBody =
        "{\"type\":\"unit.PlanRoute\",\"payloadJson\":"
            + JSON.writeValueAsString(payload)
            + ",\"branch\":\"main\",\"expectedRevision\":1}";

    HttpResponse<String> response = post("/api/command", requestBody);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("result").asText()).isEqualTo("committed");

    JsonNode committedPath = getJson("/api/unit/u-1").get("movement").get("route").get("path");
    assertThat(committedPath).hasSize(3);
    assertCoord(committedPath.get(0), 1, 1);
    assertCoord(committedPath.get(2), 1, 3);
  }

  private static void assertCoord(JsonNode coord, int q, int r) {
    assertThat(coord.get("q").asInt()).isEqualTo(q);
    assertThat(coord.get("r").asInt()).isEqualTo(r);
  }

  private static int hexDistance(JsonNode a, JsonNode b) {
    int dq = a.get("q").asInt() - b.get("q").asInt();
    int dr = a.get("r").asInt() - b.get("r").asInt();
    return (Math.abs(dq) + Math.abs(dq + dr) + Math.abs(dr)) / 2;
  }

  /**
   * ★★ **R2（T0）：{@code population} 的口径 = 批次求和，且来源跟着数字一起发**。
   *
   * <p>★ 算式：该格（H11）有批次 ⇒ {@code population} = {@code Σ 批次 count} = 100 + 200 + 50 + 300 + 700 =
   * **1,350**，{@code source = "batches"}。★ 期望值**不再是**农村序列的取值（11,400）——那是**回退口径**，由 {@link
   * #populationFallsBackToTheLegacySeriesWhenTheHexHasNoBatches()} 单独钉。
   */
  @Test
  void populationComesFromTheBatchesWhenTheHexHasThem() throws Exception {
    JsonNode body = getJson("/api/social/population?q=1&r=1");

    assertThat(body.get("population").asLong()).as("Σ 批次（真值源）").isEqualTo(1_350L);
    assertThat(body.get("source").asText()).as("来源必须读得出来（T0 新增的一维）").isEqualTo("batches");
    assertThat(body.get("at").get("tick").asLong()).isEqualTo(T7.tick());
  }

  /**
   * ★★ **R2（T0）的另一半：该格没有批次 ⇒ 回退旧序列**（H13 有序列、没有批次 —— 随包 bootstrap 世界 {@code worlds/v17levant.json}
   * 与升级前的每条 revision 就是这一形态）。
   *
   * <p>★ 判别力：把口径写成"一律读批次"（或"一律读序列"）⇒ 本条的 11,400 / {@code legacySeries} 与上一条的 1,350 / {@code
   * batches} **总有一条红** —— 两条用例合起来才是完整口径。
   */
  @Test
  void populationFallsBackToTheLegacySeriesWhenTheHexHasNoBatches() throws Exception {
    JsonNode body = getJson("/api/social/population?q=1&r=3");

    assertThat(body.get("population").asLong())
        .as("没有批次 ⇒ 回退农村序列在 head 的取值（不是 0）")
        .isEqualTo(populationSeries().valueAt(T7));
    assertThat(body.get("source").asText()).isEqualTo("legacySeries");
    assertThat(body.get("groups").get("total").asLong()).as("确实没有批次").isZero();
    assertThat(body.get("labor").get("availableMilli").asLong()).as("没有批次 ⇒ 没有可用劳动").isZero();
    assertThat(body.get("labor").get("actors")).as("没有批次 ⇒ 没有主体").isEmpty();
  }

  /**
   * ★★ **R1.5 的核心判据：读口把面打开** —— {@code /api/social/population} 不再"只读农村序列"，同一响应里给出批次的现算读数 （总数 / 城乡 /
   * 年龄档 / 性别）；R2 又在这份体上加了 **{@code source}**（口径来源）与 **{@code labor}**（劳动分配，见 {@link
   * #populationExposesTheLaborAllocationPerActor()}）。
   *
   * <p>★ 期望值全部由 {@link #mixedGroups()} 的算式推出（1,350 = 1,000 城镇 + 350 农村；男 450 / 女 900； 0-14 400 /
   * 15-59 900 / 60+ 50），**逐值**断言，不是"非空""大于 0"这类关系断言。
   *
   * <p>★★ **判别力（夹具刻意混合，R1 的实现者在这里栽过）**：
   *
   * <ul>
   *   <li>"只看农村"（R1.5 之前的实现）⇒ {@code total}/{@code urban}/{@code ageBrackets}/{@code sex} 一起错；
   *   <li>"只看某一格"（装配时漏筛落点）⇒ 本格数字多算（H11/H12 都有批次 ⇒ 本夹具**挡得住**；R1.5 时只有 H11， 这条由 social 侧的用例承担）；
   *   <li>"档位写死在批次上（读 {@code ageAtAnchorDays} 而不走 {@code ageDaysAt(tick)}）"⇒ 本夹具锚点 T0 / head T7 相差
   *       7 天且离边界很远，**本条不会红**——那条判别力由 {@code SocialDataTest} 的跨边界用例承担（如实记，不在这里声称）；
   *   <li>"把 {@code urban}/{@code rural} 的键接反" ⇒ 1,350 / 1,000 / 350 三条一起红。
   * </ul>
   *
   * <p>★ **键序**也钉住（GUI 与 MCP 的响应必须逐字节稳定）：{@code q,r,at,population,source,groups,labor,crisis} +
   * {@code total,urban,rural,ageBrackets,sex,physiologicalStress} + 档名/性别按词表序。 ★ R4 追加两块：{@code
   * groups.physiologicalStress}（生理压力）与顶层 {@code crisis}（危机**类别**，见 {@code CrisisMonitor}）。
   */
  @Test
  void populationExposesTheBatchBreakdownsBesideTheLegacySeriesValue() throws Exception {
    JsonNode body = getJson("/api/social/population?q=1&r=1");

    assertThat(fieldNames(body))
        .as("顶层键序（population 之后紧跟它的来源 source，再是批次块、R2 的劳动块、R4 的危机块）")
        .containsExactly("q", "r", "at", "population", "source", "groups", "labor", "crisis");
    assertThat(body.get("population").asLong()).as("R2（T0）：口径 = 批次求和（真值源）").isEqualTo(1_350L);
    assertThat(body.get("source").asText()).as("来源 = 批次").isEqualTo("batches");

    JsonNode groups = body.get("groups");
    assertThat(fieldNames(groups))
        .as("块内键序（三个派生量：城乡 / 年龄档 / 性别 / R4 的生理压力）")
        .containsExactly("total", "urban", "rural", "ageBrackets", "sex", "physiologicalStress");
    assertThat(groups.get("total").asLong()).as("Σ 批次 = 1,350（这就是与经济侧对拍的那一侧）").isEqualTo(1_350L);
    assertThat(groups.get("urban").asLong()).as("城镇：男 10 岁 300 + 女 30 岁 700").isEqualTo(1_000L);
    assertThat(groups.get("rural").asLong())
        .as("农村：男 5 岁 100 + 女 20 岁 200 + 男 70 岁 50")
        .isEqualTo(350L);
    assertThat(groups.get("urban").asLong() + groups.get("rural").asLong())
        .as("城乡两项必须相加等于 total（一个人不丢、也不重复）")
        .isEqualTo(groups.get("total").asLong());

    JsonNode ageBrackets = groups.get("ageBrackets");
    assertThat(fieldNames(ageBrackets))
        .as("档名与 D4 一致、按词表序")
        .containsExactly("0-14", "15-59", "60+");
    assertThat(ageBrackets.get("0-14").asLong()).as("农村男 5 岁 100 + 城镇男 10 岁 300").isEqualTo(400L);
    assertThat(ageBrackets.get("15-59").asLong()).as("农村女 20 岁 200 + 城镇女 30 岁 700").isEqualTo(900L);
    assertThat(ageBrackets.get("60+").asLong()).as("农村男 70 岁 50").isEqualTo(50L);

    JsonNode sex = groups.get("sex");
    assertThat(fieldNames(sex)).as("性别按词表序（MALE → FEMALE）").containsExactly("MALE", "FEMALE");
    assertThat(sex.get("MALE").asLong()).as("男 100 + 50 + 300").isEqualTo(450L);
    assertThat(sex.get("FEMALE").asLong()).as("女 200 + 700").isEqualTo(900L);
    assertThat(sex.get("MALE").asLong() + sex.get("FEMALE").asLong())
        .as("同一批人的三种切法：性别之和也必须等于 total")
        .isEqualTo(groups.get("total").asLong());
  }

  /**
   * ★★ **R2（T4）：劳动分配维读得出来** —— 按格：该格可用劳动 / 各主体占用劳动 / 占用率。
   *
   * <p>★★ **期望值全部由 {@link #laborSupply()} 与 {@link #laborAllocations()} 的字面量推出**（算式写在那两个夹具的注释里）：
   *
   * <pre>
   * H11：可用 = 200,000（农村女 20 岁）+ 15,000（农村男 70 岁）+ 700,000（城镇女 30 岁） = 915,000
   *      已分配 = 120,000 + 60,000 + 20,000 + 15,000 + 500,000                          = 715,000
   *      占用率 = 715,000 × 1000 ÷ 915,000 = 781（向下取整；留 200,000 未分配 ⇒ 不是 1000‰）
   *      各主体（按 kind,id 序）：ESTATE|farm@1_1 = 135,000、HOUSEHOLD|1_1 = 20,000、WORKSHOP|workshop@1_1 = 560,000
   * H12：可用 = 30,000 + 1,500 + 60,000 = 91,500；已分配 = 80,000 ⇒ 占用率 874（家户 1_2 一个主体）
   * </pre>
   *
   * <p>★★ **判别力（逐条对着一种坏实现；夹具刻意混合是前提）**：
   *
   * <ul>
   *   <li>"只看第一个产业 / 只认农业" ⇒ 715,000 会变 135,000、主体表只剩一项 ⇒ 红；
   *   <li>"每批次只取第一条配额"（`break` 提前）⇒ 635,000 ⇒ 红；
   *   <li>"只认产业、漏掉家户" ⇒ 695,000 ⇒ 红；
   *   <li>"忘了按格筛落点"（把所有配额加一起）⇒ H11 读到 795,000、H12 读到 715,000 ⇒ 两条都红；
   *   <li>"占用率写死 1000‰ / 用别的分母" ⇒ 781 与 874 两个非平凡值一起挡。
   * </ul>
   */
  @Test
  void populationExposesTheLaborAllocationPerActor() throws Exception {
    JsonNode labor = getJson("/api/social/population?q=1&r=1").get("labor");

    assertThat(fieldNames(labor))
        .as("劳动块的键序（可用 / 已分配 / 占用率 / 主体表）")
        .containsExactly("availableMilli", "allocatedMilli", "utilizationPerMille", "actors");
    assertThat(labor.get("availableMilli").asLong()).as("Σ 该格各批次的可用劳动").isEqualTo(915_000L);
    assertThat(labor.get("allocatedMilli").asLong()).as("Σ 该格各批次的全部配额").isEqualTo(715_000L);
    assertThat(labor.get("utilizationPerMille").asLong())
        .as("715,000 × 1000 ÷ 915,000 = 781（向下取整；不是 1000‰ ⇒ 挡住'写死满分'的实现）")
        .isEqualTo(781L);

    JsonNode actors = labor.get("actors");
    assertThat(actors).as("三个主体：农业产业 / 家户 / 手工业产业").hasSize(3);
    assertThat(fieldNames(actors.get(0))).containsExactly("kind", "id", "laborMilli");
    // ★ 同一批次（农村女 20 岁）的三条配额落在三个主体上：农业 120,000 + 手工业 60,000 + 家户 20,000。
    assertThat(actors.get(0).get("kind").asText()).isEqualTo("ESTATE");
    assertThat(actors.get(0).get("id").asText()).isEqualTo(FARM_1_1);
    assertThat(actors.get(0).get("laborMilli").asLong())
        .as("120,000（农村女）+ 15,000（农村男 70 岁）")
        .isEqualTo(135_000L);
    assertThat(actors.get(1).get("kind").asText())
        .as("家户也进表（漏掉它 ⇒ 已分配少 20,000）")
        .isEqualTo("HOUSEHOLD");
    assertThat(actors.get(1).get("id").asText()).isEqualTo(HOUSEHOLD_1_1);
    assertThat(actors.get(1).get("laborMilli").asLong()).isEqualTo(20_000L);
    assertThat(actors.get(2).get("kind").asText()).isEqualTo("WORKSHOP");
    assertThat(actors.get(2).get("id").asText()).isEqualTo(WORKSHOP_1_1);
    assertThat(actors.get(2).get("laborMilli").asLong())
        .as("60,000（农村女）+ 500,000（城镇女）")
        .isEqualTo(560_000L);

    // ★★ **按格筛落点**：H12 的配额（80,000）不进 H11 的数，H11 的也不进 H12 的数。
    JsonNode other = getJson("/api/social/population?q=1&r=2").get("labor");
    assertThat(other.get("availableMilli").asLong()).isEqualTo(91_500L);
    assertThat(other.get("allocatedMilli").asLong()).as("只有 H12 的两条配额").isEqualTo(80_000L);
    assertThat(other.get("utilizationPerMille").asLong())
        .as("80,000 × 1000 ÷ 91,500 = 874")
        .isEqualTo(874L);
    assertThat(other.get("actors")).hasSize(1);
    assertThat(other.get("actors").get(0).get("kind").asText()).isEqualTo("HOUSEHOLD");
    assertThat(other.get("actors").get(0).get("id").asText()).isEqualTo(HOUSEHOLD_1_2);
    assertThat(other.get("actors").get(0).get("laborMilli").asLong()).isEqualTo(80_000L);
  }

  /**
   * ★ **读口没有变宽**：没有人口序列的格依旧 404（R1.5 加的三个派生量只跟着**已有**的 {@code social:<q>_<r>} 资源走，不新开面）。H14
   * 在地图上但不属于任何区域、也**没有人口序列**。
   *
   * <p>★ R2 的劳动块同理：它是**人口资源的一维**（"这批人的劳动被谁占了多少"），判据仍是 R1.5 那一条（该格有序列 + 该格人口可见）， 没有新开权限面（T4 的原文）。
   */
  @Test
  void populationStill404sForAHexWithoutASeries() throws Exception {
    assertThat(get("/api/social/population?q=1&r=4").statusCode())
        .as("没有人口序列的格 ⇒ 404（不是 200 + 空 groups）")
        .isEqualTo(404);
  }

  /** {@link JsonNode} 的字段名（保序）。 */
  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /**
   * ★★ R2a 一格一产业 + **R2 的劳动配额**（断言值都是这里写下的字面量）。
   *
   * <p>★★ **R2 的刻意混合**（本夹具承担 T4 读口的判别力，逐条写在 {@link #laborAllocations()} 与 {@link #laborSupply()}
   * 的注释里）：H11 的**同一个批次**同时挂**三条**配额（农业产业 + 手工业产业 + 家户）， 且 H12 另有配额 ——
   * 于是"只看第一个产业""只看第一条配额""忘了按格筛落点"三种坏实现都会**逐值**红。
   *
   * <p>★ **两个产业都在 (1,1)**：{@code workshop@1_1} 只有槽位、没有阶层行（本轮手工业不产出，夹具只借它的**身份**当 第二个"各产业占用劳动"的落点）。★
   * 产业 id 排序 = {@code craft...} 之外的字典序 ⇒ 取名 {@code workshop@1_1} 让 {@code farm@1_1} 仍排在 {@code
   * industries[0]}（既有断言一字不动）。
   */
  private static EconomyData economyData() {
    IndustryId farm = new IndustryId("farm@1_1");
    IndustryId workshop = new IndustryId("workshop@1_1");
    SocialClassId peasant = new SocialClassId("poor_peasant");
    Industry industry =
        new Industry(
            farm,
            "农业",
            new RegimeId("feudal"),
            120L,
            33L,
            // ★ R3（V7）：产能锚与劳动那一路（读口要发它们 ⇒ 夹具必须给非平凡的值，见 industryRecipeIsVisible）。
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            143L,
            Map.of(new CommodityId("grain"), 7L, new CommodityId("fiber"), 3L),
            Map.of(), // ★ 同本夹具的字面量：不配投入
            List.of(new ClassSlot(peasant, "贫农", 950)),
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(new CommodityId("grain"), 40L));
    ClassRow row =
        new ClassRow(
            new ClassKey(farm, peasant),
            100L,
            58_000L,
            950,
            Map.of(AssetKind.LAND, 1_000_000L),
            Map.of(new CommodityId("grain"), 498_000L),
            12L,
            List.of(),
            Map.of(new CommodityId("grain"), 8_300L),
            Map.of());
    return new EconomyData(
        java.util.Optional.of(
            new EconomyMeta(
                "Map1",
                7L,
                java.util.OptionalLong.empty(),
                "aggregate-v1",
                java.util.Optional.empty())),
        Map.of(farm, industry, workshop, workshopIndustry(workshop)),
        Map.of(new ClassKey(farm, peasant), row),
        Map.of(),
        Map.of(),
        laborSupply(),
        laborAllocations());
  }

  /** 第二个产业（手工业 = 作坊）：只借身份（无阶层行 ⇒ 无人口/劳动，读口多一条空产业）。 */
  private static Industry workshopIndustry(IndustryId id) {
    return new Industry(
        id,
        "手工业",
        new RegimeId("handicraft"),
        120L,
        0L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1_000L,
        Map.of(),
        Map.of(),
        List.of(new ClassSlot(new SocialClassId("poor_peasant"), "贫农", 950)),
        new AllocationRule.Split(400, 600),
        0L,
        Map.of());
  }

  /** ★ R2a 的 G1 读口：{@code GET /api/economy/hex} 逐值给读数，与 MCP 的 {@code simos.economy.hex} 共用一份视图。 */
  @Test
  void economyHexReportsPerHexReadingsAndRejectsAs() throws Exception {
    JsonNode body = getJson("/api/economy/hex?q=1&r=1");

    assertThat(body.get("activated").asBoolean()).isTrue();
    assertThat(body.get("population").asLong()).isEqualTo(100L);
    assertThat(body.get("laborMilli").asLong()).isEqualTo(58_000L);
    assertThat(body.get("landMilliMu").asLong()).isEqualTo(1_000_000L);
    assertThat(body.get("goods").get("grain").asLong()).isEqualTo(498_000L);
    assertThat(body.get("money").asLong()).isEqualTo(12L);
    assertThat(body.get("debtCount").asLong()).isZero();
    assertThat(body.get("debtPrincipal").asLong()).isZero();
    JsonNode industry = body.get("industries").get(0);
    assertThat(industry.get("id").asText()).isEqualTo("farm@1_1");
    assertThat(industry.get("regime").asText()).isEqualTo("feudal");
    assertThat(industry.get("cycleDays").asLong()).isEqualTo(120L);
    assertThat(industry.get("progressDays").asLong()).as("周期进度").isEqualTo(33L);
    assertThat(industry.get("allocation").get("meansWeightPerMille").asInt()).isEqualTo(700);
    // ★★ R3（T6）：**V7 配方读得出来**（"每单位什么"是数据 ⇒ 报表里也要看得见）：
    //   {capacityPerUnit, inputPerUnit, laborPerUnit, outputPerUnit} + 本周期实际扣到的投入。
    assertThat(industry.get("capacityPerUnit").get("LAND").asLong())
        .as("单位规模 = 1 亩")
        .isEqualTo(1_000L);
    assertThat(industry.get("laborPerUnit").asLong()).as("每亩需劳动").isEqualTo(143L);
    assertThat(industry.get("outputPerUnit").get("grain").asLong()).isEqualTo(7L);
    assertThat(industry.get("outputPerUnit").get("fiber").asLong())
        .as("★ 读口发得出**不止粮**的商品（多商品产出的读侧判据）")
        .isEqualTo(3L);
    assertThat(industry.get("inputPerUnit")).as("本夹具不配投入").isEmpty();
    assertThat(industry.get("cycleInputUsedMilli").get("grain").asLong())
        .as("★ 本周期实际扣到的投入（按商品）")
        .isEqualTo(40L);
    JsonNode row = industry.get("classes").get(0);
    assertThat(row.get("slot").asText()).isEqualTo("poor_peasant");
    assertThat(row.get("naturalNeeds").get("grain").asLong()).as("日耗").isEqualTo(8_300L);

    // 该格没有产业：200 + 空 industries（不是 404 —— "没数据"与"不存在"是两件事）。
    // ★ activated 是**切片级**标志（经济是否激活），不是"这一格有没有数据"。
    JsonNode empty = getJson("/api/economy/hex?q=1&r=2");
    assertThat(empty.get("activated").asBoolean()).isTrue();
    assertThat(empty.get("industries")).isEmpty();
    assertThat(empty.get("population").asLong()).isZero();

    // 不在图上的格 ⇒ 404（与 /api/map/hex 同形）。
    assertThat(get("/api/economy/hex?q=9&r=9").statusCode()).isEqualTo(404);
    // 未接 redaction ⇒ 带 as= 显式拒绝（fail-closed）。
    assertThat(get("/api/economy/hex?q=1&r=1&as=dm-1").statusCode()).isEqualTo(400);
  }

  // ── 写端点（R4：initiator 由独立 store + Timeline 读回）────────────────

  @Test
  void writeCommitsThroughCoreAndStampsPlayerGuiInitiator() throws Exception {
    String payload = "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}";
    String requestBody =
        "{\"type\":\"unit.RenameUnit\",\"payloadJson\":"
            + JSON.writeValueAsString(payload)
            + ",\"branch\":\"main\",\"expectedRevision\":1}";

    HttpResponse<String> response = post("/api/command", requestBody);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode result = JSON.readTree(response.body());
    assertThat(result.get("result").asText()).isEqualTo("committed");
    assertThat(result.get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(result.get("ref").get("revision").asLong()).isEqualTo(2L);

    try (SqliteStore store = SqliteStore.open(dbFile())) {
      RevisionRow row = new Timeline(store, CHECKPOINT_INTERVAL).row(ref("main", 2)).orElseThrow();
      assertThat(row.initiator()).as("GUI 写命令的发起者恰是 player:gui（R4）").isEqualTo("player:gui");
      assertThat(row.commandType()).isEqualTo("unit.RenameUnit");
      assertThat(row.commandId())
          .as("C22：单命令链 commandId = correlationId")
          .isEqualTo(row.correlationId());
      assertThat(row.correlationId()).isNotBlank();
    }
    assertThat(getJson("/api/unit/u-1").get("name").asText()).isEqualTo("改名后的第一连");
  }

  // ── 404 / 405 / 审批接缝 / 静态 / 生命周期 ─────────────────────────────

  @Test
  void unknownApiPathIs404AndWrongMethodIs405() throws Exception {
    assertThat(get("/api/nope").statusCode()).isEqualTo(404);

    HttpResponse<String> wrongMethod = get("/api/command");
    assertThat(wrongMethod.statusCode()).isEqualTo(405);
    assertThat(wrongMethod.headers().firstValue("Allow").orElse("")).isEqualTo("POST");

    assertThat(post("/api/state", "{}").statusCode()).isEqualTo(405);

    HttpResponse<String> pathWrongMethod = post("/api/map/path", "{}");
    assertThat(pathWrongMethod.statusCode()).as("寻路是 GET 端点").isEqualTo(405);
    assertThat(pathWrongMethod.headers().firstValue("Allow").orElse("")).isEqualTo("GET");
  }

  @Test
  void approvalsProxyForwardsToTheLiveAgentLibEndpoint() throws Exception {
    // Shell 现在恒装配审批端点（T6）⇒ 5711 的 /api/approvals 是真代理，状态码/体透传。
    JsonNode list = getJson("/api/approvals");
    assertThat(list.get("pending")).as("无写调用 ⇒ 待裁决表为空").isEmpty();

    assertThat(post("/api/approvals/ap-nope", "{\"decision\":\"deny\"}").statusCode())
        .as("未知 id ⇒ AgentLib 端点的 404 原样透传")
        .isEqualTo(404);

    HttpResponse<String> wrongMethod = get("/api/approvals/ap-nope");
    assertThat(wrongMethod.statusCode()).as("错误方法 ⇒ 405（端点判定）").isEqualTo(405);
    assertThat(wrongMethod.headers().firstValue("Allow").orElse("")).isEqualTo("POST");
  }

  @Test
  void indexHtmlIsServedFromClasspath() throws Exception {
    HttpResponse<String> root = get("/");
    assertThat(root.statusCode()).isEqualTo(200);
    assertThat(root.headers().firstValue("Content-Type").orElse("")).contains("text/html");
    assertThat(root.body()).contains("Simos Shell");

    assertThat(get("/nope").statusCode()).as("未知静态路径 ⇒ 404").isEqualTo(404);
  }

  @Test
  void closeReleasesTheGuiPort() throws Exception {
    int bound = shell.boundGuiPort();
    shell.close();

    try (ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(true);
      probe.bind(new InetSocketAddress("127.0.0.1", bound));
      assertThat(probe.isBound()).as("close() 后端口已释放，可重新绑定").isTrue();
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private JsonNode getJson(String path) throws Exception {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit())));
    // ★★ R1.5：H11 挂**刻意混合**的批次（男女 × 城乡 × 三档各非零，见 mixedGroups）——读口那条用例靠它避免假绿。
    //   ★ R2：**H12 也挂一批**（同样混合，见 {@link #mixedGroups()}）⇒ 两个格有批次，R1.5 那条"每个有批次的格都混合"照旧成立，
    //     而 T4 的劳动读口多了一条"按格筛落点"的判别力。
    //   ★ H13 **只有序列、没有批次**：R2（T0）的回退口径（legacySeries）就靠它钉住。
    SocialData social =
        new SocialData(
            new LinkedHashMap<>(
                Map.of(H11, populationSeries(), H12, populationSeries(), H13, populationSeries())),
            Map.of(),
            mixedGroups());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty()),
                // ★ R2a：经济切片带一份可断言的读数（一格一产业一阶层行）。
                "economy", new EconomySnapshot(ref("main", 1), T7, economyData())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec())));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  /**
   * ★★ **H11 的混合批次夹具**（R1.5 的读口用例用；**每一个有批次的格**都要"男女 × 城乡 × 三档"齐全，否则 "只看农村 / 只看某一档 / 档位写死"的实现照样绿 =
   * 假绿，R1 的实现者在这里栽过一次）：
   *
   * <pre>
   * 农村 男 5 岁 100（0-14） | 农村 女 20 岁 200（15-59） | 农村 男 70 岁 50（60+）
   * 城镇 男 10 岁 300（0-14）| 城镇 女 30 岁 700（15-59）
   * ⇒ 城镇 1,000 / 农村 350 / 合计 1,350；男 450 / 女 900；0-14 400 / 15-59 900 / 60+ 50
   * </pre>
   *
   * <p>★ 锚点 = {@code T0}（创世态的时间戳是 {@code T7}）⇒ 读口在 head 上算出来的年龄是"锚点年龄 + 7 天"， 三个档都离边界很远（7
   * 天不会把任何人挪档）——"必须现算"由 {@code SocialDataTest} 那条跨边界的用例钉， 这里钉的是**装配**（哪个派生量发到哪个键上）。
   *
   * <p>★ 城镇批次的 id 前缀是 {@code urban:c-1_1:}（真正的命名规则；本夹具的 {@code SocialData.cities} 为空， 因为"城乡"只由 id
   * 前缀判定，与"那座城登不登记"无关——与 R1 的 {@code populationAt} 用例同口径）。
   */
  private static Map<PeopleLotId, PopulationGroup> mixedGroups() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    addLot(groups, H11, PopulationLots.rural(H11, Sex.MALE, "0"), Sex.MALE, 100L, 5L * YEAR_DAYS);
    addLot(
        groups, H11, PopulationLots.rural(H11, Sex.FEMALE, "1"), Sex.FEMALE, 200L, 20L * YEAR_DAYS);
    addLot(groups, H11, PopulationLots.rural(H11, Sex.MALE, "2"), Sex.MALE, 50L, 70L * YEAR_DAYS);
    addLot(
        groups,
        H11,
        PopulationLots.urban(CITY_1_1, Sex.MALE, "0"),
        Sex.MALE,
        300L,
        10L * YEAR_DAYS);
    addLot(
        groups,
        H11,
        PopulationLots.urban(CITY_1_1, Sex.FEMALE, "1"),
        Sex.FEMALE,
        700L,
        30L * YEAR_DAYS);
    // ★★ R2：**第二个有批次的格**（H12）—— 同样刻意混合（男女 × 城乡 × 三档），用途见 {@link #laborAllocations()}：
    //   它让"忘了按格筛落点"的实现**逐值**红（只加 H11 的话，全世界的配额恰好都在 H11，"不筛"与"筛了"同值 ⇒ 假绿）。
    addLot(groups, H12, PopulationLots.rural(H12, Sex.MALE, "0"), Sex.MALE, 10L, 5L * YEAR_DAYS);
    addLot(
        groups, H12, PopulationLots.rural(H12, Sex.FEMALE, "1"), Sex.FEMALE, 30L, 20L * YEAR_DAYS);
    addLot(groups, H12, PopulationLots.rural(H12, Sex.MALE, "2"), Sex.MALE, 5L, 70L * YEAR_DAYS);
    addLot(
        groups, H12, PopulationLots.urban(CITY_1_2, Sex.MALE, "0"), Sex.MALE, 20L, 10L * YEAR_DAYS);
    addLot(
        groups,
        H12,
        PopulationLots.urban(CITY_1_2, Sex.FEMALE, "1"),
        Sex.FEMALE,
        60L,
        30L * YEAR_DAYS);
    return groups;
  }

  private static void addLot(
      Map<PeopleLotId, PopulationGroup> groups,
      HexCoord hex,
      PeopleLotId id,
      Sex sex,
      long count,
      long ageAtAnchorDays) {
    groups.put(id, new PopulationGroup(id, hex, sex, count, ageAtAnchorDays, T0.tick()));
  }

  /**
   * ★★ **R2 的劳动供给**（T4 读口的分母）：逐批次一条，毛额 = 人数 × 年龄性别系数（H11/H12 各三条，算式见常量区的清单）。
   *
   * <p>★ 未成年批次**不发**（毛劳动 0）：与 {@code EconomySeeder.appendLabor} 的口径同一处理由（0 的配额只是噪声）。
   */
  private static Map<PeopleLotId, LaborSupply> laborSupply() {
    Map<PeopleLotId, LaborSupply> supply = new LinkedHashMap<>();
    addSupply(supply, PopulationLots.rural(H11, Sex.FEMALE, "1"), H11_RURAL_FEMALE_ADULT);
    addSupply(supply, PopulationLots.rural(H11, Sex.MALE, "2"), H11_RURAL_MALE_ELDER);
    addSupply(supply, PopulationLots.urban(CITY_1_1, Sex.FEMALE, "1"), H11_URBAN_FEMALE_ADULT);
    addSupply(supply, PopulationLots.rural(H12, Sex.FEMALE, "1"), H12_RURAL_FEMALE_ADULT);
    addSupply(supply, PopulationLots.rural(H12, Sex.MALE, "2"), H12_RURAL_MALE_ELDER);
    addSupply(supply, PopulationLots.urban(CITY_1_2, Sex.FEMALE, "1"), H12_URBAN_FEMALE_ADULT);
    return supply;
  }

  private static void addSupply(
      Map<PeopleLotId, LaborSupply> supply, PeopleLotId group, long grossLaborMilli) {
    supply.put(group, new LaborSupply(group, FIRST_PERIOD, grossLaborMilli, 0L, 0L));
  }

  /**
   * ★★ **R2 的劳动配额**（T4 读口的分子）：**刻意混合**的夹具，逐条说明它挡的是哪一种坏实现。
   *
   * <pre>
   * H11（可用 915,000 = 200,000 + 15,000 + 700,000）：
   *   农村女 20 岁 200 人 → 农业 120,000 + 手工业 60,000 + 家户 20,000   ← **同一批次三条配额、两个产业**
   *   农村男 70 岁  50 人 → 农业  15,000
   *   城镇女 30 岁 700 人 → 手工业 500,000                                ← 留 200,000 未分配（占用率因此不是 1000‰）
   *   ⇒ 已分配 715,000 / 可用 915,000 / 占用率 715,000×1000÷915,000 = 781（向下取整）
   *   ⇒ 各主体：{@code ESTATE|farm@1_1 135,000}、{@code HOUSEHOLD|1_1 20,000}、{@code WORKSHOP|workshop@1_1 560,000}
   * H12（可用 91,500）：农村女 20 岁 30,000 + 城镇女 30 岁 60,000 → 家户 1_2 = 80,000 ⇒ 占用率 874
   * </pre>
   *
   * <p>★★ **判别力（逐条对应一种坏实现）**：
   *
   * <ul>
   *   <li>"**只看第一个产业**"（每条配额只算头一个 actor / 只认农业）⇒ 715,000 会变成 135,000 ⇒ 红；
   *   <li>"**每批次只取第一条配额**"（`break` 写早了）⇒ 635,000 ⇒ 红；
   *   <li>"**只认产业、漏掉家户**"⇒ 695,000 ⇒ 红；
   *   <li>"**忘了按格筛落点**"（把全世界的配额加一起）⇒ H11 读到 795,000、H12 读到 715,000 ⇒ 两条都红；
   *   <li>"**占用率写成固定 1000‰ / 或拿 available 当分母以外的数**"⇒ 781 与 874 两个非平凡值一起挡。
   * </ul>
   */
  private static Map<LaborAllocationId, LaborAllocation> laborAllocations() {
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    PeopleLotId ruralAdult = PopulationLots.rural(H11, Sex.FEMALE, "1");
    PeopleLotId ruralElder = PopulationLots.rural(H11, Sex.MALE, "2");
    PeopleLotId urbanAdult = PopulationLots.urban(CITY_1_1, Sex.FEMALE, "1");
    // ★ 同一批次（农村女 20 岁）**三条配额**：两个产业 + 一个家户。
    addAllocation(allocations, ruralAdult, FARM_1_1, ActorKind.ESTATE, "farm", 120_000L);
    addAllocation(allocations, ruralAdult, WORKSHOP_1_1, ActorKind.WORKSHOP, "craft", 60_000L);
    addAllocation(allocations, ruralAdult, HOUSEHOLD_1_1, ActorKind.HOUSEHOLD, "weaving", 20_000L);
    addAllocation(allocations, ruralElder, FARM_1_1, ActorKind.ESTATE, "farm", 15_000L);
    addAllocation(allocations, urbanAdult, WORKSHOP_1_1, ActorKind.WORKSHOP, "craft", 500_000L);
    // H12：第二个有批次的格（挡"忘了按格筛落点"）——它的劳动全部给本格的家户（本格没有产业）。
    addAllocation(
        allocations,
        PopulationLots.rural(H12, Sex.FEMALE, "1"),
        HOUSEHOLD_1_2,
        ActorKind.HOUSEHOLD,
        "weaving",
        20_000L);
    addAllocation(
        allocations,
        PopulationLots.urban(CITY_1_2, Sex.FEMALE, "1"),
        HOUSEHOLD_1_2,
        ActorKind.HOUSEHOLD,
        "weaving",
        60_000L);
    return allocations;
  }

  /** 一条配额：id 由 {@code (actor, 批次)} 确定性拼出（与 {@code EconomySeeder.allocationId} 同形）。 */
  private static void addAllocation(
      Map<LaborAllocationId, LaborAllocation> allocations,
      PeopleLotId group,
      String actorId,
      ActorKind kind,
      String activity,
      long laborMilli) {
    LaborAllocationId id = new LaborAllocationId("alloc-" + actorId + "-" + group.value());
    allocations.put(
        id,
        new LaborAllocation(
            id, group, new ActorRef(kind, actorId), activity, laborMilli, FIRST_PERIOD));
  }

  /** 与 {@code QueryServiceTest.populationSeries()} 同款：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    hexes.put(H14, new HexCell(0.5)); // 不属任何区域 ⇒ regions 空数组
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    // M7 T1：两个区域——r-1 带全量 meta（覆盖 H11/H13，非相邻 ⇒ 两条环），r-2 空 meta（覆盖 H12）
    Region r1 =
        Region.of(
            new RegionId("r-1"),
            "第一区",
            Set.of(H11, H13),
            new RegionMeta("#112233", "核心", "测试区域", "u-1"));
    Region r2 = Region.of(new RegionId("r-2"), "第二区", Set.of(H12), RegionMeta.empty());
    // M8 T1：r-3 与 r-1 **重叠**于 H11（多从属）——/api/map/hex 的 regions 必须两条都在；★ V3 起按定义序（本夹具 r-1
    // 先建、两种口径同序）。
    Region r3 = Region.of(new RegionId("r-3"), "第三区", Set.of(H11), RegionMeta.empty());
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(r1.id(), r1);
    regions.put(r2.id(), r2);
    regions.put(r3.id(), r3);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 从第二个连接追加一行 revision（M7 T1 的时间轴夹具；空变更集 ⇒ 不需要新 checkpoint）。 */
  private void appendRevision(
      StateRef target, StateRef parent, SimosTimestamp timestamp, String commandType) {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      new Timeline(store, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  target.branch(),
                  target.revision(),
                  Optional.of(parent),
                  timestamp,
                  "cmd-" + target.revision().value(),
                  "corr-" + target.revision().value(),
                  "player:local",
                  commandType,
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
  }

  private static JsonNode regionById(JsonNode regions, String id) {
    for (JsonNode region : regions) {
      if (id.equals(region.get("id").asText())) {
        return region;
      }
    }
    throw new AssertionError("overview 里没有区域 " + id);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
