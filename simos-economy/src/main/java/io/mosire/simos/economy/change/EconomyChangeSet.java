package io.mosire.simos.economy.change;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.UseRightId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.UseRight;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 经济状态的变更集。**组件与 {@link EconomyData} 的 record 组件一一对应**（当前 13 个：{@code meta} / {@code industries} /
 * {@code classes} / {@code debts} / {@code flows} / {@code laborSupply} / {@code allocations} /
 * {@code relations} / {@code markets} / {@code shipments} / {@code memberships} / {@code useRights}
 * / {@code operatorConditions}）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code EconomyRoundTripTest} 的**反射枚举**把守——新增状态组件若不进 变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} / {@code
 * LedgerChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★★ **{@code meta} 是单值组件，用"单键表"投影进同一份机制**：{@code FieldDelta} 是对**表**的差异（键 → 值），而 {@code meta} 是
 * {@code Optional<EconomyMeta>}。若为它另写一份"单值差异"机制，就有了与 {@code FieldDelta} 分叉的第二份实现。 故把 {@code
 * Optional} 投影成至多一行的表（键 = {@link #META_KEY}），diff/rebuild 全走既有机制，再投影回 {@code Optional}。
 * 语义是纯的：{@code 空 → 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值 → 另一个值} = {@code
 * Upsert}。同 {@code LedgerChangeSet.economyMeta} 的键选择，记入说明。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十）：该接口已收窄为**标记接口**，实现它不带来任何新义务。
 *
 * <p>★ **R2 的两个新组件走同一份机制**（{@code laborSupply} = 每批次有多少可支配劳动、{@code allocations} = 每批次把多少给了谁）：
 * 它们是普通的"键 → 值"表，故 diff/rebuild 一字不用改——rebuild 的键解析器 = 各自的 {@code parse} （{@link PeopleLotId#parse}
 * / {@link LaborAllocationId#parse}），与 {@link CohortKey} 一族同款。
 *
 * <p>★ **T2 的第 8 个组件同款**（{@code relations} = 每个产业一次生产的结算规则）：键 = {@code IndustryId} （已有键反序列化器），值 =
 * {@link ProductionRelation}（**深层**带一个 sealed 多态类型 {@code Recipient} ⇒ 它的 线格式由类型上的 Jackson
 * 注解把守，见那个接口的类注；此处无须有任何分支）。
 */
public record EconomyChangeSet(
    FieldDelta<EconomyMeta> meta,
    FieldDelta<Industry> industries,
    FieldDelta<ClassRow> classes,
    FieldDelta<Debt> debts,
    FieldDelta<FlowRow> flows,
    FieldDelta<LaborSupply> laborSupply,
    FieldDelta<LaborAllocation> allocations,
    FieldDelta<ProductionRelation> relations,
    FieldDelta<Market> markets,
    FieldDelta<ShipmentBatch> shipments,
    FieldDelta<Membership> memberships,
    FieldDelta<UseRight> useRights,
    FieldDelta<OperatorCondition> operatorConditions)
    implements ChangeSet {

  /** {@code meta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String META_KEY = "meta";

  public EconomyChangeSet {
    // ★ **旧档兼容**（§11 的口径，照 {@code LedgerChangeSet}）：升级前落盘的这条变更集没有这些
    //   键时，Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (meta == null) {
      meta = new FieldDelta.Unchanged<>();
    }
    if (industries == null) {
      industries = new FieldDelta.Unchanged<>();
    }
    if (classes == null) {
      classes = new FieldDelta.Unchanged<>();
    }
    if (debts == null) {
      debts = new FieldDelta.Unchanged<>();
    }
    if (flows == null) {
      flows = new FieldDelta.Unchanged<>();
    }
    if (laborSupply == null) {
      laborSupply = new FieldDelta.Unchanged<>();
    }
    if (allocations == null) {
      allocations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 8 个组件（T2）：同一口径（旧档没提该组件，就是没动它）。
    if (relations == null) {
      relations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 9 个组件（H4）：同一口径（旧档没提该组件，就是没动它）。
    if (markets == null) {
      markets = new FieldDelta.Unchanged<>();
    }
    // ★ 第 10 个组件（M2.4）：同一口径（旧档没提该组件，就是没动它）。
    if (shipments == null) {
      shipments = new FieldDelta.Unchanged<>();
    }
    // ★ S1 的两个新组件：同一口径（旧档没提该组件，就是没动它）。
    if (memberships == null) {
      memberships = new FieldDelta.Unchanged<>();
    }
    if (useRights == null) {
      useRights = new FieldDelta.Unchanged<>();
    }
    // ★ 第 13 个组件（S3.2）：同一口径（旧档没提该组件，就是没动它）。
    if (operatorConditions == null) {
      operatorConditions = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static EconomyChangeSet between(EconomyData base, EconomyData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new EconomyChangeSet(
        FieldDelta.diff(metaTable(base.meta()), metaTable(target.meta())),
        FieldDelta.diff(base.industries(), target.industries()),
        FieldDelta.diff(base.classes(), target.classes()),
        FieldDelta.diff(base.debts(), target.debts()),
        FieldDelta.diff(base.flows(), target.flows()),
        FieldDelta.diff(base.laborSupply(), target.laborSupply()),
        FieldDelta.diff(base.allocations(), target.allocations()),
        FieldDelta.diff(base.relations(), target.relations()),
        FieldDelta.diff(base.markets(), target.markets()),
        FieldDelta.diff(base.shipments(), target.shipments()),
        FieldDelta.diff(base.memberships(), target.memberships()),
        FieldDelta.diff(base.useRights(), target.useRights()),
        FieldDelta.diff(base.operatorConditions(), target.operatorConditions()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static EconomyData apply(EconomyChangeSet cs, EconomyData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new EconomyData(
        metaOf(FieldDelta.rebuild(metaTable(base.meta()), cs.meta(), Function.identity())),
        FieldDelta.rebuild(base.industries(), cs.industries(), IndustryId::parse),
        FieldDelta.rebuild(base.classes(), cs.classes(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.debts(), cs.debts(), DebtId::parse),
        FieldDelta.rebuild(base.flows(), cs.flows(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.laborSupply(), cs.laborSupply(), PeopleLotId::parse),
        FieldDelta.rebuild(base.allocations(), cs.allocations(), LaborAllocationId::parse),
        FieldDelta.rebuild(base.relations(), cs.relations(), IndustryId::parse),
        FieldDelta.rebuild(base.markets(), cs.markets(), HexCoord::parse),
        FieldDelta.rebuild(base.shipments(), cs.shipments(), ShipmentId::parse),
        FieldDelta.rebuild(base.memberships(), cs.memberships(), MembershipId::parse),
        FieldDelta.rebuild(base.useRights(), cs.useRights(), UseRightId::parse),
        FieldDelta.rebuild(base.operatorConditions(), cs.operatorConditions(), IndustryId::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(meta.changed()
        || industries.changed()
        || classes.changed()
        || debts.changed()
        || flows.changed()
        || laborSupply.changed()
        || allocations.changed()
        || relations.changed()
        || markets.changed()
        || shipments.changed()
        || memberships.changed()
        || useRights.changed()
        || operatorConditions.changed());
  }

  /** {@code Optional<EconomyMeta>} → 至多一行的表（键固定为 {@link #META_KEY}）。 */
  private static Map<String, EconomyMeta> metaTable(Optional<EconomyMeta> meta) {
    return meta.map(value -> Map.of(META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<EconomyMeta> metaOf(Map<String, EconomyMeta> table) {
    return Optional.ofNullable(table.get(META_KEY));
  }

  /**
   * ★★ S1：变更集里的键是 {@code toString()} 的产物 ⇒ 旧档的 CohortKey 串（{@code 0_0|rural|poor}，含 {@code |} 且非
   * legacy- 前缀）必须在**重建时**映射成 {@link HouseholdId#ofLegacy}，否则它与快照迁移后的 classes 键对不上。 已是 {@code
   * legacy-} 前缀的新键 ⇒ 原样 parse（幂等）。
   */
  private static HouseholdId householdId(String text) {
    if (text != null && !text.startsWith(HouseholdId.LEGACY_PREFIX) && text.indexOf('|') >= 0) {
      return HouseholdId.ofLegacy(CohortKey.parse(text));
    }
    return HouseholdId.parse(text);
  }
}
