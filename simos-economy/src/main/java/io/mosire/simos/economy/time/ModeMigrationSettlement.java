package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionUnit;
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
 * ★★ <b>P10.2 迁移执行器（架构 §5.3 的 ⑨）</b>：<b>只执行</b> {@link ModeMigrationPolicy.MigrationPlan}
 * 里已确定的 move，不重算利润、不改计划。
 *
 * <pre>
 * 逐 move：
 *   ① 源/目标行解析；目标已有 ⇒ 合并；目标缺失 ⇒ 新建（ClassRow + ClassStanding + 账户 + 组织/unit/关系 + 租赁资产）
 *   ② 人口/劳动/成员份额/劳动配额按人口比例移动（逐笔 floor；源户清空时余数随最后一笔走）
 *   ③ 货币按**全部币种**逐项移动（D-023：逐币种按人口比例 floor，余数留源/迁空随最后一笔；不做 FX；只搬余额，不新造）
 *   ④ 债务逐合同走 DebtContractBook.reduce/upsert（唯一写口）；计划金额全部分摊，绝不静默丢债
 *   ⑤ 源户人口归零：清点货币/债务必须清零 ⇒ 从 classes/classStandings 移除，并清掉它的组织/unit/关系；
 *      源户资产按"随最后一批人"转给最后目标（资产守恒）
 * </pre>
 *
 * <p>★★ <b>D-022 硬不变量</b>：源户的 {@code ClassStanding} / {@code ProductionOrganization.modeId} /
 * {@code ProductionUnit.modeKey} 在本类里<b>一字不改</b>；目标 mode 只出现在目标家户（已有或新建）上。
 *
 * <p>★★ <b>失败具名抛</b>：不静默丢人/丢债/丢钱；整段写入发生在同一个 {@link EconomySession}/revision 内。
 */
public final class ModeMigrationSettlement {

  private ModeMigrationSettlement() {}

