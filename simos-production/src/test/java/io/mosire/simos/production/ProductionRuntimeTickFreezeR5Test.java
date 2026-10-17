package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ExpressionDefinition;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionTickCondition;
import io.mosire.simos.production.contract.RestrictedExpression;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>R5（§15.5 / P 批判据）：影响本 tick 生产公式的<b>全部</b>经验输入必须冻结 —— 含原始经验</b> （D-032 修复轮 T9 批；期望值按 §15.5
 * 与 P 批账本的手算复算）。
 *
 * <p>★★ <b>缺陷现场（回代码核过）</b>：{@code efficiencyScaleFor} 的 {@code condition != null} 分支里熟练度读冻结条件， 但
 * {@code primaryExperience} 仍读 {@code current.experienceOf(...)} ⇒ 同 tick 先执行的实例涨经验，后执行的实例公式吃到新经验
 * （复现：同户两实例正序 100/105、倒序 105/100）。
 *
 * <p>★★ <b>公式与手算（本世界）</b>：效率公式 = {@code 1000 + primary_experience}；家户 H1 起点 farming=300 ⇒ E=1300；
 * 方案 1 天周期、每规模 1 劳动 + 1 LAND、每规模产 1 谷；每实例出工 100、占用 LAND 200。
 *
 * <pre>
 *   baseScale = min(劳动 100, 资产 200) = 100
 *   E=1300 ⇒ finalScale = ⌊100 × 1300 ÷ 1000⌋ = 130   （若读到本 tick 先涨到的 400 ⇒ E=1400 ⇒ 140 = 缺陷读数）
 *   经验增长：g=1000、出工 100 ⇒ exact = 100×1000 = 100,000 ⇒ +100/次
 * </pre>
 *
 * <p>★ 三个判据：① 引用 {@code primary_experience} 的公式 + 同户两实例<b>正反序</b> ⇒ 规模逐值相同；② tick 边界（N 内执行不改变 N
 * 的冻结值）；③ {@code condition == null}（手搭夹具直调）路径逐值不变（仍读当刻账本）。
 */
class ProductionRuntimeTickFreezeR5Test {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;

  // ── ① 正反序：同户两实例在本 tick 的规模必须逐值相同 ────────────────────────────────────

