package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Optional;

/**
 * ★★ <b>候选级工资默认唯一读口</b>（D-032 D3 §2.2.1/§2.3）。
 *
 * <p>只在生产实例存在前使用：新进入候选读 {@code (plan, role, marketZone)} 的默认报价与目标。 已存在实例必须走 {@code
 * ProductionWageQuoteLookup}（执行期 posting），不得回退本读口。
 *
 * <p>★ <b>缺 = {@link Optional#empty()}</b>；不得用 0 冒充。显式 0 报价是合法值（U18）。本接口只读，不写状态。
 */
public interface ProductionWageDefaultLookup {

  /**
   * 读取候选级工资默认。
   *
   * @param plan 方案稳定身份
   * @param role 方案内工资角色
   * @param zone 市场区
   * @return 默认；缺 ⇒ empty（不得当作 0）
   */
  Optional<ProductionWageDefault> defaultFor(
      IndustryId plan, ProductionRoleId role, MarketZoneId zone);
}
