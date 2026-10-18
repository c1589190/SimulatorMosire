package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.EconomySettlement.MILLI_PER_GRAIN;
import static io.mosire.simos.economy.time.LaborSettlement.requireCohortRows;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.availableGoodsOf;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.availableMoneyOf;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.creditOutput;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.creditRosterNetPool;
import static io.mosire.simos.economy.time.ProductionOrganizationSettlement.routeRelationTransfer;
import static io.mosire.simos.economy.time.SettlementLogSupport.logInKindRentChain;
import static io.mosire.simos.economy.time.SettlementMath.addGoods;
import static io.mosire.simos.economy.time.SettlementMath.hexOfIndustry;
import static io.mosire.simos.economy.time.SettlementMath.mergeGoodsInto;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRecipe;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ProductionRunSettlement {

  /**
   * 收获阶段的一条工作单元（关账前的 unit/模板快照 + {@link ProductionEfficiencyBook} 求好的规模 + 该产业的产出数量覆盖 + 所在格 + 原遍历序）。
   *
   * <p>★ Z2 起 {@code scale} 不再由本阶段现算：周期末求值（§6.2 ①~⑤）在日循环里完成（那里是效率状态工作副本的唯一写点， 且要 fail-closed
   * 校验余数有效域）；本记录把结果带进按 hex 并行的收获阶段。
   */
  record HarvestWork(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
      HexCoord location,
      int order,
      // ── ★★★ D-032 B3（I9 / §13.13.7 第 5 条）：**回执里的实际出工家户与各自贡献** ──────────────────
      //   `laborByHousehold` = 本周期对该 unit 实际出过工的家户 → 实际劳动（千分劳动·日，逐 tick 累计）。
      //   `cycleInputUsed` = 本周期**实际扣到**的投入（毫单位，逐商品）。
      //   ★ 为什么必须由回执带进来（而不是收获阶段现算）：旧口径用
      //     `laborOfCohort(householdEconomies, location, cycleDays)` = "该格**所有**家户行 × 每日劳动 × 周期天数"
      //     —— 那是"住在这一格"的投影，不是"实际出工"的事实：没出工的户也进分母、最后一天出工的户被当成整周期
      //     出工。I9 要的是回执里的实际贡献（`ProductionReceipt.cycleContributions` / `consumedInputs`）。
      Map<HouseholdId, Long> laborByHousehold,
      Map<CommodityId, Long> cycleInputUsed,
      // ── ★★★ D-032 C2：逐 (家户 × 角色) 周期劳动 + 该 unit 的名册（无 rosters ⇒ 旧路径）────────────
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      io.mosire.simos.economy.model.ProductionRoster roster) {}

  /** 收获阶段一个分区的产出（意向 + 本地实物入账 + 本地账本）。 */
  record HarvestPartition(
      List<OrderedAccountIntent> intents,
      Map<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger) {}

  /**
   * ★★ <b>收获/关系分账按 hex 并行</b>（{@link #harvest} 的并行外壳）。
   *
   * <p>同一 hex 内 farm/weave/craft 共用家户账与关系表 ⇒ 必须同分区串行（按关账原遍历序）；不同 hex 无共享账户。 转移在分区账本里铸号（{@code
   * tr-<day>-p<partition>-<seq>}），协调器按分区序吸收 ⇒ 1/4/8 同 id、同序。
   */
  static void harvestPartitioned(
      List<HarvestWork> works,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      SettlementIndex index,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      Map<ActorRef, HouseholdId> householdOfActor,
      AccountSession accounts,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal,
      long day,
      EconomyParallelism parallelism,
      Map<AssetShareId, OwnershipStake> assetShares) {
    if (works.isEmpty()) {
      return;
    }
    TreeMap<String, List<HarvestWork>> byHex = new TreeMap<>();
    for (HarvestWork work : works) {
      byHex.computeIfAbsent(work.location().toString(), ignored -> new ArrayList<>()).add(work);
    }
    for (List<HarvestWork> members : byHex.values()) {
      members.sort(Comparator.comparingInt(HarvestWork::order));
    }
    AccountSnapshot snapshot = accounts.snapshot();
    PartitionPlan plan =
        PartitionPlan.of(SettlementStage.HARVEST, byHex.keySet(), parallelism.partitionCount());
    List<HarvestPartition> partitions =
        SettlementExecutor.execute(
            plan,
            partition -> {
              AccountIntentBuffer buffer =
                  AccountIntentBuffer.onHexPartition(
                      snapshot,
                      SettlementStage.HARVEST,
                      partition.partitionIndex(),
                      plan.partitionCount());
              BufferedAccountTables tables = new BufferedAccountTables(snapshot, buffer);
              LinkedHashMap<HouseholdId, Map<CommodityId, Long>> localIncome =
                  new LinkedHashMap<>();
              ProductionLedger.Accumulator partitionLedger =
                  new ProductionLedger.Accumulator(day, partition.partitionIndex());
              for (String hex : partition.canonicalKeys()) {
                for (HarvestWork work : byHex.get(hex)) {
                  harvest(
                      day,
                      work.unit(),
                      work.industry(),
                      work.scale(),
                      work.outputQuantityOverrides(),
                      work.laborByHousehold(),
                      work.cycleInputUsed(),
                      work.cycleLaborByParticipant(),
                      work.roster(),
                      assetShares,
                      householdEconomies,
                      index,
                      localIncome,
                      tables.householdGoods,
                      tables.householdMoney,
                      relations,
                      enterpriseByProcess,
                      viewIndex,
                      householdOfActor,
                      partitionLedger,
                      issuanceJournal);
                }
              }
              return new HarvestPartition(buffer.drainIntents(), localIncome, partitionLedger);
            },
            parallelism.poolOrNull());
    List<OrderedAccountIntent> intents = new ArrayList<>();
    for (HarvestPartition partition : partitions) {
      intents.addAll(partition.intents());
    }
    accounts.commit(intents);
    for (HarvestPartition partition : partitions) {
      mergeGoodsInto(income, partition.income());
      // ★★ M7：同投入阶段 —— 列表序由协调器的分区遍历序决定；跨代码态比较按业务键排序后比较集合（见投入阶段的注释）。
      ledger.absorb(partition.ledger());
    }
  }

  /**
   * ★★★ <b>B1（D-032 §4.3 数据流第 5 段）：把本生产日交给 production 执行 —— economy 不再写实例状态</b>。
   *
   * <p>★★ <b>为什么这是 economy 侧"最终投入"的落点</b>：本方法在<b>劳动分配</b>（{@code LaborQueueSettlement} / {@code
   * reallocateLabor}）与<b>现扣周期投入</b>（{@code drawCycleInputs}）都落地之后调用，所以三样输入 都是"已经决定好的"：
   *
   * <ul>
   *   <li><b>劳动贡献</b>：逐 {@code HouseholdLaborCommitment}（{@code activity == unit id}）按家户汇总 ——
   *       <b>不是</b>一个总数。（I9：回执必须保留各家户实际贡献；经验也按<b>实际贡献</b>累加。）
   *   <li><b>资产占用</b>：{@link AssetOccupancyBudget} 给出的该 unit 资产分配（与 economy 自己算产能规模读的是同一份表 ——
   *       预计与实际同源）。
   *   <li><b>原料投入</b>：现扣阶段实际扣到的量（读当刻候选态的 {@code cycleInputUsedMilli}）。
   * </ul>
   *
   * <p>★★ <b>失败语义</b>（§13.2 第 1 条④）：业务拒绝由 {@code executeDay} 逐项具名放进结果；<b>契约故障</b> （{@code
   * ProductionContractFault}）<b>不</b>在这里捕获 —— 它一路冒泡到提案层，让整个 Advance 被拒 （禁止在污染态上收尾）。
   *
   * @return 本生产日的结果（回执 / 拒绝 / 关账规模）
   */
  static io.mosire.simos.production.contract.ProductionDayResult driveProductionDay(
      EconomySession session,
      long day,
      SettlementIndex settlementIndex,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions) {
    return driveProductionDay(
        session,
        day,
        settlementIndex,
        laborCommitments,
        operatorConditions,
        ProductionLandFilter.none());
  }

  /**
   * ★★★ <b>P3b：M7 执行入口（带“本轮新取得土地排除集”）</b>。
   *
   * <p>★ M7 的 {@code ProductionDayRequest.Step.assetOccupancy} 只取 {@link SettlementIndex} 里那份<b>已在
   * scope 供给匹配阶段排除新土地</b>的 D4 allocation；本入口另加 fail-closed 守卫：只要本批 {@code grantsToWrite}
   * 出现新土地授予，即视为契约故障（ERROR，不降级）。
   */
  static io.mosire.simos.production.contract.ProductionDayResult driveProductionDay(
      EconomySession session,
      long day,
      SettlementIndex settlementIndex,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      Set<AssetShareId> landAcquiredThisRound) {
    Objects.requireNonNull(
        landAcquiredThisRound, "landAcquiredThisRound 不得为 null（空集用 ProductionLandFilter.none()）");
    Set<AssetShareId> excludedLand = ProductionLandFilter.normalize(landAcquiredThisRound);
    io.mosire.simos.production.contract.ProductionRuntime runtime = session.productionRuntime();
    // ★★ B3：`runtime` 由 `EconomySession` 的构造器保证非 null（Objects.requireNonNull）⇒ 旧的
    //   "本会话没有运行时" 分支已不可达、随之删除 —— fail-closed 的落点前移到**装配边界**（构造会话时当场抛），
    //   而不是等到日推进中途才发现。
    Objects.requireNonNull(runtime, "runtime（EconomySession 保证非 null；此处为防御性读取）");
    // ★★★ B8（缺陷 DEF-T2b-2 的同源一处）：步表的键集必须读**当刻**权威候选态，不得用日结算入口抓的那份快照。
    //   本日更早的阶段（进入执行 / 自动组织 / 模式变迁 / 迁移搬迁）会经契约口 `createInstance` **新建实例**
    //   —— 那些实例当刻就在权威候选态里（`session.sheet().units()` 直读 runtime），而入口快照看不见它们。
    Map<ProductionUnitId, ProductionProcess> stepUnits = session.sheet().units();
    if (stepUnits.isEmpty()) {
      return io.mosire.simos.production.contract.ProductionDayResult.empty();
    }
    // ① 按名册/配额表把实际劳动贡献汇总成**逐 (家户 × 角色)** 列表（D2 §6.1/§6.2）。
    //   ★ 角色只从 `HouseholdLaborCommitment.productionRole()` 读（D2 §5.6 的唯一权威）；
    //     漏角色 = 多角色同户静默合并，故这里 fail-closed。
    //   ★★★ W5-F：同户可有多个 PeopleLot（commitment id 含 group），故同一个 `(activity, household,
    //     productionRole)` 可能有多行合法承诺。生产契约的贡献键只有 `(household, role)` —— 必须先在
    //     本适配层按该键稳定聚合（首见序 = 输出序），否则合法多 lot 户会被 Step 构造期当成重复键拒掉。
    Map<String, LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>>
        aggregatedByUnit = new LinkedHashMap<>();
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      // ★★★ W5-A（缺陷：GOV_SERVICE 被当成生产承诺）：本驱动只接 `LaborCommitmentKind.PRODUCTION`。
      //   `GOV_SERVICE` 按契约**禁止** productionRole（见 HouseholdLaborCommitment 的 kind 注），也不喂任何生产
      //   unit ⇒ 必须在角色校验与生产提交之前跳过：既不抛，也不进 contributionsByUnit。
      //   ★ 本判断**只**收窄本驱动的输入面：GOV_SERVICE 的配额/守恒/政府逻辑不在本方法，逐字不受影响。
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        continue;
      }
      long amount = commitment.laborMilli();
      if (amount <= 0L) {
        continue;
      }
      if (commitment.productionRole().isEmpty()) {
        throw new IllegalStateException(
            "生产承诺缺 productionRole（D2 §5.6 不变量被绕开；状态已被改坏）: id="
                + commitment.id().value()
                + " activity="
                + commitment.activity());
      }
      io.mosire.simos.production.contract.ProductionParticipantKey participantKey =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              commitment.household(), commitment.productionRole().get());
      LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          byParticipant =
              aggregatedByUnit.computeIfAbsent(
                  commitment.activity(), ignored -> new LinkedHashMap<>());
      Long previous = byParticipant.get(participantKey);
      byParticipant.put(
          participantKey, previous == null ? amount : Math.addExact(previous, amount));
    }
    Map<String, List<io.mosire.simos.production.contract.ParticipantContribution>>
        contributionsByUnit = new LinkedHashMap<>();
    for (Map.Entry<
            String,
            LinkedHashMap<io.mosire.simos.production.contract.ProductionParticipantKey, Long>>
        unitEntry : aggregatedByUnit.entrySet()) {
      List<io.mosire.simos.production.contract.ParticipantContribution> contributions =
          new ArrayList<>();
      for (Map.Entry<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          participantEntry : unitEntry.getValue().entrySet()) {
        contributions.add(
            new io.mosire.simos.production.contract.ParticipantContribution(
                participantEntry.getKey().household(),
                participantEntry.getKey().role(),
                participantEntry.getValue()));
      }
      contributionsByUnit.put(unitEntry.getKey(), contributions);
    }
    // ② 按**稳定序**（实例 id 升序）建步表 —— 序是内容的纯函数（引擎不重排）。
    List<ProductionUnitId> ordered = new ArrayList<>(stepUnits.keySet());
    ordered.sort(Comparator.comparing(ProductionUnitId::value));
    Map<ProductionUnitId, io.mosire.simos.economy.model.ProductionRoster> rosters =
        session.sheet().productionRostersOrBase();
    Map<HouseholdId, io.mosire.simos.economy.model.HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomiesOrBase();
    List<io.mosire.simos.production.contract.ProductionDayRequest.Step> steps = new ArrayList<>();
    Map<ProductionUnitId, String> assetScopeRejections = new LinkedHashMap<>();
    java.util.Optional<AssetOccupancyBudget> d4Budget = settlementIndex.d4AssetOccupancyBudget();
    // ★★★ P3b：M7 只执行过滤后的 allocation —— 若本批 grant 里出现“本轮新土地”，是契约故障（ERROR 不降级）。
    if (d4Budget.isPresent()) {
      ProductionLandFilter.requireNoGrantOnExcludedLand(
          day, d4Budget.get(), session.sheet().assetShares(), excludedLand);
    }
    for (ProductionUnitId id : ordered) {
      ProductionProcess unit = stepUnits.get(id);
      // ★★★ D4-Ib2a：预计/排队/执行只认同一份 D4 占用预算；预算缺失 ⇒ 具名不可用，绝不回退旧整池扫描。
      if (d4Budget.isEmpty()) {
        assetScopeRejections.put(id, "asset-occupancy-unavailable");
        continue;
      }
      java.util.Optional<String> assetRejection = d4Budget.get().rejectionOf(id);
      if (assetRejection.isPresent()) {
        assetScopeRejections.put(id, assetRejection.get());
        continue;
      }
      IndustryDefinitionView industry =
          session.sheet().definitions().industry(unit.industry()).orElse(null);
      if (industry == null) {
        // ★ 实例引用悬空 = 契约故障（不是业务拒绝）：本域状态自相矛盾，必须拒绝整个候选提交（I15）。
        //   这里当场抛，让 {@code executeDay} 的同一条口径在 economy 侧也生效（不静默跳过这一格）。
        throw new IllegalStateException(
            "生产实例引用的产业定义不存在（production 当刻权威；状态已被改坏）: unit="
                + id.value()
                + " plan="
                + unit.industry().value());
      }
      io.mosire.simos.economy.model.ProductionRoster roster = rosters.get(id);
      List<io.mosire.simos.production.contract.ParticipantContribution> contributions =
          contributionsByUnit.getOrDefault(id.value(), List.of());
      if (roster != null) {
        // ★★ D2-I17：名册方案的 `ProductionRules.rules` 必须为空（旧 CompensationRule 产出分配禁用）。
        //   双分 = 契约故障，必须在任何写入之前拒绝整个候选提交。
        io.mosire.simos.economy.api.relation.ProductionRules relation =
            session.sheet().relationsOrBase().get(id);
        if (relation != null && !relation.rules().isEmpty()) {
          throw new IllegalStateException(
              "legacy-compensation-rules-present: 名册方案不得同时保留旧补偿规则（D2-I17）: unit="
                  + id.value()
                  + " rules="
                  + relation.rules().size());
        }
        // ★★ 名册与名册内角色的轻量执行前重查（D2 §7.3：execute 前查；完整角色资格由 production 执行面重查）。
        for (io.mosire.simos.production.contract.ParticipantContribution contribution :
            contributions) {
          if (roster.participant(contribution.household(), contribution.role()).isEmpty()) {
            throw new IllegalStateException(
                "role-unknown: 劳动承诺的角色不在名册里（跨切片不一致）: unit="
                    + id.value()
                    + " household="
                    + contribution.household().value()
                    + " role="
                    + contribution.role().value());
          }
        }
      }
      // ★★ 执行入参：逐参与户属性事实（属性桥；读不到 ⇒ 不填 EMPTY 冒充）+ 逐户劳动预算。
      java.util.Set<HouseholdId> participantHouseholds = new java.util.LinkedHashSet<>();
      if (roster != null) {
        participantHouseholds.addAll(roster.households());
        participantHouseholds.add(roster.organizerHousehold());
      }
      for (io.mosire.simos.production.contract.ParticipantContribution contribution :
          contributions) {
        participantHouseholds.add(contribution.household());
      }
      Map<HouseholdId, io.mosire.simos.production.contract.ExternalAttributeFacts>
          participantAttributes = new LinkedHashMap<>();
      Map<HouseholdId, Long> householdLaborBudgetMilli = new LinkedHashMap<>();
      Map<HouseholdId, Long> managementLaborMilli = new LinkedHashMap<>();
      for (HouseholdId household : participantHouseholds) {
        io.mosire.simos.economy.model.HouseholdEconomy row = householdEconomies.get(household);
        if (row == null) {
          throw new IllegalStateException(
              "参与户不在 economy 行表里（跨切片不一致）: unit=" + id.value() + " household=" + household.value());
        }
        session
            .participantAttributeLookup()
            .factsOf(household)
            .ifPresent(facts -> participantAttributes.put(household, facts));
        householdLaborBudgetMilli.put(household, row.laborMilli());
      }
      // ★★ 管理劳动：C2 没有管理劳动来源。countsManagement 角色缺该项 ⇒ 具名业务拒绝（不按 0 静默）。
      if (roster != null) {
        for (io.mosire.simos.economy.model.ProductionParticipant participant :
            roster.participants()) {
          boolean countsManagement =
              industry.roles().stream()
                  .filter(definition -> definition.id().equals(participant.role()))
                  .findFirst()
                  .flatMap(definition -> definition.experienceGrowth())
                  .map(io.mosire.simos.production.contract.ExperienceGrowth::countsManagement)
                  .orElse(false);
          if (countsManagement) {
            assetScopeRejections.put(
                id,
                "management-labor-missing:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value());
            break;
          }
        }
        if (assetScopeRejections.containsKey(id)) {
          continue;
        }
      }
      // ★★★ D4-Ib2a：执行请求写入的占用 = 同一份 budget 的 allocations（不再由本方法重算，
      //   不先给整池、不伪造等分）。空 Map 是合法零分配；有拒绝的 unit 已在上面短路。
      Map<io.mosire.simos.actor.api.asset.AssetKind, Long> assetOccupancy =
          d4Budget.get().allocationOf(id);
      Map<CommodityId, Long> finalInputs = session.drawnProductionInputsOf(id);
      // ★★ 计划级属性主体 = organizer（D2 §6.2）：有属性桥读数才填，读不到时仍传 EMPTY 作为
      //   `attributes`（production 的计划级条件会因缺属性 fail-closed）；participantAttributes 只装权威读数。
      io.mosire.simos.production.contract.ExternalAttributeFacts planAttributes =
          roster == null
              ? io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY
              : participantAttributes.getOrDefault(
                  roster.organizerHousehold(),
                  io.mosire.simos.production.contract.ExternalAttributeFacts.EMPTY);
      // ★★ grantedScale 的口径 = **基准规模（投入侧）上限**（§13.8 裁定一）。
      //   本批不额外设上限：economy 的"统一预算"已经落在**实际投入**上（劳动配额 + 实扣原料 + 实际占用），
      //   再叠一个授予上限只会变成第二份口径；计划规模（缩产/停业）的作用点在劳动需求与投入需求那一侧，
      //   那两处已经在分配前生效。⇒ 给 Long.MAX_VALUE（"不额外夹"），分配结果本身就是上限。
      steps.add(
          new io.mosire.simos.production.contract.ProductionDayRequest.Step(
              java.util.Optional.of(id),
              java.util.Optional.empty(),
              Long.MAX_VALUE,
              contributions,
              assetOccupancy,
              finalInputs,
              planAttributes,
              participantAttributes,
              householdLaborBudgetMilli,
              managementLaborMilli));
    }
    io.mosire.simos.production.contract.ProductionDayResult productionDay =
        steps.isEmpty()
            ? io.mosire.simos.production.contract.ProductionDayResult.empty()
            : runtime.executeDay(
                new io.mosire.simos.production.contract.ProductionDayRequest(steps));
    // ★★★ D4-Ib2a：同一 ChangeSet 写 grant/release；被业务拒绝（economy 预检或 production 回执）的 unit
    //   不留下新 grant，释放行照常回填。写工作副本 = 随后 session.build() 的唯一 ChangeSet。
    java.util.Set<ProductionUnitId> rejectedUnits =
        new LinkedHashSet<>(assetScopeRejections.keySet());
    rejectedUnits.addAll(productionDay.rejections().keySet());
    if (d4Budget.isPresent()) {
      applyAssetOccupancyBudget(session, d4Budget.get(), rejectedUnits);
    }
    if (assetScopeRejections.isEmpty()) {
      return productionDay;
    }
    // ★★ R2：业务拒绝与 production 自己的业务拒绝合并，键序仍按 unit id 稳定序；回执/关账表原样保留。
    Map<ProductionUnitId, String> mergedRejections = new LinkedHashMap<>();
    for (ProductionUnitId id : ordered) {
      String assetReason = assetScopeRejections.get(id);
      if (assetReason != null) {
        mergedRejections.put(id, assetReason);
        continue;
      }
      String engineReason = productionDay.rejections().get(id);
      if (engineReason != null) {
        mergedRejections.put(id, engineReason);
      }
    }
    return new io.mosire.simos.production.contract.ProductionDayResult(
        productionDay.receipts(),
        mergedRejections,
        productionDay.closedUnitScales(),
        productionDay.closedGrossOutput(),
        productionDay.closedMintCandidates());
  }

  /** ★★★ D4-Ib2a：把同一份预算的 grant/release 写入 economy 工作副本（最终进同一 ChangeSet）。 */
  static void applyAssetOccupancyBudget(
      EconomySession session,
      AssetOccupancyBudget budget,
      java.util.Set<ProductionUnitId> rejectedUnits) {
    java.util.Objects.requireNonNull(session, "session");
    java.util.Objects.requireNonNull(budget, "budget");
    java.util.Objects.requireNonNull(rejectedUnits, "rejectedUnits");
    java.util.LinkedHashMap<
            io.mosire.simos.economy.model.AssetOccupancyGrantId,
            io.mosire.simos.economy.model.AssetOccupancyGrant>
        grants = session.sheet().assetOccupancies();
    for (io.mosire.simos.economy.model.AssetOccupancyGrant release : budget.grantsToRelease()) {
      grants.put(release.id(), release);
    }
    for (io.mosire.simos.economy.model.AssetOccupancyGrant grant : budget.grantsToWrite()) {
      if (!rejectedUnits.contains(grant.unitId())) {
        grants.put(grant.id(), grant);
      }
    }
  }

  /**
   * ★★ <b>周期末的产出（§四 周期结算 1~5；税明确不做）</b>—— T4 起**产出不再写进阶层行**（R5 ②）。
   *
   * <p>★★ <b>本方法的形状（一步都不能少）</b>：
   *
   * <pre>
   * ① 规模 = {@link ProductionEfficiencyBook} 在关账处按 §6.2 求好的 scale（最紧约束 ④ + 修正乘算 ⑤；含四个余数结转）
   * ② 逐商品：数量_j = GM 覆盖表命中则覆盖值、否则配方默认（§6.2 ⑥）；
   *    毛产 = 规模 × 数量_j × 1000；损耗 = 毛产 × (饲料 + 折旧)‰；净产 = 毛产 − 损耗
   *      · 毛产 / 损耗 → ledger（I4.2 的 ΣOutput / ΣLoss）
   *      · **净产 → operator 的产权条目**（+net 一条；★ 产出离开 HouseholdEconomy 的**唯一去处**）
   * ③ 按 relation 结算（{@link ProductionSettlement}）：转出/收入**都是产权条目**（H1：受方恒为 actor，
   *      {@code ToCohort} 的家户 actor = {@code HouseholdActors.of(cohort)}）
   * ④ 家户那一条**同时记进两处**：会话工作副本（{@code householdGoods}，下一日消费读它）+ 本行流水 {@code income}
   *      （读口）—— ★ 条目本身照旧进 ledger（app 侧落盘的口径见 {@code EconomyDayStepper#step}）
   * </pre>
   *
   * <p>★★ <b>为什么 ② 与 ③ 必须在同一个方法里</b>（T4 与 T5 必须同批落地的全部理由）：产出一旦离开 {@code HouseholdEconomy}，
   * 家户唯一还有实物的通道就只剩 ③ 的实付 —— 少了它，**家户当场断粮**（而账面上看不出少了谁：产权条目照旧生成）。 由 {@code
   * ProductionLedgerTest#theCohortGetsItsPaidShareIntoTheConsumptionRow} 逐值守着。
   *
   * <p>★★ <b>H1.3：{@code deliverCohortIntake} 已删</b>（那一步做的是"按人口把 cohort 入账分派到行"）：受方就是**唯一那个家户** ⇒
   * 分派、取整余数、以及"解析不到行 ⇒ 补一条 {@code +unresolved} 留在 operator"的兜底**全都不再需要**。 取而代之的是**两条
   * fail-closed**（都在 {@link #requireCohortRows} 里，判在所有公式之前）：
   *
   * <ul>
   *   <li>规则指名的 cohort **必须有行**（家户不存在 ⇒ 当场抛；不许静默留在 operator —— 那会把"配置错了"伪装成"operator 自留"）；
   *   <li>该 cohort **必须住在这一格**（{@code cohort.hex() == facts.location()}）：家户账只住在它自己的格 （{@code
   *       HouseholdAccountKey(actor, cohort.hex())}），否则条目与账本会落在两个地方。
   * </ul>
   *
   * <p>★ <b>缺 {@code relation} 的产业</b>（{@code relations} 表里没有它）：<b>没有规则要结算</b>，产出全部留在 operator ——
   * 与"空规则表 ⇒ 全归 {@code residualOwner}"是**同一条等价路径**（裁定 E9）。
   *
   * <p>★ <b>如实记的边界</b>：{@link ProductionSettlement.Facts#inputs()} 本阶段没有公式读它（表里没有用到它的档）。
   *
   * <p>★★ <b>已知边界（Z2 记录，不在本批修）</b>：本批只让<b>生产路径</b>看见修正参数与 GM 产出数量覆盖；劳动/市场/债务/预期利润等 <b>规划读数</b>仍走
   * {@link ProductionProcessBook#plannedCapacityScaleOf} 与配方默认数量（§13.7），看不到这两个参数 ——
   * 读数与实收产出的差额待另批对齐。
   *
   * @param scale 关账处按 §6.2 求好的规模（含平均修正与余数结转）
   * @param outputQuantityOverrides 该产业的 GM 产出数量覆盖（缺商品 = 回落配方 {@code outputPerUnit()}）
   * @param income 逐家户的实物入账累加器（关系实付那几笔落在这里 ⇒ 读口与守恒式都读它）
   * @param householdGoods ★★ 家户账的会话工作副本（**就地更新**：关系实付计进来）
   * @param relations 生产关系表（键 = 产业 id；缺键 ⇒ 无规则）
   * @param ledger 当天的发生额累加器（毛产 / 损耗 / 产权条目 / 货币待办都进这里）
   */
  static void harvest(
      long day,
      ProductionProcess unit,
      IndustryDefinitionView industry,
      long scale,
      Map<CommodityId, Long> outputQuantityOverrides,
      Map<HouseholdId, Long> cycleLaborByHousehold,
      Map<CommodityId, Long> cycleInputUsed,
      Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long>
          cycleLaborByParticipant,
      io.mosire.simos.economy.model.ProductionRoster roster,
      Map<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      SettlementIndex index,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<CohortKey, HouseholdId> viewIndex,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal) {
    ProductionRecipe recipe = industry.recipe();
    HexCoord location = hexOfIndustry(industry.id());
    ActorRef operator = unit.operator();
    // ★★ P2-A §13.3：unit 的账户主体 = 组织者/经营者家户（单一或集体）。解析不到 ⇒ 具名缺口（见 creditOutput）。
    HouseholdRouting.Subject subject =
        HouseholdRouting.subjectOf(unit, householdEconomies, enterpriseByProcess, index);
    Map<HouseholdId, Long> subjectWeights =
        HouseholdRouting.weightsOf(unit.id(), index, householdEconomies);
    // ★★ **逐商品产出入账**：数量_j = GM 覆盖命中则覆盖值、否则配方默认（§6.2 ⑥）；
    //   毛产 = 规模 × 数量_j × 1000 毫/单位（其余算式一字未改）。
    Map<CommodityId, Long> grossByCommodity = new LinkedHashMap<>();
    Map<CommodityId, Long> netByCommodity = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> output : recipe.outputPerUnit().entrySet()) {
      CommodityId commodity = output.getKey();
      long quantityPerUnit = outputQuantityOverrides.getOrDefault(commodity, output.getValue());
      long gross = scale * quantityPerUnit * MILLI_PER_GRAIN; // 毫单位（毛产出）
      if (gross <= 0L) {
        continue;
      }
      // ★★ 生产消耗 = **饲料 + 农具折旧**（v2 spec §3.4：留种已移出收获扣减，改在现扣投入步）——
      //   两项各自具名，此处取**两者之和**、**逐商品按同一千分比**（作坊的 3% 即织机磨损）。
      // ★★★ D-032 C2（D2 §8.3）：毛产 → 净产只走 `ProductionOutputNetting`（全仓唯一拼写点）。
      ProductionOutputNetting.Netted nettedOutput = ProductionOutputNetting.net(gross);
      long loss = nettedOutput.loss();
      long net = nettedOutput.net();
      ledger.addGross(industry.id(), commodity, gross);
      ledger.addLoss(industry.id(), commodity, loss);
      grossByCommodity.put(commodity, gross);
      netByCommodity.put(commodity, net);
    }
    if (netByCommodity.isEmpty()) {
      return; // 规模 0（或产出表为空）⇒ 没有产出、也没有可付的：连规则都不必结算
    }
    // ★★★ D-032 C2（D2 §8.3）：名册方案只走 RoleDistribution —— 先把 net 按角色池/角色内拆到户，
    //   直接逐户 credit（不先记给 operator 再转出，避免双记），并且绝不调用旧 `ProductionSettlement` 产出分配。
    if (roster != null) {
      creditRosterNetPool(
          day,
          unit,
          industry,
          roster,
          cycleLaborByParticipant,
          assetShares,
          netByCommodity,
          householdEconomies,
          householdGoods,
          income,
          ledger);
      return;
    }
    // ★★ **R5 ②：净产进产出计提（+净产 → 账户主体家户），不再写进本产业的行**。
    //   ★ 单一主体 ⇒ 一条计提；集体主体 ⇒ 按劳动权重分给各家家户（产出归属与投入代理同一批人）。
    for (Map.Entry<CommodityId, Long> netEntry : netByCommodity.entrySet()) {
      creditOutput(
          subject,
          subjectWeights,
          householdGoods,
          operator,
          netEntry.getKey(),
          netEntry.getValue(),
          location,
          ledger);
    }
    ProductionRules relation = relations.get(unit.id());
    if (relation == null) {
      return; // 缺 relation ⇒ 没有规则：产出全部留在 operator（等价路径，见方法注释）
    }
    // ★★ **H1.3 的 fail-closed**：受方家户必须存在、且住在这一格（判在所有公式之前 —— 与 E14 同款）。
    requireCohortRows(relation, householdEconomies, location);
    // ★★ **R5 ③：按 relation 结算**（priority 序、付款上限 = 本周期收到的产出、E14 的守卫都在 {@link
    //   ProductionSettlement} 里 —— 本方法只负责"把事实递给它、把结果落到该落的地方"）。
    //   ★★ P2-A §13.3：结算的 from 恒为 relation.operator()（可能是组织 actor）⇒ 这里用一个**路由铸造口**
    //   把它解析成家户：单一主体一条腿；集体主体按劳动权重拆成多条腿（每条的两端恒为家户 actor）。
    List<Transfer> routedTransfers = new ArrayList<>();
    // ★ 同一 unit 的多条规则共用一批家户时，付方可用量必须在**本轮结算内**累计扣减（否则同一笔钱会被两条规则各花一次）。
    Map<HouseholdId, Map<CommodityId, Long>> routedGoodsDebited = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> routedMoneyDebited = new LinkedHashMap<>();
    // ★★★ W2-fix7 / D1②（v3.10 §7 裁定②）：**实物租腿三级支付链的②级** —— 付方主体结算前的实物库存（逐商品合计）。
    //   ★ 上面的 creditOutput 已把本周期净产计进这批家户的账 ⇒ ①"本 unit 产出"与 ②"家户库存"在这张表里是
    //     同一份数的两级（与 ProductionSettlement.availableOf 的口径注同源）。★ 主体 = unit 的账户主体，
    //     而 {@code EconomyData} 的构造期守卫已判死 {@code relation.operator() == unit.operator()} ⇒ 与路由付方同源。
    Map<CommodityId, Long> payerStock = availableGoodsOf(subject, householdGoods);
    ProductionSettlement.Outcome outcome =
        ProductionSettlement.settle(
            relation,
            new ProductionSettlement.Facts(
                location,
                grossByCommodity,
                netByCommodity,
                // ★★★ D-032 B3（I9）：投入与"谁出了多少工"一律取**回执**的实际事实（同源、同一次关账）。
                cycleInputUsed,
                cycleLaborByHousehold,
                industry.outputPerUnit(),
                // ★★ 货币档的付款上限 = **账户主体家户们的可花货币合计**（逐币种）；解析不到主体 ⇒ 空表（实付 0、
                //   欠额进读数 —— 如实报，不是静默付 0）。
                availableMoneyOf(subject, householdMoney),
                // ★ W2-fix7 / D1②：实物租腿的第二级（见上面的 payerStock 口径注）
                payerStock),
            (from, to, transferLocation, goods, money, reason) -> {
              Transfer first =
                  routeRelationTransfer(
                      unit,
                      subject,
                      subjectWeights,
                      from,
                      to,
                      transferLocation,
                      goods,
                      money,
                      reason,
                      householdEconomies,
                      householdGoods,
                      householdMoney,
                      enterpriseByProcess,
                      viewIndex,
                      index,
                      householdOfActor,
                      ledger,
                      routedTransfers,
                      routedGoodsDebited,
                      routedMoneyDebited);
              return first;
            });
    // ★★ **H2：实付一律是转移**（每条两端恒为家户）—— 铸的时候已经进了当天的账，这里只需
    //   ① 把两端落到会话副本上（唯一 applier）② 把收方的实物腿记进流水（实物入账读数）。
    for (Transfer transfer : routedTransfers) {
      applyTransfer(householdGoods, householdMoney, householdOfActor, transfer, issuanceJournal);
      HouseholdId cohort = householdOfActor.get(transfer.to());
      if (cohort != null) {
        for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
          addGoods(income, cohort, leg.getKey(), leg.getValue());
        }
      }
    }
    // ★★ **S4：逐规则的实得读数**（应付 / 实付 / 欠）—— 只读、不影响守恒、不落债权。
    for (ProductionLedger.RuleSettlement reading : outcome.ruleSettlements()) {
      ledger.addRuleSettlement(reading);
    }
    for (CompensationRule rule : outcome.deferredMoney()) {
      ledger.addDeferred(rule);
    }
    // ★★★ W2-fix7 / D1②（v3.10 §7 裁定②）：**实物租腿三级支付链的日志面** —— ①本 unit 产出 → ②家户库存 →
    //   ③具名缺口。"付不出"绝不静默：缺口一条 INFO（默认档就看得见，含 应付/实付/缺口/产出/库存/是否可产），
    //   付得出来的逐条在 DEBUG（现场判"租金吃的是产出还是老库存"）。★ 缺口同时进 RuleSettlement.owed（S4 读数）
    //   并在关账处被 capitalizeArrears 折成债权 —— 三处都读得到，不是只有日志。
    logInKindRentChain(day, unit, industry, outcome, netByCommodity, payerStock);
  }
}
