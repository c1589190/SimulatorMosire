package io.mosire.simos.social.population;

/**
 * ★★ **该格人口读数的来源**（R2 的 T0，控制器已裁定）：批次（真值源）还是旧序列（回退）。
 *
 * <p>★★ **为什么这一维必须读得出来**：R1 起人口的真值源是 {@code groups}（{@link PopulationGroup}），而**旧世界（随包 bootstrap 的
 * {@code worlds/v17levant.json} 与升级前落盘的每条 revision）只有旧序列** —— 一律读批次会把"世界还没初始化"显示成"这一格没人"（0 与"没有数据"
 * 在界面上长得一模一样）。故回退是口径的一部分，而**用的是哪一个**必须跟着数字一起发出去： 两个口径的数字共用一个名字（{@code population}）而不标来源，正是 R1.5
 * 留下的那处"同一资源两个形状"。
 *
 * <p>★ {@link #key()} 就是它的稳定拼写（读口按它发键）：它进 JSON，故不做大小写转换（与 {@link AgeBracket#key()} 同款约定）。
 */
public enum PopulationSource {

  /** 批次求和：该格有批次 ⇒ 批次是**唯一**真值源（读口报的 {@code population} = Σ 该格各批次的 {@code count}）。 */
  BATCHES("batches"),

  /** 回退旧序列：该格**没有批次** ⇒ 读口报的是农村人口序列在查询时刻的取值（旧口径，唯一还能用的那一份）。 */
  LEGACY_SERIES("legacySeries");

  private final String key;

  PopulationSource(String key) {
    this.key = key;
  }

  /** 读口发出去的键（{@code batches} / {@code legacySeries}）。 */
  public String key() {
    return key;
  }
}
