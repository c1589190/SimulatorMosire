package io.mosire.simos.unit.move;

import static io.mosire.simos.unit.move.MoveFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** ★ **判据二**（M3 spec §4.5 的冻结夹具与逐值表）+ R10（出发时冻结）。 */
class UnitMovesTest {

  private static final Route ROUTE = new Route(List.of(H11, H13), List.of(H11, H12, H13));

  /** `at = T0 + hours` 的在途单位（路线 `[1,1]→[1,2]→[1,3]`，出发于 T0）。 */
  private static Unit inTransit(long hours) {
    return inTransitWithSpeed(hours, unit().speed());
  }

  /** 同上，但速度自定（`Movement.speedAtDeparture` 同步取该值）——等值边界用例（R-10-b）用。 */
  private static Unit inTransitWithSpeed(long hours, int speed) {
    Unit base = unit();
    return new Unit(
        base.id(),
        base.name(),
        base.parent(),
        base.position(),
        base.member(),
        base.equipment(),
        speed,
        base.mobilityPerMille(),
        Optional.of(new Movement(ROUTE, T0, speed, base.mobilityPerMille())));
  }

  @Test
  void atTwentyHoursIsInTransitWithFiveMpRemaining() {
    MovementState state =
        UnitMoves.evaluate(inTransit(20), T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.currentHex())
        .as("预算 40000 ⇒ 40000−12500=27500；27500−32500=−5000")
        .isEqualTo(H12);
    assertThat(state.nextHex()).contains(H13);
    assertThat(state.remainingEdgeCostMillis()).hasValue(5000L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void atTwentyTwoHoursIsOneThousandShort() {
    MovementState state =
        UnitMoves.evaluate(inTransit(22), T0.plus(22), map(STEEP_65), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).isEqualTo(H12);
    assertThat(state.nextHex()).contains(H13);
    assertThat(state.remainingEdgeCostMillis()).as("预算 44000 比 45000 少 1000").hasValue(1000L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void atTwentyThreeHoursArrives() {
    MovementState state =
        UnitMoves.evaluate(inTransit(23), T0.plus(23), map(STEEP_65), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).isEqualTo(H13);
    assertThat(state.nextHex()).as("ARRIVED ⇒ nextHex / remaining 皆空").isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.ARRIVED);
  }

  /**
   * ★ R-10-b：预算**恰好**付清全部段（等值边界，`speed = 5`、`at = T0+9` ⇒ `5×1000×9 = 45000 == 12500+32500`）⇒
   * `ARRIVED`。这是 m1 变体（`>=` → `>`）唯一的靶子：`>` 会把第 2 段判成"付不起"并试图构造 `remaining = 0` 的 `MovementState` ⇒
   * 构造期 IAE——"恰够也是够"。
   */
  @Test
  void exactBudgetArrivalIsArrived() {
    MovementState state =
        UnitMoves.evaluate(
            inTransitWithSpeed(9, 5), T0.plus(9), map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.status()).as("恰够也是够").isEqualTo(MovementStatus.ARRIVED);
    assertThat(state.currentHex()).isEqualTo(H13);
    assertThat(state.nextHex()).isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
  }

  @Test
  void impassableNextStepNeedsReplan() {
    MovementState state =
        UnitMoves.evaluate(
            inTransit(20), T0.plus(20), map(IMPASSABLE_999), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).as("卡住前所在格").isEqualTo(H12);
    assertThat(state.nextHex()).isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.NEED_REPLAN);
  }

  /** ★ R10：出发后改单位速度，`evaluate` 结果不变（`speedAtDeparture` 已冻结）。 */
  @Test
  void speedChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit(20);
    Unit faster =
        new Unit(
            departed.id(),
            departed.name(),
            departed.parent(),
            departed.position(),
            departed.member(),
            departed.equipment(),
            99,
            departed.mobilityPerMille(),
            departed.movement());

    assertThat(UnitMoves.evaluate(faster, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .as("在途行程不得因参数变更而时间反演")
        .isEqualTo(
            UnitMoves.evaluate(departed, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  /** ★ 同一条的机动性半：`mobilityAtDeparture` 冻结（成本函数读的是 `frozen` 视图）。 */
  @Test
  void mobilityChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit(20);
    Unit nimbler =
        new Unit(
            departed.id(),
            departed.name(),
            departed.parent(),
            departed.position(),
            departed.member(),
            departed.equipment(),
            departed.speed(),
            1000,
            departed.movement());

    assertThat(
            UnitMoves.evaluate(nimbler, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isEqualTo(
            UnitMoves.evaluate(departed, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  @Test
  void noRouteOrEarlierThanDepartureIsACallerBug() {
    assertThatThrownBy(
            () -> UnitMoves.evaluate(unit(), T0, map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                UnitMoves.evaluate(
                    inTransit(20), T0.plus(-1), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * 构造期拒自相矛盾组合（R-10-e 列了四组：`IN_TRANSIT` 必须 next present 且余量严格 &gt; 0； `ARRIVED` / `NEED_REPLAN`
   * 两者皆空）——四组各断言一次。
   */
  @Test
  void movementStateRejectsSelfContradictoryCombinations() {
    assertThatThrownBy(
            () ->
                new MovementState(
                    H12, Optional.empty(), OptionalLong.empty(), MovementStatus.IN_TRANSIT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MovementState(
                    H12, Optional.of(H13), OptionalLong.of(0), MovementStatus.IN_TRANSIT))
        .as("IN_TRANSIT 的余量必须严格 > 0")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MovementState(
                    H13, Optional.of(H12), OptionalLong.of(1), MovementStatus.ARRIVED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MovementState(
                    H12, Optional.of(H13), OptionalLong.empty(), MovementStatus.NEED_REPLAN))
        .as("NEED_REPLAN 不得带 nextHex 或余量")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
