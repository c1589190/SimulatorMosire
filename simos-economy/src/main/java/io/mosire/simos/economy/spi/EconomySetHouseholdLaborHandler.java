package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code economy.SetHouseholdLabor}（P2-B §13.6）：直接配置一个家户的**每 tick 劳动时间预算**与**参与率**；
 * 只写 {@code classes} 一张表。
 *
 * <pre>{@code
 * {"household":"hh-...","laborMilli"?:"28000","participationPerMille"?:1000,
 *  "at"?:{"q":0,"r":0}}
 * }</pre>
 *
 * <p>★ <b>语义与如实边界</b>：
 *
 * <ul>
 *   <li>至少给一个字段；{@code laborMilli ≥ 0}（毫小时）、{@code participationPerMille ∈ [0,1000]}；
 *   <li>只改 {@link HouseholdEconomy} 的这两个字段（{@code HouseholdEconomy.withLaborAndParticipation}，其余字段逐值保留）；
 *   <li>★★ <b>{@code laborMilli} 的常规来源是 Social 成员组成的逐 tick 投影</b>（P2-A §13.4，参与者的
 *       {@code recomputeLaborBudgets}）。本命令是一次显式状态写入：下一次推进时若 Social 侧该户成员存在，投影会按
 *       Social 重算并覆盖它 —— <b>要持久改劳动时间，需同时编辑 Social 成员组成</b>（那不属于本命令的边界）。
 *       本批不做"覆盖位"这类第二权威，读口读到的永远是当刻状态；
 *   <li>{@code at} 是给 {@link CommandTargets} 的目标声明；给了就必须等于该家户当刻居住格。
 * </ul>
 *
 * <p>★ <b>权限</b>：非 {@code GmOnlyCommand}（同 social 的家户命令）；决策令路径是否可达由 {@code economy}
 * 命名空间的目标格可达面决定。
 */
public final class EconomySetHouseholdLaborHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetHouseholdLabor";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    EconomyCommandPayloads.requireText(TYPE, payload, "household");
    if (!payload.has("laborMilli") && !payload.has("participationPerMille")) {
      throw new IllegalArgumentException(TYPE + " 至少要给 laborMilli 或 participationPerMille");
    }
    return HouseholdEconomyCommands.targetPaths(HouseholdEconomyCommands.optionalAt(TYPE, payload));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      HouseholdId household = HouseholdEconomyCommands.requireHousehold(TYPE, base, payload);
      Optional<HexCoord> at = HouseholdEconomyCommands.optionalAt(TYPE, payload);
      HouseholdEconomyCommands.requireAtMatchesHousehold(TYPE, base, household, at);
      if (!payload.has("laborMilli") && !payload.has("participationPerMille")) {
        throw new IllegalArgumentException(TYPE + " 至少要给 laborMilli 或 participationPerMille");
      }
      HouseholdEconomy householdEconomy = base.classes().get(household);
      if (householdEconomy == null) {
        throw new IllegalArgumentException(TYPE + " 的家户不存在: " + household.value());
      }
      long laborMilli =
          payload.has("laborMilli")
              ? EconomyCommandPayloads.requireLong(TYPE, payload, "laborMilli")
              : householdEconomy.laborMilli();
      if (laborMilli < 0L) {
        throw new IllegalArgumentException(TYPE + " 的 laborMilli 不得为负: " + laborMilli);
      }
      int participationPerMille =
          payload.has("participationPerMille")
              ? EconomyCommandPayloads.requireInt(TYPE, payload, "participationPerMille")
              : householdEconomy.participationPerMille();
      if (participationPerMille < 0 || participationPerMille > 1000) {
        throw new IllegalArgumentException(
            TYPE + " 的 participationPerMille 必须 ∈ [0,1000]: " + participationPerMille);
      }
      HouseholdEconomy afterHouseholdEconomy = householdEconomy.withLaborAndParticipation(laborMilli, participationPerMille);
      if (afterHouseholdEconomy.equals(householdEconomy)) {
        return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base));
      }
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies = new LinkedHashMap<>(base.classes());
      householdEconomies.put(household, afterHouseholdEconomy);
      EconomyData projected = base.withHouseholdEconomies(householdEconomies);
      EconomyLog.population()
          .info(
              "event=HOUSEHOLD_LABOR_CONFIG household={} laborMilli={} participationPerMille={}",
              household.value(),
              laborMilli,
              participationPerMille);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
