package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * ★★ <b>区域市场的只读拓扑</b>（M2.3 的派生层）：把"城市节点 + 半径 + 地图格集 + 逐格地形代价"现算成 <b>一个 hex 恰属一个区</b>的成员表 +
 * 区与区的邻接关系。
 *
 * <p>★★ <b>它是派生件，不是状态</b>（M2.0 定案）：没有进 {@code EconomyData}、没有变更集、没有 codec —— 城市的权威在 map/social，半径是
 * GM 参数；economy 只在每次结算时按调用方交给它的拓扑现算。★ 同一个输入必然给出同一份成员表 （最近节点优先、同距按 nodeId 字典序），M0.1 的"一次 360 == 三次
 * 120 同日同轮"因此不被拓扑本身破坏。
 *
 * <p>★★ <b>一个区怎么形成</b>：
 *
 * <pre>
 * 候选节点 = 调用方给的城市节点（MarketNode：锚格 + radiusHex + 报价币种 + 接收工具）
 * 逐格归属  = 半径内最近的节点；同距取 nodeId 字典序小的；都不在半径内 ⇒ 若该格有市场，退化成"单格区"
 * 邻接      = 两区锚距 ≤ rA + rB + {@link #MARKET_REGION_ADJACENCY_GAP_HEX}（第一版"直接邻接供应区"的口径）
 * </pre>
 *
 * <p>★ <b>邻接为什么不按国界、也不按"行政相邻"</b>：M2.0 #1 的原话 —— 一个王国 430 格宽、单程 200+ 天，比周期还长。
 * 第一版只考**直接邻接的供应区**，把"全国市场"这种会把 430 格塞进一次撮合的形态挡在门外。
 *
 * <p>★★ <b>地形代价的入口</b>（铁律 3/4）：本类**不读全局 {@code GameMap}**，逐格代价由调用方（组合根 app 侧， 它同时看得见 map 与
 * economy）以 {@link ToIntFunction} 只读传入；economy 侧只消费、不反查地图。★ 口径与 {@code SettlementGenerator}
 * 的腹地竞争逐字同源：{@code cost = hexDistance × moveCost(目标格)}（平原 1 … 山地 6 … 高原山地 12；海洋 999 = 不可通行）。
 *
 * <p>★ <b>本类不可变</b>：成员表、索引、节点表都在构造期冻结（{@code Collections.unmodifiable*}），可在一次结算里安全共享。
 */
public final class MarketTopology {

  /**
   * ★ <b>"直接邻接供应区"的额外容差</b>（hex）：两区锚距 ≤ {@code rA + rB + 本值} 即互为邻接供应区。
   *
   * <p>★ 为什么需要这个容差而不是"成员格必须贴边"：腹地成员按**最近节点**划分，两座城的半径若刚好相接，中间可能有一格归属给 A、而 B 的成员从下一格才开始 ——
   * "贴边"会把这种本就相邻的供应区判掉。★ 1 是**出厂值、GM 可调**：它只放宽"哪些区能互相看见"， 不放宽运输的 ETA/运力/限价。
   */
  static final int MARKET_REGION_ADJACENCY_GAP_HEX = 1;

  private final List<MarketRegion> regions;
  private final Map<HexCoord, MarketRegion> regionByHex;
  private final ToIntFunction<HexCoord> moveCostAt;
  private final boolean regional;

  private MarketTopology(
      List<MarketRegion> regions,
      Map<HexCoord, MarketRegion> regionByHex,
      ToIntFunction<HexCoord> moveCostAt,
      boolean regional) {
    this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
    this.regionByHex = Collections.unmodifiableMap(new LinkedHashMap<>(regionByHex));
    this.moveCostAt = Objects.requireNonNull(moveCostAt, "moveCostAt");
    this.regional = regional;
  }

  /**
   * ★★ <b>逐格自成区的退化形态</b>（没有城市节点可用的世界）：每个有市场的 hex 是一个半径 0 的区，且**不构造任何跨区候选** —— 这保留了 M2-L1
   * 的"同格市场"行为，给单模块用例与尚未提供城市信息的世界一条合法路径。
   */
  public static MarketTopology singleHex(Map<HexCoord, Market> markets) {
    Objects.requireNonNull(markets, "markets");
    return of(List.of(), markets, markets.keySet(), hex -> 1, false);
  }

  /** 正常入口：城市节点 + 半径 + 地图格集 + 逐格地形代价。 */
  public static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt) {
    return of(nodes, markets, hexes, moveCostAt, true);
  }

  private static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt,
      boolean regional) {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(moveCostAt, "moveCostAt");

    // ★ 去重：同 nodeId 只认第一条（保序）；并跳过"锚格没有市场"的节点（没有报价币种的区不能交易）。
    Map<String, MarketNode> byId = new LinkedHashMap<>();
    for (MarketNode node : nodes) {
      if (node == null) {
        throw new IllegalArgumentException("MarketTopology.nodes 不得含 null");
      }
      if (!markets.containsKey(node.anchor())) {
        continue; // 锚格没有市场表条目 ⇒ 没有价格/币种可用，这个节点不构成可交易区
      }
      byId.putIfAbsent(node.nodeId(), node);
    }

    // ★ 逐格归属：半径内最近；同距按 nodeId 字典序（构造性可复现）。
    Map<String, LinkedHashSet<HexCoord>> members = new LinkedHashMap<>();
    for (String id : byId.keySet()) {
      members.put(id, new LinkedHashSet<>());
    }
    Map<HexCoord, MarketRegion> byHex = new LinkedHashMap<>();
    List<HexCoord> sortedHexes = new ArrayList<>(hexes);
    sortedHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HexCoord hex : sortedHexes) {
      MarketNode best = null;
      int bestDistance = Integer.MAX_VALUE;
      for (MarketNode node : byId.values()) {
        int distance = hex.distanceTo(node.anchor());
        if (distance > node.radiusHex()) {
          continue;
        }
        if (best == null
            || distance < bestDistance
            || (distance == bestDistance && node.nodeId().compareTo(best.nodeId()) < 0)) {
          best = node;
          bestDistance = distance;
        }
      }
      if (best != null) {
        members.get(best.nodeId()).add(hex);
      }
    }
    // ★ 集散节点本身必须在成员表里（即使 hexes 不含它，或它超出自己的半径）。
    for (MarketNode node : byId.values()) {
      members.get(node.nodeId()).add(node.anchor());
    }
    List<MarketRegion> built = new ArrayList<>(byId.size());
    for (MarketNode node : byId.values()) {
      Set<HexCoord> regionMembers = members.get(node.nodeId());
      MarketRegion region = new MarketRegion(node, regionMembers);
      built.add(region);
      for (HexCoord member : regionMembers) {
        byHex.putIfAbsent(member, region);
      }
    }
    // ★ 没有落在任何半径内的市场格 ⇒ 退化成单格区（它仍然可以**作为买方区**参与跨区候选；卖方得先有邻接）。
    for (Map.Entry<HexCoord, Market> entry : markets.entrySet()) {
      HexCoord hex = entry.getKey();
      if (byHex.containsKey(hex)) {
        continue;
      }
      Market market = entry.getValue();
      MarketNode node =
          new MarketNode(
              "hex:" + IndustryHexKeys.hexKey(hex.q(), hex.r()),
              hex,
              0,
              market.numeraire(),
              io.mosire.simos.economy.api.money.MoneyVocabulary.SILVER_SPECIE.id());
      MarketRegion region = new MarketRegion(node, Set.of(hex));
      built.add(region);
      byHex.put(hex, region);
    }
    // ★ 没有显式城市节点 ⇒ 不构造跨区候选（退化成单格区；与 singleHex 同语义）：否则"相邻单格区"
    //   会被当成跨区路线，凭空造出 ETA/运力/在途 —— 那正是"没有城市信息的世界"不该有的行为。
    return new MarketTopology(
        built, byHex, moveCostAt, regional && !byId.isEmpty() && built.size() > 1);
  }

  /** 全部区（保序：节点声明序；退化单格区接在其后）。 */
  public List<MarketRegion> regions() {
    return regions;
  }

  /** 某个有市场的格所属的区（拓扑构造时保证每个市场格都有归属）。 */
  public MarketRegion regionOf(HexCoord hex) {
    MarketRegion region = regionByHex.get(Objects.requireNonNull(hex, "hex"));
    if (region == null) {
      throw new IllegalArgumentException("MarketTopology.regionOf 只服务有市场的格：没有归属: " + hex);
    }
    return region;
  }

  /** 这张拓扑有没有跨区候选（{@code false} = 每个区各管各的，等价于 M2-L1 的同格市场）。 */
  public boolean regional() {
    return regional;
  }

  /**
   * ★ 两区是否互为"直接邻接供应区"（第一版跨区口径；见 {@link #MARKET_REGION_ADJACENCY_GAP_HEX}）。 退化拓扑（{@link
   * #singleHex}）恒 {@code false} —— 单格区之间不凭空跨区。
   */
  public boolean adjacent(MarketRegion a, MarketRegion b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (!regional || a.equals(b)) {
      return false;
    }
    long reach = (long) a.radiusHex() + b.radiusHex() + MARKET_REGION_ADJACENCY_GAP_HEX;
    return a.anchor().distanceTo(b.anchor()) <= reach;
  }

  /** 逐格地形代价（原始 moveCost；不可通行的哨兵值原样返回，由调用方按 {@link TerrainType#IMPASSABLE_MOVE_COST} 判）。 */
  public int moveCostAt(HexCoord hex) {
    return Math.max(1, moveCostAt.applyAsInt(Objects.requireNonNull(hex, "hex")));
  }

  /** 两格之间的实际投入代价：{@code hexDistance × moveCost(目标格)}（口径 = {@code SettlementGenerator}）。 */
  public long transportCost(HexCoord from, HexCoord to) {
    return (long) from.distanceTo(to) * moveCostAt(to);
  }

  /** 单程天数：1 天/格（与单位移动的平原口径一致）；同格为 0（区内即时不走 TradeRoute）。 */
  public long travelTicks(HexCoord from, HexCoord to) {
    return Math.max(0L, from.distanceTo(to));
  }
}
