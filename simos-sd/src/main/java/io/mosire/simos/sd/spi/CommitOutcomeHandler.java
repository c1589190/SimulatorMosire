package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd.CommitCombatOutcome} 命令的处理器（spec §四，C2）：为某交战选定**唯一**实际结局。
 *
 * <pre>{@code
 * {"combatId":"c1","stageId":"s1","selectedOutcomeId":"o1"}
 * }</pre>
 *
 * <p>★ 拒绝：交战/阶段不存在；结局**不在该阶段的 outcomeTable 里**（可在**别的**阶段的表里 —— 这正是本条守卫独有的判别面）； 该交战**已选过**（不覆盖，N2
 * 恰一个）。
 */
public final class CommitOutcomeHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CommitCombatOutcome";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String combatForLog = null;
    String stageForLog = null;
    String outcomeForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      combatForLog = combatId.value();
      CombatStageId stageId = CombatStageId.parse(SdPayloads.requireText(payload, "stageId"));
      stageForLog = stageId.value();
      CombatOutcomeId outcomeId =
          CombatOutcomeId.parse(SdPayloads.requireText(payload, "selectedOutcomeId"));
      outcomeForLog = outcomeId.value();
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return rejected("交战不存在: " + combatId, "combat", combatForLog);
      }
      CombatStage stage = SdCombats.stageOrNull(combat, stageId);
      if (stage == null) {
        return rejected("阶段不存在: " + stageId, "combat", combatForLog, "stage", stageForLog);
      }
      if (!inTable(stage, outcomeId)) {
        return rejected(
            "结局不在该阶段的 outcomeTable 里（N2）: " + outcomeId + " 不属于 " + stageId,
            "combat",
            combatForLog,
            "stage",
            stageForLog,
            "outcome",
            outcomeForLog);
      }
      CombatState combatState = SdCombats.stateOrNull(base, combatId);
      if (combatState == null) {
        return rejected("该交战没有 CombatState: " + combatId, "combat", combatForLog);
      }
      if (combatState.selectedOutcome().isPresent()) {
        return rejected(
            "该交战已选定结局（不覆盖）: " + combatState.selectedOutcome().get(),
            "combat",
            combatForLog,
            "outcome",
            outcomeForLog);
      }
      Map<CombatStateId, CombatState> next = new LinkedHashMap<>(base.combatStates());
      next.put(
          combatState.id(),
          new CombatState(
              combatState.id(),
              combatState.combatId(),
              combatState.currentStage(),
              combatState.hex(),
              combatState.participants(),
              Optional.of(outcomeId),
              combatState.losses()));
      EventLog.channel(SdLog.combat())
          .info(
              LogEvent.of(
                  "SD_COMBAT_OUTCOME_COMMITTED",
                  SdLogSource.SD_COMBAT,
                  "combat",
                  combatId.value(),
                  "stage",
                  stageId.value(),
                  "outcome",
                  outcomeId.value(),
                  "options",
                  stage.outcomes().options().size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withCombatStates(next)));
    } catch (IllegalArgumentException e) {
      return rejected(
          e.getMessage(), "combat", combatForLog, "stage", stageForLog, "outcome", outcomeForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.combat(), SdLogSource.SD_COMBAT, "SD_COMMIT_OUTCOME_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  private static boolean inTable(CombatStage stage, CombatOutcomeId outcomeId) {
    for (OutcomeOption option : stage.outcomes().options()) {
      if (option.id().equals(outcomeId)) {
        return true;
      }
    }
    return false;
  }
}
