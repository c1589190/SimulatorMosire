package io.mosire.simos.ga.tools;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具源装饰器（T8）：把委托源的每个工具**执行**记进 {@link GmToolUsage}，其余语义逐字转交。
 *
 * <p>★ **只包一个源**：{@code Shell} 只把它套在 GM 组（{@link SimosToolSource.Role#GM}，= 运行时 MCP 口）的源上 ⇒
 * 记录的就是"GM MCP 的工具使用"。决策人**不经 MCP**（spec §四.3：决策人没有暴露 MCP），故不存在"另一个口混入"的问题。
 *
 * <p>★ **只碰 {@code execute}**：{@code name}/{@code description}/{@code jsonSchema}/{@code
 * spec}/{@code gate}/ {@code resources}/{@code ledgerArgs}/{@code roles} 全部原样转交——工具面（{@code
 * tools/list}）、权限判定、审批闸位、资源声明、**角色声明**与 脱敏参数视图不因装饰而变。这是"包装不改变被包装者"的落点（登记进 {@code ToolRegistry}
 * 的是装饰后的实例）。
 *
 * <p>★★ <b>2026-10-17 批 R（计划 §4.2 / 审核 P1-1）：{@code roles()} 必须转发</b>。库的判定读的是 {@link
 * AgentTool#roles()}： 记录壳不转发 ⇒ 包装后的真实注册表里声明变成空集（= 本维不表态）⇒ 角色判定被静默跳过（审核探针实测 {@code raw_roles=[GM];
 * wrapped_roles=[]}）。★ 记录壳不是 {@link io.mosire.simos.ga.spi.SimosTool}，这里透传的是**库的方法**，
 * 不违反"领域工具不得自行覆写 {@code roles()}"。
 *
 * <p>★ **失败也记**：{@code ToolResult} 带 code = 失败，照记（含 code）。工具抛出的**意外**异常不记、原样外泄——工具契约要求 把边界错误折成
 * {@link ToolResult}，异常是违约，不伪装成一条"工具使用"（与"工具不存在的调用不记"同口径）。
 */
public final class RecordingToolSource implements ToolSource {

  /** 工具调用事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

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

    /**
     * ★★ 2026-10-17 批 R（计划 §4.2 / 审核 P1-1）：角色声明**原样转发**。
     *
     * <p>库的判定（{@code ToolExecutionGuard} → {@code PermissionChecker}）读的是 {@link
     * AgentTool#roles()}；记录壳不转发的一天， 包装后的真实注册表里每条工具都成了"本维不表态"⇒ 角色不相交的调用者照样能过（审核探针实测 {@code
     * DM_wrapped_denied=false}）。★ 这里只转交，不做任何增删——角色仍只从被包装工具的 {@code SimosTool.toolRoles()} 派生。
     */
    @Override
    public Set<String> roles() {
      return delegate.roles();
    }

    @Override
    public ToolResult execute(ToolContext context) {
      // ★ 2026-10-23 L1：工具调用面三行（发起 DEBUG / 具名拒绝 INFO / 成功结果 DEBUG）——只记工具名、
      //   调用者身份与结果码，绝不记 arguments（那是载荷明文/模型输入），也不记 identity.goal()（那是用户文本）。
      String caller = context.caller() == null ? "-" : context.caller().name();
      String identity =
          context.identity() == null ? "-" : String.valueOf(context.identity().instanceId());
      TOOL.debug(
          LogEvent.of(
              "TOOL_CALL_START",
              GaLogSource.GA_TOOL_CALL,
              "tool",
              name(),
              "caller",
              caller,
              "identity",
              identity));
      ToolResult result = delegate.execute(context);
      usage.record(name(), result.success(), result.code(), System.currentTimeMillis());
      if (result.success()) {
        TOOL.debug(
            LogEvent.of(
                "TOOL_CALL_END",
                GaLogSource.GA_TOOL_CALL,
                "tool",
                name(),
                "caller",
                caller,
                "identity",
                identity,
                "success",
                result.success(),
                "code",
                result.code()));
      } else {
        // ★ 用户 2026-10-23：被拒绝一律 INFO（成功结果仍保持 DEBUG）。
        TOOL.info(
            LogEvent.of(
                "TOOL_CALL_REJECTED",
                GaLogSource.GA_TOOL_CALL,
                "tool",
                name(),
                "caller",
                caller,
                "identity",
                identity,
                "success",
                result.success(),
                "code",
                result.code()));
      }
      return result;
    }
  }
}
