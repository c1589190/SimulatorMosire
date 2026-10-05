package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionProcess;
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
  private LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies;
  private LinkedHashMap<DebtContractId, DebtContract> debtContracts;
  private LinkedHashMap<PledgeId, Pledge> pledges;
  private LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments;
  private LinkedHashMap<AssetShareId, OwnershipStake> assetShares;
  private LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions;
  private LinkedHashMap<ProductionUnitId, ProductionProcess> units;
  private LinkedHashMap<ProductionUnitId, ProductionRules> relations;
  private LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> productionOrganizations;
  private LinkedHashMap<HexCoord, Market> markets;
  private LinkedHashMap<ShipmentId, ShipmentBatch> shipments;
  private LinkedHashMap<GovernmentId, Government> governments;
  private LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances;
  private LinkedHashMap<AssetRuleId, LiquidationPolicy> liquidationPolicies;
  private LinkedHashMap<CrisisSignalId, HexCrisisSignal> crisisSignals;
  private LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships;

  /** ★★ E6a：模式变迁表工作副本（命令只登记 PENDING；日结算写 APPLIED/FAILED）。 */
  private LinkedHashMap<ModeTransitionId, ModeTransition> modeTransitions;

  /** ★★ E6a：阶层保留份额表工作副本（日结算 apply 时按变迁/家户写出）。 */
  private LinkedHashMap<ClassShareId, ClassShare> classShares;

  /**
   * ★★ <b>P10.2：商号表工作副本（第 30 个组件）</b> —— 承运选择扣本周期运力、周期末结算写 lastFee/upkeep/profit/容量；
   * 未物化时 {@link #build} 原样复用 base 的不可变表（无商号世界零拷贝）。
   */
  private LinkedHashMap<ProductionOrganizationId, MerchantFirm> merchantFirms;

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
  public LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies() {
    if (householdEconomies == null) {
      householdEconomies = new LinkedHashMap<>(base.classes());
    }
    return householdEconomies;
  }

  /** ★★ E4a：债务**合同**表工作副本（键 = 稳定合同 id）。 */
  public LinkedHashMap<DebtContractId, DebtContract> debtContracts() {
    if (debtContracts == null) {
      debtContracts = new LinkedHashMap<>(base.debtContracts());
    }
    return debtContracts;
  }

  /** ★★ E4a：质押表工作副本（E4a 只落形状，日结算暂不写；工作副本留给 E5）。 */
  public LinkedHashMap<PledgeId, Pledge> pledges() {
    if (pledges == null) {
      pledges = new LinkedHashMap<>(base.pledges());
    }
    return pledges;
  }

  /** 劳动配额表工作副本。 */
  public LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments() {
    if (laborCommitments == null) {
      laborCommitments = new LinkedHashMap<>(base.allocations());
    }
    return laborCommitments;
  }

  /** 实物资产份额表工作副本（R3B.1）。 */
  public LinkedHashMap<AssetShareId, OwnershipStake> assetShares() {
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
  public LinkedHashMap<ProductionUnitId, ProductionProcess> units() {
    if (units == null) {
      units = new LinkedHashMap<>(base.units());
    }
    return units;
  }

  /**
   * ★★ <b>R4-E2b：生产关系表工作副本</b>（第 8 个组件）—— E2b 的进入执行会为新建 unit 插入一条 relation； 未物化时由 {@link
   * #relationsOrBase()} 直接复用 base 的不可变表（空表基线不产生任何拷贝）。
   */
  public LinkedHashMap<ProductionUnitId, ProductionRules> relations() {
    if (relations == null) {
      relations = new LinkedHashMap<>(base.relations());
    }
    return relations;
  }

  /** ★ <b>relation 表的只读选择</b>：已物化工作副本则读它，否则读 base 的表 —— 日结算的每个读取点都走这里， 避免"空表也先拷一份"。 */
  public Map<ProductionUnitId, ProductionRules> relationsOrBase() {
    return relations == null ? base.relations() : relations;
  }

  /**
   * ★★ <b>E2：生产组织表工作副本</b>（第 21 个组件）—— 自动组织阶段会 upsert 组织（ACTIVE/SHORTAGE）； 未物化时 {@link #build} 直接复用
   * base 的不可变表（旧的空表基线因此不产生任何拷贝）。
   */
  public LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> productionOrganizations() {
    if (productionOrganizations == null) {
      productionOrganizations = new LinkedHashMap<>(base.productionOrganizations());
    }
    return productionOrganizations;
  }

  /**
   * ★★ <b>P10.2：商号表工作副本</b>（键 = 值内 organizationId）。本批的写口：{@link MerchantSettlement} 的承运扣量
   * 与周期末 {@code withTradeResult/withCapacityDelta}。
   */
  public LinkedHashMap<ProductionOrganizationId, MerchantFirm> merchantFirms() {
    if (merchantFirms == null) {
      merchantFirms = new LinkedHashMap<>(base.merchantFirms());
    }
    return merchantFirms;
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

  /** ★★ E3：政府表工作副本（发行腿切换前由 `syncAuthorities` 读它；写口目前只在 GM 命令）。 */
  public LinkedHashMap<GovernmentId, Government> governments() {
    if (governments == null) {
      governments = new LinkedHashMap<>(base.governments());
    }
    return governments;
  }

  /** ★★ E3：货币发行审计表工作副本（settleOneDay 的发行腿按转移 id 确定性追加记录）。 */
  public LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances() {
    if (moneyIssuances == null) {
      moneyIssuances = new LinkedHashMap<>(base.moneyIssuances());
    }
    return moneyIssuances;
  }

  /**
   * ★★ E5a：清算政策表工作副本（E5a 只落地基；E5b 的清算阶段在结算会话里读它、并按需写回覆盖）。
   *
   * <p>★ 与其余组件同款：未物化 ⇒ {@link #build} 直接复用 base 的不可变表，空表基线不产生任何拷贝。
   */
  public LinkedHashMap<AssetRuleId, LiquidationPolicy> liquidationPolicies() {
    if (liquidationPolicies == null) {
      liquidationPolicies = new LinkedHashMap<>(base.liquidationPolicies());
    }
    return liquidationPolicies;
  }

  /** ★★ E5a：hex 危机信号表工作副本（E5a 只落地基；E5b 的危机阶段按 {@code (hex, kind)} 覆盖写最新一条）。 */
  public LinkedHashMap<CrisisSignalId, HexCrisisSignal> crisisSignals() {
    if (crisisSignals == null) {
      crisisSignals = new LinkedHashMap<>(base.crisisSignals());
    }
    return crisisSignals;
  }

  /**
   * ★★ <b>E5b：家户阶层归属的工作副本</b>（第 19 个组件）—— 债务压力计数器、阶层下滑与 5c 的 standing 权威投影会写它； 未物化时 {@link
   * #classMembershipsOrBase()} 直接复用 base 的不可变表（旧档空表因此不产生任何拷贝）。
   */
  public LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships() {
    if (classMemberships == null) {
      classMemberships = new LinkedHashMap<>(base.classStandings());
    }
    return classMemberships;
  }

  /** ★ <b>阶层归属表的只读选择</b>：已物化工作副本则读它，否则读 base 的表（5c 的投影因此每次构造都读同一份）。 */
  public Map<HouseholdId, HouseholdClassMembership> classMembershipsOrBase() {
    return classMemberships == null ? base.classStandings() : classMemberships;
  }

  /**
   * ★★ <b>E6a：模式变迁表工作副本</b>（第 28 个组件）—— 命令登记 PENDING、日结算写 APPLIED/FAILED； 未物化时 {@link #build} 直接复用
   * base 的不可变表（空表基线不产生任何拷贝）。
   */
  public LinkedHashMap<ModeTransitionId, ModeTransition> modeTransitions() {
    if (modeTransitions == null) {
      modeTransitions = new LinkedHashMap<>(base.modeTransitions());
    }
    return modeTransitions;
  }

  /**
   * ★★ <b>E6a：阶层保留份额表工作副本</b>（第 29 个组件）—— 日结算 apply 按 {@code (transition, household)} 写出两条份额；未物化时
   * {@link #build} 直接复用 base 的不可变表（空表基线不产生任何拷贝）。
   */
  public LinkedHashMap<ClassShareId, ClassShare> classShares() {
    if (classShares == null) {
      classShares = new LinkedHashMap<>(base.classShares());
    }
    return classShares;
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
        householdEconomies == null ? base.classes() : householdEconomies,
        debtContracts == null ? base.debtContracts() : debtContracts,
        flows,
        laborCommitments == null ? base.allocations() : laborCommitments,
        // ★★ R4-E2b：relations 也成了可选工作副本（进入执行会插入新 relation；未物化 ⇒ 原样复用 base）。
        relationsOrBase(),
        markets == null ? base.markets() : markets,
        shipments == null ? base.shipments() : shipments,
        assetShares == null ? base.assetShares() : assetShares,
        operatorConditions == null ? base.operatorConditions() : operatorConditions,
        units == null ? base.units() : units,
        // ★★ R4-E2：需求/候选不参与日结算写回 —— 原样带过 base 的表（写入口只有 GM 命令）。
        base.demands(),
        base.candidates(),
        // ★★ E1：生产方式/阶层结构/阶层位置不参与旧日结算写回 —— 原样带过 base 的表；
        //   E2+ 若要让结算改写它们，应像上面各组件一样增加显式工作副本，而不是在这里另造语义。
        base.modes(),
        base.classStructures(),
        base.classPositions(),
        // ★★ E5b：家户阶层归属是结算工作副本（债务压力计数器/阶层下滑写它；未物化 ⇒ 原样复用 base）。
        classMembershipsOrBase(),
        // ★★ E2：生产组织由自动组织阶段 upsert（显式工作副本）；生产资料规则只读 —— 原样带过 base 的表。
        productionOrganizations == null ? base.productionOrganizations() : productionOrganizations,
        base.assetRules(),
        governments == null ? base.governments() : governments,
        moneyIssuances == null ? base.moneyIssuances() : moneyIssuances,
        pledges == null ? base.pledges() : pledges,
        liquidationPolicies == null ? base.liquidationPolicies() : liquidationPolicies,
        crisisSignals == null ? base.crisisSignals() : crisisSignals,
        // ★★ E6a：命令登记 PENDING、日结算执行终态 —— 显式工作副本；未物化 ⇒ 原样复用 base。
        modeTransitions == null ? base.modeTransitions() : modeTransitions,
        classShares == null ? base.classShares() : classShares,
        // ★★ P10.2：商号表是结算工作副本（承运扣量、周期末贸易结果写回）；未物化 ⇒ 原样复用 base。
        merchantFirms == null ? base.merchantFirms() : merchantFirms);
  }
}
