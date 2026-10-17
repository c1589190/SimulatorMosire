package io.mosire.simos.production.contract;

/**
 * ★★★ <b>一条家户经验账的读模型 = 主值 + 精度余数 + 当刻熟练度</b>（D-032 A 批；复核裁决 §4）。
 *
 * <p>★ <b>为什么需要它</b>：{@code ProductionRuntime.experienceOf(...)} 只回一个 {@code long} 时， "余数"这个 A
 * 批新增的完整特征就没法被外部读到（迁移/合户要携带它、对账要核它）。本记录是那条读口的
 * <b>返回形状</b>：主值、余数、由主值解释出的熟练度三件一起给，且三者同源（同一个经验条定义、同一份状态）。
 *
 * <p>★ <b>它不是状态</b>：状态是 {@code ProductionData.householdExperience}（{@code HouseholdExperienceKey} →
 * {@link ProductionExperienceFeature}）；本记录只是那行的投影。
 *
 * @param feature 经验主值 + 精度余数（完整特征；迁移/合户必须整条搬，见 {@link ProductionExperienceFeature}）
 * @param masteryPerMille 由 {@code feature.value()} 经该经验条的熟练度公式解释出的熟练度（千分制）； 经验条未登记 ⇒
 *     0（"还没这门技术"，不是错误）
 */
public record ExperienceState(ProductionExperienceFeature feature, long masteryPerMille) {

  public ExperienceState {
    if (feature == null) {
      throw new IllegalArgumentException("ExperienceState.feature 不得为 null");
    }
    if (masteryPerMille < 0L) {
      throw new IllegalArgumentException(
          "ExperienceState.masteryPerMille 不得为负: " + masteryPerMille);
    }
  }

  /** ★ 空状态（经验 0、余数 0、熟练度 0）——"没有这一行"的等价读模型。 */
  public static final ExperienceState EMPTY =
      new ExperienceState(ProductionExperienceFeature.ZERO, 0L);

  /** 经验主值。 */
  public long value() {
    return feature.value();
  }

  /** 精度余数（千分之一经验）。 */
  public long remainder() {
    return feature.remainder();
  }
}
