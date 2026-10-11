package io.mosire.simos.app.tools.army;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.tools.ArmyToolPerspective;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * {@link ArmyToolPerspective} 的宿主实现（组合根）：**可见性判定**逐条转调 {@link ToolSupport}（围栏逻辑只有那一份）， **Army
 * 的主子事实**逐条转调 sd 切片的 {@code Army}（军队模块不认识 sd——铁律 3）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@link ToolContext} 进来同一个布尔出来；同一个 {@code armyId} 进来 同两个字段出来（"军队不存在"走空
 * {@link Optional}，由调用点给出与迁入前逐字相同的具名拒）。
 */
public final class AppArmyToolPerspective implements ArmyToolPerspective {

  @Override
  public boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord) {
    return ToolSupport.hexVisible(context, mapId, map, coord);
  }

  @Override
  public Optional<ArmyGovernance> armyGovernance(SimulationState state, String armyId) {
    // ★ ArmyId.parse 的合法性判定留在这一层（与迁入前 AssignArmyGovPlan 的第一行同源）：非法 id 抛的
    //   IllegalArgumentException（类与文案都来自 sd）原样冒到工具层的 BAD_REQUEST 折叠。
    Army army = ToolSupport.sdState(state).armies().get(ArmyId.parse(armyId));
    if (army == null) {
      return Optional.empty();
    }
    return Optional.of(new ArmyGovernance(army.rootUnit(), army.masterGovUnitId()));
  }
}
