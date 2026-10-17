package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>候选级工资默认的稳定复合键</b>（D-032 D3 §2.2.1）。
 *
 * <p>键 = {@code (plan, role, marketZone)}。同一个键至多一条 {@link ProductionWageDefault}；
 * 它只服务实例存在前的候选估价与选中物化， <b>不是</b>执行期报价权威（执行期唯一权威仍是 {@link ProductionWagePosting}）。
 *
 * <p>★ 线格式用 {@link #toString()} 的规范串，{@link #parse(String)} 与其互逆；分隔符为 {@code |}。三个组成 id 的 {@code
 * toString()} 都是裸值，故变更集/快照的 Jackson 键绑定无需额外的值侧绑定。
 *
 * @param plan 方案稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 * @param marketZone 市场区稳定身份；不得为 null
 */
public record WageDefaultKey(IndustryId plan, ProductionRoleId role, MarketZoneId marketZone) {

  public WageDefaultKey {
    Objects.requireNonNull(plan, "WageDefaultKey.plan 不得为 null");
    Objects.requireNonNull(role, "WageDefaultKey.role 不得为 null");
    Objects.requireNonNull(marketZone, "WageDefaultKey.marketZone 不得为 null");
  }

  @Override
  public String toString() {
    return plan.value() + "|" + role.value() + "|" + marketZone.value();
  }

  /** 解析 {@link #toString()} 的产物；段数/空白不合法即抛。 */
  public static WageDefaultKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("WageDefaultKey 不得为空白: " + text);
    }
    String[] parts = text.split("\\|", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "WageDefaultKey 必须是 <plan>|<role>|<marketZone> 三段: " + text);
    }
    return new WageDefaultKey(
        IndustryId.parse(parts[0]), ProductionRoleId.parse(parts[1]), MarketZoneId.parse(parts[2]));
  }
}
