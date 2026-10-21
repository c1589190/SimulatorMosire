package io.mosire.simos.map;

/**
 * 一个六边形格的海拔 + 土地组件树。
 *
 * <p>地形仍由 {@link GameMap#terrainAt(io.mosire.simos.map.hex.HexCoord)} 的权威地形块提供；本类型只存逐格海拔与 {@link
 * HexLand}。土地总量由 component 数量求和派生，不再保存第二份 flat {@code areas}。
 *
 * <p>{@code withHeight} 必须原样保留 {@code land}：涂地形只改高度，不得抹掉用户/结算写下的面积成分与属性。
 *
 * @param height 海拔，归一到 {@code [0,1]}
 * @param land 土地组件树；不得为 null
 */
public record HexCell(double height, HexLand land) {

  public HexCell {
    if (!Double.isFinite(height)) {
      throw new IllegalArgumentException("height 必须是有限数: " + height);
    }
    if (height < 0.0 || height > 1.0) {
      throw new IllegalArgumentException("height 必须在 [0,1]: " + height);
    }
    if (land == null) {
      throw new IllegalArgumentException("HexCell.land 不得为 null；空土地用 HexLand.empty()");
    }
  }

  /** 便捷构造器：只有海拔 ⇒ 空土地。 */
  public HexCell(double height) {
    this(height, HexLand.empty());
  }

  /** 只换海拔，土地组件树原样保留。 */
  public HexCell withHeight(double newHeight) {
    return new HexCell(newHeight, land);
  }

  /** 只换土地组件树，海拔原样保留。 */
  public HexCell withLand(HexLand newLand) {
    return new HexCell(height, newLand);
  }
}
