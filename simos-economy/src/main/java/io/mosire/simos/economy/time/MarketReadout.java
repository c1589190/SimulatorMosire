package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★ <b>M2.7 逐区逐商品的市场读数（只读、纯派生、不改状态）</b>—— GUI / MCP 共用这一份视图的原料（AGENTS §8.3）。
 *
 * <p>★★ <b>逐字段的来源与窗口（报数前必读）</b>：
 *
 * <ul>
 *   <li>{@code supplyMilli} / {@code effectiveDemandMilli} / {@code needsButCannotAffordMilli}：
 *       <b>读时现算</b>，入口是 {@link MarketSettlement#planOrders}（与真正成交用的是同一条订单生成实现）—— 供给 = 卖订单 {@code
 *       sellable} 之和；有效需求 = 买订单数量之和（已含"预算 &gt; 0 + 按参考价买得起"两重过滤；买方限价 ask 在订单生成时已写入）；
 *   <li>{@code naturalNeedMilli} / {@code cycleNaturalNeedMilli}：读 {@code ClassRow}。 <b>粮</b>用 M2.7
 *       丙条累加器（{@code cycleNaturalNeedMilli} = {@code Σ_d dailyRationMilli(pop_d, d)}， {@code pop_d}
 *       = 第 d 天结算前的行人口 = 日初人口）；<b>其它商品</b>用 {@code naturalNeeds}（最近一次结算日那一份日需求）， {@code
 *       cycleNaturalNeedMilli} 对它们恒 0（逐商品累加器尚未实现，<b>不拿日需求冒充周期需要</b>）。★ 粮的累加器尚未累计 （旧档 / 还没结算过）⇒
 *       {@code naturalNeedWindow = last-settled-day}，退回该字段并如实标注；绝不把"还没累计"读成"没有需要"；
 *   <li>{@code referencePriceMilli} / {@code bidPriceMilli} / {@code askPriceMilli}：从 {@code
 *       Market} 现读现算 （bid/ask 两个具名常量，见 {@link Market}）；
 *   <li>★★ <b>{@code match}（成交量/到货价/运费/损耗/未成交原因分布/未利用运力）与 {@code priceUpdates} 来自 L2 的进程内 {@link
 *       MarketReport}——它<b>不落盘、重启即失</b>。没有它时本读数把 {@code match} 留成 {@link Optional#empty()}、并在
 *       {@code unavailable} 里具名"读不到"，<b>绝不填 0</b>（0 是"开了市但没有成交"， 与"读不到报告"是两回事）。
 * </ul>
 *
 * <p>★★ <b>跨区结算暂设即时</b>：{@code crossRegionSettlementImmediate} 原样接 {@link
 * MarketReport#CROSS_REGION_SETTLEMENT_IMMEDIATE}（M2.0 #4）；到货价里的"付款日"不是"到货日"。
 *
 * <p>★★ <b>它不进 {@code EconomyData}</b>：逐区读数由状态现算 + 进程内报告派生，{@code EconomyData} 里只有价格表与库存/账户 （见
 * {@code EconomyData} 的类注"市场表里没有会过期的读数"）。
 *
 * @param tick 读数的世界日（= 状态坐标 {@code state.meta().timestamp().tick()}）
 * @param lastSettledDay 经济切片最近一次日结算的世界日；★ 本批由"当前 tick"派生（日结算与状态推进同步）——若手搭一个 未经结算的状态，本值仍等于
 *     tick，调用方须自行核对（{@code provenance} 里写明）
 * @param priceMode 本轮报价模式（固定 / 自适应）；★ 固定模式下也必须显式给出（M2.6 判据③）
 * @param adaptivePricingEnabled 自适应开关的当值（默认 false）
 * @param crossRegionSettlementImmediate 跨区结算暂设即时（恒 true；M2.0 #4 必须在读数里标注）
 * @param priceUpdates 自适应模式下的逐 (集散节点, 商品) 改价记录；固定模式恒空
 * @param regions 逐区读数（{@code deriveFor} 时只含焦点区）
 * @param provenance 每个数"从哪来、什么窗口、人口快照是什么"的机器可读标注
 * @param unavailable 读不到的项与**具名原因**（键 = 项名；绝不填 0）
 */
public record MarketReadout(
    long tick,
    OptionalLong lastSettledDay,
    PriceMode priceMode,
    boolean adaptivePricingEnabled,
    boolean crossRegionSettlementImmediate,
    List<MarketReport.PriceUpdate> priceUpdates,
    List<RegionReadout> regions,
    Map<String, String> provenance,
    Map<String, String> unavailable) {

  public MarketReadout {
    Objects.requireNonNull(lastSettledDay, "lastSettledDay");
    Objects.requireNonNull(priceMode, "priceMode");
    priceUpdates = priceUpdates == null ? List.of() : List.copyOf(priceUpdates);
    regions = regions == null ? List.of() : List.copyOf(regions);
    provenance = Collections.unmodifiableMap(copyStrings(provenance));
    unavailable = Collections.unmodifiableMap(copyStrings(unavailable));
  }

  /** 包含该格的那个区（没有 ⇒ {@link Optional#empty()}：那一格不在任何市场区，合法状态）。 */
  public Optional<RegionReadout> regionOf(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    for (RegionReadout region : regions) {
      if (region.members().contains(hex)) {
        return Optional.of(region);
      }
    }
    return Optional.empty();
  }

  /** ★★ <b>全部区的读数</b>（逐区逐商品；代价与区的数量成正比，供全量报表用）。单格读口请用 {@link #deriveFor}。 */
  public static MarketReadout derive(
      EconomyData data,
      MarketTopology topology,
      long tick,
      MarketReadoutAccounts accounts,
      Optional<MarketReport> report) {
    return deriveInternal(null, data, topology, tick, accounts, report);
  }

  /**
   * ★★ <b>焦点区读数</b>（只算包含该格的区；{@code focus} 不在任何区 ⇒ {@link Optional#empty()}）。
   *
   * <p>★ 存在的理由：{@code economyHex} 类逐格读口被报表脚本逐格调用，必须只算一个区（全量版本会把 O(区) 的成本乘进 O(格) 次调用）。
   */
  public static Optional<MarketReadout> deriveFor(
      HexCoord focus,
      EconomyData data,
      MarketTopology topology,
      long tick,
      MarketReadoutAccounts accounts,
      Optional<MarketReport> report) {
    Objects.requireNonNull(focus, "focus");
    MarketRegion focusRegion = null;
    for (MarketRegion region : topology.regions()) {
      if (region.members().contains(focus)) {
        focusRegion = region;
        break;
      }
    }
    if (focusRegion == null) {
      return Optional.empty();
    }
    return Optional.of(deriveInternal(focusRegion, data, topology, tick, accounts, report));
  }

  private static MarketReadout deriveInternal(
      MarketRegion focus,
      EconomyData data,
      MarketTopology topology,
      long tick,
      MarketReadoutAccounts accounts,
      Optional<MarketReport> report) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(report, "report");
    // 市场轮次会话视图：只被 planOrders 读（不改状态）。★ 用 readout 自己的只读账本副本，不碰真实会话。
    // ★ R4-B.3a-perf：MarketRound 的参与者/必要投入/家户归属走只读索引；读口每次现建一份快照
    //   （O(unit + 配额 + 份额) 构建，避免逐格全表扫描）。
    SettlementIndex index =
        SettlementIndex.build(
            data.units(),
            data.industries(),
            data.assetShares(),
            data.allocations(),
            data.classes(),
            data.debtContracts(),
            data.relations());
    MarketSettlement.MarketRound round =
        new MarketSettlement.MarketRound(
            tick,
            data.classes(),
            accounts.householdGoods(),
            accounts.householdMoney(),
            accounts.householdFrozenGoods(),
            accounts.householdFrozenMoney(),
            accounts.operatorGoods(),
            accounts.operatorMoney(),
            accounts.operatorFrozenGoods(),
            accounts.operatorFrozenMoney(),
            Map.of(),
            Map.of(),
            data.industries(),
            data.units(),
            data.assetShares(),
            data.relations(),
            data.allocations(),
            data.shipments(),
            new ProductionLedger.Accumulator(tick),
            data.operatorConditions(),
            index,
            // ★★ R4-E2：读口与结算走同一条 planOrders ⇒ 需求目标必须同源传入，否则读到的订单会与真实下单漂开。
            data.demands());
    Map<HexCoord, MarketRegion> regionByHex = new LinkedHashMap<>();
    List<MarketRegion> regions = new ArrayList<>();
    for (MarketRegion region : topology.regions()) {
      if (focus != null && !region.equals(focus)) {
        continue;
      }
      regions.add(region);
      for (HexCoord member : region.members()) {
        regionByHex.put(member, region);
      }
    }
    Map<MarketRegion, List<HouseholdId>> rowsByRegion = new LinkedHashMap<>();
    for (MarketRegion region : regions) {
      rowsByRegion.put(region, new ArrayList<>());
    }
    for (Map.Entry<HouseholdId, ClassRow> entry : data.classes().entrySet()) {
      MarketRegion region = regionByHex.get(entry.getValue().view().hex());
      if (region != null) {
        rowsByRegion.get(region).add(entry.getKey());
      }
    }
    // ★ 一次建好"格 → 行"索引：逐区逐商品调 planOrders 时不再每次重扫全部行。
    Map<String, List<HouseholdId>> rowsByHex = EconomySettlement.rowsByHex(data.classes());
    List<RegionReadout> regionReadouts = new ArrayList<>(regions.size());
    for (MarketRegion region : regions) {
      Market anchorMarket = data.markets().get(region.anchor());
      if (anchorMarket == null) {
        continue;
      }
      List<HouseholdId> rows = rowsByRegion.getOrDefault(region, List.of());
      List<CommodityReadout> commodities = new ArrayList<>();
      for (CommodityId commodity : sortedCommodities(anchorMarket)) {
        long reference = anchorMarket.priceOf(commodity);
        if (reference <= 0L) {
          continue; // 没定价 ⇒ 本区不交易它（同 Market 的口径），不伪造一行 0
        }
        long supply = 0L;
        long demand = 0L;
        Set<ActorRef> buyers = new LinkedHashSet<>();
        for (HexCoord member : region.members()) {
          Market memberMarket = data.markets().get(member);
          if (memberMarket == null || memberMarket.priceOf(commodity) <= 0L) {
            continue;
          }
          MarketSettlement.PlannedOrders orders =
              MarketSettlement.planOrders(round, member, memberMarket, commodity, rowsByHex);
          for (SellOrder sell : orders.sells()) {
            supply += sell.sellable();
          }
          for (BuyOrder buy : orders.buys()) {
            demand += buy.quantity();
            buyers.add(buy.requester());
          }
        }
        long dailyNeed = 0L;
        long cycleNeed = 0L;
        for (HouseholdId key : rows) {
          ClassRow row = data.classes().get(key);
          if (row == null) {
            continue;
          }
          dailyNeed += row.naturalNeeds().getOrDefault(commodity, 0L);
          if (commodity.equals(EconomySettlement.GRAIN)) {
            cycleNeed += row.cycleNaturalNeedMilli();
          }
        }
        long cannotAfford = 0L;
        long cannotAffordHouseholds = 0L;
        boolean grain = commodity.equals(EconomySettlement.GRAIN);
        if (grain) {
          long ask = anchorMarket.askPriceOf(commodity);
          for (HouseholdId key : rows) {
            ClassRow row = data.classes().get(key);
            if (row == null) {
              continue;
            }
            // ★ 周期累加器尚未累计（旧档 / 还没结算过）⇒ 退回首行的最近结算日日需求，窗口如实标成
            //   last-settled-day；绝不把"还没累计"读成"没有需要"。
            long need =
                row.cycleNaturalNeedMilli() > 0L
                    ? row.cycleNaturalNeedMilli()
                    : row.naturalNeeds().getOrDefault(commodity, 0L);
            if (need <= 0L || buyers.contains(HouseholdActors.of(key))) {
              continue; // 本轮生成了有效需求 ⇒ 不算"买不起"
            }
            long spendable = spendableMoney(accounts, key, anchorMarket.numeraire());
            if (spendable <= 0L
                || ask <= 0L
                || spendable * EconomySettlement.MILLI_PER_GRAIN / ask <= 0L) {
              cannotAfford += need;
              cannotAffordHouseholds++;
            }
          }
        }
        boolean cycleWindow = grain && cycleNeed > 0L;
        long naturalNeed = cycleWindow ? cycleNeed : dailyNeed;
        String naturalNeedWindow = cycleWindow ? "cycle" : "last-settled-day";
        Optional<CommodityMatchReadout> match =
            report.map(value -> matchReadout(value, region, commodity, regionByHex));
        commodities.add(
            new CommodityReadout(
                commodity,
                reference,
                anchorMarket.bidPriceOf(commodity),
                anchorMarket.askPriceOf(commodity),
                supply,
                naturalNeed,
                naturalNeedWindow,
                cycleNeed,
                demand,
                cannotAfford,
                cannotAffordHouseholds,
                match));
      }
      regionReadouts.add(
          new RegionReadout(
              region.node().nodeId(),
              region.anchor(),
              region.radiusHex(),
              region.numeraire(),
              region.members(),
              commodities));
    }
    Map<String, String> unavailable = new LinkedHashMap<>();
    if (report.isEmpty()) {
      unavailable.put("matchResults", MATCH_REPORT_PROCESS_ONLY);
    }
    long missingHouseholdAccounts = 0L;
    for (Map.Entry<HouseholdId, ClassRow> entry : data.classes().entrySet()) {
      if (regionByHex.containsKey(entry.getValue().view().hex())
          && !accounts.householdGoods().containsKey(entry.getKey())) {
        missingHouseholdAccounts++;
      }
    }
    if (missingHouseholdAccounts > 0L) {
      unavailable.put(
          "householdAccounts",
          "有 " + missingHouseholdAccounts + " 个家户账在本次读口缺席（读时派生按缺失键跳过；这是读口覆盖不足，不是库存 0）");
    }
    Map<String, String> provenance = new LinkedHashMap<>();
    provenance.put("supplyDemand", SUPPLY_DEMAND_PROVENANCE);
    provenance.put("naturalNeed", NATURAL_NEED_PROVENANCE);
    provenance.put("cycleNaturalNeed", CYCLE_NATURAL_NEED_PROVENANCE);
    provenance.put("matchResults", MATCH_REPORT_PROVENANCE);
    provenance.put("lastSettledDay", LAST_SETTLED_DAY_PROVENANCE);
    provenance.put("priceMode", PRICE_MODE_PROVENANCE);
    provenance.put("crossRegionSettlementImmediate", CROSS_REGION_PROVENANCE);
    OptionalLong lastSettledDay =
        data.meta().isPresent() && tick >= data.meta().orElseThrow().activatedDay()
            ? OptionalLong.of(tick)
            : OptionalLong.empty();
    return new MarketReadout(
        tick,
        lastSettledDay,
        // ★ 有本轮报告 ⇒ 用报告里的模式（它来自真正开市的那次结算）；没报告 ⇒ 用当前常量（固定/自适应）。
        report.map(MarketReport::priceMode).orElse(MarketSettlement.priceMode()),
        MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED,
        MarketReport.CROSS_REGION_SETTLEMENT_IMMEDIATE,
        report.map(MarketReport::priceUpdates).orElse(List.of()),
        regionReadouts,
        provenance,
        unavailable);
  }

  private static CommodityMatchReadout matchReadout(
      MarketReport report,
      MarketRegion region,
      CommodityId commodity,
      Map<HexCoord, MarketRegion> regionByHex) {
    long traded = 0L;
    BigInteger landedNumerator = BigInteger.ZERO;
    long freight = 0L;
    long loss = 0L;
    long unusedCapacity = 0L;
    boolean bottleneck = false;
    Map<MarketUnfilledReason, Long> buyCounts = new LinkedHashMap<>();
    Map<MarketUnfilledReason, Long> sellCounts = new LinkedHashMap<>();
    Map<MarketUnfilledReason, Long> buyQuantities = new LinkedHashMap<>();
    Map<MarketUnfilledReason, Long> sellQuantities = new LinkedHashMap<>();
    for (MarketReport.Fill fill : report.fills()) {
      if (!fill.commodity().equals(commodity)) {
        continue;
      }
      MarketRegion at = regionByHex.get(fill.to());
      if (at == null || !at.equals(region)) {
        continue;
      }
      traded += fill.quantity();
      landedNumerator =
          landedNumerator.add(
              BigInteger.valueOf(fill.quantity())
                  .multiply(
                      BigInteger.valueOf(fill.unitPriceMilli() + fill.freightPerUnitMilli())));
      freight += fill.freightMilli();
      loss += fill.lossMilli();
    }
    for (MarketReport.Unfilled unfilled : report.unfilled()) {
      if (!unfilled.commodity().equals(commodity)) {
        continue;
      }
      MarketRegion at = regionByHex.get(unfilled.hex());
      if (at == null || !at.equals(region)) {
        continue;
      }
      if (unfilled.buyerSide()) {
        buyCounts.merge(unfilled.reason(), 1L, Long::sum);
        buyQuantities.merge(unfilled.reason(), unfilled.quantity(), Long::sum);
      } else {
        sellCounts.merge(unfilled.reason(), 1L, Long::sum);
        sellQuantities.merge(unfilled.reason(), unfilled.quantity(), Long::sum);
      }
    }
    for (MarketReport.RouteUsage route : report.routes()) {
      if (!route.commodity().equals(commodity)) {
        continue;
      }
      MarketRegion at = regionByHex.get(route.to());
      if (at == null || !at.equals(region)) {
        continue;
      }
      long capacity = route.capacityPerWindow() * MarketSettlement.MARKET_MAX_TRANSPORT_ROUNDS;
      unusedCapacity += Math.max(0L, capacity - route.used());
      bottleneck |= route.bottleneck();
    }
    OptionalLong landedPrice =
        traded > 0L
            ? OptionalLong.of(landedNumerator.divide(BigInteger.valueOf(traded)).longValueExact())
            : OptionalLong.empty();
    // ★★ S3：逐槽位结果的**聚合**（MarketReport 里有逐条，这里按区×商品折成可读的计数/极值；
    //   "缺价/未知成本"与"库存已足"因此不会消失在总数里）。
    long sellerOutcomeCount = 0L;
    long sellerSelfUsableQtyMilli = 0L;
    long sellerOutcompetedCount = 0L;
    long sellerOutcompetedQtyMilli = 0L;
    long sellerPriceMissingCount = 0L;
    long cheapestSellerCost = Long.MAX_VALUE;
    long dearestSellerCost = Long.MIN_VALUE;
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      if (!outcome.commodity().equals(commodity)) {
        continue;
      }
      MarketRegion at = regionByHex.get(outcome.hex());
      if (at == null || !at.equals(region)) {
        continue;
      }
      sellerOutcomeCount++;
      MarketUnfilledReason reason = outcome.unfilledReason().orElse(null);
      if (reason == MarketUnfilledReason.UNSOLD_SELF_USABLE) {
        sellerSelfUsableQtyMilli += outcome.unfilledQty();
      }
      if (reason == MarketUnfilledReason.OUTCOMPETED) {
        sellerOutcompetedCount++;
        sellerOutcompetedQtyMilli += outcome.outcompetedQty();
      }
      if (outcome.priceMissing()) {
        sellerPriceMissingCount++;
      }
      if (outcome.costKnown()) {
        cheapestSellerCost = Math.min(cheapestSellerCost, outcome.unitCostEstimateMilli());
        dearestSellerCost = Math.max(dearestSellerCost, outcome.unitCostEstimateMilli());
      }
    }
    long buyerOutcomeCount = 0L;
    long buyerStockSufficientCount = 0L;
    long buyerNoBudgetCount = 0L;
    long buyerGapMilli = 0L;
    for (MarketReport.BuyerOutcome outcome : report.buyerOutcomes()) {
      if (!outcome.commodity().equals(commodity)) {
        continue;
      }
      MarketRegion at = regionByHex.get(outcome.hex());
      if (at == null || !at.equals(region)) {
        continue;
      }
      buyerOutcomeCount++;
      MarketUnfilledReason reason = outcome.unfilledReason().orElse(null);
      if (reason == MarketUnfilledReason.STOCK_SUFFICIENT) {
        buyerStockSufficientCount++;
      }
      if (reason == MarketUnfilledReason.NO_BUDGET) {
        buyerNoBudgetCount++;
      }
      buyerGapMilli += outcome.gapQty();
    }
    OptionalLong cheapestSellerCostReadout =
        cheapestSellerCost == Long.MAX_VALUE
            ? OptionalLong.empty()
            : OptionalLong.of(cheapestSellerCost);
    OptionalLong dearestSellerCostReadout =
        dearestSellerCost == Long.MIN_VALUE
            ? OptionalLong.empty()
            : OptionalLong.of(dearestSellerCost);
    return new CommodityMatchReadout(
        traded,
        landedPrice,
        freight,
        loss,
        unusedCapacity,
        bottleneck,
        sellerOutcomeCount,
        sellerSelfUsableQtyMilli,
        sellerOutcompetedCount,
        sellerOutcompetedQtyMilli,
        sellerPriceMissingCount,
        cheapestSellerCostReadout,
        dearestSellerCostReadout,
        buyerOutcomeCount,
        buyerStockSufficientCount,
        buyerNoBudgetCount,
        buyerGapMilli,
        buyCounts,
        sellCounts,
        buyQuantities,
        sellQuantities);
  }

  private static List<CommodityId> sortedCommodities(Market anchorMarket) {
    List<CommodityId> commodities = new ArrayList<>(anchorMarket.prices().keySet());
    commodities.sort(Comparator.comparing(CommodityId::value));
    return commodities;
  }

  private static long spendableMoney(
      MarketReadoutAccounts accounts, HouseholdId key, CurrencyId currency) {
    long money = accounts.householdMoney().getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
    long frozen =
        accounts.householdFrozenMoney().getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
    return Math.max(0L, money - frozen);
  }

  /** 字符串表的**构造期防御性拷贝**（保序；旧档/空表按空处理）。不可变包装写在构造器赋值处（SpotBugs 只认那里）。 */
  private static Map<String, String> copyStrings(Map<String, String> values) {
    if (values == null) {
      return new LinkedHashMap<>();
    }
    Map<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketReadout 的字符串表不得含 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 逐区读数的只读视图（{@code members} 是派生的成员格集，供"这个格属于哪个区"反查）。 */
  public record RegionReadout(
      String regionId,
      HexCoord anchor,
      int radiusHex,
      CurrencyId numeraire,
      Set<HexCoord> members,
      List<CommodityReadout> commodities) {

    public RegionReadout {
      if (regionId == null || regionId.isBlank()) {
        throw new IllegalArgumentException("RegionReadout.regionId 不得为空白");
      }
      Objects.requireNonNull(anchor, "anchor");
      if (radiusHex < 0) {
        throw new IllegalArgumentException("RegionReadout.radiusHex 不得为负: " + radiusHex);
      }
      Objects.requireNonNull(numeraire, "numeraire");
      Objects.requireNonNull(members, "members");
      if (members.isEmpty()) {
        throw new IllegalArgumentException("RegionReadout.members 不得为空");
      }
      members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
      commodities = commodities == null ? List.of() : List.copyOf(commodities);
    }
  }

  /**
   * ★★ <b>逐区逐商品的读数</b>。{@code naturalNeedMilli} 与 {@code effectiveDemandMilli} <b>分列</b>（M2.6 判据①）：
   * 前者是生理需要、后者是有支付力且合限价的需求；{@code needsButCannotAffordMilli} 是"有需要、但本轮完全没有有效需求 且可花货币 ≤ 0"的那部分（M2.6
   * 判据②的显式读数——穷到买不起时不会被报成"无人缺粮"）。
   *
   * @param cycleNaturalNeedMilli 粮 = 本周期累计自然需要；其它商品 = 0（逐商品累加器未实现，见类注）
   * @param naturalNeedWindow {@code cycle} = 用本周期累加器；{@code last-settled-day} = 累加器尚未累计（旧档 /
   *     还没结算过）⇒ 退回最近结算日日需求并如实标注窗口；非粮商品恒 {@code last-settled-day}
   * @param effectiveDemandMilli 本轮买订单数量之和（预算 &gt; 0 且买得起；来自同一 {@code planOrders}）
   * @param needsButCannotAffordMilli 生理需要 &gt; 0、本轮无有效需求、且可花货币 ≤ 0 的部分（只对粮；毫粮）
   * @param match 撮合结果（进程内报告；读不到 ⇒ {@link Optional#empty()}，不是 0）
   */
  public record CommodityReadout(
      CommodityId commodity,
      long referencePriceMilli,
      long bidPriceMilli,
      long askPriceMilli,
      long supplyMilli,
      long naturalNeedMilli,
      String naturalNeedWindow,
      long cycleNaturalNeedMilli,
      long effectiveDemandMilli,
      long needsButCannotAffordMilli,
      long needsButCannotAffordHouseholds,
      Optional<CommodityMatchReadout> match) {

    public CommodityReadout {
      Objects.requireNonNull(commodity, "commodity");
      if (referencePriceMilli <= 0L || bidPriceMilli <= 0L || askPriceMilli <= 0L) {
        throw new IllegalArgumentException(
            "CommodityReadout 的三个价格都必须 > 0: "
                + referencePriceMilli
                + "/"
                + bidPriceMilli
                + "/"
                + askPriceMilli);
      }
      Objects.requireNonNull(naturalNeedWindow, "naturalNeedWindow");
      Objects.requireNonNull(match, "match");
    }
  }

  /**
   * ★★ <b>撮合结果读数（进程内报告派生）</b>：成交量、成交加权到货价、运费、损耗、未成交原因分布、未利用运力。
   *
   * <ul>
   *   <li>{@code landedPriceMilli} = {@code Σ 数量 × (成交单价 + 单位运费) ÷ Σ 数量}（向下取整）；<b>没有成交 ⇒ {@link
   *       OptionalLong#empty()}，不是 0</b>；
   *   <li>{@code unusedCapacityMilli} = 每条路线的 {@code max(0, 每窗运力 × 最大轮数 − 已用)} 之和；
   *   <li>{@code unfilled*Counts} / {@code unfilled*Quantities}：买方/卖方两侧分开、逐原因档——"有货卖不掉"与
   *       "买不起"因此不会合成一个数。
   * </ul>
   */
  public record CommodityMatchReadout(
      long tradedMilli,
      OptionalLong landedPriceMilli,
      long freightMilli,
      long lossMilli,
      long unusedCapacityMilli,
      boolean capacityBottleneck,
      long sellerOutcomeCount,
      long sellerSelfUsableQtyMilli,
      long sellerOutcompetedCount,
      long sellerOutcompetedQtyMilli,
      long sellerPriceMissingCount,
      OptionalLong cheapestSellerUnitCostMilli,
      OptionalLong dearestSellerUnitCostMilli,
      long buyerOutcomeCount,
      long buyerStockSufficientCount,
      long buyerNoBudgetCount,
      long buyerGapMilli,
      Map<MarketUnfilledReason, Long> unfilledBuyCounts,
      Map<MarketUnfilledReason, Long> unfilledSellCounts,
      Map<MarketUnfilledReason, Long> unfilledBuyQuantities,
      Map<MarketUnfilledReason, Long> unfilledSellQuantities) {

    public CommodityMatchReadout {
      Objects.requireNonNull(landedPriceMilli, "landedPriceMilli");
      Objects.requireNonNull(cheapestSellerUnitCostMilli, "cheapestSellerUnitCostMilli");
      Objects.requireNonNull(dearestSellerUnitCostMilli, "dearestSellerUnitCostMilli");
      if (sellerOutcomeCount < 0L
          || sellerSelfUsableQtyMilli < 0L
          || sellerOutcompetedCount < 0L
          || sellerOutcompetedQtyMilli < 0L
          || sellerPriceMissingCount < 0L
          || buyerOutcomeCount < 0L
          || buyerStockSufficientCount < 0L
          || buyerNoBudgetCount < 0L
          || buyerGapMilli < 0L) {
        throw new IllegalArgumentException("CommodityMatchReadout 的 S3 计数/数量不得为负");
      }
      unfilledBuyCounts = Collections.unmodifiableMap(copyCounts(unfilledBuyCounts));
      unfilledSellCounts = Collections.unmodifiableMap(copyCounts(unfilledSellCounts));
      unfilledBuyQuantities = Collections.unmodifiableMap(copyCounts(unfilledBuyQuantities));
      unfilledSellQuantities = Collections.unmodifiableMap(copyCounts(unfilledSellQuantities));
    }

    /** 原因分布的**构造期防御性拷贝**；不可变包装写在赋值处（SpotBugs 只认那里）。 */
    private static Map<MarketUnfilledReason, Long> copyCounts(
        Map<MarketUnfilledReason, Long> counts) {
      if (counts == null) {
        return new LinkedHashMap<>();
      }
      Map<MarketUnfilledReason, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<MarketUnfilledReason, Long> entry : counts.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("未成交原因分布不得含 null: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      return copy;
    }
  }

  /** 进程内报告缺失的具名原因（读数与 {@code economyHex} 的 unavailable 共用这一句）。 */
  static final String MATCH_REPORT_PROCESS_ONLY =
      "L2 的 MarketReport 是进程内瞬态（重启/换进程即失）：成交量/到货价/运费/损耗/未成交原因/未利用运力读不到；"
          + "这些字段缺失不是 0（0 表示开市了但没有对应成交）";

  private static final String SUPPLY_DEMAND_PROVENANCE =
      "供给 = 卖订单 sellable 之和、有效需求 = 买订单数量之和：读时经 MarketSettlement.planOrders（与成交同一条订单生成）";
  private static final String NATURAL_NEED_PROVENANCE =
      "生理需要：粮用 ClassRow.cycleNaturalNeedMilli（本周期累计，日初人口逐日累加）；"
          + "累加器尚未累计（旧档/未结算）⇒ 退回最近结算日的 naturalNeeds 并把 naturalNeedWindow 标成 last-settled-day；"
          + "其它商品用 ClassRow.naturalNeeds（最近结算日当日）";
  private static final String CYCLE_NATURAL_NEED_PROVENANCE =
      "cycleNaturalNeedMilli = Σ_d dailyRationMilli(pop_d, d)，pop_d = 第 d 天结算前的行人口（日初人口）；本周期第一天重置为当天那一份";
  private static final String MATCH_REPORT_PROVENANCE =
      "撮合结果来自进程内 MarketReport（不落盘、重启即失）；缺失时 match=null 且 unavailable.matchResults 具名";
  private static final String LAST_SETTLED_DAY_PROVENANCE =
      "lastSettledDay 由当前 state tick 派生（经济日结算与状态推进同步）；手搭状态未经结算时仍等于 tick，读的人自行核对";
  private static final String PRICE_MODE_PROVENANCE =
      "fixed = 固定报价（默认）；adaptive = 按供需每轮调价（MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED）";
  private static final String CROSS_REGION_PROVENANCE = "跨区结算暂设即时（M2.0 #4）：货款/运费在发运日结清、货在 ETA 后到";
}
