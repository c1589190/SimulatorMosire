package io.mosire.simos.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.contract.PerCycleCarry;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>T2a 判据 N1 / N2 / N3 / N4：效率公式的算式面（手算逐值）</b> （D-032 测试阶段派单 §14.11.5；期望值来自约束设计书 §13.8
 * 裁定一与实现账本 A3-§5 的手算样例）。
 *
 * <p>★★ <b>被测入口</b>：{@code ProductionEfficiency.evaluate(...)}（包内可见；`control-v1v12.md` §9.1
 * 点名的施工图落点）。 它是"试算 / 实际 / 家户利润比较"共用的<b>唯一</b>乘算点（I6），所以它的逐值读数就是 N1–N4 的第一手证据。
 *
 * <p>★★ <b>量纲（每个手算样例都按这套量纲走）</b>：{@code laborByHousehold} = 千分劳动·日； {@code
 * efficiencyWeightedLaborByHousehold} = 劳动 × 当刻效率的<b>精确分子</b>（千分劳动·日 × 千分倍率，<b>不</b>除 1000）； {@code
 * assetScaleRaw} / {@code cycleInputUsedMilli} = 物理占用与实扣量；{@code laborPerUnit} = 每单位规模的劳动。
 *
 * <p>★ {@code E_cycle = ⌊Σ加权劳动 ÷ Σ劳动⌋}（零劳动 ⇒ 1000 中性），故"熟练度 500 ⇒ E=1500"这组样例里 {@code Σ加权 = 100 ×
 * 1500 = 150000}。
 */
class ProductionEfficiencyHandCalcTest {

  private static final Map<HouseholdId, Long> LABOR_100 = Map.of(ProductionTestFixtures.H1, 100L);
  private static final Map<HouseholdId, Long> LABOR_100_E1500 =
      Map.of(ProductionTestFixtures.H1, 150_000L);
  private static final Map<HouseholdId, Long> LABOR_100_E1505 =
      Map.of(ProductionTestFixtures.H1, 150_500L);

  // ── N1 TOTAL：三通道都不乘 E 求 baseScale，再整体乘一次 ────────────────────────────────

  @Test
  void n1TotalEfficiencyMultipliesTheWholeBaseScaleOnce() {
    // 手算（A3-§5 样例一）：劳动 100（laborPerUnit=1 ⇒ 劳动规模 100）· 资产 200 · 原料 120（每单位 1）
    //   ⑤ baseScale = min(100, 200, 120) = 100
    //   ⑥ E_cycle  = 150000 ÷ 100 = 1500
    //   ⑦ TOTAL    = ⌊(100 × 1500 + 0) ÷ 1000⌋ = 150   ⇒ 消耗/占用按 100 档，产出按 150 档
    ProductionEfficiency.Evaluation evaluation =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.TOTAL,
            1L,
            LABOR_100_E1500,
            LABOR_100,
            200L,
            Map.of(ProductionTestFixtures.GRAIN, 1L),
            Map.of(ProductionTestFixtures.GRAIN, 120L),
            1L,
            Long.MAX_VALUE);

