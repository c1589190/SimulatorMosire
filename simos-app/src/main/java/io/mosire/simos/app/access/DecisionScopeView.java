package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.UnitId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * **一份已算出的可见范围的可读投影**（给 GM 看的）：把 {@link ResourceScopeMap} 解码成"哪些区域 / 哪些格 / 哪些单位 + 各命名空间的前缀摘要"。
 *
 * <p>★★ **本类只解码、不算范围**——它的输入必须是 {@link DecisionCallerFactory#resourceScopesFor} 的**输出**（含 {@code
 * narrowTo(accessLimit)} 那一步）。在这里另算一份可见范围是本阶段最要防的形态：两边**都不会报错**，只会慢慢漂移， 而"GM
 * 看到的范围"与"决策人实际能碰的范围"一旦分叉，配权就失去了意义。
 *
 * <p>★ **为什么区域级授权要展开成逐格**：范围函数对国家决策人给的是**区域**前缀（真档 98 区域）、对军队给的是**格**前缀 （视野圈）。地图高亮要的是格，GM
 * 想核对的也是格（"它到底看得见哪几格"）；两种形状在这里归一成一份 hex 集合。
 *
 * <p>★ **图外的格如实计数、不静默丢**：军队的视野圈是纯半径（{@code ArmyScope} 不做地形/边界裁剪），贴着地图边缘时半径里有 格不存在于图上。丢掉它们会让"范围算出来是
 * 7 格"变成"6 格"——那正是本仓反复栽过的"把没有伪装成没有"。故拆成 {@code hexCount}（范围覆盖几格）与 {@code
 * offMapHexCount}（其中几格图上没有），{@code hexes} 只列**图上**的（高亮要画得出来）。
 *
 * <p>★ **解不出的前缀进 {@link #unparsedPrefixes()}，不当作不存在**：{@code map} 命名空间的前缀形状只有"区域 / 格 / 整张图"
 * 三种是内置范围函数认识、也是本类认识的。GM 手写的 {@code accessLimit} 前缀可以长成别的样子（{@code map: ["Map1/西线"]}）——
 * 那种前缀**仍然管用**（{@code ResourceScope} 按段边界做前缀匹配），只是投影不出区域/格。把它列出来，GM 才知道"这条限制我 没看懂"而不是"这条限制没生效"。
 */
public final class DecisionScopeView {

  /**
   * 一个命名空间的范围摘要：是否"不限"，以及前缀原文（**不截断**——GM 要核对的就是它）。
   *
   * <p>★ **包装写在构造器体里**：`List` 分量不拷贝的话，记录会把外部可变的列表原样存进来、也原样返回出去 （SpotBugs `EI_EXPOSE_REP` /
   * `EI_EXPOSE_REP2` 各一条，实测报在本行）。本仓那条纪律在此同样适用——包装那一层必须 **留在构造器体**，抽进辅助方法 SpotBugs 就判不出来了。
   */
  public record NamespaceSummary(boolean unrestricted, List<String> prefixes) {
    public NamespaceSummary {
      prefixes = List.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
    }
  }

  /** 图上可见格的边界（空范围 ⇒ 空）。 */
  public record BoundingBox(int minQ, int maxQ, int minR, int maxR) {}

  private static final String REGION_SEGMENT = "/region/";
  private static final String HEX_SEGMENT = "/hex/";
  private static final String WILDCARD = "*";

  private final List<RegionId> regions;
  private final List<HexCoord> hexes;
  private final int hexCount;
  private final int offMapHexCount;
  private final List<UnitId> units;
  private final Map<String, NamespaceSummary> namespaces;
  private final List<String> unparsedPrefixes;

  private DecisionScopeView(
      List<RegionId> regions,
      List<HexCoord> hexes,
      int hexCount,
      int offMapHexCount,
      List<UnitId> units,
      Map<String, NamespaceSummary> namespaces,
      List<String> unparsedPrefixes) {
    this.regions = List.copyOf(regions);
    this.hexes = List.copyOf(hexes);
    this.hexCount = hexCount;
    this.offMapHexCount = offMapHexCount;
    this.units = List.copyOf(units);
    this.namespaces = Map.copyOf(namespaces);
    this.unparsedPrefixes = List.copyOf(unparsedPrefixes);
  }

  /**
   * 解码一份**已算好的**范围。
   *
   * @param scopes 范围（必须来自 {@link DecisionCallerFactory#resourceScopesFor}，即已与 {@code accessLimit}
   *     求过交集）
   * @param map 当前地图（区域 → 格 的展开、以及"这一格在不在图上"都要它）
   * @param mapId 地图称谓（{@code map} 命名空间前缀的第一段，资源路径语法见 spec §3.3）
   */
  public static DecisionScopeView of(ResourceScopeMap scopes, GameMap map, String mapId) {
    Objects.requireNonNull(scopes, "scopes");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(mapId, "mapId");

    Map<String, NamespaceSummary> summaries = new TreeMap<>();
    for (Map.Entry<String, ResourceScope> entry : scopes.byNamespace().entrySet()) {
      ResourceScope scope = entry.getValue();
      List<String> prefixes = new ArrayList<>(scope.prefixes());
      prefixes.sort(null);
      summaries.put(entry.getKey(), new NamespaceSummary(scope.unrestricted(), prefixes));
    }

    // ★ 区域用"id 的字典序"排（RegionId 不是 Comparable）；格用 HexCoord 的自然序 ⇒ 同一状态两次响应逐字节相同。
    TreeSet<String> regionIds = new TreeSet<>();
    TreeSet<HexCoord> onMap = new TreeSet<>();
    TreeSet<String> offMapKeys = new TreeSet<>();
    List<String> unparsed = new ArrayList<>();

    ResourceScope mapScope = scopes.byNamespace().get(ToolSupport.MAP_NAMESPACE);
    if (mapScope != null) {
      for (String prefix : new TreeSet<>(mapScope.prefixes())) {
        decodeMapPrefix(prefix, mapId, map, regionIds, onMap, offMapKeys, unparsed);
      }
      // "不限"时前缀集是空的，但那不是"什么都看不见"——把整张图算进可见集合（否则 GM 会把 unlimited 读成 deny-all）。
      if (mapScope.unrestricted()) {
        onMap.addAll(map.hexes().keySet());
      }
    }

    TreeSet<String> unitIds = new TreeSet<>();
    ResourceScope unitScope = scopes.byNamespace().get(ToolSupport.UNIT_NAMESPACE);
    if (unitScope != null) {
      for (String prefix : unitScope.prefixes()) {
        if (!WILDCARD.equals(prefix)) {
          unitIds.add(prefix);
        }
      }
    }

    List<RegionId> regions = new ArrayList<>();
    for (String id : regionIds) {
      regions.add(new RegionId(id));
    }
    List<UnitId> units = new ArrayList<>();
    for (String id : unitIds) {
      units.add(new UnitId(id));
    }
    return new DecisionScopeView(
        regions,
        new ArrayList<>(onMap),
        onMap.size() + offMapKeys.size(),
        offMapKeys.size(),
        units,
        summaries,
        unparsed);
  }

  /** {@code map} 命名空间的一条前缀：区域 / 单格 / 整张图 / 认不出。 */
  private static void decodeMapPrefix(
      String prefix,
      String mapId,
      GameMap map,
      TreeSet<String> regionIds,
      TreeSet<HexCoord> onMap,
      TreeSet<String> offMapKeys,
      List<String> unparsed) {
    if (prefix.equals(mapId) || WILDCARD.equals(prefix)) {
      onMap.addAll(map.hexes().keySet()); // 整张图
      return;
    }
    if (!prefix.startsWith(mapId)) {
      unparsed.add(prefix);
      return;
    }
    String local = prefix.substring(mapId.length());
    int regionAt = local.indexOf(REGION_SEGMENT);
    if (regionAt == 0) {
      String id = local.substring(REGION_SEGMENT.length());
      if (id.isEmpty()) {
        unparsed.add(prefix);
        return;
      }
      regionIds.add(id);
      Region region = map.regions().get(new RegionId(id));
      if (region != null) {
        // ★ 区域在范围里 ⇒ 它**全部**的格都在范围里（区域前缀的语义就是整片区域）。
        for (HexCoord coord : region.hexes()) {
          if (map.hexes().containsKey(coord)) {
            onMap.add(coord);
          } else {
            offMapKeys.add(coord.q() + "_" + coord.r());
          }
        }
      }
      return;
    }
    int hexAt = local.indexOf(HEX_SEGMENT);
    if (hexAt == 0) {
      Optional<HexCoord> coord = parseHex(local.substring(HEX_SEGMENT.length()));
      if (coord.isEmpty()) {
        unparsed.add(prefix);
        return;
      }
      if (map.hexes().containsKey(coord.get())) {
        onMap.add(coord.get());
      } else {
        offMapKeys.add(coord.get().q() + "_" + coord.get().r());
      }
      return;
    }
    unparsed.add(prefix);
  }

  /** {@code q_r} → 坐标；形状不对 ⇒ 空（调用方记进 {@code unparsedPrefixes}）。 */
  private static Optional<HexCoord> parseHex(String text) {
    int at = text.indexOf('_');
    if (at <= 0 || at == text.length() - 1) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new HexCoord(
              Integer.parseInt(text.substring(0, at)), Integer.parseInt(text.substring(at + 1))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public List<RegionId> regions() {
    return regions;
  }

  /** 图上可见的格（升序）。 */
  public List<HexCoord> hexes() {
    return hexes;
  }

  /** 范围覆盖的格数，**含图上没有的**。 */
  public int hexCount() {
    return hexCount;
  }

  /** 范围覆盖、但图上不存在的格数（军队视野圈贴边时 > 0）。 */
  public int offMapHexCount() {
    return offMapHexCount;
  }

  public List<UnitId> units() {
    return units;
  }

  /** 各命名空间的前缀摘要（按命名空间名升序）。 */
  public Map<String, NamespaceSummary> namespaces() {
    return namespaces;
  }

  /** {@code map} 命名空间里认不出形状的前缀（空 = 内置范围函数的形状全可解码）。 */
  public List<String> unparsedPrefixes() {
    return unparsedPrefixes;
  }

  /** 图上可见格的边界；一格都没有 ⇒ 空（**不是** 0/0/0/0——那会被读成"范围就是原点那一格"）。 */
  public Optional<BoundingBox> boundingBox() {
    if (hexes.isEmpty()) {
      return Optional.empty();
    }
    int minQ = Integer.MAX_VALUE;
    int maxQ = Integer.MIN_VALUE;
    int minR = Integer.MAX_VALUE;
    int maxR = Integer.MIN_VALUE;
    for (HexCoord coord : hexes) {
      minQ = Math.min(minQ, coord.q());
      maxQ = Math.max(maxQ, coord.q());
      minR = Math.min(minR, coord.r());
      maxR = Math.max(maxR, coord.r());
    }
    return Optional.of(new BoundingBox(minQ, maxQ, minR, maxR));
  }
}
