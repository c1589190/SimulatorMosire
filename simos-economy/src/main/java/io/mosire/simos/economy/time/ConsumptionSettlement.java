package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomySettlement.CLOTH;
import static io.mosire.simos.economy.time.EconomySettlement.GRAIN;
import static io.mosire.simos.economy.time.SettlementLogSupport.RAW;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.addGoods;
import static io.mosire.simos.economy.time.SettlementMath.frozenGoodsOf;
import static io.mosire.simos.economy.time.SettlementMath.mergeGoodsInto;
import static io.mosire.simos.economy.time.SettlementMath.rowsByHex;
import static io.mosire.simos.economy.time.SettlementMath.setStock;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.EconomySettlement.LossConsumption;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ConsumptionSettlement {

  // ── R2：按 hex 分区的日结算阶段（统一执行器 + 线程本地意向 + 协调器稳定提交）──────────────────
  //
  // ★★ 三个阶段共用同一条架构（照 §4.4）：
  //   ① 协调器在 AccountSnapshot 上按 hex canonical 串建 PartitionPlan（固定结构分区数，1/4/8 同一份）；
  //   ② worker 只读快照/只读共享状态表，写自己的 AccountIntentBuffer 与本地状态副本；
  //   ③ 协调器按分区序 commit 意向、合并本地副本 —— 提交序只由 CommitOrder 决定，与线程到达序无关。
  // ★ 冲突表判据：同一 hex 内的产业/家户共用账户与行，必须同分区串行；跨 hex 没有共享 (actor, location) 账户。
  // ★ 本阶段的转移仍走唯一写口 applyTransfer（worker 把它应用在 BufferedAccountTables 的意向视图上），
  //   铸号走分区累加器（id 带 p<partition> 段，1/4/8 同 id），协调器按分区序吸收进当天账本。

  /** 消费阶段一个分区的产出（意向 + 行更新 + 三个读数累加器）。 */
  record ConsumptionPartition(
      List<OrderedAccountIntent> intents,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Map<CommodityId, Long>> unmet,
      Map<HouseholdId, Long> deficits) {}

  /**
   * ★★ <b>消费：吃自家库存</b>（按 hex 并行；缺口的借贷已按 S-12① 移到市场轮的货币腿）。
   *
   * <p>每个分区处理若干 hex；同 hex 内按 {@link #rowsByHex} 的既有行序逐户消费（户与户之间无共享账户），
   * 写的是线程本地意向。缺口/消费/未满足三个累加器随分区返回，协调器按分区序合并（整数加法可交换）。
   */
  static void consumeOwnStockPartitioned(
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      LinkedHashMap<HouseholdId, Long> deficitToday,
      EconomyParallelism parallelism) {
    Map<String, List<HouseholdId>> hexToRows = rowsByHex(householdEconomies);
    if (hexToRows.isEmpty()) {
      return;
    }
    AccountSnapshot snapshot = accounts.snapshot();
    PartitionPlan plan =
        PartitionPlan.of(
            SettlementStage.CONSUMPTION, hexToRows.keySet(), parallelism.partitionCount());
    List<ConsumptionPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.onHexPartition(
                      snapshot,
                      SettlementStage.CONSUMPTION,
                      partition.partitionIndex(),
                      plan.partitionCount());
              BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
              LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomyUpdates =
                  new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmet = new LinkedHashMap<>();
              LinkedHashMap<HouseholdId, Long> deficits = new LinkedHashMap<>();
              for (String hex : partition.canonicalKeys()) {
                for (HouseholdId key : hexToRows.get(hex)) {
                  consumeOneHousehold(
                      householdEconomies,
                      tables.householdGoods,
                      key,
                      householdEconomyUpdates,
                      consumed,
                      unmet,
                      deficits);
                }
              }
              return new ConsumptionPartition(
                  buffer.drainIntents(), householdEconomyUpdates, consumed, unmet, deficits);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (ConsumptionPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (ConsumptionPartition partition : partitions) {
      for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyUpdate :
          partition.householdEconomyUpdates().entrySet()) {
        householdEconomies.put(householdEconomyUpdate.getKey(), householdEconomyUpdate.getValue());
      }
      mergeGoodsInto(consumedGoods, partition.consumed());
      mergeGoodsInto(unmetNeed, partition.unmet());
      for (Map.Entry<HouseholdId, Long> deficit : partition.deficits().entrySet()) {
        deficitToday.merge(deficit.getKey(), deficit.getValue(), Long::sum);
      }
    }
  }

  /**
   * 消费一户（{@link #consumeOwnStockPartitioned} 的逐户版；读共享行/意向视图，写本地累加器）。
   *
   * <p>★★ <b>2026-10-09 家户结构修复 Batch 3：需求不再按 {@code population} 现算</b> —— 直接读 app 在本日 {@link
   * #applyNaturalNeedsInto} 注入的 {@code naturalNeeds}；消费开始时把当日粮需求累加进 {@code
   * cycleNaturalNeedMilli}（一天只此一次；新周期第一天的重置仍在流水清零点由 {@link #withCycleNaturalNeed} 完成）。{@code
   * population <= 0} 的行只做这次 0 增量写回，不读账、不消费。
   */
  static void consumeOneHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      Map<HouseholdId, HouseholdEconomy> householdEconomyUpdates,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<HouseholdId, Map<CommodityId, Long>> unmetNeed,
      Map<HouseholdId, Long> deficitToday) {
    HouseholdEconomy current = householdEconomies.get(key);
    if (current == null) {
      throw new IllegalStateException("消费步的家户行不存在（分区键与工作表不一致，拒绝把缺行当成 0 需求）: " + key);
    }
    Map<CommodityId, Long> needs = current.naturalNeeds();
    long need = needs.getOrDefault(GRAIN, 0L);
    HouseholdEconomy householdEconomy =
        current.withNaturalNeedsAndCycle(
            needs, Math.addExact(current.cycleNaturalNeedMilli(), need));
    householdEconomyUpdates.put(key, householdEconomy);
    if (householdEconomy.population() <= 0L) {
      return;
    }
    long stock = stockOf(householdGoods, key, GRAIN);
    long eaten = Math.min(stock, need);
    setStock(householdGoods, key, GRAIN, stock - eaten);
    addGoods(consumedGoods, key, GRAIN, eaten);
    long clothNeed = needs.getOrDefault(CLOTH, 0L);
    long clothStock = stockOf(householdGoods, key, CLOTH);
    long clothGot = Math.min(clothStock, clothNeed);
    if (clothNeed > 0L) {
      setStock(householdGoods, key, CLOTH, clothStock - clothGot);
      addGoods(consumedGoods, key, CLOTH, clothGot);
    }
    if (clothNeed - clothGot > 0L) {
      addGoods(unmetNeed, key, CLOTH, clothNeed - clothGot);
    }
    if (need - eaten > 0L) {
      addGoods(unmetNeed, key, GRAIN, need - eaten);
      deficitToday.put(key, need - eaten);
    }
  }

  /** 家户消费扣减（把 {@code amount} 从该家户库存移进 consumed 流水；缺账 ⇒ 0，不造负库存）。 */
  static void consumeFromHousehold(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    long stock = stockOf(householdGoods, key, commodity);
    long eaten = Math.min(stock, amount);
    setStock(householdGoods, key, commodity, stock - eaten);
    addGoods(consumedGoods, key, commodity, eaten);
  }

  /**
   * ★★ <b>T-fix：非换手损耗扣减的唯一写口</b>（"货物离开某家户的账户、不换手给任何人"这一类；与 {@link #consumeFromHousehold}
   * 的消费/投入扣减同族）。
   *
   * <pre>
   * 可用量 = max(0, stock − frozen)      ← 冻结只表达已明确的占用：**被冻结的货不许被损耗扣掉**
   * 可用量 &lt; requested ⇒ **一点也不扣**（fail-closed，绝不允许部分扣）⇒ consumedMilli = 0，两本账一字未动
   * 可用量 ≥ requested ⇒ 扣 requested **并同址**记 ledger.addLoss(lossAccount, commodity, requested)
   *                       ⇒ Σ余额 + losses 守恒（"账户减 + 损耗账加"在同一个落点里做完，调用方不可能只做一半）
   * </pre>
   *
   * <p>★★ <b>为什么它不能是一个 {@link #applyTransfer}</b>（如实记，T-fix 的核心结论）：{@code Transfer} 的两端
   * <b>恒为两个不同的已登记家户</b>（{@link Transfer} 的构造不变式 {@code from != to} + 本类 {@link #requireHouseholdOf}
   * 的键集 = 现存家户行），而"损耗"<b>没有对端</b> —— 硬塞一个接收方等于伪造一笔转移： 接收方凭空多出货、账面上还看不出破绽（正是本仓最贵的那类账）。⇒ 损耗按 spec
   * §3.2 的「守恒实现口径（唯一写口 + 非换手落点）」落在<b>非换手</b>那一半，且收成<b>一个</b>口：改前是市场轮自己 {@code setHouseholdStock}
   * 直改会话账， 那条旁路既绕开写口、又不知道冻结（T-fix 要拆掉的正是它）。
   *
   * <p>★ 账户 = {@code (家户, 格)}：本方法的键是家户身份（与 {@link #applyTransfer} 同款），"落在哪一格"由调用方保证。
   */
  static LossConsumption consumeForLoss(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      ProductionLedger.Accumulator ledger,
      IndustryId lossAccount,
      HouseholdId household,
      CommodityId commodity,
      long requestedMilli) {
    Objects.requireNonNull(householdGoods, "householdGoods");
    Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(lossAccount, "lossAccount");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(commodity, "commodity");
    if (requestedMilli <= 0L) {
      throw new IllegalArgumentException("损耗扣减量必须为正（0 不是一次扣减）: " + requestedMilli);
    }
    long stock = stockOf(householdGoods, household, commodity);
    long frozen = frozenGoodsOf(householdFrozenGoods, household, commodity);
    if (Math.max(0L, stock - frozen) < requestedMilli) {
      return new LossConsumption(stock, frozen, 0L); // ★ fail-closed：一点也不扣（不许部分扣）
    }
    setStock(householdGoods, household, commodity, stock - requestedMilli);
    // ★ 守恒：账户减、损耗账加（同一句话里的两条腿，调用方拿不到"只减不记"的中间态）
    ledger.addLoss(lossAccount, commodity, requestedMilli);
    return new LossConsumption(stock, frozen, requestedMilli);
  }

  /**
   * ★★★ <b>W3（2026-10-11）：报缺口 —— 经济侧对"人"的唯一动作</b>（原 {@code applyFamine} 的替代者）。
   *
   * <p>★★ <b>本方法不改任何状态、不判死、不缩劳动、不动人口</b>：它只把"这一天经济腿看见的逐户缺口"写进 {@link EconomySession} 的瞬态
   * outbox（{@link EconomySession#recordHouseholdGap}），由 {@code simos-app} 的组合根在 {@code step(day)}
   * 之后取走、翻成 Social 的饥荒输入（约束设计书 §2 的 S-11 第②半：<b>口粮缺口 ⇒ 报社会</b>， 社会据此扣劳动/人口；经济侧不自己扣）。
   *
   * <pre>
   * 逐户（只报"口粮缺口 &gt; 0 或 生产花费缺口非空"的户；两条腿都干净的户不进 outbox = 缺省中性）：
   *   ① productionCostGapByCommodity = 当日市场轮 MarketReport.unmetProductionCostByHousehold()
   *        ★ W2 的读口原样取用（"生产花费（内含口粮）的未满足量"）；**没开市 ⇒ 空表**（不是"缺口 0"）
   *   ② rationGapMilli  = 当日 unmetToday[grain]（"家户吃不上"的量；救济全部走完之后）
   *      rationNeedMilli = 当日 naturalNeeds[grain]（同窗口的分母，由 app 从 Social 注入）
   *   ③ household + population（稳定身份；铁律 1）
   * </pre>
   *
   * <p>★★ <b>窗口口径（与旧判据的唯一差异，具名）</b>：旧判据把"本周期累计缺口 ÷ 本周期累计需求"折成"饿满整周期的比例"， 只在**产业关账日**、只对**关账 unit
   * 名下的家户**跑；本方法按<b>当日</b>报（分子分母同窗口 = 那一天）， <b>逐日对全部家户</b>跑。⇒ 缺口的"结算窗口"从"周期"变成"日"，与"每 tick
   * 生死"（Social 的 ppm/tick + 余数累加器） 同一个时间基准；这正是 W3「两段式 tick + 死亡率移交」要的形态。
   *
   * <p>★ <b>为什么读 {@code unmetToday} 而不是"再算一遍"</b>：它就是消费步（{@link #consumeOneHousehold}）
   * 写下的同一笔账（{@code need − eaten}），并且已经在**借粮/还债之后**（本步的位置）：借到粮的人不再算缺口。 本方法只做"按家户取出 +
   * 报出去"，不引入第二个缺口口径。
   *
   * <p>★ <b>日志（AGENTS §一.9）</b>：逐户明细 TRACE（{@code HOUSEHOLD_GAP}）；当日池子/为什么 DEBUG（{@code
   * HOUSEHOLD_GAP_POOL}）；真有缺口时 INFO 一条具名计数（{@code HOUSEHOLD_GAP_REPORTED}）—— "谁因什么缺了多少"。
   * 全部只读，日志失败不影响结算。
   */
  static void reportHouseholdGaps(
      EconomySession session,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetToday,
      Set<HouseholdId> newCycleHouseholds,
      ProductionLedger.Accumulator ledger,
      long day) {
    Objects.requireNonNull(session, "session（报缺口要落进会话 outbox）");
    // ★ ① W2 的读口：没开市 ⇒ ledger.marketReport() == null ⇒ 空表（"那一天根本没有市场这件事"，
    //   与"市场开了但大家都买到了"是两件事，后者是空 Map —— 两者在缺口语义上等价，在归因上不同）。
    MarketReport marketReport = ledger.marketReport();
    Map<HouseholdId, Map<CommodityId, Long>> productionCostGaps =
        marketReport == null ? Map.of() : marketReport.unmetProductionCostByHousehold();
    long householdsEvaluated = 0L;
    long householdsWithRationGap = 0L;
    long householdsWithProductionCostGap = 0L;
    long rationGapTotalMilli = 0L;
    long rationNeedTotalMilli = 0L;
    long productionCostGapTotalMilli = 0L;
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId key = entry.getKey();
      HouseholdEconomy row = entry.getValue();
      householdsEvaluated++;
      Map<CommodityId, Long> costGap = productionCostGaps.getOrDefault(key, Map.of());
      long rationGapMilli =
          unmetToday.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L); // 当日缺口（毫粮）
      if (rationGapMilli <= 0L && costGap.isEmpty()) {
        continue; // 两条腿都没有 ⇒ 不进 outbox（"没缺口"就是不报，缺省中性）
      }
      long rationNeedMilli = row.naturalNeeds().getOrDefault(GRAIN, 0L); // 当日粮需求（同窗口分母）
      session.recordHouseholdGap(
          new EconomyHouseholdGap(key, row.population(), rationGapMilli, rationNeedMilli, costGap));
      if (rationGapMilli > 0L) {
        householdsWithRationGap++;
        rationGapTotalMilli = Math.addExact(rationGapTotalMilli, rationGapMilli);
      }
      if (!costGap.isEmpty()) {
        householdsWithProductionCostGap++;
        for (long gap : costGap.values()) {
          productionCostGapTotalMilli = Math.addExact(productionCostGapTotalMilli, gap);
        }
      }
      rationNeedTotalMilli = Math.addExact(rationNeedTotalMilli, rationNeedMilli);
      if (EventLog.channel(RAW).isTraceEnabled()) {
        // ★ TRACE：逐户明细（谁、多少、为什么）—— 默认关闭，可单独开（-Dsimos.economy.traceLevel=TRACE）。
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_GAP",
                    EconomyLogSource.ECONOMY_POPULATION,
                    "day",
                    day,
                    "household",
                    key.value(),
                    "population",
                    row.population(),
                    "rationGapMilli",
                    rationGapMilli,
                    "rationNeedMilli",
                    rationNeedMilli,
                    "productionCostGapByCommodity",
                    costGap,
                    "marketRan",
                    marketReport != null,
                    "why",
                    rationGapMilli > 0L
                        ? "ration-short-after-all-relief"
                        : "production-cost-short-after-all-relief"));
      }
    }
    // ★ 回写窗口的基线差分要用到"本周期累计"（app 侧读 FlowRow.unmetNeed），这里只做一条池子 DEBUG。
    long cycleCarried = 0L;
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      if (newCycleHouseholds.contains(entry.getKey())) {
        continue; // 新周期第一天的流水从 0 重记 ⇒ 没有"上期累计"可读
      }
      cycleCarried =
          Math.addExact(cycleCarried, entry.getValue().unmetNeed().getOrDefault(GRAIN, 0L));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_GAP_POOL",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "householdsEvaluated",
                  householdsEvaluated,
                  "householdsWithRationGap",
                  householdsWithRationGap,
                  "householdsWithProductionCostGap",
                  householdsWithProductionCostGap,
                  "rationGapTotalMilli",
                  rationGapTotalMilli,
                  "rationNeedTotalMilli",
                  rationNeedTotalMilli,
                  "productionCostGapTotalMilli",
                  productionCostGapTotalMilli,
                  "marketRan",
                  marketReport != null,
                  "cycleGrainUnmetCarriedMilli",
                  cycleCarried,
                  "why",
                  "w3-report-only: economy-no-longer-kills-or-scales-labor"));
    }
    if (householdsWithRationGap > 0L) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_GAP_REPORTED",
                  EconomyLogSource.ECONOMY_POPULATION,
                  "day",
                  day,
                  "householdsWithRationGap",
                  householdsWithRationGap,
                  "householdsWithProductionCostGap",
                  householdsWithProductionCostGap,
                  "rationGapTotalMilli",
                  rationGapTotalMilli,
                  "rationNeedTotalMilli",
                  rationNeedTotalMilli,
                  "productionCostGapTotalMilli",
                  productionCostGapTotalMilli,
                  "marketRan",
                  marketReport != null,
                  "why",
                  "S11-second-half: ration-gap-goes-to-social"));
    }
  }
}
