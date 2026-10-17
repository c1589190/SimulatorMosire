package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★ <b>一条可审计的经验增长明细</b>（D-032 D2 §6.1）：家户 × 角色 × 经验条 × 本次 tick 增量。
 *
 * <p>{@link ProductionReceipt#experienceGrowth()} 仍按 {@code (household, track)} 汇总；本类型是其逐角色拆解，
 * 两者必须逐项相等（由 {@link ProductionReceipt} 构造器校验）。
 *
 * @param household 家户；不得为 null
 * @param role 角色；不得为 null
 * @param track 经验条；不得为 null
 * @param delta 本次 tick 实际写入的经验增量；必须 &gt; 0（未增长的行不进表）
 */
public record ProductionExperienceGrowthLine(
    HouseholdId household, ProductionRoleId role, ExperienceTrackId track, long delta) {

  public ProductionExperienceGrowthLine {
    Objects.requireNonNull(household, "ProductionExperienceGrowthLine.household 不得为 null");
    Objects.requireNonNull(role, "ProductionExperienceGrowthLine.role 不得为 null");
    Objects.requireNonNull(track, "ProductionExperienceGrowthLine.track 不得为 null");
    if (delta <= 0L) {
      throw new IllegalArgumentException(
          "ProductionExperienceGrowthLine.delta 必须为正（未增长的行不进表）: " + delta);
    }
  }
}
