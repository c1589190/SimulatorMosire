package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>P10.2 迁移执行器（架构 §5.3 的 ⑨）</b>：<b>只执行</b> {@link ModeMigrationPolicy.MigrationPlan} 里已确定的
 * move，不重算利润、不改计划。
 *
 * <pre>
 * 逐 move：
 *   ① 源/目标行解析；目标已有 ⇒ 合并；目标缺失 ⇒ 新建（HouseholdEconomy + HouseholdClassMembership + 账户 + 组织/unit/关系 + 租赁资产）
 *   ② 人口/劳动/劳动配额按人口比例推进**迁移投影账**（逐笔 floor；源户清空时余数随最后一笔走）——
 *      ★★ P0 起不写 householdEconomies 行的 population/laborMilli；每笔成功落账后追加 outbox（EconomyPopulationTransfer），
 *      由 App 在同一 revision 内翻译成 Social 工单并把 Social 真值 delta 回写经济行
 *   ③ 货币按**全部币种**逐项移动（D-023：逐币种按人口比例 floor，余数留源/迁空随最后一笔；不做 FX；只搬余额，不新造）
 *   ④ 债务逐合同走 DebtContractBook.reduce/upsert（唯一写口）；计划金额全部分摊，绝不静默丢债。
 *      ★★ D3：**债权人恰是迁入目标**的那部分 ⇒ 按自债显式净额（同 {@code DebtPartyResolver} 口径），
 *      不落合同、不留在源户；INFO 事件 + 当天 ledger 具名读数，净值不变、金额可见
 *   ⑤ 资产随迁（D-023）：源户自有资产按**逐笔迁移人口比例**随迁 —— 可移动资产（SMALL_TOOL/LARGE_TOOL/SHIP/CATTLE/MACHINE）
 *      同 hex 同产业直接拆份额、跨 hex 在目标产业的同 AssetKind 下重建；不可移动资产（LAND/WORKSHOP）同 hex 可换主人，
 *      跨 hex 留原户并记具名读数。绝不再走"整户消亡时把资产全给最后目标"的旧路
 *   ⑥ 源户**计划**人口归零：钱/债必须为 0；组织/unit/关系/劳动配额照旧退役；**始终保留 0 人口壳行**
 *      （HouseholdEconomy + HouseholdClassMembership + FlowRow，不删行/不删 FlowRow/不摘 crisisSignal 引用），
 *      实际行 population 由 App 按 outbox delta 回写为 0
 * </pre>
 *
 * <p>★★ <b>D-022 硬不变量</b>：源户的 {@code HouseholdClassMembership} / {@code
 * ProductionEnterprise.modeId} / {@code ProductionProcess.modeKey} 在本类里<b>一字不改</b>；目标 mode
 * 只出现在目标家户（已有或新建）上。
 *
 * <p>★★ <b>P0 人口权威（2026-10-10）</b>：Economy 不再把人写进 {@code HouseholdEconomy.population} 当权威。
 * 投影账只服务"本笔搬多少、按什么比例搬钱/债/资产/劳动配额"；人的最终落点由 Social 工单决定，经济行人口是 App 回写的物化视图。{@link
 * EconomyPopulationTransfer} 是两条腿之间唯一的瞬态接口。
 *
 * <p>★★ <b>失败具名抛</b>：不静默丢人/丢债/丢钱；整段写入发生在同一个 {@link EconomySession}/revision 内。
 */
public final class ModeMigrationSettlement {

