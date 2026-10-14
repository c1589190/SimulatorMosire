package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * {@code ga.UpsertMergedEffectPlan} 命令的处理器（D3 契约 §3；<b>★ 2026-10-12 批次 3a 自 {@code simos-sd/sd.spi}
 * 迁入并改名 {@code sd.* → ga.*}，语义一字未改</b>）：整包写入/覆盖一个 {@link MergedEffectPlan}。
 *
 * <pre>{@code
 * {"id":"merge-360-1","tick":360,"participantIds":["dm-1","dm-2"],
 *  "orderedEffects":[{"toolName":"simos.unit.raiseUnit","argsJson":"{...}",
 *                     "sourceCallRefs":["pkt-dm-1-360:0"]}],
 *  "sources":["pkt-dm-1-360:0","pkt-dm-2-360:0"],"reasonInfoId":null,"outcome":null}
 * }</pre>
 *
 * <p>★ 同 id <b>幂等替换</b>（整包覆盖）：GM 建计划与执行器回写 {@code outcome} 都走这一条命令。
 *
 * <p>★ 扁平载荷字段由 {@link GaPayloads#requireMergedEffectPlan(JsonNode)} 读；{@code participantIds}
 * 允许历史已删除的决策人（与 packet 同口径，不做存在性校验）。
 *
 * <p>★ {@link CommandTargets#targetPaths} 返回 {@code merged-plan/<id>}；路径串逐字保留（见 {@link
 * UpsertDecisionPacketHandler} 的类注）。
 *
 * <p>★ 改名理由与权限口径同 {@link UpsertDecisionPacketHandler} 的类注（{@code namespaceOf} 按类型前缀选 codec； <b>不打
 * {@code DmUsableCommand} ⇒ 仅 GM</b>）。
 */
public final class UpsertMergedEffectPlanHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "ga.UpsertMergedEffectPlan";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = GaPayloads.parse(payloadJson);
    MergedEffectPlanId id = MergedEffectPlanId.parse(GaPayloads.requireText(payload, "id"));
    return List.of(ResourcePaths.ga("merged-plan", id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String planForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      MergedEffectPlan plan = GaPayloads.requireMergedEffectPlan(payload);
      planForLog = plan.id().value();
      Map<MergedEffectPlanId, MergedEffectPlan> next =
          new LinkedHashMap<>(base.mergedEffectPlans());
      boolean newPlan = !next.containsKey(plan.id());
      next.put(plan.id(), plan); // 整包覆盖，同 id 幂等替换
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_UPSERT_MERGED_EFFECT_PLAN_APPLIED",
                  GaLogSource.GA_DECISION,
                  "plan",
                  plan.id().value(),
                  "tick",
                  plan.tick(),
                  "participants",
                  plan.participantIds().size(),
                  "effects",
                  plan.orderedEffects().size(),
                  "sources",
                  plan.sources().size(),
                  "outcomePresent",
                  plan.outcome().isPresent(),
                  "newPlan",
                  newPlan,
                  "mergedEffectPlans",
                  next.size()));
      return new HandlerOutcome.Applied(
          GaChangeSet.between(base, base.withMergedEffectPlans(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "plan", planForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GaPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GaPayloads.logRejected(
        GaLog.decision(),
        GaLogSource.GA_DECISION,
        "SD_UPSERT_MERGED_EFFECT_PLAN_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
