package io.mosire.simos.unit.tools.write;

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
 * 单位窄工具的共同落点（spec §八.3，N9）：**命令类型固定**、参数只有载荷与坐标，最终仍走命令链（铁律 2）。
 *
 * <p>★ <b>2026-10-12 B3 第五域下放</b>：本类是 app 侧 {@code AbstractNarrowWriteTool} 的**单位分册**——同一条骨架
 * （{@code spec=sensitive} + {@code ToolGate.Ask} + 资源断言 + 信封组装 + 结局折叠），把对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（谁在调、命令 id 怎么生成由 app 的适配器负责，见端口类注）。
 *
 * <p>★★ <b>与 app 侧基类的三处差别（都是"没搬"而不是"改语义"）</b>（与 B3 试点 map / 第二域 army / 第三四域 economy-social
 * 的分册**逐字同款**）：
 *
 * <ol>
 *   <li><b>没有 {@code signatureViolation} 钩子</b>：那是 sd 的"署名不许冒名"（决策人窄写专用），unit 的窄写一条都
 *       不用它；搬一个恒空的钩子过来只会造出"看着能覆写、其实没人覆写"的假接缝；
 *   <li><b>没有 {@code afterSubmit} 钩子</b>：同上（只有 {@code sd.RunDecision} 那类"落盘后还要做事"的工具要它）；
 *   <li><b>不记 {@code TOOL_SUBMIT_RESULT} 日志行</b>：unit 模块的来源表口径是"命令入口的工具/人工交互由 app 层自记 {@code
 *       interaction}（origin 不跨模块传）"（{@code UnitLogSource} 类注），而工具面的发起/结果已由 app 的 {@code
 *       RecordingToolSource} 统一记录 ⇒ 在这里再记一条会是同一事实的第二处。这条差别**只影响一行 DEBUG 日志**， 不改任何状态、载荷、拒绝码（台账里有记）。
 * </ol>
 *
 * <p>★ 敏感的固定命令类型仍走审批门链（{@link ToolGate.Ask}）：GM 面无脑过、决策人面仍要确认——审批链按**装配**决定， 与工具实现住在哪个模块无关。
 */
abstract class AbstractNarrowWriteTool implements AgentTool {

  private final ToolWritePort writes;
  private final String mapId;

  /**
   * 构造器只做 `Objects.requireNonNull`（装配期 fail-closed）；`CT_CONSTRUCTOR_THROW` 是 SpotBugs 对 `abstract`
   * 类的 **启发式误报**（本类包内可见、不可实例化、外部无法子类化 ⇒ finalizer 攻击面不存在）。
   *
   * <p>★ 处置与仓内既有形制同款：`simos-app/.../write/AbstractHouseholdGmTool:87` 用的是同一条 `@SuppressFBWarnings`。
   */
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

  /**
   * 工具名（缺省 = {@link #commandType()}）。
   *
   * <p>★ D1（2026-10-02）新增这个钩子：{@code simos.*} 风格的工具名与命令类型不同名，而本基类的信封组装 / 资源断言 /
   * 结局折叠三段仍照用。只改名字，其余一字不动；unit 的 28 条窄写里 3 条覆写它（{@code UnitAdjustCompositionTool} / {@code
   * UnitSetCompositionTool} / {@code UnitSetStateDescriptionTool}），其余不覆写 ⇒ 行为逐字不变。
   */
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

  /**
   * ★★ **本工具要写哪些资源**（T10 的可覆写钩子）：缺省 = 三命名空间粗断言（{@link ToolResources#allWriteResources}， GM
   * 侧行为**一字不变**）。unit 的 28 条窄写都不覆写。
   */
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
      // ★★ **不许把资源拒因折成 TOOL_ERROR**（T10，T5-T8 实测发现 2）：`ToolCallAuthorizer` 的边界上有一条
      //   `catch (ResourceDeniedException) → RESOURCE_DENIED`，它**只**在异常真的逃出去时才有机会跑。
      //   折成 TOOL_ERROR 会让模型看到"命令提交失败"（像是参数问题），而正确结论是"换个资源"——
      //   AgentLib 特意分的两个码的意图就此丢失。故这里**原样重抛**，让唯一入口去折叠。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 落一条本工具固定类型的命令（{@code type}/{@code payloadJson} 的**唯一拼装点**）：{@code branch}/{@code
   * expectedRevision} 取自载荷（乐观并发，C26 的信封）；命令 id / 发起者由写口适配器按装配面给（见 {@link ToolWritePort} 的适配规则）。
   *
   * @throws IllegalArgumentException 载荷缺 {@code branch}/{@code expectedRevision}（坏输入，由 {@link
   *     #execute} 折成 {@code BAD_REQUEST}）
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
   * @param commandId 本次提交的命令 id（装配面生成、写口回报；工具结果里的 {@code commandId}/{@code correlationId} 用它）
   * @param outcome 三结局（committed / conflict / rejected）
   */
  protected record SubmittedCommand(String commandId, ToolWritePort.Outcome outcome) {}
}
