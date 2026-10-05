package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.household.GovernmentHouseholdWiring;
import io.mosire.simos.app.household.HouseholdClassRowProjection;
import io.mosire.simos.app.household.HouseholdPositionResolver;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.app.world.EconomySeeder;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.LaborTimeTable;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.MigrationPolicy;
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
import io.mosire.simos.social.api.population.AgeBracketView;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.GovFormation;
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
import io.mosire.simos.util.time.SimosTimestamp;
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
 * ★★ **人口—经济协调器**（R4）：**唯一同时看得见 {@code social} 与 {@code economy} 的推进参与者** —— 于是"人"第一次真的随时间变：**年龄推进
 * + 逐日生理压力 + 月度出生/死亡**，而且**死亡会同时反映到经济侧的阶层行与劳动配额上**。
 *
 * <p>★★ **为什么必须有它**（不是"图省事"，是结构上只能如此）：
 *
 * <ol>
 *   <li>**出生/死亡只能算在人口那一侧**（`年龄 × 性别 × 基础死亡率 × 生理压力` —— 年龄与性别是 {@link PopulationGroup}
 *       的属性），**而"吃得饱不饱"只算在经济那一侧**（需求与实得都在 {@code FlowRow} 里）⇒ 判定需要两侧同时在场；
 *   <li>**§十一 等价性**（一次推 N 天 == N 次单日）要求"逐日"这条语义落在**同一个参与者内部** —— 若让两个参与者各自读对方的**基态**，一次推 365
 *       天时社会侧只能看到第 0 天的经济状态， 而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**；
 *   <li>跨切片写要求"同一模块只能有一个写者"（{@code TimeProposalResolver} 的写-写检查）⇒ 同时写这两片的参与者**只能有一份**。
 * </ol>
 *
 * <p>★ 它住在 {@code simos-app}：设计稿 §8.2 的原文——"跨 {@code social}+{@code economy} 的协调器**必须住 {@code
 * simos-app}** （唯一认识所有模块的地方）"。★ 它**内联调用** {@link EconomySettlement} 的包内可见日结算入口， 从而与 {@code
 * EconomyTimeParticipant} 共用同一个结算实现（**只有一条真相**，不是两份公式）。
 *
 * <p>★★ **一天的次序**（缺一不可）：
 *
 * <pre>
 * ① 经济结算一天（消费/借粮/进度/劳动/周期末收获）      —— 行人口仍是"上个月末"的
 * ② 生理压力：读**当天**的发生额（需求与实得）⇒ 逐批次 stressAfter
 * ③ 每 30 天：月度结算（出生/死亡）⇒ 改社会侧的 count，并把同一份账回写经济侧（行人口/配额/流水）
 * </pre>
 *
 * <p>★★ <b>H4：两份副本（商品 + 货币）按同一顺序收尾</b>：<b>载入</b>（{@link OwnershipBooks#loadHouseholdGoods} / {@link
 * OwnershipBooks#loadHouseholdMoney}）→ step（两者都由 {@code EconomyDayStepper} 就地更新）→ 条目落账 （{@link
 * OwnershipBooks#apply}）→ **两份副本按绝对值落回**（先商品、后货币；顺序不能反，因为它们写的是同一本 {@code HouseholdInventory} 的两个余额表）。
 *
 * <p>★★ **它是"人口守恒"的落点**：出生与死亡在这一处算出来、在两侧各落一次账（社会侧改 {@code count}、 经济侧改行人口与 {@code
 * FlowRow.births/deaths}）⇒ {@code Σ新人口 == Σ旧人口 + 出生 − 死亡} 逐值可核。
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
    // ★★ S3b（2026-10-09）：Unit/Gov 的 households ↔ Social 家户位置一致性 + ClassRow 人口向 Social 家户投影。
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
      //   市场参与 / 生产组织 / 贷款等 economy 内部一律读 ClassRow.view().hex()，本对齐让它们无需 new dependency
      //   就跟随 unit.PlaceAt / 行军 / 迁都。HEX 家户原样不动。
      HouseholdPositionResolver.Alignment alignment =
          HouseholdPositionResolver.alignClassRowViews(economyBase, social, units, range.from());
      economyAligned = alignment.data();
      if (alignment.moved() > 0) {
        LOG.info(
            "event=UNIT_HOUSEHOLD_ECONOMY_VIEW_ALIGNED mapId={} moved={} tick={}",
            mapId,
            alignment.moved(),
            range.from().tick());
      }
    }
    HouseholdClassRowProjection.Result classRowProjection =
        HouseholdClassRowProjection.project(economyAligned, social);
    if (!classRowProjection.unresolved().isEmpty()) {
      LOG.warn(
          "event=CLASSROW_POPULATION_PROJECTION_UNRESOLVED mapId={} count={} first={}",
          mapId,
          classRowProjection.unresolved().size(),
          classRowProjection.unresolved().get(0));
    }
    EconomyData economy = classRowProjection.data();
    // ★★ P2-C §13.7：经济已激活 + 存在 GOV 单位时，推进入口把"GovFormation 政府家户 ↔ ClassRow ↔ 政府记录 ↔ 国库账户"
    //   这条闭环判死 —— 缺任何一边都具名失败，不把"没有政府记录"读成"没有政府"。
    if (economy.meta().isPresent() && units != null) {
      GovernmentHouseholdWiring.requireConsistent(economy, social, units);
    }

    // ★★ P2-D：gov 切片（GOV 单位 → 每 tick 行政读数）与地图（Region 拓扑）在日循环之前读入。
    //   引导（沿用阶段 11b 的口径）：只要单位带 GovFormation 而 gov 片还没有它的读数，就为本推进补一条
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
      // ★ S3b：家户一致性同步 / ClassRow 投影即使在经济未激活时也照常提交（它们各自与 base 差分）。
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
        // ★★ P2-A A3：家户人口组成的唯一权威是 Social 的 {@code Household.members} —— 这里把它投影成
        //   「household → (lot → count)」只读表注入经济会话（组织/进入阶段挑批次用；不进 Economy 状态、
        //   不进变更集）。SocialData 的构造期守卫已经保证逐 lot 守恒（Σshare == PopulationGroup.count）。
        stepper.updateComposition(compositionOf(social));
        stepper.recomputeLaborBudgets(laborBudgetsOf(social, range.from().tick()));
        ActorData currentBooks = migratedBooks;
        SocialData currentSocial = social;
        // ★★ P2-D：gov 状态（每 tick 行政读数）+ 跨日累计读数（只进日志，不进状态）。
        GovState currentGov = bootstrappedGov;
        AdminTotals adminTotals = new AdminTotals();
        Set<String> missingAdminRegions = new LinkedHashSet<>();
        for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
          // ★★ P2-A §13.4：每 tick 重算家户时间预算（Social 人口组成 × 可调系数表）—— 见 EconomyDayStepper。
          stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
          LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetBefore = unmetOf(stepper.flows());
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
                    stepper.classRows(),
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
          // ② 逐日生理压力（读**当天**的需求与实得 —— 两者都在刚结算完的账上）。
          currentSocial =
              applyDailyStress(
                  stepper.classRows(), currentSocial, stepper.flows(), unmetBefore, units, day);
          // ③ 月度结算：出生/死亡 → 先改人口（真值源），再按同一份账回写经济侧。
          if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L) {
            PopulationDynamics.Outcome outcome =
                PopulationDynamics.monthly(currentSocial, day, CalendarClock.julianDefault());
            currentSocial = outcome.data();
            if (!outcome.isEmpty()) {
              stepper.applyPopulationChange(outcome.changeList());
              LOG.info(
                  "event=POPULATION_WRITEBACK day={} births={} deaths={} households={}"
                      + " population={}",
                  day,
                  outcome.births(),
                  outcome.deaths(),
                  stepper.classRows().size(),
                  stepper.classRows().values().stream().mapToLong(ClassRow::population).sum());
              // ★★ P2-A A3：出生/死亡/新生批次都在 Social 侧落定（PopulationDynamics 已维护 Household.members）
              //   ⇒ 这里只刷新经济会话的只读组成投影，不再回写任何 Economy 成员份额。
              stepper.updateComposition(compositionOf(currentSocial));
              // ★ 月末**重新对齐副本**（照 flows 的既有先例：那份实现会带出自己的流水副本 ⇒ 累加器要重新读一遍）。
              //   ★ 放在月度回写之后、且**在条目落账之后**：全部账户以会话的绝对值收尾（顺序反了会把条目加两遍）。
              currentBooks = OwnershipBooks.landAccountSession(currentBooks, stepper.accounts());
            }
            // ★★ P8 迁移接线位（**默认 no-op**）：`planMigrations` 目前传空读数表 ⇒ 规划器恒返回空表 ⇒
            //   不改任何状态、不动任何数值。P9 启用时的顺序必须在这里（月度人口回写 + 份额对账之后、
            //   `landAccountSession`/`stepper.finish()` 之前）：
            //     ① 读数：从 MarketReportFeed / CityLandBook / social.urbanPopulationAt 现算
            //        List<CityMigrationReading>（本参与者已在 reads 里声明 map/social 根地址）；
            //     ② social 侧：按 planned 拆/合 PopulationGroup（换 residence，id 不变），得到新的 SocialData；
            //     ③ 经济侧：stepper.applyMigrations(planned, day)（行人口/劳动/债务；唯一写口见 LotMigrationBook，
            //        它有意不碰 Membership —— 份额由下一步的 reconcile 按行人口重建/削平）；
            //     ④ 对账：MembershipWriteback.reconcile(stepper.classRows(), stepper.memberships(), 新
            // SocialData)
            //        逐 lot 硬校验；⑤ 再落 actor 账户（若迁移不碰账户可省略，但顺序必须早于 finish()）。
            List<LotMigration> plannedMigrations = planMigrations(currentSocial, day);
            if (!plannedMigrations.isEmpty()) {
              throw new IllegalStateException(
                  "P8 迁移执行尚未接线：规划器返回了 "
                      + plannedMigrations.size()
                      + " 笔迁移，但 social/economy 双写与对账顺序未实现（默认空读数表 ⇒ 正常不会到达这里）");
            }
          }
        }
        EconomyData currentEconomy = stepper.finish();
        long finalPopulation = 0L;
        for (ClassRow row : currentEconomy.classes().values()) {
          finalPopulation += row.population();
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

  // ── P8 迁移规划接线位（默认 no-op）──────────────────────────────────────────────────────────

  /**
   * ★★ <b>P8 人口迁移规划器调用位（默认 no-op）</b>：当前显式传空读数表 ⇒ {@link PopulationMigrationPlanner#plan} 恒返回
   * {@code List.of()}，调用点 {@code if (!plannedMigrations.isEmpty())} 不进入 ⇒ <b>不改任何状态、不改任何数值</b>。
   *
   * <p>★★ <b>为什么必须住这里</b>：迁移计划同时看得见 social（批次）与 economy（城市/市场读数），而只有组合根同时认识 两片；plan 本身仍是纯函数（见
   * {@link PopulationMigrationPlanner}）。
   *
   * <p>★★ <b>P9 接线点（本方法就是那个唯一入口）</b>：把空表替换成按城现算的 {@link CityMigrationReading} 列表—— 数据源 = {@link
   * MarketReportFeed}（本轮交易/利润读数）、P6 {@code CityLandBook}（承载/拥挤）与 {@code
   * SocialData.urbanPopulationAt(cityId)}（人口分母）；然后把调用点改造成上面的 ①②③④⑤ 顺序。
   */
  private static List<LotMigration> planMigrations(SocialData social, long day) {
    // ★ 默认空读数表：不是“还没写”，是 P8 明确只做契约/纯规划器/接线点；P9 统一接读数与测试。
    return PopulationMigrationPlanner.plan(social, List.of(), MigrationPolicy.defaults(), day);
  }

  // ── 逐日生理压力（社会侧唯一的日常写点）────────────────────────────────────────────────

  /**
   * ★★ **把当天的生活资料满足情况折成每个批次的压力**（spec §七："短期缺粮加一些、恢复供给后逐渐消退"）。
   *
   * <pre>
   * 逐家户：粮/布的"当日需求"  = Σ该家户各行 {@code naturalNeeds[商品]}（结算当天写回的那一份 ⇒ 与结算同源）
   *          粮/布的"当日实得"  = 需求 − 当日新记进 {@code FlowRow.unmetNeed[商品]} 的那一笔
   * 逐批次：取**它住的那一格、它那一种居住类型**的家户（四行求和）⇒ 满足率‰ ⇒ {@link PopulationDynamics#stressAfter}
   * </pre>
   *
   * <p>★★ **H0.2 起批次 ↔ 家户的对应不再经产业**：批次的<b>落点格</b>由所属家户给出（HEX 家户直接取，
   * {@code UNIT} 家户经 {@link HouseholdPositionResolver} 派生 unit 当刻 hex）与 <b>居住类型</b>（批次 id 的前缀 ⇒
   * {@link ResidenceKind#ofLot}，唯一拼写点），而家户行的键正是 {@code (格, 居住类型, 阶层)}（{@code CohortKey}）
   * ⇒ 两维直接对上，**不需要中间映射表**。旧版要经"批次供给哪些产业"（{@code LaborAllocation}）再回退到"该格的产业"， 那一步在"一格既有农村又有城镇"时会把两池并起来算
   * —— 正是 R-N1 要堵的"农村余粮喂城市缺口"。
   *
   * <p>★ **没有配额的批次照样吃饭**（0-14 档与全部新生儿）：它们的居住类型与落点格本来就在批次上 ⇒ 这条兜底现在是**结构上白拿的**（旧版要为它单独查一次"该格的产业"）。 ★
   * **没有需求的批次不动**（{@code 需求 == 0} ⇒ 满足率按 1000‰ 计，压力照常消退）："这一天没记账"不等于"饿了一天"。
   *
   * @param unmetBefore 当日结算**之前**的 {@code FlowRow.unmetNeed} 快照（用于取"当天新增的那一笔"）
   * @param units unit 切片（UNIT 家户的有效格由 {@link HouseholdPositionResolver} 现算；可为 {@code null} ⇒ 只用
   *     HEX 家户）
   * @param day 世界日（resolver 的取位时刻）
   */
  private static SocialData applyDailyStress(
      Map<HouseholdId, ClassRow> classes,
      SocialData social,
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore,
      UnitState units,
      long day) {
    if (social.groups().isEmpty() || classes.isEmpty()) {
      return social; // 没有批次/没有经济 ⇒ 没有可算的人
    }
    Map<HouseholdRef, long[]> byHousehold = dailyProvisioning(classes, flows, unmetBefore);
    Map<PeopleLotId, PopulationGroup> next = new LinkedHashMap<>(social.groups());
    for (PopulationGroup group : social.groups().values()) {
      // ★ 批次 → 家户：**落点格 + 居住类型**（落点从所属家户取；UNIT 家户经 resolver 派生 unit 当刻 hex）。
      HexCoord at =
          units == null
              ? social.hexOfLot(group.id()).orElse(null)
              : HouseholdPositionResolver.hexOfLot(
                      group.id(), social, units, SimosTimestamp.of(day))
                  .orElse(null);
      if (at == null) {
        continue; // 家户不在 HEX 上且所在 unit 无位置 ⇒ 没有可算的格
      }
      long[] row = byHousehold.get(new HouseholdRef(at, ResidenceKind.ofLot(group.id())));
      if (row == null) {
        continue; // 该格没有这一组家户（世界还没播种到这里，或该池在这格没有人）⇒ 没有可算的满足率
      }
      long stress =
          PopulationDynamics.stressAfter(
              group.physiologicalStress(),
              satisfactionPerMille(row[1], row[0]),
              satisfactionPerMille(row[3], row[2]));
      if (stress != group.physiologicalStress()) {
        next.put(group.id(), group.withPhysiologicalStress(stress));
      }
    }
    return social.withGroups(next);
  }

  /** 一格 + 一种居住类型 = **一组家户**（该格那一组的四行合并读；H0.2 的对接口径）。 */
  private record HouseholdRef(HexCoord hex, ResidenceKind residence) {}

  /** 逐家户组的当日 {@code [粮需求, 粮实得, 布需求, 布实得]}（毫单位）—— 该格该居住类型的**四行求和**。 */
  private static Map<HouseholdRef, long[]> dailyProvisioning(
      Map<HouseholdId, ClassRow> classes,
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore) {
    Map<HouseholdRef, long[]> byHousehold = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : classes.entrySet()) {
      HouseholdId key = entry.getKey();
      ClassRow classRow = entry.getValue();
      long[] row =
          byHousehold.computeIfAbsent(
              new HouseholdRef(classRow.view().hex(), classRow.view().residence()),
              ignored -> new long[4]);
      long grainNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
      long clothNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.CLOTH, 0L);
      row[0] += grainNeed;
      row[2] += clothNeed;
      row[1] += grainNeed - dayUnmet(flows, unmetBefore, key, EconomySettlement.GRAIN);
      row[3] += clothNeed - dayUnmet(flows, unmetBefore, key, EconomySettlement.CLOTH);
    }
    return byHousehold;
  }

  /** 某行某商品**当天新增**的未满足需求（= 结算后 − 结算前）。 */
  private static long dayUnmet(
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore,
      HouseholdId key,
      CommodityId commodity) {
    FlowRow after = flows.get(key);
    long now = after == null ? 0L : after.unmetNeed().getOrDefault(commodity, 0L);
    Map<CommodityId, Long> before = unmetBefore.get(key);
    long was = before == null ? 0L : before.getOrDefault(commodity, 0L);
    return Math.max(0L, now - was);
  }

  /** 满足率（‰）：{@code 需求 == 0 ⇒ 1000}（"这一天没记账"不等于"饿了一天"）；否则 {@code 实得 × 1000 ÷ 需求}，封顶 1000。 */
  static long satisfactionPerMille(long got, long need) {
    if (need <= 0L) {
      return 1000L;
    }
    return Math.min(1000L, Math.max(0L, got) * 1000L / need);
  }

  /** 各行的 {@code unmetNeed} 快照（当日结算前）——只读一份，供"当天新增"的差分用。 */
  private static LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetOf(
      Map<HouseholdId, FlowRow> flows) {
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      copy.put(entry.getKey(), entry.getValue().unmetNeed());
    }
    return copy;
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
   * ★★ <b>行政读数引导</b>（阶段 11b 口径不变）：为"带 {@link GovFormation} 但 gov 片还没有读数"的单位补一条 {@link
   * GovOfficeState#empty(UnitId, long)} 作为本推进基线。
   *
   * <p>确定性：缺读数单位按 {@link UnitId#value()} 升序追加；已有读数原样保留（键序不变）。没有任何缺项 ⇒ 返回入参同一实例 （无 GOV/已引导世界零变化）。
   */
  private static GovState withBootstrapOffices(GovState base, UnitState units, long tick) {
    LinkedHashMap<UnitId, GovOfficeState> offices = new LinkedHashMap<>(base.offices());
    List<UnitId> missing = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovFormation && !offices.containsKey(unit.id())) {
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
   * <p>口径：{@code govState.offices()} 里每个 office 先取单位上的 {@link GovFormation}（没有 ⇒ 不进表 = 税侧整单位跳过）； 再用
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
      if (!(module instanceof GovFormation formation)) {
        continue; // 没有 GovFormation ⇒ 不进效率表 ⇒ 税侧整单位跳过（无 GOV 不征）。
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
    for (Map.Entry<HouseholdId, io.mosire.simos.social.household.Household> entry :
        social.households().entrySet()) {
      composition.put(entry.getKey(), entry.getValue().members());
    }
    return composition;
  }

  /** ★★ P2-A §13.4：Social 人口组成 × 系数表 → 每家户每 tick 的时间预算（毫小时；只读投影）。 */
  private static Map<HouseholdId, Long> laborBudgetsOf(SocialData social, long day) {
    CalendarClock clock = CalendarClock.julianDefault();
    Map<HouseholdId, Long> budgets = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, io.mosire.simos.social.household.Household> entry :
        social.households().entrySet()) {
      long total = 0L;
      for (AgeBracketView view : social.ageBrackets(entry.getKey(), day, clock)) {
        total =
            Math.addExact(
                total,
                Math.multiplyExact(
                    view.count(),
                    EconomySeeder.LABOR_TIME_TABLE.perPersonMilliHours(
                        bracketOf(view), view.sex())));
      }
      budgets.put(entry.getKey(), total);
    }
    return budgets;
  }

  /**
   * 年龄档视图 → {@link LaborTimeTable} 的三档：优先用 social 的档名（{@code 0-14}/{@code 15-59}/{@code 60+}， 与
   * {@code AgeBracket} 的历法边界逐字同源）；档名不认识时退回"天数上界/下界"近似（只作防御，不另立一套历法）。
   */
  private static int bracketOf(AgeBracketView view) {
    switch (view.bracketId()) {
      case "0-14":
        return LaborTimeTable.BRACKET_CHILD;
      case "15-59":
        return LaborTimeTable.BRACKET_ADULT;
      case "60+":
        return LaborTimeTable.BRACKET_ELDER;
      default:
        long childMaxDays = 15L * 365L;
        long elderMinDays = 60L * 365L;
        if (view.maxAgeDays() < childMaxDays) {
          return LaborTimeTable.BRACKET_CHILD;
        }
        if (view.minAgeDays() >= elderMinDays) {
          return LaborTimeTable.BRACKET_ELDER;
        }
        return LaborTimeTable.BRACKET_ADULT;
    }
  }
}
