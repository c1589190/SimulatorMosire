package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.ACTION_DEBT_EXPLOSION;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.ACTION_SKIPPED;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.AuditEntry;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Context;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DEBT;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DEBT_EXPLOSION_F_MULTIPLE;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DEBT_STRESS_CYCLES_THRESHOLD;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DebtExplosionPlan;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.LOG;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Plan;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.StressUpdate;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** E5b 机械拆分 helper（P2d：只保留债务压力/危机信号，不处置资产）。 */
final class DebtStressBook {

  private DebtStressBook() {}

  // ── planner ─────────────────────────────────────────────────────────────────────────────

  static Plan plan(Context context) {
    EconomyData base = context.base();
    // ★★ 路径无关修复（E5b 计数器）：阶层归属是**当刻工作副本**，不是本次 advance 的基态快照。
    Map<HouseholdId, HouseholdClassMembership> classMemberships =
        context.session().sheet().classMembershipsOrBase();
    Map<ClassPositionId, ProductionRole> positions = base.classPositions();
    List<AuditEntry> audits = new ArrayList<>();

    Map<HouseholdId, List<DebtContract>> debtsByDebtor = indexDebtsByDebtor(context.debts());
    List<HouseholdId> households = sortedHouseholds(context.householdEconomies());
    // ★★ 到期即默认：先用有效状态参与本轮的应力判定，需落状态的在 apply 统一 markStatus。
    List<DebtContractId> autoDefaults = new ArrayList<>();
    Map<DebtContractId, DebtStatus> effectiveStatuses = effectiveStatuses(context, autoDefaults);

    // ── 1. 压力判定 + 连续计数器（逐户，稳定 HouseholdId 序）──────────────────────────────
    Map<HouseholdId, Long> nextCounts = new LinkedHashMap<>();
    Map<HouseholdId, Set<DebtContractId>> stressedContractsByHousehold = new LinkedHashMap<>();
    List<StressUpdate> stressUpdates = new ArrayList<>();
    for (HouseholdId household : households) {
      List<DebtContract> active = activeDebts(debtsByDebtor.getOrDefault(household, List.of()));
      DebtCapacity capacity = context.debtCapacities().get(household);
      Map<String, Long> evidence = new LinkedHashMap<>();
      Set<DebtContractId> stressedContracts = new LinkedHashSet<>();
      boolean anyStress = false;
      long principalTotal = 0L;
      long startTotal = 0L;
      long interestTotal = 0L;
      long repaidToday = 0L;
      int defaultedContracts = 0;
      int overdueContracts = 0;
      int shortContracts = 0;
      int grainContracts = 0;
      int nonGrainContracts = 0;
      for (DebtContract debt : active) {
        if (DebtContractBook.isSelfHeld(debt)) {
          // 自持合同没有可执行的债权人，不计违约压力；逐条 DEBUG 具名。
          if (DEBT.isDebugEnabled()) {
            EventLog.channel(DEBT)
                .debug(
                    LogEvent.of(
                        "DEBT_STRESS_SELF_HELD_SKIPPED",
                        EconomyLogSource.ECONOMY_DEBT_STATE,
                        "day",
                        context.day(),
                        "cycle",
                        context.currentCycle(),
                        "household",
                        household.value(),
                        "debtContract",
                        debt.id().value(),
                        "unit",
                        debt.unit().key(),
                        "outstandingPrincipal",
                        debt.principal(),
                        "why",
                        "self-held-not-executable"));
          }
          continue;
        }
        long startPrincipal = context.principalAtDayStart().getOrDefault(debt.id(), 0L);
        long currentPrincipal = debt.principal();
        long cycleInterest = perMille(startPrincipal, debt.terms().interestRatePerMillePerCycle());
        boolean overdue = contractDueCycle(debt) <= context.currentCycle();
        long required = overdue ? Math.max(cycleInterest, currentPrincipal) : cycleInterest;
        boolean principalDeclined = currentPrincipal < startPrincipal;
        boolean shortOfRequired =
            isGrainDebt(debt) && capacity != null && capacity.F() < required && !principalDeclined;
        DebtStatus effectiveStatus = effectiveStatuses.getOrDefault(debt.id(), debt.status());
        boolean stressed = contractStressed(effectiveStatus, overdue, shortOfRequired);
        if (stressed) {
          anyStress = true;
          stressedContracts.add(debt.id());
        }
        if (effectiveStatus == DebtStatus.DEFAULTED) {
          defaultedContracts++;
        }
        if (overdue) {
          overdueContracts++;
        }
        if (shortOfRequired) {
          shortContracts++;
        }
        if (!isGrainDebt(debt)) {
          nonGrainContracts++;
        } else {
          grainContracts++;
          principalTotal = saturatedAdd(principalTotal, currentPrincipal);
          startTotal = saturatedAdd(startTotal, startPrincipal);
          interestTotal = saturatedAdd(interestTotal, cycleInterest);
          repaidToday =
              saturatedAdd(
                  repaidToday, context.repaidPrincipalByDebt().getOrDefault(debt.id(), 0L));
        }
      }
      boolean inputShortfall = capacity != null && nextRoundInputNotFunded(capacity);
      boolean counterStress = anyStress || inputShortfall;
      Optional<ClassPositionId> resolvable =
          resolvablePosition(context, household, classMemberships, positions);
      long previous =
          classMemberships.get(household) == null
              ? 0L
              : classMemberships.get(household).consecutiveDebtStressCycles();
      long incremented = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
      long next =
          counterStress && resolvable.isPresent() ? incremented : (counterStress ? previous : 0L);
      nextCounts.put(household, next);
      stressedContractsByHousehold.put(household, stressedContracts);
      evidence.put("grainPrincipalTotalMilli", principalTotal);
      evidence.put("grainPrincipalAtDayStartMilli", startTotal);
      evidence.put("grainInterestChargedMilli", interestTotal);
      evidence.put("grainContracts", (long) grainContracts);
      evidence.put("nonGrainContracts", (long) nonGrainContracts);
      evidence.put("defaultedContracts", (long) defaultedContracts);
      evidence.put("overdueContracts", (long) overdueContracts);
      evidence.put("shortOfRequiredContracts", (long) shortContracts);
      if (capacity == null) {
        evidence.put("FUnavailable", 1L);
      } else {
        evidence.put("FMilli", capacity.F());
        evidence.put("basicRationMilli", capacity.basicRation());
        evidence.put("nextRoundNecessaryInputMilli", capacity.nextRoundNecessaryInput());
        evidence.put("afterAllocationGrainIncomeMilli", capacity.afterAllocationGrainIncome());
        evidence.put("inputNotFunded", inputShortfall ? 1L : 0L);
      }
      evidence.put("repaidPrincipalTodayMilli", repaidToday);
      if (next != previous) {
        stressUpdates.add(new StressUpdate(household, previous, next, counterStress, evidence));
      } else if (counterStress && resolvable.isEmpty()) {
        audits.add(
            audit(
                context.day(),
                ACTION_SKIPPED,
                household,
                null,
                "stress-counter-unpersisted:no-current-position",
                evidence));
      }
    }

    // ── 2. DEBT_EXPLOSION 读数（本金/F 比或"压力≥阈值且本金净增"）────────────────────────
    List<DebtExplosionPlan> explosions = new ArrayList<>();
    for (HouseholdId household : households) {
      List<DebtContract> active = activeDebts(debtsByDebtor.getOrDefault(household, List.of()));
      if (active.isEmpty()) {
        continue;
      }
      HouseholdEconomy householdEconomy = context.householdEconomies().get(household);
      if (householdEconomy == null) {
        continue;
      }
      long principalTotal = 0L;
      long startTotal = 0L;
      int grainContracts = 0;
      int nonGrainContracts = 0;
      for (DebtContract debt : active) {
        if (!isGrainDebt(debt)) {
          nonGrainContracts++;
          continue;
        }
        grainContracts++;
        principalTotal = saturatedAdd(principalTotal, debt.principal());
        startTotal =
            saturatedAdd(startTotal, context.principalAtDayStart().getOrDefault(debt.id(), 0L));
      }
      if (grainContracts == 0) {
        continue;
      }
      long netDelta = principalTotal - startTotal;
      DebtCapacity capacity = context.debtCapacities().get(household);
      long F = capacity == null ? -1L : capacity.F();
      long stressCycles = nextCounts.getOrDefault(household, 0L);
      long multiple = capacity == null ? -1L : principalTotal / Math.max(1L, F);
      boolean ratioExplosion = capacity != null && multiple >= DEBT_EXPLOSION_F_MULTIPLE;
      boolean accumulationExplosion = stressCycles >= DEBT_STRESS_CYCLES_THRESHOLD && netDelta > 0L;
      if (!ratioExplosion && !accumulationExplosion) {
        continue;
      }
      int severity =
          ratioExplosion ? (int) Math.min(5L, 2L + multiple / DEBT_EXPLOSION_F_MULTIPLE) : 2;
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("principalTotalMilli", principalTotal);
      evidence.put("FMilli", F);
      evidence.put("principalToFMultiple", multiple);
      evidence.put("netPrincipalDeltaMilli", netDelta);
      evidence.put("consecutiveStressCycles", stressCycles);
      evidence.put("thresholdMultiple", DEBT_EXPLOSION_F_MULTIPLE);
      evidence.put("grainDebtContracts", (long) grainContracts);
      evidence.put("nonGrainDebtContracts", (long) nonGrainContracts);
      if (capacity != null) {
        evidence.put("nextRoundNecessaryInputMilli", capacity.nextRoundNecessaryInput());
        evidence.put("afterAllocationGrainIncomeMilli", capacity.afterAllocationGrainIncome());
      } else {
        evidence.put("FUnavailable", 1L);
      }
      String reason =
          "debt-explosion:principalToFMultiple="
              + multiple
              + ";stressCycles="
              + stressCycles
              + ";window=preLiquidation"
              + ";unit=milli";
      explosions.add(
          new DebtExplosionPlan(
              household,
              householdEconomy.view().hex(),
              principalTotal,
              F,
              multiple,
              netDelta,
              stressCycles,
              severity,
              reason,
              evidence));
      audits.add(audit(context.day(), ACTION_DEBT_EXPLOSION, household, null, reason, evidence));
    }

    if (LOG.isDebugEnabled() || LOG.isInfoEnabled()) {
      logStressThresholdFired(context, households, nextCounts, stressedContractsByHousehold);
    }

    return new Plan(stressUpdates, audits, explosions, autoDefaults);
  }

