package io.mosire.simos.economy.model;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>W1（2026-10-10）：逐区价表的只读查询面</b> —— "这一格属于哪个市场 / 这一格的价表是哪一张"的<b>唯一拼写点</b>。
 *
 * <p>★★ <b>它替掉了什么</b>：逐格价表时代，每个调用点自己 {@code markets.get(hex)} 就拿到了那一格的价格与计价币。价表改成
 * <b>每区一张</b>（{@code EconomyData.marketTables} 键 = {@link MarketZoneId}）之后，"这一格的价"必须先回答"这一格属于哪个市场"，
 * 而那一问有两套权威（与 {@code EconomyData.marketZones} 是否为空一一对应，见 {@link MarketTable} 的类注）：
 *
 * <pre>
 * 持久形态（marketZones 非空）: 区 = {@link MarketZoneBook#zoneOfHex(EconomyDayView, HexCoord)} ⇒ 价表 = marketTables.get(区 id)
 *                               ★ 该区**每个成员格**都取本区价表（区内任何格取价一致）
 * 派生形态（marketZones 为空）: 价表 = 唯一一张 {@link MarketTable#hexes()} 含该格的条目（id = 派生市场 id）
 * 混合（持久区 + 非区键的派生市场）: 先查区，查不到再查派生市场（与 {@code MarketTopology} 的兜底同一口径）
 * 都不命中                        : 空 —— "这一格没有市场"是**合法状态**，不是故障（不抛、不造默认价）
 * </pre>
 *
 * <p>★ <b>本类不写状态</b>：全是纯函数，不落盘、不进变更集、不认识 codec（{@code IndustryHexKeys} / {@code MarketZoneBook}
 * 的同款形制）。★ 迭代序一律走 {@link #canonicalIds(Map)} 的规范序（{@link MarketZoneId} 裸值升序）：读数与日志要可复现， 不许依赖 {@code
 * LinkedHashMap} 的写入史。
 */
public final class MarketTables {

  private MarketTables() {}

  /** 规范序的 id 表（裸值升序）：迭代序 = 内容的纯函数（I7）。 */
  public static List<MarketZoneId> canonicalIds(Map<MarketZoneId, MarketTable> tables) {
    Objects.requireNonNull(tables, "tables");
    if (tables.isEmpty()) {
      return List.of();
    }
    List<MarketZoneId> ids = new ArrayList<>(tables.keySet());
    ids.sort(Comparator.comparing(MarketZoneId::value));
    return List.copyOf(ids);
  }

  /** ★ 一组市场 id 的规范序（裸值升序）：给"只有 id 集合"的调用方（读数/日志的迭代序是内容的纯函数）。 */
  public static List<MarketZoneId> canonicalIdsOf(Set<MarketZoneId> ids) {
    Objects.requireNonNull(ids, "ids");
    if (ids.isEmpty()) {
      return List.of();
    }
    List<MarketZoneId> ordered = new ArrayList<>(ids);
    ordered.sort(Comparator.comparing(MarketZoneId::value));
    return List.copyOf(ordered);
  }

  /** 规范序的价表（与 {@link #canonicalIds(Map)} 同序）。 */
  public static List<MarketTable> canonical(Map<MarketZoneId, MarketTable> tables) {
    Objects.requireNonNull(tables, "tables");
    List<MarketTable> ordered = new ArrayList<>(tables.size());
    for (MarketZoneId id : canonicalIds(tables)) {
      ordered.add(tables.get(id));
    }
    return List.copyOf(ordered);
  }

  /**
   * ★★ <b>这一格所属的市场 id</b>（持久形态 = 区 id；派生形态 = 派生市场 id）。不属于任何市场 ⇒ 空。
   *
   * <p>★ 它的语义 = "这一格按哪张表报价"，与 {@link #at(EconomyDayView, HexCoord)} 同源（后者是本方法的一次查表）。
   *
   * <p>★★ <b>W1d：区有、表缺 ⇒ 空</b>（缺表 = 该区不交易任何商品）—— 这正是"区成员格"与"有市场的格"在本方法里的分界： 成员格由 {@link
   * MarketZoneBook#zoneOfHex(EconomyDayView, HexCoord)} 回答，而"按哪张表报价"必须先有表。
   */
  public static Optional<MarketZoneId> marketIdAt(EconomyDayView view, HexCoord hex) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(hex, "hex");
    Optional<MarketZone> zone = MarketZoneBook.zoneOfHex(view, hex);
    if (zone.isPresent()) {
      MarketZoneId zoneId = zone.get().zoneId();
      return view.marketTables().containsKey(zoneId) ? Optional.of(zoneId) : Optional.empty();
    }
    for (MarketZoneId id : canonicalIds(view.marketTables())) {
      if (view.marketTables().get(id).hexes().contains(hex)) {
        return Optional.of(id);
      }
    }
    return Optional.empty();
  }

  /** ★ 这一格的价表（{@link #marketIdAt} 的一次查表）；没有市场 ⇒ 空。 */
  public static Optional<MarketTable> at(EconomyDayView view, HexCoord hex) {
    return marketIdAt(view, hex).map(id -> view.marketTables().get(id));
  }

  /**
   * ★★ {@link #at(EconomyDayView, HexCoord)} 的<b>不抛版本</b>（没有市场 ⇒ {@code null}）。
   *
   * <p>★ 为什么给 {@code null} 而不是 {@code Optional}：既有的逐格读取点（{@code markets.get(hex)} 那一族）全部是
   * "拿不到就什么都不做"的形态，改造时逐字保留那个分支（{@code == null} 判空），读起来与改前一样。
   */
  public static MarketTable atOrNull(EconomyDayView view, HexCoord hex) {
    return at(view, hex).orElse(null);
  }

  /** ★ 区 id 取价表（查无 ⇒ 空）：持久形态的直取口，不扫成员格。 */
  public static Optional<MarketTable> ofZone(
      Map<MarketZoneId, MarketTable> tables, MarketZoneId zoneId) {
    Objects.requireNonNull(tables, "tables");
    Objects.requireNonNull(zoneId, "zoneId");
    return Optional.ofNullable(tables.get(zoneId));
  }

  /**
   * ★★ <b>表的冻结拷贝</b>：逐键 copy + 规范序（裸值升序）—— {@code EconomyData} 构造期与各 {@code with*} 的同一形制。
   *
   * <p>★ 为什么在这里归一成规范序（而不是保留写入史）：价表是"重放时逐轮改价"的组件，它的迭代序会直接成为 结算的逐区序与读数序；内容相同的两份状态必须给出同一个序（I7）。
   */
  public static Map<MarketZoneId, MarketTable> frozen(Map<MarketZoneId, MarketTable> tables) {
    Objects.requireNonNull(tables, "tables");
    Map<MarketZoneId, MarketTable> copy = new LinkedHashMap<>();
    for (MarketZoneId id : canonicalIds(tables)) {
      copy.put(id, tables.get(id));
    }
    return Collections.unmodifiableMap(copy);
  }

  /**
   * ★★ <b>全部"有市场的格"（规范序）</b>：持久形态 = 区成员格的并集；派生形态 = 价表成员格的并集。
   *
   * <p>★ 它就是改动前 逐格价表（键 = 格） 的<b>键集</b>：那一族"遍历哪些市场"的调用点 （逐格读数、运费服务面、迁移的格宇宙…）用它逐值接上。
   *
   * <p>★★ <b>W1d（2026-10-11）：缺表的区不贡献成员格</b> —— 遍历的是<b>价表条目</b>，条目在、它才在；某个区没有价表条目 ⇒
   * 那个区<b>不交易任何商品</b>（设计书 §4.2 N-4 的口径），它的成员格因此<b>不是</b>"有市场的格"。★ 这条与 {@link MarketIndex} / {@link
   * MarketTopology} 的"缺表 ⇒ 不装配该区"是同一个判据的三种拼写，取值必须一致 （否则会出现"这个格有市场、却读不出价"的自相矛盾读数）。
   */
  public static Set<HexCoord> allMarketHexes(
      Map<MarketZoneId, MarketTable> tables, Map<MarketZoneId, MarketZone> zones) {
    Objects.requireNonNull(tables, "tables");
    Objects.requireNonNull(zones, "zones");
    List<HexCoord> all = new ArrayList<>();
    for (MarketZoneId id : canonicalIds(tables)) {
      MarketZone zone = zones.get(id);
      if (zone != null) {
        all.addAll(zone.hexes());
      } else {
        all.addAll(tables.get(id).hexes());
      }
    }
    all.sort(HexCoord::compareTo);
    return Collections.unmodifiableSet(new LinkedHashSet<>(all));
  }

  /** 全部成员格（规范序）：派生形态的"有市场的格"= 价表成员格的并集；持久形态 = 空（区成员格在区表里）。 */
  public static Set<HexCoord> memberHexes(Map<MarketZoneId, MarketTable> tables) {
    Set<HexCoord> members = new LinkedHashSet<>();
    List<HexCoord> sorted = new ArrayList<>();
    for (MarketTable table : canonical(tables)) {
      sorted.addAll(table.hexes());
    }
    sorted.sort(HexCoord::compareTo);
    members.addAll(sorted);
    return Collections.unmodifiableSet(members);
  }
}
