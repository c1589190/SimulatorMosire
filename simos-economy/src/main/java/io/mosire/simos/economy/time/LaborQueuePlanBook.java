package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ArbitrageLaborBook.arbHasMarket;
import static io.mosire.simos.economy.time.ArbitrageLaborBook.arbitrageOffer;
import static io.mosire.simos.economy.time.ArbitrageLaborBook.buildArbitrageSnapshots;
import static io.mosire.simos.economy.time.ArbitrageLaborBook.logArbitrageDecision;
import static io.mosire.simos.economy.time.ArbitrageLaborBook.removeStaleArbitrageCommitments;
import static io.mosire.simos.economy.time.LaborCandidateBook.candidateSlotsOf;
import static io.mosire.simos.economy.time.LaborCandidateBook.chooseLot;
import static io.mosire.simos.economy.time.LaborCandidateBook.marketOf;
import static io.mosire.simos.economy.time.LaborCandidateBook.modeIdOf;
import static io.mosire.simos.economy.time.LaborCandidateBook.moneyWageCandidateSkipReason;
import static io.mosire.simos.economy.time.LaborQueueWritebackBook.applyPlan;
import static io.mosire.simos.economy.time.LaborQueueWritebackBook.laborCommitmentContractFault;
import static io.mosire.simos.economy.time.LaborQueueWritebackBook.preserveIntoBudget;
import static io.mosire.simos.economy.time.LaborQueueWritebackBook.withLaborMilli;
import static io.mosire.simos.economy.time.LaborSurvivalBook.survivalOutlookFor;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.time.LaborCandidateBook.LaborSlot;
import io.mosire.simos.economy.time.LaborQueueWritebackBook.UnitRole;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
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

final class LaborQueuePlanBook {
  private LaborQueuePlanBook() {}

  static LaborQueueReport applyInternal(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector,
      Map<HouseholdId, SurvivalOutlook> survivalOutlooks,
      SurvivalSocialFacts survivalSocialFacts) {
    return applyInternal(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        accounts,
        arbitrageCollector,
        survivalOutlooks,
        survivalSocialFacts,
        null,
        List.of());
  }

  /**
   * ★★★ <b>D-032 P3c3：M6 实际编排入口</b>。{@code allowedSlots == null} ⇒ 旧行为；非 null 时只排 M3/M6 实际计划内的
   * (unit, role) 槽，且 {@code locks} 对应的槽不进队列（保留既有承诺，锁定雇佣不可变）。
   */
  static LaborQueueReport applyInternal(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector,
      Map<HouseholdId, SurvivalOutlook> survivalOutlooks,
      SurvivalSocialFacts survivalSocialFacts,
      Set<LaborQueueWritebackBook.UnitRole> allowedSlots,
      List<EmploymentLock> locks) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(survivalOutlooks, "survivalOutlooks");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(composition, "composition");
    Objects.requireNonNull(entryTrialUnits, "entryTrialUnits");
    Objects.requireNonNull(arbitrageCollector, "arbitrageCollector");
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    // ★★★ DEF-7：同一 (household, unit, role) 的锁定雇佣可能对应多条旧 PRODUCTION 承诺（group/period
    //   不同）。锁定槽不进队列 ⇒ 队列不会删重复，而 preserveIntoBudget 会把 protected 行整额保留两次 ⇒
    //   Σ PRODUCTION 超预算。必须在构建按家户索引之前把锁定槽收敛成唯一模板。
    reconcileLockedCommitments(laborCommitments, locks, day);
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    Map<ProductionUnitId, ProductionRoster> productionRosters =
        session.sheet().productionRostersOrBase();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    Map<IndustryId, IndustryDefinitionView> industryTemplates = session.sheet().industries();
    // ★★★ DEF-8 + RC8b：先删除"角色已不在名册且也不是当前同 hex MONEY_WAGE 响应者"的陈旧 PRODUCTION 承诺。
    //   名册仍是 (household, role) 的历史参与权威，但 RC8b 起同 hex 工资响应者的当前劳动也可形成本 tick 的有效参与项。
    LaborCommitmentReconcileBook.pruneStaleRoleCommitments(
        laborCommitments, units, productionRosters, industryTemplates, householdEconomies, day);
    // ★★★ D-032 C9 hotfix：运输 unit 不进 actualProductionPlan（服务产出无市场价 ⇒ positionNet=0），
    //   若只靠创世种子一次，既有承诺一旦缺失/被替换就再也不会重发。这里按 roster/plannedLabor 每 tick
    //   补发/确保运输 operator 的 PRODUCTION 承诺；预算按同一 household 的当刻剩余劳动封顶，不造第二份劳动。
    reissueCaravanRosterCommitments(
        laborCommitments,
        units,
        productionRosters,
        industryTemplates,
        householdEconomies,
        composition,
        day);
    // ★ W1：结算器内部持**逐格取价表解析器**（价表每区一张 ⇒ 不能再按 hex 直接查表）。
    MarketIndex markets =
        MarketIndex.of(session.sheet().marketTables(), session.sheet().base().marketZones());
    Map<ProductionUnitId, OperatorCondition> conditions = session.sheet().operatorConditions();
    // ★★★ RC8b：逐 unit 的当前招工需求缓存（同一 tick 只从 farm 实际劳动需求派生一次；不是 roster 历史快照）。
    Map<ProductionUnitId, Map<ProductionRoleId, Long>> hiredDemandByUnit = new LinkedHashMap<>();
    LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises =
        session.sheet().productionOrganizations();
    // ★ P4c3：本 tick 的只读日视图（锚唯一报价权威；M6 不依赖 M8 之后的任何新价）。
    io.mosire.simos.economy.EconomyDayView wageDayView = new WorkingDayView(session.sheet());
    SurvivalIdlePolicy survivalIdlePolicy =
        SurvivalIdlePolicy.fromPolicyValue(
            session
                .sheet()
                .base()
                .economyPolicyOf(
                    io.mosire.simos.economy.model.EconomyPolicyKey.SURVIVAL_IDLE_POLICY));

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

