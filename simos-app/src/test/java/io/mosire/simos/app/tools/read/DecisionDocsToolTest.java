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
import io.mosire.simos.app.docs.DecisionDoc;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.write.AdjudicateTickTool;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
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
 * ★★ **Docs 系统的真实验收面**：{@code sd.DecisionDocs}——决策人**只看到发给自己的**设定文档，可见性 = **显式指派 （{@code tags}）∪
 * 归属自动（{@code affiliations}）**（用户 2026-09-23 裁定）。
 *
 * <p>★ **判别力来自"两条轴各一条、且有一个跨归属的共享文档"**：
 *
 * <pre>
 *   sd:doc.alpha-brief   tags={dm-a}          affiliations={}          （显式指派）
 *   sd:doc.shared-brief  tags={}              affiliations={alpha}     （归属自动）
 *   sd:doc.beta-brief    tags={}              affiliations={beta}      （归属自动，另一国）
 *   sd:doc.secret        tags={}              affiliations={}          （无主）
 *   sd:adjudication.7    tags={dm-a}                                   （**不是文档**）
 * </pre>
 *
 * 于是：{@code dm-a}（alpha）看到前两条、看不到后两条；{@code dm-c}（同为 alpha）**只**看到 {@code shared-brief}（证明归属轴
 * 真的在起作用，而不是"都能看到"）；{@code dm-b}（beta）只看到 {@code beta-brief}（证明归属轴是**按归属**、不是全局放行）。
 *
 * <p>★ **无主（两轴都空）不是"大家都能看"**：{@code secret} 对三个人都不可见（fail-closed），且用例先断言它**确实在 INFO 层里** （{@code
 * allInfoIds()}）再断言它不出现——否则"过滤器坏了"与"夹具里根本没有"分不开。
 *
 * <p>★ **全程走 {@link DecisionCallerFactory} 的唯一入口**（真身份 + 真权限组 + 资源前置闸），不手工拼 {@code ToolContext}。
 */
class DecisionDocsToolTest {

  private static final String MAP_ID = ScopeFixtures.MAP_ID;
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final DecisionMakerId DM_A = new DecisionMakerId("dm-a"); // alpha
  private static final DecisionMakerId DM_B = new DecisionMakerId("dm-b"); // beta
  private static final DecisionMakerId DM_C = new DecisionMakerId("dm-c"); // alpha

  private static final String ADDR_ALPHA = DecisionDoc.addressOf("alpha-brief");
  private static final String ADDR_SHARED = DecisionDoc.addressOf("shared-brief");
  private static final String ADDR_BETA = DecisionDoc.addressOf("beta-brief");
  private static final String ADDR_SECRET = DecisionDoc.addressOf("secret");

  /** 一条**不是文档**的 INFO 条目（证明本工具不是把整个 INFO 层吐出来）。 */
  private static final String ADJUDICATION_ADDR = adjudicationAddress(7);

  /** 文档正文（约定是 JSON 文本）：**逐字节**比对用，故不含需要转义的字符。 */
  private static final String ALPHA_BODY = "{\"title\":\"Alpha 简报\",\"body\":\"第一行 / 第二行\"}";

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

  // ── 判据一：两轴取并集 ────────────────────────────────────────────────────────────

  @Test
  void aDecisionMakerSeesTheUnionOfItsTaggedDocsAndItsAffiliationsDocs() throws Exception {
    JsonNode body = body(call(DM_A, Map.of()));

    assertThat(docIds(body))
        .as("★ dm-a 看到：显式指派给自己的 + 按归属（alpha）自动可见的；序 = tick 降序")
        .containsExactly("shared-brief", "alpha-brief");
    assertThat(docIds(body)).as("★ 别的国家的归属文档与空归属文档都不出现").doesNotContain("beta-brief", "secret");
  }

  // ── 判据二：归属轴是**按归属**、不是全局放行；也不是"什么都看不到"──────────────

