package io.mosire.simos.army.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
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
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.army.combat}（阶段 D1 / 用户设计 D-012，2026-10-02）：**单条交战记录详情**——记录 id、tick、交战格、参与单位、
 * 自然语言过程、损失。
 *
 * <p>★ <b>形状与清单读口 / GUI 同源</b>：走 {@link ArmyToolViews#armyCombat(CombatRecord)}（与 {@code
 * simos.army.combats} 逐字段同形；"同一资源的两个形状"在结构上不可能）。
 *
 * <p>★ <b>可见性与不存在同款</b>（照 {@code simos.unit.get} 的 T10 口径）：记录不存在**或**所在格不可见 ⇒ 拒因逐字相同的 {@code
 * NOT_FOUND}——不泄露"有这条记录但你看不到"。可见性按记录所在格判（{@link ArmyToolPerspective#hexVisible}， 围栏实现只有 app 的
 * {@code ToolSupport} 一份）。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-army}，对 app/core 的直连换成
 * 构造期注入（{@link ToolReadPort} 读口 + {@link ArmyToolViews} 视图口 + {@link ArmyToolPerspective} 视角口）；
 * 载荷、文案、拒绝码、资源声明逐字不变。
 */
public final class ArmyCombatTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.combat";

  private final ToolReadPort reads;
  private final ArmyToolViews views;
  private final ArmyToolPerspective perspective;
  private final String mapId;

  public ArmyCombatTool(
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
    return "按 id 查单条交战记录详情：{id,kind,tick,hex,participants,text,stages,losses}；"
        + "kind=自定义交战状态（如野战/轰城）；stages=有序阶段（含概率表 outcomes 与 selectedOutcomeId/rollSeed）；"
        + "losses=已判定阶段命中结局的逐单位有符号增量；不可见与不存在同款 NOT_FOUND。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("id", ToolSchema.prop("string", "交战记录 id（如 c-1）"));
    return ToolSchema.schema(props, List.of("id"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      CombatRecordId id = new CombatRecordId(ToolSchema.requiredText(args, "id"));
      SimulationState state = ArmyToolReads.state(reads, args);
      CombatRecord record = ArmyToolReads.armyData(state).combats().get(id);
      GameMap map = ArmyToolReads.gameMap(state);
      if (record == null || !perspective.hexVisible(context, mapId, map, record.hex())) {
        return ToolResult.error("NOT_FOUND", "交战记录不存在: " + id.value());
      }
      return ToolResults.ok(views.armyCombat(record));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
