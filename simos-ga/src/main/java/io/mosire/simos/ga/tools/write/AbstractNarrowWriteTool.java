package io.mosire.simos.ga.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>ga 工具面的窄写共同落点</b>（spec §八.3，N9）：**命令类型固定**、参数只有载荷与坐标，最终仍走命令链（铁律 2）。
 *
 * <p>★ <b>2026-10-12 B3 第七域下放</b>：本类是 app 侧 {@code AbstractNarrowWriteTool} 的**推演框架分册**——同一条骨架
 * （{@code spec=sensitive} + {@code ToolGate.Ask} + 资源断言 + 信封组装 + 结局折叠 + 署名判定 + 落盘后的扩展点），把对 {@code
 * CoreSimos} 的直连换成构造期注入的 {@link ToolWritePort}（谁在调、命令 id 怎么生成由 app 的适配器负责，见端口类注）。
 *
 * <p>★ <b>与 app 侧基类的差别（都是"换了宿主接缝"，不是"改语义"）</b>：
 *
 * <ol>
 *   <li>{@link #decisionWriteResources} 的身份派生与 {@link #signatureViolation} 的署名规则都经 {@link
 *       GaToolIdentity}（{@code DecisionCallerFactory} 在 app，本模块看不见）——**判据本体仍只有一处**；
 *   <li>{@code TOOL_SUBMIT_RESULT} / {@code TOOL_REJECTED} 两行日志改走 {@link GaLog#tool()}（本模块的日志门面；
 *       事件来源 = {@link GaLogSource#GA_TOOL_CALL}）——**只影响 logger 名与开关，不改任何状态/载荷/拒绝码**；
 *   <li>落盘结局类型是 {@link ToolWritePort.Outcome}（core 的 {@code CommandResult} 的镜像），折叠经 {@link
 *       ToolResults#fold} ⇒ 拒绝码与视图字段逐字不变。
 * </ol>
 *
 * <p>★ 敏感的固定命令类型仍走审批门链（{@link ToolGate.Ask}）：GM 面无脑过、决策人面仍要确认——审批链按**装配**决定，与工具实现住在 哪个模块无关。
 */
public abstract class AbstractNarrowWriteTool implements AgentTool {

  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  private final ToolWritePort writes;
  private final GaToolIdentity identity;
  private final String mapId;

  /**
   * 构造器只做 {@code Objects.requireNonNull}（装配期 fail-closed）；{@code CT_CONSTRUCTOR_THROW} 是 SpotBugs 对
   * {@code abstract} 类的**启发式误报**（本类不可实例化、外部无法子类化 ⇒ finalizer 攻击面不存在）。
   *
   * <p>★ 处置与仓内既有形制同款：{@code simos-app/.../write/AbstractHouseholdGmTool:87} 与四个域分册用的是同一条
   * {@code @SuppressFBWarnings}。
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "本类是 abstract 类（不可实例化、外部无法子类化），构造器只做 Objects.requireNonNull ⇒ finalizer 攻击面不存在，属 SpotBugs 对 abstract 的启发式误报")
  protected AbstractNarrowWriteTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  /** 身份口（子类要用它派生"我是谁"——如署名判定与自指资源前缀）；判据本体仍在 app 的唯一实现里。 */
  protected final GaToolIdentity identity() {
    return identity;
  }

  /** 固定命令类型。★ 缺省时它同时是工具名；两者不同名时覆写 {@link #toolName()}。 */
  protected abstract String commandType();

  /**
   * 工具名（缺省 = {@link #commandType()}）。
   *
   * <p>★ D1（2026-10-02）新增这个钩子：{@code simos.*} 风格的工具名与命令类型不同名（用户给定的 {@code
   * simos.sd.set-diplomatic-relation} 等），而本基类的信封组装 / 资源断言 / 结局折叠三段仍照用。 只改名字，其余一字不动。
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
   * **GM 专用 ga 窄写**共用的资源声明（P0 对齐）：这些工具各自钉死一条 {@code sd.*} 命令，命令 handler 只产 {@code SdChangeSet} ⇒
   * 实际只写 ga 命名空间（2026-10-14 批次 F 由 {@code sd} 改名），不能再沿用基类的三命名空间粗断言。
   *
   * <p>★ 写操作取 {@link ResourcePolicy#UNRESTRICTED}（不是 {@code IssueDirective}/{@code SubmitVerdict}
   * 那两条决策窄写的 {@code READ_ONLY}）：它们只在 GM 桶，调用者的 ga 表态是 unlimited；取只读会把"未表态 ga 的系统身份"整调拒掉。
   */
  protected static final ResourceManifest GA_NAMESPACE_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** GM 专用 ga 窄写的写资源断言（{@code ga:*}；GM 侧 ga 是 unlimited）。 */
  protected static List<ResourceId> gaNamespaceWriteResources() {
    return List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*"));
  }

  /**
   * ★★ **本工具要写哪些资源**（T10 的可覆写钩子）：由子类按**调用上下文**决定；缺省 = 三命名空间粗断言 （{@link
   * ToolResources#allWriteResources}，GM 侧行为**一字不变**）。
   *
   * <p>★ **按 context 取、不按载荷取**：资源声明必须从**宿主已知的东西**（身份 / 工具的构造参数）推出，不采信模型自报的载荷字段。
   */
  protected List<ResourceId> writeResources(ToolContext context) {
    return ToolResources.allWriteResources(mapId);
  }

  /**
   * **决策窄写**的资源声明（{@code IssueDirective} / {@code SubmitVerdict} / {@code ga.RunDecision} 共用一处）。
   *
   * <ul>
   *   <li>调用者是**决策人**（身份 {@code decision-maker:<id>}）⇒ 只声明它**自己**的决策域 {@code
   *       sd:decision-maker/<自己>}——出令/交判决是决策行为，不是改地图数据；且决策人只能以**自己**名义决策；
   *   <li>其余身份（**GM**）⇒ 声明**整个决策人集合** {@code sd:decision-maker}（GM 的 sd 是 unlimited ⇒ 放行； 而决策人自己的前缀
   *       **不覆盖**这个粗路径 ⇒ 决策人走不到这一支）。含义是"GM 可以为任意决策人落决策"。
   * </ul>
   *
   * <p>★ id 取自**身份**（{@code context.identity()}）而**不是**载荷里的 {@code decisionMakerId}：载荷是模型写的。
   */
  protected final List<ResourceId> decisionWriteResources(ToolContext context) {
    return identity
        .decisionMakerIdOf(context.identity())
        .map(id -> List.of(ToolResources.resourceGa(identity.decisionMakerKind(), id)))
        .orElseGet(
            () -> List.of(ResourceId.of(ToolResources.GA_NAMESPACE, identity.decisionMakerKind())));
  }

  /**
   * **署名（subject）校验钩子**（T11B）：缺省不校验；载荷里带"决策人署名"的窄写在这里拒"以别人名义落决策"。
   *
   * <p>★ 规则住在 sd 域（{@link DecisionSignature}），这里只负责把"调用者是谁"喂进去——身份只能从 {@link ToolContext} 取（spec
   * §4.3：不由参数自报），而 {@code CommandHandler} 结构性看不见调用者。
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
    // ★ 工具面的「发起/结果」由 ga 工具源的 RecordingToolSource 统一记（含 caller/success/code）；
    //   本类只保留**外层没有的两条事实**：具名署名拒绝的 reason、以及本次固定命令的 commandId。
    // 署名先于资源判：它最便宜、也最具体（"换个资源就行"与"换个署名就行"是两条不同的纠正方向）。
    Optional<String> violation = signatureViolation(context);
    if (violation.isPresent()) {
      TOOL.info(
          LogEvent.of(
              "TOOL_REJECTED", GaLogSource.GA_TOOL_CALL, "tool", name(), "reason", "signature"));
      return ToolResult.error("REJECTED", violation.get());
    }
    try {
      ToolResources.requireAll(context, Operation.WRITE, writeResources(context));
      SubmittedCommand submitted = submit(context);
      ToolResult result = afterSubmit(context, submitted);
      TOOL.debug(
          LogEvent.of(
              "TOOL_SUBMIT_RESULT",
              GaLogSource.GA_TOOL_CALL,
              "tool",
              name(),
              "commandId",
              submitted.commandId(),
              "result",
              submitted.outcome().getClass().getSimpleName()));
      return result;
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★★ **不许把资源拒因折成 TOOL_ERROR**（T10，T5-T8 实测发现 2）：`ToolCallAuthorizer` 的边界上有一条
      //   `catch (ResourceDeniedException) → RESOURCE_DENIED`，它**只**在异常真的逃出去时才有机会跑。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "命令提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 落一条本工具固定类型的命令（{@code type}/{@code payloadJson} 的**唯一拼装点**）：{@code branch}/{@code
   * expectedRevision} 取自载荷（乐观并发，C26 的信封）；命令 id / 发起者由写口适配器按装配面给。
   *
   * @throws IllegalArgumentException 载荷缺 {@code branch}/{@code expectedRevision}（坏输入，由 {@link
   *     #execute} 折成 {@code BAD_REQUEST}）
   */
  protected final SubmittedCommand submit(ToolContext context) {
    Map<String, Object> args = context.arguments();
    BranchId branch = new BranchId(ToolSchema.requiredText(args, "branch"));
    RevisionId expectedRevision = new RevisionId(ToolSchema.requiredLong(args, "expectedRevision"));
    String payloadJson = ToolSchema.optionalText(args, "payloadJson", "{}");
    // ★ **一条命令**（2026-10-12 批次 3b-2）：批次 3b-1 引入的"配套命令"钩子（{@code companionRequests}）已随
    //   {@code info} 搬进 ga 而删除 —— 出令的执行原文现在由 {@code ga.IssueDirective} **自己**在同一条命令里写
    //   （见 IssueDirectiveHandler 类注）。本方法回到加钩子之前逐字同形的单命令提交（含 correlationId = commandId）。
    Command command = () -> expectedRevision;
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(branch, command, commandType(), payloadJson);
    return new SubmittedCommand(receipt.commandId(), receipt.commandId(), receipt.outcome());
  }

  /**
   * **提交之后的扩展点**（T11C）：缺省把结局折成工具结果（绝大多数窄工具就到此为止）。
   *
   * <p>★ **为什么要这个钩子**：`ga.RunDecision` 的动作**发生在触发事实落盘之后**（它触发的那一轮决策要读到**新 head** 的世界）。 基类的
   * `execute` 是 {@code final} ⇒ 子类不可能绕过署名判定/资源断言/错误折叠这三段，只能从这一个明写的口子往下接。
   */
  protected ToolResult afterSubmit(ToolContext context, SubmittedCommand submitted) {
    return ToolResults.fold(submitted.outcome(), submitted.commandId(), submitted.correlationId());
  }

  /**
   * 一次提交的账。
   *
   * @param commandId 本次提交的**主命令** id（装配面生成、写口回报）
   * @param correlationId 调用链 id：单命令提交 = {@code commandId}（与加配套命令钩子之前逐字同口径）；带配套命令的批 = 批级
   *     id（信封与结果视图报的是同一个）
   * @param outcome 三结局（committed / conflict / rejected）
   */
  protected record SubmittedCommand(
      String commandId, String correlationId, ToolWritePort.Outcome outcome) {}
}
