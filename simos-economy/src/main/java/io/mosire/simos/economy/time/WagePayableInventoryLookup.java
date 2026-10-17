package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-C 工资可支付库存唯一只读读口</b>（D4 §7.1 / D3 §6.9）：返回付款人在某结算地点上下文中、 已扣完账户冻结与该 payer 全部地点
 * ACTIVE 生产预留后的逐商品可支付数量。
 *
 * <p>★★ <b>不可用与零的区分</b>：{@link Optional#empty()} 只表示 D4 lookup 尚未接线/明确 disabled、payer 无法解析为
 * 已登记家户账户或实现明确不可用；调用方必须具名 {@code wage-inventory-scope-unavailable}，不得当 0、不得回退 {@code
 * AccountSession} 原始余额或整池库存。可用读口的空 {@link Map} 表示该 payer 确实没有可支付商品（合法 0）。
 *
 * <p>★ <b>只读</b>：本接口不写账户、不写占用、不改变 revision。返回表按 {@link CommodityId#value()} 升序冻结， 只保留数量 {@code > 0}
 * 的项；缺项按 0 解释。
 */
public interface WagePayableInventoryLookup {

  /**
   * 读取付款人最终可支付的逐商品数量（毫商品单位）。
   *
   * @param payer 付款人 actor；工资链中为 organizer 家户
   * @param settlementLocation 本次结算/预算分组的地点上下文
   * @return 保序不可变的可支付数量表；接口未装配或权威事实不可用时为空
   */
  Optional<Map<CommodityId, Long>> payableInventory(
      ActorRef payer, ProductionLocation settlementLocation);
}
