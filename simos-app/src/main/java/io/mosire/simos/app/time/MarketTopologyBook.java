package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongBiFunction;

/**
 * ★★ <b>区域拓扑的组合根装配</b>（M2.3；★★ W1 重写：价表每区一张之后的装配口径）。
 *
 * <p>★★ <b>为什么住 {@code simos-app}</b>（铁律 3/4）：市场价格住在 economy，"这一格用什么地形/路网/辐射"住在 map —— economy
 * <b>不许</b>反查 map（模块边界），Core 也看不见领域类型。⇒ "谁同时看得见地图与经济状态"的地方只有组合根。 economy 侧只收一个<b>只读拓扑对象</b>（{@code
 * MarketTopology}）。
 *
 * <p>★★ <b>W1 的装配口径（唯一权威，两条互斥的形态）</b>：
 *
 * <pre>
 * 持久形态（{@code EconomyData.marketZones} 非空）:
 *   逐区一个 {@link MarketRegion}：id/锚/半径/计价币 = {@code MarketZone} 的四栏（I22：成员格由区表唯一给定），
 *   价表 = {@code marketTables.get(区 id)}（"每区一张"）。
 *   另外：**派生市场**（键不在区表里的价表条目）= 今天 {@code ofZones} 的"未覆盖市场格 ⇒ 单格兜底区"的对应物，
 *   成员格 = 条目自己的 {@code hexes}、锚 = 规范序第一个、半径 0、币 = 条目的 numeraire。
 *   辐射项 = 各**持久区** anchor 的最小 hex 距离（改动前 ofZones 逐字同源）；地形/路网/费率由本类现装配。
 *   ★★ W1d：**没有价表条目的区不装配**（缺表 = 该区不交易任何商品，设计书 §4.2 N-4）—— 落一条 DEBUG 具名计数，
 *   与 {@code MarketIndex.at}/{@code MarketTables.at} 的"读不出价"同一判据（三处取值必须一致）。
 *
 * 派生形态（{@code marketZones} 为空）:
 *   逐**价表条目**一个 {@link MarketRegion}：id = 条目键、成员格 = 条目的 {@code hexes}、锚 = 规范序第一个、
 *   半径 0、币 = 条目的 numeraire —— 逐值等于改动前的 {@code MarketTopology.singleRegion}
 *   （它也是"锚格 = 规范序第一个市场格、半径 0、辐射项恒 0、无城市/农村调整"）。
 *
 * 没有 map 切片 / 没有任何市场:
 *   逐格退化拓扑（{@code MarketTopology.singleHex}）：每个有市场的格自成一区、不构造任何跨区候选。
 * </pre>
 *
 * <p>★★ <b>W1 删掉的是哪条路径（具名）</b>：改动前"城市 + tier 半径"的派生装配（{@code byCityRadius}：节点 = social/map
 * 的城市、逐格按最近节点归属、未落半径的市场格退化成单格区）随 W1 退役 —— 新形状下"一个市场覆盖哪些格"由 {@code marketZones}（持久）或价表条目的 {@code
 * hexes}（派生）唯一给定，拓扑侧只<b>采信</b>不推算（单一权威）。 ★ 对既有世界逐值不变：{@code small-world} / {@code corridor} 全格同币 ⇒
 * 改动前走的也正是 {@code singleRegion} 快路； {@code three-powers} 走持久区（改动前后同一条 {@code ofZones} 语义）。差别只在"有
 * map、无城市、有市场"的形态 （改动前用 craft 格当节点 + 非零辐射项，现在辐射项为 0）—— 该形态没有任何生产世界使用，具名记进 W1 账本。
 *
 * <p>★★ <b>R0 / P1.1：地形索引在本类里只建一次</b>（{@link #terrainCostIndex(GameMap)}），交给 {@link MarketTopology}
 * 的 lambda 只查表 —— 消除每次调用 {@code GameMap.terrainIndex()} 重建 59,223 条 的主项。语义逐值不变。
 */
final class MarketTopologyBook {

  private MarketTopologyBook() {}

