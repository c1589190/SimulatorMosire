package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.GovernmentId;
import java.util.Objects;

/**
 * ★★ <b>一条政府市场授权 = 一次「明确授权下单」</b>（R1：政府的行政家户回到商品市场，且<b>只按授权下单</b>）。
 *
 * <p>★★ <b>为什么是它、不是政策层</b>（用户原话，见约束设计书 §1.3）：
 *
 * <blockquote>
 *
 * 「原本我预想的政府经济调整，除了口岸是直接用机制作用在市场区上实现……其他调整，包括压低价格/收购等都是通过政府的行政家户
 * 或者任意指定来实现的，<b>你不会给做成额外再加一个调整机制吧？</b>」
 *
 * </blockquote>
 *
 * <p>⇒ 政府的经济行为只有一条落点：<b>像任何家户一样挂单</b>（G8）。本类型就是那条"挂单"的<b>授权凭据</b>： 它不改变价格、不改变成本、不豁免任何市场规则；它只回答四个问题
 * —— <b>谁（哪个政府的国库家户）、什么商品、哪个方向、多少量、什么限价、有效到哪一天</b>。 订单仍在既有的 {@code 家户→订单→撮合→结算}
 * 全链路里生成与成交，账户主体恒是家户（铁律：账户主体只有家户）。
 *
 * <p>★★ <b>与"自动订单"的区别（本批的核心判据）</b>：国库户<b>不</b>因自然需求/库存差异自动生成任何买单或卖单 （它不是无意识的买家/卖家， 见 {@code
 * GovernmentMarketMandatePlan}）；能出现在市场上的国库户订单<b>全部</b>来自本表的授权。
 *
 * <p>★★ <b>生命周期（不许留永久挂单）</b>：
 *
 * <ul>
 *   <li><b>到期</b>：{@code expiresOnDay} 是最后一个生效日（含当日），{@code day > expiresOnDay} ⇒ 该轮不再下单，
 *       并由状态层<b>清除</b>该行（ {@code expiresOnDay < 0} 在本批<b>不允许</b>：永久授权 = 永久挂单）；
 *   <li><b>耗尽</b>：{@code filledMilli} 累计到 {@code quantityMilli} ⇒ 状态层<b>清除</b>该行；
 *   <li><b>幂等</b>：同 id 重复注入形状逐值相同的授权 ⇒ no-op（保留 {@code filledMilli}），绝不双倍下单。
 * </ul>
 *
 * <p>★★ <b>量纲</b>：{@code quantityMilli} / {@code filledMilli} = 毫商品单位；{@code limitPriceMilli} =
 * 毫计价货币 <b>每商品单位</b>（与市场价表 {@code Market.prices()} 同量纲）。★ 它<b>只是限价</b>：成交价仍由市场按参考价裁定 （区内 = 本格参考价 /
 * 跨区 = 卖方格参考价），government 的授权<b>不</b>改价。
 *
 * <p>★★ <b>方向语义</b>：
 *
 * <ul>
 *   <li>{@link Side#BUY}（收购/压价）：以<b>不高于</b> {@code limitPriceMilli} 的价格买入（实际生效限价还受市场自身买方限价约束，
 *       取二者更严者）；
 *   <li>{@link Side#SELL}（抛售）：以<b>不低于</b> {@code limitPriceMilli} 的价格卖出（实际生效限价亦受市场自身卖方底价约束，
 *       取二者更严者）。
 * </ul>
 *
 * <p>★ <b>不变量（构造期判死，不静默归一）</b>：见 compact 构造器。
 *
 * @param id 稳定身份；不得为 null
 * @param government 授权主体政府（国库家户由 {@code Government.treasury()} 唯一确定）；不得为 null
 * @param commodity 商品；不得为 null
 * @param side 方向；不得为 null
 * @param quantityMilli 授权总量（毫商品单位）；必须 &gt; 0
 * @param limitPriceMilli 限价（毫计价货币/单位）；必须 ≥ 1（本批不接受"无限价授权"）
 * @param authorizedDay 授权生效起始日（含）；必须 ≥ 0
 * @param expiresOnDay 最后一个生效日（含）；必须 ≥ {@code authorizedDay}
 * @param filledMilli 已成交累计（毫商品单位）；必须 ∈ [0, {@code quantityMilli}]
 * @param source 授权来源标签（谁授权的：{@code gm:…} / {@code gov:…}）；不得为空白
 */
