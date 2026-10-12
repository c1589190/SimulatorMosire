package io.mosire.simos.sd.change;

import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * sd 状态的变更集（spec §五.1）：**组件与 {@link SdState} 的 record 组件一一对应**（★★ 2026-10-12 批次 3a 起 <b>12
 * 个</b>，3b-1 起 <b>10 个</b>，3b-2 起 <b>8 个</b>，3c 起 <b>5 个</b>，3d-1 起 <b>4 个</b>，<b>本批 3d-2 起 1
 * 个</b>）。 收缩史与"本批是退役不是搬迁"见 {@link SdState} 的类注；本批删掉的三条 {@link FieldDelta} 是 {@code combats} /
 * {@code combatStates} / {@code lossRecords}（旧战斗族），它们<b>没有</b>搬进 {@code ArmyChangeSet} —— army 自阶段
 * D1 起就有自己的交战记录组件（{@code ArmyData.combats}），留着就是第二本交战账。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生，由 {@code SdRoundTripTest} 的反射枚举把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ 差异与重建的语义一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 map/social/unit
 * 共用同一份机制）。
 *
 * <p>★ 实现 util 的 {@link ChangeSet} 标记接口（M4 / spec §十）。
 */
public record SdChangeSet(FieldDelta<Effect> effects) implements ChangeSet {

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static SdChangeSet between(SdState base, SdState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new SdChangeSet(FieldDelta.diff(base.effects(), target.effects()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static SdState apply(SdChangeSet cs, SdState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SdState(FieldDelta.rebuild(base.effects(), cs.effects(), EffectId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !effects.changed();
  }
}
