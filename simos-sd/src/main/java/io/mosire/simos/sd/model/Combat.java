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
 *
 * <p>★ **执行期取代说明（A3，spec §三.3 的字段形状）**：spec §三.3 把 {@code stages} 写作 {@code List<CombatStageId>}，但
 * {@code SdState} 的 10 个组件里**没有**阶段表（§三.1），而 §三.1.3/§三.1.4 两条不变量（结局一致性、阶段链）都要求从 {@code Combat}
 * 读得到**阶段内容**。故此处取 {@code List<CombatStage>}——阶段对象**内嵌**在它的交战里，组件数仍是 10。记入台账。
 */
public record Combat(
    CombatId id,
    String name,
    List<CombatStage> stages,
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
    Set<CombatStageId> stageIds = new LinkedHashSet<>();
    for (CombatStage stage : stages) {
      if (stage == null) {
        throw new IllegalArgumentException("stages 不得含 null");
      }
      if (!stageIds.add(stage.id())) {
        throw new IllegalArgumentException("stages 不得含重复的阶段 id: " + stage.id());
      }
    }
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
