package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ConsumptionSettlement.consumeForLoss;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.frozenGoodsOf;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ShipmentSettlement {

  /**
   * ★★★ <b>A5 + A7（2026-10-10）：周期边界作废"上一周期没卖出去的运输服务"</b>（用户裁定「运力作为特殊商品不可储存转卖」； 约束设计书 §3.1 v1.2 /
   * I-H3 v1.2 / v1.5 的 A7 收口）。
   *
   * <pre>
   * 触发   = 产出运输服务的 unit 关账那天（{@code progressed >= cycleDays}），在**收获入账之前**
   * 扫谁   = **任何持有 haul 的家户**（A7 扩域；= 会话里全部已登记家户的 haul 键，不再限于关账 unit 的主体家户）
   * 扫多少 = 每户 {@code max(0, haul 现货 − haul 冻结)}（被冻结的那一份是别处的明确占用，不动它）
   * 落点   = {@link EconomySettlement#consumeForLoss}（非换手损耗唯一写口）+ {@link HaulService#SERVICE_EXPIRED_ACCOUNT}
   * </pre>
   *
   * <p>★★ <b>A7 为什么要扩到"任何持 haul 的家户"（A6 真实 world 实测的逃逸口）</b>：A5 的作用域是"关账 unit 的账户主体家户"，
   * 而服务货会**离开**产它的家户 —— B 世界 day 240 的 5,249 毫陈货经 {@code loan_repayment}（实物还债）转移到已退出商家名册的持货户， 之后跨
   * day 360 / day 480 两次边界都没被作废（A6 账本 §1.2）：效果上卖不掉（该户 480 天 0 条池行），字面上没销毁。 ★ 一旦该户被选回跑商（本仓确有 {@code
   * pureMerchant} 状态机），陈货**立刻**变成可卖运力 ⇒ 按"任何持货家户"收口。
   *
   * <p>★★ <b>为什么必须在"收获之前"</b>：关账日的收获会把**本周周期的净产**记进同一批账户。若作废排在收获之后， 刚产出的服务会被当成陈货一起作废（新货当陈货）。排在收获之前
   * ⇒ 作废的**恰好**是上一周期留下、没卖出去的那一份； 而"谁有服务货可卖"（{@link MerchantCapacityPool#of} 的服务口径：{@code max(0, 现货
   * − 冻结)}）因此**只可能**看到 本周期的产出 —— 这就是"不得跨周期留存、不得再卖"的结构性落点，不需要给池加任何周期过滤（也就没有第二处判据）。 ★
   * 全局清扫落在"收获之前"仍然成立：本阶段的**唯一**产出写入口是 {@code harvestPartitioned}（在调用点之后）， 而它尚未开始 ⇒ 此刻账上的 haul
   * **一律**是"本周周期收获之前就存在"的存货 ⇒ 清扫不会误伤当期新产（详见实现账本 §3）。
   *
   * <p>★★ <b>为什么不是静默销毁（I-H2）</b>：走的是与货损 / 服务成交消耗**同一个**写口 —— 账户减与 {@code
   * ledger.addLoss(SERVICE_EXPIRED_ACCOUNT, haul, amount)} 在同一句话里做完 ⇒ Σ家户余额 + Σ损耗账守恒。
   * 具名账户与"卖出去时消耗"的那一项（{@code market-haul-service}）分开 ⇒ "卖了多少 / 烂了多少"在账上分得开。
   *
   * <p>★ <b>为什么"只影响 haul"</b>：读写的商品键恒为 {@link HaulService#HAUL_COMMODITY}；清扫只对"该户有 haul 键"的户写
   * （别的商品一个字节不动）；且**没有**任何跨商品分支。无跑商 unit 的世界 ⇒ {@code triggeringUnits} 为空 ⇒
   * 本方法连一次表查询都不做（缺省中性，I-H3）。
   *
   * <p>★ <b>只作用于"没卖出去"的那一份</b>：{@code available = max(0, stock − frozen)}，冻结量不动（它是别处已明确的占用；
   * 服务不进订单簿，故现实中它为 0）；可用量为 0 的户连损耗条目都不落（不制造 0 额账）。
   *
   * <p>★★ <b>确定性（I7 / I-H4）</b>：清扫顺序 = 家户 id 规范串升序（与 {@code MerchantCapacityPool} 的逐户顺序同一把尺），
   * 是**内容的纯函数**；不依赖哈希序，也不用 {@code Map.copyOf}/{@code Set.copyOf}。
   *
   * @param day 当日日号
   * @param triggeringUnits 本日关账且产出运输服务的 unit（**只当触发开关与日志归因用**；A7 起不再是清扫范围）
   * @param accounts 会话账户（活表；本方法在收获阶段取快照**之前**写它）
   * @param ledger 当日发生额累加器（损耗账的唯一写入点）
   * @return 本日作废的服务总量（毫服务；没有作废 ⇒ 0）—— 当日对账 INFO 的"作废"一栏
   */
  static long expireUnservedHaulService(
      long day,
      List<ProductionProcess> triggeringUnits,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger) {
    if (triggeringUnits.isEmpty()) {
      return 0L; // 本日没有跑商 unit 关账 ⇒ 一行不跑、一账不动（缺省中性）
    }
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = accounts.householdGoods();
    Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods = accounts.householdFrozenGoods();
    // ★★ A7：清扫范围 = 会话里**全部**已登记家户里"有 haul 存货"的那些（键的存在即"持有"——
    //   余额归零即去键，见 setStock 的口径）⇒ 不再做任何主体解析，持货不再需要与"谁产的"挂钩。
    List<HouseholdId> holders = new ArrayList<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : householdGoods.entrySet()) {
      if (entry.getValue().containsKey(HaulService.HAUL_COMMODITY)) {
        holders.add(entry.getKey());
      }
    }
    if (holders.isEmpty()) {
      return 0L; // 全世界没有一户持 haul ⇒ 不排序、不落账、不改一个字节（"没有 haul 持有者 ⇒ 中性"）
    }
    holders.sort(Comparator.comparing(HouseholdId::value)); // ★ I7：顺序是内容的纯函数（不许依赖哈希/插入序）
    long total = 0L;
    long householdCount = 0L;
    for (HouseholdId household : holders) {
      long stock = stockOf(householdGoods, household, HaulService.HAUL_COMMODITY);
      long frozen = frozenGoodsOf(householdFrozenGoods, household, HaulService.HAUL_COMMODITY);
      long available = Math.max(0L, stock - frozen);
      if (available <= 0L) {
        continue; // 这个户没有"没卖出去的"服务货（或全部被冻结）⇒ 不落 0 额账、不改一个字节
      }
      EconomySettlement.LossConsumption consumed =
          consumeForLoss(
              householdGoods,
              householdFrozenGoods,
              ledger,
              HaulService.SERVICE_EXPIRED_ACCOUNT,
              household,
              HaulService.HAUL_COMMODITY,
              available);
      if (consumed.consumedMilli() <= 0L) {
        // ★ fail-closed 的写口在"可用量 < 请求量"时一点也不扣；这里请求量**就是**可用量 ⇒ 结构上取不到 0。
        //   真取到 0 说明账在两行之间被改了（契约故障）⇒ 具名 ERROR，绝不静默少作废。
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HAUL_SERVICE_EXPIRY_FAULT",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "requestedMilli",
                    available,
                    "stockMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "scope",
                    "all-haul-holders",
                    "reason",
                    "haul-service-expiry-blocked-by-live-table-change"));
        continue;
      }
      total = Math.addExact(total, consumed.consumedMilli());
      householdCount++;
      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "HAUL_SERVICE_EXPIRED_DETAIL",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "expiredMilli",
                    consumed.consumedMilli(),
                    "stockBeforeMilli",
                    consumed.stockMilli(),
                    "frozenMilli",
                    consumed.frozenMilli(),
                    "lossAccount",
                    HaulService.SERVICE_EXPIRED_ACCOUNT.value(),
                    "scope",
                    "all-haul-holders",
                    "reason",
                    "unsold-haul-service-cannot-be-stored-carried-over"));
      }
    }
    if (total <= 0L) {
      return 0L; // 有持货户但都没有"没卖出去的"那一份 ⇒ 不刷 INFO（"没发生"不进日志；对账行仍会报 0）
    }
    // ── INFO（§一.9：新状态写口至少一条"发生了什么 + 具名计数"）────────────────────────────
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "HAUL_SERVICE_EXPIRED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "households",
                householdCount,
                "triggerUnits",
                triggeringUnits.size(),
                "holders",
                holders.size(),
                "scope",
                "all-haul-holders",
                "expiredMilli",
                total,
                "lossAccount",
                HaulService.SERVICE_EXPIRED_ACCOUNT.value(),
                "reason",
                "unsold-haul-service-cannot-be-stored-carried-over"));
    return total;
  }

  /**
   * ★★ <b>A2：本轮"运输服务成市"的格集</b>（= 本格市场给 {@code haul} 定过价）—— 逐轮现算，不落状态、不进任何组件。
   *
   * <p>★ <b>它是本批缺省中性的唯一开关</b>：本集为空 ⇒ 运力池按既有"劳动 + 工具"算式、运费走既有 {@code CARRIER_FEE} 腿 ⇒
   * <b>逐值等于改前</b>（I-H3 的第一条腿"无牌价"）。★ 成市的格则改走服务商品口径（运力 = 商家户手上的服务货， 运费 = 服务成交），见 {@link
   * MerchantCapacityPool#of}（第 7 参）与 {@code MarketSettlement} 的服务分支。
   *
   * <p>★ 判据的唯一拼写点是 {@link HaulService#pricedAt}（本方法只负责"遍历哪些市场"）。
   */
  static Set<HexCoord> haulServiceHexes(EconomyDayView view) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    // ★ W1：遍历的格 = "有市场的格"（持久形态 = 区成员格；派生形态 = 价表成员格），逐格取本格所属市场的价表。
    for (HexCoord hex : MarketTables.allMarketHexes(view.marketTables(), view.marketZones())) {
      if (HaulService.pricedAt(MarketTables.atOrNull(view, hex))) {
        hexes.add(hex);
      }
    }
    return Collections.unmodifiableSet(hexes);
  }

  /**
   * ★★ <b>A5：逐户服务收入 → 逐币总额</b>（当日对账 INFO 的 {@code paidByCurrency} 一栏）—— 只读求和，不写任何状态。
   *
   * <p>★ <b>为什么在这里求和而不是再记一份</b>：金额的唯一事实是逐笔钱腿（{@code carrierPool.recordFee} 按户归集）； 逐币总额是它的纯派生量。★ 保序
   * = {@code LinkedHashMap} 的首次出现序，而 {@code revenueByHousehold()} 已按 （家户 id → 币种规范串）冻结 ⇒
   * 键序是内容的纯函数（I7；禁 {@code Map.copyOf}）。
   */
  static Map<CurrencyId, Long> haulServicePaidByCurrency(MarketSettlement.HaulServiceRound round) {
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (Map<CurrencyId, Long> legs : round.revenueByHousehold().values()) {
      for (Map.Entry<CurrencyId, Long> leg : legs.entrySet()) {
        totals.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /**
   * ★★ <b>到货处理</b>（M2.4/M2.5）：把 {@code arrivalTick <= day} 的在途批次逐票落回买方账户。
   *
   * <pre>
   * loss    = ⌊本票数量 × lossPerMille ÷ 1000⌋        // 在途实物减少
   * arrived = 本票数量 − loss                         // 买方只收到净额（M2.5：买方承担损耗）
   * 记损耗：ledger.addLoss(TRANSPORT_LOSS_ACCOUNT, commodity, loss)   // 进损耗账户，不静默蒸发
   * 记到货：买方账 += arrived（家户或经营者）★ M6：能定位的买方/卖方由 app 侧 loadAccountSession 一并载入
   * （缺 actor 账建零账）；这里只在**真正无主**时抛，货不静默丢
   * </pre>
   *
   * <p>★ <b>为什么在日循环里、且排在消费之前</b>：到货日是"目的地第一次能消费它"的那一天（M2.4 的判据）。 ★ <b>补投</b>：手工搭的状态可能给 {@code
   * arrivalTick < day}，本方法照样投递，不让货卡在在途表里。 ★ 本批只产生 {@link
   * io.mosire.simos.economy.api.market.LossBearer#BUYER}；其他分担方留位，见到即抛（不假装已实现）。
   */
  /**
   * ★★ <b>到货模块的分区数</b>：R2 起由 {@link EconomyParallelism#partitionCount()} 统一给（固定 {@link
   * EconomyParallelism#STRUCTURAL_PARTITIONS} 个结构分区，1/4/8 线程共用同一份分区计划与提交序）。
   *
   * <p>★ 到货按<b>目的账户</b>分区（不是 hex：同一买方多批到货必须并到同一分区，而买方可能在任意格）； 其它阶段按 hex / 市场区 / PeopleLotId 分区，见
   * {@link SettlementStage} 的枚举注释。
   */

  /** 到货阶段的一条工作单元（按目的地账户分区；同一买方的多票并到同一分区）。 */
  record DeliveryWork(
      AccountPartitionKey buyerKey,
      ShipmentId shipmentId,
      int allocationIndex,
      CommodityId commodity,
      long arrived,
      long loss) {}

  /** 到货阶段的一条在途损耗（提交时按稳定序落 {@code ProductionLedger.losses}）。 */
  record DeliveryLoss(
      ShipmentId shipmentId, int allocationIndex, CommodityId commodity, long amount) {}

  /** 一个分区的到货产出：线程本地意向 + 损耗读数（都不可变，提交前不触碰共享状态）。 */
  record DeliveryPartition(List<OrderedAccountIntent> intents, List<DeliveryLoss> losses) {}

  /**
   * ★★ <b>到货：在途 → 买方账户</b>（M2.4；R1 起是本仓第一条真正按账户分区的阶段）。
   *
   * <pre>
   * ① 协调器在 AccountSnapshot 上收集"到达日 ≤ day"的逐票工作单元，按 (买方账户 canonical, shipmentId, 票序)
   *    排序 ⇒ 分区计划只依赖 canonical 串（1/4/8 线程同分区）；
   * ② worker 只读快照、只写自己的 AccountIntentBuffer（creditGoods）⇒ 共享账户 Map 在 worker 期间没有写者；
   * ③ 提交：SettlementExecutor.commit 按 (stage, partitionIndex, canonicalKey, intraIndex) 单线程落账；
   * ④ 损耗按同一稳定序落 ledger；到达批次按 canonical 序销账。
   * </pre>
   *
   * <p>★ <b>语义与逐条实现的关系</b>：到货加库存原本就走 {@code addStock}（家户账）/{@code addOperatorStock}
   * （经营者账）；本实现把"买方是家户还是经营者"统一收敛到 {@code (ActorRef, location)} 账户键，故只有一条路径。 ★
   * 到达前消费不到它；到货日"在途减、目的地库存增"的守恒口径不变，损耗仍由买方承担。
   */
  static void deliverShipments(
      long day,
      EconomySession session,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism) {
    LinkedHashMap<ShipmentId, ShipmentBatch> shipments = session.sheet().shipments();
    AccountSnapshot snapshot = accounts.snapshot();
    List<DeliveryWork> works = new ArrayList<>();
    LinkedHashSet<ShipmentId> arrivingBatches = new LinkedHashSet<>();
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : shipments.entrySet()) {
      ShipmentBatch batch = entry.getValue();
      if (batch.arrivalTick() > day) {
        continue;
      }
      arrivingBatches.add(entry.getKey());
      List<ShipmentAllocation> allocations = batch.allocations();
      for (int i = 0; i < allocations.size(); i++) {
        ShipmentAllocation allocation = allocations.get(i);
        if (allocation.lossBearer() != io.mosire.simos.economy.api.market.LossBearer.BUYER) {
          throw new IllegalStateException(
              "本批只支持买方承担在途损耗（M2.5 基线合同），见到其他分担方: " + allocation.lossBearer());
        }
        long loss = allocation.quantity() * batch.route().lossPerMille() / 1000L;
        long arrived = allocation.quantity() - loss;
        AccountPartitionKey buyerKey = snapshot.actorKeyOrNull(allocation.buyer());
        if (buyerKey == null) {
          // ★★ M6：货仍然不能丢，但"缺账"只在**真正无主**时才抛 —— 能在 EconomyData 里定位的买方/卖方
          //   （家户行 / 产业 operator）已由 app 的 OwnershipBooks.loadAccountSession 一并载入（缺 actor 账时建零账），
          //   到货日不会再因"跨区经营者未播种"这种口径缺口整条推进失败。
          throw new IllegalStateException(
              "在途到货时买方在 economy 侧也定位不到账户（真正无主，货不能静默丢）：批次="
                  + entry.getKey()
                  + " 买方="
                  + allocation.buyer()
                  + " 收货格="
                  + allocation.deliverTo());
        }
        works.add(
            new DeliveryWork(
                buyerKey, entry.getKey(), i, batch.commodity(), arrived, loss > 0L ? loss : 0L));
      }
    }
    if (works.isEmpty()) {
      return;
    }
    // ★ 分区内的遍历序 = canonical 升序（先按账户键、同账户再按票的稳定序）——意向生成序是内容的纯函数。
    TreeMap<String, List<DeliveryWork>> worksByKey = new TreeMap<>();
    for (DeliveryWork work : works) {
      worksByKey
          .computeIfAbsent(work.buyerKey().canonical(), ignored -> new ArrayList<>())
          .add(work);
    }
    for (List<DeliveryWork> bucket : worksByKey.values()) {
      bucket.sort(
          Comparator.comparing((DeliveryWork work) -> work.shipmentId().value())
              .thenComparingInt(DeliveryWork::allocationIndex));
    }
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.DELIVER_SHIPMENTS, worksByKey.keySet(), parallelism.partitionCount());
    List<DeliveryPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.on(
                      snapshot,
                      SettlementStage.DELIVER_SHIPMENTS,
                      partition.partitionIndex(),
                      plan.partitionCount());
              List<DeliveryLoss> losses = new ArrayList<>();
              for (String canonical : partition.canonicalKeys()) {
                AccountPartitionKey key = AccountPartitionKey.parseCanonical(canonical);
                for (DeliveryWork work : worksByKey.get(canonical)) {
                  if (work.arrived() > 0L) {
                    // ★ 全损到货（arrived == 0）不产出一笔 0 增量：与旧 addStock 的"delta == 0 直接返回"逐值同口径。
                    buffer.creditGoods(key, work.commodity(), work.arrived());
                  }
                  if (work.loss() > 0L) {
                    losses.add(
                        new DeliveryLoss(
                            work.shipmentId(),
                            work.allocationIndex(),
                            work.commodity(),
                            work.loss()));
                  }
                }
              }
              return new DeliveryPartition(buffer.drainIntents(), losses);
            },
            parallelism.poolOrNull());
    // ★★ 单线程稳定提交：转移/增量/冻结同走 SettlementExecutor.commit 的字典序；到货只产生增量。
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (DeliveryPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (DeliveryPartition partition : partitions) {
      for (DeliveryLoss loss : partition.losses()) {
        ledger.addLoss(MarketSettlement.TRANSPORT_LOSS_ACCOUNT, loss.commodity(), loss.amount());
      }
    }
    List<ShipmentId> removals = new ArrayList<>(arrivingBatches);
    removals.sort(Comparator.comparing(ShipmentId::value));
    for (ShipmentId id : removals) {
      shipments.remove(id);
    }
  }
}
