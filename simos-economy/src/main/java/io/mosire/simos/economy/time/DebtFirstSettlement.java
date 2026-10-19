package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceMoneyByCurrency;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalLongs;
import static io.mosire.simos.economy.time.SettlementMath.spendableGoods;
import static io.mosire.simos.economy.time.SettlementMath.spendableMoney;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
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
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>P2e：市场日债务最先清算（M2）的唯一执行器</b>。
 *
 * <pre>
 * 触发   = 只在市场轮（day % 5 == 0 且市场非空）；由 {@link EconomyDayPipeline} 在消费/生产/市场之前调用。
 * 范围   = principal > 0 且非 {@link DebtContractBook#isSelfHeld} 的合同。
 * 债务人 = HouseholdId.value 升序。
 * 债权人 = 同一债务人：纯库存财富降序 → HouseholdId.value 升序 → DebtContractId.value 升序；
 *          财富快照在 M2 开始时冻结（{@link PureInventoryWealthBook#snapshot}）。
 * 介质   = ① 货币（债务币种优先；其它币种经本区 CurrencyValuation，缺价具名跳过，不 1:1）
 *          ② 商品库存（低估值优先 + CommodityId 稳定破平；只作还款介质）
 *          ③ 土地（整块；价值升序 → AssetShareId；landPrice empty 不转，0 转地不减债）
 * 土地   = 只走 {@link OwnershipStakeBook#transferLandBlock}；id/location/quantity/industry/kind 不变；
 *          不写 HouseholdGoods、不生成市场订单；取得方记入 {@code ctx.landAcquiredThisRound}。
 * 边界   = 债权人不在活家户表/人口 ≤ 0 ⇒ 全额 FORGIVEN、本金归零、土地留债务人。
 *          资产不足 ⇒ 余债继续；不强制卖地、不伪造买家、不新增 Pledge。
 * </pre>
 *
 * <p>★ 与旧“市场之后按任意介质偿还”的差别：大类次序从“预估价值最低优先”改为冻结形状的 <b>货币 → 库存 → 土地</b>，且时点从关账日市场之后提前到市场日最前面。旧入口
 * {@link DebtRepaymentSettlement#repayDebts} 保留给非 M2 的经营者/退出路径，不在这里复用其排序。
 */
final class DebtFirstSettlement {

  private static final Logger DEBT = EconomyLog.debt();

  /** 土地地价的量纲分母：毫计价货币 / 1 个整亩，数量为千分亩。 */
  private static final long MILLI_PER_LAND_UNIT = 1000L;

  private DebtFirstSettlement() {}

  /**
   * 执行 M2 债务最先清算。
   *
   * @param ctx 当日瞬态上下文（工作副本就地更新）
   * @param wealthSnapshot M2 开始时冻结的纯库存财富
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
    Map<HouseholdId, List<OwnershipStake>> landByHousehold = landByHousehold(ctx);

    Map<HouseholdId, List<DebtContractId>> byDebtor = DebtIndex.byDebtor(ctx.debts);
    List<HouseholdId> debtors = new ArrayList<>(byDebtor.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value));

    long selfHeldExcluded = 0L;
    long contractsConsidered = 0L;
    long debtorsWithOutstanding = 0L;
    long paidContracts = 0L;
    long forgivenContracts = 0L;
    long landBlocksTransferred = 0L;
    long detachedEnterpriseRefs = 0L;
    long detachedOccupancyGrants = 0L;
    long detachedRosterRights = 0L;

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
                landByHousehold.getOrDefault(debtor, List.of()));
        landBlocksTransferred += outcome.landBlocksTransferred();
        detachedEnterpriseRefs += outcome.detachedEnterpriseRefs();
        detachedOccupancyGrants += outcome.detachedOccupancyGrants();
        detachedRosterRights += outcome.detachedRosterRights();
        if (outcome.paidContractUnits() > 0L) {
          paidContracts++;
        }
      }
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
                "landBlocksTransferred",
                landBlocksTransferred,
                "landAcquiredThisRound",
                ctx.landAcquiredThisRound().size(),
                "repaidGrainMilli",
                traceTotalLongs(ctx.repaidToday),
                "repaidMoneyByCurrency",
                traceMoneyByCurrency(ctx.repaidMoneyToday),
                "why",
                "P2e-M2: market-open debt-first, pure-inventory-wealth creditor order"));
    if (landBlocksTransferred > 0L) {
      // ★★★ D-032 P2fix：土地整块抵债会改份额 operator（→ 债权人），债务人侧三类旧引用（组织 assetSources /
      //   占用 grant / 名册 rightsStakes）都不能再指名这些份额；本事件把“转了多少块 / 摘掉多少条”留成 INFO 证据。
      EventLog.channel(DEBT)
          .info(
              LogEvent.of(
                  "DEBT_FIRST_M2_LAND_ORG_DETACH",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  ctx.day,
                  "transferredLandBlocks",
                  landBlocksTransferred,
                  "detachedEnterpriseRefs",
                  detachedEnterpriseRefs,
                  "detachedOccupancyGrants",
                  detachedOccupancyGrants,
                  "detachedRosterRights",
                  detachedRosterRights,
                  "reason",
                  "transferred-land-share-operator-no-longer-debtor-organizer"));
    }
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
                  "landBlockOrder",
                  "contract value asc → AssetShareId asc",
                  "landWholeBlock",
                  true,
                  "landNoChange",
                  "no change for land value over remaining debt",
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
      List<OwnershipStake> landBlocks) {
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

    // ③ 土地：整块、不找零、不拆分；价值升序 → AssetShareId 升序。
    long landContractUnits = 0L;
    int landBlocksTransferred = 0;
    Set<AssetShareId> transferredLandShareIds = new LinkedHashSet<>();
    if (remaining > 0L && !landBlocks.isEmpty()) {
      List<LandBlock> priced = pricedLandBlocks(landBlocks, debt, ctx, currencyValuation);
      for (LandBlock block : priced) {
        if (remaining <= 0L) {
          break;
        }
        if (block.contractValue() > remaining) {
          landContractUnits += remaining;
          remaining = 0L;
        } else if (block.contractValue() > 0L) {
          landContractUnits += block.contractValue();
          remaining -= block.contractValue();
        }
        ActorRef creditorActor = HouseholdActors.of(debt.creditor());
        OwnershipStakeBook.transferLandBlock(
            ctx.assetShares, block.share().id(), creditorActor, creditorActor);
        ctx.markLandAcquired(block.share().id());
        transferredLandShareIds.add(block.share().id());
        landBlocksTransferred++;
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
                    "land",
                    block.share().id().value(),
                    "quantityMilliMu",
                    block.share().quantity(),
                    "landPrice",
                    block.landPrice(),
                    "contractValue",
                    block.contractValue(),
                    "remainingDebtAfter",
                    remaining));
      }
    }
    // ★★★ D-032 P2fix：抵地成功后份额 operator 已改成债权人；债务人侧所有还指名这些 share 的旧引用都必须摘除，
    //   否则 revision 边界的 EconomyData / production-reference 校验会 fail-closed：
    //   ① 生产组织 assetSources；② 资产占用 grant（scope 校验先于 STATUS，不能只置 RELEASED）；③ 名册 rightsStakes。
    //   不改份额、不改组织身份/unit/laborSources/inputSources/status，也不把新 share 塞给债权人组织。
    int detachedEnterpriseRefs = 0;
    int detachedOccupancyGrants = 0;
    int detachedRosterRights = 0;
    if (!transferredLandShareIds.isEmpty()) {
      detachedEnterpriseRefs =
          ProductionOrganizationSettlement.detachReturnedSharesFromEnterprises(
              ctx.session.sheet().productionOrganizations(),
              HouseholdActors.of(debtor),
              transferredLandShareIds);
      detachedOccupancyGrants =
          AssetOccupancyBook.detachGrantsForShares(
              ctx.session.sheet().assetOccupancies(), transferredLandShareIds);
      detachedRosterRights =
          ProductionOrganizationSettlement.detachRightsStakesFromRosters(
              ctx.session.sheet().productionRosters(), debtor, transferredLandShareIds);
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
                  "landBlocks",
                  landBlocksTransferred,
                  "detachedEnterpriseRefs",
                  detachedEnterpriseRefs,
                  "detachedOccupancyGrants",
                  detachedOccupancyGrants,
                  "detachedRosterRights",
                  detachedRosterRights));
    }
    return new PaymentOutcome(
        paid,
        landBlocksTransferred,
        detachedEnterpriseRefs,
        detachedOccupancyGrants,
        detachedRosterRights);
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

  /** 本户 LAND 块按「合同价值升序 → AssetShareId 升序」排列；未定价/缺价块不入列（余债保留）。 */
  private static List<LandBlock> pricedLandBlocks(
      List<OwnershipStake> landBlocks,
      DebtContract debt,
      SettlementDayContext ctx,
      CurrencyValuation currencyValuation) {
    List<LandBlock> priced = new ArrayList<>();
    List<OwnershipStake> ordered = new ArrayList<>(landBlocks);
    ordered.sort(Comparator.comparing(share -> share.id().value()));
    ActorRef debtorActor = HouseholdActors.of(debt.debtor());
    for (OwnershipStake snapshotShare : ordered) {
      // 同一债务人的多笔债会依次走土地腿：每笔都必须读**当刻**产权，已抵给前一个债权人的块不得二次转移。
      OwnershipStake share = ctx.assetShares.get(snapshotShare.id());
      if (share == null
          || share.asset() != AssetKind.LAND
          || share.quantity() <= 0L
          || !debtorActor.equals(share.owner())) {
        continue;
      }
      HexCoord hex = HexCoord.parse(share.location().value());
      MarketTable landMarket = DebtSettlement.marketForHex(hex, ctx.markets, ctx.topology);
      if (landMarket == null || !landMarket.hasLandPrice()) {
        logLandUnpriced(ctx, debt, share, "land-market-unpriced");
        continue;
      }
      OptionalLong landPrice = landMarket.landPrice();
      if (landPrice.isEmpty()) {
        logLandUnpriced(ctx, debt, share, "land-price-empty");
        continue;
      }
      long nativeValue =
          DebtValuation.convertedContractUnits(
              share.quantity(), landPrice.getAsLong(), MILLI_PER_LAND_UNIT);
      long contractValue =
          landValueInDebtCurrency(
              nativeValue,
              landMarket.numeraire(),
              debt.unit().currency(),
              hex,
              ctx,
              currencyValuation);
      if (contractValue < 0L) {
        logLandUnpriced(ctx, debt, share, "land-currency-unpriced");
        continue;
      }
      priced.add(new LandBlock(share, contractValue, landPrice.getAsLong()));
    }
    priced.sort(
        Comparator.comparingLong(LandBlock::contractValue)
            .thenComparing(block -> block.share().id().value()));
    return priced;
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

  private static void logLandUnpriced(
      SettlementDayContext ctx, DebtContract debt, OwnershipStake share, String why) {
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
                "land",
                share.id().value(),
                "why",
                why,
                "remainingDebtKept",
                true));
  }

  /** 本户拥有的 LAND 块（按 AssetShareId.value 升序；每一块整块参与）。 */
  private static Map<HouseholdId, List<OwnershipStake>> landByHousehold(SettlementDayContext ctx) {
    LinkedHashMap<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();
    List<HouseholdId> orderedHouseholds = new ArrayList<>(ctx.householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : orderedHouseholds) {
      householdByActor.put(HouseholdActors.of(household), household);
    }
    List<OwnershipStake> shares = new ArrayList<>(ctx.assetShares.values());
    shares.sort(Comparator.comparing(share -> share.id().value()));
    LinkedHashMap<HouseholdId, List<OwnershipStake>> byHousehold = new LinkedHashMap<>();
    for (OwnershipStake share : shares) {
      if (share.asset() != AssetKind.LAND || share.quantity() <= 0L) {
        continue;
      }
      HouseholdId owner = householdByActor.get(share.owner());
      if (owner == null) {
        continue;
      }
      byHousehold.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(share);
    }
    return byHousehold;
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

  private record LandBlock(OwnershipStake share, long contractValue, long landPrice) {}

  private record PaymentOutcome(
      long paidContractUnits,
      int landBlocksTransferred,
      int detachedEnterpriseRefs,
      int detachedOccupancyGrants,
      int detachedRosterRights) {}
}