  /** ★★ <b>§一.9：连续压力达到阈值时的具名读数</b>（只读快照，不写任何状态、不改公式）。本阶段不再处置资产，因此只报压力户与受压合同数。 */
  static void logStressThresholdFired(
      Context context,
      List<HouseholdId> households,
      Map<HouseholdId, Long> nextCounts,
      Map<HouseholdId, Set<DebtContractId>> stressedContractsByHousehold) {
    long householdsAtThreshold = 0L;
    long stressedContractsTotal = 0L;
    for (HouseholdId household : households) {
      long stressCycles = nextCounts.getOrDefault(household, 0L);
      if (stressCycles < DEBT_STRESS_CYCLES_THRESHOLD) {
        continue;
      }
      householdsAtThreshold++;
      long stressedContracts =
          stressedContractsByHousehold.getOrDefault(household, Set.of()).size();
      stressedContractsTotal += stressedContracts;
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "DEBT_STRESS_THRESHOLD_FIRED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    context.day(),
                    "cycle",
                    context.currentCycle(),
                    "household",
                    household.value(),
                    "stressCycles",
                    stressCycles,
                    "threshold",
                    DEBT_STRESS_CYCLES_THRESHOLD,
                    "stressedContracts",
                    stressedContracts));
      }
    }
    if (householdsAtThreshold > 0L) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "DEBT_STRESS_BRANCH_FIRED",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  context.day(),
                  "cycle",
                  context.currentCycle(),
                  "householdsAtThreshold",
                  householdsAtThreshold,
                  "threshold",
                  DEBT_STRESS_CYCLES_THRESHOLD,
                  "stressedContracts",
                  stressedContractsTotal));
    }
  }

  // ── 纯函数/工具 ─────────────────────────────────────────────────────────────────────────

  /** P2c：合同本金只允许货币；旧“GRAIN 实物债”判据已无合法输入，恒 false（保留调用点待 P2e 重写）。 */
  static boolean isGrainDebt(DebtContract debt) {
    return false;
  }

  /** 合同压力：有效状态 {@code DEFAULTED} 或 到期未偿 或 {@code F < 本期利息/到期应还 且本金未下降}。 */
  static boolean contractStressed(
      DebtStatus effectiveStatus, boolean overdue, boolean shortOfRequired) {
    return effectiveStatus == DebtStatus.DEFAULTED || overdue || shortOfRequired;
  }

  /** {@code F 项不足下一轮必要投入}的饱和比较（不溢出）。 */
  static boolean nextRoundInputNotFunded(DebtCapacity capacity) {
    if (capacity.nextRoundNecessaryInput() <= 0L) {
      return false;
    }
    long remaining = capacity.afterAllocationGrainIncome();
    if (remaining < capacity.basicRation()) {
      return true;
    }
    remaining -= capacity.basicRation();
    if (remaining < capacity.nextRoundNecessaryInput()) {
      return true;
    }
    remaining -= capacity.nextRoundNecessaryInput();
    return remaining < capacity.taxPaid();
  }

  /** 户当前位置：唯一拼写点已搬到 {@link ClassLadderBook#resolvablePosition}。 */
  static Optional<ClassPositionId> resolvablePosition(
      Context context,
      HouseholdId household,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<ClassPositionId, ProductionRole> positions) {
    return ClassLadderBook.resolvablePosition(
        household, classMemberships, positions, context.householdEconomies().get(household));
  }

  static HouseholdClassMembership withStressCount(
      HouseholdClassMembership classMembership, long count) {
    return new HouseholdClassMembership(
        classMembership.householdId(),
        classMembership.originalPositionId(),
        classMembership.currentPositionId(),
        classMembership.participatingPositionIds(),
        classMembership.retainedShares(),
        count,
        classMembership.lastTransitionDay(),
        classMembership.reason());
  }

  /** 到期即默认的有效状态（自持合同不在此列）。 */
  static Map<DebtContractId, DebtStatus> effectiveStatuses(
      Context context, List<DebtContractId> autoDefaults) {
    Map<DebtContractId, DebtStatus> statuses = new LinkedHashMap<>();
    for (DebtContract debt : context.debts().values()) {
      DebtStatus status = debt.status();
      boolean terminal =
          status == DebtStatus.DEFAULTED
              || status == DebtStatus.SETTLED
              || status == DebtStatus.FORGIVEN;
      if (DebtContractBook.isSelfHeld(debt)) {
        statuses.put(debt.id(), status);
      } else if (debt.principal() > 0L
          && !terminal
          && contractDueCycle(debt) <= context.currentCycle()) {
        statuses.put(debt.id(), DebtStatus.DEFAULTED);
        autoDefaults.add(debt.id());
      } else {
        statuses.put(debt.id(), status);
      }
    }
    autoDefaults.sort(Comparator.comparing(DebtContractId::value));
    return statuses;
  }

  /** 现有 overdue 判定：合同滚动 dueCycle 优先，否则 terms 固定期限；两者都空 = 无到期。 */
  static long contractDueCycle(DebtContract debt) {
    return debt.dueCycle().isPresent()
        ? debt.dueCycle().getAsLong()
        : debt.terms().dueCycle().orElse(Long.MAX_VALUE);
  }

  static Map<HouseholdId, List<DebtContract>> indexDebtsByDebtor(
      Map<DebtContractId, DebtContract> debts) {
    List<DebtContract> sorted = new ArrayList<>(debts.values());
    sorted.sort(Comparator.comparing(debt -> debt.id().value()));
    Map<HouseholdId, List<DebtContract>> index = new LinkedHashMap<>();
    for (DebtContract debt : sorted) {
      index.computeIfAbsent(debt.debtor(), ignored -> new ArrayList<>()).add(debt);
    }
    return index;
  }

  static List<HouseholdId> sortedHouseholds(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> households = new ArrayList<>(householdEconomies.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    return households;
  }

  static List<DebtContract> activeDebts(List<DebtContract> debts) {
    List<DebtContract> active = new ArrayList<>();
    for (DebtContract debt : debts) {
      if (debt.principal() > 0L) {
        active.add(debt);
      }
    }
    return active;
  }

  static long perMille(long value, int perMille) {
    if (value <= 0L || perMille <= 0) {
      return 0L;
    }
    return BigInteger.valueOf(value)
        .multiply(BigInteger.valueOf(perMille))
        .divide(BigInteger.valueOf(1000L))
        .longValueExact();
  }

  static long saturatedAdd(long left, long right) {
    long sum = left + right;
    if (((left ^ sum) & (right ^ sum)) < 0L) {
      return Long.MAX_VALUE;
    }
    return sum;
  }

  static AuditEntry audit(
      long day,
      String action,
      HouseholdId household,
      DebtContractId contractId,
      String reason,
      Map<String, Long> evidence) {
    return new AuditEntry(day, action, household, contractId, reason, Map.copyOf(evidence));
  }
}
