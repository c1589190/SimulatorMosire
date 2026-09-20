package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 编制树操作面（M3 的 8 项 + T3 的编制命令 A 3 项）：25 条 = 计划 12 条 + R-11-b 补的 placeAtClearsInTransitRoute + T2
 * 补的三态速度 3 条 + T3 补的 9 条。
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
   */
  private static UnitState formation(boolean subAttached, boolean leafAttached) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(ROOT, unit("u-root", Optional.empty(), Optional.of(H11), false));
    units.put(OTHER, unit("u-other", Optional.empty(), Optional.of(H12), false));
    units.put(SUB, unit("u-sub", Optional.of("u-root"), Optional.empty(), subAttached));
    units.put(LEAF, unit("u-leaf", Optional.of("u-sub"), Optional.empty(), leafAttached));
    return new UnitState(units);
  }

  /** ★ 判据（P3）：attach **级联**——`id` 与其全部后代都 `attached=true`；只有 `id` 换父，后代的 `parent` 不动。 */
  @Test
  void attachCascadesAttachedToTheWholeSubtree() {
    UnitState base = formation(false, false);
    UnitState state = UnitOperations.attachSubtree(base, SUB, OTHER, T10);

    assertThat(state.units().get(SUB).parent().valueAt(T10)).contains(OTHER);
    assertThat(state.units().get(SUB).attached().valueAt(T10)).as("u-sub").isTrue();
    assertThat(state.units().get(LEAF).attached().valueAt(T10)).as("u-leaf（后代也 true）").isTrue();
    assertThat(state.units().get(LEAF).parent().valueAt(T10)).as("后代父不动").contains(SUB);
    assertThat(state.units().get(ROOT).attached().valueAt(T10)).as("原父不在子树内").isFalse();
    assertThat(state.units().get(OTHER).parent().valueAt(T10)).as("新父不动").isEmpty();
    assertThat(state.units().get(SUB).attached().valueAt(T0)).as("T0 仍是旧值（追加段）").isFalse();
    assertThat(base.units().get(SUB).parent().segments()).as("纯函数：旧状态不变").hasSize(1);
    assertThat(base.units().get(SUB).attached().segments()).hasSize(1);
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

  /** ★ 判据：detached + 无自身位置 ⇒ 空（不回退父）；即便带着偏移也仍是空。 */
  @Test
  void aDetachedNodeWithoutItsOwnPositionHasNoEffectivePosition() {
    UnitState detached = UnitOperations.detachUnit(formation(true, true), SUB, T10);
    assertThat(detached.effectivePosition(SUB, T10)).isEmpty();
    assertThat(detached.effectivePosition(LEAF, T10))
        .as("u-leaf 仍 attached，但 u-sub 无位可给")
        .isEmpty();

    UnitState shifted =
        UnitOperations.setOffset(detached, SUB, Optional.of(new RelativeOffset(1, 0)), T20);
    assertThat(shifted.effectivePosition(SUB, T20)).as("detached 即便有偏移也不回退父").isEmpty();
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
}
