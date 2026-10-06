package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.UpsertMergedEffectPlan} 命令的处理器（D3 契约 §3）：整包写入/覆盖一个 {@link MergedEffectPlan}。
 *
 * <pre>{@code
 * {"id":"merge-360-1","tick":360,"participantIds":["dm-1","dm-2"],
 *  "orderedEffects":[{"toolName":"simos.unit.raiseUnit","argsJson":"{...}",
 *                     "sourceCallRefs":["pkt-dm-1-360:0"]}],
 *  "sources":["pkt-dm-1-360:0","pkt-dm-2-360:0"],"reasonInfoId":null,"outcome":null}
 * }</pre>
 *
 * <p>★ 同 id **幂等替换**（整包覆盖）：GM 建计划与执行器回写 {@code outcome} 都走这一条命令。
 *
 * <p>★ 扁平载荷字段由 {@link SdPayloads#requireMergedEffectPlan(JsonNode)} 读；{@code participantIds}
 * 允许历史已删除 决策人（与 packet 同口径，不做存在性校验）。
 *
 * <p>★ {@link CommandTargets#targetPaths} 返回 {@code merged-plan/<id>}（GM 侧 sd unlimited）。
 */
public final class UpsertMergedEffectPlanHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "sd.UpsertMergedEffectPlan";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SdPayloads.parse(payloadJson);
    MergedEffectPlanId id = MergedEffectPlanId.parse(SdPayloads.requireText(payload, "id"));
    return List.of(ResourcePaths.sd("merged-plan", id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      MergedEffectPlan plan = SdPayloads.requireMergedEffectPlan(payload);
      Map<MergedEffectPlanId, MergedEffectPlan> next =
          new LinkedHashMap<>(base.mergedEffectPlans());
      next.put(plan.id(), plan); // 整包覆盖，同 id 幂等替换
      return new HandlerOutcome.Applied(
          SdChangeSet.between(base, base.withMergedEffectPlans(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
