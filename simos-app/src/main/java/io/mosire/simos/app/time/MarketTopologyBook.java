package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongBiFunction;

/**
 * ★★ <b>区域拓扑的组合根装配</b>（M2.3）：把"城市节点（social/map 的权威）+ tier 半径 + 地图"现算成 {@link MarketTopology} 交给
 * economy 的日结算。
 *
 * <p>★★ <b>为什么住 {@code simos-app}</b>（铁律 3/4）：城市与 tier 住在 social/map，市场价格住在 economy —— economy
 * **不许**反查 social（模块边界），Core 也看不见领域类型。⇒ "谁同时看得见地图与城市"的地方只有组合根。 economy 侧只收一个<b>只读拓扑对象</b>（{@code
 * MarketTopology}），不持有任何对 {@code GameMap} 的全局引用。
 *
 * <p>★★ <b>B2（2026-10-08 阶段 2-B）：持久区优先</b>（约束设计书 §4.2 / 不变量 I22）—— {@code EconomyData.marketZones}
 * <b>非空</b> ⇒ 成员格由持久状态唯一给定（{@link #byPersistentZones}，{@code MarketTopology.ofZones}）；<b>空表</b> ⇒
 * 逐字走下面 的派生路径（旧世界逐值不变）。★ 两条路径**不同时**生效：只有一个权威说了算，否则"这一格属于谁"会有两份互相矛盾的答案。
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
 *
 * <p>★★ <b>P4 的道路/辐射装配</b>：{@link RoadNetwork} 从同一张 {@code GameMap.pathways()}/{@code edges()}
 * 现算道路瓶颈； 辐射距离 = 拓扑节点 anchor 集的最小 {@link HexCoord#distanceTo}；运输费率取 {@link
 * TransportTariff#probeDefaults()}。三者 都以只读函数（方法引用/lambda）交给 {@link MarketTopology}，地图不泄漏进 economy。
 *
 * <p>★★ <b>P6 的商人调整量装配口</b>：本类新增一个带两个 {@link ToLongBiFunction} 的 {@code from} 重载， 返回给定 lane 的
 * {@code cityDiscountPerMille}/{@code ruralPenaltyPerMille}；旧 {@code from(state)} 传恒 0 函数 ⇒
 * 旧行为逐值不变。★ P6 没有稳定的“商人组织/政策 → 每城每队”来源，故默认不注入任何折扣/惩罚；P9/P7 在组合根把 {@code
 * MerchantPolicy.cityDiscountForLane/ruralPenaltyForLane} 汇总成这两个函数后，从新重载注入。
 *
 * <p>★★ <b>R0 / P1.1：地形索引在本类里只建一次</b>（{@link #terrainCostIndex(GameMap)}），交给 {@link MarketTopology}
 * 的 lambda 只查表 —— 消除每次调用 {@code GameMap.terrainIndex()} 重建 59,223 条 的主项（见 {@code
 * from(SimulationState)} 的注释）。语义逐值不变。
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

  /** 从当前状态现算区域拓扑（商人调整量默认恒 0）。★ 地图切片缺席（单模块夹具）⇒ 退化成"每格一区、不跨区"，与 M2-L1 的既有行为一致。 */
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
   *
   * <p>★★ <b>D-027（同币即同区）的生产路径</b>：{@code economy.markets()} 非空、有城市权威、且<b>所有 {@code
   * Market.numeraire} 相同</b> ⇒ 直接返回 {@link MarketTopology#singleRegion}（全部有市场的 hex 归一个区， 锚格 =
   * 规范序第一个 hex、{@code nodeId = "single-region"}）；币种不一致时才退回既有的"城市节点 + tier 半径"路径
   * （D-027：跨市场区本批暂缓，不新增跨区撮合）。地形索引/道路/费率的装配方式逐字不变。
   *
   * <p>★★ <b>B1（阶段 2-B）："几个市场区"由两处决定</b>（多币种世界的区数判据；逐条 file:line 见 {@code
   * .superpowers/sdd/2026-10-08-stage2-three-powers/b1-impl-ledger.md}）：
   *
   * <ol>
   *   <li>{@link #sameNumeraire(Map)} 为真（所有市场同币）⇒ 走 {@link MarketTopology#singleRegion} ⇒ <b>恰好 1
   *       个区</b>；
   *   <li>币种不一致 ⇒ 走 {@link #byCityRadius}：**每个"锚格有市场"的城市**成一个节点（{@link #addNode}），逐格按"半径内最近节点"
   *       归属（同距按 {@code nodeId} 字典序），都没落在任何半径内的市场格退化成单格区 ⇒ <b>区数 = 节点数 + 兜底单格区数</b>。
   * </ol>
   *
   * ⇒ "每区法定币不同"的世界要落成<b>恰好 3 个区</b>，两个条件缺一不可：① 每座城的锚格**有市场**、且其计价币就是本区法定币； ②
   * 每座城的半径**覆盖本区全部有市场的格**（否则多出兜底单格区）。{@code three-powers} 世界按这两条构造：3 座 {@code City}（半径 8）+ 半径 3 的
   * 37 格六边形 ⇒ 全域被 3 个节点覆盖，既无多余兜底区，也无覆盖不到的格。
   */
  static MarketTopology from(
      SimulationState state,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(cityDiscountPerMilleBetween, "cityDiscountPerMilleBetween");
    Objects.requireNonNull(ruralPenaltyPerMilleBetween, "ruralPenaltyPerMilleBetween");
    EconomyData economy = economyOf(state);
    // ★★ F 批（2026-10-09）：商品运费系数表**从状态现读**（不是构造期常量）—— 本条是本类唯一的装配点，
    //   四条返回路径（缺 map 切片 / 空 markets / 持久区 / 单区 / 城市半径）一次全覆盖。
    //   ★ 空表 ⇒ 每个商品缺键 ⇒ 1000 ⇒ 既有世界逐值不变（I-F1）；表非空 ⇒ 费率逐商品乘系数（§4.1 甲方案）。
    return build(state, cityDiscountPerMilleBetween, ruralPenaltyPerMilleBetween)
        .withCommodityFreight(economy.commodityFreightPerMille());
  }

  /**
   * ★★ <b>{@link #from(SimulationState, ToLongBiFunction, ToLongBiFunction)} 的装配本体</b>（F 批拆出）——
   * 不注入商品运费系数表（拓扑构造期的默认 = 空表 = 缺键 1000）。真装配请走 {@code from(...)}：它在本方法的返回值上 {@code
   * withCommodityFreight(economy.commodityFreightPerMille())}。
   */
  private static MarketTopology build(
      SimulationState state,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    EconomyData economy = economyOf(state);
    Snapshot mapSnapshot = state.module("map").orElse(null);
    if (!(mapSnapshot instanceof MapSnapshot map)) {
      return MarketTopology.singleHex(economy.markets());
    }
    if (economy.markets().isEmpty()) {
      return MarketTopology.singleHex(economy.markets());
    }
    GameMap gameMap = map.map();
    SocialData social = socialOrNull(state);
    // ★★ B2（阶段 2-B；I22）：**持久区优先**。区表非空 ⇒ 成员格由市场区**唯一给定**（单一权威），
    //   不再现算"城市 + tier 半径"；空表 ⇒ 逐字走既有派生路径（旧世界逐值不变，B1 的 three-powers 也照旧）。
    List<MarketZone> persistentZones = MarketZoneBook.zones(economy);
    if (!persistentZones.isEmpty()) {
      return byPersistentZones(
          economy,
          gameMap,
          persistentZones,
          cityDiscountPerMilleBetween,
          ruralPenaltyPerMilleBetween);
    }
    boolean cityAuthority = social != null && !social.cities().isEmpty();
    boolean sameNumeraire = sameNumeraire(economy.markets());
    // ★★ D-027：同币即同区。城市权威缺席（单模块夹具/只有 craft@ 格）时也走此路径？不 ——
    //   退回"城市节点 + 半径"的老装配，保持旧夹具可见的地形/道路装配口径不变。
    if (cityAuthority && sameNumeraire) {
      Map<HexCoord, Integer> terrainCost = terrainCostIndex(gameMap);
      RoadNetwork roadNetwork = RoadNetwork.from(gameMap);
      return MarketTopology.singleRegion(
          economy.markets(),
          economy.markets().keySet(),
          hex -> terrainCostOf(terrainCost, hex),
          roadNetwork::roadBottleneckBetween,
          TransportTariff.probeDefaults());
    }
    return byCityRadius(
        economy, gameMap, social, cityDiscountPerMilleBetween, ruralPenaltyPerMilleBetween);
  }

  /**
   * ★★ <b>B2（2026-10-08 阶段 2-B）：持久区的装配路径</b>（约束设计书 §4.2 / 不变量 I22）—— 成员格来自 {@code
   * EconomyData.marketZones}，本方法只做三件事：
   *
   * <ol>
   *   <li>逐区建 {@link MarketNode}：{@code nodeId = zoneId}、{@code anchor = zone.anchor()}、{@code
   *       radiusHex = zone.radiusHex()}（声明值，只影响 {@code adjacent} 的可达判据）、{@code numeraire =
   *       legalTender}、 {@code receiveWith = 该币种在世界状态里的工具 id}（{@link #receiveInstrumentOf} 的既有口径）；
   *   <li>把 {@code zone.hexes()} 原样当成员格（**不**按半径重算 —— 那正是"两个权威"的来源）；
   *   <li>装配地形/道路/费率：与派生路径逐字同源（地形索引一次构建、道路子图、节点锚集的最小距离）。
   * </ol>
   *
   * <p>★★ <b>为什么读口也跟着变</b>：{@link MarketZoneReadout} 走本方法 ⇒ 区表非空时它报的就是持久区（同一个权威），
   * 不再有"结算用持久区、读数用派生区"的两份答案。
   *
   * <p>★ <b>两处不静默</b>（都落 DEBUG，不改变任何数值行为）：锚格没有市场（说不出本区按什么钱报价）、成员格的市场计价币与本区
   * 法定币漂开（写入侧守卫本该挡住；这里报出来是为了让"漂了但没人知道"不可能发生）。
   */
  private static MarketTopology byPersistentZones(
      EconomyData economy,
      GameMap gameMap,
      List<MarketZone> zones,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    List<MarketRegion> regions = new ArrayList<>(zones.size());
    List<HexCoord> nodeAnchors = new ArrayList<>(zones.size());
    int anchorWithoutMarket = 0;
    int numeraireDriftHexes = 0;
    for (MarketZone zone : zones) {
      if (economy.markets().get(zone.anchor()) == null) {
        anchorWithoutMarket++;
      }
      for (HexCoord hex : zone.hexes()) {
        Market market = economy.markets().get(hex);
        if (market != null && !market.numeraire().equals(zone.legalTender())) {
          numeraireDriftHexes++;
        }
      }
      MarketNode node =
          new MarketNode(
              zone.zoneId().value(),
              zone.anchor(),
              zone.radiusHex(),
              zone.legalTender(),
              receiveInstrumentOf(economy, zone.legalTender()));
      regions.add(new MarketRegion(node, new LinkedHashSet<>(zone.hexes())));
      nodeAnchors.add(zone.anchor());
    }
    EventLog.channel(AppLog.time())
        .debug(
            LogEvent.of(
                "MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES",
                AppLogSource.APP_MARKET_TOPOLOGY,
                "source",
                "persistent",
                "zones",
                regions.size(),
                "marketHexes",
                economy.markets().size(),
                "anchorWithoutMarket",
                anchorWithoutMarket,
                "numeraireDriftHexes",
                numeraireDriftHexes));
    Map<HexCoord, Integer> terrainCost = terrainCostIndex(gameMap);
    RoadNetwork roadNetwork = RoadNetwork.from(gameMap);
    return MarketTopology.ofZones(
        regions,
        economy.markets(),
        economy.markets().keySet(),
        hex -> terrainCostOf(terrainCost, hex),
        roadNetwork::roadBottleneckBetween,
        hex -> nearestAnchorDistance(nodeAnchors, hex),
        TransportTariff.probeDefaults(),
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween);
  }

  /**
   * ★★ <b>旧路径（城市节点 + tier 半径 / craft@ 退化）</b>：币种不一致或城市权威缺席时使用；装配口径与 D-027
   * 之前逐字相同（地形索引一次构建、道路/辐射由组合根注入、两个商人调整量落在费率公式里）。
   */
  private static MarketTopology byCityRadius(
      EconomyData economy,
      GameMap gameMap,
      SocialData social,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween) {
    List<MarketNode> nodes = new ArrayList<>();
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
    // ★★ R0 / P1.1：地形索引**一次构建、之后查表**。`GameMap.terrainIndex()` 每次调用都会重建
    //   59,223 条（实测占市场轮 22.66%），而旧写法在 `moveCostAt` 里**逐格调用**它 ⇒ O(格数²)。
    Map<HexCoord, Integer> terrainCost = terrainCostIndex(gameMap);
    // ★★ P4：道路子图从 map.pathways()/map.edges() 现算；辐射距离 = 拓扑节点 anchor 集的最小 hex 距离。
    //   两者都只在这里（组合根）装配，economy 侧只收方法引用。
    RoadNetwork roadNetwork = RoadNetwork.from(gameMap);
    List<HexCoord> nodeAnchors = new ArrayList<>(nodes.size());
    for (MarketNode node : nodes) {
      nodeAnchors.add(node.anchor());
    }
    return MarketTopology.of(
        nodes,
        economy.markets(),
        economy.markets().keySet(),
        hex -> terrainCostOf(terrainCost, hex),
        roadNetwork::roadBottleneckBetween,
        hex -> nearestAnchorDistance(nodeAnchors, hex),
        TransportTariff.probeDefaults(),
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween);
  }

  /**
   * ★★ <b>D-027 的同币判据</b>：所有有市场的 hex 的 {@code Market.numeraire} 逐值相同 ⇒ 一个市场区。 空 markets ⇒
   * false（由调用方先判空，不在这里派单区）。
   */
  private static boolean sameNumeraire(Map<HexCoord, Market> markets) {
    CurrencyId single = null;
    for (Market market : markets.values()) {
      if (single == null) {
        single = market.numeraire();
      } else if (!single.equals(market.numeraire())) {
        return false;
      }
    }
    return single != null;
  }

  /**
   * 一个 hex 到拓扑节点 anchor 集的<b>最小 hex 距离</b>（P4 的辐射项口径，取 from/to 两端较小者；见 {@code
   * MarketTopology.freightPerMilleBetween}）。没有节点 ⇒ 0（退化路径不会走到这里，仍按 0 兜底）。
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
   * ★★ <b>一个城市节点</b>：锚格必须有市场（否则"这一格按什么钱报价"说不出来）。
   *
   * <p>★★ <b>B1（2026-10-08 阶段 2-B）改了这里的一行</b>：旧实现<b>显式跳过非 silver 锚</b>（"本批只有 silver 一种货币工具， M2.0
   * #2"）⇒ 在多币种世界里，除银区外的每一座城都<b>成不了节点</b>，它的腹地只能退化成"逐格单格区"（{@link MarketTopology#of}
   * 的兜底分支），"几个市场区"就变成"1 个城域区 + N 个单格区"这种既非 1 也非 3 的形态。 现在改成本格市场**自己的**计价币：节点币种 = {@code
   * market.numeraire()}（每区法定币不同的世界因此真的落成 3 个区）。
   *
   * <p>★ <b>为什么对既有世界逐值不变</b>：所有既有世界（{@code v17levant} / {@code small-world} / {@code
   * corridor}）逐格市场都是银 ⇒ {@code sameNumeraire} 为真 ⇒ 走 {@link MarketTopology#singleRegion} 快路
   * （**根本不会调到本方法**）；本方法只在"计价币不一致"时被调，而那正是本批新增的世界形态。
   *
   * <p>★ <b>{@code receiveWith} 的口径</b>：取该币种在**世界状态**（{@code EconomyData.moneyInstruments}）里的工具 id
   * —— 币种词表 A1 起是逐世界的，硬写 {@code silver-specie} 会在铜/金区写出"收银"的谎。★ 若该币种在世界状态里没有工具 （旧档/单模块夹具），退回 {@link
   * MoneyVocabulary#SILVER_SPECIE} 这一既有占位：该字段**全仓零读取者**（见 {@code MarketNode} 的类注与阶段 1 报告
   * §2.2），故它不改变任何结算行为，只影响这一栏读数的自洽性。
   */
  private static void addNode(
      List<MarketNode> nodes, String nodeId, HexCoord at, String tier, EconomyData economy) {
    Market market = economy.markets().get(at);
    if (market == null) {
      return;
    }
    int radius =
        tier == null
            ? DEFAULT_CITY_RADIUS_HEX
            : TIER_RADIUS_HEX.getOrDefault(tier, DEFAULT_CITY_RADIUS_HEX);
    nodes.add(
        new MarketNode(
            nodeId,
            at,
            radius,
            market.numeraire(),
            receiveInstrumentOf(economy, market.numeraire())));
  }

  /**
   * 某币种在世界状态里的**唯一一个**工具 id（保序第一个；{@code EconomyData.moneyInstruments} 是保序表）。
   *
   * <p>★ 没有该币种的工具 ⇒ 退回 {@link MoneyVocabulary#SILVER_SPECIE}（见 {@link #addNode} 的注：该字段零读取者）。
   */
  static InstrumentId receiveInstrumentOf(EconomyData economy, CurrencyId currency) {
    for (MoneyInstrument instrument : economy.moneyInstruments().values()) {
      if (instrument.currency().equals(currency)) {
        return instrument.id();
      }
    }
    return MoneyVocabulary.SILVER_SPECIE.id();
  }

  /** props 里的 tier（空白/非文本 ⇒ null，不猜）。 */
  private static String tierOf(Map<String, Object> props) {
    Object value = props.get("tier");
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    return null;
  }

  /**
   * ★★ <b>R0 / P1.1：一次性地形代价索引</b>（键 = 格，值 = 原始 {@code moveCost}）。
   *
   * <p>构建一次的成本 = 一次 {@link GameMap#terrainIndex()}（O(格数)）；查表是 O(1)。旧实现在 {@link #moveCostAt} 里逐格重建索引
   * ⇒ 市场轮的地形项从 O(格数) 涨到 O(格数²)。本方法保持**逐值同旧** （地形 key 查不到类型 ⇒ 平原 1；缺格 ⇒ 平原 1）。
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

  private static SocialData socialOrNull(SimulationState state) {
    Snapshot snapshot = state.module("social").orElse(null);
    if (snapshot instanceof SocialSnapshot socialSnapshot) {
      return socialSnapshot.data();
    }
    return null;
  }
}
