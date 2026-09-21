package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
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
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      CombatStageId stageId = CombatStageId.parse(SdPayloads.requireText(payload, "stageId"));
      CombatOutcomeId outcomeId =
          CombatOutcomeId.parse(SdPayloads.requireText(payload, "selectedOutcomeId"));
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return new HandlerOutcome.Rejected("交战不存在: " + combatId);
      }
      CombatStage stage = SdCombats.stageOrNull(combat, stageId);
      if (stage == null) {
        return new HandlerOutcome.Rejected("阶段不存在: " + stageId);
      }
      if (!inTable(stage, outcomeId)) {
        return new HandlerOutcome.Rejected(
            "结局不在该阶段的 outcomeTable 里（N2）: " + outcomeId + " 不属于 " + stageId);
      }
      CombatState combatState = SdCombats.stateOrNull(base, combatId);
      if (combatState == null) {
        return new HandlerOutcome.Rejected("该交战没有 CombatState: " + combatId);
      }
      if (combatState.selectedOutcome().isPresent()) {
        return new HandlerOutcome.Rejected("该交战已选定结局（不覆盖）: " + combatState.selectedOutcome().get());
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
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withCombatStates(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
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
