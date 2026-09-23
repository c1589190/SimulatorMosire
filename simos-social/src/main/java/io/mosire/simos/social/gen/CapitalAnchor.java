package io.mosire.simos.social.gen;

import java.util.OptionalLong;

/**
 * 首都锚点：**唯一的城市人口硬约束**（其余城市规模全由生成器公式给出）。
 *
 * <p>来自冻结输入 {@code config/worldgen/v17levant-nations.json} 的 {@code
 * nations[].capital}：三国里只有德意志第二帝国的 {@code 日耳曼尼亚} 带 {@code targetPopulation = 350000}，另两国国籍首都没有人口数字
 * —— 后者的表达是 {@link OptionalLong#empty()}（**"无文档依据"不是"零"**，故用 {@code Optional} 而非哨兵值）。
 *
 * @param name 首都名（文档地名，直接采用，不参与哈希取词）；**空白即抛**
 * @param targetPopulation 硬目标人口；{@code empty()} = 无目标、由公式给出
 */
public record CapitalAnchor(String name, OptionalLong targetPopulation) {

  public CapitalAnchor {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (targetPopulation == null) {
      throw new IllegalArgumentException("targetPopulation 不得为 null（无目标用 OptionalLong.empty()）");
    }
    if (targetPopulation.isPresent() && targetPopulation.getAsLong() < 0) {
      throw new IllegalArgumentException("targetPopulation 不得为负: " + targetPopulation.getAsLong());
    }
  }

  /** 带硬目标。 */
  public static CapitalAnchor of(String name, long targetPopulation) {
    return new CapitalAnchor(name, OptionalLong.of(targetPopulation));
  }

  /** 无人口数字的国籍首都（文档只给名字时用这个）。 */
  public static CapitalAnchor withoutTarget(String name) {
    return new CapitalAnchor(name, OptionalLong.empty());
  }

  /** 换硬目标（名字不动）。 */
  public CapitalAnchor withTargetPopulation(OptionalLong value) {
    return new CapitalAnchor(name, value);
  }
}
