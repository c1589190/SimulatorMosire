package io.mosire.simos.app.tools.read;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
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
 *
 * <p>★ **部分可见**（T10）：逐单位按调用者现算的范围筛，越界的不进结果。
 */
public final class UnitListTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.unit.list";

  private final QueryService query;
  private final CalendarService calendarService;

  /** 旧构造器（测试/旧路径）：历法走 {@link CalendarService#defaults()}。 */
  public UnitListTool(QueryService query) {
    this(query, CalendarService.defaults());
  }

  // ★ CalendarService 是共享只读协作者（只调读侧方法），与 CalendarInfoTool 同口径豁免 EI_EXPOSE_REP2。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CalendarService 是共享只读协作者（只调 dateOfTick/seasonAt 等读侧方法），非内部表示外泄")
  public UnitListTool(QueryService query, CalendarService calendarService) {
    this.query = query;
    this.calendarService = calendarService;
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
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.UNIT_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      UnitState units = ToolSupport.unitState(state);
      Map<String, Object> view = new LinkedHashMap<>();
      // ★ **逐单位按调用者现算的范围筛**（T10）：越界的单位不进结果（与 `unit.get` 的 NOT_FOUND 同一口径）。
      view.put(
          "units",
          ToolSupport.units(
              units,
              state.meta().timestamp(),
              ToolSupport.gameMap(state),
              ToolSupport.sdState(state),
              unitId -> ToolSupport.unitVisible(context, unitId),
              calendarService));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
