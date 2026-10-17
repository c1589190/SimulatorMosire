package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ProductionLocation;

/**
 * ★★ <b>D-032 D4 商品库存预留的稳定身份</b>（D4 §4.4）：由 {@code (unit, payer, location, commodity, fromTick)}
 * 确定性生成。
 *
 * <p>裸值 {@code toString()} + {@link #parse(String)}；不随机、不读时钟、不用 UUID，且不含 {@code "."}。
 *
 * @param value 非空白规范串
 */
public record InventoryReservationId(String value) {

  public InventoryReservationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("InventoryReservationId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "InventoryReservationId 不得含 '.'（地址 economy:<mapId>:inventoryReservation.<id> 会被第一个点截断）: "
              + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白；格式权威在 {@link #idOf}。 */
  public static InventoryReservationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("InventoryReservationId 不得为空白: " + text);
    }
    return new InventoryReservationId(text);
  }

  /** ★ 新 id 的唯一拼写点：{@code reservation-<unit>-<payer>-<location>-<commodity>-<fromTick>}。 */
  public static InventoryReservationId idOf(
      ProductionUnitId unitId,
      ActorRef payer,
      ProductionLocation location,
      CommodityId commodity,
      long fromTick) {
    if (unitId == null) {
      throw new IllegalArgumentException("InventoryReservationId.idOf 的 unitId 不得为 null");
    }
    if (payer == null) {
      throw new IllegalArgumentException("InventoryReservationId.idOf 的 payer 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("InventoryReservationId.idOf 的 location 不得为 null");
    }
    if (commodity == null) {
      throw new IllegalArgumentException("InventoryReservationId.idOf 的 commodity 不得为 null");
    }
    if (fromTick < 0L) {
      throw new IllegalArgumentException(
          "InventoryReservationId.idOf 的 fromTick 不得为负: " + fromTick);
    }
    return new InventoryReservationId(
        "reservation-"
            + unitId.value()
            + "-"
            + payer.kind()
            + "-"
            + payer.id()
            + "-"
            + location.value()
            + "-"
            + commodity.value()
            + "-"
            + fromTick);
  }
}
