package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>一个区域市场的集散节点</b>（M2.3）：<b>城市节点 + 市场半径 + 报价币种 + 接收工具</b>。
 *
 * <p>★★ <b>它是派生件、不是持久状态</b>（M2.0 定案）：节点由"城市（{@code City}/{@code SocialCity} 的落点 + tier）
 * 与半径表"在组合根现算，交给 {@code MarketTopology}；{@code EconomyData} 不新增这一维 —— 城市的权威在 {@code
 * simos-map}/{@code simos-social}，economy 再存一份必然漂开。
 *
 * <p>★★ <b>半径 = 该城 tier 的 {@code tierRadiiHex} 上界</b>（MajorCity [8,16] 取 16；见 M2.0 定案与 {@code
 * SettlementGenerator} 腹地口径同源）。★ 具体数值属 GM 参数：{@link #radiusHex()} 由调用方（组合根）算好， economy
 * 不猜、也不内建城市等级表。
 *
 * <p>★ <b>为什么不按国界</b>：一个王国 430 格宽 ⇒ 单程 200+ 天，比整个周期还长（M2.0 #1 的原话）。 区的边界只由"到集散节点的距离"决定，国界不进这条算式。
 *
 * @param nodeId 节点身份（城市 id 的字符串形态；组合根填，稳定即可）
 * @param anchor 集散节点所在格（城市格）；不得为 null
 * @param radiusHex 市场半径（hex）；{@code >= 0}（0 = 只覆盖本格，合法：没有半径信息的城市退回单格）
 * @param numeraire 本区的报价币种；不得为 null
 * @param receiveWith 本区卖方接收的货币工具；不得为 null
 */
public record MarketNode(
    String nodeId, HexCoord anchor, int radiusHex, CurrencyId numeraire, InstrumentId receiveWith) {

  public MarketNode {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("MarketNode.nodeId 不得为空白");
    }
    if (anchor == null) {
      throw new IllegalArgumentException("MarketNode.anchor 不得为 null（集散节点必须落在某一格）");
    }
    if (radiusHex < 0) {
      throw new IllegalArgumentException("MarketNode.radiusHex 不得为负: " + radiusHex);
    }
    if (numeraire == null) {
      throw new IllegalArgumentException("MarketNode.numeraire 不得为 null（区必须有报价币种）");
    }
    if (receiveWith == null) {
      throw new IllegalArgumentException("MarketNode.receiveWith 不得为 null（区必须说清收哪种钱）");
    }
  }
}
