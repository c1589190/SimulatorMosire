package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AccountTransferSettlement.applyTransfer;
import static io.mosire.simos.economy.time.EconomySettlement.GRAIN;
import static io.mosire.simos.economy.time.SettlementLogSupport.RAW;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.traceTotalLongs;
import static io.mosire.simos.economy.time.SettlementMath.netOf;
import static io.mosire.simos.economy.time.SettlementMath.spendableGoods;
import static io.mosire.simos.economy.time.SettlementMath.spendableMoney;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
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

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class DebtRepaymentSettlement {

  /**
   * ★★ <b>P11.1 / D-023 / D-030：周期末偿还 —— “有啥付啥”，介质不设限</b>。
   *
   * <pre>
   * 债务人序 = HouseholdId.value 升序（稳定）
   * 合同序   = 未偿本金按共同价值升序；不可定价排最后（unit id 升序）
   *            tie = 债权人 id 升序 → 债的单位 id 升序 → DebtContractId.value
   * 条款闸   = 只走 RepaymentRule.AVAILABLE_SURPLUS_SHARE + InterestTiming.AFTER_REPAYMENT_ON_CLOSE
   *            其它组合 fail-closed 具名抛（不静默当默认档）
   * 可动用   = 商品：★★ v3.6（用户第六批裁定）= max(0, 余额 − 冻结) —— **不扣任何保留量**（有库存就还）
   *            × 还款份额千分比
   *            货币：max(0, 逐币种余额 − 冻结)（全部币种；不跨币种求和/折换）
   * 介质序   = ★ W2 起 = 该户**预估价值最低**的单位优先（S-5，用户 2026-10-10 第二批第 5 条）：
   *            {@code DebtValuation.estimatedUnitValueMilli} 升序，同值按单位 canonical 串升序。
   *            （旧口径"全部货币按余额降序 → 全部商品按余额降序、钱永远第一"= D-030 §4，已被取代）
   * 选择     = 逐条债（合同序）× 逐 medium（介质序）：identity 直接 1:1；否则用债务人价目表折成共同价值、
   *            整数 floor、最多还清本金；缺价 medium 具名跳过，换下一条
   * 付款     = 一组实际商品/货币腿铸成**唯一一条** LOAN_REPAYMENT 转移 → applyTransfer（带冻结/发行审计）
   * 本金     = 只减合同唯一的 principal（走 DebtContractBook.reduce；无 interest/principal 双账）
   * 缺价     = 持有资产但没有稳定价格 ⇒ 不折算、不静默付 0；记具名 DebtRepaymentSkip
   * 还不上   = **挂账、计息、不清零**（用户 0–8 第 7 条「没库存就记为实际债务」）—— 本方法不核销
   * </pre>
   *
   * <p>★★ <b>为什么不按合同单位/币种分别核算</b>（D-023 第 3 条）：合同仍只有一条连续本金；利息仍按现有 {@link #chargeInterest}
   * 资本化进本金。介质自由只发生在“这笔本金用什么资产折付”这一层。
   *
   * <p>★★ <b>外部放贷主体 / 政府 / 家户一视同仁</b>（D-023 第 1 条）：收款人由 {@link DebtValuation.RepayeeResolver} 解析（默认
   * = 家户 actor）；只要其账户在本日 {@code AccountSession}/{@code actor} 账里可收，任意商品/货币腿都照收。收不了 ⇒ 具名
   * fail-closed，绝不静默吞款。
   *
   * <p>★★ <b>★★ v3.6（2026-10-11 用户第六批裁定）：还债不设任何保留窗口</b> —— 用户原话：「<b>为啥要设还债窗口，应当是有库存就还，
   * 没库存就不还啊</b>」⇒ 可动额 = 手头库存（含货币，逐 medium 按 S-5 估值升序取用），**不扣"下轮生产花费"、不扣"一日口粮"、不扣任何 前瞻量**（D-030 §4
   * 的"一日口粮保留"与 v3.5 的"35 天窗口"一并作废）。★ 唯一保留的前瞻扣除在<b>卖侧</b>（用户 0–8 第 5 条：投入市场的商品 = 扣除下一 tick
   * 生产需要后的），本方法不碰它。★ 冻结口径不弱化：任何腿都不越过冻结；还清后 {@link DebtContract} 留在表里（本金 0 的历史条不删，见 {@link
   * DebtContractBook#reduce}）。
   *
   * <p>★ <b>读数</b>：{@code repaid} 记本日实际走粮腿的毫粮（含以粮折付其它计价口径）；{@code repaidMoney}
   * 记本日实际走货币腿的逐币种毫钱；其它商品腿从合同 principal 下降 + 转移凭据读出。
   *
   * @param repaid 本日实际粮腿的逐户累加器（就地更新 ⇒ 进 {@code FlowRow.repaid}）；不得为 null
   * @param repaidMoney 本日实际货币腿的逐户逐币种累加器（就地更新 ⇒ 进 {@code FlowRow.repaidMoney}）；不得为 null
   * @param marketsByHousehold 债务人 → 其所在市场区默认价目表（缺键 = 没有市场默认价目表 ⇒ 只有 identity 腿可付）
   * @param repayeeResolver 债权人 → 收款 actor 的解析口（默认 {@link
   *     DebtValuation.RepayeeResolver#HOUSEHOLD_ACTORS}； 外部放贷主体/政府账户可换一份 resolver）
   * @param householdPrices 家户价目表（可选；W2 起由 {@link HouseholdValuationBook} 现算注入 —— 它是 S-5
   *     介质序与债务折价的<b>唯一</b>估值尺）
   * @param day 本日的绝对世界日（★ W2-fix7 / D5：还债的每一条事件都带 {@code day} —— 现场可按天直接检索/对账， 不再依赖"日志时间序的最近一个
   *     day"去推）
   */
  static void repayDebts(
      long day,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      LinkedHashMap<HouseholdId, Long> repaid,
      LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> repaidMoney,
      LinkedHashMap<DebtContractId, Long> repaidPrincipalByDebt,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HouseholdId, MarketTable> marketsByHousehold,
      DebtValuation.RepayeeResolver repayeeResolver,
      DebtValuation.HouseholdPriceTable householdPrices,
      ProductionLedger.Accumulator ledger,
      MoneyIssuanceJournal issuanceJournal,
      Set<DebtContractId> sameDayWageDebtIds) {
    Objects.requireNonNull(sameDayWageDebtIds, "sameDayWageDebtIds（没有同日工资债给空集，不得为 null）");
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = DebtIndex.byDebtor(debts);
    List<HouseholdId> debtors = new ArrayList<>(householdEconomies.keySet());
    debtors.sort(Comparator.comparing(HouseholdId::value)); // 稳定债务人序（不沿用 Map 插入序）
    // ★★ §一.9：还债轮的三个具名计数（INFO 一条用；只读累加，不改任何口径）。
    long debtorsWithOutstanding = 0L;
    long householdsRepaid = 0L;
    long householdsWithNoStockToRepay = 0L;
    // ★★ W5-fix（BLOCKED-2）：本轮被排除的**自持合同**条数（见下面 `debtor.equals(creditor)` 那一段）。
    long selfHeldExcluded = 0L;
    Set<HouseholdId> debtorsWithPayment = new LinkedHashSet<>();
    for (HouseholdId debtor : debtors) {
      HouseholdEconomy householdEconomy = householdEconomies.get(debtor);
      if (householdEconomy == null) {
        continue;
      }
      List<DebtContract> owed = new ArrayList<>();
      for (DebtContractId debtId : debtsByDebtor.getOrDefault(debtor, List.of())) {
        DebtContract debt = debts.get(debtId);
        if (debt == null || debt.principal() <= 0L) {
          continue;
        }
        if (DebtContractBook.isSelfHeld(debt)) {
          // ★★★ W5-fix（BLOCKED-2，2026-10-11）：**自持合同（debtor == creditor）不是可执行的债权** ——
          //   它是"发行额 = 政府负债"这一类**记账事实**（铸币生产方式是其唯一生产者：`MintSettlement.credit` 的
          //   债务人是发行政府的国库家户、债权人是铸币这一路的组织者，常态世界里两者同一户）。
          //   对它清偿 = 铸一条 debtor → creditor 的转移 ⇒ `Transfer` 判死（两端相等不是一条发生额，是坏数据），
          //   而经济上它本来就是"自己的钱还给自己"（什么也没发生）⇒ **还债轮排除**（控制方裁定，设计书 v3.15 §7）。
          //   ★ **不静默**：逐条 DEBUG 具名（谁 / 哪条合同 / 为什么不动它）。
          //   ★ 排除**不改任何状态**：合同原样留在 `debts` 里（本金不减、状态不改）—— 它是记账事实，不是坏数据。
          //   ★ 同族先例（同一口径已存在于别处）：`DebtPartyResolver.splitDebtAmounts` 的自债净额
          //     （`capitalizeArrears` 用它，永不落自持合同）、`ModeMigrationSettlement.moveDebt` 的
          //     `MIGRATION_DEBT_SELF_NETTED`（目标恰是债权人 ⇒ 不落合同）。
          selfHeldExcluded++;
          if (TRACE.isDebugEnabled()) {
            EventLog.channel(TRACE)
                .debug(
                    LogEvent.of(
                        "DEBT_REPAY_SELF_HELD_SKIPPED",
                        EconomyLogSource.ECONOMY_DEBT_STATE,
                        "day",
                        day,
                        "household",
                        debtor.value(),
                        "debtContract",
                        debt.id().value(),
                        "unit",
                        debt.unit().key(),
                        "outstandingPrincipal",
                        debt.principal(),
                        "why",
                        "self-held-not-executable"));
          }
          continue;
        }
        if (sameDayWageDebtIds.contains(debt.id())) {
          // ★★★ D3-I13 / §6.5（E12）：本日 W3 新建的工资债不得在同日还款轮被再付一次。
          EventLog.channel(TRACE)
              .info(
                  LogEvent.of(
                      "WAGE_DEBT_SAME_DAY_REPAY_BLOCKED",
                      EconomyLogSource.ECONOMY_DEBT,
                      "day",
                      day,
                      "worker",
                      DebtContractBook.isSelfHeld(debt) ? "-" : debt.creditor().value(),
                      "creditor",
                      debt.creditor().value(),
                      "debtContract",
                      debt.id().value(),
                      "amount",
                      debt.principal(),
                      "reason",
                      "wage-debt-same-day-repay-blocked"));
          continue;
        }
        owed.add(debt);
      }
      if (owed.isEmpty()) {
        continue;
      }
      debtorsWithOutstanding++;
      MarketTable market = marketsByHousehold.get(debtor);
      owed.sort(repaymentOrder(debtor, market, householdPrices));

      // ★★★ v3.6（2026-10-11，用户第六批原话：「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」）：
      //   **不扣任何保留量** —— 可动商品 = max(0, 余额 − 冻结)（含冻结不越），逐商品同式；
      //   ★ 作废的两条：D-030 §4 的"一日口粮保留"、v3.5 裁定的"35 天窗口"（见约束设计书 v3.6/v3.7 §4.1）。
      //   ★ 还不上 ⇒ 挂账、计息、不清零（0–8 第 7 条）；本方法不核销、不留位。
      Map<CommodityId, Long> spendableGoods =
          spendableGoods(debtor, householdGoods, householdFrozenGoods);
      Map<CurrencyId, Long> spendableMoney =
          spendableMoney(debtor, householdMoney, householdFrozenMoney);
      // ★★ S-5（用户 2026-10-10 第二批第 5 条）：介质序 = **该户预估价值最低的商品优先** ——
      //   {@link DebtValuation#estimatedUnitValueMilli} 升序（估算尺 = 家户价目表 → 市场区默认；
      //   说不出价的排最后，同值按单位 canonical 串升序保证可复现）。★ 旧口径"钱永远第一 → 商品按数量降序"
      //   （D-030 §4）**已被取代**，见 {@link #repaymentMediumOrder}。
      List<DebtValuation.RepaymentMedium> mediumOrder =
          repaymentMediumOrder(debtor, spendableMoney, spendableGoods, market, householdPrices);
      Map<DebtValuation.RepaymentMedium, Long> availableByMedium =
          availableByMedium(spendableMoney, spendableGoods);
      // ★★ §一.9：无货可还 ⇒ 挂账（有未偿本金、而可动用介质一件都没有）。只计数，不改口径。
      if (availableByMedium.isEmpty()) {
        householdsWithNoStockToRepay++;
      }
      if (RAW.isTraceEnabled()) {
        // ★★ W2（§一.9：逐笔明细 TRACE）—— S-5 介质序的现场证据：这一户这一轮按什么次序付、为什么是这个次序。
        EventLog.channel(RAW)
            .trace(
                LogEvent.of(
                    "DEBT_REPAY_MEDIUM_ORDER",
                    EconomyLogSource.ECONOMY_DEBT_STATE,
                    "day",
                    day,
                    "debtor",
                    debtor.value(),
                    "mediumOrder",
                    mediumOrder.stream().map(DebtValuation.RepaymentMedium::key).toList(),
                    "reserveCommodities",
                    0,
                    "why",
                    "ascending-estimated-unit-value (S-5) + v3.6-no-reserve-window"));
      }

      for (DebtContract debt : owed) {
        requireRepayableTerms(debt);
        DebtValuation.PaymentPlan plan =
            DebtValuation.choosePayment(
                debt.principal(),
                debt.unit(),
                debtor,
                mediumOrder,
                availableByMedium,
                market,
                householdPrices);
        long paid = plan.totalContractUnits();
        if (paid > 0L) {
          if (debtorsWithPayment.add(debtor)) {
            householdsRepaid++; // ★ §一.9：逐户只计一次（同一户可能有多条合同）
          }
          ActorRef payee =
              requireReceivableRepayee(
                  repayeeResolver, debt, householdOfActor, householdGoods, householdMoney);
          Transfer repayment =
              ledger.mint(
                  HouseholdActors.of(debtor),
                  payee,
                  householdEconomy.view().hex(),
                  plan.goodsLegs(),
                  plan.moneyLegs(),
                  TransferReason.LOAN_REPAYMENT);
          applyTransfer(
              householdGoods,
              householdMoney,
              householdFrozenGoods,
              householdFrozenMoney,
              householdOfActor,
              repayment,
              issuanceJournal);
          DebtContractBook.reduce(debts, debt.id(), paid);
          repaidPrincipalByDebt.merge(debt.id(), paid, Long::sum);
          for (DebtValuation.PaymentLeg leg : plan.legs()) {
            debitRepaymentMedium(availableByMedium, leg);
            if (leg instanceof DebtValuation.CommodityLeg commodityLeg) {
              if (commodityLeg.commodity().equals(GRAIN)) {
                repaid.merge(debtor, commodityLeg.quantityMilli(), Long::sum);
              }
            } else if (leg instanceof DebtValuation.MoneyLeg moneyLeg) {
              repaidMoney
                  .computeIfAbsent(debtor, ignored -> new LinkedHashMap<>())
                  .merge(moneyLeg.currency(), moneyLeg.amountMilli(), Long::sum);
            }
          }
        }
        if (RAW.isTraceEnabled()) {
          EventLog.channel(RAW)
              .trace(
                  LogEvent.of(
                      "DEBT_REPAY_DECISION",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtor",
                      debtor.value(),
                      "creditor",
                      debt.creditor().value(),
                      "unit",
                      debt.unit().key(),
                      "outstandingBefore",
                      debt.principal(),
                      "paid",
                      paid,
                      "remaining",
                      debt.principal() - paid,
                      "goodsLegs",
                      plan.goodsLegs(),
                      "moneyLegs",
                      plan.moneyLegs()));
          if (!plan.unpricedAssets().isEmpty()) {
            EventLog.channel(RAW)
                .trace(
                    LogEvent.of(
                        "DEBT_REPAY_UNPRICED",
                        EconomyLogSource.ECONOMY_DEBT_STATE,
                        "day",
                        day,
                        "debtor",
                        debtor.value(),
                        "creditor",
                        debt.creditor().value(),
                        "unit",
                        debt.unit().key(),
                        "remaining",
                        debt.principal() - paid,
                        "assets",
                        plan.unpricedAssets()));
          }
        }
        // ★ 没有稳定价格 ⇒ 不折算、不静默付 0：持有但缺价的资产落具名 skip（有腿时也报剩余本金）。
        recordUnpricedRepaymentSkipIfNeeded(
            ledger, debtor, debt, debt.principal() - paid, plan.unpricedAssets());
      }
    }
    // ★★ §一.9（v3.6 落地）：还债轮 INFO 一条 —— "本轮还了多少 / 有几户无货可还（挂账）"。
    //   ★ 具名口径：`householdsWithNoStockToRepay` = 有未偿本金、而手头可动用介质**一件都没有**的家户数
    //     （挂账、计息、不清零 —— 用户 0–8 第 7 条）⇒ 这就是"没库存就不还"的现场读数。
    EventLog.channel(TRACE)
        .info(
            LogEvent.of(
                "DEBT_REPAY_ROUND",
                EconomyLogSource.ECONOMY_DEBT,
                "day",
                day,
                "debtorsWithOutstanding",
                debtorsWithOutstanding,
                "householdsRepaid",
                householdsRepaid,
                "householdsWithNoStockToRepay",
                householdsWithNoStockToRepay,
                // ★★ W5-fix（BLOCKED-2）：本轮被排除的自持合同条数（`0` = 本世界没有这种合同）。
                "selfHeldExcluded",
                selfHeldExcluded,
                "repaidGrainMilli",
                traceTotalLongs(repaid),
                "debtContracts",
                debts.size(),
                "reserveCommodities",
                0,
                "why",
                "v3.6-no-reserve-window (有库存就还、没库存记债)"));
    if (TRACE.isDebugEnabled()) {
      // ★ 保留量口径 DEBUG 一条（为什么"一件都不扣"）：供现场判读"还债额为什么比上一版大"。
      EventLog.channel(TRACE)
          .debug(
              LogEvent.of(
                  "DEBT_REPAY_RESERVE_POLICY",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  day,
                  "reserveCommodities",
                  0,
                  "reserveMoney",
                  0,
                  "spendableGoodsRule",
                  "max(0, 余额 − 冻结)",
                  "spendableMoneyRule",
                  "max(0, 逐币种余额 − 冻结)",
                  "why",
                  "v3.6：D-030 §4 一日口粮保留 + v3.5 的 35 天窗口一并作废；卖侧前瞻未动"));
    }
  }

  /**
   * ★★★ <b>W2（2026-10-11）：S-5 的介质序 —— 该户预估价值最低的商品优先</b>。
   *
   * <pre>
   * 逐介质估值 = {@link DebtValuation#estimatedUnitValueMilli}(债务人, 单位, 市场默认价目表, 家户价目表)
   * 排序       = 估值升序 → 单位 canonical 串升序（同值/不可定价的稳定 tie-break，I7：序是内容的纯函数）
   * </pre>
   *
   * <p>★★ <b>它取代了什么</b>：改动前是 D-030 §4 的「全部货币（余额降序、币种 id 升序）→ 全部商品（余额降序、商品 id 升序）」 ——
   * <b>钱永远第一位</b>，商品内部只看数量、不看价值。用户 2026-10-10 第二批第 5 条已取代该口径：
   * 「偿还顺序不是先还钱或者先还粮还工具之类的，而是只取决于这个家户的哪个种类商品，预估价值最低」。
   *
   * <p>★ <b>不可定价（估值为 0）排最前</b>：0 价商品与"说不出价的资产"在"值不值钱"这一维上是同一档（都换不来钱）， 先付掉它们对债务人最有利。★ 与 {@code
   * choosePayment} 的分工不变：本方法只定<b>次序</b>， 逐笔能不能折、折多少仍由 {@code choosePayment}/{@code
   * commonValueMilli} 判（缺价 ⇒ 具名 skip，不静默付 0）。
   */
  static List<DebtValuation.RepaymentMedium> repaymentMediumOrder(
      HouseholdId debtor,
      Map<CurrencyId, Long> spendableMoney,
      Map<CommodityId, Long> spendableGoods,
      MarketTable market,
      DebtValuation.HouseholdPriceTable householdPrices) {
    List<DebtValuation.RepaymentMedium> order =
        new ArrayList<>(spendableMoney.size() + spendableGoods.size());
    for (CurrencyId currency : spendableMoney.keySet()) {
      order.add(new DebtValuation.MoneyMedium(currency));
    }
    for (CommodityId commodity : spendableGoods.keySet()) {
      order.add(new DebtValuation.CommodityMedium(commodity));
    }
    order.sort(
        Comparator.comparingLong(
                (DebtValuation.RepaymentMedium unit) ->
                    DebtValuation.estimatedUnitValueMilli(debtor, unit, market, householdPrices))
            .thenComparing(DebtValuation.RepaymentMedium::key));
    return order;
  }

  /** 可动余额的逐 unit 视图（D-030 §3.5 的逐 medium 扣减表）。 */
  static Map<DebtValuation.RepaymentMedium, Long> availableByMedium(
      Map<CurrencyId, Long> spendableMoney, Map<CommodityId, Long> spendableGoods) {
    LinkedHashMap<DebtValuation.RepaymentMedium, Long> available = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : spendableMoney.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        available.put(new DebtValuation.MoneyMedium(entry.getKey()), entry.getValue());
      }
    }
    for (Map.Entry<CommodityId, Long> entry : spendableGoods.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0L) {
        available.put(new DebtValuation.CommodityMedium(entry.getKey()), entry.getValue());
      }
    }
    return available;
  }

  /** 从逐 medium 余额表里扣掉一条已落账的付款腿（必须扣得动；不制造负余额）。 */
  static void debitRepaymentMedium(
      Map<DebtValuation.RepaymentMedium, Long> availableByMedium, DebtValuation.PaymentLeg leg) {
    DebtValuation.RepaymentMedium medium;
    long amount;
    if (leg instanceof DebtValuation.CommodityLeg commodityLeg) {
      medium = new DebtValuation.CommodityMedium(commodityLeg.commodity());
      amount = commodityLeg.quantityMilli();
    } else if (leg instanceof DebtValuation.MoneyLeg moneyLeg) {
      medium = new DebtValuation.MoneyMedium(moneyLeg.currency());
      amount = moneyLeg.amountMilli();
    } else {
      throw new IllegalStateException("未知偿还腿类型（拒绝静默跳过）: " + leg);
    }
    long remaining = availableByMedium.getOrDefault(medium, 0L) - amount;
    if (remaining < 0L) {
      throw new IllegalStateException(
          "偿还介质余额不足（唯一 applier 已落账，拒绝把余额扣成负）: medium="
              + medium.key()
              + " 余额="
              + availableByMedium.getOrDefault(medium, 0L)
              + " 付款="
              + amount);
    }
    availableByMedium.put(medium, remaining);
  }

  /**
   * ★★ <b>W2：还款轮的逐户资源快照</b>（{@link HouseholdValuationBook#derive} 的入参）—— 只读、不写状态。
   *
   * <pre>
   * market = 该户所在市场区的默认价目表（{@code marketsByHousehold}；缺 ⇒ 该户整户不给价，回落市场默认）
   * goods  = max(0, 现货 − 冻结)   money = max(0, 余额 − 冻结)
   * labor  = 该户当刻的劳动预算（估值不用它，照快照契约带上）
   * </pre>
   *
   * <p>★ 与 {@code LaborQueueSettlement.buildArbitrageSnapshots} 同一条"净额"口径（余额 − 冻结，夹到 ≥ 0）；
   * 那份是套利时刻、这份是还款时刻 —— 两者都不进状态，故不共享实例（时点不同）。
   */
  static Map<HouseholdId, HouseholdResourceSnapshot> repaymentSnapshots(
      long day,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<HouseholdId, MarketTable> marketsByHousehold) {
    List<HouseholdId> ordered = new ArrayList<>(householdEconomies.keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, HouseholdResourceSnapshot> snapshots = new LinkedHashMap<>();
    for (HouseholdId household : ordered) {
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null) {
        continue;
      }
      snapshots.put(
          household,
          new HouseholdResourceSnapshot(
              day,
              household,
              marketsByHousehold.get(household),
              netOf(householdGoods.get(household), householdFrozenGoods.get(household)),
              netOf(householdMoney.get(household), householdFrozenMoney.get(household)),
              Math.max(0L, row.laborMilli())));
    }
    return snapshots;
  }

  /**
   * ★★ <b>收款人账户闸门</b>（D-023 第 1 条）：债权人 → actor 由 {@link DebtValuation.RepayeeResolver}
   * 解析；收款账户必须在本日会话账里真的可收（家户账 or 经营者/actor 账）。否则具名 fail-closed，不让 {@code applyTransfer}
   * 的“两端缺一就跳过”把付款静默吞掉。
   */
  static ActorRef requireReceivableRepayee(
      DebtValuation.RepayeeResolver repayeeResolver,
      DebtContract debt,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    ActorRef payee;
    try {
      payee = repayeeResolver.resolve(debt.creditor());
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "偿还收款方解析失败（拒绝静默吞款）: 合同=" + debt.id() + " 债权人=" + debt.creditor(), failure);
    }
    if (payee == null) {
      throw new IllegalStateException(
          "偿还收款方为 null（拒绝静默吞款）: 合同=" + debt.id() + " 债权人=" + debt.creditor());
    }
    // ★★ P2-A §13.3：收款账户只有家户账 —— 解析不到家户 ⇒ 不可收（具名抛，不静默吞款）。
    HouseholdId householdRoute = householdOfActor.get(payee);
    boolean receivable =
        householdRoute != null
            && (householdGoods.containsKey(householdRoute)
                || householdMoney.containsKey(householdRoute));
    if (!receivable) {
      throw new IllegalStateException(
          "偿还收款方在本日账户会话里不可收（拒绝静默吞款）: 合同="
              + debt.id()
              + " creditor="
              + debt.creditor()
              + " payee="
              + payee
              + " householdRoute="
              + householdRoute);
    }
    return payee;
  }

  /** ★★ P11.1：持有资产缺稳定价格时的具名 skip（原因含资产清单；绝不把缺价静默成 0 付款）。 */
  static void recordUnpricedRepaymentSkipIfNeeded(
      ProductionLedger.Accumulator ledger,
      HouseholdId debtor,
      DebtContract debt,
      long remaining,
      List<String> unpricedAssets) {
    if (remaining <= 0L || unpricedAssets.isEmpty()) {
      return;
    }
    ledger.addDebtRepaymentSkip(
        new ProductionLedger.DebtRepaymentSkip(
            debtor,
            debt.id(),
            debt.unit(),
            remaining,
            DebtValuation.UNPRICED_ASSET_REASON_PREFIX + String.join(",", unpricedAssets)));
  }

  /**
   * ★★ <b>D-030 §3.5 的债排序</b>：未偿本金按共同价值升序；不可定价的排最后（unit id 升序）；tie = 债权人 id → 债的单位 id → 合同 id。
   *
   * <p>★ 共同价值用债务人自己的价目表（{@link DebtValuation#commonValueMilli}：家户表优先、市场区默认回退）；无法定价 ⇒ {@link
   * OptionalLong#empty()} ⇒ 排最后。本合同自己的单位不需要 price 吗？需要：排序要拿它和别的债比，故 仍走同一份价目表；真正“不需要 price”的是付款时的
   * identity 腿（见 {@link DebtValuation#choosePayment}）。
   */
  static Comparator<DebtContract> repaymentOrder(
      HouseholdId debtor, MarketTable market, DebtValuation.HouseholdPriceTable householdPrices) {
    return (left, right) -> {
      OptionalLong leftValue =
          DebtValuation.commonValueMilli(
              left.principal(), left.unit(), debtor, market, householdPrices);
      OptionalLong rightValue =
          DebtValuation.commonValueMilli(
              right.principal(), right.unit(), debtor, market, householdPrices);
      if (leftValue.isPresent() != rightValue.isPresent()) {
        return leftValue.isPresent() ? -1 : 1;
      }
      if (leftValue.isPresent()) {
        int byValue = Long.compare(leftValue.getAsLong(), rightValue.getAsLong());
        if (byValue != 0) {
          return byValue;
        }
      } else {
        int byUnit = left.unit().key().compareTo(right.unit().key());
        if (byUnit != 0) {
          return byUnit;
        }
      }
      int byCreditor = left.creditor().value().compareTo(right.creditor().value());
      if (byCreditor != 0) {
        return byCreditor;
      }
      int byUnit = left.unit().key().compareTo(right.unit().key());
      if (byUnit != 0) {
        return byUnit;
      }
      return left.id().value().compareTo(right.id().value());
    };
  }

  /**
   * ★★ <b>E4c：只对已接线的 legacy 条款走现有语义</b>——{@link RepaymentRule#AVAILABLE_SURPLUS_SHARE} + {@link
   * InterestTiming#AFTER_REPAYMENT_ON_CLOSE}。任何其它组合当场具名抛（fail-closed），不静默当默认档。
   */
  static void requireRepayableTerms(DebtContract debt) {
    if (debt.terms().repaymentRule() != RepaymentRule.AVAILABLE_SURPLUS_SHARE) {
      throw new IllegalStateException(
          "E4c 尚未接线非 legacy 偿还规则：合同="
              + debt.id()
              + " rule="
              + debt.terms().repaymentRule()
              + "（fail-closed，不静默当成默认档）");
    }
    if (debt.terms().interestTiming() != InterestTiming.AFTER_REPAYMENT_ON_CLOSE) {
      throw new IllegalStateException(
          "E4c 尚未接线非 legacy 计息时点：合同="
              + debt.id()
              + " timing="
              + debt.terms().interestTiming()
              + "（fail-closed，不静默当成默认档）");
    }
  }
}
