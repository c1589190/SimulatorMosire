package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.query.QueryService.QueryTarget;
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
import io.mosire.simos.economy.codec.EconomyCodec;
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
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **T10 的真实验收面**（spec §5.2 第 1/2 条）：决策人**能不能真读到自己范围内的数据、能不能出令**。
 *
 * <p>与 {@code DecisionCallerFactoryTest} 的分工：那个类用**夹具工具**证"权限链本身"（资源判定者确实在拦）；本类**不再用夹具**，
 * 全程走**真装配**（真 {@code Shell} + 真 store + 真注册表 + 真读/写工具）——因为 T10 修的正是"真工具的断言粒度"这件事， 夹具工具永远证不出它。
 *
 * <p>★ **两条相反方向的判别力都要有**：
 *
 * <ul>
 *   <li>**越界进不来**：国家决策人读 GER 的格/单位 ⇒ 与"不存在"同款（{@code NOT_FOUND}）或干脆不进列表；
 *   <li>**范围内的进得来**：本国区域、军队视野圈内的数据必须**真的读得到**（只证"拒"会把"全拒"当成安全）。
 * </ul>
 *
 * <p>★ **两类决策人的可见集在夹具里**故意**分叉**（{@code (2,1)} 上的 {@code u-3}：军队决策人看得见、国家决策人看不见） ⇒
 * "把两类范围函数写成同一个"当场红。
 *
 * <p>★ 夹具世界（{@code Map1}，四格）：
 *
 * <pre>
 *   (1,1) u-1 ── 区域 701（nation:FRA）      (2,1) u-3 ── 无区域（中立格）
 *   (1,2)     ── 区域 701（nation:FRA）      (1,3) u-2 ── 区域 201（nation:GER）
 * </pre>
 *
 * 军队 {@code a1}（FRA、根单位 {@code u-1}、视野半径 1）⇒ 圈内 {1,1}/{1,2}/{2,1}（六角距离 ≤ 1），圈外 (1,3)。
 */
class DecisionMakerScopeEndToEndTest {

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

  private static final DecisionMaker DM_FRA = nationDm("dm-fra", "FRA");
  private static final DecisionMaker DM_ARMY = armyDm("dm-a1", "a1");

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

  // ── 读：地图总览逐区域 / 逐格筛 ─────────────────────────────────────────────────────

  /** 国家决策人的总览只剩本国区域与它的格——**不是**全量、也不是空。 */
  @Test
  void aNationDecisionMakerSeesOnlyItsOwnRegionsAndTheirHexesInTheOverview() throws Exception {
    JsonNode body = body(dmCall("simos.map.overview", DM_FRA, Map.of()));

    assertThat(regionIds(body)).as("只剩本国区域（701），GER 的 201 不进结果").containsExactly("701");
    assertThat(hexes(body)).as("只剩本国区域覆盖的格").containsExactlyInAnyOrder("1_1", "1_2");
    assertThat(body.get("hexCount").asInt()).as("★ 总数也是筛过的（回全量地图的格数会泄露地图规模）").isEqualTo(2);
  }

  /** ★ 反方向：不设限的调用者（GM 组同形）看到的仍是**整张图**——过滤不能变成"谁都只剩一点点"。 */
  @Test
  void anUnlimitedCallerStillSeesTheWholeMapInTheOverview() throws Exception {
    JsonNode body = body(callAsUnlimited("simos.map.overview", Map.of()));

    assertThat(regionIds(body)).containsExactlyInAnyOrder("701", "201");
    assertThat(hexes(body)).hasSize(4);
    assertThat(body.get("hexCount").asInt()).isEqualTo(4);
  }

