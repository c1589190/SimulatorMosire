package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：N13「单产出方案入口算法逐值不变（候选面退役后）」的专用载体</b>。
 *
 * <p>★★ <b>判据</b>：{@code ProductionCandidate}/{@code templateFor} 候选面退役后，"单产出方案"从 {@code
 * probe}（试算）到 {@code execute}（真实执行）的入口算法结果必须与退役前<b>逐值一致</b>；多产出方案按商品逐项一致。
 *
 * <p>★★ <b>纸面算式（期望值全部从方案数据与投入手算，不从被测代码反算）</b>：
 *
 * <pre>
 * 方案：capacityPerUnit = {LAND: 1,000}（每 1 规模 1,000 千分亩）
 *       laborPerUnit    = 1,000（每 1 规模 1,000 千分劳动）
 *       inputPerUnit    = {SEED: 3}（每 1 规模吃 3 毫种子）
 *       outputPerUnit   = {GRAIN: 67}（单产出；每 1 规模 67 单位 ⇒ ×1000 换毫）
 * 家户投入：劳动 100,000 毫、LAND 200,000 千分亩、SEED 60,000 毫、熟练度 500（E = 1 + 500‰ = 1500‰）
 *
 * ① 资产通道 = ⌊200,000 ÷ 1,000⌋ = 200
 * ② 劳动通道 = ⌊100,000 ÷ 1,000⌋ = 100
 * ③ 原料通道 = ⌊60,000 ÷ 3⌋     = 20,000
 *    baseScale（基准规模）= min(200, 100, 20,000) = 100
 *    finalScale（产出规模）= ⌊100 × 1500 ÷ 1000⌋ = 150（余数 0）
 * ④ 资源需求按 baseScale（不是 finalScale）：劳动 100 × 1,000 = 100,000；LAND 100 × 1,000 = 100,000；
 *    SEED 100 × 3 = 300
 * ⑤ 预计产出按 finalScale：150 × 67 × 1000 = 10,050,000 毫
 * ⇒ 真实执行（按最终投入 100,000 劳动 / 100,000 LAND / 300 SEED）必须给出同一 scale = 150 与同一毛产 10,050,000，
 *    实扣 = 300 SEED（消耗按 baseScale，不因熟练度多占）。
 * </pre>
 *
 * <p>★ <b>多产出腿</b>：同一组数字把 {@code outputPerUnit} 换成 {@code {GRAIN:67, CLOTH:3}} ⇒ 每一商品各自 ×
 * finalScale × 1000（10,050,000 / 450,000）；合计不是"拿某个商品当代表"算出来的第二份口径。
 *
 * <p>★ <b>为什么这是 N13 的独立载体</b>：既有 {@code ProductionEfficiencyHandCalcTest} 钉的是 {@code
 * ProductionEfficiency.evaluate} 的<b>公式</b>（N1–N4），本用例钉的是<b>退役后唯一入口</b>（{@code probe}/{@code
 * execute}）在两段契约上的逐值一致：试算的"需求三件"与"预计产出"、执行的"实扣/实毛产"。
 */
class SingleOutputEntryHandCalcTest {

  private static final HouseholdId H1 = ProductionTestFixtures.H1;
  private static final AssetKind LAND = AssetKind.LAND;
  private static final CommodityId GRAIN = ProductionTestFixtures.GRAIN;
  private static final CommodityId CLOTH = ProductionTestFixtures.CLOTH;
  private static final CommodityId SEED = new CommodityId("seed");

  private static final long LABOR_MILLI = 100_000L;
  private static final long LAND_MILLI = 200_000L;
  private static final long SEED_MILLI = 60_000L;
  private static final long MASTERY = 500L;

  @Test
  void theSingleOutputEntryHandComputationIsUnchangedFromProbeToExecute() {
    IndustryDefinitionView single = plan(FARM_ID, Map.of(GRAIN, 67L));
    ProductionRuntimeEngine engine = engine(single);
    EngineHolder holder = new EngineHolder(engine);

    ProductionProbeResult probe = probe(engine, single, LABOR_MILLI, LAND_MILLI, SEED_MILLI);

    // ── ① 试算段：需求三件按 baseScale、产出按 finalScale ──────────────────────────────
    assertThat(probe.feasible()).as("可行（三路都够）").isTrue();
    assertThat(probe.scale()).as("④ 产出规模 = ⌊100 × 1500 ÷ 1000⌋ = 150").isEqualTo(150L);
    assertThat(probe.efficiencyScalePerMille()).as("E = 1000 + 熟练度 500").isEqualTo(1_500L);
    assertThat(probe.laborRequiredMilli()).as("劳动需求 = 100 × 1,000").isEqualTo(100_000L);
    assertThat(probe.assetRequired())
        .as("LAND 需求 = 100 × 1,000")
        .containsExactly(Map.entry(LAND, 100_000L));
    assertThat(probe.inputRequired())
        .as("SEED 需求 = 100 × 3")
        .containsExactly(Map.entry(SEED, 300L));
    assertThat(probe.expectedOutput())
        .as("⑤ 预计毛产 = 150 × 67 × 1000 = 10,050,000 毫（单产出）")
        .containsExactly(Map.entry(GRAIN, 10_050_000L));

    // ── ② 执行段：同一组最终投入 ⇒ 同一 scale / 同一毛产 / 实扣按 baseScale ────────────
    ProductionExecutionOutcome.Executed executed =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                new ProductionExecutionRequest(
                    Optional.of(ProductionTestFixtures.U1),
                    Optional.empty(),
                    probe.scale(),
                    Map.of(H1, LABOR_MILLI),
                    Map.of(LAND, LAND_MILLI),
                    Map.of(SEED, probe.inputRequired().get(SEED)),
                    ExternalAttributeFacts.EMPTY));
    ProductionReceipt receipt = executed.receipt();

