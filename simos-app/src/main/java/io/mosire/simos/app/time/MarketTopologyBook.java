package io.mosire.simos.app.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>区域拓扑的组合根装配</b>（M2.3）：把"城市节点（social/map 的权威）+ tier 半径 + 地图"现算成 {@link MarketTopology} 交给
 * economy 的日结算。
 *
 * <p>★★ <b>为什么住 {@code simos-app}</b>（铁律 3/4）：城市与 tier 住在 social/map，市场价格住在 economy —— economy
 * **不许**反查 social（模块边界），Core 也看不见领域类型。⇒ "谁同时看得见地图与城市"的地方只有组合根。 economy 侧只收一个<b>只读拓扑对象</b>（{@code
 * MarketTopology}），不持有任何对 {@code GameMap} 的全局引用。
 *
 * <p>★★ <b>节点来源的优先级</b>：
 *
 * <ol>
 *   <li>{@code social.cities}（真档的权威：worldgen 的 {@code PlannedCity} 把 tier 写进 {@code props.tier}）；
 *   <li>{@code map.cities}（地图侧城市；{@code props.tier} 同样认）；
 *   <li>经济侧的 {@code craft@} 产业格（退回"手工业格 = 城市格"，即 M0.6 的 201 格口径）。
 * </ol>
 *
 * <p>★★ <b>半径 = tierRadiiHex 的上界</b>（MarketTown 2 · Town 4 · City 8 · MajorCity 16）。★ 这里写成具名常量而不是 读
 * worldgen 配置：{@code SettlementPlan}/{@code SettlementParams} 是生成期对象，**不落进状态树** （{@code
 * SocialCity.props} 只留 tier）⇒ 结算期读不到配置。数值与 {@code config/worldgen/v17levant-nations.json} 的 {@code
 * tierRadiiHex} 上界一致；V7 参数目录落地后迁入。
 *
 * <p>★ <b>成员格只取"有市场的格"</b>：只有那里有可交易的账户与价格，把 5.9 万格地图全塞进区域归属是做无用功
 * （结算只遍历市场格）。地图的用途是逐格地形代价（运输成本）与格集边界。
 *
 * <p>★ <b>M2.7 起 {@link MarketReadoutAssembly}（同包）复用本类</b>：逐格市场读数与结算读的是同一份拓扑
 * （不许在视图层另算一套区域）；它仍是纯派生件、不是状态。
 */
final class MarketTopologyBook {

  /** tierRadiiHex 的**上界**（与 worldgen 配置一致；见类注）。 */
  private static final Map<String, Integer> TIER_RADIUS_HEX =
      Map.of(
          "MarketTown", 2,
          "Town", 4,
          "City", 8,
          "MajorCity", 16);

  /** 没有 tier 信息时的城市半径（= City 档上界；既不把城当单格，也不假装它是首都）。 */
  private static final int DEFAULT_CITY_RADIUS_HEX = 8;

  private MarketTopologyBook() {}

  /** 从当前状态现算区域拓扑。★ 地图切片缺席（单模块夹具）⇒ 退化成"每格一区、不跨区"，与 M2-L1 的既有行为一致。 */
  static MarketTopology from(SimulationState state) {
    Objects.requireNonNull(state, "state");
    EconomyData economy = economyOf(state);
    Snapshot mapSnapshot = state.module("map").orElse(null);
    if (!(mapSnapshot instanceof MapSnapshot map)) {
      return MarketTopology.singleHex(economy.markets());
    }
    GameMap gameMap = map.map();
    List<MarketNode> nodes = new ArrayList<>();
    SocialData social = socialOrNull(state);
    if (social != null && !social.cities().isEmpty()) {
      List<SocialCity> cities = new ArrayList<>(social.cities().values());
      cities.sort(Comparator.comparing(city -> city.id().value()));
      for (SocialCity city : cities) {
        addNode(nodes, city.id().value(), city.at(), tierOf(city.props()), economy);
      }
    } else {
      List<City> cities = new ArrayList<>(gameMap.cities().values());
      cities.sort(Comparator.comparing(city -> city.id().value()));
      for (City city : cities) {
        addNode(nodes, city.id().value(), city.at(), tierOf(city.props()), economy);
      }
    }
    if (nodes.isEmpty()) {
      // 退回 M0.6 的"手工业格 = 城市格"口径：节点 = 有 craft@ 产业的格。
      Set<HexCoord> craftHexes = new LinkedHashSet<>();
      for (var entry : economy.industries().entrySet()) {
        if (entry.getKey().value().startsWith("craft@")) {
          IndustryHexKeys.hexKeyOf(entry.getKey()).map(HexCoord::parse).ifPresent(craftHexes::add);
        }
      }
      List<HexCoord> sorted = new ArrayList<>(craftHexes);
      sorted.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      for (HexCoord hex : sorted) {
        addNode(nodes, "hex:" + IndustryHexKeys.hexKey(hex.q(), hex.r()), hex, null, economy);
      }
    }
    if (nodes.isEmpty()) {
      return MarketTopology.singleHex(economy.markets());
    }
    return MarketTopology.of(
        nodes, economy.markets(), economy.markets().keySet(), hex -> moveCostAt(gameMap, hex));
  }

  /** 一个节点：锚格必须有市场（否则没有报价币种可用）；非 silver 市场本批跳过（单一货币工具）。 */
  private static void addNode(
      List<MarketNode> nodes, String nodeId, HexCoord at, String tier, EconomyData economy) {
    Market market = economy.markets().get(at);
    if (market == null) {
      return;
    }
    if (!MoneyVocabulary.SILVER_CURRENCY.equals(market.numeraire())) {
      return; // 本批只有 silver 一种工具（M2.0 #2）；其他币种的区域等货币工具落地后再说
    }
    int radius =
        tier == null
            ? DEFAULT_CITY_RADIUS_HEX
            : TIER_RADIUS_HEX.getOrDefault(tier, DEFAULT_CITY_RADIUS_HEX);
    nodes.add(
        new MarketNode(nodeId, at, radius, market.numeraire(), MoneyVocabulary.SILVER_SPECIE.id()));
  }

  /** props 里的 tier（空白/非文本 ⇒ null，不猜）。 */
  private static String tierOf(Map<String, Object> props) {
    Object value = props.get("tier");
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    return null;
  }

  /** 逐格地形代价（原始 moveCost；上层按 {@code TerrainType.IMPASSABLE_MOVE_COST} 判不可通行）。 */
  private static int moveCostAt(GameMap map, HexCoord hex) {
    String key = map.terrainIndex().get(hex);
    if (key == null) {
      return 1; // 不在图上的格（单模块夹具）：按平原，不因缺图把路判死
    }
    TerrainType type = map.terrainTypes().get(key);
    return type == null ? 1 : type.moveCost();
  }

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("状态里没有 economy 切片（装配故障）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static SocialData socialOrNull(SimulationState state) {
    Snapshot snapshot = state.module("social").orElse(null);
    if (snapshot instanceof SocialSnapshot socialSnapshot) {
      return socialSnapshot.data();
    }
    return null;
  }
}
