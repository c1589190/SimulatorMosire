package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.CrisisSignalBook.writeCrisisSignals;
import static io.mosire.simos.economy.time.DebtStressBook.resolvablePosition;
import static io.mosire.simos.economy.time.DebtStressBook.withStressCount;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.AuditEntry;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Context;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Plan;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.STANDING_SEED_REASON;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.StressUpdate;

import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** E5b 机械拆分 helper（P2d：压力计数 + 危机信号；不处置资产）。 */
final class LiquidationApplyBook {

  private LiquidationApplyBook() {}

  static void apply(Context context, Plan plan) {
    // 到期即默认：先落状态，后续计息/结清仍按既有唯一写口语义。
    for (DebtContractId debtId : plan.autoDefaults()) {
      DebtContractBook.markStatus(context.debts(), debtId, DebtStatus.DEFAULTED);
    }

    writeStressCounters(context, plan);

    if (!plan.explosions().isEmpty()) {
      writeCrisisSignals(context, plan);
    }

    for (AuditEntry entry : plan.audits()) {
      context.ledger().addLiquidationAudit(toLiquidationAudit(entry));
    }
  }

  /** 压力计数器落到 working copy；只有真的要写才物化 {@code classStandings} 工作副本。 */
  static void writeStressCounters(Context context, Plan plan) {
    if (plan.stressUpdates().isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships =
        context.session().sheet().classMemberships();
    Map<ClassPositionId, ProductionRole> positions = context.base().classPositions();

    for (StressUpdate update : plan.stressUpdates()) {
      HouseholdClassMembership existingClassMembership = classMemberships.get(update.household());
      if (existingClassMembership == null) {
        Optional<ClassPositionId> position =
            resolvablePosition(context, update.household(), classMemberships, positions);
        if (position.isEmpty()) {
          continue; // planner 已写具名审计：不伪造位置
        }
        existingClassMembership =
            new HouseholdClassMembership(
                update.household(),
                position.get(),
                position.get(),
                Set.of(),
                Map.of(),
                update.previousCount(),
                0L,
                STANDING_SEED_REASON);
      }
      classMemberships.put(
          update.household(), withStressCount(existingClassMembership, update.nextCount()));
    }
  }

  static ProductionLedger.LiquidationAudit toLiquidationAudit(AuditEntry entry) {
    return new ProductionLedger.LiquidationAudit(
        entry.day(),
        entry.action(),
        Optional.ofNullable(entry.household()),
        Optional.ofNullable(entry.contractId()),
        Optional.empty(),
        Optional.empty(),
        0L,
        0L,
        0L,
        0L,
        entry.reason(),
        entry.evidence());
  }
}
