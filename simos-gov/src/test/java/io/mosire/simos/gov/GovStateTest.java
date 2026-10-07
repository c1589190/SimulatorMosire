package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code gov} 切片（阶段 10a，计划 §2.2）的构造期不变量、保序不可变、变更集往返（铁律 5）、旧档缺键兼容与 {@link GovCodec} 线格式往返的逐值判据。
 *
 * <p>★ 铁律 5 的判据是 {@code apply(between(base, target), base)} <b>逐字段重建 target</b>，不是“不抛”；编解码判据是
 * <b>六张表逐键序</b>与<b>字节稳定</b>，不是“非 null”。
 */
class GovStateTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final SimosTimestamp T9 = SimosTimestamp.of(9L);
  private static final StateRef REF1 = new StateRef(new BranchId("main"), new RevisionId(1L));
  private static final StateRef REF2 = new StateRef(new BranchId("main"), new RevisionId(2L));
  private static final StateMeta META2 = new StateMeta(REF2, T9);

  private static final UnitId U1 = new UnitId("gov-1");
  private static final UnitId U2 = new UnitId("gov-2");
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId CLOTH = CommodityId.parse(EconomyVocabulary.CLOTH_COMMODITY_ID);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  // ── GovState：构造期不变量 ──────────────────────────────────────────────────────────

  @Test
  void officesKeyMustEqualValueUnitId() {
    GovOfficeState office = GovOfficeState.empty(U2, 3L);

    assertThatThrownBy(() -> new GovState(Map.of(U1, office)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键必须与 GovOfficeState.unitId 一致")
        .hasMessageContaining("gov-1")
        .hasMessageContaining("gov-2");
  }

  @Test
  void officesNullKeyOrValueThrows() {
    Map<UnitId, GovOfficeState> nullKey = new LinkedHashMap<>();
    nullKey.put(null, GovOfficeState.empty(U1, 0L));
    assertThatThrownBy(() -> new GovState(nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");

    Map<UnitId, GovOfficeState> nullValue = new LinkedHashMap<>();
    nullValue.put(U1, null);
    assertThatThrownBy(() -> new GovState(nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
  }

  @Test
  void missingOfficesKeyIsReadAsEmptyAndDoesNotThrow() {
    GovState fromNull = new GovState(null);
    assertThat(fromNull.offices()).as("缺键（Jackson 缺参 null）⇒ 空表，旧档不读死").isEmpty();
    assertThat(fromNull).isEqualTo(GovState.empty());
  }

  @Test
  void officesPreserveInsertionOrderAndAreImmutable() {
    GovOfficeState first = GovOfficeState.empty(U2, 1L);
    GovOfficeState second = GovOfficeState.empty(U1, 2L);
    Map<UnitId, GovOfficeState> source = new LinkedHashMap<>();
    source.put(U2, first);
    source.put(U1, second);

    GovState state = new GovState(source);
    source.clear(); // 构造后改输入不得影响状态
    source.put(U1, GovOfficeState.empty(U1, 99L));

    assertThat(new ArrayList<>(state.offices().keySet()))
        .as("保序：迭代序 = 插入序（U2 先、U1 后），且与之后改动的输入无关")
        .containsExactly(U2, U1);
    assertThat(state.offices().get(U2)).isEqualTo(first);
    assertThat(state.offices().get(U1)).isEqualTo(second);
    assertThatThrownBy(() -> state.offices().put(U1, first))
        .as("冻结：外部不得改内部表")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void withOfficeDerivesKeyFromValueAndKeepsOtherOffices() {
    GovState base = new GovState(Map.of(U1, GovOfficeState.empty(U1, 1L)));
    GovOfficeState replacement =
        new GovOfficeState(
            U1, 2L, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 1L, 2L, 3L, 4L);

    GovState withReplacement = base.withOffice(replacement);
    assertThat(withReplacement.offices()).containsOnlyKeys(U1);
    assertThat(withReplacement.offices().get(U1)).isEqualTo(replacement);
    assertThat(base.offices().get(U1).tick()).as("withOffice 不改原状态").isEqualTo(1L);

    GovState withSecond = base.withOffice(GovOfficeState.empty(U2, 5L));
    assertThat(new ArrayList<>(withSecond.offices().keySet()))
        .as("追加键在表尾；原键序保留")
        .containsExactly(U1, U2);
  }

  // ── GovChangeSet：往返、旧档缺键 ────────────────────────────────────────────────────

  @Test
  void changeSetBetweenAndApplyRebuildTargetFieldByField() {
    GovState base = state(U1, office(U1, 1L));
    GovState target = new GovState(ordered(U1, office(U1, 2L), U2, office(U2, 2L)));

    GovChangeSet changeSet = GovChangeSet.between(base, target);
    assertThat(changeSet.isEmpty()).as("内容确实变了").isFalse();
    assertThat(GovChangeSet.apply(changeSet, base))
        .as("铁律 5：apply(between(base,target), base) 逐字段重建 target")
        .isEqualTo(target);
    assertThat(changeSet.offices()).isInstanceOf(FieldDelta.Upsert.class);
  }

  @Test
  void changeSetForEqualStatesIsAllUnchanged() {
    GovState target = state(U1, office(U1, 1L));

    GovChangeSet changeSet = GovChangeSet.between(target, target);
    assertThat(changeSet.isEmpty()).as("全相等 ⇒ Unchanged").isTrue();
    assertThat(changeSet.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GovChangeSet.apply(changeSet, target)).isEqualTo(target);
  }

  @Test
  void removalDeltaRoundTrips() {
    GovState base = new GovState(ordered(U1, office(U1, 1L), U2, office(U2, 1L)));
    GovState target = state(U1, office(U1, 1L));

    GovChangeSet changeSet = GovChangeSet.between(base, target);
    assertThat(changeSet.offices()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(GovChangeSet.apply(changeSet, base)).isEqualTo(target);
  }

  @Test
  void applyToUsesNewMetaNotStaleBaseMeta() {
    GovState base = state(U1, office(U1, 1L));
    GovState target = state(U2, office(U2, 1L));
    GovSnapshot baseSnapshot = new GovSnapshot(REF1, T0, base);

    GovSnapshot applied = GovChangeSet.between(base, target).applyTo(baseSnapshot, META2);

    assertThat(applied.ref()).as("C28：ref 取新坐标").isEqualTo(REF2);
    assertThat(applied.timestamp()).as("C28：timestamp 取新坐标").isEqualTo(T9);
    assertThat(applied.state()).as("状态逐字段重建 target").isEqualTo(target);
    assertThat(baseSnapshot.state()).as("不改 base 快照").isEqualTo(base);
  }

  @Test
  void changeSetMissingOfficesKeyDecodesAsUnchanged() {
    GovCodec codec = new GovCodec();

    GovChangeSet oldArchive = (GovChangeSet) codec.decodeChangeSet("{}");

    assertThat(oldArchive.isEmpty()).as("旧档缺 offices 键 ⇒ 一字未动，不抛").isTrue();
    assertThat(oldArchive.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(oldArchive.administrationPlans())
        .as("旧档缺 administrationPlans 键 ⇒ 一字未动，不抛")
        .isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(oldArchive.budgetPolicies())
        .as("旧档缺 budgetPolicies 键 ⇒ 一字未动，不抛")
        .isInstanceOf(FieldDelta.Unchanged.class);
  }

  // ── GovCodec：线格式往返与保序 ─────────────────────────────────────────────────────

  @Test
  void codecNamespaceIsGov() {
    assertThat(new GovCodec().namespace()).isEqualTo("gov");
  }

  @Test
  void snapshotRoundTripKeepsNonEmptyTablesAndTheirOrder() {
    GovCodec codec = new GovCodec();
    GovState state =
        new GovState(
            ordered(
                U2, office(U2, 4L),
                U1, richOffice(U1, 7L)));
    GovSnapshot snapshot = new GovSnapshot(REF1, T9, state);

    String firstBytes = codec.encodeSnapshot(snapshot);
    String secondBytes = codec.encodeSnapshot(snapshot);
    assertThat(secondBytes).as("字节是内容的纯函数：两次编码逐字节相同").isEqualTo(firstBytes);

    GovSnapshot decoded = (GovSnapshot) codec.decodeSnapshot(firstBytes);
    assertThat(decoded).as("快照往返逐字段相等").isEqualTo(snapshot);

    GovOfficeState decodedRich = decoded.state().offices().get(U1);
    assertThat(decodedRich).isEqualTo(richOffice(U1, 7L));
    assertThat(new ArrayList<>(decodedRich.lastAssessedGoods().keySet()))
        .as("商品表保序：grain → cloth")
        .containsExactly(GRAIN, CLOTH);
    assertThat(new ArrayList<>(decodedRich.lastPaidGoods().keySet()))
        .as("实付表保序：grain → cloth")
        .containsExactly(GRAIN, CLOTH);
    assertThat(new ArrayList<>(decodedRich.lastShortfallGoods().keySet()))
        .as("缺口表保序：grain → cloth")
        .containsExactly(GRAIN, CLOTH);
    assertThat(decodedRich.lastAssessedMoney().keySet()).containsExactly(SILVER);
    assertThat(new ArrayList<>(decoded.state().offices().keySet()))
        .as("offices 保序：U2 先、U1 后")
        .containsExactly(U2, U1);
  }

  @Test
  void changeSetRoundTripThroughJson() {
    GovCodec codec = new GovCodec();
    GovState base = new GovState(ordered(U2, office(U2, 1L), U1, office(U1, 1L)));
    GovState target = new GovState(ordered(U2, richOffice(U2, 2L), U1, office(U1, 2L)));
    GovChangeSet changeSet = GovChangeSet.between(base, target);

    String json = codec.encodeChangeSet(changeSet);
    GovChangeSet decoded = (GovChangeSet) codec.decodeChangeSet(json);

    assertThat(decoded).as("变更集往返逐字段相等").isEqualTo(changeSet);
    assertThat(GovChangeSet.apply(decoded, base)).as("解码后的变更集仍重建 target").isEqualTo(target);
  }

  @Test
  void changeSetUnchangedRoundTripThroughJson() {
    GovCodec codec = new GovCodec();
    GovState base = state(U1, office(U1, 1L));
    GovChangeSet unchanged = GovChangeSet.between(base, base);

    GovChangeSet decoded = (GovChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(unchanged));

    assertThat(decoded.isEmpty()).isTrue();
    assertThat(decoded.offices()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  // ── 两条源状态：构造/拷贝纪律/中性默认（Z2 §4.1）────────────────────────────────

  @Test
  void threeComponentConstructorCopiesAndPreservesOrder() {
    GovAdministrationPlan planU2 = plan(100L, 200L);
    GovAdministrationPlan planU1 = GovAdministrationPlan.neutral();
    GovBudgetPolicy policyU2 = policy();
    GovBudgetPolicy policyU1 = GovBudgetPolicy.neutral();

    Map<UnitId, GovOfficeState> offices = ordered(U2, office(U2, 4L), U1, office(U1, 1L));
    Map<UnitId, GovAdministrationPlan> plans = new LinkedHashMap<>();
    plans.put(U2, planU2);
    plans.put(U1, planU1);
    Map<UnitId, GovBudgetPolicy> policies = new LinkedHashMap<>();
    policies.put(U2, policyU2);
    policies.put(U1, policyU1);

    GovState state = new GovState(offices, plans, policies);
    offices.clear();
    plans.clear();
    policies.clear();

    assertThat(new ArrayList<>(state.offices().keySet()))
        .as("offices 保序且与之后改动的输入无关")
        .containsExactly(U2, U1);
    assertThat(new ArrayList<>(state.administrationPlans().keySet()))
        .as("administrationPlans 保插入序")
        .containsExactly(U2, U1);
    assertThat(new ArrayList<>(state.budgetPolicies().keySet()))
        .as("budgetPolicies 保插入序")
        .containsExactly(U2, U1);
    assertThat(state.administrationPlans().get(U2)).isEqualTo(planU2);
    assertThat(state.budgetPolicies().get(U2)).isEqualTo(policyU2);
    assertThatThrownBy(() -> state.administrationPlans().put(U1, planU2))
        .as("冻结：外部不得改内部表")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> state.budgetPolicies().put(U1, policyU2))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void missingSourceStateKeysReadAsEmptyAndNeutralDefaults() {
    GovState fromNulls = new GovState(null, null, null);
    assertThat(fromNulls).as("三组件全缺键（Jackson 缺参 null）⇒ 三张空表，旧档不读死").isEqualTo(GovState.empty());
    assertThat(fromNulls.administrationPlans()).isEmpty();
    assertThat(fromNulls.budgetPolicies()).isEmpty();

    GovState oldArchive = new GovState(Map.of(U1, office(U1, 1L)));
    assertThat(oldArchive.administrationPlans()).as("旧 1 参构造器/旧档缺键 ⇒ 空表").isEmpty();
    assertThat(oldArchive.budgetPolicies()).isEmpty();
    assertThat(oldArchive.administrationPlan(U1)).isEmpty();
    assertThat(oldArchive.budgetPolicy(U1)).isEmpty();

    GovAdministrationPlan neutralPlan = oldArchive.administrationPlanOrDefault(U1);
    assertThat(neutralPlan).isEqualTo(GovAdministrationPlan.neutral());
    assertThat(neutralPlan.securityPlannedLaborMilli()).isZero();
    assertThat(neutralPlan.paperworkPlannedLaborMilli()).isZero();
    assertThat(neutralPlan.postTiers()).hasSize(3);
    assertThat(neutralPlan.securitySupplyStaticModifierPerMille()).isEqualTo(1_000L);
    assertThat(neutralPlan.paperworkSupplyStaticModifierPerMille()).isEqualTo(1_000L);
    assertThat(neutralPlan.securityDemandStaticModifierPerMille()).isEqualTo(1_000L);
    assertThat(neutralPlan.paperworkDemandStaticModifierPerMille()).isEqualTo(1_000L);
    assertThat(neutralPlan.supernumerarySqrtCoefficient()).isEqualTo(1L);

    GovBudgetPolicy neutralPolicy = oldArchive.budgetPolicyOrDefault(U1);
    assertThat(neutralPolicy).as("预算缺省 ⇒ 不自动付").isEqualTo(GovBudgetPolicy.neutral());
    assertThat(neutralPolicy.orderedCategories()).isEmpty();
    assertThat(neutralPolicy.officialSalaryRule()).isEqualTo(GovOfficialSalaryRule.zero());
  }

  @Test
  void withOfficesKeepsSourceStateCopyDiscipline() {
    GovState base =
        new GovState(
            Map.of(U1, office(U1, 1L)), Map.of(U1, plan(100L, 200L)), Map.of(U1, policy()));

    GovState next = base.withOffices(Map.of(U1, office(U1, 9L)));

    assertThat(next.offices().get(U1).tick()).isEqualTo(9L);
    assertThat(next.administrationPlans())
        .as("§4.1 拷贝纪律：只改 offices 时必须原样带过 administrationPlans")
        .isEqualTo(base.administrationPlans());
    assertThat(next.budgetPolicies()).isEqualTo(base.budgetPolicies());
    assertThat(base.offices().get(U1).tick()).as("withOffices 不改原状态").isEqualTo(1L);
  }

  @Test
  void withAdministrationPlanAndBudgetPolicyKeepOtherComponents() {
    GovAdministrationPlan originalPlan = plan(100L, 200L);
    GovBudgetPolicy originalPolicy = policy();
    GovState base =
        new GovState(
            Map.of(U1, office(U1, 1L)), Map.of(U1, originalPlan), Map.of(U1, originalPolicy));
    GovAdministrationPlan replacementPlan = plan(300L, 400L);
    GovBudgetPolicy replacementPolicy =
        policy(List.of(new GovBudgetLine(GovBudgetCategory.ADMIN_SALARY, 5L, 50L)), 7L, 11L);

    GovState withPlan = base.withAdministrationPlan(U2, replacementPlan);
    assertThat(withPlan.offices()).isEqualTo(base.offices());
    assertThat(withPlan.budgetPolicies()).isEqualTo(base.budgetPolicies());
    assertThat(withPlan.administrationPlans())
        .containsExactly(entry(U1, originalPlan), entry(U2, replacementPlan));
    assertThat(base.administrationPlans()).doesNotContainKey(U2);

    GovState withPolicy = base.withBudgetPolicy(U2, replacementPolicy);
    assertThat(withPolicy.offices()).isEqualTo(base.offices());
    assertThat(withPolicy.administrationPlans()).isEqualTo(base.administrationPlans());
    assertThat(withPolicy.budgetPolicies())
        .containsExactly(entry(U1, originalPolicy), entry(U2, replacementPolicy));
    assertThat(base.budgetPolicies()).doesNotContainKey(U2);
  }

  // ── 三组件变更集：往返与脏组件判别 ────────────────────────────────────────────────

  @Test
  void changeSetBetweenAndApplyRebuildsSourceStates() {
    GovState base = GovState.empty();
    GovState target =
        new GovState(
            Map.of(U1, richOffice(U1, 5L)),
            Map.of(U1, plan(100L, 200L)),
            Map.of(
                U1, policy(List.of(new GovBudgetLine(GovBudgetCategory.OTHER, 1L, 2L)), 3L, 4L)));

    GovChangeSet changeSet = GovChangeSet.between(base, target);

    assertThat(changeSet.isEmpty()).isFalse();
    assertThat(changeSet.offices()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.administrationPlans()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.budgetPolicies()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(GovChangeSet.apply(changeSet, base))
        .as("铁律 5：apply(between(base,target), base) 逐字段重建 target（含两条源状态）")
        .isEqualTo(target);
  }

  @Test
  void changeSetForOnlySourceStateChangesMarksOfficesUnchanged() {
    GovState base = new GovState(Map.of(U1, office(U1, 1L)));
    GovState target =
        new GovState(
            Map.of(U1, office(U1, 1L)), Map.of(U1, plan(100L, 200L)), Map.of(U1, policy()));

    GovChangeSet changeSet = GovChangeSet.between(base, target);

    assertThat(changeSet.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(changeSet.administrationPlans()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.budgetPolicies()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(changeSet.isEmpty()).isFalse();
    assertThat(GovChangeSet.apply(changeSet, base)).isEqualTo(target);
  }

  // ── 三组件线格式往返 ──────────────────────────────────────────────────────────────

  @Test
  void snapshotRoundTripKeepsSourceStateOrderAndFields() {
    GovCodec codec = new GovCodec();
    GovAdministrationPlan planU2 = plan(111L, 222L);
    GovAdministrationPlan planU1 = plan(333L, 444L);
    GovBudgetPolicy policyU2 =
        policy(List.of(new GovBudgetLine(GovBudgetCategory.MILITARY_STIPEND, 1L, 9L)), 3L, 4L);
    GovBudgetPolicy policyU1 =
        policy(List.of(new GovBudgetLine(GovBudgetCategory.OTHER, 0L, Long.MAX_VALUE)), 5L, 6L);
    Map<UnitId, GovAdministrationPlan> plans = new LinkedHashMap<>();
    plans.put(U2, planU2);
    plans.put(U1, planU1);
    Map<UnitId, GovBudgetPolicy> policies = new LinkedHashMap<>();
    policies.put(U2, policyU2);
    policies.put(U1, policyU1);
    GovState state =
        new GovState(ordered(U2, office(U2, 4L), U1, richOffice(U1, 7L)), plans, policies);
    GovSnapshot snapshot = new GovSnapshot(REF1, T9, state);

    String firstBytes = codec.encodeSnapshot(snapshot);
    assertThat(codec.encodeSnapshot(snapshot)).as("字节是内容的纯函数：两次编码逐字节相同").isEqualTo(firstBytes);

    GovSnapshot decoded = (GovSnapshot) codec.decodeSnapshot(firstBytes);

    assertThat(decoded).as("三组件快照往返逐字段相等").isEqualTo(snapshot);
    assertThat(new ArrayList<>(decoded.state().administrationPlans().keySet()))
        .as("administrationPlans 保序：U2 先、U1 后")
        .containsExactly(U2, U1);
    assertThat(new ArrayList<>(decoded.state().budgetPolicies().keySet()))
        .as("budgetPolicies 保序：U2 先、U1 后")
        .containsExactly(U2, U1);
    assertThat(decoded.state().administrationPlans().get(U1)).isEqualTo(planU1);
    assertThat(decoded.state().budgetPolicies().get(U1)).isEqualTo(policyU1);
  }

  @Test
  void changeSetRoundTripThroughJsonIncludesSourceStates() {
    GovCodec codec = new GovCodec();
    GovState base =
        new GovState(ordered(U1, office(U1, 1L)), Map.of(U1, plan(1L, 2L)), Map.of(U1, policy()));
    GovState target =
        new GovState(
            ordered(U1, office(U1, 4L)),
            Map.of(U1, plan(5L, 6L)),
            Map.of(
                U1,
                policy(
                    List.of(new GovBudgetLine(GovBudgetCategory.ADMIN_STIPEND, 2L, 3L)), 7L, 8L)));

    GovChangeSet changeSet = GovChangeSet.between(base, target);
    GovChangeSet decoded = (GovChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(changeSet));

    assertThat(decoded).as("三组件变更集往返逐字段相等").isEqualTo(changeSet);
    assertThat(GovChangeSet.apply(decoded, base)).isEqualTo(target);
  }

  @Test
  void oldArchiveSnapshotJsonWithoutSourceStateKeysDecodesToEmptySourceStates() {
    GovCodec codec = new GovCodec();
    String oldArchiveJson =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":2}},"
            + "\"timestamp\":{\"tick\":9,\"calendarLabel\":null},"
            + "\"state\":{\"offices\":{}}}";

    GovSnapshot decoded = (GovSnapshot) codec.decodeSnapshot(oldArchiveJson);

    assertThat(decoded.ref()).isEqualTo(REF2);
    assertThat(decoded.timestamp()).isEqualTo(T9);
    assertThat(decoded.state().offices()).isEmpty();
    assertThat(decoded.state().administrationPlans())
        .as("旧档 state 缺 administrationPlans 键 ⇒ 空表（中性默认由 orDefault 读口给）")
        .isEmpty();
    assertThat(decoded.state().budgetPolicies())
        .as("旧档 state 缺 budgetPolicies 键 ⇒ 空表（不自动付）")
        .isEmpty();
    assertThat(decoded.state()).isEqualTo(GovState.empty());
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static GovState state(UnitId unitId, GovOfficeState office) {
    return new GovState(Map.of(unitId, office));
  }

  private static Map<UnitId, GovOfficeState> ordered(Object... pairs) {
    Map<UnitId, GovOfficeState> offices = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      offices.put((UnitId) pairs[i], (GovOfficeState) pairs[i + 1]);
    }
    return offices;
  }

  /** 六表全空、四个 per-mille 全 0 的基线读数。 */
  private static GovOfficeState office(UnitId unitId, long tick) {
    return GovOfficeState.empty(unitId, tick);
  }

  /** 六张表都非空且顺序可辨的富读数：grain → cloth；silver。 */
  private static GovOfficeState richOffice(UnitId unitId, long tick) {
    Map<CommodityId, Long> assessedGoods = new LinkedHashMap<>();
    assessedGoods.put(GRAIN, 10L);
    assessedGoods.put(CLOTH, 5L);
    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    paidGoods.put(GRAIN, 7L);
    paidGoods.put(CLOTH, 2L);
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    shortfallGoods.put(GRAIN, 3L);
    shortfallGoods.put(CLOTH, 3L);
    Map<CurrencyId, Long> assessedMoney = new LinkedHashMap<>();
    assessedMoney.put(SILVER, 9L);
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    paidMoney.put(SILVER, 4L);
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    shortfallMoney.put(SILVER, 5L);
    return new GovOfficeState(
        unitId,
        tick,
        assessedGoods,
        paidGoods,
        shortfallGoods,
        assessedMoney,
        paidMoney,
        shortfallMoney,
        940L,
        880L,
        1010L,
        10L);
  }

  /** 四修正 1000‰、k=1、默认 3 档的编制计划（只给两维计划量）。 */
  private static GovAdministrationPlan plan(long securityPlanned, long paperworkPlanned) {
    return new GovAdministrationPlan(
        securityPlanned,
        paperworkPlanned,
        GovAdministrationPlan.DEFAULT_POST_TIERS,
        1_000L,
        1_000L,
        1_000L,
        1_000L,
        1L);
  }

  /** 中性预算：空类别表 + 零工资（不自动付）。 */
  private static GovBudgetPolicy policy() {
    return new GovBudgetPolicy(List.of(), GovOfficialSalaryRule.zero());
  }

  /** 带工资速率的预算策略（类别表可空 = 不自动付，但工资规则独立保留）。 */
  private static GovBudgetPolicy policy(
      List<GovBudgetLine> lines, long grainRate, long silverRate) {
    return new GovBudgetPolicy(lines, new GovOfficialSalaryRule(grainRate, silverRate));
  }
}