    // ── 候选 (unit × 角色) 槽按家户归集（D-032 D2 §4.3：PRODUCT_SHARE 读名册、MONEY_WAGE 读报价读口）──────
    List<HouseholdId> orderedHouseholds = new ArrayList<>(householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, List<LaborSlot>> candidatesByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      candidatesByHousehold.put(household, new ArrayList<>());
    }
    // ★★ D-032 W5-E：名册 plannedLabor 是历史计划快照（人口/户结构变化后可能 &gt; 当刻预算）。
    //   运行时不硬拒（见 EconomyReferenceValidation 的运行时/写入口分流），但要在分配处留下具名读数：
    //   下面逐户 begin 时若 planned &gt; budget，记一条具名读数：
    //   HOUSEHOLD_LABOR_CAPPED（reason=household-labor-capped）；
    //   实际授予量仍由 LaborQueueBook.plan 用 budget 封顶，绝不让实际 labor 超预算。
    Map<HouseholdId, Long> plannedLaborByHousehold = new LinkedHashMap<>();
    for (ProductionRoster roster : productionRosters.values()) {
      for (Map.Entry<HouseholdId, Long> planned : roster.plannedLaborByHousehold().entrySet()) {
        plannedLaborByHousehold.merge(planned.getKey(), planned.getValue(), Math::addExact);
      }
    }
    Set<LaborQueueWritebackBook.UnitRole> lockedSlots = new LinkedHashSet<>();
    if (locks != null) {
      for (EmploymentLock lock : locks) {
        lockedSlots.add(new LaborQueueWritebackBook.UnitRole(lock.unit(), lock.role()));
      }
    }
    for (ProductionProcess unit : units.values()) {
      for (LaborSlot slot :
          candidateSlotsOf(
              unit,
              householdEconomies,
              enterpriseByProcess,
              allocationHouseholdsByActivity,
              index,
              session.sheet().definitions(),
              productionRosters)) {
        if (allowedSlots != null
            && !allowedSlots.contains(
                new LaborQueueWritebackBook.UnitRole(slot.unit().id(), slot.role()))) {
          continue;
        }
        List<LaborSlot> list = candidatesByHousehold.get(slot.household());
        if (list != null && !list.contains(slot)) {
          list.add(slot);
        }
      }
    }
    for (List<LaborSlot> list : candidatesByHousehold.values()) {
      list.sort(
          Comparator.comparing((LaborSlot slot) -> slot.unit().id().value())
              .thenComparing(slot -> slot.role().value()));
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
      List<LaborSlot> candidates = candidatesByHousehold.getOrDefault(household, List.of());
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
      // ★★ D-032 W5-E：历史名册计划 &gt; 当刻预算 ⇒ **不硬拒、按预算封顶**，并留具名读数。
      //   ★ 不改 roster 的 plannedLabor 值（那是历史计划快照）；实际授予量由 LaborQueueBook.plan(household, budget, …)
      //     封顶，EconomyData 构造期再守 Σ PRODUCTION ≤ budget，故实际 labor 不会超预算。
      long plannedLaborMilli = plannedLaborByHousehold.getOrDefault(household, 0L);
      if (plannedLaborMilli > budget && EconomyLog.population().isInfoEnabled()) {
        EventLog.channel(EconomyLog.population())
            .info(
                LogEvent.of(
                    "HOUSEHOLD_LABOR_CAPPED",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "plannedLaborMilli",
                    plannedLaborMilli,
                    "budgetMilli",
                    budget,
                    "reason",
                    "household-labor-capped"));
      }
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
      List<ProductionProcess> candidateUnits = new ArrayList<>();
      for (LaborSlot slot : candidates) {
        candidateUnitsById.put(slot.unit().id(), slot.unit());
        if (!candidateUnits.contains(slot.unit())) {
          candidateUnits.add(slot.unit());
        }
      }
      MarketTable market = marketOf(householdEconomy, candidateUnits, markets);
      List<LaborQueueBook.Offer> offers = new ArrayList<>();
      Set<UnitRole> queuedSlots = new LinkedHashSet<>();
      for (LaborSlot slot : candidates) {
        ProductionProcess unit = slot.unit();
        if (lockedSlots.contains(new LaborQueueWritebackBook.UnitRole(unit.id(), slot.role()))) {
          continue; // ★ P3c3：M3 锁定的雇佣关系不进队列（保留既有承诺，锁定雇佣不可变）
        }
        if (govServiceActivities.contains(unit.id().value())) {
          continue; // ★ C7：该户在此 unit 上有 GOV_SERVICE 承诺 ⇒ 不进队列（队列不得创建/覆盖政府承诺）
        }
        if (entryTrialUnits.contains(unit.id())
            && !unit.modeKey().startsWith(EconomyEnterpriseSettlement.MODE_KEY_PREFIX)) {
          continue; // ★ R4-E2b 试产 unit：本日保留试产配额（不放进队列 ⇒ 被算进 preserved）
        }
        IndustryDefinitionView industry = industryTemplates.get(unit.industry());
        if (industry == null) {
          continue; // 坏状态：不静默删配额（候选里没有 offer ⇒ 被算进 preserved）
        }
        Optional<ProductionModeId> modeId = modeIdOf(unit, enterpriseByProcess);
        String rankModeKey = modeId.map(ProductionModeId::value).orElse(unit.modeKey());
        if (slot.moneyWage()) {
          // ★★★ D-032 P4c3 + RC8b：工资报价唯一读 U20 锚（同一 tick 快照）；锚缺 ⇒ 具名拒绝。
          //   ★ 招工目标来自本 unit 当前实际劳动需求 − organizer 当前自耕劳动，不读 roster 历史计划/标准角色固定值。
          WageQuoteBridge.WageQuoteResolution resolution =
              WageQuoteBridge.resolveWageQuote(
                  wageDayView,
                  day,
                  "m6-labor-queue",
                  unit,
                  slot.role(),
                  session.sheet().productionWageAnchorsOrBase(),
                  false);
          Optional<io.mosire.simos.economy.model.ProductionWageQuote> quote = resolution.quote();
          if (quote.isEmpty()) {
            offers.add(
                new LaborQueueBook.Offer(
                    unit.id(),
                    slot.role(),
                    modeId,
                    rankModeKey,
                    0L,
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "wage-quote-missing:" + slot.role().value()));
            continue;
          }
          // ★★★ RC8b：招工目标 = 本 unit 当前实际劳动需求 − organizer 当前自耕劳动；
          //   不再用 standardLaborMilliPerCycle / cycleDays 的标准角色固定值。
          ProductionRoster queueRoster = productionRosters.get(unit.id());
          Map<ProductionRoleId, Long> hiredDemandByRole =
              queueRoster == null
                  ? Map.of()
                  : hiredDemandByUnit.computeIfAbsent(
                      unit.id(),
                      ignored ->
                          ProductionPlanBridge.hiredLaborDemandByRole(
                              session,
                              index,
                              unit,
                              industry,
                              queueRoster,
                              householdEconomies,
                              accounts == null ? null : accounts.householdGoods(),
                              conditions,
                              market));
          long targetLaborMilliPerDay = hiredDemandByRole.getOrDefault(slot.role(), 0L);
          if (targetLaborMilliPerDay <= 0L) {
            offers.add(
                new LaborQueueBook.Offer(
                    unit.id(),
                    slot.role(),
                    modeId,
                    rankModeKey,
                    0L,
                    true,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "wage-target-missing:" + slot.role().value()));
            continue;
          }
          String skip =
              moneyWageCandidateSkipReason(
                  session,
                  index,
                  accounts,
                  unit,
                  slot.household(),
                  enterpriseByProcess,
                  productionRosters);
          if (skip != null) {
            offers.add(
                new LaborQueueBook.Offer(
                    unit.id(),
                    slot.role(),
                    modeId,
                    rankModeKey,
                    0L,
                    true,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    skip));
            continue;
          }
          long wagePerThousandLaborMilli = quote.get().wagePerLaborMilli();
          long yieldScaled;
          try {
            yieldScaled = Math.multiplyExact(wagePerThousandLaborMilli, 1000L);
          } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                "wage-quote-overflow: " + unit.id().value() + "|" + slot.role().value(), overflow);
          }
          offers.add(
              new LaborQueueBook.Offer(
                  unit.id(),
                  slot.role(),
                  modeId,
                  rankModeKey,
                  targetLaborMilliPerDay,
                  true,
                  wagePerThousandLaborMilli,
                  0L,
                  wagePerThousandLaborMilli,
                  1000L,
                  yieldScaled,
                  ""));
          continue;
        }
        LaborQueueBook.Offer offer =
            LaborQueueBook.offer(
                unit,
                industry,
                market,
                conditions.get(unit.id()),
                index,
                modeId,
                rankModeKey,
                // ★★★ D-032 §15.12 A（R1）：家户决定这批物理量的熟练度/经验（probe 入参），
                //   产出/投入/劳动由同一次试算给出（本户在循环外已解析）。
                household,
                // ★★★ D-032 C2 §5.6：候选按名册角色分流；同 unit 多角色分别排队、分别落配额 id。
                slot.role(),
                // ★★★ RF2：M6 早于 M7 ⇒ 投入通道读 **M5 后的家户在手库存**（粮先扣一 tick 口粮），
                //   不得把"本 tick 尚未现扣"的 drawn=0 当成 NO_INPUT。
                //   ★ RF6a：5 参兼容入口允许 accounts == null（无账视图）⇒ 必须原样传 null，
                //   由 LaborQueueBook.offer 记为 awaiting-draw；不得用空 Map 冒充"有账但零库存"（会成 NO_INPUT）。
                session,
                accounts == null ? null : accounts.householdGoods());
        if (!LaborQueueBook.isPreservedByQueue(offer)) {
          offers.add(offer);
          queuedSlots.add(new UnitRole(unit.id(), slot.role()));
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
      if (allowedSlots == null) {
        // P3c3 的 M6 实际编排不产生市场订单 ⇒ 不把套利活动放进队列（旧路径逐值不变）。
        offers.add(arbitrageOffer(arbitrageUnit, arbitrage));
      }
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
        UnitRole commitmentSlot =
            new UnitRole(
                new ProductionUnitId(laborCommitment.activity()),
                laborCommitment
                    .productionRole()
                    .orElseThrow(
                        () ->
                            laborCommitmentContractFault(
                                household,
                                day,
                                "PRODUCTION 承诺缺 productionRole（D2 §5.6 不变量被绕开）: id="
                                    + laborCommitment.id().value())));
        if (!queuedSlots.contains(commitmentSlot)) {
          preserved = Math.addExact(preserved, laborCommitment.laborMilli());
        }
      }
      if (preserved > budget) {
        // 合法状态到不了这里（构造期守卫；GOV_SERVICE 越界已在上面 fail-closed），但排队写回不得以坏状态为借口
        // 超预算：先整额保 GOV_SERVICE，只对 PRODUCTION 既有保留量按比例缩到剩余预算以内。
        preserveIntoBudget(
            laborCommitments,
            householdLaborCommitments,
            queuedSlots,
            budget,
            govServiceLaborMilli,
            lockedSlots);
        preserved = budget;
      }
      SurvivalOutlook outlook =
          survivalSocialFacts == null
              ? survivalOutlooks.get(household)
              : survivalOutlookFor(
                  household,
                  householdEconomy,
                  day,
                  offers,
                  session,
                  accounts,
                  market,
                  units,
                  industryTemplates,
                  productionRosters,
                  survivalSocialFacts);
      IdleOption idleOption =
          outlook == null ? IdleOption.notEligible() : IdleOption.of(outlook, survivalIdlePolicy);
      LaborQueueBook.Plan desired =
          LaborQueueBook.plan(household, budget, preserved, offers, idleOption);
      if (outlook != null && EconomyLog.population().isDebugEnabled()) {
        EventLog.channel(EconomyLog.population())
            .debug(
                LogEvent.of(
                    "SURVIVAL_OUTLOOK",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "nextTick",
                    outlook.nextTickNeed().forTick(),
                    "bottleneckCoveragePerMille",
                    outlook.bestAchievableCoverage().bottleneckCoveragePerMille(),
                    "idleEligible",
                    outlook.idleEligible(),
                    "idleChosen",
                    desired.idleChosen(),
                    "reason",
                    outlook.reason().stableReason()));
      }
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
    Map<String, Long> finalGrantBySlot = new LinkedHashMap<>();
    Map<ProductionUnitId, List<Wanted>> desiredByUnit = new LinkedHashMap<>();
    Map<ProductionUnitId, Long> capByUnit = new LinkedHashMap<>();
    for (HouseholdWork work : works) {
      for (LaborQueueBook.Decision decision : work.desired().decisions()) {
        if (decision.grantedLaborMilli() <= 0L) {
          continue;
        }
        ProductionUnitId unitId = decision.offer().unitId();
        // ★★★ RC8b：同一 unit 的不同角色 offer 携带的 maxAbsorbable 是不同切面（organizer 的产能需求 /
        //   money-wage 的剩余需求）。全局封顶必须取**最大**那个（= farm 总劳动需求），不能取"最后一条"
        //   （否则会把 organizer 的自耕劳动再从 cap 里扣一遍，导致 hired 永远拿不到剩余劳动）。
        capByUnit.merge(unitId, decision.offer().maxAbsorbableLaborMilli(), Math::max);
        desiredByUnit
            .computeIfAbsent(unitId, ignored -> new ArrayList<>())
            .add(
                new Wanted(
                    work.household(), decision.offer().role(), decision.grantedLaborMilli()));
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
    for (Map.Entry<ProductionUnitId, List<Wanted>> entry : desiredByUnit.entrySet()) {
      ProductionUnitId unitId = entry.getKey();
      List<Wanted> desired = entry.getValue();
      long cap =
          Math.max(
              0L, capByUnit.getOrDefault(unitId, 0L) - reservedByUnit.getOrDefault(unitId, 0L));
      long total = 0L;
      for (Wanted wanted : desired) {
        total += wanted.laborMilli();
      }
      if (total <= cap) {
        for (Wanted wanted : desired) {
          finalGrantBySlot.put(key(unitId, wanted.household(), wanted.role()), wanted.laborMilli());
        }
        continue;
      }
      long[] weights = new long[desired.size()];
      for (int i = 0; i < desired.size(); i++) {
        weights[i] = desired.get(i).laborMilli();
      }
      long[] parts = ProportionalSplit.byDenominator(cap, weights, total);
      for (int i = 0; i < desired.size(); i++) {
        finalGrantBySlot.put(
            key(unitId, desired.get(i).household(), desired.get(i).role()), parts[i]);
      }
    }

    // ── 阶段 3：逐家户写回 + 报告/日志 ───────────────────────────────────────────────────
    List<LaborQueueBook.Plan> plans = new ArrayList<>();
    for (HouseholdWork work : works) {
      List<LaborQueueBook.Decision> decisions = new ArrayList<>();
      long allocated = work.preserved();
      for (LaborQueueBook.Decision decision : work.desired().decisions()) {
        long finalGrant =
            finalGrantBySlot.getOrDefault(
                key(decision.offer().unitId(), work.household(), decision.offer().role()), 0L);
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
              work.desired().idleChosen(),
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

  /**
   * ★★★ <b>DEF-7：锁定槽旧承诺去重</b>。
   *
   * <p>M3 的 {@link EmploymentLock} 只是"本 tick 锁定雇佣关系"的权威快照；既有 {@code laborCommitments} 里可能因 历史
   * group/period 差异留下同一 {@code (household, unit, role)} 的多条 PRODUCTION 行。锁定槽不进队列 ⇒ {@code
   * applyPlan} 不会删它们，而 {@code preserveIntoBudget} 会把每条 protected 行都整额保留 ⇒ Σ PRODUCTION
   * 超预算。本方法在按家户建索引之前把每个锁定槽收敛成唯一模板：
   *
   * <ul>
   *   <li>同一锁定槽有多条旧行 ⇒ 按 {@link LaborAllocationId#value()} 升序保留最小 id，其余删除；
   *   <li>保留行的 {@code laborMilli} 改写为锁的 {@code laborMilliPerDay}（锁是 M3 权威快照）；
   *   <li>只有一条旧行 ⇒ 一律不改值（避免无谓行为变化）；
   *   <li>同一锁定槽的多条锁 laborMilliPerDay 不一致、或清理后仍有多行 ⇒ 具名 ERROR + fail-closed。
   * </ul>
   *
   * @return 删除的旧 PRODUCTION 行数
   */
  static int reconcileLockedCommitments(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<EmploymentLock> locks,
      long day) {
    if (locks == null || locks.isEmpty()) {
      return 0;
    }
    Objects.requireNonNull(laborCommitments, "laborCommitments");
    Map<LockedSlot, EmploymentLock> lockBySlot = new LinkedHashMap<>();
    for (EmploymentLock lock : locks) {
      LockedSlot slot = LockedSlot.of(lock);
      EmploymentLock previous = lockBySlot.putIfAbsent(slot, lock);
      if (previous != null && previous.laborMilliPerDay() != lock.laborMilliPerDay()) {
        throw laborCommitmentContractFault(
            slot.household(),
            day,
            "locked-slot-multiple-locks: unit="
                + slot.unit().value()
                + " role="
                + slot.role().value()
                + " firstLaborMilli="
                + previous.laborMilliPerDay()
                + " secondLaborMilli="
                + lock.laborMilliPerDay());
      }
    }

    Map<LockedSlot, List<HouseholdLaborCommitment>> rowsBySlot = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
          || laborCommitment.productionRole().isEmpty()) {
        continue; // GOV_SERVICE 与无角色行绝不进锁定槽去重
      }
      LockedSlot slot =
          new LockedSlot(
              laborCommitment.household(),
              new ProductionUnitId(laborCommitment.activity()),
              laborCommitment.productionRole().get());
      if (lockBySlot.containsKey(slot)) {
        rowsBySlot.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(laborCommitment);
      }
    }

    int removedTotal = 0;
    for (Map.Entry<LockedSlot, List<HouseholdLaborCommitment>> entry : rowsBySlot.entrySet()) {
      List<HouseholdLaborCommitment> rows = entry.getValue();
      if (rows.size() <= 1) {
        continue; // 单行不改值（DEF-7 规则 5）
      }
      rows.sort(Comparator.comparing(row -> row.id().value()));
      HouseholdLaborCommitment kept = rows.get(0);
      List<LaborAllocationId> removedIds = new ArrayList<>();
      for (int i = 1; i < rows.size(); i++) {
        LaborAllocationId removedId = rows.get(i).id();
        laborCommitments.remove(removedId);
        removedIds.add(removedId);
      }
      long lockedLaborMilli = lockBySlot.get(entry.getKey()).laborMilliPerDay();
      laborCommitments.put(kept.id(), withLaborMilli(kept, lockedLaborMilli));
      removedTotal += removedIds.size();
      if (EconomyLog.population().isInfoEnabled()) {
        EventLog.channel(EconomyLog.population())
            .info(
                LogEvent.of(
                    "LABOR_LOCKED_COMMITMENT_DEDUPED",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    entry.getKey().household().value(),
                    "unit",
                    entry.getKey().unit().value(),
                    "role",
                    entry.getKey().role().value(),
                    "kept",
                    kept.id().value(),
                    "removed",
                    removedIds.size(),
                    "removedIds",
                    removedIds.toString(),
                    "laborMilli",
                    lockedLaborMilli));
      }
    }

    // ★ 契约后置条件：清理后同一锁定槽仍多于 1 行 ⇒ 契约故障，不静默继续（DEF-7 规则 6）。
    Map<LockedSlot, Integer> remainingBySlot = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
          || laborCommitment.productionRole().isEmpty()) {
        continue;
      }
      LockedSlot slot =
          new LockedSlot(
              laborCommitment.household(),
              new ProductionUnitId(laborCommitment.activity()),
              laborCommitment.productionRole().get());
      if (lockBySlot.containsKey(slot)) {
        remainingBySlot.merge(slot, 1, Integer::sum);
      }
    }
    for (Map.Entry<LockedSlot, Integer> entry : remainingBySlot.entrySet()) {
      if (entry.getValue() > 1) {
        throw laborCommitmentContractFault(
            entry.getKey().household(),
            day,
            "locked-slot-still-duplicated-after-reconcile: unit="
                + entry.getKey().unit().value()
                + " role="
                + entry.getKey().role().value()
                + " rows="
                + entry.getValue());
      }
    }
    return removedTotal;
  }

