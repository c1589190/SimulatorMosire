package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>一批在途货物</b>（M2.4）：路线 + 商品 + 发运/到达日 + 总量 + <b>逐票分配</b>。
 *
 * <p>★★ <b>它是跨 tick 状态</b>（{@code EconomyData} 的第 10 个组件 {@code shipments} 的值）：发运日建、到达日（或更晚） 才销账。★
 * 判据"到货前目的地不得消费"就落在这一维上 —— 货在 {@link #quantity()} 里、不在任何 {@code HouseholdInventory}
 * 的余额里，消费与市场都读不到它。
 *
 * <p>★★ <b>基线合同（M2.5）</b>：发运时买方付货款与运费、货权归买方（进入其在途资产）；卖方库存减少； 到达日"在途减、目的地库存增"；约定由买方承担运输损耗（{@link
 * ShipmentAllocation#lossBearer()} 逐票保留， **不为聚合丢掉**）。
 *
 * <p>★ {@link #allocations()} 保留每一票的卖方/买方/收货格/数量/损耗归属：一份批次可以合并同路线上的多笔成交， 但到货与损耗只能按票落，不能按人头平摊。
 *
 * <p>★ 两张表**保序不可变**（{@code LinkedHashMap} 的键序 = 发运序；{@code List.copyOf} 保序），迭代序是内容的纯函数。
 *
 * @param route 本批的路线（起终点 = 真发货/收货格）；不得为 null
 * @param commodity 在途商品；不得为 null
 * @param dispatchTick 发运世界日（{@code >= 0}）
 * @param arrivalTick 到达世界日（{@code >= dispatchTick + route.travelTicks()}；运力窗口排期会让它更晚）
 * @param quantity 在途总量（毫商品；= 各票之和；{@code > 0}）
 * @param allocations 逐票分配（至少一票）；不得为 null
 */
public record ShipmentBatch(
    TradeRoute route,
    CommodityId commodity,
    long dispatchTick,
    long arrivalTick,
    long quantity,
    List<ShipmentAllocation> allocations) {

  public ShipmentBatch {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(commodity, "commodity");
    if (dispatchTick < 0L) {
      throw new IllegalArgumentException("ShipmentBatch.dispatchTick 不得为负: " + dispatchTick);
    }
    if (arrivalTick < dispatchTick + route.travelTicks()) {
      throw new IllegalArgumentException(
          "ShipmentBatch.arrivalTick 不得早于 dispatchTick + travelTicks（时间也是一件运输成本）: "
              + arrivalTick
              + " < "
              + dispatchTick
              + " + "
              + route.travelTicks());
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("ShipmentBatch.quantity 必须 > 0（0 不是一批在途）: " + quantity);
    }
    Objects.requireNonNull(allocations, "allocations");
    if (allocations.isEmpty()) {
      throw new IllegalArgumentException("ShipmentBatch.allocations 不得为空（在途必须能追到票）");
    }
    allocations = List.copyOf(allocations); // ★ 冻在赋值处（保序）
    long sum = 0L;
    for (ShipmentAllocation allocation : allocations) {
      if (allocation == null) {
        throw new IllegalArgumentException("ShipmentBatch.allocations 不得含 null");
      }
      sum += allocation.quantity();
    }
    if (sum != quantity) {
      throw new IllegalArgumentException(
          "ShipmentBatch.quantity 必须等于各票之和（同一件事不许两处拼写）: " + quantity + " != " + sum);
    }
  }
}
