package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.LaneCapacityCommitment;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportDemand;
import io.mosire.simos.economy.model.TransportDemandKey;
import io.mosire.simos.economy.model.TransportHexObservation;
import io.mosire.simos.economy.model.TransportRoundPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ★★★ <b>D-032 C7b1：S7-T/CAP 的运输需求派生、lane 容量承诺与本地观察唯一实现</b>。
 *
 * <pre>
 * S7-A 订单构建完成
 *   → planRound(ctx, round, transportCapacityBatches)
 *       1. 从当轮买/卖订单派生跨 hex 需求；shippingHex = sell.dispatchFrom；
 *       2. 只取 (zone, homeHex=shippingHex, marketRoundId=current) 的 C6 批次；
 *       3. 临时 remaining 投影按 lane 稳定序分配，不修改 C6 持久批次；
 *       4. 产出 demands/commitments/observationsByShippingHex/demandSupplyByZone；
 *       5. 只写 ctx.transportRoundPlan（瞬态）。
 * S7-MATCH
 *   C7b2 只读 ctx.transportRoundPlan 的上述四张表，不得重算。
 * S7-END
 *   调用方逐 zone 用 demandSupplyByZone 更新 C7a 锚价，再清 C6 批次。
 * </pre>
 *
 * <p>★ <b>本类不收费、不建债、不改 matcher/预算/冻结/成交/运费</b>。本任务只做计划与观察。
 *
 * <p>★ <b>确定性</b>：zone / commodity / hex 用规范序；lane 按 work desc → arrivalHex → commodity； 同 hex
 * 批次按可售容量 desc → household asc；所有 map/list 保序不可变。
 *
 * <p>★ <b>失败语义</b>：订单无法解析为 zone/homeHex ⇒ 跳过并 DEBUG，不伪造；当前轮批次跨 zone/hex、 负值、承诺超过需求或本地容量、溢出 ⇒ 具名
 * fail-closed，不写半状态。
 */
public final class TransportDemandBook {

