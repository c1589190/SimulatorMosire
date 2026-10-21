package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionInstanceOutcome;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
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
 * ★★★ <b>D-032 RC4-D：同 key 家户合并的冻结编排</b>。
 *
 * <p>父序列锚点：{@code docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md}
 * §3 的 S2（进入/迁移/组织）与 §6.2 的 同 key 合户；App 在 S3 前调用本编排，使 S3 只看到合并后的活户。
 *
 * <pre>
 * 单笔 source → survivor 固定顺序：
 *   0. preflight（行/账户/政府国库/冻结/planAssetSources/LAND assetShares/债务 id 碰撞）；非 PRODUCTION 承诺 source 跳过
 *   1. production operator reoperate + cycleContributions rekey
 *   2. RC4-C structural rebind（assetShares / relations / roster / enterprise）
 *   3. 双向 debt rekey / 自债净额
 *   4. LAND 全量 transfer（LandLedgerState → LandBook）
 *   5. occupancy / reservation / demand / wageCarry / shipment buyer 重指
 *   6. RC3 HouseholdTransferSettlement（钱/货/债/经验/劳动 + population outbox）
 *   7. source 0 人口壳保留；本类不写 Social、不写 HouseholdEconomy.population/laborMilli
 * </pre>
 *
 * <p>★ <b>原子性边界</b>：所有能在写前完成的具名拒绝都在 preflight 集中完成；后续若遇派生 id 碰撞等契约故障，仍 fail-closed 抛出，由 App 丢弃整次
 * advance 的候选态。production 的候选态与 economy sheet 同属一次 advance，调用方不得在失败后继续 step。
 */
public final class HouseholdMergeSettlement {

  /** 合户人口 outbox 的固定原因标签。 */
  public static final String HOUSEHOLD_MERGE_REASON = "HOUSEHOLD_MERGE";

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private HouseholdMergeSettlement() {}

