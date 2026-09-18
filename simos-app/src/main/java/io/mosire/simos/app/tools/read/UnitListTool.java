package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.unit.list}（spec §7.1 读工具）：单位列表（每个带 head 时刻的有效位置）。
 *
 * <p>位置走 {@link UnitState#effectivePosition}（向父取），与 facet / GUI 同口径。
 */
public final class UnitListTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.unit.list";

  private final QueryService query;

  public UnitListTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "列出全部单位（含 head 时刻的有效位置 position / parent）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(ToolSupport.targetProps(), List.of());
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
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      UnitState units = ToolSupport.unitState(state);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("units", ToolSupport.units(units, state.meta().timestamp()));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
