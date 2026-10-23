package io.mosire.simos.app.testing;

import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.llm.ProviderLlm;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>批 G/批 H 的共享夹具：一座"有编制 + 有家户 + 起了真 Shell + 种了真决策人"的小世界</b> （2026-10-15，测试面）。
 *
 * <h2>它补的是哪个缺口（上一批台账 §6.3 的根因）</h2>
 *
 * <p>上一批（{@code 2026-10-14-gm-dm-tests}）实测：本仓<b>没有任何既有夹具能造出"可落地的 {@code propose} call"</b>——
 *
 * <ul>
 *   <li>{@code RunDecisionEndToEndTest} 的 genesis：{@code u-1} <b>没有</b> {@code
 *       GovernmentFormation}、{@code social} <b>没有</b>家户 ⇒ {@code ProposalCatalog} 的全部候选工具在真 preview
 *       阶段一律 {@code BAD_REQUEST}（实测原文：{@code 目标工具预览失败(BAD_REQUEST): 单位 u-1 没有
 *       GovernmentFormation：退休只对 GOV 单位；先 unit.SetGovFormation}）；
 *   <li>{@code SmallWorld} 创世（{@code DmMessagingEndToEndTest} 用的那个）<b>有</b>编制与家户，但<b>不种决策人</b>
 *       （{@code GovWorldBootstrap} 类注：「不建决策人（GM/后续显式建）」）；
 *   <li>{@code UnitHouseholdWorldFixture} <b>有</b> {@code GovernmentFormation}，但它<b>不起
 *       Shell、不种决策人</b> （只有 core + host 工具）。
 * </ul>
 *
 * <p>★★ <b>本夹具的做法 = 把三者各有的那一块拼起来，且不复制任何一块的第二份真相</b>：
 *
 * <ol>
 *   <li><b>编制 + 家户</b>取自{@code SmallWorld}（19 hex 创世，含两个 GOV 单位 {@code gov-central} / {@code
 *       gov-province}、它们的 {@code GovernmentFormation}、政府家户 {@code hh-gov-<id>} 与官吏户 {@code
 *       hh-unit:<id>}）—— 由 {@link SmallWorld#state(String)} 现算，不手搭；
 *   <li><b>决策人 + 归属行</b>由本夹具<b>注入创世状态</b>（形制与 {@code gov.AssignDecisionMakerGov} 逐字同形：写 {@code
 *       GovState.govAffiliations()} 那一行）—— 归属是 {@code GovScope} 唯一的可见性来源，缺它 ⇒ 范围函数 deny-all ⇒
 *       连"世界读"都被拒（{@code GovToolsZ6Test.withAffiliations} 已为这条付过一次代价）；
 *   <li><b>真 Shell + 注入假 LLM</b>：{@link Shell#start(ShellConfig, DecisionAgentService.LlmClients)}
 *       ⇒ 真 MCP 口、真审批链、真工具面、真 store，但一轮决策人<b>不碰网络</b>。
 * </ol>
 *
 * <h2>★ 世界里有什么（判据可以依赖的事实）</h2>
 *
 * <table>
 *   <caption>夹具事实</caption>
 *   <tr><th>事实</th><th>值</th><th>谁用</th></tr>
 *   <tr><td>中央 GOV 单位</td><td>{@code gov-central} @ {@code (0,0)}，带 {@code GovernmentFormation}</td>
 *       <td>{@code ProposalCatalog} 的真 preview / 真执行</td></tr>
 *   <tr><td>中央管辖 Region</td><td>{@code capital-province}（含 {@code (0,0)}）</td>
 *       <td>{@code GovScope} 的 map/social/unit 前缀</td></tr>
 *   <tr><td>政府家户</td><td>{@code hh-gov-gov-central}（{@code GovernmentHouseholds.of}）</td>
 *       <td>国库 / 招募目标</td></tr>
 *   <tr><td>官吏户</td><td>{@code hh-unit:gov-central}（2 名成年男性，1 个 POST 岗位）</td>
 *       <td>退休 / 编制</td></tr>
 *   <tr><td>决策人</td><td>{@link #DM} = {@code dm-central}，{@code govAffiliations} 指向 {@code gov-central}</td>
 *       <td>代打的内层身份</td></tr>
 *   <tr><td>tick</td><td>{@code 0}（创世 {@code (main, 1)}）</td><td>包 id 的 tick 段</td></tr>
 * </table>
 *
 * <h2>★ 本夹具刻意<b>不</b>做的事</h2>
 *
 * <ul>
 *   <li><b>不手搭 GOV 单位</b>：编制、岗位、家户、国库、政策全部走 {@code SmallWorld} 的真创世命令链（ {@code
 *       GovWorldBootstrap}）——手搭一份就是第二份"世界该长什么样"的真相；
 *   <li><b>不改生产代码</b>：注入点在 {@code bootstrapGenesis} 之前的状态上（与 {@code GovToolsZ6Test} 同法）， {@code
 *       simos-&ast;/src/main/&ast;&ast;} 一个字节不动；
 *   <li><b>不伪造 provider</b>：决策人身上绑的 {@code providerId} 是真的（{@link #PROVIDER_ID}），只是它的客户端由 {@link
 *       Fake#provider(String)} 提供 ⇒ "壳解析 provider ⇒ 拿到客户端"这条链是真的，只有网络是假的。
 * </ul>
 */
public final class GmActAsWorldFixture {

  /** 本世界的 branch（与 {@code SmallWorld} 创世同字面）。 */
  public static final BranchId MAIN = new BranchId("main");

  /** 夹具决策人 id（{@code gov-central} 的决策人）。 */
  public static final String DM = "dm-central";

  /** ★ 第二个决策人：绑定到 @{code gov-province}（够得着的是<b>省</b>那一份世界），用于"换个 DM ⇒ 换一个可达面"。 */
  public static final String DM_PROVINCE = "dm-province";

  /** {@link #DM} 绑的 GOV 单位（中央；管辖 {@code capital-province}）。 */
  public static final UnitId GOV_CENTRAL = UnitId.parse(GovWorldBootstrap.CENTRAL_GOV_ID);

  /** {@link #DM_PROVINCE} 绑的 GOV 单位（省；管辖 {@code small-world} 那 18 格）。 */
  public static final UnitId GOV_PROVINCE = UnitId.parse(GovWorldBootstrap.PROVINCE_GOV_ID);

  /** 决策人绑定的 provider id（**真的**：壳按它解析；客户端由夹具提供）。 */
  public static final String PROVIDER_ID = "stub";

  /** 一个夹具国家 id（只有"归属种类齐全"的用例需要；不作为 GOV 决策人的可见性来源）。 */
  public static final String NATION = "FRA";

  private GmActAsWorldFixture() {}

  /**
   * 一座真壳小世界：创世已落在 {@code (main, 1)}，{@link #DM} / {@link #DM_PROVINCE} 已种好且归属行已落表。
   *
   * @param storeDir 该世界的独立存储目录（调用方保证不同用例不同目录）
   * @param fake 注入的假 LLM（一轮决策人不碰网络）
   */
  public static World start(Path storeDir, Fake fake) {
    return start(
        storeDir, fake, Map.of(DM, GOV_CENTRAL, DM_PROVINCE, GOV_PROVINCE), Map.of(), Map.of());
  }

  /**
   * 同 {@link #start(Path, Fake)}，但可指定决策人集合与它们的世界事实（{@code allowedTools} / {@code accessLimit}）。
   *
   * @param govOf 决策人 id → 它绑的 GOV 单位 id
   * @param allowedToolsOf 决策人 id → {@code allowedTools}（空/缺席 = 沿用派生面）
   * @param accessLimitOf 决策人 id → {@code accessLimit} 前缀表（缺席 = 不收紧）
   */
  public static World start(
      Path storeDir,
      Fake fake,
      Map<String, UnitId> govOf,
      Map<String, Set<String>> allowedToolsOf,
      Map<String, Map<String, Set<String>>> accessLimitOf) {
    ShellConfig config =
        ShellConfig.defaults(storeDir).withPorts(0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
    Shell shell = Shell.start(config, fake::providerFor);
    try {
      SimulationState state = SmallWorld.state(config.mapId());
      state = withDecisionMakers(state, govOf, allowedToolsOf, accessLimitOf);
      shell.coreSimos().bootstrapGenesis(state);
      return new World(shell, config.mapId());
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
  }

  /**
   * ★ <b>把决策人与它们的归属行写进创世状态</b>（形制与 {@code gov.AssignDecisionMakerGov} 逐字同形）。
   *
   * <p>★ 为什么必须在 {@code bootstrapGenesis} <b>之前</b>注入：{@code Shell.start} 的装配期会按世界里的决策人名单
   * <b>开通工作区</b>（{@code WORKSPACE_PROVISIONED ... decisionMakers=N}）与<b>决策人工具面</b>；先起壳再写归属 ⇒
   * 装配期那份名单是空的（且往已 bootstrap 的库上叠种被具名拒）。
   *
   * <p>★ 归属行是 {@code GovScope} 的<b>唯一</b>可见性来源：{@code ga.CreateDecisionMaker} 自乙收尾起<b>拒收</b> {@code
   * affiliation}（归属归模块），少了这一行 ⇒ 范围函数 deny-all ⇒ 连世界读都被拒（"没有归属"与"够不着" 在授权上不可区分）。
   *
   * @param state 创世候选状态（{@link SmallWorld#state(String)} 的产物）
   * @param govOf 决策人 id → 它绑的 GOV 单位 id
   */
  public static SimulationState withDecisionMakers(
      SimulationState state, Map<String, UnitId> govOf) {
    return withDecisionMakers(state, govOf, Map.of(), Map.of());
  }

  /**
   * 同 {@link #withDecisionMakers(SimulationState, Map)}，但可逐决策人给 {@code allowedTools} 与 {@code
   * accessLimit}。
   *
   * <p>★★ <b>为什么需要它</b>：G2/G9 的判据要证明"代打用的是<b>那个 DM</b>的白名单 / 资源范围"—— 而这两样是决策人身上的<b>世界事实</b>（随
   * revision 落盘），只能在开世界时给，不能中途改（改了就是绕过判定链造状态）。
   *
   * @param allowedToolsOf 决策人 id → 它的 {@code allowedTools}（<b>空集或缺席 = 沿用派生面</b>；非空 = 再收窄）
   * @param accessLimitOf 决策人 id → 它的 {@code accessLimit} 前缀表（<b>缺席 = 不收紧</b>；某命名空间给空集 = 那个面"够不着"）
   */
  public static SimulationState withDecisionMakers(
      SimulationState state,
      Map<String, UnitId> govOf,
      Map<String, Set<String>> allowedToolsOf,
      Map<String, Map<String, Set<String>>> accessLimitOf) {
    StateMeta meta = state.meta();
    GaState ga = ((GaSnapshot) state.module("ga").orElseThrow()).state();
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    Map<DecisionMakerId, UnitId> affiliations = new LinkedHashMap<>();
    for (Map.Entry<String, UnitId> entry : govOf.entrySet()) {
      DecisionMakerId id = new DecisionMakerId(entry.getKey());
      Set<String> allowed = allowedToolsOf.getOrDefault(entry.getKey(), Set.of());
      Map<String, Set<String>> prefixes = accessLimitOf.getOrDefault(entry.getKey(), Map.of());
      // ★ 形态照 GovToolsZ6Test.maker(...) 的六参构造器：allowedTools 空 = 沿用全局（派生）白名单、
      //   accessLimit 空 = 不额外收紧、cadence 1、绑真 provider、会话世代 0（没被重置过）。
      makers.put(
          id,
          new DecisionMaker(
              id,
              Set.copyOf(allowed),
              prefixes.isEmpty()
                  ? io.mosire.simos.ga.model.AccessLimit.empty()
                  : new io.mosire.simos.ga.model.AccessLimit(
                      prefixes, Set.of(), io.mosire.simos.ga.model.DisclosurePolicy.WITHHELD),
              1,
              Optional.of(PROVIDER_ID),
              0L));
      affiliations.put(id, entry.getValue());
    }
    GovState gov = ((io.mosire.simos.gov.GovSnapshot) state.module("gov").orElseThrow()).state();
    GovState nextGov = gov.withGovAffiliations(affiliations);
    SimulationState withGa =
        GovZ6WorldFixture.withModule(
            state,
            "ga",
            new GaSnapshot(meta.ref(), meta.timestamp(), ga.withDecisionMakers(makers)));
    return GovZ6WorldFixture.withModule(
        withGa, "gov", new io.mosire.simos.gov.GovSnapshot(meta.ref(), meta.timestamp(), nextGov));
  }

  /** 一座真壳小世界（封装"读 head / 读状态 / 读决策人 / 关壳"这几件用例天天要做的事）。 */
  public static final class World implements AutoCloseable {

    private final Shell shell;
    private final String mapId;

    private World(Shell shell, String mapId) {
      this.shell = shell;
      this.mapId = mapId;
    }

    public Shell shell() {
      return shell;
    }

    public String mapId() {
      return mapId;
    }

    public long head() {
      return shell.coreSimos().head(MAIN).orElseThrow().value();
    }

    public SimulationState state() {
      return stateAt(head());
    }

    public SimulationState stateAt(long revision) {
      return shell.coreSimos().replay(ref(revision));
    }

    /** 世界里的决策人（读不到 ⇒ 抛：本夹具保证它在）。 */
    public DecisionMaker dm(String id) {
      DecisionMaker maker =
          ((GaSnapshot) state().module("ga").orElseThrow())
              .state()
              .decisionMakers()
              .get(new DecisionMakerId(id));
      if (maker == null) {
        throw new AssertionError("夹具世界里没有决策人 " + id + "（创世注入失败）");
      }
      return maker;
    }

    /** 该决策人本 tick 的唯一世界状态记录（{@code null} = 还没有）。 */
    public io.mosire.simos.ga.model.DecisionRecord recordOf(String decisionMakerId) {
      SimulationState state = state();
      String packetId = "pkt-" + decisionMakerId + "-" + state.meta().timestamp().tick();
      return ((GaSnapshot) state.module("ga").orElseThrow())
          .state()
          .decisionPackets()
          .get(io.mosire.simos.ga.id.DecisionPacketId.parse(packetId));
    }

    /** 该决策人本 tick 的只读领域视图（{@code null} = 还没有）；精确状态请用 {@link #recordOf}。 */
    public io.mosire.simos.ga.model.DecisionPacket packetOf(String decisionMakerId) {
      io.mosire.simos.ga.model.DecisionRecord record = recordOf(decisionMakerId);
      return record == null ? null : record.view();
    }

    /** 决策人面上的某一条工具（真装配的桶；找不到 ⇒ 抛）。 */
    public io.mosire.agentlib.tool.AgentTool decisionTool(String name) {
      return shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.DECISION_AGENT).stream()
          .filter(tool -> tool.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new AssertionError("决策人面上必须有 " + name + "（toolRoles() 声明与桶装配不一致？）"));
    }

    /** 决策人面上的工具名（快照）。 */
    public Set<String> decisionToolNames() {
      Set<String> names = new java.util.LinkedHashSet<>();
      for (io.mosire.agentlib.tool.AgentTool tool :
          shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.DECISION_AGENT)) {
        names.add(tool.name());
      }
      return names;
    }

    @Override
    public void close() {
      shell.close();
    }
  }

  /** {@code (main, revision)} 坐标。 */
  public static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  /**
   * ★ <b>注入真壳的假 LLM</b>：脚本化回放（{@link FakeLlmClient}），并把每次请求记下来（"喂进去了什么"可读回）。
   *
   * <p>★ 形态与 {@code RunDecisionEndToEndTest.RecordingLlmClient} 同源，但**只保留本批需要的两件事** （enqueue +
   * requests）：本批没有一条判据依赖视觉能力/多 provider，多抄一份没人用的面就是第二处真相。
   */
  public static final class Fake {

    private final FakeLlmClient delegate = new FakeLlmClient();
    private final java.util.List<LlmRequest> requests = new java.util.ArrayList<>();

    /** 注入进壳的那条缝：providerId 是**真的**（壳按世界事实解析出来），客户端由本替身提供。 */
    public ProviderLlm providerFor(String providerId) {
      if (!PROVIDER_ID.equals(providerId)) {
        throw new AssertionError("壳解析出的 providerId 必须与夹具里绑的一致（实得 " + providerId + "）");
      }
      return new ProviderLlm(client(), false);
    }

    public void enqueue(LlmResponse... responses) {
      for (LlmResponse response : responses) {
        delegate.enqueue(response);
      }
    }

    public java.util.List<LlmRequest> requests() {
      return java.util.List.copyOf(requests);
    }

    public int calls() {
      return delegate.calls();
    }

    /** 一次性把 {@link LlmClient} 这条缝交出去（构造 {@link ProviderLlm} 用）。 */
    public LlmClient client() {
      return this::chat;
    }

    private LlmResponse chat(LlmRequest request) {
      requests.add(request);
      return delegate.chat(request);
    }
  }
}