  /** 从当前状态现算区域拓扑（商人调整量默认恒 0）。★ 地图切片缺席（单模块夹具）⇒ 退化成"每格一区、不跨区"。 */
  static MarketTopology from(SimulationState state) {
    return from(
        state,
        MarketTopology.NO_CITY_DISCOUNT_PER_MILLE,
        MarketTopology.NO_RURAL_PENALTY_PER_MILLE);
  }

  /**
   * ★★ <b>P6：带商人调整量的装配入口</b>：两个只读函数按 {@code from→to} 返回城市折扣/农村惩罚（‰）。
   *
   * <p>★ P6 没有稳定来源时传 {@link MarketTopology#NO_CITY_DISCOUNT_PER_MILLE}/{@link
   * MarketTopology#NO_RURAL_PENALTY_PER_MILLE}（= 旧 {@link #from(SimulationState)}）；P9/P7 在组合根按
   * {@code MerchantPolicy} 汇总后注入。
   */
  static MarketTopology from(
      SimulationState state,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(cityDiscountPerMilleBetween, "cityDiscountPerMilleBetween");
    Objects.requireNonNull(ruralPenaltyPerMilleBetween, "ruralPenaltyPerMilleBetween");
    EconomyData economy = economyOf(state);
    // ★★ 商品**基础运费**表**从状态现读**（不是构造期常量）—— 本条是本类唯一的装配点，
    //   四条返回路径（缺 map 切片 / 无市场 / 持久区 / 派生市场）一次全覆盖。
    //   ★ 空表 ⇒ 每个商品落到现行硬编码分档 ⇒ 既有世界逐值不变（I-F1）；表非空 ⇒ 单位运费按该商品的基础费算。
    return build(state, cityDiscountPerMilleBetween, ruralPenaltyPerMilleBetween)
        .withCommodityFreightBase(economy.commodityFreightBaseMilli());
  }

  /**
   * ★★ <b>{@link #from(SimulationState, ToLongBiFunction, ToLongBiFunction)} 的装配本体</b> ——
   * 不注入商品基础运费表（拓扑构造期的默认 = 空表 = 现行硬编码分档）。真装配请走 {@code from(...)}。
   */
  private static MarketTopology build(
      SimulationState state,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    EconomyData economy = economyOf(state);
    Snapshot mapSnapshot = state.module("map").orElse(null);
    if (!(mapSnapshot instanceof MapSnapshot map)) {
      return MarketTopology.singleHex(economy.marketTables(), economy.marketZones());
    }
    if (economy.marketTables().isEmpty()) {
      return MarketTopology.singleHex(economy.marketTables(), economy.marketZones());
    }
    GameMap gameMap = map.map();
    List<MarketZone> persistentZones = MarketZoneBook.zones(economy);
    if (!persistentZones.isEmpty()) {
      return byPersistentZones(
          economy,
          gameMap,
          persistentZones,
          cityDiscountPerMilleBetween,
          ruralPenaltyPerMilleBetween);
    }
    return byDerivedMarkets(
        economy, gameMap, cityDiscountPerMilleBetween, ruralPenaltyPerMilleBetween);
  }

