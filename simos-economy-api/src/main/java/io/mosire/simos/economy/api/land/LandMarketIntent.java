package io.mosire.simos.economy.api.land;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>一张标量 LAND 市场意向</b>（D-032 RA1a）：{@code (actor, hex, side, quantityMilliMu,
 * unitPriceMilliPerMu)}。
 *
 * <p>★★ <b>0 价合法，且不等于未定价</b>：{@code unitPriceMilliPerMu == 0} 是正常的市场成交价格（用户 2026-10-19
 * 「土地价为0就不能转移了吗？」）；“未定价 vs 0 价”的持久语义不在本类型，留给 RA1b/市场层。
 *
 * <p>★ 本类型只表达意向：不装旧资产份额 id、不冻结预算、不预留数量、不生成订单身份。数量必须严格为正， 单价不得为负；成交、预算与债务由市场层在 RA1b/RA3 处理。
 *
 * @param actor 挂单家户；不得为 null
 * @param hex 土地所在格；不得为 null
 * @param side 买/卖；不得为 null
 * @param quantityMilliMu 意向数量（千分亩）；必须严格为正
 * @param unitPriceMilliPerMu 单价（毫币/整亩）；必须 {@code >= 0}，0 合法
 */
public record LandMarketIntent(
    HouseholdId actor,
    HexCoord hex,
    LandMarketSide side,
    long quantityMilliMu,
    long unitPriceMilliPerMu) {

  public LandMarketIntent {
    Objects.requireNonNull(actor, "LandMarketIntent.actor 不得为 null");
    Objects.requireNonNull(hex, "LandMarketIntent.hex 不得为 null");
    Objects.requireNonNull(side, "LandMarketIntent.side 不得为 null");
    if (quantityMilliMu < 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_NEGATIVE_QUANTITY,
          "LandMarketIntent.quantityMilliMu 不得为负: " + quantityMilliMu);
    }
    if (quantityMilliMu == 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_ZERO_QUANTITY, "LandMarketIntent.quantityMilliMu 必须 > 0: 0");
    }
    if (unitPriceMilliPerMu < 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_ORDER_INVALID,
          "LandMarketIntent.unitPriceMilliPerMu 不得为负: " + unitPriceMilliPerMu);
    }
  }
}
