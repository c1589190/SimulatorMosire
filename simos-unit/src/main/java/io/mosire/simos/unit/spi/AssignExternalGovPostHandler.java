package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
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
 * {@code unit.AssignExternalGovPost} 命令的处理器（Z3d，2026-10-23 用户裁定 B 路）：<b>把不在本 GOV 单位里的外部家户挂到 {@code
 * GovernmentFormation.externalPosts}</b>——只写外部岗位表，<b>绝不碰</b> {@code Unit.households} / Social 位置 /
 * staff（外部户保留原单位与位置，只承接行政任务）。
 *
 * <pre>{@code
 * {"govUnitId":"gov-province-1","householdId":"hh-other-17",
 *  "role":"SCRIBE","tierId":"tier-2","level":"PROVINCE","headOfGovernment":false,"reason":"open-posts-v1"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code govUnitId}/{@code householdId}/{@code reason} 必填；{@code role}/{@code
 * tierId}/{@code level}/{@code headOfGovernment} 可缺省——<b>改派既有外部岗位时缺省字段沿用该岗位既有值</b>；新建外部岗位时缺省 {@code
 * tierId} = 空串（legacy/未指派档位）、{@code level} = 该 GOV 编制自身层级、{@code headOfGovernment} = false、{@code
 * role} 必填（无既有可继承 ⇒ 具名拒，不臆造角色）。
 *
 * <p>★★ <b>模块边界（Z3d 台账的残余裸提交边界）</b>：unit 模块看不见 Social/economy，"该家户存在（Social/economy 行）"由 app 工具
 * {@code simos.gov.openPostsToMarket} 在 preview/apply 两条路径预检；本命令只判结构合法（ {@link
 * GovernmentPostOfHousehold} 构造期）与内外岗位互斥。GM 裸 {@code simos.command.submit} / 决策令嵌入可绕过预检， 这是已经记录的 V1
 * 边界（不存在的家户不会出现在承诺表里 ⇒ 供给桥自然地把它读成"有岗位无承诺"，不伪造供给）。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#assignExternalGovernmentPost} 给出，边界只折 {@code
 * Rejected}）：单位不存在 / 不带 {@code GovernmentFormation}；家户在 {@code Unit.households}（指路 {@code
 * unit.AssignGovPost}）；家户已在内部 {@code householdPosts}（内外互斥）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 {@code govUnitId} 判；本命令只写 unit 命名空间。
 */
public final class AssignExternalGovPostHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}；app 侧工具直接引用本常量，不再另抄字面量）。 */
  public static final String TYPE = "unit.AssignExternalGovPost";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "govUnitId"));
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
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "govUnitId"));
      unitForLog = id.value();
      HouseholdId household = HouseholdId.parse(UnitPayloads.requireText(payload, "householdId"));
      UnitPayloads.requireText(payload, "reason"); // 审计必填；不进状态，只保证调用方给出理由
      Unit unit = snapshot.state().units().get(id);
      GovernmentFormation formation = governmentFormationOrNull(unit);
      GovernmentPostOfHousehold existing =
          formation == null ? null : formation.externalPosts().get(household);

      String roleText = UnitPayloads.optionalText(payload, "role").orElse("");
      StaffRole role;
      if (!roleText.isBlank()) {
        role = parseRole(roleText);
      } else if (existing != null) {
        role = existing.role(); // 改派既有外部岗位：缺省沿用其角色（不臆造）
      } else {
        throw new IllegalArgumentException(
            "role 必填：新建外部岗位必须显式给角色（SCRIBE|YAMEN|POST）；缺省只在改派既有外部岗位时沿用其角色");
      }
      String tierId =
          UnitPayloads.optionalText(payload, "tierId")
              .orElseGet(() -> existing == null ? "" : existing.tierId());
      GovernmentLevel level =
          UnitPayloads.optionalText(payload, "level")
              .map(AssignExternalGovPostHandler::parseLevel)
              .orElseGet(
                  () ->
                      existing != null
                          ? existing.level()
                          : (formation == null ? GovernmentLevel.PROVINCE : formation.level()));
      boolean head =
          UnitPayloads.optionalBoolean(payload, "headOfGovernment")
              .orElseGet(() -> existing != null && existing.headOfGovernment());
      UnitState next =
          UnitOperations.assignExternalGovernmentPost(
              snapshot.state(), id, household, role, level, head, tierId);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_ASSIGN_EXTERNAL_GOV_POST_APPLIED",
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
                  "UNIT_ASSIGN_EXTERNAL_GOV_POST_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 单位存在且带 {@code GovernmentFormation} ⇒ 返回它；否则 null（域层随后给具名拒，这里只为缺省解析）。 */
  private static GovernmentFormation governmentFormationOrNull(Unit unit) {
    if (unit != null && unit.module().orElse(null) instanceof GovernmentFormation formation) {
      return formation;
    }
    return null;
  }

  /** 显式角色文本 → 词表；未知串具名拒（不静默回落）。 */
  private static StaffRole parseRole(String text) {
    try {
      return StaffRole.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + text, e);
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
}
