package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>W1（2026-10-10）：逐格取价表的只读解析器</b> —— "这一格按哪张表报价"的<b>唯一拼写点</b>（economy 内部用）。
 *
 * <p>★★ <b>它替掉了什么</b>：逐格价表时代，结算器内部一律持 逐格价表（键 = 格），随手 {@code markets.get(hex)} 就拿到了那一格的价格与计价币。W1
 * 把价表改成<b>每区一张</b>（{@code EconomyData.marketTables} 键 = 市场 id）之后，"这一格的价"必须先回答"这一格属于哪个市场"：
 *
 * <pre>
 * 持久形态（marketZones 非空）: 区 = 成员格含该格的那个 MarketZone ⇒ 表 = tables.get(区 id)
 *                              ★ 区没有价表条目 ⇒ null（缺表 = 该区不交易任何商品，W1d）
 * 派生形态（marketZones 为空）: 表 = 成员格含该格的那个价表条目（id = 派生市场 id）
 * 都不命中                     : null —— "这一格没有市场"是**合法状态**，不是故障（不抛、不造默认价）
 * </pre>
 *
 * <p>★★ <b>为什么要有这么一个类型、而不是把 {@code Map<MarketZoneId, MarketTable>} 继续往下传</b>：{@code Map.get} 收
 * {@code Object} ⇒ 传 hex 进去<b>编译期不报错、运行期恒返回 null</b>（静默丢价，本仓最贵的那类 bug 形态）。本类的 {@link #at(HexCoord)}
 * 与 {@link #ofMarket(MarketZoneId)} 把两种键分开，改错会当场编译失败。
 *
 * <p>★ <b>两种构造形态</b>：{@link #of(EconomyDayView)} 持<b>当日视图</b>（逐日写入换表 ⇒ 必须现场解析，不拍快照）、 {@link
 * #of(Map, Map)} 持两张不可变表（段级/命令面调用方）。两条都只读，不落盘、不进变更集。
 */
final class MarketIndex {

  /** 当日视图（活解析；{@code null} = 持不可变快照）。 */
  private final EconomyDayView view;

  /** 不可变快照（{@code view == null} 时唯一来源）。 */
  private final Map<MarketZoneId, MarketTable> tables;

  /** 不可变区表（{@code view == null} 时唯一来源）。 */
  private final Map<MarketZoneId, MarketZone> marketZones;

  private MarketIndex(
      EconomyDayView view,
      Map<MarketZoneId, MarketTable> tables,
      Map<MarketZoneId, MarketZone> marketZones) {
    this.view = view;
    this.tables = tables;
    this.marketZones = marketZones;
  }

  /**
   * ★★ <b>当日视图形态</b>（日循环内部）：每次查询现场问视图 ⇒ 段内换过表（自适应改价）之后读到的是新表。
   *
   * <p>★ 与改动前的 {@code session.sheet().marketTables()} 工作副本引用同一条"当刻值"口径（{@code WorkingDayView} 的类注）。
   */
  static MarketIndex of(EconomyDayView view) {
    Objects.requireNonNull(view, "view（当日视图）");
    return new MarketIndex(view, null, null);
  }

  /**
   * ★ <b>两张表的形态</b>（段级/命令面/日循环里"手里已经是工作副本"的调用方）：<b>持引用</b>，不拷贝 ——
   * 传入的通常是**会话工作副本**，它在段内会被就地改写（自适应改价），拷一份就会读到过期价。
   */
  static MarketIndex of(
      Map<MarketZoneId, MarketTable> tables, Map<MarketZoneId, MarketZone> marketZones) {
    Objects.requireNonNull(tables, "tables");
    Objects.requireNonNull(marketZones, "marketZones");
    return new MarketIndex(null, tables, marketZones);
  }

  /** 全部价表（键 = 市场 id；规范序：{@link MarketTables#canonicalIds}）。 */
  Map<MarketZoneId, MarketTable> tables() {
    return view == null ? tables : view.marketTables();
  }

  /** 市场区表（持久形态的成员格权威）。 */
  Map<MarketZoneId, MarketZone> marketZones() {
    return view == null ? marketZones : view.marketZones();
  }

  /** 世上有没有任何市场（改动前 {@code markets.isEmpty()} 的替身）。 */
  boolean isEmpty() {
    return tables().isEmpty();
  }

  /** ★ 市场 id 取表（查无 ⇒ {@code null}）。 */
  MarketTable ofMarket(MarketZoneId marketId) {
    Objects.requireNonNull(marketId, "marketId");
    return tables().get(marketId);
  }

  /** ★ <b>本区的表</b>（区 id 与拓扑节点 id 是同一个 id 空间）。★★ W1d：区没有价表条目 ⇒ {@code null}（缺表 = 该区不交易）。 */
  MarketTable ofRegion(MarketRegion region) {
    Objects.requireNonNull(region, "region");
    return tables().get(new MarketZoneId(region.node().nodeId()));
  }

  /**
   * ★★ <b>本格的表</b> —— 逐值替掉改动前的 {@code markets.get(hex)}（区内任何格同一张；不在任何市场里 ⇒ {@code null}）。
   *
   * <p>★ 解析次序：先查<b>持久区</b>的成员格（区表是成员格的唯一权威），再查<b>派生市场</b>的成员格（价表条目自带格集）—— 与 {@code MarketTables.at}
   * 的次序逐字相同（唯一拼写点：那里给 {@code EconomyDayView} 调用方用，这里给 "只有两张表"的结算器用）。
   *
   * <p>★★ <b>W1d：区有、表缺 ⇒ {@code null}</b>（与 {@link MarketTables#at} 同一条口径：缺表 = 该区不交易任何商品，
   * 故"这一格按哪张表报价"没有答案）。★ 这里<b>绝不</b>回落到"该格所在的派生市场"—— 区一旦覆盖该格，它的市场就由该区 给定（I22），回落会造出第二个权威。
   */
  MarketTable at(HexCoord hex) {
    if (hex == null) {
      return null;
    }
    Map<MarketZoneId, MarketTable> current = tables();
    Map<MarketZoneId, MarketZone> currentZones = marketZones();
    for (MarketZone zone : currentZones.values()) {
      if (zone.hexes().contains(hex)) {
        return current.get(zone.zoneId()); // ★ 缺表 ⇒ null（W1d：不交易，不回落到派生市场）
      }
    }
    for (MarketZoneId marketId : MarketTables.canonicalIds(current)) {
      if (currentZones.containsKey(marketId)) {
        continue;
      }
      MarketTable table = current.get(marketId);
      if (table != null && table.hexes().contains(hex)) {
        return table;
      }
    }
    return null;
  }

  /** ★ 本格所属市场的计价币（没有市场 ⇒ {@code null}）：改动前 {@code markets.get(hex).numeraire()} 的替身。 */
  io.mosire.simos.economy.api.id.CurrencyId currencyAt(HexCoord hex) {
    MarketTable table = at(hex);
    return table == null ? null : table.numeraire();
  }
}
