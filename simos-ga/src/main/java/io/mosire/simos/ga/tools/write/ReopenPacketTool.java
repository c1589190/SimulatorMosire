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
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.read.GmRoundTool;
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
 * ★★ {@code simos.ga.packet.reopen}（DM 面，本批新建；2026-10-14 GM⇄DM 设计书 §3.2 / §9-B）：<b>撤回确认</b>—— 把本
 * tick 自己的包 PENDING → DRAFT。
 *
 * <pre>{@code
 * {"tick":"可选","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>语义收窄（2026-10-15 用户裁定："PENDING 不是锁"；设计书 §3.9.2）：本工具 = 显式撤回提交标记，内容一字不动</b>。
 * 它**不再**是"想改包"的必经之路——四个修改工具（{@link ProposeCallTool} / {@link ReviseCallTool} / {@link
 * DropCallTool} / {@link PacketIntentTool}）在 PENDING 上会**自动撤回成 DRAFT**（隐式 reopen），DM 不必自己调它。
 * 保留它的用途只有一个：让这个 DM **重新出现在** GM 的 {@code simos.gm.round} 的 {@code pendingConfirmations} 里 —— 即明确告诉
 * GM"我还没说完，先别裁"。★ 所以它**不改 calls、不改 intent**，只翻状态。
 *
 * <p>★★★ <b>硬防线（不变量 I3 / I14）：已裁决的包不可回到 DRAFT。</b>判据是**合取**、且只有一处实现 （{@link
 * PacketDraftSupport#rejectIfDecided}）：{@code decidedBy} **非空** **或** 状态是 {@code APPROVED} / {@code
 * REJECTED} / {@code MERGED} / {@code PARTIALLY_APPROVED} 任一 ⇒ 具名拒。
 *
 * <p>★★ <b>为什么这条防线必须落在工具侧</b>（设计书 §3.2 原文的"这一条是防线，不是顺手"）：{@code ga.UpsertDecisionPacket}
 * **本身没有状态机**——status 从载荷取、整包覆盖（{@code UpsertDecisionPacketHandler:93,132-136}）⇒ 不加这两条守卫，DM 就能把 GM
 * 已批的包翻回 DRAFT、改完再提交， **GM 的裁决被静默抹掉**（没有任何一层会报错）。撤回的**合法**边界只是"我改主意了，GM 还没看"，不是"我要撤销 GM 的决定"。
 *
 * <p>★ <b>幂等</b>：已经是 DRAFT ⇒ 幂等成功（返回现状，**不写 revision**）——"撤回"是个状态目标，不是一次计数。
 *
 * <p>★ <b>本 tick 没有包</b> ⇒ {@code BAD_REQUEST}；★ <b>非当前 tick</b> ⇒ {@code BAD_REQUEST}（不变量 I15，历史
 * tick 只读）。 ★ <b>零 revision</b> 是拒因的共同后果：所有守卫都在 {@code writes.submitWithReceipt}
 * **之前**，被拒时世界（含包）逐字段不变。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5）：撤回零世界效果（不变量 I1，只写自己的包）⇒ {@code gate() =
 * ALLOW}、{@code roles() = {DM}}。★ 身份从 {@code context.identity()} 派生（不采信载荷）——
 * "撤回**自己的**确认"是这条的唯一语义，替别人撤回既没必要也不该有入口。
 */
public final class ReopenPacketTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.reopen";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param identity 身份口（决策人 id 从调用者身份派生，不采信载荷）
   */
  public ReopenPacketTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人**显式撤回提交标记**：把本 tick 自己的决策包 PENDING → DRAFT，内容**一字不动**。参数 "
        + "{tick?(缺省 = 当前世界 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "★ 用途 = 让 GM 的 "
        + GmRoundTool.NAME
        + " 的 pendingConfirmations 里重新出现你（明确告诉 GM「我还没说完，先别裁」）。"
        + "★ **改包不必先调它**："
        + ReviseCallTool.NAME
        + " / "
        + DropCallTool.NAME
        + " / "
        + PacketIntentTool.NAME
        + " / "
        + ProposeCallTool.NAME
        + " 在 PENDING 上会自动把包撤回成 DRAFT（隐式 reopen），改完再 "
        + ConfirmPacketTool.NAME
        + " 提交即可。"
        + "★ **已裁决就撤不回来**：GM 已经裁决过（decidedBy 非空）或状态属 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED "
        + "⇒ 具名拒 + 包不变（GM 的裁决不会被抹掉）。已是 DRAFT ⇒ 幂等成功（返回现状，不写 revision）。"
        + "本 tick 没有包 ⇒ BAD_REQUEST；非当前 tick ⇒ BAD_REQUEST（历史 tick 只读）。"
        + "返回 {packetId, status, reopened, callCount, decidedBy?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "要撤回的 tick（缺省 = 当前世界 tick）"));
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
    return GA_WRITE;
  }

  /** ★ 类3 {@code DM_FREE}：撤回自己的确认，零世界效果（不变量 I1；设计书 §5 权限表）。 */
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
        return ToolResult.error("BAD_REQUEST", "本 tick 没有决策包，无可撤回: " + packetId.value());
      }
      if (!packet.proposerId().equals(proposerId)) {
        return ToolResult.error("FORBIDDEN", "决策包不属于调用者: " + packetId.value());
      }
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceGa(identity.decisionPacketKind(), proposerId.value())));

      // ★★★ 硬防线（不变量 I3 / I14）：GM 已裁决 ⇒ 绝不允许回到 DRAFT。★ 判据的**唯一实现**在 PacketDraftSupport
      //   （decidedBy 非空 **或** 状态属四种终态；两者不一致 = 契约故障，也拒，fail-closed）。
      Optional<ToolResult> decided = PacketDraftSupport.rejectIfDecided(packet);
      if (decided.isPresent()) {
        return decided.get();
      }
      // ★ 幂等：已经是 DRAFT ⇒ 返回现状，不写 revision。
      if (packet.status() == PacketStatus.DRAFT) {
        Map<String, Object> view = view(packet, false);
        view.put("status", PacketStatus.DRAFT.name());
        return ToolResults.ok(view);
      }
      // ★ 走到这里只可能是 PENDING（DRAFT 与四种终态上面都已返回）——但**不写成隐式假设**：撤回是一次**真实的状态翻转**，
      //   一旦将来 PacketStatus 多出一个取值，没有这支的话它会静默走进下面的 upsert。fail-closed 的代价只有一行。
      Optional<ToolResult> notPending = rejectIfNotPending(packet);
      if (notPending.isPresent()) {
        return notPending.get();
      }

      DecisionPacket draft = packet.withStatus(PacketStatus.DRAFT);
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(draft));
      Map<String, Object> view = view(packet, true);
      view.put("status", PacketStatus.DRAFT.name());
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "撤回失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 唯一可撤回的状态 = {@code PENDING}（见类注"撤回的合法边界只是我改主意了、GM 还没看"）。
   *
   * <p>★ <b>DRAFT / 四种已裁决终态一律不走这里</b>（前两支在 {@link #execute} 里已经返回）：本方法若是被别处调用， 对 DRAFT 会返回 {@code
   * BAD_REQUEST}——这是**刻意的 fail-closed**，好过让一个新的调用点悄悄写出第二条 DRAFT 路径。
   */
  private static Optional<ToolResult> rejectIfNotPending(DecisionPacket packet) {
    if (packet.status() == PacketStatus.PENDING) {
      return Optional.empty();
    }
    return Optional.of(
        ToolResult.error(
            "BAD_REQUEST",
            "只有已确认（PENDING）的决策包可撤回，当前状态: " + packet.status() + "（" + packet.id().value() + "）"));
  }

  private static Map<String, Object> view(DecisionPacket packet, boolean reopened) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("status", packet.status().name());
    view.put("reopened", reopened);
    view.put("callCount", packet.calls().size());
    packet.decidedBy().ifPresent(value -> view.put("decidedBy", value));
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}
