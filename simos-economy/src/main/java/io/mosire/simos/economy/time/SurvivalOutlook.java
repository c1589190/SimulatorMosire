package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/** D3 §2.5.1：纯只读生存预测结果；只填 idleEligible，不填 idleChosen。 */
public record SurvivalOutlook(
    HouseholdId household,
    NextTickSubsistenceNeed nextTickNeed,
    SurvivalCoverage bestAchievableCoverage,
    SurvivalShortfall shortfall,
    boolean idleEligible,
    SurvivalOutlookReason reason) {
  public SurvivalOutlook {
    Objects.requireNonNull(household, "SurvivalOutlook.household 不得为 null");
    Objects.requireNonNull(nextTickNeed, "SurvivalOutlook.nextTickNeed 不得为 null");
    Objects.requireNonNull(
        bestAchievableCoverage, "SurvivalOutlook.bestAchievableCoverage 不得为 null");
    Objects.requireNonNull(shortfall, "SurvivalOutlook.shortfall 不得为 null");
    Objects.requireNonNull(reason, "SurvivalOutlook.reason 不得为 null");
  }
}
