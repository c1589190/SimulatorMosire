package io.mosire.simos.unit.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 Task 4 的 {@code SocialRoundTripTest}，同源于 M2 的 {@code
 * RoundTripComponentsTest}）：反射枚举 {@link UnitState} 的 record 组件，逐组件造差异，三条断言 —— 新增状态组件若忘了进变更集，本测试自动红。
 */
class UnitRoundTripTest {

  /** ★ 唯一的豁免集合：UnitState 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static Unit unit(String id, int member) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        member,
        Map.of(),
        2,
        1000,
        Optional.empty());
  }

  @Test
  void everyUnitStateComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : UnitState.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      UnitState base = UnitState.empty();
      UnitState target = mutate(base, name);
      UnitChangeSet cs = UnitChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(UnitChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("UnitState 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  /**
   * ★ **视野半径（权限阶段 Task 1 / spec §4.1）**：只改它也必须进变更集，且往返逐字段重建。
   *
   * <p>★ **这条判据的强度必须说清楚**（否则会被读成"字段丢不了"）：{@code UnitChangeSet} 是 {@code FieldDelta<Unit>}
   * 的**实体粒度**形态——差异里存的**就是 {@code Unit} 对象本身**，所以这里的往返**证不了** {@code Unit}
   * 自己的字段没丢（它把同一个对象递回来了）。它在这里证的是**另一半**：字段进了 record ⇒ 实体比较就分得出来 ⇒ 变更集不会 把它当"没变化"。真正的逐字段重建由 {@code
   * UnitCodecTest.snapshotRoundTripsANonDefaultVisionRadius}（过线）把守。
   */
  @Test
  void aChangedVisionRadiusAloneIsAChange() {
    UnitState base = new UnitState(Map.of(new UnitId("u-1"), unit("u-1", 100)));
    UnitState target =
        new UnitState(Map.of(new UnitId("u-1"), withVisionRadius(unit("u-1", 100), 3)));
    UnitChangeSet cs = UnitChangeSet.between(base, target);

    assertThat(cs.isEmpty()).as("只改视野半径也必须让变更集非空").isFalse();
    assertThat(cs.units().changed()).as("units 组件必须被报成非 Unchanged").isTrue();
    assertThat(UnitChangeSet.apply(cs, base)).as("往返").isEqualTo(target);
    assertThat(UnitChangeSet.apply(cs, base).units().get(new UnitId("u-1")).visionRadius())
        .as("重建出来的实体带着 3（不是缺省 1）")
        .isEqualTo(3);
  }

  /** 兼容构造器（9 参）造的夹具单位改视野半径：**只换那一个分量**，其余逐字段带过。 */
  private static Unit withVisionRadius(Unit unit, int visionRadius) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.member(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        unit.movement(),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        visionRadius);
  }

  @Test
  void changeSetHasExactlyTwoComponents() {
    assertThat(UnitChangeSet.class.getRecordComponents()).hasSize(2);
    assertThat(componentNames(UnitChangeSet.class))
        .as("变更集的每个组件都必须在 UnitState 里有同名的 record 组件")
        .isSubsetOf(componentNames(UnitState.class));
    assertThat(componentNames(UnitState.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(UnitChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsUnit() {
    UnitSnapshot snapshot =
        new UnitSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            UnitState.empty());
    assertThat(snapshot.namespace()).isEqualTo("unit");
  }

  private static UnitState mutate(UnitState base, String name) {
    return switch (name) {
      case "units" -> base.withUnits(oneUnit());
      case "commandChains" -> stateWithOneChain();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(UnitChangeSet cs, String name) {
    return switch (name) {
      case "units" -> cs.units().changed();
      case "commandChains" -> cs.commandChains().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  private static Map<UnitId, Unit> oneUnit() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-1"), unit("u-1", 100));
    return units;
  }

  /** units 与一条链都在的状态（commandChains 组件的差异夹具：链是唯一差异，units 侧两边一致）。 */
  private static UnitState stateWithOneChain() {
    UnitId commander = new UnitId("u-1");
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(
        new CommandChainId("c-1"),
        new CommandChain(new CommandChainId("c-1"), "链 c-1", commander, Set.of(commander)));
    return new UnitState(oneUnit(), chains);
  }
}
