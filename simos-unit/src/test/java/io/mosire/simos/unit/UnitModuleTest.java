package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
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
 *   <li>{@link GovernmentFormation}/{@link ArmyFormation}/{@link OfficePolicy} 的 null / 负值 / 空白
 *       role 一律当场抛，不静默钳制；
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
  private static final HouseholdId HH_A = HouseholdId.parse("hh-a");
  private static final HouseholdId HH_B = HouseholdId.parse("hh-b");
  private static final HouseholdId HH_EXT_A = HouseholdId.parse("hh-ext-a");
  private static final HouseholdId HH_EXT_B = HouseholdId.parse("hh-ext-b");

  // ── GovernmentFormation：构造期不变量 ─────────────────────────────────

  @Test
  void govFormationRejectsNullStaff() {
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    null,
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 不得为 null（无人员用 Map.of()）");
  }

  @Test
  void govFormationRejectsNullKeysAndValuesInStaff() {
    Map<StaffRole, Long> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 1L);
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    nullKey,
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的键与值都不得为 null");

    Map<StaffRole, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(StaffRole.SCRIBE, null);
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    nullValue,
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的键与值都不得为 null");
  }

  @Test
  void govFormationRejectsNegativeStaffWithoutClamping() {
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(StaffRole.YAMEN, -1L),
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("staff 的值必须 ≥ 0: YAMEN=-1");
  }

  @Test
  void govFormationRejectsNullPolicySuperiorAndLevel() {
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(), Map.of(), null, Optional.empty(), GovernmentLevel.CENTRAL, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("policy 不得为 null");
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(),
                    Map.of(),
                    OfficePolicy.defaults(),
                    null,
                    GovernmentLevel.CENTRAL,
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("superiorGov 不得为 null（无上级用 Optional.empty()）");
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(), Map.of(), OfficePolicy.defaults(), Optional.empty(), null, Map.of()))
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

    GovernmentFormation formation =
        new GovernmentFormation(
            staff,
            Map.of(),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            Map.of());

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

  // ── GovernmentFormation：externalPosts（Z3d）契约 ───────────────

  /** ★ Z3d：{@code externalPosts} 缺失/null ⇒ 空表（旧档兼容），且不得影响内部表/投影判定。 */
  @Test
  void govFormationNormalizesMissingExternalPostsToEmptyMap() {
    GovernmentFormation noExternal =
        new GovernmentFormation(
            Map.of(),
            Map.of(),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            null);
    assertThat(noExternal.externalPosts()).as("null ⇒ 空表（不是 null）").isEmpty();
    assertThat(noExternal.hasAnyPosts()).as("两张表都空 ⇒ 无岗位").isFalse();
    assertThat(noExternal.staffIsHouseholdProjection()).as("无岗位 ⇒ staff 仍是旧口径权威").isFalse();
    assertThat(noExternal.allPosts()).isEmpty();

    GovernmentPostOfHousehold internal = post(HH_A, StaffRole.SCRIBE, "tier-1");
    GovernmentFormation internalOnly =
        new GovernmentFormation(
            Map.of(),
            orderedPosts(Map.entry(HH_A, internal)),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            null);
    assertThat(internalOnly.externalPosts()).as("内表非空时 externalPosts 仍归一为空表").isEmpty();
    assertThat(internalOnly.hasAnyPosts()).isTrue();
    assertThat(internalOnly.staffIsHouseholdProjection()).isTrue();
    assertThat(internalOnly.allPosts())
        .as("externalPosts 空 ⇒ allPosts 退化为内部表")
        .containsExactly(Map.entry(HH_A, internal));
  }

  /** ★ Z3d：{@code externalPosts} 的键与值非 null、键 == value.householdId()，违者具名 IAE。 */
  @Test
  void govFormationRejectsExternalPostsWithMismatchedKeyNullKeyAndNullValue() {
    GovernmentPostOfHousehold mismatched = post(HH_A, StaffRole.SCRIBE, "tier-1");
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(),
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of(HH_EXT_A, mismatched)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("externalPosts")
        .hasMessageContaining("必须等于")
        .hasMessageContaining(HH_EXT_A.value())
        .hasMessageContaining(HH_A.value());

    Map<HouseholdId, GovernmentPostOfHousehold> nullKey = new LinkedHashMap<>();
    nullKey.put(null, post(HH_A, StaffRole.SCRIBE, "tier-1"));
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(),
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("externalPosts 的键与值都不得为 null");

    Map<HouseholdId, GovernmentPostOfHousehold> nullValue = new LinkedHashMap<>();
    nullValue.put(HH_EXT_A, null);
    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(),
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("externalPosts 的键与值都不得为 null");
  }

  /** ★ Z3d：同一家户不得同时在内部 {@code householdPosts} 与外部 {@code externalPosts}（两表互斥）。 */
  @Test
  void govFormationRejectsSameHouseholdInInternalAndExternalTables() {
    GovernmentPostOfHousehold internal = post(HH_A, StaffRole.SCRIBE, "tier-1");
    GovernmentPostOfHousehold external = post(HH_A, StaffRole.YAMEN, "tier-2");

    assertThatThrownBy(
            () ->
                new GovernmentFormation(
                    Map.of(),
                    orderedPosts(Map.entry(HH_A, internal)),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    orderedPosts(Map.entry(HH_A, external))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同一家户不得同时在")
        .hasMessageContaining("householdPosts")
        .hasMessageContaining("externalPosts")
        .hasMessageContaining(HH_A.value());
  }

  /** ★ Z3d：{@code allPosts()} 内部插入序在前、外部插入序在后；{@code postOf} 两表都查得到且保序冻结。 */
  @Test
  void allPostsOrdersInternalBeforeExternalAndPostOfFindsBoth() {
    GovernmentPostOfHousehold internalA = post(HH_A, StaffRole.YAMEN, "tier-1");
    GovernmentPostOfHousehold internalB = post(HH_B, StaffRole.POST, "tier-2");
    GovernmentPostOfHousehold externalA = post(HH_EXT_A, StaffRole.SCRIBE, "tier-3");
    GovernmentPostOfHousehold externalB = post(HH_EXT_B, StaffRole.SCRIBE, "");
    GovernmentFormation formation =
        new GovernmentFormation(
            Map.of(),
            orderedPosts(Map.entry(HH_B, internalB), Map.entry(HH_A, internalA)),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            orderedPosts(Map.entry(HH_EXT_A, externalA), Map.entry(HH_EXT_B, externalB)));

    assertThat(new ArrayList<>(formation.allPosts().keySet()))
        .as("内部表插入序 U+外部表插入序（不是哈希序、不是 externals 插到前面）")
        .containsExactly(HH_B, HH_A, HH_EXT_A, HH_EXT_B);
    assertThat(formation.postOf(HH_A)).contains(internalA);
    assertThat(formation.postOf(HH_B)).contains(internalB);
    assertThat(formation.postOf(HH_EXT_A)).contains(externalA);
    assertThat(formation.postOf(HH_EXT_B)).contains(externalB);
    assertThat(formation.postOf(HouseholdId.parse("hh-none"))).isEmpty();
    assertThat(formation.staffIsHouseholdProjection()).as("外部岗位非空同样让 staff 降为投影（Z3d 同权）").isTrue();
    assertThatThrownBy(() -> formation.allPosts().clear())
        .as("合并视图必须冻结")
        .isInstanceOf(UnsupportedOperationException.class);

    GovernmentFormation internalOnly =
        new GovernmentFormation(
            Map.of(),
            orderedPosts(Map.entry(HH_A, internalA)),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            Map.of());
    assertThat(internalOnly.allPosts())
        .as("外部表为空 ⇒ 零拷贝返回内部表（旧世界行为不变）")
        .isSameAs(internalOnly.governmentPostsOfHousehold());
  }

  /** ★ Z4/C4 + Z3d：{@code projectedStaff} 聚合内外部岗位，角色键沿用首次出现序。 */
  @Test
  void projectedStaffAggregatesInternalAndExternalPostsInFirstOccurrenceOrder() {
    GovernmentPostOfHousehold externalScribe = post(HH_EXT_A, StaffRole.SCRIBE, "tier-1");
    GovernmentFormation formation =
        new GovernmentFormation(
            Map.of(),
            orderedPosts(
                Map.entry(HH_B, post(HH_B, StaffRole.YAMEN, "tier-2")),
                Map.entry(HH_A, post(HH_A, StaffRole.SCRIBE, "tier-1"))),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            orderedPosts(Map.entry(HH_EXT_A, externalScribe)));

    Map<StaffRole, Long> projection =
        formation.projectedStaff(
            household -> {
              if (household.equals(HH_A)) {
                return 5L;
              }
              if (household.equals(HH_B)) {
                return 4L;
              }
              return 2L; // HH_EXT_A
            });

    assertThat(new ArrayList<>(projection.keySet()))
        .as("角色首次出现序 = 全岗位表遍历序（YAMEN 在 SCRIBE 前，但外部 SCRIBE 并入既有 SCRIBE）")
        .containsExactly(StaffRole.YAMEN, StaffRole.SCRIBE);
    assertThat(projection)
        .as("SCRIBE = 内部 5 + 外部 2（两张表同权聚合）")
        .containsExactly(Map.entry(StaffRole.YAMEN, 4L), Map.entry(StaffRole.SCRIBE, 7L));
  }

  /** ★ Z4：{@code projectedStaff} 的负贡献量与求和溢出都具名拒（不静默钳 0 / 不 wrap）。 */
  @Test
  void projectedStaffRejectsNegativeContributionAndOverflow() {
    GovernmentFormation twoScribes =
        new GovernmentFormation(
            Map.of(),
            orderedPosts(
                Map.entry(HH_A, post(HH_A, StaffRole.SCRIBE, "tier-1")),
                Map.entry(HH_B, post(HH_B, StaffRole.SCRIBE, "tier-1"))),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            Map.of());

    assertThatThrownBy(() -> twoScribes.projectedStaff(household -> -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负")
        .hasMessageContaining(HH_A.value());

    assertThatThrownBy(() -> twoScribes.projectedStaff(household -> Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("溢出")
        .hasMessageContaining("SCRIBE");
  }

  /**
   * ★★ Z4 #13 的 unit 侧钩子：{@code postTiers} 权重目录住在 gov 计划表，岗位只存不透明 {@code tierId}—— {@link
   * GovernmentPostOfHousehold} 恰五个分量、没有任何权重快照；改计划表权重不可能让本 record 的逐值变。
   */
  @Test
  void governmentPostStoresOnlyTheOpaqueTierReferenceAndNoPlanWeights() {
    List<String> components =
        Arrays.stream(GovernmentPostOfHousehold.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertThat(components)
        .as("岗位 record 恰这五个分量：多一个权重字段 = 把计划目录复制进 posts（引用完整性第二本账）")
        .containsExactly("householdId", "role", "level", "headOfGovernment", "tierId");

    GovernmentPostOfHousehold tiered = post(HH_EXT_A, StaffRole.SCRIBE, "tier-2");
    assertThat(tiered)
        .as("tierId 只是不透明字符串：逐值相等即可（权重目录不在 unit 模块）")
        .isEqualTo(post(HH_EXT_A, StaffRole.SCRIBE, "tier-2"));
    assertThat(tiered.tierId()).isEqualTo("tier-2");
    assertThat(tiered.hasTier()).isTrue();
  }

  /** ★ Z4/Z3d：{@code tierId} 缺省（4 参/显式 null）⇒ 空串 legacy；空串 = 未指派档位。 */
  @Test
  void governmentPostLegacyTierDefaultsToEmptyString() {
    GovernmentPostOfHousehold oldFourArg =
        new GovernmentPostOfHousehold(HH_A, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true);
    assertThat(oldFourArg.tierId()).as("旧 4 参构造器 ⇒ legacy 空串").isEmpty();
    assertThat(oldFourArg.hasTier()).isFalse();

    GovernmentPostOfHousehold nullTier =
        new GovernmentPostOfHousehold(HH_A, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true, null);
    assertThat(nullTier.tierId()).as("显式 null 归一为空串（旧 JSON 缺字段同语义）").isEmpty();
    assertThat(nullTier.hasTier()).isFalse();

    GovernmentPostOfHousehold emptyTier = post(HH_A, StaffRole.SCRIBE, "");
    assertThat(emptyTier.tierId()).isEmpty();
    assertThat(emptyTier.hasTier()).isFalse();
  }

  /** ★ Z4：岗位配置的必填身份字段（家户/角色/层级）null ⇒ 当场 IAE，不静默。 */
  @Test
  void governmentPostRejectsNullHouseholdRoleAndLevel() {
    assertThatThrownBy(
            () ->
                new GovernmentPostOfHousehold(
                    null, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("householdId");
    assertThatThrownBy(
            () -> new GovernmentPostOfHousehold(HH_A, null, GovernmentLevel.CENTRAL, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role");
    assertThatThrownBy(() -> new GovernmentPostOfHousehold(HH_A, StaffRole.SCRIBE, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level");
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

    // ★ G12 修正（2026-10-01）：旧值 RATION_MILLI_PER_PERSON=10000 是"每人每 120 天"的口径，
    //   而 GovDaily 按每 tick 乘 ⇒ 120× 超支。默认必须是"每人每日"的 83 毫粮（120 天 = 9,960）。
    assertThat(defaults.grainPerStaffPerTick())
        .as("粮的单一拼写点是 EconomyVocabulary.dailyRationMilli(1,1)")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(1L, 1L))
        .isEqualTo(83L)
        .isNotEqualTo(EconomyVocabulary.RATION_MILLI_PER_PERSON);
    assertThat(defaults.clothPerStaffPerCycle())
        .as("布的单一拼写点是 EconomyVocabulary.CLOTH_MILLI_PER_PERSON")
        .isEqualTo(EconomyVocabulary.CLOTH_MILLI_PER_PERSON)
        .isEqualTo(1000L);
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
    assertThat(GovernmentLevel.values())
        .as("层级恰中央/省两档")
        .containsExactly(GovernmentLevel.CENTRAL, GovernmentLevel.PROVINCE);
  }

  // ── 一单位一标签：sealed 类型层 ────────────────────────────────

  @Test
  void unitModuleIsSealedWithExactlyGovAndArmyPermitted() {
    assertThat(UnitModule.class.isSealed()).as("互斥由 sealed 类型层保证").isTrue();
    assertThat(UnitModule.class.getPermittedSubclasses())
        .as("许可子类恰 GovernmentFormation / ArmyFormation，没有第三条路")
        .containsExactlyInAnyOrder(GovernmentFormation.class, ArmyFormation.class);
  }

  @Test
  void unitHasExactlyOneModuleComponentOfOptionalType() {
    List<String> names =
        Arrays.stream(Unit.class.getRecordComponents()).map(RecordComponent::getName).toList();
    assertThat(names.stream().filter("module"::equals).count())
        .as("module 只出现一次——类型层没有第二个编制分量可以并挂")
        .isEqualTo(1);
    assertThat(
            Unit.class.getRecordComponents()[Unit.class.getRecordComponents().length - 3].getType())
        .as("module 是 Optional<UnitModule>（擦除后 Optional；其后是 stateDescriptions / households）")
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
                UnitOperations.setGovernmentFormation(
                    withArmy,
                    U1,
                    new GovernmentFormation(
                        Map.of(),
                        Map.of(),
                        OfficePolicy.defaults(),
                        Optional.empty(),
                        GovernmentLevel.CENTRAL,
                        Map.of())))
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
        UnitOperations.setGovernmentFormation(
            stateOf(plainUnit()),
            U1,
            new GovernmentFormation(
                Map.of(),
                Map.of(),
                OfficePolicy.defaults(),
                Optional.empty(),
                GovernmentLevel.CENTRAL,
                Map.of()));

    assertThatThrownBy(
            () ->
                UnitOperations.setArmyFormation(
                    withGov, U1, new ArmyFormation(Optional.empty(), "garrison")))
        .as("一单位至多一个编制标签：不静默替换")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GovernmentFormation")
        .hasMessageContaining("至多一个");
    assertThat(withGov.units().get(U1).module().orElseThrow())
        .as("拒绝后原标签一字不动")
        .isInstanceOf(GovernmentFormation.class);
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  /** Z4/Z3d 岗位配置夹具：键 == householdId，tierId 逐值给（空串 = legacy）。 */
  private static GovernmentPostOfHousehold post(
      HouseholdId household, StaffRole role, String tierId) {
    return new GovernmentPostOfHousehold(household, role, GovernmentLevel.CENTRAL, false, tierId);
  }

  /** 保序岗位表（顺序是内容的一部分；逐位断言用）。 */
  @SafeVarargs
  private static Map<HouseholdId, GovernmentPostOfHousehold> orderedPosts(
      Map.Entry<HouseholdId, GovernmentPostOfHousehold>... entries) {
    Map<HouseholdId, GovernmentPostOfHousehold> posts = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry : entries) {
      posts.put(entry.getKey(), entry.getValue());
    }
    return posts;
  }

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
        List.of(new CompositionEntry("步枪", 5)),
        2,
        500,
        Optional.empty());
  }
}
