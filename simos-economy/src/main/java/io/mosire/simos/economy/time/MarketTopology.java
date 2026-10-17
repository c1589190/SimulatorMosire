package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketNode;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.CommodityFreightBase;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.ArrayList;
import java.util.Collections;
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

  /**
   * ★ <b>"没有城市距离"查询</b>：任何格到最近节点的距离都是 0（没有辐射成本）。
   *
   * <p>★ W1 起它也是<b>派生形态</b>（{@code marketZones} 为空）的正式取值：与改动前的 {@code singleRegion} 逐字同源
   * （单区语义下没有"最近城市"可言）⇒ 组合根装配时显式传它，费率公式的半径项逐值不变。
   */
  public static final ToIntFunction<HexCoord> NO_NEAREST_NODE_DISTANCE = hex -> 0;

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

  /**
   * ★★ <b>商品<b>基础运费</b>表的快照</b>（毫计价货币 / 商品单位 / 程；键 = 商品）—— 商品维的**唯一**落点。
   *
   * <p>★★ <b>它为什么在这里、而不是烘进 {@link TransportTariff}</b>：{@code TransportTariff} 是构造期常量（{@code
   * probeDefaults()} 在组合根装配），而基础费是**状态**（{@code EconomyData.commodityFreightBaseMilli}，GM 可改）⇒
   * 只能由组合根每次从状态现读后交进来 （{@link io.mosire.simos.app.time.MarketTopologyBook} 的 {@code from(state)}
   * 是唯一装配点， 每次结算/读数都按当刻状态重建拓扑）。★ <b>空表 = 旧世界形态</b>：每个商品落到 {@link
   * CommodityFreightBase#legacyMilli(CommodityId)}（现行硬编码分档）⇒ 逐值不变（I-F1）。
   *
   * <p>★ <b>费率维没有第二个商品维</b>：{@link #freightPerMilleBetween} 只算距离 / 辐射 / 道路 / 城乡；F 批（{@code
   * f3366545}）曾加过的"商品系数"入口已撤销。
   */
  private final Map<CommodityId, Long> commodityFreightBaseMilli;

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
    this(
        regions,
        regionByHex,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween,
        regional,
        // ★ 旧入口（没有传表的工厂/夹具）默认空表 = 每个商品落到现行硬编码分档 ⇒ 逐值等于本批改动前。
        Map.of());
  }

  private MarketTopology(
      List<MarketRegion> regions,
      Map<HexCoord, MarketRegion> regionByHex,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween,
      boolean regional,
      Map<CommodityId, Long> commodityFreightBaseMilli) {
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
    // ★★ 商品基础运费表的快照（逐键复制 + 冻结；"缺键 ⇒ 具名缺省"的解析在 commodityFreightBaseMilliOf）。
    //   ★ 守卫：键/值非 null、值 ≥ 0（0 = 明确的"该商品免基础费"；负值 = 契约违约，与状态层同一条口径）。
    Map<CommodityId, Long> freightCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry :
        Objects.requireNonNull(commodityFreightBaseMilli, "commodityFreightBaseMilli").entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "MarketTopology.commodityFreightBaseMilli 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "MarketTopology.commodityFreightBaseMilli 不得为负: "
                + entry.getKey().value()
                + "="
                + entry.getValue());
      }
      freightCopy.put(entry.getKey(), entry.getValue());
    }
    this.commodityFreightBaseMilli = Collections.unmodifiableMap(freightCopy);
  }

  /**
   * ★★ <b>W1（2026-10-10）：逐格退化拓扑</b>（没有地图切片的世界：单模块夹具 / 纯状态读者）—— <b>每个有市场的格自成一个半径 0 的区</b>，
   * 且<b>不构造任何跨区候选</b>（保留 M2-L1 的"同格市场"行为）。
   *
   * <p>★★ <b>它逐值等于改动前的 {@code singleHex(markets)}</b>：那时"有市场的格"就是价表键集，每个键一个区、 币 = 该格的计价币、 {@code
   * moveCostAt} 恒 1、无道路/无辐射/默认费率。价表改成<b>每区一张</b>之后，"一个市场覆盖哪些格"由两处给定 —— 持久区（键 ∈ {@code
   * marketZones}）取 {@code MarketZone.hexes}，派生市场取价表条目自己的 {@link MarketTable#hexes()} ——
   * 本方法把它们<b>摊开回逐格</b>，于是同一个世界读到同一份退化拓扑。
   *
   * <p>★★ <b>W1d（2026-10-11）：缺表的区仍摊开为逐格区</b> —— 遍历的是<b>区 ∪ 价表条目</b>（不再是只看价表）， 某区没有价表条目时用<b>区</b>自己的
   * {@code hexes}/{@code legalTender} 当权威（区在那一形态下正是唯一权威）； 它在这一拓扑里没有价格（{@code MarketIndex.at}
   * 对它的成员格返回 {@code null}），与"缺表 = 该区不交易"一致 —— 但成员格的<b>归属</b>不因此消失（否则同一个世界在两处读到"这一格属不属于某个市场"的不同答案）。
   *
   * @param marketTables 逐市场价表（键 = 区 id / 派生市场 id）
   * @param marketZones 持久市场区表（空 = 全部条目都是派生市场）
   */
  public static MarketTopology singleHex(
      Map<MarketZoneId, MarketTable> marketTables, Map<MarketZoneId, MarketZone> marketZones) {
    Objects.requireNonNull(marketTables, "marketTables");
    Objects.requireNonNull(marketZones, "marketZones");
    List<MarketRegion> regions = new ArrayList<>();
    Map<HexCoord, MarketRegion> byHex = new LinkedHashMap<>();
    // ★ W1d：迭代集合 = 价表条目 ∪ 区（规范序：先价表 id、再补只出现在区表里的 id，逐段各自有序）。
    Set<MarketZoneId> ids = new LinkedHashSet<>(MarketTables.canonicalIds(marketTables));
    ids.addAll(MarketTables.canonicalIdsOf(marketZones.keySet()));
    for (MarketZoneId id : ids) {
      MarketTable table = marketTables.get(id);
      MarketZone zone = marketZones.get(id);
      if (table == null && zone == null) {
        continue; // 不可达（id 来自上面两处之一）
      }
      // ★ 成员格与计价币的权威按形态给：区在 ⇒ 区（I22，缺表时它仍是那一格的归属）；否则价表条目。
      Set<HexCoord> members = zone != null ? zone.hexes() : table.hexes();
      CurrencyId numeraire = zone != null ? zone.legalTender() : table.numeraire();
      List<HexCoord> sortedMembers = new ArrayList<>(members);
      sortedMembers.sort(HexCoord::compareTo);
      for (HexCoord hex : sortedMembers) {
        MarketNode node =
            new MarketNode(
                "hex:" + IndustryHexKeys.hexKey(hex.q(), hex.r()),
                hex,
                0,
                numeraire,
                MoneyVocabulary.SILVER_SPECIE.id());
        MarketRegion region = new MarketRegion(node, Set.of(hex));
        regions.add(region);
        byHex.put(hex, region);
      }
    }
    return new MarketTopology(
        regions,
        byHex,
        hex -> 1,
        NO_ROADS,
        NO_NEAREST_NODE_DISTANCE,
        TransportTariff.probeDefaults(),
        NO_CITY_DISCOUNT_PER_MILLE,
        NO_RURAL_PENALTY_PER_MILLE,
        false);
  }

  /**
   * ★★ <b>W1：显式成员格的拓扑（唯一的正式装配入口）</b>—— 区的成员格、锚、半径、计价币<b>全部由调用方给定</b>， 本类不做任何"半径推算 / 最近锚格归属 /
   * 从价表键反推格"。
   *
   * <p>★★ <b>为什么把装配全部推给组合根</b>：改动前本类有四个工厂（{@code singleHex}/{@code singleRegion}/{@code of}/{@code
   * ofZones}）， 每个都从 逐格价表 的**键集**推"哪些格有市场"、从**值**取"这一格用什么钱"。价表改成每区一张之后， 这两件事分别由 {@code
   * MarketZone.hexes}/{@code legalTender}（持久形态）与 {@link MarketTable#hexes()}/{@link
   * MarketTable#numeraire()}（派生形态）给定 ⇒ 拓扑侧只<b>采信</b>，不再"顺手推算"（单一权威）。
   *
   * <p>★ <b>两条 fail-closed 守卫</b>（与改动前的 {@code ofZones} 逐字同款）：
   *
   * <ol>
   *   <li>{@code regions} 不得含 null，节点 id 去重（<b>先到者胜、不抛</b>）；
   *   <li>成员格重叠 ⇒ <b>先到者胜、不抛</b>：状态层的 {@code EconomyData} 构造期守卫已把"一个 hex 属于两个市场"判成非法，
   *       这里只保证"即使拿到坏输入也不产生两处互相矛盾的归属"。
   * </ol>
   *
   * <p>★ <b>{@code regional}</b> 由调用方给（"这张拓扑有没有跨区候选"）：持久形态 = 区数 > 1； 派生形态恒 {@code true} （与改动前的
   * {@code singleRegion} 一致 —— 单区时它不产生任何跨区候选，因为 {@link #adjacent} 对自身恒 false）。
   *
   * @param regions 区的成员表（保序：调用方的声明序即读数序）
   * @param moveCostAt 逐格地形代价（组合根装配；{@code >= 1} 的钳位见 {@link #moveCostAt}）
   * @param roadBottleneckBetween 两格间道路瓶颈等级（组合根装配）
   * @param nearestNodeDistance 到最近集散节点 anchor 的 hex 距离（组合根装配；派生形态传恒 0，见改动前 singleRegion 的口径）
   * @param tariff 运输费率（{@link TransportTariff}）
   * @param cityDiscountPerMilleBetween 城市商人折扣（‰；无政策时传 {@link #NO_CITY_DISCOUNT_PER_MILLE}）
   * @param ruralPenaltyPerMilleBetween 农村商人累积惩罚（‰；无政策时传 {@link #NO_RURAL_PENALTY_PER_MILLE}）
   * @param regional 有没有跨区候选
   */
  public static MarketTopology ofRegions(
      List<MarketRegion> regions,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord, HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestNodeDistance,
      TransportTariff tariff,
      ToLongBiFunction<HexCoord, HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord, HexCoord> ruralPenaltyPerMilleBetween,
      boolean regional) {
    Objects.requireNonNull(regions, "regions");
    Objects.requireNonNull(moveCostAt, "moveCostAt");
    Objects.requireNonNull(roadBottleneckBetween, "roadBottleneckBetween");
    Objects.requireNonNull(nearestNodeDistance, "nearestNodeDistance");
    Objects.requireNonNull(tariff, "tariff");
    Objects.requireNonNull(cityDiscountPerMilleBetween, "cityDiscountPerMilleBetween");
    Objects.requireNonNull(ruralPenaltyPerMilleBetween, "ruralPenaltyPerMilleBetween");
    Map<String, MarketRegion> byId = new LinkedHashMap<>();
    for (MarketRegion region : regions) {
      if (region == null) {
        throw new IllegalArgumentException("MarketTopology.ofRegions 的 regions 不得含 null");
      }
      byId.putIfAbsent(region.node().nodeId(), region);
    }
    List<MarketRegion> built = new ArrayList<>(byId.size());
    Map<HexCoord, MarketRegion> byHex = new LinkedHashMap<>();
    for (MarketRegion region : byId.values()) {
      built.add(region);
      for (HexCoord member : region.members()) {
        byHex.putIfAbsent(member, region);
      }
    }
    return new MarketTopology(
        built,
        byHex,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween,
        regional);
  }

  /**
   * ★★ <b>W1：一个市场（区或派生市场）的规范区域</b> —— 组装 {@link MarketNode} + {@link MarketRegion} 的<b>唯一拼写点</b>。
   *
   * <p>★ 三条口径（与改动前 {@code singleRegion}/{@code ofZones} 逐字同源）：
   *
   * <ol>
   *   <li><b>锚格</b>：持久形态取 {@code MarketZone.anchor}；派生形态取成员格的<b>规范序第一个</b>（{@code (q,r)} 升序 —— "同状态
   *       ⇒ 同锚"，与 {@code singleRegion} 的 {@code canonicalFirstHex} 同一口径）；
   *   <li><b>计价币</b>：持久形态取 {@code legalTender}（唯一权威）；派生形态取价表条目的 {@code numeraire}；
   *   <li><b>接收工具</b>：取该币种在世界状态里的工具 id（没有 ⇒ 既有占位 {@code SILVER_SPECIE}；该字段全仓零读取者， 不影响任何结算行为）。
   * </ol>
   *
   * @param marketId 市场 id（= 区 id 或派生市场 id；成为 {@link MarketNode#nodeId()}，也是价表的键）
   * @param anchor 锚格（集散节点；区内参考价取这一格）
   * @param radiusHex 声明半径（只影响 {@link #adjacent} 的可达判据与读数）
   * @param numeraire 本市场计价币
   * @param receiveWith 卖方接收的货币工具
   * @param members 成员格（非空；迭代序由 {@link MarketRegion} 保序冻结）
   */
  public static MarketRegion regionOf(
      String marketId,
      HexCoord anchor,
      int radiusHex,
      CurrencyId numeraire,
      InstrumentId receiveWith,
      Set<HexCoord> members) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(numeraire, "numeraire");
    Objects.requireNonNull(receiveWith, "receiveWith");
    Objects.requireNonNull(members, "members");
    if (members.isEmpty()) {
      throw new IllegalArgumentException("MarketTopology.regionOf 的成员格不得为空: " + marketId);
    }
    MarketNode node = new MarketNode(marketId, anchor, radiusHex, numeraire, receiveWith);
    return new MarketRegion(node, members);
  }

  /** ★ 规范序（q, r 升序）第一个格；调用方保证非空（派生市场的锚 = 它）。 */
  public static HexCoord canonicalFirstHex(Set<HexCoord> hexes) {
    Objects.requireNonNull(hexes, "hexes");
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("MarketTopology.canonicalFirstHex 的格集不得为空");
    }
    HexCoord first = null;
    for (HexCoord hex : hexes) {
      if (hex == null) {
        throw new IllegalArgumentException("MarketTopology.canonicalFirstHex 的格集不得含 null");
      }
      if (first == null || hex.compareTo(first) < 0) {
        first = hex;
      }
    }
    return first;
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
   * ★★ <b>某个商品的<b>基础运费</b>（毫计价货币 / 商品单位 / 程）—— "缺键 ⇒ 具名缺省"的读取唯一拼写点</b>。
   *
   * <p>表里有该商品的键 ⇒ <b>状态值唯一权威</b>（GM 设过就照它，{@code 0} = 免基础费）；表里没有 ⇒ {@link
   * CommodityFreightBase#legacyMilli(CommodityId)}（= 现行硬编码分档：粮 1 / 纤维 1 / 布 2 / 工具 3；未登记商品 ⇒ 1） ⇒
   * <b>未设表的世界逐值等于改动前</b>（不变量 I-F1）。
   *
   * <p>★ <b>商品维只在这里</b>：费率入口 {@link #freightPerMilleBetween(HexCoord, HexCoord)} 不带商品参数 —— F
   * 批（{@code f3366545}）那条"商品系数乘整条费率"的路已撤销（它会与面值维相乘、方向相反）。
   */
  public long commodityFreightBaseMilliOf(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    Long configured = commodityFreightBaseMilli.get(commodity);
    return configured == null ? CommodityFreightBase.legacyMilli(commodity) : configured;
  }

  /**
   * ★★ <b>换一份商品基础运费表（返回新拓扑）</b> —— 组合根装配时的唯一注入点（{@code MarketTopologyBook.from(state)} 从 {@code
   * EconomyData.commodityFreightBaseMilli()} 现读）。
   *
   * <p>★ 为什么不进构造器参数表：现有四个工厂（{@code singleHex}/{@code singleRegion}/{@code of}/{@code
   * ofZones}）的签名是既有调用面（含大量夹具），加参数等于逐处改；本方法只多一处拷贝，且"没有传 ⇒ 空表 ⇒ 现行硬编码分档 ⇒ 逐值不变"这条默认语义**写在一个地方**。
   */
  public MarketTopology withCommodityFreightBase(Map<CommodityId, Long> table) {
    return new MarketTopology(
        regions,
        regionByHex,
        moveCostAt,
        roadBottleneckBetween,
        nearestNodeDistance,
        tariff,
        cityDiscountPerMilleBetween,
        ruralPenaltyPerMilleBetween,
        regional,
        table);
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
