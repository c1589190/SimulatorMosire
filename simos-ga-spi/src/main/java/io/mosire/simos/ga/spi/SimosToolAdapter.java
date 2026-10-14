package io.mosire.simos.ga.spi;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>2026-10-14 AgentLib Docs/Workspace 接入（设计书 §4.1）：把一条<b>外来</b> {@link AgentTool} 适配成我们的
 * {@link SimosTool}。</b>
 *
 * <p>★ <b>为什么必须有本类</b>（不是优化、是必需）：外来工具面（{@code DocsTools$DocTool} / {@code
 * WorkspaceTools$WorkspaceTool}）只实现 {@link AgentTool} —— {@code ToolRole} 在 agentlib jar 里**0 命中**
 * （反编译核实），"角色"是 Simos 特有的概念。而我们的装配面要求桶里每条工具都是 {@code SimosTool} （{@code
 * ToolRoleAudit.requireFace}）⇒ **原样塞进桶 = 装配当场抛**（设计书 §2.2）。
 *
 * <p>★ <b>本类对文档零知识</b>：不 import docs/workspace 的任何类型（设计书 §4.1 的落点理由）。它只认识 agentlib 的 {@link
 * AgentTool} / {@link ToolContext} / {@link ToolGate} 与我们的 {@link SimosTool} / {@link ToolRole}
 * 五个契约类型 ⇒ 住在 {@code simos-ga-spi}（已依赖 {@code agentlib-mosire}），而"这 10 条工具各是什么角色、 审批摘要怎么合成"
 * 这类**业务判断**留在组合根（{@code SimosToolSource}）。
 *
 * <p>★ <b>三条不可破坏的点（不变量）</b>：
 *
 * <ol>
 *   <li><b>I3 —— 逐字委托</b>：{@code name / description / jsonSchema / spec / resources / ledgerArgs /
 *       execute} 七条**原样转给被包装的工具**（不吞结果、不改参数、不加白名单）。{@code resources()} 尤其要紧：那是外来工具自己的 资源声明（{@code
 *       READ_MANIFEST} / {@code WRITE_MANIFEST}），我们的权限判定靠它。
 *   <li><b>I4 —— {@code gate()} 不得静默丢弃</b>：审批策略由装配方**注入**（{@link Function}）。本类**不给缺省值**
 *       ——"缺省放行"就是绕过审批链；注入方必须逐条给策略（构造器 fail-closed）。
 *   <li><b>I1 —— {@code roles()} 是唯一拼写点</b>：角色由构造注入的**不可变**集合承载；空集当场抛（空集 = 谁都看不见 = 装配故障，与 {@link
 *       SimosTool#roles()} 的契约同口径）。
 * </ol>
 *
 * <p>★ <b>为什么不在这里声明"哪条工具给谁"</b>：那会让本类认识具体的工具名 ⇒ 契约层反过来依赖组合根的知识。角色表在装配处 （{@code
 * SimosToolSource.EXTERNAL_TOOL_ROLES}），**逐条、无缺省**。
 */
public final class SimosToolAdapter implements SimosTool {

  private final AgentTool delegate;
  private final Set<ToolRole> roles;
  private final Function<ToolContext, ToolGate> gateOf;

  /**
   * 包装一条外来工具。
   *
   * @param delegate 被包装的外来工具（非 null）。★ 名字**不改**（I7：保留他们的原名 {@code doc_*} / {@code
   *     workspace_*}——改名会让他们的文档/日志/清单对不上）
   * @param roles 该工具的角色标签（非 null、**非空**；必须是 {@code {GM}} / {@code {DM}} / {@code {GM, DM}} 之一）
   * @param gateOf 审批策略（非 null；由装配方按工具名**逐条**给，本类不提供任何缺省）
   */
  public SimosToolAdapter(
      AgentTool delegate, Set<ToolRole> roles, Function<ToolContext, ToolGate> gateOf) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    Set<ToolRole> declared = Objects.requireNonNull(roles, "roles");
    if (declared.isEmpty()) {
      throw new IllegalArgumentException(
          "SimosToolAdapter 装配故障："
              + delegate.name()
              + " 的角色集是空的 —— 空集 = 谁都看不见 = 装配故障（SimosTool#roles 的契约：必须是"
              + " {GM} / {DM} / {GM, DM} 之一）。不要静默放行。");
    }
    this.roles = Set.copyOf(declared);
    // ★ I4：gate 策略**必须**由装配方给。这里没有"没给就当 ALLOW"的兜底——那正是绕过审批链的形态。
    this.gateOf = Objects.requireNonNull(gateOf, "gateOf（审批策略；吞掉它 = 绕过审批链，见 I4）");
  }

  /** 被包装的外来工具（审计/用例读回用；本类不改它的任何行为）。 */
  public AgentTool delegate() {
    return delegate;
  }

  // ── I3：以下七条一律逐字委托 ────────────────────────────────────────────────

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public String description() {
    return delegate.description();
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return delegate.jsonSchema();
  }

  @Override
  public ToolSpec spec() {
    return delegate.spec();
  }

  /** ★ 原样转发：这是外来的资源声明（{@code READ_MANIFEST} / {@code WRITE_MANIFEST}），我们的权限判定靠它。 */
  @Override
  public ResourceManifest resources() {
    return delegate.resources();
  }

  @Override
  public Map<String, Object> ledgerArgs(ToolContext context) {
    return delegate.ledgerArgs(context);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    return delegate.execute(context);
  }

  // ── 两条**我们**的语义（外来工具面没有）：角色与审批 ──────────────────────────

  /** I1：`roles()` 是"这个工具能被谁用"的唯一拼写点（不可变副本）。 */
  @Override
  public Set<ToolRole> roles() {
    return roles;
  }

  /** I4：审批策略由装配方注入（本类只转调，不解释、不吞）。 */
  @Override
  public ToolGate gate(ToolContext context) {
    return gateOf.apply(context);
  }
}
