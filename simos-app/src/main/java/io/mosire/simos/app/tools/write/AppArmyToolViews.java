package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.tools.ArmyToolViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import java.util.List;
import java.util.Map;

/**
 * {@link ArmyToolViews} 的宿主实现（组合根）：**逐条转调同一份既有视图函数**——形状一个字节都不在这里重写。
 *
 * <p>★★ <b>为什么必须转调而不是重写</b>：这些形状（交战记录视图、{@code {q,r}} 坐标、人力/装备表、单位 canonical 地址）今天由 GUI 与 MCP
 * **共用**（{@code ApiViews.armyCombat} / {@code ToolSupport.hexCoord} 的类注都点明"两处各写一份 = 加字段时没人提醒"） ⇒
 * 工具下放到 {@code simos-army} 后形状仍只有一份实现，输出因此逐字节不变（I-G1）。
 *
 * <p>★★ <b>为什么这个适配器住在 {@code app.tools.write} 而不是 {@code app.tools.army}</b>：它要转调的 {@link
 * RaiseUnitPlan#unitAddress(String)} 是**包内可见**的（本仓那种"单位 canonical 地址"的既有唯一拼写点之一，旁边还有 {@code
 * GovCreateOfficePlan}/{@code SpawnArmyPlan} 的同名同形函数）。★ 刻意的取舍：**不为一次下放把既有类的可见性放宽**，
 * 代价只是适配器与它同包（同包先例：{@code AppArmyManpowerPort}——它的委托人 {@code HouseholdManpowerAllocator} 也是包内可见）。
 * 其余不依赖包内可见物的军队适配器（{@code AppArmyToolPerspective}）仍住在 {@code app.tools.army}，与 B3 试点的 {@code
 * app.tools.map.*} 同形制。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个入参进去，同一个视图对象/同一段字节出来。
 */
public final class AppArmyToolViews implements ArmyToolViews {

  @Override
  public Map<String, Object> armyCombat(CombatRecord record) {
    return ApiViews.armyCombat(record);
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
  public List<Map<String, Object>> compositionDeltaView(List<CompositionDelta> deltas) {
    return ToolSupport.compositionDeltaView(deltas);
  }

  /**
   * ★ 转调 {@link RaiseUnitPlan#unitAddress(String)}（{@code unit:<id>} 的既有拼写点；`FormatUnitTool`
   * 迁入前用的就是它）。
   */
  @Override
  public String unitAddress(String unitId) {
    return RaiseUnitPlan.unitAddress(unitId);
  }
}
