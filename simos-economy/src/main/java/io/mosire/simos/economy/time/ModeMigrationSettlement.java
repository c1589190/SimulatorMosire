package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
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
 *   ① 源/目标行解析；目标已有 ⇒ 合并；目标缺失 ⇒ 新建（ClassRow + ClassStanding + 账户 + 组织/unit/关系 + 租赁资产）
 *   ② 人口/劳动/成员份额/劳动配额按人口比例移动（逐笔 floor；源户清空时余数随最后一笔走）
 *   ③ 货币按**全部币种**逐项移动（D-023：逐币种按人口比例 floor，余数留源/迁空随最后一笔；不做 FX；只搬余额，不新造）
 *   ④ 债务逐合同走 DebtContractBook.reduce/upsert（唯一写口）；计划金额全部分摊，绝不静默丢债
 *   ⑤ 资产随迁（D-023）：源户自有资产按**逐笔迁移人口比例**随迁 —— 可移动资产（TOOL/SHIP/CATTLE/MACHINE）
 *      同 hex 同产业直接拆份额、跨 hex 在目标产业的同 AssetKind 下重建；不可移动资产（LAND/WORKSHOP）同 hex 可换主人，
 *      跨 hex 留原户并记具名读数。绝不再走"整户消亡时把资产全给最后目标"的旧路
 *   ⑥ 源户人口归零：钱/债必须为 0；仍有资产（自有不可移动/不可用，或作为他人资产 operator）⇒ 保留 0 人口“资产持有壳户”，
 *      不从 classes/classStandings 删除；没有任何残留 ⇒ 整户移除并清掉它的组织/unit/关系
 * </pre>
 *
 * <p>★★ <b>D-022 硬不变量</b>：源户的 {@code ClassStanding} / {@code ProductionOrganization.modeId} /
 * {@code ProductionUnit.modeKey} 在本类里<b>一字不改</b>；目标 mode 只出现在目标家户（已有或新建）上。
 *
 * <p>★★ <b>失败具名抛</b>：不静默丢人/丢债/丢钱；整段写入发生在同一个 {@link EconomySession}/revision 内。
 */
public final class ModeMigrationSettlement {

  private ModeMigrationSettlement() {}

  /**
   * 执行一份计划；空计划 ⇒ 一字不改。★ 无 {@link ProductionLedger} 的旧调用点（单模块用例）走本重载：资产随迁照做，
   * 只少“留原户资产”的当天具名审计（资产本身仍逐行留在状态里）。
   */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ModeMigrationPolicy.MigrationPlan plan,
      EconomyData base,
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
      EconomyData base,
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
    Map<HouseholdId, ClassRow> rows = session.sheet().rows();
    LinkedHashMap<HouseholdId, ClassStanding> standings = session.sheet().classStandings();
    LinkedHashMap<MembershipId, Membership> memberships = session.sheet().memberships();
    LinkedHashMap<LaborAllocationId, LaborAllocation> allocations = session.sheet().allocations();
    LinkedHashMap<AssetShareId, AssetShare> assetShares = session.sheet().assetShares();
    LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations =
        session.sheet().productionOrganizations();
    LinkedHashMap<ProductionUnitId, ProductionUnit> units = session.sheet().units();
    LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
        operatorConditions = session.sheet().operatorConditions();
    LinkedHashMap<ProductionUnitId, ProductionRelation> relations = session.sheet().relations();
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<PledgeId, Pledge> pledges = session.sheet().pledges();
    LinkedHashMap<ClassShareId, ClassShare> classShares = session.sheet().classShares();
    Map<DemandId, DemandEntry> demands = base.demands();

