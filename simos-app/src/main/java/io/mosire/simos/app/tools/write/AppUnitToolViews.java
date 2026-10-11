package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link UnitToolViews} 的宿主实现（组合根）：**逐条转调同一份既有视图函数**——形状一个字节都不在这里重写。
 *
 * <p>★★ <b>为什么必须转调而不是重写</b>：这些形状（{@code {q,r}} 坐标、人力/装备表、工具入参→装备表、单位详情/清单）今天由 GUI 与 MCP
 * **共用**（{@code ApiViews.unit} / {@code ToolSupport.hexCoord}/{@code compositionView} 的类注都点明"两处各写一份
 * = 加字段时没人提醒"）⇒ 工具下放到 {@code simos-unit} 后形状仍只有一份实现，输出因此逐字节不变（I-G1）。
 *
 * <p>★ <b>为什么这个适配器住在 {@code app.tools.write}</b>：与 {@code AppArmyToolViews} 同一条既有先例（B3 第二域台账
 * D4）——凡要转调 {@code app.tools.write} 里**包内可见**的助手（本包还有 {@code RegionAllocations} / {@code
 * HouseholdManpowerAllocator} / {@code GovSelectExamineesPlan.residenceAt} 三处委托人）的适配器，就放这个包；
 * 不为一次下放把既有类的可见性放宽。
 *
 * <p>★ <b>历法服务由本类持有</b>：单位视图要它算在途移动的 {@code departedAt} 日期（C6a），而 {@code simos-calendar} 不在 unit
 * 的允许面里 ⇒ 时钟留在组合根（与 {@code AppSocialToolViews} 同款）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个入参进去，同一个视图对象/同一段字节出来。
 */
public final class AppUnitToolViews implements UnitToolViews {

  private final CalendarService calendarService;

  public AppUnitToolViews(CalendarService calendarService) {
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  @Override
  public Map<String, Object> hexCoord(HexCoord coord) {
    return ToolSupport.hexCoord(coord);
  }

  @Override
  public List<Map<String, Object>> compositionView(List<CompositionEntry> entries) {
    return ToolSupport.compositionView(entries);
  }

  @Override
  public List<CompositionEntry> compositionEntries(Map<String, Integer> counts) {
    return ToolSupport.compositionEntries(counts);
  }

  /**
   * 转调 {@code ToolSupport.unit(unit, units, at, map, sd, social, calendars)}——五个派生量（时刻 / map 切片 /
   * sd 切片 / social 切片 / 历法）都从**同一次读数**的 {@code state} 取，与迁入前逐字同源。
   */
  @Override
  public Map<String, Object> unit(Unit unit, UnitState units, SimulationState state) {
    return ToolSupport.unit(
        unit,
        units,
        state.meta().timestamp(),
        ToolSupport.gameMap(state),
        ToolSupport.sdState(state),
        ToolSupport.socialData(state),
        calendarService);
  }

  /**
   * 转调 {@code ToolSupport.units(..., Predicate<UnitId>, ...)}：**逐单位可见性谓词仍是 app 的 {@code
   * ToolSupport.unitVisible} 这一份实现**（围栏不抄第二份），unit 侧只把 {@code context} 原样交过来。
   */
  @Override
  public List<Map<String, Object>> units(
      UnitState units, ToolContext context, SimulationState state) {
    return ToolSupport.units(
        units,
        state.meta().timestamp(),
        ToolSupport.gameMap(state),
        ToolSupport.sdState(state),
        unitId -> ToolSupport.unitVisible(context, unitId),
        ToolSupport.socialData(state),
        calendarService);
  }
}
