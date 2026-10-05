package io.mosire.simos.social.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 M2 的 {@code RoundTripComponentsTest}）：反射枚举 {@link SocialData} 的 record
 * 组件，逐组件造差异，三条断言 —— 新增状态组件若忘了进变更集，本测试自动红。
 */
class SocialRoundTripTest {

  private static final HexCoord H00 = new HexCoord(0, 0);

  /** ★ 唯一的豁免集合：M3 的 SocialData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everySocialDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : SocialData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      SocialData base = SocialData.empty();
      SocialData target = mutate(base, name);
      SocialChangeSet cs = SocialChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(SocialChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("SocialData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyFiveComponents() {
    assertThat(SocialChangeSet.class.getRecordComponents()).hasSize(5);
    assertThat(componentNames(SocialChangeSet.class))
        .as("变更集的每个组件都必须在 SocialData 里有同名的 record 组件")
        .isSubsetOf(componentNames(SocialData.class));
    assertThat(componentNames(SocialData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(SocialChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsSocial() {
    SocialSnapshot snapshot =
        new SocialSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            SocialData.empty());
    assertThat(snapshot.namespace()).isEqualTo("social");
  }

  /**
   * ★ R1 起 {@code groups} **自带支撑组件**（{@code populations}）：设计稿 §十.7 的跨组件校验要求"批次必须落在有 {@code
   * populations} 序列的格上"，而本用例的起点是 {@link SocialData#empty()} —— 只加一个批次会当场被构造期拒。
   * 多带一个支撑组件**不破坏本用例的任何断言**（三条断言都只盯 {@code name} 那一个组件）。 同款先例：plan2 Task 3 对 "debts" 变体的处理（单改 debts
   * 必然非法 ⇒ 变异体自带支撑组件）。
   */
  private static SocialData mutate(SocialData base, String name) {
    return switch (name) {
      case "populations" -> base.withPopulations(onePopulation());
      case "cities" -> base.withCities(oneCity());
      case "groups", "households" ->
          base.withPopulations(onePopulation()).withGroupsAndHouseholds(oneGroup(), oneHousehold());
      case "populationEvents" ->
          base.withPopulations(onePopulation())
              .withGroupsAndHouseholds(oneGroup(), oneHousehold())
              .withPopulationEvents(Map.of(oneEvent().id(), oneEvent()));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(SocialChangeSet cs, String name) {
    return switch (name) {
      case "populations" -> cs.populations().changed();
      case "cities" -> cs.cities().changed();
      case "groups" -> cs.groups().changed();
      case "households" -> cs.households().changed();
      case "populationEvents" -> cs.populationEvents().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  private static Map<HexCoord, PopulationSeries> onePopulation() {
    return Map.of(
        H00,
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 10000L),
            new SegmentedSeries<>(
                List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
            List.of()));
  }

  private static Map<CityId, SocialCity> oneCity() {
    CityId id = new CityId("c1");
    return Map.of(id, new SocialCity(id, "城甲", H00, Optional.empty(), Map.of()));
  }

  /** 一条 {@link PopulationGroup}：落在 {@link #H00}（与 {@link #onePopulation()} 同一格，跨组件校验才过）。 */
  private static Map<PeopleLotId, PopulationGroup> oneGroup() {
    return Map.of(
        PopulationLots.rural(H00, Sex.MALE, "1"),
        new PopulationGroup(
            PopulationLots.rural(H00, Sex.MALE, "1"), Sex.MALE, 300L, 250L, 0L));
  }

  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-0_0");

  /** 与 {@link #oneGroup()} 配套的家户：位置 = H00、成员 = 那一条批次（S2 起批次必须有主）。 */
  private static Map<HouseholdId, Household> oneHousehold() {
    return Map.of(
        HOUSEHOLD,
        new Household(
            HOUSEHOLD,
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("一户口", null, Map.of()),
            Map.of(PopulationLots.rural(H00, Sex.MALE, "1"), 300L),
            new HouseholdVitalRates(List.of())));
  }

  /** 与 {@link #oneHousehold()} 配套的审计事件（RATE_SET 不改人数，只给事件表一个非空差异）。 */
  private static HouseholdPopulationEvent oneEvent() {
    return new HouseholdPopulationEvent(
        "evt-rate-set",
        HOUSEHOLD,
        PopulationEventType.RATE_SET,
        Sex.MALE,
        "0-14",
        0L,
        0L,
        "test",
        "SocialRoundTripTest");
  }
}
