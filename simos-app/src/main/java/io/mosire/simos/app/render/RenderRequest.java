package io.mosire.simos.app.render;

import io.mosire.simos.map.hex.HexCoord;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次渲染的参数（纯值）。它是<b>缓存键的一半</b>——另一半是 revision：同一份参数在同一个 revision 上必须命中同一张图。
 *
 * <p>★ 上界不是洁癖：{@code radius ≤ 10} 与 {@code side ≤ 1024} 一起把"发给 LLM 的图"钉在 token 预算内（实测 64×64 的小图 也要
 * ~230 prompt tokens，几百 KB 的图能把一次决策的预算吃掉一大半）。
 */
public record RenderRequest(
    HexCoord center, int radius, Set<RenderLayer> layers, int width, int height) {

  /** 半径下界（1 = 中心 + 一圈）。 */
  public static final int MIN_RADIUS = 1;

  /** 半径上界：与 GSimulator 的字符查看器同口径（再大在文本形态下失去可读性）。 */
  public static final int MAX_RADIUS = 10;

  /** 边长下界/上界（像素）。 */
  public static final int MIN_SIDE = 64;

  public static final int MAX_SIDE = 1024;

  /** 缺省边长：看图够用、token 预算友好。 */
  public static final int DEFAULT_SIDE = 768;

  public RenderRequest {
    Objects.requireNonNull(center, "center");
    Objects.requireNonNull(layers, "layers");
    if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
      throw new IllegalArgumentException(
          "radius 必须在 " + MIN_RADIUS + ".." + MAX_RADIUS + ": " + radius);
    }
    if (layers.isEmpty()) {
      throw new IllegalArgumentException("layers 不能为空");
    }
    layers = Set.copyOf(layers);
    requireSide(width, "width");
    requireSide(height, "height");
  }

  /** 缺省地图视图：地形 + 区域 + 城市 + 单位，{@link #DEFAULT_SIDE} 见方。 */
  public static RenderRequest map(HexCoord center, int radius) {
    return new RenderRequest(
        center,
        radius,
        EnumSet.of(RenderLayer.TERRAIN, RenderLayer.REGIONS, RenderLayer.CITIES, RenderLayer.UNITS),
        DEFAULT_SIDE,
        DEFAULT_SIDE);
  }

  /** 参数指纹（缓存键的参数字段；**不含 revision**——那由缓存调用方拼在键里）。 */
  public String fingerprint() {
    TreeSet<String> names = new TreeSet<>();
    for (RenderLayer layer : layers) {
      names.add(layer.name());
    }
    return "v1:"
        + center.q()
        + "_"
        + center.r()
        + ":r"
        + radius
        + ":"
        + String.join("+", names)
        + ":"
        + width
        + "x"
        + height;
  }

  private static void requireSide(int side, String name) {
    if (side < MIN_SIDE || side > MAX_SIDE) {
      throw new IllegalArgumentException(name + " 必须在 " + MIN_SIDE + ".." + MAX_SIDE + ": " + side);
    }
  }
}
