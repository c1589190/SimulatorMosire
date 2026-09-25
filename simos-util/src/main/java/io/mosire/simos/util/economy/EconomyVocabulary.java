package io.mosire.simos.util.economy;

/**
 * 跨模块共用的**经济词表**：全仓恰一份，由源扫描护栏钉住（{@code EconomyVocabularyGuardTest}）。
 *
 * <p>★★ **为什么在 {@code simos-util} 而不是 {@code simos-economy-api}**：军队（{@code simos-unit}）将来也要按同一
 * 口径吃粮，而 {@code simos-unit} 只依赖 util + map ⇒ **只有 util 是 economy 与 unit 都能看见的共同上游**。
 *
 * <p>★ **本类只放 {@code String} 与原生类型**：util 不能依赖 economy-api（方向相反），故 {@code CommodityId} 那一层留在
 * economy 侧，由 {@link #GRAIN_COMMODITY_ID} 构造。
 *
 * <p>★★ **口粮的载体是"口径 + 两个纯函数"，不是一个"每人每日的量"常量**（V5；v2 spec §八.6）：
 *
 * <ul>
 *   <li>口径 = 每人每 {@link #RATION_CYCLE_DAYS} 天 {@link #RATION_MILLI_PER_PERSON} 毫粮（= 10 粮/周期）；
 *   <li>由它推出的**累计**函数 {@link #cumulativeRationMilli}（人 × 天 ⇒ 毫粮）与**逐日差分**函数 {@link
 *       #dailyRationMilli}（人 × 第几天 ⇒ 该日毫粮）。
 * </ul>
 *
 * ★ **为什么不能是"每人每日 83 毫粮"**：83 是 {@code 10,000 ÷ 120} 的**商**（83.33 ⇒ 83）。拿它当"一天的量"再乘天数， 每个周期每人口就少吃
 * {@code 120 × 0.33 = 40 毫粮}（−0.4%）—— **残差在定义上就已经丢了**，留作底数也是把陷阱留在原地。 逐日差分则**一分不丢**：{@code Σ(第
 * 1..120 天的日耗) == 人口 × 10,000} 精确成立（见 {@code EconomyVocabularyTest}）。
 *
 * <p>★ **量纲三者不可互相顶替**：口径是「毫粮 / 人·**{@link #RATION_CYCLE_DAYS} 天**」；{@code days}/{@code day}
 * 是「**天**」； 而 {@code Industry.cycleDays} 是「**该产业的**一周期几天」（可以不是 120 —— 夹具里常见 2~3 天）。三者数值可能巧合相等，
 * 语义互不相干。
 *
 * <p>★ **V7 参数目录（v2 spec §四）落地后**：口径的两个数迁入 {@code economy} 切片的 {@code worldParams} 并成为 GM 可调，
 * 本类的两个纯函数跟着改成读参数。**V7 之前不许造半套目录**。
 */
public final class EconomyVocabulary {

  /** 粮的商品 id（v2 spec §3.2：**粮与种子是同一个商品**，不设 {@code seed}）。 */
  public static final String GRAIN_COMMODITY_ID = "grain";

  /** 1 粮 = 1000 毫粮（库存按最小计量单位，{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = 1000L;

  /** 口粮口径的**分子**（毫粮/人）：每人每 {@link #RATION_CYCLE_DAYS} 天吃 10 粮 = 10,000 毫粮（v2 spec §十"消费"行）。 */
  public static final long RATION_MILLI_PER_PERSON = 10_000L;

  /** 口粮口径的**分母**（天）：一个口粮周期 = 120 天（与 {@code EconomySeeder.CYCLE_DAYS} 数值相同、**语义无关**）。 */
  public static final long RATION_CYCLE_DAYS = 120L;

  private EconomyVocabulary() {}

  /**
   * **累计口粮**（毫粮）：{@code population} 人 **{@code days} 天**的总需求 = {@code 人口 × 10,000 × 天 ÷ 120}（向下取整）。
   *
   * <p>★ **这才是"多日口粮"的唯一写法**：{@code n × 某一天的量} 在逐日差分的口径下**乘不出来**（每日的量本身逐日不同）。
   *
   * <p>★ 它是**天的函数**（不是"周期内第几天"的函数）：调用方传绝对天数/绝对日号。整周期处的值恰好是口径的整数倍 （{@code cumulativeRationMilli(p,
   * 120) == p × 10,000}），故残差在周期边界上**归零**，不会跨周期累积漂移。
   *
   * @param population 人口（人）；不得为负
   * @param days 天数（天）；不得为负（0 ⇒ 0）
   * @throws IllegalArgumentException 人口或天数为负
   */
  public static long cumulativeRationMilli(long population, long days) {
    if (population < 0L) {
      throw new IllegalArgumentException("累计口粮的人口不得为负: " + population);
    }
    if (days < 0L) {
      throw new IllegalArgumentException("累计口粮的天数不得为负: " + days);
    }
    return population * RATION_MILLI_PER_PERSON * days / RATION_CYCLE_DAYS;
  }

  /**
   * **第 {@code day} 天的当日口粮**（毫粮）= {@code 累计(day) − 累计(day − 1)} —— **逐日差分**，残差不丢。
   *
   * <pre>
   * 100 人：第 1 天 8,333、第 2 天 8,333、第 3 天 8,334（= 25,000 − 16,666）、… 第 120 天补足差额
   * ⇒ Σ(第 1..120 天) == cumulativeRationMilli(100, 120) == 100 × 10,000 == 1,000,000（精确）
   * </pre>
   *
   * @param population 人口（人）；不得为负
   * @param day **绝对日号**（1 起；0 是创世、没有"第 0 天"这一天）
   * @throws IllegalArgumentException 人口为负或 {@code day < 1}
   */
  public static long dailyRationMilli(long population, long day) {
    if (day < 1L) {
      throw new IllegalArgumentException("当日口粮的日号必须 ≥ 1（创世是第 0 天，没有第 0 天这一天）: " + day);
    }
    return cumulativeRationMilli(population, day) - cumulativeRationMilli(population, day - 1L);
  }
}
