package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_ADAPTIVE_ALPHA_PER_MILLE;
import static io.mosire.simos.economy.time.MarketConstants.MARKET_ADAPTIVE_PRICING_ENABLED;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketRosterBook.orderedCommodities;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketPricer {

  private MarketPricer() {}

  /** 本进程当前的报价模式（M2.6 的开关只有一个：2026-10-07 起默认自适应）。 */
  static PriceMode priceMode() {
    return MARKET_ADAPTIVE_PRICING_ENABLED ? PriceMode.ADAPTIVE : PriceMode.FIXED;
  }

  /**
   * ★★ <b>M2.6 自适应的一轮结果</b>：{@code marketTables} = 更新后的价格表（未更新时就是入参本身）；{@code updates} = 逐 (集散节点,
   * 商品) 的改价记录（读数组件原样发出）。
   */
  record AdaptivePrices(
      List<MarketReport.PriceUpdate> updates,
      Map<MarketZoneId, MarketTable> marketTables,
      // ★★ W1：本轮逐市场逐商品的 **D/S 累加器**（[0] = 需求、[1] = 供给）—— 自适应定价本来就在算这两个量
      //   （改动前用完即弃）；统计簿直接读它，不另造一套口径（约束设计书 §6.1 W1②）。
      Map<MarketZoneId, Map<CommodityId, long[]>> quantities) {
    AdaptivePrices {
      updates = List.copyOf(updates);
      Objects.requireNonNull(marketTables, "marketTables");
      Objects.requireNonNull(quantities, "quantities（没有 D/S 就给空表）");
      // ★ 保序不可变（不用 Map.copyOf：迭代序不是内容的纯函数）；值是不可变 record。
      marketTables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(marketTables));
    }
  }

  /** ★ W1：一个 hex 所属市场的 id（不在任何市场里 ⇒ {@code null}）。 */
  static MarketZoneId marketIdAt(MatchContext ctx, HexCoord hex) {
    if (hex == null || ctx.topology == null || !ctx.topology.contains(hex)) {
      return null;
    }
    return new MarketZoneId(ctx.topology.regionOf(hex).node().nodeId());
  }

  /**
   * ★★ <b>按供需逐区改价</b>（M2.6 自适应；开关判据在本方法内，调用点无条件）—— 开关关时立即原样交回入参价格表。
   *
   * <pre>
   * D &gt; S（缺货）: r = (D − S) / max(1, S)；step‰ = min(α, ⌊α·r⌋)；p' = max(p + 1, ⌊p×(1000 + step)/1000⌋)（p &gt; 0；无上限）
   * S &gt; D（滞销）: r = (S − D) / max(1, D)；step‰ = min(α, ⌊α·r⌋)；p' = max(0, min(p − 1, ⌈p×(1000 − step)/1000⌉))（触底 0；★ v3.10 与涨侧对称）
   * D = S:         p' = p                                                   （缺省中性：不失衡的世界逐值不变）
   * </pre>
   *
   * ★ 算式本体与逐值口径全部在 {@link #adaptiveNextPrice}（唯一拼写点）；本方法只负责**汇总 D/S 与逐区取 α**。
   *
   * <p>★★ <b>需求口径（W2-fix，控制方裁定 v3.5 §2.1）：D = 全额想要量，一个量都不扣</b> —— {@code demand} = 本轮 **已生成**的买槽
   * {@code order.quantity()} 之和。改动前它被折回"按当前参考价真正付得起的量"（2026-10-09 的 防乘性暴涨补丁），随后又保留了 M-A1
   * 的"扣掉被运力截断的那一份"；两者现在**都已删除**（前者见下面买槽循环的注释， 后者见 v3.5 的 ★★ 裁定）。★ 只按绝对供需，不看本轮成交结果 ——
   * 成交受运力/运费/时限约束，那些是撮合期事实， 已在全额需求里；再扣一遍等于"运力没运走 ⇒ 没人要"。
   *
   * <p>★★★ <b>D/S 的不对称是刻意的（不许改回对称）</b>：
   *
   * <ul>
   *   <li><b>D 不扣</b>"运力截断量"：买家的"想要"就是全额需求，运力没运走 ≠ 没人要；
   *   <li><b>S 仍扣</b>{@code capacityTruncatedMilli}：供给侧只算**真能上架**的（运不出去的不算，不自我抵消）。
   * </ul>
   *
   * ⇒ 缺货压力只能从这条不对称里来 —— 若两侧同扣，"被运力/口岸拦下"的那部分会同时从 D 与 S 里消失、比值不变、
   * 价格不动，与用户"缺货价格应该逐步增加"直接矛盾。这就是"抢手能涨价"的**唯一通道**（设计书 §2.1 v3.5 的两条 ★★）。
   *
   * <p>★ <b>"口岸闸拦下的量"不新增、不重复计</b>：它**本来就在 D 里** —— D 取自买槽 {@code order.quantity()} 全额，
   * 而口岸闸拦下的是撮合期事实（只写 {@code remaining}/{@code blocked}，从不改 {@code order.quantity()}）。 逐 (区, 商品)
   * 的那一份只作**日志读数**（{@link #logDemandComposition}），不进任何算式。
   *
   * <p>★★ <b>区价 = 集散节点价</b>：W1 之后价表每区一张，"区内一价"是**结构上必然**（改价 = 本区一次 upsert）， 不再有逐成员对齐循环。
   */
  static AdaptivePrices adaptPrices(Map<MarketZoneId, MarketTable> marketTables, MatchContext ctx) {
    if (!MARKET_ADAPTIVE_PRICING_ENABLED) {
      // ★ 固定报价回退：开关关时价格表逐值原样带过、更新表为空。判断放在这里而不是调用点，保证方法在字节码里存在。
      return new AdaptivePrices(List.of(), marketTables, Map.of());
    }
    // 逐 (region, commodity) 汇总订单；region 用拓扑对象本身当键（它由 node+members 派生，等值即同区）。
    Map<MarketRegion, Map<CommodityId, long[]>> byRegion = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      // ★★ W2（2026-10-11）：**D 全算** —— 需求全额进价格信号。改动前这里把需求折回"按当前参考价真正付得起的量"
      //   （{@code affordable = 可付额 × 1000 ÷ 参考价}）：那是 2026-10-09 为压住"需求对价格不敏感 ⇒ 每轮乘性暴涨"
      //   加的红字补丁。W2 起"买不起"这个概念已经不存在（未付价款进债务、需求全额成交），故该折回**删除** ——
      //   取而代之的防失控闸门是 §2.1 的"每轮 ≤ α"（约束设计书 §2.1 末段的具名理由）。
      CommodityId commodity = buy.order.commodity();
      long quantity = buy.order.quantity();
      // ★★★ W2-fix（2026-10-11，控制方裁定 v3.5 §2.1）：**D = 全额想要量，一个量都不扣**。
      //   ★ 改动前这里还留着 M-A1（V-20）的"扣掉被运力截断的那一份"；v3.5 的 ★★ 条明确：
      //     **D 不扣"运力截断量"**（买家的"想要"就是全额需求；运力没运走 ≠ 没人要），**S 仍扣**（只算真能上架的）。
      //   ★★★ 这条**不对称是刻意的**：缺货压力只能从这条不对称里来 —— 若两侧都扣，被运力/口岸拦下的那部分
      //     会同时从 D 与 S 里消失 ⇒ D 与 S 一起缩小、比值不变 ⇒ 价格不动，与用户"缺货价格应该逐步增加"
      //     直接矛盾（这正是"抢手能涨价"的唯一通道）。S 侧一字未动（见下面卖槽循环）。
      //   ★ 与"口岸闸拦下的量"的关系：那部分**本来就在 D 里**（D 取自买槽 `order.quantity()` 全额，
      //     口岸闸拦下的是撮合期事实、只写 `remaining`/`blocked`，从不改 `order.quantity()`）⇒ 不新增、不重复计。
      byRegion.computeIfAbsent(buy.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(commodity, ignored -> new long[2])[0] +=
          quantity;
    }
    for (SellSlot sell : ctx.sells) {
      long sellable = sell.order.sellable();
      byRegion.computeIfAbsent(sell.region, ignored -> new LinkedHashMap<>())
              .computeIfAbsent(sell.order.commodity(), ignored -> new long[2])[1] +=
          // ★★ M-A1（V-20，W2-fix 后**保留不动**）：供给侧仍然剔除被运力截断的部分（上限 = 该槽可售量）。
          //   ★ 为什么 S 扣、D 不扣：这就是 v3.5 §2.1 那条 ★★ 的**刻意不对称**（见上面买槽循环的同一段注释）——
          //     "能上架"只算真能卖的（运不出去的不算），而"想要"是全额需求。两侧口径**不许**被改回对称。
          sellable - Math.min(sellable, sell.capacityTruncatedMilli);
    }
    // ★★ W1（价表每区一张）：**逐成员对齐循环随形状退役**。改动前沿 {@code region.members()} 逐格写回
    //   "同一张价表的副本"，并在 {@code next == reference} 时 {@code continue}（成员漂移永不被修）；
    //   现在改价就是改**本区那一张表**的一次 upsert —— 区内任何格读到的价结构上必然一致。
    LinkedHashMap<MarketZoneId, MarketTable> updated = new LinkedHashMap<>(marketTables);
    List<MarketReport.PriceUpdate> updates = new ArrayList<>();
    // ★★ W1：把"拓扑区对象 → 累加器"翻译成"市场 id → 累加器"（统计簿的键空间 = 价表的键空间）。
    Map<MarketZoneId, Map<CommodityId, long[]>> quantitiesByMarket = new LinkedHashMap<>();
    // ★★★ W7：运输服务的**派生需求**逐发货格取一次（{@link CapacityDemandBook}）—— 同一个量在下面按市场区求和。
    Map<HexCoord, Long> haulDemandByShippingHex = ctx.capacityDemands.demandWorkByShippingHex();
    Map<HexCoord, Long> haulServedByShippingHex = ctx.capacityDemands.servedWorkByShippingHex();
    for (MarketRegion region : ctx.topology.regions()) {
      MarketZoneId zoneId = new MarketZoneId(region.node().nodeId());
      MarketTable zoneMarket = updated.get(zoneId);
      if (zoneMarket == null) {
        continue; // ★ W1d：这个区没有价表条目 ⇒ 不交易（不是"坏状态"，是"缺表 = 这个区没有任何价格"）
      }
      Map<CommodityId, long[]> quantities = byRegion.getOrDefault(region, Map.of());
      // ★★★ W7（2026-10-11）：**把"运力需求"接进 §2.1 唯一的那套自适应定价**（禁另写一套算式）。
      //
      //   用户派单口径（逐字）：「让运力需求进入市场机制：至少让 haul（运输服务）参与 §2.1 的自适应定价
      //   （D = 该轮真实未满足运力量、S = 可交付运力），缺运力 ⇒ 运费上升（照 §2.1 唯一实现，禁另写一套）」。
      //
      //   <pre>
      //   D（本区）= Σ 本区**成员格作为发货格**这一轮派生出来的运输服务需求全额（毫商品·程）
      //              ★ 全额（含已服务的那一份）—— §2.1 的 D 语义就是"想要的总量"，见 CapacityDemandBook 的同一段口径
      //   S（本区）= Σ 本区成员格里承运方**可交付的运输服务货**（毫商品·程）
      //   接入点    = quantities[haul]（下面那个既有循环把它交给 adaptiveNextPrice —— 一个字面量的算式都不新写）
      //   </pre>
      //
      //   ★ 为什么服务不在订单簿里却还能有 D/S：服务的**需求**由其他商品的购买派生（不挂单，见 executeTrade），
      //     它的**供给**是商家户手上的服务货（MerchantCapacityPool 的运力预算）—— 两者都是每轮现算的派生量，
      //     在撮合与分配都做完之后（本方法的位置）已经齐备。
      //   ★ 缺省中性：服务未定价的区一个字节都不动（{@code hasPrice} 为假 ⇒ 不注入）；两侧都为 0 ⇒ 不注入
      //     ⇒ 既有世界（无运力、无需求）逐值不变。
      if (zoneMarket.hasPrice(HaulService.HAUL_COMMODITY)) {
        long haulDemandMilli = 0L;
        long haulSupplyMilli = 0L;
        long haulUnservedMilli = 0L;
        Map<HexCoord, Long> zoneDemandDetail = new LinkedHashMap<>();
        for (HexCoord member : region.members()) {
          long memberDemand = haulDemandByShippingHex.getOrDefault(member, 0L);
          long memberServed = haulServedByShippingHex.getOrDefault(member, 0L);
          haulDemandMilli = Math.addExact(haulDemandMilli, memberDemand);
          haulSupplyMilli =
              Math.addExact(haulSupplyMilli, Math.max(0L, ctx.carrierPool.totalCapacityAt(member)));
          haulUnservedMilli =
              Math.addExact(haulUnservedMilli, Math.max(0L, memberDemand - memberServed));
          if (memberDemand > 0L) {
            zoneDemandDetail.put(member, memberDemand);
          }
        }
        if (haulDemandMilli > 0L || haulSupplyMilli > 0L) {
          long[] slot = quantities.get(HaulService.HAUL_COMMODITY);
          if (slot == null) {
            Map<CommodityId, long[]> extended = new LinkedHashMap<>(quantities);
            slot = new long[2];
            extended.put(HaulService.HAUL_COMMODITY, slot);
            quantities = extended;
          }
          slot[0] = Math.addExact(slot[0], haulDemandMilli);
          slot[1] = Math.addExact(slot[1], haulSupplyMilli);
          logHaulPriceSignal(
              ctx,
              zoneId,
              zoneMarket,
              haulDemandMilli,
              haulSupplyMilli,
              haulUnservedMilli,
              zoneDemandDetail);
        }
      }
      for (CommodityId commodity : orderedCommodities(ctx)) {
        // ★★ 2026-10-09：有定价行（含明确 0 价）才自适应；"从未定价"的商品跳过（不凭空造一行）。
        if (!zoneMarket.hasPrice(commodity)) {
          continue;
        }
        long reference = zoneMarket.priceOf(commodity);
        long[] demandSupply = quantities.get(commodity);
        long demand = demandSupply == null ? 0L : demandSupply[0];
        long supply = demandSupply == null ? 0L : demandSupply[1];
        // ★★ W2：α 逐区现读（{@code MarketZone.priceAlphaPerMille}，GM 可调）—— 改 α 后下一市场轮生效。
        long alphaPerMille = priceAlphaPerMilleOf(ctx, zoneId);
        // ★★ W2-fix（§一.9 DEBUG = 每一阶段的池子/汇总/理由）：把 **D 的构成**如实记下来（全额 / 其中运力未服务 /
        //   其中口岸拦下），否则"N 涨了"只能靠事后读代码反推是需求真的多了还是口径变了。
        logDemandComposition(ctx, zoneId, commodity, demand, supply, alphaPerMille);
        long next = adaptiveNextPrice(reference, demand, supply, alphaPerMille);
        if (next == reference) {
          continue; // 不动的商品一行都不写（缺省中性：失衡为 0 的世界逐值不变）
        }
        zoneMarket = zoneMarket.withPrice(commodity, next);
        updated.put(zoneId, zoneMarket);
        updates.add(new MarketReport.PriceUpdate(region.anchor(), commodity, reference, next));
      }
      // ★ W1：本区的 D/S 累加器（无论本轮改没改价，都要如实记下来 —— 统计簿是"每轮"的账）。
      if (!quantities.isEmpty()) {
        quantitiesByMarket.put(zoneId, quantities);
      }
    }
    return new AdaptivePrices(updates, updated, quantitiesByMarket);
  }

  /**
   * ★★★ <b>W7（2026-10-11）：运输服务的价格信号读数（INFO = "这一轮运力缺口把运费推成什么"）</b>。
   *
   * <pre>
   * haulDemandWorkMilli      D = 本区（按**发货格**归属）本轮派生出来的运输服务需求全额（毫商品·程）
   * haulSupplyWorkMilli      S = 本区承运方手上**可交付**的运输服务货（毫商品·程）
   * unservedWorkMilli        其中**真的没运走**的那一份（Σ 未服务工作量；只作读数，不进 §2.1 的 D —— 见那条口径）
   * priceMilliBefore / alphaPerMille / priceMilliAfter   本轮 §2.1 的输入与输出（唯一算式，本方法只读数）
   * providersWithService     全池"手上有可交付服务货"的家户数（**全局**：运力池已改为全市场区共享）
   * demandByShippingHex      逐发货格明细（谁在等运力；"可被接单"的第一现场）
   * </pre>
   *
   * <p>★ 只在服务定价行存在、且 D 或 S 非零时发（缺省中性：无运力的世界一行不打）。★ 不写任何状态、不改任何算式。
   */
  static void logHaulPriceSignal(
      MatchContext ctx,
      MarketZoneId zoneId,
      MarketTable zoneMarket,
      long haulDemandWorkMilli,
      long haulSupplyWorkMilli,
      long haulUnservedWorkMilli,
      Map<HexCoord, Long> demandByShippingHex) {
    if (!MARKET.isInfoEnabled()) {
      return;
    }
    long reference = zoneMarket.priceOf(HaulService.HAUL_COMMODITY);
    long next =
        adaptiveNextPrice(
            reference, haulDemandWorkMilli, haulSupplyWorkMilli, priceAlphaPerMilleOf(ctx, zoneId));
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "HAUL_PRICE_SIGNAL",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "market",
                zoneId.value(),
                "haulDemandWorkMilli",
                haulDemandWorkMilli,
                "haulSupplyWorkMilli",
                haulSupplyWorkMilli,
                "unservedWorkMilli",
                haulUnservedWorkMilli,
                "shortageRatioPerMille",
                haulSupplyWorkMilli <= 0L
                    ? (haulDemandWorkMilli > 0L ? 1000L : 0L)
                    : Math.min(
                        1000L,
                        Math.multiplyExact(1000L, haulDemandWorkMilli - haulSupplyWorkMilli)
                            / Math.max(1L, haulSupplyWorkMilli)),
                "priceMilliBefore",
                reference,
                "priceMilliAfter",
                next,
                "alphaPerMille",
                priceAlphaPerMilleOf(ctx, zoneId),
                "providersWithService",
                ctx.carrierPool.householdsWithCapacity(),
                "demandByShippingHex",
                demandByShippingHex));
  }

  /**
   * ★★ <b>W2-fix：D 的构成（DEBUG 一条，逐区逐商品）</b>—— 只读、不写状态、不改任何算式（§一.9 的"每一阶段的池子/汇总/理由"）。
   *
   * <pre>
   * demandMilli               D（全额想要量）—— 一个量都不扣（v3.5 §2.1 的 ★★）
   *   unservedByCapacityMilli 其中：因**运力**未获服务的部分（Σ min(买槽挂单量, capacityTruncatedMilli)）
   *   blockedByPortMilli      其中：因**口岸闸**拦下的部分（见 down 的读数口径；无口岸面 ⇒ 恒 0）
   * supplyMilli               S（只算真能上架的 —— 供给侧仍扣运力截断，这是刻意的**不对称**）
   * </pre>
   *
   * <p>★ 为什么这三项都要打在**同一行**：V-20 的原始裁定是"被运力截断的不提价/不压价"，而 W2-fix 把它**只**保留在
   * 供给侧；没有这一行，读日志的人无法分辨"需求真的变多了"与"扣除口径变了"（两者都会让 D 变大、价上涨）。
   *
   * <p>★★ <b>两个"其中"互不重叠</b>：运力截断记在 {@code capacityTruncatedMilli}（协调器串行路径的 承运分配不足 / 整条车道无运力 /
   * 路线窗口预算用尽三处），口岸拦下记在 {@link MatchContext#portBlockedMilliByZoneCommodity} （{@code matchRoute}
   * 里"闸用尽且还有合格需求"那一个分支）—— 后者**不改**前者（见那一处的具名注释）。
   */
  static void logDemandComposition(
      MatchContext ctx,
      MarketZoneId zoneId,
      CommodityId commodity,
      long demand,
      long supply,
      long alphaPerMille) {
    if (!MARKET.isDebugEnabled()) {
      return; // 逐区逐商品一行 ⇒ 默认关（TRACE 太细、INFO 太粗：这是"池子/汇总/理由"那一档）
    }
    long unservedByCapacity = 0L;
    for (BuySlot buy : ctx.buys) {
      if (!zoneId.value().equals(buy.region.node().nodeId())
          || !commodity.equals(buy.order.commodity())) {
        continue;
      }
      long quantity = buy.order.quantity();
      // 上限按本槽挂单量封顶（与 S 侧同一条纪律）：截断读数不该超过"这份挂单真正想要的量"。
      unservedByCapacity =
          Math.addExact(unservedByCapacity, Math.min(quantity, buy.capacityTruncatedMilli));
    }
    Map<CommodityId, Long> blockedByCommodity = ctx.portBlockedMilliByZoneCommodity.get(zoneId);
    long blockedByPort =
        blockedByCommodity == null ? 0L : blockedByCommodity.getOrDefault(commodity, 0L);
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_DEMAND_COMPOSITION",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "zone",
                zoneId.value(),
                "commodity",
                commodity.value(),
                "demandMilli",
                demand,
                "unservedByCapacityMilli",
                unservedByCapacity,
                "blockedByPortMilli",
                blockedByPort,
                "supplyMilli",
                supply,
                "alphaPerMille",
                alphaPerMille,
                "reason",
                "demand-is-full-quantity-supply-excludes-capacity-truncation"));
  }

  /**
   * ★★ <b>自适应公式的定点实现（W2：约束设计书 §2.1 的冻结算式 + W2-fix 的整数毫位最小步进）</b>（包内可见，直接可测）。
   *
   * <pre>
   * D &gt; S（缺货）: r = (D − S) / max(1, S)；step‰ = min(α, ⌊α × r⌋)
   *                p &gt; 0: p' = max(p + 1, ⌊p × (1000 + step) / 1000⌋)   // 无上限
   *                p = 0: p' = 1                                        // 退出免费态（唯一例外，见下）
   * S &gt; D（滞销）: r = (S − D) / max(1, D)；step‰ = min(α, ⌊α × r⌋)；p' = max(0, min(p − 1, ⌈p × (1000 − step) / 1000⌉))（★ v3.10：与涨侧对称）
   * D = S:         p' = p                                               // 不动（缺省中性）
   * </pre>
   *
   * <p>★★ <b>它取代了什么</b>：改动前是 {@code z = clamp((D − S) / max(D + S, ε), −1, 1)}、 {@code p' = max(0,
   * round(p × (1 + 50‰ × z)))} —— 在那个算式里"缺货倍数"被分母 {@code D + S} 摊平， 于是<b>再严重的缺货每轮也只涨
   * ≈α/2</b>，且缺口越大涨得越慢（{@code z → 1} 但被 clamp 住）。 §2.1 把速度交回给规模：{@code r} 是"缺口相对供给的倍数"，倍数越大越快打满 α。
   *
   * <p>★★ <b>整数/定点，禁浮点</b>：全程 {@code long}/{@link BigInteger}，无平台差异（可复现是 I7 的要求）。 乘法真的越过 {@code
   * long} ⇒ 顶上 {@code Long.MAX_VALUE}（"价无上限"的饱和，不抛、不回退 double）。
   *
   * <p>★★★ <b>整数毫位的"最小步进"（W2-fix，控制方裁定 v3.5 §2.1 第二条 ★★；W2-fix7 扩到跌侧）</b>：缺货且 {@code p &gt; 0} 时取
   * {@code max(p + 1, ⌊p×(1000+step)/1000⌋)} —— 因为价格以**毫**为最小单位，{@code p &lt; ⌈1000/step⌉} （α=50‰ ⇒
   * {@code p &lt; 20} 毫）时纯取整会**恒等不动**，与"缺货价格应该逐步增加"直接矛盾（真实世界的 1 毫粮价 将永不涨）。★ {@code +1 毫} =
   * **整数粒度下最小的"逐步"**，不是第二条公式：涨价方向、步长上限（≤ α）、无上限， 全仍由上面那条乘法式定，{@code +1} 只是它的整数下限。★ <b>滞销侧自
   * W2-fix7（v3.10）起有对称的例外</b>：{@code min(p − 1, ⌈p×(1000−step)/1000⌉)} —— 只靠 {@code ⌈…⌉} 的话 {@code
   * p = 1} 会恒等不动（{@code ⌈0.95⌉ = 1}），跌不到 0，与"触底 0 = 白送"矛盾； {@code min(p − 1, …)} 就是"至少降 1 毫"，与涨侧的
   * {@code max(p + 1, …)} 逐字对称（代价也一样：{@code p &lt; 20} 时实现跌幅必然 &gt; α）。
   *
   * <p>★★ <b>0 价的"退出免费态"具名例外（W2 保留，见账本偏离记录）</b>：{@code p = 0} 时乘法式恒为 0 （{@code ⌊0 ×
   * (1000+step)/1000⌋ = 0}）⇒ 白送出去的商品<b>永远涨不回来</b>，与 §2.1 的"缺货逐步涨" 及判据
   * W2-⑦"抢手场景逐轮单调涨"直接冲突。故保留既有那条<b>唯一</b>的例外：{@code p = 0} 且 {@code D > S} ⇒ 下一轮从 1 毫重新起步；{@code p =
   * 0} 且 {@code S ≥ D} ⇒ 维持 0（免费）。★ 它不是第二套定价公式： 只在 `p == 0` 这一个不可乘点上替代乘法式，其余一切仍走上式。
   *
   * <p>★ <b>α = 0 仍然是"该区价格不随供需变动"</b>（W2 的具名口径，见账本 §四-6）：{@code α = 0} 是**合法状态** （GM
   * 可以把某区的价格钉死），不是"最慢的逐步" ⇒ 缺货侧在 {@code step ≤ 0} 时**不动**，{@code +1} 不越过它。 ★ 这是一处**具名例外**（v3.5
   * 的公式没写 α=0 这一支），已在 W2-fix 账本"偏离记录"里记明。
   *
   * @param price 当前单价（毫计价货币 / 商品单位；≥ 0）
   * @param demand 本轮该区该商品的 <b>D</b>（毫商品；全额口径见 {@link #adaptPrices}）
   * @param supply 本轮该区该商品的 <b>S</b>（毫商品；只含真能上架的）
   * @param alphaPerMille ★★ 本区步长 α（‰/轮；≥ 0；0 = 价格不动）。由 {@link MarketZone#priceAlphaPerMille()}
   *     逐区给出，缺省 {@link MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}
   */
  static long adaptiveNextPrice(long price, long demand, long supply, long alphaPerMille) {
    if (price < 0L) {
      throw new IllegalArgumentException("自适应价格的入参 price 不得为负: " + price);
    }
    if (alphaPerMille < 0L) {
      throw new IllegalArgumentException("自适应价格的入参 alphaPerMille 不得为负: " + alphaPerMille);
    }
    long d = Math.max(0L, demand);
    long s = Math.max(0L, supply);
    if (d == s) {
      return price; // D = S ⇒ 不动（缺省中性：不失衡的世界逐值不变）
    }
    if (d > s) {
      // ── 缺货：涨 ─────────────────────────────────────────────────────────────────────
      if (price == 0L) {
        return 1L; // ★ 具名例外：退出免费态（见类注），否则 0 价商品永远涨不回来
      }
      long step = stepPerMille(d - s, Math.max(1L, s), alphaPerMille);
      if (step <= 0L) {
        // ★ α = 0 或倍数取整后为 0 ⇒ 本轮不动。★ α = 0 是**合法状态**（该区价格不随供需变动，W2 口径），
        //   不是"最慢的逐步" ⇒ 下面的 +1 毫最小步进**不越过**它（见方法注的具名例外）。
        return price;
      }
      // ★★★ W2-fix（控制方裁定 v3.5 §2.1 第二条 ★★）：整数毫位的**最小步进**。
      //   p < ⌈1000/step⌉ 时 ⌊p×(1000+step)/1000⌋ 恒等于 p（α=50‰ ⇒ p < 20 毫被整数除法抹平）⇒
      //   "缺货逐步涨"在低价位失效（真实世界的 1 毫粮价永不涨）。取 max(p + 1, 乘法式)：
      //   +1 毫 = 整数粒度下最小的"逐步"，**不是第二条公式**（方向/上限/无上限全由乘法式定，+1 只是它的整数下限）。
      //   ★ 乘法式溢出时返回 Long.MAX_VALUE ⇒ max 自然取饱和值；p 已到 Long.MAX_VALUE ⇒ +1 那一侧也饱和
      //     （不减、不抛、不回退 —— 与乘法式同一条"价无上限"的饱和纪律）。
      return Math.max(
          incrementOneMilli(price), multiplyByPerMille(price, 1000L + step, Long.MAX_VALUE));
    }
    // ── 滞销：跌（触底 0 = 白送）─────────────────────────────────────────────────────────
    if (price == 0L) {
      return 0L; // 已经白送且供过于求 ⇒ 维持白送
    }
    long step = stepPerMille(s - d, Math.max(1L, d), alphaPerMille);
    if (step <= 0L) {
      return price; // ★ α = 0（或倍数取整后为 0）⇒ 本轮不动（v3.8 口径；与缺货侧逐字同款）
    }
    // ★★★ W2-fix7（控制方裁定 v3.10 §2.1：跌侧与涨侧**对称**）：
    //   p' = max(0, min(p − 1, ⌈p × (1000 − step) / 1000⌉))
    //   ★ 为什么把 ⌊…⌋ 改成 ⌈…⌉：向下取整会让**实现**跌幅超过 α（验收报告 §3 D3 实测：day120 grain
    //     56→53 = −53.6‰、最大 −90.9‰ 出现在 p=11、step=50 一步掉到 10）—— 机制步长 step ≤ α 恒成立，
    //     超出的部分全是 floor 伪影。向上取整后 p ≥ ⌈1000/step⌉ 的档实现跌幅 ≤ α。
    //   ★ 为什么还要 min(p − 1, …)：p 小于 ⌈1000/step⌉ 时整数取整会把降价**完全抹平**
    //     （p=1、step=50 ⇒ ⌈0.95⌉ = 1 = p ⇒ 永远跌不到 0），与"滞销逐步跌、触底 0（白送合法）"直接矛盾。
    //     min(p − 1, …) = 整数粒度下最小的一步，**不是第二条公式** —— 正是缺货侧 max(p + 1, …) 的镜像。
    //     代价与涨侧同形：p < 20（α=50‰）时实现跌幅必然 > α（一步 1 毫），这是整数毫位的必然，不是失控。
    //   ★ 溢出/饱和：乘法式溢出 ⇒ 饱和 0（跌侧的饱和方向就是一步到 0）；p ≥ 1 ⇒ p − 1 ≥ 0。
    long multiplicative = multiplyByPerMilleCeiling(price, Math.max(0L, 1000L - step), 0L);
    return Math.max(0L, Math.min(Math.max(0L, price - 1L), multiplicative));
  }

  /**
   * ★★ <b>W2-fix：整数毫位的最小步进 {@code p + 1}</b>（缺货侧 {@code max} 的右项；见 {@link #adaptiveNextPrice}）。
   *
   * <p>★ 为什么需要它：价格的最小单位是毫，{@code ⌊p×(1000+step)/1000⌋} 在小价位上会被整数除法**完全抹平** （α=50‰、{@code p < 20} 毫
   * ⇒ 每轮涨 0）⇒ "缺货逐步涨"这一条在低价位失效。+1 毫就是这条粒度下的最小一步。
   *
   * <p>★ 溢出：{@code p} 已到 {@link Long#MAX_VALUE} 时返回原值（饱和，不回退、不抛 —— 与乘法式同一条纪律）。
   */
  static long incrementOneMilli(long price) {
    try {
      return Math.addExact(price, 1L);
    } catch (ArithmeticException overflow) {
      return price; // 价已到 long 的顶 ⇒ +1 毫没有意义（乘法式那边同样饱和成 Long.MAX_VALUE）
    }
  }

  /** {@code step‰ = min(α, ⌊α × r⌋)}，{@code r} 是"缺口 / max(1, 对手方)"的整数比（floor，禁浮点）。 */
  static long stepPerMille(long gap, long base, long alphaPerMille) {
    if (alphaPerMille <= 0L || gap <= 0L) {
      return 0L;
    }
    // ⌊α × gap / base⌋：先乘后除；α 与 gap 都是 long，乘积可能溢出 ⇒ BigInteger 中间量（每轮每区每商品一次，不是热路径）。
    BigInteger scaled =
        BigInteger.valueOf(alphaPerMille)
            .multiply(BigInteger.valueOf(gap))
            .divide(BigInteger.valueOf(base));
    if (scaled.compareTo(BigInteger.valueOf(alphaPerMille)) > 0) {
      return alphaPerMille; // min(α, ⌊α·r⌋) —— r 大时打满 α（"逐步"的封顶）
    }
    return scaled.longValueExact();
  }

  /**
   * {@code ⌊value × numerator ÷ 1000⌋}（整数、向下取整；溢出 ⇒ {@code saturation} 饱和值而不是抛）。
   *
   * <p>★ 用 {@link BigInteger} 而不是 {@code Math.multiplyExact} 的 try/catch：这里是"价本身无上限"的落点，
   * 溢出必须是<b>饱和</b>（{@code Long.MAX_VALUE}）而不是异常 —— 世界真的通胀到那个量级时，模拟要继续跑。
   */
  static long multiplyByPerMille(long value, long numerator, long saturation) {
    BigInteger product =
        BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator)).divide(THOUSAND);
    if (product.bitLength() > 62) {
      return saturation;
    }
    return product.longValueExact();
  }

  /**
   * ★★ <b>W2-fix7（v3.10 §2.1 跌侧）：{@code ⌈value × numerator ÷ 1000⌉}</b>（整数、<b>向上</b>取整；溢出 ⇒ {@code
   * saturation} 饱和值而不是抛）。
   *
   * <p>★ 与 {@link #multiplyByPerMille} 是同一个乘法式的两种取整方向：涨侧与"至少降 1 毫"的例外都用 floor 那一支（那正是 v3.5
   * 冻结的算式），<b>跌侧的乘法式用 ceiling</b> —— 只有向上取整才能保证"实现跌幅 ≤ α" （向下取整会把不足 1 毫的降价也吃满 1 毫，见 {@link
   * #adaptiveNextPrice} 的裁定注）。 溢出纪律与 floor 那支同款：饱和、不抛。
   */
  static long multiplyByPerMilleCeiling(long value, long numerator, long saturation) {
    BigInteger ceiling =
        BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(numerator))
            .add(THOUSAND_MINUS_ONE)
            .divide(THOUSAND);
    if (ceiling.bitLength() > 62) {
      return saturation;
    }
    return ceiling.longValueExact();
  }

  static final BigInteger THOUSAND = BigInteger.valueOf(1000L);

  /** {@code 999}（ceiling 的整数式 {@code ⌈a/1000⌉ = (a + 999) / 1000} 里的常数）。 */
  static final BigInteger THOUSAND_MINUS_ONE = BigInteger.valueOf(999L);

  /**
   * ★★ <b>W2：本区本轮的自适应步长 α</b>（‰/轮）—— 唯一拼写点。
   *
   * <p>★ 持久形态：{@link MarketZone#priceAlphaPerMille()}（GM 可调，{@code
   * economy.SetMarketZonePriceAlpha}）。 ★ 派生形态 / 没有区表（夹具、没有 map 切片的世界）：出厂缺省 {@link
   * MarketZone#DEFAULT_PRICE_ALPHA_PER_MILLE}。 ★ 区表里有这个键但它是派生市场（键不是持久区 id ⇒ 其实查不到）同样落缺省 —— 不静默建区。
   */
  static long priceAlphaPerMilleOf(MatchContext ctx, MarketZoneId marketId) {
    MarketZone zone = ctx.marketZones.get(marketId);
    return zone == null ? MARKET_ADAPTIVE_ALPHA_PER_MILLE : zone.priceAlphaPerMille();
  }

  // ── 订单生成 ───────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>一个 hex 所属区的规范 id</b>（与 {@link MarketRegion#node()}{@code .nodeId()} 同源；不在任何区 ⇒ null，不抛）。★
   * P-T1c 起它只用于"区内税费归属哪个区"（{@link MatchContext#regulationByRegionId}）。
   */
  static String regionIdAt(MarketTopology topology, HexCoord hex) {
    if (topology == null || hex == null || !topology.contains(hex)) {
      return null;
    }
    return topology.regionOf(hex).node().nodeId();
  }
}
