package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.SettlementLogSupport.MANDATE;
import static io.mosire.simos.economy.time.SettlementLogSupport.RAW;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.logArbitrageRound;
import static io.mosire.simos.economy.time.SettlementLogSupport.logFxWindows;
import static io.mosire.simos.economy.time.SettlementLogSupport.logTaxCollected;
import static io.mosire.simos.economy.time.ShipmentSettlement.haulServiceHexes;
import static io.mosire.simos.economy.time.ShipmentSettlement.haulServicePaidByCurrency;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.Budget;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayMarketPipeline {

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void settleMarket(SettlementDayContext ctx) {
    executeMarketRound(ctx, prepareMarketRound(ctx));
  }

  /**
   * ★★★ <b>P3.0：只装配本轮市场，不撮合、不冻结、不落账。</b>
   *
   * <p>保留旧 {@code settleMarket} 中直到 {@code clearOncePerCycle} 之前的全部构造/注入：本轮 {@link
   * MarketSettlement.MarketRound}、运力池、市场触发、套利/外汇/政府授权/口岸/税/采购优先级注入及防复发守卫。返回并写入 {@code
   * ctx.marketRound}，供 {@link #executeMarketRound(SettlementDayContext,
   * MarketSettlement.MarketRound)} 在同一对象上执行撮合。
   */
  static MarketSettlement.MarketRound prepareMarketRound(SettlementDayContext ctx) {
    // ── 4. 区域市场清算（M2.3/M2.4：每 5 天一轮；区内即时 / 跨区 ETA）────────────────
    //   ★★ 调度只依赖**绝对世界日 + 市场表是否为空**（M0.1）：两条推进路径在同一天必然同轮。
    //   ★ 它读**当刻**市场表（价格是数据）：缺格的格没有市场 ⇒ 不触发（不造默认价）。
    //     ★ B2：本段的市场表来自会话工作副本（见上面的 `markets` 局部量），不是段首 base。
    //   ★★ 买卖**只走唯一的 applier**（applyTransfer）：本步绝不直接改副本 ——
    //     "任何库存变动必有对应转移记录"这条不变量的落点因此仍是一处（见 MarketSettlement 的类注）。
    //   ★★ M2.1 起这一支不再按"本周期缺口/余量"就地配对，而是**主体各自生成订单**（家户 + 经营者，见
    //     {@link MarketSettlement} 的类注）：生活保留 = 到下一市场轮的 5 tick 基础消费 + 生产投入。
    //   ★★ M2 的冻结写者从这里接上：挂单冻结 → 成交/发运/轮末释放（冻结表随后原样带出，净额必然回到基值）。
    //   ★★★ W5.5（2026-10-11，S-12②）：**货币腿在撮合之前**（挂商品 → 挂钱 → 借钱 → 买货）——
    //     见 {@code MarketSettlement} 的 1d 段与 {@code MoneyMarketSettlement}；借粮腿已整段退役（S-12①）。
    ctx.marketRound =
        new MarketSettlement.MarketRound(
            ctx.day,
            ctx.householdEconomies,
            ctx.householdGoods,
            ctx.householdMoney,
            ctx.householdFrozenGoods,
            ctx.householdFrozenMoney,
            ctx.unmetToday,
            ctx.householdOfActor,
            ctx.industries,
            ctx.units,
            ctx.assetShares,
            ctx.relations,
            ctx.laborCommitments,
            ctx.shipments,
            ctx.ledger,
            ctx.operatorConditions,
            ctx.settlementIndex,
            // ★★ R4-E2：当日有效需求来自状态组件的只读账本（订单路径据此把"生活保留基线 + 需求目标"合成买卖目标）。
            // ★ B2-保留段首base：`demands` 无工作副本（写入口只有命令面）⇒ 段内不变。
            ctx.base.demands(),
            // ★★ P-T1c：生产路径默认区内市场规则（单区锚格 = markets 规范序第一个 hex；无税费 ⇒ 逐值现状）。
            //   跨市场区/自定义税费由后续批次经 GM 命令面注入同一入口。
            MarketRegulation.defaultsFor(ctx.markets, ctx.base.marketZones()),
            // ★★ R1：本入口不预置债务记账能力（开市判定后由 withDeferredPayments 补）；排除集 = **只有**组合根传入的单位户
            //   （国库户不再并入：它现在是"只按授权下单"的市场参与者）。
            null,
            null,
            ctx.effectiveMarketExcludedHouseholds);
    // ★★ M-A1：逐 hex 运力池（派生量，一轮一份、不落状态）—— 成员 = 该格"选了跑商"的家户（主业或副业含 merchant
    //   生产方式的位置，J-A）；运力 = f(劳动投入, 工具可投入量)（见 MerchantCapacity）。
    //   ★ 位置 = 生产/欠租阶段之后、市场装配之前：它必须在撮合时可用，且必须读**当刻**的劳动与商品账（每轮重算、不累积）。
    //   ★ 工具维读会话商品账（tool 商品；V-22：不动 AssetKind.SMALL_TOOL 产权份额）。
    // ★★★ A3（2026-10-10）：原来的"运力报价表"（{@code CapacityQuote}/{@code CapacityQuoteBook}，逐户自报价簿）
    //   **已整族退役** —— 限价改为**纯派生量**（{@code MerchantCapacityPool.askPerMilleOf}：派生承运成本 × 规模档
    //   + 具名固定"上门"附加费），服务成市 lane 上的钱由**市场牌价**定（{@code MarketTable.prices[haul]}，见 A2）。
    //   ⇒ 生产路径传 {@code priced = true}（第 6 参）；夹具 / 纯状态读者传 {@code false} ⇒ 逐值退回 M-A1（I-C2）。
    //   ★★ 2026-10-10 G3-fix-1 + G3-fix-2：工具维必须读**可用量**（现货 − 冻结），且必须在**承运选择点**现读 ——
    //     与提交侧的实扣判据（{@link #consumeForLoss}：可用量 < 一趟 ⇒ 一点也不烧）同口径、同活表。
    //     ★★ 时点（这里曾被写错、并因此让修复空转了一整轮）：本处装配在市场轮**之前**，而本轮的卖单冻结由
    //       {@code MarketSettlement.commitFreezes} 在市场轮**内**才落表 ⇒ 在装配点读冻结，减项**结构上不可能**
    //       含本轮承诺（G3b 复验：真实世界 `TOOL_SHORT_AT_COMMIT` 3,589 条逐值未变、708 组连运费腿一起成立）。
    //       ⇒ 口径落在 {@link MerchantCapacityPool#select}：它拿到的 goods/frozenGoods 是**活视图**
    //         （{@code accounts.householdGoods()/householdFrozenGoods()}，与市场轮写的是同一张表），
    //         在每条承运被判定的那一刻现读 {@code max(0, 现货 − 冻结)}。
    //     ★ 本处仍把活视图传进池（第 4/5 参 = 活视图的来源）；逐户装配读数（{@code MERCHANT_CAPACITY_HOUSEHOLD}，
    //       toolMilli = 装配时的可用量）由紧接着的那一行发出（见下）。
    // ★★★ A5（2026-10-10）：本日"服务成市"的格集**只算一次**，同一份值同时喂 ① 运力池的装配（下面第 7 参）
    //   ② 当日对账 INFO 一行的"要不要刷"判据（见 4 段末）—— 一处拼写、两处用它，不可能漂开。
    ctx.haulServiceMarketHexes = haulServiceHexes(ctx.dayView);
    ctx.carrierPool =
        MerchantCapacityPool.of(
            ctx.session.sheet().classMemberships(),
            // ★ B2：阶层位置模板（无工作副本 ⇒ 段内不变）也统一走当日视图 —— 逐日读者只认一个"当刻值"来源。
            ctx.dayView.classPositions(),
            ctx.householdEconomies,
            ctx.householdGoods,
            ctx.householdFrozenGoods,
            true,
            // ★★ A2（2026-10-10）：**服务成市的格**（本格市场给 haul 定过价；唯一判据 = HaulService.pricedAt）。
            //   这些格的运力预算改成"该户手上的运输服务货"（服务商品账，I-H2）；其余格一个判据都不变（I-H3）。
            //   ★ 逐轮现算（价格是 GM 数据，可随时改）：不落任何状态、不进任何组件。
            ctx.haulServiceMarketHexes,
            // ★★ W7-fix3（2026-10-11）：把**本轮的世界日**交给池 —— {@code select} 的
            //   MERCHANT_CAPACITY_LANE_TRUNCATED 一行据此补上 `day`（同族三条 INFO/DEBUG 都带日）。
            //   ★ 与下面 MarketRound 的 `day` **同源**（本方法同一个 `day`）⇒ 池的 lane 读数与轮级
            //     汇总读数不可能按不同的日对齐。★ 只被日志读：不改任何判据、不落状态。
            ctx.day);
    // ★★ G3-leftovers（2026-10-10）：逐户装配读数（DEBUG；事件 MERCHANT_CAPACITY_HOUSEHOLD）。★ 由 tick 面的本处发
    //   （与下面那条 CAPACITY_PRICING_ASSEMBLY 同位；池只把日**记下来**供 {@code select} 的 lane 读数用，本事件的
    //   发射点不动）⇒ 该事件必须带 `day` 才能与同日的 MARKET_* 逐户按日对齐。
    ctx.carrierPool.logHouseholdAssembly(ctx.day);
    // ★★★ A3（2026-10-10）：原来这条 DEBUG 叫 {@code CAPACITY_QUOTE_BOOK}（报"报价表挂了多少户"）——
    //   自报价簿已整族退役（{@code CapacityQuote}/{@code CapacityQuoteBook}）⇒ 改名
    //   {@code CAPACITY_PRICING_ASSEMBLY}，报"限价是怎么派生的"（不再有逐户挂单这回事），并带上退役族/取代者的
    //   具名行（§一.9 的 INFO/DEBUG 口径）。分类 logger = market（与池内运力事件同一门面）。
    if (MANDATE.isDebugEnabled()) {
      EventLog.channel(MANDATE)
          .debug(
              LogEvent.of(
                  "CAPACITY_PRICING_ASSEMBLY",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  ctx.day,
                  "priced",
                  ctx.carrierPool.isPriced(),
                  "households",
                  ctx.carrierPool.householdCount(),
                  "maxAskPerMille",
                  ctx.carrierPool.maxAskPerMille(),
                  "getReadySurchargePerMille",
                  MerchantCapacityPool.GET_READY_SURCHARGE_PER_MILLE,
                  "pricing",
                  "derived-carrier-cost-per-tier+flat-get-ready",
                  "capacityBudgetMilli",
                  ctx.carrierPool.totalCapacityMilli(),
                  "retiredFamilies",
                  MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_FAMILIES,
                  "replacedBy",
                  MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_REPLACEMENTS));
    }
    ctx.marketTrigger = MarketSettlement.triggerFor(ctx.day, ctx.markets);
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "MARKET_SCHEDULE",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "trigger",
                  ctx.marketTrigger,
                  "markets",
                  ctx.markets.size()));
    }
    // ★★ R1：本轮政府授权挂单的成交累计（市场未触发 ⇒ 空表）；只用于把 filledMilli 累加回授权行。
    ctx.govMandateFills = Map.of();
    if (ctx.marketTrigger != MarketTrigger.NONE) {
      // ★★ D-031：借款人侧不再有信用额度上限 —— 市场信用只带到期周期与当日债务工作副本；唯一上限 = 放贷人
      //   实际可借的货币/卖单剩余。`DebtCapacity` 不再是任何借出路径的门。
      ctx.marketRound = ctx.marketRound.withDeferredPayments(ctx.dueCycle, ctx.debts);
      // ★★ 2026-10-08（阶段 1）：把排序产出的套利决定注入本轮市场（订单生成的输入；不进状态）。
      //   ★ 位置 = 市场轮之前（设计书 §9 的冻结落点）：它必须在撮合之前（它的输出就是订单），且依赖"到货已结算"
      //     （:1240 的 deliverShipments 先于它）。
      MarketArbitragePlan arbitragePlan = ctx.arbitrageCollector.toPlan();
      ctx.marketRound = ctx.marketRound.withArbitrage(arbitragePlan);
      logArbitrageRound(ctx.day, arbitragePlan);
      // ★★ A2a（阶段 2）+ B4（2026-10-08）：把"这个世界有哪些政府外汇窗口、储备上限多少"带进本轮 ——
      //   唯一来源 = EconomyData.governments()（GOV 级报价是状态）+ marketZones（B2 的区级覆盖）+ moneyIssuances
      //   （累计发行量 ⇒ 储备上限）。★ B4 起报价口径 = 区级优先、按币对回落该区发行 GOV 的 GOV 级报价。
      //   ★ 一条生效报价都没有 ⇒ FxRoundInput.none() ⇒ 只有"没有政府窗口"这一件事；★ P-T5 起家户民间簿不依赖它
      //     （逐格逐户按 F-1 购买力自报价），本段仍会跑 —— 缺省中性（单币世界 / 无价可比）由 FxSettlement 自己守。
      //   ★★ B2：三张表全部走**当日视图** —— 政府表与发行审计表都是会话工作副本（当日发行腿会追加记录，
      //     读段首 base 会把"今天的储备上限"按段首累计发行量算），市场区表无工作副本（段内不变）。
      FxRoundInput fxInput =
          FxRoundInput.of(
              ctx.dayView.governments(), ctx.dayView.moneyIssuances(), ctx.dayView.marketZones());
      ctx.marketRound = ctx.marketRound.withFx(fxInput);
      logFxWindows(ctx.day, ctx.dayView, fxInput);
      // ★★★ A3（2026-10-10）：此处原有"注入本轮跑商家户集合"（§16.4 ①，提交 b22da5b7，
      //   {@code marketRound.withMerchantHouseholds(MerchantIdentity.merchants(...))}）—— **已随
      //   {@code MarketRound.merchantHouseholds} 字段一并撤回**：它只服务 {@code necessaryInputsOf} 里
      //   "给跑商家户下夹一趟工具"那段特例，而该特例已删（预留改由产业声明的投入经标准循环覆盖，I-H6）。
      //   ★ 撤回理由与三支直证见 {@code MarketSettlement.necessaryInputsOf} 尾部的注（不再需要"范围"这个概念）。
      // ★★ R1（2026-10-09）：政府市场授权计划 = 本日生效的"明确挂单"（逐轮瞬态，不落盘）。
      //   ★ 它必须**最后**注入：withDeferredPayments/withArbitrage/withFx 三处各自逐字段带过它（克隆丢字段是本类踩过的坑），
      //     而这里注入之后不再有别的 withX。
      //   ★★ 名单上**所有**政府的国库户都在里面（哪怕今天没有授权）：它们是"只按授权下单"的家户，
      //     自动买卖单、市场信用放贷、家户外汇单全部不生成。
      //   ★★ 读**会话工作副本**（不是 base）：一次 advance 的多日共用同一个会话，授权在本段内被耗尽/清除必须
      //     当日就对后续各日生效（读 base 会把已清掉的行再挂一遍 —— 探针实测踩到并已修）。
      GovernmentMarketMandatePlan govMandatePlan =
          GovernmentMarketMandatePlan.of(
              ctx.session.sheet().govMarketMandatesOrBase(), ctx.dayView.governments(), ctx.day);
      // ★★ R2：口岸实际管制力随授权计划一起注入（各自逐字段带过 ⇒ 两个字段都在；withPortEnforcement 不覆盖 govMandates）。
      //   ★★ P-T1b：三层税的税率与收税政府同样在这里注入（`withPortTax` 逐字段带过前四个字段）——
      //     闸（E）与税（税率）是两个量，缺省各管各的，合成一个字段会让"只设了税"静默丢掉。
      ctx.marketRound =
          ctx.marketRound
              .withGovMandates(govMandatePlan)
              .withPortEnforcement(ctx.portEnforcement)
              .withPortTax(ctx.portTax)
              // ★★ P-T1d：政府采购优先级（谁要求管控市场 + 各自的行政力池）随授权计划一起注入 ——
              //   `withProcurementPriority` 逐字段带过前五个字段（丢了它 = 置顶整段不生效且毫无报错）。
              .withProcurementPriority(ctx.procurementPriority);
      // ★★ P-T1d 防复发守卫：与 govMandates / portTax 同一条（丢了它 = 政府要求管控却一户也置不了顶，契约故障）。
      //   契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (ctx.procurementPriority.isActive()
          && ctx.marketRound.procurementPriority() != ctx.procurementPriority) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "MARKET_PROCUREMENT_PRIORITY_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "reason",
                    "procurement-priority-input-lost-by-with-chain",
                    "expectedGovernments",
                    ctx.procurementPriority.governmentCount(),
                    "actualGovernments",
                    ctx.marketRound.procurementPriority().governmentCount()));
        throw new IllegalStateException("政府采购优先级的注入表在本轮装配里被丢掉了（置顶不会生效，契约故障）: day=" + ctx.day);
      }
      // ★★ P-T1b 防复发守卫：与 govMandates 同一条（丢了税 = 三层税一分不收且毫无报错）。
      //   契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (ctx.portTax.isActive() && ctx.marketRound.portTax() != ctx.portTax) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "MARKET_PORT_TAX_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "reason",
                    "port-tax-input-lost-by-with-chain",
                    "expectedZones",
                    ctx.portTax.zoneCount(),
                    "actualZones",
                    ctx.marketRound.portTax().zoneCount()));
        throw new IllegalStateException("三层税的注入表在本轮装配里被丢掉了（税会一分不收，契约故障）: day=" + ctx.day);
      }
      if (fxInput.isActive() && TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MARKET_FX_WINDOWS",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    ctx.day,
                    "windows",
                    fxInput.windows().size()));
      }
      // ★★ R1 防复发守卫：**本轮注入的授权计划必须真的在 round 上**。withDeferredPayments/withArbitrage/withFx 三处各自
      //   逐字段带过它（"克隆丢字段"是本类与 MarketRound 都踩过的坑：丢了它 = 国库户当场退回"自动清仓"且毫无报错）。
      //   ★ 契约/一致性故障 ⇒ ERROR + fail-closed（§一.9：不降级）；正常路径上恒不触发。
      if (!ctx.marketRound.govMandates().equals(govMandatePlan)) {
        EventLog.channel(MANDATE)
            .error(
                LogEvent.of(
                    "GOV_MARKET_MANDATE_CONTRACT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "reason",
                    "authorization-plan-lost-by-with-chain",
                    "expectedHouseholds",
                    govMandatePlan.authorizationOnlyHouseholds(),
                    "actualHouseholds",
                    ctx.marketRound.govMandates().authorizationOnlyHouseholds()));
        throw new IllegalStateException("政府市场授权计划在本轮装配里被丢掉了（国库户会退回自动下单，契约故障）: day=" + ctx.day);
      }
      // ★★ M-C：本轮的纯商号集合（H-2；免运费判据 H-A/H-G 的**范围**）—— 判据的唯一拼写点是
      //   {@code MerchantIdentity}，这里只把它从同一份 classMemberships × classPositions 现算一次。
      Set<HouseholdId> pureMerchantHouseholds =
          MerchantIdentity.pureMerchants(
              ctx.session.sheet().classMemberships(), ctx.dayView.classPositions());
      ctx.preparedPureMerchantHouseholds = pureMerchantHouseholds;
      // ★★★ W4（2026-10-11，S-4 / N-1）：**开盘前结算序快照** —— 家户按"资产 − 负债净值"、区按"上周期名义成交额"。
      //   ★ 位置（禁自指的全部理由）：它必须在 `clearOncePerCycle` **之前**构造一次 —— 快照一落，本轮撮合/改价/记账
      //     一个字节都进不了排序量（"把当日成交额翻倍 ⇒ 排序不变"由结构保证，不靠纪律）；
      //   ★ 区序的量取自 W1 统计簿的 `previousCycle`（上周期累计，关账轮轮转）→ 同值退 `cycleToDate` → 再退区 id；
      //     两者都在**盘前**读 ⇒ 不含当轮。★ 家户净值与 {@code ClassSlideSettlement} 的债务/资产**同一把尺**
      //     （同一份快照，不另算第二套），见 {@link SettlementOrderBook}。
      SettlementOrderBook ordering =
          SettlementOrderBook.snapshot(
              ctx.day,
              ctx.topology,
              ctx.markets,
              ctx.householdEconomies,
              ctx.debts,
              ctx.accounts,
              ctx.dayView);
      ctx.preparedSettlementOrdering = ordering;
    }
    return ctx.marketRound;
  }

  /**
   * ★★★ <b>P3.0：在自动订单之后追加 M3 的 MarketIntent，仍写回同一个 MarketRound。</b>
   *
   * <p>先调用无 intent 的 {@link #prepareMarketRound(SettlementDayContext)} 构造自动订单与全部市场前置状态，再按列表顺序
   * 逐条转换并追加。★ 本批<b>不清空、不替换</b> planner 已生成的订单；空列表 ⇒ 与无 intent 重载逐值相同。
   */
  static MarketSettlement.MarketRound prepareMarketRound(
      SettlementDayContext ctx, List<MarketIntent> intents) {
    Objects.requireNonNull(intents, "MarketIntent 列表不得为 null（没有意向请传 List.of()）");
    MarketSettlement.MarketRound round = prepareMarketRound(ctx);
    for (MarketIntent intent : intents) {
      appendMarketIntent(ctx, round, intent);
    }
    if (!intents.isEmpty() && MANDATE.isInfoEnabled()) {
      int commodityBuys = 0;
      int commoditySells = 0;
      int landBuys = 0;
      int landSells = 0;
      for (MarketIntent intent : intents) {
        if (intent.kind() == MarketIntentKind.COMMODITY) {
          if (intent.side() == MarketIntentSide.BUY) {
            commodityBuys++;
          } else {
            commoditySells++;
          }
        } else if (intent.side() == MarketIntentSide.BUY) {
          landBuys++;
        } else {
          landSells++;
        }
      }
      EventLog.channel(MANDATE)
          .info(
              LogEvent.of(
                  "MARKET_INTENTS_APPENDED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "intents",
                  intents.size(),
                  "commodityBuys",
                  commodityBuys,
                  "commoditySells",
                  commoditySells,
                  "landBuys",
                  landBuys,
                  "landSells",
                  landSells,
                  "reason",
                  "m3-intents-appended-to-the-same-market-round-without-replacing-planner-orders"));
    }
    return round;
  }

  /** 把一条冻结形状的 {@link MarketIntent} 转成现有订单或整块土地订单；只追加，不撮合、不落账。 */
  private static void appendMarketIntent(
      SettlementDayContext ctx, MarketSettlement.MarketRound round, MarketIntent intent) {
    Objects.requireNonNull(intent, "MarketIntent 列表不得含 null");
    round.addInjectedMarketIntent(intent);
    switch (intent.kind()) {
      case COMMODITY -> {
        CommodityId commodity =
            intent
                .commodity()
                .orElseThrow(
                    () -> new IllegalArgumentException("COMMODITY MarketIntent 必须带 commodity"));
        switch (intent.side()) {
          case BUY ->
              MarketSettlement.putCommodityBuyOrder(
                  round,
                  new BuyOrder(
                      intent.actor(),
                      intent.location(),
                      commodity,
                      intent.quantity(),
                      intent.limitPrice(),
                      ctx.day,
                      new Budget(intent.budgetMilli(), intent.payWith()),
                      intent.payWith()));
          case SELL ->
              MarketSettlement.putCommoditySellOrder(
                  round,
                  new SellOrder(
                      intent.actor(),
                      intent.location(),
                      commodity,
                      intent.quantity(),
                      intent.limitPrice(),
                      ctx.day,
                      intent.payWith()));
        }
      }
      case LAND -> {
        var landShare =
            intent
                .landShare()
                .orElseThrow(() -> new IllegalArgumentException("LAND MarketIntent 必须带 landShare"));
        MarketSettlement.putLandOrder(
            round,
            new LandOrder(
                intent.side() == MarketIntentSide.BUY ? LandOrder.Side.BUY : LandOrder.Side.SELL,
                landShare,
                intent.actor(),
                intent.location(),
                intent.quantity(),
                intent.limitPrice(),
                intent.payWith(),
                intent.budgetMilli()));
      }
    }
  }

  /**
   * ★★★ <b>P3.0：在已装配好的同一个 MarketRound 上执行唯一撮合入口。</b>
   *
   * <p>从旧 {@code settleMarket} 的 {@code MarketSettlement.clearOncePerCycle(...)} 起到市场后处理/读数写回逐行保留。
   * 传入的 {@code round} 必须由 {@link #prepareMarketRound(SettlementDayContext)} 或其 intent 重载构造； 本方法把它写回
   * {@code ctx.marketRound} 后只撮合一次。
   */
  static void executeMarketRound(
      SettlementDayContext ctx, MarketSettlement.MarketRound preparedRound) {
    Objects.requireNonNull(ctx, "executeMarketRound 的 ctx 不得为 null");
    Objects.requireNonNull(preparedRound, "executeMarketRound 的 round 不得为 null");
    ctx.marketRound = preparedRound;
    if (ctx.marketTrigger != MarketTrigger.NONE) {
      if (ctx.preparedPureMerchantHouseholds == null || ctx.preparedSettlementOrdering == null) {
        throw new IllegalStateException(
            "executeMarketRound 缺少 prepareMarketRound 产出的预撮合状态（纯商号集合/结算序快照）: day=" + ctx.day);
      }
      Set<HouseholdId> pureMerchantHouseholds = ctx.preparedPureMerchantHouseholds;
      SettlementOrderBook ordering = ctx.preparedSettlementOrdering;
      MarketSettlement.MarketOutcome outcome =
          MarketSettlement.clearOncePerCycle(
              ctx.markets,
              ctx.marketRound,
              ctx.marketTrigger,
              ctx.topology,
              ctx.parallelism,
              ctx.carrierPool,
              pureMerchantHouseholds,
              // ★★ W2：本轮的持久市场区表（自适应定价逐区读步长 α = MarketZone.priceAlphaPerMille）。
              //   ★ B2 保留段首 base：α 是命令面组件，段内无工作副本（改 α ⇒ 下一段/下一轮生效）。
              //   ★★ W5.5：货币市场的利率/α **不在区表上**，在价表上（逐市场持久，派生形态的世界也有它）——
              //     它随 {@code outcome.marketTables()} 交回、由上面那段既有的价表写回落到会话工作副本。
              ctx.base.marketZones(),
              // ★★ W4：结算序快照（盘前构造、不可变；撮合期间不回读任何当轮量）。
              ordering);
      // ★ L2 只把报告留给 L3 的读数组件（不落盘）；不聚合丢失（见 MarketReport 的类注）。
      ctx.ledger.recordMarketReport(outcome.report());
      ctx.govMandateFills = outcome.mandateFills();
      // ★★★ P2f：**市场买地 marker 接线** —— 本轮在同一个 MarketRound 里取得的土地块写进
      //   SettlementDayContext.landAcquiredThisRound（P3 的 M3/M6/M7 消费；不落盘/不进 ChangeSet/Codec）。
      //   与 M2 抵债取得的土地共用同一份事实（M2 在前、市场在后 ⇒ 保序去重）。
      for (AssetShareId landShare : ctx.marketRound.landAcquiredThisRound()) {
        ctx.markLandAcquired(landShare);
      }
      MarketReport report = outcome.report();
      // ── 4a0d'. ★★★ A5（2026-10-10）：**每个开市日一行可对账 INFO**（产出 / 交付 / 作废 三个量）──────────
      //   ★ 它取代了市场轮里那条只在 {@code trades > 0} 时才刷的 HAUL_SERVICE_SETTLED（A4 账本 §5-2：B 世界
      //     43 个有交付的日子只刷了 33 行、少报 91,383 毫服务 = 33.4%）—— 事件名不变，位置改到"三个量的最全来源"
      //     处（产出与作废都发生在生产阶段，市场轮看不到它们；交付量经 {@link MarketSettlement.HaulServiceRound}
      //     随交还物带出来）。
      //   ★ 一行不刷的条件**只剩一个**：本格连一个"服务成市的格"都没有（缺省 / 无牌价世界 ⇒ 日志逐字不变，I-H3）。
      //     当天三量全为 0 的日子照刷 —— "这一天什么都没发生"本身就是审计要能读到的事实（旧行为正是丢掉了它）。
      //   ★ 三个量必须能对上：Σ净产 − Σ交付 − Σ作废 == 期末服务货现货（交付那一份同时进
      //     {@code market-haul-service}、作废那一份进 {@code market-haul-service-expired}，各自账户减、账加）。
      if (!ctx.haulServiceMarketHexes.isEmpty() && MANDATE.isInfoEnabled()) {
        MarketSettlement.HaulServiceRound haulService = outcome.haulService();
        EventLog.channel(MANDATE)
            .info(
                LogEvent.of(
                    "HAUL_SERVICE_SETTLED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.day,
                    // ★ 产出/作废发生在生产阶段（本方法上半段），交付发生在市场轮 —— 三个量在同一行按日对齐。
                    "producedMilli",
                    ctx.haulServiceProducedMilli,
                    "deliveredMilli",
                    haulService.deliveredMilli(),
                    "expiredMilli",
                    ctx.haulServiceExpiredMilli,
                    // ★ trades = 铸了几条服务钱腿（免运费/自承运的交付不计）⇒ 与 deliveredMilli 是两个量，都在。
                    "trades",
                    haulService.trades(),
                    // ★ 逐币总额由逐户实收求和得到（同一批金额，不再另记一份；见 ctx 字段注）。
                    "paidByCurrency",
                    haulServicePaidByCurrency(haulService),
                    "providers",
                    ctx.carrierPool.householdCount(),
                    "deliveryFaults",
                    haulService.deliveryFaults(),
                    // ★ 缺省/无牌价世界一行不打（I-H3）；本行是"当日的三量对账行"，不是"成交事件行"。
                    "reason",
                    "haul-service-day-reconciliation-produced-delivered-expired"));
      }
      Map<MarketUnfilledReason, Long> reasonCounts = new TreeMap<>();
      // ★★ F-2（2026-10-10）：**"卡在运力"这一档的具名细分**（键 = MerchantCapacityPool.LogisticsBlock 的字面量）——
      //   改前"没有服务卖"与"运力不足"在读数里同为 LOGISTICS_CAPACITY；这里按**具名档**分列未成交量，
      //   让两者在公开读数面（本条 INFO/TRACE 的 MARKET 汇总）可分辨（§一.9：业务拒绝本该 INFO 可读）。
      //   ★ 保序 = LinkedHashMap（I7：键序是内容的纯函数，禁 Map.copyOf/无序表）。
      Map<String, Long> logisticsDetailCounts = new LinkedHashMap<>();
      for (MarketReport.Unfilled unfilled : report.unfilled()) {
        reasonCounts.merge(unfilled.reason(), 1L, Long::sum);
        if (unfilled.reason() == MarketUnfilledReason.LOGISTICS_CAPACITY
            && unfilled.logisticsDetail() != null) {
          logisticsDetailCounts.merge(unfilled.logisticsDetail(), unfilled.quantity(), Long::sum);
        }
      }
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "MARKET",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.day,
                  "trigger",
                  report.trigger(),
                  "fills",
                  report.fills().size(),
                  "immediateCrossHex",
                  report.immediateCrossHexFills(),
                  "immediateCrossHexLossMilli",
                  report.immediateCrossHexLossMilli(),
                  "scheduledLossMilli",
                  report.scheduledLossMilli(),
                  "unfilled",
                  report.unfilled().size(),
                  "reasons",
                  reasonCounts,
                  // ★★ F-2：LOGISTICS_CAPACITY 的具名细分（空表 = 本轮没有卡在运力的未成交；缺省中性）。
                  "logisticsDetailQuantities",
                  logisticsDetailCounts,
                  "regulatedTariffByCurrency",
                  report.regulatedTariffByCurrency(),
                  // ★★ P-T4：运费读数按币分列（禁跨币相加）—— 一并进"这一轮发生了什么"。
                  "freightPaidByCurrency",
                  report.freightPaidByCurrency(),
                  "freightUncollectedByCurrency",
                  report.freightUncollectedByCurrency(),
                  // ★★ P-T1b：三层税的真收总额（按币分列；= 买方多付的那一部分）。
                  "taxByCurrency",
                  report.taxByCurrency()));
      // ★★ P-T1b（§一.9 的 INFO 档）：**当日分层的税汇总** —— 每一行 = 一个（层 × 收款政府 × 币种）的实收金额。
      //   ★ 只在真的收了税时发（缺省 0 ⇒ 一行都不发，旧世界日志逐字不变）；禁跨币求和（I-C10）由键的第三段保证。
      logTaxCollected(ctx.day, report);
      if (RAW.isTraceEnabled()) {
        for (MarketReport.Fill fill : report.fills()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_FILL",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.day,
                      "from",
                      fill.from().q() + "," + fill.from().r(),
                      "to",
                      fill.to().q() + "," + fill.to().r(),
                      "commodity",
                      fill.commodity().value(),
                      "seller",
                      fill.seller().id(),
                      "buyer",
                      fill.buyer().id(),
                      "quantity",
                      fill.quantity(),
                      // ★★ P-T4：每一栏钱都带币种（读口不混币）—— 异币成交时"单价"与"实付"是两种钱。
                      "unitCurrency",
                      fill.unitCurrency().value(),
                      "unitPriceMilli",
                      fill.unitPriceMilli(),
                      "paymentCurrency",
                      fill.paymentCurrency().value(),
                      "goodsPaymentMilli",
                      fill.goodsPaymentMilli(),
                      "freightCurrency",
                      fill.freightCurrency().value(),
                      "freightPerUnitMilli",
                      fill.freightPerUnitMilli(),
                      "freightMilli",
                      fill.freightMilli(),
                      "immediate",
                      fill.immediate(),
                      "arrivalTick",
                      fill.arrivalTick(),
                      "shipmentId",
                      fill.shipmentId(),
                      "lossMilli",
                      fill.lossMilli()));
        }
        for (MarketReport.Unfilled unfilled : report.unfilled()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_UNFILLED",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.day,
                      "side",
                      unfilled.buyerSide() ? "buy" : "sell",
                      "actor",
                      unfilled.actor().id(),
                      "commodity",
                      unfilled.commodity().value(),
                      "quantity",
                      unfilled.quantity(),
                      "reason",
                      unfilled.reason(),
                      "hex",
                      unfilled.hex().q() + "," + unfilled.hex().r()));
        }
        for (MarketReport.SellerOutcome seller : report.sellerOutcomes()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_SELLER_OUTCOME",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.day,
                      "actor",
                      seller.actor().id(),
                      "unit",
                      seller.unitId().map(ProductionUnitId::value).orElse("-"),
                      "hex",
                      seller.hex().q() + "," + seller.hex().r(),
                      "commodity",
                      seller.commodity().value(),
                      "offered",
                      seller.offeredQty(),
                      "filled",
                      seller.filledQty(),
                      "unfilled",
                      seller.unfilledQty(),
                      "unitPriceMilli",
                      seller.unitPriceMilli(),
                      "unitCostEstimateMilli",
                      seller.unitCostEstimateMilli(),
                      "freightPerUnitMilli",
                      seller.freightPerUnitMilli(),
                      "bestAcceptedLandedPriceMilli",
                      seller.bestAcceptedLandedPriceMilli(),
                      "costRank",
                      seller.costRank(),
                      "reason",
                      seller.unfilledReason().map(Enum::name).orElse("-"),
                      "outcompetedByActors",
                      seller.outcompetedByActorCount(),
                      "outcompetedQty",
                      seller.outcompetedQty(),
                      "priceMissing",
                      seller.priceMissing(),
                      "costKnown",
                      seller.costKnown()));
        }
        for (MarketReport.BuyerOutcome buyer : report.buyerOutcomes()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "MARKET_BUYER_OUTCOME",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.day,
                      "actor",
                      buyer.actor().id(),
                      "household",
                      buyer.household().map(HouseholdId::value).orElse("-"),
                      "hex",
                      buyer.hex().q() + "," + buyer.hex().r(),
                      "commodity",
                      buyer.commodity().value(),
                      "stockOnHandMilli",
                      buyer.stockOnHandMilli(),
                      "stockCoverDays",
                      buyer.stockCoverDays(),
                      "gapQty",
                      buyer.gapQty(),
                      "desiredQty",
                      buyer.desiredQty(),
                      "spendableMoneyMilli",
                      buyer.spendableMoneyMilli(),
                      "affordableQty",
                      buyer.affordableQty(),
                      "orderedQty",
                      buyer.orderedQty(),
                      "filledQty",
                      buyer.filledQty(),
                      "reason",
                      buyer.unfilledReason().map(Enum::name).orElse("-")));
        }
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MARKET_REPORT",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "fills",
                    report.fills().size(),
                    "immediateCrossHex",
                    report.immediateCrossHexFills(),
                    "immediateCrossHexLossMilli",
                    report.immediateCrossHexLossMilli(),
                    "scheduledLossMilli",
                    report.scheduledLossMilli(),
                    "unfilled",
                    report.unfilled().size(),
                    "reasons",
                    reasonCounts,
                    "regulatedTariffByCurrency",
                    report.regulatedTariffByCurrency(),
                    "freightPaidByCurrency",
                    report.freightPaidByCurrency(),
                    "freightUncollectedByCurrency",
                    report.freightUncollectedByCurrency()));
      }
      // ★★ S3 修复：把本轮的逐卖方证据累加进经营者条件的"本周期累计"字段。一个周期有多轮市场，关账日那轮
      //   很可能已经看不到更早轮里的滞销/被挤出 ⇒ 不在这里累加，状态机的连续计数就永远不涨。
      //   ★★★ A5（T-H3）：服务成交的钱腿也走这个口（服务不进订单簿 ⇒ 它不在 report.fills() 里）——
      //   见 {@code OperatorSettlement.accumulateHaulServiceRevenue}。
      OperatorSettlement.accumulateMarketEvidence(
          ctx.operatorConditions,
          ctx.units,
          ctx.industries,
          outcome.report(),
          outcome.haulService());
      // ★★ M2.6：自适应模式把价格表工作副本换成 outcome 交回的新表（默认固定模式下两者逐值相同 ⇒ 无状态变化）。
      //   ★★ 2026-10-07 修：`markets` 是 `session.sheet().marketTables()` 的**工作副本引用**，不能整个重新绑定 ——
      //     重新绑定只改本地变量，`EconomyStateBuilder` 仍持旧表 ⇒ 自适应价算出来却永远不落盘。
      //     `outcome.marketTables()` 在固定模式下就是同一实例；只有真的换了实例才 clear+putAll 回工作副本。
      Map<MarketZoneId, MarketTable> updatedMarkets = outcome.marketTables();
      if (updatedMarkets != ctx.markets) {
        ctx.markets.clear();
        ctx.markets.putAll(updatedMarkets);
      }
      // ★★★ P2f：**轮末写回土地价**（唯一落点；键 = 市场区，同一区一个价）。地价由 MarketMatcher.matchAll
      //   内部土地分支用 MarketPricer.adaptiveNextPrice 的同一算式算好放进 MarketRound.nextLandPrices；这里只把它落到价目表的
      //   withLandPrice(...)。只对原本 hasLandPrice() 的市场写 —— empty 市场不生成、不改价、不当免费。
      Map<MarketZoneId, Long> nextLandPrices = ctx.marketRound.nextLandPrices();
      if (!nextLandPrices.isEmpty()) {
        long landPriceUpdates = 0L;
        for (Map.Entry<MarketZoneId, Long> entry : nextLandPrices.entrySet()) {
          MarketTable table = ctx.markets.get(entry.getKey());
          if (table == null || !table.hasLandPrice()) {
            continue; // 契约防御：empty 市场不得被"补"出一个价（不改价是明确的语义）
          }
          ctx.markets.put(entry.getKey(), table.withLandPrice(entry.getValue()));
          landPriceUpdates++;
        }
        if (landPriceUpdates > 0L && MANDATE.isInfoEnabled()) {
          EventLog.channel(MANDATE)
              .info(
                  LogEvent.of(
                      "MARKET_LAND_PRICE_WRITEBACK",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      ctx.day,
                      "markets",
                      landPriceUpdates,
                      "nextLandPrices",
                      nextLandPrices,
                      "reason",
                      "land-price-lands-on-the-next-round-market-table"));
        }
      }
      // ★★ W1（2026-10-10）：**市场轮统计簿**——每轮把本轮读数折进状态（最近一轮明细 + 本周期累计 + 上周期累计）。
      //   ★ 窗口固定（三段覆盖写 + 关账轮轮转）⇒ 状态大小与推进天数无关（性能红线：Revision Δ 不随天数增长；
      //     本写入在**同一次 advance 的工作副本**里，不产生额外 revision）。
      //   ★ 关账轮的语义：本轮先计入"本周期累计"，若本轮同时是关账轮 ⇒ 再把本周期轮转成上周期（关账发生在市场轮之后）。
      if (!outcome.roundStats().isEmpty()) {
        Map<MarketZoneId, MarketRoundStats> statsByMarket = ctx.session.sheet().marketRoundStats();
        long recorded = 0L;
        long tradedTotal = 0L;
        long turnoverTotal = 0L;
        long cashTurnoverTotal = 0L;
        for (Map.Entry<MarketZoneId, MarketRoundStats> entry : outcome.roundStats().entrySet()) {
          MarketRoundStats round = entry.getValue();
          MarketRoundStats current =
              statsByMarket.getOrDefault(entry.getKey(), MarketRoundStats.EMPTY);
          // ★★ W2-fix7 / D4（v3.10 §7）：两栏都写 —— `turnover` = 名义货值（全额货款，含赊购）、
          //   `cashTurnover` = 实付现金（改前 `turnover` 的口径，逐值搬到新字段）。
          statsByMarket.put(
              entry.getKey(),
              current.withRound(
                  ctx.day,
                  round.lastRound(),
                  round.lastRoundTurnoverMilli(),
                  round.lastRoundCashTurnoverMilli(),
                  ctx.anyCycleClosed));
          recorded++;
          turnoverTotal = Math.addExact(turnoverTotal, round.lastRoundTurnoverMilli());
          cashTurnoverTotal = Math.addExact(cashTurnoverTotal, round.lastRoundCashTurnoverMilli());
          for (CommodityRound commodityRound : round.lastRound().values()) {
            tradedTotal = Math.addExact(tradedTotal, commodityRound.tradedMilli());
          }
        }
        EventLog.channel(MANDATE)
            .info(
                LogEvent.of(
                    "MARKET_ROUND_STATS_RECORDED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "markets",
                    recorded,
                    "commodities",
                    outcome.roundStats().values().stream()
                        .mapToInt(stats -> stats.lastRound().size())
                        .sum(),
                    "tradedMilli",
                    tradedTotal,
                    // ★ D4：两栏都给（名义货值 / 实付现金）—— 只打一栏会让读者以为"成交额 = 现金"
                    "turnoverMilli",
                    turnoverTotal,
                    "cashTurnoverMilli",
                    cashTurnoverTotal,
                    "cycleClosed",
                    ctx.anyCycleClosed,
                    "window",
                    "last-round+cycle-to-date+previous-cycle"));
        if (MANDATE.isDebugEnabled()) {
          // ★★ W1 收尾批（2026-10-11）：这一行打印的必须是**刚落盘的那份统计**（上文 `statsByMarket` 里
          //   `current.withRound(...)` 的返回值），**不是**本轮入参 `outcome.roundStats()` —— 入参只带本轮读数
          //   （`lastRound`），它的 `cycleToDate` / `previousCycle` 必然为空 ⇒ 旧写法让 `describe()` 的
          //   "本周期/上周期"两栏**恒 0**，与同 tick 落盘的 `marketRoundStats` 直接矛盾（验收报告 §7.2：
          //   day=360 的 DETAIL 写 `上周期商品=0`，而同 tick 的落盘原文里 `previousCycle` 有 5 商品、
          //   `previousCycleTurnoverMilli=5601`）—— 那是"字段名与所指对象不一致"的误导，读者会以为窗口是空的。
          //   ★ 只改**打印对象**：状态、公式、写口一字未动，级别仍是 DEBUG。
          for (Map.Entry<MarketZoneId, MarketRoundStats> entry : outcome.roundStats().entrySet()) {
            MarketRoundStats stored = statsByMarket.get(entry.getKey());
            EventLog.channel(MANDATE)
                .debug(
                    LogEvent.of(
                        "MARKET_ROUND_STATS_DETAIL",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        ctx.day,
                        "marketId",
                        entry.getKey().value(),
                        "stats",
                        // 写入就在上面同一个块里完成 ⇒ 正常情况下恒非空；真取不到也只如实写"未写入"，
                        // 不回落到本轮入参（那正是这条日志原先会误导人的地方）。
                        stored == null ? "未写入" : stored.describe()));
          }
        }
      }
      // ── ★★★ W4（2026-10-11，S-1）：**阶层滑落** —— 速度 = f(债务/资产)，结算点 = **市场轮最末** ────────────
      //   ★ 位置为什么是"这里"（用户原话「这个速度的结算必须在当前经济操作搞完之后最后再结算…当轮算出这个家户的
      //     债务规模后进行」）：上面这一段已经跑完 ① 撮合（本轮赊购 ⇒ 本轮的 debtContracts 已建）② 自适应价格写回
      //     ③ 统计簿落盘 ⇒ "当轮债务规模"与"本轮价"都已是事实；而 4b 借粮 / 4c 偿还 / 计息都在**下面** ——
      //     滑落读的是"市场轮结束时点"的债务/资产，不掺当天后续的借还（口径唯一、可复现）。
      //   ★ 逐级不跳级：{@link ClassSlideSettlement} 每次调用对同一户只写一次、只写一个"最近严格低位"。
      ClassSlideSettlement.SlideRound slideRound =
          ClassSlideSettlement.settleAtMarketRoundEnd(
              ctx.base,
              ctx.session.sheet(),
              ctx.day,
              ctx.householdEconomies,
              ctx.debts,
              ctx.accounts,
              ctx.topology,
              ctx.markets);
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CLASS_SLIDE_SETTLED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "trigger",
                    ctx.marketTrigger,
                    "evaluated",
                    slideRound.evaluated(),
                    "slid",
                    slideRound.slid(),
                    "skippedNoDebt",
                    slideRound.skippedNoDebt(),
                    "skippedNoPosition",
                    slideRound.skippedNoPosition(),
                    "skippedCooldown",
                    slideRound.skippedCooldown(),
                    "skippedNoLowerTarget",
                    slideRound.skippedNoLowerTarget(),
                    "unpricedDebtHouseholds",
                    slideRound.unpricedDebtHouseholds(),
                    "at",
                    "market-round-end-after-price-writeback"));
      }
    }
  }
}
