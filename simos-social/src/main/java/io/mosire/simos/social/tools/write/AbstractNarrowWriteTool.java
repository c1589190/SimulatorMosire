package io.mosire.simos.social.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * social 窄工具的共同落点：**命令类型固定**、参数只有载荷与坐标，最终仍走命令链（铁律 2）。
 *
 * <p>★ <b>2026-10-12 B3 第四域下放</b>：本类是 app 侧 {@code AbstractNarrowWriteTool} 的**社会分册**——同一条骨架
 * （{@code spec=sensitive} + {@code ToolGate.Ask} + 资源断言 + 信封组装 + 结局折叠），把对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（谁在调、命令 id 怎么生成由 app 的适配器负责，见端口类注）。
 *
 * <p>★★ <b>与 app 侧基类的三处差别（都是"没搬"而不是"改语义"）</b>：没有 {@code signatureViolation} 钩子、没有 {@code
 * afterSubmit} 钩子、不记 {@code TOOL_SUBMIT_RESULT} 日志行（工具面的发起/结果已由 app 的 {@code RecordingToolSource}
 * 统一记录）——前两条是 sd 决策人窄写专用，第三条**只影响一行 DEBUG 日志**，不改任何状态、载荷、拒绝码。
 */
abstract class AbstractNarrowWriteTool implements AgentTool {

  private final ToolWritePort writes;
  private final String mapId;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "本类是包内可见的 abstract 类（不可实例化、外部无法子类化），构造器只做 Objects.requireNonNull ⇒ finalizer 攻击面不存在，属 SpotBugs 对 abstract 的启发式误报")
  AbstractNarrowWriteTool(ToolWritePort writes, String mapId) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  /** 固定命令类型。★ 缺省时它同时是工具名；两者不同名时覆写 {@link #toolName()}。 */
  protected abstract String commandType();

  /** 工具名（缺省 = {@link #commandType()}）。 */
  protected String toolName() {
    return commandType();
  }

  /** 审批摘要里的一行人话。 */
  protected abstract String summary(Map<String, Object> args);

  @Override
  public final String name() {
    return toolName();
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("payloadJson", ToolSchema.prop("string", "命令载荷 JSON 文本"));
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSchema.schema(props, List.of("branch", "expectedRevision"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_WRITE;
  }

  /** ★★ **本工具要写哪些资源**：缺省 = 三命名空间粗断言（{@link ToolResources#allWriteResources}）。 */
  protected List<ResourceId> writeResources(ToolContext context) {
    return ToolResources.allWriteResources(mapId);
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  @Override
  public final ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, writeResources(context));
      SubmittedCommand submitted = submit(context);
      return ToolResults.fold(submitted.outcome(), submitted.commandId(), submitted.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★★ **不许把资源拒因折成 TOOL_ERROR**：`ToolCallAuthorizer` 的边界上有一条
      //   `catch (ResourceDeniedException) → RESOURCE_DENIED`，它**只**在异常真的逃出去时才有机会跑。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 落一条本工具固定类型的命令（{@code type}/{@code payloadJson} 的**唯一拼装点**）：{@code branch}/{@code
   * expectedRevision} 取自载荷；命令 id / 发起者由写口适配器按装配面给。
   */
  protected final SubmittedCommand submit(ToolContext context) {
    Map<String, Object> args = context.arguments();
    BranchId branch = new BranchId(ToolSchema.requiredText(args, "branch"));
    RevisionId expectedRevision = new RevisionId(ToolSchema.requiredLong(args, "expectedRevision"));
    Command command = () -> expectedRevision;
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(
            branch, command, commandType(), ToolSchema.optionalText(args, "payloadJson", "{}"));
    return new SubmittedCommand(receipt.commandId(), receipt.outcome());
  }

  /**
   * 一次提交的账。
   *
   * @param commandId 命令 id（装配面生成、写口回报；工具结果里的 {@code commandId}/{@code correlationId} 用它）
   * @param outcome 三结局（committed / conflict / rejected）
   */
  protected record SubmittedCommand(String commandId, ToolWritePort.Outcome outcome) {}
}
