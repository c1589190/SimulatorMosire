package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.ga.message.send}（DM 面，2026-10-14 决策人互致消息，设计书 §4.2）：<b>被 GM 授权的两个决策人之间</b> 发一条消息。
 *
 * <pre>{@code
 * {"to":"dm-ger","subject":"可选","body":"必填非空白","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>闸门 = 事先授权，不是逐条审批</b>（设计书 §4.2 / I7）：GM 的管控点是 {@code ga.SetDecisionMakerPeer} 写下的 {@code
 * accessLimit.peer} 授权表 ⇒ 本工具 {@code gate() = ALLOW}。★ 因此它**故意不 extends {@code
 * AbstractNarrowWriteTool}**（那个基类硬编码 {@code Ask(SENSITIVE)}）：让它进"窄写家族"会让"窄写 ⇒ 逐条审批"
 * 这条不变量出现例外，既有的两条审计（写闸覆盖集 / 窄写 ↔ GM 桶）随即失去判别力。
 *
 * <p>★ <b>未授权 = 具名拒</b>：{@code execute} 里按**参数**（收件人）断言 {@code peer:<to>} 与自指 {@code
 * peer:<自己>}（{@link ToolResources#resourcePeer}）——断言在**提交之前**，被拒 ⇒ 世界零变化（零 revision）。 可达面文案由 {@code
 * ResourceAuthorizer} 给（"够不着"），不另造一套。
 *
 * <p>★★ <b>收件人 == 自己 ⇒ 具名拒</b>（{@code BAD_REQUEST}，在**任何写入之前** ⇒ 零 revision）。★ 这道守卫不是锦上添花： 设计书 §4.1
 * 给**每个**决策人的基础可达面补了自指域 {@code peer:<自己>}（{@code inbox} 与"以自己名义"的断言要用它）⇒ 一旦 {@code to ==
 * 自己}，上面那两条资源断言**全都过** ⇒ 等于给每个决策人开了一条**免 GM 授权**的写通道（"只有 GM 授权才能用"当场失效）； 它还会**每 tick 烧掉一条
 * revision**（自发自收的 {@code msg-<自己>-<自己>-<tick>}），并**破设计书 §10.4 的行为契约**—— "发件人在自己的 {@code inbox}
 * 里看不到自己发出去的信"（自发自收的信 {@code to == 自己}，恰好落进自己 {@code inbox} 的那道筛子）。
 *
 * <p>★ <b>为什么这个码是 {@code BAD_REQUEST} 而不是 {@code RESOURCE_DENIED}</b>：收件人 = 自己**不是**"够不着某块资源"
 * ——{@code peer:<自己>} 是**人人皆有**的基面，配置面拿不掉它，GM 也无从"再授权一次"来放行；它是**用法错**（{@code to}
 * 该填**另一个**决策人）。两者共用一个码，"改参数"与"改配置"这两种修法就分不开了（既有运行流正是靠这个区分给模型回灌）。 ★
 * 因此本守卫放在资源断言**之前**：拒因**不随调用者的可达面变化**（零授权的决策人与被 GM 授权者得到同一句拒）， 且它在任何 reads / submit 之前 ⇒ 被拒时世界零变化。
 *
 * <p>★ <b>不泄漏名册</b>（I4）：本工具从不列出候选收件人——{@code to} 只能填**被授权的那一个 id**（超出可达面即拒， 而错误文案只回显调用者自己填的那个 id）。
 *
 * <p>★ <b>每 tick 每方向一条</b>（I1）：条目 id = {@code msg-<from>-<to>-<tick>}，而 {@code PutInfoHandler} 对显式
 * id **命令期重复即拒** ⇒ 同 tick 第二条**当场具名拒**，不需要自建限流器。
 *
 * <p>★ <b>发件人不可伪造</b>（I6）：{@code from} 从 {@link ToolContext#identity()} 派生（身份里的 {@code
 * decision-maker:} 前缀），**不采信载荷里的任何 id**。载荷里根本没有 {@code from} 键。
 *
 * <p>★ <b>落点是感知层</b>（I8：不新增 {@code GaState} 组件）：一条 {@code ga.PutInfo}，地址 {@code
 * ga:message.<from>.<to>.<tick>}（I9：不再产生 {@code sd:} 地址），{@code tags={from,to}}（I5：可见性 = 收发双方 +
 * GM，零新机制）。
 */
public final class GaMessageSendTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（它提交的是既有的 ga.PutInfo）。 */
  public static final String NAME = "simos.ga.message.send";

  /** 正文（{@code body}）的长度上限：防一条消息把整轮上下文吃掉（超限**具名拒**，不静默截断）。 */
  public static final int MAX_BODY_CHARS = 8_000;

  /** 主题（{@code subject}）的长度上限（同上口径）。 */
  public static final int MAX_SUBJECT_CHARS = 200;

  /** 条目 {@code key}：inbox 只取这个 key（与旧的 {@code "report"} 分开，旧档不解释——I10）。 */
  public static final String KEY_MESSAGE = "message";

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public GaMessageSendTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "给**被 GM 授权与你互通**的那一个决策人发一条消息（每 tick 每方向一条；★ 不能发给自己——to 必须是**另一个**决策人）。载荷 {to, subject?, body}："
        + "to = 对方的决策人 id（★ 只填 GM 授权给你的那一个——系统**不提供**决策人名册；且不得填自己）；body 必填非空白（≤ "
        + MAX_BODY_CHARS
        + " 字符）。未授权（或同 tick 已发过一条）⇒ 命令期具名拒、世界零变化。"
        + "对方用 simos.ga.message.inbox 收。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("to", ToolSchema.prop("string", "收件人的决策人 id（★ 只能是 GM 授权给你的那一个，且**不能是自己**）"));
    props.put("subject", ToolSchema.prop("string", "主题（可选，≤ " + MAX_SUBJECT_CHARS + " 字符）"));
    props.put("body", ToolSchema.prop("string", "正文（必填非空白，≤ " + MAX_BODY_CHARS + " 字符）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("to", "body"));
  }

  /**
   * ★ 写感知层 ⇒ {@code destructive=true}（它确实落盘一条 revision）、**{@code sensitive=false}**：本工具不走审批
   * （闸门是事先授权），标 sensitive 会让"要不要停下来问人"出现第二处真相。
   */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, false, true);
  }

  /** ★ 声明 {@code peer} 域（{@link ToolResources#PEER_WRITE}）：策略 DENY ⇒ **没表态就不许**（fail-closed）。 */
  @Override
  public ResourceManifest resources() {
    return ToolResources.PEER_WRITE;
  }

  /**
   * ★★ <b>审批 = 无</b>（I7）：闸门是 GM 的**事先授权**（{@code accessLimit.peer}），不是逐条点头。 ★ 诚实标注（设计书 §4.3 的代价）：GM
   * 因此**看不到**每一条消息的正文——他只是没被逐条打扰，**不是看不见**： 消息住 {@code info} 覆盖层，GM 读全部（GM 面的读口与 GUI 照旧可见）。
   */
  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      // ★ I6：发件人只从身份派生（载荷里没有 from 键，也不采信任何自报 id）。
      Optional<String> senderRaw = identity.decisionMakerIdOf(context.identity());
      if (senderRaw.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId sender = DecisionMakerId.parse(senderRaw.get());
      DecisionMakerId to = DecisionMakerId.parse(ToolSchema.requiredText(args, "to"));
      String body = ToolSchema.requiredText(args, "body");
      if (body.isBlank()) {
        return ToolResult.error("BAD_REQUEST", "body 不得为空白");
      }
      if (body.length() > MAX_BODY_CHARS) {
        return ToolResult.error(
            "BAD_REQUEST", "body 上限为 " + MAX_BODY_CHARS + " 字符: " + body.length());
      }
      String subject = ToolSchema.optionalText(args, "subject", null);
      if (subject != null && subject.length() > MAX_SUBJECT_CHARS) {
        return ToolResult.error(
            "BAD_REQUEST", "subject 上限为 " + MAX_SUBJECT_CHARS + " 字符: " + subject.length());
      }

      // ★★ 收件人 == 调用者 ⇒ 具名拒（在任何写入之前；理由全文见类注）：设计书 §4.1 的自指域 peer:<自己> 是**人人皆有**的
      //   基础可达面 ⇒ 放过去就等于开了一条**免 GM 授权**的写通道（上面那两条资源断言这时全都过），而且每 tick 烧一条
      //   revision（msg-<自己>-<自己>-<tick>）、还破 §10.4（自发自收的信 to == 自己 ⇒ 出现在自己的 inbox 里）。
      //   ★ 码取 BAD_REQUEST（**用法错**：to 该填另一个决策人）——★ 不是 RESOURCE_DENIED：那会把"改参数"与"改配置"
      //     混成同一种坏法，而 peer:<自己> 是拿不掉的基面、GM 也无从"再授权一次"放行。
      //   ★ 它在资源断言**之前** ⇒ 拒因与调用者的可达面无关（零授权者与被授权者得到同一句拒），也不读任何状态。
      if (sender.value().equals(to.value())) {
        return ToolResult.error(
            "BAD_REQUEST",
            "不能给自己发消息：本工具用于**两个被 GM 授权的决策人之间**通信，to 必须填**另一个**决策人 id（收件人 = 调用者自己）: " + to.value());
      }

      // ★ 闸门（按参数取资源）：[peer:<收件人>, peer:<自己>] —— 顺序有意：先判收件人（未授权的拒因最具体）。
      //   ★ 必须在 submit 之前 ⇒ 被拒时世界零变化（P3 的"无 revision"）。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(
              ToolResources.resourcePeer(to.value()), ToolResources.resourcePeer(sender.value())));

      BranchId branch = GaToolReads.branch(args);
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      long worldTick = state.meta().timestamp().tick();

      // ★ I1：id 把 (from,to,tick) 三元组钉死 ⇒ 同 tick 同方向第二条被 PutInfoHandler 命令期具名拒。
      String infoId = "msg-" + sender.value() + "-" + to.value() + "-" + worldTick;
      Address address =
          new Address(
              List.of(
                  new Namespace("ga"),
                  Entity.of(KEY_MESSAGE, sender.value() + "." + to.value() + "." + worldTick)));

      Map<String, Object> message = new LinkedHashMap<>();
      message.put("from", sender.value());
      message.put("to", to.value());
      message.put("tick", worldTick);
      if (subject != null && !subject.isBlank()) {
        message.put("subject", subject);
      }
      message.put("body", body);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("address", address.canonical());
      payload.put("key", KEY_MESSAGE);
      payload.put("value", ToolResults.json(message));
      payload.put("id", infoId);
      // ★ I5：可见性 = 收发双方（tags 是 DecisionMakerId 的集合；GM 读全部，不靠 tags）。
      payload.put("tags", List.of(sender.value(), to.value()));
      payload.put("tick", worldTick);
      String payloadJson = ToolResults.json(payload);

      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              PutInfoHandler.TYPE,
              payloadJson);

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("address", address.canonical());
      view.put("id", infoId);
      view.put("from", sender.value());
      view.put("to", to.value());
      view.put("tick", worldTick);
      if (subject != null && !subject.isBlank()) {
        view.put("subject", subject);
      }
      view.put("submitted", true);
      view.put("commandId", receipt.commandId());
      return switch (receipt.outcome()) {
        case ToolWritePort.Outcome.Committed committed -> {
          view.put(
              "submission",
              ToolResults.committedView(committed.ref(), receipt.commandId(), receipt.commandId()));
          yield ToolResults.ok(view);
        }
        case ToolWritePort.Outcome.Conflict conflict -> {
          Map<String, Object> submission = new LinkedHashMap<>();
          submission.put("result", "conflict");
          submission.put("current", ToolResults.stateRef(conflict.current()));
          view.put("submission", submission);
          yield ToolResult.error("CONFLICT", ToolResults.json(view));
        }
        case ToolWritePort.Outcome.Rejected rejectedOutcome -> {
          Map<String, Object> submission = new LinkedHashMap<>();
          submission.put("result", "rejected");
          submission.put("reason", rejectedOutcome.reason());
          view.put("submission", submission);
          yield ToolResult.error("REJECTED", ToolResults.json(view));
        }
      };
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**决策人面**（GM 不是决策人，它没有这个身份 ⇒ 不注册进 GM 桶）。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}
