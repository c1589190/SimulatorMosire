package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.economy.model.WageDefaultKey;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
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
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "本类是日结算的可变工作表（协调器单线程独占，见类注）：各 accessor 返回惰性拷贝的工作副本本身即设计语义（结算写回 base 的唯一通道），防御性拷贝会破坏工作表语义")
public final class EconomyStateBuilder {

  /** ★ <b>B1 批：建档类写入推回 production 时的日志门面</b>（I15：每模块一个 {@code XxxLog} 唯一拼写点； 跨域不一致 = ERROR 不降级）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private final EconomyData base;
  private final io.mosire.simos.production.contract.ProductionDefinitionLookup definitions;

  /**
   * ★★ <b>D-032 U2：生产运行时的只读投影</b>（本会话的当刻生产候选态来源）。
   *
   * <p>★★ <b>为什么工作表要持有它</b>：D-032 起<b>生产实例（进度 / 周期累计劳动 / 周期累计投入）的唯一权威在 production</b>（{@link
   * ProductionProcess} 的类注 / I1）⇒ economy 侧对实例的读必须落在<b>同一份候选态</b>上， 而那份候选态由本会话的 {@link
   * ProductionRuntime} 提供（I13：不得跨 revision 陈旧，故本字段是<b>会话级</b>的 runtime 投影、不是基态快照）。
   *
   * <p>★ <b>可以为 null</b>：只做家户/债务等非生产组件的旧调用点（如 {@code LotMigrationBook}）可以不接生产运行时； 那种会话一旦读 {@link
   * #units()} 就<b>当场抛</b>（不是"读到空表"—— 见 {@link #units()} 的 fail-closed 口径）。
   */
  private final ProductionRuntime runtime;

  /**
   * ★★ <b>本会话的产业定义工作副本</b>（瞬态、不落盘）：常态下为空（直接读注入的 production 定义）；仅当日结算的 **按需创建**（如跑商为某格即时生成 {@code
   * trade} 模板）物化它。★ 它不是第二份持久权威——{@link #build()} 不把它写进 {@code EconomyData}；定义权威仍在 production。
   */
  private LinkedHashMap<IndustryId, IndustryDefinitionView> industriesOverlay;

  private LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies;
  private LinkedHashMap<DebtContractId, DebtContract> debtContracts;
  private LinkedHashMap<PledgeId, Pledge> pledges;
  private LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments;
  private LinkedHashMap<AssetShareId, OwnershipStake> assetShares;
  private LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions;

  // ★★ B2b（§13.11.4 第 4 条）：`unitsWorkCopy` / `unitsWorkCopyBaselineKeys` / `flushUnitWorkCopy()`
  //   三件套已**整体退役** —— 建档类写者（进入执行 / 自动组织 / 模式变迁 / 迁移搬迁）改直调 production 契约口
  //   （`createInstance` / `removeInstance` / `reoperateInstance`），economy 侧不再有任何实例写回路径。

  private LinkedHashMap<ProductionUnitId, ProductionRules> relations;
  private LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> productionOrganizations;
  private LinkedHashMap<MarketZoneId, MarketTable> marketTables;

  /** ★★ W1：市场轮统计簿的会话工作副本（键 = 市场 id）。 */
  private LinkedHashMap<MarketZoneId, MarketRoundStats> marketRoundStats;

  /** ★★★ D-032 C2：生产参与名册工作副本（自动组织 / 名册命令在段内写；未物化 ⇒ 原样复用 base）。 */
  private LinkedHashMap<ProductionUnitId, ProductionRoster> productionRosters;

  /** ★★★ D-032 W2a：候选级工资默认工作副本（W1/W2b 若在段内物化会写；未物化 ⇒ 原样复用 base）。 */
  private LinkedHashMap<WageDefaultKey, ProductionWageDefault> productionWageDefaults;

