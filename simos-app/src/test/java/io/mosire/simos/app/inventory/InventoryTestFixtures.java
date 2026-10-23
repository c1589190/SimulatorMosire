package io.mosire.simos.app.inventory;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-032 T1 测试专用夹具：只在本包测试内复用，不触碰生产代码。
 *
 * <p>刻意只走公开构造器，把"最小世界"显式拼出来：家户身份、actor 商品/货币/冻结账本、economy 家户行、map 单格 arable
 * 土地。不伪造生产路径（不反射、不跳过构造期守卫）。
 */
final class InventoryTestFixtures {

  static final String MAP_ID = "inv-test";
  static final HexCoord ORIGIN = new HexCoord(0, 0);
  static final HexCoord OTHER_HEX = new HexCoord(1, 0);

  private InventoryTestFixtures() {}

  // ── 身份 ──────────────────────────────────────────────────────────────────

  static HouseholdId household(String value) {
    return new HouseholdId(value);
  }

  static CommodityId commodity(String value) {
    return new CommodityId(value);
  }

  static CurrencyId currency(String value) {
    return new CurrencyId(value);
  }

  // ── actor 切片 ────────────────────────────────────────────────────────────

  /** 只有商品余额、无货币、无冻结的账本。 */
  static HouseholdInventory inventory(HouseholdId owner, Map<CommodityId, Long> balances) {
    return new HouseholdInventory(new HouseholdAccountKey(owner), balances);
  }

  /** 五表齐全的账本（货币/冻结表都显式给出）。 */
  static HouseholdInventory inventory(
      HouseholdId owner,
      Map<CommodityId, Long> balances,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenBalances,
      Map<CurrencyId, Long> frozenMoney) {
    return new HouseholdInventory(
        new HouseholdAccountKey(owner), balances, money, frozenBalances, frozenMoney);
  }

  static ActorData actorData(HouseholdInventory... inventories) {
    ActorData data = ActorData.empty();
    for (HouseholdInventory inventory : inventories) {
      data = data.withInventory(inventory);
    }
    return data;
  }

  // ── economy 切片 ──────────────────────────────────────────────────────────

  static HouseholdEconomy householdEconomy(HouseholdId id, HexCoord hex) {
    return new HouseholdEconomy(
        id,
        new CohortKey(hex, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
        0L,
        0L,
        0,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  /** 用 家户 → 所在格 建一份最小 EconomyData（键与行内 id 逐值一致）。 */
  static EconomyData economyData(Map<HouseholdId, HexCoord> households) {
    Map<HouseholdId, HouseholdEconomy> classes = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HexCoord> entry : households.entrySet()) {
      classes.put(entry.getKey(), householdEconomy(entry.getKey(), entry.getValue()));
    }
    return EconomyData.empty().withHouseholdEconomies(classes);
  }

  static EconomyData economyData(HouseholdId owner, HexCoord hex) {
    Map<HouseholdId, HexCoord> households = new LinkedHashMap<>();
    households.put(owner, hex);
    return economyData(households);
  }

  // ── map 切片 ──────────────────────────────────────────────────────────────

  /** 单格地图：该格土地由调用方给；地形块按分割不变式原子建好。 */
  static GameMap mapWith(HexCoord hex, HexLand land) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(hex, new HexCell(0.0, land));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(Set.of(hex), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  static GameMap emptyMap() {
    return GameMap.empty();
  }

  /** 一块只含 ARABLE 类型的土地。 */
  static HexLand arableLand(List<LandComponent> components, long nextOrdinal) {
    LandTypeArea area = new LandTypeArea(LandTypeId.ARABLE, components, nextOrdinal);
    Map<LandTypeId, LandTypeArea> byType = new LinkedHashMap<>();
    byType.put(LandTypeId.ARABLE, area);
    return new HexLand(byType);
  }

  static LandComponent arableComponent(
      String id, long quantityMilliMu, Map<LandAttributeKey, LandAttributeValue> attributes) {
    return new LandComponent(new LandComponentId(id), quantityMilliMu, attributes);
  }

  /** 属性表的便捷构造：交替给 key/value。 */
  static Map<LandAttributeKey, LandAttributeValue> attrs(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("attrs 需要成对的 key/value");
    }
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      attributes.put((LandAttributeKey) keyValues[i], (LandAttributeValue) keyValues[i + 1]);
    }
    return attributes;
  }

  static Map<LandAttributeKey, LandAttributeValue> ownerAttrs(String owner) {
    return attrs(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner));
  }

  /** 一份带生产状态的土地属性（owner + crop/stage/progress/cycle_progress；开始投入默认为已支付）。 */
  static Map<LandAttributeKey, LandAttributeValue> statefulAttrs(
      String owner, String crop, String stage, long progressDays, long cycleProgressDays) {
    return statefulAttrs(owner, crop, stage, progressDays, cycleProgressDays, true);
  }

  /** 一份带生产状态的土地属性（显式给 start_input_paid）。 */
  static Map<LandAttributeKey, LandAttributeValue> statefulAttrs(
      String owner,
      String crop,
      String stage,
      long progressDays,
      long cycleProgressDays,
      boolean startInputPaid) {
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    attributes.put(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner));
    attributes.put(LandAttributeKey.CROP, LandAttributeValue.ofString(crop));
    attributes.put(LandAttributeKey.STAGE, LandAttributeValue.ofString(stage));
    attributes.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(progressDays));
    attributes.put(LandAttributeKey.CYCLE_PROGRESS, LandAttributeValue.ofLong(cycleProgressDays));
    attributes.put(LandAttributeKey.START_INPUT_PAID, LandAttributeValue.ofBoolean(startInputPaid));
    return attributes;
  }

  // ── U1b 上下文 ────────────────────────────────────────────────────────────

  static InventoryTransferContext context(
      ActorData actorData, GameMap gameMap, EconomyData economyData) {
    InventoryLotView view = InventoryLotViewBuilder.build(actorData, economyData, gameMap);
    return new InventoryTransferContext(MAP_ID, actorData, gameMap, economyData, view);
  }

  static InventoryTransferPortImpl port(
      ActorData actorData, GameMap gameMap, EconomyData economyData) {
    return InventoryTransferPortImpl.standard(context(actorData, gameMap, economyData));
  }

  /** 把 arable 组件按给定顺序装成真实尺寸的下一个序号（测试里显式声明，不读进程计数器）。 */
  static List<LandComponent> components(LandComponent... components) {
    return new ArrayList<>(List.of(components));
  }
}
