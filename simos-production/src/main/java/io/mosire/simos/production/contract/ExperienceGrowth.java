package io.mosire.simos.production.contract;

import java.util.Objects;

/**
 * ★★ <b>角色经验增长配置</b>（D-032 D1 §2.5）。
 *
 * <p>同一 {@link ExperienceTrackId} 可被多个角色共用；增长仍写入现有 {@code ProductionData.householdExperience} 的
 * {@code (家户, 经验条)} 主值 + 余数。按角色计算后如何归并由 C1 执行；本类型只冻结配置。
 *
 * @param track 增长到哪条经验条；不得为 null
 * @param basis 计算基准；不得为 null
 * @param coefficientPerMille 该角色增长系数（千分比）；≥ 0，0 = 不涨
 * @param maxPerTickValue 单 tick 增长上限；≥ 0，0 = 不设额外上限
 * @param countsManagement 管理投入是否计入；默认 false，真值需 economy 提供管理投入事实
 */
public record ExperienceGrowth(
    ExperienceTrackId track,
    GrowthBasis basis,
    long coefficientPerMille,
    long maxPerTickValue,
    boolean countsManagement) {

  public ExperienceGrowth {
    Objects.requireNonNull(track, "ExperienceGrowth.track 不得为 null");
    Objects.requireNonNull(basis, "ExperienceGrowth.basis 不得为 null");
    if (coefficientPerMille < 0L) {
      throw new IllegalArgumentException(
          "ExperienceGrowth.coefficientPerMille 不得为负: " + coefficientPerMille);
    }
    if (maxPerTickValue < 0L) {
      throw new IllegalArgumentException(
          "ExperienceGrowth.maxPerTickValue 不得为负: " + maxPerTickValue);
    }
  }
}
