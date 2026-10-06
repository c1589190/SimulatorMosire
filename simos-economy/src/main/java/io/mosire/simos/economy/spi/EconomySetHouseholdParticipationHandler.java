package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code economy.SetHouseholdParticipation}（P2-B §13.5）：配置一个家户"除当前职业外还参与哪些生产方式" = {@link
 * HouseholdClassMembership#participatingPositionIds()}；只写 {@code classStandings} 一张表。
 *
 * <pre>{@code
 * {"household":"hh-...","positions":["family-farm-family-farmer"],"modes"?["family_farm"],
 *  "reason"?:"gm:...", "day"?:120,"at"?:{"q":0,"r":0}}
 * }</pre>
 *
 * <p>★ <b>语义</b>：
 *
 * <ul>
 *   <li>{@code positions} 是 {@code ClassPositionId} 数组，逐项必须已存在；{@code modes} 是 {@code
 *       ProductionModeId} 数组，展开成该 mode 阶层结构下的全部位置（具体哪些位置真的生产由组织阶段的 {@code shouldProduce}
 *       过滤，本命令不复制那份判据）；两者可同时给，取并集；
 *   <li><b>空数组 = 清空追加集合</b>（回到"只参与 {@code currentPositionId}"的旧口径）；两个字段都缺 ⇒ 具名拒绝
 *       （"想清空"必须显式写空数组，不能靠漏字段）；
 *   <li>当前位置由 {@code currentPositionId} 自动并入（{@link
 *       HouseholdClassMembership#effectivePositionIds()}）， 所以这里<b>不需要也不允许</b>借本命令改"当前职业"（那是 {@code
 *       economy.SetHouseholdClass} 的职责）；
 *   <li>家户没有 standing ⇒ 先按旧 stratum 解析当前位置播种一条，再写追加集合；解析不出 ⇒ 具名拒绝（不伪造归属）；
 *   <li>{@code at} 是给 {@link CommandTargets} 的目标声明；给了就必须等于该家户当刻居住格。
 * </ul>
 *
 * <p>★ <b>权限</b>：非 {@code GmOnlyCommand}（同 {@link EconomySetHouseholdClassHandler}）。
 */
public final class EconomySetHouseholdParticipationHandler
    implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.SetHouseholdParticipation";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    EconomyCommandPayloads.requireText(TYPE, payload, "household");
    if (!payload.has("positions") && !payload.has("modes")) {
      throw new IllegalArgumentException(TYPE + " 至少要给 positions 或 modes（清空请给空数组）");
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
      if (!payload.has("positions") && !payload.has("modes")) {
        throw new IllegalArgumentException(TYPE + " 至少要给 positions 或 modes（清空请给空数组）");
      }
      Set<ClassPositionId> positions = new LinkedHashSet<>();
      positions.addAll(HouseholdEconomyCommands.parsePositions(TYPE, base, payload));
      positions.addAll(HouseholdEconomyCommands.expandModes(TYPE, base, payload));
      // ★ 位置所属 mode 必须在场：不在场的 mode 组织阶段根本不会遍历，写进去就是"看起来可参与、实际永不生效"。
      for (ClassPositionId positionId : positions) {
        ProductionRole position = base.classPositions().get(positionId);
        if (position == null) {
          throw new IllegalArgumentException(TYPE + " 的阶层位置不存在: " + positionId.value());
        }
        if (!base.modes().containsKey(position.modeId())) {
          throw new IllegalArgumentException(
              TYPE
                  + " 的位置所属生产方式不存在（先 economy.GmAdjust.upsertProductionMode）: 位置="
                  + positionId.value()
                  + " mode="
                  + position.modeId().value());
        }
      }
      String reason = EconomyCommandPayloads.optionalText(TYPE, payload, "reason", "gm:" + TYPE);
      long day = EconomyCommandPayloads.optionalLong(TYPE, payload, "day", 0L);
      if (day < 0L) {
        throw new IllegalArgumentException(TYPE + " 的 day 不得为负: " + day);
      }
      HouseholdClassMembership existingClassMembership =
          HouseholdEconomyCommands.requireStandingOrSeed(TYPE, base, household, reason, day);
      HouseholdClassMembership afterClassMembership =
          new HouseholdClassMembership(
              household,
              existingClassMembership.originalPositionId(),
              existingClassMembership.currentPositionId(),
              positions,
              existingClassMembership.retainedShares(),
              existingClassMembership.consecutiveDebtStressCycles(),
              existingClassMembership.lastTransitionDay(),
              reason);
      if (afterClassMembership.equals(base.classStandings().get(household))) {
        return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base));
      }
      LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships =
          new LinkedHashMap<>(base.classStandings());
      classMemberships.put(household, afterClassMembership);
      EconomyData projected = base.withClassMemberships(classMemberships);
      EventLog.channel(EconomyLog.enterprise())
          .info(
              LogEvent.of(
                  "HOUSEHOLD_PARTICIPATION",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "household",
                  household.value(),
                  "positions",
                  HouseholdEconomyCommands.sortedPositionValues(positions),
                  "reasonLength",
                  reason == null ? 0 : reason.length()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
