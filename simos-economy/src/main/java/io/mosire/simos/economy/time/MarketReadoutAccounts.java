package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>{@link MarketReadout} 的账户输入（读时派生的会话快照，不落盘、不改状态）</b>—— 四张余额/冻结表，键 = 家户身份。
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
 */
public record MarketReadoutAccounts(
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
    Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
    Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney) {

  public MarketReadoutAccounts {
    // ★ 外层不可变包装写在赋值处（SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析，只认赋值点看得见的
    //   Collections.unmodifiableMap）；内层逐值防御性拷贝 + 不可变包装仍由 helper 完成。
    householdGoods = Collections.unmodifiableMap(copyGoods(householdGoods));
    householdFrozenGoods = Collections.unmodifiableMap(copyGoods(householdFrozenGoods));
    householdMoney = Collections.unmodifiableMap(copyMoney(householdMoney));
    householdFrozenMoney = Collections.unmodifiableMap(copyMoney(householdFrozenMoney));
  }

  /** 四张空表：单模块用例/无账户世界的合法形态（读时派生按"看不见"处理）。 */
  public static MarketReadoutAccounts empty() {
    return new MarketReadoutAccounts(Map.of(), Map.of(), Map.of(), Map.of());
  }

  private static <K> Map<K, Map<CommodityId, Long>> copyGoods(
      Map<K, Map<CommodityId, Long>> values) {
    if (values == null) {
      return new LinkedHashMap<>();
    }
    Map<K, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<K, Map<CommodityId, Long>> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketReadoutAccounts 的商品表不得含 null 键/值");
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return copy;
  }

  private static <K> Map<K, Map<CurrencyId, Long>> copyMoney(Map<K, Map<CurrencyId, Long>> values) {
    if (values == null) {
      return new LinkedHashMap<>();
    }
    Map<K, Map<CurrencyId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<K, Map<CurrencyId, Long>> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketReadoutAccounts 的货币表不得含 null 键/值");
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return copy;
  }
}
