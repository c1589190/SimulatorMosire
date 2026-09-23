package io.mosire.simos.social.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code social.SetPopulation} 命令边界：只改点名的格、anchorTick、重复坐标、负值拒绝、目标路径。 */
class SetPopulationHandlerTest {

  private static final SetPopulationHandler HANDLER = new SetPopulationHandler();
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);

  private static SocialData apply(HandlerOutcome.Applied applied, SocialData base) {
    return SocialChangeSet.apply((SocialChangeSet) applied.changeSet(), base);
  }

  private static HandlerOutcome.Rejected rejected(SocialData base, String payloadJson) {
    return (HandlerOutcome.Rejected) HANDLER.handle(SocialSpiFixture.state(base), payloadJson);
  }

  @Test
  void typeIsSocialSetPopulation() {
    assertThat(HANDLER.type()).isEqualTo("social.SetPopulation");
  }

  /** ★ 只改点名的格：未点名的格**原样**（连那一份序列对象都不动）。 */
  @Test
  void setsOnlyNamedHexesAndLeavesOthersUntouched() {
    SocialData base = new SocialData(Map.of(H00, SocialSpiFixture.still(100L)), Map.of());

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                SocialSpiFixture.state(base),
                "{\"entries\":[{\"q\":1,\"r\":0,\"population\":500}]}");

    SocialData after = apply(applied, base);
    assertThat(after.populations().get(H10).valueAt(SimosTimestamp.of(0))).isEqualTo(500L);
    assertThat(after.populations().get(H00))
        .as("未点名的 H00 必须原样")
        .isEqualTo(base.populations().get(H00));
    assertThat(after.cities()).as("SetPopulation 不动城市").isEmpty();
  }

  /** anchorTick 缺省 = 世界当前 tick。 */
  @Test
  void defaultAnchorIsTheStateTimestamp() {
    SocialData base = SocialData.empty();
    SimulationState state = SocialSpiFixture.state(base, SimosTimestamp.of(7));

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(state, "{\"entries\":[{\"q\":0,\"r\":0,\"population\":42}]}");

    SocialData after = apply(applied, base);
    assertThat(after.populations().get(H00).anchor().from()).isEqualTo(SimosTimestamp.of(7));
    assertThat(after.populations().get(H00).valueAt(SimosTimestamp.of(7))).isEqualTo(42L);
  }

  @Test
  void explicitAnchorTickWins() {
    SocialData base = SocialData.empty();
    SimulationState state = SocialSpiFixture.state(base, SimosTimestamp.of(7));

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                state, "{\"entries\":[{\"q\":0,\"r\":0,\"population\":42}],\"anchorTick\":3}");

    SocialData after = apply(applied, base);
    assertThat(after.populations().get(H00).anchor().from()).isEqualTo(SimosTimestamp.of(3));
  }

  /** ★ 重复坐标：后出现者覆盖先出现者，不报错。 */
  @Test
  void duplicateCoordinatesLaterWins() {
    SocialData base = SocialData.empty();
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                SocialSpiFixture.state(base),
                "{\"entries\":[{\"q\":0,\"r\":0,\"population\":100},"
                    + "{\"q\":0,\"r\":0,\"population\":200}]}");

    SocialData after = apply(applied, base);
    assertThat(after.populations()).hasSize(1);
    assertThat(after.populations().get(H00).valueAt(SimosTimestamp.of(0))).isEqualTo(200L);
  }

  @Test
  void negativePopulationIsRejected() {
    assertThat(
            rejected(SocialData.empty(), "{\"entries\":[{\"q\":0,\"r\":0,\"population\":-1}]}")
                .reason())
        .contains("population 必须 ≥ 0");
  }

  @Test
  void emptyEntriesIsRejected() {
    assertThat(rejected(SocialData.empty(), "{\"entries\":[]}").reason()).contains("entries 不得为空");
  }

  @Test
  void malformedPayloadIsRejected() {
    assertThat(rejected(SocialData.empty(), "这不是 JSON").reason()).contains("不是合法 JSON");
    assertThat(rejected(SocialData.empty(), "{}").reason()).contains("字段 entries 必须是");
    assertThat(rejected(SocialData.empty(), "{\"entries\":{}}").reason())
        .contains("字段 entries 必须是");
    assertThat(rejected(SocialData.empty(), "{\"entries\":[1]}").reason())
        .contains("的元素必须是 {q,r,population} 对象");
    assertThat(rejected(SocialData.empty(), "{\"entries\":[{\"q\":0}]}").reason())
        .contains("必须有整数 q 与 r");
    assertThat(rejected(SocialData.empty(), "{\"entries\":[{\"q\":0,\"r\":0}]}").reason())
        .contains("字段 population 必须是整数");
  }

  /** ★ 目标资源取 social 命名空间的既有形态 {@code <q>_<r>}（不带 mapId）——与读侧 fence 同一个资源。 */
  @Test
  void targetPathsAreSocialHexPaths() {
    List<String> paths =
        HANDLER.targetPaths(
            "Map1",
            "{\"entries\":[{\"q\":0,\"r\":0,\"population\":1},{\"q\":1,\"r\":0,\"population\":2}]}");
    assertThat(paths).containsExactly("0_0", "1_0");
  }

  /** 缺 social 切片是装配故障（{@code IllegalStateException}），不是 {@code Rejected}。 */
  @Test
  void missingSocialSliceIsAssemblyFaultNotRejection() {
    SimulationState mapOnly =
        new SimulationState(
            new StateMeta(SocialSpiFixture.REF, SocialSpiFixture.T0),
            Map.of(
                "map", new MapSnapshot(SocialSpiFixture.REF, SocialSpiFixture.T0, GameMap.empty())),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () -> HANDLER.handle(mapOnly, "{\"entries\":[{\"q\":0,\"r\":0,\"population\":1}]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("social");
  }
}