    assertThat(evaluation.efficiencyWeightedLaborMilli()).isEqualTo(150_000L);
    assertThat(evaluation.avgLaborMilli()).as("① 周期→日均：100 ÷ 1").isEqualTo(100L);
    assertThat(evaluation.baseLaborScale()).as("② 劳动通道基准规模（不乘 E）").isEqualTo(100L);
    assertThat(evaluation.assetScale()).as("③ 资产通道（不乘 E）").isEqualTo(200L);
    assertThat(evaluation.inputScale()).as("④ 原料通道（物理实扣）").isEqualTo(120L);
    assertThat(evaluation.baseScale()).as("⑤ 最紧基准规模 = min(100,200,120)").isEqualTo(100L);
    assertThat(evaluation.efficiencyScalePerMille()).as("⑥ E_cycle").isEqualTo(1_500L);
    assertThat(evaluation.finalScale()).as("⑦ 产出规模 = ⌊100×1500÷1000⌋").isEqualTo(150L);
    assertThat(evaluation.nextCarry()).as("余数三段全零 ⇒ 不建行（缺行与全零行逐值等价）").isNull();
  }

  // ── N2 LABOR：只折算劳动通道，再与其余通道取 min ────────────────────────────────────────

  @Test
  void n2LaborScopeFoldsOnlyTheLaborChannelAndMayLoseTheWholeBonus() {
    // 手算（A3-§5 样例一 LABOR 分支）：
    //   劳动规模' = ⌊(min(100, ∞) × 1500 + 0) ÷ 1000⌋ = 150
    //   baseScale = finalScale = min(150, 资产 200, 原料 120) = 120
    ProductionEfficiency.Evaluation labor =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.LABOR,
            1L,
            LABOR_100_E1500,
            LABOR_100,
            200L,
            Map.of(ProductionTestFixtures.GRAIN, 1L),
            Map.of(ProductionTestFixtures.GRAIN, 120L),
            1L,
            Long.MAX_VALUE);

    assertThat(labor.baseScale()).as("LABOR：劳动通道折算后与其余通道取 min").isEqualTo(120L);
    assertThat(labor.finalScale()).as("LABOR 下 baseScale 与 finalScale 同值").isEqualTo(120L);
    assertThat(labor.baseLaborScale()).as("劳动通道<基准>规模仍是未乘 E 的 100").isEqualTo(100L);
    assertThat(labor.efficiencyScalePerMille()).isEqualTo(1_500L);

    // 手算（A3-§5 样例三）：劳动 100 · 资产 100 · 原料 300 的 LABOR 后果——
    //   劳动规模' = 150，但 min(150, 100, 300) = 100 ⇒ 产出与"完全不加成"逐值相同（加成整段为 0）。
    ProductionEfficiency.Evaluation bottleneckElsewhere =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.LABOR,
            1L,
            LABOR_100_E1500,
            LABOR_100,
            100L,
            Map.of(ProductionTestFixtures.GRAIN, 1L),
            Map.of(ProductionTestFixtures.GRAIN, 300L),
            1L,
            Long.MAX_VALUE);

    assertThat(bottleneckElsewhere.baseScale()).isEqualTo(100L);
    assertThat(bottleneckElsewhere.finalScale())
        .as("★ LABOR 的后果：瓶颈在资产 ⇒ 加成整段为 0（枚举注里写明的语义）")
        .isEqualTo(100L);
  }

  // ── N3 反例：原料主导时 TOTAL 不再把加成归零 ───────────────────────────────────────────

  @Test
  void n3TotalKeepsTheBonusAliveWhenTheBindingChannelIsNotLabor() {
    // 手算（A3-§5 样例二；§13.8 裁定一要治的那个反例）：劳动 100 · 资产 100 · 原料 100 · E=1500
    //   A 批旧式（劳动通道×E、资产通道×E、原料物理实扣后取 min）= min(150, 150, 100) = 100 ⇒ 加成整段归零
    //   A′ TOTAL                                              = min(100,100,100)=100 ⇒
    // ⌊100×1500÷1000⌋ = 150
    ProductionEfficiency.Evaluation total =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.TOTAL,
            1L,
            LABOR_100_E1500,
            LABOR_100,
            100L,
            Map.of(ProductionTestFixtures.GRAIN, 1L),
            Map.of(ProductionTestFixtures.GRAIN, 100L),
            1L,
            Long.MAX_VALUE);
    ProductionEfficiency.Evaluation labor =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.LABOR,
            1L,
            LABOR_100_E1500,
            LABOR_100,
            100L,
            Map.of(ProductionTestFixtures.GRAIN, 1L),
            Map.of(ProductionTestFixtures.GRAIN, 100L),
            1L,
            Long.MAX_VALUE);

    assertThat(total.baseScale()).as("三通道基准规模").isEqualTo(100L);
    assertThat(total.finalScale()).as("★ 今 = 150（旧式同输入给 100）").isEqualTo(150L);
    assertThat(labor.finalScale()).as("同输入 LABOR = 100（局部模型，与旧式结构同款；故 TOTAL 是默认）").isEqualTo(100L);
  }

  // ── N4 余数跨周期：⌊⌋ 的零头不得系统性丢掉 ────────────────────────────────────────────

  @Test
  void n4EfficiencyRemainderCarriesAcrossCyclesInsteadOfBeingTruncatedEveryTime() {
    long assetScaleRaw = 200L;
    Map<CommodityId, Long> inputPerUnit = Map.of(ProductionTestFixtures.GRAIN, 1L);
    Map<CommodityId, Long> inputUsed = Map.of(ProductionTestFixtures.GRAIN, 120L);

    // 手算（A3-§5 样例四）：baseScale=100、E=1505（Σ加权 150500 ÷ 劳动 100）、余数起点 0
    //   第 1 周期：⌊(100×1505 + 0) ÷ 1000⌋   = 150，余数 500
    //   第 2 周期：⌊(100×1505 + 500) ÷ 1000⌋ = 151，余数 0
    //   ⇒ 两周期合计 301 = ⌊2 × 150.5⌋；逐次截断（不看余数）会永远给 150+150 = 300。
    ProductionEfficiency.Evaluation first =
        ProductionEfficiency.evaluate(
            null,
            ProductionEffectScope.TOTAL,
            1L,
            LABOR_100_E1505,
            LABOR_100,
            assetScaleRaw,
            inputPerUnit,
            inputUsed,
            1L,
            Long.MAX_VALUE);

    assertThat(first.efficiencyScalePerMille()).isEqualTo(1_505L);
    assertThat(first.finalScale()).as("第 1 周期产出规模").isEqualTo(150L);
    assertThat(first.nextCarry()).as("第 1 周期算出非零余数 ⇒ 必须建行").isNotNull();
    assertThat(first.nextCarry().carry())
        .as("三段余数逐值：劳动两段为 0（周期 1 天、每单位劳动 1）、效率段 500")
        .isEqualTo(new PerCycleCarry(0L, 0L, 500L));

    ProductionEfficiency.Evaluation second =
        ProductionEfficiency.evaluate(
            first.nextCarry(),
            ProductionEffectScope.TOTAL,
            1L,
            LABOR_100_E1505,
            LABOR_100,
            assetScaleRaw,
            inputPerUnit,
            inputUsed,
            1L,
            Long.MAX_VALUE);

    assertThat(second.finalScale()).as("第 2 周期产出规模（吃上期余数 500）").isEqualTo(151L);
    assertThat(second.nextCarry()).as("余数已吃净 ⇒ 回到缺行").isNull();
    assertThat(first.finalScale() + second.finalScale())
        .as("★ 两周期合计 301（逐次截断会永远 300）")
        .isEqualTo(301L);
  }

  // ── 余数有效域：越域 ⇒ 具名契约故障（不截断、不降级）────────────────────────────────────

  @Test
  void anOutOfDomainRemainderIsANamedContractFaultRatherThanASilentTruncation() {
    // 手算：cycleDays=1 ⇒ laborDayRemainderMilli 的有效域是 [0, 1) ⇒ 1 越域；
    //       laborPerUnit=1 ⇒ laborScaleRemainderMilli 的有效域是 [0, 1) ⇒ 1 越域。
    assertThatThrownBy(
            () ->
                ProductionEfficiency.evaluate(
                    new ProductionEfficiencyState(new PerCycleCarry(1L, 0L, 0L)),
                    ProductionEffectScope.TOTAL,
                    1L,
                    LABOR_100_E1500,
                    LABOR_100,
                    200L,
                    Map.of(ProductionTestFixtures.GRAIN, 1L),
                    Map.of(ProductionTestFixtures.GRAIN, 120L),
                    1L,
                    Long.MAX_VALUE))
        .isInstanceOf(ProductionContractFault.class)
        .hasMessageContaining("laborDayRemainderMilli 越域");

    assertThatThrownBy(
            () ->
                ProductionEfficiency.evaluate(
                    new ProductionEfficiencyState(new PerCycleCarry(0L, 1L, 0L)),
                    ProductionEffectScope.TOTAL,
                    1L,
                    LABOR_100_E1500,
                    LABOR_100,
                    200L,
                    Map.of(ProductionTestFixtures.GRAIN, 1L),
                    Map.of(ProductionTestFixtures.GRAIN, 120L),
                    1L,
                    Long.MAX_VALUE))
        .isInstanceOf(ProductionContractFault.class)
        .hasMessageContaining("laborScaleRemainderMilli 越域");
  }
}
