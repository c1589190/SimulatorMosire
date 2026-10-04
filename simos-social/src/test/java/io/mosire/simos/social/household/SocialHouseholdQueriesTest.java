package io.mosire.simos.social.household;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.AgeBracketView;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S2 聚合读口验收（架构 §5/§7 第 3 条）：hex 人口 = 该 hex 家户成员之和；unit 人口 = 该 Unit 家户成员之和；
 * 家户年龄档给出 count / increase / death 读数；SPI 视图与状态本体同源。
 */
class SocialHouseholdQueriesTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final long YEAR = 365L;
  private static final CalendarClock CLOCK = CalendarClock.julianDefault();
  private static final long NOW = 0L;

  private static final HouseholdId HEX_A = HouseholdId.parse("hh-hex-a");
  private static final HouseholdId HEX_B = HouseholdId.parse("hh-hex-b");
  private static final HouseholdId UNIT = HouseholdId.parse("hh-unit");

  private static final PeopleLotId MAN = PeopleLotId.parse("man");
  private static final PeopleLotId WOMAN = PeopleLotId.parse("woman");
  private static final PeopleLotId OTHER_MAN = PeopleLotId.parse("other-man");
  private static final PeopleLotId SOLDIER = PeopleLotId.parse("soldier");

  @Test
  void populationAtSumsOnlyTheHouseholdsOnThatHex() {
    SocialData data = fixture();

    assertThat(data.populationAt(H00)).as("H00 家户：100 + 50").isEqualTo(150L);
    assertThat(data.populationAt(H10)).as("H10 家户：7").isEqualTo(7L);
    assertThat(data.populationAt(new HexCoord(9, 9))).as("没有家户 ⇒ 0（不是异常）").isZero();
    assertThat(data.householdsAt(H00)).extracting(household -> household.id())
        .containsExactly(HEX_A);
    assertThat(data.householdsAt(H10)).extracting(household -> household.id())
        .containsExactly(HEX_B);
  }

  @Test
  void unitPopulationSumsOnlyTheHouseholdsInThatUnit() {
    SocialData data = fixture();

    assertThat(data.unitPopulation("u-7")).as("Unit 家户：40").isEqualTo(40L);
    assertThat(data.unitPopulation("u-missing")).isZero();
    assertThat(data.householdsInUnit("u-7")).extracting(household -> household.id())
        .containsExactly(UNIT);
    assertThat(data.unitOfLot(SOLDIER)).contains("u-7");
    assertThat(data.hexOfLot(SOLDIER)).as("Unit 家户的成员没有 hex").isEmpty();
    assertThat(data.populationAt(new HexCoord(5, 5))).isZero();
  }

  @Test
  void householdPopulationAndLocationAreDerivedFromTheSingleOwnershipEdge() {
    SocialData data = fixture();

    assertThat(data.householdPopulation(HEX_A)).isEqualTo(150L);
    assertThat(data.householdPopulation(HEX_B)).isEqualTo(7L);
    assertThat(data.householdPopulation(UNIT)).isEqualTo(40L);
    assertThat(data.householdPopulation(HouseholdId.parse("hh-missing"))).isZero();

    assertThat(data.householdOfLot(MAN).orElseThrow().id()).isEqualTo(HEX_A);
    assertThat(data.locationOfLot(MAN)).contains(new HouseholdLocation.Hex(H00));
    assertThat(data.locationOfLot(SOLDIER)).contains(new HouseholdLocation.Unit("u-7"));
    assertThat(data.locationOfLot(PeopleLotId.parse("lot-missing"))).isEmpty();
  }

  @Test
  void ageBracketsReportCountsIncreaseAndDeathPerBracketAndSex() {
    SocialData data = fixture();

    List<AgeBracketView> views = data.ageBrackets(HEX_A, NOW, CLOCK);
    assertThat(views).as("恒为 3 档 × 2 性别（即使人数为 0，键也在）").hasSize(6);
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.ADULT.key()) && view.sex() == Sex.MALE)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.count()).isEqualTo(100L);
              assertThat(view.increaseRatePerTick()).isZero();
              assertThat(view.deathRatePerTick()).as("率表读数逐档带出").isEqualTo(7L);
            });
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.ADULT.key()) && view.sex() == Sex.FEMALE)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.count()).isEqualTo(50L);
              assertThat(view.increaseRatePerTick()).as("最小档才承载出生增加率").isZero();
              assertThat(view.deathRatePerTick()).isEqualTo(3L);
            });
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.CHILD.key()))
        .allSatisfy(view -> assertThat(view.count()).isZero());
    assertThat(data.ageBrackets(HouseholdId.parse("hh-missing"), NOW, CLOCK)).isEmpty();
  }

  @Test
  void lookupAdapterExposesTheSameReadingsAsTheState() {
    SocialData data = fixture();
    SocialLookupAdapter adapter = new SocialLookupAdapter(data, NOW, CLOCK);

    assertThat(adapter.household(HEX_A)).isPresent();
    assertThat(adapter.household(HEX_A).orElseThrow().location())
        .isEqualTo(new HouseholdLocation.Hex(H00));
    assertThat(adapter.household(HEX_A).orElseThrow().memberLots())
        .containsExactly(MAN, WOMAN);
    assertThat(adapter.household(HouseholdId.parse("hh-missing"))).isEmpty();

    assertThat(adapter.at(H00)).extracting(view -> view.id()).containsExactly(HEX_A);
    assertThat(adapter.inUnit("u-7")).extracting(view -> view.id()).containsExactly(UNIT);
    assertThat(adapter.populationAt(H00)).isEqualTo(data.populationAt(H00));
    assertThat(adapter.unitPopulation("u-7")).isEqualTo(data.unitPopulation("u-7"));
    assertThat(adapter.householdPopulation(HEX_A)).isEqualTo(data.householdPopulation(HEX_A));
    assertThat(adapter.ageBrackets(HEX_A))
        .as("SPI 的年龄档与状态本体同一读数")
        .isEqualTo(data.ageBrackets(HEX_A, NOW, CLOCK));
    assertThat(adapter.ageBrackets(HouseholdId.parse("hh-missing"))).isEmpty();
  }

  private static SocialData fixture() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, HEX_A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    data = HouseholdBook.create(data, HEX_B, new HouseholdLocation.Hex(H10), profile("乙"), rates());
    data = HouseholdBook.create(data, UNIT, new HouseholdLocation.Unit("u-7"), profile("丙"), rates());

    data = HouseholdBook.addMembers(data, HEX_A, MAN, Sex.MALE, 100L, 30L * YEAR, 0L, "seed");
    data = HouseholdBook.addMembers(data, HEX_A, WOMAN, Sex.FEMALE, 50L, 30L * YEAR, 0L, "seed");
    data = HouseholdBook.addMembers(data, HEX_B, OTHER_MAN, Sex.MALE, 7L, 30L * YEAR, 0L, "seed");
    data = HouseholdBook.addMembers(data, UNIT, SOLDIER, Sex.FEMALE, 40L, 25L * YEAR, 0L, "seed");

    return HouseholdBook.setVitalRates(
        data,
        HEX_A,
        rates(
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 7L),
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 0L, 3L)),
        "率表");
  }

  private static HouseholdVitalRates rates(HouseholdVitalRate... rates) {
    return new HouseholdVitalRates(List.of(rates));
  }

  private static HouseholdProfile profile(String name) {
    return new HouseholdProfile(name, null, Map.of());
  }
}
