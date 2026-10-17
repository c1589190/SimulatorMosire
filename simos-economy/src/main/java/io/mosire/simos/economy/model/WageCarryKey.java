package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>逐户工资进位余数的稳定复合键</b>（D-032 D3 §2.4；{@code EconomyData} 第 35 个组件）。
 *
 * <p>键 = {@code (unit, role, worker)}。同一 worker 在不同 unit/role 的 carry 分开；不同 worker 不共享 carry。 值域
 * {@code 0..999}，表示上次 {@code (quote × labor + carry)} 除 1000 后的分子余数。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 * @param worker 领取该笔工资的 worker 家户；不得为 null
 */
public record WageCarryKey(ProductionUnitId unit, ProductionRoleId role, HouseholdId worker) {

  public WageCarryKey {
    Objects.requireNonNull(unit, "WageCarryKey.unit 不得为 null");
    Objects.requireNonNull(role, "WageCarryKey.role 不得为 null");
    Objects.requireNonNull(worker, "WageCarryKey.worker 不得为 null");
  }

  @Override
  public String toString() {
    return unit.value() + "|" + role.value() + "|" + worker.value();
  }

  /** 解析 {@link #toString()} 的产物；段数/空白不合法即抛。 */
  public static WageCarryKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("WageCarryKey 不得为空白: " + text);
    }
    String[] parts = text.split("\\|", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("WageCarryKey 必须是 <unit>|<role>|<worker> 三段: " + text);
    }
    return new WageCarryKey(
        ProductionUnitId.parse(parts[0]),
        ProductionRoleId.parse(parts[1]),
        HouseholdId.parse(parts[2]));
  }
}
