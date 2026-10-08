package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>§4.3.5 的"同一份资源快照"</b>：一个家户在一次结算日开始时的只读资源视图。
 *
 * <p>排序用到的所有投入都在这里冻结：<b>家户价目表（保留价的来源 = 本格的 {@link Market} + 本户 {@code expectedNeedMilli}）、
 * 可动库存、可动货币、本 tick 时间预算</b>。活动执行<b>不得</b>回头重算它、也<b>不得</b>因"先执行者改了价格"而重排 —— 那会让同一存档两跑不同（{@link
 * ActivitySelector} 的类注）。
 *
 * <p>★ <b>它不是状态、不进快照树、不落盘</b>：逐日构建、只在本日的排序/执行之间传递（{@code EconomyData} 的组件表零命中）。 ★ <b>量纲</b>：{@code
 * goods}/{@code money} 一律<b>毫</b>单位（毫商品 / 毫钱）；{@code laborMilli} = 毫小时。
 *
 * @param day 世界日（日志与报告用；不参与任何算式）
 * @param household 家户身份
 * @param market 该户<b>居住格</b>的价表；{@code null} = 该户没有市场（活动必须具名跳过，不猜价 —— N7）
 * @param goods 可动库存（已扣冻结；键 = 商品；缺键 = 0；保序不可变）
 * @param money 可动货币（已扣冻结；键 = 币种；缺键 = 0；保序不可变）
 * @param laborMilli 本 tick 时间预算（毫小时；≥ 0）
 */
record HouseholdResourceSnapshot(
    long day,
    HouseholdId household,
    Market market,
    Map<CommodityId, Long> goods,
    Map<CurrencyId, Long> money,
    long laborMilli) {

  HouseholdResourceSnapshot {
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdResourceSnapshot.day 不得为负: " + day);
    }
    Objects.requireNonNull(household, "HouseholdResourceSnapshot.household 不得为 null");
    Objects.requireNonNull(goods, "HouseholdResourceSnapshot.goods 不得为 null（无库存给空表）");
    Objects.requireNonNull(money, "HouseholdResourceSnapshot.money 不得为 null（无货币给空表）");
    if (laborMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdResourceSnapshot.laborMilli 不得为负: " + laborMilli);
    }
    goods = freezeGoods(goods);
    money = freezeMoney(money);
  }

  /** 某商品的可动量（缺键 = 0；负值由构造期拒，故返回值恒 ≥ 0）。 */
  long goodsOf(CommodityId commodity) {
    Objects.requireNonNull(commodity, "goodsOf 的商品不得为 null");
    return goods.getOrDefault(commodity, 0L);
  }

  /** 某币种的可动量（缺键 = 0）。 */
  long moneyOf(CurrencyId currency) {
    Objects.requireNonNull(currency, "moneyOf 的币种不得为 null");
    return money.getOrDefault(currency, 0L);
  }

  /** 本格价表里"有定价"的商品（含明确 0 价），按商品 id 升序 —— 遍历序是内容的纯函数（禁 HashMap 迭代序）。 */
  List<CommodityId> pricedCommodities() {
    if (market == null) {
      return List.of();
    }
    List<CommodityId> ordered = new ArrayList<>(market.prices().keySet());
    ordered.sort(Comparator.comparing(CommodityId::value));
    return ordered;
  }

  private static Map<CommodityId, Long> freezeGoods(Map<CommodityId, Long> source) {
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HouseholdResourceSnapshot.goods 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdResourceSnapshot.goods 不得为负: " + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 保序冻结（不用 Map.copyOf：迭代序不是内容的纯函数）
  }

  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> source) {
    LinkedHashMap<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HouseholdResourceSnapshot.money 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdResourceSnapshot.money 不得为负: " + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
