package io.mosire.simos.economy.time;

/**
 * ★★ <b>本轮市场为什么开市</b>（P2a 调度判据）：商品撮合只在绝对世界日 {@code day % 5 == 0} 开一轮。
 *
 * <p>★ 调度只依赖<b>绝对世界日 + 当前市场表是否为空</b>：两条推进路径（一次 360 天 vs 三次 120 天）在同一世界日看到的触发原因必须逐值相同 ——
 * 任何"距上次开市几天"的计数器或关账/库存旁路都会破坏这条。
 *
 * <ul>
 *   <li>{@link #NONE} —— 今天不开市；
 *   <li>{@link #PERIODIC} —— 每 {@code MARKET_RESTOCK_INTERVAL_DAYS} 天的例行轮。
 * </ul>
 */
public enum MarketTrigger {
  NONE,
  PERIODIC
}
