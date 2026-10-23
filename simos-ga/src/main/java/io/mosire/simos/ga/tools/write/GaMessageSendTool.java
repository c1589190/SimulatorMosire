package io.mosire.simos.ga.tools.write;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionInfoEntry;
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
import io.mosire.simos.ga.tools.read.GaMessageInboxTool;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.json.SimosObjectMapper;
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
 * ★★ {@code simos.ga.message.send}（DM 面，2026-10-14 决策人互致消息 + GM⇄DM 双向信件）：给 <b>GM</b> 或 <b>被 GM
 * 授权与你互通的那一个决策人</b>发一条消息。
 *
 * <pre>{@code
 * {"to":"gm 或 被授权的决策人 id","subject":"可选","body":"必填非空白","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>{@code to="gm"} 永远允许、不需要 GM 先授权</b>（2026-10-14 GM⇄DM 设计书 §3.1 的裁定）：DM 写的是**自己的疑问**，
 * 不读也不写任何别人的数据 ⇒ 不构成越权；GM 读全部 INFO 本来就是它的位置。实现上它**不是特例分支**：{@code peer:gm} 与 {@code peer:<自己>}
 * 同属**人人皆有的基础可达面**（{@code DecisionCallerFactory.withPeerSelfFace}），故下面那条 {@code peer:<to>} 断言对
 * {@code gm} 与对"被授权的 DM"**走同一条路**。 ★ 而<b>DM→DM 仍需授权</b>（那是把信息给第三方）：未授权 ⇒ {@code
 * RESOURCE_DENIED}（{@code peer:<对方>} 够不着）。
 *
 * <p>★★ <b>闸门 = 事先授权，不是逐条审批</b>（设计书 §4.2 / I7）：GM 的管控点是 {@code ga.SetDecisionMakerPeer} 写下的 {@code
 * accessLimit.peer} 授权表 ⇒ 本工具 {@code gate() = ALLOW}。★ 因此它**故意不 extends {@code
 * AbstractNarrowWriteTool}**（那个基类硬编码 {@code Ask(SENSITIVE)}）：让它进"窄写家族"会让"窄写 ⇒ 逐条审批"
 * 这条不变量出现例外，既有的两条审计（写闸覆盖集 / 窄写 ↔ GM 桶）随即失去判别力。
 *
 * <p>★★ <b>同 tick 同方向可发多条（本批的核心放宽）</b>：问答至少需要来回多条，而旧形态的条目 id 是 {@code msg-<from>-<to>-<tick>}（显式 id
 * ⇒ {@code PutInfoHandler} 命令期重复即拒）⇒ 一问一答都发不完。现在：
 *
 * <table border="1">
 *   <caption>地址与 id 都带序号</caption>
 *   <tr><th>项</th><th>形状</th></tr>
 *   <tr><td>地址</td><td>{@code ga:message.<from>.<to>.<tick>.<n>}（{@code n} 从 0 起）</td></tr>
 *   <tr><td>条目 id</td><td>{@code msg-<from>-<to>-<tick>-<n>}（仍**显式给**，仍靠命令期查重；序号保证不撞）</td></tr>
 * </table>
 *
 * <p>★★ <b>上限 = {@value #MAX_MESSAGES_PER_TICK_PER_DIRECTION} 条 / 每 tick / 每方向</b>（设计书 §9-C
 * 的用户裁定，原话： 「这个上限太傻逼了，至少改成128」）：<b>命令期判</b>——本工具在提交之前自己数一次该 {@code (from,to,tick)} 下已有条数， 第 {@value
 * #MAX_MESSAGES_PER_TICK_PER_DIRECTION} + 1 条**具名拒 {@code BAD_REQUEST} + 世界零变化**（不静默截断、不排队）。★
 * 收敛责任交给人/模型（上限到了就等下一个 tick），**不是**靠丢消息。
 *
 * <p>★ <b>条数按语义判据数</b>（{@code value.from/to/tick} 三个字段逐一比对），<b>不是按地址前缀</b>：地址前缀在 id 含 {@code .}
 * 时会撞（{@code dm-a.dm-b} 与 {@code dm-a} + {@code dm-b.x} 渲染出同一段），而语义判据无歧义。
 *
 * <p>★ <b>未授权 = 具名拒</b>：{@code execute} 里按**参数**（收件人）断言 {@code peer:<to>} 与自指 {@code
 * peer:<自己>}（{@link ToolResources#resourcePeer}）——断言在**提交之前**，被拒 ⇒ 世界零变化（零 revision）。 可达面文案由 {@code
 * ResourceAuthorizer} 给（"够不着"），不另造一套。
 *
 * <p>★★ <b>收件人 == 自己 ⇒ 具名拒</b>（{@code BAD_REQUEST}，在**任何写入之前** ⇒ 零 revision）。★ 这道守卫不是锦上添花： 设计书 §4.1
 * 给**每个**决策人的基础可达面补了自指域 {@code peer:<自己>}（{@code inbox} 与"以自己名义"的断言要用它）⇒ 一旦 {@code to ==
 * 自己}，上面那两条资源断言**全都过** ⇒ 等于给每个决策人开了一条**免 GM 授权**的写通道（"只有 GM 授权才能用"当场失效）； 它还会**每 tick 烧掉一条
 * revision**（自发自收的信），并**破设计书 §10.4 的行为契约**—— "发件人在自己的 {@code inbox} 里看不到自己发出去的信"（自发自收的信 {@code to
 * == 自己}，恰好落进自己 {@code inbox} 的那道筛子）。
 *
 * <p>★ <b>为什么这个码是 {@code BAD_REQUEST} 而不是 {@code RESOURCE_DENIED}</b>：收件人 = 自己**不是**"够不着某块资源"
 * ——{@code peer:<自己>} 是**人人皆有**的基面，配置面拿不掉它，GM 也无从"再授权一次"来放行；它是**用法错**（{@code to}
 * 该填**另一个**收件人）。两者共用一个码，"改参数"与"改配置"这两种修法就分不开了（既有运行流正是靠这个区分给模型回灌）。 ★
 * 因此本守卫放在资源断言**之前**：拒因**不随调用者的可达面变化**（零授权的决策人与被 GM 授权者得到同一句拒）， 且它在任何 reads / submit 之前 ⇒ 被拒时世界零变化。
 *
 * <p>★ <b>不泄漏名册</b>（I4）：本工具从不列出候选收件人——{@code to} 只能填 {@code gm} 或**被授权的那一个 id**（超出可达面即拒，
 * 而错误文案只回显调用者自己填的那个 id）。
 *
 * <p>★ <b>发件人不可伪造</b>（I6）：{@code from} 从 {@link ToolContext#identity()} 派生（身份里的 {@code
 * decision-maker:} 前缀），**不采信载荷里的任何 id**。载荷里根本没有 {@code from} 键。
 *
 * <p>★ <b>落点是感知层</b>（I8：不新增 {@code GaState} 组件）：一条 {@code ga.PutInfo}，地址 {@code
 * ga:message.<from>.<to>.<tick>.<n>}（I9：不产生 {@code sd:} 地址），{@code tags={from,to}}（I5：可见性 = 收发双方 +
 * GM，零新机制）。
 *
 * <p>★★ <b>本类还是信件形状的唯一拼写点</b>（GM 侧工具与运行流都从这里取，不各写一份）：{@link #KEY_MESSAGE}（条目 key）、{@link
 * #MAX_MESSAGES_PER_TICK_PER_DIRECTION}（上限）、{@link #countMessages}（计数）、{@link #messageId} / {@link
 * #messageAddress}（地址与 id）、{@link #messageValue}（条目值）、{@link #parseMessageValue}（值 → JSON 对象）。 ★
 * 它们取 {@code public static}（不是包内可见）：调用方分布在三个包——{@link GmMessageSendTool}（同包）、{@code
 * io.mosire.simos.ga.tools.read.GmInboxTool}（收件箱与轮次看板）、{@code
 * io.mosire.simos.ga.decision.DecisionAgentRunner}（新信件注入）——单一拼写点的价值正在于**跨包也只此一份**。
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

  /**
   * **同 tick 同方向**的信件条数上限（★ 用户 2026-10-14 裁定：「这个上限太傻逼了，至少改成128」；设计书 §9-C 落地为**命令期**判）。
   *
   * <p>★ 它是<b>硬上限</b>（第 N+1 条具名拒、世界零变化），不是提示词里的建议——"靠模型自觉"在问答跑飞时没有任何牙。 ★ 为什么不是"截断注入"：截断会静默丢掉 GM 的答复
   * = 问答断链（设计书 §9-C 的裁定原文），故收敛压力落在**发送方**。
   */
  public static final int MAX_MESSAGES_PER_TICK_PER_DIRECTION = 128;

  /** 信件条目值 → JSON 对象的解析器（与 {@code ToolResults.json} 同一份建造方式）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

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
    return "给 **GM**（to=\"gm\"，永远可用、不需要事先授权）或**被 GM 授权与你互通**的那一个决策人发一条消息。载荷 {to, subject?, body}："
        + "to = "
        + ToolResources.GM_ID
        + " 或对方的决策人 id（★ 只填 GM 授权给你的那一个——系统**不提供**决策人名册；且不得填自己）；"
        + "body 必填非空白（≤ "
        + MAX_BODY_CHARS
        + " 字符）。★ 同一 tick 同一方向**最多发 "
        + MAX_MESSAGES_PER_TICK_PER_DIRECTION
        + " 条**（每条自动带序号 ⇒ 可以来回多轮；到上限 ⇒ 命令期具名拒、世界零变化）。"
        + "未授权（或超出本 tick 上限）⇒ 命令期具名拒、世界零变化。"
        + "对方用 "
        + GaMessageInboxTool.NAME
        + " 收；GM 的回信用 simos.gm.message.send。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "to",
        ToolSchema.prop(
            "string", "收件人：\"" + ToolResources.GM_ID + "\"（GM，永远可用）或 GM 授权给你的那一个决策人 id（★ 不能是自己）"));
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
   * ★★ <b>审批 = 无</b>（I7）：闸门是 GM 的**事先授权**（{@code accessLimit.peer}）+ DM 基础可达面里的 {@code peer:gm}，
   * 不是逐条点头。 ★ 诚实标注（设计书 §4.3 的代价）：GM 因此**看不到**每一条消息的正文——他只是没被逐条打扰，**不是看不见**： 消息住 {@code info} 覆盖层，GM
   * 读全部（{@code simos.gm.inbox} 与 GUI 照旧可见）。
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
      //   revision（自发自收的信）、还破 §10.4（自发自收的信 to == 自己 ⇒ 出现在自己的 inbox 里）。
      //   ★ 码取 BAD_REQUEST（**用法错**：to 该填另一个收件人）——★ 不是 RESOURCE_DENIED：那会把"改参数"与"改配置"
      //     混成同一种坏法，而 peer:<自己> 是拿不掉的基面、GM 也无从"再授权一次"放行。
      //   ★ 它在资源断言**之前** ⇒ 拒因与调用者的可达面无关（零授权者与被授权者得到同一句拒），也不读任何状态。
      //   ★ to = "gm" 永远不会命中这一条（gm 不是决策人身份 ⇒ sender 不可能是 gm）。
      if (sender.value().equals(to.value())) {
        return ToolResult.error(
            "BAD_REQUEST",
            "不能给自己发消息：本工具用于**给 GM 或被 GM 授权的另一个决策人**通信，to 必须填 gm 或**另一个**决策人 id（收件人 = 调用者自己）: "
                + to.value());
      }

      // ★ 闸门（按参数取资源）：[peer:<收件人>, peer:<自己>] —— 顺序有意：先判收件人（未授权的拒因最具体）。
      //   ★ to = "gm" **不走特例**：peer:gm 与 peer:<自己> 同属决策人的基础可达面（DecisionCallerFactory），
      //     故这一行对两种收件人逐字相同 —— "DM→GM 不需要 GM 先授权"体现在**可达面**上，不体现在分支里。
      //   ★ 必须在 submit 之前 ⇒ 被拒时世界零变化（P3 的"无 revision"）。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(
              ToolResources.resourcePeer(to.value()), ToolResources.resourcePeer(sender.value())));

      BranchId branch = GaToolReads.branch(args);
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      long worldTick = state.meta().timestamp().tick();
      GaState ga = GaToolReads.ga(state);

      // ★★ 本 tick 上限（§9-C 的用户裁定）：命令期判——数的是**已落盘**的条数（state 是提交前的快照），
      //   第 MAX+1 条当场具名拒、世界零变化；序号 n 同一个数 ⇒ 地址与 id 都不会与既有的撞。
      int sequence = countMessages(ga, sender.value(), to.value(), worldTick);
      if (sequence >= MAX_MESSAGES_PER_TICK_PER_DIRECTION) {
        return ToolResult.error(
            "BAD_REQUEST",
            "本 tick 上限 "
                + MAX_MESSAGES_PER_TICK_PER_DIRECTION
                + " 条（同一 tick、同一方向 "
                + sender.value()
                + "→"
                + to.value()
                + " 已有 "
                + sequence
                + " 条）：命令期拒、世界零变化——请等下一个 tick，不要在同一个 tick 里继续发。");
      }

      String infoId = messageId(sender.value(), to.value(), worldTick, sequence);
      Address address = messageAddress(sender.value(), to.value(), worldTick, sequence);
      String payloadJson =
          putInfoPayload(
              address, infoId, sender.value(), to.value(), worldTick, subject, body, null);

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
      view.put("sequence", sequence);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点：**决策人面**（GM 用 GM 侧那条 {@code simos.gm.message.send}）。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }

  // ── 信件形状的**唯一拼写点**（GM 侧工具 / 运行流 / 收件箱都从这里取；见类注末段）──────────────────

  /**
   * 信件条目 id：{@code msg-<from>-<to>-<tick>-<n>}。
   *
   * <p>★ <b>显式给 id</b>（不走 {@code DecisionInfoIds.synthesize}）：显式 id 在 {@code PutInfoHandler}
   * 里**命令期查重**， 而"序号 = 该方向已有条数"保证同一 {@code (from,to,tick)} 下不会撞；不同方向/不同 tick 天然不同段。
   */
  public static String messageId(String from, String to, long tick, int sequence) {
    return "msg-" + from + "-" + to + "-" + tick + "-" + sequence;
  }

  /** 信件地址：{@code ga:message.<from>.<to>.<tick>.<n>}（序号进地址段 ⇒ 同 tick 同方向多条各占一个地址）。 */
  public static Address messageAddress(String from, String to, long tick, int sequence) {
    return new Address(
        List.of(
            new Namespace("ga"),
            Entity.of(KEY_MESSAGE, from + "." + to + "." + tick + "." + sequence)));
  }

  /**
   * 信件条目**值**（结构化：{@code from}/{@code to}/{@code tick} 三个事实 + 可选 {@code subject}/{@code replyTo} +
   * 正文）。★ {@code from} 只由调用方从**身份**派生后传入（载荷里没有这个键）。
   */
  public static Map<String, Object> messageValue(
      String from, String to, long tick, String subject, String body, String replyTo) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("from", from);
    message.put("to", to);
    message.put("tick", tick);
    if (subject != null && !subject.isBlank()) {
      message.put("subject", subject);
    }
    message.put("body", body);
    if (replyTo != null && !replyTo.isBlank()) {
      message.put("replyTo", replyTo);
    }
    return message;
  }

  /** {@code ga.PutInfo} 的载荷 JSON（分支/版本由调用方在信封上给；本方法是取值→载荷的**唯一**拼装点）。 */
  public static String putInfoPayload(
      Address address,
      String infoId,
      String from,
      String to,
      long tick,
      String subject,
      String body,
      String replyTo) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", address.canonical());
    payload.put("key", KEY_MESSAGE);
    payload.put("value", ToolResults.json(messageValue(from, to, tick, subject, body, replyTo)));
    payload.put("id", infoId);
    // ★ I5：可见性 = 收发双方（tags 是 DecisionMakerId 的集合；GM 读全部，不靠 tags）。
    payload.put("tags", List.of(from, to));
    payload.put("tick", tick);
    return ToolResults.json(payload);
  }

  /**
   * 该 {@code (from,to,tick)} 三元组下**已落盘**的信件条数（= 下一条信的序号 {@code n}）。
   *
   * <p>★ 判据 = **语义**（逐条解析条目值，比对 {@code from}/{@code to}/{@code tick} 三个字段），不是地址前缀匹配： 地址段在 id 含
   * {@code .} 时会歧义（{@code dm-a.dm-b} 与 {@code dm-a} + {@code dm-b.x} 拼出同一段），语义判据无歧义。 ★ 解析不出结构（不是对象
   * / 非 JSON 文本）的条目**不计数**——"看不懂的信"不参与上限判断（不猜）。
   */
  public static int countMessages(GaState ga, String from, String to, long tick) {
    int count = 0;
    for (List<DecisionInfoEntry> entries : ga.info().values()) {
      for (DecisionInfoEntry entry : entries) {
        if (!KEY_MESSAGE.equals(entry.key())) {
          continue;
        }
        Optional<JsonNode> message = parseMessageValue(entry.value());
        if (message.isEmpty()) {
          continue;
        }
        JsonNode node = message.get();
        if (!from.equals(node.path("from").asText(""))) {
          continue;
        }
        if (!to.equals(node.path("to").asText(""))) {
          continue;
        }
        JsonNode tickNode = node.get("tick");
        if (tickNode == null || !tickNode.isNumber() || tickNode.asLong() != tick) {
          continue;
        }
        count++;
      }
    }
    return count;
  }

  /**
   * 信件条目值 → JSON 对象（**唯一实现**）：值按约定是 JSON 文本（{@code ga.PutInfo} 的载荷契约）； 已是对象节点则原样用； 不是文本 / 解析失败 /
   * 不是对象 ⇒ 空（调用方**不猜**：看不见的信不显示、不计数）。
   */
  public static Optional<JsonNode> parseMessageValue(Object value) {
    if (value instanceof JsonNode node) {
      return node.isObject() ? Optional.of(node) : Optional.empty();
    }
    if (!(value instanceof String text)) {
      return Optional.empty();
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }
}
