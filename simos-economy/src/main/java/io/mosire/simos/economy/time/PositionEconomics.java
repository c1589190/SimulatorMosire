package io.mosire.simos.economy.time;

/**
 * ★★★ <b>D-032 P3c1：M3 预期编排与 M6 实际编排共用的唯一位置收益语义</b>。
 *
 * <p><b>公式（两轮不得各写一份）</b>：
 *
 * <pre>
 * positionNetMilli            = expectedIncomeMilli − directInputCostMilli − wageCostMilli
 * positionYieldPerLaborScaled = floorDiv(positionNetMilli × 1_000_000, max(1, laborMilli))
 * </pre>
 *
 * <p>★ {@link #positionNetMilli()} 与 {@link #positionYieldPerLaborScaled()} <b>都可以为负</b>：这是候选筛选的输入，
 * 不是“状态里的余额”。M3/M6 都只把净收益为正的位置放进贪心候选；负值必须保留可读，不能在这里夹成 0（夹 0 会把 “亏本位置”伪装成“保本位置”）。
 *
 * <p>★ 溢出语义：净额按有序减法计算，只有在最终结果确实越过 {@code long} 下界时才饱和到 {@link Long#MIN_VALUE}； 每劳动收益的乘法越界时按符号饱和到
 * {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE}。正常量级的逐值结果与直接算式相同。
 *
 * <p>本类型是瞬态纯值：不落 {@code EconomyData}、不进 ChangeSet/Codec，也不提供任何状态改写方法。
 */
record PositionEconomics(
    long expectedIncomeMilli, long directInputCostMilli, long wageCostMilli, long laborMilli) {

  /** 每劳动收益的固定刻度：百万分之一。 */
  static final long PER_LABOR_SCALE = 1_000_000L;

  PositionEconomics {
    if (expectedIncomeMilli < 0L) {
      throw new IllegalArgumentException(
          "PositionEconomics.expectedIncomeMilli 不得为负: " + expectedIncomeMilli);
    }
    if (directInputCostMilli < 0L) {
      throw new IllegalArgumentException(
          "PositionEconomics.directInputCostMilli 不得为负: " + directInputCostMilli);
    }
    if (wageCostMilli < 0L) {
      throw new IllegalArgumentException("PositionEconomics.wageCostMilli 不得为负: " + wageCostMilli);
    }
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("PositionEconomics.laborMilli 不得为负: " + laborMilli);
    }
  }

  /**
   * 位置净收益：{@code expectedIncome − directInputCost − wageCost}。★ 可为负；M3/M6 共用。
   *
   * <p>先减直接投入再减工资：两个非负分量各自不可能让第一步溢出，第二步只有真正的最终下溢才饱和。
   */
  long positionNetMilli() {
    long afterDirectInput = Math.subtractExact(expectedIncomeMilli, directInputCostMilli);
    try {
      return Math.subtractExact(afterDirectInput, wageCostMilli);
    } catch (ArithmeticException overflow) {
      return Long.MIN_VALUE;
    }
  }

  /**
   * 单位劳动净收益（百万分之一刻度）：{@code floorDiv(positionNet × 1_000_000, max(1, laborMilli))}。
   *
   * <p>★ 可为负；M3/M6 必须调用本方法（或 {@link PositionPlan#fromEconomics}）得到排序键，不得在别处复制公式。 {@code laborMilli
   * == 0} 时按文档口径使用分母 1（零劳动组织者仍有可比较的收益读数）。
   */
  long positionYieldPerLaborScaled() {
    return scaledYield(positionNetMilli(), laborMilli);
  }

  /** 无分配、整数、floor 语义的收益缩放；越界按符号饱和。 */
  static long scaledYield(long positionNetMilli, long laborMilli) {
    long denominator = Math.max(1L, laborMilli);
    try {
      return Math.floorDiv(Math.multiplyExact(positionNetMilli, PER_LABOR_SCALE), denominator);
    } catch (ArithmeticException overflow) {
      // 分母 ≥ 1 ⇒ 真商必然越过同一侧的 long 边界，饱和值就是该侧极值。
      return positionNetMilli >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
