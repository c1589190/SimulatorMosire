package io.mosire.simos.gov;

/**
 * 一档行政岗位的<b>三维</b>权重（Z2 两维；R2 补第三维 = 口岸，2026-10-09 设计书 §4.2 / 上位文档 §10-3）。
 *
 * <p>★★ <b>3 档岗位目录的位置</b>：档位目录住在 {@link GovAdministrationPlan#postTiers()}（每 GOV 一份慢变配置），本类型只描述
 * <b>一档</b>：稳定 tier id + 治安/公文/口岸三维权重（‰）。V1 冻结恰 3 档（{@link GovAdministrationPlan} 构造期判），默认权重见
 * {@link GovAdministrationPlan#DEFAULT_POST_TIERS}。
 *
 * <p>★ <b>量纲与不封顶</b>：三个权重都是 per-mille（‰），把该档岗位上的承诺劳动按权重投到对应维；<b>不设上限</b>（用户
 * 2026-10-23「都不封顶」）——默认目录每档三维权重的合计是 1000‰，但构造期只保 非负，不强制合计 1000 （GM/决策人可配出跨维或超额口径，公式逐值照算）。
 *
 * <p>★★ <b>R2 旧档/旧调用点兼容（{@code portWeightPerMille} 缺省 = 0）</b>：
 *
 * <ul>
 *   <li><b>Java 侧</b>：保留旧 3 参构造器，口岸维权重取 <b>0</b> = 该档位不产出任何口岸编制劳动；
 *   <li><b>JSON 侧</b>：旧档缺该键 ⇒ Jackson 给原始 {@code long} 的 0，同一语义；
 *   <li><b>为什么 0 是唯一安全缺省</b>：口岸维权重为 0 ⇒ 口岸维供给 0 ⇒ 口岸效率 0 ⇒ 实际管制力 0 ⇒ 一切照旧放行（I-P8 「无口岸政策 ⇒
 *       旧世界逐值不变」）。这与另两维不同（它们缺省必须是非零才有意义），因为口岸维是<b>新增的、可缺席的</b>维度。
 * </ul>
 *
 * <p>★ <b>为什么没有档名/StaffRole</b>：Z2 的范围冻结为“每档 → 各维权重”这一件事；档名与 {@code StaffRole} 兼容键的映射是 Z4 的职责（设计书
 * §4.3 / §14.2 的开放项）。此处不预埋第二份真相。★ 口岸编制<b>不</b>新增 {@code StaffRole} 词条（那要改 {@code simos-unit}，超出 R2
 * 的文件所有权）：口岸岗位 = 挂到"口岸权重 &gt; 0 的档位"上的既有角色家户。
 *
 * @param tierId 档位稳定 id（非空白；同一目录内唯一）
 * @param securityWeightPerMille 治安维权重（‰；≥ 0，不封顶）
 * @param paperworkWeightPerMille 公文维权重（‰；≥ 0，不封顶）
 * @param portWeightPerMille 口岸维权重（‰；≥ 0，不封顶；旧档/旧调用点缺省 = 0 = 不产出口岸编制）
 */
public record GovPostTier(
    String tierId,
    long securityWeightPerMille,
    long paperworkWeightPerMille,
    long portWeightPerMille) {

  public GovPostTier {
    if (tierId == null || tierId.isBlank()) {
      throw new IllegalArgumentException("tierId 不得为空白");
    }
    requireNonNegative(securityWeightPerMille, "securityWeightPerMille");
    requireNonNegative(paperworkWeightPerMille, "paperworkWeightPerMille");
    requireNonNegative(portWeightPerMille, "portWeightPerMille");
  }

  /** ★ <b>旧 3 参构造器（R2 兼容）</b>：口岸维权重取 0（= 本档位不产出口岸编制；旧世界逐值不变）。 */
  public GovPostTier(String tierId, long securityWeightPerMille, long paperworkWeightPerMille) {
    this(tierId, securityWeightPerMille, paperworkWeightPerMille, 0L);
  }

  /** 三维权重合计（‰；供给拆分算式的分母 W）。{@code 0} ⇒ 该档位无法定向（拆分处具名契约 ERROR）。 */
  public long weightSumPerMille() {
    return Math.addExact(
        Math.addExact(securityWeightPerMille, paperworkWeightPerMille), portWeightPerMille);
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
