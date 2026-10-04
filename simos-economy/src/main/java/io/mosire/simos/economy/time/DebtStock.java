package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.model.DebtContract;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>P5：债务存量的纯读口（{@code 债务 = Σ 合同本金}；不写任何状态、不新增 {@code EconomyData} 组件）</b>。
 *
 * <p>★★ <b>唯一权威是合同表</b>：{@code ClassRow.debts} 只是债务人方向派生引用，本类不读它 —— 每个数与 {@code DebtContractBook}
 * 写下的本金逐值一致。{@link #totalPrincipal} 是 P9 核对 {@code 债务 = 发行 − 还款 − 删债（利息另列）} 的左侧；{@link
 * #principalByUnit} 把实物债与各币种货币债分开 （粮 {@code commodity:grain} 与币 {@code money:silver} 不混加）。
 *
 * <p>★ 逐项求和用 {@link Math#addExact} fail-closed（溢出不是"一个大数"，是账错了）。
 */
public final class DebtStock {

  private DebtStock() {}

  /** Σ 全部合同本金（不按状态过滤；本金为 0 的结清/减免条自然贡献 0）。 */
  public static long totalPrincipal(Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    long total = 0L;
    for (DebtContract debt : debts.values()) {
      total = Math.addExact(total, debt.principal());
    }
    return total;
  }

  /** 逐债务人本金合计（保序 = 合同表首现序；只列本金 &gt; 0 的债务人）。 */
  public static Map<HouseholdId, Long> principalByDebtor(Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    LinkedHashMap<HouseholdId, Long> totals = new LinkedHashMap<>();
    for (DebtContract debt : debts.values()) {
      if (debt.principal() > 0L) {
        totals.merge(debt.debtor(), debt.principal(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /** 逐计量标的本金合计（{@link DebtUnit} 键；只列本金 &gt; 0 的标的）。 */
  public static Map<DebtUnit, Long> principalByUnit(Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    LinkedHashMap<DebtUnit, Long> totals = new LinkedHashMap<>();
    for (DebtContract debt : debts.values()) {
      if (debt.principal() > 0L) {
        totals.merge(debt.unit(), debt.principal(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }
}
