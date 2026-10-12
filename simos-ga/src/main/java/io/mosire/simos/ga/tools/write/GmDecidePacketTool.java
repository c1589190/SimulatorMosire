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
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.spi.DecideDecisionPacketHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gm.packet.decide}（D2 决策包计划 §4）：GM 对 PENDING 决策包做整包/逐 call 的 true/false 裁决。
 *
 * <p>★ 载荷 {packetId, decision: APPROVE|DENY|MERGE, note?, callIndexes?, mergedPlanId?}；{@code
 * MERGE} 必带 {@code mergedPlanId}（目标 plan 必须已存在，先 upsert 再 decide），把包/当前 PENDING call 标 MERGED。
 *
 * <p>★ **执行者身份**：{@code decidedBy} 从 {@code context.identity()} 派生（GM 面 = {@code external-mcp}），
 * 模型自报不出这个字段。
 *
 * <p>★ 敏感写（{@link ToolGate.Ask}）；GM 侧 sd 命名空间 unlimited ⇒ 写 {@code sd:*} 通过。
 */
public final class GmDecidePacketTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.gm.packet.decide";

  /** 合并裁决档（D3）：把本包当前 PENDING call 并入指定合并计划。 */
  public static final String MERGE = "MERGE";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   */
  public GmDecidePacketTool(ToolWritePort writes, ToolReadPort reads) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 裁决决策包（写）：参数 {packetId(必填), decision: APPROVE|DENY|MERGE(必填), note?, "
        + "callIndexes?(可选；APPROVE 时点名批准哪些 call，缺省 = 整包；与 MERGE 互斥), "
        + "mergedPlanId?(decision=MERGE 时必填；目标 plan 必须已由 simos.gm.mergedPlan.upsert 建好), "
        + "branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。decidedBy 从调用者身份派生。返回 {packetId, decision, decidedBy, "
        + "status(预期), callStatuses, mergedPlanId?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("packetId", ToolSchema.prop("string", "决策包 id（必填）"));
    props.put("decision", ToolSchema.prop("string", "APPROVE | DENY | MERGE"));
    props.put("note", ToolSchema.prop("string", "可选裁决注记（进 decisionNote）"));
    Map<String, Object> callIndexesProps =
        ToolSchema.prop("array", "可选：APPROVE 时点名批准的 callIndex 列表（缺省 = 整包批准；与 MERGE 互斥）");
    callIndexesProps.put("items", ToolSchema.prop("integer", "callIndex"));
    props.put("callIndexes", callIndexesProps);
    props.put("mergedPlanId", ToolSchema.prop("string", "decision=MERGE 时必填：目标合并效果计划 id"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("packetId", "decision"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "裁决决策包 packetId="
            + args.get("packetId")
            + " decision="
            + args.get("decision")
            + " callIndexes="
            + args.getOrDefault("callIndexes", "[]")
            + " mergedPlanId="
            + args.get("mergedPlanId"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String packetId = ToolSchema.requiredText(args, "packetId");
      String decision = ToolSchema.requiredText(args, "decision").trim();
      if (!"APPROVE".equals(decision) && !"DENY".equals(decision) && !MERGE.equals(decision)) {
        return ToolResult.error("BAD_REQUEST", "decision 只允许 APPROVE|DENY|MERGE: " + decision);
      }
      Optional<String> note = Optional.ofNullable(ToolSchema.optionalText(args, "note", null));
      List<Integer> callIndexes = optionalCallIndexes(args);
      Optional<String> mergedPlanIdArg =
          Optional.ofNullable(ToolSchema.optionalText(args, "mergedPlanId", null));
      if (MERGE.equals(decision)) {
        if (!callIndexes.isEmpty()) {
          return ToolResult.error("BAD_REQUEST", "MERGE 与 callIndexes 互斥：MERGE 是整包并入合并计划");
        }
        if (mergedPlanIdArg.isEmpty()) {
          return ToolResult.error("BAD_REQUEST", "decision=MERGE 时必须给 mergedPlanId");
        }
      } else if (mergedPlanIdArg.isPresent()) {
        return ToolResult.error("BAD_REQUEST", "mergedPlanId 只在 decision=MERGE 时有意义");
      }
      String decidedBy = context.identity().instanceId();
      if (decidedBy == null || decidedBy.isBlank()) {
        return ToolResult.error("FORBIDDEN", "调用者身份没有 instanceId，判不出执行者");
      }
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      SdState sd = GaToolReads.sd(state);
      DecisionPacket packet = sd.decisionPackets().get(DecisionPacketId.parse(packetId));
      if (packet == null) {
        return ToolResult.error("NOT_FOUND", "决策包不存在: " + packetId);
      }
      if (packet.status() != PacketStatus.PENDING) {
        return ToolResult.error(
            "BAD_REQUEST", "只有 PENDING 决策包可裁决，当前状态: " + packet.status() + "（" + packetId + "）");
      }
      if (MERGE.equals(decision)
          && !sd.mergedEffectPlans()
              .containsKey(MergedEffectPlanId.parse(mergedPlanIdArg.orElseThrow()))) {
        return ToolResult.error("NOT_FOUND", "合并计划不存在: " + mergedPlanIdArg.orElseThrow());
      }
      ToolResources.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolResources.SD_NAMESPACE, "*")));
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              DecideDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.decide(
                  packetId, decision, note, callIndexes, decidedBy, mergedPlanIdArg));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId);
      view.put("decision", decision);
      view.put("decidedBy", decidedBy);
      view.put("status", expectedStatus(packet, decision, callIndexes).name());
      view.put(
          "callStatuses",
          callStatuses(packet, decision, callIndexes, mergedPlanIdArg.orElse(null)));
      mergedPlanIdArg.ifPresent(value -> view.put("mergedPlanId", value));
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "裁决失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static List<Integer> optionalCallIndexes(Map<String, Object> args) {
    Object raw = args.get("callIndexes");
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 callIndexes 若给出必须是整数数组");
    }
    List<Integer> out = new ArrayList<>(list.size());
    for (Object element : list) {
      if (!(element instanceof Number number)
          || (number instanceof Double || number instanceof Float)) {
        throw new IllegalArgumentException("参数 callIndexes 的元素必须是整数: " + element);
      }
      long value = number.longValue();
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("参数 callIndexes 的元素超出 int 范围: " + element);
      }
      out.add((int) value);
    }
    return List.copyOf(out);
  }

  private static PacketStatus expectedStatus(
      DecisionPacket packet, String decision, List<Integer> callIndexes) {
    if ("DENY".equals(decision)) {
      return PacketStatus.REJECTED;
    }
    if (MERGE.equals(decision)) {
      return PacketStatus.MERGED;
    }
    Set<Integer> selected = new LinkedHashSet<>(callIndexes);
    if (selected.isEmpty()) {
      return PacketStatus.APPROVED;
    }
    for (FormattedCall call : packet.calls()) {
      if (!selected.contains(call.callIndex())) {
        return PacketStatus.PARTIALLY_APPROVED;
      }
    }
    return PacketStatus.APPROVED;
  }

  private static List<Map<String, Object>> callStatuses(
      DecisionPacket packet, String decision, List<Integer> callIndexes, String mergedPlanId) {
    Set<Integer> selected = new LinkedHashSet<>(callIndexes);
    List<Map<String, Object>> out = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("callIndex", call.callIndex());
      if ("DENY".equals(decision)) {
        row.put("status", "REJECTED");
      } else if (MERGE.equals(decision)) {
        boolean merged = call.status() == CallStatus.PENDING;
        row.put("status", merged ? "MERGED" : call.status().name());
        if (merged) {
          row.put("mergedPlanId", mergedPlanId);
        }
      } else {
        row.put(
            "status",
            selected.isEmpty() || selected.contains(call.callIndex()) ? "APPROVED" : "REJECTED");
      }
      out.add(row);
    }
    return out;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
