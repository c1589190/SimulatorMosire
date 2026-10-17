package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomyReferenceValidation;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.economy.model.WageQuoteSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
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
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>{@code economy.SetProductionWagePosting}</b>（D-032 D3 §2.7）：GM-only 的执行期工资岗位整表替换命令。
 *
 * <pre>{@code
 * {"unit":"unit-farm@0_0-hh-1",
 *  "postings":[{"role":"hired-hand","targetLaborMilliPerDay":5000,
 *               "currency":"silver","wagePerLaborMilli":1000,"quoteSource":"explicit"}]}
 * }</pre>
 *
 * <p>★ <b>整表替换</b>该 unit 的 posting 集合；{@code postings: []} 清空，不做 patch。unit 必须已存在，
 * 本命令<b>不得隐式创建实例</b>。每个 posting 必须显式给报价（0 合法）与目标（0 表示显式关闭岗位）； currency 省略时从 unit 所在市场区 legalTender
 * 派生，给了就必须一致。
 *
 * <p>★ <b>锁定期</b>：载荷不得写 streak/lastAdjustedTick/lastObserved* 等历史字段（{@code wage-posting-locked}）。
 * 显式改报价会清空两侧 streak 并写 {@code lastAdjustedTick=命令日}、{@code lastAdjustmentDelta=新-旧}、 {@code
 * quoteSource=EXPLICIT}；只改目标则保留报价与 streak。
 *
 * <p>业务拒绝（INFO、零变更）：{@code wage-posting-unit-unknown} / {@code wage-posting-role-unknown} / {@code
 * wage-role-not-money} / {@code wage-target-missing} / {@code wage-target-negative} / {@code
 * wage-quote-missing} / {@code wage-currency-mismatch} / {@code wage-market-zone-missing} / {@code
 * wage-posting-locked}。
 */
public final class EconomySetProductionWagePostingHandler implements CommandHandler {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetProductionWagePosting";

  private static final Logger LOG = EconomyLog.command();

