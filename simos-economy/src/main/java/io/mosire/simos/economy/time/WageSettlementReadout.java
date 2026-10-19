package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>逐义务工资结算读数</b>（D-032 P4a）：一笔 {@code (unit, role, worker)} 的 due 被拆成 现金/欠款两部分，且构造期核对两数守恒。
 *
 * <pre>
 * dueMilli == paidMoneyMilli + arrearsMilli
 * </pre>
 *
 * <p>★ 读数与转移/债务合同同源：本记录只由 {@link WageSettlement} 在真实落账后构造，不另算第二份工资公式。
 *
 * <p>★ <b>P4a 起没有 barter/库存腿</b>：工资只付货币，未付余额只形成 {@code WAGE_ARREARS} 普通货币债。
 *
 * <p>★ <b>P4c2</b>：{@code anchorKey} 是本义务解析到的 U20 锚键（定不出市场区时为空）；它只用于锚级 matched
 * 聚合/读数，不把锚级目标或工资伪装成逐义务事实。
 */
public record WageSettlementReadout(
    ProductionUnitId unit,
    ProductionRoleId role,
    Optional<WageAnchorKey> anchorKey,
    HouseholdId worker,
    HouseholdId payer,
    CurrencyId currency,
    long tickLaborMilli,
    long quoteWagePerLaborMilli,
    long carryBefore,
    long carryAfter,
    long dueMilli,
    long paidMoneyMilli,
    long arrearsMilli,
    Optional<DebtContractId> debtContractId,
    List<String> reasons) {

  public WageSettlementReadout {
    Objects.requireNonNull(unit, "WageSettlementReadout.unit 不得为 null");
    Objects.requireNonNull(role, "WageSettlementReadout.role 不得为 null");
    Objects.requireNonNull(
        anchorKey, "WageSettlementReadout.anchorKey 不得为 null（缺键用 Optional.empty()）");
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
    Objects.requireNonNull(debtContractId, "WageSettlementReadout.debtContractId 不得为 null");
    Objects.requireNonNull(reasons, "WageSettlementReadout.reasons 不得为 null");
    reasons = List.copyOf(reasons);
    long accounted = Math.addExact(paidMoneyMilli, arrearsMilli);
    if (accounted != dueMilli) {
      throw new IllegalArgumentException(
          "WageSettlementReadout 两数不守恒: due="
              + dueMilli
              + " != paidMoney="
              + paidMoneyMilli
              + " + arrears="
              + arrearsMilli);
    }
  }
}
