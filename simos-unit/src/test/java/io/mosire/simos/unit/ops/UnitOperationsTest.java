package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 编制树操作面（M3 的 8 项 + T3 的编制命令 A 3 项 + T4 的编制命令 B 3 项 + T5 的命令链 2 项）：43 条 = 计划 12 条 + R-11-b 补的
 * placeAtClearsInTransitRoute + T2 补的三态速度 3 条 + T3 补的 9 条 + T4 补的 10 条 + T5 补的 8 条。
 */
class UnitOperationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId BRIGADE = new UnitId("u-brigade");
  private static final UnitId COMPANY = new UnitId("u-company");
  private static final UnitId LOST = new UnitId("u-lost");

  private static Unit unit(String id, Optional<String> parent, Optional<HexCoord> position) {
    return unit(id, parent, position, true);
  }

  /** 同上，`attached` 逐节点给（T3 的级联用例必须从 `false` 起步：缺省 `true` 会把"级联"整个掩盖掉）。 */
  private static Unit unit(
      String id, Optional<String> parent, Optional<HexCoord> position, boolean attached) {
    return unit(id, parent, position, attached, UnitStatus.MOVING);
  }

  /** 同上，`status` 逐节点给（T4 的合体前置：非 MOVING 的那条必须与"同格"分开测）。 */
  private static Unit unit(
      String id,
      Optional<String> parent,
      Optional<HexCoord> position,
      boolean attached,
      UnitStatus status) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        1000,
        Optional.empty(),
        status,
        new SegmentedSeries<>(List.of(new Segment<>(T0, attached)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty());
  }

  private static UnitState twoUnits() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(BRIGADE, unit("u-brigade", Optional.empty(), Optional.of(H11)));
    units.put(COMPANY, unit("u-company", Optional.of("u-brigade"), Optional.empty()));
    return new UnitState(units);
  }

  /** 真·无位置单位（R-11-a 取代写法）：整链（自身与父）都无位置，effectivePosition 必为空。 */
  private static UnitState withLost() {
    return UnitOperations.create(twoUnits(), unit("u-lost", Optional.empty(), Optional.empty()));
  }

  /** 无父、无位置的新兵（改编用例的 T0 基线：parent 在 T0 为空，才能证明"追加段的时间作用域"）。 */
  private static UnitState withRecruit() {
    return UnitOperations.create(twoUnits(), unit("u-recruit", Optional.empty(), Optional.of(H12)));
  }

  @Test
  void createRejectsDuplicateId() {
    assertThatThrownBy(
            () ->
                UnitOperations.create(
                    twoUnits(), unit("u-company", Optional.empty(), Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");
  }

  @Test
  void createRejectsAnUnknownParent() {
    assertThatThrownBy(
            () ->
                UnitOperations.create(
                    twoUnits(), unit("u-new", Optional.of("u-ghost"), Optional.of(H12))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createAcceptsAWellFormedUnit() {
    UnitState state =
        UnitOperations.create(
            twoUnits(), unit("u-new", Optional.of("u-brigade"), Optional.of(H12)));
    assertThat(state.units()).containsKey(new UnitId("u-new"));
  }

  @Test
  void reparentAppendsASegmentAndRejectsUnknownParents() {
    // ★ 取代说明（R-11-a 同族）：计划的正例把 COMPANY 改编给 BRIGADE——但 COMPANY 在 T0 就已是
    // BRIGADE 的下属，其 valueAt(T0) 恒非空，"T0 为空"断言对正确实现也红。改用无父新兵：
    // T0 空 → T10 起 BRIGADE，恰好证明"追加段"（替换式覆写或跳过追加都会红）。
    UnitState recruit = withRecruit();
    UnitState state =
        UnitOperations.reparent(recruit, new UnitId("u-recruit"), Optional.of(BRIGADE), T10);
    assertThat(state.units().get(new UnitId("u-recruit")).parent().valueAt(T10)).contains(BRIGADE);
    assertThat(state.units().get(new UnitId("u-recruit")).parent().valueAt(T0)).isEmpty();
    assertThat(recruit.units().get(new UnitId("u-recruit")).parent().segments())
        .as("纯函数：旧状态不变")
        .hasSize(1);

    assertThatThrownBy(
            () ->
                UnitOperations.reparent(
                    twoUnits(), COMPANY, Optional.of(new UnitId("u-ghost")), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), new UnitId("u-ghost"), Optional.empty(), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reparentOntoItselfThrows() {
    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), COMPANY, Optional.of(COMPANY), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renameChangesOnlyTheName() {
    UnitState state = UnitOperations.rename(twoUnits(), COMPANY, "一营指挥部");
    assertThat(state.units().get(COMPANY).name()).isEqualTo("一营指挥部");
    assertThat(state.units().get(COMPANY).member()).isEqualTo(100);
    assertThatThrownBy(() -> UnitOperations.rename(twoUnits(), new UnitId("u-ghost"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setStrengthReplacesMemberAndEquipment() {
    UnitState state = UnitOperations.setStrength(twoUnits(), COMPANY, 80, Map.of("炮", 4));
    assertThat(state.units().get(COMPANY).member()).isEqualTo(80);
    assertThat(state.units().get(COMPANY).equipment()).containsOnlyKeys("炮");
    assertThatThrownBy(
            () -> UnitOperations.setStrength(twoUnits(), new UnitId("u-ghost"), 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void placeAtAppendsAPositionSegmentAndClearsTheRoute() {
    UnitState state = UnitOperations.placeAt(twoUnits(), BRIGADE, Optional.of(H12), T10);
    assertThat(state.units().get(BRIGADE).position().valueAt(T10)).contains(H12);
    assertThat(state.units().get(BRIGADE).position().valueAt(T0)).contains(H11);
    assertThatThrownBy(
            () -> UnitOperations.placeAt(twoUnits(), new UnitId("u-ghost"), Optional.of(H12), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ R-11-b 补条（spec §4.6 第 5 条的靶子）：placeAt 必须顺带清空在途路线。 */
  @Test
  void placeAtClearsInTransitRoute() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState planned = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    UnitState state = UnitOperations.placeAt(planned, BRIGADE, Optional.of(H12), T10);
    assertThat(state.units().get(BRIGADE).movement()).isEmpty();
    assertThat(state.units().get(BRIGADE).position().valueAt(T10)).contains(H12);
  }

  @Test
  void planRouteRequiresAStartThatMatchesTheEffectivePosition() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState state = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(state.units().get(BRIGADE).movement()).isPresent();
    assertThat(state.units().get(BRIGADE).movement().orElseThrow().route()).isEqualTo(route);

    // ★ R-11-a 取代说明：计划断言"COMPANY 整链无位置 ⇒ 抛"不成立——COMPANY 无自身位置，
    // 但 effectivePosition 会沿父链继承 BRIGADE 的 H11（Task 6 的 R7 语义），起点对得上 ⇒ 应成功。
    // 正例钉住继承语义：
    assertThat(
            UnitOperations.planRoute(twoUnits(), COMPANY, route, T10)
                .units()
                .get(COMPANY)
                .movement())
        .isPresent();
    // "无位置 ⇒ 抛"的真靶子：u-lost 整链（自身与父）都无位置 ⇒ 抛。
    assertThatThrownBy(() -> UnitOperations.planRoute(withLost(), LOST, route, T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("位置");
    // 起点对不上：抛
    assertThatThrownBy(
            () ->
                UnitOperations.planRoute(
                    twoUnits(), BRIGADE, new Route(List.of(H12, H11), List.of(H12, H11)), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.planRoute(twoUnits(), new UnitId("u-ghost"), route, T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancelRouteClearsTheMovement() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState planned = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(UnitOperations.cancelRoute(planned, BRIGADE).units().get(BRIGADE).movement())
        .isEmpty();
    assertThatThrownBy(() -> UnitOperations.cancelRoute(twoUnits(), new UnitId("u-ghost")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disbandRefusesWhileSubordinatesExist() {
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), BRIGADE, T10))
        .as("在 at 时刻有下属 ⇒ 先改编子单位、再解散")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");

    UnitState state = UnitOperations.disband(twoUnits(), COMPANY, T10);
    assertThat(state.units()).doesNotContainKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), new UnitId("u-ghost"), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 解散的时点敏感：改编走在前 ⇒ 同一对单位在**晚一点的时刻**可以解散。 */
  @Test
  void disbandIsTimeSensitive() {
    UnitState reparented =
        UnitOperations.reparent(twoUnits(), COMPANY, Optional.empty(), SimosTimestamp.of(20));
    assertThat(UnitOperations.disband(reparented, BRIGADE, SimosTimestamp.of(20)).units())
        .containsKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(reparented, BRIGADE, T10))
        .as("在 T10 时刻 COMPANY 仍挂在 BRIGADE 下")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 三态速度（T2 / spec §三.2 / P6） ─────────────────────────────

  private static final Route CORNER = new Route(List.of(H11, H12), List.of(H11, H12));

  /** 单个无父、位于 H11、指定 speed 与 status 的单位状态。 */
  private static UnitState soloUnit(int speed, UnitStatus status) {
    Unit unit =
        new Unit(
            new UnitId("u-solo"),
            "独立连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of(),
            speed,
            1000,
            Optional.empty(),
            status,
            new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
            Optional.empty());
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(unit.id(), unit);
    return new UnitState(units, Map.of());
  }

  private static int departureSpeed(int speed, UnitStatus status) {
    UnitState state =
        UnitOperations.planRoute(soloUnit(speed, status), new UnitId("u-solo"), CORNER, T10);
    return state.units().get(new UnitId("u-solo")).movement().orElseThrow().speedAtDeparture();
  }

  /** ★ 判据：同单位同路线，三态出发速度 8 / 4 / 2（可区分且有序）——m1/m2 的靶子。 */
  @Test
  void planRouteFreezesTheStatusScaledSpeed() {
    assertThat(departureSpeed(8, UnitStatus.MOVING)).isEqualTo(8);
    assertThat(departureSpeed(8, UnitStatus.RESTING)).isEqualTo(4);
    assertThat(departureSpeed(8, UnitStatus.ENGAGED)).isEqualTo(2);
  }

  /** ★ 判据：在途改状态**不回溯**——已冻结的 `speedAtDeparture` 与已走路程不变（m3 的靶子）。 */
  @Test
  void changingStatusInFlightDoesNotRetroactivelyChangeTheFrozenSpeed() {
    UnitState departed =
        UnitOperations.planRoute(soloUnit(8, UnitStatus.MOVING), new UnitId("u-solo"), CORNER, T10);
    int frozen =
        departed.units().get(new UnitId("u-solo")).movement().orElseThrow().speedAtDeparture();

    UnitState resting =
        UnitOperations.setStatus(departed, new UnitId("u-solo"), UnitStatus.RESTING);

    assertThat(resting.units().get(new UnitId("u-solo")).status()).isEqualTo(UnitStatus.RESTING);
    assertThat(
            resting.units().get(new UnitId("u-solo")).movement().orElseThrow().speedAtDeparture())
        .as("在途不回溯")
        .isEqualTo(frozen);
    assertThat(resting.units().get(new UnitId("u-solo")).movement().orElseThrow())
        .as("已冻结的整条 Movement 一字不变")
        .isEqualTo(departed.units().get(new UnitId("u-solo")).movement().orElseThrow());
  }

  @Test
  void setStatusRejectsUnknownId() {
    assertThatThrownBy(
            () -> UnitOperations.setStatus(twoUnits(), new UnitId("u-ghost"), UnitStatus.RESTING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 编制命令 A（T3 / spec §一.3 / P2 / P3） ──────────────────────

  private static final SimosTimestamp T20 = SimosTimestamp.of(20);
  private static final UnitId ROOT = new UnitId("u-root");
  private static final UnitId SUB = new UnitId("u-sub");
  private static final UnitId LEAF = new UnitId("u-leaf");
  private static final UnitId OTHER = new UnitId("u-other");

  /**
   * 三层树 `u-root → u-sub → u-leaf` + 独立根 `u-other`；除 `parent` 外只有 `u-root` 有位置（`H11`，子节点无自身位置 ⇒ 可判
   * `effectivePosition` 的继承与偏移）。★ 三个根与 `u-other` 一律 `attached=false`：级联若溢出 `id` 的子树，会被"子树外不动"
   * 的断言抓住（缺省 `true` 会把级联整个掩盖掉）。
   *
   * <p>★ 注意：本夹具的 `u-sub` **无自身位置且它的父在别格**（H11）——它**不能**用于 attach 正例（attach 现在要求"同格"，
   * 且会清掉子树自身位置）。attach 正例一律用 {@link #attachableFormation()}。
   */
  private static UnitState formation(boolean subAttached, boolean leafAttached) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(ROOT, unit("u-root", Optional.empty(), Optional.of(H11), false));
    units.put(OTHER, unit("u-other", Optional.empty(), Optional.of(H12), false));
    units.put(SUB, unit("u-sub", Optional.of("u-root"), Optional.empty(), subAttached));
    units.put(LEAF, unit("u-leaf", Optional.of("u-sub"), Optional.empty(), leafAttached));
    return new UnitState(units);
  }

  /**
   * ★ **attach 的同格基线（本次改动新增）**：树形与 {@link #formation} 相同，但 `u-sub` 带**自身**位置 `H12`（= `u-other` 的格）⇒
   * 满足 attach 的"同格"前提。`u-leaf` 仍无自身位置（用来钉级联清位）。
   */
  private static UnitState attachableFormation() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(ROOT, unit("u-root", Optional.empty(), Optional.of(H11), false));
    units.put(OTHER, unit("u-other", Optional.empty(), Optional.of(H12), false));
    units.put(SUB, unit("u-sub", Optional.of("u-root"), Optional.of(H12), false));
    units.put(LEAF, unit("u-leaf", Optional.of("u-sub"), Optional.empty(), false));
    return new UnitState(units);
  }

  /**
   * ★★ 判据（P3 + 本次改动的"进入跟随"）：attach **级联**——`id` 与其全部后代都 `attached=true`；只有 `id` 换父，后代的 `parent`
   * 不动；**子树每个节点的自身位置都被清掉**（进入"移动时跟随"）。
   */
  @Test
  void attachCascadesAttachedToTheWholeSubtreeAndClearsPositions() {
    UnitState base = attachableFormation();
    UnitState state = UnitOperations.attachSubtree(base, SUB, OTHER, T10);

    assertThat(state.units().get(SUB).parent().valueAt(T10)).contains(OTHER);
    assertThat(state.units().get(SUB).attached().valueAt(T10)).as("u-sub").isTrue();
    assertThat(state.units().get(LEAF).attached().valueAt(T10)).as("u-leaf（后代也 true）").isTrue();
    assertThat(state.units().get(LEAF).parent().valueAt(T10)).as("后代父不动").contains(SUB);
    assertThat(state.units().get(ROOT).attached().valueAt(T10)).as("原父不在子树内").isFalse();
    assertThat(state.units().get(OTHER).parent().valueAt(T10)).as("新父不动").isEmpty();
    assertThat(state.units().get(SUB).attached().valueAt(T0)).as("T0 仍是旧值（追加段）").isFalse();
    // ★ 本次改动的核心：attach 把子树每个节点的自身位置清掉 ⇒ 进入跟随
    assertThat(state.units().get(SUB).position().valueAt(T10)).as("u-sub 的自身位置被清（进入跟随）").isEmpty();
    assertThat(state.units().get(LEAF).position().valueAt(T10)).as("后代也被清位").isEmpty();
    assertThat(state.effectivePosition(SUB, T10)).as("清位后向新父取位（u-other 的 H12）").contains(H12);
    assertThat(state.units().get(SUB).position().segments()).as("是追加段，不是覆写").hasSize(2);
    assertThat(base.units().get(SUB).parent().segments()).as("纯函数：旧状态不变").hasSize(1);
    assertThat(base.units().get(SUB).attached().segments()).hasSize(1);
    assertThat(base.units().get(SUB).position().segments()).as("纯函数：旧状态不变").hasSize(1);
  }

  /**
   * ★★ 判据（本次改动新增的同格前提）：attach 前用有效位置比较**子树根**与**新父**——不同格、或任一侧不可确定 ⇒ 拒（理由带 "同格"）。这是据方案 B 加的连带裁定（见
   * {@code UnitOperations} 类 javadoc）。
   */
  @Test
  void attachRejectsADifferentHexOrAnIndeterminatePosition() {
    assertThatThrownBy(() -> UnitOperations.attachSubtree(formation(true, true), SUB, OTHER, T10))
        .as("u-sub 的有效位置是 u-root 的 H11，u-other 在 H12 ⇒ 不同格")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同格");
    assertThatThrownBy(() -> UnitOperations.attachSubtree(formation(false, false), SUB, OTHER, T10))
        .as("u-sub detached 且无自身位置 ⇒ 位置不可确定")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同格");
  }

  /** ★★ 判据（本次改动的"父动子随"）：同格 attach 后，父移到别格 ⇒ 子（含后代）的有效位置跟着过去。 */
  @Test
  void attachThenParentMovesAndTheSubtreeFollows() {
    UnitState attached = UnitOperations.attachSubtree(attachableFormation(), SUB, OTHER, T10);
    assertThat(attached.effectivePosition(SUB, T10)).contains(H12);

    UnitState moved = UnitOperations.placeAt(attached, OTHER, Optional.of(H13), T20);
    assertThat(moved.effectivePosition(SUB, T20)).as("父动 ⇒ 子随").contains(H13);
    assertThat(moved.effectivePosition(LEAF, T20)).as("后代（u-leaf 仍无自身位置）也随之").contains(H13);
  }

  /** ★ 判据（T3/P2 的既有语义）：无自身位置 + attached 的单位，父动它随——这是"跟随"的纯函数面。 */
  @Test
  void aUnitWithoutItsOwnPositionFollowsItsParent() {
    UnitState base = twoUnits();
    assertThat(base.effectivePosition(COMPANY, T10))
        .as("u-company 无自身位置 ⇒ 取父 u-brigade 的 H11")
        .contains(H11);
    UnitState moved = UnitOperations.placeAt(base, BRIGADE, Optional.of(H12), T20);
    assertThat(moved.effectivePosition(COMPANY, T20)).as("父动子随").contains(H12);
  }

  /** ★ 判据：成环 ⇒ op 内**先显式拒**（可读理由），状态不变。 */
  @Test
  void attachRejectsAParentInsideTheSubtree() {
    UnitState base = formation(false, false);
    assertThatThrownBy(() -> UnitOperations.attachSubtree(base, SUB, LEAF, T10))
        .as("u-leaf 是 u-sub 的后代")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(() -> UnitOperations.attachSubtree(base, SUB, SUB, T10))
        .as("自身也是子树的一员")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThat(base.units().get(SUB).parent().segments()).as("拒绝 ⇒ 状态不变").hasSize(1);
    assertThat(base.units().get(SUB).attached().segments()).hasSize(1);
  }

  @Test
  void attachRejectsUnknownUnits() {
    UnitState base = formation(false, false);
    assertThatThrownBy(() -> UnitOperations.attachSubtree(base, new UnitId("u-ghost"), OTHER, T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
    assertThatThrownBy(() -> UnitOperations.attachSubtree(base, SUB, new UnitId("u-ghost"), T10))
        .as("父不存在同样拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("父单位不存在");
  }

  /** ★ 判据（P3 的不对称）：detach **只节点**——只 `id` 变 false，子节点不动；`parent` 也不动。 */
  @Test
  void detachTouchesOnlyTheNodeItself() {
    UnitState base = formation(true, true);
    UnitState state = UnitOperations.detachUnit(base, SUB, T10);

    assertThat(state.units().get(SUB).attached().valueAt(T10)).as("u-sub").isFalse();
    assertThat(state.units().get(LEAF).attached().valueAt(T10)).as("u-leaf（子节点）不动").isTrue();
    assertThat(state.units().get(SUB).parent().valueAt(T10)).as("detach 不改父").contains(ROOT);
    assertThat(state.units().get(SUB).attached().valueAt(T0)).as("T0 仍是旧值（追加段）").isTrue();
    assertThat(base.units().get(SUB).attached().segments()).as("纯函数：旧状态不变").hasSize(1);
  }

  @Test
  void detachRejectsARootAndUnknownUnits() {
    UnitState base = formation(true, true);
    assertThatThrownBy(() -> UnitOperations.detachUnit(base, ROOT, T10))
        .as("根没有可脱离的父")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已是根");
    assertThatThrownBy(() -> UnitOperations.detachUnit(base, OTHER, T10))
        .as("另一个根同判")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitOperations.detachUnit(base, new UnitId("u-ghost"), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
  }

  /** ★ 判据（P2）：attached + 无自身位置 + offset ⇒ 有效位置 = 父位 ⊕ 偏移；清偏移 ⇒ 回父位。 */
  @Test
  void anOffsetShiftsTheEffectivePositionOfAnAttachedChild() {
    UnitState base = formation(true, true);
    assertThat(base.effectivePosition(SUB, T10)).as("无偏移 ⇒ 父位").contains(H11);

    UnitState shifted =
        UnitOperations.setOffset(base, SUB, Optional.of(new RelativeOffset(1, 0)), T10);
    assertThat(shifted.effectivePosition(SUB, T10)).contains(new HexCoord(2, 1));
    assertThat(shifted.effectivePosition(LEAF, T10))
        .as("沿父链传播（u-leaf 无自身位置）")
        .contains(new HexCoord(2, 1));

    UnitState cleared =
        UnitOperations.setOffset(shifted, SUB, Optional.<RelativeOffset>empty(), T20);
    assertThat(cleared.effectivePosition(SUB, T20)).as("清偏移 ⇒ 回父位").contains(H11);
    assertThat(cleared.effectivePosition(SUB, T10)).as("T10 的历史值不受影响").contains(new HexCoord(2, 1));
    assertThat(shifted.units().get(SUB).offset().segments()).as("追加段").hasSize(2);
    assertThat(base.units().get(SUB).offset().segments()).as("纯函数：旧状态不变").hasSize(1);
  }

  /** ★ 判据（P2）：offset **不强制落在地图内**（这是相对父的站位，不是绝对坐标）。 */
  @Test
  void anOffsetIsNotRequiredToStayInsideTheMap() {
    UnitState state =
        UnitOperations.setOffset(
            formation(true, true), SUB, Optional.of(new RelativeOffset(-9999, 9999)), T10);
    assertThat(state.effectivePosition(SUB, T10)).contains(new HexCoord(1 - 9999, 1 + 9999));
  }

  /**
   * ★ 判据：detached + 无自身位置 ⇒ 空（不回退父）；即便带着偏移也仍是空。
   *
   * <p>★ **本次改动后就地校正**：detach 现在会把有效位置**物化进自身 `position`** ⇒ 这个形态**不再能经 `detachUnit` 造出**
   * （脱离后它自己有位置了）。但该 UnitState 级不变量仍然成立（spec §一.4 第五情形），故**直接构造**该形态来钉它， 不依赖任何命令路径。
   */
  @Test
  void aDetachedNodeWithoutItsOwnPositionHasNoEffectivePosition() {
    UnitState detached = formation(false, false); // u-sub/u-leaf 都 attached=false 且无自身位置
    assertThat(detached.effectivePosition(SUB, T10)).isEmpty();
    assertThat(detached.effectivePosition(LEAF, T10)).as("u-leaf 也 detached 且无位可给").isEmpty();

    UnitState shifted =
        UnitOperations.setOffset(detached, SUB, Optional.of(new RelativeOffset(1, 0)), T20);
    assertThat(shifted.effectivePosition(SUB, T20)).as("detached 即便有偏移也不回退父").isEmpty();
  }

  /**
   * ★★ 判据（本次改动的核心之一）：detach 把**脱离前**的有效位置物化进自身 `position`——顺序不能在改 `attached` 之后取位。
   *
   * <p>判据两半：(a) 脱离后有效位置 == 脱离前的位置（若顺序写反 ⇒ 取到空 ⇒ 这里红）；(b) 父再移动，它**不动**（已不再跟随）。 `u-sub` 无自身位置、经父
   * `u-root` 取 H11 ⇒ 正好检验"先算后翻"。
   */
  @Test
  void detachMaterializesTheCurrentPositionBeforeFlippingAttached() {
    UnitState base = formation(true, true);
    assertThat(base.effectivePosition(SUB, T10)).as("脱离前：经父取位").contains(H11);

    UnitState detached = UnitOperations.detachUnit(base, SUB, T10);
    assertThat(detached.units().get(SUB).position().valueAt(T10))
        .as("把位置写进了自身 position")
        .contains(H11);
    assertThat(detached.effectivePosition(SUB, T10))
        .as("★ 脱离后仍在原格（顺序写反 ⇒ 取到空 ⇒ 这里必红）")
        .contains(H11);

    UnitState moved = UnitOperations.placeAt(detached, ROOT, Optional.of(H12), T20);
    assertThat(moved.effectivePosition(SUB, T20)).as("脱离后父动它不动").contains(H11);
    assertThat(moved.effectivePosition(LEAF, T20))
        .as("u-leaf 仍跟随 u-sub ⇒ 也留在 H11（不随 u-root 去 H12）")
        .contains(H11);
  }

  /** ★ 判据（本次改动的边界）：脱离时当前有效位置**本来就空**（不在图上的子树）⇒ 仍允许，`position` 段写空。 */
  @Test
  void detachOfAnOffMapNodeKeepsItOffMap() {
    UnitState base = formation(false, false); // u-sub detached、无自身位置 ⇒ 有效位置空；但它有父 ⇒ detach 不拒
    UnitState detached = UnitOperations.detachUnit(base, SUB, T10);
    assertThat(detached.units().get(SUB).position().valueAt(T10)).isEmpty();
    assertThat(detached.effectivePosition(SUB, T10)).isEmpty();
  }

  @Test
  void setOffsetRejectsUnknownId() {
    assertThatThrownBy(
            () ->
                UnitOperations.setOffset(
                    formation(true, true), new UnitId("u-ghost"), Optional.empty(), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
  }

  // ── 编制命令 B（T4 / spec §一.3 / §一.4 / §一.5 表 / P4 / P9） ──

  private static final UnitId CHILD = new UnitId("u-child");
  private static final UnitId GRAND = new UnitId("u-grand");

  /**
   * 同格基线（T4 的两条合体前置要用**位置相等**与**状态**两个独立维度，故位置与状态都逐值给）：`u-root` 与 `u-child` 都在 `H11`（`u-child`
   * 有**自身**位置 ⇒ detached 时也定得出位置，"拆完还能同格"才成立），`u-child` 挂 `u-root`；它自己还带一个 下属
   * `u-grand`（同格，用来钉"合体是否级联"）。`childAttached` 同时给 `u-child`/`u-grand` 的起步归属——往返用例必须从 `true`
   * 起步，否则"拆分把它翻成 false"这一步看不出来。
   */
  private static UnitState sameHex(boolean childAttached, UnitStatus status) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(ROOT, unit("u-root", Optional.empty(), Optional.of(H11), false));
    units.put(
        CHILD, unit("u-child", Optional.of("u-root"), Optional.of(H11), childAttached, status));
    units.put(GRAND, unit("u-grand", Optional.of("u-child"), Optional.of(H11), childAttached));
    return new UnitState(units);
  }

  private static UnitState sameHex(UnitStatus status) {
    return sameHex(false, status);
  }

  /**
   * ★ 判据（spec §一.3 机制列 / §一.5 表 / §八 #6）：整树迁移——`rootId` 换父，**每个后代**都在**同一刻**被重新挂载。
   *
   * <p>逐 id 断言两件事：**值**（后代仍挂它本来的父，反扁平化）与**段**（`parent` 系列多出一段）。只断言值的话，"只改 root"
   * 那个变异体是**等价变体**（值全同），判据形同装饰——杀它的是段数。
   */
  @Test
  void reparentSubtreeRemountsEveryDescendantAtTheSameInstant() {
    UnitState base = formation(true, true);
    UnitState state = UnitOperations.reparentSubtree(base, SUB, Optional.of(OTHER), T10);

    assertThat(state.units().get(SUB).parent().valueAt(T10)).as("root 换父").contains(OTHER);
    assertThat(state.units().get(LEAF).parent().valueAt(T20)).as("后代不改挂（反扁平化）").contains(SUB);
    assertThat(state.units().get(LEAF).parent().valueAt(T0)).as("T0 的历史值不动").contains(SUB);
    assertThat(state.units().get(SUB).parent().segments()).as("root 的 parent 追加段").hasSize(2);
    assertThat(state.units().get(LEAF).parent().segments())
        .as("★ 后代也在同一刻落段（整树迁移；只改 root ⇒ 这里是 1）")
        .hasSize(2);
    assertThat(state.units().get(ROOT).parent().segments()).as("子树外不动").hasSize(1);
    assertThat(state.units().get(OTHER).parent().segments()).as("新父不动").hasSize(1);
    assertThat(state.effectivePosition(LEAF, T20)).as("整树跟着新父走（H12）").contains(H12);
    assertThat(state.units().get(ROOT)).as("原父一字不变").isEqualTo(base.units().get(ROOT));
    assertThat(base.units().get(SUB).parent().segments()).as("纯函数：旧状态不变").hasSize(1);
    assertThat(base.units().get(LEAF).parent().segments()).as("纯函数：旧状态不变").hasSize(1);
  }

  /** ★ 判据（spec §一.5 表）：新父落在 `rootId` 子树内（含自身）⇒ 拒，且理由是"子树"（不是构造期那句成环消息）。 */
  @Test
  void reparentSubtreeRejectsANewParentInsideTheSubtree() {
    UnitState base = formation(true, true);
    assertThatThrownBy(() -> UnitOperations.reparentSubtree(base, SUB, Optional.of(LEAF), T10))
        .as("u-leaf 是 u-sub 的后代")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(() -> UnitOperations.reparentSubtree(base, SUB, Optional.of(SUB), T10))
        .as("自身也是子树的一员")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThat(base.units().get(SUB).parent().segments()).as("拒绝 ⇒ 状态一字不变").hasSize(1);
    assertThat(base.units().get(LEAF).parent().segments()).as("拒绝 ⇒ 状态一字不变").hasSize(1);
  }

  @Test
  void reparentSubtreeRejectsUnknownUnits() {
    UnitState base = formation(true, true);
    assertThatThrownBy(
            () ->
                UnitOperations.reparentSubtree(
                    base, new UnitId("u-ghost"), Optional.of(OTHER), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
    assertThatThrownBy(
            () ->
                UnitOperations.reparentSubtree(base, SUB, Optional.of(new UnitId("u-ghost")), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("父单位不存在");
    assertThatThrownBy(() -> UnitOperations.reparentSubtree(base, SUB, null, T10))
        .as("null 是调用方的编程错误（命令边界只可能给出 Optional）：与「缺省」不同，直接 NPE")
        .isInstanceOf(NullPointerException.class);
  }

  /** ★ 判据（P4）：`parent` 缺省 ⇒ **提升为根**——本操作面唯一的"降为根"路径，绝不留下悬空的 parentId。 */
  @Test
  void reparentSubtreeWithoutAParentPromotesTheSubtreeToRoot() {
    UnitState state =
        UnitOperations.reparentSubtree(formation(true, true), SUB, Optional.empty(), T10);

    assertThat(state.units().get(SUB).parent().valueAt(T10)).as("不再是谁的下属").isEmpty();
    assertThat(state.units().get(LEAF).parent().valueAt(T10)).as("后代仍挂 u-sub").contains(SUB);
    assertThat(state.units().get(LEAF).parent().segments()).as("后代也落段").hasSize(2);
    assertThat(state.effectivePosition(SUB, T20)).as("无父 ⇒ 位置来源断了").isEmpty();
    assertThat(state.effectivePosition(LEAF, T20)).as("后代随之无位可继承").isEmpty();
  }

  /** ★ 判据（P3 不对称）：拆**只节点**——被拆的节点 `attached=false`，它**自己的后代不动**；`parent` 也不动。 */
  @Test
  void splitFormationDetachesOnlyTheNamedNodes() {
    UnitState base = formation(true, true);
    UnitState state = UnitOperations.splitFormation(base, ROOT, List.of(SUB), T10);

    assertThat(state.units().get(SUB).attached().valueAt(T10)).as("u-sub").isFalse();
    assertThat(state.units().get(LEAF).attached().valueAt(T10)).as("u-leaf（后代）不动").isTrue();
    assertThat(state.units().get(SUB).parent().valueAt(T10)).as("拆不改父").contains(ROOT);
    assertThat(state.units().get(SUB).attached().valueAt(T0)).as("T0 仍是旧值（追加段）").isTrue();
    assertThat(state.units().get(SUB).attached().segments()).as("只追加一段").hasSize(2);

    UnitState both = UnitOperations.splitFormation(base, ROOT, List.of(SUB, LEAF), T10);
    assertThat(both.units().get(LEAF).attached().valueAt(T10)).as("一次可指名多个目标").isFalse();

    UnitState dup = UnitOperations.splitFormation(base, ROOT, List.of(SUB, SUB), T10);
    assertThat(dup.units().get(SUB).attached().segments())
        .as("重复项去重：同一目标在同一刻只追加一段（否则撞严格升序）")
        .hasSize(2);

    UnitState self = UnitOperations.splitFormation(base, SUB, List.of(SUB), T10);
    assertThat(self.units().get(SUB).attached().valueAt(T10))
        .as("rootId 自身在子树内 ⇒ 把它从自己的父那里拆下来是合法的（detach 的既有语义）")
        .isFalse();
    assertThat(base.units().get(SUB).attached().segments()).as("纯函数：旧状态不变").hasSize(1);
  }

  /** ★ 判据（spec §一.5 表）：目标不在 root 子树 / 不存在 / 空名单 ⇒ 拒；"已是根"由 detachUnit 的既有语义接管。 */
  @Test
  void splitFormationRejectsTargetsOutsideTheSubtreeAndEmptyLists() {
    UnitState base = formation(true, true);
    assertThatThrownBy(() -> UnitOperations.splitFormation(base, ROOT, List.of(OTHER), T10))
        .as("u-other 是另一个根，不在 u-root 子树内")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(() -> UnitOperations.splitFormation(base, SUB, List.of(ROOT), T10))
        .as("父在子树外：子树是单向的")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(
            () -> UnitOperations.splitFormation(base, ROOT, List.of(new UnitId("u-ghost")), T10))
        .as("不存在 ⇒ 报「单位不存在」，不是「不在子树内」")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
    assertThatThrownBy(
            () -> UnitOperations.splitFormation(base, new UnitId("u-ghost"), List.of(SUB), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
    assertThatThrownBy(() -> UnitOperations.splitFormation(base, ROOT, List.of(), T10))
        .as("指不到任何目标的拆分是坏命令")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
    assertThatThrownBy(() -> UnitOperations.splitFormation(base, ROOT, List.of(ROOT), T10))
        .as("root 自身在子树内，但它在 at 已是根 ⇒ 由 detachUnit 拒（操作面不新增守卫）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已是根");
    assertThat(base.units().get(SUB).attached().segments()).as("拒绝 ⇒ 状态一字不变").hasSize(1);
  }

  /**
   * ★ 判据（spec §一.4）：**同格**与 **MOVING** 是**两个独立的前置条件**——同格但静止 ⇒ 拒；在移动但不同格 ⇒ 拒。
   * 位置"不可确定"是同格判据的另一半（任一侧为空同样拒）。
   */
  @Test
  void mergeFormationRequiresTheSameHexAndTheMovingStatus() {
    UnitState merged = UnitOperations.mergeFormation(sameHex(UnitStatus.MOVING), CHILD, ROOT, T10);
    assertThat(merged.units().get(CHILD).parent().valueAt(T10)).contains(ROOT);
    assertThat(merged.units().get(CHILD).parent().segments()).as("重新挂到同一个父 ⇒ 再追加一段").hasSize(2);
    assertThat(merged.units().get(CHILD).attached().valueAt(T10)).isTrue();
    assertThat(merged.units().get(CHILD).attached().valueAt(T0)).as("T0 仍是旧值").isFalse();

    assertThatThrownBy(
            () -> UnitOperations.mergeFormation(sameHex(UnitStatus.RESTING), CHILD, ROOT, T10))
        .as("同格了，但不在移动")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MOVING");
    assertThatThrownBy(
            () -> UnitOperations.mergeFormation(sameHex(UnitStatus.ENGAGED), CHILD, ROOT, T10))
        .as("同格，交战中同样拒（三态里只有 MOVING 放行）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MOVING");

    assertThatThrownBy(() -> UnitOperations.mergeFormation(formation(true, true), SUB, OTHER, T10))
        .as("u-sub 的有效位置是 u-root 的 H11，u-other 在 H12（都在移动，仍拒）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同格");
    assertThatThrownBy(() -> UnitOperations.mergeFormation(formation(false, false), SUB, ROOT, T10))
        .as("u-sub 已 detached 且无自身位置 ⇒ 位置不可确定")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同格");
  }

  /** ★ 判据（§一.5 表把本命令的操作记作 attach + P3 级联 + P9 不销毁节点）：合体把 `childId` 的**整支编队**带回来。 */
  @Test
  void mergeFormationCascadesToTheChildsSubtree() {
    UnitState base = sameHex(UnitStatus.MOVING);
    UnitState merged = UnitOperations.mergeFormation(base, CHILD, ROOT, T10);

    assertThat(merged.units().get(CHILD).attached().valueAt(T10)).isTrue();
    assertThat(merged.units().get(GRAND).attached().valueAt(T10))
        .as("下属一起归队（复用 attach 的级联）")
        .isTrue();
    assertThat(merged.units().get(GRAND).parent().valueAt(T10)).as("下属的父不动").contains(CHILD);
    assertThat(merged.units().get(GRAND).attached().valueAt(T0)).as("是追加段，不是覆写").isFalse();
  }

  /** ★ 判据（spec §一.5 表）：环（`parentId` 是 `childId` 的后代）与不存在的 id 都拒。 */
  @Test
  void mergeFormationRejectsCyclesAndUnknownUnits() {
    UnitState base = sameHex(UnitStatus.MOVING);
    assertThatThrownBy(() -> UnitOperations.mergeFormation(base, CHILD, GRAND, T10))
        .as("u-grand 是 u-child 的后代，且同格 ⇒ 走到成环那条")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(() -> UnitOperations.mergeFormation(base, CHILD, CHILD, T10))
        .as("自身也在自己的子树内")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("子树");
    assertThatThrownBy(() -> UnitOperations.mergeFormation(base, new UnitId("u-ghost"), ROOT, T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
    assertThatThrownBy(() -> UnitOperations.mergeFormation(base, CHILD, new UnitId("u-ghost"), T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("父单位不存在");
  }

  /**
   * ★★ 判据（spec §一.4 / §八 E2 的核心场景）：**拆 → 合往返**。`detachUnit` 只翻 `attached`、**不碰 `parent`**，所以合体时
   * `childId` 的父**本来就是** `parentId`——"已是父 ⇒ 拒"这条守卫一旦有人补上，本用例当场红。这正是 T3 裁定 `AttachUnit`
   * 不判"已是父"的同一条（合体 = 同格前提下**重新 attach**，spec §一.5 表的回填注），T4 不得反向补回。
   */
  @Test
  void splittingThenMergingRoundTripsTheFormation() {
    UnitState base = sameHex(true, UnitStatus.MOVING);
    assertThat(base.units().get(CHILD).attached().valueAt(T0)).as("往返的起点：已归属").isTrue();

    UnitState split = UnitOperations.splitFormation(base, ROOT, List.of(CHILD), T10);
    assertThat(split.units().get(CHILD).attached().valueAt(T10)).isFalse();
    assertThat(split.units().get(GRAND).attached().valueAt(T10)).as("P3：拆只节点").isTrue();

    UnitState back = UnitOperations.mergeFormation(split, CHILD, ROOT, T20);
    assertThat(back.units().get(CHILD).parent().valueAt(T20)).as("同一个父，照样重挂").contains(ROOT);
    assertThat(back.units().get(CHILD).parent().segments()).as("重挂 ⇒ 再追加一段").hasSize(2);
    assertThat(back.units().get(CHILD).attached().valueAt(T20)).as("往返回到已归属").isTrue();
    assertThat(back.units().get(CHILD).attached().valueAt(T10)).as("中间那段历史留着（T10 拆过）").isFalse();
    assertThat(back.effectivePosition(CHILD, T20)).contains(H11);
    assertThat(back.units().get(GRAND).attached().valueAt(T20)).as("合体把下属一起带回来").isTrue();
  }

  // ── 命令链（T5 / spec §一.2 / §五.2 / §五.3 / P11） ──────────────

  private static final CommandChainId C1 = new CommandChainId("c-1");
  private static final CommandChainId C2 = new CommandChainId("c-2");

  /**
   * `formation(true, true)` + 两条链（`LinkedHashMap` 保序 ⇒ 遍历顺序确定，拒绝消息里报的是哪一条链可判）： c-1 的 commander 是
   * `u-root`、成员 `{u-root, u-sub}`；c-2 的 commander 是 `u-sub`、成员 `{u-sub, u-leaf}`。
   *
   * <p>★ 这个夹具同时是三个判据的载体：**多属**（`u-sub` 同属两条链）、**交叉**（`u-sub` 在 c-1 里是成员、在 c-2 里是 commander ⇒
   * 链不构成层级，没有环要防）、**链外对照**（`u-other` 不在任何链里）。
   */
  private static UnitState chained() {
    return chained(formation(true, true));
  }

  /** 在给定编制树之上加两条链（T5-U2 的"改单位字段不得清链"判据可换不同树形复用）。 */
  private static UnitState chained(UnitState units) {
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(C1, new CommandChain(C1, "第一链", ROOT, Set.of(ROOT, SUB)));
    chains.put(C2, new CommandChain(C2, "第二链", SUB, Set.of(SUB, LEAF)));
    return units.withCommandChains(chains);
  }

  /** ★ 判据（spec §五.3 第 1 条）：建链的 happy path + 三条拒绝（id 已在 / commander 不存在 / 成员不存在）。 */
  @Test
  void createChainAppliesTheChainAndRejectsDuplicatesAndDanglingReferences() {
    UnitState base = formation(true, true);
    UnitState one =
        UnitOperations.createChain(base, new CommandChain(C1, "第一链", ROOT, Set.of(ROOT, SUB)));

    assertThat(one.commandChains()).containsOnlyKeys(C1);
    assertThat(one.commandChains().get(C1).name()).isEqualTo("第一链");
    assertThat(one.commandChains().get(C1).commander()).isEqualTo(ROOT);
    assertThat(one.commandChains().get(C1).members()).containsExactlyInAnyOrder(ROOT, SUB);
    assertThat(one.units()).as("建链不动 units").isEqualTo(base.units());

    assertThatThrownBy(
            () -> UnitOperations.createChain(one, new CommandChain(C1, "又来一条", ROOT, Set.of(ROOT))))
        .as("链 id 已在")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在")
        .hasMessageContaining("c-1");
    assertThatThrownBy(
            () ->
                UnitOperations.createChain(
                    base,
                    new CommandChain(
                        C1, "幽灵链", new UnitId("u-ghost"), Set.of(new UnitId("u-ghost")))))
        .as("commander 不在 units 里")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commander 不存在")
        .hasMessageContaining("u-ghost");
    assertThatThrownBy(
            () ->
                UnitOperations.createChain(
                    base, new CommandChain(C1, "幽灵成员", ROOT, Set.of(ROOT, new UnitId("u-ghost")))))
        .as("成员不在 units 里")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成员不存在")
        .hasMessageContaining("u-ghost");

    assertThat(base.commandChains()).as("纯函数：旧状态不变").isEmpty();
    assertThat(one.commandChains()).as("拒绝不改已建好的那条").containsOnlyKeys(C1);
  }

  /**
   * ★★ 判据（spec §一.2 / P11）：**多属是正常态**，链**不构成层级**——`u-sub` 在 c-1 里是成员、在 c-2 里是 commander，
   * 两条链各自成立；再建第三条把 `u-root` 同时挂成成员，`createChain` 一次都不许抛。
   *
   * <p>本用例是「链不需要环校验」的**显式**判据：唯一防环的是编制树（{@code UnitState} 构造期那套），链是扁平星形， 有人往 {@code createChain}
   * 里补一条"链成环"检查，这里就红（那检查没有可判定的语义）。
   */
  @Test
  void aUnitMayBelongToSeveralChainsAndChainsMayCrossFreely() {
    UnitState base = formation(true, true);
    UnitState two =
        UnitOperations.createChain(
            UnitOperations.createChain(base, chain(C1, "第一链", ROOT, ROOT, SUB)),
            chain(C2, "第二链", SUB, SUB, LEAF));

    assertThat(two.commandChains().get(C1).members()).as("u-sub 是 c-1 的成员").contains(SUB);
    assertThat(two.commandChains().get(C2).commander())
        .as("同一个 u-sub 又是 c-2 的 commander")
        .isEqualTo(SUB);
    assertThat(two.commandChains().get(C2).members()).containsExactlyInAnyOrder(SUB, LEAF);

    UnitState three =
        UnitOperations.createChain(
            two, chain(new CommandChainId("c-3"), "第三链", OTHER, OTHER, ROOT));
    assertThat(three.commandChains())
        .as("三条链共存")
        .containsOnlyKeys(C1, C2, new CommandChainId("c-3"));
    assertThat(three.commandChains().get(new CommandChainId("c-3")).members())
        .as("u-root 在 c-1 里是 commander、在 c-3 里只是成员")
        .containsExactlyInAnyOrder(OTHER, ROOT);
    assertThat(three.units()).as("链是扁平星，编制树一字不动").isEqualTo(base.units());
  }

  /** 链的构造助手：commander 必在成员里（少写一次样板，免得把 `Set.of` 的重复项写成编译期错误）。 */
  private static CommandChain chain(
      CommandChainId id, String name, UnitId commander, UnitId... members) {
    return new CommandChain(id, name, commander, Set.of(members));
  }

  /**
   * ★★ 判据（spec §五.3 第 2/3 条）：三个字段**各自独立**——未给的原样不动（**不是清空**）；给了 `members` ⇒ 生效的
   * commander（给没给都由它定）必须落在**新** members 里；只给 `commander` ⇒ 必须落在**既有** members 里；三缺省合法。
   */
  @Test
  void updateChainTouchesOnlyTheFieldsThatWereGiven() {
    UnitState base = chained();

    UnitState renamed =
        UnitOperations.updateChain(
            base, C1, Optional.of("改过的名字"), Optional.empty(), Optional.empty());
    assertThat(renamed.commandChains().get(C1).name()).isEqualTo("改过的名字");
    assertThat(renamed.commandChains().get(C1).commander()).as("未给 commander ⇒ 不动").isEqualTo(ROOT);
    assertThat(renamed.commandChains().get(C1).members())
        .as("★ 未给 members ⇒ 不动（不是清空）")
        .containsExactlyInAnyOrder(ROOT, SUB);
    assertThat(renamed.commandChains().get(C2))
        .as("别的链一字不变")
        .isEqualTo(base.commandChains().get(C2));

    UnitState promoted =
        UnitOperations.updateChain(base, C1, Optional.empty(), Optional.of(SUB), Optional.empty());
    assertThat(promoted.commandChains().get(C1).commander()).isEqualTo(SUB);
    assertThat(promoted.commandChains().get(C1).members())
        .as("★ 只给 commander：members 仍是既有那组")
        .containsExactlyInAnyOrder(ROOT, SUB);
    assertThat(promoted.commandChains().get(C1).name()).as("名字也没被顺手清掉").isEqualTo("第一链");

    UnitState idle =
        UnitOperations.updateChain(base, C1, Optional.empty(), Optional.empty(), Optional.empty());
    assertThat(idle.commandChains().get(C1))
        .as("三缺省 ⇒ 合法（空转，不判无变化命令）")
        .isEqualTo(base.commandChains().get(C1));

    UnitState givenMembers =
        UnitOperations.updateChain(
            base, C1, Optional.empty(), Optional.empty(), Optional.of(List.of(ROOT, LEAF)));
    assertThat(givenMembers.commandChains().get(C1).members())
        .containsExactlyInAnyOrder(ROOT, LEAF);
    assertThat(givenMembers.commandChains().get(C1).commander())
        .as("未给 commander ⇒ 仍是 u-root，它落在新 members 里 ⇒ 放行")
        .isEqualTo(ROOT);

    UnitState movedCommander =
        UnitOperations.updateChain(
            base, C1, Optional.empty(), Optional.of(LEAF), Optional.of(List.of(LEAF)));
    assertThat(movedCommander.commandChains().get(C1).commander()).isEqualTo(LEAF);
    assertThat(movedCommander.commandChains().get(C1).members()).containsExactly(LEAF);

    assertThat(base.commandChains().get(C1).name()).as("纯函数：旧状态不变").isEqualTo("第一链");
    assertThat(base.commandChains().get(C1).commander()).as("纯函数：旧状态不变").isEqualTo(ROOT);
  }

  /** ★ 判据（spec §五.3 第 2/3 条的另一半）：四条拒绝——commander 被 members 挤出去、链不存在、成员不存在、空 members。 */
  @Test
  void updateChainRejectsCommandersOutsideTheEffectiveMembersAndDanglingReferences() {
    UnitState base = chained();

    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base, C1, Optional.empty(), Optional.empty(), Optional.of(List.of(SUB))))
        .as("★ 只给 members：生效 commander 仍是 u-root（链上原有），它被挤出去了")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在 members 内")
        .hasMessageContaining("c-1")
        .hasMessageContaining("先把它加进 members");
    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base, C1, Optional.empty(), Optional.of(OTHER), Optional.empty()))
        .as("★ 只给 commander：u-other 存在，但不在**既有** members 里")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在 members 内");
    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base,
                    C1,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of(ROOT, new UnitId("u-ghost")))))
        .as("新成员不存在")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成员不存在")
        .hasMessageContaining("u-ghost");
    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base,
                    C1,
                    Optional.empty(),
                    Optional.of(new UnitId("u-ghost")),
                    Optional.empty()))
        .as("新 commander 不存在")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commander 不存在");
    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base, C1, Optional.empty(), Optional.empty(), Optional.of(List.of())))
        .as("空 members 由同一句拒（不另设「不得为空」的守卫）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在 members 内");
    assertThatThrownBy(
            () ->
                UnitOperations.updateChain(
                    base,
                    new CommandChainId("c-404"),
                    Optional.of("改名"),
                    Optional.empty(),
                    Optional.empty()))
        .as("改一条不存在的链是坏命令，不是顺手建一条")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("链不存在")
        .hasMessageContaining("c-404");

    assertThat(base.commandChains().get(C1))
        .as("拒绝 ⇒ 链一字不变")
        .isEqualTo(chained().commandChains().get(C1));
  }

  /**
   * ★★ 判据（T5 / spec §一.2 不变量 2）：还在链上的单位不许解散——**两个方向都拒**，理由里带链 id 与"先改链" （命令边界要的是可读原因 + 可执行的下一步）。
   *
   * <p>★ 这条**先显式拒**而不是让 {@code UnitState} 构造期丢一句"链 X 的成员不在 units"：两处措辞刻意不同，
   * 否则删掉这一处后构造期兜底会说同样的话，"是哪一层拒的"就判不出来（T3/T4 的 m9 正是栽在这上面）。
   */
  @Test
  void disbandRejectsUnitsThatAreStillInACommandChain() {
    UnitState base = chained();

    assertThatThrownBy(() -> UnitOperations.disband(base, ROOT, T10))
        .as("c-1 的 commander")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("c-1")
        .hasMessageContaining("commander")
        .hasMessageContaining("先改链");
    assertThatThrownBy(() -> UnitOperations.disband(base, SUB, T10))
        .as("同属两条链、且两条都是成员方向 ⇒ 报遍历到的第一条（LinkedHashMap 保序 ⇒ c-1）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("c-1")
        .hasMessageContaining("成员")
        .hasMessageContaining("先改链");
    assertThatThrownBy(() -> UnitOperations.disband(base, LEAF, T10))
        .as("c-2 的成员（不是 commander）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("c-2")
        .hasMessageContaining("成员")
        .hasMessageContaining("先改链");

    assertThat(base.units()).as("拒绝 ⇒ 状态一字不变").containsKey(ROOT);
    assertThat(base.commandChains()).as("拒绝 ⇒ 链一字不变").isEqualTo(chained().commandChains());
  }

  /**
   * ★★ 判据（T5-U2）：链**外**的单位照样能解散，且解散**不碰** commandChains。
   *
   * <p>★ 靶子是 `disband` 的返回：改回 `new UnitState(next)`（1 参兼容构造器 ⇒ `commandChains = Map.of()`），
   * 本用例当场红——而"单位真的没了"照样成立，红的正是被保护的那一行。
   */
  @Test
  void disbandRemovesAChainFreeUnitAndKeepsTheChains() {
    UnitState base = chained();
    UnitState next = UnitOperations.disband(base, OTHER, T10);

    assertThat(next.units()).as("人没了").doesNotContainKey(OTHER);
    assertThat(next.units()).as("别的人一个不少").hasSize(base.units().size() - 1);
    assertThat(next.commandChains())
        .as("★ T5-U2：disband 走 state.withUnits ⇒ 链逐值活下来（旧写法会全清）")
        .isEqualTo(base.commandChains());
    assertThat(next.commandChains()).containsOnlyKeys(C1, C2);
  }

  /**
   * ★★ 判据（T5-U2，T3/T4 两处收口）：`attachSubtree` 与 `reparentSubtree` 都返回 `state.withUnits(next)` ⇒
   * 链逐值活下来。
   *
   * <p>两处的旧写法（`new UnitState(next)`）在 T5 之前一直是对的（那时没人建链）；建链命令一落地，它就成了**静默清空**。
   */
  @Test
  void formationCommandsKeepTheChains() {
    // ★ 本次改动：attach 现在要求"同格"，故这里换成同格树形（u-sub@H12 = u-other@H12）。
    UnitState base = chained(attachableFormation());

    UnitState attached = UnitOperations.attachSubtree(base, SUB, OTHER, T10);
    assertThat(attached.units().get(SUB).parent().valueAt(T10)).as("前提：改编真的发生了").contains(OTHER);
    assertThat(attached.commandChains()).as("attachSubtree 保链").isEqualTo(base.commandChains());

    UnitState migrated = UnitOperations.reparentSubtree(base, SUB, Optional.of(OTHER), T10);
    assertThat(migrated.units().get(SUB).parent().valueAt(T10)).as("前提：迁移真的发生了").contains(OTHER);
    assertThat(migrated.commandChains()).as("reparentSubtree 保链").isEqualTo(base.commandChains());

    assertThat(base.units().get(SUB).parent().valueAt(T10)).as("纯函数：旧状态不变").contains(ROOT);
    assertThat(base.commandChains()).as("纯函数：旧状态不变").isEqualTo(chained().commandChains());
  }

  /**
   * ★★ 判据（T5-U2，**最宽的那一处**）：私有助手 `withUnit` 是 rename / setStrength / placeAt / planRoute /
   * setStatus / cancelRoute / setOffset 七条操作的公共返回路径——逐个跑一遍，链必须逐值活下来。
   *
   * <p>改回 `new UnitState(next)` 时七条**全部**会清链，本用例（以及命令边界上那条同型用例）当场红。
   */
  @Test
  void everyWithUnitRoutedOperationKeepsTheChains() {
    UnitState base = chained();
    Map<CommandChainId, CommandChain> chains = base.commandChains();

    assertThat(UnitOperations.rename(base, SUB, "改个名").commandChains())
        .as("rename")
        .isEqualTo(chains);
    assertThat(UnitOperations.setStrength(base, SUB, 7, Map.of("炮", 1)).commandChains())
        .as("setStrength")
        .isEqualTo(chains);
    assertThat(UnitOperations.placeAt(base, SUB, Optional.of(H12), T10).commandChains())
        .as("placeAt")
        .isEqualTo(chains);
    assertThat(
            UnitOperations.planRoute(
                    base, SUB, new Route(List.of(H11, H12), List.of(H11, H12)), T10)
                .commandChains())
        .as("planRoute")
        .isEqualTo(chains);
    assertThat(UnitOperations.setStatus(base, SUB, UnitStatus.RESTING).commandChains())
        .as("setStatus")
        .isEqualTo(chains);
    assertThat(UnitOperations.cancelRoute(base, SUB).commandChains())
        .as("cancelRoute")
        .isEqualTo(chains);
    assertThat(
            UnitOperations.setOffset(base, SUB, Optional.of(new RelativeOffset(1, 0)), T10)
                .commandChains())
        .as("setOffset")
        .isEqualTo(chains);

    assertThat(base.commandChains()).as("纯函数：旧状态不变").isEqualTo(chained().commandChains());
  }

  // ── 稀疏路线（T6 / spec §二.2 / P10 / P12 / R4） ─────────────────

  private static final HexCoord H13 = new HexCoord(1, 3);

  /** 与走廊**不相邻**的孤岛：它的六个邻格一律不在图上 ⇒ 成本实现（单次查表判图外）对它返回空 ⇒ A\* 进不去。 */
  private static final HexCoord FAR = new HexCoord(5, 5);

  /**
   * 三格走廊 `H11→H12→H13` + 孤岛 `FAR(5,5)`（平坦地形 `moveCost 25`）。
   *
   * <p>孤岛在图上、但不可达——是"段不可达"的真靶子（不在图上 ⇒ `PathFinder` 直接空，判据弱一档）。
   */
  private static GameMap sparseMap() {
    TerrainType flat =
        new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "稀疏路线夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    hexes.put(FAR, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(flat.key(), flat);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), flat.key()),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 走路的人：`twoUnits()` 里的 BRIGADE（H11、mobility ‰1000 ⇒ 平坦格 25 毫 MP）。 */
  private static Unit walker() {
    return twoUnits().units().get(BRIGADE);
  }

  /**
   * ★★ 判据（P10 / **m1 靶子**）：非相邻 `waypoints` 展开成**逐格** `path`——`[1,1]→[1,3]` 必须补出中间的 `[1,2]`。
   *
   * <p>m1（回到 `new Route(waypoints, waypoints)`）在本用例当场红：`(1,1) → (1,3)` 不相邻，构造期就抛， 路线根本落不下来。
   */
  @Test
  void planSparseRouteFreezesTheExpandedPath() {
    UnitState next =
        UnitOperations.planSparseRoute(
            twoUnits(), BRIGADE, sparseMap(), List.of(H11, H13), TerrainMovementCost.INSTANCE, T10);
    Route route = next.units().get(BRIGADE).movement().orElseThrow().route();
    assertThat(route.waypoints()).as("waypoints 原样保留（它就是稀疏的）").containsExactly(H11, H13);
    assertThat(route.path()).as("path 逐格展开").containsExactly(H11, H12, H13);
    assertThat(next.units().get(BRIGADE).movement().orElseThrow().departedAt()).isEqualTo(T10);
  }

  /** ★ 判据（P10）：纯展开函数逐值可测——多段拼接的第二段丢掉与上一段重复的**连接点**。 */
  @Test
  void expandSparsePathJoinsSegmentsWithoutRepeatingTheJoint() {
    assertThat(
            UnitOperations.expandSparsePath(
                sparseMap(), walker(), List.of(H11, H13), TerrainMovementCost.INSTANCE))
        .as("单段：整段都在")
        .containsExactly(H11, H12, H13);
    assertThat(
            UnitOperations.expandSparsePath(
                sparseMap(), walker(), List.of(H11, H13, H12), TerrainMovementCost.INSTANCE))
        .as("两段：连接点 H13 只出现一次；★ 跨段回头造成的重复**不在这里去重**（R4 交给 Route 拒）")
        .containsExactly(H11, H12, H13, H12);
  }

  /**
   * ★★ 判据（P12 / **m2 靶子**）：任一段不可达 ⇒ **抛**，消息带"不可达"。
   *
   * <p>m2（`orElse(List.of())` 静默截断）在本用例红：截断后既不抛也不带"不可达"。★ 后半段的孤岛靶子比"图外格"强一档——
   * 它是"在图上但走不通"，与"根本没这格"区分开。
   */
  @Test
  void expandSparsePathRejectsAnUnreachableSegment() {
    assertThatThrownBy(
            () ->
                UnitOperations.expandSparsePath(
                    sparseMap(), walker(), List.of(H11, FAR), TerrainMovementCost.INSTANCE))
        .as("在图上但不可达")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不可达");
    assertThatThrownBy(
            () ->
                UnitOperations.expandSparsePath(
                    sparseMap(),
                    walker(),
                    List.of(H11, new HexCoord(9, 9)),
                    TerrainMovementCost.INSTANCE))
        .as("不在图上")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不可达");
    assertThatThrownBy(
            () ->
                UnitOperations.expandSparsePath(
                    sparseMap(), walker(), List.of(H11, H13, FAR), TerrainMovementCost.INSTANCE))
        .as("前一段可达、后一段不可达 ⇒ 同样抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不可达");
  }

  /**
   * ★★ 判据（§二.2 起点校验 / **m3 靶子**）：`waypoints` 首格 ≠ 单位在 `at` 的位置 ⇒ 抛，消息带"起点"。
   *
   * <p>★ 起点校验**不在本任务新写的代码里**：`planSparseRoute` 复用了既有 `planRoute` 的校验（spec §二.2「既有校验不动」）—— m3
   * 删的是那一处，故它同时会红既有 `PlanRoute` 的两个用例（报告里如实记）。
   */
  @Test
  void planSparseRouteRequiresAStartThatMatchesTheEffectivePosition() {
    assertThatThrownBy(
            () ->
                UnitOperations.planSparseRoute(
                    twoUnits(),
                    BRIGADE,
                    sparseMap(),
                    List.of(H12, H13),
                    TerrainMovementCost.INSTANCE,
                    T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("起点");
    assertThatThrownBy(
            () ->
                UnitOperations.planSparseRoute(
                    twoUnits(),
                    new UnitId("u-ghost"),
                    sparseMap(),
                    List.of(H11, H13),
                    TerrainMovementCost.INSTANCE,
                    T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
  }

  /**
   * ★★ 判据（**R4** / m4 靶子）：跨段回头 ⇒ 拼接出的 `path` 有重复格 ⇒ `Route` 构造期拒，消息带"重复"。
   *
   * <p>`[H11, H13, H11]`：两段各自都是简单路径（A\* 单段产物天然无重复），**拼起来**才重复——正是 R4 说的那种情形。
   */
  @Test
  void planSparseRouteRejectsACrossSegmentRepeat() {
    assertThatThrownBy(
            () ->
                UnitOperations.planSparseRoute(
                    twoUnits(),
                    BRIGADE,
                    sparseMap(),
                    List.of(H11, H13, H11),
                    TerrainMovementCost.INSTANCE,
                    T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }

  /** ★ 判据（§二.2 步骤 3）：`waypoints` 恰好逐格相邻时，稀疏命令与既有 `planRoute` 落下来的路线**逐值相同**。 */
  @Test
  void sparseExpansionMatchesPlanRouteForAdjacentWaypoints() {
    Route plain = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState viaPlanRoute = UnitOperations.planRoute(twoUnits(), BRIGADE, plain, T10);
    UnitState viaSparse =
        UnitOperations.planSparseRoute(
            twoUnits(), BRIGADE, sparseMap(), List.of(H11, H12), TerrainMovementCost.INSTANCE, T10);
    assertThat(viaSparse.units().get(BRIGADE).movement().orElseThrow().route())
        .isEqualTo(viaPlanRoute.units().get(BRIGADE).movement().orElseThrow().route());
  }

  /**
   * ★★ 判据（**T5-L4**，本轮新增路径的自证）：稀疏路线也走 `planRoute` → `withUnit` → `state.withUnits(...)` ⇒ 链逐值活下来。
   *
   * <p>★ 这条是**新代码的复发点守卫**：`planSparseRoute` 若在哪一步改回 `new UnitState(units)`，链会**静默清空**——编译器不响、门禁不响，
   * 只有这里会红。
   */
  @Test
  void planSparseRouteKeepsTheCommandChains() {
    UnitState base = chained();
    UnitState next =
        UnitOperations.planSparseRoute(
            base, ROOT, sparseMap(), List.of(H11, H13), TerrainMovementCost.INSTANCE, T10);
    assertThat(next.units().get(ROOT).movement().orElseThrow().route().path())
        .as("前提：路线真的落下来了")
        .containsExactly(H11, H12, H13);
    assertThat(next.commandChains()).as("★ T5-L4：链逐值活下来").isEqualTo(base.commandChains());
    assertThat(base.units().get(ROOT).movement()).as("纯函数：旧状态不变").isEmpty();
  }

  // ── 战损增量（T8 / spec §四 / E4 / N3 / P14） ──────────────────────

  /**
   * ★★ 判据（**m1 的靶子**）：Δ 是**增量**、落在当前值上——`100 + (−30) = 70`。
   *
   * <p>把 Δ 当**绝对值**（覆写）会让结果变成 30，本用例当场红。同时逐值钉住"未受战损的单位一字不动"。
   */
  @Test
  void applyCasualtiesSubtractsFromTheCurrentValueInsteadOfOverwriting() {
    UnitState base = twoUnits();
    UnitState next = UnitOperations.applyCasualties(base, BRIGADE, -30, Map.of("步枪", -10));

    Unit brigade = next.units().get(BRIGADE);
    assertThat(brigade.member()).as("★ 100 + (−30) = 70（不是 30、不是覆写）").isEqualTo(70);
    assertThat(brigade.equipment()).as("★ 装备同样是增量：50 + (−10) = 40").containsEntry("步枪", 40);
    assertThat(brigade.name()).as("其余字段原样带过").isEqualTo(base.units().get(BRIGADE).name());
    assertThat(brigade.position()).isEqualTo(base.units().get(BRIGADE).position());
    assertThat(next.units().get(COMPANY)).as("未受战损的单位一字不动").isEqualTo(base.units().get(COMPANY));
    assertThat(base.units().get(BRIGADE).member()).as("纯函数：旧状态不变").isEqualTo(100);
  }

  /**
   * ★★ 判据（**m3 的靶子**）：装备是**双轨增量**——只扣**提及**的键，未提及的键**保持不变**。
   *
   * <p>整表替换（`setStrength` 的语义）会把 `炮` 整条丢掉，本用例当场红。
   */
  @Test
  void applyCasualtiesLeavesUnmentionedEquipmentKeysUntouched() {
    // 两键基线由既有操作面造出：{步枪:50, 炮:4}（不手搓 Unit，免得绕过构造期校验）
    UnitState base = UnitOperations.setStrength(twoUnits(), BRIGADE, 100, Map.of("步枪", 50, "炮", 4));
    UnitState next = UnitOperations.applyCasualties(base, BRIGADE, 0, Map.of("步枪", -10));

    Unit brigade = next.units().get(BRIGADE);
    assertThat(brigade.equipment()).as("★ 只扣提及键").containsEntry("步枪", 40);
    assertThat(brigade.equipment()).as("★ 未提及键不变（整表替换会丢它）").containsEntry("炮", 4);
    assertThat(brigade.equipment()).containsOnlyKeys("步枪", "炮");
    assertThat(brigade.member()).as("人员 Δ=0 ⇒ 不动").isEqualTo(100);
  }

  /** ★★ 判据（**m2 的靶子**）：逐项上界 `|Δ| ≤ 当前值`；正 Δ 与越界都拒（战损只减员）。 */
  @Test
  void applyCasualtiesRejectsPositiveDeltasAndOutOfRangeAmounts() {
    UnitState base = twoUnits();

    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, 5, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人员增量必须 ≤ 0");
    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, -101, Map.of()))
        .as("★ 100 + (−101) 越界 ⇒ 拒（删掉上界校验会让它通过）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人员战损超出当前值");
    assertThat(
            UnitOperations.applyCasualties(base, BRIGADE, -100, Map.of())
                .units()
                .get(BRIGADE)
                .member())
        .as("上界本身合法：恰好 −100 ⇒ 0")
        .isZero();
    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, 0, Map.of("步枪", 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("装备增量必须 ≤ 0");
    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, 0, Map.of("步枪", -51)))
        .as("★ 装备逐项上界：50 + (−51) 越界 ⇒ 拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("装备战损超出当前值");
  }

  /**
   * ★★ 判据（**m4 的靶子** / P14）：**提及**一个不存在的装备键 ⇒ 拒（**不视作 0**）。
   *
   * <p>含 Δ=0 的形态：未知键即使"什么都不减"也是错误——它说明调用方对装备表的心智模型是错的。
   */
  @Test
  void applyCasualtiesRejectsUnknownEquipmentKeys() {
    UnitState base = twoUnits();

    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, 0, Map.of("炮", -1)))
        .as("★ 未知键 ⇒ 拒（视作 0 忽略会让它静默通过）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知装备键");
    assertThatThrownBy(() -> UnitOperations.applyCasualties(base, BRIGADE, 0, Map.of("炮", 0)))
        .as("未知键 + Δ=0 一样拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知装备键");
    assertThatThrownBy(
            () -> UnitOperations.applyCasualties(base, new UnitId("u-ghost"), -1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在");
  }

  /**
   * ★★ 判据（**T5-L4 通则**，本轮新路径的自证）：`applyCasualties` 走 `copy` → `withUnit` → `state.withUnits(...)` ⇒
   * `commandChains` 逐值活下来。
   *
   * <p>★ 这条是**新代码的复发点守卫**：若在哪一步改回 `new UnitState(units)`，链会**静默清空**——编译器不响、门禁不响，只有这里会红。 （既有的
   * `everyWithUnitRoutedOperationKeepsTheChains` 枚举了七条老操作，**本轮未改它**，故 T5 的旧证据不受影响。）
   */
  @Test
  void applyCasualtiesKeepsTheCommandChains() {
    UnitState base = chained();
    UnitState next = UnitOperations.applyCasualties(base, ROOT, -30, Map.of("步枪", -10));

    assertThat(next.units().get(ROOT).member()).as("前提：战损真的发生了").isEqualTo(70);
    assertThat(next.commandChains()).as("★ T5-L4：链逐值活下来").isEqualTo(base.commandChains());
    assertThat(base.commandChains()).as("纯函数：旧状态不变").isEqualTo(chained().commandChains());
  }
}
