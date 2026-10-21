package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 C6：临时运输能力批次的唯一写口。</b>
 *
 * <p>嵌在目标运行序列的 S5（生产收获）与 S7-END（市场轮收尾）：
 *
 * <pre>
 * S5 harvest 识别 CaravanTransportTemplate.SERVICE_OUTPUT
 *   → capacityWorkMilli = scale × SERVICE_MILLI_PER_SCALE_UNIT
 *   → sink.accept(operatorOwnerHousehold, operator 当刻 homeHex, marketRoundIdOf(day), capacity)
 *   → 本类按 (household, homeHex, marketRoundId) 累加；marketZone = 当刻 homeHex 所属市场区
 * S7-END
 *   → clearCurrentMarketRound(sheet, marketRoundIdOf(day)) 只删当前 marketRoundId 的批次
 * </pre>
 *
 * <p>★ <b>唯一拼写点</b>：市场轮间隔只读 {@link MarketConstants#MARKET_RESTOCK_INTERVAL_DAYS}；服务键只读 {@code
 * CaravanTransportTemplate.SERVICE_OUTPUT}（调用方）；本类不复制 5 或服务键字面量。
 *
 * <p>★ <b>失败语义</b>：operator 户不存在、operator 户当刻 homeHex 与传入 homeHex 不一致、homeHex 不属于任何市场区、 或同一 key
 * 的已有批次市场区漂开 ⇒ 具名 {@link IllegalStateException} fail-closed，不静默丢容量。
 */
public final class TransportCapacityBatchBook {

  private static final LogChannel LOG = EventLog.channel(EconomyLog.settlement());

  private TransportCapacityBatchBook() {}

  /**
   * 本批次将结算的市场日（绝对 tick）：当前 day 是市场日 ⇒ day；否则下一个 {@code day % interval == 0} 的 day。
   *
   * @throws IllegalArgumentException day 为负
   */
  public static long marketRoundIdOf(long day) {
    if (day < 0L) {
      throw new IllegalArgumentException("marketRoundIdOf 的 day 不得为负: " + day);
    }
    long interval = MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;
    long remainder = day % interval;
    return remainder == 0L ? day : Math.addExact(day, interval - remainder);
  }

  /**
   * 构造注入到 {@code SettlementDayProductionPipeline.driveProduction} 的 sink。
   *
   * <p>sink 在收获阶段被调用；每次调用都重新读 operator 户当前工作副本的 homeHex 与当前市场区， 不使用 industry id 字符串或买方 hex。
   */
  public static TransportCapacitySink sink(
      EconomyStateBuilder sheet, EconomyDayView view, long day) {
    Objects.requireNonNull(sheet, "TransportCapacityBatchBook.sink 的 sheet 不得为 null");
    Objects.requireNonNull(view, "TransportCapacityBatchBook.sink 的 view 不得为 null");
    long marketRoundId = marketRoundIdOf(day);
    return (operatorHousehold, homeHex, suppliedMarketRoundId, capacityWorkMilli) ->
        accumulate(
            sheet,
            view,
            operatorHousehold,
            homeHex,
            suppliedMarketRoundId,
            capacityWorkMilli,
            marketRoundId);
  }

