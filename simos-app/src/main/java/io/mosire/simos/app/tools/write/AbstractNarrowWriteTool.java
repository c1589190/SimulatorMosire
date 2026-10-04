package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.spi.DecisionSignature;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * 专用窄工具的共同落点（spec §八.3，N9）：**命令类型固定**、参数只有载荷与坐标，最终仍走 {@code core.submit}（铁律 2）。
 *
 * <p>★ **不给通用写**：决策 Agent / GM 的工具面里没有 {@code simos.command.submit}——它们只能用这些 type
 * 固定的窄工具；因此"选什么命令"不再是模型可自由发挥的面。
 *
 * <p>★ 敏感写：{@code spec().sensitive()=true} + {@link ToolGate.Ask}（走审批留痕）。
 */
abstract class AbstractNarrowWriteTool implements AgentTool {

  private static final Logger TOOL = AppLog.tool();

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  AbstractNarrowWriteTool(CoreSimos core, String initiator, String mapId) {
    this.core = core;
    this.initiator = initiator;
    this.mapId = mapId;
  }

  /** 固定命令类型。★ 缺省时它同时是工具名；两者不同名时覆写 {@link #toolName()}。 */
  protected abstract String commandType();

  /**
   * 工具名（缺省 = {@link #commandType()}）。
   *
   * <p>★ D1（2026-10-02）新增这个钩子：{@code simos.*} 风格的工具名与命令类型不同名（用户给定的 {@code
   * simos.unit.set-state-description} / {@code simos.army.recordCombat}），而本基类的信封组装 / 资源断言 /
   * 结局折叠三段仍照用。 只改名字，其余一字不动；既有子类不覆写 ⇒ 行为逐字不变。
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
   * **GM 专用 sd 窄写**共用的资源声明（P0 对齐）：这些工具各自钉死一条 {@code sd.*} 命令，命令 handler 只产 {@code SdChangeSet} ⇒
   * 实际只写 sd 命名空间，不能再沿用基类的三命名空间粗断言。
   *
   * <p>★ 写操作取 {@link ResourcePolicy#UNRESTRICTED}（不是 {@code IssueDirective}/{@code SubmitVerdict}
   * 那两条决策窄写的 {@code READ_ONLY}）：它们只在 GM 桶，调用者的 sd 表态是 unlimited；取只读会把"未表态 sd 的系统身份"整调拒掉（与 {@code
   * WorldgenInitializeTool} 的取舍同一条）。
   */
  protected static final ResourceManifest SD_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /**
   * 同 {@link #SD_NAMESPACE_WRITE}，用于 {@code actor.AdjustAccounts}（handler 只产 {@code
   * ActorChangeSet}）。
   */
  protected static final ResourceManifest ACTOR_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /**
   * 同 {@link #SD_NAMESPACE_WRITE}，用于 {@code social.UpdateCity}（handler 只产 {@code SocialChangeSet}）。
   */
  protected static final ResourceManifest SOCIAL_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /**
   * 同 {@link #SD_NAMESPACE_WRITE}，用于 {@code army.RecordCombat}（阶段 D1：handler 只产 {@code
   * ArmyChangeSet}）。
   */
  protected static final ResourceManifest ARMY_NAMESPACE_WRITE =
      ResourceManifest.of(ToolSupport.ARMY_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** GM 专用 sd 窄写的写资源断言（{@code sd:*}；GM 侧 sd 是 unlimited）。 */
  protected static List<ResourceId> sdNamespaceWriteResources() {
    return List.of(ResourceId.of(ToolSupport.SD_NAMESPACE, "*"));
  }

  /** GM 专用 actor 窄写的写资源断言（{@code actor:*}；GM 侧 actor 是 unlimited）。 */
  protected static List<ResourceId> actorNamespaceWriteResources() {
    return List.of(ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"));
  }

  /** GM 专用 social 窄写的写资源断言（{@code social:*}；GM 侧 social 是 unlimited）。 */
  protected static List<ResourceId> socialNamespaceWriteResources() {
    return List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));
  }

