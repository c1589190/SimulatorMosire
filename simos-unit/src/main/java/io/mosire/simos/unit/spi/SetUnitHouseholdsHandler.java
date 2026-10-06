package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
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
 * {@code unit.SetUnitHouseholds} 命令的处理器（S3a，2026-10-09 / 架构 §4.2）：<b>整体替换</b>一个 unit 容纳的家户列表。
 *
 * <pre>{@code
 * {"unitId":"gov-central","households":["hh-hindu-001","hh-han-001"],"reason":"整编"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code unitId} 必填；{@code households} 必填数组（空数组合法 = 清空，保序）；{@code reason} 必填非空白
 * （事件/日志要能回答为什么）。元素重复/空白在边界具名拒；unit 不存在、跨单位家户冲突（同一家户同时属于两个 unit、Unit id 与 household id 撞名）由 {@link
 * UnitOperations#setUnitHouseholds} / {@link UnitState} 构造期具名拒。
 *
 * <p>★ <b>本命令只动 unit 切片</b>（返回 {@link UnitChangeSet}）：家户在 Social 侧的位置由 {@code
 * social.SetHouseholdLocation} 负责；"加入 Unit"的两侧原子一致由 app 组合工具（同批）保证（架构 §3.3）。
 *
 * <p>★ <b>日志</b>：应用成功记 INFO {@code event=UNIT_HOUSEHOLDS_SET}；逐家户明细走 TRACE（{@link UnitLog}）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：点名的 unitId，与既有 unit 命令同制（{@code unit} 命名空间的裸 id）。
 */
public final class SetUnitHouseholdsHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "unit.SetUnitHouseholds";

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
      List<HouseholdId> households = UnitPayloads.requireHouseholdIds(payload, "households");
      String reason = requireReason(payload);
      UnitState next = UnitOperations.setUnitHouseholds(snapshot.state(), id, households);
      logApplied(id, households, reason);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** reason 必填非空白（与 Social 侧 {@code requireReason} 同口径；日志/审计要能回答为什么）。 */
  private static String requireReason(JsonNode payload) {
    String reason = UnitPayloads.requireText(payload, "reason");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("字段 reason 不得为空白（命令/日志要能回答为什么）");
    }
    return reason;
  }

  /** INFO 生命周期一条 + TRACE 逐家户明细（架构 §6 的 UNIT_HOUSEHOLDS_SET）。 */
  private static void logApplied(UnitId id, List<HouseholdId> households, String reason) {
    UnitLog.household()
        .info(
            "event=UNIT_HOUSEHOLDS_SET "
                + UnitLog.kv(
                    "unit",
                    id,
                    "count",
                    households.size(),
                    "households",
                    households,
                    "reason",
                    reason));
    if (UnitLog.trace().isTraceEnabled()) {
      for (HouseholdId household : households) {
        UnitLog.trace()
            .trace(
                "event=UNIT_HOUSEHOLD_SET_ITEM "
                    + UnitLog.kv("unit", id, "household", household, "reason", reason));
      }
    }
  }
}
