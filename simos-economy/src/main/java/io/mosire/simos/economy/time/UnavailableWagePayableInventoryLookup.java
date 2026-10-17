package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-C fail-closed 工资库存适配器</b>（D4 §7.1 / D3 §6.9）：D4-I 真实实现未接时始终返回 {@link
 * Optional#empty()}。
 *
 * <p>★ 无状态、只读。调用方必须将它解释为 {@code wage-inventory-scope-unavailable}，不得读成零库存或回退 {@code AccountSession}
 * 原始余额/整池库存。
 */
public final class UnavailableWagePayableInventoryLookup implements WagePayableInventoryLookup {

  @Override
  public Optional<Map<CommodityId, Long>> payableInventory(
      ActorRef payer, ProductionLocation settlementLocation) {
    return Optional.empty();
  }
}
