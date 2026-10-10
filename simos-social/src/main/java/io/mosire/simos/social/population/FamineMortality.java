package io.mosire.simos.social.population;

/**
 * ★★★ <b>W3（2026-10-11）：饥荒死亡率的唯一口径（Social 自己定；经济侧只给事实）</b>。
 *
 * <p>★★ <b>为什么口径在 Social</b>：用户 0–8 第 0 条原话「经济模块算完之后才应该把经济情况报给社会模块，<b>让社会模块单独核算死亡率</b>」； 约束设计书 §2 的
 * S-11 把它拆成两半：① 生产资料缺口 ⇒ 经济侧扣产能（W2 已做）；② <b>口粮缺口 ⇒ 报社会</b>，社会据此扣劳动/人口
 * （经济侧不自己扣）。故本类只吃"事实"（缺口、当日需求），产出"每 tick 每人的饥荒死亡率（ppm）"。
 *
 * <pre>
 * 断顿比例 ratioPerMille = need ≤ 0 ? (gap &gt; 0 ? 1000 : 0)
 *                                   : min(1000, ⌊gap × 1000 ÷ need⌋)      // 封顶 1000‰
 * 饥荒死亡率 faminePpm    = ⌊FAMINE_MAX_PPM_PER_TICK × ratioPerMille ÷ 1000⌋ // 线性、向下取整
 * 有效死亡率 effectivePpm = 基准率（SocialVitalRates / 家户覆盖） + faminePpm
 * </pre>
 *
 * <p>★★ <b>不变量（fail-closed 的形态）</b>：
 *
 * <ul>
 *   <li>{@code gap ≤ 0} ⇒ {@code faminePpm = 0} ⇒ <b>完全不动</b>（判据 W3-④：缺口=0 只走基准生死率）；
 *   <li>{@code faminePpm ≤ } {@link #FAMINE_MAX_PPM_PER_TICK} = 1000 ppm（1‰/tick）⇒
 *       即使全部人"完全没得吃"，日死亡也 ≤ 1‰；
 *   <li>两个入参都为负 ⇒ 具名拒（坏数据不当 0）。
 * </ul>
 *
 * <p>★★ <b>它只改"当 tick 的分子"，不新增任何持久状态</b>：死亡仍走 {@code HouseholdBook.settleVitalEventsResult} 既有的
 * {@code (家户, 批次, DEATH)} 余数累加器 —— 于是 sub-1 的死亡按余数跨 tick 累加（长期速率精确、同状态重放逐字节相同）， 且<b>不触碰铁律
 * 5</b>（无新组件、无 Codec/ChangeSet 改动）。
 *
 * <p>★★ <b>为什么"逐商品生产花费缺口"不进这个算式（具名口径，可被推翻）</b>：那个量的口粮维 = {@code naturalNeeds × 35 天}（W2 的 {@code
 * MARKET_LIFE_RESERVE_DAYS} 前瞻保留），拿它当"今天吃不上"的比例， 一个"还剩 3 天存粮"的户就会得到 ≈914‰ 的断顿比例 ⇒
 * 饥荒从"逐步"变成"一次性团灭"。本口径取<b>已实现的那一段</b>：家户当日真的没吃到的那部分 （经济腿的 {@code
 * unmetNeed[grain]}，且已在借粮/还债等全部救济之后）。★ 若控制方/用户要求改用市场缺口， 改的是 {@code HouseholdBook} 取哪一个字段，本类的算式不用动。
 *
 * <p>★ <b>标定项（W6）</b>：{@link #FAMINE_MAX_PPM_PER_TICK} 是<b>控制方实现口径</b>（不是用户裁定）：取保守值 1‰/tick。 线性 vs
 * 带阈值/超线性、以及这个数本身，都属于"跑完真实 world 再标定"的旋钮（用户 2026-09-25：致死率是"判断结果" ⇒ 必须可调、默认取保守值）。
 */
public final class FamineMortality {

  /** 断顿比例的上限（毫）：缺口 = 全部需求。 */
  public static final long FULL_SHORTAGE_PER_MILLE = 1_000L;

  /**
   * ★★ <b>"完全没得吃"（断顿比例 = 1000‰）时的饥荒死亡率</b>（ppm/tick）：<b>1000 ppm = 1‰/tick</b>。
   *
   * <p>★ 保守取值：即使一户连续整月一粒未进，月死亡也 ≈ 3%；部分断顿按比例线性下降。★ <b>它是 W6 的标定项</b>， 不是从任何既有裁定推出来的数（见类注）。
   */
  public static final long FAMINE_MAX_PPM_PER_TICK = 1_000L;

  private FamineMortality() {}

  /**
   * 断顿比例（毫，0..1000）：{@code min(1000, ⌊gap × 1000 ÷ need⌋)}。
   *
   * <p>★ {@code need ≤ 0} 而 {@code gap > 0}（没有需求却有缺口 = 坏数据/边界）⇒ 封顶 1000‰； {@code gap ≤ 0} ⇒
   * 0（<b>不</b>看需求）。
   */
  public static long shortageRatioPerMille(long rationGapMilli, long rationNeedMilli) {
    if (rationGapMilli < 0L || rationNeedMilli < 0L) {
      throw new IllegalArgumentException(
          "FamineMortality 的两个量都必须 ≥ 0: gap=" + rationGapMilli + " need=" + rationNeedMilli);
    }
    if (rationGapMilli <= 0L) {
      return 0L;
    }
    if (rationNeedMilli <= 0L) {
      return FULL_SHORTAGE_PER_MILLE;
    }
    return Math.min(
        FULL_SHORTAGE_PER_MILLE,
        Math.floorDiv(
            Math.multiplyExact(rationGapMilli, FULL_SHORTAGE_PER_MILLE), rationNeedMilli));
  }

  /**
   * 饥荒死亡率（ppm/tick）：{@code ⌊FAMINE_MAX_PPM_PER_TICK × ratioPerMille ÷ 1000⌋}。
   *
   * <p>★ 断言：{@code 0 ≤ 返回值 ≤ FAMINE_MAX_PPM_PER_TICK}；{@code gap ≤ 0 ⇒ 0}。
   */
  public static long famineRatePerMillionPerTick(long rationGapMilli, long rationNeedMilli) {
    long ratio = shortageRatioPerMille(rationGapMilli, rationNeedMilli);
    return Math.floorDiv(
        Math.multiplyExact(FAMINE_MAX_PPM_PER_TICK, ratio), FULL_SHORTAGE_PER_MILLE);
  }
}
