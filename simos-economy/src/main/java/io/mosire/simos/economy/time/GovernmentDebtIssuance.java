package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
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

/**
 * ★★ <b>2026-10-07 GOV 非生产家户试点：周期开始日的政府发债写口</b>。
 *
 * <p>对每个 {@code debtIssuePerCycle > 0} 的政府，在每个产业大周期开始日按固定目标向**家户**借入货币：
 * 出借人的货币余额真实减少、政府国库账户真实增加，并写一条 {@link DebtContract}(debtor=政府家户, creditor=家户, unit={@code money},
 * terms=legacyDefault)。它<b>不</b>发行新钱 —— 钱只是从家户搬家到政府；政府日后再用这笔钱买粮/布， 从而把“发债→支出→货币回流家户”的链条跑通。
 *
 * <pre>
 * 出借人筛选：EconomyData.classes 里 population > 0 的家户（按 HouseholdId 规范序）
 * 出借上限：余额 − max(0, 人口 × MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI)
 * 目标不足：借到实际可借总额为止，写 GOV_DEBT_ISSUE_SHORTFALL（不静默补钱/不跳过整条）
 * 合同到期：currentCycle + 1（与市场信用同一条“下周期到期”口径）
 * </pre>
 *
 * <p>★★ <b>为什么不复用市场信用路径</b>：市场信用是“买方缺钱买货”的内生结果，金额由缺口/放贷头寸决定；
 * 本批要的是<b>政府主动发行的定量债务</b>（政策量），两者是不同事实，各有显式写口与审计。两者最终都落同一张 {@code debtContracts} 表、同一批 {@link
 * DebtContractBook} 写口，故不会出现第二本债账。
 */
final class GovernmentDebtIssuance {

  /** 债务日志（debt 分类）：逐周期发债与借不满的 shortfall。 */
  private static final org.slf4j.Logger LOG = EconomyLog.debt();

  private GovernmentDebtIssuance() {}

