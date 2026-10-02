package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.SimosToolSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **判据 J1（spec §2.1）**：MCP 口的 {@code ToolContext} 由**显式构造**的 GM 权限组承担 —— **不是** {@code
 * AgentPermissionSet.unrestricted(...)} 那种"资源维不表态"的取值。
 *
 * <p>★ **本用例的判别力全在"表过态"三个字上**：{@code unrestricted(...)} 走 6 参兼容构造 ⇒ {@code resourceScopes} 为 null ⇒
 * 规范化成**空图**；而空图在 AgentLib 的判定表里是"本层不表态"（回落工具缺省策略）。当前工具面下两者**行为恰好相同**
 * （工具声明的三命名空间缺省策略本就放行写），故**只有结构断言**（可达面是显式数据）能区分它们 —— 这正是 spec §1.1 记的根因形态："断言在跑，但永远通过"。
 *
 * <p>★ 身份那条**故意与权限分开**：{@code AgentIdentity.external()} 是**审批面语义**（"这不是本进程派生的下级"），
 * 与权限级别正交（用户裁定①）——退回 {@code unrestricted} 的变异体**不该**在这条上红，它该红的只有资源维那条。
 */
class GmPermissionGroupTest {

  @TempDir Path tempDir;

  /**
   * GM 资源可达面的逐命名空间表态清单（与 {@code Shell#gmPermissionSet} 实际表态的命名空间同源，见 {@code ToolSupport} 的 manifest
   * 常量）：map / social / unit / sd / economy（R2a 起）/ actor（S1 阶段 2 起）/ army（D1 起）。
   */
  private static final List<String> GM_NAMESPACES =
      List.of("map", "social", "unit", "sd", "economy", "actor", "army");

  @Test
  void gmCallerDeclaresEveryNamespaceInsteadOfLeavingTheResourceDimensionSilent() {
    ToolContext caller = Shell.gmCaller();
    ResourceScopeMap scopes = caller.permissions().resourceScopes();

    assertThat(scopes.namespaces())
        .as("★ J1：资源维**表过态**（空图 = 不表态 = 判定走缺省分支恒放行，见 spec §1.1）")
        .containsExactlyInAnyOrderElementsOf(GM_NAMESPACES);
    for (String namespace : GM_NAMESPACES) {
      ResourceScope scope = scopes.declaredScope(namespace);
      assertThat(scope).as("%s 命名空间已表态（不是 null=未表态）", namespace).isNotNull();
      assertThat(scope.unrestricted())
          .as("GM 在 %s 上不设限（用户裁定：MCP 与 GM 同权限级，想改什么改什么）", namespace)
          .isTrue();
      assertThat(scope.prefixes()).as("「不限」与「列出哪些地方」是两种取值").isEmpty();
    }
  }

  @Test
  void gmCallerAllowsEveryToolOfTheGmFaceAndKeepsBothExplicitGrants() {
    ToolContext caller = Shell.gmCaller();
    AgentPermissionSet permissions = caller.permissions();

    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      List<String> gmFace =
          shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList();
      assertThat(gmFace).as("GM 面非空（扫到 0 条是『扫描器静默』陷阱）").isNotEmpty();
      assertThat(gmFace).as("GM 组的白名单必须覆盖 GM 面的**每一条**工具名").allMatch(permissions::isToolAllowed);
    }

    assertThat(permissions.allowedTools())
        .as("GM 是全工具白名单（通配形态，不是一份会漂移的手抄名单）")
        .containsExactly(AgentPermissionSet.ALL_TOOLS);
    assertThat(permissions.deniedTools()).as("没有显式拒绝项").isEmpty();
    assertThat(permissions.sensitiveAllowed()).as("★ 敏感位必须显式放行：写工具的敏感位在硬拒规则里『默认拒绝』").isTrue();
    assertThat(permissions.destructiveAllowed()).isTrue();
    assertThat(permissions.readOnly())
        .as("★ readOnly 在 AgentLib 里是『拒一切工具』（不是『只读工具放行』），GM 口绝不能带它")
        .isFalse();
  }

  @Test
  void gmCallerKeepsTheExternalIdentityAndTheDefaultBucket() {
    ToolContext caller = Shell.gmCaller();

    assertThat(caller.caller())
        .as("DEFAULT 是满足该工具面全部工具的最小桶（GUEST 下三条通用写被硬拒，见 spec §3.2 的取代说明）")
        .isEqualTo(AccessToken.DEFAULT);
    assertThat(caller.identity())
        .as("身份 = 外部 MCP 面（审批面据它认得出『这不是我派的下级』；用户裁定①）")
        .isEqualTo(AgentIdentity.external());
    assertThat(caller.identity().instanceId()).isEqualTo(AgentIdentity.EXTERNAL_ID);
  }
}
