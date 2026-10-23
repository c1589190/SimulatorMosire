package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MerchantProspectBook.merchantProspect;
import static io.mosire.simos.economy.time.ProspectArithmetic.askValuesByCommodity;
import static io.mosire.simos.economy.time.ProspectArithmetic.bidValuesByCommodity;
import static io.mosire.simos.economy.time.ProspectArithmetic.demandCapped;
import static io.mosire.simos.economy.time.ProspectArithmetic.infeasible;
import static io.mosire.simos.economy.time.ProspectArithmetic.joinReasons;
import static io.mosire.simos.economy.time.ProspectArithmetic.laborCostMilli;
import static io.mosire.simos.economy.time.ProspectArithmetic.obligationsOf;
import static io.mosire.simos.economy.time.ProspectArithmetic.relationFor;
import static io.mosire.simos.economy.time.ProspectArithmetic.scaledNetPerLabor;
import static io.mosire.simos.economy.time.ProspectArithmetic.sumValues;
import static io.mosire.simos.economy.time.WageProspectBook.estimateEmployerWageCost;
import static io.mosire.simos.economy.time.WageProspectBook.wageProspect;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.time.ExpectedProfitBook.Prospect;
import io.mosire.simos.economy.time.ProspectArithmetic.Obligations;
import io.mosire.simos.economy.time.WageProspectBook.WageEstimate;
import io.mosire.simos.economy.time.WageProspectBook.WageTermsSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ProductionProspectBook {
  private ProductionProspectBook() {}

  /** 产业 id 的格键 → {@link HexCoord}（规范形状才返回；否则 null）。 */
  static HexCoord industryHex(IndustryId industryId) {
    return IndustryHexKeys.hexKeyOf(industryId).map(HexCoord::parse).orElse(null);
  }

  /** ★★★ D4-Ib2a：调用方已持有同一 revision 的唯一占用预算时走本重载，不二次构造。 */
  static Prospect prospect(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      HexCoord hex,
      IndustryDefinitionView industryOrNull,
      MarketTable marketOrNull,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTopology topology,
      long day,
      ClassPositionId positionOrNull,
      ProductionRuntime runtime,
      AssetOccupancyBudget assetBudget) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(demand, "demand");
    Objects.requireNonNull(shares, "shares");
    Objects.requireNonNull(
        claimedByEnterprises, "claimedByOrganizations（必须由调用方用当天工作副本 organizations 算出，不得传 null）");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(runtime, "runtime（§15.12 B：物理量必须取自 production 的试算）");
    Objects.requireNonNull(assetBudget, "assetBudget 不得为 null（D4 预算不可用时必须 fail-closed）");
    // ★★★ D-032 P4c3（W1）：工资报价只从 U20 锚快照取；目标走标准劳动/周期天数的工程默认。
    //   缺报价/缺目标一律具名，绝不按 0 静默或回读旧表。
    WageTermsSource wageTermsSource = WageTermsSource.of(base);
    if (day < 0L) {
      throw new IllegalArgumentException("ExpectedProfitBook.prospect 的 day 不得为负: " + day);
    }
    ProductionModeDefinitionView mode = base.modes().get(modeId);
    if (mode == null) {
      return infeasible(household, modeId, null, hex, null, "NO_MODE");
    }
    ClassStructure structure = base.classStructures().get(mode.classStructureId());
    if (structure == null) {
      return infeasible(household, modeId, null, hex, null, "NO_STRUCTURE");
    }
    List<ProductionRole> producing = producingPositions(structure);
    if (producing.isEmpty()) {
      return infeasible(household, modeId, null, hex, null, "NO_POSITION");
    }
    // ★ 当前户在本 mode 的位置若是纯收租/被供养位置（landlord / destitute），不进入生产候选（E2 的 shouldProduce 同口径）：
    //   它没有"生产净收益"可算（租金收入本批不建模）⇒ 具名不可行、net=0；A 规则不会被它触发。
    ProductionRole currentPosition = currentPositionOf(base, household);
    if (currentPosition != null
        && modeId.equals(currentPosition.modeId())
        && (currentPosition.laborRole() == ProductionRole.LaborRole.NONE
            || currentPosition.surplusRole() == ProductionRole.SurplusRole.DEPENDENT)) {
      return infeasible(household, modeId, currentPosition.id(), hex, null, "DEPENDENT");
    }
    ProductionRole position = resolvePosition(base, household, modeId, producing, positionOrNull);
    HouseholdEconomy householdEconomy = base.classes().get(household);
    if (householdEconomy == null || householdEconomy.population() <= 0L) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_ROW");
    }
    if (DefaultProductionModes.DISPLACED.equals(modeId)) {
      // ★★ D-023 #6：流民没有工作、不得从 DISPLACED 池主动招募 —— 预期收益里也不得假装它有一份雇主工资。
      return infeasible(household, modeId, position.id(), hex, null, "DISPLACED_NO_WORK");
    }
    IndustryDefinitionView industry =
        industryOrNull != null ? industryOrNull : resolveIndustry(base, hex, modeId);
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return merchantProspect(
          base,
          household,
          householdEconomy,
          modeId,
          position,
          hex,
          industry,
          marketOrNull,
          demand,
          shares,
          claimedByEnterprises,
          units,
          relations,
          topology,
          day,
          accounts,
          assetBudget,
          wageTermsSource);
    }
    if (industry == null) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_INDUSTRY");
    }
    if (isWagePosition(position)) {
      return wageProspect(
          base,
          household,
          householdEconomy,
          modeId,
          position,
          hex,
          industry,
          marketOrNull,
          units,
          relations,
          assetBudget,
          wageTermsSource);
    }
    return productionProspect(
        base,
        household,
        householdEconomy,
        modeId,
        position,
        hex,
        industry,
        marketOrNull,
        demand,
        shares,
        claimedByEnterprises,
        accounts,
        units,
        relations,
        runtime,
        assetBudget,
        wageTermsSource);
  }

  // ── 生产位置（OWNER / SURPLUS_RECEIVER / TENANCY）─────────────────────────────────────────

  /**
   * ★★★ <b>D-032 §15.12 B（R1）：经营位置的预期读数 —— 物理量一律取自 {@link ProductionRuntime#probe}</b>。
   *
   * <p>★★ <b>为什么要换读源</b>：改前这里自算 {@code scale = min(资产规模, 劳动规模)}、{@code plannedOutput =
   * outputPerUnit × scale × 1000} —— 不读熟练度/经验、不读 GM 产出覆盖、不读解锁/可用性条件。于是"预计 100、实际 200" （熟练度 2
   * 倍）这类脱节在选择链上全程看不见（R1）。改后：probe 是<b>唯一的</b>物理产出/需求来源，本方法只保留估价 （价 × 量、自给、需求上限、关系分账）与排序读数。
   *
   * <p>★★ <b>probe 入参（§15.12 B 逐条）</b>：
   *
   * <pre>
   * 劳动       = 家户 participationAdjustedLaborMilli × horizonDays      （本周期累计；probe 内部 ÷cycleDays）
   * 占用       = 既有 unit 的 D4 AssetOccupancyBudget allocation（同一 revision/tick）
   *              候选：与 assetScaleOf **同一判据**的可用资产表（自有自营 + 同格可租闲置、claimed 排除）
   * 投入       = 本户账户里方案投入键上的库存（缺账户/缺键 ⇒ 0）
   * 熟练度/经验 = 本 tick 冻结条件优先（R5），否则运行时读口（ProductionProbeInputs 的唯一拼写点）
   * carry      = runtime.efficiencyOf(unitId)（候选 ⇒ 空：没有实例就没有余数行）
   * attributes = EMPTY（正式日循环的属性通路本就未接；声明了外部属性条件的方案因此具名拒，见 §4.5）
   * </pre>
   *
   * <p>★★ <b>结果消费</b>：{@code plannedOutput = probe.expectedOutput}（毫商品/周期，含 GM 覆盖与 E）· {@code scale
   * = probe.scale()}（产出规模）· {@code inputCost} 用 {@code probe.inputRequired}（键集同配方）· {@code
   * laborNeed = probe.laborRequiredMilli × horizonDays}（每日 →
   * 周期）。自给/可售/需求上限/收入/分账（obligations）/净额的算式形状不变。
   *
   * <p>★ <b>不可行理由映射</b>：{@code asset-short → NO_ASSET:<industry>}、{@code labor-short →
   * NO_LABOR:<household>}、 {@code plan-unknown → NO_INDUSTRY}、其余（含 {@code locked}/{@code
   * condition-unsatisfied:*}）⇒ {@code PROBE:<reason>}； feasible 但规模 0 ⇒ {@code SCALE_ZERO}（旧词表保留）。★
   * 资产那道闸仍在 probe 之前用同一判据判（旧口径的 {@code NO_ASSET} 逐值不变；占用为空时 probe 会给"可行、规模 0"，不提前判会把旧理由漂成 {@code
   * SCALE_ZERO}）。
   */
  static Prospect productionProspect(
      EconomyDayView base,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      ProductionModeId modeId,
      ProductionRole position,
      HexCoord hex,
      IndustryDefinitionView industry,
      MarketTable market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      ProductionRuntime runtime,
      AssetOccupancyBudget assetBudget,
      WageTermsSource wageTermsSource) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    ActorRef operator = HouseholdActors.of(household);
    ProductionUnitId unitId = existingUnitId(industry, operator, units);
    ProductionProcess existingUnit = units.get(unitId);
    Map<AssetKind, Long> occupancy;
    Optional<ProductionEfficiencyState> carry;
    if (existingUnit != null) {
      // ★★ R2：共享资产 scope 的 unit 在预计阶段具名不可行；不得把整池复制给多个实例。
      Optional<String> assetRejection = assetBudget.rejectionOf(existingUnit.id());
      if (assetRejection.isPresent()) {
        return infeasible(
            household, modeId, position.id(), hex, industry.id(), assetRejection.get());
      }
      // 当前在产：用这个 unit 的统一资产分配结果（含租佃而来的 operator==组织者份额）——预计与实际同源。
      occupancy = ProductionProbeInputs.positiveAssets(assetBudget.allocationOf(existingUnit.id()));
      carry = runtime.efficiencyOf(unitId);
    } else {
      // ★★ R2：同一个 (industry, operator) 已有别的 unit（旧档 opaque id/坏路径）⇒ 不得把候选再叠成共享实例。
      if (hasUnitInScope(units, industry.id(), operator)) {
        // 候选不在同一 revision 的 D4 预算里 ⇒ 不得回退整池份额；要求调用方补显式请求。
        return infeasible(
            household,
            modeId,
            position.id(),
            hex,
            industry.id(),
            "asset-occupancy-request-missing:" + unitId.value());
      }
      // 候选：自有自营 + 同格可租闲置（闲置判据的唯一拼写点在 ModeMigrationPolicy）。
      // ★★ D-024 修复 1：claimed 由调用方从当天工作副本 enterprises 算出并传入，本类不再从 base 重算。
      occupancy =
          ProductionProbeInputs.positiveAssets(
              candidateUsableAssets(industry, household, shares, claimedByEnterprises));
      carry = Optional.empty(); // 候选：没有实例 ⇒ 没有精度余数行
    }
    // ★★★ D-032 D3 §5.1（W1）：全部 MONEY_WAGE 角色的预计应付工资进入成本；在算成本之前先做
    //   payer 级地点预检（existing ∪ candidate）；>1 地点 ⇒ 候选不可行、零工资成本/净额。
    WageEstimate wageEstimate =
        estimateEmployerWageCost(
            base, industry, existingUnit, hex, operator, units, horizonDays, wageTermsSource);
    if (wageEstimate.rejection().isPresent()) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), wageEstimate.rejection().get());
    }

    // ★★ durable 商品占用：候选与 existing 都读本评估户（household）的库存快照。
    //   若 existingUnit.operator 不是本户，仍保留这个“以候选家户为评估主体”的明确口径：
    //   本方法是预计/排序入口，不能借用别的 operator 的库存把本户候选算成可生产。
    Map<CommodityId, Long> durableOccupancy =
        ProductionProbeInputs.durableCommodityOccupancyOf(
            accounts.householdGoods().get(household), industry);
    // ★ 资产那道闸：同时遍历 capacityPerUnit 与 durableCommodityCapacityPerUnit。
    long assetScale = assetScaleOf(industry, occupancy, durableOccupancy);
    if (assetScale < 1L) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          "NO_ASSET:" + industry.id().value());
    }

    // ★★ §15.12 B：本周期累计劳动（probe 内部 ÷cycleDays 折回日均）。
    long laborPeriodMilli =
        Math.multiplyExact(householdEconomy.participationAdjustedLaborMilli(), horizonDays);
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            household,
            laborPeriodMilli,
            occupancy,
            ProductionProbeInputs.inputsOf(accounts.householdGoods().get(household), industry),
            ProductionProbeInputs.masteryOf(runtime, household, industry),
            ProductionProbeInputs.experienceOf(runtime, household, industry));
    ProductionProbeResult probe =
        runtime.probe(
            new ProductionProbeRequest(
                industry.id(),
                conditions,
                occupancy,
                durableOccupancy,
                carry,
                ExternalAttributeFacts.EMPTY));
    if (!probe.feasible()) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          probeRejectionReason(probe.reason(), industry, household));
    }
    long scale = probe.scale();
    if (scale < 1L) {
      return infeasible(household, modeId, position.id(), hex, industry.id(), "SCALE_ZERO");
    }

    LinkedHashMap<CommodityId, Long> plannedOutput = new LinkedHashMap<>(probe.expectedOutput());
    LinkedHashMap<CommodityId, Long> selfConsumed = new LinkedHashMap<>();
    LinkedHashMap<CommodityId, Long> sellable = new LinkedHashMap<>();
    boolean priceMissing = false;
    long remainingTotal = 0L;
    long sellableTotal = 0L;
    for (Map.Entry<CommodityId, Long> output : plannedOutput.entrySet()) {
      CommodityId commodity = output.getKey();
      long planned = output.getValue();
      long need =
          Math.multiplyExact(
              householdEconomy.naturalNeeds().getOrDefault(commodity, 0L), horizonDays);
      long self = Math.min(planned, need);
      long remaining = Math.max(0L, planned - self);
      long addressable = demand.addressable(hex, commodity);
      long sell = Math.min(remaining, Math.max(0L, addressable - self));
      if (self > 0L) {
        selfConsumed.put(commodity, self);
      }
      if (sell > 0L) {
        sellable.put(commodity, sell);
      }
      remainingTotal = Math.addExact(remainingTotal, remaining);
      sellableTotal = Math.addExact(sellableTotal, sell);
      if (sell + self > 0L && (market == null || !market.hasPrice(commodity))) {
        priceMissing = true;
      }
    }

    // ★★★ D-032 C2：产品/投入估值统一走 `bidValueMilli`/`askValueMilli`（唯一拼写点；与
    //   `rosterIncomes` 同口径），本方法不再内联一套价格公式。
    Map<CommodityId, Long> plannedQuantities = new LinkedHashMap<>();
    Set<CommodityId> unpricedOutputs = new LinkedHashSet<>();
    for (CommodityId commodity : plannedOutput.keySet()) {
      long quantity =
          Math.addExact(
              selfConsumed.getOrDefault(commodity, 0L), sellable.getOrDefault(commodity, 0L));
      if (quantity > 0L) {
        plannedQuantities.put(commodity, quantity);
      }
    }
    Map<CommodityId, Long> revenueByCommodity =
        bidValuesByCommodity(market, plannedQuantities, unpricedOutputs);
    long revenue = sumValues(revenueByCommodity);

    boolean inputPriceMissing = false;
    Set<CommodityId> unpricedInputs = new LinkedHashSet<>();
    Map<CommodityId, Long> inputCostByCommodity =
        askValuesByCommodity(market, probe.inputRequired(), unpricedInputs);
    inputPriceMissing = !unpricedInputs.isEmpty();
    long inputCost = sumValues(inputCostByCommodity);

    // ★★ §15.12 B：laborRequiredMilli 是"每日"口径（基准规模 × 每单位劳动）⇒ × horizonDays 折成本周期劳动。
    long laborNeed =
        laborPerUnit <= 0L ? 0L : Math.multiplyExact(probe.laborRequiredMilli(), horizonDays);
    long grainPrice = market == null ? 0L : market.priceOf(EconomySettlement.GRAIN);
    boolean grainPriceMissing =
        laborNeed > 0L && (market == null || !market.hasPrice(EconomySettlement.GRAIN));
    long subsistenceLaborCost =
        grainPriceMissing
            ? 0L
            : laborCostMilli(laborNeed, RegimeRelations.subsistenceMilliPerLabor(), grainPrice);

    ProductionRules relation =
        relationFor(relations, unitId, modeId, industry, householdEconomy, operator);
    Obligations obligations =
        obligationsOf(
            relation,
            household,
            householdEconomy,
            hex,
            market,
            revenueByCommodity,
            inputCostByCommodity,
            revenue,
            inputCost,
            laborNeed,
            false);
    // ★ D3 §5.4：laborCostMilli 只保留"生存/非工资劳动折算"；预计工资单独成项。
    long laborCost = subsistenceLaborCost;
    long expectedWageCost = wageEstimate.amountMilli();
    long rent = obligations.rentMilli();
    long net =
        Math.subtractExact(
            Math.subtractExact(
                Math.subtractExact(Math.subtractExact(revenue, inputCost), laborCost),
                expectedWageCost),
            rent);
    boolean demandCapped = demandCapped(remainingTotal, sellableTotal);
    String reason =
        joinReasons(
            obligations.notes(),
            wageEstimate.notes(),
            priceMissing ? List.of("NO_PRICE") : List.of(),
            inputPriceMissing ? List.of("NO_INPUT_PRICE") : List.of(),
            grainPriceMissing ? List.of("NO_GRAIN_PRICE") : List.of(),
            market == null ? List.of("NO_MARKET") : List.of(),
            demandCapped ? List.of("DEMAND_CAPPED") : List.of());
    return new Prospect(
        household,
        modeId,
        position.id(),
        hex,
        Optional.of(industry.id()),
        scale,
        plannedOutput,
        selfConsumed,
        sellable,
        revenue,
        inputCost,
        laborCost,
        expectedWageCost,
        rent,
        net,
        laborNeed,
        scaledNetPerLabor(net, laborNeed),
        demandCapped,
        true,
        reason);
  }

  // ── 规模 / 关系 / 义务 ────────────────────────────────────────────────────────────────────

  /** 同一 {@code (industry, operator)} 是否已有生产实例；只做 scope 守卫，不读资产数量。 */
  static boolean hasUnitInScope(
      Map<ProductionUnitId, ProductionProcess> units, IndustryId industry, ActorRef operator) {
    for (ProductionProcess unit : units.values()) {
      if (unit.industry().equals(industry) && unit.operator().equals(operator)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★★ <b>§15.12 B：候选可用资产表</b>（自有自营 + 同格可租闲置，判据与 {@link ModeMigrationPolicy#isIdleShare} 逐字同源）——
   * "返回资产表"的<b>唯一拼写点</b>。
   *
   * <p>★ <b>为什么返回表而不是规模</b>：§15.12 B 要求"候选用与现有 {@code assetScaleOf} 同一判据的可用资产表"喂 {@code
   * probe.assetOccupancy}，而规模只是同一张表的一次 {@code min}（{@link #assetScaleOf(IndustryDefinitionView,
   * Map)}）。分成"先算规模、再算占用"两处算术就是两份判据 —— 本方法只做表，规模从表来。
   *
   * <p>★ <b>D-024 修复 1</b>：{@code claimedByEnterprises} 由调用方从当天工作副本 {@code enterprises}/{@code
   * units} 算出并传入，本类不从 {@code base} 重算（过期快照会把已使用份额误判成闲置）。
   */
  static Map<AssetKind, Long> candidateUsableAssets(
      IndustryDefinitionView industry,
      HouseholdId household,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises) {
    ActorRef actor = HouseholdActors.of(household);
    Map<AssetKind, Long> available = new LinkedHashMap<>();
    for (OwnershipStake share : shares.values()) {
      if (!share.industry().equals(industry.id()) || share.quantity() <= 0L) {
        continue;
      }
      boolean own = share.operator().equals(actor);
      boolean idle = ModeMigrationPolicy.isIdleShare(share, claimedByEnterprises);
      if (!own && !idle) {
        continue;
      }
      available.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return available;
  }

  /**
   * ★★ <b>资产表的产能规模（唯一算式）</b>：{@code min_k ⌊可用资产[k] ÷ capacityPerUnit[k]⌋}（缺键 = 0；空表 = 0）。
   *
   * <p>★ 与 {@code ProductionRuntimeEngine} 的资产通道逐字同式（E=1000 时既有的 {@code 资产规模} 读数不变）；既有 unit 的 既有
   * unit 的占用表只读 {@link AssetOccupancyBudget#allocationOf(ProductionUnitId)}；候选计划表是进入前的
   * 非资产规模派生，不是运行时可用资产回退。
   */
  static long assetScaleOf(IndustryDefinitionView industry, Map<AssetKind, Long> availableAssets) {
    return assetScaleOf(industry, availableAssets, Map.of());
  }

  /**
   * 资产表 + durable 商品占用表的产能规模（唯一算式）。
   *
   * <p>同时遍历 {@code capacityPerUnit} 与 {@code durableCommodityCapacityPerUnit}；缺项按 0，两表都空 ⇒ 0。
   * 旧调用等价于 durable 空表，逐值不变。
   */
  static long assetScaleOf(
      IndustryDefinitionView industry,
      Map<AssetKind, Long> availableAssets,
      Map<CommodityId, Long> durableCommodityOccupancy) {
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(availableAssets, "availableAssets");
    Objects.requireNonNull(
        durableCommodityOccupancy, "durableCommodityOccupancy 不得为 null（无 durable 通道用空表）");
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> required : industry.capacityPerUnit().entrySet()) {
      scale =
          Math.min(
              scale, availableAssets.getOrDefault(required.getKey(), 0L) / required.getValue());
    }
    for (Map.Entry<CommodityId, Long> required :
        industry.durableCommodityCapacityPerUnit().entrySet()) {
      scale =
          Math.min(
              scale,
              durableCommodityOccupancy.getOrDefault(required.getKey(), 0L) / required.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * ★★ <b>§15.12 B：probe 的不可行理由 → 经济侧的旧词表</b>（键序/词表稳定性）。
   *
   * <pre>
   * asset-short   → NO_ASSET:&lt;industry&gt;      （旧词，逐字保留）
   * labor-short   → NO_LABOR:&lt;household&gt;     （旧词，逐字保留）
   * plan-unknown  → NO_INDUSTRY               （旧词：该格没有可用产业定义）
   * 其余          → PROBE:&lt;production 的具名理由&gt;（locked / condition-unsatisfied:* / input-short …）
   * </pre>
   *
   * <p>★ 其余理由<b>不得</b>被折叠成 NO_INDUSTRY/SCALE_ZERO：{@code locked} 与 {@code condition-unsatisfied:*}
   * 是"这户还不够格 用这个方案"（R1 要求它们具名出现在选择链的拒绝里），与"没有产业模板"是两件事。
   */
  static String probeRejectionReason(
      String probeReason, IndustryDefinitionView industry, HouseholdId household) {
    if ("asset-short".equals(probeReason)) {
      return "NO_ASSET:" + industry.id().value();
    }
    if ("labor-short".equals(probeReason)) {
      return "NO_LABOR:" + household.value();
    }
    if ("plan-unknown".equals(probeReason)) {
      return "NO_INDUSTRY";
    }
    return "PROBE:" + probeReason;
  }

  // ── 位置 / 产业 / 雇主 ────────────────────────────────────────────────────────────────────

  static List<ProductionRole> producingPositions(ClassStructure structure) {
    List<ProductionRole> positions = new ArrayList<>();
    for (ProductionRole position : structure.positions().values()) {
      if (position.laborRole() != ProductionRole.LaborRole.NONE
          && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT) {
        positions.add(position);
      }
    }
    positions.sort(Comparator.comparing(position -> position.id().value()));
    return positions;
  }

  /**
   * 目标位置：先取家户当前位置（若它属于本 mode），否则同 relationToMeans/surplusRole 的第一个可生产位置，再否则 id 升序第一个 —— 与 {@code
   * ModeMigrationSettlement.pickTargetPosition} 同一口径（本类不另立一套）。
   */
  static ProductionRole currentPositionOf(EconomyDayView base, HouseholdId household) {
    var classMembership = base.classStandings().get(household);
    return classMembership == null
        ? null
        : base.classPositions().get(classMembership.currentPositionId());
  }

  static ProductionRole choosePosition(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      List<ProductionRole> producing) {
    ProductionRole current = currentPositionOf(base, household);
    if (current != null && modeId.equals(current.modeId()) && producing.contains(current)) {
      return current;
    }
    if (current != null) {
      for (ProductionRole position : producing) {
        if (position.relationToMeans() == current.relationToMeans()
            && position.surplusRole() == current.surplusRole()) {
          return position;
        }
      }
    }
    return producing.get(0);
  }

  /**
   * ★★ <b>W9：位置解析的唯一分叉点</b> —— 指定了 {@code positionOrNull} 且它确实是该 mode 的<b>可生产位置</b> ⇒ 用它
   * （"按库存能支撑的最高档"那条路）；否则退回 {@link #choosePosition} 的既有口径（逐值不变）。
   */
  static ProductionRole resolvePosition(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      List<ProductionRole> producing,
      ClassPositionId positionOrNull) {
    if (positionOrNull != null) {
      for (ProductionRole candidate : producing) {
        if (candidate.id().equals(positionOrNull)) {
          return candidate;
        }
      }
    }
    return choosePosition(base, household, modeId, producing);
  }

  static boolean isWagePosition(ProductionRole position) {
    if (position.surplusRole() == ProductionRole.SurplusRole.WAGE_EARNER) {
      return true;
    }
    return position.relationToMeans() == ProductionRole.RelationToMeans.DIRECT_LABORER
        && position.surplusRole() != ProductionRole.SurplusRole.SELF_SUBSISTENCE;
  }

  static boolean isMerchantRegime(IndustryDefinitionView industry) {
    return industry != null && RegimeOperators.MERCHANT.equals(industry.regime().value());
  }

  static IndustryDefinitionView resolveIndustry(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    for (IndustryId industryId : ModeMigrationPolicy.industriesForMode(base, hex, modeId)) {
      IndustryDefinitionView industry = base.industries().get(industryId);
      if (industry != null) {
        return industry;
      }
    }
    return null;
  }

  /**
   * 本户在该产业上实际在产的 unit id（现有 unit 优先；没有 ⇒ 由 {@code (industry, operator)} 确定性推出的候选 unit id）。
   *
   * <p>★ 为什么要先扫 {@code units}：播种/旧档的 unit id 可能由别的工厂/旧档原样落盘（{@code parse} 是 opaque 的）， 用 canonical
   * id 去查 {@code relations} 会漏掉真实关系、退回制度默认（那会让佃农拿到封建庄园规则）。
   */
  static ProductionUnitId existingUnitId(
      IndustryDefinitionView industry,
      ActorRef operator,
      Map<ProductionUnitId, ProductionProcess> units) {
    ProductionUnitId canonical = ProductionUnitId.idOf(industry.id(), operator);
    if (units.containsKey(canonical)) {
      return canonical;
    }
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit != null
          && unit.industry().equals(industry.id())
          && unit.operator().equals(operator)) {
        return unitId;
      }
    }
    return canonical;
  }
}
