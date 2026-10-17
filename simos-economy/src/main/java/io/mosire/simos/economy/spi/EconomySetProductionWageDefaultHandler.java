package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomyReferenceValidation;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.WageDefaultKey;
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
 * ★★★ <b>{@code economy.SetProductionWageDefault}</b>（D-032 D3 §2.10）：GM-only 的候选级工资默认整表替换命令。
 *
 * <pre>{@code
 * {"plan":"farm","marketZone":"zone-0_0",
 *  "roles":[{"role":"hired-hand","initialWagePerLaborMilli":1000,
 *            "initialTargetLaborMilliPerDay":5000}]}
 * }</pre>
 *
 * <p>★ <b>整表替换</b>该 {@code (plan, marketZone)} 的 default 集合；{@code roles: []} 清空，不做 patch。 本命令只写候选级
 * default，不写执行期 posting，不隐式创建实例。
 *
 * <p>业务拒绝（INFO、零变更）：{@code wage-default-plan-unknown} / {@code wage-default-role-unknown} / {@code
 * wage-default-role-not-money} / {@code wage-default-market-zone-missing} / {@code
 * wage-default-target-missing} / {@code wage-default-quote-negative} / {@code
 * wage-default-target-negative}。
 */
public final class EconomySetProductionWageDefaultHandler implements CommandHandler {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetProductionWageDefault";

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
      IndustryId plan = IndustryId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "plan"));
      MarketZoneId marketZone =
          MarketZoneId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "marketZone"));
      ProductionDefinitionLookup definitions = EconomyProductionDefinitions.of(state);
      ProductionStateLookup production = EconomyProductionDefinitions.stateOf(state);
      IndustryDefinitionView planDefinition =
          definitions
              .industry(plan)
              .orElseThrow(
                  () -> new IllegalArgumentException("wage-default-plan-unknown:" + plan.value()));
      if (!base.marketZones().containsKey(marketZone)) {
        return reject("wage-default-market-zone-missing:" + marketZone.value());
      }
      JsonNode rolesNode = payload.get("roles");
      if (rolesNode == null || !rolesNode.isArray()) {
        throw new IllegalArgumentException(TYPE + " 的 roles 必须是数组（清空给空数组）");
      }
      Map<WageDefaultKey, ProductionWageDefault> next =
          new LinkedHashMap<>(base.productionWageDefaults());
      next.keySet().removeIf(key -> key.plan().equals(plan) && key.marketZone().equals(marketZone));
      for (JsonNode node : rolesNode) {
        if (node == null || !node.isObject()) {
          throw new IllegalArgumentException(TYPE + " 的 roles 元素必须是对象: " + node);
        }
        ProductionRoleId role =
            ProductionRoleId.parse(EconomyCommandPayloads.requireText(TYPE, node, "role"));
        ProductionRoleDefinition roleDefinition = roleDefinition(planDefinition, role);
        if (roleDefinition == null) {
          return reject("wage-default-role-unknown:" + role.value());
        }
        if (!(roleDefinition.payment() instanceof RolePayment.MoneyWage)) {
          return reject("wage-default-role-not-money:" + role.value());
        }
        if (!node.has("initialWagePerLaborMilli")
            || node.get("initialWagePerLaborMilli").isNull()) {
          throw new IllegalArgumentException(TYPE + " 的 role 缺少 initialWagePerLaborMilli");
        }
        if (!node.has("initialTargetLaborMilliPerDay")
            || node.get("initialTargetLaborMilliPerDay").isNull()) {
          return reject("wage-default-target-missing:" + role.value());
        }
        long quote = EconomyCommandPayloads.requireLong(TYPE, node, "initialWagePerLaborMilli");
        if (quote < 0L) {
          return reject("wage-default-quote-negative:" + role.value());
        }
        long target =
            EconomyCommandPayloads.requireLong(TYPE, node, "initialTargetLaborMilliPerDay");
        if (target < 0L) {
          return reject("wage-default-target-negative:" + role.value());
        }
        if (target == 0L) {
          return reject("wage-default-target-missing:" + role.value());
        }
        WageDefaultKey key = new WageDefaultKey(plan, role, marketZone);
        if (next.putIfAbsent(key, new ProductionWageDefault(plan, role, marketZone, quote, target))
            != null) {
          return reject(
              "wage-default-role-duplicate:"
                  + plan.value()
                  + ":"
                  + marketZone.value()
                  + ":"
                  + role.value());
        }
      }
      EconomyData projected = base.withProductionWageDefaults(next);
      // 跨切片完整校验：角色存在/支付类型/市场区与目标/报价范围。
      EconomyReferenceValidation.validateProductionWages(definitions, production, projected, TYPE);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_WAGE_DEFAULT_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "plan",
                  plan.value(),
                  "marketZone",
                  marketZone.value(),
                  "roles",
                  rolesNode.size(),
                  "replaced",
                  true));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject(e.getMessage());
    }
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
                "ECONOMY_SET_PRODUCTION_WAGE_DEFAULT_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(reason)));
    return new HandlerOutcome.Rejected(reason);
  }
}
