package io.mosire.simos.sd.time;

import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 条件求值（spec §三.3 N1 / §三.6 R6）：**纯函数**，数据驱动，不硬编码 Java 分支。
 *
 * <p>★ v1 语义：{@code ThresholdKills} = **全体损失记录里的人员损失绝对值之和**（sd 没有独立击杀计数；损失记录即事实来源）。 {@code
 * AfterTicks(n)} = 相对参照点 {@code sinceTick} 再经过 n tick（效果用其 {@code createdTick}，阶段用 0）。
 */
public final class TriggerEvaluator {

  private TriggerEvaluator() {}

  public static boolean evaluate(
      Trigger trigger, SimosTimestamp at, SdState sd, UnitState units, long sinceTick) {
    Objects.requireNonNull(trigger, "trigger");
    return switch (trigger) {
      case Trigger.AtOrAfterTick t -> at.tick() >= t.tick();
      case Trigger.AfterTicks t -> at.tick() - sinceTick >= t.ticks();
      case Trigger.UnitAtHex t ->
          units.effectivePosition(t.unit(), at).filter(t.hex()::equals).isPresent();
      case Trigger.ThresholdKills t -> cumulativeKills(sd) >= t.kills();
      case Trigger.OutcomeSelected t ->
          sd.combatStates().values().stream()
              .filter(state -> state.combatId().equals(t.combat()))
              .anyMatch(state -> state.selectedOutcome().filter(t.outcome()::equals).isPresent());
      case Trigger.And t -> {
        for (Trigger child : t.all()) {
          if (!evaluate(child, at, sd, units, sinceTick)) {
            yield false;
          }
        }
        yield true;
      }
      case Trigger.Or t -> {
        for (Trigger child : t.any()) {
          if (evaluate(child, at, sd, units, sinceTick)) {
            yield true;
          }
        }
        yield false;
      }
    };
  }

  public static long cumulativeKills(SdState sd) {
    long total = 0;
    for (LossRecord record : sd.lossRecords().values()) {
      for (CasualtyDelta delta : record.deltas()) {
        total += -delta.personnel();
      }
    }
    return total;
  }
}
