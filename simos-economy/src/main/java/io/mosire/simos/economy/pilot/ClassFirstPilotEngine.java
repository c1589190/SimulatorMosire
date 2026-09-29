package io.mosire.simos.economy.pilot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 独立最小试点引擎：单 mode（佃农制农业），按"计划 → 投入 → 生产 → 阶层分配 → 家户份额 → 滚动账户 → 消费 → 借贷 → 催收 → 人口流动 → 报告"推进。
 *
 * <p>★ 只依赖 pilot 包内的数据 record；不读写 {@code EconomyData}/三国 compact world/E1–E6。所有集合都是 {@link
 * LinkedHashMap} 或按固定键排序的 list；没有任何随机数/UUID/时钟；同样的初始夹具必然得到同样的轨迹。
 *
 * <p>账户是双边的：任何一笔债务/索取权都会同时记到 {@code (债务人, 债权人)} 与 {@code (债权人, 债务人)} 两条账户上， 因此全体账户的 {@code
 * cumulativeNet} 之和恒为 0。正净额只出现在 claim 侧，负净额只出现在 debt 侧。
 */
public final class ClassFirstPilotEngine {

  private static final String TERMS_RENT = "rent-arrears";
  private static final String TERMS_INPUT_SEED = "input-seed";
  private static final String TERMS_INPUT_LABOR = "input-labor/wage";
  private static final String TERMS_GRAIN_LOAN = "grain-loan";
  private static final String TERMS_MONEY_LOAN = "money-loan";

  private static final Comparator<HouseholdState> BY_RANK_THEN_ID =
      Comparator.comparingLong((HouseholdState h) -> classPosition(h).rank())
          .thenComparing(h -> h.id);

  private final PilotConfig config;
  private final LinkedHashMap<String, HouseholdState> households = new LinkedHashMap<>();
  private final LinkedHashMap<AccountKey, AccountState> accounts = new LinkedHashMap<>();
  private final LinkedHashMap<String, LenderState> lenders = new LinkedHashMap<>();
  private final List<PilotModel.Transition> transitions = new ArrayList<>();
  private final List<PilotModel.TickReport> reports = new ArrayList<>();
  private final List<PilotModel.ProductionAccount> lastProductionAccounts = new ArrayList<>();
  private final LinkedHashMap<String, Long> deployedOwnLabor = new LinkedHashMap<>();
  private final List<String> tickDiagnostics = new ArrayList<>();

  private long tick;
  private long producedGrainTotal;
  private long seedUsedTotal;
  private long rationConsumedTotal;
  private long clothConsumedTotal;
  private long borrowedGrainTotal;
  private long borrowedMoneyTotal;
  private long boughtGrainTotal;
  private long liquidSeizedTotal;
  private long landSeizedTotal;
  private long capitalizedTotal;
  private long redLightTotal;
  private long collectionEventCount;
  private long interestChargedTotal;
  private long rentPaidTotal;
  private long wagePaidTotal;
  private long externalSeedPaidTotal;
  private long residualPaidTotal;
  private long lastTickRedLights;
  private long lastTickBaseGap;
  private final LinkedHashMap<String, Long> pendingBaseGap = new LinkedHashMap<>();
  private final long initialGrainTotal;
  private final long initialClothTotal;
  private final long initialHouseholdMoneyTotal;
  private final long initialLenderMoneyTotal;

  public ClassFirstPilotEngine(PilotConfig config, List<PilotModel.Household> initialHouseholds) {
    this.config = Objects.requireNonNull(config, "config");
    Objects.requireNonNull(initialHouseholds, "initialHouseholds");
    for (PilotModel.Household spec : initialHouseholds) {
      PilotModel.classPosition(spec.classPositionId());
      if (households.containsKey(spec.id())) {
        throw new IllegalArgumentException("duplicate household id: " + spec.id());
      }
      households.put(spec.id(), new HouseholdState(spec));
    }
    if (households.isEmpty()) {
      throw new IllegalArgumentException("at least one household is required");
    }
    PilotModel.Lender lenderSpec = config.lender();
    lenders.put(lenderSpec.id(), new LenderState(lenderSpec));
    this.initialGrainTotal = totalGrain();
    this.initialClothTotal = totalCloth();
    this.initialHouseholdMoneyTotal = totalHouseholdMoney();
    this.initialLenderMoneyTotal = totalLenderMoney();
  }

  // ── 对外只读 API ─────────────────────────────────────────────────────────────

  public long tick() {
    return tick;
  }

  public List<PilotModel.TickReport> reports() {
    return List.copyOf(reports);
  }

  public List<PilotModel.Transition> transitions() {
    return List.copyOf(transitions);
  }

  public List<PilotModel.ProductionAccount> lastProductionAccounts() {
    return List.copyOf(lastProductionAccounts);
  }

  public List<PilotModel.Household> households() {
    List<PilotModel.Household> snapshots = new ArrayList<>();
    for (HouseholdState h : households.values()) {
      snapshots.add(h.snapshot());
    }
    return snapshots;
  }

  public List<PilotModel.HouseholdAccount> accounts() {
    List<PilotModel.HouseholdAccount> snapshots = new ArrayList<>();
    for (AccountState state : accounts.values()) {
      snapshots.add(state.snapshot(config.mode().id()));
    }
    return snapshots;
  }

