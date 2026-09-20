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
      case "commandChains" -> false;
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
