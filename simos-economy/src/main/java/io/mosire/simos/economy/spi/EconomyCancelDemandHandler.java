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
import java.util.Optional;

/**
 * ★★ {@code economy.CancelDemand}（R4-E2）：取消一条需求。
 *
 * <pre>{@code {"demand":"demand-HEX--20_-81-wool-RECURRING-PER_CAPITA-1"}}</pre>
 *
 * <p>★ 不存在 ⇒ {@link HandlerOutcome.Rejected}（不静默成功）；<b>只写 {@code demands}</b>。
 *
 * <p>★★ <b>class-first 世界拒绝</b>：{@link EconomyData#classFirst()} 非空时本命令由 {@link
 * ClassFirstCommandGuard} 在读取 base 后立即具名拒绝 —— class-first 消费由结算按口粮/非必要品规则决定，<b>不读</b> {@code
 * demands}（需求账本不参与 class-first 结算），对应工具未接（后续阶段）；{@code classFirst} 为空（旧档/未播种）时本命令行为逐字不变。
 */
public final class EconomyCancelDemandHandler implements CommandHandler {

  private static final String COMMAND = "economy.CancelDemand";

  /** class-first 拒绝的理由主体（不读什么 + 真值在哪 + 指路）。 */
  private static final String CLASS_FIRST_GUIDANCE =
      "class-first 消费由结算按口粮/非必要品规则决定，不读 demands（需求账本不参与 class-first 结算）；" + "对应工具未接（后续阶段）";

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Optional<HandlerOutcome> classFirstRejection =
        ClassFirstCommandGuard.rejectIfClassFirst(COMMAND, base, CLASS_FIRST_GUIDANCE);
    if (classFirstRejection.isPresent()) {
      return classFirstRejection.get();
    }
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
