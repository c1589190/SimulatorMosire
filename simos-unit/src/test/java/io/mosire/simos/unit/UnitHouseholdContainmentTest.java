package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>S3a 正式验收：Unit / GovFormation 的家户容纳</b>（架构 {@code
 * docs/superpowers/specs/2026-10-09-s3a-unit-household-containment.md} §3、§7）。
 *
 * <p>覆盖四组判据：
 *
 * <ol>
 *   <li>第 17 组件 {@code households} 的构造语义（旧 16 参缺省空表、canonical 保序/拒重/冻结）；
 *   <li>{@link UnitState} 的两条跨单位守卫（同一家户两个 unit、unit id 与 household id 撞名）；
 *   <li>JSON/ChangeSet/快照往返保留 households（空表与非空表）；
 *   <li>{@link GovFormation#households()} 的旧 4 参缺省、canonical 语义、往返，以及 unit 侧拷贝点不丢家户。
 * </ol>
 */
class UnitHouseholdContainmentTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final HouseholdId HH_A = HouseholdId.parse("hh-a");
  private static final HouseholdId HH_B = HouseholdId.parse("hh-b");
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final UnitCodec CODEC = new UnitCodec();

  // ── 1. Unit 第 17 组件 ────────────────────────────────────────────────

  /** 旧 canonical 的 16 参构造器（第 17 组件没有来源）⇒ 缺省空表，且旧 16 个组件逐值在场。 */
  @Test
  void sixteenArgCompatibilityConstructorDefaultsToEmptyHouseholds() {
    Unit unit = oldShapeUnit(U1);

    assertThat(unit.households()).as("第 17 组件没有来源 ⇒ 空表（不是 null）").isEmpty();
    assertThat(unit.name()).isEqualTo("单位 u-1");
    assertThat(unit.equipment()).containsExactly(new CompositionEntry("步枪", 50));
    assertThat(unit.stateDescriptions()).as("第 16 组件仍逐值在场").containsEntry("回合", "map:Map1");
  }

  /** canonical 17 参：保序、拒重、拒 null 元素/整表、防御性拷贝 + 冻结。 */
  @Test
  void canonicalConstructorKeepsOrderRejectsDuplicatesAndFreezes() {
    List<HouseholdId> input = new ArrayList<>(List.of(HH_B, HH_A));
    Unit unit = unit(U1, input);

    assertThat(unit.households()).as("顺序是内容的一部分（HH_B 在 HH_A 前）").containsExactly(HH_B, HH_A);
    assertThatThrownBy(() -> unit.households().add(HouseholdId.parse("hh-c")))
        .as("返回的列表必须冻结")
        .isInstanceOf(UnsupportedOperationException.class);
    input.add(HouseholdId.parse("hh-c"));
    assertThat(unit.households()).as("构造期做防御性拷贝：改入参不得改单位").containsExactly(HH_B, HH_A);

    assertThatThrownBy(() -> unit(U1, List.of(HH_A, HH_A)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("households 不得有重复");
    assertThatThrownBy(() -> unit(U1, Arrays.asList(HH_A, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("households 的元素不得为 null");
    assertThatThrownBy(() -> unit(U1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("households 不得为 null");
  }

  /** 快照 JSON 往返：空表与非空表都要逐值（含顺序）回来。 */
  @Test
  void snapshotRoundTripPreservesEmptyAndNonEmptyHouseholds() {
    UnitState state =
        new UnitState(
            new LinkedHashMap<>(Map.of(U1, oldShapeUnit(U1), U2, unit(U2, List.of(HH_B, HH_A)))));

    String json = CODEC.encodeSnapshot(new UnitSnapshot(REF, T0, state));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);

    assertThat(json).as("线格式里 households 键必须真的出现（否则本用例恒真）").contains("\"households\"");
    assertThat(back.state().units().get(U1).households()).as("空表往返").isEmpty();
    assertThat(back.state().units().get(U2).households())
        .as("非空表往返保留顺序")
        .containsExactly(HH_B, HH_A);
    assertThat(back.state()).as("整份状态逐值往返").isEqualTo(state);
  }

  /** 变更集 JSON 往返：households 变化必须进差异，且 apply 后逐值重建 target。 */
  @Test
  void changeSetRoundTripPreservesHouseholds() {
    UnitState base = new UnitState(Map.of(U1, oldShapeUnit(U1)));
    UnitState target = new UnitState(Map.of(U1, unit(U1, List.of(HH_B, HH_A))));

    UnitChangeSet changeSet = UnitChangeSet.between(base, target);
    String json = CODEC.encodeChangeSet(changeSet);
    ChangeSet back = CODEC.decodeChangeSet(json);
    UnitState applied = UnitChangeSet.apply((UnitChangeSet) back, base);

    assertThat(changeSet.isEmpty()).as("只改 households 也必须让变更集非空").isFalse();
    assertThat(applied).as("变更集往返后逐值重建").isEqualTo(target);
    assertThat(applied.units().get(U1).households()).containsExactly(HH_B, HH_A);

    // 反向（非空 → 空表）也必须过线并逐值重建：只验一个方向会漏掉"清空家户"这一档。
    UnitChangeSet backward = UnitChangeSet.between(target, base);
    UnitState appliedBack =
        UnitChangeSet.apply(
            (UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(backward)), target);
    assertThat(appliedBack).as("非空 → 空表的变更集往返").isEqualTo(base);
    assertThat(appliedBack.units().get(U1).households()).as("清空家户逐值回到空表").isEmpty();
  }

  /**
   * 旧档缺 {@code households} 键 ⇒ **响亮读不出**，不是静默丢家户。★ S3a 明确不做旧档归一（架构 §1 非目标：旧世界不迁移）；
   * 这条断言钉住"最贵教训"的反面：静默补空表的实现会在这里变绿。
   */
  @Test
  void snapshotWithoutHouseholdsKeyIsRejectedNotSilentlyDropped() throws Exception {
    UnitState state = new UnitState(Map.of(U1, unit(U1, List.of(HH_A))));
    ObjectMapper treeMapper = new ObjectMapper();
    ObjectNode root =
        (ObjectNode) treeMapper.readTree(CODEC.encodeSnapshot(new UnitSnapshot(REF, T0, state)));
    ObjectNode unitNode = (ObjectNode) root.get("state").get("units").get("u-1");
    assertThat(unitNode.has("households")).as("前置：新形状确实写了该键（否则删键用例是恒真）").isTrue();
    unitNode.remove("households");

    assertThatThrownBy(() -> CODEC.decodeSnapshot(root.toString()))
        .as("旧档缺 households ⇒ 当场抛；静默补空表 = 家户被静默丢")
        .isInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("households 不得为 null（空列表合法）");
  }

  // ── 2. UnitState 跨单位守卫 ───────────────────────────────────────────

  @Test
  void sameHouseholdInTwoUnitsIsRejected() {
    Unit first = unit(U1, List.of(HH_A));
    Unit second = unit(U2, List.of(HH_A));

    assertThatThrownBy(() -> new UnitState(new LinkedHashMap<>(Map.of(U1, first, U2, second))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同一个家户不得同时属于多个 unit")
        .hasMessageContaining(HH_A.value());
  }

  @Test
  void unitIdClashingWithHouseholdIdIsRejected() {
    Unit holder = unit(U1, List.of(HouseholdId.parse(U2.value())));
    Unit clashing = unit(U2, List.of());

    assertThatThrownBy(() -> new UnitState(new LinkedHashMap<>(Map.of(U1, holder, U2, clashing))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unit id 不得与 household id 撞名")
        .hasMessageContaining(U2.value());
  }

  // ── 3. GovFormation.households ────────────────────────────────────────

  @Test
  void fourArgGovFormationDefaultsToEmptyHouseholds() {
    GovFormation gov =
        new GovFormation(Map.of(), OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL);

    assertThat(gov.households()).as("旧 4 参构造器没有下辖家户来源 ⇒ 空表").isEmpty();
  }

  @Test
  void canonicalGovFormationKeepsOrderRejectsDuplicatesAndFreezes() {
    List<HouseholdId> input = new ArrayList<>(List.of(HH_B, HH_A));
    GovFormation gov = gov(input);

    assertThat(gov.households()).containsExactly(HH_B, HH_A);
    assertThatThrownBy(() -> gov.households().add(HouseholdId.parse("hh-c")))
        .as("返回的列表必须冻结")
        .isInstanceOf(UnsupportedOperationException.class);
    input.add(HouseholdId.parse("hh-c"));
    assertThat(gov.households()).as("防御性拷贝").containsExactly(HH_B, HH_A);

    assertThatThrownBy(() -> gov(List.of(HH_A, HH_A)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("households 不得有重复");
    assertThatThrownBy(() -> gov(Arrays.asList(HH_A, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("households 的元素不得为 null");
  }

  /** GovFormation JSON 往返（经 Unit 编制线格式）：下辖家户逐值保序。 */
  @Test
  void govFormationSnapshotRoundTripPreservesHouseholds() {
    GovFormation gov = gov(List.of(HH_B, HH_A));
    UnitState state = new UnitState(Map.of(U1, unit(U1, List.of(), Optional.of(gov))));

    String json = CODEC.encodeSnapshot(new UnitSnapshot(REF, T0, state));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);
    GovFormation decoded = (GovFormation) back.state().units().get(U1).module().orElseThrow();

    assertThat(json).contains("\"@class\":\"gov\"");
    assertThat(decoded.households()).containsExactly(HH_B, HH_A);
    assertThat(decoded).as("整个 GovFormation 逐值往返").isEqualTo(gov);
  }

  /** GovFormation 的 households 变化必须进变更集，且过线后 apply 逐值重建 target。 */
  @Test
  void govFormationChangeSetRoundTripPreservesHouseholds() {
    UnitState base = stateWithGov(U1, gov(List.of(HH_A)));
    UnitState target = stateWithGov(U1, gov(List.of(HH_B, HH_A)));

    UnitChangeSet changeSet = UnitChangeSet.between(base, target);
    String json = CODEC.encodeChangeSet(changeSet);
    UnitState applied = UnitChangeSet.apply((UnitChangeSet) CODEC.decodeChangeSet(json), base);

    assertThat(changeSet.isEmpty()).isFalse();
    assertThat(applied).isEqualTo(target);
    assertThat(((GovFormation) applied.units().get(U1).module().orElseThrow()).households())
        .containsExactly(HH_B, HH_A);
  }

  /** GOV 的三个拷贝点（policy/superior/staff）必须原样带过 households（漏传 = 静默丢下辖家户）。 */
  @Test
  void govRebuildPointsPreserveHouseholds() {
    GovFormation gov = gov(List.of(HH_B, HH_A));
    UnitState base = stateWithGov(U1, gov);

    GovFormation afterPolicy =
        (GovFormation)
            UnitOperations.setGovPolicy(
                    base,
                    U1,
                    Optional.of(1L),
                    Optional.empty(),
                    Optional.of(2L),
                    Optional.empty(),
                    Optional.empty())
                .units()
                .get(U1)
                .module()
                .orElseThrow();
    assertThat(afterPolicy.households()).as("setGovPolicy 保留下辖家户").containsExactly(HH_B, HH_A);

    GovFormation afterSuperior =
        (GovFormation)
            UnitOperations.setGovSuperior(base, U1, Optional.empty())
                .units()
                .get(U1)
                .module()
                .orElseThrow();
    assertThat(afterSuperior.households()).as("setGovSuperior 保留下辖家户").containsExactly(HH_B, HH_A);

    GovFormation afterStaff =
        (GovFormation)
            UnitOperations.recruitStaff(base, U1, StaffRole.SCRIBE, 2L)
                .units()
                .get(U1)
                .module()
                .orElseThrow();
    assertThat(afterStaff.households()).as("recruitStaff 保留下辖家户").containsExactly(HH_B, HH_A);
  }

  // ── 4. Unit 侧拷贝点不丢 households ───────────────────────────────────

  /** 代表性 Unit 重建点逐个过一遍：只换各自目标字段，households 必须逐值原样。 */
  @Test
  void unitRebuildPointsPreserveHouseholds() {
    UnitState base = new UnitState(Map.of(U1, unit(U1, List.of(HH_B, HH_A))));

    assertHouseholdsKept(UnitOperations.rename(base, U1, "新名").units().get(U1));
    assertHouseholdsKept(
        UnitOperations.setComposition(base, U1, List.of(new CompositionEntry("马", 3)))
            .units()
            .get(U1));
    assertHouseholdsKept(UnitOperations.setStatus(base, U1, UnitStatus.RESTING).units().get(U1));
    assertHouseholdsKept(
        UnitOperations.setStateDescription(base, U1, "回合", Optional.of("map:Map1"))
            .units()
            .get(U1));
  }

  /** 整体替换语义：只换 households，其余组件一个不丢。 */
  @Test
  void setUnitHouseholdsReplacesWholesaleAndKeepsOtherComponents() {
    Unit before = unit(U1, List.of(HH_A));
    UnitState base = new UnitState(Map.of(U1, before));

    Unit after = UnitOperations.setUnitHouseholds(base, U1, List.of(HH_B, HH_A)).units().get(U1);

    assertThat(after.households()).containsExactly(HH_B, HH_A);
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.equipment()).isEqualTo(before.equipment());
    assertThat(after.stateDescriptions()).isEqualTo(before.stateDescriptions());
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────

  private static void assertHouseholdsKept(Unit unit) {
    assertThat(unit.households())
        .as("拷贝点漏传 before.households() ⇒ 家户被静默丢")
        .containsExactly(HH_B, HH_A);
  }

  /** canonical 17 参 Unit（第 17 组件 households 显式给，其余取固定夹具值）。 */
  private static Unit unit(UnitId id, List<HouseholdId> households) {
    return unit(id, households, Optional.empty());
  }

  private static Unit unit(UnitId id, List<HouseholdId> households, Optional<UnitModule> module) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        module,
        Map.of(),
        households);
  }

  /** 旧 16 参 canonical 形态（S3a/S3b 之前的规范形状；stateDescriptions 有显式来源，households 无来源）。 */
  private static Unit oldShapeUnit(UnitId id) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.empty(),
        Map.of("回合", "map:Map1"));
  }

  private static GovFormation gov(List<HouseholdId> households) {
    return new GovFormation(
        Map.of(StaffRole.SCRIBE, 1L),
        households,
        OfficePolicy.defaults(),
        Optional.empty(),
        GovLevel.CENTRAL);
  }

  private static UnitState stateWithGov(UnitId id, GovFormation gov) {
    return new UnitState(Map.of(id, unit(id, List.of(), Optional.of(gov))));
  }
}
