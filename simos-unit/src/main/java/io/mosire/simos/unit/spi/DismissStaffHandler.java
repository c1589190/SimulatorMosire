package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.DismissStaff} 命令的处理器（阶段 10b-i，2026-10-01）：{@code unitId, role(SCRIBE|YAMEN|POST),
 * count}。
 *
 * <pre>{@code
 * {"unitId":"gov-province-1","role":"YAMEN","count":2}
 * }</pre>
 *
 * <p>★★ <b>只离编、不支付退休待遇、也不回写社会</b>：本 handler 只做 {@code GovernmentFormation.staff[role] -= count}；{@code
 * policy.retirementPerStaff} 的一次性支付与人员回写 由 10b-ii 的配套工具批（actor 支付 + social 回写）或决策令批承担——本命令不是完整退休入口。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#dismissStaff} 给出，边界只折 {@code Rejected}）：单位不存在； 单位不是
 * GOV；{@code role} 未知；{@code count < 1}；{@code 现有 < count}（消息带现有与请求数字）。 减到 0 时<b>保留该角色键</b>（不删键）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判；本命令只写 unit 命名空间。
 */
public final class DismissStaffHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}；P1.5 起 Plan 类直接引用本常量，不再另抄字面量）。 */
  public static final String TYPE = "unit.DismissStaff";

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
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      StaffRole role = UnitPayloads.requireStaffRole(payload, "role");
      long count = UnitPayloads.requireLong(payload, "count");
      UnitState next = UnitOperations.dismissStaff(snapshot.state(), id, role, count);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
