package io.mosire.simos.app.render;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import java.util.Objects;

/**
 * 一张"世界视图"的<b>绘制清单</b>（纯数据，像素坐标已算好）。
 *
 * <p>分层理由：<b>取值/裁剪</b>在 {@link RenderModelBuilder}（认识领域数据）、<b>画</b>在 {@link MapImageRenderer}
 * （哑画笔，不认识领域）。这样图与字符图共用同一份取值口径；测试可以断清单（稳），不必断像素（脆）。
 */
public record RenderModel(
    int width,
    int height,
    String title,
    String subtitle,
    List<HexShape> hexes,
    List<Segment> segments,
    List<Marker> markers,
    List<LegendEntry> legend) {

  public RenderModel {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("画布尺寸必须为正: " + width + "x" + height);
    }
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(subtitle, "subtitle");
    hexes = List.copyOf(hexes);
    segments = List.copyOf(segments);
    markers = List.copyOf(markers);
    legend = List.copyOf(legend);
  }

  /** 一块地形/人口色块（六角）。 */
  public record HexShape(
      HexCoord at, double centerX, double centerY, double size, String fillColor) {}

  /** 一条线段（区域边界）。 */
  public record Segment(double x1, double y1, double x2, double y2, String color, double width) {}

  /** 标记种类（决定画什么形状，见渲染器）。 */
  public enum MarkerKind {
    /** 城市（方点）。 */
    CITY,
    /** 单位（圆点）。 */
    UNIT
  }

  /** 一个标记（城市/单位）。 */
  public record Marker(double x, double y, MarkerKind kind, String label, String color) {}

  /** 图例一行：色块 + 文字。 */
  public record LegendEntry(String label, String color) {}
}
