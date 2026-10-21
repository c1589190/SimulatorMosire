package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.ConsumptionSettlement.consumeFromHousehold;
import static io.mosire.simos.economy.time.ProductionInputSettlement.drawCycleInputs;
import static io.mosire.simos.economy.time.ProductionInputSettlement.drawableStockOf;
import static io.mosire.simos.economy.time.ProductionInputSettlement.usableScaleOf;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.addGoods;
import static io.mosire.simos.economy.time.SettlementMath.apportionToHouseholds;
import static io.mosire.simos.economy.time.SettlementMath.byStockDescending;
import static io.mosire.simos.economy.time.SettlementMath.hexOfIndustry;
import static io.mosire.simos.economy.time.SettlementMath.mergeGoodsInto;
import static io.mosire.simos.economy.time.SettlementMath.setStock;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.time.ProductionInputSettlement.CapacityDeductionSummary;
import io.mosire.simos.economy.time.ProductionInputSettlement.InputPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ProductionInputDrawSettlement {

  /**
   * 周期投入阶段一个分区的产出（意向 + <b>本 tick 逐 unit 实扣 delta</b> + 本地消费累加器 + 本地账本 + "扣产能"日志读数）。
   *
   * <p>★★ <b>M7fix：为什么是 delta 而不是 {@code cycleInputUsedMilli} 累计值</b>：{@code
   * ProductionDayRequest.Step.finalInputsMilli} 的契约是"本 tick 最终实扣的原料增量"；production 的 {@code execute}
   * 会把它 merge 进**已有的**周期累计 （{@code instance.cycleInputUsedMilli()}）。若分区交回周期累计值，无新扣的 tick 也会把旧累计再
   * merge 一次 ⇒ 重复累计、 tick 42 起 long overflow。周期累计的唯一权威仍由 production 的 {@code executeDay} 维护。
   */
  record InputDrawPartition(
      List<OrderedAccountIntent> intents,
      Map<ProductionUnitId, Map<CommodityId, Long>> inputDeltas,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      ProductionLedger.Accumulator ledger,
      CapacityDeductionSummary deductions) {}

  /**
   * ★★★ <b>M7fix：本 tick 实扣 delta = 现扣后的周期累计 − 现扣前的周期累计</b>。
   *
   * <p>现扣阶段只把本 tick 新扣的量加进分区副本的 {@code cycleInputUsedMilli}；与现扣前的同一实例副本逐商品相减即得 delta。没有新扣的 unit
   * 逐商品差为 0 ⇒ 不出现在结果里（{@link EconomySession#recordDrawnProductionInput} 对空表删行）。
   *
   * @param before 现扣前的实例副本（键集与 {@code after} 相同；用于取现扣前的周期累计）
   * @param after 现扣后的实例副本
   */
  static Map<ProductionUnitId, Map<CommodityId, Long>> inputDeltasOf(
      Map<ProductionUnitId, ProductionProcess> before,
      Map<ProductionUnitId, ProductionProcess> after) {
    Map<ProductionUnitId, Map<CommodityId, Long>> deltas = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : after.entrySet()) {
      ProductionProcess prior = before.get(entry.getKey());
      Map<CommodityId, Long> priorInputs = prior == null ? Map.of() : prior.cycleInputUsedMilli();
      Map<CommodityId, Long> delta = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> input : entry.getValue().cycleInputUsedMilli().entrySet()) {
        long increment =
            Math.subtractExact(input.getValue(), priorInputs.getOrDefault(input.getKey(), 0L));
        if (increment > 0L) {
          delta.put(input.getKey(), increment);
        }
      }
      if (!delta.isEmpty()) {
        deltas.put(entry.getKey(), delta);
      }
    }
    return deltas;
  }

  /**
   * ★★★ <b>M7fix：把本 tick 的实扣 delta 记进会话</b>（协调器线程；空表由会话删行）。
   *
   * <p>DEBUG 事件 {@code PRODUCTION_INPUT_DELTA_RECORDED} 每条 = 一个 (unit × commodity) 的 {@code
   * deltaMilli}； 只记数量，不记逐笔账/隐私。
   */
  static void recordInputDeltas(
      EconomySession session, long day, Map<ProductionUnitId, Map<CommodityId, Long>> deltas) {
    for (Map.Entry<ProductionUnitId, Map<CommodityId, Long>> unitEntry : deltas.entrySet()) {
      session.recordDrawnProductionInput(unitEntry.getKey(), unitEntry.getValue());
      if (!TRACE.isDebugEnabled()) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> input : unitEntry.getValue().entrySet()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "PRODUCTION_INPUT_DELTA_RECORDED",
                    EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                    "day",
                    day,
                    "unit",
                    unitEntry.getKey().value(),
                    "commodity",
                    input.getKey().value(),
                    "deltaMilli",
                    input.getValue()));
      }
    }
  }

  /**
   * ★★ <b>现扣周期投入按 hex 并行</b>（{@link #drawCycleInputs} 的并行外壳）。
   *
   * <p>同格争用（H6-lite 的池子）只在同一 hex 内成立 ⇒ 每个 worker 只拿自己分区那些 hex 的产业副本， 三遍式（调查 → 配给 →
   * 落账）逐字复用；账户写走意向视图，产业写回与消费/账本随分区交回协调器合并。 ★ 产业 id 里没有 {@code @<q>_<r>}
   * 的旧档条目没有格、不参与争用：它们在并行部分之后由协调器按原路径单线程处理。
   */
  static void drawCycleInputsPartitioned(
      EconomySession session,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day,
      EconomyParallelism parallelism,
      SettlementIndex index) {
    drawCycleInputsPartitioned(
        session, accounts, consumedGoods, householdOfActor, ledger, day, parallelism, index, null);
  }

  /**
   * ★★ <b>RF2：现扣只覆盖实际入选/实际有劳动的 unit</b>（{@code producingUnits == null} ⇒ 不限制，legacy 逐值不变）。
   *
   * <p>未入选/无劳动的 unit 不进入调查/配给/落账 ⇒ 不会预扣整周期种子。
   */
  static void drawCycleInputsPartitioned(
      EconomySession session,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day,
      EconomyParallelism parallelism,
      SettlementIndex index,
      Set<ProductionUnitId> producingUnits) {
    Map<ProductionUnitId, ProductionProcess> allUnits = session.sheet().units();
    Map<ProductionUnitId, ProductionProcess> units = allUnits;
    if (producingUnits != null) {
      LinkedHashMap<ProductionUnitId, ProductionProcess> filtered = new LinkedHashMap<>();
      for (Map.Entry<ProductionUnitId, ProductionProcess> entry : allUnits.entrySet()) {
        if (producingUnits.contains(entry.getKey())) {
          filtered.put(entry.getKey(), entry.getValue());
        }
      }
      units = filtered;
    }
    // ★★ R3B.2：产业表只读（模板；日结算不再改它）。
    Map<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    // ★★ R4-E2b：走 relation 的**工作副本选择**（进入执行可能刚插入新 unit 的 relation；未物化时等于 base）。
    Map<ProductionUnitId, ProductionRules> relations = session.sheet().relationsOrBase();
    // ★ S3：缩产/停业后的"计划规模"要进投入调查（条件缺失 ⇒ 系数 1000‰ ⇒ 旧行为逐值相同）。
    Map<ProductionUnitId, OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
    TreeMap<String, LinkedHashMap<ProductionUnitId, ProductionProcess>> byHex = new TreeMap<>();
    List<ProductionUnitId> noHex = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : units.entrySet()) {
      Optional<String> hexKey = IndustryHexKeys.hexKeyOf(entry.getValue().industry());
      if (hexKey.isEmpty()) {
        noHex.add(entry.getKey());
        continue;
      }
      byHex
          .computeIfAbsent(hexKey.get(), ignored -> new LinkedHashMap<>())
          .put(entry.getKey(), entry.getValue());
    }
    // ★★ W2-fix3：本轮"扣产能"日志读数 —— 分区各记各的（worker 内），在这里按分区序合并后**只发一条 INFO**。
    CapacityDeductionSummary deductions = new CapacityDeductionSummary(day);
    if (!byHex.isEmpty()) {
      AccountSnapshot snapshot = accounts.snapshot();
      PartitionPlan plan =
          PartitionPlan.of(
              SettlementStage.INPUT_DRAW, byHex.keySet(), parallelism.partitionCount());
      List<InputDrawPartition> partitions =
          SettlementExecutor.execute(
              plan,
              partition -> {
                AccountIntentBuffer buffer =
                    AccountIntentBuffer.onHexPartition(
                        snapshot,
                        SettlementStage.INPUT_DRAW,
                        partition.partitionIndex(),
                        plan.partitionCount());
                BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
                LinkedHashMap<ProductionUnitId, ProductionProcess> localUnits =
                    new LinkedHashMap<>();
                for (String hex : partition.canonicalKeys()) {
                  localUnits.putAll(byHex.get(hex));
                }
                // ★★★ M7fix：现扣前的实例副本 —— 现扣后逐商品相减即得"本 tick 实扣 delta"。
                Map<ProductionUnitId, ProductionProcess> beforeDraw =
                    new LinkedHashMap<>(localUnits);
                LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumed = new LinkedHashMap<>();
                ProductionLedger.Accumulator partitionLedger =
                    new ProductionLedger.Accumulator(day, partition.partitionIndex());
                CapacityDeductionSummary partitionDeductions =
                    drawCycleInputs(
                        localUnits,
                        industries,
                        householdEconomies,
                        relations,
                        operatorConditions,
                        index,
                        tables.householdGoods,
                        tables.householdMoney,
                        consumed,
                        householdOfActor,
                        partitionLedger,
                        day,
                        producingUnits);
                return new InputDrawPartition(
                    buffer.drainIntents(),
                    inputDeltasOf(beforeDraw, localUnits),
                    consumed,
                    partitionLedger,
                    partitionDeductions);
              },
              // ★★★ D-032 B3（§13.13.7 第 3 条）：**本批确定的串行实现** —— 现扣周期投入这一段不再起 worker。
              //   ★ 只动并行度：分区计划（同一个 `PartitionPlan.of(..., parallelism.partitionCount())`，结构分区数
              //     仍是常量 32）、同一个 `AccountIntentBuffer`、同一个 `SettlementExecutor#commit` 提交序一字未改；
              //     `pool == null` ⇒ 就地按**分区序**逐个跑（见 `SettlementExecutor` 的类注）⇒ 逐值结果与
              //     "确定性归并的并行"同一套新规则下一致（协调器侧的合并循环本来就按分区序 absorb）。
              //   ★ 业务日序不变（分段位置、到货、消费/市场次序均未动）；★ 未来恢复并行必须保持"同 tick 生效条件 /
              //     全局预算 / 候选态与归并语义"，本批**不**为它预加框架。
              null);
      List<OrderedAccountIntent> intents = new ArrayList<>();
      for (InputDrawPartition partition : partitions) {
        intents.addAll(partition.intents());
      }
      accounts.commit(intents);
      // ★ B1：本日"现扣周期投入"的**唯一权威**现在由 production 的候选态承载（见 {@code executeDay}）。
      //   本循环只把账户侧的消费并入全局累加器；分区副本里的 unit 不再写回主表。
      for (InputDrawPartition partition : partitions) {
        mergeGoodsInto(consumedGoods, partition.consumed());
        // ★★ M7：协调器按分区序 absorb ⇒ 列表序 = (阶段 → 分区序 → 分区内生成序)；转移 id 带 p<partition> 段，
        //   seq 是分区内序号，与旧串行号的对应关系不保证。同代码态内 1/4/8 线程确定；V 阶段跨代码态比较
        //   transfers/outputAccruals/ruleSettlements 一律"按业务键排序后比较集合"，不得按 id 或列表下标逐条比。
        ledger.absorb(partition.ledger());
        // ★★ W2-fix3：分区账按**分区计划序**合并（{@code SettlementExecutor.execute} 保证结果序 = 计划序）。
        deductions.merge(partition.deductions());
      }
      // ★★★ M7fix（D-032 §4.3 数据流第 5 段 / §1.2 M7）：现扣阶段交回的是**本 tick 逐 unit 实扣 delta**，
      //   不是 {@code cycleInputUsedMilli} 周期累计值 —— `executeDay` 的 `finalInputsMilli` 契约就是"本 tick
      // 增量"，
      //   production 会把它 merge 进自己的周期累计（唯一权威），economy 不得把累计值再交一次（否则每 tick 重复累加）。
      //   ★ 为什么必须经过契约而不是就地写实例表：现扣发生在**并行分区**里（每分区一份实例副本），
      //     而 production 的候选态是**单线程独占**的（引擎不是线程安全的"每分区一个副本"形态）
      //     ⇒ 分区结果先在协调器线程汇总，再一次性推回；顺序由此确定（分区序 → 分区内生成序）。
      for (InputDrawPartition partition : partitions) {
        recordInputDeltas(session, day, partition.inputDeltas());
      }
    }
    if (!noHex.isEmpty()) {
      // ★ 旧档/手搭状态的产业 id 没有格键：没有位置就没有账户可安全分区 ⇒ 协调器按原路径单线程处理。
      LinkedHashMap<ProductionUnitId, ProductionProcess> local = new LinkedHashMap<>();
      for (ProductionUnitId id : noHex) {
        local.put(id, units.get(id));
      }
      // ★★★ M7fix：与分区路径同一口径 —— 只把本 tick 实扣 delta 交给会话，绝不交周期累计值。
      Map<ProductionUnitId, ProductionProcess> beforeDraw = new LinkedHashMap<>(local);
      deductions.merge(
          drawCycleInputs(
              local,
              industries,
              householdEconomies,
              relations,
              operatorConditions,
              index,
              accounts.householdGoods(),
              accounts.householdMoney(),
              consumedGoods,
              householdOfActor,
              ledger,
              day,
              producingUnits));
      recordInputDeltas(session, day, inputDeltasOf(beforeDraw, local));
    }
    // ★★ W2-fix3（§一.9）：轮末**一条 INFO 汇总**（协调器线程；与分区数无关 ⇒ 行数恒为 1）。
    //   逐 unit 明细已在各分区里按 DEBUG 发出（PRODUCTION_CAPACITY_DEDUCTED_DETAIL）。
    deductions.logSummary();
  }

  /**
   * ★★ <b>落账：把一个 unit 的投入计划扣成实际发生额</b>（H6-lite 第三遍；口径见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <p>取用次序（四层，越靠前越优先；每一笔都经 {@code recordInputDraw} 落账 ⇒ "任何库存变动必有对应转移记录"那条不变量的落点仍然只有一处）：
   *
   * <pre>
   * ③ 一  ：relation 名下的家户账 —— 按人口占比 + 最大余数法分派（Σ分派 == remaining，不再逐户丢余数）
   * ③ 一b ：同层还有货的账**补齐**（持仓降序）
   * ③ 二  ：★ H6-lite：**争用**的商品的**池子里、别人名下的**账（持仓降序）—— 只在上面取不满时进场
   * </pre>
   */
  static void drawOneProductionProcessInputs(
      InputPlan plan,
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day) {
    long usableScale = usableScaleOf(plan);
    if (usableScale <= 0L) {
      return; // 一点料都筹不到（或本格没有产能）⇒ 不扣、规模自然 0 —— "地荒着"
    }
    ProductionProcess unit = plan.unit;
    Map<CommodityId, Long> drawnTotal = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
      CommodityId commodity = entry.getKey();
      long perUnit = entry.getValue();
      if (perUnit <= 0L) {
        continue;
      }
      // 毫单位（★ 单位规模 × 毫单位/单位规模）—— 整个 unit 本周期要用的料（争用 ⇒ 已被第二遍换成配给额）。
      long remaining = plan.limitOf(commodity, usableScale);
      if (remaining <= 0L) {
        continue; // ★ 配给的整数份额可能是 0 ⇒ 这一家这一种料一根都不取
      }
      // ③ 一：按人口占比 + **最大余数法**分派（Σ分派 == remaining，不再逐户丢余数），逐户上限 = 它的余额。
      for (Map.Entry<HouseholdId, Long> ask :
          apportionToHouseholds(plan.ownKeys, householdEconomies, remaining).entrySet()) {
        long onHand = stockOf(householdGoods, ask.getKey(), commodity);
        long available =
            drawableStockOf(householdEconomies, householdGoods, ask.getKey(), commodity);
        long drawn = Math.min(available, ask.getValue());
        if (drawn <= 0L) {
          continue; // 缸空/不够（或只剩一 tick 口粮）⇒ 这一户出不起它那一份
        }
        logProductionInputReserved(
            day, unit, ask.getKey(), commodity, ask.getValue(), drawn, onHand, available);
        recordInputDraw(
            ask.getKey(),
            unit,
            commodity,
            drawn,
            householdEconomies,
            householdGoods,
            householdMoney,
            consumedGoods,
            householdOfActor,
            ledger);
        remaining -= drawn;
        drawnTotal.merge(commodity, drawn, Long::sum);
      }
      // ③ 一b：缺口由**同层还有货的账**补齐（持仓量降序、取多少 = 缺多少）。
      if (remaining > 0L) {
        remaining =
            drawFromKeys(
                plan,
                byStockDescending(plan.ownKeys, householdGoods, commodity),
                commodity,
                remaining,
                drawnTotal,
                householdEconomies,
                householdGoods,
                householdMoney,
                consumedGoods,
                householdOfActor,
                ledger,
                day);
      }
      // ③ 二：★ H6-lite —— **争用**的商品的池子里、**别人名下**的账（持仓降序）。
      List<HouseholdId> shared = plan.sharedKeys.get(commodity);
      if (remaining > 0L && shared != null) {
        List<HouseholdId> others = new ArrayList<>(shared.size());
        for (HouseholdId key : shared) {
          if (!plan.ownKeys.contains(key)) {
            others.add(key);
          }
        }
        drawFromKeys(
            plan,
            byStockDescending(others, householdGoods, commodity),
            commodity,
            remaining,
            drawnTotal,
            householdEconomies,
            householdGoods,
            householdMoney,
            consumedGoods,
            householdOfActor,
            ledger,
            day);
      }
    }
    if (!drawnTotal.isEmpty()) {
      // ★★★ B1/M7fix（D-032）：旧代码在这里 `units.put(plan.id, unit.withCycleState(..., accumulated))`
      //   ——那是 economy **就地写实例状态**的最后一处。现扣结果只累进**本分区副本**
      //   （{@code partition.inputDeltas()} 交回"现扣后 − 现扣前"的 delta），由协调器经 {@code EconomySession}
      //   汇总后交给 {@code ProductionRuntime#executeDay} 的 {@code finalInputsMilli}（本 tick 增量）
      //   ⇒ 周期投入的**累计权威**落在 production。
      Map<CommodityId, Long> accumulated = new LinkedHashMap<>(unit.cycleInputUsedMilli());
      for (Map.Entry<CommodityId, Long> entry : drawnTotal.entrySet()) {
        accumulated.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
      units.put(plan.id, unit.withCycleState(unit.progressDays(), accumulated));
    }
  }

  /**
   * 从一串候选账里按序取料直到 {@code remaining} 满足（{@link #drawOneIndustryInputs} 的 ③一b 与 ③二 共用）。
   *
   * @return 还没取满的余量（0 = 取满了）
   */
  static long drawFromKeys(
      InputPlan plan,
      List<HouseholdId> keys,
      CommodityId commodity,
      long remaining,
      Map<CommodityId, Long> drawnTotal,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day) {
    long left = remaining;
    for (HouseholdId key : keys) {
      if (left <= 0L) {
        break;
      }
      long onHand = stockOf(householdGoods, key, commodity);
      long available = drawableStockOf(householdEconomies, householdGoods, key, commodity);
      long drawn = Math.min(available, left);
      if (drawn <= 0L) {
        continue;
      }
      logProductionInputReserved(day, plan.unit, key, commodity, left, drawn, onHand, available);
      recordInputDraw(
          key,
          plan.unit,
          commodity,
          drawn,
          householdEconomies,
          householdGoods,
          householdMoney,
          consumedGoods,
          householdOfActor,
          ledger);
      left -= drawn;
      drawnTotal.merge(commodity, drawn, Long::sum);
    }
    return left;
  }

  /**
   * ★★ <b>RF2：扣种时保留一 tick 基础口粮的可 grep 读数</b>（只读；不影响算式）。
   *
   * <p>只在粮作为生产投入且该户确有口粮保留额时发射 DEBUG：{@code unit/household/commodity/requested/drawn/
   * onHand/reservedForFood}。真实扣减仍只走 {@link #recordInputDraw}。
   */
  private static void logProductionInputReserved(
      long day,
      ProductionProcess unit,
      HouseholdId household,
      CommodityId commodity,
      long requested,
      long drawn,
      long onHand,
      long available) {
    if (!TRACE.isDebugEnabled() || !EconomySettlement.GRAIN.equals(commodity)) {
      return;
    }
    long reservedForFood = Math.max(0L, onHand - Math.max(0L, available));
    if (reservedForFood <= 0L) {
      return;
    }
    EventLog.channel(TRACE)
        .debug(
            LogEvent.of(
                "PRODUCTION_INPUT_RESERVED",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                day,
                "unit",
                unit.id().value(),
                "household",
                household.value(),
                "commodity",
                commodity.value(),
                "requested",
                requested,
                "drawn",
                drawn,
                "onHand",
                onHand,
                "reservedForFood",
                reservedForFood));
  }

  /**
   * ★★ <b>relation 指名的投入供方 → 它那一本（或那一批）账</b>（H3；两处边界见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <pre>
   * ToCohort(c)                  ⇒ {c}（**必须存在**、且必须住在该 unit 那一格 —— 见下）
   * ToActor(a) 且 a 是本世界现存的家户 actor ⇒ {cohortOf(a)}（唯一拼写点 HouseholdActors）
   * ToActor(a) 其余（聚合主体：庄园/作坊/产业型家户）⇒ **该 unit 名下的家户账**（householdKeysOf，唯一事实）
   * </pre>
   *
   * <p>★★ <b>两条 fail-closed</b>：命名的 cohort 必须有行；命名的账必须住在该 unit 那一格。
   */
  static List<HouseholdId> supplierAccountsOf(
      Payee supplier,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ActorRef, HouseholdId> householdOfActor,
      SettlementIndex index) {
    if (supplier instanceof Payee.ToHousehold toHousehold) {
      HouseholdId key = toHousehold.household();
      if (!householdEconomies.containsKey(key)) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账，"
                + "把'配置错'静默当成'缸里没有'会让账面上看不出问题）：unit="
                + unit.id()
                + " 供方="
                + key);
      }
      requireSupplierHex(householdEconomies.get(key).view().hex(), industry);
      return List.of(key);
    }
    if (supplier instanceof Payee.ToCohort toCohort) {
      // ★ S1 兼容档：旧档的 ToCohort 按视图反查**恰一个**家户（构造期归一化已把一对一的转成
      //   ToHousehold；走到这里说明视图有歧义或状态还没归一，故 fail-closed，不猜第几个）。
      HouseholdId matched = null;
      for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
        if (householdEconomy.view().equals(toCohort.cohort())) {
          if (matched != null) {
            throw new IllegalStateException(
                "旧档 ToCohort 视图对上了多个家户（S1 起视图不再是唯一身份）："
                    + toCohort.cohort()
                    + " ⇒ "
                    + matched
                    + " / "
                    + householdEconomy.id());
          }
          matched = householdEconomy.id();
        }
      }
      if (matched == null) {
        throw new IllegalStateException(
            "关系指名的投入供方没有家户行（H3 fail-closed：供方的账就是它的家户账）：unit="
                + unit.id()
                + " 供方视图="
                + toCohort.cohort());
      }
      requireSupplierHex(householdEconomies.get(matched).view().hex(), industry);
      return List.of(matched);
    }
    ActorRef actor = ((Payee.ToActor) supplier).actor();
    HouseholdId named = householdOfActor.get(actor);
    if (named != null) {
      requireSupplierHex(householdEconomies.get(named).view().hex(), industry);
      return List.of(named);
    }
    // ★ 聚合主体：它的账在 actor 切片上（economy 看不见）⇒ 由**该 unit 名下的家户账**代理（见方法注释）。
    List<HouseholdId> proxied = index.householdsOf(unit.id());
    for (HouseholdId key : proxied) {
      requireSupplierHex(householdEconomies.get(key).view().hex(), industry);
    }
    return proxied;
  }

  /** 供方账必须在该 unit 的产业那一格（一条转移只有一个 {@code location}；见 {@link #supplierAccountsOf}）。 */
  static void requireSupplierHex(HexCoord supplierLocation, IndustryDefinitionView industry) {
    HexCoord location = hexOfIndustry(industry.id());
    if (!supplierLocation.equals(location)) {
      throw new IllegalStateException(
          "关系指名的投入供方不住在这个 unit 那一格（H3 fail-closed：账户 = (actor, location)，"
              + "跨格的供方说不清料从哪本账出）：activity="
              + industry.id()
              + " 供方的格="
              + supplierLocation
              + " 产业的格="
              + location);
    }
  }

  /**
   * ★★ <b>把一笔投入的划转落账</b>（H3；两条路径，见 {@link #drawCycleInputs} 的方法注释）：
   *
   * <pre>
   * ① 供方 ≠ 经营者，且经营者的账**在会话副本里**（"地主出种"那类）⇒ 走转移：
   *      mint(INPUT_REQUISITION: 供方 actor → 经营者 actor) → applyTransferToHouseholds（唯一 applier：供方 −、经营者 +）
   *      → 随后在**经营者**账上消费掉这一笔（consumed 记在经营者：它是这批料的消费者）；
   * ② 其余情形（供方 == 经营者；或经营者的账不在副本里 —— 聚合主体）⇒ 不产生转移：
   *      供方 −余额、consumed 记在**供方**（H2 的既有口径："日耗与投入不是转移"）。
   * </pre>
   *
   * <p>★ 两条路径的<b>共同点</b>（也是守恒式成立的关键）：被扣的账**一定**是会话副本里的那一本（或转移的两端都在副本里）， 且 {@code consumed}
   * <b>恰好记一次</b>（记在"这批料的消费者"那本账上）⇒ {@code Σ(前库存) − Σ(后库存) == Σconsumed − Σincome} 那条逐商品守恒式两条路都成立。
   *
   * @param supplierKey 出料的那个家户（余额由此扣除）
   * @param drawn 已经算好的实扣量（&gt; 0，且 ≤ 该家户在该商品上的余额）
   */
  static void recordInputDraw(
      HouseholdId supplierKey,
      ProductionProcess unit,
      CommodityId commodity,
      long drawn,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    HouseholdId operatorKey = householdOfActor.get(unit.operator());
    // ★★ **T4：投入同时记进当天的 ledger**（I4.2 的 `ΣProductionInputs` 的唯一来源）——
    //   它与"这东西从谁的账上出"是两件事：前者是**读数**，后者是**转移凭据**（H2 的口径）。
    //   ★ ledger 的键仍是**产业模板 id**（读数按产业汇总），不是 unit id。
    ledger.addInput(unit.industry(), commodity, drawn);
    if (operatorKey != null && !operatorKey.equals(supplierKey)) {
      // ① 两个主体都在家户账里 ⇒ 走转移（唯一 applier 落副本），随后由经营者消费掉。
      HouseholdEconomy supplierHouseholdEconomy = householdEconomies.get(supplierKey);
      if (supplierHouseholdEconomy == null) {
        throw new IllegalStateException(
            "投入供方不在家户表里（账户 must 有 location，拒绝静默用 (0,0)）: " + supplierKey);
      }
      Transfer requisition =
          ledger.mint(
              HouseholdActors.of(supplierKey),
              unit.operator(),
              supplierHouseholdEconomy.view().hex(),
              Map.of(commodity, drawn),
              TransferReason.INPUT_REQUISITION);
      applyTransfer(householdGoods, householdMoney, householdOfActor, requisition);
      consumeFromHousehold(householdGoods, consumedGoods, operatorKey, commodity, drawn);
      return;
    }
    // ② 其余情形：投入被生产直接吃掉（不写"给产业"的那条转移 —— 收端的账不在家户副本里，见方法注释）。
    long stock = stockOf(householdGoods, supplierKey, commodity);
    setStock(householdGoods, supplierKey, commodity, stock - drawn);
    addGoods(consumedGoods, supplierKey, commodity, drawn);
  }

  /** ★★ 索引口径的“一个批次摊到哪些家户行”（R4-B.3a-perf；只有调用方仍需给 target 集合，不再扫全量配额）。 */
  static List<HouseholdId> householdKeysOfLot(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      List<ProductionUnitId> units,
      ResidenceKind residence,
      SettlementIndex index) {
    LinkedHashSet<ProductionUnitId> targets = new LinkedHashSet<>(units);
    LinkedHashSet<HouseholdId> keys = new LinkedHashSet<>();
    for (ProductionUnitId target : targets) {
      for (HouseholdLaborCommitment laborCommitment : index.allocationsOfUnit(target)) {
        if (ResidenceKind.ofLot(laborCommitment.group()) == residence
            && householdEconomies.containsKey(laborCommitment.household())) {
          keys.add(laborCommitment.household());
        }
      }
    }
    List<HouseholdId> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  // ★ B12 / DEF-T6-4 #5（UPM_UNCALLED_PRIVATE_METHOD）：原 `unitsOfHouseholds(...)` 已**删除**。
  //   读者—写者对照（§14.18.3 要求的处置形制）：
  //   · 读者：本类内 **0 处**；它是 `private static`，类外不可达 ⇒ 全仓读者 **0**（`grep` 只命中
  //     `SettlementIndex` 的注释与 `DebtCapacityBook` 的同名局部变量，不是本方法）。
  //   · 写者：它只**读**三张入参表（classes / production 实例投影 / 配额表），自身不写任何状态。
  //   · 替代：同一事实的当刻索引由 `SettlementIndex#unitsOfHousehold`（日结算入口一次建好）承担；
  //     周期翻篇判定读 `settlementIndex.industriesByHex()` + `unitsOfHousehold`，不再现扫配额表。
  //   ⇒ 纯死代码：无读者即无行为，删除不改变任何结算路径。

  /**
   * ★★ <b>每条家户行的 {@code cycleDays}</b>：取**它供给的那些产业的最长周期**。
   *
   * <p>★★ <b>W5.5 后的用途收窄（如实记）</b>：它原先服务两处 —— ① 借粮腿放贷方的"本周期自需"（{@code lendableOf}， 已随该腿删除）；②
   * 各行流水的"本期"何时翻篇（{@link FlowRow} 的清零窗口，**仍在用**）。⇒ 本方法保留，但它的 {@code max} 口径现在只服务流水翻篇；{@code
   * DebtCapacityBook}（信用/headroom 的唯一算法，服务只读诊断） 仍按同一份口径自算一次（它有自己的 {@code
   * cycleDaysByHousehold(EconomyData, Map, ProductionDefinitionLookup)}）。
   *
   * <p>★ <b>为什么取 max</b>：多产业的家户没有一个自己的周期 ⇒ 取**最长**的那个是保守方向（不会把它这一周期的口粮当余粮）。 真档三个产业的 {@code
   * cycleDays} 都是 120 ⇒ 与改前逐值相同。
   *
   * <p>★ <b>没有配额的家户</b>（供给集合为空）：退回**本格产业的最长周期**（那格至少有一个产业 —— 见 {@code EconomyData} 的
   * 构造期守卫）；连产业都没有的格 ⇒ 记 0（保留额 0，与改前的隐含口径同侧：没有周期就没有"整周期自需"）。
   *
   * @param industriesByHex 格键 → 该格产业 id（日结算由 {@link SettlementIndex#industriesByHex()}
   *     一次给出；读口见公开重载）
   */
  static Map<HouseholdId, Long> cycleDaysByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, Set<ProductionUnitId>> unitsOfHousehold,
      Map<String, List<IndustryId>> industriesByHex) {
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry : householdEconomies.entrySet()) {
      HouseholdId key = householdEntry.getKey();
      Set<ProductionUnitId> supplied = unitsOfHousehold.getOrDefault(key, Set.of());
      long cycleDays = 0L;
      for (ProductionUnitId unitId : supplied) {
        ProductionProcess unit = units.get(unitId);
        IndustryDefinitionView industry = unit == null ? null : industries.get(unit.industry());
        if (industry != null) {
          cycleDays = Math.max(cycleDays, industry.cycleDays());
        }
      }
      if (cycleDays == 0L) {
        HouseholdEconomy householdEconomy = householdEntry.getValue();
        if (householdEconomy != null) {
          // ★ R4-B.3a-perf：本格产业由入口索引一次给出（旧实现逐无配额家户扫全量产业表）。
          for (IndustryId industryId :
              industriesByHex.getOrDefault(
                  IndustryHexKeys.hexKey(
                      householdEconomy.view().hex().q(), householdEconomy.view().hex().r()),
                  List.of())) {
            IndustryDefinitionView industry = industries.get(industryId);
            if (industry != null) {
              cycleDays = Math.max(cycleDays, industry.cycleDays());
            }
          }
        }
      }
      byHousehold.put(key, cycleDays);
    }
    return byHousehold;
  }
}
