package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class LaborCandidateBook {
  private LaborCandidateBook() {}

  record LaborSlot(
      HouseholdId household, ProductionProcess unit, ProductionRoleId role, boolean moneyWage) {}

  // ── 候选 / 批次 / 市场 ─────────────────────────────────────────────────────────────────

  /**
   * ★★★ W2b 候选级 skip：同市场区、非 organizer 自雇、账户存在、payer 单位置预检通过。 返回 null = 通过；否则是 D3 §4.5/§6.9.1 的稳定
   * reason。
   *
   * <p>★ P4a：MONEY_WAGE 岗位不再因工资库存 lookup 不可用而被 skip（工资只付货币、未付成债）。
   */
  static String moneyWageCandidateSkipReason(
      EconomySession session,
      SettlementIndex index,
      AccountSession accounts,
      ProductionProcess unit,
      HouseholdId worker,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    HouseholdId organizer =
        organizerHouseholdOf(unit, index, enterpriseByProcess, productionRosters);
    if (organizer == null) {
      return "wage-payer-account-missing:" + worker.value();
    }
    if (worker.equals(organizer)) {
      return "wage-organizer-self-employment:" + worker.value();
    }
    if (!sameMarketZone(session, unit, worker)) {
      return "wage-worker-location-mismatch:" + worker.value();
    }
    if (accounts == null
        || (!accounts.householdMoney().containsKey(worker)
            && !accounts.householdGoods().containsKey(worker))) {
      return "wage-worker-account-missing:" + worker.value();
    }
    ActorRef payer = HouseholdActors.of(organizer);
    WagePayerLocationSnapshot snapshot =
        new WagePayerLocationSnapshot(
            payer,
            existingSettlementLocationsOf(session, payer, enterpriseByProcess, productionRosters),
            Set.of(unit.location()));
    if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
        == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
      return "wage-payer-location-unsupported:" + worker.value();
    }
    return null;
  }

  /** organizer 的唯一来源：组织表优先；无名册/组织的旧 unit 退回 operator 家户。 */
  static HouseholdId organizerHouseholdOf(
      ProductionProcess unit,
      SettlementIndex index,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
    if (enterprise != null) {
      HouseholdId household = index.householdByActor().get(enterprise.organizer());
      if (household != null) {
        return household;
      }
    }
    ProductionRoster roster = productionRosters.get(unit.id());
    if (roster != null) {
      return roster.organizerHousehold();
    }
    return index.householdByActor().get(unit.operator());
  }

  /** payer 当前 revision 已有 unit/roster 地点（与候选地点合并后交给唯一预检）。 */
  static Set<ProductionLocation> existingSettlementLocationsOf(
      EconomySession session,
      ActorRef payer,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    Set<ProductionLocation> locations = new LinkedHashSet<>();
    for (ProductionProcess unit : session.sheet().units().values()) {
      if (payer.equals(unit.operator())) {
        locations.add(unit.location());
        continue;
      }
      ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
      if (enterprise != null && payer.equals(enterprise.organizer())) {
        locations.add(unit.location());
      }
      ProductionRoster roster = productionRosters.get(unit.id());
      if (roster != null && payer.equals(HouseholdActors.of(roster.organizerHousehold()))) {
        locations.add(unit.location());
      }
    }
    // 已有 settlement location 只由上面"payer 的 unit/组织/名册"覆盖；不得把**全世界的**
    // unit 地点都并进来 —— 那会伪造 MULTIPLE_LOCATIONS（payer 级预检的集合必须是该 payer 的）。
    return locations;
  }

  /** W2b 首期只允许同一 MarketZoneId 的本地招募；无法确定区（派生市场/缺区表）⇒ 具名 skip。 */
  static boolean sameMarketZone(
      EconomySession session, ProductionProcess unit, HouseholdId worker) {
    HouseholdEconomy household = session.sheet().householdEconomiesOrBase().get(worker);
    if (household == null) {
      return false;
    }
    io.mosire.simos.economy.api.id.MarketZoneId unitZone =
        zoneOfHex(session, unit.location().value());
    io.mosire.simos.economy.api.id.MarketZoneId workerZone =
        zoneOfHex(session, household.view().hex().toString());
    return unitZone != null && unitZone.equals(workerZone);
  }

  static io.mosire.simos.economy.api.id.MarketZoneId zoneOfHex(
      EconomySession session, String hexText) {
    HexCoord hex;
    try {
      hex = HexCoord.parse(hexText);
    } catch (RuntimeException bad) {
      return null;
    }
    for (var entry : session.sheet().base().marketZones().entrySet()) {
      if (entry.getValue().hexes().contains(hex)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /** 该 unit 归哪些家户（operator、组织的 organizer/laborSources、既有配额的 household）——稳定去重。 */
  static Set<HouseholdId> candidateHouseholdsOf(
      ProductionProcess unit,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<String, Set<HouseholdId>> allocationHouseholdsByActivity,
      SettlementIndex index) {
    Set<HouseholdId> households = new LinkedHashSet<>();
    if (unit.operator().kind() == ActorKind.HOUSEHOLD) {
      HouseholdId operatorHousehold = index.householdByActor().get(unit.operator());
      if (operatorHousehold != null && householdEconomies.containsKey(operatorHousehold)) {
        households.add(operatorHousehold);
      }
    }
    ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
    if (enterprise != null) {
      HouseholdId organizerHousehold = index.householdByActor().get(enterprise.organizer());
      if (organizerHousehold != null && householdEconomies.containsKey(organizerHousehold)) {
        households.add(organizerHousehold);
      }
      for (HouseholdId laborSource : enterprise.laborSources()) {
        if (householdEconomies.containsKey(laborSource)) {
          households.add(laborSource);
        }
      }
    }
    for (HouseholdId household :
        allocationHouseholdsByActivity.getOrDefault(unit.id().value(), Set.of())) {
      if (householdEconomies.containsKey(household)) {
        households.add(household);
      }
    }
    return households;
  }

  /**
   * ★★★ <b>D-032 D2 §4.3 / §5.4：按名册角色分流候选</b>。
   *
   * <ul>
   *   <li>有 {@link ProductionRoster}：{@code PRODUCT_SHARE} 角色只取该角色 {@code plannedLaborMilliPerDay >
   *       0} 的 participant（纯收租/零计划劳动户不进自动劳动匹配）；{@code MONEY_WAGE} 角色不读 roster 的 planned labor， 但 C2
   *       只读 U20 工资锚；缺锚 ⇒ 为每个工资角色发一条零额候选，具名 {@code
   *       wage-quote-missing:<role>}（不伪造 worker、不按 0 报价）。
   *   <li>无名册：单角色 plan ⇒ 用既有候选户 + 该唯一角色；多角色 plan 无名册 ⇒ 不产生任何候选（调用方记 {@code
   *       roster-required}），绝不按角色各建一份。
   * </ul>
   */
  static List<LaborSlot> candidateSlotsOf(
      ProductionProcess unit,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess,
      Map<String, Set<HouseholdId>> allocationHouseholdsByActivity,
      SettlementIndex index,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionRoster> productionRosters) {
    Optional<IndustryDefinitionView> plan = definitions.industry(unit.industry());
    if (plan.isEmpty()) {
      return List.of();
    }
    List<LaborSlot> slots = new ArrayList<>();
    ProductionRoster roster = productionRosters.get(unit.id());
    if (roster != null) {
      for (io.mosire.simos.economy.model.ProductionParticipant participant :
          roster.participants()) {
        IndustryDefinitionView definition = plan.get();
        io.mosire.simos.production.contract.ProductionRoleDefinition roleDefinition =
            definition.roles().stream()
                .filter(candidate -> candidate.id().equals(participant.role()))
                .findFirst()
                .orElse(null);
        if (roleDefinition == null) {
          continue; // 未知角色由 EconomyReferenceValidation 拒绝；这里不猜
        }
        if (roleDefinition.payment()
            instanceof io.mosire.simos.production.contract.RolePayment.MoneyWage) {
          continue; // MONEY_WAGE 不读 roster worker；下面按同市场区自动展开本地家户候选。
        }
        if (participant.plannedLaborMilliPerDay() > 0L
            && householdEconomies.containsKey(participant.household())) {
          slots.add(new LaborSlot(participant.household(), unit, participant.role(), false));
        }
      }
      addMoneyWageSlots(unit, plan.get(), householdEconomies, slots);
      return slots;
    }
    if (plan.get().roles().size() != 1) {
      return List.of(); // 多角色 plan 无名册 ⇒ roster-required（由校验/执行面具名拒绝）
    }
    io.mosire.simos.production.contract.ProductionRoleDefinition onlyRole =
        plan.get().roles().get(0);
    if (onlyRole.payment() instanceof io.mosire.simos.production.contract.RolePayment.MoneyWage) {
      addMoneyWageSlots(unit, plan.get(), householdEconomies, slots);
      return slots;
    }
    for (HouseholdId household :
        candidateHouseholdsOf(
            unit, householdEconomies, enterpriseByProcess, allocationHouseholdsByActivity, index)) {
      slots.add(new LaborSlot(household, unit, onlyRole.id(), false));
    }
    return slots;
  }

  /**
   * ★★★ W2b：MONEY_WAGE 角色的候选面 = 本地家户全体，资格/同区/账户/自雇/payer 单位置在 offer 构造处逐条 skip。 roster 的工资
   * participant 不构成第二份 worker 权威。
   */
  static void addMoneyWageSlots(
      ProductionProcess unit,
      IndustryDefinitionView plan,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      List<LaborSlot> slots) {
    List<HouseholdId> households = new ArrayList<>(householdEconomies.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (io.mosire.simos.production.contract.ProductionRoleDefinition roleDefinition :
        plan.roles()) {
      if (!(roleDefinition.payment()
          instanceof io.mosire.simos.production.contract.RolePayment.MoneyWage)) {
        continue;
      }
      for (HouseholdId household : households) {
        slots.add(new LaborSlot(household, unit, roleDefinition.id(), true));
      }
    }
  }

  /** 该 unit 的组织 mode；没有组织 ⇒ 空（旧 unit 的并列键退回 {@code modeKey}）。 */
  static Optional<ProductionModeId> modeIdOf(
      ProductionProcess unit, Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess) {
    ProductionEnterprise enterprise = enterpriseByProcess.get(unit.id());
    return enterprise == null ? Optional.empty() : Optional.of(enterprise.modeId());
  }

  /** 居住格的价表（没有 ⇒ 该 unit 所在格的价表；都没有 ⇒ null = 无价，排队读数按 0 估值并具名）。 */
  static MarketTable marketOf(
      HouseholdEconomy householdEconomy, List<ProductionProcess> candidates, MarketIndex markets) {
    MarketTable market = markets.at(householdEconomy.view().hex());
    if (market != null) {
      return market;
    }
    for (ProductionProcess unit : candidates) {
      Optional<HexCoord> hex = hexOf(unit);
      if (hex.isPresent() && markets.at(hex.get()) != null) {
        return markets.at(hex.get());
      }
    }
    return null;
  }

  static Optional<HexCoord> hexOf(ProductionProcess unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse);
  }

  /** 新配额挂哪个批次：家户人口组成里 count &gt; 0 的最小批次 id；没有 ⇒ 该户既有配额的批次；再没有 ⇒ 空 （调用方跳过本户排队，不猜）。 */
  static PeopleLotId chooseLot(
      HouseholdId household,
      Map<HouseholdId, List<HouseholdLaborCommitment>> laborCommitmentsByHousehold,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition) {
    List<PeopleLotId> lots = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> member :
        composition.getOrDefault(household, Map.of()).entrySet()) {
      if (member.getKey() != null && member.getValue() != null && member.getValue() > 0L) {
        lots.add(member.getKey());
      }
    }
    lots.sort(Comparator.comparing(PeopleLotId::value));
    if (!lots.isEmpty()) {
      return lots.get(0);
    }
    List<PeopleLotId> existing = new ArrayList<>();
    for (HouseholdLaborCommitment laborCommitment :
        laborCommitmentsByHousehold.getOrDefault(household, List.of())) {
      if (laborCommitment.group() != null) {
        existing.add(laborCommitment.group());
      }
    }
    existing.sort(Comparator.comparing(PeopleLotId::value));
    return existing.isEmpty() ? null : existing.get(0);
  }
}
