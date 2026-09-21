package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 专用窄工具的共同落点（spec §八.3，N9）：**命令类型固定**、参数只有载荷与坐标，最终仍走 {@code core.submit}（铁律 2）。
 *
 * <p>★ **不给通用写**：决策 Agent / GM 的工具面里没有 {@code simos.command.submit}——它们只能用这些 type
 * 固定的窄工具；因此"选什么命令"不再是模型可自由发挥的面。
 *
 * <p>★ 敏感写：{@code spec().sensitive()=true} + {@link ToolGate.Ask}（走审批留痕）。
 */
abstract class AbstractNarrowWriteTool implements AgentTool {

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  AbstractNarrowWriteTool(CoreSimos core, String initiator, String mapId) {
    this.core = core;
    this.initiator = initiator;
    this.mapId = mapId;
  }

  /** 固定命令类型（同时是工具名）。 */
  protected abstract String commandType();

  /** 审批摘要里的一行人话。 */
  protected abstract String summary(Map<String, Object> args);

  @Override
  public final String name() {
    return commandType();
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("payloadJson", ToolSupport.prop("string", "命令载荷 JSON 文本"));
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSupport.schema(props, List.of("branch", "expectedRevision"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String id = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAllWrite(context, mapId);
      Map<String, Object> args = context.arguments();
      CommandEnvelope command =
          new CommandEnvelope(
              id,
              id,
              initiator,
              new BranchId(ToolSupport.requiredText(args, "branch")),
              new RevisionId(ToolSupport.requiredLong(args, "expectedRevision")),
              commandType(),
              ToolSupport.optionalText(args, "payloadJson", "{}"));
      ToolResult folded = ToolSupport.fold(core.submit(command), id, id);
      return folded.success()
          ? folded
          : ToolResult.error(folded.code(), "{\"reason\":\"载荷非法\"}");
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
