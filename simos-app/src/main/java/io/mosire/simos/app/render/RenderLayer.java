package io.mosire.simos.app.render;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 渲染图层：一张"世界视图"要画哪些层。
 *
 * <p>取值与 {@code simos.map.render} 工具的 {@code layers} 参数一一对应（大小写不敏感）；<b>未知图层响亮拒绝</b>——
 * 静默忽略会让调用方以为"人口层画上了"， 而图上其实没有（这类"以为发了"的失败在渲染链路里最难发现，因为图看起来永远"有点东西"）。
 */
public enum RenderLayer {
  /** 地形色块（来自世界数据的 {@code terrainTypes[].color}）。 */
  TERRAIN,
  /** 区域边界（相邻两格区域不同处画线，颜色取 {@code region.meta.color}）。 */
  REGIONS,
  /** 城市标记（social 侧城市节点）。 */
  CITIES,
  /** 单位标记（unit 侧单位在该时刻的<b>有效位置</b>）。 */
  UNITS,
  /** 人口热力（按格的农村人口做对数分档着色；开启时<b>取代</b>地形底色——它是一张人口图，不是叠加层）。 */
  POPULATION;

  /** 解析工具/接口传来的图层名（空表 = 拒绝：没图层的图没有意义）。 */
  public static Set<RenderLayer> parseAll(List<String> names) {
    if (names == null) {
      throw new IllegalArgumentException("layers 不得为 null");
    }
    EnumSet<RenderLayer> out = EnumSet.noneOf(RenderLayer.class);
    for (String raw : names) {
      String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      try {
        out.add(RenderLayer.valueOf(name));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "未知图层: " + raw + "（可用: " + java.util.Arrays.toString(values()) + "）");
      }
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("layers 不能为空（至少给一个图层）");
    }
    return out;
  }
}
