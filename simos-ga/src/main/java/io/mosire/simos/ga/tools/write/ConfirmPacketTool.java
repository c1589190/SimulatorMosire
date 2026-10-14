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
import io.mosire.simos.ga.spi.SubmitDecisionPacketHandler;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
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
import java.util.Set;

/**
 * ★★ {@code simos.ga.packet.confirm}（DM 面；2026-10-14 GM⇄DM 设计书 §3.2 / 缺口 G4 / 不变量 I10）：<b>决策人确认本
 * tick 的全部决策</b>——DRAFT → PENDING。
 *
 * <p>★★ <b>它是 DM 的一次真实动作</b>（用户 2026-10-14 原话「DM 要允许通过工具确认之前的所有决策」）：运行流**不再**在回合内拦截它， 也不再由回合末自动替 DM
 * 提交（旧 {@code simos.sd.packet.submit} 的 {@code {"submitted":false,"deferred":true}} 形态已整条删除）⇒ DM
 * 调它，包**立刻**变 PENDING。
 *
 * <p>★ <b>"全部决策" = 包里的全部 calls + intent</b>：跨轮累积（同 tick 跑多轮，每轮都能再改/再加，见 {@link ReviseCallTool} /
 * {@link DropCallTool}）⇒ {@code confirm} 定稿的是**这一刻**的整包，不是某一轮那一条。
 *
 * <p>★★ <b>提交可以反复做</b>（用户 2026-10-15：「gm 应当取最后一轮提交的决策……提交多少轮都没问题」；设计书 §3.9.2 / 不变量 I13）：<b>已是
 * PENDING ⇒ 幂等成功</b>（返回现状、**零 revision**）——它的意思就是"我又提交了一次，**以这一次为准**" （包内容没变，所以不必再写一遍）。★ 于是"PENDING
 * 上继续改（{@link ReviseCallTool} / {@link DropCallTool} / {@link PacketIntentTool} / {@link
 * ProposeCallTool}）→ 再 confirm"是**正常的工作流**，不需要中间调一回 {@link ReopenPacketTool}。
 *
 * <p>★★ <b>两条真边界</b>（设计书 §3.9.3）：<b>已裁决 ⇒ 具名拒</b>（不变量 I14：{@code decidedBy} 非空 **或** 状态属四种终态； 统一判据在
 * {@link PacketDraftSupport#rejectIfDecided}）；<b>非当前 tick ⇒ 具名拒</b>（不变量 I15：历史 tick 只读）。本 tick 没有包
 * ⇒ 具名拒。★ GM 已裁决之后 DM **改不了也再提交不了**（不变量 I3）—— 那条硬防线落在 {@link ReopenPacketTool} 与 {@link
 * PacketDraftSupport#rejectIfDecided} 两侧，因为 {@code ga.UpsertDecisionPacket} 本身**没有状态机**。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5 权限表、用户同日裁定「决策-提交流程属无条件执行」）：本工具 **零世界效果**（只写自己的包）⇒
 * {@code gate() = ALLOW}、{@code roles() = {DM}}。★ 闸门在 GM 侧 （{@code gm.packet.decide} / {@code
 * mergedPlan.*} / {@code packet.execute} 保持 GM-only，本批一处不放松）。
 *
 * <p>★ 身份从 {@code context.identity()} 派生（不采信载荷）；只写自己的 {@code sd:decision-packet/<自己>} 前缀。
 */
public final class ConfirmPacketTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.confirm";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param identity 身份口（决策人 id 从调用者身份派生，不采信载荷）
   */
  public ConfirmPacketTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人确认**本 tick 的全部决策**（本 tick 的决策在此定稿交给 GM）：DRAFT → PENDING。参数 "
        + "{tick?(缺省 = 当前世界 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "确认的是**整包**（跨轮累积的全部 calls + intent），不是某一条。"
        + "★ 本 tick 内**可以提交很多次，GM 以最后一次为准**：已 PENDING ⇒ 幂等成功（返回现状、**不写 revision**）"
        + "——它的意思就是「我又提交了一次」。★ 提交之后想再改**不必**先撤回："
        + ReviseCallTool.NAME
        + " / "
        + DropCallTool.NAME
        + " / "
        + PacketIntentTool.NAME
        + " / "
        + ProposeCallTool.NAME
        + " 在 PENDING 上会自动把包撤回成 DRAFT，改完再 confirm 一次即可。"
        + "★ 只有**已裁决**（decidedBy 非空或状态 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）才真的不能再提交 "
        + "⇒ BAD_REQUEST；只许动**当前 tick** 的包（历史 tick 只读）。本 tick 没有包 ⇒ BAD_REQUEST。"
        + "返回 {packetId, status, callCount, submitted}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "要确认的 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 类3 {@code DM_FREE}：DM 确认自己的决策包，零世界效果（设计书 §5 权限表）。 */
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
      // ★★ I15（只许动当前 tick 的包）：tick 显式给出且不是世界当前 tick ⇒ 拒（历史 tick 只读）。
      long currentTick = reads.stateAt(branch, null).meta().timestamp().tick();
      Optional<ToolResult> staleTick =
          PacketDraftSupport.rejectIfNotCurrentTick(packetId.value(), tick, currentTick);
      if (staleTick.isPresent()) {
        return staleTick.get();
      }
      DecisionPacket packet = ga.decisionPackets().get(packetId);
      if (packet == null) {
        return ToolResult.error(
            "BAD_REQUEST",
            "本 tick 还没有决策包（先 "
                + ProposeCallTool.NAME
                + " 或 "
                + PacketIntentTool.NAME
                + "）: "
                + packetId.value());
      }
      if (!packet.proposerId().equals(proposerId)) {
        return ToolResult.error("FORBIDDEN", "决策包不属于调用者: " + packetId.value());
      }
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceGa(identity.decisionPacketKind(), proposerId.value())));
      // ★★ I14（已裁决 ⇒ 拒）：唯一判据在 PacketDraftSupport——必须在"已 PENDING ⇒ 幂等成功"**之前**判，
      //   否则一个"已裁决但状态残留 PENDING"的契约故障会被当成幂等成功放过去。
      Optional<ToolResult> decided = PacketDraftSupport.rejectIfDecided(packet);
      if (decided.isPresent()) {
        return decided.get();
      }
      if (packet.status() == PacketStatus.PENDING) {
        return ToolResults.ok(packetView(packet, false)); // 幂等：又提交一次（以这一次为准）⇒ 返回现状
      }
      if (packet.status() != PacketStatus.DRAFT) {
        // ★ fail-closed：走到这里 = 状态既不是 DRAFT 也不是 PENDING，而上面那条 decided 判据又没拦住
        //   （decidedBy 为空 + 状态不在四种终态里）⇒ 只可能是将来新增的状态取值 ⇒ 不放行。
        return ToolResult.error(
            "BAD_REQUEST",
            "决策包状态不是可提交的草稿，也不在已知的已裁决终态里 ⇒ 拒绝提交（fail-closed）: "
                + packetId.value()
                + " status="
                + packet.status());
      }
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）——载荷里嵌的 id 与落盘信封的是同一个。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              SubmitDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.submit(packetId.value(), proposerId.value()));
      Map<String, Object> view = packetView(packet, true);
      view.put("status", PacketStatus.PENDING.name());
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "确认决策包失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Map<String, Object> packetView(DecisionPacket packet, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("status", packet.status().name());
    view.put("callCount", packet.calls().size());
    view.put("submitted", submitted);
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}
