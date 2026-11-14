package io.mosire.simos.gov.render;

import io.mosire.simos.gov.tools.GovNationViews.NationView;
import io.mosire.simos.gov.tools.NationRenderPort;
import io.mosire.simos.map.hex.HexCoord;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * 国家 PNG 绘制（{@link NationRenderPort} 的领域实现，住 gov，不引 app）。
 *
 * <p>★ 只消费同一次 state 派生出的 {@link NationView}：领土并集、名称、颜色都由 gov 视图给出；本类不读世界、不解析 tag、不二次查询。 PNG 字节交给注入的
 * {@link NationRenderPort.PngPublisher} 内容寻址落库。
 */
public final class NationImageRenderer implements NationRenderPort {

  private static final double HEX_SIZE = 26.0;
  private static final int PADDING = 70;
  private static final int MAX_SIDE = 2000;

  private final PngPublisher publisher;

  public NationImageRenderer(PngPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  public Rendered render(NationView view) {
    Objects.requireNonNull(view, "view");
    if (view.hexes().isEmpty()) {
      throw new IllegalArgumentException("国家领土为空，不能渲染: " + view.nationId());
    }
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (HexCoord hex : view.hexes()) {
      double[] center = centerOf(hex);
      minX = Math.min(minX, center[0] - HEX_SIZE);
      minY = Math.min(minY, center[1] - HEX_SIZE);
      maxX = Math.max(maxX, center[0] + HEX_SIZE);
      maxY = Math.max(maxY, center[1] + HEX_SIZE);
    }
    double logicalWidth = Math.max(520.0, maxX - minX + PADDING * 2);
    double logicalHeight = Math.max(380.0, maxY - minY + PADDING * 2 + 50);
    double scale =
        Math.min(
            1.0, Math.min((double) MAX_SIDE / logicalWidth, (double) MAX_SIDE / logicalHeight));
    int width = Math.max(320, (int) Math.round(logicalWidth * scale));
    int height = Math.max(240, (int) Math.round(logicalHeight * scale));
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.scale(scale, scale);
      g.setColor(new Color(249, 248, 242));
      g.fillRect(0, 0, (int) Math.ceil(logicalWidth), (int) Math.ceil(logicalHeight));
      double offsetX = PADDING - minX;
      double offsetY = PADDING + 42 - minY;
      Color fill = parseColor(view.color());
      Color border = fill.darker();
      g.setStroke(new BasicStroke(1.1f));
      for (HexCoord hex : view.hexes()) {
        double[] center = centerOf(hex);
        int[] xs = new int[6];
        int[] ys = new int[6];
        for (int index = 0; index < 6; index++) {
          double angle = Math.toRadians(60.0 * index - 30.0);
          xs[index] = (int) Math.round(center[0] + offsetX + HEX_SIZE * Math.cos(angle));
          ys[index] = (int) Math.round(center[1] + offsetY + HEX_SIZE * Math.sin(angle));
        }
        g.setColor(fill);
        g.fillPolygon(xs, ys, 6);
        g.setColor(border);
        g.drawPolygon(xs, ys, 6);
      }
      drawTitle(g, view, logicalWidth);
    } finally {
      g.dispose();
    }
    byte[] png;
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      png = out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("国家 PNG 编码失败: " + e.getMessage(), e);
    }
    String assetId = publisher.publish(png);
    String text =
        "国家「"
            + view.name()
            + "」（"
            + view.nationId()
            + "）PNG：领土 "
            + view.hexCount()
            + " 格，颜色 "
            + (view.color() == null ? "(无)" : view.color())
            + "，homeRegion "
            + view.homeRegionId()
            + "，GOV "
            + view.govUnitIds()
            + "；PNG "
            + width
            + "×"
            + height
            + "（"
            + png.length
            + " 字节，assetId="
            + assetId
            + "）。";
    return new Rendered(text, java.util.Optional.of(assetId), png.length, width, height);
  }

  private static void drawTitle(Graphics2D g, NationView view, double logicalWidth) {
    g.setColor(new Color(35, 42, 52));
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    FontMetrics bold = g.getFontMetrics();
    g.drawString(
        clip(
            bold,
            view.name() + " (" + view.nationId() + ")",
            (int) logicalWidth - PADDING * 2 - 10),
        PADDING,
        36);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(88, 94, 104));
    String sub =
        view.hexCount()
            + " 格 · "
            + view.regionIds().size()
            + " 区域 · homeRegion "
            + view.homeRegionId()
            + (view.colorConflict() ? " · 颜色冲突（以 homeRegion 为准）" : "")
            + (view.govUnitIds().isEmpty() ? " · 无 GOV 关联" : " · GOV " + view.govUnitIds());
    g.drawString(clip(g.getFontMetrics(), sub, (int) logicalWidth - PADDING * 2 - 10), PADDING, 56);
  }

  private static double[] centerOf(HexCoord hex) {
    double x = HEX_SIZE * Math.sqrt(3.0) * (hex.q() + hex.r() / 2.0);
    double y = HEX_SIZE * 1.5 * hex.r();
    return new double[] {x, y};
  }

  private static Color parseColor(String color) {
    if (color == null || !color.matches("^#[0-9a-fA-F]{6}$")) {
      return new Color(130, 145, 170);
    }
    String hex = color.substring(1).toLowerCase(Locale.ROOT);
    return new Color(
        Integer.parseInt(hex.substring(0, 2), 16),
        Integer.parseInt(hex.substring(2, 4), 16),
        Integer.parseInt(hex.substring(4, 6), 16));
  }

  private static String clip(FontMetrics metrics, String text, int maxWidth) {
    if (metrics.stringWidth(text) <= maxWidth) {
      return text;
    }
    String suffix = "…";
    int end = text.length();
    while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) {
      end--;
    }
    return text.substring(0, end) + suffix;
  }
}
