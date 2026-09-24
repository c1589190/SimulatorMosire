package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 绘制清单的取值口径：地形色、区域边、城市/单位标记、人口分档、图例。
 *
 * <p>用合成世界（见 {@link RenderFixtures}）断言<b>清单</b>而不是像素——清单是"画什么"的真相，像素是"怎么画"的实现细节。
 */
class RenderModelBuilderTest {

  private static final int SIDE = 768;

  private static RenderModel build(RenderRequest request) {
    return RenderModelBuilder.build(
        RenderFixtures.smallMap(),
        RenderFixtures.smallSocial(),
        RenderFixtures.smallUnits(),
        RenderFixtures.T0,
        request);
  }

  /** 7 格各得其色；中心格落在画布正中（视野以中心格为中心）。 */
  @Test
  void hexesCarryTerrainColorsAndCenterLandsOnCanvasCenter() {
    RenderModel model = build(RenderRequest.map(RenderFixtures.CENTER, 1));

    assertThat(model.hexes()).hasSize(7);
    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(RenderFixtures.CENTER))
        .singleElement()
        .satisfies(
            hex -> {
              assertThat(hex.fillColor()).isEqualTo(RenderFixtures.PLAINS_COLOR);
              assertThat(hex.centerX())
                  .isCloseTo(SIDE / 2.0, org.assertj.core.data.Offset.offset(0.001));
              assertThat(hex.centerY())
                  .isCloseTo(SIDE / 2.0, org.assertj.core.data.Offset.offset(0.001));
            });
    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(RenderFixtures.EAST))
        .singleElement()
        .satisfies(hex -> assertThat(hex.fillColor()).isEqualTo(RenderFixtures.DESERT_COLOR));
    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(new HexCoord(0, -1)))
        .singleElement()
        .satisfies(hex -> assertThat(hex.fillColor()).isEqualTo(RenderFixtures.OCEAN_COLOR));
  }

  /** 区域边只在"两侧区域不同"处画：测试区三格 + 六个邻格的地图上共 6 条（中心 4 条 + 东邻 1 条 + 东南邻 1 条； 地图外邻格不画——那是视野边界不是区域边界）。 */
  @Test
  void regionEdgesAreDrawnOnlyWhereTheRegionChanges() {
    RenderModel model = build(RenderRequest.map(RenderFixtures.CENTER, 1));

    assertThat(model.segments()).hasSize(6);
    assertThat(model.segments())
        .allSatisfy(
            segment -> {
              assertThat(segment.color()).isEqualTo(RenderFixtures.REGION_COLOR);
              assertThat(segment.width()).isGreaterThan(0.0);
            });
  }

  /** 城市与单位各出一枚标记，落点与有效位置一致；视野外的单位不画。 */
  @Test
  void markersCoverVisibleCitiesAndUnitsOnly() {
    RenderModel model = build(RenderRequest.map(RenderFixtures.CENTER, 1));

    assertThat(model.markers())
        .filteredOn(marker -> marker.kind() == RenderModel.MarkerKind.CITY)
        .singleElement()
        .satisfies(marker -> assertThat(marker.label()).isEqualTo("石堡"));
    assertThat(model.markers())
        .filteredOn(marker -> marker.kind() == RenderModel.MarkerKind.UNIT)
        .singleElement()
        .satisfies(marker -> assertThat(marker.label()).isEqualTo("一军"));

    RenderModel far =
        RenderModelBuilder.build(
            RenderFixtures.smallMap(),
            RenderFixtures.smallSocial(),
            RenderFixtures.farAwayUnits(),
            RenderFixtures.T0,
            RenderRequest.map(RenderFixtures.CENTER, 1));
    assertThat(far.markers())
        .as("视野外的单位一枚标记都不该有")
        .filteredOn(marker -> marker.kind() == RenderModel.MarkerKind.UNIT)
        .isEmpty();
  }

  /**
   * 人口层取代地形底色，并按对数分档取色：25 万（视野最大）落最深档、1000 落第 3 档（浅→深 0..4）。
   *
   * <p>判别性：把分档改成线性（不取对数），25 万与 1000 之间的距离会被压扁——1000 那一档会落回 0，本用例必红。
   */
  @Test
  void populationLayerReplacesTerrainFillWithLogBuckets() {
    RenderRequest request =
        new RenderRequest(
            RenderFixtures.CENTER,
            1,
            EnumSet.of(RenderLayer.POPULATION, RenderLayer.CITIES),
            SIDE,
            SIDE);
    RenderModel model = build(request);

    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(RenderFixtures.SOUTH_EAST))
        .singleElement()
        .satisfies(
            hex ->
                assertThat(hex.fillColor())
                    .as("25 万 = 视野最大 ⇒ 最深档")
                    .isEqualTo(RenderModelBuilder.POPULATION_SCALE.get(4)));
    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(RenderFixtures.CENTER))
        .singleElement()
        .satisfies(
            hex ->
                assertThat(hex.fillColor())
                    .as("1000 相对 25 万在对数尺上约 0.556 ⇒ 第 3 档（下标 2）")
                    .isEqualTo(RenderModelBuilder.POPULATION_SCALE.get(2)));
    assertThat(model.subtitle()).contains("农村人口");
    assertThat(model.legend())
        .extracting(RenderModel.LegendEntry::label)
        .anySatisfy(label -> assertThat(label).contains("人"));
  }

  /**
   * ★ 无人口数据的格用**中性灰**、且图例写明"无数据"——第一版实测把"无数据"画成了色阶最浅档（"0–6 人"）， 读图的人会以为那里人口极低。判别性：把 no-data
   * 分支去掉（回落到 {@code populationBucket(null,...)=0}）本用例必红。
   */
  @Test
  void hexesWithoutPopulationDataGetANeutralColorAndALegendEntry() {
    RenderRequest request =
        new RenderRequest(RenderFixtures.CENTER, 1, EnumSet.of(RenderLayer.POPULATION), SIDE, SIDE);
    RenderModel model = build(request);

    // 夹具：只有中心/东邻/东南邻有人口序列，其余四格没有
    assertThat(model.hexes())
        .filteredOn(hex -> hex.at().equals(new HexCoord(0, -1)))
        .singleElement()
        .satisfies(hex -> assertThat(hex.fillColor()).isEqualTo(RenderModelBuilder.NO_DATA_COLOR));
    assertThat(model.legend()).extracting(RenderModel.LegendEntry::label).contains("无数据");
  }

  /** 地形图例按"视野里真实出现的地形"给，不列没出现过的。 */
  @Test
  void terrainLegendListsOnlyTerrainPresentInView() {
    RenderModel model = build(RenderRequest.map(RenderFixtures.CENTER, 1));

    List<String> labels = model.legend().stream().map(RenderModel.LegendEntry::label).toList();
    assertThat(labels).contains("平原", "沙漠", "海洋", "城市", "单位");
  }

  /** 参数校验：半径/边长越界、空图层都要响亮。 */
  @Test
  void requestGuardsRejectOutOfRangeValues() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> RenderRequest.map(RenderFixtures.CENTER, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radius");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> RenderRequest.map(RenderFixtures.CENTER, 11))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radius");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new RenderRequest(
                    RenderFixtures.CENTER, 2, EnumSet.noneOf(RenderLayer.class), 512, 512))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("layers");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new RenderRequest(
                    RenderFixtures.CENTER, 2, EnumSet.of(RenderLayer.TERRAIN), 32, 512))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("width");
    assertThat(RenderLayer.parseAll(List.of("terrain", "UNITS")))
        .containsExactlyInAnyOrder(RenderLayer.TERRAIN, RenderLayer.UNITS);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> RenderLayer.parseAll(List.of("layers")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知图层");
  }
}
