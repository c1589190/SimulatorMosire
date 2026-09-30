package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.HouseholdProductionAccountId;
import java.util.Objects;

/**
 * 池内家户生产子账户：只承载人口/劳动/份额与生产子账户读数，**不承载库存资产**（库存的唯一权威是 {@link ClassPool}）。
 *
 * <p>身份 = {@code (poolId, householdId)} 的纯函数（{@link HouseholdProductionAccountId#idOf}）。{@code
 * laborUnits} 是本账户当前可提供的劳动（千分劳动单位）。
 *
 * @param id 稳定身份；必须等于 {@code HouseholdProductionAccountId.idOf(poolId, householdId)}
 * @param poolId 所属阶层池
 * @param householdId 家户稳定 id（池内唯一）
 * @param name 家户名（报告用）
 * @param population 人口（>= 0；空池 seed 账户为 0）
 * @param laborPerCapita 人均劳动（千分）
 * @param participationSharePerMille 分配份额（千分）
 * @param laborUnits 本账户可提供劳动（千分劳动单位）
 */
public record HouseholdProductionAccount(
    HouseholdProductionAccountId id,
    ClassPoolId poolId,
    String householdId,
    String name,
    long population,
    long laborPerCapita,
    long participationSharePerMille,
    long laborUnits) {

  public HouseholdProductionAccount {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(poolId, "poolId");
    if (householdId == null || householdId.isBlank()) {
      throw new IllegalArgumentException("HouseholdProductionAccount.householdId 不得为空白");
    }
    if (name == null) {
      throw new IllegalArgumentException("HouseholdProductionAccount.name 不得为 null");
    }
    if (population < 0L || laborPerCapita < 0L || participationSharePerMille < 0L) {
      throw new IllegalArgumentException("家户账户的人口/劳动/份额都不得为负: " + householdId);
    }
    HouseholdProductionAccountId derived = HouseholdProductionAccountId.idOf(poolId, householdId);
    if (!id.equals(derived)) {
      throw new IllegalArgumentException(
          "HouseholdProductionAccount.id 必须由 (poolId, householdId) 确定性派生：id="
              + id
              + "，派生="
              + derived);
    }
  }
}
