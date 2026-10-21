package io.mosire.simos.economy.model;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * 家户流动表的单个目标。
 *
 * @param targetHousehold 目标家户稳定身份；不得为 null
 * @param reason 为什么选它；不得为 null
 */
public record HouseholdFlowTarget(HouseholdId targetHousehold, HouseholdFlowReason reason) {

  public HouseholdFlowTarget {
    Objects.requireNonNull(targetHousehold, "HouseholdFlowTarget.targetHousehold 不得为 null");
    Objects.requireNonNull(reason, "HouseholdFlowTarget.reason 不得为 null");
  }
}
