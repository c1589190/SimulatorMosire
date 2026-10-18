package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.SettlementLogSupport.MANDATE;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class MarketLifecycleSettlement {

  /**
   * ★★ <b>R1：政府市场授权的生命周期</b>（成交累加 → 耗尽/到期清除）—— 每个世界日**无条件**跑一次。
   *
   * <p>★★ <b>它做什么</b>：
   *
   * <ol>
   *   <li><b>成交累加</b>：把本轮（可能多轮）撮合里该授权的成交毫商品累加进 {@code filledMilli}；
   *   <li><b>耗尽清除</b>：{@code filledMilli ≥ quantityMilli} ⇒ <b>删除该行</b>（"量用完就撤单"）；
   *   <li><b>到期清除</b>：{@code day > expiresOnDay} ⇒ <b>删除该行</b>（不许留永久挂单）。
   * </ol>
   *
   * <p>★ <b>为什么是无条件调用</b>：到期是日历事实 —— 闭市日、没有市场的格、没有对手方，都不能让一条授权永远挂在状态里。
   *
   * <p>★ 每一次清除都记 INFO（谁/商品/方向/量/成交量/为何清除，§一.9 的"新状态写口至少一条具名 INFO"）； 一条都没有 ⇒ 整段
   * no-op（不产生任何状态差异，也不刷日志）。
   *
   * @param fills 本日成交累计（{@code mandateId → 毫商品}；市场未触发 / 无授权 ⇒ 空表）
   */
  static void applyGovMarketMandateLifecycle(
      EconomySession session, long day, Map<MarketMandateId, Long> fills) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(fills, "fills（没有成交给空表）");
    Map<MarketMandateId, GovernmentMarketMandate> base = session.sheet().govMarketMandatesOrBase();
    boolean anyFill = fills.values().stream().anyMatch(value -> value != null && value > 0L);
    boolean anyExpiry = false;
    for (GovernmentMarketMandate mandate : base.values()) {
      if (mandate.expiredOn(day) || mandate.exhausted()) {
        anyExpiry = true;
        break;
      }
    }
    if (!anyFill && !anyExpiry) {
      return; // 没有成交、也没有要清的 ⇒ 不物化工作副本（无授权世界零拷贝）
    }
    LinkedHashMap<MarketMandateId, GovernmentMarketMandate> mandates =
        session.sheet().govMarketMandates();
    long filledRows = 0L;
    long exhaustedRows = 0L;
    long expiredRows = 0L;
    for (Map.Entry<MarketMandateId, Long> entry : fills.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      GovernmentMarketMandate mandate = mandates.get(entry.getKey());
      if (mandate == null) {
        // 市场报告里出现了状态里没有的授权 ⇒ 契约故障（不静默丢成交量，也不凭空建一行）。
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "mandate",
                    entry.getKey().value(),
                    "reason",
                    "filled-authorization-not-in-state",
                    "filledMilli",
                    entry.getValue()));
        throw new IllegalStateException("政府授权成交找不到状态行（契约故障）: " + entry.getKey().value());
      }
      GovernmentMarketMandate updated = mandate.withFill(entry.getValue());
      mandates.put(entry.getKey(), updated);
      filledRows++;
      if (TRACE.isTraceEnabled()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_FILLED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    day,
                    "mandate",
                    updated.id().value(),
                    "government",
                    updated.government().value(),
                    "side",
                    updated.side().name(),
                    "commodity",
                    updated.commodity().value(),
                    "filledDelta",
                    entry.getValue(),
                    "filledMilli",
                    updated.filledMilli(),
                    "quantityMilli",
                    updated.quantityMilli(),
                    "remainingMilli",
                    updated.remainingMilli()));
      }
    }
    // 清除：耗尽优先判（"量用完了"比"到期了"更能解释这条行为什么消失）。
    for (GovernmentMarketMandate mandate : new ArrayList<>(mandates.values())) {
      String reason = null;
      if (mandate.exhausted()) {
        reason = "exhausted";
        exhaustedRows++;
      } else if (mandate.expiredOn(day)) {
        reason = "expired";
        expiredRows++;
      }
      if (reason == null) {
        continue;
      }
      mandates.remove(mandate.id());
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_CLEARED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "mandate",
                  mandate.id().value(),
                  "government",
                  mandate.government().value(),
                  "side",
                  mandate.side().name(),
                  "commodity",
                  mandate.commodity().value(),
                  "quantityMilli",
                  mandate.quantityMilli(),
                  "filledMilli",
                  mandate.filledMilli(),
                  "expiresOnDay",
                  mandate.expiresOnDay(),
                  "reason",
                  reason));
    }
    if (MANDATE.isDebugEnabled()) {
      EventLog.channel(MANDATE)
          .debug(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_LIFECYCLE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "filledRows",
                  filledRows,
                  "exhaustedCleared",
                  exhaustedRows,
                  "expiredCleared",
                  expiredRows,
                  "remaining",
                  mandates.size()));
    }
  }
}
