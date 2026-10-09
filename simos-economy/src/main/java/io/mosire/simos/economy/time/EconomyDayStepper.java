package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.api.production.ProductionEfficiencyModifier;
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
  private final int famineMortalityPerMille;
  private final MarketTopology topology;

  private final EconomySession session;
  private final AccountSession accounts;

  /** 「家户 → (lot → count)」只读投影（P2-A A3；由调用方从 Social 注入，不进任何 Economy 状态）。 */
  private Map<HouseholdId, Map<PeopleLotId, Long>> composition = Map.of();

  /**
   * ★★ <b>退出商品市场的单位户集合</b>（由 app 组合根从 {@code Unit.households()} 现算后注入；R1 起**只**含单位户 —— 政府国库户
   * 回到市场且只按授权下单，见 {@code GovernmentMarketMandatePlan}；economy 侧在日结算里并入 {@code
   * EconomyData.governments()} 的国库户）。缺省空集 = 只排政府国库户；不进任何 Economy 状态、不进变更集。
   */
  private Set<HouseholdId> marketExcludedHouseholds = Set.of();

  /** ★★ R2：本会话的并行度（默认单线程退化路径；{@link #finish()} 关掉自建的池）。 */
  private final EconomyParallelism parallelism;

  /**
   * ★★ <b>P10.2：本周期利润/迁移累加器</b>（{@code modes} 非空才有；逐日喂当天账本，关账日 ⑦⑧⑨ 后在 {@code EconomySettlement}
   * 内复位）。旧档 {@code modes} 为空时恒为 {@code null} ⇒ 逐值不变。
   */
  private final EnterpriseProfitBook.CycleAccumulator profitCycle;

  /** ★ M2.3/M2.4：最近一次 step 的区域市场报告（瞬态；L3 读数接它）。 */
  private MarketReport lastMarketReport;

  /**
   * ★★ <b>Z7c：最近一次 {@link #step(long)} 是否有关账的产业周期</b>（瞬态会话读数）：语义 = step 前后 {@code
   * EconomyMeta.lastClosedCycle} 发生变化，与 {@code EconomySettlement} 内部的 {@code anyCycleClosed} 同一事实。
   * app 组合根用它把 remittance 落在仓库既有的“关账日”上；本类只把既有事实开成只读读数，<b>不</b>改任何结算行为、不进状态/变更集。
   */
  private boolean lastCycleClosed;

  /** ★★ <b>组合根入口（区域拓扑版）</b>：账户会话由调用方载入；两个行为旋钮取出厂默认值。 */
  public EconomyDayStepper(EconomyData base, AccountSession accounts, MarketTopology topology) {
    this(
        base,
        accounts,
        topology,
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /** ★ 单模块/手搭夹具：没有城市信息 ⇒ 每格一区、不跨区（M2-L1 的既有行为）。 */
  public EconomyDayStepper(EconomyData base, AccountSession accounts) {
    this(
        base,
        accounts,
        MarketTopology.singleHex(base.markets()),
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE,
        EconomyParallelism.singleThreaded());
  }

  /**
   * ★ <b>两个行为旋钮可注入</b>（公开：契约层的两个常量是唯一拼写点）：先播种还是先吃饭、以及致死率 —— 理由逐字见 {@code
   * EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION} 与 {@code
   * EconomySettlement.FAMINE_MORTALITY_PER_MILLE}。
   */
  public EconomyDayStepper(
      EconomyData base,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille) {
    this(
        base,
        accounts,
        topology,
        plantingDrawsFirst,
        famineMortalityPerMille,
        EconomyParallelism.singleThreaded());
  }

  /**
   * ★★ <b>R2 完整入口</b>：并行度可注入。{@code workerCount} 只决定"谁先算完"——分区函数、提交序与 tie-break 都由固定的 {@link
   * EconomyParallelism#STRUCTURAL_PARTITIONS} 决定，1/4/8 线程共用同一套。
   *
   * <p>★ 池的生命周期归本对象：{@link #finish()} 会关掉 {@link EconomyParallelism#of(int)} 自建的池； 注入的池由调用方自己关。
   */
  public EconomyDayStepper(
      EconomyData base,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille,
      EconomyParallelism parallelism) {
    this.lastCycleClosed = false;
    this.session = new EconomySession(Objects.requireNonNull(base, "base"));
    this.accounts = Objects.requireNonNull(accounts, "accounts（账户会话是会话状态，必须由调用方载入）");
    this.topology =
        Objects.requireNonNull(topology, "topology（M2.3：没有城市信息用 MarketTopology.singleHex）");
    this.plantingDrawsFirst = plantingDrawsFirst;
    this.famineMortalityPerMille = famineMortalityPerMille;
    this.parallelism = EconomyParallelism.requireNonNull(parallelism);
    this.profitCycle = base.modes().isEmpty() ? null : new EnterpriseProfitBook.CycleAccumulator();
  }

  /** ★ R2：本条会话的并行度（只读；见类注的"1 线程不是另一套实现"）。 */
  public EconomyParallelism parallelism() {
    return parallelism;
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
   * ★★ <b>Z7b：替换本轮“退出商品市场”的单位户集合</b>（app 组合根在推进前从 {@code Unit.households()} 现算注入；与 {@link
   * #updateComposition(Map)} 同一条“只读投影、不进状态”的纪律）。
   *
   * <p>★ 语义是<b>替换</b>：单位/家户在 revision 边界才可能变，本轮推进内不变；政府国库户由 economy 在每个日结算里从 {@code
   * base.governments()} 自行并入，本方法只负责 economy 编译期看不见的 unit 切片。
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

  /** ★ Z7b：当前注入的单位户排除集（只读；政府国库户由日结算另并入）。 */
  public Set<HouseholdId> marketExcludedHouseholds() {
    return marketExcludedHouseholds;
  }

  /** ★★ <b>Z7c：最近一次 {@link #step(long)} 是否关账了至少一个产业周期</b>（只读；见字段注释）。默认 {@code false}。 */
  public boolean lastCycleClosed() {
    return lastCycleClosed;
  }

  /** ★★ 唯一账户会话（推进前载入、推进中就地更新、推进后整体落回 actor）。 */
  public AccountSession accounts() {
    return accounts;
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
    // ★★ Z1（§8）：PRODUCTION_MODIFIER_INJECTED INFO，每日一条（day/count/非中性数）—— 修正注入集在当日 step 之前由
    //   协调器替换，这里带着 day 落地；未注入 ⇒ count=0（中性）。
    Map<ProductionUnitId, ProductionEfficiencyModifier> injected =
        session.productionModifiersView();
    long nonNeutral = 0L;
    for (ProductionEfficiencyModifier modifier : injected.values()) {
      if (modifier.modifierPerMille() != 1_000L) {
        nonNeutral++;
      }
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_MODIFIER_INJECTED",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                day,
                "count",
                injected.size(),
                "nonNeutral",
                nonNeutral));
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
        famineMortalityPerMille,
        ledger,
        parallelism,
        profitCycle,
        composition,
        marketExcludedHouseholds);
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
                  session.sheet().units().size(),
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

  /**
   * ★★ <b>Z1（§5.2）：替换本 tick 的程序内修正参数注入集</b>（由 app 组合根在当日 {@link #step(long)} <b>之前</b>调用，与 {@link
   * #updateNaturalNeeds(Map)} / {@link #recomputeLaborBudgets(Map)} 同日序先例）。
   *
   * <p>语义：<b>替换</b>而非累加；未注入的 unit = 1000‰（中性）；当日结算消费后由 Z2 的结算路径清空（本区只提供会话暂存）。
   * 机制的输入必须是<b>已持久化状态的纯函数</b>（本批不提供持久化修正表，§5.2）。
   *
   * <p>★★ <b>具名拒绝（INFO + {@link IllegalArgumentException}，同 updateNaturalNeeds 的 fail-closed
   * 口径）</b>：
   *
   * <ul>
   *   <li>{@code modifiers == null} / 含 null 项 ⇒ 拒（不猜"空"）；
   *   <li>未知 unit（不在当前 {@code units} 表）⇒ 拒 {@code unknown-unit}；
   *   <li>同一 unit 重复出现 ⇒ 拒 {@code duplicate-unit}。
   * </ul>
   *
   * <p>★ <b>越界（不属于 [0,2000]）在本方法不可达</b>：{@link ProductionEfficiencyModifier} 的构造期守卫已经
   * fail-closed（§5.1）—— 本方法收到的 record 必然合法；命令边界之外的"拒绝日志"因此只覆盖本方法能看见的三种。
   *
   * @param modifiers 本 tick 的全量注入集（可空列表 = 全部 unit 中性）；不得为 null、不得含 null/重复/未知 unit
   */
  public void updateProductionModifiers(List<ProductionEfficiencyModifier> modifiers) {
    if (modifiers == null) {
      logProductionModifierRejected("null-modifier-list", null, null);
      throw new IllegalArgumentException(
          "updateProductionModifiers 的 modifiers 不得为 null（没有注入用空列表）");
    }
    Map<ProductionUnitId, ProductionEfficiencyModifier> replacements = new LinkedHashMap<>();
    // ★ unitsOrBase：只判存在，不为一次查询整表拷一份工作副本。
    Map<ProductionUnitId, ?> units = session.sheet().unitsOrBase();
    for (ProductionEfficiencyModifier modifier : modifiers) {
      if (modifier == null) {
        logProductionModifierRejected("null-modifier", null, null);
        throw new IllegalArgumentException("updateProductionModifiers 不得含 null 修正项");
      }
      ProductionUnitId unit = modifier.unit();
      if (!units.containsKey(unit)) {
        logProductionModifierRejected("unknown-unit", unit, modifier.modifierPerMille());
        throw new IllegalArgumentException(
            "updateProductionModifiers 指向未知 unit（不在 units 表）: " + unit.value());
      }
      if (replacements.putIfAbsent(unit, modifier) != null) {
        logProductionModifierRejected("duplicate-unit", unit, modifier.modifierPerMille());
        throw new IllegalArgumentException(
            "updateProductionModifiers 同一 unit 重复注入: " + unit.value());
      }
    }
    session.replaceProductionModifiers(replacements);
  }

  /**
   * ★★ Z1（§8）：{@code PRODUCTION_MODIFIER_REJECTED} INFO（reason/unit/modifierPerMille；缺值记 {@code
   * -}）。
   */
  private static void logProductionModifierRejected(
      String reason, ProductionUnitId unit, Long modifierPerMille) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_MODIFIER_REJECTED",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "reason",
                reason,
                "unit",
                unit == null ? "-" : unit.value(),
                "modifierPerMille",
                modifierPerMille == null ? "-" : modifierPerMille));
  }

  /** ★★ 把逐批次出生/死亡回写经济侧（行人口、劳动配额与流水；成员份额由 Social 权威维护，本侧不再持副本）。 */
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
   * @param deltas 逐家户净人口变化（出生 − 死亡；只含非 0 项）；不得为 null
   */
  public void applyHouseholdPopulationDeltas(Map<HouseholdId, Long> deltas) {
    Objects.requireNonNull(deltas, "deltas");
    EconomySettlement.applyHouseholdPopulationDeltasInto(session, deltas);
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
                current.kind()));
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