  private static final Set<String> LOCKED_FIELDS =
      Set.of(
          "shortageStreak",
          "satisfiedStreak",
          "lastAdjustedTick",
          "lastAdjustmentDelta",
          "lastObservedMatchedLabor",
          "lastObservedEffectiveTarget",
          "lastMaxAbsorbable");

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
      ProductionUnitId unit =
          ProductionUnitId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "unit"));
      ProductionDefinitionLookup definitions = EconomyProductionDefinitions.of(state);
      ProductionStateLookup production = EconomyProductionDefinitions.stateOf(state);
      ProductionProcess instance = production.instances().get(unit);
      if (instance == null) {
        return reject("wage-posting-unit-unknown:" + unit.value());
      }
      IndustryDefinitionView planDefinition =
          definitions
              .industry(instance.plan())
              .orElseThrow(
                  () ->
                      crossSliceFault(
                          "生产实例指名的方案定义不存在: unit="
                              + unit.value()
                              + " plan="
                              + instance.plan().value()));
      MarketZone marketZone = marketZoneOf(instance, base.marketZones());
      if (marketZone == null) {
        return reject("wage-market-zone-missing:" + unit.value());
      }
      JsonNode postingsNode = payload.get("postings");
      if (postingsNode == null || !postingsNode.isArray()) {
        throw new IllegalArgumentException(TYPE + " 的 postings 必须是数组（清空给空数组）");
      }
      long commandDay = state.meta().timestamp().tick();
      Map<WagePostingKey, ProductionWagePosting> next =
          new LinkedHashMap<>(base.productionWagePostings());
      next.keySet().removeIf(key -> key.unit().equals(unit));
      for (JsonNode node : postingsNode) {
        if (node == null || !node.isObject()) {
          throw new IllegalArgumentException(TYPE + " 的 postings 元素必须是对象: " + node);
        }
        for (String locked : LOCKED_FIELDS) {
          if (node.has(locked)) {
            return reject("wage-posting-locked:" + unit.value() + ":" + locked);
          }
        }
        ProductionRoleId role =
            ProductionRoleId.parse(EconomyCommandPayloads.requireText(TYPE, node, "role"));
        ProductionRoleDefinition roleDefinition = roleDefinition(planDefinition, role);
        if (roleDefinition == null) {
          return reject("wage-posting-role-unknown:" + unit.value() + ":" + role.value());
        }
        if (!(roleDefinition.payment() instanceof RolePayment.MoneyWage)) {
          return reject("wage-role-not-money:" + unit.value() + ":" + role.value());
        }
        if (!node.has("targetLaborMilliPerDay") || node.get("targetLaborMilliPerDay").isNull()) {
          return reject("wage-target-missing:" + unit.value() + ":" + role.value());
        }
        long target = EconomyCommandPayloads.requireLong(TYPE, node, "targetLaborMilliPerDay");
        if (target < 0L) {
          return reject("wage-target-negative:" + unit.value() + ":" + role.value());
        }
        if (!node.has("wagePerLaborMilli") || node.get("wagePerLaborMilli").isNull()) {
          return reject("wage-quote-missing:" + unit.value() + ":" + role.value());
        }
        long quote = EconomyCommandPayloads.requireLong(TYPE, node, "wagePerLaborMilli");
        if (quote < 0L) {
          return reject("wage-quote-negative:" + unit.value() + ":" + role.value());
        }
        String currencyText = EconomyCommandPayloads.optionalText(TYPE, node, "currency", null);
        CurrencyId currency =
            currencyText == null ? marketZone.legalTender() : CurrencyId.parse(currencyText);
        if (!currency.equals(marketZone.legalTender())) {
          return reject("wage-currency-mismatch:" + unit.value() + ":" + role.value());
        }
        // quoteSource 是合法载荷字段（显式命令解析后固定写成 EXPLICIT）；只校验形状，不让它绕过来源规则。
        String sourceText = EconomyCommandPayloads.optionalText(TYPE, node, "quoteSource", null);
        if (sourceText != null) {
          WageQuoteSource.parse(sourceText);
        }
        WagePostingKey key = new WagePostingKey(unit, role);
        ProductionWagePosting existing = base.productionWagePostings().get(key);
        ProductionWagePosting replacement =
            existing == null
                ? new ProductionWagePosting(
                    unit,
                    role,
                    target,
                    currency,
                    quote,
                    WageQuoteSource.EXPLICIT,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L)
                : replace(existing, target, currency, quote, commandDay);
        if (next.putIfAbsent(key, replacement) != null) {
          return reject("wage-posting-duplicate:" + unit.value() + ":" + role.value());
        }
      }
      EconomyData projected = base.withProductionWagePostings(next);
      // 跨切片完整校验：unit/role/MONEY_WAGE/currency==legalTender/target+quote 范围。
      EconomyReferenceValidation.validateProductionWages(definitions, production, projected, TYPE);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_WAGE_POSTING_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "unit",
                  unit.value(),
                  "postings",
                  postingsNode.size(),
                  "currency",
                  marketZone.legalTender().value(),
                  "replaced",
                  true));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject(e.getMessage());
    }
  }

  /** 保留/清零历史字段的唯一拼写点：只有 quote 真变才写 EXPLICIT + 命令日 + 带符号差并清 streak。 */
  private static ProductionWagePosting replace(
      ProductionWagePosting existing,
      long nextTarget,
      CurrencyId currency,
      long nextQuote,
      long commandDay) {
    boolean quoteChanged = existing.wagePerLaborMilli() != nextQuote;
    long delta = existing.lastAdjustmentDelta();
    long adjustedTick = existing.lastAdjustedTick();
    long shortageStreak = existing.shortageStreak();
    long satisfiedStreak = existing.satisfiedStreak();
    WageQuoteSource source = existing.quoteSource();
    if (quoteChanged) {
      try {
        delta = Math.subtractExact(nextQuote, existing.wagePerLaborMilli());
      } catch (ArithmeticException overflow) {
        throw quoteOverflow(existing.unit(), existing.role());
      }
      adjustedTick = commandDay;
      shortageStreak = 0L;
      satisfiedStreak = 0L;
      source = WageQuoteSource.EXPLICIT;
    }
    return new ProductionWagePosting(
        existing.unit(),
        existing.role(),
        nextTarget,
        currency,
        nextQuote,
        source,
        shortageStreak,
        satisfiedStreak,
        adjustedTick,
        delta,
        existing.lastObservedMatchedLabor(),
        existing.lastObservedEffectiveTarget(),
        existing.lastMaxAbsorbable());
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

  private static MarketZone marketZoneOf(
      ProductionProcess instance, Map<MarketZoneId, MarketZone> marketZones) {
    HexCoord hex;
    try {
      hex = HexCoord.parse(instance.location().value());
    } catch (RuntimeException e) {
      return null;
    }
    for (MarketZone zone : MarketZoneBook.zones(marketZones)) {
      if (zone.hexes().contains(hex)) {
        return zone;
      }
    }
    return null;
  }

  private static IllegalStateException quoteOverflow(ProductionUnitId unit, ProductionRoleId role) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ECONOMY_WAGE_QUOTE_OVERFLOW",
                EconomyLogSource.ECONOMY_COMMAND,
                "unit",
                unit.value(),
                "role",
                role.value()));
    return new IllegalStateException("wage-quote-overflow:" + unit.value() + ":" + role.value());
  }

  private static IllegalStateException crossSliceFault(String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ECONOMY_PRODUCTION_REFERENCE_CONTRACT",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(reason)));
    return new IllegalStateException(TYPE + ": " + reason);
  }

  private static HandlerOutcome reject(String reason) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_SET_PRODUCTION_WAGE_POSTING_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(reason)));
    return new HandlerOutcome.Rejected(reason);
  }
}
