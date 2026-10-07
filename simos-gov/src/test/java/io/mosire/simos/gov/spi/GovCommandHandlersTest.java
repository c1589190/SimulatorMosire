package io.mosire.simos.gov.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovOfficialSalaryRule;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * gov 首个 handler 批（Z2）的命令边界判据（z2 台账 §9.7 / 设计书 §12.3、§12.7）：
 *
 * <ul>
 *   <li>{@code gov.SetAdministrationPlan} / {@code gov.SetBudgetPolicy}：合法载荷 ⇒ {@code Applied}
 *       且变更集重建逐值目标；
 *   <li>除 {@code unitId} 外全缺省 ⇒ 中性默认（计划 0/默认 3 档/修正 1000‰/k=1；预算空/0 工资）；
 *   <li>★ Z7e-3 双模：{@code mode} 缺省 = {@code PATCH}（缺省字段保留现值，只改上缴率不清预算；{@code orderedCategories:[]}
 *       才清空），{@code mode:"REPLACE"} = 旧整表替换；词表大小写不敏感、词表外具名拒；
 *   <li>幂等重放（载荷与既有配置逐值相同）⇒ 空变更集、<b>不落 revision</b>（spec §16.1）；
 *   <li>GOV 单位不存在 / 单位缺 {@code GovernmentFormation} / 坏载荷 ⇒ 具名 {@code Rejected}，零变更；
 *   <li>切片装配故障（缺 gov/unit 切片）⇒ {@code IllegalStateException}（ERROR 不降级），不是 Rejected；
 *   <li>两条命令都实现 {@link GmOnlyCommand}、不实现 {@link CommandTargets}（不进决策令/决策人路径）。
 * </ul>
 */
class GovCommandHandlersTest {

