package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** D3 §2.5.1：当前已入库可用库存与可花货币的只读事实（不是预计收入）。 */
public record SurvivalResourceFacts(
    Map<CommodityId, Long> usableInventoryMilli, Map<CurrencyId, Long> spendableMoneyMilli) {
  public SurvivalResourceFacts {
    usableInventoryMilli =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeCommodity(usableInventoryMilli, "usableInventoryMilli")));
    spendableMoneyMilli =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeCurrency(spendableMoneyMilli, "spendableMoneyMilli")));
  }

  private static Map<CommodityId, Long> freezeCommodity(
      Map<CommodityId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null（无库存给空表）");
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 商品");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value < 0L) {
            throw new IllegalArgumentException(name + " 的数量不得为负: " + key + "=" + value);
          }
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CurrencyId, Long> freezeCurrency(Map<CurrencyId, Long> source, String name) {
    Objects.requireNonNull(source, name + " 不得为 null（无货币给空表）");
    LinkedHashMap<CurrencyId, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, name + " 不得含 null 币种");
          Objects.requireNonNull(value, name + " 不得含 null 数量");
          if (value < 0L) {
            throw new IllegalArgumentException(name + " 的数量不得为负: " + key + "=" + value);
          }
          if (value > 0L) {
            copy.put(key, value);
          }
        });
    return Collections.unmodifiableMap(copy);
  }
}
