package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
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
 * {@link EconomySettlement} 的**纯函数**用例（R3a/R4a）：日耗（累计口粮的逐日差分）/ 进度 / 劳动累计、未激活不动、以及
 * **分配残差按最大余数法分派**（"Σ行得 = 剩余产出"的定点整数保证）。
 *
 * <p>★ 断言值都是这里写下的字面量（手算），不是"再调一遍结算对拍"。
 */
class EconomySettlementTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);

  /** ★ R2 的夹具：本文件的配额都挂在同一个批次上（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-0-farm@0_0");

  private static final long PEASANT_POPULATION = 100L;
  private static final long LANDLORD_POPULATION = 10L;

  /**
   * 某行**第 {@code day} 天**的口粮（毫粮）：唯一算法在 {@link EconomyVocabulary#dailyRationMilli}（累计口粮的逐日差分）。
   *
   * <p>★ 这里**不写**"人口 × 一天的量"：日耗逐日不同（10,000 ÷ 120 除不尽 ⇒ 第 3 天是 8,334），乘不出来。
   */
  private static long rationOn(long population, long day) {
    return EconomyVocabulary.dailyRationMilli(population, day);
  }

  /** 某行**头 {@code days} 天**的口粮合计（毫粮）—— 多日口粮一律用累计函数表达。 */
  private static long rationOver(long population, long days) {
    return EconomyVocabulary.cumulativeRationMilli(population, days);
  }

  /** 未激活（meta 空）⇒ 原样返回：一个字都不改（§6.6）。 */
  @Test
  void unactivatedEconomyIsReturnedUnchanged() {
    EconomyData empty = EconomyData.empty();

    assertThat(EconomySettlement.settle(empty, 0L, 1L)).isSameAs(empty);
  }

  /** 日结算：每行扣**当天**口粮（累计口粮的逐日差分）、progressDays +1、劳动累计加上当日实际劳动；流水记本期发生额。 */
  @Test
  void oneDayConsumesThatDaysRationAdvancesProgressAndRecordsFlows() {
    EconomyData base = fixture();

    EconomyData next = EconomySettlement.settle(base, 0L, 1L);

    // 贫农 100 人：第 1 天口粮 = floor(100 × 10,000 ÷ 120) = 8,333 ⇒ 83,000 − 8,333 = 74,667；
    // 地主 10 人：floor(10 × 10,000 ÷ 120) = 833 ⇒ 8,300 − 833 = 7,467。
    assertThat(grainOf(next, PEASANT_KEY)).isEqualTo(83_000L - rationOn(PEASANT_POPULATION, 1L));
    assertThat(grainOf(next, LANDLORD_KEY)).isEqualTo(8_300L - rationOn(LANDLORD_POPULATION, 1L));
    assertThat(next.industries().get(FARM).progressDays()).as("progressDays +1").isEqualTo(1L);
    assertThat(next.industries().get(FARM).cycleLaborMilli())
        .as("当日实际劳动 = 贫农 58000 × 1000‰ + 地主 5800 × 0‰ = 58000")
        .isEqualTo(58_000L);

    FlowRow peasantFlow = next.flows().get(PEASANT_KEY);
    assertThat(peasantFlow.consumed().get(GRAIN))
        .as("本期消费 = 第 1 天口粮")
        .isEqualTo(rationOn(PEASANT_POPULATION, 1L));
    assertThat(peasantFlow.income()).as("无收获 ⇒ 所得一分没有（逐商品的表为空）").isEmpty();
    assertThat(peasantFlow.netSurplus())
        .as("净盈余 = 0 − 第 1 天口粮")
        .isEqualTo(-rationOn(PEASANT_POPULATION, 1L));
    assertThat(next.classes().get(PEASANT_KEY).naturalNeeds().get(GRAIN))
        .as("★ §八.8：结算把**当日需求**写进 naturalNeeds ⇒ 读口与结算同源")
        .isEqualTo(rationOn(PEASANT_POPULATION, 1L));
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

    // 贫农：83,000 − 头 3 天口粮 25,000（= floor(100 × 10,000 × 3 ÷ 120)）= 58,000，净得 floor(6790×790/1000)
    //       = 5,364（余 100）⇒ 63,364；
    // 地主：8,300 − 头 3 天口粮 2,500（= floor(10 × 10,000 × 3 ÷ 120)）= 5,800，净得 floor(6790×210/1000) =
    // 1,425
    //       （余 900），**残差 1 归余数最大者 = 地主**（分母 = Σ权重 = 1000）⇒ 1,426 ⇒ 7,226。
    // ★ 剩余产出 6,790 = 毛产 7,000 − 生产损耗（饲料 0‰ + 折旧 30‰ = 210）。
    // ★ 残差不是"丢"而是"归地主"：Σ净得 = 5,364 + 1,426 = 6,790 = net（守恒）；残差规则是**最大余数法**（同余数按下标序）。
    assertThat(grainOf(next, PEASANT_KEY)).as("头 3 天逐日口粮 + 第 3 天分配净得").isEqualTo(63_364L);
    assertThat(grainOf(next, LANDLORD_KEY)).as("头 3 天逐日口粮 + 第 3 天分配净得（含残差 1）").isEqualTo(7_226L);
    assertThat(grainOf(next, PEASANT_KEY) + grainOf(next, LANDLORD_KEY))
        .as("Σ净得 + 两端日耗 = 基期库存 + net（账要平）")
        .isEqualTo(
            91_300L
                - (rationOver(PEASANT_POPULATION, 3L) + rationOver(LANDLORD_POPULATION, 3L))
                + 6_790L);
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
    // ★ 流水也纳入终态比较（不是"两边都只留最后一天"的平凡相等）：3 天流水逐日累加后两边逐值相同。
    assertThat(next.flows()).as("§十一：一次 3 天的流水 == 3 次单日各自并入 base 的流水").isEqualTo(chained.flows());
  }

  /**
   * ★★ **多日流水逐日累加**（2026-09-25 修的真 bug）：{@code settle(base, 0, 3)} 的 {@link FlowRow} 必须把 3
   * 天的发生额**累加**， 不能只留最后一天。
   *
   * <p>字面量（夹具：贫农 100 人 / 地主 10 人，口粮 = 累计的逐日差分，{@code cycleDays = 3} ⇒ 第 3 天收获）：
   *
   * <ul>
   *   <li>贫农 {@code consumed.grain} = 头 3 天口粮 25,000 + 166（分到的生产损耗） = 25,166；
   *   <li>地主 {@code consumed.grain} = 头 3 天口粮 2,500 + 44 = 2,544；
   *   <li>贫农 {@code income} = 5,364 + 166 = 5,530、地主 = 1,426 + 44 = 1,470（毛产份额，**与损耗率无关**）；
   *   <li>Σ 行 {@code consumed} − Σ 行 {@code income} = 27,710 − 7,000 = 20,710 = 基期库存 91,300 − 终态
   *       70,590（守恒）。
   * </ul>
   *
   * <p>★ 生产损耗 210 的两份由**最大余数法**给出：地主 floor(210 × 210 ÷ 1000) = 44（余 100）、 贫农 floor(210 × 790 ÷
   * 1000) = 165（余 900）⇒ 残差 1 归余数最大的贫农 ⇒ 166 / 44（v1 的"按下标序"会给 45 / 165）。
   *
   * <p>★ 判别力：把日循环里的累加改回"每日重建"（只留第 3 天），{@code consumed} 会掉到 8,334 + 166 = 8,500 ⇒ 本条红。
   */
  @Test
  void threeDayFlowAccumulatesDailyConsumption() {
    EconomyData next = EconomySettlement.settle(fixture(), 0L, 3L);

    FlowRow peasant = next.flows().get(PEASANT_KEY);
    FlowRow landlord = next.flows().get(LANDLORD_KEY);
    assertThat(peasant.consumed().get(GRAIN))
        .as("头 3 天口粮之和 + 贫农分到的生产损耗")
        .isEqualTo(rationOver(PEASANT_POPULATION, 3L) + 166L);
    assertThat(landlord.consumed().get(GRAIN))
        .as("头 3 天口粮之和 + 地主分到的生产损耗")
        .isEqualTo(rationOver(LANDLORD_POPULATION, 3L) + 44L);
    assertThat(peasant.income().get(GRAIN)).as("贫农分到的收获毛产份额").isEqualTo(5_530L);
    assertThat(landlord.income().get(GRAIN)).as("地主分到的收获毛产份额").isEqualTo(1_470L);
    assertThat(peasant.netSurplus()).as("5,530 − 25,166").isEqualTo(-19_636L);
    assertThat(landlord.netSurplus()).as("1,470 − 2,544").isEqualTo(-1_074L);
    assertThat(peasant.newBorrowing()).as("库存够吃 ⇒ 无借入").isZero();

    long sumConsumed = peasant.consumed().get(GRAIN) + landlord.consumed().get(GRAIN);
    long sumIncome = peasant.income().get(GRAIN) + landlord.income().get(GRAIN);
    assertThat(sumConsumed - sumIncome)
        .as("Σ 行 consumed − Σ 行 income == 基期库存 − 终态库存（守恒口径一致）")
        .isEqualTo((83_000L + 8_300L) - (63_364L + 7_226L));
  }

  /**
   * ★★ **饿死判据（字面量算例，用户 2026-09-25 点名）**：一行 100 人、周期 3 天、库存只够 **1 天**（8,333 毫粮）⇒ 后 2 天缺粮。
   * **致死率由包内可见的重载注入 200‰**（默认值是 0‰ = 不致命 ⇒ 这条路必须显式走到，不是死分支）。
   *
   * <pre>
   * cycleNeed     = 累计(3) − 累计(0) = floor(100 × 10,000 × 3 ÷ 120)      = 25,000
   * unmetNeed     = 第 2 天 8,333 + 第 3 天 8,334（= 25,000 − 16,666）      = 16,667
   * faminePerMille= 16,667 × 1000 / 25,000                                  = 666   （向下取整）
   * deaths        = 100 × 666 / 1000 × 200/1000                             = 13    （20% 致死率）
   * 人口 100 → 87；劳动 58,000 × 87 / 100                                   = 50,460（同比例缩）
   * </pre>
   *
   * <p>★ 判别力：把注入的致死率当 0 用 ⇒ {@code deaths=0}、人口/劳动不变 ⇒ 本条红（默认值那条路由下一条用例钉住）。
   */
  @Test
  void famineKillsTheStarvationShareOfThoseWhoGoHungryTheWholeCycle() {
    EconomyData next = EconomySettlement.settle(famineFixture(rationOn(100L, 1L)), 0L, 3L, 200);

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);

    assertThat(flow.unmetNeed().getOrDefault(GRAIN, 0L))
        .as("缺口 = 缺的那两天 = 25,000 − 8,333（★ R4 起 unmetNeed 逐商品，本条只说**粮**那一维）")
        .isEqualTo(rationOver(100L, 3L) - rationOn(100L, 1L));
    assertThat(flow.deaths()).as("100 × 666/1000 × 200/1000 = 13").isEqualTo(13L);
    assertThat(row.population()).as("人口 100 → 87").isEqualTo(87L);
    assertThat(row.laborMilli()).as("劳动同比例缩：58,000 × 87/100").isEqualTo(50_460L);

    // ★ 结算后的不变量（§六）：人口不为负、死亡 ≤ 当期人口、劳动 ≥ 0。
    assertThat(row.population()).isGreaterThanOrEqualTo(0L);
    assertThat(flow.deaths()).isLessThanOrEqualTo(100L);
    assertThat(row.laborMilli()).isGreaterThanOrEqualTo(0L);
  }

  /**
   * ★★ **默认路径：缺口照记、一个人都不死**（V4 的验收判据；用户 2026-09-25「可以先不做什么饿死人系统」）。
   *
   * <p>同一个夹具、同一个区间，唯一区别是**走公开入口**（致死率 = 默认 0‰）⇒ {@code unmetNeed} 一样非 0（缺口不是没在记）， 但 {@code deaths =
   * 0}、人口与有效劳动**纹丝不动**。
   *
   * <p>★ 判别力：把默认值改回 200 ⇒ 人口掉到 87 ⇒ 本条红。
   */
  @Test
  void theDefaultRationGapIsRecordedWithoutKillingAnyone() {
    EconomyData base = famineFixture(rationOn(100L, 1L));

    EconomyData next = EconomySettlement.settle(base, 0L, 3L); // 公开入口：致死率 = 默认 0‰

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);
    assertThat(flow.unmetNeed().getOrDefault(GRAIN, 0L))
        .as("缺口照记（= 后两天没吃到的那部分；★ R4 起 unmetNeed 逐商品，本条只说粮那一维）")
        .isEqualTo(rationOver(100L, 3L) - rationOn(100L, 1L));
    assertThat(flow.deaths()).as("★ 默认 0‰：一个人都不死").isZero();
    assertThat(row.population()).as("人口一个不少").isEqualTo(100L);
    assertThat(row.laborMilli()).as("有效劳动纹丝不动").isEqualTo(58_000L);
  }

  /**
   * ★ 判别力对照：储备**够吃满整个周期** ⇒ 无缺口、无死亡、人口与劳动纹丝不动。★ 这条**显式传 200‰**：即便致死率开着， 只要口粮一天不缺就没人死 ——
   * 它证明"没死人"不是因为致死率被关掉，而是因为缺口真是 0。
   */
  @Test
  void noFamineWhenReservesCoverTheWholeCycle() {
    // 储备恰好 = 头 3 天的口粮合计（逐日差分的 telescoping：Σ 日耗 == cumulativeRationMilli(100, 3)）
    EconomyData next = EconomySettlement.settle(famineFixture(rationOver(100L, 3L)), 0L, 3L, 200);

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);

    assertThat(flow.unmetNeed().getOrDefault(GRAIN, 0L)).as("一天不缺").isZero();
    assertThat(flow.unmetNeed())
        .as("★★ R4（T2）：这张表**多了布那一维** —— 该夹具没有织机 ⇒ 布一点没得到 ⇒ 布那一维非 0（R3 时它根本不在表里）")
        .containsKey(EconomySettlement.CLOTH);
    assertThat(flow.unmetNeed().getOrDefault(EconomySettlement.CLOTH, 0L)).isPositive();
    assertThat(flow.deaths()).as("无人饿死").isZero();
    assertThat(row.population()).as("人口不变").isEqualTo(100L);
    assertThat(row.laborMilli()).as("劳动不变").isEqualTo(58_000L);
  }

  /**
   * ★★ **多周期口径：{@code unmetNeed} 是"本期"的，不是"累计"的**（否则第 2 周期的饿死比例会把第 1 周期的旧缺口算进去）。
   *
   * <p>同一个 3 天周期跑**两轮**（{@code settle 0→6}，致死率 200‰）：第 1 周期末（第 3 天）缺口 16,667、死 13、人口 87； 第 2 周期 87
   * 人每天吃 7,250 毫粮（= floor(87×10,000×4÷120) − floor(87×10,000×3÷120)），收获为 0 ⇒ 第 4~6 天全缺， 缺口 =
   * 累计(87,6) − 累计(87,3) = 21,750，**不含**第 1 周期的 16,667 {@code faminePerMille = 21,750 × 1000 /
   * 21,750 = 1000‰} ⇒ {@code deaths = 87 × 1000/1000 × 200/1000 = 17}。
   *
   * <p>★★ **{@code deaths} 也是本期口径**（§八.5：整行按周期清零）⇒ 第 6 天读到的是**第 2 周期**的 17，不是 13 + 17 = 30。
   */
  @Test
  void unmetNeedResetsEachCycleSoTheSecondFamineUsesOnlyItsOwnGap() {
    EconomyData next = EconomySettlement.settle(famineFixture(rationOn(100L, 1L)), 0L, 6L, 200);

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);

    assertThat(flow.unmetNeed().getOrDefault(GRAIN, 0L))
        .as("第 2 周期缺口 = 累计(87,6) − 累计(87,3)（**不含**第 1 周期的 16,667；★ R4 起只读粮那一维）")
        .isEqualTo(rationOver(87L, 6L) - rationOver(87L, 3L));
    assertThat(flow.deaths()).as("本期（第 2 周期）死亡 = 17；累计口径已废（§八.5）").isEqualTo(17L);
    assertThat(row.population()).as("87 → 70").isEqualTo(70L);
    assertThat(row.laborMilli()).as("50,460 × 70 / 87 = 40,600").isEqualTo(40_600L);
  }

  /**
   * ★★ **生产损耗拆成两项：饲料 0‰ + 农具折旧 30‰**（v2 spec §3.4；用户 2026-09-25 裁定「现定」的定案数）。
   *
   * <p>★★ 钉住的是"**饲料为 0 是因为不做耕牛，不是漏掉了**"（用户 2026-09-25：「耕牛系统觉得复杂现阶段就别做」）： 谁把耕牛接进来（V7
   * 参数目录），必须连同本条与端到端的收获字面量一起改。
   *
   * <p>★ 判别力：把 {@code DEPRECIATION_PER_MILLE} 改成 0、或把两项之和改回 v1 的 150‰ ⇒ 本条（最后那条真档账）以及 {@code
   * EconomySeederTest} / 端到端的收获字面量一起红。
   */
  @Test
  void productionLossSplitsIntoFeedAndDepreciation() {
    assertThat(EconomySettlement.FEED_PER_MILLE).as("饲料为 0：v1 不做耕牛（不是漏掉）").isZero();
    assertThat(EconomySettlement.DEPRECIATION_PER_MILLE).as("农具折旧 3%").isEqualTo(30);
    assertThat(EconomySettlement.FEED_PER_MILLE + EconomySettlement.DEPRECIATION_PER_MILLE)
        .as("收获时扣的生产损耗 = 两项之和（v1 的 150‰ 里已把**留种**拆出去，改在播种日现扣）")
        .isEqualTo(30);
    // ★ 端到端那笔账的同源算式：真档一格毛产 3,100 × 67 × 1000 = 207,700,000 ⇒ 损耗 6,231,000 ⇒ 净 201,469,000
    //   （EconomySeeder 的"自给率 1198‰"与端到端的收获字面量都建立在这个数上）。
    //   ★ 3,100 / 67 在这里**写死**：economy 模块不许依赖 app（{@code EconomySeeder} 的常量不在本模块可见）。
    long grossMilli = 3_100L * 67L * 1_000L;
    assertThat(
            grossMilli
                * (EconomySettlement.FEED_PER_MILLE + EconomySettlement.DEPRECIATION_PER_MILLE)
                / 1000L)
        .as("真档一格的生产损耗（毫粮）")
        .isEqualTo(6_231_000L);
  }

  /**
   * ★★ **分配 = 按权重定点切分 + 最大余数法分派残差**（v2 spec §八.7）：分母是 **Σ权重** ⇒ 各行所得**恰好**与权重成比例，
   * 残差只是取整余数（不再"人人均摊"）。
   *
   * <p>★ 判别力（变异实测）：把分母改回恒定的 1000‰（v1 口径）⇒ 变的是**逐行值**（第四行拿 217,175 ≈ 按权重应得的 7 倍）⇒ 本条的 {@code
   * containsExactly} 与"≈ 按权重应得"两条红。 ★ 注意 **Σ 仍 == total**（均摊口径照样不丢总量）⇒ 判据必须落在**逐行值**上。
   */
  @Test
  void allocateDistributesResidueByLargestRemainderKeepingTheTotal() {
    assertThat(EconomySettlement.allocate(1_000L, new long[] {333L, 333L, 333L}))
        .as("Σ权重 = 999 ⇒ 残差 1；三行余数相同（333）= 平手 ⇒ 按下标序给第一项")
        .containsExactly(334L, 333L, 333L);
    assertThat(EconomySettlement.allocate(7L, new long[] {600L, 400L}))
        .as("7 × 600 ÷ 1000 = 4 余 200、7 × 400 ÷ 1000 = 2 余 800 ⇒ **余数大者先得**（第二项）")
        .containsExactly(4L, 3L);
    long[] parts = EconomySettlement.allocate(5_950_000L, new long[] {464L, 354L, 144L, 36L});
    assertThat(parts[0] + parts[1] + parts[2] + parts[3])
        .as("Σ行得 == 剩余产出（分母 = Σ权重 = 998 ⇒ Σparts == total 精确）")
        .isEqualTo(5_950_000L);
    // 余数：第一项 5,950,000 × 464 mod 998 = 664（最大）、第四项 258、第二项 42、第三项 34 ⇒ 残差 1 归第一项。
    assertThat(parts)
        .as("四行各自 ≈ 按权重应得（差 ≤ 1）；残差 1 归**余数最大**的第一行")
        .containsExactly(2_766_333L, 2_110_521L, 858_517L, 214_629L);
    // ★★ §八.7 的原始病灶：v1 把 Σ权重 的缺口（2‰ ⇒ 11,900 毫粮）**平均分给每一行** ⇒ 第四行（权重 36‰）实得
    //    214,200 + 2,975 = 217,175，而它按权重只该得 5,950,000 × 36 ÷ 998 = 214,629.26 ⇒ 实得是应得的 7 倍。
    assertThat(parts[3])
        .as("第四行实得 == 按权重应得（向下取整），不再是「均摊后远高于权重」的那一份")
        .isEqualTo(5_950_000L * 36L / 998L);
    assertThat(parts[3]).as("★ 判别力：旧口径（分母 1000‰ + 人手均摊）会给出 217,175，两者必须不同").isNotEqualTo(217_175L);
  }

  /**
   * ★★ **I3.1「不丢失」的守门用例**（裁定 D9-1）：**显式绑定的、与制度默认值不同的** operator，走完一日结算仍是它。
   *
   * <p>★★ <b>为什么夹具必须是**非默认**值</b>：本文件其余夹具的 operator 都是**派生值**（`feudal ⇒ ESTATE:farm@0_0`）——
   * 用派生值的话，{@code withCycleState} 漏传时会被**重新推导成同一个值** ⇒ 变异体存活、断言恒真 （T2 报告 §5.3
   * 把这条边界如实记下了，本用例正是接住它的那一条）。本用例取 `HOUSEHOLD:house-7` —— 制度是 `feudal`、经营主体是**外来的家户**，而 `feudal`
   * 的推导值是 `ESTATE:farm@0_0`： **kind 与 id 都不同** ⇒ 读出来是它就只可能来自透传，不可能来自任何推导。
   *
   * <p>★★ **为什么跑满一个周期而不是只跑一天**：结算里重建 `Industry` 的只有 `withCycleState` 一处， 但有**两个调用点、三种形状** ——
   * ①今日常规分支（{@code progressDays + 1}）；②播种日 `drawCycleInputs` 的投入累加分支 （{@link #fixtureWithOperator}
   * 配了种子且贫农占地 = 1 个产能单位 ⇒ 真的扣得动 ⇒ 这一支被走到）； ③关账分支（{@code progressDays} / {@code cycleLaborMilli}
   * 归零、{@code cycleInputUsedMilli} 清空）。 只跑一天只覆盖 ①②；跑满 `cycleDays = 3` 才把 ③ 也跑到。
   *
   * <p>★ 判别力：把 `withCycleState` 的透传改成 `null` ⇒ **构造期抛**（{@code Industry} 的守卫）； 改成
   * `defaultOperator(industry.regime(), industry.id())` ⇒ **本用例的断言红**（读出来是 `ESTATE:farm@0_0`）。
   * 两条都实测过（T3 报告 §变异自证）。
   */
  @Test
  void anExplicitOperatorSurvivesADayOfSettlement() {
    ActorRef household = new ActorRef(ActorKind.HOUSEHOLD, "house-7");
    // ★ 前置：夹具真的是"非默认"（kind 与 id 都与推导值不同）—— 否则本用例恒真，白写。
    assertThat(household)
        .as("夹具必须是**非默认**值：否则「漏传」会被重新推导成同一个值 ⇒ 本用例恒真")
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));

    EconomyData afterOneDay = EconomySettlement.settle(fixtureWithOperator(household), 0L, 1L);
    assertThat(afterOneDay.industries().get(FARM).operator())
        .as("★ 一日结算不得改写经营主体（defaultOperator 会给 ESTATE:farm@0_0 ⇒ 那样当场红）")
        .isEqualTo(household);

    // ★★ 再跑到周期末（第 3 天关账）：关账那一支把 progressDays / cycleLaborMilli 归零、cycleInputUsedMilli 清空，
    //   是同一次重建里"换的字段更多"的形状 —— 它照样不许碰 operator。
    EconomyData afterOneCycle = EconomySettlement.settle(afterOneDay, 1L, 3L);
    assertThat(afterOneCycle.industries().get(FARM).progressDays())
        .as("前置：第 3 天真的是周期末（关账分支被走到）")
        .isZero();
    assertThat(afterOneCycle.industries().get(FARM).operator())
        .as("★ 关账重建同样不得改写经营主体")
        .isEqualTo(household)
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
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
            // ★ R3：产能锚（规模单位 = 1 亩）；劳动那一路给 0（不施加约束 —— 本文件的字面量是 V2 口径算好的，
            //   而它算的是"两行合计地 3,000 亩是瓶颈"那一条链）。
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(), // ★ 一个都不配种子：同上
            slots,
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            // ★ 通用夹具的 operator = **派生**（`feudal` ⇒ `ESTATE:<产业 id>`）。
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, row(PEASANT_KEY, PEASANT_POPULATION, 58_000L, 1000, 700L, 83_000L));
    classes.put(LANDLORD_KEY, row(LANDLORD_KEY, LANDLORD_POPULATION, 5_800L, 0, 300L, 8_300L));
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★★ R2：当日劳动的来源是配额表（见 {@link #famineFixture} 的同款注释）。本夹具的当日劳动 = 贫农 58,000（投入率
    //   1000‰）+ 地主 0（投入率 0‰）= 58,000 ⇒ 发一条 58,000 的配额，逐值不变。
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        Map.of(LOT, supply(LOT, 58_000L)),
        Map.of(ALLOCATION, allocation(ALLOCATION, LOT, FARM, 58_000L)),
        // ★ T2：生产关系表（本文件只谈日结算 ⇒ 空表 = 全归 residualOwner 的等价路径）
        Map.of());
  }

  /**
   * ★★ **D9 的守门夹具**：与 {@link #fixture()} 同形（一格、一个农业产业、3 天周期、贫农 + 地主两行、同一批劳动）， 但**三处刻意不同**：
   *
   * <ul>
   *   <li>第 16 个组件（经营主体）由**调用方给** —— 守门用例据此塞**非默认**值；
   *   <li>**配了种子**（{@code cycleInputPerUnit} 非空，8,000 毫粮 / 亩 = v2 spec §3.3 的标定值）；
   *   <li>**贫农占地恰好 = 1 个产能单位**（1,000 千分亩 ÷ 1,000）⇒ 播种日**真的扣得动** ⇒ 结算里 {@code drawCycleInputs} 那次
   *       `withCycleState`（重建 Industry 的第二个调用点）**被走到**。
   * </ul>
   *
   * <p>★ 两行占地**刻意不同**（贫农 1,000 / 地主 300）：地主那一行 {@code rowScale = 300 ÷ 1000 = 0} ⇒ 它不扣种子
   * （"投入各扣各的"这条既有口径），而贫农那一行扣 1 × 8,000 = 8,000 毫粮 ⇒ 两条支路都走到。
   *
   * <p>★ 本夹具**只**服务守门用例：它的数值与 {@link #fixture()} 不同（多了一条投入），故不共用 —— 共用会改动
   * 那一批已算好的字面量（本仓纪律：不许因新用例放宽/改写既有断言）。
   */
  private static EconomyData fixtureWithOperator(ActorRef operator) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 0));
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            3L,
            0L,
            // ★ R3：产能锚（规模单位 = 1 亩）；劳动那一路给 0（不施加约束，同 {@link #fixture()}）。
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            // ★ 种子：8,000 毫粮 / 亩（**唯一**一处与 {@link #fixture()} 不同的配方字段）。
            Map.of(AssetKind.LAND, Map.of(GRAIN, 8_000L)),
            slots,
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            // ★★ 第 16 个组件：**调用方给的**（守门用例塞非默认值）。
            operator);
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(
        PEASANT_KEY,
        row(PEASANT_KEY, PEASANT_POPULATION, 58_000L, 1000, /* land= */ 1_000L, 83_000L));
    classes.put(LANDLORD_KEY, row(LANDLORD_KEY, LANDLORD_POPULATION, 5_800L, 0, 300L, 8_300L));
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★★ R2：当日劳动的来源是配额表（同 {@link #fixture()}）：发一条 58,000 的配额。
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        Map.of(LOT, supply(LOT, 58_000L)),
        Map.of(ALLOCATION, allocation(ALLOCATION, LOT, FARM, 58_000L)),
        // ★ T2：生产关系表（本文件只谈日结算 ⇒ 空表 = 全归 residualOwner 的等价路径）
        Map.of());
  }

  /**
   * 一条劳动供给（R2 的夹具）：毛额 = {@code grossLaborMilli}，两项扣除 0 ⇒ 可用劳动 = 毛额。
   *
   * <p>★ 第 1 周期（{@code period = 1}）：创世的正在进行的周期恒为 {@code lastClosedCycle(空) + 1 = 1}，
   * 且配额与供给**必须同期**（{@code EconomyData} 的构造期守卫按月判）。
   */
  static LaborSupply supply(PeopleLotId group, long grossLaborMilli) {
    return new LaborSupply(group, 1L, grossLaborMilli, 0L, 0L);
  }

  /** 一条劳动配额（R2 的夹具）：{@code laborMilli} 归 {@code actor}（= 产业 id），活动名同产业种类。 */
  static LaborAllocation allocation(
      LaborAllocationId id, PeopleLotId group, IndustryId industry, long laborMilli) {
    return new LaborAllocation(
        id, group, new ActorRef(ActorKind.ESTATE, industry.value()), "farm", laborMilli, 1L);
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
        Map.of(GRAIN, rationOn(population, 1L)),
        Map.of());
  }

  /**
   * **饿死夹具**：一格、一个农业产业（周期 3 天）、**一行 100 人贫农**（投入率 1000、劳动 58,000）、**不占地**（⇒ 收获恒 0，饿死是唯一变量）、库存 =
   * {@code stock} 毫粮。
   */
  private static EconomyData famineFixture(long stock) {
    List<ClassSlot> slots = List.of(new ClassSlot(PEASANT, "贫农", 1000));
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            3L,
            0L,
            Map.of(AssetKind.LAND, 1_000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(), // ★ 一个都不配种子（同 {@link #fixture()}）
            slots,
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            // ★ 通用夹具的 operator = **派生**（同上）。
            RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, farm);
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            100L,
            58_000L,
            1000,
            Map.of(),
            Map.of(GRAIN, stock),
            0L,
            List.of(),
            Map.of(GRAIN, rationOn(100L, 1L)),
            Map.of());
    Map<ClassKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(PEASANT_KEY, row);
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    // ★★ R2：**当日劳动的来源是配额表**（不再是"行 laborMilli × 投入率"）⇒ 本夹具必须发一条配额，
    //   否则"当日劳动 = 58,000"那条断言会读成 0（那是**新口径的直接后果**，不是兜底可修的东西）。
    //   ★ 配额量 = 58,000 = 贫农 100 人 × 580‰ × 投入率 1000‰ + 地主 10 人的投入率 0‰（见 fixture 的两个槽位）。
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        Map.of(LOT, supply(LOT, 58_000L)),
        Map.of(ALLOCATION, allocation(ALLOCATION, LOT, FARM, 58_000L)),
        // ★ T2：生产关系表（本文件只谈日结算 ⇒ 空表 = 全归 residualOwner 的等价路径）
        Map.of());
  }

  private static long grainOf(EconomyData data, ClassKey key) {
    return data.classes().get(key).goods().getOrDefault(GRAIN, 0L);
  }
}
