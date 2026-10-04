package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.app.testing.SocialHouseholdFixture;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ **{@code NationSummary} 派生视图**（阶段 12，计划 §2.4）：每个 {@link GovLevel#CENTRAL} 根沿 {@code
 * superiorGov} 向下聚合自己 + 全部下级 GOV，汇总显示名、名义区域与人口。
 *
 * <p>★ 判据逐值：
 *
 * <ol>
 *   <li>{@code govUnits} = BFS 序（根在前、直接下级按 units 插入序、逐层展开）；
 *   <li>{@code nominalRegions} = 各 GOV {@code Unit.jurisdiction} 的**并集、首现序**；
 *   <li>{@code totalPopulation} = 名义 Region 覆盖的 **hex 并集**上 {@code populationAt} 之和——同一格被多个 Region
 *       覆盖只计一次；
 *   <li>无 CENTRAL ⇒ 空列表；多个 CENTRAL ⇒ 各聚合各自的子树。
 * </ol>
 */
class NationSummaryTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H21 = new HexCoord(2, 1);

  private static final UnitId CENTRAL = new UnitId("g-central");
  private static final UnitId PROVINCE = new UnitId("g-province");
  private static final UnitId GRAND = new UnitId("g-grand");
  private static final UnitId CENTRAL_B = new UnitId("g-central-b");

  private static final RegionId R_ROOT = new RegionId("r-root");
  private static final RegionId R_CHILD = new RegionId("r-child");
  private static final RegionId R_GRAND = new RegionId("r-grand");
  private static final RegionId R_OTHER = new RegionId("r-other");

  @Test
  void centralRootAggregatesItselfAndItsWholeSubtree() {
    UnitState units =
        stateOf(
            gov(CENTRAL, "中央 GOV", Optional.empty(), GovLevel.CENTRAL, R_ROOT),
            gov(PROVINCE, "省 GOV", Optional.of(CENTRAL), GovLevel.PROVINCE, R_CHILD),
            gov(GRAND, "县 GOV", Optional.of(PROVINCE), GovLevel.PROVINCE, R_GRAND));
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.taggedRegion("r-root", null, H11, H12),
            ScopeFixtures.taggedRegion("r-child", null, H12, H13),
            ScopeFixtures.taggedRegion("r-grand", null, H21));
    SocialData social = social(Map.of(H11, 100L, H12, 200L, H13, 35L, H21, 7L));

    List<NationSummary> summaries = NationSummary.of(units, map, social);

    assertThat(summaries).as("恰一个 CENTRAL ⇒ 恰一条概括").hasSize(1);
    NationSummary summary = summaries.get(0);
    assertThat(summary.displayName()).as("displayName = 中央 GOV 单位名").isEqualTo("中央 GOV");
    assertThat(summary.centralGovUnit()).isEqualTo(CENTRAL);
    assertThat(new ArrayList<>(summary.govUnits()))
        .as("BFS 序：根 → 省 → 县")
        .containsExactly(CENTRAL, PROVINCE, GRAND);
    assertThat(new ArrayList<>(summary.nominalRegions()))
        .as("名义 Region 并集 = 根 + 下级，保首次出现序")
        .containsExactly(R_ROOT, R_CHILD, R_GRAND);
    assertThat(social.populationAt(H12)).as("夹具前提：H12 有 200 人").isEqualTo(200L);
    assertThat(summary.totalPopulation())
        .as("逐值：100(H11) + 200(H12) + 35(H13) + 7(H21)，H12 被两个 Region 覆盖只计一次 = 342")
        .isEqualTo(342L);

    // 派生视图的集合不可变。
    assertThat(summary.govUnits()).isUnmodifiable();
    assertThat(summary.nominalRegions()).isUnmodifiable();
  }

  @Test
  void noCentralGovMeansAnEmptyList() {
    UnitState units = stateOf(gov(PROVINCE, "省 GOV", Optional.empty(), GovLevel.PROVINCE, R_CHILD));
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.taggedRegion("r-child", null, H11));

    assertThat(NationSummary.of(units, map, social(Map.of(H11, 10L))))
        .as("没有 CENTRAL ⇒ 空表（省级 GOV 不自己造国家）")
        .isEmpty();
  }

  @Test
  void everyCentralGovGetsItsOwnSubtree() {
    UnitState units =
        stateOf(
            gov(CENTRAL, "甲国中央", Optional.empty(), GovLevel.CENTRAL, R_ROOT),
            gov(PROVINCE, "甲国省", Optional.of(CENTRAL), GovLevel.PROVINCE, R_CHILD),
            gov(CENTRAL_B, "乙国中央", Optional.empty(), GovLevel.CENTRAL, R_OTHER));
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.taggedRegion("r-root", null, H11),
            ScopeFixtures.taggedRegion("r-child", null, H12),
            ScopeFixtures.taggedRegion("r-other", null, H13));
    SocialData social = social(Map.of(H11, 100L, H12, 20L, H13, 5L));

    List<NationSummary> summaries = NationSummary.of(units, map, social);

    assertThat(summaries)
        .as("按 units 插入序，每个 CENTRAL 一条")
        .extracting(NationSummary::displayName)
        .containsExactly("甲国中央", "乙国中央");
    assertThat(summaries.get(0).govUnits()).containsExactly(CENTRAL, PROVINCE);
    assertThat(summaries.get(0).nominalRegions()).containsExactly(R_ROOT, R_CHILD);
    assertThat(summaries.get(0).totalPopulation()).as("甲国：100 + 20").isEqualTo(120L);
    assertThat(summaries.get(1).govUnits()).containsExactly(CENTRAL_B);
    assertThat(summaries.get(1).nominalRegions()).containsExactly(R_OTHER);
    assertThat(summaries.get(1).totalPopulation()).as("乙国：只算自己名义区域里的 5").isEqualTo(5L);
  }

  @Test
  void aCentralWithEmptyJurisdictionStillAggregatesChildren() {
    UnitState units =
        stateOf(
            gov(CENTRAL, "中央 GOV", Optional.empty(), GovLevel.CENTRAL),
            gov(PROVINCE, "省 GOV", Optional.of(CENTRAL), GovLevel.PROVINCE, R_CHILD));
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.taggedRegion("r-child", null, H12, H13));
    SocialData social = social(Map.of(H12, 200L, H13, 35L));

    NationSummary summary = NationSummary.of(units, map, social).get(0);

    assertThat(summary.govUnits()).containsExactly(CENTRAL, PROVINCE);
    assertThat(summary.nominalRegions()).as("中央自己无管辖，但下级辖区照常并入名义全境").containsExactly(R_CHILD);
    assertThat(summary.totalPopulation()).as("200 + 35，逐值").isEqualTo(235L);
  }

  @Test
  void aHandBuiltCycleDoesNotHang() {
    UnitState units =
        stateOf(
            gov(CENTRAL, "环中央", Optional.of(PROVINCE), GovLevel.CENTRAL, R_ROOT),
            gov(PROVINCE, "环省", Optional.of(CENTRAL), GovLevel.PROVINCE, R_CHILD));
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.taggedRegion("r-root", null, H11),
            ScopeFixtures.taggedRegion("r-child", null, H12));
    SocialData social = social(Map.of(H11, 3L, H12, 4L));

    NationSummary summary = NationSummary.of(units, map, social).get(0);

    assertThat(summary.govUnits()).as("环由 seen 兜底，各出现一次").containsExactly(CENTRAL, PROVINCE);
    assertThat(summary.nominalRegions()).containsExactly(R_ROOT, R_CHILD);
    assertThat(summary.totalPopulation()).isEqualTo(7L);
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  private static Unit gov(
      UnitId id, String name, Optional<UnitId> superior, GovLevel level, RegionId... regions) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(ScopeFixtures.T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.<RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.of(jurisdiction(regions)),
        Optional.of(new GovFormation(Map.of(), OfficePolicy.defaults(), superior, level)));
  }

  private static Jurisdiction jurisdiction(RegionId... regions) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    for (RegionId region : regions) {
      rates.put(region, 0L);
    }
    return new Jurisdiction(rates, 0L, 0L, 0L, 0);
  }

  /** 按格人口量给 social：每个格一条农村批次（SocialData 的跨组件校验要求批次落点有序列）。 */
  private static SocialData social(Map<HexCoord, Long> byHex) {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Long> entry : byHex.entrySet()) {
      HexCoord hex = entry.getKey();
      populations.put(hex, populationSeries());
      PeopleLotId lot = new PeopleLotId("lot-" + hex);
      groups.put(lot, new PopulationGroup(lot, Sex.MALE, entry.getValue(), 30L, 0L));
      locations.put(lot, hex);
    }
    return SocialHouseholdFixture.withHouseholdsAt(populations, Map.of(), groups, locations);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(ScopeFixtures.T0, 0L),
        new SegmentedSeries<>(List.of(new Segment<>(ScopeFixtures.T0, 0.0)), List.of(), null),
        List.of());
  }
}
