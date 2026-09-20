package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 交战阶段（spec §三.3，N1）：**进入/退出条件驱动的数据对象**（不是硬编码 Java 分支）。
 *
 * <p>★ {@code entry}/{@code exit} 是数据驱动条件列表（v1 封闭集见 {@link Trigger}）；**链式条件**——{@code
 * Combat.stages[i].exit} 与 {@code stages[i+1].entry} 相等——由**命令期**校验（spec §四 / C1 的 {@code
 * CombatStages}）。 本 record 只守形状与时长不变量。
 *
 * <p>★ {@code participants} 与 {@code Combat.participants} **是两层集合**：阶段可加**独立参与单位**（从大编制拆出的小单位，spec
 * §三.3）。
 */
public record CombatStage(
    CombatStageId id,
    String name,
    Set<UnitId> participants,
    List<Trigger> entry,
    List<Trigger> exit,
    long minDurationTicks,
    long maxDurationTicks,
    OutcomeTable outcomes) {

  public CombatStage {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (participants == null) {
      throw new IllegalArgumentException("participants 不得为 null");
    }
    if (entry == null) {
      throw new IllegalArgumentException("entry 不得为 null");
    }
    if (exit == null) {
      throw new IllegalArgumentException("exit 不得为 null");
    }
    if (outcomes == null) {
      throw new IllegalArgumentException("outcomes 不得为 null");
    }
    if (minDurationTicks < 0) {
      throw new IllegalArgumentException("minDurationTicks 必须 ≥ 0: " + minDurationTicks);
    }
    if (maxDurationTicks < minDurationTicks) {
      throw new IllegalArgumentException(
          "maxDurationTicks 必须 ≥ minDurationTicks: " + maxDurationTicks + " < " + minDurationTicks);
    }
    Set<UnitId> units = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      units.add(unit);
    }
    participants = Collections.unmodifiableSet(units); // ★ 冻在赋值处
    entry = List.copyOf(entry);
    exit = List.copyOf(exit);
  }
}
