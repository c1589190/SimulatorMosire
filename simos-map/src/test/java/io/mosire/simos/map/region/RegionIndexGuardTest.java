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
 * ★ **L5 的守卫**（M2 关账判据第一条的第九条）：GSimulator 的 Province 归属是 6 处逐字重复的 {@code for (entry :
 * map.provinces()) if (hexes.contains(key))} 线性扫描（其中一处不 break，每次渲染都全扫）， 故每次 {@code hex:{q}_{r}}
 * 地址解析都全表扫。{@link RegionIndex#regionOf} 必须是**一次 {@code Map.get}**。
 *
 * <p>★ **为什么单独成文件、放在 region 包**（R-14-a）：判据是**计数注入**——靶子变异 （{@code regionOf}
 * 改线性）在行为上分不出来（重叠裁决仍是同一答案），只有数 {@code Map.get} 的调用次数能红。注入点是 {@code RegionIndex(Map)}
 * 这个**包私有**构造器，{@code RegressionGuardsTest} 在 {@code io.mosire.simos.map} 包进不去。形态照 {@code
 * RegionIndexTest#regionOfIsConstantTime} （不改它，本类自带同形的 {@link CountingMap}）。
 */
class RegionIndexGuardTest {

  private static final HexCoord OWNED = new HexCoord(0, 0);

  /**
   * L5（Province 归属线性扫描）：**承重断言是"一次 {@code regionOf} 只触发一次 {@code Map.get}"**。
   *
   * <p>夹具照 spec §9.1 加大：100 region × 各 1000 hex——先冒烟（大索引里答案仍正确），再注入计数层。 遍历式实现触发 **0** 次（或 N
   * 次）{@code get}，一次 {@code Map.get} 的实现恰好 **1** 次；不测时间（不稳）。
   */
  @Test
  void L5_regionOfIsIndexedNotScanned() {
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
    assertThat(big.regionOf(OWNED))
        .as("冒烟：100×1000 的索引里第一格仍属 r000（答案对不是本条的判据，计数才是）")
        .containsExactly(new RegionId("r000"));

    CountingMap counting = new CountingMap();
    counting.put(OWNED, List.of(new RegionId("r1")));
    RegionIndex injected = new RegionIndex(counting);
    assertThat(counting.gets()).as("构造期不该触发 get（否则计数没有判别力）").isZero();

    assertThat(injected.regionOf(OWNED)).containsExactly(new RegionId("r1"));
    assertThat(counting.gets()).as("一次 regionOf = 恰一次 Map.get（线性扫描是 0 次或 N 次，都不是 1）").isEqualTo(1);
    assertThat(injected.regionOf(new HexCoord(9, 9))).isEmpty();
    assertThat(counting.gets()).as("无归属也只查一次").isEqualTo(2);
  }

  /** 计数包装层：只数 {@code get}（{@code put}/{@code containsKey} 走 HashMap 的私有路径，不经过它）。 */
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
