package io.mosire.simos.gov.change;

import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovPortPolicy;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.Map;
import java.util.Objects;

/**
 * gov 切片的变更集（阶段 10a；Z2 扩为三组件、Z7c 扩为四组件、<b>R2 扩为五组件</b>，设计书 §4.1）。<b>组件与 {@link GovState} 的 record
 * 组件一一对应</b>： {@code offices} / {@code administrationPlans} / {@code budgetPolicies} / {@code
 * portPolicies} / {@code remittanceStates}。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@link #between(GovState, GovState)} 是唯一生产者，差异/重建语义一律委托 {@link
 * FieldDelta#diff} / {@link FieldDelta#rebuild}（与 Map/Social/Unit/Sd/Economy/Actor 共用同一份机制，
 * 不另写第二份）。
 *
 * <p>★★ <b>旧档缺键 ⇒ {@link FieldDelta.Unchanged}</b>（照 {@code ActorChangeSet} 的兼容口径）：升级前落盘的这条变更集
 * 没有新组件键时 Jackson 绑成 null，此处归一成“一字未动”，<b>不抛</b>——否则旧变更集会读不回来；方向是 fail-closed：旧档没提该组件，就是没动它。{@link
 * GovState} 的旧档兼容同理（缺键 ⇒ 空表/中性默认）。
 *
 * <p>★ {@link #applyTo(GovSnapshot, StateMeta)} 按 {@code ModuleCodec} 的 C28 契约把 <b>新的</b> {@code
 * ref} / {@code timestamp} 装上去（模块状态类型本身不带坐标）。
 */
public record GovChangeSet(
    FieldDelta<GovOfficeState> offices,
    FieldDelta<GovAdministrationPlan> administrationPlans,
    FieldDelta<GovBudgetPolicy> budgetPolicies,
    FieldDelta<GovPortPolicy> portPolicies,
    FieldDelta<GovRemittanceState> remittanceStates)
    implements ChangeSet {

  public GovChangeSet {
    if (offices == null) {
      offices = new FieldDelta.Unchanged<>(); // ★ 旧档缺键，见类注释
    }
    if (administrationPlans == null) {
      administrationPlans = new FieldDelta.Unchanged<>();
    }
    if (budgetPolicies == null) {
      budgetPolicies = new FieldDelta.Unchanged<>();
    }
    if (portPolicies == null) {
      portPolicies = new FieldDelta.Unchanged<>();
    }
    if (remittanceStates == null) {
      remittanceStates = new FieldDelta.Unchanged<>();
    }
  }

  /** 旧 1 参构造器（阶段 10a 的调用点/夹具）：四条源状态记“未动”。 */
  public GovChangeSet(FieldDelta<GovOfficeState> offices) {
    this(
        offices,
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>());
  }

  /** ★ 旧 4 参构造器（R2 之前的调用点/夹具）：口岸政策记“未动”。 */
  public GovChangeSet(
      FieldDelta<GovOfficeState> offices,
      FieldDelta<GovAdministrationPlan> administrationPlans,
      FieldDelta<GovBudgetPolicy> budgetPolicies,
      FieldDelta<GovRemittanceState> remittanceStates) {
    this(
        offices,
        administrationPlans,
        budgetPolicies,
        new FieldDelta.Unchanged<>(),
        remittanceStates);
  }

  /** ★ 旧 3 参构造器（Z7c 之前的调用点/夹具）：口岸政策与 remittance 周期账记“未动”。 */
  public GovChangeSet(
      FieldDelta<GovOfficeState> offices,
      FieldDelta<GovAdministrationPlan> administrationPlans,
      FieldDelta<GovBudgetPolicy> budgetPolicies) {
    this(
        offices,
        administrationPlans,
        budgetPolicies,
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>());
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static GovChangeSet between(GovState base, GovState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new GovChangeSet(
        FieldDelta.diff(base.offices(), target.offices()),
        FieldDelta.diff(base.administrationPlans(), target.administrationPlans()),
        FieldDelta.diff(base.budgetPolicies(), target.budgetPolicies()),
        FieldDelta.diff(base.portPolicies(), target.portPolicies()),
        FieldDelta.diff(base.remittanceStates(), target.remittanceStates()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static GovState apply(GovChangeSet cs, GovState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    Map<UnitId, GovOfficeState> offices =
        FieldDelta.rebuild(base.offices(), cs.offices(), UnitId::parse);
    Map<UnitId, GovAdministrationPlan> administrationPlans =
        FieldDelta.rebuild(base.administrationPlans(), cs.administrationPlans(), UnitId::parse);
    Map<UnitId, GovBudgetPolicy> budgetPolicies =
        FieldDelta.rebuild(base.budgetPolicies(), cs.budgetPolicies(), UnitId::parse);
    Map<UnitId, GovPortPolicy> portPolicies =
        FieldDelta.rebuild(base.portPolicies(), cs.portPolicies(), UnitId::parse);
    Map<UnitId, GovRemittanceState> remittanceStates =
        FieldDelta.rebuild(base.remittanceStates(), cs.remittanceStates(), UnitId::parse);
    return new GovState(
        offices, administrationPlans, budgetPolicies, portPolicies, remittanceStates);
  }

  /**
   * 把本变更集施加到快照上，返回<b>新</b>快照：状态走 {@link #apply}，{@code ref}/{@code timestamp} 取自 {@code
   * newMeta}（C28：新坐标由 Core 告诉 codec，不照抄 base 的陈旧坐标）。
   */
  public GovSnapshot applyTo(GovSnapshot base, StateMeta newMeta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newMeta, "newMeta");
    return new GovSnapshot(newMeta.ref(), newMeta.timestamp(), apply(this, base.state()));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !offices.changed()
        && !administrationPlans.changed()
        && !budgetPolicies.changed()
        && !portPolicies.changed()
        && !remittanceStates.changed();
  }
}
