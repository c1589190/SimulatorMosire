package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/**
 * {@link CorridorWorld} 的逐值护栏（M5 T9b）：走廊世界的形状与**可断言的数字**（走廊三格 / 单位位置 / 逐边成本 1500 / 人口 15000）都钉成字面量。
 *
 * <p>★ 全部预期值都是**当场算过的字面量**（desert moveCost=3 × mobility 500‰ ⇒ 1500；人口 10000 + round(10000×0.10×5)
 * ⇒ 15000），不是"再调一遍领域 API 对拍"——后者会把被测物的公式错误 一起复制进来。
 */
class CorridorWorldTest {

  private static final SimosTimestamp T5 = SimosTimestamp.of(5);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  @Test
  void corridorStateIsDeterministicAndCarriesTheFourSlices() {
    SimulationState first = CorridorWorld.state("Map1");
    SimulationState second = CorridorWorld.state("Map1");

    assertThat(first).as("确定性：同参数逐字段相等").isEqualTo(second);
    assertThat(first.meta().ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(1)));
    assertThat(first.meta().timestamp()).isEqualTo(T5);
    assertThat(first.modules().keySet())
        .containsExactlyInAnyOrder("map", "unit", "social", "sd", "economy");
  }

  @Test
  void corridorIsThreeDesertHexesFromOneToThree() {
    GameMap map = ((MapSnapshot) state().module("map").orElseThrow()).map();

    assertThat(map.hexes().keySet())
        .as("走廊按 [1,1] → [1,2] → [1,3] 保序")
        .containsExactly(H11, H12, H13);
    assertThat(map.terrainIndex().values())
        .as("三格全为 desert")
        .allSatisfy(terrain -> assertThat(terrain).isEqualTo("desert"));
  }

  @Test
  void unitSitsAtOneOneAndEachDesertEdgeCosts1500() {
    SimulationState state = state();
    UnitState units = ((UnitSnapshot) state.module("unit").orElseThrow()).state();
    Unit unit = units.units().get(new UnitId("u-1"));

    assertThat(unit).isNotNull();
    assertThat(unit.name()).isEqualTo("第一连");
    assertThat(units.effectivePosition(unit.id(), T5)).contains(H11);

    GameMap map = ((MapSnapshot) state.module("map").orElseThrow()).map();
    assertThat(TerrainMovementCost.INSTANCE.costMillis(H11, H12, unit, map))
        .as("desert(moveCost=3) × mobility 500‰ ⇒ 1500 毫 MP")
        .hasValue(1500L);
    assertThat(TerrainMovementCost.INSTANCE.costMillis(H12, H13, unit, map))
        .as("第二段同价")
        .hasValue(1500L);
  }

  /**
   * ★ **创建点给缺省**（spec §4.1 / 权限阶段 Task 1）：{@code CorridorWorld} 的单位是**新建**的、没有视野的来源 ⇒ 给 {@link
   * Unit#DEFAULT_VISION_RADIUS}。
   *
   * <p>★ 这条是 app 侧那一半；unit 侧的创建点（{@code CreateUnitHandler}）由 {@code
   * UnitCommandHandlersTest.createUnitGivesTheDefaultVisionRadius} 把守，拷贝点由 {@code
   * UnitVisionRadiusTest} 的 16 条把守。
   */
  @Test
  void corridorUnitCarriesTheDefaultVisionRadius() {
    UnitState units = ((UnitSnapshot) state().module("unit").orElseThrow()).state();
    Unit unit = units.units().get(new UnitId("u-1"));

    assertThat(unit.visionRadius())
        .as("新建的单位取缺省 1 圈（不是 0：0 是合法值，但它是「只看自身格」，不是缺省）")
        .isEqualTo(Unit.DEFAULT_VISION_RADIUS);
  }

  @Test
  void populationAtOneOneIsFifteenThousandAtTheCorridorTick() {
    SocialData social = ((SocialSnapshot) state().module("social").orElseThrow()).data();
    PopulationSeries series = social.populations().get(H11);

    assertThat(series).isNotNull();
    assertThat(series.valueAt(T5))
        .as("anchor 10000 @0、10%/tick、t=5 ⇒ 10000 + round(10000×0.1×5) = 15000")
        .isEqualTo(15000L);
  }

  @Test
  void blankMapIdIsRejected() {
    assertThatThrownBy(() -> CorridorWorld.state(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白");
  }

  private static SimulationState state() {
    return CorridorWorld.state("Map1");
  }
}
