package io.mosire.simos.sd.model;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 交战状态（spec §三.3）：**实际记录在单个时间线状态里的**是它——当前阶段、当前 hex、参与单位、已选结局、损失记录。
 *
 * <p>★ 结局一致性（§三.1.3）：{@code selectedOutcome} 若存在，必须是其 {@code Combat} 的某阶段 outcomeTable 里的条目——这是
 * {@code SdState} 的构造期不变量（A3），不在本 record。
 *
 * <p>★ {@code participants} 是**第二层**集合（阶段可加独立参与单位）；{@code losses} 引用 sd 自己的 {@code LossRecord}。
 */
public record CombatState(
    CombatStateId id,
    CombatId combatId,
    CombatStageId currentStage,
    HexCoord hex,
    Set<UnitId> participants,
    Optional<CombatOutcomeId> selectedOutcome,
    Set<LossRecordId> losses) {

  public CombatState {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (combatId == null) {
      throw new IllegalArgumentException("combatId 不得为 null");
    }
    if (currentStage == null) {
      throw new IllegalArgumentException("currentStage 不得为 null");
    }
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    if (participants == null) {
      throw new IllegalArgumentException("participants 不得为 null");
    }
    if (selectedOutcome == null) {
      throw new IllegalArgumentException("selectedOutcome 不得为 null（未选用 Optional.empty()）");
    }
    if (losses == null) {
      throw new IllegalArgumentException("losses 不得为 null");
    }
    Set<UnitId> units = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      units.add(unit);
    }
    participants = Collections.unmodifiableSet(units); // ★ 冻在赋值处
    Set<LossRecordId> records = new LinkedHashSet<>();
    for (LossRecordId record : losses) {
      if (record == null) {
        throw new IllegalArgumentException("losses 不得含 null");
      }
      records.add(record);
    }
    losses = Collections.unmodifiableSet(records); // ★ 冻在赋值处
  }
}
