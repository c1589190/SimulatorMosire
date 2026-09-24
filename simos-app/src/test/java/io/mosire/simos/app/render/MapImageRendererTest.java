package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.EnumSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 哑画笔：尺寸、颜色落点、兜底色、确定性。用<b>像素</b>断言（这一层就是像素的实现），但只钉子集： ① PNG 解码回得来且尺寸正确；②
 * 格的<b>中心</b>像素等于该格填色（抗锯齿只影响边缘）；③ 非法颜色回兜底色；④ 同清单两次出同字节。
 */
class MapImageRendererTest {

  private static RenderModel terrainOnlyModel(int side) {
    return RenderModelBuilder.build(
        RenderFixtures.smallMap(),
        RenderFixtures.smallSocial(),
        RenderFixtures.smallUnits(),
        RenderFixtures.T0,
        new RenderRequest(RenderFixtures.CENTER, 1, EnumSet.of(RenderLayer.TERRAIN), side, side));
  }

  @Test
  void rendersDecodablePngWithRequestedSize() throws Exception {
    byte[] png = MapImageRenderer.renderPng(terrainOnlyModel(256));

    assertThat(png).hasSizeGreaterThan(100);
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
    assertThat(decoded.getWidth()).isEqualTo(256);
    assertThat(decoded.getHeight()).isEqualTo(256);
  }

  /** 中心格填色必须真的落在它的格心上（判别性：投影或填充顺序写错，这里立刻变色）。 */
  @Test
  void paintsHexCentersWithTheirFillColors() {
    RenderModel model = terrainOnlyModel(256);
    BufferedImage image = MapImageRenderer.renderImage(model);

    RenderModel.HexShape center =
        model.hexes().stream()
            .filter(hex -> hex.at().equals(RenderFixtures.CENTER))
            .findFirst()
            .orElseThrow();
    Color painted =
        new Color(
            image.getRGB((int) Math.round(center.centerX()), (int) Math.round(center.centerY())));
    assertThat(painted)
        .isEqualTo(MapImageRenderer.parseColor(RenderFixtures.PLAINS_COLOR, Color.BLACK));

    RenderModel.HexShape east =
        model.hexes().stream()
            .filter(hex -> hex.at().equals(RenderFixtures.EAST))
            .findFirst()
            .orElseThrow();
    Color eastPainted =
        new Color(image.getRGB((int) Math.round(east.centerX()), (int) Math.round(east.centerY())));
    assertThat(eastPainted)
        .isEqualTo(MapImageRenderer.parseColor(RenderFixtures.DESERT_COLOR, Color.BLACK));
  }

  @Test
  void illegalColorsFallBackInsteadOfPaintingBlack() {
    Color fallback = Color.MAGENTA;
    assertThat(MapImageRenderer.parseColor(null, fallback)).isEqualTo(fallback);
    assertThat(MapImageRenderer.parseColor("  ", fallback)).isEqualTo(fallback);
    assertThat(MapImageRenderer.parseColor("nope", fallback)).isEqualTo(fallback);
    assertThat(MapImageRenderer.parseColor("#GGGGGG", fallback)).isEqualTo(fallback);
    assertThat(MapImageRenderer.parseColor("#7BA05B", fallback))
        .isEqualTo(new Color(0x7B, 0xA0, 0x5B));
  }

  /** 确定性：同一份清单在同一 JVM 里出同一份字节（跨机器不保证——所以缓存按渲染键，不按字节哈希）。 */
  @Test
  void sameModelRendersToIdenticalBytes() {
    RenderModel model = terrainOnlyModel(128);
    assertThat(MapImageRenderer.renderPng(model)).isEqualTo(MapImageRenderer.renderPng(model));
  }
}
