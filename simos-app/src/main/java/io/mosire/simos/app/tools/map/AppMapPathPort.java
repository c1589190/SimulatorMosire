package io.mosire.simos.app.tools.map;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.tools.MapPathPort;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.PathFinder;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Optional;

/**
 * {@link MapPathPort} 的宿主实现（组合根）：{@code simos.map.path} 的寻路腿——**逐条照搬**它今天在工具里的四段逻辑 （单位查无 / 无有效位置 /
 * 到不了 / 到达），只是把"从哪拿 unit 切片"换成 app 侧同一个 {@link ToolSupport#unitState}。
 *
 * <p>★ 起点**由服务端取**（该单位在该状态下的 {@code effectivePosition}），调用方只给终点——与 GUI 同一口径 （前端不传起点，避免第二份真相）。寻路本身转调
 * unit 域的 {@link PathFinder}，app 层不另写寻路。
 */
public final class AppMapPathPort implements MapPathPort {

  @Override
  public Outcome findPath(SimulationState state, String unitId, HexCoord goal) {
    // ★ 顺序照原样：先取 unit 切片（装配故障优先暴露），再校验单位 id 的形态（坏 id ⇒ IllegalArgumentException）。
    UnitState units = ToolSupport.unitState(state);
    UnitId id = new UnitId(unitId);
    Unit unit = units.units().get(id);
    if (unit == null) {
      return new Outcome.UnitMissing();
    }
    Optional<HexCoord> start = units.effectivePosition(id, state.meta().timestamp());
    if (start.isEmpty()) {
      return new Outcome.NoStart();
    }
    Optional<List<HexCoord>> path =
        PathFinder.findPath(
            ToolSupport.gameMap(state), start.get(), goal, unit, TerrainMovementCost.INSTANCE);
    return path.<Outcome>map(Outcome.Reachable::new).orElseGet(Outcome.Unreachable::new);
  }
}
