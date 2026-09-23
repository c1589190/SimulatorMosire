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
 * unit.*} **必须留着**：它们在**令里可以嵌**（{@code DirectiveWhitelist} 的判据），只按"能不能直接调"过滤会**过窄**。
 *
 * <p>★ **判据只读权限、不读身份**（{@link CatalogVisibility} 的类注）：本用例用"换身份不换权限"两个方向把它钉死——
 * 身份字符串会随装配漂移，权限面才是判定本身。
 */
class CatalogVisibilityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 合成注册面：三个 map 命令 + 两个 unit 命令 + 六条 sd 命令（全部真在 {@code PAYLOAD_HINTS} 里）。 */
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

  @Test
  void anUnrestrictedCallerSeesEveryRegisteredType() throws Exception {
    JsonNode body = body(new CatalogTool(REGISTERED).execute(gmContext()));

    assertThat(typesOf(body))
        .as("★ 管辖者（MCP 口）看到的仍是**全量**——McpCoverageTest 逐条要 46 类，靠的就是这一支")
        .containsExactlyInAnyOrderElementsOf(REGISTERED);
    assertThat(body.get("payloadHints").size()).as("提示与清单同长（不留孤项）").isEqualTo(REGISTERED.size());
  }

  @Test
  void aDecisionMakerSeesOnlyWhatItCanActuallyTrigger() throws Exception {
    JsonNode body = body(new CatalogTool(REGISTERED).execute(dmContext()));

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
    assertThat(typesOf(body(new CatalogTool(REGISTERED).execute(dmPermissionsGmIdentity))))
        .as("★ 身份是 external，但权限组受限 ⇒ 照旧过滤（判据读的是权限组）")
        .containsExactlyInAnyOrderElementsOf(DM_EXPECTED);

    // 反过来：带上决策人的身份、用不受限的权限组 ⇒ 全量。
    ToolContext gmPermissionsDmIdentity = gmContext().withIdentity(dmContext().identity());
    assertThat(typesOf(body(new CatalogTool(REGISTERED).execute(gmPermissionsDmIdentity))))
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

  /** **真**决策人权限组（白名单 + 现算范围）：走 {@link DecisionCallerFactory} 的唯一入口，不手工拼。 */
  private static ToolContext dmContext() {
    DecisionMaker dm = ScopeFixtures.nationDm("dm-a", "alpha");
    SdState sd = SdState.empty().withDecisionMakers(Map.of(new DecisionMakerId("dm-a"), dm));
    SimulationState state = ScopeFixtures.state(ScopeFixtures.mapOf(), ScopeFixtures.units(), sd);
    return DecisionCallerFactory.defaults(authorizer()).callerFor(dm, state, ScopeFixtures.MAP_ID);
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
