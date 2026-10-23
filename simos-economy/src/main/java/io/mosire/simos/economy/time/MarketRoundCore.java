package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketSettlement.HouseholdMarketRole;
import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
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
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.market.BuyOrder;
import io.mosire.simos.economy.api.market.MarketOrder;
import io.mosire.simos.economy.api.market.SellOrder;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>一个市场轮次的只读会话视图</b>（包内可见的施工坞；类注见 {@link MarketSettlement}）。
 *
 * <p>★ <b>为什么不是 record</b>（形态由实现裁，理由记在这里）：它装的是**就地可变的会话副本**（{@code applyTransfer} 要写它们）—— 用 record
 * 的访问器交出去会触发 {@code EI_EXPOSE_REP}，包成不可变视图又写不动。⇒ 按 {@code InputPlan} 的先例用 "final 字段 +
 * 包内可见构造器"的内部类；本类只在包内流转，不进任何持久状态。
 */
class MarketRoundCore {

  final long day;
  final Map<HouseholdId, HouseholdEconomy> householdEconomies;
  final Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
  final Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
  final Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods;
  final Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney;
  final Map<HouseholdId, Map<CommodityId, Long>> unmetToday;
  final Map<ActorRef, HouseholdId> householdOfActor;
  final Map<IndustryId, IndustryDefinitionView> industries;

  /** ★★ R3B.2：生产单元表（键 = unit id；参与者/必要投入/卖单归属都读它）。 */
  final Map<ProductionUnitId, ProductionProcess> units;

  /** ★★ R3B.2：实物总账（只读；必要投入的规模从它纯派生 —— 它是唯一的 quantity 真相）。 */
  final Map<AssetShareId, OwnershipStake> assetShares;

  final Map<ProductionUnitId, ProductionRules> relations;
  final Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments;
  final Map<ShipmentId, ShipmentBatch> shipments;
  final ProductionLedger.Accumulator ledger;

  /** ★ S3：经营者状态（只读；市场的原因归因与"真无法再生产"判据读它，不写它；R3B.2 起键 = unit id）。 */
  final Map<ProductionUnitId, OperatorCondition> operatorConditions;

  /** ★★ R4-B.3a-perf：本轮参与者/必要投入/自留/家户归属共用的只读派生索引（入口构建一次）。 */
  final SettlementIndex index;

  /**
   * ★★ R4-E2：需求账本（只读；由 {@code EconomySettlement}/{@code MarketReadout} 从 {@code
   * EconomyData.demands()} 传入）。订单路径用它把"生活保留基线 + 有效需求目标"合成买/不卖目标。
   */
  final Map<DemandId, HouseholdDemand> householdDemands;

  /**
   * ★★ <b>本轮区内市场规则</b>（逐轮瞬态，不落盘）—— P-T1c 起只剩"区内税"：{@link MarketRegulation#anchor()} 所在的区按它
   * 施加税费；其余区不受影响（单区世界里就是全区）。★ 不给 ⇒ 语义为 {@link MarketRegulation#none()}（无税费，逐值现状）。
   */
  final MarketRegulation regulation;

  /**
   * ★★ <b>W2：本轮"未付价款记债"的入参</b>（{@code null} = 本入口没有记债能力 ⇒ 逐值退回"现金封顶"的旧口径）； 只带<b>到期周期</b>（市场信用撮合已随
   * W2-fix4 整段退役，见 {@link MarketRound#DeferredPaymentConfig}）。
   */
  final DeferredPaymentConfig deferredPaymentConfig;

  /**
   * ★★ <b>W2：债务工作表</b>（与 {@code EconomySettlement} 的当日工作副本同一个 map；未付价款合同经 {@link
   * DebtContractBook#upsert} 写入，市场不另造债务表）。{@code null} = 信用关闭。
   */
  final Map<DebtContractId, DebtContract> debts;

  /**
   * ★★ <b>Z7b 的单位户集合</b>（保序不可变；由 {@code simos-app} 组合根从 {@code Unit.households()} 算好传入，economy
   * 编译期看不见 unit 切片）。
   *
   * <p>★★ <b>控制方裁定 R2（2026-10-11）起它<b>不再是"退出商品市场"</b>，而是"<b>只买不卖</b>"名单</b>：军户/官吏户按普通家户
   * 参与市场<b>买盘</b>（它们的钱来自俸禄/军俸，买口粮是<b>家户消费</b>，不是政策层/补贴层），但它们的实物供给仍不得被市场当余量 卖掉（Z7b
   * 的卖出侧原口径不变）。政府国库户（{@code hh-gov-*}）另按 {@link #govMandates()} 走"只按授权下单"。
   *
   * <p>★ <b>唯一判据</b>：本字段只允许经 {@link #householdMarketRole(HouseholdId)} 消费 —— 那一处同时回答"是不是单位户"与
   * "是不是国库户"（后者优先，fail-closed）。直接 {@code contains} 就是在拼第二份口径。
   *
   * <p>★ 本集合只影响<b>市场订单/参与</b>：税、预算、转移照常动账，账户与冻结语义一个字不改。
   */
  final Set<HouseholdId> marketExcludedHouseholds;

  /**
   * ★★ <b>2026-10-08（阶段 1）：本轮的套利决定</b>（§4.3.4/§4.3.5；由 {@code LaborQueueSettlement} 排序产出、 经 {@link
   * #withArbitrage} 注入）。默认 {@link MarketArbitragePlan#empty()} ⇒ <b>逐值退回改前行为</b>
   * （旧构造器、只读计划轮与读口都不注入它）。
   *
   * <p>★ 它是<b>逐轮瞬态</b>：不进 {@code EconomyData}、不进变更集、不落盘；订单仍由 {@code ordersFor} 生成、 仍由 {@code
   * clearOncePerCycle} 撮合、仍由唯一写口落账（本字段只回答"这个家户这一轮想额外吃多少货"）。
   */
  MarketArbitragePlan arbitrage = MarketArbitragePlan.empty();

  /**
   * ★★★ <b>P2f/P3d：本轮的土地订单</b>（与商品 {@code ctx.buys}/{@code ctx.sells} 同属<b>一个</b> {@link
   * MarketRound} 订单簿）。
   *
   * <p>★ <b>可变的逐轮表</b>（默认空）：P3d 的生产侧土地买卖意向经 {@link MarketOrderPlanner#putLandOrder} 写入；
   * 本类只提供注入/读取口，不伪造生产需求。★ 它<b>不落盘</b>、不进 ChangeSet/Codec。
   *
   * <p>★★ <b>公共入口/生命周期只有一个</b>：{@code MarketRoundRunner.clearOncePerCycle} 只调 {@code
   * MarketMatcher.matchAll} 一次；商品与土地是同一本订单簿里的订单变体（保留内部 typed slot 只是最小改动， 不是独立市场阶段）。{@code
   * MarketMatcher.matchLand} 只是 {@code matchAll} 内部的私有土地分支。
   */
  final List<LandOrder> landOrders = new ArrayList<>();

