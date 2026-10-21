package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>RC8c（D-032 v2 §4.4 / P0-01·P0-02）：单户单 tick 的统一需求账单</b>。
 *
 * <p>★★ <b>它是什么</b>：每户每 tick 恰好一张、只活在 {@link SettlementDayContext} 的<b>瞬态对象</b>。S4 生产日结把
 * 当日基础温饱、生产方式每日额外项、以及本次实际要投入的种子/原料写进同一张账单；S7 市场轮在<b>同一个对象</b>上派生 {@code marketPurchaseNeed}，并据 S4
 * 后的当前库存挂买/卖单。所有读者读同一张账单，不另立 naturalNeeds/生产投入第二套表。
 *
 * <p>★★ <b>不落盘</b>：它不是 {@code EconomyData} 的组件、不进 {@code EconomyChangeSet}、不进 Codec；每 tick 由 {@link
 * SettlementDayProductionPipeline#buildDailyDemandBill(SettlementDayContext)} 重建一份。
 *
 * <p>★★ <b>行键</b>：{@code (commodity, source)} 在一张账单内唯一（{@link #line(CommodityId, DemandSource)} 为主键
 * 访问口）。同商品可以有多行（基础温饱 + 生产投入），但它们必须共享同一份当前库存：{@link #marketGapFor(CommodityId, long, long)} 与 {@link
 * #applyMarketPurchaseNeed(CommodityId, long, long)} 是唯一把库存缺口拆回各行的拼写点。
 *
 * <p>★★ <b>数量不变量</b>：{@code quantityMilli >= 0}、{@code providedFromStockMilli ∈ [0,
 * quantityMilli]}、{@code marketPurchaseNeedMilli >= 0}、{@code purchasedFromMarketMilli >= 0}、{@code
 * unmetMilli >= 0}。S4 已提供量写在这里 后，S7 不得再把同一份量挂成买盘（{@link #marketGapFor(CommodityId, long, long)} 对
 * {@code PRODUCTION_INPUT} 先扣 {@code providedFromStockMilli}）。
 */
final class DailyDemandBill {

  /** 需求来源（v2 §4.4 的四档；本批只产生前三档）。 */
  enum DemandSource {
    BASE_SUBSISTENCE,
    PRODUCTION_DAILY_EXTRA,
    PRODUCTION_INPUT,
    OTHER
  }

  /** 一行需求；字段可变是刻意的（S4 填 provided、S7 填 marketPurchaseNeed、轮末填成交/未满足）。 */
  static final class Line {
    private final CommodityId commodity;
    private final DemandSource source;
    private long quantityMilli;
    private long providedFromStockMilli;
    private long marketPurchaseNeedMilli;
    private long purchasedFromMarketMilli;
    private long unmetMilli;

    private Line(CommodityId commodity, DemandSource source) {
      this.commodity = Objects.requireNonNull(commodity, "DailyDemandBill.Line.commodity");
      this.source = Objects.requireNonNull(source, "DailyDemandBill.Line.source");
    }

    CommodityId commodity() {
      return commodity;
    }

    DemandSource source() {
      return source;
    }

    long quantityMilli() {
      return quantityMilli;
    }

    long providedFromStockMilli() {
      return providedFromStockMilli;
    }

    long marketPurchaseNeedMilli() {
      return marketPurchaseNeedMilli;
    }

    long purchasedFromMarketMilli() {
      return purchasedFromMarketMilli;
    }

    long unmetMilli() {
      return unmetMilli;
    }

    private void addQuantity(long amountMilli) {
      if (amountMilli < 0L) {
        throw new IllegalArgumentException("DailyDemandBill 行的 quantity 不得为负: " + amountMilli);
      }
      quantityMilli = Math.addExact(quantityMilli, amountMilli);
    }

    private void addProvidedFromStock(long amountMilli) {
      if (amountMilli < 0L) {
        throw new IllegalArgumentException("DailyDemandBill 行的 provided 不得为负: " + amountMilli);
      }
      // ★ 已提供量不得超过本行毛需求；超出即调用方归因错误（数量口径不是"库存余额"）。
      long next = Math.addExact(providedFromStockMilli, amountMilli);
      if (next > quantityMilli) {
        throw new IllegalStateException(
            "DailyDemandBill 行 providedFromStock 超出 quantity: commodity="
                + commodity.value()
                + " source="
                + source
                + " quantity="
                + quantityMilli
                + " provided="
                + next);
      }
      providedFromStockMilli = next;
    }

    private long marketNeedFor(long restockDays) {
      return switch (source) {
        case BASE_SUBSISTENCE, PRODUCTION_DAILY_EXTRA ->
            // ★ 基础温饱/方式额外项是"日口径"，市场日要覆盖到下一市场轮（当前 5 个生产 tick）。
            Math.multiplyExact(quantityMilli, restockDays);
        case PRODUCTION_INPUT, OTHER ->
            // ★ 生产投入是"本周期当前一次性缺口"：S4 已提供的部分不得再次挂买，也不乘 5。
            //   OTHER 同口径 = max(0, quantity − provided)。
            Math.max(0L, quantityMilli - providedFromStockMilli);
      };
    }

    private long remainingMarketNeed() {
      return Math.max(0L, marketPurchaseNeedMilli - purchasedFromMarketMilli);
    }

    private void setMarketPurchaseNeed(long amountMilli) {
      if (amountMilli < 0L) {
        throw new IllegalArgumentException(
            "DailyDemandBill 行的 marketPurchaseNeed 不得为负: " + amountMilli);
      }
      marketPurchaseNeedMilli = amountMilli;
    }

    private long takeMarketPurchase(long requestedMilli) {
      long take = Math.min(Math.max(0L, requestedMilli), remainingMarketNeed());
      purchasedFromMarketMilli = Math.addExact(purchasedFromMarketMilli, take);
      return take;
    }

    private long takeUnmet(long requestedMilli) {
      long take = Math.min(Math.max(0L, requestedMilli), remainingMarketNeed());
      unmetMilli = Math.addExact(unmetMilli, take);
      return take;
    }
  }

  private final long day;
  private final HouseholdId household;
  private final LinkedHashMap<String, Line> lines = new LinkedHashMap<>();

  private DailyDemandBill(long day, HouseholdId household) {
    if (day < 0L) {
      throw new IllegalArgumentException("DailyDemandBill.day 不得为负: " + day);
    }
    this.day = day;
    this.household = Objects.requireNonNull(household, "DailyDemandBill.household");
  }

  /** 建一张空账单；同户同 tick 的唯一副本由调用方（{@link SettlementDayContext}）保证。 */
  static DailyDemandBill create(long day, HouseholdId household) {
    return new DailyDemandBill(day, household);
  }

  long day() {
    return day;
  }

  HouseholdId household() {
    return household;
  }

  /** 取一行；没有就返回 {@code null}（不伪造 0 行）。 */
  Line line(CommodityId commodity, DemandSource source) {
    return lines.get(key(commodity, source));
  }

  /** 保序取一行，没有就按 {@link DemandSource} 枚举序追加（同 {@code (commodity, source)} 唯一）。 */
  Line ensureLine(CommodityId commodity, DemandSource source) {
    return lines.computeIfAbsent(key(commodity, source), ignored -> new Line(commodity, source));
  }

  /** 追加毛需求（同键合并）。 */
  void addQuantity(CommodityId commodity, DemandSource source, long amountMilli) {
    if (amountMilli == 0L) {
      return;
    }
    ensureLine(commodity, source).addQuantity(amountMilli);
  }

  /** 追加 S4 已从库存实际提供量（同键合并；不得超过该行毛需求）。 */
  void addProvidedFromStock(CommodityId commodity, DemandSource source, long amountMilli) {
    if (amountMilli == 0L) {
      return;
    }
    ensureLine(commodity, source).addProvidedFromStock(amountMilli);
  }

  /** 全部行（保序、不可变视图）。 */
  List<Line> lines() {
    return Collections.unmodifiableList(new ArrayList<>(lines.values()));
  }

  /** 本账单涉及的（保序去重）商品。 */
  Set<CommodityId> commodities() {
    Set<CommodityId> commodities = new LinkedHashSet<>();
    for (Line line : lines.values()) {
      commodities.add(line.commodity());
    }
    return Collections.unmodifiableSet(commodities);
  }

  /** 某商品的保序行（同商品多来源）。 */
  List<Line> linesOf(CommodityId commodity) {
    List<Line> matched = new ArrayList<>();
    for (Line line : lines.values()) {
      if (line.commodity().equals(commodity)) {
        matched.add(line);
      }
    }
    return matched;
  }

  /**
   * ★★ <b>S7 的唯一市场缺口口径</b>：{@code max(0, Σ本商品各行市场口径需求 − 当前库存)}。
   *
   * <ul>
   *   <li>BASE_SUBSISTENCE / PRODUCTION_DAILY_EXTRA：{@code quantity × restockDays}；
   *   <li>PRODUCTION_INPUT：{@code max(0, quantity − providedFromStock)}（已提供的不再挂买、也不乘 5）。
   * </ul>
   *
   * <p>同一商品的多行共享同一份当前库存：库存只在这里被减一次，避免"两行各减一遍库存"把需求算小。
   */
  long marketGapFor(CommodityId commodity, long restockDays, long currentGoodsMilli) {
    if (restockDays <= 0L) {
      throw new IllegalArgumentException("DailyDemandBill 的市场补货窗口必须为正: " + restockDays);
    }
    if (currentGoodsMilli < 0L) {
      throw new IllegalArgumentException("DailyDemandBill 的当前库存不得为负: " + currentGoodsMilli);
    }
    long need = 0L;
    for (Line line : linesOf(commodity)) {
      need = Math.addExact(need, line.marketNeedFor(restockDays));
    }
    return Math.max(0L, need - currentGoodsMilli);
  }

  /**
   * ★★ <b>S7 的卖侧保留口径</b>：{@code Σ本商品各行市场口径需求}（当前库存由调用方再减一次）。
   *
   * <p>实际盈余 = {@code max(0, currentGoods − reserve)}；调用方不得把未来产出当现货。
   */
  long marketReserveFor(CommodityId commodity, long restockDays) {
    if (restockDays <= 0L) {
      throw new IllegalArgumentException("DailyDemandBill 的市场补货窗口必须为正: " + restockDays);
    }
    long need = 0L;
    for (Line line : linesOf(commodity)) {
      need = Math.addExact(need, line.marketNeedFor(restockDays));
    }
    return need;
  }

  /**
   * ★★ <b>把市场缺口按行拆回</b>（同商品多行共享库存后的唯一落点）：按账单行序分配，写 {@code marketPurchaseNeed}。
   *
   * <p>行序 = S4 的写入序（BASE_SUBSISTENCE 先，随后是各生产单元额外项/投入）；分配只影响"哪一行记录了多少购买需要"，不改变 本商品的总买单数量。
   */
  void applyMarketPurchaseNeed(CommodityId commodity, long gapMilli, long restockDays) {
    long remaining = Math.max(0L, gapMilli);
    for (Line line : linesOf(commodity)) {
      long lineNeed = line.marketNeedFor(restockDays);
      long take = Math.min(remaining, lineNeed);
      line.setMarketPurchaseNeed(take);
      remaining -= take;
    }
  }

  /** 轮末：把本商品成交的买量按行分配到 {@code purchasedFromMarket}（超出各行市场需要的部分忽略）。 */
  void recordMarketPurchase(CommodityId commodity, long quantityMilli) {
    long remaining = Math.max(0L, quantityMilli);
    for (Line line : linesOf(commodity)) {
      remaining -= line.takeMarketPurchase(remaining);
      if (remaining <= 0L) {
        return;
      }
    }
  }

  /** 轮末：把本商品买方未成交剩余按行分配到 {@code unmet}（超出各行市场需要的部分忽略）。 */
  void recordMarketUnmet(CommodityId commodity, long quantityMilli) {
    long remaining = Math.max(0L, quantityMilli);
    for (Line line : linesOf(commodity)) {
      remaining -= line.takeUnmet(remaining);
      if (remaining <= 0L) {
        return;
      }
    }
  }

  private static String key(CommodityId commodity, DemandSource source) {
    Objects.requireNonNull(commodity, "DailyDemandBill 商品不得为 null");
    Objects.requireNonNull(source, "DailyDemandBill 来源不得为 null");
    return commodity.value() + '\u0000' + source.name();
  }
}
