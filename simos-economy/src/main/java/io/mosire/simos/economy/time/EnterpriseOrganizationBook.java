package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.AllocationPlan;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.AssetGrant;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.GeneratedRelation;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.LotChoice;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.MODE_KEY_PREFIX;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.OrganizeResult;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.Outcome;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_ASSET;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_ASSET_RULE;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_IDLE_ASSET;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_INDUSTRY_TEMPLATE;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_INPUT;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_INPUT_NO_MARKET;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_LABOR;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_LABOR_LOT;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_NO_RENT_RULE;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_REFUSED;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_SCALE_OVERFLOW;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.REASON_UNIT_ID_TAKEN;
import static io.mosire.simos.economy.time.EconomyEnterpriseSettlement.TenancyPlan;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.applyGrants;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.assetKindKey;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.candidateOwnAssets;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.expectedCapacityAfterGrants;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.generateRelation;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.hasUnitInScope;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.idleSources;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.orgIdFallback;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.rentGroups;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.rentRules;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.shareIdsOf;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.shortage;
import static io.mosire.simos.economy.time.EnterpriseStatusBook.shortageResult;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.migrate.ProductionRoleResolver;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionEnterprise.Status;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRole.LaborRole;
import io.mosire.simos.economy.model.ProductionRole.RelationToMeans;
import io.mosire.simos.economy.model.ProductionRole.SurplusRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionCreationRequest;
import io.mosire.simos.production.contract.ProductionInstanceOutcome;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRecipe;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
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

/** E2 拆分 helper（由 EconomyEnterpriseSettlement 抽取；行为不变）。 */
final class EnterpriseOrganizationBook {

  private EnterpriseOrganizationBook() {}

  /** 一个"无约束"的规模（劳动/投入那一路不施加约束时用它；任何真实规模都远小于它）。 */
  static final long UNCONSTRAINED = Long.MAX_VALUE;

