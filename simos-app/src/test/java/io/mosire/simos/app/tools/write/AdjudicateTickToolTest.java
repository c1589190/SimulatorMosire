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
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
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
import io.mosire.simos.sd.model.AdjudicationStatus;
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
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandTargets;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * ★★ **第 3 波第 2 步的真实验收面**：{@code sd.AdjudicateTick}——一个 tick 里**所有决策人**的令一起判效果、 **一次落一条
 * revision**（原子），越权者不进批、拒因进决策结果。
 *
 * <p>★ **全程走真装配**（真 {@code Shell} + 真 store + 真注册表 + 真 handler）：夹具世界 {@code Map1}（四格）：
 *
 * <pre>
 *   (1,1) u-1 ── 区域 701（nation:FRA）      (2,1) u-3 ── 无区域（中立格）
 *   (1,2)     ── 区域 701（nation:FRA）      (1,3) u-2 ── 区域 201（nation:GER）
 * </pre>
 *
 * <p>★ **两类决策人的可达面在夹具里故意分叉**（{@code (2,1)} 上的 {@code u-3}：**军队决策人够得着、国家决策人够不着**） ⇒
 * "把两类范围函数写成同一个"当场红，也正是"越权可区分"那条判据的承重点。
 *
 * <p>★ **不许只证"拒"**：每条判据都带**反方向**（范围内的真的落得下去、同一条命令换个有权的决策人就进批、 一个 tick 恰好推一格 head）。
 */
class AdjudicateTickToolTest {

  private static final String MAP_ID = "Map1";
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;
  private static final String INITIATOR = "player:local";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H21 = new HexCoord(2, 1);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final UnitId U3 = new UnitId("u-3");

  /** 世界 tick（创世时刻）——令都记在它上面。 */
  private static final long WORLD_TICK = 7L;

  private static final DecisionMakerId DM_FRA = new DecisionMakerId("dm-fra");
  private static final DecisionMakerId DM_ARMY = new DecisionMakerId("dm-army");
  private static final DecisionMakerId DM_NARROW = new DecisionMakerId("dm-fra-narrow");

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

  // ── 判据一：一个 tick 两个决策人 ⇒ 恰好一条新 revision，且多命名空间 + sd 都在 ─────────────

