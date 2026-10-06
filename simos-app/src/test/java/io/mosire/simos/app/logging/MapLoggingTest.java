package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.CreateRegionHandler;
import io.mosire.simos.map.spi.RenameRegionHandler;
import io.mosire.simos.map.spi.SetTerrainHandler;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>map 模块日志契约验收</b>（计划 {@code docs/superpowers/plans/2026-10-23-all-module-logging-rollout.md}
 * §3/§4.2/§4.4/§9；形态照 {@code SocialLoggingTest}）。
 *
 * <p>用 log4j2 appender 捕获 {@code io.mosire.simos.map} 根 + 其 {@code .trace} 子 logger，断言：
 *
 * <ul>
 *   <li>前提：采集面真的非空（空集上"不含某串"会假绿——{@code CommandBusLoggingTest} 已记过这个坑）；
 *   <li>INFO 写口成功/具名拒绝的事件名 + 关键字段 + {@code origin=} + {@code originKind=} 三者齐；
 *   <li>被拒绝一律 INFO（用户 2026-10-23：「被拒绝肯定走 INFO」）；
 *   <li>{@link MapLog#ROOT_LOGGER_NAME} 升降级真的改变 INFO 输出；{@link MapLog#TRACE_LOGGER_NAME} 开/关真的改变逐格
 *       TRACE 输出；
 *   <li>既有 WARN 不接受降级（{@code MAP_CODEC_LEGACY_CHANGE_SET_UNMIGRATABLE}）。
 * </ul>
 *
 * <p>★ 夹具住 app 模块：只有 app 的测试类路径上有 log4j-core + log4j-slf4j2-impl；被验对象仍是 {@code io.mosire.simos.map}
 * 及其子 logger。地图夹具照 {@code RegionHandlersTest} 内联（模块 test-jar 不可用）。
 *
 * <p>★ <b>没有 TICK 断言的原因</b>：map 的 {@code MapLogSource}
 * 四项（map-edit/map-generate/map-codec/map-resolve） 全标 {@code SYSTEM}，且全部发射点都没有 {@code day} 字段——map 无
 * tick 结算面，故不硬造 day； 这是报告里的「无低成本 TICK 事件」结论。
 */
class MapLoggingTest {

  private static final String APPENDER_NAME = "map-logging-capture";
  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HexCoord H2 = new HexCoord(2, 0);

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig mapConfig;
  private Level originalMapLevel;
  private Level originalTraceLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    mapConfig = configuration.getLoggerConfig(MapLog.ROOT_LOGGER_NAME);
    originalMapLevel = mapConfig.getLevel();
    originalTraceLevel = configuration.getLoggerConfig(MapLog.TRACE_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    attachAppender();
    context.updateLoggers();
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(MapLog.ROOT_LOGGER_NAME, originalMapLevel);
    Configurator.setLevel(MapLog.TRACE_LOGGER_NAME, originalTraceLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(MapLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void log4jConfigReadsTheMapLevelProperties() {
    assertThat(mapConfig.getName())
        .as("log4j2.xml 必须为 io.mosire.simos.map 建一条显式 LoggerConfig")
        .isEqualTo(MapLog.ROOT_LOGGER_NAME);
    assertThat(originalMapLevel.toString())
        .as("默认/覆盖级别必须来自 simos.map.logLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.map.logLevel", "INFO"));
    assertThat(originalTraceLevel.toString())
        .as("TRACE 明细级别必须来自 simos.map.traceLevel")
        .isEqualToIgnoringCase(System.getProperty("simos.map.traceLevel", "INFO"));
  }

  @Test
  void logLinesAreActuallyCaptured() {
    setMapLevels(Level.INFO, Level.INFO);
    appender.clear();
    createRegion("r1");

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  /** 判别力：把 {@code MAP_CREATE_REGION_APPLIED} 改名、降级、去掉 origin/originKind、写错 region/hexes， 这里都当场红。 */
  @Test
  void createRegionAppliedIsInfoWithKeyFieldsOriginAndOriginKind() {
    setMapLevels(Level.INFO, Level.INFO);
    appender.clear();

    HandlerOutcome outcome = createRegion("r1");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    List<String> lines = appender.messages();
    assertThat(lines)
        .as("region 写口成功必须是 INFO 事件；实得 %s", lines)
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|")
                    .contains("event=MAP_CREATE_REGION_APPLIED")
                    .contains("region=r1")
                    .contains("hexes=2")
                    .contains("origin=map-edit")
                    .contains("originKind=system"));
  }

  /** 判别力：把具名拒绝降级成 DEBUG/WARN、去掉 reason/region 或 origin，这里当场红。 */
  @Test
  void namedRejectionIsLoggedAsInfoWithReasonAndRegion() {
    setMapLevels(Level.INFO, Level.INFO);
    appender.clear();

    HandlerOutcome outcome =
        new RenameRegionHandler()
            .handle(stateOf(emptyRegionMap()), "{\"regionId\":\"missing\",\"name\":\"新名\"}");

    assertThat(outcome)
        .as("目标 region 不存在 ⇒ 必须具名 Rejected")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("missing");
    assertThat(appender.messages())
        .as("被拒绝一律 INFO（用户 2026-10-23）；实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|")
                    .contains("event=MAP_RENAME_REGION_REJECTED")
                    .contains("reason=区域不存在")
                    .contains("region=missing")
                    .contains("origin=map-edit")
                    .contains("originKind=system"));
  }

  /** 判别力：INFO 行若走不受 level 约束的旁路（或不随根级别升降），这里的「消失/出现」必有一头红。 */
  @Test
  void configuratorLevelSwitchRemovesAndRestoresInfoLines() {
    setMapLevels(Level.INFO, Level.INFO);
    appender.clear();
    createRegion("r1");
    assertEvent(appender.messages(), "MAP_CREATE_REGION_APPLIED");

    appender.clear();
    setMapLevels(Level.WARN, Level.INFO);
    createRegion("r2");
    assertThat(appender.messages())
        .as("map 根级别 WARN ⇒ map INFO 必须消失；实得 %s", appender.messages())
        .noneSatisfy(line -> assertThat(line).contains("event=MAP_CREATE_REGION_APPLIED"));

    appender.clear();
    setMapLevels(Level.INFO, Level.INFO);
    createRegion("r3");
    assertThat(appender.messages())
        .as("调回 INFO ⇒ map INFO 必须重新出现；实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line).contains("event=MAP_CREATE_REGION_APPLIED").contains("region=r3"));
  }

  /** 判别力：DEBUG 诊断若提级到 INFO（或反过来降级）都会让本用例红。 */
  @Test
  void resolveEmptyDiagnosticAppearsOnlyWhenDebugIsEnabled() {
    setMapLevels(Level.DEBUG, Level.INFO);
    appender.clear();
    assertThat(resolveMissingHex().candidates()).isEmpty();
    assertThat(appender.messages())
        .as("DEBUG 打开时空候选必须有一条判据说明；实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("DEBUG|")
                    .contains("event=MAP_RESOLVE_EMPTY")
                    .contains("reason=hex 不存在")
                    .contains("mapId=m1")
                    .contains("hex=9_9")
                    .contains("origin=map-resolve")
                    .contains("originKind=system"));

    appender.clear();
    setMapLevels(Level.INFO, Level.INFO);
    assertThat(resolveMissingHex().candidates()).isEmpty();
    assertThat(appender.messages())
        .as("INFO 级别不得出现 DEBUG 诊断；实得 %s", appender.messages())
        .noneSatisfy(line -> assertThat(line).contains("event=MAP_RESOLVE_EMPTY"));
  }

  /**
   * TRACE 只挂 {@link MapLog#TRACE_LOGGER_NAME}：打开后逐格 {@code MAP_TERRAIN_HEX} 出现，关掉后消失， 而 INFO
   * 汇总（{@code MAP_SET_TERRAIN_APPLIED}）照旧——证明开关只管逐笔明细，不是把整条命令关掉。
   */
  @Test
  void terrainHexTraceAppearsOnlyWhenTraceLoggerIsRaised() {
    setMapLevels(Level.INFO, Level.TRACE);
    appender.clear();
    assertThat(setTerrainOnH0()).isInstanceOf(HandlerOutcome.Applied.class);

    assertThat(appender.messages())
        .as("TRACE 打开后逐格明细必须出现；实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("TRACE|")
                    .contains("event=MAP_TERRAIN_HEX")
                    .contains("hex=0_0")
                    .contains("terrain=plains")
                    .contains("height=0.375")
                    .contains("origin=map-edit")
                    .contains("originKind=system"));

    appender.clear();
    setMapLevels(Level.INFO, Level.INFO);
    assertThat(setTerrainOnH0()).isInstanceOf(HandlerOutcome.Applied.class);
    List<String> atInfo = appender.messages();
    assertThat(atInfo)
        .as("TRACE 关上后 MAP_TERRAIN_HEX 必须消失；实得 %s", atInfo)
        .noneSatisfy(line -> assertThat(line).contains("event=MAP_TERRAIN_HEX"));
    assertEvent(atInfo, "MAP_SET_TERRAIN_APPLIED");
  }

  /** 判别力：把既有 WARN 降级成 INFO/DEBUG，或去掉 origin，这里当场红。 */
  @Test
  void legacyUnmigratableChangeSetStaysWarnWithOrigin() {
    setMapLevels(Level.INFO, Level.INFO);
    appender.clear();

    assertThatThrownBy(
            () ->
                new MapCodec()
                    .decodeChangeSet(
                        "{\"hexes\":{\"0_0\":{\"terrain\":\"plains\",\"height\":0.3}}}"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(appender.messages())
        .as("既有 WARN 不接受降级（用户 2026-10-23）；实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("WARN|")
                    .contains("event=MAP_CODEC_LEGACY_CHANGE_SET_UNMIGRATABLE")
                    .contains("origin=map-codec")
                    .contains("originKind=system")
                    .contains("reason="));
  }

  /** 改级别后 {@code Configurator} 可能替换 LoggerConfig ⇒ 把采集 appender 重新挂到 map 根配置上。 */
  private void setMapLevels(Level root, Level trace) {
    Configurator.setLevel(MapLog.ROOT_LOGGER_NAME, root);
    Configurator.setLevel(MapLog.TRACE_LOGGER_NAME, trace);
    attachAppender();
    context.updateLoggers();
  }

  private void attachAppender() {
    LoggerConfig current = configuration.getLoggerConfig(MapLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
  }

  private static void assertEvent(List<String> lines, String event) {
    assertThat(lines)
        .as("必须至少有一条 event=%s（实得 %s）", event, lines)
        .anySatisfy(line -> assertThat(line).contains("event=" + event));
  }

  private static HandlerOutcome createRegion(String regionId) {
    return new CreateRegionHandler()
        .handle(
            stateOf(emptyRegionMap()),
            "{\"regionId\":\""
                + regionId
                + "\",\"name\":\"甲区\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],"
                + "\"meta\":{\"color\":\"#abc\",\"tag\":\"Nation\"}}");
  }

  private static HandlerOutcome setTerrainOnH0() {
    return new SetTerrainHandler()
        .handle(
            stateOf(emptyRegionMap()), "{\"hexes\":[{\"q\":0,\"r\":0}],\"terrain\":\"plains\"}");
  }

  private static QueryResult resolveMissingHex() {
    SimulationState state = stateOf(emptyRegionMap());
    return new MapResolver()
        .resolve(Address.parse("map:m1:hex.9_9"), new ResolveContext(state, SimosTimestamp.of(0)));
  }

  /** 夹具照 {@code RegionHandlersTest}：三格小图 + 空区域表，不打 DB。 */
  private static GameMap emptyRegionMap() {
    return mapOf(Map.of());
  }

  private static GameMap mapOf(Map<RegionId, Region> regions) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, new HexCell(0.4));
    cells.put(H1, new HexCell(0.5));
    cells.put(H2, new HexCell(0.6));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        regions,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SimulationState stateOf(GameMap map) {
    BranchId main = new BranchId("main");
    StateRef ref = new StateRef(main, new RevisionId(1));
    SimosTimestamp t = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, t),
        Map.of("map", new MapSnapshot(ref, t, map)),
        InMemoryInfoSystem.empty());
  }

  /** 采集 appender：记 {@code level|message}，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> messages = new ArrayList<>();

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      return List.copyOf(messages);
    }

    private void clear() {
      messages.clear();
    }
  }
}
