package io.mosire.simos.util.economy;

import java.math.BigInteger;

/**
 * ★★ **按权重成比例切分 + 最大余数法分派残差**（全仓**唯一**实现；v2 spec §八.7）。
 *
 * <p>★★ **为什么要收在一处**：v1 有**三份**同族的定点切分（{@code EconomySeeder.splitByShares}、 {@code
 * EconomySeeder.splitProportional}、{@code EconomySettlement.allocate}），残差规则**各不相同** ——{@code
 * splitByShares}/{@code splitProportional} 按**下标序**逐个 +1，{@code allocate} 按**索引序均摊** （{@code
 * remainder / n} 人人有份）。spec §八.7 点名后者："把权重和的缺口**平均分给每一行**"⇒ 按格净产 5,950,000 算， 地主实得是**按权重应得的 7
 * 倍**（2,975 vs 429），与"制度分配决定谁得多少"**方向相反**。
 *
 * <p>★★ **规则（本节即口径）**：{@code parts[i] = total × weight[i] ÷ denominator}（向下取整），随后把残差 {@code total −
 * Σparts} 按**最大余数法**分派 —— 余数 {@code total × weight[i] mod denominator} **大者先得**，
 * **同余数按下标序**；残差多于一项时先人人摊 {@code remainder ÷ n}，再按上述次序给前 {@code remainder mod n} 名各 +1。
 *
 * <p>★ **分母各自不动**（有意）：{@code splitByShares} 的分母恒 **1000‰**（并保留其**故意的**报警行为：比例表被改坏 ⇒ {@code Σ ≠
 * total}，由调用方的守恒断言当场红 —— **绝不静默归一化**）；{@code splitProportional}/{@code allocate} 的分母是
 * **Σ权重**。统一的是**残差规则**，不是分母。
 *
 * <p>★ **{@code Σparts ≥ total} 时不静默修正**（比例表被改坏时 {@code remainder < 0}）：原样返回 ⇒ 调用方的守恒判据看得见。
 *
 * <p>★ **整数运算、禁 double**（v2 spec §7 的量纲纪律）；权重与总量都不得为负（调用方各自校验并抛具名异常）。
 *
 * <p>★★ <b>R0（S0.2）：{@code total × weight} 不再裸乘</b>——快路 {@code Math.multiplyExact}，溢出进 {@code
 * BigInteger} 分支；{@code Σparts} 用 {@code Math.addExact} 安全累加。外部语义（floor + 最大余数法、 同余数按下标升序、{@code
 * denominator == 0} 记第一项、负残差不修正）逐条不变。
 */
public final class ProportionalSplit {

  private ProportionalSplit() {}

