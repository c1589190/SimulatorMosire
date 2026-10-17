package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageQuote;
import java.util.Optional;

/**
 * ★★ <b>D-032 D2 §6.5：C2 的 fail-closed 工资报价适配器</b>—— 永远返回 {@link Optional#empty()}。
 *
 * <p>★ 无状态、只读。调用方必须把它解释为 {@code wage-quote-missing:<role>}（含 {@code MONEY_WAGE} 角色的候选）， <b>不得</b>按
 * 0 报价；真实报价状态与支付顺序归 D3/W2a/W3。
 */
public final class UnavailableProductionWageQuoteLookup implements ProductionWageQuoteLookup {

  @Override
  public Optional<ProductionWageQuote> quoteFor(ProductionUnitId unit, ProductionRoleId role) {
    return Optional.empty();
  }
}