  /**
   * ★ **"一个 tick 一条 revision"的承重判据**：{@code dm-fra} 出一条 {@code unit.*}、{@code dm-army} 出一条 {@code
   * map.*} ⇒ 裁决后 head **恰好 +1**，而那一条 {@code WorldChangeSet} 里**同时**有 {@code unit} / {@code map} /
   * {@code sd}（决策结果）三个键。
   *
   * <p>★ 判别力：若实现改成"每条令各落一条 revision"，head 会 +3 ⇒ 这里红；若决策结果没进同一批，{@code sd} 键缺席 ⇒ 这里也红。
   */
  @Test
  void twoDecisionMakersInOneTickLandExactlyOneRevisionAcrossNamespaces() throws Exception {
    long before = head();
    issueDirective(
        WORLD_TICK,
        DM_FRA,
        "d-fra",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第一连改\"}"));
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands("map.SetTerrain", "{\"hexes\":[{\"q\":2,\"r\":1}],\"terrain\":\"plains\"}"));

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("★ 一个 tick 恰好一条 revision（两条令 + 决策结果同落一批）").isEqualTo(before + 2 + 1);

    Set<String> namespaces = namespacesOfRevision(after);
    assertThat(namespaces)
        .as("各命令所属命名空间的键 + sd（决策结果）都在同一条 revision 里")
        .contains("unit", "map", "sd");

    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(value.get("tick").asLong()).isEqualTo(WORLD_TICK);
    assertThat(value.get("resultRevision").asLong()).isEqualTo(after);
    assertThat(resultsByType(value))
        .containsEntry("unit.RenameUnit", "applied")
        .containsEntry("map.SetTerrain", "applied");

    // 反方向：世界**真的**变了（不是"没报错"）。
    assertThat(unitName(after, U1)).isEqualTo("第一连改");
  }

  // ── 判据二：handler 级被拒 ⇒ 该条被剔出、其余照落、仍只有一条 revision ────────────────────

  /**
   * ★ **前置校验覆盖不到的那一类**（"单位名不得空白"只有域层知道）：一条被 handler 拒、一条过 ⇒ 被拒的被**剔出**、 过的那条照落，而**整批仍然只落一条
   * revision**（不是"先落一条、再补一条结果"），拒因**逐条**记在决策结果里。
   */
  @Test
  void aHandlerRejectedCommandIsDroppedAndRecordedWhileTheRestLand() throws Exception {
    long before = head();
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands(
            "unit.RenameUnit",
            "{\"id\":\"u-1\",\"name\":\"第一连改\"}",
            "unit.RenameUnit",
            "{\"id\":\"u-3\",\"name\":\"\"}"));

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("★ 仍只有一条 revision（被拒的那条被剔出后重试，不是另落一条）").isEqualTo(before + 1 + 1);
    assertThat(namespacesOfRevision(after)).contains("unit", "sd");

    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(value.get("commands")).hasSize(2);
    assertThat(value.get("commands").get(0).get("result").asText()).isEqualTo("applied");
    assertThat(value.get("commands").get(1).get("result").asText()).isEqualTo("rejected");
    assertThat(value.get("commands").get(1).get("reason").asText())
        .as("拒因是**域层的原文**（handler 拒绝），不是一句笼统的失败")
        .contains("不得为空白");
    assertThat(unitName(after, U1)).as("过检的那条真的落了").isEqualTo("第一连改");
    assertThat(unitName(after, U3)).as("被剔的那条真的没落").isEqualTo("第三连");
  }

  // ── 判据三：expectedRevision 过期 ⇒ Conflict、零 revision ─────────────────────────────

  /**
   * ★★ **重写后只有新版生效**（2026-09-23 用户裁定的"令可重写"在裁决侧的承重判据）。
   *
   * <p>同一 ({@code dm-army}, {@code WORLD_TICK}) 出两条令（第二条把第一条顶成 {@code SUPERSEDED}）⇒
   * 裁决时**只有新版**的命令进批：
   *
   * <ul>
   *   <li>单位名 == 新版给的名字（旧版那条命令**一条都没执行**）；
   *   <li>决策结果里**只有新版那一条命令**、**只有新版那一条翻转**（旧版不进批、也不产生翻转——它早就是终态）；
   *   <li>状态：{@code d-v1 = SUPERSEDED}、{@code d-v2 = EXECUTED}。
   * </ul>
   *
   * <p>★ 判别力：把 {@code directivesAt} 改回"只按 tick 取"，旧版的命令会**也**进批（两条都生效 ⇒ 单位名落到旧版或结果多一行）⇒ 本用例红。
   */
  @Test
  void onlyTheLatestVersionOfARewrittenDirectiveIsAdjudicated() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-v1",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第一版\"}"));
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-v2",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第二版\"}"));

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(unitName(after, U1)).as("★ 只有新版生效（旧版那条命令一条都没执行）").isEqualTo("第二版");

    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(value.get("commands")).as("旧版不进批、不进结果").hasSize(1);
    assertThat(value.get("commands").get(0).get("directiveId").asText()).isEqualTo("d-v2");
    assertThat(value.get("flips")).as("旧版不产生翻转（它已是终态 SUPERSEDED）").hasSize(1);
    assertThat(value.get("flips").get(0).get("directiveId").asText()).isEqualTo("d-v2");

    assertThat(sdAt(after).directives().get(new DirectiveId("d-v1")).status())
        .as("旧版保持 SUPERSEDED（裁决不碰它）")
        .isEqualTo(DirectiveStatus.SUPERSEDED);
    assertThat(sdAt(after).directives().get(new DirectiveId("d-v2")).status())
        .as("新版被裁决为 EXECUTED")
        .isEqualTo(DirectiveStatus.EXECUTED);
  }

  /**
   * ★★ **被打回的令不参与裁决**（2026-09-23 用户裁定的"GM 打回"在裁决侧的承重判据）：一条被 GM 打回（{@code CANCELLED}）的令**不执行**，
   * 也不进决策结果；同 tick 的另一条（未被打回）照常裁决。
   *
   * <p>★ 判别力：把 {@code participatesInAdjudication} 改成"一律参与"，被打回那条的命令会真的执行（单位名变成"不该生效"）⇒ 本用例红。
   */
  @Test
  void aRejectedDirectiveDoesNotParticipateInAdjudicationWhileItsSiblingDoes() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-rejected",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"不该生效\"}"));
    assertThat(
            submit(
                "sd.SetDirectiveStatus",
                "{\"directiveId\":\"d-rejected\",\"status\":\"CANCELLED\"}"))
        .as("先把它打回（造出 CANCELLED 的前置）")
        .isInstanceOf(CommandResult.Committed.class);
    issueDirective(
        WORLD_TICK,
        DM_FRA,
        "d-live",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"该生效\"}"));
    long before = head();

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("仍只一条 revision").isEqualTo(before + 1);
    assertThat(unitName(after, U1)).as("★ 被打回的那条没执行、没被打回的那条执行了").isEqualTo("该生效");

    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(value.get("commands")).as("被打回的令不进批、不进结果").hasSize(1);
    assertThat(value.get("commands").get(0).get("directiveId").asText()).isEqualTo("d-live");
    assertThat(value.get("flips")).as("被打回的令不产生翻转").hasSize(1);
    assertThat(sdAt(after).directives().get(new DirectiveId("d-rejected")).status())
        .as("★ 被打回的令保持 CANCELLED（裁决不把它翻回、也不改成别的）")
        .isEqualTo(DirectiveStatus.CANCELLED);
  }

  @Test
  void aStaleExpectedRevisionIsAConflictWithZeroRevisions() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_FRA,
        "d-fra",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"x\"}"));
    long head = head();

    ToolResult result = adjudicateWithExpected(WORLD_TICK, head - 1);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).as("★ 冲突原样上报（不是部分提交、不是蒙过 expectedRevision）").isEqualTo("CONFLICT");
    assertThat(result.message()).contains("\"revision\":" + head);
    assertThat(head()).as("冲突 ⇒ 零 revision").isEqualTo(head);
    assertThat(head()).as("★ 而且那条 revision 还是**出令**留下的那一格，裁决一格都没落").isEqualTo(2L);
  }

  // ── 判据四：该 tick 无令 ⇒ 明确拒绝（不静默成功）─────────────────────────────────────

  @Test
  void anEmptyTickIsRejectedInsteadOfSilentlySucceeding() {
    long before = head();

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("没有令可裁 ⇒ 必须明确失败，不许静默返回成功").isFalse();
    assertThat(result.code()).isEqualTo("REJECTED");
    assertThat(result.message()).contains("无可裁决");
    assertThat(head()).as("零 revision").isEqualTo(before);
  }

  // ── 判据五：越权 ⇒ 不进批；同一条命令换个有权的决策人 ⇒ 进批（可区分）────────────────────

  /**
   * ★★ **"越权可区分"的承重判据**：**同一条命令**（{@code unit.RenameUnit} on {@code u-3}，载荷逐字相同）——
   *
   * <ul>
   *   <li>{@code dm-fra}（**国家**决策人，可达面 = 本国区域 701 ⇒ u-1）：{@code u-3} 在**中立格**上 ⇒ **够不着** ⇒
   *       不进批、拒因（含越界资源 id）进决策结果；
   *   <li>{@code dm-army}（**军队**决策人，可达面 = 视野圈 ⇒ u-1 与 u-3）：同一条 ⇒ **进批并生效**。
   * </ul>
   *
   * <p>★ 若把授权退化成"命名空间级"（只看 unit 这个命名空间够不够得着），两个决策人都会放行 ⇒ 第一条断言红。
   */
  @Test
  void theSameCommandIsRefusedForOneDecisionMakerAndAppliedForAnother() throws Exception {
    long before = head();
    String renameU3 = "unit.RenameUnit";
    String payload = "{\"id\":\"u-3\",\"name\":\"第三连改\"}";
    issueDirective(WORLD_TICK, DM_FRA, "d-fra", commands(renameU3, payload));
    issueDirective(WORLD_TICK, DM_ARMY, "d-army", commands(renameU3, payload));

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("两条令 + 决策结果仍只落一条 revision").isEqualTo(before + 2 + 1);

    JsonNode value = decisionResult(after, WORLD_TICK);
    JsonNode fra = commandOf(value, DM_FRA.value());
    JsonNode army = commandOf(value, DM_ARMY.value());
    assertThat(fra.get("result").asText()).as("★ 国家决策人够不着 u-3 ⇒ 不进批").isEqualTo("rejected");
    assertThat(fra.get("reason").asText()).as("拒因写明**哪条资源**越界（可读、可核对）").contains("unit:u-3");
    assertThat(army.get("result").asText()).as("★ 同一条命令，军队决策人够得着 ⇒ 进批").isEqualTo("applied");
    assertThat(unitName(after, U3)).as("真的按军队决策人的令改了名").isEqualTo("第三连改");
  }

  // ── 判据六：显式 none 必拒；"不表态"不是收紧（两条方向相反）────────────────────────────

  /**
   * ★★ **两条方向相反的语义**都要有样本：
   *
   * <ol>
   *   <li>**显式 {@code none()}**（{@code accessLimit {"unit": []}} ⇒ 该命名空间"够不着"）：令**必须被拒**（端到端）；
   *   <li>**不表态**（{@code declaredScope} 返 null）：按既有语义**回落缺省策略 = 不收紧** ⇒ 放行。
   * </ol>
   *
   * <p>★ 第 2 条在端到端路径上**今天走不到**：两个内置范围函数都把 map/unit/social **显式表态**（这是它们的既定契约） ⇒ 只能在 {@link
   * AdjudicateTickTool#violations} 这一层测（不然这条判据就是空的）。
   */
  @Test
  void anExplicitlyBlockedNamespaceIsRefusedWhileAnUndeclaredOneIsNotTightened() throws Exception {
    // ① 显式 none：dm-fra-narrow 的 unit 可达面被 accessLimit 收成空集 ⇒ 同一条命令被拒（端到端）。
    issueDirective(
        WORLD_TICK,
        DM_NARROW,
        "d-narrow",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"x\"}"));
    long before = head();
    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("只剩一条被拒的令也必须成（拒因本身是「实在效果」）").isTrue();
    long after = head();
    assertThat(after).isEqualTo(before + 1);
    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(value.get("commands").get(0).get("result").asText()).isEqualTo("rejected");
    assertThat(value.get("commands").get(0).get("reason").asText()).contains("unit:u-1");
    assertThat(unitName(after, U1)).as("没落").isEqualTo("第一连");

    // ② 不表态 vs 显式 none：同一层上直接断言两条相反方向（端到端走不到"不表态"，见类注）。
    assertThat(AdjudicateTickTool.violations(ResourceScopeMap.empty(), "unit", List.of("u-1")))
        .as("★ 不表态 = 本层不收紧（回落缺省策略）⇒ 放行")
        .isEmpty();
    assertThat(
            AdjudicateTickTool.violations(
                ResourceScopeMap.of("unit", ResourceScope.none()), "unit", List.of("u-1")))
        .as("★ 显式 none ⇒ 拒，且拒因点名那条资源")
        .containsExactly("unit:u-1");
  }

  // ── 判据七：过去的 tick 也能裁决，决策结果条目带**那个** tick ────────────────────────────

  /**
   * ★ 令的 {@code tick} 允许**补记过去**（{@code IssueDirectiveHandler} 只拒未来）⇒ 裁决一个过去的 tick 必须可行，
   * 且决策结果条目**带那个 tick**（否则那些令永远没有结果）。命令的**效果仍落在当下**（世界还在 current tick）。
   */
  @Test
  void aPastTickIsAdjudicatedAndItsDecisionResultCarriesThatTick() throws Exception {
    long past = 3L;
    issueDirective(
        past, DM_ARMY, "d-army", commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"补记改\"}"));

    ToolResult result = adjudicate(past);

    assertThat(result.success()).as("补记的令必须可裁决（实际：%s）", result.message()).isTrue();
    long after = head();
    SimulationState state = core().replay(ref("main", after));
    SdState sd = ((SdSnapshot) state.module("sd").orElseThrow()).state();
    List<SdInfoEntry> entries = sd.info().get(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + past);
    assertThat(entries).as("按 tick 归档 ⇒ 地址里带的就是那个 tick").isNotNull().hasSize(1);
    assertThat(entries.get(0).tick()).as("★ 条目带**过去**那个 tick（不是世界 tick）").isEqualTo(past);
    assertThat(entries.get(0).tags()).as("标签挂到本次涉及的决策人").containsExactly(DM_ARMY);
    assertThat(unitName(after, U1)).as("效果落在当下").isEqualTo("补记改");
  }

  // ── 判据八：同一个 tick 的第二次裁决落**第二条记录**（改判）；"至多一条生效"由状态承担 ──────────────

  /**
   * ★★ **改判语义（2026-09-23 用户裁定「只有生效裁决和作废裁决」）**：旧口径是"一个 tick 一条"的幂等闸（id 写死 {@code #0}，同 tick 再裁就撞 id
   * 被拒）。那条闸被**有意撤掉**了——它让"作废之后重裁"根本不可能（作废把 {@code #0} 翻成 VOIDED 留在原地，重裁再写 {@code #0} 必撞）。新的模型是：一个
   * tick 可以留**多条**记录，**至多一条生效**。
   *
   * <p>★ 本条钉两件事：① 第二次裁决**成功**、落 {@code #1}、条目状态 {@code EFFECTIVE}；② 第一条**还在**（留痕）。 ★ 判别力：把 id 改回写死
   * {@code #0} ⇒ 第二次撞 id 被拒 ⇒ 本条红。
   */
  @Test
  void adjudicatingTheSameTickTwiceLandsASecondRecordRatherThanBeingBlockedById() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第一连改\"}"));
    // 先把第一次裁决作废（不然世界已是改后的样子，第二次的命令会在错误的基态上试）——但本用例只证"能不能落第二条"，
    // 故直接再裁一次：命令会因"名字已经是第一连改"而无实际变更，这不影响本条要证的东西。
    assertThat(adjudicate(WORLD_TICK).success()).isTrue();
    long afterFirst = head();

    ToolResult second = adjudicate(WORLD_TICK);

    assertThat(second.success()).as("第二次不再被幂等闸挡下（旧口径反过来会红）").isTrue();
    assertThat(head()).as("落了第二条 revision").isGreaterThan(afterFirst);

    String address = AdjudicateTickTool.RESULT_ADDRESS_PREFIX + WORLD_TICK;
    SimulationState state = core().replay(ref("main", head()));
    SdState sd = ((SdSnapshot) state.module("sd").orElseThrow()).state();
    List<SdInfoEntry> entries = sd.info().get(address);
    assertThat(entries).as("同 tick 两条记录都在（留痕）").hasSize(2);
    assertThat(entries.stream().map(e -> e.id().value()))
        .as("★ id 是「该地址下第 n 条」，不是写死的 #0")
        .containsExactly(address + "#0", address + "#1");
    assertThat(entries.get(1).adjudicationStatus())
        .as("★ 新落的那条是**生效**裁决（旧的那条的作废由 void 路径负责，不在本工具职责内）")
        .contains(io.mosire.simos.sd.model.AdjudicationStatus.EFFECTIVE);
  }

  // ── 判据九：目标声明清单（缺口可见，不静默）────────────────────────────────────────────

  /**
   * ★★ **23 条映射逐条有断言 + 4 条缺口在册可查**。
   *
   * <p>★ 为什么必须逐条断言：映射表漏一项的后果是"那条命令**永远被静默拒**"（fail-closed 的另一面）——看起来
   * 一切正常，只有用户的令无声无息地不生效。所以这里对**每一条**都钉住输出路径。
   */
  @Test
  void everyWhitelistedDecisionCommandDeclaresItsTargetsOrIsListedAsMissing() {
    AdjudicateTickTool tool = tool();
    Map<String, CommandTargets> targets = tool.commandTargets();

    // ① 4 条缺口必须**恰好**是这 4 条（少一条 ⇒ 有人悄悄补上了却没登记；多一条 ⇒ 白名单里多了没人管的类型）。
    Set<String> missing = new LinkedHashSet<>(tool.allowedCommandTypes());
    missing.removeAll(targets.keySet());
    assertThat(missing)
        .as("★ 有意无目标的 4 条（语义对象不是资源命名空间）——缺口在册可查，不静默")
        .containsExactlyInAnyOrder(
            "unit.CreateCommandChain",
            "unit.UpdateCommandChain",
            "map.SetEdge",
            "map.RegisterPathwayGroup");
    assertThat(tool.allowedCommandTypes())
        .as("白名单 = unit 20 + map 7 + social 3 + economy 1")
        .hasSize(31);

    // ② 其余 26 条：逐条给真载荷、钉死输出路径。
    Map<String, List<String>> samples = new LinkedHashMap<>();
    samples.put("unit.RenameUnit", List.of("{\"id\":\"u-1\",\"name\":\"x\"}", "u-1"));
    samples.put(
        "unit.CreateUnit",
        List.of(
            "{\"id\":\"u-9\",\"name\":\"x\",\"position\":{\"q\":1,\"r\":1},\"member\":1,\"equipment\":{},\"speed\":1,\"mobilityPerMille\":500}",
            "u-9"));
    samples.put("unit.CancelRoute", List.of("{\"id\":\"u-1\"}", "u-1"));
    samples.put("unit.DisbandUnit", List.of("{\"id\":\"u-1\"}", "u-1"));
    samples.put(
        "unit.SetStrength", List.of("{\"id\":\"u-1\",\"member\":1,\"equipment\":{}}", "u-1"));
    samples.put("unit.SetStatus", List.of("{\"id\":\"u-1\",\"status\":\"RESTING\"}", "u-1"));
    samples.put(
        "unit.ApplyCasualties",
        List.of("{\"id\":\"u-1\",\"personnel\":-1,\"equipment\":{}}", "u-1"));
    samples.put("unit.DetachUnit", List.of("{\"id\":\"u-3\"}", "u-3"));
    samples.put("unit.SetFormationOffset", List.of("{\"id\":\"u-3\",\"dq\":1,\"dr\":0}", "u-3"));
    samples.put("unit.PlaceAt", List.of("{\"id\":\"u-1\",\"hex\":{\"q\":1,\"r\":2}}", "u-1"));
    samples.put(
        "unit.PlanRoute", List.of("{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":2}]}", "u-1"));
    samples.put(
        "unit.PlanSparseRoute",
        List.of("{\"id\":\"u-1\",\"waypoints\":[{\"q\":2,\"r\":1}]}", "u-1"));
    samples.put(
        "unit.SetRejoinTarget", List.of("{\"id\":\"u-1\",\"target\":\"u-3\"}", "u-1", "u-3"));
    samples.put("unit.ReparentUnit", List.of("{\"id\":\"u-1\",\"parent\":\"u-3\"}", "u-1", "u-3"));
    samples.put("unit.AttachUnit", List.of("{\"id\":\"u-3\",\"parent\":\"u-1\"}", "u-3", "u-1"));
    samples.put(
        "unit.MergeFormation", List.of("{\"childId\":\"u-3\",\"parentId\":\"u-1\"}", "u-3", "u-1"));
    samples.put(
        "unit.ReparentSubtree", List.of("{\"rootId\":\"u-3\",\"parent\":\"u-1\"}", "u-3", "u-1"));
    samples.put(
        "unit.SplitFormation",
        List.of("{\"rootId\":\"u-1\",\"subUnitIds\":[\"u-3\",\"u-2\"]}", "u-1", "u-3", "u-2"));
    samples.put("map.DeleteRegion", List.of("{\"regionId\":\"701\"}", MAP_ID + "/region/701"));
    samples.put(
        "map.SetTerrain",
        List.of(
            "{\"hexes\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}],\"terrain\":\"plains\"}",
            MAP_ID + "/hex/1_1",
            MAP_ID + "/hex/1_2"));
    samples.put(
        "map.RandomizeRegion",
        List.of("{\"hexes\":[{\"q\":1,\"r\":1}],\"seed\":7}", MAP_ID + "/hex/1_1"));
    samples.put(
        "map.CreateRegion",
        List.of(
            "{\"regionId\":\"r-9\",\"name\":\"n\",\"hexes\":[{\"q\":1,\"r\":1}]}",
            MAP_ID + "/region/r-9",
            MAP_ID + "/hex/1_1"));
    samples.put(
        "map.UpdateRegion",
        List.of(
            "{\"regionId\":\"701\",\"hexes\":[{\"q\":1,\"r\":1}]}",
            MAP_ID + "/region/701",
            MAP_ID + "/hex/1_1"));
    // social 三条：逐格人口与城市节点都按 social 命名空间的 {@code <q>_<r>} 形态给目标；
    //   UpdateCity 的载荷不含坐标 ⇒ 目标声明为**空**（fail-closed，见 UpdateCityHandler 类注）。
    samples.put(
        "social.SetPopulation",
        List.of("{\"entries\":[{\"q\":1,\"r\":1,\"population\":1}]}", "1_1"));
    samples.put(
        "social.CreateCity",
        List.of("{\"id\":\"c1\",\"name\":\"n\",\"at\":{\"q\":1,\"r\":2},\"population\":1}", "1_2"));
    samples.put("social.UpdateCity", List.of("{\"id\":\"c1\",\"name\":\"x\"}"));
    // economy 一条（R2a）：播种按**格**给目标（{@code <q>_<r>}，与 social 同款不带 mapId）。
    samples.put(
        "economy.Seed",
        List.of(
            "{\"mapId\":\"Map1\",\"rulesVersion\":\"v\",\"entries\":[{\"q\":1,\"r\":1,"
                + "\"industries\":[]}]}",
            "1_1"));

    for (Map.Entry<String, List<String>> sample : samples.entrySet()) {
      List<String> expected = sample.getValue();
      String type = sample.getKey();
      assertThat(targets).as("%s 必须有目标声明", type).containsKey(type);
      assertThat(targets.get(type).targetPaths(MAP_ID, expected.get(0)))
          .as("%s 的目标路径", type)
          .containsExactlyInAnyOrderElementsOf(expected.subList(1, expected.size()));
    }
    assertThat(samples.keySet()).as("27 条有目标声明的类型一条不漏（少一条 ⇒ 上面那条断言根本不会跑）").hasSize(27);
    assertThat(targets.keySet())
        .as("表里不该有白名单外的类型")
        .containsExactlyInAnyOrderElementsOf(samples.keySet());
  }

  // ── 判据十：状态翻转（第 3 波最后一块）——两种终态可区分、仍只有一条 revision、重放一致 ────────────

  /**
   * ★★ **本步的承重判据**：同一个 tick 两条令——一条命令**全部被接受** ⇒ {@code EXECUTED}，一条命令**被拒** ⇒ {@code CANCELLED}。
   *
   * <p>★ 三处判别力：
   *
   * <ol>
   *   <li>**两种终态可区分**：把"全部接受 / 有一个被拒"写成同一种结局（都 {@code EXECUTED} 或都 {@code CANCELLED}）⇒ 当场红；
   *   <li>**仍只有一条 revision**：翻转若被实现成"再 submit 一次"，head 会多 +1（或 +2）⇒ 这里红；
   *   <li>**重放往返**：翻转是同一条 revision 里的 sd 变更，{@code replay(after)} 必须复现两个终态（不是只有内存里改过）。
   * </ol>
   *
   * <p>★ 拒因来自**可达面**（{@code dm-fra} 够不着中立格上的 {@code u-3}）——这正是"被拒 ⇒ CANCELLED"的样本。
   */
  @Test
  void allAcceptedCommandsYieldExecutedAndAnyRejectionYieldsCancelledInOneRevision()
      throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第一连改\"}"));
    issueDirective(
        WORLD_TICK,
        DM_FRA,
        "d-fra",
        commands("unit.RenameUnit", "{\"id\":\"u-3\",\"name\":\"第三连改\"}"));
    long before = head();

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("★ 两条令 + 一条决策结果 + 两条状态翻转仍**只落一条** revision").isEqualTo(before + 1);

    // ★ 重放往返：终态是**落盘变更**（不是内存副作用），且两种终态可区分。
    SdState replayed = sdAt(after);
    assertThat(replayed.directives().get(new DirectiveId("d-army")).status())
        .as("命令全部被接受 ⇒ EXECUTED")
        .isEqualTo(DirectiveStatus.EXECUTED);
    assertThat(replayed.directives().get(new DirectiveId("d-fra")).status())
        .as("★ 有任一命令被拒 ⇒ CANCELLED（与上面那条必须不同）")
        .isEqualTo(DirectiveStatus.CANCELLED);

    // 决策结果条目里也逐条记下翻转结局（可回放、可审计的那份）。
    JsonNode value = decisionResult(after, WORLD_TICK);
    assertThat(flipStatusOf(value, "d-army")).isEqualTo("EXECUTED");
    assertThat(flipStatusOf(value, "d-fra")).isEqualTo("CANCELLED");
    assertThat(flipResultOf(value, "d-army")).isEqualTo("applied");
    assertThat(flipResultOf(value, "d-fra")).isEqualTo("applied");

    assertThat(unitName(after, U1)).as("EXECUTED 的令的命令真的落了").isEqualTo("第一连改");
    assertThat(unitName(after, U3)).as("CANCELLED 的令的命令真的没落").isEqualTo("第三连");
  }

  /**
   * ★ **"一个 tick 仍然只有一条 revision"的边界样本**：一条令**部分**命令被拒（handler 级，前置校验覆盖不到）⇒ 该令 {@code
   * CANCELLED}、其余命令照落、**head 只 +1**。
   *
   * <p>★ 若把翻转写成"先落命令、再补一条翻转 revision"，这里 head 会多一格 ⇒ 红。
   */
  @Test
  void aPartiallyRejectedDirectiveIsCancelledWhileTheRestLandInOneRevision() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands(
            "unit.RenameUnit",
            "{\"id\":\"u-1\",\"name\":\"第一连改\"}",
            "unit.RenameUnit",
            "{\"id\":\"u-3\",\"name\":\"\"}"));
    long before = head();

    ToolResult result = adjudicate(WORLD_TICK);

    assertThat(result.success()).as("裁决必须成（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("★ 部分被拒也仍然只落一条 revision").isEqualTo(before + 1);
    assertThat(sdAt(after).directives().get(new DirectiveId("d-army")).status())
        .as("有任一命令被剔 ⇒ 整条令 CANCELLED")
        .isEqualTo(DirectiveStatus.CANCELLED);
    assertThat(unitName(after, U1)).as("过检的那条照落").isEqualTo("第一连改");
    assertThat(unitName(after, U3)).as("被剔的那条没落").isEqualTo("第三连");
  }

  /**
   * ★★ **转移守卫端到端**（真 {@code CommandBus} + 真 handler）：同一条令重复翻转 / 目标值非法 / 令不存在，三条都必须**拒**且拒因
   * **可读**；拒不留 revision。
   *
   * <p>★ 用**已注册的命令类型**经通用命令面提交（与裁决内部的编排同一条执行路径），故这条也钉住"翻转命令确实注册在册"。
   */
  @Test
  void statusFlipGuardRejectsRepeatIllegalTargetAndMissingDirective() {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"x\"}"));
    issueDirective(
        WORLD_TICK,
        DM_FRA,
        "d-fra",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"y\"}"));

    // 正向：ISSUED → EXECUTED 成；落地可重放。
    CommandResult first =
        submit("sd.SetDirectiveStatus", "{\"directiveId\":\"d-army\",\"status\":\"EXECUTED\"}");
    assertThat(first).isInstanceOf(CommandResult.Committed.class);
    assertThat(sdAt(head()).directives().get(new DirectiveId("d-army")).status())
        .isEqualTo(DirectiveStatus.EXECUTED);

    // ① 重复翻转（已终态，且还换了另一个终态）⇒ 拒，拒因写明**当前态与目标态**。
    long headBeforeRepeat = head();
    CommandResult repeat =
        submit("sd.SetDirectiveStatus", "{\"directiveId\":\"d-army\",\"status\":\"CANCELLED\"}");
    assertThat(repeat).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) repeat).reason())
        .as("★ 重复翻转被拒且拒因可读（点名当前 EXECUTED、目标 CANCELLED）")
        .contains("不可转移")
        .contains("EXECUTED")
        .contains("CANCELLED");
    assertThat(head()).as("被拒 ⇒ 零 revision").isEqualTo(headBeforeRepeat);

    // ② 目标值非法（只允许两个终态；PLANNED 也不行）⇒ 拒。
    CommandResult illegal =
        submit("sd.SetDirectiveStatus", "{\"directiveId\":\"d-fra\",\"status\":\"PLANNED\"}");
    assertThat(illegal).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) illegal).reason()).contains("目标状态非法").contains("PLANNED");
    assertThat(sdAt(head()).directives().get(new DirectiveId("d-fra")).status())
        .as("被拒 ⇒ 该令还是 ISSUED")
        .isEqualTo(DirectiveStatus.ISSUED);

    // ③ 令不存在 ⇒ 拒。
    CommandResult missing =
        submit("sd.SetDirectiveStatus", "{\"directiveId\":\"ghost\",\"status\":\"EXECUTED\"}");
    assertThat(missing).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) missing).reason()).contains("决策不存在").contains("ghost");
  }

  /**
   * ★ **翻转自己被拒 ⇒ 走收敛逻辑（剔出 + 记拒因），不许静默吞掉**。
   *
   * <p>造样本：先手工把一条令翻到终态（{@code EXECUTED}），再裁决它所在的 tick ⇒ 裁决要生成的翻转（{@code ISSUED → EXECUTED}）
   * 撞上转移守卫被拒。判据三处：① 裁决仍**成**且**只落一条 revision**（翻转被剔后 info 照落，退化成"无翻转"的一批）；② 决策结果的 {@code flips}
   * 行把它记为 {@code rejected} 且带**可读拒因**（不静默）；③ 已终态的令**不被改回**。
   */
  @Test
  void aRejectedStatusFlipIsDroppedAndRecordedInsteadOfSilentlySwallowed() throws Exception {
    long tick = 5L;
    issueDirective(tick, DM_ARMY, "d-pre", commands());
    assertThat(
            submit("sd.SetDirectiveStatus", "{\"directiveId\":\"d-pre\",\"status\":\"EXECUTED\"}"))
        .as("先把 d-pre 手工翻到终态（造出『翻转必然被拒』的前置）")
        .isInstanceOf(CommandResult.Committed.class);
    long before = head();

    ToolResult result = adjudicate(tick);

    assertThat(result.success()).as("翻转被拒不该拖垮整次裁决（实际：%s）", result.message()).isTrue();
    long after = head();
    assertThat(after).as("★ 翻转被剔后 info 照落，仍只一条 revision（不是零条、也不是两条）").isEqualTo(before + 1);
    JsonNode value = decisionResult(after, tick);
    assertThat(flipResultOf(value, "d-pre")).as("翻转自己被拒 ⇒ 记为 rejected（不静默）").isEqualTo("rejected");
    assertThat(flipRow(value, "d-pre").get("reason").asText()).as("★ 拒因可读且点名不可转移").contains("不可转移");
    assertThat(sdAt(after).directives().get(new DirectiveId("d-pre")).status())
        .as("已终态的令不许被改回/改掉")
        .isEqualTo(DirectiveStatus.EXECUTED);
  }

  // ── 判据十：作废一次裁决 = 一条 revision 的原子撤销（2026-09-23，用户裁定 (b)+B）────────────────

  /**
   * ★★ **作废的完整语义**（用户 2026-09-23：「只有生效裁决和作废裁决」，且作废要**回滚世界**）：一次作废 = **一条** revision ——
   * 世界回到裁决之前、被它翻过的令退回待裁决、那条记录**不删**只换成 VOIDED。
   *
   * <p>★ 判别力：把 restore 换成"只改状态不回滚"⇒ ② 红；把记录删掉而不是翻状态 ⇒ ④ 红；把令留在 EXECUTED ⇒ ③ 红。
   */
  @Test
  void voidingAnAdjudicationRollsBackTheWorldAndReturnsTheDirectiveToIssued() throws Exception {
    issueDirective(
        WORLD_TICK,
        DM_ARMY,
        "d-army",
        commands("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"第一连改\"}"));
    assertThat(adjudicate(WORLD_TICK).success()).isTrue();
    long afterAdjudication = head();
    assertThat(unitName(afterAdjudication, U1)).as("前提：裁决真的改了世界").isEqualTo("第一连改");

    ToolResult result = voidAdjudication(WORLD_TICK);

    assertThat(result.success()).as("作废应成功：%s", result.message()).isTrue();
    long afterVoid = head();
    assertThat(afterVoid).as("① 追加一条逆变更 revision（时间线只追加，不截断）").isGreaterThan(afterAdjudication);
    assertThat(unitName(afterVoid, U1)).as("② 世界真的回到裁决之前").isNotEqualTo("第一连改");
    SdState sd = sdAt(afterVoid);
    assertThat(sd.directives().get(new DirectiveId("d-army")).status())
        .as("③ 被它翻过的令退回待裁决（于是可以改判/重裁）")
        .isEqualTo(DirectiveStatus.ISSUED);
    List<SdInfoEntry> entries =
        sd.info().get(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + WORLD_TICK);
    assertThat(entries).as("④ 那条记录**不删**（留痕：「第 1 版被作废」本身要看得到）").hasSize(1);
    assertThat(entries.get(0).adjudicationStatus()).contains(AdjudicationStatus.VOIDED);
    assertThat(SdInfoEntry.isEffective(entries.get(0))).as("⑤ 作废之后该 tick 没有生效裁决").isFalse();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private AdjudicateTickTool tool() {
    return (AdjudicateTickTool)
        shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.GM).stream()
            .filter(t -> AdjudicateTickTool.NAME.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("GM 桶里没有 " + AdjudicateTickTool.NAME));
  }

  private ToolResult voidAdjudication(long tick) {
    AgentTool tool =
        shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.GM).stream()
            .filter(t -> VoidAdjudicationTool.NAME.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("GM 桶里没有 " + VoidAdjudicationTool.NAME));
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", head());
    args.put("tick", tick);
    return tool.execute(gmContext(tool, args));
  }

  private ToolResult adjudicate(long tick) {
    return adjudicateWithExpected(tick, head());
  }

  private ToolResult adjudicateWithExpected(long tick, long expectedRevision) {
    AgentTool tool = tool();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    args.put("tick", tick);
    return tool.execute(gmContext(tool, args));
  }

  /** 与 GM 组同形的调用者（四个命名空间各自 unlimited）——本类测的是裁决语义，不是权限门，故用它的"够得着"形态。 */
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

  /** 经真命令面落一条令（每条令自己是一条 revision，与生产路径同形）。 */
  private void issueDirective(
      long tick, DecisionMakerId dm, String directiveId, String commandsJson) {
    String payload =
        "{\"directiveId\":\""
            + directiveId
            + "\",\"decisionMakerId\":\""
            + dm.value()
            + "\",\"tick\":"
            + tick
            + ",\"intentInfo\":\"意图\",\"commands\":"
            + commandsJson
            + "}";
    CommandResult result =
        core()
            .submit(
                new CommandEnvelope(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    INITIATOR,
                    main(),
                    new RevisionId(head()),
                    "sd.IssueDirective",
                    payload));
    assertThat(result).as("出令必须成: %s", result).isInstanceOf(CommandResult.Committed.class);
  }

  /** {@code [{"type":…,"payloadJson":"…"}…]}（成对给 type/payload，载荷里的引号自动转义）。 */
  private static String commands(String... typeAndPayloadPairs) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < typeAndPayloadPairs.length; i += 2) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"type\":\"")
          .append(typeAndPayloadPairs[i])
          .append("\",\"payloadJson\":\"")
          .append(typeAndPayloadPairs[i + 1].replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\"}");
    }
    return json.append(']').toString();
  }

  private JsonNode decisionResult(long revision, long tick) throws Exception {
    SimulationState state = core().replay(ref("main", revision));
    SdState sd = ((SdSnapshot) state.module("sd").orElseThrow()).state();
    List<SdInfoEntry> entries = sd.info().get(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick);
    assertThat(entries).as("tick %s 的决策结果条目", tick).isNotNull().hasSize(1);
    SdInfoEntry entry = entries.get(0);
    assertThat(entry.id().value())
        .as("id 由地址派生 ⇒ 一个 tick 一条")
        .isEqualTo(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick + "#0");
    assertThat(entry.key()).isEqualTo(AdjudicateTickTool.RESULT_KEY);
    return JSON.readTree((String) entry.value());
  }

  private static Map<String, String> resultsByType(JsonNode value) {
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode row : value.get("commands")) {
      out.put(row.get("type").asText(), row.get("result").asText());
    }
    return out;
  }

  private static JsonNode commandOf(JsonNode value, String decisionMakerId) {
    for (JsonNode row : value.get("commands")) {
      if (decisionMakerId.equals(row.get("decisionMakerId").asText())) {
        return row;
      }
    }
    throw new AssertionError("决策结果里没有 " + decisionMakerId + " 的命令: " + value);
  }

  private String unitName(long revision, UnitId id) {
    return ((UnitSnapshot) core().replay(ref("main", revision)).module("unit").orElseThrow())
        .state()
        .units()
        .get(id)
        .name();
  }

  /** 重放某个 revision 得到的 sd 状态——状态翻转是否**真落盘**的判据面（往返）。 */
  private SdState sdAt(long revision) {
    SimulationState state = core().replay(ref("main", revision));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  /** 经**真命令面**提交一条命令（期望坐标 = 当前 head）——用于转移守卫的端到端样本。 */
  private CommandResult submit(String type, String payloadJson) {
    return core()
        .submit(
            new CommandEnvelope(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                INITIATOR,
                main(),
                new RevisionId(head()),
                type,
                payloadJson));
  }

  /** 决策结果里某条令的翻转**目标终态**。 */
  private static String flipStatusOf(JsonNode value, String directiveId) {
    return flipRow(value, directiveId).get("status").asText();
  }

  /** 决策结果里某条令的翻转**结局**（{@code applied}/{@code rejected}）。 */
  private static String flipResultOf(JsonNode value, String directiveId) {
    return flipRow(value, directiveId).get("result").asText();
  }

  private static JsonNode flipRow(JsonNode value, String directiveId) {
    for (JsonNode row : value.get("flips")) {
      if (directiveId.equals(row.get("directiveId").asText())) {
        return row;
      }
    }
    throw new AssertionError("决策结果里没有 " + directiveId + " 的翻转: " + value);
  }

  private Set<String> namespacesOfRevision(long revision) {
    RevisionRow row =
        core().revisions(main()).stream()
            .filter(r -> r.revision().value() == revision)
            .findFirst()
            .orElseThrow();
    return Timeline.readChangeSet(row.changesetJson()).modules().keySet();
  }

  private long head() {
    return core().head(main()).orElseThrow().value();
  }

  private CoreSimos core() {
    return shell.coreSimos();
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
    UnitState units =
        new UnitState(
            new LinkedHashMap<>(
                Map.of(
                    U1, genesisUnit(U1, "第一连", H11),
                    U2, genesisUnit(U2, "第二连", H13),
                    U3, genesisUnit(U3, "第三连", H21))));
    SocialData social =
        new SocialData(
            new LinkedHashMap<>(Map.of(H12, populationSeries(), H13, populationSeries())),
            Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, fourHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithNationArmyAndMakers())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(new MapCodec(), new SocialCodec(), new UnitCodec(), new SdCodec())));
  }

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1，视野 1）+ 三个决策人（国家 / 军队 / 被收紧的国家）。 */
  private static SdState sdWithNationArmyAndMakers() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_FRA, maker(DM_FRA, new Affiliation.Nation(fra), AccessLimit.empty()));
    makers.put(DM_ARMY, maker(DM_ARMY, new Affiliation.Army(a1), AccessLimit.empty()));
    makers.put(
        DM_NARROW,
        maker(
            DM_NARROW,
            new Affiliation.Nation(fra),
            AccessLimit.ofPrefixes(Map.of("unit", Set.<String>of()))));
    return SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
  }

  private static DecisionMaker maker(
      DecisionMakerId id, Affiliation affiliation, AccessLimit accessLimit) {
    return new DecisionMaker(id, affiliation, Set.of(), accessLimit, 1);
  }

  /** 四格世界 + 两个区域（FRA 覆盖 (1,1)/(1,2)、GER 覆盖 (1,3)）；(2,1) 无归属（中立格）。 */
  private static GameMap fourHexMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12, H21, H13)) {
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
    regions.put(
        new RegionId("201"),
        Region.of(
            new RegionId("201"),
            "区域 201",
            Set.of(H13),
            new RegionMeta(null, NationTag.tagFor(new NationId("GER")), null, null)));
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

  /** 战损无关的最小单位：自身带位置、无父、无路线、视野 1。 */
  private static Unit genesisUnit(UnitId id, String name, HexCoord position) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
