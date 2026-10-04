package io.mosire.simos.app.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T9 判据（spec §3.4/§4.2，取代 D4 的 {@code viewScope} 版本）：GUI 的 {@code as=} 路径**换了可见性来源**。
 *
 * <p>★★ **本用例的关键命题是"同源"**：{@link RedactingQueryService} 的可见性**不再**是"GM 存的那张可见集合表"，而是 **app 层范围函数现算
 * ∩ GM 额外限制**——与 MCP / 决策人路径**同一份**装配。故第一条用例是**逐格/逐单位对拍**：对每个实体，GUI 路径的判据必须与 {@link
 * DecisionCallerFactory} 那条路径的判据**逐值相同**。各写一份可见性规则时，两边都**不会报错**，只会慢慢漂移 ——对拍是唯一能发现它的形状。
 *
 * <p>★ **不再有"两个 scope 的决策人"那种夹具**：旧用例手搭两个 {@code ViewScope} 就能造出差异，新语义下可见性由**归属**决定 ⇒
 * 夹具改为"两个国家各占一个区域"（{@code alpha} / {@code beta}）。
 */
class RedactingQueryServiceTest {

  private static final String MAP_ID = ScopeFixtures.MAP_ID;
  private static final QueryTarget HEAD = QueryTarget.head(ScopeFixtures.MAIN);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final DecisionMakerId DM_ALPHA = new DecisionMakerId("dm-alpha");
  private static final DecisionMakerId DM_BETA = new DecisionMakerId("dm-beta");
  private static final DecisionMakerId DM_NARROWED = new DecisionMakerId("dm-narrowed");
  private static final DecisionMakerId DM_WIDEN_ATTEMPT = new DecisionMakerId("dm-widen");
  private static final DecisionMakerId DM_EMPTY_INTERSECTION = new DecisionMakerId("dm-empty");
  private static final DecisionMakerId DM_NO_UNIT = new DecisionMakerId("dm-no-unit");
  private static final DecisionMakerId DM_ARMY = new DecisionMakerId("dm-army");
  private static final DecisionMakerId DM_FULL = new DecisionMakerId("dm-full");
  private static final DecisionMakerId DM_PERCEPTION = new DecisionMakerId("dm-perception");
  private static final DecisionMakerId DM_WITHHELD = new DecisionMakerId("dm-withheld");
  private static final DecisionMakerId DM_REDACT_POSITION = new DecisionMakerId("dm-redact");
  private static final VerdictId V1 = new VerdictId("v-1");

  @TempDir java.nio.file.Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  // ── ★ 同源：GUI 路径与决策人路径的判据逐值相同 ───────────────────────────────────────

  @Test
  void guiVisibilityAgreesWithTheMcpReadToolsForEveryEntity() {
    RedactingQueryService service = start();
    QueryService query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
    ToolRegistry registry = new ToolRegistry();
    registry.register(new MapHexTool(query, MAP_ID));
    registry.register(new UnitGetTool(query));
    DecisionCallerFactory factory = DecisionCallerFactory.defaults(authorizer());
    SimulationState state = stateAt(1);
    var map = ToolSupport.gameMap(state);

    int seen = 0;
    for (HexCoord coord : map.hexes().keySet()) {
      boolean viaGui = service.seesHex(DM_ALPHA, HEAD, coord);
      boolean viaTool =
          factory
              .execute(
                  registry,
                  MapHexTool.NAME,
                  decisionMakerOf(DM_ALPHA),
                  state,
                  MAP_ID,
                  Map.of("q", coord.q(), "r", coord.r()))
              .success();
      assertThat(viaGui)
          .as("★ hex %s：GUI 的 as= 与 MCP 的 simos.map.hex 必须同判（%s）", coord, viaTool)
          .isEqualTo(viaTool);
      if (viaGui) {
        seen++;
      }
    }
    assertThat(seen).as("★ 至少有一格是可见的——否则两边'都是 false'，对拍退化成恒真").isGreaterThan(0);

    for (UnitId unit : ToolSupport.unitState(state).units().keySet()) {
      boolean viaGui = service.seesUnit(DM_ALPHA, HEAD, unit);
      boolean viaTool =
          factory
              .execute(
                  registry,
                  UnitGetTool.NAME,
                  decisionMakerOf(DM_ALPHA),
                  state,
                  MAP_ID,
                  Map.of("id", unit.value()))
              .success();
      assertThat(viaGui).as("★ 单位 %s：两条路的判据必须一致（%s）", unit, viaTool).isEqualTo(viaTool);
    }
  }

