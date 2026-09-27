package io.mosire.simos.util.economy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨模块共用的**经济词表**：全仓恰一份，由源扫描护栏钉住（{@code EconomyVocabularyGuardTest}）。
 *
 * <p>★★ **为什么在 {@code simos-util} 而不是 {@code simos-economy-api}**：军队（{@code simos-unit}）将来也要按同一
 * 口径吃粮，而 {@code simos-unit} 只依赖 util + map ⇒ **只有 util 是 economy 与 unit 都能看见的共同上游**。
 *
 * <p>★ **本类只放 {@code String} 与原生类型**：util 不能依赖 economy-api（方向相反），故 {@code CommodityId} 那一层留在
 * economy 侧，由 {@link #GRAIN_COMMODITY_ID} 构造。
 *
 * <p>★★ **商品不再只有粮**（R3 的 T1）：本类是**六个商品 id 的唯一拼写点**（粮 / 布 / 纤维 / 工具 / 铁 / 木）—— 三个以上模块各写一份
 * 字面量就是三处真相（{@code ApiViews} 与 {@code EconomySettlement} 各私藏一份 {@code "grain"} 正是 v1 的病灶形态）。
 *
 * <p>★★ **需求口径带商品维度**（R3 的 T1；spec §七 原文："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）：
 *
 * <ul>
 *   <li>**粮**：每人每 {@link #RATION_CYCLE_DAYS} 天 {@link #RATION_MILLI_PER_PERSON} 毫粮（= 10 粮/周期）；
 *   <li>**布**：每人每 {@link #CLOTH_CYCLE_DAYS} 天 {@link #CLOTH_MILLI_PER_PERSON} 毫布（= 1 匹/年）。
 * </ul>
 *
 * ★ **本轮只把"每商品一条需求"的形状做出来**：{@link #dailyNeedsMilli} 逐商品给出当日毫单位，结算把它写进 {@code
 * ClassRow.naturalNeeds}（读口因此看得见"这个格子的人一年要几匹布"），**但布的缺口不参与饿死判据** —— 阈值与死亡作用留 R4（本轮 明确不做
 * `CrisisMonitor` / 生理压力）。
 *
 * <p>★★ **载体是"口径 + 纯函数"，不是一个"每人每日的量"常量**（V5；v2 spec §八.6）：<b>为什么不能是"每人每日 83 毫粮"</b>：83 是 {@code
 * 10,000 ÷ 120} 的**商**（83.33 ⇒ 83）。拿它当"一天的量"再乘天数， 每个周期每人口就少吃 {@code 120 × 0.33 = 40 毫粮}（−0.4%） ——
 * **残差在定义上就已经丢了**，留作底数也是把陷阱留在原地。 逐日差分则**一分不丢**：{@code Σ(第 1..120 天的日耗) == 人口 × 10,000} 精确成立（见
 * {@code EconomyVocabularyTest}）。
 *
 * <p>★ **量纲三者不可互相顶替**：口径是「毫粮 / 人·**{@link #RATION_CYCLE_DAYS} 天**」；{@code days}/{@code day}
 * 是「**天**」； 而 {@code Industry.cycleDays} 是「**该产业的**一周期几天」（可以不是 120 —— 夹具里常见 2~3 天）。三者数值可能巧合相等，
 * 语义互不相干。
 *
 * <p>★ **V7 参数目录（v2 spec §四）落地后**：口径的四个数迁入 {@code economy} 切片的 {@code worldParams} 并成为 GM 可调，
 * 本类的这些纯函数跟着改成读参数。**V7 之前不许造半套目录**。
 */
public final class EconomyVocabulary {

  /** 粮的商品 id（v2 spec §3.2：**粮与种子是同一个商品**，不设 {@code seed}）。 */
  public static final String GRAIN_COMMODITY_ID = "grain";

  /** 布的**商品** id（R3；★ 与 {@code AssetKind.TOOL} 不同命名空间：那个是生产资料，这个是商品）。 */
  public static final String CLOTH_COMMODITY_ID = "cloth";

  /** 纤维的商品 id（R3）：农田的副产物（亚麻/秸秆），也是织机与工坊的原料。 **内生于土地**（由农业配方一并产出，不凭空造）。 */
  public static final String FIBER_COMMODITY_ID = "fiber";

  /** 工具的商品 id（R3）：工坊的产品。 */
  public static final String TOOL_COMMODITY_ID = "tool";

  /** 铁的商品 id（R3）：工坊的另一种原料（本轮无冶炼流程 ⇒ 只来自创世给的初始库存）。 */
  public static final String IRON_COMMODITY_ID = "iron";

  /** 木材的商品 id（R3）：本轮**只进词表**（没有任何配方用它），留给后续的建材/燃料增量。 */
  public static final String WOOD_COMMODITY_ID = "wood";

  /** 1 **单位商品** = 1000 最小计量单位（粮 ⇒ 毫粮、布 ⇒ 毫匹、工具 ⇒ 毫件……**与商品无关**）。 */
  public static final long MILLI_PER_COMMODITY_UNIT = 1000L;

  /**
   * 1 粮 = 1000 毫粮：{@link #MILLI_PER_COMMODITY_UNIT} 的**粮别名**（历史名，端到端夹具与本类的口粮函数都用它）。
   *
   * <p>★ 两者是同一件事的两个名字（值也必须相同，由 {@code EconomyVocabularyTest} 钉住）—— 不合并是因为"毫粮"在库房/口粮的 语境里读起来比"每单位
   * 1000"清楚，而"每商品单位 1000"才是 V7 多商品口径里的那个数。
   */
  public static final long MILLI_PER_GRAIN = MILLI_PER_COMMODITY_UNIT;

  /** 口粮口径的**分子**（毫粮/人）：每人每 {@link #RATION_CYCLE_DAYS} 天吃 10 粮 = 10,000 毫粮（v2 spec §十"消费"行）。 */
  public static final long RATION_MILLI_PER_PERSON = 10_000L;

  /** 口粮口径的**分母**（天）：一个口粮周期 = 120 天（与 {@code EconomySeeder.CYCLE_DAYS} 数值相同、**语义无关**）。 */
  public static final long RATION_CYCLE_DAYS = 120L;

  /**
   * ★★ **衣着口径的分子**（毫布/人）：每人每 {@link #CLOTH_CYCLE_DAYS} 天 1 匹布 = 1,000 毫布。
   *
   * <p>★ **为什么是"1 匹/年"**：前现代一户人家一年添一身衣裳的量级；它是**判断结果**（spec §十一：出生率/死亡率的默认值都给不出， 属 GM 可调参数）⇒
   * 这里只钉一个**量级合理、可被 R4 改**的默认值，并明说它**不参与任何结算**（本轮只写进 {@code naturalNeeds}）。
   *
   * <p>★ **为什么与粮的口粮周期不同（365 ≠ 120）**：这正是 spec §七 那句话的落点 —— 粮食不足与衣物不足对死亡的时间尺度
   * 本来就不一样，两条口径各带自己的周期，不许共用一个"每人每周期吃/穿多少"的常量。
   */
  public static final long CLOTH_MILLI_PER_PERSON = 1_000L;

  /** 衣着口径的分母（天）：一年（365 天）。★ 与 {@link #RATION_CYCLE_DAYS} **刻意不同**，见上。 */
  public static final long CLOTH_CYCLE_DAYS = 365L;

  private EconomyVocabulary() {}

  /**
   * ★★ <b>全部商品 id（<b>保序</b>：粮 → 布 → 纤维 → 工具 → 铁 → 木）—— <b>含留位商品</b></b>（H5 ④）。
   *
   * <p>★★ <b>它为什么必须有</b>（用户裁定"铁留作留位"的同一条要求）：本仓禁"看起来在记、其实永远不被读"的字段 —— 而 <b>H5 起 {@link
   * #IRON_COMMODITY_ID} 不再进任何配方</b>（作坊的投入由铁改成工具，见 {@code
   * EconomySeeder.TOOL_MILLI_PER_WORKSHOP_CYCLE}）：它仍是词表里的一个商品、仍由创世给一份库存、 仍在价格表里 ——
   * 若没有一个读口列出"这个世界有哪些商品"，它就成了**没人读得到的孤字面量**。 ⇒ 本方法给出那份清单，{@code ApiViews} 把它发进经济读口（{@code
   * commodityIds}）。
   *
   * <p>★ <b>留位与在用的分界（如实记，不假装）</b>：
   *
   * <ul>
   *   <li>**在用**：{@code grain} / {@code cloth}（自然需求 + 配方）/ {@code fiber}（农田副产 + 织机与作坊的投入）/ {@code
   *       tool}（作坊的产出**与投入** —— H5 ④ 起自产自用）；
   *   <li>**留位**：{@code iron}（★ H5 起**没有任何配方读它**；等冶炼流程）/ {@code wood}（R3 起只进词表， 等建材/燃料增量）。
   * </ul>
   *
   * <p>★ <b>为什么是"含留位"的完整清单而不是"在用商品"清单</b>：读口要回答的是"世界里存在哪些商品"（进账本、进价格表、 进守恒式的那一套），而"哪一条配方读它"是另一个问题（读
   * {@code Industry.inputPerUnit/outputPerUnit} 就看得出来）。 把留位项藏起来，等于把"铁到底是留位还是没人知道的死字面量"这件事从读口抹掉。
   *
   * <p>★ 返回的是**保序**的不可变清单（声明序 = 本类的常量序）：调用方可以直接当"词表序"用（读口的键序要求）。
   */
  public static java.util.List<String> allCommodityIds() {
    return java.util.List.of(
        GRAIN_COMMODITY_ID,
        CLOTH_COMMODITY_ID,
        FIBER_COMMODITY_ID,
        TOOL_COMMODITY_ID,
        IRON_COMMODITY_ID,
        WOOD_COMMODITY_ID);
  }

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

  /**
   * **累计衣着需求**（毫布）：{@code population} 人 **{@code days} 天** = {@code 人口 × 1,000 × 天 ÷ 365}（向下取整）。
   *
   * <p>★ 与 {@link #cumulativeRationMilli} **同制**（累计 + 逐日差分），只是换了一套口径的分子/分母 —— 两个函数共用一条纪律： 需求是"人 ×
   * 天"的函数，**不是**"每人每天多少"乘天数。
   *
   * @param population 人口（人）；不得为负
   * @param days 天数（天）；不得为负
   * @throws IllegalArgumentException 人口或天数为负
   */
  public static long cumulativeClothMilli(long population, long days) {
    if (population < 0L) {
      throw new IllegalArgumentException("累计衣着需求的人口不得为负: " + population);
    }
    if (days < 0L) {
      throw new IllegalArgumentException("累计衣着需求的天数不得为负: " + days);
    }
    return population * CLOTH_MILLI_PER_PERSON * days / CLOTH_CYCLE_DAYS;
  }

  /**
   * **第 {@code day} 天的当日衣着需求**（毫布）= {@code 累计(day) − 累计(day − 1)} —— 与 {@link #dailyRationMilli}
   * 同制的逐日差分。
   *
   * @param population 人口（人）；不得为负
   * @param day **绝对日号**（1 起）
   * @throws IllegalArgumentException 人口为负或 {@code day < 1}
   */
  public static long dailyClothNeedMilli(long population, long day) {
    if (day < 1L) {
      throw new IllegalArgumentException("当日衣着需求的日号必须 ≥ 1: " + day);
    }
    return cumulativeClothMilli(population, day) - cumulativeClothMilli(population, day - 1L);
  }

  /**
   * ★★ **第 {@code day} 天的全套自然需求**（R3 的 T1：**每一商品一条需求**）：{@code 商品 id → 当日毫单位}。
   *
   * <pre>
   * { "grain": dailyRationMilli(人口, day), "cloth": dailyClothNeedMilli(人口, day) }
   * </pre>
   *
   * <p>★★ **它是结算写 {@code ClassRow.naturalNeeds} 的唯一入口**（v2 spec §八.8 的"读数与结算同源"）：读口因此看得见"这一格
   * 的人一天要几毫粮、几毫布"，而不是只有粮一个数。★ 返回的是**保序**的 {@code LinkedHashMap}（词表序 = 粮、布）：冻结与迭代序
   * 都由调用方负责，本方法只保证"同一个入参给出同一个序"。
   *
   * <p>★ **本轮这张表里只有粮与布**：工具/铁/木是**生产资料与中间品**，不是"自然需求"（spec §三 的 naturalNeeds 只作
   * "生存/再生产"两档；更高档的需求要等市场与价格 = R4+）。新增一档就在这里加一行 —— 那是本方法存在的理由。
   *
   * @param population 人口（人）；不得为负
   * @param day **绝对日号**（1 起）
   */
  public static Map<String, Long> dailyNeedsMilli(long population, long day) {
    Map<String, Long> needs = new LinkedHashMap<>();
    needs.put(GRAIN_COMMODITY_ID, dailyRationMilli(population, day));
    needs.put(CLOTH_COMMODITY_ID, dailyClothNeedMilli(population, day));
    return needs;
  }
}
