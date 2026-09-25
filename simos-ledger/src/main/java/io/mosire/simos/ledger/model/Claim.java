package io.mosire.simos.ledger.model;

import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 索取权（应收应付）一条（增量 2 spec §3 逐字）：拖欠、欠薪、欠税、地租、本金债权。
 *
 * <p>★ **实物债与货币债分开**：{@code commodity} 有值 ⇒ 实物债（{@code amount} 是最小计量单位）； 空 ⇒ 货币债（{@code amount}
 * 是最小币值）。both 量纲都是整数，**禁 {@code double}**（spec §〇）。
 *
 * <p>★ **借款不凭空造现钞**（设计稿 §6.2）：本切片只记"谁欠谁多少"，钱的搬运由 {@link Transfer} 记； 守恒由命令层/协调器校验，**不落成状态**。
 *
 * <p>★ 不变量（spec §3 逐字）：{@code amount ≥ 0}、{@code dueDay ≥ 0}。三个 {@code Optional*} 组件不得为 null
 * （缺席用空值，照 {@code SocialCity.region}）。
 *
 * @param id 稳定身份
 * @param kind 债权种类
 * @param debtor 债务人
 * @param creditor 债权人
 * @param commodity 实物债的商品；**货币债 = {@code Optional.empty()}**
 * @param amount 数额（实物债 = 最小计量单位 / 货币债 = 最小币值）；不得为负
 * @param dueDay 到期日（日）；不得为负
 * @param settledDay 已清偿日（日）；**未清偿 = {@code OptionalLong.empty()}**
 */
public record Claim(
    ClaimId id,
    ClaimKind kind,
    ActorRef debtor,
    ActorRef creditor,
    Optional<CommodityId> commodity,
    long amount,
    long dueDay,
    OptionalLong settledDay) {

  public Claim {
    if (id == null) {
      throw new IllegalArgumentException("Claim.id 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("Claim.kind 不得为 null");
    }
    if (debtor == null) {
      throw new IllegalArgumentException("Claim.debtor 不得为 null");
    }
    if (creditor == null) {
      throw new IllegalArgumentException("Claim.creditor 不得为 null");
    }
    if (commodity == null) {
      throw new IllegalArgumentException("Claim.commodity 不得为 null（货币债用 Optional.empty()）");
    }
    if (amount < 0) {
      throw new IllegalArgumentException("Claim.amount 不得为负: " + amount);
    }
    if (dueDay < 0) {
      throw new IllegalArgumentException("Claim.dueDay 不得为负: " + dueDay);
    }
    if (settledDay == null) {
      throw new IllegalArgumentException("Claim.settledDay 不得为 null（未清偿用 OptionalLong.empty()）");
    }
  }
}
