package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>连续债务合同</b>（E4a；理想架构 §2.7）：同一 {@code (debtor, creditor, unit, terms)} 是**一条连续欠账**，
 * 跨周期不新开条；本金是连续余额（借入与计息只增、偿还只减），到期/状态是合同记录上的滚动字段。
 *
 * <pre>
 * DebtContract(
 *   id,               // (debtor, creditor, unit, terms) 的确定性稳定身份；键 == 值内 id
 *   debtor, creditor, // 家户稳定身份（S1 起身份与视图分离）
 *   unit,             // 显式计量标的：实物 CommodityId 或货币 CurrencyId，恰有其一
 *   terms,            // 条款（利率/计息时点/偿还规则/折偿/救济/期限维）；不同 terms 不合并
 *   principal,        // 连续本金余额；≥ 0；计息并入本金（不搬粮/钱）
 *   openedDay,        // 首次建条日（旧档迁移无此字段 ⇒ 0，见 Codec 迁移说明）
 *   lastInterestDay,  // 最近一次**真的把利息并入本金**的日；可空 = 本档尚未计息
 *   dueCycle,         // 当前约定的到期周期；可空 = 没有写死期限（条款维另在 terms.dueCycle）
 *   status)           // NORMAL/DELINQUENT/DEFAULTED/SETTLED/FORGIVEN
 * </pre>
 *
 * <p>★★ <b>旧 {@code Debt} 的逐值投影</b>：旧 {@code (周期, 债务人, 债权人, 商品)} 聚合条迁移后按 {@code (debtor, creditor,
 * unit, legacy terms)} 合并为一条；{@code principal} 用 {@code Math.addExact} 求和（本金逐值守恒），{@code dueCycle}
 * 取被合并条的**最大**到期周期（连续余额的最新约定），旧 {@code defaulted} 映射到 {@link DebtStatus#DEFAULTED}。
 *
 * <p>★ <b>计息只增 {@code principal}</b>，不动任何库存/货币余额；旧路径的“关账日偿还后计息、以当日起始本金为 基数”由 {@code 旧结算引擎（R3a
 * 已删除）.chargeInterest} 按 {@code terms.interestTiming} 分派。
 *
 * @param id 稳定身份；不得为 null（键 == 值内 id、且必须是 {@code DebtContractId.idOf(...)} 的产物）
 * @param debtor 债务人；不得为 null
 * @param creditor 债权人；不得为 null
 * @param unit 计量标的；不得为 null
 * @param terms 条款；不得为 null
 * @param principal 连续本金余额；不得为负
 * @param openedDay 建条日；不得为负
 * @param lastInterestDay 最近真的计息并入本金的日；不得为 null（从未计息用 {@code OptionalLong.empty()}）
 * @param dueCycle 当前约定到期周期；不得为 null（无期限用 {@code OptionalLong.empty()}）
 * @param status 合同状态；不得为 null
 */
public record DebtContract(
    DebtContractId id,
    HouseholdId debtor,
    HouseholdId creditor,
    DebtUnit unit,
    DebtTerms terms,
    long principal,
    long openedDay,
    OptionalLong lastInterestDay,
    OptionalLong dueCycle,
    DebtStatus status) {

  public DebtContract {
    Objects.requireNonNull(id, "DebtContract.id 不得为 null");
    Objects.requireNonNull(debtor, "DebtContract.debtor 不得为 null");
    Objects.requireNonNull(creditor, "DebtContract.creditor 不得为 null");
    Objects.requireNonNull(unit, "DebtContract.unit 不得为 null");
    Objects.requireNonNull(terms, "DebtContract.terms 不得为 null");
    Objects.requireNonNull(status, "DebtContract.status 不得为 null");
    if (principal < 0L) {
      throw new IllegalArgumentException("DebtContract.principal 不得为负: " + principal);
    }
    if (openedDay < 0L) {
      throw new IllegalArgumentException("DebtContract.openedDay 不得为负: " + openedDay);
    }
    // ★ 旧档/手写 JSON 缺键时 Jackson 可能把 OptionalLong 位置绑成 null ⇒ 统一收成 empty。
    if (lastInterestDay == null) {
      lastInterestDay = OptionalLong.empty();
    }
    if (dueCycle == null) {
      dueCycle = OptionalLong.empty();
    }
    if (lastInterestDay.isPresent() && lastInterestDay.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "DebtContract.lastInterestDay 不得为负: " + lastInterestDay.getAsLong());
    }
    if (dueCycle.isPresent() && dueCycle.getAsLong() < 0L) {
      throw new IllegalArgumentException("DebtContract.dueCycle 不得为负: " + dueCycle.getAsLong());
    }
  }

  /** ★ 旧读数的兼容投影：{@code defaulted == (status == DEFAULTED)}（只读，不写状态）。 */
  public boolean defaulted() {
    return status == DebtStatus.DEFAULTED;
  }

  /** ★ {@code id} 是否确实是四元组的确定性派生（迁移/读口/守卫的核对点；本记录不替调用方重算覆盖）。 */
  public boolean idMatchesIdentity() {
    return id.equals(DebtContractId.idOf(debtor, creditor, unit, terms));
  }

  /** 换本金（借入累加 / 偿还减少 / 计息并入共用；其余字段逐值保留）。 */
  public DebtContract withPrincipal(long newPrincipal) {
    return new DebtContract(
        id,
        debtor,
        creditor,
        unit,
        terms,
        newPrincipal,
        openedDay,
        lastInterestDay,
        dueCycle,
        status);
  }

  /** 换状态（违约判定 / 结清共用；本金与其余字段逐值保留）。 */
  public DebtContract withStatus(DebtStatus newStatus) {
    return new DebtContract(
        id,
        debtor,
        creditor,
        unit,
        terms,
        principal,
        openedDay,
        lastInterestDay,
        dueCycle,
        newStatus);
  }

  /** 换当前约定到期周期（借入续期用；其余字段逐值保留）。 */
  public DebtContract withDueCycle(OptionalLong newDueCycle) {
    return new DebtContract(
        id,
        debtor,
        creditor,
        unit,
        terms,
        principal,
        openedDay,
        lastInterestDay,
        newDueCycle,
        status);
  }

  /** 换“最近真的计息并入本金”的日（计息写点用；其余字段逐值保留）。 */
  public DebtContract withLastInterestDay(OptionalLong newLastInterestDay) {
    return new DebtContract(
        id,
        debtor,
        creditor,
        unit,
        terms,
        principal,
        openedDay,
        newLastInterestDay,
        dueCycle,
        status);
  }
}
