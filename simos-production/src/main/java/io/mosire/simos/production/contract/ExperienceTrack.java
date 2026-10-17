package io.mosire.simos.production.contract;

/**
 * ★★ <b>经验条定义</b>（D-032 约束设计书 §4.1；用户 U2/U4）：家户在某个生产门类上积累的<b>经验</b>， 以及由它解释出的<b>熟练度</b>。
 *
 * <p>★★ <b>经验与熟练度是两件事</b>（用户 §2.2 原文：「经验与由它参与公式的"熟练度"须能清楚区分； 用户没有把熟练度数值域冻结为 0～1、千分制或原始经验值」）：
 *
 * <ul>
 *   <li>{@link #maxExperience()} / 家户经验账里记的是<b>经验值</b>（本模块的原始计数）；
 *   <li>{@link #masteryFormula()} 把经验值解释成<b>熟练度</b>——映射<b>不硬编码</b> 0–1 或千分制，由公式给出。
 * </ul>
 *
 * <p>★★ <b>本仓对熟练度的唯一约定是"千分数"</b>（见 {@link RestrictedExpression} 类注的量纲口径）： 好处是加成公式 {@code 1 + x*a}
 * 可以直接写成整数千分乘算，不需要任何浮点。若将来某个经验条想用别的 数值域，改的是它的 {@code
 * masteryFormula}（公式），<b>不是</b>这里的字段——类型面不为某一档数值域开口子。
 *
 * <p>★★ <b>衰减只降熟练度/加成，不撤销解锁</b>（约束设计书 I7；用户 U10「肯定算数」）： 本类型只描述"每 tick
 * 掉多少经验"；"掉了之后已解锁还算不算"是账本与解锁集合的事， 见 {@code ProductionData.unlocks} 的类注。
 *
 * <p>★ <b>参数是"可调默认值"</b>（设计书 §11 控制方工程取舍）：数值由创世/命令配置，本类型不写死任何常量。
 *
 * @param id 稳定身份；不得为 null
 * @param name 展示名；非空白
 * @param maxExperience 经验上限（达到即"到顶"，不再增长）；必须 ≥ 1
 * @param growthPerLaborMilli 增长换算：每 <b>千分劳动·日</b> 的实际生产贡献带来多少经验；必须 ≥ 0
 * @param decayPerTick 衰减规则：每 tick 衰减多少经验；必须 ≥ 0（0 = 不衰减）
 * @param masteryFormula 熟练度解释：输入名 {@code experience}（见 {@link #EXPERIENCE_INPUT}）→ 熟练度（千分制约定）
 */
public record ExperienceTrack(
    ExperienceTrackId id,
    String name,
    long maxExperience,
    long growthPerLaborMilli,
    long decayPerTick,
    ExpressionDefinition masteryFormula) {

  /** ★ 熟练度公式里"经验值"那一路输入的<b>唯一拼写点</b>。 */
  public static final String EXPERIENCE_INPUT = "experience";

  public ExperienceTrack {
    if (id == null) {
      throw new IllegalArgumentException("ExperienceTrack.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ExperienceTrack.name 不得为空白");
    }
    if (maxExperience < 1L) {
      throw new IllegalArgumentException("ExperienceTrack.maxExperience 必须 ≥ 1: " + maxExperience);
    }
    if (growthPerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ExperienceTrack.growthPerLaborMilli 不得为负: " + growthPerLaborMilli);
    }
    if (decayPerTick < 0L) {
      throw new IllegalArgumentException("ExperienceTrack.decayPerTick 不得为负: " + decayPerTick);
    }
    if (masteryFormula == null) {
      throw new IllegalArgumentException("ExperienceTrack.masteryFormula 不得为 null");
    }
  }

  /**
   * ★ <b>经验 → 熟练度</b>（唯一解释点）。
   *
   * <p>★ 求值错误（公式引用未声明输入、除零、溢出）⇒ {@link ProductionContractFault}， <b>不</b>退化成中性（I15）。
   */
  public long masteryOf(long experience) {
    return masteryFormula.evaluate(java.util.Map.of(EXPERIENCE_INPUT, experience));
  }

  /** 把经验值夹进本经验条的有效域 {@code [0, maxExperience]}（增长/衰减的写回形制）。 */
  public long clampExperience(long experience) {
    if (experience < 0L) {
      return 0L;
    }
    return Math.min(experience, maxExperience);
  }
}
