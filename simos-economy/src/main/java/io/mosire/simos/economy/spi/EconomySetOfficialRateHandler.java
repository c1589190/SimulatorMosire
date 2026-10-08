package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketZoneBook;
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
import java.util.Optional;

/**
 * ★★ {@code economy.SetOfficialRate}（A2a 2026-10-08；约束设计书 §3.3/§3.4；★ B2 2026-10-08 扩展）：<b>给一个 GOV 定某个币对的
 * 官方汇率</b>，★ 或（B2 起）<b>给一个市场区定它自己的官方汇率覆盖</b> —— 只写一条，一条命令 = 一条 revision（铁律 2）。
 *
 * <pre>{@code
 * {"govUnitId":"gov-central",   // 必填（B2 前）：报价的 GOV 单位（决策人侧由身份派生，GM 侧显式给）
 *  "base":"copper",             // 必填：政府买入/卖出的标的币
 *  "quote":"silver",            // 必填：计价币
 *  "buyPerMille":1000,          // 必填：政府买入 base 的报价（per-mille：每 1000 个 base 最小单位付多少 quote 最小单位）
 *  "sellPerMille":1050,         // 必填：政府卖出 base 的报价（同量纲）
 *  "reason"?:"…"}               // 可选：审计文本（只进日志，不进状态）
 *
 * // ── B2 扩展：区级覆盖（★ 载荷向后兼容：老载荷（无 marketZoneId）逐字走老路径）──
 * {"marketZoneId":"c-tp-copper",// 必填（给了它就写"本区官方汇率覆盖"）
 *  "govUnitId"?:"gov-tp-copper", // 可选：给了就必须等于该区的发行 GOV 单位（不然说不出"谁报的价"）
 *  "base":"copper","quote":"silver","buyPerMille":1000,"sellPerMille":1050}
 * }</pre>
 *
 * <p>★★ <b>它是"官方汇率"的唯一写入口</b>：官方汇率是<b>状态</b>（GOV 级随 {@code governments}、区级随 {@code marketZones} 进
 * ChangeSet/Codec；可持久、可回放、可分支），而<b>实际汇率永远是读数</b>（不落盘，I17）。两者<b>不相等是常态</b>：官方汇率是
 * 政府挂牌的报价，成交价由撮合给出（{@code FxPricing}），窗口没量时市场价由家户自己定（§4.5）。
 *
 * <p>★★ <b>B2 的区级覆盖（§4.3"官方汇率在此迁到市场区"）</b>：给了 {@code marketZoneId} ⇒ 写该区的 {@code officialRates}
 * 一条（币对键 {@code base|quote}），<b>不动</b>任何 GOV 级报价；读取口径是"区级优先、回落该区发行 GOV 的 GOV 级报价"
 * （{@link MarketZoneBook#officialRateFor}）。★ 本批的边界（如实记）：{@code FxRoundInput} 的政府外汇窗口仍按 GOV 级报价装配
 * （世界级匹配域），区级覆盖在本批落"状态 + 命令面 + 读数 + 解析函数"；把窗口按区收窄会改 A2 的撮合语义与 F2/F3/F4 的读数，
 * 留给阶段 3（口岸/管制）。
 *
 * <p>★★ <b>为什么两个报价都必须 &gt; 0，且 {@code base != quote}</b>：0 报价不是"免费"而是"说不出价"（停做一侧是窗口的
 * <b>容量</b>约束，不是报价）；同币对没有汇率可言。两条都在构造期与命令期各判一次（fail-closed）。
 *
 * <p>★★ <b>写这条命令的代价（如实记）</b>：GOV 级那条它<b>激活</b>这个币对的外汇市场 —— 有官方汇率，家户的 FX 挂单规则才有锚
 * （{@code FxSettlement} 的类注写了为什么锚必须是政策价）。也就是说<b>没定过汇率的世界一个数都不动</b>，定了才开始有外汇面。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand}（排除出令白名单 / {@code RegisterEffect} / 决策人命令目录三条路；
 * 命令总线本身仍可被窄工具提交）。决策人侧的受控入口是窄工具 {@code simos.gov.setFxRate}（身份派生 + 只能自己的 GOV + GM 审批链），
 * 与 {@code gov.defineCurrency} 的既有形制同源；★ <b>B2 的区级分支不给决策人桶开</b>（决策人窄工具不暴露 marketZoneId）——
 * 区级报价的作用域（"本行政区下辖的本国市场区部分"）要按身份派生，与口岸政策一起做（阶段 3），权限不得因为参数新增而放大。
 * ★ 不声明格资源（政府表/区表都没有单格路径）⇒ {@link CommandTargets#targetPaths} 恒空。
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
      if (definition.marketZoneId() != null) {
        return handleZoneOverride(base, definition, rate);
      }
      return handleGovernmentRate(base, definition, rate);
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", definition, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /** A2a 老路径（GOV 级报价）：逐字保持 A2 的行为与拒因（老载荷向后兼容的实质就在这一段）。 */
  private static HandlerOutcome handleGovernmentRate(
      EconomyData base, Definition definition, OfficialRate rate) {
    GovernmentId governmentId = GovernmentIds.ofUnit(definition.govUnitId());
    Government government = base.governments().get(governmentId);
    if (government == null) {
      return rejected(
          "government-not-registered",
          definition,
          "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
    }
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
            "scope",
            "government",
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
            "scope",
            "government",
            "pair",
            rate.key(),
            "midPerMille",
            rate.midPerMille(),
            "spreadPerMille",
            rate.sellPerMille() - rate.buyPerMille(),
            "officialRatesSize",
            governments.get(governmentId).officialRates().size()));
    return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
  }

  /**
   * ★★ <b>B2 新区路径（区级覆盖）</b>：只写目标区的 {@code officialRates} 一条；GOV 级报价一字不动。
   *
   * <p>★ 四条具名拒：{@code zone-not-found}（区不存在）、{@code gov-mismatch}（给了 govUnitId 却不等于本区发行 GOV 单位）、
   * {@code zone-issuer-not-a-gov-unit}（本区发行政府是世界级主体，没有 GOV 单位可指 —— 那时只能省略 govUnitId）、
   * {@code rate-unchanged}（逐字相同 ⇒ 拒）。
   */
  private static HandlerOutcome handleZoneOverride(
      EconomyData base, Definition definition, OfficialRate rate) {
    MarketZoneId zoneId = new MarketZoneId(definition.marketZoneId());
    MarketZone zone = base.marketZones().get(zoneId);
    if (zone == null) {
      return rejected(
          "zone-not-found",
          definition,
          "市场区不存在: " + zoneId.value() + "（先 economy.DefineMarketZone 建区）");
    }
    Optional<String> issuerUnit = MarketZoneBook.issuingGovUnitOf(zone);
    if (definition.govUnitId() != null) {
      if (issuerUnit.isEmpty()) {
        return rejected(
            "zone-issuer-not-a-gov-unit",
            definition,
            "本区发行政府是世界级主体（不是任何 GOV 单位），给 govUnitId 说不出\"谁报的价\"：zone="
                + zoneId.value()
                + " issuingGov="
                + zone.issuingGov().value());
      }
      if (!issuerUnit.get().equals(definition.govUnitId())) {
        return rejected(
            "gov-mismatch",
            definition,
            "给的 govUnitId 不是本区发行 GOV 单位：给了="
                + definition.govUnitId()
                + "，本区发行 GOV="
                + issuerUnit.get()
                + "（zone="
                + zoneId.value()
                + "）");
      }
    }
    OfficialRate existing = zone.officialRates().get(rate.key());
    if (existing != null && existing.equals(rate)) {
      return rejected(
          "rate-unchanged",
          definition,
          "该区该币对官方汇率已是 "
              + existing.buyPerMille()
              + "/"
              + existing.sellPerMille()
              + "（逐字相同 ⇒ 拒，不做静默幂等）");
    }
    Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(base.marketZones());
    MarketZone updated = zone.withOfficialRate(rate);
    zones.put(zoneId, updated);
    EconomyData projected = base.withMarketZones(zones);
    LOG.info(
        LogEvent.of(
            "ZONE_OFFICIAL_RATE_SET",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "scope",
            "market-zone",
            "zone",
            zoneId.value(),
            "issuingGov",
            zone.issuingGov().value(),
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
            "zoneRatesSize",
            updated.officialRates().size(),
            "reasonLength",
            definition.reason() == null ? 0 : definition.reason().length()));
    LOG.debug(
        LogEvent.of(
            "ZONE_OFFICIAL_RATE_SET_DETAIL",
            EconomyLogSource.ECONOMY_MARKET_ZONE,
            "scope",
            "market-zone",
            "zone",
            zoneId.value(),
            "pair",
            rate.key(),
            "midPerMille",
            rate.midPerMille(),
            "spreadPerMille",
            rate.sellPerMille() - rate.buyPerMille(),
            "hexCount",
            zone.hexCount(),
            "legalTender",
            zone.legalTender().value(),
            "note",
            "区级覆盖不改 GOV 级报价；读取口径 = 区级优先、回落该区发行 GOV 的 GOV 级报价"));
    return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
  }

  /** 具名拒（业务拒绝 = INFO 一条"发生了什么 + 具名原因"，字段级细节 = DEBUG 一条"为什么"；AGENTS §一.9）。 */
  private static HandlerOutcome rejected(
      String reason, Definition definition, String message) {
    LOG.info(
        LogEvent.of(
            "OFFICIAL_RATE_REJECTED",
            EconomyLogSource.ECONOMY_FX,
            "reason",
            reason,
            "scope",
            definition == null || definition.marketZoneId() == null ? "government" : "market-zone",
            "govUnit",
            definition == null ? "" : definition.govUnitId(),
            "zone",
            definition == null || definition.marketZoneId() == null ? "" : definition.marketZoneId(),
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
    String marketZoneId =
        payload.hasNonNull("marketZoneId")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "marketZoneId")
            : null;
    // ★ 向后兼容：老载荷（无 marketZoneId）时 govUnitId 仍是必填；区级分支里它可选（缺省 = 由该区发行 GOV 反查）。
    String govUnitId = payload.hasNonNull("govUnitId")
        ? EconomyCommandPayloads.requireText(TYPE, payload, "govUnitId")
        : null;
    if (marketZoneId == null && govUnitId == null) {
      throw new IllegalArgumentException(TYPE + " 缺少字段: govUnitId（不给 marketZoneId 时必填）");
    }
    String base = EconomyCommandPayloads.requireText(TYPE, payload, "base");
    String quote = EconomyCommandPayloads.requireText(TYPE, payload, "quote");
    long buyPerMille = EconomyCommandPayloads.requireLong(TYPE, payload, "buyPerMille");
    long sellPerMille = EconomyCommandPayloads.requireLong(TYPE, payload, "sellPerMille");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(
        govUnitId, marketZoneId, base, quote, buyPerMille, sellPerMille, reason);
  }

  /**
   * 一条报价：{@code govUnitId}（GOV 级必填；区级可省略）+ 可选 {@code marketZoneId} + 币对 + 两个报价 + 可选审计文本
   * （构造期判完边界 —— 与 {@link OfficialRate} 的守卫同一口径）。
   */
  private record Definition(
      String govUnitId,
      String marketZoneId,
      String base,
      String quote,
      long buyPerMille,
      long sellPerMille,
      String reason) {

    Definition {
      Objects.requireNonNull(base, "base");
      Objects.requireNonNull(quote, "quote");
      if (govUnitId != null && govUnitId.isBlank()) {
        throw new IllegalArgumentException("govUnitId 给了就必须非空白");
      }
      if (marketZoneId != null && marketZoneId.isBlank()) {
        throw new IllegalArgumentException("marketZoneId 给了就必须非空白");
      }
      if (govUnitId == null && marketZoneId == null) {
        throw new IllegalArgumentException("govUnitId 与 marketZoneId 至少要给一个");
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
