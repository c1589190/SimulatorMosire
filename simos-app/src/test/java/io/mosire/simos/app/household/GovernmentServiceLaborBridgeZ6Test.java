package io.mosire.simos.app.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.StateRunner;
import io.mosire.simos.app.time.GovServiceFlowFeed;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovPostTier;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.gov.AdministrationCoefficients;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovEfficiencyModifier;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>Z6a 承诺→两维供给桥 / 单次计算 / 服务流量 / 动态修正 / staff 投影用例（Z3b §10.1）</b>。
 *
 * <p>逐值来源 = 设计书 §3 公式与 Z3b 台账 §2.2/§2.3/§3/§5/§6：
 *
 * <ul>
 *   <li>拆分 = {@code ⌊L × w_sec ÷ (w_sec+w_pap)⌋} + 余数归公文（Σ 不丢）；
 *   <li>无岗位 = 两维 0 + 具名 INFO（承诺行不动）；真契约故障 = ERROR + ISE；
 *   <li>流量三条恒等式由 {@link GovServiceFlow} 构造期守卫；feed 同 tick 才可读、重启即失；
 *   <li>动态修正由 app 参与者注入（GM 不可达），消费一次即清空。
 * </ul>
 */
class GovernmentServiceLaborBridgeZ6Test {

  private static final UnitId GOV = new UnitId("gov-1");
  private static final HouseholdId H1 = new HouseholdId("hh-1");
  private static final long DAY = 3L;

  /** office 模板 id（唯一拼写点）。 */
  private static final IndustryId OFFICE = new IndustryId("office@0_0");

  private final AppLogCapture timeLog = AppLogCapture.appTime();

  @AfterEach
  void closeLog() {
    timeLog.close();
  }

  // ── ① 单户拆分逐值 ──────────────────────────────────────────────────────────────────

  @Test
  void tierWeightsSplitEveryCommittedLaborMilli() {
    assertThat(
            supplyOf(commitment("alloc-1", H1, 16001L), post(H1, "t1"), tiers(tier("t1", 1000, 0))))
        .as("(1000,0)：全治安")
        .isEqualTo(new long[] {16001L, 0L});
    assertThat(
            supplyOf(commitment("alloc-1", H1, 16001L), post(H1, "t1"), tiers(tier("t1", 0, 1000))))
        .as("(0,1000)：全公文")
        .isEqualTo(new long[] {0L, 16001L});
    assertThat(
            supplyOf(
                commitment("alloc-1", H1, 16001L), post(H1, "t1"), tiers(tier("t1", 500, 500))))
        .as("奇数 16001 ⇒ (8000,8001)，余数归公文")
        .isEqualTo(new long[] {8000L, 8001L});
    assertThat(
            supplyOf(
                commitment("alloc-1", H1, 16001L), post(H1, "t1"), tiers(tier("t1", 333, 667))))
        .as("自定义权重 333/667 ⇒ ⌊16001×333÷1000⌋ = 5328，余 10673")
        .isEqualTo(new long[] {5328L, 10673L});
  }

