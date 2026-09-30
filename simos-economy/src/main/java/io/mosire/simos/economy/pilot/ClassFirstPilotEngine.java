package io.mosire.simos.economy.pilot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 独立最小试点引擎：单 mode（佃农制农业），按"阶层池"推进 "计划 → 投入 → 生产 → 阶层分配 → 家户份额 → 滚动账户 → 消费 → 借贷 → 催收 → 人口流动 → 报告"。
 *
 * <p>★ 池化边界：人口、劳动、库存资产、债务与 A_C/x_C 都由 {@link ClassPool} 持有；家户只是池内的 {@link
 * PilotModel.HouseholdAccount}（人口 + 劳动 + 份额 + 生产子账户），不再持有库存。投入抽取、生产、阶层分配、
 * 家户份额、滚动账户、消费（base+劳动口粮、非必要品只扣效率）、借款顺序、催收、红灯全部保留原语义，只有 "阶层/人口流动"从家户级改为池级 bundle。
 *
 * <p>所有集合都是 {@link LinkedHashMap} 或按固定键排序的 list；没有任何随机数/UUID/时钟；同样的初始夹具必然得到 同样的 360 tick 轨迹。
 *
 * <p>账户是双边的：任何一笔债务/索取权都会同时记到 {@code (债务人, 债权人)} 与 {@code (债权人, 债务人)} 两条账户上， 因此全体账户的 {@code
 * cumulativeNet} 之和恒为 0；正净额只出现在 claim 侧，负净额只出现在 debt 侧。迁移时按人头 比例搬走债务/债权份额（或按 bundle
 * 规则留在原池），同时搬两侧镜像，因此守恒不被打破。
 */
public final class ClassFirstPilotEngine {

  private static final String TERMS_RENT = "rent-arrears";
  private static final String TERMS_INPUT_SEED = "input-seed";
  private static final String TERMS_INPUT_LABOR = "input-labor/wage";
  private static final String TERMS_GRAIN_LOAN = "grain-loan";
  private static final String TERMS_MONEY_LOAN = "money-loan";

  private final PilotConfig config;
  private final MobilityPolicy mobility;
  private final LinkedHashMap<String, ClassPool> pools = new LinkedHashMap<>();
  private final LinkedHashMap<String, HouseholdState> households = new LinkedHashMap<>();
  private final LinkedHashMap<AccountKey, AccountState> accounts = new LinkedHashMap<>();
  private final LinkedHashMap<String, LenderState> lenders = new LinkedHashMap<>();
  private final List<PilotModel.Transition> transitions = new ArrayList<>();
  private final List<PilotModel.MobilityEvent> mobilityEvents = new ArrayList<>();
  private final List<PilotModel.TickReport> reports = new ArrayList<>();
  private final List<PilotModel.ProductionAccount> lastProductionAccounts = new ArrayList<>();
  private final List<PoolPlan> lastPlans = new ArrayList<>();
  private final LinkedHashMap<String, Long> deployedOwnLabor = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> pendingBaseGap = new LinkedHashMap<>();
  private final List<String> tickDiagnostics = new ArrayList<>();

  private long tick;
  private long landForSale;
  private long landMarketEscrowGrain;
  private long landMarketEscrowMoney;
  private long totalLeaseHolding;
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
  private long taxPaidTotal;
  private long lastTickRedLights;
  private long lastTickBaseGap;
  private long initialGrainTotal;
  private long initialClothTotal;
  private long initialHouseholdMoneyTotal;
  private long initialLenderMoneyTotal;
  private long initialPopulationTotal;
  private long initialOwnedLandTotal;
  private long initialToolsTotal;
  private long initialClaimGrainMilli;
  private long stockEnrichmentViolations;

