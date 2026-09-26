package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **V5：本期流水按周期清零 + 多日口粮残差不丢**（v2 spec §八.5/§八.6 的可执行判据）。
 *
 * <p>夹具（一格、一个农业产业、一行贫农）：周期 {@value #CYCLE_DAYS} 天（真档周期）、人口 {@value #POPULATION}、 有效劳动 {@value
 * #LABOR_MILLI}（投入率 1000‰）、地 {@value #LAND_MU} 亩（**土地是瓶颈**：劳动可经营 {@code 58,000 ÷ 每亩需劳动 143 = 405 亩}
 * &gt; 300 亩；★ R3 前那条式子写作 {@code 58,000 × 7 ÷ 1000 = 406}，143 = ⌈1000/7⌉ 就是它的倒数形式）、亩产 {@value
 * #YIELD_PER_MU} 粮/亩、**不配投入**（本文件只验流水与口粮）。
 *
 * <pre>
 * 一周期毛产 = 300 亩 × 67 粮/亩 × 1000 = 20,100,000 毫粮（单行 ⇒ 全归它）
 * 生产损耗   = 20,100,000 × (饲料 0‰ + 折旧 30‰) ÷ 1000 = 603,000 ⇒ 净 19,497,000
 * 一周期口粮 = cumulativeRationMilli(100, 120) = 100 × 10,000 = 1,000,000 毫粮（**精确**，残差一分不丢）
 * </pre>
 *
 * <p>★★ **清零的判据（§八.5）**：清零点必须在**新周期第一天**（{@code progressDays == 0}），不在关账那一支 ——
 * 关账那一支自己产生本周期最大的一笔所得（收获的毛产分配），在那里清零会让关账日读到 {@code income == 0}。
 */
class EconomyFlowCycleTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);

  /** 真档周期（天）—— 本文件的所有"周期"判据都要跨过它。 */
  private static final long CYCLE_DAYS = 120L;

  private static final long POPULATION = 100L;
  private static final long LABOR_MILLI = 58_000L;

  /** 一行的地（千分亩）= 300 亩 ⇒ 土地是瓶颈（劳动可经营 406 亩 &gt; 300 亩）。 */
  private static final long LAND_MILLI_MU = 300_000L;

  private static final long YIELD_PER_MU = 67L;

  /** 一行的缸（毫粮）：够 240 天口粮（2,000,000）还剩得多 ⇒ 本文件里"缺口/饿死"都不是变量。 */
  private static final long JAR = 3_000_000L;

  /** ★ R2 的夹具：本文件的配额都挂在同一个批次上（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");

  /** ★ 创世配额的发放周期（与 {@code EconomySeeder.FIRST_PERIOD} 同值：周期序号从 1 起）。 */
  private static final long FIRST_PERIOD = 1L;

  /** 一整个周期的毛产（毫粮）= 实际投入亩 × 亩产 × 1000（单行 ⇒ 流水所得 = 毛产）。 */
  private static final long CYCLE_GROSS =
      (LAND_MILLI_MU / 1000L) * YIELD_PER_MU * EconomySettlement.MILLI_PER_GRAIN;

  private static final long PRODUCTION_LOSS_PER_MILLE =
      EconomySettlement.FEED_PER_MILLE + EconomySettlement.DEPRECIATION_PER_MILLE;

  /** 一整个周期的**净**产（毫粮）= 毛产 × (1000 − 损耗) ÷ 1000。 */
  private static final long CYCLE_NET = CYCLE_GROSS * (1000L - PRODUCTION_LOSS_PER_MILLE) / 1000L;

  /** 一整个周期的口粮（毫粮）—— 口径的整数倍，**精确**。 */
  private static final long CYCLE_RATION =
      EconomyVocabulary.cumulativeRationMilli(POPULATION, CYCLE_DAYS);

  // ── ① 关账日读得到整周期、次日从 0 起（§八.5）──────────────────────────────────────────

  /**
   * ★★ **关账那一支不清零**：第 {@value #CYCLE_DAYS} 天（周期末）读到的 {@code income} 是**一个**周期的毛产（含那天收获的分配）， 不是
   * 0、也不是两个周期。
   *
   * <p>★ 判别力：把清零挪进 {@code progressed >= cycleDays} 那一支（控制器骨架的原写法）⇒ 这里读到 {@code income == 0} ⇒ 本条红。
   */
  @Test
  void theClosingDayCarriesTheWholeCyclesIncome() {
    EconomyData closeOfCycleOne = EconomySettlement.settle(fixture(), 0L, CYCLE_DAYS);

    FlowRow flow = closeOfCycleOne.flows().get(PEASANT_KEY);
    assertThat(flow.income().get(EconomySettlement.GRAIN))
        .as("关账日 = 一个周期的毛产（那次收获的分配）")
        .isEqualTo(CYCLE_GROSS);
    assertThat(flow.income().get(EconomySettlement.GRAIN))
        .as("★ 不是 0（在关账那一支清零会让收获当场消失）")
        .isNotZero();
    assertThat(flow.consumed().get(EconomySettlement.GRAIN))
        .as("本期消费 = 一周期口粮 + 本期生产损耗份额")
        .isEqualTo(CYCLE_RATION + (CYCLE_GROSS - CYCLE_NET));
    assertThat(closeOfCycleOne.industries().get(FARM).progressDays()).as("关账后进度归零").isZero();
    assertThat(closeOfCycleOne.meta().orElseThrow().lastClosedCycle()).hasValue(1L);

    EconomyData closeOfCycleTwo =
        EconomySettlement.settle(closeOfCycleOne, CYCLE_DAYS, 2L * CYCLE_DAYS);

    FlowRow second = closeOfCycleTwo.flows().get(PEASANT_KEY);
    assertThat(second.income().get(EconomySettlement.GRAIN))
        .as("★ 第 2 个关账日仍是**一个**周期的量（不是两个周期的累计）")
        .isEqualTo(CYCLE_GROSS);
    assertThat(closeOfCycleTwo.meta().orElseThrow().lastClosedCycle()).hasValue(2L);
  }

  /** ★★ **新周期第一天整行从 0 重记**（清零点在这里，不在关账那一支）：关账日之后的**次日**读到的本期字段全部只含这一天。 */
  @Test
  void theFirstDayOfANewCycleStartsEveryFieldFromZero() {
    EconomyData closed = EconomySettlement.settle(fixture(), 0L, CYCLE_DAYS);
    EconomyData nextDay = EconomySettlement.settle(closed, CYCLE_DAYS, CYCLE_DAYS + 1L);

    FlowRow flow = nextDay.flows().get(PEASANT_KEY);
    assertThat(nextDay.industries().get(FARM).progressDays())
        .as("第 121 天推进完 ⇒ 进度 1（进入新周期；进入时它是 0，故该行流水在**这一天之内**已被清零）")
        .isEqualTo(1L);
    assertThat(nextDay.industries().get(FARM).cycleLaborMilli())
        .as("新周期的劳动累计从这一天的 58,000 起（上周期那 6,960,000 已清零）")
        .isEqualTo(58_000L);
    assertThat(flow.income().getOrDefault(EconomySettlement.GRAIN, 0L))
        .as("★ 上周期那笔收获已归档，本期所得从 0 起")
        .isZero();
    assertThat(flow.consumed().get(EconomySettlement.GRAIN))
        .as("本期消费只有第 %d 天的口粮（上周期的 1,603,000 已清零）", CYCLE_DAYS + 1L)
        .isEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, CYCLE_DAYS + 1L));
    assertThat(flow.netSurplus())
        .as("本期净盈余 = 0 − 当日口粮")
        .isEqualTo(-EconomyVocabulary.dailyRationMilli(POPULATION, CYCLE_DAYS + 1L));
    assertThat(flow.unmetNeed().getOrDefault(EconomySettlement.GRAIN, 0L))
        .as("本期**粮**的缺口从 0 起（上周期没缺口）—— ★ R4 起 unmetNeed 逐商品，本条只说粮那一维")
        .isZero();
    assertThat(flow.unmetNeed().getOrDefault(EconomySettlement.CLOTH, 0L))
        .as("★★ R4（T2）：**布也真的被消费** —— 这张表里多出来的那一维非 0（该夹具不产布；R3 时它根本不在表里）")
        .isPositive();
    assertThat(flow.deaths()).as("本期死亡从 0 起；致死率 0‰ ⇒ 恒 0").isZero();
    assertThat(flow.births()).as("★ R4：出生与死亡**对称**（本期出生从 0 起）").isZero();
  }

  /**
   * ★★ **§八.6：一整个周期的 Σ 日耗 == 人口 × 10,000 毫粮，精确、不丢**（旧口径"每人每日 83"会少 0.4%）。
   *
   * <p>判据落在**结算**这一层：流水里的本期消费 = 一周期口粮 + 本期生产损耗份额（口粮那一项精确等于口径的整数倍）。 逐日差分本身的代数和性质另由 {@code
   * EconomyVocabularyTest} 钉住。
   */
  @Test
  void oneCyclesRationIsExactlyThePopulationTimesTenThousand() {
    EconomyData closed = EconomySettlement.settle(fixture(), 0L, CYCLE_DAYS);

    long ration =
        closed.flows().get(PEASANT_KEY).consumed().get(EconomySettlement.GRAIN)
            - (CYCLE_GROSS - CYCLE_NET); // 减掉本期生产损耗那一份额，剩下的就是口粮
    assertThat(ration)
        .as("一周期 Σ 日耗 == 人口 × 10,000 毫粮（精确；旧的 83 口径会给出 9,960 × 人口）")
        .isEqualTo(POPULATION * 10_000L);
    assertThat(ration)
        .as("★ 判别力：与旧口径（人口 × 83 × 120）必须不同")
        .isNotEqualTo(POPULATION * 83L * CYCLE_DAYS);
  }

  /** ★★ **§八.8：结算把当日需求写进 {@code ClassRow.naturalNeeds}**（读口与结算同源）—— 且**每天**都跟着走， 不是创世写一次就不动。 */
  @Test
  void settlementRewritesTheDailyNaturalNeedOnEveryDay() {
    EconomyData day1 = EconomySettlement.settle(fixture(), 0L, 1L);
    EconomyData day2 = EconomySettlement.settle(day1, 1L, 2L);

    assertThat(day1.classes().get(PEASANT_KEY).naturalNeeds().get(EconomySettlement.GRAIN))
        .as("第 1 天的需求")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, 1L));
    assertThat(day2.classes().get(PEASANT_KEY).naturalNeeds().get(EconomySettlement.GRAIN))
        .as("★ 第 2 天的需求（与第 1 天**不同**：累计差分在第 2 天给出另一个数 ⇒ 这条有内容，不是恒真）")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, 2L));
    assertThat(EconomyVocabulary.dailyRationMilli(POPULATION, 2L))
        .as("前两天口粮相等（10,000 ÷ 120 = 83.33 ⇒ 第 1、2 天各 83）⇒ 用第 3 天验'逐日不同'")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, 1L));
    EconomyData day3 = EconomySettlement.settle(day2, 2L, 3L);
    assertThat(day3.classes().get(PEASANT_KEY).naturalNeeds().get(EconomySettlement.GRAIN))
        .as("第 3 天的需求（= 25,000 − 16,666 = 8,334，比前两天多 1）")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, 3L));
    assertThat(EconomyVocabulary.dailyRationMilli(POPULATION, 3L))
        .as("★ 判别力：第 3 天必须与前两天不同（否则'天天覆写'与'写一次就不动'分不出来）")
        .isNotEqualTo(EconomyVocabulary.dailyRationMilli(POPULATION, 2L));
  }

  // ── ② §十一 等价性：一次 N 天 == N 次单日（清零不得破坏它）────────────────────────────

  /**
   * ★★ **等价性护栏（§十一）**：一次推 {@code 2 × CYCLE_DAYS = 240} 天 == 240 次单日推 ⇒ **终态逐值相同**（含流水与 meta）。
   *
   * <p>★ 为什么特意跨**两个**周期末：清零是周期末的确定性操作，且"关账日归档、次日清零"两件事都发生在边界上 ——
   * 一次性大跨越必须在同一处清同一批字段。判别力：把清零点挂到"调用边界"（每次 {@code settle} 开头清一次）⇒ 240 次单日那条会清 240 次而一次大跨越只清 1 次 ⇒
   * 两份终态不等。
   */
  @Test
  void twoHundredFortyDaysInOneCallEqualsTwoHundredFortySingleDays() {
    EconomyData base = fixture();
    long total = 2L * CYCLE_DAYS;

    EconomyData once = EconomySettlement.settle(base, 0L, total);
    EconomyData daily = base;
    for (long day = 1L; day <= total; day++) {
      daily = EconomySettlement.settle(daily, day - 1L, day);
    }

    assertThat(once).as("§十一：一次 240 天的终态 == 240 次单日").isEqualTo(daily);
    assertThat(once.flows()).as("流水也逐值相同（含两次周期清零）").isEqualTo(daily.flows());
    assertThat(once.flows().get(PEASANT_KEY).income().get(EconomySettlement.GRAIN))
        .as("非平凡：终态落在一个周期末 ⇒ 所得是一个周期的量（不是 0，也不是 240 天的全部）")
        .isEqualTo(CYCLE_GROSS);
  }

  /** 一份经济状态：一格、一个农业产业（周期 {@value #CYCLE_DAYS} 天）、一行贫农（缸 {@value #JAR}、**不配种子**）。 */
  private static EconomyData fixture() {
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            0L,
            // ★ R3：产能锚（规模单位 = 1 亩）与劳动那一路照常给（二者都不是本文件的判据）。
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            EconomySettlement.LABOR_MILLI_PER_MU,
            Map.of(EconomySettlement.GRAIN, YIELD_PER_MU),
            Map.of(), // ★ 不配种子：本文件只验流水与口粮，不引入投入那一路瓶颈
            List.of(new ClassSlot(PEASANT, "贫农", 1000)),
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            // ★ 通用夹具的 operator = **派生**（`feudal` ⇒ `ESTATE:farm@0_0`）。
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            POPULATION,
            LABOR_MILLI,
            1000,
            Map.of(AssetKind.LAND, LAND_MILLI_MU),
            Map.of(EconomySettlement.GRAIN, JAR),
            0L,
            List.of(),
            Map.of(EconomySettlement.GRAIN, EconomyVocabulary.dailyRationMilli(POPULATION, 1L)),
            Map.of());
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    LinkedHashMap<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, row);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★★ R2：当日劳动取自**配额表**（不再从"行 laborMilli × 投入率"算）⇒ 夹具必须发一条 = 该日劳动的配额。
    //   本文件的瓶颈是**土地**（300 亩 < 劳动可经营 406 亩），但劳动为 0 会把劳动瓶颈压到 0 亩 ⇒
    //   cycledLabor 一起变 0、收获恒 0 ⇒ 本文件的字面量（CYCLE_GROSS 那一族）全变。
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        Map.of(LOT, new LaborSupply(LOT, FIRST_PERIOD, LABOR_MILLI, 0L, 0L)),
        Map.of(
            ALLOCATION,
            new LaborAllocation(
                ALLOCATION,
                LOT,
                new ActorRef(ActorKind.ESTATE, FARM.value()),
                "farm",
                LABOR_MILLI,
                FIRST_PERIOD)),
        Map.of()); // ★ T2：生产关系表（本文件只谈周期流水 ⇒ 空表）
  }
}
