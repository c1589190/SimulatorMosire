package io.mosire.simos.economy.api.land;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>一个家户在一个 hex 上的标量土地持有</b>（D-032 RA1a 冻结形状，2026-10-19）。
 *
 * <p>★ <b>LAND 是标量商品</b>：同一户在同一 hex 只有一个数量，不存在旧资产份额 id、旧块编号， 也不允许“先拆成固定块再转移”。任意正数量都可转移；数量降到 0
 * 时由账本删除该键（账本不保留 0 行）。
 *
 * <p>★★ <b>每 hex 总量守恒的输入</b>：本记录只管“某户某格有多少”，总量权威在 {@link LandArableSupply}；两者必须由 {@code LandBook}
 * 的构造校验对齐（每一 hex 的持有合计 == 该 hex 固定可耕量）。
 *
 * @param owner 土地持有人；不得为 null
 * @param hex 土地所在格；不得为 null
 * @param quantityMilliMu 持有量（千分亩）；必须严格为正
 */
public record LandHolding(HouseholdId owner, HexCoord hex, long quantityMilliMu) {

  public LandHolding {
    Objects.requireNonNull(owner, "LandHolding.owner 不得为 null");
    Objects.requireNonNull(hex, "LandHolding.hex 不得为 null");
    if (quantityMilliMu < 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_NEGATIVE_QUANTITY,
          "LandHolding.quantityMilliMu 不得为负: " + quantityMilliMu);
    }
    if (quantityMilliMu == 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_ZERO_QUANTITY, "LandHolding.quantityMilliMu 必须 > 0（0 数量行不得存在）: 0");
    }
  }

  /** 稳定 map 键：先 hex 自然序、再持有人 id，不是任何“块身份”。 */
  public LandHoldingKey key() {
    return new LandHoldingKey(hex, owner);
  }
}
