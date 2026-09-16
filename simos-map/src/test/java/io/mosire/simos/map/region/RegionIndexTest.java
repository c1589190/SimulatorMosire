package io.mosire.simos.map.region;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 归属索引：O(1) 查找（解 L5）、重叠区域的裁决**确定**。
 *
 * <p>★ 本类**不写**"GameMap 不含 RegionIndex"那条断言 —— {@code GameMap} 在 Task 5 才存在，该断言归 Task 5 的 {@code
 * regionIndexIsDerivedNotStored}，只写一处。
 */
class RegionIndexTest {

  private static final HexCoord OWNED = new HexCoord(0, 0);

  @Test
  void findsOwningRegionAndReportsMembership() {
    RegionIndex index = RegionIndex.of(List.of(region("r1", Set.of(OWNED, new HexCoord(1, 0)))));

    assertThat(index.regionOf(OWNED)).isEqualTo(new RegionId("r1"));
    assertThat(index.hasRegion(OWNED)).isTrue();
  }

  @Test
  void unknownHexReturnsNull() {
    RegionIndex index = RegionIndex.of(List.of(region("r1", Set.of(OWNED))));

    assertThat(index.regionOf(new HexCoord(9, 9))).isNull();
    assertThat(index.hasRegion(new HexCoord(9, 9))).isFalse();
  }

  /** ★ 重叠时"先到者胜"，而"先到"由 **id 字典序**定义（不是入参顺序）—— 故两种入参顺序必须得同一结果， 且结果必须是 id 较小的那个。 */
  @Test
  void overlappingRegionsResolveDeterministically() {
    RegionIndex idAscending =
        RegionIndex.of(List.of(region("a", Set.of(OWNED)), region("b", Set.of(OWNED))));
    RegionIndex idDescending =
        RegionIndex.of(List.of(region("b", Set.of(OWNED)), region("a", Set.of(OWNED))));

    assertThat(idAscending.regionOf(OWNED)).isEqualTo(new RegionId("a"));
    assertThat(idDescending.regionOf(OWNED)).isEqualTo(new RegionId("a"));
  }

  /**
   * ★ **承重断言是"只触发一次 {@code get}"**，不是"大索引与小索引给出同一结果"（那只是冒烟检查）。
   *
   * <p>做法：用一个**计数 {@code Map}** 包住底层表注入索引 —— 遍历式实现会触发 0 次（或 n 次）{@code get}， 一次 {@code Map.get}
   * 的实现恰好触发 1 次。不测时间（不稳）。
   */
  @Test
  void regionOfIsConstantTime() {
    // 冒烟：同一格的归属不依赖索引里其他条目的数量
    List<Region> many = new ArrayList<>();
    int regionCount = 100;
    int hexesPerRegion = 1000;
    for (int i = 0; i < regionCount; i++) {
      Set<HexCoord> hexes = new HashSet<>();
      for (int j = 0; j < hexesPerRegion; j++) {
        hexes.add(new HexCoord(i * hexesPerRegion + j, 0));
      }
      many.add(Region.of(new RegionId(String.format("r%03d", i)), "R" + i, hexes, null));
    }
    RegionIndex big = RegionIndex.of(many);
    RegionIndex tiny = RegionIndex.of(List.of(many.getFirst()));
    assertThat(big.regionOf(OWNED)).isEqualTo(tiny.regionOf(OWNED));
    assertThat(big.regionOf(OWNED)).isEqualTo(new RegionId("r000"));

    // ★ 承重：regionOf 只查一次表
    CountingMap counting = new CountingMap();
    counting.put(OWNED, new RegionId("r1"));
    RegionIndex injected = new RegionIndex(counting);
    assertThat(counting.gets()).as("构造期不该触发 get（否则计数没有判别力）").isZero();

    assertThat(injected.regionOf(OWNED)).isEqualTo(new RegionId("r1"));
    assertThat(counting.gets()).isEqualTo(1);
    assertThat(injected.regionOf(new HexCoord(9, 9))).isNull();
    assertThat(counting.gets()).as("无归属也只查一次").isEqualTo(2);
  }

  private static Region region(String id, Set<HexCoord> hexes) {
    return Region.of(new RegionId(id), "区域 " + id, hexes, null);
  }

  /** 计数包装层：只数 {@code get}（{@code put}/{@code containsKey} 走的是 {@code HashMap} 的私有路径，不经过它）。 */
  private static final class CountingMap extends HashMap<HexCoord, RegionId> {

    private int gets;

    @Override
    public RegionId get(Object key) {
      gets++;
      return super.get(key);
    }

    int gets() {
      return gets;
    }
  }
}
