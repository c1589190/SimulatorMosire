package io.mosire.simos.gov.tools.write;

import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政府组合工具的**批视图助手**：{@code planOnly} 视图、整批拒的逐条拒因、冲突节点。
 *
 * <p>★ <b>为什么收在一处</b>：迁入前这三段在 11 个工具里各抄一份（{@code ToolSupport.plannedCommandsView} + 每类的整批拒循环），
 * 字段名/顺序一致纯靠人眼。收成一处后字段集仍**逐字照抄**原实现（{@code preview/submitted/planOnly/plannedCommands}、 {@code
 * result/commandId/correlationId/current/commands[{type,reason}]}），但只有一处会漂移。
 *
 * <p>★ 拒因文本的来源换了类型：{@code CommandResult.Rejected.reason()} → {@link
 * ToolWritePort.Outcome.Rejected#reason()}（写口适配器逐字转交 core 的拒因）；非拒结局的兜底文本是 {@code
 * outcome.toString()} —— 两种 record 的**简单名与组件名相同**（{@code Committed[ref=…]} / {@code
 * Conflict[current=…]}）， 故文本逐字不变。
 */
public final class GovBatchViews {

  private GovBatchViews() {}

  /**
   * D3 执行器专用的**只规划不提交**结果视图（逐条命令的 {@code type}/{@code payloadJson}/顺序，不含提交结论）。
   *
   * <p>★★ <b>绝不进 jsonSchema / 工具描述</b>：{@code planOnly} 是内部执行器参数，只有 app 的 {@code
   * DecisionEffectExecutor} 用系统上下文注入；对模型暴露它等于给出绕过预览直接组批的入口。
   */
  public static Map<String, Object> planOnly(List<ToolWritePort.Request> batch) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", false);
    view.put("submitted", false);
    view.put("planOnly", true);
    List<Map<String, Object>> commands = new ArrayList<>(batch.size());
    for (ToolWritePort.Request request : batch) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", request.type());
      row.put("payloadJson", request.payloadJson());
      commands.add(row);
    }
    view.put("plannedCommands", commands);
    return view;
  }

  /** 整批拒的 {@code submission} 节点（逐条列真拒因，不吞成一句"提交失败"）。 */
  public static Map<String, Object> rejectedSubmission(
      String batchId, List<ToolWritePort.Request> batch, List<ToolWritePort.Outcome> outcomes) {
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>(outcomes.size());
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      ToolWritePort.Outcome outcome = outcomes.get(i);
      row.put(
          "reason",
          outcome instanceof ToolWritePort.Outcome.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf）。
    submission.put("commands", Collections.unmodifiableList(rows));
    return submission;
  }

  /** 整批冲突的 {@code submission} 节点（{@code current} = 真实 head）。 */
  public static Map<String, Object> conflictSubmission(String batchId, StateRef current) {
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "conflict");
    submission.put("current", ToolResults.stateRef(current));
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    return submission;
  }
}
