package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
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
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionClaim;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageEndAction;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * D-032 L2b2a 独立测试夹具：只在本包测试内复用，不触碰生产代码。
 *
 * <p>全部走公开构造器：真实 {@link InventoryLotViewBuilder}、真实 {@link ProductionOpportunityBook#from}、真实 LAND
 * component/actor 账本。这里只拼最小世界，不伪造 opportunity / settlement 结果。
 */
final class LandProductionSettlementTestFixtures {

  static final HexCoord H0 = new HexCoord(0, 0);
  static final HexCoord H1 = new HexCoord(1, 0);

  static final HouseholdId H_A = new HouseholdId("h-a");
  static final HouseholdId H_B = new HouseholdId("h-b");
  static final HouseholdId H_C = new HouseholdId("h-c");
  static final HouseholdId H_OTHER = new HouseholdId("h-other");
  static final HouseholdId H_NO_ACCOUNT = new HouseholdId("h-no-account");

  static final CommodityId GRAIN = new CommodityId("grain");
  static final CommodityId FIBER = new CommodityId("fiber");

  static final IndustryId GRAIN_PLAN = new IndustryId("grain_farm");
  static final IndustryId TRANSPORT_PLAN = new IndustryId("carrier_staged");

  static final ActorRef LEGACY_OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, H_A.value());

  static final LandAttributeKey FERTILITY = LandAttributeKey.FERTILITY;

  private LandProductionSettlementTestFixtures() {}

  // ── 方案 ──────────────────────────────────────────────────────────────────

  /**
   * 10d sow（start grain100/tick grain1, NEXT_STAGE）+ 90d grow（tick grain2, HARVEST
   * grain1000/fiber5）。
   */
  static IndustryDefinitionView grainPlan() {
    return stagedPlan(
        GRAIN_PLAN,
        100L,
        List.of(
            new ProductionStageDefinition(
                "stage1",
                10L,
                Map.of(GRAIN, 100L),
                Map.of(GRAIN, 1L),
                ProductionStageEndAction.NEXT_STAGE,
                Map.of()),
            new ProductionStageDefinition(
                "stage2",
                90L,
                Map.of(),
                Map.of(GRAIN, 2L),
                ProductionStageEndAction.HARVEST,
                Map.of(GRAIN, 1000L, FIBER, 5L))));
  }

  /** 1d HARVEST：普通 grain 产出 + transport_capacity_service 服务键同行。 */
  static IndustryDefinitionView transportPlan() {
    return stagedPlan(
        TRANSPORT_PLAN,
        1L,
        List.of(
            new ProductionStageDefinition(
                "haul",
                1L,
                Map.of(),
                Map.of(),
                ProductionStageEndAction.HARVEST,
                Map.of(
                    io.mosire.simos.economy.model.CaravanTransportTemplate.SERVICE_OUTPUT,
                    1000L,
                    GRAIN,
                    7L))));
  }

  /**
   * L2e 旧权威夹具：公开构造器拼出的 {@link ProductionProcess}（进度 0、无 stageState、无台账）。
   *
   * <p>只表达“同一 plan+location 仍有 legacy 实例”这一状态，不伪造任何私有字段；operator 取 {@link #H_A}， 实例 id
   * 由调用方显式给出以便断言具名冲突消息里的 {@code unit}。
   */
  static ProductionProcess legacyProcess(String unitId, IndustryId planId, HexCoord location) {
    return new ProductionProcess(
        new ProductionUnitId(unitId),
        planId,
        new ProductionLocation(location.toString()),
        LEGACY_OPERATOR,
        planId.value(),
        0L,
        Map.of(),
        Map.of(),
        Optional.empty());
  }

  static IndustryDefinitionView stagedPlan(
      IndustryId id, long cycleDays, List<ProductionStageDefinition> stages) {
    return new IndustryDefinitionView(
        id,
        "D-032 L2b2a " + id.value(),
        new RegimeId("tenant"),
        Optional.empty(),
        cycleDays,
        0L,
        Map.of(AssetKind.LAND, 1_000L),
        Map.of(),
        0L,
        10L,
        Map.of(),
        Map.of(),
        List.of(new ClassSlot(new SocialClassId("poor_peasant"), "贫农", 1000)),
        new AllocationRule.Split(1000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED),
        List.of(ProductionRoleDefinition.defaultOperator()),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty(),
        Map.of(),
        stages);
  }

  static Map<IndustryId, IndustryDefinitionView> plans(IndustryDefinitionView... definitions) {
    Map<IndustryId, IndustryDefinitionView> plans = new LinkedHashMap<>();
    for (IndustryDefinitionView definition : definitions) {
      plans.put(definition.id(), definition);
    }
    return plans;
  }

  // ── actor / economy / view / opportunity 真实链路 ──────────────────────────

  static HouseholdInventory inventory(HouseholdId owner, Map<CommodityId, Long> balances) {
    return new HouseholdInventory(new HouseholdAccountKey(owner), balances);
  }

  static HouseholdInventory inventory(
      HouseholdId owner, Map<CommodityId, Long> balances, Map<CommodityId, Long> frozenBalances) {
    return new HouseholdInventory(
        new HouseholdAccountKey(owner), balances, Map.of(), frozenBalances, Map.of());
  }

  static ActorData actorData(HouseholdInventory... inventories) {
    ActorData data = ActorData.empty();
    for (HouseholdInventory inventory : inventories) {
      data = data.withInventory(inventory);
    }
    return data;
  }

  static EconomyData economyFor(ActorData actor) {
    Map<HouseholdId, HexCoord> households = new LinkedHashMap<>();
    for (HouseholdInventory inventory : actor.accounts().values()) {
      households.put(inventory.key().household(), H0);
    }
    return economyData(households);
  }

  static EconomyData economyData(Map<HouseholdId, HexCoord> households) {
    Map<HouseholdId, HouseholdEconomy> classes = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HexCoord> entry : households.entrySet()) {
      classes.put(
          entry.getKey(),
          new HouseholdEconomy(
              entry.getKey(),
              new CohortKey(entry.getValue(), ResidenceKind.RURAL, SocialClassId.POOR_PEASANT),
              0L,
              0L,
              0,
              0L,
              Map.of(),
              Map.of(),
              0L));
    }
    return EconomyData.empty().withHouseholdEconomies(classes);
  }

  static InventoryLotView view(ActorData actor, EconomyData economy, GameMap map) {
    return InventoryLotViewBuilder.build(actor, economy, map);
  }

  static ProductionOpportunityBook opportunities(
      InventoryLotView view, Map<IndustryId, IndustryDefinitionView> plans) {
    return ProductionOpportunityBook.from(view, plans, commodity -> OptionalLong.empty());
  }

  /** 一次真实世界上下文：actor + economy + map + view + plans + opportunity book。 */
  record World(
      ActorData actor,
      EconomyData economy,
      GameMap map,
      Map<IndustryId, IndustryDefinitionView> plans,
      InventoryLotView view,
      ProductionOpportunityBook opportunities) {}

  static World world(ActorData actor, GameMap map, IndustryDefinitionView... definitions) {
    EconomyData economy = economyFor(actor);
    Map<IndustryId, IndustryDefinitionView> plans = plans(definitions);
    InventoryLotView view = view(actor, economy, map);
    ProductionOpportunityBook opportunities = opportunities(view, plans);
    return new World(actor, economy, map, plans, view, opportunities);
  }

  // ── claim ────────────────────────────────────────────────────────────────

  static ProductionClaim claim(
      String claimId,
      LandComponentId lotId,
      IndustryId planId,
      HouseholdId owner,
      HouseholdId operator) {
    return new ProductionClaim(
        claimId, InventoryLotId.forLand(lotId), planId, owner, operator, 0L, 1L);
  }

  // ── map / component 夹具 ──────────────────────────────────────────────────

  static GameMap mapWith(Map<HexCoord, HexLand> lands) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, HexLand> entry : lands.entrySet()) {
      cells.put(entry.getKey(), new HexCell(0.0, entry.getValue()));
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  static HexLand arableLand(LandComponent... components) {
    return arableLand(components.length, components);
  }

  /** 单块/稀疏 component 场景可显式给下一个 ordinal（须大于本格现有最大 ordinal）。 */
  static HexLand arableLand(long nextComponentOrdinal, LandComponent... components) {
    LandTypeArea area =
        new LandTypeArea(LandTypeId.ARABLE, List.of(components), nextComponentOrdinal);
    return new HexLand(Map.of(LandTypeId.ARABLE, area));
  }

  static LandComponent component(
      LandComponentId id,
      long quantityMilliMu,
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    return new LandComponent(id, quantityMilliMu, attributes);
  }

  static LandComponentId id(String value) {
    return new LandComponentId(value);
  }

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

  static Map<LandAttributeKey, LandAttributeValue> idleAttrs(String owner, long fertility) {
    return attrs(
        LandAttributeKey.OWNER, text(owner), LandAttributeKey.FERTILITY, number(fertility));
  }

  /** 直接拼出 carrier lot 的生产属性（模拟前一 tick 已写回 Map 的状态）。 */
  static Map<LandAttributeKey, LandAttributeValue> inProductionAttrs(
      String owner,
      long fertility,
      String planId,
      String stageId,
      long stageProgressDays,
      long cycleProgressDays,
      boolean startInputPaid,
      Map<CommodityId, Long> cycleInputUsedMilli) {
    Map<LandAttributeKey, LandAttributeValue> attributes =
        attrs(
            LandAttributeKey.OWNER,
            text(owner),
            LandAttributeKey.FERTILITY,
            number(fertility),
            LandAttributeKey.CROP,
            text(planId),
            LandAttributeKey.STAGE,
            text(stageId),
            LandAttributeKey.PROGRESS,
            number(stageProgressDays),
            LandAttributeKey.CYCLE_PROGRESS,
            number(cycleProgressDays),
            LandAttributeKey.START_INPUT_PAID,
            bool(startInputPaid));
    for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
      attributes.put(
          new LandAttributeKey(
              LandProductionStateWriter.CYCLE_INPUT_KEY_PREFIX + entry.getKey().value()),
          number(entry.getValue()));
    }
    return attributes;
  }

  static LandAttributeValue text(String value) {
    return LandAttributeValue.ofString(value);
  }

  static LandAttributeValue number(long value) {
    return LandAttributeValue.ofLong(value);
  }

  static LandAttributeValue bool(boolean value) {
    return LandAttributeValue.ofBoolean(value);
  }

  // ── 读数 / 快照 ───────────────────────────────────────────────────────────

  static LandComponent componentOf(GameMap map, HexCoord hex, LandComponentId componentId) {
    return LandComponentOperations.componentOf(
        map.hexes().get(hex).land(), LandTypeId.ARABLE, componentId);
  }

  static long totalArableMilliMu(GameMap map, HexCoord hex) {
    return map.hexes().get(hex).land().totalMilliMu(LandTypeId.ARABLE);
  }

  static long balance(ActorData actor, HouseholdId household, CommodityId commodity) {
    HouseholdInventory inventory = actor.accounts().get(new HouseholdAccountKey(household));
    return inventory == null ? 0L : inventory.balances().getOrDefault(commodity, 0L);
  }

  static long frozen(ActorData actor, HouseholdId household, CommodityId commodity) {
    HouseholdInventory inventory = actor.accounts().get(new HouseholdAccountKey(household));
    return inventory == null ? 0L : inventory.frozenBalances().getOrDefault(commodity, 0L);
  }

  /** 全图 component 快照（含 hex / quantity / attrs），用于“输入逐字段不变”断言。 */
  static List<ComponentSnapshot> snapshot(GameMap map) {
    List<ComponentSnapshot> snapshots = new ArrayList<>();
    for (Map.Entry<HexCoord, HexCell> hexEntry : map.hexes().entrySet()) {
      for (LandTypeArea area : hexEntry.getValue().land().byType().values()) {
        for (LandComponent component : area.components()) {
          snapshots.add(new ComponentSnapshot(hexEntry.getKey(), component));
        }
      }
    }
    return snapshots;
  }

  record ComponentSnapshot(HexCoord hex, LandComponent component) {}
}
