package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.CancelDemand}（R4-E2）：取消一条需求。
 *
 * <pre>{@code {"demand":"demand-HEX--20_-81-wool-RECURRING-PER_CAPITA-1"}}</pre>
 *
 * <p>★ 不存在 ⇒ {@link HandlerOutcome.Rejected}（不静默成功）；<b>只写 {@code demands}</b>。
 */
public final class EconomyCancelDemandHandler implements CommandHandler {

  private static final String COMMAND = "economy.CancelDemand";

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
        return new HandlerOutcome.Rejected("需求不存在: " + demandId.value());
      }
      Map<DemandId, DemandEntry> demands = new LinkedHashMap<>(base.demands());
      demands.remove(demandId);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withDemands(demands)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