  // ── 可见性由范围函数现算 ─────────────────────────────────────────────────────────────

  @Test
  void twoAffiliationsSeeDifferentHexesOnTheSameEndpoint() {
    RedactingQueryService service = start();

    Map<String, Object> seenByAlpha = service.mapOverview(DM_ALPHA, HEAD);
    Map<String, Object> seenByBeta = service.mapOverview(DM_BETA, HEAD);

    assertThat(hexes(seenByAlpha)).containsExactlyInAnyOrder("1_1", "1_3");
    assertThat(hexes(seenByBeta)).containsExactly("1_2");
    assertThat(seenByAlpha).as("两个归属的响应必须不同（去掉范围函数即成恒等）").isNotEqualTo(seenByBeta);
  }

  @Test
  void unitVisibilityFollowsTheScope() {
    RedactingQueryService service = start();

    assertThat(service.units(DM_ALPHA, HEAD))
        .extracting(view -> view.get("id"))
        .containsExactly("u-a");
    assertThat(service.units(DM_BETA, HEAD))
        .extracting(view -> view.get("id"))
        .containsExactly("u-b");
  }

  /** 军队决策人的范围是**视野圈**（纯几何、逐格前缀），与国家的区域级前缀是两种货币。 */
  @Test
  void armyAffiliationSeesItsVisionCircleNotItsNation() {
    RedactingQueryService service = start();

    assertThat(service.seesHex(DM_ARMY, HEAD, H11)).as("自己那一格").isTrue();
    assertThat(service.seesHex(DM_ARMY, HEAD, H12)).as("半径 1 的邻格也进范围（纯几何，不看归属）").isTrue();
    assertThat(service.seesHex(DM_ALPHA, HEAD, H12)).as("国家决策人看不到 beta 的格").isFalse();
    assertThat(service.seesHex(DM_ARMY, HEAD, new HexCoord(1, 4))).as("半径之外（距离 3）").isFalse();
  }

  /** actor 不存在 ⇒ 什么都看不见（fail-closed）。 */
  @Test
  void unknownActorIsFailClosedToEmptyScope() {
    RedactingQueryService service = start();
    DecisionMakerId ghost = new DecisionMakerId("ghost");

    assertThat(service.accessLimitOf(ghost, HEAD).prefixesByNamespace()).isEmpty();
    assertThat(service.readContextOf(ghost, HEAD)).as("解不出决策人 ⇒ 没有上下文（不是'空限制'）").isEmpty();
    assertThat(hexes(service.mapOverview(ghost, HEAD))).isEmpty();
    assertThat(service.units(ghost, HEAD)).isEmpty();
    assertThat(service.seesHex(ghost, HEAD, H11)).isFalse();
  }

  // ── ★ accessLimit：交集（只能收紧、不能放大）────────────────────────────────────────

  @Test
  void gmAccessLimitNarrowsTheComputedScope() {
    RedactingQueryService service = start();

    assertThat(hexes(service.mapOverview(DM_NARROWED, HEAD)))
        .as("范围函数给 {r1,r3}，GM 限制只留 r1 ⇒ 只剩 r1 的格")
        .containsExactly("1_1");
    assertThat(service.mapOverview(DM_NARROWED, HEAD))
        .as("收紧是真的收（不是把 GM 的限制当装饰）")
        .isNotEqualTo(service.mapOverview(DM_ALPHA, HEAD));
  }