  /**
   * 累加一笔运输能力到一个批次。
   *
   * @param sheet 当刻经济工作表（批次写入其工作副本）
   * @param view 当刻只读视图（只用于解析 homeHex 所属市场区）
   * @param operatorHousehold operator OWNER 家户
   * @param homeHex 调用方解析的 operator 当刻 homeHex；必须与工作表当前值一致
   * @param marketRoundId 本批次市场日（绝对 tick）
   * @param capacityWorkMilli 本笔运输能力（≥ 0）
   * @param expectedMarketRoundId sink 构造时冻结的本日市场轮 id（用于防调用方跨轮写错）
   * @return 1 = 新建/更新了一条批次；0 = capacity 为 0（无状态变化）
   */
  static int accumulate(
      EconomyStateBuilder sheet,
      EconomyDayView view,
      HouseholdId operatorHousehold,
      HexCoord homeHex,
      long marketRoundId,
      long capacityWorkMilli,
      long expectedMarketRoundId) {
    Objects.requireNonNull(sheet, "TransportCapacityBatchBook.accumulate 的 sheet 不得为 null");
    Objects.requireNonNull(view, "TransportCapacityBatchBook.accumulate 的 view 不得为 null");
    Objects.requireNonNull(
        operatorHousehold, "TransportCapacityBatchBook.accumulate 的 operatorHousehold 不得为 null");
    Objects.requireNonNull(homeHex, "TransportCapacityBatchBook.accumulate 的 homeHex 不得为 null");
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException(
          "transport-capacity-market-round-negative: marketRoundId=" + marketRoundId);
    }
    if (marketRoundId != expectedMarketRoundId) {
      throw new IllegalStateException(
          "transport-capacity-market-round-mismatch: supplied="
              + marketRoundId
              + " expected="
              + expectedMarketRoundId);
    }
    if (capacityWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "transport-capacity-negative: household="
              + operatorHousehold.value()
              + " capacityWorkMilli="
              + capacityWorkMilli);
    }
    if (capacityWorkMilli == 0L) {
      return 0;
    }
    Map<HouseholdId, HouseholdEconomy> rows = sheet.householdEconomiesOrBase();
    HouseholdEconomy operatorRow = rows.get(operatorHousehold);
    if (operatorRow == null) {
      throw new IllegalStateException(
          "transport-capacity-operator-household-missing: household=" + operatorHousehold.value());
    }
    HexCoord currentHomeHex = operatorRow.view().hex();
    if (!currentHomeHex.equals(homeHex)) {
      throw new IllegalStateException(
          "transport-capacity-operator-home-hex-mismatch: household="
              + operatorHousehold.value()
              + " supplied="
              + homeHex
              + " current="
              + currentHomeHex);
    }
    // ★ MarketTables.marketIdAt = “这一格按哪张表报价”的唯一拼写点：持久形态要求区表存在、派生形态要求成员格
    //   在价表 hexes 中；区/表任一缺失都返回空 ⇒ 下面 fail-closed。禁止用一个 zone id 冒充“有市场”。
    Optional<MarketZoneId> marketZone = MarketTables.marketIdAt(view, currentHomeHex);
    if (marketZone.isEmpty()) {
      throw new IllegalStateException(
          "transport-capacity-market-zone-missing: household="
              + operatorHousehold.value()
              + " homeHex="
              + currentHomeHex);
    }
    TransportCapacityBatchKey key =
        new TransportCapacityBatchKey(operatorHousehold, currentHomeHex, marketRoundId);
    LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        sheet.transportCapacityBatches();
    TransportCapacityBatch existing = batches.get(key);
    long nextCapacity = capacityWorkMilli;
    if (existing != null) {
      if (!existing.marketZone().equals(marketZone.get())) {
        throw new IllegalStateException(
            "transport-capacity-market-zone-mismatch: key="
                + key
                + " existing="
                + existing.marketZone()
                + " current="
                + marketZone.get());
      }
      nextCapacity = Math.addExact(existing.capacityWorkMilli(), capacityWorkMilli);
    }
    batches.put(key, new TransportCapacityBatch(marketZone.get(), nextCapacity));
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          LogEvent.of(
              "TRANSPORT_CAPACITY_BATCH_ACCUMULATED",
              EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
              "household",
              operatorHousehold.value(),
              "homeHex",
              currentHomeHex.toString(),
              "marketRoundId",
              marketRoundId,
              "marketZone",
              marketZone.get().value(),
              "capacityWorkMilli",
              nextCapacity));
    }
    return 1;
  }

  /**
   * S7-END：只清除当前 marketRoundId 的全部批次；别的市场轮一条都不动。
   *
   * @return 被清除的批次数
   */
  public static int clearCurrentMarketRound(EconomyStateBuilder sheet, long marketRoundId) {
    Objects.requireNonNull(
        sheet, "TransportCapacityBatchBook.clearCurrentMarketRound 的 sheet 不得为 null");
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException(
          "transport-capacity-market-round-negative: marketRoundId=" + marketRoundId);
    }
    LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        sheet.transportCapacityBatches();
    int cleared = 0;
    var iterator = batches.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<TransportCapacityBatchKey, TransportCapacityBatch> entry = iterator.next();
      if (entry.getKey().marketRoundId() == marketRoundId) {
        iterator.remove();
        cleared++;
      }
    }
    if (cleared > 0 && LOG.isInfoEnabled()) {
      LOG.info(
          LogEvent.of(
              "TRANSPORT_CAPACITY_BATCHES_CLEARED",
              EconomyLogSource.ECONOMY_MARKET,
              "marketRoundId",
              marketRoundId,
              "cleared",
              cleared));
    }
    return cleared;
  }
}
