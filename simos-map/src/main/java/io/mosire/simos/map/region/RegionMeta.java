package io.mosire.simos.map.region;

/**
 * 区域的非内容元数据。**四字段都可为 null**（空元数据是合法状态，不是缺失）。
 *
 * <p>它们不参与任何几何/归属判定：改变其中一个不影响 {@code hexes}，也不影响边界。
 */
public record RegionMeta(String color, String tag, String description, String annexedBy) {

  /** 全空的元数据。{@code Region} 的构造器在 {@code meta == null} 时用它兜底。 */
  public static RegionMeta empty() {
    return new RegionMeta(null, null, null, null);
  }
}