  /**
   * ★★ **本任务的核心语义**：GM 的限制与范围函数**求交**，**不能放大**。
   *
   * <p>GM 声明 {@code map: ["demo"]}（比范围函数宽得多）。写成"**覆盖**"的实现会让这个决策人看见整张图（19 格）＋ beta 的格 ——那正是被取代的
   * {@code viewScope} 语义（GM 绝对指定）。求交的实现只保留范围函数给的两条区域前缀。
   */
  @Test
  void gmAccessLimitCannotWidenTheComputedScope() {
    RedactingQueryService service = start();

    assertThat(hexes(service.mapOverview(DM_WIDEN_ATTEMPT, HEAD)))
        .as("★ GM 说 map:['demo']，但范围函数只给 r1/r3 ⇒ 交集仍是那两条")
        .containsExactlyInAnyOrder("1_1", "1_3");
    assertThat(service.seesHex(DM_WIDEN_ATTEMPT, HEAD, H12)).as("★ 别国的格不会因为 GM 配得宽而露出来").isFalse();
  }

  /** 交集为空 ⇒ **哪里都不许**（不是回落到"不表态"= 放行，spec §5.2 第 3 条）。 */
  @Test
  void anEmptyIntersectionDeniesRatherThanFallsBackToUnrestricted() {
    RedactingQueryService service = start();

    assertThat(hexes(service.mapOverview(DM_EMPTY_INTERSECTION, HEAD))).isEmpty();
    assertThat(service.seesRegion(DM_EMPTY_INTERSECTION, HEAD, new RegionId("r1"))).isFalse();
    assertThat(service.seesRegion(DM_EMPTY_INTERSECTION, HEAD, new RegionId("r3"))).isFalse();
    assertThat(service.units(DM_EMPTY_INTERSECTION, HEAD))
        .as("★ 收紧是**按命名空间各自**的：GM 只收窄了 map，unit 那一维照旧（别把'某个维度空'读成'全都空'）")
        .hasSize(1);
  }

  /** 收窄 **unit** 命名空间（{@code []} = 显式"够不着"）：单位没了，格还在。 */
  @Test
  void narrowingTheUnitNamespaceHidesUnitsButNotHexes() {
    RedactingQueryService service = start();

    assertThat(service.units(DM_NO_UNIT, HEAD)).isEmpty();
    assertThat(hexes(service.mapOverview(DM_NO_UNIT, HEAD)))
        .as("map 那一维没被收紧 ⇒ 格照旧可见")
        .containsExactlyInAnyOrder("1_1", "1_3");
  }

  // ── adjudicationDisclosure 与 redactedFields 由 accessLimit 承载 ──────────────────────

  @Test
  void withheldDisclosureHidesEveryVerdict() {
    RedactingQueryService service = start();

    assertThat(service.verdicts(DM_WITHHELD, HEAD)).as("WITHHELD ⇒ 判决整条不出现（空列表，不是空串）").isEmpty();
    assertThat(service.verdicts(DM_FULL, HEAD)).as("FULL ⇒ 有判决").isNotEmpty();
    assertThat(service.verdicts(DM_PERCEPTION, HEAD)).isNotEmpty();
  }

  @Test
  void perceptionOnlyDropsTheNonObservableVerdictFields() {
    RedactingQueryService service = start();

    Map<String, Object> full = service.verdicts(DM_FULL, HEAD).get(0);
    Map<String, Object> perception = service.verdicts(DM_PERCEPTION, HEAD).get(0);

    assertThat(full).as("FULL 含模型输出与 meta").containsKeys("payload", "meta");
    assertThat(perception)
        .as("PERCEPTION_ONLY 只留可观察项，去掉不可感知的模型内部量")
        .containsKeys("id", "breakpoint", "subject", "atRevision")
        .doesNotContainKeys("payload", "meta");
    assertThat(perception.get("id")).isEqualTo(full.get("id"));
  }