    Map<HouseholdId, List<ModeMigrationPolicy.MigrationMove>> bySource = new LinkedHashMap<>();
    for (ModeMigrationPolicy.MigrationMove move : plan.moves()) {
      bySource.computeIfAbsent(move.source(), ignored -> new ArrayList<>()).add(move);
    }
    List<HouseholdId> sources = new ArrayList<>(bySource.keySet());
    sources.sort(Comparator.comparing(HouseholdId::value));
    // ★★ D-023：本一次 apply 内被“新建目标户”创建的家户集合（跨源共享）——后续源并入同一新户时，
    //    也要把随迁资产份额补进它的组织 assetSources（见 attachMigratedSharesToNewTargetOrganizations）。
    Set<HouseholdId> createdTargets = new LinkedHashSet<>();
    for (HouseholdId source : sources) {
      applySource(
          source,
          session,
          bySource.get(source),
          rows,
          standings,
          memberships,
          allocations,
          assetShares,
          organizations,
          units,
          operatorConditions,
          relations,
          debts,
          accounts,
          pledges,
          classShares,
          demands,
          base,
          day,
          createdTargets,
          auditLedger);
    }
  }

  // ── 一个源户的全部 move ────────────────────────────────────────────────────────────────────

  private static void applySource(
      HouseholdId source,
      EconomySession session,
      List<ModeMigrationPolicy.MigrationMove> moves,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassStanding> standings,
      LinkedHashMap<MembershipId, Membership> memberships,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
          operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      LinkedHashMap<PledgeId, Pledge> pledges,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, DemandEntry> demands,
      EconomyData base,
      long day,
      Set<HouseholdId> createdTargets,
      ProductionLedger.Accumulator auditLedger) {
    ClassRow sourceRow = rows.get(source);
    if (sourceRow == null) {
      throw new IllegalStateException("迁移源家户不存在（拒绝静默丢人）: " + source);
    }
    ActorRef sourceActor = HouseholdActors.of(source);
    HexCoord sourceHex = sourceRow.view().hex();
    long sourcePopulation = sourceRow.population();
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

    LinkedHashMap<MembershipId, Membership> sourceMemberships = new LinkedHashMap<>();
    for (Membership membership : memberships.values()) {
      if (membership.household().equals(source) && membership.count() > 0L) {
        sourceMemberships.put(membership.id(), membership);
      }
    }
    List<MembershipId> sourceMembershipIds = new ArrayList<>(sourceMemberships.keySet());
    sourceMembershipIds.sort(Comparator.comparing(MembershipId::value));
    LinkedHashMap<LaborAllocationId, LaborAllocation> sourceAllocations = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations.values()) {
      if (allocation.household().equals(source) && allocation.laborMilli() > 0L) {
        sourceAllocations.put(allocation.id(), allocation);
      }
    }
    List<LaborAllocationId> sourceAllocationIds = new ArrayList<>(sourceAllocations.keySet());
    sourceAllocationIds.sort(Comparator.comparing(LaborAllocationId::value));

    // ★★ D-023：源户自有资产快照（owner == 源户 actor；operator 可能是别人 —— 那是出租，不搬别人的所有者份额）。
    //    逐笔迁移只在这份快照上按当前剩余量切，绝不递归处理随迁后新生成的目标份额。
    List<AssetShareId> sourceOwnedAssetIds = new ArrayList<>();
    for (AssetShare share : assetShares.values()) {
      if (share.quantity() > 0L && share.owner().equals(sourceActor)) {
        sourceOwnedAssetIds.add(share.id());
      }
    }
    sourceOwnedAssetIds.sort(Comparator.comparing(AssetShareId::value));
    // 已被别的组织（organizer != 源户）实际使用的份额：移动会改 operator、拆掉那个组织的引用 ⇒ 本批留原户并具名。
    Set<AssetShareId> foreignUsedShareIds = foreignUsedAssetShareIds(organizations, sourceActor);
    // ACTIVE 质押的份额：随迁会让质押上界 fail-closed；本批留原户并具名，不越权解押。
    Set<AssetShareId> activePledgedShareIds = activePledgedAssetShareIds(pledges);
    Map<AssetShareId, String> residualReasons = new LinkedHashMap<>();
    Set<AssetShareId> movedWholeShareIds = new LinkedHashSet<>();
    Map<HouseholdId, List<AssetShareId>> migratedShareIdsByTarget = new LinkedHashMap<>();

    long populationLeft = sourcePopulation;
    long laborLeft = sourceRow.laborMilli();
    long movedLaborTotal = 0L;
    for (int index = 0; index < moves.size(); index++) {
      ModeMigrationPolicy.MigrationMove move = moves.get(index);
      long populationBefore = populationLeft;
      boolean empties = move.population() == populationBefore;
      ClassRow targetRow = rows.get(move.target());
      boolean newTarget = targetRow == null;
      ProductionUnitId targetUnit = null;
      ClassPositionId newTargetPosition = null;
      if (!newTarget) {
        ClassStanding targetStanding = standings.get(move.target());
        if (targetStanding == null) {
          throw new IllegalStateException(
              "合并目标家户没有 ClassStanding（说不出它的 mode，拒绝静默改人）: " + move.target());
        }
        ClassPosition targetPosition =
            base.classPositions().get(targetStanding.currentPositionId());
        if (targetPosition == null || !targetPosition.modeId().equals(move.targetMode())) {
          throw new IllegalStateException(
              "合并目标家户的当前 mode 与计划 targetMode 不一致（拒绝把源户并错 mode）: target="
                  + move.target()
                  + " plan="
                  + move.targetMode()
                  + " actual="
                  + (targetPosition == null ? "<无此位置>" : targetPosition.modeId()));
        }
        ProductionOrganization targetOrg = organizationOf(move.target(), rows, organizations);
        targetUnit =
            targetOrg != null && targetOrg.unitId().isPresent() ? targetOrg.unitId().get() : null;
      } else {
        newTargetPosition =
            createNewHousehold(move, sourceRow, rows, standings, accounts, base, day);
        // ★★ 新建目标户后必须刷新行引用：createNewHousehold 只把新行写进 rows，
        //    不改变本方法早先捕获的 targetRow（原为 null）。不刷新 ⇒ 下一段 NPE。
        targetRow = rows.get(move.target());
        if (targetRow == null) {
          throw new IllegalStateException("新建目标家户后行表里仍无该行（拒绝静默丢人）: " + move.target());
        }
      }

      // ② 人口/劳动
      long popTake = move.population();
      long laborTake =
          empties
              ? laborLeft
              : Math.multiplyExact(sourceRow.laborMilli(), popTake)
                  / Math.max(1L, populationBefore);
      if (laborTake > laborLeft) {
        throw new IllegalStateException(
            "迁移劳动超过源户剩余劳动（拒绝抽成负劳动）: source=" + source + " take=" + laborTake);
      }
      rows.put(
          move.target(),
          targetRow.withPopulationAndLabor(
              targetRow.population() + popTake, targetRow.laborMilli() + laborTake));
      sourceRow =
          sourceRow.withPopulationAndLabor(
              sourceRow.population() - popTake, sourceRow.laborMilli() - laborTake);
      rows.put(source, sourceRow);
      populationLeft = sourceRow.population();
      laborLeft = sourceRow.laborMilli();
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
              activePledgedShareIds,
              foreignUsedShareIds,
              movedWholeShareIds,
              residualReasons);
      if (!assetOutcome.createdIds().isEmpty()) {
        migratedShareIdsByTarget
            .computeIfAbsent(move.target(), ignored -> new ArrayList<>())
            .addAll(assetOutcome.createdIds());
      }
      if (newTarget && !DefaultProductionModes.DISPLACED.equals(move.targetMode())) {
        // ★ 组织/unit/资产必须在目标行带上迁移人口/劳动之后建（规模 = 劳动/工艺需求，不能拿 0 劳动建）；
        //   随迁份额（assetOutcome）进 assetSources，并在 planAssetMoves 里抵减目标产业容量需求。
        targetUnit =
            createOrganizationAndUnit(
                move,
                rows,
                rows.get(move.target()),
                newTargetPosition,
                assetShares,
                units,
                relations,
                organizations,
                base,
                assetOutcome.createdIds(),
                assetOutcome.coverage(),
                day,
                auditLedger);
      }

      // 成员份额：必须与本笔迁出人口逐值相等（EconomyData 的 Σcount == Σpopulation 守卫），
      // 故按当前份额计数用最大余数法切出恰好 popTake；清空时余数随最后一笔全部带走。
      List<MembershipId> currentMembershipIds = new ArrayList<>();
      List<Long> currentMembershipCounts = new ArrayList<>();
      long membershipTotal = 0L;
      for (MembershipId membershipId : sourceMembershipIds) {
        Membership membership = memberships.get(membershipId);
        if (membership != null && membership.count() > 0L) {
          currentMembershipIds.add(membershipId);
          currentMembershipCounts.add(membership.count());
          membershipTotal = Math.addExact(membershipTotal, membership.count());
        }
      }
      if (!currentMembershipIds.isEmpty() && membershipTotal < popTake) {
        throw new IllegalStateException(
            "源户成员份额不足以承载迁出人口（拒绝让 Σcount 与 Σpopulation 漂开）: source="
                + source
                + " take="
                + popTake
                + " membership="
                + membershipTotal);
      }
      long[] membershipShares;
      if (empties || currentMembershipIds.isEmpty()) {
        membershipShares = new long[currentMembershipCounts.size()];
        for (int i = 0; i < membershipShares.length; i++) {
          membershipShares[i] = currentMembershipCounts.get(i);
        }
      } else {
        membershipShares =
            io.mosire.simos.util.economy.ProportionalSplit.byDenominator(
                popTake, toLongArray(currentMembershipCounts), membershipTotal);
      }
      for (int i = 0; i < currentMembershipIds.size(); i++) {
        MembershipId membershipId = currentMembershipIds.get(i);
        Membership membership = memberships.get(membershipId);
        if (membership == null) {
          continue;
        }
        long take = Math.min(membershipShares[i], membership.count());
        if (take <= 0L) {
          continue;
        }
        if (take == membership.count()) {
          memberships.remove(membershipId);
        } else {
          memberships.put(
              membershipId,
              new Membership(
                  membershipId,
                  membership.lot(),
                  membership.household(),
                  membership.count() - take));
        }
        MembershipId targetMembershipId = Membership.idOf(membership.lot(), move.target());
        Membership existingTarget = memberships.get(targetMembershipId);
        memberships.put(
            targetMembershipId,
            new Membership(
                targetMembershipId,
                membership.lot(),
                move.target(),
                (existingTarget == null ? 0L : existingTarget.count()) + take));
      }

      // 劳动配额：逐笔 floor，清空时余数随最后一笔
      for (LaborAllocationId allocationId : sourceAllocationIds) {
        LaborAllocation allocation = allocations.get(allocationId);
        if (allocation == null || allocation.laborMilli() <= 0L) {
          continue;
        }
        long take =
            empties
                ? allocation.laborMilli()
                : Math.multiplyExact(allocation.laborMilli(), popTake)
                    / Math.max(1L, populationBefore);
        take = Math.min(take, allocation.laborMilli());
        if (take <= 0L) {
          continue;
        }
        if (take == allocation.laborMilli()) {
          allocations.remove(allocationId);
        } else {
          allocations.put(
              allocationId,
              new LaborAllocation(
                  allocation.id(),
                  allocation.group(),
                  allocation.household(),
                  allocation.actor(),
                  allocation.activity(),
                  allocation.laborMilli() - take,
                  allocation.period()));
        }
        if (!DefaultProductionModes.DISPLACED.equals(move.targetMode()) && targetUnit != null) {
          moveAllocationToTarget(
              allocation, move.target(), targetUnit, take, allocations, rows.get(move.target()));
        }
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

      // ④ 债务（计划金额分摊到源户合同；目标不新建组织）
      if (move.debtMilli() > 0L) {
        moveDebt(move, debts, day);
      }

      if (empties) {
        break;
      }
    }

    // ★★ D-023：整条随迁的源份额旧 id 已在 AssetShareBook.apply/rebuild 里删除 ⇒ 源户组织 assetSources
    //    必须同步删掉这些 id（不悬空）；部分随迁的份额 id 仍在、数量已减，无需改引用。
    removeSourceOrganizationAssetSources(organizations, sourceActor, movedWholeShareIds);
    // ★★ D-023：目标户若是本一次 apply 新建的（含后续源并入），把随迁新份额 id 补进它的组织 assetSources。
    attachMigratedSharesToNewTargetOrganizations(
        createdTargets, migratedShareIdsByTarget, rows, assetShares, organizations);
    // ★★ D-023：跨 hex 不可移动 / 目标 hex 无法承载 / 已出租给他组织 / 已质押 ⇒ 留原户的具名读数
    //    （瞬态 ProductionLedger；不新增持久组件，不改数量）。
    recordResidualAssetAudits(auditLedger, day, source, assetShares, residualReasons);

    // ⑤ 源户消亡 / 缩编
    if (sourceRow.population() == 0L) {
      retireSource(
          source,
          session,
          rows,
          standings,
          memberships,
          allocations,
          assetShares,
          organizations,
          units,
          operatorConditions,
          relations,
          debts,
          classShares,
          demands,
          accounts,
          pledges,
          day);
    }
  }

  /** 源户人口清零后的清点与移除：钱/债必须为 0；有资产残留 ⇒ 留 0 人口壳户；组织/unit/关系/劳动配额一并退役。 */
  private static void retireSource(
      HouseholdId source,
      EconomySession session,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassStanding> standings,
      LinkedHashMap<MembershipId, Membership> memberships,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition>
          operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, DemandEntry> demands,
      AccountSession accounts,
      Map<PledgeId, Pledge> pledges,
      long day) {
    long residualMoney = 0L;
    for (long amount : accounts.householdMoney().getOrDefault(source, Map.of()).values()) {
      residualMoney = Math.addExact(residualMoney, amount);
    }
    long residualDebt = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        residualDebt = Math.addExact(residualDebt, contract.principal());
      }
    }
    if (residualMoney != 0L || residualDebt != 0L) {
      throw new IllegalStateException(
          "迁移后源户人口为 0 但仍有货币/债务残留（拒绝消亡丢账）: source="
              + source
              + " money="
              + residualMoney
              + " debt="
              + residualDebt);
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
    //    ⇒ 保留“资产持有壳户”（人口 0、无组织/unit/配额，但 ClassRow/ClassStanding/资产份额都在）。
    //    本批不写假价格、不做假变卖；具名读数已由 recordResidualAssetAudits 记进当天瞬态 ledger。
    boolean assetShell = hasAnySourceAsset(sourceActorForOrg, assetShares);
    boolean shell = contractShell || assetShell;
    // 需求/模式变迁份额指名源户时：若源户要消亡则本批无法表达"迁移它们" ⇒ fail-closed 具名抛；
    // 若已是壳户（合同端点/资产残留）则保留行本身，表仍可解析。
    if (!shell) {
      for (DemandEntry demand : demands.values()) {
        if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD
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
    for (ProductionOrganization organization : new ArrayList<>(organizations.values())) {
      if (organization.laborSources().contains(source)) {
        List<HouseholdId> sources = new ArrayList<>(organization.laborSources());
        sources.remove(source);
        organizations.put(
            organization.id(),
            new ProductionOrganization(
                organization.id(),
                organization.modeId(),
                organization.classPositionId(),
                organization.unitId(),
                organization.organizer(),
                sources,
                organization.assetSources(),
                organization.inputSources(),
                organization.outputOwnership(),
                organization.relationTemplateRef(),
                organization.status(),
                organization.statusReason()));
      } else if (organization.organizer().equals(sourceActorForOrg)) {
        // 源户自己的组织已在下面退役分支处理
        continue;
      }
    }
    // ★★ D-023：不再有“整户消亡时把全部资产转给最后目标”的旧路径。资产随迁已在逐笔 move 里按人口比例完成；
    //    这里只负责组织/unit/关系/配额/成员份额的退役，以及决定“整户移除”还是“留资产壳户”。
    List<ProductionUnitId> removedUnits = new ArrayList<>();
    List<ProductionOrganizationId> removedOrganizations = new ArrayList<>();
    for (ProductionOrganization organization : new ArrayList<>(organizations.values())) {
      if (organization.organizer().equals(sourceActorForOrg)) {
        organization.unitId().ifPresent(removedUnits::add);
        removedOrganizations.add(organization.id());
        organizations.remove(organization.id());
      }
    }
    // ★ 跨表键检查：merchantFirms 以 organizationId 为键；组织行已删，商号行不得残留
    //   （EconomyData 的「商号指名的生产组织不存在」守卫会在同一 revision 的 build 里 fail-closed）。
    if (!removedOrganizations.isEmpty()) {
      LinkedHashMap<ProductionOrganizationId, MerchantFirm> merchantFirms =
          session.sheet().merchantFirms();
      for (ProductionOrganizationId removed : removedOrganizations) {
        merchantFirms.remove(removed);
      }
    }
    for (ProductionUnitId unitId : removedUnits) {
      units.remove(unitId);
      relations.remove(unitId);
      operatorConditions.remove(unitId);
    }
    for (LaborAllocation allocation : new ArrayList<>(allocations.values())) {
      if (allocation.household().equals(source)
          || removedUnits.contains(unitOfActivity(allocation.activity()))) {
        allocations.remove(allocation.id());
      }
    }
    for (Membership membership : new ArrayList<>(memberships.values())) {
      if (membership.household().equals(source)) {
        memberships.remove(membership.id());
      }
    }
    if (!shell) {
      standings.remove(source);
      rows.remove(source);
      // ★★ 缺陷②：flows 与 classes 自 S1 起同键 —— 源户行删除时必须同步删它的 FlowRow，
      //    否则同一 revision 的 EconomyData 构造期守卫会以「flows 的键必须是已存在的家户」拒绝。
      session.flows().remove(source);
      // ★ 同类跨表键：危机信号的 households 点名源户时也得摘掉（否则 build 守卫「危机信号点名的家户不存在」）。
      removeCrisisSignalReferences(session.sheet().crisisSignals(), source);
    } else {
      // ★★ D-023 壳户：人口 0、无组织/unit/配额；保留 ClassRow + ClassStanding + FlowRow，
      //    让资产份额的 owner（或作为 operator 的既有引用）与债务合同端点仍可解析。不从 classes 删除。
      ClassRow shellRow = rows.get(source);
      if (shellRow != null && shellRow.population() != 0L) {
        throw new IllegalStateException(
            "壳户源户人口必须为 0: " + source + " population=" + shellRow.population());
      }
    }
  }

  /**
   * ★ 同类跨表键清理：危机信号以 {@code households} 列表指名家户；源户行已删时把它从每个信号的 {@code households} 里摘掉（信号本身按 {@code
   * (hex, kind)} 的最新警告保留，不删行、不改 severity/evidence）。
   */
  private static void removeCrisisSignalReferences(
      LinkedHashMap<CrisisSignalId, HexCrisisSignal> signals, HouseholdId source) {
    for (Map.Entry<CrisisSignalId, HexCrisisSignal> entry : new ArrayList<>(signals.entrySet())) {
      HexCrisisSignal signal = entry.getValue();
      if (signal == null || !signal.households().contains(source)) {
        continue;
      }
      List<HouseholdId> households = new ArrayList<>(signal.households());
      households.removeIf(source::equals);
      signals.put(
          entry.getKey(),
          new HexCrisisSignal(
              signal.id(),
              signal.hex(),
              signal.kind(),
              signal.severity(),
              signal.day(),
              signal.evidence(),
              households,
              signal.classes(),
              signal.reason()));
    }
  }

  // ── 新建目标家户 ──────────────────────────────────────────────────────────────────────────

  private static ClassPositionId createNewHousehold(
      ModeMigrationPolicy.MigrationMove move,
      ClassRow sourceRow,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassStanding> standings,
      AccountSession accounts,
      EconomyData base,
      long day) {
    HouseholdId target = move.target();
    if (rows.containsKey(target)) {
      throw new IllegalStateException("新建目标家户 id 已存在（拒绝覆盖）: " + target);
    }
    ClassPositionId positionId = pickTargetPosition(base, move.targetMode(), sourceRow);
    CohortKey view =
        new CohortKey(move.targetHex(), sourceRow.view().residence(), sourceRow.view().stratum());
    ClassRow created =
        new ClassRow(
            target,
            view,
            0L,
            0L,
            sourceRow.participationPerMille(),
            0L,
            List.of(),
            Map.of(),
            Map.of(),
            0L);
    rows.put(target, created);
    standings.put(
        target,
        new ClassStanding(
            target, positionId, positionId, Map.of(), 0L, day, "AUTO_MIGRATION:" + move.reason()));
    accounts.registerHousehold(
        target,
        HouseholdActors.of(target),
        move.targetHex(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
    return positionId;
  }

  /** 目标 mode 的位置选择：优先同 relationToMeans+surplusRole，其次第一个可生产位置（id 升序）。 */
  private static ClassPositionId pickTargetPosition(
      EconomyData base, ProductionModeId modeId, ClassRow sourceRow) {
    ProductionMode mode = base.modes().get(modeId);
    if (mode == null) {
      throw new IllegalStateException("计划的目标 mode 不在目录里（拒绝凭空造 mode）: " + modeId);
    }
    var structure = base.classStructures().get(mode.classStructureId());
    if (structure == null) {
      throw new IllegalStateException("目标 mode 没有阶层结构（拒绝凭空造位置）: " + modeId);
    }
    ClassPosition sourcePosition = null;
    ClassStanding sourceStanding = null;
    for (ClassStanding standing : base.classStandings().values()) {
      if (standing.householdId().equals(sourceRow.id())) {
        sourceStanding = standing;
        break;
      }
    }
    if (sourceStanding != null) {
      sourcePosition = base.classPositions().get(sourceStanding.currentPositionId());
    }
    List<ClassPosition> positions = new ArrayList<>(structure.positions().values());
    positions.sort(Comparator.comparing(position -> position.id().value()));
    if (sourcePosition != null) {
      for (ClassPosition position : positions) {
        if (producing(position)
            && position.relationToMeans() == sourcePosition.relationToMeans()
            && position.surplusRole() == sourcePosition.surplusRole()) {
          return position.id();
        }
      }
    }
    for (ClassPosition position : positions) {
      if (producing(position)) {
        return position.id();
      }
    }
    throw new IllegalStateException("目标 mode 没有可承载人口的生产位置: " + modeId);
  }

  private static boolean producing(ClassPosition position) {
    return position.laborRole() != ClassPosition.LaborRole.NONE
        && position.surplusRole() != ClassPosition.SurplusRole.DEPENDENT;
  }

  /**
   * 为一个产业模板规划"从该 hex 的闲置份额拆出 TENANCY"的资产移动（{@link AssetShareBook#apply} 唯一写口）； 任一 capacity 种类不足 ⇒
   * null（该模板不可行）。
   *
   * <p>★★ P10.7 / D-024 修复 1b：闲置判据的唯一拼写点是 {@link ModeMigrationPolicy#isIdleShare} —— {@code
   * quantity > 0 && operator == owner && id ∉ claimed}，claimed = 既有组织 assetSources ∪ 在产 unit 占用的份额（同
   * industry、同 operator、quantity>0）。既有商号/组织/在产 unit 在用的份额不是闲置，拆空会让组织的 assetSources
   * 指向已删除的份额 id，或在产 unit 的产能凭空消失。
   *
   * <p>★★ D-023：{@code availableByKind} = 本目标户随迁进来、已登记在该产业下的份额（{@code AssetKind → quantity}）。
   * 它们已经是目标户自有的产能 ⇒ 先从需求里抵减，只对缺口租闲置份额，避免同一份资产既随迁又租一遍。
   */
  private static List<AssetShareBook.Move> planAssetMoves(
      IndustryId industryId,
      Industry industry,
      ActorRef targetActor,
      long scale,
      Map<AssetShareId, AssetShare> assetShares,
      Set<AssetShareId> claimedByOrganizations,
      Map<AssetKind, Long> availableByKind) {
    Map<AssetKind, Long> coveredByKind = availableByKind == null ? Map.of() : availableByKind;
    List<AssetShare> idle = new ArrayList<>();
    for (AssetShare share : assetShares.values()) {
      if (share.industry().equals(industryId)
          && ModeMigrationPolicy.isIdleShare(share, claimedByOrganizations)) {
        idle.add(share);
      }
    }
    idle.sort(Comparator.comparing(share -> share.id().value()));
    List<AssetShareBook.Move> moves = new ArrayList<>();
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long requiredQuantity = Math.multiplyExact(scale, required.getValue());
      long need =
          Math.max(0L, requiredQuantity - coveredByKind.getOrDefault(required.getKey(), 0L));
      for (AssetShare share : idle) {
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
            new AssetShareBook.Move(
                share.id(), take, share.owner(), targetActor, AssetShare.RightKind.TENANCY));
        need -= take;
      }
      if (need > 0L) {
        return null;
      }
    }
    return moves;
  }

  private static ProductionUnitId createOrganizationAndUnit(
      ModeMigrationPolicy.MigrationMove move,
      Map<HouseholdId, ClassRow> rows,
      ClassRow targetRow,
      ClassPositionId positionId,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations,
      EconomyData base,
      List<AssetShareId> migratedShareIds,
      Map<IndustryId, Map<AssetKind, Long>> migratedCoverage,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    ActorRef targetActor = HouseholdActors.of(move.target());
    // ★★ D-024：按目标 mode 的默认 regime 选产业模板（与计划期 ModeMigrationPolicy.industriesForMode 同源）——
    //   merchant 目标只在 trade 模板里找；找不到 ⇒ 空 ⇒ 具名失败（绝不把农场模板当商号）。
    List<IndustryId> industries =
        ModeMigrationPolicy.industriesForMode(base, move.targetHex(), move.targetMode());
    if (industries.isEmpty()) {
      throw new IllegalStateException("新建目标 hex 没有产业模板（拒绝凭空造生产）: " + move.targetHex());
    }
    IndustryId industryId = null;
    Industry industry = null;
    List<AssetShareBook.Move> assetMoves = new ArrayList<>();
    // ★★ P10.7 / P10.8 / D-024 修复 1b：既有组织 assetSources 正在使用的份额不是闲置；seeder 直接以 ESTATE 等
    //    operator 建立的 farm/weave/craft/trade 主 unit 没有对应组织，故 claimed 还必须并入"在产 unit 占用的份额"
    //    （同 industry、同 operator、quantity>0）。这里与 ModeMigrationPolicy.plan() 顶层共用同一个三参拼装点，
    //    并读同一份当天工作副本 organizations/units/assetShares（含当天自动组织阶段新加、以及本一次 apply 前几笔
    //    新建的组织/unit），保证"计划可新建 ⇔ 执行可拆到"；随迁新份额也从闲置池里排除（它们已归目标户）。
    Set<AssetShareId> claimedByOrganizations =
        new LinkedHashSet<>(
            ModeMigrationPolicy.claimedAssetShares(organizations, units, assetShares));
    claimedByOrganizations.addAll(migratedShareIds);
    for (IndustryId candidate : industries) {
      Industry candidateIndustry = base.industries().get(candidate);
      if (candidateIndustry == null) {
        continue;
      }
      long scale =
          candidateIndustry.recipe().laborPerUnit() <= 0L
              ? 1L
              : Math.max(1L, targetRow.laborMilli() / candidateIndustry.recipe().laborPerUnit());
      List<AssetShareBook.Move> planned =
          planAssetMoves(
              candidate,
              candidateIndustry,
              targetActor,
              scale,
              assetShares,
              claimedByOrganizations,
              migratedCoverage.getOrDefault(candidate, Map.of()));
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
              + targetRow.laborMilli());
    }
    List<AssetShareId> createdShares = new ArrayList<>();
    if (!assetMoves.isEmpty()) {
      createdShares.addAll(
          AssetShareBook.apply(assetShares, base.industries(), base.pledges(), assetMoves));
    }
    // ★★ GAP-2：组织 assetSources 只收 operator == organizer（targetActor）的份额。随迁份额由
    //    migrateAssetsForMove 按 targetActor 重建（同/跨 hex 都一样）；租赁份额由上方 planAssetMoves
    //    按 targetActor 新建。这里再做最后一次逐项核对：新建租赁份额对不上是内部错误，当场抛；
    //    随迁份额对不上（理论上不会发生）不得进 assetSources，也不放进 unit 的可用资产依赖里。
    List<AssetShareId> assetSources =
        new ArrayList<>(migratedShareIds.size() + createdShares.size());
    for (AssetShareId migratedShareId : migratedShareIds) {
      AssetShare migratedShare = assetShares.get(migratedShareId);
      if (migratedShare != null && migratedShare.operator().equals(targetActor)) {
        assetSources.add(migratedShareId);
      }
    }
    for (AssetShareId createdShare : createdShares) {
      AssetShare share = assetShares.get(createdShare);
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
    ProductionUnit unit =
        new ProductionUnit(
            unitId, industryId, targetActor, "mode:" + move.targetMode().value(), 0L, 0L, Map.of());
    units.put(unitId, unit);
    // ★★ D-023 第 5 项：新家户必须用目标产业 regime + 目标 mode 的**完整关系模板**（不再空规则全归 operator）。
    MigrationRelationPlan relationPlan =
        buildMigrationRelation(
            move, unitId, industryId, targetActor, rows, targetRow, base, day, auditLedger);
    ProductionRelation relation = relationPlan.relation();
    relations.put(unitId, relation);
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            move.targetMode(),
            positionId,
            move.target(),
            IndustryHexKeys.hexKey(move.targetHex().q(), move.targetHex().r()));
    ProductionOrganization organization =
        new ProductionOrganization(
            organizationId,
            move.targetMode(),
            positionId,
            Optional.of(unitId),
            targetActor,
            List.of(move.target()),
            assetSources,
            List.of(relation.inputSupplier()),
            new Recipient.ToActor(relation.residualOwner()),
            Optional.of(relationPlan.templateRef()),
            ProductionOrganization.Status.ACTIVE,
            "");
    organizations.put(organizationId, organization);
    return unitId;
  }

  // ── 迁移原子 —— 劳动配额/货币/债务 ───────────────────────────────────────────────────────

  private static void moveAllocationToTarget(
      LaborAllocation sourceAllocation,
      HouseholdId target,
      ProductionUnitId targetUnit,
      long laborTake,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      ClassRow targetRow) {
    String activity = targetUnit == null ? sourceAllocation.activity() : targetUnit.value();
    ProductionUnitId targetUnitKey = targetUnit == null ? unitOfActivity(activity) : targetUnit;
    LaborAllocationId targetId =
        targetUnitKey == null
            ? new LaborAllocationId(
                "alloc-mig-" + sourceAllocation.group().value() + "-" + target.value())
            : LaborAllocation.idOf(targetUnitKey, sourceAllocation.group(), target);
    LaborAllocation existing = allocations.get(targetId);
    LaborAllocation created =
        new LaborAllocation(
            targetId,
            sourceAllocation.group(),
            target,
            HouseholdActors.of(target),
            activity,
            (existing == null ? 0L : existing.laborMilli()) + laborTake,
            sourceAllocation.period());
    allocations.put(targetId, created);
    if (targetRow == null) {
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

  private static void moveDebt(
      ModeMigrationPolicy.MigrationMove move,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      long day) {
    List<DebtContract> contracts = new ArrayList<>();
    long totalPrincipal = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(move.source()) && contract.principal() > 0L) {
        contracts.add(contract);
        totalPrincipal = Math.addExact(totalPrincipal, contract.principal());
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long remaining = move.debtMilli();
    for (int i = 0; i < contracts.size(); i++) {
      DebtContract contract = contracts.get(i);
      long take =
          i == contracts.size() - 1
              ? Math.min(remaining, contract.principal())
              : Math.min(
                  contract.principal(),
                  Math.multiplyExact(move.debtMilli(), contract.principal()) / totalPrincipal);
      if (take <= 0L) {
        continue;
      }
      remaining -= take;
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
        // 目标恰是债权人：这部分留在源户（人口清零时会因残留债具名抛，绝不静默消灭债权）。
        DebtContractBook.upsert(
            debts,
            move.source(),
            contract.creditor(),
            contract.unit(),
            contract.terms(),
            take,
            day,
            contract.dueCycle());
      }
    }
    if (remaining != 0L) {
      throw new IllegalStateException(
          "债务迁移金额没有全部分摊（拒绝静默丢债）: move=" + move + " remaining=" + remaining);
    }
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
  private record MigrationRelationPlan(ProductionRelation relation, String templateRef) {}

  /**
   * ★★ <b>D-023 一条 move 的资产随迁</b>：只动 {@code owner == 源户 actor} 的份额；逐笔按迁移人口比例 {@code ⌊当前剩余量 ×
   * popTake ÷ 迁移前剩余人口⌋} 切（源户本笔迁空 ⇒ 余数随本笔全部带走）。
   *
   * <pre>
   * 同 hex（同产业）：AssetShareBook.apply 拆出目标户份额（owner/operator 见 transferOperator/transferRightKind）
   * 跨 hex + 可移动（TOOL/SHIP/CATTLE/MACHINE）：目标 hex 里第一个 capacityPerUnit 含该 AssetKind 的产业下
   *                                        AssetShareBook.rebuild 新建同量份额；无承载产业 ⇒ 留原户并具名
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
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      EconomyData base,
      Set<AssetShareId> activePledgedShareIds,
      Set<AssetShareId> foreignUsedShareIds,
      Set<AssetShareId> movedWholeShareIds,
      Map<AssetShareId, String> residualReasons) {
    boolean sameHex = move.targetHex().equals(sourceHex);
    ActorRef targetActor = HouseholdActors.of(move.target());
    List<AssetShareBook.Move> sameHexMoves = new ArrayList<>();
    List<AssetShareBook.RebuildMove> rebuildMoves = new ArrayList<>();
    for (AssetShareId shareId : sourceOwnedAssetIds) {
      AssetShare share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L || !share.owner().equals(sourceActor)) {
        continue; // 已在前面 move 整条随迁 / 已非源户所有
      }
      // 已出租给他组织实际使用的份额：改 operator 会拆掉那个组织的资产引用 ⇒ 本批留原户并具名（不越权搬）。
      if (foreignUsedShareIds.contains(share.id())) {
        residualReasons.putIfAbsent(share.id(), "leased-to-foreign-org:" + share.asset().name());
        continue;
      }
      // ACTIVE 质押的份额：随迁会让质押上界 fail-closed；本批不越权解押，留原户并具名。
      if (activePledgedShareIds.contains(share.id())) {
        residualReasons.putIfAbsent(share.id(), "pledged-retained:" + share.asset().name());
        continue;
      }
      long take =
          empties
              ? share.quantity()
              : Math.multiplyExact(share.quantity(), popTake) / Math.max(1L, populationBefore);
      take = Math.min(take, share.quantity());
      if (take <= 0L) {
        continue;
      }
      AssetKind asset = share.asset();
      AssetShare.RightKind kind = transferRightKind(share);
      ActorRef newOperator = transferOperator(share, targetActor);
      if (sameHex) {
        sameHexMoves.add(new AssetShareBook.Move(share.id(), take, targetActor, newOperator, kind));
      } else {
        if (!isMobileAsset(asset)) {
          residualReasons.putIfAbsent(share.id(), "immobile-cross-hex:" + asset.name());
          continue;
        }
        Optional<IndustryId> hostIndustry = hostIndustryFor(base, move.targetHex(), asset);
        if (hostIndustry.isEmpty()) {
          residualReasons.putIfAbsent(share.id(), "no-host-industry:" + asset.name());
          continue;
        }
        rebuildMoves.add(
            new AssetShareBook.RebuildMove(
                share.id(), take, hostIndustry.get(), targetActor, newOperator, kind));
      }
      if (take == share.quantity()) {
        movedWholeShareIds.add(share.id());
      }
    }
    List<AssetShareId> createdIds = new ArrayList<>();
    if (!sameHexMoves.isEmpty()) {
      createdIds.addAll(
          AssetShareBook.apply(assetShares, base.industries(), base.pledges(), sameHexMoves));
    }
    if (!rebuildMoves.isEmpty()) {
      createdIds.addAll(
          AssetShareBook.rebuild(assetShares, base.industries(), base.pledges(), rebuildMoves));
    }
    if (createdIds.isEmpty()) {
      return AssetMigrationOutcome.empty();
    }
    Map<IndustryId, Map<AssetKind, Long>> coverage = new LinkedHashMap<>();
    for (AssetShareId createdId : createdIds) {
      AssetShare created = assetShares.get(createdId);
      if (created == null) {
        throw new IllegalStateException("资产随迁新建份额在表里不存在（拒绝静默丢资产）: " + createdId);
      }
      coverage
          .computeIfAbsent(created.industry(), ignored -> new LinkedHashMap<>())
          .merge(created.asset(), created.quantity(), Math::addExact);
    }
    return new AssetMigrationOutcome(createdIds, coverage);
  }

  /** 可移动资产判据（D-023：TOOL/SHIP/CATTLE/MACHINE 随人走；LAND/WORKSHOP 不跨 hex）。 */
  private static boolean isMobileAsset(AssetKind asset) {
    return switch (asset) {
      case TOOL, SHIP, CATTLE, MACHINE -> true;
      case LAND, WORKSHOP -> false;
    };
  }

  /** 随迁新份额的权利性质：共有保持共有；其余一律随目标户自营（owner == operator ⇒ OWNED）。 */
  private static AssetShare.RightKind transferRightKind(AssetShare share) {
    return share.kind() == AssetShare.RightKind.COMMUNAL
        ? AssetShare.RightKind.COMMUNAL
        : AssetShare.RightKind.OWNED;
  }

  /**
   * 随迁后的 operator = 目标户（D-023 §5.3 的“改 owner/operator 到目标户”）。★ 已被别的组织实际使用的 出租份额不在这里处理：上游 {@code
   * foreignUsedAssetShareIds} 已把它按“留原户并具名”跳过，避免拆掉那个组织的引用。
   */
  private static ActorRef transferOperator(AssetShare share, ActorRef targetActor) {
    if (share == null || targetActor == null) {
      throw new IllegalArgumentException("transferOperator 的 share/targetActor 不得为 null");
    }
    return targetActor;
  }

  /** 目标 hex 能承载该 AssetKind 的第一个产业（{@link IndustryHexKeys#at} 已按 id 升序）；无 ⇒ 空。 */
  private static Optional<IndustryId> hostIndustryFor(
      EconomyData base, HexCoord targetHex, AssetKind asset) {
    for (IndustryId candidate :
        IndustryHexKeys.at(base.industries(), targetHex.q(), targetHex.r())) {
      Industry industry = base.industries().get(candidate);
      if (industry != null && industry.capacityPerUnit().containsKey(asset)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /** 被 organizer != 源户的组织在 assetSources 里实际使用的份额 id 并集（这些份额本批不搬，留原户并具名）。 */
  private static Set<AssetShareId> foreignUsedAssetShareIds(
      Map<ProductionOrganizationId, ProductionOrganization> organizations, ActorRef sourceActor) {
    Set<AssetShareId> used = new LinkedHashSet<>();
    for (ProductionOrganization organization : organizations.values()) {
      if (organization != null && !organization.organizer().equals(sourceActor)) {
        used.addAll(organization.assetSources());
      }
    }
    return used;
  }

  /** ACTIVE 质押指名的份额 id 并集（随迁会让质押上界 fail-closed；本批留原户并具名）。 */
  private static Set<AssetShareId> activePledgedAssetShareIds(Map<PledgeId, Pledge> pledges) {
    Set<AssetShareId> pledged = new LinkedHashSet<>();
    for (Pledge pledge : pledges.values()) {
      if (pledge != null && pledge.status() == Pledge.Status.ACTIVE) {
        pledged.add(pledge.assetShareId());
      }
    }
    return pledged;
  }

  /** 整条随迁后旧 id 已删：把源户自己的组织 assetSources 里的这些 id 摘掉（部分随迁 id 仍在、数量已减，不动）。 */
  private static void removeSourceOrganizationAssetSources(
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      ActorRef sourceActor,
      Set<AssetShareId> removedShareIds) {
    if (removedShareIds.isEmpty()) {
      return;
    }
    List<ProductionOrganizationId> organizationIds = new ArrayList<>(organizations.keySet());
    organizationIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : organizationIds) {
      ProductionOrganization organization = organizations.get(organizationId);
      if (organization == null || !organization.organizer().equals(sourceActor)) {
        continue;
      }
      List<AssetShareId> updated = new ArrayList<>(organization.assetSources());
      if (!updated.removeIf(removedShareIds::contains)) {
        continue;
      }
      organizations.put(
          organizationId,
          new ProductionOrganization(
              organization.id(),
              organization.modeId(),
              organization.classPositionId(),
              organization.unitId(),
              organization.organizer(),
              organization.laborSources(),
              updated,
              organization.inputSources(),
              organization.outputOwnership(),
              organization.relationTemplateRef(),
              organization.status(),
              organization.statusReason()));
    }
  }

  /**
   * ★★ D-023：本一次 apply 内新建的目标户，其组织 assetSources 必须指向随迁后的新份额 id（含后续源并入同一新户）。 只收 operator ==
   * organizer 的份额（EconomyData 的「组织使用的份额必须由 organizer 经营」守卫）。
   */
  private static void attachMigratedSharesToNewTargetOrganizations(
      Set<HouseholdId> createdTargets,
      Map<HouseholdId, List<AssetShareId>> migratedShareIdsByTarget,
      Map<HouseholdId, ClassRow> rows,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    if (migratedShareIdsByTarget.isEmpty()) {
      return;
    }
    List<HouseholdId> targets = new ArrayList<>(migratedShareIdsByTarget.keySet());
    targets.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId target : targets) {
      if (!createdTargets.contains(target)) {
        continue; // 已有目标户不在此路（其组织不是本批新建；随迁份额仍按 operator 计产能）
      }
      ProductionOrganization organization = organizationOf(target, rows, organizations);
      if (organization == null) {
        continue; // DISPLACED 新建户不建组织
      }
      List<AssetShareId> updated = new ArrayList<>(organization.assetSources());
      for (AssetShareId shareId : migratedShareIdsByTarget.get(target)) {
        AssetShare share = assetShares.get(shareId);
        if (share == null || !share.operator().equals(organization.organizer())) {
          continue;
        }
        if (!updated.contains(shareId)) {
          updated.add(shareId);
        }
      }
      if (updated.size() == organization.assetSources().size()) {
        continue;
      }
      organizations.put(
          organization.id(),
          new ProductionOrganization(
              organization.id(),
              organization.modeId(),
              organization.classPositionId(),
              organization.unitId(),
              organization.organizer(),
              organization.laborSources(),
              updated,
              organization.inputSources(),
              organization.outputOwnership(),
              organization.relationTemplateRef(),
              organization.status(),
              organization.statusReason()));
    }
  }

  /** ★ D-023 留原户资产的具名读数（瞬态 ProductionLedger；不新增持久组件、改数量为 0）。 */
  private static void recordResidualAssetAudits(
      ProductionLedger.Accumulator auditLedger,
      long day,
      HouseholdId source,
      Map<AssetShareId, AssetShare> assetShares,
      Map<AssetShareId, String> residualReasons) {
    if (auditLedger == null || residualReasons.isEmpty()) {
      return;
    }
    List<AssetShareId> shareIds = new ArrayList<>(residualReasons.keySet());
    shareIds.sort(Comparator.comparing(AssetShareId::value));
    for (AssetShareId shareId : shareIds) {
      AssetShare share = assetShares.get(shareId);
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
   * ★★ <b>D-023 第 5 项：按目标 mode + 目标产业 regime 生成完整 {@link ProductionRelation}</b>。
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
   * <p>★★ <b>GAP-3 同源收口</b>：模板里的 {@code ToCohort(view)} 受方必须像 E2 自动组织一样在**落盘前**用当前
   * rows 归一成 {@link Recipient.ToHousehold}（唯一视图）或退回最小自留关系；否则本关系会在**当天** harvest 时被
   * {@code requireCohortRows} 的"视图不再是唯一身份"守卫拒绝。归一逻辑不另写一份，直接复用
   * {@link EconomyOrganizationSettlement} 的唯一拼写点。
   */
  private static MigrationRelationPlan buildMigrationRelation(
      ModeMigrationPolicy.MigrationMove move,
      ProductionUnitId unitId,
      IndustryId industryId,
      ActorRef targetActor,
      Map<HouseholdId, ClassRow> rows,
      ClassRow targetRow,
      EconomyData base,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    Optional<RegimeId> templateRegime =
        migrationTemplateRegime(move.targetMode(), industryId, base);
    Set<ResidenceKind> residences = Set.of(targetRow.view().residence());
    if (templateRegime.isPresent()) {
      try {
        ProductionRelation relation =
            RegimeRelations.defaultRelation(
                templateRegime.get(), unitId, industryId, targetActor, residences);
        ProductionRelation normalized =
            EconomyOrganizationSettlement.normalizeRecipients(
                relation, rows, targetRow.view().hex());
        if (normalized != null) {
          return new MigrationRelationPlan(
              normalized,
              "migration:" + move.reason() + ":regime:" + templateRegime.get().value());
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

  /** mode → 关系模板 regime（唯一映射点；未知/未登记 ⇒ 空 = 具名回退，不猜别的制度）。 */
  private static Optional<RegimeId> migrationTemplateRegime(
      ProductionModeId mode, IndustryId industryId, EconomyData base) {
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
      Industry industry = base.industries().get(industryId);
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
    Industry industry = base.industries().get(industryId);
    if (industry != null && RegimeRelations.registered().containsKey(industry.regime().value())) {
      return Optional.of(industry.regime());
    }
    return Optional.empty();
  }

  /** 具名回退：空规则 = 产出全归 operator（显式允许的缺省路径，但绝不在无具名原因时使用）。 */
  private static ProductionRelation fallbackMigrationRelation(
      ProductionUnitId unitId, ActorRef targetActor) {
    return new ProductionRelation(
        unitId,
        targetActor,
        new Recipient.ToActor(targetActor),
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

  private static ProductionOrganization organizationOf(
      HouseholdId household,
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    ActorRef actor = HouseholdActors.of(household);
    ProductionOrganization best = null;
    List<ProductionOrganizationId> ids = new ArrayList<>(organizations.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : ids) {
      ProductionOrganization organization = organizations.get(id);
      if (organization != null && organization.organizer().equals(actor)) {
        best = organization;
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
      ActorRef sourceActor, Map<AssetShareId, AssetShare> assetShares) {
    for (AssetShare share : assetShares.values()) {
      if (share.quantity() > 0L
          && (share.owner().equals(sourceActor) || share.operator().equals(sourceActor))) {
        return true;
      }
    }
    return false;
  }

  private static long[] toLongArray(List<Long> values) {
    long[] array = new long[values.size()];
    for (int i = 0; i < values.size(); i++) {
      array[i] = values.get(i);
    }
    return array;
  }

  private static void putOrRemove(Map<CurrencyId, Long> table, CurrencyId key, long amount) {
    if (amount <= 0L) {
      table.remove(key);
    } else {
      table.put(key, amount);
    }
  }
}