  /** ★★ **不可见的格与不存在的格必须长得一模一样**：都 {@code NOT_FOUND}、**消息逐字相同**—— 否则"这个格存在但你看不到"这件事会从拒因里漏出去。 */
  @Test
  void aHexOutsideTheScopeIsIndistinguishableFromAMissingHex() {
    ToolResult outside = dmCall("simos.map.hex", DM_FRA, Map.of("q", 1, "r", 3));
    ToolResult missing = dmCall("simos.map.hex", DM_FRA, Map.of("q", 9, "r", 9));

    assertThat(outside.success()).as("GER 的格：不是成功").isFalse();
    assertThat(outside.code()).isEqualTo("NOT_FOUND");
    // ★ 拒因**逐字**是"格不存在"那一条模板（只带坐标）——不可见与不存在在消息面上分不开，
    //   否则"这个格存在但你看不到"会从拒因里漏出去。
    assertThat(outside.message()).isEqualTo("六角格不存在: 1_3");
    assertThat(missing.code()).isEqualTo("NOT_FOUND");
    assertThat(missing.message()).isEqualTo("六角格不存在: 9_9");
  }

  /** ★ 范围内的格**真的读得到**：国家决策人的范围是**区域级**前缀，故这里同时钉住"按区域放行它的格"那条通道。 */
  @Test
  void aHexInsideItsOwnRegionIsReadable() throws Exception {
    JsonNode body = body(dmCall("simos.map.hex", DM_FRA, Map.of("q", 1, "r", 1)));

    assertThat(body.get("terrain").asText()).isEqualTo("desert");
    assertThat(body.get("facets")).as("本国格上的 facet 照常给（非空）").isNotEmpty();
  }

  // ── 读：单位与人口 ────────────────────────────────────────────────────────────────

  /** 单位维：本国区域内的单位进得来，别国/中立格上的进不来；单查越界单位 ⇒ 与不存在同款。 */
  @Test
  void unitListAndUnitGetAreFilteredByTheNation() throws Exception {
    JsonNode listed = body(dmCall("simos.unit.list", DM_FRA, Map.of()));
    assertThat(unitIds(listed)).as("u-1 在 701 内；u-2 属 GER、u-3 在无区域的中立格").containsExactly("u-1");

    assertThat(dmCall("simos.unit.get", DM_FRA, Map.of("id", "u-1")).success()).isTrue();
    ToolResult foreign = dmCall("simos.unit.get", DM_FRA, Map.of("id", "u-2"));
    ToolResult missing = dmCall("simos.unit.get", DM_FRA, Map.of("id", "nope"));
    assertThat(foreign.code()).isEqualTo("NOT_FOUND");
    assertThat(foreign.message()).as("与「单位不存在」同一条模板（只带 id）").isEqualTo("单位不存在: u-2");
    assertThat(missing.message()).isEqualTo("单位不存在: nope");
  }

  /** 人口维（social 命名空间，与地图格**同格口径**）：本国格给数、别国格与"格不存在"同款。 */
  @Test
  void populationIsGatedByTheSameCellScopeAsTheMap() throws Exception {
    assertThat(
            body(dmCall("simos.social.population", DM_FRA, Map.of("q", 1, "r", 2)))
                .get("population"))
        .as("本国格上的人口读数照常给")
        .isNotNull();

    ToolResult outside = dmCall("simos.social.population", DM_FRA, Map.of("q", 1, "r", 3));
    ToolResult missing = dmCall("simos.social.population", DM_FRA, Map.of("q", 8, "r", 8));
    assertThat(outside.code()).isEqualTo("NOT_FOUND");
    assertThat(outside.message()).as("与「该格没有人口序列」同一条模板").isEqualTo("该格没有人口序列: 1_3");
    assertThat(missing.message()).isEqualTo("该格没有人口序列: 8_8");
  }

  // ── 读：两类决策人的可见集分叉（军队 = 视野圈）──────────────────────────────────────

  /** ★ 军队决策人：圈内的格与单位进得来；**圈外的本国区域**反而进不来（与上一条的国家决策人**方向相反**）。 */
  @Test
  void anArmyDecisionMakerSeesItsVisionCircleNotItsNationsRegions() throws Exception {
    assertThat(dmCall("simos.map.hex", DM_ARMY, Map.of("q", 2, "r", 1)).success())
        .as("(2,1) 在 u-1@(1,1) 的一环内 ⇒ 军队决策人看得见（国家决策人看不见，见上一条）")
        .isTrue();
    assertThat(dmCall("simos.map.hex", DM_ARMY, Map.of("q", 1, "r", 3)).code())
        .as("(1,3) 距 u-1 两环 ⇒ 圈外")
        .isEqualTo("NOT_FOUND");

    JsonNode listed = body(dmCall("simos.unit.list", DM_ARMY, Map.of()));
    assertThat(unitIds(listed))
        .as("u-1@(1,1) 与 u-3@(2,1) 在圈内；u-2@(1,3) 不在")
        .containsExactlyInAnyOrder("u-1", "u-3");
  }

