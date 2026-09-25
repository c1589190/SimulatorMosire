package io.mosire.simos.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationHeadline;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.PopulationSource;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.social.population.UrbanRural;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link SocialData}：三个组件都保序不可变、拒 null、三个 with（R1 起第三个组件 = 人口批次）。 */
class SocialDataTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final CityId C1 = new CityId("c1");
  private static final CityId C2 = new CityId("c2");

  /** 一条落在 {@code H00} 的农村批次（该格必须有 {@code populations} 序列，见跨组件校验那条用例）。 */
  private static PeopleLotId ruralLot() {
    return PopulationLots.rural(H00, Sex.MALE, "1");
  }

  private static Map<PeopleLotId, PopulationGroup> oneGroup() {
    return Map.of(ruralLot(), new PopulationGroup(ruralLot(), H00, Sex.MALE, 100L, 0L, 0L));
  }

  private static SocialCity city() {
    return new SocialCity(C1, "城甲", H00, Optional.empty(), Map.of());
  }

  private static PopulationSeries population() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 100L),
        SegmentedSeries.of(List.of(new Segment<>(SimosTimestamp.of(0), 0.0)), List.of(), null),
        List.of());
  }

  @Test
  void emptyHasAllThreeComponentsEmpty() {
    SocialData data = SocialData.empty();
    assertThat(data.populations()).isEmpty();
    assertThat(data.cities()).isEmpty();
    assertThat(data.groups()).isEmpty();
  }

  @Test
  void citiesAreFrozenAgainstLaterMutation() {
    Map<CityId, SocialCity> mutable = new LinkedHashMap<>();
    mutable.put(C1, city());
    SocialData data = new SocialData(Map.of(), mutable, Map.of());
    mutable.put(new CityId("c2"), city());
    assertThat(data.cities()).containsOnlyKeys(C1);
  }

  @Test
  void citiesFollowInsertionOrder() {
    CityId c1 = new CityId("c1");
    CityId c2 = new CityId("c2");
    CityId c3 = new CityId("c3");
    Map<CityId, SocialCity> inserted = new LinkedHashMap<>();
    inserted.put(c2, city());
    inserted.put(c3, city());
    inserted.put(c1, city());
    SocialData data = new SocialData(Map.of(), inserted, Map.of());
    assertThat(data.cities().keySet()).containsExactly(c2, c3, c1);
  }

  @Test
  void rejectsNullsEverywhere() {
    assertThatThrownBy(() -> new SocialData(null, Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);

    Map<CityId, SocialCity> nullValue = new LinkedHashMap<>();
    nullValue.put(C1, null);
    assertThatThrownBy(() -> new SocialData(Map.of(), nullValue, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★ `cities` 为 null **不是**错误，而是**老档兼容的入口**：升级前的快照没有这个键（真档 `worlds/v17levant.json` 就是这样），Jackson
   * 会把它绑成 null。此处必须收成空表 —— 抛了等于"整个世界打不开"（先例：SdInfoEntry 的 affiliations）。
   */
  @Test
  void nullCitiesMeansEmptyCitiesForLegacyArchives() {
    SocialData data = new SocialData(Map.of(H00, population()), null, Map.of());

    assertThat(data.cities()).as("旧档没有城市 ⇒ 空表").isEmpty();
    assertThat(data.populations()).containsOnlyKeys(H00);
  }

  @Test
  void withCitiesAndWithPopulationsSwapOneComponentEach() {
    SocialData base = new SocialData(Map.of(H00, population()), Map.of(), Map.of());
    SocialData withCity = base.withCities(Map.of(C1, city()));
    assertThat(withCity.cities()).containsOnlyKeys(C1);
    assertThat(withCity.populations()).isEqualTo(base.populations());

    SocialData withPopulation = base.withPopulations(Map.of());
    assertThat(withPopulation.populations()).isEmpty();
    assertThat(withPopulation.cities()).isEqualTo(base.cities());
  }

  // ── R1：第三个组件 groups（人口批次）────────────────────────────────────────────────

  @Test
  void groupsAreFrozenAgainstLaterMutation() {
    Map<PeopleLotId, PopulationGroup> mutable = new LinkedHashMap<>(oneGroup());
    SocialData data = new SocialData(Map.of(H00, population()), Map.of(), mutable);
    mutable.put(
        PopulationLots.rural(H00, Sex.FEMALE, "1"),
        new PopulationGroup(
            PopulationLots.rural(H00, Sex.FEMALE, "1"), H00, Sex.FEMALE, 7L, 0L, 0L));
    assertThat(data.groups()).containsOnlyKeys(ruralLot());
  }

  @Test
  void groupsFollowInsertionOrder() {
    PeopleLotId male = PopulationLots.rural(H00, Sex.MALE, "1");
    PeopleLotId female = PopulationLots.rural(H00, Sex.FEMALE, "1");
    Map<PeopleLotId, PopulationGroup> inserted = new LinkedHashMap<>();
    inserted.put(female, new PopulationGroup(female, H00, Sex.FEMALE, 40L, 0L, 0L));
    inserted.put(male, new PopulationGroup(male, H00, Sex.MALE, 60L, 0L, 0L));
    SocialData data = new SocialData(Map.of(H00, population()), Map.of(), inserted);
    assertThat(data.groups().keySet()).as("迭代序 = 插入序（不是内容序）").containsExactly(female, male);
  }

  @Test
  void rejectsNullsInGroupsBothWays() {
    Map<PeopleLotId, PopulationGroup> nullValue = new LinkedHashMap<>();
    nullValue.put(ruralLot(), null);
    assertThatThrownBy(() -> new SocialData(Map.of(H00, population()), Map.of(), nullValue))
        .isInstanceOf(IllegalArgumentException.class);

    Map<PeopleLotId, PopulationGroup> nullKey = new LinkedHashMap<>();
    nullKey.put(null, new PopulationGroup(ruralLot(), H00, Sex.MALE, 1L, 0L, 0L));
    assertThatThrownBy(() -> new SocialData(Map.of(H00, population()), Map.of(), nullKey))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★★ **R1 唯一保留的那一行兼容**（用户 2026-09-26 裁定：旧档重建也没关系，**只留这一行**）：升级前的快照没有 {@code groups} 这个键（随包的创世文件
   * {@code worlds/v17levant.json} 就是这样），Jackson 会把它绑成 null ⇒ 收成空表，**不抛** ——
   * 抛了等于"整个世界打不开"（先例：SdInfoEntry 的 affiliations、本类的 cities）。
   */
  @Test
  void nullGroupsMeansEmptyGroupsForLegacyArchives() {
    SocialData data = new SocialData(Map.of(H00, population()), Map.of(), null);

    assertThat(data.groups()).as("旧档没有批次 ⇒ 空表").isEmpty();
    assertThat(data.populations()).containsOnlyKeys(H00);
  }

  @Test
  void withGroupsSwapsOnlyThatComponent() {
    SocialData base = new SocialData(Map.of(H00, population()), Map.of(C1, city()), Map.of());
    SocialData withGroups = base.withGroups(oneGroup());

    assertThat(withGroups.groups()).containsOnlyKeys(ruralLot());
    assertThat(withGroups.populations()).isEqualTo(base.populations());
    assertThat(withGroups.cities()).isEqualTo(base.cities());
  }

  /**
   * ★★ **跨组件校验**（设计稿 §十.7：「`group` 必须落在有 `populations` 序列的格上」）：批次落在没有序列的格上 ⇒ 构造期拒。
   *
   * <p>★ 判别力：把这条校验删掉，本用例当场红（那正是"两笔人口账各说各话"的入口）；而上面每一条用到批次的用例都落在有序列的格上， 故它们都不会因为这条校验而被迫改动。
   */
  @Test
  void rejectsGroupsOnHexesWithoutAPopulationSeries() {
    HexCoord orphan = new HexCoord(9, 9);
    PeopleLotId lot = PopulationLots.rural(orphan, Sex.MALE, "1");
    Map<PeopleLotId, PopulationGroup> groups =
        Map.of(lot, new PopulationGroup(lot, orphan, Sex.MALE, 10L, 0L, 0L));

    assertThatThrownBy(
            () -> new SocialData(Map.of(H00, population()) /* 只有 H00 有序列 */, Map.of(), groups))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有 populations 序列");
  }

  /**
   * ★ **派生量之一：该格人口 = Σ 该格各批次的 count**（旧算法"农村序列 + 各城人口"的等价物）。
   *
   * <p>★ 判别力（夹具**刻意城乡混合**）：把 {@code populationAt} 改成"只看农村批次"（H00 会得 1,000 而非 2,500）或"漏掉某格" （另一格得
   * 0），三条断言逐值对不上。★ 这条夹具上的差别**实测过**：全农村的夹具下"只看农村"照样绿 ⇒ 那样写等于没有判别力。 ★ 城镇批次的落点上**没有登记城市**也照样计入 ——
   * 该格人口与"那座城在不在"是两件事（前者只看批次落点）。
   */
  @Test
  void populationAtSumsEveryLotOnTheHex() {
    PeopleLotId male = PopulationLots.rural(H00, Sex.MALE, "1");
    PeopleLotId female = PopulationLots.rural(H00, Sex.FEMALE, "1");
    PeopleLotId urban = PopulationLots.urban(C1, Sex.MALE, "1");
    HexCoord otherHex = new HexCoord(1, 0);
    PeopleLotId otherLot = PopulationLots.rural(otherHex, Sex.MALE, "1");
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(male, new PopulationGroup(male, H00, Sex.MALE, 600L, 0L, 0L));
    groups.put(female, new PopulationGroup(female, H00, Sex.FEMALE, 400L, 0L, 0L));
    groups.put(urban, new PopulationGroup(urban, H00, Sex.MALE, 1_500L, 0L, 0L));
    groups.put(otherLot, new PopulationGroup(otherLot, otherHex, Sex.MALE, 99L, 0L, 0L));
    SocialData data =
        new SocialData(Map.of(H00, population(), otherHex, population()), Map.of(), groups);

    assertThat(data.populationAt(H00)).as("农村 600 + 400，城镇 1,500").isEqualTo(2_500L);
    assertThat(data.populationAt(otherHex)).isEqualTo(99L);
    assertThat(data.populationAt(new HexCoord(5, 5))).as("没有批次 ⇒ 0（不是异常）").isZero();
  }

  /**
   * ★★ **派生量之二：城的城镇人口 = 该城各批次之和**（R1 的 T5：{@code SocialCity.population} 不再是字段）。
   *
   * <p>★ 判别力：把 {@code urbanPopulationAt} 改成读农村批次、或按落点（而不是按批次身份）求和 ⇒ 逐值对不上；城不存在必须**抛** 而不是静默给 0（拼错
   * id 与"这座城没人"必须可分辨）。
   */
  @Test
  void urbanPopulationAtSumsTheCitysOwnLotsOnly() {
    PeopleLotId male = PopulationLots.urban(C1, Sex.MALE, "1");
    PeopleLotId female = PopulationLots.urban(C1, Sex.FEMALE, "2");
    PeopleLotId rural = PopulationLots.rural(H00, Sex.MALE, "1");
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(male, new PopulationGroup(male, H00, Sex.MALE, 3_000L, 0L, 0L));
    groups.put(female, new PopulationGroup(female, H00, Sex.FEMALE, 2_000L, 0L, 0L));
    groups.put(rural, new PopulationGroup(rural, H00, Sex.MALE, 500L, 0L, 0L));
    SocialData data = new SocialData(Map.of(H00, population()), Map.of(C1, city()), groups);

    assertThat(data.urbanPopulationAt(C1))
        .as("3,000 + 2,000（农村那 500 不算城里人；两批的细分名不同也照样归本城）")
        .isEqualTo(5_000L);
    assertThat(data.populationAt(H00)).as("该格总人口 = 农村 500 + 城镇 5,000").isEqualTo(5_500L);
    assertThatThrownBy(() -> data.urbanPopulationAt(new CityId("c-不存在")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("城市不存在");
  }

  // ── R1.5：三个"按格切一刀"的派生量（年龄结构 / 性别 / 城乡）──────────────────────────────

  /** 另一格（与 {@code H00} 不同格）：用来钉"只看某一格 / 只看农村"这类实现。 */
  private static final HexCoord H10 = new HexCoord(1, 0);

  /** 只有序列、**一条批次都没有**的格（R1.5 的"零人口的格返回三个 0，不是缺键"）。 */
  private static final HexCoord H01 = new HexCoord(0, 1);

  /** 有序列、但批次全是 {@code count=0} 的格（创世播种器就长这样，见 {@code PopulationSeeder}）。 */
  private static final HexCoord H99 = new HexCoord(9, 9);

  private static final long DAYS_PER_YEAR = 365L;

  /** 创世锚点（本夹具统一用 100，好让"现算"的用例把 {@code nowTick} 挪一格就跨档）。 */
  private static final long ANCHOR = 100L;

  /**
   * ★★ **刻意混合的夹具**（R1.5 的硬要求）：**每一个有批次的格**都同时有**男有女、有城有乡、三个年龄档各有**。
   *
   * <p>★ 为什么非混不可（R1 的实现者在这一处栽过）：夹具若全是农村（或全是一个档），"只看农村"或"档位写死"的实现**照样绿** ——
   * 那是**假绿**（判别力声明写在注释里、而实测不会红）。本夹具的每一格都因此在**三个维度上同时**非退化。
   *
   * <p>★ 数字都是**小整数**（好手算）：逐格、逐维度的期望值写在下面各条用例的注释里，且满足 {@code 年龄三档之和 == 性别两性之和 == 城乡之和 ==
   * total}（同一批人的三种切法）。
   */
  private static Map<PeopleLotId, PopulationGroup> mixedGroups() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    // H00（城乡 + 两性 + 三档齐全）：农村 100(男,5岁) + 200(女,20岁) + 50(男,70岁)；城镇 700(女,30岁) + 300(男,10岁)。
    put(groups, PopulationLots.rural(H00, Sex.MALE, "0"), H00, Sex.MALE, 100L, 5L * DAYS_PER_YEAR);
    put(
        groups,
        PopulationLots.rural(H00, Sex.FEMALE, "1"),
        H00,
        Sex.FEMALE,
        200L,
        20L * DAYS_PER_YEAR);
    put(groups, PopulationLots.rural(H00, Sex.MALE, "2"), H00, Sex.MALE, 50L, 70L * DAYS_PER_YEAR);
    put(
        groups,
        PopulationLots.urban(C1, Sex.FEMALE, "1"),
        H00,
        Sex.FEMALE,
        700L,
        30L * DAYS_PER_YEAR);
    put(groups, PopulationLots.urban(C1, Sex.MALE, "0"), H00, Sex.MALE, 300L, 10L * DAYS_PER_YEAR);
    // H10：农村 11(男,40岁) + 城镇 22(女,65岁) —— 与 H00 的分布**不同**（只看 H00 的实现会在这一格错）。
    put(groups, PopulationLots.rural(H10, Sex.MALE, "1"), H10, Sex.MALE, 11L, 40L * DAYS_PER_YEAR);
    put(
        groups,
        PopulationLots.urban(C2, Sex.FEMALE, "2"),
        H10,
        Sex.FEMALE,
        22L,
        65L * DAYS_PER_YEAR);
    // H99：6 条 count=0 的批次（创世播种器对"零人口的格"就是这个形状）⇒ 各维度全 0，但**键必须齐**。
    for (Sex sex : Sex.values()) {
      for (String cohort : List.of("0", "1", "2")) {
        put(groups, PopulationLots.rural(H99, sex, cohort), H99, sex, 0L, 7L * DAYS_PER_YEAR);
      }
    }
    return groups;
  }

  private static void put(
      Map<PeopleLotId, PopulationGroup> groups,
      PeopleLotId id,
      HexCoord at,
      Sex sex,
      long count,
      long ageAtAnchorDays) {
    groups.put(id, new PopulationGroup(id, at, sex, count, ageAtAnchorDays, ANCHOR));
  }

  /** 混合夹具的 {@link SocialData}（四个格都有序列：H00/H10 有批次，H01 一条都没有，H99 全是 0 人批次）。 */
  private static SocialData mixedData() {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(H00, population());
    populations.put(H10, population());
    populations.put(H01, population());
    populations.put(H99, population());
    return new SocialData(populations, Map.of(C1, city(), C2, cityOf(C2)), mixedGroups());
  }

  private static SocialCity cityOf(CityId id) {
    return new SocialCity(id, "城" + id.value(), H10, Optional.empty(), Map.of());
  }

  /**
   * ★★ **年龄结构 = 三档人数，且档位由 {@code nowTick} 现算**（不是批次里存的档）。
   *
   * <p>★ 判别力（夹具刻意混合）：
   *
   * <ul>
   *   <li>H00 期望 {@code 0-14 = 400}（男 100 的 5 岁 + 男 300 的 10 岁）、{@code 15-59 = 900}（女 200 的 20 岁 +
   *       女 700 的 30 岁）、{@code 60+ = 50}（男 50 的 70 岁）；**三档都非零**⇒"只看某一档 / 把档写死"的实现当场红；
   *   <li>H10 期望 {@code 0-14 = 0}、{@code 15-59 = 11}、{@code 60+ = 22}：**键必须齐**（0 也要在，不是缺键）—— 且它与
   *       H00 的分布不同 ⇒ "只看 H00 / 只看农村"的实现也红；
   *   <li>H99（6 条 0 人批次）与 H01（**一条批次都没有**）都必须是三个 0 —— "有序列的格 ⇔ 有批次的格"是 R1
   *       的跨组件校验保证的，但**读侧不许因此少发键**（前端一次判空即可）。
   * </ul>
   */
  @Test
  void ageStructureAtBucketsEveryLotOnTheHex() {
    SocialData data = mixedData();

    Map<AgeBracket, Long> h00 = data.ageStructureAt(H00, ANCHOR);
    assertThat(h00.keySet())
        .as("键序 = 词表序（0-14 → 15-59 → 60+）——读口的字节序因此是内容的纯函数")
        .containsExactly(AgeBracket.CHILD, AgeBracket.ADULT, AgeBracket.ELDER);
    assertThat(h00.get(AgeBracket.CHILD)).as("男 100（5 岁）+ 男 300（10 岁）").isEqualTo(400L);
    assertThat(h00.get(AgeBracket.ADULT)).as("女 200（20 岁）+ 女 700（30 岁）").isEqualTo(900L);
    assertThat(h00.get(AgeBracket.ELDER)).as("男 50（70 岁）").isEqualTo(50L);

    Map<AgeBracket, Long> h10 = data.ageStructureAt(H10, ANCHOR);
    assertThat(h10.get(AgeBracket.CHILD)).as("这一格没有未成年人：0 但键在").isZero();
    assertThat(h10.get(AgeBracket.ADULT)).as("男 11（40 岁）").isEqualTo(11L);
    assertThat(h10.get(AgeBracket.ELDER)).as("女 22（65 岁）").isEqualTo(22L);

    // ★ 这两条只判"值对且键齐"（键**序**由上一条 keySet 断言承担，这里用 anyOrder 是因为 Map.of 本身没有序）。
    assertThat(data.ageStructureAt(H99, ANCHOR))
        .as("全是 0 人批次 ⇒ 三个 0（不是缺键）")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(AgeBracket.CHILD, 0L, AgeBracket.ADULT, 0L, AgeBracket.ELDER, 0L));
    assertThat(data.ageStructureAt(H01, ANCHOR))
        .as("一条批次都没有的格 ⇒ 同样是三个 0（读侧不因'没有批次'少发键）")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(AgeBracket.CHILD, 0L, AgeBracket.ADULT, 0L, AgeBracket.ELDER, 0L));
  }

  /**
   * ★★ **年龄档的边与"现算"**：{@code 14→15}、{@code 59→60} 两条边各用**同一个批次、只把 {@code nowTick} 挪一天**来钉。
   *
   * <p>★ 算式（夹具 {@code anchorTick = 100}）：
   *
   * <pre>
   * 十四岁零 364 天（= 15×365 − 1 天）的那一批：
   *   ageDaysAt(100) = 5,474 &lt; 15×365=5,475 ⇒ 0-14
   *   ageDaysAt(101) = 5,475           ⇒ 15-59      ← 只挪一天就跨档
   * 五十九岁零 364 天（= 60×365 − 1 天）的那一批：
   *   ageDaysAt(100) = 21,899 &lt; 60×365=21,900 ⇒ 15-59
   *   ageDaysAt(101) = 21,900                  ⇒ 60+
   * </pre>
   *
   * <p>★ **判别力由"档位必须是现算"承担**（这是本条存在的全部理由）：把 {@code ageStructureAt} 改成读批次的 {@code
   * ageAtAnchorDays}（"存一个档位字段"的等价物）⇒ 两条 {@code nowTick=101} 的断言当场红（它们会停在 CHILD / ADULT）。★ 边界本身由
   * {@code 15×365 − 1 / 15×365} 与 {@code 60×365 − 1 / 60×365} 两侧各钉一次 （少算一边、把 {@code <} 写成 {@code ≤}
   * 都会红）。
   */
  @Test
  void ageBracketBoundariesAreRecomputedFromTheQueryTick() {
    HexCoord hex = new HexCoord(2, 2);
    PeopleLotId child = PopulationLots.rural(hex, Sex.MALE, "0");
    PeopleLotId adult = PopulationLots.rural(hex, Sex.FEMALE, "1");
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(
        child, new PopulationGroup(child, hex, Sex.MALE, 1L, 15L * DAYS_PER_YEAR - 1L, ANCHOR));
    groups.put(
        adult, new PopulationGroup(adult, hex, Sex.FEMALE, 1L, 60L * DAYS_PER_YEAR - 1L, ANCHOR));
    SocialData data = new SocialData(Map.of(hex, population()), Map.of(), groups);

    // ★ **锚点那一刻**的整张结构（两条边各钉下侧）——★ **如实记：这一条对"现算"没有判别力**
    //   （{@code nowTick == anchorTick} 时 {@code ageDaysAt} 与 {@code ageAtAnchorDays}
    // **同值**，两种写法都给这个结果）。
    //   它守的是"14 岁零 364 天仍在 0-14、59 岁零 364 天仍在 15-59"这两条下侧边；判别力所在是下面那一条。
    assertThat(data.ageStructureAt(hex, ANCHOR))
        .as("锚点处：14 岁零 364 天 ⇒ 0-14；59 岁零 364 天 ⇒ 15-59；末档空")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(AgeBracket.CHILD, 1L, AgeBracket.ADULT, 1L, AgeBracket.ELDER, 0L));
    // ★★ **只挪一天**：两批**各自**跨过自己那条边（0-14 → 15-59、15-59 → 60+）——断言**整张结构**，
    //   不单挑某一个键：单挑会被"另一批恰好在那一档"掩盖（本用例初稿实测踩过：`ADULT == 1` 那条在变异体下**照样绿**，
    //   因为 59 岁那批本来就是 ADULT）。
    assertThat(data.ageStructureAt(hex, ANCHOR + 1L))
        .as("15×365 天 ⇒ 15-59；60×365 天 ⇒ 60+（两条边都是 `age < 上界`；整个结构挪了一格）")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(AgeBracket.CHILD, 0L, AgeBracket.ADULT, 1L, AgeBracket.ELDER, 1L));
  }

  /**
   * ★ **查询早于"出生之前"** ⇒ {@link AgeBracket#of(long)} 对负年龄 fail-closed（不静默归档）。
   *
   * <p>★ 这条把 {@code SocialData.ageStructureAt} 的 javadoc 里那句"负年龄 ⇒ 抛"钉成事实：{@code ageDaysAt} 本身
   * **不夹取、不抛**（往回推是重放/分支比较的正常查询），但"这一批人当时多大"在出生之前**没有答案** ⇒ 宁可当场出错。
   */
  @Test
  void ageStructureRejectsAQueryTickBeforeTheBatchWasBorn() {
    HexCoord hex = new HexCoord(3, 3);
    PeopleLotId lot = PopulationLots.rural(hex, Sex.MALE, "0");
    Map<PeopleLotId, PopulationGroup> groups =
        new LinkedHashMap<>(Map.of(lot, new PopulationGroup(lot, hex, Sex.MALE, 1L, 0L, ANCHOR)));
    SocialData data = new SocialData(Map.of(hex, population()), Map.of(), groups);

    assertThatThrownBy(() -> data.ageStructureAt(hex, ANCHOR - 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ageDays 不得为负");
  }

  /**
   * ★ **性别构成 = 两性人数**（键恒为两个性别）。
   *
   * <p>★ 判别力（夹具刻意混合）：H00 期望 男 {@code 100+50+300 = 450}、女 {@code 200+700 = 900}（**两性都非零**⇒
   * "只算男"或"把两个键合并"的实现当场红）；H10 期望 男 {@code 11}、女 {@code 22}（与 H00 分布不同 ⇒ 只看 H00 的实现也红）。
   */
  @Test
  void sexRatioAtCountsBothSexesOnTheHex() {
    SocialData data = mixedData();

    Map<Sex, Long> h00 = data.sexRatioAt(H00);
    assertThat(h00.keySet()).as("键序 = 词表序（MALE → FEMALE）").containsExactly(Sex.MALE, Sex.FEMALE);
    assertThat(h00.get(Sex.MALE)).as("男 100（农村,5 岁）+ 50（农村,70 岁）+ 300（城镇,10 岁）").isEqualTo(450L);
    assertThat(h00.get(Sex.FEMALE)).as("女 200（农村,20 岁）+ 700（城镇,30 岁）").isEqualTo(900L);

    assertThat(data.sexRatioAt(H10))
        .containsExactlyInAnyOrderEntriesOf(Map.of(Sex.MALE, 11L, Sex.FEMALE, 22L));
    assertThat(data.sexRatioAt(H99))
        .as("0 人批次 ⇒ 两个 0（不是缺键）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(Sex.MALE, 0L, Sex.FEMALE, 0L));
    assertThat(data.sexRatioAt(H01))
        .containsExactlyInAnyOrderEntriesOf(Map.of(Sex.MALE, 0L, Sex.FEMALE, 0L));
  }

  /**
   * ★★ **城乡构成 = 该格的城镇/农村人数**，归属**由 lot id 的前缀判**（不额外读 {@code cities}、不加"城乡"字段）。
   *
   * <p>★ 判别力（夹具刻意混合）：H00 期望 城镇 {@code 720}（女 700 + 男 300）、农村 {@code 350}（100+200+50）—— **两侧都非零且不等**
   * ⇒ "只算农村"（旧口径，R1.5 之前的读口就是这个）与"把两者相加后只发一个数"都会红。★ {@code total} 同时等于 {@code
   * populationAt(H00)}（同一批人的两种算法）。 ★ 城镇批次的落点上**没有登记城市**（H10 的 {@code c2} 不在本夹具的 cities 里也能算对 —— 见
   * {@code mixedData} 里其实登记了 C2，而 H00 的 C1 也登记了；本条**不依赖**登记，判据只是前缀）。
   */
  @Test
  void urbanRuralAtSplitsTheHexByLotIdentity() {
    SocialData data = mixedData();

    UrbanRural h00 = data.urbanRuralAt(H00);
    assertThat(h00.urban()).as("城镇：女 700 + 男 300").isEqualTo(1_000L);
    assertThat(h00.rural()).as("农村：男 100 + 女 200 + 男 50").isEqualTo(350L);
    assertThat(h00.total()).as("城乡之和 = 该格批次求和").isEqualTo(1_350L);
    assertThat(h00.total()).as("与 populationAt 同一答案（两种算法，一个人不差）").isEqualTo(data.populationAt(H00));

    assertThat(data.urbanRuralAt(H10)).isEqualTo(new UrbanRural(22L, 11L));
    assertThat(data.urbanRuralAt(H99)).as("0 人批次 ⇒ (0,0)").isEqualTo(new UrbanRural(0L, 0L));
    assertThat(data.urbanRuralAt(H01)).as("没有批次 ⇒ (0,0)，不是异常").isEqualTo(new UrbanRural(0L, 0L));
  }

  // ── R2（T0）：人口读口的口径与来源 ──────────────────────────────────────────────────

  /**
   * ★★ **有批次 ⇒ 批次求和（真值源）**：这是 R2 起 {@code population} 的**主口径**。
   *
   * <p>★ 判别力：H00 的批次和是 1,350，而它的**序列**在 ANCHOR 时刻是 100 ⇒ "一律读序列"（R1.5 之前的实现）与 "读批次但不标来源"都会在值或
   * {@code source} 上红。
   */
  @Test
  void headlinePopulationComesFromTheBatchesWhenTheHexHasThem() {
    SocialData data = mixedData();

    assertThat(data.hasGroupsAt(H00)).as("H00 有批次").isTrue();
    assertThat(data.headlinePopulationAt(H00, population(), SimosTimestamp.of(ANCHOR)))
        .as("Σ 批次 = 1,000 城镇 + 350 农村")
        .isEqualTo(new PopulationHeadline(1_350L, PopulationSource.BATCHES));
  }

  /**
   * ★★ **无批次 ⇒ 回退旧序列**（随包 bootstrap 世界与升级前的每条 revision 只有序列）：回退是**口径的一部分**， 不是兜底 ——
   * 一律读批次会把"世界还没初始化"显示成"这格没人"。
   *
   * <p>★ 判别力：H01 有序列、**一条批次都没有** ⇒ 值必须等于序列在查询时刻的取值、来源必须是 {@code legacySeries}； 把口径写成"一律读批次"⇒ 这里读到 0
   * ⇒ 红。
   */
  @Test
  void headlinePopulationFallsBackToTheLegacySeriesWhenTheHexHasNoBatches() {
    SocialData data = mixedData();
    PopulationSeries series = population();

    assertThat(data.hasGroupsAt(H01)).as("H01 没有批次").isFalse();
    assertThat(data.headlinePopulationAt(H01, series, SimosTimestamp.of(ANCHOR)))
        .as("回退口径：序列在查询时刻的取值")
        .isEqualTo(
            new PopulationHeadline(
                series.valueAt(SimosTimestamp.of(ANCHOR)), PopulationSource.LEGACY_SERIES));
  }

  /**
   * ★★ **"有批次"判的是批次在不在，不是"人数是否为 0"**：H99 有 6 条 {@code count=0} 的批次（创世播种器对零人口的格 就长这样）⇒ 读数是
   * 0，但**来源仍是批次**（"这格确实没人"与"这格没有数据"是两件事）。
   *
   * <p>★ 判别力：把判据写成"批次求和 &gt; 0 才用批次" ⇒ 本条读到 {@code legacySeries} 与序列的 100 ⇒ 红。
   */
  @Test
  void zeroCountBatchesStillCountAsBatches() {
    SocialData data = mixedData();

    assertThat(data.hasGroupsAt(H99)).as("H99 有批次（只是每批 0 人）").isTrue();
    assertThat(data.headlinePopulationAt(H99, population(), SimosTimestamp.of(ANCHOR)))
        .isEqualTo(new PopulationHeadline(0L, PopulationSource.BATCHES));
  }

  /** ★ {@link SocialData#groupsAt} 逐条给出该格的批次（保序 = 与 {@code groups} 的插入序同序）。 */
  @Test
  void groupsAtListsThatHexesBatchesInInsertionOrder() {
    SocialData data = mixedData();

    assertThat(data.groupsAt(H00))
        .extracting(group -> group.id().value())
        .containsExactly(
            "rural:0_0:MALE:0",
            "rural:0_0:FEMALE:1",
            "rural:0_0:MALE:2",
            "urban:c1:FEMALE:1",
            "urban:c1:MALE:0");
    assertThat(data.groupsAt(H01)).as("没有批次 ⇒ 空表（不是异常）").isEmpty();
    assertThat(data.groupsAt(H99)).as("0 人批次也是批次").hasSize(6);
  }

  /** ★ 两个新入口都拒 null（与其余派生量同款：静默给 0 会让"参数拼错"看起来像"这格没人"）。 */
  @Test
  void headlineAndGroupsAtRejectNulls() {
    SocialData data = mixedData();

    assertThatThrownBy(() -> data.hasGroupsAt(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.groupsAt(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.headlinePopulationAt(null, population(), SimosTimestamp.of(0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.headlinePopulationAt(H00, null, SimosTimestamp.of(0)))
        .as("回退要读序列 ⇒ 它不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data.headlinePopulationAt(H00, population(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