  private static final UnitId GOV_ID = new UnitId("gov-1");
  private static final UnitId PLAIN_ID = new UnitId("plain-1");
  private static final UnitId MISSING_ID = new UnitId("gov-missing");
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1L));
  private static final StateMeta META = new StateMeta(REF, T0);

  private static final String ADMIN_PLAN_PAYLOAD =
      "{\"unitId\":\"gov-1\","
          + "\"securityPlannedLaborMilli\":3200000,"
          + "\"paperworkPlannedLaborMilli\":1600000,"
          + "\"postTiers\":["
          + "{\"tierId\":\"tier-1\",\"securityWeightPerMille\":1000,\"paperworkWeightPerMille\":0},"
          + "{\"tierId\":\"tier-2\",\"securityWeightPerMille\":0,\"paperworkWeightPerMille\":1000},"
          + "{\"tierId\":\"tier-3\",\"securityWeightPerMille\":500,\"paperworkWeightPerMille\":500}],"
          + "\"securitySupplyStaticModifierPerMille\":1500,"
          + "\"paperworkSupplyStaticModifierPerMille\":1600,"
          + "\"securityDemandStaticModifierPerMille\":1700,"
          + "\"paperworkDemandStaticModifierPerMille\":1800,"
          + "\"supernumerarySqrtCoefficient\":4}";

  private static final GovAdministrationPlan EXPECTED_PLAN =
      new GovAdministrationPlan(
          3_200_000L,
          1_600_000L,
          List.of(
              new GovPostTier("tier-1", 1_000L, 0L),
              new GovPostTier("tier-2", 0L, 1_000L),
              new GovPostTier("tier-3", 500L, 500L)),
          1_500L,
          1_600L,
          1_700L,
          1_800L,
          4L);

  private static final String BUDGET_POLICY_PAYLOAD =
      "{\"unitId\":\"gov-1\","
          + "\"orderedCategories\":["
          + "{\"category\":\"ADMIN_STIPEND\",\"minPerCycle\":0,\"capPerCycle\":100000},"
          + "{\"category\":\"MILITARY_STIPEND\",\"minPerCycle\":5,\"capPerCycle\":50000},"
          + "{\"category\":\"ADMIN_SALARY\",\"minPerCycle\":0,\"capPerCycle\":30000}],"
          + "\"officialSalaryRule\":{\"grainMilliPerCommittedHour\":10,"
          + "\"silverMilliPerCommittedHour\":5}}";

  private static final GovBudgetPolicy EXPECTED_POLICY =
      new GovBudgetPolicy(
          List.of(
              new GovBudgetLine(GovBudgetCategory.ADMIN_STIPEND, 0L, 100_000L),
              new GovBudgetLine(GovBudgetCategory.MILITARY_STIPEND, 5L, 50_000L),
              new GovBudgetLine(GovBudgetCategory.ADMIN_SALARY, 0L, 30_000L)),
          new GovOfficialSalaryRule(10L, 5L));

  // ── gov.SetAdministrationPlan ─────────────────────────────────────────────────────

  @Test
  void setAdministrationPlanAppliesValidPayloadAndRebuildsExactPlan() {
    SetAdministrationPlanHandler handler = new SetAdministrationPlanHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome outcome = handler.handle(state, ADMIN_PLAN_PAYLOAD);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    assertThat(changeSet(applied).isEmpty()).isFalse();
    assertThat(changeSet(applied).administrationPlans()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(govState(applied, state).administrationPlans())
        .as("合法载荷逐值重建计划（两维计划量/3 档/四个修正/k）")
        .containsExactly(entry(GOV_ID, EXPECTED_PLAN));
  }

  @Test
  void setAdministrationPlanDefaultsOnlyUnitIdToNeutralPlan() {
    SetAdministrationPlanHandler handler = new SetAdministrationPlanHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) handler.handle(state, "{\"unitId\":\"gov-1\"}");

    assertThat(govState(applied, state).administrationPlans().get(GOV_ID))
        .as("缺省 = 计划量 0/默认 3 档/修正 1000‰/k=1（GoVPayloads 只展开一次）")
        .isEqualTo(GovAdministrationPlan.neutral());
  }

  @Test
  void setAdministrationPlanIdempotentReplayIsNoopEmptyChangeSet() {
    SetAdministrationPlanHandler handler = new SetAdministrationPlanHandler();
    SimulationState first = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState afterFirst =
        govState((HandlerOutcome.Applied) handler.handle(first, ADMIN_PLAN_PAYLOAD), first);
    SimulationState replayState = state(afterFirst, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied replay =
        (HandlerOutcome.Applied) handler.handle(replayState, ADMIN_PLAN_PAYLOAD);

    assertThat(changeSet(replay).isEmpty()).as("逐值相同 ⇒ 空变更集、不落 revision（spec §16.1）").isTrue();
    assertThat(changeSet(replay).administrationPlans()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(govState(replay, replayState)).isEqualTo(afterFirst);
  }

  @Test
  void setAdministrationPlanRejectsUnknownUnitAndNonGovernmentUnit() {
    SetAdministrationPlanHandler handler = new SetAdministrationPlanHandler();

    HandlerOutcome unknown =
        handler.handle(
            state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID))),
            "{\"unitId\":\"gov-missing\"}");
    assertThat(unknown).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) unknown).reason()).contains("GOV 单位不存在");

    HandlerOutcome nonGov =
        handler.handle(
            state(GovState.empty(), Map.of(PLAIN_ID, armyUnit(PLAIN_ID))),
            "{\"unitId\":\"plain-1\"}");
    assertThat(nonGov).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) nonGov).reason()).contains("缺 GovernmentFormation");
  }

  @Test
  void setAdministrationPlanRejectsBadPayloads() {
    SetAdministrationPlanHandler handler = new SetAdministrationPlanHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"postTiers\":["
                            + "{\"tierId\":\"a\"},{\"tierId\":\"b\"}]}"))
                .reason())
        .contains("必须恰 3 档");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"postTiers\":["
                            + "{\"tierId\":\"dup\"},{\"tierId\":\"dup\"},{\"tierId\":\"c\"}]}"))
                .reason())
        .contains("不得重复");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state, "{\"unitId\":\"gov-1\"," + "\"securityPlannedLaborMilli\":-1}"))
                .reason())
        .contains("securityPlannedLaborMilli");
    assertThat(((HandlerOutcome.Rejected) handler.handle(state, "{}")).reason())
        .contains("字段 unitId 必须是非空白字符串");
  }

  // ── gov.SetBudgetPolicy ───────────────────────────────────────────────────────────

  @Test
  void setBudgetPolicyAppliesValidPayloadAndRebuildsExactPolicy() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome outcome = handler.handle(state, BUDGET_POLICY_PAYLOAD);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    assertThat(changeSet(applied).isEmpty()).isFalse();
    assertThat(govState(applied, state).budgetPolicies())
        .as("合法载荷逐值重建有序类别表 + 工资规则")
        .containsExactly(entry(GOV_ID, EXPECTED_POLICY));
  }

  @Test
  void setBudgetPolicyDefaultsOnlyUnitIdToNeutralPolicy() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) handler.handle(state, "{\"unitId\":\"gov-1\"}");

    assertThat(govState(applied, state).budgetPolicies().get(GOV_ID))
        .as("缺省 = 空类别表 + 0/0 工资（不自动付）")
        .isEqualTo(GovBudgetPolicy.neutral());
  }

  @Test
  void setBudgetPolicyIdempotentReplayIsNoopEmptyChangeSet() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState first = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState afterFirst =
        govState((HandlerOutcome.Applied) handler.handle(first, BUDGET_POLICY_PAYLOAD), first);
    SimulationState replayState = state(afterFirst, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied replay =
        (HandlerOutcome.Applied) handler.handle(replayState, BUDGET_POLICY_PAYLOAD);

    assertThat(changeSet(replay).isEmpty()).isTrue();
    assertThat(changeSet(replay).budgetPolicies()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(govState(replay, replayState)).isEqualTo(afterFirst);
  }

  @Test
  void setBudgetPolicyRejectsUnknownUnitBadCategoryDuplicateAndBadRange() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState state = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID))),
                        "{\"unitId\":\"gov-missing\"}"))
                .reason())
        .contains("GOV 单位不存在");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"orderedCategories\":[{\"category\":\"BRIBE\"}]}"))
                .reason())
        .contains("未知预算类别");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"orderedCategories\":["
                            + "{\"category\":\"ADMIN_STIPEND\"},{\"category\":\"ADMIN_STIPEND\"}]}"))
                .reason())
        .contains("类别不得重复");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"orderedCategories\":["
                            + "{\"category\":\"ADMIN_STIPEND\",\"minPerCycle\":10,\"capPerCycle\":5}]}"))
                .reason())
        .contains("minPerCycle 不得超过 capPerCycle");
    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(
                        state,
                        "{\"unitId\":\"gov-1\",\"officialSalaryRule\":{"
                            + "\"grainMilliPerCommittedHour\":-1}}"))
                .reason())
        .contains("grainMilliPerCommittedHour");
  }

  // ── ★ Z7e-3：PATCH/REPLACE 双模（控制方裁定 A+B）────────────────────────────────────

  @Test
  void setBudgetPolicyPatchKeepsOmittedFieldsRun7Regression() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState created = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState seeded =
        govState((HandlerOutcome.Applied) handler.handle(created, BUDGET_POLICY_PAYLOAD), created);
    SimulationState seededState = state(seeded, Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState withRate =
        govState(
            (HandlerOutcome.Applied)
                handler.handle(
                    seededState, "{\"unitId\":\"gov-1\",\"remittancePerMilleToSuperior\":500}"),
            seededState);
    SimulationState withRateState = state(withRate, Map.of(GOV_ID, govUnit(GOV_ID)));

    // run7 首跑污染同形：上缴 500→0（抗税）只传 remittance，PATCH 不得清空类别表/工资规则。
    HandlerOutcome.Applied patched =
        (HandlerOutcome.Applied)
            handler.handle(
                withRateState, "{\"unitId\":\"gov-1\",\"remittancePerMilleToSuperior\":0}");

    GovBudgetPolicy policy = govState(patched, withRateState).budgetPolicies().get(GOV_ID);
    assertThat(policy.orderedCategories())
        .as("PATCH：缺省字段保留现值（类别表不被清空）")
        .isEqualTo(EXPECTED_POLICY.orderedCategories());
    assertThat(policy.officialSalaryRule())
        .as("PATCH：工资规则逐值保留")
        .isEqualTo(EXPECTED_POLICY.officialSalaryRule());
    assertThat(policy.remittancePerMilleToSuperior()).isZero();
    assertThat(changeSet(patched).isEmpty()).isFalse();
  }

  @Test
  void setBudgetPolicyPatchClearsCategoriesOnlyWhenExplicitlyEmpty() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState created = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState seeded =
        govState((HandlerOutcome.Applied) handler.handle(created, BUDGET_POLICY_PAYLOAD), created);
    SimulationState seededState = state(seeded, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied cleared =
        (HandlerOutcome.Applied)
            handler.handle(seededState, "{\"unitId\":\"gov-1\",\"orderedCategories\":[]}");

    GovBudgetPolicy policy = govState(cleared, seededState).budgetPolicies().get(GOV_ID);
    assertThat(policy.orderedCategories()).as("显式 [] = 清空").isEmpty();
    assertThat(policy.officialSalaryRule())
        .as("同一载荷里的缺省字段仍保留")
        .isEqualTo(EXPECTED_POLICY.officialSalaryRule());
  }

  @Test
  void setBudgetPolicyPatchMergesSalaryRuleInnerFields() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState created = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState seeded =
        govState((HandlerOutcome.Applied) handler.handle(created, BUDGET_POLICY_PAYLOAD), created);
    SimulationState seededState = state(seeded, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied patched =
        (HandlerOutcome.Applied)
            handler.handle(
                seededState,
                "{\"unitId\":\"gov-1\",\"officialSalaryRule\":{"
                    + "\"silverMilliPerCommittedHour\":7}}");

    GovBudgetPolicy policy = govState(patched, seededState).budgetPolicies().get(GOV_ID);
    assertThat(policy.officialSalaryRule())
        .as("对象内缺省字段保留现值（10 保留、5→7）")
        .isEqualTo(new GovOfficialSalaryRule(10L, 7L));
    assertThat(policy.orderedCategories()).isEqualTo(EXPECTED_POLICY.orderedCategories());
  }

  @Test
  void setBudgetPolicyReplaceModeKeepsLegacyWholePayloadSemantics() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState created = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState seeded =
        govState((HandlerOutcome.Applied) handler.handle(created, BUDGET_POLICY_PAYLOAD), created);
    SimulationState seededState = state(seeded, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied replaced =
        (HandlerOutcome.Applied)
            handler.handle(
                seededState,
                "{\"unitId\":\"gov-1\",\"mode\":\"REPLACE\","
                    + "\"remittancePerMilleToSuperior\":500}");

    GovBudgetPolicy policy = govState(replaced, seededState).budgetPolicies().get(GOV_ID);
    assertThat(policy.orderedCategories()).as("REPLACE：缺省字段 = 空表（旧语义）").isEmpty();
    assertThat(policy.officialSalaryRule()).isEqualTo(GovOfficialSalaryRule.zero());
    assertThat(policy.remittancePerMilleToSuperior()).isEqualTo(500L);
  }

  @Test
  void setBudgetPolicyPatchOnFirstWriteFillsNeutralDefaults() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState fresh = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            handler.handle(fresh, "{\"unitId\":\"gov-1\",\"remittancePerMilleToSuperior\":500}");

    GovBudgetPolicy policy = govState(applied, fresh).budgetPolicies().get(GOV_ID);
    assertThat(policy.orderedCategories()).as("无现值可保留 ⇒ 等价中性默认").isEmpty();
    assertThat(policy.officialSalaryRule()).isEqualTo(GovOfficialSalaryRule.zero());
    assertThat(policy.remittancePerMilleToSuperior()).isEqualTo(500L);
  }

  @Test
  void setBudgetPolicyModeIsCaseInsensitiveAndRejectsUnknownVocabulary() {
    SetBudgetPolicyHandler handler = new SetBudgetPolicyHandler();
    SimulationState created = state(GovState.empty(), Map.of(GOV_ID, govUnit(GOV_ID)));
    GovState seeded =
        govState((HandlerOutcome.Applied) handler.handle(created, BUDGET_POLICY_PAYLOAD), created);
    SimulationState seededState = state(seeded, Map.of(GOV_ID, govUnit(GOV_ID)));

    HandlerOutcome lower =
        handler.handle(
            seededState,
            "{\"unitId\":\"gov-1\",\"mode\":\"patch\"," + "\"remittancePerMilleToSuperior\":250}");
    assertThat(lower).isInstanceOf(HandlerOutcome.Applied.class);
    GovBudgetPolicy patched =
        govState((HandlerOutcome.Applied) lower, seededState).budgetPolicies().get(GOV_ID);
    assertThat(patched.orderedCategories()).as("小写 patch 同样可识别").hasSize(3);
    assertThat(patched.remittancePerMilleToSuperior()).isEqualTo(250L);

    HandlerOutcome upper =
        handler.handle(seededState, "{\"unitId\":\"gov-1\",\"mode\":\"replace\"}");
    assertThat(govState((HandlerOutcome.Applied) upper, seededState).budgetPolicies().get(GOV_ID))
        .as("小写 replace 同样可识别")
        .isEqualTo(GovBudgetPolicy.neutral());

    assertThat(
            ((HandlerOutcome.Rejected)
                    handler.handle(seededState, "{\"unitId\":\"gov-1\",\"mode\":\"MERGE\"}"))
                .reason())
        .contains("mode")
        .contains("PATCH|REPLACE");
  }

  // ── GM-only / 切片装配故障 ────────────────────────────────────────────────────────

  @Test
  void bothCommandsAreGmOnlyAndCarryNoCommandTargets() {
    CommandHandler administrationPlan = new SetAdministrationPlanHandler();
    CommandHandler budgetPolicy = new SetBudgetPolicyHandler();

    assertThat(administrationPlan).isInstanceOf(GmOnlyCommand.class);
    assertThat(budgetPolicy).isInstanceOf(GmOnlyCommand.class);
    assertThat(administrationPlan)
        .as("不在决策令路径：不实现 CommandTargets")
        .isNotInstanceOf(CommandTargets.class);
    assertThat(budgetPolicy).isNotInstanceOf(CommandTargets.class);
    assertThat(administrationPlan.type()).isEqualTo(SetAdministrationPlanHandler.TYPE);
    assertThat(budgetPolicy.type()).isEqualTo(SetBudgetPolicyHandler.TYPE);
  }

  @Test
  void missingGovOrUnitSliceIsContractFailureNotRejection() {
    SimulationState noGov =
        new SimulationState(
            META,
            Map.of("unit", unitSnapshot(Map.of(GOV_ID, govUnit(GOV_ID)))),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () -> new SetAdministrationPlanHandler().handle(noGov, "{\"unitId\":\"gov-1\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gov 切片");

    SimulationState noUnit =
        new SimulationState(
            META, Map.of("gov", govSnapshot(GovState.empty())), InMemoryInfoSystem.empty());
    assertThatThrownBy(() -> new SetBudgetPolicyHandler().handle(noUnit, "{\"unitId\":\"gov-1\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unit 切片");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static SimulationState state(GovState gov, Map<UnitId, Unit> units) {
    return new SimulationState(
        META,
        Map.of("gov", govSnapshot(gov), "unit", unitSnapshot(units)),
        InMemoryInfoSystem.empty());
  }

  private static GovSnapshot govSnapshot(GovState gov) {
    return new GovSnapshot(REF, T0, gov);
  }

  private static UnitSnapshot unitSnapshot(Map<UnitId, Unit> units) {
    return new UnitSnapshot(REF, T0, new UnitState(new LinkedHashMap<>(units)));
  }

  /** 施加 handler 返回的变更集，返回新 gov 状态（铁律 5 的同一入口）。 */
  private static GovState govState(HandlerOutcome.Applied applied, SimulationState state) {
    GovSnapshot slice = (GovSnapshot) state.module("gov").orElseThrow();
    return GovChangeSet.apply(changeSet(applied), slice.state());
  }

  /** handler 的成功返回类型是 {@link ChangeSet} 接口，这里折回 gov 的具体变更集。 */
  private static GovChangeSet changeSet(HandlerOutcome.Applied applied) {
    return (GovChangeSet) applied.changeSet();
  }

  private static Unit govUnit(UnitId id) {
    return unit(
        id, Optional.of(governmentFormation()), List.of(GovernmentHouseholds.of(id.value())));
  }

  private static Unit armyUnit(UnitId id) {
    return unit(id, Optional.of(new ArmyFormation(Optional.empty(), "infantry")), List.of());
  }

  private static GovernmentFormation governmentFormation() {
    return new GovernmentFormation(
        Map.of(),
        Map.of(),
        new OfficePolicy(0L, 0L, 0L, 0L, Map.of()),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        Map.of());
  }

  private static Unit unit(UnitId id, Optional<UnitModule> module, List<HouseholdId> households) {
    return new Unit(
        id,
        id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<HexCoord>empty())), List.of(), null),
        List.of(),
        1,
        1000,
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
}
