package io.mosire.simos.economy.api.market;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>一票在途货物里的一条分配</b>（M2.4/M2.5）：谁卖给谁、多少、送到哪一格、**损耗谁承担**。
 *
 * <p>★★ <b>为什么是"分配"而不是"批次只记总量"</b>：一个 {@link ShipmentBatch} 可以合并同一路线上的多笔成交 （同批发运、同路线、同
 * ETA），但**每个买方的到货量与损耗归属必须逐票保留** —— 到货日要按票加进各自的账， 损耗要按 {@link #lossBearer()}
 * 归属；聚合掉这两样，到货处理就只能"按人头平摊"，那是编造。
 *
 * @param seller 卖方主体；不得为 null
 * @param buyer 买方主体（发运时货权已归它，在途资产属于它）；不得为 null
 * @param deliverTo 收货格（买方订单的 {@code deliverTo}，可追溯到格）；不得为 null
 * @param quantity 本票在途数量（毫商品；> 0）
 * @param lossBearer 本票损耗的承担方；不得为 null
 */
public record ShipmentAllocation(
    ActorRef seller, ActorRef buyer, HexCoord deliverTo, long quantity, LossBearer lossBearer) {

  public ShipmentAllocation {
    Objects.requireNonNull(seller, "seller");
    Objects.requireNonNull(buyer, "buyer");
    Objects.requireNonNull(deliverTo, "deliverTo");
    Objects.requireNonNull(lossBearer, "lossBearer");
    if (quantity <= 0L) {
      throw new IllegalArgumentException(
          "ShipmentAllocation.quantity 必须 > 0（0 不是一票在途）: " + quantity);
    }
  }
}