  @Test
  void verdictsWithoutActorAreFullDisclosure() {
    RedactingQueryService service = start();

    assertThat(service.verdicts(HEAD)).hasSize(1);
    assertThat(service.verdicts(HEAD).get(0)).containsKeys("payload", "meta");
  }

  @Test
  void redactedFieldsRemovesTheNamedFieldFromUnitViews() {
    RedactingQueryService service = start();

    List<Map<String, Object>> redacted = service.units(DM_REDACT_POSITION, HEAD);
    assertThat(redacted).as("单位仍可见").hasSize(1);
    assertThat(redacted.get(0))
        .as("position 被按名剔除")
        .doesNotContainKey("position")
        .containsKey("id");

    List<Map<String, Object>> plain = service.units(DM_FULL, HEAD);
    assertThat(plain.get(0)).as("未声明 redactedFields 的 actor 仍有 position").containsKey("position");
  }

  @Test
  @SuppressWarnings("unchecked")
  void redactedFieldsRecursesIntoNestedLists() {
    RedactingQueryService service = start();
    AccessLimit limit = new AccessLimit(Map.of(), Set.of("q"), DisclosurePolicy.FULL);

    Object stripped =
        service.applyRedactedFields(Map.of("hexes", List.of(Map.of("q", 1, "r", 2))), limit);

    Map<String, Object> body = (Map<String, Object>) stripped;
    List<Map<String, Object>> nested = (List<Map<String, Object>>) body.get("hexes");
    assertThat(nested.get(0)).as("嵌套列表里的 q 也被剔除").doesNotContainKey("q").containsKey("r");
  }

  // ── 装配 ────────────────────────────────────────────────────────────────────────────

