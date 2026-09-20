package io.mosire.simos.unit.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
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
 * R-3-unit：unit 模块的 JSON 往返守卫（M4 Task 3 Step 4，与 {@code MapCodecTest}/{@code SocialCodecTest} 同制）。
 *
 * <p>★ 覆盖：{@code SegmentedSeries<Optional<…>>}（嵌套泛型里的 {@code Optional}——probe 的重灾区）、{@code
 * Optional<Movement>} 两侧向（在途/驻止）、自定义键 {@code UnitId}、密封接口 {@code FieldDelta} 四变体。往返只断 {@code
 * equals}（裁定 12）。
 */
class UnitCodecTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final HexCoord H12 = new HexCoord(1, 2);

  private static final UnitCodec CODEC = new UnitCodec();

  @Test
  void namespaceIsUnit() {
    assertThat(CODEC.namespace()).isEqualTo("unit");
  }

  /** 非平凡快照往返：驻止单位（movement 为 empty）+ 带历注。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    UnitSnapshot snapshot =
        snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10, "弘光元年"));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 在途单位：{@code Optional<Movement>} 的 present 侧 + {@code Route} 的 {@code List<HexCoord>} 组件。 */
  @Test
  void snapshotRoundTripsWithUnitInTransit() {
    UnitSnapshot snapshot = snapshotOf(stateOf(oneUnit("u-1", H11, true)), SimosTimestamp.of(11));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 变更集往返：四条变体各造一条（Unchanged / Upsert / Remove / Patch），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    UnitState one =
        UnitState.empty().withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11, false)));
    UnitState other =
        UnitState.empty().withUnits(Map.of(new UnitId("u-2"), oneUnit("u-2", H12, false)));

    UnitChangeSet unchanged = UnitChangeSet.between(one, one);
    UnitChangeSet upsert = UnitChangeSet.between(UnitState.empty(), one);
    UnitChangeSet remove = UnitChangeSet.between(one, UnitState.empty());
    UnitChangeSet patch = UnitChangeSet.between(one, other);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.units()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.units()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.units()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.units()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /** 值类型的绑定不能在读入侧丢成 Map：Unit 得还是 Unit。 */
  @Test
  void deltaValuesSurviveAsUnitNotAsMaps() {
    UnitChangeSet back =
        (UnitChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(
                    UnitChangeSet.between(
                        UnitState.empty(),
                        UnitState.empty()
                            .withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11, false))))));
    FieldDelta.Upsert<Unit> upsert = (FieldDelta.Upsert<Unit>) back.units();
    assertThat(upsert.entries().get("u-1")).isInstanceOf(Unit.class);
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    UnitSnapshot base = snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10));
    UnitState target =
        UnitState.empty().withUnits(Map.of(new UnitId("u-2"), oneUnit("u-2", H12, false)));
    UnitChangeSet changeSet = UnitChangeSet.between(base.state(), target);
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    UnitSnapshot applied = (UnitSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.state()).isEqualTo(UnitChangeSet.apply(changeSet, base.state()));
    assertThat(base.state().units()).containsOnlyKeys(new UnitId("u-1"));
  }

  /**
   * ★ 下转型守卫的自证：喂一个**别的模块的切片**，{@code apply} 与 {@code encodeSnapshot} 都必须当场 {@link
   * IllegalStateException}。
   *
   * <p>这条用例**只在改成 {@code instanceof} 之后**才有判别力——裸 cast 同样会抛（{@code ClassCastException}），
   * 所以断言钉的是**异常类型 + 消息**，不是"抛了就算"。
   */
  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    UnitSnapshot base = snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10));
    UnitChangeSet changeSet = UnitChangeSet.between(base.state(), base.state());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 UnitSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 UnitSnapshot");
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }

  /** ★ T1：含 2 条链 + 同一 unit 多属的快照往返（{@code CommandChainId} 作 Map 键的靶子）。 */
  @Test
  void snapshotRoundTripsCommandChainsWithASharedMember() {
    UnitSnapshot snapshot =
        snapshotOf(
            stateWithChains(oneUnit("u-1", H11, false), oneUnit("u-2", H12, false)),
            SimosTimestamp.of(12));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().commandChains())
        .containsOnlyKeys(new CommandChainId("c-1"), new CommandChainId("c-2"));
    assertThat(back.state().commandChains().get(new CommandChainId("c-1")).members())
        .containsExactlyInAnyOrder(new UnitId("u-1"), new UnitId("u-2"));
  }

  /** 变更集里的 {@code commandChains} 组件也逐值往返。 */
  @Test
  void changeSetRoundTripsCommandChainUpserts() {
    UnitState target = stateWithChains(oneUnit("u-1", H11, false), oneUnit("u-2", H12, false));
    UnitChangeSet cs = UnitChangeSet.between(UnitState.empty(), target);
    assertThat(cs.commandChains()).isInstanceOf(FieldDelta.Upsert.class);

    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(cs))).isEqualTo(cs);
  }

  // ── 夹具 ──

  private static UnitState stateOf(Unit unit) {
    return UnitState.empty().withUnits(Map.of(unit.id(), unit));
  }

  private static Unit oneUnit(String id, HexCoord at, boolean inTransit) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
        500,
        Map.of("旗帜", 3),
        2,
        1000,
        inTransit
            ? Optional.of(
                new Movement(new Route(List.of(H11, H12), List.of(H11, H12)), T0, 2, 1000))
            : Optional.empty());
  }

  private static UnitSnapshot snapshotOf(UnitState state, SimosTimestamp timestamp) {
    return new UnitSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, state);
  }

  /** 两个单位 + 2 条链、两个单位**同属两条链**（多属）。 */
  private static UnitState stateWithChains(Unit one, Unit two) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(one.id(), one);
    units.put(two.id(), two);
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(
        new CommandChainId("c-1"),
        new CommandChain(new CommandChainId("c-1"), "第一链", one.id(), Set.of(one.id(), two.id())));
    chains.put(
        new CommandChainId("c-2"),
        new CommandChain(new CommandChainId("c-2"), "第二链", two.id(), Set.of(one.id(), two.id())));
    return new UnitState(units, chains);
  }
}
