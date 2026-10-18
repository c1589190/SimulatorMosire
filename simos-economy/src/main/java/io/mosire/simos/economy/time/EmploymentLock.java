package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P3c1：M3 锁定、M6 只读的雇佣关系快照</b>。
 *
 * <p>它只活在一个 tick 的两遍生产编排之间：不落 {@code EconomyData}、不进 ChangeSet/Codec、不提供 {@code with} / {@code
 * apply} 之类的改写口。M6 只能原样读取本对象的七个组件；换雇主、换雇工、换角色、改劳动份额或工资都必须被视为 违约，而不是构造一个新锁。
 *
 * <p>量纲：{@code laborMilliPerDay} 是毫劳动/日；{@code wagePerLaborMilli} 是“毫货币 / 毫劳动”，构造期只要求非负， 0
 * 是合法工资下界（是否接受由家户按净收益判断，不做 {@code wage == 0} 特判）。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param employer 雇佣方家户；不得为 null
 * @param worker 受雇方家户；不得为 null
 * @param role 受雇角色；不得为 null
 * @param laborMilliPerDay 锁定的日劳动份额；必须 &gt; 0
 * @param wagePerLaborMilli 锁定工资率；不得为负
 * @param currency 工资币种；不得为 null
 */
record EmploymentLock(
    ProductionUnitId unit,
    HouseholdId employer,
    HouseholdId worker,
    ProductionRoleId role,
    long laborMilliPerDay,
    long wagePerLaborMilli,
    CurrencyId currency) {

  EmploymentLock {
    Objects.requireNonNull(unit, "EmploymentLock.unit 不得为 null");
    Objects.requireNonNull(employer, "EmploymentLock.employer 不得为 null");
    Objects.requireNonNull(worker, "EmploymentLock.worker 不得为 null");
    Objects.requireNonNull(role, "EmploymentLock.role 不得为 null");
    Objects.requireNonNull(currency, "EmploymentLock.currency 不得为 null");
    if (laborMilliPerDay <= 0L) {
      throw new IllegalArgumentException(
          "EmploymentLock.laborMilliPerDay 必须 > 0: " + laborMilliPerDay);
    }
    if (wagePerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "EmploymentLock.wagePerLaborMilli 不得为负: " + wagePerLaborMilli);
    }
  }
}
