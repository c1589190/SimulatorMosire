package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.army.combats}（阶段 D1 / 用户设计 D-012，2026-10-02）：**交战记录清单**——"当前 tick 在哪发生了交战"的读口。
 *
 * <p>★ <b>过滤</b>：{@code tick?}（可选；只列该世界日的记录，缺省 = 全部——不把"当前 tick"设成隐含缺省，免得读历史时被静默截断）与 {@code
 * q?/r?}（可选；只列该格的记录，两个要一起给）。过滤后按记录 id **字典序**发出（{@code combats} 是插入序表，不排序则响应字节不可复现）。
 *
 * <p>★ <b>形状与 GUI / 详情读口同源</b>：每条走 {@link ApiViews#armyCombat(CombatRecord)}（GUI 与 MCP
 * 共用这一份；本类不另拼字段清单）。
 *
 * <p>★ <b>四桶共享</b>（不标 {@code GmOnlyRead}）：交战记录是世界状态（可回放）——但**可见性按记录所在格判**（{@link
 * ToolSupport#hexVisible}，与 {@code map.hex}/{@code simos.sd.combats} 的地图可见性同口径）：看不见的格上的交战不进结果，
 * 而不是把整调拒掉（T10 的"部分可见"）。★ 逐条判可见性而不是"按 tick 整表发"：D-002 的中央不开天眼同样约束军队记录。
 */
public final class ArmyCombatsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.combats";

  private final QueryService query;
  private final String mapId;

  public ArmyCombatsTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "交战记录清单（可选按 tick / 格过滤）：{combats:[{id,tick,hex,participants,text,losses}]}；"
        + "只发调用者看得见的格上的记录；按 id 字典序。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("tick", ToolSupport.prop("integer", "只列该世界日的交战（可选；缺省 = 全部，含历史）"));
    props.put("q", ToolSupport.prop("integer", "只列该格的交战：六角列坐标 q（可选，须与 r 同时给）"));
    props.put("r", ToolSupport.prop("integer", "只列该格的交战：六角行坐标 r（可选，须与 q 同时给）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Long tick = ToolSupport.optionalLong(args, "tick");
      HexCoord hex = optionalHex(args);
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      ArmyData data = ApiViews.armyData(state);
      GameMap map = ToolSupport.gameMap(state);
      List<CombatRecordId> ids = new ArrayList<>(data.combats().keySet());
      ids.sort(Comparator.comparing(CombatRecordId::value));
      List<Map<String, Object>> combats = new ArrayList<>();
      for (CombatRecordId id : ids) {
        CombatRecord record = data.combats().get(id);
        if (tick != null && record.tick() != tick) {
          continue;
        }
        if (hex != null && !record.hex().equals(hex)) {
          continue;
        }
        if (!ToolSupport.hexVisible(context, mapId, map, record.hex())) {
          continue; // ★ 不可见的格：不进结果（不是整调拒）
        }
        combats.add(ApiViews.armyCombat(record));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("combats", combats);
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** 可选格过滤：q/r 给了一个就必须给另一个（半给 ⇒ BAD_REQUEST，不静默当"没给"）。 */
  private static HexCoord optionalHex(Map<String, Object> args) {
    boolean hasQ = ToolSupport.has(args, "q");
    boolean hasR = ToolSupport.has(args, "r");
    if (!hasQ && !hasR) {
      return null;
    }
    if (hasQ != hasR) {
      throw new IllegalArgumentException("格过滤必须 q 与 r 同时给（当前 q=" + hasQ + ", r=" + hasR + "）");
    }
    return new HexCoord(
        (int) ToolSupport.requiredLong(args, "q"), (int) ToolSupport.requiredLong(args, "r"));
  }
}
