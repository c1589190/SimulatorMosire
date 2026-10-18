package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomyReferenceValidation;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★★ <b>{@code economy.SetProductionWageAnchor}</b>（D-032 P4 §6.1/§6.4）：GM-only 的 U20 工资锚显式写入口。
 *
 * <pre>{@code
 * {"marketZone":"zone-0_0","industry":"farm","role":"hired-hand",
 *  "currency":"silver","wagePerLaborMilli":1000}
 * }</pre>
 *
 * <p>★★ <b>键 = (marketZone, industry, role)</b>，单条 upsert（不整表替换；清除由 seed/后续命令面，不在本批）。
 * 币种省略时取该市场区 {@code legalTender}，给了就必须一致。{@code wagePerLaborMilli = 0} 合法；缺锚与 0 是两件事。
 *
 * <p>★ <b>历史字段</b>：可选 {@code shortageStreak}/{@code satisfiedStreak}/{@code lastAdjustedTick}/
 * {@code lastObservedMatchedLaborMilli}/{@code lastObservedTargetLaborMilli}/{@code lastAdjustmentDelta}。
 * 新建时缺省 = 0（{@code lastAdjustedTick} = -1 = 从未）。已有锚且未显式给历史字段时：同价保留历史；改价则清两侧
 * streak、写 {@code lastAdjustedTick = 命令日}、{@code lastAdjustmentDelta = 新-旧}，观测值保留。
 *
 * <p>业务拒绝（INFO、零变更）：{@code wage-anchor-market-zone-missing} / {@code wage-anchor-industry-unknown} /
 * {@code wage-anchor-role-unknown} / {@code wage-role-not-money} / {@code wage-anchor-currency-mismatch} /
 * {@code wage-anchor-quote-negative}。
 */
