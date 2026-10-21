package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.classes.HouseholdOwnershipFacts;
import io.mosire.simos.economy.api.classes.OwnershipCapitalVocabulary;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★★ <b>D-032 C1：家户 ownershipClass 的唯一派生读口</b>（取代旧 {@code ClassNatureBook}）。
 *
 * <p>★★ <b>只读、不持久</b>：本类不写 {@code EconomyData}/ChangeSet/Codec/快照；每次调用从<strong>同一份当刻活工作副本</strong>
 * 现算：
 *
 * <pre>
 * OWNER if
 *   Σ LAND(household, anyHex).quantityMilliMu &gt; 0
 *   OR 已登记耐久商品资本（{@link OwnershipCapitalVocabulary}）的家户库存 &gt; 0
 *   OR economy assetShares 中该户 owner 的非 LAND 正数量份额 &gt; 0
 * else PROLETARIAN
 * </pre>
 *
 * <p>★ <b>边界</b>：普通商品库存、现金、operator 但非 owner 的份额都不计入；本派生不看 productionMode/currentPosition。 LAND 只读
 * {@code LandComponentLedger} 的 Map 派生瞬态工作副本，不读旧持久 landHoldings。
 *
 * <p>★ <b>S7-M2 中间态</b>：调用方在 working copy 里每笔资产转移后调用本类，立即得到当刻 class；不缓存、不读 S7 前旧快照。 需要给 S7-B
 * 用的市场买入判定由 {@link #snapshot(SettlementDayContext)} 在 S7-M2 结束后一次性拍下。
 */
final class OwnershipClassBook {

  private OwnershipClassBook() {}

  /** 当刻活工作副本 → ownershipClass。 */
  static OwnershipClass classOf(SettlementDayContext ctx, HouseholdId household) {
    return factsOf(ctx, household)
        .ownershipClass(OwnershipCapitalVocabulary.durableCommodityCapitalIds());
  }

  /**
   * 当刻跨切片事实 → ownershipClass。用于 merge 等没有完整 {@link SettlementDayContext} 的只读计划路径。
   *
   * <p>调用方必须传<strong>同一份当刻活工作副本</strong>的 land / goods / assetShares；执行顺序/中间态与持久状态无关。
   */
  static OwnershipClass classOf(
      Map<LandHoldingKey, LandHolding> landHoldings,
      Map<CommodityId, Long> commodityBalances,
      Map<AssetShareId, OwnershipStake> assetShares,
      HouseholdId household) {
    return factsOf(landHoldings, commodityBalances, assetShares, household)
        .ownershipClass(OwnershipCapitalVocabulary.durableCommodityCapitalIds());
  }

  /** 当刻跨切片事实的可审计载体（不持久；供 app 装配或测试注入同一把尺）。 */
  static HouseholdOwnershipFacts factsOf(SettlementDayContext ctx, HouseholdId household) {
    Objects.requireNonNull(ctx, "OwnershipClassBook.factsOf 的 ctx 不得为 null");
    Objects.requireNonNull(household, "OwnershipClassBook.factsOf 的 household 不得为 null");
    if (ctx.householdGoods == null) {
      throw new IllegalStateException(
          "ownership class lookup 缺少活商品库存工作副本: ctx.householdGoods == null, household="
              + household.value());
    }
    if (ctx.assetShares == null) {
      throw new IllegalStateException(
          "ownership class lookup 缺少活 assetShares 工作副本: ctx.assetShares == null, household="
              + household.value());
    }
    Map<CommodityId, Long> goods = ctx.householdGoods.getOrDefault(household, Map.of());
    return factsOf(ctx.session.sheet().landHoldingsOrBase(), goods, ctx.assetShares, household);
  }

  static HouseholdOwnershipFacts factsOf(
      Map<LandHoldingKey, LandHolding> landHoldings,
      Map<CommodityId, Long> commodityBalances,
      Map<AssetShareId, OwnershipStake> assetShares,
      HouseholdId household) {
    Objects.requireNonNull(landHoldings, "OwnershipClassBook.factsOf 的 landHoldings 不得为 null");
    Objects.requireNonNull(
        commodityBalances, "OwnershipClassBook.factsOf 的 commodityBalances 不得为 null");
    Objects.requireNonNull(assetShares, "OwnershipClassBook.factsOf 的 assetShares 不得为 null");
    Objects.requireNonNull(household, "OwnershipClassBook.factsOf 的 household 不得为 null");

    long land = 0L;
    for (LandHolding holding : landHoldings.values()) {
      if (holding != null && holding.owner().equals(household)) {
        land = Math.addExact(land, holding.quantityMilliMu());
      }
    }
    long nonLandDurable = 0L;
    var actor = HouseholdActors.of(household);
    for (OwnershipStake share : assetShares.values()) {
      if (share != null
          && share.asset() != AssetKind.LAND
          && share.quantity() > 0L
          && share.owner().equals(actor)) {
        nonLandDurable = Math.addExact(nonLandDurable, share.quantity());
      }
    }
    return new HouseholdOwnershipFacts(household, land, commodityBalances, nonLandDurable);
  }

  /** S7-M2 完成后、S7-A 前拍一次：所有活家户的当刻 ownershipClass（稳定 HouseholdId 序；不可变）。 */
  static Map<HouseholdId, OwnershipClass> snapshot(SettlementDayContext ctx) {
    Objects.requireNonNull(ctx, "OwnershipClassBook.snapshot 的 ctx 不得为 null");
    TreeMap<HouseholdId, OwnershipClass> ordered =
        new TreeMap<>(java.util.Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : ctx.householdEconomies.keySet()) {
      ordered.put(household, classOf(ctx, household));
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
  }

  /**
   * ★★ D-032 C8：S7-M2 完成后、S7-A 前拍一次“买方当前 hex”快照。
   *
   * <p>它就是当刻 {@code householdEconomies[id].view().hex()}，与 {@link #snapshot} 同一时点、同一稳定序；
   * 马匹向上跃迁只能读本快照，不得取市场格、卖方格或 LAND hex。
   */
  static Map<HouseholdId, HexCoord> snapshotHomeHex(SettlementDayContext ctx) {
    Objects.requireNonNull(ctx, "OwnershipClassBook.snapshotHomeHex 的 ctx 不得为 null");
    TreeMap<HouseholdId, HexCoord> ordered =
        new TreeMap<>(java.util.Comparator.comparing(HouseholdId::value));
    for (Map.Entry<HouseholdId, io.mosire.simos.economy.model.HouseholdEconomy> entry :
        ctx.householdEconomies.entrySet()) {
      if (entry.getValue() == null) {
        throw new IllegalStateException(
            "ownership homeHex snapshot 遇到 null 家户行: household=" + entry.getKey().value());
      }
      ordered.put(entry.getKey(), entry.getValue().view().hex());
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
  }

  /**
   * 只读派生：给定家户当刻事实与显式资本商品集合（C5 接入的单一入口）。
   *
   * <p>保留显式重载是为了让装配/测试能用同一把尺而不改生产默认词表；生产路径用 {@link
   * OwnershipCapitalVocabulary#durableCommodityCapitalIds()}。
   */
  static OwnershipClass classOf(HouseholdOwnershipFacts facts, Set<CommodityId> durableIds) {
    Objects.requireNonNull(facts, "OwnershipClassBook.classOf 的 facts 不得为 null");
    return facts.ownershipClass(durableIds);
  }
}
