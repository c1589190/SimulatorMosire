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
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.map.hex.HexCoord;
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
 * ★★★ <b>W3 工资结算：只付现金 → 未付差额成普通工资债</b>（D-032 P4a）。
 *
 * <p>★★ <b>只处理有生产名册的实例</b>：名册路径的 organizer household 是唯一付款人；无名册的旧 {@code ProductionSettlement}
 * 货币工资路径按 D3 保留，本类不碰它 ⇒ 两条路径不会双付。
 *
 * <p>★★ <b>顺序钉死</b>：
 *
 * <ol>
 *   <li>按 {@code (unit, role, household)} 稳定序构造本次实际劳动义务；
 *   <li>先按付款人做 {@link WagePayerLocationPrecheck}：已有 unit/roster 地点 ∪ 本次义务地点 &gt; 1 ⇒
 *       整组业务拒绝，零支付 / 零新债 / 零 carry 变化；
 *   <li>通过预检后先算完全部 due（carry 是逐义务状态），再按稳定序逐笔付现金；
 *   <li>每笔 {@code paidMoney = min(dueRemaining, cash[currency])}，现金用完为止；未付差额立即形成
 *       {@link DebtSource#WAGE_ARREARS} 的货币普通债（{@code DebtTerms.wageArrearsDefault()}，0‰）。
 * </ol>
 *
 * <p>★ <b>不读库存/市场价/barter</b>：工资只付货币腿，绝不拿商品库存抵付，也不生成商品腿。
 *
 * <p>★ <b>账户写口</b>：本类不直接改商品/货币表；所有支付都铸成 {@link TransferReason#WAGE_PAYMENT} 的 {@link Transfer}，再经
 * {@link EconomySettlement#applyTransfer} 唯一写口落账。
 */
public final class WageSettlement {

  private static final Logger LOG = EconomyLog.settlement();
  private static final Logger TRACE = EconomyLog.trace();
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

  /** 本次工资结算的回执：逐义务读数 + carry 工作副本 + 业务拒绝。 */
  public record Result(
      List<WageSettlementReadout> readouts,
      Map<WageCarryKey, Long> carryRemainders,
      List<Rejection> rejections) {

    public Result {
      Objects.requireNonNull(readouts, "Result.readouts 不得为 null");
      Objects.requireNonNull(carryRemainders, "Result.carryRemainders 不得为 null");
      Objects.requireNonNull(rejections, "Result.rejections 不得为 null");
      readouts = List.copyOf(readouts);
      Map<WageCarryKey, Long> carryCopy = new LinkedHashMap<>(carryRemainders);
      carryRemainders = Collections.unmodifiableMap(carryCopy);
      rejections = List.copyOf(rejections);
    }
  }

  /**
   * 一条待结工资义务（内部；已按名册 organizer 解析出付款人）。
   *
   * <p>★ P4c3：{@code anchorKey} 是 {@code (unit 所在市场区, unit.industry, role)} 的 U20 锚键；定不出市场区时为空。
   * 缺锚 ⇒ 该义务按 {@code wage-anchor-missing} 业务拒绝，<b>不按 0、不读旧表</b>。
   */
  private record Obligation(
      ProductionUnitId unit,
      ProductionRoleId role,
      HouseholdId worker,
      HouseholdId payer,
      ProductionLocation location,
      long tickLaborMilli,
      Optional<WageAnchorKey> anchorKey) {

    private Obligation {
      Objects.requireNonNull(anchorKey, "Obligation.anchorKey 不得为 null（缺键用 Optional.empty()）");
    }
  }

  /** 逐付款人一组义务；组内按稳定义务序。 */
  private record PayerGroup(HouseholdId payer, List<Obligation> obligations) {}

  /** 已算定 due 的义务；支付阶段只消费这一份，不再重算 quote/carry。 */
  private record DueObligation(
      Obligation obligation,
      ActorRef workerActor,
      ProductionWageQuote quote,
      long carryBefore,
      ProductionWageBook.Due due) {}

  /**
   * ★★★ <b>执行 W3 工资结算</b>。
   *
   * @param day 世界日；不得为负
   * @param dueCycle 新工资债的到期周期（调用方给的当前周期 + 1）
   * @param dayView 当刻经济只读视图（锚 / roster / definitions）
   * @param units ★★ <b>本 tick 回执所在同一 revision 的当刻生产实例读口</b>（payer 已有地点汇总 + 回执 unit 地点解析）。调用方必须在
   *     {@code driveProductionDay} 之后取当刻权威候选态（{@code session.sheet().units()}），<b>不得</b>传日结算入口抓的
   *     tick 前快照 —— 本 tick 内 {@code createInstance} 新建的 unit 只存在于前者。
   * @param accountSnapshot W3 开始时的账户只读快照（货币腿的唯一余额来源）
   * @param householdGoods 家户商品活表（唯一写口 {@link EconomySettlement#applyTransfer} 会读它；本类不读库存）
   * @param householdMoney 家户货币活表
   * @param householdFrozenGoods 家户商品冻结活表
   * @param householdFrozenMoney 家户货币冻结活表
   * @param householdOfActor actor → 家户身份
   * @param debts 债务工作表（唯一写口 {@link DebtContractBook#upsert}）
   * @param ledger 当日账本累加器（工资转移的唯一铸造口）
   * @param productionDay 本日生产回执（due 的唯一实际劳动来源）
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
      ProductionDayResult productionDay) {
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
    if (day < 0L) {
      throw new IllegalArgumentException("WageSettlement.day 不得为负: " + day);
    }
    if (dueCycle < 0L) {
      throw new IllegalArgumentException("WageSettlement.dueCycle 不得为负: " + dueCycle);
    }

    Map<ProductionUnitId, ProductionRoster> rosters = dayView.productionRosters();
    ProductionWageBook wageBook = new ProductionWageBook(dayView);
    // ★★★ P4c1：U20 锚的**同一 tick 快照**（M8 开始时刻冻结）；M8 只读不写锚（调价是 P4c2），
    //   故本 tick 的 due 与 M3/M6 的锁不会被任何"新价"追改。
    Map<WageAnchorKey, ProductionWageAnchor> wageAnchors =
        new WageAnchorBook(dayView).snapshot();
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
    int newWageDebts = 0;
    for (HouseholdId payer : payers) {
      List<Obligation> groupObligations = new ArrayList<>(byPayer.get(payer));
      groupObligations.sort(OBLIGATION_ORDER);
      PayerGroup group = new PayerGroup(payer, groupObligations);
      ActorRef payerActor = HouseholdActors.of(payer);
      Set<ProductionLocation> existing =
          existingSettlementLocations(payer, rosters, units);
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
        // ★★★ P4c3：due 的 quote 唯一来自同一 tick 锚快照；缺锚 ⇒ 具名 wage-anchor-missing
        //   业务拒绝（ERROR 日志），不支付、不成债、carry 不动。
        ProductionProcess obligationUnit = units.get(obligation.unit());
        WageQuoteBridge.WageQuoteResolution resolution =
            WageQuoteBridge.resolveWageQuote(
                day,
                "m8-wage-due",
                obligationUnit,
                obligation.role(),
                obligation.anchorKey(),
                wageAnchors,
                true);
        if (resolution.quote().isEmpty()) {
          throw contractFault(
              WageQuoteBridge.WAGE_ANCHOR_MISSING,
              "M8 工资义务缺 U20 锚（契约故障，整批不提交）: "
                  + obligation.unit().value()
                  + "|"
                  + obligation.role().value()
                  + "|"
                  + obligation.worker().value(),
              null);
        }
        ProductionWageQuote quote = resolution.quote().get();
        long carryBefore =
            wageBook.carryOf(obligation.unit(), obligation.role(), obligation.worker());
        ProductionWageBook.Due due;
        try {
          due =
              wageBook.dueFor(
                  quote,
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

      // ★ 付款人现金预算 = 账户余额 − 冻结；现金用完为止，不按比例摊。
      Map<CurrencyId, Long> moneyBudget = spendableMoney(payerAccount);
      long dueGroup = 0L;
      long paidMoneyGroup = 0L;
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
        long arrears = remaining;
        Optional<DebtContractId> debtId = Optional.empty();
        if (arrears > 0L) {
          DebtContract contract =
              DebtContractBook.upsert(
                  debts,
                  payer,
                  obligation.worker(),
                  new DebtUnit.Money(settled.due().currency()),
                  DebtTerms.wageArrearsDefault(),
                  DebtSource.WAGE_ARREARS,
                  arrears,
                  day,
                  OptionalLong.of(dueCycle));
          debtId = Optional.of(contract.id());
          newWageDebts++;
          if (TRACE.isTraceEnabled()) {
            EventLog.channel(TRACE)
                .trace(
                    LogEvent.of(
                        "WAGE_SETTLEMENT_DEBT",
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
                        "currency",
                        settled.due().currency().value(),
                        "arrearsMilli",
                        arrears,
                        "debtContractId",
                        contract.id().value()));
          }
        }
        if (paidMoney > 0L) {
          Map<CurrencyId, Long> moneyLegs =
              new LinkedHashMap<>(Map.of(settled.due().currency(), paidMoney));
          Transfer transfer =
              ledger.mint(
                  payerActor,
                  settled.workerActor(),
                  requireHex(settlementLocation, payer),
                  Map.of(),
                  moneyLegs,
                  TransferReason.WAGE_PAYMENT);
          EconomySettlement.applyTransfer(
              householdGoods,
              householdMoney,
              householdFrozenGoods,
              householdFrozenMoney,
              householdOfActor,
              transfer);
          if (TRACE.isTraceEnabled()) {
            EventLog.channel(TRACE)
                .trace(
                    LogEvent.of(
                        "WAGE_SETTLEMENT_TRANSFER",
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
                        "currency",
                        settled.due().currency().value(),
                        "paidMoneyMilli",
                        paidMoney,
                        "transferId",
                        transfer.id().value()));
          }
        }

        WageSettlementReadout readout =
            new WageSettlementReadout(
                obligation.unit(),
                obligation.role(),
                obligation.anchorKey(),
                obligation.worker(),
                payer,
                settled.due().currency(),
                obligation.tickLaborMilli(),
                settled.quote().wagePerLaborMilli(),
                settled.carryBefore(),
                settled.due().carryRemainder(),
                settled.due().dueMilli(),
                paidMoney,
                arrears,
                debtId,
                List.of());
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
                      "currency",
                      settled.due().currency().value(),
                      "carryBefore",
                      settled.carryBefore(),
                      "carryAfter",
                      settled.due().carryRemainder(),
                      "dueMilli",
                      settled.due().dueMilli(),
                      "paidMoneyMilli",
                      paidMoney,
                      "arrearsMilli",
                      arrears));
        }
        dueGroup = Math.addExact(dueGroup, settled.due().dueMilli());
        paidMoneyGroup = Math.addExact(paidMoneyGroup, paidMoney);
        arrearsGroup = Math.addExact(arrearsGroup, arrears);
        processed++;
      }
      if (processed > 0) {
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
                    "arrearsMilli",
                    arrearsGroup));
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
                newWageDebts,
                "rejections",
                rejections.size()));
    return new Result(orderedReadouts, wageBook.carryRemainders(), rejections);
  }

  /** 只收集“有生产名册 + MONEY_WAGE 角色”的义务。 */
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
      // ★★ W5-A：`units` = 回执同一 revision 的当刻实例表（调用方从 session.sheet().units() 取）。
      //   这里查不到 = 真的跨切片不一致（不是"快照过期"）⇒ 仍按契约故障 fail-closed。
      ProductionProcess process = units.get(receipt.unit());
      if (process == null) {
        throw contractFault(
            "wage-obligation-unit-unknown", "回执 unit 不在当刻生产实例表: " + receipt.unit().value(), null);
      }
      IndustryDefinitionView industry =
          dayView
              .definitions()
              .industry(receipt.plan())
              .orElseThrow(
                  () ->
                      contractFault(
                          "wage-obligation-unit-unknown",
                          "receipt plan 无生产定义: " + receipt.plan().value(),
                          null));
      for (Map.Entry<ProductionParticipantKey, Long> contribution :
          receipt.laborContributionsMilli().entrySet()) {
        ProductionParticipantKey key = contribution.getKey();
        ProductionRoleDefinition role = requireRole(industry, key.role());
        if (!(role.payment() instanceof RolePayment.MoneyWage)) {
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
                WageQuoteBridge.wageAnchorKeyOf(dayView, process, key.role())));
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
        "wage-obligation-role-unknown",
        "industry=" + industry.id().value() + " role=" + role.value(),
        null);
  }

  private static Set<ProductionLocation> existingSettlementLocations(
      HouseholdId payer,
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<ProductionUnitId, ProductionProcess> units) {
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
