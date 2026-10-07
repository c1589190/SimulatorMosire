package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.gov.pay}（D5 / D-004 / R5）的决策人面判据：
 *
 * <ul>
 *   <li>非 GOV 归属的决策人 ⇒ 具名 {@code REJECTED}（"只有 GOV 归属的决策人可付款"），零 revision；
 *   <li>收款 {@code toGovId} 不存在 / 没有 {@code GovernmentFormation} / 没有当刻有效位置 ⇒ 具名 {@code
 *       BAD_REQUEST}，零 revision；
 *   <li>preview（缺省 true）只读：双方落点与可支配量，不写；
 *   <li><b>审批链</b>：真壳 + 真决策人 authorizer ⇒ DM 调用落待批（工具名可读）、head 不动；GM 点头后同一批提交成功并真的转账；
 *   <li>付款人身份派生：载荷没有 from 字段（schema 不含），<b>由调用者 sd 决策人 → GOV 归属解析</b>。
 * </ul>
 *
 * <p>★ 本类用**手搭创世 checkpoint + 真 Shell**（{@code UnitDebtToolsTest} 同形）：DM 上下文经 {@code
 * shell.toolAuthorizer()} 执行，审批链是真的 AgentLib 装配；负例直接调 {@code tool.execute}（身份仍是 DM）以区分"身份/世界拒"与
 * "审批待批"两条链。
 */
