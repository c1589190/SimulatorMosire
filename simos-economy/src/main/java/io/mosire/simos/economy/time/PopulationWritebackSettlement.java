package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.LaborSettlement.requireGovServiceCommitmentsWithinBudgets;
import static io.mosire.simos.economy.time.LaborSettlement.scaleLaborOfGroup;
import static io.mosire.simos.economy.time.LaborSettlement.withLaborMilli;
import static io.mosire.simos.economy.time.PopulationDebtWriteOffSettlement.logEmptiedHouseholdDebtRetained;
import static io.mosire.simos.economy.time.PopulationDebtWriteOffSettlement.requireReportedVitalCounts;
import static io.mosire.simos.economy.time.PopulationDebtWriteOffSettlement.writeOffDebtsForDeaths;
import static io.mosire.simos.economy.time.PopulationDebtWriteOffSettlement.writeOffExtinctHouseholdDebts;
import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.householdKeysOfLot;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.allocate;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class PopulationWritebackSettlement {

  // ── 人口变动回写（R4：社会侧的出生/死亡 → 经济侧的行人口、劳动配额与流水）──────────────────
  //   ★★ W3-fix（2026-10-11）语义收口：本区两条入口（批次侧 applyPopulationChange / 逐户侧
  //     applyHouseholdPopulationDeltasInto）都是**接收社会报来的生死**，经济侧**不判定死亡、不缩放劳动**。
  //     原 applyPopulationChange 的 javadoc 悬在这里（离方法 180 行、语法上根本没挂到它身上）⇒ 已移到方法正上方。

  /**
   * ★★ <b>P2-A §13.4：每个 tick 重算家户时间预算</b>（毫小时）—— 输入 = 协调器从 Social 人口组成 × {@code SocialProvisioning}
   * 的劳动权威（C8；{@code HouseholdLaborTimeTable} 只是 legacy 值载体）现算的「household → 预算」；本方法把它写进 {@code
   * HouseholdEconomy.laborMilli} （唯一投影），并把超预算的家户配额**按比例缩到预算内**（保持 {@code Σallocations ≤ budget}
   * 不变量）。
   *
   * <p>★★ <b>为什么在这里缩</b>：预算每 tick 会随出生/死亡/成年变化；配额是周期粒度的。若只改行预算不缩配额， {@code EconomyData}
   * 的构造期不变量会在下一个 revision 边界当场拒。缩法是确定性的最大余数法 （同权重按 allocation id 升序），<b>不</b>做"缺口优先"的新分配 —— 那个排序属
   * P2-B 的利润率排队。
   *
   * <p>★★ <b>Z7d-1（饥饿折算）</b>：{@code GOV_SERVICE} 承诺是**职位/诉求**，预算被饿少后允许 `Σ GOV_SERVICE >
   * budget`（不缩/不删，C7）；此时先把 GOV_SERVICE 整额留在活表里，PRODUCTION 可用量按 0 处理（只缩/删 PRODUCTION）。有效供给不足由 app
   * 的供给桥按 `min(承诺, 实际劳动)` cap 并记录 underfed。
   */
  static void applyLaborBudgetsInto(
      EconomySession session, Map<HouseholdId, Long> budgetsByHousehold) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(budgetsByHousehold, "budgetsByHousehold");
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    List<HouseholdId> households = new ArrayList<>(budgetsByHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        throw new IllegalStateException("时间预算指向不存在的家户（协调器投影必须与经济行同键）：" + household);
      }
      long budget = Math.max(0L, budgetsByHousehold.getOrDefault(household, 0L));
      householdEconomies.put(
          household,
          householdEconomy.withPopulationAndLabor(householdEconomy.population(), budget));
    }
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    Map<HouseholdId, List<LaborAllocationId>> byHousehold = new LinkedHashMap<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      byHousehold
          .computeIfAbsent(laborCommitment.household(), ignored -> new ArrayList<>())
          .add(laborCommitment.id());
    }
    for (Map.Entry<HouseholdId, List<LaborAllocationId>> entry : byHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomies.get(entry.getKey());
      if (householdEconomy == null) {
        continue;
      }
      List<LaborAllocationId> ids = new ArrayList<>(entry.getValue());
      ids.sort(Comparator.comparing(LaborAllocationId::value));
      // ★★ Z3a/C7：先整额扣 GOV_SERVICE（不可缩/最高优先级），只对剩余预算里的 PRODUCTION 做比例缩；
      //   Σ GOV_SERVICE > budget ⇒ 具名 LABOR_COMMITMENT_CONTRACT ERROR + fail-closed（绝不静默缩政府承诺）。
      long govServiceSum = 0L;
      long productionSum = 0L;
      for (int i = 0; i < ids.size(); i++) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
        if (laborCommitment == null) {
          continue;
        }
        long amount = Math.max(0L, laborCommitment.laborMilli());
        if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          govServiceSum = Math.addExact(govServiceSum, amount);
        } else {
          productionSum = Math.addExact(productionSum, amount);
        }
      }
      long total = Math.addExact(govServiceSum, productionSum);
      long budget = Math.max(0L, householdEconomy.laborMilli());
      if (total <= budget || total <= 0L) {
        continue;
      }
      // ★★ Z7d-1：GOV_SERVICE 是职位/诉求，允许饿少后的预算 `budget < Σ GOV_SERVICE`（C7 不缩/不删）；
      //   此时 PRODUCTION 可用量 = 0（只缩/删 PRODUCTION，政府承诺行原样保留）。
      long availableForProduction = Math.max(0L, budget - govServiceSum);
      if (productionSum <= availableForProduction || productionSum <= 0L) {
        continue; // GOV_SERVICE 已整额装下（或 over-budget 但没有可缩 PRODUCTION）：防御性 no-op
      }
      // ★★ D-032 W5-E：实际既有 PRODUCTION 配额超过**当刻**预算 ⇒ 按比例缩到预算内，并留具名读数。
      //   这是实际 labor 不超预算的兜底点之一（另一处是 LaborQueueBook.plan 的预算封顶）。
      if (EconomyLog.settlement().isInfoEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "HOUSEHOLD_LABOR_CAPPED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "stage",
                    "settlement-budget-rescale",
                    "household",
                    entry.getKey().value(),
                    "productionLaborMilli",
                    productionSum,
                    "availableForProductionMilli",
                    availableForProduction,
                    "budgetMilli",
                    budget,
                    "govServiceLaborMilli",
                    govServiceSum,
                    "reason",
                    "household-labor-capped"));
      }
      List<LaborAllocationId> productionIds = new ArrayList<>();
      long[] productionWeights = new long[ids.size()];
      int productionCount = 0;
      for (int i = 0; i < ids.size(); i++) {
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(ids.get(i));
        if (laborCommitment == null || laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
          continue;
        }
        productionIds.add(ids.get(i));
        productionWeights[productionCount] = Math.max(0L, laborCommitment.laborMilli());
        productionCount++;
      }
      long[] weightArray = new long[productionCount];
      System.arraycopy(productionWeights, 0, weightArray, 0, productionCount);
      long[] parts =
          ProportionalSplit.byDenominator(availableForProduction, weightArray, productionSum);
      for (int i = 0; i < productionIds.size(); i++) {
        LaborAllocationId id = productionIds.get(i);
        HouseholdLaborCommitment laborCommitment = laborCommitments.get(id);
        if (laborCommitment == null) {
          continue;
        }
        if (parts[i] <= 0L) {
          laborCommitments.remove(id);
        } else {
          laborCommitments.put(id, withLaborMilli(laborCommitment, parts[i]));
        }
      }
    }
  }

  /**
   * ★★ <b>2026-10-09 家户结构修复 Batch 3：把 app 注入的当日逐户需求写进家户行</b>（{@code naturalNeeds} 的唯一写入点）。
   *
   * <pre>
   * 入参：Map&lt;HouseholdId, Map&lt;CommodityId, Long&gt;&gt;   // app 逐户调 Social.householdNaturalNeeds(...) 的展开结果
   * 逐行：row.naturalNeeds = 入参.get(row.id())          // 缺键 = 空 map = 无需求（调用方给的 map 可能为空）
   * </pre>
   *
   * <p>★★ <b>本方法只换 {@code naturalNeeds}，绝不在这里累加 {@code cycleNaturalNeedMilli}</b>：周期累加按"消费步对
   * 当日粮需求执行一次"（见 {@link #consumeOneHousehold}）；若注入也加一遍，周期分母会翻倍。
   *
   * <p>★ <b>拒绝语义</b>：入参 key 不在经济家户行里 ⇒ 具名 {@link IllegalStateException}（Social/Economy 投影不一致，不静默丢）；
   * 需求表为 null ⇒ 具名 {@link IllegalArgumentException}（无需求用空 map）；map 的键/值/非负由 {@link
   * HouseholdEconomy} 规范构造器当场拒。★ 日志只把计数放 DEBUG（逐户明细由 app 展开日志承担），不刷 INFO；拒绝路径另记 ERROR 具名。
   */
  static void applyNaturalNeedsInto(
      EconomySession session, Map<HouseholdId, Map<CommodityId, Long>> needsByHousehold) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(needsByHousehold, "needsByHousehold");
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : needsByHousehold.entrySet()) {
      HouseholdId household = entry.getKey();
      if (household == null) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-household-key"));
        throw new IllegalArgumentException("自然需求注入含 null 家户键（app 展开表不得含 null）");
      }
      if (!householdEconomies.containsKey(household)) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "unknown-household-row",
                    "household",
                    household));
        throw new IllegalStateException(
            "自然需求注入指向不存在的经济家户行（Social/Economy 投影不一致，拒绝静默丢弃）: " + household);
      }
      if (entry.getValue() == null) {
        EventLog.channel(TRACE)
            .error(
                LogEvent.of(
                    "HOUSEHOLD_NATURAL_NEEDS_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-needs",
                    "household",
                    household));
        throw new IllegalArgumentException(
            "自然需求注入的家户需求表不得为 null（无需求用空 map）: household=" + household);
      }
    }
    for (Map.Entry<HouseholdId, HouseholdEconomy> row : householdEconomies.entrySet()) {
      Map<CommodityId, Long> needs = needsByHousehold.get(row.getKey());
      row.setValue(row.getValue().withNaturalNeeds(needs == null ? Map.of() : needs));
    }
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_NATURAL_NEEDS_INJECTED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "households",
                  needsByHousehold.size(),
                  "rows",
                  householdEconomies.size()));
    }
  }

  // ★★★ D-032 B3（§13.13.7 第 8 条 / §13.3 第 2 条）：静态入口 `applyPopulationChange(EconomyData, changes)`
  //   已**删除**（同 `settle`：main 里 0 个调用点、runtime = null 的会话）。会话形态的
  //   `applyPopulationChangeInto(EconomySession, changes)` 保留（`EconomyDayStepper` 走它，runtime 合法）。

  /**
   * ★ session 形态（{@link EconomyDayStepper} 走它）：就地更新工作表与流水，不构造中间状态。
   *
   * <p>★ <b>语义同 {@link #applyPopulationChange}</b>：接收外部报来的逐批次生死；经济侧不判定死亡（见那条的类注第②段）。 {@code
   * deathsParts[j] > 0} 时删债是**经济侧对"人已死"这一既成事实的后果处理**（P5），不是"经济侧决定谁死"。
   */
  static void applyPopulationChangeInto(EconomySession session, List<LotChange> changes) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(changes, "changes");
    if (changes.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        session.sheet().laborCommitments();
    // ★ P2-A A3：成员份额不再是 Economy 状态组件 —— 家户人口组成由调用方作为**只读投影**传入（见 composition）。
    LinkedHashMap<AssetShareId, OwnershipStake> assetShares = session.sheet().assetShares();
    // ★★ P5：死亡删债的两个工作输入 —— 合同表工作副本与「债务人 → 合同 id」的只读索引。
    //   索引只在这里建一次（月度调用）；合同本金在下面逐笔改写时，索引里的 id 仍有效（forgive 只换值不删键）。
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor =
        debts.isEmpty() ? Map.of() : DebtIndex.byDebtor(debts);
    // ★★ R4-B.3a-perf：人口回写是“逐 LotChange 摊到目标家户”，旧实现对每条 change 扫全量配额
    //   （真档月度 6,000+ 条 × 44,000+ 配额）。这里在入口建一次只读索引：批次→unit、格→unit、
    //   unit→配额，随后每条 change 只碰与自己目标集合相关的行。
    SettlementIndex index =
        SettlementIndex.buildWithoutAssetOccupancy(
            session.sheet().units(),
            session.sheet().industries(),
            assetShares,
            laborCommitments,
            householdEconomies,
            null,
            // ★★ R4-E2b：同一会话里 settleOneDayInto 可能刚由进入执行插入新 unit 的 relation ⇒ 读工作副本选择，
            //   不再直读 base.relations()（否则这一次人口回写的经济家户索引会漏掉刚建的 unit）。
            session.sheet().relationsOrBase());
    // 批次 → 它供给的 unit（保序、去重；只认 activity 命中现存 unit 的配额）。
    Map<PeopleLotId, List<ProductionUnitId>> unitsOf = index.unitsByGroup();
    for (LotChange change : changes) {
      if (change.isEmpty()) {
        continue;
      }
      List<ProductionUnitId> targets = unitsOf.getOrDefault(change.group(), List.of());
      if (targets.isEmpty()) {
        // ★★ **兜底：摊到"它住的那一格"的 unit 家户行上**（见 {@link LotChange} 的类注）—— 没有劳动配额的批次
        //   （0-14 岁那一档）照样要吃饭、照样会死。
        //   ★ R4-B.3a-perf：格 → unit 由索引一次给出（旧实现逐 change 全量扫 unit）。
        targets = index.unitsInHex(IndustryHexKeys.hexKey(change.at().q(), change.at().r()));
        if (targets.isEmpty()) {
          continue; // 该格本来就没有任何经济状态（世界还没播种到这里）⇒ 没有可摊的行
        }
      }
      // ★★ **H0/S3：这批人的家户行 = 供给目标产业的配额里、居住类型匹配的 HouseholdLaborCommitment.household 并集**，
      //   **并集去重** —— 不按四阶层枚举、也不读 HouseholdEconomy.view.stratum（阶层写回后仍不漏行/错行）。
      //   农村批次同时供给农业与家庭纺织（两者落在**同一批农村家户行**上）⇒ 不去重就会把它的人与生死**算两遍**。
      List<HouseholdId> keys =
          householdKeysOfLot(
              householdEconomies, targets, ResidenceKind.ofLot(change.group()), index);
      if (keys.isEmpty()) {
        continue; // 那些产业在这一格没有家户行（行还没种下）⇒ 没有可摊的行
      }
      long[] rowPopulations = new long[keys.size()];
      long populationBefore = 0L;
      for (int j = 0; j < keys.size(); j++) {
        rowPopulations[j] = householdEconomies.get(keys.get(j)).population();
        populationBefore += rowPopulations[j];
      }
      long[] birthsParts = allocate(change.births(), rowPopulations);
      long[] deathsParts = allocate(change.deaths(), rowPopulations);
      for (int j = 0; j < keys.size(); j++) {
        HouseholdId key = keys.get(j);
        HouseholdEconomy householdEconomy = householdEconomies.get(key);
        long population = householdEconomy.population();
        if (population <= 0L) {
          continue;
        }
        long remaining = population - deathsParts[j]; // deathsParts ≤ row 人口（按人口权重切，见 allocate）
        long labor = householdEconomy.laborMilli() * remaining / population; // 死亡同比例缩；出生不加劳动
        householdEconomies.put(
            key,
            withPopulationAndLabor(
                householdEconomy, remaining + birthsParts[j], Math.max(0L, labor)));
        if (birthsParts[j] != 0L || deathsParts[j] != 0L) {
          flows.put(key, withLifecycle(flows.get(key), key, birthsParts[j], deathsParts[j]));
        }
        // ★★ P5：死亡按人口比例删债 —— 用**死亡前**的 population 与 survivors = population − deathsParts[j]，
        //   只缩减「debtor == 本行」的活跃合同（逐笔 floor；survivors == 0 ⇒ 全额减免）。不搬粮/钱、不碰账户。
        if (deathsParts[j] > 0L) {
          writeOffDebtsForDeaths(
              debts,
              debtsByDebtor.getOrDefault(key, List.of()),
              key,
              population,
              deathsParts[j],
              session.debtWriteOffs());
        }
      }
      scaleLaborOfGroup(
          change.group(),
          populationBefore,
          populationBefore - change.deaths(),
          laborCommitments,
          index);
      // ★ P2-A A3：出生/死亡只改 HouseholdEconomy.population 与派生量（工时/配额/债务）；家户成员份额由 Social 权威维护。
    }
    // ★★ Z3a/C7：全部批次人口缩放已跑完 —— 政府承诺整额保留后若越预算，统一具名 ERROR + fail-closed。
    //   本方法签名无 day ⇒ 按日志纪律用 system 来源、事件不带 day。
    requireGovServiceCommitmentsWithinBudgets(
        laborCommitments, householdEconomies, EconomyLogSource.ECONOMY_POPULATION_WRITE, -1L);
    // ★ 工作表与流水都在 session 里就地更新；构造与全量守卫由 revision 边界（build）负责（P1.5a）。
  }

  /**
   * ★★ <b>2026-10-09 每 tick 生死 Batch B：把 Social 的逐家户净人口变化直接落到经济行</b>（新主路径；旧 {@link
   * #applyPopulationChangeInto} 的批次摊派路径保留给迁移/兼容调用）。
   *
   * <pre>
   * 逐条：row.population += delta      // delta = 出生 − 死亡，由 Social 按 HouseholdId 算好
   *       结果 &lt; 0 或行不存在 ⇒ 具名拒（fail-closed，不静默跳过）
   * </pre>
   *
   * <p>★★ <b>本重载不带生死流水</b>（出生/死亡两表当空表）：调用方是"人在户与户之间搬"的两条路 —— P0 迁移桥（{@code ModeMigrationSettlement}
   * 的 outbox）与 Z7d-2 逃亡（{@code GovernmentServiceDesertionBridge}）。搬人不产生出生/死亡， 故 {@code
   * FlowRow.births}/{@code FlowRow.deaths} 不该因它变化。★ 逐 tick 的**生死**走五参重载 {@link
   * #applyHouseholdPopulationDeltasInto(EconomySession, Map, Map, Map, long)}。
   *
   * <p>★★ <b>为什么不像旧路径那样摊批次/缩劳动/删债</b>：调用方（app 日循环）在调用本方法**之前**已经用新 Social 刷新了 {@code composition} /
   * {@code laborMilli} / {@code naturalNeeds}——劳动权威已经是结算后 Social；这里再按人口 比例缩一次会把当日权威缩两遍。人口 delta
   * 只改行人口这一项投影，其它派生量由调用方的刷新与后续 step 承担。
   *
   * <p>★ <b>拒绝语义</b>：null 键/值、0 delta、缺经济行、结果为负都具名拒（ERROR 日志 + 异常）；空表是合法输入 （当天无生死）。★ 汇总只记
   * DEBUG（逐户明细由 Social 侧事件日志承担）。
   *
   * <p>★★ <b>W8（2026-10-11）：本重载是"迁移把某户搬空"的入口</b> —— 两表恒空 ⇒ 五参重载里的户绝判据②（社会报来的死亡 &gt; 0）**恒不成立** ⇒
   * 只管到"债务保留"那一条具名 INFO（{@code HOUSEHOLD_EMPTIED_DEBT_RETAINED}），
   * <b>绝不销账</b>。这正是"迁移不销账、只有死亡导致的归零才销账"那条判据的落点。
   *
   * <p>★ <b>D4（W3-fix2，2026-10-11）：本重载不带世界日</b>——它的两表恒空 ⇒ {@code HOUSEHOLD_VITAL_WRITEBACK}
   * 永不发射（见下面的守卫），故 {@link #NO_WORLD_DAY} 不进任何输出。真发生生死的路径必须给真日。
   */
  static void applyHouseholdPopulationDeltasInto(
      EconomySession session, Map<HouseholdId, Long> deltas) {
    applyHouseholdPopulationDeltasInto(session, deltas, Map.of(), Map.of(), NO_WORLD_DAY);
  }

  /**
   * ★ <b>"这条调用路径没有世界日"的哨兵</b>（只给"人在户与户之间搬"那条路用；那条路 births/deaths 恒空 ⇒ {@code
   * HOUSEHOLD_VITAL_WRITEBACK} 永不发射）。★ <b>D4</b>：真发生生死的调用必须给真日 —— 否则下面的守卫当场拒， 绝不把假日写进日志。
   */
  static final long NO_WORLD_DAY = -1L;

  /**
   * ★★★ <b>W3-fix（2026-10-11）：把 Social（人口权威）报来的逐户净变化 + 逐户出生/死亡一起落到经济侧</b>。
   *
   * <pre>
   * ① row.population += delta                            // 净变化（出生 − 死亡）
   * ② FlowRow.births += birthsByHousehold[household]     // 社会报来的出生原值 → 流水
   *    FlowRow.deaths += deathsByHousehold[household]     // 社会报来的死亡原值 → 流水（含饥荒那一份）
   * ③ ★★ W8：本行人口由 &gt; 0 变 0 且**社会报来的死亡 &gt; 0** ⇒ 户绝 ⇒ 该户作为债务人/债权人的账**双向销账**
   *         （{@link #writeOffExtinctHouseholdDebts}）；由**迁移**归零的只具名记"债务保留"，不销账。
   * </pre>
   *
   * <p>★★ <b>为什么要有 ②</b>：净变化读不出"死了多少人"（一户同时生死时只报差），而经济侧的危机读数与流水要的正是死亡原值； 只报净变化 ⇒ {@code
   * FlowRow.deaths} 永远是 0 ⇒ {@code CrisisMonitor} 的 MORTALITY 灯**永远不会亮**（W3b 报的读数缺口）。
   * 本步把它接上：**经济侧仍然一个字都不判**（判死在 Social），只把社会报来的数落进流水（唯一写口 {@link #withLifecycle}）。 ★ <b>{@code
   * VitalSettlementResult#famineDeaths()} ≤ 这里的死亡人数</b>（它是"cause 含饥荒的死亡人数"，W3-fix2 / D2 + W3-fix3
   * 裁定），故"饿死多少"在经济读数里也读得到；明细仍在 Social 的 {@code FAMINE_MORTALITY} 日志里。
   *
   * <p>★ <b>不做</b>：不摊批次、不缩劳动（劳动权威是结算后的 Social，调用方已刷新）、不改 {@code naturalNeeds}。 ★★ <b>W8
   * 改口（2026-10-11）：本方法<b>现在会删债</b></b> —— 改前这里的 javadoc 写着"不删债"，那正是调查报告 D1 指出的缺陷（活路径不核销、核销路径不可达）。现在
   * ③ 只在"**死亡**把本行打到 0"时删债（双向），迁移归零照旧不删。
   *
   * <p>★★ <b>拒绝语义（fail-closed，全部在写任何一行之前）</b>：三张表的 null 键/值、0 值、值 &lt; 0、缺经济行，以及 "净变化 ≠ 出生 −
   * 死亡"（逐户交叉核对）都具名拒（ERROR 日志 + 异常）。★ 三张表都空是合法输入（当天无生死、无人搬家）。
   *
   * <p>★ <b>日志</b>：报来的死亡 &gt; 0 ⇒ 一条 INFO「这一轮死了多少人、落在哪些行」（§一.9：生命周期事件走 INFO）； 只有出生 ⇒ 同事件名
   * DEBUG。逐户明细由 Social 侧事件日志承担。
   *
   * @param deltas 逐家户净人口变化（出生 − 死亡；只含非 0 项）；不得为 null
   * @param birthsByHousehold 逐家户出生原值（只含 &gt; 0 的家户）；不得为 null
   * @param deathsByHousehold 逐家户死亡原值（只含 &gt; 0 的家户；<b>含饥荒那一份</b>）；不得为 null
   * @param day ★★ <b>W3-fix2（D4，2026-10-11）：世界日</b> —— {@code HOUSEHOLD_VITAL_WRITEBACK} 的 {@code
   *     day} 字段（改前 162/162 条都没有该字段 ⇒ 这条读数无法按天检索/对账）；<b>真发生生死的调用必须 ≥ 0</b> （{@link #NO_WORLD_DAY}
   *     只许给两表恒空的搬迁路径）
   */
  static void applyHouseholdPopulationDeltasInto(
      EconomySession session,
      Map<HouseholdId, Long> deltas,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(deltas, "deltas");
    Objects.requireNonNull(birthsByHousehold, "birthsByHousehold");
    Objects.requireNonNull(deathsByHousehold, "deathsByHousehold");
    // ★★ D4 守卫（fail-closed）：只有"真的要把生死写进流水"时才需要世界日 —— 缺了它绝不用假日凑数。
    if (day < 0L && (!birthsByHousehold.isEmpty() || !deathsByHousehold.isEmpty())) {
      throw new IllegalArgumentException(
          "HOUSEHOLD_VITAL_WRITEBACK 需要世界日（day >= 0），但调用方给的是 day="
              + day
              + "（births="
              + birthsByHousehold.size()
              + " 户, deaths="
              + deathsByHousehold.size()
              + " 户）");
    }
    if (deltas.isEmpty() && birthsByHousehold.isEmpty() && deathsByHousehold.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    // ① 先做全量校验：任何坏输入都在写任何一行之前具名拒（不留半落账状态）。
    long deltaSum = 0L;
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      Long delta = entry.getValue();
      if (household == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-household-key"));
        throw new IllegalArgumentException("逐户人口变化含 null 家户键（Social 结算结果不得含 null）");
      }
      if (delta == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化的值不得为 null: household=" + household);
      }
      if (delta == 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "zero-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化不应含 0（只给非 0 净变化）: household=" + household);
      }
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "unknown-household-row",
                    "household",
                    household));
        throw new IllegalStateException(
            "逐户人口变化指向不存在的经济家户行（Social/Economy 投影不一致，拒绝静默丢弃）: " + household);
      }
      long nextPopulation = Math.addExact(row.population(), delta);
      if (nextPopulation < 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "negative-result",
                    "household",
                    household,
                    "population",
                    row.population(),
                    "delta",
                    delta,
                    "next",
                    nextPopulation));
        throw new IllegalStateException(
            "逐户人口变化后结果为负（拒绝落账）: household="
                + household
                + " population="
                + row.population()
                + " delta="
                + delta);
      }
      deltaSum = Math.addExact(deltaSum, delta);
    }
    // ①b ★★ W3-fix：社会报来的两张生死原值表也在这里一次校验（同样是"写任何一行之前"）。
    requireReportedVitalCounts(birthsByHousehold, "birthsByHousehold", householdEconomies);
    requireReportedVitalCounts(deathsByHousehold, "deathsByHousehold", householdEconomies);
    // ①c 交叉核对：净变化必须等于"报来的出生 − 报来的死亡"（缺键按 0 计）。Social 侧的同一条不变量在这里做**接收侧复核** ——
    //   两侧对不上就当场拒，绝不让"落进人口的是一个数、落进流水的是另一个数"这种半落账状态存在。
    Set<HouseholdId> lifecycleHouseholds = new LinkedHashSet<>(birthsByHousehold.keySet());
    lifecycleHouseholds.addAll(deathsByHousehold.keySet());
    for (HouseholdId household : lifecycleHouseholds) {
      long births = birthsByHousehold.getOrDefault(household, 0L);
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      long delta = deltas.getOrDefault(household, 0L);
      if (Math.subtractExact(births, deaths) != delta) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_VITAL_WRITEBACK_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "lifecycle-delta-mismatch",
                    "household",
                    household,
                    "births",
                    births,
                    "deaths",
                    deaths,
                    "delta",
                    delta));
        throw new IllegalArgumentException(
            "报来的逐户生死与净人口变化不一致（拒绝落账）: household="
                + household
                + " births="
                + births
                + " deaths="
                + deaths
                + " delta="
                + delta);
      }
    }
    // ② 校验全过后再写：population += delta；laborMilli 原样（调用方已按新 Social 重算）。
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      HouseholdEconomy row = householdEconomies.get(household);
      long nextPopulation = Math.addExact(row.population(), entry.getValue());
      householdEconomies.put(
          household, withPopulationAndLabor(row, nextPopulation, row.laborMilli()));
    }
    // ③ ★★ W3-fix：社会报来的**出生/死亡**逐行追进流水（唯一写口 withLifecycle）。
    //   键序 = 家户行序（与 settleOneDayInto 的流水键序同一规则）⇒ 状态键序仍是输入的确定性函数。
    //   ★ 这里只"落账"：不判定谁死、不缩劳动、不删债 —— 那些都已由 Social 判定/由调用方刷新。
    if (!birthsByHousehold.isEmpty() || !deathsByHousehold.isEmpty()) {
      long reportedBirths = 0L;
      long reportedDeaths = 0L;
      long lifecycleRows = 0L;
      for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
        HouseholdId household = entry.getKey();
        long births = birthsByHousehold.getOrDefault(household, 0L);
        long deaths = deathsByHousehold.getOrDefault(household, 0L);
        if (births == 0L && deaths == 0L) {
          continue; // 绝大多数行与今天无关：不碰它们的流水（保住"没发生额就不建行"）
        }
        flows.put(household, withLifecycle(flows.get(household), household, births, deaths));
        reportedBirths = Math.addExact(reportedBirths, births);
        reportedDeaths = Math.addExact(reportedDeaths, deaths);
        lifecycleRows++;
      }
      // ★ §一.9：死亡是生命周期事件 ⇒ INFO；只有出生 ⇒ DEBUG（同事件名、同字段，避免每天一条 INFO 噪声）。
      //   ★★ D4（W3-fix2）：补 `day` —— 与同写口的邻居（POPULATION_SETTLE / FAMINE_MORTALITY /
      //   HOUSEHOLD_GAP_*）同字段名同语义，这条读数从此可按天检索/对账。
      LogEvent vital =
          LogEvent.of(
              "HOUSEHOLD_VITAL_WRITEBACK",
              EconomyLogSource.ECONOMY_POPULATION_WRITE,
              "day",
              day,
              "households",
              lifecycleRows,
              "births",
              reportedBirths,
              "deaths",
              reportedDeaths,
              "unit",
              "person",
              "why",
              "w3-fix: social-is-population-authority; economy-receives-births-deaths-into-flowrow");
      if (reportedDeaths > 0L) {
        EventLog.channel(EconomyLog.population()).info(vital);
      } else if (EconomyLog.population().isDebugEnabled()) {
        EventLog.channel(EconomyLog.population()).debug(vital);
      }
    }
    if (EconomyLog.population().isDebugEnabled()) {
      EventLog.channel(EconomyLog.population())
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_POPULATION_DELTAS_APPLIED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "households",
                  deltas.size(),
                  "deltaNet",
                  deltaSum,
                  "unit",
                  "person"));
    }
    // ④ ★★★ W8（2026-10-11 用户裁定「穷人的话，死了账一并消除」）：**户绝核销接通到活路径**。
    //   改前：核销只在批次入口 applyPopulationChangeInto（死代码）里，活路径一个字都不动 ⇒ 两个世界 × 600 天
    //   DEBT_FORGIVE = 0，唯一一例死亡户绝（hh-2_-2-rural-landlord，d550）的债到 d600 变成 ×153.93（调查报告 D1/D2）。
    //   ★ 判据（唯一，且必须用**社会侧权威**）：
    //     ① 本行人口**由 > 0 变 0**（populationBefore = −delta > 0 且写后 == 0）；
    //     ② 且**社会报来的死亡 > 0**（deathsByHousehold）⇒ 这是"人死光了"，不是"人搬走了"。
    //   ★ 为什么②必须有（调查报告 §2.2 的口径陷阱）：本世界里"人口 → 0"里 **19/21 是迁移**。迁移时债务按比例
    //     随人走（LotMigrationBook.moveDebts / ModeMigrationSettlement.moveDebt，目标恰是债权人时走自债净额）
    //     ⇒ 迁移归零**不销账**（用户口径），只具名记一条"空了、债务保留"。区分落在这条 if 上，不靠形态学猜。
    //   ★ 键序 = 家户行序（deltas 只用来查"这一行今天动没动"）—— 与 ③ 的流水键序同一规则 ⇒ 确定性 I7
    //     （不引入 Map.copyOf/Set.copyOf，也不按调用方给的表序走）。
    //   ★ 两个分支都真实可达：迁移走二参重载（births/deaths 两表恒空 ⇒ deaths == 0），生死走五参重载。
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId household = entry.getKey();
      Long delta = deltas.get(household);
      if (delta == null || entry.getValue().population() != 0L) {
        continue; // 这一行今天没动、或没归零 ⇒ 与户绝无关
      }
      long populationBefore = Math.negateExact(delta);
      if (populationBefore <= 0L) {
        continue; // 本来就是空户（空槽位）⇒ 不是"归零"事件，不销账
      }
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      if (deaths > 0L) {
        writeOffExtinctHouseholdDebts(session, household, populationBefore, deaths, day);
      } else {
        logEmptiedHouseholdDebtRetained(session, household, populationBefore, day);
      }
    }
  }

  // ★★★ B5（§13.15.4 裁定二十）：原三个 **main + test 全仓 0 调用点**的公开读口
  //   —— `industriesAt(definitions, HexCoord)` / `industriesAt(industriesByHex, HexCoord)` /
  //   `industriesByHex(definitions)` —— **整段删除**（能力不再"静默保留"：0 调用点的公开面 = 噪声）。
  //   ★ 日结算真正在用的那条路**一字未动**：`SettlementIndex` 自己建同一份"格 → 产业"索引
  //     （`SettlementIndex:635` 的私有 `industriesByHex(industries)`，由 `:193` 从当刻定义来源构建），
  //     逐日 `settlementIndex.industriesByHex()` 读它。

  /**
   * ★★ <b>D-032 P1：population-only 窄写回</b>（app 在 {@code stepper.step(day)} <b>之前</b>落 Social
   * 当日净人口变化）。
   *
   * <p>与五参全量写回同口径：null 键/值、0 delta、缺行、结果为负都在<b>写任何一行之前</b>具名 fail-closed；只更新 {@code
   * HouseholdEconomy.population}，<b>不</b>写 {@code FlowRow}、不删债、不碰劳动。
   *
   * <p>★ 为什么不能复用二参全量入口：二参入口在人口归零时会记“债务保留”（迁移口径）；P1 的死亡户绝必须等 step 后由 lifecycle-only
   * 写回统一处理一次，不能在日初先误报“保留”。
   */
  static void applyHouseholdPopulationDeltasOnlyInto(
      EconomySession session, Map<HouseholdId, Long> deltas) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(deltas, "deltas");
    if (deltas.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    long deltaSum = requireHouseholdPopulationDeltas(deltas, householdEconomies);
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      HouseholdEconomy row = householdEconomies.get(household);
      long nextPopulation = Math.addExact(row.population(), entry.getValue());
      householdEconomies.put(
          household, withPopulationAndLabor(row, nextPopulation, row.laborMilli()));
    }
    if (EconomyLog.population().isDebugEnabled()) {
      EventLog.channel(EconomyLog.population())
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_POPULATION_DELTAS_APPLIED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "households",
                  deltas.size(),
                  "deltaNet",
                  deltaSum,
                  "unit",
                  "person"));
    }
  }

  /** population-only 的校验（异常/日志与五参入口的 delta 段同字面）；返回 delta 总和。 */
  private static long requireHouseholdPopulationDeltas(
      Map<HouseholdId, Long> deltas, Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    long deltaSum = 0L;
    for (Map.Entry<HouseholdId, Long> entry : deltas.entrySet()) {
      HouseholdId household = entry.getKey();
      Long delta = entry.getValue();
      if (household == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-household-key"));
        throw new IllegalArgumentException("逐户人口变化含 null 家户键（Social 结算结果不得含 null）");
      }
      if (delta == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "null-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化的值不得为 null: household=" + household);
      }
      if (delta == 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "zero-delta",
                    "household",
                    household));
        throw new IllegalArgumentException("逐户人口变化不应含 0（只给非 0 净变化）: household=" + household);
      }
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "unknown-household-row",
                    "household",
                    household));
        throw new IllegalStateException(
            "逐户人口变化指向不存在的经济家户行（Social/Economy 投影不一致，拒绝静默丢弃）: " + household);
      }
      long nextPopulation = Math.addExact(row.population(), delta);
      if (nextPopulation < 0L) {
        EventLog.channel(EconomyLog.population())
            .error(
                LogEvent.of(
                    "HOUSEHOLD_POPULATION_DELTA_REJECTED",
                    EconomyLogSource.ECONOMY_POPULATION_WRITE,
                    "reason",
                    "negative-result",
                    "household",
                    household,
                    "population",
                    row.population(),
                    "delta",
                    delta,
                    "next",
                    nextPopulation));
        throw new IllegalStateException(
            "逐户人口变化后结果为负（拒绝落账）: household="
                + household
                + " population="
                + row.population()
                + " delta="
                + delta);
      }
      deltaSum = Math.addExact(deltaSum, delta);
    }
    return deltaSum;
  }

  /**
   * ★★★ <b>D-032 P1：lifecycle-only 窄写回</b> —— 在 {@code stepper.step(day)} <b>之后</b>，把本日 Social
   * 已结算出的出生/死亡原值补记到 {@code FlowRow}（只写 births/deaths），并在“人口已为 0 且 deaths &gt; 0”时执行一次户绝双向销债。
   *
   * <p>★ 不更新 population（population-only 已在 step 前完成）；不碰劳动、自然需求、其它流水字段；不重复记生死。调用方每 tick 只调用一次。
   */
  static void applyHouseholdLifecycleOnlyInto(
      EconomySession session,
      Map<HouseholdId, Long> birthsByHousehold,
      Map<HouseholdId, Long> deathsByHousehold,
      long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(birthsByHousehold, "birthsByHousehold");
    Objects.requireNonNull(deathsByHousehold, "deathsByHousehold");
    if (birthsByHousehold.isEmpty() && deathsByHousehold.isEmpty()) {
      return;
    }
    if (day < 0L) {
      throw new IllegalArgumentException(
          "HOUSEHOLD_VITAL_WRITEBACK 需要世界日（day >= 0），但调用方给的是 day="
              + day
              + "（births="
              + birthsByHousehold.size()
              + " 户, deaths="
              + deathsByHousehold.size()
              + " 户）");
    }
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies =
        session.sheet().householdEconomies();
    LinkedHashMap<HouseholdId, FlowRow> flows = session.flows();
    requireReportedVitalCounts(birthsByHousehold, "birthsByHousehold", householdEconomies);
    requireReportedVitalCounts(deathsByHousehold, "deathsByHousehold", householdEconomies);
    LinkedHashMap<HouseholdId, Long> extinctPopulationBefore = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId household = entry.getKey();
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      if (deaths <= 0L || entry.getValue().population() != 0L) {
        continue;
      }
      long births = birthsByHousehold.getOrDefault(household, 0L);
      long populationBefore = Math.subtractExact(deaths, births);
      if (populationBefore <= 0L) {
        throw new IllegalStateException(
            "户绝写回的死亡前人口必须 > 0（Social 生死事实与经济人口投影不一致）: household="
                + household
                + " births="
                + births
                + " deaths="
                + deaths
                + " population="
                + entry.getValue().population());
      }
      extinctPopulationBefore.put(household, populationBefore);
    }
    long reportedBirths = 0L;
    long reportedDeaths = 0L;
    long lifecycleRows = 0L;
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : householdEconomies.entrySet()) {
      HouseholdId household = entry.getKey();
      long births = birthsByHousehold.getOrDefault(household, 0L);
      long deaths = deathsByHousehold.getOrDefault(household, 0L);
      if (births == 0L && deaths == 0L) {
        continue;
      }
      flows.put(household, withLifecycle(flows.get(household), household, births, deaths));
      reportedBirths = Math.addExact(reportedBirths, births);
      reportedDeaths = Math.addExact(reportedDeaths, deaths);
      lifecycleRows++;
    }
    LogEvent vital =
        LogEvent.of(
            "HOUSEHOLD_VITAL_WRITEBACK",
            EconomyLogSource.ECONOMY_POPULATION_WRITE,
            "day",
            day,
            "households",
            lifecycleRows,
            "births",
            reportedBirths,
            "deaths",
            reportedDeaths,
            "unit",
            "person",
            "why",
            "w3-fix: social-is-population-authority; economy-receives-births-deaths-into-flowrow");
    if (reportedDeaths > 0L) {
      EventLog.channel(EconomyLog.population()).info(vital);
    } else if (EconomyLog.population().isDebugEnabled()) {
      EventLog.channel(EconomyLog.population()).debug(vital);
    }
    // 户绝判据只在这里执行一次：population 已在 step 前由 population-only 写成 Social 结算后的值。
    for (Map.Entry<HouseholdId, Long> entry : extinctPopulationBefore.entrySet()) {
      writeOffExtinctHouseholdDebts(
          session, entry.getKey(), entry.getValue(), deathsByHousehold.get(entry.getKey()), day);
    }
  }

  /**
   * ★★ <b>{@code FlowRow.births}/{@code FlowRow.deaths} 的唯一写口</b>：把**外部（社会，人口权威）报来的**出生/死亡原值
   * 追加进一条流水行 ——**保留**该行本期的其它发生额（不覆盖）。
   *
   * <p>★★ <b>语义（W3-fix 2026-10-11）：接收方，不是判定方</b>：本方法**不产生**任何生死判定，{@code births}/{@code deaths}
   * 两个数由调用方（{@code VitalSettlementResult} 的逐户原值 / {@code LotChange} 的逐批次账）原样传来；经济侧也不在这里缩劳动、 不删债 ——
   * 它只把"社会说死了/生了多少"落进流水读数（口径见 {@link FlowRow#deaths()}）。
   *
   * <p>★ 流水行缺席（该行本期还没有任何发生额）⇒ 以一条全零的流水起步：{@code FlowRow} 的其余字段本来就有合法零值， 而"这一行这个月死了人"必须**读得出来**。
   *
   * <p>★ 累加口径 = 本周期累计（新周期第一天由 {@code settleOneDayInto} 整行归零，与 {@code unmetNeed} 同窗口）。
   */
  static FlowRow withLifecycle(FlowRow flow, HouseholdId key, long births, long deaths) {
    FlowRow base =
        flow == null
            ? new FlowRow(
                key, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, 0L, Map.of(), Map.of())
            : flow;
    return new FlowRow(
        base.key(),
        base.income(),
        base.consumed(),
        base.taxPaid(),
        base.interestDue(),
        base.newBorrowing(),
        base.repaid(),
        base.netSurplus(),
        base.unmetNeed(),
        base.deaths() + deaths,
        base.births() + births,
        base.repaidMoney(),
        base.capitalizedArrears());
  }

  /** 换人口与有效劳动（其余字段原样带过）。★ W3 起经济侧**没有**"判死"调用者了（那条路径已删）—— 仍服务迁移等路径。 */
  static HouseholdEconomy withPopulationAndLabor(
      HouseholdEconomy householdEconomy, long population, long laborMilli) {
    return new HouseholdEconomy(
        householdEconomy.id(),
        householdEconomy.view(),
        population,
        laborMilli,
        householdEconomy.participationPerMille(),
        householdEconomy.money(),
        householdEconomy.naturalNeeds(),
        householdEconomy.effectiveDemand(),
        householdEconomy.cycleNaturalNeedMilli());
  }

  /**
   * ★★ <b>M2.7：把周期累加器重置为"今天这一份"</b>（新周期第一天用）。
   *
   * <p>★ 为什么不是置 0：今天已经吃掉的这一份**属于新周期**（消费步按注入的 {@code naturalNeeds[grain]} 刚累加过）—— 置 0 会把新周期第一天的需要
   * 抹掉，整周期分母因此少一天。调用点必须用"最近结算日写下的 {@code naturalNeeds[grain]}"作为参数（同源，不另算）。
   */
  static HouseholdEconomy withCycleNaturalNeed(
      HouseholdEconomy householdEconomy, long cycleNaturalNeedMilli) {
    return new HouseholdEconomy(
        householdEconomy.id(),
        householdEconomy.view(),
        householdEconomy.population(),
        householdEconomy.laborMilli(),
        householdEconomy.participationPerMille(),
        householdEconomy.money(),
        householdEconomy.naturalNeeds(),
        householdEconomy.effectiveDemand(),
        cycleNaturalNeedMilli);
  }
}
