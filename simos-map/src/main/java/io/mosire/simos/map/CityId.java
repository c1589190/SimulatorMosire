package io.mosire.simos.map;

/**
 * 城市的稳定身份。**与名字分离** —— 改名不动它，历史与 Info 因此不断（铁律 1）。
 *
 * <p>形制照 {@code RegionId} / {@code PathwayId}：**裸值 {@link #toString()} + {@code static parse} +
 * 空白即抛**。
 *
 * <p>★ 手写 {@link #toString()} 返回**裸值**（不是 record 默认的 {@code CityId[value=c1]}）：本类型是 cities 组件的
 * key，Task 6 的 {@code MapChangeSet} 用 {@code keyOf = toString()} 把 key 变成 {@code FieldDelta} 的
 * String key、apply 侧再用 {@link #parse} 还原 —— 默认实现会让 key 变成 {@code CityId[value=c1]}，apply
 * 侧认不出来，{@code applyRebuildsTargetExactly} 当场红。换言之这个串是**地址**，不是调试输出。
 */
public record CityId(String value) {

  public CityId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CityId 不得为空白");
    }
  }

  /** 裸值。见类注释：它是**地址形式**，供变更集的 String key 使用，**不是**给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

  /**
   * 解析 {@link #toString()} 的产物。**非空白即收**；空白与 {@code null} 抛 {@link IllegalArgumentException} ——
   * 与构造器**同口径**：宁抛不静默（把空白静默收下，等于让一个地址在往返里换了身份）。
   */
  public static CityId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CityId 不得为空白: " + text);
    }
    return new CityId(text);
  }
}
