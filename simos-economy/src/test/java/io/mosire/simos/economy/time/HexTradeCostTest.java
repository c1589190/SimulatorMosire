package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.TransportTariff;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-027 单 hex 贸易成本判据（设计 §7.2）</b>：
 *
 * <ol>
 *   <li>同格 {@code lossPerMille = 0}；
 *   <li>跨格损耗 = {@code min(500, 2 × distance × max(1, moveCostAt(to)))}，随距离/地形单调；
 *   <li>封顶 {@code 500‰}；
 *   <li>缺归属（非成员格）fail-closed 具名拒绝；
 *   <li>{@code costMilliPerUnit == 0}（单区第一版不产生货币运费）。
 * </ol>
 */
class HexTradeCostTest {

  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);
  private static final HexCoord H01 = new HexCoord(0, 1);
  private static final HexCoord H99 = new HexCoord(99, 0);
  private static final HexCoord OUTSIDE = new HexCoord(50, 50);

  private static MarketTopology topology() {
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    for (HexCoord hex : new HexCoord[] {H00, H10, H20, H01, H99}) {
      markets.put(hex, new Market(MarketSettlementFixtures.SILVER, Map.of(GRAIN, 10L)));
    }
    Map<HexCoord, Integer> moveCostAt = new LinkedHashMap<>();
    moveCostAt.put(H00, 1);
    moveCostAt.put(H10, 2);
    moveCostAt.put(H20, 4);
    moveCostAt.put(H01, 6);
    moveCostAt.put(H99, 999); // 不可通行的哨兵也不必特殊：封顶仍然生效
    return MarketTopology.singleRegion(
        markets,
        markets.keySet(),
        hex -> moveCostAt.getOrDefault(hex, 1),
        (from, to) -> 0,
        TransportTariff.probeDefaults());
  }

  @Test
  void sameHexHasZeroLoss() {
    HexTradeCost cost = new HexTradeCost(topology());
    assertThat(cost.lossPerMilleBetween(H00, H00)).as("同格 0").isZero();
    assertThat(cost.lossPerMilleBetween(H10, H10)).as("同格 0（任意成员格）").isZero();
  }

  @Test
  void lossIsMonotonicInDistanceAndMoveCost() {
    HexTradeCost cost = new HexTradeCost(topology());

    long oneHex = cost.lossPerMilleBetween(H00, H10); // 1 × 2 × 2‰ = 4
    long twoHex = cost.lossPerMilleBetween(H00, H20); // 2 × 4 × 2‰ = 16
    assertThat(oneHex).as("距离 1、地形 2 的具名值").isEqualTo(4L);
    assertThat(twoHex).as("距离 2、地形 4 的具名值").isEqualTo(16L);
    assertThat(twoHex).as("★ 距离/地形增加 ⇒ 损耗不降").isGreaterThanOrEqualTo(oneHex);

    long worseTerrain = cost.lossPerMilleBetween(H00, H01); // 1 × 6 × 2‰ = 12
    assertThat(worseTerrain).as("同距离、更难的格 ⇒ 损耗更高").isGreaterThan(oneHex);

    // 方向性：moveCostAt(to) 只读目标格（与拓扑的唯一拼写点一致）。
    assertThat(cost.lossPerMilleBetween(H10, H00)).as("反向只读目标格 H00 的地形 1").isEqualTo(2L);
  }

  @Test
  void lossIsCappedAt500PerMille() {
    HexTradeCost cost = new HexTradeCost(topology());
    long capped = cost.lossPerMilleBetween(H00, H99);
    assertThat(capped)
        .as("★ 2 × 99 × 999 远超上限 ⇒ 封顶 500‰")
        .isEqualTo(HexTradeCost.MAX_HEX_TRADE_LOSS_PER_MILLE)
        .isEqualTo(500L);
    assertThat(capped).as("损耗率不得超过 500‰").isLessThanOrEqualTo(500L);
  }

  @Test
  void missingOwnershipFailsClosed() {
    HexTradeCost cost = new HexTradeCost(topology());
    assertThatThrownBy(() -> cost.lossPerMilleBetween(OUTSIDE, H00))
        .as("from 无归属必须具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有归属");
    assertThatThrownBy(() -> cost.lossPerMilleBetween(H00, OUTSIDE))
        .as("to 无归属必须具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有归属");
    assertThatThrownBy(() -> cost.lossPerMilleBetween(null, H00))
        .as("null fail-closed")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
    assertThatThrownBy(() -> new HexTradeCost(null))
        .as("null topology fail-closed")
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void singleRegionCostMilliPerUnitIsAlwaysZero() {
    HexTradeCost cost = new HexTradeCost(topology());
    assertThat(HexTradeCost.HEX_TRADE_COST_MILLI_PER_UNIT).as("★ 第一版单区货币运费恒 0（唯一拼写点）").isZero();
    assertThat(cost.costMilliPerUnit(H00, H00)).as("同格 0").isZero();
    assertThat(cost.costMilliPerUnit(H00, H99)).as("跨格也是 0（货币运费留给后续承运人批次）").isZero();
  }
}
