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
        100,
        Map.of(),
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
        100,
        Map.of(),
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
            100,
            Map.of(),
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
            100,
            Map.of(),
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

  @Test
  void effectivePositionPrefersOwnThenWalksToParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.empty(), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22);
    assertThat(state.effectivePosition(new UnitId("c"), T0)).as("自身无位置 ⇒ 向父取").contains(H22);
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

  /** 行 2：attached=true、自身无位置、offset 非空 ⇒ 父的有效位置 ⊕ offset（**不等于**父位）。 */
  @Test
  void attachedChildWithoutPositionAddsOffsetToParentPosition() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child =
        formedUnit(
            "c", Optional.of("p"), Optional.empty(), true, Optional.of(new RelativeOffset(2, -1)));
    UnitState state = stateOf(parent, child);

    HexCoord expected = new RelativeOffset(2, -1).appliedTo(H22);
    assertThat(state.effectivePosition(new UnitId("c"), T0)).as("父位 ⊕ offset").contains(expected);
    assertThat(state.effectivePosition(new UnitId("c"), T0))
        .as("不是父位本身")
        .isNotEqualTo(Optional.of(H22));
  }

  /** 行 5：attached=false、自身无位置 ⇒ 空（**不回退**父位，spec §一.4 的取代）。 */
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

  /** 行 3 的回归条：attached=true、offset 为空 ⇒ 父位（与 M3 今天逐字相同）。 */
  @Test
  void attachedChildWithEmptyOffsetIsAByteForByteRegression() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = formedUnit("c", Optional.of("p"), Optional.empty(), true, Optional.empty());
    UnitState state = stateOf(parent, child);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(H22);
  }

  /** offset 沿父链复合：孙子 = 祖父位 ⊕ 父偏移 ⊕ 自己偏移。 */
  @Test
  void offsetsComposeUpTheParentChain() {
    Unit grand = unit("g", Optional.empty(), Optional.of(H22), T0);
    Unit parent =
        formedUnit(
            "p", Optional.of("g"), Optional.empty(), true, Optional.of(new RelativeOffset(1, 0)));
    Unit child =
        formedUnit(
            "c", Optional.of("p"), Optional.empty(), true, Optional.of(new RelativeOffset(0, 2)));
    UnitState state = stateOf(grand, parent, child);

    HexCoord expected = new RelativeOffset(0, 2).appliedTo(new RelativeOffset(1, 0).appliedTo(H22));
    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(expected);
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
