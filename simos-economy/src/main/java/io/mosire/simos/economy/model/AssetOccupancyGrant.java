package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;

/**
 * ★★ <b>D-032 D4 资产占用授予事实</b>（D4 §4.3）：按份额、按实例、按时段的数量锁，不是资产账。
 *
 * <p>★★ <b>不复制 owner/operator/industry/assetKind/location</b>：它们从 {@code shareId} 指向的 {@link
 * OwnershipStake} 与 {@code unitId} 指向的生产实例派生，避免两处漂移。{@code quantity} 是占用锁数量，绝不修改 {@code
 * OwnershipStake.quantity}。
 *
 * <p>★ 区间是半开 {@code [fromTick, toTickExclusive)}；{@code ACTIVE} 时 {@code releasedAtTick =
 * -1}，{@code RELEASED} 时 {@code releasedAtTick >= 0}。同一份额同一时段全部 ACTIVE 授予之和不得超过份额数量（表级交验落在 {@code
 * EconomyReferenceValidation}）。
 *
 * @param id 确定性授予身份
 * @param shareId 唯一实物来源
 * @param unitId 占用者/消费者
 * @param quantity 占用数量；必须 {@code > 0}
 * @param fromTick 区间起点（含）；不得为负
 * @param toTickExclusive 区间终点（不含）；必须大于起点
 * @param status 状态
 * @param releasedAtTick ACTIVE 为 -1；RELEASED 为非负
 */
public record AssetOccupancyGrant(
    AssetOccupancyGrantId id,
    AssetShareId shareId,
    ProductionUnitId unitId,
    long quantity,
    long fromTick,
    long toTickExclusive,
    AssetOccupancyStatus status,
    long releasedAtTick) {

  public AssetOccupancyGrant {
    if (id == null) {
      throw new IllegalArgumentException("AssetOccupancyGrant.id 不得为 null");
    }
    if (shareId == null) {
      throw new IllegalArgumentException("AssetOccupancyGrant.shareId 不得为 null");
    }
    if (unitId == null) {
      throw new IllegalArgumentException("AssetOccupancyGrant.unitId 不得为 null");
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("AssetOccupancyGrant.quantity 必须 > 0: " + quantity);
    }
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancyGrant.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancyGrant 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
    if (status == null) {
      throw new IllegalArgumentException("AssetOccupancyGrant.status 不得为 null");
    }
    if (status == AssetOccupancyStatus.ACTIVE && releasedAtTick != -1L) {
      throw new IllegalArgumentException(
          "ACTIVE AssetOccupancyGrant 的 releasedAtTick 必须为 -1: " + releasedAtTick);
    }
    if (status == AssetOccupancyStatus.RELEASED && releasedAtTick < 0L) {
      throw new IllegalArgumentException(
          "RELEASED AssetOccupancyGrant 的 releasedAtTick 必须 >= 0: " + releasedAtTick);
    }
  }

  /** 两条半开区间是否重叠：{@code a < d && c < b}（D4 §4.5.2 的唯一判据）。 */
  public boolean overlaps(long otherFromTick, long otherToTickExclusive) {
    return fromTick < otherToTickExclusive && otherFromTick < toTickExclusive;
  }

  /** 当前状态是否计入占用。 */
  public boolean countsAsOccupied() {
    return status == AssetOccupancyStatus.ACTIVE;
  }
}
