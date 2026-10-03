package io.mosire.simos.app.time;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayId;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>道路子图的只读读取器</b>（P4）：从 {@link GameMap} 的 {@code pathways}/{@code edges} 现算道路网络，回答 {@code
 * from→to} 的<b>道路瓶颈等级</b>；不落状态、不进变更集、不读时钟。
 *
 * <p>★★ <b>为什么住 {@code simos-app}</b>（铁律 3/4）：{@code GameMap} 住在 simos-map，经济侧（{@code
 * MarketTopology}）不反查地图。 "谁同时看得见地图与市场拓扑"的地方是组合根：本类在这里把地图派生成一个只读查询，再把方法引用交给 economy。
 *
 * <p>★★ <b>道路在哪</b>（本仓有两代表示，本类都认，且只认裸 key {@code "road"}）：
 *
 * <ol>
 *   <li>{@code GameMap.edges()} 里 key 是某条 {@link Pathway} 的 id、且那条 Pathway 的 {@code groupId =
 *       "road"} 的边 —— 兼容"线实例 + 边上标注"的形态；★ key 命中已知 Pathway id 时一律以那条线的 groupId 为准（id 恰好叫 "road" 的
 *       river 线不会因此变成道路）；
 *   <li>{@code GameMap.edges()} 里 key <b>不是</b>任何已知 Pathway id、但恰好是裸组 id {@code "road"} 的边 —— GM 的
 *       {@code map.SetEdge} 把组 id 当 tag key（{@code EdgeOperations.resolveKind} 返回注册组 id）⇒
 *       这是新建道路的主形态；
 *   <li>{@code GameMap.pathways()} 里 {@code groupId = "road"} 的链：链上每条边都是道路（等级取自该 Pathway 的 props）。
 * </ol>
 *
 * <p>★ <b>river 不是 road</b>：已知 Pathway id 一律按该线的 {@code groupId} 判；只有"不是任何已知 Pathway id"的裸 key 才按
 * {@code "road"} 组标注认。不做大小写猜测、不把"任何有边的组"当路。组是否已在 {@code pathwayGroups()} 注册不构成判据：道路标注本来就可能
 * 先于/独立于组定义到达。
 *
 * <p>★★ <b>等级规则</b>：边等级 = 来源 props 里的 {@code level}（缺省看 {@code width}）的<b>正整数</b>；来源按上面的优先级：
 * Pathway 链/Pathway 标注取该 Pathway 的 props，裸 {@code "road"} 标注取标注自己的 props。缺失/非数字/非整数/≤0 一律按
 * <b>1</b>，不猜更复杂语义。同一条 {@link EdgeRef} 同时属于多条道路线（或同时有裸标注）时取 <b>较大等级</b>（唯一且可复现的确定规则）。 等级上限截到 {@link
 * Integer#MAX_VALUE}（配合 50‰/级的折扣不会在 {@code long} 里溢出）。
 *
 * <p>★★ <b>瓶颈</b>：{@link #roadBottleneckBetween} 在道路子图上求"最小边权最大化"路径的瓶颈值（与探针 {@code
 * ProbeEconomy.roadPathMinLevel} 同口径）；<b>没有可达道路 ⇒ 0</b>。★ 与探针的具名差异：探针 {@code roadPathMinLevel}
 * 不可达时返回 {@code -1}，正式版返回 0 —— 因为正式 {@code TransportTariff.perMille} 只把等级用作折扣，0 与"无路"折扣同效。
 *
 * <p>★ <b>邻接表保序</b>（{@link LinkedHashMap} + {@link ArrayDeque}）、无随机/时钟：同一张图必然给出同一张邻接表与同一个瓶颈值。
 */
public final class RoadNetwork {

  /** 道路组 id（裸 key，与 {@code PathwayGroup.defaults()} 的 {@code "road"} 逐字一致）。 */
  public static final String ROAD_GROUP_ID = "road";

  /** 等级属性名：优先读 {@code level}。 */
  private static final String LEVEL_KEY = "level";

  /** 等级属性名：没有 {@code level} 时读 {@code width}。 */
  private static final String WIDTH_KEY = "width";

  /** hex → 邻格 → 该边的道路等级；两级都是保序不可变表。 */
  private final Map<HexCoord, Map<HexCoord, Integer>> adjacency;

  private RoadNetwork(Map<HexCoord, Map<HexCoord, Integer>> adjacency) {
    Map<HexCoord, Map<HexCoord, Integer>> copy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Map<HexCoord, Integer>> entry : adjacency.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    this.adjacency = Collections.unmodifiableMap(copy);
  }

  /** 从一张地图现算道路子图；不缓存、不落状态（道路是地图派生件）。 */
  public static RoadNetwork from(GameMap map) {
    Objects.requireNonNull(map, "map");
    Map<String, Pathway> pathwayByBareId = new LinkedHashMap<>();
    for (Map.Entry<PathwayId, Pathway> entry : map.pathways().entrySet()) {
      pathwayByBareId.put(entry.getKey().toString(), entry.getValue());
    }

    Map<EdgeRef, Integer> levelByEdge = new LinkedHashMap<>();
    // ① 边上的标注：key 若是已知的 Pathway id ⇒ 按那条线的 groupId 判（只有 road 才算，river 不算）；
    //    key 不是任何已知 Pathway、而是裸组 id "road" ⇒ 是 GM map.SetEdge 的道路标注（等级读标注自己的 props）。
    for (Map.Entry<EdgeRef, EdgeTags> entry : map.edges().entrySet()) {
      for (Map.Entry<String, Map<String, Object>> tag : entry.getValue().byPathway().entrySet()) {
        String key = tag.getKey();
        Pathway pathway = pathwayByBareId.get(key);
        if (pathway != null) {
          if (ROAD_GROUP_ID.equals(pathway.groupId())) {
            mergeLevel(levelByEdge, entry.getKey(), levelOf(pathway.props()));
          }
          continue;
        }
        if (ROAD_GROUP_ID.equals(key)) {
          mergeLevel(levelByEdge, entry.getKey(), levelOf(tag.getValue()));
        }
      }
    }
    // ② Pathway 链本身（groupId = road）：链上每条边都是道路，等级取自该 Pathway 的 props。
    for (Pathway pathway : map.pathways().values()) {
      if (!ROAD_GROUP_ID.equals(pathway.groupId())) {
        continue;
      }
      int level = levelOf(pathway.props());
      for (EdgeRef edge : pathway.edges()) {
        mergeLevel(levelByEdge, edge, level);
      }
    }
    return new RoadNetwork(buildAdjacency(levelByEdge));
  }

  /**
   * {@code from→to} 道路路径的<b>最大瓶颈等级</b>：在道路子图上最大化"路径上最小边权"；没有可达道路（或 from/to 缺图）⇒ 0。
   *
   * <p>同格返回 0（没有跨边可走）。★ 与探针 {@code roadPathMinLevel} 的具名差异：不可达时探针回 -1、正式回 0，见类注。
   */
  public int roadBottleneckBetween(HexCoord from, HexCoord to) {
    if (from == null || to == null || from.equals(to)) {
      return 0;
    }
    if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) {
      return 0;
    }
    Map<HexCoord, Integer> bestBottleneck = new LinkedHashMap<>();
    ArrayDeque<HexCoord> queue = new ArrayDeque<>();
    bestBottleneck.put(from, Integer.MAX_VALUE);
    queue.addLast(from);
    while (!queue.isEmpty()) {
      HexCoord current = queue.removeFirst();
      int currentBottleneck = bestBottleneck.getOrDefault(current, 0);
      for (Map.Entry<HexCoord, Integer> edge :
          adjacency.getOrDefault(current, Map.of()).entrySet()) {
        int candidate = Math.min(currentBottleneck, edge.getValue());
        Integer existing = bestBottleneck.get(edge.getKey());
        if (existing == null || candidate > existing) {
          bestBottleneck.put(edge.getKey(), candidate);
          queue.addLast(edge.getKey());
        }
      }
    }
    Integer result = bestBottleneck.get(to);
    return result == null ? 0 : result;
  }

  /** 一条边的等级：同 EdgeRef 多来源时取较大（{@code Math.max} 交换律 ⇒ 合并顺序不影响结果）。 */
  private static void mergeLevel(Map<EdgeRef, Integer> levelByEdge, EdgeRef edge, int level) {
    levelByEdge.merge(edge, level, Math::max);
  }

  /** 读 Pathway/边 props 的等级；缺省/非法一律 1（见类注）。 */
  private static int levelOf(Map<String, Object> props) {
    Object value = props.containsKey(LEVEL_KEY) ? props.get(LEVEL_KEY) : props.get(WIDTH_KEY);
    return positiveIntOrOne(value);
  }

  /** {@code Number}/{@code String} 里的正整数 ⇒ 该值（截到 int 上限）；其余（缺失/非数字/非整数/≤0）⇒ 1。 */
  private static int positiveIntOrOne(Object value) {
    if (value instanceof Number number) {
      double asDouble = number.doubleValue();
      if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble) || asDouble < 1.0d) {
        return 1;
      }
      long asLong = asDouble >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : (long) asDouble;
      return (int) Math.min(asLong, (long) Integer.MAX_VALUE);
    }
    if (value instanceof String text) {
      try {
        long parsed = Long.parseLong(text.trim());
        if (parsed < 1L) {
          return 1;
        }
        return (int) Math.min(parsed, (long) Integer.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return 1;
  }

  /** 每条道路边给两个方向各放一条弧；每条边只出现一次（levelByEdge 已按 EdgeRef 归并）。 */
  private static Map<HexCoord, Map<HexCoord, Integer>> buildAdjacency(
      Map<EdgeRef, Integer> levelByEdge) {
    Map<HexCoord, Map<HexCoord, Integer>> adjacency = new LinkedHashMap<>();
    for (Map.Entry<EdgeRef, Integer> entry : levelByEdge.entrySet()) {
      EdgeRef edge = entry.getKey();
      int level = entry.getValue();
      putArc(adjacency, edge.a(), edge.b(), level);
      putArc(adjacency, edge.b(), edge.a(), level);
    }
    return adjacency;
  }

  private static void putArc(
      Map<HexCoord, Map<HexCoord, Integer>> adjacency, HexCoord from, HexCoord to, int level) {
    adjacency.computeIfAbsent(from, ignored -> new LinkedHashMap<>()).put(to, level);
  }
}
