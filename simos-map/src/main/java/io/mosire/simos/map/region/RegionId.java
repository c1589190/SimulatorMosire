package io.mosire.simos.map.region;

/**
 * 区域的稳定身份。**与名字分离** —— GSimulator 的 Province 拿 map 的键当身份，改名就要重建键。
 *
 * <p>铁律 1：名字与位置是定位方式，这个 id 才是身份。改名、改内容都不动它，历史与 Info 因此不断。
 *
 * <p>★ {@link #toString()} 返回**裸值**（不是 record 默认的 {@code RegionId[value=r1]}）：本类型是 regions 组件的
 * key，Task 6 的 {@code MapChangeSet} 用 {@code keyOf = toString()} 把 key 变成 {@code FieldDelta} 的
 * String key、apply 侧再用 {@link #parse} 还原 —— 默认实现会让 key 变成 {@code RegionId[value=r1]}，apply 侧认不出来。
 * 换言之这个串是**地址**，不是调试输出。
 */
public record RegionId(String value) {

  public RegionId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RegionId 不得为空白");
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
  public static RegionId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("RegionId 不得为空白: " + text);
    }
    return new RegionId(text);
  }
}
