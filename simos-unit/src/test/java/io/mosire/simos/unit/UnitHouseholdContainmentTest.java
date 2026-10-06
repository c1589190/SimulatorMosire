package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
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
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>S3a/S3b 正式验收：Unit / 编制模块的家户容纳</b>（架构 {@code
 * docs/superpowers/specs/2026-10-09-s3a-unit-household-containment.md} §3、§7）。
 *
 * <p>覆盖四组判据：
 *
 * <ol>
 *   <li>第 17 组件 {@code households} 的构造语义（旧 16 参缺省空表、canonical 保序/拒重/冻结）；
 *   <li>{@link UnitState} 的跨单位守卫（同一家户两个 unit、unit id 与 household id 撞名）；
 *   <li>S3b 的家户配置容纳：GOV 单位恰含自己的政府家户，{@code GovernmentFormation.householdPosts}、 {@code
 *       ArmyFormation.householdDuties}/{@code militaryPayPolicy} 三张表的键 ⊆ {@code Unit.households}，
 *       以及 JSON/ChangeSet 往返保留这些配置（编制里<b>没有</b>重复的 households 组件——2026-10-09 唯一列表裁定）；
 *   <li>Unit 侧拷贝点与 GOV 编制重建点不丢 households / 领导配置。
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

  // ── 3. 编制配置的家户容纳（S3b；编制里已无重复 households 列表） ──────

  /**
   * ★★ GOV 单位必须恰含一个家户、且必须是它自己稳定 id 派生的 {@code hh-gov-<unitId>}（2026-10-09 唯一列表裁定）。
   * 空表与"挂了别的政府家户"两条都要拒。
   */
  @Test
  void govUnitMustContainExactlyItsOwnGovernmentHousehold() {
    GovernmentFormation gov = gov(Map.of(), Optional.empty(), GovernmentLevel.CENTRAL);

    Unit withoutGovernmentHousehold = unit(U1, List.of(), Optional.of(gov));
    assertThatThrownBy(() -> new UnitState(Map.of(U1, withoutGovernmentHousehold)))
        .as("GOV 编制但不含政府家户 ⇒ 拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须恰含一个政府家户")
        .hasMessageContaining("hh-gov-u-1");

    Unit withForeignGovernmentHousehold =
        unit(U1, List.of(GovernmentHouseholds.of("other-gov")), Optional.of(gov));
    assertThatThrownBy(() -> new UnitState(Map.of(U1, withForeignGovernmentHousehold)))
        .as("政府家户只能挂在它自己的 GOV 单位上 ⇒ 拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须恰含一个政府家户")
        .hasMessageContaining("hh-gov-u-1");
  }

  /** ★ S3b：{@code governmentPostsOfHousehold} 的键必须 ⊆ 本单位的 {@code households}（否则配置与人口脱钩）。 */
  @Test
  void governmentPostsKeysMustBelongToUnitHouseholds() {
    HouseholdId outsider = HouseholdId.parse("hh-outsider");
    GovernmentFormation gov =
        gov(
            Map.of(
                outsider,
                new GovernmentPostOfHousehold(
                    outsider, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true)),
            Optional.empty(),
            GovernmentLevel.CENTRAL);
    Unit bad = unit(U1, List.of(govHouseholdOf(U1)), Optional.of(gov));

    assertThatThrownBy(() -> new UnitState(Map.of(U1, bad)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("householdPosts")
        .hasMessageContaining("households")
        .hasMessageContaining(outsider.value());
  }

  /** 领导配置与 households 一起过 JSON 快照 / 变更集往返，且 {@code householdPosts} 用旧线格式键落盘。 */
  @Test
  void governmentPostsSurviveSnapshotAndChangeSetRoundTrip() {
    HouseholdId member = HouseholdId.parse("hh-member");
    GovernmentPostOfHousehold post =
        new GovernmentPostOfHousehold(member, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true);
    GovernmentFormation gov =
        gov(orderedPosts(Map.entry(member, post)), Optional.empty(), GovernmentLevel.CENTRAL);
    UnitState state =
        new UnitState(Map.of(U1, unit(U1, List.of(govHouseholdOf(U1), member), Optional.of(gov))));

    String json = CODEC.encodeSnapshot(new UnitSnapshot(REF, T0, state));
    assertThat(json).as("领导配置的线格式键仍钉在旧名 householdPosts（零迁移）").contains("\"householdPosts\"");
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);
    assertThat(back.state()).as("整份状态逐值往返").isEqualTo(state);
    GovernmentFormation decoded =
        (GovernmentFormation) back.state().units().get(U1).module().orElseThrow();
    assertThat(decoded.governmentPostsOfHousehold()).containsExactly(Map.entry(member, post));
    assertThat(back.state().units().get(U1).households())
        .as("GOV 家户与领导配置家户都往返保留")
        .containsExactly(govHouseholdOf(U1), member);

    // 变更集：追加第二条领导配置 + 对应家户，过线后逐值重建。
    HouseholdId member2 = HouseholdId.parse("hh-member-2");
    GovernmentPostOfHousehold post2 =
        new GovernmentPostOfHousehold(member2, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false);
    GovernmentFormation gov2 =
        gov(
            orderedPosts(Map.entry(member, post), Map.entry(member2, post2)),
            Optional.empty(),
            GovernmentLevel.CENTRAL);
    UnitState target =
        new UnitState(
            Map.of(U1, unit(U1, List.of(govHouseholdOf(U1), member, member2), Optional.of(gov2))));
    UnitChangeSet changeSet = UnitChangeSet.between(state, target);
    assertThat(changeSet.isEmpty()).isFalse();
    UnitState applied =
        UnitChangeSet.apply(
            (UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet)), state);
    assertThat(applied).as("领导配置变更也必须过线并逐值重建（铁律 5）").isEqualTo(target);
    assertThat(
            ((GovernmentFormation) applied.units().get(U1).module().orElseThrow())
                .governmentPostsOfHousehold())
        .containsExactly(Map.entry(member, post), Map.entry(member2, post2));
  }

  /** GOV 的两个重建点（policy / superior）必须原样带过领导配置与 households（漏传 = 静默丢配置）。 */
  @Test
  void govRebuildPointsPreserveGovernmentPostsAndHouseholds() {
    HouseholdId member = HouseholdId.parse("hh-member");
    GovernmentPostOfHousehold post =
        new GovernmentPostOfHousehold(member, StaffRole.SCRIBE, GovernmentLevel.PROVINCE, false);
    GovernmentFormation gov =
        gov(orderedPosts(Map.entry(member, post)), Optional.empty(), GovernmentLevel.PROVINCE);
    UnitState base =
        new UnitState(Map.of(U1, unit(U1, List.of(govHouseholdOf(U1), member), Optional.of(gov))));

    UnitState afterPolicyState =
        UnitOperations.setGovPolicy(
            base,
            U1,
            Optional.of(1L),
            Optional.empty(),
            Optional.of(2L),
            Optional.empty(),
            Optional.empty());
    GovernmentFormation afterPolicy =
        (GovernmentFormation) afterPolicyState.units().get(U1).module().orElseThrow();
    assertThat(afterPolicy.governmentPostsOfHousehold())
        .as("setGovPolicy 保留下辖领导配置")
        .containsExactly(Map.entry(member, post));
    assertThat(afterPolicyState.units().get(U1).households())
        .as("setGovPolicy 保留 households")
        .containsExactly(govHouseholdOf(U1), member);

    UnitState afterSuperiorState = UnitOperations.setGovSuperior(base, U1, Optional.empty());
    GovernmentFormation afterSuperior =
        (GovernmentFormation) afterSuperiorState.units().get(U1).module().orElseThrow();
    assertThat(afterSuperior.governmentPostsOfHousehold())
        .as("setGovSuperior 保留下辖领导配置")
        .containsExactly(Map.entry(member, post));
    assertThat(afterSuperiorState.units().get(U1).households())
        .as("setGovSuperior 保留 households")
        .containsExactly(govHouseholdOf(U1), member);
  }

  /** ★ S3b：领导配置非空 ⇒ {@code staff} 只是家户投影，直改 staff 必须具名拒（不制造第二本权威）。 */
  @Test
  void staffEditsAreRejectedWhenPostsMakeStaffAProjection() {
    HouseholdId member = HouseholdId.parse("hh-member");
    GovernmentPostOfHousehold post =
        new GovernmentPostOfHousehold(member, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true);
    GovernmentFormation gov =
        gov(orderedPosts(Map.entry(member, post)), Optional.empty(), GovernmentLevel.CENTRAL);
    UnitState base =
        new UnitState(Map.of(U1, unit(U1, List.of(govHouseholdOf(U1), member), Optional.of(gov))));

    assertThatThrownBy(() -> UnitOperations.recruitStaff(base, U1, StaffRole.YAMEN, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("householdPosts")
        .hasMessageContaining("投影");
    assertThatThrownBy(() -> UnitOperations.dismissStaff(base, U1, StaffRole.SCRIBE, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("householdPosts")
        .hasMessageContaining("投影");
  }

  /** ★ S3b：Army 的军官配置键必须 ⊆ 本单位 households。 */
  @Test
  void armyDutyKeysMustBelongToUnitHouseholds() {
    HouseholdId outsider = HouseholdId.parse("hh-outsider");
    MilitaryDutyOfHousehold duty =
        new MilitaryDutyOfHousehold(outsider, MilitaryDutyKind.OFFICER, "百人将", Optional.empty());
    ArmyFormation army = new ArmyFormation(Optional.empty(), "garrison", Map.of(outsider, duty));
    Unit bad = unit(U1, List.of(), Optional.of(army));

    assertThatThrownBy(() -> new UnitState(Map.of(U1, bad)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("householdDuties")
        .hasMessageContaining(outsider.value());
  }

  /** ★ P4b：{@code militaryPayPolicy} 三张表的键必须 ⊆ 本单位 households（"给不存在于本单位的人发钱"具名拒）。 */
  @Test
  void armyPayPolicyKeysMustBelongToUnitHouseholds() {
    HouseholdId member = HouseholdId.parse("hh-member");
    HouseholdId outsider = HouseholdId.parse("hh-outsider");
    MilitaryPayPolicy policy =
        new MilitaryPayPolicy(
            30L, 0L, 0L, OptionalLong.empty(), Map.of(outsider, 5L), Map.of(), Map.of());
    ArmyFormation army = new ArmyFormation(Optional.empty(), "garrison", Map.of(), policy);
    Unit bad = unit(U1, List.of(member), Optional.of(army));

    assertThatThrownBy(() -> new UnitState(Map.of(U1, bad)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("grainPerHouseholdPerCycle")
        .hasMessageContaining(outsider.value());
  }

  /** Army 的军官配置 + 军俸政策一起过 JSON 快照往返（配置不是第二本人头账，但必须逐值不丢）。 */
  @Test
  void armyHouseholdConfigsRoundTripThroughSnapshot() {
    HouseholdId member = HouseholdId.parse("hh-member");
    MilitaryDutyOfHousehold duty =
        new MilitaryDutyOfHousehold(member, MilitaryDutyKind.OFFICER, "百人将", Optional.empty());
    MilitaryPayPolicy policy =
        new MilitaryPayPolicy(
            30L,
            0L,
            0L,
            OptionalLong.empty(),
            Map.of(member, 5L),
            Map.of(member, 2L),
            Map.of(member, 7L));
    ArmyFormation army =
        new ArmyFormation(Optional.empty(), "garrison", Map.of(member, duty), policy);
    UnitState state = new UnitState(Map.of(U1, unit(U1, List.of(member), Optional.of(army))));

    String json = CODEC.encodeSnapshot(new UnitSnapshot(REF, T0, state));
    assertThat(json).contains("\"householdDuties\"").contains("\"militaryPayPolicy\"");
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);
    assertThat(back.state()).as("整份状态逐值往返").isEqualTo(state);
    ArmyFormation decoded = (ArmyFormation) back.state().units().get(U1).module().orElseThrow();
    assertThat(decoded.militaryDutiesOfHousehold()).containsExactly(Map.entry(member, duty));
    assertThat(decoded.militaryPayPolicy()).isEqualTo(policy);
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

  /** 该 GOV 单位稳定 id 派生的政府家户 {@code hh-gov-<unitId>}（唯一列表裁定）。 */
  private static HouseholdId govHouseholdOf(UnitId id) {
    return GovernmentHouseholds.of(id.value());
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

  /** GOV 编制：{@code staff} 空、policy 取缺省；领导配置与层级逐值给。 */
  private static GovernmentFormation gov(
      Map<HouseholdId, GovernmentPostOfHousehold> posts,
      Optional<UnitId> superior,
      GovernmentLevel level) {
    return new GovernmentFormation(Map.of(), posts, OfficePolicy.defaults(), superior, level);
  }

  @SafeVarargs
  private static Map<HouseholdId, GovernmentPostOfHousehold> orderedPosts(
      Map.Entry<HouseholdId, GovernmentPostOfHousehold>... entries) {
    Map<HouseholdId, GovernmentPostOfHousehold> posts = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry : entries) {
      posts.put(entry.getKey(), entry.getValue());
    }
    return posts;
  }
}