  @Test
  void theAffiliationAxisIsPerAffiliation() throws Exception {
    assertThat(docIds(body(call(DM_C, Map.of()))))
        .as("★ dm-c 与 dm-a 同属 alpha ⇒ 看得到归属文档，但**看不到**指派给 dm-a 的那条（tag 只认自己）")
        .containsExactly("shared-brief");
    assertThat(docIds(body(call(DM_B, Map.of()))))
        .as("★ dm-b 属 beta ⇒ 只看得到 beta 的归属文档（alpha 那条不出现）")
        .containsExactly("beta-brief");
  }

  // ── 判据三：两轴都空 ⇒ 谁都不给看（不是"大家都能看"）────────────────────────────

  @Test
  void aDocWithNeitherTagNorAffiliationIsInvisibleToEveryone() throws Exception {
    assertThat(allInfoIds()).as("前提：无主文档确实在 INFO 层里（否则下面的『看不到』是假象）").contains(idOf(ADDR_SECRET, 0));

    for (DecisionMakerId dm : List.of(DM_A, DM_B, DM_C)) {
      assertThat(docIds(body(call(dm, Map.of()))))
          .as("★ %s 看不到无主文档（两个轴都不命中 ⇒ fail-closed）", dm.value())
          .doesNotContain("secret");
    }
  }

  // ── 判据四：docId 精确取一篇，且 value 原样逐字节返回 ───────────────────────────────

  @Test
  void aDocIdFetchesExactlyThatDocAndTheValueComesBackVerbatim() throws Exception {
    JsonNode body = body(call(DM_A, Map.of("docId", "alpha-brief")));

    assertThat(docIds(body)).as("★ 给了 docId ⇒ 只回那一篇").containsExactly("alpha-brief");
    JsonNode row = body.get("docs").get(0);
    assertThat(row.get("docId").asText()).isEqualTo("alpha-brief");
    assertThat(row.get("value").asText())
        .as("★ value 原样返回（本层不解析、不改写）——逐字节相等")
        .isEqualTo(ALPHA_BODY);
    assertThat(row.get("tick").asLong()).isEqualTo(3L);
  }

  // ── 判据五："不存在"与"存在但你无权看"返回**同一个回答**（不漏存在性）──────────────

  @Test
  void anUnknownDocIdAndAnInvisibleDocIdGetTheIdenticalAnswer() throws Exception {
    JsonNode invisible = body(call(DM_A, Map.of("docId", "beta-brief")));
    JsonNode unknown = body(call(DM_A, Map.of("docId", "no-such-doc")));

    assertThat(invisible.get("count").asInt()).isZero();
    assertThat(unknown.get("count").asInt()).isZero();
    assertThat(invisible.toString())
        .as("★ 两者响应逐字相同：否则读出的是『这篇文档存在』（一个不该漏的信息）")
        .isEqualTo(unknown.toString());
  }

  // ── 判据六：只回文档地址的条目（不是把整个 INFO 层吐出来）────────────────────────

