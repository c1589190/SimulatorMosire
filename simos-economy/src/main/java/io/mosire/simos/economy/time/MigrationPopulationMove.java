package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MigrationAssetMove.attachMigratedSharesToNewTargetEnterprises;
import static io.mosire.simos.economy.time.MigrationAssetMove.createEnterpriseAndProcess;
import static io.mosire.simos.economy.time.MigrationAssetMove.foreignUsedOwnershipStakeIds;
import static io.mosire.simos.economy.time.MigrationAssetMove.migrateAssetsForMove;
import static io.mosire.simos.economy.time.MigrationAssetMove.recordResidualAssetAudits;
import static io.mosire.simos.economy.time.MigrationAssetMove.removeSourceEnterpriseAssetSources;
import static io.mosire.simos.economy.time.MigrationDebtMove.carryGoodsForMove;
import static io.mosire.simos.economy.time.MigrationDebtMove.moveAllCurrenciesByPopulation;
import static io.mosire.simos.economy.time.MigrationDebtMove.moveDebt;
import static io.mosire.simos.economy.time.MigrationDebtMove.moveMoney;
import static io.mosire.simos.economy.time.ModeMigrationSettlement.createNewHousehold;
import static io.mosire.simos.economy.time.ModeMigrationSettlement.enterpriseOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.economy.time.MigrationAssetMove.AssetMigrationOutcome;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
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
import java.util.Optional;
import java.util.Set;

/** P0.5-E1 机械拆分自 {@code ModeMigrationSettlement}；职责见类名与各方法注释。 */
final class MigrationPopulationMove {

  private MigrationPopulationMove() {}

  /** 迁移执行日志（与门面同一 migration 分类；logger 名仍唯一由 EconomyLog 拼写）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  static void applySource(
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

      // ★★★ D-032 B3（I10 / §13.13.7 第 6 条）：人口迁移完整携带各经验条（主值 + 余数、不乘人口比例），
      //   到迁入户后按人口加权平均合并；唯一权威在 production（mergeExperience 契约口）。
      MigrationExperienceMove.mergeExperience(
          session, source, popTake, move, targetPlannedPopulationBox, day);

      // ★★ P0：本笔 move 校验/落账全部成功后追加 outbox（人口事实），由 App 在同一 revision 内
      //    翻译成 Social 工单、再把 Social 真值 delta 回写经济行。outbox 是瞬态：整次 advance 失败 ⇒ 会话丢弃。
      EconomyPopulationTransfer populationTransfer =
          new EconomyPopulationTransfer(
              source,
              move.target(),
              popTake,
              move.targetHex(),
              Optional.of(move.targetMode()),
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
    // ★★ D-023：跨 hex 不可移动 / 目标 hex 无法承载 / 已出租给他组织 ⇒ 留原户的具名读数
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
  static void retireSource(
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
    //   ★ 债务价值守恒由 moveDebt 的 target upsert 保证，删 0 本金行不减少任何本金。
    List<DebtContractId> zeroDebtorContracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() == 0L) {
        zeroDebtorContracts.add(contract.id());
      }
    }
    for (DebtContractId contractId : zeroDebtorContracts) {
      debts.remove(contractId);
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
    // ★★★ RC8e：roster 是 laborSources 的派生权威。整户迁空时 mergeHousehold 已把 source 替换/合并到 target；
    //   这里必须按 roster.households() 重建企业引用 —— 只 remove(source) 会把 target/合并后的户集合漏掉，
    //   下一 revision 的 HouseholdReferenceValidation 以 enterprise-roster-mismatch fail-closed。
    //   有 roster ⇒ 逐户同步（保持 organizer/unit/classPosition/mode/资产/投入/产出/状态不变）；
    //   无 roster ⇒ 保留旧防御路径：仅当源户仍在 laborSources 时 remove(source)。
    Map<ProductionUnitId, ProductionRoster> rostersForEnterprises =
        session.sheet().productionRostersOrBase();
    for (ProductionEnterprise enterprise : new ArrayList<>(enterprises.values())) {
      ProductionRoster roster =
          enterprise.unitId().isPresent()
              ? rostersForEnterprises.get(enterprise.unitId().get())
              : null;
      if (roster != null) {
        List<HouseholdId> derived = List.copyOf(roster.households());
        if (!enterprise.laborSources().equals(derived)) {
          enterprises.put(enterprise.id(), withLaborSources(enterprise, derived));
          if (LOG.isDebugEnabled()) {
            EventLog.channel(LOG)
                .debug(
                    LogEvent.of(
                        "MIGRATION_ENTERPRISE_ROSTER_SYNC",
                        EconomyLogSource.ECONOMY_MIGRATION,
                        "day",
                        day,
                        "organization",
                        enterprise.id().value(),
                        "unit",
                        enterprise.unitId().get().value(),
                        "before",
                        enterprise.laborSources().size(),
                        "after",
                        derived.size()));
          }
        }
        continue;
      }
      if (enterprise.laborSources().contains(source)) {
        List<HouseholdId> sources = new ArrayList<>(enterprise.laborSources());
        sources.remove(source);
        enterprises.put(enterprise.id(), withLaborSources(enterprise, List.copyOf(sources)));
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

  static void refuseGovServiceMigration(
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

  static void moveLaborCommitmentToTarget(
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

  static ProductionUnitId unitOfActivity(String activity) {
    if (activity == null || activity.isBlank()) {
      return null;
    }
    try {
      return ProductionUnitId.parse(activity);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static boolean hasAnySourceAsset(
      ActorRef sourceActor, Map<AssetShareId, OwnershipStake> assetShares) {
    for (OwnershipStake share : assetShares.values()) {
      if (share.quantity() > 0L
          && (share.owner().equals(sourceActor) || share.operator().equals(sourceActor))) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★★ RC8e：仅替换 {@code laborSources} 的企业重写 —— 其余字段逐值保留。
   *
   * <p>roster 是 laborSources 的派生权威；同步时不得顺带改 organizer / unitId / classPositionId / modeId /
   * assetSources / inputSources / outputOwnership / status。构造器仍会做保序去重与冻结，{@code laborSources} 由调用方按
   * {@code roster.households()} 的稳定序传入。
   */
  private static ProductionEnterprise withLaborSources(
      ProductionEnterprise enterprise, List<HouseholdId> laborSources) {
    return new ProductionEnterprise(
        enterprise.id(),
        enterprise.modeId(),
        enterprise.classPositionId(),
        enterprise.unitId(),
        enterprise.organizer(),
        laborSources,
        enterprise.assetSources(),
        enterprise.inputSources(),
        enterprise.outputOwnership(),
        enterprise.relationTemplateRef(),
        enterprise.status(),
        enterprise.statusReason());
  }
}
