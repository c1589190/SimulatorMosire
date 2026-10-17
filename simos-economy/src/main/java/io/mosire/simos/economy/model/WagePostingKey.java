package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>执行期工资岗位的稳定复合键</b>（D-032 D3 §2.2）。
 *
 * <p>键 = {@code (unit, role)}。同一 {@code (unit, role)} 至多一条 {@link ProductionWagePosting}；
 * 它是执行期唯一实际报价权威，候选 default 不参与已存在实例的报价/结算。
 *
 * @param unit 生产实例稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 */
public record WagePostingKey(ProductionUnitId unit, ProductionRoleId role) {

  public WagePostingKey {
    Objects.requireNonNull(unit, "WagePostingKey.unit 不得为 null");
    Objects.requireNonNull(role, "WagePostingKey.role 不得为 null");
  }

  @Override
  public String toString() {
    return unit.value() + "|" + role.value();
  }

  /** 解析 {@link #toString()} 的产物；段数/空白不合法即抛。 */
  public static WagePostingKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("WagePostingKey 不得为空白: " + text);
    }
    String[] parts = text.split("\\|", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("WagePostingKey 必须是 <unit>|<role> 两段: " + text);
    }
    return new WagePostingKey(ProductionUnitId.parse(parts[0]), ProductionRoleId.parse(parts[1]));
  }
}
