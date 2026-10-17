package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.BarterDiscountPolicy;
import io.mosire.simos.production.contract.BarterReferencePrice;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>W3 工资结算：直付 → 抵物 → 余款成债</b>（D-032 D3 §6/§7；U21）。
 *
 * <p>★★ <b>只处理有生产名册的实例</b>：名册路径的 organizer household 是唯一付款人；无名册的旧 {@code ProductionSettlement}
 * 货币工资路径按 D3 保留，本类不碰它 ⇒ 两条路径不会双付。
 *
 * <p>★★ <b>顺序钉死</b>：
 *
 * <ol>
 *   <li>按 {@code (unit, role, household)} 稳定序构造本次实际劳动义务；
 *   <li>先按付款人做 {@link WagePayerLocationPrecheck}：已有 unit/roster/posting 地点 ∪ 本次义务地点 &gt; 1 ⇒
 *       整组业务拒绝， 零 lookup / 零支付 / 零新债 / 零 carry 变化；
 *   <li>通过预检后先算完全部 due（carry 是逐义务状态），再对该组调用一次 {@link
 *       WagePayableInventoryLookup#payableInventory(ActorRef, ProductionLocation)}；
 *   <li>货币腿按 {@link AccountSnapshot} 的户级快照逐笔扣减；库存腿只消费该次 lookup 快照；余款才写工资债。
 * </ol>
 *
 * <p>★ <b>账户写口</b>：本类不直接改商品/货币表；所有支付都铸成 {@link TransferReason#WAGE_PAYMENT} 的 {@link Transfer}，再经
 * {@link EconomySettlement#applyTransfer} 唯一写口落账。
 */
public final class WageSettlement {

  private static final Logger LOG = EconomyLog.settlement();
  private static final Logger TRACE = EconomyLog.trace();
  private static final BigInteger MILLI = BigInteger.valueOf(1000L);
  private static final Comparator<Obligation> OBLIGATION_ORDER =
      Comparator.comparing((Obligation value) -> value.unit().value())
          .thenComparing(value -> value.role().value())
          .thenComparing(value -> value.worker().value());

  private WageSettlement() {}

  /** 一条被业务拒绝/跳过的义务（INFO 已具名；不是半支付）。 */
  public record Rejection(
      ProductionUnitId unit,
      ProductionRoleId role,
      HouseholdId worker,
      HouseholdId payer,
      String reason) {

    public Rejection {
      Objects.requireNonNull(unit, "Rejection.unit 不得为 null");
      Objects.requireNonNull(role, "Rejection.role 不得为 null");
      Objects.requireNonNull(worker, "Rejection.worker 不得为 null");
      Objects.requireNonNull(payer, "Rejection.payer 不得为 null");
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Rejection.reason 不得空白");
      }
    }
  }

  /** 本次工资结算的回执：逐义务读数 + carry 工作副本 + 同日还款排除集 + 业务拒绝。 */
  public record Result(
      List<WageSettlementReadout> readouts,
      Map<WageCarryKey, Long> carryRemainders,
      Set<DebtContractId> newWageDebtIds,
      List<Rejection> rejections) {

    public Result {
      Objects.requireNonNull(readouts, "Result.readouts 不得为 null");
      Objects.requireNonNull(carryRemainders, "Result.carryRemainders 不得为 null");
      Objects.requireNonNull(newWageDebtIds, "Result.newWageDebtIds 不得为 null");
      Objects.requireNonNull(rejections, "Result.rejections 不得为 null");
      readouts = List.copyOf(readouts);
      Map<WageCarryKey, Long> carryCopy = new LinkedHashMap<>(carryRemainders);
      carryRemainders = Collections.unmodifiableMap(carryCopy);
      newWageDebtIds = Collections.unmodifiableSet(new LinkedHashSet<>(newWageDebtIds));
      rejections = List.copyOf(rejections);
    }
  }

  /** 一条待结工资义务（内部；已按名册 organizer 解析出付款人）。 */
  private record Obligation(
      ProductionUnitId unit,
      ProductionRoleId role,
      HouseholdId worker,
      HouseholdId payer,
      ProductionLocation location,
      long tickLaborMilli,
      BarterDiscountPolicy discountPolicy) {}

  /** 逐付款人一组义务；组内按稳定义务序。 */
  private record PayerGroup(HouseholdId payer, List<Obligation> obligations) {}

  /** 已算定 due 的义务；支付阶段只消费这一份，不再重算 quote/carry。 */
  private record DueObligation(
      Obligation obligation,
      ActorRef workerActor,
      ProductionWageQuote quote,
      long carryBefore,
      ProductionWageBook.Due due) {}

  /** 一个结算地点内的库存预算（只来自一次 lookup 快照）。 */
  private record InventoryBudget(Map<CommodityId, Long> payable, boolean unavailable) {}

  /**
   * ★★★ <b>执行 W3 工资结算</b>。
   *
   * @param day 世界日；不得为负
   * @param dueCycle 新工资债的到期周期（调用方给的当前周期 + 1）
   * @param dayView 当刻经济只读视图（posting / roster / definitions / markets）
   * @param units 当刻生产实例读口（payer 已有地点汇总）
   * @param accountSnapshot W3 开始时的账户只读快照（货币腿的唯一余额来源）
   * @param householdGoods 家户商品活表（唯一写口 {@link EconomySettlement#applyTransfer} 会写它）
   * @param householdMoney 家户货币活表
   * @param householdFrozenGoods 家户商品冻结活表
   * @param householdFrozenMoney 家户货币冻结活表
   * @param householdOfActor actor → 家户身份
   * @param debts 债务工作表（唯一写口 {@link DebtContractBook#upsert}）
   * @param ledger 当日账本累加器（工资转移的唯一铸造口）
   * @param productionDay 本日生产回执（due 的唯一实际劳动来源）
   * @param topology 市场拓扑（unit 地点 → 市场区价表）
   * @param inventoryLookup D4 的可支付库存唯一读口
   */
  static Result settle(
      long day,
      long dueCycle,
      EconomyDayView dayView,
      Map<ProductionUnitId, ProductionProcess> units,
      AccountSnapshot accountSnapshot,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      ProductionLedger.Accumulator ledger,
      ProductionDayResult productionDay,
      MarketTopology topology,
      WagePayableInventoryLookup inventoryLookup) {
    Objects.requireNonNull(dayView, "dayView");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(accountSnapshot, "accountSnapshot");
    Objects.requireNonNull(householdGoods, "householdGoods");
    Objects.requireNonNull(householdMoney, "householdMoney");
    Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods");
    Objects.requireNonNull(householdFrozenMoney, "householdFrozenMoney");
    Objects.requireNonNull(householdOfActor, "householdOfActor");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(productionDay, "productionDay");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(inventoryLookup, "inventoryLookup（D4-C，不可用请注入 Unavailable*）");
    if (day < 0L) {
      throw new IllegalArgumentException("WageSettlement.day 不得为负: " + day);
    }
    if (dueCycle < 0L) {
      throw new IllegalArgumentException("WageSettlement.dueCycle 不得为负: " + dueCycle);
    }

    Map<ProductionUnitId, ProductionRoster> rosters = dayView.productionRosters();
    ProductionWageBook wageBook = new ProductionWageBook(dayView);
    List<Rejection> rejections = new ArrayList<>();
    List<Obligation> obligations = collectObligations(dayView, rosters, units, productionDay);
    obligations.sort(OBLIGATION_ORDER);

    Map<HouseholdId, List<Obligation>> byPayer = new LinkedHashMap<>();
    for (Obligation obligation : obligations) {
      byPayer.computeIfAbsent(obligation.payer(), ignored -> new ArrayList<>()).add(obligation);
    }
    List<HouseholdId> payers = new ArrayList<>(byPayer.keySet());
    payers.sort(Comparator.comparing(HouseholdId::value));

    List<WageSettlementReadout> readouts = new ArrayList<>();
    Set<DebtContractId> newWageDebtIds = new LinkedHashSet<>();
    for (HouseholdId payer : payers) {
      List<Obligation> groupObligations = new ArrayList<>(byPayer.get(payer));
      groupObligations.sort(OBLIGATION_ORDER);
      PayerGroup group = new PayerGroup(payer, groupObligations);
      ActorRef payerActor = HouseholdActors.of(payer);
      Set<ProductionLocation> existing =
          existingSettlementLocations(payer, rosters, units, dayView);
      Set<ProductionLocation> candidates = new LinkedHashSet<>();
      for (Obligation obligation : group.obligations()) {
        candidates.add(obligation.location());
      }
      WagePayerLocationDecision decision =
          WagePayerLocationPrecheck.standard()
              .evaluate(new WagePayerLocationSnapshot(payerActor, existing, candidates));
      if (decision == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
        String reason = "wage-payer-location-unsupported";
        rejectGroup(group, reason, rejections);
        continue;
      }

      ProductionLocation settlementLocation = requireSingleSettlementLocation(group);
      requireSpecifiedLocation(settlementLocation, payer);
      AccountSnapshot.SnapshotAccount payerAccount =
          payerAccountOrNull(accountSnapshot, payerActor);
      if (payerAccount == null) {
        String reason = "wage-payer-account-missing";
        rejectGroup(group, reason, rejections);
        continue;
      }
      // ★★ 预检/账户守卫都在 due 计算之前：整组拒绝时 carry 一个字节不动。
      InventoryBudget inventory =
          inventoryBudget(inventoryLookup, payerActor, settlementLocation, payer);
      // ★★ 两阶段：先把全组 due 算完（carry 写在工作副本），再按稳定序支付；支付阶段不重读 quote。
      List<DueObligation> dueObligations = new ArrayList<>();
      for (Obligation obligation : group.obligations()) {
        ActorRef workerActor = HouseholdActors.of(obligation.worker());
        if (accountSnapshot.actorKeyOrNull(workerActor) == null) {
          rejectOne(obligation, "wage-worker-account-missing", rejections);
          continue;
        }
        if (obligation.worker().equals(payer)) {
          rejectOne(obligation, "wage-organizer-self-employment", rejections);
          continue;
        }
        ProductionWageQuote quote =
            wageBook
                .quoteFor(obligation.unit(), obligation.role())
                .orElseThrow(
                    () ->
                        contractFault(
                            "wage-quote-missing",
                            obligation.unit().value() + "|" + obligation.role().value(),
                            null));
        long carryBefore =
            wageBook.carryOf(obligation.unit(), obligation.role(), obligation.worker());
        ProductionWageBook.Due due;
        try {
          due =
              wageBook.dueFor(
                  obligation.unit(),
                  obligation.role(),
                  obligation.worker(),
                  obligation.tickLaborMilli());
        } catch (RuntimeException failure) {
          throw contractFault(
              "wage-quote-overflow",
              obligation.unit().value()
                  + "|"
                  + obligation.role().value()
                  + "|"
                  + obligation.worker().value()
                  + " reason="
                  + failure.getMessage(),
              failure);
        }
        if (!due.currency().equals(quote.currency())) {
          throw contractFault(
              "wage-currency-mismatch",
              obligation.unit().value()
                  + "|"
                  + obligation.role().value()
                  + " dueCurrency="
                  + due.currency()
                  + " quoteCurrency="
                  + quote.currency(),
              null);
        }
        dueObligations.add(new DueObligation(obligation, workerActor, quote, carryBefore, due));
      }

      Map<CurrencyId, Long> moneyBudget = spendableMoney(payerAccount);
      Map<CommodityId, Long> inventoryRemaining = new LinkedHashMap<>(inventory.payable());
      List<CommodityId> barterOrder = barterOrder(inventoryRemaining);
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "WAGE_BARTER_ORDER",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "payer",
                    payer.value(),
                    "location",
                    settlementLocation.value(),
                    "commodities",
                    barterOrder.stream().map(CommodityId::value).toList(),
                    "initialPayable",
                    inventory.payable()));
      }
      long dueGroup = 0L;
      long paidMoneyGroup = 0L;
      long paidBarterGroup = 0L;
      long arrearsGroup = 0L;
      int processed = 0;
      for (DueObligation settled : dueObligations) {
        Obligation obligation = settled.obligation();
        long remaining = settled.due().dueMilli();
        long paidMoney =
            Math.min(remaining, moneyBudget.getOrDefault(settled.due().currency(), 0L));
        if (paidMoney > 0L) {
          moneyBudget.put(
              settled.due().currency(), moneyBudget.get(settled.due().currency()) - paidMoney);
          remaining -= paidMoney;
        }
        Map<CommodityId, Long> barterQuantity = new LinkedHashMap<>();
        Map<CommodityId, Long> barterPaidValue = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        if (inventory.unavailable() && remaining > 0L) {
          reasons.add("wage-inventory-scope-unavailable");
        }
        if (!inventory.unavailable() && remaining > 0L) {
          remaining =
              payBarter(
                  obligation,
                  remaining,
                  barterOrder,
                  inventoryRemaining,
                  dayView,
                  topology,
                  settled.due().currency(),
                  barterQuantity,
                  barterPaidValue,
                  reasons);
        }
        long arrears = remaining;
        Optional<DebtContractId> debtId = Optional.empty();
        if (arrears > 0L) {
          DebtContract contract =
              DebtContractBook.upsert(
                  debts,
                  payer,
                  obligation.worker(),
                  DebtUnit.money(settled.due().currency()),
                  DebtTerms.wageArrearsDefault(),
                  DebtSource.WAGE_ARREARS,
                  arrears,
                  day,
                  OptionalLong.of(dueCycle));
          debtId = Optional.of(contract.id());
          newWageDebtIds.add(contract.id());
        }
        Map<CurrencyId, Long> moneyLegs =
            paidMoney > 0L
                ? new LinkedHashMap<>(Map.of(settled.due().currency(), paidMoney))
                : Map.of();
        if (paidMoney > 0L || !barterQuantity.isEmpty()) {
          Transfer transfer =
              ledger.mint(
                  payerActor,
                  settled.workerActor(),
                  requireHex(settlementLocation, payer),
                  barterQuantity,
                  moneyLegs,
                  TransferReason.WAGE_PAYMENT);
          EconomySettlement.applyTransfer(
              householdGoods,
              householdMoney,
              householdFrozenGoods,
              householdFrozenMoney,
              householdOfActor,
              transfer);
        }

        long paidBarterValue = sumBarterValue(barterPaidValue);
        WageSettlementReadout readout =
            new WageSettlementReadout(
                obligation.unit(),
                obligation.role(),
                obligation.worker(),
                payer,
                settled.due().currency(),
                obligation.tickLaborMilli(),
                settled.quote().wagePerLaborMilli(),
                settled.carryBefore(),
                settled.due().carryRemainder(),
                settled.due().dueMilli(),
                paidMoney,
                barterQuantity,
                barterPaidValue,
                arrears,
                debtId,
                reasons);
        readouts.add(readout);
        if (LOG.isDebugEnabled()) {
          EventLog.channel(LOG)
              .debug(
                  LogEvent.of(
                      "WAGE_SETTLEMENT_OBLIGATION",
                      EconomyLogSource.ECONOMY_SETTLEMENT,
                      "day",
                      day,
                      "unit",
                      obligation.unit().value(),
                      "role",
                      obligation.role().value(),
                      "worker",
                      obligation.worker().value(),
                      "payer",
                      payer.value(),
                      "carryBefore",
                      settled.carryBefore(),
                      "carryAfter",
                      settled.due().carryRemainder(),
                      "dueMilli",
                      settled.due().dueMilli(),
                      "paidMoneyMilli",
                      paidMoney,
                      "paidBarterValueMilli",
                      paidBarterValue,
                      "arrearsMilli",
                      arrears));
        }
        dueGroup = Math.addExact(dueGroup, settled.due().dueMilli());
        paidMoneyGroup = Math.addExact(paidMoneyGroup, paidMoney);
        paidBarterGroup = Math.addExact(paidBarterGroup, paidBarterValue);
        arrearsGroup = Math.addExact(arrearsGroup, arrears);
        processed++;
      }
      if (processed > 0L) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "WAGE_SETTLEMENT_PAYER",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "payer",
                    payer.value(),
                    "location",
                    settlementLocation.value(),
                    "obligations",
                    processed,
                    "dueMilli",
                    dueGroup,
                    "paidMoneyMilli",
                    paidMoneyGroup,
                    "paidBarterValueMilli",
                    paidBarterGroup,
                    "arrearsMilli",
                    arrearsGroup,
                    "inventoryScopeUnavailable",
                    inventory.unavailable()));
      }
    }

    List<WageSettlementReadout> orderedReadouts = new ArrayList<>(readouts);
    orderedReadouts.sort(
        Comparator.comparing((WageSettlementReadout value) -> value.unit().value())
            .thenComparing(value -> value.role().value())
            .thenComparing(value -> value.worker().value()));
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "WAGE_SETTLEMENT_DAY",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "day",
                day,
                "readouts",
                readouts.size(),
                "newWageDebts",
                newWageDebtIds.size(),
                "rejections",
                rejections.size()));
    return new Result(orderedReadouts, wageBook.carryRemainders(), newWageDebtIds, rejections);
  }

  /** 只收集“有生产名册 + MONEY_WAGE 角色 + 本次实际劳动 &gt; 0”的义务。 */
  private static List<Obligation> collectObligations(
      EconomyDayView dayView,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<ProductionUnitId, ProductionProcess> units,
      ProductionDayResult productionDay) {
    List<Obligation> result = new ArrayList<>();
    for (ProductionReceipt receipt : productionDay.receipts()) {
      ProductionRoster roster = rosters.get(receipt.unit());
      if (roster == null) {
        // ★ 名册路径之外是旧 ProductionSettlement 的保留路径；W3 不参与，避免双付。
        continue;
      }
      ProductionProcess process = units.get(receipt.unit());
      if (process == null) {
        throw contractFault(
            "wage-posting-unit-unknown", "回执 unit 不在当刻生产实例表: " + receipt.unit().value(), null);
      }
      IndustryDefinitionView industry =
          dayView
              .definitions()
              .industry(receipt.plan())
              .orElseThrow(
                  () ->
                      contractFault(
                          "wage-posting-unit-unknown",
                          "receipt plan 无生产定义: " + receipt.plan().value(),
                          null));
      for (Map.Entry<ProductionParticipantKey, Long> contribution :
          receipt.laborContributionsMilli().entrySet()) {
        ProductionParticipantKey key = contribution.getKey();
        ProductionRoleDefinition role = requireRole(industry, key.role());
        if (!(role.payment() instanceof RolePayment.MoneyWage moneyWage)) {
          continue;
        }
        result.add(
            new Obligation(
                receipt.unit(),
                key.role(),
                key.household(),
                roster.organizerHousehold(),
                process.location(),
                contribution.getValue(),
                moneyWage.barterDiscount()));
      }
    }
    return result;
  }

  private static ProductionRoleDefinition requireRole(
      IndustryDefinitionView industry, ProductionRoleId role) {
    for (ProductionRoleDefinition definition : industry.roles()) {
      if (definition.id().equals(role)) {
        return definition;
      }
    }
    throw contractFault(
        "wage-posting-role-unknown",
        "industry=" + industry.id().value() + " role=" + role.value(),
        null);
  }

  private static Set<ProductionLocation> existingSettlementLocations(
      HouseholdId payer,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<ProductionUnitId, ProductionProcess> units,
      EconomyDayView dayView) {
    Set<ProductionLocation> locations = new LinkedHashSet<>();
    for (ProductionProcess unit : units.values()) {
      HouseholdId operatorHousehold = householdOfActor(unit.operator());
      ProductionRoster roster = rosters.get(unit.id());
      HouseholdId organizerHousehold = roster == null ? null : roster.organizerHousehold();
      boolean belongs = payer.equals(operatorHousehold) || payer.equals(organizerHousehold);
      if (!belongs) {
        continue;
      }
      ProductionLocation location = unit.location();
      if (location == null || !location.isSpecified()) {
        throw contractFault(
            "asset-location-missing",
            "付款人已有实例地点缺失/未落定: payer=" + payer.value() + " unit=" + unit.id().value(),
            null);
      }
      locations.add(location);
      boolean hasPosting =
          dayView.productionWagePostings().keySet().stream()
              .anyMatch(key -> key.unit().equals(unit.id()));
      if (hasPosting) {
        locations.add(location);
      }
    }
    return locations;
  }

  private static HouseholdId householdOfActor(ActorRef actor) {
    // operator 非家户时本方法无法替它猜一个付款人；返回 null，由 roster organizer 兜底。
    try {
      return HouseholdActors.householdOf(actor);
    } catch (RuntimeException notHousehold) {
      return null;
    }
  }

  private static ProductionLocation requireSingleSettlementLocation(PayerGroup group) {
    LinkedHashSet<ProductionLocation> locations = new LinkedHashSet<>();
    for (Obligation obligation : group.obligations()) {
      locations.add(obligation.location());
    }
    if (locations.size() != 1) {
      throw contractFault(
          "wage-payer-location-unsupported",
          "通过预检的组必须恰有一个本次地点: payer=" + group.payer().value() + " locations=" + locations,
          null);
    }
    return locations.iterator().next();
  }

  private static void rejectGroup(PayerGroup group, String reason, List<Rejection> rejections) {
    for (Obligation obligation : group.obligations()) {
      rejectOne(obligation, reason, rejections);
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "WAGE_SETTLEMENT_REJECTED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "payer",
                group.payer().value(),
                "reason",
                reason,
                "obligations",
                group.obligations().size()));
  }

  private static void rejectOne(Obligation obligation, String reason, List<Rejection> rejections) {
    rejections.add(
        new Rejection(
            obligation.unit(), obligation.role(), obligation.worker(), obligation.payer(), reason));
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "WAGE_SETTLEMENT_OBLIGATION_REJECTED",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "unit",
                obligation.unit().value(),
                "role",
                obligation.role().value(),
                "worker",
                obligation.worker().value(),
                "payer",
                obligation.payer().value(),
                "reason",
                reason));
  }

  private static AccountSnapshot.SnapshotAccount payerAccountOrNull(
      AccountSnapshot snapshot, ActorRef payerActor) {
    AccountPartitionKey key = snapshot.actorKeyOrNull(payerActor);
    return key == null ? null : snapshot.requireAccount(key);
  }

  private static Map<CurrencyId, Long> spendableMoney(AccountSnapshot.SnapshotAccount account) {
    Map<CurrencyId, Long> spendable = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
      long frozen = account.frozenMoney().getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, entry.getValue() - frozen);
      if (available > 0L) {
        spendable.put(entry.getKey(), available);
      }
    }
    return spendable;
  }

  private static InventoryBudget inventoryBudget(
      WagePayableInventoryLookup lookup,
      ActorRef payer,
      ProductionLocation location,
      HouseholdId payerHousehold) {
    Optional<Map<CommodityId, Long>> payable = lookup.payableInventory(payer, location);
    if (payable.isEmpty()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "WAGE_INVENTORY_SCOPE_UNAVAILABLE",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "payer",
                  payerHousehold.value(),
                  "location",
                  location.value(),
                  "reason",
                  "wage-inventory-scope-unavailable"));
      return new InventoryBudget(Map.of(), true);
    }
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : payable.get().entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return new InventoryBudget(copy, false);
  }

  private static List<CommodityId> barterOrder(Map<CommodityId, Long> inventory) {
    List<CommodityId> ordered = new ArrayList<>(inventory.keySet());
    ordered.sort(
        Comparator.comparingLong((CommodityId commodity) -> inventory.getOrDefault(commodity, 0L))
            .reversed()
            .thenComparing(CommodityId::value));
    return ordered;
  }

  private static long payBarter(
      Obligation obligation,
      long remainingDue,
      List<CommodityId> barterOrder,
      Map<CommodityId, Long> inventoryRemaining,
      EconomyDayView dayView,
      MarketTopology topology,
      CurrencyId currency,
      Map<CommodityId, Long> barterQuantity,
      Map<CommodityId, Long> barterPaidValue,
      List<String> reasons) {
    long remaining = remainingDue;
    for (CommodityId commodity : barterOrder) {
      if (remaining <= 0L) {
        break;
      }
      MarketTable market = marketOf(obligation, dayView, topology);
      if (market == null || !market.hasPrice(commodity)) {
        reasons.add("wage-barter-price-missing:" + commodity.value());
        continue;
      }
      if (!market.numeraire().equals(currency)) {
        reasons.add("wage-barter-not-deliverable");
        continue;
      }
      long reference = referencePrice(market, commodity, obligation.discountPolicy());
      if (reference <= 0L) {
        reasons.add("wage-barter-price-missing:" + commodity.value());
        continue;
      }
      long unitValue =
          divideFloor(
              multiplyExact(reference, obligation.discountPolicy().discountPerMille()), 1000L);
      if (unitValue <= 0L) {
        reasons.add("wage-barter-discount-zero:" + commodity.value());
        continue;
      }
      long available = inventoryRemaining.getOrDefault(commodity, 0L);
      if (available <= 0L) {
        continue;
      }
      BigInteger wanted =
          BigInteger.valueOf(remaining).multiply(MILLI).divide(BigInteger.valueOf(unitValue));
      long qWanted = wanted.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
      long qPay = Math.min(available, qWanted);
      if (qPay <= 0L) {
        continue;
      }
      long paidValue = divideFloor(multiplyExact(qPay, unitValue), 1000L);
      if (paidValue <= 0L) {
        reasons.add("wage-barter-discount-zero:" + commodity.value());
        continue;
      }
      if (paidValue > remaining) {
        throw contractFault(
            "wage-barter-value-overflow",
            "抵物价值不得超过剩余 due: commodity="
                + commodity.value()
                + " value="
                + paidValue
                + " remaining="
                + remaining,
            null);
      }
      inventoryRemaining.put(commodity, available - qPay);
      barterQuantity.put(commodity, qPay);
      barterPaidValue.put(commodity, paidValue);
      remaining -= paidValue;
      if (TRACE.isTraceEnabled()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "WAGE_BARTER_LEG",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "worker",
                    obligation.worker().value(),
                    "unit",
                    obligation.unit().value(),
                    "role",
                    obligation.role().value(),
                    "commodity",
                    commodity.value(),
                    "quantity",
                    qPay,
                    "unitValue",
                    unitValue,
                    "paidValueMilli",
                    paidValue,
                    "remainingMilli",
                    remaining));
      }
    }
    return remaining;
  }

  private static long referencePrice(
      MarketTable market, CommodityId commodity, BarterDiscountPolicy policy) {
    BarterReferencePrice reference = policy.referencePrice();
    return switch (reference) {
      case LOCAL_BID -> market.bidPriceOf(commodity);
      case LOCAL_ASK -> market.askPriceOf(commodity);
    };
  }

  private static MarketTable marketOf(
      Obligation obligation, EconomyDayView dayView, MarketTopology topology) {
    HexCoord hex = requireHex(obligation.location(), obligation.payer());
    if (topology == null || !topology.contains(hex)) {
      return null;
    }
    return dayView.marketTables().get(new MarketZoneId(topology.regionOf(hex).node().nodeId()));
  }

  private static long sumBarterValue(Map<CommodityId, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  private static HexCoord requireHex(ProductionLocation location, HouseholdId payer) {
    if (location == null || !location.isSpecified()) {
      throw contractFault(
          "asset-location-missing", "payer=" + payer.value() + " location=" + location, null);
    }
    try {
      return HexCoord.parse(location.value());
    } catch (RuntimeException failure) {
      throw contractFault(
          "asset-location-missing",
          "payer=" + payer.value() + " location=" + location.value(),
          failure);
    }
  }

  private static void requireSpecifiedLocation(ProductionLocation location, HouseholdId payer) {
    if (location == null || !location.isSpecified()) {
      throw contractFault(
          "asset-location-missing", "payer=" + payer.value() + " location=" + location, null);
    }
    try {
      HexCoord.parse(location.value());
    } catch (RuntimeException failure) {
      throw contractFault(
          "asset-location-missing",
          "payer=" + payer.value() + " location=" + location.value(),
          failure);
    }
  }

  private static long multiplyExact(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      throw contractFault("wage-barter-value-overflow", left + " * " + right + " 溢出", overflow);
    }
  }

  private static long divideFloor(long numerator, long denominator) {
    return BigInteger.valueOf(numerator).divide(BigInteger.valueOf(denominator)).longValueExact();
  }

  private static IllegalStateException contractFault(String word, String detail, Throwable cause) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "WAGE_SETTLEMENT_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail,
                "cause",
                cause == null ? "-" : cause.getClass().getSimpleName()));
    return new IllegalStateException(word + ": " + detail, cause);
  }
}
