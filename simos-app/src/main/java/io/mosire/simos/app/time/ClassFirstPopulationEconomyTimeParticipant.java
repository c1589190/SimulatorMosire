package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstSettlement;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovDemand;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRules;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationDynamics;
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
 * ★★ <b>R2c：class-first 生产路径上唯一的人口—经济时间参与者</b>（namespace 仍是 {@code "population"}）。
 *
 * <p>它取代旧 PopulationEconomyTimeParticipant（R3a 已删除）在 {@code Shell} 里的注册位：<b>只调</b> {@link
 * ClassFirstSettlement#settleOneDay(ClassFirstState, ClassFirstSettlement.Inputs)}，<b>不</b>构造
 * {@code 旧日推进器（R3a 已删除）}、<b>不</b>调 {@code 旧结算引擎（R3a 已删除）}（旧两类已在 R3a 随旧结算运行时删除）。
 *
 * <p>★★ <b>每次 {@code simulateWorld} = 一个 proposal、一条 revision、内部逐日</b>。阶段 11b 起，<b>有 GOV 读数</b>时
 * 一天的次序是（写清并实现）：
 *
 * <pre>
 * ① 由 economy.classFirst + actor 家户账 + social 人口构造 settleOneDay 的输入（非空态里 households/config 只作契约形状，
 *    引擎以状态内 config 为权威）；
 * ② settleOneDay(base, inputs) → 新 ClassFirstState + AccountDelta + PopulationDelta + 日审计；
 * ③ AccountDelta 经 {@link ClassFirstActorWriteback} 折进 actor 账本（池级 → 家户级唯一一次展开；土地/农具无 actor 维度 ⇒ 具名 gap）；
 * ④ 算 GOV 效率表：对每个有 {@link GovFormation} 的 office，{@link GovDemand#of} + {@link GovEfficiency#of}；
 *    先检查辖区 Region 是否都在 map 里，缺的记一条具名 WARN（整轮一次）；缺 GOV 读数 ⇒ 该单位不进表（税侧跳过）；
 * ⑤ 收长期税：{@link JurisdictionDailyTax} 传入效率表（无 GOV 单位跳过）——★ 税在收入侧、先于 upkeep，当天税可先供当天俸禄；
 * ⑥ {@link GovDaily#settle}：付款 oracle 从 currentActor 的国库账扣 grain/cloth/silver（按 AvailableStock 可用量；
 *    缺账 ⇒ paid=0），付不足由 GovDaily 记 shortfall；
 * ⑦ 把 GovDaily 的 {@code SignalDraft} 折成 {@link HexCrisisSignal} 写进 currentEconomy（同 {@code (hex,kind)} 覆盖）；
 * ⑧ 家户人口差分经 {@link ClassFirstSocialWriteback} 同步 social 批次（具名 gap）+ 用当日口粮/布读数更新生理压力；
 * ⑨ 每 30 天调 {@code PopulationDynamics.monthly}，出生/死亡经 {@link ClassFirstPopulationWriteback} 接回 classfirst 池/家户账户；
 * ⑩ classes 只读投影经 {@link ClassFirstClassProjection} 与家户账户对齐（键集/地址不变）。
 * </pre>
 *
 * <p>★★ <b>为什么并入本参与者 / 为什么不能另起</b>：module clash —— 另起写 actor 的 participant 会与 population 的 actor
 * 变更同名， {@code TimeProposalResolver} 会拒整次推进；写 gov 片同理。故 gov 片由本参与者代写，且 govState <b>非空</b>时才把 {@code
 * gov:<mapId>} 加进 reads/writes、并在 {@code moduleChanges} 里附 {@code gov ->
 * GovChangeSet.between(before, after)}（{@code GovOfficeState} 的六表语义按 11a 的 Outcome 原样落盘）。
 *
 * <p>★ <b>无 GOV 读数 / {@code classFirst} 空 / {@code range.to} 缺省 ⇒ gov 片一字不加</b>：gov 切片缺失读作 {@link
 * GovState#empty()}；{@code classFirst} 为空或 {@code range.to} 缺省走既有 {@link #unchanged} 路径，不附 gov 片、
 * 不征行政税、不写任何 11b 新日志（无 GOV 世界的 actor/economy/social 三片保持既有字节）。
 *
 * <p>★★ <b>R2c 的世界级聚合</b>：三国的 {@code economy.Seed} 由 {@code ClassFirstState.merge} 把同键池/放贷账户按加法合并成
 * <b>世界级 4 池</b>（不先做 region 维，区域地理后置）；本参与者因此一次推进整个世界，池内的阶层迁移/借贷/消费在世界范围内发生。
 *
 * <p>★★ <b>本轮如实边界</b>（计划允许，但必须点名）：
 *
 * <ul>
 *   <li><b>人口回写按 {@code (格, 居住类型)} 组聚合</b>：classfirst 家户没有年龄/性别维，出生/死亡先按组求和、再按家户人口权重最大余数法摊
 *       （近似在"组内怎么分"，见 {@link ClassFirstPopulationWriteback}）；逐日生理压力仍是聚合满足率口径（见 {@link
 *       ClassFirstSocialWriteback} 类注）；
 *   <li><b>actor 账的土地/农具维度不存在</b>：{@code ownedLand}/{@code tools} 的 AccountDelta 只落 classfirst
 *       池，落账时记具名 gap 并写日志；
 *   <li><b>economy.classes 只是投影</b>：classfirst 池/家户生产账户才是人口/资产真源；{@link ClassRow} 的
 *       population/labor/money 每次推进后从家户账户重算（只读，不再被任何生产路径当权威）。
 * </ul>
 *
 * <p>★ <b>未激活/无上界</b>：{@code range.to} 缺省、或 {@code economy.classFirst} 为空（含未播种与旧 legacy 世界）⇒
 * 三片都交<b>不变</b>变更集（不是空提案），且 <b>绝不回退</b>旧结算；后者写一条 WARN 具名说明。
 *
 * <p>★ <b>“无 GOV ⇒ 不征”具名口径</b>：{@link JurisdictionDailyTax#collect} 只认效率表里的键；查不到效率（= 没有 {@code
 * GovFormation}/没有 GOV 读数）的单位<b>整单位跳过</b>，不读 {@code Jurisdiction.administrationPerMille} 的旧值。无 GOV
 * 时 actor 仍是同一实例、{@link JurisdictionDailyTax.Report#isEmpty()} 为真 ⇒ 既不落账、也不写税日志；本参与者因此新增只读
 * unit/map/gov 切片，写面仍是 actor/economy/social 三片（gov 片只在 govState 非空时写入）。
 */
public final class ClassFirstPopulationEconomyTimeParticipant implements TimeParticipant {

  private static final Logger LOG =
      LoggerFactory.getLogger(ClassFirstPopulationEconomyTimeParticipant.class);

  /** 参与者身份（**不是模块名**：它同时写 {@code economy} / {@code social} / {@code actor} 三片）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";
  private static final String ACTOR = "actor";

  /** 阶段 6.3 新增的<b>只读</b>切片（长期税的管辖/区域/单位位置）。 */
  private static final String UNIT = "unit";

  /** 阶段 6.3 新增的<b>只读</b>切片（区域 hex 集）。 */
  private static final String MAP = "map";

  /** 阶段 11b：gov 切片（GOV 读数；读改写在 govState 非空时启用）。 */
  private static final String GOV = "gov";

  /**
   * 阶段 11b：有 GOV 时整轮一条 INFO。前半是逐 office 的 assessed/paid/shortfall、coverage/efficiency、signals 数；后半把
   * 6.3 的长期税汇总并进同一条（避免 GOV 世界出两条 INFO）。
   */
  private static final String GOV_LOG =
      "行政结算（并入 population 参与者）：mapId={} days={} offices={} signals={} 逐office=[{}]"
          + " 长期税[grain: assessed={} collected={} adminShortfall={} stockShortfall={};"
          + " money: assessed={} collected={} adminShortfall={} stockShortfall={};"
          + " unitsCharged={} householdsCharged={} gaps={}]";

  /** 商品 id 的唯一拼写点（PilotModel 的既有常量；审计汇总与 11b 支付表复用同一份）。 */
  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);

  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);

  /** 税汇总日志的唯一格式（每次推进最多一条；不逐户刷）。 */
  private static final String TAX_LOG =
      "长期税结算（并入 population 参与者）：mapId={} days={} grain[assessed={} collected={} adminShortfall={}"
          + " stockShortfall={}] money[assessed={} collected={} adminShortfall={} stockShortfall={}]"
          + " unitsCharged={} householdsCharged={} gaps={} 摘要={}";

  private final String mapId;

  /** 历法/气候配置服务：一次推进取一次快照（见 {@link #simulateWorld} 开头）。 */
  private final CalendarService calendarService;

  public ClassFirstPopulationEconomyTimeParticipant(String mapId, CalendarService calendarService) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  /** 测试/旧路径：全缺省，不读 store；生产 Shell 必须用两参数构造器（{@link CalendarService#load}）。 */
  public ClassFirstPopulationEconomyTimeParticipant(String mapId) {
    this(mapId, CalendarService.defaults());
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    // ★ C5：一次推进取一次快照——整轮（含 30 天月度人口学）用同一台时钟，不与中途的 apply 混用。
    CalendarClock clock = calendarService.clock();
    EconomyData economy = economyOf(state);
    if (economy.classFirst().isEmpty() && !economy.modes().isEmpty()) {
      // production-runtime：正式生产/市场运行时由旧结算协调器承担
      return new PopulationEconomyTimeParticipant(mapId).simulateWorld(state, range);
    }
    SocialData social = socialOf(state);
    ActorData actor = actorOf(state);
    // ★ 阶段 6.3：长期税只读 unit/map（管辖、区域 hex、单位有效位置）——缺切片/类型不符照既有切片读取器当场抛。
    UnitState units = unitsOf(state);
    GameMap map = mapOf(state);
    // ★ 阶段 11b：gov 切片缺失 ⇒ GovState.empty()（旧档/无 GOV 世界）。
    GovState govState = govOf(state);
    // ★ 引导（控制方 2026-10-01 补）：gov 片是"每 tick 读数"，由本参与者写；只要单位带 GovFormation 而
    //   govState 还没有它的读数，就在本推进里为它补一条 GovOfficeState.empty(...) 作为基线——
    //   否则"建了 GOV 编制"永远不会激活行政结算（没有人负责首建读数）。govState 空且单位都没有编制时
    //   引导集仍为空 ⇒ 无 GOV 世界零变化。
    GovState bootstrappedGov =
        withBootstrapOffices(govState, units, state.meta().timestamp().tick());
    boolean govActive = !bootstrappedGov.offices().isEmpty();

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    for (HouseholdId key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }
    reads.add(actorAddressRoot());
    writes.add(actorAddressRoot());
    for (GoodsAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }
    // ★ 阶段 6.3：长期税只读 unit/map 两片 ⇒ reads 补两片的根地址；writes 不变（仍只写 actor/economy/social）。
    reads.add(unitAddressRoot());
    reads.add(mapAddressRoot());

    Optional<SimosTimestamp> to = range.to();
    ClassFirstState classFirst = economy.classFirst();
    if (to.isEmpty() || classFirst.isEmpty()) {
      if (to.isPresent()) {
        LOG.warn(
            "classFirst 未播种（economy.classFirst 为空）⇒ population 参与者交不变变更集，不回退旧结算：mapId={}", mapId);
      }
      return unchanged(economy, social, actor, reads, writes);
    }

    // ★ 阶段 11b：只在 govState 非空时声明 gov 片的读写；classFirst 空 / 无上界路径已在上面原样返回，不附 gov 片。
    if (govActive) {
      reads.add(govAddressRoot());
      writes.add(govAddressRoot());
    }

    PilotConfig config = classFirst.meta().config();
    if (config == null) {
      throw new IllegalStateException("classFirst 非空但 meta.config 为空（状态损坏）：mapId=" + mapId);
    }

    ClassFirstState current = classFirst;
    // ★★ R2c：旧 R2b 档可能带"旧档迁移器补出的合成 memberships"（class-first 不读它，但会让 classes 投影的
    //    S1 守恒守卫误红）⇒ 推进前先剥掉这一旧口径影子（classFirst 是权威，memberships 不是）。
    EconomyData currentEconomy =
        economy.memberships().isEmpty() ? economy : economy.withMemberships(Map.of());
    ActorData currentActor = actor;
    SocialData currentSocial = social;
    GovState currentGov = bootstrappedGov;
    LinkedHashSet<String> unmappedActorDimensions = new LinkedHashSet<>();
    boolean socialGapsLogged = false;
    JurisdictionDailyTax.Report taxReport = JurisdictionDailyTax.Report.empty();
    AdminSettlementAudit adminAudit = govActive ? new AdminSettlementAudit() : null;
    LinkedHashSet<String> missingAdminRegions = new LinkedHashSet<>();
    long adminSignalDrafts = 0L;
    for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
      ClassFirstSettlement.Inputs inputs = inputsFor(current, currentEconomy, currentActor, day);
      ClassFirstSettlement.Result result = ClassFirstSettlement.settleOneDay(current, inputs);
      ClassFirstActorWriteback.Applied applied =
          ClassFirstActorWriteback.apply(currentActor, currentEconomy, current, result);
      currentActor = applied.data();
      unmappedActorDimensions.addAll(applied.unmappedDimensions());
      // ★★ 阶段 11b：GOV 片有读数时才跑行政结算链；无 GOV 时整段跳过（零日志、零字节、零新开销路）。
      if (govActive) {
        // ④ 算效率表：对每个有 GovFormation 的 office 走 GovDemand + GovEfficiency；辖区 Region 缺失记具名 WARN（整轮一次）。
        Map<UnitId, Long> efficiencyPerMilleByUnit =
            efficiencyTable(currentGov, units, map, currentSocial, missingAdminRegions);
        // ⑤ 长期税：税在收入侧、upkeep 之前 ⇒ 当天税可先供当天俸禄（税基 = 该日结算 + actor 写回后的家户账）。
        JurisdictionDailyTax.Collected taxed =
            JurisdictionDailyTax.collect(currentActor, units, map, day, efficiencyPerMilleByUnit);
        currentActor = taxed.actor();
        taxReport = taxReport.plus(taxed.report());
        // ⑥ GovDaily.settle：付款 oracle 从 currentActor 的国库账扣 grain/cloth/silver（AvailableStock 可用量；缺账
        // paid=0）。
        TreasuryPaymentOracle oracle = new TreasuryPaymentOracle(currentActor);
        // day 是 1 起日号 ⇒ 该日对应的 tick = day - 1L。
        int daysInYear = clock.daysInYearAtTick(day - 1L);
        GovDaily.Outcome settled =
            GovDaily.settle(currentGov, units, map, currentSocial, day, daysInYear, oracle);
        currentActor = oracle.actor();
        currentGov = settled.next();
        adminAudit.record(settled);
        adminSignalDrafts += settled.signals().size();
        // ⑦ SignalDraft → HexCrisisSignal：写进 currentEconomy，同 (hex,kind) 直接覆盖（映射只此一处）。
        if (!settled.signals().isEmpty()) {
          currentEconomy = foldSignals(currentEconomy, settled.signals(), day);
        }
      }
      ClassFirstSocialWriteback.AppliedSocial socialApplied =
          ClassFirstSocialWriteback.apply(currentSocial, currentEconomy, current, result);
      currentSocial = socialApplied.data();
      if (!socialApplied.gaps().isEmpty() && !socialGapsLogged) {
        socialGapsLogged = true;
        LOG.warn(
            "classFirst → social 落账有具名缺口（本轮只报一次）：mapId={} gaps={}", mapId, socialApplied.gaps());
      }
      current = result.state();

      // ★★ R2c：每 30 天（与旧协调器同一窗口）结算出生/死亡，并把 LotChange 接回 classfirst 池/家户账户。
      //   次序与旧协调器一致：先跑完这一天的经济结算与两条写回，再做月度人口学；出生/死亡不产生商品/货币/土地/债务条目。
      if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L) {
        // ★ 既有语义不动：day 是 1 起日号，monthly 一直把它当 nowTick 用（与 ageDaysAt(day) 同轴）；
        //   日历换算在 monthly 内走 clock.dayNumberOfTick(nowTick)，不在这里顺手改 day/tick 口径。
        PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(currentSocial, day, clock);
        currentSocial = outcome.data();
        if (!outcome.isEmpty()) {
          ClassFirstPopulationWriteback.Applied populationApplied =
              ClassFirstPopulationWriteback.apply(current, currentEconomy, outcome.changeList());
          current = populationApplied.state();
          LOG.info(
              "classFirst 月度人口学接回：mapId={} day={} births={} deaths={} net={} classFirstPopulation={} socialPopulation={}",
              mapId,
              day,
              populationApplied.births(),
              populationApplied.deaths(),
              populationApplied.births() - populationApplied.deaths(),
              classFirstPopulation(current),
              socialPopulation(currentSocial));
        }
      }

      // ★★ R2c：每次推进后把只读 classes 投影刷新到与 classfirst 家户账户一致（键集/地址不变）。
      currentEconomy = ClassFirstClassProjection.project(currentEconomy, current, currentActor);
    }
    if (!unmappedActorDimensions.isEmpty()) {
      LOG.info(
          "classFirst → actor 落账：以下资产维度在 actor 账本无对应维度，只落 classFirst 池（具名 gap）：mapId={} dimensions={}",
          mapId,
          unmappedActorDimensions);
    }
    long elapsedDays = to.get().tick() - range.from().tick();
    // ★ 阶段 11b：辖区 Region 缺失的具名 WARN（整轮一条；即使跨多天/多 office 也只报一条汇总）。
    if (!missingAdminRegions.isEmpty()) {
      LOG.warn(
          "行政效率：以下辖区 Region 不在 map.regions()，GovDemand 按既有口径跳过该 Region（整轮一条）：mapId={} 共{}项 {}",
          mapId,
          missingAdminRegions.size(),
          missingAdminRegions);
    }
    // ★ 阶段 11b：有 GOV 时整轮一条 INFO（逐 office 明细 + 长期税汇总并入同一条）。税缺口只另补 WARN，不再多一条 INFO。
    if (govActive) {
      adminAudit.logSummary(mapId, elapsedDays, adminSignalDrafts, taxReport);
      if (!taxReport.gaps().isEmpty()) {
        logTaxSummary(taxReport, elapsedDays);
      }
    } else if (!taxReport.isEmpty()) {
      logTaxSummary(taxReport, elapsedDays);
    }

    EconomyData currentEconomyFinal = currentEconomy.withClassFirst(current);
    // ★ 保序不可变：moduleChanges 用 LinkedHashMap 演进；gov 片只在 govActive 时附加。
    LinkedHashMap<String, ChangeSet> moduleChanges = new LinkedHashMap<>();
    moduleChanges.put(ECONOMY, EconomyChangeSet.between(economy, currentEconomyFinal));
    moduleChanges.put(SOCIAL, SocialChangeSet.between(social, currentSocial));
    moduleChanges.put(ACTOR, ActorChangeSet.between(actor, currentActor));
    if (govActive) {
      moduleChanges.put(GOV, GovChangeSet.between(govState, currentGov));
    }
    return new WorldTimeProposal(NAMESPACE, moduleChanges, reads, writes);
  }

  /**
   * ★ 引导（控制方 2026-10-01 补）：为"带 GovFormation 但 gov 片还没有读数"的单位补一条空读数，作为本推进的基线。
   *
   * <p>确定性：单位按 {@code UnitId.value()} 升序处理；已有读数原样保留（键序不变）、只追加缺失的。 已有读数与引导读数都以 {@code
   * GovOfficeState.empty(...)} 形制给出（tick 取推进起点；settle 会按日覆写）。 无 GovFormation 单位时返回入参同一实例（无 GOV
   * 世界零变化）。
   */
  private static GovState withBootstrapOffices(GovState base, UnitState units, long tick) {
    LinkedHashMap<UnitId, GovOfficeState> offices = new LinkedHashMap<>(base.offices());
    java.util.List<UnitId> missing = new java.util.ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovFormation && !offices.containsKey(unit.id())) {
        missing.add(unit.id());
      }
    }
    if (missing.isEmpty()) {
      return base;
    }
    missing.sort(java.util.Comparator.comparing(UnitId::value));
    for (UnitId id : missing) {
      offices.put(id, GovOfficeState.empty(id, tick));
    }
    return new GovState(offices);
  }

  private static long classFirstPopulation(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.population();
    }
    return total;
  }

  private static long socialPopulation(SocialData social) {
    long total = 0L;
    for (var group : social.groups().values()) {
      total += group.count();
    }
    return total;
  }

  // ── 阶段 11b：GOV 效率表 / 信号折叠 / 付款 oracle / 审计日志 ─────────────────────────────

  /**
   * ★ 算当日 GOV 效率表（单位 → efficiency‰），供长期税查表。
   *
   * <p>口径：{@code govState.offices()} 里每个 office，先取单位上的 {@link GovFormation}（没有 ⇒ 不进表 = 税侧整单位跳过）； 再用
   * {@link GovDemand#of} + {@link GovEfficiency#of} 现算。★ 检查该单位管辖的每个 Region 是否都在 map 里，缺的累积进 {@code
   * missingRegions}（只累积、不抛；调用方整轮汇总成一条具名 WARN）。
   *
   * @param missingRegions 跨日累积的“unit=…,region=…”明细（调用方只在整轮结束时汇总 WARN 一次）
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
        continue; // 状态损坏由 GovDaily.settle 当场抛；税侧只保证“查不到效率就不征”。
      }
      UnitModule module = unit.module().orElse(null);
      if (!(module instanceof GovFormation formation)) {
        continue; // 没有 GovFormation ⇒ 不进效率表 ⇒ JurisdictionDailyTax 整单位跳过（无 GOV 不征）。
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

  /**
   * 把当日 {@link GovDaily.SignalDraft} 折成 {@link HexCrisisSignal} 写进 economy 的 signals 表。
   *
   * <p>★ 同 {@code (hex,kind)} 覆盖：{@code put} 到 {@link CrisisSignalId#idOf} 的键上，旧读数只换值、不追加历史。★ kind
   * 字符串 → 枚举的映射表只有 {@link #signalKind(String)} 一处。
   */
  private static EconomyData foldSignals(
      EconomyData economy, List<GovDaily.SignalDraft> drafts, long day) {
    Map<CrisisSignalId, HexCrisisSignal> next = new LinkedHashMap<>(economy.crisisSignals());
    for (GovDaily.SignalDraft draft : drafts) {
      HexCrisisSignal.Kind kind = signalKind(draft.kind());
      int severity = Math.toIntExact(draft.severity());
      HexCrisisSignal signal =
          new HexCrisisSignal(
              CrisisSignalId.idOf(draft.hex(), kind.name()),
              draft.hex(),
              kind,
              severity,
              day,
              draft.evidence(),
              List.of(),
              List.of(),
              draft.reason());
      next.put(signal.id(), signal); // ★ 同 (hex,kind) 直接覆盖 = 保留最新一条
    }
    return economy.withCrisisSignals(next);
  }

  /** ★ kind 字符串 → {@link HexCrisisSignal.Kind} 的<b>唯一</b>映射点；未知 kind 当场抛，不静默丢信号。 */
  private static HexCrisisSignal.Kind signalKind(String kind) {
    return switch (kind) {
      case GovDaily.KIND_ADMIN_SUPPLY -> HexCrisisSignal.Kind.ADMIN_SUPPLY;
      case GovDaily.KIND_ADMIN_SECURITY -> HexCrisisSignal.Kind.ADMIN_SECURITY;
      case GovDaily.KIND_ADMIN_PAPERWORK -> HexCrisisSignal.Kind.ADMIN_PAPERWORK;
      default -> throw new IllegalStateException("未知的 GovDaily SignalDraft.kind（映射表只此一处）: " + kind);
    };
  }

  // ── 阶段 11b：付款 oracle（只读可见性内实现，不引 app 工具）──────────────────────────────

  /**
   * 国库付款 oracle：从 {@code currentActor} 的 {@code ActorRef(UNIT, unitId)} 国库账扣款。
   *
   * <p>★★ <b>可用量/冻结口径</b>：每次付款都走 {@link AvailableStock}（余额 − 冻结，缺键 = 0）， {@code paid =
   * min(available, requested)}；编辑 {@link GoodsAccount} 的余额表时<b>原样保留两张冻结表</b>（不侵冻结）。 缺账 ⇒ {@code
   * paid = 0}（“没有这本账”= 没有可支配库存；不新建、不猜位置）。同一天同一本账的多次扣款按 GovDaily 的固定资源次序（grain → cloth →
   * silver）在<b>同一份工作账</b>上串行发生，后一次看得到前一次扣减。
   */
  private static final class TreasuryPaymentOracle implements GovDaily.PaymentOracle {

    private final ActorData original;
    private final Map<GoodsAccountKey, GoodsAccount> working;
    private boolean changed;

    TreasuryPaymentOracle(ActorData actor) {
      this.original = Objects.requireNonNull(actor, "actor");
      this.working = new LinkedHashMap<>(actor.accounts());
    }

    /** 付款后的 actor；一笔都没付 ⇒ 返回入参同一实例（上层 between 自然落 Unchanged）。 */
    ActorData actor() {
      return changed ? original.withAccounts(working) : original;
    }

    @Override
    public long pay(UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested) {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(resource, "resource");
      if (requested <= 0L) {
        return 0L; // GovDaily 只对 >0 的请求调本方法；防御性返回 0，不制造“负付款”。
      }
      GoodsAccountKey key = new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unitId.value()), at);
      GoodsAccount account = working.get(key);
      if (account == null) {
        return 0L; // ★ 缺账 ⇒ paid=0（没有可支配库存；不新建账本）。
      }
      long available = availableOf(account, resource);
      long paid = Math.min(available, requested);
      if (paid <= 0L) {
        return 0L;
      }
      working.put(key, debit(account, resource, paid));
      changed = true;
      return paid;
    }

    /** 可用量 = {@link AvailableStock} 的唯一算法（商品/货币各走对应重载；本类不写减法）。 */
    private static long availableOf(GoodsAccount account, GovDaily.GovResource resource) {
      if (resource instanceof GovDaily.Commodity commodity) {
        return AvailableStock.available(account, commodity.commodity());
      }
      if (resource instanceof GovDaily.Money money) {
        return AvailableStock.available(account, money.currency());
      }
      throw new IllegalArgumentException("未知 GovResource: " + resource);
    }

    /** 只改一个余额表：键序、另一张余额表、两张冻结表全部原样带过（冻结不侵）。 */
    private static GoodsAccount debit(
        GoodsAccount account, GovDaily.GovResource resource, long amount) {
      if (amount <= 0L) {
        throw new IllegalArgumentException("amount 必须 > 0: " + amount);
      }
      if (resource instanceof GovDaily.Commodity commodity) {
        Map<CommodityId, Long> nextBalances = new LinkedHashMap<>(account.balances());
        long current = nextBalances.getOrDefault(commodity.commodity(), 0L);
        if (amount > current) {
          throw new IllegalStateException(
              "行政付款商品扣减超出余额（内部不自洽）：key="
                  + account.key()
                  + " commodity="
                  + commodity.name()
                  + " amount="
                  + amount
                  + " balance="
                  + current);
        }
        nextBalances.put(commodity.commodity(), current - amount); // 0 余额保留这条键
        return new GoodsAccount(
            account.key(),
            nextBalances,
            account.money(),
            account.frozenBalances(),
            account.frozenMoney());
      }
      if (resource instanceof GovDaily.Money money) {
        Map<CurrencyId, Long> nextMoney = new LinkedHashMap<>(account.money());
        long current = nextMoney.getOrDefault(money.currency(), 0L);
        if (amount > current) {
          throw new IllegalStateException(
              "行政付款货币扣减超出余额（内部不自洽）：key="
                  + account.key()
                  + " currency="
                  + money.name()
                  + " amount="
                  + amount
                  + " balance="
                  + current);
        }
        nextMoney.put(money.currency(), current - amount); // 0 余额保留这条键
        return new GoodsAccount(
            account.key(),
            account.balances(),
            nextMoney,
            account.frozenBalances(),
            account.frozenMoney());
      }
      throw new IllegalArgumentException("未知 GovResource: " + resource);
    }
  }

  // ── 阶段 11b：有 GOV 时整轮一条 INFO 的逐 office 审计累计 ─────────────────────────────

  /** 一轮 GOV 结算的逐 office 审计累计（本地聚合，不落盘；只在本类日志里读）。 */
  private static final class AdminSettlementAudit {

    private final Map<UnitId, OfficeAudit> offices = new LinkedHashMap<>();

    /** 把一天的 {@link GovDaily.Outcome} 折进逐 office 累计；无座位的 office 只更新读数、不造 assessed/paid/shortfall。 */
    void record(GovDaily.Outcome outcome) {
      Objects.requireNonNull(outcome, "outcome");
      Map<UnitId, List<GovDaily.UpkeepDue>> duesByUnit = new LinkedHashMap<>();
      for (GovDaily.UpkeepDue due : outcome.dues()) {
        duesByUnit.computeIfAbsent(due.unitId(), ignored -> new ArrayList<>()).add(due);
      }
      List<UnitId> ordered = new ArrayList<>(outcome.next().offices().keySet());
      ordered.sort(Comparator.comparing(UnitId::value));
      for (UnitId unitId : ordered) {
        GovOfficeState state = outcome.next().offices().get(unitId);
        if (state == null) {
          throw new IllegalStateException(
              "GovDaily.Outcome.next 缺少 office 读数（状态损坏）: " + unitId.value());
        }
        OfficeAudit audit = offices.computeIfAbsent(unitId, ignored -> new OfficeAudit());
        audit.updateReading(state);
        List<GovDaily.UpkeepDue> dues = duesByUnit.get(unitId);
        if (dues == null || dues.isEmpty()) {
          continue; // 无座位日：GovDaily 不评估/不支付/不发信号，本审计同样不造事实。
        }
        audit.daysSeated++;
        long shortfallTotal = 0L;
        for (GovDaily.UpkeepDue due : dues) {
          shortfallTotal = Math.addExact(shortfallTotal, due.shortfall());
          audit.addDue(due);
        }
        // ★ 与 GovDaily 的三类信号条件逐条同构：supply 每 office 至多一条、security/paperwork 各自覆盖 <1000‰ 时一条。
        if (shortfallTotal > 0L) {
          audit.signals++;
        }
        if (state.securityCoveragePerMille() < GovRules.COVERAGE_FULL_PER_MILLE) {
          audit.signals++;
        }
        if (state.paperworkCoveragePerMille() < GovRules.COVERAGE_FULL_PER_MILLE) {
          audit.signals++;
        }
      }
    }

    /** 整轮一条 INFO：逐 office（按 unitId 升序）汇总 + 长期税汇总并入同一条。 */
    void logSummary(
        String mapId, long days, long totalSignals, JurisdictionDailyTax.Report taxReport) {
      List<Map.Entry<UnitId, OfficeAudit>> ordered = new ArrayList<>(offices.entrySet());
      ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
      StringBuilder text = new StringBuilder();
      for (Map.Entry<UnitId, OfficeAudit> entry : ordered) {
        if (text.length() > 0) {
          text.append("; ");
        }
        text.append(entry.getValue().summary(entry.getKey()));
      }
      LOG.info(
          GOV_LOG,
          mapId,
          days,
          offices.size(),
          totalSignals,
          text.toString(),
          taxReport.grain().assessed(),
          taxReport.grain().collected(),
          taxReport.grain().adminShortfall(),
          taxReport.grain().stockShortfall(),
          taxReport.money().assessed(),
          taxReport.money().collected(),
          taxReport.money().adminShortfall(),
          taxReport.money().stockShortfall(),
          taxReport.unitsCharged(),
          taxReport.householdsCharged(),
          taxReport.gaps().size());
    }
  }

  /** 单个 office 的审计累计（六表逐资源累加 + 最后一次 coverage/efficiency 读数 + 信号草稿计数）。 */
  private static final class OfficeAudit {

    long daysSeated;
    long assessedGrain;
    long paidGrain;
    long shortfallGrain;
    long assessedCloth;
    long paidCloth;
    long shortfallCloth;
    long assessedMoney;
    long paidMoney;
    long shortfallMoney;
    long securityCoveragePerMille;
    long paperworkCoveragePerMille;
    long efficiencyPerMille;
    long bonusPerMille;
    long signals;

    void updateReading(GovOfficeState state) {
      securityCoveragePerMille = state.securityCoveragePerMille();
      paperworkCoveragePerMille = state.paperworkCoveragePerMille();
      efficiencyPerMille = state.efficiencyPerMille();
      bonusPerMille = state.bonusPerMille();
    }

    void addDue(GovDaily.UpkeepDue due) {
      if (due.resource() instanceof GovDaily.Commodity commodity) {
        if (GRAIN.equals(commodity.commodity())) {
          assessedGrain = Math.addExact(assessedGrain, due.assessed());
          paidGrain = Math.addExact(paidGrain, due.paid());
          shortfallGrain = Math.addExact(shortfallGrain, due.shortfall());
          return;
        }
        if (CLOTH.equals(commodity.commodity())) {
          assessedCloth = Math.addExact(assessedCloth, due.assessed());
          paidCloth = Math.addExact(paidCloth, due.paid());
          shortfallCloth = Math.addExact(shortfallCloth, due.shortfall());
          return;
        }
        throw new IllegalStateException("GovDaily 评估了未知商品（审计无法归表）: " + commodity.name());
      }
      if (due.resource() instanceof GovDaily.Money) {
        assessedMoney = Math.addExact(assessedMoney, due.assessed());
        paidMoney = Math.addExact(paidMoney, due.paid());
        shortfallMoney = Math.addExact(shortfallMoney, due.shortfall());
        return;
      }
      throw new IllegalStateException("GovDaily 评估了未知 GovResource: " + due.resource());
    }

    String summary(UnitId unitId) {
      return "unit="
          + unitId.value()
          + " days="
          + daysSeated
          + " assessed[grain="
          + assessedGrain
          + ",cloth="
          + assessedCloth
          + ",silver="
          + assessedMoney
          + "] paid[grain="
          + paidGrain
          + ",cloth="
          + paidCloth
          + ",silver="
          + paidMoney
          + "] shortfall[grain="
          + shortfallGrain
          + ",cloth="
          + shortfallCloth
          + ",silver="
          + shortfallMoney
          + "] coverage[security="
          + securityCoveragePerMille
          + "‰,paperwork="
          + paperworkCoveragePerMille
          + "‰] efficiency="
          + efficiencyPerMille
          + "‰ bonus="
          + bonusPerMille
          + "‰ signals="
          + signals;
    }
  }

  // ── 阶段 6.3：长期税汇总日志（整轮一条；含缺口条数与摘要）──────────────────────────────

  /** 有缺口 ⇒ WARN，否则 INFO；格式与参数表见 {@link #TAX_LOG}（不逐户刷日志）。 */
  private void logTaxSummary(JurisdictionDailyTax.Report report, long days) {
    Object[] args = {
      mapId,
      days,
      report.grain().assessed(),
      report.grain().collected(),
      report.grain().adminShortfall(),
      report.grain().stockShortfall(),
      report.money().assessed(),
      report.money().collected(),
      report.money().adminShortfall(),
      report.money().stockShortfall(),
      report.unitsCharged(),
      report.householdsCharged(),
      report.gaps().size(),
      gapSummary(report.gaps())
    };
    if (report.gaps().isEmpty()) {
      LOG.info(TAX_LOG, args);
    } else {
      LOG.warn(TAX_LOG, args);
    }
  }

  /** 缺口摘要：按 kind 计数 + 最多 5 条明细（确定性；不把整张表倒进日志）。 */
  private static String gapSummary(List<JurisdictionDailyTax.Gap> gaps) {
    StringBuilder summary = new StringBuilder();
    for (JurisdictionDailyTax.GapKind kind : JurisdictionDailyTax.GapKind.values()) {
      int count = 0;
      for (JurisdictionDailyTax.Gap gap : gaps) {
        if (gap.kind() == kind) {
          count++;
        }
      }
      if (count > 0) {
        if (summary.length() > 0) {
          summary.append(", ");
        }
        summary.append(kind).append('×').append(count);
      }
    }
    int shown = 0;
    for (JurisdictionDailyTax.Gap gap : gaps) {
      if (shown == 5) {
        summary.append(" …（共 ").append(gaps.size()).append(" 条）");
        break;
      }
      summary.append(" | ").append(gap.summary());
      shown++;
    }
    return summary.toString();
  }

  // ── 输入构造（经济状态 + actor 家户账 + social 人口）──────────────────────────────────

  /**
   * ★ 构造 {@link ClassFirstSettlement.Inputs}：家户 = classfirst 家户生产账户（人口/劳动/份额）+ actor 家户账（粮/布/货币），放贷
   * = classFirst.lenders。
   *
   * <p>★ <b>非空态里 {@code households}/{@code config} 被 {@code settleOneDay} 忽略</b>（它以状态内 config 为权威）⇒
   * 这里构造是<b>契约形状</b>，人口为 0 的家户被跳过（{@link PilotModel.Household} 的守卫要求 ≥ 1）—— 权威的人口/劳动仍在 {@code
   * ClassFirstState.householdAccounts} 里逐日推进。social 批次不重复进本输入（旧口径的映射在 {@link
   * ClassFirstSocialWriteback} 里单独做）。
   */
  private static ClassFirstSettlement.Inputs inputsFor(
      ClassFirstState state, EconomyData economy, ActorData actor, long day) {
    List<PilotModel.Household> households = new ArrayList<>();
    for (Map.Entry<ClassPoolId, ClassPool> poolEntry : state.classPools().entrySet()) {
      ClassPool pool = poolEntry.getValue();
      List<HouseholdProductionAccount> members = new ArrayList<>();
      for (HouseholdProductionAccount account : state.householdAccounts().values()) {
        if (poolEntry.getKey().equals(account.poolId())) {
          members.add(account);
        }
      }
      if (members.isEmpty()) {
        continue;
      }
      long[] weights = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        weights[i] = members.get(i).population();
      }
      long[] landShares =
          ClassFirstDistribution.largestRemainder(pool.stock(AssetKind.OWNED_LAND), weights);
      long[] toolShares =
          ClassFirstDistribution.largestRemainder(pool.stock(AssetKind.TOOLS), weights);
      for (int i = 0; i < members.size(); i++) {
        HouseholdProductionAccount member = members.get(i);
        if (member.population() < 1L) {
          continue; // PilotModel.Household 的守卫；这些家户的人口仍由 classFirstState 权威承载
        }
        HouseholdId householdId = HouseholdId.parse(member.householdId());
        ClassRow row = economy.classes().get(householdId);
        Map<String, Long> goods = new LinkedHashMap<>();
        long money = 0L;
        if (row != null) {
          GoodsAccount book =
              actor
                  .accounts()
                  .get(new GoodsAccountKey(HouseholdActors.of(householdId), row.view().hex()));
          if (book != null) {
            long grain = book.balances().getOrDefault(GRAIN, 0L);
            long cloth = book.balances().getOrDefault(CLOTH, 0L);
            if (grain > 0L) {
              goods.put(PilotModel.GRAIN, grain);
            }
            if (cloth > 0L) {
              goods.put(PilotModel.CLOTH, cloth);
            }
            for (long value : book.money().values()) {
              money = Math.addExact(money, value);
            }
          }
        }
        households.add(
            new PilotModel.Household(
                member.householdId(),
                member.name(),
                pool.classPositionId(),
                member.population(),
                member.laborPerCapita(),
                goods,
                money,
                landShares[i],
                toolShares[i],
                member.participationSharePerMille()));
      }
    }
    return new ClassFirstSettlement.Inputs(
        day, null, households, new ArrayList<>(state.lenders().values()));
  }

  // ── 未激活：不变变更集 ──────────────────────────────────────────────────────────────

  private static WorldTimeProposal unchanged(
      EconomyData economy,
      SocialData social,
      ActorData actor,
      Set<String> reads,
      Set<String> writes) {
    return new WorldTimeProposal(
        NAMESPACE,
        Map.of(
            ECONOMY, EconomyChangeSet.between(economy, economy),
            SOCIAL, SocialChangeSet.between(social, social),
            ACTOR, ActorChangeSet.between(actor, actor)),
        reads,
        writes);
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

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

  /** ★ 阶段 6.3：长期税只读 unit（管辖 + 有效位置）——缺切片/类型不符照既有读取器当场抛。 */
  private static UnitState unitsOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(UNIT)
            .orElseThrow(() -> new IllegalStateException("state 里没有 unit 切片（装配故障：长期税要求切片在场）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /** ★ 阶段 6.3：长期税只读 map（区域的 hex 集）——缺切片/类型不符照既有读取器当场抛。 */
  private static GameMap mapOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(MAP)
            .orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障：长期税要求切片在场）"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** ★ 阶段 11b：读 gov 切片；<b>缺切片 ⇒ {@link GovState#empty()}</b>（旧档/无 GOV 世界），类型不符则照既有读取器当场抛。 */
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

  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  /** ★ 阶段 6.3：只读 unit 切片的根地址（声明用；具体写入方 {@code UnitTimeParticipant} 仍用 {@code unit:<id>}）。 */
  private String unitAddressRoot() {
    return new Address(List.of(new Namespace(UNIT), Entity.of(mapId))).canonical();
  }

  /** ★ 阶段 6.3：只读 map 切片的根地址。 */
  private String mapAddressRoot() {
    return new Address(List.of(new Namespace(MAP), Entity.of(mapId))).canonical();
  }

  /** ★ 阶段 11b：gov 切片的根地址（只在 govState 非空时进 reads/writes）。 */
  private String govAddressRoot() {
    return new Address(List.of(new Namespace(GOV), Entity.of(mapId))).canonical();
  }

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。{@code <key>} 是 {@link GoodsAccountKey#toString()} 的产物，<b>本类不复述那个格式</b>。
   */
  private String accountAddress(GoodsAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }
}
