package io.mosire.simos.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link SocialData}：两个组件都保序不可变、拒 null、两个 with。 */
class SocialDataTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final CityId C1 = new CityId("c1");

  private static SocialCity city() {
    return new SocialCity(C1, "城甲", H00, Optional.empty(), 100L, Map.of());
  }

  private static PopulationSeries population() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 100L),
        SegmentedSeries.of(List.of(new Segment<>(SimosTimestamp.of(0), 0.0)), List.of(), null),
        List.of());
  }

  @Test
  void emptyHasBothComponentsEmpty() {
    SocialData data = SocialData.empty();
    assertThat(data.populations()).isEmpty();
    assertThat(data.cities()).isEmpty();
  }

  @Test
  void citiesAreFrozenAgainstLaterMutation() {
    Map<CityId, SocialCity> mutable = new LinkedHashMap<>();
    mutable.put(C1, city());
    SocialData data = new SocialData(Map.of(), mutable);
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
    SocialData data = new SocialData(Map.of(), inserted);
    assertThat(data.cities().keySet()).containsExactly(c2, c3, c1);
  }

  @Test
  void rejectsNullsEverywhere() {
    assertThatThrownBy(() -> new SocialData(null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);

    Map<CityId, SocialCity> nullValue = new LinkedHashMap<>();
    nullValue.put(C1, null);
    assertThatThrownBy(() -> new SocialData(Map.of(), nullValue))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★ `cities` 为 null **不是**错误，而是**老档兼容的入口**：升级前的快照没有这个键（真档 `worlds/v17levant.json` 就是这样），Jackson
   * 会把它绑成 null。此处必须收成空表 —— 抛了等于"整个世界打不开"（先例：SdInfoEntry 的 affiliations）。
   */
  @Test
  void nullCitiesMeansEmptyCitiesForLegacyArchives() {
    SocialData data = new SocialData(Map.of(H00, population()), null);

    assertThat(data.cities()).as("旧档没有城市 ⇒ 空表").isEmpty();
    assertThat(data.populations()).containsOnlyKeys(H00);
  }

  @Test
  void withCitiesAndWithPopulationsSwapOneComponentEach() {
    SocialData base = new SocialData(Map.of(H00, population()), Map.of());
    SocialData withCity = base.withCities(Map.of(C1, city()));
    assertThat(withCity.cities()).containsOnlyKeys(C1);
    assertThat(withCity.populations()).isEqualTo(base.populations());

    SocialData withPopulation = base.withPopulations(Map.of());
    assertThat(withPopulation.populations()).isEmpty();
    assertThat(withPopulation.cities()).isEqualTo(base.cities());
  }
}
