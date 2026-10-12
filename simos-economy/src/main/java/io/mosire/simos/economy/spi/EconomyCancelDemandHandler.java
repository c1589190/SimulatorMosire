package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.CancelDemand}（R4-E2）：取消一条需求。
 *
 * <pre>{@code {"demand":"demand-HEX--20_-81-wool-RECURRING-PER_CAPITA-1"}}</pre>
 *
 * <p>★ 不存在 ⇒ {@link HandlerOutcome.Rejected}（不静默成功）；<b>只写 {@code demands}</b>。
 */
public final class EconomyCancelDemandHandler implements CommandHandler {

  private static final String COMMAND = "economy.CancelDemand";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
      DemandId demandId =
          DemandId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "demand"));
      if (!base.demands().containsKey(demandId)) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_CANCEL_DEMAND_REJECTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "reason",
                    "unknown-demand",
                    "demand",
                    demandId.value()));
        return new HandlerOutcome.Rejected("需求不存在: " + demandId.value());
      }
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ECONOMY_CANCEL_DEMAND_CRITERIA",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "demand",
                    demandId.value(),
                    "demandsBefore",
                    base.demands().size()));
      }
      Map<DemandId, HouseholdDemand> householdDemands = new LinkedHashMap<>(base.demands());
      householdDemands.remove(demandId);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CANCEL_DEMAND_APPLIED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "demand",
                  demandId.value(),
                  "demandsBefore",
                  base.demands().size(),
                  "demandsAfter",
                  householdDemands.size()));
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(base, base.withHouseholdDemands(householdDemands)));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CANCEL_DEMAND_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "reason",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
