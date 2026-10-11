package io.mosire.simos.unit.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.tools.UnitToolReads;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code simos.unit.list}（spec §7.1 读工具）：单位列表（每个带 head 时刻的有效位置）。
 *
 * <p>位置走 {@code UnitState#effectivePosition}（向父取），与 facet / GUI 同口径。
 *
 * <p>★ **部分可见**（T10）：逐单位按调用者现算的范围筛，越界的不进结果。
 *
 * <p>★★ <b>2026-10-12 B3 第五域下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-unit} （{@code
 * unit.tools.read}），对 {@code QueryService} / {@code CalendarService} / {@code ToolSupport} 的直连换成
 * 构造期注入（窄读口 + 视图口）；工具名、描述、schema、资源声明、可见性口径逐字不变——**过滤谓词仍只有一处实现** （app 的 {@code
 * ToolSupport.unitVisible}，本类只把 {@code context} 原样交给视图口）。
 */
public final class UnitListTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.unit.list";

  private final ToolReadPort reads;
  private final UnitToolViews views;

  /**
   * @param reads 窄读口（{@code QueryService.stateAt} 的方法面）
   * @param views 视图口（清单视图与逐单位可见性筛都由它一处实现）
   */
  public UnitListTool(ToolReadPort reads, UnitToolViews views) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.UNIT_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      SimulationState state = UnitToolReads.state(reads, context.arguments());
      UnitState units = UnitToolReads.unitState(state);
      Map<String, Object> view = new LinkedHashMap<>();
      // ★ **逐单位按调用者现算的范围筛**（T10）：越界的单位不进结果（与 `unit.get` 的 NOT_FOUND 同一口径）。
      view.put("units", views.units(units, context, state));
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
