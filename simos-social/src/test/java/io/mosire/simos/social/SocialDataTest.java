package io.mosire.simos.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
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
}
