package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
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
  private final RedactingQueryService redacting;

  public UnitListTool(QueryService query) {
    this.query = query;
    this.redacting = new RedactingQueryService(query);
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("actor", ToolSupport.prop("string", "决策人 id（给出则按该决策人的 viewScope 脱敏；缺省 = GM 全量）"));
    return ToolSupport.schema(props, List.of());
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
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      String actor = ToolSupport.optionalText(args, "actor", null);
      if (actor != null) {
        return ToolSupport.ok(Map.of("units", redacting.units(new DecisionMakerId(actor), target)));
      }
      SimulationState state = query.stateAt(target);
      UnitState units = ToolSupport.unitState(state);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put(
          "units", ToolSupport.units(units, state.meta().timestamp(), ToolSupport.gameMap(state)));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
