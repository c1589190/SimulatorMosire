package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-027 单市场区拓扑判据（设计 §7.1）</b>：
 *
 * <ol>
 *   <li>{@code MarketTopology.singleRegion} 把全部有市场的 hex 归入<b>恰好一个</b>区；{@code regionOf} 无第二区；
 *   <li>锚格 = 规范序（q, r 升序）第一个格、{@code nodeId = "single-region"}、{@code radiusHex = 0}；
 *   <li>{@code adjacent} 对同区/自身恒 false（单区没有跨区候选）；
 *   <li>成员表与 {@code regionOf}/{@code contains} 一致；
 *   <li>同币多个市场格（多城）仍是一个区；
 *   <li>缺市场/两入参不同源等坏数据 fail-closed（具名 IAE）。
 * </ol>
 */
class MarketTopologySingleRegionTest {

  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);
  private static final HexCoord H02 = new HexCoord(0, 2);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord OUTSIDE = new HexCoord(9, 9);

  @Test
  void singleRegionCollapsesAllMarketHexesIntoOneRegion() {
    Map<HexCoord, Market> markets = markets(H00, H10, H20, H02, H12);
    MarketTopology topology =
        MarketTopology.singleRegion(
            markets, markets.keySet(), hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults());

    assertThat(topology.regions()).as("★ 恰好一个区").hasSize(1);
    MarketRegion region = topology.regions().get(0);
    assertThat(region.node().nodeId()).as("单区 nodeId").isEqualTo("single-region");
    assertThat(region.node().radiusHex()).as("单区 radiusHex = 0").isZero();
    assertThat(region.anchor()).as("★ 锚 = 规范序第一个格 (0,0)").isEqualTo(H00);
    assertThat(region.numeraire()).as("区报价币种来自锚格市场").isEqualTo(markets.get(H00).numeraire());
    assertThat(region.members())
        .as("★ 全部有市场的 hex 都在同一个区")
        .containsExactlyInAnyOrderElementsOf(markets.keySet());
    assertThat(topology.regional()).as("单区仍是区域拓扑（有区级撮合）").isTrue();

    for (HexCoord member : markets.keySet()) {
      assertThat(topology.contains(member)).as("contains(%s)", member).isTrue();
      assertThat(topology.regionOf(member)).as("★ regionOf(%s) 无第二区", member).isSameAs(region);
    }
    assertThat(topology.contains(OUTSIDE)).as("非成员 contains = false").isFalse();
    assertThatThrownBy(() -> topology.regionOf(OUTSIDE))
        .as("非成员 regionOf 必须具名拒绝，不许静默返回别的区")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有归属");
  }

  @Test
  void anchorIsCanonicalFirstIndependentOfInputIterationOrder() {
    // 故意用 (1,2) -> (1,0) -> (0,2) 的逆/乱序迭代；规范序第一个必须是 (0,2)（q 最小；同 q 再 r 最小）。
    Set<HexCoord> members = new LinkedHashSet<>();
    members.add(H12);
    members.add(H10);
    members.add(H02);
    Map<HexCoord, Market> markets = markets(H12, H10, H02);

    MarketTopology topology =
        MarketTopology.singleRegion(
            markets, members, hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults());

    assertThat(topology.regions().get(0).anchor())
        .as("★ 锚 = q,r 升序第一个 (0,2)，与入参迭代序无关")
        .isEqualTo(H02);
    // 同 q 不同 r 时按 r 升序：{ (1,2), (0,2), (1,0) } 的规范序第一是 (0,2)；再验证 q 相同的一对。
    LinkedHashSet<HexCoord> sameQ = new LinkedHashSet<>();
    sameQ.add(new HexCoord(1, 2));
    sameQ.add(new HexCoord(1, 0));
    Map<HexCoord, Market> sameQMarkets = markets(new HexCoord(1, 2), new HexCoord(1, 0));
    assertThat(
            MarketTopology.singleRegion(
                    sameQMarkets,
                    sameQ,
                    hex -> 1,
                    (from, to) -> 0,
                    TransportTariff.probeDefaults())
                .regions()
                .get(0)
                .anchor())
        .as("同 q 时按 r 升序：(1,0) 在前")
        .isEqualTo(new HexCoord(1, 0));
  }

  @Test
  void adjacentIsFalseForSingleRegionAndItself() {
    Map<HexCoord, Market> markets = markets(H00, H10);
    MarketTopology topology =
        MarketTopology.singleRegion(
            markets, markets.keySet(), hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults());
    MarketRegion region = topology.regions().get(0);

    assertThat(topology.adjacent(region, region))
        .as("★ 单区对自身不构成跨区邻接")
        .isFalse();
    assertThat(topology.regions()).as("没有第二个区可判邻接").hasSize(1);
    assertThat(topology.freightPerMilleBetween(H00, H10))
        .as("跨区费率入口仍可用于同区运费读数，但它不是本批判据（本批区内走实物损耗）")
        .isNotNegative();
  }

  @Test
  void sameNumeraireMultipleMarketHexesRemainOneRegion() {
    // 同币多个市场格 = "多城同币"；单区入口只认市场表与成员集，不按城市/半径拆区。
    Map<HexCoord, Market> markets = markets(H00, H10, H20);
    MarketTopology topology =
        MarketTopology.singleRegion(
            markets, markets.keySet(), hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults());

    assertThat(topology.regions()).as("★ 同币多城/多市场格仍是一个区").hasSize(1);
    assertThat(topology.regions().get(0).members()).containsExactlyInAnyOrder(H00, H10, H20);
  }

  @Test
  void malformedInputsFailClosed() {
    Map<HexCoord, Market> markets = markets(H00, H10);
    Set<HexCoord> members = new LinkedHashSet<>(markets.keySet());

    assertThatThrownBy(
            () ->
                MarketTopology.singleRegion(
                    Map.of(), members, hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults()))
        .as("空 markets 必须拒绝（没有报价币种的区不能交易）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("markets 为空");

    assertThatThrownBy(
            () ->
                MarketTopology.singleRegion(
                    markets, Set.of(), hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults()))
        .as("空 marketHexes 必须拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("marketHexes 不得为空");

    Set<HexCoord> missingMarketMember = new LinkedHashSet<>(members);
    missingMarketMember.add(H20);
    assertThatThrownBy(
            () ->
                MarketTopology.singleRegion(
                    markets,
                    missingMarketMember,
                    hex -> 1,
                    (from, to) -> 0,
                    TransportTariff.probeDefaults()))
        .as("成员格缺市场表条目必须拒绝（不许无价交易）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须有市场表条目");

    Set<HexCoord> shortMembers = new LinkedHashSet<>();
    shortMembers.add(H00);
    assertThatThrownBy(
            () ->
                MarketTopology.singleRegion(
                    markets, shortMembers, hex -> 1, (from, to) -> 0, TransportTariff.probeDefaults()))
        .as("markets 有成员表之外的格（两入参不同源）必须拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("两个入参不同源");
  }

  private static Map<HexCoord, Market> markets(HexCoord... hexes) {
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    for (HexCoord hex : hexes) {
      markets.put(hex, new Market(MarketSettlementFixtures.SILVER, Map.of(GRAIN, 10L)));
    }
    return markets;
  }
}
