package io.mosire.simos.production.contract;

/**
 * ★★★ <b>一条家户经验账的完整特征 = 经验主值 + 精度余数</b>——D-032 A 批「经验精度」修正的落形 （复核裁决 §4；设计书 §13.4 A 批第 2 条）。
 *
 * <p>★★ <b>它替换掉的旧行为</b>（复核裁决 §4 原文）：旧增长式 {@code floor(laborMilli × growthRate ÷ 1000)}
 * <b>逐次截断、不结转</b>⇒ 增长率 1、每次投入 100 毫劳动时每次结果都是 0，重复十次仍为 0，而合并后应有 1。 「这是系统性丢失，不会靠'日积月累'补回来。」⇒
 * 余数必须<b>结转</b>，且要跟着家户/迁移一起走。
 *
 * <p>★★ <b>精度的唯一拼写点</b>：
 *
 * <pre>
 * 一次实际贡献的<b>精确</b>增量（千分定点） = 劳动(千分劳动·日) × 增长换算 ÷ 1000
 *   exactNumerator = 劳动 × growthPerLaborMilli        // 整数，不截断
 *   exactWhole     = floorDiv(exactNumerator, 1000)
 *   exactFraction  = exactNumerator mod 1000           // ∈ [0, 1000)
 * 写回：main += exactWhole + carry(main 侧，见 {@link ExperienceState})；remainder = exactFraction
 * </pre>
 *
 * <p>★★ <b>为什么不是"商 + 商的小数"</b>：若把 {@code exactWhole} 再拆成商和尾数，多次累计会与一次性累计 差
 * 1（逐次下取整的固有损失）。本记录记的是<b>未截断分子</b>的那个"分数部分"（{@code exactFraction} ∈ [0,1000)） 与"已经进到主值的整数部分"（{@code
 * value}）⇒ 对账口径（复核 §4）严格成立：
 *
 * <pre>
 * 零衰减、未触上限、参数相同时，同一批实际劳动 L（拆成 L₁+…+Lₙ）：
 *   Σᵢ floor(Lᵢ × g ÷ 1000)  +  最终余数 ÷ 1000  ==  L × g ÷ 1000   （精确等式）
 * 即：{@code Σ(主值增量) + 最终分数的千分表示 == 精确总量}（不丢、不重）。
 * </pre>
 *
 * <p>★★ <b>边界处余数的处理必须明确</b>（复核 §4 第 2 条）：
 *
 * <ul>
 *   <li><b>到上限</b>（{@code value == maxExperience}）：<b>不再增长</b>，且余数照常按每次贡献的精确分数 重置 ⇒
 *       不把"本该溢出的整数"藏进余数（没有超上限的经验）；退还的那份整数不再参与后续增长， 直到主值因衰减离开上限（那时只有新的实际贡献+新余数能增长，<b>不</b>补发旧零头）；
 *   <li><b>归零</b>（衰减到 0）：{@code value = 0}，{@code remainder} <b>原样保留</b>（它记的是已发生的
 *       实际劳动的分数部分，不是"旧零头"）；<b>不</b>重新授予已经进过主值的整数部分；
 *   <li><b>迁移/合户</b>（I10）：携带的是<b>本整条特征</b>（主值 + 余数），不是只搬主值——只搬主值就是 把余数扔掉（复核 §4 第 3 条明禁）。
 * </ul>
 *
 * @param value 经验主值（整数经验点）；∈ [0, 该经验条的 maxExperience]
 * @param remainder 精度余数（"已发生但还没凑成一个整经验点"的分数部分，以千分之一经验为单位）；∈ [0, 1000)
 */
public record ProductionExperienceFeature(long value, long remainder) {

  /** ★ 余数的模 = 1/1000 经验（与 {@code growthPerLaborMilli} 的"每千分劳动"同一千分定点约定）。 */
  public static final long REMAINDER_MODULUS = 1_000L;

  /** 零特征（"还没这门经验"的显式物化形态）。 */
  public static final ProductionExperienceFeature ZERO = new ProductionExperienceFeature(0L, 0L);

  public ProductionExperienceFeature {
    if (value < 0L) {
      throw new IllegalArgumentException("ProductionExperienceFeature.value 不得为负: " + value);
    }
    if (remainder < 0L || remainder >= REMAINDER_MODULUS) {
      throw new IllegalArgumentException(
          "ProductionExperienceFeature.remainder 必须 ∈ [0, "
              + REMAINDER_MODULUS
              + "): "
              + remainder);
    }
  }

  /** ★ 主值与余数是否都为 0（"缺行"与"全零行"逐值等价 ⇒ 这是能不能不建行的判据）。 */
  public boolean allZero() {
    return value == 0L && remainder == 0L;
  }

  /** ★ 余数换算成"经验的千分之一"（读数/对账用；不参与状态）。 */
  public long remainderPerMille() {
    return remainder;
  }
}