  /**
   * ★★★ <b>P3.0：M3 显式商品意向转换后的买单</b>（与 planner 自动订单<b>并存</b>，不清空/不替换）。
   *
   * <p>★ 它仍是同一个 {@link MarketRound} 的逐轮瞬态订单簿：撮合入口不变，依旧只有 {@code clearOncePerCycle}。P3.0 只追加；P3c
   * 才决定与自动订单的替换/优先级策略。
   */
  final List<BuyOrder> injectedBuyOrders = new ArrayList<>();

  /** ★★★ <b>P3.0：M3 显式商品意向转换后的卖单</b>（与 {@link #injectedBuyOrders} 同一条纪律）。 */
  final List<SellOrder> injectedSellOrders = new ArrayList<>();

  /**
   * ★★★ <b>D-032 C7b1：本轮最终商品订单快照（撮合前捕获；瞬态，不落盘、不进 ChangeSet/Codec）。</b>
   *
   * <p>自动订单由 {@code MarketRoundRunner} 在内部生成，显式订单由 {@link #injectedBuyOrders} / {@link
   * #injectedSellOrders} 追加；C7b1 的运输计划必须在真正撮合之前看到<b>两者合并后的最终订单</b>。 {@link #planningBuyOrders()} /
   * {@link #planningSellOrders()} 在未捕获时回落到显式订单，保证只读计划轮与 夹具仍可用同一条派生入口。
   */
  private List<BuyOrder> planningBuyOrders = List.of();

  private List<SellOrder> planningSellOrders = List.of();

  private boolean planningOrdersCaptured;

