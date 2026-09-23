package io.mosire.simos.social.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code social.CreateCity} 命令边界：建城、重复 id 拒绝、props 保序、目标路径。 */
class CreateCityHandlerTest {

  private static final CreateCityHandler HANDLER = new CreateCityHandler();
  private static final CityId C1 = new CityId("c1");

  private static SocialData apply(HandlerOutcome.Applied applied, SocialData base) {
    return SocialChangeSet.apply((SocialChangeSet) applied.changeSet(), base);
  }

  private static SocialData apply(SocialData base, String payloadJson) {
    return apply(
        (HandlerOutcome.Applied) HANDLER.handle(SocialSpiFixture.state(base), payloadJson), base);
  }

  private static HandlerOutcome.Rejected rejected(SocialData base, String payloadJson) {
    return (HandlerOutcome.Rejected) HANDLER.handle(SocialSpiFixture.state(base), payloadJson);
  }

  @Test
  void typeIsSocialCreateCity() {
    assertThat(HANDLER.type()).isEqualTo("social.CreateCity");
  }

  @Test
  void createsACityWithDefaultsForRegionAndProps() {
    SocialData after =
        apply(
            SocialData.empty(),
            "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":0,\"r\":0},\"population\":12000}");

    SocialCity city = after.cities().get(C1);
    assertThat(city).isNotNull();
    assertThat(city.name()).isEqualTo("城甲");
    assertThat(city.at()).isEqualTo(new HexCoord(0, 0));
    assertThat(city.region()).as("region 缺省 = 无归属").isEmpty();
    assertThat(city.population()).isEqualTo(12000L);
    assertThat(city.props()).as("props 缺省 = 空表").isEmpty();
  }

  @Test
  void createsACityWithRegionAndPropsKeepingPropOrder() {
    SocialData after =
        apply(
            SocialData.empty(),
            "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":1,\"r\":2},\"region\":\"r1\","
                + "\"population\":900,\"props\":{\"tier\":3,\"catchmentHexes\":7,\"tag\":\"core\"}}");

    SocialCity city = after.cities().get(C1);
    assertThat(city.at()).isEqualTo(new HexCoord(1, 2));
    assertThat(city.region()).contains(new RegionId("r1"));
    assertThat(city.props().keySet())
        .as("props 保插入序")
        .containsExactly("tier", "catchmentHexes", "tag");
  }

  @Test
  void existingIdIsRejected() {
    SocialData base =
        apply(
            SocialData.empty(),
            "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":0,\"r\":0},\"population\":1}");
    assertThat(
            rejected(
                    base,
                    "{\"id\":\"c1\",\"name\":\"另一座\",\"at\":{\"q\":5,\"r\":5},\"population\":1}")
                .reason())
        .contains("城市已存在");
  }

  @Test
  void negativePopulationIsRejected() {
    assertThat(
            rejected(
                    SocialData.empty(),
                    "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":0,\"r\":0},\"population\":-5}")
                .reason())
        .contains("population 必须 ≥ 0");
  }

  @Test
  void blankNameIsRejected() {
    assertThat(
            rejected(
                    SocialData.empty(),
                    "{\"id\":\"c1\",\"name\":\"  \",\"at\":{\"q\":0,\"r\":0},\"population\":1}")
                .reason())
        .contains("name 不得为空白");
  }

  @Test
  void blankRegionIsRejected() {
    assertThat(
            rejected(
                    SocialData.empty(),
                    "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":0,\"r\":0},\"region\":\" \","
                        + "\"population\":1}")
                .reason())
        .contains("RegionId 不得为空白");
  }

  @Test
  void malformedPayloadIsRejected() {
    assertThat(rejected(SocialData.empty(), "not json").reason()).contains("不是合法 JSON");
    assertThat(rejected(SocialData.empty(), "{}").reason()).contains("字段 id 必须是字符串");
    assertThat(rejected(SocialData.empty(), "{\"id\":\"c1\"}").reason()).contains("字段 name 必须是字符串");
    assertThat(rejected(SocialData.empty(), "{\"id\":\"c1\",\"name\":\"n\"}").reason())
        .contains("字段 at 必须是 {q,r} 对象");
    assertThat(
            rejected(SocialData.empty(), "{\"id\":\"c1\",\"name\":\"n\",\"at\":{\"q\":0,\"r\":0}}")
                .reason())
        .contains("字段 population 必须是整数");
  }

  /** ★ 目标资源 = 城市落点那一格，取 social 逐格形态 {@code <q>_<r>}。 */
  @Test
  void targetPathIsTheHexTheCityWillSitOn() {
    List<String> paths =
        HANDLER.targetPaths(
            "Map1", "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":1,\"r\":2},\"population\":1}");
    assertThat(paths).containsExactly("1_2");
  }

  @Test
  void missingSocialSliceIsAssemblyFaultNotRejection() {
    SimulationState mapOnly =
        new SimulationState(
            new StateMeta(SocialSpiFixture.REF, SocialSpiFixture.T0),
            Map.of(
                "map", new MapSnapshot(SocialSpiFixture.REF, SocialSpiFixture.T0, GameMap.empty())),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () ->
                HANDLER.handle(
                    mapOnly,
                    "{\"id\":\"c1\",\"name\":\"城甲\",\"at\":{\"q\":0,\"r\":0},\"population\":1}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("social");
  }
}
