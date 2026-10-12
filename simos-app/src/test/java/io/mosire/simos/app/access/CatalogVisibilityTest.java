package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.channel.ChannelAdmission;
import io.mosire.simos.ga.command.CommandFaces;
import io.mosire.simos.ga.command.CommandPermissionRules;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **catalog 按调用者过滤（用户 2026-09-23 裁定）**：{@code simos.command.catalog} 对**管辖者**列全量、对**决策人**只列它
 * 有途径触发的类型。
 *
 * <p>★ **判别力来自"同一份注册面、两个调用者"**：本用例只喂一份**合成注册面**（都是真 {@code PAYLOAD_HINTS} 里有的类型）， 分别用 GM
 * 权限组与**真**决策人权限组（走 {@link DecisionCallerFactory}）执行同一个 {@link CatalogTool}。
 * "都能看到"或"都看不到"的实现在这里当场红。
 *
 * <p>★ **{@code ga.PutInfo} / {@code ga.CreateDecisionMaker} / {@code ga.StartDecision} / {@code
 * ga.SetDecisionMakerAccess} 必须对决策人消失** ——那正是用户要修的现象（旧行为下决策人照着目录去试，每一试都白烧一轮真 LLM 调用）。而 {@code
 * unit.PlanRoute} 一类**必须留着**：它们在**令里可以嵌**（{@link CommandFaces#directiveTypes()} 的判据），只按"能不能直接调"
 * 过滤会**过窄**。
 *
 * <p>★★ <b>2026-10-12 批次 1 迁移（接缝位移，不是行为回归）</b>：夹具从"把含 {@code sd.*} 的<b>原始注册面</b>同时当作 catalog
 * 面与可嵌令白名单"改成"<b>装配面派生</b>" —— {@link #FACES} 经 ga 规则层 （{@code CommandPermissionRules.derive}）从注册面
 * + {@code DmUsableCommand} 声明派生，再把两个面喂给 {@link CatalogTool}（新契约：<b>装配面派生、消费方信任</b>）。★ 迁移前那一版靠的是
 * {@code DirectiveWhitelist} 的 {@code sd.} 前缀规则现场剔 {@code sd.*}，该规则已按用户裁定"一条不留"删除 ⇒
 * 继续喂原始注册面只会测到<b>已不存在的机制</b>。★★ <b>断言一条都没放宽</b>：决策人该看见什么、不该看见什么，逐条与迁移前相同。
 *
 * <p>★★★ <b>2026-10-13 收尾测试第 2 层：夹具换到新契约面</b>（台账「批次 4」第 4 条）。旧 {@link #DM_DECLARED} 硬编码 {@code
 * map.CreateRegion / map.UpdateRegion / map.DeleteRegion / unit.DisbandUnit /
 * unit.SetStatus}，类注自称"与生产逐条一致" —— 而批次 4 的权限收紧把这 5 条<b>全部摘掉</b>了（{@code DmUsableCommand} 66 → 17），⇒
 * 那份夹具是在**为旧契约背书** （它继续绿，但已与生产脱钩）。本层把合成面换成<b>今天真在 DM 面上的命令</b>（逐条回 {@code simos-unit/src/main} 的
 * handler 核过）， 并把"装配期三条审计"一起跑起来：{@link CommandPermissionRules#derive}
 * 会当场抛出"声明了未注册的类型"，故夹具再也无法与生产悄悄分叉。
 *
 * <p>★ **判据只读权限、不读身份**（{@link CatalogVisibility} 的类注）：本用例用"换身份不换权限"两个方向把它钉死——
 * 身份字符串会随装配漂移，权限面才是判定本身。
 */
class CatalogVisibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * 合成**完整注册面**：五条**真在 DM 面上**的 unit 命令（逐条回 {@code simos-unit/src/main} 的 handler 核过：这五条都 {@code
   * implements DmUsableCommand}） + 两条**决策落点**（{@code ga.IssueDirective} / {@code
   * ga.SubmitVerdict}，只作为窄工具 对决策人可见、**不可嵌进令**） + 四条**GM-only**（批次 4 之后仍在 DM 面之外）。全部真在 {@code
   * PAYLOAD_HINTS} 里。
   */
  private static final Set<String> REGISTERED =
      Set.of(
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.AssignGovPost",
          "ga.IssueDirective",
          "ga.SubmitVerdict",
          "ga.PutInfo",
          "ga.CreateDecisionMaker",
          "ga.StartDecision",
          "ga.SetDecisionMakerAccess");

  /**
   * 本合成面里**打了 {@code DmUsableCommand}** 的那 5 条（与生产逐条一致 = {@code simos-unit} 的 {@code
   * PlanRouteHandler} / {@code CancelRouteHandler} / {@code SetGovSuperiorHandler} / {@code
   * SetJurisdictionHandler} / {@code AssignGovPostHandler}）。★ 落点两条与四条 {@code ga.*} 写口 <b>一条都不打</b>
   * —— 前者是被嵌的对象（"落点又被声明为 DM 可用"是装配故障），后者是批次 4 收紧掉的 DM 面外命令。
   */
  private static final Set<String> DM_DECLARED =
      Set.of(
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.AssignGovPost");

  /**
   * 装配面派生结果（与 {@code Shell} 里 {@code CommandPermissionRules.resolve(...)} 同一口径）：catalog 面 =
   * 完整注册面，可嵌令面 = 声明面。★ 落点用真 {@link ChannelAdmission#LANDING_POINTS}（{@code Shell} 送进去的就是它）。
   */
  private static final CommandFaces FACES =
      CommandPermissionRules.derive(REGISTERED, DM_DECLARED, ChannelAdmission.LANDING_POINTS);

  /** 决策人**应当**看到的那些：令里可嵌的领域命令 + 它自己能直接调的两条落点窄工具。 */
  private static final Set<String> DM_EXPECTED =
      Set.of(
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.AssignGovPost",
          "ga.IssueDirective",
          "ga.SubmitVerdict");

  /** 决策人**不得**看到的那些（它执行不了 ⇒ 目录里不该出现；用户要修的就是这个现象）。 */
  private static final Set<String> DM_INVISIBLE =
      Set.of(
          "ga.PutInfo", "ga.CreateDecisionMaker", "ga.StartDecision", "ga.SetDecisionMakerAccess");

  /** 夹具：catalog 面取派生出的注册面，可嵌令白名单面取派生出的可嵌令面（不再把原始注册面当白名单）。 */
  private static CatalogTool catalog() {
    return new CatalogTool(FACES.catalogTypes(), FACES.directiveTypes());
  }

  @Test
  void anUnrestrictedCallerSeesEveryRegisteredType() throws Exception {
    JsonNode body = body(catalog().execute(gmContext()));

    assertThat(typesOf(body))
        .as("★ 管辖者（MCP 口）看到的仍是**全量**——McpCoverageTest 逐条要 46 类，靠的就是这一支")
        .containsExactlyInAnyOrderElementsOf(REGISTERED);
    assertThat(body.get("payloadHints").size()).as("提示与清单同长（不留孤项）").isEqualTo(REGISTERED.size());
  }

  @Test
  void aDecisionMakerSeesOnlyWhatItCanActuallyTrigger() throws Exception {
    assertThat(FACES.directiveTypes())
        .as("★ 夹具的第二参是**派生面**（不是原始注册面）：里面只有打了 DmUsableCommand 的那几条")
        .containsExactlyInAnyOrderElementsOf(DM_DECLARED)
        .doesNotContainAnyElementsOf(DM_INVISIBLE)
        .as("★ 落点本身不得可嵌令 / 入队（否则决策人能把落点当效果再发一次 = 权限放大）")
        .doesNotContain("ga.IssueDirective", "ga.SubmitVerdict");

    JsonNode body = body(catalog().execute(dmContext()));

    assertThat(typesOf(body))
        .as("★ 决策人的可见面 = 令里可嵌的领域命令 ∪ 它能直接调的窄工具")
        .containsExactlyInAnyOrderElementsOf(DM_EXPECTED);
    assertThat(typesOf(body))
        .as("★ 这四条是用户要修的现象：决策人**执行不了**它们，就不该在目录里看见")
        .doesNotContainAnyElementsOf(DM_INVISIBLE);
    Set<String> hintKeys = new LinkedHashSet<>();
    body.get("payloadHints").fieldNames().forEachRemaining(hintKeys::add);
    assertThat(hintKeys)
        .as("提示表跟着清单走（不给看不见的类型留提示）")
        .containsExactlyInAnyOrderElementsOf(DM_EXPECTED);
  }

  @Test
  void theFilterIsDrivenByPermissionsNotByIdentity() throws Exception {
    // 换身份、不换权限：带上 GM 的身份，可见面**仍是决策人的**（判据不看身份）。
    ToolContext dmPermissionsGmIdentity = dmContext().withIdentity(AgentIdentity.external());
    assertThat(typesOf(body(catalog().execute(dmPermissionsGmIdentity))))
        .as("★ 身份是 external，但权限组受限 ⇒ 照旧过滤（判据读的是权限组）")
        .containsExactlyInAnyOrderElementsOf(DM_EXPECTED);

    // 反过来：带上决策人的身份、用不受限的权限组 ⇒ 全量。
    ToolContext gmPermissionsDmIdentity = gmContext().withIdentity(dmContext().identity());
    assertThat(typesOf(body(catalog().execute(gmPermissionsDmIdentity))))
        .as("★ 身份是 subagent，但权限组不受限 ⇒ 全量")
        .containsExactlyInAnyOrderElementsOf(REGISTERED);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 不受限的调用者（= {@code Shell#gmCaller()} 的形状：{@code allowAll()} + external 身份）。 */
  private static ToolContext gmContext() {
    return ToolContext.of(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        AgentIdentity.external());
  }

  /**
   * **真**决策人权限组（白名单 + 现算范围）：走 {@link DecisionCallerFactory} 的唯一入口，不手工拼。
   *
   * <p>★ B4b：白名单改用**测试侧金名单**（{@link DecisionFaceGolden#ALL}）——生产里那份静态 44 条已删，而本用例不启 {@code
   * Shell}（它要的是"同一份注册面、两个调用者"，不是真壳），故取规格本身。catalog 的可见类型由权限面驱动
   * （见类注），所以这里必须是**完整**的决策人面，不能只放本用例用到的那条工具。
   */
  private static ToolContext dmContext() {
    // ★★ 2026-10-13 第 2 层：归属住世界表里、按 dmId 索引 ⇒ 夹具<b>先建 dm、再带着它建世界</b>（落表）。
    //   本用例判的是"工具面可见性"（catalog），但夹具仍必须给出**真归属**——否则它在为"裸决策人"这种
    //   生产上只该 deny-all 的形态背书。
    ScopeFixtures.Affiliated affiliated = ScopeFixtures.nationDm("dm-a", "alpha");
    DecisionMaker dm = affiliated.maker();
    GaState sd = GaState.empty().withDecisionMakers(Map.of(new DecisionMakerId("dm-a"), dm));
    SimulationState state =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(),
            ScopeFixtures.units(),
            io.mosire.simos.app.testing.WorldSlices.gaOnly(sd),
            affiliated);
    return DecisionCallerFactory.defaults(authorizer(), DecisionFaceGolden.ALL)
        .callerFor(dm, state, ScopeFixtures.MAP_ID);
  }

  /** 决策人路径的执行器（本用例证的是可见性，不是审批链）：走真壳那套审批装配。 */
  private static ToolCallAuthorizer authorizer() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending)), List.of(), pending, Duration.ofMinutes(1), null);
    return ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator);
  }

  private static JsonNode body(ToolResult result) throws Exception {
    assertThat(result.success()).as("调用应成功：%s", result.message()).isTrue();
    return JSON.readTree(result.message());
  }

  private static Set<String> typesOf(JsonNode body) {
    Set<String> out = new LinkedHashSet<>();
    List<String> list = new ArrayList<>();
    body.get("types").forEach(node -> list.add(node.asText()));
    out.addAll(list);
    assertThat(out).as("清单不得有重复项").hasSameSizeAs(list);
    return out;
  }
}
