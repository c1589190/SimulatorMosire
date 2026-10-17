package io.mosire.simos.unit.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.tools.UnitToolPerspective;
import io.mosire.simos.unit.tools.UnitToolReads;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.unit.get}（spec §7.1 读工具）：单个单位详情（冻结字段 + 有效位置 + parent）。
 *
 * <p>★ <b>P4 只读 additive（2026-10-01）</b>：视图层仍在 {@link UnitToolViews#unit} → {@code ApiViews.unit}
 * 这一份 ——单位带 {@code module}（GovernmentFormation/ArmyFormation）时追加 {@code module}，带 {@code
 * jurisdiction} 时追加 {@code jurisdiction}；缺席 ⇒ 键缺席，旧键逐字不变。工具的资源/级别/桶归属一字不动。
 *
 * <p>★ <b>D1 只读 additive（2026-10-02 / D-012）</b>：{@code stateDescriptions}（当前回合状态 → canonical
 * 状态描述地址的 链接表，空表也发）同样只在 {@code ApiViews.unit} 一处加 —— GUI 与 MCP 两面同源。
 *
 * <p>查无此人 ⇒ {@code NOT_FOUND}（不是静默空对象）。
 *
 * <p>★★ <b>2026-10-12 B3 第五域下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-unit} （{@code
 * unit.tools.read}），对 {@code QueryService} / {@code CalendarService} / {@code ToolSupport} 的直连换成
 * 构造期注入（窄读口 + 视图口 + 视角口）；工具名、描述、schema、资源声明、拒绝码与拒因文案逐字不变。
 */
public final class UnitGetTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.unit.get";

  private final ToolReadPort reads;
  private final UnitToolViews views;
  private final UnitToolPerspective perspective;

  /**
   * @param reads 窄读口（{@code QueryService.stateAt} 的方法面；head 解析/创世等待/读缓存都在宿主实现里）
   * @param views 视图口（{@code ApiViews.unit} 那一份；历法服务由宿主实现持有）
   * @param perspective 视角口（可见性围栏的唯一实现）
   */
  public UnitGetTool(ToolReadPort reads, UnitToolViews views, UnitToolPerspective perspective) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
    this.perspective = Objects.requireNonNull(perspective, "perspective");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "按 id 查单位详情：name/equipment[{type,amount}]/speed/mobilityPerMille/"
        + "parent/position/stateDescriptions/households[{id,name?,location?,memberLots?,population?}]/population（实时人口，"
        + "从 Social 家户汇总现算；S3b 起 manpower 已退役）；"
        + "若单位带编制/管辖，另含 module（gov: level/superiorGov/householdPosts/staff/policy；"
        + "army: masterGov/role/householdDuties；★ 编制里没有 households——谁在这个 Unit 里只看 unit 视图的 "
        + "households[]，GOV 单位必含政府家户 hh-gov-<unitId>）与 "
        + "jurisdiction（regions→每周期税率‰、levy*CapPerCommand、administrationPerMille）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("id", ToolSchema.prop("string", "单位 id（如 u-1）"));
    return ToolSchema.schema(props, List.of("id"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.UNIT_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      UnitId id = new UnitId(ToolSchema.requiredText(args, "id"));
      SimulationState state = UnitToolReads.state(reads, args);
      UnitState units = UnitToolReads.unitState(state);
      Unit unit = units.units().get(id);
      // ★ **不可见与不存在同款**（T10）：拒因逐字相同，不泄露"有这个单位但你看不到"。
      if (unit == null || !perspective.unitVisible(context, id)) {
        return ToolResult.error("NOT_FOUND", "单位不存在: " + id.value());
      }
      return ToolResults.ok(views.unit(unit, units, state));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
