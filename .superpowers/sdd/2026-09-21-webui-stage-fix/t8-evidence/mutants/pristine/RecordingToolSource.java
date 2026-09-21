package io.mosire.simos.app.gm;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具源装饰器（T8）：把委托源的每个工具**执行**记进 {@link GmToolUsage}，其余语义逐字转交。
 *
 * <p>★ **只包一个口**：{@code Shell} 只用它包现有 MCP 口（{@code EXTERNAL_WITH_GM}）的源 ⇒ 记录的就是"GM MCP 的工具使用"， 决策人
 * MCP 口（{@code DECISION_AGENT}）**不包**、不混入。
 *
 * <p>★ **只碰 {@code execute}**：{@code name}/{@code description}/{@code jsonSchema}/{@code
 * spec}/{@code gate}/ {@code resources}/{@code ledgerArgs} 全部原样转交——工具面（{@code
 * tools/list}）、权限判定、审批闸位、资源声明与 脱敏参数视图不因装饰而变。这是"包装不改变被包装者"的落点（登记进 {@code ToolRegistry} 的是装饰后的实例）。
 *
 * <p>★ **失败也记**：{@code ToolResult} 带 code = 失败，照记（含 code）。工具抛出的**意外**异常不记、原样外泄——工具契约要求 把边界错误折成
 * {@link ToolResult}，异常是违约，不伪装成一条"工具使用"（与"工具不存在的调用不记"同口径）。
 */
public final class RecordingToolSource implements ToolSource {

  private final ToolSource delegate;
  private final GmToolUsage usage;

  private RecordingToolSource(ToolSource delegate, GmToolUsage usage) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.usage = Objects.requireNonNull(usage, "usage");
  }

  /** 建立装饰源：{@code delegate} 的工具清单每次读取时各包一层记录壳。 */
  public static RecordingToolSource record(ToolSource delegate, GmToolUsage usage) {
    return new RecordingToolSource(delegate, usage);
  }

  @Override
  public String id() {
    return delegate.id();
  }

  @Override
  public List<AgentTool> listTools() {
    return delegate.listTools().stream()
        .<AgentTool>map(tool -> new RecordingTool(tool, usage))
        .toList();
  }

  @Override
  public AutoCloseable onChange(Runnable listener) {
    return delegate.onChange(listener);
  }

  /** 单个工具的记录壳：只有 {@link #execute} 有行为变化，其余一律转交。 */
  static final class RecordingTool implements AgentTool {

    private final AgentTool delegate;
    private final GmToolUsage usage;

    RecordingTool(AgentTool delegate, GmToolUsage usage) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      this.usage = Objects.requireNonNull(usage, "usage");
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
      ToolResult result = delegate.execute(context);
      usage.record(name(), result.success(), result.code(), System.currentTimeMillis());
      return result;
    }
  }
}
