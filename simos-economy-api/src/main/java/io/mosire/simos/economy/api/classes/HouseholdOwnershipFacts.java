package io.mosire.simos.economy.api.classes;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>D-032 C1：家户 ownershipClass 的跨切片只读输入事实</b>（不持久、不进 ChangeSet/Codec）。
 *
 * <p>它是装配层把三份权威读成同一把尺的唯一载体：
 *
 * <ul>
 *   <li>{@code landQuantityMilliMu}：Map transient LAND ledger/owner 视图中该户的 Σ 数量；
 *   <li>{@code commodityBalances}：actor 家户商品库存（只有 {@link OwnershipCapitalVocabulary} 登记的耐久商品资本才计入）；
 *   <li>{@code nonLandDurableAssetQuantity}：economy {@code assetShares} 中该户 owner 且 asset !=
 *       LAND、quantity &gt; 0 的合计。
 * </ul>
 *
 * <p>★ 失败语义：负数（装配错误）当场抛，不静默当 0；不可变、无 null。
 */
public record HouseholdOwnershipFacts(
    HouseholdId household,
    long landQuantityMilliMu,
    Map<CommodityId, Long> commodityBalances,
    long nonLandDurableAssetQuantity) {

  public HouseholdOwnershipFacts {
    Objects.requireNonNull(household, "HouseholdOwnershipFacts.household 不得为 null");
    if (landQuantityMilliMu < 0L) {
      throw new IllegalArgumentException(
          "HouseholdOwnershipFacts.landQuantityMilliMu 不得为负: " + landQuantityMilliMu);
    }
    if (nonLandDurableAssetQuantity < 0L) {
      throw new IllegalArgumentException(
          "HouseholdOwnershipFacts.nonLandDurableAssetQuantity 不得为负: "
              + nonLandDurableAssetQuantity);
    }
    if (commodityBalances == null) {
      throw new IllegalArgumentException(
          "HouseholdOwnershipFacts.commodityBalances 不得为 null（无库存用空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : commodityBalances.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HouseholdOwnershipFacts.commodityBalances 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdOwnershipFacts.commodityBalances 的数量不得为负: "
                + entry.getKey().value()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    commodityBalances = Collections.unmodifiableMap(copy);
  }

  /**
   * 按冻结语义派生 ownershipClass。
   *
   * @param durableCommodityCapitalIds 计入资本的耐久商品 id；不得为 null（空集用 {@code Set.of()}）
   */
  public OwnershipClass ownershipClass(Set<CommodityId> durableCommodityCapitalIds) {
    Objects.requireNonNull(
        durableCommodityCapitalIds,
        "HouseholdOwnershipFacts.ownershipClass 的 durableCommodityCapitalIds 不得为 null");
    if (landQuantityMilliMu > 0L || nonLandDurableAssetQuantity > 0L) {
      return OwnershipClass.OWNER;
    }
    for (CommodityId capital : durableCommodityCapitalIds) {
      if (commodityBalances.getOrDefault(capital, 0L) > 0L) {
        return OwnershipClass.OWNER;
      }
    }
    return OwnershipClass.PROLETARIAN;
  }
}
