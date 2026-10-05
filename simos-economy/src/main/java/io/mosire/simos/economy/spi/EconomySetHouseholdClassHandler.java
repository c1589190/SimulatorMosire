package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code economy.SetHouseholdClass}（P2-B §13.6）：把某个家户的 {@link ClassStanding#currentPositionId()}
 * 改到一个**已存在**的 {@link ClassPosition} 上；只写 {@code classStandings} 一张表。
 *
 * <pre>{@code
 * {"household":"hh-...","position":"wage-farm-wage-laborer","originalPosition"?:"...",
 *  "reason"?:"gm:...","day"?:120,"at"?:{"q":0,"r":0}}
 * }</pre>
 *
 * <p>★ <b>语义边界（逐条）</b>：
 *
 * <ul>
 *   <li>{@code position} 必须已在 {@code classPositions} 里（引用完整性在本命令边界给出可读拒绝；{@code EconomyData}
 *       构造期守卫是第二道）；
 *   <li>{@code originalPosition} 缺省 = 既有 standing 的原所属 / 新建时 = {@code position}；
 *       {@code participatingPositionIds}（追加参与集合）与 {@code retainedShares}、债务压力计数**逐值保留** ——
 *       本命令的承诺就是"只改当前职业"，要改追加集合请用 {@code economy.SetHouseholdParticipation}；
 *   <li>家户没有 {@code ClassStanding} ⇒ 新建一条（original = current = position，追加集合空）；
 *   <li>它<b>不改</b> {@code ClassRow.view}（那是 Social 阶层名/旧分类器的投影），也不动人口/劳动/资产 ——
 *       "家户 Class"的权威是 {@code ClassStanding}，组织阶段按它解析可参与位置；
 *   <li>{@code at} 是给 {@link CommandTargets} 的目标声明（见 {@link HouseholdEconomyCommands}）；给了就必须等于
 *       该家户当刻居住格。
 * </ul>
 *
 * <p>★ <b>权限</b>：非 {@code GmOnlyCommand}（与 social 的家户命令同待遇）⇒ 可进决策令白名单与
 * {@code sd.RegisterEffect}；是否真的到得了那条路径由决策人的资源可达面（{@code economy} 命名空间的目标格）决定。
 */
public final class EconomySetHouseholdClassHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetHouseholdClass";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    EconomyCommandPayloads.requireText(TYPE, payload, "household");
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
      ClassPosition position =
          HouseholdEconomyCommands.requirePosition(
              TYPE, base, EconomyCommandPayloads.requireText(TYPE, payload, "position"));
      String reason = EconomyCommandPayloads.optionalText(TYPE, payload, "reason", "gm:" + TYPE);
      long day = EconomyCommandPayloads.optionalLong(TYPE, payload, "day", 0L);
      if (day < 0L) {
        throw new IllegalArgumentException(TYPE + " 的 day 不得为负: " + day);
      }
      ClassStanding existing = base.classStandings().get(household);
      ClassPositionId originalPosition = position.id();
      if (payload.hasNonNull("originalPosition")) {
        originalPosition =
            HouseholdEconomyCommands.requirePosition(
                    TYPE, base, EconomyCommandPayloads.requireText(TYPE, payload, "originalPosition"))
                .id();
      } else if (existing != null) {
        originalPosition = existing.originalPositionId();
      }
      ClassStanding after =
          new ClassStanding(
              household,
              originalPosition,
              position.id(),
              existing == null ? Set.of() : existing.participatingPositionIds(),
              existing == null ? Map.of() : existing.retainedShares(),
              existing == null ? 0L : existing.consecutiveDebtStressCycles(),
              day,
              reason);
      if (after.equals(existing)) {
        return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base));
      }
      LinkedHashMap<HouseholdId, ClassStanding> standings =
          new LinkedHashMap<>(base.classStandings());
      standings.put(household, after);
      EconomyData projected = base.withClassStandings(standings);
      io.mosire.simos.economy.EconomyLog.organization()
          .info(
              "event=HOUSEHOLD_CLASS household={} currentPosition={} originalPosition={} reason={}",
              household.value(),
              position.id().value(),
              originalPosition.value(),
              reason);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
