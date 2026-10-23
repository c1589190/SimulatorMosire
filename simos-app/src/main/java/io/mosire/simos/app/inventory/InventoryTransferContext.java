package io.mosire.simos.app.inventory;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import java.util.List;
import java.util.Objects;

/**
 * U1b 规划上下文：当前 actor/map/economy 基础状态、统一库存视图与 map 身份。
 *
 * <p>适配器只读本上下文并计算变更集，不直接修改其中的基础状态。
 *
 * @param mapId 当前地图稳定身份（canonical 地址根）
 * @param actorData actor 切片基础状态
 * @param gameMap map 切片基础状态
 * @param economyData economy 切片基础状态
 * @param view U1a 统一库存视图
 */
public record InventoryTransferContext(
    String mapId,
    ActorData actorData,
    GameMap gameMap,
    EconomyData economyData,
    InventoryLotView view) {

  public InventoryTransferContext {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("InventoryTransferContext.mapId 不得为空白");
    }
    Objects.requireNonNull(actorData, "actorData");
    Objects.requireNonNull(gameMap, "gameMap");
    Objects.requireNonNull(economyData, "economyData");
    Objects.requireNonNull(view, "view");
  }

  /** 兼容参数顺序：状态在前、map id 在后。 */
  public InventoryTransferContext(
      ActorData actorData,
      GameMap gameMap,
      EconomyData economyData,
      InventoryLotView view,
      String mapId) {
    this(mapId, actorData, gameMap, economyData, view);
  }

  public ActorData actor() {
    return actorData;
  }

  public GameMap map() {
    return gameMap;
  }

  public EconomyData economy() {
    return economyData;
  }

  /**
   * 一本家户商品账的 canonical 地址：{@code actor:<mapId>:goods.<household>}。
   *
   * <p>形制与既有 app 写点一致：只经 {@link Address}/{@link Namespace}/{@link Entity} 构造，不手拼 canonical 串。
   */
  public String actorAccountAddress(HouseholdId owner) {
    Objects.requireNonNull(owner, "owner");
    return new Address(
            List.of(
                new Namespace("actor"),
                Entity.of(mapId),
                Entity.of("goods", new HouseholdAccountKey(owner).toString())))
        .canonical();
  }

  /** 一格土地的 canonical 地址：{@code map:<mapId>:land.<hex>}。 */
  public String mapLandAddress(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    return new Address(
            List.of(new Namespace("map"), Entity.of(mapId), Entity.of("land", hex.toString())))
        .canonical();
  }
}
