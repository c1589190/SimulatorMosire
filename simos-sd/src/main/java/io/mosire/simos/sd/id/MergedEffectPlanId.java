package io.mosire.simos.sd.id;

/**
 * 合并效果集 ID（D2 定义、D3 使用）：冲突决策包的 GM 合并计划身份。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。D2 只把表放进状态（空表），不产生计划。
 */
public record MergedEffectPlanId(String value) {

  public MergedEffectPlanId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MergedEffectPlanId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static MergedEffectPlanId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MergedEffectPlanId 不得为空白: " + text);
    }
    return new MergedEffectPlanId(text);
  }
}
