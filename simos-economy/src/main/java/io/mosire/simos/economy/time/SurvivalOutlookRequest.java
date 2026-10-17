package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** D3 §2.5.1：SurvivalOutlook 的完整只读输入快照。 */
public record SurvivalOutlookRequest(
    HouseholdId household,
    NextTickSubsistenceNeed nextTickNeed,
    SurvivalResourceFacts currentResources,
    long laborBudgetMilli,
    List<LivelihoodAlternative> feasibleAlternatives,
    LocalReachabilityFacts localFacts,
    ExistingClaims existingClaims,
    int maxCombinations) {

  public SurvivalOutlookRequest {
    Objects.requireNonNull(household, "SurvivalOutlookRequest.household 不得为 null");
    Objects.requireNonNull(nextTickNeed, "SurvivalOutlookRequest.nextTickNeed 不得为 null");
    Objects.requireNonNull(currentResources, "SurvivalOutlookRequest.currentResources 不得为 null");
    if (laborBudgetMilli < 0L) {
      throw new IllegalArgumentException(
          "survival-input-invalid: laborBudgetMilli 不得为负: " + laborBudgetMilli);
    }
    Objects.requireNonNull(
        feasibleAlternatives, "SurvivalOutlookRequest.feasibleAlternatives 不得为 null（无替代给空表）");
    List<LivelihoodAlternative> alternatives = new ArrayList<>();
    for (LivelihoodAlternative alternative : feasibleAlternatives) {
      Objects.requireNonNull(alternative, "feasibleAlternatives 不得含 null");
      alternatives.add(alternative);
    }
    alternatives.sort((left, right) -> left.id().compareTo(right.id()));
    feasibleAlternatives = Collections.unmodifiableList(alternatives);
    Objects.requireNonNull(localFacts, "survival-market-facts-missing: localFacts 不得为 null");
    Objects.requireNonNull(existingClaims, "SurvivalOutlookRequest.existingClaims 不得为 null");
    if (maxCombinations <= 0) {
      throw new IllegalArgumentException(
          "survival-input-invalid: maxCombinations 必须 > 0: " + maxCombinations);
    }
  }
}
