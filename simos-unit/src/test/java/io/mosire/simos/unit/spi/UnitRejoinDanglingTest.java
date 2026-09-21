package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T10-d 裁定：`disband` 之后**允许悬空 `rejoinTarget`**——本用例把它钉成**可观测的既定行为**，而不是留成无判据的缺口。
 *
 * <p>★ 裁定依据：`rejoinTarget` 不像 `commandChains` 那样有"引用完整性"不变量（{@link UnitState} 构造期只查链），且运行期口径安全——
 * {@code effectivePosition} 对查无此人的 id 返空 ⇒ {@link UnitOperations#rejoinRoute}
 * 走四类"假"的公共空出口，**不回归、不抛、不写任何东西**。 在 `disband` 里顺带清他单位的引用会改 T3/T4 已关账的 op 族（按裁定 42
 * 须连带重跑其变异轮），收益却低于代价。
 *
 * <p>★ 本用例的**咬点**：把"悬空引用不回归、也不被 disband 清理"两半都钉住。任何一侧被改（`disband` 清引用 / `rejoinRoute`
 * 对悬空目标抛或误判）都会在这里红。
 */
class UnitRejoinDanglingTest {

  private static final SimosTimestamp T5 = SimosTimestamp.of(5);

  private static final UnitId SURVIVOR = SpiFixture.U1;
  private static final UnitId VICTIM = new UnitId("u-2");

  @Test
  void danglingRejoinTargetAfterDisbandIsSafeAndObservable() {
    UnitState wired =
        UnitOperations.setRejoinTarget(
            base(), SURVIVOR, Optional.of(VICTIM)); // 先确认引用真的挂上了（否则下面的"悬空"无从谈起）
    assertThat(wired.units().get(SURVIVOR).rejoinTarget()).contains(VICTIM);

    UnitState after = UnitOperations.disband(wired, VICTIM, T5);

    assertThat(after.units()).as("被解散者真的走了").doesNotContainKey(VICTIM);
    assertThat(after.units().get(SURVIVOR).rejoinTarget())
        .as("T10-d：disband **不**清他单位的回归引用 ⇒ 引用悬空（有意，非缺口）")
        .contains(VICTIM);
    assertThat(after.effectivePosition(VICTIM, T5)).as("目标已不在 units ⇒ 位置不可确定").isEmpty();
    assertThat(
            UnitOperations.rejoinRoute(
                after, SURVIVOR, SpiFixture.map(), TerrainMovementCost.INSTANCE, T5))
        .as("悬空引用 ⇒ 走公共空出口：不回归、不抛")
        .isEmpty();
  }

  private static UnitState base() {
    return SpiFixture.unitState(
        SpiFixture.unitWithMovement(Optional.empty()), other(VICTIM, SpiFixture.H12));
  }

  /** 与 {@code SpiFixture.unitWithMovement} 同形的第二个单位（9 参兼容构造器：缺省 MOVING / attached=true / 无偏移）。 */
  private static Unit other(UnitId id, HexCoord position) {
    return new Unit(
        id,
        "第二连",
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.of(position))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }
}
