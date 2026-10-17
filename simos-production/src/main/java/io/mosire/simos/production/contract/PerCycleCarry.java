package io.mosire.simos.production.contract;

/**
 * ★★ <b>多周期下"分不出来"的余数（劳动通道两段 + 效率倍率乘算一段）</b>——D-032 A 批效率公式修正的落形 （复核裁决 §3、§5；设计书 §13.4 A 批第 1/3 条；★
 * A′ 批按 §13.8 裁定一改成"约束后整体乘一次"）。
 *
 * <p>★★ <b>为什么必须结转</b>：规模是<b>整数</b>（"多少单位规模"），而周期→日均、日均→规模、倍率乘算 三级都是整数除法。不结转 ⇒ 每次差一点点的零头被系统性丢掉；结转 ⇒
 * 长期不再丢精度（这是旧 {@code ProductionEfficiencyBook} 里 {@code laborDayRemainderMilli}/{@code
 * laborScaleRemainderMilli} 的机制，本批<b>保留机制、更新输入</b>）。
 *
 * <p>★★ <b>三段余数、各有物理含义与有效域</b>（越域 ⇒ 契约故障 ERROR，不截断、不降级）：
 *
 * <pre>
 * ① 劳动通道：(Σ 实际劳动 + ①) ÷ cycleDays            的余数   ∈ [0, cycleDays)
 * ② 劳动通道：(日均劳动 + ②) ÷ laborPerUnit           的余数   ∈ [0, laborPerUnit) （laborPerUnit == 0 ⇒ 必为 0）
 * ③ 倍率乘算：((被作用的那一路基准规模 × 效率倍率) + ③) 的余数   ∈ [0, 1000)
 * </pre>
 *
 * <p>★★ <b>A′ 批删掉了"逐 tick 折算"那一段</b>（原 {@code tickLaborRemainderMilli}）：那条余数是 "劳动 × 当刻效率 ÷
 * 1000"的零头，按<b>实例</b>存放 ⇒ 同一实例多户出工时共享一个累加器（口径偏松， 见设计书 §13.8 末段）。现在逐 tick 的加权劳动<b>不除 1000、不截断</b>地累进
 * {@link CycleContribution#efficiencyWeightedLaborMilli()}，折算只在关账时做一次 ⇒ 该段余数不再存在
 * （不是"留了兼容位"，是<b>没有可丢的零头</b>）。
 *
 * <p>★ <b>③ 的"被作用的那一路"取决于方案声明</b>（{@link ProductionEffectScope}）：{@code TOTAL} 作用在 三通道取 min
 * <b>之后</b>的基准规模上（{@code finalScale = ⌊baseScale × E ÷ 1000⌋}）；{@code LABOR} 只作用在 劳动通道上。两种情形的余数模都是
 * 1000（都是千分制乘算），故同一段承载 —— ★ 方案改声明（配置变更）时 余数依旧 ∈ [0,1000)、依旧可复算，不产生越域。
 *
 * <p>★ <b>缺行 = 全 0 余数</b>：不给全部实例建零行（同旧面纪律）；缺行与全零行逐值等价，故"算出全零 余数 ⇒ 不建行/移除该行"是可确定复算的。
 *
 * <p>★★ <b>有效域里的 {@code cycleDays}/{@code laborPerUnit} 是 per-plan 值</b>，本类型看不见它们 ⇒
 * 校验只能在<b>使用处</b>做（{@code ProductionEfficiency.requireValidCarry}），不在 record 构造期。
 *
 * @param laborDayRemainderMilli ① 劳动通道"周期实际劳动 ÷ cycleDays"的余数；有效域 {@code [0, cycleDays)}
 * @param laborScaleRemainderMilli ② 劳动通道"日均劳动 ÷ laborPerUnit"的余数；有效域 {@code [0, laborPerUnit)}
 * @param efficiencyRemainderMilli ③ 效率倍率乘算的余数（TOTAL：基准规模那一路；LABOR：劳动通道那一路）； 有效域 {@code [0, 1000)}
 */
public record PerCycleCarry(
    long laborDayRemainderMilli, long laborScaleRemainderMilli, long efficiencyRemainderMilli) {

  /** ★ 效率倍率乘算余数的模（千分制乘算的模；与 {@link ProductionEffectScope} 的倍率千分制同源）。 */
  public static final long EFFICIENCY_REMAINDER_MODULUS = 1_000L;

  /** ★ 缺行的显式物化形态（三段全 0）。 */
  public static final PerCycleCarry ZERO = new PerCycleCarry(0L, 0L, 0L);

  public PerCycleCarry {
    if (laborDayRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "PerCycleCarry.laborDayRemainderMilli 不得为负: " + laborDayRemainderMilli);
    }
    if (laborScaleRemainderMilli < 0L) {
      throw new IllegalArgumentException(
          "PerCycleCarry.laborScaleRemainderMilli 不得为负: " + laborScaleRemainderMilli);
    }
    if (efficiencyRemainderMilli < 0L || efficiencyRemainderMilli >= EFFICIENCY_REMAINDER_MODULUS) {
      throw new IllegalArgumentException(
          "PerCycleCarry.efficiencyRemainderMilli 必须 ∈ [0, "
              + EFFICIENCY_REMAINDER_MODULUS
              + "): "
              + efficiencyRemainderMilli);
    }
  }

  /** ★ 三段是否全零（"缺行"与"全零行"逐值等价 ⇒ 这是能不能不建行的判据）。 */
  public boolean allZero() {
    return laborDayRemainderMilli == 0L
        && laborScaleRemainderMilli == 0L
        && efficiencyRemainderMilli == 0L;
  }
}