  // ── 读：派生信息（T11）—— 邻国（J5）与逐格归属国家（J6）────────────────────────────

  /**
   * ★★ **判据 J5**：国家决策人的视图里带**邻国**——本国（FRA，区域 701 覆盖 {@code (1,1)/(1,2)}）与 GER 的区域 201（{@code
   * (1,3)}）在 {@code (1,2)}–{@code (1,3)} 这条边上六角相邻。
   */
  @Test
  void aNationDecisionMakerSeesItsNeighbouringNations() throws Exception {
    JsonNode body = body(dmCall("simos.map.overview", DM_FRA, Map.of()));

    assertThat(strings(body.get("neighbors")))
        .as("本国的邻国：GER（区域 201 与区域 701 六角相邻）")
        .containsExactly("GER");
  }

  /** ★★ **判据 J6**：军队决策人看到的每个格都带**归属国家**——它自己的范围是**逐格**前缀（没有任何区域级前缀）， 故这一项是它唯一能知道"这块地归谁"的途径。 */
  @Test
  void anArmyDecisionMakerSeesTheOwningNationOfEachVisibleHex() throws Exception {
    JsonNode own = body(dmCall("simos.map.hex", DM_ARMY, Map.of("q", 1, "r", 1)));

    assertThat(strings(own.get("nation"))).as("(1,1) 落在区域 701（nation:FRA）里").containsExactly("FRA");

    JsonNode neutral = body(dmCall("simos.map.hex", DM_ARMY, Map.of("q", 2, "r", 1)));
    assertThat(strings(neutral.get("nation"))).as("(2,1) 不属于任何区域 ⇒ 空列表（不是缺字段，也不是某个默认国家）").isEmpty();
  }

  /**
   * ★ **反方向**：{@code neighbors} 是**国家决策人**才有的字段（spec §3.4 的字段表）——军队决策人**不给**。
   *
   * <p>用**字段缺席**而不是空列表：`"你不是国家决策人"` 与 `"你没有邻国"` 是两件事，空列表会把后者当成唯一解释。
   */
  @Test
  void anArmyDecisionMakerGetsNoNeighbourField() throws Exception {
    JsonNode body = body(dmCall("simos.map.overview", DM_ARMY, Map.of()));

    assertThat(body.has("neighbors")).as("军队决策人那一行要的是逐格归属国家，不是邻国标识").isFalse();
    assertThat(body.get("hexes")).as("但总览本身照常给（只少一项，不是整调被拒）").isNotEmpty();
  }

  /** ★ 同上：不设限的调用者（GM）没有"本国"这个概念 ⇒ 也不给这一项。 */
  @Test
  void anUnlimitedCallerGetsNoNeighbourField() throws Exception {
    JsonNode body = body(callAsUnlimited("simos.map.overview", Map.of()));

    assertThat(body.has("neighbors")).as("GM 的视图没有决策人视角的派生字段").isFalse();
    assertThat(body.get("hexCount").asInt()).as("其余项逐字不变").isEqualTo(4);
  }

  /** ★ **不泄露**：越界格仍然只在"不存在"那条模板上被拒——归属国家不会从拒因或任何字段里漏出去。 */
  @Test
  void theOwningNationOfAnOutOfScopeHexIsNeverRevealed() {
    ToolResult outside = dmCall("simos.map.hex", DM_ARMY, Map.of("q", 1, "r", 3));

    assertThat(outside.code()).isEqualTo("NOT_FOUND");
    assertThat(outside.message()).as("与「格不存在」逐字同款，且不含任何国家信息").isEqualTo("六角格不存在: 1_3");
  }

  // ── 读：地址解析与 facet 不回落全量 ─────────────────────────────────────────────────

