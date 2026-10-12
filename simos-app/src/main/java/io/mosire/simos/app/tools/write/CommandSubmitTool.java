package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.spi.IssueDirectiveHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.sd.spi.DirectiveIntentInfo;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
public final class CommandSubmitTool implements SimosTool {

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
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "提交领域命令 type="
            + args.get("type")
            + " branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String id = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAllWrite(context, mapId);
      Map<String, Object> args = context.arguments();
      BranchId branch = new BranchId(ToolSupport.requiredText(args, "branch"));
      RevisionId expectedRevision =
          new RevisionId(ToolSupport.requiredLong(args, "expectedRevision"));
      String type = ToolSupport.requiredText(args, "type");
      String payloadJson = ToolSupport.optionalText(args, "payloadJson", "{}");
      CommandEnvelope command =
          new CommandEnvelope(id, id, initiator, branch, expectedRevision, type, payloadJson);
      // ★★ 2026-10-12 批次 3b-1（控制方裁定 §16.3 的第三条写路径）：{@code ga.IssueDirective} 的**执行原文**
      //   条目与令**同批**落（同一条 revision）。它必须是一条独立命令——一条命令 = 一个命名空间
      //   （{@code CommandBus.namespaceOf} 取类型前缀），而令住 ga、INFO 覆盖层仍住 sd。载荷由
      //   {@link DirectiveIntentInfo} 从同一份出令载荷派生（地址/键/来源引用的唯一拼写点在那里）；
      //   派生不出 ⇒ **退回单命令提交**，让出令命令自己给具名拒因（不在这里改判据）。
      Optional<String> companion =
          IssueDirectiveHandler.TYPE.equals(type)
              ? DirectiveIntentInfo.putInfoPayloadJson(payloadJson)
              : Optional.empty();
      if (companion.isEmpty()) {
        return ToolSupport.fold(core.submit(command), id, id);
      }
      List<CommandEnvelope> batch =
          List.of(
              command,
              new CommandEnvelope(
                  UUID.randomUUID().toString(),
                  id,
                  initiator,
                  branch,
                  expectedRevision,
                  PutInfoHandler.TYPE,
                  companion.get()));
      return ToolSupport.fold(primaryResult(core.submitBatch(batch)), id, id);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 AbstractNarrowWriteTool 同一条（T10）：资源拒因必须原样逃到 ToolCallAuthorizer 的边界，
      //   折成 TOOL_ERROR 会让模型看到"命令提交失败"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 批结局 ⇒ <b>批内第 0 条（= 本工具提交的主命令）</b>的单一结局：与单命令提交的折叠逐字同形。
   *
   * <p>★ 批内结局**与入参顺序逐位对应**（{@code BatchResult} 的不变式）⇒ 第 0 条就是本条 {@code type} 自己的结局；
   * 整批被拒时它带的是自己的真拒因（若被拒的是配套命令，则是"随整批复原"——而世界**一条 revision 都没落**，原子性不变）。
   */
  private static CommandResult primaryResult(BatchResult batch) {
    List<CommandOutcome> outcomes = batch.outcomes();
    return outcomes.isEmpty()
        ? new CommandResult.Rejected("整批未提交（无逐条结局）")
        : outcomes.get(0).result();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