  /** ★★★ D-032 W2a：执行期工资岗位工作副本（U20/W2b 后续写；未物化 ⇒ 原样复用 base）。 */
  private LinkedHashMap<WagePostingKey, ProductionWagePosting> productionWagePostings;

  /** ★★★ D-032 W2b：逐 (unit, role, worker) 工资进位余数工作副本（未物化 ⇒ 原样复用 base）。 */
  private LinkedHashMap<WageCarryKey, Long> wageCarryRemainders;

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

  // ★★ B2b：Z1 的 `productionEfficiency` 工作副本已删（效率累计与余数的唯一权威在 production，
  //   经 `ProductionRuntime#efficiencyOf(unit)` 只读）。

  /**
   * ★★ <b>R1（第 39 个组件）：政府市场授权表工作副本</b> —— 日结算把当日**成交**累加进 {@code filledMilli}、并清除耗尽/到期的行； 未物化时
   * {@link #build} 原样复用 base 的不可变表（无授权世界零拷贝）。
   */
  private LinkedHashMap<MarketMandateId, GovernmentMarketMandate> govMarketMandates;

  private Optional<EconomyMeta> meta;

  /**
   * ★★★ <b>D-032 U2 的装配口</b>：把本会话的生产运行时只读投影一起注入。
   *
   * <p>★★ <b>B3（§13.13.7 第 8 条 / §13.3 第 2 条）：{@code runtime} 必须非 null</b> —— 旧的两参构造器 （{@code
   * runtime = null}，"本会话不读/不写生产实例"）已**删除**：生产实例的唯一权威在 production（I1）， 任何支持生产的有效入口都必须显式获得合法
   * runtime；"没有运行时"不再是一条合法装配形态（它只会让读到实例的路径 逐个 fail-closed，= "能编译、实际不能履约"的入口）。需要"零实例"的世界请传一个空候选态的
   * runtime。
   */
  public EconomyStateBuilder(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      ProductionRuntime runtime) {
    this.base = Objects.requireNonNull(base, "base");
    this.definitions = Objects.requireNonNull(definitions, "definitions（定义权威在 production，必须显式注入）");
    this.runtime =
        Objects.requireNonNull(runtime, "runtime（D-032：生产实例的唯一权威在 production，必须显式注入合法运行时）");
  }

  public EconomyData base() {
    return base;
  }

  /**
   * ★★ <b>当刻产业定义的工作副本</b>（首次访问时从注入的 production 定义惰性拷贝）—— 只服务日结算的按需创建； 常态读者请走 {@link
   * #definitions()}（零拷贝、直接读 production 权威）。
   */
  public LinkedHashMap<IndustryId, IndustryDefinitionView> industries() {
    if (industriesOverlay == null) {
      industriesOverlay = new LinkedHashMap<>(definitions.industries());
    }
    return industriesOverlay;
  }

  /**
   * ★★ <b>当刻生产定义的只读来源</b>（production 契约；当刻视图与 revision 边界共用同一份注入）。
   *
   * <p>★ 若本会话发生了按需创建（{@link #industries()} 被物化），返回一份把该覆盖层叠在注入定义之上的只读视图 —— 于是当刻读者与新定义一致；覆盖层<b>不进</b>
   * {@link #build()} 的 {@code EconomyData}（持久权威仍在 production）。
   */
  public io.mosire.simos.production.contract.ProductionDefinitionLookup definitions() {
    if (industriesOverlay == null) {
      return definitions;
    }
    return new io.mosire.simos.production.contract.ProductionDefinitionLookup() {
      @Override
      public java.util.Optional<IndustryDefinitionView> industry(IndustryId id) {
        IndustryDefinitionView overlay = industriesOverlay.get(id);
        return overlay != null ? java.util.Optional.of(overlay) : definitions.industry(id);
      }

      @Override
      public java.util.Optional<io.mosire.simos.production.contract.ProductionModeDefinitionView>
          mode(io.mosire.simos.economy.api.id.ProductionModeId id) {
        return definitions.mode(id);
      }

      @Override
      public Map<IndustryId, IndustryDefinitionView> industries() {
        return java.util.Collections.unmodifiableMap(industriesOverlay);
      }

      @Override
      public Map<
              io.mosire.simos.economy.api.id.ProductionModeId,
              io.mosire.simos.production.contract.ProductionModeDefinitionView>
          modes() {
        return definitions.modes();
      }
    };
  }

