package io.mosire.simos.app.tools.army;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.tools.ArmyToolPerspective;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * {@link ArmyToolPerspective} 的宿主实现（组合根）：**可见性判定**逐条转调 {@link ToolSupport}（围栏逻辑只有那一份）， **Army
 * 的主子事实**逐条转调 army 切片的 {@code Army}（铁律 3）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@link ToolContext} 进来同一个布尔出来；同一个 {@code armyId} 进来 同两个字段出来（"军队不存在"走空
 * {@link Optional}，由调用点给出与迁入前逐字相同的具名拒）。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1</b>：{@code armies} 表与 {@code Army} / {@code ArmyId} 从 {@code sd} 搬进
 * {@code simos-army} ⇒ 本实现从"读 sd 切片"改成"读 army 切片"（{@link ToolSupport#armyData}）。★
 * 判据与异常文案<b>逐字未改</b> （{@code ArmyId.parse} 的非空白拒因、以及"军队不存在 ⇒ 空 {@code Optional}"的分工），只有切片的家变了。
 */
public final class AppArmyToolPerspective implements ArmyToolPerspective {

  @Override
  public boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord) {
    return ToolSupport.hexVisible(context, mapId, map, coord);
  }

  @Override
  public Optional<ArmyGovernance> armyGovernance(SimulationState state, String armyId) {
    // ★ ArmyId.parse 的合法性判定留在这一层（与迁入前 AssignArmyGovPlan 的第一行同源）：非法 id 抛的
    //   IllegalArgumentException（类与文案都来自 ArmyId）原样冒到工具层的 BAD_REQUEST 折叠。
    Army army = ToolSupport.armyData(state).armies().get(ArmyId.parse(armyId));
    if (army == null) {
      return Optional.empty();
    }
    return Optional.of(new ArmyGovernance(army.rootUnit(), army.masterGovUnitId()));
  }
}