    assertThat(receipt.scale()).as("★★ 执行 scale 与试算逐值相同（150）").isEqualTo(probe.scale());
    assertThat(receipt.grossOutput())
        .as("★★ 实毛产与预计逐值相同（10,050,000 毫）")
        .isEqualTo(probe.expectedOutput());
    assertThat(receipt.consumedInputs())
        .as("实扣 = baseScale 档的 300 SEED（不因熟练度多占）")
        .containsExactly(Map.entry(SEED, 300L));
    assertThat(receipt.cycleClosed()).as("1 天周期 ⇒ 本次关账").isTrue();
    holder.assertNoDefinitionSideEffects();
  }

  @Test
  void aMultiOutputPlanIsHandComputedPerCommodityInsteadOfThroughOneRepresentative() {
    IndustryDefinitionView multi = plan(FARM_ID, Map.of(GRAIN, 67L, CLOTH, 3L));
    ProductionRuntimeEngine engine = engine(multi);

    ProductionProbeResult probe = probe(engine, multi, LABOR_MILLI, LAND_MILLI, SEED_MILLI);
    assertThat(probe.scale()).as("多产出不改变规模：仍是 150").isEqualTo(150L);
    assertThat(probe.expectedOutput())
        .as(
            "★★ 多产出按商品逐项：GRAIN = 150 × 67 × 1000 = 10,050,000；"
                + "CLOTH = 150 × 3 × 1000 = 450,000（各商品各算一次）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 10_050_000L, CLOTH, 450_000L));

    ProductionExecutionOutcome.Executed executed =
        (ProductionExecutionOutcome.Executed)
            engine.execute(
                new ProductionExecutionRequest(
                    Optional.of(ProductionTestFixtures.U1),
                    Optional.empty(),
                    probe.scale(),
                    Map.of(H1, LABOR_MILLI),
                    Map.of(LAND, LAND_MILLI),
                    Map.of(SEED, probe.inputRequired().get(SEED)),
                    ExternalAttributeFacts.EMPTY));
    assertThat(executed.receipt().grossOutput())
        .as("执行段逐商品与试算相同（不是只对 GRAIN 一行）")
        .isEqualTo(probe.expectedOutput());
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  private static final IndustryId FARM_ID = ProductionTestFixtures.FARM;

  private static IndustryDefinitionView plan(IndustryId id, Map<CommodityId, Long> outputPerUnit) {
    Map<CommodityId, Long> input = Map.of(SEED, 3L);
    return ProductionTestFixtures.plan(
        id,
        1L,
        1_000L,
        Map.of(LAND, 1_000L),
        input,
        outputPerUnit,
        ProductionTestFixtures.totalUse(
            ProductionTestFixtures.FARMING, ProductionEffectScope.TOTAL, 0L));
  }

  private static ProductionRuntimeEngine engine(IndustryDefinitionView plan) {
    return new ProductionRuntimeEngine(
        ProductionData.empty()
            .withExperienceTrack(
                ProductionTestFixtures.track(ProductionTestFixtures.FARMING, 10_000L, 0L, 0L))
            .withIndustry(plan)
            .withInstance(
                ProductionTestFixtures.instance(
                    ProductionTestFixtures.U1, ProductionTestFixtures.FARM, "0_0"))
            .withHouseholdExperience(
                H1, ProductionTestFixtures.FARMING, ProductionTestFixtures.feature(MASTERY, 0L)));
  }

  private static ProductionProbeResult probe(
      ProductionRuntimeEngine engine,
      IndustryDefinitionView plan,
      long laborMilli,
      long landMilli,
      long seedMilli) {
    Map<io.mosire.simos.production.contract.ExperienceTrackId, Long> mastery =
        Map.of(ProductionTestFixtures.FARMING, MASTERY);
    Map<io.mosire.simos.production.contract.ExperienceTrackId, Long> experience =
        Map.of(ProductionTestFixtures.FARMING, MASTERY);
    return engine.probe(
        new ProductionProbeRequest(
            plan.id(),
            ProductionTestFixtures.conditions(
                H1,
                laborMilli,
                Map.of(LAND, landMilli),
                Map.of(SEED, seedMilli),
                mastery,
                experience),
            Map.of(LAND, landMilli),
            Optional.empty(),
            ExternalAttributeFacts.EMPTY));
  }

  /** 只读壳：确保两条入口没有偷偷塞进方案定义/覆盖表（"逐值不变"的另一半）。 */
  private static final class EngineHolder {

    private final ProductionRuntimeEngine engine;
    private final Map<IndustryId, IndustryDefinitionView> definitionsBefore;

    EngineHolder(ProductionRuntimeEngine engine) {
      this.engine = engine;
      this.definitionsBefore = new LinkedHashMap<>(engine.state().industries());
    }

    void assertNoDefinitionSideEffects() {
      assertThat(engine.state().industries())
          .as("probe/execute 不得改方案定义（候选面退役后入口只读定义）")
          .isEqualTo(definitionsBefore);
    }
  }
}