  /** ★ "解析不出/不可见 ⇒ 空"，**不许**回全量：越界区域的候选为空，本国区域的候选照常给。 */
  @Test
  void resolveDropsCandidatesOutsideTheScopeInsteadOfReturningEverything() throws Exception {
    assertThat(
            candidates(
                dmCall("simos.state.resolve", DM_FRA, Map.of("address", "map:Map1:region.701"))))
        .as("本国区域：解析得到")
        .hasSize(1);
    assertThat(
            candidates(
                dmCall("simos.state.resolve", DM_FRA, Map.of("address", "map:Map1:region.201"))))
        .as("★ 别国区域：候选为空（不是「照常给一条」）")
        .isEmpty();

    JsonNode own =
        body(dmCall("simos.state.facets", DM_FRA, Map.of("address", "map:Map1:hex.1_1")));
    assertThat(own.get("entries")).as("本国格的 facet 照常给").isNotEmpty();
    JsonNode foreign =
        body(dmCall("simos.state.facets", DM_FRA, Map.of("address", "map:Map1:hex.1_3")));
    assertThat(foreign.get("entries")).as("别国格的 facet：与非 canonical 地址同款（空）").isEmpty();
  }

  // ── 写：出令真的能成（本任务的核心验收）──────────────────────────────────────────────

  /**
   * ★★ **决策人今天出不了的令，现在要真的出得来**：{@code sd.IssueDirective} 经**真审批链**（AutoApprove）执行并 **落
   * revision**（head 前进、命令行与发起者逐值可读）。
   */
  @Test
  void aDecisionMakerCanActuallyIssueADirective() throws Exception {
    long headBefore = head();
    ToolResult result =
        dmCall("sd.IssueDirective", DM_FRA, directiveArgs(headBefore, "d1", "dm-fra"));

    assertThat(result.success()).as("出令必须成（拒因：%s）", result.message()).isTrue();
    assertThat(JSON.readTree(result.message()).get("result").asText()).isEqualTo("committed");
    assertThat(head()).as("落了真 revision").isEqualTo(headBefore + 1);

    RevisionRow row = latestRevision();
    assertThat(row.commandType()).isEqualTo("sd.IssueDirective");
    assertThat(row.initiator()).as("发起者仍是装配期定的那个（决策人不自报身份）").isEqualTo(INITIATOR);
  }

  /**
   * ★★ **不许冒名**（T11B 修的洞，spec N16「渠道不得冒称任意 actor」的同一族）：决策人 {@code dm-fra} 落一条**署名 {@code dm-ger}**
   * 的 directive ⇒ 拒。
   *
   * <p>★ **判别力在"head 不动"上**：这条载荷在**修之前是真的会提交成功**的（资源断言取自身份、不取自载荷 ⇒ 署名 B 照样通过）， 故"没留
   * revision"才是这条用例的承重断言；只断言 {@code success()==false} 会放过"换了别的原因拒"。
   */
  @Test
  void aDecisionMakerCannotSignSomeoneElsesName() throws Exception {
    long headBefore = head();
    ToolResult result =
        dmCall("sd.IssueDirective", DM_FRA, directiveArgs(headBefore, "d-forged", "dm-ger"));

    assertThat(result.success()).as("以别人名义落决策 ⇒ 拒").isFalse();
    assertThat(result.code()).isEqualTo("REJECTED");
    assertThat(result.message()).contains("dm-fra").contains("dm-ger");
    assertThat(head()).as("★ 被拒的写不留 revision（洞的形态就是这条 revision 落了盘）").isEqualTo(headBefore);
  }

  /**
   * ★ **反方向**：GM（身份不是决策人）**不受此限**——"GM 可以为任意决策人落决策"是既有的、有意的授权，不是漏洞。
   *
   * <p>缺了这一条，把校验写成"载荷署名必须存在且等于身份"就会把 GM 那条路一起堵死，而下面的用例全绿。
   */
  @Test
  void anUnlimitedCallerMaySignForAnyDecisionMaker() throws Exception {
    long headBefore = head();
    ToolResult result =
        callAsUnlimited("sd.IssueDirective", directiveArgs(headBefore, "d-by-gm", "dm-fra"));

    assertThat(result.success()).as("GM 为 dm-fra 落决策：照旧放行（拒因：%s）", result.message()).isTrue();
    assertThat(head()).isEqualTo(headBefore + 1);
  }