  /** GM 专用 army 窄写的写资源断言（{@code army:*}；GM 侧 army 是 unlimited，见 {@code Shell#gmPermissionSet}）。 */
  protected static List<ResourceId> armyNamespaceWriteResources() {
    return List.of(ResourceId.of(ToolSupport.ARMY_NAMESPACE, "*"));
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

  /**
   * **署名（subject）校验钩子**（T11B）：缺省不校验；载荷里带"决策人署名"的窄写在这里拒"以别人名义落决策"。
   *
   * <p>★ 为什么需要它：资源断言（{@link #writeResources}）解决的是"**够不够得着**"，管不到"**以谁的名义**"——决策人 A 的资源
   * 前缀是它自己那一条（{@code sd:decision-maker/A}），于是"署名 B"的载荷**照样通过资源判定**，落盘后就是一条 冒名的 directive（T10 报告 §六
   * 第 4 条实测的洞；spec **N16**「渠道不得冒称任意 actor」的同一族）。
   *
   * <p>★ **规则住在 sd 域**（{@link DecisionSignature}），这里只负责把"调用者是谁"喂进去——身份只能从 {@link ToolContext}
   * 取（spec §4.3：不由参数自报），而 {@code CommandHandler} 结构性看不见调用者（见 {@code DecisionSignature} 的类注）。
   *
   * @return 拒绝理由；不校验 / 不构成冒名 ⇒ 空
   */
  protected Optional<String> signatureViolation(ToolContext context) {
    return Optional.empty();
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  @Override
  public final ToolResult execute(ToolContext context) {
    TOOL.debug("event=TOOL_CALL tool={} commandType={}", name(), commandType());
    // ★ 署名先于资源判：它最便宜、也最具体（"换个资源就行"与"换个署名就行"是两条不同的纠正方向）。
    Optional<String> violation = signatureViolation(context);
    if (violation.isPresent()) {
      TOOL.debug("event=TOOL_RESULT tool={} result=Rejected reason=signature", name());
      return ToolResult.error("REJECTED", violation.get());
    }
    try {
      ToolSupport.requireAll(context, Operation.WRITE, writeResources(context));
      SubmittedCommand submitted = submit(context);
      ToolResult result = afterSubmit(context, submitted);
      TOOL.debug(
          "event=TOOL_RESULT tool={} commandId={} result={}",
          name(),
          submitted.commandId(),
          submitted.result().getClass().getSimpleName());
      return result;
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
   * 落一条本工具固定类型的命令（信封与命令 id 的**唯一生成点**）：命令 id == correlationId，`branch`/`expectedRevision`
   * 取自载荷（乐观并发，C26 的信封）。
   *
   * @throws IllegalArgumentException 载荷缺 `branch`/`expectedRevision`（坏输入，由 {@link #execute} 折成
   *     {@code BAD_REQUEST}）
   */
  protected final SubmittedCommand submit(ToolContext context) {
    String id = UUID.randomUUID().toString();
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
    return new SubmittedCommand(id, core.submit(command));
  }

  /**
   * **提交之后的扩展点**（T11C 新增）：缺省把结局折成工具结果（绝大多数窄工具就到此为止）。
   *
   * <p>★ **为什么要这个钩子**：`sd.RunDecision` 的动作**发生在触发事实落盘之后**（它触发的那一轮决策要读到**新 head** 的世界）。 基类的
   * `execute` 是 {@code final} ⇒ 子类不可能绕过署名判定/资源断言/错误折叠这三段（那是**收紧**：以前一个子类覆写 `execute`
   * 就能把三段全丢掉），只能从这一个明写的口子往下接。
   *
   * <p>★ **本钩子里抛出的异常会落到 {@link #execute} 的 `RuntimeException` 分支**（消息写成"命令提交失败"——对"落盘后做事" 的工具并不贴切）⇒
   * 需要**自己的失败语义**的子类应当在本方法内自行 catch 并折成 {@code ToolResult.error}。
   *
   * @param submitted 本次提交的账（命令 id + 三结局之一）
   */
  protected ToolResult afterSubmit(ToolContext context, SubmittedCommand submitted) {
    return ToolSupport.fold(submitted.result(), submitted.commandId(), submitted.commandId());
  }

  /**
   * 一次提交的账。
   *
   * @param commandId 命令 id（= correlationId，信封的唯一生成点给的）
   * @param result 三结局（committed / conflict / rejected）
   */
  protected record SubmittedCommand(String commandId, CommandResult result) {}
}
