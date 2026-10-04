package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.UnitHouseholdWorldFixture;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.household.SocialLookupAdapter;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>S3a 正式验收：ApiViews 的 unit 详情/清单读口</b>（S3a spec §5/§7；任务书 B7）。
 *
 * <ul>
 *   <li>带 lookup：{@code households[]}（逐项 id/name/location/memberLots/population）+ {@code population}（实时汇总）；
 *   <li>旧无 lookup 重载：{@code households[]} 只发 id、{@code population=null}（"没有注入"必须与"是 0 人"可分）；
 *   <li>GOV 编制视图的 {@code module.households} 与顶层 households 同序同值。
 * </ul>
 */
class ApiViewsUnitHouseholdTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final UnitId GOV = new UnitId("u-gov");
  private static final UnitId EMPTY = new UnitId("u-empty");
  private static final HouseholdId HH_ONE = HouseholdId.parse("hh-one");
  private static final HouseholdId HH_TWO = HouseholdId.parse("hh-two");

  @Test
  void unitDetailAndListExposeHouseholdsAndLivePopulation() {
    UnitState units = unitState();
    SocialLookupAdapter lookup = lookup(twoHouseholdSocial());

    Map<String, Object> detail =
        ApiViews.unit(
            units.units().get(GOV),
            units,
            T0,
            UnitHouseholdWorldFixture.map(),
            SdState.empty(),
            CalendarService.defaults(),
            lookup,
            lookup);

    assertThat(detail.get("population")).as("实时人口 = 5 + 3").isEqualTo(8L);
    List<Map<String, Object>> households = householdsOf(detail);
    assertThat(idsOf(households)).as("保序原样透出").containsExactly(HH_ONE.value(), HH_TWO.value());
    assertThat(households.get(0))
        .as("注入 lookup 时补 name/location/memberLots/population")
        .containsEntry("name", "甲户")
        .containsEntry("location", "UNIT:u-gov")
        .containsEntry("memberLots", 1)
        .containsEntry("population", 5L);
    assertThat(households.get(1)).containsEntry("name", "乙户").containsEntry("population", 3L);

    @SuppressWarnings("unchecked")
    Map<String, Object> module = (Map<String, Object>) detail.get("module");
    assertThat((List<String>) module.get("households"))
        .as("GOV 编制视图 households 与顶层一致")
        .containsExactly(HH_ONE.value(), HH_TWO.value());

    List<Map<String, Object>> list =
        ApiViews.units(
            units,
            T0,
            UnitHouseholdWorldFixture.map(),
            SdState.empty(),
            CalendarService.defaults(),
            lookup,
            lookup);
    assertThat(list).hasSize(2);
    Map<String, Object> listGov =
        list.stream().filter(row -> GOV.value().equals(row.get("id"))).findFirst().orElseThrow();
    assertThat(listGov.get("population")).isEqualTo(8L);
    assertThat(idsOf(householdsOf(listGov))).containsExactly(HH_ONE.value(), HH_TWO.value());

    Map<String, Object> listEmpty =
        list.stream().filter(row -> EMPTY.value().equals(row.get("id"))).findFirst().orElseThrow();
    assertThat(listEmpty.get("households")).as("空家户列表也发（空数组不是键缺席）").isEqualTo(List.of());
    assertThat(listEmpty.get("population")).as("注入 lookup 后是 0（不是 null）").isEqualTo(0L);
  }

  @Test
  void legacyOverloadWithoutLookupKeepsPopulationNullAndHouseholdRowsIdOnly() {
    UnitState units = unitState();

    Map<String, Object> detail =
        ApiViews.unit(
            units.units().get(GOV),
            units,
            T0,
            UnitHouseholdWorldFixture.map(),
            SdState.empty(),
            CalendarService.defaults());

    assertThat(detail.get("population")).isNull();
    List<Map<String, Object>> households = householdsOf(detail);
    assertThat(idsOf(households)).containsExactly(HH_ONE.value(), HH_TWO.value());
    assertThat(households.get(0).keySet()).as("旧无 lookup 重载只发 id").containsExactly("id");

    List<Map<String, Object>> list =
        ApiViews.units(
            units,
            T0,
            UnitHouseholdWorldFixture.map(),
            SdState.empty(),
            CalendarService.defaults());
    Map<String, Object> govRow =
        list.stream().filter(row -> GOV.value().equals(row.get("id"))).findFirst().orElseThrow();
    assertThat(govRow.get("population")).isNull();
    assertThat(householdsOf(govRow).get(0).keySet()).containsExactly("id");
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────

  private static UnitState unitState() {
    Unit gov =
        UnitHouseholdWorldFixture.govUnit(
            List.of(HH_ONE, HH_TWO), List.of(HH_ONE, HH_TWO), Optional.empty());
    Unit empty = UnitHouseholdWorldFixture.plainUnit(EMPTY);
    return new UnitState(new LinkedHashMap<>(Map.of(GOV, gov, EMPTY, empty)));
  }

  private static SocialData twoHouseholdSocial() {
    SocialData social =
        HouseholdBook.create(
            SocialData.empty(),
            HH_ONE,
            new HouseholdLocation.Unit(GOV.value()),
            new HouseholdProfile("甲户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    social = HouseholdBook.addMembers(social, HH_ONE, PeopleLotId.parse("lot-one"), Sex.MALE, 5L, 0L, 0L, "seed");
    social =
        HouseholdBook.create(
            social,
            HH_TWO,
            new HouseholdLocation.Unit(GOV.value()),
            new HouseholdProfile("乙户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    return HouseholdBook.addMembers(social, HH_TWO, PeopleLotId.parse("lot-two"), Sex.FEMALE, 3L, 0L, 0L, "seed");
  }

  private static SocialLookupAdapter lookup(SocialData social) {
    return new SocialLookupAdapter(social, T0.tick(), CalendarClock.julianDefault());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> householdsOf(Map<String, Object> view) {
    return (List<Map<String, Object>>) view.get("households");
  }

  private static List<String> idsOf(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> (String) row.get("id")).toList();
  }
}
