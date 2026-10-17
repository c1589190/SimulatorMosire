package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>逐日结算的会话</b>（R4；给 {@code simos-app} 的人口—经济协调器用）：把"一次推 N 天"的内部日循环开放给 <b>唯一同时看得见多个切片的调用方</b>。
 *
 * <p>★★ <b>为什么必须有它</b>（而不是把 {@code settleOneDay} 直接公开）：日循环里有<b>跨日存活的可变状态</b> （本期流水累加器 +
 * 账户工作副本）。把它们作为公开方法入参交出去，等于把"哪一份累加器"变成调用方的责任 ——
 * 而它错了不会报错，只会让流水少记几天、或者让家户凭空断粮。本类把这些可变状态收进一个对象，对外只出"推进一天 / 交回状态"两件事。
 *
 * <p>★★ <b>S1：账户工作副本统一为 {@link AccountSession}</b> —— 旧口径的 householdGoods / householdMoney /
 * operatorGoods / operatorMoney 与四张 frozen 表已经收敛到<b>一个按 {@code (ActorRef, HexCoord)} 索引的会话对象</b> （见
 * {@link AccountSession} 的类注）。本类只借它一程：调用方在推进前从 actor 侧载入、推进中就地在会话里更新、推进结束后 通过 {@link #accounts()}
 * 落回 actor。★ 它<b>不进 {@link EconomyData}、不进变更集、不跨 revision 存活</b>。
 *
 * <p>★ <b>它是可变对象</b>（唯一的一个）：不共享、不并发；一次推进一个实例，用完即弃。
 *
 * <p>★ <b>{@link #finish()} 之前拿到的 {@link #data()} 里流水还是旧的</b>（累加器在会话里）：日循环结束后由 {@code finish()}
 * 一次性挂上。
 */
public final class EconomyDayStepper implements AutoCloseable {

  /** 会话生命周期日志（settlement 分类；逐日明细由 EconomySettlement 的阶段日志承担）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private final boolean plantingDrawsFirst;
  private final MarketTopology topology;

  private final EconomySession session;
  private final AccountSession accounts;

  /**
   * ★★ <b>D-032 U2：本段 Advance 的生产运行时只读投影</b>（组合根在推进起点新建；见带 runtime 的构造器）。
   *
   * <p>★ 可能为 null（只做家户/债务的窄会话）；那种会话读生产实例会 fail-closed 抛 （{@link EconomyStateBuilder#units()}）。
   */
  private final io.mosire.simos.production.contract.ProductionRuntime runtime;

  /** 「家户 → (lot → count)」只读投影（P2-A A3；由调用方从 Social 注入，不进任何 Economy 状态）。 */
  private Map<HouseholdId, Map<PeopleLotId, Long>> composition = Map.of();

  /**
   * ★★ <b>Z7b 的单位户集合</b>（由 app 组合根从 {@code Unit.households()} 现算后注入）。
   *
   * <p>★★ <b>控制方裁定 R2（2026-10-11）起它<b>不再是"退出商品市场"</b>，而是"<b>只买不卖</b>"名单</b>：军户/官吏户按普通家户参与
   * 买盘（口粮通道），卖侧维持 Z7b 原口径（其实物供给不得被当余量清仓）；政府国库户另按 {@code GovernmentMarketMandatePlan}（权威 = {@code
   * EconomyData.governments()}）走"只按授权下单"，**不再由本集合承担**。 分类的唯一拼写点 = {@code
   * MarketSettlement.MarketRound.householdMarketRole}。缺省空集 = 没有单位户要按只买处理。 不进任何 Economy 状态、不进变更集。
   */
  private Set<HouseholdId> marketExcludedHouseholds = Set.of();

  /**
   * ★★ <b>R2：本轮口岸实际管制力</b>（app 组合根在推进前折算后注入；逐轮瞬态、只读投影、不进状态）。
   *
   * <p>★ 与 {@link #updateMarketExcludedHouseholds(Set)} / {@link #updateComposition(Map)}
   * 同一条纪律：本类只持有 组合根给的<b>只读投影</b>，不落盘、不进变更集；缺省 {@link PortEnforcementInput#none()} ⇒ 逐值退回改前行为（I-P8）。
   */
  private PortEnforcementInput portEnforcement = PortEnforcementInput.none();

  /**
   * ★★ <b>P-T1b：本轮三层税的税率与收税政府</b>（app 组合根按口岸政策 × 暴露边权重折算后注入；逐轮瞬态、只读投影、不进状态）。
   *
   * <p>★ 与 {@link #portEnforcement} 分开：闸（能不能过）与税（收多少）是两个量，缺省各管各的；缺省 {@link PortTaxInput#none()} ⇒
   * 出口税/进口税/区内税<b>一分不收</b> ⇒ 旧世界逐值不变（I-C2）。
   */
  private PortTaxInput portTax = PortTaxInput.none();

  /**
   * ★★ <b>P-T1d：本轮的政府采购优先级（谁要求管控市场 + 各自的行政力池）</b>（app 组合根从 gov 政策与本政府的编制劳动力折算后注入； 逐轮瞬态、只读投影、不进状态）。
   *
   * <p>★ 与 {@link #portEnforcement} / {@link #portTax} 同一条纪律：经济侧只做"按余量给置顶排序 + 见底硬停"，池子怎么来由组合根决定； 缺省
   * {@link ProcurementPriorityInput#none()} ⇒ 没有政府要求管控市场 ⇒ 无置顶、无消耗 ⇒ 旧世界逐值不变（I-C2）。
   */
  private ProcurementPriorityInput procurementPriority = ProcurementPriorityInput.none();

  /** ★★ R2：本会话的并行度（默认单线程退化路径；{@link #finish()} 关掉自建的池）。 */
  private final EconomyParallelism parallelism;

  /**
   * ★★★ <b>D-032 W4：Social 对下一 tick 的权威只读投影</b>（逐 tick 由 app 组合根用当日 Social 重算后注入；不进状态/变更集）。
   *
   * <p>缺省 {@link SurvivalSocialFacts#unavailable()} = 没有 Social 接线。真实生产 tick 若存在劳动候选，匹配层必须具名 {@code
   * survival-need-missing}，不得把缺投影当空需求继续。
   */
  private SurvivalSocialFacts survivalSocialFacts = SurvivalSocialFacts.unavailable();

  /**
   * ★★ <b>P10.2：本周期利润/迁移累加器</b>（{@code modes} 非空才有；逐日喂当天账本，关账日 ⑦⑧⑨ 后在 {@code EconomySettlement}
   * 内复位）。旧档 {@code modes} 为空时恒为 {@code null} ⇒ 逐值不变。
   */
  private final EnterpriseProfitBook.CycleAccumulator profitCycle;

  /** ★ D-032 W4：最近一次 {@link #step(long)} 的 W3 逐义务只读读数（原样来自 {@link WageSettlementReadoutFeed}）。 */
  private List<WageSettlementReadout> lastWageSettlementReadouts = List.of();

  /** ★ M2.3/M2.4：最近一次 step 的区域市场报告（瞬态；L3 读数接它）。 */
  private MarketReport lastMarketReport;

  /**
   * ★★ <b>Z7c：最近一次 {@link #step(long)} 是否有关账的产业周期</b>（瞬态会话读数）：语义 = step 前后 {@code
   * EconomyMeta.lastClosedCycle} 发生变化，与 {@code EconomySettlement} 内部的 {@code anyCycleClosed} 同一事实。
   * app 组合根用它把 remittance 落在仓库既有的“关账日”上；本类只把既有事实开成只读读数，<b>不</b>改任何结算行为、不进状态/变更集。
   */
  private boolean lastCycleClosed;

  /**
   * ★★★ <b>D-032 U2 的装配口（组合根用）</b>：除定义只读来源外，另注入本段 Advance 的 <b>生产运行时只读投影</b>（{@code
   * production.contract.ProductionRuntime}）。
   *
   * <p>★★ <b>生命周期硬约束</b>：{@code runtime} 必须由组合根在<b>每次 Advance 起点</b>新建 （{@code new
   * ProductionRuntimeEngine(productionSlice)}）并独占使用到底；<b>不得</b>跨 Advance 复用 —— 引擎持有"当刻候选态"，复用它就是
   * I13 禁止的"跨 revision 陈旧"。
   */
  public EconomyDayStepper(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AccountSession accounts,
      MarketTopology topology) {
    this(
        base,
        definitions,
        runtime,
        accounts,
        topology,
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomyParallelism.singleThreaded());
  }

  // ★★★ D-032 B3（§13.13.7 第 8 条 / §13.3 第 2 条）：三个 **runtime = null** 的旧构造器已**删除**
  //   （4 参「组合根入口（区域拓扑版）」/ 3 参「单模块夹具」/ 6 参「行为旋钮可注入」）—— 它们都委托
  //   `this(..., null, ...)`，而 null runtime 的会话在任何日推进上都会 fail-closed ⇒ **没有实际可用职责**。
  //   ★ 合法装配只有下面两个（都显式收 runtime）：组合根用的 5 参（区域拓扑，出厂默认旋钮 + 单线程）与
  //     全参 7 参。★ 需要"零实例"世界请传空候选态的 runtime，不要传 null。

  /** ★★★ <b>D-032 U2：全参构造器（带生产运行时只读投影）</b>。 */
  public EconomyDayStepper(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      EconomyParallelism parallelism) {
    this(
        base,
        definitions,
        runtime,
        accounts,
        topology,
        plantingDrawsFirst,
        parallelism,
        new UnavailableAssetOccupancyLookup(),
        new UnavailableProductionWageQuoteLookup(),
        new UnavailableParticipantAttributeLookup());
  }

  /**
   * ★★★ <b>D-032 C2 全参构造器</b>：在原有行为旋钮之外显式注入 D4-C 资产占用读口、D2 §6.5 工资报价读口与 参与户属性桥。未接真实实现时必须传对应的 {@code
   * Unavailable*} fail-closed 适配器（不得传 null）。
   */
  public EconomyDayStepper(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      EconomyParallelism parallelism,
      AssetOccupancyLookup assetOccupancyLookup,
      ProductionWageQuoteLookup wageQuoteLookup,
      ParticipantAttributeLookup participantAttributeLookup) {
    this(
        base,
        definitions,
        runtime,
        accounts,
        topology,
        plantingDrawsFirst,
        parallelism,
        assetOccupancyLookup,
        wageQuoteLookup,
        participantAttributeLookup,
        null);
  }

  /** ★★★ <b>D-032 C2 全参构造器（含权威 revision）</b>：资产占用快照的 expectedRevision 由组合根给出。 */
  public EconomyDayStepper(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      EconomyParallelism parallelism,
      AssetOccupancyLookup assetOccupancyLookup,
      ProductionWageQuoteLookup wageQuoteLookup,
      ParticipantAttributeLookup participantAttributeLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision) {
    this.lastCycleClosed = false;
    // ★★ B3：runtime 必须非 null（同上：生产实例的唯一权威在 production）。
    this.runtime =
        Objects.requireNonNull(runtime, "runtime（D-032：生产实例的唯一权威在 production，必须显式注入合法运行时）");
    this.session =
        new EconomySession(
            Objects.requireNonNull(base, "base"),
            Objects.requireNonNull(definitions, "definitions（定义权威在 production，必须显式注入）"),
            runtime,
            Objects.requireNonNull(assetOccupancyLookup, "assetOccupancyLookup（D4-C 读口）"),
            Objects.requireNonNull(wageQuoteLookup, "wageQuoteLookup（D2 §6.5 读口）"),
            Objects.requireNonNull(participantAttributeLookup, "participantAttributeLookup（属性桥）"),
            expectedRevision);
    this.accounts = Objects.requireNonNull(accounts, "accounts（账户会话是会话状态，必须由调用方载入）");
    this.topology =
        Objects.requireNonNull(topology, "topology（M2.3：没有城市信息用 MarketTopology.singleHex）");
    this.plantingDrawsFirst = plantingDrawsFirst;
    this.parallelism = EconomyParallelism.requireNonNull(parallelism);
    this.profitCycle =
        definitions.modes().isEmpty() ? null : new EnterpriseProfitBook.CycleAccumulator();
  }

  /** ★ R2：本条会话的并行度（只读；见类注的"1 线程不是另一套实现"）。 */
  public EconomyParallelism parallelism() {
    return parallelism;
  }

  /**
   * ★★★ <b>D-032 W4：替换本 tick 的 Social 下一 tick 生存投影</b> —— 与 {@link #updateComposition(Map)} 同一条
   * "只读投影、不进状态"的纪律；由 app 在每次 {@link #step(long)} 之前从当刻 Social 现算后注入。
   */
  public void updateSurvivalSocialFacts(SurvivalSocialFacts next) {
    this.survivalSocialFacts =
        Objects.requireNonNull(next, "survivalSocialFacts（无 Social 投影请传 unavailable()）");
  }

  /** ★ D-032 W4：本 tick 的 Social 下一 tick 生存投影（只读；缺省 unavailable）。 */
  public SurvivalSocialFacts survivalSocialFacts() {
    return survivalSocialFacts;
  }

  /** ★ D-032 W4：最近一次 {@link #step(long)} 的 W3 逐义务只读读数（稳定序由 W3 保证；空表 = 当日无义务）。 */
  public List<WageSettlementReadout> lastWageSettlementReadouts() {
    return lastWageSettlementReadouts;
  }

  /**
   * ★ 当前状态快照（**会跑一次全量守卫并构造 {@link EconomyData}**）—— 只服务读口/测试；日循环内部请走 {@link #householdEconomies()}
   * 与 {@link #flows()}（P1.5：不在日循环里构造状态）。
   */
  public EconomyData data() {
    return session.preview();
  }

  /** ★ 家户行工作副本（**活视图**；日循环内读它，不构造 EconomyData）。 */
  public Map<HouseholdId, HouseholdEconomy> householdEconomies() {
    return session.sheet().householdEconomies();
  }

  /**
   * ★★ <b>家户人口组成的只读投影</b>（P2-A A3）：{@code household → (lot → count)}，由调用方（app 组合根）从 Social 的 {@code
   * Household.members} 现算后注入；<b>不进 Economy 状态、不进变更集</b>。组织/进入阶段用它 为"没有既有配额的家户"挑批次。缺省空表 ⇒ 那些路径按具名
   * SHORTAGE 退回（不伪造批次）。
   */
  public Map<HouseholdId, Map<PeopleLotId, Long>> composition() {
    return composition;
  }

  /** 更新人口组成投影（出生/死亡/迁移之后由协调器重建一份；不触发任何 Economy 状态写入）。 */
  public void updateComposition(Map<HouseholdId, Map<PeopleLotId, Long>> next) {
    Objects.requireNonNull(next, "next");
    Map<HouseholdId, Map<PeopleLotId, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<PeopleLotId, Long>> entry : next.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("composition 不得含 null 键/值");
      }
      frozen.put(
          entry.getKey(),
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    this.composition = java.util.Collections.unmodifiableMap(frozen);
  }

  /**
   * ★★ <b>Z7b：替换本轮的单位户集合</b>（app 组合根在推进前从 {@code Unit.households()} 现算注入；与 {@link
   * #updateComposition(Map)} 同一条“只读投影、不进状态”的纪律）。
   *
   * <p>★★ <b>R2（2026-10-11）</b>：集合的消费口径已收窄为"<b>只买不卖</b>"（军户/官吏户回到买盘；政府国库户由 economy
   * 的"只按授权下单"名单单独管）—— 判据的唯一拼写点 = {@code MarketRound.householdMarketRole}，本方法只管注入。
   *
   * <p>★ 语义是<b>替换</b>：单位/家户在 revision 边界才可能变，本轮推进内不变。
   */
  public void updateMarketExcludedHouseholds(Set<HouseholdId> next) {
    Objects.requireNonNull(next, "next");
    java.util.LinkedHashSet<HouseholdId> frozen = new java.util.LinkedHashSet<>();
    for (HouseholdId household : next) {
      if (household == null) {
        throw new IllegalArgumentException("marketExcludedHouseholds 不得含 null");
      }
      frozen.add(household);
    }
    this.marketExcludedHouseholds = java.util.Collections.unmodifiableSet(frozen);
  }

  /** ★ Z7b/R2：当前注入的单位户集合（只读；判定一律走 {@code MarketRound.householdMarketRole}）。 */
  public Set<HouseholdId> marketExcludedHouseholds() {
    return marketExcludedHouseholds;
  }

  /**
   * ★★ <b>R2：替换本轮的口岸实际管制力</b>（app 组合根在推进前从"管辖政府的口岸政策 × 口岸效率 × 暴露边"折算后注入； 与 {@link
   * #updateComposition(Map)} 同一条"只读投影、不进状态"的纪律）。
   *
   * <p>★ <b>时序（本批的冻结口径）</b>：gov 效率在<b>当日经济结算之后</b>才算（它要读当日结算后的家户劳动），而市场轮在 {@link #step(long)}
   * <b>之内</b> ⇒ 本批注入的口岸管制作用于<b>下一次</b>市场轮（一 tick 滞后）。这与既有 {@code GovEfficiencyModifier} 的"逐 tick
   * 注入、当场消费"同族；账本 §关键判断记了这条与理由。
   */
  public void updatePortEnforcement(PortEnforcementInput next) {
    Objects.requireNonNull(next, "portEnforcement 不得为 null（没有口岸面就给 PortEnforcementInput.none()）");
    this.portEnforcement = next;
  }

  /** ★ R2：本条会话当前的口岸实际管制力（只读；缺省 none）。 */
  public PortEnforcementInput portEnforcement() {
    return portEnforcement;
  }

  /**
   * ★★ <b>P-T1b：替换本轮的三层税税率与收税政府</b>（app 组合根按口岸政策 × 暴露边权重折算后注入；与 {@link
   * #updatePortEnforcement(PortEnforcementInput)} 同一条"只读投影、不进状态"的纪律）。
   *
   * <p>★ <b>时序</b>与口岸管制力相同：折算读的是当日结算之后的政府效率，故注入值作用于<b>下一次</b>市场轮（一 tick 滞后）。
   */
  public void updatePortTax(PortTaxInput next) {
    Objects.requireNonNull(next, "portTax 不得为 null（没有税就给 PortTaxInput.none()）");
    this.portTax = next;
  }

  /** ★ P-T1b：本条会话当前的三层税税率（只读；缺省 none = 一分不收）。 */
  public PortTaxInput portTax() {
    return portTax;
  }

  /**
   * ★★ <b>P-T1d：替换本轮的政府采购优先级</b>（app 组合根从"该政府要求管控市场的政策 × 本政府编制劳动力折成的行政力池"注入； 与 {@link
   * #updatePortTax(PortTaxInput)} 同一条"只读投影、不进状态"的纪律）。
   *
   * <p>★ <b>时序</b>与口岸管制力/税相同：折算读的是当日结算之后的编制劳动力，故注入值作用于<b>下一次</b>市场轮（一 tick 滞后）。
   */
  public void updateProcurementPriority(ProcurementPriorityInput next) {
    Objects.requireNonNull(
        next, "procurementPriority 不得为 null（没有政府管控就给 ProcurementPriorityInput.none()）");
    this.procurementPriority = next;
  }

  /** ★ P-T1d：本条会话当前的政府采购优先级（只读；缺省 none = 没有政府要求管控市场）。 */
  public ProcurementPriorityInput procurementPriority() {
    return procurementPriority;
  }

  /** ★★ <b>Z7c：最近一次 {@link #step(long)} 是否关账了至少一个产业周期</b>（只读；见字段注释）。默认 {@code false}。 */
  public boolean lastCycleClosed() {
    return lastCycleClosed;
  }

  /** ★★ 唯一账户会话（推进前载入、推进中就地更新、推进后整体落回 actor）。 */
  public AccountSession accounts() {
    return accounts;
  }

  /**
   * ★★★ <b>D-032 U2：本段 Advance 的生产运行时只读投影</b>（组合根新建、推进结束后由它取走本域变更集）。
   *
   * <p>★★ <b>为什么要开这个读口</b>：① 组合根在推进收尾处要调 {@code runtime.changeSet()} 把 production
   * 本域变更集汇入同一次提案（I11：与 ACTOR 账户同一条 Revision）；② app 侧读实例/方案的路径（ {@code ApiViews}/{@code
   * ToolSupport}/{@code CrisisMonitor}…）改读这份当刻投影（§9 第 3 条 / U9）。
   *
   * <p>★★ <b>B12 登记：EI_EXPOSE_REP 是孤立例外，不是缺陷</b>（§14.25.3 裁定四十五第 3 条）—— 理由见 {@link
   * EconomySession#productionRuntime()} 的同一条账：返回的是**注入的契约端口接口**（economy 调 production
   * 的唯一通道），防御性拷贝语义为零；例外由下面这条**具名** {@code @SuppressFBWarnings} 登记（不是静默排除）。
   *
   * @return 本会话注入的运行时；<b>未注入时为 null</b>（只做家户/债务的窄会话 —— 调用方须自行 fail-closed）
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "孤立例外（§14.25.3 裁定四十五第 3 条）：返回的是组合根注入的契约端口接口 ProductionRuntime —— economy 读/写生产候选态的唯一通道；接口没有拷贝契约，拷贝掉就不再是同一份权威候选态（changeSet()/beginTick() 会丢失），调用面限于 economy 域内与组合根收尾。登记见 impl-ledger B12")
  public io.mosire.simos.production.contract.ProductionRuntime productionRuntime() {
    return runtime;
  }

  /**
   * ★★ <b>本会话按需创建、尚未登记的产业定义</b>（瞬态候选态）：app 组合根在 Advance 收尾时投影成 production 的 ChangeSet，与经济实例变更集在同一次
   * Advance 原子提交（D-031 §9.1）。空表 = 本段没有新定义。
   */
  public Map<
          io.mosire.simos.economy.api.id.IndustryId,
          io.mosire.simos.production.contract.IndustryDefinitionView>
      createdIndustryDefinitions() {
    return session.createdIndustryDefinitions();
  }

  /** 本期的流水累加器（**只读视图**；键序 = 行的插入序）。 */
  public Map<HouseholdId, FlowRow> flows() {
    return session.flowsView();
  }

  /**
   * ★★ <b>P2-D：把辖区日税的粮口径实缴记进当行流水读数</b>（协调器线程；唯一写法见 {@link
   * FlowRow#withAdditionalGrainTaxPaid(long)}）。
   *
   * <p>它<b>只改流水读数</b>：真正的粮去哪了由账户会话（{@link #accounts()} 的 {@link AccountSession#commit}）
   * 落定——两者是同一天、同一笔事实的两个面，调用方必须先扣账再调本方法。缺该家户的流水行 ⇒ 返回 {@code false}（不造行、不抛；调用方按"这一笔没有流水读数"具名记
   * DEBUG），因为 GOV 家户等零人口行在 某些旧档里可能没有流水行。
   *
   * @return 真的写进了流水 ⇒ true；该家户没有流水行 ⇒ false（调用方自己记具名缺口）
   */
  public boolean recordTaxPaid(HouseholdId household, long grainMilli) {
    Objects.requireNonNull(household, "household");
    if (grainMilli < 0L) {
      throw new IllegalArgumentException("recordTaxPaid 的 grainMilli 不得为负: " + grainMilli);
    }
    if (grainMilli == 0L) {
      return true;
    }
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    FlowRow flow = flows.get(household);
    if (flow == null) {
      return false;
    }
    flows.put(household, flow.withAdditionalGrainTaxPaid(grainMilli));
    return true;
  }

  /**
   * ★★ <b>P2-D：把一条行政危机信号写进本会话的 {@code crisisSignals}</b>（同 {@code (hex,kind)} 覆盖 = 保留最新）。
   *
   * <p>地图/负荷明文不进日志；本方法只把调用方已构造好的 {@link HexCrisisSignal} 放进会话工作表， 与 {@code EconomySettlement}
   * 自己的危机信号共用同一个组件，不另立第二本信号账。
   */
  public void putCrisisSignal(HexCrisisSignal signal) {
    Objects.requireNonNull(signal, "signal");
    session.sheet().crisisSignals().put(signal.id(), signal);
  }

  /**
   * ★★ <b>P5：本会话的死亡按人口比例删债读数</b>（瞬态；键 = 债务人，值 = Σ 逐笔删债本金）——只读视图。
   *
   * <p>★★ <b>它不是第二本账</b>：合同表的本金下降才是权威事实；本读口只回答"这次推进里删掉多少债"（按家户）， 供 app 侧/P9 核对 {@code 债务 = 发行 − 还款
   * − 删债}（利息另列）。它不进 {@link EconomyData}/变更集/{@code Codec}； 会话结束（{@link #finish()}）后随对象一起丢弃。
   */
  public Map<HouseholdId, Long> debtWriteOffs() {
    return session.debtWriteOffsView();
  }

  /**
   * ★★ <b>八个协调器账户视图（M1：包内可见，不是 public API）</b> —— 只服务同包日结算代码的既有"键 → 内层表"写法。
   *
   * <p>★ <b>为什么不 public</b>：这些视图经 {@link AccountSession} 的 owner 守卫访问活表；把它们开成 public，等于给 app/worker
   * 递一条"拿到视图就能读写活账本"的接缝。R1/R2 的结构契约是：worker 只能拿 {@link AccountSession#snapshot()}（不可变）与 {@code
   * AccountIntentBuffer}（线程本地）；跨分区的写只能经 {@link AccountSession#commit}。★ 内层表本身是只读活视图，即使被同包代码误传给
   * worker，写也会抛。
   */
  Map<HouseholdId, Map<CommodityId, Long>> householdGoods() {
    return accounts.householdGoods();
  }

  /** 家户货币协调器视图（包内；见上面的八个视图说明）。 */
  Map<HouseholdId, Map<CurrencyId, Long>> householdMoney() {
    return accounts.householdMoney();
  }

  /** 家户商品冻结协调器视图（包内）。 */
  Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods() {
    return accounts.householdFrozenGoods();
  }

  /** 家户货币冻结协调器视图（包内）。 */
  Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney() {
    return accounts.householdFrozenMoney();
  }

  /**
   * ★★ <b>结算一天</b>（{@code day} 是绝对世界日）：与 {@code EconomySettlement.settleOneDay} 是同一条实现， 并交回当天的
   * {@link ProductionLedger}（产出的产权条目交给看得见 actor 的那一侧落账）。
   *
   * <p>★ <b>调用契约（2026-10-09 家户结构修复 Batch 3 起）</b>：本日应收的调用方必须先在当天调用 {@link #updateNaturalNeeds(Map)}
   * 注入逐户需求 —— 消费步只读它，不再按 {@code population} 现算。
   */
  public ProductionLedger step(long day) {
    if (day < 1L) {
      throw new IllegalArgumentException("结算的日号必须 ≥ 1（创世是第 0 天）: " + day);
    }
    // ★★ D-032（D2 直接删）：`PRODUCTION_MODIFIER_INJECTED` 每日 INFO 已随注入链退役——没有注入集，
    //   就没有"本日注入了几个、几个非中性"这条读数。生产侧的对应读数改由 production 的
    //   `PRODUCTION_CYCLE_CLOSED` / `PRODUCTION_EXPERIENCE_*` 事件承担（见 ProductionLog.runtime()）。
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
    // ★ Z7c：关账事实在 step 前抓一份、step 后再读一次（与 EconomySettlement 的 anyCycleClosed 同源：
    //   有任一周期关账 ⇒ lastClosedCycle 从旧值/空变成 currentCycle）。只开读数，不改行为。
    OptionalLong closedBefore =
        session.sheet().meta().map(meta -> meta.lastClosedCycle()).orElseGet(OptionalLong::empty);
    EconomySettlement.settleOneDayInto(
        session,
        day,
        accounts,
        topology,
        plantingDrawsFirst,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds,
        portEnforcement,
        portTax,
        procurementPriority,
        survivalSocialFacts);
    lastWageSettlementReadouts =
        session
            .base()
            .meta()
            .flatMap(meta -> WageSettlementReadoutFeed.last(meta.mapId(), day))
            .orElse(List.of());
    OptionalLong closedAfter =
        session.sheet().meta().map(meta -> meta.lastClosedCycle()).orElseGet(OptionalLong::empty);
    this.lastCycleClosed = !closedBefore.equals(closedAfter);
    MarketReport report = ledger.marketReport();
    if (report != null) {
      lastMarketReport = report;
    }
    ProductionLedger result = ledger.toLedger();
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "STEPPER_STEP",
                  EconomyLogSource.ECONOMY_DAY,
                  "day",
                  day,
                  "rows",
                  session.sheet().householdEconomies().size(),
                  "units",
                  // ★★ D-032 U2：实例数的权威在 production 的当刻候选态（本会话的运行时只读投影）；
                  //   未注入运行时（窄会话）⇒ 本读数记为 -1（**不**把"没有运行时"写成 0 —— 那与"没有实例"不可分）。
                  runtime.units().size(), // ★ B3：runtime 恒非 null（构造器保证）
                  "transfers",
                  result.transfers().size(),
                  "marketReport",
                  report != null,
                  "shipments",
                  session.sheet().shipments().size()));
    }
    return result;
  }

  /**
   * ★★ <b>P2-A §13.4：每个 tick 重算家户时间预算</b>（毫小时）—— 由协调器从 Social 人口组成 × 系数表现算后传入； 本方法把它写进 {@code
   * HouseholdEconomy.laborMilli} 并把超预算的配额按比例缩回（不变量在下一 revision 边界仍成立）。
   */
  public void recomputeLaborBudgets(Map<HouseholdId, Long> budgetsByHousehold) {
    EconomySettlement.applyLaborBudgetsInto(session, budgetsByHousehold);
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：注入当日逐户逐商品自然需求</b> —— 由 app 从 Social 逐户展开后传入； 本方法把它写进 {@code
   * HouseholdEconomy.naturalNeeds}（当日物化读模型），日结算消费步直接读它，不再按 {@code population} 另算一份。
   *
   * <p>★ 写口语义与拒绝口径见 {@link EconomySettlement#applyNaturalNeedsInto(EconomySession, Map)}：入参 key
   * 缺经济行 ⇒ 具名拒；空表 = 无需求；本方法**不**累加 {@code cycleNaturalNeedMilli} （周期累加在消费步按粮需求执行一次）。
   */
  public void updateNaturalNeeds(Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold) {
    EconomySettlement.applyNaturalNeedsInto(session, needsByHousehold);
  }

  // ★★ D-032（D2 直接删）：`updateProductionModifiers(List<ProductionEfficiencyModifier>)` 与
  //   `PRODUCTION_MODIFIER_REJECTED` 日志已随"文化 → 生产效率倍率"整条通路退役（用户 U5）。
  //   ★ 新口径：效率 = 家户熟练度经方案公式解释出的倍率，归 production；economy 不再有可注入的修正集，
  //   也没有"未知 unit ⇒ 具名拒"这一层（实例的存在性与引用完整性改由 production 的候选态只读校验承担）。

  /**
   * ★★ 把**外部（社会/命令面）报来的**逐批次出生/死亡回写经济侧（行人口、劳动配额与流水；成员份额由 Social 权威维护，本侧不再持副本）。
   *
   * <p>★ <b>语义（W3-fix 2026-10-11）</b>：这是**接收**入口，不是判死入口 —— 经济侧不判定死亡、不按缺口处死（见 {@link
   * EconomySettlement#applyPopulationChange}）。
   */
  public void applyPopulationChange(List<LotChange> changes) {
    Objects.requireNonNull(changes, "changes");
    if (!changes.isEmpty()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "POPULATION_CHANGE_APPLIED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "changes",
                  changes.size()));
    }
    EconomySettlement.applyPopulationChangeInto(session, changes);
  }

  /**
   * ★★ <b>2026-10-09 每 tick 生死 Batch B：把 Social 结算出的逐家户净人口变化落到经济行</b>（{@code population +=
   * delta}）——委托 {@link EconomySettlement#applyHouseholdPopulationDeltasInto(EconomySession,
   * Map)}；键缺行/结果为负都具名拒，不静默跳过。
   *
   * <p>★★ <b>调用方次序（app 日循环）</b>：先 {@link #updateComposition(Map)} + {@link
   * #recomputeLaborBudgets(Map)} + {@link #updateNaturalNeeds(Map)} 用**新** Social 刷新投影，再调本方法加
   * delta，最后 {@link #step(long)}。★ 劳动预算已在前面按新 Social 重算，故本方法**不动** {@code laborMilli}
   * ——在这里再按人口比例缩一遍会把 Social 的当日权威缩两次。
   *
   * <p>★★ <b>本重载不带生死流水</b>：调用方是"人在户与户之间搬"的两条路（P0 迁移桥 outbox、Z7d-2 逃亡）—— 搬人不产生出生/死亡。 逐 tick 的生死走三参重载
   * {@link #applyHouseholdPopulationDeltas(Map, Map, Map)}。★ <b>W8（2026-10-11）</b>：两表恒空 ⇒ 五参重载的户绝判据
   * （社会报来的死亡 &gt; 0）不成立 ⇒ 本路<b>不销账</b>，只在把某户搬空时记一条"债务保留" （{@code
   * HOUSEHOLD_EMPTIED_DEBT_RETAINED}）—— 这就是"迁移不销账"那条口径的落点。
   *
   * @param deltas 逐家户净人口变化（出生 − 死亡；只含非 0 项）；不得为 null
   */
  public void applyHouseholdPopulationDeltas(Map<HouseholdId, Long> deltas) {
    Objects.requireNonNull(deltas, "deltas");
    EconomySettlement.applyHouseholdPopulationDeltasInto(session, deltas);
  }

  /**
   * ★★★ <b>W3-fix（2026-10-11）：把 Social（人口权威）报来的逐户净变化 + 逐户出生/死亡一起落到经济侧</b> ——委托 {@link
   * EconomySettlement#applyHouseholdPopulationDeltasInto(EconomySession, Map, Map, Map, long)}。
   *
   * <p>★★ <b>为什么要把出生/死亡原值也传进来</b>：只传净变化时 {@code FlowRow.births}/{@code FlowRow.deaths} 永远是 0，
   * 经济侧的危机读数（{@code CrisisMonitor} 的 MORTALITY 灯）与人口守恒读数因此没有输入；净变化在一户同时生死时只报差， 反推不出死亡数。★
   * 经济侧收到后**只落账**（唯一写口 {@code EconomySettlement.withLifecycle}），**不判定死亡、不缩劳动**。 ★★
   * <b>W8（2026-10-11）</b>：若某户因**死亡**人口归零（{@code deathsByHousehold} &gt; 0 且写后人口 == 0）⇒ 追加
   * <b>双向销账</b>（{@code EconomySettlement.writeOffExtinctHouseholdDebts}，复用 P5 写口与 {@code
   * debtWriteOffs} 累加器）与具名 INFO {@code HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF}。
   *
   * <p>★ <b>拒绝语义</b>：三张表的 null 键/值、0 值、缺经济行，以及"净变化 ≠ 出生 − 死亡"都具名拒（ERROR + 异常，写任何一行之前）。
   *
   * @param deltas 逐家户净人口变化（出生 − 死亡；只含非 0 项）；不得为 null
   * @param birthsByHousehold 逐家户出生原值（只含 &gt; 0 的家户）；不得为 null
   * @param deathsByHousehold 逐家户死亡原值（只含 &gt; 0 的家户；含饥荒那一份）；不得为 null
   * @param day ★★ W3-fix2（D4，2026-10-11）：世界日 —— 落进 {@code HOUSEHOLD_VITAL_WRITEBACK} 的 {@code day}
   *     字段，使这条"今天社会报来多少死亡"的读数**可按天检索/对账**（改前 162/162 条都没有该字段）；不得为负
   */
  public void applyHouseholdPopulationDeltas(
      Map<HouseholdId, Long> deltas,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      long day) {
    Objects.requireNonNull(deltas, "deltas");
    Objects.requireNonNull(birthsByHousehold, "birthsByHousehold");
    Objects.requireNonNull(deathsByHousehold, "deathsByHousehold");
    EconomySettlement.applyHouseholdPopulationDeltasInto(
        session, deltas, birthsByHousehold, deathsByHousehold, day);
  }

  /**
   * ★★ <b>P8：把一份迁移计划落进本会话的经济侧工作副本</b>（人口 / 劳动 / 债务；见 {@link LotMigrationBook}）。
   *
   * <p>★ 本方法只转发经济侧写口；<b>不</b>动 social 批次、{@code Membership}、{@code HouseholdLaborCommitment}/{@code
   * LaborSupply} 与 actor 账户（那是 P9 跨切片协调器的接线内容）。空计划 ⇒ 一字不改。
   */
  public void applyMigrations(List<LotMigration> migrations, long day) {
    Objects.requireNonNull(migrations, "migrations");
    if (!migrations.isEmpty()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MIGRATION_APPLIED_INTO_STEPPER",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "count",
                  migrations.size()));
    }
    LotMigrationBook.applyInto(session, migrations, day);
  }

  /** ★★ 最近一次 {@link #step(long)} 的区域市场报告（没开市 ⇒ {@link Optional#empty()}）。 */
  public Optional<MarketReport> lastMarketReport() {
    return Optional.ofNullable(lastMarketReport);
  }

  /**
   * ★★ <b>P0（2026-10-10）：本会话已记但尚未被 App 取走的迁移人口 outbox</b>（只读视图；保序 = move 执行序）。
   *
   * <p>由 {@link ModeMigrationSettlement} 在每笔 move 成功落账后追加；App 的 {@code
   * PopulationEconomyTimeParticipant} 在 {@code step(day)} 之后用 {@link
   * #drainPendingPopulationTransfers()} 取走并翻译成 Social 工单。
   */
  public List<EconomyPopulationTransfer> pendingPopulationTransfers() {
    return session.pendingPopulationTransfers();
  }

  /**
   * ★★ <b>P0：App 每日取走并清空迁移人口 outbox</b>（返回保序不可变快照；空 ⇒ {@link List#of()}）。
   *
   * <p>★ 同一条事实只被翻译一次：取走后本会话记录清零；整次 advance 失败 ⇒ 会话丢弃，不落 revision（outbox 是瞬态）。
   */
  public List<EconomyPopulationTransfer> drainPendingPopulationTransfers() {
    return session.drainPendingPopulationTransfers();
  }

  /**
   * ★★★ <b>W3（2026-10-11）：本会话已记但尚未被 App 取走的逐户缺口 outbox</b>（只读视图；保序 = 家户处理序）。
   *
   * <p>由 {@link EconomySettlement} 在当日"全部救济通道之后"的<b>报缺口</b>阶段追加；App 的 {@code
   * PopulationEconomyTimeParticipant} 在 {@code step(day)} 之后用 {@link #drainPendingHouseholdGaps()}
   * 取走、 翻成 Social 的饥荒输入（S-11 第②半）。
   */
  public List<EconomyHouseholdGap> pendingHouseholdGaps() {
    return session.pendingHouseholdGaps();
  }

  /**
   * ★★★ <b>W3：App 每日取走并清空逐户缺口 outbox</b>（返回保序不可变快照；空 ⇒ {@link List#of()}）。
   *
   * <p>★ 同一条事实只被翻译一次：取走后本会话记录清零；整次 advance 失败 ⇒ 会话丢弃，不落 revision（outbox 是瞬态）。 ★ 逐日现算、逐日取走 ⇒ 即使把
   * {@code advance(0,360)} 切成 3 段，每一天看到的仍是<b>那一天自己</b>的缺口（B2 分段等价）。
   */
  public List<EconomyHouseholdGap> drainPendingHouseholdGaps() {
    return session.drainPendingHouseholdGaps();
  }

  /**
   * ★★ <b>Z7d-2：按 id 显式缩减/释放 {@code GOV_SERVICE} 承诺</b>（逃亡执行的唯一经济侧写口）。
   *
   * <p>★★ <b>为什么是独立窄口而不是通用承诺写口</b>：本方法只服务"逃亡/解职这类显式动作已经算好每一条要变成多少"的场景—— 只允许 <b>GOV_SERVICE
   * 行</b>、<b>只允许减（或释放）</b>、<b>id 必须已存在</b>。它<b>不</b>创建新行、不把 PRODUCTION 改成 GOV_SERVICE、不静默改
   * kind；任何越界（未知 id / kind 不符 / 增量 / 负数）都具名 {@link IllegalArgumentException}。 写出的 {@code 0} =
   * release（删该行）；其余值保留原有 group/actor/activity/period/kind 逐值不变。
   *
   * <p>★ 本方法只改会话工作表；最终状态仍由 {@link #finish()} → {@code EconomyData} 构造期全量守卫把关 （{@code Σ PRODUCTION ≤
   * budget − min(Σ GOV_SERVICE, budget)} 等）。
   *
   * @param newLaborMilliByCommitmentId 每条 GOV_SERVICE 承诺的新量（0 = 释放；0..旧值）；不得为 null/含 null
   */
  public void applyGovServiceCommitmentReductions(
      Map<LaborAllocationId, Long> newLaborMilliByCommitmentId) {
    Objects.requireNonNull(newLaborMilliByCommitmentId, "newLaborMilliByCommitmentId（没有缩减用空表）");
    if (newLaborMilliByCommitmentId.isEmpty()) {
      return;
    }
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments =
        session.sheet().laborCommitments();
    List<LaborAllocationId> ids = new ArrayList<>(newLaborMilliByCommitmentId.size());
    for (Map.Entry<LaborAllocationId, Long> entry : newLaborMilliByCommitmentId.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("GOV_SERVICE 缩减表的键与值都不得为 null: " + entry.getKey());
      }
      ids.add(entry.getKey());
    }
    ids.sort(Comparator.comparing(LaborAllocationId::value));
    for (LaborAllocationId id : ids) {
      Long requested = newLaborMilliByCommitmentId.get(id);
      HouseholdLaborCommitment current = commitments.get(id);
      if (current == null) {
        throw new IllegalArgumentException("GOV_SERVICE 缩减指向不存在的承诺行: " + id.value());
      }
      if (current.kind() != LaborCommitmentKind.GOV_SERVICE) {
        throw new IllegalArgumentException(
            "GOV_SERVICE 缩减只能作用于 GOV_SERVICE 行（不是 PRODUCTION）: "
                + id.value()
                + " kind="
                + current.kind());
      }
      long next = requested.longValue();
      if (next < 0L || next > current.laborMilli()) {
        throw new IllegalArgumentException(
            "GOV_SERVICE 缩减必须 ∈ [0, 旧值]（逃亡只减不增）: id="
                + id.value()
                + " old="
                + current.laborMilli()
                + " new="
                + next);
      }
      if (next == 0L) {
        commitments.remove(id);
      } else if (next < current.laborMilli()) {
        commitments.put(
            id,
            new HouseholdLaborCommitment(
                id,
                current.group(),
                current.household(),
                current.actor(),
                current.activity(),
                next,
                current.period(),
                current.kind(),
                current.productionRole()));
      }
    }
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "ECONOMY_GOV_SERVICE_COMMITMENT_REDUCED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "rows",
                  newLaborMilliByCommitmentId.size()));
    }
  }

  /** 收尾：把累加器挂上，交出可以进变更集的最终状态（账户在 {@link #accounts()} 里，不在这个状态里）。 */
  public EconomyData finish() {
    try {
      return session.build();
    } finally {
      close(); // ★ R2：自建的结算线程池随本次推进一起收掉（注入的池由调用方关；重复 close 幂等）。
    }
  }

  /**
   * ★★ <b>关闭本会话自建的结算线程池</b>（R2 生命周期）：由 {@link #finish()} 自动调用，也供调用方在 <b>异常路径</b>用 {@code
   * try/finally} 收口（{@code simulateWorld} 的日循环抛错时 {@code finish()} 根本走不到）。
   *
   * <p>★ 幂等：{@link EconomyParallelism#close()} 对已关/未自建的池是安全操作；注入的池不归本类关。
   */
  @Override
  public void close() {
    parallelism.close();
  }

  @Override
  public String toString() {
    return "EconomyDayStepper[" + parallelism + "]";
  }
}
