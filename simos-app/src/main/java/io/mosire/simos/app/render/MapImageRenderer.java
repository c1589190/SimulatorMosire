package io.mosire.simos.app.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * 哑画笔：{@link RenderModel} → PNG 字节。不认识任何领域数据、不读状态、不写盘。
 *
 * <p>★ <b>确定性</b>：同一份清单在同一台机器上出<b>同一张图</b>（固定渲染提示、不嵌时间戳）。跨机器不保证逐字节相同
 * （字体与抗锯齿随环境），因此缓存按"渲染键"寻址、不按字节哈希（见 {@link RenderCache}）。
 *
 * <p>★ <b>可读性优先</b>：底色深、色块饱和、标记带描边、文字带底衬——这张图是给模型看的，看不清等于没画。
 */
public final class MapImageRenderer {

  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

  private static final Color HEX_STROKE = new Color(0x1A, 0x1F, 0x27);
  private static final Color MARKER_STROKE = new Color(0x0D, 0x10, 0x15);
  private static final Color TEXT = new Color(0xF2, 0xF5, 0xF8);
  private static final Color TEXT_SHADOW = new Color(0, 0, 0, 0xB0);
  private static final Color LEGEND_BG = new Color(0, 0, 0, 0xAA);

  private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 15);
  private static final Font SUBTITLE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
  private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

  private MapImageRenderer() {}

  /** 渲染成 PNG 字节（发给 LLM / 回给 MCP / 落盘给 GUI 的都是这一份字节）。 */
  public static byte[] renderPng(RenderModel model) {
    BufferedImage image = renderImage(model);
    ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      // BufferedImage 的内存写入不该失败；真失败说明 JVM/ImageIO 环境坏了，响亮抛
      throw new UncheckedIOException("PNG 编码失败", e);
    }
    return out.toByteArray();
  }

  /** 画出位图（测试用；生产走 {@link #renderPng}）。 */
  static BufferedImage renderImage(RenderModel model) {
    BufferedImage image =
        new BufferedImage(model.width(), model.height(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setColor(parseColor(RenderModelBuilder.BACKGROUND, Color.BLACK));
      g.fillRect(0, 0, model.width(), model.height());

      drawHexes(g, model);
      drawSegments(g, model);
      drawMarkers(g, model);
      drawHeader(g, model);
      drawLegend(g, model);
    } finally {
      g.dispose();
    }
    return image;
  }

  private static void drawHexes(Graphics2D g, RenderModel model) {
    for (RenderModel.HexShape hex : model.hexes()) {
      double[][] corners = MapProjection.corners(hex.size());
      Path2D path = new Path2D.Double();
      for (int i = 0; i < corners.length; i++) {
        double x = hex.centerX() + corners[i][0];
        double y = hex.centerY() + corners[i][1];
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      path.closePath();
      g.setColor(parseColor(hex.fillColor(), Color.GRAY));
      g.fill(path);
      g.setColor(HEX_STROKE);
      g.setStroke(new BasicStroke((float) Math.max(0.6, hex.size() * 0.05)));
      g.draw(path);
    }
  }

  private static void drawSegments(Graphics2D g, RenderModel model) {
    for (RenderModel.Segment segment : model.segments()) {
      g.setColor(parseColor(segment.color(), Color.WHITE));
      g.setStroke(new BasicStroke((float) segment.width()));
      g.drawLine(
          (int) Math.round(segment.x1()),
          (int) Math.round(segment.y1()),
          (int) Math.round(segment.x2()),
          (int) Math.round(segment.y2()));
    }
  }

  private static void drawMarkers(Graphics2D g, RenderModel model) {
    g.setFont(LABEL_FONT);
    for (RenderModel.Marker marker : model.markers()) {
      int x = (int) Math.round(marker.x());
      int y = (int) Math.round(marker.y());
      Color color = parseColor(marker.color(), Color.YELLOW);
      g.setColor(color);
      if (marker.kind() == RenderModel.MarkerKind.CITY) {
        g.fillRect(x - 5, y - 5, 10, 10);
        g.setColor(MARKER_STROKE);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRect(x - 5, y - 5, 10, 10);
      } else {
        g.fillOval(x - 6, y - 6, 12, 12);
        g.setColor(MARKER_STROKE);
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(x - 6, y - 6, 12, 12);
      }
      if (marker.label() != null && !marker.label().isBlank()) {
        drawLabel(g, marker.label(), x + 8, y - 7);
      }
    }
  }

  private static void drawHeader(Graphics2D g, RenderModel model) {
    g.setFont(TITLE_FONT);
    g.setColor(TEXT);
    g.drawString(model.title(), 12, 24);
    g.setFont(SUBTITLE_FONT);
    g.drawString(model.subtitle(), 12, 42);
  }

  private static void drawLegend(Graphics2D g, RenderModel model) {
    if (model.legend().isEmpty()) {
      return;
    }
    g.setFont(LABEL_FONT);
    int lineHeight = 18;
    int boxWidth = 0;
    var metrics = g.getFontMetrics();
    for (RenderModel.LegendEntry entry : model.legend()) {
      boxWidth = Math.max(boxWidth, metrics.stringWidth(entry.label()) + 30);
    }
    int boxHeight = model.legend().size() * lineHeight + 12;
    int x = 12;
    int y = model.height() - boxHeight - 12;
    g.setColor(LEGEND_BG);
    g.fillRect(x, y, boxWidth, boxHeight);
    int row = y + 20;
    for (RenderModel.LegendEntry entry : model.legend()) {
      g.setColor(parseColor(entry.color(), Color.GRAY));
      g.fillRect(x + 8, row - 10, 12, 12);
      g.setColor(MARKER_STROKE);
      g.setStroke(new BasicStroke(1f));
      g.drawRect(x + 8, row - 10, 12, 12);
      g.setColor(TEXT);
      g.drawString(entry.label(), x + 26, row);
      row += lineHeight;
    }
  }

  /** 带底衬的文字（深底浅字上直接画会糊；先画一层半透明黑偏移影）。 */
  private static void drawLabel(Graphics2D g, String text, int x, int y) {
    g.setColor(TEXT_SHADOW);
    g.drawString(text, x + 1, y + 1);
    g.setColor(TEXT);
    g.drawString(text, x, y);
  }

  /** {@code #RRGGBB} → {@link Color}；非法/空白回兜底色（不静默用黑：黑在深底上等于没画）。 */
  static Color parseColor(String text, Color fallback) {
    if (text == null) {
      return fallback;
    }
    String trimmed = text.trim().toUpperCase(Locale.ROOT);
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      return fallback;
    }
    return new Color(Integer.parseInt(trimmed.substring(1), 16));
  }
}
