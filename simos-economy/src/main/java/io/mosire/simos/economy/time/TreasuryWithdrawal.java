package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.money.MoneyIssuance;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>P5：发行主体国库回笼（{@link MoneyIssuanceKind#WITHDRAWAL}）的唯一写口 + 审计落点</b>。
 *
 * <pre>
 * ① 授权：governmentId 必须存在，且 currency ∈ Government.issuable
 *         （Government.authorityOf(currency) 就是付方国库；说不出授权 ⇒ 具名抛）
 * ② 金额：amount &gt; 0；可用余额 = max(0, 国库余额 − 国库冻结)；amount ≤ 可用余额
 *         （普通账户与国库都不得透支；冻结只表达已明确的占用，回笼不得动它）
 * ③ 落账：国库余额 −amount（归零去键）；发行审计表写一条 WITHDRAWAL（金额记正、方向由 kind 表达）
 * ④ id：MoneyIssuanceId.forWithdrawal(operationRef, currency)（现有拼写点的确定性派生）
 * ⑤ 幂等：同一 operationRef 重放 ⇒ 审计已在、余额必然已扣 ⇒ 逐值比对后 no-op（不一致 ⇒ 具名抛）
 * </pre>
 *
 * <p>★★ <b>为什么需要它</b>：{@code EconomyData.moneyIssuances} 的读口（{@code ApiViews.moneyIssuanceView}）与
 * {@link MoneyIssuanceKind#WITHDRAWAL} 早已存在，但运行期没有一处能从国库真正注销货币并把审计写下来 ——
 * 缺了本口，回笼只能靠"改账但无记录"（静默销钱）或"FISCAL_ISSUE 反向"（把发行记成负数）两条本仓明文禁止的路。
 *
 * <p>★★ <b>它是账户写口，不是转移写口</b>：回笼没有对端（钱被注销，不是进另一个账户）⇒ 不走 {@code EconomySettlement.applyTransfer}，也不产生
 * {@code Transfer}/流水。它只改两处：<b>国库货币余额</b>与 <b>发行审计表</b>；普通家户/经营者账户一字不碰。
 *
 * <p>★★ <b>当前调用边界（P5，如实记）</b>：本批只提供写口，<b>没有</b>把它接进日结算的自动路径（日结算里唯一的 发行腿仍是 FISCAL_ISSUE）。P7 的 GM
 * 编辑工具接它：GM 的"回笼/注销"命令带一个稳定 {@code operationRef} 进入一次 revision 会话，成功后由 {@code
 * EconomySession.build()} 把审计表与国库余额一起交进 ChangeSet。 {@code TreasuryWithdrawal.withdraw}
 * 因此不做任何命令期/结算期判断（不读规则表、不调策略），只做上表 ①–⑤ 的落账守卫。
 */
public final class TreasuryWithdrawal {

  /** 审计原因前缀（具名来源；后接 operationRef）。 */
  public static final String REASON_PREFIX = "回笼：GM 注销";

  private TreasuryWithdrawal() {}

  /**
   * ★★ 从发行主体国库注销一笔货币并写一条 WITHDRAWAL 审计记录（全有或全无：任一条守卫不过 ⇒ 活表一字未动）。
   *
   * @param session 经济会话（提供政府表、周期与发行审计工作表）；不得为 null，且 {@code meta} 必须在场
   * @param accounts 账户会话（国库货币余额/冻结的唯一来源）；不得为 null；国库账户必须已登记
   * @param governmentId 发行主体；必须存在于当前政府表
   * @param currency 回笼币种；必须在该政府的 {@code issuable} 集合内
   * @param amount 回笼金额；必须 &gt; 0 且 ≤ 国库可用余额（余额 − 冻结）
   * @param day 世界日；不得为负
   * @param operationRef 回笼命令的稳定身份（P7/GM 提供；同一 ref 重放 = 同一条记录）；不得空白，且不得含 {@code '.'} / {@code '|'}
   * @return 本次（或重放命中的那条）WITHDRAWAL 审计记录
   * @throws IllegalArgumentException 政府/币种/金额/ref 不合法，或币种不在该政府 {@code issuable} 内
   * @throws IllegalStateException 国库账户不在会话副本里、可用余额不足、已登记发行主体与世界表不一致，或同 ref 重放但内容不同
   */
  public static MoneyIssuanceRecord withdraw(
      EconomySession session,
      AccountSession accounts,
      GovernmentId governmentId,
      CurrencyId currency,
      long amount,
      long day,
      String operationRef) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(governmentId, "governmentId");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(operationRef, "operationRef");
    if (amount <= 0L) {
      throw new IllegalArgumentException("回笼金额必须 > 0（方向由 WITHDRAWAL 表达，不用负号）: " + amount);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("回笼的日号不得为负: " + day);
    }
    EconomyMeta meta =
        session
            .sheet()
            .meta()
            .orElseThrow(
                () -> new IllegalStateException("经济切片未激活（EconomyMeta 缺席）⇒ 说不出回笼属于哪个周期，拒绝写发行审计"));
    Government government = session.sheet().governments().get(governmentId);
    if (government == null) {
      throw new IllegalArgumentException("回笼的发行主体不在当前政府表里（说不出这是哪一届政府在回笼）: " + governmentId);
    }
    // ★ 授权：issuable 不含该币种 ⇒ authorityOf 具名抛（"它不发行这个币"），这一步就是 ② 的币种闸。
    ActorRef treasury = government.authorityOf(currency);
    // ★ 若进程登记表已登记该币种，发行主体必须与世界表一致（两处漂开时拒绝按任一侧静默销账）。
    ActorRef registered = MoneyIssuance.issuerOfRegistered(currency);
    if (registered != null && !registered.equals(treasury)) {
      throw new IllegalStateException(
          "币种 "
              + currency
              + " 的已登记发行主体是 "
              + registered
              + "，与世界表给出的国库 "
              + treasury
              + " 不一致（拒绝按任一侧单边销账）");
    }
    long period = meta.lastClosedCycle().orElse(0L) + 1L;
    MoneyIssuanceId id = MoneyIssuanceId.forWithdrawal(operationRef, currency);
    MoneyIssuanceRecord record =
        new MoneyIssuanceRecord(
            id,
            governmentId,
            day,
            period,
            currency,
            amount,
            MoneyIssuanceKind.WITHDRAWAL,
            REASON_PREFIX + "（ref=" + operationRef + "）");
    LinkedHashMap<MoneyIssuanceId, MoneyIssuanceRecord> audit = session.sheet().moneyIssuances();
    MoneyIssuanceRecord existing = audit.get(id);
    if (existing != null) {
      // ★ 同一命令重放：审计已在 ⇒ 上一次调用已经把余额扣掉了；逐值相同才允许 no-op。
      if (!existing.equals(record)) {
        throw new IllegalStateException(
            "回笼命令 ref 相同但内容不同（id 冲突，拒绝覆盖审计）: id=" + id + " 旧=" + existing + " 新=" + record);
      }
      return existing;
    }
    // ★★ P2-A §13.3：国库不再是"经营者账" —— 政府家户就是账户主体（treasury 恒为 HOUSEHOLD actor）。
    //   国库账必须是会话里已载入的家户账（缺账不得静默当 0/不得造账）。
    HouseholdId treasuryHousehold = HouseholdRouting.requireHouseholdOf(treasury);
    Map<CurrencyId, Long> wallet = accounts.householdMoney().get(treasuryHousehold);
    if (wallet == null) {
      throw new IllegalStateException(
          "国库家户账户不在本会话副本里（拒绝从看不见的账上销账）：国库家户="
              + treasuryHousehold
              + "，政府="
              + governmentId);
    }
    long balance = wallet.getOrDefault(currency, 0L);
    long frozen =
        accounts
            .householdFrozenMoney()
            .getOrDefault(treasuryHousehold, Map.of())
            .getOrDefault(currency, 0L);
    long available = Math.max(0L, balance - frozen);
    if (amount > available) {
      throw new IllegalStateException(
          "国库可用余额不足以回笼（普通账户与国库都不得透支）：国库="
              + treasury
              + " 币种="
              + currency
              + " 余额="
              + balance
              + " 冻结="
              + frozen
              + " 可用="
              + available
              + " 回笼="
              + amount);
    }
    long after = balance - amount;
    Map<CurrencyId, Long> nextWallet = new LinkedHashMap<>(wallet);
    if (after <= 0L) {
      nextWallet.remove(currency); // 归零去键（与家户/经营者账户写手同口径）
    } else {
      nextWallet.put(currency, after);
    }
    accounts.householdMoney().put(treasuryHousehold, nextWallet);
    MoneyIssuanceRecord previous = audit.putIfAbsent(id, record);
    if (previous != null) {
      throw new IllegalStateException("回笼审计写口遇到并发 id 冲突（本次余额已扣、记录未写，调用方必须整批失败重放）: id=" + id);
    }
    return record;
  }
}
