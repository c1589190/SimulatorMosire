package io.mosire.simos.economy.api.money;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import java.util.Objects;

/**
 * ★★ <b>货币发行/回笼的持久审计记录</b>（E3；设计稿 §2.6 的 {@code MoneyIssuance}）。它是
 * {@code EconomyData.moneyIssuances} 的值，进 ChangeSet/Codec，可持久、可回放。
 *
 * <p>★★ <b>它和账户余额的关系</b>：记录是<b>事实的账</b>，不是余额的第二本账。读口按窗口求和：
 *
 * <pre>
 * 累计发行 = Σ kind.issuance() 的 amount（逐币种）
 * 累计回笼 = Σ kind.withdrawal() 的 amount（逐币种）
 * 流通量   = Σ 全部账户余额（逐币种）
 * </pre>
 *
 * <p>发行腿的单边差额（发行主体余额不足时，收方仍足额到账）必须写一条 {@link MoneyIssuanceKind#FISCAL_ISSUE}
 * 记录；创世钱包必须写 {@link MoneyIssuanceKind#INITIAL_ENDOWMENT} 记录。金额为正、方向由 kind 表达。
 *
 * @param id 稳定身份；不得为 null
 * @param governmentId 发行主体（必须存在于 {@code EconomyData.governments}）；不得为 null
 * @param day 世界日；不得为负
 * @param period 经济周期序号（≥ 1；创世为 1）
 * @param currency 币种；不得为 null
 * @param amount 金额（最小币值）；必须 &gt; 0
 * @param kind 发行/回笼类别；不得为 null
 * @param reason 具名原因（审计用）；不得为空白
 */
public record MoneyIssuanceRecord(
    MoneyIssuanceId id,
    GovernmentId governmentId,
    long day,
    long period,
    CurrencyId currency,
    long amount,
    MoneyIssuanceKind kind,
    String reason) {

  public MoneyIssuanceRecord {
    Objects.requireNonNull(id, "MoneyIssuanceRecord.id 不得为 null");
    Objects.requireNonNull(governmentId, "MoneyIssuanceRecord.governmentId 不得为 null");
    Objects.requireNonNull(currency, "MoneyIssuanceRecord.currency 不得为 null");
    Objects.requireNonNull(kind, "MoneyIssuanceRecord.kind 不得为 null（方向必须显式）");
    if (day < 0L) {
      throw new IllegalArgumentException("MoneyIssuanceRecord.day 不得为负: " + day);
    }
    if (period <= 0L) {
      throw new IllegalArgumentException("MoneyIssuanceRecord.period 必须 ≥ 1: " + period);
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(
          "MoneyIssuanceRecord.amount 必须 > 0（方向由 kind 表达，不用负号）: " + amount);
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("MoneyIssuanceRecord.reason 不得为空白");
    }
    if (id.value().indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "MoneyIssuanceId 不得含 '|'（它同时是账户/编码分段符）: " + id.value());
    }
  }
}
