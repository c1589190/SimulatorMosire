package io.mosire.simos.sd.change;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * sd 状态的变更集（spec §五.1）：**组件与 {@link SdState} 的 record 组件一一对应**（当前 12 个；D5 / R6 追加 {@code
 * diplomaticRelations} 与 {@code diplomaticEvents}）。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生，由 {@code SdRoundTripTest} 的反射枚举把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ 差异与重建的语义一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 map/social/unit
 * 共用同一份机制）。
 *
 * <p>★ **{@code info} 组件**：{@code SdState.info} 是 {@code Map<String, List<SdInfoEntry>>}（键 =
 * canonical 地址串，见 {@link SdState} 的类注），故差异值为 {@code List<SdInfoEntry>}，重建时键解析是恒等（String → String）。
 *
 * <p>★ 实现 util 的 {@link ChangeSet} 标记接口（M4 / spec §十）。
 */
public record SdChangeSet(
    FieldDelta<Nation> nations,
    FieldDelta<Army> armies,
    FieldDelta<Combat> combats,
    FieldDelta<CombatState> combatStates,
    FieldDelta<DecisionMaker> decisionMakers,
    FieldDelta<Directive> directives,
    FieldDelta<Effect> effects,
    FieldDelta<Verdict> verdicts,
    FieldDelta<LossRecord> lossRecords,
    FieldDelta<List<SdInfoEntry>> info,
    FieldDelta<DiplomaticRelation> diplomaticRelations,
    FieldDelta<DiplomaticEvent> diplomaticEvents)
    implements ChangeSet {

  /**
   * ★ **旧档兼容**（与 {@code EconomyChangeSet} 同口径）：D5 之前落盘的变更集字节没有最后两个键 ⇒ Jackson 绑 null ⇒ 缺省 = {@link
   * FieldDelta.Unchanged}（"旧档没提该组件，就是没动它"）。读成 null 会让 {@link #isEmpty()} 与 {@link #apply} 当场
   * NPE；方向必须落在 fail-closed 一侧。
   */
  public SdChangeSet {
    if (diplomaticRelations == null) {
      diplomaticRelations = new FieldDelta.Unchanged<>();
    }
    if (diplomaticEvents == null) {
      diplomaticEvents = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static SdChangeSet between(SdState base, SdState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new SdChangeSet(
        FieldDelta.diff(base.nations(), target.nations()),
        FieldDelta.diff(base.armies(), target.armies()),
        FieldDelta.diff(base.combats(), target.combats()),
        FieldDelta.diff(base.combatStates(), target.combatStates()),
        FieldDelta.diff(base.decisionMakers(), target.decisionMakers()),
        FieldDelta.diff(base.directives(), target.directives()),
        FieldDelta.diff(base.effects(), target.effects()),
        FieldDelta.diff(base.verdicts(), target.verdicts()),
        FieldDelta.diff(base.lossRecords(), target.lossRecords()),
        FieldDelta.diff(base.info(), target.info()),
        FieldDelta.diff(base.diplomaticRelations(), target.diplomaticRelations()),
        FieldDelta.diff(base.diplomaticEvents(), target.diplomaticEvents()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static SdState apply(SdChangeSet cs, SdState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SdState(
        FieldDelta.rebuild(base.nations(), cs.nations(), NationId::parse),
        FieldDelta.rebuild(base.armies(), cs.armies(), ArmyId::parse),
        FieldDelta.rebuild(base.combats(), cs.combats(), CombatId::parse),
        FieldDelta.rebuild(base.combatStates(), cs.combatStates(), CombatStateId::parse),
        FieldDelta.rebuild(base.decisionMakers(), cs.decisionMakers(), DecisionMakerId::parse),
        FieldDelta.rebuild(base.directives(), cs.directives(), DirectiveId::parse),
        FieldDelta.rebuild(base.effects(), cs.effects(), EffectId::parse),
        FieldDelta.rebuild(base.verdicts(), cs.verdicts(), VerdictId::parse),
        FieldDelta.rebuild(base.lossRecords(), cs.lossRecords(), LossRecordId::parse),
        FieldDelta.rebuild(base.info(), cs.info(), Function.identity()),
        FieldDelta.rebuild(
            base.diplomaticRelations(), cs.diplomaticRelations(), DiplomaticRelationKey::parse),
        FieldDelta.rebuild(
            base.diplomaticEvents(), cs.diplomaticEvents(), DiplomaticEventId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !nations.changed()
        && !armies.changed()
        && !combats.changed()
        && !combatStates.changed()
        && !decisionMakers.changed()
        && !directives.changed()
        && !effects.changed()
        && !verdicts.changed()
        && !lossRecords.changed()
        && !info.changed()
        && !diplomaticRelations.changed()
        && !diplomaticEvents.changed();
  }
}