public final class EconomySetProductionWageAnchorHandler implements CommandHandler {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetProductionWageAnchor";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      MarketZoneId marketZone =
          MarketZoneId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "marketZone"));
      MarketZone zone = base.marketZones().get(marketZone);
      if (zone == null) {
        return reject("wage-anchor-market-zone-missing:" + marketZone.value());
      }
      IndustryId industry =
          IndustryId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "industry"));
      ProductionDefinitionLookup definitions = EconomyProductionDefinitions.of(state);
      ProductionStateLookup production = EconomyProductionDefinitions.stateOf(state);
      IndustryDefinitionView industryDefinition =
          definitions
              .industry(industry)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "wage-anchor-industry-unknown:" + industry.value()));
      ProductionRoleId role =
          ProductionRoleId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "role"));
      ProductionRoleDefinition roleDefinition = roleDefinition(industryDefinition, role);
      if (roleDefinition == null) {
        return reject("wage-anchor-role-unknown:" + role.value());
      }
      if (!(roleDefinition.payment() instanceof RolePayment.MoneyWage)) {
        return reject("wage-role-not-money:" + role.value());
      }
      String currencyText = EconomyCommandPayloads.optionalText(TYPE, payload, "currency", null);
      CurrencyId currency =
          currencyText == null ? zone.legalTender() : CurrencyId.parse(currencyText);
      if (!currency.equals(zone.legalTender())) {
        return reject("wage-anchor-currency-mismatch:" + marketZone.value());
      }
      if (!payload.has("wagePerLaborMilli") || payload.get("wagePerLaborMilli").isNull()) {
        return reject("wage-anchor-quote-missing:" + marketZone.value());
      }
      long wage = EconomyCommandPayloads.requireLong(TYPE, payload, "wagePerLaborMilli");
      if (wage < 0L) {
        return reject("wage-anchor-quote-negative:" + marketZone.value());
      }
      WageAnchorKey key = new WageAnchorKey(marketZone, industry, role);
      Map<WageAnchorKey, ProductionWageAnchor> next =
          new LinkedHashMap<>(base.productionWageAnchors());
      ProductionWageAnchor replacement =
          replace(base.productionWageAnchors().get(key), payload, key, currency, wage, state);
      next.put(key, replacement);
      EconomyData projected = base.withProductionWageAnchors(next);
      // 跨切片完整校验：industry/role/MONEY_WAGE/zone/currency。
      EconomyReferenceValidation.validateProductionWages(definitions, production, projected, TYPE);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_WAGE_ANCHOR_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "marketZone",
                  marketZone.value(),
                  "industry",
                  industry.value(),
                  "role",
                  role.value(),
                  "currency",
                  currency.value(),
                  "wagePerLaborMilli",
                  wage));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject(e.getMessage());
    }
  }

  /** 构造替换锚：已有锚且未给历史字段时保留历史（改价才清 streak / 记改价日与差）。 */
  private static ProductionWageAnchor replace(
      ProductionWageAnchor existing,
      JsonNode payload,
      WageAnchorKey key,
      CurrencyId currency,
      long wage,
      SimulationState state) {
    boolean historyProvided =
        payload.has("shortageStreak")
            || payload.has("satisfiedStreak")
            || payload.has("lastAdjustedTick")
            || payload.has("lastObservedMatchedLaborMilli")
            || payload.has("lastObservedTargetLaborMilli")
            || payload.has("lastAdjustmentDelta");
    long commandDay = state.meta().timestamp().tick();
    if (existing == null) {
      return new ProductionWageAnchor(
          currency,
          wage,
          EconomyCommandPayloads.optionalLong(TYPE, payload, "shortageStreak", 0L),
          EconomyCommandPayloads.optionalLong(TYPE, payload, "satisfiedStreak", 0L),
          EconomyCommandPayloads.optionalLong(TYPE, payload, "lastAdjustedTick", -1L),
          EconomyCommandPayloads.optionalLong(
              TYPE, payload, "lastObservedMatchedLaborMilli", 0L),
          EconomyCommandPayloads.optionalLong(TYPE, payload, "lastObservedTargetLaborMilli", 0L),
          EconomyCommandPayloads.optionalLong(TYPE, payload, "lastAdjustmentDelta", 0L));
    }
    if (!historyProvided) {
      if (existing.currency().equals(currency) && existing.wagePerLaborMilli() == wage) {
        return existing;
      }
      return new ProductionWageAnchor(
          currency,
          wage,
          0L,
          0L,
          commandDay,
          existing.lastObservedMatchedLaborMilli(),
          existing.lastObservedTargetLaborMilli(),
          wage - existing.wagePerLaborMilli());
    }
    return new ProductionWageAnchor(
        currency,
        wage,
        EconomyCommandPayloads.optionalLong(
            TYPE, payload, "shortageStreak", existing.shortageStreak()),
        EconomyCommandPayloads.optionalLong(
            TYPE, payload, "satisfiedStreak", existing.satisfiedStreak()),
        EconomyCommandPayloads.optionalLong(
            TYPE, payload, "lastAdjustedTick", existing.lastAdjustedTick()),
        EconomyCommandPayloads.optionalLong(
            TYPE,
            payload,
            "lastObservedMatchedLaborMilli",
            existing.lastObservedMatchedLaborMilli()),
        EconomyCommandPayloads.optionalLong(
            TYPE,
            payload,
            "lastObservedTargetLaborMilli",
            existing.lastObservedTargetLaborMilli()),
        EconomyCommandPayloads.optionalLong(
            TYPE, payload, "lastAdjustmentDelta", existing.lastAdjustmentDelta()));
  }

  private static ProductionRoleDefinition roleDefinition(
      IndustryDefinitionView definition, ProductionRoleId role) {
    for (ProductionRoleDefinition candidate : definition.roles()) {
      if (candidate.id().equals(role)) {
        return candidate;
      }
    }
    return null;
  }

  private static HandlerOutcome reject(String reason) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_SET_PRODUCTION_WAGE_ANCHOR_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(reason)));
    return new HandlerOutcome.Rejected(reason);
  }
}