  public ClassFirstPilotEngine(PilotConfig config, List<PilotModel.Household> initialHouseholds) {
    this.config = Objects.requireNonNull(config, "config");
    this.mobility = config.mobilityPolicy();
    Objects.requireNonNull(initialHouseholds, "initialHouseholds");
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      pools.put(position.id(), new ClassPool(config.mode().id(), position.id()));
    }
    for (PilotModel.Household spec : initialHouseholds) {
      PilotModel.classPosition(spec.classPositionId());
      for (String good : spec.goods().keySet()) {
        if (!PilotModel.GRAIN.equals(good) && !PilotModel.CLOTH.equals(good)) {
          throw new IllegalArgumentException(
              "pilot goods only support grain/cloth, got: " + good + " in household " + spec.id());
        }
      }
      if (households.containsKey(spec.id())) {
        throw new IllegalArgumentException("duplicate household id: " + spec.id());
      }
      ClassPool pool = pools.get(spec.classPositionId());
      pool.addStock(AssetKind.GRAIN, spec.grain());
      pool.addStock(AssetKind.CLOTH, spec.cloth());
      pool.addStock(AssetKind.MONEY, spec.money());
      pool.addStock(AssetKind.OWNED_LAND, spec.land());
      pool.addStock(AssetKind.TOOLS, spec.tools());
      HouseholdState state = new HouseholdState(spec, pool);
      households.put(spec.id(), state);
    }
    if (households.isEmpty()) {
      throw new IllegalArgumentException("at least one household is required");
    }
    // 空池也保留一个零人口 seed 账户，迁移进入时人口/劳动有确定的落点。
    for (ClassPool pool : pools.values()) {
      if (householdStatesOf(pool).isEmpty()) {
        PilotModel.Household seed =
            new PilotModel.Household(
                pool.classPositionId() + "-POOL",
                pool.classPositionId() + " seed",
                pool.classPositionId(),
                1L,
                500L,
                Map.of(),
                0L,
                0L,
                0L,
                1000L);
        HouseholdState seedState = new HouseholdState(seed, pool);
        seedState.population = 0L;
        households.put(seed.id(), seedState);
      }
    }
    PilotModel.Lender lenderSpec = config.lender();
    lenders.put(lenderSpec.id(), new LenderState(lenderSpec));
    this.landForSale = Math.max(0L, mobility.initialLandForSale());
    recomputePoolMembers();
    refreshAccountsAndDerived();
    this.initialGrainTotal = totalGrain();
    this.initialClothTotal = totalCloth();
    this.initialHouseholdMoneyTotal = totalHouseholdMoney();
    this.initialLenderMoneyTotal = totalLenderMoney();
    this.initialPopulationTotal = totalPopulation();
    this.initialOwnedLandTotal = totalOwnedLand() + landForSale;
    this.initialToolsTotal = totalTools();
    this.initialClaimGrainMilli = totalAccountClaimMilli();
  }

  // ── 对外只读 API ─────────────────────────────────────────────────────────────

  public long tick() {
    return tick;
  }

  public PilotConfig config() {
    return config;
  }

  public MobilityPolicy mobilityPolicy() {
    return mobility;
  }

  public List<PilotModel.TickReport> reports() {
    return List.copyOf(reports);
  }

  public List<PilotModel.MobilityEvent> mobilityEvents() {
    return List.copyOf(mobilityEvents);
  }

  public List<PilotModel.Transition> transitions() {
    return List.copyOf(transitions);
  }

  public List<PilotModel.ProductionAccount> lastProductionAccounts() {
    return List.copyOf(lastProductionAccounts);
  }

  public List<PilotModel.HouseholdAccount> householdAccounts() {
    List<PilotModel.HouseholdAccount> snapshots = new ArrayList<>();
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      ClassPool pool = pools.get(position.id());
      for (HouseholdState member : householdStatesOf(pool)) {
        snapshots.add(member.snapshot());
      }
    }
    return snapshots;
  }

  public List<PilotModel.RollingAccount> accounts() {
    List<PilotModel.RollingAccount> snapshots = new ArrayList<>();
    for (AccountState state : accounts.values()) {
      snapshots.add(state.snapshot());
    }
    return snapshots;
  }

  public List<PilotModel.RollingAccount> accountsOf(String ownerId) {
    List<PilotModel.RollingAccount> snapshots = new ArrayList<>();
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(ownerId)) {
        snapshots.add(state.snapshot());
      }
    }
    return snapshots;
  }

  public ClassPool poolOf(String classPositionId) {
    return pools.get(classPositionId);
  }

  public long landForSale() {
    return landForSale;
  }

  public long landMarketEscrowGrain() {
    return landMarketEscrowGrain;
  }

  public long landMarketEscrowMoney() {
    return landMarketEscrowMoney;
  }

  public long leaseSupply() {
    return leaseSupplyAvailable();
  }

  public long totalPopulation() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      total += pool.population();
    }
    return total;
  }

  public long totalOwnedLand() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      total += pool.stock(AssetKind.OWNED_LAND);
    }
    return total;
  }

  public long totalTools() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      total += pool.stock(AssetKind.TOOLS);
    }
    return total;
  }

  public long totalGrain() {
    long total = landMarketEscrowGrain;
    for (ClassPool pool : pools.values()) {
      total += pool.stock(AssetKind.GRAIN);
    }
    for (LenderState lender : lenders.values()) {
      total += lender.goods.getOrDefault(PilotModel.GRAIN, 0L);
    }
    return total;
  }

  public long totalCloth() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      total += pool.stock(AssetKind.CLOTH);
    }
    for (LenderState lender : lenders.values()) {
      total += lender.goods.getOrDefault(PilotModel.CLOTH, 0L);
    }
    return total;
  }

  public long totalHouseholdMoney() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      total += pool.stock(AssetKind.MONEY);
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

  public long totalMoney() {
    return totalHouseholdMoney() + totalLenderMoney() + landMarketEscrowMoney;
  }

  public long totalDebtGrainMilli() {
    return totalAccountDebtMilli();
  }

  public long totalClaimGrainMilli() {
    return totalAccountClaimMilli();
  }

  /** 全体账户（含放贷方镜像账户）的 cumulativeNet 之和；双边记账 ⇒ 恒为 0。 */
  public long accountNetSum() {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      total += state.cumulativeNet;
    }
    return total;
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

  public long initialPopulationTotal() {
    return initialPopulationTotal;
  }

  public long initialOwnedLandTotal() {
    return initialOwnedLandTotal;
  }

  public long initialToolsTotal() {
    return initialToolsTotal;
  }

  public long initialClaimGrainMilli() {
    return initialClaimGrainMilli;
  }

  /** 迁移 bundle 后原池库存人均份额不得提高的违例计数（应为 0）。 */
  public long stockEnrichmentViolations() {
    return stockEnrichmentViolations;
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

  public long taxPaidTotal() {
    return taxPaidTotal;
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
    lastPlans.clear();
    lastProductionAccounts.clear();
    pendingBaseGap.clear();
    deployedOwnLabor.clear();

    List<PoolPlan> plans = planAndProcure();
    produce(plans);
    distributeByClass(plans);
    rollAccounts();
    consume();
    borrowIfNeeded();
    dueAndCollect();
    populationFlow();

    PilotModel.TickReport report = buildReport();
    reports.add(report);
    return report;
  }

  // ── 1) plan：本期土地/劳动/工具/种子需求（池级） ────────────────────────────

  private List<PoolPlan> planAndProcure() {
    List<PoolPlan> plans = new ArrayList<>();
    long leaseSupplyForPlan = leaseSupplyAvailable();
    long laborerRemaining = pools.get(PilotModel.LABORER_ID).labor();
    long unmetLaborUnits = 0L;

    List<ClassPool> farming = poolsByTier(true);
    farming.removeIf(
        pool ->
            !PilotModel.MIDDLE_PEASANT_ID.equals(pool.classPositionId())
                && !PilotModel.TENANT_ID.equals(pool.classPositionId()));

    for (ClassPool pool : farming) {
      PoolPlan plan = new PoolPlan(pool);
      plan.ownedLand = pool.stock(AssetKind.OWNED_LAND);
      long toolsCapacity = pool.stock(AssetKind.TOOLS) * config.toolCapacityPerTool();
      long rightBefore = plan.ownedLand + pool.leaseHolding();
      long newLease = 0L;
      if (rightBefore < toolsCapacity) {
        newLease = Math.min(toolsCapacity - rightBefore, Math.max(0L, leaseSupplyForPlan));
        if (newLease > 0L) {
          pool.setLeaseHolding(pool.leaseHolding() + newLease);
          totalLeaseHolding += newLease;
          leaseSupplyForPlan -= newLease;
        }
      }
      plan.leasedLand = pool.leaseHolding();
      long desiredLand = Math.min(plan.ownedLand + plan.leasedLand, toolsCapacity);
      long desiredLabor = desiredLand * config.laborPerLand();
      long ownLabor = pool.labor();
      long ownUsed = Math.min(ownLabor, desiredLabor);
      long externalNeeded = desiredLabor - ownUsed;
      long externalTaken = 0L;
      if (externalNeeded > 0L) {
        externalTaken = Math.min(externalNeeded, laborerRemaining);
        if (externalTaken > 0L) {
          laborerRemaining -= externalTaken;
          plan.externalLaborByProviderPool.put(PilotModel.LABORER_ID, externalTaken);
        }
      }
      long laborLimitedLand = (ownUsed + externalTaken) / Math.max(1L, config.laborPerLand());
      long plannedLand = Math.min(desiredLand, laborLimitedLand);
      plan.laborShortageLand = desiredLand - plannedLand;
      unmetLaborUnits += Math.max(0L, desiredLand - plannedLand) * config.laborPerLand();

      // 种子：先自给，再向高于保护储备的池借；仍不够就按缺口缩地。
      long seedSelf = Math.min(pool.stock(AssetKind.GRAIN), plannedLand * config.seedPerLand());
      long seedExternal = 0L;
      if (seedSelf < plannedLand * config.seedPerLand()) {
        long needed = plannedLand * config.seedPerLand() - seedSelf;
        for (ClassPool provider : poolsByTier(true)) {
          if (needed <= 0L) {
            break;
          }
          if (provider.classPositionId().equals(pool.classPositionId())) {
            continue;
          }
          long lendable =
              Math.max(0L, provider.stock(AssetKind.GRAIN) - protectedReserveGrain(provider));
          long take = Math.min(needed, lendable);
          if (take > 0L) {
            plan.seedExternalByProviderPool.merge(provider.classPositionId(), take, Long::sum);
            seedExternal += take;
            needed -= take;
          }
        }
      }
      long seedAvailable = seedSelf + seedExternal;
      long seedLimitedLand = seedAvailable / Math.max(1L, config.seedPerLand());
      if (seedLimitedLand < plannedLand) {
        plannedLand = seedLimitedLand;
        long neededLabor = plannedLand * config.laborPerLand();
        ownUsed = Math.min(ownLabor, neededLabor);
        long externalNeededAfterSeed = neededLabor - ownUsed;
        LinkedHashMap<String, Long> trimmedLabor =
            trimToMap(plan.externalLaborByProviderPool, externalNeededAfterSeed);
        long returned = sumValues(plan.externalLaborByProviderPool) - sumValues(trimmedLabor);
        if (returned > 0L) {
          laborerRemaining += returned;
        }
        plan.externalLaborByProviderPool = trimmedLabor;
        seedSelf = Math.min(pool.stock(AssetKind.GRAIN), plannedLand * config.seedPerLand());
        long seedNeeded = plannedLand * config.seedPerLand() - seedSelf;
        plan.seedExternalByProviderPool =
            trimToMap(plan.seedExternalByProviderPool, Math.max(0L, seedNeeded));
        seedExternal = sumValues(plan.seedExternalByProviderPool);
      } else {
        plan.seedExternalByProviderPool = trimToMap(plan.seedExternalByProviderPool, seedExternal);
      }
      plan.desiredLand = desiredLand;
      plan.plannedLand = plannedLand;
      plan.ownLaborUsed = ownUsed;
      plan.externalLaborUsed = sumValues(plan.externalLaborByProviderPool);
      plan.seedRequired = plannedLand * config.seedPerLand();
      plan.seedSelf = Math.min(pool.stock(AssetKind.GRAIN), plan.seedRequired);
      plan.seedExternal = sumValues(plan.seedExternalByProviderPool);
      plan.toolsUsed =
          Math.min(
              pool.stock(AssetKind.TOOLS),
              ceilDiv(plannedLand, Math.max(1L, config.toolCapacityPerTool())));
      plan.toolsShortageLand =
          Math.max(
              0L,
              plan.ownedLand
                  + plan.leasedLand
                  - pool.stock(AssetKind.TOOLS) * config.toolCapacityPerTool());
      plan.seedShortage = Math.max(0L, plan.seedRequired - plan.seedSelf - plan.seedExternal);
      plan.laborShortageLand = Math.max(0L, plan.desiredLand - plan.plannedLand);

      // 实际扣种子：自给从池库存出；外部种子从提供者库存出，提供者拿到 claim。
      if (plan.seedSelf > 0L) {
        pool.takeStock(AssetKind.GRAIN, plan.seedSelf);
      }
      for (Map.Entry<String, Long> entry : plan.seedExternalByProviderPool.entrySet()) {
        ClassPool provider = pools.get(entry.getKey());
        long taken = provider.takeStock(AssetKind.GRAIN, entry.getValue());
        postObligation(
            pool.classPositionId(),
            provider.classPositionId(),
            PilotModel.GRAIN,
            taken,
            TERMS_INPUT_SEED,
            config.loanInterestRatePerMille(),
            tick + config.collectionIntervalTicks());
      }
      seedUsedTotal += plan.seedSelf + plan.seedExternal;

      // 劳动用外部提供 ⇒ 生产账户欠提供者工资；分配时按"地租→工资/给养→种子→残值"优先偿付。
      long externalLabor = sumValues(plan.externalLaborByProviderPool);
      if (externalLabor > 0L) {
        long wage = externalLabor * config.wagePerLabor();
        for (Map.Entry<String, Long> entry : plan.externalLaborByProviderPool.entrySet()) {
          long part = entry.getValue() * config.wagePerLabor();
          postObligation(
              pool.classPositionId(),
              entry.getKey(),
              PilotModel.GRAIN,
              part,
              TERMS_INPUT_LABOR,
              config.loanInterestRatePerMille(),
              tick + config.collectionIntervalTicks());
        }
        plan.wageDue = wage;
      }
      plan.leasedLand = Math.max(0L, plan.plannedLand - Math.min(plan.ownedLand, plan.plannedLand));
      pool.setOperatedLand(plan.plannedLand);
      deployedOwnLabor.put(pool.classPositionId(), plan.ownLaborUsed);
      plans.add(plan);
    }
    lastPlans.clear();
    lastPlans.addAll(plans);
    tickDiagnostics.add(
        "plan unmetLaborUnits="
            + unmetLaborUnits
            + " laborerRemaining="
            + laborerRemaining
            + " leaseSupplyAfterPlan="
            + leaseSupplyForPlan);
    return plans;
  }

  // ── 2) produce：粮产出 ───────────────────────────────────────────────────────

  private void produce(List<PoolPlan> plans) {
    for (PoolPlan plan : plans) {
      ClassPool pool = plan.pool;
      long output =
          plan.plannedLand * config.yieldPerLand() * pool.laborEfficiencyPerMille() / 1000L;
      plan.outputGrain = output;
      producedGrainTotal += output;
      pool.addStock(AssetKind.GRAIN, output);
      if (plan.seedShortage > 0L || plan.laborShortageLand > 0L || plan.toolsShortageLand > 0L) {
        tickDiagnostics.add(
            "produce shortage pool="
                + pool.classPositionId()
                + " seedShortage="
                + plan.seedShortage
                + " laborShortageLand="
                + plan.laborShortageLand
                + " toolsShortageLand="
                + plan.toolsShortageLand);
      }
    }
  }

  // ── 3) distributeByClass：先地租、工资/给养、种子、残值；先到阶层位置 ─────────

  private void distributeByClass(List<PoolPlan> plans) {
    for (PoolPlan plan : plans) {
      ClassPool operator = plan.pool;
      PilotModel.ClassRule rule = config.mode().ruleFor(operator.classPositionId());
      long budget = plan.outputGrain;
      long operatedLeasedLand =
          Math.max(0L, plan.plannedLand - Math.min(plan.ownedLand, plan.plannedLand));
      long rentDue = operatedLeasedLand * config.rentPerLand() * rule.rentSharePerMille() / 1000L;
      long wageDue = sumValues(plan.externalLaborByProviderPool) * config.wagePerLabor();
      long seedDue = sumValues(plan.seedExternalByProviderPool);
      plan.rentDue = rentDue;
      plan.wageDue = wageDue;
      plan.seedDue = seedDue;

      LinkedHashMap<String, Long> rentByLandlord =
          splitByPool(rentDue, pools.get(PilotModel.LANDLORD_ID));
      for (Map.Entry<String, Long> entry : rentByLandlord.entrySet()) {
        postObligation(
            operator.classPositionId(),
            entry.getKey(),
            PilotModel.GRAIN,
            entry.getValue(),
            TERMS_RENT,
            config.loanInterestRatePerMille(),
            tick + config.collectionIntervalTicks());
      }

      long remaining = budget;
      long rentPaid = 0L;
      for (Map.Entry<String, Long> entry : rentByLandlord.entrySet()) {
        ClassPool landlord = pools.get(entry.getKey());
        long pay = Math.min(remaining, Math.min(entry.getValue(), operator.stock(AssetKind.GRAIN)));
        if (pay > 0L) {
          operator.takeStock(AssetKind.GRAIN, pay);
          landlord.addStock(AssetKind.GRAIN, pay);
          postPayment(
              operator.classPositionId(), landlord.classPositionId(), PilotModel.GRAIN, pay);
          remaining -= pay;
          rentPaid += pay;
          rentPaidTotal += pay;
        }
      }

      long wagePaid = 0L;
      if (wageDue > 0L) {
        ClassPool laborerPool = pools.get(PilotModel.LABORER_ID);
        long pay = Math.min(remaining, Math.min(wageDue, operator.stock(AssetKind.GRAIN)));
        if (pay > 0L) {
          operator.takeStock(AssetKind.GRAIN, pay);
          laborerPool.addStock(AssetKind.GRAIN, pay);
          postPayment(
              operator.classPositionId(), laborerPool.classPositionId(), PilotModel.GRAIN, pay);
          remaining -= pay;
          wagePaid += pay;
          wagePaidTotal += pay;
        }
      }

      long seedPaid = 0L;
      for (Map.Entry<String, Long> entry : plan.seedExternalByProviderPool.entrySet()) {
        long pay = Math.min(remaining, Math.min(entry.getValue(), operator.stock(AssetKind.GRAIN)));
        if (pay > 0L) {
          operator.takeStock(AssetKind.GRAIN, pay);
          pools.get(entry.getKey()).addStock(AssetKind.GRAIN, pay);
          postPayment(operator.classPositionId(), entry.getKey(), PilotModel.GRAIN, pay);
          remaining -= pay;
          seedPaid += pay;
          externalSeedPaidTotal += pay;
        }
      }

      long tax = remaining * Math.max(0L, mobility.extractionTaxPerMille()) / 1000L;
      if (tax > 0L) {
        LinkedHashMap<String, Long> taxByLandlord =
            splitByPool(tax, pools.get(PilotModel.LANDLORD_ID));
        for (Map.Entry<String, Long> entry : taxByLandlord.entrySet()) {
          ClassPool landlord = pools.get(entry.getKey());
          long pay = Math.min(operator.stock(AssetKind.GRAIN), entry.getValue());
          if (pay > 0L) {
            operator.takeStock(AssetKind.GRAIN, pay);
            landlord.addStock(AssetKind.GRAIN, pay);
            postPayment(
                operator.classPositionId(), landlord.classPositionId(), PilotModel.GRAIN, pay);
            remaining -= pay;
            taxPaidTotal += pay;
          }
        }
      }

      long residualPaid = Math.max(0L, remaining);
      residualPaidTotal += residualPaid;

      LinkedHashMap<String, Long> distributedByClass = new LinkedHashMap<>();
      if (rentPaid > 0L) {
        distributedByClass.merge(PilotModel.LANDLORD_ID, rentPaid, Long::sum);
      }
      if (wagePaid > 0L) {
        distributedByClass.merge(PilotModel.LABORER_ID, wagePaid, Long::sum);
      }
      if (residualPaid > 0L) {
        distributedByClass.merge(
            config.mode().ruleFor(operator.classPositionId()).residualToClassPositionId(),
            residualPaid,
            Long::sum);
      }

      LinkedHashMap<String, Long> householdShares = splitByHousehold(residualPaid, operator);
      List<String> shortages = new ArrayList<>();
      if (plan.seedShortage > 0L) {
        shortages.add("seedShortage=" + plan.seedShortage);
      }
      if (plan.laborShortageLand > 0L) {
        shortages.add("laborShortageLand=" + plan.laborShortageLand);
      }
      if (plan.toolsShortageLand > 0L) {
        shortages.add("toolsShortageLand=" + plan.toolsShortageLand);
      }
      lastProductionAccounts.add(
          new PilotModel.ProductionAccount(
              tick,
              operator.classPositionId(),
              operator.classPositionId(),
              plan.plannedLand,
              plan.plannedLand,
              plan.outputGrain,
              plan.seedSelf,
              plan.seedExternal,
              seedPaid,
              rentPaid,
              wagePaid,
              residualPaid,
              distributedByClass,
              householdShares,
              shortages));
    }
  }

  // ── 4) rollAccounts：正净额 claim、负净额 debt；利息只加 debt；跨 tick 累积 ─────

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

  // ── 5) consume：baseRation 全体 + laborRation 实际出劳动者；缺非必要品扣效率 ──

  private void consume() {
    for (ClassPool pool : pools.values()) {
      long baseNeed = config.baseRationPerCapita() * pool.population();
      long ownLabor = deployedOwnLabor.getOrDefault(pool.classPositionId(), 0L);
      long laborNeed = ownLabor * config.laborRationPerLabor();
      long totalNeed = baseNeed + laborNeed;
      long eaten = Math.min(pool.stock(AssetKind.GRAIN), totalNeed);
      long baseEaten = Math.min(eaten, baseNeed);
      long baseGap = baseNeed - baseEaten;
      if (eaten > 0L) {
        pool.takeStock(AssetKind.GRAIN, eaten);
        rationConsumedTotal += eaten;
      }
      lastTickBaseGap += baseGap;
      pendingBaseGap.put(pool.classPositionId(), baseGap);
      if (baseGap > 0L) {
        tickDiagnostics.add(
            "consume baseGap pool="
                + pool.classPositionId()
                + " baseNeed="
                + baseNeed
                + " eaten="
                + eaten
                + " gap="
                + baseGap);
      }

      long clothNeed = pool.population() * config.nonEssentialNeedPerMille() / 1000L;
      long clothEaten = Math.min(pool.stock(AssetKind.CLOTH), clothNeed);
      if (clothEaten > 0L) {
        pool.takeStock(AssetKind.CLOTH, clothEaten);
        clothConsumedTotal += clothEaten;
      }
      long coverage = clothNeed <= 0L ? 1000L : clothEaten * 1000L / clothNeed;
      long penalty = (1000L - coverage) * config.nonEssentialEfficiencyPenaltyPerMille() / 1000L;
      pool.setLaborEfficiencyPerMille(Math.max(300L, 1000L - penalty));
    }
  }

  // ── 6) borrowIfNeeded：借商品 → 借钱买 → 红灯 ────────────────────────────────

  private void borrowIfNeeded() {
    for (ClassPool pool : pools.values()) {
      long gap = Math.max(0L, pendingBaseGap.getOrDefault(pool.classPositionId(), 0L));
      if (gap <= 0L) {
        continue;
      }
      long originalGap = gap;

      long borrowedGoods = 0L;
      long internalGrainLendable = 0L;
      for (ClassPool lender : poolsByTier(true)) {
        if (lender.classPositionId().equals(pool.classPositionId())) {
          continue;
        }
        internalGrainLendable +=
            Math.max(0L, lender.stock(AssetKind.GRAIN) - protectedReserveGrain(lender));
      }
      if (gap > 0L) {
        for (ClassPool lender : poolsByTier(true)) {
          if (gap <= 0L) {
            break;
          }
          if (lender.classPositionId().equals(pool.classPositionId())) {
            continue;
          }
          long lendable =
              Math.max(0L, lender.stock(AssetKind.GRAIN) - protectedReserveGrain(lender));
          long take = Math.min(gap, lendable);
          if (take > 0L) {
            lender.takeStock(AssetKind.GRAIN, take);
            pool.addStock(AssetKind.GRAIN, take);
            postObligation(
                pool.classPositionId(),
                lender.classPositionId(),
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
                    pool.classPositionId(),
                    lender.classPositionId(),
                    0L,
                    0L,
                    take,
                    "baseRationGap=" + originalGap));
          }
        }
      }

      LenderState lender = lenders.values().iterator().next();
      long moneyBorrowed = 0L;
      if (gap > 0L) {
        long moneyNeeded = gap * config.moneyPerGrain();
        long lend = Math.min(moneyNeeded, lender.money);
        if (lend > 0L) {
          lender.money -= lend;
          pool.addStock(AssetKind.MONEY, lend);
          postObligation(
              pool.classPositionId(),
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
                  pool.classPositionId(),
                  config.lender().id(),
                  0L,
                  0L,
                  lend,
                  "baseRationGap=" + originalGap));
        }
      }

      long bought = 0L;
      if (gap > 0L && pool.stock(AssetKind.MONEY) > 0L) {
        for (ClassPool seller : poolsByTier(true)) {
          if (gap <= 0L) {
            break;
          }
          if (seller.classPositionId().equals(pool.classPositionId())) {
            continue;
          }
          long sellable =
              Math.max(0L, seller.stock(AssetKind.GRAIN) - protectedReserveGrain(seller));
          long affordable = pool.stock(AssetKind.MONEY) / config.moneyPerGrain();
          long take = Math.min(Math.min(gap, sellable), affordable);
          if (take > 0L) {
            long cost = take * config.moneyPerGrain();
            seller.takeStock(AssetKind.GRAIN, take);
            pool.addStock(AssetKind.GRAIN, take);
            pool.takeStock(AssetKind.MONEY, cost);
            seller.addStock(AssetKind.MONEY, cost);
            bought += take;
            boughtGrainTotal += take;
            gap -= take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.BUY_GRAIN,
                    pool.classPositionId(),
                    seller.classPositionId(),
                    0L,
                    0L,
                    cost,
                    "grain=" + take + " money=" + cost));
          }
        }
      }

      long gapBeforeFill = gap;
      long eat = Math.min(gapBeforeFill, pool.stock(AssetKind.GRAIN));
      if (eat > 0L) {
        pool.takeStock(AssetKind.GRAIN, eat);
        rationConsumedTotal += eat;
        lastTickBaseGap = Math.max(0L, lastTickBaseGap - eat);
      }
      gap = gapBeforeFill - eat;
      long filled = originalGap - gap;
      pendingBaseGap.put(pool.classPositionId(), gap);
      if (filled > 0L) {
        tickDiagnostics.add(
            "borrow-cover pool="
                + pool.classPositionId()
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
                + internalGrainLendable);
      }
      if (gap > 0L) {
        lastTickRedLights++;
        redLightTotal++;
        tickDiagnostics.add(
            "red-light pool="
                + pool.classPositionId()
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
                + " lenderMoney="
                + lender.money);
        transitions.add(
            new PilotModel.Transition(
                tick,
                PilotModel.TransitionKind.RED_LIGHT,
                pool.classPositionId(),
                "",
                0L,
                0L,
                0L,
                "仍缺基础口粮 " + gap));
      }
    }
  }

  // ── 7) dueAndCollect：只有到期/阈值/压力才催收；先流动商品，再按地主定价收地 ──

  private void dueAndCollect() {
    PilotModel.CollectionPolicy policy = config.collectionPolicy();
    for (ClassPool pool : pools.values()) {
      long debtValue = poolDebtValueMilli(pool);
      if (debtValue <= 0L) {
        pool.setCollectionCooldownUntilTick(Math.max(pool.collectionCooldownUntilTick(), tick));
        continue;
      }
      boolean due = false;
      long minDueTick = Long.MAX_VALUE;
      for (AccountState state : accounts.values()) {
        if (state.key.ownerId.equals(pool.classPositionId()) && state.cumulativeNet < 0L) {
          minDueTick = Math.min(minDueTick, state.nextDueTick);
          if (tick >= state.nextDueTick) {
            due = true;
          }
        }
      }
      boolean threshold = debtValue >= policy.collectionThreshold() * 1000L;
      long seizableValue = seizableValueMilli(pool);
      boolean pressure =
          seizableValue > 0L
              && debtValue * 1000L >= policy.collectionTriggerRatioPerMille() * seizableValue;
      if (!due && !threshold && !pressure) {
        tickDiagnostics.add(
            "collect-skip pool="
                + pool.classPositionId()
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
      if (tick < pool.collectionCooldownUntilTick()) {
        tickDiagnostics.add(
            "collect-cooldown pool="
                + pool.classPositionId()
                + " debtValueMilli="
                + debtValue
                + " cooldownUntil="
                + pool.collectionCooldownUntilTick()
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
        if (state.key.ownerId.equals(pool.classPositionId()) && state.cumulativeNet < 0L) {
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
        ClassPool creditor = pools.get(creditorId);
        long accountDebt = -state.cumulativeNet;
        long accountValueMilli = valueMilli(key.unit, accountDebt);
        long collectValueMilli = Math.min(accountValueMilli, remainingDue);
        if (PilotModel.GRAIN.equals(key.unit)) {
          long desiredGrain = Math.max(1L, collectValueMilli / 1000L);
          long availableGrain =
              Math.max(0L, pool.stock(AssetKind.GRAIN) - protectedReserveGrain(pool));
          long take = Math.min(availableGrain, desiredGrain);
          if (take > 0L && creditor != null) {
            pool.takeStock(AssetKind.GRAIN, take);
            creditor.addStock(AssetKind.GRAIN, take);
            postPayment(pool.classPositionId(), creditorId, PilotModel.GRAIN, take);
            remainingDue -= take * 1000L;
            liquidSeizedTotal += take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.LIQUID_SEIZED,
                    pool.classPositionId(),
                    creditorId,
                    0L,
                    0L,
                    take,
                    "优先扣流动粮（保留保护储备）"));
          }
          long accountRemaining = -accounts.get(key).cumulativeNet;
          boolean creditorIsCollector =
              creditor != null
                  && creditor.classPositionId().equals(policy.collectorClassPositionId());
          long dueGrain = Math.max(0L, remainingDue / 1000L);
          if (accountRemaining > 0L && creditorIsCollector) {
            long seizableGrain = Math.min(accountRemaining, dueGrain);
            long landSeized =
                Math.min(
                    pool.stock(AssetKind.OWNED_LAND),
                    seizableGrain / Math.max(1L, policy.landPricePerUnit()));
            if (landSeized > 0L) {
              long debtReduced = landSeized * policy.landPricePerUnit();
              pool.takeStock(AssetKind.OWNED_LAND, landSeized);
              creditor.addStock(AssetKind.OWNED_LAND, landSeized);
              postPayment(pool.classPositionId(), creditorId, PilotModel.GRAIN, debtReduced);
              remainingDue = Math.max(0L, remainingDue - debtReduced * 1000L);
              landSeizedTotal += landSeized;
              transitions.add(
                  new PilotModel.Transition(
                      tick,
                      PilotModel.TransitionKind.LAND_SEIZED,
                      pool.classPositionId(),
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
                        pool.classPositionId(),
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
                      pool.classPositionId(),
                      creditorId,
                      0L,
                      0L,
                      0L,
                      "债权人非地主/无地可收，欠额资本化 " + capitalized));
            }
          }
        } else if (PilotModel.MONEY.equals(key.unit)) {
          long desiredMoney = collectValueMilli * config.moneyPerGrain() / 1000L;
          long take = Math.min(pool.stock(AssetKind.MONEY), desiredMoney);
          LenderState lender = lenders.get(creditorId);
          if (take > 0L && lender != null) {
            pool.takeStock(AssetKind.MONEY, take);
            lender.money += take;
            postPayment(pool.classPositionId(), creditorId, PilotModel.MONEY, take);
            remainingDue -= valueMilli(PilotModel.MONEY, take);
            liquidSeizedTotal += take;
            transitions.add(
                new PilotModel.Transition(
                    tick,
                    PilotModel.TransitionKind.LIQUID_SEIZED,
                    pool.classPositionId(),
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
                      pool.classPositionId(),
                      creditorId,
                      0L,
                      0L,
                      0L,
                      "无货币可扣，本次应还货币债资本化 " + capitalized));
            }
          }
        }
      }

      pool.setCollectionCooldownUntilTick(tick + config.collectionIntervalTicks());
      for (AccountState state : accounts.values()) {
        if (state.key.ownerId.equals(pool.classPositionId()) && state.cumulativeNet < 0L) {
          state.nextDueTick = tick + config.collectionIntervalTicks();
          state.status = PilotModel.AccountStatus.ACTIVE;
        }
      }
      refreshAccountsAndDerived();
    }
  }

  // ── 8) populationFlow：池级 A/x/r × 真实机会 × cap ⇒ bundle 迁移 ─────────────

  private void populationFlow() {
    long unmetLaborUnits =
        lastPlans.stream().mapToLong(plan -> plan.laborShortageLand * config.laborPerLand()).sum();
    // 先下行（高 tier → 低 tier）：中农下放的土地先进 LandForSale，供随后佃农上行购买。
    for (ClassPool pool : poolsByTier(true)) {
      if (pool.tier() <= 0) {
        continue;
      }
      applyDirection(pool, PilotModel.Direction.DOWN, unmetLaborUnits);
    }
    // 再上行（低 tier → 高 tier）：佃农买同一批 LandForSale，同一 tick 可见双向流动。
    for (ClassPool pool : poolsByTier(false)) {
      if (pool.tier() >= 3) {
        continue;
      }
      applyDirection(pool, PilotModel.Direction.UP, unmetLaborUnits);
    }
    refreshAccountsAndDerived();
  }

  private void applyDirection(
      ClassPool origin, PilotModel.Direction direction, long unmetLaborUnits) {
    if (origin.population() <= 0L) {
      return;
    }
    long aMilli = mobility.schema().aMilli(origin, config.moneyPerGrain());
    long xMilli = mobility.bounds().xMilli(origin.classPositionId(), aMilli);
    long rate =
        direction == PilotModel.Direction.UP
            ? mobility.rateUpPerMillePerYear(xMilli)
            : mobility.rateDownPerMillePerYear(xMilli);
    long requestedMilli = mobility.flowPerTickMilli(origin.population(), rate);
    long capPerMille =
        direction == PilotModel.Direction.UP
            ? mobility.upCapPerMillePerTick()
            : mobility.downCapPerMillePerTick();
    long capMilli = origin.population() * capPerMille;

    ClassPool destination = destinationFor(origin, direction);
    boolean skipLevel = false;
    if (destination == null
        || mobility.templateFor(origin.classPositionId(), destination.classPositionId()) == null) {
      tickDiagnostics.add(
          "mobility-skip pool="
              + origin.classPositionId()
              + " dir="
              + direction
              + " reason=no-edge-template A="
              + aMilli
              + " x="
              + xMilli
              + " r="
              + rate);
      return;
    }
    String destinationClass = destination.classPositionId();
    Opportunity opportunity =
        opportunityTo(destinationClass, requestedMilli, origin, unmetLaborUnits);
    long flowMilli = requestedMilli * opportunity.opportunityPerMille / 1000L;
    if (mobility.absorptionPolicy() == MobilityPolicy.AbsorptionPolicy.ALL_OR_NOTHING
        && opportunity.supplyBelowDemand) {
      flowMilli = 0L;
    }
    long cappedMilli = Math.min(flowMilli, capMilli);

    // 直接目的地吸不动时允许跳一级（人口不消失，也不硬塞）。
    if (cappedMilli <= 0L && requestedMilli > 0L && origin.population() > 0L) {
      ClassPool skipDestination = skipDestinationFor(origin, direction);
      if (skipDestination != null
          && !skipDestination.classPositionId().equals(destination.classPositionId())) {
        Opportunity skipOpportunity =
            opportunityTo(
                skipDestination.classPositionId(), requestedMilli, origin, unmetLaborUnits);
        long skipFlow = requestedMilli * skipOpportunity.opportunityPerMille / 1000L;
        long skipCapped = Math.min(skipFlow, capMilli);
        if (skipCapped > 0L) {
          destination = skipDestination;
          destinationClass = destination.classPositionId();
          opportunity = skipOpportunity;
          flowMilli = skipFlow;
          cappedMilli = skipCapped;
          skipLevel = true;
        }
      }
    }

    long remainder =
        (direction == PilotModel.Direction.UP
                ? origin.flowUpRemainderMilli()
                : origin.flowDownRemainderMilli())
            + cappedMilli;
    long movedPeople = remainder / 1000L;
    remainder %= 1000L;
    if (direction == PilotModel.Direction.UP) {
      origin.setFlowUpRemainderMilli(remainder);
    } else {
      origin.setFlowDownRemainderMilli(remainder);
    }
    movedPeople = Math.min(movedPeople, origin.population());

    if (movedPeople <= 0L) {
      tickDiagnostics.add(
          "mobility-blocked pool="
              + origin.classPositionId()
              + " dir="
              + direction
              + " dest="
              + destinationClass
              + " A="
              + aMilli
              + " x="
              + xMilli
              + " r="
              + rate
              + " requestedMilli="
              + requestedMilli
              + " O="
              + opportunity.opportunityPerMille
              + " capPerMille="
              + capPerMille
              + " capMilli="
              + capMilli
              + " flowMilli="
              + flowMilli
              + " remainderMilli="
              + remainder
              + " supply="
              + opportunity.supply
              + " demand="
              + opportunity.demand
              + " skipLevel="
              + skipLevel
              + " template="
              + (mobility.templateFor(origin.classPositionId(), destinationClass) != null
                  ? mobility.templateFor(origin.classPositionId(), destinationClass)
                  : fallbackTemplate(origin, destination)));
      return;
    }

    PilotModel.MobilityEvent event =
        applyMigration(
            origin,
            destination,
            direction,
            movedPeople,
            aMilli,
            xMilli,
            rate,
            opportunity,
            capMilli,
            skipLevel);
    if (event != null) {
      mobilityEvents.add(event);
      transitions.add(
          new PilotModel.Transition(
              tick,
              PilotModel.TransitionKind.POPULATION_FLOW,
              origin.classPositionId(),
              destination.classPositionId(),
              movedPeople,
              0L,
              0L,
              "mobility "
                  + direction
                  + " A="
                  + aMilli
                  + " x="
                  + xMilli
                  + " r="
                  + rate
                  + " O="
                  + opportunity.opportunityPerMille));
    }
  }

  private PilotModel.MobilityEvent applyMigration(
      ClassPool origin,
      ClassPool destination,
      PilotModel.Direction direction,
      long movedPeople,
      long aMilli,
      long xMilli,
      long rate,
      Opportunity opportunity,
      long capMilli,
      boolean skipLevel) {
    long beforePopulation = origin.population();
    if (beforePopulation <= 0L || movedPeople <= 0L) {
      return null;
    }
    movedPeople = Math.min(movedPeople, beforePopulation);
    MobilityPolicy.BundleTemplate template =
        mobility.templateFor(origin.classPositionId(), destination.classPositionId());
    if (template == null) {
      template = fallbackTemplate(origin, destination);
    }

    List<AssetKind> stockKinds =
        List.of(
            AssetKind.OWNED_LAND,
            AssetKind.TOOLS,
            AssetKind.GRAIN,
            AssetKind.CLOTH,
            AssetKind.MONEY,
            AssetKind.LEASE_SECURITY);
    long[] beforeStocks = new long[stockKinds.size()];
    for (int i = 0; i < stockKinds.size(); i++) {
      AssetKind kind = stockKinds.get(i);
      beforeStocks[i] =
          kind == AssetKind.LEASE_SECURITY ? origin.leaseHolding() : origin.stock(kind);
    }

    LinkedHashMap<AssetKind, Long> movedAssets = new LinkedHashMap<>();
    long landToDestination = 0L;
    long landToMarket = 0L;
    for (AssetKind kind :
        List.of(
            AssetKind.GRAIN,
            AssetKind.CLOTH,
            AssetKind.MONEY,
            AssetKind.TOOLS,
            AssetKind.OWNED_LAND)) {
      long sharePerMille = shareFor(template, kind);
      if (sharePerMille <= 0L) {
        continue;
      }
      // 用 ceil 取人均份额：宁可让原池少留 1 单位，也不让"人口减少"把原池人均状态抬高。
      long wanted =
          proportionalShareCeil(origin.stock(kind), movedPeople, beforePopulation, sharePerMille);
      long taken = origin.takeStock(kind, wanted);
      if (taken <= 0L) {
        continue;
      }
      if (kind == AssetKind.OWNED_LAND) {
        if (template.landToMarket()) {
          landForSale += taken;
          landToMarket += taken;
        } else {
          destination.addStock(kind, taken);
          landToDestination += taken;
        }
      } else {
        destination.addStock(kind, taken);
      }
      movedAssets.put(kind, taken);
    }

    long leaseGranted = 0L;
    long leaseReturned = 0L;
    if (template.returnsLease()) {
      leaseReturned =
          Math.min(
              origin.leaseHolding(),
              proportionalShareCeil(origin.leaseHolding(), movedPeople, beforePopulation, 1000L));
      if (leaseReturned > 0L) {
        origin.setLeaseHolding(origin.leaseHolding() - leaseReturned);
        totalLeaseHolding = Math.max(0L, totalLeaseHolding - leaseReturned);
      }
    }
    if (template.takesLease()) {
      long requestedLease = movedPeople * mobility.leasePerCapitaMilli() / 1000L;
      leaseGranted = Math.min(requestedLease, leaseSupplyAvailable());
      if (leaseGranted > 0L) {
        destination.setLeaseHolding(destination.leaseHolding() + leaseGranted);
        totalLeaseHolding += leaseGranted;
      }
    }

    long landBought = 0L;
    if (template.buysLand()) {
      long desiredLand = movedPeople * mobility.landPurchasePerCapitaMilli() / 1000L;
      long price = config.collectionPolicy().landPricePerUnit();
      long capacityGrain =
          destination.stock(AssetKind.GRAIN)
              + destination.stock(AssetKind.MONEY) / Math.max(1L, config.moneyPerGrain());
      long affordable = capacityGrain / Math.max(1L, price);
      long buy = Math.min(Math.min(desiredLand, landForSale), affordable);
      if (buy > 0L) {
        long cost = buy * price;
        long grainPart = Math.min(destination.stock(AssetKind.GRAIN), cost);
        long remainingCost = cost - grainPart;
        long moneyPart =
            Math.min(destination.stock(AssetKind.MONEY), remainingCost * config.moneyPerGrain());
        destination.takeStock(AssetKind.GRAIN, grainPart);
        destination.takeStock(AssetKind.MONEY, moneyPart);
        destination.addStock(AssetKind.OWNED_LAND, buy);
        landForSale -= buy;
        landBought = buy;
        payLandSaleProceeds(grainPart, moneyPart);
      }
    }

    long[] accountMovement =
        moveAccountShare(
            origin,
            destination,
            movedPeople,
            beforePopulation,
            template.movesDebt(),
            template.movesClaims());

    movePeople(origin, destination, movedPeople);
    long operatedBefore = origin.stock(AssetKind.OPERATED_LAND);
    if (operatedBefore > 0L) {
      long operatedMoved =
          proportionalShareCeil(operatedBefore, movedPeople, beforePopulation, 1000L);
      origin.setOperatedLand(Math.max(0L, operatedBefore - operatedMoved));
    }
    boolean stockPerCapitaNotIncreased =
        checkOriginStockPerCapitaNotIncreased(origin, beforeStocks, beforePopulation);
    if (!stockPerCapitaNotIncreased) {
      stockEnrichmentViolations++;
      tickDiagnostics.add(
          "migration-stock-enrichment pool="
              + origin.classPositionId()
              + " moved="
              + movedPeople
              + " beforePop="
              + beforePopulation
              + " afterPop="
              + origin.population());
    }
    refreshAccountsAndDerived();

    String debtRule = template.movesDebt() ? "按人头带走（双边镜像同搬）" : "债务留在原池（bundle 规则）";
    String landRule =
        template.landToMarket()
            ? "土地所有权按人头份额进 LandForSale"
            : (landToDestination > 0L ? "土地所有权按人头份额进目的池" : "不带土地所有权（租约权利）");
    long afterAMilli = mobility.schema().aMilli(origin, config.moneyPerGrain());
    PilotModel.TransitionBundle bundle =
        new PilotModel.TransitionBundle(
            movedPeople,
            movedPeople * averageLaborPerCapita(origin) / 1000L,
            movedAssets,
            landToDestination,
            landToMarket,
            landBought,
            leaseGranted,
            leaseReturned,
            accountMovement[0],
            accountMovement[1],
            debtRule,
            landRule);
    return new PilotModel.MobilityEvent(
        tick,
        direction,
        origin.classPositionId(),
        destination.classPositionId(),
        movedPeople,
        bundle,
        aMilli,
        afterAMilli,
        stockPerCapitaNotIncreased,
        xMilli,
        rate,
        opportunity.opportunityPerMille,
        mobility.absorptionCapPerMille(origin.classPositionId(), destination.classPositionId()),
        capMilli,
        skipLevel,
        "A="
            + aMilli
            + " x="
            + xMilli
            + " r="
            + rate
            + " O="
            + opportunity.opportunityPerMille
            + " supply="
            + opportunity.supply
            + " demand="
            + opportunity.demand
            + " capPerMille="
            + (direction == PilotModel.Direction.UP
                ? mobility.upCapPerMillePerTick()
                : mobility.downCapPerMillePerTick()));
  }

  /** 跳级/未配置边的保守模板：土地只进 LandForSale 或目的地主池，绝不直接落进雇农池。 */
  private MobilityPolicy.BundleTemplate fallbackTemplate(ClassPool origin, ClassPool destination) {
    boolean destinationLandlord = PilotModel.LANDLORD_ID.equals(destination.classPositionId());
    boolean destinationTenant = PilotModel.TENANT_ID.equals(destination.classPositionId());
    boolean destinationMiddle = PilotModel.MIDDLE_PEASANT_ID.equals(destination.classPositionId());
    boolean takesLease = destinationTenant;
    boolean returnsLease = PilotModel.TENANT_ID.equals(origin.classPositionId());
    boolean buysLand = destinationMiddle;
    boolean landToMarket = !destinationLandlord && !destinationMiddle;
    return new MobilityPolicy.BundleTemplate(
        1000L,
        1000L,
        1000L,
        1000L,
        1000L,
        landToMarket,
        takesLease,
        returnsLease,
        buysLand,
        true,
        true);
  }

  private long shareFor(MobilityPolicy.BundleTemplate template, AssetKind kind) {
    return switch (kind) {
      case GRAIN -> template.grainSharePerMille();
      case CLOTH -> template.clothSharePerMille();
      case MONEY -> template.moneySharePerMille();
      case TOOLS -> template.toolsSharePerMille();
      case OWNED_LAND -> template.landSharePerMille();
      default -> 0L;
    };
  }

  private void movePeople(ClassPool origin, ClassPool destination, long movedPeople) {
    long remaining = movedPeople;
    for (HouseholdState member : householdStatesOf(origin)) {
      if (remaining <= 0L) {
        break;
      }
      long take = Math.min(remaining, member.population);
      member.population -= take;
      remaining -= take;
    }
    if (remaining > 0L) {
      throw new IllegalStateException(
          "origin pool population shrank below moved people: "
              + origin.classPositionId()
              + " moved="
              + movedPeople
              + " remaining="
              + remaining);
    }
    List<HouseholdState> destinationMembers = householdStatesOf(destination);
    if (destinationMembers.isEmpty()) {
      throw new IllegalStateException(
          "destination pool has no household account: " + destination.classPositionId());
    }
    HouseholdState receiver = destinationMembers.get(0);
    receiver.population += movedPeople;
    recomputePoolMembers(origin);
    recomputePoolMembers(destination);
  }

  /** 按人头比例搬走账户净额份额，并同步搬两侧镜像，保持 Σnet=0。 */
  private long[] moveAccountShare(
      ClassPool origin,
      ClassPool destination,
      long movedPeople,
      long beforePopulation,
      boolean moveDebt,
      boolean moveClaims) {
    long claimsMoved = 0L;
    long debtMoved = 0L;
    List<AccountState> owned = new ArrayList<>();
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(origin.classPositionId())) {
        owned.add(state);
      }
    }
    for (AccountState state : owned) {
      if (state.cumulativeNet == 0L) {
        continue;
      }
      boolean isDebt = state.cumulativeNet < 0L;
      if (isDebt && !moveDebt) {
        continue;
      }
      if (!isDebt && !moveClaims) {
        continue;
      }
      if (state.key.counterpartyId.equals(origin.classPositionId())
          || state.key.counterpartyId.equals(destination.classPositionId())) {
        continue;
      }
      long delta = state.cumulativeNet * movedPeople / beforePopulation;
      if (delta == 0L) {
        continue;
      }
      state.cumulativeNet -= delta;
      AccountState destinationState =
          accountFor(
              destination.classPositionId(), state.key.counterpartyId, state.key.unit, state);
      destinationState.cumulativeNet += delta;
      AccountState mirrorFrom =
          accountFor(state.key.counterpartyId, origin.classPositionId(), state.key.unit, state);
      mirrorFrom.cumulativeNet += delta;
      AccountState mirrorTo =
          accountFor(
              state.key.counterpartyId, destination.classPositionId(), state.key.unit, state);
      mirrorTo.cumulativeNet -= delta;
      if (isDebt) {
        debtMoved += valueMilli(state.key.unit, -delta);
      } else {
        claimsMoved += valueMilli(state.key.unit, delta);
      }
    }
    return new long[] {claimsMoved, debtMoved};
  }

  private AccountState accountFor(
      String ownerId, String counterpartyId, String unit, AccountState template) {
    AccountKey key = new AccountKey(ownerId, counterpartyId, unit);
    AccountState state = accounts.get(key);
    if (state == null) {
      state =
          new AccountState(
              key, template.terms, template.interestRatePerMille, template.nextDueTick);
      accounts.put(key, state);
    }
    return state;
  }

  private ClassPool destinationFor(ClassPool origin, PilotModel.Direction direction) {
    int tier = origin.tier() + (direction == PilotModel.Direction.UP ? 1 : -1);
    for (ClassPool candidate : pools.values()) {
      if (candidate.tier() == tier) {
        return candidate;
      }
    }
    return null;
  }

  private ClassPool skipDestinationFor(ClassPool origin, PilotModel.Direction direction) {
    int tier = origin.tier() + (direction == PilotModel.Direction.UP ? 2 : -2);
    for (ClassPool candidate : pools.values()) {
      if (candidate.tier() == tier) {
        return candidate;
      }
    }
    return null;
  }

  private Opportunity opportunityTo(
      String destinationClass, long requestedMilli, ClassPool origin, long unmetLaborUnits) {
    long cap = mobility.absorptionCapPerMille(origin.classPositionId(), destinationClass);
    long supplyMilli;
    long demandMilli;
    if (PilotModel.TENANT_ID.equals(destinationClass)) {
      // 可租地（地主按 leaseAvailability 放出的地 + LandForSale 上可续租的地）对租地需求。
      supplyMilli = leaseSupplyAvailable() * 1000L;
      demandMilli = Math.max(1L, requestedMilli * mobility.leasePerCapitaMilli() / 1000L);
    } else if (PilotModel.MIDDLE_PEASANT_ID.equals(destinationClass)) {
      // LandForSale 供给 + 购地基金的支付能力。
      supplyMilli = landForSale * 1000L;
      long perCapitaLiquidMilli =
          origin.population() <= 0L
              ? 0L
              : (origin.stock(AssetKind.GRAIN) * 1000L
                      + origin.stock(AssetKind.MONEY)
                          * 1000L
                          / Math.max(1L, config.moneyPerGrain()))
                  / origin.population();
      long landDemandMilli =
          Math.max(1L, requestedMilli * mobility.landPurchasePerCapitaMilli() / 1000L);
      long demandCostMilli = landDemandMilli * config.collectionPolicy().landPricePerUnit();
      long capacityMilli = perCapitaLiquidMilli * requestedMilli / 1000L;
      long affordability =
          demandCostMilli <= 0L ? 1000L : Math.min(1000L, capacityMilli * 1000L / demandCostMilli);
      long landOpportunity =
          supplyMilli <= 0L ? 0L : Math.min(1000L, supplyMilli * 1000L / landDemandMilli);
      long opportunity = Math.min(Math.min(cap, landOpportunity), affordability);
      return new Opportunity(
          opportunity, supplyMilli, landDemandMilli, supplyMilli < landDemandMilli);
    } else if (PilotModel.LANDLORD_ID.equals(destinationClass)) {
      // 可组织他人劳动的容量：下层愿意承租的人口越多，地主池吸收中农上行的容量越大。
      supplyMilli =
          (pools.get(PilotModel.TENANT_ID).population()
                  + pools.get(PilotModel.LABORER_ID).population())
              * 1000L;
      demandMilli = Math.max(1L, requestedMilli);
    } else if (PilotModel.LABORER_ID.equals(destinationClass)) {
      // 经营池尚未满足的劳动需求（真实机会）。
      long laborPerCapita = averageLaborPerCapita(origin);
      supplyMilli = unmetLaborUnits * 1_000_000L / Math.max(1L, laborPerCapita);
      demandMilli = Math.max(1L, requestedMilli);
    } else {
      return new Opportunity(0L, 0L, 1L, true);
    }
    long ratio =
        supplyMilli <= 0L ? 0L : Math.min(1000L, supplyMilli * 1000L / Math.max(1L, demandMilli));
    long opportunity = Math.min(cap, ratio);
    return new Opportunity(opportunity, supplyMilli, demandMilli, supplyMilli < demandMilli);
  }

  private void payLandSaleProceeds(long grain, long money) {
    List<ClassPool> landlords = new ArrayList<>();
    for (ClassPool pool : pools.values()) {
      if (PilotModel.LANDLORD_ID.equals(pool.classPositionId())) {
        landlords.add(pool);
      }
    }
    if (landlords.isEmpty() || (grain <= 0L && money <= 0L)) {
      landMarketEscrowGrain += Math.max(0L, grain);
      landMarketEscrowMoney += Math.max(0L, money);
      return;
    }
    long landlordPopulation = Math.max(1L, totalLandlordPopulation());
    if (grain > 0L) {
      long assigned = 0L;
      for (int i = 0; i < landlords.size(); i++) {
        ClassPool landlord = landlords.get(i);
        long amount =
            i == landlords.size() - 1
                ? grain - assigned
                : grain * landlord.population() / landlordPopulation;
        amount = Math.max(0L, amount);
        landlord.addStock(AssetKind.GRAIN, amount);
        assigned += amount;
      }
    }
    if (money > 0L) {
      long assigned = 0L;
      for (int i = 0; i < landlords.size(); i++) {
        ClassPool landlord = landlords.get(i);
        long amount =
            i == landlords.size() - 1
                ? money - assigned
                : money * landlord.population() / landlordPopulation;
        amount = Math.max(0L, amount);
        landlord.addStock(AssetKind.MONEY, amount);
        assigned += amount;
      }
    }
  }

  // ── 9) 报告 ──────────────────────────────────────────────────────────────────

  private PilotModel.TickReport buildReport() {
    LinkedHashMap<String, PilotModel.PoolReading> readings = new LinkedHashMap<>();
    for (PilotModel.ClassPosition position : PilotModel.classPositions()) {
      ClassPool pool = pools.get(position.id());
      long aMilli = mobility.schema().aMilli(pool, config.moneyPerGrain());
      long xMilli = mobility.bounds().xMilli(position.id(), aMilli);
      long up = mobility.rateUpPerMillePerYear(xMilli);
      long down = mobility.rateDownPerMillePerYear(xMilli);
      readings.put(
          position.id(),
          new PilotModel.PoolReading(
              position.id(),
              position.name(),
              pool.population(),
              pool.labor(),
              aMilli,
              xMilli,
              up,
              down,
              up / Math.max(1L, mobility.ticksPerYear()),
              down / Math.max(1L, mobility.ticksPerYear()),
              pool.assetVector(),
              pool.debtByUnit(),
              pool.leaseHolding(),
              pool.laborEfficiencyPerMille(),
              mobility.upCapPerMillePerTick(),
              mobility.downCapPerMillePerTick()));
    }
    List<PilotModel.MobilityEvent> tickMobility = new ArrayList<>();
    for (PilotModel.MobilityEvent event : mobilityEvents) {
      if (event.tick() == tick) {
        tickMobility.add(event);
      }
    }
    List<PilotModel.Transition> tickTransitions = new ArrayList<>();
    for (PilotModel.Transition transition : transitions) {
      if (transition.tick() == tick) {
        tickTransitions.add(transition);
      }
    }
    return new PilotModel.TickReport(
        tick,
        readings,
        landForSale,
        leaseSupplyAvailable(),
        tickMobility,
        tickTransitions,
        lastProductionAccounts,
        lastTickRedLights,
        lastTickBaseGap,
        tickDiagnostics,
        totalPopulation(),
        totalOwnedLand(),
        totalTools(),
        totalGrain(),
        totalCloth(),
        totalMoney(),
        totalAccountDebtMilli(),
        totalAccountClaimMilli(),
        producedGrainTotal,
        seedUsedTotal,
        rationConsumedTotal,
        clothConsumedTotal);
  }

  // ── 内部辅助 ─────────────────────────────────────────────────────────────────

  private List<ClassPool> poolsByTier(boolean descending) {
    List<ClassPool> result = new ArrayList<>(pools.values());
    result.sort(Comparator.comparingInt(ClassPool::tier).thenComparing(ClassPool::classPositionId));
    if (descending) {
      java.util.Collections.reverse(result);
    }
    return result;
  }

  private List<HouseholdState> householdStatesOf(ClassPool pool) {
    List<HouseholdState> result = new ArrayList<>();
    for (HouseholdState state : households.values()) {
      if (state.pool == pool) {
        result.add(state);
      }
    }
    result.sort(Comparator.comparing(state -> state.id));
    return result;
  }

  private void recomputePoolMembers() {
    for (ClassPool pool : pools.values()) {
      recomputePoolMembers(pool);
    }
  }

  private void recomputePoolMembers(ClassPool pool) {
    long population = 0L;
    long labor = 0L;
    for (HouseholdState state : householdStatesOf(pool)) {
      population += state.population;
      labor += state.population * state.laborPerCapita / 1000L;
    }
    pool.setPopulation(population);
    pool.setLabor(labor);
  }

  private void refreshAccountsAndDerived() {
    for (ClassPool pool : pools.values()) {
      LinkedHashMap<String, Long> debtByUnit = new LinkedHashMap<>();
      long debtGrainMilli = 0L;
      for (AccountState state : accounts.values()) {
        if (!state.key.ownerId.equals(pool.classPositionId()) || state.cumulativeNet >= 0L) {
          continue;
        }
        long magnitude = -state.cumulativeNet;
        debtByUnit.merge(state.key.unit, magnitude, Long::sum);
        debtGrainMilli += valueMilli(state.key.unit, magnitude);
      }
      pool.setDebtState(debtByUnit, debtGrainMilli);
    }
    long leaseTotal = 0L;
    for (ClassPool pool : pools.values()) {
      leaseTotal += pool.leaseHolding();
    }
    totalLeaseHolding = leaseTotal;
  }

  private long leaseSupplyAvailable() {
    long leasable =
        landlordOwnedLand() * Math.max(0L, mobility.leaseAvailabilityPerMille()) / 1000L
            + landForSale;
    return Math.max(0L, leasable - totalLeaseHolding);
  }

  private long landlordOwnedLand() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      if (PilotModel.LANDLORD_ID.equals(pool.classPositionId())) {
        total += pool.stock(AssetKind.OWNED_LAND);
      }
    }
    return total;
  }

  private long totalLandlordPopulation() {
    long total = 0L;
    for (ClassPool pool : pools.values()) {
      if (PilotModel.LANDLORD_ID.equals(pool.classPositionId())) {
        total += pool.population();
      }
    }
    return total;
  }

  private long protectedReserveGrain(ClassPool pool) {
    long laborUnits = pool.labor();
    return config.reserveTicks()
        * (config.baseRationPerCapita() * pool.population()
            + config.laborRationPerLabor() * laborUnits);
  }

  private long poolDebtValueMilli(ClassPool pool) {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      if (state.key.ownerId.equals(pool.classPositionId()) && state.cumulativeNet < 0L) {
        total += valueMilli(state.key.unit, -state.cumulativeNet);
      }
    }
    return total;
  }

  private long seizableValueMilli(ClassPool pool) {
    return pool.stock(AssetKind.GRAIN) * 1000L
        + pool.stock(AssetKind.MONEY) * 1000L / config.moneyPerGrain()
        + pool.stock(AssetKind.OWNED_LAND) * config.collectionPolicy().landPricePerUnit() * 1000L
        + pool.stock(AssetKind.TOOLS) * config.toolPricePerUnit() * 1000L;
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

  private long totalAccountDebtMilli() {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      if (state.cumulativeNet < 0L) {
        total += valueMilli(state.key.unit, -state.cumulativeNet);
      }
    }
    return total;
  }

  private long totalAccountClaimMilli() {
    long total = 0L;
    for (AccountState state : accounts.values()) {
      if (state.cumulativeNet > 0L) {
        total += valueMilli(state.key.unit, state.cumulativeNet);
      }
    }
    return total;
  }

  private LinkedHashMap<String, Long> splitByPool(long total, ClassPool pool) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    if (total > 0L && pool.population() > 0L) {
      result.put(pool.classPositionId(), total);
    }
    return result;
  }

  private LinkedHashMap<String, Long> splitByHousehold(long total, ClassPool pool) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    if (total <= 0L) {
      return result;
    }
    List<HouseholdState> members = householdStatesOf(pool);
    long shareSum = 0L;
    for (HouseholdState member : members) {
      shareSum += Math.max(0L, member.population * member.participationSharePerMille / 1000L);
    }
    if (shareSum <= 0L) {
      if (members.isEmpty()) {
        return result;
      }
      long each = total / members.size();
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
      long share = Math.max(0L, member.population * member.participationSharePerMille / 1000L);
      long amount = i == members.size() - 1 ? total - assigned : total * share / shareSum;
      amount = Math.max(0L, amount);
      if (amount > 0L) {
        result.put(member.id, amount);
      }
      assigned += amount;
    }
    return result;
  }

  private long averageLaborPerCapita(ClassPool pool) {
    if (pool.population() <= 0L) {
      List<HouseholdState> members = householdStatesOf(pool);
      return members.isEmpty() ? 500L : members.get(0).laborPerCapita;
    }
    return pool.labor() * 1000L / pool.population();
  }

  private void postObligation(
      String debtorId,
      String creditorId,
      String unit,
      long amount,
      String terms,
      long interestRatePerMille,
      long nextDueTick) {
    if (amount <= 0L || debtorId.equals(creditorId)) {
      return;
    }
    addNet(debtorId, creditorId, unit, -amount, terms, interestRatePerMille, nextDueTick);
    addNet(creditorId, debtorId, unit, amount, terms, interestRatePerMille, nextDueTick);
  }

  private void postPayment(String payerId, String payeeId, String unit, long amount) {
    if (amount <= 0L || payerId.equals(payeeId)) {
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

  private long trimMap(Map<String, Long> source, long limit) {
    long total = 0L;
    long left = Math.max(0L, limit);
    for (long value : source.values()) {
      long take = Math.min(left, Math.max(0L, value));
      total += take;
      left -= take;
    }
    return total;
  }

  private LinkedHashMap<String, Long> trimToMap(Map<String, Long> source, long limit) {
    LinkedHashMap<String, Long> result = new LinkedHashMap<>();
    long left = Math.max(0L, limit);
    for (Map.Entry<String, Long> entry : source.entrySet()) {
      if (left <= 0L) {
        break;
      }
      long take = Math.min(left, Math.max(0L, entry.getValue()));
      if (take > 0L) {
        result.put(entry.getKey(), take);
        left -= take;
      }
    }
    return result;
  }

  private static boolean checkOriginStockPerCapitaNotIncreased(
      ClassPool origin, long[] beforeStocks, long beforePopulation) {
    if (beforePopulation <= 0L) {
      return true;
    }
    long afterPopulation = origin.population();
    List<AssetKind> stockKinds =
        List.of(
            AssetKind.OWNED_LAND,
            AssetKind.TOOLS,
            AssetKind.GRAIN,
            AssetKind.CLOTH,
            AssetKind.MONEY,
            AssetKind.LEASE_SECURITY);
    for (int i = 0; i < stockKinds.size(); i++) {
      AssetKind kind = stockKinds.get(i);
      long before = beforeStocks[i];
      long after = kind == AssetKind.LEASE_SECURITY ? origin.leaseHolding() : origin.stock(kind);
      if (after * beforePopulation > before * afterPopulation) {
        return false;
      }
    }
    return true;
  }

  private static long proportionalShareCeil(
      long asset, long movedPeople, long beforePopulation, long sharePerMille) {
    if (asset <= 0L || movedPeople <= 0L || sharePerMille <= 0L) {
      return 0L;
    }
    java.math.BigInteger numerator =
        java.math.BigInteger.valueOf(asset)
            .multiply(java.math.BigInteger.valueOf(movedPeople))
            .multiply(java.math.BigInteger.valueOf(sharePerMille));
    java.math.BigInteger denominator =
        java.math.BigInteger.valueOf(Math.max(1L, beforePopulation))
            .multiply(java.math.BigInteger.valueOf(1000L));
    return numerator
        .add(denominator)
        .subtract(java.math.BigInteger.ONE)
        .divide(denominator)
        .longValue();
  }

  private static long ceilDiv(long numerator, long denominator) {
    return (numerator + denominator - 1L) / denominator;
  }

  // ── 内部状态类型 ─────────────────────────────────────────────────────────────

  private static final class HouseholdState {
    final String id;
    final String name;
    final ClassPool pool;
    final long laborPerCapita;
    final long participationSharePerMille;
    long population;

    HouseholdState(PilotModel.Household spec, ClassPool pool) {
      this.id = spec.id();
      this.name = spec.name();
      this.pool = pool;
      this.population = spec.population();
      this.laborPerCapita = spec.laborPerCapita();
      this.participationSharePerMille = spec.participationSharePerMille();
    }

    long sharePerMille() {
      return pool.population() <= 0L ? 0L : population * 1000L / pool.population();
    }

    PilotModel.HouseholdAccount snapshot() {
      return new PilotModel.HouseholdAccount(
          pool.classPositionId(),
          id,
          name,
          population,
          laborPerCapita,
          sharePerMille(),
          population * laborPerCapita / 1000L);
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

    PilotModel.RollingAccount snapshot() {
      long debt = cumulativeNet < 0L ? -cumulativeNet : 0L;
      long claim = cumulativeNet > 0L ? cumulativeNet : 0L;
      return new PilotModel.RollingAccount(
          key.ownerId,
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

  private static final class PoolPlan {
    final ClassPool pool;
    long ownedLand;
    long leasedLand;
    long desiredLand;
    long plannedLand;
    long ownLaborUsed;
    long externalLaborUsed;
    long seedRequired;
    long seedSelf;
    long seedExternal;
    long toolsUsed;
    long toolsShortageLand;
    long laborShortageLand;
    long seedShortage;
    long outputGrain;
    long rentDue;
    long wageDue;
    long seedDue;
    LinkedHashMap<String, Long> externalLaborByProviderPool = new LinkedHashMap<>();
    LinkedHashMap<String, Long> seedExternalByProviderPool = new LinkedHashMap<>();

    PoolPlan(ClassPool pool) {
      this.pool = pool;
    }
  }

  private record Opportunity(
      long opportunityPerMille, long supply, long demand, boolean supplyBelowDemand) {}
}