  /** C7b1：由市场轮 runner 在订单构建完成、真正撮合之前调用一次；只捕获只读订单引用。 */
  void capturePlanningOrders(List<BuyOrder> buys, List<SellOrder> sells) {
    planningBuyOrders =
        Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(buys, "planning buys 不得为 null")));
    planningSellOrders =
        Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(sells, "planning sells 不得为 null")));
    planningOrdersCaptured = true;
  }

  /** C7b1：当轮最终买单快照；未捕获时回落到显式注入订单。 */
  List<BuyOrder> planningBuyOrders() {
    return planningOrdersCaptured
        ? planningBuyOrders
        : Collections.unmodifiableList(injectedBuyOrders);
  }

  /** C7b1：当轮最终卖单快照；未捕获时回落到显式注入订单。 */
  List<SellOrder> planningSellOrders() {
    return planningOrdersCaptured
        ? planningSellOrders
        : Collections.unmodifiableList(injectedSellOrders);
  }

  /**
   * ★★★ <b>RC8c：本户商品键由 S4 {@link DailyDemandBill} 权威覆盖的集合</b>（瞬态；不落盘、不进 ChangeSet/Codec）。
   *
   * <p>★ 语义：只要某户某商品在这里，planner 的自动商品订单（BUY/SELL）就不再为该 {@code (actor, commodity)} 生成 —— 该键的
   * 数量必须来自账单派生意向。没在这里的键仍走既有 planner 自动路径（政府授权、经营者/商号、纯状态读口等一个字节不动）。
   *
   * <p>★ 只活在本轮；{@link #inheritLandStateFrom(MarketRoundCore)} 逐值带过（withX 克隆与 worker 副本都不会丢）。
   */
  final Map<HouseholdId, Set<CommodityId>> billCoveredCommodities = new LinkedHashMap<>();

  /** RC8c：登记"该户该商品由账单权威覆盖"。 */
  void markBillCoveredCommodity(HouseholdId household, CommodityId commodity) {
    Objects.requireNonNull(household, "billCoveredCommodities.household");
    Objects.requireNonNull(commodity, "billCoveredCommodities.commodity");
    billCoveredCommodities
        .computeIfAbsent(household, ignored -> new LinkedHashSet<>())
        .add(commodity);
  }

  /** RC8c：该户该商品的自动商品订单是否应让位给账单派生意向。 */
  boolean billCoversCommodity(HouseholdId household, CommodityId commodity) {
    if (household == null || commodity == null) {
      return false;
    }
    Set<CommodityId> covered = billCoveredCommodities.get(household);
    return covered != null && covered.contains(commodity);
  }

  /**
   * ★★★ <b>D-032 U2b：本轮注入的统一 {@link MarketOrder} 唯一列表</b>（保序只读保存）。
   *
   * <p>★ 生产侧 {@link MarketIntent} 经 {@code MarketOrderBridge.fromIntent} 包装后只进入本表；typed matcher 输入
   * {@link #injectedBuyOrders}/{@link #injectedSellOrders}/{@link #landOrders} 是提交前的派生暂存，不是第二份注入语义。
   * {@code priority} 等字段随统一订单一起保存，供替换策略与日志使用。
   */
  final List<MarketOrder> injectedMarketOrders = new ArrayList<>();

  /**
   * ★★ <b>旧整块土地取得标记（历史兼容；RA1b2c2 起市场与债务都不再写它）</b>。
   *
   * <p>★ 保序去重（{@link LinkedHashSet}），只活在本轮；保留读取口以免旧调用面编译断裂。★ <b>不落盘、不进 ChangeSet/Codec</b>。★
   * 新的唯一生产者/消费者是 {@link #landAcquiredQuantityThisRound}（按数量扣减）。
   */
  final LinkedHashSet<AssetShareId> landAcquiredThisRound = new LinkedHashSet<>();

  /**
   * ★★★ <b>D-032 RA1b2a/RA1b2c2：本轮按 {@code (hex, owner)} 实际取得的土地数量</b>（唯一取得事实）。
   *
   * <p>★ 只活在本轮；数量按 key 累加，支持"部分取得"（原持有 100、本轮取得 30 ⇒ 可用 70）。M2 抵债与 M4 市场买地都写它； {@code
   * LandProductionProjection} / {@code SettlementIndex} 按数量扣减，不整 key 排除。★ <b>不落盘、不进
   * ChangeSet/Codec</b>。
   */
  final LinkedHashMap<LandHoldingKey, Long> landAcquiredQuantityThisRound = new LinkedHashMap<>();

  /**
   * ★★★ <b>D-032 RA1b2c2：本轮标量 LAND 持有工作副本</b>（键 = {@code (hex, owner)}；值 = 正数量持有）。
   *
   * <p>★ 由 {@code MarketRoundRunner} 在开盘时从会话 {@code landHoldings} 注入；土地成交经 {@code
   * LandLedgerState.transfer} 更新工作态后由 runner 写回本表。轮末由 {@code SettlementDayMarketPipeline} 写回 {@code
   * EconomyStateBuilder.landHoldings()}。★ <b>只活在本轮；不落盘、不进 ChangeSet/Codec</b>。
   */
  final LinkedHashMap<LandHoldingKey, LandHolding> landHoldings = new LinkedHashMap<>();

  /**
   * ★★ <b>P2f：下一轮的土地价</b>（键 = 市场区；值 = 毫计价货币 / 整亩）。
   *
   * <p>★ 由 {@link MarketMatcher#matchAll} 内部的土地私有分支用 {@link MarketPricer#adaptiveNextPrice}
   * 的同一口径算出；<b>轮末</b>由 {@code SettlementDayMarketPipeline} 写回对应 {@link
   * io.mosire.simos.economy.model.MarketTable#withLandPrice(long)}。★ 只对原本 {@code hasLandPrice()}
   * 的市场写；empty 市场不生成、不改价、不当免费。
   */
  final Map<MarketZoneId, Long> nextLandPrices = new LinkedHashMap<>();

  /**
   * ★★ <b>R1（2026-10-09）：本日的政府市场授权计划</b>（由 {@code EconomySettlement}/{@code MarketReadout} 从 {@code
   * EconomyData.govMarketMandates()} + {@code governments()} 现算后经 {@link #withGovMandates} 注入）。
   *
   * <p>★★ <b>它做了两件事</b>：① 点名单上的国库户（{@code hh-gov-*}）是"<b>只按授权下单</b>"—— 自动买卖单、市场信用放贷、
   * 家户外汇单<b>全部不生成</b>（国库户持有税收实收的粮/银且 0 人口 ⇒ 自动卖单会把它清仓，这正是 Z7b 的根因）； ② 给出它们今天真正生效的挂单（谁/商品/方向/量/限价）。
   *
   * <p>★ 默认 {@link GovernmentMarketMandatePlan#empty()} ⇒ <b>逐值退回改前行为</b>（没有政府的世界、旧构造器与不走 {@code
   * withGovMandates} 的调用点都不受影响）。
   */
  GovernmentMarketMandatePlan govMandates = GovernmentMarketMandatePlan.empty();

  /**
   * ★★ <b>R2（2026-10-09 口岸设计书 §4.4）：本轮逐区逐币种的实际管制力</b>（组合根折算后经 {@link #withPortEnforcement} 注入；由
   * {@code CurrencyValuation} 作为"家户对外币估值的减项"消费）。
   *
   * <p>★ 默认 {@link PortEnforcementInput#none()} ⇒ <b>逐值退回改前行为</b>（没有口岸政策的世界一个数都不动，I-P8）。 ★ 逐轮瞬态：不进
   * {@code EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code govMandates}
   * 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
   */
  PortEnforcementInput portEnforcement = PortEnforcementInput.none();

  /**
   * ★★ <b>P-T1b（2026-10-10 口岸设计书 §13）：本轮逐区的<b>税率</b>与收税政府</b>（组合根折算后经 {@link #withPortTax} 注入）——
   * 出口税（源区）/ 进口税（目的区）/ 区内市场税（本区）三层真收款的<b>税率来源</b>。
   *
   * <p>★★ <b>与 {@link #portEnforcement} 分开的原因</b>：闸（E，能不能过）与税（收多少）是两个量，缺省也各管各的 ——
   * 只设税率而两侧全开的世界<b>能过但要多付钱</b>；只设限制而不设税的世界<b>过不去但不加价</b>。合成一个字段会让 "只设了税"（管制力全 0 ⇒ 那个字段看上去是缺省）静默丢掉税。
   *
   * <p>★ 默认 {@link PortTaxInput#none()} ⇒ <b>逐值退回改前行为</b>（未设税 ⇒ 一个数都不动，I-C2）。 ★ 逐轮瞬态：不进 {@code
   * EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code govMandates}/{@code
   * portEnforcement} 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
   */
  PortTaxInput portTax = PortTaxInput.none();

  /**
   * ★★ <b>P-T1d（2026-10-10 口岸设计书 §16.3/§17）：本轮的政府采购优先级</b>（谁要求管控市场 + 各自的行政力池；组合根折算后经 {@link
   * #withProcurementPriority} 注入）。
   *
   * <p>★ 它只在<b>市场轮的撮合顺序</b>上兑现：要求管控市场的政府（表里有它的国库户 {@code hh-gov-<govUnitId>}），
   * 其挂单在行政力池余量内被移到挂单簿<b>最前</b>（最先卖 / 最先买），每超越一户扣一份、见底硬停；<b>只改顺序、不改价格与量规则</b> （唯一拼写点 = {@code
   * ProcurementPriorityOrder}）。
   *
   * <p>★ 默认 {@link ProcurementPriorityInput#none()} ⇒ <b>逐值退回改前行为</b>（没有政府要求管控 ⇒ 一个数都不动，I-C2）。 ★
   * 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘。★ 与 {@code arbitrage}/{@code fx}/{@code
   * govMandates}/{@code portEnforcement}/{@code portTax} 同一条"克隆必须逐字段带过"的纪律（本类踩过三次的那个坑）。
   */
  ProcurementPriorityInput procurementPriority = ProcurementPriorityInput.none();

  // ★★★ A3（2026-10-10）：本轮"跑商家户"集合字段（§16.4 ①，提交 b22da5b7）**已删** —— 它只服务
  //   {@code necessaryInputsOf} 尾部那段"给跑商家户下夹一趟工具"的特例，而该特例已撤回（理由见那个方法尾部）。
  //   ★ 用户诉求（「难到家户不会给预估生产方式预留生产资料吗？」）现由**产业声明投入**的标准保留循环满足（I-H6）。

  /**
   * ★★ <b>2026-10-08（阶段 2-A2a）：本轮的外汇入参</b>（官方汇率 + 窗口储备上限；由 {@code EconomySettlement} 从 {@code
   * EconomyData.governments()} 装配后经 {@link #withFx} 注入；★ B4 起再加上 {@code EconomyData.marketZones()}
   * 的区级覆盖 —— 口径 = 区级优先、按币对回落该区发行 GOV 的 GOV 级报价）。 默认 {@link FxRoundInput#none()} ⇒ <b>本轮没有政府窗口</b>（★
   * P-T5 起<b>不再</b>等于"没有外汇面"：家户的民间簿不依赖窗口， 逐格逐户按 F-1 购买力自报价；见 {@code
   * FxSettlement#planHouseholdOrders}）。★ 单币世界 / 无价可比 ⇒ 不挂单 ⇒ 逐值退回 A2a 之前（I-C2）。
   *
   * <p>★ 逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘（I17：汇率不进状态）。
   */
  FxRoundInput fx = FxRoundInput.none();

  /**
   * ★★ <b>W2：一轮"未付价款记债"的入参</b>（{@code null} = 本轮没有记债能力 ⇒ 退回旧口径）。
   *
   * <p>★★ <b>W2-fix4（2026-10-11）改名并收窄</b>：原 {@code CreditConfig}（D-030/D-031 的市场信用入参：
   * 放贷人可借头寸、借款人额度）随市场信用撮合整段退役 ⇒ 现在它只剩一个字段，服务 {@link #recordDeferredPaymentDebt} 那一条通道（未付价款 ⇒
   * 对该卖方的债务）。
   *
   * @param dueCycle 新合同的到期周期（本批 = 当前周期 + 1；与货币腿的借款合同同源，见 {@code MoneyMarketSettlement}）
   */
  record DeferredPaymentConfig(long dueCycle) {
    DeferredPaymentConfig {
      if (dueCycle < 0L) {
        throw new IllegalArgumentException("DeferredPaymentConfig.dueCycle 不得为负: " + dueCycle);
      }
    }
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "市场轮构造器只做装配期 fail-closed 校验（Objects.requireNonNull / 排除集冻结），失败必须整轮不产出；本类不定义"
              + " finalizer，唯一子类 MarketSettlement.MarketRound 是包内 static final，外部无法子类化、拿不到半成品 ⇒"
              + " finalizer 攻击面不存在")
  MarketRoundCore(
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
    this(
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
        MarketRegulation.none(),
        null,
        null,
        Set.of());
  }

  /**
   * ★★ <b>带区内市场规则的完整构造器</b>（生产路径用；旧构造器委托 {@link MarketRegulation#none()}）。
   *
   * <p>★ 旧构造器<b>保留且行为不变</b>：{@code regulation = none()} ⇒ 无税费；{@code deferredPaymentConfig}/{@code
   * debts} 为 {@code null} ⇒ 无记债能力、逐值退回"现金封顶"的旧口径。
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "市场轮构造器只做装配期 fail-closed 校验（Objects.requireNonNull / 排除集冻结），失败必须整轮不产出；本类不定义"
              + " finalizer，唯一子类 MarketSettlement.MarketRound 是包内 static final，外部无法子类化、拿不到半成品 ⇒"
              + " finalizer 攻击面不存在")
  MarketRoundCore(
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
    this(
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
        null,
        null,
        Set.of());
  }

  /** ★★ <b>D-030：带市场信用的完整构造器（旧签名兼容）</b>：不含 Z7b 排除集 ⇒ 与改动前逐值同行为；生产、 只读计划轮与读口都走带排除集的下一支。 */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "市场轮构造器只做装配期 fail-closed 校验（Objects.requireNonNull / 排除集冻结），失败必须整轮不产出；本类不定义"
              + " finalizer，唯一子类 MarketSettlement.MarketRound 是包内 static final，外部无法子类化、拿不到半成品 ⇒"
              + " finalizer 攻击面不存在")
  MarketRoundCore(
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
    this(
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
        Set.of());
  }

  /**
   * ★★ <b>Z7b：带单位户集合的完整构造器</b>（生产/只读计划轮/读口的唯一入口；旧构造器委托 {@code Set.of()}）。
   *
   * <p>★ {@code debts} 是<b>引用</b>：市场信用经 {@link DebtContractBook#upsert} 就地累加，不复制成第二份债务表。
   *
   * @param marketExcludedHouseholds 组合根传入的<b>单位户集合</b>（非 null、不得含 null）。★★ R2（2026-10-11）起它
   *     <b>不再表示"不生成任何订单"</b>：其中的家户按 {@link #householdMarketRole} 分为"只买不卖"（军户/官吏户）与
   *     "只按授权下单"（政府国库户，取自 {@code govMandates}）；本构造器<b>不再</b>并入任何国库户（那是第二个拼法）
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "市场轮构造器只做装配期 fail-closed 校验（Objects.requireNonNull / 排除集冻结），失败必须整轮不产出；本类不定义"
              + " finalizer，唯一子类 MarketSettlement.MarketRound 是包内 static final，外部无法子类化、拿不到半成品 ⇒"
              + " finalizer 攻击面不存在")
  MarketRoundCore(
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
    this.day = day;
    this.householdEconomies = Objects.requireNonNull(householdEconomies, "rows");
    this.householdGoods = Objects.requireNonNull(householdGoods, "householdGoods");
    this.householdMoney = Objects.requireNonNull(householdMoney, "householdMoney");
    this.householdFrozenGoods =
        Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
    this.householdFrozenMoney =
        Objects.requireNonNull(householdFrozenMoney, "householdFrozenMoney");
    this.unmetToday = Objects.requireNonNull(unmetToday, "unmetToday");
    this.householdOfActor = Objects.requireNonNull(householdOfActor, "householdOfActor");
    this.industries = Objects.requireNonNull(industries, "industries");
    this.units = Objects.requireNonNull(units, "units");
    this.assetShares = Objects.requireNonNull(assetShares, "assetShares");
    this.relations = Objects.requireNonNull(relations, "relations");
    this.laborCommitments = Objects.requireNonNull(laborCommitments, "allocations");
    this.shipments = Objects.requireNonNull(shipments, "shipments");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.operatorConditions =
        operatorConditions == null ? Map.of() : Map.copyOf(operatorConditions);
    this.index = Objects.requireNonNull(index, "index");
    this.householdDemands = householdDemands == null ? Map.of() : householdDemands;
    this.regulation = regulation == null ? MarketRegulation.none() : regulation;
    this.deferredPaymentConfig = deferredPaymentConfig;
    this.debts = debts;
    this.marketExcludedHouseholds = freezeExcludedHouseholds(marketExcludedHouseholds);
  }

  /**
   * ★★ <b>本入口有没有"未付价款 ⇒ 记债"的能力</b>（{@code deferredPaymentConfig} 与债务工作副本缺一即关闭； 旧构造器因此逐值退回"货款 + 运费 +
   * 税 ≤ 可付"的旧口径，绝不凭空赊账）。
   */
  boolean deferredPaymentsEnabled() {
    return deferredPaymentConfig != null && debts != null;
  }

  /** ★ W2：记债入参（{@code null} = 本轮无记债能力；订单生成与 {@code executeTrade} 都只读它）。 */
  DeferredPaymentConfig deferredPaymentConfig() {
    return deferredPaymentConfig;
  }

  /** ★ D-030：债务工作表引用（信用关闭时为 {@code null}；市场不复制、不另建第二份）。 */
  Map<DebtContractId, DebtContract> debts() {
    return debts;
  }

  /** ★★ Z7b 的单位户集合（只读；★ R2 起**只允许经** {@link #householdMarketRole} 消费）。 */
  Set<HouseholdId> marketExcludedHouseholds() {
    return marketExcludedHouseholds;
  }

  /**
   * ★★★ <b>控制方裁定 R2（2026-10-11）：本户在本轮商品市场上的角色 —— 唯一判据（唯一拼写点）</b>。
   *
   * <pre>
   * ① 国库户（{@code GovernmentMarketMandatePlan.isAuthorizationOnly}，权威 = {@code Government.treasury()}）
   *      ⇒ AUTHORIZATION_ONLY：**两侧都不生成自动订单**（改前口径一字不动）
   * ② 其余在组合根注入的单位户集合里（Z7b 集合）
   *      ⇒ BUY_ONLY：**买盘照常、卖盘不生成**（R2 的收窄点）
   * ③ 其余 ⇒ FULL：买卖两侧照旧
   * </pre>
   *
   * <p>★★ <b>为什么国库优先</b>：两处来源（{@code governments} 与 {@code Unit.households()}）在 GOV 单位形态下会同时命中
   * 同一个家户（国库就是该单位的账房）⇒ 判据必须是"更严的那一条赢"（fail-closed），否则公家库存会被自动清仓 —— 那正是 Z7b 的根因（run6 day50 中央粮
   * 271,393 → 0）。
   *
   * <p>★ <b>不适用面</b>：{@code household == null} 的参与者是**经营者/组织**（不是家户，没有口粮需求、也不在 {@code
   * Unit.households()} 里）⇒ 恒判 {@link HouseholdMarketRole#FULL}，走改前的普通经营者路径。
   */
  HouseholdMarketRole householdMarketRole(HouseholdId household) {
    if (household == null) {
      return HouseholdMarketRole.FULL; // 经营者/组织：本条判据的适用面之外
    }
    if (govMandates.isAuthorizationOnly(household)) {
      return HouseholdMarketRole.AUTHORIZATION_ONLY;
    }
    return marketExcludedHouseholds.contains(household)
        ? HouseholdMarketRole.BUY_ONLY
        : HouseholdMarketRole.FULL;
  }

  /**
   * ★★ <b>P-T5：某户的持币表</b>（币种 → 余额，毫；只读）。
   *
   * <p>★ <b>为什么必须有这个读口</b>：民间簿要回答"这户手里有哪几种币"（F-1 的比较集），而"我有多钱"的唯一口径是 {@link
   * MarketSettlement#spendableMoneyOf}（余额 − 冻结）—— 持币表在 {@code MarketRound} 里是私有字段，FX 段（另一个类） 拿不到 ⇒
   * 给它一个<b>窄入口</b>，而不是在那边另拼一份"我有多钱"。缺行 ⇒ 空表（不是 null）。
   */
  Map<CurrencyId, Long> moneyOf(HouseholdId household) {
    return household == null ? Map.of() : householdMoney.getOrDefault(household, Map.of());
  }

  /**
   * ★★ <b>2026-10-08（阶段 1）：注入本轮的套利决定</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 沿用 {@code withDeferredPayments} 的形制：先用既有唯一完整构造器造一份新实例，再把套利计划挂上 ——
   * <b>不改任何构造器签名</b>（既有调用方/测试因此逐字不动，旧路径自然拿到 {@link
   * MarketArbitragePlan#empty()}）。挂载发生在对象发布之前（同一天结算内的协调器线程），因此不存在 数据竞争。
   */
  MarketRound withArbitrage(MarketArbitragePlan plan) {
    Objects.requireNonNull(plan, "withArbitrage 的套利计划不得为 null（没有就给 MarketArbitragePlan.empty()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = plan;
    next.fx = fx; // ★ A2a：同一处"替换式构造"必须逐字段带过（克隆丢字段是本类踩过的坑）
    next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段
    next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
    next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /**
   * ★★ <b>A2a：注入本轮的外汇入参</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 形制与 {@link #withArbitrage} 逐字相同：不新增构造器签名，旧调用方自然拿到 {@link FxRoundInput#none()}（= 没有政府窗口； ★
   * P-T5 起它<b>不</b>等于"没有外汇面"）。
   */
  MarketRound withFx(FxRoundInput input) {
    Objects.requireNonNull(input, "withFx 的外汇入参不得为 null（没有就给 FxRoundInput.none()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = arbitrage;
    next.fx = input;
    next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段
    next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
    next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /** ★★ A2a：本轮的外汇入参（缺省 {@link FxRoundInput#none()} ⇒ 没有政府窗口；P-T5 起家户民间簿不依赖它）。 */
  FxRoundInput fx() {
    return fx;
  }

  /** ★★ 2026-10-08（阶段 1）：本轮的套利决定（缺省空 ⇒ 订单生成逐值退回改前口径）。 */
  MarketArbitragePlan arbitrage() {
    return arbitrage;
  }

  /** ★★ R1：本日的政府市场授权计划（缺省空 ⇒ 没有"只按授权下单"的家户，逐值退回改前行为）。 */
  GovernmentMarketMandatePlan govMandates() {
    return govMandates;
  }

  /** ★★ R2：本轮逐区逐币种/逐商品的实际管制力（缺省 {@link PortEnforcementInput#none()} ⇒ 没有口岸面）。 */
  PortEnforcementInput portEnforcement() {
    return portEnforcement;
  }

  /** ★★ P-T1b：本轮逐区的税率与收税政府（缺省 {@link PortTaxInput#none()} ⇒ 三层税都不收）。 */
  PortTaxInput portTax() {
    return portTax;
  }

  /**
   * ★★ <b>P-T1d：注入本轮的政府采购优先级（谁要求管控市场 + 各自的行政力池）</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 形制与 {@link #withPortTax} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link ProcurementPriorityInput#none()}（=
   * 没有政府要求管控，逐值退回改前）。★ 它必须与其余五个 {@code withX} 互相带过（见各方法里的 {@code next.xxx = xxx} 几行）——丢字段是本类踩过三次的坑。
   */
  MarketRound withProcurementPriority(ProcurementPriorityInput input) {
    Objects.requireNonNull(
        input, "withProcurementPriority 的入参不得为 null（没有政府管控就给 ProcurementPriorityInput.none()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = arbitrage;
    next.fx = fx;
    next.govMandates = govMandates;
    next.portEnforcement = portEnforcement;
    next.portTax = portTax;
    next.procurementPriority = input;
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /**
   * ★★ <b>P-T1d：本轮的政府采购优先级</b>（缺省 {@link ProcurementPriorityInput#none()} ⇒ 没有政府要求管控市场）。
   *
   * <p>★ 读者只有两处：撮合前的置顶排序（{@link #applyProcurementPriority}）与撮合里"按哪一套顺序"的判据 ——
   * 它<b>不</b>参与订单生成、不参与结算、不改任何价格/量算式。
   */
  ProcurementPriorityInput procurementPriority() {
    return procurementPriority;
  }

  /**
   * ★★★ <b>P2f：土地订单簿（本轮可写视图）</b>—— 与商品买卖槽同属这一个 {@link MarketRound}；P3 经 {@link
   * #addLandOrder(LandOrder)} 注入，撮合与读数经本读口消费。
   *
   * <p>★ <b>为什么返回活表</b>：它是"本轮可变订单簿"，由协调器单线程在撮合之前填充、撮合期间只读；与 {@code round.householdGoods}
   * 等会话工作副本同一条"包内、逐轮瞬态"纪律。P2f 不复制第二本订单簿。
   */
  List<LandOrder> landOrders() {
    return landOrders;
  }

  /** ★★ P2f：注入一张土地订单（唯一入口；校验在 {@link MarketOrderPlanner#putLandOrder}）。 */
  void addLandOrder(LandOrder order) {
    MarketOrderPlanner.putLandOrder(this, order);
  }

  /** ★★★ RA1b2c2：本轮标量 LAND 持有工作副本（只读视图；轮末写回会话）。 */
  Map<LandHoldingKey, LandHolding> landHoldings() {
    return Collections.unmodifiableMap(landHoldings);
  }

  /** ★★★ RA1b2c2：注入/刷新本轮标量 LAND 持有工作副本（runner 开盘时与成交后各写一次；唯一写口）。 */
  void setLandHoldings(Map<LandHoldingKey, LandHolding> source) {
    Objects.requireNonNull(source, "setLandHoldings 的 source 不得为 null（没有土地给空表）");
    landHoldings.clear();
    landHoldings.putAll(source);
  }

  /** ★★★ P3.0：追加一张 M3 显式买单到本轮订单簿（不清空 planner 自动订单）。 */
  void addInjectedBuyOrder(BuyOrder order) {
    injectedBuyOrders.add(Objects.requireNonNull(order, "injectedBuyOrder 不得为 null"));
  }

  /** ★★★ P3.0：追加一张 M3 显式卖单到本轮订单簿（不清空 planner 自动订单）。 */
  void addInjectedSellOrder(SellOrder order) {
    injectedSellOrders.add(Objects.requireNonNull(order, "injectedSellOrder 不得为 null"));
  }

  /** ★★★ D-032 U2b：追加一张统一市场订单到本轮注入订单唯一列表（不清空 planner 自动订单）。 */
  void addInjectedMarketOrder(MarketOrder order) {
    injectedMarketOrders.add(Objects.requireNonNull(order, "injectedMarketOrder 不得为 null"));
  }

  /** ★★★ D-032 U2b：本轮注入统一订单的只读视图。 */
  List<MarketOrder> injectedMarketOrders() {
    return Collections.unmodifiableList(injectedMarketOrders);
  }

  /** ★★ P2f：本轮新取得土地块（只读视图；P3 的 M3/M6/M7 消费）。 */
  Set<AssetShareId> landAcquiredThisRound() {
    return Collections.unmodifiableSet(landAcquiredThisRound);
  }

  /** ★★ P2f：登记一块本轮新取得的土地（市场买地 / M2 抵债共用同一事实；重复保持首次出现序）。 */
  void markLandAcquired(AssetShareId landShare) {
    if (landShare == null) {
      throw new IllegalArgumentException("MarketRound.landAcquiredThisRound 不得登记 null 土地块");
    }
    landAcquiredThisRound.add(landShare);
  }

  /** ★★ D-032 RA1b2a/RA1b2c2：本轮按 key 取得的土地数量只读视图（M3/M6/M7 按数量排除；不落盘）。 */
  Map<LandHoldingKey, Long> landAcquiredQuantityThisRound() {
    return Collections.unmodifiableMap(landAcquiredQuantityThisRound);
  }

  /** ★★ D-032 RA1b2a：登记一笔本轮取得的标量土地数量；同 key 累加，数量必须严格为正。 */
  void markLandAcquired(LandHoldingKey key, long quantityMilliMu) {
    if (key == null) {
      throw new IllegalArgumentException("MarketRound.landAcquiredQuantityThisRound 不得登记 null key");
    }
    if (quantityMilliMu <= 0L) {
      throw new IllegalArgumentException(
          "MarketRound.landAcquiredQuantityThisRound 的数量必须 > 0: key="
              + key
              + " -> "
              + quantityMilliMu);
    }
    long previous = landAcquiredQuantityThisRound.getOrDefault(key, 0L);
    try {
      landAcquiredQuantityThisRound.put(key, Math.addExact(previous, quantityMilliMu));
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "MarketRound.landAcquiredQuantityThisRound 累加溢出: key="
              + key
              + " "
              + previous
              + " + "
              + quantityMilliMu,
          overflow);
    }
  }

  /**
   * ★★★ D-032 RC6：S7-B 把某个 {@code (hex, buyer)} 本轮市场取得的 LAND 全量转给 LANDED target 后，清除该项市场取得 标记。★ 只清
   * {@code marketRound.landAcquiredQuantityThisRound}（市场事实）；不碰 {@code
   * SettlementDayContext.landAcquiredQuantityThisRound} 里的 M2 抵债事实。
   */
  void clearMarketLandAcquired(LandHoldingKey key) {
    if (key == null) {
      throw new IllegalArgumentException("clearMarketLandAcquired 的 key 不得为 null");
    }
    if (landAcquiredQuantityThisRound.remove(key) == null) {
      throw new IllegalStateException("clearMarketLandAcquired 找不到本轮市场 LAND 取得项（契约故障）: " + key);
    }
  }

  /** ★★ P2f：下一轮的土地价（只读视图；轮末由 {@code SettlementDayMarketPipeline} 写回 MarketTable）。 */
  Map<MarketZoneId, Long> nextLandPrices() {
    return Collections.unmodifiableMap(nextLandPrices);
  }

  /** ★★ P2f：登记一个市场区的下一轮土地价（唯一写入点 = {@link MarketMatcher#matchAll} 内部土地分支）。 */
  void recordNextLandPrice(MarketZoneId zoneId, long nextLandPrice) {
    Objects.requireNonNull(zoneId, "recordNextLandPrice 的 zoneId 不得为 null");
    if (nextLandPrice < 0L) {
      throw new IllegalArgumentException("土地价不得为负（0 = 明确免费）: " + nextLandPrice);
    }
    nextLandPrices.put(zoneId, nextLandPrice);
  }

  /**
   * ★★★ <b>P2f：土地状态必须逐值带过克隆</b>（本类"克隆丢字段"坑的第七个字段族）。
   *
   * <p>★ 每一个 {@code withX} 与 {@link #copyForWorker} 都新建一个 {@link MarketRound}（构造器只给空土地表）⇒
   * 调用本方法把订单簿、 本轮取得标记、下一轮地价<b>逐值搬过去</b>。漏掉它 = 订单簿在装配链上静默清空 / 本轮买地标记消失（无任何报错）。
   */
  void inheritLandStateFrom(MarketRoundCore source) {
    landOrders.clear();
    landOrders.addAll(source.landOrders);
    injectedBuyOrders.clear();
    injectedBuyOrders.addAll(source.injectedBuyOrders);
    injectedSellOrders.clear();
    injectedSellOrders.addAll(source.injectedSellOrders);
    planningBuyOrders = source.planningBuyOrders;
    planningSellOrders = source.planningSellOrders;
    planningOrdersCaptured = source.planningOrdersCaptured;
    injectedMarketOrders.clear();
    injectedMarketOrders.addAll(source.injectedMarketOrders);
    billCoveredCommodities.clear();
    for (Map.Entry<HouseholdId, Set<CommodityId>> entry :
        source.billCoveredCommodities.entrySet()) {
      billCoveredCommodities.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
    }
    landAcquiredThisRound.clear();
    landAcquiredThisRound.addAll(source.landAcquiredThisRound);
    landAcquiredQuantityThisRound.clear();
    landAcquiredQuantityThisRound.putAll(source.landAcquiredQuantityThisRound);
    landHoldings.clear();
    landHoldings.putAll(source.landHoldings);
    nextLandPrices.clear();
    nextLandPrices.putAll(source.nextLandPrices);
  }

  /**
   * ★★ <b>P-T1b：注入本轮的三层税税率（区级 + 收税政府）</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 形制与 {@link #withPortEnforcement} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link PortTaxInput#none()}（=
   * 不收税，逐值退回改前）。★ 它必须与其余四个 {@code withX} 互相带过（见各方法里的 {@code next.xxx = xxx} 几行）——丢字段是本类踩过三次的坑。
   */
  MarketRound withPortTax(PortTaxInput input) {
    Objects.requireNonNull(input, "withPortTax 的入参不得为 null（没有税就给 PortTaxInput.none()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = arbitrage;
    next.fx = fx;
    next.govMandates = govMandates;
    next.portEnforcement = portEnforcement;
    next.portTax = input;
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /**
   * ★★ <b>R2：注入本轮的口岸实际管制力</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 形制与 {@link #withFx} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link PortEnforcementInput#none()}。 ★
   * 它必须与其余三个 {@code withX} 互相带过（见各方法里的三行 `next.xxx = xxx`）。
   */
  MarketRound withPortEnforcement(PortEnforcementInput input) {
    Objects.requireNonNull(
        input, "withPortEnforcement 的入参不得为 null（没有口岸面就给 PortEnforcementInput.none()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = arbitrage;
    next.fx = fx;
    next.govMandates = govMandates;
    next.portEnforcement = input;
    next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /**
   * ★★ <b>R1：注入本日的政府市场授权计划</b>（返回一个新的 {@link MarketRound}；原对象不动）。
   *
   * <p>★ 形制与 {@link #withArbitrage} 逐字相同：不新增构造器签名，既有调用方自然拿到 {@link
   * GovernmentMarketMandatePlan#empty()}。★ 它必须**最后**注入（或由各个 {@code withX} 逐字段带过），否则会被后续的 {@code
   * withDeferredPayments}/{@code withArbitrage}/{@code withFx} 静默丢掉 —— 那正是本类踩过的"克隆丢字段"坑。
   */
  MarketRound withGovMandates(GovernmentMarketMandatePlan plan) {
    Objects.requireNonNull(
        plan, "withGovMandates 的计划不得为 null（没有就给 GovernmentMarketMandatePlan.empty()）");
    MarketRound next = MarketRoundWitherBook.newRoundForWither(this, deferredPaymentConfig, debts);
    next.arbitrage = arbitrage;
    next.fx = fx;
    next.govMandates = plan;
    next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段
    next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /**
   * ★★ <b>克隆的唯一拼写点</b>（2026-10-08 修复"克隆静默丢字段"这一类 bug）。
   *
   * <p>★★ <b>为什么必须有它</b>：并行 worker 用的两条克隆路径（{@code MarketSettlement.readOnlyPlanningRound} 与区副本的
   * local round）此前各自<b>手抄一遍 22 个构造参数</b>；{@code MarketRound} 一加字段 （本轮 = {@code
   * arbitrage}），克隆就会把它悄悄丢掉，而克隆出的那个轮<b>才是在下订单的那一份</b> ⇒ 整套套利一次都不生效、且没有任何报错（实测：360 tick 与基线逐值相同，计划 83
   * 户但 {@code ARBITRAGE_BUY_ORDER} = 0 条）。
   *
   * <p>★★ <b>语义</b>：<b>逐字段原样带过本轮的其余全部输入</b>（含 {@code regulation} / {@code deferredPaymentConfig} /
   * {@code debts} / {@code marketExcludedHouseholds} / {@code index} / {@code arbitrage}），
   * 只替换调用方<b>显式点名</b>的六样：四张账户表、未满足表、账本累加器 —— 这六样正是两条克隆路径真正不同的部分。 因此"将来再加一个 {@code MarketRound}
   * 字段"不再需要改克隆点。
   *
   * <p>★ <b>与改前的逐值差异（已审计）</b>：旧克隆把 {@code regulation} 写死成 {@link MarketRegulation#none()}、 区副本还把
   * {@code deferredPaymentConfig}/{@code debts}/{@code marketExcludedHouseholds} 留成缺省。三者在本轮**都没有
   * 读取点**：克隆轮只进 {@code planFor}/{@code ordersFor}（P-T1c 起订单生成只读逐格价表，不再读调控）与 {@code
   * matchGroup}（区副本；{@code collectUnfilled} 只在协调器的真实 {@code ctx} 上跑）⇒ 本方法把它们原样带过是
   * **行为等价**的，只是不再有"字段悄悄变缺省"。
   */
  MarketRound copyForWorker(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoodsCopy,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoneyCopy,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoodsCopy,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoneyCopy,
      Map<HouseholdId, Map<CommodityId, Long>> unmetTodayCopy,
      ProductionLedger.Accumulator workerLedger) {
    MarketRound next =
        new MarketRound(
            day,
            householdEconomies,
            householdGoodsCopy,
            householdMoneyCopy,
            householdFrozenGoodsCopy,
            householdFrozenMoneyCopy,
            unmetTodayCopy,
            householdOfActor,
            industries,
            units,
            assetShares,
            relations,
            laborCommitments,
            shipments,
            workerLedger,
            operatorConditions,
            index,
            householdDemands,
            regulation,
            deferredPaymentConfig,
            debts,
            marketExcludedHouseholds);
    next.arbitrage = arbitrage; // ★ 新字段在这里被带过 —— 这正是"手抄 22 个参数"漏掉的那一行
    next.fx = fx; // ★ A2a：同一个坑的第二个字段（克隆轮丢 fx = 外汇面整段不生效且毫无报错）
    next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段（丢了它 = 国库户自动订单守卫整段失效）
    next.portEnforcement = portEnforcement; // ★ R2：同一个坑的第四个字段（丢了它 = 口岸管制整段不生效）
    next.portTax = portTax; // ★ P-T1b：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
    next.procurementPriority = procurementPriority; // ★ P-T1d：同一个坑的第六个字段
    next.inheritLandStateFrom(this); // ★ P2f：土地订单簿/取得标记/下一轮地价必须逐值带过
    return next;
  }

  /** ★ 单位户集合是身份集合：逐元素查 null、保序冻结（绝不用 {@code Set.copyOf}——不承诺保序）。 */
  static Set<HouseholdId> freezeExcludedHouseholds(Set<HouseholdId> excluded) {
    Objects.requireNonNull(excluded, "marketExcludedHouseholds 不得为 null（无排除给空集）");
    LinkedHashSet<HouseholdId> copy = new LinkedHashSet<>();
    for (HouseholdId household : excluded) {
      if (household == null) {
        throw new IllegalArgumentException("marketExcludedHouseholds 不得含 null");
      }
      copy.add(household);
    }
    return Collections.unmodifiableSet(copy);
  }

  /**
   * ★★ <b>W2：给已构造的市场轮补上"未付价款记债"的入参</b>（{@code EconomySettlement} 在判定今天真的开市之后才构造 {@link
   * DeferredPaymentConfig}；本方法<b>不复制账户表</b>，只换记债入参）。
   *
   * <p>★★★ <b>W5-fix（2026-10-11）：本方法此前是六个 wither 里<b>唯一</b>漏带三个字段的那一个</b> —— 它只带过 {@code
   * arbitrage}/{@code fx}/{@code govMandates}，<b>不带</b> {@code portEnforcement}/{@code
   * portTax}/{@code procurementPriority}。 生产注入次序（{@code withDeferredPayments} 在 {@code
   * withGovMandates}/{@code withPortEnforcement}/ {@code withPortTax}/{@code
   * withProcurementPriority} <b>之前</b>调用）恰好绕过了这个缺陷，因此它不是可复现的 线上故障 —— 但形状与其余五个 wither
   * 的注释里点名三次的"克隆丢字段"坑<b>逐字相同</b>：换一次注入次序 （例如把记债入参挪到口岸/税之后注入）就会让口岸管制与三层税<b>整段静默失效且毫无报错</b>。
   *
   * <p>★ <b>按 fail-closed 修</b>：逐字段带过它们（与 {@code withArbitrage}/{@code withFx}/… 同一形态），
   * 并加一条**运行期守卫**（见方法尾）—— 真正的契约故障宁可当场抛，也不许静默退回缺省。
   */
  MarketRound withDeferredPayments(long dueCycle, Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    MarketRound next =
        MarketRoundWitherBook.newRoundForWither(this, new DeferredPaymentConfig(dueCycle), debts);
    next.arbitrage = arbitrage;
    next.fx = fx;
    next.govMandates = govMandates; // ★ R1：同一个坑的第三个字段（丢了它 = 授权整段静默失效）
    next.portEnforcement = portEnforcement; // ★ W5-fix：同一个坑的第四个字段
    next.portTax = portTax; // ★ W5-fix：同一个坑的第五个字段（丢了它 = 三层税整段不生效且毫无报错）
    next.procurementPriority = procurementPriority; // ★ W5-fix：同一个坑的第六个字段
    // ★★ P2f：土地订单簿/本轮取得标记/下一轮地价逐值带过（第七个字段族）。
    next.inheritLandStateFrom(this);
    // ★★ W5-fix 的 fail-closed 守卫：**这一轮上原本有的**入参，克隆之后必须还是**同一个实例**。
    //   ★ 判据用引用相等（不是 equals）：本类全部 wither 的语义就是"逐字段原样带过**同一份**输入"
    //     （缺省实例 {@code none()}/{@code empty()} 因此也逐值相同），引用漂开只可能来自"新建了一份"。
    //   ★ 契约故障 ⇒ ERROR 且不降级（AGENTS §一.9：不把一致性故障降级成 WARN/DEBUG）。
    if (arbitrage != next.arbitrage
        || fx != next.fx
        || govMandates != next.govMandates
        || portEnforcement != next.portEnforcement
        || portTax != next.portTax
        || procurementPriority != next.procurementPriority
        || next.landOrders().size() != landOrders.size()
        || next.landAcquiredThisRound().size() != landAcquiredThisRound.size()
        || !next.landAcquiredQuantityThisRound().equals(landAcquiredQuantityThisRound)
        || !next.landHoldings().equals(landHoldings)
        || !next.nextLandPrices().equals(nextLandPrices)) {
      EventLog.channel(MARKET)
          .error(
              LogEvent.of(
                  "MARKET_ROUND_CLONE_FIELD_LOST",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "reason",
                  "withDeferredPayments-dropped-a-field",
                  "arbitrageKept",
                  arbitrage == next.arbitrage,
                  "fxKept",
                  fx == next.fx,
                  "govMandatesKept",
                  govMandates == next.govMandates,
                  "portEnforcementKept",
                  portEnforcement == next.portEnforcement,
                  "portTaxKept",
                  portTax == next.portTax,
                  "procurementPriorityKept",
                  procurementPriority == next.procurementPriority));
      throw new IllegalStateException(
          "MarketRound.withDeferredPayments 丢了一个本轮已注入的字段（克隆丢字段，契约故障）: day=" + day);
    }
    return next;
  }

  /** ★ R4-E2：需求账本（只读；空表 = 没有 GM 需求，订单退回旧基线）。 */
  Map<DemandId, HouseholdDemand> householdDemands() {
    return householdDemands;
  }

  /** ★★ D-027：本轮区级市场总调控（逐轮瞬态；不落盘）。默认实例 = 逐值现状。 */
  MarketRegulation regulation() {
    return regulation;
  }

  /** 本轮世界日（与字段同源，不另设第二个日号）。 */
  long day() {
    return day;
  }

  /*
   * ★★ A2a：外汇面（{@code FxSettlement}）与本类同包但不同顶层类 ⇒ 它读不到 MarketRound 的私有字段。
   * 这里给出**只读访问器**（不复制表、不暴露 setter）：FX 撮合与商品撮合同住一轮，必须用同一份账户表与同一个
   * 账本累加器 —— 第二份副本就会变成"两本账"。
   */

  Map<HouseholdId, Map<CommodityId, Long>> householdGoods() {
    return householdGoods;
  }

  Map<HouseholdId, Map<CurrencyId, Long>> householdMoney() {
    return householdMoney;
  }

  Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods() {
    return householdFrozenGoods;
  }

  Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney() {
    return householdFrozenMoney;
  }

  Map<ActorRef, HouseholdId> householdOfActor() {
    return householdOfActor;
  }

  Map<HouseholdId, HouseholdEconomy> householdEconomies() {
    return householdEconomies;
  }

  ProductionLedger.Accumulator ledger() {
    return ledger;
  }
}
