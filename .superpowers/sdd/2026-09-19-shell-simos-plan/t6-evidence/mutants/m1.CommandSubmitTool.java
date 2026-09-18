package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * {@code simos.command.submit}（spec §7.1 写工具）：**通用信封**工具，一条工具覆盖全部已注册命令。
 *
 * <p>★ **全仓唯一让 Agent 写状态的路**：构造 {@link CommandEnvelope}（{@code commandId = correlationId =} 新
 * UUID，{@code initiator} 取注入值）→ {@link CoreSimos#submit} → {@code CommandResult} 折叠成 {@link
 * ToolResult}。
 *
 * <p>★ 敏感写：{@code spec().sensitive()=true} + {@link ToolGate.Ask}（classKey = 工具名，summary 含
 * type/branch/expected）；资源面 map+soc+unit {@code UNRESTRICTED}。
 */
public final class CommandSubmitTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.command.submit";

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CoreSimos 是本工具的唯一写入口（只调 submit），非内部表示外泄；写面仍受权限/审批闸约束")
  public CommandSubmitTool(CoreSimos core, String initiator, String mapId) {
    this.core = core;
    this.initiator = initiator;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "提交一条领域命令（通用信封）：type + payloadJson + branch + expectedRevision";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("type", ToolSupport.prop("string", "命令类型（见 simos.command.catalog）"));
    props.put("payloadJson", ToolSupport.prop("string", "载荷 JSON 文本（见 catalog 的 payloadHints）"));
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSupport.schema(props, List.of("type", "branch", "expectedRevision"));
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
    return ToolGate.ALLOW;
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
              ToolSupport.requiredText(args, "type"),
              ToolSupport.optionalText(args, "payloadJson", "{}"));
      return ToolSupport.fold(core.submit(command), id, id);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