  /**
   * ★★ <b>持久形态的装配</b>（{@code marketZones} 非空；不变量 I22）：逐区一个区域，成员格来自 {@code MarketZone.hexes}， 价表来自
   * {@code marketTables.get(区 id)}；非区键的价表条目 = 派生市场（未覆盖格的兜底）。
   *
   * <p>★ <b>两处不静默</b>（都落 DEBUG，不改变任何数值行为）：某区没有价表条目（构造期守卫本该挡住）、某区的价表一行价格都没有 （"说不出本区按什么价报价"）。
   */
  private static MarketTopology byPersistentZones(
      EconomyData economy,
      GameMap gameMap,
      List<MarketZone> zones,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    List<MarketRegion> regions = new ArrayList<>(zones.size());
    List<HexCoord> nodeAnchors = new ArrayList<>(zones.size());
    int zoneWithoutTable = 0;
    int zoneWithoutPrice = 0;
    List<String> zoneIdsWithoutTable = new ArrayList<>();
    for (MarketZone zone : zones) {
      MarketTable table = economy.marketTables().get(zone.zoneId());
      if (table == null) {
        // ★★ W1d：缺表 = 该区不交易任何商品（合法状态，不是坏状态）⇒ 不装配区域；具名记进下面的 DEBUG。
        zoneWithoutTable++;
        zoneIdsWithoutTable.add(zone.zoneId().value());
        continue;
      }
      if (table.prices().isEmpty()) {
        zoneWithoutPrice++;
      }
      regions.add(
          MarketTopology.regionOf(
              zone.zoneId().value(),
              zone.anchor(),
              zone.radiusHex(),
              zone.legalTender(),
              receiveInstrumentOf(economy, zone.legalTender()),
              zone.hexes()));
      nodeAnchors.add(zone.anchor());
    }
    // ★ 非区键的价表条目 = 派生市场（改动前 ofZones 的"未覆盖市场格 ⇒ 单格兜底区"的对应物；成员格由条目给定）。
    int derivedMarkets = 0;
    for (MarketZoneId marketId : MarketTables.canonicalIds(economy.marketTables())) {
      if (economy.marketZones().containsKey(marketId)) {
        continue;
      }
      MarketTable table = economy.marketTables().get(marketId);
      if (table.hexes().isEmpty()) {
        continue; // 派生形态的形状守卫已判死；这里只保证不抛
      }
      regions.add(
          MarketTopology.regionOf(
              marketId.value(),
              MarketTopology.canonicalFirstHex(table.hexes()),
              0,
              table.numeraire(),
              MoneyVocabulary.SILVER_SPECIE.id(),
              table.hexes()));
      derivedMarkets++;
    }
    EventLog.channel(AppLog.time())
        .debug(
            LogEvent.of(
                "MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES",
                AppLogSource.APP_MARKET_TOPOLOGY,
                "source",
                "persistent",
                "zones",
                zones.size(),
                "regions",
                regions.size(),
                "derivedMarkets",
                derivedMarkets,
                "marketTables",
                economy.marketTables().size(),
                "zoneWithoutTable",
                zoneWithoutTable,
                "zoneIdsWithoutTable",
                List.copyOf(zoneIdsWithoutTable),
                "zoneWithoutPrice",
                zoneWithoutPrice));
    Map<HexCoord, Integer> terrainCost = terrainCostIndex(gameMap);
    RoadNetwork roadNetwork = RoadNetwork.from(gameMap);
    return MarketTopology.ofRegions(
        regions,
        hex -> terrainCostOf(terrainCost, hex),
        roadNetwork::roadBottleneckBetween,
        hex -> nearestAnchorDistance(nodeAnchors, hex),
        TransportTariff.probeDefaults(),
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween,
        !zones.isEmpty() && regions.size() > 1);
  }

  /**
   * ★★ <b>派生形态的装配</b>（{@code marketZones} 为空）：逐价表条目一个区域 —— 逐值等于改动前的 {@code
   * MarketTopology.singleRegion}（锚 = 规范序第一个成员格、半径 0、辐射项恒 0、无城市/农村调整、 {@code regional = true}）。
   *
   * <p>★ 为什么辐射项恒 0：改动前这条路径（"全部市场格同币 ⇒ 一个区"）在 {@code singleRegion} 里传的就是恒 0 的 {@code
   * NO_NEAREST_NODE_DISTANCE} ⇒ 沿用同一取值才逐值不变（费率公式的半径项是"城市辐射"语义，单区语义下没有"最近城市" 可言）。
   */
  private static MarketTopology byDerivedMarkets(
      EconomyData economy,
      GameMap gameMap,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    List<MarketRegion> regions = new ArrayList<>(economy.marketTables().size());
    for (MarketZoneId marketId : MarketTables.canonicalIds(economy.marketTables())) {
      MarketTable table = economy.marketTables().get(marketId);
      if (table.hexes().isEmpty()) {
        continue; // 派生形态必须有成员格（EconomyData 的构造期守卫已判死）；这里只保证不抛
      }
      regions.add(
          MarketTopology.regionOf(
              marketId.value(),
              MarketTopology.canonicalFirstHex(table.hexes()),
              0,
              table.numeraire(),
              MoneyVocabulary.SILVER_SPECIE.id(),
              table.hexes()));
    }
    if (regions.isEmpty()) {
      return MarketTopology.singleHex(economy.marketTables(), economy.marketZones());
    }
    EventLog.channel(AppLog.time())
        .debug(
            LogEvent.of(
                "MARKET_TOPOLOGY_FROM_DERIVED_MARKETS",
                AppLogSource.APP_MARKET_TOPOLOGY,
                "source",
                "derived",
                "markets",
                regions.size(),
                "marketHexes",
                MarketTables.allMarketHexes(economy.marketTables(), economy.marketZones()).size(),
                "numeraire",
                regions.get(0).numeraire().value()));
    Map<HexCoord, Integer> terrainCost = terrainCostIndex(gameMap);
    RoadNetwork roadNetwork = RoadNetwork.from(gameMap);
    return MarketTopology.ofRegions(
        regions,
        hex -> terrainCostOf(terrainCost, hex),
        roadNetwork::roadBottleneckBetween,
        MarketTopology.NO_NEAREST_NODE_DISTANCE,
        TransportTariff.probeDefaults(),
        MarketTopology.NO_CITY_DISCOUNT_PER_MILLE,
        MarketTopology.NO_RURAL_PENALTY_PER_MILLE,
        true);
  }

