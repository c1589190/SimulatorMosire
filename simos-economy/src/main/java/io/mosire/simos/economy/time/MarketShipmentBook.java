package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.RouteAccumulator;
import static io.mosire.simos.economy.time.MarketModelBook.RouteContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketRosterBook.ceilDiv;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketShipmentBook {

  private MarketShipmentBook() {}

  /**
   * ★★ <b>F-2（2026-10-10）：一条 lane 上"卡在运力"的**具名归因档**（唯一拼写点）</b>。
   *
   * <pre>
   * 发货格 fromHex 服务成市（有 haul 牌价）⇒ 取池给出的那一档（缺提供商 / 缺服务货 / 半径外 / 亚单位 / 运力耗尽）
   * 否则（无牌价）                          ⇒ "capacity-exhausted"（逐值等于改前的笼统"运力不足"）
   * </pre>
   *
   * <p>★ 为什么按 {@code fromHex} 有没有牌价分流：服务口径与非服务口径的"没走完"归因不同（前者可能根本没货、 后者只可能是运力不够）；这一条与 {@code
   * HaulService.pricedAt}（唯一的成市判据）同一口径。
   */
  static String logisticsBlockDetail(
      MatchContext ctx, HexCoord fromHex, MerchantCapacityPool.CarrierAllocation allocation) {
    MerchantCapacityPool.LogisticsBlock block = allocation.logisticsBlock();
    return haulServiceAt(ctx, fromHex)
        ? block.detail()
        : MerchantCapacityPool.LogisticsBlock.CAPACITY_EXHAUSTED.detail();
  }

  /**
   * ★★ <b>F-2（2026-10-10）：业务拒绝的 INFO 具名行</b>（§一.9 分级："业务拒绝 = INFO"）—— 只在归因档是**业务拒绝**（缺提供商 /
   * 缺服务货）时发一行；运行期三支（半径 / 亚单位 / 耗尽）不发（那由池侧 DEBUG/TRACE 与逐轮 INFO 分档给出）。★ 不发 ⇒ 缺省世界（无牌价、无跨格需求）日志逐字不变。
   *
   * <p>★ 它是"买不到运力"的**第一现场读数**：改前这一档在公开读数面与"运力不足"同为 {@code LOGISTICS_CAPACITY}， 读者必须开 DEBUG
   * 才能分辨；现在这一行按具名 reason 出现，另加 {@link MarketReport.Unfilled#logisticsDetail()} 的逐笔细分。
   */
  static void logLogisticsBusinessRejection(
      MatchContext ctx,
      BuySlot buy,
      RouteContext route,
      MerchantCapacityPool.CarrierAllocation allocation,
      String detail,
      long truncatedMilli) {
    if (truncatedMilli <= 0L || !allocation.businessRejection()) {
      return;
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "LOGISTICS_SERVICE_SUPPLY_REJECTED",
                EconomyLogSource.ECONOMY_ORGANIZATION,
                "day",
                ctx.round.day,
                "reason",
                detail,
                "commodity",
                route.commodity().value(),
                "fromHex",
                route.from(),
                "toHex",
                route.to(),
                "buyer",
                buy.buyer.actor().id(),
                "truncatedMilli",
                truncatedMilli,
                "requestedMilli",
                allocation.requestedMilli(),
                "unallocatedMilli",
                allocation.unallocatedMilli()));
  }

  /**
   * ★★ <b>承运运力不足的具名落点</b>：买方槽 blocked、卖方槽 capacityBlocked、路线 bottleneck（有这条车道时），三处都
   * 读得到；并把<b>被运力截断的量</b>累加到两个槽位（V-20：截断部分不许进自适应定价的 demand/supply 统计）。
   *
   * <p>★ M-A1 起参数改用 {@code (from, to, commodity)} 而不是 {@code RouteContext}：有一条"发货格没有运力"的拦下发生在
   * **路线根本不建**的那一刻（那时没有 RouteContext）。
   *
   * <p>★★ D-1b：记进两侧的是 {@link LaneUnservedBook#claimPair} / {@link LaneUnservedBook#claimLane}
   * 认领的**增量**。 同一条 lane 的同一份未服务量若已由本轮其它落点记过（候选生成处的整条拦下 / 路线窗口预算用尽），这里不再记第二遍。
   *
   * @param truncatedMilli 本笔因运力未获服务的量（毫商品；&gt; 0）
   */
  static void markCapacityBlocked(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      HexCoord from,
      HexCoord to,
      CommodityId commodity,
      long truncatedMilli,
      String logisticsDetail) {
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
    }
    // ★★ F-2（2026-10-10）：**具名归因档**与 blocked 在**同一处**落定（两者不可能漂开）；卖方侧同写。
    //   档由调用方从池的分配结果取（缺提供商 / 缺服务货 / 半径外 / 亚单位 / 运力耗尽；无牌价 ⇒ "运力不足"）。
    buy.logisticsDetail = logisticsDetail;
    sell.logisticsDetail = logisticsDetail;
    sell.capacityBlocked = true;
    // ★★ D-1b（净额记账）：同一次配对的这份未服务量只记一次 —— 本笔"没分到的那份"在同一轮的多次试配 / 多个运力窗口上
    //   会被反复观察到（**同一份**量）；整条车道级的水位（整条拦下 / 路线窗口用尽记在所有还有剩余的槽位上）同样要认。
    String laneKey = LaneUnservedBook.laneKey(from, to, commodity);
    long recorded =
        ctx.laneUnserved.claimPair(laneKey, buy.orderIndex, sell.orderIndex, truncatedMilli);
    if (recorded > 0L) {
      buy.capacityTruncatedMilli = Math.addExact(buy.capacityTruncatedMilli, recorded);
      sell.capacityTruncatedMilli = Math.addExact(sell.capacityTruncatedMilli, recorded);
    }
    RouteAccumulator acc = ctx.routes.get(laneKey);
    if (acc != null) {
      acc.bottleneck = true;
    }
  }

  /**
   * ★★ <b>M-A1：发货格没有运力 ⇒ 这条车道整条拦下</b>（具名 {@code LOGISTICS_CAPACITY}）—— 买卖两侧的余量都记成
   * "被运力截断"（V-20：截断部分不进自适应定价的 demand/supply 统计），并各留一条 DEBUG 的"为什么"。
   *
   * <p>★ 两侧的截断量各自按对侧余量封顶（{@code min(本侧余量, 对侧余量)}）：一个买方要 100、卖方只剩 30 ⇒ 双方各记 30。 两侧记的是**同一条车道**的
   * {@code min(买方余量合计, 卖方余量合计)}（= 下面日志的 {@code truncatedMilli}），
   * 不是"对侧的整份余量"——后者会把本侧装不下的部分也记成截断，超过挂单量时连**已服务**的那份都被剔出统计（V-20）。
   *
   * <p>★★ <b>D-1b（净额记账）</b>：这里记进两侧的是 {@link LaneUnservedBook#claimLane} 认领的**增量**。 同一条 lane
   * 的同一份未服务量若已被本轮其它落点记过（承运分配不足 {@link #markCapacityBlocked} / 路线窗口预算用尽），
   * 本落点<b>不再记第二遍</b>（两次相加会把已服务的那一份也剔出统计 ⇒ demand 被剔光、价格信号被压低）。
   */
  static void blockLaneWithoutCapacity(
      MatchContext ctx,
      List<BuySlot> buys,
      List<SellSlot> sells,
      HexCoord sellerHex,
      HexCoord buyerHex,
      CommodityId commodity) {
    long buyTotal = 0L;
    for (BuySlot buy : buys) {
      if (buy.remaining > 0L) {
        buyTotal = Math.addExact(buyTotal, buy.remaining);
      }
    }
    long sellTotal = 0L;
    for (SellSlot sell : sells) {
      if (sell.remaining > 0L) {
        sellTotal = Math.addExact(sellTotal, sell.remaining);
      }
    }
    long blocked = Math.min(buyTotal, sellTotal);
    // ★★ F-2（2026-10-10）：**可分辨归因** —— 发货格有牌价（运输服务成市）⇒ 本格的拒绝原因不再是笼统的"没有承运人"，
    //   而是具名的"这一格没有跑商家户"（缺提供商）；没有牌价 ⇒ 退回既有的"运力不足"一支（逐值等于改前）。
    //   ★ 与"有跑商家户但没有服务货"（池侧 NO_CARRIER_WITH_SERVICE）**分开**：两者处置不同（引商 vs 等货）。
    //   ★ 这一档随轮级读数（池的 blockedByReason 与 MARKET 的 blockedByReason）进公开面。
    String detail =
        haulServiceAt(ctx, sellerHex)
            ? MerchantCapacityPool.NO_MERCHANT_HOUSEHOLD_REASON
            : MerchantCapacityPool.LogisticsBlock.CAPACITY_EXHAUSTED.detail();
    // ★★ D-1b（净额记账）：整条车道的这份未服务量只记一次 —— 同一份量可能已由本轮的**别的落点**记过
    //   （承运分配不足 / 路线窗口预算用尽），那两次相加会把**已服务**的那一份也剔出价格统计（V-20 幅度错）。
    long recorded =
        ctx.laneUnserved.claimLane(
            LaneUnservedBook.laneKey(sellerHex, buyerHex, commodity), blocked);
    for (BuySlot buy : buys) {
      if (buy.remaining > 0L) {
        // ★★ D-1（2026-10-10 裁定）：按**本侧余量**封顶 —— 记的是这条车道真正装不下的量（min(买余, 卖余)），
        //   不是对侧的整份余量。记多了会把**挂单量以内、已被服务**的那一份也剔出价格统计（V-20）。
        if (recorded > 0L) {
          buy.capacityTruncatedMilli = Math.addExact(buy.capacityTruncatedMilli, recorded);
        }
        if (buy.blocked == null) {
          buy.blocked = MarketUnfilledReason.LOGISTICS_CAPACITY;
        }
        // ★★ F-2：具名归因与 blocked 同写点（有牌价 ⇒ "缺提供商"；无牌价 ⇒ 退回"运力不足"）。
        buy.logisticsDetail = detail;
      }
    }
    for (SellSlot sell : sells) {
      if (sell.remaining > 0L) {
        sell.capacityBlocked = true;
        sell.logisticsDetail = detail;
        if (recorded > 0L) {
          sell.capacityTruncatedMilli = Math.addExact(sell.capacityTruncatedMilli, recorded);
        }
      }
    }
    // ★★ F-2（§一.9 分级）：**业务拒绝 = INFO**（不必开 DEBUG 就能分辨"没有服务卖"与"运力不足"）。
    //   ★ 只在真的有量被拦（blocked > 0）时发一行 —— 缺省世界（无牌价、无跨格需求）一行不打。
    if (blocked > 0L && MerchantCapacityPool.NO_MERCHANT_HOUSEHOLD_REASON.equals(detail)) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "LOGISTICS_SERVICE_SUPPLY_REJECTED",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  ctx.round.day,
                  "reason",
                  detail,
                  "commodity",
                  commodity.value(),
                  "sellerHex",
                  sellerHex,
                  "buyerHex",
                  buyerHex,
                  "truncatedMilli",
                  blocked));
    }
    if (MARKET.isDebugEnabled() && blocked > 0L) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MERCHANT_CAPACITY_LANE_BLOCKED",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  ctx.round.day,
                  "commodity",
                  commodity.value(),
                  "sellerHex",
                  sellerHex,
                  "buyerHex",
                  buyerHex,
                  "truncatedMilli",
                  blocked,
                  // ★★ D-1b：本落点**真正记进两个槽位**的量（0 = 这条 lane 的这份未服务量已由别的落点记过 ⇒ 未重复计入）。
                  "recordedMilli",
                  recorded,
                  "reason",
                  detail));
    }
  }

  /**
   * ★★ <b>M-A2：本轮因运力未获服务的货物量（毫商品）</b>—— 各买槽 {@code capacityTruncatedMilli}（M-A1 的 V-20 既有读数） 之和。
   *
   * <p>★ 口径：那部分货<b>不成交、不成债、不计价</b>（K-4 / Q-27）—— 它在撮合里被收缩掉，这里只是把同一个事实 读数化进需求簿的轮总（不重复记账、不写状态）。
   */
  static long goodsBlockedByCapacity(MatchContext ctx) {
    long blocked = 0L;
    for (BuySlot buy : ctx.buys) {
      if (buy.capacityTruncatedMilli > 0L) {
        blocked = Math.addExact(blocked, buy.capacityTruncatedMilli);
      }
    }
    return blocked;
  }

  /**
   * ★★ <b>A2：服务交付 —— 把这条 lane 上被买走的运输服务从卖方（跑商家户）的货物账里消耗掉</b>（设计书 §3.3/§5 I-H2）。
   *
   * <pre>
   * 逐条承运条目（{@code allocation.choices()}，含被免运费/自承运的条目 —— 服务是**物理上真的发生了**）：
   *   服务量 = {@code CarrierChoice.consumedWorkMilli()}（= CapacityDemand.workConsumedBy(货量, 本 lane 耗用‰)，既有算式）
   *   落点   = {@link EconomySettlement#consumeForLoss}（**非换手损耗的唯一写口**：账户减 + 损耗账加同址 ⇒ Σ余额 + losses 守恒）
   *   账     = {@link HaulService#SERVICE_CONSUMED_ACCOUNT}（{@code market-haul-service}，与货损/工具磨损分开）
   * </pre>
   *
   * <p>★★ <b>为什么在"动任何账之前"就交付</b>：它必须早于货腿/钱腿 —— 取不到服务货时本笔成交当场放弃（{@code executeTrade} 直接 {@code
   * return 0}），库里一个字节都还没动 ⇒ "买不到 ⇒ 该笔不成交（具名归因，fail-closed）"是**完整**的， 不是"发了货再补一张欠条"。
   *
   * <p>★★ <b>取不到货 = 契约故障（ERROR 不降级）</b>：运力池的运力预算**就是**该户当刻的 {@code haul} 可用量 （{@link
   * MerchantCapacityPool} 的服务口径），而本次交付紧跟在该次分配之后、在同一张活表上 ⇒ 正常情况下恒能取到。 取不到只有两种可能：账被别处改了、或池的预算不是从货来的
   * —— 两者都必须当场可见（ERROR + 计数），绝不静默少扣。
   *
   * @return 实际消耗的服务总量（毫服务）；<b>-1 = 有一条被 fail-closed 挡下</b>（调用方必须放弃本笔成交）
   */
  static long deliverHaulService(
      MatchContext ctx, RouteContext route, MerchantCapacityPool.CarrierAllocation allocation) {
    MarketRound round = ctx.round;
    // ★★★ D-032 C7b2：活动运输计划路径只消耗本轮瞬态 work capacity；不扣 householdGoods[haul]、
    //   不建旧服务库存/loss，也不产生 HAUL_SERVICE_DELIVERY_FAULT。返回实际 delivered work 供读数。
    if (ctx.transportRoundPlan != null) {
      long deliveredWork = 0L;
      for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
        long work = choice.consumedWorkMilli();
        if (work <= 0L) {
          continue;
        }
        deliveredWork = Math.addExact(deliveredWork, work);
        ctx.haulServiceSoldMilli = Math.addExact(ctx.haulServiceSoldMilli, work);
        if (EconomyLog.trace().isTraceEnabled()) {
          EventLog.channel(EconomyLog.trace())
              .trace(
                  LogEvent.of(
                      "TRANSPORT_WORK_DELIVERED",
                      EconomyLogSource.ECONOMY_ORGANIZATION,
                      "day",
                      round.day,
                      "household",
                      choice.household().value(),
                      "commodity",
                      route.commodity().value(),
                      "fromHex",
                      route.from(),
                      "toHex",
                      route.to(),
                      "goodsQuantityMilli",
                      choice.quantityMilli(),
                      "workMilli",
                      work));
        }
      }
      return deliveredWork;
    }
    long total = 0L;
    for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
      long service = choice.consumedWorkMilli();
      if (service <= 0L) {
        continue;
      }
      EconomySettlement.LossConsumption consumed =
          EconomySettlement.consumeForLoss(
              round.householdGoods,
              round.householdFrozenGoods,
              round.ledger,
              HaulService.SERVICE_CONSUMED_ACCOUNT,
              choice.household(),
              HaulService.HAUL_COMMODITY,
              service);
      if (consumed.consumedMilli() <= 0L) {
        EventLog.channel(MARKET)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERY_FAULT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "hex",
                    choice.hex(),
                    "fromHex",
                    route.from(),
                    "toHex",
                    route.to(),
                    "neededServiceMilli",
                    service,
                    "stockMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "availableMilli",
                    consumed.availableMilli(),
                    "reason",
                    "haul-service-goods-missing-at-delivery-no-trade"));
        return -1L;
      }
      total = Math.addExact(total, consumed.consumedMilli());
      // ★★ A2：轮级读数 = **物理上真的交付了多少服务**（含被免运费 / 自承运的条目 —— 服务照跑、只是不收钱）。
      ctx.haulServiceSoldMilli = Math.addExact(ctx.haulServiceSoldMilli, consumed.consumedMilli());
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "HAUL_SERVICE_DELIVERED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "commodity",
                    route.commodity().value(),
                    "fromHex",
                    route.from(),
                    "toHex",
                    route.to(),
                    "goodsQuantityMilli",
                    choice.quantityMilli(),
                    "serviceMilli",
                    consumed.consumedMilli(),
                    "stockBeforeMilli",
                    consumed.stockMilli()));
      }
    }
    return total;
  }

  /**
   * ★★ <b>M-A1/M-A2/M-C：把一票跨格运费分摊成逐提供者（家户）的运费金额</b>。
   *
   * <pre>
   * ⓪ 【M-C】**自运自货**（承运方是纯商号 ∧ 货主是纯商号）⇒ **整条豁免**（H-A/H-G："商号自己买东西不计运费"、
   *    "只有纯商号能免运费拿货"），该条的运费既不进实收、也不进未收；它按"成本直接按劳动力计"落进利润读数的
   *    劳动力成本腿（见 {@link #settleHaulRuns}）。★ 免掉的金额本身作为**具名读数**记录，不铸任何钱腿；
   *    ★ 其余情形（含 H-F 的"顺便跑商"承担的运力）⇒ 运费**独立计算**，算式一字不改。
   * ① 自承运条目（carrier == 买方 actor）整条跳过：不铸自转移、不计实收、不记未收（P10.9 口径保留）；
   * ② 逐条按**该提供者的限价**算单位运费 = 商品基础费 × (1000 + 路线费率‰) × (1000 + 限价‰)（算式一字不改；
   *    缺省口径下限价 == 派生承运成本 ⇒ 与 M-A1 逐值相同）；
   * ③ 实际可收运费 = min(非自承运部分的名义运费上限, Σ 各条按自身限价的运费)；
   * ④ 【报价口径】名义上限没被顶到 ⇒ 逐条按自己的限价收（M-A2：这才是"按提供者的报价买运力"）；
   *    【缺省口径 / 上限被顶到】⇒ 按承运量占非自承运总量的比例 floor 分摊，顺序里**最后一条实际承运条目拿余数**
   *    ⇒ Σ各条金额 == 实际可收运费，且 ≤ 该票 nominal freight（M-A1 原样，逐值不变）；
   * ⑤ 金额为 0 的条目不产生转移（仍保持守恒）。
   * </pre>
   *
   * <p>★ 分摊顺序 = select 返回顺序（报价口径：限价升序 → 议价权降序 → 家户 id 升序；缺省口径：议价权降序 → 家户 id 升序）， 不读时钟/随机 ⇒
   * 同输入逐值确定（I7）。
   *
   * <p>★★ <b>P-T5b：全部分摊都在<b>买方支付币</b>上做</b>（运费腿铸在 {@code buy.currency} 上）—— 逐条的单位运费经 {@link
   * BuySlot#payAmountOf} 折一次（同币 ⇒ 原样），说不出价的条目不参与（整票退回空表，fail-closed）。 自承运判据因此从"买方 actor"改为直接读
   * {@code buy.buyer.actor}（同一事实，不再多传一个入参）。
   */
  static List<FreightCharge> carrierChargeSplit(
      MerchantCapacityPool.CarrierAllocation allocation,
      BuySlot buy,
      RouteContext route,
      long commodityBaseMilli,
      boolean buyerIsPureMerchant) {
    List<MerchantCapacityPool.CarrierChoice> choices = allocation.choices();
    long chargeableQuantity = 0L;
    long effectiveFreightSum = 0L;
    int lastChargeable = -1;
    long[] ownFreight = new long[choices.size()];
    for (int i = 0; i < choices.size(); i++) {
      MerchantCapacityPool.CarrierChoice choice = choices.get(i);
      if (choice.carrier().equals(buy.buyer.actor())) {
        continue; // 自承运：该条的运费不进入实收，也不进入未收
      }
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        continue; // ★★ M-C（H-A/H-G）：**自运自货**（承运方与货主都是纯商号）⇒ 免运费；
        //   免掉的金额与劳动成本在 settleHaulRuns 里记读数（H-F：非纯商号承担的运力照收，一条不改）
      }
      lastChargeable = i;
      chargeableQuantity = Math.addExact(chargeableQuantity, choice.quantityMilli());
      // ★★ P-T5b：逐条的单位运费同样折成**买方支付币**（腿铸在 buy.currency 上）；说不出价 ⇒ 整票不收运费腿
      //   （fail-closed：与 chargesAtMost / executeTrade 的判据同口径，绝不按 1:1 顶上）。
      //   ★★ M-A2：承运成本项改用**该提供者自己的限价**（`choice.askPerMille()`）——缺省口径下它就等于
      //   `carrierCostPerMille(tier)`（逐值不变），报价口径下它就是"从最低价起买"的成交价。
      long unitFreight =
          buy.payAmountOf(
              route.haulService()
                  // ★★ A2：服务成市 ⇒ 成交价 = **服务牌价**（本 lane 已按它折出 route.freightPerUnit，
                  //   预判与结算同源）；承运成本限价那一套不再进入**钱**的算式（只进"按最低价提供者买"的选择序）。
                  ? route.freightPerUnit()
                  : freightUnitMilli(
                      commodityBaseMilli, route.freightRatePerMille(), choice.askPerMille()));
      if (unitFreight < 0L) {
        return List.of();
      }
      ownFreight[i] = freightOf(choice.quantityMilli(), unitFreight);
      effectiveFreightSum = Math.addExact(effectiveFreightSum, ownFreight[i]);
    }
    if (chargeableQuantity <= 0L) {
      return List.of();
    }
    long nominalCap = freightOf(chargeableQuantity, buy.payAmountOf(route.freightPerUnit()));
    long collectible = Math.min(nominalCap, effectiveFreightSum);
    List<FreightCharge> charges = new ArrayList<>();
    if (allocation.priced() && collectible == effectiveFreightSum) {
      // ★★ M-A2 报价口径：名义上限没被顶到 ⇒ **逐条按各自的限价收**（这才是"按提供者的报价买运力"；
      //   Σ == effectiveFreightSum == collectible ⇒ 守恒与 M-A1 同一式子）。任何一条说不出价都不会走到这里
      //   （上面已 fail-closed 退空表）。★ 缺省口径不进这一支：那样才能保证"无报价 ⇒ 逐值不变"是结构性成立，
      //   而不是"算出来恰好相等"。
      for (int i = 0; i < choices.size(); i++) {
        if (ownFreight[i] <= 0L) {
          continue;
        }
        MerchantCapacityPool.CarrierChoice choice = choices.get(i);
        charges.add(
            new FreightCharge(
                choice.carrier(),
                ownFreight[i],
                choice.consumedWorkMilli(),
                choice.household(),
                choice.hex(),
                choice.pureMerchant()));
      }
      return List.copyOf(charges);
    }
    long assigned = 0L;
    for (int i = 0; i < choices.size(); i++) {
      MerchantCapacityPool.CarrierChoice choice = choices.get(i);
      if (choice.carrier().equals(buy.buyer.actor())) {
        continue;
      }
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        continue; // ★★ M-C：免运费条目在**两条分摊分支**里都要跳过（与走哪一支无关）
      }
      long amount;
      if (i == lastChargeable) {
        amount = collectible - assigned; // 余数全部给顺序里最后一条实际承运条目
      } else {
        amount = Math.multiplyExact(collectible, choice.quantityMilli()) / chargeableQuantity;
        assigned = Math.addExact(assigned, amount);
      }
      if (amount > 0L) {
        charges.add(
            new FreightCharge(
                choice.carrier(),
                amount,
                choice.consumedWorkMilli(),
                choice.household(),
                choice.hex(),
                choice.pureMerchant()));
      }
    }
    return List.copyOf(charges);
  }

  /**
   * 一条实际要铸的运费腿（M-A1）：收款**家户** actor + 金额 + 本条的**服务量** + 归属（家户/发货格，日志用）。
   *
   * <p>★ <b>A2：{@code serviceMilli} = 这条承运消耗掉的运输服务（毫服务 = 毫商品·程）</b>—— 它就是 {@code
   * CapacityDemand.workConsumedBy(货量, 本 lane 耗用‰)} 的既有结果（{@code CarrierChoice.consumedWorkMilli}），
   * 服务成交时按它从卖方货物账扣（{@link HaulService#SERVICE_CONSUMED_ACCOUNT}）。★ 缺省（服务不成市）路径下它只作读数， 不进任何算式。
   */
  record FreightCharge(
      ActorRef carrierActor,
      long amountMilli,
      long serviceMilli,
      HouseholdId household,
      HexCoord hex,
      boolean pureMerchant) {
    FreightCharge {
      Objects.requireNonNull(carrierActor, "carrierActor");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("FreightCharge 金额必须为正: " + amountMilli);
      }
      if (serviceMilli < 0L) {
        throw new IllegalArgumentException("FreightCharge 的服务量不得为负: " + serviceMilli);
      }
    }
  }

  // ── ★★★ A3：跑商落地（工具门槛/烧工具/平行利润读数**整族已退役**）────────────────────────────

  /**
   * ★★★ <b>A3（2026-10-10）：一笔成交里的每一条跑商落地 —— 现在只剩**逐笔 TRACE 读数**</b>。
   *
   * <pre>
   * 退役（本方法原有三件事，两件已删、一件改归属）：
   * ① 工具一次性消耗（每趟 1,000 毫工具，写进 market-merchant-haul 损耗账）  ⇒ **删**（{@code MerchantHaul} 整族退役）
   * ② "缺工具 ⇒ 该次跑商不成立"的提交侧归因（tool-frozen / tool-short）      ⇒ **删**（门槛不存在了）
   * ③ 利润读数（劳动成本腿 / 工具损耗腿 / 免运费读数 → MerchantProfitBook）   ⇒ **改归属**：收益走标准
   *    {@code EnterpriseProfitBook}（服务成交的钱腿 reason = {@code MARKET_TRADE}，见 {@link #executeTrade}
   *    的运费腿；收入按卖方组织归集 —— {@code EnterpriseProfitBook} 的 MARKET_TRADE 分支）
   * 保留：逐笔 TRACE（§一.9）—— 本条承运了多少、耗了多少服务、折算多少劳动小时、免了多少运费。
   * </pre>
   *
   * <p>★★ <b>为什么工具改由产业侧消耗（设计书 §3.2 / I-H6）</b>：跑商是**生产方式** ⇒ 它的生产资料由产业声明的 {@code cycleInputPerUnit}
   * 在生产阶段现扣（{@code drawCycleInputs}），并在挂单面按标准路径预留 （{@code
   * necessaryInputsOf}）。市场轮不再"按趟烧工具"——那是把产业投入与市场准入混成两套口径（A1/A2 期间并存 = 双扣）。
   *
   * <p>★ 只在**协调器**路径被调用（{@code allocation != null} ⇒ {@code route != null} ⇒ 串行撮合）。
   */
  static void settleHaulRuns(
      MatchContext ctx,
      BuySlot buy,
      RouteContext route,
      MerchantCapacityPool.CarrierAllocation allocation,
      boolean buyerIsPureMerchant) {
    MarketRound round = ctx.round;
    long baseMilli = commodityFreightBaseMilli(ctx.topology, route.commodity());
    for (MerchantCapacityPool.CarrierChoice choice : allocation.choices()) {
      // ★★ M-C 保留：免运费读数（H-A/H-G）—— **自运自货**（承运方 ∧ 货主都是纯商号）⇒ 该条运费不铸，
      //   这里只算"本应付多少"作为 TRACE 读数（同一张单位运费算式 + 该户限价）。
      long waived = 0L;
      if (choice.pureMerchant() && buyerIsPureMerchant) {
        long unitFreight =
            buy.payAmountOf(
                // ★★ A2：服务成市 ⇒ "本应付多少"同样按服务牌价口径（预判/实收/免运费三处同一个数）。
                route.haulService()
                    ? route.freightPerUnit()
                    : freightUnitMilli(
                        baseMilli, route.freightRatePerMille(), choice.askPerMille()));
        if (unitFreight > 0L) {
          waived = freightOf(choice.quantityMilli(), unitFreight);
        }
      }
      long laborHoursMilli =
          ctx.carrierPool.laborHoursOf(choice.household(), choice.consumedWorkMilli());
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "MERCHANT_HAUL_RUN",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    round.day,
                    "household",
                    choice.household().value(),
                    "pureMerchant",
                    choice.pureMerchant(),
                    "commodity",
                    route.commodity().value(),
                    "from",
                    route.from(),
                    "to",
                    route.to(),
                    "quantityMilli",
                    choice.quantityMilli(),
                    "serviceMilli",
                    choice.consumedWorkMilli(),
                    "laborHoursMilli",
                    laborHoursMilli,
                    "waivedFreightMilli",
                    waived,
                    "currency",
                    buy.currency.value()));
      }
    }
  }

  // ★★★ A3（2026-10-10）：{@code capitalOccupancyMicro(...)} 已随 {@code MerchantProfitBook} 退役删除
  //   —— 它只服务那本平行读数的"本钱占用"腿（毫级通常为 0），不是任何账务事实。

  /** 把刚记到买方名下的量移出会话余额（在途资产的装载；到货日反向落回）。 */
  static void loadInTransit(MatchContext ctx, BuySlot buy, long quantity) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    HouseholdId key = buy.buyer.household();
    long stock = householdStockOf(round.householdGoods, key, commodity);
    if (stock < quantity) {
      throw new IllegalStateException(
          "装载在途时买方库存不足（货腿刚记上，账不应漂开）：家户=" + key + " 商品=" + commodity + " 余额=" + stock);
    }
    setHouseholdStock(round.householdGoods, key, commodity, stock - quantity);
  }

  /**
   * ★★ <b>D-027：单 hex 即时贸易损耗的「非换手扣减」落点</b>（spec §3.2 的守恒实现口径）。
   *
   * <p>货腿已由唯一 applier 按<b>毛量</b>记到买方名下；这里把损耗那一份从买方会话余额移走 —— 它<b>不换手给任何 人</b>，与 {@link
   * #loadInTransit} 把货物移进在途批次是同一类"货物离开账户但未换手"的落点（唯一区别：在途
   * 批次日后会反向落回，损耗不再回来）。扣减本身不产生转移、不铸币、不写第二本账；损耗的唯一凭据是紧随其后的 {@code
   * ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, …)}。
   *
   * <p>买方是家户 ⇒ 扣家户账；买方是经营者 ⇒ 扣经营者账。扣前校验买方余额 {@code >= lossMilli}（货腿刚按毛量 入账，正常必然够）；不足时抛具名 {@link
   * IllegalStateException}，不静默夹 0、不造负库存。
   */
  static void deductBuyerLossNoTransfer(MatchContext ctx, BuySlot buy, long lossMilli) {
    MarketRound round = ctx.round;
    CommodityId commodity = buy.order.commodity();
    HouseholdId key = buy.buyer.household();
    long stock = householdStockOf(round.householdGoods, key, commodity);
    if (stock < lossMilli) {
      throw new IllegalStateException(
          "扣减单 hex 贸易损耗时买方家户库存不足（货腿刚按毛量记上，账不应漂开）：家户="
              + key
              + " 商品="
              + commodity
              + " 余额="
              + stock
              + " 损耗="
              + lossMilli);
    }
    setHouseholdStock(round.householdGoods, key, commodity, stock - lossMilli);
  }

  // ── 会话副本的最小读写（本类不引入第二个 applier；这里只是把"哪张表、哪个键"说清）──────────────

  static long householdStockOf(
      Map<HouseholdId, Map<CommodityId, Long>> goods, HouseholdId key, CommodityId commodity) {
    return goods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  static void setHouseholdStock(
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      HouseholdId key,
      CommodityId commodity,
      long value) {
    Map<CommodityId, Long> inner = new LinkedHashMap<>(goods.getOrDefault(key, Map.of()));
    inner.put(commodity, Math.max(0L, value));
    goods.put(key, inner);
  }

  /**
   * 同一对 (sellerHex,buyerHex) 上的成交单价：取卖方**格价表里的参考价**的上界（M2.6：成交仍按参考价，买卖双方各自比自己的 bid/ask
   * 限价占优；多价表时宁高不低，不让卖家亏本）。★ 限价过滤在 {@link #matchRoute} 里逐买方判，不走这里。
   *
   * <p>★★ P-T1c：参考价一律取卖方格价表（{@link MarketTable#priceOf}）—— 区级参考价覆盖已删（设计书 §16）， 与 {@link #ordersFor}
   * 同一条口径，区内/跨区不漂开。
   */
  static long unitPriceOf(MatchContext ctx, List<SellSlot> sells, CommodityId commodity) {
    long price = 0L;
    for (SellSlot sell : sells) {
      if (sell.order.commodity().equals(commodity)) {
        price = Math.max(price, sell.market.priceOf(commodity));
      }
    }
    return price;
  }

  /**
   * ★★ <b>商品基础运费（毫计价货币 / 商品单位 / 程）的唯一读取口</b> —— 读<b>当刻状态表</b>（{@link MarketTopology} 携带的 {@code
   * EconomyData.commodityFreightBaseMilli} 快照；GM 可改）。
   *
   * <p>★★ <b>它从硬编码 dispatch 改成读状态（2026-10-09 用户裁定「甲」后的纠正）</b>：改前粮 1 / 纤维 1 / 布 2 / 工具 3
   * 是写死在这里的常量（源自 {@code d7604ea5}），GM 改不了、且与 F 批后加的"费率乘数维"构成<b>两个商品维相乘</b>。
   * 现在这组分档降级为"表里没有该商品"时的<b>具名缺省</b>（{@link CommodityFreightBase#legacyMilli(CommodityId)}，
   * 全仓唯一拼写点）⇒ 空表（旧档 / 新世界 / 夹具）逐值等于改动前（不变量 I-F1）。
   */
  static long commodityFreightBaseMilli(MarketTopology topology, CommodityId commodity) {
    Objects.requireNonNull(topology, "topology");
    return topology.commodityFreightBaseMilliOf(commodity);
  }

  /**
   * ★★ <b>A2：某格运输服务的牌价（毫该格计价货币 / 商品单位服务）</b>；<b>该格没给 {@code haul} 定价 ⇒ 0</b>。
   *
   * <pre>
   * 0 ⇒ 该格**服务不成市** ⇒ lane 的单位运费走既有 {@code freightUnitMilli}（承运成本口径）、运费腿铸 CARRIER_FEE
   * &gt;0 ⇒ 该格服务成市   ⇒ 单位运费走 {@link HaulService#unitFreightMilli}（服务牌价口径）、运费只走服务商品成交
   * </pre>
   *
   * <p>★ 判据的唯一拼写点是 {@link HaulService#pricedAt}（本方法只负责"取哪一格的市场"）；没有市场的格 ⇒ 0（不成市）。
   */
  static long servicePriceAt(MatchContext ctx, HexCoord hex) {
    // ★★★ D-032 C7b2：活动运输计划路径的价格权威 = C7a 每市场区锚价（plan observation 冻结同一份值）；
    //   0 合法，不再读旧 MarketTable.prices[haul]。
    if (ctx.transportRoundPlan != null) {
      return ctx.transportAnchorPriceAt(hex);
    }
    MarketTable market = ctx.tableAt(hex);
    if (!HaulService.pricedAt(market)) {
      return 0L;
    }
    return market.priceOf(HaulService.HAUL_COMMODITY);
  }

  /**
   * ★★ <b>RC8a（2026-10-20）：这条 lane 走不走"运输服务商品"口径 = 发货格有 haul 牌价 ∧ 本格当前有可交付 haul 服务货</b>。
   *
   * <pre>
   * false（未定价 / 没有市场 / 本格无任何可交付 haul 服务货）
   *       ⇒ 简单生产性运力 + 既有 {@code freightUnitMilli}（商品基础费 × 路线费率 × 承运成本）与 {@code CARRIER_FEE} 腿
   * true  ⇒ 单位运费由服务牌价给出（{@link HaulService#unitFreightMilli}）、运费腿改走服务商品成交（I-H5）
   * </pre>
   *
   * <p>★★ <b>为什么必须与 {@link ShipmentSettlement#haulServiceHexes(SettlementDayContext)} 同源</b>：运力池已在
   * {@code MerchantCapacityCore} 按同一个"服务成市格集"分流 —— 服务成市格只给可交付服务货容量，其余格给劳动+工具容量。
   * 如果这里仍只按"有没有牌价"判，就会让一条**没有可交付服务货**的 lane 走服务交付腿，而池给的是生产性容量 ⇒ 交付点取不到 {@code haul} 货 ⇒ {@code
   * HAUL_SERVICE_DELIVERY_FAULT} + 本笔成交作废。判据必须同源，才能让 "有牌价但无货"的 hex 走 CARRIER_FEE。
   *
   * <p>★ <b>为什么不按"牌价 &gt; 0"</b>：本仓对"从未定价 ⇒ 不交易"与"明确 0 价 ⇒ 免费交易"有明文区分（{@code MarketTable.hasPrice}
   * / {@code isFree}）—— 用价格数值当开关会把"GM 明确设成免费服务"静默读成"这一格没有服务市场"。
   */
  static boolean haulServiceAt(MatchContext ctx, HexCoord hex) {
    // ★★★ D-032 C7b2：活动运输计划路径只看“该 shippingHex 在 plan 中是否有本地 C6 容量”。
    //   不回改为读旧 haul 服务库存，也不把旧 MarketTable haul 牌价当开关。
    if (ctx.transportRoundPlan != null) {
      return ctx.transportPlanHasLocalCapacity(hex);
    }
    if (!HaulService.pricedAt(ctx.tableAt(hex))) {
      return false;
    }
    // ★ RC8a 判据 ②：与 ShipmentSettlement.haulServiceHexes(SettlementDayContext) 同一拼写点 ——
    //   本格至少一户有可交付 haul 服务货。市场轮的 goods/frozenGoods 就是账户活表（同一批 Map 实例）。
    return ShipmentSettlement.hasDeliverableHaulStockAt(
        ctx.round.householdEconomies,
        ctx.round.householdGoods,
        ctx.round.householdFrozenGoods,
        hex);
  }

  /**
   * ★★ <b>承运方运营成本加价（‰ / 每档 tier 城区当量）</b>：脚夫与商人"要吃饭"的粗估表示 —— 承运不是免费的 公共服务，运费里必须含这笔成本。PORTER 25‰ /
   * SELF_EMPLOYED 50‰ / BOSS 100‰（数值是粗估、可由 GM 改）。
   */
  static final long MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP = 25L;

  /** 没有运力池（没有"选了跑商的家户"）时的默认承运成本（‰）；也用于撮合前的可负担量预判。 */
  static final long MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE = 25L;

  /**
   * ★★ <b>某提供者的承运成本（‰）= 派生 tier 城区当量 × 每档步长</b>。
   *
   * <p>★ M-A1：tier 不再是持久状态（{@code MerchantFirm} 已退役）—— 它由运力规模<b>派生</b> （{@link
   * MerchantCapacity#tierOf(long)}），本条算式因此只剩"派生读数 → 成本"这一步，算式本身逐字保留。
   */
  static long carrierCostPerMille(MerchantPolicy.MerchantTier tier) {
    Objects.requireNonNull(tier, "tier");
    return Math.multiplyExact(
        (long) tier.districtUse(), MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>撮合前可负担性预判用的承运成本（‰）</b>：有运力池时取<b>上界</b>，保证 {@code pairUp} 按它规划的钱 ≤
   * 实际逐提供者收费；没有跑商家户（池空）沿用小默认值。
   *
   * <pre>
   * 池空（没有跑商家户）           ⇒ 小默认值 25‰（逐值不变）
   * 池非空 + 缺省口径（无报价）    ⇒ 最高档 tier 的成本上界（BOSS = 4 × 25 = 100‰；逐字等于 M-A1）
   * 池非空 + 报价口径（M-A2）      ⇒ 本轮所有提供者的**最高限价**（= max(自报价 + 上门附加费)）
   * </pre>
   *
   * <p>★ 报价口径为什么取"最高限价"而不是"最高档 tier 成本"：限价可能高于 BOSS 档成本（含上门附加费），也可能被逐户
   * 覆写拉高；取实际最高限价才是真正的上界。它同时是"买方名义运费上限"的口径来源（{@code carrierChargeSplit}），
   * 因此买方永远不会因报价而被收超过计划的钱（fail-closed，I-C1 守恒不变）。
   */
  static long plannedCarrierCostPerMille(MatchContext ctx) {
    if (ctx.carrierPool.isEmpty()) {
      return MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE;
    }
    if (ctx.carrierPool.isPriced()) {
      return ctx.carrierPool.maxAskPerMille();
    }
    return Math.multiplyExact(
        (long) MerchantPolicy.MerchantTier.BOSS.districtUse(),
        MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP);
  }

  /**
   * ★★ <b>单位运费（毫计价货币 / 商品单位）的唯一算式</b>（2026-10-09 与货款价格解耦；算式逐字保留）：
   *
   * <pre>
   * unit = max(1, ⌈ 商品基础费 × (1000 + 路线费率‰) × (1000 + 承运成本‰) ÷ 1,000,000 ⌉ )
   * </pre>
   *
   * <p>三项来源：① <b>商品基础费</b>（{@link #commodityFreightBaseMilli}：读当刻状态表 {@code
   * EconomyData.commodityFreightBaseMilli}，GM 可改；缺键 ⇒ 现行硬编码分档）；② 路线费率（{@link
   * MarketTopology#freightPerMilleBetween} 的里程/辐射/道路 —— <b>没有</b>商品维）；③ 承运成本（{@link
   * #carrierCostPerMille}，脚夫/商号要吃饭的粗估）。<b>不含</b> {@code unitPrice} —— 0 价免费商品仍产生正运费。
   *
   * <p>★★ <b>{@code 0} 基础费仍收 1 毫</b>：末尾的 {@code max(1, …)} 把"该商品免基础费"抬到 1 毫 —— 这是既有语义
   * （本批**不改**）：{@code baseMilli = 0} 是"免基础费"的明确表达，不是"免费运输"。
   */
  static long freightUnitMilli(
      long commodityBaseMilli, long ratePerMille, long carrierCostPerMille) {
    if (commodityBaseMilli < 0L) {
      throw new IllegalArgumentException(
          "commodityBaseMilli 不得为负（0 = 该商品免基础费）: " + commodityBaseMilli);
    }
    if (ratePerMille < 0L) {
      throw new IllegalArgumentException("ratePerMille 不得为负: " + ratePerMille);
    }
    if (carrierCostPerMille < 0L) {
      throw new IllegalArgumentException("carrierCostPerMille 不得为负: " + carrierCostPerMille);
    }
    long routeFactor = Math.addExact(1000L, ratePerMille);
    long carrierFactor = Math.addExact(1000L, carrierCostPerMille);
    long product =
        Math.multiplyExact(Math.multiplyExact(commodityBaseMilli, routeFactor), carrierFactor);
    return Math.max(1L, ceilDiv(product, 1_000_000L));
  }

  /**
   * 一条成交量的运费：{@code ⌈数量(毫商品) × 单位运费(毫钱/商品单位) ÷ 1000⌉}（毫计价货币）。 ★ 单位运费由 {@link #freightUnitMilli}
   * 给出；<b>不看货款单价</b>。
   */
  static long freightOf(long quantityMilli, long unitFreightMilli) {
    if (quantityMilli <= 0L || unitFreightMilli <= 0L) {
      return 0L;
    }
    return ceilDiv(Math.multiplyExact(quantityMilli, unitFreightMilli), 1000L);
  }
}
