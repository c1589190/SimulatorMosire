package io.mosire.simos.ga.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionItemVerdict;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策包的只读工具面视图（设计书 §8.3 / §5.4）。
 *
 * <p>{@code simos.ga.packet.my} 与 GM 的 {@code simos.gm.packet} 共用同一份字段口径。数据源永远是 {@link
 * DecisionRecord}：{@code DecisionPacket} 只用于展示枚举/字段映射，精确状态由 {@link DecisionRecord#packageState()}
 * 原样输出。
 */
public final class DecisionPacketViews {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private DecisionPacketViews() {}

  /** 单包全量视图（领域投影 + 精确 packageState + verdicts + 逐 call 扩展）。 */
  public static Map<String, Object> packetView(DecisionRecord record, SimulationState state) {
    DecisionPacket packet = record.view();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("branch", packet.branch());
    view.put("tick", packet.tick());
    view.put("proposerId", packet.proposerId().value());
    view.put("status", packet.status().name());
    view.put("intent", packet.intent());
    view.put("createdAtRevision", packet.createdAtRevision());
    record.confirmedAtRevision().ifPresent(value -> view.put("confirmedAtRevision", value));
    if (packet.decidedAtRevision().isPresent()) {
      view.put("decidedAtRevision", packet.decidedAtRevision().getAsLong());
    }
    packet.reasonInfoId().ifPresent(value -> view.put("reasonInfoId", value));
    packet.decidedBy().ifPresent(value -> view.put("decidedBy", value));
    packet.decisionNote().ifPresent(value -> view.put("decisionNote", value));
    view.put("worldRevision", state.meta().ref().revision().value());
    view.put("packageState", record.packageState());
    List<Map<String, Object>> verdicts = new ArrayList<>(record.packageState().verdicts().size());
    for (DecisionItemVerdict verdict : record.packageState().verdicts()) {
      verdicts.add(verdictView(verdict));
    }
    view.put("verdicts", verdicts);
    List<Map<String, Object>> calls = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      calls.add(callView(call));
    }
    view.put("calls", calls);
    return view;
  }

  /** 单条 call 的视图（字段全部来自只读 {@link FormattedCall} 投影）。 */
  public static Map<String, Object> callView(FormattedCall call) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("callIndex", call.callIndex());
    row.put("toolName", call.toolName());
    row.put("argsJson", call.argsJson());
    row.put("args", parseObject(call.argsJson()));
    List<Map<String, Object>> targets = new ArrayList<>(call.targets().size());
    for (CommandTarget target : call.targets()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("namespace", target.namespace());
      item.put("path", target.path());
      targets.add(item);
    }
    row.put("targets", targets);
    row.put("previewJson", call.previewJson());
    row.put("preview", parseObject(call.previewJson()));
    row.put("draftChecks", call.draftChecks());
    row.put("status", call.status().name());
    call.mergedPlanId().ifPresent(value -> row.put("mergedPlanId", value));
    call.outcomeJson().ifPresent(value -> row.put("outcomeJson", value));
    return row;
  }

  private static Map<String, Object> verdictView(DecisionItemVerdict verdict) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("index", verdict.index());
    row.put("approved", verdict.approved());
    row.put("by", verdict.by());
    row.put("note", verdict.note());
    return row;
  }

  private static Map<String, Object> parseObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("决策包内的 JSON 不是对象: " + e.getMessage(), e);
    }
  }
}
