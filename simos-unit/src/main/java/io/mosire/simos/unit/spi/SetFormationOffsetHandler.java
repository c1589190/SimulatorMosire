package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.SetFormationOffset} 命令的处理器（T3 / spec §一.3 / P2；P8 起**具名拒**）。
 *
 * <p>★★ 编制 v2（2026-09-24，取消跟随）起 {@code RelativeOffset} 不再影响任何计算，P8 用户裁定：本命令**命中即** {@link
 * HandlerOutcome.Rejected}，不再写 {@code offset} 段——不静默忽略、不保留假成功。
 *
 * <p>★ 处理面不再解析 {@code dq}/{@code dr}：命令已退役，任何载荷都返回同一条具名拒因。命令类型与目标声明（{@link CommandTargets} 仍按
 * {@code id} 点名那个单位）不变；{@link io.mosire.simos.unit.ops.UnitOperations#setOffset} 与 {@link
 * io.mosire.simos.unit.RelativeOffset} 保留（旧档/模型可能仍读），只是命令面不再接受。
 *
 * <p>★ 历史口径（旧实现，不再执行）：{@code dq}/{@code dr} 都缺或为 {@code null} ⇒ 清偏移；只给一个 ⇒ 另一个按 0 补；
 * 偏移允许越界（相对父的站位，不判地图内）。
 */
public final class SetFormationOffsetHandler implements CommandHandler, CommandTargets {

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return "unit.SetFormationOffset";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    // 命令已退役、没有成功路径：只有 1 条 INFO 拒绝。id 尽力取（解析失败/形状不符 ⇒ "-"），日志失败不影响结局。
    String idForLog = "-";
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      idForLog = UnitPayloads.optionalText(payload, "id").orElse("-");
    } catch (IllegalArgumentException ignored) {
      // 取不到 id 不是本条命令的判决依据：任何载荷都走同一条具名拒。
    }
    EventLog.channel(UnitLog.command())
        .info(
            LogEvent.of(
                "UNIT_SET_FORMATION_OFFSET_REJECTED",
                UnitLogSource.UNIT_COMMAND,
                "reason",
                "命令已退役：RelativeOffset 当前无任何消费点",
                "unit",
                idForLog));
    return new HandlerOutcome.Rejected(
        "unit.SetFormationOffset 已退役（具名拒）：RelativeOffset 当前无任何消费点"
            + "——移动、编队、战斗都不读它（编制 v2 取消跟随，位置永远是各单位自己的）。"
            + "字段与旧档保留，但命令面不再接受。如需站位调整：绝对落位用 unit.PlaceAt，"
            + "路径移动用 unit.PlanRoute/unit.PlanSparseRoute，编制归属用 "
            + "unit.AttachUnit/unit.DetachUnit/unit.ReparentUnit；相对父的站位偏移暂无替代。");
  }
}
