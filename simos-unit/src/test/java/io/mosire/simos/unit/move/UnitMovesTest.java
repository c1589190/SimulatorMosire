package io.mosire.simos.unit.move;

import static io.mosire.simos.unit.move.MoveFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★ **判据二**（M3 spec §4.5 的冻结夹具与逐值表）+ R10（出发时冻结）+ **日制重标定**（2026-09-24 用户裁定：1 tick = 1 天，{@code
 * speed} 为 MP/小时，日预算 = {@code speed × 1000 × 24}）。
 *
 * <p>★ 重标定的由来：旧用例用"20/22/23 刻"制造"段中边界"，其前提是 1 刻 ≪ 一天。1 tick = 1 天之后，要复现"段中行进"就得让
 * **一天的预算小于全程成本**——走廊速度因此取 {@code 1 MP/小时}（一天 24000 > 全程 45000 的一半、小于全程）， 旧口径凑数据的 speed 2（一天 48000
 * > 全程 45000）一步就到达，表达不出任何段中节奏。
 */
class UnitMovesTest {

  private static final Route ROUTE = new Route(List.of(H11, H13), List.of(H11, H12, H13));

  /** T0 出发、speed = 1 MP/小时（日预算 24000）的在途单位——路线 `[1,1]→[1,2]→[1,3]`。 */
  private static Unit inTransit() {
    return inTransitWithSpeed(1);
  }

  /** 同上，但速度自定（`Movement.speedAtDeparture` 同步取该值）——出发时刻恒为 T0。 */
  private static Unit inTransitWithSpeed(int speed) {
    Unit base = unit();
    return new Unit(
        base.id(),
        base.name(),
        base.parent(),
        base.position(),
        base.manpower(),
        base.equipment(),
        speed,
        base.mobilityPerMille(),
        Optional.of(new Movement(ROUTE, T0, speed, base.mobilityPerMille())));
  }

  // ── 日制：段中边界（走廊两段成本 12500 / 32500） ─────────────────────

