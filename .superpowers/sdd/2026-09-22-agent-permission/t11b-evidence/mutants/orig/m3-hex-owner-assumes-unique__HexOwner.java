package io.mosire.simos.app.access;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionIndex;
import io.mosire.simos.sd.spi.NationTag;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * **hex 的归属国家**（spec §3.4，判据 **J6**）：{@code hex → 它所属的区域 → 其中带 nation: tag 的那些 → 国家集合}。
 *
 * <p>★ **返回的是一个集合，不是唯一归属**（M8-U1 用户裁定：「hex 只是地形块，应当兼容多种从属」）：一个格可以同时落在 若干区域里，其中若干可以是不同国家的区域 ⇒
 * 「取第一个」「假定唯一」是错的。**这是本类最容易被写歪的地方**， 故 {@code HexOwnerTest} 有一条专门的多归属用例。
 *
 * <p>★ **返回国家 id，不是 tag 本身**（铁律 1：地址是定位方式，ID 是身份）：{@code nation:FRA} → {@code "FRA"}， 于是它与 {@code
 * sd:nation/FRA} 那类资源路径、以及 {@code Affiliation.Nation} 里的 id 是**同一套身份**。
 *
 * <p>★ **不按"区域是否可见"过滤**（这是 J6 能不能成立的前提）：军队决策人的范围是**逐格**前缀（{@code ArmyScope}）， 它**没有任何区域级前缀** ⇒
 * 若在这里按 {@link ToolSupport#regionVisible} 过滤，军队决策人看到的每个格都会是"无归属"， J6
 * 当场变成空话。格的可见性已在**调用点**判过（`map.hex` 先 `hexVisible` 才产出视图）， 此处给的是"这一格归谁"这个**被授予的派生事实**本身（spec §3.4
 * 的字段级可见性表）。
 *
 * <p>★ **有序返回**（{@code TreeSet} 包裹，非 {@code Set.copyOf}）：视图层直接把它落进 JSON，无序集会让**同一个状态**产出 不同字节（M2
 * Task 5 的实测教训：{@code Set.copyOf} 走散列槽位序，不是键集的纯函数）。
 */
public final class HexOwner {

  private HexOwner() {}

  /**
   * 该格归属的国家（可能多个、可能为空）。
   *
   * @param map 地图（区域与格的事实来源）
   * @param coord 要查的格
   * @return **有序**的国家 id 集合；格不属于任何国家区域时为**空集**（不是异常、也不是"回落到某个默认国家"）
   */
  public static Set<String> nationsOf(GameMap map, HexCoord coord) {
    Objects.requireNonNull(map, "map");
    return nationsOf(map, map.regionIndex(), coord);
  }

  /**
   * 复用已建好的 {@link RegionIndex} 的重载（**内部接缝**）：{@link GameMap#regionIndex()} 是**每次调用重算**的派生件 （它的
   * javadoc 明写"缓存就是第二份可漂移的副本"），故 {@link NeighborNations} 那种要扫成千上万格的调用方必须先建一次、
   * 之后逐格复用——否则是"每查一格重建一遍全图反向索引"。
   *
   * <p>★ 它同时保证"归属国家"这件事**只有一份定义**：邻国计算走的就是这里的口径，两处不会各自漂移。
   */
  static Set<String> nationsOf(GameMap map, RegionIndex index, HexCoord coord) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(coord, "coord");
    Set<String> nations = new TreeSet<>();
    for (RegionId owner : index.regionOf(coord)) {
      Region region = map.regions().get(owner);
      if (region == null) {
        continue;
      }
      String tag = region.meta().tag();
      if (NationTag.isNationTag(tag)) {
        nations.add(nationIdOf(tag));
      }
    }
    return Collections.unmodifiableSet(nations);
  }

  /**
   * {@code nation:<id>} → {@code <id>}。**用 {@link NationTag#PREFIX} 而不是手拼 {@code "nation:"}**
   * （前缀是那个类的常量，改了这里要跟着改就会编译期可见）。
   *
   * <p>★ 调用方必须先判 {@link NationTag#isNationTag}：本方法不重复判形。
   */
  static String nationIdOf(String tag) {
    return tag.substring(NationTag.PREFIX.length());
  }
}
