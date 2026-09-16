package io.mosire.simos.map.hex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** spec §3.2：全模块唯一的方向表，A 序（顺时针）`E, SE, SW, W, NW, NE`。 */
class HexDirectionTest {

  @Test
  void oppositeIsInvolution() {
    for (HexDirection d : HexDirection.ALL) {
      assertThat(d.opposite().opposite()).as("%s 的反向的反向", d).isEqualTo(d);
    }
  }

  /** 钉住"恰隔 3 项"不是"隔 0 项"。 */
  @Test
  void oppositeIsNotSelf() {
    for (HexDirection d : HexDirection.ALL) {
      assertThat(d.opposite()).as("%s 的反向", d).isNotEqualTo(d);
    }
  }

  @Test
  void nextAndPrevAreInverse() {
    for (HexDirection d : HexDirection.ALL) {
      assertThat(d.next().prev()).as("%s 的下一项的上一项", d).isEqualTo(d);
      assertThat(d.prev().next()).as("%s 的上一项的下一项", d).isEqualTo(d);
      assertThat(d.next()).as("%s 的下一项", d).isNotEqualTo(d);
      assertThat(d.prev()).as("%s 的上一项", d).isNotEqualTo(d);
    }
  }

  @Test
  void allSixDistinctAndCoversEnum() {
    assertThat(HexDirection.ALL).hasSize(6);
    assertThat(HexDirection.ALL).doesNotHaveDuplicates();
    assertThat(HexDirection.ALL).containsExactly(HexDirection.values());
  }

  /**
   * ★ **唯一真值锚**：这 6 行（名字 + 偏移）是 A 序的冻结副本，来自 spec §3.2 与 GSimulator 的 `riverMask`
   * 位序。改序、改偏移、改名字，这一条必红——它是"单一方向表"这句话的唯一真值来源。
   */
  @Test
  void offsetsMatchFrozenTable() {
    String[] names = {"E", "SE", "SW", "W", "NW", "NE"};
    int[][] offsets = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};
    HexDirection[] dirs = HexDirection.values();
    assertThat(dirs).hasSize(offsets.length);
    for (int i = 0; i < offsets.length; i++) {
      assertThat(dirs[i].name()).as("索引 %d 的名字", i).isEqualTo(names[i]);
      assertThat(dirs[i].dq()).as("索引 %d（%s）的 dq", i, names[i]).isEqualTo(offsets[i][0]);
      assertThat(dirs[i].dr()).as("索引 %d（%s）的 dr", i, names[i]).isEqualTo(offsets[i][1]);
    }
  }

  @Test
  void neighborOffsetsAreAllDistinct() {
    Set<List<Integer>> offsets = new HashSet<>();
    for (HexDirection d : HexDirection.ALL) {
      offsets.add(List.of(d.dq(), d.dr()));
    }
    assertThat(offsets).hasSize(6);
  }

  @Test
  void everyNeighborIsAtDistanceOne() {
    HexCoord origin = new HexCoord(0, 0);
    for (HexDirection d : HexDirection.ALL) {
      assertThat(origin.neighbor(d).distanceTo(origin)).as("%s 的邻居", d).isEqualTo(1);
    }
  }
}