  private static final Comparator<HexCoord> HEX_ORDER =
      Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r);
  private static final Comparator<MarketZoneId> ZONE_ORDER =
      Comparator.comparing(MarketZoneId::value);
  private static final Comparator<CommodityId> COMMODITY_ORDER =
      Comparator.comparing(CommodityId::value);
  private static final Comparator<TransportCapacityBatchKey> BATCH_HOUSEHOLD_ORDER =
      Comparator.comparing((TransportCapacityBatchKey key) -> key.household().value());

  private TransportDemandBook() {}

  /**
   * 从当轮订单派生运输计划并写入 {@link SettlementDayContext#transportRoundPlan}。
   *
   * @param ctx 当次市场轮结算上下文（只写其瞬态 transportRoundPlan）
   * @param round 已装配的当轮市场轮（读取当轮最终买/卖订单快照）
   * @param transportCapacityBatches C6 当前状态中的批次表；只读，不修改
   * @return 本次计划；无市场/无订单 ⇒ {@link TransportRoundPlan#empty(long)}
   */
  public static TransportRoundPlan planRound(
      SettlementDayContext ctx,
      MarketSettlement.MarketRound round,
      Map<TransportCapacityBatchKey, TransportCapacityBatch> transportCapacityBatches) {
    Objects.requireNonNull(ctx, "TransportDemandBook.planRound 的 ctx 不得为 null");
    Objects.requireNonNull(round, "TransportDemandBook.planRound 的 round 不得为 null");
    Objects.requireNonNull(
        transportCapacityBatches,
        "TransportDemandBook.planRound 的 transportCapacityBatches 不得为 null（没有批次请给空表）");
    long marketRoundId = TransportCapacityBatchBook.marketRoundIdOf(ctx.day);
    if (round.day != ctx.day) {
      throw contractFault(
          "transport-demand-round-day-mismatch", "ctx.day=" + ctx.day + " round.day=" + round.day);
    }
    if (ctx.marketTrigger == MarketTrigger.NONE) {
      return storeEmpty(ctx, marketRoundId, "market-trigger-none");
    }
    EconomyDayView view = ctx.dayView != null ? ctx.dayView : ctx.base;
    if (view == null) {
      throw contractFault("transport-demand-day-view-missing", "ctx.day=" + ctx.day);
    }
    if (view.marketTables().isEmpty()) {
      return storeEmpty(ctx, marketRoundId, "no-market-tables");
    }
    if (ctx.topology == null) {
      throw contractFault("transport-demand-topology-missing", "ctx.day=" + ctx.day);
    }

    List<BuyOrder> buys = round.planningBuyOrders();
    List<SellOrder> sells = round.planningSellOrders();
    if (buys.isEmpty() || sells.isEmpty()) {
      return storeEmpty(ctx, marketRoundId, "no-orders");
    }

    // ── 1. 按 zone × commodity 聚合当轮订单；同 hex/跨 zone 的不可解析订单跳过并 DEBUG ────────────
    Map<MarketZoneId, Map<CommodityId, OrderFlow>> flows = new TreeMap<>(ZONE_ORDER);
    for (BuyOrder buy : buys) {
      Optional<MarketZoneId> zone = MarketTables.marketIdAt(view, buy.deliverTo());
      if (zone.isEmpty()) {
        logSkippedOrder("buy", buy.deliverTo(), buy.commodity(), "market-zone-missing");
        continue;
      }
      flows
          .computeIfAbsent(zone.get(), ignored -> new TreeMap<>(COMMODITY_ORDER))
          .computeIfAbsent(buy.commodity(), ignored -> new OrderFlow())
          .addBuy(buy.deliverTo(), buy.quantity());
    }
    for (SellOrder sell : sells) {
      Optional<MarketZoneId> zone = MarketTables.marketIdAt(view, sell.dispatchFrom());
      if (zone.isEmpty()) {
        logSkippedOrder("sell", sell.dispatchFrom(), sell.commodity(), "market-zone-missing");
        continue;
      }
      flows
          .computeIfAbsent(zone.get(), ignored -> new TreeMap<>(COMMODITY_ORDER))
          .computeIfAbsent(sell.commodity(), ignored -> new OrderFlow())
          .addSell(sell.dispatchFrom(), sell.sellable());
    }

    // ── 2. 确定性流量配对：同一卖量只计一次；同 hex 不生成 ─────────────────────────────────────
    LinkedHashMap<TransportDemandKey, Long> quantities = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, Map<CommodityId, OrderFlow>> zoneEntry : flows.entrySet()) {
      MarketZoneId zone = zoneEntry.getKey();
      for (Map.Entry<CommodityId, OrderFlow> commodityEntry : zoneEntry.getValue().entrySet()) {
        CommodityId commodity = commodityEntry.getKey();
        OrderFlow flow = commodityEntry.getValue();
        for (Map.Entry<HexCoord, Long> buyEntry : flow.buys.entrySet()) {
          HexCoord arrivalHex = buyEntry.getKey();
          long buyRemaining = buyEntry.getValue();
          for (Map.Entry<HexCoord, Long> sellEntry : flow.sells.entrySet()) {
            HexCoord shippingHex = sellEntry.getKey();
            if (shippingHex.equals(arrivalHex)) {
              continue; // 同 hex 交易不产生运输需求
            }
            long sellRemaining = sellEntry.getValue();
            if (buyRemaining <= 0L || sellRemaining <= 0L) {
              continue;
            }
            long allocated = Math.min(buyRemaining, sellRemaining);
            if (allocated <= 0L) {
              continue;
            }
            TransportDemandKey key =
                new TransportDemandKey(zone, shippingHex, arrivalHex, commodity);
            mergeQuantity(quantities, key, allocated);
            buyRemaining -= allocated;
            sellEntry.setValue(sellRemaining - allocated);
          }
        }
      }
    }

    // ── 3. 只读当前轮 C6 批次；校验批次 zone 与 homeHex 归属一致 ─────────────────────────────────
    List<BatchRow> currentBatches = new ArrayList<>();
    for (Map.Entry<TransportCapacityBatchKey, TransportCapacityBatch> entry :
        transportCapacityBatches.entrySet()) {
      TransportCapacityBatchKey key =
          Objects.requireNonNull(entry.getKey(), "transportCapacityBatches 的键不得为 null");
      TransportCapacityBatch batch =
          Objects.requireNonNull(entry.getValue(), "transportCapacityBatches 的值不得为 null");
      if (key.marketRoundId() != marketRoundId) {
        continue;
      }
      Optional<MarketZoneId> actualZone = MarketTables.marketIdAt(view, key.homeHex());
      if (actualZone.isEmpty()) {
        throw contractFault(
            "transport-capacity-home-hex-market-zone-missing",
            "household=" + key.household().value() + " homeHex=" + key.homeHex());
      }
      if (!actualZone.get().equals(batch.marketZone())) {
        throw contractFault(
            "transport-capacity-market-zone-mismatch",
            "household="
                + key.household().value()
                + " homeHex="
                + key.homeHex()
                + " batchZone="
                + batch.marketZone().value()
                + " actualZone="
                + actualZone.get().value());
      }
      currentBatches.add(new BatchRow(key, batch));
    }

    Map<HexCoord, List<BatchRow>> batchesByHex = new TreeMap<>(HEX_ORDER);
    Map<HexCoord, Long> offeredByHex = new LinkedHashMap<>();
    Map<MarketZoneId, Long> offeredByZone = new TreeMap<>(ZONE_ORDER);
    Map<TransportCapacityBatchKey, Long> batchCapacity = new LinkedHashMap<>();
    for (BatchRow row : currentBatches) {
      batchesByHex.computeIfAbsent(row.key.homeHex(), ignored -> new ArrayList<>()).add(row);
      offeredByHex.merge(row.key.homeHex(), row.batch.capacityWorkMilli(), Math::addExact);
      offeredByZone.merge(row.batch.marketZone(), row.batch.capacityWorkMilli(), Math::addExact);
      batchCapacity.put(row.key, row.batch.capacityWorkMilli());
    }
    for (List<BatchRow> rows : batchesByHex.values()) {
      rows.sort(
          Comparator.comparingLong((BatchRow row) -> row.batch.capacityWorkMilli())
              .reversed()
              .thenComparing(row -> row.key, BATCH_HOUSEHOLD_ORDER)
              .thenComparing(row -> row.key.homeHex(), HEX_ORDER)
              .thenComparingLong(row -> row.key.marketRoundId()));
    }

    // ── 4. 生成需求 work；lane 稳定序 = work desc → arrivalHex → commodity ────────────────────────
    LinkedHashMap<TransportDemandKey, TransportDemand> demands = new LinkedHashMap<>();
    for (Map.Entry<TransportDemandKey, Long> entry : quantities.entrySet()) {
      TransportDemandKey key = entry.getKey();
      long quantityMilli = entry.getValue();
      long workPerGoodPerMille =
          CapacityDemand.workPerGoodPerMille(
              ctx.topology.commodityFreightBaseMilliOf(key.commodity()),
              ctx.topology.freightPerMilleBetween(key.shippingHex(), key.arrivalHex()));
      long workMilli = CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille);
      demands.put(key, new TransportDemand(key, quantityMilli, workMilli));
    }
    List<TransportDemand> orderedDemands = new ArrayList<>(demands.values());
    orderedDemands.sort(
        Comparator.comparingLong(TransportDemand::workMilli)
            .reversed()
            .thenComparing(demand -> demand.key().arrivalHex(), HEX_ORDER)
            .thenComparing(demand -> demand.key().commodity().value())
            .thenComparing(demand -> demand.key().marketZone().value())
            .thenComparing(demand -> demand.key().shippingHex(), HEX_ORDER));
    LinkedHashMap<TransportDemandKey, TransportDemand> orderedDemandMap = new LinkedHashMap<>();
    for (TransportDemand demand : orderedDemands) {
      orderedDemandMap.put(demand.key(), demand);
    }

    // ── 5. 临时 remaining 投影：一个批次可服务本 hex 多 lane，扣完即止；不修改 C6 批次 ─────────────
    Map<HexCoord, Long> remainingByHex = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Long> entry : offeredByHex.entrySet()) {
      remainingByHex.put(entry.getKey(), entry.getValue());
    }
    Map<HexCoord, Long> committedByHex = new LinkedHashMap<>();
    Map<TransportCapacityBatchKey, Long> committedByBatch = new LinkedHashMap<>();
    LinkedHashMap<TransportDemandKey, List<LaneCapacityCommitment>> commitments =
        new LinkedHashMap<>();
    LinkedHashMap<TransportDemandKey, Long> committedByDemand = new LinkedHashMap<>();
    long totalCommittedWorkMilli = 0L;
    for (TransportDemand demand : orderedDemands) {
      TransportDemandKey demandKey = demand.key();
      long demandRemaining = demand.workMilli();
      List<LaneCapacityCommitment> demandCommitments = new ArrayList<>();
      List<BatchRow> localBatches = batchesByHex.getOrDefault(demandKey.shippingHex(), List.of());
      for (BatchRow row : localBatches) {
        if (demandRemaining <= 0L) {
          break;
        }
        long remaining = remainingByHex.getOrDefault(row.key.homeHex(), 0L);
        if (remaining <= 0L) {
          continue;
        }
        long committed = Math.min(remaining, demandRemaining);
        if (committed <= 0L) {
          continue;
        }
        LaneCapacityCommitment rowCommitment =
            new LaneCapacityCommitment(demandKey, row.key, row.key.household(), committed);
        demandCommitments.add(rowCommitment);
        demandRemaining -= committed;
        remainingByHex.put(row.key.homeHex(), remaining - committed);
        committedByHex.merge(row.key.homeHex(), committed, Math::addExact);
        committedByBatch.merge(row.key, committed, Math::addExact);
        totalCommittedWorkMilli = Math.addExact(totalCommittedWorkMilli, committed);
      }
      commitments.put(demandKey, Collections.unmodifiableList(new ArrayList<>(demandCommitments)));
      committedByDemand.put(demandKey, demand.workMilli() - demandRemaining);
    }

    // 计划期不变量：每一份 lane 承诺不超过 demand；同一批次跨 lane 总承诺不超过容量。
    for (Map.Entry<TransportDemandKey, Long> entry : committedByDemand.entrySet()) {
      TransportDemand demand = orderedDemandMap.get(entry.getKey());
      if (entry.getValue() > demand.workMilli()) {
        throw contractFault(
            "transport-lane-commitment-exceeds-demand",
            "demand="
                + entry.getKey()
                + " committed="
                + entry.getValue()
                + " work="
                + demand.workMilli());
      }
    }
    for (Map.Entry<TransportCapacityBatchKey, Long> entry : committedByBatch.entrySet()) {
      long capacity = batchCapacity.getOrDefault(entry.getKey(), 0L);
      if (entry.getValue() > capacity) {
        throw contractFault(
            "transport-lane-batch-oversold",
            "batch=" + entry.getKey() + " committed=" + entry.getValue() + " capacity=" + capacity);
      }
    }

    // ── 6. 逐 (zone, shippingHex) 观察；本地 unserved/unsold 保留，不先净额抵消 ───────────────────
    Map<HexCoord, Long> demandByHex = new LinkedHashMap<>();
    Map<MarketZoneId, Long> demandByZone = new TreeMap<>(ZONE_ORDER);
    Map<HexCoord, MarketZoneId> zoneByHex = new TreeMap<>(HEX_ORDER);
    for (TransportDemand demand : orderedDemands) {
      TransportDemandKey key = demand.key();
      Optional<MarketZoneId> actualZone = MarketTables.marketIdAt(view, key.shippingHex());
      if (actualZone.isEmpty() || !actualZone.get().equals(key.marketZone())) {
        throw contractFault(
            "transport-demand-shipping-hex-zone-mismatch",
            "key=" + key + " actualZone=" + actualZone.map(MarketZoneId::value).orElse("-"));
      }
      demandByHex.merge(key.shippingHex(), demand.workMilli(), Math::addExact);
      demandByZone.merge(key.marketZone(), demand.workMilli(), Math::addExact);
      putZoneForHex(zoneByHex, key.shippingHex(), key.marketZone());
    }
    for (BatchRow row : currentBatches) {
      putZoneForHex(zoneByHex, row.key.homeHex(), row.batch.marketZone());
    }

    LinkedHashMap<HexCoord, TransportHexObservation> observations = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, MarketZoneId> entry : zoneByHex.entrySet()) {
      HexCoord shippingHex = entry.getKey();
      MarketZoneId zone = entry.getValue();
      long demandWorkMilli = demandByHex.getOrDefault(shippingHex, 0L);
      long offeredWorkMilli = offeredByHex.getOrDefault(shippingHex, 0L);
      long committedWorkMilli = committedByHex.getOrDefault(shippingHex, 0L);
      if (committedWorkMilli > demandWorkMilli || committedWorkMilli > offeredWorkMilli) {
        throw contractFault(
            "transport-hex-observation-overcommitted",
            "shippingHex="
                + shippingHex
                + " demand="
                + demandWorkMilli
                + " offered="
                + offeredWorkMilli
                + " committed="
                + committedWorkMilli);
      }
      observations.put(
          shippingHex,
          new TransportHexObservation(
              zone,
              shippingHex,
              marketRoundId,
              demandWorkMilli,
              offeredWorkMilli,
              committedWorkMilli,
              demandWorkMilli - committedWorkMilli,
              offeredWorkMilli - committedWorkMilli,
              TransportPriceAnchorBook.pricePerWorkMilli(view, zone)));
    }

    // ── 7. 区级 D/S 与总计；D 与 S 分开保留，禁止先做本地净额抵消 ─────────────────────────────────
    Map<MarketZoneId, Long> zones = new TreeMap<>(ZONE_ORDER);
    for (MarketZoneId zone : demandByZone.keySet()) {
      zones.put(zone, 0L);
    }
    for (MarketZoneId zone : offeredByZone.keySet()) {
      zones.put(zone, 0L);
    }
    LinkedHashMap<MarketZoneId, long[]> demandSupplyByZone = new LinkedHashMap<>();
    long totalDemandWorkMilli = 0L;
    long totalOfferedWorkMilli = 0L;
    for (MarketZoneId zone : zones.keySet()) {
      long demand = demandByZone.getOrDefault(zone, 0L);
      long supply = offeredByZone.getOrDefault(zone, 0L);
      demandSupplyByZone.put(zone, new long[] {demand, supply});
      totalDemandWorkMilli = Math.addExact(totalDemandWorkMilli, demand);
      totalOfferedWorkMilli = Math.addExact(totalOfferedWorkMilli, supply);
    }

    TransportRoundPlan plan =
        new TransportRoundPlan(
            marketRoundId,
            orderedDemandMap,
            commitments,
            observations,
            demandSupplyByZone,
            totalDemandWorkMilli,
            totalOfferedWorkMilli,
            totalCommittedWorkMilli);
    ctx.transportRoundPlan = plan;
    logPlan(ctx, plan);
    return plan;
  }

  /** S7-END：把同一份计划的逐 hex 观察写到日志（INFO 汇总 + DEBUG 逐 hex）；不写状态、不改锚价。 */
  public static void logS7EndObservations(SettlementDayContext ctx) {
    Objects.requireNonNull(ctx, "ctx");
    TransportRoundPlan plan = ctx.transportRoundPlan;
    if (plan == null || plan.isEmpty()) {
      return;
    }
    EventLog.channel(EconomyLog.market())
        .info(
            LogEvent.of(
                "TRANSPORT_HEX_OBSERVATIONS_RECORDED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.day,
                "marketRoundId",
                plan.marketRoundId(),
                "zones",
                plan.demandSupplyByZone().size(),
                "hexes",
                plan.observationsByShippingHex().size(),
                "totalDemandWorkMilli",
                plan.totalDemandWorkMilli(),
                "totalOfferedWorkMilli",
                plan.totalOfferedWorkMilli(),
                "totalCommittedWorkMilli",
                plan.totalCommittedWorkMilli()));
    if (EconomyLog.market().isDebugEnabled()) {
      for (TransportHexObservation observation : plan.observationsByShippingHex().values()) {
        EventLog.channel(EconomyLog.market())
            .debug(
                LogEvent.of(
                    "TRANSPORT_HEX_OBSERVATION",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "marketRoundId",
                    observation.marketRoundId(),
                    "zone",
                    observation.marketZone().value(),
                    "shippingHex",
                    observation.shippingHex().toString(),
                    "demandWorkMilli",
                    observation.demandWorkMilli(),
                    "offeredWorkMilli",
                    observation.offeredWorkMilli(),
                    "committedWorkMilli",
                    observation.committedWorkMilli(),
                    "unservedWorkMilli",
                    observation.unservedWorkMilli(),
                    "unsoldWorkMilli",
                    observation.unsoldWorkMilli(),
                    "anchorPricePerWorkMilli",
                    observation.anchorPricePerWorkMilli()));
      }
    }
  }

  private static TransportRoundPlan storeEmpty(
      SettlementDayContext ctx, long marketRoundId, String reason) {
    TransportRoundPlan empty = TransportRoundPlan.empty(marketRoundId);
    ctx.transportRoundPlan = empty;
    if (EconomyLog.market().isDebugEnabled()) {
      EventLog.channel(EconomyLog.market())
          .debug(
              LogEvent.of(
                  "TRANSPORT_ROUND_PLAN_EMPTY",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "marketRoundId",
                  marketRoundId,
                  "reason",
                  reason));
    }
    return empty;
  }

  private static void logPlan(SettlementDayContext ctx, TransportRoundPlan plan) {
    if (!EconomyLog.market().isDebugEnabled()) {
      return;
    }
    EventLog.channel(EconomyLog.market())
        .debug(
            LogEvent.of(
                "TRANSPORT_ROUND_PLANNED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.day,
                "marketRoundId",
                plan.marketRoundId(),
                "demands",
                plan.demands().size(),
                "commitments",
                plan.commitments().values().stream().mapToInt(List::size).sum(),
                "observations",
                plan.observationsByShippingHex().size(),
                "zones",
                plan.demandSupplyByZone().size(),
                "totalDemandWorkMilli",
                plan.totalDemandWorkMilli(),
                "totalOfferedWorkMilli",
                plan.totalOfferedWorkMilli(),
                "totalCommittedWorkMilli",
                plan.totalCommittedWorkMilli()));
  }

  private static void logSkippedOrder(
      String side, HexCoord hex, CommodityId commodity, String reason) {
    if (!EconomyLog.market().isDebugEnabled()) {
      return;
    }
    EventLog.channel(EconomyLog.market())
        .debug(
            LogEvent.of(
                "TRANSPORT_DEMAND_ORDER_SKIPPED",
                EconomyLogSource.ECONOMY_MARKET,
                "side",
                side,
                "hex",
                hex.toString(),
                "commodity",
                commodity.value(),
                "reason",
                reason));
  }

  private static void mergeQuantity(
      LinkedHashMap<TransportDemandKey, Long> quantities, TransportDemandKey key, long quantity) {
    if (quantity <= 0L) {
      return;
    }
    quantities.merge(key, quantity, Math::addExact);
  }

  private static void putZoneForHex(
      Map<HexCoord, MarketZoneId> zoneByHex, HexCoord hex, MarketZoneId zone) {
    MarketZoneId previous = zoneByHex.putIfAbsent(hex, zone);
    if (previous != null && !previous.equals(zone)) {
      throw contractFault(
          "transport-observation-hex-zone-conflict",
          "hex=" + hex + " first=" + previous.value() + " second=" + zone.value());
    }
  }

  private static IllegalStateException contractFault(String word, String detail) {
    EventLog.channel(EconomyLog.market())
        .error(
            LogEvent.of(
                "TRANSPORT_DEMAND_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_MARKET,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(word + ": " + detail);
  }

  /** 一个 zone × commodity 的订单聚合；TreeMap 保证确定性的 hex 序。 */
  private static final class OrderFlow {
    final TreeMap<HexCoord, Long> buys = new TreeMap<>(HEX_ORDER);
    final TreeMap<HexCoord, Long> sells = new TreeMap<>(HEX_ORDER);

    void addBuy(HexCoord hex, long quantity) {
      if (quantity <= 0L) {
        return;
      }
      buys.merge(hex, quantity, Math::addExact);
    }

    void addSell(HexCoord hex, long quantity) {
      if (quantity <= 0L) {
        return;
      }
      sells.merge(hex, quantity, Math::addExact);
    }
  }

  /** 当前轮 C6 批次的一行只读投影。 */
  private record BatchRow(TransportCapacityBatchKey key, TransportCapacityBatch batch) {}
}
