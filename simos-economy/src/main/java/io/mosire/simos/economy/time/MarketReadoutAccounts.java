package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>{@link MarketReadout} 的账户输入（读时派生的会话快照，不落盘、不改状态）</b>—— 四对余额/冻结表，键 = 家户身份或 经营者主体。
 *
 * <p>★★ <b>为什么单独一个类型</b>：读口（{@code simos-app} 的组合根）从 actor 侧载入这些表，economy 侧只吃纯 map；
 * 把八张表装进一个不可变记录，工厂的入参就不会是八个同型参数的"位置陷阱"。
 *
 * <p>★ <b>缺席 = 该主体在本读口看不见</b>：读时派生按缺失键跳过（不是"库存 0"）；家户侧缺席由 {@link MarketReadout} 的 {@code
 * unavailable.householdAccounts} 计数点名。
 *
 * @param householdGoods 家户商品余额（缺失键 = 该家户在本次读口缺席）
 * @param householdMoney 家户货币余额
 * @param householdFrozenGoods 家户商品冻结额
 * @param householdFrozenMoney 家户货币冻结额
 * @param operatorGoods 经营者商品余额
 * @param operatorMoney 经营者货币余额
 * @param operatorFrozenGoods 经营者商品冻结额
 * @param operatorFrozenMoney 经营者货币冻结额
 */
public record MarketReadoutAccounts(
    Map<CohortKey, Map<CommodityId, Long>> householdGoods,
    Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
    Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods,
    Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney,
    Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
    Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
    Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
    Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney) {

  public MarketReadoutAccounts {
    householdGoods = freezeGoods(householdGoods);
    householdFrozenGoods = freezeGoods(householdFrozenGoods);
    operatorGoods = freezeGoods(operatorGoods);
    operatorFrozenGoods = freezeGoods(operatorFrozenGoods);
    householdMoney = freezeMoney(householdMoney);
    householdFrozenMoney = freezeMoney(householdFrozenMoney);
    operatorMoney = freezeMoney(operatorMoney);
    operatorFrozenMoney = freezeMoney(operatorFrozenMoney);
  }

  /** 八张空表：单模块用例/无账户世界的合法形态（读时派生按"看不见"处理）。 */
  public static MarketReadoutAccounts empty() {
    return new MarketReadoutAccounts(
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  private static <K> Map<K, Map<CommodityId, Long>> freezeGoods(
      Map<K, Map<CommodityId, Long>> values) {
    if (values == null) {
      return Map.of();
    }
    Map<K, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<K, Map<CommodityId, Long>> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketReadoutAccounts 的商品表不得含 null 键/值");
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static <K> Map<K, Map<CurrencyId, Long>> freezeMoney(
      Map<K, Map<CurrencyId, Long>> values) {
    if (values == null) {
      return Map.of();
    }
    Map<K, Map<CurrencyId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<K, Map<CurrencyId, Long>> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketReadoutAccounts 的货币表不得含 null 键/值");
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(copy);
  }
}
