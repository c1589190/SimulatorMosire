package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>一个区域市场</b>（M2.3）：由 {@link #node()}（集散节点 + 报价币种 + 接收工具）与 {@link #members()} （成员格，含节点格）组成。
 *
 * <p>★★ <b>它是纯派生件</b>：{@code MarketTopology} 由 {@code GameMap}/{@code SocialCity} 的城市与半径现算成员格， 一个
 * hex 归属**最近**的节点（同距按节点 id 字典序，构造性可复现）。★ 不新增状态组件 —— 区域是"城市的腹地"， 城市的权威不在 economy。
 *
 * <p>★ <b>成员格可以没有市场表条目</b>：那时它只是"在这座城的辐射范围内"，结算对它没有价格可用 ⇒ 不产生订单。 这与 {@code Market} 的"缺格 =
 * 该格没有市场"是同一条既有口径。
 *
 * <p>★ {@code members} <b>保序不可变</b>（{@code LinkedHashMap} 的键序 → 插入序），迭代序因此是内容的纯函数：
 * 同一份城市集与地图必然给出同一份成员表（读数与报告才可复现）。
 *
 * @param node 集散节点；不得为 null
 * @param members 成员格（含节点格）；不得为 null、不得为空
 */
public record MarketRegion(MarketNode node, Set<HexCoord> members) {

  public MarketRegion {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(members, "members");
    if (members.isEmpty()) {
      throw new IllegalArgumentException("MarketRegion.members 不得为空（一个区至少含集散节点格）");
    }
    Set<HexCoord> copy = new LinkedHashSet<>(members);
    if (copy.contains(null)) {
      throw new IllegalArgumentException("MarketRegion.members 不得含 null");
    }
    members = Collections.unmodifiableSet(copy); // ★ 冻在赋值处（SpotBugs 只认它看得见的包装）
  }

  /** 集散节点格（跨区路线的终点就是它；区内成交的参考价也取这一格的市场）。 */
  public HexCoord anchor() {
    return node.anchor();
  }

  /** 市场半径（hex）。 */
  public int radiusHex() {
    return node.radiusHex();
  }

  /** 本区报价币种（区内所有价格都按它计）。 */
  public CurrencyId numeraire() {
    return node.numeraire();
  }

  /** 本区卖方接收的货币工具。 */
  public InstrumentId receiveWith() {
    return node.receiveWith();
  }
}
