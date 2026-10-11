package io.mosire.simos.app.tools.economy;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.tools.EconomyToolViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * {@link EconomyToolViews} 的宿主实现（组合根）：**逐条转调** {@link ToolSupport} 的同名视图（它又转调 GUI 的 {@code
 * ApiViews}）——视图只有一份，本类对下放工具的行为是**恒等**的（同一个坐标 + 同一个状态进来，同一张 Map 出去）。
 *
 * <p>★ 依赖倒置的方向：经济域的工具**不认识** {@code ApiViews}（那是 app 的 GUI 视图层，且要 actor 切片，不在 economy 的允许面） ⇒
 * 端口住在域里、实现住在组合根（与 B3 试点 {@code AppMapToolViews} / 第二域 {@code AppArmyToolViews} 同一条）。
 */
public final class AppEconomyToolViews implements EconomyToolViews {

  @Override
  public Map<String, Object> economyHex(HexCoord coord, SimulationState state) {
    return ToolSupport.economyHex(coord, state);
  }

  @Override
  public Map<String, Object> economyOwnership(HexCoord coord, SimulationState state) {
    return ToolSupport.economyOwnership(coord, state);
  }
}
