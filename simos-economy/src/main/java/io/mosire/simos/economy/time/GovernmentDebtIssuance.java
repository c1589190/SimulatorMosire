package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
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
 * 出借人的货币余额真实减少、政府国库账户真实增加，并写一条 {@link DebtContract}(debtor=政府家户, creditor=家户,
 * unit={@code money}, terms=legacyDefault)。它<b>不</b>发行新钱 —— 钱只是从家户搬家到政府；政府日后再用这笔钱买粮/布，
 * 从而把“发债→支出→货币回流家户”的链条跑通。
 *
 * <pre>
 * 出借人筛选：EconomyData.classes 里 population > 0 的家户（按 HouseholdId 规范序）
 * 出借上限：余额 − max(0, 人口 × MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI)
 * 目标不足：借到实际可借总额为止，写 GOV_DEBT_ISSUE_SHORTFALL（不静默补钱/不跳过整条）
 * 合同到期：currentCycle + 1（与市场信用同一条“下周期到期”口径）
 * </pre>
 *
 * <p>★★ <b>为什么不复用市场信用路径</b>：市场信用是“买方缺钱买货”的内生结果，金额由缺口/放贷头寸决定；
 * 本批要的是<b>政府主动发行的定量债务</b>（政策量），两者是不同事实，各有显式写口与审计。两者最终都落同一张
 * {@code debtContracts} 表、同一批 {@link DebtContractBook} 写口，故不会出现第二本债账。
 */
final class GovernmentDebtIssuance {

  /** 债务日志（debt 分类）：逐周期发债与借不满的 shortfall。 */
  private static final org.slf4j.Logger LOG = EconomyLog.debt();

  private GovernmentDebtIssuance() {}

  /**
   * 在周期开始日执行全部政府的发债政策；返回本次实际借入的货币总量（跨政府/币种直接相加，只作日志/调用方读数）。
   */
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
    Map<HouseholdId, ClassRow> rows = session.sheet().rows();
    Map<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    long issuedTotal = 0L;
    for (Government government : base.governments().values()) {
      long target = government.debtIssuePerCycle();
      if (target <= 0L) {
        continue;
      }
      if (government.issuable().isEmpty()) {
        LOG.warn(
            "event=GOV_DEBT_ISSUE_SKIPPED government={} day={} reason=no-issuable-currency target={}",
            government.id().value(),
            day,
            target);
        continue;
      }
      // 单政府单币种试点：取第一个可发行币种作为债务币种（LinkedHashSet 保序 ⇒ 确定性）。
      CurrencyId currency = government.issuable().iterator().next();
      HouseholdId governmentHousehold = householdOf(government);
      ClassRow governmentRow = rows.get(governmentHousehold);
      if (governmentRow == null) {
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
      for (HouseholdId lender : sortedLenders(rows, governmentHousehold)) {
        if (remaining <= 0L) {
          break;
        }
        ClassRow lenderRow = rows.get(lender);
        if (lenderRow == null || lenderRow.population() <= 0L) {
          continue;
        }
        ActorAccount lenderAccount = accounts.householdAccount(lender);
        if (lenderAccount == null) {
          continue; // 人口 > 0 的家户缺账应由载入阶段 fail-closed；这里只防御手搭会话
        }
        long balance = lenderAccount.money().getOrDefault(currency, 0L);
        long reserve =
            Math.multiplyExact(
                lenderRow.population(), MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI);
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
        rows.put(
            governmentHousehold,
            DebtContractBook.withDebtReference(governmentRow, contract.id()));
        governmentRow = rows.get(governmentHousehold);
        remaining = Math.subtractExact(remaining, take);
        borrowed = Math.addExact(borrowed, take);
        lenderCount++;
      }
      issuedTotal = Math.addExact(issuedTotal, borrowed);
      if (remaining > 0L) {
        LOG.warn(
            "event=GOV_DEBT_ISSUE_SHORTFALL government={} day={} currency={} target={} issued={} shortfall={} lenders={}",
            government.id().value(),
            day,
            currency.value(),
            target,
            borrowed,
            remaining,
            lenderCount);
      }
      if (borrowed > 0L) {
        LOG.info(
            "event=GOV_DEBT_ISSUE government={} day={} period={} currency={} target={} issued={} lenders={} treasuryBefore={} treasuryAfter={}",
            government.id().value(),
            day,
            currentCycle,
            currency.value(),
            target,
            borrowed,
            lenderCount,
            treasury.money().getOrDefault(currency, 0L) - borrowed,
            treasury.money().getOrDefault(currency, 0L));
      }
    }
    return issuedTotal;
  }

  /** 出借人家户（人口 > 0、排除政府自己），按 {@link HouseholdId#value()} 规范序 —— 同一世界/重放同序。 */
  private static List<HouseholdId> sortedLenders(
      Map<HouseholdId, ClassRow> rows, HouseholdId governmentHousehold) {
    List<HouseholdId> lenders = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      if (entry.getKey().equals(governmentHousehold)) {
        continue;
      }
      if (entry.getValue() != null && entry.getValue().population() > 0L) {
        lenders.add(entry.getKey());
      }
    }
    lenders.sort(Comparator.comparing(HouseholdId::value));
    return lenders;
  }

  /** 政府身份 → 政府家户 id（本写口只支持 treasury = HOUSEHOLD）。 */
  private static HouseholdId householdOf(Government government) {
    if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
      throw new IllegalStateException(
          "GOV 发债需要 HOUSEHOLD 国库（非 HOUSEHOLD 国库不参与本写口）：" + government);
    }
    return HouseholdActors.householdOf(government.treasury());
  }

  /** 国库账户：HOUSEHOLD treasury 走家户账户；其余走经营者账户。 */
  private static ActorAccount accountOf(AccountSession accounts, Government government) {
    if (government.treasury().kind() == ActorKind.HOUSEHOLD) {
      return accounts.householdAccount(HouseholdActors.householdOf(government.treasury()));
    }
    return accounts.operatorAccount(government.treasury());
  }
}