class GovPayToolD5Test {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String TEST_INITIATOR = "agent:t2b-govpay";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);

  private static final String GOV_A = "gov-a";
  private static final String GOV_B = "gov-b";
  private static final String GOV_NO_POS = "gov-no-pos";
  private static final String PLAIN_UNIT = "u-plain";

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private final List<Shell> shells = new ArrayList<>();
  private int worldSeq;

  @AfterEach
  void stopShells() {
    for (Shell shell : shells) {
      shell.close();
    }
  }

  // ── 身份 / 收款校验（直调 execute，不经审批）────────────────────────────────────────────

  @Test
  void nonGovAffiliationIsRejectedByName() throws Exception {
    World world = startWorld("non-gov");
    ToolResult result =
        world.tool.execute(context(world.tool, "dm-nation", args(GOV_B, 1L, 0L, 0L, null)));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("REJECTED");
    assertThat(result.message()).contains("只有 GOV 归属的决策人可付款").contains("归属").contains("Nation");
    assertThat(world.head()).as("身份拒 ⇒ 零 revision").isEqualTo(1L);
  }

  @Test
  void unknownOrNonGovRecipientIsRejectedByName() throws Exception {
    World world = startWorld("bad-recipient");

    ToolResult missing =
        world.tool.execute(context(world.tool, "dm-gov", args("gov-missing", 1L, 0L, 0L, null)));
    assertThat(missing.success()).isFalse();
    assertThat(missing.code()).isEqualTo("BAD_REQUEST");
    assertThat(missing.message()).contains("指定的单位不存在").contains("gov-missing");

    ToolResult plain =
        world.tool.execute(context(world.tool, "dm-gov", args(PLAIN_UNIT, 1L, 0L, 0L, null)));
    assertThat(plain.success()).isFalse();
    assertThat(plain.code()).isEqualTo("BAD_REQUEST");
    assertThat(plain.message()).contains("没有 GovernmentFormation").contains(PLAIN_UNIT);

    ToolResult noPosition =
        world.tool.execute(context(world.tool, "dm-gov", args(GOV_NO_POS, 1L, 0L, 0L, null)));
    assertThat(noPosition.success()).isFalse();
    assertThat(noPosition.code()).isEqualTo("BAD_REQUEST");
    assertThat(noPosition.message()).contains("没有当刻有效位置").contains(GOV_NO_POS);

    assertThat(world.head()).as("三条世界拒 ⇒ 零 revision").isEqualTo(1L);
  }

  @Test
  void previewShowsBothTreasuriesAndAvailableStockWithoutWriting() throws Exception {
    World world = startWorld("preview");
    ToolResult result =
        world.tool.execute(context(world.tool, "dm-gov", args(GOV_B, 100L, 7L, 5L, null)));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isTrue();
    assertThat(view.get("submitted").asBoolean()).isFalse();
    assertThat(view.get("payerDerivedFromIdentity").asBoolean()).as("付款人由身份派生，载荷不指定").isTrue();
    assertThat(view.get("from").get("unitId").asText()).isEqualTo(GOV_A);
    assertThat(view.get("from").get("q").asInt()).isEqualTo(1);
    assertThat(view.get("from").get("r").asInt()).isEqualTo(1);
    assertThat(view.get("from").get("available").get("grain").asLong()).isEqualTo(1000L);
    assertThat(view.get("from").get("available").get("cloth").asLong()).isEqualTo(50L);
    assertThat(view.get("from").get("available").get("money").asLong()).isEqualTo(500L);
    assertThat(view.get("to").get("unitId").asText()).isEqualTo(GOV_B);
    assertThat(view.get("to").get("q").asInt()).isEqualTo(1);
    assertThat(view.get("to").get("r").asInt()).isEqualTo(2);
    assertThat(view.get("requested").get("grain").asLong()).isEqualTo(100L);
    assertThat(view.get("commandsPreview").get(0).get("type").asText())
        .isEqualTo("actor.RemitGovTreasury");
    assertThat(world.head()).as("preview 零写入").isEqualTo(1L);
  }

  // ── 审批链：DM 调用 ⇒ 待批 ⇒ GM 点头 ⇒ 提交 ────────────────────────────────────────────

  @Test
  void decisionMakerCallAsksAndOnlyCommitsAfterGmApproval() throws Exception {
    World world = startWorld("approval");
    AgentTool tool = world.tool;

    ToolContext dm = context(tool, "dm-gov", args(GOV_B, 100L, 0L, 0L, 1L));
    ToolRegistry dmTools = new ToolRegistry();
    dmTools.register(tool);
    FutureTask<ToolResult> call =
        new FutureTask<>(() -> world.shell.toolAuthorizer().execute(dmTools, tool.name(), dm));
    Thread.ofVirtual().name("t2b-govpay-dm").start(call);

    ApprovalRequest pending = awaitPending(world);
    assertThat(call.isDone()).as("审批未决之前调用必须阻塞（不是'未审即执行'）").isFalse();
    assertThat(pending.tool()).as("待批项就是支付工具").isEqualTo(GovPayTool.NAME);
    assertThat(pending.classKey()).isEqualTo(GovPayTool.NAME);
    assertThat(pending.kind()).as("敏感闸的 Ask 语义（不是无脑过）").isEqualTo(AskKind.SENSITIVE);
    assertThat(pending.summary()).contains(GOV_B).contains("grain=100");
    assertThat(world.head()).as("待批期间 head 不动").isEqualTo(1L);

    assertThat(
            world
                .shell
                .pendingApprovals()
                .decide(pending.id(), ApprovalDecision.APPROVE_ONCE, "test:t2b"))
        .isTrue();
    ToolResult result = call.get(10, TimeUnit.SECONDS);
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode submission = JSON.readTree(result.message()).get("submission");
    assertThat(submission.get("result").asText()).isEqualTo("committed");
    assertThat(world.head()).as("放行后才推 revision").isEqualTo(2L);

    ActorData actors = world.actor(world.stateAt(2L));
    HouseholdInventory from =
        actors.accounts().get(new HouseholdAccountKey(GovernmentHouseholds.of(GOV_A)));
    HouseholdInventory to =
        actors.accounts().get(new HouseholdAccountKey(GovernmentHouseholds.of(GOV_B)));
    assertThat(from.balances().getOrDefault(GRAIN, 0L)).as("付款国库 grain 1000 − 100").isEqualTo(900L);
    assertThat(to.balances().getOrDefault(GRAIN, 0L)).as("收款国库 grain 0 + 100").isEqualTo(100L);
    assertThat(world.shell.pendingApprovals().pending()).as("决议后不再待批").isEmpty();
  }

  // ── 工具 / 世界夹具 ─────────────────────────────────────────────────────────────────

  private record World(Shell shell, GovPayTool tool, Path storeDir) {
    long head() {
      return shell.coreSimos().head(new BranchId("main")).orElseThrow().value();
    }

    SimulationState stateAt(long revision) {
      return shell.coreSimos().replay(new StateRef(new BranchId("main"), new RevisionId(revision)));
    }

    ActorData actor(SimulationState state) {
      return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
    }
  }

  private World startWorld(String label) {
    Path storeDir = tempDir.resolve(label + "-" + worldSeq++);
    seedGenesis(storeDir);
    ShellConfig base = ShellConfig.defaults(storeDir).withPorts(0, 0, 0);
    Shell shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                TEST_INITIATOR,
                base.mapId(),
                base.bindAddress()));
    shells.add(shell);
    // 决策人桶不在 shell.toolRegistry()（那是 GM 源）里 ⇒ 按生产装配新造一条同参工具实例。
    GovPayTool tool = new GovPayTool(shell.coreSimos(), shell.queryService(), TEST_INITIATOR);
    return new World(shell, tool, storeDir);
  }

  /** 真 Shell 下等 DM 调用进审批：轮询登记表；若调用在登记前就结束（= 未审即执行）⇒ 断言失败。 */
  private static ApprovalRequest awaitPending(World world) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      List<ApprovalRequest> pending = world.shell.pendingApprovals().pending();
      if (!pending.isEmpty()) {
        return pending.get(0);
      }
      Thread.sleep(10);
    }
    throw new AssertionError("DM 调用既未进审批、也未在 10s 内结束——审批链装配异常");
  }

  /** DM 调用上下文：身份 = decision-maker:<id>；权限 = tool + sensitive + actor 域（其余按世界面拒）。 */
  private static ToolContext context(
      AgentTool tool, String decisionMakerId, Map<String, Object> args) {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow(tool.name())
            .sensitiveAllowed(true)
            .resourceScopes(
                ResourceScopeMap.of(ToolSupport.ACTOR_NAMESPACE, ResourceScope.unlimited()))
            .build();
    AgentIdentity identity =
        AgentIdentity.subagent(
            DecisionCallerFactory.INSTANCE_ID_PREFIX + decisionMakerId,
            CommandMode.LIMITED,
            DecisionCallerFactory.GOAL,
            1);
    return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args, identity)
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  private static Map<String, Object> args(
      String toGovId, Long grain, Long cloth, Long money, Long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("toGovId", toGovId);
    if (grain != null) {
      args.put("grain", grain);
    }
    if (cloth != null) {
      args.put("cloth", cloth);
    }
    if (money != null) {
      args.put("money", money);
    }
    if (expectedRevision != null) {
      args.put("preview", Boolean.FALSE);
      args.put("expectedRevision", expectedRevision);
    }
    return args;
  }

  private void seedGenesis(Path storeDir) {
    try {
      java.nio.file.Files.createDirectories(storeDir);
    } catch (IOException e) {
      throw new AssertionError("创建测试 store 目录失败: " + storeDir, e);
    }
    try (SqliteStore seedStore = SqliteStore.open(storeDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  TEST_INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref(1L), T7),
            Map.of(
                "map", new MapSnapshot(ref(1L), T7, ScopeFixtures.mapOf()),
                "social", new SocialSnapshot(ref(1L), T7, SocialData.empty()),
                "unit", new UnitSnapshot(ref(1L), T7, units()),
                "sd", new SdSnapshot(ref(1L), T7, sdState()),
                "economy", new EconomySnapshot(ref(1L), T7, EconomyData.empty()),
                "actor", new ActorSnapshot(ref(1L), T7, actors())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir).write(ref(1L), CheckpointEncoder.encode(genesis, GENESIS_CODECS));
  }

  private static final List<ModuleCodec> GENESIS_CODECS =
      List.of(
          new MapCodec(),
          new SocialCodec(),
          new UnitCodec(),
          new SdCodec(),
          new EconomyCodec(),
          new ActorCodec());

  private static UnitState units() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(
        new UnitId(GOV_A),
        unit(
            GOV_A,
            H11,
            Optional.of(govFormation(GOV_A, GovernmentLevel.CENTRAL, Optional.empty()))));
    units.put(
        new UnitId(GOV_B),
        unit(
            GOV_B,
            H12,
            Optional.of(
                govFormation(GOV_B, GovernmentLevel.PROVINCE, Optional.of(new UnitId(GOV_A))))));
    units.put(
        new UnitId(GOV_NO_POS),
        unit(
            GOV_NO_POS,
            null,
            Optional.of(
                govFormation(
                    GOV_NO_POS, GovernmentLevel.PROVINCE, Optional.of(new UnitId(GOV_A))))));
    units.put(new UnitId(PLAIN_UNIT), unit(PLAIN_UNIT, H11, Optional.empty()));
    return new UnitState(units);
  }

  private static GovernmentFormation govFormation(
      String unitId, GovernmentLevel level, Optional<UnitId> superiorGov) {
    // ★ P2-C §13.7：GOV 单位必须恰含自己的政府家户 hh-gov-<unitId>（UnitState 构造期强制）。
    HouseholdId governmentHousehold = GovernmentHouseholds.of(unitId);
    return new GovernmentFormation(
        Map.of(),
        Map.of(
            governmentHousehold,
            new GovernmentPostOfHousehold(governmentHousehold, StaffRole.SCRIBE, level, true)),
        OfficePolicy.defaults(),
        superiorGov,
        level,
        Map.of());
  }

  private static Unit unit(
      String id, HexCoord at, Optional<io.mosire.simos.unit.UnitModule> module) {
    HouseholdId governmentHousehold = GovernmentHouseholds.of(id);
    List<HouseholdId> households =
        module
            .filter(GovernmentFormation.class::isInstance)
            .map(ignored -> List.of(governmentHousehold))
            .orElseGet(List::of);
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T7, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T7, Optional.ofNullable(at))), List.of(), null),
        List.of(new CompositionEntry("步枪", 5)),
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
        module,
        Map.of(),
        households);
  }

  private static SdState sdState() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        new DecisionMakerId("dm-gov"), maker("dm-gov", new Affiliation.Gov(new UnitId(GOV_A))));
    makers.put(
        new DecisionMakerId("dm-nation"),
        maker("dm-nation", new Affiliation.Nation(new NationId("FRA"))));
    makers.put(
        new DecisionMakerId("dm-army"),
        maker("dm-army", new Affiliation.Army(new ArmyId("army-1"))));
    return SdState.empty().withDecisionMakers(makers);
  }

  private static DecisionMaker maker(String id, Affiliation affiliation) {
    return new DecisionMaker(
        new DecisionMakerId(id), affiliation, Set.of(), AccessLimit.empty(), 1);
  }

  private static ActorData actors() {
    HouseholdInventory account =
        new HouseholdInventory(
            new HouseholdAccountKey(GovernmentHouseholds.of(GOV_A)),
            Map.of(GRAIN, 1000L, new CommodityId("cloth"), 50L),
            Map.of(SILVER, 500L),
            Map.of(),
            Map.of());
    return ActorData.empty().withInventory(account);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(long revision) {
    return new StateRef(main(), new RevisionId(revision));
  }
}
