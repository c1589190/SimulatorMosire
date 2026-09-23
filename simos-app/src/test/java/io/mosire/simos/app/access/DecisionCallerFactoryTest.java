package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.read.DecisionDocsTool;
import io.mosire.simos.app.tools.read.DecisionResultsTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.app.tools.write.UnitPlaceAtTool;
import io.mosire.simos.app.tools.write.UnitRenameTool;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **判据 J9 / J3（spec §2.2、§5.2 第 1 条）**：决策人的权限组**由范围函数现算**，且**必须经 {@code ToolCallAuthorizer}**
 * 才生效。
 *
 * <p>★ **J9 的判别形状照抄 AgentLib 自己的用例**（{@code ToolCallAuthorizerResourceTest} 的
 * "同工具同参数、两个调用者一个放过一个拒"）：两个**国家决策人**（FRA / GER）打**同一个**工具、**同一份**参数（都读区域 701）—— FRA 拿 {@code
 * ok}、GER 拿 {@code RESOURCE_DENIED}，且被拒那次**工具体一格没走**（计数器证明）。
 *
 * <p>★ **为什么用夹具工具而不是现成读工具**：现成的 {@code simos.map.hex}/{@code simos.map.overview} 断言的是 **{@code
 * map:<mapId>}**（粗），而范围函数给的是**区域级前缀** —— 粗断言撞细围栏会**整调被拒**（spec §5.2 第 2 条， {@code
 * ScopeFenceTest.aCoarseAssertionAgainstAFineFenceIsDeniedWholeCall} 已钉住）。读工具的细粒度化是 **T10** 的活；
 * 本用例要证的是**权限链本身**，故夹具工具用与范围函数**同一套货币**（{@code ToolSupport.resourceRegion}）。
 *
 * <p>★ **判定者只在 {@code ToolCallAuthorizer} 第 ③ 段注入**：手工拼 {@code ToolContext} 后直接 {@code
 * tool.execute(ctx)} 会让每个调 {@code require} 的工具 `RESOURCE_DENIED`（那是"没有判定者"的 fail-closed，不是"拒"）。
 * 本用例全程走 {@link DecisionCallerFactory#execute}，故它同时也是"必须走唯一入口"的守卫。
 */
class DecisionCallerFactoryTest {

  /** 合成小世界的 map 里两个区域（{@code ScopeFixtures}）：FRA=701 在 (1,1)、GER=201 在 (1,2)。 */
  private static final SimulationState STATE =
      ScopeFixtures.state(
          ScopeFixtures.mapOf(
              ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1)),
              ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
          ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1)),
          ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

  private static final DecisionMaker FRA = ScopeFixtures.nationDm("dm-FRA", "FRA");
  private static final DecisionMaker GER = ScopeFixtures.nationDm("dm-GER", "GER");

  @TempDir java.nio.file.Path tempDir;

  // ── J9 的核心：同一工具、同一参数、两个决策人 ──────────────────────────────────────────

  @Test
  void sameToolSameArgumentsTwoDecisionMakersOnePassesOneIsDenied() {
    RegionProbe probe = new RegionProbe(ScopeFixtures.MAP_ID);
    ToolRegistry registry = registryWith(probe);
    DecisionCallerFactory factory = factoryWith(probe.name());

    ToolResult allowed =
        factory.execute(
            registry, probe.name(), FRA, STATE, ScopeFixtures.MAP_ID, Map.of("regionId", "701"));
    assertThat(allowed.success()).as("FRA 自己的区域：读得到（%s）", allowed.message()).isTrue();
    assertThat(allowed.message()).isEqualTo("probe:701");
    assertThat(probe.executed()).isEqualTo(1);

    ToolResult denied =
        factory.execute(
            registry, probe.name(), GER, STATE, ScopeFixtures.MAP_ID, Map.of("regionId", "701"));
    assertThat(denied.success()).as("同一工具、同一参数：GER 够不着 FRA 的区域").isFalse();
    assertThat(denied.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(denied.message()).contains("701").contains("不在调用者的可达面内");
    // ★ 判别性：被拒那一次**工具体一格没走**（计数停在 FRA 那一次）——否则"拒"只是事后报错
    assertThat(probe.executed()).as("被拒的调用不得让工具体执行").isEqualTo(1);
  }

  @Test
  void aDecisionMakerCanReadWhatItsOwnScopeCoversAndNotWhatOthersCover() {
    RegionProbe probe = new RegionProbe(ScopeFixtures.MAP_ID);
    ToolRegistry registry = registryWith(probe);
    DecisionCallerFactory factory = factoryWith(probe.name());

    assertThat(
            factory
                .execute(
                    registry,
                    probe.name(),
                    FRA,
                    STATE,
                    ScopeFixtures.MAP_ID,
                    Map.of("regionId", "701"))
                .success())
        .as("本国区域：过")
        .isTrue();
    assertThat(
            factory
                .execute(
                    registry,
                    probe.name(),
                    FRA,
                    STATE,
                    ScopeFixtures.MAP_ID,
                    Map.of("regionId", "201"))
                .success())
        .as("别国区域：拒")
        .isFalse();
    assertThat(
            factory
                .execute(
                    registry,
                    probe.name(),
                    GER,
                    STATE,
                    ScopeFixtures.MAP_ID,
                    Map.of("regionId", "201"))
                .success())
        .as("别国自己的区域：过（判别力是双向的）")
        .isTrue();
  }

  /** 走 brief 指定的那个签名（自建 {@code ToolContext}）：身份/权限来自 {@code callerFor}，参数自己装。 */
  @Test
  void theToolContextFlavouredEntryPointIsAlsoGatedByTheSameChain() {
    RegionProbe probe = new RegionProbe(ScopeFixtures.MAP_ID);
    ToolRegistry registry = registryWith(probe);
    DecisionCallerFactory factory = factoryWith(probe.name());
    ToolContext fra = factory.callerFor(FRA, STATE, ScopeFixtures.MAP_ID);
    ToolContext ger = factory.callerFor(GER, STATE, ScopeFixtures.MAP_ID);

    ToolContext fraWithArgs =
        new ToolContext(
            fra.caller(),
            fra.permissions(),
            fra.config(),
            Map.of("regionId", "701"),
            fra.identity());
    ToolContext gerWithArgs =
        new ToolContext(
            ger.caller(),
            ger.permissions(),
            ger.config(),
            Map.of("regionId", "701"),
            ger.identity());

    assertThat(factory.execute(registry, probe.name(), fraWithArgs).success())
        .as("FRA 读 701：过")
        .isTrue();
    ToolResult denied = factory.execute(registry, probe.name(), gerWithArgs);
    assertThat(denied.success()).as("GER 读 701：拒").isFalse();
    assertThat(denied.code())
        .as("★ 拒因必须是资源级（判定者由唯一入口注入），不是『没有判定者』的 fail-closed")
        .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(probe.executed()).as("被拒那一次工具体没走").isEqualTo(1);
  }

  // ── J3：决策人不能直接改数据 ────────────────────────────────────────────────────────

  @Test
  void decisionMakerCannotCallUnitDomainWrites() {
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      ToolRegistry registry = new ToolRegistry();
      registry.register(new UnitRenameTool(shell.coreSimos(), "agent:test", ScopeFixtures.MAP_ID));
      registry.register(new UnitPlaceAtTool(shell.coreSimos(), "agent:test", ScopeFixtures.MAP_ID));

      DecisionCallerFactory factory = factory();
      for (String name : List.of(UnitRenameTool.NAME, UnitPlaceAtTool.NAME)) {
        ToolResult result =
            factory.execute(
                registry,
                name,
                FRA,
                STATE,
                ScopeFixtures.MAP_ID,
                Map.of("payloadJson", "{}", "branch", "main", "expectedRevision", 1L));
        assertThat(result.success()).as("J3：%s 是 unit 域写工具，决策人不得执行", name).isFalse();
        assertThat(result.code())
            .as("拒在**工具名级**（白名单），不是资源级——资源级拒会让人以为『换个资源就行』")
            .isEqualTo(ToolExecutionGuard.DENIED);
      }
    }
  }

  /**
   * ★ J3 的**结构性**那一半：决策人白名单 = 10 读（第 3 波第 3 步起含 {@code sd.DecisionResults}）+ 两条决策窄写；**GM
   * 面上的其他写工具一个都不许在**。
   *
   * <p>★ 名单从**真 GM 面**派生（不是手抄的期望表）⇒ 任何人往 GM 组加一条写工具而不同步这里，用例当场红。
   */
  @Test
  void theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites() {
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      List<String> gmFace =
          shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList();
      Set<String> decisionFace =
          Set.copyOf(
              shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
                  .map(AgentTool::name)
                  .toList());
      assertThat(gmFace).as("GM 面非空").isNotEmpty();

      Set<String> whitelist =
          factory().callerFor(FRA, STATE, ScopeFixtures.MAP_ID).permissions().allowedTools();
      assertThat(whitelist)
          .as("白名单 == 决策人桶的工具面（读 + 两条决策窄写）")
          .containsExactlyInAnyOrderElementsOf(decisionFace);
      assertThat(whitelist)
          .as("★ 判据 J3：GM 面上**除决策人桶之外的每一条**工具都不在白名单里（写工具一条都不给）")
          .doesNotContainAnyElementsOf(
              gmFace.stream().filter(n -> !decisionFace.contains(n)).toList());
      assertThat(whitelist)
          .as(
              "★ 决策人必须读得到**自己**的决策结果（2026-09-23）：'决策 → 看结果 → 再决策'的回路靠这条工具闭合"
                  + "——缺了它，回路是断的，而模型只会觉得'我查不到'")
          .contains(DecisionResultsTool.NAME);
      assertThat(whitelist)
          .as(
              "★ 决策人必须读得到**发给自己的设定文档**（Docs，2026-09-23）：'我是谁、我这一局该做什么'靠这条工具补全"
                  + "——缺了它，模型只能拿通用常识硬套")
          .contains(DecisionDocsTool.NAME);
      assertThat(whitelist)
          .as("两条决策窄写必须在（语义就靠它们）")
          .contains(IssueDirectiveTool.NAME, SubmitVerdictTool.NAME);
    }
  }

  // ── 身份 / 现算 ──────────────────────────────────────────────────────────────────

  @Test
  void theCallerCarriesTheSubagentIdentityOfThatDecisionMaker() {
    ToolContext ctx = factory().callerFor(FRA, STATE, ScopeFixtures.MAP_ID);

    assertThat(ctx.identity().instanceId())
        .as("★ conversationId 的同一个来源：按决策人 id 派生（跨 tick 的会话靠它接上）")
        .isEqualTo("decision-maker:dm-FRA");
    assertThat(ctx.identity().mode())
        .as("命令档位 LIMITED：决策人不是完全权限的 Agent")
        .isEqualTo(CommandMode.LIMITED);
    assertThat(ctx.identity().depth()).as("进程内派生的一级").isEqualTo(1);
    assertThat(ctx.identity().goal()).as("goal 进审批提示面（不进事件库）").isNotBlank();
    assertThat(ctx.caller()).isEqualTo(AccessToken.DEFAULT);
    assertThat(ctx.permissions().sensitiveAllowed()).as("两条决策窄写是敏感工具：不放行就进不了审批闸（被硬拒）").isTrue();
    assertThat(ctx.permissions().readOnly())
        .as("★ 绝不能用 readOnly(true)：AgentLib 把它实现成『拒一切工具』（spec §1.4 的 C1）")
        .isFalse();
  }

  /** ★ J4 的下游形态：范围**每次现算**——同一个决策人，世界变了范围就变。 */
  @Test
  void theScopeIsRecomputedPerCallSoWorldChangesMoveTheFence() {
    DecisionCallerFactory factory = factory();
    DecisionMaker ger = ScopeFixtures.nationDm("dm-GER2", "GER");

    SimulationState before = STATE;
    SimulationState after =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(
                ScopeFixtures.nationRegion("701", "GER", new HexCoord(1, 1)),
                ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
            ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1)),
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ToolContext ctxBefore = factory.callerFor(ger, before, ScopeFixtures.MAP_ID);
    ToolContext ctxAfter = factory.callerFor(ger, after, ScopeFixtures.MAP_ID);

    assertThat(
            ctxBefore
                .permissions()
                .resourceScopes()
                .declaredScope(ToolSupport.MAP_NAMESPACE)
                .prefixes())
        .as("世界①：GER 只够得着 201")
        .containsExactlyInAnyOrderElementsOf(List.of(ScopeFixtures.MAP_ID + "/region/201"));
    assertThat(
            ctxAfter
                .permissions()
                .resourceScopes()
                .declaredScope(ToolSupport.MAP_NAMESPACE)
                .prefixes())
        .as("世界②：701 改属 GER 之后，同一个决策人的范围**当场**变大（没有缓存）")
        .containsExactlyInAnyOrderElementsOf(
            List.of(ScopeFixtures.MAP_ID + "/region/201", ScopeFixtures.MAP_ID + "/region/701"));
  }

  /**
   * ★★ **T9 的核心语义**：GM 配的 {@code accessLimit} 与现算范围**求交**（{@code narrowTo}）——GM 只能**额外收紧**，不能放大。
   *
   * <p>限制**存在决策人身上**（{@code dm.accessLimit()}，随 revision 落盘），不再是调用方传参：GM 配"只许碰 201"，而 FRA 决策人在世界①里
   * 只有 701 ⇒ 交集为空 = 哪里都不许。写成"**覆盖**"的实现会让它看见 201（那正是被取代的 {@code viewScope} 语义），且**不报错**。
   */
  @Test
  void theGmSuppliedLimitIsIntersectedNotReplaced() {
    DecisionCallerFactory factory = factory();
    DecisionMaker narrowedDm =
        ScopeFixtures.nationDmWithLimit(
            "dm-FRA-narrowed",
            "FRA",
            AccessLimit.ofPrefixes(Map.of("map", Set.of(ScopeFixtures.MAP_ID + "/region/201"))));

    ToolContext narrowed = factory.callerFor(narrowedDm, STATE, ScopeFixtures.MAP_ID);

    assertThat(
            scopesOf(narrowed)
                .declaredScope(ToolSupport.MAP_NAMESPACE)
                .allows(ScopeFixtures.MAP_ID + "/region/701"))
        .as("★ 交集：GM 的限制**不能**把范围放大回 FRA 自己的 701（701 ∩ 201 = 空）")
        .isFalse();
    assertThat(
            scopesOf(narrowed)
                .declaredScope(ToolSupport.MAP_NAMESPACE)
                .allows(ScopeFixtures.MAP_ID + "/region/201"))
        .as("★ 反向也要钉死：交集为空就是哪里都不许，不是'GM 说了算于是 201 放行'")
        .isFalse();
  }

  /** GM 写一个**比范围宽**的命名空间值（整张地图）⇒ 交集仍是范围函数给的那两条（**不能放大**）。 */
  @Test
  void aBroaderGmLimitDoesNotWidenTheComputedScope() {
    DecisionCallerFactory factory = factory();
    DecisionMaker widerDm =
        ScopeFixtures.nationDmWithLimit(
            "dm-FRA-wide",
            "FRA",
            AccessLimit.ofPrefixes(Map.of("map", Set.of(ScopeFixtures.MAP_ID))));

    ToolContext wide = factory.callerFor(widerDm, STATE, ScopeFixtures.MAP_ID);

    assertThat(ScopeFixtures.prefixes(scopesOf(wide)))
        .as("★ GM 说'整张 demo'，范围函数只给 701 ⇒ 交集仍是 701（配得宽 ≠ 看得多）")
        .containsExactly(ScopeFixtures.MAP_ID + "/region/701");
    assertThat(
            scopesOf(wide)
                .declaredScope(ToolSupport.MAP_NAMESPACE)
                .allows(ScopeFixtures.MAP_ID + "/region/201"))
        .as("别国的区域不会因为 GM 配得宽而露出来")
        .isFalse();
  }

  /** 决策人的限制**不表态**（空图）⇒ 范围函数说什么就是什么（缺省必须是"不收紧"，否则新建的决策人当场变瞎）。 */
  @Test
  void anEmptyAccessLimitLeavesTheComputedScopeUntouched() {
    DecisionCallerFactory factory = factory();

    ToolContext plain = factory.callerFor(FRA, STATE, ScopeFixtures.MAP_ID);

    assertThat(ScopeFixtures.prefixes(scopesOf(plain)))
        .containsExactly(ScopeFixtures.MAP_ID + "/region/701");
  }

  private static io.mosire.agentlib.permission.ResourceScopeMap scopesOf(ToolContext context) {
    return context.permissions().resourceScopes();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  /** 走真壳的审批装配（{@code AutoApproveGate}）——不是 {@code standard()}（那条路遇到 {@code Ask} 一律拒）。 */
  private static DecisionCallerFactory factory() {
    return factoryWith();
  }

  /**
   * 把**夹具工具**也放进白名单的工厂（包内接缝）。
   *
   * <p>★ 为什么需要它：J9 要证的是**资源维**（同一工具、同一参数、两个调用者一个放过一个拒）。夹具工具的名字不在生产白名单里， 不放进来的话两次调用都会先被**白名单**拦下 ——
   * 那证的就不是资源维了。生产白名单由 {@link #theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites} 单独钉死。
   */
  private static DecisionCallerFactory factoryWith(String... extraTools) {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending)), List.of(), pending, Duration.ofMinutes(1), null);
    Set<String> whitelist = new java.util.LinkedHashSet<>(DecisionCallerFactory.WHITELIST);
    whitelist.addAll(List.of(extraTools));
    return new DecisionCallerFactory(
        DecisionScopeFunctions.defaults(),
        ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator),
        whitelist);
  }

  private static ToolRegistry registryWith(AgentTool... tools) {
    ToolRegistry registry = new ToolRegistry();
    for (AgentTool tool : tools) {
      registry.register(tool);
    }
    return registry;
  }

  /**
   * 夹具工具：区域级的 map 资源断言（与范围函数**同一套货币**）。
   *
   * <p>★ 它存在的唯一理由是现成读工具的断言还**太粗**（{@code map:<mapId>}）：粗断言撞细围栏 = 整调被拒 （spec §5.2 第 2 条）。T10
   * 把读工具改细之后，本夹具应被真工具取代——那时把它删掉，不要留两份判据。
   */
  private static final class RegionProbe implements AgentTool {

    private final String mapId;
    private final AtomicInteger executed = new AtomicInteger();

    RegionProbe(String mapId) {
      this.mapId = mapId;
    }

    @Override
    public String name() {
      return "test.region.probe";
    }

    @Override
    public String description() {
      return "夹具：按 regionId 读一个区域（区域级资源断言）";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return ToolSupport.schema(
          Map.of("regionId", ToolSupport.prop("string", "区域 id")), List.of("regionId"));
    }

    @Override
    public ResourceManifest resources() {
      return ToolSupport.MAP_READ;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      String regionId = ToolSupport.requiredText(context.arguments(), "regionId");
      context.resources().require(Operation.READ, ToolSupport.resourceRegion(mapId, regionId));
      executed.incrementAndGet();
      return ToolResult.ok("probe:" + regionId);
    }

    int executed() {
      return executed.get();
    }
  }
}
