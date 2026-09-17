package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** UnitId 的三件套：裸值 toString、static parse、空白即抛（形制照 RegionId/CityId）。 */
class UnitIdTest {

  @Test
  void toStringIsTheBareValueAndParseRoundTrips() {
    assertThat(new UnitId("u-f82a").toString()).isEqualTo("u-f82a");
    assertThat(UnitId.parse("u-f82a")).isEqualTo(new UnitId("u-f82a"));
  }

  @Test
  void blankValuesAreRejected() {
    assertThatThrownBy(() -> new UnitId(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value");
    assertThatThrownBy(() -> new UnitId("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitId.parse(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
