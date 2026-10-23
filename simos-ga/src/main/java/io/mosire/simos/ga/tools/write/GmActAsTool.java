package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.decision.DecisionToolCaller;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.gm.actAs}（2026-10-14 GM 代打，设计书 §3.7 + §3.7.1）：<b>GM 以某个决策人的环境调一次任意工具</b>。
 *
 * <pre>{@code
 * {"decisionMakerId":"决策人 id（必填，该坐标下必须存在）",
 *  "tool":"真实工具名（必填）",
 *  "args":{…},                 // 可选，缺省 {}
 *  "branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>它要解决什么</b>：从外部把<b>真人玩家的东西</b>输进去（用户原话「出于真实的伪造决策的需求，也应该让 GM 搞」）。GM 指定一个决策人 ⇒
 * 本工具在那个决策人的环境里调一次工具 ⇒ 落下来的东西（决策包里的 call、发出去的信、出令……） <b>署名就是那个决策人</b>，与"这个决策人自己跑的"在世界上逐字同形。
 *
 * <p>★★ <b>执行路径只有一条，且必须是这一条</b>（设计书 §3.7，本类不另找思路）：
 *
 * <pre>{@code
 * state = <GM 给的 branch/expectedRevision 取，缺省 head>
 * dm    = GaToolReads.ga(state).decisionMakers().get(id)        // 查无 ⇒ 具名拒 BAD_REQUEST
 * r     = caller.execute(决策人桶注册表, tool, dm, state, mapId, args, Map.of())
 * }</pre>
 *
 * {@link DecisionToolCaller#execute} 内部用 {@code callerFor(dm, state, mapId)} 建<b>那个决策人的身份 +
 * 现算权限组</b> （范围 / {@code accessLimit} / docs / peer），再走 AgentLib {@code ToolCallAuthorizer}
 * 的<b>五段判定链</b> （工具存在 / 档位硬拒 / 资源声明前置闸 / 审批闸 / 执行）——**一段都不跳**。⇒ 它用的注册表是<b>决策人桶</b>（不是 GM 桶），用的白名单是
 * {@code whitelistFor(那个 DM)}、资源范围是那个 DM 现算的那一份。
 *
 * <p>★★ <b>「它只能干那个决策人能干的」不是一句口号，是三条同时成立</b>：① {@code registry} 是决策人桶 ⇒ GM-only 工具（含本工具自己）在这一层就是
 * {@code TOOL_NOT_FOUND}；② 权限组是 {@code whitelistFor(dm)}（可被 {@code dm.allowedTools} 再收窄）⇒ 白名单外是
 * {@code PERMISSION_DENIED}；③ 资源判定者是"DM 的权限集 × 内层工具自己声明的 manifest"当场建的 ⇒ 够不着就是 {@code
 * RESOURCE_DENIED}。★ <b>不许为了"让 GM 成功"而放过任何一段</b>：那个 DM 够不着就照实回拒，把内层结局的码原样透出去。
 *
 * <p>★ <b>不手工拼 {@code ToolContext}</b>：资源判定者只在 {@code ToolCallAuthorizer} 的判定链第 ③ 段注入 ⇒ 手工拼一个
 * {@code ToolContext} 再直接 {@code tool.execute(ctx)} 会让每个调 {@code require} 的工具 {@code
 * RESOURCE_DENIED}（那是"没有判定者"的 **fail-closed 缺省**，不是"拒"）。本类因此只拿 {@link DecisionToolCaller}， 拿不到
 * "裸执行"这条路。
 *
 * <h2>★ 三条落地裁定（§3.7.1，最高权威）</h2>
 *
 * <p><b>裁定 1 —— 留痕只记日志；不伪造 agent 上下文</b>（用户原话「log 肯定是要的，agent 上下文没必要伪造」）：
 *
 * <ul>
 *   <li>每次调用<b>必写 INFO</b>（{@link GaLog#tool()} / {@link GaLogSource#GA_TOOL_CALL}，<b>不新造 logger
 *       名</b>）： {@code decisionMakerId} + {@code tool} + 结局码（成功 {@code OK}，失败 = 内层码）；
 *   <li>★★ <b>决策包与日志里都不加"这次是 GM 代打"的标记</b>：包里的 {@code proposerId} 就是那个 DM（它从 {@code
 *       context.identity()} 派生，而身份由 {@code callerFor} 按该 DM 建），<b>不多一个字段</b> —— 用户要的是"真实的伪造"；
 *   <li>★★ <b>本工具不写任何会话、不跑 LLM</b>：不往 {@code conversations} 追加任何消息（那是决策人桶之外的另一条路）， 那个 DM 的 agent
 *       上下文<b>保持原样</b>。这正是"没必要伪造"的落点——代打是"替它落一次工具调用"，不是"替它想一轮"；
 *   <li>★ <b>{@code GmToolUsage} 的核查结论</b>：GM 口的工具留痕由<b>桥层统一记</b>，工具自己<b>不必</b>写 —— {@code
 *       Shell.start} 用 {@code RecordingToolSource.record(GaToolSource.of(gmToolFace), gmToolUsage)}
 *       把**整个 合并后的 GM 面**（含本工具）包一层（{@code Shell.java:1369-1371}），每个工具的 {@code execute} 由装饰壳记录。 ⇒
 *       调一次本工具 = {@code GmToolUsage} 里一条 {@code simos.gm.actAs}（工具名 + 成功/失败码）。 ★ 而<b>内层</b>那次
 *       调用**不进** {@code GmToolUsage}：它走的是决策人桶的权限链（不是 GM 口），{@code GmToolUsage} 的口径就是"GM MCP
 *       口用过的工具"（其类注原文），把内层也塞进去等于把两个口混成一份读数。⇒ 本类<b>只写日志</b>，不碰 {@code GmToolUsage}。
 * </ul>
 *
 * <p><b>裁定 2 —— 不能触发"跑一轮 LLM"</b>（用户原话「不能」）：对<b>编排族</b>三条工具（{@link RunDecisionMakersTool} / {@link
 * RunDecisionTool} / {@link StartDecisionTool}——只引用它们各自的 {@code NAME} 常量，不抄字面量）**显式具名拒** {@code
 * BAD_REQUEST}，文案写明"代打只落工具调用，不触发决策人跑轮"。★ 它们本来就不在决策人桶里、 会被自然拒成 {@code TOOL_NOT_FOUND} ——
 * 但<b>显式拒</b>比"碰巧不可达"清楚得多，模型也看得懂（"这条路不走" ≠ "这个名字打错了"）。
 *
 * <p><b>裁定 3 —— GM 想放哪放哪</b>（用户原话「GM 想放哪放哪」）：{@code branch} / {@code expectedRevision}
 * <b>原样生效</b>——用 GM 给的那个坐标取状态，**在那个状态上**现算该 DM 的权限组与环境（范围函数吃 {@code state} 入参，见 {@link
 * DecisionToolCaller} 类注"范围每次现算"）。★ 本工具<b>不限制成 head</b>；两个键都不传 ⇒ 缺省 head（既有惯例，照 {@link
 * GaToolReads#stateOfExpectedRevision}）。
 *
 * <h2>★ 诚实标注（必须说清，否则容易被读成一条新权力）</h2>
 *
 * <p>GM 本来就有<b>通用写</b>（{@code simos.command.submit} 可提交任意命令，用户 2026-09-22 裁定 D2 的有意取舍）⇒ 本工具
 * <b>不放大任何权力</b>。它存在的意义是给"以某个决策人的环境落一次调用"这件事一个<b>唯一的、可见的、经判定链的</b>口 —— 否则 GM 只能手拼 {@code ga.*}
 * 命令，而<b>手工拼 = 绕过权限链</b>（{@code DecisionToolCaller} 类注：手工拼 {@code ToolContext} 再直接 {@code
 * tool.execute()} 会让每个调 {@code require} 的工具 fail-closed）。★ 若某个 DM 够不着，本工具<b>不会</b>给它开一条旁路。
 *
 * <h2>★ 边界与形状</h2>
 *
 * <ul>
 *   <li>{@code toolRoles()={GM}} ⇒ <b>不进决策人桶</b>（决策人不得替别人代打；装配期 {@code ToolRoleAudit.requireFaces}
 *       双向钉死"桶 ⇔ 声明"，注册进错桶当场抛）；
 *   <li>{@code gate()=Ask}（敏感写，与其余 GM 写同制）：GM 面那条链挂 {@code GmAutoApproveGate}（无脑过，2026-09-24 用户裁定）⇒
 *       实际不拦，但若将来把它挂到别的审批链上，它仍是一条要人裁决的工具；
 *   <li>{@code resources() = NONE}（<b>有意的，不是漏写</b>）：本工具**自己不碰任何世界命名空间**——它声明不了内层要碰什么 （内层工具各有各的
 *       manifest，且是在**那个 DM 的权限集**下判的）⇒ 声明一个 {@code ga} 写断言既"对只读内层调用是假话"、 又可能踩到"GM
 *       组没表态的命名空间"那个坑（{@code Shell#gmPermissionSet} 逐命名空间表态，声张未表态的命名空间会被执行前闸整调拒）。 ★ 取 {@code NONE}
 *       <b>不等于放过判定</b>：内层那次调用的资源前置闸照跑，用的是内层的 manifest 与 DM 的权限集；
 *   <li>★ <b>内层 {@code toolConfig} 传空表</b>（{@code Map.of()}）：它是<b>宿主编排配置</b>不是权限（见 {@code
 *       DecisionCallerFactory#callerFor} 类注），目前唯一用途是让渲染工具的 {@code format=auto} 按视觉能力选形态；代打不是"跑一轮"，
 *       宿主的视觉能力不在本工具的注入面里 ⇒ {@code auto} 回落该工具的历史行为。**这不改可达面、不放行任何判定**；
 *   <li>★ <b>异常口径照既有契约</b>：参数坏输入折 {@code BAD_REQUEST}；<b>内层工具违约抛出的意外异常原样外泄</b>（不折成一条 "代打失败"）——与
 *       {@code RecordingToolSource} 的"异常是违约，不伪装成一次工具使用"同口径；
 *   <li>★ <b>目标取自决策人桶 ⇒ 本工具与其余 GM-only 工具天然不可达</b>（{@code TOOL_NOT_FOUND}），不需要另立一条"不许代打自己"
 *       的规则；桶里也<b>没有</b>会再跑 LLM 的工具（三条编排工具都是 GM-only），故不存在自环。
 * </ul>
 */
public final class GmActAsTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（它自己没有命令，内层工具各自走各自的路）。 */
  public static final String NAME = "simos.gm.actAs";

  /** 工具面事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  /**
   * ★ 裁定 2：**编排族**（"再跑一轮 LLM"的那三条）——具名拒，绝不由代打触发。
   *
   * <p>★★ 三个名字都<b>引用各自类的 {@code NAME} 常量</b>（不抄字面量）：常量改了这里跟着改，抄的那份不会（那是本仓最贵的 一类失效）。三个类都在本包内，看得见。
   */
  private static final Set<String> ORCHESTRATION_TOOLS =
      Set.of(RunDecisionMakersTool.NAME, RunDecisionTool.NAME, StartDecisionTool.NAME);

  /** **权限链执行面**（宿主注入，见 {@link DecisionToolCaller}：身份、白名单与资源范围都按"那个决策人"现算）。 */
  private final DecisionToolCaller caller;

  /** ★★ <b>决策人桶</b>的注册表（不是 GM 桶）：代打只能干那个 DM 能干的；GM-only 工具在这一层就不可达。 */
  private final ToolRegistry decisionTools;

  private final ToolReadPort reads;

  /** 本世界的 map 称谓（范围函数要它拼资源前缀；由装配面注入，不从载荷取）。 */
  private final String mapId;

  /**
   * @param caller 权限链执行面（生产 = {@code DecisionCallerFactory}，见 {@link
   *     DecisionToolCaller}；**不手工拼上下文**）
   * @param decisionTools <b>决策人桶</b>的注册表（{@code SimosToolSource.Role.DECISION_AGENT} 那一份）
   * @param reads 窄读口（取 GM 指定坐标的状态；缺省 = head）
   * @param mapId 本世界的 map 称谓
   */
  public GmActAsTool(
      DecisionToolCaller caller, ToolRegistry decisionTools, ToolReadPort reads, String mapId) {
    this.caller = Objects.requireNonNull(caller, "caller");
    this.decisionTools = Objects.requireNonNull(decisionTools, "decisionTools");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 以**某个决策人的环境**调一次工具（代打，用于从外部把真人玩家的东西输进去）。"
        + "参数 {decisionMakerId(必填：该坐标下**已存在**的决策人 id；查无 ⇒ 具名拒 BAD_REQUEST), "
        + "tool(必填：真实工具名), args?(对象，缺省 {}), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "★ 内部走**决策人桶**的注册表 + `whitelistFor(那个决策人)` + 那个决策人**现算**的资源范围，经与决策人回合**完全同一条**"
        + "判定链（工具存在 / 档位 / 资源声明前置闸 / 审批 / 执行）⇒ **它只能干那个 DM 能干的**：够不着就照实回"
        + " TOOL_NOT_FOUND / PERMISSION_DENIED / RESOURCE_DENIED，不会被放宽。"
        + "★ **不触发跑轮**：编排族（"
        + RunDecisionMakersTool.NAME
        + " / "
        + RunDecisionTool.NAME
        + " / "
        + StartDecisionTool.NAME
        + "）显式具名拒 BAD_REQUEST；本工具也不写会话、不跑 LLM（那个决策人的 agent 上下文保持原样）。"
        + "★ **坐标可指定**：branch/expectedRevision 原样生效，权限组在**那个状态上**现算（不限制成 head）。"
        + "返回 {decisionMakerId, tool, ok, code?(失败码，内层结局原样透出), result(内层结果原文), branch, stateRevision}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("decisionMakerId", ToolSchema.prop("string", "决策人 id（必填；必须是该坐标下已存在的决策人）"));
    props.put("tool", ToolSchema.prop("string", "要调用的真实工具名（必填；必须在那个决策人的可达面上）"));
    props.put("args", ToolSchema.prop("object", "内层工具的参数对象（可选，缺省 {}）——原样透传"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "坐标版本（缺省 = 该分支 head；给出必须 ≥ 0）——权限组就在这个状态上现算"));
    return ToolSchema.schema(props, List.of("decisionMakerId", "tool"));
  }

  /** 敏感写（走审批门链）、可外发：与其余 GM 写同制（GM 面 {@code GmAutoApproveGate} 无脑过）。 */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  /**
   * ★ {@code NONE}（有意的，不是漏写）：本工具自己不碰任何世界命名空间——内层工具各有各的 manifest，且是在**那个 DM 的权限集** 下判的；在这里声明 {@code
   * ga} 写既对只读内层调用是假话，也可能踩"GM 组未表态的命名空间"那个坑。★ 取 {@code NONE} 不放行任何判定： 内层调用的资源前置闸照跑（理由全文见类注）。
   */
  @Override
  public ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    // ★ 摘要只做字段回显、不解析参数：gate() 在 execute() 的校验之前调用，解析失败不能让审批链先炸（照 GmMessageSendTool）。
    Map<String, Object> args = context.arguments();
    String decisionMakerId = String.valueOf(args.get("decisionMakerId"));
    String tool = String.valueOf(args.get("tool"));
    return new ToolGate.Ask(
        NAME, "GM 代打 decisionMakerId=" + decisionMakerId + " tool=" + tool, AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String decisionMakerId = ToolSchema.requiredText(args, "decisionMakerId");
      String tool = ToolSchema.requiredText(args, "tool");
      // ★ args 的解析复用同包里已有的那一份（ReviseCallTool 也这么用）：缺省 = 空表，"对象参数"的解析不复制第二份。
      Map<String, Object> innerArgs = DecisionCallValidator.optionalMap(args, "args");

      // ★ 裁定 2：编排族显式具名拒（先判它——它与坐标、与决策人是否存在都无关，且省一次取状态）。
      if (ORCHESTRATION_TOOLS.contains(tool)) {
        return reject(
            "BAD_REQUEST",
            "代打只落工具调用，不触发决策人跑轮：" + tool + " 属编排族（会真跑一轮 LLM）——要跑轮请 GM 直接用该工具（它只在 GM 面）。");
      }

      // ★ 裁定 3：GM 给的坐标原样生效（不限制成 head；两个键都不传 ⇒ 缺省 head）。
      Long expectedRevision = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevision != null && expectedRevision < 0L) {
        return reject("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevision);
      }
      BranchId branch = GaToolReads.branch(args);
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);

      // ★ 决策人必须**已存在**：存在性就是决策人表里的一行（同模块内直接读，不为一次 get 造端口）。
      //   拒因只回显调用者填的 id，不列名册（不泄漏名册，与 GM 侧其余工具同一条纪律）。
      DecisionMaker dm =
          GaToolReads.ga(state).decisionMakers().get(DecisionMakerId.parse(decisionMakerId));
      if (dm == null) {
        return reject(
            "BAD_REQUEST", "决策人不存在（decisionMakerId 必须是该坐标下已存在的决策人 id）: " + decisionMakerId);
      }

      // ★★ 唯一入口：决策人桶注册表 + 那个 DM 的身份与现算权限组 + 五段判定链一段不跳（见类注）。
      //   ★ toolConfig 传空表：它是宿主编排配置不是权限（代打不是"跑一轮"，视觉能力不在本工具的注入面里）。
      ToolResult inner = caller.execute(decisionTools, tool, dm, state, mapId, innerArgs, Map.of());

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("decisionMakerId", dm.id().value());
      view.put("tool", tool);
      view.put("ok", inner.success());
      if (!inner.success()) {
        // ★ 内层结局的码必须透出来：GM 要能分辨 RESOURCE_DENIED / PERMISSION_DENIED / TOOL_NOT_FOUND / BAD_REQUEST。
        view.put("code", inner.code());
      }
      view.put("result", inner.message());
      view.put("branch", branch.value());
      view.put("stateRevision", state.meta().ref().revision().value());

      // ★ 裁定 1：留痕 = INFO 日志（决策人 id + 工具 + 结局码），不新造 logger 名；包里/日志里都不加"GM 代打"标记。
      TOOL.info(
          LogEvent.of(
              "GM_ACT_AS",
              GaLogSource.GA_TOOL_CALL,
              "decisionMakerId",
              dm.id().value(),
              "tool",
              tool,
              "code",
              inner.success() ? "OK" : inner.code(),
              "branch",
              branch.value(),
              "revision",
              state.meta().ref().revision().value()));
      return inner.success()
          ? ToolResult.ok(ToolResults.json(view), inner.assetDocIds())
          : ToolResult.error(inner.code(), ToolResults.json(view));
    } catch (IllegalArgumentException e) {
      return reject("BAD_REQUEST", e.getMessage());
    }
  }

  /** 具名拒绝的唯一发射点（业务拒绝 = INFO，用户 2026-10-23 裁定）；拒因原文同时在返回值里给调用方。 */
  private static ToolResult reject(String code, String reason) {
    TOOL.info(
        LogEvent.of(
            "GM_ACT_AS_REJECTED",
            GaLogSource.GA_TOOL_CALL,
            "tool",
            NAME,
            "code",
            code,
            "reason",
            reason));
    return ToolResult.error(code, reason);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**GM 面**（代打是 GM 的活；决策人不得替别人代打）。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
