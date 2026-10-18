package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.WageProspectBook.moneyWageRoles;
import static io.mosire.simos.economy.time.WageProspectBook.wageAmountMilli;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.time.ExpectedProfitBook.RosterHouseholdIncome;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class RosterIncomeBook {
  private RosterIncomeBook() {}

  /**
   * ★★★ <b>W1 名册工资估值</b>：报价只读 U20 锚，目标只走标准劳动/周期天数的工程默认，另带已有 settlement
   * location。
   *
   * <p>★ <b>无旧表回退</b>：锚缺失 ⇒ 具名 {@code wage-quote-missing:<role>}；工程目标为 0 ⇒ 具名 {@code
   * wage-target-missing:<role>}，不会静默按 0。
   *
   * <p>★ <b>已有地点必须完整</b>：{@code existingSettlementLocations} 必须来自当前 revision 该 payer 的全部已有
   * unit/roster 地点；不得只传候选。集合空 = 不知道已有地点（fail-open 仅限"没有工资角色"的旧调用方）。
   */
  static List<RosterHouseholdIncome> rosterIncomes(
      IndustryDefinitionView plan,
      ProductionProcess unit,
      ProductionRoster roster,
      Map<AssetShareId, OwnershipStake> assetShares,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTable market,
      EconomyDayView dayView,
      Map<WageAnchorKey, ProductionWageAnchor> wageAnchors,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      AssetOccupancyLookup assetOccupancyLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision,
      Set<ProductionLocation> existingSettlementLocations,
      long day,
      ProductionRuntime runtime) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(roster, "roster");
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(dayView, "dayView");
    Objects.requireNonNull(wageAnchors, "wageAnchors（无锚请用空表）");
    Objects.requireNonNull(existingSettlementLocations, "existingSettlementLocations");
    long cycleDays = Math.max(1L, plan.cycleDays());
    Map<HouseholdId, ExternalAttributeFacts> facts =
        participantAttributes == null ? Map.of() : participantAttributes;
    ProductionRules relation = relations == null ? null : relations.get(unit.id());
    String legacyRejection =
        relation != null && !relation.rules().isEmpty()
            ? "legacy-compensation-rules-present"
            : null;
    // ★ D2 §7.3：角色级资格逐户检查（计划级资格由 probe 的 organizer 主体判；execute 会再查一次）。
    Optional<String> qualificationRejection =
        roleQualificationRejection(plan, roster, runtime, facts);
    // 1) 资产占用（D4-C）：不可用 ⇒ 具名拒绝（不读成 0、不回退整池份额扫描）。
    Map<AssetKind, Long> occupancy = Map.of();
    String occupancyRejection = null;
    if (assetOccupancyLookup == null || expectedRevision == null) {
      occupancyRejection = "asset-occupancy-unavailable";
    } else {
      Optional<AssetOccupancySnapshot> snapshot =
          assetOccupancyLookup.occupancyOf(unit.id(), expectedRevision, day, day + 1L);
      if (snapshot.isEmpty()) {
        occupancyRejection = "asset-occupancy-unavailable";
      } else {
        AssetOccupancySnapshot value = snapshot.get();
        if (!value.revision().equals(expectedRevision)
            || !value.unit().equals(unit.id())
            || !value.location().equals(unit.location())
            || value.fromTick() != day
            || value.toTickExclusive() != day + 1L) {
          throw new IllegalStateException(
              "asset-occupancy-snapshot-mismatch: unit=" + unit.id().value());
        }
        occupancy = ProductionProbeInputs.positiveAssets(value.allocation());
      }
    }
    // 2) 计划级 probe：主体 = organizer；劳动 = Σ planned × cycleDays。
    long plannedLaborPerDay = 0L;
    for (ProductionParticipant participant : roster.participants()) {
      plannedLaborPerDay = Math.addExact(plannedLaborPerDay, participant.plannedLaborMilliPerDay());
    }
    HouseholdId organizer = roster.organizerHousehold();
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            organizer,
            Math.multiplyExact(plannedLaborPerDay, cycleDays),
            occupancy,
            ProductionProbeInputs.inputsOf(
                accounts == null ? Map.of() : accounts.householdGoods().get(organizer), plan),
            ProductionProbeInputs.masteryOf(runtime, organizer, plan),
            ProductionProbeInputs.experienceOf(runtime, organizer, plan));
    ProductionProbeResult probe =
        runtime.probe(
            new ProductionProbeRequest(
                plan.id(),
                conditions,
                occupancy,
                runtime.efficiencyOf(unit.id()),
                facts.getOrDefault(organizer, ExternalAttributeFacts.EMPTY)));
    String probeRejection =
        !probe.feasible()
            ? "PROBE:" + probe.reason()
            : probe.scale() <= 0L ? "PROBE:SCALE_ZERO" : null;
    String rejection =
        legacyRejection != null
            ? legacyRejection
            : qualificationRejection.orElse(
                occupancyRejection != null ? occupancyRejection : probeRejection);
    // 3) 毛产 → net（唯一拼写点）。
    Map<CommodityId, Long> netPools = new LinkedHashMap<>();
    if (rejection == null) {
      for (Map.Entry<CommodityId, ProductionOutputNetting.Netted> entry :
          ProductionOutputNetting.netAll(probe.expectedOutput()).entrySet()) {
        netPools.put(entry.getKey(), entry.getValue().net());
      }
    }
    // 4) 角色两层分配（planned labor / rights / weights）。
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Map<CommodityId, Long>>
        sharesByParticipant = new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> plannedByParticipant =
        new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> rightsWeights =
        new LinkedHashMap<>();
    List<io.mosire.simos.production.contract.RoleDistributionParticipant> distributionParticipants =
        new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      distributionParticipants.add(
          new io.mosire.simos.production.contract.RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      plannedByParticipant.put(
          key, Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays));
      long rights = 0L;
      for (AssetShareId shareId : participant.rightsStakes()) {
        OwnershipStake stake = assetShares == null ? null : assetShares.get(shareId);
        if (stake == null) {
          throw new IllegalStateException(
              "BY_RIGHTS 引用的资产份额不存在: unit=" + unit.id().value() + " share=" + shareId.value());
        }
        rights = Math.addExact(rights, stake.quantity());
      }
      if (rights > 0L) {
        rightsWeights.put(key, rights);
      }
    }
    if (rejection == null && !netPools.isEmpty()) {
      io.mosire.simos.production.contract.RoleDistributionResult distribution =
          io.mosire.simos.production.contract.RoleDistribution.allocate(
              new io.mosire.simos.production.contract.RoleDistributionRequest(
                  plan, distributionParticipants, plannedByParticipant, rightsWeights, netPools));
      sharesByParticipant.putAll(distribution.shares());
    }
    // 5) ★ W1：payer 级地点预检先于工资读口/估值（D3 §5.1/§6.9.1）；>1 地点 ⇒ 具名拒绝，零 lookup/零工资读数。
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(plan);
    String wageRejection = null;
    Set<String> wageReasons = new LinkedHashSet<>();
    if (!moneyRoles.isEmpty()) {
      ActorRef payer = HouseholdActors.of(roster.organizerHousehold());
      WagePayerLocationSnapshot snapshot =
          new WagePayerLocationSnapshot(
              payer, existingSettlementLocations, Set.of(unit.location()));
      if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
          == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
        wageRejection = "wage-payer-location-unsupported";
      }
    }
    // 5b) 工资报价/目标：报价唯一读 U20 锚；目标只走工程默认；缺 quote/缺目标具名，不按 0 静默。
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, ProductionWageQuote> wageQuotes =
        new LinkedHashMap<>();
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> wageTargets =
        new LinkedHashMap<>();
    if (wageRejection == null) {
      for (ProductionRoleDefinition role : moneyRoles) {
        Optional<WageAnchorKey> anchorKey =
            WageQuoteBridge.wageAnchorKeyOf(dayView, unit, role.id());
        ProductionWageAnchor anchor =
            anchorKey.map(wageAnchors::get).orElse(null);
        if (anchor == null) {
          if (wageRejection == null) {
            wageRejection = "wage-quote-missing:" + role.id().value();
          }
          continue;
        }
        long target = WageQuoteBridge.wageTargetLaborMilliPerDay(0L, role, cycleDays);
        if (target <= 0L) {
          if (wageRejection == null) {
            wageRejection = "wage-target-missing:" + role.id().value();
          }
          continue;
        }
        ProductionWageQuote quote =
            new ProductionWageQuote(anchor.currency(), anchor.wagePerLaborMilli());
        wageQuotes.put(role.id(), quote);
        wageTargets.put(role.id(), target);
        if (quote.wagePerLaborMilli() == 0L) {
          wageReasons.add("WAGE_QUOTE_ZERO");
        }
      }
    }
    if (wageRejection != null) {
      wageReasons.add(wageRejection);
    }
    // 5c) 组织者周期预计应付：Σ quote × 工程目标 × cycleDays / 1000（D3 §5.1/A1）。
    long organizerWageCost = 0L;
    if (wageRejection == null) {
      for (Map.Entry<io.mosire.simos.economy.api.production.ProductionRoleId, ProductionWageQuote>
          wage : wageQuotes.entrySet()) {
        Long target = wageTargets.get(wage.getKey());
        if (target == null) {
          continue;
        }
        try {
          organizerWageCost =
              Math.addExact(
                  organizerWageCost,
                  wageAmountMilli(wage.getValue().wagePerLaborMilli(), target, cycleDays));
        } catch (ArithmeticException overflow) {
          organizerWageCost = 0L;
          wageRejection = "wage-quote-overflow";
          wageReasons.add(wageRejection);
          break;
        }
      }
    }
    // 6) 逐户预计所得（负净额保留）。
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> wageLaborByRole =
        new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      wageLaborByRole.merge(
          participant.role(),
          Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays),
          Math::addExact);
    }
    // 投入负担：只有 relation.inputSupplier 解析到某参与户时才归该户；否则归 organizer。
    Optional<HouseholdId> inputSupplierHousehold = Optional.empty();
    if (relation != null && relation.inputSupplier() instanceof Payee.ToActor toActor) {
      for (ProductionParticipant participant : roster.participants()) {
        if (toActor.actor().equals(HouseholdActors.of(participant.household()))) {
          inputSupplierHousehold = Optional.of(participant.household());
          break;
        }
      }
    }
    long inputBurden =
        rejection == null && market != null ? askValueMilli(market, probe.inputRequired()) : 0L;
    List<RosterHouseholdIncome> incomes = new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      Map<CommodityId, Long> share = sharesByParticipant.getOrDefault(key, Map.of());
      long productValue = 0L;
      Set<CommodityId> unpriced = new LinkedHashSet<>();
      if (market != null) {
        for (Map.Entry<CommodityId, Long> line : share.entrySet()) {
          if (!market.hasPrice(line.getKey())) {
            unpriced.add(line.getKey());
            continue;
          }
          productValue =
              Math.addExact(
                  productValue,
                  Math.multiplyExact(line.getValue(), market.bidPriceOf(line.getKey()))
                      / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
        }
      }
      long receivable = 0L;
      long payable = 0L;
      ProductionWageQuote quote = wageQuotes.get(participant.role());
      if (quote != null) {
        long labor = wageLaborByRole.getOrDefault(participant.role(), 0L);
        receivable = Math.multiplyExact(quote.wagePerLaborMilli(), labor) / 1000L;
      }
      if (participant.organizer()) {
        payable = organizerWageCost;
      }
      long participantInputBurden =
          inputSupplierHousehold.orElse(organizer).equals(participant.household())
              ? inputBurden
              : 0L;
      long net =
          Math.subtractExact(
              Math.addExact(productValue, receivable),
              Math.addExact(payable, participantInputBurden));
      String rowRejection = rejection != null ? rejection : wageRejection;
      Set<String> rowWageReasons = new LinkedHashSet<>(wageReasons);
      incomes.add(
          new RosterHouseholdIncome(
              unit.id(),
              plan.id(),
              participant.household(),
              participant.role(),
              participant.organizer(),
              share,
              productValue,
              receivable,
              payable,
              payable,
              participantInputBurden,
              net,
              net < 0L,
              unpriced,
              rowWageReasons,
              Optional.ofNullable(rowRejection)));
    }
    return Collections.unmodifiableList(incomes);
  }

  /**
   * ★ D2 §7.3：角色级资格逐户检查（`roles[].conditions`）。
   *
   * <p>★ 主体 = 该角色每个参与户；{@code MinimumExperience} 读 production 经验（冻结条件优先）， {@code ExternalAttribute}
   * 读该户属性事实；缺事实/经验不足 ⇒ 具名 {@code role-qualification:<role>:<household>:<reason>}。
   */
  static Optional<String> roleQualificationRejection(
      IndustryDefinitionView plan,
      ProductionRoster roster,
      ProductionRuntime runtime,
      Map<HouseholdId, ExternalAttributeFacts> facts) {
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionRoleDefinition role =
          plan.roles().stream()
              .filter(candidate -> candidate.id().equals(participant.role()))
              .findFirst()
              .orElse(null);
      if (role == null) {
        return Optional.of("role-unknown:" + participant.role().value());
      }
      for (io.mosire.simos.production.contract.ProductionAvailabilityCondition condition :
          role.conditions()) {
        if (condition
            instanceof
            io.mosire.simos.production.contract.ProductionAvailabilityCondition.MinimumExperience
                minimum) {
          long experience =
              runtime.currentCondition().isPresent()
                  ? runtime
                      .currentCondition()
                      .get()
                      .experienceOf(participant.household(), minimum.track())
                  : runtime.experienceOf(participant.household(), minimum.track());
          if (experience < minimum.minimumExperience()) {
            return Optional.of(
                "role-qualification:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value()
                    + ":"
                    + condition.describe());
          }
        } else {
          ExternalAttributeFacts participantFacts =
              facts.getOrDefault(participant.household(), ExternalAttributeFacts.EMPTY);
          ExternalAttributeFacts.Verdict verdict = participantFacts.evaluate(condition);
          if (!verdict.satisfied()) {
            return Optional.of(
                "role-qualification:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value()
                    + ":"
                    + verdict.reason());
          }
        }
      }
    }
    return Optional.empty();
  }

  /** 数量 × 本地 ask ÷ 1000（投入负担的唯一估值口径）。 */
  static long askValueMilli(MarketTable market, Map<CommodityId, Long> quantities) {
    if (market == null) {
      return 0L;
    }
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (!market.hasPrice(entry.getKey())) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(entry.getValue(), market.askPriceOf(entry.getKey()))
                  / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return total;
  }
}
