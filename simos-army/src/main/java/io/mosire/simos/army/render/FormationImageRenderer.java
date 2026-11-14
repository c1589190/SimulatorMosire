package io.mosire.simos.army.render;

import io.mosire.simos.army.tools.FormationRenderPort;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.imageio.ImageIO;

/**
 * 军队编制树 PNG 渲染（{@link FormationRenderPort} 的宿主实现）。
 *
 * <p>★ <b>真图片</b>：Java2D 画盒子/连线/文字，PNG 字节经既有工件库 {@code putPng} 内容寻址；工具结果只拿 assetId。
 *
 * <p>★ <b>不静默漏节点</b>：所有传入节点都参与布局；画布超过上限时整体缩放（文字变小但节点仍在），不截断。
 * 附属线（attached=true）为实线，独立机动线（attached=false）为虚线；人数显示"本级/子树"。
 */
public final class FormationImageRenderer {

  private static final int BOX_WIDTH = 250;
  private static final int BOX_HEIGHT = 78;
  private static final int H_GAP = 24;
  private static final int V_GAP = 110;
  private static final int MARGIN_X = 24;
  private static final int MARGIN_Y = 70;
  private static final int MAX_WIDTH = 2400;
  private static final int MAX_HEIGHT = 2600;

  private final Function<byte[], String> putPng;

  public FormationImageRenderer(Function<byte[], String> putPng) {
    this.putPng = Objects.requireNonNull(putPng, "putPng");
  }

