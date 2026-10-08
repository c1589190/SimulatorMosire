package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.SetOfficialRate}（A2a 2026-10-08；约束设计书 §3.3/§3.4）：<b>给一个 GOV 定某个币对的官方汇率</b> ——
 * 只写 {@code Government.officialRates} 一条，一条命令 = 一条 revision（铁律 2）。
 *
 * <pre>{@code
 * {"govUnitId":"gov-central",   // 必填：报价的 GOV 单位（决策人侧由身份派生，GM 侧显式给）
 *  "base":"copper",             // 必填：政府买入/卖出的标的币
 *  "quote":"silver",            // 必填：计价币
 *  "buyPerMille":1000,          // 必填：政府买入 base 的报价（per-mille：每 1000 个 base 最小单位付多少 quote 最小单位）
 *  "sellPerMille":1050,         // 必填：政府卖出 base 的报价（同量纲）
 *  "reason"?:"…"}               // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>它是"官方汇率"的唯一写入口</b>：官方汇率是<b>状态</b>（随 {@code governments} 进 ChangeSet/Codec；
 * 可持久、可回放、可分支），而<b>实际汇率永远是读数</b>（不落盘，I17）。两者<b>不相等是常态</b>：官方汇率是政府挂牌的 报价，成交价由撮合给出（{@code
 * FxPricing}），窗口没量时市场价由家户自己定（§4.5）。
 *
 * <p>★★ <b>为什么两个报价都必须 &gt; 0，且 {@code base != quote}</b>：0 报价不是"免费"而是"说不出价"（停做一侧是窗口的
 * <b>容量</b>约束，不是报价）；同币对没有汇率可言。两条都在构造期与命令期各判一次（fail-closed）。
 *
 * <p>★★ <b>写这条命令的代价（如实记）</b>：它<b>激活</b>这个币对的外汇市场 —— 有官方汇率，家户的 FX 挂单规则才有锚 （{@code FxSettlement}
 * 的类注写了为什么锚必须是政策价）。也就是说<b>没定过汇率的世界一个数都不动</b>，定了才开始有外汇面。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand}（排除出令白名单 / {@code RegisterEffect} / 决策人命令目录三条路；
 * 命令总线本身仍可被窄工具提交）。决策人侧的受控入口是窄工具 {@code simos.gov.setFxRate}（身份派生 + 只能自己的 GOV + GM 审批链），与 {@code
 * gov.defineCurrency} 的既有形制同源。★ 不声明格资源（政府表没有格路径）⇒ {@link CommandTargets#targetPaths} 恒空。
 */
public final class EconomySetOfficialRateHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetOfficialRate";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验（缺字段 ⇒ 抛具名载荷错）；本命令没有格资源目标。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Definition definition;
    try {
      definition = parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return rejected("bad-payload", null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected("economy-not-activated", definition, "economy 切片尚未激活（先 economy.Seed 播种）");
      }
      GovernmentId governmentId = GovernmentIds.ofUnit(definition.govUnitId());
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return rejected(
            "government-not-registered",
            definition,
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      CurrencyId currencyBase = new CurrencyId(definition.base());
      CurrencyId currencyQuote = new CurrencyId(definition.quote());
      if (currencyBase.equals(currencyQuote)) {
        return rejected(
            "same-currency", definition, "base 与 quote 不得是同一种钱（同币对没有汇率）: " + definition.base());
      }
      // ★ 说不出"这是什么钱"就不许给它定价（与 A1 的 issuable ⊆ currencies 同族的跨表守卫）。
      if (!base.currencies().containsKey(currencyBase)) {
        return rejected(
            "currency-not-defined", definition, "base 币种未在世界词表里定义: " + definition.base());
      }
      if (!base.currencies().containsKey(currencyQuote)) {
        return rejected(
            "currency-not-defined", definition, "quote 币种未在世界词表里定义: " + definition.quote());
      }
      OfficialRate rate =
          new OfficialRate(
              currencyBase, currencyQuote, definition.buyPerMille(), definition.sellPerMille());
      OfficialRate existing = government.officialRates().get(rate.key());
      if (existing != null && existing.equals(rate)) {
        return rejected(
            "rate-unchanged",
            definition,
            "该币对官方汇率已是 "
                + existing.buyPerMille()
                + "/"
                + existing.sellPerMille()
                + "（逐字相同 ⇒ 拒，不做静默幂等）");
      }
      Map<GovernmentId, Government> governments = new LinkedHashMap<>(base.governments());
      governments.put(governmentId, government.withOfficialRate(rate));
      EconomyData projected = base.withGovernments(governments);
      LOG.info(
          LogEvent.of(
              "OFFICIAL_RATE_SET",
              EconomyLogSource.ECONOMY_FX,
              "government",
              governmentId.value(),
              "govUnit",
              definition.govUnitId(),
              "base",
              rate.base().value(),
              "quote",
              rate.quote().value(),
              "buyPerMille",
              rate.buyPerMille(),
              "sellPerMille",
              rate.sellPerMille(),
              "previous",
              existing == null ? "none" : existing.buyPerMille() + "/" + existing.sellPerMille(),
              "reasonLength",
              definition.reason() == null ? 0 : definition.reason().length()));
      LOG.debug(
          LogEvent.of(
              "OFFICIAL_RATE_SET_DETAIL",
              EconomyLogSource.ECONOMY_FX,
              "pair",
              rate.key(),
              "midPerMille",
              rate.midPerMille(),
              "spreadPerMille",
              rate.sellPerMille() - rate.buyPerMille(),
              "officialRatesSize",
              governments.get(governmentId).officialRates().size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", definition, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /** 具名拒（业务拒绝 = INFO 一条"发生了什么 + 具名原因"，字段级细节 = DEBUG 一条"为什么"；AGENTS §一.9）。 */
  private static HandlerOutcome.Rejected rejected(
      String reason, Definition definition, String message) {
    LOG.info(
        LogEvent.of(
            "OFFICIAL_RATE_REJECTED",
            EconomyLogSource.ECONOMY_FX,
            "reason",
            reason,
            "govUnit",
            definition == null ? "" : definition.govUnitId(),
            "base",
            definition == null ? "" : definition.base(),
            "quote",
            definition == null ? "" : definition.quote()));
    LOG.debug(
        LogEvent.of(
            "OFFICIAL_RATE_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_FX,
            "reason",
            reason,
            "buyPerMille",
            definition == null ? -1L : definition.buyPerMille(),
            "sellPerMille",
            definition == null ? -1L : definition.sellPerMille(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String govUnitId = EconomyCommandPayloads.requireText(TYPE, payload, "govUnitId");
    String base = EconomyCommandPayloads.requireText(TYPE, payload, "base");
    String quote = EconomyCommandPayloads.requireText(TYPE, payload, "quote");
    long buyPerMille = EconomyCommandPayloads.requireLong(TYPE, payload, "buyPerMille");
    long sellPerMille = EconomyCommandPayloads.requireLong(TYPE, payload, "sellPerMille");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(govUnitId, base, quote, buyPerMille, sellPerMille, reason);
  }

  /** 一条报价：三个业务字段 + 两个报价 + 可选审计文本（构造期判完边界 —— 与 {@link OfficialRate} 的守卫同一口径）。 */
  private record Definition(
      String govUnitId,
      String base,
      String quote,
      long buyPerMille,
      long sellPerMille,
      String reason) {

    Definition {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(quote, "quote");
      if (govUnitId.isBlank()) {
        throw new IllegalArgumentException("govUnitId 不得为空白");
      }
      if (base.isBlank()) {
        throw new IllegalArgumentException("base 不得为空白");
      }
      if (quote.isBlank()) {
        throw new IllegalArgumentException("quote 不得为空白");
      }
      if (buyPerMille <= 0L) {
        throw new IllegalArgumentException(
            "buyPerMille 必须 > 0（per-mille；0 报价 = 说不出价，停做一侧请走窗口容量）: " + buyPerMille);
      }
      if (sellPerMille <= 0L) {
        throw new IllegalArgumentException(
            "sellPerMille 必须 > 0（per-mille；0 报价 = 说不出价，停做一侧请走窗口容量）: " + sellPerMille);
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
