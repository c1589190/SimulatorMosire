package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.time.Duration;
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
 * ★★ **第 3 波第 3 步的真实验收面**：{@code sd.DecisionResults}——决策人**只看到与自己有关**的决策结果。
 *
 * <p>★ **判别力来自"两个决策人同 tick 各持一条"**：{@code sd:adjudication.7} 下**挂两条**——一条 tags 只含 {@code dm-a}、一条只含
 * {@code dm-b}。"都能看到"或"都看不到"的实现在 {@link #twoDecisionMakersInTheSameTickEachSeeOnlyTheirOwnResult}
 * 上当场红。
 *
 * <p>★ **空/无主/越界三类都不是靠"实现恰好没给"证**：每条判据先断言那条 INFO 条目**确实在状态里**（{@link #allInfoIds()}），
 * 再断言它**不出现**在结果里——否则"过滤器坏了"与"夹具里根本没那条"分不开。
 *
 * <p>★ **全程走 {@link DecisionCallerFactory} 的唯一入口**（真身份 + 真权限组 + 资源前置闸），不手工拼 {@code ToolContext} 直接
 * {@code execute}——后者会让每个工具在"没有判定者"的 fail-closed 缺省上被判否。
 *
 * <p>★ **夹具不借生产代码当期望值**：INFO 条目的 tags / tick / 值都是字面量；只有地址前缀与 id 合成引用生产常量/助手（它们是"数据格式"的
 * 唯一出处，照写一份才是漂移源）。
 */
class DecisionResultsToolTest {

  private static final String MAP_ID = ScopeFixtures.MAP_ID;
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final DecisionMakerId DM_A = new DecisionMakerId("dm-a");
  private static final DecisionMakerId DM_B = new DecisionMakerId("dm-b");
  private static final DecisionMakerId DM_C = new DecisionMakerId("dm-c");

  private static final String ADDR_7 = address(7);
  private static final String ADDR_8 = address(8);
  private static final String ADDR_9 = address(9);

  /** 一条**不是**决策结果的** INFO 条目（证明本工具不是把整个 INFO 层吐出来）。 */
  private static final String DIRECTIVE_ADDR = "sd:directive.d-1";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir java.nio.file.Path tempDir;

  private CoreSimos core;
  private QueryService queryService;

  @BeforeEach
  void start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new MapCodec());
    core.register(new UnitCodec());
    core.register(new SdCodec());
    core.bootstrapGenesis(genesis());
    queryService = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
  }

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  // ── 判据一：同一 tick 两个决策人，各自只看到与自己标签相符的结果 ───────────────────────────

  @Test
  void twoDecisionMakersInTheSameTickEachSeeOnlyTheirOwnResult() throws Exception {
    JsonNode a = body(call(DM_A, Map.of()));
    JsonNode b = body(call(DM_B, Map.of()));

    assertThat(ids(a))
        .as("★ A 看到：tick8 那条共同结果 + tick7 那条自己的（tick 降序）")
        .containsExactly(idOf(ADDR_8, 0), idOf(ADDR_7, 0));
    assertThat(ids(b))
        .as("★ B 看到：tick8 那条共同结果 + tick7 那条自己的——与 A 的 tick7 不是同一条")
        .containsExactly(idOf(ADDR_8, 0), idOf(ADDR_7, 1));
    assertThat(ids(a)).as("★ A **看不到**别家在同一个 tick 上的结果").doesNotContain(idOf(ADDR_7, 1));
    assertThat(ids(b)).as("★ B **看不到**别家在同一个 tick 上的结果").doesNotContain(idOf(ADDR_7, 0));
  }

  // ── 判据二：无主（tags 空）对决策人不可见 ─────────────────────────────────────────────

  @Test
  void anUnownedResultIsInvisibleToEveryDecisionMaker() throws Exception {
    assertThat(allInfoIds()).as("前提：无主条目确实在 INFO 层里（否则下面的『看不到』是假象）").contains(idOf(ADDR_9, 0));

    for (DecisionMakerId dm : List.of(DM_A, DM_B, DM_C)) {
      assertThat(ids(body(call(dm, Map.of()))))
          .as("★ %s 看不到无主结果（空集 ≠ 大家都能看）", dm.value())
          .doesNotContain(idOf(ADDR_9, 0));
    }
  }

  // ── 判据三：tick 过滤生效、跨 tick 取到多条、保序 ──────────────────────────────────────

  @Test
  void tickFilteringWorksAndResultsAreOrderedByTickDescending() throws Exception {
    JsonNode all = body(call(DM_A, Map.of()));
    assertThat(all.get("count").asInt()).as("跨 tick 取到多条").isEqualTo(2);
    List<Long> ticks = new ArrayList<>();
    for (JsonNode row : all.get("results")) {
      ticks.add(row.get("tick").asLong());
    }
    assertThat(ticks).as("★ 序 = tick 降序（新的在前）").containsExactly(8L, 7L);

    JsonNode only7 = body(call(DM_A, Map.of("tick", 7L)));
    assertThat(ids(only7)).as("tick 过滤：只留该 tick").containsExactly(idOf(ADDR_7, 0));

    JsonNode range = body(call(DM_A, Map.of("fromTick", 8L, "toTick", 99L)));
    assertThat(ids(range)).as("闭区间过滤").containsExactly(idOf(ADDR_8, 0));

    JsonNode limit1 = body(call(DM_A, Map.of("limit", 1L)));
    assertThat(ids(limit1)).as("★ 截断取的是**最近的**（先排后截）").containsExactly(idOf(ADDR_8, 0));
  }

  // ── 判据四：空结果 ⇒ 明确可读的"无"（不是静默成功）────────────────────────────────────

  @Test
  void aDecisionMakerWithNoResultsGetsAnExplicitEmptyAnswer() throws Exception {
    ToolResult result = call(DM_C, Map.of());

    assertThat(result.success()).as("空结果是一次**成功**调用（不是错误），但必须显式").isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("results")).isEmpty();
    assertThat(body.get("count").asInt()).isZero();
    assertThat(body.get("note").asText()).as("★ 明确可读的『没有可查看的决策结果』，不是静默成功").contains("没有可查看的决策结果");
  }

  // ── 判据五：越界/未授权地址拿不到（不是把整个 INFO 层吐出来）──────────────────────────

  @Test
  void onlyAdjudicationResultsAreReturnedNeverTheWholeInfoLayer() throws Exception {
    assertThat(allInfoIds()).as("前提：非裁决地址的条目确实在同一 INFO 层里").contains(idOf(DIRECTIVE_ADDR, 0));

    for (DecisionMakerId dm : List.of(DM_A, DM_B, DM_C)) {
      assertThat(ids(body(call(dm, Map.of()))))
          .as("★ %s 的结果里不得出现非裁决地址的条目", dm.value())
          .doesNotContain(idOf(DIRECTIVE_ADDR, 0));
    }
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  /** 走唯一入口执行一次工具调用：身份/权限组现算，参数自己装。 */
  private ToolResult call(DecisionMakerId dm, Map<String, Object> args) {
    SimulationState state = stateAtGenesis();
    ToolRegistry registry = new ToolRegistry();
    registry.register(new DecisionResultsTool(queryService, MAP_ID));
    return DecisionCallerFactory.defaults(authorizer())
        .execute(
            registry,
            DecisionResultsTool.NAME,
            state
                .module("sd")
                .map(slice -> ((SdSnapshot) slice).state())
                .orElseThrow()
                .decisionMakers()
                .get(dm),
            state,
            MAP_ID,
            args);
  }

  private static JsonNode body(ToolResult result) throws Exception {
    assertThat(result.success()).as("调用应成功：%s", result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  private static List<String> ids(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : body.get("results")) {
      out.add(row.get("id").asText());
    }
    return out;
  }

  /** INFO 层里**全部**条目的 id（"那条确实在状态里"的前提断言用）。 */
  private List<String> allInfoIds() {
    List<String> out = new ArrayList<>();
    for (List<SdInfoEntry> entries : sdState().info().values()) {
      for (SdInfoEntry entry : entries) {
        out.add(entry.id().value());
      }
    }
    return out;
  }

  private SdState sdState() {
    return ((SdSnapshot) stateAtGenesis().module("sd").orElseThrow()).state();
  }

  private SimulationState stateAtGenesis() {
    return core.replay(new StateRef(ScopeFixtures.MAIN, new RevisionId(1)));
  }

  /** 决策结果地址（与写路径同源：{@code sd:adjudication.<tick>} 的 canonical 形）。 */
  private static String address(long tick) {
    return Address.parse(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick).canonical();
  }

  private static String idOf(String canonicalAddress, int ordinal) {
    return SdInfoIds.synthesize(canonicalAddress, ordinal).value();
  }

  private static DecisionMaker nationDm(DecisionMakerId id, String nationId) {
    return new DecisionMaker(
        id, new Affiliation.Nation(new NationId(nationId)), Set.of(), AccessLimit.empty(), 1);
  }

  /** 决策人路径的执行器（本用例证的是可见性，不是审批链）：走真壳那套审批装配。 */
  private static ToolCallAuthorizer authorizer() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending)), List.of(), pending, Duration.ofMinutes(1), null);
    return ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /**
   * 世界：一个国家 {@code alpha}（区域 r1 覆盖 (1,1)）+ 军队 a1（根单位 u-a）。
   *
   * <p>INFO 层：
   *
   * <pre>
   *   sd:adjudication.7  ── #0 tags={dm-a}        （同一 tick 两条，各自的主人）
   *                        #1 tags={dm-b}
   *   sd:adjudication.8  ── #0 tags={dm-a, dm-b}  （共同涉及）
   *   sd:adjudication.9  ── #0 tags={}            （无主）
   *   sd:directive.d-1   ── #0 tags={dm-a}        （**不是**决策结果）
   * </pre>
   */
  private static SimulationState genesis() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_A, nationDm(DM_A, "alpha"));
    makers.put(DM_B, nationDm(DM_B, "alpha"));
    makers.put(DM_C, nationDm(DM_C, "alpha"));

    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>();
    info.put(
        ADDR_7,
        List.of(
            entry(ADDR_7, 0, 7, Set.of(DM_A), "A@7"), entry(ADDR_7, 1, 7, Set.of(DM_B), "B@7")));
    info.put(ADDR_8, List.of(entry(ADDR_8, 0, 8, Set.of(DM_A, DM_B), "AB@8")));
    info.put(ADDR_9, List.of(entry(ADDR_9, 0, 9, Set.of(), "unowned@9")));
    info.put(DIRECTIVE_ADDR, List.of(entry(DIRECTIVE_ADDR, 0, 7, Set.of(DM_A), "directive@7")));

    SdState sd =
        ScopeFixtures.sdWithArmy("a1", "alpha", "u-a").withDecisionMakers(makers).withInfo(info);
    return ScopeFixtures.state(
        ScopeFixtures.mapOf(ScopeFixtures.nationRegion("r1", "alpha", H11)),
        ScopeFixtures.units(ScopeFixtures.unitWithVision("u-a", H11, 1)),
        sd);
  }

  private static SdInfoEntry entry(
      String canonicalAddress, int ordinal, long tick, Set<DecisionMakerId> tags, String marker) {
    return new SdInfoEntry(
        SdInfoIds.synthesize(canonicalAddress, ordinal),
        tick,
        tags,
        "result",
        "{\"marker\":\"" + marker + "\"}",
        Optional.empty(),
        new RevisionId(1),
        Optional.empty());
  }
}
