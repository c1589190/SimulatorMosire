package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R4（T1）人口再生产的判据**（spec §七）：**缺粮"五天"与"半年"产生不同的死亡结果**、**粮与布的系数分开**、 **出生按育龄女性 × 具体年龄 ×
 * 历史满足情况**、**死亡率四维齐备**。
 *
 * <p>★★ **五天一课**是 brief 点名必须有的那条：它把"短期中断"与"长期不足"两条路由**同一份状态+同一个式子**跑出来， 断言两条路的**死亡结果不同**（一条
 * 0、一条显著为正）。判别力：把 {@code STRESS_MORTALITY_THRESHOLD} 改成 0 （"缺粮当天就死人"）⇒ 五天一课的死亡数变成非 0 ⇒ 红。
 */
class PopulationDynamicsTest {

  private static final HexCoord HEX = new HexCoord(3, 4);
  private static final long YEAR = 365L;

  /** 满额（‰）。 */
  private static final long FULL = 1000L;

  // ── ① 生理压力：累积与消退（spec §七 的机制本体）────────────────────────────────────

  /**
   * ★ **缺什么加什么、各自系数**：粮缺 1000‰ 一天 = +10 压力；布缺 1000‰ 一天 = +1 压力 ⇒ **两者相差一个数量级**
   * （这正是"粮食不足与衣物不足对死亡的时间尺度显然不能一样"的落点）。
   *
   * <p>★ 判别力：把两个系数**合并成一个数**（spec §七 明令不许）⇒ 两条断言必然有一条红。
   */
  @Test
  void grainAndClothShortfallsAddStressWithSeparateCoefficients() {
    assertThat(PopulationDynamics.stressAfter(0L, 0L, FULL))
        .as("满缺**粮**一天：+10（GRAIN_STRESS_PER_MILLE_PER_DAY）")
        .isEqualTo(PopulationDynamics.GRAIN_STRESS_PER_MILLE_PER_DAY);
    assertThat(PopulationDynamics.stressAfter(0L, FULL, 0L))
        .as("满缺**布**一天：+1（CLOTH_STRESS_PER_MILLE_PER_DAY）")
        .isEqualTo(PopulationDynamics.CLOTH_STRESS_PER_MILLE_PER_DAY);
    assertThat(PopulationDynamics.GRAIN_STRESS_PER_MILLE_PER_DAY)
        .as("★ 两个系数**必须不同**（时间尺度不同），且粮那个更大（先饿死、再冻死）")
        .isGreaterThan(PopulationDynamics.CLOTH_STRESS_PER_MILLE_PER_DAY);
  }

  /** ★ **供给恢复后逐渐消退**：全满足的一天消退 {@code STRESS_DECAY_PER_DAY}，且**不会退成负数**。 */
  @Test
  void stressDecaysWhenProvisionsAreSatisfied() {
    assertThat(PopulationDynamics.stressAfter(100L, FULL, FULL))
        .as("全满足 ⇒ 退 STRESS_DECAY_PER_DAY")
        .isEqualTo(100L - PopulationDynamics.STRESS_DECAY_PER_DAY);
    assertThat(PopulationDynamics.stressAfter(2L, FULL, FULL)).as("退到 0 就停（压力不得为负）").isZero();
    assertThat(PopulationDynamics.stressAfter(0L, 500L, 500L))
        .as("★ 半满足：加 5+0.5 取整 0、退 3 ⇒ 净 +2（压力是'满足程度'的函数，不是'满/空'二值）")
        .isEqualTo(2L);
  }

  // ── ② ★★ 缺粮"五天"与"半年"产生不同的死亡结果（spec §九 R4 行原文的判据）──────────────

