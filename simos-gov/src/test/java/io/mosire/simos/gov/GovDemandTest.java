package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link GovDemand} 的逐值判据（阶段 11a，计划 §2.2 / §3）：10 万人口非城市格 = (200,100)、城市格 = (240,160)； 需求为 0
 * 的格也保留在表里；管辖 Region 查无 ⇒ 跳过（空表不抛）；保序不可变。
 *
 * <p>★ 期望值是手算的 {@code ceil(100000/500)=200}、{@code ceil(100000/1000)=100} 与城市加项 +40/+60，不是“再调一遍领域
 * API”。
 */
class GovDemandTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord H1 = new HexCoord(1, 1);
  private static final HexCoord H2 = new HexCoord(2, 2);
  private static final RegionId R1 = new RegionId("r-1");
  private static final RegionId R2 = new RegionId("r-2");
  private static final RegionId GHOST = new RegionId("r-ghost");
  private static final UnitId U1 = new UnitId("u-1");
  private static final long POPULATION = 100_000L;

  @Test
  void tenMyriadPopulationNonCityIsTwoHundredSecurityAndOneHundredPaperwork() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, false);
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(R1, 100L)));

    Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);

    assertThat(demand).as("一个 Region 一格 ⇒ 一条需求").hasSize(1);
    assertThat(demand.get(H1))
        .as("非城市：ceil(100000/500)=200、ceil(100000/1000)=100")
        .isEqualTo(new GovDemand.HexDemand(200L, 100L));
  }

  @Test
  void tenMyriadPopulationCityAddsFixedWeights() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, true);
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(R1, 100L)));

    Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);

    assertThat(demand.get(H1))
        .as("城市：200+40=240、100+60=160")
        .isEqualTo(new GovDemand.HexDemand(240L, 160L));
  }

  @Test
  void zeroPopulationAndNoCityKeepsZeroDemandInTheTable() {
    GameMap map = map(region(R1, H1));
    SocialData social = SocialData.empty();
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(R1, 100L)));

    Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);

    assertThat(demand).as("0 也是事实：无人无城的格保留在表里，不是查无此键").containsOnlyKeys(H1);
    assertThat(demand.get(H1)).isEqualTo(new GovDemand.HexDemand(0L, 0L));
  }

  @Test
  void ceilingRoundsUpForAnyNonZeroRemainder() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, 1L, false);
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(R1, 100L)));

    assertThat(GovDemand.of(map, social, unit).get(H1))
        .as("1 人也各要 1 名：ceil(1/500)=1、ceil(1/1000)=1")
        .isEqualTo(new GovDemand.HexDemand(1L, 1L));
  }

  @Test
  void jurisdictionPointingAtMissingRegionIsSkippedWithoutThrowing() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, false);
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(GHOST, 100L, R1, 100L)));
    // ★ GHOST 的 rate 也 >0，但它不在 map.regions()：整区跳过，R1 仍算出需求（不是整表空）。
    Unit both = unit;

    Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, both);
    assertThat(demand).containsOnlyKeys(H1);
    assertThat(demand.get(H1)).isEqualTo(new GovDemand.HexDemand(200L, 100L));

    Unit onlyGhost = unitWithJurisdiction(jurisdiction(Map.of(GHOST, 100L)));
    assertThat(GovDemand.of(map, social, onlyGhost)).as("管辖 Region 全查无 ⇒ 空表（空是事实，不抛）").isEmpty();
  }

  @Test
  void unitWithoutJurisdictionReturnsEmptyTable() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, false);
    Unit unit = unitWithJurisdiction(Optional.empty()); // 构造器签名的 Optional 版本见下

    assertThat(GovDemand.of(map, social, unit)).isEmpty();
  }

  @Test
  void preservesJurisdictionRegionOrderAndIsImmutable() {
    GameMap map = map(region(R2, H2), region(R1, H1));
    SocialData social = socialWithPopulations(Map.of(H1, POPULATION, H2, 2_000L), Map.of());
    // 管辖表按 R1 → R2 给（map.regions 的插入序相反）；需求表应跟着管辖 key 的序。
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(R1, 100L);
    rates.put(R2, 100L);
    Unit unit = unitWithJurisdiction(jurisdiction(rates));

    Map<HexCoord, GovDemand.HexDemand> demand = GovDemand.of(map, social, unit);

    assertThat(new ArrayList<>(demand.keySet()))
        .as("保序：需求表键序 = 管辖 Region key 的迭代序（R1 的 H1 先、R2 的 H2 后）")
        .containsExactly(H1, H2);
    assertThat(demand.get(H1)).isEqualTo(new GovDemand.HexDemand(200L, 100L));
    assertThat(demand.get(H2)).isEqualTo(new GovDemand.HexDemand(4L, 2L));
    assertThatThrownBy(() -> demand.put(H1, new GovDemand.HexDemand(1L, 1L)))
        .as("冻结：外部不得改需求表")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void overlappingRegionsKeepFirstOccurrenceOnce() {
    GameMap map = map(region(R1, H1), region(R2, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, false);
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(R1, 100L);
    rates.put(R2, 100L);

    Map<HexCoord, GovDemand.HexDemand> demand =
        GovDemand.of(map, social, unitWithJurisdiction(jurisdiction(rates)));

    assertThat(demand).as("重叠 Region 的同一格只保留一条（putIfAbsent）").hasSize(1);
    assertThat(demand.get(H1)).isEqualTo(new GovDemand.HexDemand(200L, 100L));
  }

  @Test
  void nullInputsThrow() {
    GameMap map = map(region(R1, H1));
    SocialData social = socialWithPopulation(H1, POPULATION, false);
    Unit unit = unitWithJurisdiction(jurisdiction(Map.of(R1, 100L)));

    assertThatThrownBy(() -> GovDemand.of(null, social, unit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map 不得为 null");
    assertThatThrownBy(() -> GovDemand.of(map, null, unit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("social 不得为 null");
    assertThatThrownBy(() -> GovDemand.of(map, social, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit 不得为 null");
  }

  @Test
  void hexDemandNegativeValuesThrow() {
    assertThatThrownBy(() -> new GovDemand.HexDemand(-1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("security 必须 ≥ 0");
    assertThatThrownBy(() -> new GovDemand.HexDemand(0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperwork 必须 ≥ 0");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static Unit unitWithJurisdiction(Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        U1,
        "gov-unit",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H1))), List.of(), null),
        List.of(new CompositionEntry("官员", 1)),
        List.of(),
        1,
        1000,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction,
        Optional.empty());
  }

  private static Unit unitWithJurisdiction(Jurisdiction jurisdiction) {
    return unitWithJurisdiction(Optional.of(jurisdiction));
  }

  private static Jurisdiction jurisdiction(Map<RegionId, Long> rates) {
    return new Jurisdiction(rates, 0L, 0L, 0L, 0L);
  }

  private static Region region(RegionId id, HexCoord hex) {
    return Region.of(id, id.value(), Set.of(hex), RegionMeta.empty());
  }

  private static GameMap map(Region... regions) {
    HexCoord[] hexes = {H1, H2};
    Map<HexCoord, HexCell> hexMap = new LinkedHashMap<>();
    for (HexCoord hex : hexes) {
      hexMap.put(hex, new HexCell(0.5));
    }
    Map<RegionId, Region> regionMap = new LinkedHashMap<>();
    for (Region region : regions) {
      regionMap.put(region.id(), region);
    }
    TerrainType desert = TerrainCatalog.of("desert");
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexMap,
        TerrainBlocks.uniform(hexMap.keySet(), desert.key()),
        regionMap,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SocialData socialWithPopulation(HexCoord at, long count, boolean withCity) {
    Map<CityId, SocialCity> cities = new LinkedHashMap<>();
    if (withCity) {
      cities.put(
          new CityId("c-1"), new SocialCity(new CityId("c-1"), "城", at, Optional.of(R1), Map.of()));
    }
    return socialWithPopulations(Map.of(at, count), cities);
  }

  private static SocialData socialWithPopulations(
      Map<HexCoord, Long> populations, Map<CityId, SocialCity> cities) {
    Map<HexCoord, PopulationSeries> series = new LinkedHashMap<>();
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    int index = 0;
    for (Map.Entry<HexCoord, Long> entry : populations.entrySet()) {
      HexCoord at = entry.getKey();
      series.put(at, populationSeries());
      if (entry.getValue() > 0L) {
        PeopleLotId lot = new PeopleLotId("lot-" + index++);
        groups.put(lot, new PopulationGroup(lot, Sex.MALE, entry.getValue(), 20L * 365L, 0L));
        locations.put(lot, at);
      }
    }
    return GovSocialDataFixture.withHouseholdsAt(series, cities, groups, locations);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }
}
