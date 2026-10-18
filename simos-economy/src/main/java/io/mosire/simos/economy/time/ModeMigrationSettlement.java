package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MigrationAssetMove.filterOnDemandMerchantMoves;
import static io.mosire.simos.economy.time.MigrationPopulationMove.applySource;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
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

  static ClassPositionId createNewHousehold(
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
  static SocialClassId stratumForTier(
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
  static ClassPositionId pickTargetPosition(
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

  static boolean producing(ProductionRole position) {
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
  static io.mosire.simos.production.contract.ProductionLocation requireInstanceLocation(
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

  /** 新家户的关系模板落点（关系本体 + 具名来源；失败回退时 templateRef 里写明原因）。 */
  record MigrationRelationPlan(ProductionRules relation, String templateRef) {}

  static MigrationRelationPlan buildMigrationRelation(
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
  static MigrationRelationPlan planMigrationRelation(
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
            0L,
            0L,
            0L,
            0L,
            "relation-template-fallback:" + reason,
            Map.of("fallbackEmptyRules", 1L)));
  }

  static ProductionEnterprise enterpriseOf(
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

  /** 原包内入口：可搬运商品判据委托到 {@link MigrationDebtMove}（判据本体不变）。 */
  static boolean isMobileCommodity(CommodityId commodity) {
    return MigrationDebtMove.isMobileCommodity(commodity);
  }

  /** 原包内入口：可移动资产判据委托到 {@link MigrationAssetMove}（判据本体不变）。 */
  static boolean isMobileAsset(AssetKind asset) {
    return MigrationAssetMove.isMobileAsset(asset);
  }
}
