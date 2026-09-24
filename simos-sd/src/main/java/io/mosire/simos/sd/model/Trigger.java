package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.unit.UnitId;
import java.util.List;

/**
 * 触发条件（spec §三.3 的 {@code entry}/{@code exit}、§三.6 的 {@code Effect.trigger}）：**数据驱动**、不是硬编码 Java
 * 分支；v1 封闭集（N1）。
 *
 * <p>★ 执行期取代说明：spec §三.3 把它写作 {@code Condition}、条件名写作 {@code AtOrAfterTick}；计划 §三 A2 写作 {@code
 * Trigger}/{@code AtTick}。本实现取 **{@code Trigger}**（与 §三.6 一致）与 **{@code AtOrAfterTick}**（与 §十一.5
 * 判据一致）。 记台账。
 *
 * <p>★ **sealed 多态**：裸往返不可能（同 {@code FieldDelta}）⇒ 类型信息以注解钉在类型上。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Trigger.AtOrAfterTick.class, name = "at_or_after_tick"),
  @JsonSubTypes.Type(value = Trigger.AfterTicks.class, name = "after_ticks"),
  @JsonSubTypes.Type(value = Trigger.UnitAtHex.class, name = "unit_at_hex"),
  @JsonSubTypes.Type(value = Trigger.ThresholdKills.class, name = "threshold_kills"),
  @JsonSubTypes.Type(value = Trigger.OutcomeSelected.class, name = "outcome_selected"),
  @JsonSubTypes.Type(value = Trigger.And.class, name = "and"),
  @JsonSubTypes.Type(value = Trigger.Or.class, name = "or"),
})
public sealed interface Trigger {

  /** 世界日到达（或已过）某天（{@code >=}，不是"恰在"；单位：日，2026-09-24 日制裁定）。 */
  record AtOrAfterTick(long tick) implements Trigger {

    public AtOrAfterTick {
      if (tick < 0) {
        throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
      }
    }
  }

  /** 自某参照点起再经过若干**天**（单位：日，2026-09-24 日制裁定）。 */
  record AfterTicks(long ticks) implements Trigger {

    public AfterTicks {
      if (ticks < 1) {
        throw new IllegalArgumentException("ticks 必须 ≥ 1: " + ticks);
      }
    }
  }

  /** 某单位位于某 hex。 */
  record UnitAtHex(UnitId unit, HexCoord hex) implements Trigger {

    public UnitAtHex {
      if (unit == null) {
        throw new IllegalArgumentException("unit 不得为 null");
      }
      if (hex == null) {
        throw new IllegalArgumentException("hex 不得为 null");
      }
    }
  }

  /** 累计击杀达到阈值。 */
  record ThresholdKills(int kills) implements Trigger {

    public ThresholdKills {
      if (kills < 1) {
        throw new IllegalArgumentException("kills 必须 ≥ 1: " + kills);
      }
    }
  }

  /** 某交战的结局已被选定。 */
  record OutcomeSelected(CombatId combat, CombatOutcomeId outcome) implements Trigger {

    public OutcomeSelected {
      if (combat == null) {
        throw new IllegalArgumentException("combat 不得为 null");
      }
      if (outcome == null) {
        throw new IllegalArgumentException("outcome 不得为 null");
      }
    }
  }

  /** 合取（全部满足）。**至少一项**（空 And 恒真、是拼写错误）。 */
  record And(List<Trigger> all) implements Trigger {

    public And {
      if (all == null) {
        throw new IllegalArgumentException("all 不得为 null");
      }
      if (all.isEmpty()) {
        throw new IllegalArgumentException("And 不得为空（空 And 恒真，属拼写错误）");
      }
      all = List.copyOf(all);
    }
  }

  /** 析取（任一满足）。**至少一项**（空 Or 恒假、是拼写错误）。 */
  record Or(List<Trigger> any) implements Trigger {

    public Or {
      if (any == null) {
        throw new IllegalArgumentException("any 不得为 null");
      }
      if (any.isEmpty()) {
        throw new IllegalArgumentException("Or 不得为空（空 Or 恒假，属拼写错误）");
      }
      any = List.copyOf(any);
    }
  }
}
