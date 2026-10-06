package io.mosire.simos.app.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策包的工具面视图（D2 决策包计划 §3.2/§4）：{@code simos.sd.packet.my} 与 GM 的 {@code simos.gm.packet} **共用同一份
 * 字段口径**，避免两个读口慢慢漂移（AGENTS §8.3 的"共用同一份视图层"）。
 *
 * <p>★ {@code argsJson}/{@code previewJson} 原始文本保留，同时给解析后的 {@code args}/{@code preview} Map（GM
 * 直接看参数与 预览内容）。
 */
public final class DecisionPacketViews {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private DecisionPacketViews() {}

  /** 单包全量视图（calls 含 args/preview/targets/draftChecks/status）。 */
  public static Map<String, Object> packetView(DecisionPacket packet, SimulationState state) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("branch", packet.branch());
    view.put("tick", packet.tick());
    view.put("proposerId", packet.proposerId().value());
    view.put("status", packet.status().name());
    view.put("intent", packet.intent());
    view.put("createdAtRevision", packet.createdAtRevision());
    packet.decidedBy().ifPresent(value -> view.put("decidedBy", value));
    if (packet.decidedAtRevision().isPresent()) {
      view.put("decidedAtRevision", packet.decidedAtRevision().getAsLong());
    }
    packet.reasonInfoId().ifPresent(value -> view.put("reasonInfoId", value));
    packet.decisionNote().ifPresent(value -> view.put("decisionNote", value));
    view.put("worldRevision", state.meta().ref().revision().value());
    List<Map<String, Object>> calls = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      calls.add(callView(call));
    }
    view.put("calls", calls);
    return view;
  }

  /** 单条 call 的视图。 */
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

  private static Map<String, Object> parseObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("决策包内的 JSON 不是对象: " + e.getMessage(), e);
    }
  }
}
