package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** D3 §2.5.1.0：只输出下一 tick 可交付的增量与收入最早可消费 tick。 */
public record NextTickIncomeProjectionResult(
    Map<CurrencyId, Long> expectedMoneyIncomeMilli,
    Map<CommodityId, Long> expectedInKindMilli,
    Map<CommodityId, Long> expectedProductShareMilli,
    long incomeAvailableAtTick) {

  public NextTickIncomeProjectionResult {
    expectedMoneyIncomeMilli = freezeMoney(expectedMoneyIncomeMilli, "expectedMoneyIncomeMilli");
    expectedInKindMilli = freezeCommodity(expectedInKindMilli, "expectedInKindMilli");
    expectedProductShareMilli =
        freezeCommodity(expectedProductShareMilli, "expectedProductShareMilli");
    if (incomeAvailableAtTick < 0L) {
      throw new IllegalArgumentException("incomeAvailableAtTick 不得为负: " + incomeAvailableAtTick);
    }
  }

  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null");
    LinkedHashMap<CurrencyId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 币种");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CommodityId, Long> freezeCommodity(
      Map<CommodityId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null");
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 商品");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }
}
