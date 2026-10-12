package io.mosire.simos.ga.effect;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.map.hex.HexCoord;
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
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：两档战斗耦合的触发条件随旧战斗族退役（如实记）</b>：
 *
 * <ul>
 *   <li>{@code OutcomeSelected(CombatId, CombatOutcomeId)} —— 它引的 <b>两个 id 类型都是 sd 自己的战斗 id</b>，且判据
 *       {@code CombatState.selectedOutcome}（旧战斗族的第二张表）。两者本批一起删除 ⇒ 该档<b>在编译期已不可能保留</b>；
 *   <li>{@code ThresholdKills(int kills)} —— 数值本身不引任何战斗类型，但它的<b>求值读 {@code
 *       SdState.lossRecords}</b>（旧战斗族的第三张 表：{@code TriggerEvaluator.cumulativeKills}
 *       把全体损失记录里的人员损失绝对值求和）。该表退役 ⇒ 这个条件<b>没有事实来源</b>； 留着就只有两条路：① 让它读 army 的记录（新开 {@code sd → army}
 *       边，enforcer 当场拒）；② 让它恒假（静默失效，比删更坏）。故一并退役。
 * </ul>
 *
 * <p>★ <b>存活档（本批一个字未改语义）</b>：{@code AtOrAfterTick} / {@code AfterTicks} / {@code UnitAtHex} /
 * {@code And} / {@code Or}。
 *
 * <p>★ <b>线格式的后果（如实记）</b>：{@code @class} 里的 {@code threshold_kills} / {@code outcome_selected}
 * 两个取值不再被认领（Jackson 对未知子类型抛 ⇒ 该载荷在 {@code ga.RegisterEffect} 的命令期被<b>具名拒</b>，不是静默当假）。 这是 retired
 * 能力的预期失败方向，不是兼容缺口（§一.11：Alpha 不背旧档）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类型自 {@code io.mosire.simos.sd.model} <b>整族迁入本包</b></b>（用户裁定「<b>sd
 * 整个弃用！</b>」）：<b>{@code @class} 取值、五档存活的语义、四条构造期判据与线格式一字未改</b>。★ 本档是 <b>{@code ga → unit} + {@code
 * ga → map} 两条新依赖边的唯一依据</b>——{@code UnitAtHex(UnitId, HexCoord)}
 * 的类型本身就来自那两个模块（用户原话：「<b>那让它依赖unit呗，这不是Army模块管理的吗？照道理来说Army和Nation都得依赖Unit作为地图实体</b>」）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Trigger.AtOrAfterTick.class, name = "at_or_after_tick"),
  @JsonSubTypes.Type(value = Trigger.AfterTicks.class, name = "after_ticks"),
  @JsonSubTypes.Type(value = Trigger.UnitAtHex.class, name = "unit_at_hex"),
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
