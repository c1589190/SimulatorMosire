package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.household.GovernmentHouseholdWiring;
import io.mosire.simos.app.household.HouseholdEconomyProjection;
import io.mosire.simos.app.household.HouseholdPositionResolver;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomyParallelism;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionLedger.ActorEntry;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.household.VitalSettlementResult;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ **人口—经济协调器**（R4）：**唯一同时看得见 {@code social} 与 {@code economy} 的推进参与者** —— 于是"人"第一次真的随时间变：
 * **日初每 tick 生死结算（Social 唯一权威）+ 经济结算一天 + 逐户净人口变化同步到经济行**。
 *
 * <p>★★ **为什么必须有它**（不是"图省事"，是结构上只能如此）：
 *
 * <ol>
 *   <li>**出生/死亡只算在 Social 那一侧**（年龄/性别/率表都是 {@code Household} + {@code PopulationGroup}
 *       的属性，见 {@code HouseholdBook.settleOneTick}）；**而"经济行人口"只住在 economy 那一侧** ⇒ 两边必须在一个参与者里按
 *       "先 Social 结算、再刷新经济投影、最后把 delta 加到经济行"的次序接线；
 *   <li>**§十一 等价性**（一次推 N 天 == N 次单日）要求"逐日"这条语义落在**同一个参与者内部** —— 若让两个参与者各自读对方的**基态**，一次推 365
 *       天时社会侧只能看到第 0 天的经济状态， 而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**；
 *   <li>跨切片写要求"同一模块只能有一个写者"（{@code TimeProposalResolver} 的写-写检查）⇒ 同时写这两片的参与者**只能有一份**。
 * </ol>
 *
 * <p>★ 它住在 {@code simos-app}：设计稿 §8.2 的原文——"跨 {@code social}+{@code economy} 的协调器**必须住 {@code
 * simos-app}** （唯一认识所有模块的地方）"。★ 它**内联调用** {@link EconomySettlement} 的包内可见日结算入口， 从而与 {@code
 * EconomyTimeParticipant} 共用同一个结算实现（**只有一条真相**，不是两份公式）。
 *
 * <p>★★ **一天的次序**（2026-10-09 每 tick 生死 Batch B 起；缺一不可）：
 *
 * <pre>
 * ① 日初：Social 每 tick 生死结算（HouseholdBook.settleOneTick）
 *     → 用**新** Social 刷新经济侧 composition / laborBudgets / naturalNeeds
 *     → 把逐户净人口变化（出生 − 死亡）加到经济行 population
 * ② 经济结算一天（消费/借粮/进度/劳动/周期末收获）——
 *     行人口、劳动预算与当日需求都已是**新 Social** 的投影
 * </pre>
 *
 * <p>★ 旧口径的"日末生理压力 + 每 30 天月度出生/死亡"已在 Batch B 整体删除；出生/死亡不再进
 * {@code FlowRow.births/deaths} 的逐户流水（本批只同步行人口，见报告"未完成/风险"）。
 *
 * <p>★★ <b>H4：两份副本（商品 + 货币）按同一顺序收尾</b>：<b>载入</b>（{@link OwnershipBooks#loadHouseholdGoods} / {@link
 * OwnershipBooks#loadHouseholdMoney}）→ step（两者都由 {@code EconomyDayStepper} 就地更新）→ 条目落账 （{@link
 * OwnershipBooks#apply}）→ **两份副本按绝对值落回**（先商品、后货币；顺序不能反，因为它们写的是同一本 {@code HouseholdInventory} 的两个余额表）。
 *
 * <p>★★ **它是"人口守恒"的落点**：出生与死亡在 Social 侧算出（唯一权威），本参与者把逐户净变化同步到经济行 ⇒
 * {@code Σ经济行新人口 == Σ经济行旧人口 + 出生 − 死亡} 逐值可核。
 *
 * <p>★ **未激活/无上界**：经济未激活（{@code meta} 空）⇒ **两侧都交不变变更集**（没有生活资料信号 ⇒ 人口不动， 这正是"世界还没播种"该有的样子）；{@code
 * range.to} 缺省 ⇒ 同样交不变变更集、不抛（该推进随后必被 Core 拒）。
 */
public final class PopulationEconomyTimeParticipant implements TimeParticipant {

  private static final Logger LOG = LoggerFactory.getLogger(PopulationEconomyTimeParticipant.class);

  /** 参与者身份（**不是模块名**：它同时写 {@code social} 与 {@code economy} 两个模块）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";

  /** ★★ P2-D：gov 切片（GOV 单位 → 每 tick 行政读数）。 */
  private static final String GOV = "gov";

  /** map 切片（税/行政需求要读 Region 拓扑；只在 gov 激活时强制在场）。 */
  private static final String MAP = "map";

  /** ★ T5 的第三片（产权落账）：actor 切片必须在场（缺席 ⇒ 抛 —— 产出没有地方落）。 */
  private static final String ACTOR = "actor";

  private final String mapId;

  /**
   * ★★ <b>经济日结算的 worker 数</b>（R2）：由组合根从 {@code ShellConfig.economyWorkerCount()} 传入；缺省 1 = 单线程退化路径。
   *
   * <p>★ 本参与者在每次 {@code simulateWorld} 里构造一次 {@link EconomyParallelism#of(int)}（自建池），并由 {@link
   * EconomyDayStepper#finish()}/{@link EconomyDayStepper#close()} 关池；异常路径由 {@code try/finally} 收口
   * ——推进失败不留下活着的结算线程池。
   */
  private final int economyWorkerCount;

  /** 旧调用点（测试/夹具）兼容：并行度取缺省 {@link ShellConfig#DEFAULT_ECONOMY_WORKER_COUNT}（单线程退化路径）。 */
  public PopulationEconomyTimeParticipant(String mapId) {
    this(mapId, 1);
  }

  /** ★ R2 组合根入口：{@code economyWorkerCount} 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）。 */
  public PopulationEconomyTimeParticipant(String mapId, int economyWorkerCount) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    if (economyWorkerCount < 1) {
      throw new IllegalArgumentException(
          "economyWorkerCount 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）: " + economyWorkerCount);
    }
    this.economyWorkerCount = economyWorkerCount;
  }

  /** ★ R2：本参与者配置的经济结算 worker 数（只读；服务装配日志/诊断）。 */
  public int economyWorkerCount() {
    return economyWorkerCount;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    EconomyData economyBase = economyOf(state);
    SocialData socialBase = socialOf(state);
    // ★★ S1 阶段 4+5 Task 5：**第三片 actor** —— 产权落账只可能发生在同时看得见 economy 与 actor 的地方。
    ActorData actor = actorOf(state);
    // ★★ S3b（2026-10-09）：Unit/Gov 的 households ↔ Social 家户位置一致性 + HouseholdEconomy 人口向 Social 家户投影。
    //   两件事都必须在日循环之前做：它们改的是本轮推进的**基态**，终端变更集相对 economyBase/socialBase 取差分。
    UnitState units = unitStateOf(state);
    SocialData social = socialBase;
    if (units != null && !social.households().isEmpty()) {
      HouseholdUnitConsistency.Reconciliation reconciliation =
          HouseholdUnitConsistency.reconcileSocialToUnits(social, units);
      social = reconciliation.data();
      if (!reconciliation.unresolved().isEmpty()) {
        throw new IllegalStateException(
            "Unit.households 与 Social 家户位置存在单侧修不了的不一致（S3b）："
                + reconciliation.unresolved().size()
                + " 处，首条："
                + reconciliation.unresolved().get(0));
      }
      HouseholdUnitConsistency.requireConsistent(social, units);
      Map<String, Long> staffProjection =
          HouseholdUnitConsistency.staffHouseholdProjection(social, units);
      if (!staffProjection.isEmpty()) {
        LOG.info(
            "event=GOV_STAFF_HOUSEHOLD_PROJECTION mapId={} entries={} projection={}",
            mapId,
            staffProjection.size(),
            staffProjection);
      }
    }
    EconomyData economyAligned = economyBase;
    if (units != null && !social.households().isEmpty()) {
      // ★★ 2026-10-09：UNIT 家户（政府/军队小家户）的 economy 行视图对齐到 unit 当刻 effectivePosition 的 hex——
      //   市场参与 / 生产组织 / 贷款等 economy 内部一律读 HouseholdEconomy.view().hex()，本对齐让它们无需 new dependency
      //   就跟随 unit.PlaceAt / 行军 / 迁都。HEX 家户原样不动。
      HouseholdPositionResolver.Alignment alignment =
          HouseholdPositionResolver.alignHouseholdEconomyViews(economyBase, social, units, range.from());
      economyAligned = alignment.data();
      if (alignment.moved() > 0) {
        LOG.info(
            "event=UNIT_HOUSEHOLD_ECONOMY_VIEW_ALIGNED mapId={} moved={} tick={}",
            mapId,
            alignment.moved(),
            range.from().tick());
      }
    }
    HouseholdEconomyProjection.Result householdEconomyProjection =
        HouseholdEconomyProjection.project(economyAligned, social);
    if (!householdEconomyProjection.unresolved().isEmpty()) {
      LOG.warn(
          "event=CLASSROW_POPULATION_PROJECTION_UNRESOLVED mapId={} count={} first={}",
          mapId,
          householdEconomyProjection.unresolved().size(),
          householdEconomyProjection.unresolved().get(0));
    }
    EconomyData economy = householdEconomyProjection.data();
    // ★★ P2-C §13.7：经济已激活 + 存在 GOV 单位时，推进入口把"GovernmentFormation 政府家户 ↔ HouseholdEconomy ↔ 政府记录 ↔ 国库账户"
    //   这条闭环判死 —— 缺任何一边都具名失败，不把"没有政府记录"读成"没有政府"。
    if (economy.meta().isPresent() && units != null) {
      GovernmentHouseholdWiring.requireConsistent(economy, social, units);
    }

    // ★★ P2-D：gov 切片（GOV 单位 → 每 tick 行政读数）与地图（Region 拓扑）在日循环之前读入。
    //   引导（沿用阶段 11b 的口径）：只要单位带 GovernmentFormation 而 gov 片还没有它的读数，就为本推进补一条
    //   GovOfficeState.empty(...) 作为基线 —— 否则"建了 GOV 编制"永远不会激活行政结算（没有人负责首建读数）。
    GovState govState = govOf(state);
    GameMap map = mapOfOrNull(state);
    GovState bootstrappedGov =
        units == null ? govState : withBootstrapOffices(govState, units, range.from().tick());
    boolean govActive = !bootstrappedGov.offices().isEmpty();
    if (govActive) {
      if (units == null) {
        throw new IllegalStateException("gov 片有行政读数但 state 里没有 unit 切片（装配故障：行政结算要求 GOV 编制在场）");
      }
      if (map == null) {
        throw new IllegalStateException("存在 GOV 编制但 state 里没有 map 切片（日税/行政需求要求 Region 拓扑在场；装配故障）");
      }
      if (state.module(GOV).isEmpty()) {
        throw new IllegalStateException("存在 GOV 编制但 state 里没有 gov 切片（本轮要写行政读数，装配故障；世界创世应补空 gov 片）");
      }
    }

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    for (IndustryId id : economy.industries().keySet()) {
      reads.add(economyAddress("industry", id.value()));
      writes.add(economyAddress("industry", id.value()));
    }
    // ★★ H0.2：class/flow 的地址局部名 = {@link CohortKey#toString()} 的**规范串**（{@code
    // 0_0|rural|poor_peasant}）。
    //   键里已经没有产业 ⇒ 旧版内联拼的 {@code <industryId>.<slotId>} 既拼不出来、也不该再拼（那是**第二处拼写点**）。
    //   ★ 与 {@code EconomyResolver} 的 class/flow 地址**必须逐字同串**：读写集的冲突检测全靠它。
    for (HouseholdId key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    for (HouseholdId key : economy.flows().keySet()) {
      writes.add(economyAddress("flow", key.toString()));
    }
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }
    // ★ T5：第三片 actor —— 读写集是 {@code actor:<mapId>:goods.<key>}（形制照 ActorResolver）。
    reads.add(actorAddressRoot());
    if (state.module("map").isPresent()) {
      // ★ M2.3：区域拓扑读地图（城市/地形）—— 只读声明，避免与地图写者同轮冲突时静默。
      reads.add(mapAddressRoot());
    }
    if (units != null) {
      // ★ S3b：本轮读 unit 切片做家户一致性校核/自动同步（只读，不写 unit——写侧仍归 UnitTimeParticipant）。
      reads.add(unitAddressRoot());
    }
    writes.add(actorAddressRoot());
    for (HouseholdAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }

    Optional<io.mosire.simos.util.time.SimosTimestamp> to = range.to();
    if (to.isEmpty() || economy.meta().isEmpty()) {
      // 无上界推进 / 经济未激活 ⇒ 三片都不动（但**交的是不变变更集，不是空提案**：契约原文）。
      // ★ S3b：家户一致性同步 / HouseholdEconomy 投影即使在经济未激活时也照常提交（它们各自与 base 差分）。
      return new WorldTimeProposal(
          NAMESPACE,
          Map.of(
              ECONOMY, EconomyChangeSet.between(economyBase, economy),
              SOCIAL, SocialChangeSet.between(socialBase, social),
              ACTOR, ActorChangeSet.between(actor, actor)),
          reads,
          writes);
    }
    // ★★ P2-D：gov 片只在真的存在 GOV 读数时声明读写（无 GOV 世界零变化、零声明，沿用阶段 11b 口径）。
    if (govActive) {
      reads.add(govAddressRoot());
      writes.add(govAddressRoot());
    }
    LOG.info(
        "event=ECONOMY_ADVANCE_START mapId={} fromTick={} toTick={} days={} workerCount={}"
            + " households={} markets={} debtContracts={}",
        mapId,
        range.from().tick(),
        to.get().tick(),
        to.get().tick() - range.from().tick(),
        economyWorkerCount,
        economy.classes().size(),
        economy.markets().size(),
        economy.debtContracts().size());

    // ★★ S1：唯一账户会话 —— 家户（商品/货币/冻结）+ 经营者（商品/货币/冻结）一次装载；
    //   键 = (ActorRef, HexCoord)，家户 actor id 由 HouseholdId 唯一派生（不再从 CohortKey 拼）。
    //   ★ S1.5 旧档：先把旧三段 actor id 上的账搬到新身份键（移动，不是复制 —— 否则一笔粮变两本账）。
    ActorData migratedBooks = actor; // ★ P2-A：旧账户随旧世界报废，不再做 legacy actor 账户搬家
    // ★ S1.5：搬迁后的新账户键也要进读写集（否则第二次推进的冲突检测看不见它们）。
    for (HouseholdAccountKey key : migratedBooks.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }
    AccountSession session = OwnershipBooks.loadAccountSession(economy, migratedBooks);
    // ★★ S3 缺陷修复：会话登记过的账户当天终值由 landAccountSession 的**绝对值**落回覆盖；
    //   这些账户上的 ledger 条目不能再对 actor 基准叠一遍（否则跨区到货等"只写会话"的当天流入会让付方被误判透支）。
    //   非会话账户（承运人、未播种经营者等）仍按 ledger 条目逐笔折入 actor 账。
    Set<AccountPartitionKey> sessionAccounts = new LinkedHashSet<>(session.accounts().keySet());
    // ★★ R2：并行度进构造器；workerCount == 1 时 EconomyParallelism.of 走单线程退化路径（不建池）。
    //   池的生命周期：finish()/close() 关闭；下面的 try/finally 保证日循环抛异常也不泄漏结算线程池。
    EconomyParallelism parallelism = EconomyParallelism.of(economyWorkerCount);
    try {
      EconomyDayStepper stepper =
          new EconomyDayStepper(
              economy,
              session,
              // ★ M2.3：区域拓扑由组合根从地图/城市现算（Map + SocialCity/City）；不得让 economy 反查 social。
              MarketTopologyBook.from(state),
              EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
              EconomySettlement.FAMINE_MORTALITY_PER_MILLE,
              parallelism);
      try {
        // ★ 装配行可读：实际 workerCount 与"是否真的并行"都在这里（1 = 单线程退化路径）。
        LOG.info(
            "人口—经济推进并行入口: workerCount={} parallelism={}",
            economyWorkerCount,
            stepper.parallelism());
        // ★★ P2-A A3：家户人口组成的唯一权威是 Social 的 {@code Household.members} —— 这里先把**基态**投影成
        //   「household → (lot → count)」只读表注入经济会话（组织/进入阶段挑批次用；不进 Economy 状态、
        //   不进变更集）。日循环里每 tick 在生死结算后再用新 Social 刷新一次。
        stepper.updateComposition(compositionOf(social));
        stepper.recomputeLaborBudgets(laborBudgetsOf(social, range.from().tick()));
        ActorData currentBooks = migratedBooks;
        SocialData currentSocial = social;
        // ★★ P2-D：gov 状态（每 tick 行政读数）+ 跨日累计读数（只进日志，不进状态）。
        GovState currentGov = bootstrappedGov;
        AdminTotals adminTotals = new AdminTotals();
        Set<String> missingAdminRegions = new LinkedHashSet<>();
        for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
          // ★★ 2026-10-09 每 tick 生死 Batch B：**日初先算生死**（Social 唯一权威），再用新 Social 刷新经济侧
          //   composition / laborBudgets / naturalNeeds，最后把逐户净人口变化加到经济行人口上 —— 全部在
          //   step(day) 之前完成。经济结算当天读到的都是新 Social 的投影。
          VitalSettlementResult vital =
              HouseholdBook.settleOneTick(currentSocial, day, CalendarClock.julianDefault());
          currentSocial = vital.data();
          stepper.updateComposition(compositionOf(currentSocial));
          stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
          stepper.updateNaturalNeeds(naturalNeedsOf(currentSocial, day));
          stepper.applyHouseholdPopulationDeltas(vital.populationDeltas());
          if (LOG.isDebugEnabled()) {
            long deltaNet = 0L;
            for (long delta : vital.populationDeltas().values()) {
              deltaNet = Math.addExact(deltaNet, delta);
            }
            LOG.debug(
                "event=POPULATION_SETTLE_APP day={} births={} deaths={} events={}"
                    + " deltaHouseholds={} deltaNet={}",
                day,
                vital.births(),
                vital.deaths(),
                vital.events().size(),
                vital.populationDeltas().size(),
                deltaNet);
          }
          // ★★ T5：日循环里同一处落账 —— step 交回**当天**的账，条目逐日落到 actor 账本上（不重不漏）。
          //   ★★ M2 守恒收口：**市场成交（MARKET_TRADE）不折**（理由见 {@link OwnershipBooks#REASONS_NOT_FOLDED}）——
          //   市场双方都必须是本轮参与者：落在账户上的那一份已由下面的会话副本绝对值落回覆盖，在途那一份由
          //   ShipmentBatch 承载；再折一遍会在异地键上造幽灵账。
          ProductionLedger ledger = stepper.step(day);
          // ★★ P2-D：日结算之后的税 / 行政俸禄 —— **同一账户会话、同一个日循环**（不另起 participant，避免 gov/actor 同名模块冲突）。
          //   顺序沿用阶段 11b：先税（收入侧）、后 GovDaily（支出侧）⇒ 当天税可先供当天俸禄；两者都写账户会话，
          //   由本日末尾的 landAccountSession 绝对值一次落回 actor。信号折进 economy.crisisSignals（同 (hex,kind) 覆盖）。
          if (govActive) {
            Map<UnitId, Long> efficiencyPerMilleByUnit =
                efficiencyTable(currentGov, units, map, currentSocial, missingAdminRegions);
            JurisdictionDailyTax.Report tax =
                JurisdictionDailyTax.collect(
                    stepper.accounts(),
                    stepper.householdEconomies(),
                    units,
                    map,
                    day,
                    efficiencyPerMilleByUnit);
            for (Map.Entry<HouseholdId, Long> entry : tax.grainByHousehold().entrySet()) {
              if (!stepper.recordTaxPaid(entry.getKey(), entry.getValue())) {
                LOG.warn(
                    "event=TAX_FLOW_ROW_MISSING day={} household={} grain={}",
                    day,
                    entry.getKey().value(),
                    entry.getValue());
              }
            }
            adminTotals.recordTax(tax);
            GovernmentUpkeepOracle oracle = new GovernmentUpkeepOracle(stepper.accounts(), units);
            GovDaily.Outcome settled =
                GovDaily.settle(
                    currentGov,
                    units,
                    map,
                    currentSocial,
                    day,
                    CalendarClock.julianDefault().daysInYearAtTick(day),
                    oracle);
            currentGov = settled.next();
            adminTotals.recordGovDaily(settled);
            for (GovDaily.SignalDraft draft : settled.signals()) {
              stepper.putCrisisSignal(toCrisisSignal(draft, day));
            }
          }
          // ★★ M2.7：把"最近一轮市场报告"投递给读口（进程内、不落盘、只在同一 tick 内可信；见 MarketReportFeed 的类注）。
          MarketReportFeed.publish(mapId, stepper.lastMarketReport(), day);
          // ★★ S3：把"当日结账账本"投递给读口（租/工资欠款与逐规则欠额的唯一进程内来源；同款边界）。
          EconomyDayFeed.publish(mapId, Optional.of(ledger), day);
          List<ActorEntry> entries = OwnershipBooks.fold(ledger, OwnershipBooks.REASONS_NOT_FOLDED);
          if (!entries.isEmpty()) {
            currentBooks = OwnershipBooks.apply(currentBooks, entries, sessionAccounts);
            for (HouseholdAccountKey key : currentBooks.accounts().keySet()) {
              writes.add(accountAddress(key));
            }
          }
          // ★★ H1：家户账**按绝对值**落回 actor 切片（不是"再叠加一遍条目"，见 OwnershipBooks 的类注）——
          //   日耗 / 投入 / 同格取材只写副本（它们不是产权条目），而关系实付既进条目、也已计进副本
          //   ⇒ 这一步是它们唯一共同的落点。★ 副本是**活的**（step 就地更新）⇒ 每天重新读访问器，不缓存引用。
          // ★★ S1：全部账户（家户 + 经营者；商品 + 货币 + 冻结）按会话绝对值一次落回。
          currentBooks = OwnershipBooks.landAccountSession(currentBooks, stepper.accounts());
          // ★★ Batch B 起：日末不再有生理压力/月度出生死亡回写 —— 生死已移到**日初**（见循环最前面），
          //   经济行人口由 HouseholdBook 的逐户 delta 同步；P8 迁移规划接线位（原默认 no-op，挂在旧月结块里）
          //   随月结块一并删除，P9 重新接线前必须先定周期边界（见报告）。
        }
        EconomyData currentEconomy = stepper.finish();
        long finalPopulation = 0L;
        for (HouseholdEconomy householdEconomy : currentEconomy.classes().values()) {
          finalPopulation += householdEconomy.population();
        }
        LOG.info(
            "event=ECONOMY_ADVANCE_END mapId={} toTick={} days={} finalPopulation={}"
                + " finalHouseholds={} finalDebtContracts={} finalMarkets={}",
            mapId,
            to.get().tick(),
            to.get().tick() - range.from().tick(),
            finalPopulation,
            currentEconomy.classes().size(),
            currentEconomy.debtContracts().size(),
            currentEconomy.markets().size());
        if (govActive) {
          if (!missingAdminRegions.isEmpty()) {
            LOG.warn(
                "event=GOV_ADMIN_REGION_MISSING mapId={} count={} first={}",
                mapId,
                missingAdminRegions.size(),
                missingAdminRegions.iterator().next());
          }
          adminTotals.logSummary(
              mapId, range.from().tick(), to.get().tick(), governmentCount(currentGov));
        }
        LinkedHashMap<String, ChangeSet> moduleChanges = new LinkedHashMap<>();
        moduleChanges.put(ECONOMY, EconomyChangeSet.between(economyBase, currentEconomy));
        moduleChanges.put(SOCIAL, SocialChangeSet.between(socialBase, currentSocial));
        moduleChanges.put(ACTOR, ActorChangeSet.between(actor, currentBooks));
        if (govActive) {
          // ★ 基线取**未引导**的 govState：本推进新补的空读数（GovOfficeState.empty）也是本轮的合法结果，
          //   不能因为"它本来就是空的"被差分成 Unchanged 而丢掉（否则首建 GOV 永远不会激活结算）。
          moduleChanges.put(GOV, GovChangeSet.between(govState, currentGov));
        }
        return new WorldTimeProposal(NAMESPACE, moduleChanges, reads, writes);
      } finally {
        stepper.close();
      }
    } catch (RuntimeException | Error failure) {
      // stepper 构造失败 ⇒ 池从未交给 finish()/close()；这里补关一次（已关时幂等）。
      parallelism.close();
      throw failure;
    }
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

  /** ★ 是否旧档迁移态（只有旧档需要按 social 重建份额；新档逐 lot 严格校验）。 */

  /** S3b：unit 切片（缺省 ⇒ null；只读，用于家户一致性校核/自动同步）。 */
  private static UnitState unitStateOf(SimulationState state) {
    Snapshot snapshot = state.module("unit").orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static SocialData socialOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(SOCIAL)
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** ★★ P2-D：gov 切片（缺切片 ⇒ {@link GovState#empty()}；旧档/无 GOV 世界），类型不符当场抛。 */
  private static GovState govOf(SimulationState state) {
    Optional<Snapshot> snapshot = state.module(GOV);
    if (snapshot.isEmpty()) {
      return GovState.empty();
    }
    if (!(snapshot.get() instanceof GovSnapshot govSnapshot)) {
      throw new IllegalStateException(
          "state 的 gov 切片不是 GovSnapshot: " + snapshot.get().getClass().getName());
    }
    return govSnapshot.state();
  }

  /** ★★ P2-D：map 切片（缺省 ⇒ null；gov 未激活时不强制在场）。 */
  private static GameMap mapOfOrNull(SimulationState state) {
    Snapshot snapshot = state.module(MAP).orElse(null);
    if (snapshot == null) {
      return null;
    }
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  private String socialAddressRoot() {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId))).canonical();
  }

  private String economyAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String socialAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String unitAddressRoot() {
    return new Address(List.of(new Namespace("unit"), Entity.of(mapId))).canonical();
  }

  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  private String mapAddressRoot() {
    return new Address(List.of(new Namespace("map"), Entity.of(mapId))).canonical();
  }

  /** ★★ P2-D：gov 片根地址（形制照其余切片：{@code gov:<mapId>}）。 */
  private String govAddressRoot() {
    return new Address(List.of(new Namespace(GOV), Entity.of(mapId))).canonical();
  }

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。{@code <key>} 是 {@link HouseholdAccountKey#toString()} 的产物，<b>本类不复述那个格式</b>。
   */
  private String accountAddress(HouseholdAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }

  /** actor 切片只能从 actor 模块拿（铁律 3/4）；缺席或类型不对都是装配故障（同 {@link #economyOf} 的口径）。 */
  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ACTOR)
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（装配故障：产权落账口要求切片在场）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }

  // ── P2-D：行政引导 / 效率表 / 信号折叠 / 日累计读数 ────────────────────────────────────

  /**
   * ★★ <b>行政读数引导</b>（阶段 11b 口径不变）：为"带 {@link GovernmentFormation} 但 gov 片还没有读数"的单位补一条 {@link
   * GovOfficeState#empty(UnitId, long)} 作为本推进基线。
   *
   * <p>确定性：缺读数单位按 {@link UnitId#value()} 升序追加；已有读数原样保留（键序不变）。没有任何缺项 ⇒ 返回入参同一实例 （无 GOV/已引导世界零变化）。
   */
  private static GovState withBootstrapOffices(GovState base, UnitState units, long tick) {
    LinkedHashMap<UnitId, GovOfficeState> offices = new LinkedHashMap<>(base.offices());
    List<UnitId> missing = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation && !offices.containsKey(unit.id())) {
        missing.add(unit.id());
      }
    }
    if (missing.isEmpty()) {
      return base;
    }
    missing.sort(Comparator.comparing(UnitId::value));
    for (UnitId id : missing) {
      offices.put(id, GovOfficeState.empty(id, tick));
    }
    return new GovState(offices);
  }

  /**
   * ★★ <b>算当日 GOV 效率表</b>（单位 → efficiency‰），供辖区日税查表。
   *
   * <p>口径：{@code govState.offices()} 里每个 office 先取单位上的 {@link GovernmentFormation}（没有 ⇒ 不进表 = 税侧整单位跳过）； 再用
   * {@link GovDemand#of} + {@link GovEfficiency#of} 现算。★ 检查该单位管辖的每个 Region 是否都在 map 里， 缺的累积进 {@code
   * missingRegions}（只累积、不抛；调用方整轮汇总成一条具名 WARN）。
   *
   * @param missingRegions 跨日累积的 {@code unit=…,region=…} 明细（调用方只在整轮结束时汇总 WARN 一次）
   */
  private static Map<UnitId, Long> efficiencyTable(
      GovState govState,
      UnitState units,
      GameMap map,
      SocialData social,
      Set<String> missingRegions) {
    Map<UnitId, Long> table = new LinkedHashMap<>();
    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    for (UnitId unitId : ordered) {
      Unit unit = units.units().get(unitId);
      if (unit == null) {
        continue; // 状态损坏由 GovDaily.settle 当场抛；税侧只保证"查不到效率就不征"。
      }
      UnitModule module = unit.module().orElse(null);
      if (!(module instanceof GovernmentFormation formation)) {
        continue; // 没有 GovernmentFormation ⇒ 不进效率表 ⇒ 税侧整单位跳过（无 GOV 不征）。
      }
      unit.jurisdiction()
          .ifPresent(
              jurisdiction -> {
                for (var regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
                  if (!map.regions().containsKey(regionId)) {
                    missingRegions.add("unit=" + unitId.value() + ",region=" + regionId.value());
                  }
                }
              });
      Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);
      GovEfficiency.Efficiency efficiency = GovEfficiency.of(formation, demand);
      table.put(unitId, efficiency.efficiencyPerMille());
    }
    return table;
  }

  /** ★ 把 {@link GovDaily.SignalDraft} 折成 {@link HexCrisisSignal}；kind 字符串 → 枚举的映射只此一处。 */
  private static HexCrisisSignal toCrisisSignal(GovDaily.SignalDraft draft, long day) {
    HexCrisisSignal.Kind kind =
        switch (draft.kind()) {
          case GovDaily.KIND_ADMIN_SUPPLY -> HexCrisisSignal.Kind.ADMIN_SUPPLY;
          case GovDaily.KIND_ADMIN_SECURITY -> HexCrisisSignal.Kind.ADMIN_SECURITY;
          case GovDaily.KIND_ADMIN_PAPERWORK -> HexCrisisSignal.Kind.ADMIN_PAPERWORK;
          default ->
              throw new IllegalStateException(
                  "未知的 GovDaily SignalDraft.kind（映射表只此一处）: " + draft.kind());
        };
    return new HexCrisisSignal(
        CrisisSignalId.idOf(draft.hex(), kind.name()),
        draft.hex(),
        kind,
        Math.toIntExact(draft.severity()),
        day,
        draft.evidence(),
        List.of(),
        List.of(),
        draft.reason());
  }

  /** 本推进覆盖的 GOV 单位数（= gov 片读数条数；只进日志）。 */
  private static long governmentCount(GovState govState) {
    return govState.offices().size();
  }

  /** ★★ P2-D：跨日累计的税/俸禄读数（只进日志/汇总，不进状态、不进变更集）。 */
  private static final class AdminTotals {

    private long taxGrainAssessed;
    private long taxGrainCollected;
    private long taxSilverAssessed;
    private long taxSilverCollected;
    private long upkeepPaidGrain;
    private long upkeepPaidCloth;
    private long upkeepPaidMoney;
    private long upkeepShortfallTotal;
    private long signals;

    void recordTax(JurisdictionDailyTax.Report report) {
      taxGrainAssessed = Math.addExact(taxGrainAssessed, report.grain().assessed());
      taxGrainCollected = Math.addExact(taxGrainCollected, report.grain().collected());
      taxSilverAssessed = Math.addExact(taxSilverAssessed, report.money().assessed());
      taxSilverCollected = Math.addExact(taxSilverCollected, report.money().collected());
    }

    void recordGovDaily(GovDaily.Outcome outcome) {
      for (GovDaily.UpkeepDue due : outcome.dues()) {
        switch (due.resource().name()) {
          case "grain" -> upkeepPaidGrain = Math.addExact(upkeepPaidGrain, due.paid());
          case "cloth" -> upkeepPaidCloth = Math.addExact(upkeepPaidCloth, due.paid());
          case "silver" -> upkeepPaidMoney = Math.addExact(upkeepPaidMoney, due.paid());
          default -> throw new IllegalStateException("未知的 GovDaily 资源名: " + due.resource().name());
        }
        upkeepShortfallTotal = Math.addExact(upkeepShortfallTotal, due.shortfall());
      }
      signals = Math.addExact(signals, outcome.signals().size());
    }

    void logSummary(String mapId, long fromTick, long toTick, long governments) {
      LOG.info(
          "event=GOV_ADMIN_ADVANCE_END mapId={} fromTick={} toTick={} governments={}"
              + " taxGrainAssessed={} taxGrainCollected={} taxSilverAssessed={}"
              + " taxSilverCollected={} upkeepPaidGrain={} upkeepPaidCloth={} upkeepPaidMoney={}"
              + " upkeepShortfallTotal={} signals={}",
          mapId,
          fromTick,
          toTick,
          governments,
          taxGrainAssessed,
          taxGrainCollected,
          taxSilverAssessed,
          taxSilverCollected,
          upkeepPaidGrain,
          upkeepPaidCloth,
          upkeepPaidMoney,
          upkeepShortfallTotal,
          signals);
    }
  }

  /** ★★ P2-A A3：Social 的家户成员表 → 经济结算的只读组成投影（不落任何 Economy 状态）。 */
  private static Map<HouseholdId, Map<PeopleLotId, Long>> compositionOf(SocialData social) {
    Map<HouseholdId, Map<PeopleLotId, Long>> composition = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Household> entry : social.households().entrySet()) {
      composition.put(entry.getKey(), entry.getValue().members());
    }
    return composition;
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：Social 逐户展开当日劳动预算</b>（毫小时/tick；只读投影）——
   * 唯一实现 = {@link SocialData#householdLaborMilli(HouseholdId, long, CalendarClock)}（逐成员份额 ×
   * provisioning 劳动系数，家户覆盖优先、全局默认兜底）。经济侧只收结果，不再自己查表。
   */
  private Map<HouseholdId, Long> laborBudgetsOf(SocialData social, long day) {
    CalendarClock clock = CalendarClock.julianDefault();
    Map<HouseholdId, Long> budgets = new LinkedHashMap<>();
    long totalLaborMilli = 0L;
    for (HouseholdId household : social.households().keySet()) {
      long budget = social.householdLaborMilli(household, day, clock);
      budgets.put(household, budget);
      totalLaborMilli = Math.addExact(totalLaborMilli, budget);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "event=POPULATION_LABOR_BUDGETS_EXPANDED mapId={} day={} households={} totalLaborMilli={}",
          mapId,
          day,
          budgets.size(),
          totalLaborMilli);
    }
    return budgets;
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：Social 逐户展开当日逐商品自然需求</b>（毫单位/日；只读投影）——
   * 唯一实现 = {@link SocialData#householdNaturalNeeds(HouseholdId, long, CalendarClock)}（逐成员求和、粮 120
   * 天家户层一次取整、布历年 {@code YearFraction}）；经济侧收到后由
   * {@link EconomyDayStepper#updateNaturalNeeds(Map)} 原样注入。
   *
   * <p>★ 空家户返回空需求（合法的"没有人"）；坏数据（家户/批次缺失、口径冲突、系数查不到）由 Social 侧具名拒并带 ERROR 日志。
   */
  private Map<HouseholdId, Map<CommodityId, Long>> naturalNeedsOf(SocialData social, long day) {
    CalendarClock clock = CalendarClock.julianDefault();
    Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold = new LinkedHashMap<>();
    long totalNeedsMilli = 0L;
    long totalGrainMilli = 0L;
    long totalClothMilli = 0L;
    for (HouseholdId household : social.households().keySet()) {
      Map<CommodityId, Long> needs = social.householdNaturalNeeds(household, day, clock);
      needsByHousehold.put(household, needs);
      for (Map.Entry<CommodityId, Long> need : needs.entrySet()) {
        totalNeedsMilli = Math.addExact(totalNeedsMilli, need.getValue());
        if (EconomySettlement.GRAIN.equals(need.getKey())) {
          totalGrainMilli = Math.addExact(totalGrainMilli, need.getValue());
        }
        if (EconomySettlement.CLOTH.equals(need.getKey())) {
          totalClothMilli = Math.addExact(totalClothMilli, need.getValue());
        }
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "event=POPULATION_NATURAL_NEEDS_EXPANDED mapId={} day={} households={} totalNeedsMilli={} grainMilli={} clothMilli={}",
          mapId,
          day,
          needsByHousehold.size(),
          totalNeedsMilli,
          totalGrainMilli,
          totalClothMilli);
    }
    return needsByHousehold;
  }
}
