package io.mosire.simos.economy.time;

/** D3 §2.5.1：生存资格稳定原因。 */
public enum SurvivalOutlookReason {
  SURVIVAL_REACHABLE("survival-reachable"),
  NO_FEASIBLE_LIVELIHOOD("no-feasible-livelihood"),
  SUBSISTENCE_SHORTFALL("subsistence-shortfall");

  private final String stableReason;

  SurvivalOutlookReason(String stableReason) {
    this.stableReason = stableReason;
  }

  public String stableReason() {
    return stableReason;
  }
}
