package io.mosire.simos.economy.api.classes;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★★★ <b>D-032 C1：计入 ownershipClass 的耐久商品资本词表</b>（唯一接入点）。
 *
 * <p>★★ <b>为什么需要它</b>：运输马匹（{@code transport_horse}）是<strong>普通商品库存</strong>，不是 {@code
 * OwnershipStake}；但它属于生产资料，必须参与 owner/proletarian 派生。这里把“哪些商品库存按资本计”收成 唯一词表，避免各处把“所有库存 &gt;
 * 0”误当有产者。
 *
 * <p>★ <b>C5 接入点</b>：C5 定义 {@code transport_horse} 商品时，只在此集合登记一次；C1 不提前发明商品 id、不改 {@code
 * EconomyVocabulary}/配方/价格锚。
 *
 * <p>★ 集合保序、不可变、无 null；空集 = 当前世界还没有登记任何耐久商品资本（旧世界行为）。
 */
public final class OwnershipCapitalVocabulary {

  /**
   * 计入 ownershipClass 的耐久商品资本 id 集合。
   *
   * <p>★★ C5a 起<b>只登记</b> {@link EconomyVocabulary#TRANSPORT_HORSE_COMMODITY_ID 运输马匹}：它是普通商品库存，
   * 但属于生产资料，故家户库存 &gt; 0 时参与 OWNER 派生。★ 其他普通商品（粮/布/纤维/工具/铁/木）库存<b>不</b>计入 —— 这里就是"哪些商品库存按资本计"的唯一词表。
   */
  private static final Set<CommodityId> DURABLE_COMMODITY_CAPITAL_IDS =
      Set.of(new CommodityId(EconomyVocabulary.TRANSPORT_HORSE_COMMODITY_ID));

  private OwnershipCapitalVocabulary() {}

  /** 只读、保序、不可变的耐久商品资本集合。 */
  public static Set<CommodityId> durableCommodityCapitalIds() {
    return DURABLE_COMMODITY_CAPITAL_IDS;
  }

  /** 用一个显式集合构造只读副本（测试/装配层可注入，不改变生产默认词表）。 */
  public static Set<CommodityId> freeze(Iterable<CommodityId> ids) {
    if (ids == null) {
      throw new IllegalArgumentException(
          "OwnershipCapitalVocabulary.freeze 的 ids 不得为 null（空集用 Set.of()）");
    }
    LinkedHashSet<CommodityId> copy = new LinkedHashSet<>();
    for (CommodityId id : ids) {
      if (id == null) {
        throw new IllegalArgumentException("OwnershipCapitalVocabulary.freeze 不得含 null");
      }
      copy.add(id);
    }
    return Collections.unmodifiableSet(copy);
  }
}