  @Test
  void onlyDocAddressesAreReturnedNeverTheWholeInfoLayer() throws Exception {
    assertThat(allInfoIds()).as("前提：非文档地址的条目确实在同一 INFO 层里").contains(idOf(ADJUDICATION_ADDR, 0));

    for (DecisionMakerId dm : List.of(DM_A, DM_B, DM_C)) {
      assertThat(entryIds(body(call(dm, Map.of()))))
          .as("★ %s 的文档里不得出现非文档地址的条目（那条的 tags 也含 dm-a）", dm.value())
          .doesNotContain(idOf(ADJUDICATION_ADDR, 0));
    }
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  /** 走唯一入口执行一次工具调用：身份/权限组现算，参数自己装。 */
  private ToolResult call(DecisionMakerId dm, Map<String, Object> args) {
    SimulationState state = stateAtGenesis();
    ToolRegistry registry = new ToolRegistry();
    registry.register(new DecisionDocsTool(queryService, MAP_ID));
    return DecisionCallerFactory.defaults(authorizer())
        .execute(
            registry,
            DecisionDocsTool.NAME,
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

  private static List<String> docIds(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : body.get("docs")) {
      out.add(row.get("docId").asText());
    }
    return out;
  }

  /** 返回行的**条目 id**（不是 docId）：用来证"某条 INFO 没被当成文档带出来"。 */
  private static List<String> entryIds(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : body.get("docs")) {
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

  private static String adjudicationAddress(long tick) {
    return Address.parse(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick).canonical();
  }

  private static String idOf(String canonicalAddress, int ordinal) {
    return SdInfoIds.synthesize(canonicalAddress, ordinal).value();
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
   * 世界：两个国家 {@code alpha} / {@code beta}（region {@code r1} 的归属只在 nation 记录里，SdState 看不见地图）。
   *
   * <p>INFO 层见类注的表格。
   */
  private static SimulationState genesis() {
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(
        new NationId("alpha"),
        new Nation(new NationId("alpha"), "国家 alpha", new RegionId("r1"), 0));
    nations.put(
        new NationId("beta"), new Nation(new NationId("beta"), "国家 beta", new RegionId("r1"), 0));

    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(DM_A, nationDm(DM_A, "alpha"));
    makers.put(DM_B, nationDm(DM_B, "beta"));
    makers.put(DM_C, nationDm(DM_C, "alpha"));

    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>();
    info.put(ADDR_ALPHA, List.of(doc(ADDR_ALPHA, 0, 3, Set.of(DM_A), Set.of(), ALPHA_BODY)));
    info.put(
        ADDR_SHARED,
        List.of(
            doc(
                ADDR_SHARED,
                0,
                4,
                Set.of(),
                Set.of(nation("alpha")),
                "{\"title\":\"共享简报\",\"body\":\"alpha 全体可见\"}")));
    info.put(
        ADDR_BETA,
        List.of(
            doc(
                ADDR_BETA,
                0,
                5,
                Set.of(),
                Set.of(nation("beta")),
                "{\"title\":\"Beta 简报\",\"body\":\"beta 全体可见\"}")));
    info.put(
        ADDR_SECRET,
        List.of(
            doc(ADDR_SECRET, 0, 6, Set.of(), Set.of(), "{\"title\":\"无主\",\"body\":\"谁都不给看\"}")));
    // ★ 非文档条目：同一条 INFO 层里、同一个归属轴上属于 dm-a，但它**不是**文档地址。
    info.put(
        ADJUDICATION_ADDR,
        List.of(doc(ADJUDICATION_ADDR, 0, 7, Set.of(DM_A), Set.of(), "{\"tick\":7,\"steps\":[]}")));

    SdState sd = SdState.empty().withNations(nations).withDecisionMakers(makers).withInfo(info);
    return ScopeFixtures.state(
        ScopeFixtures.mapOf(ScopeFixtures.nationRegion("r1", "alpha", H11)),
        ScopeFixtures.units(),
        sd);
  }

  private static DecisionMaker nationDm(DecisionMakerId id, String nationId) {
    return new DecisionMaker(
        id, new Affiliation.Nation(new NationId(nationId)), Set.of(), AccessLimit.empty(), 1);
  }

  private static Affiliation nation(String nationId) {
    return new Affiliation.Nation(new NationId(nationId));
  }

  private static SdInfoEntry doc(
      String canonicalAddress,
      int ordinal,
      long tick,
      Set<DecisionMakerId> tags,
      Set<Affiliation> affiliations,
      String value) {
    return new SdInfoEntry(
        SdInfoIds.synthesize(canonicalAddress, ordinal),
        tick,
        tags,
        affiliations,
        DecisionDoc.KEY,
        value,
        Optional.empty(),
        new RevisionId(1),
        Optional.empty());
  }
}
