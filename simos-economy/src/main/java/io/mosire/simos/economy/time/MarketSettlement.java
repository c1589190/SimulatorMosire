package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketPricer.AdaptivePrices;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>区域市场撮合与在途运输（M2.3 + M2.4 + M2.5）</b>：把 M2.1/M2.2 的"订单体系"从"每格一次"扩成 <b>每 5 天一轮、区内优先、邻区稀疏跨区、跨区走
 * {@link TradeRoute} 的 ETA/损耗/运力</b>。
 *
 * <p>★★ <b>调度（P2a）</b>：{@link #triggerFor} 只看<b>绝对世界日 + 当前市场表是否为空</b> —— 每 {@link
 * #MARKET_RESTOCK_INTERVAL_DAYS} 天例行一轮，其余日不开市；没有市场表也不开市。关账日与低库存追加轮均已删除。 ★ 没有任何"距上次开市几天"的计数器 ⇒ 一次
 * 360 天与三次 120 天在同一世界日必然同轮（M0.1）。
 *
 * <p>★★ <b>区域（M2.3）</b>：{@link MarketTopology} 由组合根传入（城市节点 + tier 半径 + 地图格集 + 逐格地形代价），
 * 每个市场格恰属一个区。撮合顺序：
 *
 * <pre>
 * ① 区内优先：同一 (region, commodity) 内按参考价撮合（价格 = 集散节点格的市场价）
 * ② 跨区候选：只考"直接邻接供应区"（MarketTopology.adjacent）
 * ③ 判到货价/量/期限：卖方基准价 ≤ 买方限价；到货日 ≤ latestArrivalTick；限价为**货款**上限、运费另计
 * ④ 有限轮内分运力：每路线最多 MARKET_MAX_TRANSPORT_ROUNDS 个运力窗口
 * ⑤ 按原始限价与预算分配回主体：区内按剩余需求/供给比例配给，再逐笔落到原始订单
 * ⑥ 原子提交、建在途：货款/运费即时结清，货权转买方并进入 ShipmentBatch
 * </pre>
 *
 * <p>★★ <b>在途（M2.4）</b>：发运时卖方库存减、买方在途资产增；到货日（{@code EconomySettlement} 的日循环）在途减、
 * 目的地库存增；到货前目的地既不能消费、也不能再挂牌卖出（货不在任何 {@code HouseholdInventory} 余额里）。损耗逐票由买方承担 （{@link
 * LossBearer#BUYER}），到货时从在途量里扣并记进 {@code ProductionLedger} 的损耗账户。
 *
 * <p>★★ <b>运费必须有收款方</b>：承运主体 = {@code ActorKind.ORGANIZATION} 且会话里有货币账。世界里没有承运 actor
 * 时<b>不收运费</b>（{@link MarketReport#freightUncollectedByCurrency()} 记下应收而未收的读数），禁钱凭空消失。
 *
 * <p>★★ <b>跨区结算暂设即时</b>：{@link MarketReport#CROSS_REGION_SETTLEMENT_IMMEDIATE} = {@code true}（★
 * W2-fix6：本类原副本 {@code MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE} 零读者，已删；唯一拼写点是 {@code MarketReport}
 * 的那一份）—— 货款与运费在**发运日** 结清，货却在 ETA 之后才到；这是设计允许的简化，L3 的读数契约必须原样标注（M2.0 #4）。
 *
 * <p>★★ <b>价格（M2.6）</b>：参考价是格价表里的报价（区内成交价 = 集散节点格价、跨区 = 卖方格价）；买卖两侧的 **限价**由 {@code MarketTable}
 * 的两个具名常量现算（bid = 卖方底价、ask = 买方限价），订单按限价过滤 —— 价差没有中间人截留， 成交仍按参考价。自适应（{@link
 * #MARKET_ADAPTIVE_PRICING_ENABLED}，2026-10-07 用户裁定打开）在每轮撮合后按供需 z 改下一轮的 **区价**（成员格同改），改价只经 {@link
 * #clearOncePerCycle} 返回的 {@code MarketOutcome} 交回 {@code EconomySettlement} ⇒ 走 {@code
 * marketTables} 这个既有 {@code FieldDelta} 组件的变更集，没有第二处改价。
 *
 * <p>★★ <b>本类仍不是第二个 applier</b>：一切库存/货币的换手都经 {@code EconomySettlement.applyTransfer}（唯一写口）； 冻结只动
 * {@code frozen*} 表（M1.2 的 {@code freeze*}/{@code release*} 语义），在发运/成交/轮末释放。★ 唯一的例外是
 * "装载在途"：发运时把刚记到买方名下的量从会话余额移进 {@link ShipmentBatch}（它不是换手，是**在途资产的落点**， 到货日再反向落回）。
 */
final class MarketSettlement {

  private MarketSettlement() {}

  static final long MARKET_RESTOCK_INTERVAL_DAYS = MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS;

  static final long MARKET_LIFE_RESERVE_DAYS = MarketConstants.MARKET_LIFE_RESERVE_DAYS;

  static final long MARKET_BUY_DEADLINE_DAYS = MarketConstants.MARKET_BUY_DEADLINE_DAYS;

  static final long MARKET_MAX_TRANSPORT_ROUNDS = MarketConstants.MARKET_MAX_TRANSPORT_ROUNDS;

  static final long MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW =
      MarketConstants.MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW;

  static final long MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX =
      MarketConstants.MARKET_ROUTE_CAPACITY_REFERENCE_DISTANCE_HEX;

  static final long PROCUREMENT_RANK_ABSENT = MarketConstants.PROCUREMENT_RANK_ABSENT;

  static final int MARKET_TRANSPORT_LOSS_PER_MILLE =
      MarketConstants.MARKET_TRANSPORT_LOSS_PER_MILLE;

  static final boolean MARKET_ADAPTIVE_PRICING_ENABLED =
      MarketConstants.MARKET_ADAPTIVE_PRICING_ENABLED;

  static final long MARKET_ADAPTIVE_ALPHA_PER_MILLE =
      MarketConstants.MARKET_ADAPTIVE_ALPHA_PER_MILLE;

  static final long LENDER_MONEY_BUFFER_PER_CAPITA_MILLI =
      MarketConstants.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI;

  static final IndustryId TRANSPORT_LOSS_ACCOUNT = MarketConstants.TRANSPORT_LOSS_ACCOUNT;

  static final long MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP =
      MarketShipmentBook.MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP;

  static final long MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE =
      MarketShipmentBook.MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE;

  /**
   * ★★ <b>控制方裁定 R2（2026-10-11）：家户在本轮<b>商品市场</b>上的角色</b> —— "谁自动买卖 / 谁只买不卖 /
   * 谁只按授权下单"的<b>唯一拼写点</b>（判定见 {@link MarketRound#householdMarketRole})。
   *
   * <p>★★ <b>为什么需要它</b>：Z7b 原来把{@code Unit.households()}（军户/官吏户/国库户）**整体**挡在市场之外
   * （买卖都不生成），借粮腿一退役，这些家户就失去了**唯一**的口粮通道 ⇒ {@code hh-unit:gov-central} 结构性饥饿 （W5.5 的 BLOCKED 点）。R2
   * 把这条排除**收窄**：卖侧维持原口径（公家/单位实物不得被当余量清仓，Z7b 的根因守护）， <b>买侧放开</b>（买口粮是家户消费，不是政策层/补贴层）；国库户（{@code
   * hh-gov-*}）两侧都不自动下单。
   */
  enum HouseholdMarketRole {
    /** 普通家户：买卖两侧都按自动订单参与。 */
    FULL,
    /** 单位户（官吏户/军户…）：<b>只买不卖</b> —— 买盘正常进（含既有的赊购），实物供给不被自动清仓。 */
    BUY_ONLY,
    /**
     * 政府国库户（{@code hh-gov-*}）：两侧都<b>不生成自动订单</b>，只按 {@code EconomyData.govMarketMandates()} 的明确授权。
     */
    AUTHORIZATION_ONLY
  }

  /** 一个格上、某一商品的计划订单（**瞬时**；只在本轮内流转，不进任何状态）。 */
  record PlannedOrders(List<BuyOrder> buys, List<SellOrder> sells) {
    PlannedOrders {
      Objects.requireNonNull(buys, "buys");
      Objects.requireNonNull(sells, "sells");
      buys = List.copyOf(buys);
      sells = List.copyOf(sells);
    }
  }

  /**
   * ★★ <b>一轮市场的完整交出物（M2.6/M2.7）</b>：{@code report} = 只读读数原料；{@code marketTables} = 本轮结束时的价格表
   * （固定模式下与入参逐值相同，自适应模式下是改价后的新表）。
   *
   * <p>★ 价格表<b>只经这里</b>离开本类 → {@code EconomySettlement} 把它放进交出的 {@code EconomyData} → {@code
   * marketTables} 作为既有 {@code FieldDelta} 组件进变更集。没有第二条改价路径。
   *
   * <p>★★ <b>A5（2026-10-10）</b>：第 4 个组件 {@link HaulServiceRound} = 本轮运输服务成交的读数（交付量 / 钱腿条数 / 交付故障 /
   * 逐户服务收入）。它**必须**跟着交出物一起离开本类：① 服务不进订单簿 ⇒ 这些量在 {@link MarketReport} 里没有 对应槽位（{@code fills}/{@code
   * sellerOutcomes} 恒无服务成交）；② {@code OperatorSettlement}（T-H3 的收益归属）与 {@code
   * EconomySettlement}（当日产出/交付/作废三量对账 INFO）都要读它 ⇒ 不能只活在市场轮的本地 ctx 里。
   */
  record MarketOutcome(
      MarketReport report,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<MarketMandateId, Long> mandateFills,
      HaulServiceRound haulService,
      // ★★ W1（第 5 个组件）：本轮的市场轮统计读数（逐市场的"单轮形态"统计簿；键 = 市场 id）。
      //   ★ 它是**纯读数**：不参与任何撮合/定价判定，只被 EconomySettlement 折进 EconomyData.marketRoundStats。
      //   ★★ W5.5：货币腿算出的**下一轮清算利率**不再单列组件 —— 它是 {@code MarketTable} 的一个字段，
      //     随第 2 个组件 {@code marketTables} 一起交回（"本轮成交用本轮价、写回下一轮生效"的既有那条路）。
      Map<MarketZoneId, MarketRoundStats> roundStats) {

    MarketOutcome {
      Objects.requireNonNull(report, "report");
      Objects.requireNonNull(marketTables, "marketTables");
      Objects.requireNonNull(mandateFills, "mandateFills（没有授权成交给空表）");
      Objects.requireNonNull(haulService, "haulService（没有服务成交给 HaulServiceRound.none()）");
      Objects.requireNonNull(roundStats, "roundStats（没有统计给空表）");
      // ★ 保序不可变（不用 Map.copyOf：迭代序不是内容的纯函数）；值是不可变 record / Long。
      marketTables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(marketTables));
      mandateFills = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(mandateFills));
    }

    /** ★ R1 的旧形状（没有政府授权成交）：{@code mandateFills} 取空表（夹具/旧调用方）。 */
    MarketOutcome(MarketReport report, Map<MarketZoneId, MarketTable> marketTables) {
      this(report, marketTables, Map.of(), HaulServiceRound.none(), Map.of());
    }

    /** ★ A5 之前的形状（没有服务读数）：{@link HaulServiceRound#none()}。 */
    MarketOutcome(
        MarketReport report,
        Map<MarketZoneId, MarketTable> marketTables,
        Map<MarketMandateId, Long> mandateFills) {
      this(report, marketTables, mandateFills, HaulServiceRound.none(), Map.of());
    }
  }

  /**
   * ★★★ <b>A5（2026-10-10）：一轮运输服务成交的只读读数</b>（{@link MarketOutcome} 的第 4 个组件）。
   *
   * <pre>
   * deliveredMilli     本轮**物理上真的交付**的运输服务（毫服务；含被免运费 / 自承运的条目 —— 服务照跑、只是不收钱）
   * trades             本轮铸出去的服务钱腿条数（免运费/自承运条目不计 ⇒ 它可以 &lt; deliveredMilli 对应的条数）
   * deliveryFaults     交付点取不到服务货的次数（**契约故障**；必须为 0，非 0 时逐条 ERROR 且本笔不成交）
   * revenueByHousehold 逐户服务收入（按币分列；键 = 承运家户）—— T-H3"服务收入体现为该产业经营者的收益"的唯一来源
   * </pre>
   *
   * <p>★★ <b>为什么必须单独一个形状</b>（A4 账本 §5-2 的漏报）：{@code HAUL_SERVICE_SETTLED} 旧实现只报 {@code trades}
   * 与它一起的金额 ⇒ "有交付但一条钱腿都没铸"的日子（全天免运费 / 自承运）**整行不刷**， INFO 读数比实际交付少 33.4%。{@code deliveredMilli} 与
   * {@code trades} 拆成两个量之后， "全天零成交但确有交付"再也不可能被静默丢掉。
   *
   * <p>★ 保序/不可变：逐户表按（家户 id 升序 → 币种规范串升序）冻结（I7；禁 {@code Map.copyOf}）。
   */
  record HaulServiceRound(
      long deliveredMilli,
      long trades,
      long deliveryFaults,
      Map<HouseholdId, Map<CurrencyId, Long>> revenueByHousehold) {

    HaulServiceRound {
      if (deliveredMilli < 0L || trades < 0L || deliveryFaults < 0L) {
        throw new IllegalArgumentException(
            "服务读数不得为负: delivered="
                + deliveredMilli
                + ", trades="
                + trades
                + ", faults="
                + deliveryFaults);
      }
      Map<HouseholdId, Map<CurrencyId, Long>> ordered = new LinkedHashMap<>();
      Map<HouseholdId, Map<CurrencyId, Long>> source =
          revenueByHousehold == null ? Map.of() : revenueByHousehold;
      List<HouseholdId> households = new ArrayList<>(source.keySet());
      households.sort(Comparator.comparing(HouseholdId::value));
      for (HouseholdId household : households) {
        Map<CurrencyId, Long> legs = source.get(household);
        if (legs == null || legs.isEmpty()) {
          continue;
        }
        Map<CurrencyId, Long> frozen = new LinkedHashMap<>();
        List<CurrencyId> currencies = new ArrayList<>(legs.keySet());
        currencies.sort(Comparator.comparing(CurrencyId::value));
        for (CurrencyId currency : currencies) {
          long amountMilli = legs.getOrDefault(currency, 0L);
          if (amountMilli != 0L) {
            frozen.put(currency, amountMilli);
          }
        }
        if (!frozen.isEmpty()) {
          ordered.put(household, java.util.Collections.unmodifiableMap(frozen));
        }
      }
      revenueByHousehold = java.util.Collections.unmodifiableMap(ordered);
    }

    /** 本轮没有任何服务活动（缺省中性：不落任何读数、不改任何判据）。 */
    static HaulServiceRound none() {
      return new HaulServiceRound(0L, 0L, 0L, Map.of());
    }
  }

  /** 市场轮会话视图：实现见 {@link MarketRoundCore}，保留原嵌套类型名与构造器入口。 */
  static final class MarketRound extends MarketRoundCore {

    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands) {
      super(
          day,
          householdEconomies,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands);
    }

    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation) {
      super(
          day,
          householdEconomies,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          regulation);
    }

    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation,
        DeferredPaymentConfig deferredPaymentConfig,
        Map<DebtContractId, DebtContract> debts) {
      super(
          day,
          householdEconomies,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          regulation,
          deferredPaymentConfig,
          debts);
    }

    MarketRound(
        long day,
        Map<HouseholdId, HouseholdEconomy> householdEconomies,
        Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
        Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
        Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
        Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
        Map<ActorRef, HouseholdId> householdOfActor,
        Map<IndustryId, IndustryDefinitionView> industries,
        Map<ProductionUnitId, ProductionProcess> units,
        Map<AssetShareId, OwnershipStake> assetShares,
        Map<ProductionUnitId, ProductionRules> relations,
        Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
        Map<ShipmentId, ShipmentBatch> shipments,
        ProductionLedger.Accumulator ledger,
        Map<ProductionUnitId, OperatorCondition> operatorConditions,
        SettlementIndex index,
        Map<DemandId, HouseholdDemand> householdDemands,
        MarketRegulation regulation,
        DeferredPaymentConfig deferredPaymentConfig,
        Map<DebtContractId, DebtContract> debts,
        Set<HouseholdId> marketExcludedHouseholds) {
      super(
          day,
          householdEconomies,
          householdGoods,
          householdMoney,
          householdFrozenGoods,
          householdFrozenMoney,
          unmetToday,
          householdOfActor,
          industries,
          units,
          assetShares,
          relations,
          laborCommitments,
          shipments,
          ledger,
          operatorConditions,
          index,
          householdDemands,
          regulation,
          deferredPaymentConfig,
          debts,
          marketExcludedHouseholds);
    }
  }

  static MarketTrigger triggerFor(long day, Map<MarketZoneId, MarketTable> marketTables) {
    return MarketTriggerBook.triggerFor(day, marketTables);
  }

  static PlannedOrders planOrders(
      MarketRound round, HexCoord hex, MarketTable market, CommodityId commodity) {
    return MarketOrderPlanner.planOrders(round, hex, market, commodity);
  }

  /**
   * ★★★ <b>P2f：把一张土地订单注入本轮订单簿</b>（P3 的装配口；包内可见）。
   *
   * <p>★ 它<b>不生成生产侧土地买卖意向</b>（规模/两遍编排属 P3）：P2f 只保证"土地订单能进入同一个 {@link MarketRound}、由同一个 {@link
   * #clearOncePerCycle} 撮合"。★ 典型装配点 = {@code SettlementDayMarketPipeline} 在 {@code withX} 链之后、撮合之前：
   * {@code ctx.marketRound.addLandOrder(...)}。
   */
  static void putLandOrder(MarketRound round, LandOrder order) {
    MarketOrderPlanner.putLandOrder(round, order);
  }

  /** ★★★ P3.0：把 M3 的商品意向转换出的买单追加进同一个市场轮（不清空 planner 自动订单）。 */
  static void putCommodityBuyOrder(MarketRound round, BuyOrder order) {
    java.util.Objects.requireNonNull(round, "putCommodityBuyOrder 的 round 不得为 null");
    round.addInjectedBuyOrder(order);
  }

  /** ★★★ P3.0：把 M3 的商品意向转换出的卖单追加进同一个市场轮（不清空 planner 自动订单）。 */
  static void putCommoditySellOrder(MarketRound round, SellOrder order) {
    java.util.Objects.requireNonNull(round, "putCommoditySellOrder 的 round 不得为 null");
    round.addInjectedSellOrder(order);
  }

  /** ★★ P2f：本轮土地订单簿的只读/可写视图（与商品买卖槽同属这一个市场轮）。 */
  static java.util.List<LandOrder> landOrders(MarketRound round) {
    return round.landOrders();
  }

  /** ★★★ P3.0：本轮显式商品买单的只读视图（与自动订单同属这一轮）。 */
  static java.util.List<BuyOrder> injectedBuyOrders(MarketRound round) {
    return java.util.List.copyOf(round.injectedBuyOrders);
  }

  /** ★★★ P3.0：本轮显式商品卖单的只读视图（与自动订单同属这一轮）。 */
  static java.util.List<SellOrder> injectedSellOrders(MarketRound round) {
    return java.util.List.copyOf(round.injectedSellOrders);
  }

  /** ★★★ P3.0：本轮注入的原始意向只读视图（priority 等字段留给 P3c；P3.0 不据此排序）。 */
  static java.util.List<MarketIntent> injectedMarketIntents(MarketRound round) {
    return java.util.List.copyOf(round.injectedMarketIntents);
  }

  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      Map<String, List<HouseholdId>> rowsByHex) {
    return MarketOrderPlanner.planOrders(round, hex, market, commodity, rowsByHex);
  }

  static PlannedOrders planOrders(
      MarketRound round,
      HexCoord hex,
      MarketTable market,
      CommodityId commodity,
      Map<String, List<HouseholdId>> rowsByHex,
      MarketPayChoice payChoice) {
    return MarketOrderPlanner.planOrders(round, hex, market, commodity, rowsByHex, payChoice);
  }

  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology) {
    return MarketRoundRunner.clearOncePerCycle(marketTables, round, trigger, topology);
  }

  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism) {
    return MarketRoundRunner.clearOncePerCycle(marketTables, round, trigger, topology, parallelism);
  }

  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds) {
    return MarketRoundRunner.clearOncePerCycle(
        marketTables, round, trigger, topology, parallelism, carrierPool, pureMerchantHouseholds);
  }

  static MarketOutcome clearOncePerCycle(
      Map<MarketZoneId, MarketTable> marketTables,
      MarketRound round,
      MarketTrigger trigger,
      MarketTopology topology,
      EconomyParallelism parallelism,
      MerchantCapacityPool carrierPool,
      Set<HouseholdId> pureMerchantHouseholds,
      Map<MarketZoneId, MarketZone> marketZones) {
    return MarketRoundRunner.clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones);
  }

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
    return MarketRoundRunner.clearOncePerCycle(
        marketTables,
        round,
        trigger,
        topology,
        parallelism,
        carrierPool,
        pureMerchantHouseholds,
        marketZones,
        ordering);
  }

  static PriceMode priceMode() {
    return MarketPricer.priceMode();
  }

  static Map<MarketZoneId, MarketRoundStats> roundStatsOf(
      MatchContext ctx, AdaptivePrices adapted) {
    return MarketReadoutBook.roundStatsOf(ctx, adapted);
  }

  static long adaptiveNextPrice(long price, long demand, long supply, long alphaPerMille) {
    return MarketPricer.adaptiveNextPrice(price, demand, supply, alphaPerMille);
  }

  static long commodityFreightBaseMilli(MarketTopology topology, CommodityId commodity) {
    return MarketShipmentBook.commodityFreightBaseMilli(topology, commodity);
  }

  static long carrierCostPerMille(MerchantPolicy.MerchantTier tier) {
    return MarketShipmentBook.carrierCostPerMille(tier);
  }

  static long plannedCarrierCostPerMille(MatchContext ctx) {
    return MarketShipmentBook.plannedCarrierCostPerMille(ctx);
  }

  static long freightUnitMilli(
      long commodityBaseMilli, long ratePerMille, long carrierCostPerMille) {
    return MarketShipmentBook.freightUnitMilli(
        commodityBaseMilli, ratePerMille, carrierCostPerMille);
  }

  static long productionCostOfParticipant(
      MatchContext ctx, Participant participant, CommodityId commodity) {
    return MarketReadoutBook.productionCostOfParticipant(ctx, participant, commodity);
  }

  static long spendableMoneyOf(MarketRound round, HouseholdId household, CurrencyId currency) {
    return MarketRosterBook.spendableMoneyOf(round, household, currency);
  }

  static Map<CommodityId, Long> householdProductionCostOf(
      MarketRound round, Participant participant) {
    return MarketRosterBook.householdProductionCostOf(round, participant);
  }

  /** 运输服务成市 ⇒ 货 ↔ 服务钱腿走 MARKET_TRADE；否则走既有的 CARRIER_FEE 私有腿（结构互斥）。 */
  static TransferReason freightTransferReason(boolean haulService) {
    return haulService ? TransferReason.MARKET_TRADE : TransferReason.CARRIER_FEE;
  }

  /** 与 {@link #freightTransferReason} 同源的逐笔事件名（只作 TRACE 读数，不参与任何判据）。 */
  static String freightTransferEventName(boolean haulService) {
    return haulService ? "HAUL_SERVICE_PAID" : "CARRIER_FEE_PAID";
  }
}
