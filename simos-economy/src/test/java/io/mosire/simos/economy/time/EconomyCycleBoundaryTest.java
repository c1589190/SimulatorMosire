package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **周期边界**（v2 spec §八.3 + §3.1）：{@code progressDays == cycleDays} 是**合法值**（闭区间上界），
 * 结算必须把它当"周期已满、待收获"处理，**不是**抛。
 *
 * <p><b>病灶</b>：v1 用 {@code progressed == cycleDays} 判收获、否则 {@code nextProgress = progressed} ⇒
 * 该状态下次日构造出 {@code cycleDays + 1}，在 {@code Industry} 构造期抛 IAE，异常穿出 {@code
 * EconomyTimeParticipant.simulateWorld} ⇒ **整条推进 revision 失败**。
 *
 * <p>★ <b>判别力</b>：把判据改回 {@code ==} ⇒ 本类第一条抛 IAE ⇒ 红。
 *
 * <p>★ 量纲与常数：土地 3,100 亩/格、亩产 67 粮/亩（v2 spec §10.3 定案 A）；1 粮 = 1000 毫粮。
 */
class EconomyCycleBoundaryTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final long CYCLE_DAYS = 120L;
  private static final long LAND_MILLI_MU = 3_100_000L; // 3,100 亩（千分亩）
  private static final long GRAIN_PER_MU = 67L;

  /** 夹具：一个"周期已满"的产业（{@code progressDays == cycleDays}），一行 100 人， 周期累计劳动已按整周期记满（供收获算平均日劳动）。 */
  private static EconomyData fullCycleFixture() {
    long dailyLabor = 58_000L * 950L / 1000L; // 有效劳动 58,000 千分劳动 × 投入率 950‰
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            CYCLE_DAYS, // ★ 边界值：周期已满（合法，且 v1 会在此炸）
            Map.of(),
            0L,
            Map.of(EconomySettlement.GRAIN, GRAIN_PER_MU),
            List.of(new ClassSlot(PEASANT, "贫农", 950)),
            new AllocationRule.Split(700, 300),
            dailyLabor * CYCLE_DAYS); // 周期累计劳动
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            100L,
            58_000L,
            950,
            Map.of(AssetKind.LAND, LAND_MILLI_MU),
            Map.of(EconomySettlement.GRAIN, 10_000_000L), // 10,000 粮
            0L,
            List.of(),
            Map.of(),
            Map.of());
    return new EconomyData(
        Optional.of(new EconomyMeta("m1", 0L, OptionalLong.empty(), "v2", Optional.empty())),
        Map.of(FARM, farm),
        Map.of(PEASANT_KEY, row),
        Map.of(),
        Map.of());
  }

  @Test
  void aFullCycleHarvestsInsteadOfThrowing() {
    EconomyData next = EconomySettlement.settle(fullCycleFixture(), 0L, 1L);

    assertThat(next.industries().get(FARM).progressDays()).as("周期已满 ⇒ 收获并归零").isZero();
    assertThat(next.industries().get(FARM).cycleLaborMilli()).as("周期累计清零").isZero();
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);

    // 算账（全部整数，量纲：毫粮）：
    //   日劳动    = 58,000 × 950‰ = 55,100 千分劳动
    //   周期累计  = 55,100 × 120 = 6,612,000；收获当天再 +55,100 ⇒ cycledLabor = 6,667,100
    //   平均日劳动 = 6,667,100 ÷ 120 = 55,559（向下取整）
    //   劳动可经营 = 55,559 × 7 亩/劳动 ÷ 1000 = 388 亩（**劳动是最短那块**，地有 3,100 亩）
    //   毛产      = 388 × 67 × 1000 = 25,996,000 毫粮
    //   生产消耗  = 25,996,000 × 150‰ = 3,899,400 ⇒ 净 22,096,600
    //   单行 ⇒ Split 权重 = (700×1000 + 300×1000) ÷ 1000 = 1000 ⇒ 全部归它
    //   库存      = 10,000,000 − 当日口粮 8,300 + 22,096,600 = 32,088,300
    assertThat(next.classes().get(PEASANT_KEY).goods().get(EconomySettlement.GRAIN))
        .as("吃一天 + 收获一次后的粮库存（毫粮）")
        .isEqualTo(32_088_300L);
  }

  @Test
  void aOneDayCycleHarvestsOnItsVeryFirstDayWithoutDividingByZero() {
    // Review Focus 第 8 条：cycleDays == 1 ⇒ avgLaborMilli = cycledLabor / 1，且当天即满足 progressed >=
    // cycleDays
    EconomyData base = withCycleDays(1L);
    EconomyData next = EconomySettlement.settle(base, 0L, 1L);
    assertThat(next.industries().get(FARM).progressDays()).as("1 天周期：当天就收获并归零（且不许除零）").isZero();
  }

  @Test
  void aZeroPopulationHexProducesNothingAndDoesNotDivideByZero() {
    // Review Focus 第 3 条：农村人口为 0 的纯城市格（无劳动 ⇒ 投入面积 0 ⇒ 不造粮）
    EconomyData base = zeroPopulationFixture();
    EconomyData next = EconomySettlement.settle(base, 0L, 1L);
    assertThat(next.classes().get(PEASANT_KEY).goods()).as("无劳动 ⇒ 投入面积 0 ⇒ 不造粮（也不许除零）").isEmpty();
  }

  /** 换 `cycleDays` 与 `progressDays`（其余照 {@link #fullCycleFixture()}）。 */
  private static EconomyData withCycleDays(long cycleDays) {
    EconomyData base = fullCycleFixture();
    Industry farm = base.industries().get(FARM);
    return base.withIndustries(
        Map.of(
            FARM,
            new Industry(
                farm.id(),
                farm.name(),
                farm.regime(),
                cycleDays,
                cycleDays,
                farm.dailyInputPerUnit(),
                farm.dailyLaborPerUnit(),
                farm.outputPerUnit(),
                farm.slots(),
                farm.allocation(),
                farm.cycleLaborMilli())));
  }

  /**
   * 无人口、无劳动、无库存，**且周期累计劳动也为 0** 的格（纯城市格 / 空地）。
   *
   * <p>★ 为什么必须连 {@code cycleLaborMilli}（周期累计劳动）一起清零：**它才是"本周期实际投了多少劳动" 的权威记录**（挂在产业上的累加器），行里的
   * {@code laborMilli} 只喂"当日增量"。 只清行不清累加器 ⇒ 已记为投下的劳动不会被抹掉，照样有产出 —— 那是**正确行为**，不是 bug
   * （本用例第一版就踩了这个前提：期望"无人口 ⇒ 不产粮"，实测产了 31,925,750 毫粮）。
   */
  private static EconomyData zeroPopulationFixture() {
    EconomyData base = fullCycleFixture();
    Industry farm = base.industries().get(FARM);
    ClassRow row = base.classes().get(PEASANT_KEY);
    return base.withIndustries(
            Map.of(
                FARM,
                new Industry(
                    farm.id(),
                    farm.name(),
                    farm.regime(),
                    farm.cycleDays(),
                    farm.progressDays(),
                    farm.dailyInputPerUnit(),
                    farm.dailyLaborPerUnit(),
                    farm.outputPerUnit(),
                    farm.slots(),
                    farm.allocation(),
                    0L))) // ★ 周期累计劳动清零
        .withClasses(
            Map.of(
                PEASANT_KEY,
                new ClassRow(
                    PEASANT_KEY,
                    0L, // 人口
                    0L, // 有效劳动
                    row.participationPerMille(),
                    row.meansOfProduction(),
                    Map.of(), // 库存清空 ⇒ "没凭空造粮"这条断言才有判别力
                    row.money(),
                    row.debts(),
                    row.naturalNeeds(),
                    row.effectiveDemand())));
  }
}
