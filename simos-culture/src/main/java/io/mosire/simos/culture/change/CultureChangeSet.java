package io.mosire.simos.culture.change;

import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>culture 切片的变更集</b>（D-031 spec §13 Z1；铁律 5）：组件与 {@link CultureData} 的 record 组件一一对应。
 *
 * <p>★★ <b>D-032（D2 直接删）：{@code aptitudeRules} 组件已删除</b>（"文化 × 技能 → 生产效率倍率"的表）。 本模块现在只有一个组件：
 *
 * <ul>
 *   <li>{@code householdCultures}：家户文化标签表（key = {@link HouseholdId#parse}）。
 * </ul>
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@code between(base, target)} 是唯一生产者，差异/重建语义一律委托 {@link FieldDelta#diff}
 * / {@link FieldDelta#rebuild}（与 Map/Social/Unit/Economy/Actor/Gov/Production 共用同一份机制，不另写第二份）。
 *
 * <p>★ <b>缺键（null）⇒ {@link FieldDelta.Unchanged}</b>：Jackson 对缺失组件绑成 null 时归一成"一字未动"，不抛。 这是 JSON
 * 绑定容忍，<b>不是</b>旧档兼容位——本 namespace 没有旧档（AGENTS §一.11）。
 *
 * <p>★ {@code isEmpty()} 是派生判断、不是状态组件 ⇒ 不进线格式（共享 {@code SimosObjectMapper} 已全局摘除； 本模块 codec
 * 另加更具体的同一句话）。
 */
public record CultureChangeSet(FieldDelta<HouseholdCulture> householdCultures)
    implements ChangeSet {

  public CultureChangeSet {
    if (householdCultures == null) {
      householdCultures = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static CultureChangeSet between(CultureData base, CultureData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new CultureChangeSet(
        FieldDelta.diff(base.householdCultures(), target.householdCultures()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static CultureData apply(CultureChangeSet changeSet, CultureData base) {
    Objects.requireNonNull(changeSet, "changeSet");
    Objects.requireNonNull(base, "base");
    // ★ 键读侧（toString → parse）与 CultureCodec 的键反序列化器同源：同一个类型的那个 parse。
    Map<HouseholdId, HouseholdCulture> householdCultures =
        FieldDelta.rebuild(
            base.householdCultures(), changeSet.householdCultures(), HouseholdId::parse);
    return new CultureData(householdCultures);
  }

  /**
   * 把本变更集施加到快照上，返回<b>新</b>快照：状态走 {@link #apply}，{@code ref}/{@code timestamp} 取自 {@code
   * newMeta}（C28：新坐标由 Core 告诉 codec，不照抄 base 的陈旧坐标）。
   */
  public CultureSnapshot applyTo(CultureSnapshot base, StateMeta newMeta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newMeta, "newMeta");
    return new CultureSnapshot(newMeta.ref(), newMeta.timestamp(), apply(this, base.state()));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !householdCultures.changed();
  }
}
