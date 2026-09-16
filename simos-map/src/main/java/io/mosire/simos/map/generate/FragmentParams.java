package io.mosire.simos.map.generate;

/**
 * 碎片脊线的**形状参数**，外加"碎片预算怎么切"的规则与那条**可为负的差值**的守卫。
 *
 * <p>★ 形状字段来自 GSimulator 的 {@code MapGenerator.placeRidges} 第三个循环（`:104-117`）； 切分规则来自同一方法的 {@code
 * :85} 与 {@code :105}。**本任务没有新造任何数值。**
 *
 * <p>★ **为什么切分规则在本类型而不在 {@link RidgeParams}**：GSimulator 的入参 {@code fragmentCount}
 * 是**碎片预算的总数**，它被切成两半 —— 一半摆成**次级脊线** （{@code secondary = Math.max(2, fragmentCount /
 * 2)}，`:85`），剩下的才摆成**碎片** （{@code frags = fragmentCount - secondary}，`:105`）。次级脊线的**形状**归 {@link
 * RidgeParams}，但"预算切多少给它"是碎片这一侧的事，故连同守卫一起放这里。
 *
 * <p>★ **那条差值为什么必须显式校验**（spec §6.4）：{@code fragmentCount - secondary} **可以为负** —— 传 {@code
 * fragmentCount = 1} 时 {@code secondary = max(2, 0) = 2}， 差值为 {@code -1}；而 GSimulator 那边它只是让 {@code
 * for (int i = 0; i < frags; i++)} **一次都不执行**，于是"我要 1 条碎片"变成了"一条碎片都没有"，**不抛任何异常**。
 * 这就是本项目反复付代价的"静默"模式，故 {@link #requireNonNegativeRemaining} 在**构造期**把它挡住。
 *
 * <p>★ **注意 {@code fragments} 本身不是本类型的组件**：它是 {@link GenerationSpec} 的顶层参数 （签名见 spec
 * §6.4），本类型只持有"拿到一个总数之后怎么切"的规则与守卫 —— 于是这个数在全局 **只有一份**，不会出现"spec 里写着 5、params 里写着 3"的漂移。
 *
 * <p>★ **U1**：本类型一个 String 组件都没有，也不含任何高度分界。
 *
 * @param distMin 碎片中心距图心的距离下界（`MapGenerator.java:108` 的 {@code radius * (0.35 + …)}）
 * @param distSpan 距离的随机跨度（`:108` 的 {@code rng.nextDouble() * 0.50}）
 * @param lenMin 碎片长度下界（`:111` 的 {@code radius * (0.04 + …)}）
 * @param lenSpan 长度的随机跨度（`:111` 的 {@code rng.nextDouble() * 0.08}）
 * @param angleJitter 碎片朝向的高斯抖动（`:112` 的 {@code rng.nextGaussian() * 0.5}）
 * @param tipLength 两端各占全长的比例（`:114-115` 的 {@code flen * 0.5}）
 * @param weightMin 碎片权重下界（`:116` 的 {@code 0.10 + …}）
 * @param weightSpan 碎片权重的随机跨度（`:116` 的 {@code rng.nextDouble() * 0.15}）
 * @param secondaryCountFloor 次级脊线条数的下取整下限（`:85` 的 {@code Math.max(2, …)}）
 * @param secondaryCountDivisor 次级脊线条数的除数（`:85` 的 {@code fragmentCount / 2} 里的 2）
 */
public record FragmentParams(
    double distMin,
    double distSpan,
    double lenMin,
    double lenSpan,
    double angleJitter,
    double tipLength,
    double weightMin,
    double weightSpan,
    int secondaryCountFloor,
    int secondaryCountDivisor) {

  /**
   * 由碎片总数切给**次级脊线**的条数：{@code max(floor, fragments / divisor)}（GSimulator `MapGenerator.java:85`）。
   */
  public int secondaryCount(int fragments) {
    return Math.max(secondaryCountFloor, fragments / secondaryCountDivisor);
  }

  /** 切完次级脊线之后剩下的**碎片条数**。★ **可为负** —— 这正是 GSimulator 静默吞掉的那个值。 */
  public int remainingCount(int fragments) {
    return fragments - secondaryCount(fragments);
  }

  /**
   * ★ **构造期守卫**：剩余条数不得为负，否则抛、**不静默吞掉**。
   *
   * <p>消息里同时印出三个数（总数 / 切走的次级数 / 剩余），因为"为什么 1 条不够"单看总数看不出来 —— 次级脊线的下限是 {@code
   * secondaryCountFloor}，与总数无关。
   *
   * @return 剩余的碎片条数（≥ 0），供生成器直接用，免得再算一遍
   */
  public int requireNonNegativeRemaining(int fragments) {
    int secondary = secondaryCount(fragments);
    int remaining = fragments - secondary;
    if (remaining < 0) {
      throw new IllegalArgumentException(
          "fragments 不足以供给次级脊线：fragments=" + fragments + "，次级脊线=" + secondary + "，剩余=" + remaining);
    }
    return remaining;
  }
}
