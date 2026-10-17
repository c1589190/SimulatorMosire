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
 * ★★ <b>{@code production.UpsertProductionMode}（GM-only）</b>：创建/显式推进版本的生产方式定义（D-031 P1）。
 *
 * <pre>
 * {@code {"id":"tenancy_fixed_kind","name":"定额实物租佃","version":1,"classStructureId":"...-structure"}}
 * }</pre>
 *
 * <p>★ 版本语义冻结（控制方 §9；原 {@code EconomyGmAdjustments:322}）；引用/结构存在性经 economy 端口判。
 */
public final class UpsertProductionModeHandler implements CommandHandler {

  public static final String TYPE = "production.UpsertProductionMode";

  private static final Logger LOG = ProductionLog.command();

  private final ProductionConsumerPort consumers;

  public UpsertProductionModeHandler(ProductionConsumerPort consumers) {
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
      ProductionModeUpserts.UpsertProjection projection =
          ProductionModeUpserts.upsert(base, consumers, ProductionPayloads.parse(payloadJson));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_MODE_UPSERTED",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "id",
                  projection.mode().id().value(),
                  "version",
                  projection.mode().version(),
                  "created",
                  projection.created(),
                  "changed",
                  !projection.changeSet().isEmpty()));
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalArgumentException e) {
      ProductionPayloads.logRejected(
          LOG,
          ProductionLogSource.PRODUCTION_COMMAND,
          "PRODUCTION_UPSERT_MODE_REJECTED",
          e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
