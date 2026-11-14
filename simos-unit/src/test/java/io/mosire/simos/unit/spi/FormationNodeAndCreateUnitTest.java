package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations.FormationNodePatch;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-14 编制编辑原语：{@code unit.CreateUnit} 省略子坐标写实父位置、父无位置具名拒； {@code unit.UpdateFormationNode} 改
 * parent/attached 即使位置相同也清旧路线、只同步该节点 attached 后代、保留所有其它 Unit 组件，且 ChangeSet 完整往返。
 *
 * <p>★ 判别力：直接读 {@link UnitState} 的逐组件值；把“父位置写实”“clear movement”“formationMembers 只含本节点附属后代”
 * 任一实现改坏，这里都会红。
 */
class FormationNodeAndCreateUnitTest {

  @Test
  void createUnitWithOmittedChildPositionWritesParentPositionAndRoundTrips() {
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()));
    CreateUnitHandler handler = new CreateUnitHandler();
    HandlerOutcome outcome =
        handler.handle(
            SpiFixture.state(SpiFixture.map(), base),
            "{\"id\":\"u-2\",\"name\":\"第二连\",\"parent\":\"u-1\","
                + "\"equipment\":[],\"speed\":1,\"mobilityPerMille\":1000}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitState next = apply(base, outcome);

    Unit created = next.units().get(new UnitId("u-2"));
    assertThat(created).isNotNull();
    assertThat(next.effectivePosition(created.id(), SpiFixture.T0)).contains(SpiFixture.H11);
    assertThat(created.parent().valueAt(SpiFixture.T0)).contains(SpiFixture.U1);
    assertThat(created.attached().valueAt(SpiFixture.T0)).isTrue();
    UnitChangeSet cs = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(UnitChangeSet.apply(cs, base)).as("ChangeSet 往返逐字段重建").isEqualTo(next);
  }

  @Test
  void createUnitRejectsOmittedChildPositionWhenParentHasNoPosition() {
    Unit root = unit("u-1", Optional.empty(), Optional.empty(), true, Optional.empty(), List.of());
    UnitState base = SpiFixture.unitState(root);
    HandlerOutcome outcome =
        new CreateUnitHandler()
            .handle(
                SpiFixture.state(SpiFixture.map(), base),
                "{\"id\":\"u-2\",\"name\":\"第二连\",\"parent\":\"u-1\","
                    + "\"equipment\":[],\"speed\":1,\"mobilityPerMille\":1000}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("没有可确定的位置");
  }

  @Test
  void updateClearsMovementOnParentChangeEvenWhenPositionStaysTheSame() {
    Unit u1 =
        unit(
            "u-1",
            Optional.empty(),
            Optional.of(SpiFixture.H11),
            true,
            Optional.empty(),
            List.of());
    Unit u2 =
        unit(
            "u-2",
            Optional.of("u-1"),
            Optional.of(SpiFixture.H11),
            true,
            Optional.of(SpiFixture.inFlight()),
            List.of());
    UnitState base = SpiFixture.unitState(u1, u2);

    HandlerOutcome outcome =
        new UpdateFormationNodeHandler()
            .handle(SpiFixture.state(SpiFixture.map(), base), "{\"id\":\"u-2\",\"parent\":null}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitState next = apply(base, outcome);
    Unit updated = next.units().get(new UnitId("u-2"));
    assertThat(updated.parent().valueAt(SpiFixture.T0)).isEmpty();
    assertThat(updated.position().valueAt(SpiFixture.T0)).contains(SpiFixture.H11);
    assertThat(updated.movement()).as("parent 变化必须清旧路线（即使位置相同）").isEmpty();
  }

  @Test
  void updateClearsMovementOnAttachedChangeEvenWhenPositionStaysTheSame() {
    Unit u1 =
        unit(
            "u-1",
            Optional.empty(),
            Optional.of(SpiFixture.H11),
            true,
            Optional.empty(),
            List.of());
    Unit u2 =
        unit(
            "u-2",
            Optional.of("u-1"),
            Optional.of(SpiFixture.H11),
            true,
            Optional.of(SpiFixture.inFlight()),
            List.of());
    UnitState base = SpiFixture.unitState(u1, u2);

    HandlerOutcome outcome =
        new UpdateFormationNodeHandler()
            .handle(
                SpiFixture.state(SpiFixture.map(), base), "{\"id\":\"u-2\",\"attached\":false}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitState next = apply(base, outcome);
    Unit updated = next.units().get(new UnitId("u-2"));
    assertThat(updated.attached().valueAt(SpiFixture.T0)).isFalse();
    assertThat(updated.position().valueAt(SpiFixture.T0)).contains(SpiFixture.H11);
    assertThat(updated.movement()).as("attached 变化必须清旧路线（即使位置相同）").isEmpty();
  }

  @Test
  void updateSyncsOnlyThisNodesAttachedDescendantsAndKeepsDetachedSubtreeAndSiblings() {
    // u-1 (root, H11) → u-2 (H11, 有在途路线) → u-3 (H11)；u-1 → u-4 (sibling, H11)；
    // u-3 → u-5 (detached, H13)。把 u-2 detach 到 H12：
    //   - u-3（attached=true）跟到 H12；u-5（detached）保持 H13；
    //   - u-4（u-2 的兄弟）与 u-1（祖先）保持 H11。
    Unit u1 =
        unit(
            "u-1",
            Optional.empty(),
            Optional.of(SpiFixture.H11),
            true,
            Optional.empty(),
            List.of());
    Unit u2 =
        unit(
            "u-2",
            Optional.of("u-1"),
            Optional.of(SpiFixture.H11),
            true,
            Optional.of(SpiFixture.inFlight()),
            List.of());
    Unit u3 =
        unit(
            "u-3",
            Optional.of("u-2"),
            Optional.of(SpiFixture.H11),
            true,
            Optional.empty(),
            List.of());
    Unit u4 =
        unit(
            "u-4",
            Optional.of("u-1"),
            Optional.of(SpiFixture.H11),
            true,
            Optional.empty(),
            List.of());
    Unit u5 =
        unit(
            "u-5",
            Optional.of("u-3"),
            Optional.of(SpiFixture.H13),
            false,
            Optional.empty(),
            List.of());
    UnitState base = SpiFixture.unitState(u1, u2, u3, u4, u5);

    HandlerOutcome outcome =
        new UpdateFormationNodeHandler()
            .handle(
                SpiFixture.state(SpiFixture.map(), base),
                "{\"id\":\"u-2\",\"position\":{\"q\":1,\"r\":2},\"attached\":false}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitState next = apply(base, outcome);
    assertThat(next.effectivePosition(new UnitId("u-2"), SpiFixture.T0)).contains(SpiFixture.H12);
    assertThat(next.effectivePosition(new UnitId("u-3"), SpiFixture.T0)).contains(SpiFixture.H12);
    assertThat(next.effectivePosition(new UnitId("u-5"), SpiFixture.T0))
        .as("detached 子树位置保持")
        .contains(SpiFixture.H13);
    assertThat(next.effectivePosition(new UnitId("u-1"), SpiFixture.T0))
        .as("祖先不跟着走")
        .contains(SpiFixture.H11);
    assertThat(next.effectivePosition(new UnitId("u-4"), SpiFixture.T0))
        .as("兄弟不跟着走")
        .contains(SpiFixture.H11);
    assertThat(next.units().get(new UnitId("u-3")).movement()).isEmpty();
    assertThat(UnitChangeSet.apply(UnitChangeSet.between(base, next), base)).isEqualTo(next);
  }

  @Test
  void updatePreservesEveryOtherUnitComponentAndChangeSetRoundTrips() {
    HouseholdId household = new HouseholdId("hh-unit:u-2");
    MilitaryPayPolicy policy =
        new MilitaryPayPolicy(
            30L, 0L, 0L, OptionalLong.empty(), Map.of(household, 9L), Map.of(), Map.of());
    ArmyFormation formation =
        new ArmyFormation(Optional.of(SpiFixture.U1), "BATTALION", Map.of(), policy);
    Unit u2 =
        new Unit(
            new UnitId("u-2"),
            "第二连",
            new SegmentedSeries<>(
                List.of(new Segment<>(SpiFixture.T0, Optional.of(SpiFixture.U1))), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(SpiFixture.T0, Optional.of(SpiFixture.H11))),
                List.of(),
                null),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, true)), List.of(), null),
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(
                        SpiFixture.T0, Optional.<io.mosire.simos.unit.RelativeOffset>empty())),
                List.of(),
                null),
            Optional.of(SpiFixture.U1),
            3,
            Optional.of(
                new Jurisdiction(
                    Map.of(new io.mosire.simos.map.region.RegionId("r-1"), 120L),
                    1L,
                    2L,
                    3L,
                    500L)),
            Optional.of(formation),
            Map.of("current", "map:Map1"),
            List.of(household));
    UnitState base = SpiFixture.unitState(SpiFixture.unitWithMovement(Optional.empty()), u2);

    HandlerOutcome outcome =
        new UpdateFormationNodeHandler()
            .handle(
                SpiFixture.state(SpiFixture.map(), base),
                "{\"id\":\"u-2\",\"name\":\"第二连·改名\","
                    + "\"equipment\":[{\"type\":\"步枪\",\"amount\":60}],"
                    + "\"speed\":4,\"mobilityPerMille\":700}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitState next = apply(base, outcome);
    Unit before = base.units().get(new UnitId("u-2"));
    Unit after = next.units().get(new UnitId("u-2"));
    assertThat(after.name()).isEqualTo("第二连·改名");
    assertThat(after.equipment()).containsExactly(new CompositionEntry("步枪", 60));
    assertThat(after.speed()).isEqualTo(4);
    assertThat(after.mobilityPerMille()).isEqualTo(700);
    assertThat(after.parent()).isEqualTo(before.parent());
    assertThat(after.position()).isEqualTo(before.position());
    assertThat(after.movement()).isEqualTo(before.movement());
    assertThat(after.status()).isEqualTo(before.status());
    assertThat(after.attached()).isEqualTo(before.attached());
    assertThat(after.offset()).isEqualTo(before.offset());
    assertThat(after.rejoinTarget()).isEqualTo(before.rejoinTarget());
    assertThat(after.visionRadius()).isEqualTo(before.visionRadius());
    assertThat(after.jurisdiction()).isEqualTo(before.jurisdiction());
    assertThat(after.module()).as("ArmyFormation/军俸政策完整保留").isEqualTo(before.module());
    assertThat(after.stateDescriptions()).isEqualTo(before.stateDescriptions());
    assertThat(after.households()).isEqualTo(before.households());
    assertThat(UnitChangeSet.apply(UnitChangeSet.between(base, next), base)).isEqualTo(next);
  }

  @Test
  void formationNodePatchDefensivelyCopiesEquipmentAndKeepsNullAsKeep() {
    List<CompositionEntry> mutable = new ArrayList<>();
    mutable.add(new CompositionEntry("步枪", 1));
    FormationNodePatch patch = new FormationNodePatch(null, null, null, null, null, null, mutable);
    mutable.add(new CompositionEntry("卡车", 2));
    mutable.set(0, new CompositionEntry("被外部改掉", 99));
    assertThat(patch.equipment())
        .as("构造后外部再改输入 List 不得穿透 patch（防御性复制）")
        .containsExactly(new CompositionEntry("步枪", 1));
    assertThatThrownBy(() -> patch.equipment().add(new CompositionEntry("外部写入", 1)))
        .as("输出集合必须不可写")
        .isInstanceOf(UnsupportedOperationException.class);

    FormationNodePatch keep = new FormationNodePatch(null, null, null, null, null, null, null);
    assertThat(keep.equipment()).as("null = 未填（保持），不得被归一成空表").isNull();
  }

  private static UnitState apply(UnitState base, HandlerOutcome outcome) {
    return UnitChangeSet.apply(
        (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static Unit unit(
      String id,
      Optional<String> parent,
      Optional<HexCoord> position,
      boolean attached,
      Optional<io.mosire.simos.unit.Movement> movement,
      List<CompositionEntry> equipment) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, position)), List.of(), null),
        equipment,
        2,
        500,
        movement,
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, attached)), List.of(), null),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(
                    SpiFixture.T0, Optional.<io.mosire.simos.unit.RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty());
  }
}
