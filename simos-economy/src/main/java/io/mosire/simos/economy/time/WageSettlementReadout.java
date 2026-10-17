package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>逐义务工资结算读数</b>（D-032 D3 §6.7）：一笔 {@code (unit, role, worker)} 的 due 被拆成
 * 货币/抵物/欠款三部分，且构造期核对三数守恒。
 *
 * <pre>
 * dueMilli == paidMoneyMilli + Σ barterPaidValueMilli + arrearsMilli
 * </pre>
 *
 * <p>★ 读数与转移/债务合同同源：本记录只由 {@link WageSettlement} 在真实落账后构造，不另算第二份工资公式。
 */
public record WageSettlementReadout(
    ProductionUnitId unit,
    ProductionRoleId role,
    HouseholdId worker,
    HouseholdId payer,
    CurrencyId currency,
    long tickLaborMilli,
    long quoteWagePerLaborMilli,
    long carryBefore,
    long carryAfter,
    long dueMilli,
    long paidMoneyMilli,
    Map<CommodityId, Long> barterQuantity,
    Map<CommodityId, Long> barterPaidValueMilli,
    long arrearsMilli,
    Optional<DebtContractId> debtContractId,
    List<String> reasons) {

  public WageSettlementReadout {
    Objects.requireNonNull(unit, "WageSettlementReadout.unit 不得为 null");
    Objects.requireNonNull(role, "WageSettlementReadout.role 不得为 null");
    Objects.requireNonNull(worker, "WageSettlementReadout.worker 不得为 null");
    Objects.requireNonNull(payer, "WageSettlementReadout.payer 不得为 null");
    Objects.requireNonNull(currency, "WageSettlementReadout.currency 不得为 null");
    if (tickLaborMilli < 0L
        || quoteWagePerLaborMilli < 0L
        || carryBefore < 0L
        || carryBefore > 999L
        || carryAfter < 0L
        || carryAfter > 999L
        || dueMilli < 0L
        || paidMoneyMilli < 0L
        || arrearsMilli < 0L) {
      throw new IllegalArgumentException(
          "WageSettlementReadout 的数量/进位越域: labor="
              + tickLaborMilli
              + " quote="
              + quoteWagePerLaborMilli
              + " carry="
              + carryBefore
              + "→"
              + carryAfter
              + " due="
              + dueMilli
              + " money="
              + paidMoneyMilli
              + " arrears="
              + arrearsMilli);
    }
    barterQuantity = freeze(barterQuantity, "barterQuantity");
    barterPaidValueMilli = freeze(barterPaidValueMilli, "barterPaidValueMilli");
    Objects.requireNonNull(debtContractId, "WageSettlementReadout.debtContractId 不得为 null");
    Objects.requireNonNull(reasons, "WageSettlementReadout.reasons 不得为 null");
    reasons = List.copyOf(reasons);
    long barterValue = 0L;
    for (long value : barterPaidValueMilli.values()) {
      barterValue = Math.addExact(barterValue, value);
    }
    long accounted = Math.addExact(Math.addExact(paidMoneyMilli, barterValue), arrearsMilli);
    if (accounted != dueMilli) {
      throw new IllegalArgumentException(
          "WageSettlementReadout 三数不守恒: due="
              + dueMilli
              + " != paidMoney="
              + paidMoneyMilli
              + " + barter="
              + barterValue
              + " + arrears="
              + arrearsMilli);
    }
  }

  private static Map<CommodityId, Long> freeze(Map<CommodityId, Long> source, String field) {
    Objects.requireNonNull(source, field + " 不得为 null（没有腿给空表）");
    List<Map.Entry<CommodityId, Long>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Map.Entry.comparingByKey((left, right) -> left.value().compareTo(right.value())));
    Map<CommodityId, Long> frozen = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : entries) {
      Objects.requireNonNull(entry.getKey(), field + " 的键不得为 null");
      Objects.requireNonNull(entry.getValue(), field + " 的值不得为 null");
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(field + " 的数量不得为负: " + entry.getKey());
      }
      frozen.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(frozen);
  }
}
