package io.mosire.simos.economy.api.classes;

import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★★ <b>D-032 C1：家户 ownershipClass 的只读跨切片查口</b>。
 *
 * <p>★ 实现方必须读<strong>同一份当刻活工作副本</strong>（Map transient land ledger、actor 商品库存、economy
 * assetShares）；不得读本 tick 之前的旧快照。S7-M2 working copy 内的中间态由实现方按当刻资产立即派生。
 *
 * <p>★ 本接口只读，没有写口；ownershipClass 不持久。
 */
@FunctionalInterface
public interface HouseholdOwnershipLookup {

  /**
   * @param household 家户稳定身份；不得为 null
   * @return 当刻派生阶级；不得返回 null
   */
  OwnershipClass ownershipClassOf(HouseholdId household);
}
