package io.mosire.simos.economy.model;

import java.util.Objects;

/**
 * ★★★ <b>D-032 C7b1：一条跨 hex 运输需求</b>。
 *
 * <p>{@code quantityMilli} 是当轮订单派生出的可运输商品量；{@code workMilli} 是既有 {@code
 * CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille)} 的运力需求。两者都非负；0 值只用于防御性构造，正常计划不会为 0
 * 量生成条目。
 *
 * <p>瞬态类型：不落盘、不进 ChangeSet/Codec。
 *
 * @param key 需求键；不得为 null
 * @param quantityMilli 商品量（毫商品）；不得为负
 * @param workMilli 运力需求（毫商品·程）；不得为负
 */
public record TransportDemand(TransportDemandKey key, long quantityMilli, long workMilli) {

  public TransportDemand {
    Objects.requireNonNull(key, "TransportDemand.key 不得为 null");
    if (quantityMilli < 0L) {
      throw new IllegalArgumentException("TransportDemand.quantityMilli 不得为负: " + quantityMilli);
    }
    if (workMilli < 0L) {
      throw new IllegalArgumentException("TransportDemand.workMilli 不得为负: " + workMilli);
    }
  }
}
