package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.PopulationEconomyFixture.DESERT;
import static io.mosire.simos.app.world.PopulationEconomyFixture.advanced;
import static io.mosire.simos.app.world.PopulationEconomyFixture.seeded;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.time.PopulationEconomyTimeParticipant;
import io.mosire.simos.app.world.PopulationEconomyFixture.Fixture;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R4 的端到端判据**（人口—经济协调器）：**人第一次真的随时间变**，而且**经济侧跟着一起变**。
 *
 * <p>★★ **夹具刻意混合**（brief 的硬要求 —— 否则"不转移/不生育/不看压力"的实现照样绿）：
 *
 * <ul>
 *   <li>① **同格"供方富余 + 受方不足"的一对**：(0,0) 平原格上，农业行有纤维、家庭纺织行要纤维（跨产业取材）；
 *   <li>② **一个育龄女性批次**：创世批次里 15-59 岁那一档的女性（{@link PopulationSeeder} 造的）真的生孩子；
 *   <li>③ **长期缺粮 vs 短期缺粮的对照**：(1,0) 是**沙漠**格（可耕地为 0 ⇒ 从第 1 天起就吃不饱、且永远吃不饱） —— 同一次推进只跑 5 天与跑 180
 *       天，两条路线的死亡结果**不同**。
 * </ul>
 *
 * <p>★ **被测物是参与者本身**（{@link PopulationEconomyTimeParticipant#simulateWorld}）：它交回的提案就是 Core 会落盘的那一份。
 * 用例把它 apply 回基态（真 {@code EconomyChangeSet} / {@code SocialChangeSet}）—— 这正是 Core 的 ④ Validate 做的事。
 */
class PopulationR4Test {

  /** 一年的天数（本仓日制，与 {@code PopulationSeeder} 同口径）。 */
  private static final long YEAR = 365L;

  // ── ① ★ 年龄推进：推 365 天后，某批次的年龄比推进前**大 365**，而锚点字段**一字未改** ──────────

  /**
   * ★★ **T1 的判据原文**：年龄是**派生纯函数**（{@code ageAtAnchorDays + (nowTick − anchorTick)}）——
   * 故"变老"这件事**不需要改任何字段**，只需要让它**读得出来**。
   *
   * <p>★ 判别力：若有人"每天改 ageAtAnchorDays"（把年龄做成状态），本条的第三条断言当场红（锚点变了）。
   */
  @Test
  void ageAdvancesByThreeHundredSixtyFiveWithoutChangingTheAnchor() {
    Fixture fixture = seeded();
    SocialData before = fixture.social();
    SocialData after = advanced(fixture, YEAR).social();

    PopulationGroup sample = before.groups().values().iterator().next();
    PopulationGroup grown = after.groups().get(sample.id());

    assertThat(grown.ageDaysAt(YEAR))
        .as("★ 推 365 天后：年龄比推进前大 365")
        .isEqualTo(sample.ageDaysAt(0L) + YEAR);
    assertThat(grown.ageAtAnchorDays())
        .as("★ 锚点年龄**一字未改**（年龄不是每天写一次的字段）")
        .isEqualTo(sample.ageAtAnchorDays());
    assertThat(grown.anchorTick()).as("★ 锚点日也没动").isEqualTo(sample.anchorTick());
  }

  // ── ② ★ 出生 + 死亡 + 人口守恒（显式落账）────────────────────────────────────────────

  /**
   * ★★ **人口守恒**：{@code Σ年末人口 == Σ创世人口 + 出生 − 死亡}，两侧的账都**显式**落着 （社会侧改 {@code count}、经济侧写 {@code
   * FlowRow.births} 与 {@code FlowRow.deaths}）。
   *
   * <p>★ 判别力：任一侧漏记（只记死亡不记出生、或只改人口不记流水）⇒ 本条红。
   */
  @Test
  void birthsAndDeathsAreBookedOnBothSides() {
    Fixture fixture = seeded();
    // ★★ **窗口口径**（AGENT.md §9.4 的教训）：{@code FlowRow} 的人群账是**本周期**的量、新周期第一天归零
    //   ⇒"一年的出生/死亡"必须**逐周期在关账日读、再加起来**，不能在第 365 天读一次（那时读到的只是第 4 个周期的头 5 天）。
    YearLedger ledger = yearLedger();
    Fixture after = ledger.end();

    long births = ledger.births();
    long deaths = ledger.deaths();

    assertThat(births).as("★ 育龄女性批次真的生了孩子（年度出生数 > 0）").isPositive();
    assertThat(deaths).as("有沙漠格 ⇒ 一年里真的死了人").isPositive();
    assertThat(totalPopulation(after.social()))
        .as("★★ 人口守恒：年末人口 == 创世人口 + 出生 − 死亡")
        .isEqualTo(totalPopulation(fixture.social()) + births - deaths);
    assertThat(totalPopulation(after.economy()))
        .as("★ 经济侧的 Σ 行人口与社会侧的 Σ 批次**同步**（两边记的是同一份账）")
        .isEqualTo(totalPopulation(after.social()));
    assertThat(totalPopulation(after.social()))
        .as("非平凡：人口真的变了（不再是创世那个数）")
        .isNotEqualTo(totalPopulation(fixture.social()));
  }

  // ── ③ ★★ 缺粮"五天"与"半年"产生**不同的死亡结果**（spec §九 R4 行原文）─────────────────

  /**
   * ★★ **判据原文**：两条路线**只差天数**（同一份创世、同一个式子）。
   *
   * <pre>
   * 五天（advance 0→5）：跨不过任何一个结算日（每 30 天才有一次）⇒ **0 死**
   * 半年（advance 0→180）：6 次月度结算，沙漠格的压力逐月累积（第 1 月底 300、第 2 月底 600 …）
   *                        ⇒ 第 2 个月起死亡率被显著抬高 ⇒ **远多于 0 死**
   * </pre>
   *
   * <p>★ 判别力：把"压力"直接换成"死亡"（缺粮当天就死人）⇒ 五天那一课的死亡数非 0 ⇒ 红。
   */
  @Test
  void fiveDaysOfShortageAndHalfAYearGiveDifferentDeathTolls() {
    Fixture fiveDays = advanced(seeded(), 5L);
    Fixture halfYear = advanced(seeded(), 180L);

    assertThat(totalDeaths(fiveDays.economy())).as("★ 五天：一个人都不多死（压力够不到门槛）").isZero();
    assertThat(totalDeaths(halfYear.economy())).as("★ 半年：真的开始死人").isPositive();
    assertThat(totalDeaths(halfYear.economy()))
        .as("★★ **判据原文：缺粮'五天'与'半年'产生不同的死亡结果**")
        .isGreaterThan(totalDeaths(fiveDays.economy()));
    assertThat(totalBirths(fiveDays.economy())).as("五天：没跨过结算日 ⇒ 一个孩子都没生").isZero();
  }

  // ── ④ ★ 生理压力：缺粮加、缺布也加，但**两条系数分开**（spec §七）──────────────────────

  /**
   * ★★ **粮食不足与衣物不足对死亡的时间尺度不能一样**：沙漠格（缺粮）与**只缺布**的对照。
   *
   * <p>平原格在第一年内布也常常不够（创世没有布 ⇒ 要靠自己织）—— 故这里直接量**两个系数各自生效**： 沙漠格的粮满足率 ≈ 0 ⇒ 每天 +10 压力；而它同时缺布，那只按 +1/天
   * 计。若两个系数被**合并成一个数** （spec 明令不许），沙漠格的压力会小一个数量级 ⇒ 半年的死亡数会显著变小 ⇒ 本条与上一条一起红。
   *
   * <p>★ 这里量的是**同一格上两种缺口各自贡献的那一份**：把布那一项单独算出来，量级必须**小于**粮那一项。
   */
  @Test
  void grainShortageAndClothShortageContributeOnSeparateScales() {
    Fixture after = advanced(seeded(), YEAR);
    PopulationGroup desertGroup = worstStressGroup(after.social(), DESERT);

    assertThat(desertGroup.physiologicalStress())
        .as("★ 沙漠格（粮满足率 ≈ 0）一年下来压力累积到数百 —— 远超'只缺布'能到的一百出头")
        .isGreaterThan(PopulationDynamics.STRESS_MORTALITY_THRESHOLD);
    long oneYearOfPureGrainShortage = 360L * PopulationDynamics.GRAIN_STRESS_PER_MILLE_PER_DAY;
    long oneYearOfPureClothShortage = 360L * PopulationDynamics.CLOTH_STRESS_PER_MILLE_PER_DAY;
    assertThat(oneYearOfPureGrainShortage)
        .as("★ 两个量级必须分开：满缺粮一年 +3,600 vs 满缺布一年 +360（差一个数量级）")
        .isGreaterThan(oneYearOfPureClothShortage * 5L);
  }

  // ── ⑤ ★★ T0：纺织不再第 2 周期停工（R3 的遗留）──────────────────────────────────────

  /**
   * ★★ **农田每个周期都把新一期的纤维交给同格的织机**（brief 的 T0）：判据 = **第 2 个周期仍然织出布**。
   *
   * <p>★ 判别力：去掉取材步（R3 的旧形态）⇒ 第 2 周期的布毛产为 **0** ⇒ 红。
   *
   * <p>★ 对称记账的另一半也在这里钉住：供方（农业行）的纤维**减少**了，而它的 {@code consumed} 上**记着**那一笔 （"单侧扣减"会让逐商品的守恒式当场不平 ——
   * 端到端守恒由 {@code EconomySettlementEndToEndTest} 单独守）。
   */
  @Test
  void theLoomsKeepWeavingBecauseTheFieldsFeedThemEveryCycle() {
    Fixture seeded = seeded();
    long clothCycle1 = weaveClothIncome(advanced(seeded, 120L).economy());
    long clothCycle2 = weaveClothIncome(advanced(seeded, 240L).economy());

    assertThat(clothCycle1).as("第 1 周期：织机吃创世那份纤维 ⇒ 布落织布的人（T4/I4.3）").isPositive();
    assertThat(clothCycle2).as("★★ T0：第 2 周期仍然织出布（R3 的遗留已收口）").isPositive();
    assertThat(fiberConsumedTotal(advanced(seeded, 240L).economy()))
        .as("★ 对称记账：供方（农田）那一笔进了 consumed（不是单侧扣减）")
        .isPositive();
  }

  // ── ⑥ ★★ 人死了，劳动**真的**跟着减（R2 记的那条旧账的收口）──────────────────────────

  /**
   * ★★ **"applyFamine 缩的是行劳动，而当日劳动取自配额"** 这条旧账在 R4 收口：人口减少后，
   * **该批次名下的劳动配额与劳动供给都按存活比例缩**（否则"人死了劳动没减"）。
   *
   * <p>★ 判别力：把 {@code EconomySettlement.applyPopulationChange} 里的配额缩放删掉 ⇒ 本条的配额断言红 （人口变了、配额纹丝不动）。
   */
  @Test
  void laborQuotasShrinkWithTheDeaths() {
    Fixture seeded = seeded();
    Fixture after = yearLedger().end();

    long deaths = yearLedger().deaths();
    assertThat(deaths).as("这一课的前提：真的死了人").isPositive();

    long allocatedBefore = allocatedTotal(seeded.economy());
    long allocatedAfter = allocatedTotal(after.economy());
    assertThat(allocatedAfter)
        .as("★★ 人口减少 ⇒ 配额之和**变小**（人死了劳动没减这条旧账的收口）")
        .isLessThan(allocatedBefore);
    assertThat(allocatedAfter).as("非平凡：配额没有归零（死的是少数人）").isPositive();
    assertThat(availableTotal(after.economy()))
        .as("★ 供给的毛额同样跟着缩 ⇒ Σ allocated ≤ available 仍然成立")
        .isGreaterThanOrEqualTo(allocatedAfter);
  }

  // ── ⑦ ★★ §十一 等价性：一次推 180 天 == 180 次单日 ────────────────────────────────────

  /**
   * ★★ **一次推 N 天与 N 次单日必须逐值等价**（本仓的硬判据）：协调器内部的日循环把"逐日"这条语义 **留在同一个调用栈里**（这也是它必须同时拥有两个模块的理由，见类注）。
   *
   * <p>★ 判别力：把日循环改成"一次性算完"（或让社会侧读经济侧的**基态**）⇒ 两条路线的月度结算次数/压力累积 不同 ⇒ 两份终态不相等 ⇒ 红。
   */
  @Test
  void oneLongAdvanceEqualsManyDailyAdvances() {
    Fixture once = advanced(seeded(), 180L);
    Fixture daily = seeded();
    for (long day = 0L; day < 180L; day++) {
      daily = advanced(daily, 1L);
    }

    assertThat(totalPopulation(daily.social()))
        .as("§十一：一次 180 天与 180 次单日的人口逐值相同")
        .isEqualTo(totalPopulation(once.social()));
    assertThat(totalDeaths(daily.economy())).as("死亡账逐值相同").isEqualTo(totalDeaths(once.economy()));
    assertThat(totalBirths(daily.economy())).as("出生账逐值相同").isEqualTo(totalBirths(once.economy()));
    assertThat(daily.social().groups().keySet())
        .as("★ 连批次的键集都一样（出生批次逐月落、不多不少）")
        .isEqualTo(once.social().groups().keySet());
  }

  /** 一年的三段账（在三个关账日各读一次本周期的人群账）+ 第 365 天的终态。 */
  private record YearLedger(Fixture end, long births, long deaths) {}

  /** 逐周期读一年的出生/死亡（**关账日**读得到整周期：§八.5）。 */
  private static YearLedger yearLedger() {
    Fixture fixture = seeded();
    long births = 0L;
    long deaths = 0L;
    for (long close : List.of(120L, 240L, 360L)) {
      fixture = advanced(fixture, close - fixture.tick());
      births += totalBirths(fixture.economy());
      deaths += totalDeaths(fixture.economy());
    }
    return new YearLedger(advanced(fixture, 365L - fixture.tick()), births, deaths);
  }

  private static long totalPopulation(SocialData social) {
    long total = 0L;
    for (PopulationGroup group : social.groups().values()) {
      total += group.count();
    }
    return total;
  }

  private static long totalPopulation(EconomyData economy) {
    return economy.classes().values().stream().mapToLong(ClassRow::population).sum();
  }

  private static long totalBirths(EconomyData economy) {
    return economy.flows().values().stream().mapToLong(flow -> flow.births()).sum();
  }

  private static long totalDeaths(EconomyData economy) {
    return economy.flows().values().stream().mapToLong(flow -> flow.deaths()).sum();
  }

  private static long allocatedTotal(EconomyData economy) {
    return economy.allocations().values().stream()
        .mapToLong(allocation -> allocation.laborMilli())
        .sum();
  }

  private static long availableTotal(EconomyData economy) {
    return economy.laborSupply().values().stream()
        .mapToLong(supply -> supply.availableLabor())
        .sum();
  }

  private static long fiberConsumedTotal(EconomyData economy) {
    return economy.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(EconomyTestWorld.FIBER, 0L))
        .sum();
  }

  /**
   * ★ <b>本格这一周期"织出了多少布"的可读读数（T4/T5 起）</b>：产出自本阶段起不再写进织机那四行 （它们人口为 0 ⇒ 永不是 cohort
   * 受方），布落**织布的人**（同格的农业行，I4.3）⇒ 读数取**本格行里 收到的布**（流水所得那一维），织机的行自己算 0。
   */
  private static long weaveClothIncome(EconomyData economy) {
    long total = 0L;
    for (Map.Entry<ClassKey, ClassRow> entry : economy.classes().entrySet()) {
      var flow = economy.flows().get(entry.getKey());
      if (flow != null && flow.income().getOrDefault(EconomyTestWorld.CLOTH, 0L) > 0L) {
        total += flow.income().getOrDefault(EconomyTestWorld.CLOTH, 0L);
      }
    }
    return total;
  }

  // ── ★★ B1 regression：压力与劳动配额无关 ──────────────────────────────────────────────

  /**
   * ★★ **不变量原文**（S1 spec §十）： <em>所有存在人口的 cohort 都参与生理压力计算，与其有没有 {@code LaborAllocation} 完全无关。</em>
   *
   * <p>★ 为什么要钉它：修 B1 之前，{@code applyDailyStress} 用 {@code industriesOf} （来自 {@code
   * economy.allocations()}）过滤批次，**没有配额的批次直接 continue ⇒ 压力恒 0**。 两类批次从未有过配额：0-14 档的创世批次（劳动系数
   * 0）、以及**全部新生儿批次**（创世之后产生）。
   *
   * <p>★ 判别力：把兜底改回 {@code continue} ⇒ 本条两条断言都红。
   */
  @Test
  void everyPopulatedCohortAccumulatesStressRegardlessOfLaborQuota() {
    Fixture after = advanced(seeded(), 180L);
    SocialData social = after.social();
    EconomyData economy = after.economy();

    // ① 创世就有、且**没有劳动配额**的批次：0-14 档（年龄 < 15 年）
    List<PopulationGroup> minors =
        social.groups().values().stream()
            .filter(g -> g.ageDaysAt(180L) < PopulationDynamics.FERTILE_MIN_DAYS)
            .filter(g -> !economy.laborSupply().containsKey(g.id()))
            .toList();
    // ② 新生儿批次（id 的 cohort 段以 'b' 开头）
    List<PopulationGroup> newborns =
        social.groups().values().stream().filter(g -> g.id().value().contains(":b")).toList();

    assertThat(minors).as("夹具前提：真的存在没有配额的未成年批次").isNotEmpty();
    assertThat(minors.stream().mapToLong(PopulationGroup::physiologicalStress).max().orElse(0L))
        .as("★★ 没有配额 ≠ 不挨饿：0-14 档也要累积压力")
        .isPositive();
    assertThat(newborns).as("夹具前提：半年里真的生了孩子").isNotEmpty();
    assertThat(newborns.stream().mapToLong(PopulationGroup::physiologicalStress).max().orElse(0L))
        .as("★★ 新生儿同样要累积压力（否则饥荒抑制只对创世那一代生效）")
        .isPositive();
  }

  private static PopulationGroup worstStressGroup(SocialData social, HexCoord hex) {
    PopulationGroup worst = null;
    for (PopulationGroup group : social.groupsAt(hex)) {
      if (worst == null || group.physiologicalStress() > worst.physiologicalStress()) {
        worst = group;
      }
    }
    assertThat(worst).as("该格必须有批次: %s", hex).isNotNull();
    return worst;
  }
}
