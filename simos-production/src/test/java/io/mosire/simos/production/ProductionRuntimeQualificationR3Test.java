package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionCreationRequest;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>R3（§15.3 / P 批判据）：实际执行是权威入口 —— 解锁与可用性条件必须在 {@code execute}/{@code executeDay} 上生效</b>
 * （D-032 修复轮 T9 批；期望值来自设计书 §15.3 与 P 批实现账本 §1/§2）。
 *
 * <p>★★ <b>缺陷现场（回代码核过）</b>：{@code ProductionRuntimeEngine.execute} 旧版不做 {@code
 * unsatisfiedCondition}/{@code isUnlockedBy} 检查 —— 条件只在 {@code probe} 里判 ⇒ "试算被拒的方案，直接执行仍能生产"。
 * 修复后的契约（§15.3）：
 *
 * <ul>
 *   <li>probe 与 execute <b>共用同一个</b>资格求值入口（同一条件遍历顺序、同一具名理由文本）；
 *   <li>资格被拒 = 具名业务拒绝（{@link ProductionExecutionOutcome.Rejected}）+ <b>零残留</b>（无实例/进度/经验/消耗/占用变更）；
 *   <li>条件主体 = 实例经营者对应的家户；组织经营者对"经验门槛/解锁门槛"fail-closed（{@code condition-subject-unknown}），
 *       外部属性条件不受影响（属性事实是平表）；
 *   <li>正式日循环（{@code executeDay} + {@link ExternalAttributeFacts#EMPTY}）同样具名拒（§4.5
 *       fail-closed，不是缺陷）；
 *   <li>无条件方案逐值不变。
 * </ul>
 *
 * <p>★ <b>手算基准</b>（所有世界共用）：方案 {@code laborPerUnit=1}、{@code capacityPerUnit={LAND:1}}、无原料；家户出工
 * {@code 100} 毫劳动、占用 {@code LAND 200}、零经验（E=1000）⇒ {@code baseScale=min(100,200)=100}、{@code
 * finalScale=100}、 毛产 {@code 100 × 每单位 1 × 1000 = 100,000 毫}。
 */
class ProductionRuntimeQualificationR3Test {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;
  private static final ActorRef ORGANIZATION = new ActorRef(ActorKind.ORGANIZATION, "org-r3");

  // ── ① locked 方案：直接 execute 必须与被 probe 拒的理由逐字相同，且零残留 ─────────────────────

  @Test
  void executeRejectsALockedPlanWithTheSameReasonAsProbeAndLeavesNoResidue() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(lockedWorld());
    ProductionData before = engine.state();

    ProductionProbeResult probed =
        engine.probe(probeRequest(ProductionTestFixtures.FARM, 100L, 200L));
    assertThat(probed.feasible()).as("前置：probe 真的被拒（不是 probed 可行却断言 execute 拒）").isFalse();
    assertThat(probed.reason()).as("§15.3：解锁门槛 100、家户经验 0 ⇒ 具名 locked").isEqualTo("locked");

    ProductionExecutionOutcome outcome = engine.execute(executeRequest(ProductionTestFixtures.U1));

    assertThat(outcome)
        .as("★ 实际执行不得绕过解锁资格")
        .isInstanceOf(ProductionExecutionOutcome.Rejected.class);
    String executeReason = ((ProductionExecutionOutcome.Rejected) outcome).reason();
    assertThat(executeReason)
        .as("★ 与 probe 逐字相同（同一 qualificationFailure 拼写点）")
        .isEqualTo(probed.reason());

    // 零残留：全切片（产业/模式/实例/经验条/经验账/解锁/效率行/产出覆盖）逐值不变 + 无变更集 + 无 tick 记账。
    assertThat(engine.state()).as("★ 被拒 = 零残留：production 全切片（8 组件）逐值等于调用前").isEqualTo(before);
    assertThat(((ProductionChangeSet) engine.changeSet()).isEmpty())
        .as("★ 没有可提交的变更（拒绝不产生 revision）")
        .isTrue();
    assertThat(engine.advancedUnitsThisTick()).as("★ 不登记'本 tick 已推进'").isEmpty();
    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays()).as("★ 进度未推进").isZero();
    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING)).as("★ 经验未涨").isZero();
  }

  // ── ② 外部属性缺失：execute 与 probe 逐字相同，且补上事实即放行（判别力在"缺什么"）───────────

  @Test
  void executeRejectsAMissingExternalAttributeWithTheSameReasonAsProbe() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(externalAttributeWorld());
    ProductionData before = engine.state();

    ProductionProbeResult probed =
        engine.probe(probeRequest(ProductionTestFixtures.CRAFT, 100L, 200L));
    assertThat(probed.feasible()).isFalse();
    assertThat(probed.reason())
        .as("§4.5：缺属性事实 ⇒ fail-closed，理由带键名")
        .isEqualTo("condition-unsatisfied:external-attribute-missing:literacy");

    ProductionExecutionOutcome rejected =
        engine.execute(executeRequest(ProductionTestFixtures.U3, ExternalAttributeFacts.EMPTY));

    assertThat(rejected).isInstanceOf(ProductionExecutionOutcome.Rejected.class);
    assertThat(((ProductionExecutionOutcome.Rejected) rejected).reason())
        .as("★ 直接执行同样具名拒，且与 probe 逐字相同")
        .isEqualTo(probed.reason());
    assertThat(engine.state()).as("★ 零残留：全切片逐值不变").isEqualTo(before);

    // 正对照：把同一事实补上 ⇒ probe 与 execute 都放行（证明拒绝的原因是"缺 literacy"，不是别的）。
    ExternalAttributeFacts withLiteracy = new ExternalAttributeFacts(Map.of("literacy", 1L));
    assertThat(engine.probe(probeRequest(ProductionTestFixtures.CRAFT, 100L, 200L)))
        .as("probe 的可用性只看请求里的事实")
        .isEqualTo(probed);
    ProductionProbeResult probedWithFact =
        engine.probe(probeRequest(ProductionTestFixtures.CRAFT, 100L, 200L, withLiteracy));
    assertThat(probedWithFact.feasible()).as("★ 补上 literacy=1 ⇒ 可行").isTrue();
    assertThat(probedWithFact.reason()).isNull();

    ProductionExecutionOutcome executed =
        engine.execute(executeRequest(ProductionTestFixtures.U3, withLiteracy));
    assertThat(executed)
        .as("★ 同一事实下实际执行也必须放行（判别力：拒绝不是'一律拒绝'）")
        .isInstanceOf(ProductionExecutionOutcome.Executed.class);
    assertThat(((ProductionExecutionOutcome.Executed) executed).receipt().scale())
        .as("手算：labor 100、LAND 200、E=1000 ⇒ finalScale=100")
        .isEqualTo(100L);
  }

  // ── ③ 组织经营者 + 经验/解锁门槛 ⇒ condition-subject-unknown（fail-closed）──────────────

  @Test
  void organizationOperatorWithAnExperienceGateFailsClosedAsConditionSubjectUnknown() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(subjectWorld());
    ProductionData before = engine.state();

    // ① 解锁门槛（unlockAtPrimaryExperience > 0）——需要可读经验账的主体。
    ProductionUnitId orgUnit = new ProductionUnitId("unit-org-locked");
    ProductionExecutionOutcome lockedCreation =
        engine.execute(creationRequest(ProductionTestFixtures.FARM, orgUnit, ORGANIZATION));
    assertThat(lockedCreation).isInstanceOf(ProductionExecutionOutcome.Rejected.class);
    assertThat(((ProductionExecutionOutcome.Rejected) lockedCreation).reason())
        .as("§15.3：组织经营者不构成家户主体 ⇒ 解锁资格 fail-closed")
        .isEqualTo("condition-subject-unknown");

    // ② 经验门槛条件（MinimumExperience）——同样需要主体。
    ProductionUnitId orgUnit2 = new ProductionUnitId("unit-org-min-exp");
    ProductionExecutionOutcome minimumExperienceCreation =
        engine.execute(creationRequest(ProductionTestFixtures.MILL, orgUnit2, ORGANIZATION));
    assertThat(minimumExperienceCreation).isInstanceOf(ProductionExecutionOutcome.Rejected.class);
    assertThat(((ProductionExecutionOutcome.Rejected) minimumExperienceCreation).reason())
        .as("经验门槛条件同样 fail-closed（同一主体规则）")
        .isEqualTo("condition-subject-unknown");

    assertThat(engine.state()).as("★ 零残留：两次被拒都没有创建实例（全切片逐值不变）").isEqualTo(before);

    // 正对照：无经验面/无解锁门槛的方案对组织经营者不受影响（不得被这条守卫误杀）。
    ProductionUnitId orgUnit3 = new ProductionUnitId("unit-org-ok");
    ProductionExecutionOutcome okCreation =
        engine.execute(creationRequest(ProductionTestFixtures.CRAFT, orgUnit3, ORGANIZATION));
    assertThat(okCreation)
        .as("★ 无经验类条件的方案对组织经营者照常执行（fail-closed 只作用于有经验面的方案）")
        .isInstanceOf(ProductionExecutionOutcome.Executed.class);
    assertThat(((ProductionExecutionOutcome.Executed) okCreation).receipt().created()).isTrue();
    assertThat(((ProductionExecutionOutcome.Executed) okCreation).receipt().scale())
        .isEqualTo(100L);
    assertThat(engine.units()).containsKey(orgUnit3);
  }

  // ── ④ 无条件方案逐值不变（同一世界、同一手算读数）──────────────────────────────────────

  @Test
  void unconditionalPlanStillExecutesWithTheSameNumbers() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(subjectWorld());

    ProductionExecutionOutcome.Executed executed =
        (ProductionExecutionOutcome.Executed)
            engine.execute(executeRequest(ProductionTestFixtures.U3));

    ProductionReceipt receipt = executed.receipt();
    assertThat(receipt.cycleClosed()).as("1 天周期 ⇒ 本次即关账").isTrue();
    assertThat(receipt.scale()).as("手算：baseScale=min(100,200)=100、E=1000 ⇒ 100").isEqualTo(100L);
    assertThat(receipt.grossOutput()).containsExactly(Map.entry(GRAIN, 100_000L));
    assertThat(receipt.consumedInputs()).as("该方案没有原料通道 ⇒ 无投入消耗").isEmpty();
    assertThat(engine.units().get(ProductionTestFixtures.U3).progressDays()).as("关账后进度归零").isZero();
  }

  // ── ⑤ executeDay 日循环：同拒（具名）+ 零残留；正常方案仍可推进 ───────────────────────────

  @Test
  void executeDayRejectsLockedAndMissingAttributeStepsByNameAndLeavesTheStateUntouched() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(mixedDayWorld());
    engine.beginTick(1L);
    ProductionData before = engine.state();

    ProductionDayResult result =
        engine.executeDay(
            new ProductionDayRequest(
                List.of(
                    step(ProductionTestFixtures.U1, ExternalAttributeFacts.EMPTY),
                    step(ProductionTestFixtures.U2, ExternalAttributeFacts.EMPTY))));

    assertThat(result.receipts()).as("★ 两步都被拒 ⇒ 没有任何回执").isEmpty();
    assertThat(result.rejections())
        .as("★ 具名理由逐条可读（真实日循环传 EMPTY ⇒ 外部属性条件 fail-closed 是设计口径 §4.5）")
        .containsEntry(ProductionTestFixtures.U1, "locked")
        .containsEntry(
            ProductionTestFixtures.U2, "condition-unsatisfied:external-attribute-missing:literacy");
    assertThat(engine.state()).as("★ 零残留：整批被拒后 production 全切片逐值等于整批之前").isEqualTo(before);
    assertThat(engine.advancedUnitsThisTick()).as("★ 没有任何实例被登记推进").isEmpty();
    assertThat(result.closedUnitScales()).isEmpty();
    assertThat(result.closedGrossOutput()).isEmpty();
  }

  @Test
  void executeDayStillAdvancesTheUnconditionalInstanceOnTheSameWorld() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(mixedDayWorld());
    engine.beginTick(1L);

    ProductionDayResult result =
        engine.executeDay(
            new ProductionDayRequest(
                List.of(step(ProductionTestFixtures.U3, ExternalAttributeFacts.EMPTY))));

    assertThat(result.rejections()).as("正常方案不得被同批的锁定步影响").isEmpty();
    assertThat(result.receipts()).hasSize(1);
    assertThat(result.receipts().get(0).cycleClosed()).isTrue();
    assertThat(result.receipts().get(0).grossOutput()).containsExactly(Map.entry(GRAIN, 100_000L));
    assertThat(result.closedUnitScales()).containsEntry(ProductionTestFixtures.U3, 100L);
    assertThat(engine.advancedUnitsThisTick()).containsExactly(ProductionTestFixtures.U3);
    assertThat(engine.units().get(ProductionTestFixtures.U1).progressDays())
        .as("被拒的 U1 进度 0")
        .isZero();
    assertThat(engine.units().get(ProductionTestFixtures.U2).progressDays())
        .as("被拒的 U2 进度 0")
        .isZero();
  }

  // ── 夹具（真实数据；见类注的手算基准）──────────────────────────────────────────────────

  /** locked 世界：FARM 解锁门槛 100、家户经验 0 ⇒ probe/execute 都必须具名 locked。 */
  private static ProductionData lockedWorld() {
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 100L)))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"));
  }

  /**
   * 外部属性世界：CRAFT 要求 literacy ≥ 1；家户没有该事实 ⇒ 具名
   * condition-unsatisfied:external-attribute-missing:literacy。
   */
  private static ProductionData externalAttributeWorld() {
    IndustryDefinitionView craft =
        withAvailability(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.CRAFT,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE),
            new ProductionAvailabilityCondition.ExternalAttribute("literacy", 1L));
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(craft)
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U3, ProductionTestFixtures.CRAFT, "0_0"));
  }

  /** 主体世界：FARM 有解锁门槛、MILL 有经验门槛条件、CRAFT 无经验面（组织经营者的正/负对照）。 */
  private static ProductionData subjectWorld() {
    IndustryDefinitionView mill =
        withAvailability(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.MILL,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE),
            new ProductionAvailabilityCondition.MinimumExperience(
                ProductionTestFixtures.FARMING, 10L));
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 100L)))
        .withIndustry(mill)
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.CRAFT,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U3, ProductionTestFixtures.CRAFT, "0_0"));
  }

  /** 日循环世界：U1=FARM（locked）、U2=MILL（外部属性 literacy）、U3=CRAFT（无条件）。 */
  private static ProductionData mixedDayWorld() {
    IndustryDefinitionView mill =
        withAvailability(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.MILL,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE),
            new ProductionAvailabilityCondition.ExternalAttribute("literacy", 1L));
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionTestFixtures.totalUse(
                    ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 100L)))
        .withIndustry(mill)
        .withIndustry(
            ProductionTestFixtures.plan(
                ProductionTestFixtures.CRAFT,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                ProductionExperienceUse.NONE))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.MILL, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U3, ProductionTestFixtures.CRAFT, "0_0"));
  }

  /** 复制一份方案定义、只替换可用性条件（其余字段逐值带过，不手写第二套定义）。 */
  private static IndustryDefinitionView withAvailability(
      IndustryDefinitionView base, ProductionAvailabilityCondition... conditions) {
    return new IndustryDefinitionView(
        base.id(),
        base.name(),
        base.regime(),
        base.skill(),
        base.cycleDays(),
        base.buildDays(),
        base.capacityPerUnit(),
        base.dailyInputPerUnit(),
        base.dailyLaborPerUnit(),
        base.laborPerUnit(),
        base.outputPerUnit(),
        base.cycleInputPerUnit(),
        base.slots(),
        base.allocation(),
        base.moneyOutputPerUnit(),
        base.experience(),
        List.of(conditions),
        base.acceptedRightKinds());
  }

  /** 试算请求：同方案、劳动 100、占用 LAND 200、无原料；属性事实默认空表。 */
  private static ProductionProbeRequest probeRequest(
      IndustryId plan, long laborMilli, long assets) {
    return probeRequest(plan, laborMilli, assets, ExternalAttributeFacts.EMPTY);
  }

  private static ProductionProbeRequest probeRequest(
      IndustryId plan, long laborMilli, long assets, ExternalAttributeFacts attributes) {
    return new ProductionProbeRequest(
        plan,
        ProductionTestFixtures.conditions(
            H1, laborMilli, Map.of(LAND, assets), Map.of(), Map.of(), Map.of()),
        Map.of(LAND, assets),
        Optional.empty(),
        attributes);
  }

  /** 执行请求（既有实例、一天）：劳动 100、占用 LAND 200、无原料。 */
  private static ProductionExecutionRequest executeRequest(ProductionUnitId unit) {
    return executeRequest(unit, ExternalAttributeFacts.EMPTY);
  }

  private static ProductionExecutionRequest executeRequest(
      ProductionUnitId unit, ExternalAttributeFacts attributes) {
    return new ProductionExecutionRequest(
        Optional.of(unit),
        Optional.empty(),
        Long.MAX_VALUE,
        List.of(
            new ParticipantContribution(H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
        Map.of(LAND, 200L),
        Map.of(),
        attributes,
        Map.of(H1, attributes),
        Map.of(),
        Map.of());
  }

  /** 新建请求（一天）：由给定经营者创建并推进。 */
  private static ProductionExecutionRequest creationRequest(
      IndustryId plan, ProductionUnitId unit, ActorRef operator) {
    return new ProductionExecutionRequest(
        Optional.empty(),
        Optional.of(
            new ProductionCreationRequest(
                plan, unit, new ProductionLocation("0_0"), operator, "mode:" + plan.value())),
        Long.MAX_VALUE,
        List.of(
            new ParticipantContribution(H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
        Map.of(LAND, 200L),
        Map.of(),
        ExternalAttributeFacts.EMPTY,
        Map.of(H1, ExternalAttributeFacts.EMPTY),
        Map.of(),
        Map.of());
  }

  /** 日循环的一步（既有实例）。 */
  private static ProductionDayRequest.Step step(
      ProductionUnitId unit, ExternalAttributeFacts attributes) {
    return new ProductionDayRequest.Step(
        Optional.of(unit),
        Optional.empty(),
        Long.MAX_VALUE,
        List.of(
            new ParticipantContribution(H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
        Map.of(LAND, 200L),
        Map.of(),
        attributes,
        Map.of(H1, attributes),
        Map.of(),
        Map.of());
  }
}
