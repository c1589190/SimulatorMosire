package io.mosire.simos.economy.api.land;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>一笔同 hex、任意数量的土地持有转移</b>（D-032 RA1a）。
 *
 * <p>★★ <b>不跨 hex</b>：一次转移只改同一个 hex 内两个家户的数量。跨 hex 换地是两笔独立交易，不能用一个“换地”动作 绕过每 hex 总量守恒。
 *
 * <p>★ <b>无旧资产份额 id、无旧块编号</b>：只表达“fromOwner 在这个 hex 把 quantityMilliMu 转给 toOwner”。
 * 数量必须严格为正；源家户不足、hex 未知等由纯账本 {@code LandBook#apply} 具名拒绝。
 *
 * @param hex 土地所在格；不得为 null
 * @param fromOwner 转出方；不得为 null
 * @param toOwner 转入方；不得为 null，且必须与 {@code fromOwner} 不同
 * @param quantityMilliMu 转移量（千分亩）；必须严格为正
 */
public record LandTransfer(
    HexCoord hex, HouseholdId fromOwner, HouseholdId toOwner, long quantityMilliMu) {

  public LandTransfer {
    Objects.requireNonNull(hex, "LandTransfer.hex 不得为 null");
    Objects.requireNonNull(fromOwner, "LandTransfer.fromOwner 不得为 null");
    Objects.requireNonNull(toOwner, "LandTransfer.toOwner 不得为 null");
    if (fromOwner.equals(toOwner)) {
      throw new LandViolationException(
          LandFailureReason.LAND_SAME_OWNER,
          "LandTransfer.fromOwner 与 toOwner 相同: " + fromOwner.value());
    }
    if (quantityMilliMu < 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_NEGATIVE_QUANTITY,
          "LandTransfer.quantityMilliMu 不得为负: " + quantityMilliMu);
    }
    if (quantityMilliMu == 0L) {
      throw new LandViolationException(
          LandFailureReason.LAND_ZERO_QUANTITY, "LandTransfer.quantityMilliMu 必须 > 0: 0");
    }
  }
}
