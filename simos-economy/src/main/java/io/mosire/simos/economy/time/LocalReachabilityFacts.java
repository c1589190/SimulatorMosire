package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** D3 §2.5.1：逐商品当地可达事实；缺项 = 该商品不能用货币腿覆盖（不是 0 价/1:1）。 */
public record LocalReachabilityFacts(Map<CommodityId, LocalCommodityReachability> byCommodity) {
  public LocalReachabilityFacts {
    Objects.requireNonNull(byCommodity, "LocalReachabilityFacts.byCommodity 不得为 null");
    LinkedHashMap<CommodityId, LocalCommodityReachability> copy = new LinkedHashMap<>();
    byCommodity.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "LocalReachabilityFacts 不得含 null 商品");
          Objects.requireNonNull(value, "LocalReachabilityFacts 不得含 null 事实");
          copy.put(key, value);
        });
    byCommodity = Collections.unmodifiableMap(copy);
  }
}