  /**
   * 把 {@code total} 按 {@code weights} 成比例切成同长子表，**Σ结果 == total**（残差按最大余数法分派）。
   *
   * @param total 待切分的总量（最小计量单位）；不得为负
   * @param weights 权重（非负；语义由调用方定：千分比例表或任意权重）
   * @param denominator 分母（{@code weights} 的量纲基准；**0 ⇒ 权重全为 0**，整份记在第一项，不除零）
   * @return 与 {@code weights} 等长的新数组
   * @throws IllegalArgumentException {@code weights} 为空而 {@code total != 0}（没有可承载的槽位）
   */
  public static long[] byDenominator(long total, long[] weights, long denominator) {
    int n = weights.length;
    if (n == 0) {
      if (total != 0L) {
        throw new IllegalArgumentException("没有可承载的槽位，但 total = " + total);
      }
      return new long[0];
    }
    long[] parts = new long[n];
    if (denominator == 0L) {
      // 权重全为 0：分母为 0 ⇒ 整份记在第一项（不丢总量、也不做除零）。
      parts[0] = total;
      return parts;
    }
    long[] residues = new long[n];
    long assigned = 0L;
    // ★★ R0：`assigned` 必须用安全累加（`Math.addExact`）——溢出不再静默回绕。真溢出的输入是坏数据/极端状态，
    //   此时改用 BigInteger 精确累加，让"残差是否 ≤ 0"的判断仍按数学值走（不把回绕后的负数当成真残差）。
    BigInteger assignedExact = null;
    for (int i = 0; i < n; i++) {
      long[] share = mulDivParts(total, weights[i], denominator);
      parts[i] = share[0];
      residues[i] = share[1]; // 小数部分的分子：精确份额 = parts[i] + residues[i]/denominator
      if (assignedExact != null) {
        assignedExact = assignedExact.add(BigInteger.valueOf(share[0]));
      } else {
        try {
          assigned = Math.addExact(assigned, share[0]);
        } catch (ArithmeticException overflow) {
          assignedExact = BigInteger.valueOf(assigned).add(BigInteger.valueOf(share[0]));
        }
      }
    }
    long remainder;
    if (assignedExact == null) {
      remainder = total - assigned;
    } else {
      BigInteger exactRemainder = BigInteger.valueOf(total).subtract(assignedExact);
      if (exactRemainder.signum() <= 0) {
        return parts; // 与旧语义一致：Σparts ≥ total ⇒ 原样返回、不静默修正（见类注）。
      }
      try {
        remainder = exactRemainder.longValueExact();
      } catch (ArithmeticException tooLarge) {
        throw new ArithmeticException(
            "ProportionalSplit 的残差超出 long（total=" + total + "，已分配=" + assignedExact + "）；拒绝静默回绕");
      }
    }
    distributeByLargestRemainder(parts, residues, remainder);
    return parts;
  }

  /**
   * ★★ <b>R0：单槽位的 {@code total × weight ÷ denominator}（floor）与余数</b>。
   *
   * <p>★ 常数级小权重走 {@code long} 快路；只有 {@code Math.multiplyExact} 当场溢出才进 {@code BigInteger} fallback（见
   * S0.2 的算式）。商超出 {@code long} ⇒ {@link BigInteger#longValueExact()} 抛具名异常 ——
   * <b>宁抛不静默</b>（回绕会造出负数份额，正是本修复要消灭的形态）。分母为 0 不走这里（上面已单独处理）。
   */
  private static long[] mulDivParts(long total, long weight, long denominator) {
    if (weight == 0L) {
      return new long[] {0L, 0L};
    }
    try {
      long product = Math.multiplyExact(total, weight);
      return new long[] {product / denominator, product % denominator};
    } catch (ArithmeticException overflow) {
      BigInteger[] quotientAndRemainder =
          BigInteger.valueOf(total)
              .multiply(BigInteger.valueOf(weight))
              .divideAndRemainder(BigInteger.valueOf(denominator));
      return new long[] {
        quotientAndRemainder[0].longValueExact(), quotientAndRemainder[1].longValueExact()
      };
    }
  }

  /**
   * 残差分派：先人人摊 {@code remainder / n}，再按**余数降序（同余数下标升序）**给前 {@code remainder % n} 名各 +1。
   *
   * <p>★ {@code remainder <= 0} ⇒ **原样返回**：负残差意味着 {@code Σ权重 > 分母}（比例表被改坏），
   * 静默修正会把这个错误伪装成"一切正常"（本仓最忌的那一族）⇒ 让调用方的守恒判据当场红。
   */
  private static void distributeByLargestRemainder(long[] parts, long[] residues, long remainder) {
    if (remainder <= 0L) {
      return;
    }
    int n = parts.length;
    long share = remainder / n;
    for (int i = 0; i < n; i++) {
      parts[i] += share;
    }
    int extra = (int) (remainder % n);
    if (extra == 0) {
      return;
    }
    int[] order = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    // 插入排序：余数**降序**、同余数**下标升序**（n = 槽位数，很小；不装箱、不依赖库排序的稳定性）。
    for (int i = 1; i < n; i++) {
      int current = order[i];
      int j = i - 1;
      while (j >= 0 && residues[order[j]] < residues[current]) {
        order[j + 1] = order[j];
        j--;
      }
      order[j + 1] = current;
    }
    for (int rank = 0; rank < extra; rank++) {
      parts[order[rank]]++;
    }
  }
}