  public FormationRenderPort.Rendered render(
      String armyId, String armyName, List<FormationRenderPort.Node> nodes) {
    Objects.requireNonNull(nodes, "nodes");
    if (nodes.isEmpty()) {
      throw new IllegalArgumentException("编制为空，不能渲染: " + armyId);
    }
    Map<String, FormationRenderPort.Node> byId = new LinkedHashMap<>();
    for (FormationRenderPort.Node node : nodes) {
      byId.put(node.id(), node);
    }
    Map<String, List<FormationRenderPort.Node>> children = new LinkedHashMap<>();
    List<FormationRenderPort.Node> roots = new ArrayList<>();
    for (FormationRenderPort.Node node : nodes) {
      if (node.parentId() == null || !byId.containsKey(node.parentId())) {
        roots.add(node);
      } else {
        children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
      }
    }
    for (List<FormationRenderPort.Node> list : children.values()) {
      list.sort(Comparator.comparing(FormationRenderPort.Node::id));
    }
    roots.sort(Comparator.comparing(FormationRenderPort.Node::id));
    int maxDepth = 1;
    for (FormationRenderPort.Node node : nodes) {
      maxDepth = Math.max(maxDepth, node.depth());
    }
    Map<String, Double> x = new LinkedHashMap<>();
    int[] leafCursor = new int[] {0};
    for (FormationRenderPort.Node root : roots) {
      assignX(root, children, x, leafCursor);
    }
    int leaves = Math.max(1, leafCursor[0]);
    int logicalWidth = Math.max(720, MARGIN_X * 2 + leaves * (BOX_WIDTH + H_GAP));
    int logicalHeight = Math.max(360, MARGIN_Y + maxDepth * V_GAP + 90);
    double scale =
        Math.min(
            1.0, Math.min((double) MAX_WIDTH / logicalWidth, (double) MAX_HEIGHT / logicalHeight));
    int width = Math.max(320, (int) Math.round(logicalWidth * scale));
    int height = Math.max(240, (int) Math.round(logicalHeight * scale));

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.scale(scale, scale);
      g.setColor(new Color(246, 246, 240));
      g.fillRect(0, 0, logicalWidth, logicalHeight);
      drawTitle(g, armyName, armyId, nodes, logicalWidth);
      Map<String, int[]> boxes = new LinkedHashMap<>();
      for (FormationRenderPort.Node node : nodes) {
        int centerX =
            (int) Math.round(x.getOrDefault(node.id(), (double) (MARGIN_X + BOX_WIDTH / 2)));
        int topY = MARGIN_Y + (node.depth() - 1) * V_GAP;
        boxes.put(node.id(), new int[] {centerX, topY});
      }
      // 先画线，再画盒子：线不会盖住文字。
      for (FormationRenderPort.Node node : nodes) {
        if (node.parentId() == null || !boxes.containsKey(node.parentId())) {
          continue;
        }
        int[] parent = boxes.get(node.parentId());
        int[] child = boxes.get(node.id());
        g.setColor(node.attached() ? new Color(60, 90, 150) : new Color(180, 105, 40));
        g.setStroke(
            node.attached()
                ? new BasicStroke(2.4f)
                : new BasicStroke(
                    2.0f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND,
                    1.0f,
                    new float[] {7f, 5f},
                    0f));
        g.drawLine(parent[0], parent[1] + BOX_HEIGHT, child[0], child[1]);
      }
      g.setStroke(new BasicStroke(1.0f));
      for (FormationRenderPort.Node node : nodes) {
        int[] box = boxes.get(node.id());
        drawNode(g, node, box[0], box[1]);
      }
      drawLegend(g, logicalWidth, logicalHeight);
    } finally {
      g.dispose();
    }
    byte[] png;
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      png = out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("编制 PNG 编码失败: " + e.getMessage(), e);
    }
    String assetId = putPng.apply(png);
    long direct = 0L;
    long subtree = 0L;
    for (FormationRenderPort.Node node : nodes) {
      direct = Math.addExact(direct, node.directPersonnel());
      if (node.parentId() == null) {
        subtree = Math.addExact(subtree, node.subtreePersonnel());
      }
    }
    String text =
        "编制图「"
            + armyName
            + "」（"
            + armyId
            + "）："
            + nodes.size()
            + " 节点，深度 "
            + maxDepth
            + "，直接人数 "
            + direct
            + "，子树合计 "
            + subtree
            + "；PNG "
            + width
            + "×"
            + height
            + "（"
            + png.length
            + " 字节，assetId="
            + assetId
            + "）。实线=attached（随父移动），虚线=detached（独立机动线）。";
    return new FormationRenderPort.Rendered(
        text, java.util.Optional.of(assetId), png.length, width, height);
  }

  private static double assignX(
      FormationRenderPort.Node node,
      Map<String, List<FormationRenderPort.Node>> children,
      Map<String, Double> x,
      int[] leafCursor) {
    List<FormationRenderPort.Node> kids = children.getOrDefault(node.id(), List.of());
    if (kids.isEmpty()) {
      double value = MARGIN_X + BOX_WIDTH / 2.0 + leafCursor[0] * (BOX_WIDTH + H_GAP);
      leafCursor[0]++;
      x.put(node.id(), value);
      return value;
    }
    double sum = 0.0;
    for (FormationRenderPort.Node kid : kids) {
      sum += assignX(kid, children, x, leafCursor);
    }
    double value = sum / kids.size();
    x.put(node.id(), value);
    return value;
  }

  private static void drawTitle(
      Graphics2D g,
      String armyName,
      String armyId,
      List<FormationRenderPort.Node> nodes,
      int width) {
    g.setColor(new Color(30, 38, 48));
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
    g.drawString("军队编制 · " + armyName + " (" + armyId + ")", MARGIN_X, 34);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(90, 96, 106));
    g.drawString("节点 " + nodes.size() + " · 实线=随父移动 · 虚线=独立机动 · 人数=本级/子树", MARGIN_X, 54);
  }

  private static void drawNode(Graphics2D g, FormationRenderPort.Node node, int centerX, int topY) {
    int left = centerX - BOX_WIDTH / 2;
    Color fill = roleColor(node.role());
    g.setColor(fill);
    g.fillRoundRect(left, topY, BOX_WIDTH, BOX_HEIGHT, 12, 12);
    g.setColor(fill.darker());
    g.drawRoundRect(left, topY, BOX_WIDTH, BOX_HEIGHT, 12, 12);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    FontMetrics bold = g.getFontMetrics();
    g.drawString(ellipsis(bold, node.name(), BOX_WIDTH - 20), left + 10, topY + 20);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    FontMetrics plain = g.getFontMetrics();
    String second = node.id() + (node.role() == null ? "" : " · " + node.role());
    g.drawString(ellipsis(plain, second, BOX_WIDTH - 20), left + 10, topY + 37);
    String people = "本级 " + node.directPersonnel() + " / 子树 " + node.subtreePersonnel();
    g.drawString(ellipsis(plain, people, BOX_WIDTH - 20), left + 10, topY + 53);
    String where =
        (node.q() == null ? "位置 ?" : "位置 (" + node.q() + "," + node.r() + ")")
            + (node.parentId() == null ? " · 根单位" : (node.attached() ? " · 随父" : " · 独立"));
    g.drawString(ellipsis(plain, where, BOX_WIDTH - 20), left + 10, topY + 69);
  }

  private static void drawLegend(Graphics2D g, int width, int height) {
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    g.setColor(new Color(90, 96, 106));
    g.drawString(
        "本图由 simos.army.formation 生成；位置/附属关系来自 Unit，人数来自 Social 家户。", MARGIN_X, height - 16);
  }

  private static Color roleColor(String role) {
    if (role == null) {
      return new Color(110, 116, 126);
    }
    String upper = role.toUpperCase(java.util.Locale.ROOT);
    if (upper.contains("DIVISION") || role.contains("师")) {
      return new Color(45, 92, 160);
    }
    if (upper.contains("BRIGADE") || role.contains("旅")) {
      return new Color(48, 130, 96);
    }
    if (upper.contains("BATTALION") || role.contains("营")) {
      return new Color(176, 104, 35);
    }
    if (upper.contains("COMPANY") || role.contains("连")) {
      return new Color(120, 84, 150);
    }
    return new Color(90, 100, 112);
  }

  private static String ellipsis(FontMetrics metrics, String text, int maxWidth) {
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
