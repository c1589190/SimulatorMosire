package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.CallStatus;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaEffectExecution;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gm.packet.execute}（D3 契约 §8）：把 APPROVED / PARTIALLY_APPROVED packet 里 {@link
 * CallStatus#APPROVED} 且尚无 outcome 的 call 按稳定序在**一条 revision**里执行。
 *
 * <p>★ 稳定序 = packetId 字符串序 → callIndex 升序；重复 execute 跳过已有 outcome 的 call；一条可执行 call 都没有 ⇒ {@code
 * BAD_REQUEST}。{@code MERGED}/{@code REJECTED}/{@code PENDING} 不执行。
 *
 * <p>★ 执行走 {@link DecisionEffectExecutor}（内部组批 + 同批 outcome 回写 + 一次 submitBatch）；执行后**不** 静默改 call
 * status，只写 {@code outcomeJson}。
 *
 * <p>★ 只在 GM 桶；工具名不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS；资源声明 sd UNRESTRICTED；敏感写。
 */
public final class GmPacketExecuteTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.gm.packet.execute";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolReadPort reads;
  private final String initiator;
  private final GaEffectExecution executor;

  public GmPacketExecuteTool(ToolReadPort reads, String initiator, GaEffectExecution executor) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 执行已批准 call（写）：参数 {packetId? 或 tick?(两者都给时以 packetId 为准；都缺省=当前 tick), "
        + "branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。筛 APPROVED/PARTIALLY_APPROVED packet 中 "
        + "CallStatus.APPROVED 且 outcomeJson 为空的 call，按 packetId 字符串序 → callIndex 升序，经内部执行器"
        + "（内部组批 + 同批 outcome 回写）提交一条 revision；无 call 可执行 ⇒ BAD_REQUEST。返回 {batchId, "
        + "revision?, executed:[{packetId, callIndex, tool, commandFrom, commandTo, result}], submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("packetId", ToolSchema.prop("string", "决策包 id（与 tick 至少给一个；给了就以它为准）"));
    props.put("tick", ToolSchema.prop("integer", "按 tick 选包（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
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

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "执行决策包 call packetId=" + args.get("packetId") + " tick=" + args.get("tick"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      List<DecisionPacket> selected = selectPackets(args, ga, state);
      List<GaEffectExecution.Effect> calls = new ArrayList<>();
      for (DecisionPacket packet : selected) {
        if (packet.status() != PacketStatus.APPROVED
            && packet.status() != PacketStatus.PARTIALLY_APPROVED) {
          continue;
        }
        for (FormattedCall call : packet.calls()) {
          if (call.status() == CallStatus.APPROVED && call.outcomeJson().isEmpty()) {
            calls.add(
                new GaEffectExecution.Effect(
                    packet.id().value(),
                    call.callIndex(),
                    call.toolName(),
                    call.argsJson(),
                    List.of()));
          }
        }
      }
      if (calls.isEmpty()) {
        return ToolResult.error(
            "BAD_REQUEST",
            "没有可执行 call（要求 packet 状态 APPROVED|PARTIALLY_APPROVED、call 状态 APPROVED、outcomeJson 为空）");
      }
      GaEffectExecution.Result result = executor.execute(state, initiator, calls, Optional.empty());
      return toToolResult(result);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "执行决策包失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 稳定序选包：packetId 显式 → 单包；否则按 tick（缺省当前 tick）取全部，再按 packetId 字符串序。 */
  private static List<DecisionPacket> selectPackets(
      Map<String, Object> args, GaState ga, SimulationState state) {
    String packetIdArg = ToolSchema.optionalText(args, "packetId", null);
    if (packetIdArg != null) {
      DecisionPacket packet = ga.decisionPackets().get(DecisionPacketId.parse(packetIdArg));
      if (packet == null) {
        throw new IllegalArgumentException("决策包不存在: " + packetIdArg);
      }
      return List.of(packet);
    }
    Long tickArg = ToolSchema.optionalLong(args, "tick");
    long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }
    List<DecisionPacket> packets = new ArrayList<>();
    for (DecisionPacket packet : ga.decisionPackets().values()) {
      if (packet.tick() == tick) {
        packets.add(packet);
      }
    }
    packets.sort(Comparator.comparing(packet -> packet.id().value()));
    return List.copyOf(packets);
  }

  private static ToolResult toToolResult(GaEffectExecution.Result result) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("batchId", result.batchId());
    if (result.committed()) {
      view.put("revision", result.ref().revision().value());
      List<Map<String, Object>> executed = new ArrayList<>(result.outcomes().size());
      for (GaEffectExecution.Outcome outcome : result.outcomes()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("packetId", outcome.call().packetId());
        row.put("callIndex", outcome.call().callIndex());
        row.put("tool", outcome.call().toolName());
        if (!outcome.empty()) {
          row.put("commandFrom", outcome.commandFrom());
          row.put("commandTo", outcome.commandTo());
        }
        row.put("result", outcome.result());
        executed.add(row);
      }
      view.put("executed", executed);
      view.put(
          "submission",
          ToolResults.committedView(result.ref(), result.batchId(), result.batchId()));
      return ToolResults.ok(view);
    }
    view.put("reason", result.reason());
    Map<String, Object> submission = new LinkedHashMap<>();
    if (result.status() == GaEffectExecution.Status.CONFLICT) {
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(result.ref()));
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    submission.put("result", "rejected");
    submission.put("reason", result.reason());
    submission.put("commandId", result.batchId());
    submission.put("correlationId", result.batchId());
    view.put("submission", submission);
    return ToolResult.error(
        result.failureCode() == null ? "REJECTED" : result.failureCode(), ToolResults.json(view));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
