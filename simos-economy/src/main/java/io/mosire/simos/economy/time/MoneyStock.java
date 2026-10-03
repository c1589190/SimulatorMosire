package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P5：货币存量的纯读口（逐币种；不写任何状态、不新增 {@code EconomyData} 组件）</b>。
 *
 * <pre>
 * circulation(c)        = Σ 全部已登记账户的 c 余额（家户 + 经营者 + 国库 + UNIT；快照口径）
 * cumulativeIssuance(c) = Σ kind.issuance() 的审计金额
 * cumulativeWithdrawal(c) = Σ kind.withdrawal() 的审计金额
 * 守恒式（相对创世登记时点）:
 *   circulation(c) + cumulativeWithdrawal(c) == cumulativeIssuance(c)
 * </pre>
 *
 * <p>★★ <b>为什么是纯读口而不是第二本账</b>：账户余额只有 {@link AccountSession} 一处权威，发行/回笼只有 {@code
 * EconomyData.moneyIssuances} 一处权威；本类每次调用都从这两处现算，不缓存、不落盘、不进 ChangeSet/Codec。P9 用它做差分断言；{@link
 * #circulationMinusNetIssuance} 返回的逐币种差额 0 = 该币种在 "全部创世钱包恰好由 INITIAL_ENDOWMENT 解释"的世界里守恒。
 *
 * <p>★ <b>只读快照</b>：{@link AccountSession#snapshot()} 在构造时逐值拷贝并冻结；本类不持有会话，读完即弃。
 */
public final class MoneyStock {

  private MoneyStock() {}

  /** Σ 全部已登记账户的逐币种余额（保序 = 币种首次出现序；只读）。 */
  public static Map<CurrencyId, Long> circulation(AccountSession accounts) {
    Objects.requireNonNull(accounts, "accounts");
    AccountSnapshot snapshot = accounts.snapshot();
    LinkedHashMap<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (AccountPartitionKey key : snapshot.accountKeys()) {
      for (Map.Entry<CurrencyId, Long> leg : snapshot.requireAccount(key).money().entrySet()) {
        totals.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /** Σ 发行方向（{@code kind.issuance()}）的审计金额（逐币种；保序 = 记录表首现序）。 */
  public static Map<CurrencyId, Long> cumulativeIssuance(
      Map<MoneyIssuanceId, MoneyIssuanceRecord> records) {
    return sumByKind(records, true);
  }

  /** Σ 回笼方向（{@code kind.withdrawal()}）的审计金额（逐币种；保序 = 记录表首现序）。 */
  public static Map<CurrencyId, Long> cumulativeWithdrawal(
      Map<MoneyIssuanceId, MoneyIssuanceRecord> records) {
    return sumByKind(records, false);
  }

  /**
   * 逐币种差额：{@code circulation + cumulativeWithdrawal − cumulativeIssuance}。
   *
   * <p>0 = 该币种守恒（全部创世钱包由 {@code INITIAL_ENDOWMENT} 记录解释，且此后只有 FISCAL_ISSUE/WITHDRAWAL 改变流通量）；非 0
   * 是待裁定口径差（旧档缺创世记录、账户未全部载入会话等），本方法如实给出，不静默抹平。
   */
  public static Map<CurrencyId, Long> circulationMinusNetIssuance(
      AccountSession accounts, Map<MoneyIssuanceId, MoneyIssuanceRecord> records) {
    Map<CurrencyId, Long> circulation = circulation(accounts);
    Map<CurrencyId, Long> issued = cumulativeIssuance(records);
    Map<CurrencyId, Long> withdrawn = cumulativeWithdrawal(records);
    Set<CurrencyId> currencies = new LinkedHashSet<>(circulation.keySet());
    currencies.addAll(issued.keySet());
    currencies.addAll(withdrawn.keySet());
    LinkedHashMap<CurrencyId, Long> diff = new LinkedHashMap<>();
    for (CurrencyId currency : currencies) {
      long value =
          circulation.getOrDefault(currency, 0L)
              + withdrawn.getOrDefault(currency, 0L)
              - issued.getOrDefault(currency, 0L);
      diff.put(currency, value);
    }
    return Collections.unmodifiableMap(diff);
  }

  /** 按方向求和的唯一实现（{@code issuance=true} 取发行，false 取回笼）。 */
  private static Map<CurrencyId, Long> sumByKind(
      Map<MoneyIssuanceId, MoneyIssuanceRecord> records, boolean issuance) {
    Objects.requireNonNull(records, "records");
    LinkedHashMap<CurrencyId, Long> totals = new LinkedHashMap<>();
    for (MoneyIssuanceRecord record : records.values()) {
      if (record.kind().issuance() != issuance) {
        continue;
      }
      totals.merge(record.currency(), record.amount(), Math::addExact);
    }
    return Collections.unmodifiableMap(totals);
  }
}
