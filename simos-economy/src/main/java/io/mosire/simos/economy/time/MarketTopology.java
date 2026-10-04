package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.TransportTariff;
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
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;

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
 * <p>★★ <b>运输费率入口</b>（P4；P6 追加商人调整量；铁律 3/4 同款）：道路瓶颈查询与最近城市距离由组合根以 {@link ToIntBiFunction} / {@link
 * ToIntFunction} 只读传入；P6 的<b>城市折扣</b>与<b>农村累积惩罚</b>由另外两个 {@link ToLongBiFunction} 只读传入（默认 {@link
 * #NO_CITY_DISCOUNT_PER_MILLE}/{@link #NO_RURAL_PENALTY_PER_MILLE} 恒 0，旧行为逐值不变）。本类把它们与 hex 距离喂进
 * {@link TransportTariff}，用 {@link #freightPerMilleBetween} 回答 {@code from→to} 的费率（‰）。economy 侧不反查
 * {@code GameMap}，也不认识 {@code MerchantPolicy} 的存储形态；P9/P7 在组合根把政策翻成只读函数后注入。
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

  /** 旧入口的"无路"查询：任何 lane 的道路瓶颈都是 0（没有折扣）。 */
  private static final ToIntBiFunction<HexCoord, HexCoord> NO_ROADS = (from, to) -> 0;

  /** 旧入口的"没有城市距离"查询：任何格到最近节点的距离都是 0（没有辐射成本）。 */
  private static final ToIntFunction<HexCoord> NO_NEAREST_NODE_DISTANCE = hex -> 0;

  /**
   * ★ <b>默认城市商人折扣函数：恒 0</b>（旧行为逐值不变）。
   *
   * <p>P6 不落地“商人组织/政策 → 每城每队”的稳定来源；P9/P7 在组合根按 {@code MerchantPolicy} 的 {@code
   * cityDiscountForLane(from,to)} 求和后，用 {@link MarketTopology#of} 的九参入口替换本函数。★ 默认 0
   * 不是“给每个城市商队加折扣”，而是“没有显式政策时不做任何折价”。
   */
  public static final ToLongBiFunction<HexCoord, HexCoord> NO_CITY_DISCOUNT_PER_MILLE =
      (from, to) -> 0L;

  /**
   * ★ <b>默认农村商人惩罚函数：恒 0</b>（旧行为逐值不变）。
   *
   * <p>与 {@link #NO_CITY_DISCOUNT_PER_MILLE} 同款：没有显式 {@code MerchantPolicy} 来源时不加任何惩罚。
   */
  public static final ToLongBiFunction<HexCoord, HexCoord> NO_RURAL_PENALTY_PER_MILLE =
      (from, to) -> 0L;

  private final List<MarketRegion> regions;
  private final Map<HexCoord, MarketRegion> regionByHex;
  private final ToIntFunction<HexCoord> moveCostAt;

  /** 道路瓶颈查询（组合根从地图派生；旧入口恒 0）。 */
  private final ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween;

  /** 到最近城市节点 anchor 的 hex 距离（组合根装配；没有节点 ⇒ 0）。 */
  private final ToIntFunction<HexCoord> nearestNodeDistance;

  /** 运输费率函数；默认 {@link TransportTariff#probeDefaults()}。 */
  private final TransportTariff tariff;

  /** P6 城市商人折扣（‰），按 lane 查询；默认 {@link #NO_CITY_DISCOUNT_PER_MILLE}。 */
  private final ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween;

  /** P6 农村商人累积惩罚（‰），按 lane 查询；默认 {@link #NO_RURAL_PENALTY_PER_MILLE}。 */
  private final ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween;

  private final boolean regional;

  private MarketTopology(
      List<MarketRegion> regions,
      Map<HexCoord, MarketRegion> regionByHex,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween,
      boolean regional) {
    this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
    this.regionByHex = Collections.unmodifiableMap(new LinkedHashMap<>(regionByHex));
    this.moveCostAt = Objects.requireNonNull(moveCostAt, "moveCostAt");
    this.roadBottleneckBetween =
        Objects.requireNonNull(roadBottleneckBetween, "roadBottleneckBetween");
    this.nearestNodeDistance = Objects.requireNonNull(nearestNodeDistance, "nearestNodeDistance");
    this.tariff = Objects.requireNonNull(tariff, "tariff");
    this.cityDiscountPerMilleBetween =
        Objects.requireNonNull(cityDiscountPerMilleBetween, "cityDiscountPerMilleBetween");
    this.ruralPenaltyPerMilleBetween =
        Objects.requireNonNull(ruralPenaltyPerMilleBetween, "ruralPenaltyPerMilleBetween");
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

  /**
   * ★★ <b>单市场区入口</b>（D-027：同币即同区）：{@code marketHexes} 里所有格归入<b>恰好一个</b>
   * {@link MarketRegion}，没有跨区候选、没有第二个区。
   *
   * <p>★★ <b>锚格 = 规范序第一个格</b>（q, r 升序；与 {@code marketHexes} 的迭代序无关），
   * {@code nodeId = "single-region"}、{@code radiusHex = 0}（单区语义不用半径；{@link #adjacent} 对同区/自身恒
   * false）；{@code numeraire} 取该锚格 {@link Market#numeraire()}。
   *
   * <p>★★ <b>fail-closed</b>：{@code markets} 为空、{@code marketHexes} 为空、成员格没有市场表条目、锚格没有市场表条目、
   * 两个入参不同源（{@code markets} 的某个键不在 {@code marketHexes} 里）⇒ {@link IllegalArgumentException}
   * （"没有报价币种的区不能交易"这条既有守卫在这里也是具名拒绝，不静默退化）。
   *
   * <p>★ <b>地形/道路/费率入口与旧入口逐字同源</b>：{@code moveCostAt}/{@code roadBottleneckBetween}/
   * {@code tariff} 原样交给 {@link #transportCost}/{@link #travelTicks}/{@link #roadBottleneckBetween}/{@link
   * #freightPerMilleBetween}；城市折扣/农村惩罚沿用 {@link #NO_CITY_DISCOUNT_PER_MILLE}/
   * {@link #NO_RURAL_PENALTY_PER_MILLE}（没有政策时恒 0）。逐格贸易成本本身由 {@link HexTradeCost} 现算，本类不内建。
   *
   * @param markets 逐格市场表（键 = 有市场的格；每个键都必须在 {@code marketHexes} 里）
   * @param marketHexes 单区成员格（本批 = 有市场的全部 hex）；不得为空，且必须覆盖 {@code markets} 的全部键
   * @param moveCostAt 逐格地形代价（组合根装配；{@code >= 1} 的钳位见 {@link #moveCostAt}）
   * @param roadBottleneckBetween 两格间道路瓶颈等级（组合根装配）
   * @param tariff 运输费率（{@link TransportTariff}）
   */
  public static MarketTopology singleRegion(
      Map<HexCoord, Market> markets,
      Set<HexCoord> marketHexes,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      TransportTariff tariff) {
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(marketHexes, "marketHexes");
    Objects.requireNonNull(moveCostAt, "moveCostAt");
    Objects.requireNonNull(roadBottleneckBetween, "roadBottleneckBetween");
    Objects.requireNonNull(tariff, "tariff");
    if (markets.isEmpty()) {
      throw new IllegalArgumentException("MarketTopology.singleRegion 需要至少一个有市场的格（markets 为空）");
    }
    Set<HexCoord> members = new LinkedHashSet<>();
    for (HexCoord hex : marketHexes) {
      if (hex == null) {
        throw new IllegalArgumentException("MarketTopology.singleRegion 的 marketHexes 不得含 null");
      }
      if (!markets.containsKey(hex)) {
        throw new IllegalArgumentException(
            "MarketTopology.singleRegion 的成员格必须有市场表条目（缺价/缺币种的格不能进单区）: " + hex);
      }
      members.add(hex);
    }
    if (members.isEmpty()) {
      throw new IllegalArgumentException("MarketTopology.singleRegion 的 marketHexes 不得为空");
    }
    for (HexCoord hex : markets.keySet()) {
      if (hex == null) {
        throw new IllegalArgumentException("MarketTopology.singleRegion 的 markets 键不得含 null");
      }
      if (!marketHexes.contains(hex)) {
        throw new IllegalArgumentException(
            "MarketTopology.singleRegion 的 markets 里有成员表之外的格（两个入参不同源）: " + hex);
      }
      members.add(hex); // ★ "有市场的 hex 全部归入一个区"：markets 的每个键都在同一个区里，不造第二个区
    }
    HexCoord anchor = canonicalFirstHex(members);
    Market anchorMarket = markets.get(anchor);
    if (anchorMarket == null) {
      throw new IllegalArgumentException(
          "MarketTopology.singleRegion 的锚格没有市场表条目（没有报价币种可用）: " + anchor);
    }
    MarketNode node =
        new MarketNode(
            "single-region",
            anchor,
            0,
            anchorMarket.numeraire(),
            io.mosire.simos.economy.api.money.MoneyVocabulary.SILVER_SPECIE.id());
    MarketRegion region = new MarketRegion(node, members);
    Map<HexCoord, MarketRegion> byHex = new LinkedHashMap<>();
    for (HexCoord member : members) {
      byHex.put(member, region);
    }
    return new MarketTopology(
        List.of(region),
        byHex,
        moveCostAt,
        roadBottleneckBetween,
        NO_NEAREST_NODE_DISTANCE,
        tariff,
        NO_CITY_DISCOUNT_PER_MILLE,
        NO_RURAL_PENALTY_PER_MILLE,
        true);
  }

  /** 规范序（q, r 升序）第一个格；调用方保证非空。 */
  private static HexCoord canonicalFirstHex(Set<HexCoord> hexes) {
    HexCoord first = null;
    for (HexCoord hex : hexes) {
      if (first == null
          || hex.q() < first.q()
          || (hex.q() == first.q() && hex.r() < first.r())) {
        first = hex;
      }
    }
    return first;
  }

  /**
   * 旧入口：城市节点 + 半径 + 地图格集 + 逐格地形代价。★ 道路/辐射/费率取"无路、最近城市距离 0、默认费率" —— 没有新数据的调用方行为保持可达；数值口径见 {@link
   * #freightPerMilleBetween}。
   */
  public static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt) {
    return of(
        nodes,
        markets,
        hexes,
        moveCostAt,
        NO_ROADS,
        NO_NEAREST_NODE_DISTANCE,
        TransportTariff.probeDefaults());
  }

  /**
   * 正常入口（P4 起）：城市节点 + 半径 + 地图格集 + 逐格地形代价 + <b>道路瓶颈查询</b> + <b>最近节点距离查询</b> +
   * <b>运输费率</b>。前四个参数语义与旧入口逐字相同；后三个由组合根（同时看得见地图与经济）装配。★ 商人调整量默认恒 0（等价 {@link #of(List, Map, Set,
   * ToIntFunction, ToIntBiFunction, ToIntFunction, TransportTariff, ToLongBiFunction,
   * ToLongBiFunction)} 传两个 0 函数）。
   */
  public static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff) {
    return of(
        nodes,
        markets,
        hexes,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        NO_CITY_DISCOUNT_PER_MILLE,
        NO_RURAL_PENALTY_PER_MILLE);
  }

  /**
   * ★★ <b>P6 正常入口</b>：在 P4 入口上追加两个只读的商人调整量函数 —— {@code cityDiscountPerMilleBetween} 与 {@code
   * ruralPenaltyPerMilleBetween}，按 {@code from→to} 返回该 lane 的折扣/惩罚（‰）。默认入口传的是恒 0 函数； P9/P7 在组合根按
   * {@code MerchantPolicy} 的 {@code cityDiscountForLane/ruralPenaltyForLane} 汇总后注入。
   */
  public static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    return of(
        nodes,
        markets,
        hexes,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        true,
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween);
  }

  /** 退化入口：旧语义 + 显式 regional 位（{@link #singleHex} 专用）；商人调整量恒 0。 */
  private static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt,
      boolean regional) {
    return of(
        nodes,
        markets,
        hexes,
        moveCostAt,
        NO_ROADS,
        NO_NEAREST_NODE_DISTANCE,
        TransportTariff.probeDefaults(),
        regional,
        NO_CITY_DISCOUNT_PER_MILLE,
        NO_RURAL_PENALTY_PER_MILLE);
  }

  private static MarketTopology of(
      List<MarketNode> nodes,
      Map<HexCoord, Market> markets,
      Set<HexCoord> hexes,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff,
      boolean regional,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(moveCostAt, "moveCostAt");
    Objects.requireNonNull(roadBottleneckBetween, "roadBottleneckBetween");
    Objects.requireNonNull(nearestNodeDistance, "nearestNodeDistance");
    Objects.requireNonNull(tariff, "tariff");
    Objects.requireNonNull(cityDiscountPerMilleBetween, "cityDiscountPerMilleBetween");
    Objects.requireNonNull(ruralPenaltyPerMilleBetween, "ruralPenaltyPerMilleBetween");

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
        built,
        byHex,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween,
        regional && !byId.isEmpty() && built.size() > 1);
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

  /**
   * 某个格是否属于本拓扑的成员表（{@link #regionOf} 的不抛版本；{@code null} ⇒ {@code false}）。
   *
   * <p>★ 给 {@link HexTradeCost} 的 fail-closed 守卫用：它要判"这两格有没有归属"而不想靠异常做控制流。
   */
  public boolean contains(HexCoord hex) {
    return hex != null && regionByHex.containsKey(hex);
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

  /**
   * {@code from→to} 道路路径的<b>最大瓶颈等级</b>（P4；正式版探针 {@code roadPathMinLevel}）：没有可达道路/缺图/同格 ⇒ 0。
   *
   * <p>★ 与探针的具名差异：探针不可达回 {@code -1}；正式版回 0，因为费率只把等级用作折扣，0 与"无路"折扣同效。查询函数由组合根装配 （{@code
   * RoadNetwork::roadBottleneckBetween}）；旧入口装配的是恒 0 查询。
   */
  public int roadBottleneckBetween(HexCoord from, HexCoord to) {
    if (from == null || to == null || from.equals(to)) {
      return 0;
    }
    return Math.max(0, roadBottleneckBetween.applyAsInt(from, to));
  }

  /**
   * 到最近城市节点 anchor 的 hex 距离（P4 的辐射项输入）：{@code hex} 缺图/查询函数缺席 ⇒ 0。
   *
   * <p>★ 口径与探针一致：取 {@code from}/{@code to} 两端到最近城市的<b>较小者</b>（不是贸易中点）；距离用 {@link
   * HexCoord#distanceTo}（正式六边形距离）。★ 探针 {@code ProbeEconomy.hexDistance} 用的是 {@code |Δq|+|Δr|}（轴向
   * 曼哈顿），与正式六边形距离在部分格对上不同；这处差异见 P4 报告，P9 对拍时逐 scenario 裁定。
   */
  public int nearestNodeDistance(HexCoord hex) {
    if (hex == null) {
      return 0;
    }
    return Math.max(0, nearestNodeDistance.applyAsInt(hex));
  }

  /** 本拓扑装配的运输费率函数（只读；默认 {@link TransportTariff#probeDefaults()}）。 */
  public TransportTariff tariff() {
    return tariff;
  }

  /**
   * ★ <b>本 lane 的城市商人折扣（‰，只读）</b>：调用构造期注入的函数；默认入口恒 0。返回值语义由组合根注入的 {@code MerchantPolicy}
   * 汇总决定（探针口径：本轮 {@code lastTradeProfit} 等状态不在本类里）。
   */
  public long cityDiscountPerMilleBetween(HexCoord from, HexCoord to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return cityDiscountPerMilleBetween.applyAsLong(from, to);
  }

  /** ★ <b>本 lane 的农村商人累积惩罚（‰，只读）</b>：调用构造期注入的函数；默认入口恒 0。 */
  public long ruralPenaltyPerMilleBetween(HexCoord from, HexCoord to) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    return ruralPenaltyPerMilleBetween.applyAsLong(from, to);
  }

  /**
   * ★★ <b>{@code from→to} 的运输费率（‰）—— 唯一费率入口</b>（P4 公式 + P6 两个商人调整量）：
   *
   * <pre>
   * freightPerMilleBetween = tariff.perMille(hexDistance(from, to),
   *                                          min(nearestNodeDistance(from), nearestNodeDistance(to)),
   *                                          roadBottleneckBetween(from, to),
   *                                          cityDiscountPerMilleBetween(from, to),   // 默认 0
   *                                          ruralPenaltyPerMilleBetween(from, to))   // 默认 0
   * </pre>
   *
   * <p>同格/缺图/越界（hex 不属于本拓扑的成员表）⇒ 0，且不抛异常。★ 两个商人调整量默认恒 0（旧行为逐值不变）； P9/P7 由组合根按 {@code
   * MerchantPolicy} 注入后本方法逐值照探针公式折价/加价。★ 费率是"货款价值的千分比"，把货款乘上它再除以 1000 得到运费由调用方负责。
   */
  public long freightPerMilleBetween(HexCoord from, HexCoord to) {
    if (from == null || to == null || from.equals(to)) {
      return 0L;
    }
    if (!regionByHex.containsKey(from) || !regionByHex.containsKey(to)) {
      return 0L;
    }
    return freightPerMilleBetween(
        from,
        to,
        cityDiscountPerMilleBetween.applyAsLong(from, to),
        ruralPenaltyPerMilleBetween.applyAsLong(from, to));
  }

  /**
   * ★★ <b>显式传两个商人调整量的费率入口</b>：与 {@link #freightPerMilleBetween(HexCoord, HexCoord)} 同一条 {@link
   * TransportTariff#perMille} 公式/守卫，只是不再从构造期函数取两个值。P6 的 {@code MarketSettlement}
   * 用它在承运路线构建处把两个读数显式交给费率公式；旧调用方继续用两参入口。
   *
   * @param cityDiscountPerMille 城市商人折扣（‰；组合根注入时应保证非负；本方法逐值照探针公式，不额外判负）
   * @param ruralPenaltyPerMille 农村商人累积惩罚（‰；组合根注入时应保证非负；本方法逐值照探针公式，不额外判负）
   */
  public long freightPerMilleBetween(
      HexCoord from, HexCoord to, long cityDiscountPerMille, long ruralPenaltyPerMille) {
    if (from == null || to == null || from.equals(to)) {
      return 0L;
    }
    if (!regionByHex.containsKey(from) || !regionByHex.containsKey(to)) {
      return 0L;
    }
    long distance = from.distanceTo(to);
    long radialDistance = Math.min(nearestNodeDistance(from), nearestNodeDistance(to));
    long roadLevel = roadBottleneckBetween(from, to);
    return tariff.perMille(
        distance, radialDistance, roadLevel, cityDiscountPerMille, ruralPenaltyPerMille);
  }
}
