package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.write.CalendarConfigureTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.calendar.info}（C5b 读工具，设计稿 §七/§八）的逐值验收。
 *
 * <p>形状（以 {@link CalendarInfoTool} 的实现为准）：根 = {@code tick, config, sources, date, solarTerm,
 * notes}；仅当 {@code q}/{@code r} <b>成对</b>给出时才追加 {@code season}。{@code q}/{@code r} 只给一个 ⇒ {@code
 * BAD_REQUEST}（{@link CalendarInfoTool#execute} 的成对判据，本类显式钉住）。
 *
 * <p>夹具与 {@code ArmyCombatReadToolsTest} 同法：真 {@link CoreSimos} + {@code bootstrapGenesis}（空模块的创世
 * 状态，时间戳 {@code of(7)}）+ 临时目录；直接调工具（不起真 HTTP）。历法/气候服务就是启动期同款的 {@link CalendarService#load}，GM 写口用
 * {@link CalendarConfigureTool} 真写一次 {@code store_meta.calendar}。
 *
 * <p>★ 默认锚点下 {@code tick 120} = 儒略 {@code 1445-05-01}（JDN 2248965），当日节气 = 立夏；分带未配置时 {@code
 * zoneSource=fallback}（全球北半球四季）。这些常量来自设计稿 §九与 {@code CalendarDefaults} 的唯一口径。
 */
class CalendarInfoToolTest {

  /** 默认锚点下 tick 120 = 儒略 1445-05-01；也是全部季节/节气断言的目标日。 */
  private static final long TICK_120 = 120L;

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── 判据一：默认来源 + tick 120 的日期/节气 + 无 q/r 省略 season ─────────────────────────

  /**
   * 空 store（未落 {@code store_meta.calendar}）⇒ 三个来源分别是 {@code default/default/fallback}；{@code
   * config} 是 §九 的默认算法；{@code tick 120} 的日期 = 1445-05-01、节气 = 立夏；未给坐标时 {@code season}
   * <b>整个字段省略</b>（notes 明说原因，不是静默丢）。
   */
  @Test
  void defaultSourcesAndTick120DateAndSolarTerm() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      JsonNode body = fx.info(Map.of("tick", TICK_120));

      assertThat(body.get("tick").asLong()).isEqualTo(TICK_120);

      JsonNode sources = body.get("sources");
      assertThat(sources.get("calendarSource").asText())
          .as("未落盘 ⇒ 历法来源 = default")
          .isEqualTo("default");
      assertThat(sources.get("seasonSource").asText())
          .as("未落盘 ⇒ 季节来源 = default")
          .isEqualTo("default");
      assertThat(sources.get("zoneSource").asText())
          .as("分带未配置 ⇒ 明确降级 = fallback（不假装已分带）")
          .isEqualTo("fallback");

      JsonNode config = body.get("config");
      assertThat(config.get("version").asInt()).isEqualTo(1);
      assertThat(config.get("calendar").asText()).isEqualTo("julian");
      assertThat(config.get("epoch").asText()).isEqualTo("1445-01-01");
      assertThat(config.get("seasonBoundary").asText()).isEqualTo("SOLAR_TERM");
      assertThat(config.get("tropicalModel").asText()).isEqualTo("RAINY_DRY");
      assertThat(config.get("tropicalRainyStartLongitude").asDouble()).isEqualTo(45.0);
      assertThat(config.get("tropicalRainyEndLongitude").asDouble()).isEqualTo(165.0);
      assertThat(config.get("northIsNegative").asBoolean()).isTrue();
      assertThat(config.get("northMax").isNull()).isTrue();
      assertThat(config.get("southMin").isNull()).isTrue();

      JsonNode date = body.get("date");
      assertThat(date.get("calendar").asText()).isEqualTo("julian");
      assertThat(date.get("year").asLong()).isEqualTo(1445L);
      assertThat(date.get("month").asInt()).isEqualTo(5);
      assertThat(date.get("day").asInt()).isEqualTo(1);
      assertThat(date.get("dayOfYear").asInt()).as("1445 平年第 121 天").isEqualTo(121);

      JsonNode solarTerm = body.get("solarTerm");
      assertThat(solarTerm.get("key").asText()).isEqualTo("lixia");
      assertThat(solarTerm.get("name").asText()).isEqualTo("立夏");
      assertThat(solarTerm.get("longitude").asDouble()).isEqualTo(45.0);

      assertThat(body.has("season")).as("未给 q/r 时 season 字段整体省略").isFalse();
      assertThat(body.get("notes")).as("省略理由必须写进 notes（不是静默丢）").isNotEmpty();
      assertThat(body.get("notes").toString()).contains("q/r");
    }
  }

  // ── 判据二：q/r 成对 → season；只给一个 → BAD_REQUEST ─────────────────────────────────

  /**
   * {@code q}/{@code r} 必须成对：只给一个（无论 q 还是 r）⇒ 具名 {@code BAD_REQUEST}，连日期都不返回；成对给出才追加 {@code
   * season}。分带未配置时 season 仍按全球北半球四季回答，{@code zoneSource=fallback}。
   */
  @Test
  void qAndRMustBePairedAndSeasonIsOmittedWithoutCoordinates() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      JsonNode noCoords = fx.info(Map.of("tick", TICK_120));
      assertThat(noCoords.has("season")).isFalse();

      // ★ 契约以代码为准（CalendarInfoTool.execute）：(q == null) != (r == null) ⇒ BAD_REQUEST，绝不静默按
      //   (0,0) 办。
      ToolResult qOnly = fx.infoResult(Map.of("tick", TICK_120, "q", 0L));
      assertThat(qOnly.success()).isFalse();
      assertThat(qOnly.code()).isEqualTo("BAD_REQUEST");
      assertThat(qOnly.message()).contains("q/r 必须成对给出");

      ToolResult rOnly = fx.infoResult(Map.of("tick", TICK_120, "r", 0L));
      assertThat(rOnly.success()).isFalse();
      assertThat(rOnly.code()).isEqualTo("BAD_REQUEST");
      assertThat(rOnly.message()).contains("q/r 必须成对给出");

      JsonNode season = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", -100L)).get("season");
      assertThat(season).as("成对给 q/r ⇒ season 必须出现").isNotNull();
      assertThat(season.get("phase").asText()).isEqualTo("TemperateSeason");
      assertThat(season.get("key").asText()).isEqualTo("summer");
      assertThat(season.get("name").asText()).isEqualTo("夏");
      assertThat(season.get("dayOfSeason").asInt()).isEqualTo(6);
      assertThat(season.get("daysInSeason").asInt()).isEqualTo(95);
      assertThat(season.get("zone").asText())
          .as("fallback = 全球按北半球判带")
          .isEqualTo("NORTH_TEMPERATE");
      assertThat(season.get("zoneSource").asText()).isEqualTo("fallback");
    }
  }

  // ── 判据三：northMax=-40/southMin=40 后按 r 分三带 ──────────────────────────────────────

  /**
   * GM 配置 {@code northMax=-40/southMin=40}（默认 {@code northIsNegative=true}：负 r 为北）后，同一 tick 120（日号
   * 2248965）按 r 分三带：
   *
   * <ul>
   *   <li>{@code r=-100} 北温带 ⇒ 夏（立夏后第 6 天，夏长 95）；
   *   <li>{@code r=100} 南温带 ⇒ 冬（南半球移相 180°，同为第 6 天、95 天）；
   *   <li>{@code r=0} 热带 ⇒ 雨季（黄经 49.35° 落在缺省 {@code [45°,165°)}）=> 日号 2248965 上 dayOfSeason=6、
   *       daysInSeason=126。
   * </ul>
   */
  @Test
  void configuredBandsRouteNorthTropicsAndSouthByR() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      JsonNode applied = fx.configure(Map.of("northMax", -40L, "southMin", 40L));
      assertThat(applied.get("applied").asBoolean()).isTrue();

      JsonNode north = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", -100L)).get("season");
      assertThat(north.get("phase").asText()).isEqualTo("TemperateSeason");
      assertThat(north.get("key").asText()).isEqualTo("summer");
      assertThat(north.get("name").asText()).isEqualTo("夏");
      assertThat(north.get("dayOfSeason").asInt()).isEqualTo(6);
      assertThat(north.get("daysInSeason").asInt()).isEqualTo(95);
      assertThat(north.get("zone").asText()).isEqualTo("NORTH_TEMPERATE");
      assertThat(north.get("zoneSource").asText()).isEqualTo("store");

      JsonNode south = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", 100L)).get("season");
      assertThat(south.get("phase").asText()).isEqualTo("TemperateSeason");
      assertThat(south.get("key").asText()).isEqualTo("winter");
      assertThat(south.get("name").asText()).isEqualTo("冬");
      assertThat(south.get("dayOfSeason").asInt()).isEqualTo(6);
      assertThat(south.get("daysInSeason").asInt()).isEqualTo(95);
      assertThat(south.get("zone").asText()).isEqualTo("SOUTH_TEMPERATE");
      assertThat(south.get("zoneSource").asText()).isEqualTo("store");

      JsonNode tropics = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", 0L)).get("season");
      assertThat(tropics.get("phase").asText()).isEqualTo("TropicalSeason");
      assertThat(tropics.get("key").asText()).isEqualTo("rainy");
      assertThat(tropics.get("name").asText()).isEqualTo("雨季");
      assertThat(tropics.get("dayOfSeason").asInt())
          .as("JDN 2248965 − 雨季起界 2248960 + 1")
          .isEqualTo(6);
      assertThat(tropics.get("daysInSeason").asInt())
          .as("雨季终界 2249086 − 起界 2248960")
          .isEqualTo(126);
      assertThat(tropics.get("zone").asText()).isEqualTo("TROPICS");
      assertThat(tropics.get("zoneSource").asText()).isEqualTo("store");
    }
  }

  // ── 判据四：GM 写入后三个来源都翻成 store ─────────────────────────────────────────────

  /** GM 正式 apply 落盘后，再读 info：{@code calendarSource/seasonSource/zoneSource} 都必须是 {@code store}。 */
  @Test
  void gmWriteFlipsAllThreeSourcesToStore() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      assertThat(fx.info(Map.of("tick", TICK_120)).get("sources").get("zoneSource").asText())
          .as("写前 = fallback")
          .isEqualTo("fallback");

      fx.configure(Map.of("northMax", -40L, "southMin", 40L));

      JsonNode sources = fx.info(Map.of("tick", TICK_120)).get("sources");
      assertThat(sources.get("calendarSource").asText()).isEqualTo("store");
      assertThat(sources.get("seasonSource").asText()).isEqualTo("store");
      assertThat(sources.get("zoneSource").asText()).isEqualTo("store");
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 真 {@link CoreSimos} + 空模块创世 + 启动期同款的 {@link CalendarService#load}；不注册任何 codec（创世状态没有模块）， 不启任何
   * HTTP。两个工具共用同一份服务实例，故 GM 写口 apply 后读口当场看到 {@code store}。
   */
  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final CalendarInfoTool infoTool;
    final CalendarConfigureTool configureTool;

    private Fixture(CoreSimos core) {
      this.core = core;
      QueryService query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      CalendarService calendar = CalendarService.load(core);
      this.infoTool = new CalendarInfoTool(query, calendar);
      this.configureTool = new CalendarConfigureTool(query, calendar);
    }

    static Fixture open(Path storeDir) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      Fixture fixture = new Fixture(core);
      core.bootstrapGenesis(emptyGenesis());
      return fixture;
    }

    ToolResult infoResult(Map<String, Object> args) {
      return infoTool.execute(context(args));
    }

    JsonNode info(Map<String, Object> args) throws Exception {
      ToolResult result = infoResult(args);
      assertThat(result.success())
          .as("%s(%s): %s", CalendarInfoTool.NAME, args, result.message())
          .isTrue();
      return JSON.readTree(result.message());
    }

    JsonNode configure(Map<String, Object> args) throws Exception {
      ToolResult result = configureTool.execute(context(args));
      assertThat(result.success())
          .as("%s(%s): %s", CalendarConfigureTool.NAME, args, result.message())
          .isTrue();
      return JSON.readTree(result.message());
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static SimulationState emptyGenesis() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    return new SimulationState(new StateMeta(ref, T7), Map.of(), InMemoryInfoSystem.empty());
  }

  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(AccessToken.DEFAULT, gmPermissions(), Map.of(), args);
  }

  private static AgentPermissionSet gmPermissions() {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .sensitiveAllowed(true)
        .destructiveAllowed(true)
        .build();
  }
}
