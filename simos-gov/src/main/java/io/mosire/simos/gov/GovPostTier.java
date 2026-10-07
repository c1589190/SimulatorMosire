package io.mosire.simos.gov;

/**
 * 一档行政岗位的二维权重（Z2，设计书 §4.1 / §14）。
 *
 * <p>★★ <b>3 档岗位目录的位置</b>：档位目录住在 {@link GovAdministrationPlan#postTiers()}（每 GOV 一份慢变配置），本类型只描述
 * <b>一档</b>：稳定 tier id + 治安/公文两维权重（‰）。V1 冻结恰 3 档（{@link GovAdministrationPlan} 构造期判），默认权重见 {@link
 * GovAdministrationPlan#DEFAULT_POST_TIERS}。
 *
 * <p>★ <b>量纲与不封顶</b>：两个权重都是 per-mille（‰），把该档岗位上的承诺劳动按权重投到对应维；<b>不设上限</b>（用户
 * 2026-10-23「都不封顶」）——默认目录每档两维权重的合计是 1000‰，但构造期只保 非负，不强制合计 1000 （GM/决策人可配出跨维或超额口径，公式逐值照算）。
 *
 * <p>★ <b>为什么没有档名/StaffRole</b>：Z2 的范围冻结为“每档 → 两维权重”这一件事；档名与 {@code StaffRole} 兼容键的映射是 Z4 的职责（设计书
 * §4.3 / §14.2 的开放项）。此处不预埋第二份真相。
 *
 * @param tierId 档位稳定 id（非空白；同一目录内唯一）
 * @param securityWeightPerMille 治安维权重（‰；≥ 0，不封顶）
 * @param paperworkWeightPerMille 公文维权重（‰；≥ 0，不封顶）
 */
public record GovPostTier(
    String tierId, long securityWeightPerMille, long paperworkWeightPerMille) {

  public GovPostTier {
    if (tierId == null || tierId.isBlank()) {
      throw new IllegalArgumentException("tierId 不得为空白");
    }
    requireNonNegative(securityWeightPerMille, "securityWeightPerMille");
    requireNonNegative(paperworkWeightPerMille, "paperworkWeightPerMille");
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
