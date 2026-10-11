package io.mosire.simos.social.household;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.FamineMortality;
import io.mosire.simos.social.population.FamineMortalityInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W3 的 {@code famineDeaths} 口径判别力夹具（"人数" vs "条数"）</b>。
 *
 * <p>★★ <b>为什么必须专门造一个夹具</b>：设计书 v3.12 §7 的 D2 裁定 + W3-fix3 把 {@code famineDeaths} 从"数事件**条数**"改成
 * "累加那些事件的 {@code count}（**人数**）"。但在<b>可达世界</b>里每条 {@code DEATH} 事件的 {@code count} 恒为 1 （{@code
 * HouseholdBookTest} / W3 验收实测：sw180 的 428 死 = 428 条事件）⇒ 两种口径**同值**、既有断言没有判别力 （W3-fix3 账本 §W3f3-7 第
 * 3 条点名）。
 *
 * <p>★★ <b>本夹具怎么造出判别力</b>：让"一条 DEATH 事件携带多人"。死亡人数 = {@code ⌊(初相位余数 + 份额 × 有效死亡率ppm) ÷ 10^6⌋}（{@code
 * HouseholdBook} 的每 tick 生死引擎），故"大份额 × 大率"就得到一条多人事件。为了让结果 <b>不受稳定哈希初相位影响</b>，份额 × 率取成 {@code 10^6}
 * 的整数倍（与 {@code HouseholdVitalEventsTest} 同一手法）：
 *
 * <pre>
 * 甲户（饥荒参与）： 份额 100_000 人 × 有效率 1_000 ppm（基准 0 + 饥荒 1_000）⇒ 一条 DEATH count = 100，cause = BASE_PLUS_FAMINE
 * 乙户（纯基准）：   份额  10_000 人 × 有效率 1_000 ppm（基准 1_000，无饥荒报入）⇒ 一条 DEATH count =  10，cause = BASE
 * </pre>
 *
 * ⇒ {@code famineDeaths} 按人数 = <b>100</b>；按条数（旧口径）= <b>1</b>。本类断言前者 ⇒ 口径退回条数时**当场红**。
 *
 * <p>★ 判据出处：设计书 v3.12 §7 的 <b>D2 裁定</b>（"改为按死亡事件的 cause 直接计数，使 famineDeaths == famine 致死人数"） +
 * <b>W3-fix3</b>（控制方 2026-10-11 裁定：按**人数**，不是条数）+ §6.1 W3 行判据 ④⑤（缺口 = 0 ⇒ 饥荒项不触发）。
 *
 * <p>★ 缺口报入口径：{@code FamineMortalityInput.Shortage(rationGapMilli, rationNeedMilli, …)}，完全断顿（gap ==
 * need）⇒ {@code faminePpm = FAMINE_MAX_PPM_PER_TICK = 1000}（{@link FamineMortality}）。
 */
class FamineMortalityCountingTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final long YEAR = 365L;
  private static final CalendarClock CLOCK = CalendarClock.julianDefault();
  private static final long DAY = 0L;

  /** 饥荒参与的户。 */
  private static final HouseholdId FAMINE_HOUSEHOLD = HouseholdId.parse("hh-famine");

  /** 只有基准死亡的户（缺口 0，对照组）。 */
  private static final HouseholdId BASE_HOUSEHOLD = HouseholdId.parse("hh-base");

  @Test
  void famineDeathsCountsThePeopleOfAMultiPersonDeathEventNotTheEventRows() {
    SocialData data = SocialData.empty();
    data =
        HouseholdBook.create(
            data, FAMINE_HOUSEHOLD, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    data =
        HouseholdBook.create(
            data, BASE_HOUSEHOLD, new HouseholdLocation.Hex(H10), profile("乙"), rates());
    PeopleLotId famineLot = PeopleLotId.parse("famine-adult-male");
    PeopleLotId baseLot = PeopleLotId.parse("base-adult-male");
    data = add(data, FAMINE_HOUSEHOLD, famineLot, Sex.MALE, 100_000L, 20L * YEAR);
    data = add(data, BASE_HOUSEHOLD, baseLot, Sex.MALE, 10_000L, 20L * YEAR);
    // ★ 甲户基准死亡率 = 0 ⇒ 它的死亡**全部**来自饥荒项（faminePpm = 1000），cause 必为 BASE_PLUS_FAMINE。
    data =
        HouseholdBook.setVitalRates(
            data,
            FAMINE_HOUSEHOLD,
            rates(new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 0L)),
            "甲率（基准 0）");
    // ★ 乙户基准死亡率 = 1000 ppm ⇒ 一条纯 BASE 的多人事件（对照组：它**不**该进 famineDeaths）。
    data =
        HouseholdBook.setVitalRates(
            data,
            BASE_HOUSEHOLD,
            rates(new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 1_000L)),
            "乙率（纯基准）");

    // 甲户完全断顿（gap == need ⇒ faminePpm 打满 1_000）；乙户**不报**缺口（缺省中性）。
    FamineMortalityInput famine =
        new FamineMortalityInput(
            Map.of(FAMINE_HOUSEHOLD, new FamineMortalityInput.Shortage(1_000L, 1_000L, Map.of())));

    VitalSettlementResult result = HouseholdBook.settleVitalEventsResult(data, DAY, CLOCK, famine);

    List<HouseholdPopulationEvent> deaths =
        result.events().stream()
            .filter(event -> event.type() == PopulationEventType.DEATH)
            .toList();

    // ── 夹具自证：两条 DEATH 事件，且**饥荒那一条真的含多人**（判别力的前提） ──
    assertThat(deaths).as("两户各一条 DEATH 事件（各只有一个批次）").hasSize(2);
    HouseholdPopulationEvent famineDeath =
        deaths.stream()
            .filter(event -> event.householdId().equals(FAMINE_HOUSEHOLD))
            .findFirst()
            .orElseThrow();
    HouseholdPopulationEvent baseDeath =
        deaths.stream()
            .filter(event -> event.householdId().equals(BASE_HOUSEHOLD))
            .findFirst()
            .orElseThrow();
    assertThat(famineDeath.count()).as("★ 判别力的前提：这一条饥荒 DEATH 事件携带 100 人（不是 1 人）").isEqualTo(100L);
    assertThat(famineDeath.reason())
        .as("饥荒致死事件的 cause 具名在 reason 里")
        .contains("cause=BASE_PLUS_FAMINE");
    assertThat(famineDeath.reason()).as("饥荒死亡率打满 1000 ppm/tick").contains("faminePpm=1000");
    assertThat(baseDeath.count()).as("对照户一条事件 10 人").isEqualTo(10L);
    assertThat(baseDeath.reason()).as("对照户是纯基准死因").contains("cause=BASE");

    // ── 被断言的判据：famineDeaths = **人数**（100），不是**条数**（1） ──
    assertThat(result.deaths()).as("总死亡人数 = 100 + 10").isEqualTo(110L);
    assertThat(result.famineDeaths())
        .as(
            "★★ famineDeaths 必须 = 饥荒致死**人数** 100（W3-fix3 裁定）。"
                + "读数若是 1 ⇒ 口径退回「数事件条数」（本夹具正是为这条判别力而造）；"
                + "读数若是 110 ⇒ 口径退回「从总死亡里按 ppm 分摊」或把对照组也算进来了")
        .isEqualTo(100L);
    assertThat(result.famineDeaths())
        .as("★ 不变量：饥荒致死人数 ≤ 死亡人数（子集关系，结构性成立）")
        .isLessThanOrEqualTo(result.deaths());
    assertThat(result.deathsByHousehold())
        .as("逐户死亡原值：甲 100 / 乙 10")
        .isEqualTo(Map.of(FAMINE_HOUSEHOLD, 100L, BASE_HOUSEHOLD, 10L));
  }

  @Test
  void withoutAnyReportedGapTheFamineItemStaysZero() {
    SocialData data = SocialData.empty();
    data =
        HouseholdBook.create(
            data, FAMINE_HOUSEHOLD, new HouseholdLocation.Hex(H00), profile("甲"), rates());
    PeopleLotId lot = PeopleLotId.parse("adult-male");
    data = add(data, FAMINE_HOUSEHOLD, lot, Sex.MALE, 100_000L, 20L * YEAR);
    data =
        HouseholdBook.setVitalRates(
            data,
            FAMINE_HOUSEHOLD,
            rates(new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 1_000L)),
            "率表");

    // ★ 负向（§6.1 W3 判据④）：缺口 = 0 ⇒ 饥荒项不触发 ⇒ 死亡只走基准率、cause 全是 BASE。
    VitalSettlementResult result =
        HouseholdBook.settleVitalEventsResult(data, DAY, CLOCK, FamineMortalityInput.none());

    assertThat(result.famineDeaths()).as("没有缺口 ⇒ 饿死人数恒 0").isZero();
    assertThat(result.deaths()).as("基准死亡照常：100_000 × 1_000ppm = 100 人").isEqualTo(100L);
    assertThat(result.events())
        .filteredOn(event -> event.type() == PopulationEventType.DEATH)
        .allSatisfy(event -> assertThat(event.reason()).contains("cause=BASE"));
  }

  // ── 夹具助手（与 HouseholdVitalEventsTest 同形） ──────────────────────────────────────────

  private static SocialData add(
      SocialData data, HouseholdId household, PeopleLotId lot, Sex sex, long count, long ageDays) {
    return HouseholdBook.addMembers(data, household, lot, sex, count, ageDays, 0L, "W4b 夹具播种");
  }

  private static HouseholdVitalRates rates(HouseholdVitalRate... rates) {
    return new HouseholdVitalRates(List.of(rates));
  }

  private static HouseholdProfile profile(String name) {
    return new HouseholdProfile(name, null, Map.of());
  }
}
