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
import io.mosire.simos.ga.model.DecisionMaker;
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
 * ★★ {@code simos.ga.packet.revise}（DM 面，本批新建；2026-10-14 GM⇄DM 设计书 §3.2 / 缺口 G3）：<b>改包里的一条
 * call</b>——用户原话「要允许自由覆盖/修改」的结构命令那一半。
 *
 * <pre>{@code
 * {"callIndex":1,"tool":"可选","args":"可选","intent":"可选","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>语义</b>：把包里第 {@code callIndex} 条 call **整条替换**成新的——{@code tool} 缺省 = 沿用原 tool， {@code
 * args} 缺省 = 沿用原 args。{@code intent} 有则**覆盖包级 intent**（整段替换，与 {@code packet.intent} 同义）。
 *
 * <p>★★ <b>必须重跑全部校验</b>：改一条 call 与提一条新 call 在"能不能做"上没有任何区别（否则"先提一条合规的、再改成越界的" 就是一条绕过围栏的路）⇒ 本工具调的是
 * {@link ProposeCallTool#validateCall}，**与 propose 同一段代码，不复制第二份**。 校验失败 / 越界 ⇒ 具名拒 + 包不变（**零
 * revision**）。
 *
 * <p>★★ <b>PENDING 不是修改锁</b>（用户 2026-10-15：「提交多少轮都没问题」；设计书 §3.9.2 / 不变量 I13）：包已确认（PENDING）
 * **照样能改**——改动会**自动把包撤回成 DRAFT**（隐式 reopen，一次 revision，不必 DM 自己先调 {@link ReopenPacketTool}）， 视图里报
 * {@code status=DRAFT} + {@code autoWithdrawn} + 一句"改完记得再 confirm"。
 *
 * <p>★★ <b>真正的边界只有两条</b>（设计书 §3.9.3）：
 *
 * <ol>
 *   <li><b>已裁决 ⇒ 拒</b>（不变量 I14）：{@code decidedBy} 非空 <b>或</b> 状态属四种终态 ⇒ 具名拒（统一判据在 {@link
 *       PacketDraftSupport#rejectIfDecided}）——否则 DM 能把 GM 已批的包改了；
 *   <li><b>非当前 tick ⇒ 拒</b>（不变量 I15）：历史 tick 的包**只读**。
 * </ol>
 *
 * <p>{@code callIndex} 在包里不存在 ⇒ {@code BAD_REQUEST}（本 tick 没有包 ⇒ 同样是 {@code BAD_REQUEST}）。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5）：决策表达零世界效果（不变量 I1）⇒ {@code gate() = ALLOW}、 {@code
 * toolRoles() = {DM}}。★ 身份从 {@code context.identity()} 派生（不采信载荷）。
 */
public final class ReviseCallTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.revise";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ProposeCallTool validator;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param validator ★ <b>与 propose 同一个实例</b>：校验段（catalog → 真 preview → scope）的唯一实现在它里面，
   *     本工具只是第二个调用点（装配面注入同一个对象，不 new 第二份）。★ <b>地图 id 不再另存一份</b>：scope 判据用的 {@code mapId}
   *     就在那个实例里（本类若也存一份，两个值不一致时不会有任何症状）。
   * @param identity 身份口（决策人 id / scope 判据的唯一实现）
   */
  public ReviseCallTool(
      ToolWritePort writes,
      ToolReadPort reads,
      ProposeCallTool validator,
      GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人**改**本 tick 决策包里的一条 call（整条替换）：参数 {callIndex(必填、整数 ≥ 0), "
        + "tool?(缺省 = 沿用原 tool), args?(缺省 = 沿用原 args), intent?(有则覆盖包级 intent), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "★ 改一条与提一条的校验**完全一致**（catalog → 真 preview → 逐目标 scope）：越界 / 校验失败 ⇒ 具名拒、包不变。"
        + "★ 本 tick 内可以提交（"
        + ConfirmPacketTool.NAME
        + "）**很多次**：包已是 PENDING 也**照样能改** —— 本次改动会自动把它撤回成 DRAFT（隐式 reopen，"
        + "视图里报 status=DRAFT 与 autoWithdrawn 注记），改完再 confirm 一次即可，**不必**自己先调 "
        + ReopenPacketTool.NAME
        + "。"
        + "★ 只有**已裁决**（decidedBy 非空或状态 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）才真的改不了 "
        + "⇒ BAD_REQUEST；只许动**当前 tick** 的包（历史 tick 只读）。"
        + "callIndex 不存在 ⇒ BAD_REQUEST。返回 {packetId, status, callCount, callIndex, tool, targets, preview, "
        + "intent?, autoWithdrawn?, note?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "callIndex",
        ToolSchema.prop("integer", "要替换的 call 序号（必填、整数 ≥ 0；先用 " + MyPacketTool.NAME + " 读）"));
    props.put("tool", ToolSchema.prop("string", "新的工具名（缺省 = 沿用原 tool；须在 ProposalCatalog 初始清单内）"));
    props.put("args", ToolSchema.prop("object", "新的工具参数对象（缺省 = 沿用原 args）"));
    props.put("intent", ToolSchema.prop("string", "可选：覆盖包级 intent（整段替换）"));
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
      // ★ 三个可选字段先解析（形状不对 ⇒ 在碰世界之前就 BAD_REQUEST）。
      String newToolArg = ToolSchema.optionalText(args, "tool", null);
      Map<String, Object> newArgsArg =
          args.get("args") == null ? null : ProposeCallTool.optionalMap(args, "args");
      Optional<String> newIntent = ProposeCallTool.optionalNonBlankText(args, "intent");
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      DecisionMaker dm = ga.decisionMakers().get(proposerId);
      if (dm == null) {
        return ToolResult.error("FORBIDDEN", "决策人不存在: " + proposerId.value());
      }
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
      FormattedCall target = callAt(existing, callIndex);
      if (target == null) {
        return ToolResult.error(
            "BAD_REQUEST",
            "包里没有 callIndex="
                + callIndex
                + " 的 call（现有 "
                + existing.calls().size()
                + " 条）: "
                + packetId.value());
      }
      // ★ 缺省 = 沿用原值（tool / args 各自独立缺省）。★ "给了 tool 但空白" 到不了这里：optionalText 已把
      //   空白/非文本拒成 BAD_REQUEST（"若给出必须是非空文本"）⇒ 这里只需判"给没给"。
      String toolName = newToolArg == null ? target.toolName() : newToolArg;
      Map<String, Object> innerArgs =
          newArgsArg == null ? PacketDraftSupport.parseArgsObject(target.argsJson()) : newArgsArg;
      // ★★ 与 propose 同一段校验（不是复制）：catalog → 真 preview → 逐目标 scope。
      ProposeCallTool.ValidatedCall check =
          validator.validateCall(dm, state, toolName, innerArgs, callIndex);
      if (check.error() != null) {
        return check.error();
      }

      List<FormattedCall> calls = new ArrayList<>(existing.calls().size());
      for (FormattedCall call : existing.calls()) {
        calls.add(call.callIndex() == callIndex ? check.call() : call);
      }
      // ★★ 隐式 reopen（I13）：把包**以 DRAFT 状态**写回——这就是"PENDING 也能改"的落法。**一次** revision
      //   （不是"先 reopen 再改"的两次）。★ 非 PENDING 时（本来就是 DRAFT）withStatus 是恒等变换。
      //   ★ 形状说明：三元**必须先收成一个局部变量**再 withStatus（把注释/换行插进三元 ?: 的中间是语法错）。
      DecisionPacket revisedKeepingStatus =
          newIntent.isPresent()
              ? existing.withIntent(newIntent.get()).withCalls(calls)
              : existing.withCalls(calls);
      DecisionPacket revised = revisedKeepingStatus.withStatus(PacketStatus.DRAFT);
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(revised));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      view.put("callCount", calls.size());
      view.put("callIndex", callIndex);
      view.put("tool", check.call().toolName());
      view.put("targets", ProposeCallTool.targetsView(check.call().targets()));
      view.put("preview", check.preview());
      if (newIntent.isPresent()) {
        view.put("intent", newIntent.get());
      }
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
          "TOOL_ERROR", "revise 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 包里该序号的 call；不存在 ⇒ {@code null}。 */
  private static FormattedCall callAt(DecisionPacket packet, int callIndex) {
    for (FormattedCall call : packet.calls()) {
      if (call.callIndex() == callIndex) {
        return call;
      }
    }
    return null;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
