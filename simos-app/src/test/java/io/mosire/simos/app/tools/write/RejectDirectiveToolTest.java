package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
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
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **GM 打回（{@code sd.RejectDirective}）的真实验收面**（2026-09-23 用户裁定的"决策人一般流程"第 2 件）。
 *
 * <p>★ **全程走真装配**（真 {@code Shell} + 真 store + 真注册表 + 真 handler + 真会话库）：夹具世界 {@code Map1}（两格）——
 * 一个决策人 {@code dm-shanghai}（国家 FRA）+ 一条单位。令经**真命令面**提交，打回经**真工具面**（GM 上下文）执行。
 *
 * <p>★★ **四条判据各自带反方向**：
 *
 * <ol>
 *   <li><b>打回 = 状态翻转 + 会话消息 + 审计留痕，且世界观无变更</b>（一条 revision 里只有 {@code sd} 命名空间）；
 *   <li><b>打回后同一 tick 能重写</b>（拆掉"必须推 tick"的墙）；
 *   <li><b>终态不得再打回</b>（第 2 次打回被转移守卫拒、零 revision、会话**不**多一条消息）——"不静默成功"；
 *   <li><b>乐观并发与坏输入</b>（过期 {@code expectedRevision} ⇒ CONFLICT；空白 {@code reason} ⇒ BAD_REQUEST）。
 * </ol>
 */
class RejectDirectiveToolTest {

  private static final String MAP_ID = "Map1";
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "player:gm-test";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");

  /** 世界 tick（创世时刻）——令都记在它上面。 */
  private static final long WORLD_TICK = 7L;

