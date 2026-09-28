package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>日结算的可变工作表（P1.5 的落点）</b>：把 {@link EconomyData} 的十三个可变组件做成**惰性拷贝**的工作副本 ——
 * 只有当天真的写过的组件才拷一份、被改过；没碰过的组件在 {@link #build} 时**原样复用 base 的那张表**。
 *
 * <p>★★ <b>为什么需要它</b>：旧 {@code EconomySettlement.settleOneDay} 每天在方法开头把全部组件整份复制一遍、 在末尾构造一次完整 {@code
 * EconomyData}（连同全部守卫），逐日推进 30 天就是 30 次 O(状态) 的拷贝 + 30 次全量校验。 工作表把"拷贝"和"全量校验"都推迟到**revision
 * 边界**（{@code EconomyDayStepper.finish()} 一次）， 语义不变（每天仍做方法内部已有的轻量守卫；全量守卫在 revision 边界照旧跑一次）。
 *
 * <p>★ <b>不改守卫</b>：本类不做任何"绕过构造期不变量"的事 —— {@link #build} 走的仍是 {@link EconomyData}
 * 的规范构造器；所有守卫一条不少，只是<b>频率</b>从"每天"变成"每个 revision 一次"（计划 §4.2 P1.5a）。
 *
 * <p>★★ <b>归属（R1）</b>：本工作表是<b>协调器单线程</b>的可变状态（{@link EconomySession} 独占持有），不发布给并行 worker；worker
 * 的账户意向走 {@link AccountIntentBuffer}，由协调器在 {@link AccountSession#commit} 里稳定提交。
 *
 * <p>★ <b>不变式</b>：工作表只在一次推进会话内使用（单线程、用完即弃）；{@code base} 永不被修改。
 */
public final class EconomyStateBuilder {

  private final EconomyData base;

  private LinkedHashMap<IndustryId, Industry> industries;
  private LinkedHashMap<HouseholdId, ClassRow> rows;
  private LinkedHashMap<DebtId, Debt> debts;
  private LinkedHashMap<LaborAllocationId, LaborAllocation> allocations;
  private LinkedHashMap<PeopleLotId, LaborSupply> laborSupply;
  private LinkedHashMap<MembershipId, Membership> memberships;
  private LinkedHashMap<AssetShareId, AssetShare> assetShares;
  private LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions;
  private LinkedHashMap<ProductionUnitId, ProductionUnit> units;
  private LinkedHashMap<HexCoord, Market> markets;
  private LinkedHashMap<ShipmentId, ShipmentBatch> shipments;
  private Optional<EconomyMeta> meta;

  public EconomyStateBuilder(EconomyData base) {
    this.base = Objects.requireNonNull(base, "base");
  }

  public EconomyData base() {
    return base;
  }

  /** 产业表工作副本（首次访问时从 base 惰性拷贝）。 */
  public LinkedHashMap<IndustryId, Industry> industries() {
    if (industries == null) {
      industries = new LinkedHashMap<>(base.industries());
    }
    return industries;
  }

  /** 家户行工作副本（键 = 稳定身份）。 */
  public LinkedHashMap<HouseholdId, ClassRow> rows() {
    if (rows == null) {
      rows = new LinkedHashMap<>(base.classes());
    }
    return rows;
  }

  /** 债务表工作副本。 */
  public LinkedHashMap<DebtId, Debt> debts() {
    if (debts == null) {
      debts = new LinkedHashMap<>(base.debts());
    }
    return debts;
  }

  /** 劳动配额表工作副本。 */
  public LinkedHashMap<LaborAllocationId, LaborAllocation> allocations() {
    if (allocations == null) {
      allocations = new LinkedHashMap<>(base.allocations());
    }
    return allocations;
  }

  /** 劳动供给表工作副本。 */
  public LinkedHashMap<PeopleLotId, LaborSupply> laborSupply() {
    if (laborSupply == null) {
      laborSupply = new LinkedHashMap<>(base.laborSupply());
    }
    return laborSupply;
  }

  /** 成员份额表工作副本。 */
  public LinkedHashMap<MembershipId, Membership> memberships() {
    if (memberships == null) {
      memberships = new LinkedHashMap<>(base.memberships());
    }
    return memberships;
  }

  /** 实物资产份额表工作副本（R3B.1）。 */
  public LinkedHashMap<AssetShareId, AssetShare> assetShares() {
    if (assetShares == null) {
      assetShares = new LinkedHashMap<>(base.assetShares());
    }
    return assetShares;
  }

  /** 经营者状态表工作副本（S3.2 第 13 个组件；R3B.2 起键 = unit id）。 */
  public LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions() {
    if (operatorConditions == null) {
      operatorConditions = new LinkedHashMap<>(base.operatorConditions());
    }
    return operatorConditions;
  }

  /** ★★ R3B.2 生产单元表工作副本（第 14 个组件；日结算推进进度/劳动/投入的唯一写点）。 */
  public LinkedHashMap<ProductionUnitId, ProductionUnit> units() {
    if (units == null) {
      units = new LinkedHashMap<>(base.units());
    }
    return units;
  }

  /** 市场表工作副本。 */
  public LinkedHashMap<HexCoord, Market> markets() {
    if (markets == null) {
      markets = new LinkedHashMap<>(base.markets());
    }
    return markets;
  }

  /** 在途批次表工作副本。 */
  public LinkedHashMap<ShipmentId, ShipmentBatch> shipments() {
    if (shipments == null) {
      shipments = new LinkedHashMap<>(base.shipments());
    }
    return shipments;
  }

  /** 元信息（未写 ⇒ base 的原值）。 */
  public Optional<EconomyMeta> meta() {
    return meta == null ? base.meta() : meta;
  }

  /** 写元信息（revision 边界由会话统一带入）。 */
  public void meta(Optional<EconomyMeta> value) {
    meta = value;
  }

  /** ★★ <b>唯一一次构造</b>：未写过的组件直接复用 base 的不可变表；写过的组件交给 {@link EconomyData} 的规范构造器 （全部守卫照跑）。 */
  public EconomyData build(Map<HouseholdId, FlowRow> flows) {
    Objects.requireNonNull(flows, "flows");
    return new EconomyData(
        meta(),
        industries == null ? base.industries() : industries,
        rows == null ? base.classes() : rows,
        debts == null ? base.debts() : debts,
        flows,
        laborSupply == null ? base.laborSupply() : laborSupply,
        allocations == null ? base.allocations() : allocations,
        base.relations(),
        markets == null ? base.markets() : markets,
        shipments == null ? base.shipments() : shipments,
        memberships == null ? base.memberships() : memberships,
        assetShares == null ? base.assetShares() : assetShares,
        operatorConditions == null ? base.operatorConditions() : operatorConditions,
        units == null ? base.units() : units,
        // ★★ R4-E2：需求/候选不参与日结算写回 —— 原样带过 base 的表（写入口只有 GM 命令）。
        base.demands(),
        base.candidates());
  }
}
