package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.ForkBranch;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code simos.fork}（spec §7.1 写工具）：从源分支分岔出新分支（{@code ForkBranch}）。
 *
 * <p>无资源命名空间（spec §7.1 表里 resources 列是 —）⇒ 不声明资源面。分岔行的类型串由 {@code Timeline} 钉住，本类不重复写。
 */
public final class ForkTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.fork";

  private final CoreSimos core;
  private final String initiator;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CoreSimos 是本工具的唯一写入口（只调 submit），非内部表示外泄；写面仍受权限/审批闸约束")
  public ForkTool(CoreSimos core, String initiator) {
    this.core = core;
    this.initiator = initiator;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "从源分支分岔：{source, expectedRevision, newBranch} → ForkBranch";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("source", ToolSupport.prop("string", "源分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "源分支期望 head（乐观并发）"));
    props.put("newBranch", ToolSupport.prop("string", "新分支名"));
    return ToolSupport.schema(props, List.of("source", "expectedRevision", "newBranch"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "分岔 source="
            + args.get("source")
            + " expected="
            + args.get("expectedRevision")
            + " newBranch="
            + args.get("newBranch"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String id = UUID.randomUUID().toString();
    try {
      Map<String, Object> args = context.arguments();
      ForkBranch command =
          new ForkBranch(
              id,
              id,
              initiator,
              new BranchId(ToolSupport.requiredText(args, "source")),
              new RevisionId(ToolSupport.requiredLong(args, "expectedRevision")),
              new BranchId(ToolSupport.requiredText(args, "newBranch")));
      return ToolSupport.fold(core.submit(command), id, id);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "分岔提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
