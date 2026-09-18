package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.unit.get}（spec §7.1 读工具）：单个单位详情（冻结字段 + 有效位置 + parent）。
 *
 * <p>查无此人 ⇒ {@code NOT_FOUND}（不是静默空对象）。
 */
public final class UnitGetTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.unit.get";

  private final QueryService query;

  public UnitGetTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "按 id 查单位详情：name/member/equipment/speed/mobilityPerMille/parent/position";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("id", ToolSupport.prop("string", "单位 id（如 u-1）"));
    return ToolSupport.schema(props, List.of("id"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.UNIT_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireUnitRead(context);
      Map<String, Object> args = context.arguments();
      UnitId id = new UnitId(ToolSupport.requiredText(args, "id"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      UnitState units = ToolSupport.unitState(state);
      Unit unit = units.units().get(id);
      if (unit == null) {
        return ToolResult.error("NOT_FOUND", "单位不存在: " + id.value());
      }
      return ToolSupport.ok(ToolSupport.unit(unit, units, state.meta().timestamp()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
