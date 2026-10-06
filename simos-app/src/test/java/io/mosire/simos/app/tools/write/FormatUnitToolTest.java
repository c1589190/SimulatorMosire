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
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
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
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.spi.PutInfoHandler;
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
import io.mosire.simos.unit.spi.SetCompositionHandler;
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
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.army.formatUnit}（阶段 D3a / D-010 + R2 最小环）的真命令总线用例：
 *
 * <ul>
 *   <li>无 seed ⇒ 由 {@code unitId + 基态 revision + tick + 格式表} 做 FNV-1a 派生（测试侧独立复算 FNV 与 {@link
 *       Random} 抽取序，不拿被测实现当期望值）；同状态同参数两次 ⇒ 同 seed 同目标表；
 *   <li>显式 seed ⇒ 同 seed 同结果（独立 {@link Random} 预言机逐值）；preview / apply 共用同一份推导；
 *   <li>apply 一批 = 一条 revision，批内顺序 {@code unit.SetComposition → sd.PutInfo}（真 handler 记录器 +
 *       守卫逼红读真信封序）；{@code sd.PutInfo} 同批留下 seed / 是否显式 / 前后两张表 / reason；
 *   <li>preview 零写入。
 * </ul>
 */
class FormatUnitToolTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");
  private static final long MAX = Long.MAX_VALUE;

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── 预览：派生 seed 可复现 + 零写入 ───────────────────────────────────────────────────

  @Test
  void previewDerivesTheSeedDeterministicallyAndWritesNothing() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("preview"))) {
      ToolResult first = fx.call(formatArgs("u-1", 400L, null, null, null));
      ToolResult second = fx.call(formatArgs("u-1", 400L, null, null, null));

      assertThat(first.success()).as(first.message()).isTrue();
      assertThat(second.success()).as(second.message()).isTrue();
      JsonNode a = JSON.readTree(first.message());
      JsonNode b = JSON.readTree(second.message());

      long expectedSeed = fnv1a64("u-1|1|7|M[]E[步枪:50:400:0:" + MAX + ";]");
      assertThat(a.get("seedProvided").asBoolean()).isFalse();
      assertThat(a.get("seed").asLong())
          .as("无 seed ⇒ FNV-1a(unitId|baseRevision|tick|M[...]E[...])")
          .isEqualTo(expectedSeed);
      assertThat(b.get("seed").asLong()).as("同状态同参数 ⇒ 同 seed").isEqualTo(expectedSeed);
      assertThat(amounts(a.get("equipment"))).isEqualTo(expectedFormats(expectedSeed, 400L));
      assertThat(amounts(b.get("equipment"))).isEqualTo(amounts(a.get("equipment")));
      assertThat(values(a.get("commands")))
          .containsExactly(SetCompositionHandler.TYPE, FormatUnitPlan.PUT_INFO_TYPE);

      assertThat(fx.head()).as("preview 零写入").isEqualTo(1L);
      assertThat(fx.storedRevisions()).hasSize(1);
      assertThat(fx.handlerCalls).isEmpty();
      assertThat(entries(fx.unit(fx.stateAt(1L)).equipment())).containsExactly("步枪=50");
    }
  }

  @Test
  void explicitSeedIsReproducibleAndAuditedInTheSameBatch() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("apply-explicit"))) {
      // 同 seed 的两次 preview 逐值相等（同 seed 同结果）。
      JsonNode p1 = JSON.readTree(fx.call(formatArgs("u-1", 400L, 42L, null, null)).message());
      JsonNode p2 = JSON.readTree(fx.call(formatArgs("u-1", 400L, 42L, null, null)).message());
      assertThat(amounts(p2.get("equipment"))).isEqualTo(amounts(p1.get("equipment")));

      ToolResult result = fx.call(formatArgs("u-1", 400L, 42L, 1L, Boolean.FALSE));
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("submission").get("result").asText()).isEqualTo("committed");
      assertThat(fx.head()).as("一批 = 一条 revision").isEqualTo(2L);
      assertThat(fx.storedRevisions()).hasSize(2);
      assertThat(fx.handlerCalls)
          .as("批内顺序：unit.SetComposition → sd.PutInfo")
          .containsExactly(SetCompositionHandler.TYPE, FormatUnitPlan.PUT_INFO_TYPE);
      assertThat(fx.handlerPayloads.get(1))
          .as("PutInfo 的载荷 JSON 里含 key/value")
          .contains("\"key\":\"formatUnit\"");

      List<String> expected = expectedFormats(42L, 400L);
      assertThat(entries(fx.unit(fx.stateAt(2L)).equipment())).containsExactlyElementsOf(expected);

      // ★ 同批 sd.PutInfo 审计：seed / 是否显式 / 前后两张表 / tick / reason。
      SdInfoEntry entry = infoEntry(fx.stateAt(2L));
      assertThat(entry.key()).isEqualTo("formatUnit");
      assertThat(entry.tick()).isEqualTo(7L);
      JsonNode value = JSON.readTree(entry.value().toString());
      assertThat(value.get("unitId").asText()).isEqualTo("u-1");
      assertThat(value.get("seed").asLong()).isEqualTo(42L);
      assertThat(value.get("seedProvided").asBoolean()).isTrue();
      assertThat(value.get("tick").asLong()).isEqualTo(7L);
      assertThat(deltas(value.get("beforeEquipment"))).containsExactly("步枪=50");
      assertThat(deltas(value.get("equipment"))).isEqualTo(expected);
      assertThat(value.get("reason").asText()).isEqualTo(FormatUnitTool.DEFAULT_REASON);
      assertThat(entry.note()).isPresent();
      assertThat(entry.note().orElseThrow()).contains("seed=42");
    }
  }

  @Test
  void derivedSeedPathAuditsSeedProvidedFalseAndMatchesPreview() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("apply-derived"))) {
      JsonNode preview =
          JSON.readTree(fx.call(formatArgs("u-1", 400L, null, null, null)).message());
      long derivedSeed = preview.get("seed").asLong();

      ToolResult result = fx.call(formatArgs("u-1", 400L, null, 1L, Boolean.FALSE));
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("seed").asLong())
          .as("preview / apply 共用同一份纯推导 ⇒ 同派生 seed")
          .isEqualTo(derivedSeed);
      assertThat(view.get("seedProvided").asBoolean()).isFalse();
      assertThat(amounts(view.get("equipment"))).isEqualTo(amounts(preview.get("equipment")));

      SdInfoEntry entry = infoEntry(fx.stateAt(2L));
      JsonNode value = JSON.readTree(entry.value().toString());
      assertThat(value.get("seed").asLong()).isEqualTo(derivedSeed);
      assertThat(value.get("seedProvided").asBoolean()).isFalse();
      assertThat(deltas(value.get("equipment"))).isEqualTo(deltas(view.get("equipment")));
    }
  }

  @Test
  void applyOrderIsReadFromTheRealEnvelopeSequenceWhenAGuardRejectsPutInfo() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("order"))) {
      fx.rejectTypes.add(FormatUnitPlan.PUT_INFO_TYPE);
      ToolResult result = fx.call(formatArgs("u-1", 400L, 7L, 1L, Boolean.FALSE));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      JsonNode commands = JSON.readTree(result.message()).get("submission").get("commands");
      List<String> types = new ArrayList<>();
      commands.forEach(row -> types.add(row.get("type").asText()));
      assertThat(types)
          .as("真信封序：SetComposition 先、PutInfo 后")
          .containsExactly(SetCompositionHandler.TYPE, FormatUnitPlan.PUT_INFO_TYPE);
      assertThat(fx.head()).as("整批拒 ⇒ 零 revision").isEqualTo(1L);
      assertThat(entries(fx.unit(fx.stateAt(1L)).equipment())).containsExactly("步枪=50");
    }
  }

  @Test
  void missingUnitIsRejectedBeforeAnyWrite() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("missing"))) {
      ToolResult result = fx.call(formatArgs("u-404", 400L, 42L, null, null));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).contains("单位不存在: u-404");
      assertThat(fx.head()).isEqualTo(1L);
      assertThat(fx.handlerCalls).isEmpty();
    }
  }

  // ── 独立预言机 ──────────────────────────────────────────────────────────────────────

  /** 与生产同口径、但独立实现的 FNV-1a 64。 */
  private static long fnv1a64(String text) {
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < text.length(); i++) {
      hash ^= text.charAt(i);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  /**
   * 独立 {@link Random} 预言机：只按 equipment 表序（manpower 已退役）；幅度为 0 的条目不消费随机数；在 {@code [-amplitude,
   * +amplitude]} 均匀取整后按 [0, MAX] 截断。返回 ["步枪=<n>"]。
   */
  private static List<String> expectedFormats(long seed, long equipmentJitter) {
    Random random = new Random(seed);
    long rifle = jittered(50L, equipmentJitter, random);
    return List.of("步枪=" + rifle);
  }

  private static long jittered(long base, long jitterPerMille, Random random) {
    long amplitude = base * jitterPerMille / 1000L;
    if (amplitude == 0L) {
      return base;
    }
    long delta = random.nextLong(2L * amplitude + 1L) - amplitude;
    return Math.max(0L, base + delta);
  }

  // ── 参数 / 装置 ─────────────────────────────────────────────────────────────────────

  private static Map<String, Object> formatArgs(
      String unitId,
      long equipmentJitterPerMille,
      Long seed,
      Long expectedRevision,
      Boolean preview) {
    Map<String, Object> equipment = new LinkedHashMap<>();
    equipment.put("type", "步枪");
    equipment.put("baseAmount", 50L);
    equipment.put("jitterPerMille", equipmentJitterPerMille);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", unitId);
    // ★ S3b：manpower 已退役；参数仍必填，但只接受空数组（带非空 ⇒ 具名拒）。
    args.put("manpower", List.of());
    args.put("equipment", List.of(equipment));
    if (seed != null) {
      args.put("seed", seed);
    }
    if (expectedRevision != null) {
      args.put("expectedRevision", expectedRevision);
    }
    if (preview != null) {
      args.put("preview", preview);
    }
    return args;
  }

  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final List<String> handlerCalls = new ArrayList<>();
    final List<String> handlerPayloads = new ArrayList<>();
    final Set<String> rejectTypes = new java.util.LinkedHashSet<>();
    private final FormatUnitTool tool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.tool = new FormatUnitTool(core, query, "agent:t2b-format");
      core.register(recording(new SetCompositionHandler()));
      core.register(recording(new PutInfoHandler()));
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

    List<?> storedRevisions() {
      return core.revisions(main());
    }

    SimulationState stateAt(long revision) {
      return core.replay(new StateRef(main(), new RevisionId(revision)));
    }

    Unit unit(SimulationState state) {
      return ((UnitSnapshot) state.module("unit").orElseThrow()).state().units().get(U1);
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
          handlerPayloads.add(payloadJson);
          return delegate.handle(state, payloadJson);
        }
      };
    }

    @Override
    public void close() {
      core.close();
    }
  }

  /** 从 replay 态读同批 PutInfo 的条目（地址 = 单位 canonical、key = formatUnit）。 */
  private static SdInfoEntry infoEntry(SimulationState state) {
    SdState sd = ((SdSnapshot) state.module("sd").orElseThrow()).state();
    List<SdInfoEntry> entries = sd.info().get(RaiseUnitPlan.unitAddress("u-1"));
    assertThat(entries).as("sd.PutInfo 必须落在单位 canonical 地址上").isNotNull().hasSize(1);
    return entries.get(0);
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
    Unit unit =
        new Unit(
            U1,
            "第一连",
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
    return new UnitState(Map.of(U1, unit));
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

  private static List<String> amounts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.get("type").asText() + "=" + node.get("amount").asLong()));
    return out;
  }

  /** 与 {@link #amounts} 同形，但用于审计 value 里的前后表（键为 type/amount）。 */
  private static List<String> deltas(JsonNode array) {
    return amounts(array);
  }

  private static List<String> entries(List<CompositionEntry> entries) {
    List<String> out = new ArrayList<>();
    for (CompositionEntry entry : entries) {
      out.add(entry.type() + "=" + entry.amount());
    }
    return out;
  }

  private static List<String> values(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }
}
