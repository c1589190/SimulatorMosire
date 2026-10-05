package io.mosire.simos.gov;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S2 家用夹具：gov 测试里"批次自带落点"→"家户给位置"（架构 §4.2）。 */
final class GovSocialDataFixture {

  private GovSocialDataFixture() {}

  static SocialData withHouseholdsAt(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups,
      Map<PeopleLotId, HexCoord> locations) {
    Map<HexCoord, List<PeopleLotId>> lotsByHex = new LinkedHashMap<>();
    for (PeopleLotId lot : groups.keySet()) {
      HexCoord at = locations.get(lot);
      if (at == null) {
        throw new IllegalArgumentException("gov 测试夹具缺批次落点: " + lot);
      }
      lotsByHex.computeIfAbsent(at, ignored -> new ArrayList<>()).add(lot);
    }
    Map<HouseholdId, Household> households = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<PeopleLotId>> entry : lotsByHex.entrySet()) {
      HouseholdId householdId = HouseholdId.parse("hh:" + entry.getKey());
      // ★ P2-A：成员表 = (lot → count) 份额表；单户持整批 ⇒ count = 批次人数（守恒）。
      Map<PeopleLotId, Long> members = new LinkedHashMap<>();
      for (PeopleLotId lot : entry.getValue()) {
        members.put(lot, groups.get(lot).count());
      }
      households.put(
          householdId,
          new Household(
              householdId,
              new HouseholdLocation.Hex(entry.getKey()),
              new HouseholdProfile(householdId.value(), null, Map.of()),
              members,
              new HouseholdVitalRates(List.of())));
    }
    return new SocialData(populations, cities, groups, households, Map.of());
  }
}
