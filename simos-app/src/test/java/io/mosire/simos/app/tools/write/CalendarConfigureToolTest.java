package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarConfig;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.read.CalendarInfoTool;
import io.mosire.simos.calendar.CalendarDefaults;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.calendar.configure}（C5b GM 写工具，设计稿 §七；D-018 补裁）的逐值验收。
 *
 * <p>口径（以 {@link CalendarConfigureTool} 实现为准）：字段全部可选、<b>部分合并</b>；{@code dryRun=true} 只预览 （不落 {@code
 * store_meta.calendar}、不换内存快照）；正式 apply 落盘并原子换快照，读口来源翻成 {@code store}；非法值 fail-closed 成具名 {@code
 * BAD_REQUEST}；这是<b>库级元数据写</b>，不落 revision。工具<b>只在 GM 写桶</b>（{@code
 * SimosToolSource.addGmWrites}），读工具 {@code simos.calendar.info} 则两档共享。
 *
 * <p>夹具与 {@code ArmyCombatReadToolsTest} 同法：真 {@link CoreSimos} + 空模块创世（时间戳 {@code of(7)}）+ 临时
 * 目录；工具直接执行（不起真 HTTP）。桶归属那条用 {@link Shell#toolsFor}——本仓的“四桶注册 API”就是它按 {@link SimosToolSource.Role}
 * 重建工具面。
 */
class CalendarConfigureToolTest {

  private static final long TICK_120 = 120L;
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── 判据一：部分合并（不触碰的字段沿用当前值/缺省） ───────────────────────────────────

  /** 只给 {@code northMax=-40/southMin=40}：分带落盘，其余字段（历法/锚点/季界/热带/轴向）必须逐项保留缺省。 */
  @Test
  void partialMergeKeepsEveryUnsetFieldAtItsCurrentOrDefaultValue() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      long headBefore = fx.head();

      JsonNode body = fx.configure(Map.of("northMax", -40L, "southMin", 40L));

      assertThat(body.get("applied").asBoolean()).isTrue();
      assertThat(body.get("dryRun").asBoolean()).isFalse();
      assertThat(fx.head()).as("库级元数据写不落世界 revision").isEqualTo(headBefore);

      JsonNode config = body.get("config");
      assertThat(config.get("version").asInt()).isEqualTo(1);
      assertThat(config.get("calendar").asText()).as("未给的历法沿用缺省 julian").isEqualTo("julian");
      assertThat(config.get("epoch").asText()).as("未给的锚点沿用缺省").isEqualTo("1445-01-01");
      assertThat(config.get("seasonBoundary").asText()).isEqualTo("SOLAR_TERM");
      assertThat(config.get("tropicalModel").asText()).isEqualTo("RAINY_DRY");
      assertThat(config.get("tropicalRainyStartLongitude").asDouble()).isEqualTo(45.0);
      assertThat(config.get("tropicalRainyEndLongitude").asDouble()).isEqualTo(165.0);
      assertThat(config.get("northIsNegative").asBoolean()).isTrue();
      assertThat(config.get("northMax").asLong()).isEqualTo(-40L);
      assertThat(config.get("southMin").asLong()).isEqualTo(40L);

      JsonNode sources = body.get("sources");
      assertThat(sources.get("calendarSource").asText()).isEqualTo("store");
      assertThat(sources.get("seasonSource").asText()).isEqualTo("store");
      assertThat(sources.get("zoneSource").asText()).isEqualTo("store");
    }
  }

  // ── 判据二：dryRun 只预览 ────────────────────────────────────────────────────────────

  /** dryRun=true：不落盘、不换快照、sources/config 仍是改前真相；warnings 必须含锚点平移语义。 */
  @Test
  void dryRunNeitherPersistsNorSwapsTheSnapshot() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      assertThat(fx.storeMeta()).as("启动期缺省配置不落盘").isEmpty();

      JsonNode body = fx.configure(Map.of("northMax", -40L, "southMin", 40L, "dryRun", true));

      assertThat(body.get("applied").asBoolean()).isFalse();
      assertThat(body.get("dryRun").asBoolean()).isTrue();

      JsonNode warnings = body.get("warnings");
      assertThat(warnings).isNotEmpty();
      assertThat(warnings.get(0).asText())
          .as("锚点变更会让历史日期整体平移——即使本次没改锚点也必须明示")
          .contains("锚点变更")
          .contains("平移")
          .contains("dryRun");

      // 不换快照：响应里的 config/sources 仍是改前（default/default/fallback，northMax=null）。
      assertThat(body.get("config").get("northMax").isNull()).isTrue();
      assertThat(body.get("sources").get("calendarSource").asText()).isEqualTo("default");
      assertThat(body.get("sources").get("seasonSource").asText()).isEqualTo("default");
      assertThat(body.get("sources").get("zoneSource").asText()).isEqualTo("fallback");

      // 不落盘：store_meta 仍为空，新装的服务仍是缺省。
      assertThat(fx.storeMeta()).as("dryRun 不得落盘").isEmpty();
      CalendarService reloaded = CalendarService.load(fx.core);
      assertThat(reloaded.config().northMax()).isNull();
      assertThat(reloaded.zoneSource()).isEqualTo(CalendarConfig.Source.FALLBACK);

      // 读工具也仍是 fallback（分带未生效）。
      JsonNode info = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", -100L));
      assertThat(info.get("sources").get("zoneSource").asText()).isEqualTo("fallback");
      assertThat(info.get("config").get("northMax").isNull()).isTrue();
      assertThat(info.get("season").get("zoneSource").asText()).isEqualTo("fallback");
    }
  }

  // ── 判据三：正式 apply 落盘 + 重装 + 读工具 source=store ──────────────────────────────

  /** 正式 apply：store_meta 有值、重装解析后仍是 store 来源、读工具同报 store，且不落 revision。 */
  @Test
  void applyPersistsReloadsAndTheInfoReaderReportsStore() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      long headBefore = fx.head();

      JsonNode body = fx.configure(Map.of("northMax", -40L, "southMin", 40L));

      assertThat(body.get("applied").asBoolean()).isTrue();
      assertThat(body.get("dryRun").asBoolean()).isFalse();
      assertThat(fx.head()).as("配置写不落 revision").isEqualTo(headBefore);
      assertThat(body.get("sources").get("calendarSource").asText()).isEqualTo("store");
      assertThat(body.get("sources").get("seasonSource").asText()).isEqualTo("store");
      assertThat(body.get("sources").get("zoneSource").asText()).isEqualTo("store");

      String stored = fx.storeMeta().orElseThrow();
      JsonNode persisted = JSON.readTree(stored);
      assertThat(persisted.get("version").asInt()).isEqualTo(1);
      assertThat(persisted.get("calendar").asText()).isEqualTo("julian");
      assertThat(persisted.get("epoch").asText()).isEqualTo("1445-01-01");
      assertThat(persisted.get("seasonBoundary").asText()).isEqualTo("SOLAR_TERM");
      assertThat(persisted.get("northMax").asLong()).isEqualTo(-40L);
      assertThat(persisted.get("southMin").asLong()).isEqualTo(40L);

      // 用同一 core 重装（模拟重启装载路径）：解析出的来源必须是 store。
      CalendarService reloaded = CalendarService.load(fx.core);
      assertThat(reloaded.config().northMax()).isEqualTo(-40L);
      assertThat(reloaded.config().southMin()).isEqualTo(40L);
      assertThat(reloaded.calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
      assertThat(reloaded.seasonSource()).isEqualTo(CalendarConfig.Source.STORE);
      assertThat(reloaded.zoneSource()).isEqualTo(CalendarConfig.Source.STORE);

      JsonNode info = fx.info(Map.of("tick", TICK_120, "q", 0L, "r", 0L));
      assertThat(info.get("sources").get("calendarSource").asText()).isEqualTo("store");
      assertThat(info.get("sources").get("seasonSource").asText()).isEqualTo("store");
      assertThat(info.get("sources").get("zoneSource").asText()).isEqualTo("store");
      assertThat(info.get("config").get("northMax").asLong()).isEqualTo(-40L);
    }
  }

  // ── 判据四：非法值逐条 fail-closed + 零落盘 ─────────────────────────────────────────

  /** 非法字段值逐条 {@code BAD_REQUEST}；每拒一次都不得落 store_meta、不得动 revision。 */
  @Test
  void eachInvalidValueIsBadRequestAndNothingLands() {
    record Case(String label, Map<String, Object> args, String fragment) {}
    List<Case> cases =
        List.of(
            new Case("坏 epoch 形状（不是 y-m-d）", Map.of("epoch", "1445/01/01"), "epoch 必须匹配 y-m-d"),
            new Case("epoch 月内非法（1445-02-30）", Map.of("epoch", "1445-02-30"), "该月只有 28 天"),
            new Case(
                "雨季 start ≥ end",
                Map.of(
                    "tropicalRainyStartLongitude", 165.0,
                    "tropicalRainyEndLongitude", 45.0),
                "0 ≤ start < end"),
            new Case("只给 northMax（分带不成对）", Map.of("northMax", -40L), "必须成对"),
            new Case(
                "seasonBoundary 枚举非法", Map.of("seasonBoundary", "bad-json"), "seasonBoundary 只接受"));

    try (Fixture fx = Fixture.open(tempDir.resolve("invalid"))) {
      long headBefore = fx.head();
      for (Case one : cases) {
        ToolResult result = fx.configureResult(one.args());

        assertThat(result.success()).as("[%s] 必须拒", one.label()).isFalse();
        assertThat(result.code()).as("[%s]", one.label()).isEqualTo("BAD_REQUEST");
        assertThat(result.message()).as("[%s]", one.label()).contains(one.fragment());
        assertThat(fx.storeMeta()).as("[%s] 非法输入零落盘", one.label()).isEmpty();
        assertThat(fx.head()).as("[%s] 非法输入零 revision", one.label()).isEqualTo(headBefore);
      }
    }
  }

  /** 已落盘的 {@code store_meta.calendar} 一旦不是合法 JSON，装载必须 fail-closed（不回落缺省）。 */
  @Test
  void malformedStoredJsonFailsClosedOnLoad() {
    try (Fixture fx = Fixture.open(tempDir)) {
      fx.core.writeStoreMeta(CalendarService.STORE_META_KEY, "{\"calendar\":");
      assertThatThrownBy(() -> CalendarService.load(fx.core))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("不是合法 JSON");
    }
  }

  // ── 判据五：calendar 只认 julian（显式给出即校验，不静默忽略） ───────────────────────────

  /** {@code CalendarConfig} 是历法 id 的真正解析口：{@code gregorian} 在那里被 fail-closed。 */
  @Test
  void gregorianCalendarIdIsRejectedWhereConfigIsConstructed() {
    assertThatThrownBy(
            () ->
                new CalendarConfig(
                    CalendarConfig.VERSION_1,
                    "gregorian",
                    CalendarDefaults.EPOCH_DATE,
                    CalendarDefaults.SEASON_BOUNDARY,
                    CalendarDefaults.TROPICAL_MODEL,
                    CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE,
                    CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE,
                    CalendarDefaults.NORTH_IS_NEGATIVE,
                    null,
                    null,
                    CalendarConfig.Source.DEFAULT,
                    CalendarConfig.Source.DEFAULT,
                    CalendarConfig.Source.FALLBACK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只认 \"julian\"");
  }

  /**
   * C7 生产 B 修复后的口径：显式 {@code calendar=gregorian} 必须在任何落盘之前被拒成具名 {@code BAD_REQUEST}；已有 {@code
   * store_meta.calendar} 的字节必须逐字节不变（不覆盖、不先写后滚、不静默忽略）。
   *
   * <p>C7a 的 characterization 用例（{@code
   * calendarGregorianArgumentIsSilentlyIgnoredTodayProductionGap}）钉的是修复前 “静默忽略并成功落盘”的现状；生产 B（{@code
   * ddcc9f17}）让 {@code merge} 读取 {@code args.calendar} 后该现状已消失， 本用例改钉修复后的拒绝行为。
   */
  @Test
  void gregorianCalendarIsRejectedAndStoredBytesAreUnchanged() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      // 先落一条合法配置，让“字节不变”有真实前态：空→空证明不了“拒绝不覆盖已有值”。
      fx.configure(Map.of("calendar", "julian"));
      byte[] before = fx.storeMetaBytes();
      assertThat(before).as("前置：store_meta.calendar 已有合法落盘").isNotNull();

      ToolResult result = fx.configureResult(Map.of("calendar", "gregorian"));

      assertThat(result.success()).as("gregorian 必须在落盘前被拒").isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).contains("calendar").contains("julian");
      assertThat(fx.storeMetaBytes()).as("拒写不得改动 store_meta.calendar 的字节").isEqualTo(before);
    }
  }

  /**
   * {@code calendar} 的其余非法形态逐条 fail-closed：显式 JSON null（Jackson {@link NullNode}）、带前后空格（不 trim） 都
   * {@code BAD_REQUEST}；schema 之外的未知参数（typo {@code epock}）同样具名拒绝——每拒一次都零落盘、head 不动。
   *
   * <p>★ Java 级 {@code null} 值过不了 agentlib {@link ToolContext} 的入口装配（{@code Map.copyOf} 当场
   * NPE），故“显式 null”在真实 {@code execute} 路径上只能以 JSON 树模型的 {@link NullNode} 形态出现；Java-null 分支另见 {@link
   * #javaNullCalendarIsRejectedAtTheMergeSeam}。
   */
  @Test
  void invalidCalendarValuesAndUnknownArgsAreBadRequestAndNothingLands() {
    record Case(String label, Map<String, Object> args, String fragment) {}
    Map<String, Object> jsonNullCalendar = new LinkedHashMap<>();
    jsonNullCalendar.put("calendar", NullNode.getInstance());
    List<Case> cases =
        List.of(
            new Case("calendar 显式 JSON null", jsonNullCalendar, "calendar 必须"),
            new Case("calendar 带前后空格（不 trim）", Map.of("calendar", " julian "), "calendar 必须"),
            new Case("未知参数 epock", Map.of("epock", "1444-01-01"), "不支持的参数：epock"));

    try (Fixture fx = Fixture.open(tempDir.resolve("invalid-calendar"))) {
      long headBefore = fx.head();
      for (Case one : cases) {
        ToolResult result = fx.configureResult(one.args());

        assertThat(result.success()).as("[%s] 必须拒", one.label()).isFalse();
        assertThat(result.code()).as("[%s]", one.label()).isEqualTo("BAD_REQUEST");
        assertThat(result.message()).as("[%s]", one.label()).contains(one.fragment());
        assertThat(fx.storeMeta()).as("[%s] 非法输入零落盘", one.label()).isEmpty();
        assertThat(fx.storeMetaBytes()).as("[%s] 零落盘 = 无字节", one.label()).isNull();
        assertThat(fx.head()).as("[%s] 非法输入零 revision", one.label()).isEqualTo(headBefore);
      }
    }
  }

  /**
   * Java {@code null} 值形态（{@code {"calendar": null}}）：agentlib {@link ToolContext} 入口 {@code
   * Map.copyOf} 先 NPE，真实工具链到不了 {@code merge}；这里直接反射私有合并口，钉生产端“显式 null 也必须拒”的分支。
   */
  @Test
  void javaNullCalendarIsRejectedAtTheMergeSeam() throws Exception {
    Map<String, Object> nullCalendar = new LinkedHashMap<>();
    nullCalendar.put("calendar", null);
    Method merge =
        CalendarConfigureTool.class.getDeclaredMethod("merge", Map.class, CalendarConfig.class);
    merge.setAccessible(true);

    assertThatThrownBy(() -> merge.invoke(null, nullCalendar, CalendarConfig.defaults()))
        .isInstanceOf(InvocationTargetException.class)
        .cause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("calendar 必须");
  }

  /**
   * 显式给对 {@code calendar="julian"}：正式 apply 成功，store_meta.calendar 落盘含 {@code "calendar":"julian"}。
   */
  @Test
  void explicitJulianCalendarAppliesAndPersistsJulian() throws Exception {
    try (Fixture fx = Fixture.open(tempDir)) {
      long headBefore = fx.head();

      JsonNode body = fx.configure(Map.of("calendar", "julian"));

      assertThat(body.get("applied").asBoolean()).isTrue();
      assertThat(body.get("dryRun").asBoolean()).isFalse();
      assertThat(body.get("config").get("calendar").asText()).isEqualTo("julian");
      assertThat(body.get("sources").get("calendarSource").asText()).isEqualTo("store");
      assertThat(fx.head()).as("库级元数据写不落世界 revision").isEqualTo(headBefore);

      JsonNode persisted = JSON.readTree(fx.storeMeta().orElseThrow());
      assertThat(persisted.get("calendar").asText()).isEqualTo("julian");

      CalendarService reloaded = CalendarService.load(fx.core);
      assertThat(reloaded.config().calendar()).isEqualTo("julian");
      assertThat(reloaded.calendarSource()).isEqualTo(CalendarConfig.Source.STORE);
    }
  }

  // ── 判据六：敏感写 + 审批门（GM 面） ────────────────────────────────────────────────

  /** {@code spec().sensitive()=true} + {@link ToolGate.Ask}（classKey = 工具名）；不是 destructive。 */
  @Test
  void configureIsASensitiveWriteAskingWithTheToolNameAsClassKey() {
    try (Fixture fx = Fixture.open(tempDir)) {
      assertThat(fx.configureTool.spec().sensitive()).isTrue();
      assertThat(fx.configureTool.spec().destructive()).isFalse();

      ToolGate gate = fx.configureTool.gate(context(Map.of()));
      assertThat(gate).isInstanceOf(ToolGate.Ask.class);
      ToolGate.Ask ask = (ToolGate.Ask) gate;
      assertThat(ask.classKey()).isEqualTo(CalendarConfigureTool.NAME);
      assertThat(ask.kind()).isEqualTo(AskKind.SENSITIVE);
    }
  }

  // ── 判据七：桶归属（只在 GM 写桶；info 读口两档共享） ───────────────────────────────

  /** 用实际注册 API {@link Shell#toolsFor} 断言：configure 只在 GM 桶；info 两档都有。 */
  @Test
  void configureToolIsOnlyInTheGmBucketWhileInfoIsShared() {
    Path storeDir = tempDir.resolve("bucket-world");
    try (CoreSimos seed = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()))) {
      seed.bootstrapGenesis(emptyGenesis());
    }
    try (Shell shell = Shell.start(ShellConfig.defaults(storeDir).withPorts(0, 0, 0))) {
      assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
          .contains(CalendarConfigureTool.NAME);
      assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
          .as("决策人桶不得含世界级配置写口")
          .doesNotContain(CalendarConfigureTool.NAME);

      assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.GM)))
          .contains(CalendarInfoTool.NAME);
      assertThat(toolNames(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
          .as("历法读口四桶共享")
          .contains(CalendarInfoTool.NAME);

      assertThat(shell.toolRegistry().find(CalendarConfigureTool.NAME)).isPresent();
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 真 {@link CoreSimos} + 空模块创世 + 启动期同款 {@link CalendarService#load}；工具直接执行，不起 HTTP。 */
  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final CalendarConfigureTool configureTool;
    final CalendarInfoTool infoTool;

    private Fixture(CoreSimos core) {
      this.core = core;
      QueryService query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      CalendarService calendar = CalendarService.load(core);
      this.configureTool = new CalendarConfigureTool(query, calendar);
      this.infoTool = new CalendarInfoTool(query, calendar);
    }

    static Fixture open(Path storeDir) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      Fixture fixture = new Fixture(core);
      core.bootstrapGenesis(emptyGenesis());
      return fixture;
    }

    ToolResult configureResult(Map<String, Object> args) {
      return configureTool.execute(context(args));
    }

    JsonNode configure(Map<String, Object> args) throws Exception {
      ToolResult result = configureResult(args);
      assertThat(result.success())
          .as("%s(%s): %s", CalendarConfigureTool.NAME, args, result.message())
          .isTrue();
      return JSON.readTree(result.message());
    }

    JsonNode info(Map<String, Object> args) throws Exception {
      ToolResult result = infoTool.execute(context(args));
      assertThat(result.success())
          .as("%s(%s): %s", CalendarInfoTool.NAME, args, result.message())
          .isTrue();
      return JSON.readTree(result.message());
    }

    Optional<String> storeMeta() {
      return core.readStoreMeta(CalendarService.STORE_META_KEY);
    }

    /** C7a 既有读法（{@link #storeMeta()}）的 UTF-8 字节形态；缺键 ⇒ null，用于“字节不变 / 零落盘”断言。 */
    byte[] storeMetaBytes() {
      return storeMeta().map(value -> value.getBytes(StandardCharsets.UTF_8)).orElse(null);
    }

    long head() {
      return core.head(new BranchId("main")).orElseThrow().value();
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

  private static List<String> toolNames(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }
}
