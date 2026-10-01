package io.mosire.simos.army.change;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.Objects;

/**
 * army 切片的变更集（阶段 D1 / 用户设计 D-012，2026-10-02）。<b>组件与 {@link ArmyData} 的 record 组件一一对应</b>：当前只有
 * {@code combats} 一片。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@link #between(ArmyData, ArmyData)} 是唯一生产者，差异/重建语义一律委托 {@link
 * FieldDelta#diff} / {@link FieldDelta#rebuild}（与 Map/Social/Unit/Sd/Economy/Actor/Gov
 * 共用同一份机制，不另写第二份）。
 *
 * <p>★★ <b>旧档缺键 ⇒ {@link FieldDelta.Unchanged}</b>（照 {@code GovChangeSet}/{@code ActorChangeSet}
 * 的兼容口径）：升级前落盘的 这条变更集没有 {@code combats} 键时 Jackson 绑成 null，此处归一成"一字未动"，<b>不抛</b>；方向是
 * fail-closed：旧档没提该组件，就是没动它。
 *
 * <p>★ {@link #applyTo(ArmySnapshot, StateMeta)} 按 {@code ModuleCodec} 的 C28 契约把<b>新的</b> {@code
 * ref} / {@code timestamp} 装上去（模块状态类型本身不带坐标）。
 */
public record ArmyChangeSet(FieldDelta<CombatRecord> combats) implements ChangeSet {

  public ArmyChangeSet {
    if (combats == null) {
      combats = new FieldDelta.Unchanged<>(); // ★ 旧档缺键，见类注释
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static ArmyChangeSet between(ArmyData base, ArmyData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new ArmyChangeSet(FieldDelta.diff(base.combats(), target.combats()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static ArmyData apply(ArmyChangeSet cs, ArmyData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new ArmyData(FieldDelta.rebuild(base.combats(), cs.combats(), CombatRecordId::parse));
  }

  /**
   * 把本变更集施加到快照上，返回<b>新</b>快照：状态走 {@link #apply}，{@code ref}/{@code timestamp} 取自 {@code
   * newMeta}（C28：新坐标由 Core 告诉 codec，不照抄 base 的陈旧坐标）。
   */
  public ArmySnapshot applyTo(ArmySnapshot base, StateMeta newMeta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newMeta, "newMeta");
    return new ArmySnapshot(newMeta.ref(), newMeta.timestamp(), apply(this, base.data()));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !combats.changed();
  }
}
