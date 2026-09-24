package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 字符图（无视觉能力模型的回落形态）：行数/图例/覆盖优先级（单位 &gt; 城市 &gt; 地形）。
 *
 * <p>用合成世界断言：中心平原 "."、东邻沙漠 ":" 上有城市 "C"、东南邻海洋 "~" 上有单位 "A"。
 */
class MapTextRendererTest {

  private static String render(int radius) {
    return MapTextRenderer.render(
        RenderFixtures.smallMap(),
        RenderFixtures.smallSocial(),
        RenderFixtures.smallUnits(),
        RenderFixtures.T0,
        RenderFixtures.CENTER,
        radius);
  }

  @Test
  void rendersHeaderRowsAndLegend() {
    String text = render(1);

    List<String> lines = text.lines().toList();
    assertThat(lines.get(0)).contains("字符图").contains("中心(0,0)").contains("半径 1");
    // 半径 1 ⇒ 三行格网（r = -1..1）+ 标题 + 图例
    assertThat(lines).hasSize(5);
    assertThat(lines.get(lines.size() - 1)).contains("图例");
  }

  /** 覆盖优先级与地形字符：这一行是"r=1"那一行（东南邻有单位、中心格在它的左上）。 */
  @Test
  void unitMarkerWinsOverCityWhichWinsOverTerrain() {
    String text = render(1);
    List<String> rows = text.lines().skip(1).limit(3).toList();

    assertThat(rows.get(0)).as("r=-1 行：两格海洋（西北两格在视野外，用空白占位）").contains("~");
    assertThat(rows.get(1)).as("r=0 行：中心平原 + 东邻沙漠（被城市 C 覆盖）").contains(".").contains("C");
    assertThat(rows.get(2)).as("r=1 行：东南邻海洋（被单位 A 覆盖）").contains("A");
  }

  @Test
  void legendNamesTheTerrainsAndMarkersActuallyPresent() {
    String text = render(2);

    assertThat(text).contains("平原").contains("沙漠").contains("海洋").contains("城市").contains("单位");
  }

  @Test
  void rejectsRadiusOutOfRange() {
    assertThatThrownBy(() -> render(11))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radius");
    assertThatThrownBy(
            () ->
                MapTextRenderer.render(
                    RenderFixtures.smallMap(),
                    RenderFixtures.smallSocial(),
                    RenderFixtures.smallUnits(),
                    RenderFixtures.T0,
                    new HexCoord(0, 0),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
