package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceMoneyByCurrency;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalLongs;
import static io.mosire.simos.economy.time.SettlementMath.spendableGoods;
import static io.mosire.simos.economy.time.SettlementMath.spendableMoney;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>P2e：市场日债务清算（S7-M2）的唯一执行器</b>。
 *
 * <pre>
 * 触发   = 只在市场轮（day % 5 == 0 且市场非空）；由 {@link EconomyDayPipeline} 在 S6 工资之后、S7-A 市场之前调用（S7-M2）。
 * 范围   = principal > 0 且非 {@link DebtContractBook#isSelfHeld} 的合同。
 * 债务人 = HouseholdId.value 升序。
 * 债权人 = 同一债务人：纯库存财富降序 → HouseholdId.value 升序 → DebtContractId.value 升序；
 *          财富快照在 S7-M2 开始时冻结（{@link PureInventoryWealthBook#snapshot}）。
 * 介质   = ① 货币（债务币种优先；其它币种经本区 CurrencyValuation，缺价具名跳过，不 1:1）
 *          ② 商品库存（低估值优先 + CommodityId 稳定破平；只作还款介质）
 *          ③ 土地（标量 LandHolding；按 LandHoldingKey 自然序；landPrice empty/0 都不转，0 不冲本金）
 * 土地   = 只走 {@link LandLedgerState#transfer(LandTransfer)}（内部唯一校验写口 = LandBook）；
 *          同 hex 任意数量、不跨 hex；不写 HouseholdGoods、不生成市场订单；
 *          取得方按 {@code (hex, creditor)} 数量累加进 {@code ctx.landAcquiredQuantityThisRound}；
 *          ★ D-032 C1：删除“PROLETARIAN/无产债权人跳过 LAND 腿”的旧例外；任何债权人都可按通用顺序受让 LAND，
 *          取得后由资产派生就地变为 OWNER（不在此处持久 class、不触发市场买入目标户吸收）。
 * 边界   = 债权人不在活家户表/人口 ≤ 0 ⇒ 全额 FORGIVEN、本金归零、土地留债务人。
 *          资产不足 ⇒ 余债继续；不强制卖地、不伪造买家、不新增 Pledge。
 * 同日工资债 = {@code ctx.sameDayWageDebtIds} 中的合同（本 tick S6 新建的 WAGE_ARREARS）一律跳过，
 *          最早下一市场日同池处理；只按合同 id 排除，旧工资债照常受偿。
 * </pre>
 *
 * <p>★ 与旧“市场之后按任意介质偿还”的差别：大类次序从“预估价值最低优先”改为冻结形状的 <b>货币 → 库存 → 土地</b>，且时点从关账日市场之后移到 S6 工资之后、S7-A
 * 市场之前（S7-M2）。旧入口 {@link DebtRepaymentSettlement#repayDebts} 保留给非 M2 的经营者/退出路径，不在这里复用其排序。
 */
final class DebtFirstSettlement {

  private static final Logger DEBT = EconomyLog.debt();

  /** 土地地价的量纲分母：毫计价货币 / 1 个整亩，数量为千分亩。 */
  private static final long MILLI_PER_LAND_UNIT = 1000L;

  private DebtFirstSettlement() {}

  /**
   * 执行 S7-M2 债务清算（S6 工资之后、S7-A 市场之前）。
   *
   * @param ctx 当日瞬态上下文（工作副本就地更新）
   * @param wealthSnapshot S7-M2 开始时冻结的纯库存财富
   * @param currencyValuation 市场区行情（钱/土地跨币折价的唯一来源）
   */
  static void settleAtMarketOpen(
      SettlementDayContext ctx,
      PureInventoryWealthBook.Snapshot wealthSnapshot,
      CurrencyValuation currencyValuation) {
    Objects.requireNonNull(ctx, "DebtFirstSettlement.settleAtMarketOpen 的 ctx 不得为 null");
    Objects.requireNonNull(
        wealthSnapshot, "DebtFirstSettlement.settleAtMarketOpen 的 wealthSnapshot 不得为 null");
    Objects.requireNonNull(
        currencyValuation, "DebtFirstSettlement.settleAtMarketOpen 的 currencyValuation 不得为 null");

    Set<DebtContractId> sameDayWageDebtIds =
        ctx.sameDayWageDebtIds == null ? Set.of() : ctx.sameDayWageDebtIds;
    Map<HouseholdId, MarketTable> marketsByHousehold =
        DebtSettlement.marketIndexByHousehold(ctx.householdEconomies, ctx.markets, ctx.topology);
    ctx.marketByHousehold = marketsByHousehold;
    ctx.debtUnitValueLookup = DebtCapacityBook.marketPriceLookup(marketsByHousehold);
    HouseholdValuationBook householdPrices =
        HouseholdValuationBook.derive(
            ctx.householdEconomies,
            DebtRepaymentSettlement.repaymentSnapshots(
                ctx.day,
                ctx.householdEconomies,
                ctx.householdGoods,
                ctx.householdFrozenGoods,
                ctx.householdMoney,
                ctx.householdFrozenMoney,
                marketsByHousehold),
            HouseholdValuationBook.NO_TRADE_HISTORY,
            currencyValuation);
    // ★ D-032 RA1b2c1：土地腿的工作态来自会话 landHoldings 工作副本；唯一转移/校验口仍是 LandBook。
    LandLedgerState landState =
        LandLedgerState.from(
            ctx.session.sheet().landArableSupply(), ctx.session.sheet().landHoldingsOrBase());

    Map<HouseholdId, List<DebtContractId>> byDebtor = DebtIndex.byDebtor(ctx.debts);
    List<HouseholdId> debtors = new ArrayList<>(byDebtor.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value));

    long selfHeldExcluded = 0L;
    long sameDayWageDebtExcluded = 0L;
    long contractsConsidered = 0L;
    long debtorsWithOutstanding = 0L;
    long paidContracts = 0L;
    long forgivenContracts = 0L;
    long landTransfers = 0L;
    long landTransferredMilliMu = 0L;

    for (HouseholdId debtor : debtors) {
      HouseholdEconomy debtorRow = ctx.householdEconomies.get(debtor);
      if (debtorRow == null) {
        continue; // 债务人不在活家户表：没有账户工作副本可动 ⇒ 余债保持（不凭空销账）
      }
      List<DebtContract> owed = new ArrayList<>();
      for (DebtContractId debtId : byDebtor.getOrDefault(debtor, List.of())) {
        DebtContract debt = ctx.debts.get(debtId);
        if (debt == null || debt.principal() <= 0L) {
          continue;
        }
        if (DebtContractBook.isSelfHeld(debt)) {
          selfHeldExcluded++;
          continue;
        }
        if (sameDayWageDebtIds.contains(debt.id())) {
          // ★★★ RC0a：本 tick S6 新建的工资债不进入本 tick S7-M2，最早下一市场日同池处理。
          //   只按合同 id 排除（集合由 SettlementDayWagePipeline 收集），旧工资债照常受偿。
          sameDayWageDebtExcluded++;
          if (DEBT.isDebugEnabled()) {
            EventLog.channel(DEBT)
                .debug(
                    LogEvent.of(
                        "DEBT_FIRST_M2_SAME_DAY_WAGE_DEBT_EXCLUDED",
                        EconomyLogSource.ECONOMY_DEBT,
                        "day",
                        ctx.day,
                        "debtor",
                        debtor.value(),
                        "creditor",
                        debt.creditor().value(),
                        "debtContract",
                        debt.id().value(),
                        "principal",
                        debt.principal(),
                        "reason",
                        "same-day-wage-debt-cutoff"));
          }
          continue;
        }
        owed.add(debt);
      }
      if (owed.isEmpty()) {
        continue;
      }
      debtorsWithOutstanding++;
      owed.sort(creditorOrder(wealthSnapshot));
      for (DebtContract debt : owed) {
        DebtContract live = ctx.debts.get(debt.id());
        if (live == null || live.principal() <= 0L) {
          continue;
        }
        contractsConsidered++;
        HouseholdEconomy creditorRow = ctx.householdEconomies.get(live.creditor());
        if (creditorRow == null || creditorRow.population() <= 0L) {
          DebtContractBook.forgiveAll(
              ctx.debts, live.id(), "P2e-M2 creditor not in live household table");
          forgivenContracts++;
          EventLog.channel(DEBT)
              .info(
                  LogEvent.of(
                      "DEBT_FIRST_M2_CREDITOR_EXTINCT",
                      EconomyLogSource.ECONOMY_DEBT,
                      "day",
                      ctx.day,
                      "debtContract",
                      live.id().value(),
                      "debtor",
                      debtor.value(),
                      "creditor",
                      live.creditor().value(),
                      "principal",
                      live.principal(),
                      "status",
                      "FORGIVEN",
                      "landStaysWithDebtor",
                      true));
          continue;
        }
        PaymentOutcome outcome =
            payOneContract(
                ctx,
                debtor,
                debtorRow,
                live,
                marketsByHousehold.get(debtor),
                wealthSnapshot.regionOf(debtor),
                currencyValuation,
                householdPrices,
                landState);
        landState = outcome.landState();
        landTransfers += outcome.landTransfers();
        landTransferredMilliMu += outcome.landTransferredMilliMu();
        if (outcome.paidContractUnits() > 0L) {
          paidContracts++;
        }
      }
    }

    if (landTransfers > 0L) {
      writeBackLandHoldings(ctx, landState);
    }
    EventLog.channel(DEBT)
        .info(
            LogEvent.of(
                "DEBT_FIRST_M2_ROUND",
                EconomyLogSource.ECONOMY_DEBT,
                "day",
                ctx.day,
                "debtorsWithOutstanding",
                debtorsWithOutstanding,
                "contractsConsidered",
                contractsConsidered,
                "paidContracts",
                paidContracts,
                "forgivenContracts",
                forgivenContracts,
                "selfHeldExcluded",
                selfHeldExcluded,
                "excludedSameDayWageDebts",
                sameDayWageDebtExcluded,
                "sameDayWageDebtCutoff",
                "id-only;earliest-next-market-day",
                "landTransfers",
                landTransfers,
                "landTransferredQuantityMilliMu",
                landTransferredMilliMu,
                "landAcquiredQuantityThisRound",
                ctx.landAcquiredQuantityThisRound().size(),
                "repaidGrainMilli",
                traceTotalLongs(ctx.repaidToday),
                "repaidMoneyByCurrency",
                traceMoneyByCurrency(ctx.repaidMoneyToday),
                "why",
                "P2e-M2: market-open debt-first, pure-inventory-wealth creditor order"));
    if (TRACE.isDebugEnabled()) {
      EventLog.channel(DEBT)
          .debug(
              LogEvent.of(
                  "DEBT_FIRST_M2_CRITERIA",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  ctx.day,
                  "debtorOrder",
                  "HouseholdId.value ascending",
                  "creditorOrder",
                  "pure-inventory-wealth desc → creditor id asc → DebtContractId asc",
                  "mediumOrder",
                  "money → commodities → land",
                  "moneyForeignCurrency",
                  "CurrencyValuation in debtor market zone; missing rate skipped (never 1:1)",
                  "landOrder",
                  "LandHoldingKey asc (hex then owner)",
                  "landWholeBlock",
                  false,
                  "landZeroPrice",
                  "debt settlement never auto-transfers 0-price land to reduce principal",
                  "wealthSnapshot",
                  wealthSnapshot.wealthByHousehold()));
    }
  }

  private static PaymentOutcome payOneContract(
      SettlementDayContext ctx,
      HouseholdId debtor,
      HouseholdEconomy debtorRow,
      DebtContract debt,
      MarketTable market,
      String regionId,
      CurrencyValuation currencyValuation,
      HouseholdValuationBook householdPrices,
      LandLedgerState landState) {
    long original = debt.principal();
    long remaining = original;
    LinkedHashMap<CurrencyId, Long> moneyLegs = new LinkedHashMap<>();
    LinkedHashMap<CommodityId, Long> goodsLegs = new LinkedHashMap<>();
    long moneyContractUnits = 0L;
    long goodsContractUnits = 0L;

    // ① 货币：债务币种优先；其它币种按本区 CurrencyValuation 折算；缺价具名跳过（不得 1:1）。
    Map<CurrencyId, Long> spendableMoney =
        spendableMoney(debtor, ctx.householdMoney, ctx.householdFrozenMoney);
    for (CurrencyId currency : moneyOrder(debt.unit().currency(), spendableMoney)) {
      if (remaining <= 0L) {
        break;
      }
      long available = spendableMoney.getOrDefault(currency, 0L);
      if (available <= 0L) {
        continue;
      }
      MoneyPayment payment =
          chooseMoneyPayment(
              debt.unit().currency(),
              currency,
              available,
              remaining,
              market,
              regionId,
              currencyValuation);
      if (payment == null) {
        EventLog.channel(DEBT)
            .debug(
                LogEvent.of(
                    "DEBT_FIRST_M2_MONEY_UNPRICED",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    ctx.day,
                    "debtor",
                    debtor.value(),
                    "debtContract",
                    debt.id().value(),
                    "currency",
                    currency.value(),
                    "debtCurrency",
                    debt.unit().currency().value(),
                    "why",
                    "missing-currency-valuation-not-1-to-1"));
        continue;
      }
      moneyLegs.merge(currency, payment.amountMilli(), Long::sum);
      moneyContractUnits += payment.contractUnits();
      remaining -= payment.contractUnits();
    }

    // ② 商品库存：只作还款介质；低估值优先 + CommodityId 稳定破平。
    if (remaining > 0L) {
      Map<CommodityId, Long> spendableGoods =
          spendableGoods(debtor, ctx.householdGoods, ctx.householdFrozenGoods);
      DebtValuation.PaymentPlan goodsPlan =
          chooseCommodityPayment(remaining, debt, debtor, spendableGoods, market, householdPrices);
      if (!goodsPlan.isEmpty()) {
        for (Map.Entry<CommodityId, Long> leg : goodsPlan.goodsLegs().entrySet()) {
          goodsLegs.merge(leg.getKey(), leg.getValue(), Long::sum);
        }
        goodsContractUnits = goodsPlan.totalContractUnits();
        remaining -= goodsContractUnits;
      }
      if (!goodsPlan.unpricedAssets().isEmpty()) {
        EventLog.channel(DEBT)
            .debug(
                LogEvent.of(
                    "DEBT_FIRST_M2_GOODS_UNPRICED",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    ctx.day,
                    "debtor",
                    debtor.value(),
                    "debtContract",
                    debt.id().value(),
                    "assets",
                    goodsPlan.unpricedAssets(),
                    "remainingDebtKept",
                    remaining));
      }
    }

    long paidBeforeLand = moneyContractUnits + goodsContractUnits;
    if (paidBeforeLand > 0L) {
      ActorRef payee = HouseholdActors.of(debt.creditor());
      Transfer repayment =
          ctx.ledger.mint(
              HouseholdActors.of(debtor),
              payee,
              debtorRow.view().hex(),
              goodsLegs,
              moneyLegs,
              TransferReason.LOAN_REPAYMENT);
      applyTransfer(
          ctx.householdGoods,
          ctx.householdMoney,
          ctx.householdFrozenGoods,
          ctx.householdFrozenMoney,
          ctx.householdOfActor,
          repayment,
          ctx.issuanceJournal);
      DebtContractBook.reduce(ctx.debts, debt.id(), paidBeforeLand);
      ctx.repaidPrincipalByDebt.merge(debt.id(), paidBeforeLand, Long::sum);
      recordRepaymentReadouts(ctx, debtor, goodsLegs, moneyLegs);
    }

    // ③ 土地：任何债权人都可受让；标量 LandHolding、任意数量；按 LandHoldingKey 自然序。empty/0 价都跳过，不自动转地。
    long landContractUnits = 0L;
    int landTransfers = 0;
    long landTransferredMilliMu = 0L;
    if (remaining > 0L) {
      List<LandHolding> landHoldings = landHoldingsOf(debtor, landState);
      for (LandHolding holding : landHoldings) {
        if (remaining <= 0L) {
          break;
        }
        MarketTable landMarket =
            DebtSettlement.marketForHex(holding.hex(), ctx.markets, ctx.topology);
        if (landMarket == null || !landMarket.hasLandPrice()) {
          logLandSkipped(ctx, debt, holding, "land-market-unpriced", remaining);
          continue;
        }
        OptionalLong landPrice = landMarket.landPrice();
        if (landPrice.isEmpty()) {
          logLandSkipped(ctx, debt, holding, "land-price-empty", remaining);
          continue;
        }
        long marketLandPrice = landPrice.getAsLong();
        if (marketLandPrice == 0L) {
          // ★ 0 价土地不自动用于债务清偿；市场 0 价成交仍合法（RA1b2c2）。
          logLandSkipped(ctx, debt, holding, "land-price-zero-not-auto-collateral", remaining);
          continue;
        }
        long contractPricePerMu =
            landValueInDebtCurrency(
                marketLandPrice,
                landMarket.numeraire(),
                debt.unit().currency(),
                holding.hex(),
                ctx,
                currencyValuation);
        if (contractPricePerMu < 0L) {
          logLandSkipped(ctx, debt, holding, "land-currency-unpriced", remaining);
          continue;
        }
        if (contractPricePerMu == 0L) {
          logLandSkipped(ctx, debt, holding, "land-contract-price-rounds-to-zero", remaining);
          continue;
        }
        long fullValue =
            DebtValuation.convertedContractUnits(
                holding.quantityMilliMu(), contractPricePerMu, MILLI_PER_LAND_UNIT);
        final long transferQuantity;
        final long actualValue;
        if (fullValue > 0L && fullValue <= remaining) {
          transferQuantity = holding.quantityMilliMu();
          actualValue = fullValue;
        } else if (fullValue > remaining) {
          long neededQuantity =
              DebtValuation.convertedContractUnits(
                  remaining, MILLI_PER_LAND_UNIT, contractPricePerMu);
          if (neededQuantity <= 0L) {
            logLandSkipped(ctx, debt, holding, "needed-quantity-rounds-to-zero", remaining);
            continue;
          }
          transferQuantity = Math.min(holding.quantityMilliMu(), neededQuantity);
          actualValue =
              DebtValuation.convertedContractUnits(
                  transferQuantity, contractPricePerMu, MILLI_PER_LAND_UNIT);
          if (transferQuantity <= 0L || actualValue <= 0L) {
            logLandSkipped(ctx, debt, holding, "land-value-rounds-to-zero", remaining);
            continue;
          }
        } else {
          // fullValue <= 0：正价但数量过小取整为 0 ⇒ 跳过，不得转 0 对价数量。
          logLandSkipped(ctx, debt, holding, "land-value-rounds-to-zero", remaining);
          continue;
        }
        LandTransfer transfer =
            new LandTransfer(holding.hex(), debtor, debt.creditor(), transferQuantity);
        landState = landState.transfer(transfer);
        landContractUnits += actualValue;
        remaining -= actualValue;
        landTransfers++;
        landTransferredMilliMu += transferQuantity;
        ctx.markLandAcquired(new LandHoldingKey(holding.hex(), debt.creditor()), transferQuantity);
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "DEBT_FIRST_M2_LAND_TRANSFER",
                    EconomyLogSource.ECONOMY_DEBT_STATE,
                    "day",
                    ctx.day,
                    "debtor",
                    debtor.value(),
                    "creditor",
                    debt.creditor().value(),
                    "hex",
                    holding.hex().toString(),
                    "quantityMilliMu",
                    transferQuantity,
                    "price",
                    contractPricePerMu,
                    "contractValue",
                    actualValue,
                    "remainingAfter",
                    remaining));
      }
    }
    if (landContractUnits > 0L) {
      DebtContractBook.reduce(ctx.debts, debt.id(), landContractUnits);
      ctx.repaidPrincipalByDebt.merge(debt.id(), landContractUnits, Long::sum);
    }

    long paid = paidBeforeLand + landContractUnits;
    if (TRACE.isTraceEnabled()) {
      EventLog.channel(TRACE)
          .trace(
              LogEvent.of(
                  "DEBT_FIRST_M2_CONTRACT",
                  EconomyLogSource.ECONOMY_DEBT_STATE,
                  "day",
                  ctx.day,
                  "debtor",
                  debtor.value(),
                  "creditor",
                  debt.creditor().value(),
                  "debtContract",
                  debt.id().value(),
                  "outstandingBefore",
                  original,
                  "paidMoneyUnits",
                  moneyContractUnits,
                  "paidGoodsUnits",
                  goodsContractUnits,
                  "paidLandUnits",
                  landContractUnits,
                  "remaining",
                  ctx.debts.get(debt.id()) == null
                      ? original - paid
                      : ctx.debts.get(debt.id()).principal(),
                  "moneyLegs",
                  moneyLegs,
                  "goodsLegs",
                  goodsLegs,
                  "landTransfers",
                  landTransfers,
                  "landTransferredQuantityMilliMu",
                  landTransferredMilliMu));
    }
    return new PaymentOutcome(paid, landTransfers, landTransferredMilliMu, landState);
  }

  /** 货币付款选择：本币 identity 1:1；异币必须经本区 CurrencyValuation，缺价返回 null（调用方具名 skip）。 */
  private static MoneyPayment chooseMoneyPayment(
      CurrencyId debtCurrency,
      CurrencyId currency,
      long available,
      long remaining,
      MarketTable market,
      String regionId,
      CurrencyValuation currencyValuation) {
    if (currency.equals(debtCurrency)) {
      long amount = Math.min(remaining, available);
      return amount <= 0L ? null : new MoneyPayment(amount, amount);
    }
    if (market == null) {
      return null;
    }
    CurrencyId numeraire = market.numeraire();
    long otherMicro = currencyValuation.valuationMicro(numeraire, currency, regionId);
    long debtMicro = currencyValuation.valuationMicro(numeraire, debtCurrency, regionId);
    if (otherMicro <= 0L || debtMicro <= 0L) {
      return null;
    }
    long used = DebtValuation.maxRepaymentQuantity(remaining, otherMicro, debtMicro, available);
    if (used <= 0L) {
      return null;
    }
    long contractUnits = DebtValuation.convertedContractUnits(used, otherMicro, debtMicro);
    if (contractUnits <= 0L) {
      return null;
    }
    return new MoneyPayment(used, Math.min(contractUnits, remaining));
  }

  /** 商品介质：低估值 → 稳定 id；与 {@link DebtRepaymentSettlement#repaymentMediumOrder} 同一把估值尺。 */
  private static DebtValuation.PaymentPlan chooseCommodityPayment(
      long remaining,
      DebtContract debt,
      HouseholdId debtor,
      Map<CommodityId, Long> spendableGoods,
      MarketTable market,
      HouseholdValuationBook householdPrices) {
    List<DebtValuation.RepaymentMedium> order = new ArrayList<>();
    LinkedHashMap<DebtValuation.RepaymentMedium, Long> available = new LinkedHashMap<>();
    List<CommodityId> commodities = new ArrayList<>(spendableGoods.keySet());
    commodities.sort(Comparator.comparing(CommodityId::value));
    for (CommodityId commodity : commodities) {
      long quantity = spendableGoods.getOrDefault(commodity, 0L);
      if (quantity <= 0L) {
        continue;
      }
      DebtValuation.RepaymentMedium medium = new DebtValuation.CommodityMedium(commodity);
      order.add(medium);
      available.put(medium, quantity);
    }
    order.sort(
        Comparator.comparingLong(
                (DebtValuation.RepaymentMedium medium) ->
                    DebtValuation.estimatedUnitValueMilli(debtor, medium, market, householdPrices))
            .thenComparing(DebtValuation.RepaymentMedium::key));
    if (order.isEmpty()) {
      return new DebtValuation.PaymentPlan(List.of(), 0L, List.of());
    }
    return DebtValuation.choosePayment(
        remaining, debt.unit(), debtor, order, available, market, householdPrices);
  }

  /** 土地价值从“土地所属市场区 numeraire”折到“债务币种”：同币原值；异币缺行情 ⇒ -1（不转地、余债保留）。 价值单位 = 合同计价单位（毫）。 */
  private static long landValueInDebtCurrency(
      long nativeValueMilli,
      CurrencyId landNumeraire,
      CurrencyId debtCurrency,
      HexCoord landHex,
      SettlementDayContext ctx,
      CurrencyValuation currencyValuation) {
    if (landNumeraire.equals(debtCurrency)) {
      return nativeValueMilli;
    }
    String landRegionId = null;
    if (ctx.topology != null && ctx.topology.contains(landHex)) {
      landRegionId = ctx.topology.regionOf(landHex).node().nodeId();
    }
    long debtMicro = currencyValuation.valuationMicro(landNumeraire, debtCurrency, landRegionId);
    if (debtMicro <= 0L) {
      return -1L;
    }
    return DebtValuation.convertedContractUnits(
        nativeValueMilli, CurrencyValuation.faceValueMicro(), debtMicro);
  }

  private static void logLandSkipped(
      SettlementDayContext ctx,
      DebtContract debt,
      LandHolding holding,
      String why,
      long remaining) {
    EventLog.channel(DEBT)
        .debug(
            LogEvent.of(
                "DEBT_FIRST_M2_LAND_SKIPPED",
                EconomyLogSource.ECONOMY_DEBT,
                "day",
                ctx.day,
                "debtor",
                debt.debtor().value(),
                "debtContract",
                debt.id().value(),
                "hex",
                holding.hex().toString(),
                "quantityMilliMu",
                holding.quantityMilliMu(),
                "why",
                why,
                "remainingDebtKept",
                remaining));
  }

  /** 本户当前（工作副本）的 LAND 持有，按 {@link LandHoldingKey} 自然序；只含正数量行。 */
  private static List<LandHolding> landHoldingsOf(HouseholdId debtor, LandLedgerState landState) {
    List<LandHolding> holdings = new ArrayList<>();
    for (LandHolding holding : landState.holdingsByKey().values()) {
      if (holding.owner().equals(debtor) && holding.quantityMilliMu() > 0L) {
        holdings.add(holding);
      }
    }
    holdings.sort(Comparator.comparing(LandHolding::key));
    return holdings;
  }

  /** 把 M2 标量土地转移结果写回会话 landHoldings 工作副本（同一次 build 才看得见）。 */
  private static void writeBackLandHoldings(SettlementDayContext ctx, LandLedgerState landState) {
    LinkedHashMap<LandHoldingKey, LandHolding> working = ctx.session.sheet().landHoldings();
    working.clear();
    working.putAll(landState.holdingsByKey());
  }

  /** 货币顺序：债务币种优先，其余按 CurrencyId.value 升序（稳定、内容纯函数）。 */
  private static List<CurrencyId> moneyOrder(
      CurrencyId debtCurrency, Map<CurrencyId, Long> spendableMoney) {
    LinkedHashMap<CurrencyId, Boolean> ordered = new LinkedHashMap<>();
    ordered.put(debtCurrency, Boolean.TRUE);
    List<CurrencyId> others = new ArrayList<>(spendableMoney.keySet());
    others.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : others) {
      ordered.putIfAbsent(currency, Boolean.TRUE);
    }
    return new ArrayList<>(ordered.keySet());
  }

  /** 同一债务人的债权人排序：纯库存财富降序 → 债权人 id 升序 → DebtContractId 升序。 */
  private static Comparator<DebtContract> creditorOrder(
      PureInventoryWealthBook.Snapshot wealthSnapshot) {
    return Comparator.comparingLong((DebtContract debt) -> wealthSnapshot.wealthOf(debt.creditor()))
        .reversed()
        .thenComparing(debt -> debt.creditor().value())
        .thenComparing(debt -> debt.id().value());
  }

  private static void recordRepaymentReadouts(
      SettlementDayContext ctx,
      HouseholdId debtor,
      Map<CommodityId, Long> goodsLegs,
      Map<CurrencyId, Long> moneyLegs) {
    long grain = goodsLegs.getOrDefault(EconomySettlement.GRAIN, 0L);
    if (grain > 0L) {
      ctx.repaidToday.merge(debtor, grain, Long::sum);
    }
    for (Map.Entry<CurrencyId, Long> leg : moneyLegs.entrySet()) {
      if (leg.getValue() > 0L) {
        ctx.repaidMoneyToday
            .computeIfAbsent(debtor, ignored -> new LinkedHashMap<>())
            .merge(leg.getKey(), leg.getValue(), Long::sum);
      }
    }
  }

  private record MoneyPayment(long amountMilli, long contractUnits) {
    private MoneyPayment {
      if (amountMilli <= 0L || contractUnits <= 0L) {
        throw new IllegalArgumentException(
            "MoneyPayment 必须 > 0: amount=" + amountMilli + " units=" + contractUnits);
      }
    }
  }

  private record PaymentOutcome(
      long paidContractUnits,
      int landTransfers,
      long landTransferredMilliMu,
      LandLedgerState landState) {}
}