  /**
   * ★★ <b>本会话按需创建、尚未登记的产业定义</b>（瞬态候选态）：overlay 里那些注入定义中不存在的 id。
   *
   * <p>★ app 组合根在 Advance 收尾时把这些候选态投影成 production 的 ChangeSet，与经济实例变更集在同一次 Advance 原子提交 （D-031
   * §9.1）；不进 {@code EconomyData}，不是第二份持久权威。
   */
  public Map<IndustryId, IndustryDefinitionView> createdIndustryDefinitions() {
    if (industriesOverlay == null) {
      return Map.of();
    }
    Map<IndustryId, IndustryDefinitionView> created = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry : industriesOverlay.entrySet()) {
      if (definitions.industry(entry.getKey()).isEmpty()) {
        created.put(entry.getKey(), entry.getValue());
      }
    }
    return java.util.Collections.unmodifiableMap(created);
  }

  /** 家户行工作副本（键 = 稳定身份）。 */
  public LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies() {
    if (householdEconomies == null) {
      householdEconomies = new LinkedHashMap<>(base.classes());
    }
    return householdEconomies;
  }

  /** ★★ <b>B2：家户行表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<HouseholdId, HouseholdEconomy> householdEconomiesOrBase() {
    return householdEconomies == null ? base.classes() : householdEconomies;
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

  /**
   * ★★★ <b>当刻生产实例表（D-032：权威在 production；本方法返回<b>真只读</b>投影）</b>。
   *
   * <p>★★ <b>语义（B2b 收口后）</b>：内容的来源是 {@link ProductionRuntime#units()} 交出的<b>当刻候选态</b> （I13：同次
   * Advance 的后续阶段必须看到本次最新候选态），<b>零拷贝、不缓存</b>——economy 侧的 {@code EconomyData.units} 组件与 {@code
   * unitsWorkCopy} 写回桥都已删除（B2b），economy 不再持有任何一份 实例表。
   *
   * <p>★★ <b>为什么不能返回"可写副本"</b>：副本会在两次读写之间产生窗口——一次 {@code ProductionRuntime#executeDay}
   * 推进过的进度，副本看不见；于是"同一实例同一 tick 被推两次" 这类契约故障会从检测网里漏过去。⇒ 读侧一律直读权威表；需要改实例的<b>建档类</b>写者一律直调契约口 {@code
   * createInstance} / {@code removeInstance} / {@code reoperateInstance}（不再经任何工作副本）。
   *
   * <p>★★ <b>fail-closed（不是"读到空表"）</b>：本会话若没有注入生产运行时（{@link #runtime} 为 null）， 调用本方法<b>当场抛</b>
   * {@link IllegalStateException} —— 把"没有运行时"静默当成"世界上没有生产实例" 正是本仓最反对的形态（{@code EconomyData} 的 H1/K1
   * 守卫同一条口径：没有账 ≠ 账是空的）。
   */
  public Map<ProductionUnitId, ProductionProcess> units() {
    return unitsReadOnly();
  }

  /**
   * ★★ <b>当刻生产实例的<b>真只读</b>投影（D-032 U2 的目标形态；新代码请用它）</b>。
   *
   * <p>★ 与 {@link #units()} 逐字同义（两者都只读；B2b 起 {@link #units()} 就是本方法）。任何 {@code put/remove} 当场
   * {@code UnsupportedOperationException}（production 的候选态是冻结表）。
   */
  public Map<ProductionUnitId, ProductionProcess> unitsReadOnly() {
    // ★★ B3：runtime 由构造器保证非 null（见那里的注释）—— 这里不再有"没有运行时"这一档。
    return runtime.units();
  }

  /**
   * ★ <b>生产实例表的"工作副本选择"</b>：D-032 起两侧同源（内容都来自 production 的当刻候选态） —— 保留本方法名只为不改动日结算里既有的读取点。
   *
   * <p>★ 已物化过 {@link #units()}（本会话有写者）⇒ 交给同一份工作副本（否则写者的结果会被读点看不见）； 否则交给 {@link #unitsReadOnly()}
   * 的原表（零拷贝）。
   */
  public Map<ProductionUnitId, ProductionProcess> unitsOrBase() {
    return unitsReadOnly();
  }

  // ★★ B2b：`unitsWorkCopy()` 与 `flushUnitWorkCopy()` 已整体删除（见上面字段处的说明）。
  //   ★ 曾经的 4 个写者现在的落点：
  //     · 进入执行 `EconomyEntrySettlement` → `runtime.registerPlan` + `runtime.createInstance`（B2 已改）；
  //     · 自动组织 `EconomyEnterpriseSettlement` → `runtime.createInstance`（B2b 改）；
  //     · 模式变迁 `EconomyModeTransitionSettlement` → `runtime.createInstance`（B2b 改）；
  //     · 迁移搬迁 `ModeMigrationSettlement` → `runtime.createInstance` / `runtime.removeInstance`（B2b
  // 改）。

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

  /** ★★ <b>B2：生产组织表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizationsOrBase() {
    return productionOrganizations == null
        ? base.productionOrganizations()
        : productionOrganizations;
  }

  // ★★ B2b：`productionEfficiency()` 工作副本访问器已删（唯一权威在 production）。

  /** 市场表工作副本。 */
  public LinkedHashMap<MarketZoneId, MarketTable> marketTables() {
    if (marketTables == null) {
      marketTables = new LinkedHashMap<>(base.marketTables());
    }
    return marketTables;
  }

  /** ★★ <b>B2：市场表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<MarketZoneId, MarketTable> marketTablesOrBase() {
    return marketTables == null ? base.marketTables() : marketTables;
  }

  /**
   * ★★ <b>W1：市场轮统计簿的当刻值</b>（未写 ⇒ base 的不可变表）。
   *
   * <p>★ 与其余工作副本同一条口径（{@code WorkingDayView} 的类注）：**已物化读工作副本、未物化直接复用 base**， 因此读一次当刻值不会顺带拷一份表。
   */
  public Map<MarketZoneId, MarketRoundStats> marketRoundStatsOrBase() {
    return marketRoundStats == null ? base.marketRoundStats() : marketRoundStats;
  }

  /** ★ 统计簿的可写工作副本（每个市场轮结束时写它；惰性物化 = 只读的段不拷表）。 */
  public LinkedHashMap<MarketZoneId, MarketRoundStats> marketRoundStats() {
    if (marketRoundStats == null) {
      marketRoundStats = new LinkedHashMap<>(base.marketRoundStats());
    }
    return marketRoundStats;
  }

  /** ★★★ D-032 C2：名册表的只读选择（已物化读工作副本、未物化直接复用 base 的不可变表）。 */
  public Map<ProductionUnitId, ProductionRoster> productionRostersOrBase() {
    return productionRosters == null ? base.productionRosters() : productionRosters;
  }

  /** ★★★ D-032 C2：名册表工作副本（写入口：自动名册 / 名册命令；未物化时从 base 惰性拷贝）。 */
  public LinkedHashMap<ProductionUnitId, ProductionRoster> productionRosters() {
    if (productionRosters == null) {
      productionRosters = new LinkedHashMap<>(base.productionRosters());
    }
    return productionRosters;
  }

  /** ★★★ D-032 W2a：候选级工资默认的只读选择（已物化读工作副本、未物化直接复用 base 的不可变表）。 */
  public Map<WageDefaultKey, ProductionWageDefault> productionWageDefaultsOrBase() {
    return productionWageDefaults == null ? base.productionWageDefaults() : productionWageDefaults;
  }

  /** ★★★ D-032 W2a：候选级工资默认工作副本（惰性物化）。 */
  public LinkedHashMap<WageDefaultKey, ProductionWageDefault> productionWageDefaults() {
    if (productionWageDefaults == null) {
      productionWageDefaults = new LinkedHashMap<>(base.productionWageDefaults());
    }
    return productionWageDefaults;
  }

  /** ★★★ D-032 W2a：执行期工资岗位的只读选择（已物化读工作副本、未物化直接复用 base 的不可变表）。 */
  public Map<WagePostingKey, ProductionWagePosting> productionWagePostingsOrBase() {
    return productionWagePostings == null ? base.productionWagePostings() : productionWagePostings;
  }

  /** ★★★ D-032 W2a：执行期工资岗位工作副本（惰性物化）。 */
  public LinkedHashMap<WagePostingKey, ProductionWagePosting> productionWagePostings() {
    if (productionWagePostings == null) {
      productionWagePostings = new LinkedHashMap<>(base.productionWagePostings());
    }
    return productionWagePostings;
  }

  /** ★★★ D-032 W2b：工资进位余数的只读选择（已物化读工作副本、未物化直接复用 base）。 */
  public Map<WageCarryKey, Long> wageCarryRemaindersOrBase() {
    return wageCarryRemainders == null ? base.wageCarryRemainders() : wageCarryRemainders;
  }

  /** ★★★ D-032 W2b：工资进位余数工作副本（惰性物化）。 */
  public LinkedHashMap<WageCarryKey, Long> wageCarryRemainders() {
    if (wageCarryRemainders == null) {
      wageCarryRemainders = new LinkedHashMap<>(base.wageCarryRemainders());
    }
    return wageCarryRemainders;
  }

  /** 在途批次表工作副本。 */
  public LinkedHashMap<ShipmentId, ShipmentBatch> shipments() {
    if (shipments == null) {
      shipments = new LinkedHashMap<>(base.shipments());
    }
    return shipments;
  }

  /** ★★ <b>B2：在途批次表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<ShipmentId, ShipmentBatch> shipmentsOrBase() {
    return shipments == null ? base.shipments() : shipments;
  }

  /** ★★ E3：政府表工作副本（发行腿切换前由 `syncAuthorities` 读它；写口目前只在 GM 命令）。 */
  public LinkedHashMap<GovernmentId, Government> governments() {
    if (governments == null) {
      governments = new LinkedHashMap<>(base.governments());
    }
    return governments;
  }

  /** ★★ <b>B2：政府表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<GovernmentId, Government> governmentsOrBase() {
    return governments == null ? base.governments() : governments;
  }

  /** ★★ E3：货币发行审计表工作副本（settleOneDay 的发行腿按转移 id 确定性追加记录）。 */
  public LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances() {
    if (moneyIssuances == null) {
      moneyIssuances = new LinkedHashMap<>(base.moneyIssuances());
    }
    return moneyIssuances;
  }

  /** ★★ <b>B2：货币发行审计表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuancesOrBase() {
    return moneyIssuances == null ? base.moneyIssuances() : moneyIssuances;
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

  /** ★★ <b>B2：模式变迁表的只读选择</b>（同 {@link #industriesOrBase()} 的理由）。 */
  public Map<ModeTransitionId, ModeTransition> modeTransitionsOrBase() {
    return modeTransitions == null ? base.modeTransitions() : modeTransitions;
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

  /**
   * ★★ <b>R1：政府市场授权表的只读选择</b>：已物化工作副本则读它，否则读 base 的表。
   *
   * <p>★★ <b>为什么必须有它</b>：一次 {@code advance} 的**多日**都落在同一个会话工作副本上（一整段只落一条 revision），
   * 而"授权在本段内被耗尽/到期清除"是**当日就生效**的事实 ⇒ 后续各日的"当日生效授权计划"必须读**工作副本**， 读 base 会把已经清掉的行再挂一遍（实测：day5
   * 成交量满后被清除，day10 又从 base 挂出同一张单， 成交回落时找不到状态行 ⇒ fail-closed 抛）。它同时避免"只读一次也materialize 一份拷贝"。
   */
  public Map<MarketMandateId, GovernmentMarketMandate> govMarketMandatesOrBase() {
    return govMarketMandates == null ? base.govMarketMandates() : govMarketMandates;
  }

  /** ★★ <b>R1：政府市场授权表工作副本</b>（见类字段注释）；未物化 ⇒ 原样复用 base 的不可变表。 */
  public LinkedHashMap<MarketMandateId, GovernmentMarketMandate> govMarketMandates() {
    if (govMarketMandates == null) {
      govMarketMandates = new LinkedHashMap<>(base.govMarketMandates());
    }
    return govMarketMandates;
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
        // ★★★ D-032 B4（§13.14.2 裁定十五）：A 批续做的"占位带过 base.industries()"一行**已删** ——
        //   `EconomyData` 不再有 industries 组件（定义权威唯一归 production）。本工作表仍只经
        //   {@link #definitions()} 读当刻定义，{@link #industries()} 的按需覆盖层照旧**不进**状态
        //   （瞬态候选态，由组合根在 Advance 收尾时投影成 production 的 ChangeSet）。
        householdEconomies == null ? base.classes() : householdEconomies,
        debtContracts == null ? base.debtContracts() : debtContracts,
        flows,
        laborCommitments == null ? base.allocations() : laborCommitments,
        // ★★ R4-E2b：relations 也成了可选工作副本（进入执行会插入新 relation；未物化 ⇒ 原样复用 base）。
        relationsOrBase(),
        marketTables == null ? base.marketTables() : marketTables,
        shipments == null ? base.shipments() : shipments,
        assetShares == null ? base.assetShares() : assetShares,
        operatorConditions == null ? base.operatorConditions() : operatorConditions,
        // ★★ B2b：生产实例表已从 {@link EconomyData} 删除（唯一权威在 production 的当刻候选态；
        //   读它一律走 {@link #units()} = {@link ProductionRuntime#units()} 的只读投影）。
        // ★★ R4-E2：需求不参与日结算写回 —— 原样带过 base 的表（写入口只有 GM 命令）。
        //   ★★ B2c：候选预设表已从 EconomyData 删除（裁定八）⇒ 本处不再有它的带过腿。
        base.demands(),
        // ★★★ B5（§13.15.2 裁定十八）：第 17 个组件 `modes` 已从 EconomyData 删除 ⇒ 本处不再有带过腿；
        //   生产方式定义的唯一权威在 production（读它一律走 {@link #definitions()} / {@code EconomyDayView#modes()}）。
        // ★★ E1：阶层结构与阶层位置不参与旧日结算写回 —— 原样带过 base 的表；
        //   ★★ P1：生产方式定义已迁出 economy（权威在 production），不再随本状态构造。
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
        // ★★ P4a：规则表不参与旧日结算写回，原样带过 base 的表 —— 漏了它 = 任意一次 advance
        //   都会把已注册的周期规则静默抹掉（账面上看不出是谁弄丢的）。
        base.periodicAdjustments(),
        // ★★ B2b：Z1 的产品产出数量覆盖表与生产效率表已从 {@link EconomyData} 删除 ——
        //   两维的唯一权威在 production（`ProductionRuntime#outputQuantityOverrides()` / `#efficiencyOf`），
        //   它们的差异由 production 的 ChangeSet 在同一条 Revision 里提交。
        // ★★ A1：货币词表不参与日结算写回（写入口只有创世/命令）⇒ 原样带过 base 的两张表；
        //   漏了它 = 任意一次 advance 都会把世界词表打回出厂值（账面上看不出是谁弄丢的）。
        base.currencies(),
        base.moneyInstruments(),
        // ★★ B2：市场区表不参与日结算写回（写入口只有三条区命令）⇒ 原样带过 base 的表；
        //   漏了它 = 任意一次 advance 都会把世界的市场区抹掉、市场区悄悄退回"城市 + tier 半径"派生值
        //   （账面上看不出是谁弄丢的，而那正是本批要终结的"两个权威"）。★ W5.5：货币市场的利率/α 在**价表**上
        //   （派生形态的世界也有价表），不在这里。
        base.marketZones(),
        // ★★ 2026-10-09 选项 A：家户债务引用派生索引同样**不需要**会话工作副本 —— 它是合同表的派生索引，
        //   本方法构造 EconomyData 时由 DebtReferenceReconciler 按（可能已被本会话改过的）债务工作表**整表重建**。
        //   改前这里也没有引用表（当时引用住在每一行里、由相同的对账重建）⇒ 语义逐条不变。
        base.householdDebtRefs(),
        // ★★ 第 38 个组件（商品基础运费表）：它不参与日结算写回（写入口只有 GM 命令）
        //   ⇒ 原样带过 base 的表。★ 漏了它 = **任意一次 advance 都会把 GM 设过的基础运费静默抹掉**
        //   （账面上看不出是谁弄丢的，那正是本批要防的那类静默丢字段）。
        base.commodityFreightBaseMilli(),
        // ★★ R1（第 39 个组件）：政府市场授权表**不是**会话工作副本 —— 它的写入口只有两条 GM 命令；
        //   但日结算会把**当日成交**累加进 {@code filledMilli} 并清除耗尽/到期的行（见
        //   EconomySettlement 的市场段）⇒ 走下面的显式工作副本，漏了它 = 任意一次 advance
        //   都会把 GM 授权过的挂单静默抹掉（账面上看不出是谁弄丢的）。
        govMarketMandates == null ? base.govMarketMandates() : govMarketMandates,
        // ★★ W1（第 40 个组件）：市场轮统计簿**是**会话工作副本 —— 每个市场轮结束时写它
        //   （最近一轮 / 本周期 / 上周期三段窗口，见 MarketRoundStats）。漏了它 = 统计在段内被写回、
        //   下一次 advance 又从 base 的旧窗口开始（"统计永远差一轮"这种静默漂移）。
        marketRoundStats == null ? base.marketRoundStats() : marketRoundStats,
        // ★★★ D-032 C2（第 32 个组件）：自动组织会在段内写真名册 ⇒ 走显式工作副本；未物化 ⇒ 原样复用 base。
        //   漏了它 = 任意一次 advance 都会把已建立的名册静默抹掉（本仓最贵的那类 bug）。
        productionRosters == null ? base.productionRosters() : productionRosters,
        // ★★★ D-032 W2a：工资 default/posting 两维；未物化 ⇒ 原样复用 base。漏了它们 =
        //   任意一次 advance 都会把 GM 设过的候选默认/执行岗位静默抹掉。
        productionWageDefaults == null ? base.productionWageDefaults() : productionWageDefaults,
        productionWagePostings == null ? base.productionWagePostings() : productionWagePostings,
        // ★★★ D-032 W2b：进位余数工作副本；未物化 ⇒ 原样复用 base。漏了它 = 任意一次 advance
        //   都会把已累计的工资进位静默抹掉（逐 tick floor 会永久丢掉不足 1 毫的余数）。
        wageCarryRemainders == null ? base.wageCarryRemainders() : wageCarryRemainders);
  }
}
