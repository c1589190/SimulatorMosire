package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapWrapTest {

  @Test
  void canonicalQIsPeriodicAndRowAware() {
    MapWrap wrap = new MapWrap(true, -2, 5);
    assertThat(wrap.canonicalQ(-2, 0)).isEqualTo(-2);
    assertThat(wrap.canonicalQ(3, 0)).isEqualTo(-2);
    // pointy-top row 1 shifts the q origin by floor(r/2)=0? Java floorDiv(1,2)=0;
    // row -1 shifts by -1, so the visible row origin moves with the stagger.
    assertThat(wrap.qMinAt(-1)).isEqualTo(-1);
    assertThat(wrap.canonical(new HexCoord(4, -1))).isEqualTo(new HexCoord(-1, -1));
  }

  @Test
  void neighborsAndDistanceCrossTheSeam() {
    MapWrap wrap = new MapWrap(true, 0, 5);
    List<HexCoord> neighbors = wrap.neighbors(new HexCoord(0, 0));
    assertThat(neighbors).contains(new HexCoord(4, 0));
    assertThat(wrap.distance(new HexCoord(0, 0), new HexCoord(4, 0))).isEqualTo(1);
    assertThat(wrap.distance(new HexCoord(0, 0), new HexCoord(2, 0))).isEqualTo(2);
  }

  @Test
  void noneRejectsAWidthAndWrappedRequiresOne() {
    assertThat(MapWrap.none().wrapX()).isFalse();
    assertThatThrownBy(() -> new MapWrap(false, 0, 5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MapWrap(true, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
