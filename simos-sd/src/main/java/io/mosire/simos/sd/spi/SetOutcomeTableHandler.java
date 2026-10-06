package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
    String combatForLog = null;
    String stageForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      combatForLog = combatId.value();
      CombatStageId stageId = CombatStageId.parse(SdPayloads.requireText(payload, "stageId"));
      stageForLog = stageId.value();
      OutcomeTable outcomes = SdPayloads.requireOutcomeTable(payload, "outcomes");
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return rejected("交战不存在: " + combatId, "combat", combatForLog, "stage", stageForLog);
      }
      CombatStage target = SdCombats.stageOrNull(combat, stageId);
      if (target == null) {
        return rejected("阶段不存在: " + stageId, "combat", combatForLog, "stage", stageForLog);
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
      EventLog.channel(SdLog.combat())
          .info(
              LogEvent.of(
                  "SD_SET_STAGE_OUTCOME_TABLE_APPLIED",
                  SdLogSource.SD_COMBAT,
                  "combat",
                  combatId.value(),
                  "stage",
                  stageId.value(),
                  "options",
                  outcomes.options().size(),
                  "stages",
                  stages.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withCombats(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "combat", combatForLog, "stage", stageForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.combat(),
        SdLogSource.SD_COMBAT,
        "SD_SET_STAGE_OUTCOME_TABLE_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
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
