package io.mosire.simos.army.stop;

import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.util.spi.StopReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ <b>原因系统的读口形状（单一拼写点）</b>：{@link StopReport} ⇒ 工具结果视图。
 *
 * <p>两个口共用它：{@code simos.army.stopReport}（GM 事后查）与 {@code simos.emergency.advance}（推进当次就返回原因）——
 * 两处各拼一份字段名就会漂移（本仓 I-G1 防的正是这种"看着一样、字段名各说各话"）。
 *
 * <pre>
 * {atTick, worldRef:{branch,revision}, reasons:[{namespace,kind,payload,logRefs[]}]}
 * </pre>
 *
 * <p>★ 形状逐字取自约束设计书 §4.3.1 / §7.2（GM 要能 replay 到"停下的那一刻"，并顺着 {@code logRefs} 跳到那天的日志）。
 */
public final class StopReportViews {

  private StopReportViews() {}

  /** 完整视图（{@code atTick} + 坐标 + 聚合的全部原因）。 */
  public static Map<String, Object> view(StopReport report) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("atTick", report.atTick());
    view.put("worldRef", ToolResults.stateRef(report.worldRef()));
    view.put("reasons", reasons(report));
    return view;
  }

  /** 聚合的原因清单（**一条 {@code Incident} 一条原因**：不截断、不去重 —— 只报第一个会让 GM 看到片面信息）。 */
  public static List<Map<String, Object>> reasons(StopReport report) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (StopReport.Reason reason : report.reasons()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("namespace", reason.namespace());
      row.put("kind", reason.kind());
      row.put("payload", reason.payload());
      row.put("logRefs", reason.logRefs());
      rows.add(row);
    }
    return rows;
  }
}
