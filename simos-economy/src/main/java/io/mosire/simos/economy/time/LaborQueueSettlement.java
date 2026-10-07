package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
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
   * 对 {@code session} 的 {@code allocations} 工作副本执行一次全量排队（每个世界日调用一次；旧档 {@code modes}
   * 为空时不调用，旧路径逐值不变）。
   *
   * @param session 结算会话（读 base/工作副本；只写 {@code allocations}）
   * @param index 当天的派生索引（可用资产/产能/actor→家户只读）
   * @param day 当前世界日（报告/日志用）
   * @param composition 家户人口组成的只读投影（挑新配额挂哪个批次）
   * @param entryTrialUnits 今天刚由候选预设进入的试产 unit（R4-E2b）：它们的 {@code modeKey} 不是 {@code mode:} 前缀 ⇒
   *     **本日保留试产配额原样**；自动组织阶段今天新建的 unit（{@code mode:} 前缀）照常进队列
   * @return 本日的可读排队报告
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(composition, "composition");
    Objects.requireNonNull(entryTrialUnits, "entryTrialUnits");
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

    // ── 阶段 1：逐家户排队（只算不写）────────────────────────────────────────────────────
    List<HouseholdWork> works = new ArrayList<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      List<ProductionProcess> candidates = candidatesByHousehold.getOrDefault(household, List.of());
      if (candidates.isEmpty()) {
        continue; // 没有可参与的生产活动：不动它的任何既有配额（自由家户劳动/纯消费户）
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
      List<HouseholdLaborCommitment> householdLaborCommitments =
          laborCommitmentsByHousehold.getOrDefault(household, List.of());
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
        throw new IllegalStateException("排队结果指向一个不在候选表里的 unit（内部不一致）：" + unitId.value());
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
