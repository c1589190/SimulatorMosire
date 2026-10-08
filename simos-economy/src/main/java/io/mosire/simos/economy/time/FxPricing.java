package io.mosire.simos.economy.time;

/**
 * ★★ <b>一笔外汇成交的定价规则</b>（阶段 2-A2a；约束设计书 §3.2"汇率 = 成交价"、§4.5）。
 *
 * <pre>
 * 成交价 = (买方限价 + 卖方限价) / 2      // 向下取整；两个限价都是 per-mille
 * </pre>
 *
 * <p>★★ <b>为什么是"两个限价的中间价"而不是"被动方限价"</b>（一处必须写下来的口径选择）：
 *
 * <ul>
 *   <li>本仓的撮合是<b>一轮一批</b>（订单先生成、再撮合，见 {@code MarketSettlement} 的 MarketRound），
 *       "谁先挂进簿"在这套形态里不是市场事实，而是实现细节 ⇒ 用"被动方定价"会让价格依赖插入序（一种不可见的口径）；
 *   <li>中间价是<b>双方限价的纯函数</b>：与插入序、线程数、遍历序都无关（同一世界日跑两遍逐值相同，I7）；
 *   <li>它在<b>个体理性区间</b> {@code [卖方限价, 买方限价]} 内（买方限价 ≥ 卖方限价才撮合）⇒ 双方都不亏；
 *   <li>于是"官方报价 ≠ 市场成交价"是结构性的（官方报价只是其中一方的限价）：政府挂 {@code bidP}/{@code askP}，家户挂自己的限价，成交价落在两者之间 ——
 *       这正是 I18/I17"官方汇率是报价，不是成交价"的落点。
 * </ul>
 *
 * <p>★ <b>取整方向</b>：向下取整（{@code floor}）—— 奇数和的半毫落在买方一侧。规则写在这里一处，撮合与窗口请求共用。
 */
public final class FxPricing {

  private FxPricing() {}

  /**
   * 一对买卖限价的成交价（per-mille；向下取整）。
   *
   * @param buyLimitPerMille 买方限价（愿付上限；&gt; 0）
   * @param sellLimitPerMille 卖方限价（愿收下限；&gt; 0）
   * @throws IllegalArgumentException 两个限价不构成交叉（{@code buy < sell}）—— 不撮合，也绝不"猜"一个价
   */
  public static long fillPricePerMille(long buyLimitPerMille, long sellLimitPerMille) {
    if (buyLimitPerMille <= 0L || sellLimitPerMille <= 0L) {
      throw new IllegalArgumentException(
          "FX 限价必须 > 0（说不出价就不该有委托）: buy=" + buyLimitPerMille + " sell=" + sellLimitPerMille);
    }
    if (buyLimitPerMille < sellLimitPerMille) {
      throw new IllegalArgumentException(
          "FX 限价不交叉（买方 " + buyLimitPerMille + " < 卖方 " + sellLimitPerMille + "）⇒ 不该成交");
    }
    return Math.addExact(buyLimitPerMille, sellLimitPerMille) / 2L;
  }
}
