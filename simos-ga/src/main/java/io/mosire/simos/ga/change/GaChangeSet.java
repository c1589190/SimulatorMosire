package io.mosire.simos.ga.change;

import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * ★★ <b>ga 切片的变更集</b>（2026-10-12 批次 3a 首建两组件；同批后续收为四组件；批次 3b-2 <b>再收为六组件</b>）。<b>组件与 {@link
 * GaState} 的 record 组件一一对应</b>： {@code decisionPackets} / {@code mergedEffectPlans} / {@code
 * directives} / {@code verdicts} / {@code decisionMakers} / {@code info}（恰六个）。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@link #between(GaState, GaState)} 是唯一生产者，差异/重建语义一律委托 {@link
 * FieldDelta#diff} / {@link FieldDelta#rebuild}（与 Map/Social/Unit/Sd/Economy/Actor/Gov 共用同一份机制，
 * 不另写第二份）。判别力由 {@code GaRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ <b>旧档缺键 ⇒ {@link FieldDelta.Unchanged}</b>（照 {@code GovChangeSet} / {@code ActorChangeSet}
 * 的 fail-closed 读法）：没提该组件 = 没动它，<b>不抛</b>；重建时 {@code Unchanged} 直接返回 base、连键解析器都不会被调用。
 *
 * <p>★ <b>{@code info} 组件</b>：{@code GaState.info} 是 {@code Map<String, List<SdInfoEntry>>}（键 =
 * canonical 地址串，见 {@link GaState} 的类注），故差异值为 {@code List<SdInfoEntry>}，重建时键解析是恒等（String → String）。
 *
 * <p>★ {@link #applyTo(GaSnapshot, StateMeta)} 按 {@code ModuleCodec} 的 C28 契约把 <b>新的</b> {@code
 * ref} / {@code timestamp} 装上去（模块状态类型本身不带坐标）。
 */
public record GaChangeSet(
    FieldDelta<DecisionPacket> decisionPackets,
    FieldDelta<MergedEffectPlan> mergedEffectPlans,
    FieldDelta<Directive> directives,
    FieldDelta<Verdict> verdicts,
    FieldDelta<DecisionMaker> decisionMakers,
    FieldDelta<List<SdInfoEntry>> info)
    implements ChangeSet {

  public GaChangeSet {
    if (decisionPackets == null) {
      decisionPackets = new FieldDelta.Unchanged<>(); // ★ 缺键，见类注释
    }
    if (mergedEffectPlans == null) {
      mergedEffectPlans = new FieldDelta.Unchanged<>();
    }
    if (directives == null) {
      directives = new FieldDelta.Unchanged<>();
    }
    if (verdicts == null) {
      verdicts = new FieldDelta.Unchanged<>();
    }
    if (decisionMakers == null) {
      decisionMakers = new FieldDelta.Unchanged<>();
    }
    if (info == null) {
      info = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static GaChangeSet between(GaState base, GaState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new GaChangeSet(
        FieldDelta.diff(base.decisionPackets(), target.decisionPackets()),
        FieldDelta.diff(base.mergedEffectPlans(), target.mergedEffectPlans()),
        FieldDelta.diff(base.directives(), target.directives()),
        FieldDelta.diff(base.verdicts(), target.verdicts()),
        FieldDelta.diff(base.decisionMakers(), target.decisionMakers()),
        FieldDelta.diff(base.info(), target.info()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static GaState apply(GaChangeSet cs, GaState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    Map<DecisionPacketId, DecisionPacket> decisionPackets =
        FieldDelta.rebuild(base.decisionPackets(), cs.decisionPackets(), DecisionPacketId::parse);
    Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans =
        FieldDelta.rebuild(
            base.mergedEffectPlans(), cs.mergedEffectPlans(), MergedEffectPlanId::parse);
    Map<DirectiveId, Directive> directives =
        FieldDelta.rebuild(base.directives(), cs.directives(), DirectiveId::parse);
    Map<VerdictId, Verdict> verdicts =
        FieldDelta.rebuild(base.verdicts(), cs.verdicts(), VerdictId::parse);
    Map<DecisionMakerId, DecisionMaker> decisionMakers =
        FieldDelta.rebuild(base.decisionMakers(), cs.decisionMakers(), DecisionMakerId::parse);
    Map<String, List<SdInfoEntry>> info =
        FieldDelta.rebuild(base.info(), cs.info(), Function.identity());
    return new GaState(
        decisionPackets, mergedEffectPlans, directives, verdicts, decisionMakers, info);
  }

  /**
   * 把本变更集施加到快照上，返回<b>新</b>快照：状态走 {@link #apply}，{@code ref}/{@code timestamp} 取自 {@code
   * newMeta}（C28：新坐标由 Core 告诉 codec，不照抄 base 的陈旧坐标）。
   */
  public GaSnapshot applyTo(GaSnapshot base, StateMeta newMeta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newMeta, "newMeta");
    return new GaSnapshot(newMeta.ref(), newMeta.timestamp(), apply(this, base.state()));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !decisionPackets.changed()
        && !mergedEffectPlans.changed()
        && !directives.changed()
        && !verdicts.changed()
        && !decisionMakers.changed()
        && !info.changed();
  }
}
