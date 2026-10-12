package io.mosire.simos.army.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.tools.ArmyToolPerspective;
import io.mosire.simos.army.tools.ArmyToolReads;
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.army.combats}（阶段 D1 / 用户设计 D-012，2026-10-02）：**交战记录清单**——"当前 tick 在哪发生了交战"的读口。
 *
 * <p>★ <b>过滤</b>：{@code tick?}（可选；只列该世界日的记录，缺省 = 全部——不把"当前 tick"设成隐含缺省，免得读历史时被静默截断）与 {@code
 * q?/r?}（可选；只列该格的记录，两个要一起给）。过滤后按记录 id **字典序**发出（{@code combats} 是插入序表，不排序则响应字节不可复现）。
 *
 * <p>★ <b>形状与 GUI / 详情读口同源</b>：每条走 {@link ArmyToolViews#armyCombat(CombatRecord)}（GUI 与 MCP
 * 共用这一份；本类不另拼字段清单）。
 *
 * <p>★ <b>四桶共享</b>（不标 {@code GmOnlyRead}）：交战记录是世界状态（可回放）——但**可见性按记录所在格判**（{@link
 * ArmyToolPerspective#hexVisible}，与 {@code map.hex}/{@code simos.sd.combats}
 * 的地图可见性同口径）：看不见的格上的交战不进结果， 而不是把整调拒掉（T10 的"部分可见"）。★ 逐条判可见性而不是"按 tick 整表发"：D-002 的中央不开天眼同样约束军队记录。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-army}，对 app/core 的直连换成
 * 构造期注入；载荷、文案、拒绝码、资源声明逐字不变。
 */
public final class ArmyCombatsTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.combats";

  private final ToolReadPort reads;
  private final ArmyToolViews views;
  private final ArmyToolPerspective perspective;
  private final String mapId;

  public ArmyCombatsTool(
      ToolReadPort reads, ArmyToolViews views, ArmyToolPerspective perspective, String mapId) {
    this.reads = reads;
    this.views = views;
    this.perspective = perspective;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "交战记录清单（可选按 tick / 格过滤）：{combats:[{id,kind,tick,hex,participants,text,stages,losses}]}；"
        + "kind=自定义交战状态（如野战/轰城）；stages=有序阶段（含概率表 outcomes 与 selectedOutcomeId/rollSeed）；"
        + "losses=已判定阶段命中结局的逐单位有符号增量；只发调用者看得见的格上的记录；按 id 字典序。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("tick", ToolSchema.prop("integer", "只列该世界日的交战（可选；缺省 = 全部，含历史）"));
    props.put("q", ToolSchema.prop("integer", "只列该格的交战：六角列坐标 q（可选，须与 r 同时给）"));
    props.put("r", ToolSchema.prop("integer", "只列该格的交战：六角行坐标 r（可选，须与 q 同时给）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Long tick = ToolSchema.optionalLong(args, "tick");
      HexCoord hex = optionalHex(args);
      SimulationState state = ArmyToolReads.state(reads, args);
      ArmyData data = ArmyToolReads.armyData(state);
      GameMap map = ArmyToolReads.gameMap(state);
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
        if (!perspective.hexVisible(context, mapId, map, record.hex())) {
          continue; // ★ 不可见的格：不进结果（不是整调拒）
        }
        combats.add(views.armyCombat(record));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("combats", combats);
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** 可选格过滤：q/r 给了一个就必须给另一个（半给 ⇒ BAD_REQUEST，不静默当"没给"）。 */
  private static HexCoord optionalHex(Map<String, Object> args) {
    boolean hasQ = ToolSchema.has(args, "q");
    boolean hasR = ToolSchema.has(args, "r");
    if (!hasQ && !hasR) {
      return null;
    }
    if (hasQ != hasR) {
      throw new IllegalArgumentException("格过滤必须 q 与 r 同时给（当前 q=" + hasQ + ", r=" + hasR + "）");
    }
    return new HexCoord(
        (int) ToolSchema.requiredLong(args, "q"), (int) ToolSchema.requiredLong(args, "r"));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
