package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ {@code simos.ga.packet.intent}（DM 面；D2 决策包计划 §3.2 + 2026-10-14 GM⇄DM 设计书 §3.2）：把自然语言意图写进本 tick
 * 的 DRAFT 决策包——<b>整段覆盖</b>，不是追加。
 *
 * <p>★ 用户 2026-10-14 原话点名的能力：「DM 不是已经有那种工具调用决策，和通用的自然语言决策了吗？要允许自由覆盖/修改」 ⇒ 本工具**就是**那个覆盖口（设计书 §2.1 第
 * 4 条已核实：{@code existing.withIntent(text)} 是整段替换）。
 *
 * <p>★ 拟稿期只走 DRAFT：非 DRAFT ⇒ 具名拒（统一文案："先 {@link ReopenPacketTool} 撤回"）；本 tick 还没有包 ⇒ 建一个空 DRAFT 包再写
 * intent（一个决策人一个 tick 一个包）。
 *
 * <p>★ <b>它落下的 intent 就是"DM 显式写过 intent"的证据</b>：回合末的 {@code DecisionTurnFinalizer} 靠"包里 intent
 * 非空白"判定这一点，从而**不再**追加「【最后陈述】」（补缺口 G10）。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5）：决策表达零世界效果（不变量 I1）⇒ {@code gate() = ALLOW}、 {@code
 * roles() = {DM}}。
 *
 * <p>★ 身份从 {@code context.identity()} 派生；身份派生的 proposer 不采信载荷。★ 旧名 {@code simos.sd.packet.intent}
 * 不做兼容（Alpha 不兼容旧档）；命令类型仍是 {@code ga.UpsertDecisionPacket}。
 */
public final class PacketIntentTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.intent";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param identity 身份口（决策人 id 从调用者身份派生，不采信载荷）
   */
  public PacketIntentTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人写本 tick 决策包的 NL 意图（**整段覆盖**，不是追加；不进 true/false 机械执行）。参数 "
        + "{text(必填非空白), tick?(缺省 = 当前世界 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。只接受 DRAFT；本 tick 无包则新建空 DRAFT 再写。"
        + "★ 写过的 intent 就是你的显式表达，回合结束时系统**不会**再往它后面附加你的收尾文本。"
        + "已提交/已裁决 ⇒ BAD_REQUEST（先 "
        + ReopenPacketTool.NAME
        + " 撤回）。返回 {packetId, status, intent, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("text", ToolSchema.prop("string", "意图文本（必填非空白；整段覆盖包里已有的 intent）"));
    props.put("tick", ToolSchema.prop("integer", "要写 intent 的 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("text"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 类3 {@code DM_FREE}：决策表达零世界效果（不变量 I1；设计书 §5 权限表）。 */
  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId = identity.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }

      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      String text = ToolSchema.requiredText(args, "text");
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      if (tickArg != null && tickArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tickArg);
      }
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      DecisionPacketId packetId = PacketDraftSupport.packetIdValueOf(proposerId, tick);
      DecisionPacket existing = ga.decisionPackets().get(packetId);
      Optional<ToolResult> notDraft = PacketDraftSupport.rejectIfNotDraft(existing);
      if (notDraft.isPresent()) {
        return notDraft.get();
      }
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceSd(identity.decisionPacketKind(), proposerId.value())));
      DecisionPacket packet =
          existing == null
              ? new DecisionPacket(
                  packetId,
                  branch.value(),
                  tick,
                  proposerId,
                  PacketStatus.DRAFT,
                  text,
                  List.of(),
                  state.meta().ref().revision().value(),
                  Optional.empty(),
                  OptionalLong.empty(),
                  Optional.empty(),
                  Optional.empty())
              : existing.withIntent(text);
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）——视图里报的 id 与落盘信封的是同一个。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(packet));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      view.put("intent", text);
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "intent 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}