  /** 具名业务拒绝：INFO 留痕后返回 fail-closed 异常（由 App 丢弃整次 advance）。 */
  private static IllegalStateException reject(String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_MERGE_REJECTED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                reason,
                "detail",
                detail));
    return new IllegalStateException(reason + ": " + detail);
  }

  /** 一次 merge 调用的只读结果；populationTransfers 是本次新产生、已从 session outbox 取走的人口事实。 */
  public record MergeResult(
      int plans,
      int mergedHouseholds,
      long transferredPopulation,
      List<EconomyPopulationTransfer> populationTransfers,
      boolean changed,
      int selfNettedDebtContracts,
      long selfNettedDebtPrincipal,
      ProductionLedger mergeLedger) {

    public MergeResult {
      if (plans < 0 || mergedHouseholds < 0 || transferredPopulation < 0L) {
        throw new IllegalArgumentException("MergeResult 的 plan/户数/人口不得为负");
      }
      if (selfNettedDebtContracts < 0 || selfNettedDebtPrincipal < 0L) {
        throw new IllegalArgumentException("MergeResult 的自债计数/本金不得为负");
      }
      populationTransfers = List.copyOf(populationTransfers);
      Objects.requireNonNull(mergeLedger, "MergeResult.mergeLedger");
    }

    /** 只关心 plans / populationTransfers / changed 的便捷构造（其余读数为 0/空）。 */
    public MergeResult(
        int plans, List<EconomyPopulationTransfer> populationTransfers, boolean changed) {
      this(plans, 0, 0L, populationTransfers, changed, 0, 0L, emptyLedger());
    }

    /** 空结果（不产生任何写入/outbox）。 */
    public static MergeResult empty() {
      return new MergeResult(0, List.of(), false);
    }

    private static ProductionLedger emptyLedger() {
      return new ProductionLedger(
          Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of(), List.of());
    }
  }

  private record PlanOutcome(
      int mergedHouseholds,
      long transferredPopulation,
      int selfNettedDebtContracts,
      long selfNettedDebtPrincipal) {}

  private record SourceOutcome(int selfNettedDebtContracts, long selfNettedDebtPrincipal) {}

  private record PlanPreflight(
      List<HouseholdId> sources, io.mosire.simos.map.hex.HexCoord targetHex) {}

  /**
   * 生成同 key 计划并逐计划执行合并。返回的 populationTransfers 已从 {@link EconomySession} 的瞬态 outbox 取走； App
   * 必须在本方法返回后立即经 {@code MigrationSocialBridge} 落 Social，再进入 step。
   */
  public static MergeResult mergeAll(
      EconomySession session,
      AccountSession accounts,
      HouseholdCultureKeyLookup cultureLookup,
      long day,
      int ordinalStart) {
    Objects.requireNonNull(session, "HouseholdMergeSettlement.mergeAll session");
    Objects.requireNonNull(accounts, "HouseholdMergeSettlement.mergeAll accounts");
    Objects.requireNonNull(cultureLookup, "HouseholdMergeSettlement.mergeAll cultureLookup");
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdMergeSettlement.mergeAll 的 day 不得为负");
    }
    if (ordinalStart < 0) {
      throw new IllegalArgumentException("HouseholdMergeSettlement.mergeAll 的 ordinalStart 不得为负");
    }

    List<HouseholdMergeBook.MergePlan> plans =
        HouseholdMergeBook.plan(
            new WorkingDayView(session.sheet()),
            session.sheet().landHoldingsOrBase(),
            cultureLookup,
            household ->
                OwnershipClassBook.classOf(
                    session.sheet().landHoldingsOrBase(),
                    accounts.householdGoods().getOrDefault(household, java.util.Map.of()),
                    session.sheet().assetShares(),
                    household));
    if (plans.isEmpty()) {
      return MergeResult.empty();
    }

    // 先把全部可前置校验跑完（债务 id 碰撞/账户/冻结/LAND assetShares 等）；非 PRODUCTION 承诺 source 在此过滤。
    // 然后再对任何 work copy 写入。RC4-C 之后才可知的派生 id 碰撞仍允许在写后 fail-closed，由 App 丢弃整次 advance。
    List<PlanPreflight> preflights = new ArrayList<>(plans.size());
    for (HouseholdMergeBook.MergePlan plan : plans) {
      preflights.add(preflightPlan(session, accounts, plan));
    }

    int pendingBefore = session.pendingPopulationTransfers().size();
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
    int executedPlans = 0;
    int mergedHouseholds = 0;
    long transferredPopulation = 0L;
    int selfNettedContracts = 0;
    long selfNettedPrincipal = 0L;
    int ordinal = ordinalStart;
    for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
      PlanPreflight preflight = preflights.get(planIndex);
      if (preflight.sources().isEmpty()) {
        // 过滤掉含非 PRODUCTION 承诺的 source 后只剩 survivor：不执行该 plan，也不产生任何写/outbox。
        continue;
      }
      PlanOutcome outcome =
          executePlan(session, accounts, plans.get(planIndex), preflight, day, ordinal, ledger);
      executedPlans++;
      mergedHouseholds = Math.addExact(mergedHouseholds, outcome.mergedHouseholds());
      transferredPopulation = Math.addExact(transferredPopulation, outcome.transferredPopulation());
      selfNettedContracts = Math.addExact(selfNettedContracts, outcome.selfNettedDebtContracts());
      selfNettedPrincipal = Math.addExact(selfNettedPrincipal, outcome.selfNettedDebtPrincipal());
      ordinal = Math.addExact(ordinal, outcome.mergedHouseholds());
    }
    List<EconomyPopulationTransfer> transfers = drainNewTransfers(session, pendingBefore);
    requireTransferPopulation(transfers, transferredPopulation);
    return new MergeResult(
        executedPlans,
        mergedHouseholds,
        transferredPopulation,
        transfers,
        executedPlans > 0,
        selfNettedContracts,
        selfNettedPrincipal,
        ledger.toLedger());
  }

  /** 执行一个合并计划；供测试/后续 RC6 复用。返回的 outbox 已从 session 取走。 */
  public static MergeResult mergeOne(
      EconomySession session,
      AccountSession accounts,
      HouseholdMergeBook.MergePlan plan,
      long day,
      int ordinal) {
    Objects.requireNonNull(session, "HouseholdMergeSettlement.mergeOne session");
    Objects.requireNonNull(accounts, "HouseholdMergeSettlement.mergeOne accounts");
    Objects.requireNonNull(plan, "HouseholdMergeSettlement.mergeOne plan");
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdMergeSettlement.mergeOne 的 day 不得为负");
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException("HouseholdMergeSettlement.mergeOne 的 ordinal 不得为负");
    }

    PlanPreflight preflight = preflightPlan(session, accounts, plan);
    if (preflight.sources().isEmpty()) {
      // 过滤掉含非 PRODUCTION 承诺的 source 后只剩 survivor：不执行该 plan。
      return MergeResult.empty();
    }
    int pendingBefore = session.pendingPopulationTransfers().size();
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
    PlanOutcome outcome = executePlan(session, accounts, plan, preflight, day, ordinal, ledger);
    List<EconomyPopulationTransfer> transfers = drainNewTransfers(session, pendingBefore);
    requireTransferPopulation(transfers, outcome.transferredPopulation());
    return new MergeResult(
        1,
        outcome.mergedHouseholds(),
        outcome.transferredPopulation(),
        transfers,
        true,
        outcome.selfNettedDebtContracts(),
        outcome.selfNettedDebtPrincipal(),
        ledger.toLedger());
  }

  // ── 一个 plan：先 preflight，再按稳定 source 序逐笔执行 ─────────────────────────────────────

  private static PlanOutcome executePlan(
      EconomySession session,
      AccountSession accounts,
      HouseholdMergeBook.MergePlan plan,
      PlanPreflight preflight,
      long day,
      int ordinalStart,
      ProductionLedger.Accumulator ledger) {
    LinkedHashMap<HouseholdId, HouseholdEconomy> rows = session.sheet().householdEconomies();
    HouseholdEconomy survivorRow = rows.get(plan.survivor());
    if (survivorRow == null) {
      throw new IllegalStateException(
          "household-merge-survivor-missing: " + plan.survivor().value());
    }
    long projectedTargetPopulation = survivorRow.population();
    int merged = 0;
    long transferredPopulation = 0L;
    int selfNettedContracts = 0;
    long selfNettedPrincipal = 0L;
    for (HouseholdId source : preflight.sources()) {
      HouseholdEconomy sourceRow = rows.get(source);
      if (sourceRow == null) {
        throw new IllegalStateException("household-merge-source-missing: " + source.value());
      }
      long sourcePopulation = sourceRow.population();
      SourceOutcome sourceOutcome =
          executeSource(
              session,
              accounts,
              plan.survivor(),
              source,
              sourcePopulation,
              projectedTargetPopulation,
              preflight.targetHex(),
              day,
              ordinalStart + merged,
              ledger);
      projectedTargetPopulation = Math.addExact(projectedTargetPopulation, sourcePopulation);
      transferredPopulation = Math.addExact(transferredPopulation, sourcePopulation);
      selfNettedContracts =
          Math.addExact(selfNettedContracts, sourceOutcome.selfNettedDebtContracts());
      selfNettedPrincipal =
          Math.addExact(selfNettedPrincipal, sourceOutcome.selfNettedDebtPrincipal());
      merged++;
    }

    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_MERGE_PLAN_APPLIED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "survivor",
                  plan.survivor().value(),
                  "sources",
                  preflight.sources(),
                  "mergedHouseholds",
                  merged,
                  "transferredPopulation",
                  transferredPopulation));
    }
    return new PlanOutcome(merged, transferredPopulation, selfNettedContracts, selfNettedPrincipal);
  }

  private static PlanPreflight preflightPlan(
      EconomySession session, AccountSession accounts, HouseholdMergeBook.MergePlan plan) {
    Objects.requireNonNull(session, "preflight session");
    Objects.requireNonNull(accounts, "preflight accounts");
    Objects.requireNonNull(plan, "preflight plan");

    EconomyStateBuilder sheet = session.sheet();
    HouseholdId survivor = plan.survivor();
    LinkedHashMap<HouseholdId, HouseholdEconomy> rows = sheet.householdEconomies();
    if (!rows.containsKey(survivor)) {
      throw new IllegalStateException("household-merge-survivor-missing: " + survivor.value());
    }
    if (accounts.accountOrNull(survivor) == null) {
      throw new IllegalStateException(
          "household-merge-survivor-account-missing: " + survivor.value());
    }

    Set<HouseholdId> memberIds = new LinkedHashSet<>();
    for (HouseholdMergeBook.Member member : plan.members()) {
      if (!memberIds.add(member.household())) {
        throw new IllegalArgumentException(
            "household-merge-duplicate-member: " + member.household().value());
      }
    }
    if (!memberIds.contains(survivor)) {
      throw new IllegalArgumentException(
          "household-merge-survivor-not-member: " + survivor.value());
    }
    List<HouseholdId> sources = new ArrayList<>(memberIds);
    sources.remove(survivor);
    sources.sort(Comparator.comparing(HouseholdId::value));
    if (sources.isEmpty()) {
      throw new IllegalArgumentException(
          "household-merge-plan-without-source: " + survivor.value());
    }

    List<HouseholdId> executableSources = new ArrayList<>(sources.size());
    for (HouseholdId source : sources) {
      List<HouseholdLaborCommitment> nonProductionCommitments =
          preflightSource(session, accounts, source, survivor, sheet, rows);
      if (!nonProductionCommitments.isEmpty()) {
        for (HouseholdLaborCommitment commitment : nonProductionCommitments) {
          logSkippedNonProductionCommitment(source, commitment);
        }
        // GOV_SERVICE 等承诺不能静默重绑/降级/丢失：跳过该 source，但其它的 source 仍可合入。
        continue;
      }
      executableSources.add(source);
    }

    // 债务新 id 碰撞必须在任何 work copy 写入前跑完；按可执行 source 稳定序模拟整组重键。
    Map<DebtContractId, DebtContract> stagedDebts = new LinkedHashMap<>(sheet.debtContracts());
    for (HouseholdId source : executableSources) {
      stagedDebts = HouseholdDebtMergeBook.plan(stagedDebts, source, survivor).contracts();
    }

    HouseholdEconomy survivorRow = rows.get(survivor);
    return new PlanPreflight(executableSources, survivorRow.view().hex());
  }

  /** 冻结判据：只有非零值才算"有冻结"。零值冻结项等价于无冻结；负数属坏数据，按正数同样 fail-closed。 null 值不构成冻结。 */
  private static boolean hasPositiveFrozenMoney(AccountSession.ActorAccount account) {
    for (Long value : account.frozenMoney().values()) {
      if (value != null && value != 0L) {
        return true;
      }
    }
    return false;
  }

  /** 冻结判据同 {@link #hasPositiveFrozenMoney(AccountSession.ActorAccount)}，作用于商品冻结表。 */
  private static boolean hasPositiveFrozenGoods(AccountSession.ActorAccount account) {
    for (Long value : account.frozenGoods().values()) {
      if (value != null && value != 0L) {
        return true;
      }
    }
    return false;
  }

  /**
   * 校验 source 的不可合并事实，并返回会阻止合户的非 PRODUCTION 承诺。
   *
   * <p>非 PRODUCTION 承诺本身不在这里抛出：调用方会过滤该 source；其余真正非法条件仍在写前 fail-closed。
   */
  private static List<HouseholdLaborCommitment> preflightSource(
      EconomySession session,
      AccountSession accounts,
      HouseholdId source,
      HouseholdId survivor,
      EconomyStateBuilder sheet,
      Map<HouseholdId, HouseholdEconomy> rows) {
    if (source.equals(survivor)) {
      throw new IllegalArgumentException(
          "household-merge-source-equals-survivor: " + source.value());
    }
    HouseholdEconomy sourceRow = rows.get(source);
    if (sourceRow == null) {
      throw new IllegalStateException("household-merge-source-missing: " + source.value());
    }
    if (sourceRow.population() <= 0L) {
      throw reject("household-merge-source-population-zero", "source=" + source.value());
    }
    if (accounts.accountOrNull(source) == null) {
      throw new IllegalStateException("household-merge-source-account-missing: " + source.value());
    }
    ActorRef sourceActor = HouseholdActors.of(source);

    AccountSession.ActorAccount sourceAccount = accounts.accountOrNull(source);
    if (hasPositiveFrozenMoney(sourceAccount)) {
      throw reject(
          "household-merge-frozen-account",
          "source=" + source.value() + " frozenMoney=" + sourceAccount.frozenMoney());
    }
    if (hasPositiveFrozenGoods(sourceAccount)) {
      throw reject(
          "household-merge-frozen-account",
          "source=" + source.value() + " frozenGoods=" + sourceAccount.frozenGoods());
    }

    for (var government : sheet.governmentsOrBase().values()) {
      if (government.treasury().equals(sourceActor)) {
        throw reject(
            "household-merge-government-treasury",
            "source=" + source.value() + " government=" + government.id().value());
      }
    }

    Optional<EconomyMeta> meta = sheet.meta();
    if (meta.isPresent() && meta.get().planAssetSources().containsValue(sourceActor)) {
      throw reject("household-merge-plan-asset-source", "source=" + source.value());
    }

    List<HouseholdLaborCommitment> nonProductionCommitments = new ArrayList<>();
    for (HouseholdLaborCommitment commitment : sheet.laborCommitments().values()) {
      if (commitment.household().equals(source)
          && commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        nonProductionCommitments.add(commitment);
      }
    }
    nonProductionCommitments.sort(Comparator.comparing(commitment -> commitment.id().value()));

    Map<io.mosire.simos.economy.api.id.IndustryId, IndustryDefinitionView> industries =
        session.productionRuntime().definitions().industries();
    for (var stake : sheet.assetShares().values()) {
      boolean ownerHit = stake.owner().equals(sourceActor);
      boolean operatorHit = stake.operator().equals(sourceActor);
      if (!ownerHit && !operatorHit) {
        continue;
      }
      if (stake.asset() == io.mosire.simos.actor.api.asset.AssetKind.LAND) {
        throw reject(
            "household-merge-land-in-assetshares",
            "source=" + source.value() + " share=" + stake.id().value());
      }
      if (!industries.containsKey(stake.industry())) {
        throw reject(
            "household-merge-asset-industry-missing",
            "share=" + stake.id().value() + " industry=" + stake.industry().value());
      }
      if (stake.quantity() <= 0L) {
        throw reject(
            "household-merge-zero-quantity-stake",
            "share=" + stake.id().value() + " quantity=" + stake.quantity());
      }
    }
    return List.copyOf(nonProductionCommitments);
  }

  private static void logSkippedNonProductionCommitment(
      HouseholdId source, HouseholdLaborCommitment commitment) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_MERGE_SKIPPED_NON_PRODUCTION_COMMITMENT",
                EconomyLogSource.ECONOMY_MIGRATION,
                "source",
                source.value(),
                "commitment",
                commitment.id().value(),
                "kind",
                commitment.kind().name()));
  }

  // ── 单笔执行（冻结顺序）────────────────────────────────────────────────────────────────────

  private static SourceOutcome executeSource(
      EconomySession session,
      AccountSession accounts,
      HouseholdId survivor,
      HouseholdId source,
      long sourcePopulation,
      long targetPopulationBefore,
      io.mosire.simos.map.hex.HexCoord targetHex,
      long day,
      int ordinal,
      ProductionLedger.Accumulator ledger) {
    // 1) production operator / cycle contributions（先 operator，再 cycle rekey）。
    rebindProduction(session, source, survivor);

    // 2) RC4-C structural rebind；必须拿回旧→新 assetShareId 映射。
    HouseholdMergeRebindBook.RebindOutcome structural =
        HouseholdMergeRebindBook.rebind(
            session.sheet(),
            session.productionRuntime().definitions().industries(),
            source,
            survivor);

    // 3) 双向债务重键 / 自债净额。
    HouseholdDebtMergeBook.Rework debt =
        HouseholdDebtMergeBook.apply(
            session.sheet().debtContracts(), source, survivor, day, ledger);

    // 4) LAND 全量转移（唯一 LandBook 写口）。
    long landTransferred = transferLand(session, source, survivor);

    // 5) 其余引用：occupancy / reservation / demand / wageCarry / shipment buyer。
    int occupancies = rebindOccupancyGrants(session, structural.assetIdMapping());
    int reservations = rebindInventoryReservations(session, source, survivor);
    int demands = rekeyHouseholdDemands(session, source, survivor);
    int wageCarries = rebindWageCarry(session, source, survivor);
    int shipmentAllocations = rebindShipmentBuyers(session, source, survivor);

    // 6) RC3 价值/人口腿：人口 outbox 由本会话记录，App 在 step 前桥回 Social。
    // ★ 这里不把 step 的当天 ledger 传进 RC3：合户发生在 step 之前，用的是另一个瞬态累加器；若复用 mint 会从
    //   tr-<day>-1 重新起号，与随后 step 的转移 id 撞车。RC3 的 ledger=null 路径使用稳定的
    //   tr-<day>-migration-<ordinal> 审计 id，保持合户腿可追溯且不与 step 的 tr-<day>-<seq> 冲突。
    HouseholdTransferSettlement.apply(
        session,
        accounts,
        null,
        day,
        ordinal,
        new HouseholdTransferSettlement.HouseholdTransferRequest(
            source,
            survivor,
            sourcePopulation,
            targetHex,
            Optional.empty(),
            HOUSEHOLD_MERGE_REASON,
            false),
        sourcePopulation,
        targetPopulationBefore);

    // 7) 家户级 post-state rebind 的唯一扩展点。C2 没有 transport capacity batch；C6 必须在本方法内
    //    把当前 marketRoundId 的 source batches 重键到 survivor，并按 (marketRoundId, homeHex) 相加容量。
    int postStateRowsRebound =
        HouseholdMergeRebindBook.rebindHouseholdOwnedPostState(
            session.sheet(), source, survivor, targetHex);

    // 8) source 行不删、population/labor 不在此写；FlowRow/ClassMembership/ClassShare 一律保留。
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_MERGE_APPLIED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "ordinal",
                  ordinal,
                  "source",
                  source.value(),
                  "survivor",
                  survivor.value(),
                  "population",
                  sourcePopulation,
                  "targetPopulationBefore",
                  targetPopulationBefore,
                  "landTransferredMilliMu",
                  landTransferred,
                  "assetRowsRebound",
                  structural.assetRowsRebound(),
                  "debtRekeyed",
                  debt.rekeyedContracts(),
                  "debtSelfNetted",
                  debt.selfNettedContracts(),
                  "occupanciesRebound",
                  occupancies,
                  "reservationsRebound",
                  reservations,
                  "demandsRekeyed",
                  demands,
                  "wageCarriesRebound",
                  wageCarries,
                  "shipmentAllocationsRebound",
                  shipmentAllocations,
                  "householdOwnedPostStateRowsRebound",
                  postStateRowsRebound));
    }
    return new SourceOutcome(debt.selfNettedContracts(), debt.selfNettedPrincipal());
  }

  private static void rebindProduction(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    ProductionRuntime runtime = session.productionRuntime();
    ActorRef sourceActor = HouseholdActors.of(source);
    ActorRef survivorActor = HouseholdActors.of(survivor);

    List<ProductionProcess> before = sortedUnits(runtime.units());
    for (ProductionProcess unit : before) {
      if (!unit.operator().equals(sourceActor)) {
        continue;
      }
      ProductionInstanceOutcome outcome =
          runtime.reoperateInstance(unit.id(), survivorActor, unit.modeKey());
      if (!outcome.written()) {
        throw new IllegalStateException(
            "household-merge-production-reoperate-rejected: unit="
                + unit.id().value()
                + " reason="
                + outcome.rejectionReason().orElse("unknown"));
      }
    }
    // ★ C10 blocker C：operator 已换 survivor，但 economy 的 PRODUCTION 承诺仍指 source。
    //   必须在 RC3 shrink 前把 source 户自己的承诺改挂 survivor；同时把指向 source 旧 actor 的
    //   其他参与户承诺 actor 对齐到同一实例子（household/id 保持不变）。
    rebindProductionCommitments(session.sheet(), runtime.units(), source, survivor);
    for (ProductionProcess unit : sortedUnits(runtime.units())) {
      ProductionInstanceOutcome outcome =
          runtime.rekeyCycleContributions(unit.id(), source, survivor);
      if (!outcome.written()) {
        throw new IllegalStateException(
            "household-merge-production-contribution-rekey-rejected: unit="
                + unit.id().value()
                + " reason="
                + outcome.rejectionReason().orElse("unknown"));
      }
    }
  }

  /**
   * ★★★ <b>C10 blocker C：合户后 PRODUCTION 劳动承诺的 household/id/actor 重绑</b>。
   *
   * <p>合户前预检已过滤含非 PRODUCTION 承诺的 source，故本路径只处理 {@link LaborCommitmentKind#PRODUCTION}。 两类行都必须处理：
   *
   * <ul>
   *   <li>{@code household == source}：这是 source 自己的劳动承诺；按 {@code idOfProduction(unit, role, group,
   *       survivor)} 重派生 id，并改挂 survivor 户；
   *   <li>{@code actor == HouseholdActors.of(source)}：这是同一实例上其他参与户的承诺（actor 是旧经营者， household 不一定是
   *       source）；id/household 保持原样，只把 actor 对齐到 reoperate 后的实例经营者。
   * </ul>
   *
   * <p>若 source 户的新 id 已存在，只有角色、group、activity、period、kind 与目标家户全部逐值一致时才用 {@link
   * Math#addExact(long, long)} 合并 laborMilli；否则具名 fail-closed。稳定序为 commitment id 升序。
   */
  private static void rebindProductionCommitments(
      EconomyStateBuilder sheet,
      Map<ProductionUnitId, ProductionProcess> units,
      HouseholdId source,
      HouseholdId survivor) {
    ActorRef sourceActor = HouseholdActors.of(source);
    ActorRef survivorActor = HouseholdActors.of(survivor);
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> commitments =
        sheet.laborCommitments();

    List<HouseholdLaborCommitment> toRebind = new ArrayList<>();
    for (HouseholdLaborCommitment commitment : commitments.values()) {
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue;
      }
      if (commitment.household().equals(source) || commitment.actor().equals(sourceActor)) {
        toRebind.add(commitment);
      }
    }
    if (toRebind.isEmpty()) {
      return;
    }
    toRebind.sort(Comparator.comparing(commitment -> commitment.id().value()));

    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> staged =
        new LinkedHashMap<>(commitments);
    int householdRebinds = 0;
    int actorRebinds = 0;
    int merged = 0;
    for (HouseholdLaborCommitment original : toRebind) {
      HouseholdLaborCommitment commitment = staged.get(original.id());
      if (commitment == null) {
        throw reject(
            "household-merge-production-commitment-disappeared",
            "source=" + source.value() + " commitment=" + original.id().value());
      }
      boolean moveHousehold = commitment.household().equals(source);
      boolean moveActor = commitment.actor().equals(sourceActor);
      if (!moveHousehold && !moveActor) {
        continue;
      }

      ProductionRoleId role =
          commitment
              .productionRole()
              .orElseThrow(
                  () ->
                      reject(
                          "household-merge-production-commitment-role-missing",
                          "source=" + source.value() + " commitment=" + commitment.id().value()));
      ProductionUnitId unitId = ProductionUnitId.parse(commitment.activity());
      ProductionProcess unit = units.get(unitId);
      if (unit == null) {
        if (moveHousehold) {
          throw reject(
              "household-merge-production-commitment-unit-unresolved",
              "source="
                  + source.value()
                  + " commitment="
                  + commitment.id().value()
                  + " activity="
                  + commitment.activity());
        }
        // 自由家户劳动没有 unit 可查；它不影响实例 actor 一致性，这里只把旧 actor 对齐 survivor。
        HouseholdLaborCommitment actorOnly =
            new HouseholdLaborCommitment(
                commitment.id(),
                commitment.group(),
                commitment.household(),
                survivorActor,
                commitment.activity(),
                commitment.laborMilli(),
                commitment.period(),
                commitment.kind(),
                commitment.productionRole());
        staged.put(actorOnly.id(), actorOnly);
        actorRebinds++;
        continue;
      }

      ActorRef nextActor = moveActor ? unit.operator() : commitment.actor();
      HouseholdId nextHousehold = moveHousehold ? survivor : commitment.household();
      LaborAllocationId nextId =
          moveHousehold
              ? HouseholdLaborCommitment.idOfProduction(
                  unit.id(), role, commitment.group(), survivor)
              : commitment.id();
      HouseholdLaborCommitment next =
          new HouseholdLaborCommitment(
              nextId,
              commitment.group(),
              nextHousehold,
              nextActor,
              commitment.activity(),
              commitment.laborMilli(),
              commitment.period(),
              commitment.kind(),
              commitment.productionRole());

      if (!nextId.equals(commitment.id())) {
        HouseholdLaborCommitment existing = staged.get(nextId);
        if (existing != null) {
          if (existing.kind() != LaborCommitmentKind.PRODUCTION
              || existing.productionRole().isEmpty()
              || !existing.productionRole().get().equals(role)
              || !existing.group().equals(commitment.group())
              || !existing.household().equals(nextHousehold)
              || !existing.activity().equals(commitment.activity())
              || existing.period() != commitment.period()
              || !existing.actor().equals(nextActor)) {
            throw reject(
                "household-merge-production-commitment-id-collision",
                "source="
                    + source.value()
                    + " survivor="
                    + survivor.value()
                    + " oldId="
                    + commitment.id().value()
                    + " newId="
                    + nextId.value());
          }
          long mergedLabor = Math.addExact(existing.laborMilli(), commitment.laborMilli());
          next =
              new HouseholdLaborCommitment(
                  nextId,
                  existing.group(),
                  existing.household(),
                  existing.actor(),
                  existing.activity(),
                  mergedLabor,
                  existing.period(),
                  existing.kind(),
                  existing.productionRole());
          merged++;
        }
        staged.remove(commitment.id());
      }
      staged.put(nextId, next);
      if (moveHousehold) {
        householdRebinds++;
      }
      if (moveActor) {
        actorRebinds++;
      }
    }

    commitments.clear();
    commitments.putAll(staged);
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_MERGE_PRODUCTION_COMMITMENTS_REBOUND",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "source",
                  source.value(),
                  "survivor",
                  survivor.value(),
                  "householdRebinds",
                  householdRebinds,
                  "actorRebinds",
                  actorRebinds,
                  "merged",
                  merged));
    }
  }

  private static List<ProductionProcess> sortedUnits(
      Map<ProductionUnitId, ProductionProcess> units) {
    List<ProductionProcess> ordered = new ArrayList<>(units.values());
    ordered.sort(Comparator.comparing(unit -> unit.id().value()));
    return ordered;
  }

  private static long transferLand(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<LandHoldingKey, LandHolding> holdings = sheet.landHoldings();
    LandLedgerState state = LandLedgerState.from(sheet.landArableSupply(), holdings);
    List<LandHolding> sourceHoldings = new ArrayList<>();
    for (LandHolding holding : state.holdingsByKey().values()) {
      if (holding.owner().equals(source)) {
        sourceHoldings.add(holding);
      }
    }
    if (sourceHoldings.isEmpty()) {
      return 0L;
    }
    long total = 0L;
    for (LandHolding holding : sourceHoldings) {
      state =
          state.transfer(
              new LandTransfer(holding.hex(), source, survivor, holding.quantityMilliMu()));
      total = Math.addExact(total, holding.quantityMilliMu());
    }
    holdings.clear();
    holdings.putAll(state.holdingsByKey());
    return total;
  }

  private static int rebindOccupancyGrants(
      EconomySession session, Map<AssetShareId, AssetShareId> assetIdMapping) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<AssetOccupancyGrantId, AssetOccupancyGrant> grants = sheet.assetOccupancies();
    if (grants.isEmpty()) {
      return 0;
    }
    List<Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant>> ordered =
        new ArrayList<>(grants.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    LinkedHashMap<AssetOccupancyGrantId, AssetOccupancyGrant> rebound = new LinkedHashMap<>();
    int changed = 0;
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry : ordered) {
      AssetOccupancyGrant grant = entry.getValue();
      AssetShareId mappedShare = assetIdMapping.getOrDefault(grant.shareId(), grant.shareId());
      AssetOccupancyGrantId id = entry.getKey();
      if (!mappedShare.equals(grant.shareId())) {
        id = AssetOccupancyGrantId.idOf(grant.unitId(), mappedShare, grant.fromTick());
        changed++;
      }
      AssetOccupancyGrant next;
      if (id.equals(entry.getKey()) && mappedShare.equals(grant.shareId())) {
        next = grant;
      } else {
        next =
            new AssetOccupancyGrant(
                id,
                mappedShare,
                grant.unitId(),
                grant.quantity(),
                grant.fromTick(),
                grant.toTickExclusive(),
                grant.status(),
                grant.releasedAtTick());
      }
      if (rebound.putIfAbsent(id, next) != null) {
        throw new IllegalStateException("household-merge-occupancy-id-collision: id=" + id.value());
      }
    }
    if (changed > 0) {
      grants.clear();
      grants.putAll(rebound);
    }
    return changed;
  }

  private static int rebindInventoryReservations(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<InventoryReservationId, InventoryReservation> reservations =
        sheet.inventoryReservations();
    if (reservations.isEmpty()) {
      return 0;
    }
    ActorRef sourceActor = HouseholdActors.of(source);
    ActorRef survivorActor = HouseholdActors.of(survivor);
    List<Map.Entry<InventoryReservationId, InventoryReservation>> ordered =
        new ArrayList<>(reservations.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    LinkedHashMap<InventoryReservationId, InventoryReservation> rebound = new LinkedHashMap<>();
    int changed = 0;
    for (Map.Entry<InventoryReservationId, InventoryReservation> entry : ordered) {
      InventoryReservation reservation = entry.getValue();
      if (!reservation.payer().equals(sourceActor)) {
        if (rebound.putIfAbsent(entry.getKey(), reservation) != null) {
          throw new IllegalStateException(
              "household-merge-reservation-id-collision: id=" + entry.getKey().value());
        }
        continue;
      }
      InventoryReservationId id =
          InventoryReservationId.idOf(
              reservation.unitId(),
              survivorActor,
              reservation.location(),
              reservation.commodity(),
              reservation.fromTick());
      InventoryReservation next =
          new InventoryReservation(
              id,
              reservation.unitId(),
              survivorActor,
              reservation.location(),
              reservation.commodity(),
              reservation.quantity(),
              reservation.fromTick(),
              reservation.toTickExclusive(),
              reservation.status(),
              reservation.releasedAtTick());
      if (rebound.putIfAbsent(id, next) != null) {
        throw new IllegalStateException(
            "household-merge-reservation-id-collision: id=" + id.value());
      }
      changed++;
    }
    if (changed > 0) {
      reservations.clear();
      reservations.putAll(rebound);
    }
    return changed;
  }

  private static int rekeyHouseholdDemands(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<DemandId, HouseholdDemand> demands = sheet.demands();
    if (demands.isEmpty()) {
      return 0;
    }
    List<Map.Entry<DemandId, HouseholdDemand>> ordered = new ArrayList<>(demands.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    LinkedHashMap<DemandId, HouseholdDemand> rebound = new LinkedHashMap<>();
    int changed = 0;
    for (Map.Entry<DemandId, HouseholdDemand> entry : ordered) {
      HouseholdDemand demand = entry.getValue();
      if (demand.scope() != HouseholdDemand.DemandScope.HOUSEHOLD
          || demand.household().isEmpty()
          || !demand.household().get().equals(source)) {
        rebound.put(entry.getKey(), demand);
        continue;
      }
      rebound.put(
          entry.getKey(),
          new HouseholdDemand(
              demand.id(),
              demand.scope(),
              Optional.of(survivor),
              demand.hex(),
              demand.commodity(),
              demand.kind(),
              demand.unit(),
              demand.quantityPerCycle(),
              demand.createdDay(),
              demand.expiresDay(),
              demand.priority(),
              demand.source()));
      changed++;
    }
    if (changed > 0) {
      demands.clear();
      demands.putAll(rebound);
    }
    return changed;
  }

  private static int rebindWageCarry(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<WageCarryKey, Long> carries = sheet.wageCarryRemainders();
    if (carries.isEmpty()) {
      return 0;
    }
    List<Map.Entry<WageCarryKey, Long>> ordered = new ArrayList<>(carries.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().toString()));
    LinkedHashMap<WageCarryKey, Long> rebound = new LinkedHashMap<>();
    int changed = 0;
    for (Map.Entry<WageCarryKey, Long> entry : ordered) {
      WageCarryKey key = entry.getKey();
      WageCarryKey nextKey =
          key.worker().equals(source) ? new WageCarryKey(key.unit(), key.role(), survivor) : key;
      if (rebound.putIfAbsent(nextKey, entry.getValue()) != null) {
        throw new IllegalStateException(
            "household-merge-wage-carry-collision: key="
                + nextKey
                + " reason=source-and-survivor-carry-would-merge");
      }
      if (!nextKey.equals(key)) {
        changed++;
      }
    }
    if (changed > 0) {
      carries.clear();
      carries.putAll(rebound);
    }
    return changed;
  }

  private static int rebindShipmentBuyers(
      EconomySession session, HouseholdId source, HouseholdId survivor) {
    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<ShipmentId, ShipmentBatch> shipments = sheet.shipments();
    if (shipments.isEmpty()) {
      return 0;
    }
    ActorRef sourceActor = HouseholdActors.of(source);
    ActorRef survivorActor = HouseholdActors.of(survivor);
    List<Map.Entry<ShipmentId, ShipmentBatch>> ordered = new ArrayList<>(shipments.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    LinkedHashMap<ShipmentId, ShipmentBatch> rebound = new LinkedHashMap<>();
    int changedAllocations = 0;
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : ordered) {
      ShipmentBatch batch = entry.getValue();
      List<ShipmentAllocation> allocations = new ArrayList<>(batch.allocations().size());
      boolean batchChanged = false;
      for (ShipmentAllocation allocation : batch.allocations()) {
        if (!allocation.buyer().equals(sourceActor)) {
          allocations.add(allocation);
          continue;
        }
        allocations.add(
            new ShipmentAllocation(
                allocation.seller(),
                survivorActor,
                allocation.deliverTo(),
                allocation.quantity(),
                allocation.lossBearer()));
        batchChanged = true;
        changedAllocations++;
      }
      ShipmentBatch next =
          batchChanged
              ? new ShipmentBatch(
                  batch.route(),
                  batch.commodity(),
                  batch.dispatchTick(),
                  batch.arrivalTick(),
                  batch.quantity(),
                  allocations)
              : batch;
      rebound.put(entry.getKey(), next);
    }
    if (changedAllocations > 0) {
      shipments.clear();
      shipments.putAll(rebound);
    }
    return changedAllocations;
  }

  private static void requireTransferPopulation(
      List<EconomyPopulationTransfer> transfers, long expectedPopulation) {
    long actual = 0L;
    for (EconomyPopulationTransfer transfer : transfers) {
      actual = Math.addExact(actual, transfer.population());
    }
    if (actual != expectedPopulation) {
      throw new IllegalStateException(
          "household-merge-population-outbox-mismatch: expected="
              + expectedPopulation
              + " actual="
              + actual);
    }
  }

  private static List<EconomyPopulationTransfer> drainNewTransfers(
      EconomySession session, int pendingBefore) {
    List<EconomyPopulationTransfer> all = session.drainPendingPopulationTransfers();
    if (pendingBefore < 0 || pendingBefore > all.size()) {
      throw new IllegalStateException(
          "household-merge-population-outbox-shrank: before="
              + pendingBefore
              + " drained="
              + all.size());
    }
    List<EconomyPopulationTransfer> fresh = List.copyOf(all.subList(pendingBefore, all.size()));
    for (int i = 0; i < pendingBefore; i++) {
      session.recordPopulationTransfer(all.get(i));
    }
    return fresh;
  }
}
