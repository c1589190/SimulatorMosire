package io.mosire.simos.gov.change;

import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.DiplomaticEvent;
import io.mosire.simos.gov.nation.DiplomaticEventId;
import io.mosire.simos.gov.nation.DiplomaticRelation;
import io.mosire.simos.gov.nation.DiplomaticRelationKey;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.StateMeta;
import java.util.Map;
import java.util.Objects;

/**
 * gov 切片的变更集（阶段 10a；Z2 扩为三组件、Z7c 扩为四组件、R2 扩为五组件；★ 追加裁定 12（2026-10-12 §21.9 D-1） 收为两组件；<b>★★
 * 2026-10-12 批次 3c 再扩为五组件</b>）。<b>组件与 {@link GovState} 的 record 组件一一对应</b>： {@code offices} /
 * {@code remittanceStates} / {@code nations} / {@code diplomaticRelations} / {@code
 * diplomaticEvents}。
 *
 * <p>★★★ <b>三条政策源状态（{@code administrationPlans} / {@code budgetPolicies} / {@code portPolicies}）的
 * {@link FieldDelta} 已整段删除</b>：政策不再住本切片，改挂 {@code EconomyData.governments} 的 {@code Government}
 * 记录（写口 = {@code economy.SetGovernmentPolicy}）⇒ 对非 root 节点写政策<b>不再经过本变更集</b>。政策那一半的铁律 5 往返由 {@code
 * EconomyChangeSet.governments} 承担（同一条既有通道：只有 {@code Government} 记录的字段值在变）。
 *
 * <p>★★★ <b>乙收尾（本批）再扩为七组件</b>：{@code nationAffiliations}（决策人 → 国家）与 {@code govAffiliations}（决策人 →
 * GOV 单位）两条 {@link FieldDelta} 放<b>末位</b>（既有五个键的线格式次序一字未动）。它们的键类型是 {@code
 * util.spi.DecisionMakerId}（唯一键读侧 {@code DecisionMakerId::parse}）。
 *
 * <p>★★ <b>3c 新增的三条 {@link FieldDelta} 是外交三表</b>（自 {@code SdChangeSet} 整段迁入，语义一字未改）： {@code
 * nations}（键 {@code NationId}）/ {@code diplomaticRelations}（复合键）/ {@code diplomaticEvents}。
 *
 * <p>★ 铁律 5：变更集从完整状态类型派生——{@link #between(GovState, GovState)} 是唯一生产者，差异/重建语义一律委托 {@link
 * FieldDelta#diff} / {@link FieldDelta#rebuild}（与 Map/Social/Unit/Sd/Economy/Actor 共用同一份机制，
 * 不另写第二份）。
 *
 * <p>★★ <b>旧档缺键 ⇒ {@link FieldDelta.Unchanged}</b>（照 {@code ActorChangeSet} 的兼容口径）：升级前落盘的这条变更集
 * 没有新组件键时 Jackson 绑成 null，此处归一成“一字未动”，<b>不抛</b>——否则旧变更集会读不回来；方向是 fail-closed：旧档没提该组件，就是没动它。
 *
 * <p>★ {@link #applyTo(GovSnapshot, StateMeta)} 按 {@code ModuleCodec} 的 C28 契约把 <b>新的</b> {@code
 * ref} / {@code timestamp} 装上去（模块状态类型本身不带坐标）。
 */
public record GovChangeSet(
    FieldDelta<GovOfficeState> offices,
    FieldDelta<GovRemittanceState> remittanceStates,
    FieldDelta<Nation> nations,
    FieldDelta<DiplomaticRelation> diplomaticRelations,
    FieldDelta<DiplomaticEvent> diplomaticEvents,
    FieldDelta<NationId> nationAffiliations,
    FieldDelta<UnitId> govAffiliations)
    implements ChangeSet {

  public GovChangeSet {
    if (offices == null) {
      offices = new FieldDelta.Unchanged<>(); // ★ 旧档缺键，见类注释
    }
    if (remittanceStates == null) {
      remittanceStates = new FieldDelta.Unchanged<>();
    }
    if (nations == null) {
      nations = new FieldDelta.Unchanged<>();
    }
    if (diplomaticRelations == null) {
      diplomaticRelations = new FieldDelta.Unchanged<>();
    }
    if (diplomaticEvents == null) {
      diplomaticEvents = new FieldDelta.Unchanged<>();
    }
    if (nationAffiliations == null) {
      nationAffiliations = new FieldDelta.Unchanged<>();
    }
    if (govAffiliations == null) {
      govAffiliations = new FieldDelta.Unchanged<>();
    }
  }

  /** 只给 offices 的形状（阶段 10a 的调用点/夹具）：其余六张表记“未动”。 */
  public GovChangeSet(FieldDelta<GovOfficeState> offices) {
    this(
        offices,
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>());
  }

  /** 逐组件比较。全相等 ⇒ <b>全 Unchanged</b>（不是空对象）。 */
  public static GovChangeSet between(GovState base, GovState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new GovChangeSet(
        FieldDelta.diff(base.offices(), target.offices()),
        FieldDelta.diff(base.remittanceStates(), target.remittanceStates()),
        FieldDelta.diff(base.nations(), target.nations()),
        FieldDelta.diff(base.diplomaticRelations(), target.diplomaticRelations()),
        FieldDelta.diff(base.diplomaticEvents(), target.diplomaticEvents()),
        FieldDelta.diff(base.nationAffiliations(), target.nationAffiliations()),
        FieldDelta.diff(base.govAffiliations(), target.govAffiliations()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static GovState apply(GovChangeSet cs, GovState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    Map<UnitId, GovOfficeState> offices =
        FieldDelta.rebuild(base.offices(), cs.offices(), UnitId::parse);
    Map<UnitId, GovRemittanceState> remittanceStates =
        FieldDelta.rebuild(base.remittanceStates(), cs.remittanceStates(), UnitId::parse);
    // ★ 键读侧（toString → parse）的三个解析器与 GovCodec 的键反序列化器同源（同一个类型的那个 parse）：
    //   变更集 key 与 JSON key 走的是同一把尺，此处漏一个 ⇒ 该表一旦非空就往返不成立。
    Map<NationId, Nation> nations =
        FieldDelta.rebuild(base.nations(), cs.nations(), NationId::parse);
    Map<DiplomaticRelationKey, DiplomaticRelation> diplomaticRelations =
        FieldDelta.rebuild(
            base.diplomaticRelations(), cs.diplomaticRelations(), DiplomaticRelationKey::parse);
    Map<DiplomaticEventId, DiplomaticEvent> diplomaticEvents =
        FieldDelta.rebuild(
            base.diplomaticEvents(), cs.diplomaticEvents(), DiplomaticEventId::parse);
    // ★ 归属两表的键读侧（DecisionMakerId.parse）与 GovCodec 的键反序列化器同源：漏一个 ⇒ 该表一旦非空就往返不成立。
    Map<DecisionMakerId, NationId> nationAffiliations =
        FieldDelta.rebuild(
            base.nationAffiliations(), cs.nationAffiliations(), DecisionMakerId::parse);
    Map<DecisionMakerId, UnitId> govAffiliations =
        FieldDelta.rebuild(base.govAffiliations(), cs.govAffiliations(), DecisionMakerId::parse);
    return new GovState(
        offices,
        remittanceStates,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
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
        && !remittanceStates.changed()
        && !nations.changed()
        && !diplomaticRelations.changed()
        && !diplomaticEvents.changed()
        && !nationAffiliations.changed()
        && !govAffiliations.changed();
  }
}