public record GovernmentMarketMandate(
    MarketMandateId id,
    GovernmentId government,
    CommodityId commodity,
    Side side,
    long quantityMilli,
    long limitPriceMilli,
    long authorizedDay,
    long expiresOnDay,
    long filledMilli,
    String source) {

  /** 挂单方向：{@code BUY} = 收购（价格上限），{@code SELL} = 抛售（价格下限）。 */
  public enum Side {
    BUY,
    SELL
  }

  public GovernmentMarketMandate {
    Objects.requireNonNull(id, "GovernmentMarketMandate.id 不得为 null");
    Objects.requireNonNull(government, "GovernmentMarketMandate.government 不得为 null");
    Objects.requireNonNull(commodity, "GovernmentMarketMandate.commodity 不得为 null");
    Objects.requireNonNull(side, "GovernmentMarketMandate.side 不得为 null");
    Objects.requireNonNull(source, "GovernmentMarketMandate.source 不得为 null");
    if (source.isBlank()) {
      throw new IllegalArgumentException("GovernmentMarketMandate.source 不得为空白: " + id.value());
    }
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.quantityMilli 必须 > 0（0/负量不是一次授权，是一条假记录）: " + id.value());
    }
    if (limitPriceMilli <= 0L) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.limitPriceMilli 必须 ≥ 1（本批不接受无限价授权："
              + "限价是授权的必填维度）: "
              + id.value()
              + " 实际="
              + limitPriceMilli);
    }
    if (authorizedDay < 0L) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.authorizedDay 不得为负: " + id.value() + " 实际=" + authorizedDay);
    }
    if (expiresOnDay < authorizedDay) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.expiresOnDay 必须 ≥ authorizedDay（不许出生即过期的授权）: "
              + id.value()
              + " authorizedDay="
              + authorizedDay
              + " expiresOnDay="
              + expiresOnDay);
    }
    if (filledMilli < 0L || filledMilli > quantityMilli) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.filledMilli 必须 ∈ [0, quantityMilli]: "
              + id.value()
              + " filled="
              + filledMilli
              + " quantity="
              + quantityMilli);
    }
  }

  /** 距授权总量还差多少（毫商品单位）；耗尽时为 0。 */
  public long remainingMilli() {
    return quantityMilli - filledMilli;
  }

  /** 授权量是否已耗尽（成交累计达到授权总量）。 */
  public boolean exhausted() {
    return filledMilli >= quantityMilli;
  }

  /** ★ day 当天这条授权是否生效（含首尾两日）：{@code authorizedDay ≤ day ≤ expiresOnDay}。 */
  public boolean effectiveOn(long day) {
    return day >= authorizedDay && day <= expiresOnDay;
  }

  /** ★ day 当天这条授权是否已到期（{@code day > expiresOnDay}）；到期行由状态层清除。 */
  public boolean expiredOn(long day) {
    return day > expiresOnDay;
  }

  /**
   * 记一笔成交（毫商品单位）：{@code filledMilli + delta} 封顶到 {@code quantityMilli}。
   *
   * @param delta 本次成交累计（必须 ≥ 0；封顶保证不会因浮点/重复记账越界）
   */
  public GovernmentMarketMandate withFill(long delta) {
    if (delta < 0L) {
      throw new IllegalArgumentException(
          "GovernmentMarketMandate.withFill 的 delta 不得为负: " + id.value() + " delta=" + delta);
    }
    long next = Math.min(quantityMilli, Math.addExact(filledMilli, delta));
    if (next == filledMilli) {
      return this;
    }
    return new GovernmentMarketMandate(
        id,
        government,
        commodity,
        side,
        quantityMilli,
        limitPriceMilli,
        authorizedDay,
        expiresOnDay,
        next,
        source);
  }

  /**
   * ★ <b>幂等判据（不含成交量）</b>：授权的"形状"是否逐值相同 —— 同 id 重复注入时用它判 no-op， 故 {@code filledMilli}
   * <b>不进</b>比较（成交量是执行进度，不是授权内容）。
   */
  public boolean sameAuthorizationAs(GovernmentMarketMandate other) {
    Objects.requireNonNull(other, "other");
    return id.equals(other.id)
        && government.equals(other.government)
        && commodity.equals(other.commodity)
        && side == other.side
        && quantityMilli == other.quantityMilli
        && limitPriceMilli == other.limitPriceMilli
        && authorizedDay == other.authorizedDay
        && expiresOnDay == other.expiresOnDay
        && source.equals(other.source);
  }
}
