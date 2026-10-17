package io.mosire.simos.production.contract;

import java.util.Optional;

/**
 * ★★ <b>方案引用的经验条与公式</b>（D-032 约束设计书 §4.1；用户 U4「专门经验 a + 共用经验 b」）。
 *
 * <p>★★ <b>两个槽位就是用户原话里的 a 与 b</b>：
 *
 * <pre>
 * 用户 U4：一个生产方式本身的熟练度为 a，采用当前生产方式带来的直接熟练度为 a，
 *         但不止当前生产方式也能累加的另一个熟练度参数为 b，加成就可以是 1 + x1*a + x2*b
 * </pre>
 *
 * <ul>
 *   <li>{@link #primaryTrack()} = <b>a</b>：本方案的<b>专门</b>经验条（只有做这件事才涨）；
 *   <li>{@link #sharedTrack()} = <b>b</b>：<b>可共用</b>经验条（多个方案共同增长与读取；U4 明文要求）。
 * </ul>
 *
 * <p>★ <b>两个槽位都可缺</b>（{@code Optional.empty()}）：一个方案可以只挂 a、只挂 b、都不挂（纯劳动方案） 或都挂。缺槽位时，其熟练度输入按
 * <b>0</b> 参与公式——注意这与"公式求值失败"不是一回事（后者抛故障）。
 *
 * <p>★★ <b>效率倍率公式</b>（{@link #efficiencyScale()}）：输入名与量纲的唯一拼写点见下方常量； 用户例式在千分制下写成：
 *
 * <pre>
 * 1 + x*a              ⇒ Add(Const(1000), MulDiv(Input(X1), Input(PRIMARY_MASTERY), Const(1000)))
 * 1 + x1*a + x2*b      ⇒ Add(Add(Const(1000), MulDiv(...a...)), MulDiv(...b...))
 * </pre>
 *
 * <p>★★ <b>解锁门槛</b>（{@link #unlockAtPrimaryExperience()}）：家户在主经验条上的经验达到该值 ⇒ 获得 采用资格。★ <b>0 =
 * 不需要解锁</b>（一开始就可用）。★ 解锁<b>永久记忆</b>（I7）：经验衰减跌破门槛后 <b>不撤销</b>资格——因此本字段只参与"授予"判定，不参与"撤销"判定，见 {@code
 * ProductionData.unlocks} 与 {@code ProductionExperience} 的类注。
 *
 * @param primaryTrack 专门经验条 a；可缺
 * @param sharedTrack 可共用经验条 b；可缺
 * @param effectScope ★★ <b>A 批新增</b>：熟练度加成<b>作用在哪条通道</b>的显式声明；不得为 null （默认 {@link
 *     ProductionEffectScope#TOTAL} = 用户 U4 例式的"总生产效率倍率"语义，见复核裁决 §3）
 * @param efficiencyScale 熟练度 → 效率倍率（千分制；1000 = 不加成）；不得为 null
 * @param unlockAtPrimaryExperience 解锁门槛（主经验条上的经验值）；0 = 不需解锁；不得为负
 */
public record ProductionExperienceUse(
    Optional<ExperienceTrackId> primaryTrack,
    Optional<ExperienceTrackId> sharedTrack,
    ProductionEffectScope effectScope,
    ExpressionDefinition efficiencyScale,
    long unlockAtPrimaryExperience) {

  /** ★ 效率公式里"专门熟练度 a"输入的<b>唯一拼写点</b>（千分制）。 */
  public static final String PRIMARY_MASTERY_INPUT = "primary_mastery";

  /** ★ 效率公式里"共用熟练度 b"输入的<b>唯一拼写点</b>（千分制）。 */
  public static final String SHARED_MASTERY_INPUT = "shared_mastery";

  /** ★ 效率公式里"主经验条原始经验值"输入的<b>唯一拼写点</b>（供非线性公式直接用经验而非熟练度）。 */
  public static final String PRIMARY_EXPERIENCE_INPUT = "primary_experience";

  /** ★ <b>不使用经验</b>（既无 a 也无 b、效率恒为 1000‰、无需解锁）——"没配经验面"的显式中性值。 */
  public static final ProductionExperienceUse NONE =
      new ProductionExperienceUse(
          Optional.empty(),
          Optional.empty(),
          ProductionEffectScope.TOTAL,
          ExpressionDefinition.NEUTRAL_PER_MILLE,
          0L);

  public ProductionExperienceUse {
    if (primaryTrack == null) {
      throw new IllegalArgumentException(
          "ProductionExperienceUse.primaryTrack 不得为 null（无专门经验条请给 Optional.empty()）");
    }
    if (sharedTrack == null) {
      throw new IllegalArgumentException(
          "ProductionExperienceUse.sharedTrack 不得为 null（无共用经验条请给 Optional.empty()）");
    }
    if (effectScope == null) {
      throw new IllegalArgumentException(
          "ProductionExperienceUse.effectScope 不得为 null（默认总效率倍率请显式给 TOTAL）");
    }
    if (efficiencyScale == null) {
      throw new IllegalArgumentException("ProductionExperienceUse.efficiencyScale 不得为 null");
    }
    if (unlockAtPrimaryExperience < 0L) {
      throw new IllegalArgumentException(
          "ProductionExperienceUse.unlockAtPrimaryExperience 不得为负: " + unlockAtPrimaryExperience);
    }
    if (unlockAtPrimaryExperience > 0L && primaryTrack.isEmpty()) {
      throw new IllegalArgumentException(
          "ProductionExperienceUse 设了解锁门槛却没有专门经验条（门槛无处可量）: " + unlockAtPrimaryExperience);
    }
  }

  /** 本方案是否要读经验面（两个槽位都为缺 ⇒ 纯劳动方案，效率恒 1000‰）。 */
  public boolean usesExperience() {
    return primaryTrack.isPresent() || sharedTrack.isPresent();
  }

  /** 本方案是否需要解锁（门槛 > 0）。 */
  public boolean requiresUnlock() {
    return unlockAtPrimaryExperience > 0L;
  }

  /**
   * ★ <b>按熟练度求效率倍率</b>（千分制）——公式求值的<b>唯一入口</b>（量纲与故障语义见 {@link RestrictedExpression}）。
   *
   * @param primaryMastery 专门熟练度 a（千分制）；无 a ⇒ 传 0
   * @param sharedMastery 共用熟练度 b（千分制）；无 b ⇒ 传 0
   * @param primaryExperience 主经验条原始经验值；无 a ⇒ 传 0
   */
  public long efficiencyScalePerMille(
      long primaryMastery, long sharedMastery, long primaryExperience) {
    return efficiencyScale.evaluate(
        java.util.Map.of(
            PRIMARY_MASTERY_INPUT, primaryMastery,
            SHARED_MASTERY_INPUT, sharedMastery,
            PRIMARY_EXPERIENCE_INPUT, primaryExperience));
  }
}
