package io.mosire.simos.app.inventory;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.world.LandProductionStateWriter;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotOrigin;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * S1 经济载入阶段的只读投影：把 actor 商品余额、economy 家户位置与 map ARABLE component 组装成唯一 {@link InventoryLotView}。
 *
 * <p>本类不写任何状态、无静态缓存、不读时钟/随机数；输出不落盘，仅供 U1b/U2/U3/U4 只读消费。
 */
public final class InventoryLotViewBuilder {

  private InventoryLotViewBuilder() {}

  /** 构造稳定排序的库存 lot 视图。 */
  public static InventoryLotView build(
      ActorData actorData, EconomyData economyData, GameMap gameMap) {
    Objects.requireNonNull(actorData, "actorData");
    Objects.requireNonNull(economyData, "economyData");
    Objects.requireNonNull(gameMap, "gameMap");

    List<InventoryLot> lots = new ArrayList<>();
    appendCommodityLots(actorData, economyData, lots);
    appendLandLots(gameMap, lots);
    return new InventoryLotView(lots);
  }

  /**
   * ★★ D-032 L2b2b：只投影 LAND lot 的视图（不要求 actor/economy 切片）。
   *
   * <p>用于 LAND continuation 的独立入口：只 append LAND carrier lot，普通商品/资产不参与。地图不得为 null（空地图由 {@link
   * GameMap} 自身保证是合法空集）。
   */
  public static InventoryLotView buildLandOnly(GameMap gameMap) {
    Objects.requireNonNull(gameMap, "gameMap");
    List<InventoryLot> lots = new ArrayList<>();
    appendLandLots(gameMap, lots);
    return new InventoryLotView(lots);
  }

  private static void appendCommodityLots(
      ActorData actorData, EconomyData economyData, List<InventoryLot> lots) {
    for (HouseholdInventory inventory : actorData.accounts().values()) {
      HouseholdId owner = inventory.key().household();
      for (Map.Entry<CommodityId, Long> balance : inventory.balances().entrySet()) {
        long quantityMilli = balance.getValue();
        if (quantityMilli <= 0L) {
          continue;
        }
        CommodityId commodity = balance.getKey();
        HouseholdEconomy householdEconomy = economyData.classes().get(owner);
        if (householdEconomy == null) {
          throw new IllegalArgumentException(
              "inventory-lot-location-missing: household="
                  + owner.value()
                  + ", commodity="
                  + commodity.value());
        }
        HexCoord location = householdEconomy.view().hex();
        lots.add(
            new InventoryLot(
                InventoryLotId.forCommodity(owner, location, commodity),
                InventoryLotKind.COMMODITY,
                commodity.toString(),
                owner,
                Optional.of(location),
                quantityMilli,
                LotState.COMPLETE,
                LotOrigin.currentStock(0L),
                true));
      }
    }
  }

  private static void appendLandLots(GameMap gameMap, List<InventoryLot> lots) {
    for (Map.Entry<HexCoord, HexCell> hexEntry : gameMap.hexes().entrySet()) {
      LandTypeArea arable = hexEntry.getValue().land().byType().get(LandTypeId.ARABLE);
      if (arable == null) {
        continue;
      }
      HexCoord location = hexEntry.getKey();
      for (LandComponent component : arable.components()) {
        HouseholdId owner = readOwner(component, location);
        LotState state = readProductionState(component, location);
        lots.add(
            new InventoryLot(
                InventoryLotId.forLand(component.id()),
                InventoryLotKind.LAND,
                LandTypeId.ARABLE.toString(),
                owner,
                Optional.of(location),
                component.quantityMilliMu(),
                state,
                LotOrigin.currentStock(0L),
                false));
      }
    }
  }

  /** 从 LAND component 的抽象属性读出物理生产状态。CROP/STAGE 都缺 ⇒ IDLE；二者必须同时存在，只有其一即具名拒。 */
  private static LotState readProductionState(LandComponent component, HexCoord location) {
    boolean hasCrop = component.attributes().containsKey(LandAttributeKey.CROP);
    boolean hasStage = component.attributes().containsKey(LandAttributeKey.STAGE);
    if (!hasCrop && !hasStage) {
      return LotState.IDLE;
    }
    if (hasCrop != hasStage) {
      List<String> missingKeys = new ArrayList<>();
      if (!hasCrop) {
        missingKeys.add(LandAttributeKey.CROP.wireValue());
      }
      if (!hasStage) {
        missingKeys.add(LandAttributeKey.STAGE.wireValue());
      }
      throw new IllegalArgumentException(
          "inventory-lot-state-incomplete: land="
              + component.id()
              + ", hex="
              + location
              + ", missingKeys="
              + missingKeys);
    }
    String planId = readStateString(component, location, LandAttributeKey.CROP);
    String stageId = readStateString(component, location, LandAttributeKey.STAGE);
    long stageProgressDays = readStateLong(component, location, LandAttributeKey.PROGRESS, 0L);
    long cycleProgressDays =
        readStateLong(component, location, LandAttributeKey.CYCLE_PROGRESS, 0L);
    boolean startInputPaid =
        readStateBoolean(component, location, LandAttributeKey.START_INPUT_PAID, false);
    Map<CommodityId, Long> cycleInputUsedMilli = readCycleInputUsedMilli(component, location);
    return new LotState.InProduction(
        planId,
        stageId,
        stageProgressDays,
        cycleProgressDays,
        Map.of(),
        startInputPaid,
        cycleInputUsedMilli);
  }