  public List<PilotModel.HouseholdAccount> accountsOf(String ownerId) {
    List<PilotModel.HouseholdAccount> snapshots = new ArrayList<>();
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(ownerId)) {
        snapshots.add(state.snapshot(config.mode().id()));
      }
    }
    return snapshots;
  }

  public long totalGrain() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.grain();
    }
    for (LenderState lender : lenders.values()) {
      total += lender.goods.getOrDefault(PilotModel.GRAIN, 0L);
    }
    return total;
  }

  public long totalCloth() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.cloth();
    }
    for (LenderState lender : lenders.values()) {
      total += lender.goods.getOrDefault(PilotModel.CLOTH, 0L);
    }
    return total;
  }

  public long totalHouseholdMoney() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.money;
    }
    return total;
  }

  public long totalLenderMoney() {
    long total = 0L;
    for (LenderState lender : lenders.values()) {
      total += lender.money;
    }
    return total;
  }

  public long totalLand() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.land;
    }
    return total;
  }

  public long totalTools() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.tools;
    }
    return total;
  }

  public long totalPopulation() {
    long total = 0L;
    for (HouseholdState h : households.values()) {
      total += h.population;
    }
    return total;
  }

  /** 全体账户（含放贷方镜像账户）的 cumulativeNet 之和；双边记账 ⇒ 恒为 0。 */
  public long accountNetSum() {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      total += state.cumulativeNet;
    }
    return total;
  }

  public long producedGrainTotal() {
    return producedGrainTotal;
  }

  public long seedUsedTotal() {
    return seedUsedTotal;
  }

  public long rationConsumedTotal() {
    return rationConsumedTotal;
  }

  public long clothConsumedTotal() {
    return clothConsumedTotal;
  }

  public long borrowedGrainTotal() {
    return borrowedGrainTotal;
  }

  public long borrowedMoneyTotal() {
    return borrowedMoneyTotal;
  }

  public long boughtGrainTotal() {
    return boughtGrainTotal;
  }

  public long liquidSeizedTotal() {
    return liquidSeizedTotal;
  }

  public long landSeizedTotal() {
    return landSeizedTotal;
  }

  public long capitalizedTotal() {
    return capitalizedTotal;
  }

  public long redLightTotal() {
    return redLightTotal;
  }

  public long collectionEventCount() {
    return collectionEventCount;
  }

  public long interestChargedTotal() {
    return interestChargedTotal;
  }

  public long rentPaidTotal() {
    return rentPaidTotal;
  }

  public long wagePaidTotal() {
    return wagePaidTotal;
  }

  public long externalSeedPaidTotal() {
    return externalSeedPaidTotal;
  }

  public long residualPaidTotal() {
    return residualPaidTotal;
  }

  public long initialGrainTotal() {
    return initialGrainTotal;
  }

  public long initialClothTotal() {
    return initialClothTotal;
  }

  public long initialHouseholdMoneyTotal() {
    return initialHouseholdMoneyTotal;
  }

  public long initialLenderMoneyTotal() {
    return initialLenderMoneyTotal;
  }

  /** 跑 {@code ticks} 个 tick，返回本轮新增的报告。 */
  public List<PilotModel.TickReport> runTicks(int ticks) {
    if (ticks < 0) {
      throw new IllegalArgumentException("ticks must be >= 0");
    }
    List<PilotModel.TickReport> produced = new ArrayList<>();
    for (int i = 0; i < ticks; i++) {
      produced.add(advanceTick());
    }
    return List.copyOf(produced);
  }

  // ── 单 tick 主流程 ───────────────────────────────────────────────────────────

  public PilotModel.TickReport advanceTick() {
    tick++;
    tickDiagnostics.clear();
    lastTickRedLights = 0L;
    lastTickBaseGap = 0L;

    List<PlanDraft> drafts = plan();
    List<PilotModel.ProductionPlan> plans = procureInputs(drafts);
    List<PlannedProduction> productions = produce(plans);
    List<PlannedDistribution> distributions = distributeByClass(productions);
    allocateToHouseholds(distributions);
    rollAccounts();
    consume();
    borrowIfNeeded();
    dueAndCollect();
    populationFlow();

    PilotModel.TickReport report = buildReport();
    reports.add(report);
    return report;
  }

  // ── 1) plan：本期土地/劳动/工具需求（种子/外雇劳动在 procure 里定案） ─────────

  private List<PlanDraft> plan() {
    long availableLeaseLand = 0L;
    for (HouseholdState h : households.values()) {
      if (h.classPositionId.equals(PilotModel.LANDLORD_ID)) {
        availableLeaseLand += h.land;
      }
    }

    List<HouseholdState> farming = new ArrayList<>();
    for (HouseholdState h : households.values()) {
      if (isFarmingClass(h.classPositionId)) {
        farming.add(h);
      }
    }
    farming.sort(BY_RANK_THEN_ID);

    List<PlanDraft> drafts = new ArrayList<>();
    for (HouseholdState h : farming) {
      long ownedLand = h.land;
      long toolsCap = h.tools * config.toolCapacityPerTool();
      long desiredLease = 0L;
      if (PilotModel.TENANT_ID.equals(h.classPositionId)) {
        desiredLease = Math.max(0L, toolsCap - ownedLand);
        desiredLease = Math.min(desiredLease, availableLeaseLand);
      }
      long desiredLand = Math.min(ownedLand + desiredLease, toolsCap);
      long reservedLease = Math.min(desiredLease, Math.max(0L, desiredLand - ownedLand));
      availableLeaseLand -= reservedLease;

      PlanDraft draft = new PlanDraft(h);
      draft.ownedLand = ownedLand;
      draft.desiredLand = desiredLand;
      draft.toolsUsed = Math.min(h.tools, ceilDiv(desiredLand, config.toolCapacityPerTool()));
      draft.toolsShortageLand = Math.max(0L, ownedLand + desiredLease - toolsCap);
      drafts.add(draft);
    }
    return drafts;
  }

  // ── 2) procureInputs：self=0 成本；别人提供=provider claim/debt；抽不到=scaled shortage ──

  private List<PilotModel.ProductionPlan> procureInputs(List<PlanDraft> drafts) {
    LinkedHashMap<String, Long> laborRemaining = new LinkedHashMap<>();
    List<HouseholdState> laborers = new ArrayList<>();
    for (HouseholdState h : households.values()) {
      if (PilotModel.LABORER_ID.equals(h.classPositionId)) {
        laborers.add(h);
      }
    }
    laborers.sort(BY_RANK_THEN_ID);
    for (HouseholdState laborer : laborers) {
      laborRemaining.put(laborer.id, laborer.laborUnits());
    }

    List<PilotModel.ProductionPlan> plans = new ArrayList<>();
    for (PlanDraft draft : drafts) {
      HouseholdState h = draft.household;
      long ownLabor = h.laborUnits();

      // 劳动上限（自有 + 可抽到的雇农劳动）
      long neededLabor = draft.desiredLand * config.laborPerLand();
      long ownUsed = Math.min(ownLabor, neededLabor);
      long externalNeeded = neededLabor - ownUsed;
      LinkedHashMap<String, Long> externalLabor = new LinkedHashMap<>();
      if (externalNeeded > 0L) {
        for (HouseholdState laborer : laborers) {
          if (externalNeeded <= 0L) {
            break;
          }
          long available = laborRemaining.getOrDefault(laborer.id, 0L);
          long take = Math.min(externalNeeded, available);
          if (take > 0L) {
            externalLabor.put(laborer.id, take);
            laborRemaining.put(laborer.id, available - take);
            externalNeeded -= take;
          }
        }
      }
      long laborLimitedLand =
          (ownUsed + sumValues(externalLabor)) / Math.max(1L, config.laborPerLand());
      long plannedLand = Math.min(draft.desiredLand, laborLimitedLand);
      draft.laborShortageLand = draft.desiredLand - plannedLand;

      neededLabor = plannedLand * config.laborPerLand();
      ownUsed = Math.min(ownLabor, neededLabor);
      externalNeeded = neededLabor - ownUsed;
      LinkedHashMap<String, Long> allocatedLabor = externalLabor;
      externalLabor = trimTo(allocatedLabor, externalNeeded);
      returnTrimmed(laborRemaining, allocatedLabor, externalLabor);
      draft.ownLaborUsed = ownUsed;

      // 种子：先自给，再向有余粮家户抽；仍不够就按缺口缩地
      long seedRequired = plannedLand * config.seedPerLand();
      long seedSelf = Math.min(h.grain(), seedRequired);
      long seedExternalNeeded = seedRequired - seedSelf;
      LinkedHashMap<String, Long> seedProviders = new LinkedHashMap<>();
      if (seedExternalNeeded > 0L) {
        for (HouseholdState other : orderedOthers(h.id)) {
          if (seedExternalNeeded <= 0L) {
            break;
          }
          long lendable = Math.max(0L, other.grain() - protectedReserveGrain(other));
          long take = Math.min(seedExternalNeeded, lendable);
          if (take > 0L) {
            seedProviders.put(other.id, take);
            seedExternalNeeded -= take;
          }
        }
      }
      long seedAvailable = seedSelf + sumValues(seedProviders);
      long seedLimitedLand = seedAvailable / Math.max(1L, config.seedPerLand());
      if (seedLimitedLand < plannedLand) {
        draft.seedShortage = plannedLand - seedLimitedLand;
        plannedLand = seedLimitedLand;
        neededLabor = plannedLand * config.laborPerLand();
        ownUsed = Math.min(ownLabor, neededLabor);
        externalNeeded = neededLabor - ownUsed;
        LinkedHashMap<String, Long> allocatedLaborAfterSeed = externalLabor;
        externalLabor = trimTo(allocatedLaborAfterSeed, externalNeeded);
        returnTrimmed(laborRemaining, allocatedLaborAfterSeed, externalLabor);
        draft.ownLaborUsed = ownUsed;
        seedRequired = plannedLand * config.seedPerLand();
        seedSelf = Math.min(h.grain(), seedRequired);
        seedExternalNeeded = seedRequired - seedSelf;
        seedProviders = trimTo(seedProviders, seedExternalNeeded);
      }
      draft.plannedLand = plannedLand;
      draft.leasedLand = Math.max(0L, plannedLand - Math.min(draft.ownedLand, plannedLand));
      draft.seedRequired = seedRequired;
      draft.seedSelf = seedSelf;
      draft.seedExternal = sumValues(seedProviders);
      draft.seedShortage = Math.max(0L, seedRequired - seedSelf - draft.seedExternal);
      draft.toolsUsed = Math.min(h.tools, ceilDiv(plannedLand, config.toolCapacityPerTool()));
      draft.externalLaborByProvider = externalLabor;
      draft.seedExternalByProvider = seedProviders;

      // 实际扣种子：自给种子从自己库存出；外部种子从提供者库存出（提供者拿到 claim）
      h.goods.merge(PilotModel.GRAIN, -seedSelf, Long::sum);
      for (Map.Entry<String, Long> entry : seedProviders.entrySet()) {
        HouseholdState provider = households.get(entry.getKey());
        provider.goods.merge(PilotModel.GRAIN, -entry.getValue(), Long::sum);
        postObligation(
            h.id,
            provider.id,
            PilotModel.GRAIN,
            entry.getValue(),
            TERMS_INPUT_SEED,
            config.loanInterestRatePerMille(),
            tick + config.collectionIntervalTicks());
      }
      seedUsedTotal += seedSelf + draft.seedExternal;

      // 劳动用别人提供 ⇒ 生产账户欠提供者工资；分配时按"工资/给养"优先偿付
      for (Map.Entry<String, Long> entry : externalLabor.entrySet()) {
        long wage = entry.getValue() * config.wagePerLabor();
        postObligation(
            h.id,
            entry.getKey(),
            PilotModel.GRAIN,
            wage,
            TERMS_INPUT_LABOR,
            config.loanInterestRatePerMille(),
            tick + config.collectionIntervalTicks());
      }
      deployedOwnLabor.put(h.id, ownUsed);

      plans.add(draft.toRecord(tick));
    }
    return plans;
  }

  // ── 3) produce：粮产出 ───────────────────────────────────────────────────────

  private List<PlannedProduction> produce(List<PilotModel.ProductionPlan> plans) {
    List<PlannedProduction> productions = new ArrayList<>();
    for (PilotModel.ProductionPlan plan : plans) {
      long output = plan.plannedLand() * config.yieldPerLand() * plan.efficiencyPerMille() / 1000L;
      producedGrainTotal += output;
      productions.add(new PlannedProduction(plan, output));
      if (plan.seedShortage() > 0L
          || plan.laborShortageLand() > 0L
          || plan.toolsShortageLand() > 0L) {
        tickDiagnostics.add(
            "produce shortage household="
                + plan.householdId()
                + " seedShortageLand="
                + plan.seedShortage()
                + " laborShortageLand="
                + plan.laborShortageLand()
                + " toolsShortageLand="
                + plan.toolsShortageLand());
      }
    }
    return productions;
  }

  // ── 4) distributeByClass：先地租、工资/给养、残值；先到阶层位置 ───────────────

  private List<PlannedDistribution> distributeByClass(List<PlannedProduction> productions) {
    List<PlannedDistribution> distributions = new ArrayList<>();
    List<HouseholdState> landlords = householdsOfClass(PilotModel.LANDLORD_ID);
    for (PlannedProduction production : productions) {
      PilotModel.ProductionPlan plan = production.plan;
      PilotModel.ClassRule rule = config.mode().ruleFor(plan.classPositionId());
      long pool = production.poolGrain;
      long rentDue = plan.leasedLand() * config.rentPerLand() * rule.rentSharePerMille() / 1000L;
      LinkedHashMap<String, Long> rentByLandlord = splitByParticipation(rentDue, landlords);
      for (Map.Entry<String, Long> entry : rentByLandlord.entrySet()) {
        postObligation(
            plan.householdId(),
            entry.getKey(),
            PilotModel.GRAIN,
            entry.getValue(),
            TERMS_RENT,
            config.loanInterestRatePerMille(),
            tick + config.collectionIntervalTicks());
      }

      LinkedHashMap<String, Long> wageDue = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : plan.externalLaborByProvider().entrySet()) {
        wageDue.put(entry.getKey(), entry.getValue() * config.wagePerLabor());
      }
      LinkedHashMap<String, Long> seedDue = new LinkedHashMap<>(plan.seedExternalByProvider());
      distributions.add(new PlannedDistribution(plan, pool, rentByLandlord, wageDue, seedDue));
    }
    return distributions;
  }

  // ── 5) allocateToHouseholds：按 participationShare 支付到户（地租→工资→种子→残值） ──

  private void allocateToHouseholds(List<PlannedDistribution> distributions) {
    lastProductionAccounts.clear();
    for (PlannedDistribution distribution : distributions) {
      PilotModel.ProductionPlan plan = distribution.plan;
      HouseholdState operator = households.get(plan.householdId());
      long remaining = distribution.poolGrain;

      long rentPaid = 0L;
      for (Map.Entry<String, Long> entry : distribution.rentByLandlord.entrySet()) {
        long pay = Math.min(remaining, entry.getValue());
        if (pay > 0L) {
          give(households.get(entry.getKey()), PilotModel.GRAIN, pay);
          postPayment(operator.id, entry.getKey(), PilotModel.GRAIN, pay);
          remaining -= pay;
          rentPaid += pay;
          rentPaidTotal += pay;
        }
      }
      long wagePaid = 0L;
      for (Map.Entry<String, Long> entry : distribution.wageDueByProvider.entrySet()) {
        long pay = Math.min(remaining, entry.getValue());
        if (pay > 0L) {
          give(households.get(entry.getKey()), PilotModel.GRAIN, pay);
          postPayment(operator.id, entry.getKey(), PilotModel.GRAIN, pay);
          remaining -= pay;
          wagePaid += pay;
          wagePaidTotal += pay;
        }
      }
      long seedPaid = 0L;
      for (Map.Entry<String, Long> entry : distribution.seedDueByProvider.entrySet()) {
        long pay = Math.min(remaining, entry.getValue());
        if (pay > 0L) {
          give(households.get(entry.getKey()), PilotModel.GRAIN, pay);
          postPayment(operator.id, entry.getKey(), PilotModel.GRAIN, pay);
          remaining -= pay;
          seedPaid += pay;
          externalSeedPaidTotal += pay;
        }
      }
      long residualPaid = remaining;
      if (residualPaid > 0L) {
        give(operator, PilotModel.GRAIN, residualPaid);
        residualPaidTotal += residualPaid;
      }

      LinkedHashMap<String, Long> distributedByClass = new LinkedHashMap<>();
      if (rentPaid > 0L) {
        distributedByClass.merge(PilotModel.LANDLORD_ID, rentPaid, Long::sum);
      }
      if (wagePaid > 0L) {
        distributedByClass.merge(PilotModel.LABORER_ID, wagePaid, Long::sum);
      }
      if (residualPaid > 0L) {
        distributedByClass.merge(
            config.mode().ruleFor(plan.classPositionId()).residualToClassPositionId(),
            residualPaid,
            Long::sum);
      }

      List<String> shortages = new ArrayList<>();
      if (plan.seedShortage() > 0L) {
        shortages.add("seedShortage=" + plan.seedShortage());
      }
      if (plan.laborShortageLand() > 0L) {
        shortages.add("laborShortageLand=" + plan.laborShortageLand());
      }
      if (plan.toolsShortageLand() > 0L) {
        shortages.add("toolsShortageLand=" + plan.toolsShortageLand());
      }
      lastProductionAccounts.add(
          new PilotModel.ProductionAccount(
              tick,
              plan.householdId(),
              plan.classPositionId(),
              plan.plannedLand(),
              plan.plannedLand(),
              distribution.poolGrain,
              plan.seedSelf(),
              plan.seedExternal(),
              seedPaid,
              rentPaid,
              wagePaid,
              residualPaid,
              distributedByClass,
              shortages));
    }
  }

  // ── 6) rollAccounts：正净额 claim、负净额 debt；利息只加 debt；跨 tick 累积 ─────

  private void rollAccounts() {
    List<AccountState> snapshot = new ArrayList<>(accounts.values());
    for (AccountState state : snapshot) {
      if (state.cumulativeNet < 0L && state.interestRatePerMille > 0L) {
        long interest = (-state.cumulativeNet) * state.interestRatePerMille / 1000L;
        if (interest > 0L) {
          state.cumulativeNet -= interest;
          state.interestAccrued += interest;
          interestChargedTotal += interest;
          AccountKey mirrorKey =
              new AccountKey(state.key.counterpartyId, state.key.ownerId, state.key.unit);
          AccountState mirror = accounts.get(mirrorKey);
          if (mirror == null) {
            mirror =
                new AccountState(
                    mirrorKey, state.terms, state.interestRatePerMille, state.nextDueTick);
            accounts.put(mirrorKey, mirror);
          }
          mirror.cumulativeNet += interest;
          mirror.interestAccrued += interest;
        }
      }
    }
    for (AccountState state : new ArrayList<>(accounts.values())) {
      if (state.cumulativeNet == 0L) {
        state.status = PilotModel.AccountStatus.SETTLED;
      } else if (state.cumulativeNet < 0L && tick >= state.nextDueTick) {
        state.status = PilotModel.AccountStatus.DUE;
      } else if (state.status == PilotModel.AccountStatus.SETTLED) {
        state.status = PilotModel.AccountStatus.ACTIVE;
      }
    }
  }

  // ── 7) consume：baseRation 全体 + laborRation 实际出劳动者；缺非必要品扣效率 ──

  private void consume() {
    pendingBaseGap.clear();
    for (HouseholdState h : households.values()) {
      long baseNeed = config.baseRationPerCapita() * h.population;
      long ownLabor = deployedOwnLabor.getOrDefault(h.id, 0L);
      long laborNeed = ownLabor * config.laborRationPerLabor();
      long totalNeed = baseNeed + laborNeed;
      long eaten = Math.min(h.grain(), totalNeed);
      long baseEaten = Math.min(eaten, baseNeed);
      long baseGap = baseNeed - baseEaten;
      if (eaten > 0L) {
        h.goods.merge(PilotModel.GRAIN, -eaten, Long::sum);
        rationConsumedTotal += eaten;
      }
      lastTickBaseGap += baseGap;
      pendingBaseGap.put(h.id, baseGap);
      if (baseGap > 0L) {
        tickDiagnostics.add(
            "consume baseGap household="
                + h.id
                + " baseNeed="
                + baseNeed
                + " eaten="
                + eaten
                + " gap="
                + baseGap);
      }

      long clothNeed = h.population * config.nonEssentialNeedPerMille() / 1000L;
      long clothEaten = Math.min(h.cloth(), clothNeed);
      if (clothEaten > 0L) {
        h.goods.merge(PilotModel.CLOTH, -clothEaten, Long::sum);
        clothConsumedTotal += clothEaten;
      }
      long coverage = clothNeed <= 0L ? 1000L : clothEaten * 1000L / clothNeed;
      long penalty = (1000L - coverage) * config.nonEssentialEfficiencyPenaltyPerMille() / 1000L;
      h.laborEfficiencyPerMille = Math.max(300L, 1000L - penalty);
    }
  }

  // ── 8) borrowIfNeeded：借商品 → 借钱买 → 红灯 ────────────────────────────────

  private void borrowIfNeeded() {
    for (HouseholdState h : households.values()) {
      long gap = Math.max(0L, baseGapOf(h));
      if (gap <= 0L) {
        continue;
      }
      long originalGap = gap;

      // (a) 借商品：向有余粮且高于保护储备的家户借
      long borrowedGoods = 0L;
      long internalGrainLendable = 0L;
      for (HouseholdState lender : orderedOthers(h.id)) {
        internalGrainLendable += Math.max(0L, lender.grain() - protectedReserveGrain(lender));
      }
      if (gap > 0L) {
        for (HouseholdState lender : orderedOthers(h.id)) {
          if (gap <= 0L) {
            break;
          }
          long lendable = Math.max(0L, lender.grain() - protectedReserveGrain(lender));
          long take = Math.min(gap, lendable);
          if (take > 0L) {
            transfer(lender, h, PilotModel.GRAIN, take);
            postObligation(
                h.id,
                lender.id,
                PilotModel.GRAIN,
                take,
                TERMS_GRAIN_LOAN,
                config.loanInterestRatePerMille(),
                tick + config.collectionIntervalTicks());
            borrowedGoods += take;
            borrowedGrainTotal += take;
            gap -= take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.BORROW_GOODS,
                    h.id,
                    lender.id,
                    0L,
                    0L,
                    take,
                    "baseRationGap=" + originalGap));
          }
        }
      }

      // (b) 借钱（仍买不到粮时下一步只能红灯）
      long moneyBorrowed = 0L;
      if (gap > 0L) {
        LenderState lender = lenders.values().iterator().next();
        long moneyNeeded = gap * config.moneyPerGrain();
        long lend = Math.min(moneyNeeded, lender.money);
        if (lend > 0L) {
          lender.money -= lend;
          h.money += lend;
          postObligation(
              h.id,
              config.lender().id(),
              PilotModel.MONEY,
              lend,
              TERMS_MONEY_LOAN,
              config.lender().interestRatePerMille(),
              Math.max(tick + 1L, config.lender().nextDueTick()));
          borrowedMoneyTotal += lend;
          moneyBorrowed = lend;
          transitions.add(
              new PilotModel.Transition(
                  tick,
                  PilotModel.TransitionKind.BORROW_MONEY,
                  h.id,
                  config.lender().id(),
                  0L,
                  0L,
                  lend,
                  "baseRationGap=" + originalGap));
        }
      }

      // (c) 借钱买：向有余粮家户按 moneyPerGrain 现货买
      long bought = 0L;
      if (gap > 0L && h.money > 0L) {
        for (HouseholdState seller : orderedOthers(h.id)) {
          if (gap <= 0L) {
            break;
          }
          long sellable = Math.max(0L, seller.grain() - protectedReserveGrain(seller));
          long affordable = h.money / config.moneyPerGrain();
          long take = Math.min(Math.min(gap, sellable), affordable);
          if (take > 0L) {
            long cost = take * config.moneyPerGrain();
            transfer(seller, h, PilotModel.GRAIN, take);
            h.money -= cost;
            seller.money += cost;
            bought += take;
            boughtGrainTotal += take;
            gap -= take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.BUY_GRAIN,
                    h.id,
                    seller.id,
                    0L,
                    0L,
                    cost,
                    "grain=" + take + " money=" + cost));
          }
        }
      }

      // 借到/买到的粮当 tick 就吃掉，填 base 缺口
      long filled = originalGap - gap;
      if (filled > 0L) {
        h.goods.merge(PilotModel.GRAIN, -filled, Long::sum);
        rationConsumedTotal += filled;
        lastTickBaseGap = Math.max(0L, lastTickBaseGap - filled);
      }
      pendingBaseGap.put(h.id, gap);
      if (filled > 0L) {
        List<HouseholdState> landlords = householdsOfClass(PilotModel.LANDLORD_ID);
        long landlordGrain = landlords.isEmpty() ? -1L : landlords.get(0).grain();
        tickDiagnostics.add(
            "borrow-cover household="
                + h.id
                + " initialGap="
                + originalGap
                + " borrowedGoods="
                + borrowedGoods
                + " moneyBorrowed="
                + moneyBorrowed
                + " boughtGrain="
                + bought
                + " filled="
                + filled
                + " remainingGap="
                + gap
                + " internalGrainLendable="
                + internalGrainLendable
                + " lenderMoney="
                + lenders.values().iterator().next().money
                + " householdGrain="
                + h.grain()
                + " householdDebtValueMilli="
                + householdDebtValueMilli(h.id)
                + " internalInterestRatePerMille="
                + config.loanInterestRatePerMille()
                + " landlordGrain="
                + landlordGrain);
      }
      if (gap > 0L) {
        lastTickRedLights++;
        redLightTotal++;
        long sellable = 0L;
        for (HouseholdState seller : orderedOthers(h.id)) {
          sellable += Math.max(0L, seller.grain() - protectedReserveGrain(seller));
        }
        tickDiagnostics.add(
            "red-light household="
                + h.id
                + " gap="
                + gap
                + " internalGrainLendable="
                + internalGrainLendable
                + " borrowedGoods="
                + borrowedGoods
                + " moneyBorrowed="
                + moneyBorrowed
                + " bought="
                + bought
                + " sellerSurplus="
                + sellable
                + " lenderMoney="
                + lenders.values().iterator().next().money);
        transitions.add(
            new PilotModel.Transition(
                tick, PilotModel.TransitionKind.RED_LIGHT, h.id, "", 0L, 0L, 0L, "仍缺基础口粮 " + gap));
      }
    }
  }

  // ── 9) dueAndCollect：只有到期/阈值/压力才催收；先流动商品，再按地主定价收地 ──

  private void dueAndCollect() {
    PilotModel.CollectionPolicy policy = config.collectionPolicy();
    for (HouseholdState h : households.values()) {
      long debtValue = householdDebtValueMilli(h.id);
      if (debtValue <= 0L) {
        h.collectionCooldownUntilTick = Math.max(h.collectionCooldownUntilTick, tick);
        continue;
      }
      boolean due = false;
      long minDueTick = Long.MAX_VALUE;
      for (AccountState state : accounts.values()) {
        if (state.key.ownerId.equals(h.id) && state.cumulativeNet < 0L) {
          minDueTick = Math.min(minDueTick, state.nextDueTick);
          if (tick >= state.nextDueTick) {
            due = true;
          }
        }
      }
      boolean threshold = debtValue >= policy.collectionThreshold() * 1000L;
      long seizableValue = seizableValueMilli(h);
      boolean pressure =
          seizableValue > 0L
              && debtValue * 1000L >= policy.collectionTriggerRatioPerMille() * seizableValue;
      if (!due && !threshold && !pressure) {
        tickDiagnostics.add(
            "collect-skip household="
                + h.id
                + " debtValueMilli="
                + debtValue
                + " thresholdMilli="
                + (policy.collectionThreshold() * 1000L)
                + " nextDueTick="
                + (minDueTick == Long.MAX_VALUE ? -1L : minDueTick)
                + " pressureRatioMilli="
                + (seizableValue <= 0L ? -1L : debtValue * 1000L / seizableValue));
        continue;
      }
      if (tick < h.collectionCooldownUntilTick) {
        tickDiagnostics.add(
            "collect-cooldown household="
                + h.id
                + " debtValueMilli="
                + debtValue
                + " cooldownUntil="
                + h.collectionCooldownUntilTick
                + " due="
                + due
                + " threshold="
                + threshold
                + " pressure="
                + pressure);
        continue;
      }

      collectionEventCount++;
      long dueValue = Math.max(1000L, debtValue * policy.collectionRatioPerMille() / 1000L);
      long remainingDue = dueValue;
      List<AccountState> debtAccounts = new ArrayList<>();
      for (AccountState state : accounts.values()) {
        if (state.key.ownerId.equals(h.id) && state.cumulativeNet < 0L) {
          debtAccounts.add(state);
        }
      }
      debtAccounts.sort(
          Comparator.comparing((AccountState s) -> PilotModel.GRAIN.equals(s.key.unit) ? 0 : 1)
              .thenComparing(s -> s.key.counterpartyId));

      for (AccountState state : debtAccounts) {
        if (remainingDue <= 0L) {
          break;
        }
        AccountKey key = state.key;
        String creditorId = key.counterpartyId;
        HouseholdState creditor = households.get(creditorId);
        long accountDebt = -state.cumulativeNet;
        long accountValueMilli = valueMilli(key.unit, accountDebt);
        long collectValueMilli = Math.min(accountValueMilli, remainingDue);
        if (PilotModel.GRAIN.equals(key.unit)) {
          long desiredGrain = Math.max(1L, collectValueMilli / 1000L);
          long availableGrain = Math.max(0L, h.grain() - protectedReserveGrain(h));
          long take = Math.min(availableGrain, desiredGrain);
          if (take > 0L) {
            transfer(h, creditor, PilotModel.GRAIN, take);
            postPayment(h.id, creditorId, PilotModel.GRAIN, take);
            remainingDue -= take * 1000L;
            liquidSeizedTotal += take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.LIQUID_SEIZED,
                    h.id,
                    creditorId,
                    0L,
                    0L,
                    take,
                    "优先扣流动粮（保留保护储备）"));
          }
          long accountRemaining = -accounts.get(key).cumulativeNet;
          boolean creditorIsCollector =
              creditor != null
                  && creditor.classPositionId.equals(policy.collectorClassPositionId());
          long dueGrain = Math.max(0L, remainingDue / 1000L);
          if (accountRemaining > 0L && creditorIsCollector) {
            long seizableGrain = Math.min(accountRemaining, dueGrain);
            long landSeized =
                Math.min(h.land, seizableGrain / Math.max(1L, policy.landPricePerUnit()));
            if (landSeized > 0L) {
              long debtReduced = landSeized * policy.landPricePerUnit();
              h.land -= landSeized;
              creditor.land += landSeized;
              postPayment(h.id, creditorId, PilotModel.GRAIN, debtReduced);
              remainingDue = Math.max(0L, remainingDue - debtReduced * 1000L);
              landSeizedTotal += landSeized;
              transitions.add(
                  new PilotModel.Transition(
                      tick,
                      PilotModel.TransitionKind.LAND_SEIZED,
                      h.id,
                      creditorId,
                      0L,
                      landSeized,
                      debtReduced,
                      "按地主定价 landPrice=" + policy.landPricePerUnit() + " 收地减债"));
            } else {
              long capitalized = Math.min(accountRemaining, dueGrain);
              if (capitalized > 0L) {
                capitalizedTotal += capitalized;
                transitions.add(
                    new PilotModel.Transition(
                        tick,
                        PilotModel.TransitionKind.DEBT_CAPITALIZED,
                        h.id,
                        creditorId,
                        0L,
                        0L,
                        0L,
                        "无流动粮/本次应还额不足以收 1 单位地，欠额资本化 " + capitalized));
              }
            }
          } else if (accountRemaining > 0L) {
            long capitalized = Math.min(accountRemaining, dueGrain);
            if (capitalized > 0L) {
              capitalizedTotal += capitalized;
              transitions.add(
                  new PilotModel.Transition(
                      tick,
                      PilotModel.TransitionKind.DEBT_CAPITALIZED,
                      h.id,
                      creditorId,
                      0L,
                      0L,
                      0L,
                      "债权人非地主/无地可收，欠额资本化 " + capitalized));
            }
          }
        } else if (PilotModel.MONEY.equals(key.unit)) {
          long desiredMoney = collectValueMilli * config.moneyPerGrain() / 1000L;
          long take = Math.min(h.money, desiredMoney);
          LenderState lender = lenders.get(creditorId);
          if (take > 0L && lender != null) {
            h.money -= take;
            lender.money += take;
            postPayment(h.id, creditorId, PilotModel.MONEY, take);
            remainingDue -= valueMilli(PilotModel.MONEY, take);
            liquidSeizedTotal += take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.LIQUID_SEIZED,
                    h.id,
                    creditorId,
                    0L,
                    0L,
                    take,
                    "货币债到期扣货币"));
          } else {
            long capitalized = Math.min(accountDebt, remainingDue * config.moneyPerGrain() / 1000L);
            if (capitalized > 0L) {
              capitalizedTotal += capitalized;
              transitions.add(
                  new PilotModel.Transition(
                      tick,
                      PilotModel.TransitionKind.DEBT_CAPITALIZED,
                      h.id,
                      creditorId,
                      0L,
                      0L,
                      0L,
                      "无货币可扣，本次应还货币债资本化 " + capitalized));
            }
          }
        }
      }

      h.collectionCooldownUntilTick = tick + config.collectionIntervalTicks();
      for (AccountState state : accounts.values()) {
        if (state.key.ownerId.equals(h.id) && state.cumulativeNet < 0L) {
          state.nextDueTick = tick + config.collectionIntervalTicks();
          state.status = PilotModel.AccountStatus.ACTIVE;
        }
      }
      downgradeIfBelowFloor(h);
    }
  }

  // ── 10) populationFlow：债务压力越高，人口按 flowRate 流入更低阶层家户 ─────────

  private void populationFlow() {
    PilotModel.CollectionPolicy policy = config.collectionPolicy();
    for (HouseholdState h : households.values()) {
      long debtValue = householdDebtValueMilli(h.id);
      if (debtValue <= 0L || h.population <= 1L) {
        continue;
      }
      long pressureMilli =
          debtValue / Math.max(1L, h.population * config.debtPressureUnitPerCapita());
      if (pressureMilli <= 0L) {
        continue;
      }
      long flowRate =
          Math.min(
              policy.maxFlowPerMille(),
              policy.baseFlowPerMille() + pressureMilli * policy.flowSlopePerMille() / 1000L);
      if (flowRate <= 0L) {
        continue;
      }
      HouseholdState destination = chooseLowerDestination(h);
      if (destination == null) {
        tickDiagnostics.add(
            "flow-skip household="
                + h.id
                + " pressureMilli="
                + pressureMilli
                + " flowRate="
                + flowRate
                + " reason=no-lower-class-household");
        continue;
      }
      h.flowRemainderMilli += h.population * flowRate;
      long moved = h.flowRemainderMilli / 1000L;
      h.flowRemainderMilli -= moved * 1000L;
      moved = Math.min(moved, h.population - 1L);
      if (moved <= 0L) {
        tickDiagnostics.add(
            "flow-pending household="
                + h.id
                + " pressureMilli="
                + pressureMilli
                + " flowRate="
                + flowRate
                + " remainderMilli="
                + h.flowRemainderMilli
                + " destination="
                + destination.id);
        continue;
      }
      long populationBefore = h.population;
      transferMigrantLiquidAssets(h, destination, moved, populationBefore);
      h.population -= moved;
      destination.population += moved;
      transitions.add(
          new PilotModel.Transition(
              tick,
              PilotModel.TransitionKind.POPULATION_FLOW,
              h.id,
              destination.id,
              moved,
              0L,
              0L,
              "debtPressureMilli="
                  + pressureMilli
                  + " flowRatePerMille="
                  + flowRate
                  + " debtValueMilli="
                  + debtValue));
    }
  }

  // ── 11) 报告 ─────────────────────────────────────────────────────────────────

  private PilotModel.TickReport buildReport() {
    LinkedHashMap<String, Long> populationByClass = new LinkedHashMap<>();
    LinkedHashMap<String, Long> householdsByClass = new LinkedHashMap<>();
    LinkedHashMap<String, Long> debtByClass = new LinkedHashMap<>();
    LinkedHashMap<String, Long> claimByClass = new LinkedHashMap<>();
    LinkedHashMap<String, Map<String, Long>> goodsByHousehold = new LinkedHashMap<>();
    LinkedHashMap<String, Long> moneyByHousehold = new LinkedHashMap<>();
    LinkedHashMap<String, Long> landByHousehold = new LinkedHashMap<>();
    LinkedHashMap<String, Long> toolsByHousehold = new LinkedHashMap<>();
    LinkedHashMap<String, Long> efficiencyByHousehold = new LinkedHashMap<>();

    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      long population = 0L;
      long householdCount = 0L;
      long debt = 0L;
      long claim = 0L;
      for (HouseholdState h : households.values()) {
        if (h.classPositionId.equals(position.id())) {
          population += h.population;
          householdCount++;
          debt += householdDebtValueMilli(h.id);
          claim += householdClaimValueMilli(h.id);
        }
      }
      populationByClass.put(position.id(), population);
      householdsByClass.put(position.id(), householdCount);
      debtByClass.put(position.id(), debt);
      claimByClass.put(position.id(), claim);
    }
    for (HouseholdState h : households.values()) {
      LinkedHashMap<String, Long> goods = new LinkedHashMap<>(h.goods);
      goodsByHousehold.put(h.id, goods);
      moneyByHousehold.put(h.id, h.money);
      landByHousehold.put(h.id, h.land);
      toolsByHousehold.put(h.id, h.tools);
      efficiencyByHousehold.put(h.id, h.laborEfficiencyPerMille);
    }

    long totalLand = totalLand();
    long landlordLand = 0L;
    long topLand = 0L;
    for (HouseholdState h : households.values()) {
      if (h.classPositionId.equals(PilotModel.LANDLORD_ID)) {
        landlordLand += h.land;
      }
      topLand = Math.max(topLand, h.land);
    }
    long landlordShare = totalLand <= 0L ? 0L : landlordLand * 1000L / totalLand;
    long topShare = totalLand <= 0L ? 0L : topLand * 1000L / totalLand;

    List<PilotModel.Transition> tickTransitions = new ArrayList<>();
    for (PilotModel.Transition transition : transitions) {
      if (transition.tick() == tick) {
        tickTransitions.add(transition);
      }
    }

    return new PilotModel.TickReport(
        tick,
        populationByClass,
        householdsByClass,
        debtByClass,
        claimByClass,
        goodsByHousehold,
        moneyByHousehold,
        landByHousehold,
        toolsByHousehold,
        efficiencyByHousehold,
        lastTickRedLights,
        lastTickBaseGap,
        tickTransitions,
        landlordShare,
        topShare,
        tickDiagnostics);
  }

  // ── 内部辅助 ─────────────────────────────────────────────────────────────────

  private long baseGapOf(HouseholdState h) {
    return pendingBaseGap.getOrDefault(h.id, 0L);
  }

  private long householdDebtValueMilli(String householdId) {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(householdId) && state.cumulativeNet < 0L) {
        total += valueMilli(state.key.unit, -state.cumulativeNet);
      }
    }
    return total;
  }

  private long householdClaimValueMilli(String householdId) {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(householdId) && state.cumulativeNet > 0L) {
        total += valueMilli(state.key.unit, state.cumulativeNet);
      }
    }
    return total;
  }

  private long seizableValueMilli(HouseholdState h) {
    return h.grain() * 1000L
        + h.money * 1000L / config.moneyPerGrain()
        + h.land * config.collectionPolicy().landPricePerUnit() * 1000L
        + h.tools * config.toolPricePerUnit() * 1000L;
  }

  private long valueMilli(String unit, long amount) {
    if (PilotModel.GRAIN.equals(unit)) {
      return amount * 1000L;
    }
    if (PilotModel.MONEY.equals(unit)) {
      return amount * 1000L / config.moneyPerGrain();
    }
    return 0L;
  }

  private HouseholdState chooseLowerDestination(HouseholdState origin) {
    int originRank = classPosition(origin).rank();
    HouseholdState best = null;
    long bestScore = Long.MIN_VALUE;
    for (HouseholdState candidate : households.values()) {
      if (candidate.id.equals(origin.id)) {
        continue;
      }
      if (classPosition(candidate).rank() <= originRank) {
        continue;
      }
      long score =
          (candidate.land * config.collectionPolicy().landPricePerUnit()
                  + candidate.tools * config.toolPricePerUnit())
              * 1000L;
      long scoreWithLiquid =
          candidate.grain() * 1000L + candidate.money * 1000L / config.moneyPerGrain();
      long totalScore = (score + scoreWithLiquid) / Math.max(1L, candidate.population);
      if (best == null
          || totalScore > bestScore
          || (totalScore == bestScore && candidate.id.compareTo(best.id) < 0)) {
        best = candidate;
        bestScore = totalScore;
      }
    }
    return best;
  }

  private void transferMigrantLiquidAssets(
      HouseholdState origin, HouseholdState destination, long moved, long populationBefore) {
    long grainShare = origin.grain() * moved / Math.max(1L, populationBefore);
    long clothShare = origin.cloth() * moved / Math.max(1L, populationBefore);
    long moneyShare = origin.money * moved / Math.max(1L, populationBefore);
    if (grainShare > 0L) {
      transfer(origin, destination, PilotModel.GRAIN, grainShare);
    }
    if (clothShare > 0L) {
      transfer(origin, destination, PilotModel.CLOTH, clothShare);
    }
    if (moneyShare > 0L) {
      origin.money -= moneyShare;
      destination.money += moneyShare;
    }
  }

  private void downgradeIfBelowFloor(HouseholdState h) {
    if (!PilotModel.MIDDLE_PEASANT_ID.equals(h.classPositionId)) {
      return;
    }
    long landPerCapitaMilli = h.land * 1000L / Math.max(1L, h.population);
    if (landPerCapitaMilli < config.middlePeasantFloorLandMilli()) {
      String from = h.classPositionId;
      h.classPositionId = PilotModel.TENANT_ID;
      transitions.add(
          new PilotModel.Transition(
              tick,
              PilotModel.TransitionKind.CLASS_DOWNGRADE,
              h.id,
              "",
              0L,
              0L,
              0L,
              "自有地/人="
                  + landPerCapitaMilli
                  + "‰ 低于中农下限 "
                  + config.middlePeasantFloorLandMilli()
                  + "‰，"
                  + from
                  + "→"
                  + PilotModel.TENANT_ID));
    }
  }

  private long protectedReserveGrain(HouseholdState h) {
    long laborUnits = h.laborUnits();
    return config.reserveTicks()
        * (config.baseRationPerCapita() * h.population + config.laborRationPerLabor() * laborUnits);
  }

  private List<HouseholdState> householdsOfClass(String classPositionId) {
    List<HouseholdState> result = new ArrayList<>();
    for (HouseholdState h : households.values()) {
      if (h.classPositionId.equals(classPositionId)) {
        result.add(h);
      }
    }
    result.sort(BY_RANK_THEN_ID);
    return result;
  }

  private List<HouseholdState> orderedOthers(String excludeId) {
    List<HouseholdState> result = new ArrayList<>();
    for (HouseholdState h : households.values()) {
      if (!h.id.equals(excludeId)) {
        result.add(h);
      }
    }
    result.sort(BY_RANK_THEN_ID);
    return result;
  }

  private LinkedHashMap<String, Long> splitByParticipation(
      long total, List<HouseholdState> members) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    if (total <= 0L || members.isEmpty()) {
      return result;
    }
    long shareSum = 0L;
    for (HouseholdState member : members) {
      shareSum += Math.max(0L, member.participationSharePerMille);
    }
    if (shareSum <= 0L) {
      shareSum = members.size();
      long each = total / shareSum;
      long assigned = 0L;
      for (int i = 0; i < members.size(); i++) {
        long amount = i == members.size() - 1 ? total - assigned : each;
        if (amount > 0L) {
          result.put(members.get(i).id, amount);
        }
        assigned += amount;
      }
      return result;
    }
    long assigned = 0L;
    for (int i = 0; i < members.size(); i++) {
      HouseholdState member = members.get(i);
      long amount =
          i == members.size() - 1
              ? total - assigned
              : total * Math.max(0L, member.participationSharePerMille) / shareSum;
      amount = Math.max(0L, amount);
      if (amount > 0L) {
        result.put(member.id, amount);
      }
      assigned += amount;
    }
    return result;
  }

  private void transfer(HouseholdState from, HouseholdState to, String unit, long amount) {
    if (amount <= 0L) {
      return;
    }
    from.goods.merge(unit, -amount, Long::sum);
    to.goods.merge(unit, amount, Long::sum);
  }

  private void give(HouseholdState to, String unit, long amount) {
    if (amount > 0L) {
      to.goods.merge(unit, amount, Long::sum);
    }
  }

  private void postObligation(
      String debtorId,
      String creditorId,
      String unit,
      long amount,
      String terms,
      long interestRatePerMille,
      long nextDueTick) {
    if (amount <= 0L) {
      return;
    }
    addNet(debtorId, creditorId, unit, -amount, terms, interestRatePerMille, nextDueTick);
    addNet(creditorId, debtorId, unit, amount, terms, interestRatePerMille, nextDueTick);
  }

  private void postPayment(String payerId, String payeeId, String unit, long amount) {
    if (amount <= 0L) {
      return;
    }
    addNet(payerId, payeeId, unit, amount, "payment", 0L, tick + config.collectionIntervalTicks());
    addNet(payeeId, payerId, unit, -amount, "payment", 0L, tick + config.collectionIntervalTicks());
  }

  private void addNet(
      String ownerId,
      String counterpartyId,
      String unit,
      long delta,
      String terms,
      long interestRatePerMille,
      long nextDueTick) {
    if (delta == 0L) {
      return;
    }
    AccountKey key = new AccountKey(ownerId, counterpartyId, unit);
    AccountState state = accounts.get(key);
    if (state == null) {
      state = new AccountState(key, terms, interestRatePerMille, nextDueTick);
      accounts.put(key, state);
    } else if (state.cumulativeNet == 0L) {
      state.nextDueTick = nextDueTick;
      state.status = PilotModel.AccountStatus.ACTIVE;
    }
    state.cumulativeNet += delta;
    if (state.cumulativeNet == 0L) {
      state.status = PilotModel.AccountStatus.SETTLED;
    } else if (state.cumulativeNet < 0L && tick >= state.nextDueTick) {
      state.status = PilotModel.AccountStatus.DUE;
    } else if (state.cumulativeNet > 0L) {
      state.status = PilotModel.AccountStatus.ACTIVE;
    }
  }

  private long sumValues(Map<String, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total += value;
    }
    return total;
  }

  private LinkedHashMap<String, Long> trimTo(Map<String, Long> source, long limit) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    long left = Math.max(0L, limit);
    for (Map.Entry<String, Long> entry : source.entrySet()) {
      if (left <= 0L) {
        break;
      }
      long take = Math.min(left, entry.getValue());
      if (take > 0L) {
        result.put(entry.getKey(), take);
        left -= take;
      }
    }
    return result;
  }

  private void returnTrimmed(
      LinkedHashMap<String, Long> laborRemaining,
      Map<String, Long> allocated,
      Map<String, Long> kept) {
    for (Map.Entry<String, Long> entry : allocated.entrySet()) {
      long keptAmount = kept.getOrDefault(entry.getKey(), 0L);
      long excess = entry.getValue() - keptAmount;
      if (excess > 0L) {
        laborRemaining.merge(entry.getKey(), excess, Long::sum);
      }
    }
  }

  private static boolean isFarmingClass(String classPositionId) {
    return PilotModel.MIDDLE_PEASANT_ID.equals(classPositionId)
        || PilotModel.TENANT_ID.equals(classPositionId);
  }

  private static PilotModel.ClassPosition classPosition(HouseholdState h) {
    return PilotModel.classPosition(h.classPositionId);
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }

  // ── 内部状态类型 ─────────────────────────────────────────────────────────────

  private static final class HouseholdState {
    final String id;
    final String name;
    String classPositionId;
    long population;
    long laborPerCapita;
    final LinkedHashMap<String, Long> goods = new LinkedHashMap<>();
    long money;
    long land;
    long tools;
    final long participationSharePerMille;
    long laborEfficiencyPerMille = 1000L;
    long flowRemainderMilli;
    long collectionCooldownUntilTick;

    HouseholdState(PilotModel.Household spec) {
      id = spec.id();
      name = spec.name();
      classPositionId = spec.classPositionId();
      population = spec.population();
      laborPerCapita = spec.laborPerCapita();
      goods.put(PilotModel.GRAIN, spec.goods().getOrDefault(PilotModel.GRAIN, 0L));
      goods.put(PilotModel.CLOTH, spec.goods().getOrDefault(PilotModel.CLOTH, 0L));
      for (Map.Entry<String, Long> entry : spec.goods().entrySet()) {
        goods.put(entry.getKey(), entry.getValue());
      }
      money = spec.money();
      land = spec.land();
      tools = spec.tools();
      participationSharePerMille = spec.participationSharePerMille();
    }

    long grain() {
      return goods.getOrDefault(PilotModel.GRAIN, 0L);
    }

    long cloth() {
      return goods.getOrDefault(PilotModel.CLOTH, 0L);
    }

    long laborUnits() {
      return population * laborPerCapita / 1000L;
    }

    PilotModel.Household snapshot() {
      return new PilotModel.Household(
          id,
          name,
          classPositionId,
          population,
          laborPerCapita,
          new LinkedHashMap<>(goods),
          money,
          land,
          tools,
          participationSharePerMille);
    }
  }

  private static final class LenderState {
    long money;
    final LinkedHashMap<String, Long> goods = new LinkedHashMap<>();

    LenderState(PilotModel.Lender spec) {
      money = spec.money();
      goods.putAll(spec.goods());
    }
  }

  private static final class AccountKey {
    final String ownerId;
    final String counterpartyId;
    final String unit;

    AccountKey(String ownerId, String counterpartyId, String unit) {
      this.ownerId = ownerId;
      this.counterpartyId = counterpartyId;
      this.unit = unit;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof AccountKey that)) {
        return false;
      }
      return ownerId.equals(that.ownerId)
          && counterpartyId.equals(that.counterpartyId)
          && unit.equals(that.unit);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ownerId, counterpartyId, unit);
    }
  }

  private static final class AccountState {
    final AccountKey key;
    final String terms;
    final long interestRatePerMille;
    long nextDueTick;
    PilotModel.AccountStatus status = PilotModel.AccountStatus.ACTIVE;
    long cumulativeNet;
    long interestAccrued;

    AccountState(AccountKey key, String terms, long interestRatePerMille, long nextDueTick) {
      this.key = key;
      this.terms = terms == null ? "" : terms;
      this.interestRatePerMille = interestRatePerMille;
      this.nextDueTick = nextDueTick;
    }

    PilotModel.HouseholdAccount snapshot(String modeId) {
      long debt = cumulativeNet < 0L ? -cumulativeNet : 0L;
      long claim = cumulativeNet > 0L ? cumulativeNet : 0L;
      return new PilotModel.HouseholdAccount(
          key.ownerId,
          modeId,
          key.counterpartyId,
          key.unit,
          terms,
          cumulativeNet,
          debt,
          claim,
          interestAccrued,
          nextDueTick,
          status);
    }
  }

  private static final class PlanDraft {
    final HouseholdState household;
    long ownedLand;
    long desiredLand;
    long plannedLand;
    long leasedLand;
    long ownLaborUsed;
    long seedRequired;
    long seedSelf;
    long seedExternal;
    long toolsUsed;
    long toolsShortageLand;
    long laborShortageLand;
    long seedShortage;
    long efficiencyPerMille;
    LinkedHashMap<String, Long> externalLaborByProvider = new LinkedHashMap<>();
    LinkedHashMap<String, Long> seedExternalByProvider = new LinkedHashMap<>();

    PlanDraft(HouseholdState household) {
      this.household = household;
      this.efficiencyPerMille = household.laborEfficiencyPerMille;
    }

    PilotModel.ProductionPlan toRecord(long currentTick) {
      long externalLabor = 0L;
      for (long value : externalLaborByProvider.values()) {
        externalLabor += value;
      }
      return new PilotModel.ProductionPlan(
          currentTick,
          household.id,
          household.classPositionId,
          ownedLand,
          leasedLand,
          plannedLand,
          ownLaborUsed,
          externalLabor,
          seedRequired,
          seedSelf,
          seedExternal,
          toolsUsed,
          toolsShortageLand,
          laborShortageLand,
          seedShortage,
          efficiencyPerMille,
          externalLaborByProvider,
          seedExternalByProvider);
    }
  }

  private record PlannedProduction(PilotModel.ProductionPlan plan, long poolGrain) {}

  private record PlannedDistribution(
      PilotModel.ProductionPlan plan,
      long poolGrain,
      LinkedHashMap<String, Long> rentByLandlord,
      LinkedHashMap<String, Long> wageDueByProvider,
      LinkedHashMap<String, Long> seedDueByProvider) {}
}
