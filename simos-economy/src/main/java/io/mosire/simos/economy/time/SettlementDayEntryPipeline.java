package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.requireHouseholdAccounts;
import static io.mosire.simos.economy.time.AccountTransferSettlement.requireHouseholdMoney;
import static io.mosire.simos.economy.time.SettlementLogSupport.RAW;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.logReason;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayEntryPipeline {

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void initialize(SettlementDayContext ctx) {
    Objects.requireNonNull(
        ctx.survivalSocialFacts,
        "survivalSocialFacts（没有 Social 投影请显式传 SurvivalSocialFacts.unavailable()）");
    Objects.requireNonNull(
        ctx.portEnforcement, "portEnforcement（没有口岸面给 PortEnforcementInput.none()，不得为 null）");
    Objects.requireNonNull(ctx.portTax, "portTax（没有税给 PortTaxInput.none()，不得为 null）");
    Objects.requireNonNull(
        ctx.procurementPriority,
        "procurementPriority（没有政府管控给 ProcurementPriorityInput.none()，不得为 null）");
    Objects.requireNonNull(ctx.session, "session（S1：revision 级会话持有可变工作表）");
    Objects.requireNonNull(ctx.accounts, "accounts（S1：账户会话是会话状态，必须由调用方载入）");
    Objects.requireNonNull(ctx.topology, "topology（M2.3：区域拓扑是只读输入；单格世界用 MarketTopology.singleHex）");
    Objects.requireNonNull(ctx.parallelism, "parallelism（R2：并行度配置）");
    Objects.requireNonNull(
        ctx.marketExcludedHouseholds, "marketExcludedHouseholds（无排除给空集，不得为 null）");
    ctx.base = ctx.session.base();
    // ── ★★ B2（2026-10-10）**当日视图**：逐日循环里"当刻状态"的唯一读口 ───────────────────────────────
    //   形态 = 工作表的只读门面（WorkingDayView）：构造只持一个引用（引用级、与状态规模无关），
    //   每次读取现场解析"工作副本优先、未物化则复用 base 的不可变表" ⇒ **零拷贝/零序列化/零落盘/零 Revision**。
    //   ★★ 铁律（B2 §3.2）：本方法内所有**逐日**读者（迁移/择业/清算/前瞻/运力/市场装配/日志）一律读 dayView；
    //     段首 `base` 只允许**段级**语义（审计/差分/回放起点），每一处仍读它的地方都在注释里写明理由
    //     （完整清单见 .superpowers/sdd/2026-10-10-b2-per-day-view/impl-ledger.md）。
    //   ★ 为什么必须在**每一天开始时**派生：一次 advance 的 N 天共用同一个会话工作表（整段一条 revision），
    //     而 base 是段首快照 —— 段内第 2 天起再读 base 就是读"上一天以前的旧世界"（这正是路径效应的根因）。
    //   ★ 为什么不是"构造时把表拍进字段"：工作副本是惰性物化的，拍快照会在它物化的那一刻变成过期引用。
    ctx.dayView = new WorkingDayView(ctx.session.sheet());
    // ── ★★★ D-032 D3 §7（W3）：MONEY_WAGE 的 B2 整 tick 拒绝已由真实工资结算取代 ─────────────
    //   真实结算在 `driveProductionDay`/关账之后、市场轮/还债之前执行（见下面的 WAGE_SETTLEMENT 段）。
    // ★★★ R2（2026-10-11，控制方裁定）：**组合根注入的 Z7b 集合原样照收** —— 分类只发生在**一处**：
    //   {@code MarketRound.householdMarketRole}（国库户 ⇒ 只按授权下单；其余单位户 ⇒ 只买不卖；其余 ⇒ 普通家户）。
    //   ★ 改前这里有第二处分类：`unitExclusionsMinusGovernmentTreasuries`（把"是政府国库户"的单位户从集合里摘出来）。
    //     它随 R2 删除 —— 角色的权威是 {@code Government.treasury()}（经 {@code govMandates} 注入），在角色判据里
    //     **优先于**单位户集合判定（更严的一条赢，fail-closed）⇒ 摘不摘都得到同一个角色；留着就是"谁被排除"的第二个拼法。
    //   ★ 语义（R2 冻结）：军户/官吏户按普通家户参与**买盘**（口粮通道），**卖侧维持 Z7b 原口径**（公家/单位实物不得被
    //     当余量清仓 —— run6 day50 中央粮 271,393 → 0 的根因守护）；国库户（hh-gov-*）两侧都不生成自动订单。
    ctx.effectiveMarketExcludedHouseholds = ctx.marketExcludedHouseholds;
    // ★★ E3：发行主体的权威答案是当前世界状态（governments），不是进程里的旧登记。
    //   日结算开始按**当日视图**重建登记表：旧世界/旧档 governments 为空 ⇒ 清空登记 ⇒ requireIssuerOf 逐字保留旧 fail-closed 行为。
    //   ★ B2：这是**逐日**读者（每天都可能因 GM 命令/发行腿改政府表）⇒ 读当刻值，不读段首 base。
    MoneyIssuance.syncAuthorities(ctx.dayView.governments().values());
    ctx.flows = ctx.session.flows();
    ctx.householdGoods = ctx.accounts.householdGoods();
    ctx.householdMoney = ctx.accounts.householdMoney();
    ctx.householdFrozenGoods = ctx.accounts.householdFrozenGoods();
    ctx.householdFrozenMoney = ctx.accounts.householdFrozenMoney();
    ctx.meta = ctx.session.sheet().meta().orElseThrow();
    ctx.currentCycle = ctx.meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    ctx.dueCycle = ctx.currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1
    // ★★ 2026-10-07 GOV 非生产家户试点：周期开始日的政府铸币。
    //   位置：在任何转移/市场之前 —— 政府先按政策“印”出本周期可花的钱并落 FISCAL_ISSUE 审计；
    //   若政策量不够覆盖需求，后面的市场信用路径照常让它向家户借（= 政府发行债务）。
    //   ★★ B2：三个入口都收**当日视图** —— 铸币读当刻产业表（周期第一天的产业关账口径）、发债读当刻政府表。
    //   ★★★ W5（2026-10-11）：上面那一段（`GovernmentSeigniorage` / `GovernmentDebtIssuance` 双轨）**已整段删除** ——
    //     它是用户点名的"印钱绕过市场"的旁路，且与发债构成双轨（§一.11：旧设计直接删）。
    //     铸币现在的落点是**产业关账处**（见下面 harvest 之后调用的 {@link MintSettlement#settleCycleClose}）：
    //     钱是**生产出来的**（scale ×
    // IndustryDefinitionView.moneyOutputPerUnit），投入走既有的周期投入管线，不再有"周期开始直接加钱"。
    // ★★ E3：本次 revision 的发行审计收集器（id 由 transfer id + 币种确定性派生；并行分区也安全）。
    //   ★ 发行腿只在付方余额不足且付方 = 当前政府国库时才会用到；旧路径（无 issuer）不产生任何记录。
    ctx.issuanceJournal = new MoneyIssuanceJournal(ctx.dayView.governments(), ctx.currentCycle);

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    ctx.industries = ctx.session.sheet().industries();
    // ★★ R3B.2：生产单元表工作副本（进度/劳动/投入的唯一写点；IndustryDefinitionView 只留模板）。
    ctx.units = ctx.session.sheet().units();
    ctx.householdEconomies = ctx.session.sheet().householdEconomies();
    ctx.debts = ctx.session.sheet().debtContracts();
    // ★★ **计息的"昨日本金" = 当日起始快照**（M0.5 权威口径：计息日 {@code + ⌊昨 × 率 ÷ 1000⌋}，"昨"是当日开始时
    //   的本金）。必须在 4b 放贷 / 4c 偿还之前取；否则当天新借的债当天就被计息，与守恒式不符（实测：第 120 天
    //   新债 8,750 被记成 8,925，多出 175 = ⌊8,750 × 20‰⌋）。
    ctx.principalAtDayStart = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> debtEntry : ctx.debts.entrySet()) {
      ctx.principalAtDayStart.put(debtEntry.getKey(), debtEntry.getValue().principal());
    }
    // ★★ **R2：劳动配额表**——日结算**读**它（当日劳动的唯一来源）。★ W3 起结算**不再**按存活比例缩它
    //   （见 {@link #scaleLaborOfIndustry}："人死了劳动没减"这条旧账的收口）⇒ 需要工作副本。
    ctx.laborCommitments = ctx.session.sheet().laborCommitments();
    // ★ P2-A A3：成员份额不再是 Economy 状态组件 —— 家户人口组成由调用方作为**只读投影**传入（见 composition）。
    ctx.assetShares = ctx.session.sheet().assetShares();
    // ★★ S3：经营者状态机的工作副本 —— 与 industries/rows 同一条"日结算就地更新、结束后整体交出"的口径；
    //   状态转移读市场报告与债务/库存的可观察量，不在这里重算生产公式。
    ctx.operatorConditions = ctx.session.sheet().operatorConditions();
    // ★★ **M2.6（自适应价格的写回点）**：市场表的工作副本 —— 固定模式下结算只读它、终态与 {@code base.marketTables()} 逐值相同；
    //   自适应打开时 {@code MarketSettlement} 返回更新后的价格表，本方法把**这一份**交给终态 ⇒ 改价只经
    //   "状态 → 变更集（markets 是既有 FieldDelta 组件）"这一条路，没有第二处改价。
    ctx.markets = ctx.session.sheet().marketTables();
    // ★★ R4-E2b：生产关系表视图 —— 默认直接读 base 的不可变表；只有 E2b 进入执行真的新建 relation 时才物化工作副本
    //   （空表基线因此不产生任何拷贝）。下游所有日结算读取点统一走这个局部量，不再各处写 session.base().relations()。
    ctx.relations = ctx.session.sheet().relationsOrBase();

    // ★★ R4-B.3a-perf：日结算的只读派生索引 —— **每天入口构建一次**。它把“unit 可用资产/产能、unit↔家户、
    //   格↔unit/产业、债务人↔债务”这些一天内不变的问题一次算好，串行热路径与并行 worker 都只查表。
    //   ★ 不进 EconomyData/ChangeSet/Codec，不作为第二份状态；OwnershipStake/IndustryDefinitionView/unit
    // 本步不改写，故这一份在
    //     日结算内始终有效。HouseholdLaborCommitment 会在劳动再分配后被改写 ⇒ 那之后用 withLabor(...) 换一次配额侧视图；
    //     DebtContract 会在借粮/偿还/计息后被改写 ⇒ 状态机之前用 withDebtContracts(...) 换一次债务视图。
    ctx.settlementIndex =
        SettlementIndex.build(
            ctx.units,
            ctx.industries,
            ctx.assetShares,
            ctx.laborCommitments,
            ctx.householdEconomies,
            ctx.debts,
            ctx.relations,
            ctx.session.sheet().productionOrganizations(),
            ctx.session.sheet().assetOccupanciesOrBase(),
            ctx.session.sheet().productionRostersOrBase(),
            ctx.day,
            ctx.landAcquiredThisRound());

    // ★★ **H1 的第一条守卫：家户账必须覆盖每一个"要吃粮的家户"**（fail-closed；裁定 K1 / D3-C）——
    //   放在任何公式之前（与 E14 的"在任何数量计算之前"同款）：副本缺键时若继续跑，缺的那一家会被当成"库存 0"
    //   ⇒ 它当天"吃 0、投入 0"，账面看不出少了谁。那正是本仓最反对的形态，故当场抛。
    requireHouseholdAccounts(ctx.householdEconomies, ctx.householdGoods);
    // ★★ **H4 的第一条守卫：货币账必须覆盖每一个"可能要花钱的家户"**（与商品那条同款、同一条理由）：
    //   缺键时若继续跑，那一家的可花余额会被当成 0 —— 它当天的有效需求因此是 0、市场买不到粮、
    //   而账面（缺口只多不少）看起来完全正常。⇒ 缺键 ⇒ 当场抛（"没有账"与"账是空的"是两件事）。
    requireHouseholdMoney(ctx.householdEconomies, ctx.householdMoney);

    ctx.dayStartPopulation = 0L;
    for (HouseholdEconomy householdEconomy : ctx.householdEconomies.values()) {
      ctx.dayStartPopulation += householdEconomy.population();
    }
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DAY_START",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                ctx.day,
                "mapId",
                ctx.meta.mapId(),
                "rows",
                ctx.householdEconomies.size(),
                "population",
                ctx.dayStartPopulation,
                "units",
                ctx.units.size(),
                "markets",
                ctx.markets.size(),
                "organizations",
                ctx.session.sheet().productionOrganizations().size(),
                "debtContracts",
                ctx.debts.size(),
                "modes",
                // ★★ B5（裁定十八）：本组件（生产方式定义）**已不在 economy 状态里**（唯一权威在 production）⇒ 日视图只读；
                //   本行是逐日日志的只读计数，不存在"当刻与段首不同"的可能。
                ctx.dayView.modes().size(),
                "topologyRegions",
                ctx.topology.regions().size()));

    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "SETTLEMENT_START_DETAIL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  ctx.day,
                  "rows",
                  ctx.householdEconomies.size(),
                  "units",
                  ctx.units.size(),
                  "markets",
                  ctx.markets.size(),
                  "organizations",
                  ctx.session.sheet().productionOrganizations().size(),
                  "debtContracts",
                  ctx.debts.size(),
                  "modes",
                  // ★★ B5（裁定十八）：同上一处（生产方式定义表在 production，日视图只读；逐日日志只读计数）。
                  ctx.dayView.modes().size(),
                  "topologyRegions",
                  ctx.topology.regions().size()));
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void applyEntry(SettlementDayContext ctx) {
    // ── 0-entry. ★★ R4-E2b：候选预设的实际采用（GM 预设 → 合条件家户 → TRIALING 试产）──────────────
    //   ★ 位置：**在现扣周期投入与劳动再分配之前**（下面 `if (plantingDrawsFirst)` / `else` 两支都会在
    //     当天各自的第一阶段扣投入并重排劳动）—— 新 unit 因此当天就能进入本周期：progressDays=0 ⇒ 当天
    //     drawCycleInputs 会为它扣一次投入，随后 reallocateLabor 按它的 need 保留配额。
    //   ★ 两段式：planEntries 只读评估并产出意向/具名拒绝；execute 对意向**重新评估**后写工作副本。
    //   ★ 触发门槛：demands/candidates 任一为空 ⇒ 连评估都不做（空表基线逐值不变），只投递空审计。
    //   ★ 新 unit 触发 reallocateLabor 的例外集见下面的 `enteredToday`：只建了它的那个 hex 不再因它
    //     "看起来像周期第一天"而重排一个本已跑了一半的周期（既有 unit 的配额因此不被进入动作改写）。
    ctx.enteredToday = Set.of();
    ctx.entryOutcomes = List.of();
    // ★★ B2c：触发门槛 = 有需求 **且** 有"可以被采用的方案"（= production 定义里 hexKeyOf(id) 为空的那些，
    //   唯一拼写点见 EconomyEntrySettlement.plans）。★ 段首 base 的 demands/方案池在段内不被本段写
    //   （写入口只有命令面/定义写面）⇒ 读它是"静态模板"而不是"跳过当日视图"。
    ctx.planPool = EconomyEntrySettlement.plans(ctx.industries);
    ctx.planAssetSources = ctx.base.meta().map(EconomyMeta::planAssetSources).orElseGet(Map::of);
    // ★★ B3（裁定十二）：按方案声明的**劳动来源**（缺键 ⇒ SELF = 旧候选缺省，逐值不变）。
    ctx.planLaborSources = ctx.base.meta().map(EconomyMeta::planLaborSources).orElseGet(Map::of);
    if (!ctx.base.demands().isEmpty() && !ctx.planPool.isEmpty()) {
      EconomyEntrySettlement.Plan entryPlan =
          EconomyEntrySettlement.planEntries(
              ctx.session.productionRuntime(),
              ctx.base.demands(),
              ctx.industries,
              ctx.planAssetSources,
              ctx.planLaborSources,
              ctx.settlementIndex,
              MarketIndex.of(ctx.dayView),
              ctx.householdEconomies,
              ctx.householdGoods,
              ctx.householdMoney,
              ctx.composition,
              ctx.units,
              ctx.assetShares,
              ctx.laborCommitments,
              ctx.operatorConditions,
              ctx.relations,
              ctx.day);
      List<EntryOutcome> outcomes = new ArrayList<>(entryPlan.outcomes());
      if (!entryPlan.intents().isEmpty()) {
        EconomyEntrySettlement.Execution execution =
            EconomyEntrySettlement.execute(
                ctx.session.productionRuntime(),
                entryPlan.intents(),
                ctx.base.demands(),
                ctx.industries,
                ctx.planAssetSources,
                ctx.planLaborSources,
                ctx.units,
                ctx.assetShares,
                ctx.laborCommitments,
                ctx.operatorConditions,
                ctx.session.sheet().relations(),
                ctx.householdEconomies,
                ctx.householdGoods,
                ctx.householdMoney,
                ctx.composition,
                MarketIndex.of(ctx.dayView),
                ctx.day,
                ctx.session.sheet().productionRosters());
        outcomes.addAll(execution.outcomes());
        ctx.enteredToday = execution.enteredUnitIds();
        if (execution.changed()) {
          // unit/relation/份额/配额都变了 ⇒ 换一份索引再进现有日结算（进入日是低频事件，一次 O(状态) 重建）。
          ctx.relations = ctx.session.sheet().relationsOrBase();
          ctx.settlementIndex =
              SettlementIndex.build(
                  ctx.units,
                  ctx.industries,
                  ctx.assetShares,
                  ctx.laborCommitments,
                  ctx.householdEconomies,
                  ctx.debts,
                  ctx.relations,
                  ctx.session.sheet().productionOrganizations(),
                  ctx.session.sheet().assetOccupanciesOrBase(),
                  ctx.session.sheet().productionRostersOrBase(),
                  ctx.day,
                  ctx.landAcquiredThisRound());
        }
      }
      ctx.entryOutcomes = outcomes;
    }
    EntryOutcomeFeed.publish(ctx.meta.mapId(), ctx.day, ctx.entryOutcomes);
    if (!ctx.entryOutcomes.isEmpty()) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "ENTRY",
                  EconomyLogSource.ECONOMY_ENTRY,
                  "day",
                  ctx.day,
                  "outcomes",
                  ctx.entryOutcomes.size(),
                  "enteredUnits",
                  ctx.enteredToday.size(),
                  "rejected",
                  ctx.entryOutcomes.stream().filter(outcome -> !outcome.accepted()).count()));
    }
    if (TRACE.isDebugEnabled() && !ctx.entryOutcomes.isEmpty()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "ENTRY_DETAIL",
                  EconomyLogSource.ECONOMY_ENTRY,
                  "day",
                  ctx.day,
                  "outcomes",
                  ctx.entryOutcomes.size(),
                  "enteredUnits",
                  ctx.enteredToday.size()));
    }
    if (RAW.isTraceEnabled()) {
      for (EntryOutcome outcome : ctx.entryOutcomes) {
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "ENTRY_OUTCOME",
                    EconomyLogSource.ECONOMY_ENTRY,
                    "day",
                    ctx.day,
                    "household",
                    outcome.household().value(),
                    "plan",
                    outcome.plan().value(),
                    "accepted",
                    outcome.accepted(),
                    "modeKey",
                    outcome.plan().value(),
                    "industry",
                    outcome.industryId().value(),
                    "trialScale",
                    outcome.trialScale(),
                    "expectedDay",
                    outcome.expectedDay(),
                    "laborMilli",
                    outcome.laborMilli(),
                    "reason",
                    logReason(outcome.reason())));
      }
    }
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void applyTransitionsAndOrganize(SettlementDayContext ctx) {
    // 逐行当日发生额（流水的事后组装）。★ R3 起两张实物表都是**逐商品**的（{@link FlowRow#income()} 由标量改成 Map）。
    ctx.consumedGoods = new LinkedHashMap<>();
    // ★★★ W5.5（2026-10-11）：这里原有 {@code borrowing}（逐户"本日新借入粮"的累加器）—— 它**只服务借粮腿**，
    //   随该腿整段删除（AGENTS §一.11）。★ 后果具名：{@code FlowRow.newBorrowing}（毫粮口径的持久读数）从此由
    //   结算路径**恒写 0**，而货币腿借入的是**钱**（不是粮）⇒ 换标的会让那个字段的量纲变成谎话，故本批不喂它。
    //   需要"这一户这一周期借了多少钱"的读者应从 {@code debtContracts}（{@code DebtUnit.Money}）取 —— 见账本"连带物"。
    ctx.income = new LinkedHashMap<>();
    // ★★ **R5/T4：生产损耗不再有"逐行累加器"** —— 损耗不是"谁消费了"，是"蒸发了"，它只进 {@link
    //   ProductionLedger#losses()}（守恒式里自成一项，见类注 §6.1 的新式）。留一个"分了损耗但没人收"的中间残留
    //   （旧口径里它是 consumed 的一部分）会让 I4.2 的 ΣLoss 被算两遍。
    ctx.unmetToday = new LinkedHashMap<>();
    ctx.birthsToday = new LinkedHashMap<>();
    ctx.interestToday = new LinkedHashMap<>(); // 周期末计息那一笔（§7.1③）
    // ★★ **H5：周期末偿还的那一笔**（{@code FlowRow.repaid} 的第一个写入点 —— 改前它恒 0）。
    ctx.repaidToday = new LinkedHashMap<>();
    // ★★ E4c：货币债偿还的逐币种读数（**不塞进粮口径的 repaidToday**；见 FlowRow.repaidMoney）。
    ctx.repaidMoneyToday = new LinkedHashMap<>();
    // ★★ E5b：E4c 本日逐合同的本金偿还读数（压力判定"本金未下降"的具名证据；只进审计，不改状态身份）。
    ctx.repaidPrincipalByDebt = new LinkedHashMap<>();
    // ★★ E4c：本日资本化的欠租/欠薪（按 unit.key() 分组；资本化不搬库存/货币，故只进 FlowRow.capitalizedArrears）。
    ctx.capitalizedArrearsToday = new LinkedHashMap<>();
    // ★★ **当日的缺口**（"今天这一顿没吃上多少"）—— 它只是**读数**（进 unmetNeed / 报 Social 的口粮缺口）。
    //   ★★★ W5.5 起它不再触发任何借贷：借粮腿已按 S-12① 整段退役；借钱发生在市场轮里、买货之前。
    ctx.deficitToday = new LinkedHashMap<>();
    // ── 0-entry.4. ★★ E6a 模式变迁执行（理想架构 §4.4；计划 E6a）────────────────────────────────
    //   ★★ 位置：**必须在自动组织阶段之前** —— 变迁写回 EXITING/ACTIVE 组织、同一 unit 的新 modeKey 与新
    //      HouseholdClassMembership；随后 organize 以新归属做幂等检查（新组织已 ACTIVE ⇒ 不重建），retain=1000 的旧
    //      EXITING 组织也不会被重建（organize 对该状态显式跳过）。
    //   ★★ 闸门：`modeTransitions` 为空时连工作副本都不建（空表基线逐值不变）；apply 内部再判一次到期 PENDING。
    //   ★ 只对"**当刻**表里真有一条到期 PENDING"才进入：APPLIED/FAILED 的历史变迁不产生任何拷贝（跨 revision 的稳定基线）。
    //   ★★ B2：判据读**当日视图**（= 会话工作副本优先）—— 段内第 D 天执行过的变迁在第 D+1 天已是 APPLIED，
    //     读段首 base 会让它在整段里每天都"看起来到期"（每天白跑一次 apply + 白建六张工作副本），
    //     与"逐日推进"路径的行为也不同源（那边每天看到的都是前一天终态）。
    //   ★ 它只写 enterprises / units / classStandings / modeTransitions / classShares 五张工作副本。
    ctx.dueModeTransition = false;
    for (ModeTransition transition : ctx.dayView.modeTransitions().values()) {
      if (transition.status() == ModeTransition.Status.PENDING
          && transition.effectiveDay() <= ctx.day) {
        ctx.dueModeTransition = true;
        break;
      }
    }
    if (ctx.dueModeTransition) {
      EconomyModeTransitionSettlement.Outcome transitionOutcome =
          EconomyModeTransitionSettlement.apply(
              ctx.session.productionRuntime(),
              ctx.dayView,
              ctx.day,
              ctx.householdEconomies,
              ctx.session.sheet().productionOrganizations(),
              ctx.units,
              ctx.assetShares,
              ctx.session.sheet().classMemberships(),
              ctx.session.sheet().modeTransitions(),
              ctx.session.sheet().classShares());
      if (transitionOutcome.changed()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "MODE_TRANSITION",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "applied",
                    transitionOutcome.applied(),
                    "failed",
                    transitionOutcome.failed()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "MODE_TRANSITION_DETAIL",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "applied",
                    transitionOutcome.applied(),
                    "failed",
                    transitionOutcome.failed()));
      }
    }

    // ── 0-entry.5. ★★ E2 自动生产组织（理想架构 §4.2 ①；计划 E2）────────────────────────────
    //   ★★ 位置：在 **0-entry 候选采用之后、现扣投入/劳动再分配/消费之前** —— 今天新建的 unit 因此当天就能进入本周期
    //      （progressDays=0 ⇒ 现扣投入会为它扣一次料，随后劳动再分配按它的 need 保留配额）。
    //   ★★ 闸门：`modes` 为空时**这一整段不执行**（连工作副本都不建）⇒ 旧档/未接线世界的 HouseholdClassRule
    //      与全部 settle* 路径逐值不变。闸门在调用点与 organize 内各判一次（显式、可读）。
    //   ★ 自动组织只写五张工作副本：units / relations / assetShares（租佃拆分）/ allocations /
    //      productionOrganizations；不新建 IndustryDefinitionView 模板、不写 markets/rows/debts。
    ctx.enterpriseOutcome = EconomyEnterpriseSettlement.Outcome.empty();
    // ★★ B5（裁定十八）：`modes` 读日视图（production 定义权威）⇒ 本闸门在段内恒定；它判的是"这个世界接没接线 E1–E6"。
    if (!ctx.dayView.modes().isEmpty()) {
      ctx.enterpriseOutcome =
          EconomyEnterpriseSettlement.organize(
              ctx.session.productionRuntime(),
              ctx.dayView,
              // ★ E6a：变迁刚写过的 standing 工作副本优先（无变迁时 = base 的不可变表，旧路径逐值不变）。
              ctx.session.sheet().classMembershipsOrBase(),
              ctx.householdEconomies,
              ctx.industries,
              ctx.units,
              ctx.session.sheet().relations(),
              ctx.assetShares,
              ctx.laborCommitments,
              ctx.composition,
              ctx.householdGoods,
              MarketIndex.of(ctx.dayView),
              ctx.session.sheet().productionOrganizations(),
              ctx.session.sheet().productionRosters());
      if (ctx.enterpriseOutcome.changed()) {
        // unit/关系/份额/配额/组织任一变了 ⇒ 换一份索引再进现有日结算（与 0-entry 的执行后重建同款）。
        // ★ P2：条件从"新建了 unit"放宽到 changed()，确保今天新落的 SHORTAGE/ACTIVE 组织也进
        //   DebtPartyResolver 的组织视图（只有组织变化、没有新 unit 时旧索引会漏掉它们）。
        ctx.relations = ctx.session.sheet().relationsOrBase();
        ctx.settlementIndex =
            SettlementIndex.build(
                ctx.units,
                ctx.industries,
                ctx.assetShares,
                ctx.laborCommitments,
                ctx.householdEconomies,
                ctx.debts,
                ctx.relations,
                ctx.session.sheet().productionOrganizations(),
                ctx.session.sheet().assetOccupanciesOrBase(),
                ctx.session.sheet().productionRostersOrBase(),
                ctx.day,
                ctx.landAcquiredThisRound());
        // ★ 今天由组织阶段新建的 unit 加进"刚进入"豁免集：不得让它触发/参与同格既有周期的重排
        //   （与 R4-E2b 的 enteredToday 同一条口径；下一个日结算日它自然成为普通 unit）。
        Set<ProductionUnitId> withOrganized = new LinkedHashSet<>(ctx.enteredToday);
        withOrganized.addAll(ctx.enterpriseOutcome.createdUnitIds());
        ctx.enteredToday = withOrganized;
      }
      if (ctx.enterpriseOutcome.changed()) {
        EventLog.channel(TRACE)
            .info(
                LogEvent.of(
                    "ORGANIZE",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.day,
                    "createdUnits",
                    ctx.enterpriseOutcome.createdUnitIds().size(),
                    "organizations",
                    ctx.session.sheet().productionOrganizations().size(),
                    "rows",
                    ctx.householdEconomies.size()));
      }
      if (TRACE.isDebugEnabled()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "ORGANIZE_DETAIL",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.day,
                    "changed",
                    ctx.enterpriseOutcome.changed(),
                    "createdUnits",
                    ctx.enterpriseOutcome.createdUnitIds().size(),
                    "organizations",
                    ctx.session.sheet().productionOrganizations().size()));
      }
      if (RAW.isTraceEnabled() && ctx.enterpriseOutcome.changed()) {
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "ORGANIZE_RESULT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    ctx.day,
                    "createdUnits",
                    ctx.enterpriseOutcome.createdUnitIds(),
                    "organizations",
                    ctx.session.sheet().productionOrganizations().size()));
      }
    }
  }
}
