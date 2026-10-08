package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>P2-B §13.4/§13.5：每 tick 的家户劳动利润率排队落点</b>（写 {@code allocations} 工作副本的唯一新写者）。
 *
 * <p>★★ <b>两阶段（为什么不是一个家户一个家户就地写）</b>：一条 unit 是**共享**的生产活动（主 unit 由多个家户按 {@code
 * HouseholdLaborCommitment} 出工；家户自营 unit 通常只有一个 operator 家户）。因此"这个 unit 本 tick 最大可吸收多少劳动"
 * 是<b>全局</b>上界，不能每家各算一次满额。本类分两阶段：
 *
 * <ol>
 *   <li><b>逐家户排队（只算不写）</b>：按 {@link LaborQueueBook#plan} 用家户自己的时间预算排队，得到"想要多少"；
 *   <li><b>逐 unit 全局封顶</b>：Σ 各家户对同一 unit 的想要量若超过该 unit 的最大可吸收量，按各户想要量比例 （最大余数法）缩到上界 —— 公平、确定、且只减不增；
 *   <li><b>逐家户写回</b>：同 (家户, unit) 复用既有配额行的 id/批次/actor，只改 laborMilli（没有才新发）； 最终拿不到量/预期 ≤ 0 的 unit
 *       的旧行整条删除（劳动留在空缺，不塞给别的 unit）。
 * </ol>
 *
 * <p>★★ <b>哪些活动"被保留、不排队"</b>（既有配额原样保留并先占预算，见 {@link LaborQueueBook#isPreservedByQueue}）：{@code
 * laborPerUnit ≤ 0} 的非劳动活动、没有产出配方的承运/贸易、 产出一个价都没有的活动，以及 R4-E2b 当天刚进入的试产 unit。只有"确实定过价、且预期 ≤
 * 0"的活动会被判为空缺。
 *
 * <p>★★ <b>C7（Z1b）：{@code GOV_SERVICE} 承诺是最高优先级、不可缩</b>——不进队列、不被重算、不被按比例缩； {@code
 * preserveIntoBudget} 先整额保留 GOV_SERVICE，只用剩余预算给 PRODUCTION 排队/缩。若某户 {@code Σ GOV_SERVICE > 家户
 * laborMilli}（不变量本不该允许）⇒ 具名 {@code LABOR_COMMITMENT_CONTRACT} ERROR + fail-closed，绝不静默缩/丢；
 * 队列也不得创建/覆盖 GOV_SERVICE（只允许政府工具写，Z3）。
 *
 * <p>★ <b>为什么按家户全局串行而不是按 hex 并行</b>：时间预算是**家户**的资源，可能有多条 unit（多生产方式）跨组织； 按 hex
 * 分区会让"保留配额"与"新分配"在合并后才对账。家户数 × unit 数是可控量级，串行保证 {@code Σallocations(household) ≤ laborMilli}
 * 在写回前就成立（{@code EconomyData} 的构造期守卫是第二道）。
 *
 * <p>★ <b>确定性</b>：家户 / unit / 配额全部按稳定 id 排序遍历；比例缩放在每个 unit 内按下标序（= 家户 id 升序）； 无随机、无时钟、无
 * UUID；同输入同输出。
 */
final class LaborQueueSettlement {

  private LaborQueueSettlement() {}

  /**
   * ★ <b>兼容重载（本日没有家户账视图 / 不收套利决定）</b>：等价于传空账视图与一个丢弃用的收集器 ——
   * 套利活动<b>照常评估与排序</b>（行为只有一份），只是决定没有出口（读口/单模块夹具用）。
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits) {
    return apply(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        null,
        new MarketArbitragePlan.Collector());
  }

  /**
   * 对 {@code session} 的 {@code allocations} 工作副本执行一次全量排队（每个世界日调用一次；旧档 {@code modes}
   * 为空时不调用，旧路径逐值不变）。
   *
   * <p>★★ <b>2026-10-08（阶段 1 统一活动选择器 + 套利作为一种活动）</b>：本方法是 §4.3.1 的落点 —— <b>同一个排序器</b>（{@link
   * ActivitySelector}）对"生产 offer + 套利 offer"排一次序，再按序分配家户的
   * <b>劳动</b>预算；套利活动的<b>库存/现金</b>那一侧由市场阶段按分配结果落成订单（见 {@link TradeArbitrageActivity} 与 {@link
   * MarketArbitragePlan}）。
   *
   * <ul>
   *   <li><b>同一份快照（§4.3.5）</b>：{@link HouseholdResourceSnapshot} 在排序之前<b>一次</b>构建（阶段 0），
   *       排序与规模都用它；执行阶段不回头重排；
   *   <li><b>生活保留优先（I15）</b>：套利只<b>加买</b>、绝不减卖，也不碰 {@code life}/{@code demandParts} 算式；
   *   <li><b>生产投入不被侵占（I14）</b>：本方法的调用点在投入扣减<b>之后</b>（ {@code
   *       EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION = true}）⇒ 快照里的库存已是扣完料的余额。
   * </ul>
   *
   * @param session 结算会话（读 base/工作副本；只写 {@code allocations}）
   * @param index 当天的派生索引（可用资产/产能/actor→家户只读）
   * @param day 当前世界日（报告/日志用）
   * @param composition 家户人口组成的只读投影（挑新配额挂哪个批次）
   * @param entryTrialUnits 今天刚由候选预设进入的试产 unit（R4-E2b）：它们的 {@code modeKey} 不是 {@code mode:} 前缀 ⇒
   *     **本日保留试产配额原样**；自动组织阶段今天新建的 unit（{@code mode:} 前缀）照常进队列
   * @param accounts 当日家户账的活视图（{@code null} = 没有账视图：套利活动按"无可动库存/货币"评估，
   *     不会凭空吃货，也不会抛）；它只被<b>读</b>，本方法不写任何账户
   * @param arbitrageCollector 套利决定的出口（不得为 null；排序的产出 → 市场阶段的输入）。 ★ 由 {@code EconomySettlement}
   *     传入，市场阶段用 {@code toPlan()} 取走
   * @return 本日的可读排队报告
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(composition, "composition");
    Objects.requireNonNull(entryTrialUnits, "entryTrialUnits");
    Objects.requireNonNull(arbitrageCollector, "arbitrageCollector");
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    LinkedHashMap<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    Map<IndustryId, Industry> industryTemplates = session.sheet().industries();
    LinkedHashMap<HexCoord, Market> markets = session.sheet().markets();
    Map<ProductionUnitId, OperatorCondition> conditions = session.sheet().operatorConditions();
    LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises =
        session.sheet().productionOrganizations();

    // ── 组织 → unit（mode 读数的来源；旧 unit 没有组织 ⇒ Optional.empty）────────────────────
    Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess = new LinkedHashMap<>();
    for (ProductionEnterprise enterprise : enterprises.values()) {
      if (enterprise.unitId().isEmpty()) {
        continue;
      }
      ProductionUnitId unitId = enterprise.unitId().get();
      if (units.containsKey(unitId)) {
        enterpriseByProcess.putIfAbsent(unitId, enterprise);
      }
    }

    // ── 既有配额按家户分组 + activity → 家户 索引（候选判定的 O(1) 来源）────────────────────
    Map<HouseholdId, List<HouseholdLaborCommitment>> laborCommitmentsByHousehold =
        new LinkedHashMap<>();
    Map<String, Set<HouseholdId>> allocationHouseholdsByActivity = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      laborCommitmentsByHousehold
          .computeIfAbsent(laborCommitment.household(), ignored -> new ArrayList<>())
          .add(laborCommitment);
      allocationHouseholdsByActivity
          .computeIfAbsent(laborCommitment.activity(), ignored -> new LinkedHashSet<>())
          .add(laborCommitment.household());
    }

    // ── 候选 unit 按家户归集 ─────────────────────────────────────────────────────────────
    List<HouseholdId> orderedHouseholds = new ArrayList<>(householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, List<ProductionProcess>> candidatesByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      candidatesByHousehold.put(household, new ArrayList<>());
    }
    for (ProductionProcess unit : units.values()) {
      for (HouseholdId household :
          candidateHouseholdsOf(
              unit,
              householdEconomies,
              enterpriseByProcess,
              allocationHouseholdsByActivity,
              index)) {
        List<ProductionProcess> list = candidatesByHousehold.get(household);
        if (list != null && !list.contains(unit)) {
          list.add(unit);
        }
      }
    }
    for (List<ProductionProcess> list : candidatesByHousehold.values()) {
      list.sort(Comparator.comparing(candidate -> candidate.id().value()));
    }

    // ── 阶段 0：套利活动的"同一份资源快照"（§4.3.5）+ 逐户价目表（§3.3；逐 tick 派生、不进状态）──────
    //   ★ 它必须建在**排序之前**、只建一次：排序与规模都用这一份，执行阶段不回头重算（否则同一存档两跑不同）。
    //   ★ 库存/货币取"当日劳动分配时刻的可动余额"（扣冻结）——默认预设下周期投入**已经扣走**
    //     （EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION = true）⇒ 生产投入批次不会被套利看见（I14）。
    Map<HouseholdId, HouseholdResourceSnapshot> arbitrageSnapshots =
        buildArbitrageSnapshots(
            day, orderedHouseholds, householdEconomies, candidatesByHousehold, markets, accounts);
    HouseholdValuationBook valuations =
        HouseholdValuationBook.derive(
            householdEconomies, arbitrageSnapshots, HouseholdValuationBook.NO_TRADE_HISTORY);

    // ── 阶段 1：逐家户排队（只算不写）────────────────────────────────────────────────────
    List<HouseholdWork> works = new ArrayList<>();
    // ★ 套利活动实体（逐户一条；阶段 3 的"执行"要用它 —— 不重算、不重排，§4.3.5）
    Map<HouseholdId, TradeArbitrageActivity> arbitrageByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      List<ProductionProcess> candidates = candidatesByHousehold.getOrDefault(household, List.of());
      List<HouseholdLaborCommitment> householdLaborCommitments =
          laborCommitmentsByHousehold.getOrDefault(household, List.of());
      if (candidates.isEmpty()) {
        // ★ 没有可参与的生产活动：不动它的任何既有配额（自由家户劳动/纯消费户）。★ 但**套利配额行必须每日清理**：
        //   否则"昨天分了套利劳动、今天没有机会"的户会永远留着一条占预算的空配额（静默占住 I13 的可用量）。
        removeStaleArbitrageCommitments(laborCommitments, householdLaborCommitments, day);
        continue;
      }
      PeopleLotId lot = chooseLot(household, laborCommitmentsByHousehold, composition);
      if (lot == null) {
        if (EconomyLog.population().isDebugEnabled()) {
          EventLog.channel(EconomyLog.population())
              .debug(
                  LogEvent.of(
                      "LABOR_QUEUE_NO_LOT",
                      EconomyLogSource.ECONOMY_POPULATION,
                      "day",
                      day,
                      "household",
                      household.value(),
                      "candidates",
                      candidates.size(),
                      "reason",
                      "no-composition-lot"));
        }
        continue; // 没有可挂批次 ⇒ 不猜、不重排（既有配额原样保留；预算不变量不动）
      }
      long budget = Math.max(0L, householdEconomy.laborMilli());
      // ★★ C7：GOV_SERVICE 承诺是**最高优先级、不可缩**。先按 activity 建索引（队列既不排队、也不覆盖它），
      //   再整额校验"Σ GOV_SERVICE ≤ 家户 laborMilli"——★ Z7d-1 起越界是**饥饿导致的 underfed 合法态**
      //   （职位行保留；有效供给由 app 桥 cap），队列对本户整体跳过、不给 PRODUCTION 任何小时，绝不静默缩/丢 GOV_SERVICE。
      Set<String> govServiceActivities = new LinkedHashSet<>();
      long govServiceLaborMilli = 0L;
      for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          govServiceActivities.add(laborCommitment.activity());
          govServiceLaborMilli = Math.addExact(govServiceLaborMilli, laborCommitment.laborMilli());
        }
      }
      if (govServiceLaborMilli > budget) {
        // ★★ Z7d-1：饥饿把家户时间预算饿到低于 GOV_SERVICE 职位总额 ⇒ **不是契约故障**（C7 要求职位行不缩/不删）。
        //   本户预算已被政府承诺占满，队列不给 PRODUCTION 任何小时：跳过本户（活表由 applyLaborBudgetsInto 把
        //   PRODUCTION 缩到 0；这里不再写回，避免覆盖/删除 GOV_SERVICE）。有效供给由 app 供给桥 cap 并记 underfed。
        if (EconomyLog.population().isDebugEnabled()) {
          EventLog.channel(EconomyLog.population())
              .debug(
                  LogEvent.of(
                      "LABOR_QUEUE_GOV_SERVICE_UNDERFED",
                      EconomyLogSource.ECONOMY_POPULATION,
                      "day",
                      day,
                      "household",
                      household.value(),
                      "govServiceLaborMilli",
                      govServiceLaborMilli,
                      "budgetMilli",
                      budget,
                      "reason",
                      "starvation-budget-below-gov-service-claims"));
        }
        continue;
      }
      Map<ProductionUnitId, ProductionProcess> candidateUnitsById = new LinkedHashMap<>();
      for (ProductionProcess unit : candidates) {
        candidateUnitsById.put(unit.id(), unit);
      }
      Market market = marketOf(householdEconomy, candidates, markets);
      List<LaborQueueBook.Offer> offers = new ArrayList<>();
      Set<ProductionUnitId> queuedUnits = new LinkedHashSet<>();
      for (ProductionProcess unit : candidates) {
        if (govServiceActivities.contains(unit.id().value())) {
          continue; // ★ C7：该户在此 unit 上有 GOV_SERVICE 承诺 ⇒ 不进队列（队列不得创建/覆盖政府承诺）
        }
        if (entryTrialUnits.contains(unit.id())
            && !unit.modeKey().startsWith(EconomyEnterpriseSettlement.MODE_KEY_PREFIX)) {
          continue; // ★ R4-E2b 试产 unit：本日保留试产配额（不放进队列 ⇒ 被算进 preserved）
        }
        Industry industry = industryTemplates.get(unit.industry());
        if (industry == null) {
          continue; // 坏状态：不静默删配额（候选里没有 offer ⇒ 被算进 preserved）
        }
        Optional<ProductionModeId> modeId = modeIdOf(unit, enterpriseByProcess);
        String rankModeKey = modeId.map(ProductionModeId::value).orElse(unit.modeKey());
        LaborQueueBook.Offer offer =
            LaborQueueBook.offer(
                unit, industry, market, conditions.get(unit.id()), index, modeId, rankModeKey);
        if (!LaborQueueBook.isPreservedByQueue(offer)) {
          offers.add(offer);
          queuedUnits.add(unit.id());
        }
      }

      // ★★ §4.3.4：**套利活动与生产进同一个 offers 列表**（同一个排序器排一次序 ⇒ "最划算的先做"）。
      //   ★ 无论有没有机会都要发一条 offer：没有机会的那一条 grant 恒 0，它的作用是让写回阶段**删掉昨天的
      //     套利配额行**（E4"机会抹平后资源全落回生产"就落在这一条上）—— 这是"不排队的既有配额会被 preserved
      //     整额保留"这条既有规则的必然要求。
      ProductionUnitId arbitrageUnit =
          new ProductionUnitId(TradeArbitrageActivity.activityKeyOf(household));
      TradeArbitrageActivity.Evaluation arbitrage =
          TradeArbitrageActivity.evaluate(
              household, householdEconomy, valuations, arbitrageSnapshots.get(household));
      offers.add(arbitrageOffer(arbitrageUnit, arbitrage));
      queuedUnits.add(arbitrageUnit);
      arbitrage.activity().ifPresent(activity -> arbitrageByHousehold.put(household, activity));
      if (arbitrage.hasOpportunity() && EconomyLog.settlement().isDebugEnabled()) {
        logArbitrageDecision(day, household, arbitrageUnit, arbitrage);
      } else if (!arbitrage.hasOpportunity() && EconomyLog.settlement().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "ARBITRAGE_NO_OPPORTUNITY",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "reason",
                    arbitrage.reason(),
                    "market",
                    arbHasMarket(arbitrageSnapshots.get(household))));
      }

      // 保留量 = GOV_SERVICE 整额 + 不参与排队的 PRODUCTION 既有配额；GOV_SERVICE 绝不进比例缩。
      long preserved = govServiceLaborMilli;
      for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          continue; // 已整额计入
        }
        if (!queuedUnits.contains(new ProductionUnitId(laborCommitment.activity()))) {
          preserved = Math.addExact(preserved, laborCommitment.laborMilli());
        }
      }
      if (preserved > budget) {
        // 合法状态到不了这里（构造期守卫；GOV_SERVICE 越界已在上面 fail-closed），但排队写回不得以坏状态为借口
        // 超预算：先整额保 GOV_SERVICE，只对 PRODUCTION 既有保留量按比例缩到剩余预算以内。
        preserveIntoBudget(
            laborCommitments, householdLaborCommitments, queuedUnits, budget, govServiceLaborMilli);
        preserved = budget;
      }
      LaborQueueBook.Plan desired = LaborQueueBook.plan(household, budget, preserved, offers);
      works.add(
          new HouseholdWork(
              household,
              budget,
              preserved,
              lot,
              candidateUnitsById,
              householdLaborCommitments,
              desired));
    }

    // ── 阶段 2：逐 unit 全局封顶（按各家户想要量比例缩；只减不增）──────────────────────────
    Map<String, Long> finalGrantByUnitHousehold = new LinkedHashMap<>();
    Map<ProductionUnitId, List<Map.Entry<HouseholdId, Long>>> desiredByUnit = new LinkedHashMap<>();
    Map<ProductionUnitId, Long> capByUnit = new LinkedHashMap<>();
    for (HouseholdWork work : works) {
      for (LaborQueueBook.Decision decision : work.desired().decisions()) {
        if (decision.grantedLaborMilli() <= 0L) {
          continue;
        }
        ProductionUnitId unitId = decision.offer().unitId();
        capByUnit.put(unitId, decision.offer().maxAbsorbableLaborMilli());
        desiredByUnit
            .computeIfAbsent(unitId, ignored -> new ArrayList<>())
            .add(Map.entry(work.household(), decision.grantedLaborMilli()));
      }
    }
    // ★ 被"保留"的既有配额也占该 unit 的可吸收量（如甲户市场未定价 ⇒ 保留、乙户正常价 ⇒ 排队；同一 unit 时
    //   必须先扣掉保留量，否则会超发）。保留量 = 该户不在排队集合里的既有配额。
    Map<ProductionUnitId, Long> reservedByUnit = new LinkedHashMap<>();
    for (HouseholdWork work : works) {
      Set<ProductionUnitId> queuedForWork = new LinkedHashSet<>();
      for (LaborQueueBook.Decision decision : work.desired().decisions()) {
        queuedForWork.add(decision.offer().unitId());
      }
      for (HouseholdLaborCommitment laborCommitment : work.householdLaborCommitments()) {
        ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
        if (!queuedForWork.contains(unitId) && capByUnit.containsKey(unitId)) {
          reservedByUnit.merge(unitId, laborCommitment.laborMilli(), Long::sum);
        }
      }
    }
    for (Map.Entry<ProductionUnitId, List<Map.Entry<HouseholdId, Long>>> entry :
        desiredByUnit.entrySet()) {
      ProductionUnitId unitId = entry.getKey();
      List<Map.Entry<HouseholdId, Long>> desired = entry.getValue();
      long cap =
          Math.max(
              0L, capByUnit.getOrDefault(unitId, 0L) - reservedByUnit.getOrDefault(unitId, 0L));
      long total = 0L;
      for (Map.Entry<HouseholdId, Long> wanted : desired) {
        total += wanted.getValue();
      }
      if (total <= cap) {
        for (Map.Entry<HouseholdId, Long> wanted : desired) {
          finalGrantByUnitHousehold.put(key(unitId, wanted.getKey()), wanted.getValue());
        }
        continue;
      }
      long[] weights = new long[desired.size()];
      for (int i = 0; i < desired.size(); i++) {
        weights[i] = desired.get(i).getValue();
      }
      long[] parts = ProportionalSplit.byDenominator(cap, weights, total);
      for (int i = 0; i < desired.size(); i++) {
        finalGrantByUnitHousehold.put(key(unitId, desired.get(i).getKey()), parts[i]);
      }
    }

    // ── 阶段 3：逐家户写回 + 报告/日志 ───────────────────────────────────────────────────
    List<LaborQueueBook.Plan> plans = new ArrayList<>();
    for (HouseholdWork work : works) {
      List<LaborQueueBook.Decision> decisions = new ArrayList<>();
      long allocated = work.preserved();
      for (LaborQueueBook.Decision decision : work.desired().decisions()) {
        long finalGrant =
            finalGrantByUnitHousehold.getOrDefault(
                key(decision.offer().unitId(), work.household()), 0L);
        allocated += finalGrant;
        String outcome = decision.outcome();
        if (decision.grantedLaborMilli() > 0L) {
          if (finalGrant <= 0L) {
            outcome = "IDLE_UNIT_MAX_ABSORBABLE_SHARED";
          } else if (finalGrant < decision.grantedLaborMilli()) {
            outcome = "GRANTED_SHARED_CAP";
          } else {
            outcome = "GRANTED";
          }
        }
        decisions.add(
            new LaborQueueBook.Decision(decision.rank(), decision.offer(), finalGrant, outcome));
      }
      LaborQueueBook.Plan plan =
          new LaborQueueBook.Plan(
              work.household(),
              work.budget(),
              work.preserved(),
              allocated,
              Math.max(0L, work.budget() - allocated),
              decisions);
      applyPlan(
          laborCommitments,
          work.householdLaborCommitments(),
          plan,
          work.lot(),
          work.candidateUnitsById(),
          day);
      // ── §4.3.1 第 ④ 步：按分配结果<b>执行</b>套利活动（交回声明式变更意向，不改任何账户）─────────
      //   ★ 只有"劳动真分到了"才执行（分配结果驱动，不是需求驱动）；库存/现金那一侧由市场阶段按
      //     "最后优先级 + 只吃剩余现金"再封一道（见 MarketSettlement.ordersFor）。
      //   ★ 如实边界（N13）：本方法之后 applyLaborBudgetsInto 还会对 PRODUCTION 类配额做一次"按比例缩到预算内"，
      //     套利配额与生产配额同属 PRODUCTION ⇒ 被缩的是哪一侧由那次比例缩决定；本条日志记的是**缩之前**的
      //     granted/desired，两者一起读才看得出"谁被缩了"。
      for (LaborQueueBook.Decision decision : plan.decisions()) {
        if (decision.grantedLaborMilli() <= 0L
            || !TradeArbitrageActivity.isArbitrageActivity(decision.offer().unitId().value())) {
          continue;
        }
        TradeArbitrageActivity activity = arbitrageByHousehold.get(work.household());
        if (activity == null) {
          continue; // 不该发生（offer 由同一个 evaluate 产出）；防御性跳过，不猜
        }
        HouseholdActivity.Need need = activity.resourceNeed(activity.snapshot());
        activity
            .execute(
                new HouseholdActivity.Allocation(
                    decision.grantedLaborMilli(), need.moneyMilli(), need.goods()))
            .ifPresent(
                intent -> {
                  if (intent instanceof HouseholdActivity.Trade trade) {
                    arbitrageCollector.add(
                        new MarketArbitragePlan.Instruction(
                            work.household(),
                            trade.direction(),
                            trade.commodity(),
                            trade.quantityMilli(),
                            trade.reservationMicro(),
                            trade.marketMicro(),
                            trade.edgeMicro(),
                            decision.grantedLaborMilli()));
                  }
                });
        if (EconomyLog.settlement().isDebugEnabled()) {
          EventLog.channel(EconomyLog.settlement())
              .debug(
                  LogEvent.of(
                      "ARBITRAGE_GRANTED",
                      EconomyLogSource.ECONOMY_ARBITRAGE,
                      "day",
                      day,
                      "household",
                      work.household().value(),
                      "activity",
                      decision.offer().unitId().value(),
                      "rank",
                      decision.rank(),
                      "yieldScaled",
                      decision.offer().netPerLaborScaled(),
                      "laborMilli",
                      decision.grantedLaborMilli(),
                      "outcome",
                      decision.outcome()));
        }
      }
      plans.add(plan);

      if (EconomyLog.enterprise().isDebugEnabled()) {
        EventLog.channel(EconomyLog.enterprise())
            .debug(
                LogEvent.of(
                    "LABOR_QUEUE_PLAN",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "plan",
                    LaborQueueBook.describe(plan)));
      }
      if (EconomyLog.trace().isTraceEnabled()) {
        for (LaborQueueBook.Decision decision : plan.decisions()) {
          EventLog.channel(EconomyLog.trace())
              .trace(
                  LogEvent.of(
                      "LABOR_QUEUE_DECISION",
                      EconomyLogSource.ECONOMY_POPULATION,
                      "day",
                      day,
                      "household",
                      work.household().value(),
                      "rank",
                      decision.rank(),
                      "decision",
                      LaborQueueBook.describe(decision)));
        }
      }
    }

    LaborQueueReport report = new LaborQueueReport(day, plans);
    if (!plans.isEmpty()) {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "LABOR_QUEUE",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "households",
                  plans.size(),
                  "budgetMilli",
                  report.totalBudgetMilli(),
                  "allocatedMilli",
                  report.totalAllocatedMilli(),
                  "idleMilli",
                  report.totalIdleMilli()));
      session
          .sheet()
          .meta()
          .ifPresent(meta -> HouseholdLaborCommitmentFeed.publish(meta.mapId(), report));
    }
    return report;
  }

  /** 阶段 1 的中间件：一个家户的预算/保留量/批次/候选 unit/既有配额快照/想要的排队结果。 */
  private record HouseholdWork(
      HouseholdId household,
      long budget,
      long preserved,
      PeopleLotId lot,
      Map<ProductionUnitId, ProductionProcess> candidateUnitsById,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      LaborQueueBook.Plan desired) {

    HouseholdWork {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(lot, "lot");
      Objects.requireNonNull(candidateUnitsById, "candidateUnitsById");
      Objects.requireNonNull(householdLaborCommitments, "householdAllocations");
      Objects.requireNonNull(desired, "desired");
    }
  }

  /** (unit, household) 的复合键拼写点（阶段 2/3 共用；无状态）。 */
  private static String key(ProductionUnitId unitId, HouseholdId household) {
    return unitId.value() + "\u0000" + household.value();
  }

  // ── 套利活动（§4.3；阶段 1 新增）────────────────────────────────────────────────────────

  /**
   * ★★ <b>阶段 0：逐户资源快照（§4.3.5 的"同一份"）</b>。
   *
   * <p>★ <b>为什么必须建在排序之前、且只建一次</b>：排序用的所有投入必须是"本 tick 开始的那一份"；若某活动执行后 回头重算，顺序就依赖执行副效果 ⇒ 同一存档两跑不同（不变量
   * I7/E6）。
   *
   * <p>★ <b>库存/货币怎么取</b>：{@code 可动 = max(0, 余额 − 冻结)}。{@code accounts == null}（单模块夹具/读口）⇒ 一律按 0 ——
   * 于是套利活动<b>评估得出来但吃不到任何货</b>（buy 的 {@code cashAffordable = 0} ⇒ 具名 {@code NO_FUNDS}），不会凭空行动。
   *
   * <p>★ <b>价表怎么取</b>：优先本户居住格（与市场轮同一口径 {@code marketOf}）；本户无市场 ⇒ {@code null} （活动具名 {@code
   * NO_MARKET}，不猜价）。
   */
  private static Map<HouseholdId, HouseholdResourceSnapshot> buildArbitrageSnapshots(
      long day,
      List<HouseholdId> orderedHouseholds,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, List<ProductionProcess>> candidatesByHousehold,
      Map<HexCoord, Market> markets,
      AccountSession accounts) {
    Map<HouseholdId, HouseholdResourceSnapshot> snapshots = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        continue;
      }
      Market market =
          marketOf(
              householdEconomy, candidatesByHousehold.getOrDefault(household, List.of()), markets);
      Map<CommodityId, Long> goods =
          accounts == null
              ? Map.of()
              : netOfGoods(
                  accounts.householdGoods().get(household),
                  accounts.householdFrozenGoods().get(household));
      Map<CurrencyId, Long> money =
          accounts == null
              ? Map.of()
              : netOfMoney(
                  accounts.householdMoney().get(household),
                  accounts.householdFrozenMoney().get(household));
      snapshots.put(
          household,
          new HouseholdResourceSnapshot(
              day, household, market, goods, money, Math.max(0L, householdEconomy.laborMilli())));
    }
    return snapshots;
  }

  /** {@code 余额 − 冻结}（逐键；缺冻结 = 0；结果夹到 ≥ 0；保序）。 */
  private static Map<CommodityId, Long> netOfGoods(
      Map<CommodityId, Long> stock, Map<CommodityId, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> net = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /** {@code 余额 − 冻结}（逐币种；缺冻结 = 0；结果夹到 ≥ 0；保序）。 */
  private static Map<CurrencyId, Long> netOfMoney(
      Map<CurrencyId, Long> stock, Map<CurrencyId, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CurrencyId, Long> net = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /**
   * ★★ <b>把套利活动变成一条 {@link LaborQueueBook.Offer}</b>（§4.3.4：与生产 offer 同住一个列表、同一个排序器）。
   *
   * <pre>
   * 有机会：maxAbsorbable = 活动需求劳动；netPerLaborScaled = 活动收益率（同一把尺）；
   *         outputPriced = true（= "有可算的价差"）⇒ 进队列、可被 grant
   * 无机会：maxAbsorbable = 0、键 = 0、reason = ARBITRAGE_&lt;具名理由&gt;；outputPriced 仍为 true
   *         ⇒ **照样进队列**，作用只有一个：让写回阶段删掉昨天的套利配额行（否则它会永远占住劳动预算）
   * </pre>
   *
   * <p>★ {@code outputPriced} 在无机会时也置 true 是<b>刻意的</b>：该字段在既有机器的唯一用途是 {@code
   * LaborQueueBook.isPreservedByQueue}（"估不出价的活动原样保留"）。套利**不是**"估不出价"，而是"这一轮 没有价差" ——
   * 语义上属于"该被排空配额"的一侧，不能借"保留"名义把旧配额留成永久占位。
   */
  private static LaborQueueBook.Offer arbitrageOffer(
      ProductionUnitId arbitrageUnit, TradeArbitrageActivity.Evaluation evaluation) {
    if (!evaluation.hasOpportunity()) {
      return new LaborQueueBook.Offer(
          arbitrageUnit,
          Optional.empty(),
          arbitrageUnit.value(),
          0L,
          true,
          0L,
          0L,
          0L,
          0L,
          0L,
          "ARBITRAGE_" + evaluation.reason());
    }
    TradeArbitrageActivity.Opportunity opportunity =
        evaluation.activity().orElseThrow().opportunity();
    return new LaborQueueBook.Offer(
        arbitrageUnit,
        Optional.empty(),
        arbitrageUnit.value(), // ★ 并列键 = 活动键（"收益率 desc, activityId asc"里的 activityId）
        opportunity.laborMilli(),
        true,
        opportunity.netMilli(), // expectedOutputValueMilli：这笔套利创造的净价值（毫 numeraire）
        0L, // expectedInputCostMilli：套利的"投入"就是现金与货，它们的估值已含在净额里（不重复计）
        opportunity.netMilli(),
        opportunity.laborMilli(),
        opportunity.netPerLaborScaled(),
        "");
  }

  /**
   * ★ <b>每日清理"没有生产候选的家户"残留的套利配额行</b>。
   *
   * <p>★ <b>为什么需要它</b>：写回（{@code applyPlan}）只处理**进了队列**的家户；而"没有生产候选"的户整户被跳过 ⇒
   * 它昨天留下的套利配额行会<b>永远</b>占着 {@code Σ PRODUCTION ≤ 预算} 的可用量（静默占位）。本方法把那一条删掉，
   * 其余既有配额一个都不动（与改前的"不动它的任何既有配额"逐字一致）。
   */
  private static void removeStaleArbitrageCommitments(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      long day) {
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
          || !TradeArbitrageActivity.isArbitrageActivity(laborCommitment.activity())) {
        continue;
      }
      laborCommitments.remove(laborCommitment.id());
      if (EconomyLog.population().isDebugEnabled()) {
        EventLog.channel(EconomyLog.population())
            .debug(
                LogEvent.of(
                    "LABOR_QUEUE_ARBITRAGE_STALE_REMOVED",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "household",
                    laborCommitment.household().value(),
                    "activity",
                    laborCommitment.activity(),
                    "laborMilli",
                    laborCommitment.laborMilli(),
                    "reason",
                    "no-production-candidate"));
      }
    }
  }

  /** 某户有市场的具名读数（日志用；{@code false} = 无市场 ⇒ 套利具名 NO_MARKET）。 */
  private static boolean arbHasMarket(HouseholdResourceSnapshot snapshot) {
    return snapshot != null && snapshot.market() != null;
  }

  /** 逐户套利决定的 DEBUG 一条（谁、哪个方向、多少量、为什么排这个序 —— AGENTS §一.9 的"谁/方向/量/为什么"）。 */
  private static void logArbitrageDecision(
      long day,
      HouseholdId household,
      ProductionUnitId arbitrageUnit,
      TradeArbitrageActivity.Evaluation evaluation) {
    TradeArbitrageActivity.Opportunity opportunity =
        evaluation.activity().orElseThrow().opportunity();
    EventLog.channel(EconomyLog.settlement())
        .debug(
            LogEvent.of(
                "ARBITRAGE_OPPORTUNITY",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "day",
                day,
                "household",
                household.value(),
                "activity",
                arbitrageUnit.value(),
                "direction",
                opportunity.direction(),
                "commodity",
                opportunity.commodity().value(),
                "quantityMilli",
                opportunity.quantityMilli(),
                // ★ 2026-10-08 诊断缺陷修复：字段名 = 真实量纲（三个价格都是**微**；毫与微差 1000 倍，名字不许说谎）
                "reservationMicro",
                opportunity.reservationMicro(),
                "marketMicro",
                opportunity.marketMicro(),
                "edgeMicro",
                opportunity.edgeMicro(),
                "netMilli",
                opportunity.netMilli(),
                "laborMilli",
                opportunity.laborMilli(),
                "yieldScaled",
                opportunity.netPerLaborScaled(),
                "shortfallPerMille",
                opportunity.shortfallPerMille(),
                "saturationPerMille",
                opportunity.saturationPerMille()));
  }

  // ── 候选 / 批次 / 市场 ─────────────────────────────────────────────────────────────────

  /** 该 unit 归哪些家户（operator、组织的 organizer/laborSources、既有配额的 household）——稳定去重。 */
  private static Set<HouseholdId> candidateHouseholdsOf(
      ProductionProcess unit,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<String, Set<HouseholdId>> allocationHouseholdsByActivity,
      SettlementIndex index) {
    Set<HouseholdId> households = new LinkedHashSet<>();
    if (unit.operator().kind() == ActorKind.HOUSEHOLD) {
      HouseholdId operatorHousehold = index.householdByActor().get(unit.operator());
      if (operatorHousehold != null && householdEconomies.containsKey(operatorHousehold)) {
        households.add(operatorHousehold);
      }
    }
    ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
    if (enterprise != null) {
      HouseholdId organizerHousehold = index.householdByActor().get(enterprise.organizer());
      if (organizerHousehold != null && householdEconomies.containsKey(organizerHousehold)) {
        households.add(organizerHousehold);
      }
      for (HouseholdId laborSource : enterprise.laborSources()) {
        if (householdEconomies.containsKey(laborSource)) {
          households.add(laborSource);
        }
      }
    }
    for (HouseholdId household :
        allocationHouseholdsByActivity.getOrDefault(unit.id().value(), Set.of())) {
      if (householdEconomies.containsKey(household)) {
        households.add(household);
      }
    }
    return households;
  }

  /** 该 unit 的组织 mode；没有组织 ⇒ 空（旧 unit 的并列键退回 {@code modeKey}）。 */
  private static Optional<ProductionModeId> modeIdOf(
      ProductionProcess unit, Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess) {
    ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
    return enterprise == null ? Optional.empty() : Optional.of(enterprise.modeId());
  }

  /** 居住格的价表（没有 ⇒ 该 unit 所在格的价表；都没有 ⇒ null = 无价，排队读数按 0 估值并具名）。 */
  private static Market marketOf(
      HouseholdEconomy householdEconomy,
      List<ProductionProcess> candidates,
      Map<HexCoord, Market> markets) {
    Market market = markets.get(householdEconomy.view().hex());
    if (market != null) {
      return market;
    }
    for (ProductionProcess unit : candidates) {
      Optional<HexCoord> hex = hexOf(unit);
      if (hex.isPresent() && markets.get(hex.get()) != null) {
        return markets.get(hex.get());
      }
    }
    return null;
  }

  private static Optional<HexCoord> hexOf(ProductionProcess unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse);
  }

  /** 新配额挂哪个批次：家户人口组成里 count &gt; 0 的最小批次 id；没有 ⇒ 该户既有配额的批次；再没有 ⇒ 空 （调用方跳过本户排队，不猜）。 */
  private static PeopleLotId chooseLot(
      HouseholdId household,
      Map<HouseholdId, List<HouseholdLaborCommitment>> laborCommitmentsByHousehold,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition) {
    List<PeopleLotId> lots = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> member :
        composition.getOrDefault(household, Map.of()).entrySet()) {
      if (member.getKey() != null && member.getValue() != null && member.getValue() > 0L) {
        lots.add(member.getKey());
      }
    }
    lots.sort(Comparator.comparing(PeopleLotId::value));
    if (!lots.isEmpty()) {
      return lots.get(0);
    }
    List<PeopleLotId> existing = new ArrayList<>();
    for (HouseholdLaborCommitment laborCommitment :
        laborCommitmentsByHousehold.getOrDefault(household, List.of())) {
      if (laborCommitment.group() != null) {
        existing.add(laborCommitment.group());
      }
    }
    existing.sort(Comparator.comparing(PeopleLotId::value));
    return existing.isEmpty() ? null : existing.get(0);
  }

  // ── 写回 ────────────────────────────────────────────────────────────────────────────────

  /**
   * 把最终排队结果写回配额工作副本：只删/改本户**排队的 PRODUCTION 旧行**，再按决定写回（复用旧行的 id/group/actor/period/kind；没有旧行 ⇒ 用
   * unit.operator() / 选定批次新发一条 PRODUCTION）。保留活动的旧行一律不动。
   *
   * <p>★★ C7：GOV_SERVICE 行**绝不**被队列删除/改写；若某个决定指向带 GOV_SERVICE 承诺的 activity ⇒ 具名契约 ERROR +
   * fail-closed（防"队列覆盖政府承诺"这条纪律被绕开）。
   */
  private static void applyPlan(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      LaborQueueBook.Plan plan,
      PeopleLotId lot,
      Map<ProductionUnitId, ProductionProcess> candidateUnitsById,
      long day) {
    Set<ProductionUnitId> queuedUnits = new LinkedHashSet<>();
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      queuedUnits.add(decision.offer().unitId());
    }
    // ★ C7 防御：正常路径上带 GOV_SERVICE 的 activity 已被挡在候选外；这里再判一次，任何"排队到政府承诺上"的
    //   路径都 fail-closed（不静默覆盖/删除）。
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE
          && queuedUnits.contains(new ProductionUnitId(laborCommitment.activity()))) {
        throw laborCommitmentContractFault(
            plan.household(),
            day,
            "队列试图排队/覆盖 GOV_SERVICE 承诺: activity=" + laborCommitment.activity());
      }
    }
    Map<ProductionUnitId, HouseholdLaborCommitment> templates = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue; // ★ C7：GOV_SERVICE 行原样留在活表里，不参与"删旧行/复用模板"
      }
      ProductionUnitId unitId = new ProductionUnitId(laborCommitment.activity());
      if (queuedUnits.contains(unitId)) {
        templates.putIfAbsent(unitId, laborCommitment);
        laborCommitments.remove(laborCommitment.id());
      }
    }
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      if (decision.grantedLaborMilli() <= 0L) {
        continue;
      }
      ProductionUnitId unitId = decision.offer().unitId();
      HouseholdLaborCommitment template = templates.get(unitId);
      if (template != null) {
        laborCommitments.put(template.id(), withLaborMilli(template, decision.grantedLaborMilli()));
        continue;
      }
      ProductionProcess unit = candidateUnitsById.get(unitId);
      if (unit == null) {
        // ★★ 套利活动没有 ProductionProcess：收劳动的主体是**家户自己**（{@code HouseholdActors} 是
        //   家户身份 ↔ actor 身份的唯一拼写点）；配额 id 仍走契约层的唯一拼写点 {@code idOf(unit, ...)}。
        if (!TradeArbitrageActivity.isArbitrageActivity(unitId.value())) {
          throw new IllegalStateException("排队结果指向一个不在候选表里的 unit（内部不一致）：" + unitId.value());
        }
        LaborAllocationId arbitrageId =
            HouseholdLaborCommitment.idOf(unitId, lot, plan.household());
        HouseholdLaborCommitment freshLaborCommitment =
            new HouseholdLaborCommitment(
                arbitrageId,
                lot,
                plan.household(),
                HouseholdActors.of(plan.household()),
                unitId.value(),
                decision.grantedLaborMilli(),
                1L,
                LaborCommitmentKind.PRODUCTION); // ★ 套利属普通生产承诺：进排队、可缩（I13 的 Σ 里与生产同侧）
        HouseholdLaborCommitment previousLaborCommitment =
            laborCommitments.putIfAbsent(arbitrageId, freshLaborCommitment);
        if (previousLaborCommitment != null) {
          if (previousLaborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
            throw laborCommitmentContractFault(
                plan.household(),
                day,
                "队列新发 id 撞上既有 GOV_SERVICE 承诺（拒绝覆盖政府承诺）: id=" + arbitrageId.value());
          }
          laborCommitments.put(
              arbitrageId, withLaborMilli(previousLaborCommitment, decision.grantedLaborMilli()));
        }
        continue;
      }
      LaborAllocationId id = HouseholdLaborCommitment.idOf(unitId, lot, plan.household());
      HouseholdLaborCommitment freshLaborCommitment =
          new HouseholdLaborCommitment(
              id,
              lot,
              plan.household(),
              unit.operator(),
              unitId.value(),
              decision.grantedLaborMilli(),
              1L,
              LaborCommitmentKind.PRODUCTION); // ★ C7：队列只发 PRODUCTION，绝不创建 GOV_SERVICE
      HouseholdLaborCommitment previousLaborCommitment =
          laborCommitments.putIfAbsent(id, freshLaborCommitment);
      if (previousLaborCommitment != null) {
        if (previousLaborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          throw laborCommitmentContractFault(
              plan.household(), day, "队列新发 id 撞上既有 GOV_SERVICE 承诺（拒绝覆盖政府承诺）: id=" + id.value());
        }
        laborCommitments.put(
            id, withLaborMilli(previousLaborCommitment, decision.grantedLaborMilli()));
      }
    }
  }

  /**
   * 把**超出预算的 PRODUCTION 既有保留量**按既有量比例缩到剩余预算以内（只在坏状态兜底）：0 ⇒ 删行、其余写回缩小值。
   *
   * <p>★★ C7：GOV_SERVICE 一律整额保留、不参与权重、不写回——调用方已先判"Σ GOV_SERVICE ≤ budget"；本方法的 可用量 = {@code budget
   * - govServiceLaborMilli}。GOV_SERVICE 行在此**绝不**被删/缩。
   */
  private static void preserveIntoBudget(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      Set<ProductionUnitId> queuedUnits,
      long budget,
      long govServiceLaborMilli) {
    long availableForProduction = Math.max(0L, budget - govServiceLaborMilli);
    List<LaborAllocationId> ids = new ArrayList<>();
    List<Long> weights = new ArrayList<>();
    long productionPreserved = 0L;
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue; // ★ C7：GOV_SERVICE 整额保留，绝不进入比例缩
      }
      if (queuedUnits.contains(new ProductionUnitId(laborCommitment.activity()))) {
        continue;
      }
      ids.add(laborCommitment.id());
      long weight = Math.max(0L, laborCommitment.laborMilli());
      weights.add(weight);
      productionPreserved = Math.addExact(productionPreserved, weight);
    }
    if (ids.isEmpty()) {
      return; // 没有可缩的 PRODUCTION 保留量（防御性 no-op；超预算时理论上不可达）
    }
    long[] weightArray = new long[weights.size()];
    for (int i = 0; i < weights.size(); i++) {
      weightArray[i] = weights.get(i);
    }
    long[] parts =
        ProportionalSplit.byDenominator(availableForProduction, weightArray, productionPreserved);
    for (int i = 0; i < ids.size(); i++) {
      HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
      if (laborCommitment == null) {
        continue;
      }
      if (parts[i] <= 0L) {
        laborCommitments.remove(ids.get(i));
      } else {
        laborCommitments.put(ids.get(i), withLaborMilli(laborCommitment, parts[i]));
      }
    }
  }

  /** 换劳动量（其余字段——含 {@code kind}——原样带过）—— 与 {@code EconomySettlement.withLaborMilli} 同一形制。 */
  private static HouseholdLaborCommitment withLaborMilli(
      HouseholdLaborCommitment laborCommitment, long laborMilli) {
    return new HouseholdLaborCommitment(
        laborCommitment.id(),
        laborCommitment.group(),
        laborCommitment.household(),
        laborCommitment.actor(),
        laborCommitment.activity(),
        laborMilli,
        laborCommitment.period(),
        laborCommitment.kind());
  }

  /**
   * ★★ <b>C7 具名契约故障的唯一发射点</b>：先记 {@code LABOR_COMMITMENT_CONTRACT} ERROR（契约/一致性故障不降级）， 再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。{@code reason} 只含稳定 id / 数量，不含载荷明文。
   */
  private static IllegalStateException laborCommitmentContractFault(
      HouseholdId household, long day, String reason) {
    EventLog.channel(EconomyLog.population())
        .error(
            LogEvent.of(
                "LABOR_COMMITMENT_CONTRACT",
                EconomyLogSource.ECONOMY_POPULATION,
                "day",
                day,
                "household",
                household.value(),
                "reason",
                reason));
    return new IllegalStateException("家庭劳动承诺契约违约：" + reason);
  }
}
