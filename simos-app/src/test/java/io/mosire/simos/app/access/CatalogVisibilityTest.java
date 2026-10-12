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
import io.mosire.simos.ga.command.CommandFaces;
import io.mosire.simos.ga.command.CommandPermissionRules;
import io.mosire.simos.sd.channel.ChannelAdmission;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
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
 * <p>★ **`sd.PutInfo` / `sd.CreateDecisionMaker` / `sd.StartDecision` / `sd.SetDecisionMakerAccess`
 * 必须对决策人消失** ——那正是用户要修的现象（旧行为下决策人照着目录去试，每一试都白烧一轮真 LLM 调用）。而 {@code map.CreateRegion} / {@code
 * unit.*} **必须留着**：它们在**令里可以嵌**（{@link CommandFaces#directiveTypes()} 的判据），只按"能不能直接调"过滤会**过窄**。
 *
 * <p>★★ <b>2026-10-12 批次 1 迁移（接缝位移，不是行为回归）</b>：夹具从"把含 {@code sd.*} 的<b>原始注册面</b>同时当作 catalog
 * 面与可嵌令白名单"改成"<b>装配面派生</b>" —— {@link #FACES} 经 ga 规则层 （{@code CommandPermissionRules.derive}）从注册面
 * + {@code DmUsableCommand} 声明派生，再把两个面喂给 {@link CatalogTool}（新契约：<b>装配面派生、消费方信任</b>）。★ 迁移前那一版靠的是
 * {@code DirectiveWhitelist} 的 {@code sd.} 前缀规则现场剔 {@code sd.*}，该规则已按用户裁定"一条不留"删除 ⇒
 * 继续喂原始注册面只会测到<b>已不存在的机制</b>。★★ <b>断言一条都没放宽</b>：决策人该看见什么、不该看见什么，逐条与迁移前相同。
 *
 * <p>★ **判据只读权限、不读身份**（{@link CatalogVisibility} 的类注）：本用例用"换身份不换权限"两个方向把它钉死——
 * 身份字符串会随装配漂移，权限面才是判定本身。
 */
class CatalogVisibilityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 合成**完整注册面**：三个 map 命令 + 两个 unit 命令 + 六条 sd 命令（全部真在 {@code PAYLOAD_HINTS} 里）。 */
  private static final Set<String> REGISTERED =
      Set.of(
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "sd.PutInfo",
          "sd.CreateDecisionMaker",
          "sd.IssueDirective",
          "sd.SubmitVerdict",
          "sd.StartDecision",
          "sd.SetDecisionMakerAccess");

  /**
   * 本合成面里**打了 {@code DmUsableCommand}** 的那 5 条（与生产逐条一致：三条 map 区域命令 + 两条 unit 命令，见各自的 handler）。★ 六条
   * {@code sd.*} <b>一条都不打</b> —— 28 条 {@code sd.*} 全在 DM 面外。
   */
  private static final Set<String> DM_DECLARED =
      Set.of(
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "unit.DisbandUnit",
          "unit.SetStatus");

  /**
   * 装配面派生结果（与 {@code Shell} 里 {@code CommandPermissionRules.resolve(...)} 同一口径）：catalog 面 =
   * 完整注册面，可嵌令面 = 声明面。★ 落点用真 {@link ChannelAdmission#LANDING_POINTS}（{@code Shell} 送进去的就是它）。
   */
  private static final CommandFaces FACES =
      CommandPermissionRules.derive(REGISTERED, DM_DECLARED, ChannelAdmission.LANDING_POINTS);

  /** 决策人**应当**看到的那些：领域命令（令里可嵌）+ 它自己能直接调的两条 sd 写。 */
  private static final Set<String> DM_EXPECTED =
      Set.of(
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "sd.IssueDirective",
          "sd.SubmitVerdict");

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
        .as("★ 夹具的第二参是**派生面**（不是原始注册面）：里面本就没有一条 sd.*")
        .doesNotContain("sd.PutInfo", "sd.IssueDirective", "sd.SubmitVerdict")
        .noneMatch(type -> type.startsWith("sd."));

    JsonNode body = body(catalog().execute(dmContext()));

    assertThat(typesOf(body))
        .as("★ 决策人的可见面 = 令里可嵌的领域命令 ∪ 它能直接调的窄工具")
        .containsExactlyInAnyOrderElementsOf(DM_EXPECTED);
    assertThat(typesOf(body))
        .as("★ 这四条是用户要修的现象：决策人**执行不了**它们，就不该在目录里看见")
        .doesNotContain(
            "sd.PutInfo",
            "sd.CreateDecisionMaker",
            "sd.StartDecision",
            "sd.SetDecisionMakerAccess");
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
    DecisionMaker dm = ScopeFixtures.nationDm("dm-a", "alpha");
    SdState sd = SdState.empty().withDecisionMakers(Map.of(new DecisionMakerId("dm-a"), dm));
    SimulationState state = ScopeFixtures.state(ScopeFixtures.mapOf(), ScopeFixtures.units(), sd);
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
