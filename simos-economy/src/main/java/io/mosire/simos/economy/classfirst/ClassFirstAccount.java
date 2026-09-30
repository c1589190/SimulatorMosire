package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.classfirst.PilotModel.AccountStatus;
import java.util.Objects;

/**
 * 阶层池双边滚动账户的持久形态（正净额 = claim、负净额 = debt；两侧镜像同时落表，Σnet 恒为 0）。
 *
 * <p>身份 = {@code (ownerId, counterpartyId, unit)} 的纯函数（{@link ClassFirstAccountId#idOf}）。{@code
 * interestRatePerMille} 是负债腿的利率（滚动时只加 debt），必须与账户状态一起持久化 —— 只存 {@link PilotModel.RollingAccount}
 * 读数会丢这一维。
 *
 * @param id 稳定身份；必须等于 {@code ClassFirstAccountId.idOf(ownerId, counterpartyId, unit)}
 * @param ownerId 账户所有人（阶层池 classPositionId 或放贷方 id）
 * @param counterpartyId 对手方（同上）
 * @param unit 计价/计物单位（{@link PilotModel#GRAIN} / {@link PilotModel#MONEY}）
 * @param terms 条款词（租金/投入/借款等）
 * @param interestRatePerMille 负债腿利率（千分）
 * @param nextDueTick 下次到期 tick
 * @param cumulativeNet 累计净额（> 0 claim，< 0 debt）
 * @param interestAccrued 累计已计利息
 * @param status 滚动状态
 */
public record ClassFirstAccount(
    ClassFirstAccountId id,
    String ownerId,
    String counterpartyId,
    String unit,
    String terms,
    long interestRatePerMille,
    long nextDueTick,
    long cumulativeNet,
    long interestAccrued,
    AccountStatus status) {

  public ClassFirstAccount {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    if (ownerId == null || ownerId.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccount.ownerId 不得为空白");
    }
    if (counterpartyId == null || counterpartyId.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccount.counterpartyId 不得为空白");
    }
    if (unit == null || unit.isBlank()) {
      throw new IllegalArgumentException("ClassFirstAccount.unit 不得为空白");
    }
    if (terms == null) {
      throw new IllegalArgumentException("ClassFirstAccount.terms 不得为 null");
    }
    if (interestRatePerMille < 0L) {
      throw new IllegalArgumentException(
          "ClassFirstAccount.interestRatePerMille 必须 >= 0: " + interestRatePerMille);
    }
    ClassFirstAccountId derived = ClassFirstAccountId.idOf(ownerId, counterpartyId, unit);
    if (!id.equals(derived)) {
      throw new IllegalArgumentException(
          "ClassFirstAccount.id 必须由 (ownerId, counterpartyId, unit) 确定性派生：id="
              + id
              + "，派生="
              + derived);
    }
  }
}