  /** 迁移执行日志（migration 分类：按需建商号 / 凑不出牲口的具名跳过，§一.9）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private ModeMigrationSettlement() {}

  /**
   * 执行一份计划；空计划 ⇒ 一字不改。★ 无 {@link ProductionLedger} 的旧调用点（单模块用例）走本重载：资产随迁照做，
   * 只少“留原户资产”的当天具名审计（资产本身仍逐行留在状态里）。
   */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ModeMigrationPolicy.MigrationPlan plan,
      EconomyDayView base,
      long day) {
    apply(session, accounts, plan, base, day, null);
  }

  /**
   * 执行一份计划；空计划 ⇒ 一字不改。{@code auditLedger} 可为 null（无审计口）；非 null 时把 “留原户的不可移动/不可用资产”与“关系模板回退”记进当天的
   * {@link ProductionLedger}（瞬态读数，不新增持久组件）。
   */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ModeMigrationPolicy.MigrationPlan plan,
      EconomyDayView base,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(base, "base");
    if (day < 0L) {
      throw new IllegalArgumentException("ModeMigrationSettlement.apply 的 day 不得为负: " + day);
    }
    if (plan.isEmpty() || base.modes().isEmpty()) {
      return;
    }
    Map<HouseholdId, HouseholdEconomy> householdEconomies = session.sheet().householdEconomies();
    // ★★ P0（2026-10-10）：迁移投影账（跨全部 source 共享）——迁移只推进这两本账，
    //    绝不写 householdEconomies 行的 population/laborMilli；App 在 step(day) 之后按 outbox 把 Social
    //    真值 delta 回写经济行。初值 = 现有行值（含本批之前已创建的行；迁移中新建的目标行在创建处补 0 起步）。
    LinkedHashMap<HouseholdId, Long> plannedPopulation = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Long> plannedLabor = new LinkedHashMap<>();
    for (HouseholdEconomy row : householdEconomies.values()) {
      plannedPopulation.put(row.id(), row.population());
      plannedLabor.put(row.id(), row.laborMilli());
    }
    // ★★ P0 防回归（§7.2 第 4 条）：apply 结束时校验这些**已有**行的实际人口/劳动一字未改
    //    （本类只允许写投影账；新建目标行不在快照里）。这条例行守卫把"经济域写人"挡在运行时。
    LinkedHashMap<HouseholdId, Long> initialPopulation = new LinkedHashMap<>(plannedPopulation);
    LinkedHashMap<HouseholdId, Long> initialLabor = new LinkedHashMap<>(plannedLabor);
    LinkedHashMap<HouseholdId, HouseholdClassMembership> classMemberships =
        session.sheet().classMemberships();
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    LinkedHashMap<AssetShareId, OwnershipStake> assetShares = session.sheet().assetShares();
    LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises =
        session.sheet().productionOrganizations();
    // ★★ B2b（§13.11.4 第 4 条）：本方法的实例**写侧**（退役/新建，见下面两处）**直调 production 契约口**
    //   （`removeInstance` / `createInstance`）—— `unitsWorkCopy` 写回桥与 `flushUnitWorkCopy` 已整体删除。
    //   ★ 这里读的 `units` 是 production 候选态的**真只读投影**：新建/退役后当刻即可见（零拷贝、不缓存），
    //     故本方法内部后续的读判（如"unit id 是否已存在"）与写入结果一致。
    Map<ProductionUnitId, ProductionProcess> units = session.sheet().units();
    LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
        operatorConditions = session.sheet().operatorConditions();
    LinkedHashMap<ProductionUnitId, ProductionRules> relations = session.sheet().relations();
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<PledgeId, Pledge> pledges = session.sheet().pledges();
    LinkedHashMap<ClassShareId, ClassShare> classShares = session.sheet().classShares();
    Map<DemandId, HouseholdDemand> householdDemands = base.demands();

    // ★★★ W7（2026-10-11）：**跑商自己长出来** —— 把"目标格还没有 merchant 产业模板"的迁移笔挑出来，
    //   按需为它创建 {@code trade@hex} 记录（唯一拼写点 = {@link MerchantTradeTemplate}，与创世播种逐字段同值；
    //   与决策层的可行性预估**同一个模板**）。
    //
    //   ★★ **不凭空造资产**：模板只是配方，真正让商号跑起来的是**该户自备/随迁携带**的作业资本
    //     （★ W7-fix 起 = **工具**，改前 = 牲口；D-023 §4 可移动资产随迁）。⇒ 执行前先按"这一笔实际能带过来
    //     多少作业资本"**具名过滤**：带不来的笔**整笔跳过**（INFO 具名），绝不建一间凑不齐作业资本的空商号，
    //     也绝不让 tick 在 {@code createEnterpriseAndProcess} 里当场抛（那会把整个世界卡死）。
    LinkedHashMap<IndustryId, IndustryDefinitionView> industries = session.sheet().industries();
    LinkedHashSet<HexCoord> onDemandTradeHexes = new LinkedHashSet<>();
    for (ModeMigrationPolicy.MigrationMove move : plan.moves()) {
      if (DefaultProductionModes.MERCHANT.equals(move.targetMode())
          && ModeMigrationPolicy.industriesForMode(industries, move.targetHex(), move.targetMode())
              .isEmpty()) {
        onDemandTradeHexes.add(move.targetHex());
      }
    }
    List<ModeMigrationPolicy.MigrationMove> acceptedMoves =
        filterOnDemandMerchantMoves(
            plan.moves(),
            onDemandTradeHexes,
            householdEconomies,
            assetShares,
            enterprises,
            units,
            base,
            day);
    // 逐 hex 建一次（键序 = 首次出现序 = 计划里的迁移序 ⇒ 确定性 I7）。
    LinkedHashMap<HexCoord, IndustryId> createdTradeIndustries = new LinkedHashMap<>();
    for (ModeMigrationPolicy.MigrationMove move : acceptedMoves) {
      if (!onDemandTradeHexes.contains(move.targetHex())
          || createdTradeIndustries.containsKey(move.targetHex())) {
        continue;
      }
      IndustryDefinitionView created = MerchantTradeTemplate.industry(move.targetHex());
      // ★★★ B8（缺陷 DEF-T2b-1）：按需长出来的 `trade@hex` 定义**必须先立进 production 的方案权威**，
      //   否则下面 `createEnterpriseAndProcess` 的 `runtime.createInstance(...)` 恒被具名拒 `plan-unknown`
      //   （`createInstance` 对"方案不存在"**不隐式建定义**，见 `ProductionRuntime#registerPlan` 的契约注释
      //   「先 registerPlan(定义) 把它立进方案权威，再 createInstance(请求) 引用它」）
      //   ⇒ "非城市格按需长出跑商"整条玩法不可用。
      //   ★ 正确样板 = 同模块 `EconomyEntrySettlement:1103-1110`：`registerPlan` → 判
      //     `ProductionPlanOutcome.Rejected`（具名、不降级）→ 再 `createInstance`。
      //   ★ 登记是**幂等**的（同 id 逐值相同 ⇒ no-op），故本循环在"同一次 apply 里第二次走到同一格"时无副作用；
      //     同 id 不同值 ⇒ `registerPlan` 自己抛 `ProductionContractFault`（契约故障，不静默覆盖）。
      io.mosire.simos.production.contract.ProductionPlanOutcome registered =
          session.productionRuntime().registerPlan(created);
      if (registered
          instanceof io.mosire.simos.production.contract.ProductionPlanOutcome.Rejected rejected) {
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "MERCHANT_TRADE_INDUSTRY_REGISTER_REJECTED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "hex",
                    move.targetHex(),
                    "industry",
                    created.id().value(),
                    "reason",
                    rejected.reason()));
        throw new IllegalStateException(
            "按需跑商产业定义被 production 拒绝登记（跨切片不一致，不降级）: industry="
                + created.id().value()
                + " reason="
                + rejected.reason());
      }
      // ★ 会话覆盖层仍要写：让**同一段后续的经济侧读者**（关系模板 / 组织 / 收获）当刻看得见这份定义；
      //   方案数值的权威仍是上面登记进 production 的那一份（这行只是 economy 侧的当刻可见性）。
      industries.put(created.id(), created);
      createdTradeIndustries.put(move.targetHex(), created.id());
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MERCHANT_TRADE_INDUSTRY_CREATED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "hex",
                  move.targetHex(),
                  "industry",
                  created.id().value(),
                  "regime",
                  created.regime().value(),
                  "cycleDays",
                  created.cycleDays(),
                  // ★ W7-fix：作业资本那一维从 CATTLE 换成 SMALL_TOOL（字段名随之改名，§一.11 不留旧名）——
                  //   读的是模板的 capacityPerUnit（键集与每单位量都只有模板一处拼写）。
                  "toolPerUnitMilli",
                  created
                      .recipe()
                      .capacityPerUnit()
                      .getOrDefault(MerchantTradeTemplate.capacityKinds().get(0), 0L),
                  "reason",
                  "on-demand-trade-for-migration-no-template"));
    }
    Map<HouseholdId, List<ModeMigrationPolicy.MigrationMove>> bySource = new LinkedHashMap<>();
    for (ModeMigrationPolicy.MigrationMove move : acceptedMoves) {
      bySource.computeIfAbsent(move.source(), ignored -> new ArrayList<>()).add(move);
    }
    List<HouseholdId> sources = new ArrayList<>(bySource.keySet());
    sources.sort(Comparator.comparing(HouseholdId::value));
    // ★★ D-023：本一次 apply 内被“新建目标户”创建的家户集合（跨源共享）——后续源并入同一新户时，
    //    也要把随迁资产份额补进它的组织 assetSources（见 attachMigratedSharesToNewTargetEnterprises）。
    Set<HouseholdId> createdTargets = new LinkedHashSet<>();
    for (HouseholdId source : sources) {
      applySource(
          source,
          session,
          bySource.get(source),
          householdEconomies,
          plannedPopulation,
          plannedLabor,
          classMemberships,
          laborCommitments,
          assetShares,
          enterprises,
          units,
          operatorConditions,
          relations,
          debts,
          accounts,
          pledges,
          classShares,
          householdDemands,
          base,
          day,
          createdTargets,
          auditLedger,
          industries);
    }
    // ★★ P0 防回归：已有经济行的 population/laborMilli 在本次 apply 前后必须逐值相等。
    for (Map.Entry<HouseholdId, Long> entry : initialPopulation.entrySet()) {
      HouseholdEconomy row = householdEconomies.get(entry.getKey());
      if (row == null) {
        throw new IllegalStateException("迁移不得删除已有经济行（P0：源户人口归零留 0 人口壳行）: " + entry.getKey());
      }
      long laborBefore = initialLabor.get(entry.getKey());
      if (row.population() != entry.getValue() || row.laborMilli() != laborBefore) {
        throw new IllegalStateException(
            "迁移不得写经济行 population/laborMilli（P0：只推进投影账，人口由 App 按 outbox 回写）:"
                + " household="
                + entry.getKey()
                + " populationBefore="
                + entry.getValue()
                + " populationAfter="
                + row.population()
                + " laborBefore="
                + laborBefore
                + " laborAfter="
                + row.laborMilli());
      }
    }
  }

  // ── 一个源户的全部 move ────────────────────────────────────────────────────────────────────

  private static void applySource(
      HouseholdId source,
      EconomySession session,
      List<ModeMigrationPolicy.MigrationMove> moves,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<HouseholdId, Long> plannedPopulation,
      LinkedHashMap<HouseholdId, Long> plannedLabor,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
          operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      LinkedHashMap<PledgeId, Pledge> pledges,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, HouseholdDemand> householdDemands,
      EconomyDayView base,
      long day,
      Set<HouseholdId> createdTargets,
      ProductionLedger.Accumulator auditLedger,
      Map<IndustryId, IndustryDefinitionView> industries) {
    HouseholdEconomy sourceHouseholdEconomy = householdEconomies.get(source);
    if (sourceHouseholdEconomy == null) {
      throw new IllegalStateException("迁移源家户不存在（拒绝静默丢人）: " + source);
    }
    ActorRef sourceActor = HouseholdActors.of(source);
    HexCoord sourceHex = sourceHouseholdEconomy.view().hex();
    // ★★ P0：源户人口/劳动一律取投影账（初值 = 行值；前面 source 的迁移已把自己的账推进到最新）。
    Long plannedSourcePopulation = plannedPopulation.get(source);
    Long plannedSourceLabor = plannedLabor.get(source);
    if (plannedSourcePopulation == null || plannedSourceLabor == null) {
      throw new IllegalStateException("迁移源家户不在投影账里（拒绝静默丢人）: " + source);
    }
    long sourcePopulation = plannedSourcePopulation;
    long movedPopulation = 0L;
    for (ModeMigrationPolicy.MigrationMove move : moves) {
      if (!move.source().equals(source)) {
        throw new IllegalStateException("move 的 source 与分组键不一致: " + move);
      }
      movedPopulation = Math.addExact(movedPopulation, move.population());
    }
    if (movedPopulation > sourcePopulation) {
      throw new IllegalStateException(
          "迁移人口超过源户人口（拒绝抽成负人口）: source="
              + source
              + " move="
              + movedPopulation
              + " population="
              + sourcePopulation);
    }

    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> sourceLaborCommitments =
        new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.household().equals(source) && laborCommitment.laborMilli() > 0L) {
        sourceLaborCommitments.put(laborCommitment.id(), laborCommitment);
      }
    }
    // ★★ Z3b/C7：GOV_SERVICE 承诺不可缩、最高优先级 —— 迁移会按人口比例缩/搬动劳动配额，对 GOV_SERVICE 是静默契约破坏
    //   （Z3a 遗留）。官吏户随 GOV 单位（unit.PlaceAt）而不是生产方式迁移；一旦计划要搬它，整次迁移具名 ERROR + fail-closed，
    //   绝不缩小、绝不改 activity 到目标生产 unit。
    for (HouseholdLaborCommitment govService : sourceLaborCommitments.values()) {
      if (govService.kind() == LaborCommitmentKind.GOV_SERVICE) {
        refuseGovServiceMigration(source, govService, day);
      }
    }
    List<LaborAllocationId> sourceAllocationIds = new ArrayList<>(sourceLaborCommitments.keySet());
    sourceAllocationIds.sort(Comparator.comparing(LaborAllocationId::value));

    // ★★ D-023：源户自有资产快照（owner == 源户 actor；operator 可能是别人 —— 那是出租，不搬别人的所有者份额）。
    //    逐笔迁移只在这份快照上按当前剩余量切，绝不递归处理随迁后新生成的目标份额。
    List<AssetShareId> sourceOwnedAssetIds = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (share.quantity() > 0L && share.owner().equals(sourceActor)) {
        sourceOwnedAssetIds.add(share.id());
      }
    }
    sourceOwnedAssetIds.sort(Comparator.comparing(AssetShareId::value));
    // 已被别的组织（organizer != 源户）实际使用的份额：移动会改 operator、拆掉那个组织的引用 ⇒ 本批留原户并具名。
    Set<AssetShareId> foreignUsedShareIds = foreignUsedOwnershipStakeIds(enterprises, sourceActor);
    // ★ 2026-10-09：ACTIVE 质押不再排除随迁 —— OwnershipStakeBook 会把质押按比例跟到目标户的新份额（含跨 hex rebuild）。
    Map<AssetShareId, String> residualReasons = new LinkedHashMap<>();
    Set<AssetShareId> movedWholeShareIds = new LinkedHashSet<>();
    Map<HouseholdId, List<AssetShareId>> migratedShareIdsByTarget = new LinkedHashMap<>();

    long populationLeft = sourcePopulation;
    long laborLeft = plannedSourceLabor;
    long movedLaborTotal = 0L;
    for (int index = 0; index < moves.size(); index++) {
      ModeMigrationPolicy.MigrationMove move = moves.get(index);
      long populationBefore = populationLeft;
      boolean empties = move.population() == populationBefore;
      HouseholdEconomy targetHouseholdEconomy = householdEconomies.get(move.target());
      boolean newTarget = targetHouseholdEconomy == null;
      ProductionUnitId targetUnit = null;
      ClassPositionId newTargetPosition = null;
      if (!newTarget) {
        HouseholdClassMembership targetClassMembership = classMemberships.get(move.target());
        if (targetClassMembership == null) {
          throw new IllegalStateException(
              "合并目标家户没有 ClassStanding（说不出它的 mode，拒绝静默改人）: " + move.target());
        }
        ProductionRole targetPosition =
            base.classPositions().get(targetClassMembership.currentPositionId());
        if (targetPosition == null || !targetPosition.modeId().equals(move.targetMode())) {
          throw new IllegalStateException(
              "合并目标家户的当前 mode 与计划 targetMode 不一致（拒绝把源户并错 mode）: target="
                  + move.target()
                  + " plan="
                  + move.targetMode()
                  + " actual="
                  + (targetPosition == null ? "<无此位置>" : targetPosition.modeId()));
        }
        ProductionEnterprise targetOrg =
            enterpriseOf(move.target(), householdEconomies, enterprises);
        targetUnit =
            targetOrg != null && targetOrg.unitId().isPresent() ? targetOrg.unitId().get() : null;
      } else {
        // ★★ P0：需要 HouseholdEconomy 实例的调用点传“投影行视图”（只读参数，不 put 回表）——
        //   源户在本笔之前的迁移已在投影账里，视图必须带投影值，不能回头读实际行。
        HouseholdEconomy sourceProjection =
            sourceHouseholdEconomy.withPopulationAndLabor(
                plannedPopulation.get(source), plannedLabor.get(source));
        newTargetPosition =
            createNewHousehold(
                move,
                sourceProjection,
                householdEconomies,
                classMemberships,
                accounts,
                base,
                day,
                industries);
        // ★★ P0：新建目标行人口/劳动 0 起步，先登记进投影账（跨 source 共享）；
        //   实际行由 App 按 outbox 的 +pop delta 回写（newTarget=true ⇒ Social 侧对应 CREATE_HOUSEHOLD）。
        plannedPopulation.putIfAbsent(move.target(), 0L);
        plannedLabor.putIfAbsent(move.target(), 0L);
        // ★★ 新建目标户后必须刷新行引用：createNewHousehold 只把新行写进 rows，
        //    不改变本方法早先捕获的 targetRow（原为 null）。不刷新 ⇒ 下一段 NPE。
        targetHouseholdEconomy = householdEconomies.get(move.target());
        if (targetHouseholdEconomy == null) {
          throw new IllegalStateException("新建目标家户后行表里仍无该行（拒绝静默丢人）: " + move.target());
        }
      }

      // ② 人口/劳动：只推进投影账（跨 source 共享），**不写** householdEconomies 行的
      //    population/laborMilli —— 实际行由 App 在 step(day) 之后按 outbox delta 回写 Social 真值。
      long popTake = move.population();
      long laborTake =
          empties
              ? laborLeft
              : Math.multiplyExact(laborLeft, popTake) / Math.max(1L, populationBefore);
      if (laborTake > laborLeft) {
        throw new IllegalStateException(
            "迁移劳动超过源户剩余劳动（拒绝抽成负劳动）: source=" + source + " take=" + laborTake);
      }
      Long targetPlannedPopulationBox = plannedPopulation.get(move.target());
      Long targetPlannedLaborBox = plannedLabor.get(move.target());
      if (targetPlannedPopulationBox == null || targetPlannedLaborBox == null) {
        // 防御：本批之外已在工作副本里的目标行（正常路径不会发生；新目标行在上面已登记 0）。
        targetPlannedPopulationBox = targetHouseholdEconomy.population();
        targetPlannedLaborBox = targetHouseholdEconomy.laborMilli();
        plannedPopulation.putIfAbsent(move.target(), targetPlannedPopulationBox);
        plannedLabor.putIfAbsent(move.target(), targetPlannedLaborBox);
      }
      plannedPopulation.put(source, Math.subtractExact(plannedPopulation.get(source), popTake));
      plannedLabor.put(source, Math.subtractExact(plannedLabor.get(source), laborTake));
      plannedPopulation.put(move.target(), Math.addExact(targetPlannedPopulationBox, popTake));
      plannedLabor.put(move.target(), Math.addExact(targetPlannedLaborBox, laborTake));
      populationLeft = plannedPopulation.get(source);
      laborLeft = plannedLabor.get(source);
      movedLaborTotal = Math.addExact(movedLaborTotal, laborTake);
      if (newTarget) {
        // ★★ D-023：本一次 apply 内新建的目标户要在后续源并入时也补记随迁资产引用（见 apply 顶层注释）。
        createdTargets.add(move.target());
      }
      // ★★ D-023 资产随迁：必须在目标组织/unit 建立之前完成 —— 组织 assetSources 要指向刚生成的目标份额，
      //    且新建户的“随迁资产已覆盖多少产能”要参与 planAssetMoves 的缺口计算（避免同一份资产又被租一遍）。
      AssetMigrationOutcome assetOutcome =
          migrateAssetsForMove(
              sourceActor,
              sourceHex,
              move,
              populationBefore,
              popTake,
              empties,
              sourceOwnedAssetIds,
              assetShares,
              base,
              pledges,
              foreignUsedShareIds,
              movedWholeShareIds,
              residualReasons,
              industries);
      if (!assetOutcome.createdIds().isEmpty()) {
        migratedShareIdsByTarget
            .computeIfAbsent(move.target(), ignored -> new ArrayList<>())
            .addAll(assetOutcome.createdIds());
      }
      if (newTarget && !DefaultProductionModes.DISPLACED.equals(move.targetMode())) {
        // ★ 组织/unit/资产必须在目标行带上迁移人口/劳动之后建（规模 = 劳动/工艺需求，不能拿 0 劳动建）；
        //   随迁份额（assetOutcome）进 assetSources，并在 planAssetMoves 里抵减目标产业容量需求。
        //   ★★ P0：传**投影行视图**（带投影人口/劳动），只读参数，不 put 回 householdEconomies。
        targetUnit =
            createEnterpriseAndProcess(
                session.productionRuntime(),
                move,
                industries,
                householdEconomies,
                targetHouseholdEconomy.withPopulationAndLabor(
                    plannedPopulation.get(move.target()), plannedLabor.get(move.target())),
                newTargetPosition,
                assetShares,
                units,
                relations,
                enterprises,
                base,
                pledges,
                assetOutcome.createdIds(),
                assetOutcome.coverage(),
                day,
                auditLedger);
      }

      // ★ P2-A A3：成员份额已迁 Social —— 本类只改 HouseholdEconomy.population/劳动/资产/货币；家户成员怎么随迁
      //   由跨切片协调器（P8/P9 接线）写回 Social 的 Household.members，本批如实记为缺口。
      // 劳动配额：逐笔 floor，清空时余数随最后一笔
      for (LaborAllocationId allocationId : sourceAllocationIds) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
        if (laborCommitment == null || laborCommitment.laborMilli() <= 0L) {
          continue;
        }
        long take =
            empties
                ? laborCommitment.laborMilli()
                : Math.multiplyExact(laborCommitment.laborMilli(), popTake)
                    / Math.max(1L, populationBefore);
        take = Math.min(take, laborCommitment.laborMilli());
        if (take <= 0L) {
          continue;
        }
        if (take == laborCommitment.laborMilli()) {
          laborCommitments.remove(allocationId);
        } else {
          laborCommitments.put(
              allocationId,
              new HouseholdLaborCommitment(
                  laborCommitment.id(),
                  laborCommitment.group(),
                  laborCommitment.household(),
                  laborCommitment.actor(),
                  laborCommitment.activity(),
                  laborCommitment.laborMilli() - take,
                  laborCommitment.period(),
                  laborCommitment.kind(),
                  laborCommitment.productionRole()));
        }
        if (!DefaultProductionModes.DISPLACED.equals(move.targetMode()) && targetUnit != null) {
          // ★★ P0：传投影行视图（带投影人口/劳动），只读参数；本方法只做存在性校验。
          moveLaborCommitmentToTarget(
              laborCommitment,
              move.target(),
              targetUnit,
              take,
              laborCommitments,
              targetHouseholdEconomy.withPopulationAndLabor(
                  plannedPopulation.get(move.target()), plannedLabor.get(move.target())));
        }
      }

      // ★★★ D-032 D2 §9.3：整户迁空时合并名册（同 role 计划劳动/权重相加、rights 并集、organizer OR）。
      //   organizer 合户必须走 Phase M 的
      // `production.RebindInstanceOperator`（unit.operator/enterprise/relation
      //   都是持久字段）；本类只移动名册引用，遇到 organizer 迁出时具名拒绝，绝不留下半状态。
      if (empties) {
        LinkedHashMap<ProductionUnitId, ProductionRoster> rosters =
            session.sheet().productionRosters();
        for (ProductionRoster roster : new ArrayList<>(rosters.values())) {
          if (!roster.participantsOf(source).isEmpty()
              && roster.organizerHousehold().equals(source)) {
            throw new IllegalStateException(
                "organizer-merge-requires-rebind: organizer 合户必须走 production.RebindInstanceOperator: unit="
                    + roster.unit().value()
                    + " source="
                    + source.value()
                    + " target="
                    + move.target().value());
          }
        }
        Map<ProductionUnitId, ProductionRoster> mergedRosters =
            ProductionRosters.mergeHousehold(rosters, source, move.target());
        rosters.clear();
        rosters.putAll(mergedRosters);
      }

      // ③ 货币（★★ D-023：源户全部币种逐项随迁；不做 FX、不做兑换）
      if (!move.moneyByCurrency().isEmpty()) {
        for (Map.Entry<CurrencyId, Long> leg : move.moneyByCurrency().entrySet()) {
          if (leg.getValue() > 0L) {
            moveMoney(accounts, source, move.target(), leg.getKey(), leg.getValue());
          }
        }
      } else {
        // 旧/手工计划的空 map ⇒ 就地按人口比例补算全部币种；计划器产出的 move 一律带权威 map。
        moveAllCurrenciesByPopulation(
            accounts, source, move.target(), populationBefore, popTake, empties);
      }

      // ③b ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 1 条「默认带一部分库存过去」）：**可移动商品库存随迁**。
      //   口径照 D-023 §4 的既有形制（与货币/资产同式）：按**逐笔迁移人口比例** ⌊剩余量 × 比例⌋，余数留源户；
      //   整户迁空（empties）⇒ 余数随这一笔走（不留悬空库存）。写路径 = 唯一写口
      //   {@code EconomySettlement.applyTransfer}（守恒：源减 = 目标增，总量一字不变）。
      carryGoodsForMove(
          accounts,
          source,
          move.target(),
          move.population(),
          populationBefore,
          empties,
          move.targetHex(),
          day,
          auditLedger,
          index);

      // ④ 债务（计划金额分摊到源户合同；目标不新建组织）
      if (move.debtMilli() > 0L) {
        moveDebt(move, debts, day, auditLedger);
      }

      // ★★★ D-032 B3（I10 / §13.13.7 第 6 条）：**人口迁移完整携带各经验条**（主值 + 余数、**不**乘人口比例），
      //   到迁入户后按**人口加权平均**合并（V6 的用户例式：来源 60 的 10 人 + 原有 30 人经验 20 ⇒ 30）。
      //   ★ 唯一权威在 production（`mergeExperience` 契约口；economy 只给人口事实，不碰经验账）——
      //     见 `ProductionData.mergeExperienceInto` 的逐条口径与自证。
      //   ★ 财债那一半（资产/货币/商品/债务）已由 D-023/W9 在本方法内**按迁移人口比例逐项切分并相加**，
      //     不在这里重做（见上面 migrateAssetsForMove / moveMoney / carryGoodsForMove / moveDebt 四段）。
      io.mosire.simos.production.contract.ProductionExperienceMoveOutcome experienceMove =
          session
              .productionRuntime()
              .mergeExperience(source, popTake, move.target(), targetPlannedPopulationBox);
      if (experienceMove.changed() && EconomyLog.migration().isDebugEnabled()) {
        EventLog.channel(EconomyLog.migration())
            .debug(
                LogEvent.of(
                    "MIGRATION_EXPERIENCE_MERGED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "source",
                    source.value(),
                    "target",
                    move.target().value(),
                    "movedPeople",
                    popTake,
                    "targetPeople",
                    targetPlannedPopulationBox,
                    "tracksMerged",
                    experienceMove.tracksMerged(),
                    "unlocksAdded",
                    experienceMove.unlocksAdded(),
                    "day",
                    day,
                    "note",
                    "迁移完整携带（含余数、不乘比例）+ 合户按人口加权平均；解锁并集（I7）"));
      }

      // ★★ P0：本笔 move 校验/落账全部成功后追加 outbox（人口事实），由 App 在同一 revision 内
      //    翻译成 Social 工单、再把 Social 真值 delta 回写经济行。outbox 是瞬态：整次 advance 失败 ⇒ 会话丢弃。
      EconomyPopulationTransfer populationTransfer =
          new EconomyPopulationTransfer(
              source,
              move.target(),
              popTake,
              move.targetHex(),
              move.targetMode(),
              newTarget,
              move.reason());
      session.recordPopulationTransfer(populationTransfer);
      if (EconomyLog.migration().isTraceEnabled()) {
        EventLog.channel(EconomyLog.migration())
            .trace(
                LogEvent.of(
                    "MIGRATION_POPULATION_OUTBOX",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    source.value(),
                    "target",
                    move.target().value(),
                    "population",
                    popTake,
                    "targetHex",
                    move.targetHex().q() + "," + move.targetHex().r(),
                    "targetMode",
                    move.targetMode().value(),
                    "newTarget",
                    newTarget,
                    "reasonLength",
                    move.reason() == null ? 0 : move.reason().length()));
      }

      if (empties) {
        break;
      }
    }

    // ★★ D-023：整条随迁的源份额旧 id 已在 OwnershipStakeBook.apply/rebuild 里删除 ⇒ 源户组织 assetSources
    //    必须同步删掉这些 id（不悬空）；部分随迁的份额 id 仍在、数量已减，无需改引用。
    removeSourceEnterpriseAssetSources(enterprises, sourceActor, movedWholeShareIds);
    // ★★ D-023：目标户若是本一次 apply 新建的（含后续源并入），把随迁新份额 id 补进它的组织 assetSources。
    attachMigratedSharesToNewTargetEnterprises(
        createdTargets, migratedShareIdsByTarget, householdEconomies, assetShares, enterprises);
    // ★★ D-023：跨 hex 不可移动 / 目标 hex 无法承载 / 已出租给他组织 / 已质押 ⇒ 留原户的具名读数
    //    （瞬态 ProductionLedger；不新增持久组件，不改数量）。
    recordResidualAssetAudits(auditLedger, day, source, assetShares, residualReasons);

    // ⑤ 源户计划人口归零 / 缩编
    //   ★★ P0：判据取**投影账**，不读实际行人口（实际行人口由 App 同 revision 按 outbox 回写）。
    Long plannedSourcePopulationFinal = plannedPopulation.get(source);
    if (plannedSourcePopulationFinal == null) {
      throw new IllegalStateException("迁移源家户不在投影账里（拒绝静默丢人）: " + source);
    }
    if (plannedSourcePopulationFinal == 0L) {
      retireSource(
          source,
          session,
          householdEconomies,
          plannedPopulation,
          classMemberships,
          laborCommitments,
          assetShares,
          enterprises,
          units,
          operatorConditions,
          relations,
          debts,
          classShares,
          householdDemands,
          accounts,
          pledges,
          day);
    }
  }

  /**
   * 源户计划人口清零后的清点与退役：钱/债必须为 0；组织/unit/关系/劳动配额照旧退役（商号行已随 M-A1 退役）。
   *
   * <p>★★ <b>P0（2026-10-10）：始终保留 0 人口壳行</b> —— {@code HouseholdEconomy} + {@code FlowRow} + {@code
   * HouseholdClassMembership} 一律不删（不再区分"有无资产/合同残留"），不摘 crisisSignal 引用；实际行的 {@code population} 由
   * App 在同一 revision 内按 outbox delta 回写成 0。{@code plannedPopulation} 只用于 判"源户计划归零"这一入口条件。
   */
  private static void retireSource(
      HouseholdId source,
      EconomySession session,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Long> plannedPopulation,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
          operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, HouseholdDemand> householdDemands,
      AccountSession accounts,
      Map<PledgeId, Pledge> pledges,
      long day) {
    long residualMoney = 0L;
    for (long amount : accounts.householdMoney().getOrDefault(source, Map.of()).values()) {
      residualMoney = Math.addExact(residualMoney, amount);
    }
    long residualDebt = 0L;
    List<String> residualContracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        residualDebt = Math.addExact(residualDebt, contract.principal());
        if (residualContracts.size() < 8) {
          residualContracts.add(
              contract.id().value()
                  + "->creditor:"
                  + contract.creditor().value()
                  + ":"
                  + contract.principal());
        }
      }
    }
    if (residualMoney != 0L || residualDebt != 0L) {
      // ★★ 2026-10-09 D3：具名 ERROR 必须带"残留在哪几笔合同、债权人是谁、金额多少"——只报总额时，
      //   长跑现场无法判断是哪条迁移路径把债留在了源户（控制方 2026-10-09 复现时正只有一句
      //   `error=IllegalStateException`，正文被 GUI 截掉）。
      EventLog.channel(EconomyLog.migration())
          .error(
              LogEvent.of(
                  "MIGRATION_SOURCE_RESIDUAL_REFUSED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "source",
                  source.value(),
                  "money",
                  residualMoney,
                  "debt",
                  residualDebt,
                  "contracts",
                  residualContracts,
                  "reason",
                  "empty-source-still-holds-money-or-debt"));
      throw new IllegalStateException(
          "迁移后源户人口为 0 但仍有货币/债务残留（拒绝消亡丢账）: source="
              + source
              + " money="
              + residualMoney
              + " debt="
              + residualDebt
              + " contracts="
              + residualContracts);
    }
    // ★★ 同键残留检查（债务侧）：源户作为债务人的合同，本步已由 moveDebt 按 §5.3 把本金迁到目标户；
    //   仍有正本金 ⇒ 上面已具名抛。剩下的 0 本金行只是"本金已迁走"的空壳容器，不是债；
    //   而 EconomyData 的跨表守卫仍要求 debtor 家户存在 ⇒ 不处理就会把源户钉住、无法按"人口归零"消亡。
    //   ★ 只删没有质押指名的空壳：有质押 ⇒ 质押表要求合同端点仍存在，保留合同与空壳；
    //     债务价值守恒由 moveDebt 的 target upsert 保证，删 0 本金行不减少任何本金。
    List<DebtContractId> zeroDebtorContracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() == 0L) {
        zeroDebtorContracts.add(contract.id());
      }
    }
    for (DebtContractId contractId : zeroDebtorContracts) {
      boolean pledged = false;
      for (Pledge pledge : pledges.values()) {
        if (pledge.debtContractId().equals(contractId)) {
          pledged = true;
          break;
        }
      }
      if (!pledged) {
        debts.remove(contractId);
      }
    }
    // 源户仍是任一笔债务合同的端点（debtor 或 creditor）时：EconomyData 的跨表守卫不看本金/状态，
    // 只要求端点家户仍存在 ⇒ 保留人口 0 的空壳行（架构 §5.3 的"有残留 ⇒ 保留空壳"）。
    boolean contractShell = false;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) || contract.creditor().equals(source)) {
        contractShell = true;
        break;
      }
    }
    ActorRef sourceActorForOrg = HouseholdActors.of(source);
    // ★★ D-023：源户仍持有任何资产（自有的不可移动/不可用资产留在名下，或仍作为他人资产的 operator）
    //    ⇒ 保留“资产持有壳户”（人口 0、无组织/unit/配额，但 HouseholdEconomy/HouseholdClassMembership/资产份额都在）。
    //    本批不写假价格、不做假变卖；具名读数已由 recordResidualAssetAudits 记进当天瞬态 ledger。
    boolean assetShell = hasAnySourceAsset(sourceActorForOrg, assetShares);
    boolean shell = contractShell || assetShell;
    // 需求/模式变迁份额指名源户时：若源户要消亡则本批无法表达"迁移它们" ⇒ fail-closed 具名抛；
    // 若已是壳户（合同端点/资产残留）则保留行本身，表仍可解析。
    if (!shell) {
      for (HouseholdDemand demand : householdDemands.values()) {
        if (demand.scope() == HouseholdDemand.DemandScope.HOUSEHOLD
            && demand.household().isPresent()
            && demand.household().get().equals(source)) {
          throw new IllegalStateException(
              "源户挂着 HOUSEHOLD 范围的需求（需求表不是本批工作副本，无法随迁）: source="
                  + source
                  + " demand="
                  + demand.id());
        }
      }
      for (ClassShare share : classShares.values()) {
        if (share.householdId().equals(source)) {
          throw new IllegalStateException(
              "源户挂着 ClassShare（模式变迁保留份额；本批不能把它迁给目标户）: source=" + source + " share=" + share.id());
        }
      }
    }
    // 其它组织把源户列为 laborSource 时清掉该引用（源户已不存在）。
    for (ProductionEnterprise enterprise : new ArrayList<>(enterprises.values())) {
      if (enterprise.laborSources().contains(source)) {
        List<HouseholdId> sources = new ArrayList<>(enterprise.laborSources());
        sources.remove(source);
        enterprises.put(
            enterprise.id(),
            new ProductionEnterprise(
                enterprise.id(),
                enterprise.modeId(),
                enterprise.classPositionId(),
                enterprise.unitId(),
                enterprise.organizer(),
                sources,
                enterprise.assetSources(),
                enterprise.inputSources(),
                enterprise.outputOwnership(),
                enterprise.relationTemplateRef(),
                enterprise.status(),
                enterprise.statusReason()));
      } else if (enterprise.organizer().equals(sourceActorForOrg)) {
        // 源户自己的组织已在下面退役分支处理
        continue;
      }
    }
    // ★★ D-023：不再有“整户消亡时把全部资产转给最后目标”的旧路径。资产随迁已在逐笔 move 里按人口比例完成；
    //    这里只负责组织/unit/关系/配额/成员份额的退役，以及决定“整户移除”还是“留资产壳户”。
    List<ProductionUnitId> removedUnits = new ArrayList<>();
    for (ProductionEnterprise enterprise : new ArrayList<>(enterprises.values())) {
      if (enterprise.organizer().equals(sourceActorForOrg)) {
        enterprise.unitId().ifPresent(removedUnits::add);
        enterprises.remove(enterprise.id());
      }
    }
    for (ProductionUnitId unitId : removedUnits) {
      // ★★ B2b：实例退役直调契约口（`removeInstance`）。★ 返回 false = 该实例在 production 当刻不存在
      //   ——对"整户迁出"是合法幂等（这条 unit 由别处先退役），记 INFO 具名，不静默。
      if (!session.productionRuntime().removeInstance(unitId)) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "MODE_MIGRATION_UNIT_ALREADY_GONE",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "unit",
                    unitId.value(),
                    "day",
                    day));
      }
      relations.remove(unitId);
      operatorConditions.remove(unitId);
    }
    for (HouseholdLaborCommitment laborCommitment : new ArrayList<>(laborCommitments.values())) {
      if (laborCommitment.household().equals(source)
          || removedUnits.contains(unitOfActivity(laborCommitment.activity()))) {
        laborCommitments.remove(laborCommitment.id());
      }
    }
    // ★★ P0：不再区分"整户移除 / 留壳户"——人口归零后**始终保留** 0 人口壳行
    //    （HouseholdEconomy + HouseholdClassMembership + FlowRow），不删行/不删 FlowRow/不摘 crisisSignal 引用；
    //    组织/unit/关系/劳动配额已在上方照旧退役。这样 App 的 outbox delta（源户 -pop）总有落点，
    //    也与 Social 侧"迁移后留下 0 成员家户"同形。实际行 population 由 App 同 revision 回写为 0。
    Long plannedSourcePopulation = plannedPopulation.get(source);
    if (plannedSourcePopulation == null || plannedSourcePopulation != 0L) {
      throw new IllegalStateException(
          "retireSource 只允许在源户计划人口为 0 时调用: source="
              + source
              + " plannedPopulation="
              + plannedSourcePopulation);
    }
    // 兜底：classMemberships/householdEconomies 必须仍能解析该行（其它路径不得提前删掉它）。
    if (!householdEconomies.containsKey(source) || !classMemberships.containsKey(source)) {
      throw new IllegalStateException(
          "0 人口壳户的行/阶层归属缺失（拒绝留下半删状态）: source="
              + source
              + " row="
              + householdEconomies.containsKey(source)
              + " standing="
              + classMemberships.containsKey(source));
    }
  }

  // ── 新建目标家户 ──────────────────────────────────────────────────────────────────────────

  private static ClassPositionId createNewHousehold(
      ModeMigrationPolicy.MigrationMove move,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      AccountSession accounts,
      EconomyDayView base,
      long day,
      Map<IndustryId, IndustryDefinitionView> industries) {
    HouseholdId target = move.target();
    if (householdEconomies.containsKey(target)) {
      throw new IllegalStateException("新建目标家户 id 已存在（拒绝覆盖）: " + target);
    }
    // ★★ M-D（设计书 §13 I-A/I-D、I-3）：**排序表第 1 项的落点** —— 计划带的 primaryPositionId 若属于目标生产方式，
    //   它就是新建户的 currentPositionId（主业）；不属于/缺失 ⇒ 退回既有 pickTargetPosition（旧调用方/夹具逐值不变）。
    //   ★ "主业 = 排序表第 1 项"的**落点仍在本类**（既有模式变迁路径），排列表本身不写状态（{@code PrimaryModeRanking}）。
    ClassPositionId plannedPrimary = move.primaryPositionId();
    ProductionRole plannedPrimaryRole =
        plannedPrimary == null ? null : base.classPositions().get(plannedPrimary);
    boolean usesPlannedPrimary =
        plannedPrimaryRole != null && plannedPrimaryRole.modeId().equals(move.targetMode());
    ClassPositionId positionId =
        usesPlannedPrimary
            ? plannedPrimary
            : pickTargetPosition(base, move.targetMode(), sourceHouseholdEconomy, classMemberships);
    // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 2 条）：**目标档位 = 这户带来的库存能支撑的最高档**
    //   ⇒ 新建户的**阶层槽位**（{@code view.stratum}）随档位走（贫农 → 中农 → 富农 → 地主），而不是照抄源户的槽位。
    //   ★ 档位下标由**计划**给出（{@code MigrationMove.targetTierIndex}：规划期已按"带得走的作业资本"算好；
    //     执行期不改判 —— 计划/执行两处各算一遍必然漂开）。槽位表 = 目标产业模板的 slots()（唯一拼写点）。
    SocialClassId tierStratum = stratumForTier(base, industries, move);
    CohortKey view =
        new CohortKey(
            move.targetHex(),
            sourceHouseholdEconomy.view().residence(),
            tierStratum == null ? sourceHouseholdEconomy.view().stratum() : tierStratum);
    HouseholdEconomy createdHouseholdEconomy =
        new HouseholdEconomy(
            target,
            view,
            0L,
            0L,
            sourceHouseholdEconomy.participationPerMille(),
            0L,
            Map.of(),
            Map.of(),
            0L);
    householdEconomies.put(target, createdHouseholdEconomy);
    classMemberships.put(
        target,
        new HouseholdClassMembership(
            target,
            positionId,
            positionId,
            // ★ P2-B：迁移到新 mode ⇒ 只参与新位置（旧的可参与集合已随旧 mode 退出）
            Set.of(),
            Map.of(),
            0L,
            day,
            usesPlannedPrimary
                ? "AUTO_MIGRATION:" + move.reason() + ":PRIMARY_MODE_RANKING"
                : "AUTO_MIGRATION:" + move.reason()));
    accounts.registerHousehold(target, move.targetHex(), Map.of(), Map.of(), Map.of(), Map.of());
    return positionId;
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 2 条）：档位 → 新建目标户的阶层槽位</b>。
   *
   * <pre>
   * 档位 = 计划给的 {@code MigrationMove.targetTierIndex}（规划期按"带得走的作业资本"算，见 ModeMigrationPolicy.supportedTierIndex）
   * 槽位 = 目标产业模板 slots().get(min(档位, size−1)).id()   （{@link IndustryClassSlots}：贫农/中农/富农/地主）
   * 档位 ≤ 0（兜底档 / 旧计划）⇒ 返回 null ⇒ 调用方照抄源户槽位（逐值等于改前）
   * </pre>
   *
   * <p>★ <b>为什么由计划给、执行期不重算</b>：执行期的"能带走多少"依赖逐笔比例切分（同一源户的多笔各不同）， 重算会让同一个目标户的槽位依赖执行顺序；规划期算一次、执行期照用 ⇒
   * 计划/执行不可能漂开。
   */
  private static SocialClassId stratumForTier(
      EconomyDayView base,
      Map<IndustryId, IndustryDefinitionView> industries,
      ModeMigrationPolicy.MigrationMove move) {
    int tier = move.targetTierIndex();
    if (tier <= 0) {
      return null;
    }
    IndustryDefinitionView industry = null;
    for (IndustryId candidate :
        ModeMigrationPolicy.industriesForMode(base, move.targetHex(), move.targetMode())) {
      IndustryDefinitionView resolved = industries.get(candidate);
      if (resolved != null) {
        industry = resolved;
        break;
      }
    }
    if (industry == null && DefaultProductionModes.MERCHANT.equals(move.targetMode())) {
      industry = industries.get(MerchantTradeTemplate.industryId(move.targetHex()));
    }
    if (industry == null || industry.slots().isEmpty()) {
      return null;
    }
    int index = Math.min(tier, industry.slots().size() - 1);
    return industry.slots().get(index).id();
  }

  /** 目标 mode 的位置选择：优先同 relationToMeans+surplusRole，其次第一个可生产位置（id 升序）。 */
  private static ClassPositionId pickTargetPosition(
      EconomyDayView base,
      ProductionModeId modeId,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<HouseholdId, HouseholdClassMembership> classMemberships) {
    ProductionModeDefinitionView mode = base.modes().get(modeId);
    if (mode == null) {
      throw new IllegalStateException("计划的目标 mode 不在目录里（拒绝凭空造 mode）: " + modeId);
    }
    var structure = base.classStructures().get(mode.classStructureId());
    if (structure == null) {
      throw new IllegalStateException("目标 mode 没有阶层结构（拒绝凭空造位置）: " + modeId);
    }
    ProductionRole sourcePosition = null;
    HouseholdClassMembership sourceClassMembership = null;
    // ★ 2026-10-10 路径无关修复（同族直读基态点）：源户的"当前位置"必须读**当刻工作副本**，不是本次 advance 的基态。
    //   `classStandings` 在一段 advance 内会被 E5b 阶层下滑 / E6a 模式变迁 / 本类新建目标户改写；读 base（= range.from
    //   快照）会让同段内靠后的迁移按**段首**位置选目标位置（选错 relationToMeans/surplusRole 的一档），
    //   与 `ModeMigrationPolicy.plan` 拿到的 `session.sheet().classMemberships()`（工作副本）也不是同一条真值。
    //   `modes/classStructures/classPositions` 仍是静态模板，照旧读 base。
    for (HouseholdClassMembership classMembership : classMemberships.values()) {
      if (classMembership.householdId().equals(sourceHouseholdEconomy.id())) {
        sourceClassMembership = classMembership;
        break;
      }
    }
    if (sourceClassMembership != null) {
      sourcePosition = base.classPositions().get(sourceClassMembership.currentPositionId());
    }
    List<ProductionRole> positions = new ArrayList<>(structure.positions().values());
    positions.sort(Comparator.comparing(position -> position.id().value()));
    if (sourcePosition != null) {
      for (ProductionRole position : positions) {
        if (producing(position)
            && position.relationToMeans() == sourcePosition.relationToMeans()
            && position.surplusRole() == sourcePosition.surplusRole()) {
          return position.id();
        }
      }
    }
    for (ProductionRole position : positions) {
      if (producing(position)) {
        return position.id();
      }
    }
    throw new IllegalStateException("目标 mode 没有可承载人口的生产位置: " + modeId);
  }

  private static boolean producing(ProductionRole position) {
    return position.laborRole() != ProductionRole.LaborRole.NONE
        && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT;
  }

  /**
   * ★★★ <b>D-032 B3（§13.8 裁定二第 1/3/4 条）：实例地点必须落定</b> —— 方案串解不出地点即**契约故障 ERROR 不降级**（跨切片不一致），**不**写
   * {@code UNSPECIFIED} 占位（旧的 {@code ofPlanOrUnspecified} 已删除）。
   *
   * <p>★ 本类的调用点：{@code industryId} 取自 {@code IndustryHexKeys.at(..., move.targetHex())}（自带格段） ⇒
   * 正常路径必命中；走不到这里说明候选态自相矛盾（不是业务拒绝）。
   */
  private static io.mosire.simos.production.contract.ProductionLocation requireInstanceLocation(
      IndustryId industryId) {
    return io.mosire.simos.production.contract.ProductionLocation.ofPlan(industryId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "location-unknown: 迁移搬迁新建实例的产业 id 不带格段（地点无从落定，跨切片不一致，"
                        + "不降级）: industry="
                        + industryId.value()));
  }

  /**
   * 为一个产业模板规划"从该 hex 的闲置份额拆出 TENANCY"的资产移动（{@link OwnershipStakeBook#apply} 唯一写口）； 任一 capacity 种类不足
   * ⇒ null（该模板不可行）。
   *
   * <p>★★ P10.7 / D-024 修复 1b：闲置判据的唯一拼写点是 {@link ModeMigrationPolicy#isIdleShare} —— {@code
   * quantity > 0 && operator == owner && id ∉ claimed}，claimed = 既有组织 assetSources ∪ 在产 unit
   * 占用的份额（同 industry、同 operator、quantity>0）。既有商号/组织/在产 unit 在用的份额不是闲置，拆空会让组织的 assetSources 指向已删除的份额
   * id，或在产 unit 的产能凭空消失。
   *
   * <p>★★ D-023：{@code availableByKind} = 本目标户随迁进来、已登记在该产业下的份额（{@code AssetKind → quantity}）。
   * 它们已经是目标户自有的产能 ⇒ 先从需求里抵减，只对缺口租闲置份额，避免同一份资产既随迁又租一遍。
   *
   * <p>★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗」）：{@code crossIndustryIdleInHex}</b> ——
   * 为真时，闲置份额的候选集从"**同一产业模板下**"放宽到"**同一格内、同一种 AssetKind**"（其它产业的闲置资产也可以 租来当这座新产业的作业资本）。★ 只对"**按需新建的
   * trade 产业**"为真：那个产业是本轮才长出来的，它名下不可能有任何份额 ⇒ 不放宽就永远凑不齐作业资本（W7 记的 E3 遗留门槛）。★ 安全性来自 {@code
   * isIdleShare} 本身：闲置 = 没有任何组织/在产 unit 在用的份额 ⇒ 租走它不会让别人的产能凭空消失。
   */
  private static List<OwnershipStakeBook.Move> planAssetMoves(
      IndustryId industryId,
      IndustryDefinitionView industry,
      ActorRef targetActor,
      long scale,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<AssetKind, Long> availableByKind,
      boolean crossIndustryIdleInHex,
      HexCoord hex) {
    Map<AssetKind, Long> coveredByKind = availableByKind == null ? Map.of() : availableByKind;
    String hexKey = hex == null ? null : IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<OwnershipStake> idle = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (!ModeMigrationPolicy.isIdleShare(share, claimedByEnterprises)) {
        continue;
      }
      if (share.industry().equals(industryId)) {
        idle.add(share);
        continue;
      }
      if (crossIndustryIdleInHex
          && hexKey != null
          && IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        idle.add(share);
      }
    }
    idle.sort(Comparator.comparing(share -> share.id().value()));
    List<OwnershipStakeBook.Move> moves = new ArrayList<>();
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long requiredQuantity = Math.multiplyExact(scale, required.getValue());
      long need =
          Math.max(0L, requiredQuantity - coveredByKind.getOrDefault(required.getKey(), 0L));
      for (OwnershipStake share : idle) {
        if (need <= 0L) {
          break;
        }
        if (share.asset() != required.getKey()) {
          continue;
        }
        long take = Math.min(need, share.quantity());
        if (take <= 0L) {
          continue;
        }
        moves.add(
            new OwnershipStakeBook.Move(
                share.id(), take, share.owner(), targetActor, AssetRightKind.TENANCY));
        need -= take;
      }
      if (need > 0L) {
        return null;
      }
    }
    return moves;
  }

  private static ProductionUnitId createEnterpriseAndProcess(
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      ModeMigrationPolicy.MigrationMove move,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HouseholdEconomy targetHouseholdEconomy,
      ClassPositionId positionId,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      EconomyDayView base,
      LinkedHashMap<PledgeId, Pledge> pledges,
      List<AssetShareId> migratedShareIds,
      Map<IndustryId, Map<AssetKind, Long>> migratedCoverage,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    ActorRef targetActor = HouseholdActors.of(move.target());
    // ★★ D-024：按目标 mode 的默认 regime 选产业模板（与计划期 ModeMigrationPolicy.industriesForMode 同源）——
    //   merchant 目标只在 trade 模板里找；找不到 ⇒ 空 ⇒ 具名失败（绝不把农场模板当商号）。
    List<IndustryId> industryCandidates =
        ModeMigrationPolicy.industriesForMode(industries, move.targetHex(), move.targetMode());
    if (industryCandidates.isEmpty()) {
      throw new IllegalStateException("新建目标 hex 没有产业模板（拒绝凭空造生产）: " + move.targetHex());
    }
    IndustryId industryId = null;
    IndustryDefinitionView industry = null;
    List<OwnershipStakeBook.Move> assetMoves = new ArrayList<>();
    // ★★ P10.7 / P10.8 / D-024 修复 1b：既有组织 assetSources 正在使用的份额不是闲置；seeder 直接以 ESTATE 等
    //    operator 建立的 farm/weave/craft/trade 主 unit 没有对应组织，故 claimed 还必须并入"在产 unit 占用的份额"
    //    （同 industry、同 operator、quantity>0）。这里与 ModeMigrationPolicy.plan() 顶层共用同一个三参拼装点，
    //    并读同一份当天工作副本 enterprises/units/assetShares（含当天自动组织阶段新加、以及本一次 apply 前几笔
    //    新建的组织/unit），保证"计划可新建 ⇔ 执行可拆到"；随迁新份额也从闲置池里排除（它们已归目标户）。
    Set<AssetShareId> claimedByEnterprises =
        new LinkedHashSet<>(
            ModeMigrationPolicy.claimedOwnershipStakes(enterprises, units, assetShares));
    claimedByEnterprises.addAll(migratedShareIds);
    // ★★★ W9-fix2：失败时把"每个候选按哪个规模要多少"带上（本次事故的诊断成本全在缺的这一行信息上：
    //   现场只有 labor=8857，看不出执行期要的是 scale=8 ⇒ 与计划期预留 scale=1 的具名不一致）。
    StringBuilder triedCandidates = new StringBuilder();
    for (IndustryId candidate : industryCandidates) {
      IndustryDefinitionView candidateIndustry = industries.get(candidate);
      if (candidateIndustry == null) {
        continue;
      }
      // ★★★ W9-fix2（2026-10-11）：规模算式的**唯一拼写点** = {@link
      // ModeMigrationPolicy#requiredWorkCapitalScale}（计划期预留与执行期索取共用；P0 语义在那里写一次）。
      //   ★ 这里的 labor 是**带本笔随迁投影的工作行视图**的劳动（新建户 = 0 + 本笔迁入劳动；落盘行仍是 0，
      //     由 App 按 outbox 回写）—— 逐字读落盘行会得到 scale = 1，那正是 W9-fix2 改前计划侧的旧假设。
      long scale =
          ModeMigrationPolicy.requiredWorkCapitalScale(
              candidateIndustry, targetHouseholdEconomy.laborMilli());
      if (triedCandidates.length() > 0) {
        triedCandidates.append(' ');
      }
      triedCandidates
          .append(candidate.value())
          .append(":scale=")
          .append(scale)
          .append(",need=")
          .append(candidateIndustry.recipe().capacityPerUnit());
      List<OwnershipStakeBook.Move> planned =
          planAssetMoves(
              candidate,
              candidateIndustry,
              targetActor,
              scale,
              assetShares,
              claimedByEnterprises,
              migratedCoverage.getOrDefault(candidate, Map.of()),
              // ★★★ W9（用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗」）：**这一笔没带来作业资本**时，
              //   允许租**同格**其它产业的闲置同种资产（工具可买/可租）—— 否则"按需新建的 trade 产业"名下
              //   永远没有任何份额，只能整笔否决（W7 记的 E3 遗留门槛）。★ 只在"随迁覆盖为空"时为真：
              //   带来了作业资本就照旧只在本产业内找闲置（逐值等于改前）。
              DefaultProductionModes.MERCHANT.equals(move.targetMode())
                  && migratedCoverage.getOrDefault(candidate, Map.of()).isEmpty(),
              move.targetHex());
      if (planned != null) {
        industryId = candidate;
        industry = candidateIndustry;
        assetMoves = planned;
        break;
      }
    }
    if (industryId == null || industry == null) {
      throw new IllegalStateException(
          "新建目标户的租赁资产不足（计划不该包含此目标；已按 id 序遍历该 hex 全部产业模板）: hex="
              + move.targetHex()
              + " labor="
              + targetHouseholdEconomy.laborMilli()
              + " tried=["
              + triedCandidates
              + "]");
    }
    List<AssetShareId> createdShares = new ArrayList<>();
    if (!assetMoves.isEmpty()) {
      createdShares.addAll(OwnershipStakeBook.apply(assetShares, industries, pledges, assetMoves));
    }
    // ★★ GAP-2：组织 assetSources 只收 operator == organizer（targetActor）的份额。随迁份额由
    //    migrateAssetsForMove 按 targetActor 重建（同/跨 hex 都一样）；租赁份额由上方 planAssetMoves
    //    按 targetActor 新建。这里再做最后一次逐项核对：新建租赁份额对不上是内部错误，当场抛；
    //    随迁份额对不上（理论上不会发生）不得进 assetSources，也不放进 unit 的可用资产依赖里。
    List<AssetShareId> assetSources =
        new ArrayList<>(migratedShareIds.size() + createdShares.size());
    for (AssetShareId migratedShareId : migratedShareIds) {
      OwnershipStake migratedShare = assetShares.get(migratedShareId);
      if (migratedShare != null && migratedShare.operator().equals(targetActor)) {
        assetSources.add(migratedShareId);
      }
    }
    for (AssetShareId createdShare : createdShares) {
      OwnershipStake share = assetShares.get(createdShare);
      if (share == null || !share.operator().equals(targetActor)) {
        throw new IllegalStateException(
            "新建目标户的租赁份额 operator 必须等于 organizer(targetActor): share="
                + createdShare
                + " operator="
                + (share == null ? "<份额不存在>" : share.operator()));
      }
      assetSources.add(createdShare);
    }
    ProductionUnitId unitId = ProductionUnitId.idOf(industryId, targetActor);
    if (units.containsKey(unitId)) {
      throw new IllegalStateException("新建目标 unit id 已存在（拒绝覆盖）: " + unitId);
    }
    // ★★★ D-032 B3（§13.8 裁定二第 1/3/4 条）：地点是语义边界 —— 实例必须有真实地点，解不出即**契约故障
    //   ERROR 不降级**（跨切片不一致），不写 UNSPECIFIED 占位。★ industryId 取自
    //   `IndustryHexKeys.at(..., move.targetHex())`（自带格段）⇒ 正常路径必命中。
    io.mosire.simos.production.contract.ProductionLocation instanceLocation =
        requireInstanceLocation(industryId);
    // ★★ B2b：实例新建直调契约口（`createInstance`）。被拒 = 跨切片不一致 ⇒ ERROR 不降级（I15 / §13.2 ①④）。
    io.mosire.simos.production.contract.ProductionInstanceOutcome created =
        runtime.createInstance(
            new io.mosire.simos.production.contract.ProductionCreationRequest(
                industryId,
                unitId,
                instanceLocation,
                targetActor,
                "mode:" + move.targetMode().value()));
    if (created
        instanceof
        io.mosire.simos.production.contract.ProductionInstanceOutcome.Rejected rejected) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "MODE_MIGRATION_UNIT_CREATE_REJECTED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "unit",
                  unitId.value(),
                  "plan",
                  industryId.value(),
                  "day",
                  day,
                  "reason",
                  rejected.reason()));
      throw new IllegalStateException(
          "迁移搬迁新建实例被 production 拒绝（跨切片不一致，不降级）: unit="
              + unitId.value()
              + " plan="
              + industryId.value()
              + " reason="
              + rejected.reason());
    }
    // ★★ D-023 第 5 项：新家户必须用目标产业 regime + 目标 mode 的**完整关系模板**（不再空规则全归 operator）。
    MigrationRelationPlan relationPlan =
        buildMigrationRelation(
            move,
            unitId,
            industryId,
            targetActor,
            householdEconomies,
            targetHouseholdEconomy,
            base,
            day,
            auditLedger,
            industries);
    ProductionRules relation = relationPlan.relation();
    relations.put(unitId, relation);
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            move.targetMode(),
            positionId,
            move.target(),
            IndustryHexKeys.hexKey(move.targetHex().q(), move.targetHex().r()));
    ProductionEnterprise enterprise =
        new ProductionEnterprise(
            organizationId,
            move.targetMode(),
            positionId,
            Optional.of(unitId),
            targetActor,
            List.of(move.target()),
            assetSources,
            List.of(relation.inputSupplier()),
            new Payee.ToActor(relation.residualOwner()),
            Optional.of(relationPlan.templateRef()),
            ProductionEnterprise.Status.ACTIVE,
            "");
    enterprises.put(organizationId, enterprise);
    return unitId;
  }

  /** ★ Z3b/C7：迁移计划要搬动 GOV_SERVICE 承诺家户 ⇒ 具名 ERROR（先落证据）再 fail-closed。 */
  private static void refuseGovServiceMigration(
      HouseholdId source, HouseholdLaborCommitment commitment, long day) {
    EventLog.channel(EconomyLog.migration())
        .error(
            LogEvent.of(
                "MIGRATION_GOV_SERVICE_COMMITMENT_REFUSED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                "gov-service-commitment-cannot-migrate",
                "day",
                day,
                "source",
                source.value(),
                "allocation",
                commitment.id().value(),
                "activity",
                commitment.activity(),
                "laborMilli",
                commitment.laborMilli()));
    throw new IllegalStateException(
        "迁移不得搬动 GOV_SERVICE 承诺家户（C7：不可缩、最高优先级；官吏户随 GOV 单位而不是生产方式迁移）: source="
            + source
            + " allocation="
            + commitment.id()
            + " activity="
            + commitment.activity()
            + " laborMilli="
            + commitment.laborMilli());
  }

  // ── 迁移原子 —— 劳动配额/货币/债务 ───────────────────────────────────────────────────────

  private static void moveLaborCommitmentToTarget(
      HouseholdLaborCommitment sourceLaborCommitment,
      HouseholdId target,
      ProductionUnitId targetUnit,
      long laborTake,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      HouseholdEconomy targetHouseholdEconomy) {
    String activity = targetUnit == null ? sourceLaborCommitment.activity() : targetUnit.value();
    ProductionUnitId targetUnitKey = targetUnit == null ? unitOfActivity(activity) : targetUnit;
    LaborAllocationId targetId =
        targetUnitKey == null
            ? new LaborAllocationId(
                "alloc-mig-" + sourceLaborCommitment.group().value() + "-" + target.value())
            : HouseholdLaborCommitment.idOfProduction(
                targetUnitKey,
                sourceLaborCommitment
                    .productionRole()
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "PRODUCTION 承诺缺 productionRole（D2 §5.6 不变量被绕开）")),
                sourceLaborCommitment.group(),
                target);
    HouseholdLaborCommitment existingLaborCommitment = laborCommitments.get(targetId);
    HouseholdLaborCommitment createdLaborCommitment =
        new HouseholdLaborCommitment(
            targetId,
            sourceLaborCommitment.group(),
            target,
            HouseholdActors.of(target),
            activity,
            (existingLaborCommitment == null ? 0L : existingLaborCommitment.laborMilli())
                + laborTake,
            sourceLaborCommitment.period(),
            sourceLaborCommitment.kind(),
            sourceLaborCommitment.productionRole());
    laborCommitments.put(targetId, createdLaborCommitment);
    if (targetHouseholdEconomy == null) {
      throw new IllegalStateException("迁移目标行不存在（拒绝静默丢劳动）: " + target);
    }
  }

  private static void moveMoney(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      CurrencyId currency,
      long amount) {
    Map<CurrencyId, Long> sourceMoney = accounts.householdMoney().get(source);
    Map<CurrencyId, Long> targetMoney = accounts.householdMoney().get(target);
    if (sourceMoney == null || targetMoney == null) {
      throw new IllegalStateException("迁移货币时账户不存在（拒绝静默丢钱）: " + source + " → " + target);
    }
    long balance = sourceMoney.getOrDefault(currency, 0L);
    if (amount > balance) {
      throw new IllegalStateException(
          "迁移货币超过源户余额（拒绝透支）: source=" + source + " need=" + amount + " balance=" + balance);
    }
    LinkedHashMap<CurrencyId, Long> nextSource = new LinkedHashMap<>(sourceMoney);
    putOrRemove(nextSource, currency, balance - amount);
    accounts.householdMoney().put(source, nextSource);
    LinkedHashMap<CurrencyId, Long> nextTarget = new LinkedHashMap<>(targetMoney);
    nextTarget.merge(currency, amount, Math::addExact);
    accounts.householdMoney().put(target, nextTarget);
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 1 条）：商品库存随迁 —— 「默认带一部分库存过去」</b>。
   *
   * <pre>
   * 可移动商品 = 家户商品账里的**实物库存**（粮 / 纤维 / 布 / 工具 / 铁…），**不含** {@link HaulService#HAUL_COMMODITY}
   *              （运输服务不是可搬运的实物：它是"在这个格能交付的运力"的现货，跨格搬它等于凭空承诺别处的服务）
   * 逐商品取整  = 整户迁空（empties）⇒ 冻结之外的**剩余量全部**
   *              否则            ⇒ ⌊可用量 × 本笔人口 ÷ 迁出前人口⌋          （与货币/资产同式，余数留源户）
   * 可用量      = 商品账余额 − 该商品的冻结量（★ 冻结不越：付款承诺/质押的那一份不得随人走）
   * 写路径      = {@code EconomySettlement.applyTransfer}（唯一写口，两遍式校验）；一条 move **一条转移**（多商品腿）
   * </pre>
   *
   * <p>★ <b>守恒</b>：两端都是已登记家户 ⇒ 该商品在**家户账总和**上一字不变（源减 = 目标增）；没有任何"凭空造/销毁"的腿。
   *
   * <p>★ <b>顺序</b>：本方法在同一源户的多笔 move 之间**逐笔读活表**（前面的笔已经把库存带走了）⇒ 与 D-023 §4 的"⌊剩余量 ×
   * 比例⌋、余数留源户"逐字一致（不是对同一份初值各算一遍）。
   */
  private static void carryGoodsForMove(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      long popTake,
      long populationBefore,
      boolean empties,
      HexCoord targetHex,
      long day,
      ProductionLedger.Accumulator auditLedger,
      int moveOrdinal) {
    Map<CommodityId, Long> sourceGoods = accounts.householdGoods().getOrDefault(source, Map.of());
    if (sourceGoods.isEmpty()) {
      return;
    }
    Map<CommodityId, Long> frozenGoods =
        accounts.householdFrozenGoods().getOrDefault(source, Map.of());
    LinkedHashMap<CommodityId, Long> carried = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : sourceGoods.entrySet()) {
      CommodityId commodity = entry.getKey();
      if (!isMobileCommodity(commodity)) {
        continue;
      }
      long available = entry.getValue() - frozenGoods.getOrDefault(commodity, 0L);
      if (available <= 0L) {
        continue;
      }
      long take =
          empties
              ? available
              : Math.multiplyExact(available, popTake) / Math.max(1L, populationBefore);
      take = Math.min(take, available);
      if (take > 0L) {
        carried.put(commodity, take);
      }
    }
    if (carried.isEmpty()) {
      return;
    }
    ActorRef from = HouseholdActors.of(source);
    ActorRef to = HouseholdActors.of(target);
    Transfer transfer =
        auditLedger == null
            ? new Transfer(
                new TransferId("tr-" + day + "-migration-" + moveOrdinal),
                day,
                from,
                to,
                targetHex,
                carried,
                Map.of(),
                TransferReason.MIGRATION_CARRY,
                Optional.empty())
            : auditLedger.mint(
                from, to, targetHex, carried, Map.of(), TransferReason.MIGRATION_CARRY);
    EconomySettlement.applyTransfer(
        accounts.householdGoods(),
        accounts.householdMoney(),
        accounts.householdFrozenGoods(),
        accounts.householdFrozenMoney(),
        accounts.householdOfActor(),
        transfer);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MIGRATION_INVENTORY_CARRIED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "target",
                target.value(),
                "targetHex",
                targetHex,
                "population",
                popTake,
                "goods",
                carried,
                "reason",
                "mobile-commodity-inventory-carried-by-population-ratio"));
  }

  /**
   * ★★ <b>W9：哪些商品算"可移动"（唯一拼写点）</b> —— 家户商品账里的**实物库存**都可以随人走（粮、纤维、布、工具、铁…）； <b>运输服务</b>（{@link
   * HaulService#HAUL_COMMODITY}）**不算**：它是"在本格能交付的运力"现货（服务不入库随迁）， 把它搬到另一个格等于凭空承诺那边的服务。
   */
  static boolean isMobileCommodity(CommodityId commodity) {
    return commodity != null && !HaulService.HAUL_COMMODITY.equals(commodity);
  }

  /**
   * ★★ <b>P11.1 / D-023：旧 / 手工计划（{@code moneyByCurrency} 为空）的全币种兜底</b> —— 按当前余额逐币种 独立切：{@code share
   * = ⌊余额 × popTake ÷ 迁出前人口⌋}（源户迁空 ⇒ 该币种余额全走），余数留源户。<b>不做 FX</b>， 逐币种并列存在。
   */
  private static void moveAllCurrenciesByPopulation(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      long populationBefore,
      long popTake,
      boolean empties) {
    Map<CurrencyId, Long> initialBalances =
        accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(initialBalances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long balance =
          accounts.householdMoney().getOrDefault(source, Map.of()).getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long share =
          empties ? balance : Math.multiplyExact(balance, popTake) / Math.max(1L, populationBefore);
      if (share <= 0L) {
        continue;
      }
      if (share > balance) {
        throw new IllegalStateException(
            "迁移货币超过源户余额（拒绝透支）: source="
                + source
                + " currency="
                + currency
                + " need="
                + share
                + " balance="
                + balance);
      }
      moveMoney(accounts, source, target, currency, share);
    }
  }

  /**
   * ★★ <b>把一笔 move 的债务份额从源户搬到目标户</b>（逐合同，金额按计划分摊）。
   *
   * <p>★★ <b>D3（2026-10-09）：目标恰是债权人的那部分 ⇒ 自债净额，不落合同</b>。旧实现把它 upsert 回 <b>源户</b>
   * （注释直言"人口清零时会因残留债具名抛"）—— 于是"源户人口归零"与"这笔债的债权人正是迁入目标"同时成立时， {@code retireSource} 的"拒绝消亡丢账"守卫当场
   * ERROR 500（三区世界 day=270 实测：source={@code hh--3_3-urban-middle_peasant}，target=creditor={@code
   * hh--3_3-rural-poor_peasant}，grain=323）。
   *
   * <p>净额口径取自全仓唯一实现 {@link DebtPartyResolver}：「自债（debtor == creditor）显式净额、不落合同 ——
   * 同户对自己的债权无经济意义，且还款会铸出'两端相等'的非法转移」。债务本金按人口比例随迁，这笔份额对应的人已经并入 债权人户 ⇒
   * 债权与负债落在同一个家户里互相抵消，两边同时减少、净值不变。**不静默**：INFO 事件 + 当天 ledger 的具名读数。
   */
  private static void moveDebt(
      ModeMigrationPolicy.MigrationMove move,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    List<DebtContract> contracts = new ArrayList<>();
    long totalPrincipal = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(move.source()) && contract.principal() > 0L) {
        contracts.add(contract);
        totalPrincipal = Math.addExact(totalPrincipal, contract.principal());
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long[] takes = distributeDebt(move, contracts, totalPrincipal);
    long selfNetted = 0L;
    long moved = 0L;
    for (int i = 0; i < contracts.size(); i++) {
      DebtContract contract = contracts.get(i);
      long take = takes[i];
      if (take <= 0L) {
        continue;
      }
      moved = Math.addExact(moved, take);
      DebtContractBook.reduce(debts, contract.id(), take);
      if (!move.target().equals(contract.creditor())) {
        DebtContractBook.upsert(
            debts,
            move.target(),
            contract.creditor(),
            contract.unit(),
            contract.terms(),
            take,
            day,
            contract.dueCycle());
      } else {
        // 目标恰是债权人 ⇒ 自债净额（见方法注）：本笔份额不落任何合同，两边同时减少。
        selfNetted = Math.addExact(selfNetted, take);
        EventLog.channel(EconomyLog.migration())
            .info(
                LogEvent.of(
                    "MIGRATION_DEBT_SELF_NETTED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    move.source().value(),
                    "target",
                    move.target().value(),
                    "contract",
                    contract.id().value(),
                    "principalBefore",
                    contract.principal(),
                    "netted",
                    take,
                    "reason",
                    "creditor-is-migration-target-self-debt-netted"));
        if (auditLedger != null) {
          auditLedger.addLiquidationAudit(
              new ProductionLedger.LiquidationAudit(
                  day,
                  "migration-netted-self-debt",
                  Optional.of(move.source()),
                  Optional.of(contract.id()),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  take,
                  0L,
                  take,
                  contract.principal() - take,
                  0L,
                  "creditor-is-migration-target",
                  Map.of("nettedPrincipal", take)));
        }
      }
    }
    if (moved != move.debtMilli()) {
      // distributeDebt 已保证分摊总额 == 计划额；这条例行守卫挡住"未来有人改分摊口径"。
      throw new IllegalStateException(
          "债务迁移金额没有全部分摊（拒绝静默丢债）: move="
              + move
              + " planDebtMilli="
              + move.debtMilli()
              + " moved="
              + moved);
    }
    long sourceResidualAfter = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(move.source()) && contract.principal() > 0L) {
        sourceResidualAfter = Math.addExact(sourceResidualAfter, contract.principal());
      }
    }
    if (EconomyLog.migration().isDebugEnabled()) {
      EventLog.channel(EconomyLog.migration())
          .debug(
              LogEvent.of(
                  "MIGRATION_DEBT_MOVED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "source",
                  move.source().value(),
                  "target",
                  move.target().value(),
                  "planDebtMilli",
                  move.debtMilli(),
                  "principalBefore",
                  totalPrincipal,
                  "contracts",
                  contracts.size(),
                  "selfNetted",
                  selfNetted,
                  "sourceResidualAfter",
                  sourceResidualAfter));
    }
  }

  /**
   * ★★ <b>D3：把一笔 move 的计划债务额分摊到源户的各笔合同上（总额恒等于计划额，逐笔确定性）</b>。
   *
   * <p>口径：逐合同按**剩余本金比例**取份额（{@code ⌊计划额 × 本合同剩余本金 ÷ 源户剩余本金总额⌋}）；逐笔 floor 会产生不到"合同数"的余数，**按合同 id 的
   * canonical 序补给仍有本金余额的合同**（绝不丢、绝不静默）。
   *
   * <p>★★ <b>旧实现为什么会在长跑里炸</b>：它只让**最后一笔**吃余数（{@code min(remaining, 本金)}）—— 最后一笔本金不够时 {@code
   * remaining != 0}，当场具名抛"债务迁移金额没有全部分摊"。三区世界 300 天一次推进实测：day=33x {@code
   * source=hh-0_-3-urban-middle_peasant} 计划 19,454,004、最后一笔吃不下余下的 5 ⇒ 整次 advance 500。 余数存在性只取决于
   * floor 与合同数，与金额大小无关 ⇒ 只要长跑够久就会撞上。
   *
   * @param move 本笔迁移（{@code debtMilli()} = 计划分摊额）
   * @param contracts 源户作为债务人的合同（须已按 id 升序；本金 > 0）
   * @param totalPrincipal 上述合同的本金合计（> 0）
   * @return 逐合同取额（与 {@code contracts} 同序；Σ == {@code move.debtMilli()}）
   */
  private static long[] distributeDebt(
      ModeMigrationPolicy.MigrationMove move, List<DebtContract> contracts, long totalPrincipal) {
    long[] takes = new long[contracts.size()];
    long remaining = move.debtMilli();
    for (int i = 0; i < contracts.size(); i++) {
      long principal = contracts.get(i).principal();
      long share = Math.multiplyExact(move.debtMilli(), principal) / totalPrincipal;
      takes[i] = Math.min(principal, Math.min(share, remaining));
      remaining = Math.subtractExact(remaining, takes[i]);
    }
    // 第二趟：逐笔 floor 的余数（严格小于合同数）按 canonical 序补给仍有本金余额的合同。
    //   余数 ≤ Σ(本金 − 已取) 由"计划额 ≤ 剩余本金总额"保证（计划额超出本金总额 ⇒ 走下面的具名抛）。
    for (int i = 0; i < contracts.size() && remaining > 0L; i++) {
      long headroom = contracts.get(i).principal() - takes[i];
      long add = Math.min(headroom, remaining);
      takes[i] = Math.addExact(takes[i], add);
      remaining = Math.subtractExact(remaining, add);
    }
    if (remaining != 0L) {
      throw new IllegalStateException(
          "债务迁移计划额超过源户剩余本金（拒绝静默丢债）: move="
              + move
              + " totalPrincipal="
              + totalPrincipal
              + " remaining="
              + remaining);
    }
    return takes;
  }

  // ── D-023 资产随迁（migrateAssetsForMove 及配套）─────────────────────────────────────────────

  /** 一次 move 的资产随迁结果：新建的目标份额 id + 它们在各自产业下的 (AssetKind → quantity) 覆盖。 */
  private record AssetMigrationOutcome(
      List<AssetShareId> createdIds, Map<IndustryId, Map<AssetKind, Long>> coverage) {

    private AssetMigrationOutcome {
      createdIds = List.copyOf(createdIds);
      Map<IndustryId, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
      for (Map.Entry<IndustryId, Map<AssetKind, Long>> entry : coverage.entrySet()) {
        frozen.put(
            entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
      }
      coverage = Collections.unmodifiableMap(frozen);
    }

    static AssetMigrationOutcome empty() {
      return new AssetMigrationOutcome(List.of(), Map.of());
    }
  }

  /** 新家户的关系模板落点（关系本体 + 具名来源；失败回退时 templateRef 里写明原因）。 */
  private record MigrationRelationPlan(ProductionRules relation, String templateRef) {}

  /**
   * ★★ <b>D-023 一条 move 的资产随迁</b>：只动 {@code owner == 源户 actor} 的份额；逐笔按迁移人口比例 {@code ⌊当前剩余量 ×
   * popTake ÷ 迁移前剩余人口⌋} 切（源户本笔迁空 ⇒ 余数随本笔全部带走）。
   *
   * <pre>
   * 同 hex（同产业）：OwnershipStakeBook.apply 拆出目标户份额（owner/operator 见 transferOperator/transferRightKind）
   * 跨 hex + 可移动（SMALL_TOOL/LARGE_TOOL/SHIP/CATTLE/MACHINE）：目标 hex 里第一个 capacityPerUnit 含该 AssetKind 的产业下
   *                                        OwnershipStakeBook.rebuild 新建同量份额；无承载产业 ⇒ 留原户并具名
   * 跨 hex + 不可移动（LAND/WORKSHOP）：不传送，留原户并具名
   * 已出租给他组织 / 有 ACTIVE 质押：本批不越权搬，留原户并具名
   * </pre>
   */
  private static AssetMigrationOutcome migrateAssetsForMove(
      ActorRef sourceActor,
      HexCoord sourceHex,
      ModeMigrationPolicy.MigrationMove move,
      long populationBefore,
      long popTake,
      boolean empties,
      List<AssetShareId> sourceOwnedAssetIds,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      EconomyDayView base,
      LinkedHashMap<PledgeId, Pledge> pledges,
      Set<AssetShareId> foreignUsedShareIds,
      Set<AssetShareId> movedWholeShareIds,
      Map<AssetShareId, String> residualReasons,
      Map<IndustryId, IndustryDefinitionView> industries) {
    boolean sameHex = move.targetHex().equals(sourceHex);
    ActorRef targetActor = HouseholdActors.of(move.target());
    List<OwnershipStakeBook.Move> sameHexMoves = new ArrayList<>();
    List<OwnershipStakeBook.RebuildMove> rebuildMoves = new ArrayList<>();
    for (AssetShareId shareId : sourceOwnedAssetIds) {
      OwnershipStake share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L || !share.owner().equals(sourceActor)) {
        continue; // 已在前面 move 整条随迁 / 已非源户所有
      }
      // 已出租给他组织实际使用的份额：改 operator 会拆掉那个组织的资产引用 ⇒ 本批留原户并具名（不越权搬）。
      if (foreignUsedShareIds.contains(share.id())) {
        residualReasons.putIfAbsent(share.id(), "leased-to-foreign-org:" + share.asset().name());
        continue;
      }
      // ★ 2026-10-09：ACTIVE 质押不排除 —— OwnershipStakeBook 按比例跟到目标份额（同 hex 拆分 / 跨 hex 重建都一样）。
      long take =
          empties
              ? share.quantity()
              : Math.multiplyExact(share.quantity(), popTake) / Math.max(1L, populationBefore);
      take = Math.min(take, share.quantity());
      if (take <= 0L) {
        continue;
      }
      AssetKind asset = share.asset();
      AssetRightKind kind = transferRightKind(share);
      ActorRef newOperator = transferOperator(share, targetActor);
      if (sameHex) {
        sameHexMoves.add(
            new OwnershipStakeBook.Move(share.id(), take, targetActor, newOperator, kind));
      } else {
        if (!isMobileAsset(asset)) {
          residualReasons.putIfAbsent(share.id(), "immobile-cross-hex:" + asset.name());
          continue;
        }
        Optional<IndustryId> hostIndustry = hostIndustryFor(industries, move.targetHex(), asset);
        if (hostIndustry.isEmpty()) {
          residualReasons.putIfAbsent(share.id(), "no-host-industry:" + asset.name());
          continue;
        }
        rebuildMoves.add(
            new OwnershipStakeBook.RebuildMove(
                share.id(), take, hostIndustry.get(), targetActor, newOperator, kind));
      }
      if (take == share.quantity()) {
        movedWholeShareIds.add(share.id());
      }
    }
    List<AssetShareId> createdIds = new ArrayList<>();
    if (!sameHexMoves.isEmpty()) {
      createdIds.addAll(OwnershipStakeBook.apply(assetShares, industries, pledges, sameHexMoves));
    }
    if (!rebuildMoves.isEmpty()) {
      createdIds.addAll(OwnershipStakeBook.rebuild(assetShares, industries, pledges, rebuildMoves));
    }
    if (createdIds.isEmpty()) {
      return AssetMigrationOutcome.empty();
    }
    // ★ 2026-10-09：同 tuple 目标行会合并，`createdIds` 可能重复指向同一行 ⇒ 覆盖量按**本次实际移动量**
    //   逐 Move 记账，不读合并行的总数量（否则会把目标户原有资产算进"随迁覆盖"）。
    Map<IndustryId, Map<AssetKind, Long>> coverage = new LinkedHashMap<>();
    int coverageIndex = 0;
    for (OwnershipStakeBook.Move assetMove : sameHexMoves) {
      coverageIndex =
          accumulateCoverage(
              coverage, assetShares, createdIds, coverageIndex, assetMove.quantity());
    }
    for (OwnershipStakeBook.RebuildMove rebuildMove : rebuildMoves) {
      coverageIndex =
          accumulateCoverage(
              coverage, assetShares, createdIds, coverageIndex, rebuildMove.quantity());
    }
    return new AssetMigrationOutcome(createdIds, coverage);
  }

  /** 把一条随迁移动量记进覆盖率（目标行必须真实存在；重复目标行按 Move 逐笔记，不重复读行内总量）。 */
  private static int accumulateCoverage(
      Map<IndustryId, Map<AssetKind, Long>> coverage,
      Map<AssetShareId, OwnershipStake> assetShares,
      List<AssetShareId> createdIds,
      int index,
      long quantity) {
    OwnershipStake created = assetShares.get(createdIds.get(index));
    if (created == null) {
      throw new IllegalStateException("资产随迁目标份额在表里不存在（拒绝静默丢资产）: " + createdIds.get(index));
    }
    coverage
        .computeIfAbsent(created.industry(), ignored -> new LinkedHashMap<>())
        .merge(created.asset(), quantity, Math::addExact);
    return index + 1;
  }

  /**
   * 可移动资产判据（D-023：SMALL_TOOL/LARGE_TOOL/SHIP/CATTLE/MACHINE 随人走；LAND/WORKSHOP 不跨 hex）。
   *
   * <p>★ <b>W9（2026-10-11）：包内可见</b> —— {@code
   * ModeMigrationPolicy.carriedCapitalByKind}（档位判据）要读同一条判据； 只改可见性，判据本体一字未动（转业档位不得有第二份"什么带得走"的拼写）。
   */
  static boolean isMobileAsset(AssetKind asset) {
    return switch (asset) {
      case SMALL_TOOL, LARGE_TOOL, SHIP, CATTLE, MACHINE -> true;
      case LAND, WORKSHOP -> false;
    };
  }

  /** 随迁新份额的权利性质：共有保持共有；其余一律随目标户自营（owner == operator ⇒ OWNED）。 */
  private static AssetRightKind transferRightKind(OwnershipStake share) {
    return share.kind() == AssetRightKind.COMMUNAL ? AssetRightKind.COMMUNAL : AssetRightKind.OWNED;
  }

  /**
   * 随迁后的 operator = 目标户（D-023 §5.3 的“改 owner/operator 到目标户”）。★ 已被别的组织实际使用的 出租份额不在这里处理：上游 {@code
   * foreignUsedOwnershipStakeIds} 已把它按“留原户并具名”跳过，避免拆掉那个组织的引用。
   */
  private static ActorRef transferOperator(OwnershipStake share, ActorRef targetActor) {
    if (share == null || targetActor == null) {
      throw new IllegalArgumentException("transferOperator 的 share/targetActor 不得为 null");
    }
    return targetActor;
  }

  /** 目标 hex 能承载该 AssetKind 的第一个产业（{@link IndustryHexKeys#at} 已按 id 升序）；无 ⇒ 空。 */
  private static Optional<IndustryId> hostIndustryFor(
      Map<IndustryId, IndustryDefinitionView> industries, HexCoord targetHex, AssetKind asset) {
    for (IndustryId candidate : IndustryHexKeys.at(industries, targetHex.q(), targetHex.r())) {
      IndustryDefinitionView industry = industries.get(candidate);
      if (industry != null && industry.capacityPerUnit().containsKey(asset)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * ★★★ <b>W7（2026-10-11）/ W7-fix（2026-10-11）：把"目标格要按需建商号、但这一笔**带不来作业资本**"的迁移笔**具名跳过**</b>。
   *
   * <pre>
   * 触发条件（两条同时成立）：
   *   ① mode = merchant 且目标格本来没有 merchant 制度的产业模板（= 本批要按需建 trade@hex）
   *   ② 这一笔**实际能随迁携带**的作业资本（★ W7-fix 起 = 工具；改前 = 牲口）≤ 0
   * 判据（与 {@code migrateAssetsForMove} 的取整逐式对齐）：
   *   范围 = owner == 源户 actor ∧ asset ∈ {@link MerchantTradeTemplate#capacityKinds()} ∧ quantity &gt; 0
   *          ∧ 未被别的组织实际使用
   *   取整 = 整户迁空 ⇒ 全部；否则 ⇒ ⌊数量 × 本笔人口 ÷ 该源户当时的投影人口⌋
   *   ★ "当时" = 同一源户前面的迁移笔已扣掉它们搬走的人口与份额（顺序 = 计划序 ⇒ 确定性）
   * 处置 = INFO 具名跳过（不建商号、不搬人、不搬资产）；其余笔一字不动
   * </pre>
   *
   * <p>★ <b>为什么必须有这道闸</b>：{@code createEnterpriseAndProcess} 在"新建户凑不齐目标产业的租赁资产"时
   * 是**当场抛**的（fail-closed）—— 而"按需建的 trade@hex 下唯一能有的作业资本就是这一笔背过来的那一份"。 决策层（{@code
   * ExpectedProfitBook} + {@code perMoveNewRoom}）已经按同一条口径挡过一轮，这里是执行期兜底： 挡住 = 一笔迁移不发生（可读、可重试），挡不住 =
   * 整个世界卡死。
   *
   * <p>★★ <b>W7-fix：判据从牲口换成工具</b>（用户裁定 v3.28 §7「能不能先把牲口要求取消掉啊？…算作工具需求得了」）—— 与模板 {@code
   * capacityPerUnit = {SMALL_TOOL: TOOL_PER_UNIT}} 同源；工具是**可移动资产**（{@code isMobileAsset} 含
   * SMALL_TOOL）， 随迁携带那一条路照旧成立。★ 理由字面量随之改名（{@code no-carrier-asset-… → no-tool-input-…}，§一.11 不留旧名）。
   */
  private static List<ModeMigrationPolicy.MigrationMove> filterOnDemandMerchantMoves(
      List<ModeMigrationPolicy.MigrationMove> moves,
      Set<HexCoord> onDemandTradeHexes,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      EconomyDayView base,
      long day) {
    if (onDemandTradeHexes.isEmpty()) {
      return moves;
    }
    // 逐源户模拟（键序 = 计划里的首次出现序；同一源户的多笔按计划序扣减 ⇒ 与执行期同序）。
    Map<HouseholdId, Long> projectedPopulation = new LinkedHashMap<>();
    Map<HouseholdId, Long> carriedCarrierAssetsLeft = new LinkedHashMap<>();
    List<ModeMigrationPolicy.MigrationMove> accepted = new ArrayList<>();
    for (ModeMigrationPolicy.MigrationMove move : moves) {
      boolean needsOnDemandIndustry =
          DefaultProductionModes.MERCHANT.equals(move.targetMode())
              && onDemandTradeHexes.contains(move.targetHex());
      HouseholdEconomy row = householdEconomies.get(move.source());
      if (!needsOnDemandIndustry || row == null) {
        accepted.add(move);
        continue;
      }
      long population =
          projectedPopulation.computeIfAbsent(move.source(), ignored -> row.population());
      long carrierAssetLeft =
          carriedCarrierAssetsLeft.computeIfAbsent(
              move.source(),
              ignored -> carriedCarrierAssets(row, assetShares, enterprises, move.source()));
      boolean empties = population <= 0L || move.population() >= population;
      long carry =
          empties
              ? carrierAssetLeft
              : Math.multiplyExact(carrierAssetLeft, move.population()) / Math.max(1L, population);
      if (carry <= 0L) {
        // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗？」）：先问"**买不买得到**"。
        //   判据 = 目标格的市场交易工具这个商品（买 / 赊购通道）∧ 本格有**闲置**的同种作业资本（可租）。
        //   成立 ⇒ 放行（具名 INFO：走购买/租佃通道；真的租到由 createEnterpriseAndProcess 的放宽分支完成）。
        MarketTable market = marketForHex(base, move.targetHex());
        boolean toolTraded =
            market != null && market.hasPrice(MerchantTradeTemplate.TOOL_COMMODITY);
        long idleToolMilli = idleToolInHex(assetShares, enterprises, units, move.targetHex());
        if (toolTraded && idleToolMilli > 0L) {
          EventLog.channel(LOG)
              .info(
                  LogEvent.of(
                      "ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE",
                      EconomyLogSource.ECONOMY_MIGRATION,
                      "day",
                      day,
                      "source",
                      move.source().value(),
                      "target",
                      move.target().value(),
                      "targetHex",
                      move.targetHex(),
                      "targetMode",
                      move.targetMode().value(),
                      "population",
                      move.population(),
                      "carriedToolInput",
                      carry,
                      "toolUnitPriceMilli",
                      market.priceOf(MerchantTradeTemplate.TOOL_COMMODITY),
                      "idleToolMilliInHex",
                      idleToolMilli,
                      "reason",
                      "no-tool-input-bought-or-leased-at-market"));
          projectedPopulation.put(move.source(), Math.max(0L, population - move.population()));
          accepted.add(move);
          continue;
        }
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    move.source().value(),
                    "target",
                    move.target().value(),
                    "targetHex",
                    move.targetHex(),
                    "targetMode",
                    move.targetMode().value(),
                    "population",
                    move.population(),
                    // ★ W7-fix：字段名随作业资本那一维改名（改前 = carriedCattle）。
                    "carriedToolInput",
                    carry,
                    // ★ W9：把"到底卡在哪一条"补上（市场有没有这一行 / 本格有没有闲着的工具）。
                    "toolTradedAtMarket",
                    toolTraded,
                    "idleToolMilliInHex",
                    idleToolMilli,
                    "reason",
                    "no-tool-input-to-host-on-demand-trade-industry"));
        continue;
      }
      projectedPopulation.put(move.source(), Math.max(0L, population - move.population()));
      carriedCarrierAssetsLeft.put(move.source(), Math.max(0L, carrierAssetLeft - carry));
      accepted.add(move);
    }
    return List.copyOf(accepted);
  }

  /**
   * ★★ <b>W9：目标格所属市场的价表（执行期可用、不依赖 topology）</b> —— 与 {@code PrimaryModeRanking.marketOf(markets,
   * topology, hex)} 是**同一张表**（区内一张，键 = 市场区 id）：执行期手里只有 {@link EconomyDayView}，故用 {@link
   * MarketZoneBook#zoneOfHex(EconomyDayView, HexCoord)} 求区。没有区 / 没有这一行 ⇒ null（"这格没有市场"是合法状态，不是故障）。
   */
  private static MarketTable marketForHex(EconomyDayView base, HexCoord hex) {
    return MarketZoneBook.zoneOfHex(base, hex)
        .map(zone -> base.marketTables().get(zone.zoneId()))
        .orElse(null);
  }

  /**
   * ★★ <b>W9：本格里"闲置的"作业资本有多少（{@link MerchantTradeTemplate#capacityKinds()} 那一维，毫）</b> ——
   * 它是"这户就算没带工具，也买/租得到"的**物理前提**（W2 起没钱也能买 ⇒ 唯一还要问的是"有没有闲着的"）。
   *
   * <p>★ <b>同一把尺</b>：闲置判据 = {@link ModeMigrationPolicy#isIdleShare}（没有任何组织/在产 unit 在用）， 与 {@code
   * planAssetMoves}、与"同格跨产业放宽"那一条完全同源；只数**同一格**的份额（跨格搬工具要运力，本批不引入）。
   */
  private static long idleToolInHex(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      HexCoord hex) {
    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedOwnershipStakes(enterprises, units, assetShares);
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<AssetKind> capacityKinds = MerchantTradeTemplate.capacityKinds();
    long total = 0L;
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || !capacityKinds.contains(share.asset())
          || share.quantity() <= 0L
          || !ModeMigrationPolicy.isIdleShare(share, claimed)) {
        continue;
      }
      if (IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isEmpty()) {
        continue;
      }
      total = Math.addExact(total, share.quantity());
    }
    return total;
  }

  /**
   * ★ W7 / W7-fix：一个源户"这一批迁移能带走的**作业资本**总量" —— 判据与 {@code migrateAssetsForMove} 同源 （owner == 源户 ∧
   * 未被别的组织实际使用）。★ 只统计**可移动**资产（{@code isMobileAsset}；{@code SMALL_TOOL} 在其中）。
   *
   * <p>★★ W7-fix（用户裁定 v3.28 §7）：那一维从 {@code CATTLE} 换成 {@link
   * MerchantTradeTemplate#capacityKinds()}（= 模板的 {@code capacityPerUnit} 键集，唯一拼写点） ——
   * 牲口不再是跑商的可行性门槛，也不再是运力的来源。
   */
  private static long carriedCarrierAssets(
      HouseholdEconomy row,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      HouseholdId source) {
    ActorRef sourceActor = HouseholdActors.of(source);
    Set<AssetShareId> foreignUsed = foreignUsedOwnershipStakeIds(enterprises, sourceActor);
    List<AssetKind> capacityKinds = MerchantTradeTemplate.capacityKinds();
    long total = 0L;
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || !capacityKinds.contains(share.asset())
          || share.quantity() <= 0L
          || !share.owner().equals(sourceActor)
          || foreignUsed.contains(share.id())) {
        continue;
      }
      total = Math.addExact(total, share.quantity());
    }
    return total;
  }

  /** 被 organizer != 源户的组织在 assetSources 里实际使用的份额 id 并集（这些份额本批不搬，留原户并具名）。 */
  private static Set<AssetShareId> foreignUsedOwnershipStakeIds(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises, ActorRef sourceActor) {
    Set<AssetShareId> used = new LinkedHashSet<>();
    for (ProductionEnterprise enterprise : enterprises.values()) {
      if (enterprise != null && !enterprise.organizer().equals(sourceActor)) {
        used.addAll(enterprise.assetSources());
      }
    }
    return used;
  }

  /** 整条随迁后旧 id 已删：把源户自己的组织 assetSources 里的这些 id 摘掉（部分随迁 id 仍在、数量已减，不动）。 */
  private static void removeSourceEnterpriseAssetSources(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      ActorRef sourceActor,
      Set<AssetShareId> removedShareIds) {
    if (removedShareIds.isEmpty()) {
      return;
    }
    List<ProductionOrganizationId> enterpriseIds = new ArrayList<>(enterprises.keySet());
    enterpriseIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : enterpriseIds) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null || !enterprise.organizer().equals(sourceActor)) {
        continue;
      }
      List<AssetShareId> updated = new ArrayList<>(enterprise.assetSources());
      if (!updated.removeIf(removedShareIds::contains)) {
        continue;
      }
      enterprises.put(
          organizationId,
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              updated,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
  }

  /**
   * ★★ D-023：本一次 apply 内新建的目标户，其组织 assetSources 必须指向随迁后的新份额 id（含后续源并入同一新户）。 只收 operator ==
   * organizer 的份额（EconomyData 的「组织使用的份额必须由 organizer 经营」守卫）。
   */
  private static void attachMigratedSharesToNewTargetEnterprises(
      Set<HouseholdId> createdTargets,
      Map<HouseholdId, List<AssetShareId>> migratedShareIdsByTarget,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    if (migratedShareIdsByTarget.isEmpty()) {
      return;
    }
    List<HouseholdId> targets = new ArrayList<>(migratedShareIdsByTarget.keySet());
    targets.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId target : targets) {
      if (!createdTargets.contains(target)) {
        continue; // 已有目标户不在此路（其组织不是本批新建；随迁份额仍按 operator 计产能）
      }
      ProductionEnterprise enterprise = enterpriseOf(target, householdEconomies, enterprises);
      if (enterprise == null) {
        continue; // DISPLACED 新建户不建组织
      }
      List<AssetShareId> updated = new ArrayList<>(enterprise.assetSources());
      for (AssetShareId shareId : migratedShareIdsByTarget.get(target)) {
        OwnershipStake share = assetShares.get(shareId);
        if (share == null || !share.operator().equals(enterprise.organizer())) {
          continue;
        }
        if (!updated.contains(shareId)) {
          updated.add(shareId);
        }
      }
      if (updated.size() == enterprise.assetSources().size()) {
        continue;
      }
      enterprises.put(
          enterprise.id(),
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              updated,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
  }

  /** ★ D-023 留原户资产的具名读数（瞬态 ProductionLedger；不新增持久组件、改数量为 0）。 */
  private static void recordResidualAssetAudits(
      ProductionLedger.Accumulator auditLedger,
      long day,
      HouseholdId source,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<AssetShareId, String> residualReasons) {
    if (auditLedger == null || residualReasons.isEmpty()) {
      return;
    }
    List<AssetShareId> shareIds = new ArrayList<>(residualReasons.keySet());
    shareIds.sort(Comparator.comparing(AssetShareId::value));
    for (AssetShareId shareId : shareIds) {
      OwnershipStake share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L) {
        continue; // 后来又被随迁走了：不需要“留原户”读数
      }
      auditLedger.addLiquidationAudit(
          new ProductionLedger.LiquidationAudit(
              day,
              "migration-retained-asset",
              Optional.of(source),
              Optional.empty(),
              Optional.empty(),
              Optional.of(shareId),
              Optional.empty(),
              share.quantity(),
              0L,
              0L,
              0L,
              0L,
              residualReasons.get(shareId),
              Map.of("assetKindOrdinal", (long) share.asset().ordinal())));
    }
  }

  // ── D-023 第 5 项：新家户的完整生产关系模板 ─────────────────────────────────────────────────

  /**
   * ★★ <b>D-023 第 5 项：按目标 mode + 目标产业 regime 生成完整 {@link ProductionRules}</b>。
   *
   * <pre>
   * tenancy_fixed_kind / tenancy_share / tenancy_cash → RegimeRelations 的 tenant 档（租佃模板）
   * wage_farm                                          → feudal 档（雇农/庄园模板）
   * handicraft_workshop                                → handicraft 档（手工业模板）
   * family_farm                                        → household 档（家户自用模板）
   * merchant                                           → RegimeRelations 未登记 merchant 档 ⇒ 具名回退空规则
   * displaced                                          → 不建组织（P11.1 旁路，本方法不会被调用）
   * 其余 mode                                          → 目标产业 regime（仅当已登记）；否则具名回退
   * </pre>
   *
   * <p>★ 规则里的受方/工资/租率全部来自 {@link RegimeRelations#defaultRelation}；组织 inputSources/outputOwnership
   * 也取自同一关系。回退空规则时把原因同时写进持久 {@code relationTemplateRef} 与当天瞬态 ledger 审计，绝不静默。
   *
   * <p>★★ <b>GAP-3 同源收口</b>：模板里的 {@code ToCohort(view)} 受方必须像 E2 自动组织一样在**落盘前**用当前 rows 归一成 {@link
   * Payee.ToHousehold}（唯一视图）或退回最小自留关系；否则本关系会在**当天** harvest 时被 {@code requireCohortRows}
   * 的"视图不再是唯一身份"守卫拒绝。归一逻辑不另写一份，直接复用 {@link EconomyEnterpriseSettlement} 的唯一拼写点。
   */
  private static MigrationRelationPlan buildMigrationRelation(
      ModeMigrationPolicy.MigrationMove move,
      ProductionUnitId unitId,
      IndustryId industryId,
      ActorRef targetActor,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HouseholdEconomy targetHouseholdEconomy,
      EconomyDayView base,
      long day,
      ProductionLedger.Accumulator auditLedger,
      Map<IndustryId, IndustryDefinitionView> industries) {
    MigrationRelationPlan plan =
        planMigrationRelation(
            move,
            unitId,
            industryId,
            targetActor,
            householdEconomies,
            targetHouseholdEconomy,
            base,
            day,
            auditLedger);
    // ★★★ W2-fix7 / D1①（控制方裁定 v3.10 §7 第 ①/③ 条）：**绝不落一条 E14 非法的关系** ——
    //   迁移决策处（ModeMigrationPolicy）已经按"实物腿商品拿不到就不迁入"挡过一轮，这里是**执行期的兜底**：
    //   计划期的产业（industriesForMode 的第一个）与执行期实际选中的产业（第一个租赁资产凑得齐的）可能不同，
    //   而 E14 对"除实物租腿以外的每一档"仍然 fail-closed（裁定第 ③ 条）⇒ 落一条那样的关系 = 下次 harvest
    //   整段 advance 被拒（世界卡死）。
    //   ★ 处置 = 退回**具名**空规则（产出全归 operator，既有显式允许的缺省路径）+ 当天 ledger 审计 + INFO 一条；
    //     绝不静默、也绝不让世界死。★ 实物租腿（FIXED_IN_KIND_RENT）**不在此列**：它走三级支付链
    //     （ProductionSettlement），欠租是可读的缺口/债权，不是配置错误。
    IndustryDefinitionView industry = industries.get(industryId);
    if (industry != null) {
      for (CompensationRule rule : plan.relation().rules()) {
        if (rule.commodity().isEmpty() || rule.type() == RuleType.FIXED_IN_KIND_RENT) {
          continue;
        }
        CommodityId commodity = rule.commodity().get();
        if (industry.outputPerUnit().containsKey(commodity)) {
          continue;
        }
        String reason =
            "e14-illegal-in-kind-leg:"
                + rule.type()
                + ":"
                + commodity.value()
                + ":industryOutputs="
                + industry.outputPerUnit().keySet();
        recordRelationFallbackAudit(auditLedger, day, move, reason);
        EventLog.channel(EconomyLog.migration())
            .info(
                LogEvent.of(
                    "MIGRATION_RELATION_E14_ILLEGAL_FALLBACK",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "target",
                    move.target().value(),
                    "targetMode",
                    move.targetMode().value(),
                    "industry",
                    industryId.value(),
                    "ruleType",
                    rule.type().name(),
                    "commodity",
                    commodity.value(),
                    "industryOutputs",
                    industry.outputPerUnit().keySet().toString(),
                    "why",
                    "E14 对非租腿档仍 fail-closed ⇒ 不落非法关系，退回具名空规则（产出全归 operator）"));
        return new MigrationRelationPlan(
            fallbackMigrationRelation(unitId, targetActor),
            "migration:" + move.reason() + ":fallback-empty:" + reason);
      }
    }
    return plan;
  }

  /** 关系模板的**装配本体**（{@link #buildMigrationRelation} 的 E14 兜底之外的原有逻辑，逐字未改）。 */
  private static MigrationRelationPlan planMigrationRelation(
      ModeMigrationPolicy.MigrationMove move,
      ProductionUnitId unitId,
      IndustryId industryId,
      ActorRef targetActor,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HouseholdEconomy targetHouseholdEconomy,
      EconomyDayView base,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    Optional<RegimeId> templateRegime =
        migrationTemplateRegime(move.targetMode(), industryId, base);
    Set<ResidenceKind> residences = Set.of(targetHouseholdEconomy.view().residence());
    if (templateRegime.isPresent()) {
      try {
        ProductionRules relation =
            RegimeRelations.defaultRelation(
                templateRegime.get(), unitId, industryId, targetActor, residences);
        ProductionRules normalized =
            EconomyEnterpriseSettlement.normalizePayees(
                relation, householdEconomies, targetHouseholdEconomy.view().hex());
        if (normalized != null) {
          return new MigrationRelationPlan(
              normalized, "migration:" + move.reason() + ":regime:" + templateRegime.get().value());
        }
        String reason = "recipient-unresolved:" + templateRegime.get().value();
        recordRelationFallbackAudit(auditLedger, day, move, reason);
        return new MigrationRelationPlan(
            fallbackMigrationRelation(unitId, targetActor),
            "migration:" + move.reason() + ":fallback-empty:" + reason);
      } catch (IllegalArgumentException ignored) {
        String reason = "template-failed:" + templateRegime.get().value();
        recordRelationFallbackAudit(auditLedger, day, move, reason);
        return new MigrationRelationPlan(
            fallbackMigrationRelation(unitId, targetActor),
            "migration:" + move.reason() + ":fallback-empty:" + reason);
      }
    }
    String reason =
        DefaultProductionModes.MERCHANT.equals(move.targetMode())
            ? "merchant-regime-unregistered"
            : "target-regime-unregistered";
    recordRelationFallbackAudit(auditLedger, day, move, reason);
    return new MigrationRelationPlan(
        fallbackMigrationRelation(unitId, targetActor),
        "migration:" + move.reason() + ":fallback-empty:" + reason);
  }

  /**
   * mode → 关系模板 regime（唯一映射点；未知/未登记 ⇒ 空 = 具名回退，不猜别的制度）。
   *
   * <p>★ <b>W2-fix7 / D1①：包内可见</b>（改前 private）—— {@code ModeMigrationPolicy} 的"目标生产方式的实物腿商品是否
   * 在这条产业的产出表里"这道迁移决策约束要读同一份 mode→模板映射。★ <b>不许在策略侧另写一份</b>：两份映射漂开会让
   * 决策判的模板与执行期落的模板不是同一个（那正是本仓最反对的"同一件事两处拼写"）。
   */
  static Optional<RegimeId> migrationTemplateRegime(
      ProductionModeId mode, IndustryId industryId, EconomyDayView base) {
    if (DefaultProductionModes.TENANCY_FIXED_KIND.equals(mode)
        || DefaultProductionModes.TENANCY_SHARE.equals(mode)
        || DefaultProductionModes.TENANCY_CASH.equals(mode)) {
      return Optional.of(new RegimeId(RegimeOperators.TENANT));
    }
    if (DefaultProductionModes.WAGE_FARM.equals(mode)) {
      return Optional.of(new RegimeId(RegimeOperators.FEUDAL));
    }
    if (DefaultProductionModes.HANDICRAFT_WORKSHOP.equals(mode)) {
      return Optional.of(new RegimeId(RegimeOperators.HANDICRAFT));
    }
    if (DefaultProductionModes.FAMILY_FARM.equals(mode)) {
      // ★ family_farm 没有自己的产业模板（复用农场的产业 regime=feudal）；HOUSEHOLD 默认规则的
      //   CLOTH 不在农场产出表里（harvest 的 E14 会当场拒）⇒ 有已登记产业 regime 时用它，否则退回 household。
      IndustryDefinitionView industry = base.industries().get(industryId);
      if (industry != null && RegimeRelations.registered().containsKey(industry.regime().value())) {
        return Optional.of(industry.regime());
      }
      return Optional.of(new RegimeId(RegimeOperators.HOUSEHOLD));
    }
    if (DefaultProductionModes.MERCHANT.equals(mode)) {
      return RegimeRelations.registered().containsKey(RegimeOperators.MERCHANT)
          ? Optional.of(new RegimeId(RegimeOperators.MERCHANT))
          : Optional.empty();
    }
    IndustryDefinitionView industry = base.industries().get(industryId);
    if (industry != null && RegimeRelations.registered().containsKey(industry.regime().value())) {
      return Optional.of(industry.regime());
    }
    return Optional.empty();
  }

  /** 具名回退：空规则 = 产出全归 operator（显式允许的缺省路径，但绝不在无具名原因时使用）。 */
  private static ProductionRules fallbackMigrationRelation(
      ProductionUnitId unitId, ActorRef targetActor) {
    return new ProductionRules(
        unitId,
        targetActor,
        new Payee.ToActor(targetActor),
        List.of(),
        targetActor,
        LaborSource.SELF);
  }

  private static void recordRelationFallbackAudit(
      ProductionLedger.Accumulator auditLedger,
      long day,
      ModeMigrationPolicy.MigrationMove move,
      String reason) {
    if (auditLedger == null) {
      return;
    }
    auditLedger.addLiquidationAudit(
        new ProductionLedger.LiquidationAudit(
            day,
            "migration-relation-fallback",
            Optional.of(move.target()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            0L,
            0L,
            0L,
            0L,
            0L,
            "relation-template-fallback:" + reason,
            Map.of("fallbackEmptyRules", 1L)));
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────────────────────

  private static ProductionEnterprise enterpriseOf(
      HouseholdId household,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    ActorRef actor = HouseholdActors.of(household);
    ProductionEnterprise best = null;
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : ids) {
      ProductionEnterprise enterprise = enterprises.get(id);
      if (enterprise != null && enterprise.organizer().equals(actor)) {
        best = enterprise;
        break;
      }
    }
    return best;
  }

  private static ProductionUnitId unitOfActivity(String activity) {
    if (activity == null || activity.isBlank()) {
      return null;
    }
    try {
      return ProductionUnitId.parse(activity);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static boolean hasAnySourceAsset(
      ActorRef sourceActor, Map<AssetShareId, OwnershipStake> assetShares) {
    for (OwnershipStake share : assetShares.values()) {
      if (share.quantity() > 0L
          && (share.owner().equals(sourceActor) || share.operator().equals(sourceActor))) {
        return true;
      }
    }
    return false;
  }

  private static void putOrRemove(Map<CurrencyId, Long> table, CurrencyId key, long amount) {
    if (amount <= 0L) {
      table.remove(key);
    } else {
      table.put(key, amount);
    }
  }
}