  /** ★ 反方向：不设限的调用者（GM）**照旧**能出令——写工具的资源声明换细之后 GM 侧行为不变。 */
  @Test
  void anUnlimitedCallerCanStillIssueADirective() throws Exception {
    long headBefore = head();
    ToolResult result =
        callAsUnlimited("sd.IssueDirective", directiveArgs(headBefore, "d2", "dm-fra"));

    assertThat(result.success()).as("GM 侧不得因为改细而变窄（拒因：%s）", result.message()).isTrue();
    assertThat(head()).isEqualTo(headBefore + 1);
  }

  // ── 写：拒因不许被工具自己吞掉 ─────────────────────────────────────────────────────

  /**
   * ★★ **T5-T8 发现 2 的护栏**：工具内的资源断言判否时，拒因必须以 {@code RESOURCE_DENIED} 到达调用方， **不是**被折成 {@code
   * TOOL_ERROR}（"命令提交失败"）——后者让模型以为"换个参数就行"，而正确结论是"换个资源"。
   */
  @Test
  void aResourceDenialInsideAWriteToolSurfacesAsResourceDeniedNotToolError() throws Exception {
    DecisionCallerFactory factory = factory();
    long headBefore = head();
    ToolContext own = factory.callerFor(DM_FRA, state(), MAP_ID);
    // ★ **同一身份、不同可达面**：sd 上够得着"别人的决策域"——**可达面非空 ⇒ 前置闸（命名空间级）放行**，
    //   于是判否只可能发生在**工具内那一次断言**上。这正是在测的那条路径（前置闸早就有它自己的用例）。
    ToolContext narrowed =
        new ToolContext(
            own.caller(),
            withDecisionScope(own.permissions(), "decision-maker/someone-else"),
            own.config(),
            directiveArgs(headBefore, "d3", "dm-fra"),
            own.identity());

    ToolResult result = factory.execute(shell.toolRegistry(), "sd.IssueDirective", narrowed);

    assertThat(result.success()).as("决策域被收紧 ⇒ 拒").isFalse();
    assertThat(result.code())
        .as("★ 拒因是资源级；折成 TOOL_ERROR 就等于把 AgentLib 特意分的两个码的意图丢了")
        .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(result.message()).contains("不在调用者的可达面内");
    assertThat(head()).as("被拒的写不留 revision").isEqualTo(headBefore);
  }

  /** 换掉某调用者的 sd 可达面（其余分量逐字保留）。 */
  private static AgentPermissionSet withDecisionScope(
      AgentPermissionSet base, String decisionDomain) {
    return AgentPermissionSet.builder(base.grantedToken())
        .allow(DecisionCallerFactory.WHITELIST.toArray(String[]::new))
        .sensitiveAllowed(true)
        .resourceScopes(
            base.resourceScopes()
                .withNamespace(ToolSupport.SD_NAMESPACE, ResourceScope.of(decisionDomain)))
        .build();
  }

  /**
   * ★★ **窄写基类的缺省资源声明**（T10 的钩子）：{@code unit.RenameUnit} 由一个"地图范围只有区域级前缀"的调用者发起 ⇒ 粗断言（{@code
   * map:Map1}）撞细围栏（{@code Map1/region/701}）⇒ **整调被拒**、不留 revision。
   *
   * <p>★ 与下一条（{@code simos.command.submit}）的分工：那条走**通用写**自己那一支的 {@code requireAllWrite}；
   * 这条走**窄写基类**的 {@code writeResources} 缺省实现。两条各被自己的变异体红——M3 实测：只留通用写那一条时，
   * "把缺省钩子改成空表"的变异体**存活**（说明窄写这一支当时是装饰）。
   *
   * <p>★ 判别力：缺省钩子若改成空表（= 什么都不声明）⇒ 这条命令会**真的提交成功**（head 前进）⇒ 用例红。
   */
  @Test
  void theDefaultWriteResourceDeclarationStillFencesNarrowWrites() throws Exception {
    DecisionCallerFactory factory = factoryWithExtraTools("unit.RenameUnit");
    long headBefore = head();
    ToolContext scoped = factory.callerFor(DM_FRA, state(), MAP_ID);

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}");
    args.put("branch", "main");
    args.put("expectedRevision", headBefore);