  @Test
  void dayZeroIsInTransitAtTheStartWithTheWholeFirstStepUnpaid() {
    MovementState state =
        UnitMoves.evaluate(inTransit(), T0, map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.currentHex()).as("日预算 0 ⇒ 一段都没付清，停在起点").isEqualTo(H11);
    assertThat(state.nextHex()).contains(H12);
    assertThat(state.remainingEdgeCostMillis()).as("第 1 段 12500 一分未付").hasValue(12500L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void dayOneIsStuckBetweenTheFirstAndSecondStep() {
    MovementState state =
        UnitMoves.evaluate(inTransit(), T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.currentHex())
        .as("日预算 24000 = 1×1000×24 ⇒ 24000−12500=11500；11500−32500=−21000")
        .isEqualTo(H12);
    assertThat(state.nextHex()).contains(H13);
    assertThat(state.remainingEdgeCostMillis()).as("停在两格之间，第 2 段还差 21000").hasValue(21000L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void dayTwoArrivesAtH13() {
    MovementState state =
        UnitMoves.evaluate(inTransit(), T0.plus(2), map(STEEP_65), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).as("日预算 48000 ≥ 全程 45000").isEqualTo(H13);
    assertThat(state.nextHex()).as("ARRIVED ⇒ nextHex / remaining 皆空").isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.ARRIVED);
  }

  /**
   * ★ R-13-b（M3 关账补条，**日制重标定**）：判据二三行表的**中间值直接断言**——预算 = {@code speedAtDeparture × 1000 ×
   * HOURS_PER_DAY × Δ天} = {@code 1×1000×24×1 = 24000}；{@code 24000 − 12.5 = 23.5}、{@code 23.5 −
   * 32.5 = −9}。
   *
   * <p>这些字面量把"忘了 ×24"钉死：丢了 24 则预算 = 1000，三条断言全红。变异靶子：m13v-1 让 {@code costOf} 丢 ×1000 ⇒ 成本变 12 /
   * 32，也全红。
   */
  @Test
  void criterionTwoArithmeticMatchesTheSpecTable() {
    Unit departed = inTransit();
    Movement movement = departed.movement().orElseThrow();
    SimosTimestamp at = T0.plus(1);
    long budget =
        movement.speedAtDeparture()
            * 1000L
            * UnitMoves.HOURS_PER_DAY
            * (at.tick() - movement.departedAt().tick());
    long firstCost =
        TerrainMovementCost.INSTANCE.costMillis(H11, H12, departed, map(STEEP_65)).orElseThrow();
    long secondCost =
        TerrainMovementCost.INSTANCE.costMillis(H12, H13, departed, map(STEEP_65)).orElseThrow();

    assertThat(budget).as("日预算 = 1 MP/小时 × 1000 × 24 × 1 天").isEqualTo(24000L);
    assertThat(budget - firstCost).as("24000 付第 1 段 12500 ⇒ 剩 11500").isEqualTo(11500L);
    assertThat(budget - firstCost - secondCost)
        .as("11500 付不起第 2 段 32500 ⇒ 差 −21000")
        .isEqualTo(-21000L);
    assertThat(secondCost - (budget - firstCost))
        .as("evaluate 的 remaining = 32500 − 11500 = 21000")
        .isEqualTo(21000L);
  }

  @Test
  void impassableNextStepNeedsReplan() {
    MovementState state =
        UnitMoves.evaluate(
            inTransit(), T0.plus(1), map(IMPASSABLE_999), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).as("卡住前所在格（第 1 段已付清）").isEqualTo(H12);
    assertThat(state.nextHex()).isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.NEED_REPLAN);
  }

  // ── ★★ 新增判别力：日预算 ×24（一天走多少格） ─────────────────────────

  /**
   * ★★ **判别力 A（"忘了 ×24"零容忍）**：直线长路线 + 每格固定 1000 毫 MP、speed = 1 MP/小时 ⇒ 日预算 24000。 推进 **1 天恰走 24
   * 格**（下标 24，第 25 段差 1000），**再推 1 天恰走 48 格**。
   *
   * <p>24 与 48 是**字面量**：丢 ×24（预算 1000）⇒ 只走 1 格 / 2 格；丢 Δ 乘数（预算恒 24000）⇒ 两天都停在 24 格。
   */
  @Test
  void oneDayCoversExactlyTwentyFourHexesAndTwoDaysFortyEight() {
    List<HexCoord> line = straightLine(60);
    Unit mover = lineUnit(line, 1);
    MovementCost flat = new FlatStepCost(1000);

    MovementState day1 = UnitMoves.evaluate(mover, T0.plus(1), map(STEEP_65), flat);
    assertThat(line.indexOf(day1.currentHex())).as("一天恰走 24 格（24000 ÷ 1000）").isEqualTo(24);
    assertThat(line.indexOf(day1.nextHex().orElseThrow())).as("走到的下一格下标 25").isEqualTo(25);
    assertThat(day1.remainingEdgeCostMillis()).as("第 25 段还差 1000").hasValue(1000L);
    assertThat(day1.status()).isEqualTo(MovementStatus.IN_TRANSIT);

    MovementState day2 = UnitMoves.evaluate(mover, T0.plus(2), map(STEEP_65), flat);
    assertThat(line.indexOf(day2.currentHex())).as("再推 1 天恰走 48 格（48000 ÷ 1000）").isEqualTo(48);
    assertThat(line.indexOf(day2.nextHex().orElseThrow())).isEqualTo(49);
    assertThat(day2.remainingEdgeCostMillis()).hasValue(1000L);
    assertThat(day2.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  /**
   * ★ 等值边界（`>=` 的靶子）：25 格直线 = 24 段 × 1000 = 24000 **恰好等于**一日预算 ⇒ `ARRIVED`。
   *
   * <p>变异体 `>=` → `>`：最后一段在"恰够"时被判成付不起 ⇒ 试图构造 `remaining = 0` 的 `MovementState` ⇒ 构造期
   * IAE（红）。"恰够也是够"。
   */
  @Test
  void exactOneDayBudgetArrivalIsArrived() {
    List<HexCoord> line = straightLine(25);
    MovementState state =
        UnitMoves.evaluate(lineUnit(line, 1), T0.plus(1), map(STEEP_65), new FlatStepCost(1000));

    assertThat(state.status()).as("预算 24000 恰够 24 段 × 1000").isEqualTo(MovementStatus.ARRIVED);
    assertThat(state.currentHex()).isEqualTo(line.get(24));
    assertThat(state.nextHex()).isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
  }

  /**
   * ★★ **判别力 B**：每格成本 **7000**、一日预算 **24000**（不整除）⇒ 第 1 天走满 3 段（21000），剩 3000 预算， **付不起第 4 段**（差
   * 4000）——位置停在最后一个已付清的格（下标 3），差量记在 `IN_TRANSIT` 里。
   *
   * <p>第 2 天（从出发起第 2 天整，预算 48000）⇒ 走满 6 段（42000），差 1000 付不起第 7 段。走到下标 6 > 下标 3 证明
   * **余量跨日保留**（若预算按日重置或忘了 Δ 乘数，第 2 天仍会停在旧位置）。用 `MovementState` 三字段逐值断言。
   */
  @Test
  void stuckBetweenHexesCarriesTheRemainderAcrossDays() {
    List<HexCoord> line = straightLine(60);
    Unit mover = lineUnit(line, 1);
    MovementCost sevenThousand = new FlatStepCost(7000);

    MovementState day1 = UnitMoves.evaluate(mover, T0.plus(1), map(STEEP_65), sevenThousand);
    assertThat(day1.status()).as("第 1 天卡在两格之间").isEqualTo(MovementStatus.IN_TRANSIT);
    assertThat(day1.currentHex()).as("走满 3 段（21000）后落在下标 3").isEqualTo(line.get(3));
    assertThat(day1.nextHex()).as("下一格 = 下标 4").contains(line.get(4));
    assertThat(day1.remainingEdgeCostMillis()).as("第 4 段 7000 − 余 3000 = 差 4000").hasValue(4000L);

    MovementState day2 = UnitMoves.evaluate(mover, T0.plus(2), map(STEEP_65), sevenThousand);
    assertThat(day2.status()).isEqualTo(MovementStatus.IN_TRANSIT);
    assertThat(day2.currentHex())
        .as("第 2 天继续走（余量没丢）：48000÷7000 ⇒ 走满 6 段，落在下标 6")
        .isEqualTo(line.get(6));
    assertThat(day2.nextHex()).contains(line.get(7));
    assertThat(day2.remainingEdgeCostMillis()).as("第 7 段 7000 − 余 6000 = 差 1000").hasValue(1000L);
  }

  // ── R10：出发时刻冻结 ──────────────────────────────────────────────

  /** ★ R10：出发后改单位速度，`evaluate` 结果不变（`speedAtDeparture` 已冻结）。 */
  @Test
  void speedChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit();
    Unit faster =
        new Unit(
            departed.id(),
            departed.name(),
            departed.parent(),
            departed.position(),
            departed.manpower(),
            departed.equipment(),
            99,
            departed.mobilityPerMille(),
            departed.movement());

    assertThat(UnitMoves.evaluate(faster, T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .as("在途行程不得因参数变更而时间反演")
        .isEqualTo(
            UnitMoves.evaluate(departed, T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  /** ★ 同一条的机动性半：`mobilityAtDeparture` 冻结（成本函数读的是 `frozen` 视图）。 */
  @Test
  void mobilityChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit();
    Unit nimbler =
        new Unit(
            departed.id(),
            departed.name(),
            departed.parent(),
            departed.position(),
            departed.manpower(),
            departed.equipment(),
            departed.speed(),
            1000,
            departed.movement());

    assertThat(UnitMoves.evaluate(nimbler, T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isEqualTo(
            UnitMoves.evaluate(departed, T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  /**
   * ★ T2 正交性（spec §八 #10）：`UnitStatus` 与 `MovementStatus` 不混——ENGAGED 的单位带在途 `Movement` 时， {@link
   * UnitMoves#evaluate} 仍按路线进度给出 `IN_TRANSIT`（本类源码零改动）。
   */
  @Test
  void unitStatusIsOrthogonalToMovementStatus() {
    Unit engaged = withStatus(inTransit(), UnitStatus.ENGAGED);

    MovementState state =
        UnitMoves.evaluate(engaged, T0.plus(1), map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
    assertThat(state.currentHex()).isEqualTo(H12);
  }

  private static Unit withStatus(Unit unit, UnitStatus status) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.manpower(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        status,
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget());
  }

  @Test
  void noRouteOrEarlierThanDepartureIsACallerBug() {
    assertThatThrownBy(
            () -> UnitMoves.evaluate(unit(), T0, map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                UnitMoves.evaluate(
                    inTransit(), T0.plus(-1), map(STEEP_65), TerrainMovementCost.INSTANCE))
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

  // ── 日预算用例的夹具：N 格直线 + 每格固定成本的替身 ────────────────────

  /** {@code [1,1]…[1,N]} 的直线（相邻格逐格相邻：{@code (1,k)} 与 {@code (1,k+1)} 是六角邻格）。 */
  private static List<HexCoord> straightLine(int hexes) {
    List<HexCoord> line = new ArrayList<>();
    for (int r = 1; r <= hexes; r++) {
      line.add(new HexCoord(1, r));
    }
    return line;
  }

  /** 沿 {@code line} 行进、T0 出发、指定速度的单位（{@code position} 与成本无关，沿用夹具的 {@code [1,1]}）。 */
  private static Unit lineUnit(List<HexCoord> line, int speed) {
    Unit base = unit();
    Route route = new Route(List.of(line.get(0), line.get(line.size() - 1)), line);
    return new Unit(
        base.id(),
        base.name(),
        base.parent(),
        base.position(),
        base.manpower(),
        base.equipment(),
        speed,
        base.mobilityPerMille(),
        Optional.of(new Movement(route, T0, speed, base.mobilityPerMille())));
  }

  /** 每格固定毫 MP 的成本替身（**忽略地图**：本用例只判预算与格数，不判地形）。 */
  private static final class FlatStepCost implements MovementCost {

    private final long perStep;

    FlatStepCost(long perStep) {
      this.perStep = perStep;
    }

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return OptionalLong.of(perStep);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return perStep;
    }
  }
}
