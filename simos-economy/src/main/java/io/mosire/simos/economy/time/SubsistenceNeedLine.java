package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Objects;

/** D3 §2.5.1：一条下一 tick 的逐需求行；{@code commodity} 是满足该行的具体商品。 */
public record SubsistenceNeedLine(
    SubsistenceNeedKey key, CommodityId commodity, long requiredQuantityMilli) {
  public SubsistenceNeedLine {
    Objects.requireNonNull(key, "SubsistenceNeedLine.key 不得为 null");
    Objects.requireNonNull(commodity, "SubsistenceNeedLine.commodity 不得为 null");
    if (requiredQuantityMilli < 0L) {
      throw new IllegalArgumentException(
          "SubsistenceNeedLine.requiredQuantityMilli 不得为负: " + requiredQuantityMilli);
    }
  }
}
