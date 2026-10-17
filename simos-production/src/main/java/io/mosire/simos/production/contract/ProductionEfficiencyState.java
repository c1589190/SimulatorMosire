package io.mosire.simos.production.contract;

/**
 * ★★★ <b>单个生产实例的效率精度余数状态</b>（D-032 A 批：<b>按复核裁决 §3/§5 重建</b>）。
 *
 * <p>★★ <b>与旧 {@code [0,2000]‰} 面无关</b>（设计书 §2 S7、`...-experience-choice-design.md` §2.4 明文 「不得把现有
 * {@code [0,2000]‰} 接收口的历史限制直接冒充用户对新公式的数值上限」）：旧面的 {@code cycleModifierSumPerMille} 是"逐 tick
 * 注入的文化/行政修正之和"，那条通路（D2）已删； 本类型只承载<b>新公式</b>真正需要跨周期结转的余数（{@link #carry()}），<b>不</b>承载任何"上限 2000‰"
 * 的口径 —— ★ 也不承载熟练度加成上限：效率倍率是<b>配置</b>出来的（{@link ProductionEffectScope}）， 代码里没有"最多
 * 200%"这样的隐含天花板（复核裁决 §3 末条）。
 *
 * <p>★★ <b>旧字段已作废</b>（A 批）：{@code laborEfficiencyRemainderMilli}（"逐家户乘算"的余数）在 逐 tick
 * 累计口径下<b>不再需要</b>——tick 级的精确分数部分由 {@link ProductionExperienceFeature#remainder()} 结转，规模级的余数由
 * {@link PerCycleCarry} 结转。三字段旧形态与新形态<b>不是</b>同一件事的两种写法， 故本类型不再保留旧字段（留兼容位 = 第二份口径）。
 *
 * <p>★★ <b>A′ 批再删一段</b>（§13.8 裁定一/末段）：逐 tick 的"劳动 × 当刻效率"折算<b>不再截断</b> （{@link
 * CycleContribution#efficiencyWeightedLaborMilli()} 保留未除 1000 的精确分子）⇒ 原来按<b>实例</b> 存放的 {@code
 * tickLaborRemainderMilli} 没有存在理由（它正是"同一实例多户出工共享余数累加器"的偏松口径）。 现在本类型承载的是：{@link PerCycleCarry}
 * 的<b>三段</b>（劳动通道两段 + 效率倍率乘算一段）。
 *
 * <p>★ <b>缺行 = 全 0 余数</b>：不给全部实例建零行（同旧面纪律）；缺行与全零行逐值等价，故"算出全零 余数 ⇒ 不建行/移除该行"是可确定复算的。
 *
 * @param carry 三段余数（劳动通道两段 + 效率倍率乘算一段）；不得为 null
 */
public record ProductionEfficiencyState(PerCycleCarry carry) {

  /** ★ 缺行的显式物化形态（三段全 0）。 */
  public static final ProductionEfficiencyState ZERO =
      new ProductionEfficiencyState(PerCycleCarry.ZERO);

  public ProductionEfficiencyState {
    if (carry == null) {
      throw new IllegalArgumentException(
          "ProductionEfficiencyState.carry 不得为 null（全零请给 PerCycleCarry.ZERO）");
    }
  }

  /** ★ 三段余数是否全零（缺行与全零行逐值等价 ⇒ 这是"能不能不建行"的判据）。 */
  public boolean allZero() {
    return carry.allZero();
  }
}
