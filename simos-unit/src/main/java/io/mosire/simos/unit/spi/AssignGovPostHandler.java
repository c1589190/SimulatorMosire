package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.AssignGovPost} 命令的处理器（Z4 新增）：<b>把某家户指派/改派到某档位岗位的窄写口</b>——只写 {@code
 * GovernmentFormation.householdPosts}，<b>绝不碰 staff</b>（C4 一处真相）。
 *
 * <pre>{@code
 * {"unitId":"gov-province-1","household":"hh-unit:gov-province-1",
 *  "role":"SCRIBE","tierId":"tier-2","level":"PROVINCE","head":false}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code unitId}/{@code household}/{@code role} 必填；{@code tierId} 可缺省（缺省空串 =
 * legacy/未指派档位，指向 {@code GovAdministrationPlan.postTiers}；unit 模块看不见 gov 计划，跨切片校验在 app/gov
 * 侧）；{@code level} 可缺省（缺省 = 该 GOV 编制自身层级）；{@code head} 可缺省（缺省 false）。同键岗位 = 整条替换。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#assignGovernmentPost} 给出，边界只折 {@code Rejected}）：单位不存在 / 不带
 * {@code GovernmentFormation}；岗位家户不在 {@code Unit.households}（先 {@code unit.SetUnitHouseholds}
 * 把它编入）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判；本命令只写 unit 命名空间。
 */
public final class AssignGovPostHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}；app 侧 Plan 类直接引用本常量，不再另抄字面量）。 */
  public static final String TYPE = "unit.AssignGovPost";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      unitForLog = id.value();
      HouseholdId household = HouseholdId.parse(UnitPayloads.requireText(payload, "household"));
      StaffRole role = UnitPayloads.requireStaffRole(payload, "role");
      String tierId = UnitPayloads.optionalText(payload, "tierId").orElse("");
      Unit unit = snapshot.state().units().get(id);
      GovernmentLevel level =
          UnitPayloads.optionalText(payload, "level")
              .map(AssignGovPostHandler::parseLevel)
              .orElseGet(() -> defaultLevel(unit));
      boolean head = UnitPayloads.optionalBoolean(payload, "head").orElse(false);
      UnitState next =
          UnitOperations.assignGovernmentPost(
              snapshot.state(), id, household, role, level, head, tierId);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_ASSIGN_GOV_POST_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "household",
                  household.value(),
                  "role",
                  role.name(),
                  "tierId",
                  tierId.isEmpty() ? "-" : tierId));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_ASSIGN_GOV_POST_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 显式层级文本 → 词表；未知串具名拒（不静默回落默认）。 */
  private static GovernmentLevel parseLevel(String text) {
    try {
      return GovernmentLevel.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("level 不是合法层级（CENTRAL|PROVINCE）: " + text, e);
    }
  }

  /** 缺省层级 = 该 GOV 编制自身层级；单位不存在/不是 GOV 时由域层给具名拒，这里给中性值只为解析完成。 */
  private static GovernmentLevel defaultLevel(Unit unit) {
    if (unit != null && unit.module().orElse(null) instanceof GovernmentFormation formation) {
      return formation.level();
    }
    return GovernmentLevel.PROVINCE;
  }
}
