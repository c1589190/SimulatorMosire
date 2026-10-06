package io.mosire.simos.app.time;

import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.util.state.SimulationState;

/**
 * ★★ <b>测试专用桥</b>：{@link MarketTopologyBook} 是包内可见的装配类；{@code simos-app} 的端到端测试 （{@code
 * io.mosire.simos.app.world} 包）需要从真实 {@code core.replay} 状态读生产路径的拓扑，故在这个同包测试类里 暴露一个只读入口。<b>不改
 * main</b>、不复制装配逻辑 —— 调用的仍是生产 {@link MarketTopologyBook#from(SimulationState)}。
 */
public final class MarketTopologyBookTestAccess {

  private MarketTopologyBookTestAccess() {}

  /** 生产路径的区域拓扑（与 {@code MarketTopologyBook.from} 逐字同一实现）。 */
  public static MarketTopology topologyOf(SimulationState state) {
    return MarketTopologyBook.from(state);
  }
}
