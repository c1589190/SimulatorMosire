package io.mosire.simos.army.spi;

import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import org.slf4j.Logger;

/**
 * ★ <b>逐条战损的 TRACE 拼写点</b>（2026-10-23 用户裁定：逐笔明细走 TRACE，默认关）。
 *
 * <p>记录/追加/裁决三条命令写口共用这一份：每条 {@link CombatUnitLoss} 的每个人力/装备 {@link CompositionDelta} 各一行；只记稳定
 * id、类型与有符号增量，不记 {@code label} / {@code text} 这类自然语言原文。热路径守 {@code isTraceEnabled()}。
 */
final class ArmyCombatTrace {

  private static final Logger TRACE = ArmyLog.trace();

  private ArmyCombatTrace() {}

  /** 遍历阶段概率表里的全部结局，逐条损失 TRACE。 */
  static void stage(CombatRecordId combatId, CombatStage stage) {
    if (!TRACE.isTraceEnabled()) {
      return;
    }
    for (CombatOutcome outcome : stage.outcomes()) {
      outcome(combatId, stage.id(), outcome);
    }
  }

  /** 单个结局的逐条损失 TRACE。 */
  static void outcome(CombatRecordId combatId, CombatStageId stageId, CombatOutcome outcome) {
    if (!TRACE.isTraceEnabled()) {
      return;
    }
    for (CombatUnitLoss loss : outcome.losses()) {
      for (CompositionDelta delta : loss.manpower()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "ARMY_COMBAT_LOSS_MANPOWER",
                    ArmyLogSource.ARMY_COMBAT,
                    "combat",
                    combatId.value(),
                    "stage",
                    stageId.value(),
                    "outcome",
                    outcome.id().value(),
                    "unit",
                    loss.unit().value(),
                    "type",
                    delta.type(),
                    "amount",
                    delta.amount()));
      }
      for (CompositionDelta delta : loss.equipment()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "ARMY_COMBAT_LOSS_EQUIPMENT",
                    ArmyLogSource.ARMY_COMBAT,
                    "combat",
                    combatId.value(),
                    "stage",
                    stageId.value(),
                    "outcome",
                    outcome.id().value(),
                    "unit",
                    loss.unit().value(),
                    "type",
                    delta.type(),
                    "amount",
                    delta.amount()));
      }
    }
  }
}
