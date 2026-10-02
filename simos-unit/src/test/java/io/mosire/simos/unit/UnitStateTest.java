package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 编制树不变量（R6）+ 位置继承（R7）。 */
class UnitStateTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  private static Unit unit(
      String id, Optional<String> parent, Optional<HexCoord> position, SimosTimestamp parentAt) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(parentAt, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(),
        2,
        1000,
        Optional.empty());
  }

  /** attached/offset 显式给出的单位（spec §一.4 五行情形的夹具）。 */
  private static Unit formedUnit(
      String id,
      Optional<String> parent,
      Optional<HexCoord> position,
      boolean attached,
      Optional<RelativeOffset> offset) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(),
        2,
        1000,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, attached)), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, offset)), List.of(), null),
        Optional.empty());
  }

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId, Map.of());
  }

  // ── R6 ──────────────────────────────────────────────────────────

  @Test
  void cycleAcrossUnitsThrowsAtConstruction() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), unit("a", Optional.of("b"), Optional.of(H11), T0));
    units.put(new UnitId("b"), unit("b", Optional.of("a"), Optional.of(H22), T0));

    assertThatThrownBy(() -> new UnitState(units))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成环");
  }

  @Test
  void legalReparentAcrossTimeIsNotACycle() {
    // t<10：a 无父、b 的父是 a（链 b→a）；t>=10：a 改编到 b 之下、b 同时脱离 a（链 a→b）。
    // 任一时刻都是链，**不是环**。
    // ★ 取代说明（执行期就地校正，计划原稿此处自相矛盾）：原稿只给 a 配段、b 仍是单段 [T0→a]——
    //   按 M1 时间语义"段值向前恒定延拓"，t≥10 时 a→b 与 b→a **同时在场**，是真环，计划自己的
    //   实现（逐关键时点查，spec §4.2"含 anchor 之前的恒定延拓"）必抛，实测确实抛了。合法改编
    //   要求**双方同刻各改一段**（子单位挂到新父、旧父脱离），故 b 补 [T10→空] 段。
    Unit a =
        new Unit(
            new UnitId("a"),
            "单位 a",
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(T0, Optional.<UnitId>empty()),
                    new Segment<>(T10, Optional.of(new UnitId("b")))),
                List.of(),
                null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(),
            2,
            1000,
            Optional.empty());
    Unit b =
        new Unit(
            new UnitId("b"),
            "单位 b",
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(T0, Optional.of(new UnitId("a"))),
                    new Segment<>(T10, Optional.<UnitId>empty())),
                List.of(),
                null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H22))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(),
            2,
            1000,
            Optional.empty());

    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), a);
    units.put(new UnitId("b"), b);
    UnitState state = new UnitState(units); // 不得抛
    assertThat(state.units()).hasSize(2);
  }

  // ── R7 ──────────────────────────────────────────────────────────

  // ── 位置（编制 v2：**就是自己的位置**，不再向父取） ──────────────

  @Test
  void effectivePositionIsTheUnitsOwnPositionAndNeverTheParents() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.empty(), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22);
    assertThat(state.effectivePosition(new UnitId("c"), T0))
        .as("★★ v2：没有自己的位置 ⇒ 空（旧语义「向父取」已作废——跟随取消了）")
        .isEmpty();
    assertThat(state.effectivePosition(new UnitId("c"), T0))
        .as("父在图上不等于我在图上：位置不是继承来的")
        .isNotEqualTo(Optional.of(H22));
  }

  @Test
  void ownPositionWinsOverParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.of(H11), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(H11);
  }

  @Test
  void noPositionAnywhereIsEmpty() {
    UnitState state =
        new UnitState(Map.of(new UnitId("c"), unit("c", Optional.empty(), Optional.empty(), T0)));
    assertThat(state.effectivePosition(new UnitId("c"), T0)).isEmpty();
    assertThat(state.effectivePosition(new UnitId("nobody"), T0)).as("查不存在的主体 ⇒ 空").isEmpty();
  }

  // ── spec §一.4 五行情形的取代/共存 ────────────────────────────────

  /** 行 5：attached=false、自身无位置 ⇒ 空（v2 下这条与 attached=true 同结果，见上面那条合并用例）。 */
  @Test
  void detachedChildWithoutPositionDoesNotFallBackToParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = formedUnit("c", Optional.of("p"), Optional.empty(), false, Optional.empty());
    UnitState state = stateOf(parent, child);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).isEmpty();
    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22); // 父自身不受影响
  }

  /** 行 4：attached=false、自身有位置 ⇒ 自身位置（detached 只取消继承，不取消自身位置）。 */
  @Test
  void detachedChildWithOwnPositionKeepsIt() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = formedUnit("c", Optional.of("p"), Optional.of(H11), false, Optional.empty());
    UnitState state = stateOf(parent, child);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(H11);
  }

  // ── 编制 v2（2026-09-24）：顶层 / 整支 / 整支速度 ────────────────

  /**
   * ★★ **attached 与 offset 都不再影响位置**（旧五行情形的行 2/3/5 合并成这一条）：无自身位置 ⇒ 空，无论 `attached` 是什么、`offset`
   * 多花哨。
   *
   * <p>★ 判别力：把 `effectivePosition` 改回"向父取"（或让 offset 参与）⇒ 本用例当场红。
   */
  @Test
  void withoutItsOwnPositionAUnitHasNoPositionNoMatterWhatAttachedOrOffsetSay() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit attachedWithOffset =
        formedUnit(
            "c1", Optional.of("p"), Optional.empty(), true, Optional.of(new RelativeOffset(2, -1)));
    Unit attachedNoOffset =
        formedUnit("c2", Optional.of("p"), Optional.empty(), true, Optional.empty());
    Unit detached = formedUnit("c3", Optional.of("p"), Optional.empty(), false, Optional.empty());
    UnitState state = stateOf(parent, attachedWithOffset, attachedNoOffset, detached);

    for (String id : new String[] {"c1", "c2", "c3"}) {
      assertThat(state.effectivePosition(new UnitId(id), T0))
          .as("单位 " + id + "：位置不是继承来的（attached/offset 都不参与）")
          .isEmpty();
    }
    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22); // 父自身不受影响
  }

  /**
   * ★★ **顶层判定**（编制 v2 的核心查询）：沿 parent 上溯，遇到 `attached=false` 就停；**无父者一律算顶层**。
   *
   * <p>★ 三个案例各自的判别力：① 全链 attached ⇒ 顶层是最上面那个；② 中间一个 attached=false ⇒ 它自己就是顶层
   * （"有归属但独立"，用户那句「即使这个单位有归属，也可以自动移动」）；③ 无父 + attached=true（worldgen 造出的军根） ⇒ 仍然算顶层（否则本局的军队根本动不了）。
   */
  @Test
  void formationRootStopsAtTheFirstIndependentOrParentlessAncestor() {
    Unit root = formedUnit("r", Optional.empty(), Optional.of(H22), false, Optional.empty());
    Unit mid = formedUnit("m", Optional.of("r"), Optional.of(H22), true, Optional.empty());
    Unit leaf = formedUnit("l", Optional.of("m"), Optional.of(H22), true, Optional.empty());
    Unit freeRider = formedUnit("f", Optional.of("m"), Optional.of(H22), false, Optional.empty());
    Unit sub = formedUnit("s", Optional.of("f"), Optional.of(H22), true, Optional.empty());
    Unit legacyRoot = formedUnit("g", Optional.empty(), Optional.of(H22), true, Optional.empty());
    UnitState state = stateOf(root, mid, leaf, freeRider, sub, legacyRoot);

    assertThat(state.formationRoot(new UnitId("l"), T0)).contains(new UnitId("r"));
    assertThat(state.formationRoot(new UnitId("m"), T0)).contains(new UnitId("r"));
    assertThat(state.formationRoot(new UnitId("f"), T0))
        .as("有归属（parent=m）但 attached=false ⇒ 它自己就是顶层")
        .contains(new UnitId("f"));
    assertThat(state.formationRoot(new UnitId("s"), T0))
        .as("顶层是它那个「独立」的父")
        .contains(new UnitId("f"));
    assertThat(state.formationRoot(new UnitId("g"), T0))
        .as("★ 无父 + attached=true（worldgen 现状）仍算顶层")
        .contains(new UnitId("g"));
    assertThat(state.formationRoot(new UnitId("nobody"), T0)).isEmpty();
  }

  /**
   * ★★ **整支成员**：自己 + 经 `attached=true` 链可达的后代；**detached 的后代另起一支**（不在本支里）。
   *
   * <p>★ 判别力：把遍历改成"所有后代都算"（不看 attached）⇒ 期望值里 `f`/`s` 会多出来 ⇒ 红。
   */
  @Test
  void formationMembersIncludeOnlyWhatFollowsThroughAttachedLinks() {
    Unit root = formedUnit("r", Optional.empty(), Optional.of(H22), false, Optional.empty());
    Unit mid = formedUnit("m", Optional.of("r"), Optional.of(H22), true, Optional.empty());
    Unit leaf = formedUnit("l", Optional.of("m"), Optional.of(H22), true, Optional.empty());
    Unit independent = formedUnit("f", Optional.of("m"), Optional.of(H22), false, Optional.empty());
    Unit follower = formedUnit("s", Optional.of("f"), Optional.of(H22), true, Optional.empty());
    UnitState state = stateOf(root, mid, leaf, independent, follower);

    assertThat(state.formationMembers(new UnitId("r"), T0))
        .as("r 带着 m 与 l；f 是独立的、它带着 s（不属于 r 那一支）")
        .containsExactlyInAnyOrder(new UnitId("r"), new UnitId("m"), new UnitId("l"));
    assertThat(state.formationMembers(new UnitId("f"), T0))
        .containsExactlyInAnyOrder(new UnitId("f"), new UnitId("s"));
    assertThat(state.formationMembers(new UnitId("nobody"), T0)).isEmpty();
  }

  /**
   * ★★ **整支速度 = 支内 `effectiveSpeed` 的最小值，含状态折算**（用户 2026-09-24 选的口径）。
   *
   * <p>装置：`speed` 都是 4，但那个下挂单位是 **RESTING**（折算 = ×500/1000 ⇒ 2）；MOVING 自己 = 4 ⇒ 整支速度 = 2。★
   * 判别力：把口径换成"不含状态折算"（直接比 `speed`）⇒ 期望 4 ⇒ 红。
   */
  @Test
  void formationSpeedTakesTheSlowestEffectiveSpeedIncludingStatus() {
    Unit root = movingUnit("r", Optional.empty(), 4, UnitStatus.MOVING);
    Unit fast = movingUnit("m", Optional.of("r"), 4, UnitStatus.MOVING);
    Unit resting = movingUnit("l", Optional.of("m"), 4, UnitStatus.RESTING);
    UnitState state = stateOf(root, fast, resting);

    assertThat(state.formationSpeed(new UnitId("r"), T0))
        .as("RESTING 的下挂折算成半速 ⇒ 整支 2")
        .isEqualTo(2);
    assertThat(state.formationSpeed(new UnitId("l"), T0)).as("从中间节点问也一样").isEqualTo(2);
  }

  private static Unit movingUnit(String id, Optional<String> parent, int speed, UnitStatus status) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(List.of(new Segment<>(T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H22))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(),
        speed,
        1000,
        Optional.empty(),
        status,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty());
  }

  // ── commandChains（spec §一.2 / §一.6 不变量 1、2） ────────────────

  /** 悬空引用（commander 或成员不在 units）⇒ 构造期抛（引用完整性）。 */
  @Test
  void danglingChainReferencesAreRejectedAtConstruction() {
    Unit only = unit("p", Optional.empty(), Optional.of(H22), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(only.id(), only);

    UnitId ghost = new UnitId("u-ghost");
    Map<CommandChainId, CommandChain> danglingCommander = new LinkedHashMap<>();
    danglingCommander.put(
        new CommandChainId("c-1"),
        new CommandChain(new CommandChainId("c-1"), "链", ghost, Set.of(ghost)));
    assertThatThrownBy(() -> new UnitState(units, danglingCommander))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commander");

    Map<CommandChainId, CommandChain> danglingMember = new LinkedHashMap<>();
    danglingMember.put(
        new CommandChainId("c-2"),
        new CommandChain(new CommandChainId("c-2"), "链", only.id(), Set.of(only.id(), ghost)));
    assertThatThrownBy(() -> new UnitState(units, danglingMember))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成员");
  }

  /** commandChains 保序不可变：迭代序 == 插入序（不得用 Map.copyOf）。 */
  @Test
  void commandChainsKeepInsertionOrder() {
    Unit p = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit q = unit("q", Optional.empty(), Optional.of(H11), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(p.id(), p);
    units.put(q.id(), q);
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(
        new CommandChainId("c-2"),
        new CommandChain(new CommandChainId("c-2"), "二", q.id(), Set.of(q.id())));
    chains.put(
        new CommandChainId("c-1"),
        new CommandChain(new CommandChainId("c-1"), "一", p.id(), Set.of(p.id())));
    UnitState state = new UnitState(units, chains);

    assertThat(state.commandChains().keySet())
        .containsExactly(new CommandChainId("c-2"), new CommandChainId("c-1"));
  }
}
