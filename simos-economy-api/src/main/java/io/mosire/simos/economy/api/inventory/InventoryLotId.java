package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * 库存 lot 的稳定身份。
 *
 * <p>它是 opaque 的非空白值对象；商品与土地的规范拼写只在本类的工厂方法里发生。调用方不得拆解或自行拼接该字符串。
 *
 * @param value 非空白规范串
 */
public record InventoryLotId(String value) {

  private static final String COMMODITY_PREFIX = "commodity:";
  private static final String LAND_PREFIX = "land:";

  public InventoryLotId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("InventoryLotId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 读取一个已经存在的 opaque lot id（不重写商品/土地身份格式）。 */
  public static InventoryLotId parse(String text) {
    return new InventoryLotId(text);
  }

  /** 商品聚合行身份：同一 owner + 所在地 + commodity 稳定地得到同一个 lot id。 */
  public static InventoryLotId forCommodity(
      HouseholdId owner, HexCoord location, CommodityId commodity) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(commodity, "commodity");
    return new InventoryLotId(
        COMMODITY_PREFIX
            + owner.value()
            + ":"
            + location.q()
            + "_"
            + location.r()
            + ":"
            + commodity.value());
  }

  /** 土地 component 的 lot 身份：规范映射自 {@link LandComponentId}。 */
  public static InventoryLotId forLand(LandComponentId componentId) {
    Objects.requireNonNull(componentId, "componentId");
    return new InventoryLotId(LAND_PREFIX + componentId.value());
  }

  /** {@link #forLand(LandComponentId)} 的逆：只接受本类生成的 LAND 前缀身份。 */
  public LandComponentId landComponentId() {
    if (!value.startsWith(LAND_PREFIX)) {
      throw new IllegalArgumentException("InventoryLotId 不是 LAND lot: " + value);
    }
    return LandComponentId.parse(value.substring(LAND_PREFIX.length()));
  }
}