  @Test
  void emptyCommitmentsYieldZeroSupply() {
    Fixture fixture = economy(List.of(serviceUnit("u1")), List.<HouseholdLaborCommitment>of());
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers(tier("t1", 500, 500)))),
            fixture.productionUnits(),
            GOV,
            formation(Map.of(H1, post(H1, "t1")), Map.of()),
            DAY);
    assertThat(supply.securityLaborMilli()).isZero();
    assertThat(supply.paperworkLaborMilli()).isZero();
  }

  @Test
  void multipleCommitmentsAndUnitsAreSummedBeforeSplit() {
    // 同一 GOV 的两个 service unit、同一户两条承诺 10000 + 6001 ⇒ 先加总 16001 再按 500/500 拆。
    ProductionProcess u1 = serviceUnit("u1");
    ProductionProcess u2 = serviceUnit("u2");
    HouseholdLaborCommitment a = commitmentOn("alloc-1", H1, 10_000L, u1.id().value());
    HouseholdLaborCommitment b = commitmentOn("alloc-2", H1, 6_001L, u2.id().value());
    Fixture fixture = economy(List.of(u1, u2), List.of(a, b));

    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers(tier("t1", 500, 500)))),
            fixture.productionUnits(),
            GOV,
            formation(Map.of(H1, post(H1, "t1")), Map.of()),
            DAY);
    assertThat(supply.securityLaborMilli()).isEqualTo(8000L);
    assertThat(supply.paperworkLaborMilli()).isEqualTo(8001L);
    assertThat(
            GovernmentServiceLaborBridge.committedLaborByHousehold(
                    fixture.economy(), fixture.productionUnits(), GOV, DAY)
                .get(H1))
        .as("逐户先加总 = 16001")
        .isEqualTo(16001L);
  }

  /**
   * ★★ Z7d-1：effective = {@code min(承诺, 该户当前实际劳动)}。旧 5 参入口读 {@code economy.classes()}（基态）；推进入口读
   * {@code currentHouseholdRows}（会话工作副本）——两处都必须按户 cap，且 committed 两维仍是不 cap 的职位口径。
   */
  @Test
  void supplyCapsEffectiveLaborByHouseholdRowAndKeepsCommittedUncapped() {
    Fixture fixture =
        economyWithHouseholdLabor(
            List.of(serviceUnit("u1")), List.of(commitment("alloc-1", H1, 16_001L)), 10_000L);

    GovernmentServiceLaborBridge.Supply fromBase =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers(tier("t1", 500, 500)))),
            fixture.productionUnits(),
            GOV,
            formation(Map.of(H1, post(H1, "t1")), Map.of()),
            DAY);
    assertThat(fromBase.securityLaborMilli()).as("10000 × 500/1000").isEqualTo(5_000L);
    assertThat(fromBase.paperworkLaborMilli()).isEqualTo(5_000L);
    assertThat(fromBase.committedSecurityLaborMilli()).as("16001 × 500/1000").isEqualTo(8_000L);
    assertThat(fromBase.committedPaperworkLaborMilli()).as("余数归公文").isEqualTo(8_001L);
    assertThat(fromBase.underfedHouseholds()).isEqualTo(1L);

    // 推进中的 6 参重载：实际劳动来自当 tick 的会话工作副本（可低于基态行）。
    HouseholdId govHousehold = GovernmentHouseholds.of(GOV.value());
    GovernmentServiceLaborBridge.Supply fromWorkingCopy =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers(tier("t1", 500, 500)))),
            fixture.productionUnits(),
            Map.of(H1, householdRow(H1, 8_000L), govHousehold, householdRow(govHousehold)),
            GOV,
            formation(Map.of(H1, post(H1, "t1")), Map.of()),
            DAY);
    assertThat(fromWorkingCopy.securityLaborMilli()).as("8000 × 500/1000").isEqualTo(4_000L);
    assertThat(fromWorkingCopy.paperworkLaborMilli()).isEqualTo(4_000L);
    assertThat(fromWorkingCopy.committedSecurityLaborMilli()).as("职位口径不 cap").isEqualTo(8_000L);
    assertThat(fromWorkingCopy.committedPaperworkLaborMilli()).isEqualTo(8_001L);
    assertThat(fromWorkingCopy.underfedHouseholds()).isEqualTo(1L);
  }

  @Test
  void legacyEmptyTierFallsBackToRoleDimension() {
    // 空 tierId（旧档/未指派）：YAMEN → 治安；SCRIBE/POST → 公文（GovEfficiency 旧桥口径）。
    assertThat(
            supplyOf(
                commitment("alloc-1", H1, 16001L),
                new GovernmentPostOfHousehold(H1, StaffRole.YAMEN, GovernmentLevel.CENTRAL, false),
                tiers(tier("t1", 500, 500))))
        .isEqualTo(new long[] {16001L, 0L});
    assertThat(
            supplyOf(
                commitment("alloc-1", H1, 16001L),
                new GovernmentPostOfHousehold(H1, StaffRole.POST, GovernmentLevel.CENTRAL, false),
                tiers(tier("t1", 500, 500))))
        .isEqualTo(new long[] {0L, 16001L});
  }

  // ── ② 无岗位 + ③ 契约故障 ───────────────────────────────────────────────────────────

  @Test
  void commitmentWithoutPostYieldsZeroSupplyWithNamedInfoAndKeepsTheCommitment() {
    timeLog.clear();
    HouseholdLaborCommitment commitment = commitment("alloc-1", H1, 16001L);
    Fixture fixture = economy(List.of(serviceUnit("u1")), List.of(commitment));
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers(tier("t1", 500, 500)))),
            fixture.productionUnits(),
            GOV,
            formation(Map.of(), Map.of()),
            DAY);

    assertThat(supply.securityLaborMilli()).isZero();
    assertThat(supply.paperworkLaborMilli()).isZero();
    assertThat(
            timeLog.hasInfo(
                "GOV_SERVICE_COMMITMENT_WITHOUT_POST",
                "unit=gov-1",
                "count=1",
                "first=hh-1 laborMilli=16001"))
        .as("无岗位必须具名 INFO（count/first）；实得 %s", timeLog.messages())
        .isTrue();
    assertThat(fixture.economy().allocations()).hasSize(1);
    assertThat(fixture.economy().allocations().values().iterator().next().laborMilli())
        .as("承诺行仍在且不改量（C7 不可缩）")
        .isEqualTo(16001L);
  }

  @Test
  void contractFailuresAreNamedErrorAndIse() {
    // (a) tierId 非空但不在 postTiers
    assertContractFailure(
        "tier-unknown",
        commitment("alloc-1", H1, 100L),
        post(H1, "missing-tier"),
        tiers(tier("t1", 500, 500)));
    // (b) 档位权重合计 0
    assertContractFailure(
        "tier-zero-weight",
        commitment("alloc-1", H1, 100L),
        post(H1, "t1"),
        tiers(tier("t1", 0, 0)));
    // (c) 桥内算术溢出：L = 100000（合法：= 家户劳动预算）× 权重 Long.MAX_VALUE ⇒ multiplyExact 溢出。
    assertContractFailure(
        "arithmetic-overflow",
        commitment("alloc-1", H1, 100_000L),
        post(H1, "t1"),
        tiers(tier("t1", Long.MAX_VALUE, 0L)));
    // (d) commitment.actor != unit.operator：★★ D-032 T1 **判据换落点（不是放宽）**——
    //     旧形状：economy 侧持有 unit 表 ⇒ `withProcesses` 在构造期把 actor **静默对齐**到 activity 指名的
    //       unit.operator，于是桥内 "actor-mismatch" ERROR 防御位经公开构造器不可达。
    //     新形状（B2b 裁定六 + B4 §3.2）：unit 表已迁 production ⇒ economy 侧**再没有可对齐的参照物**，
    //       承诺行按其字面值落账；"两处拼写必须一致"这条守卫**没有消失**，而是升格为**候选态 fail-closed 拒绝**：
    //       `EconomyReferenceValidation.validateUnitCrossReferences`（`:300-330`：命中实例时 actor 必须等于
    //       unit.operator，否则 IllegalArgumentException，命令层折 Rejected、不留 revision）——比旧的静默对齐**更严**。
    //     ⇒ 本处断言"按原样落账（无 economy 侧对齐）"，并保留原判据的结论面："该不一致经公开构造器不会被改写成一致"。
    HouseholdLaborCommitment wrongActor =
        new HouseholdLaborCommitment(
            LaborAllocationId.parse("alloc-1"),
            new PeopleLotId("lot-1"),
            H1,
            HouseholdActors.of(new HouseholdId("hh-other")),
            serviceUnit("u1").id().value(),
            100L,
            0L,
            LaborCommitmentKind.GOV_SERVICE);
    Fixture mismatched = economy(List.of(serviceUnit("u1")), List.of(wrongActor));
    assertThat(mismatched.economy().allocations().values().iterator().next().actor())
        .as("economy 侧不再有 unit 表 ⇒ 不对齐（不一致由候选态守卫拒，不在这里被悄悄改写成一致）")
        .isEqualTo(HouseholdActors.of(new HouseholdId("hh-other")));
    // ★ 该不一致现在必须指向一条**真实存在的实例**才有主语（实例权威在 production）。
    assertThat(mismatched.productionUnits().values().iterator().next().operator())
        .as("实例侧的 operator 仍是 GOV 家户 ⇒ 上一条的'不一致'不是空转")
        .isEqualTo(HouseholdActors.of(GovernmentHouseholds.of(GOV.value())));
  }

  // ── ④ 单次计算（GovDaily 缺效率表）────────────────────────────────────────────────────

  @Test
  void govDailyWithoutEfficiencyMapFailsClosedByName() {
    try (AppLogCapture govLog = AppLogCapture.logger("io.mosire.simos.gov.daily")) {
      GovState gov = new GovState(Map.of(GOV, GovOfficeState.empty(GOV, 0L)));
      UnitState units = oneGovUnit();
      assertThatThrownBy(
              () ->
                  GovDaily.settle(
                      gov,
                      units,
                      1L,
                      365L,
                      Map.<UnitId, GovEfficiency.Efficiency>of(),
                      (unitId, at, resource, requested, day) -> 0L))
          .as("缺当日效率 = 契约故障 ⇒ ISE（不重算、不降级）")
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              govLog.hasError(
                  "GOV_DAILY_EFFICIENCY_MISSING",
                  "reason=missing-efficiency",
                  "day=1",
                  "unit=gov-1"))
          .as("必须具名 ERROR 行；实得 %s", govLog.messages())
          .isTrue();
    }
  }

  // ── ⑤ 推进入口：零供给 INFO / 动态修正注入 ──────────────────────────────────────────────

  @Test
  void zeroSupplyDuringAdvanceIsNamedInfoAndEfficiencyZero() {
    SimulationState base = GovZ6WorldFixture.smallWorldState("Map1");
    EconomyData noCommitments = ecosystem(base).withLaborCommitments(Map.of());
    SimulationState stripped =
        GovZ6WorldFixture.withModule(
            base,
            "economy",
            new EconomySnapshot(GovZ6WorldFixture.ref(1L), base.meta().timestamp(), noCommitments));

    timeLog.clear();
    StateRunner runner = new StateRunner("Map1", stripped);
    runner.advance(0L, 1L);

    assertThat(
            timeLog.hasInfo(
                "GOV_EFFICIENCY_ZERO_SUPPLY",
                "unit=gov-central",
                "reason=no-posted-household-commitment"))
        .as("任一维供给 0 ⇒ 具名 INFO（不是静默 0）；实得 %s", timeLog.messages())
        .isTrue();
    GovState gov = GovZ6WorldFixture.govSlice(runner.state());
    for (UnitId govId : List.of(UnitId.parse("gov-central"), UnitId.parse("gov-province"))) {
      GovOfficeState office = gov.offices().get(govId);
      assertThat(office.efficiencyPerMille()).as(govId.value() + " 无承诺 ⇒ 总效率 0").isZero();
      assertThat(office.securityEfficiencyPerMille()).isZero();
      assertThat(office.paperworkEfficiencyPerMille()).isZero();
    }
  }

  @Test
  void dynamicModifiersInjectConsumeThenReturnToNeutral() {
    StateRunner runner = new StateRunner("Map1", GovZ6WorldFixture.smallWorldState("Map1"));
    runner.advance(0L, 1L);
    assertThat(office(runner, "gov-central").efficiencyPerMille())
        .as("未注入 ⇒ 中性 1000‰")
        .isEqualTo(1000L);

    // ★ 真「全不封顶」（C3；BLOCKED-1 修复后）：治安供给 ×2、公文供给 ×3 ⇒
    //   两维效率 2000/3000，总效率 = 2000 × 3000 ÷ 1000 = 6000‰，且日推进（含税路径）正常完成。
    GovEfficiencyModifier boost =
        new GovEfficiencyModifier(
            UnitId.parse("gov-central"), 2000L, 3000L, 1000L, 1000L, "z6a-test", "boost");
    runner.inject(List.of(boost));
    runner.advance(1L, 2L);
    GovOfficeState central = office(runner, "gov-central");
    assertThat(central.securityCoveragePerMille()).isEqualTo(1000L);
    assertThat(central.paperworkCoveragePerMille()).isEqualTo(1000L);
    assertThat(central.securityEfficiencyPerMille()).isEqualTo(2000L);
    assertThat(central.paperworkEfficiencyPerMille()).isEqualTo(3000L);
    assertThat(central.efficiencyPerMille()).as("两维相乘 ÷1000 = 6000，不封顶").isEqualTo(6000L);
    assertThat(office(runner, "gov-province").efficiencyPerMille())
        .as("未注入 GOV 保持中性")
        .isEqualTo(250L);

    // 消费一次即清空：再推进一天回中性（不需要显式清空）。
    runner.advance(2L, 3L);
    assertThat(office(runner, "gov-central").efficiencyPerMille())
        .as("动态修正只影响注入后的首日")
        .isEqualTo(1000L);

    // 多日一次推进：只影响首日；终态（第 5 天）回中性。
    runner.inject(List.of(boost));
    runner.advance(3L, 5L);
    assertThat(office(runner, "gov-central").efficiencyPerMille())
        .as("多日推进只影响首日 ⇒ 终态中性")
        .isEqualTo(1000L);

    // 替换语义 + 具名拒绝（null / 重复 / 未知 GOV）。
    timeLog.clear();
    assertThatThrownBy(() -> runner.inject(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> runner.inject(List.of(boost, GovEfficiencyModifier.neutral(GOV, "x", "y"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                runner.inject(
                    List.of(GovEfficiencyModifier.neutral(new UnitId("gov-unknown"), "x", "y"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(timeLog.hasInfo("GOV_EFFICIENCY_MODIFIER_REJECTED", "reason=null-modifier-list"))
        .as("三类拒绝都必须具名 INFO；实得 %s", timeLog.messages())
        .isTrue();

    // 替换：A 注入后立刻被 B 替换 ⇒ 消费到的是 B（供给修正 600）。
    runner.inject(List.of(boost));
    runner.inject(
        List.of(
            new GovEfficiencyModifier(
                UnitId.parse("gov-central"), 600L, 1000L, 1000L, 1000L, "z6a-test", "replaced")));
    runner.advance(5L, 6L);
    assertThat(office(runner, "gov-central").efficiencyPerMille())
        .as("替换后消费到 B：1000 × 600 ÷ 1000 = 600")
        .isEqualTo(600L);
  }

  // ── ⑥ 服务流量：恒等式 + feed 语义 ────────────────────────────────────────────────────

  @Test
  void govServiceFlowIdentitiesAndValuesMatchEfficiency() {
    GovernmentFormation formation = formation(Map.of(), Map.of());
    // 超编：计划 32000、供给 96000、k=1、定额 16000 ⇒ 超额 64000/16000=4、⌊√4⌋=2 ⇒ 有效 64000。
    GovEfficiency.Efficiency over =
        GovEfficiency.of(
            formation,
            Map.of(),
            coefficients(plan2(32_000L, 32_000L)),
            96_000L,
            96_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovServiceFlow flow = GovServiceFlow.of(GOV, 7L, 96_000L, 96_000L, over);
    assertThat(flow.securityEffectiveLaborMilli()).isEqualTo(64_000L);
    assertThat(flow.securityServiceOutputMilli()).isEqualTo(64_000L);
    assertThat(flow.securityConsumedByEfficiencyMilli()).as("消费 = min(产出, 需求)").isEqualTo(32_000L);
    assertThat(flow.securityExpiredUnusedMilli()).as("失效 = 产出 − 消费").isEqualTo(32_000L);
    assertThat(over.efficiencyPerMille()).as("两维各 2000‰ ⇒ 总 4000‰").isEqualTo(4000L);

    // 供给 ≤ 需求：有效 = 承诺，消费 = 有效，失效 0。
    GovEfficiency.Efficiency under =
        GovEfficiency.of(
            formation,
            Map.of(),
            coefficients(plan2(32_000L, 32_000L)),
            16_000L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovServiceFlow underFlow = GovServiceFlow.of(GOV, 8L, 16_000L, 16_000L, under);
    assertThat(underFlow.securityEffectiveLaborMilli()).isEqualTo(16_000L);
    assertThat(underFlow.securityConsumedByEfficiencyMilli()).isEqualTo(16_000L);
    assertThat(underFlow.securityExpiredUnusedMilli()).isZero();

    // 需求 0 + 供给 > 0：有效 = 开方后产出，消费 0，失效 = 产出。
    GovEfficiency.Efficiency noDemand =
        GovEfficiency.of(
            formation,
            Map.of(),
            coefficients(plan2(0L, 0L)),
            16_000L,
            16_000L,
            1000L,
            1000L,
            1000L,
            1000L,
            16_000L);
    GovServiceFlow noDemandFlow = GovServiceFlow.of(GOV, 9L, 16_000L, 16_000L, noDemand);
    assertThat(noDemandFlow.securityEffectiveLaborMilli()).isEqualTo(16_000L);
    assertThat(noDemandFlow.securityConsumedByEfficiencyMilli()).isZero();
    assertThat(noDemandFlow.securityExpiredUnusedMilli()).isEqualTo(16_000L);
  }

  @Test
  void govServiceFlowFeedIsSameTickOnlyAndLosesDataOnClear() {
    String mapId = "z6a-feed";
    GovServiceFlowFeed.clear(mapId);
    assertThat(GovServiceFlowFeed.last(mapId, 5L)).as("没有投递 ⇒ unavailable").isEmpty();

    GovServiceFlow flow =
        new GovServiceFlow(GOV, 5L, 10L, 20L, 10L, 20L, 0L, 0L, 10L, 20L, 0L, 0L, 10L, 20L);
    GovServiceFlowFeed.publish(mapId, Map.of(GOV, flow), 5L);
    assertThat(GovServiceFlowFeed.last(mapId, 5L)).as("同 tick 可读").contains(Map.of(GOV, flow));
    assertThat(GovServiceFlowFeed.last(mapId, 6L))
        .as("换 tick ⇒ unavailable（不把旧 tick 当本 tick）")
        .isEmpty();

    // 空表是合法投递（这个世界本 tick 没有 GOV 流量），读口看到空 map，而不是"有数据的 0"。
    GovServiceFlowFeed.publish(mapId, Map.of(), 7L);
    assertThat(GovServiceFlowFeed.last(mapId, 7L)).contains(Map.of());

    // 重启 / 收工 = clear ⇒ 读不到（绝不填 0）。
    GovServiceFlowFeed.clear(mapId);
    assertThat(GovServiceFlowFeed.last(mapId, 7L)).isEmpty();
    assertThat(GovServiceFlowFeed.UNAVAILABLE_REASON)
        .isEqualTo("gov-service-flow-unavailable-for-tick");
  }

  // ── ⑦ staff 投影 ────────────────────────────────────────────────────────────────────

  @Test
  void staffProjectionUsesCommitmentsAndMismatchIsDailyInfoNotWarn() {
    SimulationState genesis = GovZ6WorldFixture.smallWorldState("Map1");
    // ★★ D-032 T1：`staffHouseholdProjection/staffProjectionMismatches` 新增第 2 形参 `productionUnits`
    //   （B2b 裁定六：实例权威迁 production）。★ **必须传真创世的实例投影，不能填 `Map.of()`** ——
    //   投影就是按 production 实例的 operator 找 GOV 服务 unit，空表会让"32000 ÷ 16000 = 2"这条变成空转。
    Map<ProductionUnitId, ProductionProcess> productionUnits = productionInstances(genesis);
    Map<String, Long> projection =
        HouseholdUnitConsistency.staffHouseholdProjection(
            GovZ6WorldFixture.economySlice(genesis),
            productionUnits,
            GovZ6WorldFixture.socialSlice(genesis),
            GovZ6WorldFixture.unitSlice(genesis),
            0L);
    assertThat(projection)
        .as("posts + 承诺 ⇒ 逐 GOV 投影 = 32000 ÷ 16000 = 2 人当量")
        .containsEntry("gov-central:POST", 2L)
        .containsEntry("gov-province:POST", 2L);
    assertThat(
            HouseholdUnitConsistency.staffProjectionMismatches(
                GovZ6WorldFixture.economySlice(genesis),
                productionUnits,
                GovZ6WorldFixture.socialSlice(genesis),
                GovZ6WorldFixture.unitSlice(genesis),
                0L))
        .as("stored staff == 投影 ⇒ 无 mismatch")
        .isEmpty();

    // 篡改 central stored staff 为 99（posts/承诺不动）⇒ 投影仍 2、mismatch 具名；推进当日 INFO 一条、不得 WARN。
    SimulationState mutated = withCentralStoredStaff(genesis, 99L);
    assertThat(
            HouseholdUnitConsistency.staffProjectionMismatches(
                GovZ6WorldFixture.economySlice(mutated),
                productionInstances(mutated),
                GovZ6WorldFixture.socialSlice(mutated),
                GovZ6WorldFixture.unitSlice(mutated),
                0L))
        .singleElement()
        .asString()
        .contains("unit=gov-central")
        .contains("stored={POST=99}")
        .contains("projected={POST=2}");

    timeLog.clear();
    StateRunner runner = new StateRunner("Map1", mutated);
    runner.advance(0L, 1L);
    assertThat(
            timeLog.hasInfo(
                "GOV_STAFF_PROJECTION_MISMATCH",
                "day=0",
                "count=1",
                "first=unit=gov-central stored={POST=99} projected={POST=2}"))
        .as("每日聚合一条 INFO；实得 %s", timeLog.messages())
        .isTrue();
    assertThat(timeLog.messages())
        .as("staff 投影不一致是过渡态 ⇒ 不得出现 WARN 级该事件")
        .noneMatch(
            line -> line.startsWith("WARN|") && line.contains("GOV_STAFF_PROJECTION_MISMATCH"));
    assertThat(timeLog.hasInfo("GOV_STAFF_HOUSEHOLD_PROJECTION", "day=0")).as("投影事件同轮可见").isTrue();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private long[] supplyOf(
      HouseholdLaborCommitment commitment,
      GovernmentPostOfHousehold post,
      List<GovPostTier> tiers) {
    Fixture fixture = economy(List.of(serviceUnit("u1")), List.of(commitment));
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            withPlan(fixture.economy(), plan(tiers)),
            fixture.productionUnits(),
            GOV,
            formation(Map.of(post.householdId(), post), Map.of()),
            DAY);
    return new long[] {supply.securityLaborMilli(), supply.paperworkLaborMilli()};
  }

  private void assertContractFailure(
      String reason,
      HouseholdLaborCommitment commitment,
      GovernmentPostOfHousehold post,
      List<GovPostTier> tiers) {
    timeLog.clear();
    Fixture fixture = economy(List.of(serviceUnit("u1")), List.of(commitment));
    assertThatThrownBy(
            () ->
                GovernmentServiceLaborBridge.supply(
                    withPlan(fixture.economy(), plan(tiers)),
                    fixture.productionUnits(),
                    GOV,
                    formation(Map.of(post.householdId(), post), Map.of()),
                    DAY))
        .as("契约故障 reason=%s ⇒ ISE", reason)
        .isInstanceOf(IllegalStateException.class);
    assertThat(timeLog.hasError("GOV_SERVICE_SUPPLY_CONTRACT_VIOLATION", "reason=" + reason))
        .as("必须先落 ERROR 证据（reason=%s）；实得 %s", reason, timeLog.messages())
        .isTrue();
  }

  private static EconomyData ecosystem(SimulationState state) {
    return GovZ6WorldFixture.economySlice(state);
  }

  /**
   * ★★ D-032 T1：**生产实例**的当刻真值（跨模块测试只能经 production 切片读 —— 实例权威在 production）。形态同 {@code
   * GovZ6WorldFixture} 的 {@code economySlice/unitSlice}，只是 production 侧没有共享助手。
   */
  private static Map<ProductionUnitId, ProductionProcess> productionInstances(
      SimulationState state) {
    return ((io.mosire.simos.production.ProductionSnapshot)
            state.module("production").orElseThrow())
        .state()
        .instances();
  }

  private static GovOfficeState office(StateRunner runner, String govId) {
    return GovZ6WorldFixture.govSlice(runner.state()).offices().get(UnitId.parse(govId));
  }

  private static SimulationState withCentralStoredStaff(SimulationState state, long postStaff) {
    UnitState units = GovZ6WorldFixture.unitSlice(state);
    UnitId central = UnitId.parse("gov-central");
    Unit base = units.units().get(central);
    GovernmentFormation formation = (GovernmentFormation) base.module().orElseThrow();
    GovernmentFormation mutated =
        new GovernmentFormation(
            Map.of(StaffRole.POST, postStaff),
            formation.governmentPostsOfHousehold(),
            formation.policy(),
            formation.superiorGov(),
            formation.level(),
            formation.externalPosts());
    Unit replaced =
        new Unit(
            base.id(),
            base.name(),
            base.parent(),
            base.position(),
            base.equipment(),
            base.speed(),
            base.mobilityPerMille(),
            base.movement(),
            base.status(),
            base.attached(),
            base.offset(),
            base.rejoinTarget(),
            base.visionRadius(),
            base.jurisdiction(),
            Optional.of(mutated),
            base.stateDescriptions(),
            base.households());
    Map<UnitId, Unit> byId = new LinkedHashMap<>(units.units());
    byId.put(central, replaced);
    return GovZ6WorldFixture.withModule(
        state,
        "unit",
        new io.mosire.simos.unit.UnitSnapshot(
            state.meta().ref(), state.meta().timestamp(), new UnitState(byId)));
  }

  private static ProductionProcess serviceUnit(String suffix) {
    return new ProductionProcess(
        new ProductionUnitId("unit-" + suffix),
        new IndustryId("office@0_0"),
        io.mosire.simos.production.contract.ProductionLocation.ofPlan(new IndustryId("office@0_0"))
            .orElseThrow(),
        HouseholdActors.of(GovernmentHouseholds.of(GOV.value())),
        "gov_service",
        0L,
        Map.of() /* ★ D-032 T1：cycleContributions（旧 long 周期劳动已替换；字面 0 ⇒ 空表逐值等价）*/,
        Map.of());
  }

  /**
   * ★★ D-032 T1：本文件的夹具是**两半** —— economy 切片 + **production 实例投影**。
   *
   * <p>为什么必须是两半：生产实例的唯一权威已按 B2b（裁定六）迁到 production（{@code EconomyData.units()} 与 {@code
   * withProcesses(...)} 都已删除）⇒ 旧夹具"把 unit 挂在 economy 上"这件事在新形状里不存在。 ★ **不能给桥传 {@code
   * Map.of()}**：桥就是靠实例投影解析 GOV 服务 unit（operator/activity 的对账）， 空表会让"多 unit 求和 / activity
   * 缺省解析"这些判据变成空转。
   */
  private record Fixture(
      EconomyData economy, Map<ProductionUnitId, ProductionProcess> productionUnits) {}

  private static Fixture economy(
      List<ProductionProcess> units, List<HouseholdLaborCommitment> commitments) {
    return economyWithHouseholdLabor(units, commitments, 100_000L);
  }

  /** 同 {@link #economy}，但把目标家户 H1 的 Economy 行 laborMilli 显式设为给定值（cap 判别力）。 */
  private static Fixture economyWithHouseholdLabor(
      List<ProductionProcess> units,
      List<HouseholdLaborCommitment> commitments,
      long householdLabor) {
    Map<ProductionUnitId, ProductionProcess> unitMap = new LinkedHashMap<>();
    for (ProductionProcess unit : units) {
      unitMap.put(unit.id(), unit);
    }
    Map<LaborAllocationId, HouseholdLaborCommitment> allocationMap = new LinkedHashMap<>();
    for (HouseholdLaborCommitment commitment : commitments) {
      allocationMap.put(commitment.id(), commitment);
    }
    HouseholdId governmentHousehold = GovernmentHouseholds.of(GOV.value());
    EconomyData economy =
        EconomyData.empty()
            .withHouseholdEconomies(
                Map.of(
                    H1, householdRow(H1, householdLabor),
                    governmentHousehold, householdRow(governmentHousehold)))
            .withLaborCommitments(allocationMap);
    // ★★ D-032 T1：`withProcesses(unitMap)` 已删除（方法类型已按 B2b 删除）——实例改由 production 侧承载。
    return new Fixture(economy, unitMap);
  }

  private static HouseholdLaborCommitment commitment(
      String id, HouseholdId household, long laborMilli) {
    return commitmentOn(id, household, laborMilli, "unit-u1");
  }

  private static HouseholdLaborCommitment commitmentOn(
      String id, HouseholdId household, long laborMilli, String activity) {
    return new HouseholdLaborCommitment(
        LaborAllocationId.parse(id),
        new PeopleLotId("lot-" + id),
        household,
        HouseholdActors.of(GovernmentHouseholds.of(GOV.value())),
        activity,
        laborMilli,
        0L,
        LaborCommitmentKind.GOV_SERVICE);
  }

  private static GovernmentPostOfHousehold post(HouseholdId household, String tierId) {
    return new GovernmentPostOfHousehold(
        household, StaffRole.POST, GovernmentLevel.CENTRAL, false, tierId);
  }

  private static GovernmentFormation formation(
      Map<HouseholdId, GovernmentPostOfHousehold> posts,
      Map<HouseholdId, GovernmentPostOfHousehold> externalPosts) {
    return new GovernmentFormation(
        Map.of(),
        posts,
        OfficePolicy.defaults(),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        externalPosts);
  }

  private static GovPostTier tier(String tierId, long security, long paperwork) {
    return new GovPostTier(tierId, security, paperwork);
  }

  private static List<GovPostTier> tiers(GovPostTier... tiers) {
    return List.of(tiers);
  }

  private static GovAdministrationPlan plan(List<GovPostTier> tiers) {
    // 计划冻结"恰 3 档"：单档测试补两档占位（不影响被测档位的权重/归一化拆分）。
    java.util.List<GovPostTier> padded = new java.util.ArrayList<>(tiers);
    if (padded.size() == 1) {
      padded.add(new GovPostTier("z6a-pad-2", 0L, 1000L));
      padded.add(new GovPostTier("z6a-pad-3", 1000L, 0L));
    }
    return plan2(0L, 0L, List.copyOf(padded));
  }

  private static io.mosire.simos.economy.model.HouseholdEconomy householdRow(HouseholdId id) {
    return householdRow(id, 100_000L);
  }

  private static io.mosire.simos.economy.model.HouseholdEconomy householdRow(
      HouseholdId id, long laborMilli) {
    return new io.mosire.simos.economy.model.HouseholdEconomy(
        id,
        new io.mosire.simos.economy.api.cohort.CohortKey(
            new HexCoord(0, 0),
            io.mosire.simos.economy.api.cohort.ResidenceKind.RURAL,
            io.mosire.simos.economy.api.id.SocialClassId.POOR_PEASANT),
        0L,
        laborMilli,
        1000,
        0L,
        Map.of(),
        Map.of(),
        0L);
  }

  private static GovAdministrationPlan plan2(long security, long paperwork) {
    return plan2(security, paperwork, GovAdministrationPlan.DEFAULT_POST_TIERS);
  }

  private static GovAdministrationPlan plan2(
      long security, long paperwork, List<GovPostTier> tiers) {
    return new GovAdministrationPlan(
        security,
        paperwork,
        tiers,
        1000L,
        1000L,
        1000L,
        1000L,
        GovAdministrationPlan.DEFAULT_SUPERNUMERARY_SQRT_COEFFICIENT);
  }

  /**
   * ★★ <b>编制计划 → {@code GovEfficiency} 的纯值入参</b>（追加裁定 12 §21.9 末段选 (a)：{@code simos-gov} 不许 import
   * economy ⇒ 组合根把 {@code Government.administrationPlan()} 逐字段折成本类型，gov 侧只吃纯值）。
   *
   * <p>映射与生产调用点（{@code PopulationEconomyTimeParticipant}）<b>逐字段同形</b>：字段顺序 = 该记录组件顺序。
   */
  private static AdministrationCoefficients coefficients(GovAdministrationPlan plan) {
    return AdministrationCoefficients.of(
        plan.securityPlannedLaborMilli(),
        plan.paperworkPlannedLaborMilli(),
        plan.portPlannedLaborMilli(),
        plan.securitySupplyStaticModifierPerMille(),
        plan.paperworkSupplyStaticModifierPerMille(),
        plan.portSupplyStaticModifierPerMille(),
        plan.securityDemandStaticModifierPerMille(),
        plan.paperworkDemandStaticModifierPerMille(),
        plan.portDemandStaticModifierPerMille(),
        plan.supernumerarySqrtCoefficient());
  }

  /**
   * ★★ <b>把编制计划写进 economy 的政府记录</b>（追加裁定 12 §21.9 D-1：档位目录的唯一权威 = {@code EconomyData.governments} 的
   * {@code Government.administrationPlan()}；本夹具世界还没登记 u-1 政府 ⇒ 先补一条中性记录）。
   */
  private static EconomyData withPlan(EconomyData economy, GovAdministrationPlan plan) {
    GovernmentId id = GovernmentIds.ofUnit(GOV.value());
    Government government =
        economy
            .governments()
            .getOrDefault(
                id,
                new Government(
                    id,
                    GOV.value(),
                    HouseholdActors.of(GovernmentHouseholds.of(GOV.value())),
                    Set.of()));
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(id, government.withAdministrationPlan(plan));
    return economy.withGovernments(governments);
  }

  private static UnitState oneGovUnit() {
    HouseholdId govHousehold = GovernmentHouseholds.of(GOV.value());
    Unit unit =
        new Unit(
            GOV,
            "gov-1",
            series(Optional.<UnitId>empty()),
            series(Optional.of(new HexCoord(0, 0))),
            List.of(),
            1,
            1000,
            Optional.empty(),
            UnitStatus.MOVING,
            series(true),
            series(Optional.<RelativeOffset>empty()),
            Optional.empty(),
            Unit.DEFAULT_VISION_RADIUS,
            Optional.empty(),
            Optional.of(
                new GovernmentFormation(
                    Map.of(),
                    Map.of(
                        govHousehold,
                        new GovernmentPostOfHousehold(
                            govHousehold, StaffRole.POST, GovernmentLevel.CENTRAL, false)),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of())),
            Map.of(),
            List.of(govHousehold));
    return new UnitState(Map.of(GOV, unit));
  }

  private static <T> SegmentedSeries<T> series(T value) {
    return new SegmentedSeries<>(
        List.of(new Segment<>(SimosTimestamp.of(0L), value)), List.of(), null);
  }
}
