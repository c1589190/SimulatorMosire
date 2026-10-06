package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.app.world.RealTwelveHexWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-027 组合根单区选择判据（设计 §7.1/§3.1）</b>：生产路径 {@link MarketTopologyBook#from(SimulationState)}
 * 只在"有城市权威且所有市场同币"时走 {@link MarketTopology#singleRegion}；同币多城仍是一个区，不同币/无城市权威退回旧城市半径路径。
 *
 * <p>本类住 {@code io.mosire.simos.app.time} 包，可直接调包内可见的生产装配入口 —— 不复制装配逻辑。
 */
class MarketTopologyBookSingleRegionTest {

  private static final CommodityId GRAIN = EconomySettlement.GRAIN;
  private static final HexCoord H1 = new HexCoord(0, 0);
  private static final HexCoord H2 = new HexCoord(1, 0);

  @Test
  void sameNumeraireWithTwoCitiesIsExactlyOneRegion() {
    LinkedHashMap<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H1, market(MoneyVocabulary.SILVER_CURRENCY, 10L));
    markets.put(H2, market(MoneyVocabulary.SILVER_CURRENCY, 12L));
    SocialData social = socialData(city("city-a", H1, "MarketTown"), city("city-b", H2, "Town"));

    MarketTopology topology = MarketTopologyBook.from(state(markets, social));

    assertThat(topology.regions()).as("★ 同币多城 = 一个市场区（不是两个城市半径区）").hasSize(1);
    MarketRegion region = topology.regions().get(0);
    assertThat(region.node().nodeId()).as("生产路径走 singleRegion 入口").isEqualTo("single-region");
    assertThat(region.node().radiusHex()).as("单区 radiusHex = 0").isZero();
    assertThat(region.anchor()).as("锚 = 规范序第一个市场格 (0,0)").isEqualTo(H1);
    assertThat(region.members()).as("全部有市场的 hex 都在这个区").containsExactlyInAnyOrder(H1, H2);
    assertThat(topology.regionOf(H1)).isSameAs(region);
    assertThat(topology.regionOf(H2)).isSameAs(region);
  }

  @Test
  void differentNumeraireFallsBackToLegacyCityRadiusPath() {
    LinkedHashMap<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H1, market(MoneyVocabulary.SILVER_CURRENCY, 10L));
    markets.put(H2, market(new CurrencyId("copper"), 12L));
    SocialData social = socialData(city("city-a", H1, "MarketTown"), city("city-b", H2, "Town"));

    MarketTopology topology = MarketTopologyBook.from(state(markets, social));

    assertThat(topology.regions()).as("不同币种不合并成单区").hasSize(1);
    assertThat(topology.regions().get(0).node().nodeId())
        .as("★ 退回旧城市节点路径（非 single-region）")
        .isNotEqualTo("single-region");
    assertThat(topology.regions().get(0).node().radiusHex())
        .as("旧路径保留城市的 tier 半径（MarketTown = 2）")
        .isEqualTo(2);
    assertThat(topology.regions().get(0).node().radiusHex()).isNotZero();
  }

  @Test
  void withoutCityAuthorityMarketsStayPerHex() {
    LinkedHashMap<HexCoord, Market> markets = new LinkedHashMap<>();
    markets.put(H1, market(MoneyVocabulary.SILVER_CURRENCY, 10L));
    markets.put(H2, market(MoneyVocabulary.SILVER_CURRENCY, 12L));

    MarketTopology topology = MarketTopologyBook.from(state(markets, SocialData.empty()));

    assertThat(topology.regions()).as("没有城市权威 ⇒ 每格一区（不猜单区）").hasSize(2);
    assertThat(topology.regions())
        .allSatisfy(region -> assertThat(region.node().nodeId()).isNotEqualTo("single-region"));
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static Market market(CurrencyId numeraire, long grainPrice) {
    return new Market(numeraire, Map.of(GRAIN, grainPrice));
  }

  private static SocialCity city(String id, HexCoord at, String tier) {
    return new SocialCity(
        new CityId(id), id, at, Optional.of(RealTwelveHexWorld.REGION), Map.of("tier", tier));
  }

  private static SocialData socialData(SocialCity... cities) {
    Map<CityId, SocialCity> byId = new LinkedHashMap<>();
    for (SocialCity city : cities) {
      byId.put(city.id(), city);
    }
    return new SocialData(Map.of(), byId, Map.of());
  }

  private static SimulationState state(Map<HexCoord, Market> markets, SocialData social) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp t0 = SimosTimestamp.of(0L);
    return new SimulationState(
        new StateMeta(ref, t0),
        Map.of(
            "map", new MapSnapshot(ref, t0, RealTwelveHexWorld.map()),
            "social", new SocialSnapshot(ref, t0, social),
            "unit", new UnitSnapshot(ref, t0, UnitState.empty()),
            "sd", new SdSnapshot(ref, t0, SdState.empty()),
            "economy", new EconomySnapshot(ref, t0, EconomyData.empty().withMarkets(markets)),
            "actor", new ActorSnapshot(ref, t0, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }
}
