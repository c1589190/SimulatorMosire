package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;

/**
 * ★★ <b>D-032 D4 资产占用授予的稳定身份</b>（D4 §4.3）：由 {@code (unitId, shareId, fromTick)} 确定性生成。
 *
 * <p>裸值 {@code toString()} + {@link #parse(String)} 三件套（铁律 1）；不随机、不读时钟、不用 UUID。格式在 {@link
 * #idOf(ProductionUnitId, AssetShareId, long)} 一处拼写，且不含 {@code "."}（地址会被第一个点截断）。
 *
 * @param value 非空白规范串
 */
public record AssetOccupancyGrantId(String value) {

  public AssetOccupancyGrantId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AssetOccupancyGrantId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "AssetOccupancyGrantId 不得含 '.'（地址 economy:<mapId>:assetOccupancy.<id> 会被第一个点截断）: "
              + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白；格式权威在 {@link #idOf(ProductionUnitId, AssetShareId, long)}。 */
  public static AssetOccupancyGrantId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetOccupancyGrantId 不得为空白: " + text);
    }
    return new AssetOccupancyGrantId(text);
  }

  /**
   * ★ 新 id 的唯一拼写点：{@code grant-<unit>-<share>-<fromTick>}。
   *
   * <p>★★ 不含 {@code toTickExclusive}（D4 §4.3 明确建议的确定性键）：续占是新的相邻区间、新的 {@code fromTick}，因此不会与旧段撞 id；
   * 同一 unit/share/start 的第二次写入属于"同一段重复授予"，由分配器的 overlap 守卫拒绝，不靠 id 静默覆盖。
   */
  public static AssetOccupancyGrantId idOf(
      ProductionUnitId unitId, AssetShareId shareId, long fromTick) {
    if (unitId == null) {
      throw new IllegalArgumentException("AssetOccupancyGrantId.idOf 的 unitId 不得为 null");
    }
    if (shareId == null) {
      throw new IllegalArgumentException("AssetOccupancyGrantId.idOf 的 shareId 不得为 null");
    }
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancyGrantId.idOf 的 fromTick 不得为负: " + fromTick);
    }
    return new AssetOccupancyGrantId(
        "grant-" + unitId.value() + "-" + shareId.value() + "-" + fromTick);
  }
}
