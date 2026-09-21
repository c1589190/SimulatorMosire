package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sd.SetStageOutcomeTable} 命令的处理器（spec §四，C1）。
 *
 * <pre>{@code
 * {"combatId":"c1","stageId":"s1","outcomes":{"options":[{"id":"o1","label":"胜","weight":3}]}}
 * }</pre>
 *
 * <p>★ 拒绝：交战/阶段不存在；权重 ≤0 / 空表（N2）。
 */
public final class SetOutcomeTableHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.SetStageOutcomeTable";
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
      OutcomeTable outcomes = SdPayloads.requireOutcomeTable(payload, "outcomes");
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return new HandlerOutcome.Rejected("交战不存在: " + combatId);
      }
      CombatStage target = SdCombats.stageOrNull(combat, stageId);
      if (target == null) {
        return new HandlerOutcome.Rejected("阶段不存在: " + stageId);
      }
      List<CombatStage> stages = new ArrayList<>(combat.stages());
      for (int i = 0; i < stages.size(); i++) {
        if (stages.get(i).id().equals(stageId)) {
          stages.set(i, replaceOutcomes(stages.get(i), outcomes));
        }
      }
      Combat nextCombat =
          new Combat(
              combat.id(), combat.name(), stages, combat.participants(), combat.finalOutcome());
      Map<CombatId, Combat> next = new LinkedHashMap<>(base.combats());
      next.put(combatId, nextCombat);
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withCombats(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static CombatStage replaceOutcomes(CombatStage stage, OutcomeTable outcomes) {
    return new CombatStage(
        stage.id(),
        stage.name(),
        stage.participants(),
        stage.entry(),
        stage.exit(),
        stage.minDurationTicks(),
        stage.maxDurationTicks(),
        outcomes);
  }
}
