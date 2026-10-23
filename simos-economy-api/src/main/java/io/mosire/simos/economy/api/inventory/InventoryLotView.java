package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 稳定排序、只读的库存 lot 视图。
 *
 * <p>排序键固定为 {@code (kind.ordinal, itemTypeId, location.q/r, owner.value, lotId.value)}，其中空 location
 * 排在最后。 构造期检查重复 lotId，并冻结保序列表。
 *
 * @param lots 已排序且不可变的 lot 列表
 */
public record InventoryLotView(List<InventoryLot> lots) {

  private static final Comparator<InventoryLot> LOT_ORDER =
      Comparator.comparingInt((InventoryLot lot) -> lot.itemKind().ordinal())
          .thenComparing(InventoryLot::itemTypeId)
          .thenComparing((left, right) -> compareLocations(left.location(), right.location()))
          .thenComparing(lot -> lot.owner().value())
          .thenComparing(lot -> lot.lotId().value());

  public InventoryLotView {
    if (lots == null) {
      throw new IllegalArgumentException("InventoryLotView.lots 不得为 null（空视图用空列表）");
    }
    List<InventoryLot> ordered = new ArrayList<>();
    for (InventoryLot lot : lots) {
      if (lot == null) {
        throw new IllegalArgumentException("InventoryLotView.lots 不得含 null");
      }
      ordered.add(lot);
    }
    Set<InventoryLotId> seen = new HashSet<>();
    for (InventoryLot lot : ordered) {
      if (!seen.add(lot.lotId())) {
        throw new IllegalArgumentException("inventory-lot-duplicate-id: " + lot.lotId().value());
      }
    }
    ordered.sort(LOT_ORDER);
    lots = Collections.unmodifiableList(ordered); // ★ 保序不可变
  }

  /** 按 lot id 取唯一一行；缺失具名抛。 */
  public InventoryLot require(InventoryLotId lotId) {
    Objects.requireNonNull(lotId, "lotId");
    for (InventoryLot lot : lots) {
      if (lot.lotId().equals(lotId)) {
        return lot;
      }
    }
    throw new IllegalArgumentException("inventory-lot-not-found: " + lotId.value());
  }

  /** 该 owner 的全部行，保持视图顺序且不可变。 */
  public List<InventoryLot> byOwner(HouseholdId owner) {
    Objects.requireNonNull(owner, "owner");
    List<InventoryLot> matches = new ArrayList<>();
    for (InventoryLot lot : lots) {
      if (lot.owner().equals(owner)) {
        matches.add(lot);
      }
    }
    return Collections.unmodifiableList(matches);
  }

  /** 该 kind 的全部行，保持视图顺序且不可变。 */
  public List<InventoryLot> byKind(InventoryLotKind kind) {
    Objects.requireNonNull(kind, "kind");
    List<InventoryLot> matches = new ArrayList<>();
    for (InventoryLot lot : lots) {
      if (lot.itemKind() == kind) {
        matches.add(lot);
      }
    }
    return Collections.unmodifiableList(matches);
  }

  private static int compareLocations(Optional<HexCoord> left, Optional<HexCoord> right) {
    if (left.isEmpty() && right.isEmpty()) {
      return 0;
    }
    if (left.isEmpty()) {
      return 1;
    }
    if (right.isEmpty()) {
      return -1;
    }
    int byQ = Integer.compare(left.get().q(), right.get().q());
    return byQ != 0 ? byQ : Integer.compare(left.get().r(), right.get().r());
  }
}
