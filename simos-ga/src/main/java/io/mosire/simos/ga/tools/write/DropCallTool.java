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
import io.mosire.simos.ga.model.FormattedCall;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.ga.packet.drop}（DM 面，本批新建；2026-10-14 GM⇄DM 设计书 §3.2 / 缺口 G3）：<b>删掉包里的一条
 * call</b>——用户原话「要允许自由覆盖/修改」里"删"的那一半。
 *
 * <pre>{@code
 * {"callIndex":0,"branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>语义</b>：删掉第 {@code callIndex} 条 call，**其余 call 的 callIndex 重排连续**（{@code 0..n-1}）。 ★
 * <b>为什么必须重排</b>：{@code DecisionPacket} 的构造期不变式是"callIndex **严格递增不重复**"，而 {@code callIndex}
 * 的语义是"在包里的位置"——留着空洞（{@code 0,2,3}）会让 {@code revise}/{@code drop} 的 {@code callIndex} 与 GM
 * 看到的序号各说各话；重排之后"第 N 条"永远只有一个意思（执行顺序 = 列表顺序，见 {@code FormattedCall} 类注）。
 *
 * <p>★ 删一条**不需要重新校验**（没有新的 args/目标被引入）：越权面不会因为删除而变大——只有 {@code callIndex} 存在性要判（不存在 ⇒ {@code
 * BAD_REQUEST}）。
 *
 * <p>★★ <b>PENDING 不是修改锁</b>（用户 2026-10-15：「提交多少轮都没问题」；设计书 §3.9.2 / 不变量 I13）：包已确认（PENDING）
 * **照样能删**——删除会**自动把包撤回成 DRAFT**（隐式 reopen，一次 revision，不必 DM 自己先调 {@link ReopenPacketTool}）， 视图里报
 * {@code status=DRAFT} + {@code autoWithdrawn} + 一句"改完记得再 confirm"。★ 删到自己提过的全部结构命令只剩 NL intent
 * 是**合法**的。
 *
 * <p>★★ <b>真正的边界只有两条</b>（设计书 §3.9.3）：
 *
 * <ol>
 *   <li><b>已裁决 ⇒ 拒</b>（不变量 I14）：{@code decidedBy} 非空 <b>或</b> 状态属四种终态 ⇒ 具名拒（统一判据在 {@link
 *       PacketDraftSupport#rejectIfDecided}）；
 *   <li><b>非当前 tick ⇒ 拒</b>（不变量 I15）：历史 tick 的包**只读**。
 * </ol>
 *
 * <p>本 tick 没有包 ⇒ {@code BAD_REQUEST}。★ 删到空包是**合法**的（DM 可以撤掉自己提过的全部结构命令，只留 NL intent）。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5）：决策表达零世界效果（不变量 I1）⇒ {@code gate() = ALLOW}、 {@code
 * roles() = {DM}}。★ 身份从 {@code context.identity()} 派生（不采信载荷）。
 */
public final class DropCallTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.drop";

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
  public DropCallTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人**删**本 tick 决策包里的一条 call：参数 {callIndex(必填、整数 ≥ 0), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "★ 删掉之后其余 call 的 callIndex **重排连续**（0..n-1）——所以删完请重新读 "
        + MyPacketTool.NAME
        + " 再改。"
        + "★ 本 tick 内可以提交（"
        + ConfirmPacketTool.NAME
        + "）**很多次**：包已是 PENDING 也**照样能删** —— 本次改动会自动把它撤回成 DRAFT（隐式 reopen，"
        + "视图里报 status=DRAFT 与 autoWithdrawn 注记），改完再 confirm 一次即可，**不必**自己先调 "
        + ReopenPacketTool.NAME
        + "。"
        + "★ 只有**已裁决**（decidedBy 非空或状态 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）才真的改不了 "
        + "⇒ BAD_REQUEST；只许动**当前 tick** 的包（历史 tick 只读）。"
        + "callIndex 不存在 ⇒ BAD_REQUEST。返回 {packetId, status, dropped:{callIndex, tool}, callCount, "
        + "callIndexes, autoWithdrawn?, note?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "callIndex",
        ToolSchema.prop("integer", "要删掉的 call 序号（必填、整数 ≥ 0；先用 " + MyPacketTool.NAME + " 读）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("callIndex"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GA_WRITE;
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
      long callIndexArg = ToolSchema.requiredLong(args, "callIndex");
      if (callIndexArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "callIndex 必须 ≥ 0: " + callIndexArg);
      }
      int callIndex = Math.toIntExact(callIndexArg);
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      long tick = state.meta().timestamp().tick();
      DecisionPacketId packetId = PacketDraftSupport.packetIdValueOf(proposerId, tick);
      DecisionPacket existing = ga.decisionPackets().get(packetId);
      if (existing == null) {
        return ToolResult.error(
            "BAD_REQUEST",
            "本 tick 还没有决策包（先 "
                + ProposeCallTool.NAME
                + " 或 "
                + PacketIntentTool.NAME
                + "）: "
                + packetId.value());
      }
      if (!existing.proposerId().equals(proposerId)) {
        return ToolResult.error("FORBIDDEN", "决策包不属于调用者: " + packetId.value());
      }
      // ★★ I15（只许动当前 tick 的包）：expectedRevision 可以指出历史坐标 ⇒ 与 head 的 tick 不一致就拒。
      long currentTick = reads.stateAt(branch, null).meta().timestamp().tick();
      Optional<ToolResult> staleTick =
          PacketDraftSupport.rejectIfNotCurrentTick(packetId.value(), tick, currentTick);
      if (staleTick.isPresent()) {
        return staleTick.get();
      }
      // ★★ I14（已裁决 ⇒ 拒）：唯一判据在 PacketDraftSupport。★ PENDING 放行（隐式 reopen，见类注）。
      Optional<ToolResult> decided = PacketDraftSupport.rejectIfDecided(existing);
      if (decided.isPresent()) {
        return decided.get();
      }
      // ★ 隐式 reopen 的注记：必须在写之前判（落盘后 head 上的包已是 DRAFT）。
      Optional<String> autoWithdrawn = PacketDraftSupport.autoWithdrawNote(existing);
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceGa(identity.decisionPacketKind(), proposerId.value())));
      FormattedCall dropped = null;
      List<FormattedCall> kept = new ArrayList<>(existing.calls().size());
      for (FormattedCall call : existing.calls()) {
        if (call.callIndex() == callIndex && dropped == null) {
          dropped = call;
          continue;
        }
        kept.add(call);
      }
      if (dropped == null) {
        return ToolResult.error(
            "BAD_REQUEST",
            "包里没有 callIndex="
                + callIndex
                + " 的 call（现有 "
                + existing.calls().size()
                + " 条）: "
                + packetId.value());
      }
      // ★ 重排连续（0..n-1）：见类注——不重排就会让"第 N 条"出现两种意思。
      List<FormattedCall> reindexed = new ArrayList<>(kept.size());
      for (int i = 0; i < kept.size(); i++) {
        FormattedCall call = kept.get(i);
        reindexed.add(
            call.callIndex() == i
                ? call
                : new FormattedCall(
                    i,
                    call.toolName(),
                    call.argsJson(),
                    call.targets(),
                    call.previewJson(),
                    call.draftChecks(),
                    call.status(),
                    call.mergedPlanId(),
                    call.outcomeJson()));
      }
      // ★★ 隐式 reopen（I13）：包**以 DRAFT 状态**写回（PENDING 也能删的落法）；一次 revision。
      DecisionPacket droppedPacket = existing.withCalls(reindexed).withStatus(PacketStatus.DRAFT);
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(droppedPacket));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      Map<String, Object> droppedView = new LinkedHashMap<>();
      droppedView.put("callIndex", callIndex);
      droppedView.put("tool", dropped.toolName());
      view.put("dropped", droppedView);
      view.put("callCount", reindexed.size());
      view.put("callIndexes", reindexed.stream().map(FormattedCall::callIndex).toList());
      autoWithdrawn.ifPresent(
          note -> {
            view.put("autoWithdrawn", true);
            view.put("note", note);
          });
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "drop 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}
