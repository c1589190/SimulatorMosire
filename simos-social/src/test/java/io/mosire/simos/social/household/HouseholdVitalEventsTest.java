package io.mosire.simos.social.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.AgeBracketView;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S2 率与事件验收（架构 §4.3/§7 第 4、5 条）：逐家户率、逐日死亡按年龄段、出生只从育龄女性且并入最小档、
 * 同日生死互不干扰、重复结算被拒。
 */
class HouseholdVitalEventsTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final long YEAR = 365L;
  private static final CalendarClock CLOCK = CalendarClock.julianDefault();

  private static final HouseholdId A = HouseholdId.parse("hh-a");
  private static final HouseholdId B = HouseholdId.parse("hh-b");
  private static final HouseholdId C = HouseholdId.parse("hh-c");
  private static final HexCoord H20 = new HexCoord(2, 0);

  @Test
  void deathsFollowTheAgeBracketAndSexRate() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    PeopleLotId child = PeopleLotId.parse("child-male");
    PeopleLotId adult = PeopleLotId.parse("adult-male");
    PeopleLotId female = PeopleLotId.parse("adult-female");
    PeopleLotId elder = PeopleLotId.parse("elder-male");
    data = add(data, A, child, Sex.MALE, 10L, 5L * YEAR);
    data = add(data, A, adult, Sex.MALE, 100L, 20L * YEAR);
    data = add(data, A, female, Sex.FEMALE, 40L, 30L * YEAR);
    data = add(data, A, elder, Sex.MALE, 5L, 70L * YEAR);
    data =
        HouseholdBook.setVitalRates(
            data,
            A,
            rates(
                new HouseholdVitalRate(AgeBracket.CHILD.key(), Sex.MALE, 0L, 1000L),
                new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 100L),
                new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 0L, 50L),
                new HouseholdVitalRate(AgeBracket.ELDER.key(), Sex.MALE, 0L, 200L)),
            "率表");

    SocialData after = HouseholdBook.settleVitalEvents(data, 0L);

    assertThat(after.groups()).as("10×1000‰ = 10 ⇒ 该批次减到 0 被删").doesNotContainKey(child);
    assertThat(after.groups().get(adult).count()).as("100×100‰ = 10").isEqualTo(90L);
    assertThat(after.groups().get(female).count()).as("40×50‰ = 2").isEqualTo(38L);
    assertThat(after.groups().get(elder).count()).as("5×200‰ = 1").isEqualTo(4L);

    List<HouseholdPopulationEvent> deaths =
        after.populationEvents().values().stream()
            .filter(event -> event.type() == PopulationEventType.DEATH)
            .toList();
    assertThat(deaths).as("4 个有死亡率的批次各一条 DEATH").hasSize(4);
    assertThat(deaths)
        .allSatisfy(
            event -> {
              assertThat(event.id()).startsWith("death:" + A + ":0:");
              assertThat(event.ageBracketId()).isNotBlank();
              assertThat(event.count()).isPositive();
              assertThat(event.lotId()).isNotNull();
            });
    assertThat(deaths)
        .extracting(HouseholdPopulationEvent::lotId)
        .containsExactlyInAnyOrder(adult, female, elder, child);
    assertThat(deaths)
        .extracting(HouseholdPopulationEvent::count)
        .containsExactlyInAnyOrder(1L, 10L, 2L, 10L);
  }

  @Test
  void birthsOnlyFromFertileFemaleBatchesAndLandInTheYoungestBracket() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    data = HouseholdBook.create(data, B, new HouseholdLocation.Hex(H10), profile("乙"), rates());
    data = HouseholdBook.create(data, C, new HouseholdLocation.Hex(H20), profile("丙"), rates());

    PeopleLotId aMother = PeopleLotId.parse("a-mother");
    PeopleLotId aFather = PeopleLotId.parse("a-father");
    PeopleLotId aGirl = PeopleLotId.parse("a-girl");
    PeopleLotId bMother = PeopleLotId.parse("b-mother");
    PeopleLotId bGirl = PeopleLotId.parse("b-girl");
    PeopleLotId bBoy = PeopleLotId.parse("b-boy");
    PeopleLotId bGrandma = PeopleLotId.parse("b-grandma");

    data = add(data, A, aMother, Sex.FEMALE, 50L, 20L * YEAR);
    data = add(data, A, aFather, Sex.MALE, 100L, 20L * YEAR);
    data = add(data, C, aGirl, Sex.FEMALE, 30L, 5L * YEAR); // 非育龄（C 户没有任一年龄的出生率）
    data = add(data, B, bMother, Sex.FEMALE, 25L, 30L * YEAR);
    data = add(data, B, bGirl, Sex.FEMALE, 4L, 6L * YEAR); // 已有最小档女性批次
    data = add(data, B, bBoy, Sex.MALE, 3L, 7L * YEAR); // 已有最小档男性批次
    data = add(data, C, bGrandma, Sex.FEMALE, 100L, 70L * YEAR); // 非育龄

    HouseholdVitalRates fertile =
        rates(
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 400L, 0L),
            // ★ 男性也给出生率：实现必须只认 FEMALE（这一条把"性别真的进了生育判定"钉住）。
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 400L, 0L));
    data = HouseholdBook.setVitalRates(data, A, fertile, "甲率");
    data = HouseholdBook.setVitalRates(data, B, fertile, "乙率");
    // C 刻意不设任何出生率：它的 CHILD/ELDER 女性一个人都不该多出来。

    SocialData after = HouseholdBook.settleVitalEvents(data, 0L);

    // 甲：50×400‰ = 20 ⇒ 男 10 / 女 10；无既有最小档 ⇒ 新建两个 age=0 的批次。
    assertThat(after.groups().get(aFather).count()).as("男性批次不因出生率生孩子").isEqualTo(100L);
    assertThat(after.requireHousehold(A).memberLots()).as("甲户 = 父母 + 两个新生儿").hasSize(4);
    List<PeopleLotId> aChildren =
        after.requireHousehold(A).memberLots().stream()
            .filter(lot -> lot.value().startsWith("evt:birth:"))
            .toList();
    assertThat(aChildren).as("甲户新生男/女两个批次").hasSize(2);
    assertThat(aChildren)
        .allSatisfy(
            lot -> {
              assertThat(after.groups().get(lot).ageAtAnchorDays()).as("新生儿锚点年龄 = 0").isZero();
              assertThat(after.groups().get(lot).anchorTick()).isEqualTo(0L);
            });
    assertThat(aChildren)
        .extracting(lot -> after.groups().get(lot).sex())
        .containsExactlyInAnyOrder(Sex.MALE, Sex.FEMALE);
    assertThat(aChildren)
        .extracting(lot -> after.groups().get(lot).count())
        .containsExactlyInAnyOrder(10L, 10L);

    // 乙：25×400‰ = 10 ⇒ 男 5 / 女 5；已有最小档 ⇒ 并入既有 CHILD 批次（不新建身份）。
    assertThat(after.groups().get(bGirl).count()).as("4 + 5").isEqualTo(9L);
    assertThat(after.groups().get(bBoy).count()).as("3 + 5").isEqualTo(8L);
    assertThat(after.groups().get(bGrandma).count()).as("非育龄不生孩子").isEqualTo(100L);
    assertThat(after.requireHousehold(B).memberLots()).containsExactly(bMother, bGirl, bBoy);
    assertThat(after.groups().get(aGirl).count()).as("非育龄 CHILD 批次不生孩子").isEqualTo(30L);
    assertThat(after.groups().get(bGrandma).count()).as("非育龄 ELDER 批次不生孩子").isEqualTo(100L);

    List<HouseholdPopulationEvent> births =
        after.populationEvents().values().stream()
            .filter(event -> event.type() == PopulationEventType.BIRTH)
            .toList();
    assertThat(births).as("甲 2 条 + 乙 2 条").hasSize(4);
    assertThat(births)
        .allSatisfy(
            event -> {
              assertThat(event.ageBracketId()).isEqualTo(AgeBracket.CHILD.key());
              assertThat(event.lotId()).as("BIRTH 是按最小档/既有档聚合，不指向母亲批次").isNull();
              assertThat(event.count()).isPositive();
            });
    assertThat(births)
        .extracting(HouseholdPopulationEvent::householdId)
        .containsExactlyInAnyOrder(A, A, B, B);
    assertThat(
            after.ageBrackets(A, 0L, CLOCK).stream()
                .filter(view -> view.bracketId().equals(AgeBracket.CHILD.key()))
                .mapToLong(AgeBracketView::count)
                .sum())
        .as("甲户最小档总人数 = 20")
        .isEqualTo(20L);
    assertThat(
            after.ageBrackets(B, 0L, CLOCK).stream()
                .filter(view -> view.bracketId().equals(AgeBracket.CHILD.key()))
                .mapToLong(AgeBracketView::count)
                .sum())
        .as("乙户最小档总人数 = 4+3+10")
        .isEqualTo(17L);
  }

  @Test
  void birthAndDeathAreComputedFromTheSamePreSettlementCounts() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    PeopleLotId mother = PeopleLotId.parse("mother");
    data = add(data, A, mother, Sex.FEMALE, 100L, 30L * YEAR);
    data =
        HouseholdBook.setVitalRates(
            data,
            A,
            rates(new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 100L, 100L)),
            "率表");

    SocialData after = HouseholdBook.settleVitalEvents(data, 0L);

    assertThat(after.groups().get(mother).count()).as("死亡 100×100‰ = 10").isEqualTo(90L);
    long births =
        after.populationEvents().values().stream()
            .filter(event -> event.type() == PopulationEventType.BIRTH)
            .mapToLong(HouseholdPopulationEvent::count)
            .sum();
    assertThat(births)
        .as("出生也按结算前 100 人算 ⇒ 10（若按死后 90 算会得 9，本条当场红）")
        .isEqualTo(10L);
    assertThat(
            after.ageBrackets(A, 0L, CLOCK).stream()
                .filter(view -> view.bracketId().equals(AgeBracket.CHILD.key()))
                .mapToLong(AgeBracketView::count)
                .sum())
        .isEqualTo(10L);
  }

  @Test
  void setVitalRatesAndAgeBracketsExposeCountsIncreaseAndDeathReadings() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    PeopleLotId girl = PeopleLotId.parse("girl");
    PeopleLotId man = PeopleLotId.parse("man");
    PeopleLotId grandma = PeopleLotId.parse("grandma");
    data = add(data, A, girl, Sex.FEMALE, 10L, 5L * YEAR);
    data = add(data, A, man, Sex.MALE, 20L, 30L * YEAR);
    data = add(data, A, grandma, Sex.FEMALE, 7L, 70L * YEAR);

    HouseholdVitalRates table =
        rates(
            new HouseholdVitalRate(AgeBracket.CHILD.key(), Sex.FEMALE, 100L, 2L),
            new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 5L),
            new HouseholdVitalRate(AgeBracket.ELDER.key(), Sex.FEMALE, 0L, 30L));
    data = HouseholdBook.setVitalRates(data, A, table, "率表");

    assertThat(data.requireHousehold(A).vitalRates()).isEqualTo(table);
    assertThat(data.populationEvents().values())
        .anySatisfy(
            event -> {
              assertThat(event.type()).isEqualTo(PopulationEventType.RATE_SET);
              assertThat(event.householdId()).isEqualTo(A);
            });

    List<AgeBracketView> views = data.ageBrackets(A, 0L, CLOCK);
    assertThat(views).as("3 档 × 2 性别，键齐").hasSize(6);
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.CHILD.key()) && view.sex() == Sex.FEMALE)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.count()).isEqualTo(10L);
              assertThat(view.increaseRatePerTick()).as("最小档的 increase = 该档出生率").isEqualTo(100L);
              assertThat(view.deathRatePerTick()).isEqualTo(2L);
            });
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.ADULT.key()) && view.sex() == Sex.MALE)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.count()).isEqualTo(20L);
              assertThat(view.increaseRatePerTick()).as("非最小档：增加由事件表达，不是率").isZero();
              assertThat(view.deathRatePerTick()).isEqualTo(5L);
            });
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.ELDER.key()) && view.sex() == Sex.FEMALE)
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.count()).isEqualTo(7L);
              assertThat(view.deathRatePerTick()).isEqualTo(30L);
            });
    assertThat(views)
        .filteredOn(view -> view.bracketId().equals(AgeBracket.ADULT.key()) && view.sex() == Sex.FEMALE)
        .singleElement()
        .satisfies(view -> assertThat(view.count()).isZero());
  }

  @Test
  void settlingTheSameDayTwiceIsRejectedByEventIdGuard() {
    SocialData data = SocialData.empty();
    data = HouseholdBook.create(data, A, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    data = add(data, A, PeopleLotId.parse("mother"), Sex.FEMALE, 100L, 30L * YEAR);
    data =
        HouseholdBook.setVitalRates(
            data,
            A,
            rates(new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 100L, 100L)),
            "率表");
    SocialData once = HouseholdBook.settleVitalEvents(data, 0L);

    assertThatThrownBy(() -> HouseholdBook.settleVitalEvents(once, 0L))
        .as("同一天重复结算不是静默重复出生/死亡")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在");
  }

  private static SocialData add(
      SocialData data, HouseholdId household, PeopleLotId lot, Sex sex, long count, long ageDays) {
    return HouseholdBook.addMembers(data, household, lot, sex, count, ageDays, 0L, "创世播种");
  }

  private static HouseholdVitalRates rates(HouseholdVitalRate... rates) {
    return new HouseholdVitalRates(List.of(rates));
  }

  private static HouseholdProfile profile(String name) {
    return new HouseholdProfile(name, null, Map.of());
  }
}
