package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.inventory.InventoryLotViewBuilder;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotView;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.time.TransportCapacityBatchBook;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.ProductionClaim;
import io.mosire.simos.production.contract.ProductionOpportunity;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>D-032 L2b2b：LAND continuation 的单推进步接线（先安全口径）</b>（plan §3.3）。
 *
 * <p>★★ <b>目标运行位置：population 之前、一次 Advance 一个 land day</b>。本类只在已有 {@link LotState.InProduction} 的
 * LAND lot 上推进一天：从 map 建 LAND-only {@link InventoryLotView}，从 production 定义建 {@link
 * ProductionOpportunityBook}，给每个可继续的 InProduction lot 生成 owner/operator 相同的 {@link
 * ProductionClaim}， 调用 {@link LandProductionSettlement#settle}，最后返回替换 {@code map}/{@code actor}
 * 切片、并在有运输服务产出时 同时替换 {@code economy} 切片的 {@link SimulationState}。population 随后看到 land 扣料/产出后的
 * actor、写回后的 map 与新增的运输能力批次。
 *
 * <p>★★ <b>边界</b>：
 *
 * <ul>
 *   <li>只处理已有 {@code InProduction} LAND lot；<b>绝不自动启动 IDLE lot</b>（IDLE 选择留给后续 S3）；
 *   <li>一次 Advance 只跑一个 land day。当前 360/真实入口按 1 tick Advance；多 tick 的逐日推进留给后续批次，本类 <b>不伪造</b>多日结果；
 *   <li>不写 production；economy 只在有 service 产出时写运输能力批次；不构造 ChangeSet / Codec；不接 market / labor /
 *       缩规模；不改 legacy unit 生产；
 *   <li>★★ <b>L2e 状态权威</b>：LAND 路径以 Map component（{@code LandComponent} 生产属性）为唯一生产进度权威；旧 {@code
 *       ProductionProcess.stageState/progressDays/cycleInputUsedMilli} 只服务 legacy/non-carrier
 *       单元路径。本类 在生成 claims 后、settlement 前检查同一 {@code (location, plan)} 是否仍有 legacy 实例；存在即具名
 *       fail-closed， 过渡期禁止双权威同时推进；
 *   <li>不读时钟 / 随机数 / 文件：唯一时间输入是调用方给的 {@code tick}；
 *   <li>map / actor / production 三切片任一缺席 ⇒ 原样返回入参 state（不伪造）；没有 {@code InProduction} LAND lot ⇒
 *       原样返回（不建机会、不清状态）；
 *   <li>无成功 receipt 且 map / actor / economy 未变 ⇒ 原样返回 state（业务拒绝不产生状态变化）。
 * </ul>
 *
 * <p>★★ <b>service 产出 sink</b>：{@code transport_capacity_service} 等内部服务键不是普通商品，不进入 actor 库存；
 * settlement 聚合出非空 {@code serviceOutputs} 时，本类把每个 receipt 的服务产出折成 {@code capacityWorkMilli} （{@code
 * outputMilli / 1000}），按 {@code (operator, homeHex, marketRoundIdOf(tick))} 累加进 {@code
 * EconomyData.transportCapacityBatches}，并以同一 {@code ref}/{@code timestamp} 的 {@link
 * EconomySnapshot} 替换 economy 切片。economy 切片缺失、operator 户缺失、homeHex 无市场区或同键 zone 漂移均具名 fail-closed。
 */
public final class LandProductionCycle {

  private static final String MAP = "map";
  private static final String ACTOR = "actor";
  private static final String PRODUCTION = "production";
  private static final String ECONOMY = "economy";

  /** claim id 的唯一拼写点：{@code land-claim:<lotId>:<tick>}。 */
  private static final String CLAIM_ID_PREFIX = "land-claim:";

  private LandProductionCycle() {}

  /**
   * 推进一次 LAND continuation 日。
   *
   * @param state 当前模拟状态（不会被修改）
   * @param tick 本次 land day 的 tick；claim 的 {@code [tick, tick+1)} 即由它确定
   * @return 替换 map/actor 切片（有 service 产出时也替换 economy 切片）的新状态；无 land claim / 无变化时原样返回 {@code state}
   */
  public static SimulationState run(SimulationState state, long tick) {
    Objects.requireNonNull(state, "state");

    MapSnapshot mapSnapshot = mapSnapshotOf(state);
    ActorSnapshot actorSnapshot = actorSnapshotOf(state);
    ProductionSnapshot productionSnapshot = productionSnapshotOf(state);
    // 任一缺席 ⇒ 不伪造：land cycle 需要三片同时在同一个 state 里。
    if (mapSnapshot == null || actorSnapshot == null || productionSnapshot == null) {
      return state;
    }

    GameMap map = mapSnapshot.map();
    ActorData actor = actorSnapshot.data();
    InventoryLotView landView = InventoryLotViewBuilder.buildLandOnly(map);
    // 只做 continuation：没有在产的 LAND lot 就什么都不做（不清、不建机会）。
    if (!hasInProductionLandLot(landView)) {
      return state;
    }

    ProductionOpportunityBook opportunityBook =
        ProductionOpportunityBook.from(
            landView, productionSnapshot.industries(), commodity -> OptionalLong.empty());
    List<ProductionClaim> claims = buildClaims(landView, opportunityBook, tick);
    assertNoLegacyProcessAuthorityConflict(landView, productionSnapshot, claims);

    LandProductionSettlement.Result settlement =
        LandProductionSettlement.settle(
            map, actor, landView, opportunityBook, productionSnapshot.industries(), claims);

    EconomySnapshot economySnapshot = null;
    EconomyData settledEconomy = null;
    boolean economyChanged = false;
    if (!settlement.serviceOutputs().isEmpty()) {
      economySnapshot = economySnapshotForService(state);
      settledEconomy = accumulateTransportCapacity(economySnapshot.data(), settlement, tick);
      economyChanged = !settledEconomy.equals(economySnapshot.data());
    }
    if (settlement.receipts().isEmpty()
        && settlement.map().equals(map)
        && settlement.actor().equals(actor)
        && !economyChanged) {
      return state;
    }

    Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
    modules.put(MAP, new MapSnapshot(mapSnapshot.ref(), mapSnapshot.timestamp(), settlement.map()));
    modules.put(
        ACTOR,
        new ActorSnapshot(actorSnapshot.ref(), actorSnapshot.timestamp(), settlement.actor()));
    if (settledEconomy != null) {
      modules.put(
          ECONOMY,
          new EconomySnapshot(economySnapshot.ref(), economySnapshot.timestamp(), settledEconomy));
    }
    return new SimulationState(state.meta(), modules, state.info());
  }

  /**
   * 把每个成功 receipt 的运输服务产出折成 capacityWorkMilli 并累加进 economy 的批次表。
   *
   * <p>只认 {@link CaravanTransportTemplate#isServiceOutput(CommodityId)}；服务量必须是整千毫单位。 {@code
   * capacityWorkMilli <= 0} 的项跳过，不写零批次。
   */
  private static EconomyData accumulateTransportCapacity(
      EconomyData economy, LandProductionSettlement.Result settlement, long tick) {
    long marketRoundId = TransportCapacityBatchBook.marketRoundIdOf(tick);
    Map<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        new LinkedHashMap<>(economy.transportCapacityBatches());
    for (LandProductionSettlement.Receipt receipt : settlement.receipts()) {
      for (Map.Entry<CommodityId, Long> output : receipt.outputs().entrySet()) {
        CommodityId commodity = output.getKey();
        if (!CaravanTransportTemplate.isServiceOutput(commodity)) {
          continue;
        }
        long outputMilli = output.getValue();
        if (outputMilli % 1_000L != 0L) {
          throw new IllegalStateException(
              "land-production-service-output-not-integral: claim="
                  + receipt.claim().claimId()
                  + ", commodity="
                  + commodity.value()
                  + ", outputMilli="
                  + outputMilli);
        }
        long capacityWorkMilli = outputMilli / 1_000L;
        if (capacityWorkMilli <= 0L) {
          continue;
        }

        HouseholdId operator = receipt.claim().operatorHousehold();
        HouseholdEconomy operatorRow = economy.classes().get(operator);
        if (operatorRow == null) {
          throw new IllegalStateException(
              "land-production-service-operator-household-missing: household="
                  + operator.value()
                  + ", claim="
                  + receipt.claim().claimId());
        }
        HexCoord homeHex = operatorRow.view().hex();
        MarketZoneId marketZone =
            MarketTables.marketIdAt(economy, homeHex)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "land-production-service-market-zone-missing: household="
                                + operator.value()
                                + ", homeHex="
                                + homeHex));
        TransportCapacityBatchKey key =
            new TransportCapacityBatchKey(operator, homeHex, marketRoundId);
        TransportCapacityBatch existing = batches.get(key);
        long nextCapacity = capacityWorkMilli;
        if (existing != null) {
          if (!existing.marketZone().equals(marketZone)) {
            throw new IllegalStateException(
                "land-production-service-market-zone-mismatch: key="
                    + key
                    + ", existing="
                    + existing.marketZone()
                    + ", current="
                    + marketZone);
          }
          nextCapacity = Math.addExact(existing.capacityWorkMilli(), capacityWorkMilli);
        }
        batches.put(key, new TransportCapacityBatch(marketZone, nextCapacity));
      }
    }
    return economy.withTransportCapacityBatches(batches);
  }

  /** service sink 需要的 economy 切片；缺席专属具名，类型不对是装配故障。 */
  private static EconomySnapshot economySnapshotForService(SimulationState state) {
    Snapshot snapshot = state.module(ECONOMY).orElse(null);
    if (snapshot == null) {
      throw new IllegalStateException("land-production-service-economy-missing");
    }
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "land-production-service-economy-invalid: " + snapshot.getClass().getName());
    }
    return economySnapshot;
  }

  /**
   * ★★ L2e 过渡隔离守卫：LAND 路径与旧 {@code ProductionProcess} 状态不得同时推进。
   *
   * <p>只检查本次 claims 将被推进的 {@link LotState.InProduction} LAND lot；匹配键是 lot 的 {@code planId} 与 {@code
   * HexCoord.toString()} 所在地点，命中任一 legacy 实例即具名 fail-closed。其它 location/plan 与 IDLE lot
   * 一律不检查；无命中时行为不变。
   */
  private static void assertNoLegacyProcessAuthorityConflict(
      InventoryLotView landView,
      ProductionSnapshot productionSnapshot,
      List<ProductionClaim> claims) {
    if (claims.isEmpty()) {
      return;
    }
    Map<InventoryLotId, InventoryLot> lotsById = new LinkedHashMap<>();
    for (InventoryLot lot : landView.lots()) {
      lotsById.put(lot.lotId(), lot);
    }
    for (ProductionClaim claim : claims) {
      InventoryLot lot = lotsById.get(claim.lotId());
      if (lot == null || !(lot.state() instanceof LotState.InProduction inProduction)) {
        continue;
      }
      String planId = inProduction.planId();
      HexCoord location =
          lot.location()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "land-production-legacy-process-authority-conflict: lot="
                              + lot.lotId().value()
                              + ", location=<absent>"));
      for (ProductionProcess process : productionSnapshot.instances().values()) {
        if (process.plan().value().equals(planId)
            && process.location().value().equals(location.toString())) {
          throw new IllegalStateException(
              "land-production-legacy-process-authority-conflict: lot="
                  + lot.lotId().value()
                  + ", location="
                  + location
                  + ", plan="
                  + planId
                  + ", unit="
                  + process.id().value());
        }
      }
    }
  }

  /** 只给 InProduction LAND lot 生成 claim；且只在该 planId+lotId 确实存在于机会簿时生成。 */
  private static List<ProductionClaim> buildClaims(
      InventoryLotView landView, ProductionOpportunityBook opportunityBook, long tick) {
    List<ProductionClaim> claims = new ArrayList<>();
    long toTickExclusive = Math.addExact(tick, 1L);
    for (InventoryLot lot : landView.lots()) {
      if (lot.itemKind() != InventoryLotKind.LAND
          || !(lot.state() instanceof LotState.InProduction inProduction)) {
        continue;
      }
      IndustryId planId = IndustryId.parse(inProduction.planId());
      if (!hasOpportunity(opportunityBook, planId, lot)) {
        continue;
      }
      claims.add(
          new ProductionClaim(
              CLAIM_ID_PREFIX + lot.lotId().value() + ":" + tick,
              lot.lotId(),
              planId,
              lot.owner(),
              lot.owner(),
              tick,
              toTickExclusive));
    }
    return claims;
  }

  private static boolean hasOpportunity(
      ProductionOpportunityBook opportunityBook, IndustryId planId, InventoryLot lot) {
    for (ProductionOpportunity opportunity : opportunityBook.opportunities()) {
      if (opportunity.planId().equals(planId)
          && opportunity.carrierLotIds().contains(lot.lotId())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasInProductionLandLot(InventoryLotView landView) {
    for (InventoryLot lot : landView.lots()) {
      if (lot.itemKind() == InventoryLotKind.LAND && lot.state() instanceof LotState.InProduction) {
        return true;
      }
    }
    return false;
  }

  /** map 切片；缺席返回 null；类型不对是装配故障（同 project 既有读口口径）。 */
  private static MapSnapshot mapSnapshotOf(SimulationState state) {
    Snapshot snapshot = state.module(MAP).orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot;
  }

  /** actor 切片；缺席返回 null；类型不对是装配故障。 */
  private static ActorSnapshot actorSnapshotOf(SimulationState state) {
    Snapshot snapshot = state.module(ACTOR).orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot;
  }

  /** production 切片；缺席返回 null；类型不对是装配故障。 */
  private static ProductionSnapshot productionSnapshotOf(SimulationState state) {
    Snapshot snapshot = state.module(PRODUCTION).orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof ProductionSnapshot productionSnapshot)) {
      throw new IllegalStateException(
          "state 的 production 切片不是 ProductionSnapshot: " + snapshot.getClass().getName());
    }
    return productionSnapshot;
  }
}
