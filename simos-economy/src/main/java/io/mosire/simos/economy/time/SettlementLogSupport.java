package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomySettlement.MILLI_PER_GRAIN;

import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.time.ProductionInputSettlement.InputPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class SettlementLogSupport {

  /** 阶段/事件日志：settlement 分类（INFO 生命周期、DEBUG 池子/汇总）。 */
  static final Logger TRACE = EconomyLog.settlement();

  /** 逐笔原始事件日志：trace 分类（每笔转移/成交槽/债务变动）。 */
  static final Logger RAW = EconomyLog.trace();

  /** B4：外汇窗口装配（fx 分类 —— 与 {@code FxSettlement} 的 {@code FX_ROUND} 同一个 logger，便于一处看全外汇面）。 */
  static final Logger FX = EconomyLog.fx();

  /** ★★ R1：政府市场授权的生命周期（market 分类 —— 与 {@code MarketSettlement} 的挂单/成交事件同一个 logger）。 */
  static final Logger MANDATE = EconomyLog.market();

  /**
   * ★★ W5-fix6（D5）：<b>债务状态面</b>（debt 分类 —— 与 {@code DebtContractBook} / {@code
   * EconomyLiquidationSettlement} 同一个 logger）—— 自持合同的"不计息"具名 DEBUG 走它。
   */
  static final Logger DEBT = EconomyLog.debt();

  /** 追踪日志辅助：家户 → 商品 → 数量的两层表求和（只在 DEBUG 打开时调用）。 */
  static long traceTotalGoods(Map<HouseholdId, Map<CommodityId, Long>> table) {
    long total = 0L;
    for (Map<CommodityId, Long> goods : table.values()) {
      for (long quantity : goods.values()) {
        total += quantity;
      }
    }
    return total;
  }

  /**
   * ★★ <b>A1（2026-10-10）：运输服务产出的 INFO + DEBUG（AGENTS §一.9"新增/修改经济阶段必须同时加日志"）</b>。
   *
   * <p>★★ <b>它记的是哪一件事</b>：{@code trade@hex}（跑商/承运产业）在**收获**这一步产出了商品 {@code haul}（运输服务） —— 即用户
   * 2026-10-10 那句「跑商不是生产方式吗？」的落点：跑商的产出从此走**标准生产管线**的同一段代码（{@code harvest} 的净产入账 + relation
   * 结算），不再是市场轮里的私有分支。
   *
   * <pre>
   * INFO  HAUL_SERVICE_PRODUCED        （毛产里有 haul 才刷）**发生了什么 + 具名计数**：几个产业产出、毛产/净产（毫）、
   *                                    产出计提条数与合计（毫）、有牌价的格数
   * DEBUG HAUL_SERVICE_PRODUCED_DETAIL **为什么**：逐产业给 haul 的每规模单位产出/劳动/投入与该格的**牌价有无** ——
   *                                    缺价 ⇒ 按既有 MarketTable.prices 口径"不交易" ⇒ 本批世界里它只入账、不成交（I-H3 缺省中性）
   * </pre>
   *
   * <p>★ <b>只读</b>：不写状态、不改任何公式、失败不影响结算（{@code EventLog} 的口径）。★ 调用点只在 {@code harvestWorks}
   * 非空时；且**毛产里没有 haul 就一条都不刷** ⇒ 无跑商/无该产出的世界日志面逐字不变。
   *
   * <p>★★ <b>A5（2026-10-10）：返回值 = 本日运输服务的净产</b>（毫服务）—— 当日对账 INFO（{@code HAUL_SERVICE_SETTLED}）的
   * "产出"一栏。没有产出 ⇒ 0（不是"没算"）。
   *
   * @param day 当日日号（两级日志都必带）
   * @param ledger 当日**收获**这一段的发生额（只读）
   * @param view ★★ B2：当日视图（当刻市场表 + 当刻产业表）—— 本方法是**逐日**读者（每天都可能刷）， 故不得读段首 {@code
   *     EconomyData}（那会把"今天的定价/今天的产出"读成段首那一份）
   * @return 本日运输服务的净产（毫服务；毛产 − 收获损耗，逐产业求和；没有产出 ⇒ 0）
   */
  static long logHaulServiceOutput(long day, ProductionLedger ledger, EconomyDayView view) {
    long grossMilli = 0L;
    long netMilli = 0L;
    long industries = 0L;
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      Long produced = entry.getValue().get(EconomyCommodities.HAUL);
      if (produced == null || produced <= 0L) {
        continue;
      }
      industries++;
      long loss =
          ledger
              .losses()
              .getOrDefault(entry.getKey(), Map.of())
              .getOrDefault(EconomyCommodities.HAUL, 0L);
      grossMilli = Math.addExact(grossMilli, produced);
      netMilli = Math.addExact(netMilli, produced - loss);
    }
    if (industries <= 0L) {
      return 0L; // 本日没有任何产业产出运输服务 ⇒ 一条都不刷、净产读数 0
    }
    long accrualLegs = 0L;
    long accrualMilli = 0L;
    for (ProductionLedger.ActorEntry accrual : ledger.outputAccruals()) {
      if (EconomyCommodities.HAUL.equals(accrual.commodity())) {
        accrualLegs++;
        accrualMilli = Math.addExact(accrualMilli, accrual.delta());
      }
    }
    long pricedMarkets = 0L;
    for (MarketTable market : view.marketTables().values()) {
      if (market.hasPrice(EconomyCommodities.HAUL)) {
        pricedMarkets++;
      }
    }
    // ── INFO：发生了什么 + 具名计数 ──────────────────────────────────────────────────────────
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "HAUL_SERVICE_PRODUCED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "industries",
                industries,
                "grossMilli",
                grossMilli,
                "netMilli",
                netMilli,
                "accrualLegs",
                accrualLegs,
                "accrualMilli",
                accrualMilli,
                "pricedMarkets",
                pricedMarkets));
    // ── DEBUG：为什么（逐产业的配方读数 + 牌价有无 ⇒ 交易/不交易）────────────────────────────
    if (!TRACE.isDebugEnabled()) {
      return netMilli;
    }
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : ledger.gross().entrySet()) {
      Long produced = entry.getValue().get(EconomyCommodities.HAUL);
      if (produced == null || produced <= 0L) {
        continue;
      }
      IndustryDefinitionView industry = view.industries().get(entry.getKey());
      long haulPerScaleUnit =
          industry == null
              ? 0L
              : industry.recipe().outputPerUnit().getOrDefault(EconomyCommodities.HAUL, 0L);
      long impliedScale =
          haulPerScaleUnit <= 0L
              ? 0L
              : produced / Math.multiplyExact(haulPerScaleUnit, MILLI_PER_GRAIN);
      HexCoord hex = IndustryHexKeys.hexKeyOf(entry.getKey()).map(HexCoord::parse).orElse(null);
      // ★★ W1e（2026-10-11）：`hex` **可能为 null**（产业 id 不含 `@`、或 `@` 之后为空 ⇒ hexKeyOf 返回空），
      //   必须在**任何解引用之前**判掉，且**只判这一次**：null ⇒ 不查牌价表（priced=false，不猜牌价）、
      //   hex 栏记空串、reason 具名 —— 改动前 `:595` 把 null 直接交给 MarketTables.atOrNull，
      //   后者经 marketIdAt 的 `Objects.requireNonNull(hex, "hex")` 会当场 NPE（SpotBugs 报的
      //   RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE 就是这个真隐患，不是误报）。
      boolean hexResolved = hex != null;
      MarketTable market = hexResolved ? MarketTables.atOrNull(view, hex) : null;
      boolean priced = market != null && market.hasPrice(EconomyCommodities.HAUL);
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HAUL_SERVICE_PRODUCED_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "industry",
                  entry.getKey().value(),
                  "hex",
                  hexResolved ? hex.q() + "," + hex.r() : "",
                  "grossMilli",
                  produced,
                  "lossMilli",
                  ledger
                      .losses()
                      .getOrDefault(entry.getKey(), Map.of())
                      .getOrDefault(EconomyCommodities.HAUL, 0L),
                  "haulPerScaleUnit",
                  haulPerScaleUnit,
                  "impliedScale",
                  impliedScale,
                  "laborPerUnit",
                  industry == null ? 0L : industry.recipe().laborPerUnit(),
                  "inputPerUnit",
                  industry == null ? Map.of() : industry.recipe().inputPerUnit(),
                  "marketPriced",
                  priced,
                  "reason",
                  hexResolved
                      ? (priced ? "priced" : "no-haul-price-no-trade")
                      : "industry-hex-unparsable"));
    }
    return netMilli;
  }

  /** 追踪日志辅助：产业 → 商品 → 数量的两层表求和（只在 DEBUG 打开时调用）。 */
  static long traceTotalByIndustry(Map<IndustryId, Map<CommodityId, Long>> table) {
    long total = 0L;
    for (Map<CommodityId, Long> goods : table.values()) {
      for (long quantity : goods.values()) {
        total += quantity;
      }
    }
    return total;
  }

  /** 追踪日志辅助：家户 → 标量求和（只在 DEBUG 打开时调用）。 */
  static long traceTotalLongs(Map<HouseholdId, Long> table) {
    long total = 0L;
    for (long value : table.values()) {
      total += value;
    }
    return total;
  }

  /**
   * 追踪日志辅助：家户 → 币种 → 余额的两层表**按币分列**求和（只在 DEBUG/TRACE 打开时调用）。
   *
   * <p>★★ <b>P-T4：不再跨币相加</b>（改前是把所有币种 1:1 加成一个 {@code long}）。键按币种 id 升序 = 内容的纯函数 （不用 map
   * 迭代序）。零额条目不发（"没有这种钱"与"这种钱动了 0"分得开）。
   */
  static Map<String, Long> traceMoneyByCurrency(Map<HouseholdId, Map<CurrencyId, Long>> table) {
    Map<String, Long> totals = new TreeMap<>();
    for (Map<CurrencyId, Long> money : table.values()) {
      for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
        if (entry.getValue() != 0L) {
          totals.merge(entry.getKey().value(), entry.getValue(), Long::sum);
        }
      }
    }
    return totals;
  }

  /**
   * 追踪日志辅助：这两层表里有没有**正**额（= 改前"全部币 1:1 标量和 &gt; 0"那个门槛的等价形态）。 ★ 等价性：还款腿只累加正额 ⇒ "有一笔正的" 与 "标量和 &gt;
   * 0" 同真同假。
   */
  static boolean anyPositiveMoney(Map<HouseholdId, Map<CurrencyId, Long>> table) {
    for (Map<CurrencyId, Long> money : table.values()) {
      for (long quantity : money.values()) {
        if (quantity > 0L) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * ★★ W2-fix3：<b>逐 unit 的"扣产能"明细 —— DEBUG（{@code PRODUCTION_CAPACITY_DEDUCTED_DETAIL}）</b>。
   *
   * <p>改前 {@code PRODUCTION_CAPACITY_DEDUCTED} 是<b>逐 unit 一条 INFO</b>（真档一轮可能上百行）；现在它的字段整体下到
   * DEBUG，INFO 只留轮末一条汇总（{@link CapacityDeductionSummary}）。★ <b>一个字段都没丢</b>：本事件的字段 = 改前 INFO 的全部字段
   * （unit/industry/households/scarcestInput/scarcestOnHand/scarcestRequired/satisfactionPerMille/plannedScale/actualScale/
   * deductedScale/why）+ 原有的逐商品两张表（perUnit/onHand）。
   *
   * <p>只在<b>真的发生扣减</b>（满足率 &lt; 1000‰）时被调用；DEBUG 关着 ⇒ 直接返回（日志失败不得影响结算）。
   */
  static void logCapacityDeductionDetail(
      long day, InputPlan plan, CommodityId scarcest, long satisfactionPerMille, long actualScale) {
    if (!TRACE.isDebugEnabled()) {
      return; // 日志关闭不得影响结算
    }
    // ★ W2-fix3（①）：两个"最短缺"读数先在**基本型 long** 上落定，再进事件表 —— 改前这里写的是
    //   `scarcest == null ? 0L : hand.getOrDefault(scarcest, 0L)`：三元表达式把 Long 拆箱、随即又为
    //   事件表的 Object 形参装箱（SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED @ 5898）。数值与字段语义一字不变。
    long scarcestOnHand = 0L;
    long scarcestRequired = 0L;
    if (scarcest != null) {
      scarcestOnHand = plan.available.getOrDefault(scarcest, 0L);
      scarcestRequired = plan.plannedScale * plan.perScale.getOrDefault(scarcest, 0L);
    }
    Map<String, Long> perInput = new LinkedHashMap<>();
    Map<String, Long> handByInput = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
      perInput.put(entry.getKey().value(), entry.getValue());
      handByInput.put(entry.getKey().value(), plan.available.getOrDefault(entry.getKey(), 0L));
    }
    EventLog.channel(TRACE)
        .debug(
            LogEvent.of(
                "PRODUCTION_CAPACITY_DEDUCTED_DETAIL",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "day",
                day,
                "unit",
                plan.id.value(),
                "industry",
                plan.industry.id().value(),
                "households",
                plan.ownKeys.stream().map(HouseholdId::value).toList(),
                "scarcestInput",
                scarcest == null ? "none" : scarcest.value(),
                "scarcestOnHand",
                scarcestOnHand,
                "scarcestRequired",
                scarcestRequired,
                "satisfactionPerMille",
                satisfactionPerMille,
                "plannedScale",
                plan.plannedScale,
                "actualScale",
                actualScale,
                "deductedScale",
                plan.plannedScale - actualScale,
                "why",
                "S-11：计划产能 × 最短缺投入的满足率（拿不到 = 物理短缺，非因穷）",
                "perUnit",
                perInput,
                "onHand",
                handByInput));
  }

  /**
   * ★★★ <b>W2-fix7 / D1②（控制方裁定 v3.10 §7）：实物租腿「三级支付链」的日志面</b>。
   *
   * <pre>
   * ① 本 unit 产出 = netByCommodity[commodity]（本周期这条产业的净产；creditOutput 已计进家户账）
   * ② 家户库存     = payerStock[commodity]（结算前的会话库存合计，含 ①）
   * ③ 具名缺口     = 应付 − 实付 ⇒ **INFO 一条**（默认档可见）+ RuleSettlement.owed（S4 读数）+ 关账资本化成债权
   * </pre>
   *
   * <p>★ <b>为什么逐条打、而不是只打缺口</b>：现场要能回答"这一轮租金吃的是新产出还是老库存" —— 只打缺口时 "付得出"的那些一条都看不见，读者无法判断 D1
   * 的三级链到底有没有被用到（旧实现里这类租腿是当场抛）。
   *
   * <p>★ <b>只读</b>：不改任何状态、不改任何公式、不影响守恒；日志失败不得影响结算（§一.9）。
   *
   * @param outcome 已结算的读数（{@link ProductionLedger.RuleSettlement} 逐规则一条，连实付 0 的也在）
   * @param netByCommodity 本 unit 本周期逐商品净产（①）
   * @param payerStock 付方主体结算前逐商品库存（②；含 ①）
   */
  static void logInKindRentChain(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      ProductionSettlement.Outcome outcome,
      Map<CommodityId, Long> netByCommodity,
      Map<CommodityId, Long> payerStock) {
    for (ProductionLedger.RuleSettlement reading : outcome.ruleSettlements()) {
      if (reading.rule().type() != RuleType.FIXED_IN_KIND_RENT) {
        continue; // 三级支付链只服务实物租腿；其余档的读数走 S4 的既有面
      }
      if (reading.commodity().isEmpty()) {
        continue; // 实物租腿必有商品（CompensationRule 的构造期守卫）；这里是兜底，不猜
      }
      CommodityId commodity = reading.commodity().get();
      boolean producible = industry.outputPerUnit().containsKey(commodity);
      long gap = reading.owed();
      if (gap > 0L) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "IN_KIND_RENT_CHAIN_GAP",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "unit",
                    unit.id().value(),
                    "industry",
                    industry.id().value(),
                    "commodity",
                    commodity.value(),
                    "payee",
                    reading.payee().toString(),
                    "dueMilli",
                    reading.dueAmount(),
                    "paidMilli",
                    reading.paidNow(),
                    "gapMilli",
                    gap,
                    "unitOutputMilli",
                    netByCommodity.getOrDefault(commodity, 0L),
                    "payerStockMilli",
                    payerStock.getOrDefault(commodity, 0L),
                    "commodityInIndustryOutput",
                    producible,
                    "why",
                    "in-kind-rent-chain:unit-output-then-household-stock-remainder-is-a-named-gap"
                        + (producible ? "" : " (该商品不在本产业产出表里)")));
        continue;
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "IN_KIND_RENT_CHAIN",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "unit",
                    unit.id().value(),
                    "industry",
                    industry.id().value(),
                    "commodity",
                    commodity.value(),
                    "payee",
                    reading.payee().toString(),
                    "dueMilli",
                    reading.dueAmount(),
                    "paidMilli",
                    reading.paidNow(),
                    "unitOutputMilli",
                    netByCommodity.getOrDefault(commodity, 0L),
                    "payerStockMilli",
                    payerStock.getOrDefault(commodity, 0L),
                    "commodityInIndustryOutput",
                    producible,
                    "why",
                    "in-kind-rent-paid-in-full (①本 unit 产出 → ②家户库存)"));
      }
    }
  }

  // ── 套利活动的日志（AGENTS §一.9：新阶段必须有 INFO/DEBUG/TRACE 三级）──────────────────────

  /**
   * ★★ <b>本轮套利决定的 INFO 汇总</b>（谁、哪个方向、多少量 —— "这一轮发生了什么"）。
   *
   * <p>★ <b>只看 INFO 的人能回答</b>：本日有几户在套利、共要买多少货、为此花了多少劳动、预期净收益多少。 逐户理由在 {@code LaborQueueSettlement}
   * 的 DEBUG（{@code ARBITRAGE_OPPORTUNITY} / {@code ARBITRAGE_GRANTED}）， 逐笔订单在 {@code
   * MarketSettlement} 的 TRACE（{@code ARBITRAGE_BUY_ORDER}）。
   */
  static void logArbitrageRound(long day, MarketArbitragePlan plan) {
    if (plan.isEmpty()) {
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ARBITRAGE_ROUND",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "households",
                    0,
                    "buys",
                    0,
                    "totalQuantityMilli",
                    0,
                    "totalLaborMilli",
                    0,
                    "reason",
                    "no-opportunity-or-no-market"));
      }
      return; // 没有套利：不刷 INFO（"今天没有套利"不是生命周期事件）
    }
    long buys = 0L;
    long holds = 0L;
    long totalQuantity = 0L;
    long totalLabor = 0L;
    long totalNetMicro = 0L;
    java.util.TreeSet<String> commodities = new java.util.TreeSet<>();
    for (MarketArbitragePlan.Instruction instruction : plan.instructions()) {
      if (instruction.direction() == HouseholdActivity.Direction.BUY) {
        buys++;
      } else {
        holds++;
      }
      totalQuantity = Math.addExact(totalQuantity, instruction.quantityMilli());
      totalLabor = Math.addExact(totalLabor, instruction.laborMilli());
      // ★★ 量纲（2026-10-08 诊断缺陷修复；改前这里是"毫商品 × 毫/单位 = 毫²"却叫 Micro）：
      //     量（毫商品）× 单位价差（**微** numeraire / **商品单位**）÷ 1000（毫商品/商品单位）
      //     = **微 numeraire**  ⇒ 变量名 totalNetMicro 与算式从此同名同尺。
      totalNetMicro =
          Math.addExact(
              totalNetMicro,
              Math.multiplyExact(instruction.quantityMilli(), instruction.edgeMicro())
                  / MILLI_PER_GRAIN);
      commodities.add(instruction.commodity().value());
    }
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "ARBITRAGE_ROUND",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "day",
                day,
                "households",
                plan.size(),
                "buys",
                buys,
                "holds",
                holds,
                "totalQuantityMilli",
                totalQuantity,
                "totalLaborMilli",
                totalLabor,
                // 预期净收益（**毫** numeraire）= 微 numeraire ÷ 1000（微/毫是价格刻度，与"毫商品/商品单位"无关，
                // 故这里用的是 TradeArbitrageActivity.MICRO_PER_MILLI 而不是 MILLI_PER_GRAIN —— 两者数值同为 1000，
                // 量纲完全不同，混用正是本次修的缺陷类型）。
                "totalExpectedNetMilli",
                totalNetMicro / TradeArbitrageActivity.MICRO_PER_MILLI,
                "commodities",
                String.join(",", commodities)));
    if (TRACE.isDebugEnabled()) {
      for (MarketArbitragePlan.Instruction instruction : plan.instructions()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ARBITRAGE_INSTRUCTION",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "household",
                    instruction.household().value(),
                    "direction",
                    instruction.direction(),
                    "commodity",
                    instruction.commodity().value(),
                    "quantityMilli",
                    instruction.quantityMilli(),
                    // ★ 2026-10-08 诊断缺陷修复：字段名 = 真实量纲（微 numeraire / 商品单位）
                    "reservationMicro",
                    instruction.reservationMicro(),
                    "marketMicro",
                    instruction.marketMicro(),
                    "edgeMicro",
                    instruction.edgeMicro(),
                    "laborMilli",
                    instruction.laborMilli()));
      }
    }
  }

  /**
   * ★★ <b>B4（2026-10-08）：本轮外汇窗口装配的日志</b>（§一.9）—— 新接线（区级汇率参与报价）必须能从日志看出来：
   *
   * <ul>
   *   <li><b>INFO</b>（有窗口时一条 {@code FX_WINDOWS_ASSEMBLED}）：这一轮发生了什么 —— 几个窗口、区表几条、
   *       其中几个区带区级覆盖、政府表几个（"有窗口"是生命周期事件；"没有窗口"不刷 INFO，与 {@code logArbitrageRound} 同款）；
   *   <li><b>DEBUG</b>（无论有没有窗口都一条 {@code FX_WINDOWS_ASSEMBLED_DETAIL}）：为什么 —— 没有窗口时写明"没有任何生效报价 ⇒
   *       fail-closed 不开张"，逐窗口一条 {@code FX_WINDOW_ASSEMBLED} 记属主/币对/买价/卖价/储备上限，以及
   *       <b>这个价来自区级覆盖（哪个区）还是 GOV 级</b>（区级优先回落的现场判据）。
   * </ul>
   *
   * <p>★ 日志只读状态：不写状态、不改公式、失败不影响结算（{@code EventLog} 的口径）。
   *
   * <p>★★ <b>B2（2026-10-10）：本方法是**逐日**读者 ⇒ 入参是当日视图</b> —— 政府表在段内会变（GM 设报价、发行腿 追加记录），读段首 {@code
   * EconomyData} 会把"今天有几个政府窗口/几条区级覆盖"读成段首那一份。
   */
  static void logFxWindows(long day, EconomyDayView view, FxRoundInput fxInput) {
    List<MarketZone> zones = MarketZoneBook.zones(view.marketZones());
    int zonesWithRates = 0;
    for (MarketZone zone : zones) {
      if (!zone.officialRates().isEmpty()) {
        zonesWithRates++;
      }
    }
    if (fxInput.isActive()) {
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_WINDOWS_ASSEMBLED",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  day,
                  "windows",
                  fxInput.windows().size(),
                  "zones",
                  zones.size(),
                  "zonesWithOfficialRates",
                  zonesWithRates,
                  "governments",
                  view.governments().size()));
    }
    if (!FX.isDebugEnabled()) {
      return;
    }
    int governmentsWithRates = 0;
    for (Government government : view.governments().values()) {
      if (!government.officialRates().isEmpty()) {
        governmentsWithRates++;
      }
    }
    EventLog.channel(FX)
        .debug(
            LogEvent.of(
                "FX_WINDOWS_ASSEMBLED_DETAIL",
                EconomyLogSource.ECONOMY_FX,
                "day",
                day,
                "windows",
                fxInput.windows().size(),
                "zones",
                zones.size(),
                "zonesWithOfficialRates",
                zonesWithRates,
                "governmentsWithRates",
                governmentsWithRates,
                "reason",
                fxInput.isActive()
                    ? "有生效报价（区级优先、按币对回落 GOV 级）"
                    : "没有任何生效报价 ⇒ 本轮没有外汇窗口（fail-closed：没有政策价可锚）"));
    for (FxRoundInput.Window window : fxInput.windows()) {
      OfficialRate rate = window.rate();
      List<MarketZone> covering =
          MarketZoneBook.zonesCovering(view, window.governmentId(), rate.base(), rate.quote());
      EventLog.channel(FX)
          .debug(
              LogEvent.of(
                  "FX_WINDOW_ASSEMBLED",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  day,
                  "government",
                  window.governmentId().value(),
                  "base",
                  rate.base().value(),
                  "quote",
                  rate.quote().value(),
                  "buyPerMille",
                  rate.buyPerMille(),
                  "sellPerMille",
                  rate.sellPerMille(),
                  "reserveCapBaseMilli",
                  window.reserveCapBaseMilli(),
                  "rateSource",
                  covering.isEmpty()
                      ? "government"
                      : "market-zone:" + covering.get(0).zoneId().value()));
      if (covering.size() > 1) {
        // ★ 一个 GOV 能被多个区的区级报价覆盖（它能发行多个区的法定币）、同币对给了不同报价 ⇒ 规范序第一个区胜出，
        //   其余被覆盖：被丢掉的那几条政策价必须具名出现在日志里（不许静默吞掉另一条政府报价）。
        EventLog.channel(FX)
            .debug(
                LogEvent.of(
                    "FX_ZONE_RATE_CONFLICT",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    day,
                    "government",
                    window.governmentId().value(),
                    "base",
                    rate.base().value(),
                    "quote",
                    rate.quote().value(),
                    "chosenZone",
                    covering.get(0).zoneId().value(),
                    "chosenBuyPerMille",
                    rate.buyPerMille(),
                    "chosenSellPerMille",
                    rate.sellPerMille(),
                    "droppedZoneRates",
                    droppedZoneRates(covering, rate.base(), rate.quote()),
                    "rule",
                    "MarketZoneBook#zoneCovering：规范序第一个覆盖该币对的区胜出"));
      }
    }
  }

  /** 冲突里被覆盖掉的区级报价（{@code zone=buy/sell,…}；只用于 DEBUG 日志，不改任何判定）。 */
  static String droppedZoneRates(List<MarketZone> covering, CurrencyId base, CurrencyId quote) {
    StringBuilder text = new StringBuilder();
    for (MarketZone zone : covering.subList(1, covering.size())) {
      if (text.length() > 0) {
        text.append(',');
      }
      text.append(zone.zoneId().value())
          .append('=')
          .append(
              zone.officialRate(base, quote)
                  .map(rate -> rate.buyPerMille() + "/" + rate.sellPerMille())
                  .orElse("?"));
    }
    return text.toString();
  }

  // ── P-T1b：三层税的当日汇总日志（§一.9 的 INFO 档）────────────────────────────────────

  /**
   * ★★ <b>P-T1b：当日分层的税汇总</b>（§一.9：INFO = "这一轮发生了什么"）—— <b>一行 = 一个（层 × 收款政府 × 币种）</b>
   * 的实收金额（毫），外加一行按币分列的总额（= 买方多付的那一部分）。
   *
   * <p>★★ <b>为什么要这样分组</b>：读日志的人要能直接回答三个问题 —— ① 收的是哪一层（出口/进口/区内）； ② <b>进了哪个国库</b>；③
   * 什么币、多少。跨币求和会把这三种钱当一种（I-C10 / N5），所以币种进键。
   *
   * <p>★ <b>缺省不发</b>：没有税（或本轮没有成交）⇒ {@code taxItems} 为空 ⇒ 一行都不发，旧世界的日志逐字不变。
   */
  static void logTaxCollected(long day, MarketReport report) {
    Map<MarketReport.TaxKey, Long> byLayer = report.taxByLayerGovernmentCurrency();
    if (byLayer.isEmpty()) {
      return;
    }
    for (Map.Entry<MarketReport.TaxKey, Long> entry : byLayer.entrySet()) {
      MarketReport.TaxKey key = entry.getKey();
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "MARKET_TAX_COLLECTED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "layer",
                  key.layer().value(),
                  "government",
                  key.government(),
                  "currency",
                  key.currency().value(),
                  "amountMilli",
                  entry.getValue(),
                  "reason",
                  "three-layer-tax-transferred-to-treasury"));
    }
    for (Map.Entry<CurrencyId, Long> entry : report.taxByCurrency().entrySet()) {
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "MARKET_TAX_COLLECTED_TOTAL",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "currency",
                  entry.getKey().value(),
                  "amountMilli",
                  entry.getValue(),
                  "reason",
                  "buyer-paid-above-goods-price",
                  "items",
                  report.taxItems().size()));
    }
  }

  // ★★ R3B.2：旧的 withCycleState(IndustryDefinitionView…) 已删除 ——
  // 周期状态（progress/cycleLabor/cycleInputUsed）
  //   现在属于 ProductionProcess（见 ProductionProcess#withCycleState），IndustryDefinitionView
  // 只留模板、不再每天重建。

  /**
   * ★ <b>日志安全的拒绝理由</b>（照 L2 的 {@code logReason} 形态）：结算内部消息可能回显字段值/载荷片段；日志只保留可读前缀 ——截到第一个 JSON
   * 起始符/换行，避免把载荷原文带进日志。截断只影响日志文本，不影响异常与控制流。
   */
  static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    if (text.startsWith("payload ")) {
      int colon = text.indexOf(':');
      if (colon >= 0 && colon < cut) {
        cut = colon;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }
}
