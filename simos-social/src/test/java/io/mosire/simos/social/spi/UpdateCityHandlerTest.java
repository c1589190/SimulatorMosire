package io.mosire.simos.social.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code social.UpdateCity} 命令边界：改名/改人口/**合并** props、未知 id 拒绝、目标路径为空（fail-closed）。 */
class UpdateCityHandlerTest {

  private static final UpdateCityHandler HANDLER = new UpdateCityHandler();
  private static final CityId C1 = new CityId("c1");
  private static final HexCoord H00 = new HexCoord(0, 0);

  private static SocialData base(Map<String, Object> props) {
    SocialCity city = new SocialCity(C1, "原城", H00, Optional.empty(), 100L, props);
    return new SocialData(Map.of(), Map.of(C1, city));
  }

  private static SocialData apply(SocialData base, String payloadJson) {
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) HANDLER.handle(SocialSpiFixture.state(base), payloadJson);
    return SocialChangeSet.apply((SocialChangeSet) applied.changeSet(), base);
  }

  private static HandlerOutcome.Rejected rejected(SocialData base, String payloadJson) {
    return (HandlerOutcome.Rejected) HANDLER.handle(SocialSpiFixture.state(base), payloadJson);
  }

  @Test
  void typeIsSocialUpdateCity() {
    assertThat(HANDLER.type()).isEqualTo("social.UpdateCity");
  }

  /** ★ 只改名 ⇒ props 与 population **一点都不动**。 */
  @Test
  void renamingLeavesPropsAndPopulationUntouched() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tier", 1);
    props.put("tag", "core");
    SocialData after = apply(base(props), "{\"id\":\"c1\",\"name\":\"新名\"}");

    SocialCity city = after.cities().get(C1);
    assertThat(city.name()).isEqualTo("新名");
    assertThat(city.population()).isEqualTo(100L);
    assertThat(city.props()).containsExactlyEntriesOf(props);
    assertThat(city.at()).isEqualTo(H00);
    assertThat(city.region()).isEmpty();
  }

  @Test
  void changingPopulationLeavesNameAndPropsUntouched() {
    SocialData after = apply(base(Map.of("tier", 1)), "{\"id\":\"c1\",\"population\":777}");
    SocialCity city = after.cities().get(C1);
    assertThat(city.population()).isEqualTo(777L);
    assertThat(city.name()).isEqualTo("原城");
    assertThat(city.props()).containsOnlyKeys("tier");
  }

  /** ★ props 是**合并**语义：已有键保留、同键覆盖、新键按载荷序追加。 */
  @Test
  void propsAreMergedNotReplaced() {
    Map<String, Object> existing = new LinkedHashMap<>();
    existing.put("a", 1);
    existing.put("b", 2);
    SocialData after = apply(base(existing), "{\"id\":\"c1\",\"props\":{\"b\":9,\"c\":3}}");

    SocialCity city = after.cities().get(C1);
    assertThat(city.props().keySet()).as("已有键序在前、新键在后").containsExactly("a", "b", "c");
    assertThat(city.props()).containsEntry("a", 1).containsEntry("b", 9).containsEntry("c", 3);
    assertThat(city.name()).isEqualTo("原城");
    assertThat(city.population()).isEqualTo(100L);
  }

  @Test
  void unknownIdIsRejected() {
    assertThat(rejected(SocialData.empty(), "{\"id\":\"c404\",\"name\":\"幽灵\"}").reason())
        .contains("城市不存在");
  }

  /** 三个可选字段都不给 ⇒ 合法，产生一份空变更集（不是错误）。 */
  @Test
  void noOptionalFieldIsAnEmptyChangeSet() {
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(SocialSpiFixture.state(base(Map.of())), "{\"id\":\"c1\"}");
    assertThat(((SocialChangeSet) applied.changeSet()).isEmpty()).isTrue();
  }

  @Test
  void negativePopulationIsRejected() {
    assertThat(rejected(base(Map.of()), "{\"id\":\"c1\",\"population\":-3}").reason())
        .contains("population 必须 ≥ 0");
  }

  @Test
  void blankNameIsRejected() {
    assertThat(rejected(base(Map.of()), "{\"id\":\"c1\",\"name\":\" \"}").reason())
        .contains("name 不得为空白");
  }

  /** ★ 载荷不含坐标 ⇒ 没有可寻址目标：返回空列表（fail-closed，见 {@link UpdateCityHandler} 的类注）。 */
  @Test
  void targetPathsAreEmptyBecausePayloadCarriesNoCoordinate() {
    assertThat(HANDLER.targetPaths("Map1", "{\"id\":\"c1\",\"name\":\"新名\"}")).isEmpty();
  }

  @Test
  void missingSocialSliceIsAssemblyFaultNotRejection() {
    SimulationState mapOnly =
        new SimulationState(
            new StateMeta(SocialSpiFixture.REF, SocialSpiFixture.T0),
            Map.of(
                "map", new MapSnapshot(SocialSpiFixture.REF, SocialSpiFixture.T0, GameMap.empty())),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(() -> HANDLER.handle(mapOnly, "{\"id\":\"c1\",\"name\":\"新名\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("social");
  }
}