  /** 执行一份计划；空计划 ⇒ 一字不改。 */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ModeMigrationPolicy.MigrationPlan plan,
      EconomyData base,
      long day) {
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
    LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition> operatorConditions =
        session.sheet().operatorConditions();
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
    // ★★ P10.7：本一次 apply 内新出现的"被组织持有"资产份额 id。初始 = base 既有组织 assetSources；
    //    源户注销时整条份额换新 id 且仍被存活组织引用 ⇒ 立刻登记，后续新建户不得再把它当闲置拆走。
    Set<AssetShareId> sameApplyClaimed = new LinkedHashSet<>();
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
          sameApplyClaimed);
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
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition> operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      LinkedHashMap<PledgeId, Pledge> pledges,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, DemandEntry> demands,
      EconomyData base,
      long day,
      Set<AssetShareId> sameApplyClaimed) {
    ClassRow sourceRow = rows.get(source);
    if (sourceRow == null) {
      throw new IllegalStateException("迁移源家户不存在（拒绝静默丢人）: " + source);
    }
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

    long populationLeft = sourcePopulation;
    long laborLeft = sourceRow.laborMilli();
    long movedLaborTotal = 0L;
    HouseholdId lastTarget = null;
    long lastTargetPopulation = 0L;
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
        ClassPosition targetPosition = base.classPositions().get(targetStanding.currentPositionId());
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
        targetUnit = targetOrg != null && targetOrg.unitId().isPresent() ? targetOrg.unitId().get() : null;
      } else {
        newTargetPosition = createNewHousehold(move, sourceRow, rows, standings, accounts, base, day);
        // ★★ 新建目标户后必须刷新行引用：createNewHousehold 只把新行写进 rows，
        //    不改变本方法早先捕获的 targetRow（原为 null）。不刷新 ⇒ 下一段 NPE。
        targetRow = rows.get(move.target());
        if (targetRow == null) {
          throw new IllegalStateException(
              "新建目标家户后行表里仍无该行（拒绝静默丢人）: " + move.target());
        }
      }

      // ② 人口/劳动
      long popTake = move.population();
      long laborTake =
          empties
              ? laborLeft
              : Math.multiplyExact(sourceRow.laborMilli(), popTake) / Math.max(1L, populationBefore);
      if (laborTake > laborLeft) {
        throw new IllegalStateException(
            "迁移劳动超过源户剩余劳动（拒绝抽成负劳动）: source=" + source + " take=" + laborTake);
      }
      rows.put(move.target(), targetRow.withPopulationAndLabor(targetRow.population() + popTake, targetRow.laborMilli() + laborTake));
      sourceRow = sourceRow.withPopulationAndLabor(sourceRow.population() - popTake, sourceRow.laborMilli() - laborTake);
      rows.put(source, sourceRow);
      populationLeft = sourceRow.population();
      laborLeft = sourceRow.laborMilli();
      movedLaborTotal = Math.addExact(movedLaborTotal, laborTake);
      if (newTarget && !DefaultProductionModes.DISPLACED.equals(move.targetMode())) {
        // ★ 组织/unit/租赁资产必须在目标行带上迁移人口/劳动之后建（规模 = 劳动/工艺需求，不能拿 0 劳动建）。
        targetUnit =
            createOrganizationAndUnit(
                move,
                rows.get(move.target()),
                newTargetPosition,
                assetShares,
                units,
                relations,
                organizations,
                base,
                sameApplyClaimed);
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
                  membershipId, membership.lot(), membership.household(), membership.count() - take));
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

      lastTarget = move.target();
      lastTargetPopulation = popTake;
      if (empties) {
        break;
      }
    }

    // ⑤ 源户消亡 / 缩编
    if (sourceRow.population() == 0L) {
      retireSource(
          source,
          session,
          lastTarget,
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
          day,
          sameApplyClaimed);
    }
  }

  /** 源户人口清零后的清点与移除：钱/债必须为 0；资产随最后一批人；组织/unit/关系/劳动配额一并退役。 */
  private static void retireSource(
      HouseholdId source,
      EconomySession session,
      HouseholdId lastTarget,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassStanding> standings,
      LinkedHashMap<MembershipId, Membership> memberships,
      LinkedHashMap<LaborAllocationId, LaborAllocation> allocations,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, io.mosire.simos.economy.model.OperatorCondition> operatorConditions,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      LinkedHashMap<ClassShareId, ClassShare> classShares,
      Map<DemandId, DemandEntry> demands,
      AccountSession accounts,
      Map<PledgeId, Pledge> pledges,
      long day,
      Set<AssetShareId> sameApplyClaimed) {
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
    // 需求/模式变迁份额指名源户时：若源户要消亡则本批无法表达"迁移它们" ⇒ fail-closed 具名抛；
    // 若已是空壳（合同端点残留）则保留行本身，表仍可解析。
    if (!contractShell) {
      for (DemandEntry demand : demands.values()) {
        if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD
            && demand.household().isPresent()
            && demand.household().get().equals(source)) {
          throw new IllegalStateException(
              "源户挂着 HOUSEHOLD 范围的需求（需求表不是本批工作副本，无法随迁）: source=" + source + " demand=" + demand.id());
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
    ActorRef sourceActorForOrg = HouseholdActors.of(source);
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
    // 资产随最后一批人（资产守恒；不是"凭空消失"）
    ActorRef sourceActor = HouseholdActors.of(source);
    if (lastTarget != null) {
      ActorRef targetActor = HouseholdActors.of(lastTarget);
      List<AssetShareBook.Move> moves = new ArrayList<>();
      List<AssetShareId> movedSourceIds = new ArrayList<>();
      for (AssetShare share : assetShares.values()) {
        if (share.quantity() <= 0L) {
          continue;
        }
        boolean owned = share.owner().equals(sourceActor);
        boolean operated = share.operator().equals(sourceActor);
        if (!owned && !operated) {
          continue;
        }
        ActorRef newOwner = owned ? targetActor : share.owner();
        ActorRef newOperator = operated ? targetActor : share.operator();
        moves.add(
            new AssetShareBook.Move(
                share.id(),
                share.quantity(),
                newOwner,
                newOperator,
                owned ? AssetShare.RightKind.OWNED : AssetShare.RightKind.TENANCY));
        movedSourceIds.add(share.id());
      }
      if (!moves.isEmpty()) {
        List<AssetShareId> createdIds =
            AssetShareBook.apply(assetShares, null, pledges, moves);
        // ★★ P10.7：整条份额随最后一批人转移时旧 id 会被删除/换新 id；若某个存活组织的 assetSources
        //    仍指向旧 id，EconomyData 的「生产组织使用的资产份额必须已存在」会在同一 revision 当场拒绝。
        //    这里把存活组织的引用改指新 id（不忽略悬空引用、不只放水守卫），并把新 id 登记进本一次 apply
        //    的 claimed 集，后续新建户不得再把它当闲置拆走。
        sameApplyClaimed.addAll(
            rewriteOrganizationAssetSourceIds(
                organizations, sourceActor, movedSourceIds, createdIds));
      }
    } else if (hasAnySourceAsset(sourceActor, assetShares)) {
      throw new IllegalStateException("源户资产没有可承接的目标（拒绝静默丢资产）: " + source);
    }
    // 组织/unit/关系/配额/份额/standing/行 一并退役（源户 mode/standing 不改写，直接消失）
    List<ProductionUnitId> removedUnits = new ArrayList<>();
    List<ProductionOrganizationId> removedOrganizations = new ArrayList<>();
    for (ProductionOrganization organization : new ArrayList<>(organizations.values())) {
      if (organization.organizer().equals(sourceActor)) {
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
      if (allocation.household().equals(source) || removedUnits.contains(unitOfActivity(allocation.activity()))) {
        allocations.remove(allocation.id());
      }
    }
    for (Membership membership : new ArrayList<>(memberships.values())) {
      if (membership.household().equals(source)) {
        memberships.remove(membership.id());
      }
    }
    if (!contractShell) {
      standings.remove(source);
      rows.remove(source);
      // ★★ 缺陷②：flows 与 classes 自 S1 起同键 —— 源户行删除时必须同步删它的 FlowRow，
      //    否则同一 revision 的 EconomyData 构造期守卫会以「flows 的键必须是已存在的家户」拒绝。
      session.flows().remove(source);
      // ★ 同类跨表键：危机信号的 households 点名源户时也得摘掉（否则 build 守卫「危机信号点名的家户不存在」）。
      removeCrisisSignalReferences(session.sheet().crisisSignals(), source);
    } else {
      // 空壳：人口 0、无组织/unit/配额/资产；保留 ClassRow + ClassStanding 让债务合同端点仍可解析。
      ClassRow shell = rows.get(source);
      if (shell != null && shell.population() != 0L) {
        throw new IllegalStateException("空壳源户人口必须为 0: " + source + " population=" + shell.population());
      }
    }
  }

  /**
   * ★★ <b>P10.7 同一条不变量在"资产随最后一批人"路径上的修法</b>：源户注销时整条份额走 {@link
   * AssetShareBook#apply} 会删除旧 id、生成新 id；把仍存活组织的 {@code assetSources} 从旧 id 改指同一批新 id，
   * 避免 EconomyData 的「生产组织使用的资产份额必须已存在」fail-closed。新份额的 operator 逐值保持原 operator
   * （只换 owner），故「组织 organizer == 份额 operator」守卫仍成立。
   *
   * @return 本步真正被存活组织接管的新份额 id（追加进同一次 apply 的 claimed 集，后续新建户不能再拆）
   */
  private static Set<AssetShareId> rewriteOrganizationAssetSourceIds(
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      ActorRef retiredOrganizer,
      List<AssetShareId> movedSourceIds,
      List<AssetShareId> createdIds) {
    Set<AssetShareId> newlyClaimed = new LinkedHashSet<>();
    if (movedSourceIds.isEmpty()) {
      return newlyClaimed;
    }
    if (movedSourceIds.size() != createdIds.size()) {
      throw new IllegalStateException(
          "AssetShareBook.apply 返回的新 id 数与 Move 数不一致（拒绝半数改引用）: moved="
              + movedSourceIds.size()
              + " created="
              + createdIds.size());
    }
    Map<AssetShareId, AssetShareId> replacement = new LinkedHashMap<>();
    for (int i = 0; i < movedSourceIds.size(); i++) {
      replacement.put(movedSourceIds.get(i), createdIds.get(i));
    }
    for (ProductionOrganization organization : new ArrayList<>(organizations.values())) {
      if (organization.organizer().equals(retiredOrganizer)) {
        continue; // 该组织在同一 retireSource 里会被移除；引用无需转移
      }
      boolean changed = false;
      List<AssetShareId> updated = new ArrayList<>(organization.assetSources().size());
      for (AssetShareId assetSource : organization.assetSources()) {
        AssetShareId newer = replacement.get(assetSource);
        if (newer == null) {
          updated.add(assetSource);
        } else {
          updated.add(newer);
          changed = true;
          newlyClaimed.add(newer);
        }
      }
      if (changed) {
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
    return newlyClaimed;
  }

  /**
   * ★ 同类跨表键清理：危机信号以 {@code households} 列表指名家户；源户行已删时把它从每个信号的
   * {@code households} 里摘掉（信号本身按 {@code (hex, kind)} 的最新警告保留，不删行、不改 severity/evidence）。
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
            target,
            positionId,
            positionId,
            Map.of(),
            0L,
            day,
            "AUTO_MIGRATION:" + move.reason()));
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
   * 为一个产业模板规划"从该 hex 的闲置份额拆出 TENANCY"的资产移动（{@link AssetShareBook#apply} 唯一写口）；
   * 任一 capacity 种类不足 ⇒ null（该模板不可行）。
   *
   * <p>★★ P10.7：闲置判据的唯一拼写点是 {@link ModeMigrationPolicy#isIdleShare} ——
   * {@code quantity > 0 && operator == owner && id ∉ 既有组织 assetSources}。既有商号/组织在用的份额
   * 不是闲置，拆空会让它的 assetSources 指向已删除的份额 id。
   */
  private static List<AssetShareBook.Move> planAssetMoves(
      IndustryId industryId,
      Industry industry,
      ActorRef targetActor,
      long scale,
      Map<AssetShareId, AssetShare> assetShares,
      Set<AssetShareId> claimedByOrganizations) {
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
      long need = Math.multiplyExact(scale, required.getValue());
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
                share.id(),
                take,
                share.owner(),
                targetActor,
                AssetShare.RightKind.TENANCY));
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
      ClassRow targetRow,
      ClassPositionId positionId,
      LinkedHashMap<AssetShareId, AssetShare> assetShares,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      LinkedHashMap<ProductionUnitId, ProductionRelation> relations,
      LinkedHashMap<ProductionOrganizationId, ProductionOrganization> organizations,
      EconomyData base,
      Set<AssetShareId> sameApplyClaimed) {
    ActorRef targetActor = HouseholdActors.of(move.target());
    List<IndustryId> industries =
        new ArrayList<>(IndustryHexKeys.at(base.industries(), move.targetHex().q(), move.targetHex().r()));
    industries.sort(Comparator.comparing(IndustryId::value));
    if (industries.isEmpty()) {
      throw new IllegalStateException("新建目标 hex 没有产业模板（拒绝凭空造生产）: " + move.targetHex());
    }
    IndustryId industryId = null;
    Industry industry = null;
    List<AssetShareBook.Move> assetMoves = new ArrayList<>();
    // ★★ P10.7 / P10.8：既有组织 assetSources 正在使用的份额不是闲置。这里与 ModeMigrationPolicy.plan() 顶层
    //    共用同一个谓词，并读同一份当天工作副本 organizations（含当天自动组织阶段新加、以及本一次 apply 前几笔
    //    新建的组织），保证"计划可新建 ⇔ 执行可拆到"；
    //    sameApplyClaimed 追加本一次 apply 内刚被存活组织接管的份额新 id（源户注销换 id 的路径）。
    Set<AssetShareId> claimedByOrganizations =
        ModeMigrationPolicy.claimedAssetShares(organizations);
    if (!sameApplyClaimed.isEmpty()) {
      claimedByOrganizations = new LinkedHashSet<>(claimedByOrganizations);
      claimedByOrganizations.addAll(sameApplyClaimed);
    }
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
              candidate, candidateIndustry, targetActor, scale, assetShares, claimedByOrganizations);
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
    ProductionUnitId unitId = ProductionUnitId.idOf(industryId, targetActor);
    if (units.containsKey(unitId)) {
      throw new IllegalStateException("新建目标 unit id 已存在（拒绝覆盖）: " + unitId);
    }
    ProductionUnit unit =
        new ProductionUnit(unitId, industryId, targetActor, "mode:" + move.targetMode().value(), 0L, 0L, Map.of());
    units.put(unitId, unit);
    relations.put(
        unitId,
        new ProductionRelation(
            unitId,
            targetActor,
            new Recipient.ToActor(targetActor),
            List.of(),
            targetActor,
            LaborSource.SELF));
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
            createdShares,
            List.of(new Recipient.ToActor(targetActor)),
            new Recipient.ToActor(targetActor),
            Optional.of("migration:" + move.reason()),
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
      AccountSession accounts, HouseholdId source, HouseholdId target, CurrencyId currency, long amount) {
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
   * ★★ <b>P11.1 / D-023：旧 / 手工计划（{@code moneyByCurrency} 为空）的全币种兜底</b> —— 按当前余额逐币种
   * 独立切：{@code share = ⌊余额 × popTake ÷ 迁出前人口⌋}（源户迁空 ⇒ 该币种余额全走），余数留源户。<b>不做 FX</b>，
   * 逐币种并列存在。
   */
  private static void moveAllCurrenciesByPopulation(
      AccountSession accounts,
      HouseholdId source,
      HouseholdId target,
      long populationBefore,
      long popTake,
      boolean empties) {
    Map<CurrencyId, Long> initialBalances = accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(initialBalances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long balance =
          accounts
              .householdMoney()
              .getOrDefault(source, Map.of())
              .getOrDefault(currency, 0L);
      if (balance <= 0L) {
        continue;
      }
      long share =
          empties
              ? balance
              : Math.multiplyExact(balance, popTake) / Math.max(1L, populationBefore);
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

  private static boolean hasAnySourceAsset(ActorRef sourceActor, Map<AssetShareId, AssetShare> assetShares) {
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
