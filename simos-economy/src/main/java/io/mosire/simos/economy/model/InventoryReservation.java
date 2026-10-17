package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ProductionLocation;

/**
 * ★★ <b>D-032 D4 商品库存预留事实</b>（D4 §4.4）：商品库存的占用声明，<b>不是</b>账户余额、不是冻结、不是第二本库存账。
 *
 * <p>账户权威仍是 {@code AccountSession} 的户级商品/货币余额与冻结；本行只让 W3 知道"哪些商品虽然还在账户里，但已经被生产占用/承诺"。 创建时必须校验同一
 * {@code payer × commodity} 全部地点的 ACTIVE 预留加冻结不超过账户余额（overcommit 是契约故障 ERROR）。
 *
 * <p>★ 区间半开；ACTIVE 时 {@code releasedAtTick = -1}，RELEASED 时 {@code >= 0}；{@code quantity >
 * 0}；地点必须是规范 {@code <q>_<r>}；付款人必须是家户主体（登记存在性由引用校验/写口检查）。
 *
 * @param id 确定性预留身份
 * @param unitId 承诺使用该商品的生产实例
 * @param payer 账户所有者；必须是已登记家户
 * @param location 生产地点上下文
 * @param commodity 商品身份
 * @param quantity 预留数量（毫商品）；必须 {@code > 0}
 * @param fromTick 区间起点（含）
 * @param toTickExclusive 区间终点（不含）
 * @param status 状态
 * @param releasedAtTick ACTIVE 为 -1；RELEASED 为非负
 */
public record InventoryReservation(
    InventoryReservationId id,
    ProductionUnitId unitId,
    ActorRef payer,
    ProductionLocation location,
    CommodityId commodity,
    long quantity,
    long fromTick,
    long toTickExclusive,
    InventoryReservationStatus status,
    long releasedAtTick) {

  public InventoryReservation {
    if (id == null) {
      throw new IllegalArgumentException("InventoryReservation.id 不得为 null");
    }
    if (unitId == null) {
      throw new IllegalArgumentException("InventoryReservation.unitId 不得为 null");
    }
    if (payer == null) {
      throw new IllegalArgumentException("InventoryReservation.payer 不得为 null");
    }
    if (payer.kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalArgumentException(
          "InventoryReservation.payer 必须是家户主体: " + payer.kind() + ":" + payer.id());
    }
    if (location == null) {
      throw new IllegalArgumentException(
          "asset-location-missing: InventoryReservation.location 不得为 null");
    }
    OwnershipStake.requireSpecifiedLocation(location);
    if (commodity == null) {
      throw new IllegalArgumentException("InventoryReservation.commodity 不得为 null");
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("InventoryReservation.quantity 必须 > 0: " + quantity);
    }
    if (fromTick < 0L) {
      throw new IllegalArgumentException("InventoryReservation.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "InventoryReservation 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
    if (status == null) {
      throw new IllegalArgumentException("InventoryReservation.status 不得为 null");
    }
    if (status == InventoryReservationStatus.ACTIVE && releasedAtTick != -1L) {
      throw new IllegalArgumentException(
          "ACTIVE InventoryReservation 的 releasedAtTick 必须为 -1: " + releasedAtTick);
    }
    if (status == InventoryReservationStatus.RELEASED && releasedAtTick < 0L) {
      throw new IllegalArgumentException(
          "RELEASED InventoryReservation 的 releasedAtTick 必须 >= 0: " + releasedAtTick);
    }
  }

  /** 是否覆盖给定 tick 的活动半开区间。 */
  public boolean activeAt(long tick) {
    return status == InventoryReservationStatus.ACTIVE
        && fromTick <= tick
        && tick < toTickExclusive;
  }

  /** 两条半开区间是否重叠。 */
  public boolean overlaps(long otherFromTick, long otherToTickExclusive) {
    return fromTick < otherToTickExclusive && otherFromTick < toTickExclusive;
  }
}
