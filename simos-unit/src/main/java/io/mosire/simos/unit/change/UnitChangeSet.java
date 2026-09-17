package io.mosire.simos.unit.change;

import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * 单位状态的变更集。**组件与 {@link UnitState} 的 record 组件一一对应**（当前 1 个）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code UnitRoundTripTest} 的反射枚举把守——新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ 差异与重建的语义不在这里：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★ **不实现 util 的 {@code ChangeSet} 接口**（C8）：版本戳属 Revision 层，照 M2/M3-social 先例。
 */
public record UnitChangeSet(FieldDelta<Unit> units) {

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static UnitChangeSet between(UnitState base, UnitState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new UnitChangeSet(FieldDelta.diff(base.units(), target.units()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static UnitState apply(UnitChangeSet cs, UnitState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new UnitState(FieldDelta.rebuild(base.units(), cs.units(), UnitId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !units.changed();
  }
}
