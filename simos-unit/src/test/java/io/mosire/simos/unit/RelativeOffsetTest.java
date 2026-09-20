package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/** {@link RelativeOffset} 的逐值行为（spec §一.3 / P2：合法 HexCoord，不强制落图内）。 */
class RelativeOffsetTest {

  @Test
  void appliedToAddsTheComponents() {
    assertThat(new RelativeOffset(2, -1).appliedTo(new HexCoord(3, 4)))
        .isEqualTo(new HexCoord(5, 3));
  }

  @Test
  void aZeroOffsetIsTheIdentity() {
    HexCoord hex = new HexCoord(-7, 11);
    assertThat(new RelativeOffset(0, 0).appliedTo(hex)).isEqualTo(hex);
  }

  @Test
  void equalsIsByValue() {
    assertThat(new RelativeOffset(2, -1)).isEqualTo(new RelativeOffset(2, -1));
    assertThat(new RelativeOffset(2, -1)).isNotEqualTo(new RelativeOffset(-1, 2));
  }
}
