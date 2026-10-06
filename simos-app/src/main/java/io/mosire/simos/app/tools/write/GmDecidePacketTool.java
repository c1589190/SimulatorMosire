package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DecisionPacketId;
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
import java.util.UUID;

/**
 * {@code simos.gm.packet.decide}（D2 决策包计划 §4）：GM 对 PENDING 决策包做整包/逐 call 的 true/false 裁决。
 *
 * <p>★ 载荷 {packetId, decision: APPROVE|DENY, note?, callIndexes?}；{@link #MERGE} 返回 {@code
 * BAD_REQUEST("MERGE 留 D3")}。
 *
 * <p>★ **执行者身份**：{@code decidedBy} 从 {@code context.identity()} 派生（GM 面 = {@code external-mcp}），
 * 模型自报不出这个字段。
 *
 * <p>★ 敏感写（{@link ToolGate.Ask}）；GM 侧 sd 命名空间 unlimited ⇒ 写 {@code sd:*} 通过。
 */
public final class GmDecidePacketTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.gm.packet.decide";

  /** D2 不支持的第三档（D3 补合并流程）。 */
  public static final String MERGE = "MERGE";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GmDecidePacketTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 裁决决策包（写）：参数 {packetId(必填), decision: APPROVE|DENY(必填；MERGE 留 D3), note?, "
        + "callIndexes?(可选；APPROVE 时点名批准哪些 call，缺省 = 整包), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。decidedBy 从调用者身份派生。返回 {packetId, decision, decidedBy, "
        + "status(预期), callStatuses, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("packetId", ToolSupport.prop("string", "决策包 id（必填）"));
    props.put("decision", ToolSupport.prop("string", "APPROVE | DENY（MERGE 留 D3，返回 BAD_REQUEST）"));
    props.put("note", ToolSupport.prop("string", "可选裁决注记（进 decisionNote）"));
    Map<String, Object> callIndexesProps =
        ToolSupport.prop("array", "可选：APPROVE 时点名批准的 callIndex 列表（缺省 = 整包批准）");
    callIndexesProps.put("items", ToolSupport.prop("integer", "callIndex"));
    props.put("callIndexes", callIndexesProps);
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSupport.schema(props, List.of("packetId", "decision"));
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
            + args.getOrDefault("callIndexes", "[]"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String packetId = ToolSupport.requiredText(args, "packetId");
      String decision = ToolSupport.requiredText(args, "decision").trim();
      if (MERGE.equals(decision)) {
        return ToolResult.error("BAD_REQUEST", "MERGE 留 D3（D2 只支持 APPROVE|DENY）");
      }
      if (!"APPROVE".equals(decision) && !"DENY".equals(decision)) {
        return ToolResult.error("BAD_REQUEST", "decision 只允许 APPROVE|DENY: " + decision);
      }
      Optional<String> note = Optional.ofNullable(ToolSupport.optionalText(args, "note", null));
      List<Integer> callIndexes = optionalCallIndexes(args);
      String decidedBy = context.identity().instanceId();
      if (decidedBy == null || decidedBy.isBlank()) {
        return ToolResult.error("FORBIDDEN", "调用者身份没有 instanceId，判不出执行者");
      }
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state =
          expectedRevisionArg == null
              ? query.stateAt(QueryService.QueryTarget.head(branch))
              : query.stateAt(
                  QueryService.QueryTarget.at(branch, new RevisionId(expectedRevisionArg)));
      SdState sd = ToolSupport.sdState(state);
      DecisionPacket packet = sd.decisionPackets().get(DecisionPacketId.parse(packetId));
      if (packet == null) {
        return ToolResult.error("NOT_FOUND", "决策包不存在: " + packetId);
      }
      if (packet.status() != PacketStatus.PENDING) {
        return ToolResult.error(
            "BAD_REQUEST", "只有 PENDING 决策包可裁决，当前状态: " + packet.status() + "（" + packetId + "）");
      }
      ToolSupport.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolSupport.SD_NAMESPACE, "*")));
      String commandId = UUID.randomUUID().toString();
      CommandEnvelope envelope =
          new CommandEnvelope(
              commandId,
              commandId,
              initiator,
              branch,
              new RevisionId(state.meta().ref().revision().value()),
              DecideDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.decide(packetId, decision, note, callIndexes, decidedBy));
      CommandResult result = core.submit(envelope);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId);
      view.put("decision", decision);
      view.put("decidedBy", decidedBy);
      view.put("status", expectedStatus(packet, decision, callIndexes).name());
      view.put("callStatuses", callStatuses(packet, decision, callIndexes));
      return DecisionPacketPayloads.fold(result, view, commandId);
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
      DecisionPacket packet, String decision, List<Integer> callIndexes) {
    Set<Integer> selected = new LinkedHashSet<>(callIndexes);
    List<Map<String, Object>> out = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("callIndex", call.callIndex());
      row.put(
          "status",
          "DENY".equals(decision)
              ? "REJECTED"
              : selected.isEmpty() || selected.contains(call.callIndex())
                  ? "APPROVED"
                  : "REJECTED");
      out.add(row);
    }
    return out;
  }
}
