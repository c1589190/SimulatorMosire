package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ConsumptionSettlement.consumeOwnStockPartitioned;
import static io.mosire.simos.economy.time.LaborSettlement.reallocateLaborPartitioned;
import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.cycleDaysByHousehold;
import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.drawCycleInputsPartitioned;
import static io.mosire.simos.economy.time.ProductionInputSettlement.inputShortfallOf;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.enterprisesByProcess;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.viewToHousehold;
import static io.mosire.simos.economy.time.ProductionRunSettlement.driveProductionDay;
import static io.mosire.simos.economy.time.ProductionRunSettlement.harvestPartitioned;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.logHaulServiceOutput;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalByIndustry;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalGoods;
import static io.mosire.simos.economy.time.SettlementMath.hexOfIndustry;
import static io.mosire.simos.economy.time.ShipmentSettlement.deliverShipments;
import static io.mosire.simos.economy.time.ShipmentSettlement.expireUnservedHaulService;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.EconomySettlement.ClosedUnit;
import io.mosire.simos.economy.time.ProductionRunSettlement.HarvestWork;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IndustryDefinitionView.CurrentStageInput;
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
import java.util.Set;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayProductionPipeline {

  /**
   * ★★★ P3c2：生产阶段的**基础装配**（不依赖消费/劳动分配结果）。
   *
   * <p>这里只做"一天只该建一次的读表/快照/清零"：家户 ↔ unit / actor 反查、到货、套利收集器、本日实扣读数清零点、 W3 匹配快照，以及逐户 {@code
   * cycleDays} 派生。**不得**在这里扣投入、排劳动或做消费。
   *
   * <p>★ P3c3/RC0a 可按新日序单独调用本方法（{@code prepareProductionBase → S3 → S4 settleConsumption →
   * drawInputsAndPlanLabor → driveProduction}）；旧 {@link #prepareConsumption} 仍按原顺序调用它。
   */
  static void prepareProductionBase(SettlementDayContext ctx) {
    // ★★★ RF4a：M3 的土地买盘会读 `ctx.hexToIndustries`；本字段原先只在 M7 `closeAndHarvest`
    //   赋值，导致 M3 永远看到 null、无地户买盘恒为 0。这里在 M3 之前补上同一个索引（入口索引在
    //   `withLabor` 重建时原样传递；`closeAndHarvest` 的既有赋值保留不动，幂等）。
    ctx.hexToIndustries = ctx.settlementIndex.industriesByHex();
    // ★★ **H0：家户 → 它供给的产业**（从配额表推，唯一拼写点见 {@link #industriesOfHouseholds}）——
    //   本日的两处都要它：① 各行流水"本期"何时翻篇（家户没有自己的周期，见流水循环的注释）；
    //   ② 借粮的"本周期自需"要一个 cycleDays（见 {@link #cycleDaysByHousehold}）。
    ctx.unitsOfHousehold = ctx.settlementIndex.unitsByHousehold();

    // ★★ **H2：家户 actor 的反查表**（一天建一次）—— "这条转移的某一端是不是家户、是哪一家"这个问题
    //   在三个落点（关系实付 / 同格取材 / 同格借粮）**只能有一个答案**，故它在这里建好、逐处传下去。
    //   ★ 键集一天不变（人口的增减不改行的身份），故建一次就够。
    ctx.householdOfActor = ctx.settlementIndex.householdByActor();

    // ── 0b. 到货（M2.4：在途是跨 tick 状态；到达日"在途减、目的地库存增"）────────────────────
    //   ★ 必须排在消费/市场/借粮**之前**：到货的粮当天就能吃、当天就能再挂牌（但到货前消费不到它）。
    //   ★ 损耗逐票由买方承担（M2.5）：到达时从在途量里扣、记进 ledger 的损耗账户；买方只收到净额。
    //   ★ 迟到（手工搭的状态里 arrivalTick < day）照样补投，不让货卡在在途表里。
    ctx.shipments = ctx.session.sheet().shipments();
    ctx.shipmentsBefore = ctx.shipments.size();
    deliverShipments(ctx.day, ctx.session, ctx.accounts, ctx.ledger, ctx.parallelism);
    if (ctx.shipmentsBefore > 0) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "SHIPMENTS_ARRIVED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "arrivedBatches",
                  ctx.shipmentsBefore - ctx.shipments.size(),
                  "remainingInTransit",
                  ctx.shipments.size()));
    }
    if (TRACE.isDebugEnabled() && ctx.shipmentsBefore > 0) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SHIPMENTS_ARRIVED_DETAIL",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "arrivedBatches",
                  ctx.shipmentsBefore - ctx.shipments.size(),
                  "remainingInTransit",
                  ctx.shipments.size()));
    }

    // ★★ 2026-10-08（阶段 1）：本日套利决定的收集器 —— 排序阶段（LaborQueueSettlement）往里放，
    //   市场阶段（MarketSettlement）取走。逐日瞬态、不进状态（§4.3.5 的快照纪律）。
    ctx.arbitrageCollector = new MarketArbitragePlan.Collector();
    // ★★★ B8（缺陷 DEF-T2b-2）：**本日实扣读数（`drawnProductionInputs`）的唯一清零点** —— 位置是决定性的：
    //   必须在**本日两处现扣之前**（下面 `plantingDrawsFirst` 两支各自的第一阶段），且**只在本段入口清一次**。
    //   ★ 为什么不能留在后面（`driveProductionDay` 之前）清：那会把本日刚落进去的实扣量抹掉，使
    //     `executeDay` 的 `finalInputsMilli` 恒空 ⇒ 投入那一路规模恒 0 ⇒ 该生产日不推进、毛产恒 0。
    //   ★ 为什么不能不清：读数按"本生产日"语义（见 `EconomySession#clearDrawnProductionInputs`），
    //     同一会话跨多日（`EconomyDayStepper#step` 连推）时不得把昨天的实扣量当成今天的。
    ctx.session.clearDrawnProductionInputs();
    // ★ P4a：MONEY_WAGE 招工不再读工资可支付库存 lookup（只付货币、未付成债）。

    // ── H4：逐户 cycleDays（原在消费段；P3c2 起归基础装配）──────────────────────────────
    //   ★ H4：每条家户行的 cycleDays 提成局部量 —— 它喂"市场自留"（MarketSettlement），
    //     别处各算一遍就是同一个量的第二处拼写点（算错不会报错，只会让两处口径悄悄漂开）。
    //   ★ P3c2：位置从消费段移到基础装配：它只读 units/industries/households（+ 入口索引的
    //     industriesByHex），不读 consumed；`withLabor` 重建索引时 industriesByHex 原样传递 ⇒ 逐值不变。
    ctx.cycleDaysByHousehold =
        cycleDaysByHousehold(
            ctx.householdEconomies,
            ctx.industries,
            ctx.units,
            ctx.unitsOfHousehold,
            ctx.settlementIndex.industriesByHex());
  }

  /**
   * ★★★ P3c2：**现扣周期投入 + 本日劳动分配**（原 {@code plantingDrawsFirst} 两支的共同动作）。
   *
   * <p>调用方决定它与消费的先后：{@code plantingDrawsFirst == true} ⇒ 本方法在消费前； 否则在消费后。方法内部次序恒为：现扣投入 → 劳动排队/再分配 →
   * 重建配额侧索引。
   */
  static void drawInputsAndPlanLabor(SettlementDayContext ctx) {
    // ── 0. 现扣周期投入（周期的第一天）：**在当天吃饭之前**把种子/原料划走（v2 spec §3.2）──────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    //   ★★ H3（C3）：这一步里"谁出料"由 relation.inputSupplier 说 —— 家户供方取它自己那一本账（缸不够就扣光 ⇒ 规模缩）；
    //     聚合主体（庄园/作坊/产业型家户）的账 economy 看不见 ⇒ 由该产业名下的家户账代理；第一层取不满时同格兜底（粮除外）。
    //     逐条口径见 {@link #drawCycleInputs} 的方法注释。
    drawCycleInputsPartitioned(
        ctx.session,
        ctx.accounts,
        ctx.consumedGoods,
        ctx.householdOfActor,
        ctx.ledger,
        ctx.day,
        ctx.parallelism,
        ctx.settlementIndex);
    // ★★ P2-B：有 modes 的世界走"每 tick、按家户的利润率排队"（§13.4/§13.5）；旧档/未接线世界保留
    //   hex 分区再分配（换入空集逐值等价旧路径）。
    // ★★ B5（§13.15.2 裁定十八）：`modes` 读日视图（default → production 定义权威）⇒ 段内恒定。
    if (ctx.dayView.modes().isEmpty()) {
      // ★ R2：劳动再分配已按 hex 并行（配额键含产业 id ⇒ 跨 hex 无冲突；同一批次跨 hex 的全局协调留给 R3）。
      // ★★ R4-E2b：今天刚进入的 unit 不触发"本周期是第一天"的重排判定（它今天确实是 0，但它不属于既有周期的重排对象）。
      reallocateLaborPartitioned(
          ctx.session, ctx.parallelism, ctx.settlementIndex, ctx.enteredToday);
    } else {
      // ★★ §4.3.1：劳动分配、库存分配、投入扣减是**同一次遍历**的三个动作 —— 本调用点**恒在投入扣减之后**
      //   （本方法内 `drawCycleInputsPartitioned` 先于排队）⇒ 套利快照里的库存已是扣完料的余额（I14 由此结构性成立）。
      //   ★ 与消费的先后仍由调用方按 `plantingDrawsFirst` 决定；但两种次序下排队看到的都是扣完料的余额。
      LaborQueueSettlement.apply(
          ctx.session,
          ctx.settlementIndex,
          ctx.day,
          ctx.composition,
          ctx.enteredToday,
          ctx.accounts,
          ctx.arbitrageCollector,
          ctx.survivalSocialFacts);
    }
    // ★ 配额被改写 ⇒ 换一份“配额侧”视图，后续（unit 家户归属/市场参与者/人口回写）继续 O(1) 查表。
    ctx.settlementIndex =
        ctx.settlementIndex.withLabor(ctx.units, ctx.householdEconomies, ctx.laborCommitments);
  }

  /**
   * ★★★ <b>P3c3：M6 的实际劳动编排</b>（只排劳动、不扣投入）。
   *
   * <p>调用前提 = S4 消费已结束、{@link SettlementDayContext#actualProductionPlan} 已由 {@link
   * SettlementDayActualProductionPipeline} 产出。锁定雇佣槽不进队列（保留实际计划刚写好的承诺行），非雇佣槽只在 S4 实际计划内排队。
   */
  static void planActualLabor(SettlementDayContext ctx) {
    if (ctx.actualProductionPlan == null) {
      throw new IllegalStateException("M6 实际劳动编排缺少 ActualProductionPlan: day=" + ctx.day);
    }
    java.util.Set<LaborQueueWritebackBook.UnitRole> allowedSlots = new LinkedHashSet<>();
    for (PositionPlan.UnitPlan unitPlan : ctx.actualProductionPlan.byUnit().values()) {
      for (PositionPlan position : unitPlan.positions()) {
        allowedSlots.add(new LaborQueueWritebackBook.UnitRole(position.unit(), position.role()));
      }
    }
    if (ctx.dayView.modes().isEmpty()) {
      reallocateLaborPartitioned(
          ctx.session, ctx.parallelism, ctx.settlementIndex, ctx.enteredToday);
    } else {
      LaborQueueSettlement.apply(
          ctx.session,
          ctx.settlementIndex,
          ctx.day,
          ctx.composition,
          ctx.enteredToday,
          ctx.accounts,
          ctx.arbitrageCollector,
          ctx.survivalSocialFacts,
          allowedSlots,
          ctx.actualProductionPlan.locksReadOnly());
    }
    ctx.settlementIndex =
        ctx.settlementIndex.withLabor(ctx.units, ctx.householdEconomies, ctx.laborCommitments);
  }

  /**
   * ★★★ <b>P3c3：M7 的实际投入现扣</b>（只扣投入；劳动已在 M6 排好，不在这里再排）。
   *
   * <p>保留旧 {@link #drawInputsAndPlanLabor(SettlementDayContext)} 供未接线路径使用；主路径拆成 M6 ({@link
   * #planActualLabor}) + M7（本方法）。
   */
  static void drawActualInputs(SettlementDayContext ctx) {
    // ★★ RF2：M7 只对实际入选、且 M6 后仍有实际劳动承诺的 unit 现扣周期投入。
    //   未入选/无劳动/今天不生产的 unit 不预扣整周期种子（`producingUnits == null` 只在没有 M6 计划的
    //   legacy 路径出现，那里保持旧行为逐值不变）。
    Set<ProductionUnitId> producingUnits = actualProducingUnitsOf(ctx);
    drawCycleInputsPartitioned(
        ctx.session,
        ctx.accounts,
        ctx.consumedGoods,
        ctx.householdOfActor,
        ctx.ledger,
        ctx.day,
        ctx.parallelism,
        ctx.settlementIndex,
        producingUnits);
  }

  /**
   * ★★★ <b>RC8c：S4 生成每户每 tick 唯一 {@link DailyDemandBill}</b>。
   *
   * <p>调用位置 = M7 实际投入现扣之后（{@code drawActualInputs}）—— 此时"本 tick 实际消费"与"本 tick 实际投入"都已落账，账单才能把
   * {@code providedFromStock} 记成真实扣减量。构建一次即可；同一 {@link SettlementDayContext} 内重复调用是幂等的（不重复累计）。
   *
   * <p>行源：
   *
   * <ul>
   *   <li>{@code BASE_SUBSISTENCE}：{@link HouseholdEconomy#naturalNeeds()}（Social
   *       当刻注入的逐户日需求，不重算年龄/性别系数）； {@code providedFromStock} = 当日实际吃掉/穿掉的量（消费步写下的 {@code unmetToday}
   *       反推，只对 GRAIN/CLOTH 生效）；
   *   <li>{@code PRODUCTION_DAILY_EXTRA}：产业定义 {@code dailyInputPerUnit}（空表 ⇒ 不写行） × 实际 probe 的
   *       baseScale； 本 tick 没有"每日额外项"的实扣通道 ⇒ {@code providedFromStock} 为 0（有定义才写行）；
   *   <li>{@code PRODUCTION_INPUT}：实际生产位置 {@code probe.inputRequired()}（本周期一次性投入）× 实际生产单元；只写<b>本
   *       tick 真正进入周期投入门</b>的 unit（{@code progressDays == 0} 或周期中首次补扣），{@code providedFromStock} =
   *       本 tick 实扣 delta（{@code EconomySession#drawnProductionInputsOf}）。
   * </ul>
   *
   * <p>★ 生产投入的 {@code quantityMilli} 是"本周期毛需求"，{@code providedFromStockMilli} 是已从库存扣掉的量；S7 的 {@code
   * marketPurchaseNeed} 会先扣已提供量再扣当前库存（见 {@link DailyDemandBill#marketGapFor}）⇒ S4 已提供的部分不会再次挂买。
   */
  static void buildDailyDemandBill(SettlementDayContext ctx) {
    if (!ctx.dailyDemandBills.isEmpty()) {
      return; // 同一 tick 只建一次（每户每 tick 唯一副本）
    }
    // ── 1. BASE_SUBSISTENCE：Social 当刻 naturalNeeds + 当日实际消费提供量 ─────────────────────────
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry :
        ctx.householdEconomies.entrySet()) {
      HouseholdId household = householdEntry.getKey();
      HouseholdEconomy row = householdEntry.getValue();
      if (row == null) {
        continue;
      }
      DailyDemandBill bill = ctx.dailyDemandBill(household); // 每户每 tick 恰好一张（即使无需求）
      for (Map.Entry<CommodityId, Long> need : row.naturalNeeds().entrySet()) {
        long quantity = need.getValue();
        if (quantity <= 0L) {
          continue;
        }
        bill.addQuantity(need.getKey(), DailyDemandBill.DemandSource.BASE_SUBSISTENCE, quantity);
        long provided = consumptionProvidedMilli(ctx, household, need.getKey(), quantity);
        if (provided > 0L) {
          bill.addProvidedFromStock(
              need.getKey(), DailyDemandBill.DemandSource.BASE_SUBSISTENCE, provided);
        }
      }
    }
    // ── 2. 生产相关行：按实际入选 unit（只读 M6 事实）写 PRODUCTION_DAILY_EXTRA / PRODUCTION_INPUT ──
    Set<ProductionUnitId> producing = actualProducingUnitsOf(ctx);
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts =
        firstFactPerUnit(ctx.actualPositionFacts);
    List<ProductionUnitId> unitIds = new ArrayList<>(ctx.units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      if (producing != null && !producing.contains(unitId)) {
        continue; // 没有实际劳动/未入选 ⇒ 不写该 unit 的投入需求
      }
      ProductionProcess unit = ctx.units.get(unitId);
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView industry = ctx.industries.get(unit.industry());
      if (industry == null) {
        continue;
      }
      ProductionPlanBridge.PositionFacts fact = unitFacts.get(unitId);
      HouseholdId responsible =
          fact == null
              ? ctx.settlementIndex.economicHouseholdOf(unitId).orElse(null)
              : fact.organizer();
      if (responsible == null || !ctx.householdEconomies.containsKey(responsible)) {
        continue; // 聚合主体不在家户账里：本批不伪造家户账单行
      }
      DailyDemandBill bill = ctx.dailyDemandBill(responsible);
      long baseScale =
          fact == null ? productionScaleOf(ctx, unit, industry) : fact.probe().baseScale();
      if (baseScale > 0L) {
        addDailyExtraLines(bill, industry, baseScale);
      }
      if (!industry.stages().isEmpty()) {
        // ★★ U4b2b：staged unit 每个 tick 都有 PRODUCTION_INPUT 行；数量来自当前阶段的
        //   start+tick（阶段第一天）/ tick（其余日），不把 aggregate cycle 或 legacy dailyInput 当第二套真相。
        CurrentStageInput currentStageInput = industry.currentStageInput(unit);
        Map<CommodityId, Long> required = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> entry : currentStageInput.inputPerUnit().entrySet()) {
          if (entry.getValue() <= 0L || baseScale <= 0L) {
            continue;
          }
          required.put(entry.getKey(), Math.multiplyExact(entry.getValue(), baseScale));
        }
        addProductionInputLines(bill, required, ctx.session.drawnProductionInputsOf(unitId));
      } else if (productionInputDue(unit, producing)) {
        Map<CommodityId, Long> required =
            fact == null ? fallbackInputRequired(ctx, unit, industry) : fact.inputRequired();
        addProductionInputLines(bill, required, ctx.session.drawnProductionInputsOf(unitId));
      }
    }
    logDailyDemandBill(ctx, producing);
  }

  /** BASE_SUBSISTENCE 的已提供量：消费步只吃 GRAIN/CLOTH，其他自然需求当前没有消费通道 ⇒ 0。 */
  private static long consumptionProvidedMilli(
      SettlementDayContext ctx, HouseholdId household, CommodityId commodity, long dailyNeed) {
    HouseholdEconomy row = ctx.householdEconomies.get(household);
    if (row == null || row.population() <= 0L) {
      return 0L; // 消费步对 0 人口行不读账、不消费 ⇒ 没有"已提供"
    }
    if (!EconomySettlement.GRAIN.equals(commodity) && !EconomySettlement.CLOTH.equals(commodity)) {
      return 0L;
    }
    long unmet = ctx.unmetToday.getOrDefault(household, Map.of()).getOrDefault(commodity, 0L);
    return Math.max(0L, dailyNeed - unmet);
  }

  /** 每个 unit 取第一条位置事实（同一 unit 的全部参与户共享同一份 probe / 投入需求）。 */
  private static Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> firstFactPerUnit(
      List<ProductionPlanBridge.PositionFacts> facts) {
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> byUnit = new LinkedHashMap<>();
    for (ProductionPlanBridge.PositionFacts fact : facts) {
      byUnit.putIfAbsent(fact.position().unit(), fact);
    }
    return byUnit;
  }

  /** ★ 把一组逐商品“本 tick 毛需求”写进账单，并把 S4 实扣 delta 记成 providedFromStock。 */
  private static void addProductionInputLines(
      DailyDemandBill bill, Map<CommodityId, Long> required, Map<CommodityId, Long> drawn) {
    for (Map.Entry<CommodityId, Long> input : required.entrySet()) {
      long quantity = input.getValue();
      if (quantity <= 0L) {
        continue;
      }
      bill.addQuantity(input.getKey(), DailyDemandBill.DemandSource.PRODUCTION_INPUT, quantity);
      long provided = Math.min(quantity, drawn.getOrDefault(input.getKey(), 0L));
      if (provided > 0L) {
        bill.addProvidedFromStock(
            input.getKey(), DailyDemandBill.DemandSource.PRODUCTION_INPUT, provided);
      }
    }
  }

  /** 生产投入门与 {@code ProductionInputSettlement.surveyInputDemands} 同口径：周期第一天，或周期中首次补扣。 */
  private static boolean productionInputDue(
      ProductionProcess unit, Set<ProductionUnitId> producing) {
    if (unit.progressDays() == 0L) {
      return true;
    }
    return unit.progressDays() > 0L
        && unit.cycleInputUsedMilli().isEmpty()
        && producing != null
        && producing.contains(unit.id());
  }

  /** 产业 {@code dailyInputPerUnit} 有定义才写 {@code PRODUCTION_DAILY_EXTRA} 行（当前 32hex 通常为空）。 */
  private static void addDailyExtraLines(
      DailyDemandBill bill, IndustryDefinitionView industry, long baseScale) {
    for (Map<CommodityId, Long> line : industry.dailyInputPerUnit().values()) {
      for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
        long perScale = entry.getValue();
        if (perScale <= 0L) {
          continue;
        }
        bill.addQuantity(
            entry.getKey(),
            DailyDemandBill.DemandSource.PRODUCTION_DAILY_EXTRA,
            Math.multiplyExact(perScale, baseScale));
      }
    }
  }

  /** 没有 M6 probe 事实时的兜底：与投入现扣同源的"计划规模 × 配方单位投入"。 */
  private static Map<CommodityId, Long> fallbackInputRequired(
      SettlementDayContext ctx, ProductionProcess unit, IndustryDefinitionView industry) {
    long scale = productionScaleOf(ctx, unit, industry);
    if (scale <= 0L) {
      return Map.of();
    }
    Map<CommodityId, Long> required = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() > 0L) {
        required.put(entry.getKey(), Math.multiplyExact(entry.getValue(), scale));
      }
    }
    return required;
  }

  private static long productionScaleOf(
      SettlementDayContext ctx, ProductionProcess unit, IndustryDefinitionView industry) {
    return ProductionProcessBook.plannedCapacityScaleOf(
        unit, industry, ctx.settlementIndex, ctx.operatorConditions.get(unit.id()));
  }

  /** RC8c 一条 INFO 汇总（只读；账单行数/提供量/缺口可 grep）。 */
  private static void logDailyDemandBill(
      SettlementDayContext ctx, Set<ProductionUnitId> producingUnits) {
    if (!EconomyLog.settlement().isInfoEnabled()) {
      return;
    }
    long lines = 0L;
    long baseLines = 0L;
    long extraLines = 0L;
    long inputLines = 0L;
    long provided = 0L;
    for (DailyDemandBill bill : ctx.dailyDemandBills.values()) {
      for (DailyDemandBill.Line line : bill.lines()) {
        lines++;
        provided = Math.addExact(provided, line.providedFromStockMilli());
        switch (line.source()) {
          case BASE_SUBSISTENCE -> baseLines++;
          case PRODUCTION_DAILY_EXTRA -> extraLines++;
          case PRODUCTION_INPUT -> inputLines++;
          case OTHER -> {
            // 本批不产生 OTHER 行；保留分支让将来来源不静默漏计。
          }
        }
      }
    }
    EventLog.channel(EconomyLog.settlement())
        .info(
            LogEvent.of(
                "DAILY_DEMAND_BILL_BUILT",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                ctx.day,
                "households",
                ctx.dailyDemandBills.size(),
                "lines",
                lines,
                "baseLines",
                baseLines,
                "extraLines",
                extraLines,
                "inputLines",
                inputLines,
                "providedFromStockMilli",
                provided,
                "producingUnits",
                producingUnits == null ? -1L : producingUnits.size(),
                "reason",
                "rc8c-one-bill-per-household-provided-from-s4-actual-draws"));
  }

  /**
   * ★★ <b>RF2：M7 的 unit 资格集</b> = M6 实际编排入选的 unit ∩ 本 tick 仍有正 PRODUCTION 劳动承诺的 unit。
   *
   * <p>只看"unit 存在"就扣整周期种子会把未生产/无劳动 unit 的户粮清空；这里把扣料资格与真实劳动绑定。 没有 M6 计划（legacy 路径）⇒ 返回 {@code
   * null}（调用方不限制，逐值不变）。
   */
  private static Set<ProductionUnitId> actualProducingUnitsOf(SettlementDayContext ctx) {
    if (ctx.actualProductionPlan == null) {
      return null;
    }
    Set<ProductionUnitId> withLabor = new LinkedHashSet<>();
    for (HouseholdLaborCommitment commitment : ctx.laborCommitments.values()) {
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION || commitment.laborMilli() <= 0L) {
        continue;
      }
      withLabor.add(new ProductionUnitId(commitment.activity()));
    }
    Set<ProductionUnitId> selected =
        new LinkedHashSet<>(ctx.actualProductionPlan.byUnit().keySet());
    int planned = selected.size();
    // ★ RF2：M6 未再产出的**本周期在产 unit**（progressDays > 0）只要仍有实际劳动承诺，就继续扣料/生产；
    //   新周期第一天（progressDays == 0）必须真的被 M6 入选。这样既不会预扣未入选新 unit，也不会让
    //   "已经种下、还在周期中"的 farm 因 M6 当日重排失败而断供。
    selected.retainAll(withLabor);
    for (ProductionUnitId unitId : withLabor) {
      if (selected.contains(unitId)) {
        continue;
      }
      ProductionProcess continuing = ctx.units.get(unitId);
      if (continuing != null && continuing.progressDays() > 0L) {
        selected.add(unitId);
      }
    }
    if (EconomyLog.settlement().isInfoEnabled()) {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "PRODUCTION_INPUT_SELECTION",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  ctx.day,
                  "plannedUnits",
                  planned,
                  "producingUnits",
                  selected.size(),
                  "reason",
                  "m7-draw-only-actual-selected-with-labor"));
    }
    return selected;
  }

  /**
   * ★★★ P3c2：**消费**（各自吃自己的库存；缺口先记下不借）。不得在这里再扣投入或再排劳动。
   *
   * <p>★ 原 {@code INPUTS_CONSUMPTION} 读数**不**搬到这里：它读的 {@code consumedGoods} 同时含"消费"与"现扣投入" 两笔（见
   * {@code recordInputDraw} 的 {@code addGoods(consumedGoods, ...)}）⇒ 它是两阶段合并后的读数， 搬进消费方法会让 {@code
   * !plantingDrawsFirst} 分支少读一份投入实扣。它保留在 {@link #prepareConsumption} 尾部原位置。
   */
  static void settleConsumption(SettlementDayContext ctx) {
    // ── 1~2. 消费（各自吃自己的库存；缺口**先记下不借**）──────────────────────────────
    consumeOwnStockPartitioned(
        ctx.householdEconomies,
        ctx.accounts,
        ctx.consumedGoods,
        ctx.unmetToday,
        ctx.deficitToday,
        ctx.parallelism);
  }

  /**
   * ★★★ <b>P3c3 legacy 回退：`prepareProductionBase` 已跑过之后的旧消费/劳动次序</b>。
   *
   * <p>无 production roster 的世界（旧生产形态）不走 M3/M6：M3 观察不到任何可编排位置时，日序回退到旧的 `drawInputsAndPlanLabor +
   * settleConsumption`（按 {@code plantingDrawsFirst} 保持原顺序），随后 `driveProduction`。 本方法**不重跑** {@link
   * #prepareProductionBase}、不生成任何 P3 瞬态计划。
   */
  static void legacyConsumeAfterBase(SettlementDayContext ctx) {
    if (ctx.plantingDrawsFirst) {
      drawInputsAndPlanLabor(ctx);
      settleConsumption(ctx);
    } else {
      settleConsumption(ctx);
      drawInputsAndPlanLabor(ctx);
    }
  }

  /**
   * 旧日序 wrapper（行为逐值不变）：P3c2 只把原方法机械拆成阶段，{@link #prepareProductionBase} 先跑， 然后按 {@code
   * plantingDrawsFirst} 保留原来的两条调用顺序。
   *
   * <p>★ {@code INPUTS_CONSUMPTION} 读数留在 wrapper 尾部而不是搬进 {@link #settleConsumption}： 它读的 {@code
   * consumedGoods} 同时含"消费"与"现扣投入"两笔（见 {@code recordInputDraw} 的 {@code addGoods(consumedGoods,
   * ...)}）⇒ 它是**两阶段合并后的读数**，不是消费段尾部读数； 搬到消费方法里会让 {@code !plantingDrawsFirst} 分支少读一份投入实扣。
   */
  static void prepareConsumption(SettlementDayContext ctx) {
    prepareProductionBase(ctx);
    legacyConsumeAfterBase(ctx);

    if (TRACE.isDebugEnabled()) {
      long deficitHouseholds = 0L;
      long deficitGrain = 0L;
      for (long deficit : ctx.deficitToday.values()) {
        if (deficit > 0L) {
          deficitHouseholds++;
          deficitGrain += deficit;
        }
      }
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "INPUTS_CONSUMPTION",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "consumedQuantities",
                  traceTotalGoods(ctx.consumedGoods),
                  "deficitHouseholds",
                  deficitHouseholds,
                  "deficitGrainMilli",
                  deficitGrain));
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void driveProduction(SettlementDayContext ctx) {
    // ★★★ D-032 C6：在本日的生产驱动入口注入唯一 sink。sink 冻结“本日 = 哪个 marketRoundId”，
    //   并在每次 accept 时用 operator 户当刻 homeHex/市场区 fail-closed 地累加批次。
    ctx.transportCapacitySink =
        TransportCapacityBatchBook.sink(ctx.session.sheet(), ctx.dayView, ctx.day);
    // ★★★ B1（D-032 §4.3 数据流第 5 段）：**本生产日的推进改经 production 的两步契约** ——
    //   `probe`（无副作用）→ economy 排序与统一分配 → `executeDay`（按最终投入，一实例一次 = 一个生产日）→ 回执。
    //   ★ 位置是决定性的：必须在**劳动分配与现扣投入都落地之后**（那是"最终投入"），且在主循环读实例状态**之前**。
    //   ★ economy 从此**不写实例状态**：进度/周期台账/效率全部由 production 的候选态承载，
    //     主循环只读回执（见下面 3~4 段的 receipt 分支）。
    //   ★★ B2b（§13.11.4 第 4 条）：建档类写者（进入/组织/变迁/迁移）已**直调 production 契约口**
    //     （`createInstance` / `removeInstance` / `reoperateInstance`）⇒ 本处不再有"推回工作副本"的 flush 桥
    //     （`unitsWorkCopy` / `flushUnitWorkCopy` 已整体删除），实例当刻就在权威候选态里。
    //   ★★★ B8（缺陷 DEF-T2b-2）：**本日实扣读数（`drawnProductionInputs`）必须在它被读走之后才清**。
    //     改前这里就调 `session.clearDrawnProductionInputs()` —— 那一步把本日现扣阶段（上面
    //     `drawCycleInputsPartitioned`）刚落进去的实扣量**当场抹掉**，于是 `driveProductionDay` 读到的
    //     `finalInputsMilli` 恒为空 ⇒ 投入那一路规模恒 0 ⇒ 该生产日 `progressDays=0`、`cycleInputUsedMilli={}`
    //     ⇒ **毛产恒 0**（"声明了周期投入的生产"整类玩法不可用）。清零点已在**本段之前**（现扣之前的
    //     `session.clearDrawnProductionInputs()`，见那里），故这里不再清 —— 清在这里就是把本日读数删掉。
    ctx.productionDay =
        driveProductionDay(
            ctx.session,
            ctx.day,
            ctx.settlementIndex,
            ctx.laborCommitments,
            ctx.operatorConditions,
            // ★★★ P3b：M7 的资产占用只用排除了“本轮新土地”的 allocation（M2 抵债取得 / P3c 后 M4 买入）。
            ctx.landAcquiredThisRound(),
            // ★★★ D-032 C9：运输马匹等 durable 商品资本只读 operator 户当刻 HouseholdInventory 工作副本。
            ctx.householdGoods);
    if (EconomyLog.settlement().isInfoEnabled()) {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "D032_P3_EXECUTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  ctx.day,
                  "executed",
                  ctx.productionDay.receipts().size(),
                  "rejected",
                  ctx.productionDay.rejectionCount(),
                  "closed",
                  ctx.productionDay.closedUnitScales().size(),
                  "reason",
                  "m7-actual-inputs-drawn-before-execution"));
    }
    if (!ctx.productionDay.isEmpty() && TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "PRODUCTION_DAY_DRIVEN",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  ctx.day,
                  "steps",
                  ctx.productionDay.receipts().size() + ctx.productionDay.rejectionCount(),
                  "executed",
                  ctx.productionDay.receipts().size(),
                  "rejected",
                  ctx.productionDay.rejectionCount(),
                  "closed",
                  ctx.productionDay.closedUnitScales().size()));
    }
    for (Map.Entry<ProductionUnitId, String> rejection :
        ctx.productionDay.rejections().entrySet()) {
      // ★ I15：业务拒绝 = INFO 具名理由（世界照常推进；释放掉的资源由 economy 后续重新分配）。
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "PRODUCTION_DAY_REJECTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  ctx.day,
                  "unit",
                  rejection.getKey().value(),
                  "reason",
                  rejection.getValue()));
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void closeAndHarvest(SettlementDayContext ctx) {
    // ── 3~4. 进度 + 劳动投入；周期末追加收获/分配 ────────────────────────────────────
    ctx.anyCycleClosed = false;
    ctx.newCycleUnits = new LinkedHashSet<>();
    // ★★★ A5（2026-10-10）+ A7：本日关账、且**产出运输服务**的 unit（`trade@hex`）—— 它们是"本日到周期边界"的
    //   触发条件（A7 起不再是清扫范围）：清扫扫的是**任何持有 haul 的家户**，在本日收获**之前**作废其陈货
    //   （用户裁定「不可储存转卖」；见 expireUnservedHaulService 的类注）。
    ctx.haulExpiryUnits = new ArrayList<>();
    // ★★★ A5：当日运输服务的三个对账量（产出 / 交付 / 作废，毫服务）—— 交付量由市场轮交出（MarketOutcome.haulService()），
    //   三者一起进当日对账 INFO（见 4 段末的 HAUL_SERVICE_SETTLED）。
    ctx.haulServiceProducedMilli = 0L;
    ctx.haulServiceExpiredMilli = 0L;
    // ★★ **H0：周期刚翻篇的 unit**（progressDays 归 0，含创世）—— 先算好，因为"家户的流水何时翻篇"要读它。
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : ctx.units.entrySet()) {
      if (entry.getValue().progressDays() == 0L) {
        ctx.newCycleUnits.add(entry.getKey());
      }
    }
    // ★★ **H0：家户的流水何时整行重记** —— 它供给的**任一**产业翻篇的那一天（§八.5 的清零点）。
    //   ★ 为什么"任一"：家户自己没有周期字段（K2 之后周期仍住在 IndustryDefinitionView 上），而它的所得来自它供给的那些产业的收获
    //     ⇒ 只要有一个产业翻篇，这本账的"本期"就跟着翻。★ 代价如实记：产业周期**不同步**时这是近似（真档三个产业都是
    //     120 天且同时创世 ⇒ 与改前**逐值相同**；改前是"该行所属那一个产业"翻篇）。
    //   ★ 没有配额的产业的家户（供给集合为空）**从不翻篇**：宁可让它逐字累计（读得出来），也不每天清零把发生额抹掉。
    //   ★★ **兜底**：一条配额都没有的家户（手工搭的状态、或全部产业都不给它配额）退回**它住的那一格的产业**
    //     （与 {@link #cycleDaysByHousehold} 同一条兜底）—— 否则它的流水**永不翻篇**，利息/出生死亡会一直累加
    //     （实测：`EconomyDebtTest` 的 interestDue 读成两个周期之和）。
    ctx.hexToIndustries = ctx.settlementIndex.industriesByHex();
    ctx.newCycleHouseholds = new LinkedHashSet<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEntry :
        ctx.householdEconomies.entrySet()) {
      HouseholdId key = householdEntry.getKey();
      Set<ProductionUnitId> supplied = ctx.unitsOfHousehold.getOrDefault(key, Set.of());
      if (supplied.isEmpty()) {
        // ★ 兜底：没有配额的户按它住的那一格的产业找 unit（与 cycleDaysByHousehold 同一条兜底）。
        HouseholdEconomy cycleHouseholdEconomy = householdEntry.getValue();
        if (cycleHouseholdEconomy != null) {
          // ★ R4-B.3a-perf：格 → unit 由入口索引一次给出（旧实现逐无配额家户全量扫 8,940 个 unit）。
          supplied =
              new LinkedHashSet<>(
                  ctx.settlementIndex.unitsInHex(
                      IndustryHexKeys.hexKey(
                          cycleHouseholdEconomy.view().hex().q(),
                          cycleHouseholdEconomy.view().hex().r())));
        }
      }
      for (ProductionUnitId unitId : supplied) {
        if (ctx.newCycleUnits.contains(unitId)) {
          ctx.newCycleHouseholds.add(key);
          break;
        }
      }
    }
    // ★★ H5：本日关账的产业（它们的缺口读数要等救济通道走完才报 —— 见下面的 4d）。
    ctx.closed = new ArrayList<>();
    // ★★ P10.2：关账 unit 的周期劳动/投入快照（在下面把 unit 周期状态清零**之前**抓；⑦ 利润汇总用）。
    ctx.closedFacts = new ArrayList<>();
    // ★★ R2：关账产业先收集成工作单元，进度/周期状态按原序落回 industries；收获/关系分账在收集完后
    //   按 hex 并行执行（同一 hex 内的多个产业共用家户账/关系账 ⇒ 同分区串行，见 runHarvestStage）。
    ctx.harvestWorks = new ArrayList<>();
    ctx.harvestOrder = 0;
    // ★★★ W5（2026-10-11）：本日关账的 unit → 关账处求好的规模（scale）。铸币产出走**同一个 scale**
    //   （{@link MintSettlement#settleCycleClose}）—— 这是"投入真实消耗、产出 = 货币"两半不漂开的落点：
    //   投入侧由既有的周期投入管线（drawCycleInputs + ProductionEfficiencyBook.evaluateHarvest）扣，
    //   产出侧读的正是同一份求好的 scale。★ 没关账的日子这张表是空的 ⇒ 铸币一个字节都不动。
    ctx.closedUnitScales = new LinkedHashMap<>();
    // ★★ Z2（§6.1）：当日注入的修正集（缺省 1000‰ = 中性；读取的是本 tick 的只读视图）与效率表工作副本。
    //   效率表是结算工作副本（Z1 已在 EconomyStateBuilder 备好）；缺行 = 全 0 余数 + 本周期全 1000‰。
    // ★★ Z2（§6.2 ⑥）：产品产出数量 GM 覆盖表（只读；缺产业 = 该产业全部回落配方默认值）。
    // ★★★ B2b：唯一权威在 production（economy 侧组件已删）⇒ 从本会话的运行时常量投影取；
    //   ★ 同源：production 的 `executeDay` 用它算毛产（`effectiveOutputPerUnit`），economy 的收获腿
    //     读的是**同一份**（I6：预计与实际共用同一份覆盖参数；economy 不再有第二份表）。
    ctx.outputQuantityOverrides = ctx.session.productionRuntime().outputQuantityOverrides();
    // ★★★ B1（D-032）：实例的进度 / 周期台账 / 效率现在全部由 production 的候选态承载（见上面执行段的
    //   `executeDay`）。本循环只做三件事：
    //   ① 从**回执**读本实例的推进结果（进度、是否关账、关账规模、实际投入）；
    //   ② 把"本日关账"翻译成 economy 侧的下游工单（收获/铸币/缺口/利润快照）；
    //   ③ 不再写任何实例状态（`units.put(...)` 已整段删除）。
    //   ★ 回执缺失 = 本实例今天没被推进（业务拒绝或本就不在步表里）⇒ 跳过它的关账工单；
    //     这一支**不是**静默降级：拒绝已在上面逐条 INFO 具名。
    for (ProductionUnitId id : new ArrayList<>(ctx.units.keySet())) {
      ProductionProcess unit = ctx.units.get(id);
      IndustryDefinitionView industry = ctx.industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      java.util.Optional<io.mosire.simos.production.contract.ProductionReceipt> receiptOrEmpty =
          ctx.productionDay.receiptOf(id);
      if (receiptOrEmpty.isEmpty()) {
        continue; // 本实例今天没被推进（业务拒绝已具名 INFO）⇒ 无进度、无关账
      }
      io.mosire.simos.production.contract.ProductionReceipt receipt = receiptOrEmpty.get();
      // ★★ **H0：这个 unit 的家户行 = 由劳动配额表推**（{@code SettlementIndex.householdsOf}：配额 activity == unit
      // id）。
      //   ★ R4-B.3a-perf：入口索引已按 unit 聚好（去重/排序口径与 householdKeysOf 逐值相同）。
      List<HouseholdId> keys = ctx.settlementIndex.householdsOf(id);
      long cycledLabor =
          unit.cycleContributions().values().stream()
              .mapToLong(io.mosire.simos.production.contract.CycleContribution::laborMilli)
              .sum();
      // ★★ production 已经用**同一份**实际投入累计好了周期投入台账 ⇒ 这里读它，不再自己 merge 一遍
      //   （旧口径在现扣阶段 `units.put(withCycleState(..., accumulated))`，那是 economy 写实例状态的最后一处）。
      Map<CommodityId, Long> nextInputUsed = receipt.consumedInputs();
      // ★ v2 spec §八.3：`progressDays ∈ [0, cycleDays]` 是**闭区间**：>= 才把合法状态收获掉。
      //   ★ B1 起"关不关账"是 production 的判定（回执 `cycleClosed`）——economy 不再自己拿
      //     `progressDays + 1 >= cycleDays` 重算一遍（那是第二份口径）。
      if (receipt.cycleClosed()) {
        // ── 周期末：产出 → 产权条目 + 关系规则入账（★ 缺口读数在**所有救济通道之后**，见下面的 4d）──
        // ★★★ B1：规模由 production 的唯一入口（`ProductionEfficiency`）给出（回执 `scale` = finalScale）；
        //   economy 侧的旧效率书（`ProductionEfficiencyBook.evaluateHarvest`）**已整条退役**
        //   —— 它按 `[0,2000]‰` 的旧上限与旧加成位置算，留着就是第二份公式。
        long closedScale = receipt.scale();
        // ★★★ D-032 B3（I9）：收获阶段的两个"实际事实"一律取自**回执**（唯一交接面）：
        //   ① 本周期逐家户实际劳动 = `receipt.cycleContributions()`（逐 tick 累计、含中途换出工户、不拿关账日反推）；
        //   ② 本周期实际投入 = `receipt.consumedInputs()`（production 的实际扣量）。
        Map<HouseholdId, Long> cycleLaborByHousehold = new LinkedHashMap<>();
        Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
            cycleLaborByParticipant = new LinkedHashMap<>();
        for (io.mosire.simos.production.contract.CycleContribution contribution :
            receipt.cycleContributions().values()) {
          if (contribution.laborMilli() > 0L) {
            cycleLaborByHousehold.merge(
                contribution.household(), contribution.laborMilli(), Math::addExact);
            cycleLaborByParticipant.put(contribution.participantKey(), contribution.laborMilli());
          }
        }
        ctx.harvestWorks.add(
            new HarvestWork(
                unit,
                industry,
                closedScale,
                ctx.outputQuantityOverrides.getOrDefault(industry.id(), Map.of()),
                hexOfIndustry(unit.industry()),
                ctx.harvestOrder++,
                cycleLaborByHousehold,
                receipt.consumedInputs(),
                cycleLaborByParticipant,
                ctx.session.sheet().productionRostersOrBase().get(id)));
        // ★★★ W5：把这一份 scale 记下来给铸币用（只有真的铸币的产业会在 MintSettlement 里被读；
        //   记全部关账 unit 是刻意的 —— 判据"这个 unit 铸不铸币"只在一个地方回答：IndustryDefinitionView.moneyOutputPerUnit
        // 是否为空）。
        if (!industry.moneyOutputPerUnit().isEmpty()) {
          ctx.closedUnitScales.put(id, closedScale);
        }
        // ★★ **H5：关账的 unit 先记下来 —— 4d 的报缺口等所有救济通道走完再算**（W3 起不再判死）。
        ctx.closed.add(
            new ClosedUnit(
                id,
                industry.id(),
                keys,
                inputShortfallOf(
                    unit, industry, ctx.settlementIndex, ctx.operatorConditions.get(id))));
        // ★★ P10.2：周期劳动/投入在 unit.cycleState 清零前抓（清零现在发生在 production 的 `execute` 里，
        //   故这里读的是**回执**带回来的本周期事实）。
        ctx.closedFacts.add(
            new EnterpriseProfitBook.CloseFact(
                id,
                industry.id(),
                unit.operator(),
                hexOfIndustry(unit.industry()),
                unit.modeKey(),
                cycledLabor,
                nextInputUsed));
        ctx.anyCycleClosed = true;
        // ★★★ A5（2026-10-10）：本日关账的产业**产出运输服务**（= 它是跑商 unit）⇒ 记下来：
        //   它的账户主体手上"上一周期没卖出的"服务货在本日收获入账**之前**作废（不可储存转卖）。
        //   ★ 判据读配方（产出表含 haul），不另造开关；别的产业一个字节都不动。
        if (industry.recipe().outputPerUnit().containsKey(HaulService.HAUL_COMMODITY)) {
          ctx.haulExpiryUnits.add(unit);
        }
      }
    }
    // ★★ R2：收获/关系分账按 hex 并行（唯一写口仍是 applyTransfer；转移在分区累加器里铸号，协调器按分区序吸收）。
    // ★★ P2-A §13.3：收获阶段的两份只读派生索引 —— unit → ProductionEnterprise（组织者家户解析）与
    //   旧 CohortKey 视图 → 家户（旧 ToCohort 规则的唯一合法读法）。
    ctx.enterpriseByProcess = enterprisesByProcess(ctx.session.sheet().productionOrganizations());
    ctx.viewIndex = viewToHousehold(ctx.householdEconomies);
    // ── 3a'. ★★★ A5（2026-10-10）+ A7：周期边界作废上一周期未卖出的运输服务（用户裁定「不可储存转卖」）────
    //   ★ 位置是决定性的：必须在**本周期新产出被记入货物账之前**（下面 harvestPartitioned）—— 否则刚产出的那一份
    //     会被当成"上一周期的存货"一并作废（那是把新货当陈货，方向上正好反了）。
    //   ★★ A7 扩域：作用域 = **任何持有 haul 的家户**（不再是"关账 unit 的账户主体家户"）—— A6 实测的逃逸口正是
    //     "服务货经实物还债转移到已退出商家名册的持货户"（B 世界 day 240 的 5,249 毫，跨 day 360/480 两次边界未作废）。
    //     别的商品一个字节都不动 ⇒ "只影响 haul"仍然结构性成立（读写的商品键恒为 HAUL_COMMODITY）。
    //   ★ 落点 = 既有非换手损耗唯一写口 consumeForLoss（账户减 + 损耗账加在同一句话里做完，
    //     账 = HaulService.SERVICE_EXPIRED_ACCOUNT 具名项）⇒ Σ余额 + losses 守恒（I-H2，不静默消失）。
    ctx.haulServiceExpiredMilli =
        expireUnservedHaulService(ctx.day, ctx.haulExpiryUnits, ctx.accounts, ctx.ledger);
    harvestPartitioned(
        ctx.harvestWorks,
        ctx.householdEconomies,
        ctx.settlementIndex,
        ctx.relations,
        ctx.enterpriseByProcess,
        ctx.viewIndex,
        ctx.householdOfActor,
        ctx.accounts,
        ctx.income,
        ctx.ledger,
        ctx.issuanceJournal,
        ctx.day,
        ctx.parallelism,
        ctx.assetShares,
        ctx.transportCapacitySink);
    // ── ★★★ W5（2026-10-11）：铸币生产方式的收获 ────────────────────────────────────────────────────
    //   ★ 位置 = **产业关账之后**（`harvestPartitioned` 已把本轮的净产计进各主体账）：
    //     铸币读的是关账处求好的 scale（与粮/布同一次 evaluateHarvest 的产物），因此"投入真实消耗"与
    //     "产出 = 货币"是同一次关账的两面，不可能漂开。
    //   ★ 它写三件事实（三者同一次调用里做完，见 MintSettlement.credit）：
    //     ① 国库家户账户余额（货币产出走账户余额，与商品产出走货物账同一条"产出计入组织者"的口径）
    //     ② MoneyIssuanceRecord（kind = MINT_PRODUCTION，id 由 (政府, 日, 币种) 确定性派生）
    //     ③ 同一本 debtContracts 的一条合同（货币 = 发行政府的债务）
    //   ★ "本轮能新投入多少 ≤ 上一轮印了多少"的闸门也在那里（从 MoneyIssuanceRecord 现算，判据 ③）。
    //   ★ 没关账 / 没铸币产业 / 旋钮 0 ⇒ 空表 ⇒ 一个字节都不动（判据 ②：规模 0 ⇒ 新钱 0 毫）。
    if (!ctx.closedUnitScales.isEmpty()) {
      MintSettlement.settleCycleClose(
          ctx.dayView, ctx.session, ctx.accounts, ctx.day, ctx.currentCycle, ctx.closedUnitScales);
    }
    if (!ctx.harvestWorks.isEmpty()) {
      ProductionLedger harvestLedger = ctx.ledger.toLedger();
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "HARVEST",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "closedUnits",
                  ctx.harvestWorks.size(),
                  "gross",
                  traceTotalByIndustry(harvestLedger.gross()),
                  "losses",
                  traceTotalByIndustry(harvestLedger.losses()),
                  "inputs",
                  traceTotalByIndustry(harvestLedger.inputs()),
                  "outputAccruals",
                  harvestLedger.outputAccruals().size(),
                  "transfers",
                  harvestLedger.transfers().size(),
                  "ruleSettlements",
                  harvestLedger.ruleSettlements().size()));
      if (TRACE.isDebugEnabled()) {
        for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry :
            harvestLedger.gross().entrySet()) {
          IndustryId industry = entry.getKey();
          EventLog.channel(TRACE)
              .debug(
                  LogEvent.of(
                      "HARVEST_BY_INDUSTRY",
                      EconomyLogSource.ECONOMY_SETTLEMENT,
                      "day",
                      ctx.day,
                      "industry",
                      industry.value(),
                      "gross",
                      entry.getValue(),
                      "losses",
                      harvestLedger.losses().getOrDefault(industry, Map.of()),
                      "inputs",
                      harvestLedger.inputs().getOrDefault(industry, Map.of())));
        }
      }
      // ★★ A1（2026-10-10）：**运输服务产出**的 INFO/DEBUG（AGENTS §一.9；口径见 logHaulServiceOutput 的类注）。
      //   ★★★ A5：返回值 = 本日运输服务的**净产**（毫服务）—— 当日对账 INFO 的"产出"一栏（产出 − 交付 − 作废
      //     就是当期留下的服务货，三者必须能对上）。
      ctx.haulServiceProducedMilli = logHaulServiceOutput(ctx.day, harvestLedger, ctx.dayView);
    }
  }
}
