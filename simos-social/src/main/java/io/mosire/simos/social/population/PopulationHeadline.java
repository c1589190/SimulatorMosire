package io.mosire.simos.social.population;

/**
 * ★★ **一个读数 + 它的来源**（R2 的 T0）：读口要同时给出"这一格有多少人"与"这个数是从哪套账来的"。
 *
 * <p>★★ **为什么是一个类型而不是两个并列的返回值**：两者必须**同源产生**——先判来源、再按来源取值，写成两处调用就会出现"数字按批次算、
 * 来源标成旧序列"这种自相矛盾的读数（本仓最忌"注释声称一致、其实不一致"）。唯一的产出点是 {@code SocialData.headlinePopulationAt}。
 *
 * <p>★ 它**不是状态**：不落盘、不进 codec、不进变更集 —— 每次查询现算（与 {@link UrbanRural} / {@link AgeBracket} 的"派生量
 * 不落盘"同款）。
 *
 * @param value 人口读数（人）；不得为负
 * @param source 这个数来自哪套账；不得为 null
 */
public record PopulationHeadline(long value, PopulationSource source) {

  public PopulationHeadline {
    if (value < 0L) {
      throw new IllegalArgumentException("PopulationHeadline.value 必须 ≥ 0: " + value);
    }
    if (source == null) {
      throw new IllegalArgumentException("PopulationHeadline.source 不得为 null");
    }
  }
}
