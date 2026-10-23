package io.mosire.simos.app.inventory;

import static io.mosire.simos.app.inventory.InventoryTestFixtures.actorData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableComponent;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.arableLand;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.commodity;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.components;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.context;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.currency;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.economyData;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.household;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.inventory;
import static io.mosire.simos.app.inventory.InventoryTestFixtures.mapWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.inventory.InventoryBackendKind;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotMutation;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferPort;
import io.mosire.simos.economy.api.inventory.InventoryTransferReason;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;
import io.mosire.simos.economy.api.inventory.LotOrigin;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * D-032 T1（U1b）：{@link InventoryTransferPortImpl} + 商品/土地适配器正负例。
 *
 * <p>判据来自任务书 §8.1 / §4.4–§4.6：商品 source 减/target 加、0 余额保留、货币/冻结表保留；frozen 不足、余额不足、 target 同 owner、无
 * lot、location 缺失具名拒；土地部分转移复制 state、数量守恒、target owner 正确、 targetLocation 不等于 land hex 拒、source
 * owner mismatch 拒；plan 不改 base 状态；moduleChanges 非空、receipt 字段一致、reads/writes 稳定。
 *
 * <p>变异自证锚点：{@link #frozenShortfallIsRejectedNamed()} 在“删掉 frozen 不足检查”的改坏下必须变红。
 */
class InventoryTransferPortTest {

  private static final String MAP_ID = InventoryTestFixtures.MAP_ID;
  private static final HexCoord HEX = InventoryTestFixtures.ORIGIN;
  private static final HouseholdId H1 = household("h1");
  private static final HouseholdId H2 = household("h2");
  private static final HouseholdId H3 = household("h3");
  private static final CommodityId GRAIN = commodity("grain");
  private static final CommodityId FIBER = commodity("fiber");
  private static final CurrencyId SILVER = currency("silver");
  private static final LandComponentId SOURCE_LAND = new LandComponentId("arable@0_0:0");

  // ── 商品转移 ──────────────────────────────────────────────────────────────

  @Test
  void commodityTransferMovesBalanceAndKeepsZeroAndFrozenTables() {
    ActorData baseActor =
        actorData(
            inventory(
                H1,
                Map.of(GRAIN, 1_000L, FIBER, 100L),
                Map.of(SILVER, 500L),
                Map.of(FIBER, 40L),
                Map.of(SILVER, 100L)),
            inventory(H2, Map.of(GRAIN, 200L), Map.of(SILVER, 50L), Map.of(), Map.of()));
    EconomyData baseEconomy = economyData(Map.of(H1, HEX, H2, HEX));
    GameMap baseMap = InventoryTestFixtures.emptyMap();
    InventoryTransferContext ctx = context(baseActor, baseMap, baseEconomy);
    InventoryTransferPortImpl port = InventoryTransferPortImpl.standard(ctx);
    InventoryTransferRequest request =
        transfer("t-commodity", InventoryLotId.forCommodity(H1, HEX, GRAIN), 300L, H2);

    InventoryTransferPlan plan = port.plan(request);

    // plan 不改 base 状态：引用与逐值都不变。
    assertThat(ctx.actorData()).isSameAs(baseActor).isEqualTo(baseActor);
    assertThat(ctx.gameMap()).isSameAs(baseMap).isEqualTo(baseMap);
    assertThat(ctx.economyData()).isSameAs(baseEconomy).isEqualTo(baseEconomy);
    assertThat(baseActor.accounts().get(new HouseholdAccountKey(H1)).balances())
        .containsEntry(GRAIN, 1_000L);

    ActorData applied =
        ActorChangeSet.apply(
            (ActorChangeSet) plan.moduleChanges().get(InventoryTransferPlan.ACTOR_MODULE_KEY),
            baseActor);
    HouseholdInventory source = applied.accounts().get(new HouseholdAccountKey(H1));
    HouseholdInventory target = applied.accounts().get(new HouseholdAccountKey(H2));
    assertThat(source.balances()).as("source 减").containsEntry(GRAIN, 700L);
    assertThat(target.balances()).as("target 加").containsEntry(GRAIN, 500L);
    assertThat(source.money()).as("source 货币表保留").isEqualTo(Map.of(SILVER, 500L));
    assertThat(source.frozenBalances()).as("source 商品冻结表保留").isEqualTo(Map.of(FIBER, 40L));
    assertThat(source.frozenMoney()).as("source 货币冻结表保留").isEqualTo(Map.of(SILVER, 100L));
    assertThat(target.money()).as("target 货币表保留").isEqualTo(Map.of(SILVER, 50L));

    assertThat(plan.moduleChanges()).containsOnlyKeys(InventoryTransferPlan.ACTOR_MODULE_KEY);
    assertThat(plan.mutations())
        .singleElement()
        .isInstanceOf(InventoryLotMutation.CommodityBalanceMutation.class);
    InventoryLotMutation.CommodityBalanceMutation mutation =
        (InventoryLotMutation.CommodityBalanceMutation) plan.mutations().get(0);
    assertThat(mutation.sourceOwner()).isEqualTo(H1);
    assertThat(mutation.targetOwner()).isEqualTo(H2);
    assertThat(mutation.commodity()).isEqualTo(GRAIN);
    assertThat(mutation.quantityMilli()).isEqualTo(300L);

    assertThat(plan.receipt().transferId()).isEqualTo(request.transferId());
    assertThat(plan.receipt().sourceLotId()).isEqualTo(request.sourceLotId());
    assertThat(plan.receipt().movedQuantityMilli()).isEqualTo(300L);
    assertThat(plan.receipt().backendKind()).isEqualTo(InventoryBackendKind.ACTOR_GOODS);
    assertThat(plan.receipt().statePreserved()).isTrue();
    assertThat(plan.receipt().targetLotIds())
        .containsExactly(InventoryLotId.forCommodity(H2, HEX, GRAIN));
    assertThat(plan.reads()).isEqualTo(plan.writes());
    assertThat(plan.reads())
        .containsExactly(ctx.actorAccountAddress(H1), ctx.actorAccountAddress(H2));
  }

  @Test
  void fullCommodityTransferKeepsZeroBalanceRow() {
    ActorData baseActor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)), inventory(H2, Map.of()));
    InventoryTransferPortImpl port =
        InventoryTransferPortImpl.standard(
            context(
                baseActor,
                InventoryTestFixtures.emptyMap(),
                economyData(Map.of(H1, HEX, H2, HEX))));
    InventoryTransferRequest request =
        transfer("t-zero", InventoryLotId.forCommodity(H1, HEX, GRAIN), 1_000L, H2);

    InventoryTransferPlan plan = port.plan(request);
    ActorData applied =
        ActorChangeSet.apply(
            (ActorChangeSet) plan.moduleChanges().get(InventoryTransferPlan.ACTOR_MODULE_KEY),
            baseActor);
    HouseholdInventory source = applied.accounts().get(new HouseholdAccountKey(H1));

    assertThat(source.balances()).as("余额转空后 0 行保留").containsEntry(GRAIN, 0L);
    assertThat(applied.accounts().get(new HouseholdAccountKey(H2)).balances())
        .containsEntry(GRAIN, 1_000L);
  }

  @Test
  void readsAndWritesAreStableAcrossRepeatedPlans() {
    ActorData baseActor = actorData(inventory(H1, Map.of(GRAIN, 1_000L)), inventory(H2, Map.of()));
    InventoryTransferPortImpl port =
        InventoryTransferPortImpl.standard(
            context(
                baseActor,
                InventoryTestFixtures.emptyMap(),
                economyData(Map.of(H1, HEX, H2, HEX))));

    InventoryTransferPlan first =
        port.plan(transfer("t-stable", InventoryLotId.forCommodity(H1, HEX, GRAIN), 10L, H2));
    InventoryTransferPlan second =
        port.plan(transfer("t-stable", InventoryLotId.forCommodity(H1, HEX, GRAIN), 10L, H2));

    assertThat(first.reads()).isEqualTo(second.reads());
    assertThat(first.writes()).isEqualTo(second.writes());
    assertThat(first.reads()).isEqualTo(first.writes());
  }

  @Test
  void frozenShortfallIsRejectedNamed() {
    ActorData baseActor =
        actorData(inventory(H1, Map.of(GRAIN, 1_000L), Map.of(), Map.of(GRAIN, 800L), Map.of()));
    InventoryTransferPortImpl port =
        InventoryTransferPortImpl.standard(
            context(baseActor, InventoryTestFixtures.emptyMap(), economyData(H1, HEX)));

    assertThatThrownBy(
            () ->
                port.plan(
                    transfer("t-frozen", InventoryLotId.forCommodity(H1, HEX, GRAIN), 500L, H2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_FROZEN_SHORTFALL);
  }

  @Test
  void balanceShortfallSameOwnerMissingLotAndLocationAreRejectedNamed() {
    ActorData baseActor = actorData(inventory(H1, Map.of(GRAIN, 100L)));
    EconomyData economy = economyData(H1, HEX);
    InventoryTransferPortImpl port =
        InventoryTransferPortImpl.standard(
            context(baseActor, InventoryTestFixtures.emptyMap(), economy));

    assertThatThrownBy(
            () ->
                port.plan(
                    transfer("t-balance", InventoryLotId.forCommodity(H1, HEX, GRAIN), 200L, H2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_QUANTITY_EXCEEDS_AVAILABLE);
    assertThatThrownBy(
            () ->
                port.plan(transfer("t-same", InventoryLotId.forCommodity(H1, HEX, GRAIN), 10L, H1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_TARGET_OWNER_SAME);
    assertThatThrownBy(
            () ->
                port.plan(
                    transfer("t-missing", InventoryLotId.parse("commodity:h1:9_9:grain"), 10L, H2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_LOT_MISSING);

    // location 缺失：手工造一个无 location 的 COMMODITY lot（构造层允许），adapter 缺省目标位置时必须具名拒。
    InventoryLot noLocation =
        new InventoryLot(
            InventoryLotId.parse("commodity:h1:no-location:grain"),
            InventoryLotKind.COMMODITY,
            "grain",
            H1,
            Optional.empty(),
            1_000L,
            LotState.COMPLETE,
            LotOrigin.currentStock(0L),
            true);
    InventoryTransferContext noLocationCtx =
        new InventoryTransferContext(
            MAP_ID,
            baseActor,
            InventoryTestFixtures.emptyMap(),
            economy,
            new InventoryLotView(List.of(noLocation)));
    InventoryTransferPortImpl noLocationPort = InventoryTransferPortImpl.standard(noLocationCtx);

    assertThatThrownBy(
            () -> noLocationPort.plan(transfer("t-no-location", noLocation.lotId(), 10L, H2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_LOCATION_MISSING);
  }

  // ── 土地转移 ──────────────────────────────────────────────────────────────

  @Test
  void landPartialTransferCopiesStateAndConservesQuantity() {
    GameMap baseMap = statefulLandMap("h1");
    ActorData baseActor = ActorData.empty();
    EconomyData baseEconomy = EconomyData.empty();
    InventoryTransferContext ctx = context(baseActor, baseMap, baseEconomy);
    InventoryTransferPortImpl port = InventoryTransferPortImpl.standard(ctx);
    InventoryLotId landLot = InventoryLotId.forLand(SOURCE_LAND);
    InventoryTransferRequest request =
        new InventoryTransferRequest(
            new io.mosire.simos.economy.api.inventory.InventoryTransferId("t-land"),
            landLot,
            3_000L,
            H2,
            Optional.of(HEX),
            InventoryTransferReason.MARKET_TRADE);

    InventoryTransferPlan plan = port.plan(request);

    assertThat(ctx.gameMap()).isSameAs(baseMap).isEqualTo(baseMap);
    assertThat(ctx.actorData()).isSameAs(baseActor);
    assertThat(ctx.economyData()).isSameAs(baseEconomy);

    GameMap applied =
        MapChangeSet.apply(
            (MapChangeSet) plan.moduleChanges().get(InventoryTransferPlan.MAP_MODULE_KEY), baseMap);
    LandTypeArea area = applied.hexes().get(HEX).land().byType().get(LandTypeId.ARABLE);
    assertThat(area.components()).hasSize(2);
    LandComponent source =
        area.components().stream()
            .filter(c -> c.id().equals(SOURCE_LAND))
            .findFirst()
            .orElseThrow();
    LandComponent target =
        area.components().stream()
            .filter(c -> !c.id().equals(SOURCE_LAND))
            .findFirst()
            .orElseThrow();
    assertThat(source.quantityMilliMu()).isEqualTo(7_000L);
    assertThat(target.quantityMilliMu()).isEqualTo(3_000L);
    assertThat(source.attributes().get(LandAttributeKey.OWNER))
        .isEqualTo(LandAttributeValue.ofString("h1"));
    assertThat(target.attributes().get(LandAttributeKey.OWNER))
        .as("target owner 正确")
        .isEqualTo(LandAttributeValue.ofString("h2"));
    assertThat(target.attributes().get(LandAttributeKey.STAGE))
        .as("部分转移复制 STAGE")
        .isEqualTo(LandAttributeValue.ofString("stage2"));
    assertThat(target.attributes().get(LandAttributeKey.PROGRESS))
        .as("部分转移复制 PROGRESS")
        .isEqualTo(LandAttributeValue.ofLong(10L));
    assertThat(target.attributes().get(LandAttributeKey.CYCLE_PROGRESS))
        .isEqualTo(LandAttributeValue.ofLong(40L));
    assertThat(target.attributes().get(LandAttributeKey.CROP))
        .isEqualTo(LandAttributeValue.ofString("wheat"));
    assertThat(applied.hexes().get(HEX).land().totalMilliMu(LandTypeId.ARABLE))
        .as("数量守恒")
        .isEqualTo(10_000L);

    assertThat(plan.moduleChanges()).containsOnlyKeys(InventoryTransferPlan.MAP_MODULE_KEY);
    assertThat(plan.mutations())
        .singleElement()
        .isInstanceOf(InventoryLotMutation.LandComponentMutation.class);
    InventoryLotMutation.LandComponentMutation mutation =
        (InventoryLotMutation.LandComponentMutation) plan.mutations().get(0);
    assertThat(mutation.hex()).isEqualTo(HEX);
    assertThat(mutation.landType()).isEqualTo(LandTypeId.ARABLE);
    assertThat(mutation.sourceComponentId()).isEqualTo(SOURCE_LAND);
    assertThat(mutation.targetOwner()).isEqualTo(H2);
    assertThat(mutation.quantityMilli()).isEqualTo(3_000L);

    assertThat(plan.receipt().backendKind()).isEqualTo(InventoryBackendKind.MAP_LAND);
    assertThat(plan.receipt().statePreserved()).isTrue();
    assertThat(plan.receipt().targetLotIds()).contains(InventoryLotId.forLand(target.id()));
    assertThat(plan.reads()).isEqualTo(plan.writes());
    assertThat(plan.reads()).containsExactly(ctx.mapLandAddress(HEX));
  }

  @Test
  void landTargetLocationMismatchAndSourceOwnerMismatchAreRejectedNamed() {
    GameMap baseMap = statefulLandMap("h1");
    InventoryTransferPortImpl port =
        InventoryTransferPortImpl.standard(
            context(ActorData.empty(), baseMap, EconomyData.empty()));

    assertThatThrownBy(
            () ->
                port.plan(
                    new InventoryTransferRequest(
                        new io.mosire.simos.economy.api.inventory.InventoryTransferId("t-loc"),
                        InventoryLotId.forLand(SOURCE_LAND),
                        1_000L,
                        H2,
                        Optional.of(new HexCoord(9, 9)),
                        InventoryTransferReason.MARKET_TRADE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_TARGET_LOCATION_MISMATCH);

    // view 里 lot owner=h1，而 map component 实际 owner=h2 ⇒ source owner mismatch。
    InventoryLot mismatchedLot =
        new InventoryLot(
            InventoryLotId.forLand(SOURCE_LAND),
            InventoryLotKind.LAND,
            "arable",
            H1,
            Optional.of(HEX),
            10_000L,
            LotState.IDLE,
            LotOrigin.currentStock(0L),
            false);
    InventoryTransferContext mismatchedCtx =
        new InventoryTransferContext(
            MAP_ID,
            ActorData.empty(),
            statefulLandMap("h2"),
            EconomyData.empty(),
            new InventoryLotView(List.of(mismatchedLot)));
    InventoryTransferPortImpl mismatchedPort = InventoryTransferPortImpl.standard(mismatchedCtx);

    assertThatThrownBy(
            () ->
                mismatchedPort.plan(
                    new InventoryTransferRequest(
                        new io.mosire.simos.economy.api.inventory.InventoryTransferId("t-owner"),
                        InventoryLotId.forLand(SOURCE_LAND),
                        1_000L,
                        H3,
                        Optional.of(HEX),
                        InventoryTransferReason.MARKET_TRADE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(InventoryTransferPort.REASON_SOURCE_OWNER_MISMATCH);
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────

  private static InventoryTransferRequest transfer(
      String id, InventoryLotId sourceLotId, long quantityMilli, HouseholdId targetOwner) {
    return new InventoryTransferRequest(
        new io.mosire.simos.economy.api.inventory.InventoryTransferId(id),
        sourceLotId,
        quantityMilli,
        targetOwner,
        Optional.empty(),
        InventoryTransferReason.MARKET_TRADE);
  }

  private static GameMap statefulLandMap(String owner) {
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    attributes.put(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner));
    attributes.put(LandAttributeKey.CROP, LandAttributeValue.ofString("wheat"));
    attributes.put(LandAttributeKey.STAGE, LandAttributeValue.ofString("stage2"));
    attributes.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(10L));
    attributes.put(LandAttributeKey.CYCLE_PROGRESS, LandAttributeValue.ofLong(40L));
    HexLand land =
        arableLand(components(arableComponent(SOURCE_LAND.value(), 10_000L, attributes)), 1L);
    return mapWith(HEX, land);
  }
}