  /**
   * ★★ <b>本阶段入口</b>：{@code modes} 为空时<b>第一行就返回</b>（旧路径逐值不变的全部保证在这里）。
   *
   * @param base 结算前的不可变状态（读
   *     modes/classStructures/classPositions/classStandings/assetRules/industries）
   * @param classStandings 家户阶层归属视图（E6a：模式变迁在同一日刚写过的工作副本优先；无变迁时 = {@code base.classStandings()}）
   * @param rows 家户工作副本（只读本阶段；键 = 稳定身份）
   * @param industries 产业模板（只读；本阶段不新建模板）
   * @param units 生产单元工作副本（可能被 upsert）
   * @param relations 生产关系工作副本（可能被 upsert）
   * @param assetShares 实物资产份额工作副本（可能被租佃拆分：只改 operator/kind，总量不变）
   * @param allocations 劳动配额工作副本（可能被 upsert）
   * @param composition 家户人口组成的只读投影（{@code household → (lot → count)}；来自 Social，不是经济状态）
   * @param householdGoods 家户商品账会话副本（只读；判投入约束）
   * @param markets 市场表（只读；只用于"缺投入时有没有市场"的具名区分）
   * @param enterprises 生产组织工作副本（upsert）
   * @return 是否写了状态 + 今天新建的 unit id
   */
  static Outcome organize(
      ProductionRuntime runtime,
      EconomyDayView base,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      MarketIndex markets,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      LinkedHashMap<ProductionUnitId, ProductionRoster> productionRosters) {
    Objects.requireNonNull(runtime, "runtime（实例建档的唯一权威口，B2b 起必填）");
    Objects.requireNonNull(base, "base");
    if (base.modes().isEmpty()) {
      return Outcome.empty(); // ★★ 闸门：旧档/未接线世界完全不执行本阶段
    }
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(classMemberships, "classStandings");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(composition, "composition");
    Objects.requireNonNull(householdGoods, "householdGoods");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(productionRosters, "productionRosters（C2：名册工作副本）");

    boolean changed = false;
    Set<ProductionUnitId> created = new LinkedHashSet<>();

    // 家户 → **本 tick 的有效参与位置集合**（P2-B §13.5：一家户可参与多个生产方式/生产位置）。
    //   ★ 口径：HouseholdClassMembership.effectivePositionIds() = {currentPositionId} ∪
    // participatingPositionIds；
    //     没有 standing 的旧档退回 ProductionRoleResolver.resolveCurrent（旧单值口径），逐值不变。
    Map<HouseholdId, List<ClassPositionId>> positionsByHousehold = new LinkedHashMap<>();
    List<HouseholdId> orderedHouseholds = new ArrayList<>(householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : orderedHouseholds) {
      positionsByHousehold.put(household, effectivePositions(base, classMemberships, household));
    }

    // (mode, assetKind) → AssetRule（EconomyData 已判同一组合唯一）；按 id 升序取，重复不可能。
    Map<String, AssetRule> assetRuleByModeKind = new LinkedHashMap<>();
    List<AssetRule> orderedRules = new ArrayList<>(base.assetRules().values());
    orderedRules.sort(Comparator.comparing(rule -> rule.id().value()));
    for (AssetRule rule : orderedRules) {
      assetRuleByModeKind.put(assetKindKey(rule), rule);
    }

    List<ProductionModeDefinitionView> orderedModes = new ArrayList<>(base.modes().values());
    orderedModes.sort(Comparator.comparing(mode -> mode.id().value()));
    for (ProductionModeDefinitionView mode : orderedModes) {
      if (DefaultProductionModes.DISPLACED.equals(mode.id())) {
        // ★★ P11.1 / D-023 #6：流民没有工作、不得从 DISPLACED 池主动招募 —— 自动组织不得为
        //    displaced 位置建 unit / 关系 / 劳动配额，也不得让已有的 displaced 位置被"重新雇佣"。
        continue;
      }
      var structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        continue; // 不完整状态：没有阶层结构就没有可组织的"位置"（不猜默认结构，报告里如实记）
      }
      List<ClassPositionId> orderedPositions = new ArrayList<>(structure.positions().keySet());
      orderedPositions.sort(Comparator.comparing(ClassPositionId::value));
      // ★★ P1：本 mode 下"自己会生产"的家户集合。它们的 OWNED 份额是自家组织的生产资料，
      //   不能被别家的租佃计划当闲置份额拆走 —— 否则先建的组织会在同一天被后建的租佃拆空，
      //   到 revision 边界留下指向已删除份额的 assetSources（实测的构造期守卫失败）。
      Set<ActorRef> reservedHouseholdOwners = new LinkedHashSet<>();
      for (ClassPositionId positionId : orderedPositions) {
        ProductionRole position = structure.positions().get(positionId);
        if (position == null || !shouldProduce(position)) {
          continue;
        }
        for (HouseholdId household : orderedHouseholds) {
          if (!positionsByHousehold.getOrDefault(household, List.of()).contains(positionId)) {
            continue;
          }
          HouseholdEconomy householdEconomy = householdEconomies.get(household);
          if (householdEconomy != null && householdEconomy.population() > 0L) {
            reservedHouseholdOwners.add(HouseholdActors.of(household));
          }
        }
      }
      for (ClassPositionId positionId : orderedPositions) {
        ProductionRole position = structure.positions().get(positionId);
        if (position == null || !shouldProduce(position)) {
          continue;
        }
        for (HouseholdId household : orderedHouseholds) {
          if (!positionsByHousehold.getOrDefault(household, List.of()).contains(positionId)) {
            continue;
          }
          HouseholdEconomy householdEconomy = householdEconomies.get(household);
          if (householdEconomy == null || householdEconomy.population() <= 0L) {
            continue; // 空行/无人口：不是"应当生产的阶层"，也没有可组织的人
          }
          HexCoord hex = householdEconomy.view().hex();
          String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
          ProductionOrganizationId orgId = null;
          try {
            orgId = ProductionOrganizationId.idOf(mode.id(), positionId, household, hexKey);
            ProductionEnterprise existing = enterprises.get(orgId);
            if (existing != null && existing.status() == Status.EXITING) {
              // ★★ E6a：模式变迁把旧 mode 的组织钉在 EXITING —— 该位置即使仍有家户停留（retain=1000），
              //   也不得被自动组织阶段重建/覆盖成 SHORTAGE/ACTIVE（旧 unit 已改挂新 mode，重组织必然失败）。
              continue;
            }
            if (existing != null
                && existing.status() == Status.ACTIVE
                && existing.unitId().isPresent()
                && units.containsKey(existing.unitId().get())) {
              continue; // 已激活：逐日幂等（组织指向的 unit 仍在）
            }
            OrganizeResult result =
                organizeOne(
                    runtime,
                    assetRuleByModeKind,
                    mode,
                    position,
                    positionId,
                    household,
                    householdEconomy,
                    hex,
                    hexKey,
                    orgId,
                    reservedHouseholdOwners,
                    householdEconomies,
                    industries,
                    units,
                    relations,
                    assetShares,
                    laborCommitments,
                    composition,
                    householdGoods,
                    markets,
                    productionRosters);
            ProductionEnterprise previous = enterprises.put(orgId, result.enterprise());
            if (!result.enterprise().equals(previous)) {
              changed = true;
            }
            if (result.unitCreated()) {
              created.add(result.enterprise().unitId().orElseThrow());
            }
          } catch (IllegalArgumentException e) {
            // 坏 id/坏模板：不半建、不静默 —— 落一条具名 SHORTAGE（状态里能看到原因，读口/报告可追溯）。
            ProductionEnterprise refused =
                shortage(
                    orgId != null ? orgId : orgIdFallback(mode, positionId, household, hexKey),
                    mode,
                    positionId,
                    household,
                    List.of(),
                    REASON_REFUSED + ":" + e.getMessage());
            if (!refused.equals(enterprises.put(refused.id(), refused))) {
              changed = true;
            }
          }
        }
      }
    }
    return new Outcome(changed, created);
  }

  /**
   * ★★ <b>一个 (mode, position, household) 的组织决策</b>：先"采用已有 unit / 建新 unit"两路，再逐个把约束判死； 全部通过才返回
   * ACTIVE，否则返回具名 SHORTAGE。plan 阶段只读，apply 阶段才写工作副本。
   */
  static OrganizeResult organizeOne(
      ProductionRuntime runtime,
      Map<String, AssetRule> assetRuleByModeKind,
      ProductionModeDefinitionView mode,
      ProductionRole position,
      ClassPositionId positionId,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      HexCoord hex,
      String hexKey,
      ProductionOrganizationId orgId,
      Set<ActorRef> reservedHouseholdOwners,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      MarketIndex markets,
      LinkedHashMap<ProductionUnitId, ProductionRoster> productionRosters) {
    ActorRef organizer = HouseholdActors.of(household);
    String modeKey = MODE_KEY_PREFIX + mode.id().value();
    List<IndustryId> candidates = IndustryHexKeys.at(industries, hex.q(), hex.r());
    if (candidates.isEmpty()) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          List.of(),
          REASON_NO_INDUSTRY_TEMPLATE + ":" + hexKey);
    }
    Optional<IndustryId> selected =
        selectIndustry(industries, candidates, position, modeKey, organizer, units);
    if (selected.isEmpty()) {
      return shortageResult(
          orgId, mode, positionId, household, List.of(), REASON_UNIT_ID_TAKEN + ":" + hexKey);
    }
    IndustryId industryId = selected.get();
    IndustryDefinitionView industry = industries.get(industryId);
    ProductionUnitId unitId = ProductionUnitId.idOf(industryId, organizer);
    ProductionProcess existingUnit = units.get(unitId);

    // ── 路由 ①：采用已有 unit（本 mode 之前建的，或旧档/播种的 preset unit）────────────────
    if (existingUnit != null) {
      boolean ours = existingUnit.modeKey().equals(modeKey);
      boolean preset = existingUnit.modeKey().equals(existingUnit.industry().value());
      if (ours || preset) {
        ProductionRules relation = relations.get(unitId);
        // ★★★ D-032 C2（D2 §5.4）：采用已有 unit 时，单角色 plan 且 rules 空 ⇒ 补一条默认名册
        //   （seed 已建好实例、自动组织在第一次推进时接管的路径也由此得到名册）。
        if (!productionRosters.containsKey(unitId) && industry.roles().size() == 1) {
          boolean rulesEmpty = relation == null || relation.rules().isEmpty();
          if (rulesEmpty) {
            long remaining =
                Math.max(
                    0L,
                    householdEconomy.laborMilli()
                        - ProductionRosters.plannedLaborOf(productionRosters, household));
            Optional<ProductionRoster> defaultRoster =
                ProductionRosters.defaultSingleHouseholdRoster(
                    unitId, industry, household, remaining);
            if (defaultRoster.isEmpty()) {
              return shortageResult(
                  orgId,
                  mode,
                  positionId,
                  household,
                  shareIdsOf(industryId, organizer, assetShares),
                  "roster-required:" + unitId.value());
            }
            productionRosters.put(unitId, defaultRoster.get());
          }
        }
        List<Payee> inputSources = relation == null ? List.of() : List.of(relation.inputSupplier());
        Payee outputOwnership =
            relation == null
                ? new Payee.ToActor(organizer)
                : new Payee.ToActor(relation.residualOwner());
        List<HouseholdId> laborSources =
            productionRosters.containsKey(unitId)
                ? productionRosters.get(unitId).households()
                : List.of(household);
        return new OrganizeResult(
            new ProductionEnterprise(
                orgId,
                mode.id(),
                positionId,
                Optional.of(unitId),
                organizer,
                laborSources,
                shareIdsOf(industryId, organizer, assetShares),
                inputSources,
                outputOwnership,
                Optional.of("existing:" + unitId.value()),
                Status.ACTIVE,
                ""),
            false);
      }
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          REASON_UNIT_ID_TAKEN + ":" + unitId.value());
    }

    // ★★ D4：同一 (industry, operator) 已有别的 unit ⇒ 候选不在同一 revision 预算里；必须补显式占用请求，
    //   不得自动创建第二个同 scope unit 或回退整池份额。
    if (hasUnitInScope(units, industryId, organizer)) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          "asset-occupancy-request-missing:" + unitId.value());
    }

    // ★★★ D-032 D2 §5.4 / I16：多角色 plan 没有显式名册 ⇒ 具名 `roster-required`，不得按角色各建一份组织，
    //   也不得伪造 placeholder 工资工人。
    if (industry.roles().size() > 1 && !productionRosters.containsKey(unitId)) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          "roster-required:" + unitId.value());
    }
    // ── 路由 ②：计划一条新 unit（只读；任一路不满 ⇒ 具名 SHORTAGE）──────────────────────────
    ProductionRecipe recipe = industry.recipe();
    long disposableLabor = disposableLabor(household, householdEconomy, laborCommitments);
    LotChoice lotChoice = null;
    if (recipe.laborPerUnit() > 0L) {
      lotChoice = chooseLot(household, laborCommitments, composition, householdEconomies);
      if (lotChoice == null) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR_LOT + ":" + household.value());
      }
    }
    long laborBudget = lotChoice == null ? 0L : Math.min(disposableLabor, lotChoice.room());
    long laborScale;
    if (recipe.laborPerUnit() <= 0L) {
      laborScale = UNCONSTRAINED;
    } else {
      laborScale = laborBudget / recipe.laborPerUnit();
      if (laborScale < 1L) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR
                + ":household="
                + household.value()
                + ",budget="
                + laborBudget
                + ",need="
                + recipe.laborPerUnit());
      }
    }
    Map<CommodityId, Long> stock = householdGoods.getOrDefault(household, Map.of());
    long inputScale = UNCONSTRAINED;
    String inputReason = null;
    for (Map.Entry<CommodityId, Long> entry : recipe.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long available = stock.getOrDefault(entry.getKey(), 0L);
      long scale = available / entry.getValue();
      if (scale < 1L && inputReason == null) {
        inputReason =
            (markets.at(hex) != null ? REASON_NO_INPUT : REASON_NO_INPUT_NO_MARKET)
                + ":commodity="
                + entry.getKey().value()
                + ",available="
                + available
                + ",need="
                + entry.getValue();
      }
      inputScale = Math.min(inputScale, scale);
    }
    if (inputScale < 1L) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          inputReason == null ? REASON_NO_INPUT + ":unknown" : inputReason);
    }
    long target = Math.min(laborScale, inputScale);
    // 候选尚未进入 D4 预算：这里只派生候选计划的自有份额表（非资产计划规模已由 labor/input 夹出）。
    // 运行时既有 unit 的资产读口仍只走 AssetOccupancyBudget allocation。
    Map<AssetKind, Long> usable = candidateOwnAssets(industryId, organizer, assetShares);
    long ownCapacity = ProductionProcessBook.capacityScaleOf(usable, industry);
    // ★★ P2-E：**已被某个生产 unit 使用的 (industry, operator) 作用域** —— 该作用域下的 OWNED 份额
    //   是那条 unit 的产能来源，不是"闲置可租"的份额（否则自动组织会把别人正在用的 unit 拆空）。
    Set<ActorRef> operatorsWithUnit = new LinkedHashSet<>();
    for (ProductionProcess candidate : units.values()) {
      if (candidate.industry().equals(industryId)) {
        operatorsWithUnit.add(candidate.operator());
      }
    }
    // 资产天花板 = 自有 + 同产业全部可租闲置份额（"自家会生产"的家户份额除外）；目标规模不超过它。
    long assetCeiling =
        assetCeiling(
            industry, organizer, usable, assetShares, reservedHouseholdOwners, operatorsWithUnit);
    long desired;
    if (target == UNCONSTRAINED) {
      desired = Math.max(1L, Math.min(Math.max(ownCapacity, 0L), assetCeiling));
    } else {
      desired = Math.max(1L, Math.min(target, assetCeiling));
    }

    TenancyPlan tenancy =
        ownCapacity < desired
            ? planTenancy(
                assetRuleByModeKind,
                mode,
                industry,
                organizer,
                usable,
                desired,
                assetShares,
                reservedHouseholdOwners,
                operatorsWithUnit)
            : new TenancyPlan(List.of(), List.of());
    long expectedCapacity = expectedCapacityAfterGrants(industry, usable, tenancy.grants());
    if (expectedCapacity < 1L) {
      String reason =
          tenancy.reasons().isEmpty()
              ? REASON_NO_ASSET + ":" + industryId.value()
              : String.join(",", tenancy.reasons());
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          reason);
    }
    long scale = Math.min(expectedCapacity, target == UNCONSTRAINED ? expectedCapacity : target);
    if (scale < 1L) {
      return shortageResult(
          orgId,
          mode,
          positionId,
          household,
          shareIdsOf(industryId, organizer, assetShares),
          REASON_NO_ASSET + ":" + industryId.value());
    }
    List<AssetGrant> grants = new ArrayList<>(tenancy.grants());

    GeneratedRelation generated =
        generateRelation(
            mode,
            position,
            industry,
            industryId,
            unitId,
            organizer,
            householdEconomy,
            householdEconomies);
    List<CompensationRule> rules = new ArrayList<>(generated.relation().rules());
    for (AssetGrant grant : rentGroups(grants)) {
      AssetRule rule = assetRuleByModeKind.get(assetKindKey(mode.id().value(), grant.asset()));
      RentRule rentRule = rule == null ? null : rule.rentRule().orElse(null);
      if (rentRule == null) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_RENT_RULE + ":" + grant.asset().name());
      }
      rules.addAll(rentRules(rentRule, new Payee.ToActor(grant.owner()), industry, mode));
    }
    ProductionRules relation =
        new ProductionRules(
            unitId,
            organizer,
            generated.relation().inputSupplier(),
            rules,
            generated.relation().residualOwner(),
            generated.relation().laborSource());

    // ★★★ D-032 D2 §5.4：单角色 + `rules` 空 ⇒ 建 unit 时同批生成默认名册。
    //   remaining = laborMilli − Σ现有名册.plannedLabor；remaining<=0 或标准日劳动为 0 ⇒ `roster-required`。
    Optional<ProductionRoster> defaultRoster = Optional.empty();
    if (industry.roles().size() == 1 && relation.rules().isEmpty()) {
      long remaining =
          Math.max(
              0L,
              householdEconomy.laborMilli()
                  - ProductionRosters.plannedLaborOf(productionRosters, household));
      defaultRoster =
          ProductionRosters.defaultSingleHouseholdRoster(unitId, industry, household, remaining);
      if (defaultRoster.isEmpty()) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            "roster-required:" + unitId.value());
      }
    }

    Optional<AllocationPlan> allocation = Optional.empty();
    if (industry.roles().size() == 1 && recipe.laborPerUnit() > 0L) {
      long amount = Math.min(scale * recipe.laborPerUnit(), laborBudget);
      if (amount < recipe.laborPerUnit()) {
        return shortageResult(
            orgId,
            mode,
            positionId,
            household,
            shareIdsOf(industryId, organizer, assetShares),
            REASON_NO_LABOR + ":" + household.value());
      }
      // ★★ P2-A A4：period 只是审计标签（供给表已删除）；批次有效性已由 chooseLot 的组成投影保证。
      allocation = Optional.of(new AllocationPlan(lotChoice.lot(), amount, 1L));
    }

    // ── apply：全部可失败判断已在上面做完 ──────────────────────────────────────────────
    // ★★ B2b（§13.11.4 第 4 条）：实例建档**直调 production 契约口**（`createInstance`），
    //   economy 侧不再有任何实例工作副本/写回桥（`unitsWorkCopy` 已删）。
    //   ★ 次序是刻意的：建档放在 {@link #applyGrants} **之前** —— 被拒时当场 ERROR 抛出（跨域不一致不降级），
    //     此时份额一行未写（零部分应用）。而 `units` 参数现在是 production 的**只读投影**（读得见刚建的实例）。
    ProductionInstanceOutcome created =
        runtime.createInstance(
            new ProductionCreationRequest(
                industryId,
                unitId,
                // ★★★ D-032 B3（§13.8 裁定二第 1/3/4 条）：同上 —— 实例必须有真实地点，解不出即契约故障
                //   ERROR 不降级（industryId 取自 `IndustryHexKeys.at(..., hex)` ⇒ 正常路径必命中）。
                io.mosire.simos.production.contract.ProductionLocation.ofPlan(industryId)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "location-unknown: 生产组织新建实例的产业 id 不带格段（地点无从落定，"
                                    + "跨切片不一致，不降级）: unit="
                                    + unitId.value()
                                    + " industry="
                                    + industryId.value()
                                    + " mode="
                                    + modeKey)),
                organizer,
                modeKey));
    if (created instanceof ProductionInstanceOutcome.Rejected rejected) {
      // ★ 经济侧判定"可以建"、生产侧说建不了 = 跨切片不一致 ⇒ ERROR 不降级（I15 / §13.2 ①④），
      //   且**不**继续写 relations / allocations / grants（拒绝整个候选提交，不留半提交）。
      EventLog.channel(EconomyLog.enterprise())
          .error(
              LogEvent.of(
                  "PRODUCTION_ENTERPRISE_UNIT_CREATE_REJECTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "unit",
                  unitId.value(),
                  "plan",
                  industryId.value(),
                  "reason",
                  rejected.reason()));
      throw new IllegalStateException(
          "自动组织建档被 production 拒绝（跨切片不一致，不降级）: unit="
              + unitId.value()
              + " plan="
              + industryId.value()
              + " reason="
              + rejected.reason());
    }
    applyGrants(grants, organizer, industries, assetShares);
    relations.put(unitId, relation);
    // ★★★ D-032 D2 §5.6：生产承诺带角色、id 走 idOfProduction（单角色 plan 的角色 = roles[0]）。
    Optional<io.mosire.simos.economy.api.production.ProductionRoleId> allocationRole =
        industry.roles().size() == 1 ? Optional.of(industry.roles().get(0).id()) : Optional.empty();
    allocation.ifPresent(
        plan ->
            allocationRole.ifPresent(
                role -> {
                  LaborAllocationId allocationId =
                      HouseholdLaborCommitment.idOfProduction(unitId, role, plan.lot(), household);
                  laborCommitments.put(
                      allocationId,
                      new HouseholdLaborCommitment(
                          allocationId,
                          plan.lot(),
                          household,
                          organizer,
                          unitId.value(),
                          plan.laborMilli(),
                          plan.period(),
                          io.mosire.simos.economy.api.labor.LaborCommitmentKind.PRODUCTION,
                          Optional.of(role)));
                }));
    // 默认名册在实例创建成功、关系/配额都通过之后落工作副本（零半状态）。
    defaultRoster.ifPresent(roster -> productionRosters.put(unitId, roster));
    return new OrganizeResult(
        new ProductionEnterprise(
            orgId,
            mode.id(),
            positionId,
            Optional.of(unitId),
            organizer,
            productionRosters.containsKey(unitId)
                ? productionRosters.get(unitId).households()
                : List.of(household),
            shareIdsOf(industryId, organizer, assetShares),
            List.of(relation.inputSupplier()),
            new Payee.ToActor(relation.residualOwner()),
            Optional.of(generated.templateRef()),
            Status.ACTIVE,
            ""),
        true);
  }

  // ── 位置/产业/批次选择 ─────────────────────────────────────────────────────────────────

  /** 应当生产的位置：有劳动角色且不是纯被供养者；地主（NONE）与官署（NONE）不组织。 */
  static boolean shouldProduce(ProductionRole position) {
    return position.laborRole() != LaborRole.NONE
        && position.surplusRole() != SurplusRole.DEPENDENT;
  }

  /**
   * ★★ <b>P2-B：本户的有效参与位置集合</b>（多生产方式的唯一读口）。有 {@code HouseholdClassMembership} ⇒ {@link
   * HouseholdClassMembership#effectivePositionIds()}（当前位置 ∪ 追加集合，按 id 升序）；无 standing 的旧档 ⇒ 退回
   * {@link ProductionRoleResolver#resolveCurrent} 的单值旧口径。位置自身的合法性（已存在、mode 已存在） 由 {@code
   * EconomyData} 构造期判死，本方法只读。
   */
  static List<ClassPositionId> effectivePositions(
      EconomyDayView base,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      HouseholdId household) {
    HouseholdClassMembership classMembership = classMemberships.get(household);
    if (classMembership != null) {
      return classMembership.effectivePositionIds();
    }
    return ProductionRoleResolver.resolveCurrent(base, household).map(List::of).orElseGet(List::of);
  }

  /** 位置角色 → 优先 regime（E2 的产业模板选择启发式；只是"先试哪一个"，不是规则权威）。 */
  static String preferredRegime(ProductionRole position) {
    // ★★ P11.7 / D-024：merchant 位置优先 regime==merchant 的产业模板（trade）；没有才回退下面的现有顺序。
    if (DefaultProductionModes.MERCHANT.equals(position.modeId())) {
      return RegimeOperators.MERCHANT;
    }
    if (position.surplusRole() == SurplusRole.SELF_SUBSISTENCE) {
      return RegimeOperators.HOUSEHOLD;
    }
    if (position.surplusRole() == SurplusRole.WAGE_EARNER) {
      return position.relationToMeans() == RelationToMeans.DIRECT_LABORER
          ? RegimeOperators.TENANT
          : RegimeOperators.HANDICRAFT;
    }
    return RegimeOperators.FEUDAL;
  }

  /**
   * 从该格候选产业里选一个"这个位置可以用"的：regime 匹配的排在前面（同组按 id 升序），其余按 id 升序； 取第一个 unit id 空闲/可采用的。全被别的组织占用 ⇒
   * 空（调用方落具名 SHORTAGE）。
   */
  static Optional<IndustryId> selectIndustry(
      Map<IndustryId, IndustryDefinitionView> industries,
      List<IndustryId> candidates,
      ProductionRole position,
      String modeKey,
      ActorRef organizer,
      Map<ProductionUnitId, ProductionProcess> units) {
    String wanted = preferredRegime(position);
    List<IndustryId> preferred = new ArrayList<>();
    List<IndustryId> rest = new ArrayList<>();
    for (IndustryId id : candidates) {
      IndustryDefinitionView industry = industries.get(id);
      if (industry != null && industry.regime().value().equals(wanted)) {
        preferred.add(id);
      } else {
        rest.add(id);
      }
    }
    preferred.sort(Comparator.comparing(IndustryId::value));
    rest.sort(Comparator.comparing(IndustryId::value));
    List<IndustryId> ordered = new ArrayList<>(preferred);
    ordered.addAll(rest);
    for (IndustryId industryId : ordered) {
      ProductionUnitId unitId = ProductionUnitId.idOf(industryId, organizer);
      ProductionProcess existing = units.get(unitId);
      if (existing == null
          || existing.modeKey().equals(modeKey)
          || existing.modeKey().equals(existing.industry().value())) {
        return Optional.of(industryId);
      }
    }
    return Optional.empty();
  }

  /** 可支配时间（毫小时/tick）= 行时间预算（{@code HouseholdEconomy.laborMilli}）− 该户已承诺的全部配额；不除零、不为负。 */
  static long disposableLabor(
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    long allocated = 0L;
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.household().equals(household)) {
        allocated += laborCommitment.laborMilli();
      }
    }
    return Math.max(0L, householdEconomy.laborMilli() - allocated);
  }

  /**
   * 选批次：该户已有配额用过的批次 + 该户成员份额指向的批次，按批次 id 升序逐个算余量 （{@code LaborSupply.availableLabor() −
   * 该批次全部已分配}），取余量最大者；并列取 id 最小者。没有 ⇒ 空。
   */
  static LotChoice chooseLot(
      HouseholdId household,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Set<PeopleLotId> lots = new LinkedHashSet<>();
    for (HouseholdLaborCommitment laborCommitment : laborCommitments.values()) {
      if (laborCommitment.household().equals(household)) {
        lots.add(laborCommitment.group());
      }
    }
    // ★ P2-A A3：家户人口组成来自 Social 的只读投影（不是 Economy 状态里的成员份额表）。
    for (Map.Entry<PeopleLotId, Long> member :
        composition.getOrDefault(household, Map.of()).entrySet()) {
      if (member.getValue() != null && member.getValue() > 0L) {
        lots.add(member.getKey());
      }
    }
    List<PeopleLotId> ordered = new ArrayList<>(lots);
    ordered.sort(Comparator.comparing(PeopleLotId::value));
    // ★★ P2-A A4：余量 = **家户时间预算** − 该户已承诺的全部配额（不再有批次级供给这第二权威）；
    //   批次只决定这笔时间记在哪个 lot 名下 ⇒ 取 id 最小的可用批次。
    long room = disposableLabor(household, householdEconomies.get(household), laborCommitments);
    return ordered.isEmpty() || room <= 0L ? null : new LotChoice(ordered.get(0), room);
  }

  // ── 租佃计划 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 自有资产不够目标规模时，用 {@link AssetRule#rentRule()} 从闲置份额里拆 TENANCY。返回 null = 计划可行 （grants 已填）；非 null =
   * 具名缺口原因。只读：不改任何份额。
   *
   * <p>★ <b>部分可拆不是失败</b>：能拆多少拆多少，调用方按"拆分后的产能规模"缩产；只有缩到 {@code < 1} 时， 返回的 {@link
   * TenancyPlan#reasons()} 才被拼进 SHORTAGE 原因（缺规则/无闲置份额各是一条具名原因）。
   */
  static TenancyPlan planTenancy(
      Map<String, AssetRule> assetRuleByModeKind,
      ProductionModeDefinitionView mode,
      IndustryDefinitionView industry,
      ActorRef organizer,
      Map<AssetKind, Long> usable,
      long desiredScale,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<ActorRef> reservedHouseholdOwners,
      Set<ActorRef> operatorsWithUnit) {
    List<AssetGrant> grants = new ArrayList<>();
    // 缺资产那几路按 AssetKind.name() 稳定序处理（同一产业内的资产种类集合来自模板）。
    List<AssetKind> required = new ArrayList<>(industry.capacityPerUnit().keySet());
    required.sort(Comparator.comparing(AssetKind::name));
    List<String> reasons = new ArrayList<>();
    for (AssetKind assetKind : required) {
      long perUnit = industry.capacityPerUnit().get(assetKind);
      if (perUnit <= 0L) {
        continue;
      }
      if (desiredScale > Long.MAX_VALUE / perUnit) {
        reasons.add(REASON_SCALE_OVERFLOW + ":" + assetKind.name());
        continue;
      }
      long need = desiredScale * perUnit;
      long have = usable.getOrDefault(assetKind, 0L);
      long deficit = need - have;
      if (deficit <= 0L) {
        continue;
      }
      AssetRule rule = assetRuleByModeKind.get(assetKindKey(mode.id().value(), assetKind));
      if (rule == null) {
        reasons.add(REASON_NO_ASSET_RULE + ":" + assetKind.name());
        continue;
      }
      if (rule.rentRule().isEmpty()) {
        reasons.add(REASON_NO_RENT_RULE + ":" + assetKind.name());
        continue;
      }
      long taken = 0L;
      for (OwnershipStake source :
          idleSources(
              industry.id(),
              assetKind,
              organizer,
              assetShares,
              reservedHouseholdOwners,
              operatorsWithUnit)) {
        long give = Math.min(deficit - taken, source.quantity());
        if (give <= 0L) {
          continue;
        }
        grants.add(new AssetGrant(source.id(), assetKind, give, source.owner()));
        taken += give;
        if (taken >= deficit) {
          break;
        }
      }
      if (taken < deficit) {
        reasons.add(
            REASON_NO_IDLE_ASSET
                + ":"
                + assetKind.name()
                + "(need="
                + deficit
                + ",got="
                + taken
                + ")");
      }
    }
    return new TenancyPlan(grants, reasons);
  }

  /** 自有 + 同产业闲置份额能支撑的规模天花板（只读；用于把 desired 限在真实资产上界内、避免溢出）。 */
  static long assetCeiling(
      IndustryDefinitionView industry,
      ActorRef organizer,
      Map<AssetKind, Long> usable,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<ActorRef> reservedHouseholdOwners,
      Set<ActorRef> operatorsWithUnit) {
    long ceiling = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      AssetKind assetKind = entry.getKey();
      long available = usable.getOrDefault(assetKind, 0L);
      for (OwnershipStake source :
          idleSources(
              industry.id(),
              assetKind,
              organizer,
              assetShares,
              reservedHouseholdOwners,
              operatorsWithUnit)) {
        available = saturatingAdd(available, source.quantity());
      }
      ceiling = Math.min(ceiling, available / entry.getValue());
    }
    return ceiling == Long.MAX_VALUE ? 0L : ceiling;
  }

  /** 饱和加法（{@code Long.MAX_VALUE} 为上限；只用于"天花板"这类只关心是否足够大的量）。 */
  static long saturatingAdd(long left, long right) {
    long sum = left + right;
    return sum < 0L ? Long.MAX_VALUE : sum;
  }
}
