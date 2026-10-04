package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>订单未成交的原因词表</b>（M2.3/M2.4）：一条买/卖订单在**本轮撮合结束后仍有剩余**时，必须能说清它为什么没成 ——"有货卖不掉"是市场问题、
 * "买不起"是购买力问题、"运力不足"是物流问题，三者的处置完全不同（MASTER M0.4 的七档归因同一条纪律）。
 *
 * <p>★★ <b>为什么放在 {@code economy-api}</b>：它是 M2.7 读数面的稳定契约（L3 的逐区读数组件会按这些代码分档出表）， 而 {@code
 * simos-economy} 只负责产生它们。★ 字面量一律小写下划线（与 {@code TransferReason}/{@code ResidenceKind} 同制）， {@link
 * #parse} 不做归一——写错一个档必须当场炸并看得见全部合法值。
 *
 * <p>★ <b>本批的七档</b>（逐档对齐 M0.4 的归因口径）：
 *
 * <ul>
 *   <li>{@link #NO_SELLER} —— 这一格/这一区**没有任何卖方**（持有人也没有可卖余量）；对应 M0.4 的 {@code
 *       SUPPLY_ZERO_NO_SELLER}；
 *   <li>{@link #ALL_RESERVED} —— 有人持有，但全部被"必要生产投入 + 生活保留 + 已冻结"吃掉（M2.0 甲的退休口径）；对应 {@code
 *       SUPPLY_ZERO_ALL_RESERVED}；
 *   <li>{@link #NO_BUDGET} —— 有缺口、有货，但**买方的可花预算为 0**（没钱的缺口不是有效需求）；对应 {@code NO_PURCHASING_POWER}；
 *   <li>{@link #PRICE_LIMIT} —— 有货、有钱，但**限价过滤**掉了（卖方底价高于买方限价）；对应 {@code LOCAL_SUPPLY_SHORT} 的价差那一支；
 *   <li>{@link #LOGISTICS_CAPACITY} —— ★★ 有货、有路、**运力不足**（M2.4 的判据）：跨区候选存在且未成交的买卖两边都有， 但一个市场窗口的
 *       {@code capacityPerWindow} 用尽；
 *   <li>{@link #LOGISTICS_TIME} —— 有货、有路、有运力，但**到货日超过了买方的时限**（{@code latestArrivalTick}）；
 *   <li>{@link #NO_ROUTE} —— 两格之间地形不可通行（{@code moveCost >= IMPASSABLE_MOVE_COST}）⇒ 没有可用路线；
 *   <li>{@link #NO_ADJACENT_SUPPLY} —— 本区没有卖方，也没有**直接邻接**的供应区（M2.3 第一版的稀疏跨区口径）；
 *   <li>{@link #ALGORITHM_UNCOVERED} —— 以上都不成立（留给"算法没覆盖到"的兜底，不静默当成成交）。
 * </ul>
 *
 * <p>★ 另有 {@link #NO_BUYER}（卖方视角：有货但全市场没有人买得起/需要）单独一档，避免把"卖方的剩余"硬塞进买方的档位。
 */
public enum MarketUnfilledReason {

  /** 这一格/这一区没有任何卖方。 */
  NO_SELLER("no_seller"),

  /** 有人持有但全部被必要投入/生活保留/已冻结占住。 */
  ALL_RESERVED("all_reserved"),

  /** 有缺口有货，但买方的可花预算为 0。 */
  NO_BUDGET("no_budget"),

  /** 限价过滤：卖方底价高于买方限价。 */
  PRICE_LIMIT("price_limit"),

  /** ★ 有货有路，运力不足（物流瓶颈）。 */
  LOGISTICS_CAPACITY("logistics_capacity"),

  /** 有货有路有运力，但到货日超过买方时限。 */
  LOGISTICS_TIME("logistics_time"),

  /** 两格之间地形不可通行。 */
  NO_ROUTE("no_route"),

  /** 本区无卖方，也没有直接邻接的供应区（第一版跨区口径）。 */
  NO_ADJACENT_SUPPLY("no_adjacent_supply"),

  /** 卖方视角：有余货，但没有买方（无需求或都买不起）。 */
  NO_BUYER("no_buyer"),

  /**
   * ★ <b>S3：买方库存已足、根本不应挂单</b>—— {@code gapQty == 0}（生活保留 / 必要投入已被自有库存与在途覆盖）。
   *
   * <p>★ 它与"挂了单但没买到"是两件事：这一档的量<b>不是</b>卖方的失败（不把"没下单"算成卖方没卖掉）。
   */
  STOCK_SUFFICIENT("stock_sufficient"),

  /**
   * ★ <b>S3：卖方被同商品更低到货价的卖方挤掉</b>—— 同区/邻区存在 {@code unitCostEstimate + freightPerUnit}
   * 更低、且真的成交/仍有供给的卖方，本卖方的剩余因此没卖掉。★ 读数的 {@code outcompetedBy} 记下"被几个更便宜的卖方挤掉"。
   */
  OUTCOMPETED("outcompeted"),

  /**
   * ★ <b>S3：生产投入不足</b>—— {@code Industry.cycleInputUsedMilli} 低于本周期满规模所需，或 {@code capacityScaleOf}
   * 被投入那一路压低。★ 这是<b>生产侧</b>原因，不是市场原因；它让"卖不动"与"根本没产出来"在读数上分开。
   */
  INPUT_SHORTFALL("input_shortfall"),

  /** ★ <b>S3：产品卖不出去但仍可自用</b>—— 卖方的库存足以覆盖自身保留量 / 下一周期投入 / 家庭消费，未卖掉不等于无法再生产。 */
  UNSOLD_SELF_USABLE("unsold_self_usable"),

  /**
   * ★ <b>S3：真正无法维持再生产</b>—— 连续周期满足 {@code ReproductionStress} 阈值，且库存、借款、资产份额等缓冲全部耗尽。 ★
   * 只有这一档才允许走向停业/退出；它不能由单轮滞销直接推出（那是 {@link #UNSOLD_SELF_USABLE} 或 {@link #OUTCOMPETED}）。
   */
  CANNOT_REPRODUCE("cannot_reproduce"),

  /** 算法没有覆盖到的剩余（兜底，不静默）。 */
  ALGORITHM_UNCOVERED("algorithm_uncovered"),

  /**
   * ★ <b>D-027：市场总调控配额用尽</b>—— 本轮该区该商品的卖方成交量已达 {@code MarketRegulation.quotaPerWindow}
   * 的上限，超出的买方需求因此没成交。★ 它是<b>区级制度原因</b>，与逐 hex 的物流成本（{@code HexTradeCost} 的实物损耗）
   * 是两层：这一档不得被用来表达"路远/运力不足"。
   */
  REGULATION_QUOTA("regulation_quota"),

  /**
   * ★★ <b>D-030/D-031 市场信用：可借头寸凑不成正交易</b>—— 两个可借池都还有剩余，但剩余头寸不足以形成一笔正金额的
   * 贷款/实物借出；或现金与信用都无法覆盖该买方剩余。★ D-031 起它<b>不再表示"借款人额度为 0"</b>（借款人侧已经没有
   * 额度上限；不存在 headroom 这一道门）。
   */
  NO_CREDIT_LIMIT("no_credit_limit"),

  /**
   * ★★ <b>D-030 市场信用：货币可借池为空</b>—— 买方仍有缺口，但本区没有任何出借人余额扣除保留额后还有可借额
   * （或可借额不足以形成一笔正金额的贷款）。实物借贷是紧随其后的第二步，本档只在实物也补不上该缺口时落。
   */
  NO_LENDABLE_MONEY("no_lendable_money"),

  /**
   * ★★ <b>D-030 市场信用：商品可借池为空</b>—— 现金成交后该商品的卖单剩余里没有**可成为债权人的卖家**（本批 = 家户
   * 卖家）的可借头寸，或压根没有剩余；货币借贷必须绑定"买得到这批货"，无货可买 ⇒ 不放贷。
   */
  NO_LENDABLE_GOODS("no_lendable_goods");

  private final String value;

  MarketUnfilledReason(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进读数/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = 声明序）；由枚举常量派生，供遍历与断言用。 */
  public static List<MarketUnfilledReason> all() {
    return List.of(values());
  }

  /**
   * ★★ <b>M0.3 的"物流缺口"这一档的原因集合</b>（M2.7 复评落点）：买方的剩余是因为<b>运不进来</b>，而不是"没有货"或"没钱"—— 四档一起构成 logistic
   * gap：{@link #LOGISTICS_CAPACITY}（运力不足）、{@link #LOGISTICS_TIME}（到货超时限）、 {@link
   * #NO_ROUTE}（地形不可通行）、{@link #NO_ADJACENT_SUPPLY}（本区无卖方且没有直接邻接供应区）。
   *
   * <p>★ 它的用途是<b>归因分档</b>，不是"把四档合并成一个数"：报表里四档仍各自可见，本方法只回答"这一条算不算物流那一类"。
   */
  public boolean logistics() {
    return this == LOGISTICS_CAPACITY
        || this == LOGISTICS_TIME
        || this == NO_ROUTE
        || this == NO_ADJACENT_SUPPLY;
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static MarketUnfilledReason parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketUnfilledReason 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (MarketUnfilledReason reason : values()) {
      if (reason.value.equals(text)) {
        return reason;
      }
      legal.add(reason.value);
    }
    throw new IllegalArgumentException("未登记的未成交原因: " + text + "；合法值: " + legal);
  }
}
