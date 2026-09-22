package io.mosire.simos.app.tools.write;

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
import io.mosire.simos.app.access.DecisionCallerFactory;
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

  /**
   * ★★ **本工具要写哪些资源**（T10 的可覆写钩子）：由子类按**调用上下文**决定；缺省 = 三命名空间粗断言 （{@link
   * ToolSupport#allWriteResources}，GM 侧行为**一字不变**）。
   *
   * <p>**为什么需要它**：基类的粗断言（{@code map:<mapId>} + {@code unit:"*"} + {@code social:"*"}）在**受限调用者**
   * 身上一律判否（{@code ResourceScope} 是段边界前缀匹配）⇒ **粗断言撞细围栏 = 整调被拒**（spec §5.2 第 2 条）。 两条**决策窄写**（{@code
   * sd.IssueDirective}/{@code sd.SubmitVerdict}）因此连出令都出不了——它们写的是 **sd
   * 域的自己那一小块**，不是地图/单位数据，故各自覆写本方法（见那两个类）。
   *
   * <p>★ **按 context 取、不按载荷取**：资源声明必须从**宿主已知的东西**（身份 / 工具的构造参数）推出，不采信模型 自报的载荷字段（spec §4.3
   * 的同一口径：身份不由参数自报）。
   */
  protected List<ResourceId> writeResources(ToolContext context) {
    return ToolSupport.allWriteResources(mapId);
  }

  /**
   * **决策窄写**的资源声明（{@link IssueDirectiveTool} / {@link SubmitVerdictTool} 共用一处；两者的 {@code
   * resources()} 也相应只声明 {@code sd}）：
   *
   * <ul>
   *   <li>调用者是**决策人**（身份 {@code decision-maker:<id>}）⇒ 只声明它**自己**的决策域 {@code
   *       sd:decision-maker/<自己>}——出令/交判决是决策行为，不是改地图数据；且决策人只能以**自己**名义决策 （给 {@code unlimited}
   *       会让"以别人名义落一条 directive"变成权限上允许的事）；
   *   <li>其余身份（**GM**）⇒ 声明**整个决策人集合** {@code sd:decision-maker}（GM 的 sd 是 unlimited ⇒ 放行；
   *       而决策人自己的前缀**不覆盖**这个粗路径 ⇒ 决策人走不到这一支）。含义是"GM 可以为任意决策人落决策"。
   * </ul>
   *
   * <p>★ id 取自**身份**（{@code context.identity()}）而**不是**载荷里的 {@code decisionMakerId}：载荷是模型写的 （spec
   * §4.3：身份不由参数自报）。两处路径拼写同源（{@code DecisionCallerFactory.decisionDomainOf}）。
   */
  protected final List<ResourceId> decisionWriteResources(ToolContext context) {
    return DecisionCallerFactory.decisionMakerIdOf(context.identity())
        .map(id -> List.of(ToolSupport.resourceSd(DecisionCallerFactory.DECISION_MAKER_KIND, id)))
        .orElseGet(() -> List.of(ResourceId.of(ToolSupport.SD_NAMESPACE, DECISION_MAKER_ROOT)));
  }

  /** 决策人集合的根路径（{@code sd:decision-maker}）——GM 那一支的资源，见 {@link #decisionWriteResources}。 */
  private static final String DECISION_MAKER_ROOT = DecisionCallerFactory.DECISION_MAKER_KIND;

  @Override
  public ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String id = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAll(context, Operation.WRITE, writeResources(context));
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
      return ToolSupport.fold(core.submit(command), id, id);
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
}
