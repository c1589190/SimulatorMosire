package io.mosire.simos.app.testing;

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

/**
 * app 测试的 S2 家户夹具：把"批次自带落点"的旧夹具翻译成"家户给位置"（架构 §4.2）。
 *
 * <p>用法：调用方给逐批次的 {@code lot → hex} 表，本类为每个 hex 造一个 {@code hh:<q>_<r>} 家户并挂上全部成员；
 * 旧读口（populationAt / groupsAt / ageStructureAt / urbanPopulationAt）因此保持同一批读数。
 *
 * <p>★ 只在测试里用；生产装配走 {@code PopulationSeeder} / {@code social.SeedGroups}。
 */
public final class SocialHouseholdFixture {

  private SocialHouseholdFixture() {}

  /** 按 {@code locations} 把批次挂到逐 hex 家户上。 */
  public static SocialData withHouseholdsAt(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups,
      Map<PeopleLotId, HexCoord> locations) {
    Map<HexCoord, List<PeopleLotId>> lotsByHex = new LinkedHashMap<>();
    for (PeopleLotId lot : groups.keySet()) {
      HexCoord at = locations.get(lot);
      if (at == null) {
        throw new IllegalArgumentException("测试夹具缺批次落点: " + lot);
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

  /** 单 hex 家户的简化入口：全部批次都落 {@code at}。 */
  public static SocialData withHouseholdsAt(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups,
      HexCoord at) {
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    for (PeopleLotId lot : groups.keySet()) {
      locations.put(lot, at);
    }
    return withHouseholdsAt(populations, cities, groups, locations);
  }
}
