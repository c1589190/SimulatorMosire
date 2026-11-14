package io.mosire.simos.map.reference;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * 参考图原图的视图渲染（原图 + 六角边界 + q/r；可选当前地形半透明叠加）。
 *
 * <p>★ <b>不虚构像素</b>：局部视图只从原图裁一块再缩放，邻格背景就是原图本身。六角位置一律取参考格网自带的 {@code
 * imageX/imageY/imageRadius}（像素坐标），不另算投影——格网绑定关系是同一份工件元数据，画的时候不能再推一次。
 *
 * <p>★ <b>尺寸/字节双预算</b>：输出边长 ≤ {@value #MAX_SIDE}，PNG 字节 ≤ {@value #MAX_PNG_BYTES}。超预算时先降采样再重编码；
 * 降到局部/概览各自的可读下限仍超预算 ⇒ 明确失败，不把糊图当可判图返回。
 */
final class ReferenceViewRenderer {

  static final int MAX_SIDE = 1280;
  static final int MAX_PNG_BYTES = 2 * 1024 * 1024;

  /** 局部视图的短边下限：再小标签就不可读，宁可不缩。 */
  private static final int LOCAL_MIN_SIDE = 320;

  /** 概览的短边下限。 */
  private static final int OVERVIEW_MIN_SIDE = 240;

  private static final Color BACKGROUND = new Color(0x0E, 0x13, 0x1A);
  private static final Color OUTLINE_LOCAL = new Color(0xE8, 0xF1, 0xFA, 220);
  private static final Color OUTLINE_OVERVIEW = new Color(0xC8, 0xDC, 0xF0, 120);
  private static final Color LABEL_TEXT = new Color(0xF7, 0xFA, 0xFD);
  private static final Color LABEL_BG = new Color(0, 0, 0, 165);
  private static final Color HEADER_BG = new Color(0, 0, 0, 185);

  private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

  private ReferenceViewRenderer() {}

  /** 渲染产物：PNG + 输出尺寸 + 本次裁切的原图矩形（前端点图换算用）。 */
  record Rendered(
      byte[] png,
      int width,
      int height,
      double sourceX,
      double sourceY,
      double sourceWidth,
      double sourceHeight) {}

  /**
   * @param source 已真实解码的原图
   * @param cells 要画的参考格（概览 = 全部；局部 = 半径内）
   * @param overview 概览（整图）还是局部
   * @param radius 局部半径（仅用于说明文字）
   * @param map 当前地图；{@code null} 表示不叠地形
   * @param terrainOverlay 是否叠当前地形
   * @param caption 顶部说明文字（不夹带原图/base64）
   */
  static Rendered render(
      BufferedImage source,
      List<MapReferenceService.Cell> cells,
      boolean overview,
      int radius,
      GameMap map,
      boolean terrainOverlay,
      String caption) {
    if (source == null || cells == null || cells.isEmpty()) {
      throw new IllegalArgumentException("参考图视图没有可画的格");
    }
    int sourceWidth = source.getWidth();
    int sourceHeight = source.getHeight();
    Crop crop = cropOf(sourceWidth, sourceHeight, cells, overview);
    double scale = initialScale(crop, overview);
    byte[] png = null;
    BufferedImage output = null;
    for (int attempt = 0; attempt < 12; attempt++) {
      output = draw(source, cells, crop, scale, overview, radius, map, terrainOverlay, caption);
      png = ReferenceImageIO.encodePng(output);
      if (png.length <= MAX_PNG_BYTES) {
        break;
      }
      int minSide = overview ? OVERVIEW_MIN_SIDE : LOCAL_MIN_SIDE;
      if (output.getWidth() <= minSide && output.getHeight() <= minSide) {
        break;
      }
      scale *= 0.82;
    }
    if (png == null || output == null) {
      throw new IllegalStateException("参考图视图渲染没有产物");
    }
    if (png.length > MAX_PNG_BYTES) {
      throw new IllegalArgumentException(
          "参考图视图超过 " + MAX_PNG_BYTES + " 字节预算；请减小半径或降低原图尺寸（不返回不可读的糊图）");
    }
    return new Rendered(
        png,
        output.getWidth(),
        output.getHeight(),
        crop.x(),
        crop.y(),
        crop.width(),
        crop.height());
  }

  private record Crop(double x, double y, double width, double height) {}

  private static Crop cropOf(
      int sourceWidth, int sourceHeight, List<MapReferenceService.Cell> cells, boolean overview) {
    if (overview) {
      return new Crop(0, 0, sourceWidth, sourceHeight);
    }
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    double maxRadius = 0;
    for (MapReferenceService.Cell cell : cells) {
      maxRadius = Math.max(maxRadius, cell.imageRadius());
      minX = Math.min(minX, cell.imageX() - cell.imageRadius());
      minY = Math.min(minY, cell.imageY() - cell.imageRadius());
      maxX = Math.max(maxX, cell.imageX() + cell.imageRadius());
      maxY = Math.max(maxY, cell.imageY() + cell.imageRadius());
    }
    // 局部保留邻格背景：四周多留一格以内，且不越出原图。
    double pad = maxRadius * 1.15;
    double x = clamp(minX - pad, 0, sourceWidth - 1);
    double y = clamp(minY - pad, 0, sourceHeight - 1);
    double right = clamp(maxX + pad, x + 1, sourceWidth);
    double bottom = clamp(maxY + pad, y + 1, sourceHeight);
    return new Crop(x, y, right - x, bottom - y);
  }

  private static double initialScale(Crop crop, boolean overview) {
    double maxSide = Math.max(crop.width(), crop.height());
    double fit = MAX_SIDE / maxSide;
    if (overview) {
      return Math.min(2.0, fit);
    }
    // 局部：小裁切允许放大（上限 4×）到可读短边；大裁切按 1280 上限缩。
    double scale = Math.min(4.0, fit);
    return Math.max(scale, Math.min(4.0, LOCAL_MIN_SIDE / maxSide));
  }

  private static BufferedImage draw(
      BufferedImage source,
      List<MapReferenceService.Cell> cells,
      Crop crop,
      double scale,
      boolean overview,
      int radius,
      GameMap map,
      boolean terrainOverlay,
      String caption) {
    int width = Math.max(1, (int) Math.round(crop.width() * scale));
    int height = Math.max(1, (int) Math.round(crop.height() * scale));
    BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = output.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setColor(BACKGROUND);
      g.fillRect(0, 0, width, height);
      int sx = (int) Math.floor(crop.x());
      int sy = (int) Math.floor(crop.y());
      int ex = (int) Math.ceil(crop.x() + crop.width());
      int ey = (int) Math.ceil(crop.y() + crop.height());
      g.drawImage(source, 0, 0, width, height, sx, sy, ex, ey, null);

      if (terrainOverlay && map != null) {
        drawTerrainFill(g, cells, crop, scale, map);
      }
      drawOutlines(g, cells, crop, scale, overview);
      drawLabels(g, cells, crop, scale, overview);
      drawHeader(g, caption(width, height, overview, radius));
    } finally {
      g.dispose();
    }
    return output;
  }

  private static void drawTerrainFill(
      Graphics2D g, List<MapReferenceService.Cell> cells, Crop crop, double scale, GameMap map) {
    for (MapReferenceService.Cell cell : cells) {
      HexCoord coord = cell.coord();
      if (!map.hexes().containsKey(coord)) {
        continue;
      }
      Color color = terrainColor(map, coord);
      Path2D path = hexPath(cell, crop, scale);
      g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 120));
      g.fill(path);
    }
  }

  private static void drawOutlines(
      Graphics2D g,
      List<MapReferenceService.Cell> cells,
      Crop crop,
      double scale,
      boolean overview) {
    g.setColor(overview ? OUTLINE_OVERVIEW : OUTLINE_LOCAL);
    g.setStroke(new BasicStroke(overview ? 0.7f : 1.4f));
    for (MapReferenceService.Cell cell : cells) {
      g.draw(hexPath(cell, crop, scale));
    }
  }

  private static void drawLabels(
      Graphics2D g,
      List<MapReferenceService.Cell> cells,
      Crop crop,
      double scale,
      boolean overview) {
    for (MapReferenceService.Cell cell : cells) {
      double radiusPixels = cell.imageRadius() * scale;
      if (overview && radiusPixels < 14) {
        continue;
      }
      if (!overview && radiusPixels < 7) {
        continue;
      }
      int fontSize = (int) Math.max(9, Math.min(18, Math.round(radiusPixels * 0.55)));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
      String text = cell.coord().q() + "," + cell.coord().r();
      double cx = (cell.imageX() - crop.x()) * scale;
      double cy = (cell.imageY() - crop.y()) * scale;
      int textWidth = g.getFontMetrics().stringWidth(text);
      int textHeight = g.getFontMetrics().getAscent();
      int x = (int) Math.round(cx - textWidth / 2.0);
      int y = (int) Math.round(cy + textHeight / 2.0);
      g.setColor(LABEL_BG);
      g.fillRoundRect(x - 3, y - textHeight - 2, textWidth + 6, textHeight + 5, 4, 4);
      g.setColor(LABEL_TEXT);
      g.drawString(text, x, y);
    }
  }

  private static void drawHeader(Graphics2D g, String caption) {
    if (caption == null || caption.isBlank()) {
      return;
    }
    g.setFont(HEADER_FONT);
    int textWidth = g.getFontMetrics().stringWidth(caption);
    g.setColor(HEADER_BG);
    g.fillRect(0, 0, Math.min(1280, textWidth + 18), 24);
    g.setColor(LABEL_TEXT);
    g.drawString(caption, 8, 17);
  }

  private static String caption(int width, int height, boolean overview, int radius) {
    return (overview ? "概览" : "局部 半径 " + radius) + " · 视图 " + width + "×" + height;
  }

  private static Path2D hexPath(MapReferenceService.Cell cell, Crop crop, double scale) {
    double cx = (cell.imageX() - crop.x()) * scale;
    double cy = (cell.imageY() - crop.y()) * scale;
    double radius = cell.imageRadius() * scale;
    Path2D path = new Path2D.Double();
    for (int i = 0; i < 6; i++) {
      double angle = Math.toRadians(60 * i - 30);
      double x = cx + radius * Math.cos(angle);
      double y = cy + radius * Math.sin(angle);
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    path.closePath();
    return path;
  }

  private static Color terrainColor(GameMap map, HexCoord coord) {
    try {
      String key = map.terrainAt(coord);
      TerrainType type = map.terrainTypes().get(key);
      if (type != null) {
        Color parsed = parseColor(type.color());
        if (parsed != null) {
          return parsed;
        }
      }
    } catch (RuntimeException e) {
      // 地形叠加只是可选的读图辅助；单个格读不出地形时不改视图成败。
    }
    return new Color(0x8A, 0x93, 0x9E);
  }

  private static Color parseColor(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim().toLowerCase(Locale.ROOT);
    if (text.length() != 7 || text.charAt(0) != '#') {
      return null;
    }
    try {
      int rgb = Integer.parseInt(text.substring(1), 16);
      return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