  /** 在周期开始日执行全部政府的发债政策；返回本次实际借入的货币总量（跨政府/币种直接相加，只作日志/调用方读数）。 */
  static long issueCycleStart(
      EconomyData base,
      EconomySession session,
      AccountSession accounts,
      long day,
      long currentCycle) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    if (base.governments().isEmpty()) {
      return 0L;
    }
    Map<HouseholdId, HouseholdEconomy> householdEconomies = session.sheet().householdEconomies();
    Map<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    long issuedTotal = 0L;
    for (Government government : base.governments().values()) {
      long target = government.debtIssuePerCycle();
      if (target <= 0L) {
        continue;
      }
      if (government.issuable().isEmpty()) {
        EventLog.channel(LOG)
            .warn(
                LogEvent.of(
                    "GOV_DEBT_ISSUE_SKIPPED",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "government",
                    government.id().value(),
                    "reason",
                    "no-issuable-currency",
                    "target",
                    target));
        continue;
      }
      // 单政府单币种试点：取第一个可发行币种作为债务币种（LinkedHashSet 保序 ⇒ 确定性）。
      CurrencyId currency = government.issuable().iterator().next();
      HouseholdId governmentHousehold = householdOf(government);
      HouseholdEconomy governmentHouseholdEconomy = householdEconomies.get(governmentHousehold);
      if (governmentHouseholdEconomy == null) {
        throw new IllegalStateException(
            "GOV 发债找不到政府家户行（seed/账户会话不完整）：government="
                + government.id()
                + " household="
                + governmentHousehold);
      }
      ActorAccount treasury = accountOf(accounts, government);
      if (treasury == null) {
        throw new IllegalStateException(
            "GOV 发债找不到国库账户（拒绝静默跳过）：government="
                + government.id()
                + " treasury="
                + government.treasury());
      }

      long remaining = target;
      long borrowed = 0L;
      int lenderCount = 0;
      for (HouseholdId lender : sortedLenders(householdEconomies, governmentHousehold)) {
        if (remaining <= 0L) {
          break;
        }
        HouseholdEconomy lenderHouseholdEconomy = householdEconomies.get(lender);
        if (lenderHouseholdEconomy == null || lenderHouseholdEconomy.population() <= 0L) {
          continue;
        }
        ActorAccount lenderAccount = accounts.householdAccount(lender);
        if (lenderAccount == null) {
          continue; // 人口 > 0 的家户缺账应由载入阶段 fail-closed；这里只防御手搭会话
        }
        long balance = lenderAccount.money().getOrDefault(currency, 0L);
        long reserve =
            Math.multiplyExact(
                lenderHouseholdEconomy.population(),
                MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI);
        long lendable = Math.max(0L, balance - reserve);
        long take = Math.min(remaining, lendable);
        if (take <= 0L) {
          continue;
        }
        // ① 家户货币 −take
        Map<CurrencyId, Long> lenderMoney = new LinkedHashMap<>(lenderAccount.money());
        long lenderAfter = Math.subtractExact(balance, take);
        if (lenderAfter > 0L) {
          lenderMoney.put(currency, lenderAfter);
        } else {
          lenderMoney.remove(currency);
        }
        lenderAccount.replaceMoney(lenderMoney);
        // ② 政府国库货币 +take
        Map<CurrencyId, Long> treasuryMoney = new LinkedHashMap<>(treasury.money());
        long treasuryBefore = treasuryMoney.getOrDefault(currency, 0L);
        treasuryMoney.put(currency, Math.addExact(treasuryBefore, take));
        treasury.replaceMoney(treasuryMoney);
        // ③ 同一条债务写口（与市场信用共用 DebtContractBook）
        DebtContract contract =
            DebtContractBook.upsert(
                debts,
                governmentHousehold,
                lender,
                new DebtUnit.Money(currency),
                DebtTerms.legacyDefault(),
                take,
                day,
                OptionalLong.of(currentCycle + 1L));
        householdEconomies.put(
            governmentHousehold,
            DebtContractBook.withDebtReference(governmentHouseholdEconomy, contract.id()));
        governmentHouseholdEconomy = householdEconomies.get(governmentHousehold);
        remaining = Math.subtractExact(remaining, take);
        borrowed = Math.addExact(borrowed, take);
        lenderCount++;
      }
      issuedTotal = Math.addExact(issuedTotal, borrowed);
      if (remaining > 0L) {
        EventLog.channel(LOG)
            .warn(
                LogEvent.of(
                    "GOV_DEBT_ISSUE_SHORTFALL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "government",
                    government.id().value(),
                    "currency",
                    currency.value(),
                    "target",
                    target,
                    "issued",
                    borrowed,
                    "shortfall",
                    remaining,
                    "lenders",
                    lenderCount));
      }
      if (borrowed > 0L) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "GOV_DEBT_ISSUE",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "government",
                    government.id().value(),
                    "period",
                    currentCycle,
                    "currency",
                    currency.value(),
                    "target",
                    target,
                    "issued",
                    borrowed,
                    "lenders",
                    lenderCount,
                    "treasuryBefore",
                    treasury.money().getOrDefault(currency, 0L) - borrowed,
                    "treasuryAfter",
                    treasury.money().getOrDefault(currency, 0L)));
      }
    }
    return issuedTotal;
  }

  /** 出借人家户（人口 > 0、排除政府自己），按 {@link HouseholdId#value()} 规范序 —— 同一世界/重放同序。 */
  private static List<HouseholdId> sortedLenders(
      Map<HouseholdId, HouseholdEconomy> householdEconomies, HouseholdId governmentHousehold) {
    List<HouseholdId> lenders = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      if (householdEconomyEntry.getKey().equals(governmentHousehold)) {
        continue;
      }
      if (householdEconomyEntry.getValue() != null
          && householdEconomyEntry.getValue().population() > 0L) {
        lenders.add(householdEconomyEntry.getKey());
      }
    }
    lenders.sort(Comparator.comparing(HouseholdId::value));
    return lenders;
  }

  /** 政府身份 → 政府家户 id（treasury 必须是 HOUSEHOLD actor；非家户具名拒）。 */
  private static HouseholdId householdOf(Government government) {
    return HouseholdRouting.requireHouseholdOf(government.treasury());
  }

  /** ★★ P2-A §13.3：国库账户 = 政府家户账户（不再有经营者账户旁路）。 */
  private static ActorAccount accountOf(AccountSession accounts, Government government) {
    return accounts.householdAccount(householdOf(government));
  }
}