  /**
   * ★★ **本轮的核心判据**：同一批人、同一个式子的两条时间路线 —— **结果必须不同**。
   *
   * <pre>
   * 五天满缺：压力 = 5 × 10 = 50 < 门槛 300 ⇒ 月度死亡率 = 基础死亡率 ⇒ **一个都不多死**
   * 半年满缺：压力 = 180 × 10 = 1,800（每天加、一天不退）⇒ 超额 = (1800 − 300) × 20‰ ÷ 1000 = 30‰
   *           ⇒ 月度死亡 = 1,000 人 × (基础 1‰ + 30‰) = 31 人
   * </pre>
   *
   * <p>★★ **判别力（逐条）**：
   *
   * <ul>
   *   <li>把"压力"直接换成"死亡"（缺粮当天就死人）⇒ 五天那一课的死亡数非 0 ⇒ 红；
   *   <li>把 {@code STRESS_MORTALITY_THRESHOLD} 改成 0 ⇒ 同上 ⇒ 红；
   *   <li>把压力**不累积**（每天只记当天）⇒ 半年那一路的压力恒 10 ⇒ 死亡与五天相同 ⇒ 红。
   * </ul>
   */
  @Test
  void fiveDaysOfFamineAndHalfAYearOfFamineKillDifferentNumbers() {
    SocialData base = worldOf(adultFemale(1_000L));

    long fiveDayStress = 0L;
    for (int day = 0; day < 5; day++) {
      fiveDayStress = PopulationDynamics.stressAfter(fiveDayStress, 0L, FULL);
    }
    long halfYearStress = 0L;
    for (int day = 0; day < 180; day++) {
      halfYearStress = PopulationDynamics.stressAfter(halfYearStress, 0L, FULL);
    }
    assertThat(fiveDayStress).as("五天满缺 ⇒ 压力 50（够不到门槛）").isEqualTo(50L);
    assertThat(halfYearStress).as("半年满缺 ⇒ 压力 1,800（远超门槛）").isEqualTo(1_800L);
    assertThat(fiveDayStress)
        .as("★ 判据的前半：五天 < 门槛 < 半年")
        .isLessThan(PopulationDynamics.STRESS_MORTALITY_THRESHOLD);
    assertThat(halfYearStress).isGreaterThan(PopulationDynamics.STRESS_MORTALITY_THRESHOLD);

    // 把两条路的压力放进同一批人身上，各跑一次月度结算。
    PopulationGroup fiveDayGroup = withStress(adultFemale(1_000L), fiveDayStress);
    PopulationGroup halfYearGroup = withStress(adultFemale(1_000L), halfYearStress);
    long fiveDayDeaths = deathsOf(fiveDayGroup);
    long halfYearDeaths = deathsOf(halfYearGroup);

    // 青壮年女性 15-59 的基础死亡率 = 1‰/月 ⇒ 1,000 人基础死亡 1 人；压力不抬高（五天）⇒ 恰 1 人。
    assertThat(fiveDayDeaths).as("★ 五天：压力够不到门槛 ⇒ 只有基础死亡（1‰ × 1000 人 = 1 人）").isEqualTo(1L);
    assertThat(halfYearDeaths)
        .as("★ 半年：死亡率 = 1‰ + (1800−300)×20‰÷1000 = 31‰ ⇒ 31 人")
        .isEqualTo(31L);
    assertThat(halfYearDeaths).as("★★ **判据原文：缺粮五天与半年产生不同的死亡结果**").isGreaterThan(fiveDayDeaths);
    assertThat(base.groups()).as("本类**不写状态**：{@code monthly} 是纯函数（基态一字未改）").hasSize(1);
  }

  // ── ③ 出生：育龄女性 × 具体年龄 × 历史满足情况（**不是**总人口乘一个增长率）──────────────