  /** DEF-7 锁定槽键：household = 受雇家户（{@link EmploymentLock#worker()}），不含雇主。 */
  private record LockedSlot(HouseholdId household, ProductionUnitId unit, ProductionRoleId role) {
    LockedSlot {
      Objects.requireNonNull(household, "LockedSlot.household 不得为 null");
      Objects.requireNonNull(unit, "LockedSlot.unit 不得为 null");
      Objects.requireNonNull(role, "LockedSlot.role 不得为 null");
    }

    static LockedSlot of(EmploymentLock lock) {
      return new LockedSlot(lock.worker(), lock.unit(), lock.role());
    }
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

  /** (unit, household, role) 的复合键拼写点（阶段 2/3 共用；无状态；同名同 unit 多角色不再撞车）。 */
  static String key(ProductionUnitId unitId, HouseholdId household, ProductionRoleId role) {
    return unitId.value() + "\u0000" + household.value() + "\u0000" + role.value();
  }

  /** 阶段 2 的"想要量"：同一户在同一 unit 的某个角色上想要多少劳动。 */
  private record Wanted(HouseholdId household, ProductionRoleId role, long laborMilli) {}

  /**
   * ★★★ <b>D-032 C9 hotfix：按 roster/plannedLabor 每 tick 确保运输 operator 的 PRODUCTION 承诺存在。</b>
   *
   * <p>运输 unit 的服务产出没有普通市场价 ⇒ M3 的 {@code positionNet=0}，它不会进入 M6 {@code actualProductionPlan}，于是
   * labor queue 的常规 offer/写回路径不会重发它的承诺。本方法只处理 {@link CaravanTransportTemplate#KIND} 产业里<b>非
   * MONEY_WAGE</b>（operator 自雇）参与项：
   *
   * <ul>
   *   <li>已有正额承诺 ⇒ 不动（创世切出的份额/上一 tick 的承诺原样保留）；
   *   <li>缺失或只有 0 额行 ⇒ 用同一 {@code HouseholdLaborCommitment.idOfProduction} 拼 id，按 {@code
   *       min(plannedLabor, 当刻 remaining)} 新发；remaining = 该户当刻 {@code laborMilli} − 该户全部既存
   *       PRODUCTION 承诺；
   *   <li>remaining ≤ 0 / 缺批次 ⇒ 具名 INFO {@code CARAVAN_ROSTER_LABOR_REISSUE_SKIPPED}，不伪造劳动。
   * </ul>
   *
   * <p>★ hired_driver 是 MoneyWage 角色：它的实际承诺仍只由常规排队在真实剩余劳动上形成，本方法不另发。
   */
  private static void reissueCaravanRosterCommitments(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRoster> productionRosters,
      Map<IndustryId, IndustryDefinitionView> industryTemplates,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      long day) {
    if (productionRosters.isEmpty()) {
      return;
    }
    Map<HouseholdId, List<HouseholdLaborCommitment>> byHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      byHousehold
          .computeIfAbsent(commitment.household(), ignored -> new ArrayList<>())
          .add(commitment);
    }
    List<ProductionUnitId> orderedUnits = new ArrayList<>(productionRosters.keySet());
    orderedUnits.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : orderedUnits) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView industry = industryTemplates.get(unit.industry());
      if (!isCaravanTransportIndustry(industry)) {
        continue;
      }
      ProductionRoster roster = productionRosters.get(unitId);
      if (roster == null) {
        continue;
      }
      List<ProductionParticipant> participants = new ArrayList<>(roster.participants());
      participants.sort(
          Comparator.comparing((ProductionParticipant participant) -> participant.role().value())
              .thenComparing(participant -> participant.household().value()));
      for (ProductionParticipant participant : participants) {
        ProductionRoleDefinition role = roleOf(industry, participant.role());
        if (role == null || role.payment() instanceof RolePayment.MoneyWage) {
          continue; // hired_driver 走常规 MONEY_WAGE 排队路径，不在此重发
        }
        long planned = participant.plannedLaborMilliPerDay();
        if (planned <= 0L) {
          continue;
        }
        HouseholdLaborCommitment existing =
            slotCommitmentOf(laborCommitments, participant.household(), unitId, participant.role());
        if (existing != null && existing.laborMilli() > 0L) {
          continue; // 已有真实承诺：保留（不每 tick 覆盖运营中的份额）
        }
        HouseholdEconomy row = householdEconomies.get(participant.household());
        if (row == null) {
          logCaravanReissueSkipped(
              day,
              unitId,
              participant.household(),
              participant.role(),
              "household-economy-missing");
          continue;
        }
        long committed = productionCommittedLaborOf(laborCommitments, participant.household());
        long remaining = Math.max(0L, row.laborMilli() - committed);
        long amount = Math.min(planned, remaining);
        if (amount <= 0L) {
          logCaravanReissueSkipped(
              day, unitId, participant.household(), participant.role(), "no-real-remaining-labor");
          continue;
        }
        PeopleLotId lot = chooseLot(participant.household(), byHousehold, composition);
        if (lot == null) {
          logCaravanReissueSkipped(
              day, unitId, participant.household(), participant.role(), "no-labor-lot");
          continue;
        }
        LaborAllocationId id =
            HouseholdLaborCommitment.idOfProduction(
                unitId, participant.role(), lot, participant.household());
        HouseholdLaborCommitment fresh =
            new HouseholdLaborCommitment(
                id,
                lot,
                participant.household(),
                unit.operator(),
                unitId.value(),
                amount,
                1L,
                LaborCommitmentKind.PRODUCTION,
                Optional.of(participant.role()));
        HouseholdLaborCommitment previous = laborCommitments.putIfAbsent(id, fresh);
        if (previous != null) {
          if (previous.kind() == LaborCommitmentKind.GOV_SERVICE) {
            throw laborCommitmentContractFault(
                participant.household(), day, "运输名册重发撞上 GOV_SERVICE 承诺: id=" + id.value());
          }
          if (previous.laborMilli() > 0L) {
            continue;
          }
          laborCommitments.put(id, withLaborMilli(previous, amount));
        }
        byHousehold
            .computeIfAbsent(participant.household(), ignored -> new ArrayList<>())
            .add(laborCommitments.get(id));
        if (EconomyLog.population().isInfoEnabled()) {
          EventLog.channel(EconomyLog.population())
              .info(
                  LogEvent.of(
                      "CARAVAN_ROSTER_LABOR_REISSUED",
                      EconomyLogSource.ECONOMY_POPULATION,
                      "day",
                      day,
                      "unit",
                      unitId.value(),
                      "household",
                      participant.household().value(),
                      "role",
                      participant.role().value(),
                      "laborMilli",
                      amount,
                      "plannedLaborMilli",
                      planned,
                      "remainingBudgetMilli",
                      remaining));
        }
      }
    }
  }

  /** 运输产业判据：只认 CaravanTransportTemplate 的产业前缀（不另写字面量）。 */
  private static boolean isCaravanTransportIndustry(IndustryDefinitionView industry) {
    if (industry == null) {
      return false;
    }
    String id = industry.id().value();
    return id.equals(CaravanTransportTemplate.KIND)
        || id.startsWith(CaravanTransportTemplate.KIND + "@");
  }

  /** 方案里该角色定义；缺 ⇒ null（调用方跳过，不猜第二套角色）。 */
  private static ProductionRoleDefinition roleOf(
      IndustryDefinitionView industry, ProductionRoleId roleId) {
    if (industry == null || roleId == null) {
      return null;
    }
    for (ProductionRoleDefinition role : industry.roles()) {
      if (role.id().equals(roleId)) {
        return role;
      }
    }
    return null;
  }

  /** 同一 (household, unit, role) 的既有承诺（多 lot 时取 id 最小者）；没有 ⇒ null。 */
  private static HouseholdLaborCommitment slotCommitmentOf(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      HouseholdId household,
      ProductionUnitId unitId,
      ProductionRoleId role) {
    HouseholdLaborCommitment chosen = null;
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION
          || commitment.productionRole().isEmpty()
          || !commitment.household().equals(household)
          || !commitment.activity().equals(unitId.value())
          || !commitment.productionRole().get().equals(role)) {
        continue;
      }
      if (chosen == null || commitment.id().value().compareTo(chosen.id().value()) < 0) {
        chosen = commitment;
      }
    }
    return chosen;
  }

  /** 该户全部既有 PRODUCTION 承诺之和（GOV_SERVICE 不占普通生产预算）。 */
  private static long productionCommittedLaborOf(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments, HouseholdId household) {
    long total = 0L;
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      if (commitment.kind() == LaborCommitmentKind.PRODUCTION
          && commitment.household().equals(household)) {
        total = Math.addExact(total, commitment.laborMilli());
      }
    }
    return total;
  }

  /** 运输名册重发被跳过时的具名 INFO（业务拒绝，不伪造 0 劳动）。 */
  private static void logCaravanReissueSkipped(
      long day,
      ProductionUnitId unitId,
      HouseholdId household,
      ProductionRoleId role,
      String reason) {
    if (!EconomyLog.population().isInfoEnabled()) {
      return;
    }
    EventLog.channel(EconomyLog.population())
        .info(
            LogEvent.of(
                "CARAVAN_ROSTER_LABOR_REISSUE_SKIPPED",
                EconomyLogSource.ECONOMY_POPULATION,
                "day",
                day,
                "unit",
                unitId.value(),
                "household",
                household.value(),
                "role",
                role.value(),
                "reason",
                reason));
  }
}
