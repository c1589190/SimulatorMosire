package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.addGoods;
import static io.mosire.simos.economy.time.SettlementMath.addStock;
import static io.mosire.simos.economy.time.SettlementMath.hexOfIndustry;
import static io.mosire.simos.economy.time.SettlementMath.moneyOf;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ProductionOrganizationSettlement {

  /**
   * ★★ <b>E1：退出经营者的固定顺序事实处置</b>（{@code SUSPENDED -> EXITED} / {@code EXITING -> EXITED}）。
   *
   * <pre>
   * 1. 劳动释放   ：删除 activity == exit.unit 的全部 HouseholdLaborCommitment（laborSupply 不动），释放量进读数
   * 2. 资产处置   ：unit 名下（industry + operator）份额里，owner != operator 的只把 operator 改回 owner；
   *                owner == operator 的份额原样留在 owner 名下（unit 已停业，计划系数 0）；
   *                并同步把退回份额从该 operator 的生产组织 assetSources 里摘掉（GAP-2：不得留下 operator != organizer 的引用）
   * 3. 债务处置   ：只对解析出的 exit.household 执行（null = 聚合主体 ⇒ 跳过，不伪造家户债）：
   *                逐条按 (到期周期, 商品维, id) canonical 升序；paid = min(本金, 可用余额)；
   *                paid > 0 ⇒ 铸 LOAN_REPAYMENT 转移（唯一写口 applyTransfer）；不足才 defaulted=true
   * 4. 库存/货币  ：剩余留在原主体账上，不没收、不蒸发
   * 5. 状态与理由 ：状态机已置 EXITED；这里只把处置摘要追加进 lastReason
   * </pre>
   *
   * <p>★★ <b>五条硬边界</b>：① 债务只处置解析出的家户债务人（聚合主体的债不在 economy 会话里，不能凭空给它销债）；② 还不起的部分**只标 {@code
   * defaulted}**，不从表里删、不由结算“核销”； ③ 剩余库存/货币留在原主体账上（没有“没收”规则，不凭空造也不删）；④ 资产份额**只改
   * operator**，owner/quantity/kind 与 id 不变； ⑤ 每个 unit 只在本列表里处置一次（状态机被判 EXITED 后不再自转）。
   *
   * <p>★★ <b>E5a 如实边界：本方法仍是份额表的直接写入点，不委托 {@link OwnershipStakeBook}</b>。理由：它做的是 <b>id 保持不变的
   * operator 回主</b>（份额身份编码了 operator；{@code OwnershipStakeBook.transfer} 会按新 tuple 生成新
   * id，与本方法的契约「id 不变」冲突）。这是对"资产份额转移只走唯一写口"的<b>显式记为遗留的例外</b>，不是新增写路径； E5b 清算新增的转移/拆分一律只走 {@link
   * OwnershipStakeBook}。若要收口，应由 Book 提供一个批量、原子、id 保持的 operator 重指派口（E5a 未做，避免在无测试保护的阶段改这条低频处置路径的 id
   * 语义）。
   */
  static void settleOperatorExits(
      List<OperatorSettlement.Exit> exits,
      LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      SettlementIndex index,
      ProductionLedger.Accumulator ledger,
      long day,
      MoneyIssuanceJournal issuanceJournal) {
    for (OperatorSettlement.Exit exit : exits) {
      // ── 1. 释放劳动：删除 activity == 本 unit 的全部配额（laborSupply 一字不动）────────────────
      long releasedLaborMilli = 0L;
      int releasedAllocations = 0;
      for (LaborAllocationId allocationId : index.allocationIdsOfUnit(exit.unit())) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(allocationId);
        if (laborCommitment == null || !laborCommitment.activity().equals(exit.unit().value())) {
          continue; // 索引是入口快照；处置过程中可能已被前一个 exit 改动 ⇒ 按当前行再核一次
        }
        releasedLaborMilli += laborCommitment.laborMilli();
        laborCommitments.remove(allocationId);
        releasedAllocations++;
      }

      // ── 2. 资产处置：TENANCY/委托份额 operator 改回 owner；OWNED 份额留在 owner 名下 ──────────
      //   不得删份额、不得改 owner/quantity/kind；id 不变（opaque 身份，operator 只是行内一栏）。
      int returnedShares = 0;
      long returnedQuantity = 0L;
      int keptOwnedShares = 0;
      Set<AssetShareId> returnedShareIds = new LinkedHashSet<>();
      for (AssetShareId shareId : index.ownershipStakeIdsOfProcess(exit.unit())) {
        OwnershipStake share = assetShares.get(shareId);
        if (share == null
            || !share.industry().equals(exit.industry())
            || !share.operator().equals(exit.operator())) {
          continue; // 同上：用当前行再核作用域，避免处理已被前一个 exit 改过的份额
        }
        if (!share.owner().equals(exit.operator())) {
          assetShares.put(
              shareId,
              new OwnershipStake(
                  share.id(),
                  share.industry(),
                  share.asset(),
                  share.owner(),
                  share.owner(), // ★ 只改 operator：份额回到所有者、可再出租
                  share.quantity(),
                  share.kind(),
                  share.location()));
          returnedShares++;
          returnedQuantity += share.quantity();
          returnedShareIds.add(shareId);
        } else {
          keptOwnedShares++; // owner == operator：本来就是它自己的，unit 不再运行（计划系数 0），份额原样留下
        }
      }
      // ★★ GAP-2：退回 owner 的份额已不由 exit.operator 经营；该 operator 的生产组织 assetSources
      //   不得继续指名它们（否则 EconomyData 的 operator==organizer 守卫会在 revision 边界 fail-closed）。
      detachReturnedSharesFromEnterprises(enterprises, exit.operator(), returnedShareIds);

      // ── 3. 债务处置：保留既有"剩余库存/货币先偿债、不足才 defaulted"逻辑 ──────────────────────
      HouseholdId debtor = exit.household();
      long debtPaidMilli = 0L;
      long debtDefaultedMilli = 0L;
      int debtDefaultedItems = 0;
      HexCoord location =
          IndustryHexKeys.hexKeyOf(exit.industry()).map(HexCoord::parse).orElse(null);
      if (debtor != null && location != null) {
        // ★ 索引给出该债务人的债务 id 序（全局债务表序）；再逐条取活表当前值（前面的 exit 可能已改本金）。
        List<DebtContract> owed =
            new ArrayList<>(index.debtsByDebtor().getOrDefault(debtor, List.of()));
        owed.sort(
            Comparator.comparingLong((DebtContract debt) -> debt.dueCycle().orElse(Long.MAX_VALUE))
                .thenComparing(debt -> debt.unit().currency().value())
                .thenComparing(debt -> debt.id().value()));
        for (DebtContract snapshot : owed) {
          DebtContract debt = debts.get(snapshot.id());
          if (debt == null || !debt.debtor().equals(debtor)) {
            continue;
          }
          if (DebtContractBook.isSelfHeld(debt)) {
            // ★★★ W5-fix（BLOCKED-2 同族，2026-10-11）：**自持合同（debtor == creditor）不是可执行的债权**（见
            //   `repayDebts` 里 DEBT_REPAY_SELF_HELD_SKIPPED 那一段的同一口径）⇒ 这里若照旧铸
            //   debtor → creditor 的清偿转移，`Transfer` 会因"两端相等"当场抛（退出清算把自持债算进去 = 同一个坑）。
            //   处置**与还债轮同一口径**：排除、不铸转移、**不判违约**（它是记账事实，不是坏数据），
            //   本金与状态一概不动；**不静默**：DEBUG 具名一条。
            if (TRACE.isDebugEnabled()) {
              EventLog.channel(TRACE)
                  .debug(
                      LogEvent.of(
                          "ENTERPRISE_EXIT_SELF_HELD_DEBT_SKIPPED",
                          EconomyLogSource.ECONOMY_DEBT_STATE,
                          "day",
                          day,
                          "household",
                          debtor.value(),
                          "debtContract",
                          debt.id().value(),
                          "unit",
                          debt.unit().key(),
                          "outstandingPrincipal",
                          debt.principal(),
                          "why",
                          "self-held-not-executable"));
            }
            continue;
          }
          long available =
              householdMoney
                  .getOrDefault(debtor, Map.of())
                  .getOrDefault(debt.unit().currency(), 0L);
          long paid = Math.min(debt.principal(), Math.max(0L, available));
          if (paid <= 0L) {
            if (debt.principal() > 0L) {
              DebtContractBook.markStatus(debts, debt.id(), DebtStatus.DEFAULTED);
              debtDefaultedMilli += debt.principal();
              debtDefaultedItems++;
            }
            continue;
          }
          Transfer repayment =
              ledger.mint(
                  HouseholdActors.of(debt.debtor()),
                  HouseholdActors.of(debt.creditor()),
                  location,
                  Map.of(),
                  Map.of(debt.unit().currency(), paid),
                  TransferReason.LOAN_REPAYMENT);
          applyTransfer(
              householdGoods, householdMoney, householdOfActor, repayment, issuanceJournal);
          long remaining = debt.principal() - paid;
          // ★ 不足才违约；还清 = SETTLED、本金 0（历史留痕，不从表里删）。E4c：写口收在 DebtContractBook。
          DebtContractBook.reduce(debts, debt.id(), paid);
          DebtContractBook.markStatus(
              debts, debt.id(), remaining > 0L ? DebtStatus.DEFAULTED : DebtStatus.SETTLED);
          debtPaidMilli += paid;
          if (remaining > 0L) {
            debtDefaultedMilli += remaining;
            debtDefaultedItems++;
          }
        }
      }

      // ── 4./5. 留存库存读数 + 状态与理由（只追加摘要，不改状态机的判据字段）────────────────────
      long keptGoodsMilli = 0L;
      long keptMoneyMilli = 0L;
      if (debtor != null) {
        for (long value : householdGoods.getOrDefault(debtor, Map.of()).values()) {
          keptGoodsMilli += value;
        }
        for (Long value : householdMoney.getOrDefault(debtor, Map.of()).values()) {
          keptMoneyMilli += value == null ? 0L : value;
        }
      }
      // ★★ P2-A §13.3：解析不到经济家户的聚合主体不持账 ⇒ 留存读数恒 0（不伪造经营者账）。
      OperatorCondition condition = operatorConditions.get(exit.unit());
      if (condition != null) {
        operatorConditions.put(
            exit.unit(),
            condition.withLastReason(
                exit.reason()
                    + ";disposition{laborReleasedMilli="
                    + releasedLaborMilli
                    + ",releasedAllocations="
                    + releasedAllocations
                    + ",assetSharesReturned="
                    + returnedShares
                    + ",assetQuantityReturned="
                    + returnedQuantity
                    + ",assetSharesKeptOwned="
                    + keptOwnedShares
                    + ",debtPaidMilli="
                    + debtPaidMilli
                    + ",debtDefaultedMilli="
                    + debtDefaultedMilli
                    + ",debtDefaultedItems="
                    + debtDefaultedItems
                    + ",keptGoodsMilli="
                    + keptGoodsMilli
                    + ",keptMoneyMilli="
                    + keptMoneyMilli
                    + "}"));
      }
    }
  }

  /**
   * ★★ <b>GAP-2：处置后不再由该 operator 经营的份额 → 组织引用清理</b>。
   *
   * <p>两个调用现场共用同一条事实：份额的 {@code operator} 已不是某组织的 {@code organizer}，而该组织的 {@link
   * ProductionEnterprise#assetSources()} 里可能仍留着旧引用 ⇒ revision 边界的 {@code EconomyData} 守卫会以「份额
   * operator 必须等于 organizer」fail-closed：
   *
   * <ul>
   *   <li>{@link #settleOperatorExits}：TENANCY/委托份额的 {@code operator} 改回 {@code owner}；
   *   <li>{@link DebtFirstSettlement}：M2 债务把债务人的 LAND 整块抵给债权人后，份额 {@code operator} 变成债权人，
   *       而债务人组织仍引用它。
   * </ul>
   *
   * <p>这里按“份额已不再由该 operator 经营”的事实把引用摘掉，<b>不改份额本身、不改组织身份/unit/laborSources/inputSources/
   * status</b>；同一份额若被该 operator 的多个组织引用，全部摘除；份额退主/抵债后重新成为下一期计划可见的资产，不在本方法里 强行塞给任何新组织。
   *
   * @return 被摘除的 {@code assetSources} 引用条数（跨该 operator 的全部组织累计；0 = 无需清理）
   */
  static int detachReturnedSharesFromEnterprises(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      ActorRef operator,
      Set<AssetShareId> returnedShareIds) {
    if (returnedShareIds.isEmpty()) {
      return 0;
    }
    int detachedReferences = 0;
    List<ProductionOrganizationId> enterpriseIds = new ArrayList<>(enterprises.keySet());
    enterpriseIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : enterpriseIds) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null || !enterprise.organizer().equals(operator)) {
        continue;
      }
      List<AssetShareId> kept = new ArrayList<>(enterprise.assetSources());
      int before = kept.size();
      if (!kept.removeIf(returnedShareIds::contains)) {
        continue; // 该组织没有引用这批退回份额：一字不动
      }
      detachedReferences += before - kept.size();
      enterprises.put(
          organizationId,
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              kept,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
    return detachedReferences;
  }

  /**
   * ★★★ <b>D-032 P2fix：抵地后名册 rightsStakes 的陈旧引用清理</b>。
   *
   * <p>{@code ProductionRoster.participants[].rightsStakes} 只存 {@link AssetShareId} 引用；份额被 M2
   * 抵给债权人后， 原债务人参与项里的旧引用会在 {@code HouseholdReferenceValidation.validateProductionRosters} 的
   * `rights-not-owned` 处失败（owner 已不是该参与户）。这里只对 {@code debtor} 自己的参与项摘掉这些被转走的 share id，<b>不动名册的
   * unit/plan/participant 家户/角色/计划劳动/权重，不动份额本身</b>；没有引用时返回 0。
   *
   * @return 被摘除的 rightsStakes 引用条数
   */
  static int detachRightsStakesFromRosters(
      Map<ProductionUnitId, ProductionRoster> rosters,
      HouseholdId debtor,
      Set<AssetShareId> detachedShareIds) {
    if (rosters == null || rosters.isEmpty() || detachedShareIds.isEmpty()) {
      return 0;
    }
    int detachedReferences = 0;
    List<ProductionUnitId> unitIds = new ArrayList<>(rosters.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionRoster roster = rosters.get(unitId);
      if (roster == null) {
        continue;
      }
      boolean rosterChanged = false;
      List<ProductionParticipant> participants = new ArrayList<>(roster.participants().size());
      for (ProductionParticipant participant : roster.participants()) {
        if (!participant.household().equals(debtor) || participant.rightsStakes().isEmpty()) {
          participants.add(participant);
          continue;
        }
        List<AssetShareId> kept = new ArrayList<>(participant.rightsStakes());
        int before = kept.size();
        if (!kept.removeIf(detachedShareIds::contains)) {
          participants.add(participant);
          continue;
        }
        detachedReferences += before - kept.size();
        rosterChanged = true;
        participants.add(
            new ProductionParticipant(
                participant.household(),
                participant.role(),
                participant.organizer(),
                participant.plannedLaborMilliPerDay(),
                kept,
                participant.configuredWeight()));
      }
      if (rosterChanged) {
        rosters.put(unitId, new ProductionRoster(roster.unit(), roster.plan(), participants));
      }
    }
    return detachedReferences;
  }

  // ── 周期收获与制度分配 ─────────────────────────────────────────────────────────────

  // ── P2-A §13.3：收获阶段的账户路由（unit 主体 → 家户；转账两端的家户化）────────────────────

  /** unit → 生产组织（按 unitId 唯一；同 unit 多条组织取 id 升序第一条 —— 与组织阶段的"一个 unit 一条"同侧）。 */
  static Map<ProductionUnitId, ProductionEnterprise> enterprisesByProcess(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    Map<ProductionUnitId, ProductionEnterprise> byUnit = new LinkedHashMap<>();
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : ids) {
      ProductionEnterprise enterprise = enterprises.get(id);
      if (enterprise != null) {
        enterprise.unitId().ifPresent(unitId -> byUnit.putIfAbsent(unitId, enterprise));
      }
    }
    return byUnit;
  }

  /** 旧 {@link CohortKey} 视图 → 家户（**恰一户**才登记；多于一户 = 歧义，不登记 —— {@link #requireCohortRows} 会先抛）。 */
  static Map<CohortKey, HouseholdId> viewToHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<CohortKey, HouseholdId> index = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : keys) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        continue;
      }
      CohortKey view = householdEconomy.view();
      if (ambiguous.contains(view)) {
        continue;
      }
      HouseholdId previous = index.putIfAbsent(view, household);
      if (previous != null && !previous.equals(household)) {
        index.remove(view);
        ambiguous.add(view);
      }
    }
    return index;
  }

  /** 主体的可花货币合计（逐币种；单一主体 = 它自己的钱包，集体 = 各户之和）。 */
  static Map<CurrencyId, Long> availableMoneyOf(
      HouseholdRouting.Subject subject, Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    Map<CurrencyId, Long> sums = new LinkedHashMap<>();
    for (HouseholdId household : subject.all()) {
      for (Map.Entry<CurrencyId, Long> entry :
          householdMoney.getOrDefault(household, Map.of()).entrySet()) {
        // ★ D5 并发读到的 null 值（活表被并发清空/写入的窗口）按"该币种此刻不可用"处理，fail-closed 不 NPE；
        //   正常路径的账户写入只写非 null（见 AccountSession.ActorAccount.replaceMoney）。
        if (entry.getValue() == null) {
          continue;
        }
        sums.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return sums;
  }

  /**
   * ★★ <b>W2-fix7 / D1②：主体的实物库存合计（逐商品；单一主体 = 它自己的库存，集体 = 各户之和）</b>。
   *
   * <p>★ 与 {@link #availableMoneyOf} 逐字同形、同一条口径：它喂 {@link
   * ProductionSettlement.Facts#payerStock()}（实物租腿的 三级支付链第二级），因此<b>必须是"结算前这一刻"的会话库存</b> ——
   * 调用点（{@code harvest}）在 {@code creditOutput} 之后、{@code settle} 之前取它 ⇒ 表里已含本周期刚计提的净产。
   */
  static Map<CommodityId, Long> availableGoodsOf(
      HouseholdRouting.Subject subject, Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    Map<CommodityId, Long> sums = new LinkedHashMap<>();
    for (HouseholdId household : subject.all()) {
      for (Map.Entry<CommodityId, Long> entry :
          householdGoods.getOrDefault(household, Map.of()).entrySet()) {
        // ★ 与 availableMoneyOf 同款：并发读到的 null 值按"此刻不可用"处理（fail-closed 不 NPE）。
        if (entry.getValue() == null) {
          continue;
        }
        sums.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return sums;
  }

  /** 净产入账：单一主体 ⇒ 一条计提；集体主体 ⇒ 按劳动权重分给各家家户；解析不到主体 ⇒ <b>具名缺口</b> （WARN + 跳过，不凭空造账、不静默塞给某个家户）。 */
  static void creditOutput(
      HouseholdRouting.Subject subject,
      Map<HouseholdId, Long> weights,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      ActorRef operator,
      CommodityId commodity,
      long net,
      HexCoord location,
      ProductionLedger.Accumulator ledger) {
    if (subject.isEmpty()) {
      EventLog.channel(TRACE)
          .warn(
              LogEvent.of(
                  "ACCOUNT_SUBJECT_UNRESOLVED",
                  EconomyLogSource.ECONOMY_OUTPUT_CREDIT,
                  "kind",
                  "output",
                  "operator",
                  operator,
                  "commodity",
                  commodity.value(),
                  "amount",
                  net,
                  "hex",
                  location.q() + "," + location.r(),
                  "reason",
                  "no-household-organizer-or-labor"));
      return;
    }
    for (Map.Entry<HouseholdId, Long> share :
        HouseholdRouting.apportion(subject, net, weights).entrySet()) {
      HouseholdId household = share.getKey();
      addStock(householdGoods, household, commodity, share.getValue());
      ledger.addOutputAccrual(
          new ProductionLedger.ActorEntry(
              HouseholdActors.of(household), location, commodity, share.getValue()));
    }
  }

  /**
   * ★★★ <b>D-032 C2（D2 §8.3 / §6.3）：名册方案的两层分配落账</b>。
   *
   * <p>★★ <b>唯一算术来源</b>：{@code production.contract.RoleDistribution.allocate}（角色池 + 角色内最大余数法 +
   * 份额/整数余数规则）；economy 在这里只做两件事：把当刻事实（计划劳动/权益权重/净池）装进去，把结果逐户落账。 <b>不</b>复制任何角色池/最大余数公式。
   *
   * <p>★ <b>不双记</b>：净产直接按份额计入 participant 家户库存（不先 credit 给 operator 再转出）；未分配量 （{@code
   * unallocated}，含零权重角色池与余数）全部归 organizer residual，并具名 {@code ROLE_SHARE_UNALLOCATED_RESIDUAL} 记入
   * organizer 的流水。
   *
   * <p>★ <b>守恒</b>：{@code RoleDistributionResult} 构造期已复核逐商品 {@code Σshares + unallocated ==
   * poolTotal}； 本方法再按 gross/net 口径与 ledger 对齐（loss 已在 harvest 记入 ΣLoss）。
   */
  static void creditRosterNetPool(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      io.mosire.simos.economy.model.ProductionRoster roster,
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<CommodityId, Long> netByCommodity,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(roster, "roster");
    HexCoord location = hexOfIndustry(industry.id());
    List<io.mosire.simos.production.contract.RoleDistributionParticipant> participants =
        new ArrayList<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> rightsWeights =
        new LinkedHashMap<>();
    for (io.mosire.simos.economy.model.ProductionParticipant participant : roster.participants()) {
      participants.add(
          new io.mosire.simos.production.contract.RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      long rights = 0L;
      for (AssetShareId share : participant.rightsStakes()) {
        OwnershipStake stake = assetShares.get(share);
        if (stake == null) {
          throw new IllegalStateException(
              "BY_RIGHTS 引用的资产份额不存在（状态已被改坏）: unit="
                  + unit.id().value()
                  + " share="
                  + share.value());
        }
        rights = Math.addExact(rights, stake.quantity());
      }
      if (rights > 0L) {
        rightsWeights.put(
            new io.mosire.simos.production.contract.ProductionParticipantKey(
                participant.household(), participant.role()),
            rights);
      }
    }
    io.mosire.simos.production.contract.RoleDistributionResult distribution =
        io.mosire.simos.production.contract.RoleDistribution.allocate(
            new io.mosire.simos.production.contract.RoleDistributionRequest(
                industry, participants, cycleLaborByParticipant, rightsWeights, netByCommodity));
    for (Map.Entry<
            io.mosire.simos.production.contract.ProductionParticipantKey, Map<CommodityId, Long>>
        entry : distribution.shares().entrySet()) {
      HouseholdId household = entry.getKey().household();
      for (Map.Entry<CommodityId, Long> share : entry.getValue().entrySet()) {
        addStock(householdGoods, household, share.getKey(), share.getValue());
        addGoods(income, household, share.getKey(), share.getValue());
        ledger.addOutputAccrual(
            new ProductionLedger.ActorEntry(
                HouseholdActors.of(household), location, share.getKey(), share.getValue()));
      }
    }
    HouseholdId residualOwner = roster.organizerHousehold();
    for (Map.Entry<CommodityId, Long> residual : distribution.unallocated().entrySet()) {
      addStock(householdGoods, residualOwner, residual.getKey(), residual.getValue());
      addGoods(income, residualOwner, residual.getKey(), residual.getValue());
      ledger.addOutputAccrual(
          new ProductionLedger.ActorEntry(
              HouseholdActors.of(residualOwner), location, residual.getKey(), residual.getValue()));
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "ROLE_SHARE_UNALLOCATED_RESIDUAL",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "unit",
                  unit.id().value(),
                  "commodity",
                  residual.getKey().value(),
                  "amount",
                  residual.getValue(),
                  "organizer",
                  residualOwner.value()));
    }
  }

  /** 一个已解析的转账端：主体 + 分配权重（权重只对 collective 有意义）。 */
  record ResolvedAccountSubject(HouseholdRouting.Subject subject, Map<HouseholdId, Long> weights) {}

  /** 结转一个 actor（旧 cohort 视图 / 组织者 actor / 现存家户 actor）→ 家户主体；解析不到 ⇒ 具名抛。 */
  static ResolvedAccountSubject resolveAccountCounterparty(
      ActorRef actor,
      ProductionProcess unit,
      HouseholdRouting.Subject unitSubject,
      Map<HouseholdId, Long> unitWeights,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      SettlementIndex index) {
    Optional<HouseholdId> direct =
        HouseholdRouting.householdOfActorOrNull(actor, householdEconomies);
    if (direct.isPresent()) {
      HouseholdId household = direct.get();
      return new ResolvedAccountSubject(
          HouseholdRouting.Subject.single(household), Map.of(household, 1L));
    }
    if (actor.equals(unit.operator())) {
      return new ResolvedAccountSubject(unitSubject, unitWeights);
    }
    // 组织者 actor（组织记录里写的是它的 actor）：组织者本人必须是家户 actor。
    for (ProductionEnterprise enterprise : enterpriseByProcess.values()) {
      if (enterprise.organizer().equals(actor)) {
        Optional<HouseholdId> organizer =
            HouseholdRouting.householdOfActorOrNull(enterprise.organizer(), householdEconomies);
        if (organizer.isPresent()) {
          return new ResolvedAccountSubject(
              HouseholdRouting.Subject.single(organizer.get()), Map.of(organizer.get(), 1L));
        }
      }
    }
    // 旧 ToCohort 规则：{@code HouseholdActors.of(CohortKey)} 的三段 actor id → 视图 → 恰一个家户。
    if (actor.kind() == io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
      try {
        CohortKey view = HouseholdActors.cohortOf(actor);
        HouseholdId household = viewIndex.get(view);
        if (household != null) {
          return new ResolvedAccountSubject(
              HouseholdRouting.Subject.single(household), Map.of(household, 1L));
        }
      } catch (RuntimeException notLegacyCohortActor) {
        // 落到后面的 unit 关联解析（新档家户 actor 的 id 不是三段旧格式）。
      }
    }
    // 其他 unit 的经营者 actor：用该 unit 的经济家户 / 劳动家户解析。
    for (ProductionUnitId related : index.unitsRelatedTo(actor)) {
      Optional<HouseholdId> single = index.economicHouseholdOf(related);
      if (single.isPresent()) {
        return new ResolvedAccountSubject(
            HouseholdRouting.Subject.single(single.get()), Map.of(single.get(), 1L));
      }
      List<HouseholdId> collective = index.householdsOf(related);
      if (!collective.isEmpty()) {
        return new ResolvedAccountSubject(
            HouseholdRouting.Subject.collective(collective),
            HouseholdRouting.weightsOf(related, index, householdEconomies));
      }
    }
    throw new IllegalStateException(
        "关系结算的一端无法解析到家户账户（账户主体只有家户；庄园/作坊/商号必须能解析到组织者/经营者家户）："
            + "unit="
            + unit.id().value()
            + " actor="
            + actor
            + "（单位主体="
            + unitSubject
            + "）");
  }

  /** 支付能力读口（按家户取可用量；payerShares 的瀑布式封顶用）。 */
  interface Availability {
    long of(HouseholdId household);
  }

  /** 付方份额：按权重比例的目标额先取 min(目标, 可用)，不足由**同主体其余成员**按序补齐； 仍不足 ⇒ 具名抛（调用方应已用主体合计封顶，走到这里说明状态已坏）。 */
  static Map<HouseholdId, Long> payerShares(
      HouseholdRouting.Subject subject,
      long total,
      Map<HouseholdId, Long> weights,
      Availability available) {
    Map<HouseholdId, Long> taken = new LinkedHashMap<>();
    if (total <= 0L) {
      return taken;
    }
    Map<HouseholdId, Long> targets = HouseholdRouting.apportion(subject, total, weights);
    long shortfall = 0L;
    for (Map.Entry<HouseholdId, Long> target : targets.entrySet()) {
      long room = Math.max(0L, available.of(target.getKey()));
      long take = Math.min(target.getValue(), room);
      if (take > 0L) {
        taken.put(target.getKey(), take);
      }
      shortfall = Math.addExact(shortfall, target.getValue() - take);
    }
    if (shortfall > 0L) {
      for (HouseholdId household : subject.all()) {
        if (shortfall <= 0L) {
          break;
        }
        long already = taken.getOrDefault(household, 0L);
        long room = Math.max(0L, available.of(household) - already);
        long extra = Math.min(shortfall, room);
        if (extra > 0L) {
          taken.merge(household, extra, Math::addExact);
          shortfall -= extra;
        }
      }
    }
    if (shortfall > 0L) {
      throw new IllegalStateException(
          "关系结算的付方主体可付量不足（主体合计应已封顶；走到这里说明状态已坏）：subject="
              + subject
              + " total="
              + total
              + " shortfall="
              + shortfall);
    }
    return taken;
  }

  /**
   * ★★ <b>把 {@link ProductionSettlement} 铸的一条转移路由到家户</b>：两端各自解析成主体后按权重拆腿， 每条腿经 {@code ledger.mint}
   * 落账并收进 {@code sink}；返回第一条腿（满足 {@code TransferMint} 的返回契约）。
   */
  static Transfer routeRelationTransfer(
      ProductionProcess unit,
      HouseholdRouting.Subject unitSubject,
      Map<HouseholdId, Long> unitWeights,
      ActorRef from,
      ActorRef to,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      TransferReason reason,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      SettlementIndex index,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      List<Transfer> sink,
      Map<HouseholdId, Map<CommodityId, Long>> goodsDebited,
      Map<HouseholdId, Map<CurrencyId, Long>> moneyDebited) {
    ResolvedAccountSubject payer =
        resolveAccountCounterparty(
            from,
            unit,
            unitSubject,
            unitWeights,
            householdEconomies,
            enterpriseByProcess,
            viewIndex,
            index);
    ResolvedAccountSubject payee =
        resolveAccountCounterparty(
            to,
            unit,
            unitSubject,
            unitWeights,
            householdEconomies,
            enterpriseByProcess,
            viewIndex,
            index);
    List<Transfer> legs = new ArrayList<>();
    for (Map.Entry<CommodityId, Long> leg : goods.entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      Map<HouseholdId, Long> payerParts =
          payerShares(
              payer.subject(),
              amount,
              payer.weights(),
              household ->
                  stockOf(householdGoods, household, leg.getKey())
                      - goodsDebited
                          .getOrDefault(household, Map.of())
                          .getOrDefault(leg.getKey(), 0L));
      Map<HouseholdId, Long> payeeParts =
          HouseholdRouting.apportion(payee.subject(), amount, payee.weights());
      emitLegs(
          payerParts,
          payeeParts,
          location,
          Map.of(leg.getKey(), 1L),
          Map.of(),
          reason,
          ledger,
          legs);
    }
    for (Map.Entry<CurrencyId, Long> leg : money.entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      Map<HouseholdId, Long> payerParts =
          payerShares(
              payer.subject(),
              amount,
              payer.weights(),
              household ->
                  moneyOf(householdMoney, household, leg.getKey())
                      - moneyDebited
                          .getOrDefault(household, Map.of())
                          .getOrDefault(leg.getKey(), 0L));
      Map<HouseholdId, Long> payeeParts =
          HouseholdRouting.apportion(payee.subject(), amount, payee.weights());
      emitLegs(
          payerParts,
          payeeParts,
          location,
          Map.of(),
          Map.of(leg.getKey(), 1L),
          reason,
          ledger,
          legs);
    }
    if (legs.isEmpty()) {
      throw new IllegalStateException(
          "关系结算铸了一条空转移（两端都没有可分配的量）：unit=" + unit.id().value() + " from=" + from + " to=" + to);
    }
    sink.addAll(legs);
    for (Transfer leg : legs) {
      HouseholdId payerHousehold = HouseholdRouting.requireHouseholdOf(leg.from());
      for (Map.Entry<CommodityId, Long> entry : leg.goods().entrySet()) {
        goodsDebited
            .computeIfAbsent(payerHousehold, ignored -> new LinkedHashMap<>())
            .merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> entry : leg.money().entrySet()) {
        moneyDebited
            .computeIfAbsent(payerHousehold, ignored -> new LinkedHashMap<>())
            .merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }
    return legs.get(0);
  }

  /** 付方/受方份额的笛卡尔拆腿（贪心：逐付方把它的量按受方剩余额填满；金额按份额乘数缩放）。 */
  static void emitLegs(
      Map<HouseholdId, Long> payerParts,
      Map<HouseholdId, Long> payeeParts,
      HexCoord location,
      Map<CommodityId, Long> goodsUnit,
      Map<CurrencyId, Long> moneyUnit,
      TransferReason reason,
      ProductionLedger.Accumulator ledger,
      List<Transfer> sink) {
    Map<HouseholdId, Long> payeeRemaining = new LinkedHashMap<>(payeeParts);
    for (Map.Entry<HouseholdId, Long> payer : payerParts.entrySet()) {
      long left = payer.getValue();
      for (Map.Entry<HouseholdId, Long> payee : payeeRemaining.entrySet()) {
        if (left <= 0L) {
          break;
        }
        long remaining = payee.getValue();
        if (remaining <= 0L) {
          continue;
        }
        long take = Math.min(left, remaining);
        Map<CommodityId, Long> goods = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> unitLeg : goodsUnit.entrySet()) {
          goods.put(unitLeg.getKey(), Math.multiplyExact(unitLeg.getValue(), take));
        }
        Map<CurrencyId, Long> moneyMap = new LinkedHashMap<>();
        for (Map.Entry<CurrencyId, Long> unitLeg : moneyUnit.entrySet()) {
          moneyMap.put(unitLeg.getKey(), Math.multiplyExact(unitLeg.getValue(), take));
        }
        sink.add(
            ledger.mint(
                HouseholdActors.of(payer.getKey()),
                HouseholdActors.of(payee.getKey()),
                location,
                goods,
                moneyMap,
                reason));
        payee.setValue(remaining - take);
        left -= take;
      }
      if (left > 0L) {
        throw new IllegalStateException(
            "关系转移拆腿后付方仍有剩余（受方份额不足；状态已坏）：payer=" + payer.getKey() + " left=" + left);
      }
    }
  }
}
