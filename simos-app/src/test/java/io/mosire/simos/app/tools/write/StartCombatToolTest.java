package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.SetStateDescriptionHandler;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.army.startCombat}（阶段 D4 / D-009 补裁 + D-010 + D-012）的真命令总线用例：
 *
 * <ul>
 *   <li>preview（缺省 true）零写入——head / revision 行 / 两条内嵌命令的 handler 调用次数全不动；
 *   <li>apply 一批 = 一条 revision，批内顺序 {@code army.RecordCombat → unit.SetStateDescription × N}：用真
 *       {@code CoreSimos} 的 {@link MutationGuard} 故意拒掉第二条类型把整批逼成 {@code REJECTED}，从工具结果 {@code
 *       submission.commands} 读**真信封序**（不是复制出来的清单）；同时用 handler 记录器在成功轮再核一遍；
 *   <li>每个参与单位 {@code stateDescriptions["combat"] == "army:combat.<id>"}，初始阶段由 handler 合成为 {@code
 *       start}；
 *   <li>失败具名（id 已存在 / 参与单位不存在）零 revision；
 *   <li>GM-only 工具面：真 {@code Shell} 的 GM 桶含它、决策人桶不含它。
 * </ul>
 *
 * <p>★ 世界是手搭创世 checkpoint（unit + army；其余切片空）——本工具只读 unit/army 两片。
 */
class StartCombatToolTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── preview：零写入 ─────────────────────────────────────────────────────────────────

  @Test
  void previewComputesTheSemanticsWithoutWritingAnything() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("preview"))) {
      long headBefore = fx.head();
      int revisionsBefore = fx.core.revisions(main()).size();

      ToolResult result =
          fx.call(startArgs("c-1", "野战", List.of(U1.value(), U2.value()), null, null));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("preview").asBoolean()).isTrue();
      assertThat(view.get("submitted").asBoolean()).isFalse();
      assertThat(view.get("combatId").asText()).isEqualTo("c-1");
      assertThat(view.get("kind").asText()).isEqualTo("野战");
      assertThat(view.get("hex").get("q").asInt()).isEqualTo(1);
      assertThat(view.get("hex").get("r").asInt()).isEqualTo(1);
      assertThat(values(view.get("participants"))).containsExactly("u-1", "u-2");
      assertThat(view.get("text").asText()).isEqualTo("自然语言过程");
      assertThat(view.get("tick").asLong()).as("tick = 读数状态的 tick（= 7）").isEqualTo(7L);
      assertThat(view.get("stateKey").asText()).isEqualTo("combat");
      assertThat(view.get("address").asText()).isEqualTo("army:combat.c-1");
      assertThat(view.get("initialStageId").asText())
          .as("初始阶段由 RecordCombatHandler 合成（id=start）")
          .isEqualTo(RecordCombatHandler.INITIAL_STAGE_ID);
      assertThat(commandCounts(view.get("commands")))
          .as("批内顺序：一条 RecordCombat → 每个参与单位一条 SetStateDescription")
          .containsExactly("army.RecordCombat=1", "unit.SetStateDescription=2");

      assertThat(fx.head()).as("preview 不动 head").isEqualTo(headBefore);
      assertThat(fx.core.revisions(main())).as("preview 不落 revision").hasSize(revisionsBefore);
      assertThat(fx.handlerCalls).as("preview 一个 handler 都没进").isEmpty();

      SimulationState after = fx.stateAt(headBefore);
      assertThat(fx.army(after).combats()).isEmpty();
      assertThat(fx.unit(after, U1).stateDescriptions()).isEmpty();
      assertThat(fx.unit(after, U2).stateDescriptions()).isEmpty();
    }
  }

  // ── apply：一条 revision + 批内真信封序 + 链接 ─────────────────────────────────────────

  @Test
  void applyWritesOneRevisionInRecordThenLinkOrder() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("apply"))) {
      ToolResult result =
          fx.call(startArgs("c-1", "轰城", List.of(U1.value(), U2.value()), 1L, Boolean.FALSE));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("submitted").asBoolean()).isTrue();
      JsonNode submission = view.get("submission");
      assertThat(submission.get("result").asText()).isEqualTo("committed");
      assertThat(submission.get("ref").get("revision").asLong()).isEqualTo(2L);

      assertThat(fx.head()).as("一批 = 一张新 revision").isEqualTo(2L);
      assertThat(fx.core.revisions(main())).hasSize(2);
      assertThat(fx.handlerCalls)
          .as("真执行序：RecordCombat 先、两条 SetStateDescription 后（按 participants 序）")
          .containsExactly(
              RecordCombatHandler.TYPE,
              SetStateDescriptionHandler.TYPE,
              SetStateDescriptionHandler.TYPE);

      SimulationState after = fx.stateAt(2L);
      CombatRecord record = fx.army(after).combats().get(new CombatRecordId("c-1"));
      assertThat(record).isNotNull();
      assertThat(record.kind()).as("kind = 自定义交战状态自由文本（轰城不另开命令）").isEqualTo("轰城");
      assertThat(record.tick()).isEqualTo(7L);
      assertThat(record.hex()).isEqualTo(H11);
      assertThat(record.participants()).containsExactly(U1, U2);
      assertThat(record.text()).isEqualTo("自然语言过程");
      assertThat(record.stages()).hasSize(1);
      assertThat(record.stages().get(0).id().value())
          .isEqualTo(RecordCombatHandler.INITIAL_STAGE_ID);
      assertThat(record.stages().get(0).name()).isEqualTo(RecordCombatHandler.INITIAL_STAGE_NAME);
      assertThat(record.stages().get(0).participants()).containsExactly(U1, U2);
      assertThat(record.stages().get(0).text()).isEqualTo("自然语言过程");
      assertThat(record.stages().get(0).outcomes()).isEmpty();
      assertThat(record.stages().get(0).resolved()).isFalse();

      assertThat(fx.unit(after, U1).stateDescriptions())
          .as("参与单位的状态链接：state=combat → canonical 地址 army:combat.c-1")
          .containsExactly(Map.entry("combat", "army:combat.c-1"));
      assertThat(fx.unit(after, U2).stateDescriptions())
          .containsExactly(Map.entry("combat", "army:combat.c-1"));

      // 旧 revision 里没有记录（历史可回放：批只落在新坐标）。
      assertThat(fx.army(fx.stateAt(1L)).combats()).isEmpty();
    }
  }

  @Test
  void batchOrderIsReadFromTheRealEnvelopeSequenceWhenAGuardRejectsTheLinks() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("order"))) {
      fx.rejectTypes.add(SetStateDescriptionHandler.TYPE);

      ToolResult result =
          fx.call(startArgs("c-1", "野战", List.of(U1.value(), U2.value()), 1L, Boolean.FALSE));

      assertThat(result.success()).isFalse();
      assertThat(result.code()).as("整批原子拒").isEqualTo("REJECTED");
      JsonNode submission = JSON.readTree(result.message()).get("submission");
      assertThat(submission.get("result").asText()).isEqualTo("rejected");
      List<String> types = new ArrayList<>();
      submission.get("commands").forEach(row -> types.add(row.get("type").asText()));
      assertThat(types)
          .as("真信封序（同一份 BatchResult.outcomes 的逐条类型）")
          .containsExactly(
              RecordCombatHandler.TYPE,
              SetStateDescriptionHandler.TYPE,
              SetStateDescriptionHandler.TYPE);
      assertThat(fx.head()).as("整批拒 ⇒ 零 revision").isEqualTo(1L);
      assertThat(fx.army(fx.stateAt(1L)).combats()).isEmpty();
    }
  }

  // ── 失败具名（零写入）──────────────────────────────────────────────────────────────

  @Test
  void duplicateCombatIdIsRejectedBeforeAnySubmit() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("dup"))) {
      assertThat(fx.call(startArgs("c-1", "野战", List.of(U1.value()), 1L, Boolean.FALSE)).success())
          .isTrue();
      long headAfterFirst = fx.head();

      ToolResult again =
          fx.call(startArgs("c-1", "野战", List.of(U1.value()), headAfterFirst, Boolean.FALSE));
      assertThat(again.success()).isFalse();
      assertThat(again.code()).isEqualTo("BAD_REQUEST");
      assertThat(again.message()).contains("交战记录 id 已存在").contains("c-1");
      assertThat(fx.head()).as("具名拒 ⇒ 零写入").isEqualTo(headAfterFirst);
    }
  }

  @Test
  void missingParticipantIsRejectedBeforeAnySubmit() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("missing"))) {
      ToolResult result = fx.call(startArgs("c-1", "野战", List.of("u-404"), null, null));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).contains("参与单位不存在: u-404");
      assertThat(fx.head()).isEqualTo(1L);
      assertThat(fx.handlerCalls).isEmpty();
    }
  }

  // ── GM-only 工具面 ─────────────────────────────────────────────────────────────────

  @Test
  void startCombatIsRegisteredInTheGmBucketOnly() {
    try (Shell shell =
        Shell.start(ShellConfig.defaults(tempDir.resolve("surface")).withPorts(0, 0, 0))) {
      List<String> gm =
          shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList();
      List<String> decision =
          shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
              .map(AgentTool::name)
              .toList();
      assertThat(gm)
          .as("D4/D3a 组合工具只在 GM 桶")
          .contains(StartCombatTool.NAME, ResolveCombatTool.NAME, FormatUnitTool.NAME);
      assertThat(decision)
          .as("决策人桶没有这三条组合写")
          .doesNotContain(StartCombatTool.NAME, ResolveCombatTool.NAME, FormatUnitTool.NAME);
    } catch (Exception e) {
      throw new AssertionError("起真壳失败", e);
    }
  }

  // ── 参数 / 装置 ─────────────────────────────────────────────────────────────────────

  private static Map<String, Object> startArgs(
      String combatId,
      String kind,
      List<String> participants,
      Long expectedRevision,
      Boolean preview) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("combatId", combatId);
    args.put("kind", kind);
    args.put("q", 1L);
    args.put("r", 1L);
    args.put("participants", participants);
    args.put("text", "自然语言过程");
    if (expectedRevision != null) {
      args.put("expectedRevision", expectedRevision);
    }
    if (preview != null) {
      args.put("preview", preview);
    }
    return args;
  }

  /** 真 {@code CoreSimos} + 真 handler + 两条测试专用记录/守卫接缝（生产零改动）。 */
  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final List<String> handlerCalls = new ArrayList<>();
    final Set<String> rejectTypes = new java.util.LinkedHashSet<>();
    private final StartCombatTool tool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.tool = new StartCombatTool(core, query, "agent:t2b-start");
      core.register(recording(new RecordCombatHandler()));
      core.register(recording(new SetStateDescriptionHandler()));
      core.register(
          new MutationGuard() {
            @Override
            public String name() {
              return "t2b-reject-by-type";
            }

            @Override
            public Optional<String> rejection(
                SimulationState state, String commandType, String payloadJson) {
              return rejectTypes.contains(commandType)
                  ? Optional.of("T2b 测试守卫：故意拒 " + commandType)
                  : Optional.empty();
            }
          });
    }

    static Fixture open(Path storeDir) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      core.register(new MapCodec())
          .register(new SocialCodec())
          .register(new UnitCodec())
          .register(new SdCodec())
          .register(new ArmyCodec())
          .register(new EconomyCodec())
          .register(new ActorCodec());
      Fixture fixture = new Fixture(core);
      core.bootstrapGenesis(genesis());
      return fixture;
    }

    ToolResult call(Map<String, Object> args) {
      AgentTool agentTool = tool;
      return agentTool.execute(gmContext(agentTool, args));
    }

    long head() {
      return core.head(main()).orElseThrow().value();
    }

    SimulationState stateAt(long revision) {
      return core.replay(new StateRef(main(), new RevisionId(revision)));
    }

    ArmyData army(SimulationState state) {
      return ((ArmySnapshot) state.module("army").orElseThrow()).data();
    }

    Unit unit(SimulationState state, UnitId id) {
      return ((UnitSnapshot) state.module("unit").orElseThrow()).state().units().get(id);
    }

    private CommandHandler recording(CommandHandler delegate) {
      return new CommandHandler() {
        @Override
        public String type() {
          return delegate.type();
        }

        @Override
        public HandlerOutcome handle(SimulationState state, String payloadJson) {
          handlerCalls.add(delegate.type());
          return delegate.handle(state, payloadJson);
        }
      };
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(main(), new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T7),
        Map.of(
            "map", new MapSnapshot(ref, T7, GameMap.empty()),
            "social", new SocialSnapshot(ref, T7, SocialData.empty()),
            "unit", new UnitSnapshot(ref, T7, unitState()),
            "sd", new SdSnapshot(ref, T7, SdState.empty()),
            "army", new ArmySnapshot(ref, T7, ArmyData.empty()),
            "economy", new EconomySnapshot(ref, T7, EconomyData.empty()),
            "actor", new ActorSnapshot(ref, T7, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }

  private static UnitState unitState() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(U1, unit(U1, "第一连"));
    units.put(U2, unit(U2, "第二连"));
    return new UnitState(units);
  }

  private static Unit unit(UnitId id, String name) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(T7, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T7, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T7, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T7, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  private static ToolContext gmContext(AgentTool tool, Map<String, Object> args) {
    ResourceScopeMap scopes =
        ResourceScopeMap.of(
            Map.of(
                ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SD_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.ARMY_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.ACTOR_NAMESPACE, ResourceScope.unlimited()));
    AgentPermissionSet permissions =
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            true,
            true,
            false,
            scopes);
    return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args)
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static List<String> values(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }

  private static List<String> commandCounts(JsonNode commands) {
    List<String> out = new ArrayList<>();
    commands.forEach(row -> out.add(row.get("type").asText() + "=" + row.get("count").asInt()));
    return out;
  }
}
