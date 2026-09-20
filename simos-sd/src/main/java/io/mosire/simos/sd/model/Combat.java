package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 交战（场）（spec §三.3）：跨时间的交战状态**汇总**——稳定身份 + 阶段表 + 参与单位集合 + 最终结局。
 *
 * <p>★ {@code stages} 可为**空**（刚 {@code sd.CreateCombat}、尚未 {@code
 * sd.AddCombatStage}）——链式条件由命令期在追加阶段时校验。 {@code participants} 与 {@code CombatStage.participants}
 * 是两层集合。
 */
public record Combat(
    CombatId id,
    String name,
    List<CombatStageId> stages,
    Set<UnitId> participants,
    Optional<CombatOutcomeId> finalOutcome) {

  public Combat {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (stages == null) {
      throw new IllegalArgumentException("stages 不得为 null");
    }
    if (participants == null) {
      throw new IllegalArgumentException("participants 不得为 null");
    }
    if (finalOutcome == null) {
      throw new IllegalArgumentException("finalOutcome 不得为 null（无结局用 Optional.empty()）");
    }
    stages = List.copyOf(stages);
    Set<UnitId> units = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      units.add(unit);
    }
    participants = Collections.unmodifiableSet(units); // ★ 冻在赋值处
  }
}