  @Test
  void sameHouseholdTwoInstancesProduceTheSameScaleInBothInstanceOrders() {
    ProductionRuntimeEngine forward = new ProductionRuntimeEngine(freezeWorld());
    ProductionTickCondition opened = forward.beginTick(1L);
    assertThat(opened.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ tick 起点冻结的原始经验 = 300（效率公式的输入之一）")
        .isEqualTo(300L);
    assertThat(opened.masteryOf(H1, ProductionTestFixtures.FARMING)).isEqualTo(300L);

    ProductionDayResult forwardResult =
        forward.executeDay(day(List.of(ProductionTestFixtures.U1, ProductionTestFixtures.U2)));
    assertThat(forwardResult.rejections()).isEmpty();
    assertThat(scalesByUnit(forwardResult))
        .as("★★ 两个实例都按 tick 起点冻结的 300 算 ⇒ 都是 130（缺陷读数：后跑的 U2 = 140）")
        .containsOnlyKeys(ProductionTestFixtures.U1, ProductionTestFixtures.U2)
        .containsValues(130L, 130L);

    ProductionRuntimeEngine reversed = new ProductionRuntimeEngine(freezeWorld());
    reversed.beginTick(1L);
    ProductionDayResult reversedResult =
        reversed.executeDay(day(List.of(ProductionTestFixtures.U2, ProductionTestFixtures.U1)));

    assertThat(reversedResult.rejections()).isEmpty();
    assertThat(scalesByUnit(reversedResult))
        .as("★★ 换实例序 ⇒ 规模逐值相同（原始经验同属公式输入，必须与熟练度同口径冻结）")
        .isEqualTo(scalesByUnit(forwardResult));
  }

  // ── ② tick 边界：本 tick 内的执行只改候选态，不改冻结值；下一 tick 重新快照 ──────────────

  @Test
  void executionInsideTheTickChangesTheCandidateStateButNotTheFrozenCondition() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(freezeWorld());
    engine.beginTick(1L);

    engine.execute(execute(ProductionTestFixtures.U1));
    engine.execute(execute(ProductionTestFixtures.U2));

    assertThat(engine.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("候选态：300 + 100 + 100 = 500（两次执行各涨 100）")
        .isEqualTo(500L);
    assertThat(
            engine
                .currentCondition()
                .orElseThrow()
                .experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ tick 1 的冻结原始经验恒为 300（不因本 tick 内执行而变）")
        .isEqualTo(300L);
    assertThat(
            engine.currentCondition().orElseThrow().masteryOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 冻结熟练度同为 300")
        .isEqualTo(300L);

    ProductionTickCondition next = engine.beginTick(2L);
    assertThat(next.experienceOf(H1, ProductionTestFixtures.FARMING))
        .as("★ 下一 tick 在衰减之后重新快照：本世界衰减 0 ⇒ 读到 500")
        .isEqualTo(500L);
    ProductionExecutionOutcome.Executed inTick2 =
        (ProductionExecutionOutcome.Executed) engine.execute(execute(ProductionTestFixtures.U1));
    assertThat(inTick2.receipt().scale())
        .as("★ tick 2 用新冻结值：E = 1000 + 500 = 1500 ⇒ ⌊100×1500÷1000⌋ = 150")
        .isEqualTo(150L);
  }

  // ── ③ condition == null（手搭夹具直调）路径逐值不变：仍按当刻账本逐次读 ────────────────

  @Test
  void withoutABegunTickTheLegacyPerCallReadIsUnchanged() {
    ProductionRuntimeEngine engine = new ProductionRuntimeEngine(freezeWorld());

    ProductionExecutionOutcome.Executed first =
        (ProductionExecutionOutcome.Executed) engine.execute(execute(ProductionTestFixtures.U1));
    assertThat(first.receipt().scale()).as("无 tick：第一次读到 300 ⇒ 130").isEqualTo(130L);

    ProductionExecutionOutcome.Executed second =
        (ProductionExecutionOutcome.Executed) engine.execute(execute(ProductionTestFixtures.U2));
    assertThat(second.receipt().scale())
        .as("★ condition == null 分支逐值不变：第二次读到 400（当刻候选态）⇒ 140 —— 与 tick 内的 130 形成判别对照")
        .isEqualTo(140L);
  }

  // ── 夹具（真实数据）────────────────────────────────────────────────────────────────────

  /**
   * 同户两实例世界：一个方案、两个实例；效率公式 {@code 1000 + primary_experience}（= 合法公式输入 {@link
   * ProductionExperienceUse#PRIMARY_EXPERIENCE_INPUT}）；H1 起点 farming=300、g=1000、不衰减。
   */
  private static ProductionData freezeWorld() {
    ProductionExperienceUse primaryExperienceFormula =
        new ProductionExperienceUse(
            Optional.of(ProductionTestFixtures.FARMING),
            Optional.empty(),
            ProductionEffectScope.TOTAL,
            new ExpressionDefinition(
                ExpressionDefinition.CURRENT_VERSION,
                List.of(ProductionExperienceUse.PRIMARY_EXPERIENCE_INPUT),
                new RestrictedExpression.Add(
                    new RestrictedExpression.Const(1_000L),
                    new RestrictedExpression.Input(
                        ProductionExperienceUse.PRIMARY_EXPERIENCE_INPUT))),
            0L);
    return ProductionData.empty()
        .withExperienceTrack(
            ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 1_000L, 0L))
        .withIndustry(
            ProductionTestFixtures.planWithRoleGrowth(
                ProductionTestFixtures.FARM,
                1L,
                1L,
                Map.of(LAND, 1L),
                Map.of(),
                Map.of(GRAIN, 1L),
                primaryExperienceFormula,
                ProductionTestFixtures.FARMING))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
        .withInstance(
            ProductionTestFixtures.instance(
                ProductionTestFixtures.U2, ProductionTestFixtures.FARM, "0_0"))
        .withHouseholdExperience(
            H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(300L, 0L));
  }

  /** 一次执行（既有实例、一个生产日）：H1 出工 100、占用 LAND 200、无原料。 */
  private static ProductionExecutionRequest execute(ProductionUnitId unit) {
    return new ProductionExecutionRequest(
        Optional.of(unit),
        Optional.empty(),
        Long.MAX_VALUE,
        List.of(
            new ParticipantContribution(H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
        Map.of(LAND, 200L),
        Map.of(),
        ExternalAttributeFacts.EMPTY,
        Map.of(),
        Map.of(),
        Map.of());
  }

  /** 一天两步（按给定序）。 */
  private static ProductionDayRequest day(List<ProductionUnitId> unitsInOrder) {
    List<ProductionDayRequest.Step> steps =
        unitsInOrder.stream()
            .map(
                unit ->
                    new ProductionDayRequest.Step(
                        Optional.of(unit),
                        Optional.empty(),
                        Long.MAX_VALUE,
                        List.of(
                            new ParticipantContribution(
                                H1, ProductionRoleDefinition.defaultOperator().id(), 100L)),
                        Map.of(LAND, 200L),
                        Map.of(),
                        ExternalAttributeFacts.EMPTY,
                        Map.of(),
                        Map.of(),
                        Map.of()))
            .toList();
    return new ProductionDayRequest(steps);
  }

  /** 回执按实例 id 索引（顺序无关的读数形制）。 */
  private static Map<ProductionUnitId, Long> scalesByUnit(ProductionDayResult result) {
    Map<ProductionUnitId, Long> scales = new LinkedHashMap<>();
    for (ProductionReceipt receipt : result.receipts()) {
      scales.put(receipt.unit(), receipt.scale());
    }
    return scales;
  }
}
