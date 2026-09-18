package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.command.catalog}（spec §7.1 读工具）：列出**已注册命令类型**及其载荷字段提示。
 *
 * <p>★ **判据②（R5）的载体**：清单与 {@code Shell} 实际注册的 handler **同源**（构造期注入），故 catalog 列出的每个 type 都能经 {@code
 * simos.command.submit} 到达。工具面不另立一份"支持的类型"表。
 *
 * <p>★ **不给 Core 加新面**（spec §7.1 原文）：Core 不暴露 {@code CommandRegistry.types()}，清单由 app 持有。
 */
public final class CatalogTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.command.catalog";

  /** 每个已注册 type 的载荷字段提示（spec §四表；仅给人/模型看，不参与执行）。 */
  private static final Map<String, String> PAYLOAD_HINTS =
      Map.of(
          "unit.RenameUnit", "id, name",
          "unit.CreateUnit",
              "id, name, position{q,r}, member, equipment, speed, mobilityPerMille, parent?",
          "unit.ReparentUnit", "id, parent?（null=清根）",
          "unit.SetStrength", "id, member, equipment",
          "unit.PlaceAt", "id, hex{q,r}?（null=撤销位置）",
          "unit.PlanRoute", "id, waypoints[{q,r}...]",
          "unit.CancelRoute", "id",
          "unit.DisbandUnit", "id");

  private final List<String> types;

  /**
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源）；本类只读它
   */
  public CatalogTool(Set<String> commandTypes) {
    List<String> sorted = new ArrayList<>(commandTypes);
    Collections.sort(sorted);
    this.types = List.copyOf(sorted.subList(0, sorted.size() - 1));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "列出本世界已注册的全部命令类型及其载荷字段提示（simos.command.submit 的 type/payloadJson 依据）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("types", types);
    Map<String, Object> hints = new LinkedHashMap<>();
    for (String type : types) {
      hints.put(type, PAYLOAD_HINTS.getOrDefault(type, ""));
    }
    view.put("payloadHints", hints);
    return ToolSupport.ok(view);
  }
}
