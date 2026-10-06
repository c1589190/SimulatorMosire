package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.OfficePolicy;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetGovFormation} 命令的处理器（阶段 10a，控制方修订：编制字段在 {@code Unit.module}，故这是 unit 域命令）。
 *
 * <pre>{@code
 * {"unitId":"gov-central","level":"CENTRAL",
 *  "superiorGov":"gov-province-1","staff":{"SCRIBE":12,"YAMEN":8},
 *  "policy":{"grainPerStaffPerTick":300,"staffCap":{"SCRIBE":40}}}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code level} 必填词表（CENTRAL|PROVINCE）；{@code superiorGov} 可缺省（中央应为空）；{@code
 * staff} 缺省空表、{@code policy} 缺省 {@link OfficePolicy#defaults()}（也可给部分字段，缺省字段取 defaults）；{@code
 * householdPosts?} 是以 {@link HouseholdId} 为键的领导层家户配置，缺省 = 保持既有配置。
 *
 * <p>★★ <b>线格式已删键 {@code households}</b>（2026-10-09 唯一列表裁定）：编制里不再有家户列表；“谁在这个 Unit 里”的唯一实质列表是 {@code
 * Unit.households}，本命令不再接收、也不维护它。政府家户 {@code hh-gov-<unitId>} 由 {@link
 * UnitOperations#setGovernmentFormation} 在改编制时同批编入 {@code Unit.households}；其余家户请先用 {@code
 * unit.SetUnitHouseholds} 编入（GOV 单位上必须保留政府家户）。
 *
 * <p>★ <b>拒因</b>（全部由 {@link UnitOperations#setGovernmentFormation} 给出，边界只折 {@code
 * Rejected}）：单位不存在；单位已带 {@code ArmyFormation}（一单位至多一个标签，不静默替换）；{@code superiorGov} 不存在 / 不是 GOV /
 * 指向自身；单位已容纳别的政府家户。 同类型重复设置 = 整体替换（文档见操作面）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判（与既有 unit 命令同制）。本命令只写 unit 命名空间。
 */
public final class SetGovernmentFormationHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetGovFormation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String unitForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      // ★ 2026-10-09 唯一列表裁定：本命令不再接收 households（线格式键已删）——静默忽略等于让旧调用点
      //   以为"家户已编入"。具名拒并指路唯一整体替换口 unit.SetUnitHouseholds。
      if (payload.has("households") && !payload.get("households").isNull()) {
        throw new IllegalArgumentException(
            "unit.SetGovFormation 不再接收 households（2026-10-09 唯一列表裁定：谁的实质列表只有 "
                + "Unit.households）：立编制由域层把政府家户 hh-gov-<unitId> 编入 Unit.households；"
                + "其余家户请走 unit.SetUnitHouseholds（GOV 单位须保留该政府家户）");
      }
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      unitForLog = id.value();
      GovernmentLevel level = UnitPayloads.requireGovernmentLevel(payload, "level");
      Optional<UnitId> superiorGov = UnitPayloads.optionalId(payload, "superiorGov");
      Map<StaffRole, Long> staff = UnitPayloads.optionalStaffMap(payload, "staff").orElse(Map.of());
      OfficePolicy policy = UnitPayloads.optionalPolicy(payload, "policy");
      // ★ S3b 兼容口径保留：householdPosts 载荷缺席 ⇒ 保持既有领导配置（不是清空）。
      Map<HouseholdId, GovernmentPostOfHousehold> governmentPostsOfHousehold =
          UnitPayloads.optionalGovernmentPosts(payload, "householdPosts")
              .orElseGet(() -> existingGovPosts(snapshot.state(), id));
      GovernmentFormation formation =
          new GovernmentFormation(staff, governmentPostsOfHousehold, policy, superiorGov, level);
      UnitState next = UnitOperations.setGovernmentFormation(snapshot.state(), id, formation);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_GOV_FORMATION_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "unit",
                  id.value(),
                  "level",
                  level,
                  "staff",
                  staff.size()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_SET_GOV_FORMATION_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "unit",
                  unitForLog == null ? "-" : unitForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 既有 GOV 的领导家户配置（载荷未给 {@code householdPosts} 时的保持值）：不是 GOV ⇒ 空表。 */
  private static Map<HouseholdId, GovernmentPostOfHousehold> existingGovPosts(
      UnitState state, UnitId id) {
    Unit unit = state.units().get(id);
    if (unit != null && unit.module().orElse(null) instanceof GovernmentFormation gov) {
      return gov.governmentPostsOfHousehold();
    }
    return Map.of();
  }
}
