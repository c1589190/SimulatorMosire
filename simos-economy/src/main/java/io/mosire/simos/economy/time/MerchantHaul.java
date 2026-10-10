package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.util.economy.EconomyVocabulary;

/**
 * ★★ <b>M-C：跑商门槛 = 工具消耗（全仓唯一拼写点）</b>—— 设计书 §12 H-D/H-1/H-5，计划 §2.4 M0b、§7 Q-14/Q-17。
 *
 * <p>★★ <b>用户原话（2026-10-10）</b>：
 *
 * <blockquote>
 *
 * 「因为如果一个家户有剩余劳动力，完全可以候选跑商作为生产方式，为单hex增加运力——因此跑商这个产业的准入门槛要高， 单次跑商需要花费大量tool」
 *
 * </blockquote>
 *
 * <pre>
 * ① 消耗形态 = **一次性消耗**（H-1 默认）：从承运家户的 {@code tool} **商品账**扣掉，计成本、**不返还**；
 * ② 消耗量   = {@link #TOOL_MILLI_PER_HAUL}（具名常量 + 理由见下）；
 * ③ 缺工具   ⇒ **该次跑商不成立**（H-5）：该条承运不被分配、具名归因 {@code tool-short}，需求转成"运力未获服务"；
 * ④ ★★ V-22：**不动** {@code AssetKind.TOOL} 的产权份额（那是作坊/织机的产能份额，另一本账）。
 *    跑商消耗只走**商品账**（{@code tool} 商品）⇒ 两侧口径不互相冲抵（具名算式见实现账本 §3 J-2）。
 * ⑤ ★★ <b>T-fix（2026-10-10）：冻结让路 + fail-closed</b> —— 判据用<b>可用量</b>
 *     {@code max(0, stock − householdFrozenGoods)}：<b>被冻结的 tool 不许被跑商烧</b>（冻结只表达已明确的占用，
 *     其中最典型的就是同一户本轮的 {@code tool} 卖单承诺）。可用量 &lt; 一趟 ⇒ <b>该次跑商不成立</b>：
 *     <b>一点也不烧</b>（绝不部分扣），具名归因二选一 —— {@link #TOOL_FROZEN_REASON}（余额够、被冻结占住）
 *     与 {@link #TOOL_SHORT_REASON}（真缺货）<b>必须分得开</b>。
 *     ★ 实扣走 {@code EconomySettlement.consumeForLoss}（**非换手损耗的唯一写口**：账户减 + 损耗账加同址，
 *     Σ余额 + losses 守恒）—— 不再由市场轮直接改会话账（T-fix 修的就是那条旁路，理由见该方法的注）。
 * </pre>
 *
 * <p>★★ <b>标定理由（H-1「与一趟运费可比」）</b>：
 *
 * <pre>
 * 一趟的规模锚 = 承运 10 商品单位（10,000 毫商品 = 一个人一个周期的口粮量）
 *   出厂粮价 1 毫银/商品单位、3 hex 的路线费率 ≈ 60‰、承运成本 25‰ ⇒ 单位运费 = 2 毫银/商品单位
 *   ⇒ 一趟的运费 ≈ 10 × 2 = **20 毫银**
 * 工具价 = 20 毫银/商品单位（EconomySeeder.MARKET_PRICE_TOOL，由"每件工具 2 单位铁"内生）
 *   ⇒ {@link #TOOL_MILLI_PER_HAUL} = 1,000 毫工具（= 1 商品单位）= **20 毫银** ⇒ 与一趟运费 **1 : 1** 可比。
 * </pre>
 *
 * <p>★ <b>它是"门槛"而不是"税"</b>：门槛的形态是"每次跑商都必须有 ≥ 1 单位工具在手上"—— 工具是**存量**，用完必须再买（补货路径不在本批，见实现账本
 * §6）。{@link #TOOL_COMMODITY} 与 {@link #TOOL_BURN_ACCOUNT} 是本批新增的两处**具名**落点（商品维、损耗账维各一）。
 */
public final class MerchantHaul {

  /**
   * 跑商消耗的**工具商品**（{@code EconomyVocabulary.TOOL_COMMODITY_ID}；V-22：商品账，不是 {@code AssetKind.TOOL}
   * 产权份额）。
   */
  public static final CommodityId TOOL_COMMODITY =
      new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /**
   * ★★ <b>单次跑商消耗的工具（毫工具）</b>：{@code 1,000}（= 1 商品单位 = 20 毫银，与一趟运费 1:1 可比；理由见类注）。
   *
   * <p>改它 = 改跑商产业的准入门槛（不是改某个读数）。GM 可调是后续批次的事（V7 参数目录）。
   */
  public static final long TOOL_MILLI_PER_HAUL = 1_000L;

