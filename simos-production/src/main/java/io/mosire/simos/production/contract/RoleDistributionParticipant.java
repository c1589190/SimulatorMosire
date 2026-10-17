package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★ <b>角色分配的一个参与项</b>（D-032 D2 §6.3）：家户、角色与可选的显式权重。
 *
 * <p>{@code configuredWeight} 只在角色规则为 {@link IntraRoleSplitRule#CONFIGURED_WEIGHTS} 时被读取； 其余规则必须为
 * 0（避免“配了但不读”的假配置）。负权重是契约错误。
 *
 * @param household 参与家户；不得为 null
 * @param role 角色；不得为 null
 * @param configuredWeight 显式权重；必须 ≥ 0
 */
public record RoleDistributionParticipant(
    HouseholdId household, ProductionRoleId role, long configuredWeight) {

  public RoleDistributionParticipant {
    Objects.requireNonNull(household, "RoleDistributionParticipant.household 不得为 null");
    Objects.requireNonNull(role, "RoleDistributionParticipant.role 不得为 null");
    if (configuredWeight < 0L) {
      throw new IllegalArgumentException(
          "RoleDistributionParticipant.configuredWeight 不得为负: " + configuredWeight);
    }
  }

  public ProductionParticipantKey participantKey() {
    return new ProductionParticipantKey(household, role);
  }
}
