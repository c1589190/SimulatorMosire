package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.ProportionalSplit;
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
 * <p>★★ <b>两阶段（为什么不是一个家户一个家户就地写）</b>：一条 unit 是**共享**的生产活动（主 unit 由多个家户按
 * {@code LaborAllocation} 出工；家户自营 unit 通常只有一个 operator 家户）。因此"这个 unit 本 tick 最大可吸收多少劳动"
 * 是<b>全局</b>上界，不能每家各算一次满额。本类分两阶段：
 *
 * <ol>
 *   <li><b>逐家户排队（只算不写）</b>：按 {@link LaborQueueBook#plan} 用家户自己的时间预算排队，得到"想要多少"；
 *   <li><b>逐 unit 全局封顶</b>：Σ 各家户对同一 unit 的想要量若超过该 unit 的最大可吸收量，按各户想要量比例
 *       （最大余数法）缩到上界 —— 公平、确定、且只减不增；
 *   <li><b>逐家户写回</b>：同 (家户, unit) 复用既有配额行的 id/批次/actor，只改 laborMilli（没有才新发）；
 *       最终拿不到量/预期 ≤ 0 的 unit 的旧行整条删除（劳动留在空缺，不塞给别的 unit）。
 * </ol>
 *
 * <p>★★ <b>哪些活动"被保留、不排队"</b>（既有配额原样保留并先占预算，见
 * {@link LaborQueueBook#isPreservedByQueue}）：{@code laborPerUnit ≤ 0} 的非劳动活动、没有产出配方的承运/贸易、
 * 产出一个价都没有的活动，以及 R4-E2b 当天刚进入的试产 unit。只有"确实定过价、且预期 ≤ 0"的活动会被判为空缺。
 *
 * <p>★ <b>为什么按家户全局串行而不是按 hex 并行</b>：时间预算是**家户**的资源，可能有多条 unit（多生产方式）跨组织；
 * 按 hex 分区会让"保留配额"与"新分配"在合并后才对账。家户数 × unit 数是可控量级，串行保证
 * {@code Σallocations(household) ≤ laborMilli} 在写回前就成立（{@code EconomyData} 的构造期守卫是第二道）。
 *
 * <p>★ <b>确定性</b>：家户 / unit / 配额全部按稳定 id 排序遍历；比例缩放在每个 unit 内按下标序（= 家户 id 升序）；
 * 无随机、无时钟、无 UUID；同输入同输出。
 */
final class LaborQueueSettlement {

  private LaborQueueSettlement() {}

  /**
   * 对 {@code session} 的 {@code allocations} 工作副本执行一次全量排队（每个世界日调用一次；旧档
   * {@code modes} 为空时不调用，旧路径逐值不变）。
   *
   * @param session 结算会话（读 base/工作副本；只写 {@code allocations}）
   * @param index 当天的派生索引（可用资产/产能/actor→家户只读）
   * @param day 当前世界日（报告/日志用）
   * @param composition 家户人口组成的只读投影（挑新配额挂哪个批次）
   * @param entryTrialUnits 今天刚由候选预设进入的试产 unit（R4-E2b）：它们的 {@code modeKey} 不是
   *     {@code mode:} 前缀 ⇒ **本日保留试产配额原样**；自动组织阶段今天新建的 unit（{@code mode:} 前缀）照常进队列
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
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations = session.sheet().allocations();
    LinkedHashMap<ProductionUnitId, ProductionUnit> units = session.sheet().units();
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
    Map<IndustryId, Industry> industryTemplates = session.sheet().industries();
    LinkedHashMap<HexCoord, Market> markets = session.sheet().markets();
    Map<ProductionUnitId, OperatorCondition> conditions = session.sheet().operatorConditions();
    LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations =
        session.sheet().productionOrganizations();

    // ── 组织 → unit（mode 读数的来源；旧 unit 没有组织 ⇒ Optional.empty）────────────────────
    Map<ProductionUnitId, ProductionOrganization> organizationByUnit = new LinkedHashMap<>();
    for (ProductionOrganization organization : organizations.values()) {
      if (organization.unitId().isEmpty()) {
        continue;
      }
      ProductionUnitId unitId = organization.unitId().get();
      if (units.containsKey(unitId)) {
        organizationByUnit.putIfAbsent(unitId, organization);
      }
    }

    // ── 既有配额按家户分组 + activity → 家户 索引（候选判定的 O(1) 来源）────────────────────
    Map<HouseholdId, List<LaborAllocation>> allocationsByHousehold = new LinkedHashMap<>();
    Map<String, Set<HouseholdId>> allocationHouseholdsByActivity = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      allocationsByHousehold
          .computeIfAbsent(allocation.household(), ignored -> new ArrayList<>())
          .add(allocation);
      allocationHouseholdsByActivity
          .computeIfAbsent(allocation.activity(), ignored -> new LinkedHashSet<>())
          .add(allocation.household());
    }

    // ── 候选 unit 按家户归集 ─────────────────────────────────────────────────────────────
    List<HouseholdId> orderedHouseholds = new ArrayList<>(rows.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, List<ProductionUnit>> candidatesByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      candidatesByHousehold.put(household, new ArrayList<>());
    }
    for (ProductionUnit unit : units.values()) {
      for (HouseholdId household :
          candidateHouseholdsOf(
              unit, rows, organizationByUnit, allocationHouseholdsByActivity, index)) {
        List<ProductionUnit> list = candidatesByHousehold.get(household);
        if (list != null && !list.contains(unit)) {
          list.add(unit);
        }
      }
    }
    for (List<ProductionUnit> list : candidatesByHousehold.values()) {
      list.sort(Comparator.comparing(candidate -> candidate.id().value()));
    }

    // ── 阶段 1：逐家户排队（只算不写）────────────────────────────────────────────────────
    List<HouseholdWork> works = new ArrayList<>();
    for (HouseholdId household : orderedHouseholds) {
      ClassRow row = rows.get(household);
      List<ProductionUnit> candidates = candidatesByHousehold.getOrDefault(household, List.of());
      if (candidates.isEmpty()) {
        continue; // 没有可参与的生产活动：不动它的任何既有配额（自由家户劳动/纯消费户）
      }
      PeopleLotId lot = chooseLot(household, allocationsByHousehold, composition);
      if (lot == null) {
        if (EconomyLog.population().isDebugEnabled()) {
          EconomyLog.population()
              .debug(
                  "event=LABOR_QUEUE_NO_LOT day={} household={} candidates={} reason=no-composition-lot",
                  day,
                  household.value(),
                  candidates.size());
        }
        continue; // 没有可挂批次 ⇒ 不猜、不重排（既有配额原样保留；预算不变量不动）
      }
      Map<ProductionUnitId, ProductionUnit> candidateUnitsById = new LinkedHashMap<>();
      for (ProductionUnit unit : candidates) {
        candidateUnitsById.put(unit.id(), unit);
      }
      Market market = marketOf(row, candidates, markets);
      List<LaborQueueBook.Offer> offers = new ArrayList<>();
      Set<ProductionUnitId> queuedUnits = new LinkedHashSet<>();
      for (ProductionUnit unit : candidates) {
        if (entryTrialUnits.contains(unit.id())
            && !unit.modeKey().startsWith(EconomyOrganizationSettlement.MODE_KEY_PREFIX)) {
          continue; // ★ R4-E2b 试产 unit：本日保留试产配额（不放进队列 ⇒ 被算进 preserved）
        }
        Industry industry = industryTemplates.get(unit.industry());
        if (industry == null) {
          continue; // 坏状态：不静默删配额（候选里没有 offer ⇒ 被算进 preserved）
        }
        Optional<ProductionModeId> modeId = modeIdOf(unit, organizationByUnit);
        String rankModeKey = modeId.map(ProductionModeId::value).orElse(unit.modeKey());
        LaborQueueBook.Offer offer =
            LaborQueueBook.offer(
                unit, industry, market, conditions.get(unit.id()), index, modeId, rankModeKey);
        if (!LaborQueueBook.isPreservedByQueue(offer)) {
          offers.add(offer);
          queuedUnits.add(unit.id());
        }
      }

      long budget = Math.max(0L, row.laborMilli());
      List<LaborAllocation> householdAllocations =
          allocationsByHousehold.getOrDefault(household, List.of());
      long preserved = 0L;
      for (LaborAllocation allocation : householdAllocations) {
        if (!queuedUnits.contains(new ProductionUnitId(allocation.activity()))) {
          preserved += allocation.laborMilli();
        }
      }
      if (preserved > budget) {
        // 合法状态到不了这里（构造期守卫），但排队写回不得以坏状态为借口超预算：按既有量比例缩到预算以内。
        preserveIntoBudget(allocations, householdAllocations, queuedUnits, budget, preserved);
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
              householdAllocations,
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
      for (LaborAllocation allocation : work.householdAllocations()) {
        ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
        if (!queuedForWork.contains(unitId) && capByUnit.containsKey(unitId)) {
          reservedByUnit.merge(unitId, allocation.laborMilli(), Long::sum);
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
      applyPlan(allocations, work.householdAllocations(), plan, work.lot(), work.candidateUnitsById());
      plans.add(plan);

      if (EconomyLog.organization().isDebugEnabled()) {
        EconomyLog.organization()
            .debug("event=LABOR_QUEUE_PLAN day={} {}", day, LaborQueueBook.describe(plan));
      }
      if (EconomyLog.trace().isTraceEnabled()) {
        for (LaborQueueBook.Decision decision : plan.decisions()) {
          EconomyLog.trace()
              .trace(
                  "event=LABOR_QUEUE_DECISION day={} household={} rank={} {}",
                  day,
                  work.household().value(),
                  decision.rank(),
                  LaborQueueBook.describe(decision));
        }
      }
    }

    LaborQueueReport report = new LaborQueueReport(day, plans);
    if (!plans.isEmpty()) {
      EconomyLog.settlement()
          .info(
              "event=LABOR_QUEUE day={} households={} budgetMilli={} allocatedMilli={} idleMilli={}",
              day,
              plans.size(),
              report.totalBudgetMilli(),
              report.totalAllocatedMilli(),
              report.totalIdleMilli());
      session
          .sheet()
          .meta()
          .ifPresent(meta -> LaborAllocationFeed.publish(meta.mapId(), report));
    }
    return report;
  }

  /** 阶段 1 的中间件：一个家户的预算/保留量/批次/候选 unit/既有配额快照/想要的排队结果。 */
  private record HouseholdWork(
      HouseholdId household,
      long budget,
      long preserved,
      PeopleLotId lot,
      Map<ProductionUnitId, ProductionUnit> candidateUnitsById,
      List<LaborAllocation> householdAllocations,
      LaborQueueBook.Plan desired) {

    HouseholdWork {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(lot, "lot");
      Objects.requireNonNull(candidateUnitsById, "candidateUnitsById");
      Objects.requireNonNull(householdAllocations, "householdAllocations");
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
      ProductionUnit unit,
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionOrganization> organizationByUnit,
      Map<String, Set<HouseholdId>> allocationHouseholdsByActivity,
      SettlementIndex index) {
    Set<HouseholdId> households = new LinkedHashSet<>();
    if (unit.operator().kind() == ActorKind.HOUSEHOLD) {
      HouseholdId operatorHousehold = index.householdByActor().get(unit.operator());
      if (operatorHousehold != null && rows.containsKey(operatorHousehold)) {
        households.add(operatorHousehold);
      }
    }
    ProductionOrganization organization = organizationByUnit.get(unit.id());
    if (organization != null) {
      HouseholdId organizerHousehold = index.householdByActor().get(organization.organizer());
      if (organizerHousehold != null && rows.containsKey(organizerHousehold)) {
        households.add(organizerHousehold);
      }
      for (HouseholdId laborSource : organization.laborSources()) {
        if (rows.containsKey(laborSource)) {
          households.add(laborSource);
        }
      }
    }
    for (HouseholdId household :
        allocationHouseholdsByActivity.getOrDefault(unit.id().value(), Set.of())) {
      if (rows.containsKey(household)) {
        households.add(household);
      }
    }
    return households;
  }

  /** 该 unit 的组织 mode；没有组织 ⇒ 空（旧 unit 的并列键退回 {@code modeKey}）。 */
  private static Optional<ProductionModeId> modeIdOf(
      ProductionUnit unit, Map<ProductionUnitId, ProductionOrganization> organizationByUnit) {
    ProductionOrganization organization = organizationByUnit.get(unit.id());
    return organization == null ? Optional.empty() : Optional.of(organization.modeId());
  }

  /** 居住格的价表（没有 ⇒ 该 unit 所在格的价表；都没有 ⇒ null = 无价，排队读数按 0 估值并具名）。 */
  private static Market marketOf(
      ClassRow row, List<ProductionUnit> candidates, Map<HexCoord, Market> markets) {
    Market market = markets.get(row.view().hex());
    if (market != null) {
      return market;
    }
    for (ProductionUnit unit : candidates) {
      Optional<HexCoord> hex = hexOf(unit);
      if (hex.isPresent() && markets.get(hex.get()) != null) {
        return markets.get(hex.get());
      }
    }
    return null;
  }

  private static Optional<HexCoord> hexOf(ProductionUnit unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse);
  }

  /**
   * 新配额挂哪个批次：家户人口组成里 count &gt; 0 的最小批次 id；没有 ⇒ 该户既有配额的批次；再没有 ⇒ 空
   * （调用方跳过本户排队，不猜）。
   */
  private static PeopleLotId chooseLot(
      HouseholdId household,
      Map<HouseholdId, List<LaborAllocation>> allocationsByHousehold,
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
    for (LaborAllocation allocation : allocationsByHousehold.getOrDefault(household, List.of())) {
      if (allocation.group() != null) {
        existing.add(allocation.group());
      }
    }
    existing.sort(Comparator.comparing(PeopleLotId::value));
    return existing.isEmpty() ? null : existing.get(0);
  }

  // ── 写回 ────────────────────────────────────────────────────────────────────────────────

  /**
   * 把最终排队结果写回配额工作副本：先删本户所有排队的旧行，再按决定写回（复用旧行的 id/group/actor/period；
   * 没有旧行 ⇒ 用 unit.operator() / 选定批次新发一条）。保留活动的旧行一律不动。
   */
  private static void applyPlan(
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      List<LaborAllocation> householdAllocations,
      LaborQueueBook.Plan plan,
      PeopleLotId lot,
      Map<ProductionUnitId, ProductionUnit> candidateUnitsById) {
    Set<ProductionUnitId> queuedUnits = new LinkedHashSet<>();
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      queuedUnits.add(decision.offer().unitId());
    }
    Map<ProductionUnitId, LaborAllocation> templates = new LinkedHashMap<>();
    for (LaborAllocation allocation : householdAllocations) {
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      if (queuedUnits.contains(unitId)) {
        templates.putIfAbsent(unitId, allocation);
        allocations.remove(allocation.id());
      }
    }
    for (LaborQueueBook.Decision decision : plan.decisions()) {
      if (decision.grantedLaborMilli() <= 0L) {
        continue;
      }
      ProductionUnitId unitId = decision.offer().unitId();
      LaborAllocation template = templates.get(unitId);
      if (template != null) {
        allocations.put(template.id(), withLaborMilli(template, decision.grantedLaborMilli()));
        continue;
      }
      ProductionUnit unit = candidateUnitsById.get(unitId);
      if (unit == null) {
        throw new IllegalStateException(
            "排队结果指向一个不在候选表里的 unit（内部不一致）：" + unitId.value());
      }
      LaborAllocationId id = LaborAllocation.idOf(unitId, lot, plan.household());
      LaborAllocation fresh =
          new LaborAllocation(
              id,
              lot,
              plan.household(),
              unit.operator(),
              unitId.value(),
              decision.grantedLaborMilli(),
              1L);
      LaborAllocation previous = allocations.putIfAbsent(id, fresh);
      if (previous != null) {
        allocations.put(id, withLaborMilli(previous, decision.grantedLaborMilli()));
      }
    }
  }

  /** 按既有量比例把保留配额缩到预算以内（只在坏状态兜底）：0 ⇒ 删行、其余写回缩小值。 */
  private static void preserveIntoBudget(
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      List<LaborAllocation> householdAllocations,
      Set<ProductionUnitId> queuedUnits,
      long budget,
      long preserved) {
    List<LaborAllocationId> ids = new ArrayList<>();
    List<Long> weights = new ArrayList<>();
    for (LaborAllocation allocation : householdAllocations) {
      if (queuedUnits.contains(new ProductionUnitId(allocation.activity()))) {
        continue;
      }
      ids.add(allocation.id());
      weights.add(Math.max(0L, allocation.laborMilli()));
    }
    long[] weightArray = new long[weights.size()];
    for (int i = 0; i < weights.size(); i++) {
      weightArray[i] = weights.get(i);
    }
    long[] parts = ProportionalSplit.byDenominator(budget, weightArray, preserved);
    for (int i = 0; i < ids.size(); i++) {
      LaborAllocation allocation = allocations.get(ids.get(i));
      if (allocation == null) {
        continue;
      }
      if (parts[i] <= 0L) {
        allocations.remove(ids.get(i));
      } else {
        allocations.put(ids.get(i), withLaborMilli(allocation, parts[i]));
      }
    }
  }

  /** 换劳动量（其余字段原样带过）—— 与 {@code EconomySettlement.withLaborMilli} 同一形制。 */
  private static LaborAllocation withLaborMilli(LaborAllocation allocation, long laborMilli) {
    return new LaborAllocation(
        allocation.id(),
        allocation.group(),
        allocation.household(),
        allocation.actor(),
        allocation.activity(),
        laborMilli,
        allocation.period());
  }
}
