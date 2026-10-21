package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * ★★★ <b>D-032 C8：本轮市场成交中 PROLETARIAN 买方实际取得运输马匹的唯一读口。</b>
 *
 * <pre>
 * 输入：本轮 MarketReport.fills + S7-M2 后/S7-A 前 ownershipClass 快照
 * 只认：commodity == transport_horse，且该成交已实际交付（fill.immediate()）
 * 实得量：fill.quantity − fill.lossMilli（与 householdGoods[transport_horse] 的毫商品口径一致）
 * 排除：future in-transit shipment（fill.immediate()==false）；卖方/目标户不作为来源
 * 过滤：仅 ownershipClassAtMarketTradeStart[buyer] == PROLETARIAN 的家户买方
 * </pre>
 *
 * <p>★ 本类只读报告与快照，不写状态、不选目标、不建 AssetKind.HORSE/OwnershipStake。C9 的读口必须复用本入口， 不得另从成交/卖方反推马匹取得量。
 */
public final class TransportHorseAcquisitionBook {

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private TransportHorseAcquisitionBook() {}

  /** 一笔本轮已交付的运输马匹取得事实（毫商品；marketRoundId = 本轮市场报告日）。 */
  public record Acquisition(
      HouseholdId buyer, CommodityId commodity, long quantityMilli, long marketRoundId) {

    public Acquisition {
      Objects.requireNonNull(buyer, "Acquisition.buyer");
      Objects.requireNonNull(commodity, "Acquisition.commodity");
      if (!CaravanTransportTemplate.TRANSPORT_HORSE.equals(commodity)) {
        throw new IllegalArgumentException(
            "Acquisition.commodity 必须是 transport_horse: " + commodity.value());
      }
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "Acquisition.quantityMilli 必须 > 0（0 取得不进表）: " + quantityMilli);
      }
      if (marketRoundId < 0L) {
        throw new IllegalArgumentException("Acquisition.marketRoundId 不得为负: " + marketRoundId);
      }
    }
  }

  /**
   * 从本轮市场报告派生逐买家实得量（稳定 HouseholdId 序；不可变）。
   *
   * @param report 本轮市场报告；必须是当前轮，调用方需保证其日号 = 结算日
   * @param ownershipClassAtMarketTradeStart S7-M2 后、S7-A 前快照
   */
  public static Map<HouseholdId, Acquisition> deliveredMarketFills(
      MarketReport report, Map<HouseholdId, OwnershipClass> ownershipClassAtMarketTradeStart) {
    Objects.requireNonNull(report, "deliveredMarketFills.report");
    Objects.requireNonNull(
        ownershipClassAtMarketTradeStart, "deliveredMarketFills.ownershipClassAtMarketTradeStart");
    TreeMap<HouseholdId, Long> quantities = new TreeMap<>(Comparator.comparing(HouseholdId::value));
    long horseFills = 0L;
    long inTransitSkipped = 0L;
    long nonHouseholdSkipped = 0L;
    long nonProletarianSkipped = 0L;
    long zeroDelivered = 0L;
    long deliveredTotal = 0L;

    for (MarketReport.Fill fill : report.fills()) {
      if (!CaravanTransportTemplate.TRANSPORT_HORSE.equals(fill.commodity())) {
        continue;
      }
      horseFills++;
      // ★ 只读到货/已交付事实；跨区在途 shipment 不进入本轮取得。
      if (!fill.immediate()) {
        inTransitSkipped++;
        continue;
      }
      if (fill.lossMilli() < 0L || fill.lossMilli() > fill.quantity()) {
        throw fail(
            "transport-horse-fill-loss-invalid",
            "day="
                + report.day()
                + " buyer="
                + fill.buyer()
                + " quantity="
                + fill.quantity()
                + " lossMilli="
                + fill.lossMilli());
      }
      long delivered = Math.subtractExact(fill.quantity(), fill.lossMilli());
      if (delivered <= 0L) {
        zeroDelivered++;
        continue;
      }
      if (fill.buyer().kind() != ActorKind.HOUSEHOLD) {
        nonHouseholdSkipped++;
        continue;
      }
      HouseholdId buyer = HouseholdActors.householdOf(fill.buyer());
      OwnershipClass buyerClass = ownershipClassAtMarketTradeStart.get(buyer);
      if (buyerClass == null) {
        throw fail(
            "transport-horse-ownership-snapshot-missing",
            "day=" + report.day() + " buyer=" + buyer.value());
      }
      if (buyerClass != OwnershipClass.PROLETARIAN) {
        nonProletarianSkipped++;
        continue;
      }
      long previous = quantities.getOrDefault(buyer, 0L);
      long total = Math.addExact(previous, delivered);
      quantities.put(buyer, total);
      deliveredTotal = Math.addExact(deliveredTotal, delivered);
      if (LOG.isTraceEnabled()) {
        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "TRANSPORT_HORSE_ACQUISITION",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    report.day(),
                    "buyer",
                    buyer.value(),
                    "quantityMilli",
                    delivered,
                    "previousMilli",
                    previous,
                    "buyerClassAtMarketTradeStart",
                    buyerClass.name(),
                    "source",
                    "delivered-immediate-market-fill"));
      }
    }

    LinkedHashMap<HouseholdId, Acquisition> result = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : quantities.entrySet()) {
      result.put(
          entry.getKey(),
          new Acquisition(
              entry.getKey(),
              CaravanTransportTemplate.TRANSPORT_HORSE,
              entry.getValue(),
              report.day()));
    }
    if (horseFills > 0L && LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "TRANSPORT_HORSE_ACQUISITION_READ",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  report.day(),
                  "horseFills",
                  horseFills,
                  "proletarianBuyers",
                  result.size(),
                  "deliveredMilli",
                  deliveredTotal,
                  "inTransitSkipped",
                  inTransitSkipped,
                  "nonHouseholdSkipped",
                  nonHouseholdSkipped,
                  "nonProletarianSkipped",
                  nonProletarianSkipped,
                  "zeroDelivered",
                  zeroDelivered,
                  "reason",
                  "only-immediate-delivered-fills-count-as-acquisition"));
    }
    return Collections.unmodifiableMap(result);
  }

  private static IllegalStateException fail(String reason, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "TRANSPORT_HORSE_ACQUISITION_FAILED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                reason,
                "detail",
                detail));
    return new IllegalStateException(reason + ": " + detail);
  }
}
