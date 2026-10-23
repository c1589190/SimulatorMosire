package io.mosire.simos.app.tools.ga;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把 AgentLib {@code DecisionPackageTools} 产出的五工具接到 Simos 的角色/装配面。
 *
 * <p>这是**纯委托**：名称、描述、schema、spec、gate、resources、ledgerArgs、execute 全部原样转发； 只额外声明 {@code
 * toolRoles()={GM}}，让装配期角色审计仍只读 Simos 的唯一角色拼写点。 五工具本体仍由 AgentLib 提供，Simos 不复制其 schema/错误码。
 */
public final class AgentLibDecisionTool implements SimosTool {

  private final AgentTool delegate;

  public AgentLibDecisionTool(AgentTool delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

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

  @Override
  public ToolGate gate(ToolContext context) {
    return delegate.gate(context);
  }

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

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
