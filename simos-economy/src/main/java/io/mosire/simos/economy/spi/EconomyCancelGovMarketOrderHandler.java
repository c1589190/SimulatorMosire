package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
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
 * ★★ {@code economy.CancelGovernmentMarketOrder}（R1，GM-only）：<b>撤销一条政府市场授权</b>。
 *
 * <pre>{@code
 * {"id":"gm:central-buy-grain-1", "reason":"gm: 政策变更"}   // reason 可空
 * }</pre>
 *
 * <p>★ <b>具名拒不存在</b>（{@code unknown-authorization}）：撤销一条不存在的授权是"调用方看错了世界"，静默成功会让 它以为撤销生效了。★
 * 已耗尽的授权由日结算自行清除 ⇒ 这时撤销会得到"不存在"的具名拒（读口仍能看出它已被清除）。
 *
 * <p>★ 撤销只删状态行，<b>不</b>回滚任何已成交的货/钱（已发生的事不篡改）：成交量是历史，撤销只终止后续挂单。
 */
public final class EconomyCancelGovMarketOrderHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.CancelGovernmentMarketOrder";

  private static final Logger LOG = EconomyLog.command();

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
      Request request = parse(TYPE, payloadJson);
      GovernmentMarketMandate existing = base.govMarketMandates().get(request.id());
      if (existing == null) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ECONOMY_CANCEL_GOV_MARKET_ORDER_REJECTED",
                    EconomyLogSource.ECONOMY_COMMAND,
                    "reason",
                    "unknown-authorization",
                    "id",
                    request.id().value()));
        return new HandlerOutcome.Rejected("未知授权（不存在或已被耗尽/到期清除）: " + request.id().value());
      }
      Map<MarketMandateId, GovernmentMarketMandate> mandates =
          new LinkedHashMap<>(base.govMarketMandates());
      mandates.remove(request.id());
      EconomyData projected = base.withGovMarketMandates(mandates);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "GOV_MARKET_ORDER_CANCELLED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "id",
                  existing.id().value(),
                  "government",
                  existing.government().value(),
                  "side",
                  existing.side().name(),
                  "commodity",
                  existing.commodity().value(),
                  "quantityMilli",
                  existing.quantityMilli(),
                  "filledMilli",
                  existing.filledMilli(),
                  "expiresOnDay",
                  existing.expiresOnDay(),
                  "reason",
                  request.reason().isBlank() ? "<none>" : request.reason(),
                  "authorizations",
                  mandates.size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ECONOMY_CANCEL_GOV_MARKET_ORDER_REJECTED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "reason",
                  "payload-invalid",
                  "detail",
                  EconomyCommandPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 载荷形状（{@code targetPaths} 与 {@code handle} 共用）。 */
  static Request parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    MarketMandateId id =
        MarketMandateId.parse(EconomyCommandPayloads.requireText(command, payload, "id"));
    String reason = EconomyCommandPayloads.optionalText(command, payload, "reason", "");
    return new Request(id, reason);
  }

  /** 解析后的载荷。 */
  record Request(MarketMandateId id, String reason) {}
}