  private RedactingQueryService start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new MapCodec());
    core.register(new UnitCodec());
    core.register(new SdCodec());
    // ★ S3a：unit 读口（RedactingQueryService.units/UnitGetTool）现在经 SocialLookupAdapter 取 households/population
    //   ⇒ 夹具必须有 social 切片，否则会按"装配故障"抛（生产状态恒有该切片）。
    core.register(new SocialCodec());
    core.bootstrapGenesis(genesis());
    QueryService query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
    return new RedactingQueryService(query, DecisionScopeFunctions.defaults(), MAP_ID);
  }

  /** 从夹具 sd 切片取决策人（工具路径要的是对象，不是 id）。 */
  private static DecisionMaker decisionMakerOf(DecisionMakerId id) {
    return sdState().decisionMakers().get(id);
  }

  /** 创世状态（坐标形如 {@code (main, 1)}）。 */
  private SimulationState stateAt(long revision) {
    return core.replay(new StateRef(ScopeFixtures.MAIN, new RevisionId(revision)));
  }

  /** 决策人路径的执行器（同源对拍的对照组）——走真壳那套审批装配（{@code AutoApproveGate}）。 */
  private static ToolCallAuthorizer authorizer() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending)), List.of(), pending, Duration.ofMinutes(1), null);
    return ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator);
  }

  @SuppressWarnings("unchecked")
  private static List<String> hexes(Map<String, Object> overview) {
    List<Map<String, Object>> hexes = (List<Map<String, Object>>) overview.get("hexes");
    List<String> out = new ArrayList<>();
    for (Map<String, Object> hex : hexes) {
      out.add(hex.get("q") + "_" + hex.get("r"));
    }
    return out;
  }

  private static SimulationState genesis() {
    return withEmptySocial(ScopeFixtures.state(map(), units(), sdState()));
  }

  /** ★ S3a：给夹具补一个（空的）social 切片——单位读口经 SocialLookupAdapter 取 households/population 时需要它。 */
  private static SimulationState withEmptySocial(SimulationState base) {
    Map<String, Snapshot> slices = new LinkedHashMap<>(base.modules());
    slices.put(
        "social",
        new SocialSnapshot(base.meta().ref(), base.meta().timestamp(), SocialData.empty()));
    return new SimulationState(base.meta(), slices, base.info());
  }

  private static SdState sdState() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_ALPHA, nationDm("dm-alpha", "alpha", AccessLimit.empty()));
    makers.put(DM_BETA, nationDm("dm-beta", "beta", AccessLimit.empty()));
    makers.put(
        DM_NARROWED,
        nationDm("dm-narrowed", "alpha", Map.of("map", Set.of(MAP_ID + "/region/r1"))));
    makers.put(DM_WIDEN_ATTEMPT, nationDm("dm-widen", "alpha", Map.of("map", Set.of(MAP_ID))));
    makers.put(
        DM_EMPTY_INTERSECTION,
        nationDm("dm-empty", "alpha", Map.of("map", Set.of(MAP_ID + "/region/r2"))));
    makers.put(DM_NO_UNIT, nationDm("dm-no-unit", "alpha", Map.of("unit", Set.of())));
    makers.put(
        DM_ARMY,
        new DecisionMaker(
            DM_ARMY,
            new Affiliation.Army(new io.mosire.simos.sd.id.ArmyId("a1")),
            Set.of(),
            AccessLimit.empty(),
            1));
    makers.put(DM_FULL, nationDm("dm-full", "alpha", disclosure(DisclosurePolicy.FULL, Set.of())));
    makers.put(
        DM_PERCEPTION,
        nationDm("dm-perception", "alpha", disclosure(DisclosurePolicy.PERCEPTION_ONLY, Set.of())));
    makers.put(
        DM_WITHHELD,
        nationDm("dm-withheld", "alpha", disclosure(DisclosurePolicy.WITHHELD, Set.of())));
    makers.put(
        DM_REDACT_POSITION,
        nationDm("dm-redact", "alpha", disclosure(DisclosurePolicy.FULL, Set.of("position"))));
    Map<VerdictId, Verdict> verdicts = new LinkedHashMap<>();
    verdicts.put(V1, verdict("v-1"));
    return ScopeFixtures.sdWithArmy("a1", "alpha", "u-a")
        .withDecisionMakers(makers)
        .withVerdicts(verdicts);
  }

  /** 只改披露口径与字段剔除、**不额外收紧资源**（空前缀图 = 不表态）。 */
  private static AccessLimit disclosure(DisclosurePolicy policy, Set<String> redacted) {
    return new AccessLimit(Map.of(), redacted, policy);
  }

  private static DecisionMaker nationDm(
      String id, String nationId, Map<String, Set<String>> prefixes) {
    return nationDm(id, nationId, AccessLimit.ofPrefixes(prefixes));
  }

  private static DecisionMaker nationDm(String id, String nationId, AccessLimit limit) {
    return new DecisionMaker(
        new DecisionMakerId(id),
        new Affiliation.Nation(new NationId(nationId)),
        Set.of(),
        limit,
        1);
  }

  private static Verdict verdict(String id) {
    Address subject = new Address(List.of(new Namespace("sd"), Entity.of("combat", "c1")));
    return new Verdict(
        new VerdictId(id),
        new AdjudicationBreakpoint("D1"),
        subject,
        "{\"rationaleText\":\"理由\",\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[]}",
        new VerdictMeta("model-x", "prompt-v1", "digest-abc"),
        new RevisionId(7));
  }

  private static io.mosire.simos.map.GameMap map() {
    return ScopeFixtures.mapOf(
        ScopeFixtures.nationRegion("r1", "alpha", H11),
        ScopeFixtures.nationRegion("r3", "alpha", H13),
        ScopeFixtures.nationRegion("r2", "beta", H12));
  }

  private static io.mosire.simos.unit.UnitState units() {
    return ScopeFixtures.units(
        ScopeFixtures.unitWithVision("u-a", H11, 1), ScopeFixtures.unitWithVision("u-b", H12, 1));
  }
}
