package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D3 §2.5.1：一个可行生计替代。{@code expected*} 仅表示 {@code incomeAvailableAtTick} 那个 tick 可交付的增量，
 * 不是周期累计；evaluator 只把 {@code <= nextTickNeed.forTick} 的收入计入本 tick 覆盖。
 */
public record LivelihoodAlternative(
    LivelihoodAlternativeId id,
    long laborMilli,
    Set<ResourceClaimKey> resourceClaims,
    long incomeAvailableAtTick,
    Map<CurrencyId, Long> expectedMoneyIncomeMilli,
    Map<CommodityId, Long> expectedInKindMilli,
    Map<CommodityId, Long> expectedProductShareMilli) {

  public LivelihoodAlternative {
    Objects.requireNonNull(id, "LivelihoodAlternative.id 不得为 null");
    Objects.requireNonNull(resourceClaims, "LivelihoodAlternative.resourceClaims 不得为 null");
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("LivelihoodAlternative.laborMilli 不得为负: " + laborMilli);
    }
    if (incomeAvailableAtTick < 0L) {
      throw new IllegalArgumentException(
          "LivelihoodAlternative.incomeAvailableAtTick 不得为负: " + incomeAvailableAtTick);
    }
    LinkedHashSet<ResourceClaimKey> claims = new LinkedHashSet<>();
    for (ResourceClaimKey claim : resourceClaims) {
      Objects.requireNonNull(claim, "LivelihoodAlternative.resourceClaims 不得含 null");
      claims.add(claim);
    }
    resourceClaims = Collections.unmodifiableSet(claims);
    expectedMoneyIncomeMilli = freezeMoney(expectedMoneyIncomeMilli, "expectedMoneyIncomeMilli");
    expectedInKindMilli = freezeCommodity(expectedInKindMilli, "expectedInKindMilli");
    expectedProductShareMilli =
        freezeCommodity(expectedProductShareMilli, "expectedProductShareMilli");
    for (CommodityId commodity : expectedInKindMilli.keySet()) {
      if (expectedProductShareMilli.containsKey(commodity)) {
        throw new IllegalArgumentException(
            "survival-income-double-count: 同一替代的同一商品不得同时出现在 expectedInKindMilli 与 expectedProductShareMilli: "
                + commodity.value());
      }
    }
  }

  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null（无收入给空表）");
    LinkedHashMap<CurrencyId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 币种");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value < 0L) {
            throw new IllegalArgumentException(name + " 不得为负: " + key + "=" + value);
          }
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CommodityId, Long> freezeCommodity(
      Map<CommodityId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null（无收入给空表）");
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 商品");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value < 0L) {
            throw new IllegalArgumentException(name + " 不得为负: " + key + "=" + value);
          }
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }
}
