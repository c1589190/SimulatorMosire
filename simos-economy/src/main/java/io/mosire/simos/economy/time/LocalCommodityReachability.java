package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/** D3 §2.5.1：某商品的家户可达购买价、币种与可达供给事实。 */
public record LocalCommodityReachability(
    CurrencyId currency, boolean reachable, long purchasePriceMilli, long reachableSupplyMilli) {
  public LocalCommodityReachability {
    Objects.requireNonNull(currency, "LocalCommodityReachability.currency 不得为 null");
    if (purchasePriceMilli < 0L) {
      throw new IllegalArgumentException(
          "LocalCommodityReachability.purchasePriceMilli 不得为负: " + purchasePriceMilli);
    }
    if (reachableSupplyMilli < 0L) {
      throw new IllegalArgumentException(
          "LocalCommodityReachability.reachableSupplyMilli 不得为负: " + reachableSupplyMilli);
    }
  }
}
