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

/**
 * {@code social.UpdateCity} 命令边界：改名 / **合并** props、未知 id 拒绝、目标路径为空（fail-closed）、 **拒收已退役的
 * population 字段**（R1：人口是派生量，改它要改批次）。
 */
class UpdateCityHandlerTest {

  private static final UpdateCityHandler HANDLER = new UpdateCityHandler();
  private static final CityId C1 = new CityId("c1");
  private static final HexCoord H00 = new HexCoord(0, 0);

  private static SocialData base(Map<String, Object> props) {
    SocialCity city = new SocialCity(C1, "原城", H00, Optional.empty(), props);
    return new SocialData(Map.of(), Map.of(C1, city), Map.of());
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

  /** ★ 只改名 ⇒ props **一点都不动**（连键序都不动）。 */
  @Test
  void renamingLeavesPropsUntouched() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tier", 1);
    props.put("tag", "core");
    SocialData after = apply(base(props), "{\"id\":\"c1\",\"name\":\"新名\"}");

    SocialCity city = after.cities().get(C1);
    assertThat(city.name()).isEqualTo("新名");
    assertThat(city.props()).containsExactlyEntriesOf(props);
    assertThat(city.at()).isEqualTo(H00);
    assertThat(city.region()).isEmpty();
  }

  /**
   * ★★ **R1：population 字段被明令拒收**（不是静默忽略）——城的城镇人口是派生量 = 该城各批次之和， 要改它就得改批次（{@code
   * social.SeedGroups}）。判别力：删掉 {@code rejectRetiredPopulation} 那一行， 本用例当场红（命令会变成
   * Applied，人口改动被**静默丢弃**）。
   */
  @Test
  void retiredPopulationFieldIsRejectedNotSilentlyIgnored() {
    assertThat(rejected(base(Map.of("tier", 1)), "{\"id\":\"c1\",\"population\":777}").reason())
        .contains("不再接受 population 字段")
        .contains("派生量");
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