  private static final DecisionMakerId DM = new DecisionMakerId("dm-shanghai");
  private static final String CONVERSATION_ID = "decision-maker:dm-shanghai";
  private static final String REASON = "【打回】目标与当前补给线不匹配，重写";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
    shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                INITIATOR,
                base.mapId(),
                base.bindAddress()));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── 判据一：打回 = 翻转 + 会话消息 + 审计，世界观无变更 ────────────────────────────────

  /**
   * ★★ **打回的四件事一次钉死**（缺任何一件本用例就红）：
   *
   * <ul>
   *   <li>该令 {@code ISSUED → CANCELLED}（真落盘，重放读回）；
   *   <li>理由**真的进了该决策人的会话**（读真 {@code conversations.db}，最后一条 user 消息 == 传进去的 reason 原文）；
   *   <li>审计条目在 {@code sd:rejection.<directiveId>}：谁的令 / 第几版 / 理由文本 / 落在哪条 revision；
   *   <li>★ **世界观无变更**：那一条 revision 的变更集里**只有 {@code sd}** 命名空间，且单位名一字未动。
   * </ul>
   *
   * <p>★ 判别力：把"只翻状态不落会话"或"只落会话不翻状态"的实现拿来，本用例分别在会话断言与状态断言上红； 把"打回也执行了令里的命令"的实现拿来， {@code
   * namespacesOfRevision} 上红。
   */
  @Test
  void rejectingCancelsTheDirectiveSaysItInTheConversationAndTracesOneRevision() throws Exception {
    issueDirective("d-shanghai-0001");
    long beforeReject = head();

    ToolResult result = reject("d-shanghai-0001", REASON, beforeReject);

    JsonNode body = JSON.readTree(result.message());
    assertThat(result.success()).as("打回必须成（实际：%s）", result.message()).isTrue();
    assertThat(body.get("result").asText()).isEqualTo("committed");
    assertThat(body.get("directiveId").asText()).isEqualTo("d-shanghai-0001");
    assertThat(body.get("decisionMakerId").asText()).isEqualTo(DM.value());
    assertThat(body.get("tick").asLong()).as("第几版：同 (决策人, tick) 的第一条").isEqualTo(WORLD_TICK);
    assertThat(body.get("version").asInt()).isEqualTo(1);
    assertThat(body.get("status").asText()).isEqualTo("CANCELLED");
    assertThat(body.get("reason").asText()).as("理由原样透传（系统不做分类/改写）").isEqualTo(REASON);

    long after = head();
    assertThat(after).as("★ 打回恰好一条 revision（状态翻转 + 审计同批）").isEqualTo(beforeReject + 1);

    // ★ 世界观无变更：那一条 revision 只碰 sd 命名空间；单位名一字未动。
    assertThat(namespacesOfRevision(after)).as("打回只改 sd 域（不执行令、不动世界观）").containsExactly("sd");
    assertThat(unitName(after, U1)).isEqualTo("第一连");

    assertThat(sdAt(after).directives().get(new DirectiveId("d-shanghai-0001")).status())
        .as("★ 该令真被标 CANCELLED（从 revision 重放读回，不是工具自报）")
        .isEqualTo(DirectiveStatus.CANCELLED);

    // ★ 审计留痕：谁的令 / 第几版 / 理由文本 / 落在哪条 revision。
    List<SdInfoEntry> entries = sdAt(after).info().get("sd:rejection.d-shanghai-0001");
    assertThat(entries).as("审计条目存在").isNotNull().hasSize(1);
    SdInfoEntry audit = entries.get(0);
    assertThat(audit.key()).isEqualTo(RejectDirectiveTool.REJECTION_KEY);
    JsonNode value = JSON.readTree((String) audit.value());
    assertThat(value.get("directiveId").asText()).isEqualTo("d-shanghai-0001");
    assertThat(value.get("decisionMakerId").asText()).isEqualTo(DM.value());
    assertThat(value.get("version").asInt()).isEqualTo(1);
    assertThat(value.get("reason").asText()).isEqualTo(REASON);
    assertThat(value.get("status").asText()).isEqualTo("CANCELLED");
    assertThat(value.get("resultRevision").asLong()).as("★ 留痕写明**落在哪条 revision**").isEqualTo(after);

    // ★ 理由真的进了会话：读真库（不是工具自报的 conversationId）。
    try (SqliteConversationStore conversations = conversationsIn()) {
      List<LlmMessage> messages = conversations.load(CONVERSATION_ID);
      assertThat(messages).as("身份消息 + 打回理由").hasSize(2);
      assertThat(messages.get(0).role())
          .as("空会话先补身份 user 消息（与 say 通道同一条实现；system 只留空格占位）")
          .isEqualTo("user");
      LlmMessage last = messages.get(messages.size() - 1);
      assertThat(last.role()).as("最后一条是 user 消息").isEqualTo("user");
      assertThat(textOf(last)).as("★ 最后那条 user 消息**逐字等于**传进去的 reason").isEqualTo(REASON);
    }
    assertThat(body.get("conversation").get("conversationId").asText()).isEqualTo(CONVERSATION_ID);
  }

  // ── 判据二：打回后同一 tick 能重写（本任务的要点：拆掉"必须推 tick"的墙）───────────────────

  /**
   * ★★ **打回 → 同一 tick 再出令 ⇒ 能出**：这是"重写"闭环的关键一步（用户原话「又没说**不可以打回重写**」）。旧实现（R4 唯一）在这里会
   * 直接拒，而正确形态是两条并存的令：被打回的保持 {@code CANCELLED}、新出的 {@code ISSUED}。
   */
  @Test
  void theSameTickCanIssueAgainAfterARejection() throws Exception {
    issueDirective("d-v1");
    assertThat(reject("d-v1", REASON, head()).success()).isTrue();
    long afterReject = head();

    CommandResult second = issueDirective("d-v2");

    assertThat(second)
        .as("★ 打回后**同一 tick** 再出令必须被接受（实际：%s）", second)
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(head()).isEqualTo(afterReject + 1);

    SdState sd = sdAt(head());
    assertThat(sd.directives().get(new DirectiveId("d-v1")).status())
        .as("★ 被打回的那一版保持 CANCELLED（重写不抹掉留痕）")
        .isEqualTo(DirectiveStatus.CANCELLED);
    assertThat(sd.directives().get(new DirectiveId("d-v2")).status())
        .as("新版生效")
        .isEqualTo(DirectiveStatus.ISSUED);
  }

  // ── 判据三：终态不得再打回（不静默成功）──────────────────────────────────────────────

  @Test
  void rejectingAnAlreadyCancelledDirectiveIsRefusedAndMovesNothing() throws Exception {
    issueDirective("d1");
    assertThat(reject("d1", REASON, head()).success()).isTrue();
    long afterFirst = head();

    ToolResult second = reject("d1", "再打回一次", afterFirst);

    assertThat(second.success()).as("★ 已终态的令不得再打回（转移守卫拒，不静默成功）").isFalse();
    assertThat(second.code()).isEqualTo("REJECTED");
    assertThat(second.message()).as("拒因可读且点名不可转移").contains("不可转移");
    assertThat(head()).as("被拒 ⇒ 零 revision").isEqualTo(afterFirst);
    try (SqliteConversationStore conversations = conversationsIn()) {
      assertThat(conversations.load(CONVERSATION_ID))
          .as("★ 打回没成 ⇒ 会话里**不得**多一条消息（不把没发生的事说成发生了）")
          .hasSize(2);
    }
  }

  @Test
  void anUnknownDirectiveIsRefusedWithZeroRevisions() {
    long before = head();

    ToolResult result = reject("d-nope", REASON, before);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("REJECTED");
    assertThat(result.message()).contains("决策不存在").contains("d-nope");
    assertThat(head()).isEqualTo(before);
  }

  // ── 判据四：乐观并发 + 坏输入 ────────────────────────────────────────────────────────

  @Test
  void aStaleExpectedRevisionIsAConflictWithZeroRevisions() throws Exception {
    issueDirective("d1");
    long head = head();

    ToolResult result = reject("d1", REASON, head - 1);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).as("★ 冲突原样上报（不蒙过 expectedRevision）").isEqualTo("CONFLICT");
    assertThat(result.message()).contains("\"revision\":" + head);
    assertThat(head()).as("冲突 ⇒ 零 revision").isEqualTo(head);
    assertThat(sdAt(head).directives().get(new DirectiveId("d1")).status())
        .as("★ 世界一字未动：令还是 ISSUED")
        .isEqualTo(DirectiveStatus.ISSUED);
  }

  @Test
  void aBlankReasonIsRefusedBeforeAnythingLands() throws Exception {
    issueDirective("d1");
    long before = head();

    ToolResult result = reject("d1", "   ", before);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).as("打回必须有话可说").contains("reason");
    assertThat(head()).as("坏输入 ⇒ 零 revision").isEqualTo(before);
    assertThat(sdAt(head()).directives().get(new DirectiveId("d1")).status())
        .as("令未被翻转")
        .isEqualTo(DirectiveStatus.ISSUED);
  }

  // ── 桶归属 ─────────────────────────────────────────────────────────────────────────

  /** ★ **只在 GM 桶**（= 运行时 MCP 口）：决策人自己不打回自己的令。 */
  @Test
  void theRejectToolIsOnlyInTheGmBucket() {
    assertThat(names(shell.toolsFor(SimosToolSource.Role.GM))).contains(RejectDirectiveTool.NAME);
    assertThat(names(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)))
        .as("决策人面**不含**打回（打回是 GM 的动作）")
        .doesNotContain(RejectDirectiveTool.NAME);
    assertThat(shell.toolRegistry().find(RejectDirectiveTool.NAME)).isPresent();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private ToolResult reject(String directiveId, String reason, long expectedRevision) {
    AgentTool tool =
        shell.toolsFor(SimosToolSource.Role.GM).stream()
            .filter(t -> RejectDirectiveTool.NAME.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("GM 桶里没有 " + RejectDirectiveTool.NAME));
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    args.put("directiveId", directiveId);
    args.put("reason", reason);
    return tool.execute(gmContext(tool, args));
  }

  /** 与 GM 组同形的调用者（四个命名空间各自 unlimited）——本类测的是打回语义，不是权限门。 */
  private static ToolContext gmContext(AgentTool tool, Map<String, Object> args) {
    ResourceScopeMap unlimited =
        ResourceScopeMap.of(
            Map.of(
                ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SD_NAMESPACE, ResourceScope.unlimited()));
    AgentPermissionSet permissions =
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            true,
            true,
            false,
            unlimited);
    return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args)
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  /** 经真命令面落一条令（空命令清单：本类不测命令效果，只测"打回"这条流程）。 */
  private CommandResult issueDirective(String directiveId) {
    String payload =
        "{\"directiveId\":\""
            + directiveId
            + "\",\"decisionMakerId\":\""
            + DM.value()
            + "\",\"tick\":"
            + WORLD_TICK
            + ",\"intentInfo\":\"意图\",\"commands\":[]}";
    return core()
        .submit(
            new CommandEnvelope(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                INITIATOR,
                main(),
                new RevisionId(head()),
                "sd.IssueDirective",
                payload));
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(part -> ((ContentPart.Text) part).text())
        .findFirst()
        .orElseThrow(() -> new AssertionError("这条消息没有文本分片: " + message.role()));
  }

  private static List<String> names(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /** 那条 revision 的变更集里出现了哪些命名空间（打回只该有 {@code sd}）。 */
  private Set<String> namespacesOfRevision(long revision) {
    RevisionRow row =
        core().revisions(main()).stream()
            .filter(r -> r.revision().value() == revision)
            .findFirst()
            .orElseThrow();
    return Timeline.readChangeSet(row.changesetJson()).modules().keySet();
  }

  private String unitName(long revision, UnitId id) {
    return ((UnitSnapshot) core().replay(ref("main", revision)).module("unit").orElseThrow())
        .state()
        .units()
        .get(id)
        .name();
  }

  /** 重放某个 revision 得到的 sd 状态（状态是否真落盘的判据面）。 */
  private SdState sdAt(long revision) {
    SimulationState state = core().replay(ref("main", revision));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private long head() {
    return core().head(main()).orElseThrow().value();
  }

  private CoreSimos core() {
    return shell.coreSimos();
  }

  private SqliteConversationStore conversationsIn() {
    return SqliteConversationStore.open(
        tempDir.resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

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
                  INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, genesisUnit(U1, "第一连", H11))));
    SocialData social =
        new SocialData(new LinkedHashMap<>(Map.of(H12, populationSeries())), Map.of(), Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, twoHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithOneNationOneArmyAndMaker())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  private java.nio.file.Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 一个**国家**决策人（本类不需要它为任何 provider 绑定）。 */
  private static SdState sdWithOneNationOneArmyAndMaker() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        DM, new DecisionMaker(DM, new Affiliation.Nation(fra), Set.of(), AccessLimit.empty(), 1));
    return SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
  }

  /** 两格世界（{@code (1,1)/(1,2)}，desert），区域 701（{@code nation:FRA}）覆盖两格。 */
  private static GameMap twoHexMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        new RegionId("701"),
        Region.of(
            new RegionId("701"),
            "区域 701",
            Set.of(H11, H12),
            new RegionMeta(null, NationTag.tagFor(new NationId("FRA")), null, null)));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
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

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  private static Unit genesisUnit(UnitId id, String name, HexCoord position) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }
}
