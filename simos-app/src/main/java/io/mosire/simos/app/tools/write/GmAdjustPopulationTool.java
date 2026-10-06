package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * ★★ {@code simos.gm.adjustPopulation}（D4，2026-10-22）：<b>GM 直接增/减/转移家户人口</b>的薄适配器。
 *
 * <p>★★ <b>语义 = 现有 {@code simos.social.household.members} 逐字复用</b>：本类持有它的一个实例，{@code execute}
 * 转发（系统上下文 + 原参数 {@code preview}/{@code branch}/{@code expectedRevision} 透传），不在这条路径上另写人口推导 ——
 * 人口口径仍只有 {@code HouseholdBook} / 三条 social 命令那一份；preview/apply/拒绝语义与既有工具完全一致。
 *
 * <p>★ <b>为什么外层还要声明/断言资源</b>：转发用的系统上下文是无限权限（否则被转发工具自己的断言会拒），因此"调用者到底能不能写
 * social"必须在<b>本工具</b>这层先用调用者上下文判完（{@code social:*} 写断言）——两层各司其职，不靠内层替外层把关。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 social 命名空间。工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}。
 */
public final class GmAdjustPopulationTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.adjustPopulation";

  /** 本工具只写 social 命名空间（GM 侧 unlimited ⇒ 逐条判通过）。 */
  private static final ResourceManifest SOCIAL_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  private final SocialHouseholdMembersTool delegate;

  public GmAdjustPopulationTool(CoreSimos core, QueryService query, String initiator) {
    this.delegate = new SocialHouseholdMembersTool(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 直接增/减/转移家户人口（逐字复用 "
        + SocialHouseholdMembersTool.NAME
        + " 的语义与载荷）："
        + delegate.description()
        + " ★ 本入口只写 social 命名空间（action=add/remove/transfer，preview 缺省 true）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    // ★ 逐字复用被转发工具的 schema：两个工具的参数面与校验口径同源，不另写第二份。
    return delegate.jsonSchema();
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SOCIAL_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "人口调整 action="
            + args.get("action")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + " count="
            + args.get("count"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      // ★ 先用**调用者**上下文判 social 写；转发路径用系统上下文（内层不断言调用者围栏）。
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      AgentPermissionSet systemPermissions = AgentPermissionSet.unrestricted(AccessToken.SYSTEM);
      ToolContext forwarded =
          new ToolContext(
                  AccessToken.SYSTEM,
                  systemPermissions,
                  context.config(),
                  context.arguments(),
                  AgentIdentity.external())
              .withResources(ResourceAuthorizer.of(systemPermissions, delegate.resources()));
      return delegate.execute(forwarded);
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（与其余写工具同一条）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", name() + " 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
