package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
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
 *   <li>{@code naturalNeedMilli} / {@code cycleNaturalNeedMilli}：读 {@code HouseholdEconomy}。
 *       <b>粮</b>用 M2.7 丙条累加器（{@code cycleNaturalNeedMilli} = {@code Σ_d 当户注入的 naturalNeeds[grain]}，
 *       窗口 = 本周期实际经过的天）；<b>其它商品</b>用 {@code naturalNeeds}（最近一次结算日那一份日需求）， {@code
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
 * @param adaptivePricingEnabled 自适应开关的当值（2026-10-07 起默认 true）
 * @param crossRegionSettlementImmediate 跨区结算暂设即时（恒 true；M2.0 #4 必须在读数里标注）
 * @param priceUpdates 自适应模式下的逐 (集散节点, 商品) 改价记录；固定模式恒空
 * @param creditFills ★★ D-030：来自进程内 {@link MarketReport#creditFills()} 的信用成交透传（只读；没有报告 ⇒ 空表， 不填
 *     0）；{@code deriveFor} 时只含落点在该焦点区成员格上的信用成交
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
    List<MarketReport.CreditFill> creditFills,
    List<RegionReadout> regions,
    Map<String, String> provenance,
    Map<String, String> unavailable) {

  public MarketReadout {
    Objects.requireNonNull(lastSettledDay, "lastSettledDay");
    Objects.requireNonNull(priceMode, "priceMode");
    priceUpdates = priceUpdates == null ? List.of() : List.copyOf(priceUpdates);
    creditFills = creditFills == null ? List.of() : List.copyOf(creditFills);
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
    // ★★ R1：读口与结算**同源**注入政府市场授权计划（同一 {MarketMandatePlan.of}）—— 否则读口会显示
    //   一套订单、日结算下另一套（"看到的订单 == 会下的订单"）。
    GovernmentMarketMandatePlan govMandatePlan =
        GovernmentMarketMandatePlan.of(data.govMarketMandates(), data.governments(), tick);
    MarketSettlement.MarketRound round =
        new MarketSettlement.MarketRound(
                tick,
                data.classes(),
                accounts.householdGoods(),
                accounts.householdMoney(),
                accounts.householdFrozenGoods(),
                accounts.householdFrozenMoney(),
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
                data.demands(),
                // ★★ R1：**国库户不再排除**（它回到市场，只是"只按授权下单"）。★ unit 户集合只有 app 组合根
                //   看得见，读口不带 unit 切片 ⇒ 读口的 unit 户口径是本批的已知边界（记在 Z7e 清单）。
                MarketRegulation.none(),
                null,
                null,
                Set.of())
            .withGovMandates(govMandatePlan);
    // ★★★ A3（2026-10-10）：此处原有"读口与结算同源注入跑商家户集合"（§16.4 ① 的
    //   {@code withMerchantHouseholds(MerchantIdentity.merchants(...))}）—— **已随该字段一并撤回**：
    //   它只服务 {@code necessaryInputsOf} 里"给跑商家户下夹一趟工具"那段特例，而该特例已删
    //   （预留改由产业声明的投入经标准循环覆盖，I-H6）⇒ 读口与结算的 necessary 仍然同源（都不追加那一项）。
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
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        data.classes().entrySet()) {
      MarketRegion region = regionByHex.get(householdEconomyEntry.getValue().view().hex());
      if (region != null) {
        rowsByRegion.get(region).add(householdEconomyEntry.getKey());
      }
    }
    // ★ 一次建好"格 → 行"索引：逐区逐商品调 planOrders 时不再每次重扫全部行。
    Map<String, List<HouseholdId>> rowsByHex = EconomySettlement.rowsByHex(data.classes());
    // ★★ P-T5b：读口与结算**同源**选币（"该户最强持有币"）—— 否则多币世界里读到的买单数量会与实际下的单漂开。
    //   ★ 选币本身只依赖"家户行 + 持币 + 各币法定区价表"，与估值无关 ⇒ 两侧选出的币恒相同；折算用的估值用**同一条**
    //     装配式（官方窗口 = {@code FxRoundInput.of(...)}，与 EconomySettlement 逐字同源）+ 同一份当地流通集合。
    //   ★ 读口仍有的众所周知边界（本批不扩大它）：不注入口岸管制力（{@code portEnforcement}）⇒ 那种"减项"折不出来时
    //     按当地流通的面值算；unit 户排除集只有组合根看得见（Z7e 清单同条）。
    CurrencyValuation readoutValuation =
        CurrencyValuation.of(
            FxRoundInput.of(data.governments(), data.moneyIssuances(), data.marketZones()),
            CurrencyValuation.circulationByRegion(topology, rowsByHex, round.householdMoney()),
            round.portEnforcement());
    MarketPayChoice payChoice =
        MarketPayChoice.of(round, data.markets(), topology, rowsByHex, readoutValuation, false);
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
        if (!anchorMarket.hasPrice(commodity)) {
          continue; // 从未定价 ⇒ 本区不交易它（同 Market 的口径），不伪造一行 0
        }
        long supply = 0L;
        long demand = 0L;
        Set<ActorRef> buyers = new LinkedHashSet<>();
        for (HexCoord member : region.members()) {
          Market memberMarket = data.markets().get(member);
          if (memberMarket == null || !memberMarket.hasPrice(commodity)) {
            continue; // 该成员格从未定价 ⇒ 不生成订单（明确 0 价仍要进订单/读数）
          }
          // ★★ P-T1c：订单生成的定价只看逐格价表 ⇒ 不再传区 id 与调控（区级参考价覆盖已删，设计书 §16）。
          MarketSettlement.PlannedOrders orders =
              MarketSettlement.planOrders(
                  round, member, memberMarket, commodity, rowsByHex, payChoice);
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
          HouseholdEconomy householdEconomy = data.classes().get(key);
          if (householdEconomy == null) {
            continue;
          }
          dailyNeed += householdEconomy.naturalNeeds().getOrDefault(commodity, 0L);
          if (commodity.equals(EconomySettlement.GRAIN)) {
            cycleNeed += householdEconomy.cycleNaturalNeedMilli();
          }
        }
        long cannotAfford = 0L;
        long cannotAffordHouseholds = 0L;
        boolean grain = commodity.equals(EconomySettlement.GRAIN);
        if (grain) {
          long ask = anchorMarket.askPriceOf(commodity);
          for (HouseholdId key : rows) {
            HouseholdEconomy householdEconomy = data.classes().get(key);
            if (householdEconomy == null) {
              continue;
            }
            // ★ 周期累加器尚未累计（旧档 / 还没结算过）⇒ 退回首行的最近结算日日需求，窗口如实标成
            //   last-settled-day；绝不把"还没累计"读成"没有需要"。
            long need =
                householdEconomy.cycleNaturalNeedMilli() > 0L
                    ? householdEconomy.cycleNaturalNeedMilli()
                    : householdEconomy.naturalNeeds().getOrDefault(commodity, 0L);
            if (need <= 0L || buyers.contains(HouseholdActors.of(key))) {
              continue; // 本轮生成了有效需求 ⇒ 不算"买不起"
            }
            long spendable = spendableMoney(accounts, key, anchorMarket.numeraire());
            if (reference > 0L
                && (spendable <= 0L
                    || ask <= 0L
                    || spendable * EconomySettlement.MILLI_PER_GRAIN / ask <= 0L)) {
              // ★ 2026-10-09：0 价免费交易不算"买不起"（货款腿为 0；运费另计，不在本读数里混算）。
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
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        data.classes().entrySet()) {
      if (regionByHex.containsKey(householdEconomyEntry.getValue().view().hex())
          && !accounts.householdGoods().containsKey(householdEconomyEntry.getKey())) {
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
    // ★★ D-030：信用成交只读透传（焦点区读口按落点格过滤；没有报告 ⇒ 空表，不填 0）。
    List<MarketReport.CreditFill> creditFills = new ArrayList<>();
    if (report.isPresent()) {
      for (MarketReport.CreditFill fill : report.get().creditFills()) {
        if (regionByHex.containsKey(fill.hex())) {
          creditFills.add(fill);
        }
      }
    }
    return new MarketReadout(
        tick,
        lastSettledDay,
        // ★ 有本轮报告 ⇒ 用报告里的模式（它来自真正开市的那次结算）；没报告 ⇒ 用当前常量（固定/自适应）。
        report.map(MarketReport::priceMode).orElse(MarketSettlement.priceMode()),
        MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED,
        MarketReport.CROSS_REGION_SETTLEMENT_IMMEDIATE,
        report.map(MarketReport::priceUpdates).orElse(List.of()),
        creditFills,
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
    // ★★ P-T4：撮合读数里的每一项钱都**按币分列**（禁跨币相加）。
    //   ★ 到货价 = Σ 数量 × (单价 + 单位运费) ÷ Σ 数量 —— 这个和只在**单价币 == 运费币**时有定义：
    //     单价是卖方币、单位运费是买方币，异币成交两栏是两种钱。⇒ 逐币各算一份；异币成交**具名排除**
    //     （计入 landedPriceExcludedFills，不参与任何币的到货价），照 EnterpriseProfitBook 的先例。
    Map<CurrencyId, BigInteger> landedNumeratorByCurrency = new LinkedHashMap<>();
    Map<CurrencyId, Long> landedQuantityByCurrency = new LinkedHashMap<>();
    long landedPriceExcludedFills = 0L;
    Map<CurrencyId, Long> freightByCurrency = new LinkedHashMap<>();
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
      OptionalLong landed = fill.landedUnitPriceMilli();
      if (landed.isPresent()) {
        CurrencyId currency = fill.unitCurrency();
        landedNumeratorByCurrency.merge(
            currency,
            BigInteger.valueOf(fill.quantity()).multiply(BigInteger.valueOf(landed.getAsLong())),
            BigInteger::add);
        landedQuantityByCurrency.merge(currency, fill.quantity(), Long::sum);
      } else {
        landedPriceExcludedFills++;
      }
      freightByCurrency.merge(fill.freightCurrency(), fill.freightMilli(), Long::sum);
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
    Map<CurrencyId, Long> landedPriceByCurrency = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : landedQuantityByCurrency.entrySet()) {
      landedPriceByCurrency.put(
          entry.getKey(),
          landedNumeratorByCurrency
              .get(entry.getKey())
              .divide(BigInteger.valueOf(entry.getValue()))
              .longValueExact());
    }
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
        landedPriceByCurrency,
        landedPriceExcludedFills,
        freightByCurrency,
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
   * <p>★★ <b>P-T4：钱一律按币分列</b>（I-C10 读口不混币）：
   *
   * <ul>
   *   <li>{@code landedPriceByCurrency} = 逐币 {@code Σ 数量 × (成交单价 + 单位运费) ÷ Σ 数量}（向下取整）；<b>没有成交 ⇒
   *       空表，不是 0</b>；★ 单价是卖方币、单位运费是买方币 ⇒ 异币成交的"单价 + 单位运费"没有定义，那些成交被 <b>具名排除</b>（见 {@code
   *       landedPriceExcludedFills}），绝不相加；
   *   <li>{@code freightByCurrency} = 逐币实付运费（键 = 该笔的运费币 = 买方支付币）；
   *   <li>{@code unusedCapacityMilli} = 每条路线的 {@code max(0, 每窗运力 × 最大轮数 − 已用)} 之和（量，不是钱）；
   *   <li>{@code unfilled*Counts} / {@code unfilled*Quantities}：买方/卖方两侧分开、逐原因档——"有货卖不掉"与
   *       "买不起"因此不会合成一个数。
   * </ul>
   *
   * @param landedPriceExcludedFills 因<b>单价币 ≠ 运费币</b>而未计入任何币到货价的成交笔数（0 = 本区本商品全是同币成交；
   *     它是"排除了多少"的具名读数，不是错误）
   */
  public record CommodityMatchReadout(
      long tradedMilli,
      Map<CurrencyId, Long> landedPriceByCurrency,
      long landedPriceExcludedFills,
      Map<CurrencyId, Long> freightByCurrency,
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
      Objects.requireNonNull(landedPriceByCurrency, "landedPriceByCurrency");
      Objects.requireNonNull(freightByCurrency, "freightByCurrency");
      Objects.requireNonNull(cheapestSellerUnitCostMilli, "cheapestSellerUnitCostMilli");
      Objects.requireNonNull(dearestSellerUnitCostMilli, "dearestSellerUnitCostMilli");
      if (landedPriceExcludedFills < 0L
          || sellerOutcomeCount < 0L
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
      landedPriceByCurrency = Collections.unmodifiableMap(copyMoney(landedPriceByCurrency));
      freightByCurrency = Collections.unmodifiableMap(copyMoney(freightByCurrency));
      unfilledBuyCounts = Collections.unmodifiableMap(copyCounts(unfilledBuyCounts));
      unfilledSellCounts = Collections.unmodifiableMap(copyCounts(unfilledSellCounts));
      unfilledBuyQuantities = Collections.unmodifiableMap(copyCounts(unfilledBuyQuantities));
      unfilledSellQuantities = Collections.unmodifiableMap(copyCounts(unfilledSellQuantities));
    }

    /**
     * ★★ P-T4：逐币金额表的**构造期防御性拷贝**（保序 + 不可变）。★ I7：用 {@code LinkedHashMap} + {@code
     * Collections.unmodifiableMap}，<b>不用</b> {@code Map.copyOf}（迭代序不是内容的纯函数）。
     */
    private static Map<CurrencyId, Long> copyMoney(Map<CurrencyId, Long> amounts) {
      Map<CurrencyId, Long> copy = new LinkedHashMap<>();
      if (amounts == null) {
        return copy;
      }
      for (Map.Entry<CurrencyId, Long> entry : amounts.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException("交易读数的逐币金额不得含 null 键/值或负额: " + entry);
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      return copy;
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
      "生理需要：粮用 ClassRow.cycleNaturalNeedMilli（本周期累计，逐日累加当户注入 naturalNeeds[grain]）；"
          + "累加器尚未累计（旧档/未结算）⇒ 退回最近结算日的 naturalNeeds 并把 naturalNeedWindow 标成 last-settled-day；"
          + "其它商品用 ClassRow.naturalNeeds（最近结算日当日）";
  private static final String CYCLE_NATURAL_NEED_PROVENANCE =
      "cycleNaturalNeedMilli = Σ_d 当户注入的 naturalNeeds[grain]（各结算日结算前那一份）；本周期第一天重置为当天那一份";
  private static final String MATCH_REPORT_PROVENANCE =
      "撮合结果来自进程内 MarketReport（不落盘、重启即失）；缺失时 match=null 且 unavailable.matchResults 具名";
  private static final String LAST_SETTLED_DAY_PROVENANCE =
      "lastSettledDay 由当前 state tick 派生（经济日结算与状态推进同步）；手搭状态未经结算时仍等于 tick，读的人自行核对";
  private static final String PRICE_MODE_PROVENANCE =
      "fixed = 固定报价（可回退模式）；adaptive = 按供需每轮调价（MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED，2026-10-07 起默认 true）";
  private static final String CROSS_REGION_PROVENANCE = "跨区结算暂设即时（M2.0 #4）：货款/运费在发运日结清、货在 ETA 后到";
}
