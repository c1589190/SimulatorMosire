package io.mosire.simos.gov;

/**
 * 一条预算类别的下限/上限（Z2，设计书 §4.1 / §9）。
 *
 * <p>★ <b>量纲</b>：与国库计价单位一致（当前世界 = 毫银），但本类型不解释单位——Z3 的预算执行器负责把 min/cap 落到具体腿。{@link #minPerCycle()}
 * 是“本周期至少留/付多少”，{@link #capPerCycle()} 是“本周期至多多少”； {@code capPerCycle == Long.MAX_VALUE}
 * 是写下来的“不封顶”哨兵（命令载荷缺省 cap 时也用这个值）。
 *
 * <p>★ <b>构造期校验</b>：{@code category} 非 null；两个值 ≥ 0；{@code minPerCycle ≤ capPerCycle}。越界当场抛 {@link
 * IllegalArgumentException}。
 *
 * @param category 支出类别（非 null）
 * @param minPerCycle 本周期下限（≥ 0，不封顶）
 * @param capPerCycle 本周期上限（≥ minPerCycle；{@code Long.MAX_VALUE} = 不封顶）
 */
public record GovBudgetLine(GovBudgetCategory category, long minPerCycle, long capPerCycle) {

  public GovBudgetLine {
    if (category == null) {
      throw new IllegalArgumentException("category 不得为 null");
    }
    requireNonNegative(minPerCycle, "minPerCycle");
    requireNonNegative(capPerCycle, "capPerCycle");
    if (minPerCycle > capPerCycle) {
      throw new IllegalArgumentException(
          "minPerCycle 不得超过 capPerCycle: min=" + minPerCycle + " cap=" + capPerCycle);
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
