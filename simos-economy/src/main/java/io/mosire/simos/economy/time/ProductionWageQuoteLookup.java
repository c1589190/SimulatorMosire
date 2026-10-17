package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageQuote;
import java.util.Optional;

/**
 * ★★ <b>D2/D3 工资报价唯一读口</b>（D-032 D2 §6.5）。
 *
 * <p>★★ <b>不可用与零的区分</b>：{@link Optional#empty()} 表示"没有报价"（接口未装配 / D3 状态未落 / 真缺行），调用方必须具名 {@code
 * wage-quote-missing:<role>}，<b>不得</b>读成 0。报价值 {@code 0} 是合法 报价（U18：地主可报 0）。
 *
 * <p>★ <b>C2 的默认实现 = 永远 empty</b>：{@link UnavailableProductionWageQuoteLookup}。W2a 落真实实现 （执行期读
 * {@code productionWagePostings}，新进入候选读 {@code ProductionWageDefault}）。
 *
 * <p>★ <b>只读</b>：本接口不写状态、不产生变更。
 */
public interface ProductionWageQuoteLookup {

  /**
   * 读某 unit 某角色的当刻工资报价。
   *
   * @param unit 被读取的生产单元
   * @param role 方案内工资角色
   * @return 报价；缺报价 ⇒ empty（不得用 0 冒充）
   */
  Optional<ProductionWageQuote> quoteFor(ProductionUnitId unit, ProductionRoleId role);
}
