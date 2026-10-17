package io.mosire.simos.production.spi;

import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code production.DeactivateProductionMode}（GM-only）</b>：停用一个未被六类真实引用指向的生产方式（D-031 P1）。
 *
 * <pre>{@code {"id":"tenancy_fixed_kind"}}</pre>
 *
 * <p>★ 六类引用者经 {@link ProductionConsumerPort} 读（★★ D-032 B4 / §13.14.5 第 1 条：实现在组合根，装配期构造器注入）。
 */
public final class DeactivateProductionModeHandler implements CommandHandler {

  public static final String TYPE = "production.DeactivateProductionMode";

  private static final Logger LOG = ProductionLog.command();

  private final ProductionConsumerPort consumers;

  public DeactivateProductionModeHandler(ProductionConsumerPort consumers) {
    this.consumers =
        Objects.requireNonNull(consumers, "consumers（组合根必须注入 ProductionConsumerPort 实现）");
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ProductionData base = ProductionSnapshots.of(state);
    try {
      ProductionModeUpserts.DeactivateProjection projection =
          ProductionModeUpserts.deactivate(
              base, state, consumers, ProductionPayloads.parse(payloadJson));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_MODE_DEACTIVATED",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "id",
                  projection.removed().id().value(),
                  "changed",
                  !projection.changeSet().isEmpty()));
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalArgumentException e) {
      ProductionPayloads.logRejected(
          LOG,
          ProductionLogSource.PRODUCTION_COMMAND,
          "PRODUCTION_DEACTIVATE_MODE_REJECTED",
          e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
