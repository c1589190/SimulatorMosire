package io.mosire.simos.social.city;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link SocialCity}：构造期守卫、props 保序不可变、三个 with。 */
class SocialCityTest {

  private static final CityId C1 = new CityId("c1");
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final RegionId R1 = new RegionId("r1");

  private static SocialCity city(Map<String, Object> props) {
    return new SocialCity(C1, "城甲", H00, Optional.of(R1), 12000L, props);
  }

  @Test
  void holdsItsComponents() {
    SocialCity city = city(Map.of());
    assertThat(city.id()).isEqualTo(C1);
    assertThat(city.name()).isEqualTo("城甲");
    assertThat(city.at()).isEqualTo(H00);
    assertThat(city.region()).contains(R1);
    assertThat(city.population()).isEqualTo(12000L);
  }

  /** {@code region} 为空是合法状态（无归属的城），不是缺失。 */
  @Test
  void emptyRegionIsLegal() {
    SocialCity city = new SocialCity(C1, "无主城", H00, Optional.empty(), 1L, Map.of());
    assertThat(city.region()).isEmpty();
  }

  /**
   * ★ props 保插入序：5 键、非平凡插入序、{@code containsExactly} 逐位钉键序（{@code Map.copyOf} 的槽位序会当场红）。 与 {@code
   * SocialChangeSetTest.populationOrderFollowsInsertionOrder} 同一手法。
   */
  @Test
  void propsFollowInsertionOrder() {
    Map<String, Object> inserted = new LinkedHashMap<>();
    inserted.put("tier", 3);
    inserted.put("catchmentHexes", 7);
    inserted.put("localSurplus", 1234L);
    inserted.put("nameOfFounder", "赵");
    inserted.put("isCapital", true);

    assertThat(city(inserted).props().keySet())
        .as("props 必须保插入序（绝不用 Map.copyOf）")
        .containsExactly("tier", "catchmentHexes", "localSurplus", "nameOfFounder", "isCapital");
  }

  @Test
  void propsAreFrozenAgainstLaterMutation() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("tier", 1);
    SocialCity city = city(mutable);
    mutable.put("tier", 99);
    mutable.put("added", "later");
    assertThat(city.props()).containsOnlyKeys("tier");
    assertThat(city.props().get("tier")).isEqualTo(1);
  }

  @Test
  void rejectsNullsAndBlanksAndNegativePopulation() {
    assertThatThrownBy(() -> new SocialCity(null, "n", H00, Optional.empty(), 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, null, H00, Optional.empty(), 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, "  ", H00, Optional.empty(), 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, "n", null, Optional.empty(), 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, "n", H00, null, 0L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, "n", H00, Optional.empty(), -1L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SocialCity(C1, "n", H00, Optional.empty(), 0L, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullPropsKeyOrValue() {
    Map<String, Object> nullValue = new LinkedHashMap<>();
    nullValue.put("tier", null);
    assertThatThrownBy(() -> city(nullValue)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withNameChangesOnlyName() {
    SocialCity base = city(Map.of("tier", 2));
    SocialCity renamed = base.withName("新名");
    assertThat(renamed.name()).isEqualTo("新名");
    assertThat(renamed.population()).isEqualTo(base.population());
    assertThat(renamed.props()).isEqualTo(base.props());
    assertThat(renamed.id()).isEqualTo(base.id());
    assertThat(renamed.at()).isEqualTo(base.at());
    assertThat(renamed.region()).isEqualTo(base.region());
  }

  @Test
  void withPopulationChangesOnlyPopulation() {
    SocialCity base = city(Map.of("tier", 2));
    SocialCity updated = base.withPopulation(7L);
    assertThat(updated.population()).isEqualTo(7L);
    assertThat(updated.name()).isEqualTo(base.name());
    assertThat(updated.props()).isEqualTo(base.props());
  }

  @Test
  void withPropsReplacesTheWholeTableAndRejectsNegativeUnrelated() {
    SocialCity base = city(Map.of("tier", 2));
    SocialCity updated = base.withProps(Map.of("tier", 5, "extra", "x"));
    assertThat(updated.props()).containsOnlyKeys("tier", "extra");
    assertThat(updated.name()).isEqualTo(base.name());
    assertThat(updated.population()).isEqualTo(base.population());
  }
}
