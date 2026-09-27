package io.mosire.simos.economy.time;

/**
 * ★★ <b>本轮市场为什么开市</b>（M2.0 #5 的调度判据）：商品撮合<b>每 5 天一轮</b>，粮库存低于阈值可<b>追加一轮</b>。
 *
 * <p>★ 调度必须只依赖<b>绝对世界日 + 当前状态</b>（M0.1 的硬约束）：两条推进路径（一次 360 天 vs 三次 120 天） 在同一世界日看到的触发原因必须逐值相同 ——
 * 任何"距上次开市几天"的计数器都会破坏这条。
 *
 * <ul>
 *   <li>{@link #NONE} —— 今天不开市；
 *   <li>{@link #PERIODIC} —— 每 {@code MARKET_RESTOCK_INTERVAL_DAYS} 天的例行轮；
 *   <li>{@link #CYCLE_CLOSE} —— 产业关账日（保留 M2-L1 的旧时点：周期天数不是 5 的倍数的世界照旧在这一天开市）；
 *   <li>{@link #LOW_GRAIN_STOCK} —— 例行轮之外的**追加轮**：粮库存低于阈值（见 {@code
 *       MarketSettlement.MARKET_LOW_STOCK_TRIGGER_DAYS}）。
 * </ul>
 */
public enum MarketTrigger {
  NONE,
  PERIODIC,
  CYCLE_CLOSE,
  LOW_GRAIN_STOCK
}
