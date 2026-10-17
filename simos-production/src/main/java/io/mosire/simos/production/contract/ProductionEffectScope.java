package io.mosire.simos.production.contract;

/**
 * ★★★ <b>方案对"熟练度加成作用在哪条通道"的显式声明</b>——D-032 A 批效率公式修正的落形 （复核裁决 §3；设计书 §13.4 A 批第 1 条；★ A′ 批按 §13.8
 * <b>裁定一</b>改成"资源约束后整体乘一次"）。
 *
 * <p>★★ <b>为什么必须有这个声明</b>：复核裁决 §3 原文——「用户 U4 的例式是"总生产效率 = 总生产效率 ×
 * (1+x×熟练度)"；<b>默认实现必须能表达该总生产效率倍率，不能暗中固定为只提高劳动有效量</b>。方案若
 * <b>明确配置</b>某个效果作用于劳动效率，可按其声明计算；不能把该局部模型冒充所有方案的总效率加成。」
 *
 * <p>⇒ 本枚举就是那句"明确配置"的唯一拼写点：<b>默认值 {@link #TOTAL}</b>（总效率倍率）， 想要"只提高劳动效率"的方案必须<b>显式</b>声明 {@link
 * #LABOR}，且该声明会随方案定义持久化、被试算/实际/ 利润比较读同一份（{@code
 * IndustryDefinitionView.experience().effectScope()}）。
 *
 * <p>★★ <b>两条语义（千分制倍率 E，1000 = 不加成；三通道都不乘 E 先取基准规模）</b>：
 *
 * <pre>
 * 三通道（都不乘 E）：
 *   劳动规模 = ⌊(⌊(Σ实际劳动 + 余数) ÷ cycleDays⌋ + 余数) ÷ laborPerUnit⌋
 *   资产规模 = min_k ⌊实占资产_k ÷ capacityPerUnit_k⌋          // 物理占用，不乘 E
 *   原料规模 = min_j ⌊实扣原料_j ÷ inputPerUnit_j⌋             // 物理实扣，不乘 E
 * 基准规模 baseScale = min(三通道)
 *
 * TOTAL（默认）：E_cycle = ⌊Σ(劳动 × 当 tick 生效效率) ÷ Σ劳动⌋（劳动为 0 ⇒ 1000）
 *   finalScale = ⌊(baseScale × E_cycle + 余数) ÷ 1000⌋     ← ★ 唯一乘算点
 *   投入消耗与占用按 baseScale；产出/回执的实毛产按 finalScale
 *
 * LABOR（必须显式声明）：E_cycle 同上
 *   劳动通道规模先折算：劳动规模' = ⌊(劳动规模 × E_cycle + 余数) ÷ 1000⌋
 *   finalScale = baseScale = min(劳动规模', 资产规模, 原料规模)   ← 局部模型：只折算劳动那一路
 * </pre>
 *
 * <p>★★ <b>为什么 TOTAL 必须是"约束后整体乘一次"</b>（§13.8 裁定一）：把 E 乘进某一条通道再取 min， 在<b>另一条通道主导</b>时加成会整段归零 —— 复核
 * §3 的反例（劳动 100、资产 100、倍率 1.5 ⇒ min(150,100)=100，最终产出毫无加成）换成原料主导同样成立。基准规模是"资源允许做多少单位"，
 * 效率是"同样的资源做出多少产出"，故倍率只能作用在 min <b>之后</b>，且只作用一次。
 *
 * <p>★★ <b>两条语义都不"凭空多占"资源</b>（裁决 §3 原文「加成不意味着凭空多占土地、工具或原料」）： TOTAL 下投入消耗与占用按<b>基准规模</b>计（同样投入产出更多 =
 * 效率本义）；LABOR 下资产/原料通道本就 不折算。<b>不</b>新增任何资产/原料单位——不足一个整数规模的零头由 {@code PerCycleCarry} 跨周期结转
 * （不丢精度、也不凭空进整）。
 *
 * <p>★★ <b>{@link #LABOR} 的后果必须写明</b>（§13.8 裁定一末段）：它是"只折算劳动通道后再 min"的 <b>局部模型</b> ⇒
 * <b>瓶颈不在劳动时加成可能整段为 0</b>（例：劳动规模 100、资产规模 100、原料规模 300、E=1500 ⇒
 * min(150,100,300)=100，产出与不加成相同）。这是显式声明的预期后果，<b>不是</b>默认、 也<b>不是</b>"总效率倍率"的等价实现。
 *
 * <p>★★ <b>不得靠挪动加成位置让已配置效果失效</b>（裁决 §3 末句）：本枚举的值就是"位置"，它随方案定义 持久化并被<b>试算与执行共用同一个入口</b>（{@code
 * ProductionEfficiency}），改位置只能改配置、不能由 代码单方面挑选。
 */
public enum ProductionEffectScope {

  /**
   * ★★ <b>总生产效率倍率（默认）</b>：三通道都不乘 E 求 {@code baseScale}，再整体乘一次得 {@code finalScale}（用户 U4 例式的语义；§13.8
   * 裁定一原文"资源约束后的基准总产出 100 再应用 1.5 倍 效率则为 150"）。
   *
   * <p>★ 默认值就是"用户例式那个语义"——这是复核裁决 §3 要求的默认，不是可选优化。
   */
  TOTAL("total"),

  /**
   * ★ <b>只作用于劳动效率（必须显式声明）</b>：只折算劳动通道的规模上限，再与未折算的资产/原料通道取 min 的<b>局部模型</b>；瓶颈在别处时加成可能为 0（见类注末段）。
   *
   * <p>★ 它是合法配置，但<b>不是</b>默认：把它当所有方案的等价实现，正是复核裁决 §3 拒绝的那件事。
   */
  LABOR("labor");

  private final String key;

  ProductionEffectScope(String key) {
    this.key = key;
  }

  /** 线格式/载荷里的稳定键（不随枚举顺序变化）。 */
  public String key() {
    return key;
  }

  /** 解析稳定键；未知 ⇒ {@code null}（调用方决定拒绝口径：载荷侧具名拒，不静默取默认）。 */
  public static ProductionEffectScope fromKey(String key) {
    if (key == null) {
      return null;
    }
    for (ProductionEffectScope scope : values()) {
      if (scope.key.equals(key) || scope.name().equals(key)) {
        return scope;
      }
    }
    return null;
  }

  /**
   * ★★ 本声明是否把效率倍率作用在"三通道取 min <b>之后</b>的基准规模"上（{@link #TOTAL}）。
   *
   * <p>★ 它<b>不是</b>旧口径的 {@code appliesToAssets()}：A′ 批起 TOTAL 不再"分别乘劳动与资产通道"， 而是<b>只在 min
   * 之后乘一次</b>（§13.8 裁定一）；{@code false} 表示倍率只作用在劳动通道上 （{@link #LABOR} 的局部模型）。
   */
  public boolean scalesWholeBaseScale() {
    return this == TOTAL;
  }
}
