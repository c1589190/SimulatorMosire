package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * {@link EconomySettlement} 的**纯函数**用例（R3a/R4a）：日耗 / 进度 / 劳动累计、未激活不动、以及**分配残差按槽位 id 序补足** （"Σ行得 =
 * 剩余产出"的定点整数保证）。
 *
 * <p>★ 断言值都是这里写下的字面量（手算），不是"再调一遍结算对拍"。
 */
class EconomySettlementTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);

  /** 未激活（meta 空）⇒ 原样返回：一个字都不改（§6.6）。 */
  @Test
  void unactivatedEconomyIsReturnedUnchanged() {
    EconomyData empty = EconomyData.empty();

    assertThat(EconomySettlement.settle(empty, 0L, 1L)).isSameAs(empty);
  }

  /** 日结算：每行扣 人口 × 83 毫粮、progressDays +1、劳动累计加上当日实际劳动；流水记本期发生额。 */
  @Test
  void oneDayConsumesEightyThreePerPersonAdvancesProgressAndRecordsFlows() {
    EconomyData base = fixture();

    EconomyData next = EconomySettlement.settle(base, 0L, 1L);

    // 贫农 100 人：83000 − 8300 = 74700；地主 10 人：8300 − 830 = 7470。
    assertThat(grainOf(next, PEASANT_KEY)).isEqualTo(74_700L);
    assertThat(grainOf(next, LANDLORD_KEY)).isEqualTo(7_470L);
    assertThat(next.industries().get(FARM).progressDays()).as("progressDays +1").isEqualTo(1L);
    assertThat(next.industries().get(FARM).cycleLaborMilli())
        .as("当日实际劳动 = 贫农 58000 × 1000‰ + 地主 5800 × 0‰ = 58000")
        .isEqualTo(58_000L);

    FlowRow peasantFlow = next.flows().get(PEASANT_KEY);
    assertThat(peasantFlow.consumed().get(GRAIN)).as("本期消费 = 8300").isEqualTo(8_300L);
    assertThat(peasantFlow.income()).as("无收获 ⇒ 所得 0").isZero();
    assertThat(peasantFlow.netSurplus()).as("净盈余 = 0 − 8300").isEqualTo(-8_300L);
    assertThat(peasantFlow.taxPaid()).as("v1 不收税").isZero();
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("周期未末 ⇒ 未关账").isEmpty();
  }

  /**
   * ★★ **多日推进（§十一）**：{@code settle(base, 0, 3)} **逐日**跑到第 3 天 ⇒ 周期末（{@code cycleDays = 3}）收获一次；
   * 同一次调用里 3 天的消费逐日扣、第 3 天的收获一次性入账——**终态 == 3 次单日结算**。
   *
   * <p>★ 判别力：若把逐日循环压成"只按 to 结算一天"（把区间当一步），第 2、3 天不消费、也不会在第 3 天收获 ⇒ 字面量与等价性两条一起红。
   */
  @Test
  void multiDaySettlementClosesTheCycleOnTheAbsoluteHarvestDay() {
    EconomyData base = fixture();

    EconomyData next = EconomySettlement.settle(base, 0L, 3L);

    // 贫农：83000 − 3×8300 = 58100，净得 floor(5950×790/1000) = 4700 ⇒ 62800；
    // 地主：8300 − 3×830 = 5810，净得 floor(5950×210/1000) = 1249，**残差 1 按槽位 id 序归 landlord** ⇒ 1250 ⇒
    // 7060。
    // ★ 残差不是"丢"而是"归地主"：Σ净得 = 4700 + 1250 = 5950 = net（守恒）；本仓的残差序是**槽位 id 字典序**。
    assertThat(grainOf(next, PEASANT_KEY)).as("3 天逐日口粮 + 第 3 天分配净得").isEqualTo(62_800L);
    assertThat(grainOf(next, LANDLORD_KEY)).as("3 天逐日口粮 + 第 3 天分配净得（含残差 1）").isEqualTo(7_060L);
    assertThat(grainOf(next, PEASANT_KEY) + grainOf(next, LANDLORD_KEY))
        .as("Σ净得 + 两端日耗 = 基期库存 + net（账要平）")
        .isEqualTo(91_300L - 3L * (8_300L + 830L) + 5_950L);
    assertThat(next.industries().get(FARM).progressDays())
        .as("第 3 天是周期末 ⇒ progressDays 归零")
        .isZero();
    assertThat(next.industries().get(FARM).cycleLaborMilli()).as("周期累计清零").isZero();
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);

    // ★ 等价性（§十一）：一次 3 天 == 3 次单日（同一份终态）。
    EconomyData chained =
        EconomySettlement.settle(
            EconomySettlement.settle(EconomySettlement.settle(base, 0L, 1L), 1L, 2L), 2L, 3L);
    assertThat(next).as("§十一：一次 3 天 == 3 次单日").isEqualTo(chained);
  }

  /**
   * ★ **残差按槽位 id 序补足**（"Σ行得 = 剩余产出"的定点整数保证）：权重和 &lt; 1000 时余下的单位按索引序补齐， 一项不丢。
   *
   * <p>★ 判别力：去掉 {@code distributeResidue}，这两条的 Σ 会小于 total。
   */
  @Test
  void allocateDistributesResidueInSlotOrderKeepingTheTotal() {
    assertThat(EconomySettlement.allocate(1_000L, new long[] {333L, 333L, 333L}))
        .as("Σ权重 = 999 ⇒ 残差 1 落在第一个槽位")
        .containsExactly(334L, 333L, 333L);
    assertThat(EconomySettlement.allocate(7L, new long[] {600L, 400L}))
        .as("7 × 600‰ = 4.2 ⇒ 4、7 × 400‰ = 2.8 ⇒ 2，残差 1 落在第一个槽位")
        .containsExactly(5L, 2L);
    long[] parts = EconomySettlement.allocate(5_950_000L, new long[] {464L, 354L, 144L, 36L});
    assertThat(parts[0] + parts[1] + parts[2] + parts[3])
        .as("Σ行得 == 剩余产出（权重和为 998 ⇒ 残差 11,900 按槽位 id 序补足）")
        .isEqualTo(5_950_000L);
    assertThat(parts)
        .as("残差按索引序轮转摊到四个槽位")
        .containsExactly(2_763_775L, 2_109_275L, 859_775L, 217_175L);
  }

  // ── 夹具：一格、一个农业产业（3 天周期）、贫农 + 地主两行 ─────────────────────────────

  private static EconomyData fixture() {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 0));
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            3L,
            0L,
            Map.of(),
            0L,
            Map.of(GRAIN, 7L),
            slots,
            new AllocationRule.Split(700, 300),
            0L);
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, row(PEASANT_KEY, 100L, 58_000L, 1000, 700L, 83_000L));
    classes.put(LANDLORD_KEY, row(LANDLORD_KEY, 10L, 5_800L, 0, 300L, 8_300L));
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyData(Optional.of(meta), industries, classes, Map.of(), Map.of());
  }

  private static ClassRow row(
      ClassKey key, long population, long laborMilli, int participation, long land, long goods) {
    return new ClassRow(
        key,
        population,
        laborMilli,
        participation,
        Map.of(AssetKind.LAND, land),
        Map.of(GRAIN, goods),
        0L,
        List.of(),
        Map.of(GRAIN, population * 83L),
        Map.of());
  }

  private static long grainOf(EconomyData data, ClassKey key) {
    return data.classes().get(key).goods().getOrDefault(GRAIN, 0L);
  }
}
