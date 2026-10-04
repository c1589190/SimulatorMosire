package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>2026-10-07 GOV 非生产家户试点：周期开始日的政府铸币写口</b>。
 *
 * <p>它对每个 {@code seignioragePerCycle > 0} 的政府，在每个产业大周期开始日把该数量的货币直接记入国库账户，
 * 并写一条 {@link MoneyIssuanceKind#FISCAL_ISSUE} 审计记录。它<b>不</b>产生 {@code Transfer}、不碰商品库存：
 * “政府缺钱直接印”只改两处事实 —— 国库货币余额 + 发行审计账。
 *
 * <pre>
 * 触发日：day == 1 或 (day − 1) % max(industry.cycleDays) == 0
 * 金额：每个 issuable 币种各 seignioragePerCycle（单政府单币种试点下就是一笔 silver）
 * 账户：treasury.kind()==HOUSEHOLD ⇒ 家户账户；否则 ⇒ 经营者/政府账户
 * </pre>
 *
 * <p>★★ <b>为什么直接写账户而不是走“余额不足的发行腿”</b>：日结算的市场转移调用不携带
 * {@link EconomySettlement.MoneyIssuanceJournal}，若让政府先花空再靠透支发行，市场路径会先撞上
 * “付方余额不足且无 journal”的 fail-closed。周期开始先按政策铸币，把“印钱”做成显式、可审计、可重放的一步；
 * 政府真正入不敷出时仍走现有市场信用路径（形成 {@code DebtContract}），不会静默透支。
 *
 * <p>★ 幂等/重放：{@link MoneyIssuanceId} 由 {@code (governmentId, day, currency)} 确定性派生；
 * 同一天同一政府同一币种第二次写入 ⇒ 当场抛，不静默覆盖。
 */
final class GovernmentSeigniorage {

  /** 经济结算日志（settlement 分类）：周期开头的发行事件。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private GovernmentSeigniorage() {}

  /**
   * ★ 本日是不是“产业大周期开始日”。没有产业（旧档/未激活）时只有 day==1 算开始日；产业周期不等长时取最大周期
   * （试点世界三个产业同为 120 天，取最大值不会改变现有节奏）。
   */
  static boolean isCycleStart(EconomyData base, long day) {
    Objects.requireNonNull(base, "base");
    if (day < 1L) {
      return false;
    }
    long cycleDays = 0L;
    for (Industry industry : base.industries().values()) {
      cycleDays = Math.max(cycleDays, industry.cycleDays());
    }
    if (cycleDays <= 0L) {
      return day == 1L;
    }
    return day == 1L || (day - 1L) % cycleDays == 0L;
  }

  /**
   * ★★ 在周期开始日执行全部政府的铸币政策；返回本次增发的货币总量（各币种直接相加，只作日志/调用方读数）。
   *
   * @param base 本次 revision 的不可变基态（读 {@code governments} 与产业周期）
   * @param session 本次推进会话（发行审计写进它的 {@code moneyIssuances} 工作表）
   * @param accounts 账户会话（国库余额的唯一工作副本）
   * @param day 当前世界日
   * @param currentCycle 当前产业周期序号（≥ 1；进发行审计）
   */
  static long settleCycleStart(
      EconomyData base, EconomySession session, AccountSession accounts, long day, long currentCycle) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    if (base.governments().isEmpty()) {
      return 0L;
    }
    long minted = 0L;
    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances = session.sheet().moneyIssuances();
    for (Government government : base.governments().values()) {
      long amount = government.seignioragePerCycle();
      if (amount <= 0L) {
        continue;
      }
      ActorAccount treasury = accountOf(accounts, government);
      if (treasury == null) {
        throw new IllegalStateException(
            "政府铸币找不到国库账户（seed/账户会话不完整，拒绝静默跳过）：政府="
                + government.id()
                + " treasury="
                + government.treasury());
      }
      for (CurrencyId currency : government.issuable()) {
        Map<CurrencyId, Long> money = new LinkedHashMap<>(treasury.money());
        long before = money.getOrDefault(currency, 0L);
        long after = Math.addExact(before, amount);
        money.put(currency, after);
        treasury.replaceMoney(money);

        MoneyIssuanceId id = issuanceId(government, day, currency);
        MoneyIssuanceRecord record =
            new MoneyIssuanceRecord(
                id,
                government.id(),
                day,
                currentCycle,
                currency,
                amount,
                MoneyIssuanceKind.FISCAL_ISSUE,
                "GOV 非生产家户试点：周期开始政府为自己增发货币");
        if (issuances.putIfAbsent(id, record) != null) {
          throw new IllegalStateException(
              "政府铸币审计 id 重复（同一天同一政府同一币种只允许一条）：" + id.value());
        }
        minted = Math.addExact(minted, amount);
        LOG.info(
            "event=GOV_SEIGNIORAGE government={} day={} period={} currency={} amount={} treasuryBefore={} treasuryAfter={} accountActor={}",
            government.id().value(),
            day,
            currentCycle,
            currency.value(),
            amount,
            before,
            after,
            government.treasury());
      }
    }
    return minted;
  }

  /** 国库账户：HOUSEHOLD treasury 走家户账户，其余（含 class-first 的 GOVERNMENT）走经营者账户。 */
  private static ActorAccount accountOf(AccountSession accounts, Government government) {
    if (government.treasury().kind() == ActorKind.HOUSEHOLD) {
      HouseholdId household = HouseholdActors.householdOf(government.treasury());
      return accounts.householdAccount(household);
    }
    return accounts.operatorAccount(government.treasury());
  }

  /** 发行审计 id（确定性、无随机/时钟；政府 id 里的 {@code '|'} 换成 {@code ':'} 以避开账户/编码分段符）。 */
  private static MoneyIssuanceId issuanceId(
      Government government, long day, CurrencyId currency) {
    return new MoneyIssuanceId(
        "gov-seigniorage-"
            + government.id().value().replace('|', ':')
            + "-"
            + day
            + "-"
            + currency.value());
  }
}
