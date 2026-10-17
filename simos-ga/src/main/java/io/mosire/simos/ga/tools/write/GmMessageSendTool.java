package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.gm.message.send}（GM 面，2026-10-14 GM⇄DM 双向信件 + 推演确认）：<b>GM 回一个决策人的信</b>。
 *
 * <pre>{@code
 * {"to":"决策人 id","body":"必填非空白","subject":"可选","replyTo":"可选：你在回答的那条信的 id",
 *  "branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>它补的是缺口 G2</b>：{@code gm.*} 一族里此前**没有任何**能读 INFO 或发信的工具（{@code gm.packets} / {@code
 * gm.packet} / {@code gm.approvals} / {@code gm.tool-usage} 全是别的用途），而 {@code simos.ga.message.send}
 * 的 {@code toolRoles()={DM}} 不进 GM 桶 ⇒ GM 收不到也回不了话。DM 侧的收件箱是 {@code simos.ga.message.inbox}，GM 侧的是
 * {@code simos.gm.inbox}——**两条各有各的角色**（本工具 {@code toolRoles()={GM}}， <b>不是</b> {@code {DM,GM}}：DM
 * 侧有它自己的那条）。
 *
 * <p>★★ <b>形状与 DM 侧同源</b>（不复制第二份）：地址 {@code ga:message.<gm>.<to>.<tick>.<n>}、条目 id {@code
 * msg-gm-<to>-<tick>-<n>}、条目 {@code key = }{@link GaMessageSendTool#KEY_MESSAGE}、{@code tags = [gm,
 * to]}、 同 tick 同方向上限 {@value GaMessageSendTool#MAX_MESSAGES_PER_TICK_PER_DIRECTION}——四件事都从 {@link
 * GaMessageSendTool} 的静态助手取（那个类是本模块信件形状的**唯一拼写点**）。 ★ <b>信件的 {@code from} 恒为</b> {@link
 * ToolResources#GM_ID}：它是编译期常量，载荷里**没有** {@code from} 键（I6/I7 的对称面——DM 侧从身份派生，GM 侧从常量取，
 * 两侧都不可能被载荷伪造）。
 *
 * <p>★★ <b>收件人必须是**已存在**的决策人</b>（设计书 §3.1 的"GM：任意存在的决策人"）：读 {@code GaState.decisionMakers()}
 * 判存在性，不存在 ⇒ **提交之前**具名拒 {@code BAD_REQUEST}（世界零变化）。★ 为什么不在 ga 里另开一个 {@code
 * DecisionMakerExistencePort}：决策人表**就在本模块自己的状态树里**（{@link GaState#decisionMakers()}）， 为一次 {@code
 * containsKey} 造端口等于把同一份事实说两遍。 ★ 名册**不当成错误文案的一部分**：拒因只回显调用者填的那个 id， 不列出"有哪些决策人"（不泄漏名册，与 DM 侧同一条纪律）。
 *
 * <p>★ <b>上限同为 {@value GaMessageSendTool#MAX_MESSAGES_PER_TICK_PER_DIRECTION} 条 / 每 tick /
 * 每方向</b>：GM 一次问遍所有人的 场景由"每方向"覆盖（方向 = {@code gm → <该 dm>}），不是全局 128。命令期判、第 N+1 条具名拒、世界零变化。
 *
 * <p>★ <b>闸门 = {@code Ask}</b>（与其余 GM 敏感写同制，见 {@code GmAutoApproveGate} 类注：GM 链**无脑过**）： GM
 * 面的每一次调用都落 {@code GmToolUsage} 留痕，且若将来把它挂到别的审批链上，它仍是一条要人裁决的工具。 ★ <b>它不继承 {@code
 * AbstractNarrowWriteTool}</b>：那条基类的固定命令类型要有一条 handler（本工具提交的是既有的 {@code ga.PutInfo}）， 且它的 {@code
 * sensitive} 与"窄写 ⇒ 审批"那条不变量会被这次继承带出例外。
 *
 * <p>★★ <b>资源声明 = {@link ResourceManifest#NONE}（有意的，不是漏写）</b>：GM 组的可达面**没有** {@code peer} 这一维表态
 * （{@code Shell#gmPermissionSet} 逐命名空间列了
 * map/social/unit/sd/economy/actor/army/workspaces/docs，<b>不含 peer</b>）⇒ 若本工具把 {@code peer} 写进声明并断言
 * {@code peer:<to>}，{@code ResourceAuthorizer} 会按"调用者未表态 + 缺省策略 {@code DENY}"在**执行前闸**上把整调拒掉（{@code
 * ToolResources.PEER_NAMESPACE} javadoc 第 3 条记的正是这个坑）。 ★ 而改 {@code Shell}
 * 的权限集**不在本批可动范围**；且没有必要——GM 本来就持有通用写（{@code simos.command.submit} 可提交任意 {@code ga.PutInfo}，用户
 * 2026-09-22 裁定 D2 的有意取舍）⇒ 本工具**不新增任何写能力**，它新增的是"一条形状正确、署名 gm、带序号的信"。故取 {@code
 * NONE}：不声张任何世界命名空间，收件人判据走**状态事实**（决策人表）而不是资源断言。
 *
 * <p>★ <b>它不写世界</b>：落的是 {@code info} 感知层（{@link PutInfoHandler} 一条 {@code ga.PutInfo}）， 与 DM
 * 侧的收发同族；世界切片逐字段不变，变的是 {@code ga.info} 覆盖层与一条 revision。
 */
public final class GmMessageSendTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（它提交的是既有的 ga.PutInfo）。 */
  public static final String NAME = "simos.gm.message.send";

  /** 工具面事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  private final ToolWritePort writes;
  private final ToolReadPort reads;

  public GmMessageSendTool(ToolWritePort writes, ToolReadPort reads) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 给某个决策人发一条消息（回信）：载荷 {to, body, subject?, replyTo?}。"
        + "to = **已存在**的决策人 id（不存在 ⇒ 命令期具名拒、世界零变化）；body 必填非空白（≤ "
        + GaMessageSendTool.MAX_BODY_CHARS
        + " 字符）；replyTo = 你在回答的那条信的 id（填了 ⇒ 对方那条信在 simos.gm.inbox 里显示 answered=true）。"
        + "★ 同一 tick 同一收件人**最多 "
        + GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION
        + " 条**（超出 ⇒ 命令期具名拒）。"
        + "发出人恒为 \""
        + ToolResources.GM_ID
        + "\"（不可伪造）；落点是 info 感知层（不写世界命名空间，只落一条 revision）。"
        + "★ 决策人的答复会自动出现在它的下一轮会话里（它不需要自己去读收件箱）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("to", ToolSchema.prop("string", "收件人：已存在的决策人 id"));
    props.put(
        "subject",
        ToolSchema.prop("string", "主题（可选，≤ " + GaMessageSendTool.MAX_SUBJECT_CHARS + " 字符）"));
    props.put(
        "body",
        ToolSchema.prop("string", "正文（必填非空白，≤ " + GaMessageSendTool.MAX_BODY_CHARS + " 字符）"));
    props.put("replyTo", ToolSchema.prop("string", "你在回答的那条信的 id（可选；填了 ⇒ 那条信 answered=true）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("to", "body"));
  }

  /** 敏感写（走审批门链）、可外发：与其余 GM 敏感写同制（{@code GmAutoApproveGate} 在 GM 面无脑过）。 */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  /** ★ {@code NONE}：GM 组没有 {@code peer} 表态，声张 {@code peer} 会被执行前闸整调拒（理由全文见类注末段）。 */
  @Override
  public ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    // ★ 摘要只做字段回显、不解析参数：gate() 在 execute() 的校验之前调用，解析失败不能让审批链先炸（照 GmApproveTool）。
    Map<String, Object> args = context.arguments();
    String to = String.valueOf(args.get("to"));
    String replyTo = args.get("replyTo") == null ? "-" : String.valueOf(args.get("replyTo"));
    return new ToolGate.Ask(NAME, "GM 回信 to=" + to + " replyTo=" + replyTo, AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      DecisionMakerId to = DecisionMakerId.parse(ToolSchema.requiredText(args, "to"));
      String body = ToolSchema.requiredText(args, "body");
      if (body.isBlank()) {
        return reject("BAD_REQUEST", "body 不得为空白");
      }
      if (body.length() > GaMessageSendTool.MAX_BODY_CHARS) {
        return reject(
            "BAD_REQUEST",
            "body 上限为 " + GaMessageSendTool.MAX_BODY_CHARS + " 字符: " + body.length());
      }
      String subject = ToolSchema.optionalText(args, "subject", null);
      if (subject != null && subject.length() > GaMessageSendTool.MAX_SUBJECT_CHARS) {
        return reject(
            "BAD_REQUEST",
            "subject 上限为 " + GaMessageSendTool.MAX_SUBJECT_CHARS + " 字符: " + subject.length());
      }
      String replyTo = ToolSchema.optionalText(args, "replyTo", null);

      BranchId branch = GaToolReads.branch(args);
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      long worldTick = state.meta().timestamp().tick();
      GaState ga = GaToolReads.ga(state);
      // ★ 收件人必须**已存在**（设计书 §3.1"GM：任意存在的决策人"）：存在性就是决策人表里的一行——同模块内直接读，
      //   不为一次 containsKey 造端口（那会把同一份事实说两遍）。★ 拒因只回显调用者填的 id，不列名册。
      if (!ga.decisionMakers().containsKey(to)) {
        return reject("BAD_REQUEST", "决策人不存在（收件人必须是已存在的决策人 id）: " + to.value());
      }

      // ★ 上限与 DM 侧同一段判据（同 tick 同方向 128 条）：命令期判、第 N+1 条具名拒、世界零变化。
      int sequence =
          GaMessageSendTool.countMessages(ga, ToolResources.GM_ID, to.value(), worldTick);
      if (sequence >= GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION) {
        return reject(
            "BAD_REQUEST",
            "本 tick 上限 "
                + GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION
                + " 条（同一 tick、同一方向 "
                + ToolResources.GM_ID
                + "→"
                + to.value()
                + " 已有 "
                + sequence
                + " 条）：命令期拒、世界零变化——请等下一个 tick，不要在同一个 tick 里继续发。");
      }

      String infoId =
          GaMessageSendTool.messageId(ToolResources.GM_ID, to.value(), worldTick, sequence);
      Address address =
          GaMessageSendTool.messageAddress(ToolResources.GM_ID, to.value(), worldTick, sequence);
      String payloadJson =
          GaMessageSendTool.putInfoPayload(
              address, infoId, ToolResources.GM_ID, to.value(), worldTick, subject, body, replyTo);

      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              PutInfoHandler.TYPE,
              payloadJson);

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("address", address.canonical());
      view.put("id", infoId);
      view.put("from", ToolResources.GM_ID);
      view.put("to", to.value());
      view.put("tick", worldTick);
      view.put("sequence", sequence);
      if (subject != null && !subject.isBlank()) {
        view.put("subject", subject);
      }
      if (replyTo != null && !replyTo.isBlank()) {
        view.put("replyTo", replyTo);
      }
      view.put("submitted", true);
      view.put("commandId", receipt.commandId());
      return switch (receipt.outcome()) {
        case ToolWritePort.Outcome.Committed committed -> {
          TOOL.debug(
              LogEvent.of(
                  "GM_MESSAGE_SENT",
                  GaLogSource.GA_TOOL_CALL,
                  "tool",
                  NAME,
                  "id",
                  infoId,
                  "to",
                  to.value(),
                  "tick",
                  worldTick,
                  "sequence",
                  sequence));
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
      return reject("BAD_REQUEST", e.getMessage());
    }
  }

  /** 具名拒绝的唯一发射点（业务拒绝 = INFO，用户 2026-10-23 裁定）；拒因原文同时在返回值里给调用方。 */
  private static ToolResult reject(String code, String reason) {
    TOOL.info(
        LogEvent.of(
            "GM_MESSAGE_SEND_REJECTED",
            GaLogSource.GA_TOOL_CALL,
            "tool",
            NAME,
            "code",
            code,
            "reason",
            reason));
    return ToolResult.error(code, reason);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**GM 面**（DM 侧那条是 {@link GaMessageSendTool}，两条各有各的角色）。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
