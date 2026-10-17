package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>一个参与户在一个生产角色上的实际劳动贡献</b>（D-032 D2 §6.1）。
 *
 * <p>★ 量纲：{@link #laborMilli()} = <b>毫小时/日</b>（1 小时 = 1000 毫小时），与 {@code
 * HouseholdEconomy.laborMilli} / {@code HouseholdLaborCommitment.laborMilli} 一致。
 *
 * <p>★ 每个请求内 {@code (household, role)} 必须唯一，且逐项 {@code > 0}；同一户可以在多个角色上分别出工。 唯一性与正值由 {@link
 * ProductionExecutionRequest} / {@link ProductionDayRequest.Step} 的列表构造器统一把关。
 *
 * @param household 参与家户；不得为 null
 * @param role 方案内生产角色；不得为 null
 * @param laborMilli 本项实际劳动（毫小时/日）；必须 &gt; 0
 */
public record ParticipantContribution(
    HouseholdId household, ProductionRoleId role, long laborMilli) {

  public ParticipantContribution {
    Objects.requireNonNull(household, "ParticipantContribution.household 不得为 null");
    Objects.requireNonNull(role, "ParticipantContribution.role 不得为 null");
    if (laborMilli <= 0L) {
      throw new IllegalArgumentException(
          "ParticipantContribution.laborMilli 必须为正（毫小时/日）: " + laborMilli);
    }
  }

  /** 本贡献对应的复合键（家户 × 角色）。 */
  public ProductionParticipantKey participantKey() {
    return new ProductionParticipantKey(household, role);
  }
}
