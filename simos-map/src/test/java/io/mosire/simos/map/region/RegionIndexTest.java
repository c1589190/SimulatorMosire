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
 * 归属索引：O(1) 查找（解 L5）、多从属**全部保留**且按 id 字典序确定（M8-U1）。
 *
 * <p>★ 本类**不写**"GameMap 不含 RegionIndex"那条断言 —— {@code GameMap} 在 Task 5 才存在，该断言归 Task 5 的 {@code
 * regionIndexIsDerivedNotStored}，只写一处。
 */
class RegionIndexTest {

  private static final HexCoord OWNED = new HexCoord(0, 0);

  @Test
  void findsOwningRegionAndReportsMembership() {
    RegionIndex index = RegionIndex.of(List.of(region("r1", Set.of(OWNED, new HexCoord(1, 0)))));

    assertThat(index.regionOf(OWNED)).containsExactly(new RegionId("r1"));
    assertThat(index.hasRegion(OWNED)).isTrue();
  }

  @Test
  void unknownHexReturnsEmptyList() {
    RegionIndex index = RegionIndex.of(List.of(region("r1", Set.of(OWNED))));

    assertThat(index.regionOf(new HexCoord(9, 9))).isEmpty();
    assertThat(index.hasRegion(new HexCoord(9, 9))).isFalse();
  }

  /** ★ 多从属：重叠格**全部保留**（不是"先到者胜"），且按 **id 字典序**——故两种入参顺序必须得同一份有序列表； 夹具用 3 个区域同盖一格，覆盖 &gt;2 的情形。 */
  @Test
  void overlappingRegionsAreAllReportedInIdOrder() {
    Set<HexCoord> shared = Set.of(OWNED, new HexCoord(1, 0), new HexCoord(1, 1));
    List<Region> ascending =
        List.of(
            region("a", Set.of(OWNED, new HexCoord(1, 0))),
            region("b", Set.of(OWNED, new HexCoord(1, 1))),
            region("c", shared));
    List<Region> descending = new ArrayList<>(ascending);
    descending.sort((x, y) -> y.id().value().compareTo(x.id().value())); // 入参顺序倒过来

    RegionIndex idAscending = RegionIndex.of(ascending);
    RegionIndex idDescending = RegionIndex.of(descending);

    assertThat(idAscending.regionOf(OWNED))
        .as("3 个区域同盖一格 ⇒ 三条都在，字典序 a,b,c")
        .containsExactly(new RegionId("a"), new RegionId("b"), new RegionId("c"));
    assertThat(idDescending.regionOf(OWNED))
        .as("结果与入参迭代序无关 ⇒ 逐值相同")
        .isEqualTo(idAscending.regionOf(OWNED));
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
    assertThat(big.regionOf(OWNED)).containsExactly(new RegionId("r000"));

    // ★ 承重：regionOf 只查一次表
    CountingMap counting = new CountingMap();
    counting.put(OWNED, List.of(new RegionId("r1")));
    RegionIndex injected = new RegionIndex(counting);
    assertThat(counting.gets()).as("构造期不该触发 get（否则计数没有判别力）").isZero();

    assertThat(injected.regionOf(OWNED)).containsExactly(new RegionId("r1"));
    assertThat(counting.gets()).isEqualTo(1);
    assertThat(injected.regionOf(new HexCoord(9, 9))).isEmpty();
    assertThat(counting.gets()).as("无归属也只查一次").isEqualTo(2);
  }

  private static Region region(String id, Set<HexCoord> hexes) {
    return Region.of(new RegionId(id), "区域 " + id, hexes, null);
  }

  /** 计数包装层：只数 {@code get}（{@code put}/{@code containsKey} 走的是 {@code HashMap} 的私有路径，不经过它）。 */
  private static final class CountingMap extends HashMap<HexCoord, List<RegionId>> {

    private int gets;

    @Override
    public List<RegionId> get(Object key) {
      gets++;
      return super.get(key);
    }

    int gets() {
      return gets;
    }
  }
}
