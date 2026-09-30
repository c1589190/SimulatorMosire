package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ **阶段 9 编制模块（{@code UnitModule}）的构造期不变量与"一单位一标签"**（B2a / 计划 §2.1）。
 *
 * <p>判据分四块：
 *
 * <ol>
 *   <li>{@link GovFormation}/{@link ArmyFormation}/{@link OfficePolicy} 的 null / 负值 / 空白 role
 *       一律当场抛，不静默钳制；
 *   <li>{@code staff}/{@code staffCap} **保序不可变**——入参 map 事后改动不影响结果，直接 put 抛（{@code Map.copyOf}
 *       会打乱序，这里必须是 LinkedHashMap + unmodifiableMap）；
 *   <li>{@link OfficePolicy#defaults()} 的常量**来源**逐值等于 {@link EconomyVocabulary}（粮/布），其余为 0/空；
 *   <li>**一单位一标签**：sealed 类型层（许可子类恰两个、{@code Unit} 只有一个 {@code module} 分量）与命令层（已挂 Army 再挂 Gov
 *       必拒、反之亦然）双闸。
 * </ol>
 */
class UnitModuleTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");

  // ── GovFormation：构造期不变量 ─────────────────────────────────

  @Test
  void govFormationRejectsNullStaff() {
    assertThatThrownBy(
            () ->
                new GovFormation(null, OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 不得为 null（无人员用 Map.of()）");
  }

  @Test
  void govFormationRejectsNullKeysAndValuesInStaff() {
    Map<StaffRole, Long> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 1L);
    assertThatThrownBy(
            () ->
                new GovFormation(
                    nullKey, OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的键与值都不得为 null");

    Map<StaffRole, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(StaffRole.SCRIBE, null);
    assertThatThrownBy(
            () ->
                new GovFormation(
                    nullValue, OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的键与值都不得为 null");
  }

  @Test
  void govFormationRejectsNegativeStaffWithoutClamping() {
    assertThatThrownBy(
            () ->
                new GovFormation(
                    Map.of(StaffRole.YAMEN, -1L),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的值必须 ≥ 0: YAMEN=-1");
  }

  @Test
  void govFormationRejectsNullPolicySuperiorAndLevel() {
    assertThatThrownBy(() -> new GovFormation(Map.of(), null, Optional.empty(), GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("policy 不得为 null");
    assertThatThrownBy(
            () -> new GovFormation(Map.of(), OfficePolicy.defaults(), null, GovLevel.CENTRAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("superiorGov 不得为 null（无上级用 Optional.empty()）");
    assertThatThrownBy(
            () -> new GovFormation(Map.of(), OfficePolicy.defaults(), Optional.empty(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("level 不得为 null");
  }

  /** {@code staff} 保序 + 不可变：入参事后改、直接 put 都要挡；键序必须与插入序一致（不是哈希序）。 */
  @Test
  void govFormationStaffIsOrderedAndImmutable() {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    staff.put(StaffRole.YAMEN, 2L);
    staff.put(StaffRole.POST, 1L);
    staff.put(StaffRole.SCRIBE, 5L);

    GovFormation formation =
        new GovFormation(staff, OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL);

    staff.put(StaffRole.YAMEN, 99L);
    staff.put(StaffRole.SCRIBE, 88L);
    staff.remove(StaffRole.POST);

    assertThat(new ArrayList<>(formation.staff().keySet()))
        .as("键序 = 插入序（YAMEN→POST→SCRIBE），入参事后改动不影响")
        .containsExactly(StaffRole.YAMEN, StaffRole.POST, StaffRole.SCRIBE);
    assertThat(formation.staff())
        .containsExactly(
            Map.entry(StaffRole.YAMEN, 2L),
            Map.entry(StaffRole.POST, 1L),
            Map.entry(StaffRole.SCRIBE, 5L));
    assertThatThrownBy(() -> formation.staff().put(StaffRole.YAMEN, 100L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── ArmyFormation：构造期不变量 ─────────────────────────────────

  @Test
  void armyFormationAcceptsEmptyAndPresentMasterGov() {
    assertThat(new ArmyFormation(Optional.empty(), "garrison").masterGov()).isEmpty();
    assertThat(new ArmyFormation(Optional.of(U1), "garrison").masterGov()).contains(U1);
  }

  @Test
  void armyFormationRejectsNullMasterGovOptional() {
    assertThatThrownBy(() -> new ArmyFormation(null, "garrison"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("masterGov 不得为 null（未认领用 Optional.empty()）");
  }

  @Test
  void armyFormationRejectsNullAndBlankRole() {
    assertThatThrownBy(() -> new ArmyFormation(Optional.empty(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("role 不得为空白");
    assertThatThrownBy(() -> new ArmyFormation(Optional.empty(), ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("role 不得为空白");
    assertThatThrownBy(() -> new ArmyFormation(Optional.empty(), "  \t "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("role 不得为空白");
  }

  // ── OfficePolicy：构造期不变量 / 保序不可变 / defaults 常量来源 ──────

  @Test
  void officePolicyRejectsNegativeAmountsWithoutClamping() {
    assertThatThrownBy(() -> new OfficePolicy(-1L, 0L, 0L, 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("grainPerStaffPerTick 必须 ≥ 0: -1");
    assertThatThrownBy(() -> new OfficePolicy(0L, -2L, 0L, 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("clothPerStaffPerCycle 必须 ≥ 0: -2");
    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, -3L, 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("moneyPerStaffPerTick 必须 ≥ 0: -3");
    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, 0L, -4L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("retirementPerStaff 必须 ≥ 0: -4");
  }

  @Test
  void officePolicyRejectsNullAndBadStaffCap() {
    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, 0L, 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staffCap 不得为 null（不设上限用 Map.of()）");

    Map<StaffRole, Long> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 1L);
    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, 0L, 0L, nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staffCap 的键与值都不得为 null");

    Map<StaffRole, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(StaffRole.POST, null);
    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, 0L, 0L, nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staffCap 的键与值都不得为 null");

    assertThatThrownBy(() -> new OfficePolicy(0L, 0L, 0L, 0L, Map.of(StaffRole.POST, -2L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staffCap 的值必须 ≥ 0: POST=-2");
  }

  /** {@code staffCap} 保序 + 不可变（与 {@code staff} 同款，坏实现会退化成哈希序）。 */
  @Test
  void officePolicyStaffCapIsOrderedAndImmutable() {
    Map<StaffRole, Long> cap = new LinkedHashMap<>();
    cap.put(StaffRole.POST, 9L);
    cap.put(StaffRole.SCRIBE, 6L);
    cap.put(StaffRole.YAMEN, 3L);

    OfficePolicy policy = new OfficePolicy(1L, 2L, 3L, 4L, cap);
    cap.put(StaffRole.YAMEN, 99L);
    cap.remove(StaffRole.POST);

    assertThat(new ArrayList<>(policy.staffCap().keySet()))
        .containsExactly(StaffRole.POST, StaffRole.SCRIBE, StaffRole.YAMEN);
    assertThat(policy.staffCap())
        .containsExactly(
            Map.entry(StaffRole.POST, 9L),
            Map.entry(StaffRole.SCRIBE, 6L),
            Map.entry(StaffRole.YAMEN, 3L));
    assertThatThrownBy(() -> policy.staffCap().put(StaffRole.SCRIBE, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void defaultsComeFromEconomyVocabularyAndEverythingElseIsZeroOrEmpty() {
    OfficePolicy defaults = OfficePolicy.defaults();

    assertThat(defaults.grainPerStaffPerTick())
        .as("粮的单一拼写点是 EconomyVocabulary.RATION_MILLI_PER_PERSON")
        .isEqualTo(EconomyVocabulary.RATION_MILLI_PER_PERSON);
    assertThat(defaults.clothPerStaffPerCycle())
        .as("布的单一拼写点是 EconomyVocabulary.CLOTH_MILLI_PER_PERSON")
        .isEqualTo(EconomyVocabulary.CLOTH_MILLI_PER_PERSON);
    assertThat(defaults.moneyPerStaffPerTick()).as("俸禄默认 0").isZero();
    assertThat(defaults.retirementPerStaff()).as("退休待遇默认 0（由决策人政策定）").isZero();
    assertThat(defaults.staffCap()).as("编制上限默认空表 = 不设限").isEmpty();
  }

  // ── 词表 ───────────────────────────────────────────────────────

  @Test
  void staffRoleAndGovLevelVocabulariesAreExactlyTheRuledOnes() {
    assertThat(StaffRole.values())
        .as("GOV 编制角色恰三个（古称；Army.role 是自由短名、词表后置）")
        .containsExactly(StaffRole.SCRIBE, StaffRole.YAMEN, StaffRole.POST);
    assertThat(GovLevel.values())
        .as("层级恰中央/省两档")
        .containsExactly(GovLevel.CENTRAL, GovLevel.PROVINCE);
  }

  // ── 一单位一标签：sealed 类型层 ────────────────────────────────

  @Test
  void unitModuleIsSealedWithExactlyGovAndArmyPermitted() {
    assertThat(UnitModule.class.isSealed()).as("互斥由 sealed 类型层保证").isTrue();
    assertThat(UnitModule.class.getPermittedSubclasses())
        .as("许可子类恰 GovFormation / ArmyFormation，没有第三条路")
        .containsExactlyInAnyOrder(GovFormation.class, ArmyFormation.class);
  }

  @Test
  void unitHasExactlyOneModuleComponentOfOptionalType() {
    List<String> names =
        Arrays.stream(Unit.class.getRecordComponents()).map(RecordComponent::getName).toList();
    assertThat(names.stream().filter("module"::equals).count())
        .as("module 只出现一次——类型层没有第二个编制分量可以并挂")
        .isEqualTo(1);
    assertThat(
            Unit.class.getRecordComponents()[Unit.class.getRecordComponents().length - 1].getType())
        .as("module 是 Optional<UnitModule>（擦除后 Optional）")
        .isEqualTo(Optional.class);
  }

  // ── 一单位一标签：命令层（领域操作） ────────────────────────────

  @Test
  void aUnitThatAlreadyHasArmyFormationRejectsGovFormation() {
    UnitState withArmy =
        UnitOperations.setArmyFormation(
            stateOf(plainUnit()), U1, new ArmyFormation(Optional.empty(), "garrison"));

    assertThatThrownBy(
            () ->
                UnitOperations.setGovFormation(
                    withArmy,
                    U1,
                    new GovFormation(
                        Map.of(), OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL)))
        .as("一单位至多一个编制标签：不静默替换")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ArmyFormation")
        .hasMessageContaining("至多一个");
    assertThat(withArmy.units().get(U1).module().orElseThrow())
        .as("拒绝后原标签一字不动")
        .isInstanceOf(ArmyFormation.class);
  }

  @Test
  void aUnitThatAlreadyHasGovFormationRejectsArmyFormation() {
    UnitState withGov =
        UnitOperations.setGovFormation(
            stateOf(plainUnit()),
            U1,
            new GovFormation(
                Map.of(), OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL));

    assertThatThrownBy(
            () ->
                UnitOperations.setArmyFormation(
                    withGov, U1, new ArmyFormation(Optional.empty(), "garrison")))
        .as("一单位至多一个编制标签：不静默替换")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GovFormation")
        .hasMessageContaining("至多一个");
    assertThat(withGov.units().get(U1).module().orElseThrow())
        .as("拒绝后原标签一字不动")
        .isInstanceOf(GovFormation.class);
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  private static UnitState stateOf(Unit unit) {
    return new UnitState(Map.of(unit.id(), unit));
  }

  /** 9 参兼容形态：module/jurisdiction 均为 empty，供"立编制"命令的起点。 */
  private static Unit plainUnit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        10,
        Map.of("步枪", 5),
        2,
        500,
        Optional.empty());
  }
}
