package io.mosire.simos.production.change;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>production 切片的变更集</b>（D-031 P1 起含定义；<b>D-032 起含实例/经验/解锁/效率/覆盖</b>；铁律 5）： 组件与 {@link
 * ProductionData} 的 record 组件<b>一一对应</b>（industries / modes / instances / experienceTracks /
 * householdExperience / unlocks / efficiency / outputQuantityOverrides）。
 *
 * <p>★★ <b>由 production 自己从完整本域候选态派生</b>（边界裁决 §2 末条原文：「具体 {@code ProductionChangeSet} 仍由 production
 * 自己从完整本域候选态派生，economy 不必越过包边界自行构造它」）：economy 只拿 {@code
 * production.contract.ProductionRuntime#changeSet()} 交出的 {@link ChangeSet} 原样汇入同一次提案。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@code between(base, target)} 是唯一生产者，差异/重建语义一律委托 {@link FieldDelta#diff}
 * / {@link FieldDelta#rebuild}。★ D1 的 {@code roles}/{@code roleShareRemainderRule}/{@code
 * upgradesTo}/{@code mode} 全部是 {@link IndustryDefinitionView} 的 record 组件：整值 equals 即逐字段比较，故 {@code
 * industries} 维无需第二套 diff。
 *
 * <p>★★ <b>A′ 批的结转状态全在这 8 个组件之内</b>（§13.8 末段）：经验侧的结转 = {@code householdExperience} 的值 {@link
 * ProductionExperienceFeature#remainder()}（键 = 家户 × 经验条）；实例侧的结转 = {@code instances} 里的逐家户加权劳动与
 * {@code efficiency} 的三段余数。⇒ 本记录<b>没有</b>新增组件， 组件数仍为 8（与 {@link ProductionData} 的 record 组件一一对应）。
 *
 * <p>★ <b>缺键（null）⇒ {@link FieldDelta.Unchanged}</b>：Jackson 对缺失组件绑成 null 时归一成"一字未动"，不抛。
 *
 * <p>★ {@code isEmpty()} 是派生判断、不是状态组件 ⇒ 不进线格式（类型级 {@code @JsonIgnore}；另留共享 mapper/codec mixin
 * 同款规则）。
 */
public record ProductionChangeSet(
    FieldDelta<IndustryDefinitionView> industries,
    FieldDelta<ProductionModeDefinitionView> modes,
    FieldDelta<ProductionProcess> instances,
    FieldDelta<ExperienceTrack> experienceTracks,
    FieldDelta<ProductionExperienceFeature> householdExperience,
    FieldDelta<Set<IndustryId>> unlocks,
    FieldDelta<ProductionEfficiencyState> efficiency,
    FieldDelta<Map<CommodityId, Long>> outputQuantityOverrides)
    implements ChangeSet {

  public ProductionChangeSet {
    if (industries == null) {
      industries = new FieldDelta.Unchanged<>();
    }
    if (modes == null) {
      modes = new FieldDelta.Unchanged<>();
    }
    if (instances == null) {
      instances = new FieldDelta.Unchanged<>();
    }
    if (experienceTracks == null) {
      experienceTracks = new FieldDelta.Unchanged<>();
    }
    if (householdExperience == null) {
      householdExperience = new FieldDelta.Unchanged<>();
    }
    if (unlocks == null) {
      unlocks = new FieldDelta.Unchanged<>();
    }
    if (efficiency == null) {
      efficiency = new FieldDelta.Unchanged<>();
    }
    if (outputQuantityOverrides == null) {
      outputQuantityOverrides = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static ProductionChangeSet between(ProductionData base, ProductionData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new ProductionChangeSet(
        FieldDelta.diff(base.industries(), target.industries()),
        FieldDelta.diff(base.modes(), target.modes()),
        FieldDelta.diff(base.instances(), target.instances()),
        FieldDelta.diff(base.experienceTracks(), target.experienceTracks()),
        FieldDelta.diff(base.householdExperience(), target.householdExperience()),
        FieldDelta.diff(base.unlocks(), target.unlocks()),
        FieldDelta.diff(base.efficiency(), target.efficiency()),
        FieldDelta.diff(base.outputQuantityOverrides(), target.outputQuantityOverrides()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static ProductionData apply(ProductionChangeSet changeSet, ProductionData base) {
    Objects.requireNonNull(changeSet, "changeSet");
    Objects.requireNonNull(base, "base");
    // ★ 键读侧（toString → parse）与 ProductionCodec 的键反序列化器同源：同一个类型的那个 parse。
    Map<IndustryId, IndustryDefinitionView> industries =
        FieldDelta.rebuild(base.industries(), changeSet.industries(), IndustryId::parse);
    Map<ProductionModeId, ProductionModeDefinitionView> modes =
        FieldDelta.rebuild(base.modes(), changeSet.modes(), ProductionModeId::parse);
    Map<ProductionUnitId, ProductionProcess> instances =
        FieldDelta.rebuild(base.instances(), changeSet.instances(), ProductionUnitId::parse);
    Map<ExperienceTrackId, ExperienceTrack> experienceTracks =
        FieldDelta.rebuild(
            base.experienceTracks(), changeSet.experienceTracks(), ExperienceTrackId::parse);
    Map<HouseholdExperienceKey, ProductionExperienceFeature> householdExperience =
        FieldDelta.rebuild(
            base.householdExperience(),
            changeSet.householdExperience(),
            HouseholdExperienceKey::parse);
    Map<HouseholdId, Set<IndustryId>> unlocks =
        FieldDelta.rebuild(base.unlocks(), changeSet.unlocks(), HouseholdId::parse);
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency =
        FieldDelta.rebuild(base.efficiency(), changeSet.efficiency(), ProductionUnitId::parse);
    Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides =
        FieldDelta.rebuild(
            base.outputQuantityOverrides(), changeSet.outputQuantityOverrides(), IndustryId::parse);
    return new ProductionData(
        industries,
        modes,
        instances,
        experienceTracks,
        householdExperience,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /**
   * 把本变更集施加到快照上，返回<b>新</b>快照：状态走 {@link #apply}，{@code ref}/{@code timestamp} 取自 {@code
   * newMeta}（C28）。
   */
  public ProductionSnapshot applyTo(ProductionSnapshot base, StateMeta newMeta) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(newMeta, "newMeta");
    return new ProductionSnapshot(newMeta.ref(), newMeta.timestamp(), apply(this, base.state()));
  }

  /** 是否所有组件都未变。★ 派生判断、不是状态组件 ⇒ 类型级 @JsonIgnore（裸 mapper/Timeline 往返也成立）。 */
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return !industries.changed()
        && !modes.changed()
        && !instances.changed()
        && !experienceTracks.changed()
        && !householdExperience.changed()
        && !unlocks.changed()
        && !efficiency.changed()
        && !outputQuantityOverrides.changed();
  }
}
