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
 * <p>★ <b>P4 只读 additive（2026-10-01）</b>：视图层仍在 {@link ToolSupport#unit} → {@code ApiViews.unit} 这一份
 * ——单位带 {@code module}（GovFormation/ArmyFormation）时追加 {@code module}，带 {@code jurisdiction} 时追加
 * {@code jurisdiction}；缺席 ⇒ 键缺席，旧键逐字不变。工具的资源/级别/桶归属一字不动。
 *
 * <p>★ <b>D1 只读 additive（2026-10-02 / D-012）</b>：{@code stateDescriptions}（当前回合状态 → canonical
 * 状态描述地址的 链接表，空表也发）同样只在 {@code ApiViews.unit} 一处加 —— GUI 与 MCP 两面同源。
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
    return "按 id 查单位详情：name/manpower[{type,amount}]/equipment[{type,amount}]/speed/mobilityPerMille/"
        + "parent/position/stateDescriptions；"
        + "若单位带编制/管辖，另含 module（gov: level/superiorGov/staff/policy；army: masterGov/role）与 "
        + "jurisdiction（regions→每周期税率‰、levy*CapPerCommand、administrationPerMille）";
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
      Map<String, Object> args = context.arguments();
      UnitId id = new UnitId(ToolSupport.requiredText(args, "id"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      UnitState units = ToolSupport.unitState(state);
      Unit unit = units.units().get(id);
      // ★ **不可见与不存在同款**（T10）：拒因逐字相同，不泄露"有这个单位但你看不到"。
      if (unit == null || !ToolSupport.unitVisible(context, id)) {
        return ToolResult.error("NOT_FOUND", "单位不存在: " + id.value());
      }
      return ToolSupport.ok(
          ToolSupport.unit(
              unit,
              units,
              state.meta().timestamp(),
              ToolSupport.gameMap(state),
              ToolSupport.sdState(state)));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
