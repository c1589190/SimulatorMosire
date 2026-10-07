package io.mosire.simos.gov;

/**
 * 官吏工资规则（Z2，设计书 §4.1 / §9）：<b>每承诺小时</b>发多少粮/银。
 *
 * <p>★ <b>量纲与逐值口径</b>：承诺劳动在 {@code HouseholdLaborCommitment.laborMilli} 里是<b>毫小时/tick</b>；这里的速率是
 * <b>毫（粮/银）/承诺小时</b>。Z3 的发薪按 {@code floorDiv(multiplyExact(committedLaborMilli, rate), 1000)}
 * 逐条腿折算（本类型只存规则，不做支付）。
 *
 * <p>★ <b>不封顶 + 保序</b>：两个速率 ≥ 0、无上界；{@link #zero()} = 不发薪（中性默认）。粮与银各自独立，允许只发其中一种 （另一项为 0）。
 *
 * @param grainMilliPerCommittedHour 每承诺小时的粮（毫粮/承诺小时；≥ 0）
 * @param silverMilliPerCommittedHour 每承诺小时的银（毫银/承诺小时；≥ 0）
 */
public record GovOfficialSalaryRule(
    long grainMilliPerCommittedHour, long silverMilliPerCommittedHour) {

  /** 不发薪的中性规则（0/0）。 */
  public static final GovOfficialSalaryRule ZERO = new GovOfficialSalaryRule(0L, 0L);

  public GovOfficialSalaryRule {
    requireNonNegative(grainMilliPerCommittedHour, "grainMilliPerCommittedHour");
    requireNonNegative(silverMilliPerCommittedHour, "silverMilliPerCommittedHour");
  }

  /** 不发薪的中性规则（与 {@link #ZERO} 同值；工厂便于载荷缺省处表达意图）。 */
  public static GovOfficialSalaryRule zero() {
    return ZERO;
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
