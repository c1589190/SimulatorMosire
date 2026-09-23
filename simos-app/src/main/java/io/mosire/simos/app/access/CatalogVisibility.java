package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ **catalog 的可见性判据（唯一一份）**：{@code simos.command.catalog} 对**哪个调用者**列出**哪些命令类型**。
 *
 * <p>★★ **用户 2026-09-23 裁定**：「改造现有 catalog，按调用者权限过滤」——理由：catalog 原本不认人，于是决策人能看到
 * {@code sd.PutInfo} / {@code sd.AdjudicateTick} / {@code sd.CreateDecisionMaker} 这些**它根本执行不了**的类型。"系统允许你干什么"这半
 * 必须与**真权限面同源**，否则模型会照着目录去试，每一试都白烧一轮。
 *
 * <p>★★ **两条分支**：
 *
 * <ol>
 *   <li>**不受限（GM / MCP 口）⇒ 全量**。判据是 {@link AgentPermissionSet#ALL_TOOLS} 这个标记（{@code "*"}）在不在白名单里——
 *       {@code AgentPermissionSet#isToolAllowed("*")} 恰好等价于"白名单里有 {@code *}"（见其字节码：{@code
 *       contains("*") || contains(x)}）。★ 用**权限**判而不是用**身份**判：身份字符串会随装配漂移，权限面才是判定本身。
 *       ★ 这也保证 {@code McpCoverageTest}（走 GM 的 MCP 口、逐条要求全量 46 类）**行为不变**；
 *   <li>**受限（决策人）⇒ 两来源的并集**：
 *       <ul>
 *         <li>{@code permissions().isToolAllowed(type)}——他能**直接调**的窄工具（窄工具的 {@code name()} 逐字等于
 *             {@code commandType()}，见 {@code AbstractNarrowWriteTool}，故工具名可以直接当类型名用）。典型 = {@code
 *             sd.IssueDirective} / {@code sd.SubmitVerdict}；
 *         <li>{@link DirectiveWhitelist#allowedTypes()}——**令里可以嵌**的领域命令（"全量 − {@code sd.*} − 通用写"）。 ★ **这一支
 *             不能省**：只按 `isToolAllowed` 会**过窄**（决策人明明能在令里用 {@code map.CreateRegion}/{@code unit.*}，
 *             却看不到它们），而那条规则**已经有唯一实现**（{@code DirectiveWhitelist}，{@code sd.IssueDirective} 的校验
 *             就用它）⇒ 直接复用，绝不在这里再写一遍"哪些算领域命令"。
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>★ 判据放进本类（而不是写在 {@code CatalogTool#execute} 里）是为了让它可被**单测**、也让"两条分支"有名字。{@code
 * CatalogTool} 只调它。
 */
public final class CatalogVisibility {

  /** 令里可嵌的领域命令类型（构造期从注册面派生，见 {@link DirectiveWhitelist}）。 */
  private final Set<String> embeddedTypes;

  /**
   * @param registeredTypes 本世界已注册的命令类型（与 {@code Shell} 注册的 handler 同源）
   * @throws NullPointerException {@code registeredTypes} 为 null
   */
  public CatalogVisibility(Set<String> registeredTypes) {
    Objects.requireNonNull(registeredTypes, "registeredTypes");
    this.embeddedTypes = new DirectiveWhitelist(registeredTypes).allowedTypes();
  }

  /**
   * 该类型对**这个调用者**是否可见。
   *
   * @param type 一个已注册的命令类型
   * @param context 本次调用上下文（判据只读它的权限组，不读身份）
   */
  public boolean visible(String type, ToolContext context) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(context, "context");
    if (isUnrestricted(context.permissions())) {
      return true;
    }
    return context.permissions().isToolAllowed(type) || embeddedTypes.contains(type);
  }

  /**
   * 该权限组是否**不受工具面限制**（GM / MCP 口 / {@code AgentPermissionSet.system()}）。
   *
   * <p>★ 只问"{@code *} 在不在白名单里"这一件事——不叠加资源范围、不叠加 {@code readOnly} 之类的开关：那些说的是"能碰哪些资源"，
   * 而 catalog 要回答的是"有哪些类型存在且你有途径触发"。
   */
  public static boolean isUnrestricted(AgentPermissionSet permissions) {
    Objects.requireNonNull(permissions, "permissions");
    return permissions.isToolAllowed(AgentPermissionSet.ALL_TOOLS);
  }
}
