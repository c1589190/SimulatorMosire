package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomyReferenceValidation;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>{@code economy.SetProductionRoster}</b>（D-032 D2 §5.4）：GM-only 的**整表替换**名册命令。
 *
 * <pre>{@code
 * {"unit":"unit-farm@0_0-hh-1",
 *  "participants":[
 *    {"household":"hh-landlord","role":"landlord","organizer":true,
 *     "plannedLaborMilliPerDay":0,"rightsStakes":[],"configuredWeight":0},
 *    {"household":"hh-tenant","role":"tenant","organizer":false,
 *     "plannedLaborMilliPerDay":500,"rightsStakes":[],"configuredWeight":0}]}
 * }</pre>
 *
 * <p>★ <b>语义</b>：
 *
 * <ul>
 *   <li><b>整表替换</b>：{@code unit} 的旧名册整体替换；{@code participants: []} = 清除该 unit 名册；
 *   <li>{@code plan} 不由载荷给（从 production 当刻实例读出；给 plan 会制造第二拼写点）；
 *   <li>命令是 GM-only（不进 DM 权限面）—— 权限装配在 C3 工具面；本处理器只做业务语义与校验；
 *   <li>业务拒绝（INFO、零变更）：{@code unit-unknown} / {@code roster-locked} / {@code role-unknown} / {@code
 *       organizer-missing} / {@code organizer-multiple} / {@code role-unfilled}（仅 PRODUCT_SHARE）/
 *       {@code participant-duplicate} / {@code rights-not-owned} / {@code planned-labor-negative} /
 *       {@code configured-weight-invalid} / {@code wage-target-missing} 等。
 * </ul>
 */
public final class EconomySetProductionRosterHandler implements CommandHandler {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetProductionRoster";

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
      ProductionUnitId unit =
          ProductionUnitId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "unit"));
      ProductionDefinitionLookup definitions = EconomyProductionDefinitions.of(state);
      ProductionStateLookup production = EconomyProductionDefinitions.stateOf(state);
      ProductionProcess instance = production.instances().get(unit);
      if (instance == null) {
        return reject("unit-unknown:" + unit.value());
      }
      // ★ D2 §10.1：周期开始后名册冻结（progressDays > 0 ⇒ roster-locked）。
      if (instance.progressDays() > 0L) {
        return reject("roster-locked:" + unit.value());
      }
      IndustryDefinitionView plan =
          definitions
              .industry(instance.plan())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "生产实例指名的方案定义不存在（production 当刻权威，跨切片不一致）: unit="
                              + unit.value()
                              + " plan="
                              + instance.plan().value()));
      JsonNode participantsNode = payload.get("participants");
      if (participantsNode == null || !participantsNode.isArray()) {
        throw new IllegalArgumentException(TYPE + " 的 participants 必须是数组（清除名册给空数组）");
      }
      EconomyData projected;
      if (participantsNode.isEmpty()) {
        projected = base.withoutProductionRoster(unit); // 清除
      } else {
        List<ProductionParticipant> participants = new ArrayList<>();
        for (JsonNode node : participantsNode) {
          if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(TYPE + " 的 participants 元素必须是对象: " + node);
          }
          HouseholdId household =
              HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, node, "household"));
          ProductionRoleId role =
              ProductionRoleId.parse(EconomyCommandPayloads.requireText(TYPE, node, "role"));
          JsonNode organizerNode = node.get("organizer");
          if (organizerNode == null || !organizerNode.isBoolean()) {
            throw new IllegalArgumentException(TYPE + " 的 participant.organizer 必须是布尔值: " + node);
          }
          long plannedLaborMilliPerDay =
              EconomyCommandPayloads.requireLong(TYPE, node, "plannedLaborMilliPerDay");
          if (plannedLaborMilliPerDay < 0L) {
            return reject("planned-labor-negative:" + role.value() + ":" + household.value());
          }
          List<AssetShareId> rightsStakes = new ArrayList<>();
          JsonNode rightsNode = node.get("rightsStakes");
          if (rightsNode != null && !rightsNode.isNull()) {
            if (!rightsNode.isArray()) {
              throw new IllegalArgumentException(TYPE + " 的 participant.rightsStakes 必须是数组");
            }
            for (JsonNode stake : rightsNode) {
              if (stake == null || !stake.isTextual() || stake.asText().isBlank()) {
                throw new IllegalArgumentException(
                    TYPE + " 的 participant.rightsStakes 元素必须是非空字符串: " + stake);
              }
              rightsStakes.add(AssetShareId.parse(stake.asText()));
            }
          }
          long configuredWeight =
              EconomyCommandPayloads.optionalLong(TYPE, node, "configuredWeight", 0L);
          if (configuredWeight < 0L) {
            return reject("configured-weight-invalid:" + role.value() + ":" + household.value());
          }
          participants.add(
              new ProductionParticipant(
                  household,
                  role,
                  organizerNode.asBoolean(),
                  plannedLaborMilliPerDay,
                  rightsStakes,
                  configuredWeight));
        }
        ProductionRoster roster = new ProductionRoster(unit, plan.id(), participants);
        // ★ D2 §5.4：MONEY_WAGE 空 participant 结构上合法；但 C2 不落 target ⇒ 缺 target 具名拒绝。
        for (ProductionRoleDefinition role : plan.roles()) {
          if (!(role.payment() instanceof RolePayment.MoneyWage)) {
            continue;
          }
          if (roster.participants().stream().noneMatch(p -> p.role().equals(role.id()))) {
            return reject("wage-target-missing:" + role.id().value());
          }
        }
        projected = base.withProductionRoster(roster);
      }
      // 跨切片完整校验（角色存在/份额 owner/预算/旧规则禁用/组织派生一致性）。
      // ★ W5-E：写名册是**写入口** ⇒ 显式 enforcePlannedLaborBudget=true，保留 D2-I13 的拒绝语义。
      EconomyReferenceValidation.validateProductionRosters(
          definitions, production, projected, TYPE, true);
      ProductionRoster written = projected.productionRosters().get(unit);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_ROSTER_SET",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "unit",
                  unit.value(),
                  "plan",
                  plan.id().value(),
                  "participants",
                  written == null ? 0 : written.participants().size(),
                  "roles",
                  written == null ? "" : roleValues(written),
                  "organizer",
                  written == null ? "" : written.organizerHousehold().value(),
                  "replaced",
                  true));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return reject(e.getMessage());
    }
  }

  private static String roleValues(ProductionRoster roster) {
    Set<String> roles = new LinkedHashSet<>();
    for (ProductionParticipant participant : roster.participants()) {
      roles.add(participant.role().value());
    }
    return String.join(",", roles);
  }

  private static HandlerOutcome reject(String reason) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ECONOMY_SET_PRODUCTION_ROSTER_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(reason)));
    return new HandlerOutcome.Rejected(reason);
  }
}