  /**
   * ★ <b>工具被烧掉后记进哪个损耗账</b>（{@code ProductionLedger.Accumulator.addLoss} 的键）。
   *
   * <p>与既有的 {@code MarketSettlement.TRANSPORT_LOSS_ACCOUNT}（{@code market-transport}，货损）**分开**：
   * 一个是货没了、一个是工具磨损，读数不该混。★ 守恒式不变：账户余额减、损耗账加 ⇒ Σ余额 + losses 逐值守恒。
   */
  public static final IndustryId TOOL_BURN_ACCOUNT = new IndustryId("market-merchant-haul");

  /**
   * ★★ <b>T-fix：该次跑商因"可用量不足一趟"不成立时的具名归因 —— 真缺货</b>（{@code stock < 一趟所需}）。
   *
   * <p>它与 {@link #TOOL_FROZEN_REASON} <b>必须分得开</b>：一个是"买不到工具"，一个是"工具在手上但被卖单承诺占住" ——
   * 处置方式完全不同（前者要补货、后者要退单或等成交），混成一个说法会让日志再也答不出"为什么走不动"。 ★ 与 {@code
   * MerchantCapacityPool.laneBlockedReason} 的 {@code tool-short} <b>同值同源</b>（本常量是它的唯一拼写点）。
   */
  public static final String TOOL_SHORT_REASON = "tool-short";

  /**
   * ★★ <b>T-fix：该次跑商因冻结让路而不成立时的具名归因</b>（{@code stock ≥ 一趟所需} 但 {@code max(0, stock − frozen) <
   * 一趟所需} ⇒ 缺口**只能**来自冻结）。
   *
   * <p>★★ 这条归因是 T-fix 的核心证据：改前实扣上限只看 {@code stock}、不看冻结 ⇒ 把已承诺给卖单的 tool 烧掉，
   * 同轮该户卖单成交时当场硬抛（"转移会花掉家户账上已冻结的商品"）。冻结优先于跑商 —— 见类注 ⑤。
   */
  public static final String TOOL_FROZEN_REASON = "tool-frozen";

  // ★ 2026-10-10 清理（G3-fix-3 后续）：本类原先还有**第三档归因**「本轮预算镜像已放行过若干趟，判据量因此不足一趟」
  //   （G3-fix-2 引入）。G3-fix-3 把判据量改成"当刻可用量"本身之后，"被拦 ⇔ 可用量 < 一趟" ⇒ 该档在**生产路径与
  //   4/5 参旧路径上都结构上不可达** ⇒ 已连同它的常量、计数与日志字段一并删除（见 {@code MerchantCapacityPool} 类注）。
  //   归因只剩上面两支：真缺货 / 被冻结占住 —— 二选一的唯一拼写点是 {@link #blockedReason}。

  private MerchantHaul() {}

  /** 手里的工具够不够跑一趟（H-5 的硬门槛；不足 ⇒ 该次跑商不成立）。 */
  public static boolean affordsRun(long toolMilli) {
    return toolMilli >= TOOL_MILLI_PER_HAUL;
  }

  /** 手里的工具还够跑几趟（只作读数/日志；{@code toolMilli} 为负按 0 处理）。 */
  public static long runsAffordable(long toolMilli) {
    return toolMilli <= 0L ? 0L : toolMilli / TOOL_MILLI_PER_HAUL;
  }

  /**
   * ★★ <b>T-fix：可用量不足一趟时，这次拦下到底是"被冻结占住"还是"真缺货"</b>（二选一，不许含糊）。
   *
   * <pre>
   * 判据（纯函数，只读入参；不读时钟/随机 ⇒ I7）：
   *   stockMilli ≥ neededMilli ⇒ {@link #TOOL_FROZEN_REASON}
   *       —— 余额本身够一趟，可用量却不够 ⇒ 缺口**只能**来自冻结（max(0, stock − frozen) &lt; needed ≤ stock ⇒ frozen &gt; 0）
   *   stockMilli &lt; neededMilli ⇒ {@link #TOOL_SHORT_REASON}
   *       —— 就算冻结为 0 也照样不成立 ⇒ 约束是**实物存量**本身（真缺货）
   * </pre>
   *
   * @param stockMilli 该承运家户的 {@code tool} 余额（**未减冻结**）
   * @param neededMilli 一次跑商所需（{@link #TOOL_MILLI_PER_HAUL}）
   */
  public static String blockedReason(long stockMilli, long neededMilli) {
    return stockMilli >= neededMilli ? TOOL_FROZEN_REASON : TOOL_SHORT_REASON;
  }
}