  /**
   * 一个 hex 到拓扑节点 anchor 集的<b>最小 hex 距离</b>（P4 的辐射项口径，取 from/to 两端较小者；见 {@code
   * MarketTopology.freightPerMilleBetween}）。没有节点 ⇒ 0。
   */
  private static int nearestAnchorDistance(List<HexCoord> anchors, HexCoord hex) {
    if (hex == null || anchors.isEmpty()) {
      return 0;
    }
    int best = Integer.MAX_VALUE;
    for (HexCoord anchor : anchors) {
      int distance = hex.distanceTo(anchor);
      if (distance < best) {
        best = distance;
      }
    }
    return best == Integer.MAX_VALUE ? 0 : best;
  }

  /**
   * 某币种在世界状态里的**唯一一个**工具 id（保序第一个；{@code EconomyData.moneyInstruments} 是保序表）。
   *
   * <p>★ 没有该币种的工具 ⇒ 退回 {@link MoneyVocabulary#SILVER_SPECIE} 这一既有占位：该字段**全仓零读取者** （见 {@code
   * MarketNode} 的类注），故它不改变任何结算行为，只影响这一栏读数的自洽性。
   */
  static InstrumentId receiveInstrumentOf(EconomyData economy, CurrencyId currency) {
    for (MoneyInstrument instrument : economy.moneyInstruments().values()) {
      if (instrument.currency().equals(currency)) {
        return instrument.id();
      }
    }
    return MoneyVocabulary.SILVER_SPECIE.id();
  }

  /**
   * ★★ <b>R0 / P1.1：一次性地形代价索引</b>（键 = 格，值 = 原始 {@code moveCost}）。
   *
   * <p>构建一次的成本 = 一次 {@link GameMap#terrainIndex()}（O(格数)）；查表是 O(1)。旧实现在 {@code moveCostAt} 里逐格重建索引
   * ⇒ 市场轮的地形项从 O(格数) 涨到 O(格数²)。本方法保持**逐值同旧**（地形 key 查不到类型 ⇒ 平原 1；缺格 ⇒ 平原 1）。
   */
  private static Map<HexCoord, Integer> terrainCostIndex(GameMap map) {
    Map<String, TerrainType> types = map.terrainTypes();
    Map<HexCoord, Integer> index = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, String> entry : map.terrainIndex().entrySet()) {
      TerrainType type = types.get(entry.getValue());
      index.put(entry.getKey(), type == null ? 1 : type.moveCost());
    }
    return index;
  }

  /** 查表：不在索引里的格（单模块夹具）按平原 1，不因缺图把路判死（与旧 {@code moveCostAt} 逐值一致）。 */
  private static int terrainCostOf(Map<HexCoord, Integer> terrainCost, HexCoord hex) {
    Integer cost = terrainCost.get(hex);
    return cost == null ? 1 : cost;
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
}
