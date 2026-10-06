package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.ProductionEfficiencyState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>§6.3 黄金用例 / 余数不变量（Z4 必测）</b>：本类只测 {@link ProductionEfficiencyBook} 的纯函数面 —— 「中性逐值 ==
 * 旧口径」「`D=1,lpu=1,cap=3,L=3` 两周期 `m=500` ⇒ 1+2=3」「半周期 500/1500 差 ≤1 规模单位」 「五个余数始终在域、写回周期和 0」「越域 ⇒
 * ERROR + {@link IllegalStateException}」。
 *
 * <p>★ 旧口径 {@code scaleOf} 在 Z2 已删除，故这里把它的算式**逐字重写**成 {@link #legacyScaleOf} 作对照（不引用被测物，避免自证）。 「缺行
 * = 全 1000‰ + 全 0 余数」由 {@code state == null} 表达。
 *
 * <p>★ 契约 ERROR 的断言：economy 模块测试类路径没有日志绑定（AGENTS §一.9 的日志断言在 app 侧做），故本类断言 fail-closed 语义（{@link
 * IllegalStateException} + 稳定消息前缀）；ERROR 行本身由 {@code ProductionEfficiencyBook}
 * 的唯一发射点保证，见台账「未验证/未做」节。
 */
class ProductionEfficiencyBookTest {

  private static final CommodityId GRAIN = new CommodityId("grain");

  /**
   * ★ <b>§6.3.1 中性逐值 == 旧口径</b>：{@code state == null}（缺行 = 全 0 余数 + 本周期全 1000‰）下， {@code
   * avgModifier == 1000}、{@code scaleBase == 旧 min 三路口径}、{@code scale == scaleBase} 逐值相同。
   */
  @Test
  void neutralRunMatchesTheLegacyScaleOfValueByValue() {
    for (LegacyCase legacyCase : cases()) {
      ProductionEfficiencyBook.HarvestEvaluation evaluation =
          ProductionEfficiencyBook.evaluateHarvest(
              null,
              legacyCase.cycleDays(),
              legacyCase.laborMilli(),
              legacyCase.capacityScale(),
              legacyCase.plannedPerMille(),
              legacyCase.inputPerUnit(),
              legacyCase.cycleInputUsedMilli(),
              legacyCase.laborPerUnit());

      long legacy = legacyScaleOf(legacyCase);
      assertThat(evaluation.scaleBase())
          .as("中性 case %s：scaleBase 与旧 min 口径逐值相同", legacyCase)
          .isEqualTo(legacy);
      assertThat(evaluation.scale())
          .as("中性 case %s：修饰参数缺席 = 1000‰ ⇒ scale == scaleBase", legacyCase)
          .isEqualTo(legacy);
      assertThat(evaluation.avgModifierPerMille()).as("缺行 = 全 1000‰").isEqualTo(1000L);
      assertThat(evaluation.laborPerUnitZero()).isEqualTo(legacyCase.laborPerUnit() <= 0L);
      assertThat(evaluation.modifierEffective()).isEqualTo(legacyCase.laborPerUnit() > 0L);
    }
  }

  /**
   * ★★ <b>§6.3.2 黄金口算</b>：`D=1, lpu=1, cap=3, planned=1000, L=3`，逐 tick `m=500`：
   *
   * <pre>
   * 周期 1：avgModifier=500 ⇒ n4 = 3×500 + 0   = 1500 ⇒ scale=1、scaleRemainder=500
   * 周期 2：avgModifier=500 ⇒ n4 = 3×500 + 500 = 2000 ⇒ scale=2、scaleRemainder=0
   * 合计 1 + 2 = 3（不是 2 —— 余数结转的正面证据）
   * </pre>
   */
  @Test
  void goldenTwoCyclesAccumulateOnePlusTwo() {
    ProductionEfficiencyState cycle1State = ProductionEfficiencyBook.accrueTick(null, 500L, 0L);
    ProductionEfficiencyBook.HarvestEvaluation cycle1 =
        ProductionEfficiencyBook.evaluateHarvest(
            cycle1State, 1L, 3L, 3L, 1000L, Map.of(), Map.of(), 1L);

    assertThat(cycle1.avgModifierPerMille()).isEqualTo(500L);
    assertThat(cycle1.scaleBase()).isEqualTo(3L);
    assertThat(cycle1.scale()).as("周期 1：3×500/1000 = 1").isEqualTo(1L);
    assertThat(cycle1.scaleRemainderMilli()).as("余数 500 跨周期保留").isEqualTo(500L);
    assertThat(cycle1.nextState()).isNotNull();
    assertThat(cycle1.nextState().cycleModifierSumPerMille()).as("§6.2⑦ 周期末清零").isZero();

    ProductionEfficiencyState cycle2State =
        ProductionEfficiencyBook.accrueTick(cycle1.nextState(), 500L, 0L);
    ProductionEfficiencyBook.HarvestEvaluation cycle2 =
        ProductionEfficiencyBook.evaluateHarvest(
            cycle2State, 1L, 3L, 3L, 1000L, Map.of(), Map.of(), 1L);

    assertThat(cycle2.scale()).as("周期 2：3×500 + 500 ⇒ 2").isEqualTo(2L);
    assertThat(cycle2.scaleRemainderMilli()).isZero();
    assertThat(cycle1.scale() + cycle2.scale())
        .as("§6.3.2：Σ scale = 3 = floor(1500/1000) + floor(2000/1000)，无系统性丢精度")
        .isEqualTo(3L);
  }

  /**
   * ★ <b>§6.3.3 半周期差异上界</b>：既有行整周期全 1000‰ vs 前半 `⌊D/2⌋` 天 500、后半 1500（Σm_t 差一个 tick）， 以及缺行 + 前
   * `D-2` 天中性（不建行）+ 最后两天 500/1500 的补种路径：`scaleBase=3` 下规模差 ≤1。
   */
  @Test
  void halfPeriodModifierSplitStaysWithinOneScaleUnit() {
    for (long days : new long[] {2L, 3L, 4L, 120L, 121L}) {
      long labor = 3L * days; // lpu=1 ⇒ laborScale=3；capPlanned=3 ⇒ scaleBase=3
      ProductionEfficiencyBook.HarvestEvaluation neutral =
          ProductionEfficiencyBook.evaluateHarvest(
              accrue(repeat(1000L, (int) days)), days, labor, 3L, 1000L, Map.of(), Map.of(), 1L);
      ProductionEfficiencyBook.HarvestEvaluation split =
          ProductionEfficiencyBook.evaluateHarvest(
              accrue(halfPeriodSequence(days)), days, labor, 3L, 1000L, Map.of(), Map.of(), 1L);
      ProductionEfficiencyBook.HarvestEvaluation materialized =
          ProductionEfficiencyBook.evaluateHarvest(
              accrue(lateModifierSequence(days)), days, labor, 3L, 1000L, Map.of(), Map.of(), 1L);

      assertThat(Math.abs(split.scale() - neutral.scale()))
          .as("D=%d：半周期 500/1500 与全中性差 ≤1 规模单位", days)
          .isLessThanOrEqualTo(1L);
      assertThat(materialized.scale())
          .as("D=%d：缺行补种（前 D-2 天中性不建行、最后两天 500/1500）与缺行中性逐值相同", days)
          .isEqualTo(neutral.scale());
    }
  }

  /**
   * ★ <b>§6.3.4 五个余数始终在域、写回周期和 0</b>：在 `D × lpu × m` 的组合（含逐 tick 变动、`lpu==0`）上逐例断言 `nextState`
   * 的有效域与 `cycleModifierSumPerMille == 0`。
   */
  @Test
  void remaindersStayInDomainAndTheCycleSumIsCleared() {
    long[] daysTable = {1L, 2L, 3L, 7L, 120L, 121L};
    long[] laborPerUnitTable = {0L, 1L, 7L, 120L};
    long[] modifierTable = {0L, 500L, 1000L, 1500L, 2000L};
    for (long days : daysTable) {
      for (long laborPerUnit : laborPerUnitTable) {
        for (long modifier : modifierTable) {
          ProductionEfficiencyState state = ProductionEfficiencyBook.accrueTick(null, modifier, 0L);
          ProductionEfficiencyBook.HarvestEvaluation evaluation =
              ProductionEfficiencyBook.evaluateHarvest(
                  state, days, 500L, 10L, 1000L, Map.of(), Map.of(), laborPerUnit);

          assertThat(evaluation.modifierRemainderMilli()).isBetween(0L, days - 1L);
          assertThat(evaluation.laborDayRemainderMilli()).isBetween(0L, days - 1L);
          if (laborPerUnit > 0L) {
            assertThat(evaluation.laborScaleRemainderMilli()).isBetween(0L, laborPerUnit - 1L);
          } else {
            assertThat(evaluation.laborScaleRemainderMilli()).as("lpu==0 ⇒ 域为空、唯一合法值 0").isZero();
          }
          assertThat(evaluation.scaleRemainderMilli()).isBetween(0L, 999L);
          assertThat(evaluation.satisfactionPerMille()).isBetween(0L, 1000L);

          ProductionEfficiencyState next = evaluation.nextState();
          if (next != null) {
            assertThat(next.cycleModifierSumPerMille()).as("周期末消费后清账").isZero();
            assertThat(next.modifierRemainderMilli()).isBetween(0L, days - 1L);
            assertThat(next.laborDayRemainderMilli()).isBetween(0L, days - 1L);
            if (laborPerUnit > 0L) {
              assertThat(next.laborScaleRemainderMilli()).isBetween(0L, laborPerUnit - 1L);
            } else {
              assertThat(next.laborScaleRemainderMilli()).isZero();
            }
            assertThat(next.scaleRemainderMilli()).isBetween(0L, 999L);
          }
        }
      }
    }
  }

  /**
   * ★ <b>§6.3.4 / §8 契约故障 fail-closed</b>：越域余数与非法入参一律 {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR
   * + {@link IllegalStateException}，绝不"带着坏值继续算"。
   */
  @Test
  void outOfDomainStateOrTickFailsClosed() {
    assertThatThrownBy(() -> ProductionEfficiencyBook.accrueTick(null, 2001L, 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(() -> ProductionEfficiencyBook.accrueTick(null, -1L, 0L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(() -> ProductionEfficiencyBook.accrueTick(null, 1000L, -1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");

    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    null, 0L, 1L, 1L, 1000L, Map.of(), Map.of(), 1L))
        .as("cycleDays 必须 ≥ 1")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    null, 2L, -1L, 1L, 1000L, Map.of(), Map.of(), 1L))
        .as("cycleLaborMilli 不得为负")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");

    // 两个 < D 的余数各自越域（构造期只判 ≥0，有效域在公式使用时 fail-closed）
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    new ProductionEfficiencyState(0L, 2L, 0L, 0L, 0L),
                    2L,
                    1L,
                    1L,
                    1000L,
                    Map.of(),
                    Map.of(),
                    1L))
        .as("modifierRemainderMilli == cycleDays ⇒ 越域")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    new ProductionEfficiencyState(0L, 0L, 2L, 0L, 0L),
                    2L,
                    1L,
                    1L,
                    1000L,
                    Map.of(),
                    Map.of(),
                    1L))
        .as("laborDayRemainderMilli == cycleDays ⇒ 越域")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    new ProductionEfficiencyState(0L, 0L, 0L, 3L, 0L),
                    2L,
                    1L,
                    1L,
                    1000L,
                    Map.of(),
                    Map.of(),
                    3L))
        .as("laborScaleRemainderMilli == laborPerUnit ⇒ 越域")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    new ProductionEfficiencyState(0L, 0L, 0L, 0L, 1000L),
                    2L,
                    1L,
                    1L,
                    1000L,
                    Map.of(),
                    Map.of(),
                    1L))
        .as("scaleRemainderMilli == 1000 ⇒ 越域")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
    assertThatThrownBy(
            () ->
                ProductionEfficiencyBook.evaluateHarvest(
                    new ProductionEfficiencyState(0L, 0L, 0L, 1L, 0L),
                    2L,
                    1L,
                    1L,
                    1000L,
                    Map.of(),
                    Map.of(),
                    0L))
        .as("lpu==0 时 laborScaleRemainderMilli 必须为 0")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("生产效率契约违约");
  }

  // ── 夹具与旧口径对照 ──────────────────────────────────────────────────────────────
  /** 旧 {@code scaleOf} 的算式逐字重写（min(劳动链, 计划产能, 逐投入)）；只服务"中性逐值 == 旧口径"。 */
  private static long legacyScaleOf(LegacyCase legacyCase) {
    long scale = Long.MAX_VALUE;
    long capPlanned = legacyCase.capacityScale() * legacyCase.plannedPerMille() / 1000L;
    scale = Math.min(scale, capPlanned);
    if (legacyCase.laborPerUnit() > 0L) {
      long avgLabor = legacyCase.laborMilli() / legacyCase.cycleDays();
      scale = Math.min(scale, avgLabor / legacyCase.laborPerUnit());
    }
    for (Map.Entry<CommodityId, Long> entry : legacyCase.inputPerUnit().entrySet()) {
      Long perUnit = entry.getValue();
      if (perUnit == null || perUnit <= 0L) {
        continue;
      }
      long drawn = legacyCase.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, drawn / perUnit);
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  private static java.util.List<LegacyCase> cases() {
    return java.util.List.of(
        new LegacyCase(120L, 300_000L, 10L, 1000L, 7L, Map.of(), Map.of()),
        new LegacyCase(120L, 300_000L, 10L, 500L, 7L, Map.of(), Map.of()),
        new LegacyCase(120L, 0L, 10L, 1000L, 7L, Map.of(), Map.of()),
        new LegacyCase(1L, 3L, 3L, 1000L, 1L, Map.of(), Map.of()),
        new LegacyCase(120L, 90_000L, 4L, 1000L, 1L, Map.of(GRAIN, 2L), Map.of(GRAIN, 5L)),
        new LegacyCase(120L, 90_000L, 4L, 1000L, 1L, Map.of(GRAIN, 2L), Map.of()),
        new LegacyCase(120L, 90_000L, 4L, 1000L, 0L, Map.of(GRAIN, 2L), Map.of(GRAIN, 7L)),
        new LegacyCase(120L, 90_000L, 0L, 1000L, 7L, Map.of(), Map.of()),
        new LegacyCase(7L, 1_000L, 100L, 0L, 3L, Map.of(), Map.of()));
  }

  private record LegacyCase(
      long cycleDays,
      long laborMilli,
      long capacityScale,
      long plannedPerMille,
      long laborPerUnit,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> cycleInputUsedMilli) {

    @Override
    public String toString() {
      return "D="
          + cycleDays
          + ",L="
          + laborMilli
          + ",cap="
          + capacityScale
          + ",planned="
          + plannedPerMille
          + ",lpu="
          + laborPerUnit
          + ",inputs="
          + inputPerUnit
          + ",used="
          + cycleInputUsedMilli;
    }
  }

  /** 逐 tick 累进一串修正；每 tick 的 {@code elapsedCycleDays} = 本 tick 前的天数。 */
  private static ProductionEfficiencyState accrue(java.util.List<Long> modifiers) {
    ProductionEfficiencyState state = null;
    for (int i = 0; i < modifiers.size(); i++) {
      state = ProductionEfficiencyBook.accrueTick(state, modifiers.get(i), i);
    }
    return state;
  }

  private static java.util.List<Long> repeat(long value, int times) {
    java.util.List<Long> out = new java.util.ArrayList<>();
    for (int i = 0; i < times; i++) {
      out.add(value);
    }
    return out;
  }

  /** 前半 `⌊D/2⌋` 天 500、其余 1500（逐 tick 注入序列）。 */
  private static java.util.List<Long> halfPeriodSequence(long days) {
    java.util.List<Long> out = new java.util.ArrayList<>();
    long half = days / 2L;
    for (int i = 0; i < days; i++) {
      out.add(i < half ? 500L : 1500L);
    }
    return out;
  }

  /** 缺行 + 前 D-2 天中性（{@code accrueTick} 保持 null）+ 最后两天 500/1500（物化补种路径）。 */
  private static java.util.List<Long> lateModifierSequence(long days) {
    java.util.List<Long> out = new java.util.ArrayList<>(repeat(1000L, (int) days - 2));
    out.add(500L);
    out.add(1500L);
    return out;
  }
}
