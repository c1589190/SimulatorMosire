package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketFreezeBook.commitFreezes;
import static io.mosire.simos.economy.time.MarketFreezeBook.releaseAllFreezes;
import static io.mosire.simos.economy.time.MarketGovernmentOrderBook.collectGovMandateFills;
import static io.mosire.simos.economy.time.MarketIndexBook.MarketIndexes;
import static io.mosire.simos.economy.time.MarketIndexBook.applyProcurementPriority;
import static io.mosire.simos.economy.time.MarketIndexBook.sortBuySlotsByOrdering;
import static io.mosire.simos.economy.time.MarketMatchSupport.arbitragePlanLostByClone;
import static io.mosire.simos.economy.time.MarketMatcher.logRoundSummary;
import static io.mosire.simos.economy.time.MarketMatcher.matchAll;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteAccumulator;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketMoneyBook.applyMoneyMarketBudgetTopUps;
import static io.mosire.simos.economy.time.MarketMoneyBook.applyMoneyMarketLoanRates;
import static io.mosire.simos.economy.time.MarketMoneyBook.moneyMarketInput;
import static io.mosire.simos.economy.time.MarketOrderPlanner.HexOrderPlan;
import static io.mosire.simos.economy.time.MarketOrderPlanner.HexPlan;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketOrderPlanner.ordersFor;
import static io.mosire.simos.economy.time.MarketPortBook.requirePortTaxZoneKeysAligned;
import static io.mosire.simos.economy.time.MarketPricer.AdaptivePrices;
import static io.mosire.simos.economy.time.MarketPricer.adaptPrices;
import static io.mosire.simos.economy.time.MarketPricer.priceMode;
import static io.mosire.simos.economy.time.MarketReadoutBook.collectUnfilled;
import static io.mosire.simos.economy.time.MarketReadoutBook.roundStatsOf;
import static io.mosire.simos.economy.time.MarketRosterBook.carrierPresent;
import static io.mosire.simos.economy.time.MarketRosterBook.logHouseholdRoster;
import static io.mosire.simos.economy.time.MarketRosterBook.logLifeReserves;
import static io.mosire.simos.economy.time.MarketRosterBook.planFor;
import static io.mosire.simos.economy.time.MarketSettlement.HaulServiceRound;
import static io.mosire.simos.economy.time.MarketSettlement.MarketOutcome;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;
import static io.mosire.simos.economy.time.MarketSettlement.PlannedOrders;
import static io.mosire.simos.economy.time.MarketShipmentBook.goodsBlockedByCapacity;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketItemRef;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketOrder;
import io.mosire.simos.economy.api.market.MarketOrderSide;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketRoundRunner {

  private MarketRoundRunner() {}

  /**
   * ★★ <b>开一次市</b>（由 {@code EconomySettlement.settleOneDayInto} 在触发日调用一次）。
   *
   * <p>★ 返回 {@link MarketOutcome}（= 报告 + 本轮结束时的价格表）是 L3 的读数原料与 M2.6 的**唯一改价出口**（见 {@link
   * #MARKET_ADAPTIVE_PRICING_ENABLED}）：本类保证"信息不聚合丢失"，不替 L3 落成读数组件。失败时抛异常 —— 一次推进整条回滚，不产生半轮市场。
   *
   * @param marketTables 价格表（键 = 格；缺格 = 该格没有市场）
   * @param round 本轮只读/可写会话视图（余额与冻结都在这里）
   * @param trigger 今天为什么开市（由 {@link #triggerFor} 判定；{@link MarketTrigger#NONE} 不该走到这里）
   * @param topology 区域拓扑（派生件；由组合根传入）
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology) {
    return clearOncePerCycle(
        marketTables, round, trigger, topology, EconomyParallelism.singleThreaded());
  }

  /**
   * ★★ <b>R2 并行入口</b>：① 按<b>市场区</b>并行构建参与者/买卖订单（P1.2 扫描热点）；② 按<b>市场区</b>并行执行区内撮合 —— worker
   * 只在<b>本区账户副本</b>上算出 {@code FillIntent}（不可变意向），协调器再按<b>拓扑区序</b>把每个区的意向经唯一写口 {@code
   * EconomySettlement.applyTransfer} 回放到真实会话（冻结写只发生在协调器阶段，M8）。
   *
   * <p>★★ <b>为什么 worker 要拿账户副本</b>：同一区域的多个主体共用账户表，撮合是"先扣后加、再判断"的可变计算；直接让多个 worker 写 {@code
   * MarketRound} 的八张共享表是数据竞争。⇒ 每个区在 worker 内建一份<b>本区参与者的账户副本</b>（外层表复制、内层表只读换新）， 用与串行路径
   * <b>逐字同一条</b> {@code matchGroup/pairUp/executeTrade} 算完本区的成交序列；协调器按区序回放这些成交（同一套算式、同一套写口），
   * 因此最终账户/冻结/读数与串行路径逐值相同，且线程数不改变任何判定。
   *
   * <p>★★ <b>撮合顺序</b>：回放序 = {@code topology.regions()} 的拓扑区序 × 区内 {@code orderedCommodities} 商品序 ×
   * worker 的 canonical 槽位序（{@code ctx.buys}/{@code ctx.sells} 的全局插入序）。三者都是内容的纯函数，与 worker 完成顺序、线程
   * id 无关；因此 1/4/8 线程产出的成交序列、账户终态与报告逐值相同。★ 跨区撮合仍是协调器单线程（跨区一笔同时触碰两个区的路线/在途/运费， 属 R3 的 P3
   * 跨区协调）；本轮只把区内并行做实，并用 P1.2 索引去掉它的全表扫描。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism) {
    // ★ M-A1：没有运力池的世界（没有"选了跑商的家户"）⇒ 跨格货走不动；同格成交逐值不变。
    // ★ M-C：没有纯商号集合（夹具 / 纯状态读者）⇒ 谁都不豁免运费 ⇒ 逐值退回 M-A2（缺省语义中性，I-C2）。
    // ★ W2：没有持久区表（夹具 / 纯状态读者）⇒ α 走出厂缺省 50‰（同一族的缺省语义中性）。
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        MerchantCapacityPool.empty(),
        Set.of(),
        Map.of());
  }

  /**
   * ★★ <b>M-A1 承运入口</b>：每条跨格 lane 的运力由 {@link MerchantCapacityPool} 按<b>发货格</b>的逐 hex 运力池
   * 现算（提供方市场议价权序），买方 CARRIER_FEE 直接付给<b>提供运力的家户</b>。池空 ⇒ 该格没有"选了跑商的家户" ⇒ 跨格路线根本不建（具名 {@code
   * LOGISTICS_CAPACITY}）；<b>不再有</b>"没有承运人也照发货、运费记未收"的旧兜底。
   *
   * @param pureMerchantHouseholds ★★ <b>M-C：本轮的纯商号家户集合</b>（H-2；由 {@code EconomySettlement} 从 同一份
   *     {@code classMemberships × classPositions} 现算，判据的唯一拼写点是 {@code MerchantIdentity}）。
   *     <b>自运自货</b>（承运方与货主都是纯商号）⇒ 免运费（H-A/H-G）；空集 ⇒ 谁都不豁免 ⇒ 逐值退回 M-A2（缺省中性）。
   * @param marketZones ★★ <b>W2：本轮的持久市场区表</b>（{@code MarketZoneId → MarketZone}）—— 自适应定价逐区读 {@link
   *     MarketZone#priceAlphaPerMille()}（步长 α）。空表 = 派生形态 / 夹具 ⇒ α 取出厂缺省 {@link
   *     MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}（缺省语义中性）。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds) {
    // ★ W2：没有持久市场区表（夹具 / 纯状态读者）⇒ α 一律走出厂缺省（缺省语义中性，与 marketTables 为空那些退化入口同一族）。
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        Map.of(),
        SettlementOrderBook.empty(round.day));
  }

  /** ★★ <b>W2：带持久市场区表的承运入口</b>（生产路径走这一条）—— 与上一个重载逐字同源，只多一份区表供自适应定价逐区读步长 α。 */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones) {
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        SettlementOrderBook.empty(round.day));
  }

  /**
   * ★★★ <b>W4：带结算序快照的入口</b>（生产路径走这一条）—— 与上一个重载逐字同源，只多一份<b>开盘前快照</b>（S-4/N-1）。
   *
   * <p>★ <b>快照怎么用</b>：① 买槽按家户名次（净值降序）排序 ⇒ "谁先拿到稀缺货"；② 区的遍历序按上周期名义成交额降序 ⇒ 谁先被撮合、谁先消耗本轮的共享运力池；③ 买侧"需求
   * &gt; 供给"时按这个序<b>依次吃满</b>（不再按权重比例人人分一口）。
   *
   * @param ordering W4 开盘前结算序快照（{@link SettlementOrderBook#snapshot} 在<b>本入口之前</b>构造一次）⇒
   *     撮合期间不回读任何当轮量（禁自指）
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones,
      SettlementOrderBook ordering) {
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        ordering,
        // 旧入口没有标量土地输入；LAND assetShares 已无条件非法 ⇒ 空表 = 逐值退回旧世界。
        Map.of());
  }

  /**
   * ★★★ <b>D-032 RA1b2c1/RA1b2c2：带标量 LAND 纯库存财富 + 产权工作态的入口</b>。
   *
   * <p>★ 商品买槽与土地买单共用同一个 {@link PureInventoryWealthBook.Snapshot}；LAND 只从 {@code landHoldings} 按
   * {@code holding.hex} 所属市场区地价估值，不再读 {@code assetShares}（LAND 持久份额已无条件非法）。 没有标量土地的世界传空表 ⇒ 与旧口径同值。
   *
   * <p>★ 旧重载不传 {@code LandArableSupply}，按当前持有逐 hex 合计派生同量供应表（市场转移只在本 hex 内搬数量，总量不增不减）。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones,
      SettlementOrderBook ordering,
      Map<LandHoldingKey, LandHolding> landHoldings) {
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        ordering,
        landHoldings,
        arableSupplyOf(landHoldings));
  }

  /**
   * ★★★ <b>D-032 RA1b2c2：带标量 LAND 持有 + 可耕总量权威的入口（生产路径走这一条）。</b>
   *
   * <p>★ 土地成交的唯一写口 {@link MarketTradeExecutor#executeLandTrade} 在 {@link
   * io.mosire.simos.economy.land.LandLedgerState} 上按 {@code LandTransfer} 转移；每 hex 总量守恒由 {@link
   * io.mosire.simos.economy.land.LandBook#apply} 唯一校验。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones,
      SettlementOrderBook ordering,
      Map<LandHoldingKey, LandHolding> landHoldings,
      LandArableSupply landArableSupply) {
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        ordering,
        landHoldings,
        landArableSupply,
        () -> {});
  }

  /**
   * ★★★ <b>D-032 C7b1：订单构建完成、真正撮合之前的只读回调入口</b>。
   *
   * <p>{@code beforeMatch} 只在 {@code ctx.buys}/{@code ctx.sells} 已由既有唯一 planner 建好、显式意向已追加之后调用；
   * 它可读取同一轮最终订单并准备瞬态运输计划。回调不改订单/预算/冻结，也不改变 matcher 判定；异常 ⇒ 整轮 fail-closed。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones,
      SettlementOrderBook ordering,
      Map<LandHoldingKey, LandHolding> landHoldings,
      LandArableSupply landArableSupply,
      Runnable beforeMatch) {
    Objects.requireNonNull(beforeMatch, "beforeMatch");
    return clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        ordering,
        landHoldings,
        landArableSupply,
        (MatchContext ignored) -> beforeMatch.run());
  }

  /**
   * ★★★ <b>D-032 C7b2：带撮合前上下文回调的入口</b> —— 回调可把同一份 C7b1 计划与 C6 活动运输池装入 {@link
   * MatchContext}；仍发生在订单构建完成之后、任何冻结/撮合之前。
   */
  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones,
      SettlementOrderBook ordering,
      Map<LandHoldingKey, LandHolding> landHoldings,
      LandArableSupply landArableSupply,
      Consumer<MatchContext> beforeMatch) {
    Objects.requireNonNull(beforeMatch, "beforeMatch");
    Objects.requireNonNull(marketTables, "marketTables");
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(parallelism, "parallelism");
    Objects.requireNonNull(carrierPool, "carrierPool");
    Objects.requireNonNull(marketZones, "marketZones（没有持久区请给空表）");
    Objects.requireNonNull(ordering, "ordering（W4 结算序快照；没有排序面给 SettlementOrderBook.empty(day)）");
    Objects.requireNonNull(landHoldings, "landHoldings（没有标量土地请给空表）");
    Objects.requireNonNull(landArableSupply, "landArableSupply（没有标量土地请给空表）");
    if (MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_ROUND_START",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "trigger",
                  trigger,
                  "marketTables",
                  marketTables.size(),
                  "regions",
                  topology.regions().size(),
                  "capacityHouseholds",
                  carrierPool.householdCount(),
                  "capacityHexes",
                  carrierPool.hexCount(),
                  "rows",
                  round.householdEconomies.size(),
                  "deferredPayments",
                  round.deferredPaymentsEnabled()));
    }
    // ★★ E（2026-10-09）：本轮"钱的价"（世界行情 + 当地实际流通的币种）只装配一次 —— 它依赖本轮**全部**家户的
    //   货币账户，故必须由协调器算好、与各区 worker 副本共用同一份实例（各自现算会在"认不认得出某种钱"上漂开）。
    Map<String, List<HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies);
    CurrencyValuation currencyValuation =
        CurrencyValuation.of(
            round.fx(),
            CurrencyValuation.circulationByRegion(topology, rowsByHex, round.householdMoney()),
            // ★ R2：口岸实际管制力作为家户对外币估值的减项（缺省 none ⇒ 逐值退回改前行为）。
            round.portEnforcement());
    MatchContext ctx =
        new MatchContext(
            round,
            marketTables,
            topology,
            carrierPool,
            round.regulation(),
            currencyValuation,
            ordering);
    // ★★ M-C：本轮的纯商号集合（免运费判据的范围）—— 从调用方给的集合原样带入（装配点在 EconomySettlement）。
    ctx.pureMerchantHouseholds = pureMerchantHouseholds;
    // ★★ W2：本轮的持久市场区表（自适应定价逐区读 α）—— 同上，由入口参数带入，worker 副本照抄。
    ctx.marketZones = marketZones;
    // ★★★ RA1b2c2：本轮标量 LAND 工作态 —— 从会话 landHoldings 构造不可变账本；土地成交逐笔 transfer。
    //   ★ 关键：即使本轮不开市/无市场表，round.landHoldings 也要带出当刻持有，轮末写回才是恒等复制而不是清空。
    ctx.landLedger = LandLedgerState.from(landArableSupply, landHoldings);
    round.setLandHoldings(ctx.landLedger.holdingsByKey());
    if (MARKET.isDebugEnabled()) {
      // ★★ E（§一.9：DEBUG 写"为什么"）：本轮"钱的价"认得出哪些币 —— "某笔异币为什么没成交"的第一现场。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_CURRENCY_VALUATION",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day,
                  "quotedCurrencies",
                  currencyValuation.quotedCurrencies().size(),
                  "circulation",
                  currencyValuation.circulationSummary()));
    }
    if (marketTables.isEmpty() || trigger == MarketTrigger.NONE) {
      return new MarketOutcome(
          MarketReport.empty(round.day, trigger, carrierPresent(ctx)), marketTables, Map.of());
    }
    // ★★ P-T1b：税表的区键口径核对（与口岸闸同一条 fail-closed 契约，见 {@link #requirePortZoneKeysAligned}）——
    //   税表非空却一个区键都命中不了本轮拓扑的区 id ⇒ 三层税会**静默一分不收**，那正是本仓最贵的一类故障。
    if (round.portTax().isActive()) {
      requirePortTaxZoneKeysAligned(ctx);
    }
    // ★★ P-T5b：逐户"本轮用哪种币付"（F-1 的"最强持有币"）—— **必须在并行下单段之前单线程算好**
    //   （worker 只读它；在并行段里现算 = 共享可变缓存 = 数据竞争），且必须用**上面那一份** currencyValuation
    //   （禁两套估值漂开：订单侧的折算与撮合的折算必须是同一个实例）。
    //   ★ 它放在"本轮不开市"的早退之后：不开市的日子不发"这一轮谁用外币付"的 INFO，也不白算一遍。
    MarketPayChoice payChoice =
        MarketPayChoice.of(round, marketTables, topology, rowsByHex, currencyValuation, true);
    // ★★★ R2（2026-10-11）：本轮的"谁能买 / 谁只按授权下单"全局名册（INFO，每轮一条；空名册 ⇒ 一条不发）。
    //   位置 = "本轮真的开市"的早退之后、下单之前：与 {@code MarketPayChoice} 同一条"开市才发"的纪律。
    logHouseholdRoster(round);
    // ★★★ P3b：商品买槽与土地买单共用的**纯库存财富快照** —— 盘前（M2 后、任何商品成交/冻结之前）只取一次。
    //   ① 商品买槽 rank = 本快照的 wealth 降序 → HouseholdId 升序（见 MarketBuyerOrdering）；
    //   ② 土地买单 rank 读**同一个 Snapshot 实例**（ctx.landBuyerWealth），不得另算第二份。
    //   ★ 禁自指：位置必须在 planning/撮合之前；本轮商品成交/冻结不得改变本轮买房/买地的先后。
    PureInventoryWealthBook.Snapshot pureWealth =
        PureInventoryWealthBook.snapshot(
            round.day,
            round.householdEconomies,
            round.householdGoods,
            round.householdMoney,
            landHoldings,
            marketTables,
            topology,
            currencyValuation);
    ctx.landBuyerWealth = pureWealth;
    ctx.buyerOrdering = MarketBuyerOrdering.of(pureWealth);

    // ── 1. 逐格建计划与订单；参与表按 actor 去重（订单生成与撮合的唯一来源）──────────────────────
    //   ★★ R2：按市场区并行构建，再按 hex (q,r) 序拼回 —— 与原串行序逐字相同（见方法注释）。
    //   ★★ C4：rowsByHex 只在这里建一次（旧 R2 让每个分区 worker 各自重建一次），只读传给 worker。
    //      ★ E 起它更靠前一步建（"钱的价"的当地流通集合要用它），本段只沿用同一个变量。
    //   ★★ 并行安全：worker 读的必须是**普通只读表**，不能是 AccountSession 的活视图（owner 守卫在 worker 线程
    //     第一次 get 就抛）⇒ 协调器先把八张账户表浅拷成 planningRound，worker 只读它。
    MarketRound planningRound = readOnlyPlanningRound(round);
    // ★★ 2026-10-08 防复发守卫：**克隆轮必须与母轮携带同一份套利决定**。
    //   下一段真正下单用的是 planningRound（不是 round）⇒ 克隆一旦丢字段，套利就会"计划满格、订单为零"且毫无报错
    //   （2026-10-08 实测踩到：360 tick 与基线逐值相同、ARBITRAGE_ROUND 83 户而 ARBITRAGE_BUY_ORDER = 0 条）。
    //   ⇒ 这是**契约/一致性故障**（AGENTS §一.9：不降级），具名 ERROR + fail-closed；正常路径上恒不触发
    //     （{@link MarketRound#copyForWorker} 是克隆的唯一拼写点，已逐字段带过）。
    if (!planningRound.arbitrage().instructions().equals(round.arbitrage().instructions())) {
      throw arbitragePlanLostByClone(round.arbitrage().size(), planningRound.arbitrage().size());
    }
    // ★★ R1 防复发守卫（与上面同一条坑）：**计划轮必须携带同一份政府市场授权计划**。
    //   它丢了 ⇒ 国库户在下单的那一份轮里不再是"只按授权下单"的家户 ⇒ 当场退回自动清仓（Z7b 的根因），
    //   而且没有任何报错。契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）。
    if (!planningRound.govMandates().equals(round.govMandates())) {
      EventLog.channel(MARKET)
          .error(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_CONTRACT",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "reason",
                  "authorization-plan-lost-by-planning-clone",
                  "expectedHouseholds",
                  round.govMandates().authorizationOnlyHouseholds(),
                  "actualHouseholds",
                  planningRound.govMandates().authorizationOnlyHouseholds()));
      throw new IllegalStateException("政府市场授权计划被计划轮克隆丢掉（国库户会退回自动下单，契约故障）: day=" + round.day);
    }
    // ★★★ A3（2026-10-10）：此处原有"计划轮必须携带同一份跑商家户集合"的防复发守卫（§16.4 ①）—— **已随该字段删除**：
    //   它守的是 {@code necessaryInputsOf} 里"给跑商家户下夹一趟工具"那一项，而那一项已撤回（预留由产业声明投入
    //   的标准循环覆盖，I-H6）⇒ 没有可丢的字段、也没有需要守的静默失效。
    if (!round.arbitrage().isEmpty() && MARKET.isDebugEnabled()) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_ARBITRAGE_PLAN_ATTACHED",
                  EconomyLogSource.ECONOMY_ARBITRAGE,
                  "day",
                  round.day,
                  "households",
                  round.arbitrage().size(),
                  "planningRoundHouseholds",
                  planningRound.arbitrage().size()));
    }
    // ★★ W1（价表每区一张）：逐格迭代集合 = **拓扑的成员格**（持久形态 = 区成员格；派生形态 = 价表成员格）。
    //   改动前它是 marketTables.keySet()（有价表行的格）—— 合法状态下两者逐格重合，因此订单集合逐值不变；
    //   差别只在"区成员格在改动前没有价表行"这种被旧形状默许的形态：那时它不产生订单。
    //   ★★ W1d：那个形态 W1 起可表达（区有、表缺）⇒ 本段逐区显式判空跳过，与"区内任何格取本区价表"不再冲突：
    //      缺表 = 该区不交易（下面 regionId 循环里的具名跳过）。
    TreeMap<String, List<HexCoord>> hexesByRegion = new TreeMap<>();
    for (MarketRegion region : topology.regions()) {
      List<HexCoord> members = new ArrayList<>(region.members());
      members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      hexesByRegion.put(region.node().nodeId(), members);
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.LOCAL_MARKET, hexesByRegion.keySet(), parallelism.partitionCount());
    List<List<HexOrderPlan>> plansByPartition =
        SettlementExecutor.execute(
            plan,
            partition -> {
              List<HexOrderPlan> planned = new ArrayList<>();
              for (String regionId : partition.canonicalKeys()) {
                // ★★ W1d：区没有价表条目 ⇒ 这个区**不交易任何商品**（设计书 §4.2 N-4；与"表里没有这一行"
                //   是同一条语义）⇒ 该区一个订单都不生成，直接跳过（不抛、不 NPE、不造默认价）。
                MarketTable market = marketTables.get(new MarketZoneId(regionId));
                if (market == null) {
                  continue;
                }
                for (HexCoord hex : hexesByRegion.get(regionId)) {
                  List<HouseholdId> keys =
                      rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
                  HexPlan hexPlan = planFor(planningRound, hex, keys);
                  logLifeReserves(planningRound, hex, hexPlan);
                  if (hexPlan.participants.isEmpty()) {
                    continue;
                  }
                  Map<ActorRef, Participant> byActor = new LinkedHashMap<>();
                  for (Participant participant : hexPlan.participants) {
                    byActor.put(participant.actor(), participant);
                  }
                  List<BuySlot> buys = new ArrayList<>();
                  List<SellSlot> sells = new ArrayList<>();
                  MarketRegion region = topology.regionOf(hex);
                  for (Map.Entry<CommodityId, Long> priced : market.prices().entrySet()) {
                    CommodityId commodity = priced.getKey();
                    // ★★ 2026-10-09：有这一行就是"已定价"—— 值为 0 = 明确免费交易，不能再按 <=0 当缺价跳过。
                    //    "从未定价"的商品根本不在 market.prices() 里，这个循环天然不会碰它。
                    //    ★★ 2026-10-10 P-T1c：区级参考价覆盖已删 ⇒ 不再查"这一格属不属于调控锚区"。
                    PlannedOrders orders =
                        ordersFor(planningRound, hexPlan, hex, market, commodity, payChoice);
                    for (BuyOrder order : orders.buys()) {
                      Participant buyer = byActor.get(order.requester());
                      if (buyer == null) {
                        throw new IllegalStateException(
                            "买订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.requester());
                      }
                      // ★★ 3c：槽位不再从本格计价币取币 —— 支付币由**订单**给（BuySlot 构造期读 order.payWith()，
                      //   "缺省 = 本格计价币"落在订单生成那一侧的 orderCurrencyFor）。
                      //   ★★ P-T5b：槽位在这里把"本格计价币 ↔ 支付币"的价**冻结一次**（payValueMicro /
                      //     limitInPayCurrency）—— 限价口径比较、冻结额、运费腿此后都读这两个冻结值，
                      //     不在撮合中途反复折算（禁两次折算），worker 副本逐值照抄。
                      buys.add(new BuySlot(order, buyer, hex, region, market, payChoice));
                    }
                    for (SellOrder order : orders.sells()) {
                      Participant seller = byActor.get(order.supplier());
                      if (seller == null) {
                        throw new IllegalStateException(
                            "卖订单的主体不在本轮参与者里（订单生成与撮合漂开了）: " + order.supplier());
                      }
                      sells.add(new SellSlot(order, seller, hex, market, region, planningRound));
                    }
                  }
                  planned.add(
                      new HexOrderPlan(hex, List.copyOf(hexPlan.participants), buys, sells));
                }
              }
              return planned;
            },
            parallelism.poolOrNull());
    List<HexOrderPlan> orderedPlans = new ArrayList<>();
    for (List<HexOrderPlan> partitionPlans : plansByPartition) {
      orderedPlans.addAll(partitionPlans);
    }
    // ★ 拼回序 = 原先的 hex (q,r) 升序（分区只影响谁先算完，不影响拼回后的顺序）。
    orderedPlans.sort(
        Comparator.comparingInt((HexOrderPlan entry) -> entry.hex().q())
            .thenComparingInt(entry -> entry.hex().r()));
    for (HexOrderPlan ordered : orderedPlans) {
      for (Participant participant : ordered.participants()) {
        ctx.participants.put(participant.actor(), participant);
        ctx.participantHex.put(participant.actor(), ordered.hex());
      }
      ctx.buys.addAll(ordered.buys());
      ctx.sells.addAll(ordered.sells());
    }
    // ★★★ P3c3：M3 显式意向**替换同一 actor 的 planner 自动商品订单**（不得追加成重复订单/重复冻结）。
    //   非 intent actor（政府/商号/未接计划的户）的自动订单保留；缺方向的订单就是没有订单（不恢复自动腿）。
    replaceAutoCommodityOrdersForIntentActors(ctx, planningRound);
    // ★★★ P3.0：M3 显式商品意向（prepareMarketRound(ctx, intents) 转换出的 BuyOrder/SellOrder）
    //   仍进入同一份 ctx.buys/ctx.sells 并交给同一个 clearOncePerCycle。
    appendInjectedCommodityOrders(ctx, planningRound, payChoice);
    // ★★★ D-032 C7b1：此时当轮最终商品订单（自动 + 显式注入）已全部在 ctx.buys/ctx.sells 中，
    //   尚未做任何撮合/冻结/成交。把只读订单快照挂回同一轮，并调用执行方注入的 beforeMatch 回调；
    //   失败上抛 ⇒ 整次 advance 丢弃，不产生半轮市场。
    List<BuyOrder> planningBuys = new ArrayList<>(ctx.buys.size());
    for (BuySlot buy : ctx.buys) {
      planningBuys.add(buy.order);
    }
    List<SellOrder> planningSells = new ArrayList<>(ctx.sells.size());
    for (SellSlot sell : ctx.sells) {
      planningSells.add(sell.order);
    }
    round.capturePlanningOrders(planningBuys, planningSells);
    beforeMatch.accept(ctx);
    // ★★ W4（S-4 / N-1）：**买槽按结算序重排** —— 家户序 = 资产 − 负债净值降序（同值按稳定 id 的 canonical 序）。
    //   ★ 位置必须在 orderIndex 赋值与 MarketIndexes.build **之前**：槽位的全局下标、各 (区,商品) 桶的相对序、
    //     冻结的写序、撮合的配给序全都读这份表 ⇒ 只在这里排一次，后面不许再排第二遍（两处排序必然漂开）。
    //   ★ 卖槽**不按净值排**：卖侧的服务序是既有的"到货成本升序 → costTieBreak(canonical)"（S3/P-T1d 的机制，
    //     本批不动它）；插入序只影响同成本同 key 的残余并列，且那份并列已由 ProducerCostBook.canonicalKey 收敛。
    //   ★ 禁自指：名次全部来自 ordering（开盘前快照），排序输入里没有任何当轮成交/当轮余额。
    sortBuySlotsByOrdering(ctx);
    // ★ 槽位的全局下标 = 它们在 ctx.buys / ctx.sells 里的位置；worker 产出的 FillIntent 用它定位回放目标。
    for (int i = 0; i < ctx.buys.size(); i++) {
      ctx.buys.get(i).orderIndex = i;
    }
    for (int i = 0; i < ctx.sells.size(); i++) {
      ctx.sells.get(i).orderIndex = i;
    }

    // ── 1b. P1.2：每轮只建一次的只读索引（区内/跨区/邻接/商品序）—— worker 只读，不持有可写状态 ─────
    MarketIndexes indexes = MarketIndexes.build(ctx);

    // ── 1c. ★★ P-T1d：政府采购优先级（§2.6 第 5 步：置顶在"商户决策步"之后、"撮合"之前）──────────
    //   ★ 只写槽位的两个顺序字段（名次 + 是否真被推进）；不碰订单、不碰价格、不碰任何量算式。
    //   ★ 缺省（没有政府要求管控市场）⇒ 本调用一行不跑 ⇒ 名次保持 PROCUREMENT_RANK_ABSENT ⇒ 逐值不变（I-C2）。
    //   ★ 位置必须在 MarketIndexes 之后（簿 = 索引的 (区 × 商品) 桶）且在撮合之前（顺序是撮合的输入）。
    applyProcurementPriority(ctx, indexes);

    // ── 1d. ★★★ W5.5（2026-10-11，S-12）：**货币腿 —— 挂钱供给 → 借钱**（在冻结与撮合**之前**）─────
    //   ★★ 用户原话（次序就是判据）：「供给这一轮不是在需求挂单之前吗？应当是，**在挂商品的时候最后挂钱，
    //     买东西的时候先借钱**」「如果家户的库存货币无法满足需求，**先借钱再买货**，然后后面不就有钱了？」
    //   ⇒ 本轮的次序 = ① 挂商品（上面计划段：买卖槽 + 结算序）→ ② 挂钱供给 + ③ 借钱（本段）→
    //     ④ 买商品（下面的 commitFreezes + 区内/跨区撮合 + 未付价款记债）。
    //   ★ 位置为什么必须在 commitFreezes **之前**：买方的"冻结/可付现金"读的是**当刻**余额
    //     （spendableMoneyOf / payableMoneyOf）⇒ 借到的钱必须先真的进到借方账户，现金腿才可能通过；
    //     放在撮合之后等于"借了钱却不能用它买货"。
    //   ★ 它是**协调器单线程**段（在 worker 账户副本构造之前）⇒ 借贷写的是唯一的活账户表，worker 看到的是借完之后的余额。
    //   ★ 没有债务工作副本（{@code deferredPaymentsEnabled()} 为假）⇒ 货币腿一步都不做（借出的钱没有债权凭证）。
    //   ★ 钱腿**只搬钱**（不碰任何商品、不建 lane、不动运力/税）—— 货一律仍走下面的正规撮合。
    MoneyMarketSettlement.Round moneyRound =
        MoneyMarketSettlement.settle(round, moneyMarketInput(ctx));
    MoneyMarketSettlement.logRound(round.day, moneyRound);
    //   ★★ 借到的钱必须能花：订单预算是**下单当刻**的快照（在下单之后才借的钱不在里面）⇒ 把借方那一笔补进
    //     它名下（同币）买槽的预算余额，否则"先借钱再买货"在现金腿上依然不可用（整条腿空转）。
    applyMoneyMarketBudgetTopUps(ctx, moneyRound);

    // ── 2. 冻结（M1.2 的写者接上）：挂单即占用；成交/发运/轮末释放 ────────────────────────
    commitFreezes(ctx);
    try {
      // ── 3. ★★★ P3d：**唯一统一撮合入口**（商品区内 → 商品跨区 → 土地私有分支，全部在 matchAll 内部）──
      //   ★ runner 不再保留“商品 matcher 调完再另调土地 matcher”的两段结构：
      //     商品与土地是同一本 MarketRound 订单簿里的订单变体，同一轮冻结生命周期、同一写口回放。
      //   ★ 土地私有分支（MarketMatcher.matchLand）仍在商品区内/跨区撮合之后、releaseAllFreezes
      //     之前运行 ⇒ 土地买单只能使用 spendableMoneyOf（余额 − 商品冻结）的剩余可花额，同一买方同一币种不可能双付。
      matchAll(ctx, parallelism, indexes);
      // ── 4a0. ★★ M-A1：逐格运力池与本轮分配汇总（INFO = 每格运力池与分配汇总；§一.9）────────────
      //   ★ 位置：区内 + 跨区撮合都做完之后（此时"谁用了多少运力"才是本轮的事实）。
      ctx.carrierPool.logRoundSummary(round.day);
      // ── 4a0b. ★★ M-A2：本轮运力需求与供需缺口汇总（K-A/K-B/K-5；INFO/DEBUG/TRACE，§一.9）──────
      //   ★ 需求簿只累加读数：不写状态、不铸转移、不改任何判据（守恒与铁律 2 不受影响）；"被运力截断的货物量"
      //     读买槽的既有 V-20 读数（那部分不成交、不成债、不计价 —— K-4/Q-27）。
      ctx.capacityDemands.logRoundSummary(round.day, ctx.carrierPool, goodsBlockedByCapacity(ctx));
      // ── 4a0c. ★★★ A3（2026-10-10）：本轮**商号平行利润读数**（{@code MerchantProfitBook}）**已整族退役**
      //   （T-H3：收益走标准企业利润 {@code EnterpriseProfitBook}）⇒ 这里不再有 4a0c 这一步。
      //   ★ 组织侧的真实利润在周期关账时由 {@code EnterpriseProfitBook.collect} 从同一批
      //     {@code ProductionLedger} 腿汇总（收入 = MARKET_TRADE 钱腿按卖方组织归集）—— 服务成交的钱腿
      //     已取 {@code MARKET_TRADE}（见 executeTrade 的运费腿），所以跑商收益**结构上**进得了那一本账。
      // ── 4a0d. ★★★ A5（2026-10-10）：本轮的"运输服务成交"汇总行**从这里撤走**（不是删读数）─────────
      //   ★ 为什么撤：当日可对账的 INFO 必须是**一行三量**（产出 / 交付 / 作废）—— 产出与作废发生在 tick 面的
      //     生产阶段（{@code EconomySettlement}），市场轮看不到它们；而市场轮看得到的交付量在这里，
      //     旧实现又把它绑在 {@code trades > 0} 上（"有交付但一条钱腿都没铸"的日子整行不刷 ⇒ A4 实测 B 世界
      //     漏报 10 天 / 33.4%）。⇒ 三个量各自的最全来源都在 tick 面，对账行改由
      //     {@code EconomySettlement} 在**每个开市日**发一次（事件名不变：{@code HAUL_SERVICE_SETTLED}），
      //     交付量经 {@link #HaulServiceRound} 随 {@link MarketOutcome} 交出（见下面 6 段末的装配）。
      //   ★ 读数本身一个都没丢：{@code ctx.haulServiceSoldMilli} / {@code haulServiceTrades} /
      //     {@code carrierPool} 的逐户实收（含逐币总额）全部原样带出。
      // ── 4a0e. ★★ A2（§一.9 级别规则）：交付点取不到服务货 = 跨切片一致性故障 ⇒ **ERROR 不降级**。──────
      //   ★ 它必须为 0（池的运力预算就是该户当刻的 haul 可用量，交付紧跟分配之后）；非 0 说明账被别处改了
      //     或池的预算不是从货来的 ⇒ 当场可见，绝不静默少扣（逐条 ERROR 在 deliverHaulService 里发）。
      //   ★★★ RC8a（2026-10-20）：不得只打 ERROR 后让 advance 以 status=OK 吞掉契约故障 ——
      //     先保留轮级 ERROR，再抛具名 IllegalStateException 让 advance fail-closed（本市场轮整体拒绝）。
      if (ctx.haulServiceDeliveryFaults > 0) {
        EventLog.channel(MARKET)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERY_FAULTS",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "faults",
                    ctx.haulServiceDeliveryFaults,
                    "reason",
                    "contract-fault-service-goods-not-on-hand-at-delivery"));
        throw new IllegalStateException(
            "HAUL_SERVICE_DELIVERY_FAULT_CONTRACT: day="
                + round.day
                + " faults="
                + ctx.haulServiceDeliveryFaults
                + " reason=contract-fault-service-goods-not-on-hand-at-delivery");
      }
      // ── 4a. ★★ P-T1a：口岸节流的轮级汇总（INFO：发生了什么 + 具名计数；逐区对在 DEBUG/TRACE）──────
      //   ★ 只报"被拦下多少"这一件事（计数口径 = 源区→目的区 的<b>区对</b>）：被拦下的量不进候选集、不落状态、不进账本（§11），所以它是日志事实，不是账。
      if (ctx.portGatedPairs > 0 && MARKET.isInfoEnabled()) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "MARKET_PORT_THROTTLED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "gatedPairs",
                    ctx.portGatedPairs,
                    "blockedTransitMilli",
                    ctx.portBlockedMilli,
                    "reason",
                    "two-sided-port-gate-e-source-times-e-destination"));
      }
      // ── 4a1. ★★ P-T1e：币种挂单闸的轮级汇总（INFO：发生了什么 + 具名计数；逐笔在 DEBUG）────────────
      //   ★ 与上一条并列：只报"被挡下多少"这一件事。被挡下的候选不进候选集、不落状态、不进账本（§14.3），所以它是日志事实，不是账。
      if (ctx.currencyGateBlocked > 0 && MARKET.isInfoEnabled()) {
        EventLog.channel(MARKET)
            .info(
                LogEvent.of(
                    "MARKET_CURRENCY_GATE_BLOCKED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day,
                    "blockedAttempts",
                    ctx.currencyGateBlocked,
                    "blockedQuantityMilli",
                    ctx.currencyGateBlockedMilli,
                    "reason",
                    "currency-order-kind-blocked-by-two-sided-port-rules"));
      }
    } finally {
      releaseAllFreezes(ctx);
    }
    // ★★ P2f（§一.9 INFO = 本轮发生了什么）：土地订单/成交/赊购/下一轮地价的轮级汇总（空轮一条不发）。
    logRoundSummary(ctx);
    // ★★★ RA1b2c2：把本轮 LAND 产权工作态写回 MarketRound 工作副本（轮末由 SettlementDayMarketPipeline 写会话）。
    round.setLandHoldings(ctx.landLedger.holdingsByKey());

    // ── 4b. ★★★ W2-fix4（2026-10-11）：这里原有"市场信用撮合"（{@code creditRound}：借钱买货 → 借实物）──
    //   **整段退役**，理由见本文件"W2 债务式清算"那个段落头与 W2-fix4 账本：那两条腿绕过运费/运力池/税，
    //   会在现金撮合已明确拒绝的车道上把货搬走 ⇒ 卖方余量被一次搬空、市场从 day 8 起再无供给。
    //   ⇒ 本位置不再有任何信用撮合；未付价款记债发生在 {@code executeTrade} 里（唯一通道）。

    // ── 4c. ★★ A2a + P-T5：外汇撮合（与商品撮合同一处落账口）──────────────────────────────
    //   ★ 位置：商品撮合 + 信用之后、未成交归因之前 —— 家户能花的钱是"商品买卖之后"的余额（= 用户"挂完生产需求后"的
    //     时点，F-5）；FX 的两条腿同样会改变余额，必须先落完再判商品的未成交档（否则那份归因读的是"还没花出去"的旧数）。
    //   ★★ P-T5 起本段<b>不再</b>要求"有官方汇率"：政府窗口照旧进簿，家户侧另有<b>民间簿</b>（自报价，缺省中性见
    //     {@code FxSettlement#match} 的注）。
    FxRoundResult fx = FxSettlement.match(round, marketTables, topology);

    // ── 5. 未成交原因（不聚合丢失；买卖两侧分开）────────────────────────────────────────
    collectUnfilled(ctx, indexes);

    List<MarketReport.RouteUsage> routeUsages = new ArrayList<>();
    for (RouteAccumulator acc : ctx.routes.values()) {
      routeUsages.add(
          new MarketReport.RouteUsage(
              acc.from,
              acc.to,
              acc.commodity,
              acc.capacityPerWindow,
              acc.costPerUnit,
              acc.used,
              acc.demandMilli,
              acc.supplyMilli,
              acc.bottleneck));
    }
    // ── 6. M2.6 可选自适应：只看**本轮计划订单**（有预算且限价内的需求 vs 可出售供给），
    //   在撮合之后改下一轮的参考价。★ 开关判据收在 adaptPrices 内部、这里**无条件调用** ——
    //   ★ 开关判据收在 adaptPrices 内部、这里**无条件调用**；即使将来把常量改回 false，也能保证方法在字节码里存在。
    AdaptivePrices adapted = adaptPrices(marketTables, ctx);
    // ★★★ W5.5（2026-10-11，S-12⑤）：**货币腿的利率写回**（本轮算出的清算利率 ⇒ 下一轮生效，与价表同一条纪律）。
    //   ★ 位置：价表已自适应改完、即将作为交出物离开本类 —— 利率与商品价是**同一张表**上的两个字段 ⇒ 只有一处写回口、
    //     一次 {@code with*}；撮合已全部结束，本轮已签的合同仍带**本轮**利率（轮内价格不变）。
    Map<MarketZoneId, MarketTable> tablesWithLoanRates =
        applyMoneyMarketLoanRates(adapted.marketTables(), moneyRound, round.day);
    // ★★ W2（§一.9）：本轮"未付价款记为债务"的轮级 INFO（发生了什么 + 具名计数）—— 逐笔明细在
    //   {@link #recordDeferredPaymentDebt} 的 INFO/DEBUG/TRACE 三档里；这里只给"这一轮一共赊了多少"。
    if (ctx.deferredPaymentFills > 0 && MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_DEFERRED_PAYMENTS_SETTLED",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  round.day,
                  "fills",
                  ctx.deferredPaymentFills,
                  "deferredMilli",
                  ctx.deferredPaymentMilli,
                  "debtContracts",
                  ctx.debts == null ? 0 : ctx.debts.size(),
                  "reason",
                  "unpaid-goods-price-recorded-as-debt-to-seller"));
    }
    // ★★ R1：本轮的政府授权成交（用于把 filledMilli 累加回授权行、并清除耗尽/到期行）。
    Map<MarketMandateId, Long> mandateFills = collectGovMandateFills(round, ctx);
    // ★★★ A5（2026-10-10）：本轮运输服务的读数（交付量 / 钱腿条数 / 交付故障 / 逐户服务收入）随交出物一起离开。
    //   ★ 它是 T-H3"服务收入体现为该产业经营者的收益"的唯一来源（服务不进订单簿 ⇒ report.fills() 里没有它），
    //     也是当日"产出/交付/作废"三量对账 INFO 的交付侧读数（见 HaulServiceRound 的类注）。
    //   ★ 逐户实收 = 池在本轮的 recordFee 累加（同一条钱腿、同一批数值；不另记一份事实）。
    HaulServiceRound haulService =
        new HaulServiceRound(
            ctx.haulServiceSoldMilli,
            ctx.haulServiceTrades,
            ctx.haulServiceDeliveryFaults,
            ctx.carrierPool.freightEarnedByHousehold());
    return new MarketOutcome(
        MarketReport.withRegulatedTariff(
            round.day,
            trigger,
            carrierPresent(ctx),
            ctx.fills,
            ctx.unfilled,
            routeUsages,
            ctx.freightPaidByCurrency,
            ctx.freightUncollectedByCurrency,
            ctx.scheduledLossMilli,
            ctx.immediateFills,
            ctx.crossRegionFills,
            priceMode(),
            adapted.updates(),
            ctx.sellerOutcomes,
            ctx.buyerOutcomes,
            ctx.tariffByFill,
            fx,
            // ★★ P-T1b：三层税的逐项账目（层/金额/币种/收款政府）—— 读口 taxItems()/taxByCurrency() 的唯一来源。
            ctx.taxItems),
        tablesWithLoanRates,
        mandateFills,
        haulService,
        roundStatsOf(ctx, adapted));
  }

  /**
   * ★★★ <b>D-032 U2b / P3c3 / RF1：用统一 {@link MarketOrder} 按<b>同键</b>替换 planner 自动商品订单。</b>
   *
   * <p>替换键 = {@code (actor, commodity, side)}（商品）—— M3 有 cloth BUY 时只移除该 actor 的 cloth BUY 自动槽， 同
   * actor 的 grain BUY/SELL、cloth SELL 等自动腿逐值保留；随后 {@link #appendInjectedCommodityOrders} 再把 typed
   * 商品订单加入同一轮。缺方向的订单就是没有订单：不恢复反向自动腿。
   *
   * <p>★ 本方法只读统一注入订单里的 {@link MarketItemRef.Commodity}；LAND 统一订单没有 planner 自动手腿，因此不在这里处理。
   */
  private static void replaceAutoCommodityOrdersForIntentActors(
      MatchContext ctx, MarketRound round) {
    Set<String> buyKeys = new LinkedHashSet<>();
    Set<String> sellKeys = new LinkedHashSet<>();
    Set<ActorRef> intentActors = new LinkedHashSet<>();
    for (MarketOrder order : round.injectedMarketOrders) {
      if (!(order.item() instanceof MarketItemRef.Commodity commodity)) {
        continue;
      }
      intentActors.add(order.actor());
      String key = commodityAutoOrderKey(order.actor(), commodity.commodityId(), order.side());
      if (order.side() == MarketOrderSide.BUY) {
        buyKeys.add(key);
      } else {
        sellKeys.add(key);
      }
    }
    if (buyKeys.isEmpty() && sellKeys.isEmpty()) {
      return;
    }
    int beforeBuys = ctx.buys.size();
    int beforeSells = ctx.sells.size();
    List<String> removedKeys = new ArrayList<>();
    ctx.buys.removeIf(
        slot -> {
          String key =
              commodityAutoOrderKey(
                  slot.order.requester(), slot.order.commodity(), MarketOrderSide.BUY);
          if (buyKeys.contains(key)) {
            removedKeys.add(key);
            return true;
          }
          return false;
        });
    ctx.sells.removeIf(
        slot -> {
          String key =
              commodityAutoOrderKey(
                  slot.order.supplier(), slot.order.commodity(), MarketOrderSide.SELL);
          if (sellKeys.contains(key)) {
            removedKeys.add(key);
            return true;
          }
          return false;
        });
    int removedBuys = beforeBuys - ctx.buys.size();
    int removedSells = beforeSells - ctx.sells.size();
    Set<String> retainedKeys = new LinkedHashSet<>();
    for (BuySlot buy : ctx.buys) {
      if (intentActors.contains(buy.order.requester())) {
        retainedKeys.add(
            commodityAutoOrderKey(
                buy.order.requester(), buy.order.commodity(), MarketOrderSide.BUY));
      }
    }
    for (SellSlot sell : ctx.sells) {
      if (intentActors.contains(sell.order.supplier())) {
        retainedKeys.add(
            commodityAutoOrderKey(
                sell.order.supplier(), sell.order.commodity(), MarketOrderSide.SELL));
      }
    }
    if (MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "D032_P3_MARKET_INTENT_REPLACED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day,
                  "intentActors",
                  intentActors.size(),
                  "removedKeys",
                  removedKeys,
                  "retainedKeys",
                  retainedKeys,
                  "removedAutoBuys",
                  removedBuys,
                  "removedAutoSells",
                  removedSells,
                  "retainedAutoBuys",
                  ctx.buys.size(),
                  "retainedAutoSells",
                  ctx.sells.size(),
                  "reason",
                  "m3-intents-replace-only-same-actor-commodity-side-auto-legs"));
    }
  }

  /** 自动商品订单的同键 canonical 串：{@code (actor, commodity, side)}（只用于替换/日志，不是身份类型）。 */
  private static String commodityAutoOrderKey(
      ActorRef actor, CommodityId commodity, MarketOrderSide side) {
    return actor.kind() + ":" + actor.id() + "|" + commodity.value() + "|" + side.name();
  }

  /**
   * ★★★ <b>P3.0：把 MarketRound 上的显式商品订单追加进本次撮合槽位。</b>
   *
   * <p>它们不是第二套订单簿：仍与自动订单同属 {@code ctx.buys}/{@code ctx.sells}，仍由同一个 {@code clearOncePerCycle}
   * 撮合、同一套冻结/结算公式落账。订单指向的格必须有市场表且该商品已定价；否则具名 fail-closed。
   */
  private static void appendInjectedCommodityOrders(
      MatchContext ctx, MarketRound round, MarketPayChoice payChoice) {
    for (BuyOrder order : round.injectedBuyOrders) {
      HexCoord hex = order.deliverTo();
      MarketRegion region = ctx.topology.regionOf(hex);
      MarketTable market = ctx.tableAt(hex);
      if (market == null || !market.hasPrice(order.commodity())) {
        throw new IllegalStateException(
            "显式买单指向未开市/未定价的商品（P3.0 fail-closed）: actor="
                + order.requester()
                + " hex="
                + hex
                + " commodity="
                + order.commodity());
      }
      Participant buyer = participantForInjectedOrder(ctx, round, order.requester(), hex);
      ctx.buys.add(new BuySlot(order, buyer, hex, region, market, payChoice));
    }
    for (SellOrder order : round.injectedSellOrders) {
      HexCoord hex = order.dispatchFrom();
      MarketRegion region = ctx.topology.regionOf(hex);
      MarketTable market = ctx.tableAt(hex);
      if (market == null || !market.hasPrice(order.commodity())) {
        throw new IllegalStateException(
            "显式卖单指向未开市/未定价的商品（P3.0 fail-closed）: actor="
                + order.supplier()
                + " hex="
                + hex
                + " commodity="
                + order.commodity());
      }
      Participant seller = participantForInjectedOrder(ctx, round, order.supplier(), hex);
      ctx.sells.add(new SellSlot(order, seller, hex, market, region, round));
    }
  }

  /** 显式订单的主体优先复用 planner 已建的参与者；缺失时从现存家户行补一个最小参与者（fail-closed）。 */
  private static Participant participantForInjectedOrder(
      MatchContext ctx, MarketRound round, ActorRef actor, HexCoord hex) {
    Participant existing = ctx.participants.get(actor);
    if (existing != null) {
      return existing;
    }
    HouseholdId household = round.householdOfActor.get(actor);
    HouseholdEconomy row = household == null ? null : round.householdEconomies.get(household);
    if (household == null || row == null) {
      throw new IllegalStateException(
          "显式市场意向的主体解析不到现存家户（P3.0 fail-closed）: actor=" + actor + " hex=" + hex);
    }
    Participant created = new Participant(actor, household, row.view(), List.of());
    ctx.participants.put(actor, created);
    ctx.participantHex.put(actor, hex);
    return created;
  }

  /**
   * ★★ <b>给并行 worker 用的只读市场轮</b>：八张账户表浅拷成普通 {@code LinkedHashMap}（内层表只读共享）， 避开 {@link
   * AccountSession} 活视图的 owner 守卫；账本换成本地空累加器（订单生成不铸转移）。
   *
   * <p>★ 只允许在<b>协调器线程</b>调用（读活视图本身要过 owner 守卫），产物在并行阶段只读。
   *
   * <p>★★ <b>2026-10-08：改走克隆的唯一拼写点 {@link MarketRound#copyForWorker}</b>（旧版在这里手抄 22 个构造参数 ⇒ {@code
   * MarketRound} 新增的 {@code arbitrage} 字段被静默丢掉，而<b>这个克隆轮才是真正在下订单的那一份</b> ⇒
   * 套利一次都不生效且无任何报错）。语义与旧版唯一差异：{@code regulation} 由写死的 {@code none()} 改为<b>原样带过</b> ——
   * 逐调用点审计过，该值在克隆轮上没有读取点（P-T1c 起订单生成只读逐格价表，完全不读调控）。
   */
  static MarketRound readOnlyPlanningRound(MarketRound round) {
    return round.copyForWorker(
        new LinkedHashMap<>(round.householdGoods),
        new LinkedHashMap<>(round.householdMoney),
        new LinkedHashMap<>(round.householdFrozenGoods),
        new LinkedHashMap<>(round.householdFrozenMoney),
        new LinkedHashMap<>(round.unmetToday),
        new ProductionLedger.Accumulator(round.day));
  }

  /**
   * ★★ RA1b2c2：旧重载没有 {@link LandArableSupply} 输入时，按当前持有逐 hex 合计派生一份同量供应表。
   *
   * <p>★ 市场土地转移只在一个 hex 内把数量从卖方搬到买方，总量不变 ⇒ 派生供应表足以让 {@code LandBook.apply} 守住"不超卖、不跨 hex
   * 拼数量"；真实生产路径由 app 从 Map 构造瞬态 {@code LandArableSupply} 并在会话入口显式注入。
   */
  private static LandArableSupply arableSupplyOf(Map<LandHoldingKey, LandHolding> landHoldings) {
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    for (LandHolding holding : landHoldings.values()) {
      totals.merge(holding.hex(), holding.quantityMilliMu(), Math::addExact);
    }
    return new LandArableSupply(totals);
  }
}
