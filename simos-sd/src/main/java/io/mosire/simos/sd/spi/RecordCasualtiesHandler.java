package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code sd.RecordCasualties} 命令的处理器（spec §四，C3）：战损以 **delta（事件）** 记入 {@link
 * LossRecord}，人员/装备**双轨**。
 *
 * <pre>{@code
 * {"combatId":"c1","stageId":"s1",
 *  "deltas":[{"unit":"u-1","personnel":-10,"equipment":{"步枪":-5},"lossClass":"PERMANENT"}]}
 * }</pre>
 *
 * <p>★ **上界由代码判**（N3 / §八.4，绝不交给 AI）：{@code |Δ| ≤ 当前值}，当前值从 **unit 切片**读（只读，铁律 3）。越界**命令期拒绝**，
 * 不靠事后。
 *
 * <p>★ {@code RECOVERABLE} v1 **只记类别、不设回池**（spec §〇.3）。{@code atRevision} 落 revision ⇒ 可回放且逐值相等。
 */
public final class RecordCasualtiesHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.RecordCasualties";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    UnitState units = SdSnapshots.units(state);
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      CombatId combatId = CombatId.parse(SdPayloads.requireText(payload, "combatId"));
      CombatStageId stageId = CombatStageId.parse(SdPayloads.requireText(payload, "stageId"));
      List<CasualtyDelta> deltas = SdPayloads.requireDeltas(payload, "deltas");
      Combat combat = base.combats().get(combatId);
      if (combat == null) {
        return new HandlerOutcome.Rejected("交战不存在: " + combatId);
      }
      if (SdCombats.stageOrNull(combat, stageId) == null) {
        return new HandlerOutcome.Rejected("阶段不存在: " + stageId);
      }
      for (CasualtyDelta delta : deltas) {
        Unit unit = units.units().get(delta.unit());
        if (unit == null) {
          return new HandlerOutcome.Rejected("单位不存在: " + delta.unit());
        }
        requireWithinBound(delta, unit);
      }
      RevisionId atRevision = state.meta().ref().revision();
      LossRecordId recordId = new LossRecordId(combatId.value() + ":" + atRevision.value());
      if (base.lossRecords().containsKey(recordId)) {
        return new HandlerOutcome.Rejected("损失记录已存在: " + recordId);
      }
      LossRecord record = new LossRecord(recordId, combatId, stageId, atRevision, deltas);
      Map<LossRecordId, LossRecord> nextRecords = new LinkedHashMap<>(base.lossRecords());
      nextRecords.put(recordId, record);

      CombatState combatState = SdCombats.stateOrNull(base, combatId);
      Map<CombatStateId, CombatState> nextStates = new LinkedHashMap<>(base.combatStates());
      if (combatState != null) {
        Set<LossRecordId> losses = new LinkedHashSet<>(combatState.losses());
        losses.add(recordId);
        nextStates.put(
            combatState.id(),
            new CombatState(
                combatState.id(),
                combatState.combatId(),
                combatState.currentStage(),
                combatState.hex(),
                combatState.participants(),
                combatState.selectedOutcome(),
                losses));
      }
      SdState next = base.withLossRecords(nextRecords);
      if (combatState != null) {
        next = next.withCombatStates(nextStates);
      }
      return new HandlerOutcome.Applied(SdChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  private static void requireWithinBound(CasualtyDelta delta, Unit unit) {
    if (delta.personnel() < -unit.member()) {
      throw new IllegalArgumentException(
          "人员战损超出当前值: " + unit.member() + " + (" + delta.personnel() + ")");
    }
    for (Map.Entry<String, Integer> entry : delta.equipment().entrySet()) {
      Integer current = unit.equipment().get(entry.getKey());
      if (current == null) {
        throw new IllegalArgumentException("未知装备键: " + entry.getKey());
      }
      if (entry.getValue() < -current) {
        throw new IllegalArgumentException(
            "装备战损超出当前值: " + entry.getKey() + "=" + current + " + (" + entry.getValue() + ")");
      }
    }
  }
}