    ToolResult result =
        factory.execute(
            shell.toolRegistry(),
            "unit.RenameUnit",
            new ToolContext(
                scoped.caller(), scoped.permissions(), scoped.config(), args, scoped.identity()));

    assertThat(result.success()).as("粗断言（map:Map1）撞细围栏（区域级前缀）⇒ 整调被拒").isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(head()).as("被拒的写不留 revision").isEqualTo(headBefore);
  }

  /**
   * ★★ **两条决策窄写的声明面 + 缺省 fail-closed**（T10 变异轮实测出的真漏洞，M7 存活那一轮）：
   *
   * <ol>
   *   <li>声明面只剩 {@code sd}（不是三命名空间粗声明——那样受限调用者会**整调被拒**）；
   *   <li>★ **未表态 sd 的调用者写不了**：sd 的缺省策略是 {@code READ_ONLY}。若取 {@code UNRESTRICTED}， "调用者未表态"
   *       会回落到"放行"（spec §5.2 第 3 条：空 = 不表态 = 放行）⇒ **忘了给决策人配前缀反而拿到全量写权**， 失效方向是放宽。本用例把那条方向钉死。
   * </ol>
   *
   * <p>★ 两条工具都在这里过一遍：{@code sd.SubmitVerdict} 的端到端（真落判决）需要断点注册等一整套夹具， 本任务只覆盖到**资源声明与 fail-closed
   * 缺省**这一层（如实记在报告里）。
   */
  @Test
  void bothDecisionWritesDeclareOnlyTheDecisionNamespaceAndFailClosedWithoutIt() throws Exception {
    DecisionCallerFactory factory = factory();
    for (String name : List.of("sd.IssueDirective", "sd.SubmitVerdict")) {
      AgentTool tool = shell.toolRegistry().find(name).orElseThrow();
      assertThat(tool.resources().namespaces())
          .as("%s 只碰 sd（不是三命名空间粗声明——那样受限调用者会整调被拒）", name)
          .containsExactly(ToolSupport.SD_NAMESPACE);

      ToolResult result = factory.execute(shell.toolRegistry(), name, withoutSdScope());
      assertThat(result.success()).as("%s：未表态 sd ⇒ 不得写（fail-closed）", name).isFalse();
      assertThat(result.code())
          .as("%s：拒因是资源级（缺省策略 read-only），不是静默放行", name)
          .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
      assertThat(result.message()).contains("read-only");
    }
  }

  /**
   * 一个"没表态 sd"的决策人调用者：身份仍是 {@code decision-maker:dm-fra}（工具据此要它自己的决策域）， 但权限集里**没有 sd
   * 这一项**——于是判定只能落到工具的**缺省策略**上。
   */
  private ToolContext withoutSdScope() {
    AgentPermissionSet noSd =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow(DecisionCallerFactory.WHITELIST.toArray(String[]::new))
            .sensitiveAllowed(true)
            .build();
    ToolContext bare =
        ToolContext.of(
            AccessToken.DEFAULT,
            noSd,
            AgentIdentity.subagent(
                DecisionCallerFactory.INSTANCE_ID_PREFIX + "dm-fra",
                CommandMode.LIMITED,
                DecisionCallerFactory.GOAL,
                1));
    return new ToolContext(
        bare.caller(), bare.permissions(), bare.config(), writeArgs(head()), bare.identity());
  }

  /** 一条窄写的通用参数（资源判定在载荷解析**之前**，故载荷内容与本用例无关）。 */
  private static Map<String, Object> writeArgs(long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", "{}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /** ★ 同一条的**通用写**那一支：{@code simos.command.submit} 走的是它自己那份 {@code requireAllWrite}。 */
  @Test
  void theDefaultWriteResourceDeclarationStillFencesGenericWrites() throws Exception {
    DecisionCallerFactory factory = factoryWithExtraTools("simos.command.submit");
    long headBefore = head();
    ToolContext scoped = factory.callerFor(DM_FRA, state(), MAP_ID);

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "unit.RenameUnit");
    args.put("payloadJson", "{\"id\":\"u-1\",\"name\":\"改名后的第一连\"}");
    args.put("branch", "main");
    args.put("expectedRevision", headBefore);

    ToolResult result =
        factory.execute(
            shell.toolRegistry(),
            "simos.command.submit",
            new ToolContext(
                scoped.caller(), scoped.permissions(), scoped.config(), args, scoped.identity()));

    assertThat(result.success()).as("粗断言（map:Map1）撞细围栏（区域级前缀）⇒ 整调被拒").isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(head()).as("被拒的写不留 revision").isEqualTo(headBefore);
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private static Map<String, Object> directiveArgs(
      long expectedRevision, String directiveId, String dmId) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put(
        "payloadJson",
        "{\"directiveId\":\""
            + directiveId
            + "\",\"decisionMakerId\":\""
            + dmId
            + "\",\"tick\":7,\"intentInfo\":\"向北推进\",\"commands\":[]}");
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /** 决策人经**唯一入口**（{@link DecisionCallerFactory#execute}）执行一条真工具。 */
  private ToolResult dmCall(String toolName, DecisionMaker dm, Map<String, Object> args) {
    return factory().execute(shell.toolRegistry(), toolName, dm, state(), MAP_ID, args);
  }

  /** 不设限的调用者（与 GM 组同形：四个命名空间各自 unlimited）经同一张注册表执行。 */
  private ToolResult callAsUnlimited(String toolName, Map<String, Object> args) {
    AgentTool tool = shell.toolRegistry().find(toolName).orElseThrow();
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
    ToolContext context =
        new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args)
            .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
    return tool.execute(context);
  }

  /**
   * 带审批编排器的调用者工厂（**不是** {@code standard()}：那条路遇到 {@code Ask} 一律拒）——两条决策窄写是敏感工具，
   * 没有编排器就永远进不了工具体，测到的只会是 {@code APPROVAL_DENIED} 而**不是**资源维。
   */
  private static DecisionCallerFactory factory() {
    return factoryWithExtraTools();
  }

  private static DecisionCallerFactory factoryWithExtraTools(String... extraTools) {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(),
            List.of(new AutoAnsweringChannel(pending)),
            pending,
            Duration.ofSeconds(5),
            null);
    Set<String> whitelist = new LinkedHashSet<>(DecisionCallerFactory.WHITELIST);
    whitelist.addAll(List.of(extraTools));
    return new DecisionCallerFactory(
        DecisionScopeFunctions.defaults(),
        ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator),
        whitelist);
  }

  /**
   * 测试用的审批通道：**登记后立刻按人答"批一次"**。
   *
   * <p>★ 为什么不用 {@code grantSession}：AgentLib 明确要求"任何直接调它的旁路也必须守同一条规则"（{@code callerKey}
   * 的粒度是**身份桶**， 给非唯一身份记会话放行等于把 A 的批准白送给同桶的 B）。这里改走**通道 + 登记表**这条正道——与人点"批准"是同一条路。
   *
   * <p>★ 审批机制本身**不是本任务的对象**（它早已有护栏）；这里只需它不挡路，好让用例测到资源维。
   */
  private static final class AutoAnsweringChannel implements ApprovalChannel {

    private final PendingApprovals pending;

    AutoAnsweringChannel(PendingApprovals pending) {
      this.pending = pending;
    }

    @Override
    public String name() {
      return "test:auto";
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public void publish(ApprovalRequest req) {
      // 约定 3：答复一律经登记表落决议，通道不凭自己的内部状态返回决定
      pending.decide(req.id(), ApprovalDecision.APPROVE_ONCE, name());
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      return pending.await(id, wait);
    }
  }

  private SimulationState state() {
    return shell.queryService().stateAt(QueryTarget.head(main()));
  }

  private static JsonNode body(ToolResult result) throws Exception {
    assertThat(result.success()).as("期望成功，实际：%s %s", result.code(), result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  private static List<String> regionIds(JsonNode overview) {
    return values(overview.get("regions"), "id");
  }

  private static List<String> hexes(JsonNode overview) {
    return fields(overview.get("hexes"));
  }

  private static List<String> unitIds(JsonNode listed) {
    return values(listed.get("units"), "id");
  }

  private static List<String> candidates(ToolResult resolve) throws Exception {
    return JSON.readTree(resolve.message()).get("candidates").findValuesAsText("canonicalAddress");
  }

  /** 一个 JSON 字符串数组的逐值读出（T11：{@code neighbors} 是字符串数组，不是对象数组）。 */
  private static List<String> strings(JsonNode array) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode item : array) {
      out.add(item.asText());
    }
    return out;
  }

  private static List<String> values(JsonNode array, String field) {
    return array.findValuesAsText(field);
  }

  private static List<String> fields(JsonNode array) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode item : array) {
      out.add(item.get("q").asInt() + "_" + item.get("r").asInt());
    }
    return out;
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private RevisionRow latestRevision() {
    return shell.coreSimos().revisions(main()).stream()
        .filter(row -> row.revision().value() == head())
        .findFirst()
        .orElseThrow();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static DecisionMaker nationDm(String dmId, String nationId) {
    return maker(dmId, new Affiliation.Nation(new NationId(nationId)));
  }

  private static DecisionMaker armyDm(String dmId, String armyId) {
    return maker(dmId, new Affiliation.Army(new ArmyId(armyId)));
  }

  private static DecisionMaker maker(String dmId, Affiliation affiliation) {
    return new DecisionMaker(
        new DecisionMakerId(dmId), affiliation, Set.of(), AccessLimit.empty(), 1);
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
            Map.of(),
            Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, fourHexMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                "sd", new SdSnapshot(ref("main", 1), T7, sdWithOneNationOneArmy()),
                // ★ R2：人口读口现在还要一片 economy（T4 的劳动分配维 / T0 的 `source` 在同一响应里）⇒
                //   服务该端点的世界必须装配经济切片 —— 与 unit/sd 同款（缺切片 = 装配故障，不静默兜底）。
                //   ★ 本夹具给**未激活**的空经济（`EconomyData.empty()`）：它足够回答"这一格的劳动配额为空"，
                //     而本类的判据是**权限**（可见/不可见），不是劳动的数字。
                "economy", new EconomySnapshot(ref("main", 1), T7, EconomyData.empty())),
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

  /** 一个国家（FRA，区域 701）+ 一条军队（a1，根单位 u-1）+ 两个决策人（国家 / 军队各一）。 */
  private static SdState sdWithOneNationOneArmy() {
    NationId fra = new NationId("FRA");
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(fra, new Nation(fra, "国家 FRA", new RegionId("701"), 0));
    ArmyId a1 = new ArmyId("a1");
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(a1, new Army(a1, fra, U1, "军队 a1"));
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_FRA.id(), DM_FRA);
    makers.put(DM_ARMY.id(), DM_ARMY);
    return SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
  }

  /** 四格世界 + 两个区域（FRA 覆盖 (1,1)/(1,2)、GER 覆盖 (1,3)）；(2,1) 无归属（中立格）。 */
  private static GameMap fourHexMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord coord : List.of(H11, H12, H21, H13)) {
      hexes.put(coord, new HexCell(0.5));
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    Region fra =
        Region.of(
            new RegionId("701"),
            "区域 701",
            Set.of(H11, H12),
            new RegionMeta(null, NationTag.tagFor(new NationId("FRA")), null, null));
    Region ger =
        Region.of(
            new RegionId("201"),
            "区域 201",
            Set.of(H13),
            new RegionMeta(null, NationTag.tagFor(new NationId("GER")), null, null));
    regions.put(fra.id(), fra);
    regions.put(ger.id(), ger);
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

  /** 战损无关的最小单位：自身带位置、无父、无路线、视野缺省（1）。 */
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
