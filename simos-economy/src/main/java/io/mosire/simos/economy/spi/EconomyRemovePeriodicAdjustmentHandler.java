package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.RemoveHouseholdPeriodicAdjustment}（P4a，GM-only）：<b>按 id 删除周期家户库存扣增规则</b>。
 *
 * <pre>{@code
 * {"id":"army-pay-1", "reason"?: "gm 撤回军俸计划"}
 * }</pre>
 *
 * <p>★ <b>不存在 ⇒ 具名拒</b>：不静默成功（"删了"与"本来就没有"是两件事；前者应留下一个可审计的发生额， 后者不该悄悄消耗一条 revision）。{@code reason}
 * 只进日志，不参与任何判定。
 */
public final class EconomyRemovePeriodicAdjustmentHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.RemoveHouseholdPeriodicAdjustment";

  private static final Logger LOG = EconomyLog.enterprise();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(TYPE, payloadJson); // 形状校验；本命令没有可声明的格资源目标
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      Removal removal = parse(TYPE, payloadJson);
      HouseholdPeriodicAdjustment removed = base.periodicAdjustments().get(removal.id());
      if (removed == null) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_REMOVE_PERIODIC_ADJUSTMENT_REJECTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "reason",
                    "unknown-rule",
                    "rule",
                    removal.id().value()));
        return new HandlerOutcome.Rejected("周期家户扣增规则不存在，拒绝静默成功: id=" + removal.id().value());
      }
      Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> adjustments =
          new LinkedHashMap<>(base.periodicAdjustments());
      adjustments.remove(removal.id());
      EconomyData projected = base.withPeriodicAdjustments(adjustments);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PERIODIC_ADJUSTMENT_REMOVED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "rule",
                  removal.id().value(),
                  "payer",
                  removed.payer().value(),
                  "reasonLength",
                  removal.reason() == null ? 0 : removal.reason().length()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_REMOVE_PERIODIC_ADJUSTMENT_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 形状解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  static Removal parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    PeriodicHouseholdAdjustmentId id =
        PeriodicHouseholdAdjustmentId.parse(
            EconomyCommandPayloads.requireText(command, payload, "id"));
    String reason = EconomyCommandPayloads.optionalText(command, payload, "reason", "gm:" + TYPE);
    return new Removal(id, reason);
  }

  /** 已解析的一条删除请求：{@code reason} 只进日志。 */
  record Removal(PeriodicHouseholdAdjustmentId id, String reason) {}
}