  /** 解析 {@code cycle_input:<commodity>} 属性为逐商品累计投入；非法键/值/重复商品具名 fail-closed。 */
  private static Map<CommodityId, Long> readCycleInputUsedMilli(
      LandComponent component, HexCoord location) {
    String prefix = LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX;
    Map<CommodityId, Long> cycleInputUsedMilli = new LinkedHashMap<>();
    Set<String> seenKeys = new LinkedHashSet<>();
    for (Map.Entry<LandAttributeKey, LandAttributeValue> entry :
        component.attributes().entrySet()) {
      LandAttributeKey key = entry.getKey();
      String wireValue = key.wireValue();
      if (!wireValue.startsWith(prefix)) {
        continue;
      }
      if (!seenKeys.add(wireValue)) {
        throw new IllegalArgumentException(
            "inventory-lot-state-invalid: land="
                + component.id()
                + ", hex="
                + location
                + ", key="
                + wireValue
                + ", reason=cycle-input-duplicate-key");
      }
      String commodityText = wireValue.substring(prefix.length());
      if (commodityText.isBlank()) {
        throw new IllegalArgumentException(
            "inventory-lot-state-invalid: land="
                + component.id()
                + ", hex="
                + location
                + ", key="
                + wireValue
                + ", reason=cycle-input-commodity-blank");
      }
      LandAttributeValue attributeValue = entry.getValue();
      if (attributeValue == null) {
        throw new IllegalArgumentException(
            "inventory-lot-state-invalid: land="
                + component.id()
                + ", hex="
                + location
                + ", key="
                + wireValue
                + ", reason=cycle-input-value-null");
      }
      final long usedMilli;
      try {
        usedMilli = attributeValue.longValue();
      } catch (IllegalArgumentException e) {
        throw invalidStateAttribute(component, location, key, attributeValue, e);
      }
      if (usedMilli < 0L) {
        throw new IllegalArgumentException(
            "inventory-lot-state-invalid: land="
                + component.id()
                + ", hex="
                + location
                + ", key="
                + wireValue
                + ", value="
                + usedMilli
                + ", reason=cycle-input-negative");
      }
      CommodityId commodity = new CommodityId(commodityText);
      if (cycleInputUsedMilli.put(commodity, usedMilli) != null) {
        throw new IllegalArgumentException(
            "inventory-lot-state-invalid: land="
                + component.id()
                + ", hex="
                + location
                + ", key="
                + wireValue
                + ", reason=cycle-input-commodity-duplicate");
      }
    }
    return cycleInputUsedMilli;
  }

  private static String readStateString(
      LandComponent component, HexCoord location, LandAttributeKey key) {
    LandAttributeValue value = component.attributes().get(key);
    try {
      return value.stringValue();
    } catch (IllegalArgumentException e) {
      throw invalidStateAttribute(component, location, key, value, e);
    }
  }

  private static long readStateLong(
      LandComponent component, HexCoord location, LandAttributeKey key, long fallback) {
    LandAttributeValue value = component.attributes().get(key);
    if (value == null) {
      return fallback;
    }
    try {
      return value.longValue();
    } catch (IllegalArgumentException e) {
      throw invalidStateAttribute(component, location, key, value, e);
    }
  }

  private static boolean readStateBoolean(
      LandComponent component, HexCoord location, LandAttributeKey key, boolean fallback) {
    LandAttributeValue value = component.attributes().get(key);
    if (value == null) {
      return fallback;
    }
    try {
      return value.booleanValue();
    } catch (IllegalArgumentException e) {
      throw invalidStateAttribute(component, location, key, value, e);
    }
  }

  private static IllegalArgumentException invalidStateAttribute(
      LandComponent component,
      HexCoord location,
      LandAttributeKey key,
      LandAttributeValue value,
      IllegalArgumentException cause) {
    return new IllegalArgumentException(
        "inventory-lot-state-invalid: land="
            + component.id()
            + ", hex="
            + location
            + ", key="
            + key.wireValue()
            + ", attributeKind="
            + value.kind(),
        cause);
  }

  private static HouseholdId readOwner(LandComponent component, HexCoord location) {
    LandAttributeValue ownerAttribute = component.attributes().get(LandAttributeKey.OWNER);
    if (ownerAttribute == null) {
      throw new IllegalArgumentException(
          "inventory-lot-owner-missing: land=" + component.id() + ", hex=" + location);
    }
    final String ownerText;
    try {
      ownerText = ownerAttribute.stringValue();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "inventory-lot-owner-missing: land="
              + component.id()
              + ", hex="
              + location
              + ", ownerAttributeKind="
              + ownerAttribute.kind(),
          e);
    }
    if (ownerText.isBlank()) {
      throw new IllegalArgumentException(
          "inventory-lot-owner-missing: land=" + component.id() + ", hex=" + location);
    }
    try {
      return HouseholdId.parse(ownerText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "inventory-lot-owner-invalid: land="
              + component.id()
              + ", hex="
              + location
              + ", owner="
              + ownerText,
          e);
    }
  }
}