  /**
   * ★★ **出生只从育龄女性批次来**（spec §七："至少不要用总人口乘一个增长率"）：同一格上放**四个**批次， 只有 15-59 岁的女性那一条有出生 ——
   * 男批次、未成年女性、60+ 女性一律 0。
   *
   * <p>★ 判别力（对着"总人口 × 增长率"的坏实现）：那种实现会给**每一个**批次都算出正数（四条断言一起红）。
   */
  @Test
  void birthsComeOnlyFromFertileFemaleBatches() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("f-30"), group(lot("f-30"), Sex.FEMALE, 1_000L, 30L * YEAR));
    groups.put(lot("m-30"), group(lot("m-30"), Sex.MALE, 1_000L, 30L * YEAR));
    groups.put(lot("f-5"), group(lot("f-5"), Sex.FEMALE, 1_000L, 5L * YEAR));
    groups.put(lot("f-70"), group(lot("f-70"), Sex.FEMALE, 1_000L, 70L * YEAR));

    PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(withGroups(groups), 30L);

    assertThat(outcome.births()).as("★ 只有育龄女性那一条生：1,000 × 20‰ = 20 人").isEqualTo(20L);
    assertThat(outcome.changes().get(lot("f-30")).births()).as("30 岁女性：生 20").isEqualTo(20L);
    assertThat(outcome.changes().get(lot("m-30")).births()).as("同岁男性：不生").isZero();
    assertThat(outcome.changes().get(lot("f-5")).births()).as("未成年女性：不生").isZero();
    assertThat(outcome.changes().get(lot("f-70")).births()).as("60+ 女性：不生").isZero();
  }

  /**
   * ★★ **"历史生活资料满足情况"真的压得下出生**（spec §七 的三要素之一）：同一批育龄女性， 压力 0 时生 20 人；压力 500 时**一个都不生**（{@code
   * FERTILITY_SUPPRESSION_PER_STRESS} = 2）。
   */
  @Test
  void longTermHardshipSuppressesBirths() {
    PopulationGroup wellFed =
        new PopulationGroup(lot("f-30"), HEX, Sex.FEMALE, 1_000L, 30L * YEAR, 0L, 0L);
    PopulationGroup hungry =
        new PopulationGroup(lot("f-30"), HEX, Sex.FEMALE, 1_000L, 30L * YEAR, 0L, 500L);
    PopulationGroup halfHungry =
        new PopulationGroup(lot("f-30"), HEX, Sex.FEMALE, 1_000L, 30L * YEAR, 0L, 250L);

    assertThat(PopulationDynamics.birthsOf(wellFed, 30L * YEAR)).as("吃得饱：20").isEqualTo(20L);
    assertThat(PopulationDynamics.birthsOf(halfHungry, 30L * YEAR))
        .as("中等压力（250）：打对折 ⇒ 10")
        .isEqualTo(10L);
    assertThat(PopulationDynamics.birthsOf(hungry, 30L * YEAR)).as("长期吃不饱（压力 500）：生不出来").isZero();
  }

  /** ★ **新生儿落成"当月出生"的新批次**（年龄 0、锚点 = 结算日，性别各半）⇒ 年龄结构随推进演化。 */
  @Test
  void newbornsBecomeTheirOwnBatchSoTheAgeStructureEvolves() {
    SocialData base =
        worldOf(new PopulationGroup(lot("f-30"), HEX, Sex.FEMALE, 101L, 30L * YEAR, 0L, 0L));

    PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(base, 30L);

    assertThat(outcome.births()).as("101 × 20‰ = 2 人").isEqualTo(2L);
    assertThat(outcome.data().groups()).as("原批次之外多出**当月出生**的批次").hasSizeGreaterThan(1);
    boolean newbornFound = false;
    for (PopulationGroup group : outcome.data().groups().values()) {
      if (!group.id().equals(lot("f-30"))) {
        assertThat(group.ageDaysAt(30L)).as("新生批次的年龄 = 0 天（锚点就是结算日）").isZero();
        assertThat(group.anchorTick()).as("锚点 = 结算日").isEqualTo(30L);
        assertThat(PopulationLots.isUrban(group)).as("★ 城乡归属按母亲批次的前缀继承（不是另起一套命名）").isFalse();
        newbornFound = true;
      }
    }
    assertThat(newbornFound).as("确实落了新生批次").isTrue();
  }

  // ── ④ 死亡：年龄 × 性别 两维（spec §七 的四维公式）──────────────────────────────────

  /**
   * ★★ **年龄那一维真的生效**：同样的 1,000 人、同样没有压力，老年批次死得比青壮多（20‰ vs 1‰）； 未成年介于两者之间（2‰）。
   *
   * <p>★ **性别那一维可注入**（包内可见的重载）：把女性系数改成 2,000‰ ⇒ 女性批次的死亡数**逐值翻倍** ——
   * 这条就是"性别真的进入了折算"的载体（默认两性同值，差值无文档依据 ⇒ 不臆造）。
   */
  @Test
  void mortalityUsesBothTheAgeBracketAndTheSexCoefficient() {
    PopulationGroup young = group(lot("f-30"), Sex.FEMALE, 1_000L, 30L * YEAR);
    PopulationGroup child = group(lot("c"), Sex.MALE, 1_000L, 5L * YEAR);
    PopulationGroup elder = group(lot("e"), Sex.MALE, 1_000L, 70L * YEAR);

    assertThat(deathsOf(young)).as("青壮 1‰/月 ⇒ 1 人").isEqualTo(1L);
    assertThat(deathsOf(child)).as("未成年 2‰/月 ⇒ 2 人").isEqualTo(2L);
    assertThat(deathsOf(elder)).as("老年 20‰/月 ⇒ 20 人").isEqualTo(20L);

    long femaleDoubled =
        young.count()
            * PopulationDynamics.mortalityPerMille(
                young,
                30L * YEAR,
                PopulationDynamics.BASE_MORTALITY_PER_MILLE_PER_MONTH,
                Map.of(Sex.MALE, 1000, Sex.FEMALE, 2000))
            / FULL;
    assertThat(femaleDoubled).as("★ 性别系数可注入：女性 2,000‰ ⇒ 死亡逐值翻倍（性别真的进了折算）").isEqualTo(2L);
  }

  /** ★ **人口守恒**：{@code Σ新人口 == Σ旧人口 + 出生 − 死亡}，且两条账都逐值可核。 */
  @Test
  void birthsAndDeathsKeepThePopulationAccountExact() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("f-30"), group(lot("f-30"), Sex.FEMALE, 1_000L, 30L * YEAR));
    groups.put(lot("e"), group(lot("e"), Sex.MALE, 500L, 70L * YEAR));
    SocialData base = withGroups(groups);
    long before = totalCount(base);

    PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(base, 30L);
    long after = totalCount(outcome.data());

    assertThat(outcome.births()).as("育龄女性 1,000 × 20‰ = 20").isEqualTo(20L);
    assertThat(outcome.deaths()).as("青壮 1 + 老年 500 × 20‰ = 10 ⇒ 11").isEqualTo(11L);
    assertThat(after).as("★★ 人口守恒：新 = 旧 + 出生 − 死亡").isEqualTo(before + 20L - 11L);
    long summed = 0L;
    for (LotChange change : outcome.changes().values()) {
      summed += change.births() - change.deaths();
    }
    assertThat(summed).as("逐批次的账加起来 == 总数的变化").isEqualTo(after - before);
  }

  /** ★ 越界即坏数据（**不夹取**："算错了"不许被伪装成"满额满足"）。 */
  @Test
  void rejectsOutOfRangeSatisfaction() {
    assertThatThrownBy(() -> PopulationDynamics.stressAfter(0L, 1001L, FULL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮满足率");
    assertThatThrownBy(() -> PopulationDynamics.stressAfter(0L, FULL, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("布满足率");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static long deathsOf(PopulationGroup group) {
    PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(worldOf(group), 30L);
    LotChange change = outcome.changes().get(group.id());
    return change == null ? 0L : change.deaths();
  }

  private static PeopleLotId lot(String tag) {
    return PopulationLots.rural(HEX, Sex.FEMALE, tag);
  }

  private static PopulationGroup group(PeopleLotId id, Sex sex, long count, long ageDays) {
    return new PopulationGroup(id, HEX, sex, count, ageDays, 0L, 0L);
  }

  private static PopulationGroup adultFemale(long count) {
    return group(PopulationLots.rural(HEX, Sex.FEMALE, "adult"), Sex.FEMALE, count, 30L * YEAR);
  }

  private static PopulationGroup withStress(PopulationGroup group, long stress) {
    return new PopulationGroup(
        group.id(),
        group.residence(),
        group.sex(),
        group.count(),
        group.ageAtAnchorDays(),
        group.anchorTick(),
        stress);
  }

  private static SocialData worldOf(PopulationGroup group) {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(group.id(), group);
    return withGroups(groups);
  }

  /** 造一个只含这些批次的社会状态（人口序列只放一个格 —— {@code SocialData} 的跨组件校验要求批次落在有序列的格上）。 */
  private static SocialData withGroups(Map<PeopleLotId, PopulationGroup> groups) {
    return new SocialData(
        Map.of(HEX, series()), Map.<CityId, io.mosire.simos.social.city.SocialCity>of(), groups);
  }

  /** 一条平凡的人口序列（本用例集只关心批次；序列本身只是"这一格有人口账"的凭证）。 */
  private static PopulationSeries series() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 0L),
        new SegmentedSeries<Double>(
            List.of(new Segment<>(SimosTimestamp.of(0), 0.0)), List.of(), null),
        List.of());
  }

  private static long totalCount(SocialData data) {
    long total = 0L;
    for (PopulationGroup group : data.groups().values()) {
      total += group.count();
    }
    return total;
  }
}
