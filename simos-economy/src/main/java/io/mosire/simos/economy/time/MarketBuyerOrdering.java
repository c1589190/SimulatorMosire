package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P3b：市场买方纯库存财富序的唯一拼写点</b>。
 *
 * <pre>
 * rank = PureInventoryWealthBook.snapshot（M2 后、撮合前冻结）的
 *        wealth(household) 降序 → HouseholdId 升序
 * </pre>
 *
 * <p>★ <b>同一轮商品买槽与土地买单共用这一份 rank</b>：两者都从同一个 {@link PureInventoryWealthBook.Snapshot}
 * 实例构造，禁止各自现算第二份财富（两份快照会在商品成交/冻结前后漂开，等于让排序自指）。
 *
 * <p>★ <b>不在本类扣债务</b>：纯库存财富 = 钱 + 商品库存 + LAND，工具/作坊/机器/船不计入。{@link SettlementOrderBook}
 * 的净值/债务口径继续只服务 ClassSlide，不使用本 rank。
 *
 * <p>★ <b>缺省中性</b>：没有快照（{@link #empty()}）⇒ {@link #rankOf(HouseholdId)} 恒为 {@link
 * SettlementOrderBook#UNKNOWN_RANK}，调用方退回旧的 {@code ctx.ordering.householdRankOf(...)}。
 */
final class MarketBuyerOrdering {

  private final PureInventoryWealthBook.Snapshot snapshot;
  private final Map<HouseholdId, Integer> rankByHousehold;

  private MarketBuyerOrdering(
      PureInventoryWealthBook.Snapshot snapshot, Map<HouseholdId, Integer> rankByHousehold) {
    this.snapshot = snapshot;
    this.rankByHousehold = rankByHousehold;
  }

  /** 本轮没有纯财富快照（旧夹具/读口）⇒ 调用方退回净值序。 */
  static MarketBuyerOrdering empty() {
    return new MarketBuyerOrdering(null, Map.of());
  }

  /**
   * 用一份已冻结的纯库存财富快照构造买方序。
   *
   * <p>★ 输入快照必须是本市场轮<b>撮合前</b>取的那一份；本类只按内容的纯函数排序，不读取任何当轮成交/余额。
   */
  static MarketBuyerOrdering of(PureInventoryWealthBook.Snapshot snapshot) {
    Objects.requireNonNull(snapshot, "MarketBuyerOrdering.of 的 snapshot 不得为 null（没有用 empty()）");
    List<HouseholdId> ordered = new ArrayList<>(snapshot.wealthByHousehold().keySet());
    ordered.sort(
        Comparator.comparingLong((HouseholdId household) -> snapshot.wealthOf(household))
            .reversed()
            .thenComparing(HouseholdId::value));
    LinkedHashMap<HouseholdId, Integer> ranks = new LinkedHashMap<>();
    for (int i = 0; i < ordered.size(); i++) {
      ranks.put(ordered.get(i), i);
    }
    return new MarketBuyerOrdering(snapshot, Collections.unmodifiableMap(ranks));
  }

  /** 本 rank 是否来自真实快照（false ⇒ 调用方应退回旧的净值序）。 */
  boolean isActive() {
    return snapshot != null && !rankByHousehold.isEmpty();
  }

  /** 家户名次（0 = 最靠前）；不在快照里 ⇒ {@link SettlementOrderBook#UNKNOWN_RANK}（排最后）。 */
  int rankOf(HouseholdId household) {
    Integer rank = rankByHousehold.get(household);
    return rank == null ? SettlementOrderBook.UNKNOWN_RANK : rank;
  }

  /** 本轮参与纯财富排序的家户数。 */
  int rankedHouseholds() {
    return rankByHousehold.size();
  }

  /** 与 {@link #of(PureInventoryWealthBook.Snapshot)} 同源的那一份快照；无快照 ⇒ null。 */
  PureInventoryWealthBook.Snapshot snapshot() {
    return snapshot;
  }

  /** TRACE/DEBUG 审计行：{@code rank:household=wealth}（保序）。 */
  List<String> describe() {
    if (snapshot == null) {
      return List.of();
    }
    List<String> lines = new ArrayList<>(rankByHousehold.size());
    for (Map.Entry<HouseholdId, Integer> entry : rankByHousehold.entrySet()) {
      lines.add(
          entry.getValue()
              + ":"
              + entry.getKey().value()
              + "="
              + snapshot.wealthOf(entry.getKey()));
    }
    return List.copyOf(lines);
  }
}
