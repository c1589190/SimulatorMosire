package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.util.spi.CommandTarget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * D2 决策包两条命令载荷的**唯一拼写点**（propose/intent 写 {@code sd.UpsertDecisionPacket}；GM 写 {@code
 * sd.DecideDecisionPacket}）。
 *
 * <p>★ 工具与 handler 之间的线格式是扁平 JSON（handler 用 {@code SdPayloads} 逐字段读）；把"packet → 载荷"的映射收在一处， 避免
 * propose/intent 各拼一份、加字段时静默丢一个。
 */
final class DecisionPacketPayloads {

  private DecisionPacketPayloads() {}

  /** {@code sd.UpsertDecisionPacket} 的整包载荷（propose 追加 call / intent 改写共用）。 */
  static String upsert(DecisionPacket packet) {
    Objects.requireNonNull(packet, "packet");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packet.id().value());
    payload.put("branch", packet.branch());
    payload.put("tick", packet.tick());
    payload.put("proposerId", packet.proposerId().value());
    payload.put("status", packet.status().name());
    payload.put("intent", packet.intent());
    payload.put("createdAtRevision", packet.createdAtRevision());
    payload.put("decidedBy", packet.decidedBy().orElse(null));
    payload.put(
        "decidedAtRevision",
        packet.decidedAtRevision().isPresent() ? packet.decidedAtRevision().getAsLong() : null);
    payload.put("reasonInfoId", packet.reasonInfoId().orElse(null));
    payload.put("decisionNote", packet.decisionNote().orElse(null));
    List<Map<String, Object>> calls = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      calls.add(callView(call));
    }
    payload.put("calls", calls);
    return ToolSupport.json(payload);
  }

  /** {@code sd.SubmitDecisionPacket} 载荷。 */
  static String submit(String packetId, String proposerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packetId);
    payload.put("proposerId", proposerId);
    return ToolSupport.json(payload);
  }

  /** {@code sd.DecideDecisionPacket} 载荷（{@code callIndexes} 为空 ⇒ 整包口径）。 */
  static String decide(
      String packetId,
      String decision,
      Optional<String> note,
      List<Integer> callIndexes,
      String decidedBy) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", packetId);
    payload.put("decision", decision);
    payload.put("decidedBy", decidedBy);
    if (note.isPresent()) {
      payload.put("note", note.get());
    }
    if (callIndexes != null && !callIndexes.isEmpty()) {
      payload.put("callIndexes", List.copyOf(callIndexes));
    }
    return ToolSupport.json(payload);
  }

  /** 一条 call 的线格式（{@code targets} 逐条 {namespace,path}）。 */
  static Map<String, Object> callView(FormattedCall call) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("callIndex", call.callIndex());
    row.put("toolName", call.toolName());
    row.put("argsJson", call.argsJson());
    List<Map<String, Object>> targets = new ArrayList<>(call.targets().size());
    for (CommandTarget target : call.targets()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("namespace", target.namespace());
      item.put("path", target.path());
      targets.add(item);
    }
    row.put("targets", targets);
    row.put("previewJson", call.previewJson());
    row.put("draftChecks", call.draftChecks());
    row.put("status", call.status().name());
    row.put("mergedPlanId", call.mergedPlanId().orElse(null));
    return row;
  }

  /** 命令三结局的统一折叠（propose/submit/intent/GM decide 共用一份，避免四处各写一遍）。 */
  static ToolResult fold(CommandResult result, Map<String, Object> view, String commandId) {
    return switch (result) {
      case CommandResult.Committed committed -> {
        view.put("submission", ToolSupport.committedView(committed.ref(), commandId, commandId));
        yield ToolSupport.ok(view);
      }
      case CommandResult.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolSupport.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }
}
