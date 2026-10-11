package io.mosire.simos.economy.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * 经济工具的**视图口**：逐格经济读数 / 逐格产权读数——两者的**唯一实现**是宿主的 {@code ApiViews} （GUI 的 {@code GET
 * /api/economy/hex} 与 {@code GET /api/economy/ownership} **同一个函数**），故由宿主**构造期注入**。
 *
 * <p>★★ <b>为什么必须注入、不能在域内抄一份</b>：这两个视图同时读 economy（行侧账）与 actor（{@code HouseholdInventory}） 两片，而
 * {@code actor} 切片**不在 economy 的允许面**（economy 只依赖 actor-api 契约）⇒ 域内结构上够不着； 更要紧的是 AGENT.md §8.3
 * 的"视图只有一份"：抄一份 = 同一资源的两个形状，正是该纪律要消灭的形态（与 B3 第二域 army 的 {@code ArmyToolViews.armyCombat} 同一条处置）。
 *
 * <p>★ <b>签名里只有 map/util 类型</b>（{@code HexCoord} / {@code SimulationState} / {@code Map}）⇒
 * 依赖倒置到域自己的包， 不进 {@code ga-spi}（契约层不许出现域专属形状）。
 */
public interface EconomyToolViews {

  /** 逐格经济读数（宿主转调 {@code ApiViews.economyHex}，逐字节同形）。 */
  Map<String, Object> economyHex(HexCoord coord, SimulationState state);

  /** 逐格产权读数（宿主转调 {@code ApiViews.economyOwnership}，逐字节同形）。 */
  Map<String, Object> economyOwnership(HexCoord coord, SimulationState state);
}
