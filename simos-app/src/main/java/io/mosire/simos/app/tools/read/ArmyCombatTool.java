package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.army.combat}（阶段 D1 / 用户设计 D-012，2026-10-02）：**单条交战记录详情**——记录 id、tick、交战格、参与单位、
 * 自然语言过程、损失。
 *
 * <p>★ <b>形状与清单读口 / GUI 同源</b>：走 {@link ApiViews#armyCombat(CombatRecord)}（与 {@code
 * simos.army.combats} 逐字段同形；"同一资源的两个形状"在结构上不可能）。
 *
 * <p>★ <b>可见性与不存在同款</b>（照 {@code simos.unit.get} 的 T10 口径）：记录不存在**或**所在格不可见 ⇒ 拒因逐字相同的 {@code
 * NOT_FOUND}——不泄露"有这条记录但你看不到"。可见性按记录所在格判（{@link ToolSupport#hexVisible}）。
 */
public final class ArmyCombatTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.combat";

  private final QueryService query;
  private final String mapId;

  public ArmyCombatTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "按 id 查单条交战记录详情：{id,kind,tick,hex,participants,text,stages,losses}；"
        + "kind=自定义交战状态（如野战/轰城）；stages=有序阶段（含概率表 outcomes 与 selectedOutcomeId/rollSeed）；"
        + "losses=已判定阶段命中结局的逐单位有符号增量；不可见与不存在同款 NOT_FOUND。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("id", ToolSupport.prop("string", "交战记录 id（如 c-1）"));
    return ToolSupport.schema(props, List.of("id"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      CombatRecordId id = new CombatRecordId(ToolSupport.requiredText(args, "id"));
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      CombatRecord record = ApiViews.armyData(state).combats().get(id);
      GameMap map = ToolSupport.gameMap(state);
      if (record == null || !ToolSupport.hexVisible(context, mapId, map, record.hex())) {
        return ToolResult.error("NOT_FOUND", "交战记录不存在: " + id.value());
      }
      return ToolSupport.ok(ApiViews.armyCombat(record));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
