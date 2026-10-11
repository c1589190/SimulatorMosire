package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组合工具共用的**"只算不写"视图**（{@code planOnly=true}）：本工具将要落的命令清单。
 *
 * <p>★★ <b>字段集与顺序逐字照抄 app 侧 {@code ToolSupport.plannedCommandsView}</b>（{@code
 * preview=false}、{@code submitted=false}、{@code planOnly=true}、{@code
 * plannedCommands:[{type,payloadJson}…]}）——那是**对外载荷**， 下放不许改一个字节（I-G1）。同款先例：army 的 {@code
 * GmArmyPayPolicyTool}、economy 的 {@code AbstractEconomyGmTool}、 social 的 {@code
 * AbstractHouseholdGmTool} 各自在域内保留了同一形状（它们都注了一句"与 app 侧 ToolSupport.plannedCommandsView 的字段集逐字相同"）。
 *
 * <p>★ <b>为什么 unit 侧收在一个助手、而不是三条工具各写一份</b>：三条组合工具（{@code levyRegion} / {@code raiseUnit} / {@code
 * spawnArmy}）都要它；同一个对外形状写三遍 = 三处可以各自漂移（本仓最贵的一类失效形态）。
 *
 * <p>★ <b>为什么吃 {@link ToolWritePort.Request} 而不是 core 的 {@code CommandEnvelope}</b>：本模块看不见
 * core（enforcer 禁列 + I-G2）；而 {@code Request} 恰是"批里的一条命令"的既有契约形态（{@code type} + 不透明 {@code
 * payloadJson}）， 工具组批用的就是它。
 */
final class UnitBatchViews {

  private UnitBatchViews() {}

  /**
   * {@code planOnly} 视图：{@code {preview:false, submitted:false, planOnly:true,
   * plannedCommands:[{type,payloadJson}…]}}。
   */
  static Map<String, Object> plannedCommandsView(List<ToolWritePort.Request> batch) {
    Objects.requireNonNull(batch, "batch");
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
}
